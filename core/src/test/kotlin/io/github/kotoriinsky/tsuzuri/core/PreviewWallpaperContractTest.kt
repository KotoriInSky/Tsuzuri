package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 预览底图的缩放尺寸（[PreviewWallpaperContract.targetSize]）。 */
class PreviewWallpaperContractTest {

    @Test
    fun squareDeviceWallpaperShrinksToTheShortSideTarget() {
        // 本机实测壁纸 2656×2656
        assertEquals(960 to 960, PreviewWallpaperContract.targetSize(2656, 2656))
    }

    @Test
    fun keepsAspectRatioOnTheLongSide() {
        // 1220×2656（屏幕比例）→ 短边 960，长边按比例
        val (w, h) = PreviewWallpaperContract.targetSize(1220, 2656)!!
        assertEquals(960, w)
        assertEquals(Math.round(2656 * 960.0 / 1220).toInt(), h)
        // 横图同理
        assertEquals(960, PreviewWallpaperContract.targetSize(4000, 2000)!!.second)
    }

    @Test
    fun neverUpscales() {
        assertEquals(800 to 1600, PreviewWallpaperContract.targetSize(800, 1600))
        assertEquals(1 to 1, PreviewWallpaperContract.targetSize(1, 1))
    }

    @Test
    fun degenerateSizesYieldNothing() {
        assertNull(PreviewWallpaperContract.targetSize(0, 100))
        assertNull(PreviewWallpaperContract.targetSize(100, -1))
    }

    @Test
    fun extremeAspectKeepsTheShortSideAtTarget() {
        val (w, h) = PreviewWallpaperContract.targetSize(100_000_000, 1_000)!!
        assertEquals(960, h)
        assertEquals(96_000_000, w)
        val (w2, _) = PreviewWallpaperContract.targetSize(1_000, 100_000_000)!!
        assertEquals(960, w2)
    }
}
