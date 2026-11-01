package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 侧滑索引的起手热区（[SideIndexHotZone]）。
 *
 * 钉三件事：
 *  1. **判定逐位不变**：[legacy] 是搬进 core 之前 `SideIndexDetector.handle` 的
 *     ACTION_DOWN 三道闸原文，在一张覆盖边界的网格上与 [SideIndexHotZone.classify] 逐点比对；
 *  2. **画出来的矩形就是能起手的区域**：矩形内部的点一律判成起手，外面一圈判成拒绝；
 *  3. **退化**：宽度非有限 / ≤0、保留区吃光屏高时不出矩形。
 */
class SideIndexHotZoneTest {

    /** 本机（Xiaomi 17 Pro）：1220 × 2656，密度 3.25。 */
    private val w = 1220f
    private val h = 2656f
    private val density = 3.25f
    private val top = SideIndexHotZone.TOP_GUARD_DP * density
    private val bottom = SideIndexHotZone.CORNER_GUARD_DP * density

    private enum class Old { BOTTOM, TOP, OUTSIDE, LEFT, RIGHT }

    /** 改造前 hook 的写法，逐行照抄（`SideIndex.kt` DOWN 分支，2026-10-21 之前）。 */
    private fun legacy(
        x: Float, y: Float, edgePx: Float, allowLeft: Boolean, allowRight: Boolean,
        cornerGuardPx: Float, topGuardPx: Float,
    ): Old {
        if (y > h - cornerGuardPx) return Old.BOTTOM
        if (y < topGuardPx) return Old.TOP
        val inLeft = allowLeft && x < edgePx
        val inRight = allowRight && x > w - edgePx
        if (!inLeft && !inRight) return Old.OUTSIDE
        val onRight = inRight
        return if (onRight) Old.RIGHT else Old.LEFT
    }

    private fun SideIndexHotZone.Verdict.asOld(): Old = when (this) {
        SideIndexHotZone.Verdict.BOTTOM_GUARD -> Old.BOTTOM
        SideIndexHotZone.Verdict.TOP_GUARD -> Old.TOP
        SideIndexHotZone.Verdict.OUTSIDE -> Old.OUTSIDE
        SideIndexHotZone.Verdict.LEFT -> Old.LEFT
        SideIndexHotZone.Verdict.RIGHT -> Old.RIGHT
    }

    @Test
    fun classifyMatchesTheOldDetectorOnEveryGridPoint() {
        // 边界值本身（== edge、== H - guard、== top）都在网格里：严格不等号是最容易被「顺手改成 <=」的地方
        val edges = listOf(0f, 1f, 20f * density, 40f * density, w / 2f, w / 2f + 1f, w)
        val xs = buildList {
            for (e in edges) { add(e - 1f); add(e); add(e + 1f); add(w - e - 1f); add(w - e); add(w - e + 1f) }
            add(w / 2f)
        }
        val ys = listOf(-1f, 0f, top - 1f, top, top + 1f, h / 2f, h - bottom - 1f, h - bottom, h - bottom + 1f, h, h + 1f)
        var n = 0
        for (edge in edges) for (l in listOf(false, true)) for (r in listOf(false, true)) {
            // 关态（TriggerConfig.DISABLED）：保留区为 0
            for ((cg, tg) in listOf(bottom to top, 0f to 0f)) {
                for (x in xs) for (y in ys) {
                    val expected = legacy(x, y, edge, l, r, cg, tg)
                    val actual = SideIndexHotZone.classify(x, y, w, h, edge, l, r, tg, cg).asOld()
                    assertEquals(expected, actual, "x=$x y=$y edge=$edge L=$l R=$r guards=$cg/$tg")
                    n++
                }
            }
        }
        assertTrue(n > 5000, "网格太小（$n），钉不住边界")
    }

    @Test
    fun bothSidesHitAtOnceGoesRightLikeBefore() {
        // 带宽超过半屏时左右同时命中：旧代码 `onRight = inRight`，归右
        val v = SideIndexHotZone.classify(w / 2f, h / 2f, w, h, w, true, true, top, bottom)
        assertEquals(SideIndexHotZone.Verdict.RIGHT, v)
    }

    @Test
    fun zonesAreExactlyTheRegionsThatStartAGesture() {
        val widthDp = 20f
        val zones = SideIndexHotZone.zones(w, h, density, widthDp, allowLeft = true, allowRight = true)
        assertEquals(2, zones.size)
        val edge = widthDp * density
        for ((i, z) in zones.withIndex()) {
            val want = if (i == 0) SideIndexHotZone.Verdict.LEFT else SideIndexHotZone.Verdict.RIGHT
            // 内部（离边 1px）→ 起手
            for (x in listOf(z.left + 1f, z.centerX, z.right - 1f)) for (y in listOf(z.top + 1f, z.centerY, z.bottom - 1f)) {
                assertEquals(want, SideIndexHotZone.classify(x, y, w, h, edge, true, true, top, bottom), "zone $i @$x,$y")
            }
            // 上下外侧 → 保留区
            assertEquals(SideIndexHotZone.Verdict.TOP_GUARD,
                SideIndexHotZone.classify(z.centerX, z.top - 1f, w, h, edge, true, true, top, bottom))
            assertEquals(SideIndexHotZone.Verdict.BOTTOM_GUARD,
                SideIndexHotZone.classify(z.centerX, z.bottom + 1f, w, h, edge, true, true, top, bottom))
        }
        // 两带之间 → 不在边缘带
        assertEquals(SideIndexHotZone.Verdict.OUTSIDE,
            SideIndexHotZone.classify(w / 2f, h / 2f, w, h, edge, true, true, top, bottom))
        // 是整条边缘，不是把手那一段：上下只扣保留区
        assertEquals(top, zones[0].top)
        assertEquals(h - bottom, zones[0].bottom)
    }

    @Test
    fun disabledSideIsNotDrawn() {
        val left = SideIndexHotZone.zones(w, h, density, 20f, allowLeft = true, allowRight = false)
        assertEquals(1, left.size)
        assertEquals(0f, left[0].left)
        val right = SideIndexHotZone.zones(w, h, density, 20f, allowLeft = false, allowRight = true)
        assertEquals(w, right.single().right)
    }

    @Test
    fun degenerateInputsDrawNothing() {
        assertTrue(SideIndexHotZone.zones(w, h, density, 0f, true, true).isEmpty())
        assertTrue(SideIndexHotZone.zones(w, h, density, Float.NaN, true, true).isEmpty())
        // 屏高不够扣两个保留区（120 + 56 = 176dp）
        assertTrue(SideIndexHotZone.zones(w, 170f * density, density, 20f, true, true).isEmpty())
    }
}
