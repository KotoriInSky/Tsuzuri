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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.FeaturePreview
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.PrefCategory
import io.github.kotoriinsky.tsuzuri.core.PrefCategoryGroup
import io.github.kotoriinsky.tsuzuri.core.PrefGroup
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.changedCount
import io.github.kotoriinsky.tsuzuri.core.geometry.feedsFanPreview
import io.github.kotoriinsky.tsuzuri.core.isListedInSettings
import io.github.kotoriinsky.tsuzuri.core.isVisible
import io.github.kotoriinsky.tsuzuri.core.prefCategories
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.ShowScreenHotZones
import kotlinx.coroutines.launch
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.rememberScreenShape

/**
 * 参数**类别**页：一个类别里含多个实现分组，页内按组分块渲染。
 *
 * 与 [FeatureSectionScreen] 的关系：
 *  - 类别页是详情页的入口（`Dest.FeatureCategory`）；
 *  - 分组页保留给「搜索直达某一组」—— 搜索命中的是确切参数，落在类别页再找一遍是倒退。
 *
 * 三条纪律沿用分组页：
 *  1. 分组**从 Catalog 现算**，这一页不认识任何参数名；
 *  2. 拖动期间只更新草稿，抬手才写配置；
 *  3. 预览放在参数**之前**、整页滚动。
 *     一页有多个组时预览只画一次 —— 每组各画一次会让同一张预览在滚动中重复出现。
 */
@Composable
fun FeatureCategoryScreen(
    featureKey: String,
    categoryId: String,
    padding: PaddingValues,
    onNavigate: (Dest) -> Unit,
    focusPrefKey: String? = null,
) {
    val kit = LocalFpKit.current
    val spec = Catalog.find(featureKey)?.takeIf { it.isListedInSettings }
    val basePrefs = ConfigStore.observe()
    var draft by remember(featureKey, categoryId) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var resetUndo by remember(featureKey, categoryId) { mutableStateOf<ConfigStore.Checkpoint?>(null) }
    val messages = io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpMessageHost.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 界面与预览都读这一份：草稿盖在真实配置之上（`Prefs.withFloats`）。
    val prefs = remember(basePrefs, draft) { basePrefs.withFloats(draft) }
    val category = remember(spec) { spec?.prefCategories()?.firstOrNull { it.category.id == categoryId } }

    fun commitDraft(key: String) {
        val value = draft[key] ?: return
        ConfigStore.putFloat(key, value)
        draft = draft - key
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        if (spec == null || category == null) {
            kit.VSpace(8.dp)
            kit.Banner(fpString("common.unknown"), FpEmphasis.NEGATIVE)
            kit.VSpace(24.dp)
            return@Column
        }

        ScopeRequirement(spec) { onNavigate(Dest.Setup) }
        FeaturePreviewBlock(spec = spec, prefs = prefs, groups = category.groups)
        // 「触发」页：在屏幕上画出真实热区（不拦触摸，离页即消失，拖参数实时跟随，见 ScreenHotZones）
        if (category.category == PrefCategory.TRIGGER && prefs.isEnabled(spec)) {
            // 整块屏幕的尺寸（不是本窗口）：热区是按整屏算的，分屏 / 小窗里窗口比屏幕小
            val metrics = LocalContext.current.resources.displayMetrics
            val screen = rememberScreenShape()
            val w = screen?.width ?: metrics.widthPixels.toFloat()
            val h = screen?.height ?: metrics.heightPixels.toFloat()
            val zones = remember(spec, prefs, w, h, metrics.density) {
                triggerHotZones(spec, prefs, w, h, metrics.density)
            }
            ShowScreenHotZones(zones)
        }

        for (group in category.groups) {
            val visible = group.prefs.filter { it.isVisible(prefs, spec.key) || it.key == focusPrefKey }
            // 整组都被条件收起时，连小节标题一起不出现（HyperOS：父项关着，子项就不在页面上）
            if (visible.isEmpty()) continue
            // 小节标题不再带「6 项」计数（用户 2026-10-21：小字越少越好）
            kit.SectionHeader(title = fpString(group.labelKey))
            run {
                val hasPreview = previewKindOf(spec, category.groups) != null
                kit.Card {
                    val firstVisible = group.prefs.indexOfFirst { it in visible }
                    group.prefs.forEachIndexed { index, pref ->
                        CollapsiblePref(pref in visible) {
                        Column {
                        if (index > firstVisible) kit.Divider()
                        // 「此项不影响预览」不再拼进说明（用户 2026-10-27：不需要）——
                        // 预览不动用户自己看得出来，拼上去只会把说明拉长、折行
                        SearchTarget(pref.key == focusPrefKey) {
                            PrefRow(
                                spec = pref,
                                prefs = prefs,
                                enabled = prefs.isEnabled(spec),
                                onNavigate = onNavigate,
                                onDraftFloat = { key, value -> draft = draft + (key to value) },
                                onCommitFloat = { key -> commitDraft(key) },
                            )
                        }
                        }
                        }
                    }
                }
            }
        }

        // 恢复默认合成页底一个（原来每组一个红色「恢复本组默认」，一页最多四五个，像一排警告）。
        // 只在本页真的有改动时出现；范围是本页（这个类别）的全部参数。
        // 与详情页同一种反馈：横幅 + 撤销（原来是一闪而过的 Toast，回不去）
        resetUndo?.let { cp ->
            kit.VSpace(FpSpace.m)
            kit.Banner(
                text = fpString("settings.reset.done"),
                emphasis = FpEmphasis.MUTED,
                actionLabel = fpString("common.undo"),
                onAction = {
                    ConfigStore.rollback(cp)
                    draft = emptyMap()
                    resetUndo = null
                },
            )
        }
        if (category.groups.any { it.changedCount(prefs) > 0 }) {
            kit.VSpace(FpSpace.m)
            kit.Card {
                kit.ActionRow(
                    title = fpString("common.reset"),
                    summary = null,
                    trailingText = null,
                    destructive = true,
                    // 不再弹确认框：做完原位给「撤销」
                    onClick = {
                        val keys = category.groups.flatMap { g -> g.prefs.map { it.key } }.toSet()
                        // 联动键（DerivedPrefs）与本页参数同前缀，按功能范围取快照才撤销得全
                        val cp = ConfigStore.checkpoint(featureKeyScope(spec))
                        for (key in keys) ConfigStore.remove(key)
                        // 恢复默认后草稿必须一起清：留着它等于把刚恢复掉的值又写回去
                        draft = emptyMap()
                        resetUndo = cp
                    },
                )
            }
        }

        // 图标工具区：判据从「分组页」迁到「类别页」——
        // 这一组现在属于哪个类别由映射表决定，页面不再硬编码「某一组在详情页」。
        val iconToolsGroup = spec.presentation.iconToolsGroup
        if (iconToolsGroup != null && category.groups.any { it.id == iconToolsGroup }) {
            SideIndexIconPackSection(spec, prefs, onNavigate)
            SideIndexNameIconSection(spec, onNavigate)
        }


        kit.VSpace(24.dp)
    }
}
