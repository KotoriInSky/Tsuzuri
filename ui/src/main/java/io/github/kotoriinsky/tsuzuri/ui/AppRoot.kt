package io.github.kotoriinsky.tsuzuri.ui

import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.kyant.backdrop.backdrops.layerBackdrop
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.prefCategories
import io.github.kotoriinsky.tsuzuri.ui.glass.FpGlassBottomBar
import io.github.kotoriinsky.tsuzuri.ui.glass.rememberSceneBackdrop
import io.github.kotoriinsky.tsuzuri.ui.kit.FpBottomBar
import io.github.kotoriinsky.tsuzuri.ui.kit.FpMessageHost
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalPageKey
import io.github.kotoriinsky.tsuzuri.ui.kit.ScreenHotZoneLayer
import io.github.kotoriinsky.tsuzuri.ui.kit.FpNavItem
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSize
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpMessageHost
import io.github.kotoriinsky.tsuzuri.ui.model.UiStyle
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.nav.DestStackSaver
import io.github.kotoriinsky.tsuzuri.ui.nav.LocalPageBackAction
import io.github.kotoriinsky.tsuzuri.ui.nav.PageBackAction
import io.github.kotoriinsky.tsuzuri.ui.nav.Tab
import io.github.kotoriinsky.tsuzuri.ui.screen.AboutScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.ActivityImportScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.AppListPickerScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.AddItemScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.AppPickerScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.ConfigChannelBanner
import io.github.kotoriinsky.tsuzuri.ui.screen.FanIconOverridesScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.FanItemsScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.FeatureCategoryScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.FeatureDetailScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.FeatureSectionScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.HomeScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.WidgetSettingsScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.ProactiveScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.IconPackDrawableScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.IconPackPickerScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.LinkImportScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.LogViewerScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.PasscodeRulesScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.ScopeRequirement
import io.github.kotoriinsky.tsuzuri.ui.screen.SearchScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.SettingsScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.SettingsSection
import io.github.kotoriinsky.tsuzuri.ui.screen.SettingsSectionScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.SetupScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.ShortcutImportScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.SideIndexOverridesScreen
import io.github.kotoriinsky.tsuzuri.ui.screen.featureScopeReady
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import io.github.kotoriinsky.tsuzuri.ui.state.AppearanceStore
import io.github.kotoriinsky.tsuzuri.ui.state.FrameworkBridge
import io.github.kotoriinsky.tsuzuri.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius

/**
 * 底栏 tab 横滑的弹簧：ζ≈1.0、response≈0.28s ⇒ stiffness = (2π/0.28)² ≈ 503.5
 * （「时钟」录屏拟合）。
 */
private val TabSwitchSpring = spring<Float>(dampingRatio = 1f, stiffness = 503.5f)

/**
 * 应用外壳：主题 → 返回栈（miuix-nav 的 NavDisplay）→ 各页面。
 *
 * 结构对齐 KernelSU：
 *  - **顶栏、底栏都在页面里**，外壳不再有共享 Scaffold —— 上一轮 NavDisplay 接入失败的根因
 *    就是外层 Scaffold 的栏与 NavDisplay 的场景打架（见提交 f822657）；
 *  - 栈根是 [Dest.Home]：「首页 / 设置」两个 tab 是同一个条目里的横滑分页 + 底栏，
 *    下钻页盖在整个 tab 页（含底栏）之上，与 HyperOS 设置打开二级页一致；
 *  - 推入 / 返回 / 跟手返回的动画全部由 NavDisplay 负责，参数与 HyperOS WMShell 一致。
 *
 * 这是唯一知道「有哪些页面」的地方。页面之间互不引用，需要跳转就把 `navigate` 往上抛。
 */
@Composable
fun AppRoot() {
    // 返回栈、tab 分页与消息宿主放在主题分支之外，换风格时保持身份。
    val stack = rememberSaveable(saver = DestStackSaver) { mutableStateListOf<NavKey>(Dest.Home) }
    val pager = rememberPagerState(pageCount = { Tab.entries.size })
    val messages = remember { SnackbarHostState() }
    val pageBack = remember { PageBackAction() }
    val scope = rememberCoroutineScope()
    // tab 页底部被底栏占掉的高度（含系统导航栏）：消息条要浮在它上面
    var tabBottomInset by remember { mutableStateOf(0.dp) }
    CompositionLocalProvider(LocalFpMessageHost provides messages, LocalPageBackAction provides pageBack) {
    // 点击振动作为参数传进 AppTheme，而不是让 Kit 自己去读 AppSettings：
    // Kit 只负责画（FpKit 契约第 3 条），而这里是唯一把「配置」接到「主题」上的地方
    AppTheme(AppearanceStore.current, hapticFeedback = AppSettings.hapticFeedback) {
        // 不再自动推出模块状态页：提醒靠首页状态卡变态，入口一直在首页顶部。

        // 界面在前台时每 3 秒刷一次框架状态：
        // 诊断页要展示「哪个进程还在跑旧代码」，而这件事会在我们没操作时自己变。
        LaunchedEffect(Unit) {
            while (true) {
                delay(3000)
                FrameworkBridge.refresh()
            }
        }

        val navigate: (Dest) -> Unit = { dest ->
            if (dest.isTopLevel) {
                // 切 tab：先退回栈根（NavDisplay 播放返回动画），再横滑到目标 tab
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                val page = Tab.of(dest)?.ordinal ?: 0
                scope.launch { pager.animateScrollToPage(page, animationSpec = TabSwitchSpring) }
            } else if (stack.last() != dest) {
                stack.add(dest)
            }
        }
        // 顶栏返回键：页面接管了返回（裁剪等局部步骤）就交给页面，否则出栈。
        // 系统返回 / 侧滑不走这里：NavDisplay 自己处理，页面接管由 PageBackHandler 注册在条目作用域里。
        val back: () -> Unit = {
            val local = pageBack.action
            if (local != null) local() else if (stack.size > 1) stack.removeAt(stack.lastIndex)
        }

        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = stack,
                modifier = Modifier.fillMaxSize(),
                onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
            ) {
                // 逐路由注册（NavDisplay 按类型区分条目）。Settings 不会单独入栈，注册它只为兜底。
                entry<Dest.Home> { MainScreen(pager, navigate) { tabBottomInset = it } }
                entry<Dest.Settings> { MainScreen(pager, navigate) { tabBottomInset = it } }
                entry<Dest.About> { DrillPage(it, back, navigate) }
                entry<Dest.Credits> { DrillPage(it, back, navigate) }
                entry<Dest.SettingsPage> { DrillPage(it, back, navigate) }
                entry<Dest.Search> { DrillPage(it, back, navigate) }
                entry<Dest.Setup> { DrillPage(it, back, navigate) }
                entry<Dest.WidgetSettings> { DrillPage(it, back, navigate) }
                entry<Dest.Proactive> { DrillPage(it, back, navigate) }
                entry<Dest.LogViewer> { DrillPage(it, back, navigate) }
                entry<Dest.FeatureDetail> { DrillPage(it, back, navigate) }
                entry<Dest.FeatureSection> { DrillPage(it, back, navigate) }
                entry<Dest.FeatureCategory> { DrillPage(it, back, navigate) }
                entry<Dest.FanItems> { DrillPage(it, back, navigate) }
                entry<Dest.AppPicker> { DrillPage(it, back, navigate) }
                entry<Dest.AddItem> { DrillPage(it, back, navigate) }
                entry<Dest.IconPackPicker> { DrillPage(it, back, navigate) }
                entry<Dest.ShortcutImport> { DrillPage(it, back, navigate) }
                entry<Dest.LinkImport> { DrillPage(it, back, navigate) }
                entry<Dest.ActivityImport> { DrillPage(it, back, navigate) }
                entry<Dest.FanIconOverrides> { DrillPage(it, back, navigate) }
                entry<Dest.AppListPicker> { DrillPage(it, back, navigate) }
                entry<Dest.SideIndexOverrides> { DrillPage(it, back, navigate) }
                entry<Dest.IconPackDrawable> { DrillPage(it, back, navigate) }
                entry<Dest.PasscodeRules> { DrillPage(it, back, navigate) }
            }
            // 「触发」页的屏幕热区高亮：铺满窗口、不拦触摸，压在所有页面之上
            ScreenHotZoneLayer(activePage = stack.last())
            // 消息条是全局的（任何页面都可能发），只画一份，浮在最上层
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            FpMessageHost(
                messages,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (stack.size == 1) tabBottomInset else navBar)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    }
}

/**
 * 栈根条目：「首页 / 设置」两个 tab 的横滑分页 + 底栏。
 *
 * 顶层页**不出顶栏**：大标题由页面自己的 FpKit.PageHeader 画在正文里（HyperOS 设置的排法）。
 */
@Composable
private fun MainScreen(pager: PagerState, navigate: (Dest) -> Unit, onBottomInset: (Dp) -> Unit) {
    val kit = LocalFpKit.current
    val scope = rememberCoroutineScope()
    val selectTab: (Int) -> Unit = { index ->
        scope.launch { pager.animateScrollToPage(index, animationSpec = TabSwitchSpring) }
    }

    // 停在「设置」tab 时，返回先回「首页」tab，再按才退出应用（KernelSU 同款）
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = pager.currentPage != 0,
        onBackCompleted = { selectTab(0) },
    )

    // ── 液态玻璃（实验）──
    //
    // 玻璃底栏必须「抓」住下方页面内容才能折射，所以把分页放进 layerBackdrop；
    // 底栏本身画在这层之外（否则会采样到自己）。开关关掉时整条路径退化成 kit.Scaffold 的 bottomBar。
    val appearance = AppearanceStore.current
    val floatingBar = appearance.floatingBar && appearance.style == UiStyle.MIUIX
    val glassBars = floatingBar && appearance.glassBars
    val sceneBackdrop = rememberSceneBackdrop(kit.colors().surface)
    val bottomBar = FpBottomBar(
        items = Tab.entries.map { FpNavItem(fpString(it.labelI18n), it.icon) },
        // 用 targetPage：点下去底栏立刻换高亮，不等横滑结束
        selectedIndex = pager.targetPage,
        onSelect = selectTab,
    )

    kit.Scaffold(title = "", bottom = if (floatingBar) null else bottomBar) { padding ->
        val glassBarHeight = if (floatingBar) io.github.kotoriinsky.tsuzuri.ui.glass.floatingBarInset() else 0.dp
        val bottomInset = padding.calculateBottomPadding() + glassBarHeight
        SideEffect { onBottomInset(bottomInset) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (floatingBar) Modifier.layerBackdrop(sceneBackdrop) else Modifier)) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    // 两个 tab 都常驻组合：横滑时相邻页已经画好，不会出现空白一帧
                    beyondViewportPageCount = 1,
                ) { page ->
                    val tab = Tab.entries[page]
                    // 每个 tab 自带可折叠大标题（HyperOS 设置首页的排法），各自跟随自己的滚动
                    kit.Scaffold(
                        title = fpString(tab.pageTitleI18n),
                        actions = { if (tab == Tab.HOME) io.github.kotoriinsky.tsuzuri.ui.screen.HomeRestartAction() },
                    ) { inner ->
                        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
                            ConfigChannelBanner()
                            val screenPadding = PaddingValues(bottom = bottomInset)
                            when (tab) {
                                Tab.HOME -> HomeScreen(screenPadding, navigate)
                                Tab.MORE -> SettingsScreen(screenPadding, navigate)
                            }
                        }
                    }
                }
            }
            if (glassBars) {
                FpGlassBottomBar(
                    backdrop = sceneBackdrop,
                    items = bottomBar.items,
                    selectedIndex = bottomBar.selectedIndex,
                    onSelect = bottomBar.onSelect,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else if (floatingBar) {
                io.github.kotoriinsky.tsuzuri.ui.glass.FpFloatingBottomBar(
                    backdrop = sceneBackdrop,
                    items = bottomBar.items,
                    selectedIndex = bottomBar.selectedIndex,
                    onSelect = bottomBar.onSelect,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** 下钻页：自带「小标题栏 + 返回」，栏随页面一起被 NavDisplay 推入 / 移出。 */
@Composable
private fun DrillPage(dest: Dest, back: () -> Unit, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    // 页面身份：屏幕热区高亮只在「登记它的页面 == 栈顶」时画（见 ScreenHotZones）
    CompositionLocalProvider(LocalPageKey provides dest) {
        kit.Scaffold(title = dest.title(), subtitle = dest.subtitle(), onBack = back) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                ConfigChannelBanner()
                ScreenContent(dest, PaddingValues(bottom = padding.calculateBottomPadding()), navigate)
            }
        }
    }
}

@Composable
private fun ScreenContent(top: Dest, padding: PaddingValues, navigate: (Dest) -> Unit) {
    // 已进入的编辑子页在作用域撤销后同样受限，不能绕过详情页的禁用状态。
    val pref = when (top) {
        is Dest.FanItems -> top.spec
        is Dest.AppPicker -> top.spec
        is Dest.AddItem -> top.spec
        is Dest.IconPackPicker -> top.spec
        is Dest.ShortcutImport -> top.spec
        is Dest.LinkImport -> top.spec
        is Dest.ActivityImport -> top.spec
        is Dest.FanIconOverrides -> top.spec
        is Dest.PasscodeRules -> top.spec
        is Dest.AppListPicker -> top.spec
        is Dest.SideIndexOverrides -> top.spec
        is Dest.IconPackDrawable -> top.spec
        else -> null
    }
    // 详情/分组/类别页都按特性做作用域闸门；其它页按「参数所属特性」反查。
    val gatedFeatureKey = when (top) {
        is Dest.FeatureDetail -> top.featureKey
        is Dest.FeatureSection -> top.featureKey
        is Dest.FeatureCategory -> top.featureKey
        else -> null
    }
    val feature = if (gatedFeatureKey != null) Catalog.find(gatedFeatureKey)
        else pref?.let { target -> Catalog.features.firstOrNull { feature -> feature.prefs.any { it.key == target.key } } }
    // 缺作用域不再整页替换：页面照常显示，顶上一条横幅，点「去处理」到模块状态页。
    // 详情 / 分组 / 类别三页自己在页内画这条横幅（位置在启用开关下面），这里只管其余子页。
    if (feature != null && gatedFeatureKey == null && !featureScopeReady(feature)) {
        Column(Modifier.fillMaxSize()) {
            ScopeRequirement(feature) { navigate(Dest.Setup) }
            Box(Modifier.weight(1f)) { PageContent(top, padding, navigate) }
        }
    } else {
        PageContent(top, padding, navigate)
    }
}

@Composable
private fun PageContent(top: Dest, padding: PaddingValues, navigate: (Dest) -> Unit) {
    when (top) {
        Dest.Home -> HomeScreen(padding, navigate)
        Dest.Settings -> SettingsScreen(padding, navigate)
        Dest.About -> AboutScreen(padding, navigate)
        Dest.Credits -> io.github.kotoriinsky.tsuzuri.ui.screen.CreditsScreen(padding)
        is Dest.SettingsPage -> SettingsSection.of(top.section)?.let { SettingsSectionScreen(it, padding, navigate) }
        Dest.Search -> SearchScreen(padding, navigate)
        Dest.Setup -> SetupScreen(padding, navigate)
        Dest.WidgetSettings -> WidgetSettingsScreen(padding, navigate)
        Dest.Proactive -> ProactiveScreen(padding, navigate)
        is Dest.FeatureDetail -> FeatureDetailScreen(top.featureKey, padding, navigate, top.focusPrefKey)
        is Dest.FeatureCategory -> FeatureCategoryScreen(
            top.featureKey,
            top.category,
            padding,
            navigate,
            top.focusPrefKey,
        )
        is Dest.FeatureSection -> FeatureSectionScreen(
            top.featureKey,
            top.group,
            padding,
            navigate,
            top.focusPrefKey,
        )
        is Dest.FanItems -> FanItemsScreen(top.spec, padding, navigate)
        is Dest.AppPicker -> AppPickerScreen(top.spec, padding, navigate)
        is Dest.AddItem -> AddItemScreen(top.spec, padding, navigate)
        is Dest.IconPackPicker -> IconPackPickerScreen(top.featureKey, top.spec, top.previewPackage, padding)
        // ⚠️ `navigate` 必须传进去：这一页底部的「单项图标」入口靠它跳转。
        // 它的参数有默认值（`= {}`），漏传**照样编译通过**，症状是「那一行点了没反应」——
        // 正是「声明了但没接线」那类静默失效。
        is Dest.ShortcutImport -> ShortcutImportScreen(top.spec, padding)
        // 链接页没有 `onNavigate`（校验失败就在页内报错），所以只传清单声明与 padding ——
        // 少一个「忘了传回调」的静默失效点。
        is Dest.LinkImport -> LinkImportScreen(top.spec, padding)
        // 活动页同样没有 `onNavigate`（校验失败就在页内报错），理由与链接页一致 ——
        // 少一个「忘了传回调」的静默失效点。
        is Dest.ActivityImport -> ActivityImportScreen(top.spec, padding)
        is Dest.FanIconOverrides -> FanIconOverridesScreen(top.spec, padding, navigate, top.focusKey)
        is Dest.LogViewer -> LogViewerScreen(padding)
        is Dest.PasscodeRules -> PasscodeRulesScreen(top.spec, padding)
        is Dest.AppListPicker -> AppListPickerScreen(top.spec, padding,
            notice = if (top.spec.key.endsWith(".index_filter_packages")) {
                val mode = ConfigStore.observe().rawString(io.github.kotoriinsky.tsuzuri.core.Keys.pref(Catalog.SIDE_INDEX, "index_filter_mode"), "all")
                // 默认「显示全部」不用提示：那是没做任何筛选的正常态（用户 2026-10-25）
                when (mode) { "include" -> fpString("index_filter.notice.include"); "exclude" -> fpString("index_filter.notice.exclude"); else -> null }
            } else null,
        )
        is Dest.SideIndexOverrides -> SideIndexOverridesScreen(top.spec, padding, navigate, top.focusKey)
        is Dest.IconPackDrawable -> IconPackDrawableScreen(
            top.spec,
            top.targetKey,
            top.targetLabel,
            padding,
        )
    }
}

@Composable
private fun Dest.title(): String = fpString(
    when (this) {
        Dest.Home -> "home.title"
        Dest.Settings -> "nav.settings"
        Dest.About -> "about.title"
        Dest.Credits -> "about.thanks"
        is Dest.SettingsPage -> SettingsSection.of(section)?.titleI18n ?: "nav.settings"
        Dest.Search -> "search.title"
        Dest.Setup -> "onb.title"
        Dest.WidgetSettings -> "widget_settings_title"
        Dest.Proactive -> "group_CLIPBOARD"
        is Dest.FeatureDetail -> Catalog.find(featureKey)?.titleI18n ?: featureKey
        // 类别页的大标题就是类别名（「外观」），不再是「特性名 + 小字类别」：
        // HyperOS 的二级页只有一个标题，即这一页本身叫什么
        is Dest.FeatureCategory -> Catalog.find(featureKey)?.prefCategories()
            ?.firstOrNull { it.category.id == category }?.labelKey ?: featureKey
        // 二级页：标题仍是特性名，副标题说清「这是哪一组参数」——
        // 只写分组名会让用户不知道自己还在哪个特性里，只写特性名又会和详情页撞脸
        is Dest.FeatureSection -> "pref.group.$group"
        // 清单页的标题**就是 Catalog 里那条参数的名字**（「扇形里的项」）：
        // 它和「特性 → 应用清单」那一行是同一句话，用户点进来不会怀疑自己走错了地方
        is Dest.FanItems -> spec.labelKey
        // 图标那个二级页的标题是固定的「图标」（与清单页里那一行同名），
        // 「扇形里的项」放在副标题里 —— 见 subtitle()
        // 应用多选页 / 覆盖页的标题是那条参数的名（与 FanItems 同一条口径：
        // 和「特性 → 那一行」是同一句话，用户点进来不会怀疑自己走错地方）
        is Dest.AppListPicker -> spec.labelKey
        is Dest.SideIndexOverrides -> "side_index.overrides.title"
        // 应用多选页的标题是固定的「选择应用」，**参数的名**放在副标题里 ——
        // 标题栏里塞「扇形里的项」这种长名字会挤掉返回键，而副标题正好放得下
        is Dest.AppPicker -> "app_picker.title"
        is Dest.AddItem -> "add_item.title"
        is Dest.IconPackPicker -> "icon_pack.title"
        is Dest.ShortcutImport -> "shortcut.title"
        is Dest.LinkImport -> "link_import_title"
        is Dest.ActivityImport -> "activity_import_title"
        is Dest.FanIconOverrides -> "icon_override.title"
        is Dest.LogViewer -> "log.title"
        is Dest.PasscodeRules -> spec.labelKey
        is Dest.IconPackDrawable -> "icon_override.from_pack"
    }
)

@Composable
private fun Dest.subtitle(): String? = when (this) {
    // HyperOS 的页面标题下没有小字（用户 2026-10-21：小字越少越好）。唯一保留的是挑图标页：
    // 副标题是「正在给哪一项挑图标」——不写的话用户切了几个候选就分不清自己在改哪一项了
    is Dest.IconPackDrawable -> targetLabel
    else -> null
}
