package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.ImageBitmap
import io.github.kotoriinsky.tsuzuri.core.IndexAppFilter
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.SideIndexCustom
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import kotlinx.coroutines.launch
import io.github.kotoriinsky.tsuzuri.ui.state.PreviewIcons
import io.github.kotoriinsky.tsuzuri.ui.state.PreviewWallpaper
import io.github.kotoriinsky.tsuzuri.ui.state.rememberScreenShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.Catalog
import androidx.compose.foundation.layout.Box
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.kotoriinsky.tsuzuri.core.LetterIndex
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.SideIndexPreview
import io.github.kotoriinsky.tsuzuri.core.SideIndexPreviewParams
import io.github.kotoriinsky.tsuzuri.core.SideIndexPreviewScene
import io.github.kotoriinsky.tsuzuri.core.sideIndexPreview
import io.github.kotoriinsky.tsuzuri.ui.kit.SideIndexPreviewCanvas
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.isListedInSettings
import io.github.kotoriinsky.tsuzuri.core.isVisible
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.RestartPolicy
import io.github.kotoriinsky.tsuzuri.core.OrderedItems
import io.github.kotoriinsky.tsuzuri.core.changedCount
import io.github.kotoriinsky.tsuzuri.core.isUserToggleable
import io.github.kotoriinsky.tsuzuri.core.prefCategories
import io.github.kotoriinsky.tsuzuri.core.prefGroups
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore

/**
 * 详情页**内联展开**参数的项数上限。
 *
 * 超过它就必须收进分组二级页，哪怕这个特性只有一个分组。
 * 取 8 与「一组约 8 项」同源 —— 同一个可读性判据，
 * 只是这里用在「要不要二级页」上。
 */
private const val INLINE_PREF_LIMIT = 8

/**
 * 有多个分组、但总数仍在一屏内时，**内联成「分组标题 + 卡片」**，不再多一层二级页。
 * 识屏三类（2026-10-25）用分组标题写明作用于传送门还是超级小爱 —— 那是二级分类，
 * 不是需要点进去才看得到的东西。
 */
private const val INLINE_GROUPED_LIMIT = 12

/**
 * 特性详情。
 *
 * 这一页要用「这个特性到底动哪里」讲清楚，而不是重复列表页的摘要 ——
 * 用户在详情页就是想确认「开了会不会出问题」。所以作用域、生效方式、
 * 设计文档位置都要摆出来。
 *
 * `onNavigate` 是必需的：有些参数（应用多选清单）不可能在行内配完，
 * 它们的控件在行里只显示当前状态，点进去才是真正的编辑界面。
 */
/**
 * 入口行的副标题：这一页里能调什么。
 * 多个分组时列分组名，只有一个分组时列前几个参数名；最多 3 个，多了加「等」。
 */
@Composable
private fun contentsSummary(groups: List<io.github.kotoriinsky.tsuzuri.core.PrefGroup>): String? {
    val names = if (groups.size > 1) groups.map { fpString(it.labelKey) }
    else groups.firstOrNull()?.prefs.orEmpty().map { fpString(it.labelKey) }
    if (names.isEmpty()) return null
    val shown = names.distinct().take(3).joinToString("、")
    return if (names.distinct().size > 3) shown + "等" else shown
}

@Composable
fun FeatureDetailScreen(featureKey: String, padding: PaddingValues, onNavigate: (Dest) -> Unit, focusPrefKey: String? = null) {
    val kit = LocalFpKit.current
    val spec: FeatureSpec? = Catalog.find(featureKey)?.takeIf { it.isListedInSettings }
    val prefs = ConfigStore.observe()
    // 刚恢复过默认：留着快照给「撤销」用。离开页面即丢 —— 撤销只管「刚才那一下」
    var resetUndo by remember(featureKey) { mutableStateOf<ConfigStore.Checkpoint?>(null) }
    var reloading by remember(featureKey) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    if (spec == null) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            kit.Paragraph(fpString("common.unknown"), FpEmphasis.NEGATIVE)
        }
        return
    }
    val scopeReady = featureScopeReady(spec)

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        kit.VSpace(FpSpace.s)

        // 详情页头部：一行说清「这是什么 / 开没开」，成熟度与生效方式改成**徽标**并列。
        // 原来成熟度用横贯整屏的 Banner 画 —— 每次进详情页最抢眼的是那条「实验」，
        // 而它是这一页最不需要用户注意的信息（真正要看的参数全在下面）。
        kit.Card {
            // 标题已在 AppBar 上，这里不再重复一遍（真机截图上同一个名字出现两次）。
            kit.SwitchRow(
                title = fpString("feature.detail.enable"),
                summary = fpString(spec.summaryI18n),
                checked = prefs.isEnabled(spec),
                onCheckedChange = { ConfigStore.putBoolean(Keys.enabled(spec.key), it) },
                enabled = spec.maturity.isUserToggleable && scopeReady,
            )
            // 参数少、内联在详情页的特性不会走类别页，预览画在开关下方、同一张卡里（目前只有完美状态栏）
            if (spec.presentation.preview == io.github.kotoriinsky.tsuzuri.core.FeaturePreview.STATUS_BAR) {
                StatusBarPreviewFor(spec, prefs)
            }
            // 「实验 · 改完立即生效 / 需重启进程」那行灰字删了：它是静态属性，
            // 侧滑索引的「需重启进程」只指总开关，用户读成「改什么都要重启」。
            // 现在只在**确实待生效**时出现一行可点的操作：开关拨过且还没重载（在哪一页拨的都算，
            // 判定集中在 ConfigStore，首页用同一份）。点了走热重载（不黑屏），失败才去模块状态页。
            if (ConfigStore.isReloadPending(spec.key)) {
                kit.Divider()
                kit.ActionRow(
                    title = fpString("feature.reload.pending"),
                    summary = null,
                    trailingText = null,
                    enabled = !reloading,
                    onClick = {
                        reloading = true
                        scope.launch {
                            val outcome = io.github.kotoriinsky.tsuzuri.ui.state.ReloadController.reloadAll()
                            reloading = false
                            if (!outcome.anyReloaded) onNavigate(Dest.Setup)
                        }
                    },
                )
            }
        }

        ScopeRequirement(spec) { onNavigate(Dest.Setup) }
        // 总开关开着、效果一个都没选：功能实际什么都不做，必须说出来
        if (io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.enabledButEmpty(prefs, spec.key)) {
            kit.VSpace(FpSpace.s)
            kit.Banner(fpString("feature.capability.empty"), FpEmphasis.WARNING)
        }

        // 参数一多就必须分组（超过约 8 个）。分组**从 Catalog 现算**，
        // 这一页不认识任何具体的参数名或分组名。
        val groups = spec.prefGroups()
        //
        // ## 判据是「参数总数」，不是「分组数」
        //
        // 原来判的是 `groups.size <= 1`（只有一组就内联）。那个判据在本仓库**恰好**没出问题
        // —— 实测四个单组特性的参数量是 1 / 2 / 3 / 3，全都在「内联合适」的范围内。
        // 但它和「参数少」是两件事，只是碰巧同值：一旦有人给某个单组特性加参数加到 10 个
        // （比如把 `widget_style` 的三个开关扩成十项），判据不会变、行为却从「内联」变成
        // 「把十项埋在一屏里」，而**编译和测试都不会红**。
        //
        // 所以改成直接判参数数量。这样「内联还是分组」只取决于一个量：
        // 这一屏放不放得下。分组数变成纯粹的组织方式，不再兼职承担「要不要二级页」的决定。
        val groupCount = groups.size
        // 第三层的分组在这一屏只占一行入口，按 1 项算
        val drill = spec.presentation.drillGroups
        val totalPrefs = groups.sumOf { if (it.id in drill) 1 else it.prefs.size }
        if (groupCount <= 1 && totalPrefs <= INLINE_PREF_LIMIT) {
            val only = groups.firstOrNull()
            if (only != null) {
                // 不再挂「参数」小节标题：详情页里除了参数没有别的内容，这个标题不提供信息
                kit.VSpace(FpSpace.m)
                kit.Card {
                    // 条件折叠：依赖不满足的参数收起，满足时动画展开。
                    // 显隐在渲染处做而不是改 groups —— 「内联还是二级页」的判断（上面那个
                    // totalPrefs）必须用**声明总数**，否则切一个开关页面结构就会在
                    // 「内联 ↔ 二级页」之间跳变。
                    val visible = only.prefs.filter { it.isVisible(prefs, spec.key) || it.key == focusPrefKey }
                    val firstVisible = only.prefs.indexOfFirst { it in visible }
                    only.prefs.forEachIndexed { i, pref ->
                        CollapsiblePref(pref in visible) {
                            Column {
                                if (i > firstVisible) kit.Divider()
                                SearchTarget(pref.key == focusPrefKey) {
                                    PrefRow(pref, prefs, enabled = prefs.isEnabled(spec), onNavigate = onNavigate)
                                }
                            }
                        }
                    }
                }
            }
        } else if (totalPrefs <= INLINE_GROUPED_LIMIT && groups.none { it.id == io.github.kotoriinsky.tsuzuri.core.PREF_GROUP_OTHER }) {
            groups.forEach { group ->
                if (group.id in drill) {
                    kit.VSpace(FpSpace.m)
                    kit.Card {
                        kit.ActionRow(
                            title = fpString(group.labelKey),
                            summary = contentsSummary(listOf(group)),
                            trailingText = null,
                            navigates = true,
                            onClick = if (prefs.isEnabled(spec)) ({ onNavigate(Dest.FeatureSection(spec.key, group.id)) }) else null,
                            enabled = prefs.isEnabled(spec),
                        )
                    }
                    return@forEach
                }
                kit.VSpace(FpSpace.m)
                kit.SectionHeader(fpString(group.labelKey))
                kit.Card {
                    val visible = group.prefs.filter { it.isVisible(prefs, spec.key) || it.key == focusPrefKey }
                    val firstVisible = group.prefs.indexOfFirst { it in visible }
                    group.prefs.forEachIndexed { i, pref ->
                        CollapsiblePref(pref in visible) {
                            Column {
                                if (i > firstVisible) kit.Divider()
                                SearchTarget(pref.key == focusPrefKey) {
                                    PrefRow(pref, prefs, enabled = prefs.isEnabled(spec), onNavigate = onNavigate)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 详情页入口按**类别**列：类别是用户意图维度
            // （触发 / 内容 / 外观 / 反馈 / 高级），扇形的外观不再被劈成四行。
            // 首屏因此能看全所有入口；到任一参数仍是 2 次点击（类别 → 参数）。
            val categories = spec.prefCategories()
            kit.VSpace(FpSpace.m)
            kit.Card {
                categories.forEachIndexed { i, category ->
                    if (i > 0) kit.Divider()
                    // 只留箭头，不再写「8 项」：HyperOS 设置的入口行右侧只放当前值或什么都不放，
                    // 计数是噪音（用户 2026-10-21：小字越少越好）。
                    // 但一个说明都没有又看不出「触发」「内容」里调什么（用户 2026-10-27），
                    // 所以副标题列出里面有什么 —— 现取分组 / 参数名，不另写文案，参数增减自动跟着变
                    kit.ActionRow(
                        title = fpString(category.labelKey),
                        summary = contentsSummary(category.groups),
                        trailingText = null,
                        navigates = true,
                        onClick = { onNavigate(Dest.FeatureCategory(spec.key, category.category.id)) },
                    )
                }
            }
        }

        // ── 「帮助与管理」区已经整个删掉了（2026-09-18 用户拍板）────────────────
        //
        // 原来这里有一张卡片，标题「帮助与管理」，里面一条「运行说明」，点开显示
        // 作用范围 / 生效方式 / 配置键 / 文档引用。四条全是**给开发者看的**运行时元数据，
        // 用户既不据此做决定，也读不懂 `restart.PROCESS_RESTART` 这类值。
        // 它占的是详情页最下面一屏，而真正要用的操作（恢复默认）在它后面 ——
        // 等于让用户为一堆用不上的信息多滚一段。
        //
        // 那些信息没有丢，只是换了地方：
        //  - 作用范围 / 生效方式 → 模块页里每个特性卡片本来就有（而且在那里才比较得起来）；
        //  - 配置键 → 「复制当前配置」导出的那一份里全都有。
        // 需要排障时走这几条路，比在详情页里逐条点开更快。
        // 复制直达：「试一下」走真实链路弹一次岛
        if (spec.key == io.github.kotoriinsky.tsuzuri.core.Catalog.PASSCODE_ISLAND) {
            kit.VSpace(12.dp)
            CopyDirectTryIt(prefs, enabled = prefs.isEnabled(spec) && scopeReady)
        }
        kit.VSpace(12.dp)
        // 原来点完确认对话框就消失，没有任何「已恢复」的反馈，也回不去（交互审查 I-03）
        resetUndo?.let { cp ->
            kit.Banner(
                text = fpString("settings.reset.done"),
                emphasis = FpEmphasis.MUTED,
                actionLabel = fpString("common.undo"),
                onAction = {
                    ConfigStore.rollback(cp)
                    resetUndo = null
                },
            )
            kit.VSpace(4.dp)
        }
        kit.Card {
            kit.ActionRow(
                title = fpString("common.reset"),
                summary = null,
                trailingText = null,
                destructive = true,
                // 不再弹确认框：做完原位给「撤销」
                onClick = {
                    val cp = ConfigStore.checkpoint(featureKeyScope(spec))
                    resetFeature(spec)
                    resetUndo = cp
                },
                enabled = scopeReady,
            )
        }

        kit.VSpace(24.dp)
    }
}

/**
 * 侧滑索引的「调整预览」。
 *
 * 几何一个数都不在这里算 —— 全部来自 `core` 的 [sideIndexPreview]（与真机那几份几何逐行对应，
 * ）。这里只做两件事：把配置读成 [SideIndexPreviewParams]，
 * 以及「参数一变就重画」（`ConfigStore.observe()` 返回的 prefs 在新对象上会触发重组）。
 *
 * 两个场景：手指在字母条上（条 + 气泡）/ 手指拖到面板上（条 + 面板 + 高亮格子 + 细条）。
 * 面板使用已安装应用的真实图标与名称，套用同一过滤与图标覆盖；字母分组数量仍为示意。
 */
@Composable
internal fun SideIndexPreviewSection(spec: FeatureSpec, prefs: Prefs, showHotZones: Boolean = false) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    // 当前所在屏幕的真实尺寸 / 圆角 / 挖孔；读不到退回本窗口 metrics（见 FanPreviewSection 同一处）
    val screen = rememberScreenShape()
    val screenW = screen?.width ?: metrics.widthPixels.toFloat()
    val screenH = screen?.height ?: metrics.heightPixels.toFloat()
    var scene by rememberSaveable { mutableStateOf(SideIndexPreviewScene.PANEL) }
    val assets by produceState(initialValue = emptyList<Pair<String, String>>(), prefs) {
        val loaded = loadAppCatalog(context) as? AppCatalog.Ready
        val names = SideIndexCustom.readNames(prefs, spec.key)
        val filter = SideIndexCustom.readFilterMode(prefs, spec.key)
        val packages = SideIndexCustom.readFilterPackages(prefs, spec.key)
        value = loaded?.apps.orEmpty().filter { IndexAppFilter.keeps(filter, packages, it.packageName) }
            .take(25).map { it.packageName to (names[it.packageName] ?: it.label) }
    }
    val keys = assets.map { it.first }
    val icons by produceState(initialValue = emptyList<ImageBitmap?>(), keys, prefs) {
        value = PreviewIcons.load(context, spec.key, keys, keys.size, prefs)
    }
    val useWallpaper = AppSettings.previewWallpaper
    val wallpaper by produceState(initialValue = PreviewWallpaper.Result.NONE, useWallpaper, PreviewWallpaper.revision) {
        value = if (useWallpaper) PreviewWallpaper.load(context) else PreviewWallpaper.Result.NONE
    }
    // 屏幕尺寸也是键：原来漏了它，转屏后预览还是旧比例
    val preview = remember(spec, prefs, scene, assets, metrics.density, screenW, screenH) {
        buildSideIndexPreview(spec, prefs, screenW, screenH, metrics.density, scene, assets.size)
    }
    val glass = prefs.rawString(Keys.pref(spec.key, "material"), "blur") == "glass"
    val blur = prefs.rawBoolean(Keys.pref(spec.key, "blur"), true)
    val radius = spec.prefs.filterIsInstance<PrefSpec.Slider>()
        .firstOrNull { it.key == Keys.pref(spec.key, "blur_radius") }?.let { prefs.value(it) } ?: 20f
    kit.VSpace(FpSpace.m)
    // 预览不放白卡（用户 2026-10-25）
    Column(Modifier.fillMaxWidth().padding(horizontal = FpSpace.m)) {
        val p = preview
        if (p == null) kit.Paragraph(fpString("side_index_preview.unavailable"), FpEmphasis.MUTED)
        else {
            SideIndexPreviewCanvas(p, icons = icons, labels = assets.map { it.second }, wallpaper = wallpaper.bitmap,
                glass = glass, blurRadiusDp = if (blur) radius else 0f, showHotZones = showHotZones, screen = screen)
            kit.SegmentedRow(fpString("preview.scene"), null,
                listOf(fpString("side_index_preview.scene.bar"), fpString("side_index_preview.scene.panel")),
                if (scene == SideIndexPreviewScene.BAR) 0 else 1,
                { scene = if (it == 0) SideIndexPreviewScene.BAR else SideIndexPreviewScene.PANEL })
            // 壁纸开关移到 设置 › 预览；这里只在想用壁纸却没拿到时说原因
            PreviewWallpaperReason(wallpaper)
        }
    }
}

/**
 * 把这一页当前的配置值读成预览参数。
 *
 * **任何一项读不到就返回 null**（界面显示「预览不可用」），刻意不给兜底默认值 ——
 * 兜默认值的后果是「预览用默认值画、真机用用户值画」，而用户相信预览。
 * 那比不显示预览更糟。
 */
private fun buildSideIndexPreview(
    spec: FeatureSpec,
    prefs: Prefs,
    screenW: Float,
    screenH: Float,
    density: Float,
    scene: SideIndexPreviewScene,
    appCount: Int,
): SideIndexPreview? {
    val sliders = spec.prefs.filterIsInstance<PrefSpec.Slider>()
    val toggles = spec.prefs.filterIsInstance<PrefSpec.Toggle>()
    val choices = spec.prefs.filterIsInstance<PrefSpec.Choice>()

    fun num(suffix: String): Float? =
        sliders.firstOrNull { it.key.endsWith(".$suffix") }
            ?.let { runCatching { prefs.value(it) }.getOrNull() }

    fun flag(suffix: String): Boolean? =
        toggles.firstOrNull { it.key.endsWith(".$suffix") }
            ?.let { runCatching { prefs.value(it) }.getOrNull() }

    fun choice(suffix: String): String? =
        choices.firstOrNull { it.key.endsWith(".$suffix") }
            ?.let { runCatching { prefs.value(it) }.getOrNull() }

    // 字母条上的槽位：设置界面进程没有真索引，用 LetterIndex.ALPHABET 示意。
    // 特殊槽的顺序与 hook 侧 AppIndex.Index.withSpecial 一致：收藏 ♥ 在最近 ★ 之前。
    // 收藏清单读的是**同一个键**，空 = 不显示那个槽（与真机逐位一致）。
    val showRecent = flag("show_recent") ?: return null
    val hasFavorites = runCatching {
        OrderedItems.decode(prefs.rawString(Keys.pref(spec.key, "favorites"), null)).isNotEmpty()
    }.getOrDefault(false)
    val letters = buildList {
        if (hasFavorites) add("♥")
        if (showRecent) add("★")
        addAll(LetterIndex.ALPHABET)
    }

    val params = SideIndexPreviewParams(
        screenW = screenW,
        screenH = screenH,
        density = density,
        letters = letters,
        // 演示槽位：开了「特殊槽位用图标」就选 0（★ 在字母条最前），
        // 否则选 1（第 2 个字母）—— 否则那个开关在预览里看不出任何变化。
        slot = if (flag("bubble_icon_slots") == true) 0 else 1,
        appCount = appCount,
        scene = scene,
        letterOffsetPercent = num("letter_offset") ?: return null,
        letterScalePercent = num("letter_scale") ?: return null,
        iconSizeDp = num("icon_size") ?: return null,
        iconRadiusDp = num("icon_radius") ?: return null,
        panelInsetDp = num("panel_inset") ?: return null,
        // 外观层第二批。同样按严格读法：读不到就显示「预览不可用」，
        // 不兜默认值 —— 兜了会出现「预览用默认值画、真机用用户值画」，比不显示更糟。
        cellHeightDp = num("cell_height") ?: return null,
        hideAppNames = flag("hide_app_names") ?: return null,
        bubbleAnimMs = num("bubble_anim_ms") ?: return null,
        bubbleIconSlots = flag("bubble_icon_slots") ?: return null,
        // #5c 列数与格宽：预览必须跟着变 —— 它们改了面板总宽与"最后一列贴哪边"的对齐。
        columns = num("columns")?.toInt() ?: return null,
        cellWidthDp = num("cell_width") ?: return null,
        // 注：`material`（背景材质）**不进预览** —— 它是渲染材质的切换，
        // 不影响 geometry，预览契约里没有这个参数（材质观感只能真机看）。
        onRight = choice("side") != "left",
        bothSides = choice("side") == "both",
        showHandle = flag("show_handle") ?: return null,
        handleWidthDp = num("trigger_width") ?: return null,
        handleHeightPercent = num("handle_height") ?: return null,
        handleOffsetPercent = num("handle_offset") ?: return null,
    )
    return runCatching { sideIndexPreview(params) }.getOrNull()
}

/**
 * 图标包入口。
 *
 * **为什么是单独一行、单独一页，而不是一个参数行**：图标包的候选集是运行时枚举出来的
 * （要读每个图标包的资源），塞进 `PrefRow` 只能做成禁用的只读行 —— 那就是一个
 * 「看得见、设不了」的死控件，正是本项目最忌讳的形态。
 *
 * 页面入参要求「同一特性里有一条 OrderedList」来推特性键与预览目标，
 * `side_index.favorites` 就是那一条 —— 收藏项与图标包都指着面板里的图标，正好同源。
 */
@Composable
internal fun SideIndexIconPackSection(
    spec: FeatureSpec,
    prefs: Prefs,
    onNavigate: (Dest) -> Unit,
) {
    val kit = LocalFpKit.current
    val listSpec = spec.prefs.filterIsInstance<PrefSpec.OrderedList>().firstOrNull() ?: return
    val packKey = Keys.pref(spec.key, "icon_pack")
    val pack = remember(prefs) { prefs.rawStringSet(packKey).sorted().firstOrNull() }
    val context = LocalContext.current
    val packLabel by produceState<String?>(null, pack) {
        value = pack?.let { io.github.kotoriinsky.tsuzuri.ui.state.AppNames(context).label(it) }
    }

    kit.SectionHeader(fpString("icon_pack.title"))
    kit.Card {
        kit.ActionRow(
            title = fpString("icon_pack.entry"),
            summary = fpString("icon_pack.entry.hint"),
            trailingText = packLabel ?: pack ?: fpString("icon_pack.none"),
            onClick = { onNavigate(Dest.IconPackPicker(listSpec)) },
            navigates = true,
        )
    }
}

/**
 * 单应用「名字 / 图标」覆盖的入口（本批 3️⃣）。
 *
 * 与 [SideIndexIconPackSection] 同一条路：入参要一条 `OrderedList` 来推特性键与预览目标，
 * `side_index.favorites` 就是那一条。**单独一行、单独一页** —— 它是逐应用的编辑，
 * 塞进参数行只能做成禁用的只读行。
 */
@Composable
internal fun SideIndexNameIconSection(spec: FeatureSpec, onNavigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val listSpec = spec.prefs.filterIsInstance<PrefSpec.OrderedList>().firstOrNull() ?: return
    val withName = listSpec.iconTools == io.github.kotoriinsky.tsuzuri.core.ListIconTools.NAME_AND_ICON

    kit.SectionHeader(fpString(if (withName) "pref.side_index_name_overrides" else "icon_override.entry"))
    kit.Card {
        kit.ActionRow(
            title = fpString(if (withName) "side_index_override_intro" else "icon_override.entry"),
            summary = fpString(if (withName) "pref.side_index_name_overrides.hint" else "icon_override.entry.hint"),
            trailingText = null,
            onClick = { onNavigate(if (withName) Dest.SideIndexOverrides(listSpec) else Dest.FanIconOverrides(listSpec)) },
            navigates = true,
        )
    }
}
