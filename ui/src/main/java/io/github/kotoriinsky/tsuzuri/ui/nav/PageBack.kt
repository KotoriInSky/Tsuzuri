package io.github.kotoriinsky.tsuzuri.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

class PageBackAction {
    var action: (() -> Unit)? by mutableStateOf(null)
        internal set
}

val LocalPageBackAction = staticCompositionLocalOf<PageBackAction> { error("缺少页面返回宿主") }

/**
 * 局部编辑接管返回；保存期间仍接管，但由页面决定是否执行。
 *
 * 两条返回路径都要接住：
 *  - 系统返回 / 侧滑：用 navigationevent 的 [NavigationBackHandler]。它注册在页面所在的
 *    NavDisplay 条目作用域里，优先级高于 NavDisplay 自己的出栈手势（KernelSU 首页回 tab 0 也是这么做的）；
 *  - 顶栏返回键：外壳通过 [LocalPageBackAction] 查到这里的动作。
 */
@Composable
fun PageBackHandler(onBack: () -> Unit) {
    val host = LocalPageBackAction.current
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(host) {
        val handler = { latest() }
        host.action = handler
        onDispose { if (host.action === handler) host.action = null }
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = { latest() },
    )
}
