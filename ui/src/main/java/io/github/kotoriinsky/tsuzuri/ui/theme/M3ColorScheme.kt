package io.github.kotoriinsky.tsuzuri.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot

/**
 * 从一个种子色生成**完整的** Material 3 配色。
 *
 * ## 为什么需要这个文件
 *
 * 原实现（`AppTheme.m3SchemeFromSeed`）只覆写 primary / onPrimary / primaryContainer /
 * secondaryContainer **五个槽位**，其余全靠 `lightColorScheme()` 的默认值。后果是
 * 「换主题色只有主按钮变色」—— 那正是用户反馈的「自定义色在 Material 3 下偏平」。
 *
 * 现在改用 Google 官方的 **HCT（Hue-Chroma-Tone）色调盘**算法：
 * 1. 种子色 → `Hct.fromInt()` 转成 HCT 色彩空间；
 * 2. `SchemeTonalSpot` 按 M3 规范挑出 30+ 个色槽（primary/secondary/tertiary/neutral 四族
 *    各自的 tone 组合）；
 * 3. 逐槽映射到 AndroidX 的 `ColorScheme`。
 *
 * 这样「次要色、表面色、容器色」全部由种子色派生，换一次主题色整个界面才真的换色。
 *
 * ## 为什么不用 `material-kolor` 的高层 API
 *
 * 那个库是 Compose Multiplatform 的（依赖 `org.jetbrains.compose.material3`），
 * 与本项目的 AndroidX Compose 不是同一条线。这里只取最底层的算法部分
 * （`material-color-utilities`，零 UI 依赖），映射自己写 —— 边界清楚，也不会
 * 把 M3 组件版本拉偏。
 *
 * ## SPEC_2021 vs SPEC_2025
 *
 * 用 **SPEC_2021**：它是 Android 12 以来的既定 M3 规范，`lightColorScheme()` /
 * `darkColorScheme()` 的默认槽位定义与它一致。SPEC_2025 是给 M3 Expressive 用的新规范，
 * 会让同一种子色产生明显不同的结果 —— 而本项目的 M3 侧刻意**不上 Expressive**
 * （组件形态保持标准 M3），所以配色规范也必须跟着停在 2021，否则会出现
 * 「配色是 Expressive 的、组件是标准的」这种半吊子状态。
 */
internal fun tonalSpotSchemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val scheme = runCatching {
        SchemeTonalSpot(
            // 参数名是 Kotlin 侧的名字（`sourceColorHct`），不是 javap 显示的 JVM 名（`hct`）。
            sourceColorHct = Hct.fromInt(seed.toArgbInt()),
            isDark = dark,
            contrastLevel = 0.0,
            specVersion = ColorSpec.SpecVersion.SPEC_2021,
            platform = DynamicScheme.Platform.PHONE,
        )
    }.getOrNull()
        // 取色失败不该让界面崩：退回标准配色（不跟随种子色）。
        // 这条路径不常见（只会在种子色是异常值/库版本不兼容时命中），但「宁可没换色，
        // 也不能白屏」—— 与整个项目对 ROM 私有能力的处理态度一致。
        ?: return if (dark) darkColorScheme() else lightColorScheme()

    return if (dark) {
        darkColorScheme(
            primary = scheme.primary.c(),
            onPrimary = scheme.onPrimary.c(),
            primaryContainer = scheme.primaryContainer.c(),
            onPrimaryContainer = scheme.onPrimaryContainer.c(),
            inversePrimary = scheme.inversePrimary.c(),
            secondary = scheme.secondary.c(),
            onSecondary = scheme.onSecondary.c(),
            secondaryContainer = scheme.secondaryContainer.c(),
            onSecondaryContainer = scheme.onSecondaryContainer.c(),
            tertiary = scheme.tertiary.c(),
            onTertiary = scheme.onTertiary.c(),
            tertiaryContainer = scheme.tertiaryContainer.c(),
            onTertiaryContainer = scheme.onTertiaryContainer.c(),
            background = scheme.background.c(),
            onBackground = scheme.onBackground.c(),
            surface = scheme.surface.c(),
            onSurface = scheme.onSurface.c(),
            surfaceVariant = scheme.surfaceVariant.c(),
            onSurfaceVariant = scheme.onSurfaceVariant.c(),
            surfaceTint = scheme.surfaceTint.c(),
            inverseSurface = scheme.inverseSurface.c(),
            inverseOnSurface = scheme.inverseOnSurface.c(),
            error = scheme.error.c(),
            onError = scheme.onError.c(),
            errorContainer = scheme.errorContainer.c(),
            onErrorContainer = scheme.onErrorContainer.c(),
            outline = scheme.outline.c(),
            outlineVariant = scheme.outlineVariant.c(),
            scrim = scheme.scrim.c(),
            surfaceBright = scheme.surfaceBright.c(),
            surfaceDim = scheme.surfaceDim.c(),
            surfaceContainer = scheme.surfaceContainer.c(),
            surfaceContainerHigh = scheme.surfaceContainerHigh.c(),
            surfaceContainerHighest = scheme.surfaceContainerHighest.c(),
            surfaceContainerLow = scheme.surfaceContainerLow.c(),
            surfaceContainerLowest = scheme.surfaceContainerLowest.c(),
        )
    } else {
        lightColorScheme(
            primary = scheme.primary.c(),
            onPrimary = scheme.onPrimary.c(),
            primaryContainer = scheme.primaryContainer.c(),
            onPrimaryContainer = scheme.onPrimaryContainer.c(),
            inversePrimary = scheme.inversePrimary.c(),
            secondary = scheme.secondary.c(),
            onSecondary = scheme.onSecondary.c(),
            secondaryContainer = scheme.secondaryContainer.c(),
            onSecondaryContainer = scheme.onSecondaryContainer.c(),
            tertiary = scheme.tertiary.c(),
            onTertiary = scheme.onTertiary.c(),
            tertiaryContainer = scheme.tertiaryContainer.c(),
            onTertiaryContainer = scheme.onTertiaryContainer.c(),
            background = scheme.background.c(),
            onBackground = scheme.onBackground.c(),
            surface = scheme.surface.c(),
            onSurface = scheme.onSurface.c(),
            surfaceVariant = scheme.surfaceVariant.c(),
            onSurfaceVariant = scheme.onSurfaceVariant.c(),
            surfaceTint = scheme.surfaceTint.c(),
            inverseSurface = scheme.inverseSurface.c(),
            inverseOnSurface = scheme.inverseOnSurface.c(),
            error = scheme.error.c(),
            onError = scheme.onError.c(),
            errorContainer = scheme.errorContainer.c(),
            onErrorContainer = scheme.onErrorContainer.c(),
            outline = scheme.outline.c(),
            outlineVariant = scheme.outlineVariant.c(),
            scrim = scheme.scrim.c(),
            surfaceBright = scheme.surfaceBright.c(),
            surfaceDim = scheme.surfaceDim.c(),
            surfaceContainer = scheme.surfaceContainer.c(),
            surfaceContainerHigh = scheme.surfaceContainerHigh.c(),
            surfaceContainerHighest = scheme.surfaceContainerHighest.c(),
            surfaceContainerLow = scheme.surfaceContainerLow.c(),
            surfaceContainerLowest = scheme.surfaceContainerLowest.c(),
        )
    }
}

/**
 * HCT 取色库用的是 **ARGB int**，Compose 用的是自己的 `Color` 值类。
 * 这两个世界的转换只在这里出现一次，别处不许再写。
 *
 * `Color(argb)` 收的是 `ULong`（因为 `Color` 内部是 64 位），
 * 所以 int 必须先 `toLong()` 再 `and 0xFFFFFFFF` 去掉符号扩展 ——
 * 少了 `and` 那一步，深色（ARGB 高位为 1，int 为负）会变成全白。
 */
private fun Int.toComposeColor(): Color = Color(this.toLong() and 0xFFFFFFFFL)

private fun Int.c(): Color = toComposeColor()

/** `Color` → ARGB int。与 [toComposeColor] 互为逆运算，同样只此一处。 */
private fun Color.toArgbInt(): Int = toArgb()
