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
}
