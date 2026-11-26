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

    /** 发往超级岛的统一信号载荷（纯函数算出，供 CopyDirectEngine 填进广播）。 */
    data class IslandSignal(
        val ruleId: String,
        val label: String,
        val target: String,
        val alts: List<String>,
        val core: String?,
        val platformName: String?,
        val platformPkg: String?,
        val accent: String?,
        val category: PasscodeIsland.ContentCategory,
    )

    private val DOUYIN_DEEP_LINK_SCHEMES = setOf("snssdk1128", "snssdk2329", "snssdk1112", "aweme")
    private val DOUYIN_DEEP_LINK_ID = Regex("""/(?:aweme/)?(?:detail|video|note|slides)/(\d{10,20})(?:/|$)""")
    private val DOUYIN_DEEP_LINK_QUERY_ID = Regex("""[?&](?:aweme_id|item_id|group_id)=(\d{10,20})""")
    private val JD_DEEP_LINK_SKU = Regex("""/(?:product/)?(\d{6,16})\.html|(?:skuId|wareId|productId)(?:%22|"|=|:)+(\d{6,16})""", RegexOption.IGNORE_CASE)
    private val TAOBAO_DEEP_LINK_ITEM = Regex("""[?&](?:id|itemId|item_id)=(\d{8,16})""", RegexOption.IGNORE_CASE)

    /**
     * 从原生云解析返回的 App 深链反推出可抓取标题与封面的公开 HTTPS 地址；
     * 实际点击跳转仍优先使用原生 Intent（`EXTRA_INTENT`），这里只为岛提供元信息与去重身份。
     */
    fun previewUrlFromDeepLink(deepLinkUri: String?): String? {
        if (deepLinkUri.isNullOrBlank()) return null
        val uri = runCatching { java.net.URI(deepLinkUri) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (scheme == "http" || scheme == "https") return deepLinkUri
        val decoded = PipixShare.percentDecode(deepLinkUri)
        if (scheme in DOUYIN_DEEP_LINK_SCHEMES) {
            val hostAndPath = "/${uri.host.orEmpty()}${uri.rawPath.orEmpty()}"
            val id = DOUYIN_DEEP_LINK_ID.find(hostAndPath)?.groupValues?.get(1)
                ?: DOUYIN_DEEP_LINK_QUERY_ID.find(decoded)?.groupValues?.get(1)
                ?: return null
            val kind = if (hostAndPath.contains("/note/", true) || hostAndPath.contains("/slides/", true)) "note" else "video"
            return "https://www.iesdouyin.com/share/$kind/$id/"
        }
        if (scheme.startsWith("openapp.jd") || scheme == "jd" || scheme == "openjd") {
            val m = JD_DEEP_LINK_SKU.find(decoded) ?: return null
            val sku = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (sku.isNotEmpty()) return "https://item.m.jd.com/product/$sku.html"
        }
        if (scheme == "taobao" || scheme == "tbopen") {
            val id = TAOBAO_DEEP_LINK_ITEM.find(decoded)?.groupValues?.get(1)
            if (!id.isNullOrEmpty()) return "https://item.taobao.com/item.htm?id=$id"
        }
        return null
    }

    /** 仅为「唤起 App 首页」的裸 scheme，无内容身份；原生拿到真实深链时优先替换它。 */
    private fun isBareAppScheme(target: String): Boolean {
        val idx = target.indexOf("://")
        if (idx <= 0) return false
        val scheme = target.substring(0, idx).lowercase(java.util.Locale.ROOT)
        if (scheme == "http" || scheme == "https" || scheme == "intent") return false
        val rest = target.substring(idx + 3).trim('/')
        return rest.isEmpty() || rest.equals("home", ignoreCase = true) || rest.equals("feed", ignoreCase = true)
    }

    private fun nativePlatformMeta(pkg: String?): PasscodeRules.Platform? {
        if (pkg.isNullOrBlank()) return null
        PasscodeRules.platformForPackage(pkg)?.let { return it }
        return when (pkg) {
            "com.ss.android.ugc.aweme.lite" -> PasscodeRules.Platform("抖音极速版", pkg, PasscodeRules.P_DOUYIN.accent, PasscodeIsland.ContentCategory.SHORT_VIDEO)
            "com.ss.android.ugc.live" -> PasscodeRules.Platform("抖音火山版", pkg, PasscodeRules.P_DOUYIN.accent, PasscodeIsland.ContentCategory.SHORT_VIDEO)
            "com.kuaishou.nebula" -> PasscodeRules.Platform("快手极速版", pkg, PasscodeRules.P_KUAISHOU.accent, PasscodeIsland.ContentCategory.SHORT_VIDEO)
            "com.eg.android.AlipayGphone" -> PasscodeRules.Platform("支付宝", pkg, "#1677FF", PasscodeIsland.ContentCategory.IMAGE_TEXT)
            "com.dragon.read" -> PasscodeRules.Platform("番茄免费小说", pkg, "#FF6A00", PasscodeIsland.ContentCategory.IMAGE_TEXT)
            "com.phoenix.read" -> PasscodeRules.Platform("红果免费短剧", pkg, "#FF3B30", PasscodeIsland.ContentCategory.SHORT_VIDEO)
            "cn.wps.moffice_eng" -> PasscodeRules.Platform("WPS", pkg, "#E8463A", PasscodeIsland.ContentCategory.IMAGE_TEXT)
            else -> null
        }
    }

    /**
     * 汇总模块匹配与原生云解析结果，产出超级岛所需的完整平台元数据与预览目标：
     * - 本地没命中但原生云解析命中（如抖音/番茄/支付宝裸口令）：按包名反查平台名/强调色/模板，并从原文抠标题；
     * - 原生返回内容深链时，反推可抓封面的 HTTPS 预览地址作为 `target`（点击仍走 `EXTRA_INTENT`）。
     */
    fun resolveIslandSignal(
        match: PasscodeRules.Match?,
        sourceText: String,
        targetPackage: String?,
        nativeDataUri: String?,
        nativeTag: Int,
        usedNative: Boolean,
        shareOnly: Boolean,
        fallbackId: String,
    ): IslandSignal {
        val platform = match?.platform ?: nativePlatformMeta(targetPackage)
        val previewUrl = if (usedNative) previewUrlFromDeepLink(nativeDataUri) else null
        val target = when {
            previewUrl != null -> previewUrl
            usedNative && !nativeDataUri.isNullOrBlank() && (match == null || isBareAppScheme(match.target)) -> nativeDataUri
            match != null -> match.target
            !nativeDataUri.isNullOrBlank() -> nativeDataUri
            else -> "copydirect:$fallbackId"
        }
        val alts = buildList {
            match?.target?.takeIf { it != target }?.let(::add)
            match?.alts?.filter { it != target }?.let(::addAll)
            nativeDataUri?.takeIf { it.isNotBlank() && it != target }?.let(::add)
        }.distinct()
        val core = match?.core ?: PasscodeRules.extractTitle(sourceText)
        val ruleId = match?.ruleId ?: "native:$nativeTag"
        val label = when {
            shareOnly -> "分享内容"
            match != null -> match.label
            platform != null -> "${platform.name}口令"
            else -> "复制直达"
        }
        val category = when {
            previewUrl != null && platform != null -> PasscodeRules.categoryFor(ruleId, platform, previewUrl)
            match != null -> match.category
            platform != null -> PasscodeRules.categoryFor(ruleId, platform, target)
            else -> PasscodeRules.categoryFor(ruleId, null, target)
        }
        return IslandSignal(
            ruleId = ruleId,
            label = label,
            target = target,
            alts = alts,
            core = core,
            platformName = platform?.name,
            platformPkg = targetPackage ?: platform?.pkg,
            accent = platform?.accent,
            category = category,
        )
    }
}
