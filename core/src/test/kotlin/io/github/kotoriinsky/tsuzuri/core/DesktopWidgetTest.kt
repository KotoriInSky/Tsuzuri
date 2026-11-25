package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWidgetTest {

    @Test
    fun allThreeFeaturesDefaultOffAndTargetHomeAndSystemServer() {
        val empty = Prefs(MapStore(emptyMap()))
        assertEquals(0, DesktopWidget.computeFlags(empty))
        for (key in DesktopWidget.FEATURE_KEYS) {
            val spec = Catalog.require(key)
            assertFalse(empty.isEnabled(spec), "$key 默认必须关闭")
            assertEquals(listOf(ScopeTarget.HOME, ScopeTarget.SYSTEM_SERVER), spec.scope)
            assertEquals(Maturity.EXPERIMENTAL, spec.maturity)
        }
        assertEquals(RestartPolicy.PROCESS_RESTART, Catalog.require(Catalog.DESKTOP_WIDGET_STYLE).restart)
        assertEquals(RestartPolicy.HOT, Catalog.require(Catalog.DESKTOP_WIDGET_DRAG_PA).restart)
        assertEquals(RestartPolicy.HOT, Catalog.require(Catalog.DESKTOP_WIDGET_STACK).restart)
    }

    @Test
    fun bitmaskEncodesMainFeaturesAndStyleSubOptions() {
        // 打开风格总开关时，三个默认 true 的子项自动联动置位
        val styleDefault = Prefs(MapStore(mapOf(Keys.enabled(Catalog.DESKTOP_WIDGET_STYLE) to true)))
        assertEquals(
            DesktopWidget.BIT_STYLE or
                DesktopWidget.BIT_STYLE_NO_WORD or
                DesktopWidget.BIT_STYLE_DRAG_LIGHT or
                DesktopWidget.BIT_STYLE_UNIFY_SCALE,
            DesktopWidget.computeFlags(styleDefault),
        )

        // 子项可独立关闭
        val styleOnlyCore = Prefs(
            MapStore(
                mapOf(
                    Keys.enabled(Catalog.DESKTOP_WIDGET_STYLE) to true,
                    DesktopWidget.PREF_FOLLOW_NO_WORD to false,
                    DesktopWidget.PREF_DRAG_LIGHT to false,
                    DesktopWidget.PREF_UNIFY_SCALE to false,
                ),
            ),
        )
        assertEquals(DesktopWidget.BIT_STYLE, DesktopWidget.computeFlags(styleOnlyCore))

        // 总开关关闭时，即使子项显式 true，也绝不置位
        val styleOffSubOn = Prefs(
            MapStore(
                mapOf(
                    Keys.enabled(Catalog.DESKTOP_WIDGET_STYLE) to false,
                    DesktopWidget.PREF_FOLLOW_NO_WORD to true,
                    DesktopWidget.PREF_DRAG_LIGHT to true,
                    DesktopWidget.PREF_UNIFY_SCALE to true,
                ),
            ),
        )
        assertEquals(0, DesktopWidget.computeFlags(styleOffSubOn))

        val dragOnly = Prefs(MapStore(mapOf(Keys.enabled(Catalog.DESKTOP_WIDGET_DRAG_PA) to true)))
        assertEquals(DesktopWidget.BIT_DRAG_PA, DesktopWidget.computeFlags(dragOnly))

        val stackOnly = Prefs(MapStore(mapOf(Keys.enabled(Catalog.DESKTOP_WIDGET_STACK) to true)))
        assertEquals(DesktopWidget.BIT_STACK, DesktopWidget.computeFlags(stackOnly))

        // 侧滑索引开启时，drawer_gate 默认 true → 自动置位 BIT_OVERLAY_GATE
        val sideDefault = Prefs(MapStore(mapOf(Keys.enabled(Catalog.SIDE_INDEX) to true)))
        assertEquals(DesktopWidget.BIT_OVERLAY_GATE, DesktopWidget.computeFlags(sideDefault))

        // 侧滑索引开启但显式关闭 drawer_gate → 不置位
        val sideGateOff = Prefs(
            MapStore(
                mapOf(
                    Keys.enabled(Catalog.SIDE_INDEX) to true,
                    DesktopWidget.PREF_SIDE_INDEX_DRAWER_GATE to false,
                ),
            ),
        )
        assertEquals(0, DesktopWidget.computeFlags(sideGateOff))

        val allOn = Prefs(
            MapStore(
                mapOf(
                    Keys.enabled(Catalog.DESKTOP_WIDGET_STYLE) to true,
                    Keys.enabled(Catalog.DESKTOP_WIDGET_DRAG_PA) to true,
                    Keys.enabled(Catalog.DESKTOP_WIDGET_STACK) to true,
                    Keys.enabled(Catalog.SIDE_INDEX) to true,
                ),
            ),
        )
        assertEquals(DesktopWidget.MASK_ALL, DesktopWidget.computeFlags(allOn))
        assertEquals("127", DesktopWidget.formatFlags(DesktopWidget.computeFlags(allOn)))
    }

    @Test
    fun parseFlagsIsFailClosedOnInvalidInputs() {
        for (v in 0..DesktopWidget.MASK_ALL) {
            assertEquals(v, DesktopWidget.parseFlags(v.toString()))
            assertEquals(v, DesktopWidget.parseFlags("  $v\n"))
        }
        for (bad in listOf(null, "", " ", "-1", "128", "999", "abc", "1a", "0x7")) {
            assertEquals(0, DesktopWidget.parseFlags(bad), "非法值 $bad 必须回退 0")
        }
    }

    @Test
    fun configFileRoundTripAndStrictValidation() {
        for (v in 0..DesktopWidget.MASK_ALL) {
            val text = DesktopWidget.formatConfigFile(v)
            assertEquals(v, DesktopWidget.parseConfigFile(text))
        }
        assertNull(DesktopWidget.parseConfigFile(null))
        assertNull(DesktopWidget.parseConfigFile(""))
        assertNull(DesktopWidget.parseConfigFile("wrong v=1 flags=1"))
        assertNull(DesktopWidget.parseConfigFile("tsuzuri_home v=2 flags=1"))
        assertNull(DesktopWidget.parseConfigFile("tsuzuri_home v=1 flags=128"))
        assertNull(DesktopWidget.parseConfigFile("tsuzuri_home v=1 flags=-1"))
        assertNull(DesktopWidget.parseConfigFile("tsuzuri_home v=1"))
    }

    @Test
    fun prefKeyMatcherRecognizesSwitchesAndStyleSubPrefs() {
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey("f.desktop_widget_style"))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey("f.desktop_widget_drag_pa"))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey("f.desktop_widget_stack"))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey(DesktopWidget.PREF_FOLLOW_NO_WORD))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey(DesktopWidget.PREF_DRAG_LIGHT))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey(DesktopWidget.PREF_UNIFY_SCALE))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey(DesktopWidget.PREF_SIDE_INDEX_ENABLED))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey(DesktopWidget.PREF_SIDE_INDEX_DRAWER_GATE))
        assertFalse(DesktopWidget.isDesktopWidgetPrefKey("f.widget_style"))
        assertFalse(DesktopWidget.isDesktopWidgetPrefKey("widget_style.unify_corner"))
        assertFalse(DesktopWidget.isDesktopWidgetPrefKey(null))
    }
}
