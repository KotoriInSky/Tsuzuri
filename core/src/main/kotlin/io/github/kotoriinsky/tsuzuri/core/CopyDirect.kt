package io.github.kotoriinsky.tsuzuri.core

/** 复制直达共用契约。既有配置键保留，新增原生增强独立默认关闭。 */
object CopyDirect {
    const val ENGINE_PACKAGE = "com.xiaomi.aicr"
    /** 实测：设置在主进程，复制感知、决策和原生队列在 cognitionService。 */
    fun supportsEngineProcess(processName: String) =
        processName == ENGINE_PACKAGE || processName == "$ENGINE_PACKAGE:cognitionService"
    const val ENGINE_ENABLED = "native_engine_enabled"
    const val MODE = "interaction_mode"
    const val DISABLED_PLATFORMS = "disabled_platforms"
    const val SAME_APP = "same_app_share"
    const val ICON_PACK = "icon_pack"
    /** 浏览器许可绑定角色，避免更换默认应用后原有关闭选择丢失。 */
    const val DEFAULT_BROWSER = "default_browser"
    const val EXTRA_INTENT = "resolved_intent"
    const val EXTRA_ENGINE_RESULT = "engine_result"

    enum class Mode(val wireName: String) { ISLAND("island"), NATIVE("native") }
    enum class Strength { APP, LINK, CONTENT }
    data class Action(val intentUri: String, val packageName: String?, val strength: Strength)
    data class Platform(val id: String, val name: String, val packageName: String?)
    data class Config(
        val enabled: Boolean = false,
        val mode: Mode = Mode.ISLAND,
        val disabled: Set<String> = emptySet(),
        val sameAppShare: Boolean = false,
        val iconPack: String? = null,
    ) {
        fun allows(platform: PasscodeRules.Platform?) = platform == null || platformId(platform) !in disabled
    }

    fun key(name: String) = Keys.pref(Catalog.PASSCODE_ISLAND, name)
    fun platformId(platform: PasscodeRules.Platform) = platform.pkg ?: "content:${platform.name}"
    fun config(prefs: Prefs) = Config(
        enabled = prefs.isEnabled(Catalog.PASSCODE_ISLAND) && prefs.rawBoolean(key(ENGINE_ENABLED), false),
        mode = Mode.entries.firstOrNull { it.wireName == prefs.rawString(key(MODE), "island") } ?: Mode.ISLAND,
        // 应用许可统一交由引擎原生开关；不再读取试作版的第二份黑名单。
        disabled = emptySet(),
        sameAppShare = prefs.rawBoolean(key(SAME_APP), false),
        iconPack = prefs.rawStringSet(key(ICON_PACK)).sorted().firstOrNull(),
    )

    /** 只有目标登记事实；不把原生平台注册误写成所有内容直达已验证。 */
    private val nativePlatforms = listOf(
        Platform("com.eg.android.AlipayGphone", "支付宝", "com.eg.android.AlipayGphone"),
        Platform("com.kuaishou.nebula", "快手极速版", "com.kuaishou.nebula"),
        Platform("com.dragon.read", "番茄免费小说", "com.dragon.read"),
        Platform("com.phoenix.read", "红果免费短剧", "com.phoenix.read"),
        Platform("com.ss.android.ugc.aweme.lite", "抖音极速版", "com.ss.android.ugc.aweme.lite"),
        Platform("com.ss.android.ugc.live", "抖音火山版", "com.ss.android.ugc.live"),
        Platform("cn.wps.moffice_eng", "WPS", "cn.wps.moffice_eng"),
        Platform("com.android.browser", "小米浏览器", "com.android.browser"),
    )

    fun platforms(customRules: List<PasscodeCustomRules.Rule> = emptyList()): List<Platform> =
        (PasscodeRules.supportedPlatforms().map { Platform(platformId(it), it.name, it.pkg) } +
            nativePlatforms + customRules.filter { it.enabled }.map {
                Platform(it.platformPkg ?: "custom:${it.id}", it.platformName ?: it.label, it.platformPkg)
            }).distinctBy { it.id }

    private val cloudPackages = setOf("com.baidu.netdisk", "com.xunlei.downloadprovider", "com.quark.browser", "com.alicloud.databox")

    /** 按原生许可类型补充：裸口令、网盘分享、网页链接各自登记。 */
    fun platformsForAbility(ability: String, customRules: List<PasscodeCustomRules.Rule> = emptyList()): List<Platform> {
        val known = platforms(customRules)
        val commandPackages = PasscodeRules.commandPlatforms().mapNotNull { it.pkg }.toSet() +
            nativePlatforms.filter { it.packageName != "com.android.browser" }.mapNotNull { it.packageName } +
            customRules.filter { it.enabled }.mapNotNull { it.platformPkg }
        return known.filter {
            when (ability) {
                "command" -> it.packageName in commandPackages && it.packageName !in cloudPackages
                "jump_link" -> it.packageName in cloudPackages
                // 网页目标交给系统 IntentResolver；运行时仅展示当前默认浏览器。
                "jump_url" -> false
                else -> false
            }
        }
    }

    fun abilityFor(match: PasscodeRules.Match?): String = when {
        match?.category == PasscodeIsland.ContentCategory.EXPRESS -> "tracking_number"
        match?.platform?.pkg in cloudPackages -> "jump_link"
        match?.ruleId == "url" || match?.ruleId?.startsWith("url_") == true -> "jump_url"
        else -> "command"
    }

    fun fullName(packageName: String, fallback: String): String = when (packageName) {
        "tv.danmaku.bili" -> "哔哩哔哩"
        "com.netease.cloudmusic" -> "网易云音乐"
        "com.baidu.netdisk" -> "百度网盘"
        "com.baidu.tieba" -> "百度贴吧"
        "com.tencent.qqmusic" -> "QQ音乐"
        "cn.wps.moffice_eng" -> "WPS Office"
        else -> fallback
    }

    /** 先穷尽应用内容页再回退网页；自定义目标保持用户给定的顺序。 */
    fun orderedTargets(match: PasscodeRules.Match): List<String> {
        val targets = (listOf(match.target) + match.alts + PasscodeRules.articleAppTargets(match.target, match.platform?.pkg)).distinct()
        if (match.ruleId.startsWith("custom:")) return targets
        return targets.sortedBy { if (it.startsWith("https://", true) || it.startsWith("http://", true)) 1 else 0 }
    }

    /** 用户目标优先；原生候选仅在同平台且能补充内容动作时参与。 */
    fun choose(match: PasscodeRules.Match?, module: Action?, native: Action?, disabled: Set<String> = emptySet()): Action? {
        val platformKey = match?.platform?.let(::platformId)
        if (platformKey in disabled || match?.ruleId in disabled) return null
        val local = module?.takeUnless { it.packageName in disabled }
        if (match?.ruleId?.startsWith("custom:") == true) return local
        val expectedPackage = match?.platform?.pkg
        val remote = native?.takeUnless {
            it.packageName in disabled ||
                (expectedPackage != null && it.packageName != expectedPackage)
        }
        return when {
            local == null -> remote
            remote == null -> local
            remote.strength > local.strength -> remote
            else -> local
        }
    }
}
