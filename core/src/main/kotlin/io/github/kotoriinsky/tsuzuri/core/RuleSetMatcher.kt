package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.RuleSet.Issue

/**
 * 按格式 v2 的规则匹配一段复制文本。
 *
 * 顺序与 `PasscodeRules.match` 一致：先找链接，链接命中域名规则就返回（链接独占）；
 * 否则按文件里的顺序逐条试口令规则，第一条通过的胜出。单条正则限时 [timeoutMs]，超时跳过并回调。
 *
 * 这里只覆盖**表格部分**能表达的规则；特例（QQ 音乐、闲鱼短链、`contentCore` 抽号……）仍在
 * `PasscodeRules` 的代码里，第 3 步迁移时由它把两部分接起来。
 */
class RuleSetMatcher(private val set: RuleSetCheck.Checked, private val timeoutMs: Long = 50) {

    data class Hit(
        val ruleId: String,
        val label: String,
        val platform: RuleSet.Platform?,
        val target: String,
        val core: String? = null,
        val alts: List<String> = emptyList(),
    )

    fun match(text: String, numericTracking: Boolean = false, onTimeout: (String) -> Unit = {}): Hit? {
        matchUrl(text)?.let { return it }
        val t = text.trim()
        for (p in set.passcodes) {
            if (p.rule.requiresContext && !numericTracking) continue
            try {
                val candidates = if (p.rule.anchored) {
                    sequenceOf(RegexGuard.match(p.regex, t, anchored = true, timeoutMs = timeoutMs)).filterNotNull()
                } else {
                    RegexGuard.findAll(p.regex, t, timeoutMs)
                }
                for (m in candidates) {
                    val value = RuleTemplate.defaultValue(m)
                    if (p.accept != null && !p.accept.invoke(t, value)) continue
                    // 门控规则还要同文出现上下文词（「单号」「快递」…），与 PasscodeRules 一致
                    if (p.rule.requiresContext && PasscodeRules.CONTEXT_WORDS.none { it in t }) break
                    val target = p.target.expand(t, m, RuleCapabilities.TARGET_BUILDERS) ?: continue
                    return Hit(
                        ruleId = p.rule.id,
                        label = p.rule.label,
                        platform = p.rule.platform?.let(set.platforms::get),
                        target = target,
                        core = p.core?.expand(t, m, RuleCapabilities.CORE_BUILDERS)?.ifEmpty { null },
                        alts = p.alts.mapNotNull { it.expand(t, m, RuleCapabilities.ALT_BUILDERS) },
                    )
                }
            } catch (_: RegexGuard.Timeout) {
                onTimeout(p.rule.id)
            }
        }
        return null
    }

    private fun matchUrl(text: String): Hit? {
        val url = URL.find(text)?.value?.trimEnd('.', ',', ')', ';', ':') ?: return null
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val path = uri.rawPath.orEmpty()
        for (h in set.hosts) {
            if (!RuleSet.hostRuleMatches(h.rule, host, path)) continue
            return Hit(h.rule.id, h.rule.label, set.platforms[h.rule.platform], url)
        }
        return null
    }

    private companion object {
        /** 与 `PasscodeRules` 的链接正则同一字符集。 */
        val URL = Regex("""https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%\-]+""")
    }
}

/**
 * 样例执行器（Q14）：每条规则的样例就是它自己的测试。
 *
 *  - `hit`：整份规则按真实顺序匹配后，必须由**这条**规则命中；被别的规则抢先 = 冲突；
 *    样例写了 `target` / `core` 就要逐字相等；
 *  - `miss`：这条规则不能命中（被别的规则命中不算失败）。
 *
 * 需要上下文门控的规则按门控打开来跑。返回的每一条都是错误。
 */
object RuleSetSamples {

    fun run(set: RuleSetCheck.Checked): List<Issue> {
        val matcher = RuleSetMatcher(set)
        val out = ArrayList<Issue>()
        val rules: List<Pair<RuleSet.Rule, Boolean>> =
            set.hosts.map { it.rule to false } + set.passcodes.map { it.rule to it.rule.requiresContext }
        for ((rule, gated) in rules) {
            for (s in rule.samples.hit) {
                val hit = matcher.match(s.text, numericTracking = gated)
                when {
                    hit == null -> out += Issue(rule.id, "样例没有命中：${s.text}")
                    hit.ruleId != rule.id -> out += Issue(rule.id, "样例被 ${hit.ruleId} 抢先命中（规则冲突）：${s.text}")
                    s.target != null && hit.target != s.target ->
                        out += Issue(rule.id, "样例跳转目标不符：期望 ${s.target}，实际 ${hit.target}")
                    s.core != null && hit.core != s.core ->
                        out += Issue(rule.id, "样例核心内容不符：期望 ${s.core}，实际 ${hit.core}")
                }
            }
            for (s in rule.samples.miss) {
                if (matcher.match(s, numericTracking = gated)?.ruleId == rule.id) {
                    out += Issue(rule.id, "不该命中的样例命中了：$s")
                }
            }
        }
        return out
    }
}
