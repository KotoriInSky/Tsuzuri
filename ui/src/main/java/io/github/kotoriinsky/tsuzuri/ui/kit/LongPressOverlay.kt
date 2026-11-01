package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 给**已经可点击**的行（Kit 的 CheckRow / ActionRow，它们不收长按参数）叠一个长按。
 *
 * 按住超过系统长按时长、且没有被滚动接管、手指没挪出触摸阈值，触发 [onLongPress]；
 * 之后在 [PointerEventPass.Initial] 上把这一手势后续的事件全部消费掉（先于子节点）——
 * 子节点的点击因此被取消，长按不会顺带再「点」一下。没到时长就松手：事件照常交给子节点。
 */
fun Modifier.onLongPressFirst(enabled: Boolean = true, onLongPress: () -> Unit): Modifier = composed {
    if (!enabled) return@composed this
    val config = LocalViewConfiguration.current
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val cancelled = withTimeoutOrNull(config.longPressTimeoutMillis) {
                while (true) {
                    // Final 阶段看：列表的滚动在 Main 阶段消费事件，这里能看到「已被滚动接管」。
                    // 只比位移不够 —— 手指拖着列表时，行跟着手指一起动，行内坐标几乎不变
                    //（2026-10-22 真机：往上滑列表把「短信」误判成长按展开了）
                    val change = awaitPointerEvent(PointerEventPass.Final).changes
                        .firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    // 按下那一下在 Final 阶段已被行自己的点击消费过，不能算「被滚动接管」
                    //（第一次改漏了这条，长按一按就取消 —— 用户 2026-10-22 报长按打不开）
                    if (change.changedToDownIgnoreConsumed()) continue
                    if (!change.pressed || change.isConsumed) return@withTimeoutOrNull true
                    if ((change.position - down.position).getDistance() > config.touchSlop) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                true
            }
            if (cancelled == null) {
                onLongPress()
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    e.changes.forEach { it.consume() }
                    if (e.changes.all { !it.pressed }) break
                }
            }
        }
    }
}
