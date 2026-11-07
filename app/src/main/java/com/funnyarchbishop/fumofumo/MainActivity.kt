package com.funnyarchbishop.fumofumo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var modelStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var btnToggle: Button

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importModel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelStatus = findViewById(R.id.modelStatus)
        overlayStatus = findViewById(R.id.overlayStatus)
        notifStatus = findViewById(R.id.notifStatus)
        btnToggle = findViewById(R.id.btnToggle)

        findViewById<Button>(R.id.btnPickModel).setOnClickListener {
            pickModel.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
        }
        btnToggle.setOnClickListener { togglePet() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        overlayStatus.setOnClickListener { requestOverlayPermission() }

        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------- 状态 ----------------

    private fun refreshStatus() {
        val file = ModelRepository.file(this)
        modelStatus.text = if (file.isFile && file.length() > 0L) {
            getString(
                R.string.model_current,
                file.name,
                String.format(Locale.US, "%.1f MB", file.length() / 1048576f)
            )
        } else {
            getString(R.string.model_none)
        }

        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatus.text =
            getString(if (canOverlay) R.string.overlay_ok else R.string.overlay_missing)
        overlayStatus.setTextColor(if (canOverlay) 0xFF4CAF50.toInt() else 0xFFFFB74D.toInt())
        overlayStatus.isClickable = !canOverlay

        val needNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        notifStatus.visibility = if (needNotif) View.VISIBLE else View.GONE

        btnToggle.text = getString(if (PetService.running) R.string.btn_stop else R.string.btn_start)
    }

    // ---------------- 动作 ----------------

    private fun togglePet() {
        if (PetService.running) {
            startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_STOP))
            btnToggle.postDelayed({ refreshStatus() }, 300)
            return
        }
        if (!ModelRepository.exists(this)) {
            Toast.makeText(this, R.string.need_model_first, Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay_first, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, PetService::class.java).setAction(PetService.ACTION_START)
        )
        btnToggle.postDelayed({ refreshStatus() }, 400)
    }

    private fun importModel(uri: Uri) {
        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
        thread(name = "model-import") {
            try {
                val file = ModelRepository.import(this, uri)
                runOnUiThread {
                    refreshStatus()
                    Toast.makeText(
                        this,
                        String.format(Locale.US, "模型已导入（%.1f MB）", file.length() / 1048576f),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (PetService.running) {
                        startService(
                            Intent(this, PetService::class.java)
                                .setAction(PetService.ACTION_RELOAD)
                        )
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.import_failed, t.message ?: "未知错误"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, "请到系统设置里手动开启「显示在其他应用上层」", Toast.LENGTH_LONG).show()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
