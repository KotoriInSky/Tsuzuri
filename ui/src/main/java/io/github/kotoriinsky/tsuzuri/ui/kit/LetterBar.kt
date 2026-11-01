package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kotoriinsky.tsuzuri.core.LetterIndex
import io.github.kotoriinsky.tsuzuri.ui.fpString

/**
 * 长列表右侧的字母索引条，以及拖动时显示的字母气泡。
 *
 * ## 为什么不进 [FpKit] 契约
 *
 * 它是一块**自绘控件**：两套风格在这件事上没有各自的表达方式（Material 3 和 MiuiX
 * 都没有索引条这种组件），进契约就意味着两处实现 + 两处契约测试，而两份实现唯一会做的事
 * 就是**手感漂移** —— 索引条的全部意义是「按住哪一格就跳到哪一组」，
 * 这件事不该有两种行为。配色仍然从 [LocalFpKit] 取，所以它看起来还是当前风格的一部分。
 *
 * ## 几何只有一份
 *
 * 画出来的每一格和「手指碰到的那一格」都由**条的实际高度等分**算出（布局用 `weight(1f)`
 * 等分，命中用 `高度 / 格数`），两者是同一个除法的结果，不可能对不上。
 * 这与 hook 侧 `AppIndex.letterAt` 是同一个算法（照搬 Appindex 的 `calculateSelectedLetter`），
 * 所以设置页和屏上索引条的**手感一致**。
 */

/**
 * 卡片右缘到屏幕边的距离（有索引条时）：条放在卡片外，开关和勾选框留在卡片里贴行尾，
 * 两者不重叠（用户 2026-10-31）。比普通卡片外边距多出的部分见 [letterBarGutter]。
 */
val LetterBarSpace: Dp = 36.dp

/** 有索引条时卡片右侧要额外让出的宽度。 */
@Composable
fun letterBarGutter(): Dp = LetterBarSpace - LocalFpKit.current.cardMargin

/** 少于这么多项时**不出现**索引条：短列表上它只是噪音（HCI 第 8 条）。 */
const val LetterBarMinItems: Int = 30

/** 条到屏幕边的留白。 */
private val BarEdgeInset: Dp = 4.dp

/** 条的宽度。28dp 与 hook 侧 `AppIndex.BAR_WIDTH_DP` 一致（那是参考实现的命中列宽）。 */
private val BarWidth: Dp = 28.dp

/** 单格的最大高度。与 hook 侧 `BAR_ITEM_DP`(16)+1 同量级，格数少时不会拉成一长条。 */
private val BarSlotMax: Dp = 17.dp

/** 字母的最大字号。与 hook 侧 `BAR_TEXT_DP`(12dp) 一致。 */
private val BarTextMax: Dp = 12.dp

/** 字号相对格高的比例（0.72 < hook 侧那个 0.8 的上限，留点余量）。 */
private const val BarTextRatio = 0.72f

/** 条上下留一点空，免得字母贴到卡片圆角上。 */
private val BarVerticalInset: Dp = 8.dp

private val BubbleSize: Dp = 96.dp
private val BubbleCorner: Dp = 28.dp
private val BubbleText: Dp = 40.dp

/**
 * 字母索引条。`letters` 是**实际存在的分组字母**（不是 A–Z 全集）：
 * 条上出现一个点下去什么也没有的字母，用户会以为「这个字母下没应用」。
 *
 * 交互是「按下即选中，不等 drag 的 touch slop」：索引条要的是手指按到哪一格就是哪一格，
 * 等 slop 会有一种按了没反应的感觉。
 *
 * @param activeLetter 当前选中的字母（由调用方持有，条只负责把它画成高亮）
 * @param onPick 手指落下和移动时都会回调，**不保证字母有变化**（去重是调用方的事，
 *               因为「同一个字母只振一次」这条规则需要状态，而状态在调用方那里）
 * @param onRelease 手指抬起或手势被打断时回调，用来收起气泡
 */
@Composable
fun LetterBar(
    letters: List<String>,
    activeLetter: String?,
    onPick: (String) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val colors = LocalFpKit.current.colors()
    val title = fpString("fan.picker.index.title")

    // 索引条是**图形化控件**：格高由可用高度等分而来，字母必须跟着格子缩放。
    // 所以这里把 fontScale 固定成 1 —— 跟着系统字号放大会让字母互相压住，
    // 而「条上第 5 格」这种位置信息并不因为用户调大字号就变得更需要（联系人索引条同理）。
    // 大小仍然全部用 dp/sp 写，不写死 px。
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
        BoxWithConstraints(
            modifier = modifier
                .padding(end = BarEdgeInset)
                .padding(vertical = BarVerticalInset)
                .width(BarWidth)
                .fillMaxHeight()
                .semantics { contentDescription = title }
                .pointerInput(letters) {
                    awaitEachGesture {
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pickLetter(letters, down.position.y, size.height, onPick)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                pickLetter(letters, change.position.y, size.height, onPick)
                                change.consume()
                            }
                        } finally {
                            // 手势被打断（重组换了 key、协程取消）也必须收尾，
                            // 否则气泡会一直留在屏幕上
                            onRelease()
                        }
                    }
                },
        ) {
            // 等分格高：布局用 weight(1f)、命中用 高度/格数，两者是同一个除法
            val slot = maxHeight / letters.size
            val slotDp = slot.value.coerceAtMost(BarSlotMax.value).coerceAtLeast(0f)
            val style = TextStyle(
                fontSize = (slotDp * BarTextRatio).coerceAtMost(BarTextMax.value).sp,
                fontWeight = FontWeight.Medium,
            )
            Column(Modifier.fillMaxSize()) {
                letters.forEach { letter ->
                    val active = letter == activeLetter
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = letter,
                            // 不读主题的文本样式：这是自绘控件，两套风格下必须长得一样
                            style = style.copy(color = if (active) colors.accent else colors.muted),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 拖动索引条时显示在列表中间的字母气泡。
 *
 * 为什么需要它：手指压住的那一格**被指尖挡住了**，条上的高亮看不见 ——
 * 气泡是拖动过程中唯一的反馈通道（HCI 第 1、2 条：状态可见 + 立刻反馈）。
 */
@Composable
fun LetterBubble(letter: String, modifier: Modifier = Modifier) {
    val colors = LocalFpKit.current.colors()
    Box(
        modifier = modifier
            .size(BubbleSize)
            .clip(RoundedCornerShape(BubbleCorner))
            .background(colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = letter,
            style = TextStyle(
                color = colors.onAccent,
                fontSize = BubbleText.value.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * 手指纵向位置 → 字母。
 *
 * 抽成普通函数（不是 composable、也不在 lambda 里）是为了能直接读懂、也能单测：
 * 它就是「等分格高 + 两端夹紧」，夹紧交给 [LetterIndex.slotAt]（它有单测，
 * 且在拖动时每帧都会被调用，不能抛异常）。
 */
private fun pickLetter(
    letters: List<String>,
    y: Float,
    barHeightPx: Int,
    onPick: (String) -> Unit,
) {
    if (letters.isEmpty() || barHeightPx <= 0) return
    val slotHeight = barHeightPx.toFloat() / letters.size
    val slot = LetterIndex.slotAt(y, 0f, slotHeight, letters.size)
    letters.getOrNull(slot)?.let(onPick)
}
