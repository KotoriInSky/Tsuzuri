package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 「展开动画」预设、联动写入（DerivedPrefs）与 v5 参数精简迁移。 */
class FanMotionTest {

    private val fan = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
    private val side = Catalog.require(Catalog.SIDE_INDEX)
    private fun fk(n: String) = Keys.pref(Catalog.CORNER_SMALL_WINDOW, n)
    private fun sk(n: String) = Keys.pref(Catalog.SIDE_INDEX, n)
    private fun slider(n: String) = fan.prefs.filterIsInstance<PrefSpec.Slider>().single { it.key == fk(n) }

    @Test
    fun flymePresetEqualsTheInternalDefaults() {
        // 没选过预设的用户读到的就是四个键的默认值：两边不一致 = 「显示 Flyme、实际不是」
        val w = FanMotion.writes(FanMotion.FLYME)
        for (n in listOf(FanMotion.SPEED, FanMotion.ROTATION, FanMotion.REVEAL)) assertEquals(slider(n).default, w[fk(n)], n)
        val stagger = fan.prefs.filterIsInstance<PrefSpec.Choice>().single { it.key == fk(FanMotion.STAGGER) }
        assertEquals(stagger.default, w[fk(FanMotion.STAGGER)])
        val motion = fan.prefs.filterIsInstance<PrefSpec.Choice>().single { it.key == fk(FanMotion.NAME) }
        assertEquals(FanMotion.FLYME, motion.default)
    }

    @Test
    fun everyPresetIsInsideTheDeclaredRanges() {
        for ((name, v) in FanMotion.presets) {
            assertTrue(v.speedPercent in slider(FanMotion.SPEED).min..slider(FanMotion.SPEED).max, name)
            assertTrue(v.rotationDeg in slider(FanMotion.ROTATION).min..slider(FanMotion.ROTATION).max, name)
            assertTrue(v.revealPercent in slider(FanMotion.REVEAL).min..slider(FanMotion.REVEAL).max, name)
        }
        val motion = fan.prefs.filterIsInstance<PrefSpec.Choice>().single { it.key == fk(FanMotion.NAME) }
        assertEquals(FanMotion.presets.keys, motion.options.map { it.value }.toSet())
    }

    @Test
    fun mergedChoicesWriteAndResetTheirInternalKeys() {
        assertEquals(mapOf(sk("blur") to false), DerivedPrefs.writes(sk("material"), "none"))
        assertEquals(mapOf(sk("blur") to true), DerivedPrefs.writes(sk("material"), "glass"))
        assertEquals(mapOf(sk("haptic") to false), DerivedPrefs.writes(sk("haptic_mode"), "off"))
        assertEquals(mapOf(sk("haptic") to true), DerivedPrefs.writes(sk("haptic_mode"), "light"))
        assertEquals(4, DerivedPrefs.writes(fk(FanMotion.NAME), FanMotion.BRISK).size)
        // 每个联动写的键，恢复默认时都要一起删
        for ((key, value) in listOf(sk("material") to "blur", sk("haptic_mode") to "off", fk(FanMotion.NAME) to FanMotion.STILL)) {
            assertEquals(DerivedPrefs.writes(key, value).keys, DerivedPrefs.removals(key), key)
        }
        assertTrue(DerivedPrefs.writes(fk("corners"), "both_bottom").isEmpty())
    }

    @Test
    fun mergedDefaultsAreConsistentWithTheirInternalDefaults() {
        // 新装用户：背景默认「无」↔ blur 默认关；振动默认「标准」↔ haptic 默认开
        val material = side.prefs.filterIsInstance<PrefSpec.Choice>().single { it.key == sk("material") }
        val blur = side.prefs.filterIsInstance<PrefSpec.Toggle>().single { it.key == sk("blur") }
        assertEquals(blur.default, DerivedPrefs.writes(sk("material"), material.default)[sk("blur")])
        val mode = side.prefs.filterIsInstance<PrefSpec.Choice>().single { it.key == sk("haptic_mode") }
        val haptic = side.prefs.filterIsInstance<PrefSpec.Toggle>().single { it.key == sk("haptic") }
        assertEquals(haptic.default, DerivedPrefs.writes(sk("haptic_mode"), mode.default)[sk("haptic")])
    }

    @Test
    fun hiddenParametersAreNotExposed() {
        val exposed = fan.prefCategories().flatMap { it.prefs }.map { it.key.substringAfterLast('.') }.toSet()
        for (n in listOf("apps_mode", "icon_shape_corner", "icon_shape_exponent", "icon_edge_width", "root_activities",
            FanMotion.SPEED, FanMotion.ROTATION, FanMotion.REVEAL, FanMotion.STAGGER)) {
            assertFalse(n in exposed, n)
        }
        assertTrue(FanMotion.NAME in exposed)
        val sideExposed = side.prefCategories().flatMap { it.prefs }.map { it.key.substringAfterLast('.') }.toSet()
        for (n in listOf("haptic", "blur", "bubble_anim_ms")) assertFalse(n in sideExposed, n)
    }

    // ── v5 迁移 ──────────────────────────────────────────────────────────

    private fun plan(floats: Map<String, Float> = emptyMap(), bools: Map<String, Boolean> = emptyMap(), strings: Map<String, String> = emptyMap()) =
        ConfigMigration.plan(4, { floats[it] }, { bools[it] }, { strings[it] })!!

    @Test
    fun freshInstallWritesNothing() {
        assertTrue(plan().writes.isEmpty())
    }

    @Test
    fun retiredValuesAreRemovedAndNarrowedValuesClamped() {
        val p = plan(
            floats = mapOf(fk("icon_shape_corner") to 30f, fk(FanMotion.SPEED) to 140f, fk("hot_width_percent") to 80f, fk("trigger_percent") to 30.7f),
            bools = mapOf(fk("root_activities") to false),
            strings = mapOf(fk("apps_mode") to "auto"),
        )
        val removed = p.writes.filterIsInstance<ConfigMigration.Plan.Write.Remove>().map { it.key }.toSet()
        assertEquals(setOf(fk("icon_shape_corner"), fk(FanMotion.SPEED), fk("root_activities"), fk("apps_mode")), removed)
        val floats = p.writes.filterIsInstance<ConfigMigration.Plan.Write.FloatWrite>().associate { it.key to it.value }
        // 80% 越过上限 60%（2026-10-22 放宽后）→ 夹回；30.7%（本机实值）在 5–80 内 → 不动
        assertEquals(mapOf(fk("hot_width_percent") to 60f), floats)
    }

    @Test
    fun sideHapticAndBackgroundAreTranslated() {
        val strings = { p: ConfigMigration.Plan -> p.writes.filterIsInstance<ConfigMigration.Plan.Write.StringWrite>().associate { it.key to it.value } }
        assertEquals("off", strings(plan(bools = mapOf(sk("haptic") to false)))[sk("haptic_mode")])
        assertNull(strings(plan(bools = mapOf(sk("haptic") to true)))[sk("haptic_mode")])
        assertEquals("blur", strings(plan(bools = mapOf(sk("blur") to true)))[sk("material")])
        assertNull(strings(plan(bools = mapOf(sk("blur") to true), strings = mapOf(sk("material") to "glass")))[sk("material")])
        assertEquals("none", strings(plan(bools = mapOf(sk("blur") to false), strings = mapOf(sk("material") to "glass")))[sk("material")])
        assertTrue(plan(floats = mapOf(sk("bubble_anim_ms") to 0f)).writes.any { it is ConfigMigration.Plan.Write.Remove && it.key == sk("bubble_anim_ms") })
    }
}
