package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class PortalMotionPhotoTest {
    @Test fun readsEmbeddedPairFromOneCompleteMotionPhotoFile() {
        val prefix = PortalMotionPhoto.jpegPrefix(byteArrayOf(-1, -40, -1, -39), 240, 0)
        assertEquals(240L, PortalMotionPhoto.embeddedVideoLength(prefix, prefix.size + 240L))
    }
    @Test fun refusesOffsetsOutsideFileAndMetadataOutsideApp1() {
        val prefix = PortalMotionPhoto.jpegPrefix(byteArrayOf(-1, -40, -1, -39), 240, 0)
        assertNull(PortalMotionPhoto.embeddedVideoLength(prefix, 200))
        assertNull(PortalMotionPhoto.embeddedVideoLength(byteArrayOf(-1, -40, -1, -39) +
            "GCamera:MicroVideoOffset=\"24\"".toByteArray(), 200))
    }
    @Test fun keepsJpegAndDescribesVideoOffsetFromEndOfFile() {
        val jpeg = byteArrayOf(-1, -40, -1, -39)
        val prefix = PortalMotionPhoto.jpegPrefix(jpeg, 123456, 800000)
        assertEquals(-1, prefix[0].toInt())
        assertEquals(-40, prefix[1].toInt())
        assertEquals(-31, prefix[3].toInt())
        val payload = prefix.toString(Charsets.UTF_8)
        assertTrue("GCamera:MotionPhoto=\"1\"" in payload)
        assertTrue("GCamera:MicroVideoOffset=\"123456\"" in payload)
        assertTrue("Item:Length=\"123456\"" in payload)
        assertTrue("800000" in payload)
        assertTrue(prefix.takeLast(2).toByteArray().contentEquals(jpeg.takeLast(2).toByteArray()))
        val appLength = (prefix[4].toInt() and 255) * 256 + (prefix[5].toInt() and 255)
        assertEquals(prefix.size - jpeg.size - 2, appLength)
    }
    @Test fun refusesMissingVideoAndNonJpegInput() {
        assertFailsWith<IllegalArgumentException> { PortalMotionPhoto.jpegPrefix(byteArrayOf(), 3, 0) }
        assertFailsWith<IllegalArgumentException> { PortalMotionPhoto.jpegPrefix(byteArrayOf(-1,-40,-1,-39), 0, 0) }
    }
}
