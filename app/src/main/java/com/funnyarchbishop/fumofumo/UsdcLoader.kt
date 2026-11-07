package com.funnyarchbishop.fumofumo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USDC（USD Crate 二进制格式）读取器 —— 只做桌宠需要的那部分：
 * 找到 Mesh prim，取出 points / faceVertexIndices / faceVertexCounts / primvars:st，
 * 累加祖先 Xform 变换，再从材质网络里找一张贴图。
 *
 * 格式参考：
 *  · Pixar OpenUSD `pxr/usd/sdf/crateFile.cpp`（权威实现）
 *  · @cinevva/usdjs 的 `src/usdc/parser.ts`（MIT，本移植的对照实现）
 *
 * 不支持的：数组编辑（ArrayEdits）、时间采样动画、点云/曲线、外部引用的其它层。
 */
object UsdcLoader {

    fun isUsdc(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x58.toByte() &&
            bytes[2] == 0x52.toByte() && bytes[3] == 0x2D.toByte() &&
            bytes[4] == 0x55.toByte() && bytes[5] == 0x53.toByte() &&
            bytes[6] == 0x44.toByte() && bytes[7] == 0x43.toByte()

    fun load(file: File): MeshData = load(file.readBytes(), file.parentFile, file.name)

    fun load(bytes: ByteArray, baseDir: File?, sourceName: String): MeshData {
        val crate = CrateFile(bytes)
        return crate.buildMesh(baseDir, sourceName)
    }

    /** 调试用：打印解析到的结构信息（单元测试里用） */
    fun debugInfo(file: File): String {
        val crate = CrateFile(file.readBytes())
        return crate.debugInfo()
    }

    // ================= 内部实现 =================

    private const val SPEC_ATTRIBUTE = 1
    private const val SPEC_PRIM = 6
    private const val SPEC_PSEUDO_ROOT = 7
    private const val SPEC_RELATIONSHIP = 8

    private const val T_BOOL = 1
    private const val T_INT = 3
    private const val T_UINT = 4
    private const val T_INT64 = 5
    private const val T_UINT64 = 6
    private const val T_FLOAT = 8
    private const val T_DOUBLE = 9
    private const val T_STRING = 10
    private const val T_TOKEN = 11
    private const val T_ASSET = 12
    private const val T_MATRIX4D = 15
    private const val T_VEC2D = 19
    private const val T_VEC2F = 20
    private const val T_VEC3D = 23
    private const val T_VEC3F = 24
    private const val T_VEC4F = 28
    private const val T_PATH_LIST_OP = 34
    private const val T_PATH_VECTOR = 40
    private const val T_TOKEN_VECTOR = 41
    private const val T_SPECIFIER = 42

    private class Section(val name: String, val start: Int, val size: Int)

    private class Spec(val pathIndex: Int, val fieldSetIndex: Int, val specType: Int)

    private class CrateFile(private val data: ByteArray) {

        private val buf: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        private val sections = HashMap<String, Section>()

        private var tokens: List<String> = emptyList()
        private var stringIndices: IntArray = IntArray(0)
        private var fieldTokenIndices: IntArray = IntArray(0)
        private var fieldReps: LongArray = LongArray(0)
        private var fieldSetIndices: IntArray = IntArray(0)
        private var paths: Array<String> = emptyArray()
        private var specs: List<Spec> = emptyList()

        // path -> 字段表（Prim 用）
        private val primFields = HashMap<String, HashMap<String, Any?>>()
        // 完整属性路径 -> 字段表（Attribute / Relationship 用）
        private val propFields = HashMap<String, HashMap<String, Any?>>()

        private fun parseAll() {
            val tocOffset = readHeader()
            readSections(tocOffset)
            readTokens()
            readStrings()
            readFields()
            readFieldSets()
            readPaths()
            readSpecs()
            collectSpecs()
        }

        fun debugInfo(): String {
            parseAll()
            val sb = StringBuilder()
            sb.appendLine("sections=${sections.keys.sorted()}")
            sb.appendLine("tokens=${tokens.size}")
            sb.appendLine("tokens[0..20]=${tokens.take(20)}")
            sb.appendLine("strings=${stringIndices.size}")
            sb.appendLine("fields=${fieldReps.size}")
            sb.appendLine("fieldSets=${fieldSetIndices.size}")
            sb.appendLine("paths=${paths.size} sample=${paths.take(12)}")
            sb.appendLine("specs=${specs.size} typeCounts=${specs.groupingBy { it.specType }.eachCount()}")
            sb.appendLine("prims=${primFields.size}")
            primFields.entries.take(10).forEach { (p, f) ->
                sb.appendLine("  prim $p typeName=${f["typeName"]} fields=${f.keys}")
            }
            sb.appendLine("props=${propFields.size} sample=${propFields.keys.take(10)}")
            return sb.toString()
        }

        fun buildMesh(baseDir: File?, sourceName: String): MeshData {
            parseAll()

            val positions = ArrayList<Float>(1 shl 16)
            val normals = ArrayList<Float>(1 shl 16)
            val uvs = ArrayList<Float>(1 shl 16)
            val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

            var bestTexture: Bitmap? = null
            var bestTextureTris = -1
            var meshCount = 0

            for ((path, fields) in primFields) {
                val typeName = fields["typeName"] as? String
                if (typeName != "Mesh") continue

                // points / faceVertexCounts / faceVertexIndices 在 USD 里是**属性**，不是 prim 字段
                val points = propFields["$path.points"]?.get("default") as? FloatArray ?: continue
                val counts = propFields["$path.faceVertexCounts"]?.get("default") as? IntArray ?: continue
                val indices = propFields["$path.faceVertexIndices"]?.get("default") as? IntArray ?: continue
                if (points.isEmpty() || counts.isEmpty() || indices.isEmpty()) continue
                if (counts.sum() != indices.size) continue

                val pointCount = points.size / 3
                if (indices.any { it < 0 || it >= pointCount }) continue

                val world = if (upAxis == "Z") {
                    mul4(zUpToYUp(), ancestorTransform(path))
                } else {
                    ancestorTransform(path)
                }
                meshCount++

                // UV
                var uvValues: FloatArray? = null
                var uvInterp = "vertex"
                val stKey = "$path.primvars:st"
                propFields[stKey]?.let { st ->
                    uvValues = st["default"] as? FloatArray
                    uvInterp = (st["interpolation"] as? String) ?: "vertex"
                }

                // 三角化（扇形），同时记录每个三角形用到的"角"序号
                val triCorner = ArrayList<Int>(indices.size * 2)
                var cursor = 0
                for (c in counts) {
                    if (c >= 3) {
                        for (k in 1 until c - 1) {
                            triCorner.add(cursor)
                            triCorner.add(cursor + k)
                            triCorner.add(cursor + k + 1)
                        }
                    }
                    cursor += c
                }
                if (triCorner.isEmpty()) continue

                val faceOfCorner = IntArray(indices.size)
                run {
                    var fc = 0
                    for (f in counts.indices) {
                        repeat(counts[f]) { faceOfCorner[fc++] = f }
                    }
                }

                for (corner in triCorner) {
                    val vi = indices[corner]
                    val px = points[vi * 3]
                    val py = points[vi * 3 + 1]
                    val pz = points[vi * 3 + 2]
                    val wx = world[0] * px + world[4] * py + world[8] * pz + world[12]
                    val wy = world[1] * px + world[5] * py + world[9] * pz + world[13]
                    val wz = world[2] * px + world[6] * py + world[10] * pz + world[14]
                    positions.add(wx); positions.add(wy); positions.add(wz)
                    if (wx < min[0]) min[0] = wx
                    if (wy < min[1]) min[1] = wy
                    if (wz < min[2]) min[2] = wz
                    if (wx > max[0]) max[0] = wx
                    if (wy > max[1]) max[1] = wy
                    if (wz > max[2]) max[2] = wz
                    normals.add(0f); normals.add(0f); normals.add(1f)

                    val uv = when {
                        uvValues == null -> 0f
                        uvInterp == "faceVarying" || uvInterp == "varying" ->
                            if (uvValues.size >= (corner + 1) * 2) uvValues[corner * 2] else 0f
                        uvInterp == "uniform" ->
                            if (uvValues.size >= (faceOfCorner[corner] + 1) * 2)
                                uvValues[faceOfCorner[corner] * 2] else 0f
                        uvInterp == "constant" -> uvValues.getOrElse(0) { 0f }
                        else -> if (uvValues.size >= (vi + 1) * 2) uvValues[vi * 2] else 0f
                    }
                    uvs.add(uv)
                    val vv = when {
                        uvValues == null -> 0f
                        uvInterp == "faceVarying" || uvInterp == "varying" ->
                            if (uvValues.size >= (corner + 1) * 2) uvValues[corner * 2 + 1] else 0f
                        uvInterp == "uniform" ->
                            if (uvValues.size >= (faceOfCorner[corner] + 1) * 2)
                                uvValues[faceOfCorner[corner] * 2 + 1] else 0f
                        uvInterp == "constant" -> uvValues.getOrElse(1) { 0f }
                        else -> if (uvValues.size >= (vi + 1) * 2) uvValues[vi * 2 + 1] else 0f
                    }
                    uvs.add(vv)
                }

                // 贴图
                val tris = triCorner.size / 3
                if (tris > bestTextureTris) {
                    val bmp = findTexture(path, baseDir)
                    if (bmp != null) {
                        bestTextureTris = tris
                        bestTexture = bmp
                    }
                }
            }

            require(meshCount > 0) { "USD 场景里没有找到网格（Mesh）" }
            require(positions.isNotEmpty()) { "USD 网格里没有可用的三角面" }

            // 法线：没有 NORMAL 属性，用面法线累加出平滑法线
            val posArr = FloatArray(positions.size) { positions[it] }
            val uvArr = FloatArray(uvs.size) { uvs[it] }
            val nrmArr = computeSmoothNormals(posArr)

            return MeshData(posArr, nrmArr, uvArr, bestTexture, min, max, sourceName)
        }

        private fun computeSmoothNormals(pos: FloatArray): FloatArray {
            val out = FloatArray(pos.size)
            var i = 0
            while (i + 8 < pos.size) {
                val ax = pos[i]; val ay = pos[i + 1]; val az = pos[i + 2]
                val bx = pos[i + 3]; val by = pos[i + 4]; val bz = pos[i + 5]
                val cx = pos[i + 6]; val cy = pos[i + 7]; val cz = pos[i + 8]
                val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
                val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
                val nx = e1y * e2z - e1z * e2y
                val ny = e1z * e2x - e1x * e2z
                val nz = e1x * e2y - e1y * e2x
                for (k in 0 until 3) {
                    out[i + k * 3] += nx
                    out[i + k * 3 + 1] += ny
                    out[i + k * 3 + 2] += nz
                }
                i += 9
            }
            var j = 0
            while (j < out.size) {
                val len = kotlin.math.sqrt(out[j] * out[j] + out[j + 1] * out[j + 1] + out[j + 2] * out[j + 2])
                if (len > 1e-8f) {
                    out[j] /= len; out[j + 1] /= len; out[j + 2] /= len
                } else {
                    out[j] = 0f; out[j + 1] = 0f; out[j + 2] = 1f
                }
                j += 3
            }
            return out
        }

        // ---------- 贴图 ----------

        private fun findTexture(meshPath: String, baseDir: File?): Bitmap? {
            val candidates = ArrayList<String>()
            val binding = propFields["$meshPath.material:binding"]
            val targets = binding?.get("targetPaths") as? Array<*>
            val materialPaths = ArrayList<String>()
            targets?.forEach { t -> (t as? String)?.let { materialPaths.add(it) } }
            if (materialPaths.isEmpty()) {
                // 没有显式绑定：退一步用场景里任意一个材质
                primFields.keys.filter { primFields[it]?.get("typeName") == "Material" }
                    .forEach { materialPaths.add(it) }
            }

            for (matPath in materialPaths) {
                // 材质子树里所有 inputs:file
                for ((propPath, fields) in propFields) {
                    if (!propPath.startsWith("$matPath/") && !propPath.startsWith("$matPath.")) continue
                    val file = fields["default"] as? String ?: continue
                    if (file.isNotEmpty()) candidates.add(file)
                }
            }
            // 场景里任何 inputs:file 都兜底
            if (candidates.isEmpty()) {
                for ((propPath, fields) in propFields) {
                    if (!propPath.endsWith(".inputs:file")) continue
                    (fields["default"] as? String)?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
                }
            }
            if (candidates.isEmpty() || baseDir == null) return null

            // 优先 diffuse / basecolor 之类的名字
            candidates.sortBy { name ->
                val n = name.lowercase()
                when {
                    n.contains("diffuse") || n.contains("basecolor") || n.contains("albedo") -> 0
                    n.contains("emissive") -> 1
                    else -> 2
                }
            }
            for (candidate in candidates) {
                val f = File(baseDir, candidate)
                if (!f.isFile) continue
                try {
                    BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            // 兜底：同目录里最大的那张图
            val pics = (baseDir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in
                    setOf("png", "jpg", "jpeg", "webp", "bmp") }
                .sortedByDescending { it.length() }
            for (pic in pics) {
                try {
                    BitmapFactory.decodeFile(pic.absolutePath)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            return null
        }

        // ---------- 变换 ----------

        /** 从根到该 prim 的变换累乘（按 xformOpOrder 依次组合 translate / orient / scale / transform） */
        private fun ancestorTransform(path: String): FloatArray {
            val chain = ArrayList<String>()
            var current = path
            while (current.isNotEmpty()) {
                chain.add(current)
                val idx = current.lastIndexOf('/')
                if (idx <= 0) break
                current = current.substring(0, idx)
            }
            var result = identity4()
            for (p in chain.asReversed()) {
                result = mul4(result, localXform(p))
            }
            return result
        }

        private fun localXform(path: String): FloatArray {
            val orderArray = propFields["$path.xformOpOrder"]?.get("default") as? Array<*>
            val order = orderArray?.mapNotNull { it as? String }?.filter { it.isNotEmpty() } ?: emptyList()
            if (order.isNotEmpty()) {
                var m = identity4()
                for (op in order) {
                    val value = propFields["$path.$op"]?.get("default") ?: continue
                    val opMatrix = when {
                        op.endsWith("transform") ->
                            (value as? FloatArray)?.takeIf { it.size == 16 }
                        op.endsWith("translate") ->
                            (value as? FloatArray)?.takeIf { it.size >= 3 }?.let { translationM(it) }
                        op.endsWith("scale") ->
                            (value as? FloatArray)?.takeIf { it.size >= 3 }?.let { scaleM(it) }
                        op.endsWith("orient") ->
                            (value as? FloatArray)?.takeIf { it.size >= 4 }?.let { orientM(it) }
                        else -> null
                    } ?: continue
                    m = mul4(m, opMatrix)
                }
                return m
            }
            val direct = propFields["$path.xformOp:transform"]?.get("default") as? FloatArray
            if (direct != null && direct.size == 16) return direct
            return identity4()
        }

        private fun translationM(v: FloatArray) = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            v[0], v[1], v[2], 1f
        )

        private fun scaleM(v: FloatArray) = floatArrayOf(
            v[0], 0f, 0f, 0f,
            0f, v[1], 0f, 0f,
            0f, 0f, v[2], 0f,
            0f, 0f, 0f, 1f
        )

        /** USD 的 quatf 是 (real=w, i, j, k)，转成旋转矩阵（列主序） */
        private fun orientM(q: FloatArray): FloatArray {
            val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            return floatArrayOf(
                1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy), 0f,
                2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx), 0f,
                2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy), 0f,
                0f, 0f, 0f, 1f
            )
        }

        // ---------- 头 / TOC ----------

        private fun readHeader(): Int {
            val magic = String(data, 0, 8, Charsets.US_ASCII)
            require(magic == "PXR-USDC") { "不是 usdc 文件（文件头不对）" }
            val major = data[8].toInt()
            val minor = data[9].toInt()
            require(!(major > 0 || minor > 10)) { "usdc 版本 $major.$minor 太新，暂不支持" }
            return buf.getLong(16).toInt()
        }

        private fun readSections(tocOffset: Int) {
            require(tocOffset > 0 && tocOffset + 8 <= data.size) { "usdc 的目录偏移不合法" }
            val count = buf.getLong(tocOffset).toInt()
            var p = tocOffset + 8
            for (i in 0 until count) {
                if (p + 32 > data.size) break
                val name = String(data, p, 16, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                val start = buf.getLong(p + 16).toInt()
                val size = buf.getLong(p + 24).toInt()
                if (name.isNotEmpty()) sections[name] = Section(name, start, size)
                p += 32
            }
        }

        // ---------- 结构段 ----------

        private fun readTokens() {
            val section = sections["TOKENS"] ?: return
            var p = section.start
            val count = buf.getLong(p).toInt(); p += 8
            val uncompressedSize = buf.getLong(p).toInt(); p += 8
            val compressedSize = buf.getLong(p).toInt(); p += 8

            val raw: ByteArray = if (compressedSize in 1 until uncompressedSize) {
                lz4Decompress(data.copyOfRange(p + 1, p + compressedSize), uncompressedSize)
            } else {
                data.copyOfRange(p, p + uncompressedSize)
            }

            val list = ArrayList<String>(count)
            var start = 0
            for (i in raw.indices) {
                if (raw[i] == 0.toByte()) {
                    list.add(String(raw, start, i - start, Charsets.UTF_8))
                    start = i + 1
                    if (list.size >= count) break
                }
            }
            if (list.size < count && start < raw.size) {
                list.add(String(raw, start, raw.size - start, Charsets.UTF_8))
            }
            tokens = list
        }

        private fun readStrings() {
            val section = sections["STRINGS"] ?: return
            if (section.size <= 0) return
            var p = section.start
            val count = buf.getLong(p).toInt(); p += 8
            val out = IntArray(count.coerceAtLeast(0))
            for (i in 0 until count) {
                if (p + 4 > data.size) break
                out[i] = buf.getInt(p); p += 4
            }
            stringIndices = out
        }

        private fun readFields() {
            val section = sections["FIELDS"] ?: return
            if (section.size <= 0) return
            var p = section.start
            val count = buf.getLong(p).toInt(); p += 8
            if (count <= 0) return

            var compressedSize = buf.getLong(p).toInt(); p += 8
            val tokenIndices: IntArray = if (compressedSize in 1 until section.size) {
                val r = decompressIntegers(data.copyOfRange(p, p + compressedSize), compressedSize, count, signed = false)
                p += compressedSize
                r
            } else {
                val out = IntArray(count)
                for (i in 0 until count) { out[i] = buf.getInt(p); p += 4 }
                out
            }

            compressedSize = buf.getLong(p).toInt(); p += 8
            val repsSize = count * 8
            val repsData: ByteArray = if (compressedSize in 1 until repsSize) {
                lz4Decompress(data.copyOfRange(p + 1, p + compressedSize), repsSize)
            } else {
                data.copyOfRange(p, minOf(p + repsSize, data.size))
            }
            val repsBuf = ByteBuffer.wrap(repsData).order(ByteOrder.LITTLE_ENDIAN)
            val reps = LongArray(count)
            for (i in 0 until count) {
                reps[i] = if (i * 8 + 8 <= repsData.size) repsBuf.getLong(i * 8) else 0L
            }

            fieldTokenIndices = tokenIndices
            fieldReps = reps
        }

        private fun readFieldSets() {
            val section = sections["FIELDSETS"] ?: return
            if (section.size <= 0) return
            var p = section.start
            val count = buf.getLong(p).toInt(); p += 8
            if (count <= 0) return
            val compressedSize = buf.getLong(p).toInt(); p += 8
            fieldSetIndices = if (compressedSize in 1..(section.size - 16)) {
                decompressIntegers(data.copyOfRange(p, p + compressedSize), compressedSize, count, signed = false)
            } else {
                val out = IntArray(count)
                for (i in 0 until count) { out[i] = buf.getInt(p); p += 4 }
                out
            }
        }

        private fun readPaths() {
            val section = sections["PATHS"] ?: return
            if (section.size <= 0) return
            var p = section.start
            val numPaths = buf.getLong(p).toInt(); p += 8
            if (numPaths <= 0) return
            paths = Array(numPaths) { "" }

            val numEncoded = buf.getLong(p).toInt(); p += 8
            if (numEncoded <= 0) return

            var compressedSize = buf.getLong(p).toInt(); p += 8
            val pathIndexes: IntArray = if (compressedSize > 0) {
                val r = decompressIntegers(data.copyOfRange(p, p + compressedSize), compressedSize, numEncoded, signed = false)
                p += compressedSize
                r
            } else {
                val out = IntArray(numEncoded)
                for (i in 0 until numEncoded) { out[i] = buf.getInt(p); p += 4 }
                out
            }

            compressedSize = buf.getLong(p).toInt(); p += 8
            val elementTokens: IntArray = if (compressedSize > 0) {
                val r = decompressIntegers(data.copyOfRange(p, p + compressedSize), compressedSize, numEncoded, signed = true)
                p += compressedSize
                r
            } else {
                val out = IntArray(numEncoded)
                for (i in 0 until numEncoded) { out[i] = buf.getInt(p); p += 4 }
                out
            }

            compressedSize = buf.getLong(p).toInt(); p += 8
            val jumps: IntArray = if (compressedSize > 0) {
                decompressIntegers(data.copyOfRange(p, p + compressedSize), compressedSize, numEncoded, signed = true)
            } else {
                val out = IntArray(numEncoded)
                for (i in 0 until numEncoded) { out[i] = buf.getInt(p); p += 4 }
                out
            }

            buildPaths(pathIndexes, elementTokens, jumps)
        }

        /** 按跳转表还原路径树（对照 usdjs 的 buildDecompressedPaths） */
        private fun buildPaths(pathIndexes: IntArray, elementTokens: IntArray, jumps: IntArray) {
            if (pathIndexes.isEmpty()) return

            class Frame(val start: Int, val end: Int, val parentPath: String, val parentNode: Int)

            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(0, pathIndexes.size - 1, "", -1))
            val visited = HashSet<Int>()

            while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                var parentPath = frame.parentPath
                var parentNode = frame.parentNode

                for (i in frame.start..frame.end) {
                    val pathIdx = pathIndexes[i]
                    if (pathIdx < 0 || pathIdx >= paths.size) continue
                    if (!visited.add(pathIdx)) continue

                    val currentParentPath = parentPath
                    val currentParentNode = parentNode

                    if (parentPath.isEmpty()) {
                        paths[pathIdx] = "/"
                        parentPath = "/"
                        parentNode = pathIdx
                    } else {
                        val tokenIndex = elementTokens[i]
                        val isProperty = tokenIndex < 0
                        val actual = if (isProperty) -tokenIndex else tokenIndex
                        val elem = tokens.getOrElse(actual) { "" }
                        val fullPath = when {
                            isProperty && currentParentPath == "/" -> "/$elem"
                            isProperty -> "$currentParentPath.$elem"
                            currentParentPath == "/" -> "/$elem"
                            else -> "$currentParentPath/$elem"
                        }
                        paths[pathIdx] = fullPath
                    }

                    val jump = jumps.getOrElse(i) { -2 }
                    val hasChild = jump > 0 || jump == -1
                    val hasSibling = jump >= 0

                    if (hasChild && hasSibling) {
                        val siblingIndex = i + jump
                        if (siblingIndex > i && siblingIndex <= frame.end) {
                            val siblingEnd = frame.end
                            if (siblingEnd < frame.end) {
                                stack.addLast(Frame(siblingEnd + 1, frame.end, currentParentPath, currentParentNode))
                            }
                            stack.addLast(Frame(siblingIndex, siblingEnd, currentParentPath, currentParentNode))
                            if (siblingIndex > i + 1) {
                                stack.addLast(Frame(i + 1, siblingIndex - 1, paths[pathIdx], pathIdx))
                            }
                            break
                        }
                    }
                    if (hasChild) {
                        parentPath = paths[pathIdx]
                        parentNode = pathIdx
                    }
                }
            }
        }

        private fun readSpecs() {
            val section = sections["SPECS"] ?: return
            if (section.size <= 0) return
            var p = section.start
            val count = buf.getLong(p).toInt(); p += 8
            if (count <= 0) return

            fun readArray(): IntArray {
                val compressedSize = buf.getLong(p).toInt(); p += 8
                return if (compressedSize > 0) {
                    val r = decompressIntegers(
                        data.copyOfRange(p, p + compressedSize), compressedSize, count, signed = false
                    )
                    p += compressedSize
                    r
                } else {
                    val out = IntArray(count)
                    for (i in 0 until count) { out[i] = buf.getInt(p); p += 4 }
                    out
                }
            }

            val pathIndexes = readArray()
            val fieldSetIndexes = readArray()
            val specTypes = readArray()

            val list = ArrayList<Spec>(count)
            for (i in 0 until count) {
                list.add(Spec(pathIndexes[i], fieldSetIndexes[i], specTypes[i]))
            }
            specs = list
        }

        // ---------- 组装 ----------

        private fun collectSpecs() {
            // FIELDSETS：以 0xFFFFFFFF 分隔的字段索引串
            val sets = HashMap<Int, HashMap<String, Any?>>()
            var start = 0
            var current = ArrayList<Int>()
            for (i in fieldSetIndices.indices) {
                val idx = fieldSetIndices[i]
                if (idx == -1) {
                    if (current.isNotEmpty()) sets[start] = decodeFields(current)
                    start = i + 1
                    current = ArrayList()
                } else {
                    current.add(idx)
                }
            }
            if (current.isNotEmpty()) sets[start] = decodeFields(current)

            for (spec in specs) {
                val path = paths.getOrElse(spec.pathIndex) { "" }
                if (path.isEmpty()) continue
                val fields = sets[spec.fieldSetIndex] ?: continue
                when (spec.specType) {
                    SPEC_PRIM -> primFields[path] = fields
                    SPEC_ATTRIBUTE, SPEC_RELATIONSHIP -> propFields[path] = fields
                    SPEC_PSEUDO_ROOT -> {
                        (fields["upAxis"] as? String)?.let { upAxis = it }
                    }
                }
            }
        }

        private var upAxis: String = "Y"

        private fun decodeFields(indices: List<Int>): HashMap<String, Any?> {
            val out = HashMap<String, Any?>()
            for (idx in indices) {
                if (idx < 0 || idx >= fieldReps.size) continue
                val name = tokens.getOrElse(fieldTokenIndices[idx]) { "" }
                if (name.isEmpty()) continue
                out[name] = decodeValue(fieldReps[idx])
            }
            return out
        }

        // ---------- ValueRep ----------

        private fun decodeValue(rep: Long): Any? {
            val payload = rep and 0xFFFFFFFFFFFFL
            val type = ((rep ushr 48) and 0xFF).toInt()
            val flags = ((rep ushr 56) and 0xFF).toInt()
            val isArray = (flags and 0x80) != 0
            val isInlined = (flags and 0x40) != 0
            val isCompressed = (flags and 0x20) != 0
            val inlineValue = (payload and 0xFFFFFFFFL).toInt()
            val offset = payload.toInt()

            if (isArray) {
                if (isInlined || offset <= 0 || offset + 8 > data.size) return null
                val count = buf.getLong(offset).toInt()
                if (count < 0) return null
                val base = offset + 8
                return when (type) {
                    T_TOKEN -> {
                        if (base + count * 4 > data.size) return null
                        Array(count) { i -> tokens.getOrElse(buf.getInt(base + i * 4)) { "" } }
                    }

                    T_INT -> {
                        if (!isCompressed) {
                            if (base + count * 4 > data.size) return null
                            IntArray(count) { i -> buf.getInt(base + i * 4) }
                        } else {
                            if (base + 8 > data.size) return null
                            val size = buf.getLong(base).toInt()
                            decompressIntegers(data.copyOfRange(base + 8, base + 8 + size), size, count, signed = true)
                        }
                    }

                    T_UINT -> {
                        if (!isCompressed) {
                            if (base + count * 4 > data.size) return null
                            IntArray(count) { i -> buf.getInt(base + i * 4) }
                        } else {
                            if (base + 8 > data.size) return null
                            val size = buf.getLong(base).toInt()
                            decompressIntegers(data.copyOfRange(base + 8, base + 8 + size), size, count, signed = false)
                        }
                    }

                    T_FLOAT -> readFloatArray(base, count)
                    T_VEC2F -> readVectorArray(base, count, 2)
                    T_VEC3F -> readVectorArray(base, count, 3)
                    T_VEC2D -> readVectorArrayD(base, count, 2)
                    T_VEC3D -> readVectorArrayD(base, count, 3)
                    T_MATRIX4D -> {
                        if (base + count * 16 * 8 > data.size) return null
                        FloatArray(count * 16) { i -> buf.getDouble(base + i * 8).toFloat() }
                    }

                    T_PATH_VECTOR -> {
                        if (base + count * 4 > data.size) return null
                        Array(count) { i -> paths.getOrElse(buf.getInt(base + i * 4)) { "" } }
                    }

                    T_TOKEN_VECTOR -> {
                        if (base + count * 4 > data.size) return null
                        Array(count) { i -> tokens.getOrElse(buf.getInt(base + i * 4)) { "" } }
                    }

                    T_PATH_LIST_OP -> decodePathListOp(offset)

                    else -> null
                }
            }

            return when (type) {
                T_BOOL -> (inlineValue and 1) != 0
                T_INT -> if (isInlined) inlineValue else if (offset + 4 <= data.size) buf.getInt(offset) else null
                T_UINT -> if (isInlined) inlineValue else if (offset + 4 <= data.size) buf.getInt(offset) else null
                T_INT64, T_UINT64 ->
                    if (isInlined) inlineValue.toLong()
                    else if (offset + 8 <= data.size) buf.getLong(offset) else null

                T_FLOAT ->
                    if (isInlined) Float.fromBits(inlineValue)
                    else if (offset + 4 <= data.size) buf.getFloat(offset) else null

                T_DOUBLE ->
                    if (isInlined) Float.fromBits(inlineValue).toDouble()
                    else if (offset + 8 <= data.size) buf.getDouble(offset) else null

                T_STRING -> tokens.getOrElse(stringIndices.getOrElse(inlineValue) { -1 }) { "" }
                T_TOKEN -> tokens.getOrElse(inlineValue) { "" }
                T_ASSET ->
                    if (isInlined) tokens.getOrElse(inlineValue) { "" }
                    else tokens.getOrElse(stringIndices.getOrElse(inlineValue) { -1 }) { "" }

                T_VEC2F, T_VEC3F, T_VEC4F -> {
                    val dim = when (type) {
                        T_VEC2F -> 2
                        T_VEC3F -> 3
                        else -> 4
                    }
                    if (isInlined) {
                        FloatArray(dim) { i -> (((inlineValue shr (i * 8)) and 0xFF).toByte()).toFloat() }
                    } else if (offset + dim * 4 <= data.size) {
                        FloatArray(dim) { i -> buf.getFloat(offset + i * 4) }
                    } else null
                }

                T_VEC2D, T_VEC3D -> {
                    val dim = if (type == T_VEC2D) 2 else 3
                    if (offset + dim * 8 <= data.size) {
                        FloatArray(dim) { i -> buf.getDouble(offset + i * 8).toFloat() }
                    } else null
                }

                T_MATRIX4D -> {
                    if (isInlined) {
                        val m = FloatArray(16)
                        m[0] = (((inlineValue shr 0) and 0xFF).toByte()).toFloat()
                        m[5] = (((inlineValue shr 8) and 0xFF).toByte()).toFloat()
                        m[10] = (((inlineValue shr 16) and 0xFF).toByte()).toFloat()
                        m[15] = (((inlineValue shr 24) and 0xFF).toByte()).toFloat()
                        m
                    } else if (offset + 16 * 8 <= data.size) {
                        FloatArray(16) { i -> buf.getDouble(offset + i * 8).toFloat() }
                    } else null
                }

                T_SPECIFIER -> when (inlineValue) {
                    1 -> "over"
                    2 -> "class"
                    else -> "def"
                }

                T_PATH_LIST_OP -> decodePathListOp(offset)
                else -> null
            }
        }

        private fun readFloatArray(base: Int, count: Int): FloatArray? {
            if (count <= 0) return FloatArray(0)
            if (base + count * 4 <= data.size) {
                return FloatArray(count) { i -> buf.getFloat(base + i * 4) }
            }
            return null
        }

        private fun readVectorArray(base: Int, count: Int, dim: Int): FloatArray? {
            val total = count * dim
            if (base + total * 4 > data.size) return null
            return FloatArray(total) { i -> buf.getFloat(base + i * 4) }
        }

        private fun readVectorArrayD(base: Int, count: Int, dim: Int): FloatArray? {
            val total = count * dim
            if (base + total * 8 > data.size) return null
            return FloatArray(total) { i -> buf.getDouble(base + i * 8).toFloat() }
        }

        /** SdfPathListOp：relationships 的 targetPaths / attributes 的 connectionPaths */
        private fun decodePathListOp(offset: Int): Array<String>? {
            if (offset <= 0 || offset + 1 > data.size) return null
            var p = offset
            val bits = data[p].toInt() and 0xFF
            p += 1
            val isExplicit = (bits and 0x01) != 0
            val hasExplicit = (bits and 0x02) != 0
            val hasAdded = (bits and 0x04) != 0
            val hasDeleted = (bits and 0x08) != 0
            val hasOrdered = (bits and 0x10) != 0
            val hasPrepended = (bits and 0x20) != 0
            val hasAppended = (bits and 0x40) != 0

            fun readPathVec(): List<String> {
                if (p + 8 > data.size) return emptyList()
                val n = buf.getLong(p).toInt()
                p += 8
                if (n <= 0) return emptyList()
                if (p + n * 4 > data.size) return emptyList()
                val out = ArrayList<String>(n)
                for (i in 0 until n) {
                    out.add(paths.getOrElse(buf.getInt(p + i * 4)) { "" })
                }
                p += n * 4
                return out.filter { it.isNotEmpty() }
            }

            val explicit = if (hasExplicit) readPathVec() else emptyList()
            val added = if (hasAdded) readPathVec() else emptyList()
            val deleted = if (hasDeleted) readPathVec() else emptyList()
            val ordered = if (hasOrdered) readPathVec() else emptyList()
            val prepended = if (hasPrepended) readPathVec() else emptyList()
            val appended = if (hasAppended) readPathVec() else emptyList()

            val result = if (isExplicit) {
                explicit
            } else {
                val set = LinkedHashSet<String>()
                set.addAll(added)
                set.addAll(prepended)
                set.addAll(appended)
                deleted.forEach { set.remove(it) }
                if (ordered.isNotEmpty()) {
                    val orderedSet = ordered.filter { set.contains(it) }
                    val rest = set.filter { it !in orderedSet }
                    orderedSet + rest
                } else {
                    set.toList()
                }
            }
            return result.toTypedArray()
        }

        // ---------- 压缩算法 ----------

        /** USD 的整数压缩：1 字节头 + LZ4，再按 2bit 编码还原（差分累积） */
        private fun decompressIntegers(
            compressed: ByteArray,
            compressedSize: Int,
            numInts: Int,
            signed: Boolean
        ): IntArray {
            if (numInts <= 0) return IntArray(0)
            val encodedSize = 4 + (numInts * 2 + 7) / 8 + numInts * 4
            val payload = if (compressedSize > 1) compressed.copyOfRange(1, compressedSize) else ByteArray(0)
            val encoded = lz4Decompress(payload, encodedSize)
            if (encoded.size < 4) return IntArray(numInts)

            val encBuf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
            val common = encBuf.getInt(0)
            val codesStart = 4
            val vintsStart = codesStart + (numInts * 2 + 7) / 8

            val out = IntArray(numInts)
            var vints = vintsStart
            var prev = 0
            for (i in 0 until numInts) {
                val byteIndex = codesStart + (i * 2) / 8
                val bitOffset = (i * 2) % 8
                val code = if (byteIndex < encoded.size)
                    (encoded[byteIndex].toInt() shr bitOffset) and 0x03 else 0
                val diff = when (code) {
                    0 -> common
                    1 -> if (vints < encoded.size) {
                        val v = encoded[vints].toInt(); vints += 1; v
                    } else 0
                    2 -> {
                        if (vints + 2 <= encoded.size) {
                            val v = encBuf.getShort(vints).toInt(); vints += 2; v
                        } else 0
                    }

                    else -> {
                        if (vints + 4 <= encoded.size) {
                            val v = encBuf.getInt(vints); vints += 4; v
                        } else 0
                    }
                }
                prev += diff
                out[i] = prev
            }
            return out
        }

        /** LZ4 block 解压（USD crate 用的就是这个块格式） */
        private fun lz4Decompress(src: ByteArray, destSize: Int): ByteArray {
            val dest = ByteArray(destSize.coerceAtLeast(0))
            var s = 0
            var d = 0
            while (s < src.size && d < destSize) {
                val token = src[s].toInt() and 0xFF
                s++
                var literals = (token shr 4) and 0x0F
                var matchLen = token and 0x0F

                if (literals == 15) {
                    while (s < src.size) {
                        val b = src[s].toInt() and 0xFF
                        s++
                        literals += b
                        if (b != 255) break
                    }
                }

                if (literals > 0) {
                    if (s + literals > src.size) {
                        val copy = minOf(literals, src.size - s, destSize - d)
                        System.arraycopy(src, s, dest, d, copy)
                        break
                    }
                    System.arraycopy(src, s, dest, d, literals)
                    s += literals
                    d += literals
                }

                if (d >= destSize || s + 2 > src.size) break

                val matchOffset = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
                s += 2
                if (matchOffset == 0) break

                matchLen += 4
                if ((token and 0x0F) == 15) {
                    while (s < src.size) {
                        val b = src[s].toInt() and 0xFF
                        s++
                        matchLen += b
                        if (b != 255) break
                    }
                }

                val matchStart = d - matchOffset
                if (matchStart < 0) break
                for (i in 0 until matchLen) {
                    if (d >= destSize) break
                    dest[d] = dest[matchStart + i]
                    d++
                }
            }
            return dest
        }

        // ---------- 矩阵 ----------

        private fun identity4() = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        /** Z-up 资产转成 Y-up，否则模型会"躺"在桌面上 */
        private fun zUpToYUp() = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f
        )

        private fun mul4(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (c in 0 until 4) {
                for (r in 0 until 4) {
                    var sum = 0f
                    for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
                    out[c * 4 + r] = sum
                }
            }
            return out
        }
    }
}
