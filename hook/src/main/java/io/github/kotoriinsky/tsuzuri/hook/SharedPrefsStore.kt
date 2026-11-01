package io.github.kotoriinsky.tsuzuri.hook

import android.content.SharedPreferences
import io.github.kotoriinsky.tsuzuri.core.KeyValueStore

/**
 * `SharedPreferences` → [KeyValueStore] 的适配器（后端侧）。
 *
 * 前端 `:ui` 里有一份功能相同的私有实现 —— 这里**故意重复**，不抽到 `:core`：
 * `:core` 是纯 JVM 模块，引了 `android.content.SharedPreferences` 就再也跑不了单元测试，
 * 而「配置读取逻辑可测」比「少写 25 行胶水」重要得多。
 *
 * 每个方法都吞掉异常：远程实现（`RemotePreferences`）在框架侧出错时会抛，
 * 而配置读失败的唯一正确行为是**回退默认值**（规范 §9）。
 */
class SharedPrefsStore(private val sp: SharedPreferences) : KeyValueStore {
    override fun contains(key: String): Boolean = try { sp.contains(key) } catch (_: Throwable) { false }
    override fun getBoolean(key: String, defValue: Boolean) =
        try {
            sp.getBoolean(key, defValue)
        } catch (_: Throwable) {
            defValue
        }

    override fun getInt(key: String, defValue: Int) =
        try {
            sp.getInt(key, defValue)
        } catch (_: Throwable) {
            defValue
        }

    override fun getFloat(key: String, defValue: Float) =
        try {
            sp.getFloat(key, defValue)
        } catch (_: Throwable) {
            defValue
        }

    override fun getString(key: String, defValue: String?) =
        try {
            sp.getString(key, defValue)
        } catch (_: Throwable) {
            defValue
        }

    override fun getStringSet(key: String, defValue: Set<String>): Set<String> =
        try {
            sp.getStringSet(key, defValue) ?: defValue
        } catch (_: Throwable) {
            defValue
        }
}
