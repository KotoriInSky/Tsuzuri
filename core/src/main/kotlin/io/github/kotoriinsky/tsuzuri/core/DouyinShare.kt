package io.github.kotoriinsky.tsuzuri.core

import java.net.URI
import java.util.Locale

/**
 * 抖音分享页（`www.iesdouyin.com/share/{video|note}/<号>/`）的字段提取与地址归一。
 *
 * ## ⚠️ 这里纠正了一个**曾写进文档的错误结论**（2026-10-06）
 *
 * 先前的实测是：分享页 `window._ROUTER_DATA` 里**没有 `item_list`**（六种 UA/域名组合全为 0），
 * 于是判定"抖音结构性做不到"，还把它写进了提交信息。**那个结论是错的** ——
 * 缺的是**匿名 ttwid cookie**，不是内容。补上之后同一页面立刻有数据：
 *
 *     视频页 32429B → 36048B，item_list 0 → 1
 *     图文页 33577B → 123575B，item_list 0 → 1
 *
 * **教训（比结论本身重要）**：判断"分享页里有没有内容"之前，必须排除
 * **「我们没带对的匿名 cookie」** 这一可能 —— 否则会把"少带一个 cookie"误判成"平台做不到"。
 *
 * ## 另两条实测
 *
 * - `www.douyin.com/video/<号>` **即使带 ttwid 也没有** `_ROUTER_DATA`（72914B 的 JS 壳）
 *   → 必须走 `iesdouyin.com/share/...` 形态（[shareUrl]）；
 * - 字段位置在页面 **26~32%** 处（`item_list` 27.1%、`images` 31.8%），96KB 读取上限够用。
 *
 * 纯字符串逻辑（可离线钉住），HTTP 与 ttwid 注册在展示侧。
 */
object DouyinShare {

    data class Fields(
        val title: String?,
        val author: String?,
        val cover: String?,
        val awemeId: String?,
    )

    private val HOSTS = listOf("douyin.com", "iesdouyin.com", "amemv.com")

    fun isDouyinHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** 作品号：`/video/<号>`、`/note/<号>`、`/slides/<号>`，含 `share/` 前缀形态。 */
    private val AWEME_ID = Regex("""/(?:share/)?(?:video|note|slides)/(\d{10,20})(?:/|$)""")

    fun awemeId(url: String): String? =
        AWEME_ID.find(pathOf(url) ?: return null)?.groupValues?.get(1)

    /** 是不是图文/图集（决定分享页用 `share/note/` 还是 `share/video/`，以及岛上归类）。 */
    fun isNote(url: String): Boolean {
        val path = pathOf(url) ?: return false
        return path.contains("/note/") || path.contains("/slides/")
    }

    /**
     * 唯一**带内容**的分享页形态。拿不到作品号时返回 null（短链要先重定向）。
     */
    fun shareUrl(url: String): String? {
        val id = awemeId(url) ?: return null
        val kind = if (isNote(url)) "note" else "video"
        return "https://www.iesdouyin.com/share/$kind/$id/"
    }

    // 结尾一律不落在裸引号上，避免 Kotlin 原始字符串无法收尾（同 KuaishouShare）
    private val ITEM_START = Regex(""""item_list"\s*:\s*\[""")
    private val AWEME_ID_FIELD = Regex(""""aweme_id"\s*:\s*"(\d{6,})["]""")
    private val DESC = Regex(""""desc"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    private val AUTHOR = Regex(""""author"\s*:\s*\{[^{}]{0,300}?"nickname"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
    private val IMAGE = Regex(""""images"\s*:\s*\[[^]]{0,8000}?"url_list"\s*:\s*\[\s*"((?:[^"\\]|\\.)*)["]""")
    private val COVER = Regex(
        """"(?:cover|cover_original_scale|dynamic_cover)"\s*:\s*\{[^{}]{0,300}?"url_list"\s*:\s*\[\s*"((?:[^"\\]|\\.)*)["]""",
    )

    /** 一条作品的 JSON 大约 6~8KB（实测 `images` 在 item 起点后 5.7KB 处），留足余量。 */
    private const val ITEM_WINDOW = 24_000

    fun parse(html: String): Fields? {
        if (html.isEmpty()) return null
        val at = ITEM_START.find(html)?.range?.last?.plus(1) ?: return null
        val item = html.substring(at, minOf(at + ITEM_WINDOW, html.length))
        val title = DESC.find(item)?.groupValues?.get(1)?.let(JsonText::unescape)
        val author = AUTHOR.find(item)?.groupValues?.get(1)?.let(JsonText::unescape)
        // 图集优先用**第一张图**（图文作品的封面就是首图）；视频回落到 cover
        val cover = (IMAGE.find(item) ?: COVER.find(item))
            ?.groupValues?.get(1)?.let(JsonText::unescape)
        val fields = Fields(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            cover = cover?.trim()?.takeIf { it.startsWith("https://") },
            awemeId = AWEME_ID_FIELD.find(item)?.groupValues?.get(1),
        )
        if (fields.title == null && fields.cover == null) return null
        return fields
    }

    private fun pathOf(url: String): String? =
        runCatching { URI(url).rawPath }.getOrNull()?.takeIf { it.isNotEmpty() }
}
