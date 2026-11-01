package io.github.kotoriinsky.tsuzuri.core

/**
 * 微博**头条文章**与**视频（fid）**的地址识别与字段提取。
 *
 * 端点与字段来自本地参考实现（astrbot_plugin_parser，MIT，`core/parsers/weibo.py:76-214`）：
 *
 * - 文章：`POST https://card.weibo.com/article/m/aj/detail`，表单 `_rid` / `id` / `_t`
 *   → `data.title`、`data.userinfo.screen_name`、`data.content`（HTML，取首个 `<img src>` 当封面）
 * - 视频：`POST https://h5.video.weibo.com/api/component?page=/show/<fid>`，
 *   体 `data={"Component_Play_Playinfo":{"oid":"<fid>"}}`
 *   → `data.Component_Play_Playinfo.title` / `.cover_image`（**协议相对**，要补 `https:`）
 *     / `.reward.user.name`
 *
 * 这两种形态的网页本身都是壳（实测：`video.weibo.com/show?fid=` 888 字节、
 * `card.weibo.com/article/…` 3192 字节、`weibo.com/<uid>/<bid>` 直接是 Sina Visitor System），
 * 所以必须走这两个只读接口。**未真机验证**（用户 2026-10-06 指示"先补齐、不卡验证"）。
 */
object WeiboApi {

    data class Fields(val title: String?, val author: String?, val cover: String?)

    private val TITLE = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    private val SCREEN_NAME = Regex(""""screen_name"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    /** 文章正文是**转义在 JSON 字符串里**的 HTML，所以引号可能是 `\"`。 */
    private val CONTENT_IMG = Regex("""<img[^>]{0,400}?src=\\?"(https?://[^"\\]+)\\?""")

    /** `weibo.com/ttarticle/p/show?id=<数字>` 与 `card.weibo.com/article/m/show/id/<数字>`。 */
    private val TTARTICLE = Regex("""(?:ttarticle/[a-z/]*show|article/m/show/id/)[^0-9]{0,8}(\d{10,})""")
    private val ARTICLE_PARAM = Regex("""[?&/]id=(\d{10,})""")

    fun articleId(url: String): String? {
        if (!url.contains("weibo.com")) return null
        TTARTICLE.find(url)?.groupValues?.get(1)?.let { return it }
        return if (url.contains("ttarticle") || url.contains("/article/")) {
            ARTICLE_PARAM.find(url)?.groupValues?.get(1)
        } else {
            null
        }
    }

    /** `video.weibo.com/show?fid=<数字>:<数字>` 与 `weibo.com/tv/show/<数字>:<数字>`。
     *  注意第一段是 **4 位**（如 `1034:5145615399845897`）。 */
    private val FID = Regex("""fid=(\d{2,6}:\d{6,})""")
    private val TV_SHOW = Regex("""/tv/show/(\d{2,6}:\d{6,})""")

    fun videoFid(url: String): String? {
        if (!url.contains("weibo.com")) return null
        FID.find(url)?.groupValues?.get(1)?.let { return it }
        return TV_SHOW.find(url)?.groupValues?.get(1)
    }

    /** 文章接口返回体。 */
    fun parseArticle(json: String): Fields? {
        if (json.isEmpty()) return null
        val title = TITLE.find(json)?.groupValues?.get(1)?.let(JsonText::unescape)
        val author = SCREEN_NAME.find(json)?.groupValues?.get(1)?.let(JsonText::unescape)
        val cover = CONTENT_IMG.find(json)?.groupValues?.get(1)?.let(JsonText::unescape)
        val fields = Fields(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            cover = cover?.trim()?.takeIf { it.startsWith("https://") },
        )
        if (fields.title == null && fields.cover == null) return null
        return fields
    }

    /** 视频接口返回体：作者在 `reward.user` 里，`cover_image` 是**协议相对**地址。 */
    fun parseVideo(json: String): Fields? {
        if (json.isEmpty()) return null
        val title = TITLE.find(json)?.groupValues?.get(1)?.let(JsonText::unescape)
        val coverRaw = Regex(""""cover_image"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
            .find(json)?.groupValues?.get(1)?.let(JsonText::unescape)
        val cover = when {
            coverRaw == null -> null
            coverRaw.startsWith("//") -> "https:$coverRaw"
            coverRaw.startsWith("https://") -> coverRaw
            else -> null
        }
        // 作者：`reward.user.name` —— 先定位 reward，再在窗口内找 name，避免抓到别处的 name
        val at = json.indexOf("\"reward\"")
        val author = if (at >= 0) {
            Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
                .find(json.substring(at, minOf(at + 600, json.length)))
                ?.groupValues?.get(1)?.let(JsonText::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val fields = Fields(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            author = author,
            cover = cover,
        )
        if (fields.title == null && fields.cover == null) return null
        return fields
    }
}
