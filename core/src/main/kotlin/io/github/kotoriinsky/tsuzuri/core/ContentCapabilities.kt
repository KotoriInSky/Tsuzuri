package io.github.kotoriinsky.tsuzuri.core

/**
 * 「一个总开关 + 若干独立效果」的特性：子项怎么取值。
 *
 * ## 2026-10-25 按「用户在做什么」重组（用户拍板）
 *
 * 原来按实现拆成 7 个特性（传送门 6 个 + 小爱识屏样式），首页一半是只有一个开关的空壳页，
 * 标题也看不出改的是传送门还是超级小爱。现在收成三类，子项标题写明作用于哪个入口：
 *
 *  - [Catalog.SCREEN_TEXT] 从屏幕获取文字（传送门兼容 / 提取文字 + 超级小爱结果页的文字操作）
 *  - [Catalog.SCREEN_IMAGE] 从屏幕获取图片（传送门识图链路 + 图片卡片操作）
 *  - [Catalog.RECOGNITION_LOOK] 超级小爱 / 传送门的样式
 *
 * 旧键只在 [migration] 里读一次，之后不再使用。
 *
 * ## 三条规则（修「开了没反应」）
 *
 *  1. **迁移**：只把旧版里**有记录**的状态搬到新键；旧版什么都没写的用户什么都不写。
 *  2. **第一次打开**（[onEnable]）：没写过的子项写入**推荐组合**（[recommended]），不是全开。
 *  3. **用户明确设过的子项永远不动。**
 */
object ContentCapabilities {
    val names = mapOf(
        Catalog.SCREEN_TEXT to listOf("app_compat", "ocr", "translate", "copy_text", "share_text", "external_trigger"),
        Catalog.SCREEN_IMAGE to listOf(
            "expand_region", "screenshot_crop", "candidate_check", "press_snapshot", "source_bitmap",
            "edit", "share_image", "original_media", "object_to_xiaoai",
        ),
        Catalog.RECOGNITION_LOOK to listOf("full_bleed", "horizontal", "compact", "capsule"),
    )

    /** 第一次打开功能时默认开启的子项。没列出的子项写成关。 */
    val recommended: Map<String, Set<String>> = mapOf(
        // 翻译依赖系统翻译、外部调用给自动化用、提取文字用户说先不要 —— 不替用户打开
        Catalog.SCREEN_TEXT to setOf("app_compat", "copy_text", "share_text"),
        // 原图要另装采集组件、按下截图每次触摸都截屏、识物改道是换一个识别服务 —— 不默认
        Catalog.SCREEN_IMAGE to setOf("expand_region", "screenshot_crop", "candidate_check", "edit", "share_image", "original_media"),
        Catalog.RECOGNITION_LOOK to setOf("full_bleed", "horizontal", "capsule"),
    )

    /** 子项取值：写过就用写过的，没写过就跟总开关。 */
    fun selected(prefs: Prefs, feature: String, name: String): Boolean =
        prefs.rawBooleanOrNull(Keys.pref(feature, name))
            ?: prefs.rawBoolean(Keys.enabled(feature), false)

    /**
     * 子项**真正生效**：总开关开着 **且** 子项选中。
     *
     * hook 侧一律用它而不是 [selected]：一个实现可能同时服务两个特性（例如图片卡片的
     * 「提取文字」属于文字、「编辑」属于图片），装上它不代表另一个特性的总开关也开着。
     */
    fun on(prefs: Prefs, feature: String, name: String): Boolean =
        prefs.isEnabled(feature) && selected(prefs, feature, name)

    // ── 旧键（2026-10-25 之前的 7 个特性），只给迁移用 ───────────────────────
    private const val OLD_ORIGINAL_MEDIA = "portal_original_media"
    private const val OLD_SOURCE_BITMAP = "portal_source_bitmap"
    private const val OLD_CAPSULE = "portal_capsule_style"
    private const val OLD_APP_COMPAT = "portal_app_compat"
    private const val OLD_ACTIONS = "portal_image_actions"
    private const val OLD_REGION = "portal_image_region"
    private const val OLD_STYLE = "screen_recognition_style"

    private val OLD_KEYS = listOf(OLD_ORIGINAL_MEDIA, OLD_SOURCE_BITMAP, OLD_CAPSULE, OLD_APP_COMPAT, OLD_ACTIONS, OLD_REGION, OLD_STYLE)

    /**
     * 旧 7 个特性 → 新 3 个特性。只在新特性的总开关**从没写过**时执行一次。
     *
     * 旧子项的取值规则沿用旧版：旧总开关开着时，没写过的子项按旧默认（`def`）；旧总开关关着 = 关。
     * 新总开关 = 搬过来的子项里有任何一个是开的。旧版什么都没写的用户 → 什么都不写。
     */
    fun migration(prefs: Prefs): Map<String, Boolean> {
        if (OLD_KEYS.none { prefs.rawBooleanOrNull(Keys.enabled(it)) != null }) return emptyMap()
        fun was(k: String) = prefs.rawBooleanOrNull(Keys.enabled(k)) == true
        fun old(k: String, n: String, def: Boolean) = was(k) && (prefs.rawBooleanOrNull(Keys.pref(k, n)) ?: def)

        val plan = mapOf(
            Catalog.SCREEN_TEXT to mapOf(
                "app_compat" to was(OLD_APP_COMPAT),
                "ocr" to old(OLD_ACTIONS, "ocr", false),
                "translate" to old(OLD_STYLE, "translate", true),
                "copy_text" to old(OLD_STYLE, "copy_text", true),
                "share_text" to old(OLD_STYLE, "share_text", true),
                "external_trigger" to old(OLD_STYLE, "external_trigger", true),
            ),
            Catalog.SCREEN_IMAGE to mapOf(
                "expand_region" to old(OLD_REGION, "expand_region", true),
                "screenshot_crop" to old(OLD_REGION, "screenshot_crop", true),
                "candidate_check" to old(OLD_REGION, "candidate_check", true),
                "press_snapshot" to old(OLD_REGION, "press_snapshot", false),
                "source_bitmap" to was(OLD_SOURCE_BITMAP),
                "edit" to old(OLD_ACTIONS, "edit", true),
                "share_image" to old(OLD_ACTIONS, "share_image", true),
                "original_media" to was(OLD_ORIGINAL_MEDIA),
                "object_to_xiaoai" to old(OLD_ACTIONS, "object_to_xiaoai", false),
            ),
            Catalog.RECOGNITION_LOOK to mapOf(
                "full_bleed" to old(OLD_STYLE, "full_bleed", true),
                "horizontal" to old(OLD_STYLE, "horizontal", true),
                "compact" to old(OLD_STYLE, "compact", true),
                "capsule" to was(OLD_CAPSULE),
            ),
        )
        return buildMap {
            for ((feature, children) in plan) {
                if (prefs.rawBooleanOrNull(Keys.enabled(feature)) != null) continue
                for ((name, v) in children) {
                    val key = Keys.pref(feature, name)
                    if (prefs.rawBooleanOrNull(key) == null) put(key, v)
                }
                put(Keys.enabled(feature), children.values.any { it })
            }
        }
    }

    /**
     * 打开 [feature] 的总开关时要一起写的子项：只填没写过的，按 [recommended]。
     * 不是这一类特性 → 空。
     */
    fun onEnable(prefs: Prefs, feature: String): Map<String, Boolean> {
        val children = names[feature] ?: return emptyMap()
        val picks = recommended[feature].orEmpty()
        return buildMap {
            for (name in children) {
                val key = Keys.pref(feature, name)
                if (prefs.rawBooleanOrNull(key) == null) put(key, name in picks)
            }
        }
    }

    /** 总开关开着、子项却一个都没选 —— 界面要说出来（「未选择任何效果」），而不是静默地什么都不做。 */
    fun enabledButEmpty(prefs: Prefs, feature: String): Boolean {
        val children = names[feature] ?: return false
        return prefs.rawBoolean(Keys.enabled(feature), false) && children.none { selected(prefs, feature, it) }
    }

    fun legacyDefault(prefs: Prefs, key: String, default: Boolean): Boolean {
        for ((feature, children) in names) {
            if (children.any { Keys.pref(feature, it) == key }) return selected(prefs, feature, key.substringAfterLast('.'))
        }
        return default
    }
}
