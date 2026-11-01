package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpErrorState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIconPlate
import io.github.kotoriinsky.tsuzuri.ui.kit.FpLoadingState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconCorner
import io.github.kotoriinsky.tsuzuri.ui.kit.FpRowIconSize
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.AppIconLoader
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.IconPack
import io.github.kotoriinsky.tsuzuri.ui.state.IconPackCatalog
import io.github.kotoriinsky.tsuzuri.ui.state.iconPackKey
import io.github.kotoriinsky.tsuzuri.ui.state.loadIconPacks

/**
 * 图标包选择页（**单选**）。
 *
 * ## 为什么有单独一页，而不是复用应用多选页
 *
 * 图标包也是一个应用，但它和「扇子里放哪些应用」是**两件事**：多选页列出的是
 * 全部可启动应用（两百来个），这里只列**声明了图标包 action 的应用**（真机上通常 0–3 个）。
 * 复用多选页会让用户在一堆普通应用里找不到图标包，也会让他以为「勾一个应用」=「套用图标包」。
 *
 * ## 存哪
 *
 * 存进 `<特性>.icon_pack`（`PrefSpec.AppList` 类型，集合里只放一个包名）。
 * 为什么不用 `Choice`：`Choice` 的选项必须在 Catalog 里**静态声明**，而图标包列表是
 * **运行时枚举**出来的 —— 静态声明表达不了（同一条理由也写在 hook 侧 `FanTargets`）。
 * 「一个元素的集合」是现有类型里最接近那个语义的写法：空集 = 不套用。
 *
 * ## 列表
 *
 * 一个单选列表：第一项「不套用」（系统默认应用图标占位），其余每项画**图标包自己的图标**。
 * 以前每行画「套在清单第一个应用上的效果」，用户看到的是一排相同的淘宝图标，分不清是哪个包
 *（2026-10-23 反馈）。未覆盖的应用回退到自带图标，写在列表下方。
 */
@Composable
fun IconPackPickerScreen(featureKey: String, spec: PrefSpec.OrderedList?, previewPackage: String?, padding: PaddingValues) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val context = LocalContext.current

    // 特性键由「清单的键」推出：`corner_small_window.items` → `corner_small_window`
    val packKey = remember(featureKey) { iconPackKey(featureKey) }

    var catalog by remember { mutableStateOf<IconPackCatalog>(IconPackCatalog.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, attempt) {
        catalog = IconPackCatalog.Loading
        catalog = loadIconPacks(context)
    }

    val iconPx = with(LocalDensity.current) { FpRowIconSize.roundToPx() }
    val appIcons = remember(context, iconPx) { AppIconLoader(context, iconPx) }

    // 生效值：集合里取第一个（与 hook 侧同一规则 —— 多个时取字典序最小的）
    val selected = prefs.rawStringSet(packKey)
    val current = selected.sorted().firstOrNull()
    val nativePermission = featureKey == Catalog.PASSCODE_ISLAND


    // 2026-10-23 用户：「应该使用图标包的图标」「现在相当混乱」。旧版把当前状态分三处表达
    //（顶部横条、列表单选、底部「不套用」按钮），预览说明还误用了「当前图标包：%s」的文案，
    // 真机显示成「当前图标包：淘宝」。现在只剩一个单选列表：第一项是「不套用」，
    // 其余每项画**图标包自己的图标**；当前状态 = 哪一项被选中。
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        kit.VSpace(8.dp)
        when (val c = catalog) {
            is IconPackCatalog.Loading -> FpLoadingState(hintKey = "icon_pack.loading.hint")

            // 这个分支只在「包管理器都拿不到」时出现；空列表走下面的空态
            is IconPackCatalog.Unavailable -> FpErrorState(
                titleKey = "icon_pack.failed",
                hintKey = "icon_pack.failed.hint",
                detail = c.reason,
                actionLabel = fpString("state.action.retry"),
                onAction = { attempt++ },
            )

            is IconPackCatalog.Ready -> {
                kit.Card {
                    // 「不套用」画系统的默认应用图标（`sym_def_app_icon`）当占位（用户 2026-10-23）：
                    // 不借清单里某个应用的图标 —— 那看起来像预设了那个应用（「应该没有预设」）
                    val nativePlaceholder = remember(context, iconPx) {
                        runCatching {
                            context.getDrawable(android.R.drawable.sym_def_app_icon)
                                ?.toBitmap(iconPx, iconPx)?.asImageBitmap()
                        }.getOrNull()
                    }
                    kit.CheckRow(
                        title = fpString(if (nativePermission) "pref.copy_direct.icon_pack.none" else "icon_pack.none"),
                        summary = fpString("icon_pack.none.summary"),
                        checked = current == null,
                        onCheckedChange = { on -> if (on) ConfigStore.remove(packKey) },
                        leading = { FpIconPlate(nativePlaceholder, FpRowIconSize, FpRowIconCorner) },
                    )
                    for (pack in c.packs) {
                        IconPackRow(
                            pack = pack,
                            checked = pack.packageName == current,
                            appIcons = appIcons,
                            // 单选：点哪个就换成哪个；再点已选中的那项不取消（取消请选「不套用」）
                            onCheckedChange = { on -> if (on) ConfigStore.putStringSet(packKey, setOf(pack.packageName)) },
                        )
                    }
                }
                if (c.packs.isEmpty()) {
                    kit.VSpace(8.dp)
                    FpEmptyState(
                        titleKey = "icon_pack.empty",
                        hintKey = "icon_pack.empty.hint",
                        actionLabel = fpString("state.action.retry"),
                        onAction = { attempt++ },
                    )
                }
            }
        }
        kit.VSpace(4.dp)
        kit.Paragraph(fpString("icon_pack.hint.fallback"), FpEmphasis.MUTED)
        kit.VSpace(16.dp)
    }
}

/** 一行图标包：图标包自己的图标 + 名字（用户 2026-10-23：「应该使用图标包的图标」）。 */
@Composable
private fun IconPackRow(
    pack: IconPack,
    checked: Boolean,
    appIcons: AppIconLoader,
    onCheckedChange: (Boolean) -> Unit,
) {
    val kit = LocalFpKit.current
    var icon by remember(pack.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pack.packageName) { icon = appIcons.load(pack.packageName) }
    kit.CheckRow(
        title = pack.label,
        summary = null,
        checked = checked,
        onCheckedChange = onCheckedChange,
        leading = { FpIconPlate(icon, FpRowIconSize, FpRowIconCorner) },
    )
}
