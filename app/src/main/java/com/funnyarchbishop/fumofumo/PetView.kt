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

    // 窗口在屏幕上的位置（y 向下）
    private var winLeft = 0
    private var winTop = 0
    private var screenHeight = 0

    var onLongPress: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onModelError: ((String) -> Unit)? = null
    var onWindowRect: ((Int, Int, Int) -> Unit)? = null

    private var dragging = false
    private var multiTouch = false
    private var downX = 0f
    private var downY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
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

    // ---------------- SurfaceTexture ----------------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val metrics = resources.displayMetrics
        screenHeight = maxOf(metrics.heightPixels, metrics.widthPixels)
        val r = PetRenderer(
            surfaceTexture = st,
            prefs = prefs,
            customTexture = { ModelRepository.textureFile(context) },
            onMessage = { msg -> post { onMessage?.invoke(msg) } },
            onWindowRect = { left, top, size ->
                winLeft = left
                winTop = top
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

    private fun worldX(eventX: Float): Float = winLeft + eventX

    private fun worldY(eventY: Float): Float {
        val screenH = if (screenHeight > 0) screenHeight else height
        return screenH - (winTop + eventY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = renderer ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 窗口本来就只包住模型，所以这里一律接管；
                // 但只有真的按在模型上才开始拖拽（按在边缘留白处则是长按菜单）。
                multiTouch = false
                downX = event.x
                downY = event.y
                lastMoveX = worldX(event.x)
                lastMoveY = worldY(event.y)
                throwVx = 0f
                throwVy = 0f
                dragging = r.hitTest(lastMoveX, lastMoveY)
                if (dragging) r.beginDrag(lastMoveX, lastMoveY)
                handler.postDelayed(longPressRunnable, 500)
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
                if (hypot(event.x - downX, event.y - downY) > 24f) {
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
                    val wx = worldX(event.x)
                    val wy = worldY(event.y)
                    throwVx = wx - lastMoveX
                    throwVy = wy - lastMoveY
                    lastMoveX = wx
                    lastMoveY = wy
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
