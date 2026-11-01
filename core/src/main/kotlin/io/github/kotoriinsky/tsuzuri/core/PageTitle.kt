package io.github.kotoriinsky.tsuzuri.core

import java.util.Locale

/**
 * 网页标题的清洗 —— `og:title` 与 `<title>` 兜底都要过这一关。
 *
 * 放在 `:core` 的理由与 [HtmlCharset] 相同：**纯字符串逻辑**，可以用固定样本离线钉住；
 * 抓取跑在 SystemUI 里，真机上不好造样本。原来它在 Presenter 里，只有 6 个命名实体、
 * 站点尾巴只列了我们已知的平台（2026-10-06 补齐）。
 */
object PageTitle {

    /** 数字实体：老站（WordPress 系、GBK 时代模板）里很常见，不认就会留成字面噪声。 */
    private val NUMERIC_ENTITY = Regex("""&#(x?)([0-9A-Fa-f]{1,6});""")

    /**
     * 命名实体。**`&amp;` 必须最后**，否则 `&amp;#39;` 会被二次解码成 `'`。
     */
    private val NAMED_ENTITIES = linkedMapOf(
        "&quot;" to "\"",
        "&apos;" to "'",
        "&lt;" to "<",
        "&gt;" to ">",
        "&nbsp;" to " ",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…",
        "&ldquo;" to "“",
        "&rdquo;" to "”",
        "&lsquo;" to "‘",
        "&rsquo;" to "’",
        "&middot;" to "·",
        "&times;" to "×",
        "&amp;" to "&",
    )

    /** 已知平台的站点尾巴（「- 哔哩哔哩」「_百度搜索」这类）—— 冗余，core 已有平台色表达身份。 */
    private val KNOWN_SUFFIX = Regex(
        """\s*[-_·|｜]\s*(哔哩哔哩|bilibili|百度网盘|腾讯视频|优酷|爱奇艺|知乎|微博|抖音|""" +
            """小红书|淘宝|天猫|京东|美团|拼多多|QQ音乐|网易云音乐|百度|豆瓣)$""",
        RegexOption.IGNORE_CASE,
    )

    /** 尾部一段「- xxx」/「| xxx」，用于按**页面自己的域名**判断它是不是站名。 */
    private val TRAILING_SEGMENT = Regex("""\s*[-_·|｜]\s*([^-_·|｜]{2,16})$""")

    /**
     * 安全验证 / 人机校验 / 登录拦截页的标题（2026-10-22 真机：贴吧「百度安全验证」、
     * 点评跳美团「验证中心」）。这不是内容，当作没拿到标题，岛上回落「打开 XXX」。
     */
    private val GATE_TITLE = Regex(
        """^(?:百度)?安全验证$|^验证(?:中心|码)$|^人机验证$|^请完成验证|^访问验证|^安全检测|^Just a moment|^Attention Required|^Access Denied|^请登录$|^登录$""",
        RegexOption.IGNORE_CASE,
    )

    fun isGatePage(title: String): Boolean = GATE_TITLE.containsMatchIn(title.trim())

    private val SEPARATORS = charArrayOf('-', '_', '·', '|', '｜', ' ', '—', '–')

    /** HTML 实体解码（数字实体 + 常见命名实体）。属性值、标题、作者名共用。 */
    fun decodeEntities(raw: String): String =
        NUMERIC_ENTITY.replace(raw) { m -> decodeNumeric(m.value, m.groupValues[1], m.groupValues[2]) }
            .let { s -> NAMED_ENTITIES.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) } }

    /**
     * 清洗。[pageUrl] 可选：给了就能剥掉**长尾站点的站名尾巴**（见 [stripHostSuffix]）。
     */
    fun clean(raw: String, pageUrl: String? = null): String? {
        var x = raw
            // 整个标签（含闭合 `>`）。原实现写成 `<[^>]*`（未闭合），`<b>标题</b>` 会留下
            // 孤立的 `>`（`>标题>`）；而且遇到「a < b」这种裸 `<` 会把后面全删掉。
            .replace(Regex("""<[^>]*>"""), "")
            .let(::decodeEntities)
            .replace(Regex("""\s+"""), " ")
            .trim()
        // 已知平台的尾巴可能叠两层（「标题 - 知乎 - 知乎」这种模板错误）
        while (true) {
            val next = KNOWN_SUFFIX.replace(x, "").trim()
            if (next == x) break
            x = next
        }
        x = stripHostSuffix(x, pageUrl)
        if (x.isEmpty() || isGatePage(x)) return null
        val hasCjk = x.any { it in '\u4e00'..'\u9fa5' }
        if (!hasCjk && x.length < 8) return null
        if (x.length > 100) x = x.take(100) + "…"
        return x
    }

    /**
     * 站点尾巴的**通用**兜底：长尾站点的 `<title>` 常写成「标题 - 某某站」，而
     * [KNOWN_SUFFIX] 只列了我们已知的平台 —— 长尾的站名根本不在表里。
     *
     * 判据用**页面自己的域名**，而不是"猜任意后缀"：尾巴那段要能在 host 的主标签里
     * 找到才剥。所以「报告 - 附件」这类真标题不会被误伤（host 里没有"附件"）。
     */
    private fun stripHostSuffix(title: String, pageUrl: String?): String {
        val host = pageUrl
            ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            ?.lowercase(Locale.ROOT)
            ?: return title
        // 去掉 www 与顶级域，只看主标签（bbs.example.com → [bbs, example]）
        val labels = host.split('.')
            .filter { it.isNotEmpty() && it != "www" }
            .dropLast(1)
            .filter { it.length >= 3 }
        if (labels.isEmpty()) return title
        val m = TRAILING_SEGMENT.find(title) ?: return title
        val tail = m.groupValues[1].replace(" ", "").lowercase(Locale.ROOT)
        if (tail.length < 3) return title
        if (labels.none { tail.contains(it) || it.contains(tail) }) return title
        return title.substring(0, m.range.first).trimEnd(*SEPARATORS).trim()
    }

    private fun decodeNumeric(whole: String, hexFlag: String, digits: String): String {
        val code = digits.toIntOrNull(if (hexFlag.isEmpty()) 10 else 16) ?: return whole
        if (code !in 1..0x10FFFF) return whole
        return runCatching { String(Character.toChars(code)) }.getOrDefault(whole)
    }
}
