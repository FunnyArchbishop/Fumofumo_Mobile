package com.funnyarchbishop.fumofumo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/**
 * 前台服务 + 悬浮窗。桌宠之所以能飘在其他应用之上，全靠这里。
 */
class PetService : Service() {

    companion object {
        const val ACTION_START = "com.funnyarchbishop.fumofumo.START"
        const val ACTION_STOP = "com.funnyarchbishop.fumofumo.STOP"
        const val ACTION_RELOAD = "com.funnyarchbishop.fumofumo.RELOAD"

        private const val CHANNEL_ID = "pet"
        private const val NOTIFICATION_ID = 42

        @Volatile
        var running: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var petView: PetView? = null

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
        view.onLongPress = { openSettings() }
        view.onMessage = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        windowManager.addView(view, params)
        petView = view

        val file = ModelRepository.file(this)
        if (file.isFile && file.length() > 0L) {
            view.loadModelAsync(file)
        } else {
            Toast.makeText(this, R.string.need_model_first, Toast.LENGTH_LONG).show()
        }
    }

    private fun hideOverlay() {
        val view = petView ?: return
        petView = null
        view.destroy()
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
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
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
