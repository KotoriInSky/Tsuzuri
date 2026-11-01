package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 列表行首的图标位（**两套 Kit 共用**，而且是全项目唯一一份）。
 *
 * ## 为什么必须收成一处
 *
 * 同一个模式（`Box(clip + background(占位色)) + Image`）曾经在四处各写了一遍，
 * 于是「底板该不该画」这个问题在四处各有一个答案。用户实测看到的是：
 * **图标本身带透明区域时（QQ、Via）会露出一个浅色方块**，看起来像「图标带了个边框底板」；
 * 图标满幅时（微信、虎扑）被完全盖住、看不出问题 —— 所以这个 bug 只在部分图标上复现，
 * 最容易在只测一两个应用之后被当成「已经修好了」。
 *
 * ## 规则（只有一条，但很关键）
 *
 * **占位色只在没有图的时候画**（`bitmap == null`）。拿到图之后底板一律透明 ——
 * 图自己的透明区域就该露出它下面的行背景。
 *
 * ⚠️ 不要顺手把「加载中的占位」也去掉：取不到图标时留一块占位色是**刻意**的，
 * 它保证（a）行高不因为在等图而来回跳，（b）用户看得出这里是「还没加载出来」而不是
 * 「这一项没有图标」。所以这块占位色是**状态**，不是装饰。
 *
 * ## 圆角还要不要
 *
 * 要。`clip` 作用于图标本身：满幅的自适应图标会被裁成圆角（与旧外观逐像素一致），
 * 带透明区域的图标则连透明区一起裁 —— 没有底板之后它本来也看不见。
 *
 * @param bitmap `null` = 还没加载出来 / 这个包没有图标 → 画占位块
 * @param size 图标位边长（两处调用都是 40dp，与行高匹配）
 * @param corner 圆角（40dp 图标用 12dp）
 * @param contentDescription 图标是装饰性的就传 `null`：读屏把旁边的标题念一遍就够了，
 *   图标再念一遍只会更慢（除标题为空的行）。
 */
@Composable
fun FpIconPlate(
    bitmap: ImageBitmap?,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val placeholder = LocalFpKit.current.colors().surfaceVariant
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(if (bitmap == null) placeholder else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 行首图标的统一尺寸。抽成常量是因为「占位块大小」与「图大小」必须是同一个值。 */
val FpRowIconSize: Dp = 40.dp

/** 行首图标的统一圆角。 */
val FpRowIconCorner: Dp = 12.dp
