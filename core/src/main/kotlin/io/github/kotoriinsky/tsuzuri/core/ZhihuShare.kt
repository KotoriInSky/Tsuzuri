package io.github.kotoriinsky.tsuzuri.core

/**
 * 知乎回答 / 问题 / 专栏文章的岛上字段。
 *
 * 真机取证（2026-10-23，用户给的 4 条分享链）：浏览器 / 爬虫 UA 一律 403（694 字节的 `zh-zse-ck` 校验页），
 * 接口 403（40362「请求存在异常」/ need_login）；**只有知乎 App 的 UA**（含 `ZhihuHybrid`）拿得到整页：
 * - 回答 `/question/<q>/answer/<a>`（199KB）：`og:title` =「<问题> - <答主> 的回答」，正文图在
 *   `RichContent` 之后的 `data-original`（样本 offset 122189）；
 * - 问题 `/question/<q>`（70KB）：`<title>` 是问题，`"answerCount":17760`；
 * - 专栏 `zhuanlan.zhihu.com/p/<id>` 302 到 `www.zhihu.com/appview/p/<id>`（121KB）：`<title>` 是「知乎 - 知乎」没用，
 *   标题在 `<h1>`，作者在 `"author":{…"name":"…"}`，正文图是 `<h1>` 之后的第一个 `<img src="https://pic…">`；
 * - 盐选 `oia.zhihu.com/km_paid_content/share` 只是 4944 字节的「打开 App」壳，没有内容，不处理。
 *
 * 岛的排布（用户 2026-10-23 问「问题、答主、回答怎么放」）：标题 = 问题 / 文章标题；
 * 第二行 = 「<答主>的回答」/ 作者 / 「N 个回答」；封面 = 正文首图（没有就是 App 图标）。回答正文不上岛。
 */
object ZhihuShare {

    enum class Kind { ANSWER, QUESTION, ARTICLE }

    data class Fields(val title: String, val subtitle: String?, val image: String?)

    private val ANSWER = Regex("""^/question/(\d{4,20})/answer/(\d{4,24})/?$""")
    private val QUESTION = Regex("""^/question/(\d{4,20})/?$""")
    private val ARTICLE = Regex("""^/(?:p|appview/p)/(\d{4,24})/?$""")

    fun kind(url: String): Kind? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
        val path = uri.rawPath.orEmpty()
        return when {
            host == "zhuanlan.zhihu.com" && ARTICLE.matches(path) -> Kind.ARTICLE
            host != "www.zhihu.com" && host != "zhihu.com" -> null
            ANSWER.matches(path) -> Kind.ANSWER
            QUESTION.matches(path) -> Kind.QUESTION
            path.startsWith("/appview/p/") && ARTICLE.matches(path) -> Kind.ARTICLE
            else -> null
        }
    }

    private val OG_TITLE = Regex("""<meta[^>]*property="og:title"[^>]*content="([^"]+)"""")
    private val TITLE_TAG = Regex("""<title[^>]*>([^<]+)</title>""")
    private val ANSWER_SUFFIX = Regex("""^(.+?)\s+-\s+(.+?)\s*的回答$""")
    private val ANSWER_COUNT = Regex(""""answerCount":(\d+)""")
    private val H1 = Regex("""<h1[^>]*>([^<]+)</h1>""")
    private val AUTHOR_NAME = Regex(""""author":\{[^{}]{0,1500}?"name":"([^"]{1,40})"""")
    private val RICH = Regex("""class="RichContent""")
    private val DATA_ORIGINAL = Regex("""data-original="(https://pic[^"]+)"""")
    private val IMG_SRC = Regex("""<img[^>]*?\ssrc="(https://pic[^"]+)"""")

    fun parse(kind: Kind, html: String): Fields? = when (kind) {
        Kind.ANSWER -> {
            val og = OG_TITLE.find(html)?.groupValues?.get(1)?.let(::unescape)
            val m = og?.let { ANSWER_SUFFIX.matchEntire(it.trim()) }
            val question = m?.groupValues?.get(1)
                ?: TITLE_TAG.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()
            question?.takeIf { it.isNotEmpty() }?.let {
                val start = RICH.find(html)?.range?.last ?: 0
                Fields(it, m?.groupValues?.get(2)?.let { name -> "${name}的回答" }, imageAt(DATA_ORIGINAL, html, start))
            }
        }
        Kind.QUESTION -> TITLE_TAG.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()
            ?.takeIf { it.isNotEmpty() && it != "知乎" }?.let {
                Fields(it, ANSWER_COUNT.find(html)?.groupValues?.get(1)?.let { n -> "$n 个回答" }, null)
            }
        Kind.ARTICLE -> H1.find(html)?.let { h1 ->
            val title = unescape(h1.groupValues[1]).trim().takeIf { it.isNotEmpty() } ?: return null
            val author = AUTHOR_NAME.find(html)?.groupValues?.get(1)?.let(::unescape)
            Fields(title, author, imageAt(IMG_SRC, html, h1.range.last))
        }
    }

    private fun imageAt(re: Regex, html: String, from: Int): String? =
        re.find(html, from)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?.takeIf { runCatching { java.net.URI(it).host }.getOrNull()?.endsWith(".zhimg.com") == true }

    private fun unescape(s: String): String = s.replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
