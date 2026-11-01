package io.github.kotoriinsky.tsuzuri.core

/**
 * 皮皮虾（`h5.pipix.com/(s|ppx/item)/…`）的字段提取。
 *
 * 做法借自 **ParseHub**（`z-mio/ParseHub`，**MIT**，`provider_api/pipix.py`）：
 * GET 分享页 → 取 `<script id="RENDER_DATA">` 的文本 → **URL 解码** → JSON →
 * `ppxItemDetail.item`：`content`（文案）、`cover.download_list[].url`（图集）、
 * `video.video_download.cover_image.download_list[0].url`（视频封面）；
 * `item_type` 1=图 2=视频。
 *
 * ⚠️ **没有可用的真实样本**：ParseHub 的离线测试里那两个 URL 是占位（`/s/example/`、
 * `/ppx/item/1234567890`），我这边也没有用户分享过。所以本文件是**按它的做法实现**，
 * 结构按它代码里的字段路径合成用例 —— **端到端有效性完全未验证**（用户 2026-10-06
 * 指示"先补齐、不卡验证"）。拿到真实链接后应按实际情况校准。
 */
object PipixShare {

    data class Fields(val title: String?, val cover: String?, val isVideo: Boolean)

    /** `h5.pipix.com/(s|ppx/item)/…`。 */
    fun isPipixUrl(host: String): Boolean = host.equals("pipix.com", true) || host.endsWith(".pipix.com", true)

    private val RENDER_DATA = Regex(
        """<script[^>]*id=["']RENDER_DATA["'][^>]*>([\s\S]{1,400000}?)</script>""",
        RegexOption.IGNORE_CASE,
    )
    private val ITEM = Regex(""""item"\s*:\s*\{""")
    private val CONTENT = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    private val ITEM_TYPE = Regex(""""item_type"\s*:\s*(\d+)""")
    private val DOWNLOAD_URL = Regex(""""download_list"\s*:\s*\[\s*\{[^{}]{0,300}?"url"\s*:\s*"((?:[^"\\]|\\.)*)["]""")

    /** item 对象内部取字段的窗口。 */
    private const val WINDOW = 30_000

    /**
     * 从分享页 HTML 里取字段；页面里没有 `RENDER_DATA` 或没有 item 时返回 null。
     */
    fun parse(html: String): Fields? {
        if (html.isEmpty()) return null
        val script = RENDER_DATA.find(html)?.groupValues?.get(1)?.trim() ?: return null
        val json = percentDecode(script)
        val at = ITEM.find(json)?.range?.last?.plus(1) ?: return null
        val item = json.substring(at, minOf(at + WINDOW, json.length))
        val title = CONTENT.find(item)?.groupValues?.get(1)?.let(JsonText::unescape)
        val type = ITEM_TYPE.find(item)?.groupValues?.get(1)?.toIntOrNull()
        val cover = DOWNLOAD_URL.find(item)?.groupValues?.get(1)?.let(JsonText::unescape)
        val fields = Fields(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            cover = cover?.trim()?.takeIf { it.startsWith("https://") },
            isVideo = type == 2,
        )
        if (fields.title == null && fields.cover == null) return null
        return fields
    }

    /**
     * `%XX` 解码 —— **不动 `+`**（`java.net.URLDecoder` 会把 `+` 变成空格，而这里是
     * URL 编码的 JSON，`+` 可能是内容本身）。与 ParseHub 用的 `unquote` 语义一致。
     *
     * 实现要点：**按字节流处理** —— `%XX` 写入原始字节、字面字符写入它的 UTF-8 字节，
     * 最后统一按 UTF-8 解释。早先那版"先拼字符串再整体按 ISO-8859-1 重解释"会把
     * **字面中文**也毁成 `?`（被自己的用例抓到）。
     */
    internal fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val code = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out.write(code)
                    i += 3
                    continue
                }
            }
            val cp = s.codePointAt(i)
            out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
