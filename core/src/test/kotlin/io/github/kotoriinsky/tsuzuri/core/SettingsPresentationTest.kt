package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPresentationTest {
    @Test
    fun unavailableFeaturesStayOutOfSettingsWithoutRemovingBackendContracts() {
        val visible = Catalog.settingsFeatures.map { it.key }
        assertFalse(Catalog.MIUI_WIDGET_FLAG in visible)
        assertTrue(Catalog.find(Catalog.WIDGET_STYLE) != null)
        assertTrue(Catalog.features.filter { it.group == FeatureGroup.SCREEN_RECOGNITION }.all { it.key in visible })
    }

    /**
     * 未完成特性整组下架（2026-09-19）。
     *
     * 聚合支付暂时下架，保留后端契约和配置。
     * 它们留在列表里的唯一效果是「开了没反应」——本项目最忌讳的形态。
     *
     * 断言分两半，缺一不可：
     *  1. 界面侧：不出现在 `settingsFeatures`（= 不进模块页、不进搜索）；
     *  2. 后端侧：`Catalog.find` 仍然找得到、scope 与 pref 原样 —— 摘的是**入口**，
     *     不是键。存量用户已经打开的仍然照旧运行，`git revert` 也只需回这一行。
     */
    @Test
    fun unfinishedFeaturesAreUnlistedButKeepTheirContracts() {
        val visible = Catalog.settingsFeatures.map { it.key }
        for (key in listOf(
            Catalog.AGG_PAYMENT,
        )) {
            assertFalse(key in visible, "$key 不该出现在界面上")
            val spec = Catalog.find(key)
            assertTrue(spec != null, "$key 的下架不能连声明一起删（键与 hook 行为要保留）")
            assertTrue(spec!!.scope.isNotEmpty(), "$key 仍然参与作用域自检")
        }
    }

    /**
     * 三个负一屏小部件入口放在杂项，miuiWidget 注入仍然下架。
     */
    @Test
    fun widgetEntriesAreVisibleInMiscellaneous() {
        for (key in listOf(Catalog.PA_WIDGET_ADMISSION, Catalog.PA_WIDGET_PICKER_INJECT, Catalog.WIDGET_STYLE)) {
            assertTrue(Catalog.settingsFeatures.any { it.key == key && it.group == FeatureGroup.APPS }, key)
        }
        assertTrue(Catalog.settingsFeatures.none { it.group == FeatureGroup.NEGATIVE_SCREEN })
    }

    /**
     * 首页四组（2026-10-26 分享排序让「应用」组重新出现）：
     * 手势与小窗 / 识屏 / 复制 / 杂项，顺序即展示顺序。
     * 断言到每个键，防止有人改 group 时把某个功能悄悄挪进隐藏组或空组。
     */
    @Test
    fun homeListsExactlyFourGroupsInOrder() {
        val grouped = Catalog.settingsFeatures.groupBy { it.group }
        assertEquals(
            listOf(FeatureGroup.GESTURE, FeatureGroup.SCREEN_RECOGNITION, FeatureGroup.CLIPBOARD, FeatureGroup.APPS),
            FeatureGroup.entries.filter { it in grouped },
        )
        assertEquals(
            setOf(Catalog.CORNER_SMALL_WINDOW, Catalog.SIDE_INDEX, Catalog.MULTI_FREEFORM),
            grouped.getValue(FeatureGroup.GESTURE).map { it.key }.toSet(),
        )
        assertEquals(
            setOf(Catalog.PASSCODE_ISLAND, Catalog.CONTEXT_INSTALL, Catalog.CONTEXT_MUSIC, Catalog.CONTEXT_SCREEN_ON),
            grouped.getValue(FeatureGroup.CLIPBOARD).map { it.key }.toSet(),
        )
        assertEquals(
            setOf(Catalog.SHARE_ORDER, Catalog.TEXT_TOOLBAR, Catalog.CLEAN_STATUSBAR,
                Catalog.PA_WIDGET_ADMISSION, Catalog.PA_WIDGET_PICKER_INJECT, Catalog.WIDGET_STYLE),
            grouped.getValue(FeatureGroup.APPS).map { it.key }.toSet(),
        )
    }

    @Test
    fun sideIndexDeclaresPreviewAndIconToolsOnTheirParameterPages() {
        val spec = Catalog.require(Catalog.SIDE_INDEX)
        // side_feedback 没了：唯一一项「振动」并入触发
        assertEquals(setOf("side_trigger", "side_panel", "side_material"), spec.presentation.previewGroups)
        assertEquals("side_panel", spec.presentation.iconToolsGroup)
        assertTrue(spec.presentation.previewGroups.all { id -> spec.prefGroups().any { it.id == id } })
    }

    @Test
    fun paymentOnlyOffersImplementedEntry() {
        val entry = Catalog.require(Catalog.AGG_PAYMENT).prefs.filterIsInstance<PrefSpec.Choice>().first()
        assertEquals(listOf("lock_screen"), entry.options.map { it.value })
    }
}
