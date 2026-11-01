package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import kotlinx.coroutines.delay

/** 搜索直达参数，布局完成后滚到目标；轻底色帮助认出落点。 */
@Composable
internal fun SearchTarget(selected: Boolean, content: @Composable () -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    val color = LocalFpKit.current.colors().accent
    LaunchedEffect(selected) {
        if (selected) {
            delay(250)
            requester.bringIntoView()
        }
    }
    Box(Modifier.bringIntoViewRequester(requester)
        .then(if (selected) Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp)) else Modifier)) {
        content()
    }
}
