package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect

/**
 * 「触发」页的**屏幕热区高亮**：在屏幕上真实热区的位置画半透明块 + 描边。
 *
 * 三条约束，都是 ADR 里定的：
 *  1. **不拦触摸**：高亮层只是一块 Canvas，没有任何 pointerInput —— 用户就在高亮上起手，
 *     边缘手势先被 SystemUI 的输入监视截获，所以在设置界面里也能真实触发。
 *  2. **离页即消失**：只在「登记它的页面 == 返回栈栈顶」时画。推入下一页（比如从触发页进应用选择）
 *     或返回的那一刻栈顶就变了，不等页面被销毁，也不依赖导航库给条目的 lifecycle。
 *  3. **拖参数实时跟随**：页面每次重组把最新矩形写进来（类别页的预览草稿也在其中）。
 *
 * 坐标：矩形是**屏幕**像素（与 hook 判定同一口径），画的时候减去本层在屏幕上的原点，
 * 所以窗口被系统摆成小窗 / 分屏时也画在真实位置（或画出窗口外 = 看不见，不会画错地方）。
 */
private object ScreenHotZoneRegistry {
    var owner: Any? by mutableStateOf(null)
    var zones: List<ScreenRect> by mutableStateOf(emptyList())
}

/** 当前下钻页的身份（`Dest`）。由外壳的 DrillPage 提供；栈根页为 `null`（栈根不画高亮）。 */
val LocalPageKey = staticCompositionLocalOf<Any?> { null }

/** 本页在屏幕上画出 [zones]；离开组合时撤掉。空列表 = 什么都不画。 */
@Composable
fun ShowScreenHotZones(zones: List<ScreenRect>) {
    val page = LocalPageKey.current ?: return
    // 每次重组都写一次：拖滑杆时矩形每帧都在变，这就是「实时跟随」
    SideEffect {
        ScreenHotZoneRegistry.owner = page
        ScreenHotZoneRegistry.zones = zones
    }
    DisposableEffect(page) {
        onDispose {
            // 只撤自己登记的：返回动画里新旧两页可能短暂同时在组合中
            if (ScreenHotZoneRegistry.owner == page) {
                ScreenHotZoneRegistry.owner = null
                ScreenHotZoneRegistry.zones = emptyList()
            }
        }
    }
}

/**
 * 高亮层。挂在外壳最上层、铺满窗口，[activePage] 传返回栈栈顶。
 */
@Composable
fun ScreenHotZoneLayer(activePage: Any?, modifier: Modifier = Modifier) {
    val owner = ScreenHotZoneRegistry.owner
    val zones = ScreenHotZoneRegistry.zones
    if (owner == null || owner != activePage || zones.isEmpty()) return
    val colors = LocalFpKit.current.colors()
    val origin = remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { origin.value = it.positionOnScreen() }) {
        val o = origin.value
        for (z in zones) {
            if (z.width <= 0f || z.height <= 0f) continue
            val topLeft = Offset(z.left - o.x, z.top - o.y)
            val size = Size(z.width, z.height)
            drawRect(colors.accent.copy(alpha = 0.22f), topLeft, size)
            drawRect(colors.accent.copy(alpha = 0.8f), topLeft, size, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
