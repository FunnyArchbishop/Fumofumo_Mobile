package com.funnyarchbishop.fumofumo

import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast

/**
 * 前台服务 + 悬浮窗。
 *
 * 窗口**只包住模型**并跟着它移动 —— 这是让其他应用还能正常点击的关键：
 * 全屏窗口会把所有触摸都吃掉（Android 按窗口边界决定触摸归属，视图里 return false 没用）。
 */
class PetService : Service() {

    companion object {
        const val ACTION_START = "com.funnyarchbishop.fumofumo.START"
        const val ACTION_STOP = "com.funnyarchbishop.fumofumo.STOP"
        const val ACTION_RELOAD = "com.funnyarchbishop.fumofumo.RELOAD"

        private const val CHANNEL_ID = "pet"
        private const val NOTIFICATION_ID = 42
        private const val INITIAL_WINDOW_SIZE = 320

        @Volatile
        var running: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var petView: PetView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var menuDialog: Dialog? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> {
                if (running) {
                    hideOverlay()
                    showOverlay()
                }
                return START_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay_first, Toast.LENGTH_LONG).show()
            shutdown()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        running = true
        showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 悬浮窗 ----------------

    private fun showOverlay() {
        if (petView != null) return
        val view = PetView(this)
        view.onLongPress = { mainHandler.post { showMenu() } }
        view.onMessage = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        view.onModelError = { msg ->
            Toast.makeText(this, getString(R.string.load_failed, msg), Toast.LENGTH_LONG).show()
            // 模型读不出来就别留一个空窗口挡着别人点屏幕
            mainHandler.postDelayed({ shutdown() }, 1500)
        }
        view.onWindowRect = { left, top, size -> applyWindowRect(left, top, size) }

        val params = WindowManager.LayoutParams(
            INITIAL_WINDOW_SIZE,
            INITIAL_WINDOW_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        windowManager.addView(view, params)
        petView = view
        windowParams = params

        val file = ModelRepository.modelFile(this)
        if (file.isFile && file.length() > 0L) {
            view.loadModelAsync(file)
        } else {
            Toast.makeText(this, R.string.need_model_first, Toast.LENGTH_LONG).show()
            mainHandler.postDelayed({ shutdown() }, 1200)
        }
    }

    private fun applyWindowRect(left: Int, top: Int, size: Int) {
        mainHandler.post {
            val params = windowParams ?: return@post
            val view = petView ?: return@post
            if (params.x == left && params.y == top &&
                params.width == size && params.height == size
            ) {
                return@post
            }
            params.x = left
            params.y = top
            params.width = size
            params.height = size
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: IllegalArgumentException) {
                // 视图已经被移除，忽略
            }
        }
    }

    private fun hideOverlay() {
        menuDialog?.dismiss()
        menuDialog = null
        val view = petView ?: return
        petView = null
        windowParams = null
        view.destroy()
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------------- 长按菜单 ----------------

    private fun showMenu() {
        if (petView == null) return
        menuDialog?.dismiss()
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setContentView(R.layout.dialog_pet_menu)
        dialog.setCanceledOnTouchOutside(true)
        dialog.findViewById<Button>(R.id.menuSettings).setOnClickListener {
            dialog.dismiss()
            openSettings()
        }
        dialog.findViewById<Button>(R.id.menuStop).setOnClickListener {
            dialog.dismiss()
            shutdown()
        }
        dialog.findViewById<Button>(R.id.menuCancel).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            menuDialog = null
            petView?.setPaused(false)
        }
        menuDialog = dialog
        // 菜单开着的时候把物理停掉，宠物就挂在原地不会乱动
        petView?.setPaused(true)
        dialog.show()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun shutdown() {
        hideOverlay()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notif_channel_desc)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val settings = PendingIntent.getActivity(
            this, 1,
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val actionIcon = Icon.createWithResource(this, R.drawable.ic_pet)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_pet)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    actionIcon, getString(R.string.notif_action_settings), settings
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    actionIcon, getString(R.string.notif_action_stop), stop
                ).build()
            )
            .build()
    }
}
