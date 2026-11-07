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
        ParamSpec("airDrag", R.string.p_air_drag, 0.9f, 1f, 0.985f),
        ParamSpec("throwMultiplier", R.string.p_throw, 0f, 200f, 60f),
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

    var flipTexture: Boolean
        get() = sp.getBoolean(KEY_FLIP, true)
        set(value) {
            sp.edit().putBoolean(KEY_FLIP, value).apply()
        }

    fun resetDefaults() {
        val e = sp.edit()
        Params.LIST.forEach { e.putFloat(it.key, it.default) }
        e.putBoolean(KEY_FLIP, true)
        e.apply()
    }

    companion object {
        const val KEY_FLIP = "flipTexture"
    }
}
