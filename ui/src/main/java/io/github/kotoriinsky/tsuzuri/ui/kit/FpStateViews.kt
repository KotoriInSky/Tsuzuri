package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.ui.fpString

/**
 * 页面级状态块：**加载 / 空 / 错误**。
 *
 * 为什么放在共用代码里而不是各页面自己拼：
 *  1. 这三种状态在每个页面长得必须一样，否则用户得重新学一遍；
 *  2. 它们只用 [FpKit] 的既有组件表达（Card + Body + ActionRow），
 *     所以两套风格自动就有，不需要各写一份实现，也就不存在「某一种风格漏了空态」。
 *
 * ⚠️ 这里是这条纪律的落点：**加载态与空态必须给出不同的字面**。
 * 「还没读到」和「读到了，是空的」都画成一片空白的话，用户只会以为模块坏了。
 */

/** 三种状态只差文案与强调色，排版共用一份（免得到处复制粘贴）。 */
@Composable
private fun StateBlock(
    title: String,
    hint: String?,
    emphasis: FpEmphasis,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val kit = LocalFpKit.current
    kit.Card {
        kit.Content {
        kit.Body(title, emphasis, FpTextStyle.SUBTITLE)
        if (hint != null) {
            kit.VSpace(4.dp)
            kit.Body(hint, FpEmphasis.MUTED, FpTextStyle.CAPTION)
        }
        }
        if (actionLabel != null && onAction != null) {
            kit.Divider()
            kit.ActionRow(
                title = actionLabel,
                summary = null,
                trailingText = fpString("common.open"),
                onClick = onAction,
            )
        }
    }
}

/** 统一外层留白，免得三种状态的间距各不相同。 */
@Composable
private fun StateSlot(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        LocalFpKit.current.VSpace(8.dp)
        content()
    }
}

/**
 * 加载态。标题固定走 `common.loading`，`hintKey` 用来说明**正在读什么** ——
 * 用户看到「读取中」时的下一个疑问必然是「在读什么」。
 */
@Composable
fun FpLoadingState(hintKey: String? = null, modifier: Modifier = Modifier) {
    StateSlot(modifier) {
        StateBlock(
            title = fpString("common.loading"),
            hint = hintKey?.let { fpString(it) },
            emphasis = FpEmphasis.MUTED,
            actionLabel = null,
            onAction = null,
        )
    }
}

/**
 * 空态。`titleKey` 与 `hintKey` 必须说清**为什么是空的**（没结果 / 被筛掉了 / 还没配），
 * 并尽量给一个能立刻做点什么的手势，而不是让用户自己找出口。
 */
@Composable
fun FpEmptyState(
    titleKey: String,
    hintKey: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    StateSlot(modifier) {
        StateBlock(
            title = fpString(titleKey),
            hint = hintKey?.let { fpString(it) },
            emphasis = FpEmphasis.NORMAL,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}

/**
 * 错误态。和空态的区别是**它是我们需要解释的**：强调色是负向的，
 * 并且一般带着重试入口（错误可以再试一次，空态通常不能）。
 */
@Composable
fun FpErrorState(
    titleKey: String,
    hintKey: String? = null,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    StateSlot(modifier) {
        StateBlock(
            title = fpString(titleKey),
            // detail 是技术原因（异常类名 / 框架给的字符串），和面向用户的 hint 分开摆 ——
            // 混成一句话既看不懂，也会诱惑我们在用户文案里泄露不该有的细节
            hint = listOfNotNull(hintKey?.let { fpString(it) }, detail).joinToString("\n"),
            emphasis = FpEmphasis.NEGATIVE,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}
