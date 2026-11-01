package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kotoriinsky.tsuzuri.ui.model.AccentMode
import io.github.kotoriinsky.tsuzuri.ui.model.Appearance
import io.github.kotoriinsky.tsuzuri.ui.model.DarkMode
import io.github.kotoriinsky.tsuzuri.ui.model.Density
import io.github.kotoriinsky.tsuzuri.ui.model.FontScale
import io.github.kotoriinsky.tsuzuri.ui.model.UiStyle

/**
 * 外观偏好的持久化。
 *
 * 和 [ConfigStore] 完全分开：外观只影响这个设置界面，**不进 RemotePreferences**。
 * 混在一起会有两个后果：改主题色触发目标进程重载、以及后端要为一个
 * 它根本不需要的配置项做兼容。为此多一个 SharedPreferences 文件是便宜的。
 *
 * 所有 enum 都用 `valueOf` + 兜底：用户在旧版本存过、或文件被手改过时，
 * 不该让设置界面崩掉 —— 读不到就回默认值。
 */
object AppearanceStore {

    private lateinit var prefs: android.content.SharedPreferences

    var current by mutableStateOf(Appearance())
        private set

    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(Appearance.FILE, Context.MODE_PRIVATE)
        current = read()
    }

    private fun read(): Appearance = Appearance(
        style = enumOr(prefs.getString(Appearance.KEY_STYLE, null), UiStyle.MIUIX),
        dark = enumOr(prefs.getString(Appearance.KEY_DARK, null), DarkMode.SYSTEM),
        accent = enumOr(prefs.getString(Appearance.KEY_ACCENT, null), AccentMode.MIUI_BLUE),
        customAccentArgb = prefs.getInt(Appearance.KEY_CUSTOM, Appearance.MIUI_BLUE),
        // 字号 / 信息密度 / 纯黑深色三项已下线（用户 2026-10-25）：不再读旧值，一律按默认，
        // 否则改过的人会卡在一个界面上已经找不到的设置里。
        density = Density.STANDARD,
        fontScale = FontScale.NORMAL,
        amoled = false,
        // 默认 true：与 Appearance 的数据类默认值保持一致。两处默认值必须一样，
        // 否则「首次安装」和「清数据后」读到的外观会不同 —— 这类漂移只有用户能发现。
        barBlur = prefs.getBoolean(Appearance.KEY_BAR_BLUR, true),
        floatingBar = prefs.getBoolean(Appearance.KEY_FLOATING_BAR, true),
        glassBars = prefs.getBoolean(Appearance.KEY_GLASS_BARS, false),
    )

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        runCatching { enumValueOf<T>(raw ?: fallback.name) }.getOrDefault(fallback)

    fun update(block: (Appearance) -> Appearance) {
        val next = block(current)
        current = next
        prefs.edit()
            .putString(Appearance.KEY_STYLE, next.style.name)
            .putString(Appearance.KEY_DARK, next.dark.name)
            .putString(Appearance.KEY_ACCENT, next.accent.name)
            .putInt(Appearance.KEY_CUSTOM, next.customAccentArgb)
            .putString(Appearance.KEY_DENSITY, next.density.name)
            .putString(Appearance.KEY_FONT_SCALE, next.fontScale.name)
            .putBoolean(Appearance.KEY_AMOLED, next.amoled)
            .putBoolean(Appearance.KEY_BAR_BLUR, next.barBlur)
            .putBoolean(Appearance.KEY_FLOATING_BAR, next.floatingBar)
            .putBoolean(Appearance.KEY_GLASS_BARS, next.glassBars)
            .apply()
    }

    /** 恢复外观出厂值。走 [update] 而不是直接删键，保证内存状态与磁盘同时回退。 */
    fun reset() {
        update { Appearance() }
    }
}
