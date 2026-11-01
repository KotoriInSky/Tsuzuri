package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 圆角分档规则（[WidgetCornerTier.dimenNameFor]）。
 *
 * 这些断言为什么能写死：分档是**纯规则**，而且它有一个可对照的解析解 ——
 * PA 自己的 `w5.a.a(spanX, spanY)`。所以最有价值的断言不是「我觉得 1×2 该走默认档」，
 * 而是**逐格**与那段字节码的逐字转写比对（[paTier]）：「复刻它的语义」这件事只有
 * 对着原实现逐格跑过才算钉住。
 */
class WidgetCornerTierTest {

    /**
     * `research/decompiled/personalassistant/sources/w5/a.java:40-45` 的**逐字转写**。
     *
     * 原样的 `f31891d` / `f31890c` / `f31892e` 分别是 1×1 / 默认 / 单行三档的静态字段
     * （`:50-52` 从 `R.dimen.pa_cell_content_radius*` 赋值）。这里故意保留原判据的**形状**
     * （包括那个容易读错的 `spanY != 1 || spanX < 2`），不「顺手美化」成看起来更对称的写法 ——
     * 一旦美化了，这个对照就失去意义了。
     */
    private fun paTier(spanX: Int, spanY: Int): String =
        if (spanX == 1 && spanY == 1) {
            WidgetCornerTier.DIMEN_1X1
        } else {
            if (spanY != 1 || spanX < 2) WidgetCornerTier.DIMEN_DEFAULT else WidgetCornerTier.DIMEN_SINGLE_ROW
        }

    @Test
    fun matchesTheDecompiledRuleCellByCell() {
        // 0 也一起扫：ItemInfo 的 span 字段在 `setup()` 之前可能是 0，
        // 「未初始化的 span」必须落到默认档，而不是被当成某个特殊档
        for (spanX in 0..6) {
            for (spanY in 0..6) {
                assertEquals(
                    paTier(spanX, spanY),
                    WidgetCornerTier.dimenNameFor(spanX, spanY),
                    "span=${spanX}x$spanY 与 w5.a.a 的判据不一致",
                )
            }
        }
    }

    @Test
    fun oneByOneGetsItsOwnTier() {
        assertEquals(WidgetCornerTier.DIMEN_1X1, WidgetCornerTier.dimenNameFor(1, 1))
        // 1×1 是**唯一**独占一档的 span：它与 1×2、2×1 必须分得开，
        // 否则 1×1 又会拿到大半径（这正是本次要修的回归）
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(1, 2))
        assertEquals(WidgetCornerTier.DIMEN_SINGLE_ROW, WidgetCornerTier.dimenNameFor(2, 1))
    }

    @Test
    fun singleRowMeansHorizontalWideOnly() {
        // 名字里的 single_row 指「横向铺开的一行」：spanY == 1 且至少两格宽
        for (spanX in 2..6) assertEquals(WidgetCornerTier.DIMEN_SINGLE_ROW, WidgetCornerTier.dimenNameFor(spanX, 1))

        // ⚠️ **回归防线**：单列（1×n）走的是**默认档**，不是单行档。
        // 按「单行/单列都算窄」这种直觉去写，1×2 的卡片会拿到 20dp 而不是 24dp ——
        // 数值差得不多，但那是「我们自己发明的分档」，不是 PA 的分档
        for (spanY in 2..6) assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(1, spanY))

        // 多行多列一律默认档
        for (spanX in 2..6) {
            for (spanY in 2..6) {
                assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(spanX, spanY), "$spanX×$spanY")
            }
        }
    }

    @Test
    fun missingSpanFallsBackToTheDefaultTierNeverToZero() {
        // 拿不到 span 时的方向必须是「默认档」= **改动前那个半径**：
        // 退回默认档最坏只是「1×1 的圆角偏大」（本次要修的旧行为），
        // 而退回 0 会让圆角直接消失 —— 那是把「不够好」变成「坏了」
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(null, null))
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(1, null))
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(null, 1))
        // 单边为 null 时**不许**用另一半猜档：宁可默认档，也不要猜出一个更小的半径
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(3, null))

        // 未初始化 / 非法 span 同样回默认档（「绝不静默取 0」）
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(0, 0))
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(0, 1))
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(1, 0))
        assertEquals(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.dimenNameFor(-1, -1))
    }

    @Test
    fun dimenNamesAreTheOnesPaActuallyReads() {
        // 这三个字符串**不是我们可以随便起的名字** —— 它们要被 `Resources.getIdentifier(name,
        // "dimen", "com.miui.personalassistant")` 拿去查 PA 的资源表（`w5/a.java:50-52`）。
        // 写错一个字母不会报错，只会静默解析不到 → 退回兜底 24dp → 看起来「分档没生效」
        assertEquals("pa_cell_content_radius", WidgetCornerTier.DIMEN_DEFAULT)
        assertEquals("pa_cell_content_radius_1x1", WidgetCornerTier.DIMEN_1X1)
        assertEquals("pa_cell_content_radius_single_row", WidgetCornerTier.DIMEN_SINGLE_ROW)
        // 三档必须是三个**不同**的名字（复制粘贴写重复了，三档会退化成同一档，
        // 而数值上又看不出来 —— 那正是这个 bug 最初的样子）
        assertEquals(3, setOf(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.DIMEN_1X1, WidgetCornerTier.DIMEN_SINGLE_ROW).size)
    }

    @Test
    fun everySpanResolvesToAKnownTier() {
        // 无论传什么，返回的必须是那三个名字之一（不是 null、不是空串、不是拼出来的字符串）：
        // 调用方会拿它直接去查资源 id，返回一个查不到的字符串就等于静默失效
        val known = setOf(WidgetCornerTier.DIMEN_DEFAULT, WidgetCornerTier.DIMEN_1X1, WidgetCornerTier.DIMEN_SINGLE_ROW)
        for (spanX in -2..6) {
            for (spanY in -2..6) {
                val name = WidgetCornerTier.dimenNameFor(spanX, spanY)
                assertTrue(name in known, "$spanX×$spanY 返回了不在档位表里的名字：$name")
            }
        }
    }
}
