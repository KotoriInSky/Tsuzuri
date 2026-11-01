package io.github.kotoriinsky.tsuzuri.core

/**
 * 网页通用元信息抽取：标题、封面图、作者、描述、oEmbed 发现链接（2026-10-22）。
 *
 * 平台专用抓取器（B 站、抖音、小红书……）拿不到时才走这里。每个字段按**优先级依次尝试**，
 * 顺序参考 microlinkhq/metascraper（MIT）各字段规则包（metascraper-title / image / author /
 * description）—— 只借顺序这一事实，实现自写。
 *
 * 与 metascraper 的差异（都是刻意的）：
 *  - 只认 meta 标签、`<title>`、JSON-LD 三类来源；它那些按 CSS 类名找的规则
 *    （`.entry-title`、`h1[class*=title]`、`[class*=author]`……）依赖 DOM，core 没有 HTML 解析器，
 *    且对国内站点命中率低，不搬；
 *  - 标题在 JSON-LD 里额外认 `name`（视频 / 音乐 / 商品类结构化数据用它而不是 `headline`）；
 *  - 图片额外认 JSON-LD 的 `thumbnailUrl`（视频类）；
 *  - 作者跳过 URL 形态的值（`article:author` 常填作者主页链接）。
 *
 * 纯字符串逻辑，用固定 HTML 样本离线单测（PageMetaExtractTest）。
 */
object PageMetaExtract {

    data class Fields(
        val title: String? = null,
        val image: String? = null,
        val author: String? = null,
        val description: String? = null,
        /** 页面声明的 oEmbed 接口（`<link rel="alternate" type="application/json+oembed">`）。 */
        val oembedUrl: String? = null,
        /** 站点图标：`<link rel>` 里最大的那张，没有就是站点根的 `/favicon.ico`。 */
        val icon: String? = null,
    )

    /** 只扫前这么多字符：meta / JSON-LD 基本都在 head 与正文开头，整页正则太慢。 */
    private const val SCAN_LIMIT = 512 * 1024

    fun extract(html: String, pageUrl: String): Fields {
        val h = if (html.length > SCAN_LIMIT) html.substring(0, SCAN_LIMIT) else html
        val metas = metaTags(h)
        val ld = jsonLd(h)
        fun meta(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> metas[k.lowercase()]?.takeIf { it.isNotBlank() } }

        val title = meta("og:title", "twitter:title")
            ?: TITLE_TAG.find(h)?.groupValues?.get(1)?.let(::text)
            ?: ldString(ld, "headline")
            ?: ldString(ld.filter(::isContentNode), "name")
        val image = meta("og:image:secure_url", "og:image:url", "og:image", "twitter:image:src", "twitter:image",
            "itemprop:image")
            ?: ldString(ld, "image.0.url", "image.url", "image.0", "image", "thumbnailUrl.0", "thumbnailUrl")
        val author = ldString(ld, "author.name", "author.0.name", "author", "brand.name")
            ?: meta("author", "article:author", "twitter:creator")
        val description = meta("og:description", "twitter:description", "description", "itemprop:description")
            ?: ldString(ld, "description")
        return Fields(
            title = title?.let(::text),
            image = image?.let { absolute(PageTitle.decodeEntities(it.trim()), pageUrl) },
            author = author?.let(::text)?.takeUnless { it.startsWith("http", ignoreCase = true) },
            description = description?.let(::text),
            oembedUrl = oembedLink(h)?.let { absolute(it, pageUrl) },
            icon = (bestIcon(h) ?: "/favicon.ico").let { absolute(it, pageUrl) },
        )
    }

    // ── 站点图标 ────────────────────────────────────────────────────

    /**
     * `<link rel>` 里挑尺寸最大的图标，挑法参考 mat/besticon（MIT）：`apple-touch-icon` 没写尺寸按 180，
     * `icon` 没写尺寸按 32；`sizes="any"` 与 SVG 跳过（展示侧的位图解码器解不了矢量图）。
     */
    private fun bestIcon(html: String): String? {
        var best: String? = null
        var bestScore = -1
        for (m in LINK_TAG.findAll(html)) {
            val a = attrs(m.value)
            val href = a["href"]?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") } ?: continue
            val rels = a["rel"]?.lowercase()?.split(' ')?.filter { it.isNotEmpty() } ?: continue
            val apple = rels.any { it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }
            if (!apple && "icon" !in rels) continue
            if (a["type"]?.contains("svg", ignoreCase = true) == true ||
                href.substringBefore('?').endsWith(".svg", ignoreCase = true)
            ) continue
            val sizes = a["sizes"]?.lowercase()
            if (sizes == "any") continue
            val declared = sizes?.split(' ')?.mapNotNull { it.substringBefore('x').toIntOrNull() }?.maxOrNull()
            val score = declared ?: if (apple) 180 else 32
            if (score > bestScore) {
                bestScore = score
                best = PageTitle.decodeEntities(href)
            }
        }
        return best
    }

    // ── meta 标签 ────────────────────────────────────────────────────

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val TITLE_TAG = Regex("""<title[^>]*>([\s\S]{1,300}?)</title>""", RegexOption.IGNORE_CASE)

    private fun attrs(tag: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in ATTR.findAll(tag)) {
            val v = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
            out.putIfAbsent(m.groupValues[1].lowercase(), v)
        }
        return out
    }

    /**
     * 键 → 第一次出现的 content。键取 `property` / `name`；`itemprop` 的记成 `itemprop:<值>`，
     * 避免与同名的 `name` 撞键（`itemprop="name"` 与 `name="name"` 不是一回事）。
     */
    private fun metaTags(html: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in META_TAG.findAll(html)) {
            val a = attrs(m.value)
            val content = a["content"] ?: continue
            a["property"]?.let { out.putIfAbsent(it.lowercase(), content) }
            a["name"]?.let { out.putIfAbsent(it.lowercase(), content) }
            a["itemprop"]?.let { out.putIfAbsent("itemprop:" + it.lowercase(), content) }
        }
        return out
    }

    private fun oembedLink(html: String): String? = LINK_TAG.findAll(html).firstNotNullOfOrNull { m ->
        val a = attrs(m.value)
        a["href"]?.takeIf {
            a["type"].equals("application/json+oembed", ignoreCase = true) &&
                a["rel"]?.split(' ')?.any { r -> r.equals("alternate", ignoreCase = true) } == true
        }?.let(PageTitle::decodeEntities)
    }

    // ── JSON-LD ─────────────────────────────────────────────────────

    private val LD_SCRIPT = Regex(
        """<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE,
    )

    /** 页面里所有 JSON-LD 对象，按出现顺序；数组与 `@graph` 都展开。坏块跳过。 */
    private fun jsonLd(html: String): List<Any?> {
        val out = ArrayList<Any?>()
        fun add(v: Any?) {
            when (v) {
                is List<*> -> v.forEach(::add)
                is Map<*, *> -> {
                    out.add(v)
                    (v["@graph"] as? List<*>)?.forEach(::add)
                }
            }
        }
        for (m in LD_SCRIPT.findAll(html)) add(MiniJson.parse(m.groupValues[1].trim()))
        return out
    }

    /**
     * 「内容」节点：标题取 `name` 时只看这些。页面里常同时挂着 WebSite / Organization /
     * BreadcrumbList 等节点，它们的 `name` 是站名、公司名，当标题就错了。
     */
    private val NON_CONTENT_TYPES = setOf(
        "website", "webpage", "organization", "breadcrumblist", "person", "searchaction",
        "imageobject", "sitenavigationelement", "listitem",
    )

    private fun isContentNode(o: Any?): Boolean {
        val t = (o as? Map<*, *>)?.get("@type")
        val types = when (t) {
            is String -> listOf(t)
            is List<*> -> t.filterIsInstance<String>()
            else -> emptyList()
        }
        return types.none { it.lowercase() in NON_CONTENT_TYPES }
    }

    /** 依次试每条路径、每个对象，取第一个非空字符串。 */
    private fun ldString(objs: List<Any?>, vararg paths: String): String? {
        for (p in paths) for (o in objs) {
            val v = MiniJson.get(o, p)
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }

    // ── 小工具 ──────────────────────────────────────────────────────

    private fun text(raw: String): String? =
        PageTitle.decodeEntities(raw.replace(Regex("""<[^>]*>"""), ""))
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotEmpty() }

    /** 协议相对 `//host/x` 与站内相对 `/x` 补全成绝对地址；补不了就原样返回。 */
    private fun absolute(raw: String, pageUrl: String): String = try {
        java.net.URI(pageUrl).resolve(raw.replace(" ", "%20")).toString()
    } catch (_: Throwable) {
        raw
    }
}
