package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 触发热区的求值基准（[FanHotZone]）—— 预览与真机共用的唯一一份公式。
 *
 * 钉三件事：
 *  1. **竖屏不变**：换成「竖屏屏幕」基准对老用户必须是一个像素都不动的平移；
 *  2. **横竖屏同一块**：这是这次改动的全部意义（横屏下热区曾是 147×21dp 的横条）；
 *  3. **退化**：非有限 / 0 屏幕退到 1px，不产 NaN。
 */
class FanHotZoneTest {

    /** 本机（Xiaomi 17 Pro）：1220 × 2656，密度 3.25。 */
    private val w = 1220f
    private val h = 2656f
    private val density = 3.25f

    /** 默认参数（Catalog：宽 18%、高 5.5%）。 */
    private val widthPercent = 18f
    private val heightPercent = 5.5f

    @Test
    fun portraitValuesAreUnchangedFromTheOldAxisBoundRule() {
        // 旧公式（× 当下屏宽/屏高）在竖屏下的值 —— 平移前后必须逐字节相同
        val oldW = maxOf(1f, w * widthPercent / 100f)
        val oldH = maxOf(1f, h * heightPercent / 100f)
        assertEquals(oldW, FanHotZone.widthPx(w, h, widthPercent), "竖屏角宽")
        assertEquals(oldH, FanHotZone.heightPx(w, h, heightPercent), "竖屏角高")

        // 顺带钉住量级（2026-09-15 定 18% 宽度的依据：盖住 x≥1000 的自然起手点）
        assertEquals(220f, FanHotZone.widthPx(w, h, widthPercent), 1f)
        assertTrue(
            FanHotZone.heightPx(w, h, heightPercent) / density in 44f..46f,
            "角高应 ≈45dp（Flyme 的方块是 44dp）",
        )
    }

    @Test
    fun landscapeUsesTheSameZoneAsPortrait() {
        // ★ 本次改动的全部意义：转屏后轴互换，热区一个像素都不变。
        // 旧公式在横屏是 479×67px（147×21dp）—— 横向大 2.2 倍、纵向只剩 21dp。
        assertEquals(
            FanHotZone.widthPx(w, h, widthPercent),
            FanHotZone.widthPx(h, w, widthPercent),
            "横屏角宽必须与竖屏相同",
        )
        assertEquals(
            FanHotZone.heightPx(w, h, heightPercent),
            FanHotZone.heightPx(h, w, heightPercent),
            "横屏角高必须与竖屏相同",
        )
        // 方向断言：这是一块**横向更宽**的方块（68×45dp），横屏下也一样 ——
        // 要钉的是「与竖屏相同」（上面两条相等断言），不是「宽小于高」（那是小窗的断言）
        val lw = FanHotZone.widthPx(h, w, widthPercent)
        val lh = FanHotZone.heightPx(h, w, heightPercent)
        assertTrue(lh / density > 30f, "横屏纵向容差必须够用（>30dp）：${lh / density}dp")
        assertTrue(lw / density < 100f, "横屏横向不应暴涨到 147dp：${lw / density}dp")
    }

    @Test
    fun degenerateInputsClampToOnePixel() {
        assertEquals(1f, FanHotZone.widthPx(0f, 0f, widthPercent))
        assertEquals(1f, FanHotZone.heightPx(0f, 0f, heightPercent))
        // 单边为 0：基准是「短边/长边」—— 短边取 0 时宽退化成 1px；
        // 长边仍取得到（另一边非 0），所以高是**正常求值**，不是 1px
        assertEquals(1f, FanHotZone.widthPx(0f, h, widthPercent), "短边=0 → 宽退化")
        assertEquals(
            w * heightPercent / 100f,
            FanHotZone.heightPx(w, 0f, heightPercent),
            0.01f,
            "高 ← 长边，另一边还在 → 正常求值",
        )
        assertEquals(1f, FanHotZone.widthPx(Float.NaN, h, widthPercent), "NaN 不得沿比较链传播")
        assertEquals(1f, FanHotZone.heightPx(w, Float.POSITIVE_INFINITY, heightPercent))
        assertEquals(1f, FanHotZone.widthPx(w, h, Float.NaN))
        assertEquals(1f, FanHotZone.widthPx(w, h, 0f))
        // 极大百分比也不会炸（上游 Catalog 有 min/max 夹取，这里是最后一道）
        assertTrue(FanHotZone.widthPx(w, h, 10_000f).isFinite())
    }

    @Test
    fun cornerRectsSitOnTheBottomCornersWithTheSharedSize() {
        // 与 hook `FanGeometry.cornerAt` 同一判据：y >= H - hotH，x <= hotW / x >= W - hotW
        val (l, r) = FanHotZone.cornerRects(w, h, widthPercent, heightPercent)
        val hw = FanHotZone.widthPx(w, h, widthPercent)
        val hh = FanHotZone.heightPx(w, h, heightPercent)
        assertEquals(0f, l.left); assertEquals(hw, l.right); assertEquals(h - hh, l.top); assertEquals(h, l.bottom)
        assertEquals(w - hw, r.left); assertEquals(w, r.right); assertEquals(h - hh, r.top); assertEquals(h, r.bottom)
        assertEquals(1, FanHotZone.zones(w, h, widthPercent, heightPercent, allowLeft = false, allowRight = true).size)
        assertEquals(w, FanHotZone.zones(w, h, widthPercent, heightPercent, false, true).single().right)
        assertTrue(FanHotZone.zones(w, h, widthPercent, heightPercent, false, false).isEmpty())
    }
}
