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
    fun bitmaskEncodesEachFeatureIndependently() {
        val styleOnly = Prefs(MapStore(mapOf(Keys.enabled(Catalog.DESKTOP_WIDGET_STYLE) to true)))
        assertEquals(DesktopWidget.BIT_STYLE, DesktopWidget.computeFlags(styleOnly))

        val dragOnly = Prefs(MapStore(mapOf(Keys.enabled(Catalog.DESKTOP_WIDGET_DRAG_PA) to true)))
        assertEquals(DesktopWidget.BIT_DRAG_PA, DesktopWidget.computeFlags(dragOnly))

        val stackOnly = Prefs(MapStore(mapOf(Keys.enabled(Catalog.DESKTOP_WIDGET_STACK) to true)))
        assertEquals(DesktopWidget.BIT_STACK, DesktopWidget.computeFlags(stackOnly))

        val allOn = Prefs(
            MapStore(
                mapOf(
                    Keys.enabled(Catalog.DESKTOP_WIDGET_STYLE) to true,
                    Keys.enabled(Catalog.DESKTOP_WIDGET_DRAG_PA) to true,
                    Keys.enabled(Catalog.DESKTOP_WIDGET_STACK) to true,
                ),
            ),
        )
        assertEquals(DesktopWidget.MASK_ALL, DesktopWidget.computeFlags(allOn))
        assertEquals("7", DesktopWidget.formatFlags(DesktopWidget.computeFlags(allOn)))
    }

    @Test
    fun parseFlagsIsFailClosedOnInvalidInputs() {
        for (v in 0..DesktopWidget.MASK_ALL) {
            assertEquals(v, DesktopWidget.parseFlags(v.toString()))
            assertEquals(v, DesktopWidget.parseFlags("  $v\n"))
        }
        for (bad in listOf(null, "", " ", "-1", "8", "999", "abc", "1a", "0x7")) {
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
        assertNull(DesktopWidget.parseConfigFile("tsuzuri_home v=1 flags=8"))
        assertNull(DesktopWidget.parseConfigFile("tsuzuri_home v=1 flags=-1"))
        assertNull(DesktopWidget.parseConfigFile("tsuzuri_home v=1"))
    }

    @Test
    fun prefKeyMatcherRecognizesOnlyDesktopWidgetSwitches() {
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey("f.desktop_widget_style"))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey("f.desktop_widget_drag_pa"))
        assertTrue(DesktopWidget.isDesktopWidgetPrefKey("f.desktop_widget_stack"))
        assertFalse(DesktopWidget.isDesktopWidgetPrefKey("f.widget_style"))
        assertFalse(DesktopWidget.isDesktopWidgetPrefKey(null))
    }
}
