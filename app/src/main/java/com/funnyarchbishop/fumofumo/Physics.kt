package com.funnyarchbishop.fumofumo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 桌面宠物的物理：屏幕平面内是 2D 刚体（重力 / 弹跳 / 摩擦 / 自转），
 * 模型本身还有一路自由的 3D 自转（wx / wy / wz），从 WINobj.py 移植。
 *
 * 世界坐标：像素，原点左下角，y 轴向上（和桌面版一致，渲染矩阵可以照搬）。
 */
class PetPhysics {

    var width = 1080f
    var height = 1920f

    var cx = 0f
    var cy = 0f
    var vx = 0f
    var vy = 0f

    /** 3x3 旋转矩阵，行主序：world = rot * local */
    var rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    var wx = 0f
    var wy = 0f
    var wz = 0f

    /** 转动惯量（质量取 1） */
    var inertia = 8000f

    /** 局部半尺寸（模型已居中、已按屏幕缩放） */
    var halfX = 1f
    var halfY = 1f
    var halfZ = 1f

    var dragging = false
        private set

    private val localGrab = FloatArray(3)
    private val cornerX = FloatArray(8)
    private val cornerY = FloatArray(8)

    fun reset(x: Float, y: Float) {
        cx = x
        cy = y
        vx = 0f
        vy = 0f
        wx = 0f
        wy = 0f
        wz = 0f
        rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        dragging = false
    }

    // ---------------- 拖拽 ----------------

    fun grab(worldX: Float, worldY: Float) {
        val dx = worldX - cx
        val dy = worldY - cy
        // localGrab = R^T * d
        localGrab[0] = rot[0] * dx + rot[3] * dy
        localGrab[1] = rot[1] * dx + rot[4] * dy
        localGrab[2] = rot[2] * dx + rot[5] * dy
        dragging = true
        vx = 0f
        vy = 0f
    }

    fun dragTo(worldX: Float, worldY: Float) {
        val lx = localGrab[0]
        val ly = localGrab[1]
        val lz = localGrab[2]
        val rx = rot[0] * lx + rot[1] * ly + rot[2] * lz
        val ry = rot[3] * lx + rot[4] * ly + rot[5] * lz
        cx = worldX - rx
        cy = worldY - ry
        vx = 0f
        vy = 0f
    }

    fun release(throwVx: Float, throwVy: Float, throwMultiplier: Float, throwSpin: Float) {
        dragging = false
        vx = throwVx * throwMultiplier
        vy = throwVy * throwMultiplier
        // 甩出去带一点翻滚，但要有上限，否则一甩就转个不停
        wy += (vx * throwSpin).coerceIn(-MAX_SPIN, MAX_SPIN)
        wx += (-vy * throwSpin).coerceIn(-MAX_SPIN, MAX_SPIN)
    }

    /** 双指手势：直接叠加一次旋转 */
    fun rotateBy(dWx: Float, dWy: Float, dWz: Float, dt: Float) {
        if (abs(dWx) < 1e-6f && abs(dWy) < 1e-6f && abs(dWz) < 1e-6f) return
        rot = mul3(rotationMatrix(dWx, dWy, dWz, dt), rot)
    }

    // ---------------- 每帧推进 ----------------

    fun step(
        dt: Float,
        gravity: Float,
        restitution: Float,
        friction: Float,
        airDrag: Float,
        spinDamping: Float
    ) {
        if (dragging) return

        vy -= gravity * dt
        vx *= airDrag
        vy *= airDrag

        // 角速度按时间衰减（和帧率无关）。转得越快衰减得越快，免得一直打转
        val spinDecay = exp(-(spinDamping.coerceAtLeast(0f)) * dt).toFloat()
        wx *= spinDecay
        wy *= spinDecay
        wz *= spinDecay
        wx = wx.coerceIn(-MAX_SPIN, MAX_SPIN)
        wy = wy.coerceIn(-MAX_SPIN, MAX_SPIN)
        wz = wz.coerceIn(-MAX_SPIN, MAX_SPIN)
        if (abs(wx) < 0.02f) wx = 0f
        if (abs(wy) < 0.02f) wy = 0f
        if (abs(wz) < 0.02f) wz = 0f

        cx += vx * dt
        cy += vy * dt

        if (wx != 0f || wy != 0f || wz != 0f) {
            rot = mul3(rotationMatrix(wx, wy, wz, dt), rot)
        }

        val aabb = computeAabb()
        val minX = aabb[0]
        val minY = aabb[1]
        val maxX = aabb[2]
        val maxY = aabb[3]

        if (minY < 0f) {
            cy -= minY
            resolve(contactXAtBottom(minY), minY, 0f, 1f, restitution, friction)
        } else if (maxY > height) {
            cy -= (maxY - height)
            resolve(contactXAtTop(maxY), maxY, 0f, -1f, restitution, friction)
        }
        if (minX < 0f) {
            cx -= minX
            resolve(minX, contactYAtLeft(minX), 1f, 0f, restitution, friction)
        } else if (maxX > width) {
            cx -= (maxX - width)
            resolve(maxX, contactYAtRight(maxX), -1f, 0f, restitution, friction)
        }

        // 落地后逐渐安静下来，别在地面上抖
        if (minY < 10f && abs(vy) < 50f) {
            vx *= 0.85f
            wz *= 0.8f
            wx *= 0.85f
            wy *= 0.85f
            if (abs(vx) < 3f && abs(wz) < 0.05f) {
                vx = 0f
                wz = 0f
                wx *= 0.5f
                wy *= 0.5f
                if (abs(wx) < 0.05f) wx = 0f
                if (abs(wy) < 0.05f) wy = 0f
            }
        }
    }

    /** 返回 [minX, minY, maxX, maxY] */
    fun computeAabb(): FloatArray {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 8) {
            val lx = if (i and 1 == 0) -halfX else halfX
            val ly = if (i and 2 == 0) -halfY else halfY
            val lz = if (i and 4 == 0) -halfZ else halfZ
            val px = rot[0] * lx + rot[1] * ly + rot[2] * lz + cx
            val py = rot[3] * lx + rot[4] * ly + rot[5] * lz + cy
            cornerX[i] = px
            cornerY[i] = py
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun contactXAtBottom(minY: Float): Float = meanXWhere { cornerY[it] <= minY + 1.5f }
    private fun contactXAtTop(maxY: Float): Float = meanXWhere { cornerY[it] >= maxY - 1.5f }
    private fun contactYAtLeft(minX: Float): Float = meanYWhere { cornerX[it] <= minX + 1.5f }
    private fun contactYAtRight(maxX: Float): Float = meanYWhere { cornerX[it] >= maxX - 1.5f }

    private inline fun meanXWhere(predicate: (Int) -> Boolean): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until 8) if (predicate(i)) {
            sum += cornerX[i]; n++
        }
        return if (n == 0) cx else sum / n
    }

    private inline fun meanYWhere(predicate: (Int) -> Boolean): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until 8) if (predicate(i)) {
            sum += cornerY[i]; n++
        }
        return if (n == 0) cy else sum / n
    }

    // ---------------- 碰撞冲量 ----------------

    private fun resolve(
        contactX: Float,
        contactY: Float,
        nx: Float,
        ny: Float,
        restitution: Float,
        friction: Float
    ) {
        val rx = contactX - cx
        val ry = contactY - cy
        var vpx = vx - wz * ry
        var vpy = vy + wz * rx
        val vn = vpx * nx + vpy * ny
        if (vn >= 0f) return

        val invI = 1f / inertia
        val rn = rx * ny - ry * nx
        val jn = -(1f + restitution) * vn / (1f + rn * rn * invI)
        vx += jn * nx
        vy += jn * ny
        wz += (rx * (jn * ny) - ry * (jn * nx)) * invI

        vpx = vx - wz * ry
        vpy = vy + wz * rx
        val vt = vpx * -ny + vpy * nx
        val rt = rx * nx + ry * ny
        val limit = friction * abs(jn)
        val jt = (-vt / (1f + rt * rt * invI)).coerceIn(-limit, limit)
        vx += jt * -ny
        vy += jt * nx
        wz += (rx * (jt * nx) - ry * (jt * -ny)) * invI
    }

    // ---------------- 3x3 矩阵 ----------------

    private fun rotationMatrix(ax: Float, ay: Float, az: Float, dt: Float): FloatArray {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-6f) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val x = ax / len
        val y = ay / len
        val z = az / len
        val angle = len * dt
        val c = cos(angle)
        val s = sin(angle)
        val t = 1f - c
        return floatArrayOf(
            x * x * t + c, x * y * t - z * s, x * z * t + y * s,
            y * x * t + z * s, y * y * t + c, y * z * t - x * s,
            z * x * t - y * s, z * y * t + x * s, z * z * t + c
        )
    }

    private fun mul3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) sum += a[r * 3 + k] * b[k * 3 + c]
                out[r * 3 + c] = sum
            }
        }
        return out
    }

    companion object {
        /** 角速度上限（弧度/秒），防止被打转 */
        private const val MAX_SPIN = 9f
    }
}
