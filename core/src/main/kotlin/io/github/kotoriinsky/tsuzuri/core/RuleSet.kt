package io.github.kotoriinsky.tsuzuri.core

/**
 * 口令规则格式 v2 的数据模型与解析。
 *
 * 内置规则（APK 内 JSON）与订阅规则（tsuzuri-rules 仓库）共用这一种格式、这一个解析器。
 * 解析只管「结构对不对」：类型错、缺必填字段的**单条**丢掉并记一条 [Issue]；
 * 顶层坏了（不是对象、`format` 不认识、没有 `version`）整份返回 null。
 * 语义检查（正则能否编译、平台引用、模板、样例……）在 [RuleSetCheck]。
 *
 */
object RuleSet {

    const val FORMAT = 2

    data class Doc(
        val format: Int,
        /** 单调递增：订阅侧比当前版本小的一律拒绝（防降级），见 [RuleBundle]。 */
        val version: Long,
        val platforms: List<Platform>,
        val hosts: List<HostRule>,
        val passcodes: List<PasscodeRule>,
    )

    data class Platform(
        val id: String,
        val name: String,
        val pkg: String?,
        val accent: String,
        val category: PasscodeIsland.ContentCategory,
        /** 分享文案平台：链接前后常带标题行（对应 `SHARE_PLATFORMS`）。 */
        val share: Boolean = false,
    )

    /** 应该命中的样例；[target] / [core] 给了就要逐字相等。 */
    data class Sample(val text: String, val target: String? = null, val core: String? = null)

    data class Samples(val hit: List<Sample> = emptyList(), val miss: List<String> = emptyList())

    /** 规则共有的部分。 */
    sealed interface Rule {
        val id: String
        val label: String
        val platform: String?
        /** 抓取器名字，见 [RuleCapabilities.META]；null = 不抓。 */
        val meta: String?
        val samples: Samples
        val minAppVersion: Int
    }

    /**
     * 域名 → 平台。主机名等于或以 `.<host>` 结尾即算命中；[pathPrefix] 给了还要路径以它开头
     * （`weixin.qq.com/sph`、`doubao.com/video-sharing` 这类只认一段路径的）。打开目标就是链接本身。
     */
    data class HostRule(
        override val id: String,
        override val label: String,
        override val platform: String,
        val hosts: List<String>,
        val pathPrefix: String? = null,
        override val meta: String? = null,
        override val samples: Samples = Samples(),
        override val minAppVersion: Int = 0,
    ) : Rule

    /**
     * 裸口令 / 内容号。[target] / [core] / [alts] 是模板或白名单里的构造器（`@name`），见 [RuleTemplate]；
     * [accept] 是白名单里的二次判据名字（校验位、上下文）。
     */
    data class PasscodeRule(
        override val id: String,
        override val label: String,
        val regex: String,
        val anchored: Boolean = false,
        /** 需要上下文门控（如纯数字快递单号），对应 `PasscodeRules.Options.numericTracking`。 */
        val requiresContext: Boolean = false,
        override val platform: String? = null,
        val target: String,
        val core: String? = null,
        val alts: List<String> = emptyList(),
        val accept: String? = null,
        override val meta: String? = null,
        override val samples: Samples = Samples(),
        override val minAppVersion: Int = 0,
    ) : Rule

    /**
     * 主机名是否命中 [pattern]：等于它或以 `.<pattern>` 结尾。`name.*` 是任意后缀的通配
     * （`pinterest.*` 认 pinterest.com / pinterest.co.uk …），与旧表 `pinterest\.[a-z.]+` 同义。
     * [host] 要已经转成小写。
     */
    fun hostMatches(pattern: String, host: String): Boolean {
        if (pattern.endsWith(".*")) {
            val base = pattern.dropLast(2)
            val i = if (host.startsWith("$base.")) 0 else host.indexOf(".$base.").takeIf { it >= 0 }?.plus(1) ?: return false
            val rest = host.substring(i + base.length + 1)
            return rest.isNotEmpty() && rest.all { it in 'a'..'z' || it == '.' }
        }
        return host == pattern || host.endsWith(".$pattern")
    }

    /** 域名规则是否命中这条链接（主机名 + 可选路径前缀；路径不分大小写，与旧表对整条链接小写后匹配一致）。 */
    fun hostRuleMatches(rule: HostRule, host: String, rawPath: String): Boolean =
        rule.hosts.any { hostMatches(it, host) } &&
            (rule.pathPrefix == null || rawPath.lowercase().startsWith(rule.pathPrefix.lowercase()))

    enum class Severity { ERROR, WARNING }

    /** 一条问题。[ruleId] 为 null 表示文件级。 */
    data class Issue(val ruleId: String?, val message: String, val severity: Severity = Severity.ERROR) {
        override fun toString(): String = "${severity.name.lowercase()} ${ruleId ?: "<file>"}: $message"
    }

    data class Parsed(val doc: Doc?, val issues: List<Issue>)

    fun parse(json: String): Parsed {
        val issues = ArrayList<Issue>()
        val root = MiniJson.parse(json) as? Map<*, *>
            ?: return Parsed(null, listOf(Issue(null, "不是 JSON 对象，或超过解析上限")))
        val format = (root["format"] as? Double)?.toInt()
        if (format != FORMAT) return Parsed(null, listOf(Issue(null, "format=$format 不认识（应为 $FORMAT）")))
        val version = (root["version"] as? Double)?.takeIf { it >= 0 && it == Math.floor(it) }?.toLong()
            ?: return Parsed(null, listOf(Issue(null, "缺少 version，或不是非负整数")))

        fun <T> each(key: String, read: (Map<*, *>, Reader) -> T): List<T> {
            val raw = root[key] ?: return emptyList()
            val list = raw as? List<*> ?: run {
                issues += Issue(null, "$key 应为数组")
                return emptyList()
            }
            return list.mapIndexedNotNull { i, o ->
                val m = o as? Map<*, *>
                if (m == null) {
                    issues += Issue(null, "$key[$i] 不是对象")
                    return@mapIndexedNotNull null
                }
                val r = Reader(m, (m["id"] as? String) ?: "$key[$i]")
                try {
                    read(m, r)
                } catch (e: Bad) {
                    issues += Issue(r.id, e.message ?: "格式错误")
                    null
                }
            }
        }

        val platforms = each("platforms") { _, r ->
            Platform(
                id = r.str("id"),
                name = r.str("name"),
                pkg = r.optStr("pkg"),
                accent = r.str("accent"),
                category = r.str("category").let {
                    PasscodeIsland.ContentCategory.fromWire(it) ?: throw Bad("category=$it 不认识")
                },
                share = r.bool("share", false),
            )
        }
        val hosts = each("hosts") { _, r ->
            HostRule(
                id = r.str("id"),
                label = r.str("label"),
                platform = r.str("platform"),
                hosts = r.strList("hosts").ifEmpty { throw Bad("hosts 不能为空") },
                pathPrefix = r.optStr("pathPrefix"),
                meta = r.optStr("meta"),
                samples = r.samples(),
                minAppVersion = r.int("minAppVersion", 0),
            )
        }
        val passcodes = each("passcodes") { _, r ->
            PasscodeRule(
                id = r.str("id"),
                label = r.str("label"),
                regex = r.str("regex"),
                anchored = r.bool("anchored", false),
                requiresContext = r.bool("requiresContext", false),
                platform = r.optStr("platform"),
                target = r.str("target"),
                core = r.optStr("core"),
                alts = r.strList("alts"),
                accept = r.optStr("accept"),
                meta = r.optStr("meta"),
                samples = r.samples(),
                minAppVersion = r.int("minAppVersion", 0),
            )
        }
        return Parsed(Doc(format, version, platforms, hosts, passcodes), issues)
    }

    private class Bad(message: String) : RuntimeException(message, null, false, false)

    private class Reader(private val m: Map<*, *>, val id: String) {
        fun str(key: String): String =
            (m[key] as? String)?.takeIf { it.isNotEmpty() } ?: throw Bad("缺少字符串字段 $key")

        fun optStr(key: String): String? = when (val v = m[key]) {
            null -> null
            is String -> v.ifEmpty { null }
            else -> throw Bad("$key 应为字符串")
        }

        fun bool(key: String, default: Boolean): Boolean = when (val v = m[key]) {
            null -> default
            is Boolean -> v
            else -> throw Bad("$key 应为布尔值")
        }

        fun int(key: String, default: Int): Int = when (val v = m[key]) {
            null -> default
            is Double -> v.takeIf { it == Math.floor(it) && it >= 0 && it <= Int.MAX_VALUE }?.toInt()
                ?: throw Bad("$key 应为非负整数")
            else -> throw Bad("$key 应为整数")
        }

        fun strList(key: String): List<String> = when (val v = m[key]) {
            null -> emptyList()
            is List<*> -> v.map { (it as? String)?.takeIf(String::isNotEmpty) ?: throw Bad("$key 里有非字符串项") }
            else -> throw Bad("$key 应为字符串数组")
        }

        fun samples(): Samples {
            val s = m["samples"] ?: return Samples()
            val o = s as? Map<*, *> ?: throw Bad("samples 应为对象")
            val hit = when (val h = o["hit"]) {
                null -> emptyList()
                is List<*> -> h.map { e ->
                    when (e) {
                        is String -> Sample(e)
                        is Map<*, *> -> Sample(
                            text = (e["text"] as? String)?.takeIf(String::isNotEmpty) ?: throw Bad("samples.hit 缺少 text"),
                            target = e["target"] as? String,
                            core = e["core"] as? String,
                        )
                        else -> throw Bad("samples.hit 里的项应为字符串或对象")
                    }
                }
                else -> throw Bad("samples.hit 应为数组")
            }
            val miss = when (val x = o["miss"]) {
                null -> emptyList()
                is List<*> -> x.map { (it as? String) ?: throw Bad("samples.miss 里有非字符串项") }
                else -> throw Bad("samples.miss 应为数组")
            }
            return Samples(hit, miss)
        }
    }
}
