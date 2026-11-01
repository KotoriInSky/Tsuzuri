package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.runtime.Composable
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore

/**
 * 「你的改动还没到真机」。
 *
 * ## 为什么需要这一条，而不是「靠日志」
 *
 * 前端在框架没连上（或写远端失败）时会把改动写进本地草稿。这时**界面照样显示新值** ——
 * 因为它读的就是那份草稿 —— 而真机读的是另一份存储。用户看到的现象是
 * 「我明明把这个开关打开了，真机一点反应没有」，看起来和「这个参数是坏的」一模一样。
 *
 * 真机上出现过一对实证：`f.pa_widget_admission` 在草稿里是 `true`、在真机读的那份里是
 * `false`。当时唯一的线索是 hook 日志里一行 `window_custom=off` —— 而用户看不见日志。
 *
 * 所以这条提示不是「锦上添花的 UI」，它是**这个通道唯一的诚实出口**：
 * 只要显示出来了，就说明界面上的一切新值都是假的（真机没收到）。
 *
 * ## 为什么只在「真的压着东西」时才出现
 *
 * `degraded` 在框架没绑定时就是 `true`，但**没改动过就没有东西卡住** ——
 * 那时弹一条「改动没生效」是纯粹的噪音（首页已经有一条「未连接框架」了）。
 * 判据用 `pendingCount > 0`：草稿里真的压着几项没送出去的东西。
 */
@Composable
fun ConfigChannelBanner() {
    val kit = LocalFpKit.current
    if (ConfigStore.lastWriteFailed) {
        kit.Banner(fpString("config_channel.failed"), FpEmphasis.NEGATIVE)
        return
    }
    // 先读状态再读计数：订阅（重组）来自这个 Compose 状态，计数是从存储现算的普通值
    val degraded = ConfigStore.degraded
    if (!degraded) return
    val pending = ConfigStore.pendingCount
    if (pending <= 0) return

    kit.Banner(
        text = fpString("config_channel.degraded", pending),
        emphasis = FpEmphasis.WARNING,
        actionLabel = fpString("config_channel.retry"),
        onAction = { ConfigStore.retryChannel() },
    )
}
