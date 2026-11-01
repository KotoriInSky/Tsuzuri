package io.github.kotoriinsky.tsuzuri.core.gesture

import io.github.kotoriinsky.tsuzuri.core.gesture.CornerGesture.Corner
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 底角手势几何判定的单元测试。
 *
 * 这些断言的意义在于：**手势偶尔不灵**是这类特性最难查的故障，
 * 而判定全是纯数学。把边界钉死在测试里，真机上出问题时就只需要怀疑
 * 「事件有没有到」和「坐标对不对」，不用再怀疑角度算错。
 */
class CornerGestureTest {

    private val w = 1080f
    private val h = 2400f
    private val corner = 132f // 44dp @ 3x

    // ── 角落命中 ──────────────────────────────────────────────────────

    @Test
    fun `底部两角都命中`() {
        assertEquals(
            Corner.BOTTOM_LEFT,
            CornerGesture.cornerAt(10f, h - 10f, w, h, corner, true, true),
        )
        assertEquals(
            Corner.BOTTOM_RIGHT,
            CornerGesture.cornerAt(w - 10f, h - 10f, w, h, corner, true, true),
        )
    }

    @Test
    fun `顶部角落不算 —— 否则会和控制中心手势打架`() {
        assertNull(CornerGesture.cornerAt(10f, 10f, w, h, corner, true, true))
        assertNull(CornerGesture.cornerAt(w - 10f, 10f, w, h, corner, true, true))
    }

    @Test
    fun `底部中间不算`() {
        assertNull(CornerGesture.cornerAt(w / 2, h - 10f, w, h, corner, true, true))
    }

    @Test
    fun `刚出角块范围就不算`() {
        // 角块是 [0, corner)，所以 corner 本身已经在外面
        assertNull(CornerGesture.cornerAt(corner, h - 10f, w, h, corner, true, true))
        assertEquals(
            Corner.BOTTOM_LEFT,
            CornerGesture.cornerAt(corner - 1f, h - 10f, w, h, corner, true, true),
        )
    }

    @Test
    fun `被配置禁用的角不响应`() {
        assertNull(CornerGesture.cornerAt(10f, h - 10f, w, h, corner, allowLeft = false, allowRight = true))
        assertNull(CornerGesture.cornerAt(w - 10f, h - 10f, w, h, corner, allowLeft = true, allowRight = false))
    }

    // ── 方向 ──────────────────────────────────────────────────────────

    @Test
    fun `左下角必须朝右上 —— 朝左上是在往屏幕外滑`() {
        // dx > 0（向右=朝屏内）、dy > 0（向上）
        assertTrue(CornerGesture.isSwipe(0f, 2300f, 100f, 2200f, Corner.BOTTOM_LEFT, 48f))
        // dx < 0：往左，越出屏幕
        assertFalse(CornerGesture.isSwipe(100f, 2300f, 40f, 2200f, Corner.BOTTOM_LEFT, 48f))
        // dy < 0：往下
        assertFalse(CornerGesture.isSwipe(0f, 2300f, 100f, 2380f, Corner.BOTTOM_LEFT, 48f))
    }

    @Test
    fun `右下角必须朝左上 —— 和左下角镜像`() {
        assertTrue(CornerGesture.isSwipe(1080f, 2300f, 980f, 2200f, Corner.BOTTOM_RIGHT, 48f))
        assertFalse(CornerGesture.isSwipe(980f, 2300f, 1040f, 2200f, Corner.BOTTOM_RIGHT, 48f))
    }

    // ── 角度窗口 ──────────────────────────────────────────────────────

    @Test
    fun `纯水平被拒 —— 那是在和返回手势抢`() {
        // dx=200, dy=0 → 0 度
        assertFalse(CornerGesture.isSwipe(0f, 2300f, 200f, 2300f, Corner.BOTTOM_LEFT, 48f))
    }

    @Test
    fun `纯竖直被拒 —— 那是控制中心的手势`() {
        // dx=0, dy=200 → 90 度，超过上限 88
        assertFalse(CornerGesture.isSwipe(0f, 2300f, 0f, 2100f, Corner.BOTTOM_LEFT, 48f))
    }

    @Test
    fun `45 度是典型有效值`() {
        val angle = CornerGesture.inwardAngle(100f, 100f, Corner.BOTTOM_LEFT)
        assertEquals(45.0, angle!!, 0.001)
        assertTrue(CornerGesture.isAngleValid(angle))
    }

    @Test
    fun `角度窗口的两个边界`() {
        val min = CornerGesture.MIN_ANGLE_DEG
        val max = CornerGesture.MAX_ANGLE_DEG
        assertTrue(CornerGesture.isAngleValid(min), "下边界算有效（闭区间）")
        assertTrue(CornerGesture.isAngleValid(max), "上边界算有效（闭区间）")
        assertFalse(CornerGesture.isAngleValid(min - 0.01))
        assertFalse(CornerGesture.isAngleValid(max + 0.01))
    }

    @Test
    fun `角度算不出来时返回 null`() {
        assertNull(CornerGesture.inwardAngle(-10f, 50f, Corner.BOTTOM_LEFT))
        assertNull(CornerGesture.inwardAngle(50f, -10f, Corner.BOTTOM_LEFT))
        assertNull(CornerGesture.inwardAngle(0f, 0f, Corner.BOTTOM_RIGHT))
    }

    // ── 位移门槛 ──────────────────────────────────────────────────────

    @Test
    fun `位移不够就不触发 —— 防止手指抖动误触`() {
        // 45 度方向、位移约 14px，小于 48 的门槛
        assertFalse(CornerGesture.isSwipe(0f, 2300f, 10f, 2290f, Corner.BOTTOM_LEFT, 48f))
    }

    @Test
    fun `位移刚好达到门槛就算数（闭区间）`() {
        val d = 48f
        val dx = d / sqrt(2f)
        assertTrue(CornerGesture.isSwipe(0f, 2300f, dx, 2300f - dx, Corner.BOTTOM_LEFT, d))
    }

    @Test
    fun `门槛是欧氏距离，不是单看某一轴`() {
        // dx=40, dy=40 → 距离 56.6 > 48，但单轴都不到 48
        assertTrue(CornerGesture.isSwipe(0f, 2300f, 40f, 2260f, Corner.BOTTOM_LEFT, 48f))
    }
}
