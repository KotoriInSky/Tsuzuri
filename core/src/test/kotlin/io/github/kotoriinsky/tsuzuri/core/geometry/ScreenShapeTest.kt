package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 预览屏幕形状的组装与清洗（[ScreenShape.of]）。 */
class ScreenShapeTest {

    @Test
    fun realCornersAndCutoutsPassThrough() {
        val s = ScreenShape.of(1220f, 2656f, 3.25f, listOf(120f, 120f, 130f, 130f),
            listOf(ScreenRect(560f, 30f, 660f, 130f)))!!
        assertTrue(s.cornersReal)
        assertEquals(listOf(120f, 120f, 130f, 130f), s.corners.toList())
        assertEquals(1, s.cutouts.size)
        assertFalse(s.isLandscape)
    }

    @Test
    fun anyMissingCornerFallsBackToTheEstimateForAllFour() {
        // 一角读不到就整体估：四角混着真值与估值会画出一个歪的屏幕
        val s = ScreenShape.of(1220f, 2656f, 3.25f, listOf(120f, null, 130f, 130f), emptyList())!!
        assertFalse(s.cornersReal)
        val est = 1220f * ScreenShape.FALLBACK_CORNER_RATIO
        assertEquals(List(4) { est }, s.corners.toList())
    }

    @Test
    fun cornersAreClampedToHalfTheShortSide() {
        val s = ScreenShape.of(1000f, 800f, 2f, listOf(5000f, 0f, 0f, 0f), emptyList())!!
        assertEquals(400f, s.corners[0])
        assertTrue(s.isLandscape)
    }

    @Test
    fun cutoutsAreClippedToTheScreenAndEmptyOnesDropped() {
        val s = ScreenShape.of(1000f, 2000f, 2f, List(4) { 0f }, listOf(
            ScreenRect(-50f, -10f, 50f, 60f),
            ScreenRect(300f, 300f, 300f, 400f),
        ))!!
        assertEquals(1, s.cutouts.size)
        assertEquals(0f, s.cutouts[0].left)
        assertEquals(0f, s.cutouts[0].top)
    }

    @Test
    fun foldableInnerScreenIsNearlySquare() {
        // 折叠屏内屏（例：2480×2200）—— 不是手机比例，也必须原样带出
        val s = ScreenShape.of(2480f, 2200f, 3f, List(4) { 60f }, emptyList())!!
        assertEquals(2480f, s.width)
        assertEquals(2200f, s.height)
        assertTrue(s.isLandscape)
    }

    @Test
    fun degenerateSizeYieldsNoShape() {
        assertNull(ScreenShape.of(0f, 100f, 2f, emptyList(), emptyList()))
        assertNull(ScreenShape.of(Float.NaN, 100f, 2f, emptyList(), emptyList()))
    }
}
