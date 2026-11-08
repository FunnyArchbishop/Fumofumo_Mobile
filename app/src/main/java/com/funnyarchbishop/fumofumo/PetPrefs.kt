package com.funnyarchbishop.fumofumo

import android.content.Context
import androidx.annotation.StringRes

/** 一个可调参数的描述（设置界面用它自动生成滑条） */
data class ParamSpec(
    val key: String,
    @StringRes val labelRes: Int,
    val min: Float,
    val max: Float,
    val default: Float
)

object Params {
    val LIST = listOf(
        ParamSpec("gravity", R.string.p_gravity, 0f, 6000f, 2500f),
        ParamSpec("restitution", R.string.p_restitution, 0f, 1f, 0.3f),
        ParamSpec("friction", R.string.p_friction, 0f, 1f, 0.8f),
        // 空气阻力：0 = 无阻力；每秒指数衰减系数
        ParamSpec("airDrag", R.string.p_air_drag, 0f, 8f, 0.9f),
        ParamSpec("throwMultiplier", R.string.p_throw, 0f, 200f, 60f),
        ParamSpec("throwSpin", R.string.p_throw_spin, 0f, 0.01f, 0.0012f),
        ParamSpec("spinDamping", R.string.p_spin_damping, 0.5f, 12f, 3.5f),
        ParamSpec("swingDamping", R.string.p_swing_damping, 0f, 8f, 2f),
        ParamSpec("modelScale", R.string.p_scale, 0.2f, 3f, 1f),
        ParamSpec("heightRatio", R.string.p_height, 0.1f, 1f, 0.25f),
        ParamSpec("ambient", R.string.p_ambient, 0f, 1f, 0.65f),
        ParamSpec("gamma", R.string.p_gamma, 0.5f, 3f, 1.2f)
    )

    fun specOf(key: String): ParamSpec? = LIST.firstOrNull { it.key == key }
}

/**
 * 参数持久化。渲染线程每帧都会读，SharedPreferences 在内存里有缓存，开销可以忽略。
 */
class PetPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("pet_params", Context.MODE_PRIVATE)

    fun getFloat(key: String): Float {
        val spec = Params.specOf(key) ?: return 0f
        return sp.getFloat(key, spec.default)
    }

    fun setFloat(key: String, value: Float) {
        sp.edit().putFloat(key, value).apply()
    }

    /**
     * 贴图上下翻转。默认关闭：glTF / OBJ（本程序已把 UV 统一成 glTF 约定）都是正的，
     * 只有个别模型贴图会颠倒，那时在设置里打开。
     */
    var flipTexture: Boolean
        get() = sp.getBoolean(KEY_FLIP, false)
        set(value) {
            sp.edit().putBoolean(KEY_FLIP, value).apply()
        }

    fun resetDefaults() {
        val e = sp.edit()
        Params.LIST.forEach { e.putFloat(it.key, it.default) }
        e.putBoolean(KEY_FLIP, false)
        e.apply()
    }

    /** 应用一套预设（只写预设里列出的参数） */
    fun applyPreset(preset: PetPreset) {
        val e = sp.edit()
        preset.values.forEach { (key, value) -> e.putFloat(key, value) }
        e.apply()
    }

    /** 判断当前参数是否正好等于某套预设 */
    fun matchesPreset(preset: PetPreset): Boolean =
        preset.values.all { (key, value) -> kotlin.math.abs(getFloat(key) - value) < 1e-6f }

    companion object {
        const val KEY_FLIP = "flipTexture"
    }
}
