package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 身份头里图标的边长。HyperOS「关于本机」顶部的标志约占屏宽四分之一，这里取同一量级。 */
internal val AppIdentityIconSize = 96.dp

/**
 * [FpKit.AppIdentity] 的共享实现：两套风格（MiuiX / Material）在这里长得一样 ——
 * 它不是一个「控件」，而是页面顶部的一块排版，所以只用 foundation 的 `BasicText`，颜色取风格的 token。
 *
 * 排法照 HyperOS 设置「关于本机」：不进卡片，居中，图标 → 名称 → 一行灰字，下方才是选项卡片。
 */
@Composable
internal fun AppIdentityLayout(colors: FpColors, icon: ImageBitmap?, name: String, detail: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = FpSpace.l, bottom = FpSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            // 图标本身就是 APK 的启动图标（自适应图标已按系统遮罩渲染），不再套底板或描边
            Image(icon, contentDescription = null, modifier = Modifier.size(AppIdentityIconSize))
            androidx.compose.foundation.layout.Spacer(Modifier.size(FpSpace.l))
        }
        BasicText(
            name,
            style = TextStyle(
                color = colors.onSurface,
                fontSize = FpText.identityName,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
        )
        if (detail != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(FpSpace.xs))
            BasicText(
                detail,
                style = TextStyle(color = colors.muted, fontSize = FpText.rowSummary, textAlign = TextAlign.Center),
            )
        }
    }
}
