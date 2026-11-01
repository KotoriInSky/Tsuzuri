package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** 全应用共用一个可见消息宿主，页面只提交动作结果。 */
val LocalFpMessageHost = staticCompositionLocalOf<SnackbarHostState> {
    error("消息宿主必须由 AppRoot 提供")
}

/** 排队与计时交给 SnackbarHost，外观交给各风格的 [FpKit.Toast]（MiuiX 是 HyperOS 小胶囊）。 */
@Composable
fun FpMessageHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val kit = LocalFpKit.current
    SnackbarHost(state, modifier) { data -> kit.Toast(data.visuals.message) }
}
