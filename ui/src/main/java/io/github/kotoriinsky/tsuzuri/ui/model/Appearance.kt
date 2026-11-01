package io.github.kotoriinsky.tsuzuri.ui.model

/**
 * 设置界面自己的外观。**与系统界面无关** —— 改这里不会影响桌面或负一屏，
 * 界面上必须写清楚这一点，否则用户会以为「主题色没生效」是 bug。
 */
enum class UiStyle {
    /** 澎湃风格，用 MiuiX 渲染。 */
    MIUIX,

    /** Material 3 风格。 */
    M3;

    val labelI18n: String get() = "appearance.style." + name.lowercase()
    val descI18n: String get() = "appearance.style." + name.lowercase() + ".desc"
}

enum class DarkMode {
    SYSTEM, LIGHT, DARK;

    val labelI18n: String get() = "appearance.dark." + name.lowercase()
}

enum class AccentMode {
    /** 系统动态取色（Monet）。 */
    SYSTEM,

    /** 固定用澎湃蓝。 */
    MIUI_BLUE,

    CUSTOM;

    val labelI18n: String get() = "appearance.accent." + name.lowercase()
}

/** 界面密度。通过缩放 [androidx.compose.ui.unit.LocalDensity] 实现，两套风格通用。 */
enum class Density(val scale: Float) {
    COMPACT(0.88f),
    STANDARD(1.0f),
    RELAXED(1.12f);

    val labelI18n: String get() = "settings.density." + name.lowercase()
}

/** 字号档位。同样走 LocalDensity 的 fontScale，因此对两套风格都成立。 */
enum class FontScale(val scale: Float) {
    SMALL(0.9f),
    NORMAL(1.0f),
    LARGE(1.15f);

    val labelI18n: String get() = "settings.font.scale." + name.lowercase()
}

/**
 * 外观配置的完整快照。
 *
 * 注意它**不进 RemotePreferences**：这是设置界面自己的显示偏好，
 * hook 侧完全不需要知道。混进同一份配置只会让「改外观触发了进程重载」这种怪事发生。
 * 它存在本 App 的普通 SharedPreferences 里。
 */
data class Appearance(
    val style: UiStyle = UiStyle.MIUIX,
    val dark: DarkMode = DarkMode.SYSTEM,
    val accent: AccentMode = AccentMode.MIUI_BLUE,
    val customAccentArgb: Int = MIUI_BLUE,
    val density: Density = Density.STANDARD,
    val fontScale: FontScale = FontScale.NORMAL,
    /**
     * 深色模式下把背景与卡片换成纯黑（AMOLED）。
     *
     * 默认关：纯黑不是「更深」，而是另一种观感 —— 卡片和背景的层次会消失，
     * 只有 OLED 屏才换来省电与对比度上的好处。默认改掉用户的观感是不礼貌的，
     * 但默认不给这个能力又对不起 OLED 屏。
     */
    val amoled: Boolean = false,
    /**
     * 顶栏 / 底栏的背景毛玻璃（仅澎湃风格可表达）。
     *
     * 默认**开**：它是澎湃风格最有辨识度的那一层质感，不打开就没有「像 HyperOS」的理由。
     * 但它对 GPU 有真实开销（实时模糊需要 RenderEffect 合成），所以在低端设备上
     * 关掉它应当能换来流畅度 —— 这就是它是一个开关而不是写死的原因。
     *
     * ⚠️ Material 3 侧不接受这个开关：M3 的 NavigationBar / TopAppBar 没有模糊语义，
     * 硬做就是伪造一种 M3 不存在的东西。所以 M3 下它**静默无效**，
     * 界面上的开关文案必须说清这一点（`appearance.blur.desc`）。
     */
    val barBlur: Boolean = true,
    /**
     * 悬浮底栏：底栏离开屏幕边缘，变成圆角胶囊。只在澎湃风格下生效，M3 保持贴底。
     */
    val floatingBar: Boolean = true,
    /**
     * 液态玻璃底栏：悬浮底栏换成 Kyant 的玻璃滑块（折射 + 色散）。
     * 依附于悬浮底栏：[floatingBar] 关时无效。默认关，改完立刻生效。
     */
    val glassBars: Boolean = false,
) {
    companion object {
        const val FILE = "appearance"
        const val KEY_STYLE = "style"
        const val KEY_DARK = "dark"
        const val KEY_ACCENT = "accent"
        const val KEY_CUSTOM = "custom_accent"
        const val KEY_DENSITY = "density"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_AMOLED = "amoled"
        const val KEY_GLASS_BARS = "glass_bars"
        const val KEY_FLOATING_BAR = "floating_bar"
        const val KEY_BAR_BLUR = "bar_blur"

        /** 澎湃蓝，MiuiX 默认色。 */
        const val MIUI_BLUE = 0xFF3482FF.toInt()
    }
}
