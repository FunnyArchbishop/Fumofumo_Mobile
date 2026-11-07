package com.funnyarchbishop.fumofumo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.TextureView
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * 悬浮层里的宠物视图。
 * 用 TextureView 渲染（而不是 GLSurfaceView），悬浮窗的透明背景才正常。
 */
class PetView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private val prefs = PetPrefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private var renderer: PetRenderer? = null
    private var mesh: MeshData? = null

    var onLongPress: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

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

    private val longPressRunnable = Runnable {
        if (dragging && !multiTouch) {
            dragging = false
            renderer?.endDrag(0f, 0f)
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
                val data = GlbLoader.load(file)
                mesh = data
                renderer?.setMesh(data)
                post { onMessage?.invoke("模型已载入：${data.triangleCount} 个三角面") }
            } catch (t: Throwable) {
                post { onMessage?.invoke("模型加载失败：${t.message}") }
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
        val r = PetRenderer(st, prefs) { msg -> post { onMessage?.invoke(msg) } }
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

    /** 屏幕 y（向下为正）→ 世界 y（向上为正），和桌面版坐标一致 */
    private fun toWorldY(y: Float): Float = height - y

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = renderer ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val wx = event.x
                val wy = toWorldY(event.y)
                if (!r.hitTest(wx, wy)) return false // 模型外的触摸透传给下面的应用
                dragging = true
                multiTouch = false
                downX = wx
                downY = wy
                lastMoveX = wx
                lastMoveY = wy
                throwVx = 0f
                throwVy = 0f
                r.beginDrag(wx, wy)
                handler.postDelayed(longPressRunnable, 550)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) {
                    multiTouch = true
                    lastMultiX = event.getX(0)
                    lastMultiY = event.getY(0)
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                // downX/downY 是世界坐标（y 向上），换算回屏幕坐标再算距离
                if (hypot(event.x - downX, event.y - (height - downY)) > 24f) {
                    handler.removeCallbacks(longPressRunnable)
                }
                if (multiTouch && event.pointerCount >= 2) {
                    val mx = event.getX(0)
                    val my = event.getY(0)
                    val dx = mx - lastMultiX
                    val dy = my - lastMultiY
                    lastMultiX = mx
                    lastMultiY = my
                    r.spinBy(dx, -dy) // 屏幕 y 向下，转成 y 向上
                } else {
                    val wx = event.x
                    val wy = toWorldY(event.y)
                    throwVx = wx - lastMoveX
                    throwVy = wy - lastMoveY
                    lastMoveX = wx
                    lastMoveY = wy
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
                    r.endDrag(throwVx, throwVy)
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
