package io.github.kotoriinsky.tsuzuri.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.fpClampSingleLine
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.AppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.peekAppCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.IconOverridesStore
import io.github.kotoriinsky.tsuzuri.ui.state.InstalledApp
import io.github.kotoriinsky.tsuzuri.ui.state.SideIndexCustom
import io.github.kotoriinsky.tsuzuri.ui.state.filterApps
import io.github.kotoriinsky.tsuzuri.ui.state.iconPackKey
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog

/**
 * 侧滑索引的**单应用名字与图标覆盖**（本批 3️⃣）—— 逐个应用配。
 *
 * ## 为什么是「一个应用行 + 展开」而不是分两页
 *
 * 名字与图标说的是**同一个对象**（「微信这一项我想让它叫别的、长得不一样」）。
 * 分成「改名字」一页、「换图标」一页的话，用户改完名字想换个图标得退出去再进来，
 * 而且两页都要重新找一遍那个应用。所以按**对象**分页、页内按**能力**分行 ——
 * 这与 `FanIconOverridesScreen` 的形态一致（那一页也是「一行 + 展开后的几种来源」）。
 *
 * ## 覆盖的优先级（与 hook 侧那条链逐字对应，别在两处各说一套）
 *
 * ```
 * 单项图标覆盖（本页设的） > 整体图标包（side_index.icon_pack）> 应用自带图标
 * ```
 *
 * 行的右侧如实写出**当前生效的是哪一层**：用户在两处都调过却看到同一张图时，
 * 只有这一列能告诉他谁赢了（状态可见）。
 *
 * ## 键的粒度是**包名**
 *
 * 侧滑索引只能启动应用（拿的是 MAIN/LAUNCHER 入口），面板里一个包只会出现一次，
 * 所以覆盖按包名存（不是 `包名/activity` —— 那会造出「同一个应用有两套覆盖、
 * 不知道哪套生效」）。扇形那边用 `包名/id` 是因为它要区分「微信」和「微信·扫一扫」。
 *
 * ## 数据与通道全是**复用**的
 *
 * - 配置：`side_index.name_overrides`（`StringSet`，见 `core/DisplayNameOverrides`）
 *   与 `side_index.icon_overrides`（**与扇形同键同格式**，读写都走 [IconOverridesStore]，
 *   它按特性键参数化，所以这里不需要第二套实现）；
 * - 图片：选图 → [IconCropScreen] 裁剪 → 解码成 192×192 PNG 落进**模块 App 自己的**
 *   files 目录 → `content://` 给 SystemUI 读（`IconOverridesStore.importImage`）。
 *   这条链路与扇形完全相同，所以修一处两边都受益；
 * - 从图标包挑一张：跳 `Dest.IconPackDrawable`（那一页也是按特性键写同一个键）。
 *
 * ## 输入框为什么用 SearchField
 *
 * FpKit 的契约里**没有**通用的文本输入框（只有 [io.github.kotoriinsky.tsuzuri.ui.kit.FpKit.SearchField]）。
 * 给契约加一个 `TextField` = 两套 Kit 各实现一遍，那是 Kit 契约变更（本批范围外）。
 * 所以这里先用 SearchField 承担「输入应用名」这件事 —— 它在两套风格下都是单行输入框、
 * 都能拿到焦点与输入法，功能上没有缺失（只有放大镜图标在语义上不完全贴切）。
 * **给 Kit 补一个通用单行输入框是明确的后续项**，见交付报告。
 */
@Composable
fun SideIndexOverridesScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
    onNavigate: (Dest) -> Unit,
    /** 从收藏行点进来时带着包名：那一行进页就展开并滚进视区。 */
    focusKey: String? = null,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val context = LocalContext.current

    // 特性键从清单声明推（与 `FanIconOverridesScreen` 同一条路：两处各推一遍必然漂移）
    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val names = remember(prefs) { SideIndexCustom.readNames(prefs, featureKey) }
    val overrides = IconOverridesStore.observe(prefs, featureKey)
    val packName = prefs.rawStringSet(iconPackKey(featureKey)).sorted().firstOrNull()

    var catalog by remember { mutableStateOf<AppCatalog>(peekAppCatalog() ?: AppCatalog.Loading) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(context, attempt) { catalog = loadAppCatalog(context) }
    val apps = (catalog as? AppCatalog.Ready)?.apps ?: emptyList()

    val iconPx = with(LocalDensity.current) { FpRowIconSize.roundToPx() }
    val icons = remember(context, iconPx) { AppIconLoader(context, iconPx) }

    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(apps, query) { filterApps(apps, query, onlySelected = false, selected = emptySet()) }
    val listState = rememberLazyListState()
    LaunchedEffect(focusKey, visible) {
        val i = visible.indexOfFirst { it.packageName == focusKey }
        if (i >= 0) listState.scrollToItem(i)
    }

    var result by remember { mutableStateOf<String?>(null) }
    val okText = fpString("fan_icon.image.ok")
    // ⚠️ 文案必须在 Composable 上下文里取好：`fpString` 是 @Composable，而行里的回调
    // 是普通 `() -> Unit`（在那些 lambda 里调它编译不过，这个坑 `FanIconOverridesScreen`
    // 的注释里也记了一次）。所以这里带参数的文案一律**不带参数**：
    // 「名字已保存」而不是「名字已改为「X」」—— 名字就在上一行的输入框里，重复没有价值。
    val nameSavedText = fpString("side_index_override.name.saved")

    /** 正在录入名字的那个包（null = 没在录入）。 */
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDraft by rememberSaveable { mutableStateOf("") }

    /** 选完图之后待裁剪的 URI。非 null 时**整页换成裁剪界面**（与扇形同一条路）。 */
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var cropUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || pending == null) {
            pending = null
            return@rememberLauncherForActivityResult
        }
        cropUri = uri
    }

    // ── 裁剪这一步：整页交给它（写盘就在那一页里发生，失败能留在原地重试）──
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
                        // 值**必须用 Ok.value**（文件名 ␂ 指纹）：指纹是 hook 侧判断
                        // 「要不要丢掉解码缓存」的依据，自己拼文件名会让重新裁一次不生效
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

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(8.dp)
        kit.Banner(fpString("side_index_override.intro"), FpEmphasis.MUTED)
        kit.VSpace(4.dp)
        result?.let {
            kit.Banner(it, FpEmphasis.ACCENT)
            kit.VSpace(4.dp)
        }

        // 图标包那一行只在**真的选了包**时可点：没选包时点进去只能看到空列表，
        // 用户会以为「图标包坏了」。文案里说清去哪儿选（§9.0 第 9 条：帮助用户恢复）。
        if (packName == null) {
            kit.Banner(fpString("side_index_override.no_pack"), FpEmphasis.MUTED)
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
                kit.Card(Modifier.weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(visible, key = { it.packageName }) { app ->
                            OverrideRow(
                                app = app,
                                initiallyOpen = app.packageName == focusKey,
                                name = names[app.packageName],
                                entry = overrides[app.packageName],
                                packName = packName,
                                icons = icons,
                                editing = editing == app.packageName,
                                draft = nameDraft,
                                onToggleEdit = {
                                    if (editing == app.packageName) {
                                        editing = null
                                    } else {
                                        editing = app.packageName
                                        // 草稿初值 = **当前生效的名字**（覆盖名或应用名）：
                                        // 从空开始的话用户得先把原名字抄一遍才能改一个字
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
                    }
                }
            }
        }
        kit.VSpace(16.dp)
    }
}

/**
 * 一行 + 展开后的四种动作。
 *
 * 展开在同一页里做（与 `FanIconOverridesScreen` 一致）：用户能来回试而不必退出去再进来。
 */
@Composable
private fun OverrideRow(
    app: InstalledApp,
    initiallyOpen: Boolean = false,
    name: String?,
    entry: IconOverridesStore.Entry?,
    packName: String?,
    icons: AppIconLoader,
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
    var open by rememberSaveable(app.packageName) { mutableStateOf(initiallyOpen) }
    val changed = name != null || entry != null

    kit.ActionRow(
        // 标题是**生效的名字**（改过名字之后这里必须跟着变，否则用户看不出改没改成）
        title = name ?: app.label,
        // 副标题写包名（夹成一行：澎湃那边的行组件会从词中间折行，见 fpClampSingleLine）
        summary = fpClampSingleLine(app.packageName),
        // 右值如实写出「图标当前用的是哪一层」+「名字改没改过」
        trailingText = (if (changed) fpString("side_index_override.changed") + " · " else "") +
            when {
                entry != null -> entry.describe
                packName == null -> fpString("fan_icon.source.own")
                else -> fpString("fan_icon.source.pack", packName)
            },
        leading = { OverrideIcon(icons, app.packageName) },
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

    // ── 录入名字（行内展开，不是弹窗：两套风格下弹窗的分支更多，而这里只需要一个输入框）──
    if (editing) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FpSpace.l)) {
            kit.TextInput(
                value = draft,
                label = fpString("side_index_override.name"),
                onValueChange = onDraftChange,
            )
            kit.VSpace(6.dp)
            // 两个按钮之间用 Arrangement 留距（不能用 kit.VSpace —— 它是纵向间隔器，
            // 放进 Row 里宽度是 0，表现是两个按钮贴在一起）
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
        summary = packName ?: fpString("fan_icon.from_pack.none"),
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
        onClick = if (entry == null) null else onResetIcon,
    )
    // 「全部恢复默认」与上面两条分开：名字与图标是两条独立的链，
    // 用户经常只想撤掉其中一个，而这一条是「这一项我改乱了，一次性退回去」。
    kit.ActionRow(
        title = fpString("side_index_override.reset_all"),
        summary = null,
        trailingText = null,
        enabled = changed,
        onClick = if (changed) onResetAll else null,
    )
    kit.Divider()
}

/**
 * 行首的应用图标。
 *
 * 画的是**应用自带图标**（不是生效后的那张）——换图标之后真正长什么样要看面板，
 * 这里不假造一个预览（与 `FanIconOverridesScreen` 同一条纪律）。
 * 底板走 [FpIconPlate]：拿到图之后底板透明，否则自带透明区域的图标会露出一圈浅色方块。
 */
@Composable
private fun OverrideIcon(loader: AppIconLoader, packageName: String) {
    var image by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, loader) { image = loader.load(packageName) }
    FpIconPlate(image, FpRowIconSize, FpRowIconCorner)
}
