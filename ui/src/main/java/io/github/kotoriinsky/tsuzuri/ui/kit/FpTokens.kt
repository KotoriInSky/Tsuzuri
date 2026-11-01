package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌：**界面重写（2026-10-17）后唯一的一组尺寸常量**。
 *
 * 为什么要有这一层：重写前每个页面各写各的 `padding(horizontal = 12.dp)`、
 * `fontSize = 13.sp`，同一屏里出现 12/14/16 三种左右边距和 11/13/14/15 四种字号 ——
 * 用户读不出层级，只觉得「乱」。这里把间距、圆角、字号、动效时长收成一套，
 * 页面只允许引用令牌，不许再写裸数值（例外：预览画布这类按像素算的几何布局）。
 *
 * 令牌按 **4dp 网格**取值：HyperOS / MIUI 的卡片与行高都是 4 的倍数，
 * 取 5/7/13 这类数值会在不同密度下被取整，间距看起来忽宽忽窄。
 */
object FpSpace {
    /** 4dp：同一行内元素之间（图标与文字、徽标与标题）。 */
    val xs = 4.dp

    /** 8dp：行内主间距、卡片内段落间距。 */
    val s = 8.dp

    /** 12dp：行与行之间的副间距。 */
    val m = 12.dp

    /** 16dp：页面左右安全边距、卡片内边距。 */
    val l = 16.dp

    /** 20dp：大卡片内边距、页面标题区左右边距。 */
    val xl = 20.dp

    /** 28dp：区块之间。 */
    val xxl = 28.dp

    /** 40dp：页面底部留白（给底栏 + 手势条留呼吸）。 */
    val huge = 40.dp
}

/** 关键尺寸。文字尺寸单独列在 [FpText]。 */
object FpSize {
  /** 液态玻璃底栏的高度（不含系统导航栏 inset）。 */
  val glassBar = 64.dp

    /** 行最小高度：48dp 是点按目标下限，这里取 60dp 容纳「标题 + 两行摘要」。 */
    val rowMin = 60.dp

    /** 行首图标的底板（squircle 胶囊）。 */
    val plate = 40.dp
    val plateRadius = 12.dp

    /** 底板里的图标。 */
    val plateIcon = 20.dp

    /**
     * MiuiX 的实心色块（HyperOS 设置首页实测，2026-10-21）：
     * 色块 87px、白色图形外框 ≤ 59px，@520dpi 即 27dp / 18dp；
     * 对角线内缩 7px ⇒ 圆角半径约 24px ≈ 7.5dp。
     */
    val colorPlate = 27.dp
    val colorPlateRadius = 7.5.dp
    val colorPlateGlyph = 18.dp

    /** 行尾箭头的尺寸（比图标小一号，避免和底板抢注意力）。 */
    val chevron = 16.dp

    /** 状态徽标高度。 */
    val chipHeight = 22.dp

    /** 卡片圆角：HyperOS 的列表卡是 18dp；大状态卡用 24dp。 */
    val cardRadius = 18.dp
    val heroRadius = 24.dp

    /** 分隔线：0.5dp 起才在 3x 屏上不糊成 2dp。 */
    val hairline = 0.5.dp
}

/** 字号与行高。单位 sp，跟随系统字体缩放。 */
object FpText {
    val pageTitle = 30.sp
    val pageSubtitle = 13.sp
    val heroTitle = 22.sp
    /** 关于页身份头的应用名。 */
    val identityName = 26.sp
    val heroDetail = 13.sp
    val section = 13.sp
    val rowTitle = 16.sp
    val rowSummary = 13.sp
    val chip = 11.sp
    val caption = 12.sp
}

/** 动效时长。统一在这里，页面不许写裸毫秒。 */
object FpMotion {
    /** 120ms：按压、开关这类即时反馈。 */
    const val fastMs = 120

    /** 220ms：页面切换、展开收起。 */
    const val normalMs = 220

    /** 320ms：大卡片入场这类需要被看见的动作。 */
    const val slowMs = 320
}
