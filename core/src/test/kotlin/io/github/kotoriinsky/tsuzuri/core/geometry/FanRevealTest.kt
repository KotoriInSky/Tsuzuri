package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 展开动画的时长与插值。
 *
 * 这一组断言的作用**不是**「覆盖逻辑」，而是给「core 与 hook 各有一份实现」这条已知债
 * 上一个**报警器**：真机侧（`FanOverlay`）的常量若被改动，这里的数字会立刻不对。
 * 所以断言里写的是**从真机公式算出来的确切值**（不是 `assertTrue(it > 0)` 这种）。
 */
class FanRevealTest {

    @Test
    fun baselineSpeedMatchesHyperGesture() {
        val t = FanAnimationTiming(speedPercent = 100f, count = 7)
        assertEquals(170f, t.revealMs)
        assertEquals(150f, t.rotationMs)
        // ⚠️ stagger 是 1200 / speed，**不乘 100** —— 100% 速度下每项错峰 12ms
        assertEquals(12f, t.staggerMs)
        assertEquals(110f, t.fadeMs)
        // total = max(170, 150 + 12×6) = 222
        assertEquals(222f, t.totalMs)
    }

    @Test
    fun slowerSpeedStretchesEveryStage() {
        val t = FanAnimationTiming(speedPercent = 50f, count = 1)
        assertEquals(340f, t.revealMs)
        assertEquals(300f, t.rotationMs)
        assertEquals(24f, t.staggerMs)
        assertEquals(220f, t.fadeMs)
        // 单槽时错峰项不存在，总时长由位移那段决定
        assertEquals(340f, t.totalMs)
    }

    @Test
    fun fasterSpeedClampsStaggerToItsFloor() {
        val t = FanAnimationTiming(speedPercent = 200f, count = 7)
        assertEquals(85f, t.revealMs)
        assertEquals(75f, t.rotationMs)
        // max(8, 1200/200=6) → 8：错峰有个 8ms 的下限，再快也不会变成 0
        assertEquals(8f, t.staggerMs)
        assertEquals(55f, t.fadeMs)
        // max(85, 75 + 8×6 = 123) = 123
        assertEquals(123f, t.totalMs)
    }

    @Test
    fun illegalSpeedFallsBackToBaseline() {
        // 0 / NaN 都会把「时长 = 基数 / 速度」算成无穷大 —— 那时扇形在真机上表现为
        // 「完全不出现」，而界面上只会看到一片空白。退基准速度是唯一安全的处置。
        for (bad in listOf(0f, -1f, Float.NaN)) {
            assertEquals(170f, FanAnimationTiming(bad, 7).revealMs, "speed=$bad")
        }
    }

    @Test
    fun easeOutBackStartsAtZeroEndsAtOneAndOvershootsInBetween() {
        assertEquals(0f, FanAnimation.easeOutBack(0f))
        assertEquals(1f, FanAnimation.easeOutBack(1f))
        // 过冲：中途必须超过 1（这正是「弹一下」的来源，抹平它就不是那个手感了）
        assertTrue(FanAnimation.easeOutBack(0.85f) > 1f, "easeOutBack 应当在末尾前过冲")
    }

    @Test
    fun firstFrameIsSmallAndFadedOut() {
        val a = FanAnimation(FanAnimationTiming(100f, 7), elapsedMs = 0f, rotationDegrees = 12f, openToRight = false)
        assertEquals(0f, a.reveal)
        assertEquals(0f, a.alpha)
        assertEquals(0.78f, a.diameterScale)
        // 第 0 项没有错峰延迟，起手就是满倾角；方向随贴左/贴右取反
        assertEquals(12f, a.rotationFor(0))
    }

    @Test
    fun endsAtFinalPositionsAndUpright() {
        val t = FanAnimationTiming(100f, 7)
        val a = FanAnimation(t, elapsedMs = t.totalMs, rotationDegrees = 12f, openToRight = true)
        assertEquals(1f, a.reveal)
        assertEquals(1f, a.alpha)
        assertEquals(1f, a.diameterScale)
        for (i in 0 until 7) assertEquals(0f, a.rotationFor(i), "第 $i 项到点后必须摆正")
        assertTrue(a.finished)
    }

    @Test
    fun laterItemsStillRotatedWhenEarlierOnesAreDone() {
        // 错峰的意义：同一时刻，越靠后的项越"歪"。
        // ⚠️ 判据里的 `p >= 1f → 0` 是 hook 原样搬过来的：`easeOutBack` 在 t≈0.6 就冲到 1
        // 以上，所以「转正」发生在自己那一段的六成处，而不是终点（这是实测手感，不要改）。
        val t = FanAnimationTiming(100f, 7)
        val a = FanAnimation(t, elapsedMs = 100f, rotationDegrees = 12f, openToRight = false)
        assertEquals(0f, a.rotationFor(0), "第 0 项（无错峰延迟）在 100ms 已经转正")
        assertTrue(a.rotationFor(6) > 0f, "第 6 项 100ms 时（延迟 72ms）还歪着")
    }
}
