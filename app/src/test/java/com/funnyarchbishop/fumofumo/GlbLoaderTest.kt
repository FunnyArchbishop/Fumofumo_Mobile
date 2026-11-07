package com.funnyarchbishop.fumofumo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用真实的 glb 验证解析器。
 *
 * 默认跳过；把模型路径传进来才会跑：
 *   ./gradlew testDebugUnitTest -DtestModel=/path/to/model.glb
 *
 * 断言里的数字来自桌面版 WINobj（trimesh）对同一个模型的解析结果，用来对拍。
 */
class GlbLoaderTest {

    private fun modelPath(): String = System.getProperty("testModel").orEmpty()

    @Test
    fun parsesRealSketchfabGlb() {
        val path = modelPath()
        assumeTrue("没有提供 -DtestModel，跳过真实模型测试", path.isNotEmpty())
        val file = File(path)
        assumeTrue("模型文件不存在：$path", file.isFile)

        val data = GlbLoader.load(file)

        // 三角面数：桌面版 trimesh 读出 97421 个面 → 摊平后 292263 个角
        assertEquals("三角面数应与桌面版一致", 97421, data.triangleCount)
        assertEquals("角点数应为面数 ×3", 292263, data.vertexCount)

        // 法线 / UV 数组长度必须和位置一一对应
        assertEquals(data.vertexCount * 3, data.normals.size)
        assertEquals(data.vertexCount * 2, data.uvs.size)

        // 包围盒应与桌面版一致（容差留给浮点误差）
        assertArrayEquals(
            "min 包围盒应与桌面版一致",
            floatArrayOf(-13.211f, 0.403f, -9.396f),
            data.min,
            0.05f
        )
        assertArrayEquals(
            "max 包围盒应与桌面版一致",
            floatArrayOf(8.744f, 25.272f, 9.342f),
            data.max,
            0.05f
        )

        // 所有数据都必须是有限值（NaN 会让渲染直接消失）
        assertTrue("位置里不应出现 NaN/Inf", data.positions.all { it.isFinite() })
        assertTrue("法线里不应出现 NaN/Inf", data.normals.all { it.isFinite() })
        assertTrue("UV 里不应出现 NaN/Inf", data.uvs.all { it.isFinite() })
    }
}
