package io.github.kotoriinsky.tsuzuri.core

/**
 * 快手分享页的字段提取。
 *
 * 页面里 `window.INIT_STATE` 的**顶层键被逐字符位移混淆过**（`tusjoh.0sftu0xe…` 每位 -1 才是
 * `string/rest/...`），所以不能按 JSON 路径取值；但**内层字段名是明文**，直接按字段名取即可
 * —— 与 [PasscodeRules] 里贴吧 `forum_name` 同一套做法。
 *
 * 真机取证（2026-10-06，用户提供的真实分享链 `v.kuaishou.com/nA6zqmo2` →
 * `v.m.chenzhongtech.com/fw/photo/3xn3yeegd6bq2ga?…`）：
 * 页面 182558 字节，关键字段的偏移是 `headUrl` 171449 / `coverUrls` 167515 /
 * `caption` 171757 / `photoId` 172010 —— **都在 91~94% 处**，所以抓取方的读取上限必须够大
 * （见 Presenter 里的 `PAGE_CAP_KUAISHOU`）。
 *
 * 放在 `:core` 的理由同 [HtmlCharset] / [PageTitle]：纯字符串逻辑，可用固定样本离线钉住。
 */
object KuaishouShare {

    /** 提取到的字段；取不到的为 null（展示侧按字段降级）。 */
    data class Fields(
        val title: String?,
        val author: String?,
        val avatar: String?,
        val cover: String?,
        val photoId: String?,
    )

    // 结尾用 [" ] 而不是裸引号，避免 Kotlin 原始字符串的 `"""` 与字面引号相邻而无法收尾
    private val CAPTION = Regex(""""caption"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    private val COVER = Regex(""""coverUrls"\s*:\s*\[[^]]*?"url"\s*:\s*"([^"]+)["]""")
    private val PHOTO_ID = Regex(""""photoId"\s*:\s*"([^"]+)["]""")

    /** 头像是在**同一个用户对象**里、`userName` 之前，所以两个字段一起取，避免抓到评论者的昵称。 */
    private val USER = Regex(
        """"headUrl"\s*:\s*"([^"]+)["][^{}]*?"userName"\s*:\s*"((?:[^"\\]|\\.)*)["]""",
    )

    fun parse(html: String): Fields? {
        if (html.isEmpty()) return null
        val caption = CAPTION.find(html)?.groupValues?.get(1)?.let(JsonText::unescape)
        val cover = COVER.find(html)?.groupValues?.get(1)?.let(JsonText::unescape)
        val user = USER.find(html)
        val fields = Fields(
            title = caption?.trim()?.takeIf { it.isNotEmpty() },
            author = user?.groupValues?.get(2)?.let(JsonText::unescape)?.trim()?.takeIf { it.isNotEmpty() },
            avatar = user?.groupValues?.get(1)?.let(JsonText::unescape)?.trim()?.takeIf { it.isNotEmpty() },
            cover = cover?.trim()?.takeIf { it.startsWith("https://") },
            photoId = PHOTO_ID.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
        )
        // 标题与封面都没有 = 这条页面里没有可用内容，别让上层以为"解析成功"
        if (fields.title == null && fields.cover == null) return null
        return fields
    }
}
