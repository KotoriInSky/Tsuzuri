package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.FeaturePreview
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.PrefGroup
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.changedCount
import io.github.kotoriinsky.tsuzuri.core.geometry.feedsFanPreview
import io.github.kotoriinsky.tsuzuri.core.isListedInSettings
import io.github.kotoriinsky.tsuzuri.core.isVisible
import io.github.kotoriinsky.tsuzuri.core.prefGroups
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore

/**
 * 一个参数分组（二级页）。
 *
 * ## 为什么要有这一页
 *
 * `corner_small_window` 有 29 个参数。全堆在详情页里的后果不是「页面长」，而是
 * **没有信息架构**：用户找一项要滑半天，越加参数越不能看。
 * 分组之后，详情页只需要回答「这个特性有哪些方面可调、我改过哪些」，
 * 具体调值在这一页里做。
 *
 * ## 分组从哪来
 *
 * 从 `Catalog` 现算（`FeatureSpec.prefGroups`），**不是**这一页自己维护一张表 ——
 * 表会漂移，漏掉的参数会从界面上消失，而编译和日志都正常。所以这一页只认
 * [group] 这个标识；取不到就显示错误态（多半是 Catalog 改了分组标识、
 * 而用户是从旧版本留下的返回栈进来的）。
 *
 * 预览与参数整页滚动，避免大字与横屏时预览挤掉操作区。
 *
 * ## 拖动期间不写配置
 *
 * 滑杆的 `onValueChange` 只更新**草稿**（[draft]，本地 map），预览与参数行都读
 * `prefs.withFloats(draft)`；`onValueChangeFinished`（抬手）才 `ConfigStore.putFloat`。
 * 这样一次长滑只写一次远程配置，而预览仍然是逐帧跟手的。
 *
 * ## 生效方式
 *
 * 这一页**不**重复「改完是否要重启」那段说明 —— 那属于特性级信息，在详情页里。
 * 在这里再写一遍，等于同一个事实有两个地方会漂移。
 */
@Composable
fun FeatureSectionScreen(
    featureKey: String,
    group: String,
    padding: PaddingValues,
    onNavigate: (Dest) -> Unit,
    focusPrefKey: String? = null,
) {
    val kit = LocalFpKit.current
    val spec = Catalog.find(featureKey)?.takeIf { it.isListedInSettings }
    val basePrefs = ConfigStore.observe()
    // 分组级恢复默认的确认状态（对话框归调用方持有，Kit 不存状态）
    var resetUndo by remember(featureKey, group) { mutableStateOf<ConfigStore.Checkpoint?>(null) }
    // 拖动中的草稿：键 → 还没落盘的值。落盘时机是「抬手」，见类注释。
    var draft by remember(featureKey, group) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val section = spec?.prefGroups()?.firstOrNull { it.id == group }

    // 界面与预览都读这一份：草稿盖在真实配置之上（`Prefs.withFloats`）。
    // 空草稿时 `withFloats` 返回**同一个实例** —— 图标解码等副作用按实例缓存，
    // 多造等价对象会让它们白重算一遍。
    val prefs = remember(basePrefs, draft) { basePrefs.withFloats(draft) }

    fun commitDraft(key: String) {
        val v = draft[key] ?: return
        ConfigStore.putFloat(key, v)
        draft = draft - key
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        // 预览位于参数之前，随整页滚动。
        if (spec != null && section != null) {
            FeaturePreviewBlock(spec = spec, prefs = prefs, groups = listOf(section))
        }

        Column(
            Modifier
                .fillMaxSize(),
        ) {
            kit.VSpace(8.dp)

            if (spec == null || section == null) {
                kit.Banner(fpString("common.unknown"), FpEmphasis.NEGATIVE)
                kit.VSpace(24.dp)
                return@Column
            }

            // 通道降级提示放这一页的最上面：这一页是滑杆所在的地方，
            // 也就是「改了但真机没变」发生的那一页。

            // 条件显隐：hideWhenOff 的参数在依赖开关关着时不渲染。
            // 全组都藏起来时给一句「为什么看不到」—— 用户已经点进来了，
            // 一个空卡片等于把他扔在原地（offHintKey 正好就是那句原因）。
            ScopeRequirement(spec) { onNavigate(Dest.Setup) }
            val visiblePrefs = section.prefs.filter { it.isVisible(prefs, spec.key) || it.key == focusPrefKey }
            // 这一页到底有没有预览（决定要不要给「不影响预览」那种标注）。
            // 判据与预览台自己用的那条**必须一致**，所以复用同一个函数。
            val hasPreview = previewKindOf(spec, listOf(section)) != null
            if (visiblePrefs.isEmpty()) {
                // 全组都收起时（搜索直达但依赖没满足）仍给一句原因：用户已经点进来了，空页等于扔在原地
                val hiddenDependency = section.prefs.firstNotNullOfOrNull { it.dependsOn }
                kit.Card {
                    if (hiddenDependency != null) {
                        kit.Paragraph(fpString(hiddenDependency.offHintKey), FpEmphasis.MUTED)
                    }
                }
            } else {
                kit.Card {
                    val firstVisible = section.prefs.indexOfFirst { it in visiblePrefs }
                    section.prefs.forEachIndexed { i, pref ->
                        CollapsiblePref(pref in visiblePrefs) {
                        androidx.compose.foundation.layout.Column {
                        if (i > firstVisible) kit.Divider()
                        // 「此项不影响预览」不再拼进说明（用户 2026-10-27：不需要）
                        SearchTarget(pref.key == focusPrefKey) {
                        PrefRow(
                            spec = pref,
                            prefs = prefs,
                            enabled = prefs.isEnabled(spec),
                            onNavigate = onNavigate,
                            onDraftFloat = { key, v -> draft = draft + (key to v) },
                            onCommitFloat = { key -> commitDraft(key) },
                        )
                        }
                        }
                        }
                    }
                }

                // 分组级「恢复默认」。只在真的改过时出现 —— 没改过就没有可恢复的东西，
                // 一个永远存在的按钮只会招来「按了没反应」的疑问。
                //
                // 为什么要有它（而不是让用户自己去拖回去）：这一组的出厂值恰好是**实测**出来的
                // 小数（80 / 63.5 / 50 / 47），没人记得住；而滑杆现在是无极的，
                // 拖回"差不多"的位置在几何上并不等于默认值。恢复 = 删掉这些键，
                // 于是回到「没设过」的状态 —— 与从未打开过这一页的用户完全一致。
                resetUndo?.let { cp ->
                    kit.Banner(
                        text = fpString("settings.reset.done"),
                        emphasis = io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis.MUTED,
                        actionLabel = fpString("common.undo"),
                        onAction = {
                            ConfigStore.rollback(cp)
                            draft = emptyMap()
                            resetUndo = null
                        },
                    )
                }
                if (section.changedCount(prefs) > 0) {
                    kit.Card {
                        kit.ActionRow(
                            title = fpString("section.reset.group"),
                            summary = null,
                            trailingText = null,
                            // 不再弹确认框：做完原位给「撤销」
                            onClick = {
                                val cp = ConfigStore.checkpoint(featureKeyScope(spec))
                                for (p in section.prefs) ConfigStore.remove(p.key)
                                // 恢复默认之后草稿必须一起清：留着它等于把用户刚恢复掉的值又写回去
                                draft = emptyMap()
                                resetUndo = cp
                            },
                        )
                    }
                }
            }

            if (group == spec.presentation.iconToolsGroup) {
                SideIndexIconPackSection(spec, prefs, onNavigate)
                SideIndexNameIconSection(spec, onNavigate)
            }

            kit.VSpace(24.dp)
        }
    }
}
