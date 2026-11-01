package io.github.kotoriinsky.tsuzuri.core

/**
 * B 站视频稿件：标题从 `x/web-interface/view` 接口取，不从网页取。
 *
 * 真机取证（2026-10-23，多 P 样本 `BV1Wv411h7kN?p=1`，155 个分 P）：网页 `<title>` 与 `og:title`
 * 都是**当前分 P 的标题**（「2022-机器学习相关规定_哔哩哔哩_bilibili」），稿件标题只在接口的
 * `data.title`（「(强推)李宏毅2021/2022春机器学习课程」）。用户 2026-10-23 报「应该提取稿件标题不是 P1 的标题」。
 * 接口响应随分 P 数增长（该样本 41KB），抓取上限要放宽。
 */
object BiliVideoShare {

    private val BVID = Regex("""^/video/(BV[0-9A-Za-z]{10})(?:[/?#]|$)""")
    private val AID = Regex("""^/video/av(\d{1,12})(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
    private val HOSTS = setOf("www.bilibili.com", "bilibili.com", "m.bilibili.com")

    /** 视频页地址 → 接口地址；不是视频页返回 null。 */
    fun apiUrl(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase(java.util.Locale.ROOT) !in HOSTS) return null
        val path = uri.rawPath.orEmpty()
        BVID.find(path)?.let { return "https://api.bilibili.com/x/web-interface/view?bvid=${it.groupValues[1]}" }
        AID.find(path)?.let { return "https://api.bilibili.com/x/web-interface/view?aid=${it.groupValues[1]}" }
        return null
    }

    /** 接口给的封面是 `http://`（实测 `http://i2.hdslb.com/…`），抓取前升到 https。 */
    fun coverUrl(pic: String?): String? {
        val p = pic?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val https = if (p.startsWith("http://")) "https://" + p.removePrefix("http://") else p
        val host = runCatching { java.net.URI(https).host }.getOrNull() ?: return null
        return https.takeIf { it.startsWith("https://") && host.endsWith(".hdslb.com") }
    }
}
