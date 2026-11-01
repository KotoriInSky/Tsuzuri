package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 两套 Kit 共用的小工具。
 *
 * 这里只放「和风格无关」的东西：状态色、圆角小标签的排版、点击反馈。
 * 任何一放进来就会让某一种风格失真的东西，都不属于这里。
 */

/** 判断当前是不是深色配色 —— 用表面色亮度推断，这样两套风格都不用各自维护一份标志位。 */
fun Color.isDarkSurface(): Boolean = luminance() < 0.5f

/**
 * 点列表项时是否振动。
 *
 * 为什么不直接让 Kit 去读 `AppSettings`：Kit 契约第 3 条要求「Kit 只负责画，不读配置」。
 * 做成 CompositionLocal 之后，两套实现读的是同一个值 —— 否则很容易出现
 * 「澎湃风格振动、Material 风格不振动」这种只在一种风格下复现的 bug。
 */
val LocalFpClickFeedback = staticCompositionLocalOf { true }

/**
 * 顶栏 / 底栏是否使用**半透明**底色。
 *
 * 和 [LocalFpClickFeedback] 同一套路数：能力只有一种风格拥有时，**不进 [FpKit] 接口**，
 * 而是做成 CompositionLocal —— 这样契约签名不变（页面层不需要知道有这回事），
 * 而拥有该能力的 Kit（[MiuixKit]）能读到它。契约接口里加一个「只有一边能用的参数」
 * 等于把某一方的实现细节写进契约，那正是 FpKit 注释第 1 条禁止的。
 *
 * ⚠️ 2026-10-17 语义修正：它原来叫「毛玻璃」，但实现从未真正模糊过（见
 * [fpScaffoldBarBackground] 的注释）。现在它只表示**栏体半透明**，文案与能力一致。
 * 默认 false：与 [io.github.kotoriinsky.tsuzuri.ui.model.Appearance.barBlur] 的默认值相反，
 * 这个值代表「当前风格支持且用户想要」，Appearance 那个只代表「用户想要」。
 */
val LocalFpBarBlur = staticCompositionLocalOf { false }

/** 不振动时共用的空实现：避免每次重组都新建一个 lambda，把下游的 stability 判断弄失效。 */
private val NoFeedback: () -> Unit = {}

/** 返回「点了列表项」时应调用的回调。振动关闭时返回空实现。 */
@Composable
fun fpRowFeedback(): () -> Unit {
    if (!LocalFpClickFeedback.current) return NoFeedback
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}

/**
 * 可点击的列表行。两套 Kit 都走它，所以「振动开关」和「禁用时不可点」
 * 这两种行为在风格之间不可能不一致。
 */
@Composable
fun Modifier.fpRowClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val feedback = fpRowFeedback()
    return clickable(enabled = enabled, role = role) {
        feedback()
        onClick()
    }
}

/**
 * 语义状态色。
 *
 * Material 3 和 MiuiX 都没有「成功/警告」这两个语义色（只有 error），
 * 所以这里给一套固定值，并按深浅色切换明度，保证对比度。
 */
object FpStatusColors {
    val positiveLight = Color(0xFF1E7B34)
    val positiveDark = Color(0xFF7BD68C)
    val warningLight = Color(0xFF9A5B00)
    val warningDark = Color(0xFFF2B457)

    fun positive(dark: Boolean) = if (dark) positiveDark else positiveLight
    fun warning(dark: Boolean) = if (dark) warningDark else warningLight
}

/**
 * 状态小标签。两套 Kit 都用它，只在圆角和字号上略有差异 ——
 * 这点差异不足以值得写两份实现，所以放在共用代码里。
 *
 * 这里刻意**不收 text 参数**：文字由 `content` 画，收一个用不到的字符串
 * 只会让人以为它会被渲染出来。
 */
@Composable
fun FpBadge(
    color: Color,
    onColor: Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        content()
    }
}

/** 各 Kit 里给状态标签用的固定圆角。 */
internal val BadgeCornerM3 = 8.dp
internal val BadgeCornerMiuix = 6.dp

/**
 * 行右侧「值」那一列的最大宽度。两套 Kit 共用同一个值。
 *
 * 为什么需要一个**硬上限**：右值列里放的多半是包名（`com.huanchengfly.tieba.post`），
 * 而两套 Kit 的行组件都**不给右值列加宽度约束**（宽度 = 内容固有宽度）。于是只写
 * 「单行 + 省略号」是无效的 —— 省略号只在**被约束**时才生效，不约束时它照样量出完整一行的宽度，
 * 把标题那一列挤成 0。表现是「包名压着图标包名」。
 *
 * 160dp 的取法：大约 10 个汉字 / 22 个西文字符，够放下「第 7 项，共 12 项」「已选 8 项」
 * 这类短值，同时给标题留下至少一半行宽。**它取代的是「无上限」，不是「自适应」** ——
 * 值短的时候 [androidx.compose.foundation.layout.widthIn] 只封顶不收窄，行外观不变。
 */
internal val FpTrailingValueWidth = 160.dp

/**
 * 把一段**没有空格的长串**（包名、`包名/id`、`link:<uri>`）预先截断成一行放得下的长度。
 *
 * 为什么要在字符串上做，而不是靠 `maxLines`：**MiuiX 的行组件自己画标题与副标题**
 * （`ArrowPreference` 只收 `title: String` / `summary: String`），我们拿不到它的 `Text`，
 * 也就设不了 `maxLines`。于是同一条副标题在 M3 下是单行省略、在澎湃下会**从词中间硬折行**
 * （`com.tencent.mobil` / `eqq`）—— 那既难看，又让那一行比邻居高，而行高参差会让
 * 拖拽排序的「让位/落位」看起来在抖。
 *
 * 在**字符串层面**截断是唯一同时作用于两套 Kit 的做法。代价是尾巴看不见了 ——
 * 这条信息（配置键）在排障时有用，所以截断要留出足够长度（默认 26 字符≈常见包名整串），
 * 并且**只在真的放不下时才截**。
 *
 * 用 `…`（单个字符）而不是 `...`：三个点在小字号下几乎占一个汉字宽，而这里每一格都很紧。
 */
internal fun fpClampSingleLine(text: String, max: Int = 26): String =
    if (text.length <= max) text else text.take(max - 1) + "…"

/**
 * 栏体（顶栏 / 底栏）的底色。
 *
 * ## 2026-10-17 修正：这里原来是「假毛玻璃」
 *
 * 旧实现给栏体垫了一个空 Box 再对它 `Modifier.blur` —— 空层没有可模糊的像素，
 * 所以那个开关在真机上**从来没有产生过模糊**，用户看到的只是一个半透明栏体。
 * 更糟的是设置里写着「栏体毛玻璃」：宣称了一件没发生的事。
 *
 * 现在的做法是**如实做半透明**：栏体用 86% 不透明度的表面色盖在内容上，
 * 下方内容隐约透出来（HyperOS 栏体的观感主要来自这层半透明，而不是模糊）。
 * 设置项文案也改成了「半透明栏体」—— 能力边界与文案一致。
 *
 * 真正的 backdrop blur 需要抓取栏体下方已绘制的像素（RenderEffect 作用于自身子树，
 * 拿不到「背后」），在 Compose 里不是一行代码能做的事；现阶段不做，也不假装做了。
 */
@Composable
internal fun Modifier.fpScaffoldBarBackground(translucent: Boolean): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    return background(if (translucent) surface.copy(alpha = 0.86f) else surface)
}