package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 配置键名的格式契约。
 *
 * 这些断言全部写死字面量，而不是「用 [Keys] 自己算一遍再和自己比」——
 * 键名是**跨进程契约**：前端（模块 App，用户随时可能更新）和后端（被注入的 SystemUI /
 * 负一屏 / system_server，可能还在跑几小时前加载的旧版本）各自拼键名。两边版本错开时，
 * 格式一变就是「写的和读的不是同一个键」，**不会报任何错**，症状只有一个：
 * 用户开关打开了没反应。这类 bug 在日志里几乎不可见，所以只能在这里钉死。
 *
 * 要改格式，等于要一次覆盖全量用户的配置迁移（`Keys.CURRENT_CONFIG_VERSION` +1），
 * 必须是一次有意的决定，而不是顺手重构。
 */
class KeysTest {

    @Test
    fun featureSwitchKeyFormatIsFrozen() {
        // `f.` 前缀把「总开关」和「参数」分在两个命名空间里，这个前缀一旦变就全丢。
        assertEquals("f.corner_small_window", Keys.enabled("corner_small_window"))
        assertEquals("f.corner_small_window", Keys.enabled(Catalog.CORNER_SMALL_WINDOW))
    }

    @Test
    fun prefKeyFormatIsFrozen() {
        assertEquals("corner_small_window.trigger_size", Keys.pref("corner_small_window", "trigger_size"))
        assertEquals("corner_small_window.poll_interval_ms", Keys.pref(Catalog.CORNER_SMALL_WINDOW, "poll_interval_ms"))
    }

    @Test
    fun groupAndConfigVersionKeysAreFrozen() {
        // 分组名换了 = 后端 `getRemotePreferences(GROUP)` 读到一个空文件，全量配置静默失效。
        // 0.1.x 是 "flymeport"；随包名迁移改为 "tsuzuri"，旧配置走导出/恢复通道搬运
        // （tools/migrate-config.py 导出，设置页恢复写入）。要再改它必须同时改迁移路径。
        assertEquals("tsuzuri", Keys.GROUP)
        // 版本字段是迁移逻辑的唯一开关，改名等于丢掉「这份配置是哪一版写的」这个信息。
        assertEquals("__cfg_version", Keys.CONFIG_VERSION)
    }

    @Test
    fun switchKeysAndPrefKeysNeverOverlap() {
        // 两类键共用同一个 SharedPreferences 文件。一旦某个特性 key 恰好让
        // `f.<key>` 等于另一份 `Keys.pref(feature, pref)`，总开关就会去读某个参数的布尔值 ——
        // 比如某天有人把滑杆参数命名成 "f" 下的东西，勾选状态就会被数值读串。
        val switchKeys = Catalog.features.map { Keys.enabled(it.key) }
        val prefKeys = Catalog.features.flatMap { f -> f.prefs.map { it.key } } + Catalog.MIUI_WIDGET_PACKAGES_KEY
        assertTrue(
            switchKeys.intersect(prefKeys.toSet()).isEmpty(),
            "总开关键与参数键重叠：${switchKeys.intersect(prefKeys.toSet())}",
        )
    }
}
