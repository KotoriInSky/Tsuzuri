package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 拖动中的草稿叠加（[Prefs.withFloats]）。
 *
 * 这件事的形状是「界面显示的值 ≠ 已落盘的值」，所以断言必须**同时**盯住两边：
 * 叠上去的那一项要跟着变，其余项与底层存储一个字节都不能动。
 */
class PrefsOverlayTest {

    private val key = "corner_small_window.window_x_percent"

    @Test
    fun overlayWinsForTheDraftedKeyAndLeavesEverythingElseAlone() {
        val base = Prefs(MapStore(mapOf(key to 20f, "corner_small_window.window_y_percent" to 30f)))
        val draft = base.withFloats(mapOf(key to 42f))
        assertEquals(42f, draft.rawFloatOrNull(key), "叠上去的那一项应当是草稿值")
        assertEquals(30f, draft.rawFloatOrNull("corner_small_window.window_y_percent"), "其余项原样")
        // 底层（= 真机读的那份）不能被草稿改掉 —— 落盘只发生在抬手那一刻
        assertEquals(20f, base.rawFloatOrNull(key))
    }

    @Test
    fun overlayOnlyInterceptsFloats() {
        val store = MapStore(
            mapOf(
                "corner_small_window.window_preset" to "custom",
                "corner_small_window.corners" to "both_bottom",
                "corner_small_window.items" to "a\nb",
            ),
        )
        val prefs = Prefs(store).withFloats(mapOf(key to 42f))
        assertEquals("custom", prefs.rawString("corner_small_window.window_preset", null))
        assertEquals("both_bottom", prefs.rawString("corner_small_window.corners", null))
        assertEquals("a\nb", prefs.rawString("corner_small_window.items", null))
        // `contains` 走草稿通道时也必须如实回答（迁移与 `rawBooleanOrNull` 靠它区分
        // 「设成了 false」与「没设过」）
        assertTrue(prefs.rawBooleanOrNull("corner_small_window.window_custom") == null)
    }

    @Test
    fun emptyDraftReturnsTheSameInstance() {
        // ⚠️ 必须是同一个实例：预览那边的图标解码是按 `Prefs` 实例做缓存的，
        // 每次重组造一个等价的新对象会让它整批重解码（表现是拖动时更热、图标闪一下）。
        val base = Prefs(MapStore(mapOf(key to 20f)))
        assertSame(base, base.withFloats(emptyMap()))
    }

    @Test
    fun nullStoreStillWorks() {
        // 框架没连上时 store 可能是 null（配置读不到，一切走默认值）——
        // 草稿这条路也要能跑，否则「没连框架时拖滑杆」会崩。
        val prefs = Prefs(null).withFloats(mapOf(key to 55f))
        assertEquals(55f, prefs.rawFloatOrNull(key))
        assertEquals(false, prefs.rawBoolean("anything", false))
    }
}
