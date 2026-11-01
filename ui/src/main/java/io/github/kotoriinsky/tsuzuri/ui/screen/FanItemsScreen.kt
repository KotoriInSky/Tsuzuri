package io.github.kotoriinsky.tsuzuri.ui.screen

import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ListIconTools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.ReorderGrip
import io.github.kotoriinsky.tsuzuri.ui.kit.ReorderList
import io.github.kotoriinsky.tsuzuri.ui.kit.ReorderRemoveButton
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.peekAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.AppNames
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItemRef
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.IconOverridesStore
import io.github.kotoriinsky.tsuzuri.ui.state.iconPackKey
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.readShortcutCatalog

/**
 * 扇形里的项 —— **同一份清单的看 / 删 / 增 / 排序 / 换图标都只在这一页**。
 *
 * ## 为什么把「选择应用」和「导入快捷方式」合并到一页
 *
 * 它们在扇子里本来就是**同一份清单、同一类项**：一个槽位既可能是应用也可能是
 * 快捷方式，位置只由序号决定。分成两页的代价是用户看不到「我现在的清单长什么样」——
 * 只能各自看到一个子集，而顺序（= 槽位）这件事两页都表达不了。
 * 参考实现（HyperCeiler、HyperGesture）没有任何一个把同一类项拆到两页。
 *
 * 两个**增**的子流程仍然是独立页面（[Dest.AppPicker] / [Dest.ShortcutImport]）：
 * 两百个应用的多选、以及带搜索的快捷方式目录，和拖拽排序挤在一页里会互相打架
 * （一个要长按、一个要滚动，手势会抢）。
 *
 * ## 顺序就是数据
 *
 * 清单是按用户排的顺序存的（`OrderedItems`，一条一行），**第 1 项就在扇子的第 1 个槽位**
 * （`FanMetrics.place(index)`）。所以这一页的每一次改动都走 [FanItems]（它保证
 * 「编码一致 + 空清单删键」两条契约），并且改完立刻生效（配置通道）——
 * 拖一下位置就要能立刻在扇子上看到，不许要求重启进程。
 *
 * ## 一页三份清单
 *
 * 扇形项目、侧滑收藏、分享置顶共用这一页，差别全部来自 [PrefSpec.OrderedList] 的声明，
 * 这里不按特性键判断。
 *
 * ## 行上的操作都看得见
 *
 *  - **行首 ⊖** → 移除 + 撤销；左右滑是同一条路径的快捷手势；
 *  - **点行体**（有箭头时）→ 直达这一项的单项修改页（带着 `focusKey`，对面那一行就地展开
 *    并滚进视口）；图标由宿主画的清单没有箭头，行也不可点；
 *  - **行尾把手** → 排序（手势挂在整行，把手是它的可见提示）。
 *
 * 换过图标的项由图标上的小角标示意。
 *
 * 上移 / 下移从视觉按钮层转成了**读屏 customActions**（挂在行节点上）：纯拖拽对
 * 读屏用户等于没有排序功能，所以这条等价的路不能少，只是不再占视觉层。
 *
 * ⚠️ ⊖ 与滑删走**同一条**代码路径（[remove]）：一份撤销、一份行为，
 * 不会在入口之间漂移。破坏性操作不做二次确认，靠「能撤销」兜住。
 *
 * ## 门槛状态：「加了没反应」的成因必须摆在眼前
 *
 * `items` 的 `minCount` 大于 1 时，**低于门槛的清单不会生效**（扇子仍走自动挑选），
 * 而写盘与界面都是成功的。所以顶部常驻一条「已选 N 项，还差 M 项才生效」——
 * 这一条来自用户实测反馈。
 *
 * ## 这一页只管顺序
 *
 * 整体图标包在「外观」，单项修改从行点进去，增项走「添加」。
 */
@Composable
fun FanItemsScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
    onNavigate: (Dest) -> Unit,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val context = LocalContext.current

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val items = FanItems.read(prefs, spec)

    // ── 一次性迁移：新键还没写过、老键里有内容 ───────────────────────────
    // 「只在升级那一刻承认它一次」（与 AppSettings.readHistory 同一个写法）。
    // ⚠️ **调用本身不能删**：迁移是真的在这个调用里发生的，去掉它老配置就丢了。
    // 删掉的只是那条横幅 —— 用户拍板（2026-09-18）：清单页的状态区只留
    // 「撤销」与「门槛」，迁移说明不在这个页面上占地方（顺序本来就是可改的，
    // 迁移只是给了个初始值，不是需要用户决策的事）
    LaunchedEffect(spec.key) { FanItems.migrate(ConfigStore.prefs(), spec) }

    var catalog by remember { mutableStateOf<AppCatalog>(peekAppCatalog() ?: AppCatalog.Loading) }
    LaunchedEffect(context) { catalog = loadAppCatalog(context) }
    val installed = remember(catalog) { FanItemRef.installedLabels(catalog) }

    val shortcutCatalog = readShortcutCatalog()
    val shortcutLabels = remember(shortcutCatalog) { FanItemRef.shortcutLabels(shortcutCatalog) }

    val iconPx = with(LocalDensity.current) { FanIconSize.roundToPx() }
    val icons = remember(context, iconPx) { AppIconLoader(context, iconPx) }
    val overrides = IconOverridesStore.observe(prefs, featureKey)

    val refs = remember(items) { FanItemRef.of(items) }

    /** 被删掉的那一项 + 它原来的下标（撤销要把它插回原处，顺序才不会变）。 */
    var removed by remember { mutableStateOf<Pair<String, Int>?>(null) }

    // 读屏 customActions 的文案在组合里取好（fpString 是 @Composable，
    // 不能进 semantics 的 action lambda）。全部行共用同一组，不必每行取一遍
    val moveUpLabel = fpString("fan_items.up")
    val moveDownLabel = fpString("fan_items.down")
    val removeLabel = fpString("fan_items.remove")

    /**
     * 移除一项 —— ⊖ 与滑删**共用这一条路径**，
     * 所以撤销提示只有一份，行为也不会在两个入口之间漂移。
     *
     * 破坏性操作不做二次确认，靠「能撤销」兜住：
     * 记下键与**原下标**，撤销时插回原处，顺序才不会变。
     */
    val remove: (key: String, index: Int) -> Unit = { key, index ->
        FanItems.write(spec.key, FanItems.without(items, key))
        removed = key to index
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(FpSpace.s)

        // ── 增：放在清单上方，清单再长也不用滚到底才能加──
        kit.Card {
            kit.ActionRow(
                title = fpString("add_item.title"),
                summary = null,
                trailingText = null,
                onClick = { onNavigate(Dest.AppPicker(spec)) },
                navigates = true,
            )
        }
        kit.VSpace(FpSpace.s)

        // ── 状态区：只留两条（用户拍板 2026-09-18）───────────────────────
        //  ① 门槛 —— 「加了没反应」的真实成因，不说用户只会认为功能坏了；
        //  ② 撤销 —— 破坏性操作不弹二次确认，这条是唯一的后悔路。
        // 其余（迁移说明、分页、auto 模式）都不在这个页面上占地方：
        // 迁移只是给个初始值、顺序本来就能改；auto 模式在详情页那一行已经写着。
        if (items.isNotEmpty() && items.size < spec.minCount) {
            // 低于 minCount 时写盘成功、界面上也加进去了，但扇子仍走自动挑选。
            // 门槛从 Catalog 那条声明（`PrefSpec.OrderedList.minCount`）读，不写死 3
            kit.Banner(
                fpString("fan_items.below_min", spec.minCount - items.size),
                FpEmphasis.WARNING,
            )
            kit.VSpace(4.dp)
        }

        removed?.let { (key, index) ->
            kit.Banner(
                text = fpString("fan_items.removed", labelOf(key, installed, shortcutLabels)),
                emphasis = FpEmphasis.MUTED,
                actionLabel = fpString("fan_items.undo"),
                // 插回原下标（不是追加到末尾）：撤销就该回到原样，包括顺序
                onAction = {
                    FanItems.write(spec.key, FanItems.inserted(items, index, key))
                    removed = null
                },
            )
            kit.VSpace(4.dp)
        }

        kit.SectionHeader(fpString("fan_items.section"))
        // 第 N 项落在扇子的哪一页、哪一圈（core FanSlots，与真机分页 / 排圈同一规则）。
        // 只在每段的第一项右侧标一次，拖动时按位置实时重算 —— 拖到哪一段一眼可见
        val slots = remember(items.size, prefs, featureKey) {
            if (featureKey != Catalog.CORNER_SMALL_WINDOW) emptyList()
            else {
                fun num(n: String) = FanItems.siblingNumber(prefs, featureKey, n)
                val mode = prefs.rawString(io.github.kotoriinsky.tsuzuri.core.Keys.pref(featureKey, "fan_layout_mode"), "smart") ?: "smart"
                io.github.kotoriinsky.tsuzuri.core.geometry.FanSlots.of(
                    total = items.size,
                    perPage = (num("fan_max_targets") ?: 17f).toInt(),
                    layoutMode = mode,
                    inner = (num("fan_custom_inner_count") ?: 7f).toInt(),
                    middle = (num("fan_custom_middle_count") ?: 8f).toInt(),
                )
            }
        }

        if (refs.isEmpty()) {
            FpEmptyState(
                titleKey = "fan_items.empty",
                hintKey = "fan_items.empty.hint",
                modifier = Modifier.weight(1f),
            )
        } else {
            kit.Card(Modifier.weight(1f)) {
                ReorderList(
                    keys = items,
                    onMove = { from, to ->
                        FanItems.write(spec.key, FanItems.moved(items, from, to))
                    },
                    // 只有一项时拖不了：不接排序手势，免得长按半天什么都不动
                    enabled = items.size > 1,
                    // 左右滑删除。⚠️ 它与行首 ⊖ 走**同一条**撤销路径
                    // （写盘 → 记下键与原下标 → 状态条给「撤销」），只是入口不同 ——
                    // 破坏性操作不做二次确认，靠「能撤销」兜住
                    onDelete = { index -> remove(refs[index].key, index) },
                ) { index, dragging ->
                    val ref = refs[index]
                    // 段落标签放在行上方：行尾已有箭头和把手，再放右值就挤了
                    slots.getOrNull(index)?.let { slot -> positionLabel(slot) }?.let { label ->
                        Box(Modifier.padding(start = FpSpace.l, top = FpSpace.m)) {
                            kit.Body(label, FpEmphasis.MUTED, FpTextStyle.CAPTION)
                        }
                    }
                    FanItemRow(
                        ref = ref,
                        label = ref.label(installed, shortcutLabels),
                        index = index,
                        total = refs.size,
                        dragging = dragging,
                        overridden = overrides[ref.key] != null,
                        icons = icons,
                        moveUpLabel = moveUpLabel,
                        moveDownLabel = moveDownLabel,
                        removeLabel = removeLabel,
                        // 一点 = 给这一项换图标：带着键过去，对面就地展开那一行
                        onOpen = when (spec.iconTools) {
                            ListIconTools.ICON -> ({ onNavigate(Dest.FanIconOverrides(spec, focusKey = ref.key)) })
                            ListIconTools.NAME_AND_ICON -> ({ onNavigate(Dest.SideIndexOverrides(spec, focusKey = ref.packageName)) })
                            ListIconTools.NONE -> null
                        },
                        onMoveUp = { FanItems.write(spec.key, FanItems.moved(items, index, index - 1)) },
                        onMoveDown = { FanItems.write(spec.key, FanItems.moved(items, index, index + 1)) },
                        onRemove = { remove(ref.key, index) },
                    )
                }
            }
        }

        kit.VSpace(FpSpace.s)

        // 置顶清单没加进来的项不会消失，这句话不说就看不出来
        if (spec.pinnedFirst && refs.isNotEmpty()) {
            kit.Paragraph(fpString("fan_items.pinned_rest"), FpEmphasis.MUTED, FpTextStyle.CAPTION)
        }
        kit.VSpace(FpSpace.l)
    }
}

/** 清单里一项的标题（查不到就退回键名 —— 不隐藏它：它照样能删能拖）。 */
private fun labelOf(key: String, installed: Map<String, String>, shortcuts: Map<String, String>): String =
    FanItemRef(key).label(installed, shortcuts)

/**
 * 清单里的一行：**⊖ + 行体 + 把手**。
 *
 * 行体点击 = 直达这一项的换图标（[onOpen] 为 `null` 时不可点、无箭头）；
 * 排序手势挂整行（把手是它的可见提示）。上移 / 下移挂在行的读屏 customActions 里 ——
 * 纯拖拽对读屏用户等于没有排序，这条等价的路不能少，只是不占视觉层。
 *
 * ⚠️ customActions 必须挂在**可点击的节点**上（`ActionRow` 的 modifier 直通参
 * 先于内部点击修饰符，两者同节点），否则读屏聚焦行时看不到这些操作。
 */
@Composable
private fun FanItemRow(
    ref: FanItemRef,
    label: String,
    index: Int,
    total: Int,
    dragging: Boolean,
    overridden: Boolean,
    icons: AppIconLoader,
    moveUpLabel: String,
    moveDownLabel: String,
    removeLabel: String,
    onOpen: (() -> Unit)?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val kit = LocalFpKit.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ReorderRemoveButton(removeLabel, onRemove, Modifier.padding(start = FpSpace.s))
        kit.ActionRow(
            title = label,
            summary = null,
            trailingText = null,
            // 拖拽期间禁用行体点击：长按之后抬手会同时触发「拖拽」与「点击」，
            // 禁用会在手势中途重启 pointerInput，把那次点击取消掉
            onClick = if (dragging) null else onOpen,
            enabled = !dragging,
            navigates = onOpen != null,
            leading = { FanItemIcon(icons, ref.packageName, overridden) },
            modifier = Modifier
                .weight(1f)
                .semantics {
                    customActions = buildList {
                        if (index > 0) {
                            add(CustomAccessibilityAction(moveUpLabel) { onMoveUp(); true })
                        }
                        if (index < total - 1) {
                            add(CustomAccessibilityAction(moveDownLabel) { onMoveDown(); true })
                        }
                    }
                },
        )
        // 排序把手。`total <= 1` 时拖不动，画成不可用色但仍占位（理由见它的 KDoc）
        ReorderGrip(enabled = total > 1, modifier = Modifier.padding(end = FpSpace.s))
    }
}

/**
 * 行首的应用图标；`overridden` = 这一项换过图标，画一个小角标示意。
 *
 * 角标只回答「动没动过」这一个布尔问题 —— 「动成了什么样」在单项图标那一页有
 * 完整的一列（生效的来源、恢复默认都在那里），不必在清单行里复述。
 *
 * 底板走 [FanShortcutIcon]（内部即 `FpIconPlate`）：**全项目唯一一份「行首图标」**，
 * 「占位色只在没图时画」这条规则不会在这里被改出第二个版本。
 */
@Composable
private fun FanItemIcon(loader: AppIconLoader, packageName: String, overridden: Boolean) {
    if (!overridden) {
        FanShortcutIcon(loader, packageName)
        return
    }
    val colors = LocalFpKit.current.colors()
    Box {
        FanShortcutIcon(loader, packageName)
        // 小圆点压在图标右上角：accent 圆点 + 一圈表面色描边，
        // 让它在浅色图标上不消失、深色图标上不糊住
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 2.dp, y = (-2).dp)
                .size(9.dp)
                .border(1.5.dp, colors.surface, CircleShape)
                .background(colors.accent, CircleShape),
        )
    }
}

/** 段首标签：多页时第一圈的开头写「第 N 页」，同页里换圈写「内圈 / 中圈 / 外圈」；不是段首返回 `null`。 */
@Composable
private fun positionLabel(slot: io.github.kotoriinsky.tsuzuri.core.geometry.FanSlots.Slot): String? {
    if (!slot.startsSection) return null
    val ring = when {
        slot.rings <= 1 -> null
        slot.ring == 0 -> fpString("fan_items.ring.inner")
        slot.ring == slot.rings - 1 -> fpString("fan_items.ring.outer")
        else -> fpString("fan_items.ring.middle")
    }
    val page = if (slot.pages > 1 && slot.ring == 0) fpString("fan_items.page", slot.page + 1) else null
    return listOfNotNull(page, ring).joinToString(" · ").ifEmpty { null }
}
