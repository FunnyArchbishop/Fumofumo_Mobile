package com.funnyarchbishop.fumofumo

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 模型文件统一存在 filesDir/model.glb。用户从系统文件选择器挑一个 .glb，复制进来。
 */
object ModelRepository {

    private const val FILE_NAME = "model.glb"

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0L }

    /** 把用户选中的文件复制进来，返回目标文件 */
    fun import(context: Context, uri: Uri): File {
        val target = file(context)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("打不开所选文件")
        input.use { src ->
            target.outputStream().use { out -> src.copyTo(out, DEFAULT_BUFFER_SIZE) }
        }
        if (target.length() <= 0L) throw IllegalStateException("复制出来的文件是空的")
        return target
    }
}
