package io.github.kotoriinsky.tsuzuri.core

/**
 * 最右（`share.xiaochuankeji.cn/hybrid/share/post?pid=<数字>`）的字段提取。
 *
 * 做法借自 **ParseHub**（`z-mio/ParseHub`，**MIT**，`provider_api/zuiyou.py`）：
 * 从 URL 取 `pid` → `POST https://share.xiaochuankeji.cn/planck/share/post/detail_h5`
 * 体 `{"pid":<pid>}` → `data.post`。
 *
 * 真机取证（2026-10-06，样本 `?pid=393346270`，返回 83836 字节）确认的真实结构：
 *
 *     {"ret":1,"data":{…,"post":{"id":393346270,…,"content":"…","member":{…,"name":"…"},
 *                              "imgs":[{"id":…,"h":960,"w":540,"video":1,"fmt":"jpeg",
 *                                       "urls":{"360":{"urls":["…"]}, …}}]}}}
 *
 * **坑**：`imgs` 在**评论**里也会出现（实测 offset 297 vs post 的 61370），所以取值必须
 * **限定在 `"post":{…}` 对象内**，否则会把评论配图当封面。
 *
 * ⚠️ 未真机验证端到端（用户 2026-10-06 指示"先补齐、不卡验证"）。
 */
object ZuiyouShare {

    data class Fields(val title: String?, val author: String?, val cover: String?)

    private val PID = Regex("""[?&]pid=(\d{4,})""")

    fun pid(url: String): String? = PID.find(url)?.groupValues?.get(1)

    private val POST = Regex(""""post"\s*:\s*\{""")
    private val CONTENT = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    private val MEMBER_NAME = Regex(""""member"\s*:\s*\{[^{}]{0,2000}?"name"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    private val IMGS_AT = Regex(""""imgs"\s*:\s*\[""")
    private val ANY_HTTP = Regex("""https?://[^"\\\s]+""")

    /** post 对象约 6~8KB（实测 content 在对象头、imgs 紧随其后）。 */
    private const val WINDOW = 20_000

    /** `imgs[0]` 的缩略图窗口：取这段里**最后一个** URL（ParseHub 取 `urls[-1]` = 最大分辨率）。 */
    private const val IMG_WINDOW = 1_200

    fun parse(json: String): Fields? {
        if (json.isEmpty()) return null
        val at = POST.find(json)?.range?.last?.plus(1) ?: return null
        val post = json.substring(at, minOf(at + WINDOW, json.length))
        val title = CONTENT.find(post)?.groupValues?.get(1)?.let(JsonText::unescape)
        val author = MEMBER_NAME.find(post)?.groupValues?.get(1)?.let(JsonText::unescape)
        val cover = IMGS_AT.find(post)?.range?.last?.let { imgsAt ->
            val window = post.substring(imgsAt, minOf(imgsAt + IMG_WINDOW, post.length))
            ANY_HTTP.findAll(window).map { JsonText.unescape(it.value) }
                .filter { it.startsWith("http") }
                .lastOrNull()
        }
        val fields = Fields(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            cover = cover?.takeIf { it.startsWith("https://") },
        )
        if (fields.title == null && fields.cover == null) return null
        return fields
    }
}
