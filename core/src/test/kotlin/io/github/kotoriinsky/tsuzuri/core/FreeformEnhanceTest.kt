package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreeformEnhanceTest {

    @Test fun defaultsMatchTempOnlyAndFullMenu() {
        val cfg = FreeformEnhance.from(Prefs(null))
        assertTrue(cfg.ephemeralRecents)
        assertEquals(FreeformEnhance.SCOPE_TEMP_ONLY, cfg.ephemeralScope)
        assertTrue(cfg.swapFullscreen)
        assertTrue(cfg.windowMute)
        assertTrue(cfg.windowOpacity)
        assertEquals(55, cfg.opacityPercent)
        assertEquals(0.55f, cfg.dimAlpha)
        assertTrue(cfg.needsCaptionRow)
    }

    @Test fun tempOnlyScopeKeepsPreExistingTasksAndRemovesTemporaryOnes() {
        val cfg = FreeformEnhance.from(Prefs(null))
        assertTrue(FreeformEnhance.shouldRemoveOnClose(cfg, "com.example.calc", wasInRecentsBefore = false))
        assertFalse(FreeformEnhance.shouldRemoveOnClose(cfg, "com.tencent.mm", wasInRecentsBefore = true))
    }

    @Test fun allScopeRemovesEvenPreExistingTasksExceptProtectedPackages() {
        val cfg = FreeformEnhance.from(Prefs(null)).copy(ephemeralScope = FreeformEnhance.SCOPE_ALL)
        assertTrue(FreeformEnhance.shouldRemoveOnClose(cfg, "com.tencent.mm", wasInRecentsBefore = true))
        assertFalse(FreeformEnhance.shouldRemoveOnClose(cfg, "com.android.systemui", wasInRecentsBefore = false))
        assertFalse(FreeformEnhance.shouldRemoveOnClose(cfg, "com.miui.home", wasInRecentsBefore = false))
    }

    @Test fun disabledEphemeralNeverRemoves() {
        val cfg = FreeformEnhance.from(Prefs(null)).copy(ephemeralRecents = false)
        assertFalse(FreeformEnhance.shouldRemoveOnClose(cfg, "com.example.calc", wasInRecentsBefore = false))
    }

    @Test fun opacityPercentIsClampedSafely() {
        assertEquals(25, FreeformEnhance.clampOpacityPercent(5f))
        assertEquals(85, FreeformEnhance.clampOpacityPercent(120f))
        assertEquals(55, FreeformEnhance.clampOpacityPercent(Float.NaN))
        assertEquals(0.4f, FreeformEnhance.alphaFromPercent(40f))
    }

    @Test fun needsCaptionRowReflectsThreeMenuSwitches() {
        val off = FreeformEnhance.from(Prefs(null)).copy(
            swapFullscreen = false,
            windowMute = false,
            windowOpacity = false,
        )
        assertFalse(off.needsCaptionRow)
        assertTrue(off.copy(swapFullscreen = true).needsCaptionRow)
        assertTrue(off.copy(windowMute = true).needsCaptionRow)
        assertTrue(off.copy(windowOpacity = true).needsCaptionRow)
    }
}
