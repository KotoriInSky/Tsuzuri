package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentCapabilitiesTest {
    @Test fun freshInstallationDoesNotEnableAnyChild() {
        assertTrue(ContentCapabilities.migration(Prefs(MapStore(emptyMap()))).values.none { it })
    }

    @Test fun migrationMovesOldSevenFeaturesIntoThreeCategories() {
        // 旧版：小爱识屏样式开着且明确关了「复制文字」；图片快捷操作开着；原媒体关着
        val prefs = Prefs(MapStore(mapOf(
            "f.screen_recognition_style" to true,
            "screen_recognition_style.copy_text" to false,
            "f.portal_image_actions" to true,
            "f.portal_original_media" to false,
        )))
        val w = ContentCapabilities.migration(prefs)
        // 布局子项跟旧总开关走（旧版缺省 = 开），落到「识屏样式」
        assertTrue(w.getValue(Keys.pref(Catalog.RECOGNITION_LOOK, "horizontal")))
        // 用户明确关过的保持关，并且搬到「文字」
        assertFalse(w.getValue(Keys.pref(Catalog.SCREEN_TEXT, "copy_text")))
        assertTrue(w.getValue(Keys.pref(Catalog.SCREEN_TEXT, "share_text")))
        // 图片卡片按钮按旧默认（编辑开、提取文字关）
        assertTrue(w.getValue(Keys.pref(Catalog.SCREEN_IMAGE, "edit")))
        assertFalse(w.getValue(Keys.pref(Catalog.SCREEN_TEXT, "ocr")))
        assertFalse(w.getValue(Keys.pref(Catalog.SCREEN_IMAGE, "original_media")))
        // 三个新总开关都写了，值 = 有没有搬过来开着的子项
        assertTrue(w.getValue(Keys.enabled(Catalog.SCREEN_TEXT)))
        assertTrue(w.getValue(Keys.enabled(Catalog.SCREEN_IMAGE)))
        assertTrue(w.getValue(Keys.enabled(Catalog.RECOGNITION_LOOK)))
    }

    @Test fun migrationRunsOnlyOnce() {
        val prefs = Prefs(MapStore(mapOf(
            "f.portal_image_region" to true,
            Keys.enabled(Catalog.SCREEN_IMAGE) to false,
        )))
        // 新总开关已经写过 = 已迁移（或用户已经在新界面里操作过）：不再碰
        assertTrue(ContentCapabilities.migration(prefs).keys.none { it.startsWith(Catalog.SCREEN_IMAGE) || it == Keys.enabled(Catalog.SCREEN_IMAGE) })
    }

    @Test fun onRequiresTheMasterSwitch() {
        val f = Catalog.SCREEN_TEXT
        val prefs = Prefs(MapStore(mapOf(Keys.pref(f, "ocr") to true, Keys.enabled(f) to false)))
        assertTrue(ContentCapabilities.selected(prefs, f, "ocr"))
        assertFalse(ContentCapabilities.on(prefs, f, "ocr"))
    }

    /**
     * 2026-10-21 之前这里钉的是「拆分前关着的用户，子项被写成 false」—— 那正是「开了没反应」的成因
     * （真机 f.portal_image_region=true 而两个子项都是 false）。现在关着的用户迁移时什么都不写。
     */
    @Test fun migrationWritesNothingWithoutOldRecords() {
        val prefs = Prefs(MapStore(ContentCapabilities.names.keys.associate { Keys.enabled(it) to false }))
        assertTrue(ContentCapabilities.migration(prefs).isEmpty())
    }

    @Test fun firstEnableWritesTheRecommendedSetForUnsetChildren() {
        for ((feature, children) in ContentCapabilities.names) {
            val writes = ContentCapabilities.onEnable(Prefs(MapStore(mapOf(Keys.enabled(feature) to false))), feature)
            assertEquals(children.map { Keys.pref(feature, it) }.toSet(), writes.keys)
            val on = writes.filterValues { it }.keys.map { it.substringAfterLast('.') }.toSet()
            assertEquals(ContentCapabilities.recommended.getValue(feature), on)
            // 打开之后必须有效果：推荐组合不能是空的
            assertTrue(on.isNotEmpty(), feature)
        }
        // 翻译 / 外部调用 / 按下截图 / 原图 不在推荐里
        val text = ContentCapabilities.onEnable(Prefs(MapStore(emptyMap())), Catalog.SCREEN_TEXT)
        assertFalse(text.getValue(Keys.pref(Catalog.SCREEN_TEXT, "translate")))
        assertFalse(text.getValue(Keys.pref(Catalog.SCREEN_TEXT, "external_trigger")))
        val image = ContentCapabilities.onEnable(Prefs(MapStore(emptyMap())), Catalog.SCREEN_IMAGE)
        assertFalse(image.getValue(Keys.pref(Catalog.SCREEN_IMAGE, "press_snapshot")))
        assertFalse(image.getValue(Keys.pref(Catalog.SCREEN_IMAGE, "source_bitmap")))
    }

    @Test fun firstEnableNeverOverridesExplicitChoices() {
        val feature = Catalog.SCREEN_IMAGE
        val key = Keys.pref(feature, "expand_region")
        val writes = ContentCapabilities.onEnable(Prefs(MapStore(mapOf(key to false))), feature)
        assertFalse(key in writes)
        assertTrue(writes.getValue(Keys.pref(feature, "screenshot_crop")))
        assertTrue(ContentCapabilities.onEnable(Prefs(MapStore(emptyMap())), Catalog.SIDE_INDEX).isEmpty())
    }

    @Test fun enabledWithNothingSelectedIsReported() {
        val feature = Catalog.SCREEN_IMAGE
        val empty = Prefs(MapStore(mapOf(Keys.enabled(feature) to true) + ContentCapabilities.names.getValue(feature).associate { Keys.pref(feature, it) to false }))
        assertTrue(ContentCapabilities.enabledButEmpty(empty, feature))
        val off = Prefs(MapStore(mapOf(Keys.enabled(feature) to false)))
        assertFalse(ContentCapabilities.enabledButEmpty(off, feature))
    }

    @Test fun togglesDisplayedInUiMatchHookChoiceAndNewDefaultsAreOff() {
        for ((feature, names) in ContentCapabilities.names) {
            val spec = Catalog.require(feature)
            for (name in names) {
                val toggle = spec.prefs.filterIsInstance<PrefSpec.Toggle>().single { it.key == Keys.pref(feature, name) }
                assertFalse(toggle.default)
                val prefs = Prefs(MapStore(mapOf(Keys.enabled(feature) to true, toggle.key to false)))
                assertEquals(ContentCapabilities.selected(prefs, feature, name), prefs.value(toggle))
            }
        }
    }
}
