package io.github.kotoriinsky.tsuzuri.core

/**
 * 小窗增强套件的纯逻辑契约（`:core` / `:hook` / `:ui` 共享）。
 *
 * 全部归在 [Catalog.FREEFORM_ENHANCE] 总开关下，关态 0 hook，围绕两个高频痛点设计：
 *  1. **即用即走（关闭不留后台卡片）**：默认仅清除本次以小窗临时拉起、原本不在最近任务里的任务；
 *     也可切换为「全部关闭的小窗」均移除最近任务卡片；
 *  2. **迷你小窗手势（Flyme 风格浮层 + 主副互换）**：
 *     - 单击迷你小窗保持 HyperOS 原生行为（立刻展开回普通小窗）；
 *     - 长按迷你小窗弹出贴合窗体的半透明圆角浮层，提供「静音/恢复」与「与主屏互换」；
 *     - 双击迷你小窗直接与底层全屏应用互换（迷你窗升全屏、原全屏自动缩回迷你小窗或普通小窗）；
 *     - 静音仅临时压低该应用的播放流音量，关窗或切回全屏时自动恢复。
 */
object FreeformEnhance {

    const val PREF_EPHEMERAL = "ephemeral_recents"
    const val PREF_EPHEMERAL_SCOPE = "ephemeral_scope"
    const val PREF_MINI_LONG_PRESS_MENU = "mini_long_press_menu"
    const val PREF_MINI_DOUBLE_TAP_SWAP = "mini_double_tap_swap"
    const val PREF_SWAP_TO_MINI = "swap_to_mini"

    /** 仅清除临时拉起的小窗（打开小窗前原本已在最近任务里运行的保留）。 */
    const val SCOPE_TEMP_ONLY = "temp_only"

    /** 所有通过关闭手势或关闭按钮退出的小窗一律不留最近任务卡片。 */
    const val SCOPE_ALL = "all"

    const val DEFAULT_EPHEMERAL = true
    const val DEFAULT_EPHEMERAL_SCOPE = SCOPE_TEMP_ONLY
    const val DEFAULT_MINI_LONG_PRESS_MENU = true
    const val DEFAULT_MINI_DOUBLE_TAP_SWAP = true
    const val DEFAULT_SWAP_TO_MINI = true

    /** 永不应因「即用即走」被移除的系统核心包。 */
    val PROTECTED_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.miui.home",
        "android",
    )

    /** 竖屏迷你小窗略微放大倍率（贴近 Flyme 大小迷你窗两档切换）。 */
    const val MINI_ENLARGE_FACTOR_PORTRAIT = 1.38f

    /** 横屏视频迷你小窗略微放大倍率（方便看清字幕/直播画面）。 */
    const val MINI_ENLARGE_FACTOR_LANDSCAPE = 1.45f

    /**
     * 计算迷你小窗切换为「略微放大」形态时的目标缩放比。
     *
     * 约束：
     *  - 竖屏窗口宽/高均不超过可移动区域的 56%，且不超过普通小窗缩放比的 84%；
     *  - 横屏窗口宽不超过可移动区域的 88%、高不超过 52%，且不超过普通小窗缩放比的 92%；
     *  - 若受限于屏幕或普通小窗尺寸已无足够放大空间（< 1.05x），则返回原缩放比。
     */
    fun computeEnlargedMiniScale(
        baseScale: Float,
        rawWidth: Int,
        rawHeight: Int,
        movableWidth: Int,
        movableHeight: Int,
        restoreScale: Float,
        isLandscape: Boolean,
    ): Float {
        if (!baseScale.isFinite() || baseScale <= 0f || rawWidth <= 0 || rawHeight <= 0) {
            return baseScale
        }
        val factor = if (isLandscape) MINI_ENLARGE_FACTOR_LANDSCAPE else MINI_ENLARGE_FACTOR_PORTRAIT
        var target = baseScale * factor

        if (movableWidth > 0) {
            val maxW = movableWidth * if (isLandscape) 0.88f else 0.56f
            target = minOf(target, maxW / rawWidth.toFloat())
        }
        if (movableHeight > 0) {
            val maxH = movableHeight * if (isLandscape) 0.52f else 0.56f
            target = minOf(target, maxH / rawHeight.toFloat())
        }
        if (restoreScale.isFinite() && restoreScale > baseScale) {
            val restoreCap = restoreScale * if (isLandscape) 0.92f else 0.84f
            target = minOf(target, restoreCap)
        }
        return if (target >= baseScale * 1.05f) target else baseScale
    }

    data class Config(
        val ephemeralRecents: Boolean,
        val ephemeralScope: String,
        val miniLongPressMenu: Boolean,
        val miniDoubleTapSwap: Boolean,
        val swapToMini: Boolean,
    ) {
        /** 是否启用任一迷你小窗增强手势。 */
        val needsMiniHooks: Boolean
            get() = miniLongPressMenu || miniDoubleTapSwap
    }

    fun from(prefs: Prefs): Config {
        val f = Catalog.FREEFORM_ENHANCE
        val scope = prefs.rawString(Keys.pref(f, PREF_EPHEMERAL_SCOPE), DEFAULT_EPHEMERAL_SCOPE)
            .takeIf { it == SCOPE_TEMP_ONLY || it == SCOPE_ALL }
            ?: DEFAULT_EPHEMERAL_SCOPE
        return Config(
            ephemeralRecents = prefs.rawBoolean(Keys.pref(f, PREF_EPHEMERAL), DEFAULT_EPHEMERAL),
            ephemeralScope = scope,
            miniLongPressMenu = prefs.rawBoolean(
                Keys.pref(f, PREF_MINI_LONG_PRESS_MENU),
                DEFAULT_MINI_LONG_PRESS_MENU,
            ),
            miniDoubleTapSwap = prefs.rawBoolean(
                Keys.pref(f, PREF_MINI_DOUBLE_TAP_SWAP),
                DEFAULT_MINI_DOUBLE_TAP_SWAP,
            ),
            swapToMini = prefs.rawBoolean(
                Keys.pref(f, PREF_SWAP_TO_MINI),
                DEFAULT_SWAP_TO_MINI,
            ),
        )
    }

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
