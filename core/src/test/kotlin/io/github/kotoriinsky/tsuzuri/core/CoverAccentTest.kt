package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverAccentTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun parse(hex: String): Triple<Double, Double, Double> {
        val v = hex.removePrefix("#").toInt(16)
        return Triple(((v shr 16) and 0xFF).toDouble(), ((v shr 8) and 0xFF).toDouble(), (v and 0xFF).toDouble())
    }

    @Test
    fun `白底红标取红色`() {
        val px = IntArray(400) { if (it < 100) rgb(220, 30, 40) else rgb(255, 255, 255) }
        val c = assertNotNull(CoverAccent.pick(px))
        val (r, g, b) = parse(c)
        assertTrue(r > g * 3 && r > b * 3, c)
    }

    @Test
    fun `多数色相胜出`() {
        val px = IntArray(300) { if (it < 200) rgb(30, 90, 220) else rgb(240, 120, 20) }
        val (r, _, b) = parse(assertNotNull(CoverAccent.pick(px)))
        assertTrue(b > r)
    }

    @Test
    fun `灰度图与全透明返回 null`() {
        assertNull(CoverAccent.pick(IntArray(100) { rgb(it, it, it) }))
        assertNull(CoverAccent.pick(IntArray(100) { 0 }))
        assertNull(CoverAccent.pick(IntArray(0)))
    }

    @Test
    fun `亮色被压暗到白字可读`() {
        // 明黄：与白字对比度只有 1.1 左右
        val c = assertNotNull(CoverAccent.pick(IntArray(50) { rgb(255, 230, 0) }))
        val (r, g, b) = parse(c)
        assertTrue(CoverAccent.contrastWithWhite(r, g, b) >= CoverAccent.MIN_CONTRAST - 0.05, c)
    }

    @Test
    fun `深色不再变`() {
        assertEquals("#1E3C8C", CoverAccent.pick(IntArray(10) { rgb(30, 60, 140) }))
    }
}
