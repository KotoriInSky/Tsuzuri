package io.github.kotoriinsky.tsuzuri.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kotoriinsky.tsuzuri.ui.state.AppNames
import io.github.kotoriinsky.tsuzuri.ui.state.IconPackPreviewLoader
import io.github.kotoriinsky.tsuzuri.ui.state.ShortcutIconStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconCorner
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconSize
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.peekAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItemRef
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.IconOverridesStore
import io.github.kotoriinsky.tsuzuri.ui.state.iconPackKey
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.readAppsMode
import io.github.kotoriinsky.tsuzuri.ui.state.listTakesEffect
import io.github.kotoriinsky.tsuzuri.ui.state.readShortcutCatalog

/**
 * 清单里的一项（应用或快捷方式）由 [FanItemRef] 描述。
 *
 * 这里刻意**不再有一个 `FanItem` 类**：这一页以前自己解析清单（还顺手按名称排了序），
 * 而清单页现在也是同一个解析 —— 两份实现必然漂移，所以两边都用 [FanItemRef]。
 */


/**
 * 单项图标：列出扇形里**当前的每一项**，可以给某一项单独指定图标。
 *
 * ## 为什么是一整页，而不是「长按某一项弹出」
 *
 * 用户要的是「为这一项单独指定图标」这个**能力**。长按这条入口需要给
 * `FpKit.CheckRow` 补 `onLongClick`，而它有两套实现（M3 走 `Modifier.toggleable`、
 * 澎湃走 `CheckboxPreference`）—— 后者能不能收到长按没有验证过，做一半会让两套风格的
 * 行为不一致（本项目最忌讳的差异）。所以这一版先做「一整页 + 一行一项」的形态：
 * 两套风格一致、而且能一眼看到每一项**当前用的是哪个图标**。
 * 长按入口作为下一步（见报告）。
 *
 * ## 两段：应用 / 快捷方式
 *
 * 这两类项的**行为其实一样**（都能挑图标包里的一张、都能从图片选、都能恢复默认），
 * 所以是两段而不是两种行型。分开的理由是**能不能做这件事**不一样：
 * 只有快捷方式能在「导入快捷方式」那一页被增删，而应用项来自「选择应用」页 ——
 * 用户在这一页看到一条项目找不到它是从哪来的，分两段至少答得了「这是哪一类」。
 *
 * 每一段的段头下面那句「只改这一条，不动宿主应用」是必要的：给快捷方式换图标时
 * 用户必然会担心「会不会把微信的图标也改了」——它不会（hook 侧 `IconOverrides`
 * 就是按 `包名/id` 存的），这句话就是那个保证。
 *
 * ## 图标来源的优先级（与 hook 侧 `IconOverrides` 的注释同一条）
 *
 * ```
 * 单项自定义（图片） > 单项图标包 > 整体图标包 > 自带图标
 * ```
 *
 * 行首画**按这条优先级算出来的那张**（就是扇形里会显示的），副标题写它来自哪一层：
 * 用户在两处都调过时，只有这一行能告诉他谁赢了。单独指定过的项排在最前面单独一段。
 *（2026-10-23 以前行首是自带图标、副标题是包名、右值「整体图标包：com.n…」每行都一样。）
 *
 * ## 「从图片选」是两步：选图 → 裁剪
 *
 * 选完图**不直接写盘**，而是把整页交给 [IconCropScreen]（裁剪是这条路的中间步骤，
 * 不是另一个功能页，所以不进 `Dest`）。两个回调只有「取消」（丢掉这一页，
 * 一个字节都不写）与「使用这张」。**写盘就在裁剪页里发生**，由本页传下去的
 * `save` 完成：写 192×192 的 PNG 到模块 App 自己的 files 目录，成功后写那条配置。
 * 这样设计有两个原因：
 *
 *  - 裁剪需要一块尽量大的方形视口，而且**结果要在写盘之前**看得见 ——
 *    自动居中裁方的旧行为会把主体切掉，用户却只有在扇形里才发现；
 *  - 写盘失败的提示必须**留在裁剪页上**（用户可以立刻重试，裁剪的位置不会丢）。
 *    上一版的形态是「确认 → 这一页消失 → 写盘在别处静默失败」，实机反馈就是
 *    「裁完像什么都没发生」。
 *
 * ## 清单没启用时（`apps_mode = auto`）
 *
 * 这一页列的是清单里的项，而 `auto` 模式下清单不生效 —— 此时在这一页设的图标
 * 也就不会出现在扇形里。这条必须说出来，
 * 否则用户会以为「换了图标没反应」是图标那条链坏了。
 */
@Composable
fun FanIconOverridesScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
    onNavigate: (Dest) -> Unit,
    focusKey: String? = null,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val context = LocalContext.current

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val overrides = IconOverridesStore.observe(prefs, featureKey)
    val packName = prefs.rawStringSet(iconPackKey(featureKey)).sorted().firstOrNull()
    val manual = readAppsMode(featureKey) == "manual"

    var appCatalog by remember { mutableStateOf<AppCatalog>(peekAppCatalog() ?: AppCatalog.Loading) }
    LaunchedEffect(context) { appCatalog = loadAppCatalog(context) }
    val apps = (appCatalog as? AppCatalog.Ready)?.apps ?: emptyList()
    val shortcutCatalog = readShortcutCatalog()
    val shortcutLabels = remember(shortcutCatalog) { FanItemRef.shortcutLabels(shortcutCatalog) }
    val installed = remember(apps) { apps.associate { it.packageName to it.label } }

    val iconPx = with(LocalDensity.current) { FpRowIconSize.roundToPx() }
    val icons = remember(context, iconPx) { AppIconLoader(context, iconPx) }

    // 清单**按用户排的顺序**列（与清单页同一份顺序）：两页的顺序不一样会让用户以为
    // 「我拖的顺序没保存」。标签在这里一次算好，行里只查表
    val items = remember(prefs.value(spec)) { FanItemRef.of(FanItems.read(prefs, spec)) }
    val labels = remember(items, installed, shortcutLabels) {
        items.associate { it.key to it.label(installed, shortcutLabels) }
    }
    // 单独指定过的排最前面单独一段（用户 2026-10-23「这里你想想怎么处理更好」）：一眼看出动过哪几项
    val customItems = remember(items, overrides) { items.filter { overrides[it.key] != null } }
    val appItems = remember(items, overrides) { items.filter { !it.isShortcut && overrides[it.key] == null } }
    val shortcutItems = remember(items, overrides) { items.filter { it.isShortcut && overrides[it.key] == null } }
    val previews = remember(context, iconPx) { IconPackPreviewLoader(context, iconPx) }
    val names = remember(context) { AppNames(context) }
    var packLabel by remember(packName) { mutableStateOf(packName) }
    LaunchedEffect(packName) { packLabel = packName?.let { names.label(it) } }

    var result by remember { mutableStateOf<String?>(null) }
    // 文案在 Composable 里取好：协程里不能再调 fpString（它是 @Composable）
    val okText = fpString("fan_icon.image.ok")

    // 系统图片选择器。用 GetContent（一次性读权限）而不是 OpenDocument：
    // 我们**不需要**长期持有那个 URI —— 图片处理完就被重新编码成 192px 的小图，
    // 存进模块 App 自己的 files 目录（见 IconOverridesStore.importImage）。
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * 选完图之后待裁剪的那个 URI。非 null 时**整页换成裁剪界面** ——
     * 裁剪是「选图」这条路的中间步骤，不是另一个功能页，所以不进 `Dest`。
     */
    var cropUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pending
        if (uri == null || target == null) {
            pending = null
            return@rememberLauncherForActivityResult
        }
        // 选完不再直接写盘：先进裁剪界面。用户取消 = 这里什么都不做 = 一个字节都不写
        cropUri = uri
    }

    // ── 裁剪这一步：把整页交给它（成功 / 取消都只有这两个回调）──
    //
    // 写盘**在裁剪页里发生**（[save] 就是那条路）：只有这样才能在写盘失败时**留在裁剪页**
    // 把原因显示出来，让用户重试 —— 上一次的形态是「确认 → 这一页消失 → 写盘在别处静默失败」，
    // 用户看到的就是「裁完像什么都没发生」。
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
                    // 理论上到不了（选图时就把 target 记下了），但这里不能「什么都不做」：
                    // 那又会变成一次无声的点击。所以给一个明确的失败结果
                    IconOverridesStore.SaveResult.Failed(
                        IconOverridesStore.FailReason.WRITE,
                        "没有待设置的项（pending 为空）",
                    )
                } else {
                    val r = IconOverridesStore.importImage(context, target, cropping, crop)
                    if (r is IconOverridesStore.SaveResult.Ok) {
                        // 值**必须用 Ok.value**（文件名 ␂ 内容指纹）：指纹是 hook 侧
                        // 判断「要不要丢掉解码缓存」的依据，自己拼文件名会让重新裁一次不生效
                        val written = IconOverridesStore.put(
                            featureKey,
                            target,
                            IconOverridesStore.Entry(IconOverridesStore.KIND_FILE, r.value),
                        )
                        if (written == ConfigStore.WriteResult.FAILED) return@IconCropScreen IconOverridesStore.SaveResult.Failed(IconOverridesStore.FailReason.WRITE, "图标配置未保存")
                    }
                    r
                }
            },
        )
        return
    }

    // 两段的**行为完全一样**（都能挑图标包、都能从图片选、都能恢复默认），所以
    // 「一行怎么画、点了做什么」只写一次 —— 两段的差别只是分类，不是能力。
    // 传下去全是同一页的回调，所以这里不需要任何额外状态
    val iconRow: @Composable (FanItemRef) -> Unit = { item ->
        FanIconRow(
            item = item,
            label = labels[item.key] ?: item.key,
            entry = overrides[item.key],
            packName = packName,
            packLabel = packLabel,
            icons = icons,
            previews = previews,
            // 一点直达：清单页点行体带着 focusKey 过来，
            // 那一行进页就展开 —— 用户不用在总览里把刚才那一行重新找一遍
            initiallyOpen = item.key == focusKey,
            onPickFromPack = {
                onNavigate(Dest.IconPackDrawable(spec, item.key, labels[item.key] ?: item.key))
            },
            onPickImage = {
                result = null
                pending = item.key
                pickImage.launch("image/*")
            },
            onReset = { IconOverridesStore.remove(featureKey, item.key, context) },
        )
    }

    // 焦点行滚进视区。下标按 LazyColumn 的真实排布算：每一段 = 1 个段头 + 段内各项
    val listState = rememberLazyListState()
    LaunchedEffect(focusKey, customItems, appItems, shortcutItems) {
        if (focusKey == null) return@LaunchedEffect
        var index = 0
        for (section in listOf(customItems, appItems, shortcutItems)) {
            if (section.isEmpty()) continue
            index += 1
            val i = section.indexOfFirst { it.key == focusKey }
            if (i >= 0) {
                listState.animateScrollToItem(index + i)
                return@LaunchedEffect
            }
            index += section.size
        }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(8.dp)
        // 这里原来有一条「单项设置优先，其次是整体图标包」的横幅：已明确要删
        // （说明小字能没有就没有），而它和下面的蓝色提示条一起占据了页面最显眼的位置。
        // 信息没丢：整体图标包在「图标」那一组里，单项在这里，优先级由「单项」这两个字
        // 自己说清 —— 真需要解释时，用户看的是结果（图标变了没），不是这句话。
        // 清单没生效时，这一页做的设置也进不了扇形 —— 不是错误，是必须说清的状态
        if (items.isNotEmpty() && !listTakesEffect(featureKey, manual)) {
            kit.Banner(fpString("fan_icon.not_enabled"), FpEmphasis.WARNING)
            kit.VSpace(4.dp)
        }
        result?.let {
            kit.Banner(it, FpEmphasis.ACCENT)
            kit.VSpace(4.dp)
        }
        packLabel?.let {
            kit.Paragraph(fpString("fan_icon.follow_pack", it), FpEmphasis.MUTED)
            kit.VSpace(4.dp)
        }

        if (items.isEmpty()) {
            // 清单为空 = 现在走自动挑选：自动挑选的项**不在这一页**（用户没挑过它们，
            // 也就没有一个稳定的「项」可以绑图标）。这句必须说清楚，否则用户以为坏了。
            FpEmptyState(
                titleKey = "fan_icon.empty",
                hintKey = "fan_icon.empty.hint",
                modifier = Modifier.weight(1f),
            )
        } else {
            kit.Card(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (customItems.isNotEmpty()) {
                        item(key = "hdr-custom") {
                            kit.SectionHeader(fpString("fan_icon.section.custom"))
                        }
                        items(customItems, key = { "c-" + it.key }) { iconRow(it) }
                    }
                    if (appItems.isNotEmpty()) {
                        item(key = "hdr-apps") {
                            kit.SectionHeader(fpString("fan_icon.section.apps"))
                        }
                        items(appItems, key = { "a-" + it.key }) { iconRow(it) }
                    }
                    if (shortcutItems.isNotEmpty()) {
                        item(key = "hdr-shortcuts") {
                            kit.SectionHeader(fpString("fan_icon.section.shortcuts"))
                            // 这一段是可换图标这件事在界面上「看得懂」的关键：用户必然担心
                            // 「给扫一扫换图标会不会把微信也改了」——不会，两者是两条键
                            kit.Paragraph(fpString("fan_icon.shortcut.scope"), FpEmphasis.MUTED)
                        }
                        items(shortcutItems, key = { "s-" + it.key }) { iconRow(it) }
                    }
                }
            }
        }
        kit.VSpace(16.dp)
    }
}

/** 一行 + 展开后的三个来源。展开在同一页里做，用户能来回试而不必退出去再进来。 */
@Composable
private fun FanIconRow(
    item: FanItemRef,
    label: String,
    entry: IconOverridesStore.Entry?,
    packName: String?,
    packLabel: String?,
    icons: AppIconLoader,
    previews: IconPackPreviewLoader,
    initiallyOpen: Boolean = false,
    onPickFromPack: () -> Unit,
    onPickImage: () -> Unit,
    onReset: () -> Unit,
) {
    val kit = LocalFpKit.current
    var open by rememberSaveable(item.key) { mutableStateOf(initiallyOpen) }

    // 行首画**扇形里实际会显示的那张**（单独指定 > 整体图标包 > 自带图标），副标题说它来自哪一层。
    // 以前行首是自带图标、副标题是包名、右值是「整体图标包：com.n…」（每行都一样，还截断），
    // 用户 2026-10-23 看了说「这里你想想怎么处理更好」。
    val context = LocalContext.current
    var image by remember(item.key, entry, packName) { mutableStateOf<ImageBitmap?>(null) }
    var packCovered by remember(item.key, packName) { mutableStateOf(true) }
    val iconsRev = ShortcutIconStore.revision(context)
    LaunchedEffect(item.key, entry, packName, iconsRev) {
        image = when (entry?.kind) {
            IconOverridesStore.KIND_FILE -> withContext(Dispatchers.IO) {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(
                        java.io.File(java.io.File(context.filesDir, "icon_overrides"), IconOverridesStore.nameOf(entry.value)).path,
                    )?.asImageBitmap()
                }.getOrNull()
            }
            IconOverridesStore.KIND_PACK -> previews.loadByName(entry.value.substringBefore('/'), entry.value.substringAfter('/'))
            else -> null
        } ?: if (item.isShortcut) {
            // 快捷方式：扇形里画的是它**自己的**图标（图标包只套底板形状），SystemUI 送来的那张；
            // 没送到时退回宿主应用图标
            withContext(Dispatchers.IO) { ShortcutIconStore.load(context, item.key)?.asImageBitmap() }
                ?: icons.load(item.packageName)
        } else packName?.let { pack ->
            previews.load(pack, item.packageName).also { packCovered = it != null }
        } ?: icons.load(item.packageName)
    }
    val source = when {
        entry?.kind == IconOverridesStore.KIND_FILE -> fpString("fan_icon.source.custom_image")
        entry != null -> fpString("fan_icon.source.custom_pack")
        item.isShortcut -> null
        packName == null -> fpString("fan_icon.source.own")
        !packCovered -> fpString("fan_icon.source.uncovered")
        // 跟随整体图标包是常态，不逐行重复（页首那一行已经说了是哪个包）；只有例外才写副标题
        else -> null
    }
    kit.ActionRow(
        title = label,
        summary = source,
        trailingText = null,
        leading = { FpIconPlate(image, FpRowIconSize, FpRowIconCorner) },
        onClick = { open = !open },
    )
    if (!open) return
    kit.Divider()
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
    kit.ActionRow(
        title = fpString("fan_icon.reset"),
        summary = null,
        trailingText = null,
        enabled = entry != null,
        onClick = if (entry == null) null else onReset,
    )
    kit.Divider()
}
