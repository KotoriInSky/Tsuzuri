package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kotoriinsky.tsuzuri.core.LetterIndex
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.FpLoadingState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBar
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBarMinItems
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBubble
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.fpRowFeedback
import io.github.kotoriinsky.tsuzuri.ui.kit.letterBarGutter
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.IconOverridesStore
import io.github.kotoriinsky.tsuzuri.ui.state.IconPackPreviewLoader
import io.github.kotoriinsky.tsuzuri.ui.state.InstalledApp
import io.github.kotoriinsky.tsuzuri.ui.state.PackIconOption
import io.github.kotoriinsky.tsuzuri.ui.state.PackIconPool
import io.github.kotoriinsky.tsuzuri.ui.state.PinyinIndex
import io.github.kotoriinsky.tsuzuri.ui.state.iconPackKey
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.loadPackIconPool
import io.github.kotoriinsky.tsuzuri.ui.state.looksLikeDrawableName
import io.github.kotoriinsky.tsuzuri.ui.state.optionMatches
import kotlinx.coroutines.launch

private const val KEY_TITLE_MATCHED = "sec-matched"
private const val KEY_TITLE_OTHERS = "sec-others"
private const val DRAWABLE_GRID_COLUMNS = 4

/**
 * 从图标包挑一张图（4 列图标网格 + 拼音字母索引 + 搜索/手输 drawable 名）：
 * - 顶栏副标题已标明目标项，正文去掉重复横幅；
 * - 每行 4 格在 LazyColumn 内按块复用，一屏可快速扫览 24–28 个图标，且字母条精准跳转。
 */
@Composable
fun IconPackDrawableScreen(
    spec: PrefSpec.OrderedList,
    targetKey: String,
    targetLabel: String,
    padding: PaddingValues,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val context = LocalContext.current
    val feedback = fpRowFeedback()

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val packName = prefs.rawStringSet(iconPackKey(featureKey)).sorted().firstOrNull()

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var picked by remember(packName) { mutableStateOf<PickState?>(null) }
    var query by remember(packName) { mutableStateOf("") }
    var manual by remember(packName, query) { mutableStateOf<ManualState>(ManualState.Idle) }

    LaunchedEffect(context) {
        apps = when (val c = loadAppCatalog(context)) {
            is AppCatalog.Ready -> c.apps
            else -> emptyList()
        }
    }
    LaunchedEffect(context, packName, apps) {
        if (packName == null) return@LaunchedEffect
        picked = null
        val pool = loadPackIconPool(context, packName, apps)
        val letters = PinyinIndex.build(
            apps + pool.others.map { InstalledApp(it.drawableName, it.drawableName) },
        )
        picked = PickState(pool, letters)
    }

    val iconPx = with(LocalDensity.current) { DrawableTileIconSize.roundToPx() }
    val loader = remember(packName, iconPx) {
        if (packName == null) null else IconPackPreviewLoader(context, iconPx)
    }
    val current = IconOverridesStore.read(prefs, featureKey)[targetKey]

    val state = picked
    val q = query.trim()

    LaunchedEffect(packName, q, state, loader) {
        val pool = state?.pool
        val l = loader
        if (packName == null || pool == null || l == null || q.isEmpty()) {
            manual = ManualState.Idle
            return@LaunchedEffect
        }
        if (pool.matched.any { optionMatches(it, q) } || pool.others.any { optionMatches(it, q) }) {
            manual = ManualState.Idle
            return@LaunchedEffect
        }
        if (!looksLikeDrawableName(q)) {
            manual = ManualState.Idle
            return@LaunchedEffect
        }
        manual = ManualState.Checking
        manual = if (l.loadByName(packName, q) == null) ManualState.Missing else ManualState.Found
    }

    val injected = if (manual is ManualState.Found) PackIconOption(q, null) else null
    val matchedAll = state?.pool?.matched.orEmpty()
    val othersAll = remember(state?.pool?.others, injected) {
        val base = state?.pool?.others.orEmpty()
        if (injected == null) {
            base
        } else {
            (base + injected)
                .distinctBy { it.drawableName }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.drawableName })
        }
    }
    val visMatched = remember(matchedAll, q) { matchedAll.filter { optionMatches(it, q) } }
    val visOthers = remember(othersAll, q) { othersAll.filter { optionMatches(it, q) } }

    val letters = state?.letters
    val groupsMatched = remember(visMatched, letters) {
        letters?.let { ix ->
            LetterIndex.group(visMatched, { it.from?.label ?: it.drawableName }, ix::letterOf)
        }.orEmpty()
    }
    val groupsOthers = remember(visOthers, letters) {
        letters?.let { ix -> LetterIndex.group(visOthers, { it.drawableName }, ix::letterOf) }.orEmpty()
    }

    // 计算按 4 列分块后的精确 LazyColumn 行下标，保证字母索引条 100% 精准落位
    val matchedIndexMap = remember(groupsMatched) { chunkedRowIndexMap(groupsMatched, DRAWABLE_GRID_COLUMNS) }
    val othersIndexMap = remember(groupsOthers) { chunkedRowIndexMap(groupsOthers, DRAWABLE_GRID_COLUMNS) }
    val barLetters = remember(groupsMatched, groupsOthers) {
        LetterIndex.ALPHABET.filter { letter ->
            letter in matchedIndexMap.letterRow || letter in othersIndexMap.letterRow
        }
    }

    val offsetMatched = if (groupsMatched.isEmpty()) 0 else 1
    val offsetOthers = offsetMatched + matchedIndexMap.totalRows + if (groupsOthers.isEmpty()) 0 else 1

    val rowIndexOf: (String) -> Int? = { letter ->
        matchedIndexMap.letterRow[letter]?.plus(offsetMatched)
            ?: othersIndexMap.letterRow[letter]?.plus(offsetOthers)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeLetter by remember { mutableStateOf<String?>(null) }

    val showBar = barLetters.size >= 2 && (visMatched.size + visOthers.size) >= LetterBarMinItems

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(FpSpace.s)

        when {
            packName == null -> FpEmptyState(
                titleKey = "icon_override.pick.no_pack",
                hintKey = "icon_override.pick.no_pack.hint",
                modifier = Modifier.weight(1f),
            )

            state == null -> FpLoadingState(
                hintKey = "icon_override.pick.loading",
                modifier = Modifier.weight(1f),
            )

            state.pool.total == 0 -> FpEmptyState(
                titleKey = "icon_override.pick.empty",
                hintKey = "icon_override.pick.empty.hint",
                modifier = Modifier.weight(1f),
            )

            else -> {
                kit.SearchField(
                    value = query,
                    placeholder = fpString("icon_pack.pick.search.hint"),
                    onValueChange = { query = it },
                    onClear = { query = "" },
                )
                kit.VSpace(6.dp)

                val status = when {
                    manual is ManualState.Checking -> fpString("icon_pack.pick.manual.checking")
                    manual is ManualState.Missing -> fpString("icon_pack.pick.manual.missing", q)
                    q.isEmpty() -> fpString("icon_pack.pick.status.pool", state.pool.total)
                    else -> fpString("icon_pack.pick.status.match", visMatched.size + visOthers.size)
                }
                kit.Paragraph(status, FpEmphasis.MUTED, FpTextStyle.CAPTION)
                kit.VSpace(6.dp)

                if (visMatched.isEmpty() && visOthers.isEmpty()) {
                    FpEmptyState(
                        titleKey = "icon_pack.pick.no_match",
                        hintKey = "icon_pack.pick.no_match.hint",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        kit.Card(Modifier.fillMaxHeight().padding(end = if (showBar) letterBarGutter() else 0.dp)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 12.dp),
                            ) {
                                if (groupsMatched.isNotEmpty()) {
                                    item(key = KEY_TITLE_MATCHED) {
                                        kit.SectionHeader(
                                            fpString("icon_pack.pick.section.matched", visMatched.size),
                                        )
                                    }
                                    groupsMatched.forEach { group ->
                                        item(key = "letter-m:" + group.letter) {
                                            kit.SectionHeader(group.letter)
                                        }
                                        val chunked = group.items.chunked(DRAWABLE_GRID_COLUMNS)
                                        itemsIndexed(
                                            chunked,
                                            key = { rowIdx, _ -> "grid-m:${group.letter}:$rowIdx" },
                                        ) { _, rowItems ->
                                            DrawableGridRow(
                                                items = rowItems,
                                                packName = packName,
                                                featureKey = featureKey,
                                                targetKey = targetKey,
                                                current = current,
                                                loader = loader,
                                            )
                                        }
                                    }
                                }
                                if (groupsOthers.isNotEmpty()) {
                                    item(key = KEY_TITLE_OTHERS) {
                                        kit.SectionHeader(
                                            fpString("icon_pack.pick.section.other", visOthers.size),
                                        )
                                    }
                                    groupsOthers.forEach { group ->
                                        item(key = "letter-o:" + group.letter) {
                                            kit.SectionHeader(group.letter)
                                        }
                                        val chunked = group.items.chunked(DRAWABLE_GRID_COLUMNS)
                                        itemsIndexed(
                                            chunked,
                                            key = { rowIdx, _ -> "grid-o:${group.letter}:$rowIdx" },
                                        ) { _, rowItems ->
                                            DrawableGridRow(
                                                items = rowItems,
                                                packName = packName,
                                                featureKey = featureKey,
                                                targetKey = targetKey,
                                                current = current,
                                                loader = loader,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (showBar) {
                            LetterBar(
                                letters = barLetters,
                                activeLetter = activeLetter,
                                onPick = { letter ->
                                    if (activeLetter != letter) {
                                        activeLetter = letter
                                        feedback()
                                        rowIndexOf(letter)?.let { row ->
                                            scope.launch { listState.scrollToItem(row) }
                                        }
                                    }
                                },
                                onRelease = { activeLetter = null },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                        activeLetter?.let { LetterBubble(it, Modifier.align(Alignment.Center)) }
                    }
                }
            }
        }
        kit.VSpace(FpSpace.l)
    }
}

private class ChunkedRowMap(val letterRow: Map<String, Int>, val totalRows: Int)

private fun chunkedRowIndexMap(
    groups: List<LetterIndex.Group<PackIconOption>>,
    columns: Int,
): ChunkedRowMap {
    val map = LinkedHashMap<String, Int>(groups.size)
    var row = 0
    for (g in groups) {
        map[g.letter] = row
        row += 1 + (g.items.size + columns - 1) / columns
    }
    return ChunkedRowMap(map, row)
}

@Composable
private fun DrawableGridRow(
    items: List<PackIconOption>,
    packName: String,
    featureKey: String,
    targetKey: String,
    current: IconOverridesStore.Entry?,
    loader: IconPackPreviewLoader?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (col in 0 until DRAWABLE_GRID_COLUMNS) {
            val option = items.getOrNull(col)
            if (option == null) {
                Spacer(Modifier.weight(1f))
            } else {
                DrawableGridTile(
                    option = option,
                    packName = packName,
                    featureKey = featureKey,
                    targetKey = targetKey,
                    current = current,
                    loader = loader,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DrawableGridTile(
    option: PackIconOption,
    packName: String,
    featureKey: String,
    targetKey: String,
    current: IconOverridesStore.Entry?,
    loader: IconPackPreviewLoader?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFpKit.current.colors()
    val feedback = fpRowFeedback()
    var image by remember(packName, option.drawableName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packName, option.drawableName, loader) {
        image = loader?.loadByName(packName, option.drawableName)
    }

    val value = "$packName/${option.drawableName}"
    val selected = current?.kind == IconOverridesStore.KIND_PACK && current.value == value

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) Modifier.background(colors.surfaceVariant) else Modifier,
            )
            .clickable {
                feedback()
                IconOverridesStore.put(
                    featureKey,
                    targetKey,
                    IconOverridesStore.Entry(IconOverridesStore.KIND_PACK, value),
                )
            }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            FpIconPlate(image, DrawableTileIconSize, DrawableTileIconRadius)
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .size(18.dp)
                        .background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = colors.onAccent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = option.from?.label ?: option.drawableName,
            color = if (selected) colors.accent else colors.onSurface,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private class PickState(val pool: PackIconPool, val letters: PinyinIndex)

private sealed interface ManualState {
    data object Idle : ManualState
    data object Checking : ManualState
    data object Found : ManualState
    data object Missing : ManualState
}

private val DrawableTileIconSize = 44.dp
private val DrawableTileIconRadius = 11.dp
