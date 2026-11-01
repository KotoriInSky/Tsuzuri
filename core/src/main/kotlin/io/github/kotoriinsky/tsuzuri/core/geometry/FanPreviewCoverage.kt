package io.github.kotoriinsky.tsuzuri.core.geometry

import io.github.kotoriinsky.tsuzuri.core.PrefSpec

/**
 * **扇形预览读了哪些参数**（按参数名后缀）。
 *
 * ## 为什么要有这份清单
 *
 * 实测过一次：设置页里 34 个参数中**只有 5 个**会驱动预览，其余拖了
 * 「预览区 0 像素变化」—— 而用户无法从界面上反驳这个判断，只能得出「参数没生效」。
 * 那份诊断给出的验收标准是「任何参数改动都必须有可见反馈」：要么进预览，
 * 要么在参数行上明确标注「此项不影响预览」。两者都要求**先知道谁进谁不进**，
 * 所以这份清单是那个标注的判据，而且是可测的（见 `FanPreviewCoverageTest`）。
 *
 * ## 为什么按后缀而不是按分组
 *
 * 与 `FanPreviewSection.previewKindOf` 同一条理由：分组标识是 Catalog 里的自由文本，
 * 改名会让判断**静默失效**；参数键是跨进程契约的一部分，不会随手改。
 *
 * ## 判据来源
 *
 * 与之对应的读取点在 `ui/screen/FanPreviewSection.kt` 的 `buildPreviewInput`：
 * 那里的每一个 `number(...)` / `choice(...)` / `toggle(...)` 都对应这里的一项。
 * 测试会把两边对起来（清单里的每一项都能在 [FanPreviewParams] 上找到同名的字段对照），
 * 所以「加了一项读取却没进清单」会以测试失败的形式暴露。
 */
val FAN_PREVIEW_READS: Set<String> = setOf(
    // 触发区 / 屏幕
    "hot_width_percent",
    "hot_height_percent",
    "corners",
    // 排版（清单内容与每屏槽位）
    "items",
    "fan_layout_mode",
    "fan_custom_inner_count",
    "fan_custom_middle_count",
    "fan_max_targets",
    // 几何
    "selection_radius_percent",
    "icon_size_dp",
    "fan_edge_margin",
    "fan_row_stagger",
    "selection_scale",
    "selection_ring",
    // 图标外观
    "icon_shape",
    "icon_alpha",
    "icon_edge",
    "icon_edge_width",
    "icon_shape_corner",
    "icon_shape_exponent",
    // 小窗摆放
    "window_preset",
    "window_width_percent",
    "window_height_percent",
    "window_x_percent",
    "window_y_percent",
    // 动效（动画演示读它们，见 FanAnimationTiming）。可见的是「展开动画」预设，它联动写下面四个
    "fan_motion",
    "animation_speed",
    "fan_rotation",
    "fan_reveal_amount",
)

/**
 * 这一项会不会改变本页预览的画面。
 *
 * 判据是参数名后缀（见 [FAN_PREVIEW_READS]），与特性无关 —— 但**只有
 * `corner_small_window` 用这些后缀**（grep 过 Catalog），所以不会让别的特性凭空长出一个预览。
 * 加新后缀前先确认这一点。
 */
fun PrefSpec.feedsFanPreview(): Boolean =
    key.substringAfterLast('.') in FAN_PREVIEW_READS

/** 参数名后缀 → [FanPreviewParams] 字段名的例外（默认按 snake_case → camelCase 折算）。 */
internal val FAN_PREVIEW_READ_SYNONYMS: Map<String, String> = mapOf(
    // 排版：参数名带 fan_ 前缀，字段名不带
    "fan_layout_mode" to "layoutMode",
    "fan_custom_inner_count" to "customInner",
    "fan_custom_middle_count" to "customMiddle",
    // 几何：参数名说清「是什么的百分之几」，字段名更短
    "selection_radius_percent" to "radiusPercent",
    "icon_size_dp" to "iconDp",
    "fan_edge_margin" to "edgeMarginDp",
    "icon_shape_corner" to "shapeCornerPercent",
    "icon_shape_exponent" to "shapeExponent",
    // 字符串/枚举折成别的类型进参数
    "fan_row_stagger" to "staggerSlots",
    "fan_reveal_amount" to "revealAmountPercent",
    // 百分比在参数对象里带单位后缀
    "icon_alpha" to "iconAlphaPercent",
    "selection_scale" to "selectionScalePercent",
    // 描边：参数名是「开关 + 线宽」，字段是「enabled + dp」
    "icon_edge" to "edgeEnabled",
    "icon_edge_width" to "edgeWidthDp",
    // 清单长度与上限都不单独进参数 —— 它们合成一个 `count`（画几项），
    // 上限的夹取在 UI 侧读 `fan_max_targets` 后做，属于同一个数的来源。
    "items" to "count",
    "fan_max_targets" to "count",
)

/**
 * 这些后缀**不进 [FanPreviewParams]**：它们定的是动画的时长与倾角
 * （[FanAnimationTiming] / [FanAnimation]），画布按帧自己算。
 *
 * 单独列出来是为了让测试知道「找不到字段」在这两项上是**预期**的，
 * 而不是把字段检查整体放宽 —— 放宽之后清单里写错的后缀就再也发现不了。
 */
internal val FAN_PREVIEW_ANIMATION_READS: Set<String> = setOf(
    "animation_speed",
    "fan_rotation",
    // 「展开动画」预设：它联动写上面两项（与 fan_reveal_amount / fan_row_stagger），本身不是画布字段
    "fan_motion",
)

/**
 * 后缀 → 预期的 [FanPreviewParams] 字段名（snake_case → camelCase，例外见上表）。
 *
 * 供测试使用：清单里多写了一个**谁都不读**的后缀时，这里会找不到对应字段 → 测试失败。
 */
internal fun previewParamsFieldOf(suffix: String): String =
    FAN_PREVIEW_READ_SYNONYMS[suffix] ?: suffix.split('_').let { parts ->
        parts.first() + parts.drop(1).joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
    }
