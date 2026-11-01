package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 状态卡右下角的大水印（KSU「工作中」卡的摆法：110dp、外偏 27/31dp，两套风格共用）。
 *
 * 用户 2026-10-25 定：绿 = 圆圈对勾，黄 = 三角感叹号，红 = 圆圈叉。
 * 圆圈叉在 material-icons-core 里没有（Cancel 只在 extended，不引），所以用「圆环 + Close」拼出来。
 */
@Composable
internal fun BoxScope.StatusWatermark(tone: FpEmphasis, tint: Color) {
    val place = Modifier
        .align(Alignment.BottomEnd)
        .offset(x = 27.dp, y = 31.dp)
        .size(110.dp)
    when (tone) {
        FpEmphasis.POSITIVE -> Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = tint, modifier = place)
        FpEmphasis.WARNING -> Icon(Icons.Outlined.Warning, contentDescription = null, tint = tint, modifier = place)
        else -> Box(place.padding(9.dp).border(9.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = tint, modifier = Modifier.size(64.dp))
        }
    }
}

/** 水印颜色：绿沿用 KSU #36D167；黄、红取同饱和度的 HyperOS 警示色。 */
internal fun statusWatermarkColor(tone: FpEmphasis): Color = when (tone) {
    FpEmphasis.POSITIVE -> Color(0xFF36D167)
    FpEmphasis.WARNING -> Color(0xFFF5B000)
    else -> Color(0xFFF2564B)
}
