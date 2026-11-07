package com.funnyarchbishop.fumofumo

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 临时诊断：把 USDC 解析到的结构打出来 */
class UsdcDebugTest {

    @Test
    fun dumpStructure() {
        val path = System.getProperty("testUsdc").orEmpty()
        assumeTrue("没有 -DtestUsdc，跳过", path.isNotEmpty())
        val file = File(path)
        assumeTrue("文件不存在", file.isFile)
        println("===== USDC DEBUG =====")
        println(UsdcLoader.debugInfo(file))
        println("===== END =====")
    }
}
