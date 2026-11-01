package io.github.kotoriinsky.tsuzuri.core

/**
 * AcFun 视频页的字段提取。
 *
 * 真机取证（2026-10-06，用 fllesser 公开测试链接清单里的样本
 * `https://www.acfun.cn/v/ac46593564`，页面 60285 字节）：
 *
 *     window.videoInfo ={"id":"46593564","title":"长征十二号火箭成功首飞！…",
 *       "cover":"https://tx-free-imgs.acfun.cn/newUpload/…jpeg?imageslim&imageView2/1/w/375/h/211",
 *       "videoCover":"https://tx-…"}
 *
 * 标题与封面都是**明文**（页面 `<title>` 里也有标题，但那串带站点尾巴；
 * 这里直接用 `videoInfo.title`）。页面**没有 og:image**，所以封面只能从这段 JSON 取。
 *
 * ⚠️ 未真机验证（按用户 2026-10-06 指示"先补齐、不卡验证"）：规则与解析有离线上测，
 * 真机端到端待批量过一遍。
 */
object AcfunShare {

    data class Fields(val title: String?, val cover: String?)

    private val VIDEO_INFO = Regex("""(?:window\.)?videoInfo\s*=\s*\{""")
    private val TITLE = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    private val COVER = Regex(""""cover"\s*:\s*"((?:[^"\\]|\\.)*)["]""")

    /** `videoInfo` 这一段 JSON 约 1~2KB，取 4KB 窗口足够。 */
    private const val WINDOW = 4_000

    fun parse(html: String): Fields? {
        if (html.isEmpty()) return null
        val at = VIDEO_INFO.find(html)?.range?.last?.plus(1) ?: return null
        val block = html.substring(at, minOf(at + WINDOW, html.length))
        val title = TITLE.find(block)?.groupValues?.get(1)?.let(JsonText::unescape)
        val cover = COVER.find(block)?.groupValues?.get(1)?.let(JsonText::unescape)
        val fields = Fields(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            cover = cover?.trim()?.takeIf { it.startsWith("https://") },
        )
        if (fields.title == null && fields.cover == null) return null
        return fields
    }
}
