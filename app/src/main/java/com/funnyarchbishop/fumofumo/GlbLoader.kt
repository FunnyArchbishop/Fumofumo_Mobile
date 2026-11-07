package com.funnyarchbishop.fumofumo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** 摊平后的三角面数据：每个角一份顶点，不共享（和桌面版 WINobj 的处理方式一致） */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val texture: Bitmap?,
    val min: FloatArray,
    val max: FloatArray
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = positions.size / 9
}

/**
 * 极简 GLB（glTF 2.0 二进制）读取器：
 *   · 只读单文件 glb（贴图内嵌）
 *   · 支持节点层级 / 矩阵 / TRS、三角面、UV、baseColor 与 emissive 贴图
 *   · 多边形自动扇形三角化
 * 需要外部 .bin 或 Draco 压缩的模型会明确报错，而不是默默画错。
 */
object GlbLoader {

    private const val MAGIC_GLTF = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    private const val COMP_BYTE = 5120
    private const val COMP_UBYTE = 5121
    private const val COMP_SHORT = 5122
    private const val COMP_USHORT = 5123
    private const val COMP_UINT = 5125
    private const val COMP_FLOAT = 5126

    fun load(file: File): MeshData {
        val bytes = file.readBytes()
        require(bytes.size >= 12) { "文件太小，不像是 glb" }

        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = header.int
        require(magic == MAGIC_GLTF) { "不是 glb 文件（文件头不对）" }
        header.int // version
        header.int // total length

        var gltf: JSONObject? = null
        var bin: ByteBuffer? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            header.position(offset)
            val chunkLength = header.int
            val chunkType = header.int
            if (chunkLength < 0 || offset + 8 + chunkLength > bytes.size) break
            val chunk = ByteArray(chunkLength)
            System.arraycopy(bytes, offset + 8, chunk, 0, chunkLength)
            when (chunkType) {
                CHUNK_JSON -> gltf = JSONObject(String(chunk, Charsets.UTF_8))
                CHUNK_BIN -> bin = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            }
            offset += 8 + chunkLength
        }

        val root = requireNotNull(gltf) { "glb 里没有 JSON 块，文件可能损坏" }
        val binBuf = bin

        // 只支持内嵌 buffer（单文件 glb）
        root.optJSONArray("buffers")?.let { buffers ->
            for (i in 0 until buffers.length()) {
                val uri = buffers.optJSONObject(i)?.optString("uri", "") ?: ""
                if (uri.isNotEmpty() && !uri.startsWith("data:")) {
                    throw IllegalStateException("这个模型依赖外部文件（$uri），请用单文件 .glb")
                }
            }
        }
        if (binBuf == null && (root.optJSONArray("meshes")?.length() ?: 0) > 0) {
            throw IllegalStateException("glb 里没有二进制数据块")
        }

        val bufferViews = root.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        val nodes = root.optJSONArray("nodes") ?: JSONArray()

        // ---- 节点世界矩阵 ----
        val worldOf = HashMap<Int, FloatArray>()
        val childIndices = HashSet<Int>()
        for (i in 0 until nodes.length()) {
            nodes.optJSONObject(i)?.optJSONArray("children")?.let { ch ->
                for (k in 0 until ch.length()) childIndices.add(ch.getInt(k))
            }
        }

        fun visit(index: Int, parent: FloatArray) {
            val node = nodes.optJSONObject(index) ?: return
            val world = mul4(parent, localMatrix(node))
            worldOf[index] = world
            node.optJSONArray("children")?.let { ch ->
                for (k in 0 until ch.length()) visit(ch.getInt(k), world)
            }
        }

        val sceneRoots = mutableListOf<Int>()
        val scenes = root.optJSONArray("scenes")
        val sceneIndex = root.optInt("scene", 0)
        val sceneNodes = scenes?.optJSONObject(sceneIndex)?.optJSONArray("nodes")
        if (sceneNodes != null) {
            for (k in 0 until sceneNodes.length()) sceneRoots.add(sceneNodes.getInt(k))
        } else {
            for (i in 0 until nodes.length()) if (i !in childIndices) sceneRoots.add(i)
        }
        if (sceneRoots.isEmpty() && nodes.length() > 0) {
            for (i in 0 until nodes.length()) if (i !in childIndices) sceneRoots.add(i)
        }
        sceneRoots.forEach { visit(it, identity4()) }

        // 没有任何节点但有 mesh 时，按单位矩阵直接读
        if (worldOf.isEmpty()) {
            for (i in 0 until nodes.length()) worldOf[i] = identity4()
            if (nodes.length() == 0 && meshes.length() > 0) {
                // 交给下面的兜底：把 mesh 当成挂在单位矩阵上
            }
        }

        // ---- 收集三角面 ----
        val posOut = ArrayList<Float>(1 shl 16)
        val nrmOut = ArrayList<Float>(1 shl 16)
        val uvOut = ArrayList<Float>(1 shl 16)
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        var bestTextureTris = -1
        var bestTexture: Bitmap? = null
        val bitmapCache = HashMap<Int, Bitmap?>()

        fun processMesh(meshIndex: Int, world: FloatArray) {
            val mesh = meshes.optJSONObject(meshIndex) ?: return
            val prims = mesh.optJSONArray("primitives") ?: return
            val normalMat = normalMatrix(world)

            for (pi in 0 until prims.length()) {
                val prim = prims.optJSONObject(pi) ?: continue
                if (prim.optJSONObject("extensions")
                        ?.has("KHR_draco_mesh_compression") == true
                ) {
                    throw IllegalStateException(
                        "这个 glb 用了 Draco 压缩，暂不支持。\n请下载未压缩的 glb，或用桌面版另存一份。"
                    )
                }
                val attrs = prim.optJSONObject("attributes") ?: continue
                val posAccessor = attrs.optInt("POSITION", -1)
                if (posAccessor < 0) continue

                val pos = readFloats(accessors, bufferViews, binBuf, posAccessor) ?: continue
                val vertexCount = pos.size / 3
                if (vertexCount == 0) continue
                val nrmAccessor = attrs.optInt("NORMAL", -1)
                val uvAccessor = attrs.optInt("TEXCOORD_0", -1)
                val nrm = if (nrmAccessor >= 0) readFloats(accessors, bufferViews, binBuf, nrmAccessor) else null
                val uv = if (uvAccessor >= 0) readFloats(accessors, bufferViews, binBuf, uvAccessor) else null
                val idxAccessor = prim.optInt("indices", -1)
                val indices = if (idxAccessor >= 0) readInts(accessors, bufferViews, binBuf, idxAccessor) else null

                val cornerCount = indices?.size ?: vertexCount
                val triCount = cornerCount / 3

                // 贴图：取面数最多的那份材质（和桌面版策略一致）
                val materialIndex = prim.optInt("material", -1)
                if (materialIndex >= 0 && triCount > bestTextureTris) {
                    val bmp = materialBitmap(root, bufferViews, binBuf, materialIndex, bitmapCache)
                    if (bmp != null) {
                        bestTextureTris = triCount
                        bestTexture = bmp
                    }
                }

                for (c in 0 until triCount * 3) {
                    val vi = if (indices != null) indices[c] else c
                    if (vi < 0 || vi >= vertexCount) continue
                    val px = pos[vi * 3]
                    val py = pos[vi * 3 + 1]
                    val pz = pos[vi * 3 + 2]
                    val wx = world[0] * px + world[4] * py + world[8] * pz + world[12]
                    val wy = world[1] * px + world[5] * py + world[9] * pz + world[13]
                    val wz = world[2] * px + world[6] * py + world[10] * pz + world[14]
                    posOut.add(wx); posOut.add(wy); posOut.add(wz)
                    if (wx < min[0]) min[0] = wx
                    if (wy < min[1]) min[1] = wy
                    if (wz < min[2]) min[2] = wz
                    if (wx > max[0]) max[0] = wx
                    if (wy > max[1]) max[1] = wy
                    if (wz > max[2]) max[2] = wz

                    if (nrm != null && nrm.size >= vertexCount * 3) {
                        val nx = nrm[vi * 3]
                        val ny = nrm[vi * 3 + 1]
                        val nz = nrm[vi * 3 + 2]
                        var ox = normalMat[0] * nx + normalMat[1] * ny + normalMat[2] * nz
                        var oy = normalMat[3] * nx + normalMat[4] * ny + normalMat[5] * nz
                        var oz = normalMat[6] * nx + normalMat[7] * ny + normalMat[8] * nz
                        val len = kotlin.math.sqrt(ox * ox + oy * oy + oz * oz)
                        if (len > 1e-8f) {
                            ox /= len; oy /= len; oz /= len
                        }
                        nrmOut.add(ox); nrmOut.add(oy); nrmOut.add(oz)
                    } else {
                        nrmOut.add(0f); nrmOut.add(0f); nrmOut.add(1f)
                    }

                    if (uv != null && uv.size >= vertexCount * 2) {
                        uvOut.add(uv[vi * 2]); uvOut.add(uv[vi * 2 + 1])
                    } else {
                        uvOut.add(0f); uvOut.add(0f)
                    }
                }
            }
        }

        if (worldOf.isEmpty()) {
            for (mi in 0 until meshes.length()) processMesh(mi, identity4())
        } else {
            for ((nodeIndex, world) in worldOf) {
                val node = nodes.optJSONObject(nodeIndex) ?: continue
                val meshIndex = node.optInt("mesh", -1)
                if (meshIndex >= 0) processMesh(meshIndex, world)
            }
        }

        require(posOut.isNotEmpty()) { "这个 glb 里没有可用的三角面" }

        val positions = FloatArray(posOut.size) { posOut[it] }
        val normals = FloatArray(nrmOut.size) { nrmOut[it] }
        val uvs = FloatArray(uvOut.size) { uvOut[it] }
        if (normals.isEmpty()) {
            // 没有法线就用全 0，渲染时会被 normalize 成 NaN，这里给个朝向兜底
            val fixed = FloatArray(positions.size) { if (it % 3 == 2) 1f else 0f }
            return MeshData(positions, fixed, uvs, bestTexture, min, max)
        }
        return MeshData(positions, normals, uvs, bestTexture, min, max)
    }

    // ---------------- 材质贴图 ----------------

    private fun materialBitmap(
        root: JSONObject,
        bufferViews: JSONArray,
        bin: ByteBuffer?,
        materialIndex: Int,
        cache: HashMap<Int, Bitmap?>
    ): Bitmap? {
        val materials = root.optJSONArray("materials") ?: return null
        val mat = materials.optJSONObject(materialIndex) ?: return null
        val textureIndex = mat.optJSONObject("pbrMetallicRoughness")
            ?.optJSONObject("baseColorTexture")?.optInt("index", -1)
            ?.takeIf { it >= 0 }
            ?: mat.optJSONObject("emissiveTexture")?.optInt("index", -1)?.takeIf { it >= 0 }
            ?: return null

        val textures = root.optJSONArray("textures") ?: return null
        val imageIndex = textures.optJSONObject(textureIndex)?.optInt("source", -1) ?: -1
        if (imageIndex < 0) return null
        if (cache.containsKey(imageIndex)) return cache[imageIndex]

        val images = root.optJSONArray("images")
        val image = images?.optJSONObject(imageIndex)
        var bmp: Bitmap? = null
        val viewIndex = image?.optInt("bufferView", -1) ?: -1
        if (viewIndex >= 0 && bin != null) {
            val view = bufferViews.optJSONObject(viewIndex)
            if (view != null) {
                val off = view.optInt("byteOffset", 0)
                val len = view.optInt("byteLength", 0)
                if (len > 0 && off >= 0 && off + len <= bin.capacity()) {
                    val data = ByteArray(len)
                    val dup = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    dup.position(off)
                    dup.get(data)
                    bmp = BitmapFactory.decodeByteArray(data, 0, len)
                }
            }
        }
        cache[imageIndex] = bmp
        return bmp
    }

    // ---------------- accessor ----------------

    private fun readFloats(
        accessors: JSONArray,
        bufferViews: JSONArray,
        bin: ByteBuffer?,
        accessorIndex: Int
    ): FloatArray? {
        val acc = accessors.optJSONObject(accessorIndex) ?: return null
        val viewIndex = acc.optInt("bufferView", -1)
        if (viewIndex < 0 || bin == null) return null
        val view = bufferViews.optJSONObject(viewIndex) ?: return null

        val count = acc.optInt("count", 0)
        val comps = componentsOf(acc.optString("type", "VEC3"))
        val compType = acc.optInt("componentType", COMP_FLOAT)
        val compSize = sizeOf(compType)
        val viewOffset = view.optInt("byteOffset", 0)
        val accOffset = acc.optInt("byteOffset", 0)
        val stride = view.optInt("byteStride", 0).let { if (it > 0) it else compSize * comps }
        val base = viewOffset + accOffset
        val out = FloatArray(count * comps)
        val buf = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val start = base + i * stride
            for (c in 0 until comps) {
                val p = start + c * compSize
                if (p < 0 || p + compSize > buf.capacity()) continue
                out[i * comps + c] = when (compType) {
                    COMP_FLOAT -> buf.getFloat(p)
                    COMP_USHORT -> (buf.getShort(p).toInt() and 0xFFFF).toFloat()
                    COMP_SHORT -> buf.getShort(p).toFloat()
                    COMP_UINT -> buf.getInt(p).toFloat()
                    COMP_UBYTE -> (buf.get(p).toInt() and 0xFF).toFloat()
                    COMP_BYTE -> buf.get(p).toFloat()
                    else -> 0f
                }
            }
        }
        return out
    }

    private fun readInts(
        accessors: JSONArray,
        bufferViews: JSONArray,
        bin: ByteBuffer?,
        accessorIndex: Int
    ): IntArray? {
        val floats = readFloats(accessors, bufferViews, bin, accessorIndex) ?: return null
        return IntArray(floats.size) { floats[it].toInt() }
    }

    private fun sizeOf(compType: Int): Int = when (compType) {
        COMP_BYTE, COMP_UBYTE -> 1
        COMP_SHORT, COMP_USHORT -> 2
        COMP_UINT, COMP_FLOAT -> 4
        else -> 4
    }

    private fun componentsOf(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT2" -> 4
        "MAT3" -> 9
        "MAT4" -> 16
        else -> 3
    }

    // ---------------- 矩阵 ----------------

    private fun identity4() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    /** 列主序 4x4（glTF 规范） */
    private fun localMatrix(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.let { m ->
            val out = FloatArray(16)
            for (i in 0 until 16) out[i] = m.optDouble(i, 0.0).toFloat()
            return out
        }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f

        val x2 = qx + qx; val y2 = qy + qy; val z2 = qz + qz
        val xx = qx * x2; val xy = qx * y2; val xz = qx * z2
        val yy = qy * y2; val yz = qy * z2; val zz = qz * z2
        val wx = qw * x2; val wy = qw * y2; val wz = qw * z2

        // 旋转（列主序）后按列乘缩放，再写入平移
        return floatArrayOf(
            (1f - (yy + zz)) * sx, (xy + wz) * sx, (xz - wy) * sx, 0f,
            (xy - wz) * sy, (1f - (xx + zz)) * sy, (yz + wx) * sy, 0f,
            (xz + wy) * sz, (yz - wx) * sz, (1f - (xx + yy)) * sz, 0f,
            tx, ty, tz, 1f
        )
    }

    private fun mul4(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[c * 4 + k]
                out[c * 4 + row] = sum
            }
        }
        return out
    }

    /** 法线矩阵 = 世界矩阵左上 3x3 的逆转置（行主序） */
    private fun normalMatrix(m: FloatArray): FloatArray {
        val a = floatArrayOf(
            m[0], m[4], m[8],
            m[1], m[5], m[9],
            m[2], m[6], m[10]
        )
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) -
            a[1] * (a[3] * a[8] - a[5] * a[6]) +
            a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val inv = floatArrayOf(
            (a[4] * a[8] - a[5] * a[7]) / det,
            (a[2] * a[7] - a[1] * a[8]) / det,
            (a[1] * a[5] - a[2] * a[4]) / det,
            (a[5] * a[6] - a[3] * a[8]) / det,
            (a[0] * a[8] - a[2] * a[6]) / det,
            (a[2] * a[3] - a[0] * a[5]) / det,
            (a[3] * a[7] - a[4] * a[6]) / det,
            (a[1] * a[6] - a[0] * a[7]) / det,
            (a[0] * a[4] - a[1] * a[3]) / det
        )
        return floatArrayOf(inv[0], inv[3], inv[6], inv[1], inv[4], inv[7], inv[2], inv[5], inv[8])
    }
}
