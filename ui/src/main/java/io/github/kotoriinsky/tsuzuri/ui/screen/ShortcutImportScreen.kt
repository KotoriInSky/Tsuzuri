package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.peekAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.ImportableShortcut
import io.github.kotoriinsky.tsuzuri.ui.state.ShortcutCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.customShortcutKey
import io.github.kotoriinsky.tsuzuri.ui.state.filterApps
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.readAppsMode
import io.github.kotoriinsky.tsuzuri.ui.state.listTakesEffect
import io.github.kotoriinsky.tsuzuri.ui.state.readShortcutCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.selectedShortcuts

/**
 * 导入快捷方式。
 *
 * ## 数据从哪来（这一页**自己查不到**）
 *
 * 列表来自后端（SystemUI 侧）枚举后投递的目录，见 `ShortcutCatalogStore` 的类注释：
 * 设置界面没有权限查别的应用的快捷方式（`ACCESS_SHORTCUTS` 或当桌面，两条都没有）。
 *
 * 推论（界面文案必须如实说）：**目录为空 ≠ 设备上没有快捷方式**，它多半是「还没投递」
 * （刚装完模块、SystemUI 还没重载）。所以空态给的是「怎么做才能看到列表」，
 * 而且目录读不到时**这一页照样能用**（手工添加那条路不依赖目录）。
 *
 * ## 三分类（用户要求：「分类清晰，减少不必要的文本」）
 *
 * ```
 * ① 已选    ← 清单里**当前所有**快捷方式项（目录里没有的、手工加的也在这里）
 * ② 常用    ← 四个实测存在的常见目标，且**还没被选**的
 * ③ 按应用  ← 目录里的其余项，按应用分组（同一应用的快捷方式一定挨在一起）
 * ④ 手工添加 ← 可折叠，见下
 * ```
 *
 * 为什么不叫「全部」：这三段加起来仍是全部，而「已选」回答的是用户最想确认的那件事
 *（「我到底加了什么」），「全部」回答不了 —— 那正是这一页之前最难用的一点。
 *
 * **同一条只在其中一段出现**（已选 > 常用 > 按应用 的顺序去重），而不是在「已选」里
 * 再照抄一遍按应用的列表：同一屏出现两条一模一样的行，用户会以为那是两个不同的项。
 *
 * 两个副标题格式**故意不同**（按应用那段的副标题只写 id）：
 * 分组标题已经写了应用名，再在每行重复一遍纯属噪音；而上面两段没有分组标题，
 * 应用名是识别用的那一半信息，必须带上。id 在每一段都保留 —— 同一个应用下常有多个
 * 相似的标签，「要加/要删的是哪一条」只有 id 能对上。
 *
 * ## 图标：为什么每行左侧是**宿主应用**的图标
 *
 * **快捷方式自己的图标在设置界面进程里拿不到。** `LauncherAppsService.ensureShortcutPermission`
 * 要求调用方有 `ACCESS_SHORTCUTS` 或是当前桌面（取证完整写在 `ShortcutCatalog.kt` 的类注释里），
 * 而目录又是 SystemUI 侧枚举后投递过来的 —— **那一段字符串里没有图标位**，也不该有
 *（位图塞进配置存储既装不下也传不动）。
 *
 * 所以这里是宿主应用图标 + 应用名：它回答得了「这条属于谁」，回答不了「它长什么样」。
 * 让人看到快捷方式**自己的**图标是「单项图标」那一页的事（hook 侧按 `包名/id` 换图）。
 *
 * ## 勾选写哪
 *
 * 直接写进**扇形清单本身**（`<特性>.items`，有序字符串，见 [FanItems]），格式 `包名/id`。
 * 手工添加写的是**同一个键、同一种格式**（[customShortcutKey]），所以「勾选」与「手工添加」
 * 之间没有第二个存储位置，也就不存在「两处说法不一致」。
 * 勾上 = 追加到清单末尾（清单页可以拖顺序），取消 = 从清单里去掉那一条。
 *
 * ## 这一页的「单项图标」入口已经搬走
 *
 * 以前这一页底部有一个「单项图标」直达行。现在清单的图标入口统一收在
 * 「扇形里的项 → 图标」那个二级页里（整体图标包与单项图标同页，
 * 因为两者回答的是同一个问题的两级）。功能没丢，只是不再在三个地方各有一个入口。
 */
@Composable
fun ShortcutImportScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val context = LocalContext.current

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    /** 清单里当前的项（有序）。勾选状态、增删都以它为准。 */
    val items = FanItems.read(prefs, spec)
    val selected = remember(items) { items.toSet() }

    // 应用清单：显示名（`com.tencent.mm` → 「微信」）与行首图标都从它来。
    // 目录里拿不到显示名，只能借应用清单 —— 与「应用多选」用同一份实现。
    var appCatalog by remember { mutableStateOf<AppCatalog>(peekAppCatalog() ?: AppCatalog.Loading) }
    LaunchedEffect(context) { appCatalog = loadAppCatalog(context) }
    val apps = (appCatalog as? AppCatalog.Ready)?.apps ?: emptyList()
    val installed = remember(apps) { apps.associate { it.packageName to it.label } }

    val iconPx = with(LocalDensity.current) { FanIconSize.roundToPx() }
    val icons = remember(context, iconPx) { AppIconLoader(context, iconPx) }

    var query by rememberSaveable { mutableStateOf("") }
    val shortcuts = readShortcutCatalog()
    val known = remember(shortcuts) {
        when (shortcuts) {
            is ShortcutCatalog.Ready -> shortcuts.items.associateBy { it.key }
            ShortcutCatalog.Absent -> emptyMap()
        }
    }
    val all = (shortcuts as? ShortcutCatalog.Ready)?.items ?: emptyList()

    val chosen = remember(selected) { selectedShortcuts(selected) }
    // 「用哪条清单」的**显式模式**：`auto` 时手挑的清单仍然存着但不生效，
    // 这一点必须写在界面上，否则用户会以为清单丢了
    val manual = readAppsMode(featureKey) == "manual"

    // ── ① 已选：**不查目录**，直接由清单键反推 ─────────────────────────────
    // 目录读不到时也要列出来（手工加的那几条必须看得见、能取消），所以这一段的数据源是
    // 清单本身，目录只用来说明「这个 id 现在还在不在」
    val q = query.trim()
    val selectedRows = remember(chosen, known, installed, q) {
        chosen.map { sc -> shortcutRow(sc.key, sc.packageName, sc.id, known, installed, true) }
            .filter { it.matches(q) }
    }

    // ── ② 常用：硬编码的 id 必须在目录里**真的存在**（界面上不放点了没反应的按钮）──
    val quickKeys = remember(known) { QUICK.filter { known.containsKey(it) } }
    val quickRows = remember(quickKeys, selected, known, installed, q) {
        quickKeys.filter { it !in selected }
            .mapNotNull { known[it] }
            .map { sc -> shortcutRow(sc.key, sc.packageName, sc.id, known, installed, true) }
            .filter { it.matches(q) }
    }

    // ── ③ 按应用：目录里剩下的 ─────────────────────────────────────────────
    val quickSet = remember(quickKeys) { quickKeys.toHashSet() }
    val rest = remember(all, selected, quickSet) {
        all.filter { it.key !in selected && it.key !in quickSet }
    }
    val groups = remember(rest, installed, q) { groupShortcuts(rest, installed, q) }

    val toggle: (String, Boolean) -> Unit = { key, on ->
        // 勾上 = 追加到清单末尾（顺序就是扇形里的槽位顺序，见 FanItems 的类注释）
        FanItems.write(
            spec.key,
            if (on) FanItems.appended(items, key) else FanItems.without(items, key),
        )
    }

    // ── ④ 手工添加的状态（就地，不新开页面）───────────────────────────────
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var pickingApp by rememberSaveable { mutableStateOf(false) }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var manualPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var manualId by rememberSaveable { mutableStateOf("") }
    // 只查格式，**不查存在性**（见 customShortcutKey 的注释：设置界面查不到别的应用的快捷方式）
    val manualKey = remember(manualPkg, manualId) { customShortcutKey(manualPkg ?: "", manualId) }
    val pickableApps = remember(apps, appQuery) { filterApps(apps, appQuery, false, emptySet()) }
    val presetsOfPkg = remember(manualPkg) { manualPkg?.let { PRESETS[it] } ?: emptyList() }

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(8.dp)

        // ── 状态条：清单有几条、以及它在当前模式下**算不算数** ──
        if (!listTakesEffect(featureKey, manual) && chosen.isNotEmpty()) {
            kit.Banner(fpString("fan_shortcut.banner.disabled", chosen.size), FpEmphasis.WARNING)
            kit.VSpace(2.dp)
            kit.Paragraph(fpString("fan_shortcut.banner.auto"), FpEmphasis.MUTED)
        } else if (chosen.isNotEmpty()) {
            kit.Banner(fpString("fan_shortcut.banner.count", chosen.size), FpEmphasis.ACCENT)
        } else {
            kit.Banner(fpString("fan_shortcut.banner.none"), FpEmphasis.MUTED)
        }
        kit.VSpace(6.dp)

        kit.SearchField(
            value = query,
            placeholder = fpString("fan_shortcut.search.hint"),
            onValueChange = { query = it },
            onClear = { query = "" },
        )
        kit.VSpace(6.dp)

        kit.Card(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                // ── 目录读不到：一条短提示，而不是整页空态 ──
                // 这一页在目录读不到时**仍然可用**（复选清单来自配置、手工添加不依赖目录），
                // 画成整页空态会把「手工添加」这个唯一的出路一起挡掉
                if (shortcuts is ShortcutCatalog.Absent) {
                    item(key = "hdr-absent") {
                        kit.SectionHeader(fpString("fan_shortcut.absent"))
                        kit.Paragraph(fpString("fan_shortcut.absent.hint"), FpEmphasis.MUTED)
                    }
                }

                // ── ① 已选 ──
                if (selectedRows.isNotEmpty()) {
                    item(key = "hdr-sel") { kit.SectionHeader(fpString("fan_shortcut.section.selected")) }
                    items(selectedRows, key = { "sel-" + it.key }) { row ->
                        ShortcutCheckRow(row, icons, true, toggle)
                    }
                }

                // ── ② 常用 ──
                if (quickRows.isNotEmpty()) {
                    item(key = "hdr-quick") { kit.SectionHeader(fpString("fan_shortcut.section.quick")) }
                    items(quickRows, key = { "q-" + it.key }) { row ->
                        ShortcutCheckRow(row, icons, false, toggle)
                    }
                }

                // ── ③ 按应用 ──
                if (groups.isNotEmpty()) {
                    item(key = "hdr-all") { kit.SectionHeader(fpString("fan_shortcut.section.by_app")) }
                    for (group in groups) {
                        item(key = "g-" + group.packageName) {
                            kit.SectionHeader(installed[group.packageName] ?: group.packageName)
                        }
                        items(group.items, key = { "s-" + it.key }) { sc ->
                            val row = shortcutRow(
                                sc.key,
                                sc.packageName,
                                sc.id,
                                known,
                                installed,
                                withApp = false,
                            )
                            ShortcutCheckRow(row, icons, false, toggle)
                        }
                    }
                }

                // ── 三段都空：说清是「没有」还是「被筛掉了」 ──
                // 目录读不到时不说这一段：上面那两行已经讲了原因，再叠一句「没有可导入的」
                // 会让用户分不清是「真的没有」还是「没读到」（那是两种完全不同的处置）
                if (selectedRows.isEmpty() && quickRows.isEmpty() && groups.isEmpty() &&
                    shortcuts !is ShortcutCatalog.Absent
                ) {
                    item(key = "hdr-empty") {
                        val filtered = q.isNotEmpty()
                        kit.SectionHeader(
                            fpString(
                                if (filtered) "fan_shortcut.no_match" else "fan_shortcut.empty",
                            ),
                        )
                        kit.Paragraph(
                            fpString(
                                if (filtered) {
                                    "fan_shortcut.no_match.hint"
                                } else {
                                    "fan_shortcut.empty.hint"
                                },
                            ),
                            FpEmphasis.MUTED,
                        )
                    }
                }

                // ── ④ 手工添加 ──
                // 这一段**不配 SectionHeader**：SectionHeader 与它下面那行 ActionRow 的标题
                // 会是同一句话（「手工添加」写两遍），而这一行本身就是可点的标题 ——
                // 用户要的是「减少不必要的文本」，不是把每一段都凑齐同样的结构
                item(key = "hdr-manual") {
                    kit.ActionRow(
                        title = fpString("fan_shortcut.manual"),
                        summary = fpString("fan_shortcut.manual.sub"),
                        trailingText = fpString(
                            if (manualOpen) "fan_shortcut.collapse" else "fan_shortcut.expand",
                        ),
                        onClick = {
                            manualOpen = !manualOpen
                            if (!manualOpen) pickingApp = false
                        },
                    )
                }
                if (manualOpen) {
                    item(key = "m-hint") {
                        kit.Paragraph(fpString("fan_shortcut.manual.hint"), FpEmphasis.MUTED)
                    }
                    item(key = "m-app") {
                        kit.ActionRow(
                            title = fpString("fan_shortcut.manual.app"),
                            summary = null,
                            trailingText = manualPkg?.let { installed[it] ?: it }
                                ?: fpString("fan_shortcut.manual.app.none"),
                            onClick = { pickingApp = !pickingApp },
                        )
                    }
                    if (pickingApp) {
                        if (apps.isEmpty()) {
                            item(key = "m-app-none") {
                                kit.Paragraph(fpString("fan_shortcut.no_apps"), FpEmphasis.WARNING)
                            }
                        } else {
                            // 候选应用**必须是一个固定高度的内嵌列表**，不能直接铺进外层列表：
                            //  1. 铺进去的话，点「所属应用」时新插入的几十行会落在视口**下方**
                            //     （LazyColumn 把视口锚在原来那个 item 的 key 上）——
                            //     表现是「点了没反应」，用户会以为这一行坏了；
                            //  2. 高度必须**有界**，否则一个纵向可滚组件在外层（同样纵向可滚）
                            //     的无界约束下会被直接判为非法测量。
                            item(key = "m-app-picker") {
                                Column {
                                    kit.SearchField(
                                        value = appQuery,
                                        placeholder = fpString("fan_shortcut.manual.app.search"),
                                        onValueChange = { appQuery = it },
                                        onClear = { appQuery = "" },
                                    )
                                    LazyColumn(
                                        Modifier.fillMaxWidth().height(ManualAppListHeight),
                                    ) {
                                        items(
                                            pickableApps,
                                            key = { "mapp-" + it.packageName },
                                        ) { app ->
                                            kit.ActionRow(
                                                title = app.label,
                                                summary = app.packageName,
                                                trailingText = if (app.packageName == manualPkg) {
                                                    fpString("fan_shortcut.manual.app.chosen")
                                                } else {
                                                    null
                                                },
                                                leading = {
                                                    FanShortcutIcon(icons, app.packageName)
                                                },
                                                onClick = {
                                                    manualPkg = app.packageName
                                                    pickingApp = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "m-id") {
                        kit.SearchField(
                            value = manualId,
                            placeholder = fpString("fan_shortcut.manual.id.hint"),
                            onValueChange = { manualId = it },
                            onClear = { manualId = "" },
                        )
                    }
                    if (presetsOfPkg.isNotEmpty()) {
                        item(key = "m-preset-hint") {
                            kit.Paragraph(fpString("fan_shortcut.manual.presets"), FpEmphasis.MUTED)
                        }
                        item(key = "m-presets") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = FpSpace.l),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for (preset in presetsOfPkg) {
                                    kit.Button(
                                        text = fpString(preset.labelKey),
                                        onClick = { manualId = preset.id },
                                        // 高亮=「输入框里现在就是这个 id」，而不是「已经加进清单」——
                                        // 后者由下面那行状态文字说，两件事分开表达
                                        primary = manualId.trim() == preset.id,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "m-actions") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = FpSpace.l),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val joined = manualKey != null && manualKey in selected
                            kit.Button(
                                text = fpString("fan_shortcut.manual.add"),
                                onClick = {
                                    manualKey?.let { FanItems.write(spec.key, FanItems.appended(items, it)) }
                                },
                                primary = true,
                                enabled = manualKey != null && !joined,
                            )
                            kit.Button(
                                text = fpString("fan_shortcut.manual.remove"),
                                onClick = {
                                    manualKey?.let { FanItems.write(spec.key, FanItems.without(items, it)) }
                                },
                                enabled = joined,
                            )
                        }
                    }
                    item(key = "m-state") {
                        // 操作结果就在这一行（加完立刻变成「已在清单里」），不再单独弹一条提示
                        when {
                            manualPkg == null -> kit.Paragraph(
                                fpString("fan_shortcut.manual.need.app"),
                                FpEmphasis.MUTED,
                            )

                            manualKey == null -> kit.Paragraph(
                                fpString("fan_shortcut.manual.need.id"),
                                FpEmphasis.MUTED,
                            )

                            manualKey in selected -> kit.Paragraph(
                                fpString("fan_shortcut.manual.joined"),
                                FpEmphasis.ACCENT,
                            )

                            else -> kit.Paragraph(fpString("fan_shortcut.manual.pending"), FpEmphasis.MUTED)
                        }
                    }
                }
            }
        }

        // 图标入口不在这里：清单的图标能力（整体图标包 / 单项图标）统一收在
        // 「扇形里的项 → 图标」那一页。三个地方各挂一个入口，用户反而不知道哪个才算数
        kit.VSpace(16.dp)
    }
}

/**
 * 一行快捷方式。`summary` 由调用方决定格式（见页面注释里「两个副标题格式故意不同」）。
 */
private class ShortcutRow(
    val key: String,
    val packageName: String,
    val title: String,
    val summary: String,
) {
    /** 搜索匹配四个维度：标签、id、应用名、包名（排障时最有用的是后两个）。 */
    fun matches(query: String): Boolean =
        query.isEmpty() ||
            title.contains(query, ignoreCase = true) ||
            summary.contains(query, ignoreCase = true) ||
            key.contains(query, ignoreCase = true)
}

private fun shortcutRow(
    key: String,
    packageName: String,
    id: String,
    known: Map<String, ImportableShortcut>,
    installed: Map<String, String>,
    withApp: Boolean,
): ShortcutRow {
    // 目录里没有这条（手工添加的、目录还没投递、宿主已卸载）→ 标题退成 id。
    // 这不是错误：它照样是用户清单里的一条，照样能取消勾选
    val display = known[key]?.display ?: id
    val app = installed[packageName] ?: packageName
    return ShortcutRow(key, packageName, display, if (withApp) "$app · $id" else id)
}

/** 一行可勾选的快捷方式。图标是**宿主应用**的（见页面注释：快捷方式自己的图标拿不到）。 */
@Composable
private fun ShortcutCheckRow(
    row: ShortcutRow,
    icons: AppIconLoader,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    LocalFpKit.current.CheckRow(
        title = row.title,
        summary = row.summary,
        checked = checked,
        onCheckedChange = { on -> onToggle(row.key, on) },
        leading = { FanShortcutIcon(icons, row.packageName) },
    )
}

/**
 * 行首的应用图标。
 *
 * 实现在 [FpIconPlate] —— **全项目唯一一份「行首图标」**，所以「占位色只在没图时画」
 * 这条规则不会在这里被改出第二个版本（图标自身带透明区域时露出浅色底板，就是那个 bug）。
 * 两页共用（[FanIconOverridesScreen] 也用）：同一种「行首图标」在两页长得不一样
 * 会让人以为是两种东西。
 */
@Composable
internal fun FanShortcutIcon(loader: AppIconLoader, packageName: String) {
    var image by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, loader) { image = loader.load(packageName) }
    FpIconPlate(bitmap = image, size = FanIconSize, corner = FanIconRadius)
}

/** 一个应用的快捷方式分组。 */
private class ShortcutGroup(val packageName: String, val items: List<ImportableShortcut>)

/**
 * 按应用分组 + 搜索。
 *
 * 搜索匹配**快捷方式标签、id、应用名、包名**四个维度（用户要求）：排障时最有用的是
 * 包名 / id（`com.tencent.mm`），日常最有用的是标签（「扫一扫」）。
 *
 * 分组而不是一条平铺的长列表：200 多条按标签排序时「微信那三条在哪」要翻很久；
 * 按应用分组后同一个应用的快捷方式一定挨在一起。
 */
private fun groupShortcuts(
    all: List<ImportableShortcut>,
    installed: Map<String, String>,
    query: String,
): List<ShortcutGroup> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) {
        all
    } else {
        all.filter { sc ->
            val app = installed[sc.packageName] ?: sc.packageName
            sc.display.contains(q, ignoreCase = true) ||
                sc.id.contains(q, ignoreCase = true) ||
                app.contains(q, ignoreCase = true) ||
                sc.packageName.contains(q, ignoreCase = true)
        }
    }
    return filtered
        .groupBy { it.packageName }
        .toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER) { installed[it] ?: it })
        .map { (pkg, items) ->
            ShortcutGroup(
                pkg,
                items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.display }),
            )
        }
}

/**
 * 常见的四项目标。
 *
 * 这些 id 是**实测确认存在的**（含聚合支付那四个默认目标），
 * 但「这台机器上现在有没有」要**问后端**（[ShortcutCatalog] 就是后端用 `LauncherApps`
 * 查出来的结果），所以这里只做「在目录里挑出这四个」，查不到的那条**不显示** ——
 * 界面上放一个点了没反应的按钮比不放更糟。
 */
private val QUICK = listOf(
    "com.tencent.mm/launch_type_offline_wallet",
    "com.tencent.mm/launch_type_scan_qrcode",
    "com.eg.android.AlipayGphone/1002",
    "com.eg.android.AlipayGphone/1001",
)

/** 手工添加里「点一下填入」的一条预设。 */
private class Preset(val id: String, val labelKey: String)

/**
 * 已知可用的 id，按归属包分组（用户先选应用，所以只显示那个应用的预设）。
 *
 * 来源（全部是**本机实证**，不是猜的）：
 *  - 微信：`launch_type_offline_wallet` = 收付款、`launch_type_scan_qrcode` = 扫一扫；
 *  - 支付宝：hook 侧 `ShortcutRepository.ALIPAY_APP_ID` 的注释 ——
 *    `1002` = 收付款、`1003` = 收钱、`1001` = 扫一扫（id 取自本机
 *    `adb shell cmd shortcut get-shortcuts` 的输出）。
 *
 * 为什么**不**列 `1000`：它没有实证含义。给一个不知道是什么的 id 起名，
 * 等于把「猜」伪装成「已知可用」—— 与这一页那句「id 不校验」自相矛盾。
 */
private val PRESETS: Map<String, List<Preset>> = mapOf(
    "com.tencent.mm" to listOf(
        Preset("launch_type_scan_qrcode", "fan_shortcut.preset.scan"),
        Preset("launch_type_offline_wallet", "fan_shortcut.preset.code"),
    ),
    "com.eg.android.AlipayGphone" to listOf(
        Preset("1001", "fan_shortcut.preset.scan"),
        Preset("1002", "fan_shortcut.preset.code"),
        Preset("1003", "fan_shortcut.preset.collect"),
    ),
)

/** 40dp 图标用 12dp 圆角：与应用多选页、图标包页一致（同一个东西在三页必须长一样）。 */
internal val FanIconSize: Dp = 40.dp

internal val FanIconRadius: Dp = 12.dp

/** 手工添加里候选应用列表的高度。固定值是为了让嵌套的纵向滚动有界（见那段注释）。 */
private val ManualAppListHeight: Dp = 240.dp
