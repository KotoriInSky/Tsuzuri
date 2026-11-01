package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设置界面自己的行为偏好。同样**不进 RemotePreferences**（理由见 [AppearanceStore]）。
 *
 * 属性用自定义 setter 直接落盘，而不是再暴露一个 `setXxx()` 方法 ——
 * Kotlin 会为 `var` 自动生成 `setXxx(Z)V`，手写同名方法会撞 JVM 签名。
 * 这样调用方写 `AppSettings.hapticFeedback = false` 就够了。
 *
 * ⚠️ 曾经有过一条 `showUnimplemented`（「显示未实现的模块」，用户可见开关）。
 * 它已删除：**未实现/搁置的特性该不该出现在列表里，不是用户的决定**——
 * 这件事由 `Catalog` 的 `maturity` + `presentation.listed` 一条规则说了算
 * （见 `FeaturePresentation.isListedInSettings`），界面只读结论。
 */
object AppSettings {

    private const val FILE = "app_settings"
    private const val KEY_KEEP_SEARCH_HISTORY = "keep_search_history"
    private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    private const val KEY_AUTO_RELOAD = "auto_reload"
    private const val KEY_HISTORY = "search_history"
    private const val KEY_HISTORY_ORDERED = "search_history_ordered"

    private const val HISTORY_LIMIT = 8

    /**
     * 搜索历史的分隔符。
     *
     * 为什么不再用 StringSet：`SharedPreferences` 的 StringSet 是**无序**的，
     * 而「最近搜索」的顺序本身就是它全部的信息 —— 之前存进去再 `sorted()` 读出来，
     * 列表变成按字母排，最近搜的词沉到底下，等于这个功能白做。
     * 换成一个带分隔符的字符串，顺序就是原样。
     */
    private const val HISTORY_SEP = "\n"

    private lateinit var prefs: android.content.SharedPreferences

    private var stateKeepSearchHistory by mutableStateOf(false)
    private var stateHapticFeedback by mutableStateOf(true)
    private var stateAutoReload by mutableStateOf(false)
    private var statePreviewWallpaper by mutableStateOf(false)
    var previewWallpaper: Boolean
        get() = statePreviewWallpaper
        set(value) {
            statePreviewWallpaper = value
            prefs.edit().putBoolean("preview_wallpaper", value).apply()
        }

    var keepSearchHistory: Boolean
        get() = stateKeepSearchHistory
        set(value) {
            stateKeepSearchHistory = value
            prefs.edit().putBoolean(KEY_KEEP_SEARCH_HISTORY, value).apply()
            if (!value) clearHistory()
        }

    /**
     * 点击列表项时是否振动。
     *
     * 默认开：设置界面里「点了没反应」是最容易让人怀疑模块坏掉的情形，
     * 一次轻振动是最便宜的状态确认。它只作用于设置界面自己，和系统手势无关。
     */
    var hapticFeedback: Boolean
        get() = stateHapticFeedback
        set(value) {
            stateHapticFeedback = value
            prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()
        }

    /**
     * 改完配置是否自动重载目标进程。默认关。
     *
     * 默认关的理由：重载会让系统界面 / 负一屏有一次可见的重建（虽然不闪屏），
     * 属于「用户没要求就不该发生」的副作用。开着更省事，但必须由用户自己选。
     */
    var autoReload: Boolean
        get() = stateAutoReload
        set(value) {
            stateAutoReload = value
            prefs.edit().putBoolean(KEY_AUTO_RELOAD, value).apply()
        }

    /** 最近搜索，最新的在前。 */
    var searchHistory by mutableStateOf<List<String>>(emptyList())
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        stateKeepSearchHistory = prefs.getBoolean(KEY_KEEP_SEARCH_HISTORY, false)
        stateHapticFeedback = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        stateAutoReload = prefs.getBoolean(KEY_AUTO_RELOAD, false)
        // 默认开：系统壁纸由 SystemUI 推送、稳定可得，预览没有理由默认画示意底图
        statePreviewWallpaper = prefs.getBoolean("preview_wallpaper", true)
        searchHistory = readHistory()
    }

    private fun readHistory(): List<String> {
        val ordered = prefs.getString(KEY_HISTORY_ORDERED, null)
        if (ordered != null) return ordered.split(HISTORY_SEP).filter { it.isNotEmpty() }
        // 旧版本用无序的 StringSet 存过。只在升级那一刻承认它一次，
        // 随即写成保序的形式，免得每次启动都把顺序再打乱一遍。
        val legacy = prefs.getStringSet(KEY_HISTORY, emptySet()).orEmpty().toList()
        if (legacy.isNotEmpty()) persistHistory(legacy)
        return legacy
    }

    fun rememberQuery(query: String) {
        if (!keepSearchHistory) return
        // 换行是存储用的分隔符，而单行输入框里本来也不该有换行 —— 统一拍平，避免把一条拆成两条
        val q = query.trim().replace(HISTORY_SEP, " ")
        if (q.isEmpty() || q.length > 40) return
        val next = (listOf(q) + searchHistory.filterNot { it.equals(q, ignoreCase = true) })
            .take(HISTORY_LIMIT)
        searchHistory = next
        persistHistory(next)
    }

    fun clearHistory() {
        searchHistory = emptyList()
        prefs.edit().remove(KEY_HISTORY_ORDERED).remove(KEY_HISTORY).apply()
    }

    /**
     * 行为开关恢复默认。
     *
     * 刻意**不碰 searchHistory**：搜索历史是用户数据，不是设置项 ——
     * 外观/行为回复默认时把用户搜过什么一起抹掉，是两件不同性质的事。
     */
    fun resetBehavior() {
        hapticFeedback = true
        autoReload = false
    }

    private fun persistHistory(list: List<String>) {
        prefs.edit().putString(KEY_HISTORY_ORDERED, list.joinToString(HISTORY_SEP)).apply()
    }
}
