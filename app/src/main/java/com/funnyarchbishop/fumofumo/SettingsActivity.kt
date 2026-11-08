package com.funnyarchbishop.fumofumo

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.util.Locale

/**
 * 参数面板：滑条直接写 SharedPreferences，渲染线程每帧读取，所以是实时生效的。
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { PetPrefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        buildPresetRow()

        val container = findViewById<LinearLayout>(R.id.paramContainer)
        Params.LIST.forEach { spec -> container.addView(buildRow(spec)) }
        container.addView(buildFlipSwitch())

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            prefs.resetDefaults()
            recreate()
        }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
    }

    /** 一排预设按钮：点一下把整套手感参数换掉 */
    private fun buildPresetRow() {
        val row = findViewById<LinearLayout>(R.id.presetRow)
        val active = PetPresets.ALL.firstOrNull { prefs.matchesPreset(it) }

        PetPresets.ALL.forEach { preset ->
            val isActive = preset === active
            val button = Button(this).apply {
                text = getString(preset.nameRes)
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setTextColor(if (isActive) 0xFF101418.toInt() else 0xFFE8ECF4.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (isActive) 0xFF00BFFF.toInt() else 0xFF2A3140.toInt())
                }
                setOnClickListener {
                    prefs.applyPreset(preset)
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.preset_applied, getString(preset.nameRes)),
                        Toast.LENGTH_SHORT
                    ).show()
                    recreate()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = dp(8)
            row.addView(button, params)
        }
    }

    private fun buildRow(spec: ParamSpec): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val label = TextView(this).apply {
            text = getString(spec.labelRes)
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val value = TextView(this).apply {
            setTextColor(Color.parseColor("#00BFFF"))
            textSize = 14f
        }
        header.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(value)

        val seek = SeekBar(this).apply {
            max = STEPS
            progress = toProgress(spec, prefs.getFloat(spec.key))
        }
        value.text = format(fromProgress(spec, seek.progress))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = fromProgress(spec, progress)
                value.text = format(v)
                if (fromUser) prefs.setFloat(spec.key, v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        box.addView(header)
        box.addView(seek)
        return box
    }

    private fun buildFlipSwitch(): View {
        return SwitchCompat(this).apply {
            text = getString(R.string.p_flip)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(16), 0, dp(8))
            isChecked = prefs.flipTexture
            setOnCheckedChangeListener { _, checked -> prefs.flipTexture = checked }
        }
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun toProgress(spec: ParamSpec, value: Float): Int {
        val ratio = (value - spec.min) / (spec.max - spec.min)
        return (ratio * STEPS).toInt().coerceIn(0, STEPS)
    }

    private fun fromProgress(spec: ParamSpec, progress: Int): Float {
        val ratio = progress.toFloat() / STEPS
        return spec.min + ratio * (spec.max - spec.min)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STEPS = 1000
    }
}
