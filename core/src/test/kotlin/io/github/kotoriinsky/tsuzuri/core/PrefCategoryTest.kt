package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 参数类别层的**边界**测试。
 *
 * `CatalogTest` 已经用真实 Catalog 钉住「分组全部登记、类别是划分、入口数 5/4」；
 * 这一份用**构造出来的最小 FeatureSpec** 钉住映射规则本身，两条互补：
 *  - 真实 Catalog 会变，测试跟着变；
 *  - 规则不该变，这一份在 Catalog 怎么改的情况下都必须绿。
 */
class PrefCategoryTest {

    private fun toggle(key: String, group: String?, default: Boolean = false) = PrefSpec.Toggle(
        key = key,
        labelKey = "label.$key",
        default = default,
        group = group,
    )

    private fun feature(vararg prefs: PrefSpec) = FeatureSpec(
        key = "test_feature",
        group = FeatureGroup.GESTURE,
        scope = listOf(ScopeTarget.SYSTEM_UI),
        restart = RestartPolicy.HOT,
        maturity = Maturity.STABLE,
        prefs = prefs.toList(),
    )

    @Test
    fun unregisteredGroupFallsIntoOtherAndStaysVisible() {
        // 没登记的分组**不能从界面消失** —— 落到「其它」是兜底，不是隐藏。
        // 「新增参数却看不见」是本项目最难查的一类故障。
        val categories = feature(toggle("brand_new_key", "brand_new_group")).prefCategories()
        assertEquals(1, categories.size)
        assertEquals(PrefCategory.OTHER, categories[0].category)
        assertEquals(1, categories[0].prefs.size)
    }

    @Test
    fun exposedFalseGroupKeepsPrefsButLeavesCategories() {
        // side_handle（把手与气泡）整组下架：键与 hook 行为保留、存量配置不悬空，
        // 但它不该出现在类别页里。
        val spec = feature(toggle("handle_width", "side_handle"), toggle("side_alpha", "side_trigger"))
        assertTrue(spec.prefs.any { it.group == "side_handle" }, "参数本身必须还在（否则存量配置悬空）")
        val categories = spec.prefCategories()
        assertEquals(1, categories.size)
        assertEquals(PrefCategory.TRIGGER, categories[0].category)
        assertEquals(1, categories[0].prefs.size)
    }

    @Test
    fun categoryOrderIsEnumOrderNotDeclarationOrder() {
        // 参数在 Catalog 里是「高级 → 内容 → 触发」声明，界面上必须仍然按
        // 触发 → 内容 → 高级 排：同一类东西永远在同一位置。
        val spec = feature(
            toggle("a", "corner_advanced"),
            toggle("b", "corner_list"),
            toggle("c", "corner_trigger"),
        )
        assertEquals(
            listOf(PrefCategory.TRIGGER, PrefCategory.CONTENT, PrefCategory.ADVANCED),
            spec.prefCategories().map { it.category },
        )
    }

    @Test
    fun groupsKeepDeclarationOrderInsideCategory() {
        val spec = feature(
            toggle("icon", "corner_icon"),
            toggle("window", "corner_window"),
            toggle("motion", "corner_motion"),
        )
        val appearance = spec.prefCategories().single { it.category == PrefCategory.APPEARANCE }
        assertEquals(listOf("corner_icon", "corner_window", "corner_motion"), appearance.groups.map { it.id })
    }

    @Test
    fun changedCountAggregatesAcrossGroups() {
        val spec = feature(
            toggle("a", "corner_trigger"),
            toggle("b", "corner_trigger"),
            toggle("c", "corner_list"),
        )
        val prefs = Prefs(MapStore(mapOf("a" to true)))
        val trigger = spec.prefCategories().single { it.category == PrefCategory.TRIGGER }
        assertEquals(1, trigger.changedCount(prefs), "改了一项就应计入")
        assertEquals(0, spec.prefCategories().single {
            it.category == PrefCategory.CONTENT
        }.changedCount(prefs), "没改的类别应为 0")
    }

    @Test
    fun featureWithoutPrefsHasNoCategories() {
        // 详情页据此走「没有参数」的分支；返回空列表而不是一个空类别。
        assertTrue(feature().prefCategories().isEmpty())
        assertTrue(feature().prefGroups().isEmpty())
    }
}
