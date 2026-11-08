package com.funnyarchbishop.fumofumo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 物理调参的回归测试：重点保证"甩出去不会一直乱转"。
 */
class PhysicsTest {

    private fun newPet(): PetPhysics = PetPhysics().apply {
        width = 1080f
        height = 1920f
        halfX = 100f
        halfY = 120f
        halfZ = 80f
        inertia = 8000f
        reset(540f, 900f)
    }

    private fun PetPhysics.run(seconds: Float, onStep: () -> Unit = {}) {
        val dt = 1f / 60f
        repeat((seconds / dt).toInt()) {
            step(dt, 2500f, 0.3f, 0.8f, 0.985f, 3.5f)
            onStep()
        }
    }

    @Test
    fun throwSpinIsClamped() {
        val pet = newPet()
        pet.grab(540f, 900f)
        // 非常快的甩动
        pet.release(300f, -300f, 60f, 0.0012f)
        val maxW = maxOf(abs(pet.wx), abs(pet.wy), abs(pet.wz))
        assertTrue("角速度必须被限幅，实际 $maxW", maxW <= 9.5f)
    }

    @Test
    fun throwSpinSettlesWithinOneSecond() {
        val pet = newPet()
        pet.grab(540f, 900f)
        pet.release(40f, -10f, 60f, 0.0012f)
        assertTrue("甩出后应有一点翻滚", abs(pet.wy) > 0.5f)

        pet.run(1.0f)
        assertTrue("1 秒后应基本停转，实际 wx=${pet.wx} wy=${pet.wy} wz=${pet.wz}",
            abs(pet.wx) < 0.5f && abs(pet.wy) < 0.5f && abs(pet.wz) < 0.5f)
    }

    @Test
    fun slowReleaseDoesNotSpin() {
        val pet = newPet()
        pet.grab(540f, 900f)
        // 停住再松手（速度 0）
        pet.release(0f, 0f, 60f, 0.0012f)
        assertEquals(0f, pet.wx, 1e-6f)
        assertEquals(0f, pet.wy, 1e-6f)
    }

    @Test
    fun comesToRestOnTheFloor() {
        val pet = newPet()
        pet.grab(540f, 900f)
        pet.release(0f, 0f, 60f, 0.0012f)
        pet.run(4.0f)
        val aabb = pet.computeAabb()
        assertTrue("应该落在屏幕底部，实际 minY=${aabb[1]}", abs(aabb[1]) < 12f)
        assertTrue("落地后速度应归零", abs(pet.vx) < 1f && abs(pet.vy) < 30f)
    }

    // ---------- 拖拽映射的契约 ----------
    // 视图层传进来的必须是"手指的屏幕绝对坐标"。下面两条保证：
    // 手指不动 → 宠物不动；手指动多少 → 宠物跟多少。

    @Test
    fun holdingStillKeepsPetStill() {
        val pet = newPet()
        pet.grab(540f, 900f)
        val startCx = pet.cx
        val startCy = pet.cy
        // 模拟按住不动：反复喂同一个坐标（真实设备上手指会有亚像素抖动）
        repeat(600) {
            pet.dragTo(540f, 900f)
            pet.dragTo(540.4f, 900.3f)
            pet.dragTo(540f, 900f)
        }
        assertEquals("按住不动时中心不该漂移", startCx, pet.cx, 1e-3f)
        assertEquals("按住不动时中心不该漂移", startCy, pet.cy, 1e-3f)
    }

    @Test
    fun draggedPointStaysUnderTheFinger() {
        val pet = newPet()
        // 抓在偏心的位置：中心 (540,900)，手指在 (600,950) → 抓取偏移 (60,50)
        pet.grab(600f, 950f)
        // 手指移到 (700,850)：中心 = 手指 - 抓取偏移
        pet.dragTo(700f, 850f)
        assertEquals(640f, pet.cx, 1e-3f)
        assertEquals(800f, pet.cy, 1e-3f)
        // 再移动同样的增量 (+100,-100)，位移也应完全一致
        pet.dragTo(800f, 750f)
        assertEquals(740f, pet.cx, 1e-3f)
        assertEquals(700f, pet.cy, 1e-3f)
    }
}
