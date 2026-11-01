package io.github.kotoriinsky.tsuzuri.core

/**
 * 负一屏小部件圆角的**分档规则**（纯函数，无 Android 依赖，所以能被单测钉住）。
 *
 * ## 为什么必须分档，而不是取一个统一值
 *
 * PA 自己就是分档的：`w5.a.a(spanX, spanY)` 按 span 从三个 dimen 里挑一个
 * （`research/decompiled/personalassistant/sources/w5/a.java:40-45`，取值在 `:50-52`）：
 *
 * ```java
 * public static float a(int i10, int i11) {          // i10 = spanX, i11 = spanY
 *     if (i10 == 1 && i11 == 1) return f31891d;      // pa_cell_content_radius_1x1
 *     return (i11 != 1 || i10 < 2) ? f31890c : f31892e;  // 默认档 / 单行档
 * }
 * ```
 *
 * 所以 `unify_corner` 的语义只能是「统一到**同一套分档**」，不能是「统一成**一个数值**」：
 * 1×1 的格子边长很小，硬塞一个 24dp 会被 `getOutline` 里的 `min(w,h)/2` 夹掉，
 * 于是 1×1 小部件的圆角反而变大、接近圆形 —— 把「统一」做成了「统一变圆」。
 * Flyme 的参照也是分档的（14dp / 20dp 两档，
 * `FlymeDeviceProfile#calculateWidgetEnforcedRadius`），不存在「一个数值」这种做法。
 *
 * ## 三档的语义（注意「单行」只指**横向**单行）
 *
 * | span | 档 | 为什么 |
 * | --- | --- | --- |
 * | `1×1` | [DIMEN_1X1] | 最小的格子，圆角必须跟着变小（PA 实测 17.6dp） |
 * | `spanY == 1 且 spanX >= 2` | [DIMEN_SINGLE_ROW] | 横向铺开的单行卡片（实测 20dp） |
 * | 其余（含**单列** `1×n`、`n×1` 以外的多行） | [DIMEN_DEFAULT] | 实测 24dp |
 *
 * ⚠️ **单列 `1×2` 走的是默认档，不是单行档** —— 原字节码的判据是 `spanY != 1`，
 * 名字里的「single_row」指横向那一行，竖着的窄卡片不在其中。按名字想当然会写错这一档。
 */
object WidgetCornerTier {

    /** 默认档：非 1×1、非横向单行（PA 实测 24dp）。 */
    const val DIMEN_DEFAULT = "pa_cell_content_radius"

    /** 1×1 档（PA 实测 17.6dp）。 */
    const val DIMEN_1X1 = "pa_cell_content_radius_1x1"

    /** 横向单行档：`spanY == 1 且 spanX >= 2`（PA 实测 20dp）。 */
    const val DIMEN_SINGLE_ROW = "pa_cell_content_radius_single_row"

    /**
     * span → dimen 资源名。
     *
     * `spanX`/`spanY` 传 `null` 表示**拿不到 span**（tag 不是 ItemInfo、字段被 OTA 改名等）：
     * 退回 [DIMEN_DEFAULT]。方向与 PA 自己一致 —— `p5.d#getClipRoundCornerRadius()` 在
     * `itemInfo == null` 时返回的也是默认档（`w5.a.f31890c`），而不是 0。
     * **绝不返回「无圆角」**：这个特性的目的是圆角统一，不是把圆角抹平。
     *
     * span 为 0/负数（未初始化的 ItemInfo）同样落到默认档 —— 不会「静默取 0」。
     */
    fun dimenNameFor(spanX: Int?, spanY: Int?): String {
        if (spanX == null || spanY == null) return DIMEN_DEFAULT
        if (spanX == 1 && spanY == 1) return DIMEN_1X1
        return if (spanY == 1 && spanX >= 2) DIMEN_SINGLE_ROW else DIMEN_DEFAULT
    }
}
