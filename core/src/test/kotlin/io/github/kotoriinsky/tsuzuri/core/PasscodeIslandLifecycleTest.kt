package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasscodeIslandLifecycleTest {
    @Test
    fun `再次复制同一内容可以重新上岛`() {
        assertTrue(PasscodeIsland.isDuplicate("same", 1_000L, "same", 2_499L))
        assertFalse(PasscodeIsland.isDuplicate("same", 1_000L, "same", 2_500L))
    }

    /**
     * 展示侧的去重窗口是**岛的存活时长**，不是 hook 的 1.5s 防抖：
     * 岛还在屏幕上时再复制一次同内容，用户要的是「还那一条」，不是第二条岛
     * （2026-10-06 反馈：重复复制同内容会出现两个岛）。
     */
    @Test
    fun `岛还活着时重复复制同一内容不再开第二条岛`() {
        val window = PasscodeIsland.timeoutMs(30f)
        assertEquals(30_000L, window)
        assertTrue(PasscodeIsland.isDuplicate("content:pkg:BV1x", 1_000L, "content:pkg:BV1x", 29_000L, window))
        assertFalse(PasscodeIsland.isDuplicate("content:pkg:BV1x", 1_000L, "content:pkg:BV1x", 31_000L, window))
    }

    @Test
    fun `超时值有默认值和上下限`() {
        assertEquals(10_000L, PasscodeIsland.timeoutMs(null))
        assertEquals(2_000L, PasscodeIsland.timeoutMs(1f))
        assertEquals(300_000L, PasscodeIsland.timeoutMs(1000f))
        assertEquals(20_000L, PasscodeIsland.timeoutMs(20f))
        assertEquals(10_000L, PasscodeIsland.timeoutMs(Float.NaN))
    }

    @Test
    fun `口令岛打开方式和超时设置已声明`() {
        val prefs = Catalog.require(Catalog.PASSCODE_ISLAND).prefs
        val mode = prefs.filterIsInstance<PrefSpec.Choice>().single {
            it.key == Keys.pref(Catalog.PASSCODE_ISLAND, "window_preset")
        }
        assertEquals("flyme", mode.default)
        assertEquals(listOf("system", "flyme", "fullscreen"), mode.options.map { it.value })
        val timeout = prefs.filterIsInstance<PrefSpec.Slider>().single {
            it.key == Keys.pref(Catalog.PASSCODE_ISLAND, "timeout_seconds")
        }
        assertEquals(10f, timeout.default)
        assertEquals(2f, timeout.min)
        assertEquals(300f, timeout.max)
    }
}
