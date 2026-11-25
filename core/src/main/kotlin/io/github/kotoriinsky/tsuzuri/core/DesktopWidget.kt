package io.github.kotoriinsky.tsuzuri.core

/**
 * 桌面（`com.miui.home`，Dart AOT + Rust 无 ART 进程）原生补丁的跨进程契约。
 *
 * ## 为什么需要单独的跨进程传递层
 *
 * `com.miui.home` 没有 ART 虚拟机，LSPosed 102 只通过 `META-INF/xposed/native_init.list`
 * 加载 `libfpnative.so` 并回调纯 C 的 `native_init`，进程内拿不到 `XposedInterface` 或
 * `RemotePreferences`。因此由已经在 `system_server` 里的 Java 模块监听 `RemotePreferences`，
 * 将三个桌面特性的开关状态编码成 3-bit 掩码，发布到系统属性（无需 root、开机在桌面启动前就绪、
 * 改开关秒级同步；不写 `persist.*`，唯一事实来源始终是 LSPosed 的 `RemotePreferences`）。
 *
 * 与 `app/src/main/cpp/home_widget_patches.h` 里的常量必须逐字一致。
 */
object DesktopWidget {

    /** 位 0：桌面第三方小部件风格统一（顶边距、宽高网格、分档圆角 24dp/20dp）。 */
    const val BIT_STYLE: Int = 1 shl 0

    /** 位 1：桌面小部件任意拖入负一屏（解除第三方与尺寸限制，支持单部件与堆叠）。 */
    const val BIT_DRAG_PA: Int = 1 shl 1

    /** 位 2：桌面任意小部件堆叠（解除 dragItem / targetItem 的 MIUI 小部件限制）。 */
    const val BIT_STACK: Int = 1 shl 2

    /** 已定义的有效位掩码。 */
    const val MASK_ALL: Int = BIT_STYLE or BIT_DRAG_PA or BIT_STACK

    /**
     * 主属性（`debug_prop`）与备选属性（`system_prop`）。
     * 非 `persist.`：每次开机由 `system_server` 按 `RemotePreferences` 重写，避免卸载后残留。
     */
    const val PROP_FLAGS_DEBUG: String = "debug.tsuzuri.home.flags"
    const val PROP_FLAGS_SYS: String = "sys.tsuzuri.home.flags"

    /** 可选兜底配置文件（仅调试/特殊无属性可读环境用，桌面进程只读）。 */
    const val FALLBACK_CFG_PATH: String = "/data/local/tmp/tsuzuri_home.cfg"
    const val CFG_MAGIC: String = "tsuzuri_home"

    /** 所有桌面原生特性的 key 集合。 */
    val FEATURE_KEYS: Set<String> = setOf(
        Catalog.DESKTOP_WIDGET_STYLE,
        Catalog.DESKTOP_WIDGET_DRAG_PA,
        Catalog.DESKTOP_WIDGET_STACK,
    )

    /** 对应 `RemotePreferences` 中的开关 key 集合。 */
    val ENABLED_PREF_KEYS: Set<String> = FEATURE_KEYS.map { Keys.enabled(it) }.toSet()

    /** 从当前配置计算 3-bit 掩码；全部默认关 → 0。 */
    fun computeFlags(prefs: Prefs): Int {
        var mask = 0
        if (prefs.isEnabled(Catalog.DESKTOP_WIDGET_STYLE)) mask = mask or BIT_STYLE
        if (prefs.isEnabled(Catalog.DESKTOP_WIDGET_DRAG_PA)) mask = mask or BIT_DRAG_PA
        if (prefs.isEnabled(Catalog.DESKTOP_WIDGET_STACK)) mask = mask or BIT_STACK
        return mask
    }

    /** 编码为系统属性值（纯十进制非负整数，范围 `0..7`）。 */
    fun formatFlags(flags: Int): String = (flags and MASK_ALL).toString()

    /**
     * 严格解析系统属性值。
     *
     * 任何空值、非数字、负数、越界值都回退 `0`（全部关闭 = 与未安装一致，fail-closed）。
     */
    fun parseFlags(raw: String?): Int {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.any { it !in '0'..'9' }) return 0
        val v = s.toIntOrNull() ?: return 0
        return if (v in 0..MASK_ALL) v else 0
    }

    /** 兜底配置文件格式：`tsuzuri_home v=1 flags=<0..7>\n`。 */
    fun formatConfigFile(flags: Int): String =
        "$CFG_MAGIC v=1 flags=${flags and MASK_ALL}\n"

    /** 严格解析兜底配置文件；格式不合法返回 `null`。 */
    fun parseConfigFile(text: String?): Int? {
        val parts = text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        if (parts.isEmpty() || parts[0] != CFG_MAGIC) return null
        val kv = HashMap<String, String>(4)
        for (p in parts.drop(1)) {
            val i = p.indexOf('=')
            if (i <= 0) return null
            kv[p.substring(0, i)] = p.substring(i + 1)
        }
        if (kv["v"] != "1") return null
        val rawFlags = kv["flags"] ?: return null
        if (rawFlags.isEmpty() || rawFlags.any { it !in '0'..'9' }) return null
        val v = rawFlags.toIntOrNull() ?: return null
        return if (v in 0..MASK_ALL) v else null
    }

    /** 判断一次 SharedPreferences 键变更是否涉及桌面原生特性。 */
    fun isDesktopWidgetPrefKey(prefKey: String?): Boolean =
        prefKey != null && prefKey in ENABLED_PREF_KEYS
}
