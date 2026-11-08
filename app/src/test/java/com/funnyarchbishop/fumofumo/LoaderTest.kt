package com.funnyarchbishop.fumofumo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用真实模型验证各个读取器。
 *
 * 默认跳过，把路径传进来才会跑：
 *   ./gradlew testDebugUnitTest -DtestGlb=/path/model.glb -DtestUsdc=/path/scene.usdc
 *
 * 断言里的数字来自桌面版 WINobj（trimesh / OpenUSD）对同一模型的解析结果，用来对拍。
 */
class LoaderTest {

    private fun prop(name: String): String = System.getProperty(name).orEmpty()

    @Test
    fun parsesRealGlb() {
        val path = prop("testGlb")
        assumeTrue("没有提供 -DtestGlb，跳过", path.isNotEmpty())
        val file = File(path)
        assumeTrue("文件不存在：$path", file.isFile)

        val data = GltfLoader.loadGlb(file)

        // 桌面版 trimesh 读出 97421 个面 → 摊平后 292263 个角
        assertEquals("三角面数应与桌面版一致", 97421, data.triangleCount)
        assertEquals("角点数应为面数 ×3", 292263, data.vertexCount)
        assertEquals(data.vertexCount * 3, data.normals.size)
        assertEquals(data.vertexCount * 2, data.uvs.size)
        assertArrayEquals(
            "min 包围盒应与桌面版一致",
            floatArrayOf(-13.211f, 0.403f, -9.396f), data.min, 0.05f
        )
        assertArrayEquals(
            "max 包围盒应与桌面版一致",
            floatArrayOf(8.744f, 25.272f, 9.342f), data.max, 0.05f
        )
        assertTrue("位置里不应出现 NaN/Inf", data.positions.all { it.isFinite() })
    }

    @Test
    fun parsesRealUsdc() {
        val path = prop("testUsdc")
        assumeTrue("没有提供 -DtestUsdc，跳过", path.isNotEmpty())
        val file = File(path)
        assumeTrue("文件不存在：$path", file.isFile)

        val data = UsdcLoader.load(file)

        // 桌面版（OpenUSD）读出 42368 个三角面
        assertEquals("三角面数应与桌面版一致", 42368, data.triangleCount)
        assertEquals(data.vertexCount * 3, data.normals.size)
        assertEquals(data.vertexCount * 2, data.uvs.size)
        assertArrayEquals(
            "min 包围盒应与桌面版一致",
            floatArrayOf(-37.966f, 4.557f, -42.184f), data.min, 0.1f
        )
        assertArrayEquals(
            "max 包围盒应与桌面版一致",
            floatArrayOf(47.008f, 114.892f, 41.727f), data.max, 0.1f
        )
        assertTrue("位置里不应出现 NaN/Inf", data.positions.all { it.isFinite() })
        assertTrue("UV 应落在合理范围", data.uvs.all { it.isFinite() && it >= -2f && it <= 3f })
    }

    @Test
    fun parsesSimpleObj() {
        val tmp = File.createTempFile("quad", ".obj")
        try {
            tmp.writeText(
                """
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 1 1
                vt 0 1
                f 1/1 2/2 3/3 4/4
                """.trimIndent()
            )
            val data = ObjLoader.load(tmp)
            assertEquals("四边形应被扇形三角化成 2 个三角面", 2, data.triangleCount)
            assertEquals(6, data.vertexCount)
            // OBJ 的 v 轴朝上，这里应被翻成 glTF 的朝下：
            // 角0 = vt(0,0) → (0,1)；角1 = vt(1,0) → (1,1)；角2 = vt(1,1) → (1,0)
            assertEquals(1f, data.uvs[1], 1e-5f)
            assertEquals(1f, data.uvs[3], 1e-5f)
            assertEquals(0f, data.uvs[5], 1e-5f)
            assertArrayEquals(floatArrayOf(0f, 0f, 0f), data.min, 1e-5f)
            assertArrayEquals(floatArrayOf(1f, 1f, 0f), data.max, 1e-5f)
            assertTrue("法线应被自动算出来", data.normals.any { it != 0f })
        } finally {
            tmp.delete()
        }
    }
    /** 批量验证：把一个目录里所有 glb 都读一遍（用来检查下载回来的模型能不能被手机端吃下） */
    @Test
    fun parsesEveryGlbInDirectory() {
        val dirPath = System.getProperty("testGlbDir").orEmpty()
        assumeTrue("没有提供 -DtestGlbDir，跳过", dirPath.isNotEmpty())
        val dir = File(dirPath)
        assumeTrue("目录不存在：$dirPath", dir.isDirectory)
        val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() == "glb" }
            ?.sortedBy { it.name } ?: emptyList()
        assertTrue("目录里没有 glb 文件", files.isNotEmpty())

        val report = StringBuilder()
        var totalTris = 0
        files.forEach { file ->
            val data = GltfLoader.loadGlb(file)
            assertTrue("${file.name} 没有三角面", data.triangleCount > 0)
            assertTrue("${file.name} 顶点里出现 NaN/Inf", data.positions.all { it.isFinite() })
            assertTrue("${file.name} 法线里出现 NaN/Inf", data.normals.all { it.isFinite() })
            assertEquals("${file.name} UV 长度不对", data.vertexCount * 2, data.uvs.size)
            totalTris += data.triangleCount
            report.appendLine("  ${file.name}: ${data.triangleCount} tris")
        }
        println("===== 批量读取 ${files.size} 个 glb，共 $totalTris 个三角面 =====")
        println(report.toString())
    }
}
