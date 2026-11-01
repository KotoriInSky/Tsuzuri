package io.github.kotoriinsky.tsuzuri.core

import java.util.Locale

/**
 * 公共后缀表（Public Suffix List）查询：一个主机名的**注册域**是什么、它是不是公网域名。
 *
 * 数据是 `resources/.../public_suffix_list.dat`，原样取自 <https://publicsuffix.org/list/>，
 * **MPL-2.0**（文件头自带许可声明；按文件生效，只约束这一个数据文件，见 THIRD-PARTY.md）。
 * 更新：重新下载覆盖即可，文件头的 `VERSION` 行记录了版本。
 *
 * 两个用途（2026-10-22）：
 *  - 「识别所有链接」只对**公网域名**弹岛：`localhost`、内网主机名、`.local`、IP 地址都不是；
 *  - 岛上显示注册域（`m.weibo.cn` → `weibo.cn`），比原样的主机名好认。
 *
 * 数据读不到（资源缺失 / 加载失败）时 [loaded] 为 false，两个函数都返回 null，
 * 调用方必须按「不知道」处理、退回改动前的行为 —— 不能因为表没加载就把所有链接都拦掉。
 */
object PublicSuffix {

    private const val RESOURCE = "/io/github/kotoriinsky/tsuzuri/core/public_suffix_list.dat"

    private class Rules(val exact: Set<String>, val wildcard: Set<String>, val exception: Set<String>)

    private val rules: Rules? by lazy { load() }

    val loaded: Boolean get() = rules != null

    private fun load(): Rules? = try {
        // 绝对路径：release 包开了 R8，类名 / 包名可能被改，相对路径会跟着失效
        val stream = PublicSuffix::class.java.getResourceAsStream(RESOURCE) ?: return null
        val exact = HashSet<String>(16384)
        val wildcard = HashSet<String>()
        val exception = HashSet<String>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) continue
                // 规则只取第一个空白前的部分（规范如此）
                val rule = line.substringBefore(' ').lowercase(Locale.ROOT)
                when {
                    rule.startsWith("!") -> exception.add(rule.substring(1))
                    rule.startsWith("*.") -> wildcard.add(rule.substring(2))
                    else -> exact.add(rule)
                }
            }
        }
        if (exact.isEmpty()) null else Rules(exact, wildcard, exception)
    } catch (_: Throwable) {
        null
    }

    /**
     * 主机名的公共后缀（如 `co.uk`、`com.cn`、`github.io`）。表里没有任何规则命中时返回 null ——
     * 这正是「不是公网域名」的判据（规范里的默认 `*` 规则在这里**故意不用**）。
     * 表没加载时也返回 null，用 [loaded] 区分两种情况。
     */
    fun suffixOf(host: String): String? {
        val r = rules ?: return null
        val labels = normalize(host)?.split('.') ?: return null
        // 从最长的候选往短的找，第一个命中即最长匹配
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in r.exception) {
                // 例外规则：命中的那一级本身是注册域，后缀是它去掉最左一级
                return labels.subList(i + 1, labels.size).joinToString(".").ifEmpty { null }
            }
            if (candidate in r.exact) return candidate
            val parent = labels.subList(i + 1, labels.size).joinToString(".")
            if (parent.isNotEmpty() && parent in r.wildcard) return candidate
        }
        return null
    }

    /** 注册域 = 公共后缀再往左一级（`news.bbc.co.uk` → `bbc.co.uk`）。主机名本身就是后缀时返回 null。 */
    fun registrableDomain(host: String): String? {
        val h = normalize(host) ?: return null
        val suffix = suffixOf(h) ?: return null
        if (h == suffix) return null
        val left = h.removeSuffix(".$suffix").substringAfterLast('.')
        return "$left.$suffix"
    }

    /**
     * 是不是公网域名：true / false；表没加载时 null（调用方按「不知道」放行）。
     */
    fun isPublicHost(host: String): Boolean? {
        if (!loaded) return null
        return registrableDomain(host) != null
    }

    /**
     * 本机 / 内网地址：`localhost`、IP 字面量、单段主机名、`.local` / `.lan` / `.home.arpa` / `.internal`。
     *
     * 「识别所有链接」对它们也弹岛、交给浏览器打开（用户 2026-10-22：「localhost 弹浏览器就可以了」），
     * 但展示侧不去抓它们的网页 —— 不可达的内网地址只会白等连接超时。与 [isPublicHost] 不同，
     * 不依赖公共后缀表，表没加载时照样能判。
     */
    fun isLocalHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.contains(':') || h.all { it.isDigit() || it == '.' }) return true
        if (!h.contains('.')) return true
        return LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

    private fun normalize(host: String): String? {
        val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (h.isEmpty() || !h.contains('.')) return null
        // IP 地址（v4 或带冒号的 v6）不是域名
        if (h.contains(':') || h.all { it.isDigit() || it == '.' }) return null
        // 表里是 Unicode 形式；punycode 主机名先转回来再查
        return if (h.split('.').any { it.startsWith("xn--") }) {
            runCatching { java.net.IDN.toUnicode(h) }.getOrDefault(h)
        } else {
            h
        }
    }
}
