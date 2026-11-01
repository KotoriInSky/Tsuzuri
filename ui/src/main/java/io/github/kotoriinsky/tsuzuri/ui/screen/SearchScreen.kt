package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.search.SearchEntry
import io.github.kotoriinsky.tsuzuri.ui.search.SearchGroup
import io.github.kotoriinsky.tsuzuri.ui.search.rememberSearchIndex
import io.github.kotoriinsky.tsuzuri.ui.search.search
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import kotlinx.coroutines.delay

/**
 * 搜索。
 *
 * 三种状态都要画：**还没输**（引导 + 历史）、**没有结果**（说清怎么搜）、
 * **有结果**（按分组）。空态最容易被忽略，而它恰恰是用户碰壁时唯一看到的东西。
 */
@Composable
fun SearchScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val index = rememberSearchIndex()
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, index) { index.search(query) }

    val trimmed = query.trim()

    // 进页即聚焦输入框、弹出键盘（HyperOS 设置的搜索就是这样）。不聚焦时焦点落到第一个可聚焦元素
    // —— 顶栏返回键 —— 真机上表现为返回键上一块灰色方块（2026-10-21 截图）。
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

    // 记住这次搜索是**副作用**，不能写在组合里：它会写 Compose 状态，
    // 在组合期间改状态轻则重复组合、重则直接抛异常。
    // 顺便加一个迟滞：用户打「小窗」会经过「小」「小窗」两帧，
    // 不延迟的话历史里会混进一堆半截的词。
    LaunchedEffect(trimmed) {
        if (trimmed.isEmpty()) return@LaunchedEffect
        delay(700)
        AppSettings.rememberQuery(trimmed)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        kit.VSpace(8.dp)

        kit.SearchField(
            value = query,
            placeholder = fpString("search.hint"),
            onValueChange = { query = it },
            onClear = { query = "" },
            modifier = Modifier.focusRequester(searchFocus),
        )

        if (trimmed.isEmpty()) {
            // 空输入态：输入框的占位符已经说明能搜什么，这里不再复述一遍。
            // 只有历史记录值得占版面；没有历史就保持干净。
            if (AppSettings.searchHistory.isNotEmpty()) {
                kit.SectionHeader(fpString("search.history.title"))
                kit.Card {
                    AppSettings.searchHistory.forEachIndexed { i, term ->
                        if (i > 0) kit.Divider()
                        kit.ActionRow(
                            title = term,
                            summary = null,
                            trailingText = null,
                            onClick = { query = term },
                        )
                    }
                    kit.Divider()
                    kit.ActionRow(
                        title = fpString("search.history.clear"),
                        summary = null,
                        trailingText = null,
                        onClick = { AppSettings.clearHistory() },
                    )
                }
            }
            kit.VSpace(24.dp)
            return@Column
        }

        if (results.isEmpty()) {
            kit.Card {
                kit.Paragraph(
                    fpString("search.empty.title", trimmed),
                    FpEmphasis.NORMAL,
                    FpTextStyle.SUBTITLE,
                )
                kit.VSpace(4.dp)
                kit.Paragraph(fpString("search.empty.body"), FpEmphasis.MUTED, FpTextStyle.CAPTION)
            }
            kit.VSpace(24.dp)
            return@Column
        }

        for (group in SearchGroup.entries) {
            val inGroup = results.filter { it.group == group }
            if (inGroup.isEmpty()) continue
            kit.SectionHeader(fpString(group.labelI18n))
            kit.Card {
                inGroup.forEachIndexed { i, entry ->
                    if (i > 0) kit.Divider()
                    ResultRow(entry, navigate)
                }
            }
        }

        kit.VSpace(24.dp)
    }
}

@Composable
private fun ResultRow(entry: SearchEntry, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    // 搜索结果也要有图标：与功能列表用同一套 FeatureVisuals 映射，
    // 用户从列表搜到同一件事时看到的是同一个符号，不用重新认一遍。
    kit.ActionRow(
        title = entry.title,
        summary = entry.summary,
        trailingText = entry.badge,
        onClick = { navigate(entry.dest) },
        navigates = true,
        leading = { kit.IconPlate(entryIcon(entry)) },
    )
}

/** 结果行图标：能解析到特性就用特性图标，否则用放大镜（不画空白底板）。 */
private fun entryIcon(entry: SearchEntry): io.github.kotoriinsky.tsuzuri.ui.kit.FpIcon {
    val featureKey = when (val dest = entry.dest) {
        is Dest.FeatureDetail -> dest.featureKey
        is Dest.FeatureSection -> dest.featureKey
        else -> null
    }
    val spec = featureKey?.let { io.github.kotoriinsky.tsuzuri.core.Catalog.find(it) }
    return spec?.let(::featureIcon)
        ?: io.github.kotoriinsky.tsuzuri.ui.kit.FpIcon(Icons.Filled.Search, io.github.kotoriinsky.tsuzuri.ui.icon.PlateColors.Gray)
}
