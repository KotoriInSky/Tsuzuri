package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.listTakesEffect
import io.github.kotoriinsky.tsuzuri.ui.state.readAppsMode

/**
 * **往有序清单里加应用**（多选）。清单本身在 [FanItemsScreen] 里看与改，正文见 [AppMultiPickBody]。
 *
 * ## 为什么这一页不和清单页合并
 *
 * 两百来个应用的多选要滚动、要搜索、要字母索引，而清单页要长按拖拽 ——
 * 两种手势在同一个列表里会互相抢（长按一半被滚动打断，或者滚动被长按吃掉）。
 * 参考实现也是这么分的：**同一类项的编辑**在一页，**挑候选**在另一页。
 *
 * ## 勾选写进哪
 *
 * 直接写进清单本身。勾上 = **追加到末尾**（顺序就是生效顺序，所以「新加的排在最后」是
 * 唯一不打断已有顺序的写法；用户想挪位置就回清单页拖）；取消勾选 = 从清单里去掉那一条。
 */
@Composable
fun AppPickerScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
    onNavigate: (Dest) -> Unit,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val items = FanItems.read(prefs, spec)
    val manual = readAppsMode(featureKey) == "manual"
    // 「已选」= 清单里的**应用**项（快捷方式由「导入快捷方式」那一页管，不在这一页的候选里）
    val selected = remember(items) {
        items.filterNot { FanItems.isShortcut(it) }.toSet()
    }

    AppMultiPickBody(
        selected = selected,
        onToggle = { pkg, on ->
            FanItems.write(
                spec.key,
                if (on) FanItems.appended(items, pkg) else FanItems.without(items, pkg),
            )
        },
        padding = padding,
        pickedOrder = items,
        // 选应用是最常用的路，快捷方式等放一层下面，这一页才不用展开每个应用
        extras = if (spec.appsOnly) null else ({
            kit.Card {
                kit.ActionRow(
                    title = fpString("app_picker.more"),
                    summary = null,
                    trailingText = null,
                    onClick = { onNavigate(Dest.AddItem(spec)) },
                    navigates = true,
                )
            }
        }),
        banners = {
            // 只说「这份清单现在不算数」的情况；已选数量段头已经写了。
            // 门槛只对 minCount > 1 的清单有意义：收藏与置顶空着就是没有，不会改用最近使用
            if (selected.size < spec.minCount) {
                if (spec.minCount > 1) {
                    kit.Banner(fpString("app_picker.below.min", spec.minCount), FpEmphasis.WARNING)
                }
            } else if (!listTakesEffect(featureKey, manual)) {
                // `apps_mode = auto` 时清单存着但不生效
                kit.Banner(fpString("fan_shortcut.banner.disabled", selected.size), FpEmphasis.WARNING)
            }
        },
    )
}
