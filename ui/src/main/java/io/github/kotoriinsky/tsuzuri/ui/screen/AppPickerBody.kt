package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.LetterIndex
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpCardSegment
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpErrorState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.FpLoadingState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconCorner
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconSize
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBar
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBarMinItems
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBubble
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.fpRowFeedback
import io.github.kotoriinsky.tsuzuri.ui.kit.letterBarGutter
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.InstalledApp
import io.github.kotoriinsky.tsuzuri.ui.state.PinyinIndex
import io.github.kotoriinsky.tsuzuri.ui.state.filterApps
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.peekAppCatalog
import kotlinx.coroutines.launch

/**
 * 应用多选的页面正文：搜索、全部 / 已选、拼音分组、字母索引。
 *
 * [AppPickerScreen]（有序清单）与 [AppListPickerScreen]（无序集合）共用这一份。两页只差
 * 「勾选写到哪」和顶部状态条，所以这里只认 [selected] 与 [onToggle]，不认识任何特性。
 *
 * ## 画法照小米自己的应用列表
 *
 * 真机「视频应用管理」「自启动管理」：搜索框写总数，一页分「N 个已添加」与其余两段，
 * 段头在卡片外，每段一张卡片，每行一个开关，没有勾选框，也没有 ⊕ ⊖。
 *
 * 分段按**进页时**的已选集合算：拨开关只改开关和段头数字，行不换位。否则一拨，
 * 这一行就从手指底下跳到另一段，连续选几个时每次都要重新找位置。
 *
 * ## 搜索
 *
 * 同时匹配**显示名与包名**：「搜 `com.tencent.mm` 应该命中微信」是排障时最有用的一条。
 * 只有搜索时才显示包名：平时行上只放图标和名称，搜索时要靠它确认命中的是哪一个。
 *
 * ## 字母索引（长清单才需要）
 *
 * 索引条在卡片外：卡片右缘左移留出固定位置（[letterBarGutter]），开关贴行尾，两者不重叠。
 *
 * 三件事必须一起做，少一件这个功能就是错的：
 *  1. **排序也按拼音**（[PinyinIndex]）—— 名称序下同一个字母分组里会混进别的字母开头的
 *     应用，索引跳过去的落点看起来是随机的；
 *  2. **行下标用 [LetterIndex.rows]**（分组标题也算一行），自己算会在第 2 个字母之后
 *     整体偏移；
 *  3. 拼音键在 **IO 线程预先算好**（每个应用名都要过 ICU），界面只查表。
 *
 * @param banners 搜索框上方的状态条，由页面决定
 * @param pickedOrder 「已选」段的顺序（有序清单传清单本身）；`null` = 按拼音
 * @param extras 列表最前面的一张卡片（「快捷方式、页面与链接」入口）
 */
@Composable
internal fun AppMultiPickBody(
    selected: Set<String>,
    onToggle: (packageName: String, checked: Boolean) -> Unit,
    padding: PaddingValues,
    pickedLabelKey: String = "app_picker.section.added",
    restLabelKey: String = "app_picker.section.rest",
    pickedOrder: List<String>? = null,
    extras: (@Composable () -> Unit)? = null,
    banners: @Composable () -> Unit,
) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    /** 索引条上换一个字母振一次（同一个字母不重复振）。 */
    val feedback = fpRowFeedback()

    var query by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<AppCatalog>(peekAppCatalog() ?: AppCatalog.Loading) }
    var pinyin by remember {
        mutableStateOf((peekAppCatalog() as? AppCatalog.Ready)?.let { PinyinIndex.peek(it.apps) })
    }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(context, attempt) {
        // 只有重试才退回加载态：有缓存时再闪一次加载，每次进页都会卡一下
        if (attempt > 0) {
            catalog = AppCatalog.Loading
            pinyin = null
        }
        val loaded = loadAppCatalog(context)
        // 拼音键**在清单发布之前**算好：反过来写的话，列表会先按名称序画一帧再跳到拼音序，
        // 用户看到的是「列表自己乱动了一下」。这两次赋值之间没有挂起点，
        // 所以界面永远不会看到「清单已就绪、拼音还没算好」的中间态。
        pinyin = if (loaded is AppCatalog.Ready) PinyinIndex.build(loaded.apps) else null
        catalog = loaded
    }

    val iconPx = with(LocalDensity.current) { FpRowIconSize.roundToPx() }
    val icons = remember(context, iconPx) { AppIconLoader(context, iconPx) }

    val all = (catalog as? AppCatalog.Ready)?.apps ?: emptyList()
    // 真取不到拼音索引就退回名称序 —— 宁可顺序不对，也不能画一个空列表
    val sorted = pinyin?.apps ?: all
    val visible = remember(sorted, query) { filterApps(sorted, query, false, emptySet()) }
    /** 进页时的已选集合，只用来分段（见类注释）。 */
    val addedAtOpen = remember(catalog is AppCatalog.Ready) { selected }
    val added = remember(visible, addedAtOpen, pickedOrder) {
        val picked = visible.filter { it.packageName in addedAtOpen }
        if (pickedOrder == null) picked else picked.sortedBy { pickedOrder.indexOf(it.packageName) }
    }
    val rest = remember(visible, addedAtOpen) { visible.filterNot { it.packageName in addedAtOpen } }
    // 分组用的是与排序**同一份快照**：两者对不上就会出现「点 C 跳到 B」
    val groups = remember(rest, pinyin) {
        pinyin?.let { index -> LetterIndex.group(rest, { it.label }, index::letterOf) }.orEmpty()
    }
    val rows = remember(groups) { LetterIndex.rows(groups) }
    /** 字母分组前面还有几行（入口卡片；「已添加」段头 + 其项 +「未添加」段头，或顶部留白）。索引跳转要加上它。 */
    val leadingRows = (if (extras != null) 1 else 0) + (if (added.isEmpty()) 1 else added.size + 2)
    val showPackage = query.isNotBlank()
    val barLetters = remember(groups) { groups.map { it.letter } }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    /** 手指在索引条上压着的字母；null = 没在拖。气泡的显示也靠它。 */
    var activeLetter by remember { mutableStateOf<String?>(null) }

    // 条只在**真的需要**时出现：清单短的时候它只是噪音；
    // 只有一个分组时更没意义 —— 按哪个字母都跳到同一处
    val showBar = barLetters.size >= 2 && rest.size >= LetterBarMinItems
    val gutter = if (showBar) letterBarGutter() else 0.dp

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(FpSpace.s)
        banners()
        kit.VSpace(FpSpace.s)

        kit.SearchField(
            value = query,
            placeholder = if (all.isEmpty()) fpString("app_picker.search.hint") else fpString("app_picker.search.count", all.size),
            onValueChange = { query = it },
            onClear = { query = "" },
        )
        kit.VSpace(FpSpace.s)

        when (val c = catalog) {
            is AppCatalog.Loading -> FpLoadingState(
                hintKey = "app_picker.loading.hint",
                modifier = Modifier.weight(1f),
            )

            is AppCatalog.Unavailable -> FpErrorState(
                titleKey = "app_picker.failed",
                hintKey = "app_picker.failed.hint",
                detail = c.reason,
                actionLabel = fpString("state.action.retry"),
                onAction = { attempt++ },
                modifier = Modifier.weight(1f),
            )

            is AppCatalog.Ready -> if (pinyin == null) {
                // 拼音还没算好时不画列表：先平铺再跳成分组，看起来就是卡了一下
                FpLoadingState(
                    hintKey = "app_picker.loading.hint",
                    modifier = Modifier.weight(1f),
                )
            } else if (visible.isEmpty()) {
                FpEmptyState(
                    titleKey = "app_picker.empty",
                    hintKey = "app_picker.empty.hint",
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(end = gutter, bottom = FpSpace.xl),
                    ) {
                        if (extras != null) {
                            item(key = "extras") {
                                Column {
                                    kit.VSpace(FpSpace.l)
                                    extras()
                                }
                            }
                        }
                        if (added.isNotEmpty()) {
                            item(key = "section:added") {
                                kit.SectionHeader(fpString(pickedLabelKey, selected.size))
                            }
                            itemsIndexed(added, key = { _, app -> app.packageName }) { i, app ->
                                FpCardSegment(first = i == 0, last = i == added.lastIndex) {
                                    AppSwitchRow(app, app.packageName in selected, showPackage, icons, onToggle)
                                }
                            }
                            item(key = "section:rest") {
                                kit.SectionHeader(fpString(restLabelKey))
                            }
                        } else {
                            item(key = "top-space") { kit.VSpace(FpSpace.l) }
                        }
                        if (groups.isEmpty()) {
                            // 拼音索引还没算好的那一瞬间：平铺、没有分组标题、不出索引条，绝不留白
                            itemsIndexed(rest, key = { _, app -> app.packageName }) { i, app ->
                                FpCardSegment(first = i == 0, last = i == rest.lastIndex) {
                                    AppSwitchRow(app, app.packageName in selected, showPackage, icons, onToggle)
                                }
                            }
                        } else {
                            // ⚠️ 这里的行序必须与 LetterIndex.Rows.headerAt 的假设一致：
                            // 一组 = 1 个标题行 + N 个项行。把标题放到项后面、或者少画标题，
                            // 跳转就会整体偏移，而且十几项以内根本看不出来
                            groups.forEachIndexed { g, group ->
                                item(key = "letter:" + group.letter) {
                                    FpCardSegment(first = g == 0, last = false) {
                                        LetterLabel(group.letter)
                                    }
                                }
                                itemsIndexed(group.items, key = { _, app -> app.packageName }) { i, app ->
                                    FpCardSegment(first = false, last = g == groups.lastIndex && i == group.items.lastIndex) {
                                        AppSwitchRow(app, app.packageName in selected, showPackage, icons, onToggle)
                                    }
                                }
                            }
                        }
                    }

                    if (showBar) {
                        LetterBar(
                            letters = barLetters,
                            activeLetter = activeLetter,
                            // 条捕获这个 lambda 之后不一定每次组合都换新的（pointerInput 的
                            // block 只在 key 变时才重启），所以这里读的 activeLetter 必须是
                            // **动态**的：它是 MutableState，取值总在调用这一刻 —— 不能换成
                            // 「组合时算好的普通变量」，那会读到过期的字母
                            onPick = { letter ->
                                if (activeLetter != letter) {
                                    activeLetter = letter
                                    feedback()
                                    // 拖动中用 scrollToItem（瞬间到位）而不是动画：
                                    // 动画会排队，划快了列表就滞后于手指
                                    rows.rowOf(letter)?.let { row ->
                                        scope.launch { listState.scrollToItem(leadingRows + row) }
                                    }
                                }
                            },
                            onRelease = { activeLetter = null },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }

                    // 手指压住的那一格被指尖挡住了 —— 气泡是拖动过程中唯一的反馈
                    activeLetter?.let { LetterBubble(it, Modifier.align(Alignment.Center)) }
                }
            }
        }
    }
}

/** 字母分组的标题。在卡片里，比段头轻一级：段头有更多留白，两者叠着会把列表切得太碎。 */
@Composable
private fun LetterLabel(letter: String) {
    Box(Modifier.padding(start = FpSpace.l, end = FpSpace.l, top = FpSpace.m, bottom = 2.dp)) {
        LocalFpKit.current.Body(letter, FpEmphasis.MUTED, FpTextStyle.CAPTION)
    }
}

/** 一行。抽出来是因为它出现在已添加段、字母分组、拼音索引还没算好时的平铺三处。 */
@Composable
private fun AppSwitchRow(
    app: InstalledApp,
    checked: Boolean,
    showPackage: Boolean,
    loader: AppIconLoader,
    onToggle: (String, Boolean) -> Unit,
) {
    LocalFpKit.current.SwitchRow(
        title = app.label,
        summary = app.packageName.takeIf { showPackage },
        checked = checked,
        onCheckedChange = { on -> onToggle(app.packageName, on) },
        leading = { AppIcon(loader, app.packageName) },
    )
}

/** 行首图标。画法统一收在 [FpIconPlate]（全项目唯一一份，见它的 KDoc）。 */
@Composable
private fun AppIcon(loader: AppIconLoader, packageName: String) {
    var image by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, loader) { image = loader.load(packageName) }
    FpIconPlate(image, FpRowIconSize, FpRowIconCorner)
}
