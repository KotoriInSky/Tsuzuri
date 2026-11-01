package io.github.kotoriinsky.tsuzuri.core.gesture

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 扇形菜单几何的单元测试。
 *
 * 「手势选了错的应用」这种问题在真机上极难复现和定位，而它几乎总是几何算错。
 * 这里把 Flyme 的两条公式和命中规则钉死。
 */
class FanLayoutTest {

    // ── 角度分布（照 Flyme 的 m(i, n)） ────────────────────────────────

    @Test
    fun `四项时按 Flyme 公式分布在 90 度内`() {
        // (90 / (4+1)) * (i+1) = 18 * (i+1) → 18, 36, 54, 72
        assertEquals(18.0, FanLayout.itemAngle(0, 4), 1e-9)
        assertEquals(36.0, FanLayout.itemAngle(1, 4), 1e-9)
        assertEquals(54.0, FanLayout.itemAngle(2, 4), 1e-9)
        assertEquals(72.0, FanLayout.itemAngle(3, 4), 1e-9)
    }

    @Test
    fun `单项时落在扇中间 —— 这是 n+1 分母的意义`() {
        // 若分母是 n 而不是 n+1，(0,1) 会得到 90°，正好贴在边界上
        assertEquals(45.0, FanLayout.itemAngle(0, 1), 1e-9)
    }

    @Test
    fun `角度单调递增且永不贴到 0 或 90`() {
        val angles = FanLayout.itemAngles(6)
        assertEquals(6, angles.size)
        for (i in 1 until angles.size) {
            assertTrue(angles[i] > angles[i - 1], "角度必须严格递增：$angles")
        }
        assertTrue(angles.first() > 0.0)
        assertTrue(angles.last() < FanLayout.SPAN_DEG)
    }

    @Test
    fun `count 为 0 或负数不炸`() {
        assertEquals(0.0, FanLayout.itemAngle(0, 0), 1e-9)
        assertEquals(0.0, FanLayout.itemAngle(0, -3), 1e-9)
        assertTrue(FanLayout.itemAngles(0).isEmpty())
    }

    @Test
    fun `留边距时走第二条公式`() {
        // inset=10, n=4 → step = (90-20)/4 = 17.5；angle = i*17.5 + 8.75 + 10
        assertEquals(18.75, FanLayout.itemAngle(0, 4, insetDeg = 10.0), 1e-9)
        assertEquals(36.25, FanLayout.itemAngle(1, 4, insetDeg = 10.0), 1e-9)
        // 整体仍落在 [inset, 90-inset] 内
        val angles = FanLayout.itemAngles(4, insetDeg = 10.0)
        assertTrue(angles.all { it >= 10.0 && it <= 80.0 }, "$angles")
    }

    // ── 极角 ──────────────────────────────────────────────────────────

    @Test
    fun `正上方是 0 度 正侧方是 90 度`() {
        assertEquals(0.0, FanLayout.polarAngle(0f, -100f, 0f, 0f, openToRight = false)!!, 1e-6)
        assertEquals(90.0, FanLayout.polarAngle(100f, 0f, 0f, 0f, openToRight = false)!!, 1e-6)
    }

    @Test
    fun `45 度方向`() {
        // 屏幕坐标：y 越小越靠上，所以「右上 45°」是 (x+r, y-r)
        assertEquals(45.0, FanLayout.polarAngle(100f, -100f, 0f, 0f, openToRight = false)!!, 1e-6)
    }

    @Test
    fun `圆心正下方是 180 度 —— 落在扇外，永远命不中`() {
        // atan2(0, 负数) = +180°（不是 -180°）。无论是哪个值，都不在 [0, 90] 区间里，
        // 所以「手指滑到圆下方」必然不命中 —— 这正是我们要的语义。
        val a = FanLayout.polarAngle(0f, 100f, 0f, 0f, openToRight = false)!!
        assertTrue(a > FanLayout.SPAN_DEG || a < 0.0, "圆心下方不该落在扇内，实际 $a")
        assertEquals(-1, FanLayout.hitTest(a, 200.0, FanLayout.itemAngles(4), 100.0, 400.0))
    }

    @Test
    fun `手指正好在圆心时角度无意义返回 null`() {
        assertNull(FanLayout.polarAngle(50f, 50f, 50f, 50f, openToRight = false))
    }

    @Test
    fun `极角与距离无关 —— 这是对 Flyme 那处偏离的核心保证`() {
        // Flyme 用常量半径做 acos，距离一变就可能 NaN。这里同方向不同距离必须同角度。
        val near = FanLayout.polarAngle(10f, -10f, 0f, 0f, openToRight = false)!!
        val far = FanLayout.polarAngle(1000f, -1000f, 0f, 0f, openToRight = false)!!
        assertEquals(near, far, 1e-6)
        assertFalse(near.isNaN())
    }

    // ── 命中 ──────────────────────────────────────────────────────────

    @Test
    fun `落在某一项的角度带内就选中它`() {
        val angles = FanLayout.itemAngles(4) // 18/36/54/72，step=22.5，half=11.25
        val inner = 100.0
        val outer = 400.0
        assertEquals(0, FanLayout.hitTest(20.0, 200.0, angles, inner, outer))
        assertEquals(1, FanLayout.hitTest(40.0, 200.0, angles, inner, outer))
        assertEquals(2, FanLayout.hitTest(54.0, 200.0, angles, inner, outer))
        assertEquals(3, FanLayout.hitTest(70.0, 200.0, angles, inner, outer))
    }

    @Test
    fun `距离不足时未命中 —— 手指还没滑出触发区`() {
        val angles = FanLayout.itemAngles(4)
        assertEquals(-1, FanLayout.hitTest(40.0, 50.0, angles, inner = 100.0, outer = 400.0))
    }

    @Test
    fun `距离过头时未命中 —— 滑出扇外`() {
        val angles = FanLayout.itemAngles(4)
        assertEquals(-1, FanLayout.hitTest(40.0, 900.0, angles, inner = 100.0, outer = 400.0))
    }

    @Test
    fun `角度在项之间落空时未命中`() {
        val angles = FanLayout.itemAngles(4)
        // 18-11.25 = 6.75 是第 0 项的下边界，再往下一档就落空
        assertEquals(-1, FanLayout.hitTest(3.0, 200.0, angles, 100.0, 400.0))
        // 72+11.25 = 83.25 之上也落空
        assertEquals(-1, FanLayout.hitTest(88.0, 200.0, angles, 100.0, 400.0))
    }

    @Test
    fun `角度带是左闭右开 —— 与 Flyme 的 q() 一致`() {
        val angles = FanLayout.itemAngles(4)
        val boundary = 18.0 + 11.25 // 第 0 项上边界，同时也是第 1 项下边界
        assertEquals(1, FanLayout.hitTest(boundary, 200.0, angles, 100.0, 400.0))
    }

    @Test
    fun `空列表返回未命中`() {
        assertEquals(-1, FanLayout.hitTest(45.0, 200.0, emptyList(), 100.0, 400.0))
    }

    // ── 展开方向与圆心 ────────────────────────────────────────────────

    @Test
    fun `按触摸落在哪半屏决定贴左还是贴右`() {
        assertFalse(FanLayout.opensToRight(touchX = 100f, screenWidth = 1000f))
        assertTrue(FanLayout.opensToRight(touchX = 900f, screenWidth = 1000f))
        // 正中间算右 —— Flyme 是严格的 `> W/2` 判右，所以中线上应该贴左
        assertFalse(FanLayout.opensToRight(touchX = 500f, screenWidth = 1000f))
    }

    @Test
    fun `圆心贴在触发侧的边、纵向取屏幕中心`() {
        val (leftX, leftY) = FanLayout.center(openToRight = false, screenWidth = 1080f, screenHeight = 2400f)
        assertEquals(0f, leftX)
        assertEquals(1200f, leftY)

        val (rightX, rightY) = FanLayout.center(openToRight = true, screenWidth = 1080f, screenHeight = 2400f)
        assertEquals(1080f, rightX)
        assertEquals(1200f, rightY)
    }

    @Test
    fun `贴右展开时 手指在右上方 应命中第一项`() {
        // 端到端串一遍：屏幕 1080x2400，贴右下角展开，手指在圆心左上方偏上方向
        val (cx, cy) = FanLayout.center(openToRight = true, screenWidth = 1080f, screenHeight = 2400f)
        val x = cx - 120f
        val y = cy - 400f
        val angle = FanLayout.polarAngle(x, y, cx, cy, openToRight = true)!!
        val radius = FanLayout.radius(x, y, cx, cy)
        val angles = FanLayout.itemAngles(4)
        // 方向是「左上 17° 左右」，应落在第 0 项的带里（0..29.25）
        assertTrue(abs(angle) < 29.25, "角度 $angle 超出第 0 项范围")
        assertEquals(0, FanLayout.hitTest(angle, radius, angles, inner = 100.0, outer = 600.0))
    }
}
