package io.github.kotoriinsky.tsuzuri.core

/**
 * 规则文件能引用的**代码能力白名单**：订阅规则带不了代码，只能按名字引用这里列出的实现，
 * 不认识的名字一律按「没有」处理（校验时报警告，加载时整条规则跳过 —— 见 [RuleSetCheck]）。
 *
 * 加名字 = 发版。名字一旦发布就不能改含义：旧版 App 会按旧含义执行同名能力。
 */
object RuleCapabilities {

    /**
     * 抓取器：
     *  - `auto`：交给展示侧按链接分派（平台专用抓取器 → 通用元信息），即现在内置规则的行为；
     *  - `og`：只用通用网页元信息（`PageMetaExtract` + oEmbed），新平台用这个；
     *  - `none`：不抓。
     */
    val META: Set<String> = setOf("auto", "og", "none")

    /** 二次判据：参数是原文与命中值（有捕获组取第 1 组，否则整段命中）。 */
    val ACCEPT: Map<String, (text: String, value: String) -> Boolean> = mapOf(
        // 万国邮联 S10：形态是 S10 时校验位必须对；不是 S10 形态（SF… / JD…）不受影响
        "s10" to { _, v -> !PasscodeIsland.isS10Shape(v) || ExpressIntl.isValidS10(v) },
        // 自带前缀的国际单号（UPS 1Z / 亚马逊 / 云途 / DHL 件号）：校验位通过
        "intl_prefixed" to { _, v -> ExpressIntl.prefixedCarrier(v) != null },
        // 纯数字国际单号（FedEx / DHL / USPS）：同文出现承运商名且校验位通过
        "intl_numeric" to { text, v -> ExpressIntl.numericCarrier(v, text) != null },
    )

    /** `target` 的构造器（`@name` 或 `@name:<模板>`，参数默认是命中值）。 */
    val TARGET_BUILDERS: Map<String, (text: String, arg: String) -> String?> = mapOf(
        // 快递链首：负一屏快递详情（PasscodeRules.expressTargetChain）
        "express" to { _, a -> PasscodeRules.expressHeadTarget(a) },
    )

    /** `alts` 的构造器。 */
    val ALT_BUILDERS: Map<String, (text: String, arg: String) -> String?> = mapOf(
        // 快递备选：菜鸟 App → 支付宝菜鸟小程序 → 快递100（顺序即优先级）
        "express_cainiao" to { _, a -> PasscodeRules.cainiaoAppTarget(a) },
        "express_alipay" to { _, a -> PasscodeRules.cainiaoMiniProgramTarget(a) },
        "express_web" to { _, a -> PasscodeRules.expressWebTarget(a) },
    )

    /** `core` 的构造器。 */
    val CORE_BUILDERS: Map<String, (text: String, arg: String) -> String?> = mapOf(
        // 分享文案的标题行（淘口令：token 本身是乱码，核心内容取标题）
        "title" to { text, _ -> PasscodeRules.extractTitle(text) },
    )

    /**
     * 模板里允许的跳转 scheme：`https` 与内置规则已经在用的 App scheme。
     * 不许 `http`、`intent`、`javascript`、`file`、`content`、`data`。
     */
    val TARGET_SCHEMES: Set<String> = setOf(
        "https", "taobao", "alipays", "baiduyun", "qqmusic", "dongqiudi", "huputiyu",
        "com.sf-express", "openapp.jdlogistic",
    )
}

/**
 * 规则里的字符串模板：`https://www.bilibili.com/video/BV{1}`、`{1|lower}{2}`，或白名单构造器 `@express`、
 * `@express:{1|upper}`。`{n}` 取第 n 个捕获组（0 = 整段命中），过滤器只有 `lower` / `upper`。
 */
class RuleTemplate private constructor(
    private val builder: String?,
    private val parts: List<Part>,
) {
    private sealed interface Part
    private data class Lit(val s: String) : Part
    private data class Group(val n: Int, val filter: String?) : Part

    /** 用到的最大组号（校验时与正则的组数比较）；没有占位符为 -1。 */
    val maxGroup: Int get() = parts.filterIsInstance<Group>().maxOfOrNull { it.n } ?: -1

    val builderName: String? get() = builder

    /** 构造器参数省略时的模板。 */
    val hasExplicitArg: Boolean = parts.isNotEmpty()

    /** 字面量开头的 scheme（`https://…` → `https`）；scheme 里有占位符或不是 `x://` 形态返回 null。 */
    fun literalScheme(): String? {
        val head = (parts.firstOrNull() as? Lit)?.s ?: return null
        val i = head.indexOf("://")
        if (i <= 0) return null
        return head.substring(0, i).lowercase()
    }

    /**
     * 展开。[builders] 是这个槽位的白名单；构造器不在里面返回 null（校验阶段已报过）。
     */
    fun expand(text: String, m: MatchResult, builders: Map<String, (String, String) -> String?>): String? {
        val body = buildString {
            for (p in parts) when (p) {
                is Lit -> append(p.s)
                is Group -> {
                    val v = m.groupValues.getOrNull(p.n) ?: return null
                    append(
                        when (p.filter) {
                            "lower" -> v.lowercase()
                            "upper" -> v.uppercase()
                            else -> v
                        },
                    )
                }
            }
        }
        if (builder == null) return body
        val fn = builders[builder] ?: return null
        val arg = if (hasExplicitArg) body else defaultValue(m)
        return fn(text, arg)
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{(\d)(?:\|(lower|upper))?\}""")
        private val BUILDER = Regex("""^@([a-z][a-z0-9_]*)(?::(.*))?$""", RegexOption.DOT_MATCHES_ALL)

        /** 命中值：有捕获组取第 1 组，否则整段（与 [RuleCapabilities.ACCEPT] 的参数一致）。 */
        fun defaultValue(m: MatchResult): String = m.groupValues.getOrNull(1) ?: m.value

        /** 解析失败（未闭合的 `{`、未知过滤器、空串）返回 null。 */
        fun parse(s: String): RuleTemplate? {
            if (s.isEmpty()) return null
            val b = BUILDER.matchEntire(s)
            val name = b?.groupValues?.get(1)
            val body = if (b != null) b.groupValues[2] else s
            val parts = ArrayList<Part>()
            var i = 0
            for (m in PLACEHOLDER.findAll(body)) {
                if (m.range.first > i) parts += Lit(body.substring(i, m.range.first))
                parts += Group(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { null })
                i = m.range.last + 1
            }
            if (i < body.length) parts += Lit(body.substring(i))
            // 剩下的字面量里不能再有花括号：多半是写错的占位符（`{1|trim}`、`{12}`）
            if (parts.any { it is Lit && (it.s.contains('{') || it.s.contains('}')) }) return null
            return RuleTemplate(name, parts)
        }
    }
}
