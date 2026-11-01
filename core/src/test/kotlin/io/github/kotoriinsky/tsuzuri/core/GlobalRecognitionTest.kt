package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.GlobalRecognition.Box
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalRecognitionTest {
    private val w = 240
    private val h = 400
    private val cell = 8

    private fun canvas(bg: Int = 0xFFF5F5F5.toInt()) = IntArray(w * h) { bg }

    private fun fill(px: IntArray, b: Box, color: (Int, Int) -> Int) {
        for (y in b.top until b.bottom) for (x in b.left until b.right) px[y * w + x] = color(x, y)
    }

    private val rnd = Random(7)
    private fun noise(@Suppress("UNUSED_PARAMETER") x: Int, @Suppress("UNUSED_PARAMETER") y: Int) =
        (0xFF shl 24) or (rnd.nextInt(256) shl 16) or (rnd.nextInt(256) shl 8) or rnd.nextInt(256)

    private fun find(px: IntArray, text: List<Box> = emptyList(), minSide: Int = 32) =
        GlobalRecognition.findImageRegions(px, w, h, cell, text, minSide)

    @Test
    fun 照片块按格子对齐检出() {
        val px = canvas()
        fill(px, Box(40, 80, 200, 200), ::noise)
        val r = find(px)
        assertEquals(listOf(Box(40, 80, 200, 200)), r)
    }

    @Test
    fun 纯色按钮不算图片() {
        val px = canvas()
        fill(px, Box(40, 80, 200, 200)) { _, _ -> 0xFF3482FF.toInt() }
        assertTrue(find(px).isEmpty())
    }

    @Test
    fun 成段文字不算图片() {
        val px = canvas()
        val lines = (0 until 6).map { Box(16, 40 + it * 24, 224, 56 + it * 24) }
        // 字形：黑白交替的竖条，单看像素和照片一样「有细节」
        lines.forEach { b -> fill(px, b) { x, _ -> if (x % 3 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() } }
        assertTrue(find(px, text = lines).isEmpty())
    }

    @Test
    fun 小图标被最小边长过滤() {
        val px = canvas()
        fill(px, Box(16, 16, 40, 40), ::noise)
        assertTrue(find(px).isEmpty())
    }

    @Test
    fun 最小边长放低后小图标能点中() {
        val px = canvas()
        fill(px, Box(16, 16, 40, 40), ::noise)
        assertEquals(listOf(Box(16, 16, 40, 40)), find(px, minSide = 16))
    }

    @Test
    fun 图上压字不拆成两块_说明文字不并入() {
        val px = canvas()
        fill(px, Box(40, 80, 200, 200), ::noise)
        val onImage = Box(64, 128, 120, 144)
        val caption = Box(40, 208, 200, 224)
        fill(px, caption) { x, _ -> if (x % 3 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val r = find(px, text = listOf(onImage, caption))
        assertEquals(listOf(Box(40, 80, 200, 200)), r)
    }

    @Test
    fun 多张图按面积从大到小() {
        val px = canvas()
        fill(px, Box(16, 16, 80, 80), ::noise)
        fill(px, Box(16, 160, 224, 320), ::noise)
        val r = find(px)
        assertEquals(listOf(Box(16, 160, 224, 320), Box(16, 16, 80, 80)), r)
    }
}
