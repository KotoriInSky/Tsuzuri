package io.github.kotoriinsky.tsuzuri.core

/**
 * 口令岛**展开态**的官方模板骨架。
 *
 * 七类内容统一落到小米《展开态设计规范》模板 18：
 * A 区新图文组件（`iconTextInfo`：方图 + 主文本 + 次要文本）、
 * E 区识别图形组件 1（`picInfo` type 1：右上目标 App 图标）、
 * D 区按钮组件 4（`textButton`：「分享」「打开」）。
 * 目标机插件按区挑组件（`TemplateFactoryV3.chooseModule`），这里只决定**每个区填什么**；
 * JSON 序列化在 `:hook`（`:core` 没有 org.json）。
 *
 * 字段规则：
 *  - 方图：有内容封面用封面（长视频也是封面**中心方裁**，用户 2026-10-22 选的方案 A），
 *    没有就用实际负责打开的 App 图标；
 *  - E 区：只在方图是封面时给 —— 方图本身就是 App 图标时再放一个就是重复；
 *  - 次要文本：官方必传。按「分类次行 → 平台名 → 域名 → 分类名」取第一个与主文本不同的值，
 *    保证永远非空、也不把主文本抄一遍。
 */
object PasscodeIslandTemplate {

    enum class Picture { COVER, TARGET_ICON }

    data class Expanded(
        val picture: Picture,
        val title: String,
        val content: String,
        /** 是否给 E 区（右上目标 App 图标）。 */
        val showAppMark: Boolean,
    )

    /**
     * @param secondary 分类自己的次行（作者、歌手、单号、域名…），可空
     * @param platformName 规则声明的平台名（如「哔哩哔哩」），可空
     * @param domain 打开目标的域名，可空
     */
    fun expanded(
        category: PasscodeIsland.ContentCategory,
        title: String,
        secondary: String?,
        hasCover: Boolean,
        platformName: String? = null,
        domain: String? = null,
    ): Expanded {
        val picture = if (hasCover) Picture.COVER else Picture.TARGET_ICON
        // 标题里已经出现过的字不再当次要文本（「打开小红书 / 小红书」这种重复，2026-10-22 真机）
        val t = title.trim().lowercase()
        val content = sequenceOf(secondary, platformName, domain, categoryLabel(category))
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { it.lowercase() !in t }
            ?: categoryLabel(category)
        return Expanded(
            picture = picture,
            title = title,
            content = content,
            showAppMark = picture == Picture.COVER,
        )
    }

    /**
     * 抓不到内容标题时的大字行：「打开 XXX」（用户 2026-10-22 定）。拉丁字母开头的名字前加空格
     * （「打开 YouTube」），中文直接连（「打开小红书」）。内容号不再当标题 —— 一串号放在大字行上
     * 没人认得（真机：YouTube 显示 `dQw4w9WgXcQ`、抖音显示 19 位作品号）。
     */
    fun fallbackTitle(appName: String): String {
        val name = appName.trim()
        return if (name.firstOrNull()?.let { it.code < 0x80 } == true) "打开 $name" else "打开$name"
    }

    /**
     * 网页抓不到标题时的次行：链接路径（`/docs/api`），解码百分号、去掉末尾斜杠，超长截断。
     * 大字行此时已经是域名，再显示一遍域名没有信息量；路径至少能分清是哪一页。
     * 没有路径（根页）或解析失败返回 null。
     */
    fun webPathLine(url: String, max: Int = PATH_LINE_MAX): String? {
        val path = runCatching { java.net.URI(url).path }.getOrNull()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (path.length <= max) path else path.take(max - 1) + "…"
    }

    private const val PATH_LINE_MAX = 40

    /**
     * 从链接本身读出的「所在社区」，给次行用（用户 2026-10-22：副标题要实际一些，
     * Reddit 用 r/版块、贴吧用吧名）。不用联网，抓页失败时也有。认不出返回 null。
     *  - Reddit：`/r/<sub>/…` → `r/<sub>`；`/u/<name>`、`/user/<name>` → `u/<name>`
     *  - 贴吧：`/f?kw=<吧名>` → `<吧名>吧`（帖子链接 `/p/<号>` 里没有吧名，靠抓页补）
     */
    fun communityLine(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (host == "reddit.com" || host.endsWith(".reddit.com")) {
            val name = parts.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9_]{2,32}")) } ?: return null
            return when (parts.firstOrNull()?.lowercase()) {
                "r" -> "r/$name"
                "u", "user" -> "u/$name"
                else -> null
            }
        }
        if (host == "tieba.baidu.com" && parts.firstOrNull() == "f") {
            val kw = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull {
                it.removePrefix("kw=").takeIf { v -> v != it }
            }?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (kw.endsWith("吧")) kw else "${kw}吧"
        }
        return null
    }

    /** 分类的通用名：摘要态 B 区兜底与展开态次要文本兜底共用。 */
    fun categoryLabel(category: PasscodeIsland.ContentCategory): String = when (category) {
        PasscodeIsland.ContentCategory.SHORT_VIDEO -> "短视频"
        PasscodeIsland.ContentCategory.LONG_VIDEO -> "长视频"
        PasscodeIsland.ContentCategory.IMAGE_TEXT -> "图文"
        PasscodeIsland.ContentCategory.MUSIC -> "音乐"
        PasscodeIsland.ContentCategory.ECOMMERCE -> "商品"
        PasscodeIsland.ContentCategory.EXPRESS -> "查快递"
        PasscodeIsland.ContentCategory.WEB -> "网页"
    }
}
