package io.github.kotoriinsky.tsuzuri.core

/**
 * 贴吧帖子的**镇楼图**（一楼第一张图）提取。
 *
 * 真机取证（2026-10-22，样本 `https://tieba.baidu.com/mo/m?kz=6157983019`，约 200KB）：
 * 一楼是 `<div class="content" lz="1">`，楼里的图在
 *
 *     <div class="pb_img_item" data-url='http://c.hiphotos.baidu.com/forum/w%3D250…/<hash>.jpg?&amp;src=http%3A%2F%2Fimgsrc.baidu.com%2Fforum%2Fpic%2Fitem%2F<hash>.jpg'
 *
 * `data-url` 是 250px 缩略图，`src=` 参数里是原图地址。取原图并改成 https
 *（本地实测 imgsrc.baidu.com 的 https 返回 200，约 120KB）。
 *
 * 只认**一楼**：楼层之后的图是回帖，拿来当封面会张冠李戴。
 */
object TiebaShare {

    private val FIRST_FLOOR = Regex("""<div\s+class="content"\s+lz="1"""")
    private val NEXT_FLOOR = Regex("""<div\s+class="content"\s+lz="0"""")
    private val IMG_ITEM = Regex("""class="pb_img_item"\s+data-url=['"]([^'"]+)['"]""")
    private val SRC_PARAM = Regex("""[?&](?:amp;)?src=([^&'"]+)""")

    /** 一楼第一张图的地址（https）。一楼没图、页面结构变了都返回 null。 */
    fun firstFloorImage(html: String): String? {
        val start = FIRST_FLOOR.find(html)?.range?.last ?: return null
        val end = NEXT_FLOOR.find(html, start)?.range?.first ?: html.length
        val dataUrl = IMG_ITEM.find(html.substring(start, end))?.groupValues?.get(1)
            ?.replace("&amp;", "&") ?: return null
        val original = SRC_PARAM.find(dataUrl)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        val url = (original ?: dataUrl).trim()
        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> null
        }
    }
}
