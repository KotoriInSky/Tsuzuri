package io.github.kotoriinsky.tsuzuri.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.ListIconTools
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.geometry.FanSlots
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.FpPopupAction
import io.github.kotoriinsky.tsuzuri.ui.kit.FpPopupMenu
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.ReorderGrid
import io.github.kotoriinsky.tsuzuri.ui.kit.fpRowFeedback
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItemRef
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.IconOverridesStore
import io.github.kotoriinsky.tsuzuri.ui.state.IconPackPreviewLoader
import io.github.kotoriinsky.tsuzuri.ui.state.ShortcutIconStore
import io.github.kotoriinsky.tsuzuri.ui.state.SideIndexCustom
import io.github.kotoriinsky.tsuzuri.ui.state.iconPackKey
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.peekAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.readShortcutCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 顶栏右上角 `+` 按钮（挂在 `AppRoot.DrillPage`）：
 *  - 仅应用清单（`appsOnly = true`）→ 一步直达选择应用；
 *  - 多类型清单（`appsOnly = false`）→ 弹出原生浮层菜单（应用 / 快捷方式与页面 / 链接 / 活动）。
 */
@Composable
internal fun FanItemsAddAction(
    spec: PrefSpec.OrderedList,
    onNavigate: (Dest) -> Unit,
) {
    val kit = LocalFpKit.current
    val feedback = fpRowFeedback()
    var open by remember { mutableStateOf(false) }

    val appsLabel = fpString("fan_items.add.apps")
    val shortcutsLabel = fpString("fan_items.add.shortcuts")
    val linkLabel = fpString("fan_items.add.link")
    val activityLabel = fpString("fan_items.add.activity")

    Box {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = fpString("add_item.title"),
            tint = kit.colors().onSurface,
            modifier = Modifier
                .clip(CircleShape)
                .clickable {
                    feedback()
                    if (spec.appsOnly) onNavigate(Dest.AppPicker(spec)) else open = true
                }
                .padding(12.dp),
        )
        if (!spec.appsOnly) {
            FpPopupMenu(
                expanded = open,
                alignEnd = true,
                actions = listOf(
                    FpPopupAction(appsLabel) { onNavigate(Dest.AppPicker(spec)) },
                    FpPopupAction(shortcutsLabel) { onNavigate(Dest.AddItem(spec)) },
                    FpPopupAction(linkLabel) { onNavigate(Dest.LinkImport(spec)) },
                    FpPopupAction(activityLabel) { onNavigate(Dest.ActivityImport(spec)) },
                ),
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * 有序清单页（扇形小窗快捷清单 / 侧滑索引收藏 / 分享面板置顶共用）。
 *
 * 采用 4 列图标网格：
 *  - 顶部右上角 `+` 按钮（[FanItemsAddAction]）按清单能力分流增项；
 *  - **长按拖动** → 2D 拖拽排序，其余图标平滑让位，松手即刻落盘；
 *  - **长按松手** → 按清单能力（[PrefSpec.OrderedList.iconTools]）弹出 MiuiX 原生上下文菜单
 *    （更换图标 / 名称与图标 / 移至最前 / 移除）；
 *  - **单击** → 支持改图标/改名的清单直达对应编辑页，无图标工具的清单打开同一菜单；
 *  - 移除走统一撤销路径（顶部横幅），读屏保留 `customActions`。
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

    // 一次性迁移：新键还没写过、老键里有内容（不可删）
    LaunchedEffect(spec.key) { FanItems.migrate(ConfigStore.prefs(), spec) }

    var catalog by remember { mutableStateOf<AppCatalog>(peekAppCatalog() ?: AppCatalog.Loading) }
    LaunchedEffect(context) { catalog = loadAppCatalog(context) }
    val installed = remember(catalog) { FanItemRef.installedLabels(catalog) }

    val shortcutCatalog = readShortcutCatalog()
    val shortcutLabels = remember(shortcutCatalog) { FanItemRef.shortcutLabels(shortcutCatalog) }

    val iconPx = with(LocalDensity.current) { GridIconSize.roundToPx() }
    val icons = remember(context, iconPx) { AppIconLoader(context, iconPx) }
    val previews = remember(context, iconPx) { IconPackPreviewLoader(context, iconPx) }
    val overrides = IconOverridesStore.observe(prefs, featureKey)
    val packName = prefs.rawStringSet(iconPackKey(featureKey)).sorted().firstOrNull()
    val customNames = remember(prefs, featureKey, spec.iconTools) {
        if (spec.iconTools == ListIconTools.NAME_AND_ICON) SideIndexCustom.readNames(prefs, featureKey)
        else emptyMap()
    }

    val refs = remember(items) { FanItemRef.of(items) }

    /** 被删掉的那一项 + 原下标（撤销插回原位）。 */
    var removed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    /** 当前弹出长按菜单的项下标；-1 = 无。 */
    var menuIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(items) {
        if (menuIndex !in items.indices) menuIndex = -1
    }

    val moveUpLabel = fpString("fan_items.up")
    val moveDownLabel = fpString("fan_items.down")
    val removeLabel = fpString("fan_items.remove")
    val pinFirstLabel = fpString("fan_items.menu.pin_first")
    val editIconLabel = fpString("fan_items.menu.icon")
    val editNameIconLabel = fpString("fan_items.menu.name_icon")

    val remove: (key: String, index: Int) -> Unit = { key, index ->
        menuIndex = -1
        FanItems.write(spec.key, FanItems.without(items, key))
        removed = key to index
    }

    val openEditor: (FanItemRef) -> Unit = { ref ->
        when (spec.iconTools) {
            ListIconTools.ICON -> onNavigate(Dest.FanIconOverrides(spec, focusKey = ref.key))
            ListIconTools.NAME_AND_ICON -> onNavigate(Dest.SideIndexOverrides(spec, focusKey = ref.packageName))
            ListIconTools.NONE -> Unit
        }
    }

    // 扇形的分页 / 分圈段标
    val slots = remember(items.size, prefs, featureKey) {
        if (featureKey != Catalog.CORNER_SMALL_WINDOW) emptyList()
        else {
            fun num(n: String) = FanItems.siblingNumber(prefs, featureKey, n)
            val mode = prefs.rawString(Keys.pref(featureKey, "fan_layout_mode"), "smart") ?: "smart"
            FanSlots.of(
                total = items.size,
                perPage = (num("fan_max_targets") ?: 17f).toInt(),
                layoutMode = mode,
                inner = (num("fan_custom_inner_count") ?: 7f).toInt(),
                middle = (num("fan_custom_middle_count") ?: 8f).toInt(),
            )
        }
    }
    val sectionLabels: List<String?> = List(refs.size) { idx ->
        slots.getOrNull(idx)?.let { positionLabel(it) }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(FpSpace.s)

        if (items.isNotEmpty() && items.size < spec.minCount) {
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
                onAction = {
                    FanItems.write(spec.key, FanItems.inserted(items, index, key))
                    removed = null
                },
            )
            kit.VSpace(4.dp)
        }

        if (refs.isEmpty()) {
            FpEmptyState(
                titleKey = "fan_items.empty",
                hintKey = "fan_items.empty.hint",
                modifier = Modifier.weight(1f),
            )
        } else {
            kit.Card(Modifier.weight(1f)) {
                ReorderGrid(
                    keys = items,
                    sectionLabels = sectionLabels,
                    onMove = { from, to ->
                        menuIndex = -1
                        FanItems.write(spec.key, FanItems.moved(items, from, to))
                    },
                    onTap = { idx ->
                        val ref = refs.getOrNull(idx) ?: return@ReorderGrid
                        if (spec.iconTools != ListIconTools.NONE) {
                            openEditor(ref)
                        } else {
                            menuIndex = idx
                        }
                    },
                    onLongPressMenu = { idx ->
                        if (idx in refs.indices) menuIndex = idx
                    },
                    enabled = items.size > 1,
                    modifier = Modifier.fillMaxSize(),
                ) { index, colIdx, _, _ ->
                    val ref = refs[index]
                    val displayLabel = customNames[ref.packageName]
                        ?: ref.label(installed, shortcutLabels)
                    val overridden = overrides[ref.key] != null ||
                        (spec.iconTools == ListIconTools.NAME_AND_ICON && customNames[ref.packageName] != null)

                    val actions = buildList {
                        if (spec.iconTools == ListIconTools.ICON) {
                            add(FpPopupAction(editIconLabel) { openEditor(ref) })
                        } else if (spec.iconTools == ListIconTools.NAME_AND_ICON) {
                            add(FpPopupAction(editNameIconLabel) { openEditor(ref) })
                        }
                        if (index > 0) {
                            add(FpPopupAction(pinFirstLabel) {
                                FanItems.write(spec.key, FanItems.moved(items, index, 0))
                            })
                        }
                        add(FpPopupAction(removeLabel, destructive = true) {
                            remove(ref.key, index)
                        })
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = displayLabel
                                customActions = buildList {
                                    if (index > 0) {
                                        add(CustomAccessibilityAction(moveUpLabel) {
                                            FanItems.write(spec.key, FanItems.moved(items, index, index - 1))
                                            true
                                        })
                                    }
                                    if (index < refs.size - 1) {
                                        add(CustomAccessibilityAction(moveDownLabel) {
                                            FanItems.write(spec.key, FanItems.moved(items, index, index + 1))
                                            true
                                        })
                                    }
                                    add(CustomAccessibilityAction(removeLabel) {
                                        remove(ref.key, index)
                                        true
                                    })
                                }
                            },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        FanGridCell(
                            ref = ref,
                            label = displayLabel,
                            entry = overrides[ref.key],
                            packName = packName,
                            overridden = overridden,
                            icons = icons,
                            previews = previews,
                        )

                        FpPopupMenu(
                            expanded = menuIndex == index,
                            alignEnd = colIdx >= 2,
                            actions = actions,
                            onDismiss = { menuIndex = -1 },
                        )
                    }
                }
            }
        }

        kit.VSpace(FpSpace.s)

        if (refs.isNotEmpty()) {
            kit.Paragraph(
                if (spec.pinnedFirst) fpString("fan_items.pinned_rest") else fpString("fan_items.grid_hint"),
                FpEmphasis.MUTED,
                FpTextStyle.CAPTION,
            )
        }
        kit.VSpace(FpSpace.l)
    }
}

private fun labelOf(key: String, installed: Map<String, String>, shortcuts: Map<String, String>): String =
    FanItemRef(key).label(installed, shortcuts)

@Composable
private fun FanGridCell(
    ref: FanItemRef,
    label: String,
    entry: IconOverridesStore.Entry?,
    packName: String?,
    overridden: Boolean,
    icons: AppIconLoader,
    previews: IconPackPreviewLoader,
) {
    val colors = LocalFpKit.current.colors()
    val context = LocalContext.current
    var image by remember(ref.key, entry, packName) { mutableStateOf<ImageBitmap?>(null) }
    val iconsRev = ShortcutIconStore.revision(context)
    LaunchedEffect(ref.key, entry, packName, iconsRev) {
        image = when (entry?.kind) {
            IconOverridesStore.KIND_FILE -> withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(
                        File(File(context.filesDir, "icon_overrides"), IconOverridesStore.nameOf(entry.value)).path,
                    )?.asImageBitmap()
                }.getOrNull()
            }
            IconOverridesStore.KIND_PACK -> previews.loadByName(
                entry.value.substringBefore('/'),
                entry.value.substringAfter('/'),
            )
            else -> null
        } ?: if (ref.isShortcut) {
            withContext(Dispatchers.IO) { ShortcutIconStore.load(context, ref.key)?.asImageBitmap() }
                ?: icons.load(ref.packageName)
        } else packName?.let { pack ->
            previews.load(pack, ref.packageName)
        } ?: icons.load(ref.packageName)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 6.dp, start = 2.dp, end = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box {
            FpIconPlate(bitmap = image, size = GridIconSize, corner = GridIconRadius)
            if (overridden) {
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = colors.onSurface,
            fontSize = 11.5.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 段首标签：多页时第一圈的开头写「第 N 页」，同页里换圈写「内圈 / 中圈 / 外圈」；不是段首返回 `null`。 */
@Composable
private fun positionLabel(slot: FanSlots.Slot): String? {
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

private val GridIconSize = 48.dp
private val GridIconRadius = 12.dp
