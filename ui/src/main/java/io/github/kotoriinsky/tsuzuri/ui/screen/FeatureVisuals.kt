package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.FeatureGroup
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.Maturity
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.icon.FpGlyphs
import io.github.kotoriinsky.tsuzuri.ui.icon.PlateColors
import io.github.kotoriinsky.tsuzuri.ui.kit.FpChip
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIcon

/**
 * 特性 / 分组的**视觉标识**：图标与状态徽标。
 *
 * 为什么单独一份而不是散在页面里：
 *  - 「同一个特性在首页、功能列表、详情页必须是同一个图标」—— 三处各写一份映射必然漂移；
 *  - 徽标判据（作用域没勾 / 实验 / 需重启）是**一条业务规则**，不是三个页面各自的样式，
 *    放在这里才能保证「首页看到需重启、列表里却什么都没标」这种不一致不会出现。
 *
 * 图标是 HyperOS 式色块：图形来自 [FpGlyphs]（抽取的 Material Rounded）与
 * core 里的 `Icons.Rounded.*`，颜色来自 [PlateColors]。**不按 Hook 类名选图标**，
 * 按用户对这件事的心智选。每个首页功能有专属图形与颜色，同一张卡里相邻两行颜色不同。
 */
internal fun groupIcon(group: FeatureGroup): FpIcon = when (group) {
    FeatureGroup.GESTURE -> FpIcon(FpGlyphs.PieChart, PlateColors.Blue)
    FeatureGroup.NEGATIVE_SCREEN -> FpIcon(FpGlyphs.Widgets, PlateColors.Teal)
    FeatureGroup.SCREEN_RECOGNITION -> FpIcon(Icons.Rounded.Search, PlateColors.Purple)
    FeatureGroup.CLIPBOARD -> FpIcon(FpGlyphs.TipsAndUpdates, PlateColors.Purple)
    FeatureGroup.APPS -> FpIcon(FpGlyphs.Payment, PlateColors.Orange)
}

/** 特性的图标。首页列出的每个功能都在这里有一项；未列出的隐藏特性退回分组图标。 */
internal fun featureIcon(spec: FeatureSpec): FpIcon = when (spec.key) {
    // 手势与小窗：蓝 / 橙 / 紫
    Catalog.CORNER_SMALL_WINDOW -> FpIcon(io.github.kotoriinsky.tsuzuri.ui.icon.FpCustomGlyphs.FanWindow, PlateColors.Blue)
    Catalog.SIDE_INDEX -> FpIcon(FpGlyphs.SortByAlpha, PlateColors.Orange)
    Catalog.MULTI_FREEFORM -> FpIcon(FpGlyphs.FilterNone, PlateColors.Purple)
    // 识屏：靛（文字）/ 青（图片）/ 绿（样式）
    Catalog.SCREEN_TEXT -> FpIcon(FpGlyphs.DocumentScanner, PlateColors.Indigo)
    Catalog.SCREEN_IMAGE -> FpIcon(FpGlyphs.Image, PlateColors.Teal)
    Catalog.RECOGNITION_LOOK -> FpIcon(FpGlyphs.ToggleOn, PlateColors.Green)
    Catalog.GLOBAL_RECOGNITION -> FpIcon(FpGlyphs.CropFree, PlateColors.Blue)
    // 主动智能：新应用 / 音乐 / 常亮 / 复制直达，各一图一色
    Catalog.CONTEXT_INSTALL -> FpIcon(FpGlyphs.InstallMobile, PlateColors.Green)
    Catalog.CONTEXT_MUSIC -> FpIcon(FpGlyphs.Headphones, PlateColors.Red)
    Catalog.CONTEXT_SCREEN_ON -> FpIcon(FpGlyphs.LightMode, PlateColors.Orange)
    Catalog.PASSCODE_ISLAND -> FpIcon(FpGlyphs.ContentCopy, PlateColors.Blue)
    // 杂项：支付 / 状态栏 / 选字菜单 / 分享排序
    Catalog.AGG_PAYMENT -> FpIcon(FpGlyphs.Payment, PlateColors.Orange)
    Catalog.CLEAN_STATUSBAR -> FpIcon(FpGlyphs.SignalCellularAlt, PlateColors.Indigo)
    Catalog.TEXT_TOOLBAR -> FpIcon(FpGlyphs.TextFields, PlateColors.Gray)
    Catalog.SHARE_ORDER -> FpIcon(Icons.Rounded.Share, PlateColors.Green)
    // 负一屏小部件归入杂项后仍使用小部件图标。
    Catalog.PA_WIDGET_ADMISSION, Catalog.PA_WIDGET_PICKER_INJECT, Catalog.WIDGET_STYLE ->
        FpIcon(FpGlyphs.Widgets, PlateColors.Teal)
    else -> groupIcon(spec.group)
}

/**
 * 列表行右侧的状态徽标。
 *
 * 优先级是**对用户的影响程度**，不是代码里的枚举顺序：
 *  1. 作用域没勾 —— 开了也不会生效，最该先说；
 *  2. 实验特性 —— 会崩/会变，用之前该知道；
 *  3. 需重启进程 —— 开关拨了但还没生效，否则用户会以为坏了。
 * 关着的特性不标「需重启」：它本来就没在跑。
 */
@Composable
internal fun featureStatusChip(spec: FeatureSpec, prefs: Prefs): FpChip? {
    // 拨过还没重载：开、关两个方向都要说（关掉了但还在跑，同样是「没生效」）。交互审查 I-01
    if (io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore.isReloadPending(spec.key)) {
        return FpChip(fpString("feature.reload.chip"), FpEmphasis.WARNING)
    }
    if (!prefs.isEnabled(spec)) return null
    // 行内只说**需要用户处理**的状态（用户 2026-10-21：小字越少越好）。
    // 「实验」「重启后生效」是属性不是待办，放在详情页「启用」下方的灰字里。
    // 缺作用域不再在每行挂「需要授权」：首页状态卡已说明哪些功能受影响
    return null
}
