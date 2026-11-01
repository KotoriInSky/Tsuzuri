package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore

/**
 * **应用多选**（`PrefSpec.AppList`）：写一个无序集合。正文与 [AppPickerScreen] 共用
 * [AppMultiPickBody]，这一页不认识任何具体的特性：标题、说明、写哪个键全部来自 [spec]。
 *
 * ## 空集 = 删键
 *
 * 一个都不勾时走 `ConfigStore.remove`（不是写一个空集合）—— 与 `PrefSpec.AppList`
 * 的契约一致：空集的语义是「用户没配过」，消费侧据此走**默认行为**。
 * 所以「把勾全取消」= 恢复默认，不是「把功能弄坏」。
 */
@Composable
fun AppListPickerScreen(
    spec: PrefSpec.AppList,
    padding: PaddingValues,
    /**
     * 顶部额外的一句状态（可空）。给「这一项当前不生效」这类**依赖说明**用
     *（被忽略的值要显式说明）。为空时不占位置。
     */
    notice: String? = null,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val selected = remember(prefs, spec) { prefs.value(spec) }

    AppMultiPickBody(
        selected = selected,
        onToggle = { pkg, on ->
            val next = if (on) selected + pkg else selected - pkg
            if (next.isEmpty()) {
                ConfigStore.remove(spec.key)
            } else {
                ConfigStore.putStringSet(spec.key, next)
            }
        },
        padding = padding,
        pickedLabelKey = spec.pickedLabelKey,
        restLabelKey = spec.restLabelKey,
        banners = {
            // ⚠️ 黑名单（[PrefSpec.AppList.emptyIsValid]）下空集是**正常态**（一个都不屏蔽），
            // 不是「没配过 → 会走自动挑选」 —— 给它挂警告条等于告诉用户「你现在这样是错的」。
            if (!spec.key.endsWith(".index_filter_packages") && !spec.emptyIsValid && (selected.isEmpty() || selected.size < spec.minCount)) {
                kit.Banner(fpString("app_list.below.min"), FpEmphasis.WARNING)
            }
            if (!notice.isNullOrEmpty()) {
                kit.Banner(notice, FpEmphasis.WARNING)
            }
        },
    )
}
