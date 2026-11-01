package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 清单项 → 扇子上的页 / 圈（[FanSlots]）。 */
class FanSlotsTest {

    @Test
    fun smallListIsOnePageOneRingWithNoLabels() {
        val s = FanSlots.of(total = 6, perPage = 17, layoutMode = "smart", inner = 7, middle = 8)
        assertEquals(6, s.size)
        assertTrue(s.all { it.page == 0 && it.ring == 0 && it.rings == 1 && !it.startsSection })
    }

    @Test
    fun smartModeSplitsRingsLikeFanArcsAndLabelsEachRingStart() {
        // 智能模式：10 项 → (10+7)/8 = 2 圈，5 + 5
        val s = FanSlots.of(total = 10, perPage = 17, layoutMode = "smart", inner = 7, middle = 8)
        val rows = FanArcs.plan(10, "smart", 7, 8).rows.map { it.count }
        assertEquals(rows, s.groupBy { it.ring }.values.map { it.size })
        assertEquals(listOf(0, rows[0]), s.indices.filter { s[it].startsSection })
    }

    @Test
    fun pagingFollowsPerPageAndEachPageIsPlannedOnItsOwn() {
        val s = FanSlots.of(total = 20, perPage = 17, layoutMode = "smart", inner = 7, middle = 8)
        assertEquals(17, s.count { it.page == 0 })
        assertEquals(3, s.count { it.page == 1 })
        assertTrue(s[17].startsSection && s[17].page == 1 && s[17].ring == 0)
        assertTrue(s.all { it.pages == 2 })
        // 第 2 页只有 3 项 = 一圈
        assertTrue(s.filter { it.page == 1 }.all { it.rings == 1 })
    }

    @Test
    fun customModeFillsInnerFirst() {
        val s = FanSlots.of(total = 10, perPage = 24, layoutMode = FanArcs.LAYOUT_CUSTOM, inner = 4, middle = 6)
        assertEquals(4, s.count { it.ring == 0 })
        assertEquals(6, s.count { it.ring == 1 })
        assertTrue(FanSlots.of(0, 17, "smart", 7, 8).isEmpty())
    }
}
