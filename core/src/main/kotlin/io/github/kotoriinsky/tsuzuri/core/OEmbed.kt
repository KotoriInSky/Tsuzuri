package io.github.kotoriinsky.tsuzuri.core

import java.net.URLEncoder

/**
 * oEmbed：用站点自己提供的标准接口拿标题 / 作者 / 缩略图（<https://oembed.com/>）。
 *
 * 两种入口：
 *  1. 页面里声明的接口（`<link rel="alternate" type="application/json+oembed">`），由
 *     [PageMetaExtract] 抽出，拿来直接请求；
 *  2. 页面抓不到或缺字段时，按**提供方清单**匹配：`resources/.../oembed_providers.json`，
 *     原样取自 iamcal/oembed（MIT，许可全文在同目录 `oembed_providers.LICENSE.txt`），
 *     393 家站点的链接形态（schemes，`*` 通配）与接口地址。
 *
 * 清单读不到时 [endpointFor] 返回 null，调用方按「没有」处理。
 */
object OEmbed {

    data class Result(val title: String?, val author: String?, val thumbnail: String?)

    private class Provider(val schemes: List<Regex>, val endpoint: String)

    private const val RESOURCE = "/io/github/kotoriinsky/tsuzuri/core/oembed_providers.json"

    private val providers: List<Provider>? by lazy { load() }

    val loaded: Boolean get() = providers != null

    private fun load(): List<Provider>? = try {
        val text = OEmbed::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        val root = text?.let(MiniJson::parse) as? List<*>
        root?.flatMap { p ->
            ((p as? Map<*, *>)?.get("endpoints") as? List<*>).orEmpty().mapNotNull { e ->
                val ep = e as? Map<*, *> ?: return@mapNotNull null
                val url = ep["url"] as? String ?: return@mapNotNull null
                val schemes = (ep["schemes"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.let(::globToRegex) }
                if (schemes.isEmpty()) null else Provider(schemes, url)
            }
        }?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /** 清单里的 scheme（如 YouTube 的 `*.youtube.com/watch*` 形态）→ 正则；只有星号是通配，其余字面匹配。 */
    private fun globToRegex(glob: String): Regex =
        Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    /** 这条链接对应的 oEmbed 请求地址（已带 `url=` 与 `format=json`）；清单里没有返回 null。 */
    fun endpointFor(url: String): String? {
        val p = providers?.firstOrNull { pr -> pr.schemes.any { it.matches(url) } } ?: return null
        return requestUrl(p.endpoint, url)
    }

    /** 拼请求地址：`{format}` 占位替换成 json，再追加 `url` 与 `format` 参数。 */
    fun requestUrl(endpoint: String, url: String): String {
        val base = endpoint.replace("{format}", "json")
        val sep = if ('?' in base) '&' else '?'
        return "$base${sep}url=${URLEncoder.encode(url, "UTF-8")}&format=json"
    }

    /** 解析接口返回的 JSON：标题、作者（`author_name`）、缩略图（`thumbnail_url`）。 */
    fun parse(json: String): Result? {
        val o = MiniJson.parse(json) as? Map<*, *> ?: return null
        fun s(k: String) = (o[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val r = Result(s("title")?.let(PageTitle::decodeEntities), s("author_name"), s("thumbnail_url"))
        return r.takeIf { it.title != null || it.author != null || it.thumbnail != null }
    }
}
