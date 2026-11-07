package com.funnyarchbishop.fumofumo

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 模型和自定义贴图都落在 filesDir 里。
 * 模型文件名不带格式含义 —— 导入时是按文件内容判断格式的。
 */
object ModelRepository {

    private const val MODEL_FILE = "model.dat"
    private const val TEXTURE_FILE = "custom_texture.img"

    fun modelFile(context: Context): File = File(context.applicationContext.filesDir, MODEL_FILE)

    fun textureFile(context: Context): File = File(context.applicationContext.filesDir, TEXTURE_FILE)

    fun exists(context: Context): Boolean = modelFile(context).let { it.isFile && it.length() > 0L }

    fun hasCustomTexture(context: Context): Boolean =
        textureFile(context).let { it.isFile && it.length() > 0L }

    /** 把用户选中的模型复制进来 */
    fun importModel(context: Context, uri: Uri): File {
        val target = modelFile(context)
        copy(context, uri, target)
        if (target.length() <= 0L) throw IllegalStateException("复制出来的文件是空的")
        return target
    }

    /** 把用户选中的贴图复制进来（覆盖旧的） */
    fun importTexture(context: Context, uri: Uri): File {
        val target = textureFile(context)
        copy(context, uri, target)
        if (target.length() <= 0L) throw IllegalStateException("复制出来的图片是空的")
        return target
    }

    fun clearTexture(context: Context) {
        textureFile(context).delete()
    }

    private fun copy(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("打不开所选文件")
        input.use { src ->
            target.outputStream().use { out -> src.copyTo(out, DEFAULT_BUFFER_SIZE) }
        }
    }
}
