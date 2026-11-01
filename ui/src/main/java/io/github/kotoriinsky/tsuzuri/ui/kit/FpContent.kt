package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

internal val LocalFpCardContent = staticCompositionLocalOf { false }

/** 正文槽与偏好行分开，列表卡片仍由行组件负责内边距。 */
@Composable
fun FpKit.Content(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), content = content)
}

/** 独立段落：在卡片内对齐偏好行，在卡片外补上相同外边距。 */
@Composable
fun FpKit.Paragraph(text: String, emphasis: FpEmphasis = FpEmphasis.NORMAL, style: FpTextStyle = FpTextStyle.BODY) {
    Column(Modifier.fillMaxWidth().padding(horizontal = if (LocalFpCardContent.current) 16.dp else 28.dp, vertical = 6.dp)) {
        Body(text, emphasis, style)
    }
}

/**
 * 卡片的一段：把一张卡片拆进 LazyColumn 的多行，段头才能放在卡片外面，几百行也不用整张量高。
 *
 * [first] / [last] 决定这一行带不带圆角，连起来就是一张完整的卡片。
 */
@Composable
fun FpCardSegment(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val kit = LocalFpKit.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = if (first) FpCardCorner else 0.dp,
        topEnd = if (first) FpCardCorner else 0.dp,
        bottomStart = if (last) FpCardCorner else 0.dp,
        bottomEnd = if (last) FpCardCorner else 0.dp,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = kit.cardMargin)
            .clip(shape)
            .background(kit.colors().surface),
    ) {
        CompositionLocalProvider(LocalFpCardContent provides true) { content() }
    }
}

private val FpCardCorner = 16.dp
