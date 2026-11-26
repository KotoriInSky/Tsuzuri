package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreeformEnhanceTest {

    @Test fun defaultsMatchTempOnlyAndMiniGestures() {
        val cfg = FreeformEnhance.from(Prefs(null))
        assertTrue(cfg.ephemeralRecents)
        assertEquals(FreeformEnhance.SCOPE_TEMP_ONLY, cfg.ephemeralScope)
        assertTrue(cfg.miniLongPressMenu)
        assertTrue(cfg.miniDoubleTapSwap)
        assertTrue(cfg.swapToMini)
        assertTrue(cfg.needsMiniHooks)
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

    @Test fun needsMiniHooksReflectsBothMiniSwitches() {
        val off = FreeformEnhance.from(Prefs(null)).copy(
            miniLongPressMenu = false,
            miniDoubleTapSwap = false,
        )
        assertFalse(off.needsMiniHooks)
        assertTrue(off.copy(miniLongPressMenu = true).needsMiniHooks)
        assertTrue(off.copy(miniDoubleTapSwap = true).needsMiniHooks)
    }
}
