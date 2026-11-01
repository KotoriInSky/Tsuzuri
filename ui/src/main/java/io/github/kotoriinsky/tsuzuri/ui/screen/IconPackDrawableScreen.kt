package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.LetterIndex
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.FpLoadingState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconCorner
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconSize
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBar
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBarMinItems
import io.github.kotoriinsky.tsuzuri.ui.kit.letterBarGutter
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBubble
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.fpRowFeedback
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.IconOverridesStore
import io.github.kotoriinsky.tsuzuri.ui.state.PackIconPool
import io.github.kotoriinsky.tsuzuri.ui.state.IconPackPreviewLoader
import io.github.kotoriinsky.tsuzuri.ui.state.InstalledApp
import io.github.kotoriinsky.tsuzuri.ui.state.PackIconOption
import io.github.kotoriinsky.tsuzuri.ui.state.PinyinIndex
import io.github.kotoriinsky.tsuzuri.ui.state.iconPackKey
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.loadPackIconPool
import io.github.kotoriinsky.tsuzuri.ui.state.looksLikeDrawableName
import io.github.kotoriinsky.tsuzuri.ui.state.optionMatches
import kotlinx.coroutines.launch

/** 段标题的行 key（与字母标题/行的 key 必须互不相同，重复 key 会让 `LazyColumn` 直接崩）。 */
private const val KEY_TITLE_MATCHED = "sec-matched"
private const val KEY_TITLE_OTHERS = "sec-others"

/**
 * 从**图标包里挑一张图**，贴给选中的那一项（单项覆盖，优先级高于整体图标包）。
 *
 * ## 候选分两段
 *
 * ```
 * ① 匹配到的   appfilter 命中这个包对**已装应用**的映射   ← 默认视野，通常就是要找的那张
 * ② 包内其他   包里没被映射到已装应用的图标（含形状素材）  ← 「借一张别的图」才往下翻
 * ```
 *
 * 分段的理由：包内 drawable 可能上千个。混在一列里，用户每次都要从头找「它本来给微信
 * 画的那张」；分开之后常态路径第一屏就能点到，扩展用法才需要往下翻或去搜。
 *
 * ## 搜索 + 手输图标名
 *
 * 搜索同时匹配**应用名 / 包名 / drawable 名**（与 `AppPickerScreen` 同一套写法：同一个
 * 搜索框、同一份字母索引与气泡，两页的手感不能不一样）。
 *
 * **候选里一个都没命中**时，把输入当成 drawable 名去包里查一次：查得到就把这张图并进
 * 第 ② 段（点它就换上），查不到就明说「包里没有」—— 这同时覆盖了「我知道那张图叫什么」
 * 与「我想试试」两种用法，也是「候选没枚举全」的补救（见 `loadPackIconPool` 的注释）。
 *
 * ## 写进去的是什么
 *
 * `包名/drawable 名`（**不是**「这一项的应用」），所以：
 *  - 用户换整体图标包时，单项覆盖照样生效（它指向的是明确的那个包）；
 *  - 包被卸载时那一项静默回退到下一层（hook 侧的解析链保证）。
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
    /** 索引条上换一个字母振一次（同一个字母不重复振）。 */
    val feedback = fpRowFeedback()

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val packName = prefs.rawStringSet(iconPackKey(featureKey)).sorted().firstOrNull()

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var picked by remember(packName) { mutableStateOf<PickState?>(null) }
    var query by remember(packName) { mutableStateOf("") }
    // 手输名的查证状态。随 query 一起重置，所以「上一句话的结论」不会留在界面上
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
        // 应用清单读不到时（`AppCatalog.Unavailable`）第 ① 段会是空的，但第 ② 段照常能翻 ——
        // 这是刻意的：宁可少一段，也不要卡在加载态什么都看不到。
        val pool = loadPackIconPool(context, packName, apps)
        // 字母表与候选池**一起发布**：先发布池、再算字母的话，列表会先按「无分组」画一帧
        // 再跳成分组（用户看到列表自己动了一下）。这两次赋值之间没有挂起点。
        //
        // 第 ② 段的项不是应用，但字母槽位必须与第 ① 段（以及应用多选页）用**同一套取法**，
        // 否则「微信」在这里归 #、在多选页归 W。PinyinIndex 只按 label 取首字母，
        // 所以把这些 drawable 名包成 InstalledApp 交给它（packageName 在这里没有意义）。
        val letters = PinyinIndex.build(
            apps + pool.others.map { InstalledApp(it.drawableName, it.drawableName) },
        )
        picked = PickState(pool, letters)
    }

    val iconPx = with(LocalDensity.current) { FpRowIconSize.roundToPx() }
    val loader = remember(packName, iconPx) {
        if (packName == null) null else IconPackPreviewLoader(context, iconPx)
    }
    // 当前这一项已经有的是哪张图（用来把「已选中」标出来）：用户来回试的时候
    // 必须看得出当前生效的是哪一个
    val current = IconOverridesStore.read(prefs, featureKey)[targetKey]

    val state = picked
    val q = query.trim()

    // ── 手输名：只在「候选里一个都没命中」时去包里查一次 ───────────────────
    // 为什么要有这个条件：用户在打字过程中每敲一个字都会经过「没有命中」这一帧，
    // 无条件去查包会让每一帧都弹一次「包里没有」。
    //
    // ⚠️ `manual` **不能**当这个 effect 的 key：它自己会写 manual，写进 key 就等于
    // 「一进入『正在查』就取消掉自己的协程」——查证永远出不来结果，而且不报任何错。
    // key 只描述「要查什么」（包 + 输入 + 候选池），查出来的结论不参与重启判断。
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
        // 不像资源名的输入是**搜索词**（汉字、空格、符号），不去查包 ——
        // 否则打「微信」两个字会被回一句「包里没有「微信」」
        if (!looksLikeDrawableName(q)) {
            manual = ManualState.Idle
            return@LaunchedEffect
        }
        manual = ManualState.Checking
        // 查证的结果**只用来说状态**：查到的时候不专门画一行「可以用这张」，
        // 而是把这张图并进第 ② 段（用户点它 = 换上），少一个只在特定时刻出现的控件。
        manual = if (l.loadByName(packName, q) == null) ManualState.Missing else ManualState.Found
    }

    // ── 两段的数据（第 ② 段会把查证过的手输名并进来）──────────────────────
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

    // 字母分组：与 AppPickerScreen 一样用 LetterIndex（分组标题也算一行）
    val letters = state?.letters
    val groupsMatched = remember(visMatched, letters) {
        letters?.let { ix ->
            LetterIndex.group(visMatched, { it.from?.label ?: it.drawableName }, ix::letterOf)
        }.orEmpty()
    }
    val groupsOthers = remember(visOthers, letters) {
        letters?.let { ix -> LetterIndex.group(visOthers, { it.drawableName }, ix::letterOf) }.orEmpty()
    }
    val rowsMatched = remember(groupsMatched) { LetterIndex.rows(groupsMatched) }
    val rowsOthers = remember(groupsOthers) { LetterIndex.rows(groupsOthers) }
    val barLetters = remember(groupsMatched, groupsOthers) {
        // 字母格按 ALPHABET 的顺序（分组本身也是这个顺序），两段同字母只留一格
        LetterIndex.ALPHABET.filter { letter ->
            groupsMatched.any { it.letter == letter } || groupsOthers.any { it.letter == letter }
        }
    }

    // 行下标：**段标题也算一行**，漏掉它跳转就会整体偏一行（十几项时看不出来）。
    // 两个 offset 的算法必须与下面 LazyColumn 里画标题的条件（groups 非空）逐字一致。
    val offsetMatched = if (groupsMatched.isEmpty()) 0 else 1
    val offsetOthers = offsetMatched + rowsMatched.count + if (groupsOthers.isEmpty()) 0 else 1

    /**
     * 字母 → 行下标。
     *
     * 同一个字母在两段都有时跳**第 ① 段**那一组：那是用户常态要的那一组
     * （第 ② 段同字母的项靠滚动或搜索到达，条上只留一个字母格 —— 一个字母画两格反而
     * 会让「按住哪一格就跳到哪一组」这件事变得不确定）。
     */
    val rowIndexOf: (String) -> Int? = { letter ->
        rowsMatched.rowOf(letter)?.plus(offsetMatched)
            ?: rowsOthers.rowOf(letter)?.plus(offsetOthers)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    /** 手指在索引条上压着的字母；null = 没在拖。气泡的显示也靠它。 */
    var activeLetter by remember { mutableStateOf<String?>(null) }

    val showBar = barLetters.size >= 2 && (visMatched.size + visOthers.size) >= LetterBarMinItems

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(8.dp)
        kit.Banner(fpString("icon_override.pick_for", targetLabel), FpEmphasis.ACCENT)
        kit.VSpace(4.dp)
        kit.Paragraph(fpString("icon_override.pick_hint"), FpEmphasis.MUTED)
        kit.VSpace(6.dp)

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

                // 固定的一行状态：可借多少张 / 筛选命中多少 / 手输名查到哪一步。
                // 它**一直在**（不留空），所以打字时下面的列表不会上下跳。
                val status = when {
                    manual is ManualState.Checking -> fpString("icon_pack.pick.manual.checking")
                    manual is ManualState.Missing -> fpString("icon_pack.pick.manual.missing", q)
                    q.isEmpty() -> fpString("icon_pack.pick.status.pool", state.pool.total)
                    else -> fpString("icon_pack.pick.status.match", visMatched.size + visOthers.size)
                }
                kit.Paragraph(status, FpEmphasis.MUTED, FpTextStyle.CAPTION)
                kit.VSpace(6.dp)

                if (visMatched.isEmpty() && visOthers.isEmpty()) {
                    // 两段都空 = 搜索没命中（或手输的名字包里没有，状态行已经说了）
                    FpEmptyState(
                        titleKey = "icon_pack.pick.no_match",
                        hintKey = "icon_pack.pick.no_match.hint",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // 索引条放在卡片外（同 AppMultiPickBody）：卡片右缘左移，条不盖住行尾的控件
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        kit.Card(Modifier.fillMaxHeight().padding(end = if (showBar) letterBarGutter() else 0.dp)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
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
                                        items(group.items, key = { "row-m:" + it.drawableName }) { option ->
                                            DrawableRow(
                                                option = option,
                                                packName = packName,
                                                featureKey = featureKey,
                                                targetKey = targetKey,
                                                current = current,
                                                loader = loader,
                                            )
                                        }
                                    }
                                }
                                // 第 ② 段为空时**不画**段标题：一个空的段标题会让人以为
                                // 下面还有东西（只是没加载出来）
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
                                        items(group.items, key = { "row-o:" + it.drawableName }) { option ->
                                            DrawableRow(
                                                option = option,
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
                                // `onPick` 里读的 activeLetter 必须是动态的（见 AppPickerScreen
                                // 同一处的注释：pointerInput 的 block 不会每次组合都换新的）
                                onPick = { letter ->
                                    if (activeLetter != letter) {
                                        activeLetter = letter
                                        feedback()
                                        // 拖动中用 scrollToItem（瞬间到位）而不是动画：
                                        // 动画会排队，划快了列表就滞后于手指
                                        rowIndexOf(letter)?.let { row ->
                                            scope.launch { listState.scrollToItem(row) }
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
        kit.VSpace(16.dp)
    }
}

/**
 * 候选池 + 字母表。**一起发布**（见调用处那段注释：分两次发布会让列表自己动一下）。
 *
 * @param letters 显示名 → 字母槽位。第 ① 段查应用名、第 ② 段查 drawable 名，同一份表。
 */
private class PickState(val pool: PackIconPool, val letters: PinyinIndex)

/** 手输 drawable 名的查证状态（「候选里没命中」时才会走到这里）。 */
private sealed interface ManualState {
    data object Idle : ManualState

    data object Checking : ManualState

    /** 存在：这张图会被并进第 ② 段，用户在那里点它。 */
    data object Found : ManualState

    /** 包里没有这个名字（或它根本不是一张 drawable）：必须说出来，不能静默。 */
    data object Missing : ManualState
}

/**
 * 候选的一行。
 *
 * 两段共用同一个 composable：有归属应用（第 ① 段）时标题是**应用名**、副标题是 drawable 名；
 * 第 ② 段没有归属，标题只能是 drawable 名。分成两个 composable 的话，
 * 「点一下写什么」「选中态怎么判」这类决定迟早只在其中一处被改，而两边都编译得过。
 */
@Composable
private fun DrawableRow(
    option: PackIconOption,
    packName: String,
    featureKey: String,
    targetKey: String,
    current: IconOverridesStore.Entry?,
    loader: IconPackPreviewLoader?,
) {
    var image by remember(packName, option.drawableName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packName, option.drawableName, loader) {
        image = loader?.loadByName(packName, option.drawableName)
    }

    val value = "$packName/${option.drawableName}"
    val selected = current?.kind == IconOverridesStore.KIND_PACK && current.value == value
    LocalFpKit.current.CheckRow(
        title = option.from?.label ?: option.drawableName,
        // 第 ① 段的副标题是 drawable 名：用户认路靠应用名，但写进配置的是这个名字
        summary = option.from?.let { option.drawableName },
        checked = selected,
        onCheckedChange = {
            IconOverridesStore.put(
                featureKey,
                targetKey,
                IconOverridesStore.Entry(IconOverridesStore.KIND_PACK, value),
            )
        },
        // 底板只在图还没取到时画（FpIconPlate 的规则）：这一页的图大多自带透明区域，
        // 有底板就会露出一圈浅色方块
        leading = { FpIconPlate(image, FpRowIconSize, FpRowIconCorner) },
    )
}
