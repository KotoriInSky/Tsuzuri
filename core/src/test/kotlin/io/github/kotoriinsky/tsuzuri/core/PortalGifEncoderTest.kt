package io.github.kotoriinsky.tsuzuri.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class PortalGifEncoderTest {
    private fun decode(bytes: ByteArray): List<java.awt.image.BufferedImage> {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        return (0 until reader.getNumImages(true)).map { reader.read(it) }
    }

    @Test fun framesRoundTripWithExactColorsAndTransparency() {
        val w = 4; val h = 3
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt(); val clear = 0
        val a = IntArray(w * h) { if (it % 2 == 0) red else blue }
        val b = IntArray(w * h) { if (it < 6) clear else blue }
        val out = ByteArrayOutputStream()
        PortalGifEncoder.encode(w, h, listOf(a, b), listOf(100, 40), out)
        val bytes = out.toByteArray()
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        val frames = decode(bytes)
        assertEquals(2, frames.size)
        assertEquals(red, frames[0].getRGB(0, 0)); assertEquals(blue, frames[0].getRGB(1, 0))
        assertEquals(0, frames[1].getRGB(0, 0) ushr 24) // 透明像素。
        assertEquals(blue, frames[1].getRGB(3, 2))
    }

    @Test fun manyColorsAreReducedToAtMost256AndLzwSurvivesTableReset() {
        val w = 128; val h = 128
        val pixels = IntArray(w * h) { 0xFF000000.toInt() or (it * 2654435761L).toInt().and(0xFFFFFF) }
        val out = ByteArrayOutputStream()
        PortalGifEncoder.encode(w, h, listOf(pixels), listOf(50), out)
        val frame = decode(out.toByteArray()).single()
        assertEquals(w, frame.width); assertEquals(h, frame.height)
        val (palette, _, transparent) = PortalGifEncoder.quantize(pixels)
        assertTrue(palette.size <= 256); assertEquals(-1, transparent)
        // 中值切分后的近似色仍应接近原色。
        val src = pixels[777]; val got = frame.getRGB(777 % w, 777 / w)
        val diff = listOf(16, 8, 0).sumOf { kotlin.math.abs((src shr it and 255) - (got shr it and 255)) }
        assertTrue(diff < 200, "颜色偏差过大：$diff")
    }

    @Test fun rejectsMismatchedInput() {
        assertFailsWith<IllegalArgumentException> {
            PortalGifEncoder.encode(2, 2, listOf(IntArray(4)), listOf(10, 10), ByteArrayOutputStream())
        }
        assertFailsWith<IllegalArgumentException> {
            PortalGifEncoder.encode(2, 2, listOf(IntArray(3)), listOf(10), ByteArrayOutputStream())
        }
    }
}
