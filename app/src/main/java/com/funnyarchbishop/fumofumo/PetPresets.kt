package com.funnyarchbishop.fumofumo

import androidx.annotation.StringRes

/**
 * 一套参数预设。只动"手感"相关的量（重力/弹性/摩擦/甩出/阻尼），
 * 不动模型大小、占屏高度、环境光这些视觉设置 —— 换预设不会把宠物变大变小。
 */
data class PetPreset(
    @StringRes val nameRes: Int,
    val values: Map<String, Float>
)

object PetPresets {

    val ALL: List<PetPreset> = listOf(
        // 默认：像一只软软的玩偶
        PetPreset(
            R.string.preset_plush,
            mapOf(
                "gravity" to 2500f,
                "restitution" to 0.3f,
                "friction" to 0.8f,
                "airDrag" to 0.91f,
                "throwMultiplier" to 60f,
                "throwSpin" to 0.0012f,
                "spinDamping" to 3.5f,
                "swingDamping" to 2f
            )
        ),
        // 气球：轻飘飘，被甩出去慢慢荡
        PetPreset(
            R.string.preset_balloon,
            mapOf(
                "gravity" to 700f,
                "restitution" to 0.45f,
                "friction" to 0.9f,
                "airDrag" to 1.70f,
                "throwMultiplier" to 90f,
                "throwSpin" to 0.0006f,
                "spinDamping" to 1.2f,
                "swingDamping" to 0.7f
            )
        ),
        // 弹力球：落地来回蹦
        PetPreset(
            R.string.preset_bouncy,
            mapOf(
                "gravity" to 3200f,
                "restitution" to 0.85f,
                "friction" to 0.45f,
                "airDrag" to 0.30f,
                "throwMultiplier" to 80f,
                "throwSpin" to 0.002f,
                "spinDamping" to 2.5f,
                "swingDamping" to 1.5f
            )
        ),
        // 铁块：掉得快，落地就停，甩出去不怎么转
        PetPreset(
            R.string.preset_iron,
            mapOf(
                "gravity" to 5200f,
                "restitution" to 0.08f,
                "friction" to 0.95f,
                "airDrag" to 2.45f,
                "throwMultiplier" to 40f,
                "throwSpin" to 0.0005f,
                "spinDamping" to 6f,
                "swingDamping" to 4f
            )
        ),
        // 失重：不往下掉，甩出去慢慢飘
        PetPreset(
            R.string.preset_zerog,
            mapOf(
                "gravity" to 0f,
                "restitution" to 0.6f,
                "friction" to 0.2f,
                "airDrag" to 0.12f,
                "throwMultiplier" to 70f,
                "throwSpin" to 0.001f,
                "spinDamping" to 0.8f,
                "swingDamping" to 0.4f
            )
        )
    )
}
