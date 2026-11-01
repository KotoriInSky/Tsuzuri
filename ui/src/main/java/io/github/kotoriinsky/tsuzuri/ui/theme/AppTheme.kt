package io.github.kotoriinsky.tsuzuri.ui.theme

import android.os.Build
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpClickFeedback
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpBarBlur
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.M3Kit
import io.github.kotoriinsky.tsuzuri.ui.kit.MiuixKit
import io.github.kotoriinsky.tsuzuri.ui.model.AccentMode
import io.github.kotoriinsky.tsuzuri.ui.model.Appearance
import io.github.kotoriinsky.tsuzuri.ui.model.DarkMode
import io.github.kotoriinsky.tsuzuri.ui.model.UiStyle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 外观总入口。**这是唯一一处知道「有几种风格」的地方** ——
 * 页面代码只认 [LocalFpKit]，不知道自己是澎湃还是 Material 3。
 *
 * 切换风格 = 换一个 Kit 实例 + 换一个主题壳子，不需要重启 Activity，
 * 主题分支会重新建立；AppRoot 在分支外保存返回栈与页面的 saveable 状态。
 *
 * 密度、字号、点击振动都放在**两套风格之外**统一施加：这样它们对两种风格
 * 行为严格一致，不需要各自的 Kit 再实现一遍（也就不会漏一边）。
 */
@Composable
fun AppTheme(
    appearance: Appearance,
    hapticFeedback: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = resolveDark(appearance.dark)
    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        SideEffect {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    val base = LocalDensity.current
    val scaled = remember(base.density, base.fontScale, appearance.density, appearance.fontScale) {
        Density(
            density = base.density * appearance.density.scale,
            fontScale = base.fontScale * appearance.fontScale.scale,
        )
    }
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalFpClickFeedback provides hapticFeedback,
        // 毛玻璃只对澎湃风格有意义：它进来之前先与「本风格是否支持」相与。
        // M3 下恒为 false —— M3 的 TopAppBar / NavigationBar 没有模糊语义，
        // 打开它只会让 M3Kit 去伪造一种 M3 不存在的东西（契约纪律）。判定写在
        // 提供处而不是 Kit 内部，是为了让「谁支持」这件事只有一个地方说了算。
        LocalFpBarBlur provides (appearance.barBlur && appearance.style == UiStyle.MIUIX),
    ) {
        when (appearance.style) {
            UiStyle.MIUIX -> MiuixThemeRoot(appearance, content)
            UiStyle.M3 -> M3ThemeRoot(appearance, content)
        }
    }
}

/** 深浅色只解一次，两套风格共用同一套判定规则。 */
@Composable
private fun resolveDark(mode: DarkMode): Boolean = when (mode) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

@Composable
private fun MiuixThemeRoot(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = resolveDark(appearance.dark)
    val controller = remember(appearance.dark, appearance.accent, appearance.customAccentArgb) {
        // 澎湃蓝走 MiuiX 自带调色板（它默认 primary 就是 0xFF3482FF），
        // 不自造 seed —— 自造的色调和 MIUI 各家系统应用对不上。
        val monet = appearance.accent != AccentMode.MIUI_BLUE
        val mode = when {
            !monet && appearance.dark == DarkMode.SYSTEM -> ColorSchemeMode.System
            !monet && appearance.dark == DarkMode.LIGHT -> ColorSchemeMode.Light
            !monet -> ColorSchemeMode.Dark
            appearance.dark == DarkMode.SYSTEM -> ColorSchemeMode.MonetSystem
            appearance.dark == DarkMode.LIGHT -> ColorSchemeMode.MonetLight
            else -> ColorSchemeMode.MonetDark
        }
        ThemeController(
            colorSchemeMode = mode,
            keyColor = when (appearance.accent) {
                AccentMode.SYSTEM -> null
                AccentMode.MIUI_BLUE -> Color(Appearance.MIUI_BLUE)
                AccentMode.CUSTOM -> Color(appearance.customAccentArgb)
            },
        )
    }
    MiuixTheme(controller = controller) {
        if (appearance.amoled && dark) {
            // 纯黑在 MiuiX 这边只能靠「再套一层主题」实现，绕不开：
            //   - `Colors` 的属性是 internal setter，也没有 copy()；
            //   - `LocalColors` 本身是 internal，盖不了；
            //   - `ThemeController` 的 darkColors 只在**非 Monet** 模式下生效，
            //     而「跟随系统取色 / 自定义」两个档位走的正是 Monet 分支。
            // 所以：先在外层读出用户真正选中的那套配色（澎湃蓝 / 种子 / 系统取色都已经算好了），
            // 复制成纯黑版本，再用 ColorSchemeMode.Dark 套第二层。
            // 这样纯黑对三种主题色都成立，不会出现「换个主题色纯黑就没了」。
            val resolved = MiuixTheme.colorScheme
            val pureBlack = remember(resolved) {
                ThemeController(
                    colorSchemeMode = ColorSchemeMode.Dark,
                    darkColors = resolved.toPureBlack(),
                )
            }
            MiuixTheme(controller = pureBlack) {
                CompositionLocalProvider(LocalFpKit provides MiuixKit, content = content)
            }
        } else {
            CompositionLocalProvider(LocalFpKit provides MiuixKit, content = content)
        }
    }
}

@Composable
private fun M3ThemeRoot(appearance: Appearance, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = resolveDark(appearance.dark)
    val scheme = remember(appearance.accent, appearance.customAccentArgb, dark, context) {
        when (appearance.accent) {
            // minSdk 36（Android 12 起才有的动态取色早已可用），不需要版本判断。
            AccentMode.SYSTEM -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            AccentMode.MIUI_BLUE -> tonalSpotSchemeFromSeed(Color(Appearance.MIUI_BLUE), dark)
            AccentMode.CUSTOM -> tonalSpotSchemeFromSeed(Color(appearance.customAccentArgb), dark)
        }
    }
    val finalScheme = if (appearance.amoled && dark) scheme.toPureBlack() else scheme
    MaterialTheme(colorScheme = finalScheme) {
        CompositionLocalProvider(LocalFpKit provides M3Kit, content = content)
    }
}

// 取色实现见 M3ColorScheme.kt 的 `tonalSpotSchemeFromSeed`：那边用完整的 HCT 色调盘
// （不再是「只覆 5 个槽位」的简化版）。这里不再放一个同名私有函数，避免读代码时
// 以为用的是本地实现。

/**
 * AMOLED：把「表面」相关的槽位换成纯黑。
 *
 * 只动背景与容器，**不动 surfaceVariant / outline** —— 那两个槽位是标签和分隔线的底色，
 * 一起刷黑会让 badge 和卡片边界消失，用户就分不清「这是一张卡」还是「这是一段文字」了。
 */
private fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainerLowest = Color.Black,
    // 高阶容器**不刷黑**：对话框 / 菜单用 surfaceContainerHigh，
    // 在纯黑背景上再刷黑就变成「没有边界的黑块」（真机 2026-10-17 对话框直接看不见）。
)

/**
 * MiuiX 版的同一件事。
 *
 * 为什么要把 53 个槽位全列一遍：MiuiX 的 `Colors` 属性带的是 internal setter，
 * 外部改不了单个槽位，而它也没有 `copy()`。唯一能做的是用 `darkColorScheme(...)`
 * 重新造一份 —— 于是「保持其他槽位不变」就得靠一个个把原值传进去。
 * 这段重复是库 API 逼出来的，不是我们偷懒；改 MiuiX 版本时要重新核对槽位名单。
 */
private fun Colors.toPureBlack(): Colors = top.yukonga.miuix.kmp.theme.darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryVariant = primaryVariant,
    onPrimaryVariant = onPrimaryVariant,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    disabledPrimary = disabledPrimary,
    disabledOnPrimary = disabledOnPrimary,
    disabledPrimaryButton = disabledPrimaryButton,
    disabledOnPrimaryButton = disabledOnPrimaryButton,
    disabledPrimarySlider = disabledPrimarySlider,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryVariant = secondaryVariant,
    onSecondaryVariant = onSecondaryVariant,
    disabledSecondary = disabledSecondary,
    disabledOnSecondary = disabledOnSecondary,
    disabledSecondaryVariant = disabledSecondaryVariant,
    disabledOnSecondaryVariant = disabledOnSecondaryVariant,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    secondaryContainerVariant = secondaryContainerVariant,
    onSecondaryContainerVariant = onSecondaryContainerVariant,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    tertiaryContainerVariant = tertiaryContainerVariant,
    background = Color.Black,
    onBackground = onBackground,
    onBackgroundVariant = onBackgroundVariant,
    surface = Color.Black,
    onSurface = onSurface,
    // surfaceVariant 刻意保持原值：标签底色刷黑会让 badge 与卡片糊在一起
    surfaceVariant = surfaceVariant,
    onSurfaceSecondary = onSurfaceSecondary,
    onSurfaceVariantSummary = onSurfaceVariantSummary,
    onSurfaceVariantActions = onSurfaceVariantActions,
    disabledOnSurface = disabledOnSurface,
    surfaceContainer = Color.Black,
    onSurfaceContainer = onSurfaceContainer,
    onSurfaceContainerVariant = onSurfaceContainerVariant,
    // 同上：高阶容器保留原深灰，让对话框/菜单在纯黑底上有边界。
    surfaceContainerHigh = surfaceContainerHigh,
    onSurfaceContainerHigh = onSurfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest,
    onSurfaceContainerHighest = onSurfaceContainerHighest,
    outline = outline,
    dividerLine = dividerLine,
    windowDimming = windowDimming,
    sliderKeyPoint = sliderKeyPoint,
    sliderKeyPointForeground = sliderKeyPointForeground,
    sliderBackground = sliderBackground,
)
