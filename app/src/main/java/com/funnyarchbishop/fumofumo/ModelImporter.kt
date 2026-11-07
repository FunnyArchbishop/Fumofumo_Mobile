package com.funnyarchbishop.fumofumo

import java.io.File
import java.util.zip.ZipFile

/**
 * 统一的模型导入入口：按文件头判断真实格式，再分派给对应的读取器。
 * 扩展名不可信（下载工具会改名），所以先看内容。
 *
 * 支持：.glb / .gltf（+ 同目录 .bin 贴图）/ .obj（+ .mtl 贴图）/ .usdz / .usdc / .zip（内含以上任意一种）
 */
object ModelImporter {

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "bmp")

    fun load(file: File, cacheDir: File): MeshData {
        require(file.isFile) { "找不到模型文件：${file.name}" }
        val head = readHead(file, 64)

        return when {
            isGlb(head) -> GltfLoader.loadGlb(file)
            UsdcLoader.isUsdc(head) -> UsdcLoader.load(file)
            isZip(head) -> loadFromZip(file, cacheDir)
            isUsda(head) -> throw IllegalStateException(
                "这是文本版 USD（.usda）。\n请把它打包成 .usdz 再导入，或用桌面版另存成 .glb。"
            )

            isGltfJson(head) -> GltfLoader.loadGltf(file)
            else -> if (looksLikeObj(file)) {
                ObjLoader.load(file)
            } else {
                throw IllegalStateException(
                    "认不出这个文件的格式。\n支持：.glb / .gltf / .obj / .usdz / .zip"
                )
            }
        }
    }

    // ---------------- zip / usdz ----------------

    private fun loadFromZip(zipFile: File, cacheDir: File): MeshData {
        val slot = File(cacheDir, "zip_" + zipFile.absolutePath.hashCode() + "_" + zipFile.length())
        if (!slot.isDirectory || (slot.listFiles()?.isEmpty() != false)) {
            slot.deleteRecursively()
            slot.mkdirs()
            extract(zipFile, slot)
        }

        val all = slot.walkTopDown().filter { it.isFile }.toList()
        val priority = listOf("glb", "gltf", "usdc", "usda", "obj")
        val candidates = all
            .filter { it.extension.lowercase() in priority }
            .sortedBy { priority.indexOf(it.extension.lowercase()) }
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "压缩包里没找到模型文件。\n支持：.glb / .gltf / .obj / .usdz"
            )
        }

        val chosen = candidates.first()
        return when (chosen.extension.lowercase()) {
            "glb" -> GltfLoader.loadGlb(chosen)
            "gltf" -> GltfLoader.loadGltf(chosen)
            "usdc" -> UsdcLoader.load(chosen)
            "obj" -> ObjLoader.load(chosen)
            else -> throw IllegalStateException("压缩包里的 .usda 是文本版 USD，暂不支持，请转成 .usdz 或 .glb。")
        }
    }

    private fun extract(zipFile: File, dest: File) {
        val root = dest.canonicalFile
        ZipFile(zipFile).use { zf ->
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("__MACOSX/") || name.startsWith(".")) continue
                val target = File(dest, name)
                // 防路径穿越
                if (!target.canonicalPath.startsWith(root.path + File.separator)) continue
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
            }
        }
    }

    // ---------------- 嗅探 ----------------

    private fun readHead(file: File, size: Int): ByteArray {
        file.inputStream().use { stream ->
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = stream.read(buf, read, size - read)
                if (n <= 0) break
                read += n
            }
            return if (read == size) buf else buf.copyOf(read)
        }
    }

    private fun isGlb(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'g'.code.toByte() && head[1] == 'l'.code.toByte() &&
            head[2] == 'T'.code.toByte() && head[3] == 'F'.code.toByte()

    private fun isZip(head: ByteArray): Boolean =
        head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()

    private fun isUsda(head: ByteArray): Boolean {
        val text = String(head, 0, minOf(head.size, 16), Charsets.US_ASCII)
        return text.startsWith("#usda") || text.startsWith("#usdc")
    }

    private fun isGltfJson(head: ByteArray): Boolean {
        val text = String(head, Charsets.UTF_8)
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") &&
            (text.contains("\"asset\"") || text.contains("\"meshes\"") || text.contains("\"buffers\""))
    }

    private fun looksLikeObj(file: File): Boolean {
        return try {
            val head = file.inputStream().use { it.readNBytes(8192) }
            val text = String(head, Charsets.UTF_8)
            var hasVertex = false
            var hasFace = false
            for (line in text.lineSequence()) {
                val t = line.trimStart()
                if (t.startsWith("v ")) hasVertex = true
                if (t.startsWith("f ")) hasFace = true
                if (hasVertex && hasFace) return true
            }
            hasVertex
        } catch (_: Throwable) {
            false
        }
    }

    fun isSupportedImage(file: File): Boolean = file.extension.lowercase() in IMAGE_EXTS
}
