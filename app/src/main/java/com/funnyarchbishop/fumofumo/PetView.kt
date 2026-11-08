package com.funnyarchbishop.fumofumo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.TextureView
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * 悬浮层里的宠物视图。
 *
 * 悬浮窗只包住模型，所以这里的触摸坐标是**窗口内**坐标，
 * 要加上窗口左上角偏移才换算成屏幕坐标，再翻成物理用的 y 向上坐标。
 */
class PetView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private val prefs = PetPrefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private var renderer: PetRenderer? = null
    private var mesh: MeshData? = null

    // 窗口在屏幕上的位置（y 向下）—— 只用来上报给 Service，输入不再依赖它
    private var screenHeight = 0

    var onLongPress: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onModelError: ((String) -> Unit)? = null
    var onWindowRect: ((Int, Int, Int) -> Unit)? = null

    private var dragging = false
    private var multiTouch = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var appliedX = 0f
    private var appliedY = 0f
    private var lastMultiX = 0f
    private var lastMultiY = 0f
    private var throwVx = 0f
    private var throwVy = 0f
    private var lastMoveTime = 0L

    private val longPressRunnable = Runnable {
        if (!multiTouch) {
            if (dragging) {
                dragging = false
                renderer?.endDrag(0f, 0f)
            }
            onLongPress?.invoke()
        }
    }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun loadModelAsync(file: File) {
        thread(name = "pet-model-loader") {
            try {
                val data = ModelImporter.load(file, context.cacheDir)
                mesh = data
                renderer?.setMesh(data)
                post { onMessage?.invoke("模型已载入：${data.triangleCount} 个三角面") }
            } catch (t: Throwable) {
                post { onModelError?.invoke(t.message ?: "未知错误") }
            }
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        val r = renderer
        renderer = null
        r?.shutdown()
        try {
            r?.join(800)
        } catch (_: InterruptedException) {
        }
    }

    /** 菜单打开时暂停物理，宠物停在原地 */
    fun setPaused(value: Boolean) {
        renderer?.setPaused(value)
    }

    // ---------------- SurfaceTexture ----------------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val metrics = resources.displayMetrics
        // 必须和渲染线程用同一个屏幕高度，否则手指坐标换算会差一截
        screenHeight = metrics.heightPixels
        val r = PetRenderer(
            surfaceTexture = st,
            prefs = prefs,
            customTexture = { ModelRepository.textureFile(context) },
            onMessage = { msg -> post { onMessage?.invoke(msg) } },
            onWindowRect = { left, top, size ->
                post { onWindowRect?.invoke(left, top, size) }
            }
        )
        r.setScreenSize(metrics.widthPixels, metrics.heightPixels)
        r.resize(width, height)
        mesh?.let { r.setMesh(it) }
        renderer = r
        r.start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        renderer?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val r = renderer
        renderer = null
        r?.shutdown()
        try {
            r?.join(800)
        } catch (_: InterruptedException) {
        }
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---------------- 手势 ----------------

    /**
     * 手指的屏幕坐标一律用 rawX / rawY。
     *
     * 千万不能用「窗口内坐标 + 窗口位置」还原：悬浮窗是跟着模型每帧移动的，
     * 缓存的位置和实际位置差一帧，算出来的坐标就会偏移一个窗口位移，
     * 于是"模型跳 → 窗口跟着跳 → 坐标再偏"，按住不动也会剧烈抖动。
     */
    private fun worldX(event: MotionEvent): Float = event.rawX

    private fun worldY(event: MotionEvent): Float {
        val screenH = if (screenHeight > 0) screenHeight else height
        return screenH - event.rawY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = renderer ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                downRawX = event.rawX
                downRawY = event.rawY
                lastMoveX = worldX(event)
                lastMoveY = worldY(event)
                appliedX = lastMoveX
                appliedY = lastMoveY
                throwVx = 0f
                throwVy = 0f
                lastMoveTime = SystemClock.uptimeMillis()
                dragging = r.hitTest(lastMoveX, lastMoveY)
                if (dragging) r.beginDrag(lastMoveX, lastMoveY)
                handler.postDelayed(longPressRunnable, 700)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) {
                    multiTouch = true
                    lastMultiX = event.getX(0)
                    lastMultiY = event.getY(0)
                    // 进入双指模式后不再沿用单指的速度
                    throwVx = 0f
                    throwVy = 0f
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.rawX - downRawX, event.rawY - downRawY) > 24f) {
                    handler.removeCallbacks(longPressRunnable)
                }
                if (!dragging) return true
                if (multiTouch && event.pointerCount >= 2) {
                    val mx = event.getX(0)
                    val my = event.getY(0)
                    val dx = mx - lastMultiX
                    val dy = my - lastMultiY
                    lastMultiX = mx
                    lastMultiY = my
                    r.spinBy(dx, -dy)
                } else {
                    val wx = worldX(event)
                    val wy = worldY(event)
                    // 1px 死区：手抖不会让模型跟着颤
                    if (hypot(wx - appliedX, wy - appliedY) < 1f) return true
                    throwVx = wx - lastMoveX
                    throwVy = wy - lastMoveY
                    lastMoveX = wx
                    lastMoveY = wy
                    appliedX = wx
                    appliedY = wy
                    lastMoveTime = SystemClock.uptimeMillis()
                    r.dragTo(wx, wy)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) multiTouch = false
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    dragging = false
                    // 松手前停了一下就别把旧速度甩出去（不然会莫名其妙地飞/转）
                    val stale = SystemClock.uptimeMillis() - lastMoveTime > 90L
                    if (stale) r.endDrag(0f, 0f) else r.endDrag(throwVx, throwVy)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    dragging = false
                    r.endDrag(0f, 0f)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
