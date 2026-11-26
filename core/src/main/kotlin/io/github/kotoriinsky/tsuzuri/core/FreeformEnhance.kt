package io.github.kotoriinsky.tsuzuri.core

import kotlin.math.roundToInt

/**
 * 小窗增强套件的纯逻辑契约（`:core` / `:hook` / `:ui` 共享）。
 *
 * 涵盖四项小窗交互增强，全部归在 [Catalog.FREEFORM_ENHANCE] 总开关下，关态 0 hook：
 *  1. **即用即走（关闭不留后台卡片）**：默认仅清除本次以小窗临时拉起、原本不在最近任务里的任务；
 *     也可切换为「全部关闭的小窗」均移除最近任务卡片；
 *  2. **主副窗一键互换**：在小窗顶部「…」原生菜单追加按钮，当前小窗升全屏、底层全屏应用降小窗；
 *  3. **单窗静音**：仅静音该小窗所属应用的播放流，关窗或转全屏自动还原；
 *  4. **单窗半透明**：切换该小窗 Surface 透明度，关窗或转全屏自动还原为 1.0。
 */
object FreeformEnhance {

    const val PREF_EPHEMERAL = "ephemeral_recents"
    const val PREF_EPHEMERAL_SCOPE = "ephemeral_scope"
    const val PREF_SWAP_FULLSCREEN = "swap_fullscreen"
    const val PREF_WINDOW_MUTE = "window_mute"
    const val PREF_WINDOW_OPACITY = "window_opacity"
    const val PREF_OPACITY_PERCENT = "opacity_percent"

    /** 仅清除临时拉起的小窗（打开小窗前原本已在最近任务里运行的保留）。 */
    const val SCOPE_TEMP_ONLY = "temp_only"

    /** 所有通过关闭手势或关闭按钮退出的小窗一律不留最近任务卡片。 */
    const val SCOPE_ALL = "all"

    const val DEFAULT_EPHEMERAL = true
    const val DEFAULT_EPHEMERAL_SCOPE = SCOPE_TEMP_ONLY
    const val DEFAULT_SWAP_FULLSCREEN = true
    const val DEFAULT_WINDOW_MUTE = true
    const val DEFAULT_WINDOW_OPACITY = true

    const val MIN_OPACITY_PERCENT = 25f
    const val MAX_OPACITY_PERCENT = 85f
    const val DEFAULT_OPACITY_PERCENT = 55f

    /** 永不应因「即用即走」被移除的系统核心包。 */
    val PROTECTED_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.miui.home",
        "android",
    )

    data class Config(
        val ephemeralRecents: Boolean,
        val ephemeralScope: String,
        val swapFullscreen: Boolean,
        val windowMute: Boolean,
        val windowOpacity: Boolean,
        val opacityPercent: Int,
    ) {
        /** 顶部「…」菜单是否需要注入扩展按钮行。 */
        val needsCaptionRow: Boolean
            get() = swapFullscreen || windowMute || windowOpacity

        /** 半透明态对应的 SurfaceControl alpha（0.25f..0.85f）。 */
        val dimAlpha: Float
            get() = alphaFromPercent(opacityPercent.toFloat())
    }

    fun from(prefs: Prefs): Config {
        val f = Catalog.FREEFORM_ENHANCE
        val scope = prefs.rawString(Keys.pref(f, PREF_EPHEMERAL_SCOPE), DEFAULT_EPHEMERAL_SCOPE)
            .takeIf { it == SCOPE_TEMP_ONLY || it == SCOPE_ALL }
            ?: DEFAULT_EPHEMERAL_SCOPE
        val pct = clampOpacityPercent(
            prefs.rawFloatOrNull(Keys.pref(f, PREF_OPACITY_PERCENT)) ?: DEFAULT_OPACITY_PERCENT,
        )
        return Config(
            ephemeralRecents = prefs.rawBoolean(Keys.pref(f, PREF_EPHEMERAL), DEFAULT_EPHEMERAL),
            ephemeralScope = scope,
            swapFullscreen = prefs.rawBoolean(Keys.pref(f, PREF_SWAP_FULLSCREEN), DEFAULT_SWAP_FULLSCREEN),
            windowMute = prefs.rawBoolean(Keys.pref(f, PREF_WINDOW_MUTE), DEFAULT_WINDOW_MUTE),
            windowOpacity = prefs.rawBoolean(Keys.pref(f, PREF_WINDOW_OPACITY), DEFAULT_WINDOW_OPACITY),
            opacityPercent = pct,
        )
    }

    fun clampOpacityPercent(percent: Float): Int {
        val safe = if (percent.isFinite()) percent else DEFAULT_OPACITY_PERCENT
        return safe.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT).roundToInt()
    }

    fun alphaFromPercent(percent: Float): Float = clampOpacityPercent(percent) / 100f

    /**
     * 判定一个刚刚通过「关闭」退出的小窗是否应当从最近任务移除。
     *
     * @param packageName 任务所属包名（受保护系统包永远不移除）
     * @param wasInRecentsBefore 该任务在以小窗出现之前是否已经存在于最近任务列表中
     */
    fun shouldRemoveOnClose(
        config: Config,
        packageName: String?,
        wasInRecentsBefore: Boolean,
    ): Boolean {
        if (!config.ephemeralRecents) return false
        if (packageName != null && packageName in PROTECTED_PACKAGES) return false
        return when (config.ephemeralScope) {
            SCOPE_ALL -> true
            else -> !wasInRecentsBefore
        }
    }
}
