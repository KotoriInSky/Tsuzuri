package io.github.kotoriinsky.tsuzuri.ui.screen

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.LetterIndex
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpErrorState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.FpLoadingState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconCorner
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconSize
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBar
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBarMinItems
import io.github.kotoriinsky.tsuzuri.ui.kit.LetterBubble
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.fpRowFeedback
import io.github.kotoriinsky.tsuzuri.ui.kit.letterBarGutter
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.AppNames
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.IconOverridesStore
import io.github.kotoriinsky.tsuzuri.ui.state.IconPackPreviewLoader
import io.github.kotoriinsky.tsuzuri.ui.state.InstalledApp
import io.github.kotoriinsky.tsuzuri.ui.state.PinyinIndex
import io.github.kotoriinsky.tsuzuri.ui.state.SideIndexCustom
import io.github.kotoriinsky.tsuzuri.ui.state.filterApps
import io.github.kotoriinsky.tsuzuri.ui.state.iconPackKey
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.peekAppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 侧滑索引的单应用名称与图标覆盖（对齐 [FanIconOverridesScreen]）：
 * - 已自定义的项置顶单独分段，其余按拼音 A–Z 分组并配右侧 [LetterBar] 快速定位；
 * - 行首直接渲染当前生效图标（单项图片 > 单项图标包 > 整体图标包 > 自带图标），
 *   不再堆叠常驻说明横幅、截断包名和冗长右值；
 * - 恢复默认类操作标为警示色。
 */
@Composable
fun SideIndexOverridesScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
    onNavigate: (Dest) -> Unit,
    /** 从收藏格点进来时带着包名：那一行进页就展开并滚进视区。 */
    focusKey: String? = null,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val context = LocalContext.current
    val feedback = fpRowFeedback()

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val names = remember(prefs) { SideIndexCustom.readNames(prefs, featureKey) }
    val overrides = IconOverridesStore.observe(prefs, featureKey)
    val packName = prefs.rawStringSet(iconPackKey(featureKey)).sorted().firstOrNull()

    var catalog by remember { mutableStateOf<AppCatalog>(peekAppCatalog() ?: AppCatalog.Loading) }
    var pinyin by remember {
        mutableStateOf((peekAppCatalog() as? AppCatalog.Ready)?.let { PinyinIndex.peek(it.apps) })
    }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(context, attempt) {
        val loaded = loadAppCatalog(context)
        pinyin = if (loaded is AppCatalog.Ready) PinyinIndex.build(loaded.apps) else null
        catalog = loaded
    }
    val apps = pinyin?.apps ?: (catalog as? AppCatalog.Ready)?.apps.orEmpty()

    val iconPx = with(LocalDensity.current) { FpRowIconSize.roundToPx() }
    val icons = remember(context, iconPx) { AppIconLoader(context, iconPx) }
    val previews = remember(context, iconPx) { IconPackPreviewLoader(context, iconPx) }
    val appNames = remember(context) { AppNames(context) }
    var packLabel by remember(packName) { mutableStateOf(packName) }
    LaunchedEffect(packName) { packLabel = packName?.let { appNames.label(it) } }

    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(apps, query) { filterApps(apps, query, onlySelected = false, selected = emptySet()) }
    val customApps = remember(visible, names, overrides) {
        visible.filter { names[it.packageName] != null || overrides[it.packageName] != null }
    }
    val otherApps = remember(visible, names, overrides) {
        visible.filter { names[it.packageName] == null && overrides[it.packageName] == null }
    }
    val groups = remember(otherApps, pinyin) {
        pinyin?.let { ix -> LetterIndex.group(otherApps, { it.label }, ix::letterOf) }.orEmpty()
    }
    val rows = remember(groups) { LetterIndex.rows(groups) }
    val barLetters = remember(groups) { groups.map { it.letter } }
    val showBar = barLetters.size >= 2 && otherApps.size >= LetterBarMinItems
    val customOffset = if (customApps.isEmpty()) 0 else 1 + customApps.size

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeLetter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(focusKey, customApps, groups, otherApps) {
        if (focusKey == null) return@LaunchedEffect
        val ci = customApps.indexOfFirst { it.packageName == focusKey }
        if (ci >= 0) {
            listState.scrollToItem(1 + ci)
            return@LaunchedEffect
        }
        var idx = customOffset
        if (groups.isEmpty()) {
            val oi = otherApps.indexOfFirst { it.packageName == focusKey }
            if (oi >= 0) listState.scrollToItem(idx + oi)
        } else {
            for (g in groups) {
                idx += 1
                val gi = g.items.indexOfFirst { it.packageName == focusKey }
                if (gi >= 0) {
                    listState.scrollToItem(idx + gi)
                    return@LaunchedEffect
                }
                idx += g.items.size
            }
        }
    }

    var result by remember { mutableStateOf<String?>(null) }
    val okText = fpString("fan_icon.image.ok")
    val nameSavedText = fpString("side_index_override.name.saved")

    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDraft by rememberSaveable { mutableStateOf("") }

    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var cropUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || pending == null) {
            pending = null
            return@rememberLauncherForActivityResult
        }
        cropUri = uri
    }

    val cropping = cropUri
    if (cropping != null) {
        val target = pending
        IconCropScreen(
            uri = cropping,
            padding = padding,
            onCancel = {
                cropUri = null
                pending = null
            },
            onSaved = {
                cropUri = null
                pending = null
                result = okText
            },
            save = { crop ->
                if (target == null) {
                    IconOverridesStore.SaveResult.Failed(
                        IconOverridesStore.FailReason.WRITE,
                        "没有待设置的应用（pending 为空）",
                    )
                } else {
                    val r = IconOverridesStore.importImage(context, target, cropping, crop)
                    if (r is IconOverridesStore.SaveResult.Ok) {
                        val written = IconOverridesStore.put(
                            featureKey,
                            target,
                            IconOverridesStore.Entry(IconOverridesStore.KIND_FILE, r.value),
                        )
                        if (written == ConfigStore.WriteResult.FAILED) {
                            return@IconCropScreen IconOverridesStore.SaveResult.Failed(
                                IconOverridesStore.FailReason.WRITE,
                                "图标配置未保存",
                            )
                        }
                    }
                    r
                }
            },
        )
        return
    }

    val renderRow: @Composable (InstalledApp) -> Unit = { app ->
        OverrideRow(
            app = app,
            initiallyOpen = app.packageName == focusKey,
            name = names[app.packageName],
            entry = overrides[app.packageName],
            packName = packName,
            packLabel = packLabel,
            icons = icons,
            previews = previews,
            editing = editing == app.packageName,
            draft = nameDraft,
            onToggleEdit = {
                if (editing == app.packageName) {
                    editing = null
                } else {
                    editing = app.packageName
                    nameDraft = names[app.packageName] ?: app.label
                }
            },
            onDraftChange = { nameDraft = it },
            onSaveName = {
                SideIndexCustom.writeName(featureKey, app.packageName, nameDraft)
                editing = null
                result = nameSavedText
            },
            onResetName = {
                SideIndexCustom.removeName(featureKey, app.packageName)
                editing = null
            },
            onPickFromPack = {
                onNavigate(
                    Dest.IconPackDrawable(
                        spec,
                        app.packageName,
                        names[app.packageName] ?: app.label,
                    ),
                )
            },
            onPickImage = {
                result = null
                pending = app.packageName
                pickImage.launch("image/*")
            },
            onResetIcon = {
                IconOverridesStore.remove(featureKey, app.packageName, context)
            },
            onResetAll = {
                SideIndexCustom.removeName(featureKey, app.packageName)
                IconOverridesStore.remove(featureKey, app.packageName, context)
                editing = null
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(FpSpace.s)
        result?.let {
            kit.Banner(it, FpEmphasis.ACCENT)
            kit.VSpace(4.dp)
        }
        packLabel?.let {
            kit.Paragraph(fpString("fan_icon.follow_pack", it), FpEmphasis.MUTED)
            kit.VSpace(4.dp)
        }

        kit.SearchField(
            value = query,
            placeholder = fpString("app_picker.search.hint"),
            onValueChange = { query = it },
            onClear = { query = "" },
        )
        kit.VSpace(6.dp)

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

            is AppCatalog.Ready -> if (visible.isEmpty()) {
                FpEmptyState(
                    titleKey = "app_picker.empty",
                    hintKey = "app_picker.empty.hint",
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    kit.Card(Modifier.fillMaxHeight().padding(end = if (showBar) letterBarGutter() else 0.dp)) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            if (customApps.isNotEmpty()) {
                                item(key = "hdr-custom") {
                                    kit.SectionHeader(fpString("fan_icon.section.custom"))
                                }
                                items(customApps, key = { "c-" + it.packageName }) { renderRow(it) }
                            }
                            if (groups.isEmpty()) {
                                items(otherApps, key = { "a-" + it.packageName }) { renderRow(it) }
                            } else {
                                groups.forEach { group ->
                                    item(key = "letter:" + group.letter) { kit.SectionHeader(group.letter) }
                                    items(group.items, key = { "a-" + it.packageName }) { renderRow(it) }
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
                                    rows.rowOf(letter)?.let { row ->
                                        scope.launch { listState.scrollToItem(customOffset + row) }
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
        kit.VSpace(FpSpace.l)
    }
}

@Composable
private fun OverrideRow(
    app: InstalledApp,
    initiallyOpen: Boolean = false,
    name: String?,
    entry: IconOverridesStore.Entry?,
    packName: String?,
    packLabel: String?,
    icons: AppIconLoader,
    previews: IconPackPreviewLoader,
    editing: Boolean,
    draft: String,
    onToggleEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onResetName: () -> Unit,
    onPickFromPack: () -> Unit,
    onPickImage: () -> Unit,
    onResetIcon: () -> Unit,
    onResetAll: () -> Unit,
) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    var open by rememberSaveable(app.packageName) { mutableStateOf(initiallyOpen) }
    val changed = name != null || entry != null

    var image by remember(app.packageName, entry, packName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName, entry, packName) {
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
        } ?: packName?.let { pack ->
            previews.load(pack, app.packageName)
        } ?: icons.load(app.packageName)
    }

    val iconTag = when (entry?.kind) {
        IconOverridesStore.KIND_FILE -> fpString("fan_icon.source.custom_image")
        IconOverridesStore.KIND_PACK -> fpString("fan_icon.source.custom_pack")
        else -> null
    }
    val summary = listOfNotNull(
        if (name != null) app.label else null,
        iconTag,
    ).joinToString(" · ").ifEmpty { null }

    kit.ActionRow(
        title = name ?: app.label,
        summary = summary,
        trailingText = null,
        leading = { FpIconPlate(image, FpRowIconSize, FpRowIconCorner) },
        onClick = { open = !open },
    )
    if (!open) return
    kit.Divider()

    kit.ActionRow(
        title = fpString("side_index_override.name"),
        summary = if (name == null) {
            fpString("side_index_override.name.hint")
        } else {
            fpString("side_index_override.name.current", name)
        },
        trailingText = null,
        onClick = onToggleEdit,
    )

    if (editing) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FpSpace.l)) {
            kit.TextInput(
                value = draft,
                label = fpString("side_index_override.name"),
                onValueChange = onDraftChange,
            )
            kit.VSpace(6.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kit.Button(
                    text = fpString("side_index_override.name.save"),
                    onClick = onSaveName,
                    primary = true,
                    enabled = draft.trim().isNotEmpty(),
                )
                kit.Button(
                    text = fpString("side_index_override.name.reset"),
                    onClick = onResetName,
                    enabled = name != null,
                )
            }
            kit.VSpace(8.dp)
        }
    }

    kit.ActionRow(
        title = fpString("fan_icon.from_pack"),
        summary = packLabel ?: fpString("fan_icon.from_pack.none"),
        trailingText = null,
        enabled = packName != null,
        onClick = if (packName == null) null else onPickFromPack,
        navigates = true,
    )
    kit.ActionRow(
        title = fpString("fan_icon.from_image"),
        summary = null,
        trailingText = null,
        onClick = onPickImage,
        navigates = true,
    )
    if (entry != null) {
        kit.ActionRow(
            title = fpString("fan_icon.reset"),
            summary = null,
            trailingText = null,
            destructive = true,
            onClick = onResetIcon,
        )
    }
    if (changed) {
        kit.ActionRow(
            title = fpString("side_index_override.reset_all"),
            summary = null,
            trailingText = null,
            destructive = true,
            onClick = onResetAll,
        )
    }
    kit.Divider()
}
