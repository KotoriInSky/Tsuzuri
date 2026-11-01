package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.CandidateImageCheck.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidateImageCheckTest {
    private val s = 32

    private fun argb(a: Int, g: Int) = (a shl 24) or (g shl 16) or (g shl 8) or g

    /** 左右两半黑白 + 上下渐变：有内容、哈希稳定的「正常图」。 */
    private fun pattern(w: Int = s, h: Int = s, flip: Boolean = false) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val left = if (flip) x >= w / 2 else x < w / 2
        argb(255, if (left) 20 + y * 2 else 230 - y * 2)
    }

    private fun solid(g: Int, a: Int = 255) = IntArray(s * s) { argb(a, g) }

    private fun check(
        c: IntArray?, cw: Int = 600, ch: Int = 600,
        r: IntArray? = pattern(), rw: Int = 600, rh: Int = 600,
    ) = CandidateImageCheck.check(c, s, s, cw, ch, r, s, s, rw, rh)

    @Test fun matchingCandidatePasses() {
        val v = check(pattern())
        assertTrue(v.ok, v.codes())
        assertEquals(0, v.hamming)
    }

    @Test fun emptyOrBadInputIsEmpty() {
        assertEquals(listOf(Reason.EMPTY), check(null).reasons)
        assertEquals(listOf(Reason.EMPTY), check(IntArray(10)).reasons)
        assertEquals(listOf(Reason.EMPTY), check(pattern(), cw = 0).reasons)
    }

    @Test fun tinyCandidateIsTooSmall() {
        assertTrue(Reason.TOO_SMALL in check(pattern(), cw = 20, ch = 20, rw = 20, rh = 20).reasons)
    }

    @Test fun transparentCandidateRejected() {
        val v = check(solid(0, a = 0))
        assertTrue(Reason.MOSTLY_TRANSPARENT in v.reasons, v.codes())
        assertFalse(Reason.SOLID_COLOR in v.reasons)
    }

    @Test fun stickerWithTransparentBorderStillPasses() {
        // 四周一圈透明（约 23%），中间是正常内容
        val c = pattern().mapIndexed { i, p -> val x = i % s; val y = i / s
            if (x < 2 || y < 2 || x >= s - 2 || y >= s - 2) 0 else p }.toIntArray()
        assertFalse(Reason.MOSTLY_TRANSPARENT in check(c).reasons)
    }

    @Test fun solidPlaceholderRejected() {
        assertTrue(Reason.SOLID_COLOR in check(solid(238)).reasons)
    }

    @Test fun nearSolidWithSpeckleRejected() {
        val c = solid(200).also { it[0] = argb(255, 0); it[5] = argb(255, 255) }
        assertTrue(Reason.SOLID_COLOR in check(c).reasons)
    }

    @Test fun aspectMismatchRejected() {
        val v = check(pattern(), cw = 1080, ch = 1080, rw = 1080, rh = 300)
        assertTrue(Reason.ASPECT_MISMATCH in v.reasons, v.codes())
    }

    @Test fun moderateAspectDifferenceTolerated() {
        // 4:3 对 1:1（view_rect 被下缘裁掉一截的典型情形）
        assertTrue(check(pattern(), cw = 800, ch = 600, rw = 600, rh = 600).ok)
    }

    @Test fun differentContentRejected() {
        val v = check(pattern(flip = true))
        assertTrue(Reason.NOT_SIMILAR in v.reasons, v.codes())
        assertTrue(v.hamming > CandidateImageCheck.MAX_HAMMING)
    }

    @Test fun slightlyNoisyContentStillSimilar() {
        val c = pattern().mapIndexed { i, p -> if (i % 7 == 0) argb(255, ((p and 0xFF) + 10).coerceAtMost(255)) else p }
            .toIntArray()
        assertTrue(check(c).ok)
    }

    @Test fun flatRegionSkipsSimilarity() {
        val v = check(pattern(flip = true), r = solid(128))
        assertEquals(-1, v.hamming)
        assertTrue(v.ok)
    }

    @Test fun noRegionOnlyChecksCandidateItself() {
        val v = CandidateImageCheck.check(pattern(), s, s, 600, 600)
        assertTrue(v.ok)
        assertTrue(Reason.SOLID_COLOR in CandidateImageCheck.check(solid(10), s, s, 600, 600).reasons)
    }

    @Test fun smallSampleHashHasNoEmptyBlockArtifacts() {
        val a = pattern(4, 4)
        assertEquals(CandidateImageCheck.averageHash(a, 4, 4), CandidateImageCheck.averageHash(a.copyOf(), 4, 4))
    }
}
