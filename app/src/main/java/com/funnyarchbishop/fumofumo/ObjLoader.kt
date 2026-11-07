package com.funnyarchbishop.fumofumo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Wavefront OBJ 读取器。
 * 支持 v / vt / vn / f（含负数索引）、mtllib + usemtl 找贴图、多边形扇形三角化。
 * UV 会从 OBJ 的左下角约定转换成 glTF 的左上角约定（v' = 1 - v）。
 */
object ObjLoader {

    fun load(file: File): MeshData {
        val text = file.readText()
        val baseDir = file.parentFile

        val positions = ArrayList<Float>(1 shl 16)
        val uvs = ArrayList<Float>(1 shl 16)
        val normals = ArrayList<Float>(1 shl 16)

        // 每个角一份：(posIndex, uvIndex, normalIndex)，1-based，0 表示没有
        val cornerPos = ArrayList<Int>(1 shl 16)
        val cornerUv = ArrayList<Int>(1 shl 16)
        val cornerNrm = ArrayList<Int>(1 shl 16)

        var mtlName: String? = null
        val mtlFiles = ArrayList<String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "v" -> {
                    if (parts.size >= 4) {
                        positions.add(parts[1].toFloatOrNull() ?: 0f)
                        positions.add(parts[2].toFloatOrNull() ?: 0f)
                        positions.add(parts[3].toFloatOrNull() ?: 0f)
                    }
                }

                "vt" -> {
                    if (parts.size >= 3) {
                        uvs.add(parts[1].toFloatOrNull() ?: 0f)
                        // OBJ 的 v 轴朝上，翻成 glTF 的朝下
                        uvs.add(1f - (parts[2].toFloatOrNull() ?: 0f))
                    }
                }

                "vn" -> {
                    if (parts.size >= 4) {
                        normals.add(parts[1].toFloatOrNull() ?: 0f)
                        normals.add(parts[2].toFloatOrNull() ?: 0f)
                        normals.add(parts[3].toFloatOrNull() ?: 1f)
                    }
                }

                "mtllib" -> if (parts.size >= 2) mtlFiles.add(parts[1])

                "usemtl" -> if (parts.size >= 2) mtlName = parts[1]

                "f" -> {
                    if (parts.size < 4) continue
                    val face = ArrayList<IntArray>(parts.size - 1)
                    for (i in 1 until parts.size) {
                        val bits = parts[i].split("/")
                        val vIdx = resolve(bits.getOrNull(0), positions.size / 3)
                        val tIdx = resolve(bits.getOrNull(1), uvs.size / 2)
                        val nIdx = resolve(bits.getOrNull(2), normals.size / 3)
                        if (vIdx < 0) continue
                        face.add(intArrayOf(vIdx, tIdx, nIdx))
                    }
                    // 扇形三角化
                    for (i in 1 until face.size - 1) {
                        val a = face[0]
                        val b = face[i]
                        val c = face[i + 1]
                        for (corner in arrayOf(a, b, c)) {
                            cornerPos.add(corner[0])
                            cornerUv.add(corner[1])
                            cornerNrm.add(corner[2])
                        }
                    }
                }
            }
        }

        require(cornerPos.isNotEmpty()) { "这个 OBJ 里没有可用的三角面" }

        val count = cornerPos.size
        val pos = FloatArray(count * 3)
        val uv = FloatArray(count * 2)
        val nrm = FloatArray(count * 3)
        var hasNormals = false

        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        for (i in 0 until count) {
            val pi = cornerPos[i] * 3
            val x = positions.getOrElse(pi) { 0f }
            val y = positions.getOrElse(pi + 1) { 0f }
            val z = positions.getOrElse(pi + 2) { 0f }
            pos[i * 3] = x; pos[i * 3 + 1] = y; pos[i * 3 + 2] = z
            if (x < min[0]) min[0] = x
            if (y < min[1]) min[1] = y
            if (z < min[2]) min[2] = z
            if (x > max[0]) max[0] = x
            if (y > max[1]) max[1] = y
            if (z > max[2]) max[2] = z

            val ti = cornerUv[i]
            if (ti >= 0) {
                uv[i * 2] = uvs.getOrElse(ti * 2) { 0f }
                uv[i * 2 + 1] = uvs.getOrElse(ti * 2 + 1) { 0f }
            }

            val ni = cornerNrm[i]
            if (ni >= 0) {
                nrm[i * 3] = normals.getOrElse(ni * 3) { 0f }
                nrm[i * 3 + 1] = normals.getOrElse(ni * 3 + 1) { 0f }
                nrm[i * 3 + 2] = normals.getOrElse(ni * 3 + 2) { 1f }
                hasNormals = true
            }
        }

        val finalNormals = if (hasNormals) normalizeAll(nrm) else computeSmoothNormals(pos)
        val texture = findTexture(baseDir, mtlFiles, mtlName)

        return MeshData(pos, finalNormals, uv, texture, min, max, file.name)
    }

    /** OBJ 的索引可以是负数（相对当前末尾），返回 0-based；0 表示该角没有这一项 */
    private fun resolve(token: String?, total: Int): Int {
        if (token.isNullOrEmpty()) return -1
        val value = token.toIntOrNull() ?: return -1
        return when {
            value > 0 -> value - 1
            value < 0 -> total + value
            else -> -1
        }
    }

    private fun normalizeAll(n: FloatArray): FloatArray {
        var i = 0
        while (i < n.size) {
            val len = kotlin.math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
            if (len > 1e-8f) {
                n[i] /= len; n[i + 1] /= len; n[i + 2] /= len
            } else {
                n[i] = 0f; n[i + 1] = 0f; n[i + 2] = 1f
            }
            i += 3
        }
        return n
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
        return normalizeAll(out)
    }

    /** 贴图：先按 mtl 里的 map_Kd 找，找不到就用同目录最大的图片 */
    private fun findTexture(baseDir: File?, mtlFiles: List<String>, useMaterial: String?): Bitmap? {
        if (baseDir == null) return null

        for (mtlName in mtlFiles) {
            val mtl = File(baseDir, mtlName)
            if (!mtl.isFile) continue
            var currentMaterial: String? = null
            for (line in mtl.readLines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("newmtl ")) {
                    currentMaterial = trimmed.removePrefix("newmtl ").trim()
                } else if (trimmed.startsWith("map_Kd ") || trimmed.startsWith("map_kd ")) {
                    // 只认当前材质（没有 usemtl 时随便）
                    if (useMaterial != null && currentMaterial != null && currentMaterial != useMaterial) continue
                    val texName = trimmed.substringAfter(' ').trim().trim('"')
                    val texFile = File(baseDir, texName)
                    if (texFile.isFile) {
                        decode(texFile)?.let { return it }
                    }
                    // 贴图可能在 mtl 同级或 obj 同级，再按文件名找一次
                    val byName = File(mtl.parentFile, texName)
                    if (byName.isFile) decode(byName)?.let { return it }
                }
            }
        }

        val pics = (baseDir.listFiles() ?: emptyArray())
            .filter {
                it.isFile && it.name.substringAfterLast('.', "").lowercase() in
                    setOf("png", "jpg", "jpeg", "webp", "bmp")
            }
            .sortedByDescending { it.length() }
        for (pic in pics) decode(pic)?.let { return it }
        return null
    }

    private fun decode(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.absolutePath)
    } catch (_: Throwable) {
        null
    }
}
