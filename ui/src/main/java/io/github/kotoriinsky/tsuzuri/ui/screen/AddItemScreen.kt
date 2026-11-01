package io.github.kotoriinsky.tsuzuri.ui.screen

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import io.github.kotoriinsky.tsuzuri.ui.state.PinyinIndex
import io.github.kotoriinsky.tsuzuri.ui.kit.fpRowFeedback
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBubble
import io.github.kotoriinsky.tsuzuri.ui.kit.letterBarGutter
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBarMinItems
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBar
import io.github.kotoriinsky.tsuzuri.core.LetterIndex
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.kotoriinsky.tsuzuri.ui.kit.onLongPressFirst
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.ActivityPresets
import io.github.kotoriinsky.tsuzuri.core.ActivityTargets
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpErrorState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.FpLoadingState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconCorner
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconSize
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.peekAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.InstalledApp
import io.github.kotoriinsky.tsuzuri.ui.state.ShortcutCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.readShortcutCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「添加」：一个页面放进清单的**全部**来源。
 *
 * 原来是四个入口（选择应用 / 导入快捷方式 / 填链接 / 填「包名/活动类名」），最后一项要用户自己查类名。
 * 现在：搜索 → 点一个应用展开 → 列出它能放进清单的全部内容，只点选，不填字符串：
 *  - **应用本身**；
 *  - 它**发布的快捷方式**（SystemUI 枚举后交过来的目录，见 ShortcutCatalogStore）；
 *  - 常用页面预置（[ActivityPresets]，每条真机验证过）；
 *  - 它**对外开放的页面**（导出的 Activity）。借鉴 Activity Launcher 的做法，设置界面自己用
 *    `PackageManager` 读，不引入其代码；只在展开时读，两百个应用不会一起解析。
 *
 * 填链接 / 填类名两个旧入口留在页底给熟悉的人用 —— 它们是「目录里没有」时的出路，不能删。
 * 勾上 = 追加到清单末尾（不打断已有顺序），取消 = 从清单去掉；改完立刻生效（配置通道）。
 */
@Composable
fun AddItemScreen(spec: PrefSpec.OrderedList, padding: PaddingValues, onNavigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val prefs = ConfigStore.observe()
    val items = FanItems.read(prefs, spec)
    val selected = remember(items) { items.toSet() }

    var attempt by remember { mutableStateOf(0) }
    var catalog by remember { mutableStateOf<AppCatalog>(peekAppCatalog() ?: AppCatalog.Loading) }
    var pinyin by remember { mutableStateOf<PinyinIndex?>(null) }
    LaunchedEffect(context, attempt) {
        val loaded = loadAppCatalog(context)
        // 拼音键在清单发布之前算好（同 AppPickerScreen：避免先按名称序画一帧再跳）
        pinyin = if (loaded is AppCatalog.Ready) PinyinIndex.build(loaded.apps) else null
        catalog = loaded
    }
    val feedback = fpRowFeedback()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeLetter by remember { mutableStateOf<String?>(null) }

    val shortcuts = readShortcutCatalog()
    val byPackage = remember(shortcuts) {
        (shortcuts as? ShortcutCatalog.Ready)?.items.orEmpty().groupBy { it.packageName }
    }

    val iconPx = with(LocalDensity.current) { FpRowIconSize.roundToPx() }
    val icons = remember(context, iconPx) { AppIconLoader(context, iconPx) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    // 自动收起（用户 2026-10-22）：搜索词变了，或展开的应用连同子项整组滚出视野，就收起。
    // 只看「这一组还有没有任何一行在屏上」，滚动中途子项还露着一截时不收，免得手感突兀
    LaunchedEffect(query) { expanded = null }
    LaunchedEffect(expanded) {
        val pkg = expanded ?: return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { info ->
                val k = info.key as? String ?: return@any false
                k == pkg || k.startsWith("$pkg#")
            }
        }.collect { onScreen -> if (!onScreen) expanded = null }
    }

    val toggle: (String, Boolean) -> Unit = { key, on ->
        FanItems.write(spec.key, if (on) FanItems.appended(items, key) else FanItems.without(items, key))
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(8.dp)
        kit.SearchField(
            value = query,
            placeholder = fpString("app_picker.search.hint"),
            onValueChange = { query = it },
            onClear = { query = "" },
        )
        kit.VSpace(6.dp)
        // 长按看不出来，必须写明（用户 2026-10-22）
        Box(Modifier.padding(horizontal = FpSpace.m)) {
            kit.Paragraph(fpString("add_item.gestures"), FpEmphasis.MUTED, FpTextStyle.CAPTION)
        }
        kit.VSpace(4.dp)
        when (val c = catalog) {
            is AppCatalog.Loading -> FpLoadingState(hintKey = "app_picker.loading.hint", modifier = Modifier.weight(1f))
            is AppCatalog.Unavailable -> FpErrorState(
                titleKey = "app_picker.failed",
                hintKey = "app_picker.failed.hint",
                detail = c.reason,
                actionLabel = fpString("state.action.retry"),
                onAction = { attempt++ },
                modifier = Modifier.weight(1f),
            )
            is AppCatalog.Ready -> {
                val q = query.trim().lowercase()
                val sorted = pinyin?.apps ?: c.apps
                val apps = remember(sorted, q) {
                    if (q.isEmpty()) sorted else sorted.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
                }
                // 字母分组 + 右侧索引条（用户 2026-10-22：「加一个滑动条定位」），做法与 AppPickerScreen 一致
                val groups = remember(apps, pinyin) {
                    pinyin?.let { index -> LetterIndex.group(apps, { it.label }, index::letterOf) }.orEmpty()
                }
                val rows = remember(groups) { LetterIndex.rows(groups) }
                val barLetters = remember(groups) { groups.map { it.letter } }
                val showBar = barLetters.size >= 2 && apps.size >= LetterBarMinItems
                // 展开的那个应用的对外页面：展开时才读（PackageManager 解析在 IO 线程）
                val pages by produceState<List<Pair<String, String>>>(initialValue = emptyList(), expanded) {
                    val pkg = expanded
                    value = if (pkg == null) emptyList() else withContext(Dispatchers.IO) { exportedPages(context, pkg) }
                }
                val presetKeys = remember(expanded) {
                    ActivityPresets.ALL.filter { it.packageName == expanded }.map { ActivityTargets.PREFIX + it.target }.toSet()
                }
                fun androidx.compose.foundation.lazy.LazyListScope.appItems(list: List<InstalledApp>) {
                    for (app in list) {
                        val pkg = app.packageName
                        item(key = pkg) {
                            AppHeaderRow(
                                app = app,
                                selected = selected,
                                icons = icons,
                                onToggle = toggle,
                                onLongPress = { expanded = if (expanded == pkg) null else pkg },
                                modifier = Modifier.animateItem(placementSpec = ExpandSpring),
                            )
                        }
                        if (expanded != pkg) continue
                        // 子项：快捷方式 → 预置页面 → 对外页面。每项一行，逐个错开淡入下落（用户 2026-10-22：
                        // 「慢点展开，有层次感」）；下面的应用由 animateItem 的弹簧推下去，与 HyperOS 设置的列表一致
                        val children = buildList {
                            byPackage[pkg].orEmpty().forEach { add(ChildEntry(it.key, it.display, null, "add_item.kind.shortcut", null)) }
                            ActivityPresets.ALL.filter { it.packageName == pkg }.forEach {
                                add(ChildEntry(ActivityTargets.PREFIX + it.target, null, it.labelKey, "add_item.kind.page", null))
                            }
                            pages.filter { it.first !in presetKeys }.forEach { (key, label) ->
                                add(ChildEntry(key, label, null, null, key.substringAfterLast('.')))
                            }
                        }
                        children.forEachIndexed { i, child ->
                            item(key = "$pkg#${child.key}") {
                                StaggeredIn(index = i, modifier = Modifier.animateItem(placementSpec = ExpandSpring)) {
                                    Box(Modifier.padding(start = 24.dp)) {
                                        kit.CheckRow(
                                            title = child.title ?: fpString(child.titleKey!!),
                                            summary = child.summary ?: child.summaryKey?.let { fpString(it) },
                                            checked = child.key in selected,
                                            onCheckedChange = { on -> toggle(child.key, on) },
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "$pkg#divider") { kit.Divider() }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    kit.Card(Modifier.fillMaxHeight().padding(end = if (showBar) letterBarGutter() else 0.dp)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (groups.isEmpty()) {
                                appItems(apps)
                            } else {
                                // 一组 = 1 个标题行 + N 个应用行（LetterIndex.Rows 的假设）。展开的子项会让行号偏移，
                                // 所以拖索引条时先收起（见下面 onPick）
                                groups.forEach { group ->
                                    item(key = "letter:" + group.letter) { kit.SectionHeader(group.letter) }
                                    appItems(group.items)
                                }
                            }
                            item(key = "manual") {
                                kit.Divider()
                                kit.ActionRow(fpString("add_item.manual.link"), null, null, { onNavigate(Dest.LinkImport(spec)) }, navigates = true)
                                kit.Divider()
                                kit.ActionRow(fpString("add_item.manual.activity"), null, null, { onNavigate(Dest.ActivityImport(spec)) }, navigates = true)
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
                                    expanded = null
                                    rows.rowOf(letter)?.let { row -> scope.launch { listState.scrollToItem(row) } }
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
        kit.VSpace(8.dp)
    }
}

/** 展开子项的一行；标题 / 副标题要么是现成文字，要么是文案键。 */
private class ChildEntry(
    val key: String,
    val title: String?,
    val titleKey: String?,
    val summaryKey: String?,
    val summary: String?,
)

/** 子项出现与下方让位共用的弹簧：低刚度、轻阻尼，慢而不拖（接近 HyperOS 列表的展开手感）。 */
private val ExpandSpring = spring<IntOffset>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

/** 每个子项比上一个晚 [STAGGER_MS] 出现，从上方 12dp 处落下并淡入。 */
private const val STAGGER_MS = 40L

@Composable
private fun StaggeredIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // 屏外的项（index 很大）没必要排队等：最多错开 8 个
        delay(STAGGER_MS * index.coerceAtMost(8))
        progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * -12.dp.toPx()
        },
    ) { content() }
}

/**
 * 应用这一行：点一下 = 加 / 去掉应用本身（最常用，一步完成）；长按 = 展开它的快捷方式与页面
 * （用户 2026-10-22）。副标题写这个应用下另外加了几项：收起时也能看出「加过它的快捷方式」。
 */
@Composable
private fun AppHeaderRow(
    app: InstalledApp,
    selected: Set<String>,
    icons: AppIconLoader,
    onToggle: (String, Boolean) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kit = LocalFpKit.current
    val pkg = app.packageName
    val extras = selected.count { it != pkg && ownerOf(it) == pkg }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier.onLongPressFirst {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            onLongPress()
        },
    ) {
        kit.CheckRow(
            title = app.label,
            summary = if (extras > 0) fpString("add_item.added.extras", extras) else null,
            checked = pkg in selected,
            onCheckedChange = { onToggle(pkg, it) },
            leading = { RowAppIcon(icons, pkg) },
        )
    }
}

/**
 * 应用对外开放（exported、启用）的页面，按标签排序；存储形式见 [ActivityTargets.stored]。
 * 读不到（应用刚被卸载、包可见性不足）返回空 —— 只是这一组少几行，不报错。
 */
private fun exportedPages(context: Context, pkg: String): List<Pair<String, String>> = runCatching {
    val pm = context.packageManager
    val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
    info.activities.orEmpty()
        .filter { it.exported && it.enabled && ActivityTargets.check(pkg + "/" + it.name) == null }
        .map { ActivityTargets.stored(pkg, it.name) to it.loadLabel(pm).toString().ifBlank { it.name.substringAfterLast('.') } }
        .distinctBy { it.first }
        .sortedBy { it.second }
}.getOrDefault(emptyList())

/** 清单项属于哪个应用：活动项取「包名/活动名」的包名，链接项不属于任何应用（空串），其余按包名拆。 */
private fun ownerOf(key: String): String = when {
    ActivityTargets.isActivity(key) -> ActivityTargets.targetOf(key).substringBefore('/')
    else -> FanItems.packageOf(key)
}

@Composable
private fun RowAppIcon(loader: AppIconLoader, packageName: String) {
    val image by produceState<ImageBitmap?>(initialValue = null, packageName, loader) { value = loader.load(packageName) }
    FpIconPlate(image, FpRowIconSize, FpRowIconCorner)
}
