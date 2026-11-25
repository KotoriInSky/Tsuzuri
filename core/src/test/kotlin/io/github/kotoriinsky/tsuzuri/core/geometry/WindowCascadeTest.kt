package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowCascadeTest {

    private val screenW = 1220f
    private val screenH = 2656f
    private val step = 72f // ~22dp @3.25x
    private val flyme = WindowBounds.defaultsFor(screenW, screenH)

    @Test
    fun `no occupied windows returns desired unchanged`() {
        assertEquals(flyme, WindowCascade.avoidFlushOverlap(flyme, emptyList(), screenW, screenH, step))
    }

    @Test
    fun `occupied window already moved away returns desired unchanged`() {
        val moved = ScreenRect(320f, 900f, 320f + flyme.width, 900f + flyme.height)
        assertEquals(flyme, WindowCascade.avoidFlushOverlap(flyme, listOf(moved), screenW, screenH, step))
    }

    @Test
    fun `flush existing window cascades down-right while keeping size and staying on screen`() {
        val out = WindowCascade.avoidFlushOverlap(flyme, listOf(flyme), screenW, screenH, step)

        assertEquals(flyme.width, out.width, 0.01f)
        assertEquals(flyme.height, out.height, 0.01f)
        assertEquals(flyme.left + step, out.left, 0.01f)
        assertEquals(flyme.top + step, out.top, 0.01f)
        assertTrue(out.left >= 0f && out.right <= screenW)
        assertTrue(out.top >= 0f && out.bottom <= screenH)
    }

    @Test
    fun `third window avoids both first and second cascaded windows`() {
        val second = WindowCascade.avoidFlushOverlap(flyme, listOf(flyme), screenW, screenH, step)
        val third = WindowCascade.avoidFlushOverlap(flyme, listOf(flyme, second), screenW, screenH, step)

        val minSep = step * 0.6f
        assertTrue(abs(third.left - flyme.left) >= minSep || abs(third.top - flyme.top) >= minSep)
        assertTrue(abs(third.left - second.left) >= minSep || abs(third.top - second.top) >= minSep)
        assertTrue(third.left >= 0f && third.right <= screenW)
        assertTrue(third.top >= 0f && third.bottom <= screenH)
    }

    @Test
    fun `window flush at bottom-right corner cascades inward without clipping off screen`() {
        val corner = ScreenRect(screenW - flyme.width, screenH - flyme.height, screenW, screenH)
        val out = WindowCascade.avoidFlushOverlap(corner, listOf(corner), screenW, screenH, step)

        assertTrue(out.left >= 0f && out.right <= screenW)
        assertTrue(out.top >= 0f && out.bottom <= screenH)
        assertTrue(abs(out.left - corner.left) >= step * 0.6f || abs(out.top - corner.top) >= step * 0.6f)
    }

    @Test
    fun `full-width window still shifts vertically to avoid flush overlap`() {
        val wide = ScreenRect(0f, 400f, screenW, 400f + 1500f)
        val out = WindowCascade.avoidFlushOverlap(wide, listOf(wide), screenW, screenH, step)

        assertEquals(0f, out.left, 0.01f)
        assertEquals(wide.top + step, out.top, 0.01f)
        assertTrue(out.bottom <= screenH)
    }
}
