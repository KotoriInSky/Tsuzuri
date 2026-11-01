package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanStatusBarTest {

    @Test
    fun enterStartsWithEnterAndEveryCommandIsKnownToDemoMode() {
        // 演示模式只认 DemoMode.COMMANDS 里的命令（本机反编译）；拼错一个 = 那一项静默不生效
        val known = setOf("enter", "exit", "bars", "battery", "clock", "network", "notifications", "operator", "status", "volume")
        assertEquals("enter", CleanStatusBar.enterCommands.first()["command"])
        assertTrue(CleanStatusBar.enterCommands.all { it["command"] in known })
        assertEquals(listOf("exit"), CleanStatusBar.exitCommands.map { it["command"] })
    }

    @Test
    fun featureDeclaresBothSubItemsUnderMisc() {
        val spec = Catalog.require(Catalog.CLEAN_STATUSBAR)
        assertEquals(FeatureGroup.APPS, spec.group)
        assertEquals(listOf(ScopeTarget.SYSTEM_UI, ScopeTarget.SYSTEM_SERVER), spec.scope)
        val names = spec.prefs.map { it.key.substringAfter('.') }.toSet()
        assertEquals(setOf(CleanStatusBar.HIDE_NOTIFICATION_ICONS, CleanStatusBar.HIDE_ISLAND, CleanStatusBar.AUTO_SCREENSHOT, CleanStatusBar.AUTO_RECORDING), names)
        assertEquals(FeaturePreview.STATUS_BAR, spec.presentation.preview)
    }
}
