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

    @Test fun enlargedMiniScaleGrowsAndRespectsPortraitAndLandscapeCaps() {
        // 典型竖屏小窗：raw=1080x2200, movable=1020x2100, mini=0.30, normal=0.72
        val port = FreeformEnhance.computeEnlargedMiniScale(
            baseScale = 0.30f,
            rawWidth = 1080,
            rawHeight = 2200,
            movableWidth = 1020,
            movableHeight = 2100,
            restoreScale = 0.72f,
            isLandscape = false,
        )
        assertTrue(port > 0.30f * 1.30f && port <= 0.30f * 1.38f + 0.001f)
        assertTrue(port * 1080f <= 1020f * 0.56f + 0.5f)

        // 典型横屏视频小窗：raw=2200x1080, movable=1020x2100, mini=0.28, normal=0.45
        val land = FreeformEnhance.computeEnlargedMiniScale(
            baseScale = 0.28f,
            rawWidth = 2200,
            rawHeight = 1080,
            movableWidth = 1020,
            movableHeight = 2100,
            restoreScale = 0.45f,
            isLandscape = true,
        )
        assertTrue(land > 0.28f * 1.35f)
        assertTrue(land * 2200f <= 1020f * 0.88f + 0.5f)
        assertTrue(land <= 0.45f * 0.92f + 0.001f)

        // 若已经紧贴普通小窗，则不再放大
        val tight = FreeformEnhance.computeEnlargedMiniScale(
            baseScale = 0.65f,
            rawWidth = 1080,
            rawHeight = 2200,
            movableWidth = 1020,
            movableHeight = 2100,
            restoreScale = 0.70f,
            isLandscape = false,
        )
        assertEquals(0.65f, tight)
    }
}
