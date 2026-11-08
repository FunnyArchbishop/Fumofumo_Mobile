package com.funnyarchbishop.fumofumo

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 预设的合法性检查：参数必须都在滑条范围内，且每套预设确实覆盖了手感相关的量。
 * 这类测试的价值是防止以后手改数值时越界（越界的值在界面上会显示不出来）。
 */
class PresetsTest {

    private val feelKeys = setOf(
        "gravity", "restitution", "friction", "airDrag",
        "throwMultiplier", "throwSpin", "spinDamping", "swingDamping"
    )

    @Test
    fun everyPresetValueIsWithinSliderRange() {
        PetPresets.ALL.forEach { preset ->
            preset.values.forEach { (key, value) ->
                val spec = Params.specOf(key)
                assertNotNull("预设里有未知参数：$key", spec)
                spec!!
                assertTrue(
                    "预设 ${preset.nameRes} 的 $key=$value 超出范围 [${spec.min}, ${spec.max}]",
                    value >= spec.min && value <= spec.max
                )
            }
        }
    }

    @Test
    fun everyPresetCoversAllFeelParameters() {
        PetPresets.ALL.forEach { preset ->
            val missing = feelKeys - preset.values.keys
            if (missing.isNotEmpty()) {
                fail("预设 ${preset.nameRes} 缺少参数：$missing")
            }
        }
    }

    @Test
    fun presetsAreDistinct() {
        val seen = HashSet<Map<String, Float>>()
        PetPresets.ALL.forEach { preset ->
            assertTrue("预设 ${preset.nameRes} 与另一套重复", seen.add(preset.values))
        }
    }
}
