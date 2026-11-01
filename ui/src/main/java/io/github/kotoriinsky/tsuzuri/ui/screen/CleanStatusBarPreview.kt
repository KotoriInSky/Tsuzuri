package io.github.kotoriinsky.tsuzuri.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.kotoriinsky.tsuzuri.core.CleanStatusBar
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit

/**
 * 「完美状态栏」预览：开启后状态栏的样子（08:16、满格、满电、没有通知图标）。
 *
 * 图标是**手机上 SystemUI 自己的**（运行时从 `com.android.systemui` 的资源里取，不复制进仓库）：
 * `stat_sys_signal_4`、`stat_sys_wifi_signal_3`（本版最高档）、`battery_meter_bg` 叠
 * `battery_meter_progress_normal`（同一张 28×20 画布，叠起来就是满电）。
 * 时间用系统默认字体，与状态栏同一套。取不到（换了 ROM / 资源改名）就不画那一枚，不编一个假的。
 */
@Composable
internal fun CleanStatusBarPreview() {
    val context = LocalContext.current
    val colors = LocalFpKit.current.colors()
    val ink = colors.onSurface
    val icons = remember(ink) { SystemBarIcons.load(context, ink.toArgb()) }
    // 与开关同卡，浅底 + 前景色，像设置里的示意条（用户 2026-10-27：不要再套一层白卡、不要深色壁纸）
    Box(Modifier.fillMaxWidth().padding(start = FpSpace.m, end = FpSpace.m, bottom = FpSpace.m)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceVariant)
                .padding(horizontal = 18.dp)
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                CleanStatusBar.DEMO_CLOCK.let { it.substring(0, 2) + ":" + it.substring(2) },
                color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            icons.signal?.let { Icon(it, 18.dp) }
            icons.wifi?.let { Spacer(Modifier.width(4.dp)); Icon(it, 18.dp) }
            if (icons.batteryFrame != null) {
                Spacer(Modifier.width(4.dp))
                Box {
                    Icon(icons.batteryFrame, width = 25.dp, height = 18.dp)
                    icons.batteryFill?.let { Icon(it, width = 25.dp, height = 18.dp) }
                }
            }
        }
    }
}

@Composable
private fun Icon(bitmap: ImageBitmap, size: Dp) = Icon(bitmap, size, size)

@Composable
private fun Icon(bitmap: ImageBitmap, width: Dp, height: Dp) {
    Image(bitmap, contentDescription = null, modifier = Modifier.size(width = width, height = height))
}

/** 从 SystemUI 取来的四张图，着成预览条的前景色。 */
private class SystemBarIcons(
    val signal: ImageBitmap?,
    val wifi: ImageBitmap?,
    val batteryFrame: ImageBitmap?,
    val batteryFill: ImageBitmap?,
) {
    companion object {
        private const val SYSTEM_UI = "com.android.systemui"

        @SuppressLint("DiscouragedApi") // 取的是另一个包的资源，只能按名字查
        fun load(context: Context, tint: Int): SystemBarIcons {
            val res = runCatching { context.packageManager.getResourcesForApplication(SYSTEM_UI) }.getOrNull()
                ?: return SystemBarIcons(null, null, null, null)
            fun get(name: String): ImageBitmap? = runCatching {
                val id = res.getIdentifier(name, "drawable", SYSTEM_UI)
                if (id == 0) return@runCatching null
                val d: Drawable = res.getDrawable(id, null).mutate()
                d.setTint(tint)
                val w = d.intrinsicWidth.coerceAtLeast(1) * 3
                val h = d.intrinsicHeight.coerceAtLeast(1) * 3
                d.toBitmap(w, h).asImageBitmap()
            }.getOrNull()
            return SystemBarIcons(
                signal = get("stat_sys_signal_4"),
                wifi = get("stat_sys_wifi_signal_4") ?: get("stat_sys_wifi_signal_3"),
                batteryFrame = get("battery_meter_bg"),
                batteryFill = get("battery_meter_progress_normal"),
            )
        }
    }
}
