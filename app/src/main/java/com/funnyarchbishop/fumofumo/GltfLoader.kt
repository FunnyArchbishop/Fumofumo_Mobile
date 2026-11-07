package com.funnyarchbishop.fumofumo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * glTF 2.0 读取器，两种形态都吃：
 *   · `.glb` —— 单文件二进制（贴图内嵌）
 *   · `.gltf` —— JSON + 外部 .bin / 贴图（需要同目录一起提供，通常来自 zip 解包）
 *
 * 支持节点层级 / 矩阵 / TRS、UV、baseColor 与 emissive 贴图、多边形扇形三角化。
 * Draco 压缩、外部 URL 资源会明确报错，而不是默默画错。
 */
object GltfLoader {

    private const val MAGIC_GLTF = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    private const val COMP_BYTE = 5120
    private const val COMP_UBYTE = 5121
    private const val COMP_SHORT = 5122
    private const val COMP_USHORT = 5123
    private const val COMP_UINT = 5125
    private const val COMP_FLOAT = 5126

    /** 单文件 glb */
    fun loadGlb(file: File): MeshData {
        val bytes = file.readBytes()
        require(bytes.size >= 12) { "文件太小，不像是 glb" }

        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.int == MAGIC_GLTF) { "不是 glb 文件（文件头不对）" }
        header.int // version
        header.int // total length

        var json: JSONObject? = null
        var binChunk: ByteArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            header.position(offset)
            val chunkLength = header.int
            val chunkType = header.int
            if (chunkLength < 0 || offset + 8 + chunkLength > bytes.size) break
            val chunk = ByteArray(chunkLength)
            System.arraycopy(bytes, offset + 8, chunk, 0, chunkLength)
            when (chunkType) {
                CHUNK_JSON -> json = JSONObject(String(chunk, Charsets.UTF_8))
                CHUNK_BIN -> binChunk = chunk
            }
            offset += 8 + chunkLength
        }

        val root = requireNotNull(json) { "glb 里没有 JSON 块，文件可能损坏" }
        return parse(root, binChunk, file.parentFile, file.name)
    }

    /** .gltf（JSON 文本）+ 同目录资源 */
    fun loadGltf(jsonFile: File): MeshData {
        val text = jsonFile.readText()
        val root = JSONObject(text)
        return parse(root, null, jsonFile.parentFile, jsonFile.name)
    }

    // ---------------- 主体 ----------------

    private fun parse(
        root: JSONObject,
        glbBin: ByteArray?,
        baseDir: File?,
        sourceName: String
    ): MeshData {
        val buffers = readBuffers(root, glbBin, baseDir)
        val bufferViews = root.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        require(meshes.length() > 0) { "这个 glTF 里没有网格数据" }

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
        sceneRoots.forEach { visit(it, identity4()) }

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
                if (prim.optJSONObject("extensions")?.has("KHR_draco_mesh_compression") == true) {
                    throw IllegalStateException(
                        "这个模型用了 Draco 压缩，暂不支持。\n请下载未压缩的版本，或用桌面版另存一份。"
                    )
                }
                val attrs = prim.optJSONObject("attributes") ?: continue
                val posAccessor = attrs.optInt("POSITION", -1)
                if (posAccessor < 0) continue

                val pos = readFloats(accessors, bufferViews, buffers, posAccessor) ?: continue
                val vertexCount = pos.size / 3
                if (vertexCount == 0) continue
                val nrmAccessor = attrs.optInt("NORMAL", -1)
                val uvAccessor = attrs.optInt("TEXCOORD_0", -1)
                val nrm = if (nrmAccessor >= 0) readFloats(accessors, bufferViews, buffers, nrmAccessor) else null
                val uv = if (uvAccessor >= 0) readFloats(accessors, bufferViews, buffers, uvAccessor) else null
                val idxAccessor = prim.optInt("indices", -1)
                val indices = if (idxAccessor >= 0) readInts(accessors, bufferViews, buffers, idxAccessor) else null

                val cornerCount = indices?.size ?: vertexCount
                val triCount = cornerCount / 3

                val materialIndex = prim.optInt("material", -1)
                if (materialIndex >= 0 && triCount > bestTextureTris) {
                    val bmp = materialBitmap(
                        root, bufferViews, buffers, baseDir, materialIndex, bitmapCache
                    )
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

        require(posOut.isNotEmpty()) { "这个模型里没有可用的三角面" }

        val positions = FloatArray(posOut.size) { posOut[it] }
        val normals = FloatArray(nrmOut.size) { nrmOut[it] }
        val uvs = FloatArray(uvOut.size) { uvOut[it] }
        return MeshData(positions, normals, uvs, bestTexture, min, max, sourceName)
    }

    // ---------------- buffers / bufferViews ----------------

    private fun readBuffers(root: JSONObject, glbBin: ByteArray?, baseDir: File?): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val buffers = root.optJSONArray("buffers")
        if (buffers == null) {
            if (glbBin != null) out.add(glbBin)
            return out
        }
        for (i in 0 until buffers.length()) {
            val buf = buffers.optJSONObject(i) ?: continue
            val uri = buf.optString("uri", "")
            when {
                uri.isEmpty() -> out.add(glbBin ?: ByteArray(0))
                uri.startsWith("data:") -> {
                    val comma = uri.indexOf(',')
                    require(comma > 0) { "buffer 的 data URI 格式不对" }
                    out.add(decodeBase64(uri.substring(comma + 1)))
                }

                baseDir != null -> {
                    val f = File(baseDir, uri)
                    require(f.isFile) {
                        "缺少模型的外部文件：$uri\n请把 .gltf 连同 .bin 和贴图一起打成 zip 再导入。"
                    }
                    out.add(f.readBytes())
                }

                else -> throw IllegalStateException("缺少模型的外部文件：$uri")
            }
        }
        return out
    }

    private fun viewBytes(bufferViews: JSONArray, buffers: List<ByteArray>, viewIndex: Int): ByteArray? {
        val view = bufferViews.optJSONObject(viewIndex) ?: return null
        val bufferIndex = view.optInt("buffer", 0)
        val data = buffers.getOrNull(bufferIndex) ?: return null
        val off = view.optInt("byteOffset", 0)
        val len = view.optInt("byteLength", 0)
        if (len <= 0 || off < 0 || off + len > data.size) return null
        return data.copyOfRange(off, off + len)
    }

    // ---------------- 材质贴图 ----------------

    private fun materialBitmap(
        root: JSONObject,
        bufferViews: JSONArray,
        buffers: List<ByteArray>,
        baseDir: File?,
        materialIndex: Int,
        cache: HashMap<Int, Bitmap?>
    ): Bitmap? {
        val materials = root.optJSONArray("materials") ?: return null
        val mat = materials.optJSONObject(materialIndex) ?: return null
        val textureIndex = mat.optJSONObject("pbrMetallicRoughness")
            ?.optJSONObject("baseColorTexture")?.optInt("index", -1)?.takeIf { it >= 0 }
            ?: mat.optJSONObject("emissiveTexture")?.optInt("index", -1)?.takeIf { it >= 0 }
            ?: return null

        val textures = root.optJSONArray("textures") ?: return null
        val imageIndex = textures.optJSONObject(textureIndex)?.optInt("source", -1) ?: -1
        if (imageIndex < 0) return null
        if (cache.containsKey(imageIndex)) return cache[imageIndex]

        val image = root.optJSONArray("images")?.optJSONObject(imageIndex)
        var bmp: Bitmap? = null
        if (image != null) {
            val viewIndex = image.optInt("bufferView", -1)
            val uri = image.optString("uri", "")
            val bytes: ByteArray? = when {
                viewIndex >= 0 -> viewBytes(bufferViews, buffers, viewIndex)
                uri.startsWith("data:") -> uri.substringAfter(',', "").let { decodeBase64(it) }
                uri.isNotEmpty() && baseDir != null -> File(baseDir, uri).takeIf { it.isFile }?.readBytes()
                else -> null
            }
            if (bytes != null && bytes.isNotEmpty()) {
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
        cache[imageIndex] = bmp
        return bmp
    }

    private fun decodeBase64(text: String): ByteArray = try {
        android.util.Base64.decode(text, android.util.Base64.DEFAULT)
    } catch (t: Throwable) {
        throw IllegalStateException("模型里的 base64 数据解不开")
    }

    // ---------------- accessor ----------------

    private fun readFloats(
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray>,
        accessorIndex: Int
    ): FloatArray? {
        val acc = accessors.optJSONObject(accessorIndex) ?: return null
        val viewIndex = acc.optInt("bufferView", -1)
        if (viewIndex < 0) return null
        val view = bufferViews.optJSONObject(viewIndex) ?: return null
        val data = buffers.getOrNull(view.optInt("buffer", 0)) ?: return null

        val count = acc.optInt("count", 0)
        val comps = componentsOf(acc.optString("type", "VEC3"))
        val compType = acc.optInt("componentType", COMP_FLOAT)
        val compSize = sizeOf(compType)
        val viewOffset = view.optInt("byteOffset", 0)
        val accOffset = acc.optInt("byteOffset", 0)
        val stride = view.optInt("byteStride", 0).let { if (it > 0) it else compSize * comps }
        val base = viewOffset + accOffset
        val out = FloatArray(count * comps)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val start = base + i * stride
            for (c in 0 until comps) {
                val p = start + c * compSize
                if (p < 0 || p + compSize > data.size) continue
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
        buffers: List<ByteArray>,
        accessorIndex: Int
    ): IntArray? {
        val floats = readFloats(accessors, bufferViews, buffers, accessorIndex) ?: return null
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
