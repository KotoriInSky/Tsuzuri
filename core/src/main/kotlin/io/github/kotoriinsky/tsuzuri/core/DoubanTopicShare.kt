package io.github.kotoriinsky.tsuzuri.core

/**
 * 豆瓣小组帖 / 个人帖：按贴吧的方式上岛（标题 = 帖子标题、第二行 = 小组名、封面 = 楼主首图）。
 *
 * 真机取证（2026-10-23，用户给的两条分享链）：分享地址是 `www.douban.com/doubanapp/dispatch?uri=<编码的路径>`，
 * 路径是 `/group/topic/<id>`（小组帖）或 `/topic/<id>`（个人帖，`subtype=personal`）。网页版帖子页对普通 UA
 * 返回 404，但 **`m.douban.com/rexxar/api/v2/group/topic/<id>`（带 m.douban.com Referer）两种帖子都返回 200**：
 * `title`、`author.name`、`group.name`（个人帖为空）、`photos[0].image.large.url`、`cover_url`。
 * 豆瓣 App 自己认 dispatch 链接（`com.douban.frodo/.activity.FacadeActivity`），打开方式不用改。
 */
object DoubanTopicShare {

    private val TOPIC_PATH = Regex("""^/(?:group/)?topic/(\d{5,12})/?$""")
    private val HOSTS = setOf("www.douban.com", "douban.com", "m.douban.com")

    /** 帖子 id；dispatch 壳会先解开 `uri` 参数。不是帖子返回 null。 */
    fun topicId(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase(java.util.Locale.ROOT) !in HOSTS) return null
        val path = if (uri.rawPath == "/doubanapp/dispatch") {
            val raw = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull {
                it.removePrefix("uri=").takeIf { v -> v != it }
            } ?: return null
            runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.substringBefore('?') ?: return null
        } else {
            uri.rawPath.orEmpty()
        }
        return TOPIC_PATH.matchEntire(path)?.groupValues?.get(1)
    }

    fun apiUrl(id: String) = "https://m.douban.com/rexxar/api/v2/group/topic/$id"

    /** 只认豆瓣自己的图床（https）。 */
    fun imageUrl(candidate: String?): String? {
        val c = candidate?.trim()?.takeIf { it.startsWith("https://") } ?: return null
        val host = runCatching { java.net.URI(c).host }.getOrNull() ?: return null
        return c.takeIf { host == "doubanio.com" || host.endsWith(".doubanio.com") }
    }
}
