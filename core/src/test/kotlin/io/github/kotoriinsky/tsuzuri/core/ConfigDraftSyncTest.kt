package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 草稿 → 真机的同步计划（[ConfigDraftSync]）。
 *
 * 这一层值得单测的理由不是「逻辑复杂」，而是**它错了没有任何人会发现**：
 * 同步发生在框架连上那一刻，失败/跳过的表现是「界面上改了、真机上没变」，
 * 而用户看到的现象与「参数不起作用」完全一样。真机上已经出现过一对
 * （`f.pa_widget_admission`：草稿 `true`、远端 `false`），所以那条路径在这里被钉死。
 */
class ConfigDraftSyncTest {
    @Test fun matchedDraftIsAcknowledgedWithoutRepeatingRemoteWrite() {
        remote["same"] = true
        val draft = mapOf("same" to true, "changed" to false)
        assertEquals(setOf("same"), ConfigDraftSync.acknowledgedKeys(draft) { remote[it] })
        assertEquals(listOf("changed"), assertNotNull(plan(draft)).keys)
    }

    @Test fun completedDeletionAcknowledgesStaleValueAsWell() {
        val draft = mapOf("gone" to "stale", ConfigDraftSync.DELETED_KEYS to setOf("gone", "pending"))
        remote["pending"] = "still present"
        assertEquals(setOf("gone"), ConfigDraftSync.acknowledgedKeys(draft) { remote[it] })
    }

    @Test fun acknowledgementLeavesVersionAndUnknownTypesUntouched() {
        val draft = mapOf(Keys.CONFIG_VERSION to 4, "bad" to 1.5)
        remote.putAll(draft)
        assertTrue(ConfigDraftSync.acknowledgedKeys(draft) { remote[it] }.isEmpty())
    }

    @Test fun offlineRemovalSurvivesAndNeverUploadsInternalMarker() {
        remote["side_index.icon_pack"] = setOf("old.pack")
        val p = assertNotNull(plan(mapOf(ConfigDraftSync.DELETED_KEYS to setOf("side_index.icon_pack"))))
        assertTrue(p.writes.isEmpty())
        assertEquals(setOf("side_index.icon_pack"), p.deletions)
        assertEquals(listOf("side_index.icon_pack"), p.keys)
    }

    @Test fun latestRemovalWinsOverStaleDraftValue() {
        remote["a"] = "old"
        val p = assertNotNull(plan(mapOf("a" to "stale", ConfigDraftSync.DELETED_KEYS to setOf("a"))))
        assertTrue(p.writes.isEmpty())
        assertEquals(setOf("a"), p.deletions)
    }

    @Test fun alreadyRemovedKeyNeedsNoRemoteMutation() {
        assertNull(plan(mapOf(ConfigDraftSync.DELETED_KEYS to setOf("absent", Keys.CONFIG_VERSION))))
    }

    /** 远端当前值。默认空 —— 模拟「这台机器上从没写过」。 */
    private val remote = mutableMapOf<String, Any?>()

    private fun plan(draft: Map<String, Any?>): ConfigDraftSync.Plan? =
        ConfigDraftSync.plan(draft) { remote[it] }

    @Test
    fun nothingToDoWhenTheDraftIsEmpty() {
        assertNull(plan(emptyMap()))
    }

    @Test
    fun nothingToDoWhenTheDraftOnlyHoldsTheVersion() {
        // 版本号**永远不跨存储搬运**：它描述的是「它旁边那份数据是哪一版写的」，
        // 搬过去就等于替远端声明了一个我们没验证过的版本 —— 后果是远端的迁移被跳过，
        // 用户存在远端的老值按新语义被读一遍（表现：窗口位置自己跳了）。
        assertNull(plan(mapOf(Keys.CONFIG_VERSION to 3)))
    }

    @Test
    fun aChangedValueIsWritten() {
        val p = assertNotNull(plan(mapOf("f.side_index" to true)))
        assertEquals(listOf("f.side_index"), p.keys)
        assertEquals(true, p.writes.single().value)
    }

    @Test
    fun aValueThatAlreadyMatchesTheRemoteIsNotWritten() {
        // 每写一次都会触发一次变更通知 + 延迟自动重载（重启 SystemUI），
        // 所以「值没变」必须整次跳过，不能靠后面再去重
        remote["f.side_index"] = true
        assertNull(plan(mapOf("f.side_index" to true)))
    }

    @Test
    fun aValueThatExistsRemotelyWithADifferentValueIsStillWritten() {
        // ★ 这条就是那次静默丢失的回归测试。
        //
        // 老规则的原文是「远端已经有这个键就跳过」——它的本意是「别用草稿盖掉远端」，
        // 但草稿里**只有写不出去的那些改动**（成功的写入本来就落远端了），
        // 所以那条规则的真实效果是：这批改动永远到不了真机。
        // 谁把这条断言改回去，就等于把那个 bug 装回来。
        remote["f.pa_widget_admission"] = false
        val p = assertNotNull(plan(mapOf("f.pa_widget_admission" to true)))
        assertEquals(listOf("f.pa_widget_admission"), p.keys)
    }

    @Test
    fun unknownTypesAreSkippedInsteadOfBlowingUpTheWholeSync() {
        // 一个坏键连坐所有好键，是同步最不该有的行为：`SharedPreferences` 只认
        // Boolean/Float/Int/Long/String/StringSet，别的值搬过去只会抛，
        // 而那个异常会让**整批**改动都写不进去
        val p = assertNotNull(
            plan(
                mapOf(
                    "good.one" to 42,
                    "bad.double" to 1.5,          // Double：SharedPreferences 没有这个类型
                    "bad.list" to arrayListOf(1), // 任意集合：只有 Set<String> 能存
                ),
            ),
        )
        assertEquals(listOf("good.one"), p.keys)
    }

    @Test
    fun nullValuesAreSkipped() {
        val p = assertNotNull(plan(mapOf("a" to null, "b" to "x")))
        assertEquals(listOf("b"), p.keys)
    }

    @Test
    fun writesAreSortedSoLogsAndTestsAreStable() {
        val p = assertNotNull(plan(mapOf("z" to 1, "a" to 2, "m" to 3)))
        assertEquals(listOf("a", "m", "z"), p.keys)
    }

    @Test
    fun everyStorableTypeSurvives() {
        val draft = linkedMapOf<String, Any?>(
            "b" to true,
            "f" to 63.5f,
            "i" to 7,
            "l" to 9L,
            "s" to "circle",
            "set" to setOf("com.a", "com.b"),
        )
        val p = assertNotNull(plan(draft))
        assertEquals(6, p.writes.size)
        for (w in p.writes) {
            assertTrue(ConfigDraftSync.isSupported(w.value), "值类型应当被支持：" + w.key)
            assertEquals(draft[w.key], w.value, "值必须原样搬运：" + w.key)
        }
    }

    @Test
    fun isSupportedRejectsWhatSharedPreferencesCannotStore() {
        assertTrue(ConfigDraftSync.isSupported(true))
        assertTrue(ConfigDraftSync.isSupported(1.0f))
        assertTrue(ConfigDraftSync.isSupported(1))
        assertTrue(ConfigDraftSync.isSupported(1L))
        assertTrue(ConfigDraftSync.isSupported("x"))
        assertTrue(ConfigDraftSync.isSupported(setOf("x")))
        assertTrue(!ConfigDraftSync.isSupported(1.0))
        assertTrue(!ConfigDraftSync.isSupported(listOf("x")))
    }
}
