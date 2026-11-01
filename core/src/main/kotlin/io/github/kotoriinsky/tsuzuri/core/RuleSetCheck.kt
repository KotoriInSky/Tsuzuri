package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.RuleSet.Issue
import io.github.kotoriinsky.tsuzuri.core.RuleSet.Severity

/**
 * 规则文件的语义校验。App 加载与规则仓库的 CI 跑的是**同一个**校验器，
 * 区别只在 [Mode]：
 *  - [Mode.LOAD]（App）：有错的**单条**跳过，其余照常用；`minAppVersion` 高于当前版本的单条跳过；
 *    不要求样例（样例在 CI 里已经跑过）。
 *  - [Mode.CI]（规则仓库）：所有问题都报出来；每条规则**必须**有「应该命中」的样例（Q14）。
 *    样例本身由 [RuleSetSamples] 执行。
 *
 * 通过校验的规则编译好放进 [Checked]，[RuleSetMatcher] 直接用。
 */
object RuleSetCheck {

    enum class Mode { LOAD, CI }

    class Host(val rule: RuleSet.HostRule, val hosts: List<String>)

    class Passcode(
        val rule: RuleSet.PasscodeRule,
        val regex: Regex,
        val target: RuleTemplate,
        val core: RuleTemplate?,
        val alts: List<RuleTemplate>,
        val accept: ((String, String) -> Boolean)?,
    )

    class Checked(
        val version: Long,
        /** 本文件声明的平台 + 调用方传入的已知平台（订阅规则可以引用内置平台）。 */
        val platforms: Map<String, RuleSet.Platform>,
        val hosts: List<Host>,
        val passcodes: List<Passcode>,
        val issues: List<Issue>,
    ) {
        val ok: Boolean get() = issues.none { it.severity == Severity.ERROR }
    }

    private val ID = Regex("""^[a-z0-9][a-z0-9_.-]{0,63}$""")
    private val HOST = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""")
    /** `pinterest.*`：任意后缀通配，见 [RuleSet.hostMatches]。 */
    private val WILDCARD = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?\.\*$""")
    private val PKG = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")
    private val COLOR = Regex("""^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$""")

    fun check(
        doc: RuleSet.Doc,
        mode: Mode,
        appVersion: Int = Int.MAX_VALUE,
        knownPlatforms: Map<String, RuleSet.Platform> = emptyMap(),
    ): Checked {
        val issues = ArrayList<Issue>()

        // ── 平台 ──
        val platforms = LinkedHashMap(knownPlatforms)
        val declared = HashSet<String>()
        for (p in doc.platforms) {
            val errs = ArrayList<String>()
            if (!ID.matches(p.id)) errs += "id 只能是小写字母、数字与 _ . -"
            if (!declared.add(p.id)) errs += "平台 id 重复"
            if (p.name.isBlank()) errs += "name 为空"
            if (p.pkg != null && !PKG.matches(p.pkg)) errs += "pkg=${p.pkg} 不是包名"
            if (!COLOR.matches(p.accent)) errs += "accent=${p.accent} 不是 #RRGGBB / #AARRGGBB"
            errs.forEach { issues += Issue(p.id, it) }
            if (errs.isEmpty()) platforms[p.id] = p
        }

        val seenIds = HashSet<String>()

        fun common(rule: RuleSet.Rule, errs: MutableList<String>, warns: MutableList<String>): Boolean {
            if (!ID.matches(rule.id)) errs += "id 只能是小写字母、数字与 _ . -"
            if (!seenIds.add(rule.id)) errs += "规则 id 重复"
            if (rule.label.isBlank()) errs += "label 为空"
            if (rule.platform != null && rule.platform !in platforms) errs += "引用了不存在的平台 ${rule.platform}"
            if (rule.meta != null && rule.meta !in RuleCapabilities.META) warns += "抓取器 ${rule.meta} 不认识，按「不抓」处理"
            if (mode == Mode.CI && rule.samples.hit.isEmpty()) errs += "至少要有一个「应该命中」的样例"
            if (rule.minAppVersion > appVersion) {
                issues += Issue(rule.id, "需要 App 版本 ${rule.minAppVersion}，当前 $appVersion，跳过", Severity.WARNING)
                return false
            }
            return true
        }

        fun flush(id: String, errs: List<String>, warns: List<String>) {
            errs.forEach { issues += Issue(id, it) }
            warns.forEach { issues += Issue(id, it, Severity.WARNING) }
        }

        // ── 域名规则 ──
        val hosts = ArrayList<Host>()
        for (r in doc.hosts) {
            val errs = ArrayList<String>()
            val warns = ArrayList<String>()
            if (!common(r, errs, warns)) continue
            val hs = r.hosts.map { it.trim().trimEnd('.').lowercase() }
            hs.filterNot { HOST.matches(it) || WILDCARD.matches(it) }
                .forEach { errs += "hosts 里的 $it 不是主机名（不带 scheme 与路径；通配只能写成 name.*）" }
            if (r.pathPrefix != null && !r.pathPrefix.startsWith("/")) errs += "pathPrefix 要以 / 开头"
            flush(r.id, errs, warns)
            if (errs.isEmpty()) hosts += Host(r.copy(hosts = hs), hs)
        }

        // ── 口令规则 ──
        val passcodes = ArrayList<Passcode>()
        for (r in doc.passcodes) {
            val errs = ArrayList<String>()
            val warns = ArrayList<String>()
            if (!common(r, errs, warns)) continue
            val re = RegexGuard.compile(r.regex)
            if (re == null) errs += "正则编译失败"
            else if (RegexGuard.isRisky(r.regex)) errs += "正则有回溯失控风险（嵌套无界量词或 3 个以上 .* / .+）"
            val groups = re?.let { runCatching { it.toPattern().matcher("").groupCount() }.getOrNull() } ?: 0

            fun template(field: String, raw: String, builders: Map<String, *>, needScheme: Boolean): RuleTemplate? {
                val t = RuleTemplate.parse(raw)
                if (t == null) {
                    errs += "$field 模板写错了：$raw"
                    return null
                }
                if (t.maxGroup > groups) errs += "$field 引用了第 ${t.maxGroup} 组，正则只有 $groups 组"
                val b = t.builderName
                if (b != null) {
                    if (b !in builders) errs += "$field 的构造器 @$b 不认识"
                } else if (needScheme) {
                    val scheme = t.literalScheme()
                    if (scheme == null || scheme !in RuleCapabilities.TARGET_SCHEMES) {
                        errs += "$field 的 scheme ${scheme ?: "（缺失或含占位符）"} 不在白名单"
                    }
                }
                return t
            }

            val target = template("target", r.target, RuleCapabilities.TARGET_BUILDERS, needScheme = true)
            val core = r.core?.let { template("core", it, RuleCapabilities.CORE_BUILDERS, needScheme = false) }
            val alts = r.alts.mapIndexedNotNull { i, a ->
                template("alts[$i]", a, RuleCapabilities.ALT_BUILDERS, needScheme = true)
            }
            // 不认识的判据不能当「没有」：那会让规则比作者写的更宽，宁可整条不用
            val accept = r.accept?.let { name ->
                RuleCapabilities.ACCEPT[name] ?: run {
                    errs += "判据 $name 不认识"
                    null
                }
            }
            flush(r.id, errs, warns)
            if (errs.isEmpty() && re != null && target != null) {
                passcodes += Passcode(r, re, target, core, alts, accept)
            }
        }

        return Checked(doc.version, platforms, hosts, passcodes, issues)
    }
}
