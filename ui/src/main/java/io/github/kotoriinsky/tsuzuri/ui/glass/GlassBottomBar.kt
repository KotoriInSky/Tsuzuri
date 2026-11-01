package io.github.kotoriinsky.tsuzuri.ui.glass

import androidx.compose.foundation.background
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.drawBackdrop
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.kotoriinsky.tsuzuri.ui.glass.kyant.LiquidBottomTab
import io.github.kotoriinsky.tsuzuri.ui.glass.kyant.LiquidBottomTabs
import io.github.kotoriinsky.tsuzuri.ui.kit.FpNavItem
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSize
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings

/** 悬浮底栏离屏幕两侧与系统导航栏的距离（两种悬浮底栏共用，内容留白也按它算）。 */
private val FLOAT_SIDE = 20.dp
private val FLOAT_BOTTOM = 10.dp

/** 悬浮底栏占掉的总高度（栏高 + 下边距 + 系统导航栏），列表底部留白用它。 */
@Composable
fun floatingBarInset() =
    FpSize.glassBar + FLOAT_BOTTOM + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * 抓取「页面内容」的 backdrop。
 *
 * 先铺一层不透明底色再 `drawContent()`：不铺的话底栏会透出窗口的透明像素，
 * 玻璃上出现黑块（Backdrop 官方教程第一步就踩这个坑）。
 */
@Composable
fun rememberSceneBackdrop(background: Color): LayerBackdrop =
    rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }

/** 悬浮位置：左右留 [FLOAT_SIDE]，底部在系统导航栏之上再留 [FLOAT_BOTTOM]。 */
@Composable
private fun Modifier.floatingPlacement(): Modifier {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return fillMaxWidth().padding(start = FLOAT_SIDE, end = FLOAT_SIDE, bottom = navInset + FLOAT_BOTTOM)
}

/**
 * 液态玻璃底栏：Kyant 示例里的 `LiquidBottomTabs`（选中项是可拖动、会形变、带色散的玻璃滑块）。
 *
 * 它**画在页面内容之上**，所以内容要能滚到它下面：调用方把列表 bottom padding 设成 [floatingBarInset]。
 * 只在澎湃风格 + 「悬浮底栏」+「液态玻璃」都开时出现（用户 2026-10-25）。
 */
@Composable
fun FpGlassBottomBar(
    backdrop: Backdrop,
    items: List<FpNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFpKit.current.colors()
    val haptic = LocalHapticFeedback.current
    val light = colors.surface.luminance() > 0.5f
    // 必须是**稳定**的、读 State 的 lambda：LiquidBottomTabs 以它为 key 记住当前项，
    // 并用 snapshotFlow 观察它。每次重组传一个捕获了值的新 lambda，滑块就永远停在初始位置（真机 2026-10-25）。
    val selected = androidx.compose.runtime.rememberUpdatedState(selectedIndex)
    val selectedProvider = androidx.compose.runtime.remember { { selected.value } }
    LiquidBottomTabs(
        selectedTabIndex = selectedProvider,
        onTabSelected = { index ->
            // 不用「和当前页比较」做去重：回调可能晚于重组，比较的是旧值就会把真切换吞掉
            if (AppSettings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onSelect(index)
        },
        backdrop = backdrop,
        tabsCount = items.size,
        accentColor = colors.accent,
        containerColor = colors.surface.copy(alpha = 0.4f),
        isLightTheme = light,
        modifier = modifier.floatingPlacement(),
    ) {
        items.forEachIndexed { index, item ->
            LiquidBottomTab({ onSelect(index) }) {
                // 统一用正文色：选中态的主题色由 LiquidBottomTabs 的着色层叠出来，这里再上色就会叠两层
                Icon(item.icon, contentDescription = item.label, tint = colors.onSurface, modifier = Modifier.size(24.dp))
                Text(item.label, color = colors.onSurface, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/**
 * 悬浮底栏 ·「柔光玻璃」样式（参考小米相册底栏，用户 2026-10-25 给的截图）。
 *
 * 乳白磨砂（背景模糊 + 一层半透明白）+ 一圈细白描边 + 柔和投影；**不折射、不色散**。
 * 选中项是胶囊里一块更深的灰色内胶囊，随切换滑动；图标与文字统一正文色，不用主题色。
 * 只在澎湃风格下用，M3 保持贴底栏；「液态玻璃」打开时换成 [FpGlassBottomBar]。
 */
@Composable
fun FpFloatingBottomBar(
    backdrop: Backdrop,
    items: List<FpNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFpKit.current.colors()
    val haptic = LocalHapticFeedback.current
    val light = colors.surface.luminance() > 0.5f
    val shape = RoundedCornerShape(50)
    // 乳白：浅色下压白，深色下压深灰；描边永远是偏白的细线（截图里那圈亮边）
    val milk = if (light) Color.White.copy(alpha = 0.72f) else Color(0xFF2A2A2A).copy(alpha = 0.72f)
    val rim = if (light) Color.White else Color.White.copy(alpha = 0.18f)
    val pill = if (light) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.10f)
    val pos by androidx.compose.animation.core.animateFloatAsState(
        selectedIndex.toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "softGlassPill",
    )
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .floatingPlacement()
            .height(FpSize.glassBar)
            .shadow(16.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(16.dp.toPx())
                },
                onDrawSurface = { drawRect(milk) },
            )
            .border(1.dp, rim, shape)
            .padding(4.dp),
    ) {
        val tabW = maxWidth / items.size
        androidx.compose.foundation.layout.Box(
            Modifier
                .graphicsLayer { translationX = pos * tabW.toPx() }
                .size(tabW, maxHeight)
                .clip(shape)
                .background(pill),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable {
                            if (AppSettings.hapticFeedback) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(index)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = colors.onSurface, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, color = colors.onSurface, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}
