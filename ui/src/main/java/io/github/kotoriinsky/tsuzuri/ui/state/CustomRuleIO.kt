package io.github.kotoriinsky.tsuzuri.ui.state

import io.github.kotoriinsky.tsuzuri.core.PasscodeCustomRules
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.core.RegexGuard
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义规则的 JSON 导入/导出。
 *
 * ## 为什么这一层在 `:ui` 而不是 `:core`
 *
 * `:core` 是纯 JVM 模块、没有 `org.json`（见 `PasscodeIsland.kt` 的注释）。
 * 磁盘上的规则**存储格式**是 core 的行式编码（[PasscodeCustomRules.encode]），
 * JSON 只在「文件 ↔ 内存规则」这两个边界出现 —— 那正是设置界面（导入导出）的活。
 *
 * ## 支持的格式
 *
 *  - **本模块导出格式**（`format: "tsuzuri-rules"`）：全字段，导出再导入不丢信息；
 *  - **HyperCopy 规则文件**（github.com/1812z/HyperCopy 的 rules.json）：
 *    单对象 / 对象数组 / `rules` 数组三种摆放都认。字段映射见 [mapHyperCopy]；
 *    它的 `extractionRegexes` / `triggerRegexes` 是高级功能，映射不了 ——
 *    **跳过该条并计数**，不静默丢弃也不静默降级。
 */
object CustomRuleIO {

    const val MIME = "application/json"

    /** 导出格式标识。 */
    private const val FORMAT = "tsuzuri-rules"

    /** 单条导入结果没有错误通道，整批结果统一在这：成功几条、跳过几条、整体失败的原因。 */
    data class ImportResult(
        val rules: List<PasscodeCustomRules.Rule>,
        /** 没映射上 / 正则编不过 / 含分隔符的条数。 */
        val skipped: Int,
        /** 整体失败（连格式都不对）时的一句话原因；null = 有产出。 */
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    // ── 导出 ─────────────────────────────────────────────────────────

    /** 导出为 JSON。空列表也能导出（空 rules 数组 = 有效的备份）。 */
    fun export(rules: List<PasscodeCustomRules.Rule>): String {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("label", r.label)
                    .put("regex", r.regex)
                    .put("anchored", r.anchored)
                    .put("target", r.target)
                    .put("platformName", r.platformName.orEmpty())
                    .put("platformPkg", r.platformPkg.orEmpty())
                    .put("accent", r.accent.orEmpty())
                    .put("coreGroup", r.coreGroup ?: 0)
                    .put("category", r.category.wireName)
                    .put("enabled", r.enabled),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("rules", arr)
            .toString(2)
    }

    // ── 导入 ─────────────────────────────────────────────────────────

    /**
     * 从一段文本导入。**自动识别**三种摆放：本模块格式 / HyperCopy 单对象 /
     * HyperCopy 数组（含 `rules` 包一层）。每条独立容错：坏条跳过计数，好条照常导入。
     */
    fun import(text: String): ImportResult {
        val t = text.trim()
        if (t.isEmpty()) return ImportResult(emptyList(), 0, error = "empty")
        val out = ArrayList<PasscodeCustomRules.Rule>()
        var skipped = 0

        fun add(rule: PasscodeCustomRules.Rule?): Boolean {
            if (rule == null) return false
            // encode 校验（分隔符/换行/超量）与正则编译都在这条路上兜住；
            // 明显会回溯失控的写法也算坏条（匹配有超时兜底，但别让它进来）
            if (RegexGuard.compile(rule.regex) == null || RegexGuard.isRisky(rule.regex)) return false
            out.add(rule)
            return true
        }

        val root: Any? = try {
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        } catch (_: Throwable) {
            null
        }
        if (root == null) return ImportResult(emptyList(), 0, error = "invalid")

        when (root) {
            is JSONArray -> for (i in 0 until root.length()) {
                val o = root.optJSONObject(i) ?: continue
                val rule = if (o.has("matchRegex")) mapHyperCopy(o) else mapTsuzuri(o)
                if (!add(rule)) skipped++
            }
            is JSONObject -> {
                val items = root.optJSONArray("rules")
                when {
                    // 本模块格式（或包了一层 rules 的混合格式）
                    items != null -> for (i in 0 until items.length()) {
                        val o = items.optJSONObject(i) ?: continue
                        if (!add(if (o.has("matchRegex")) mapHyperCopy(o) else mapTsuzuri(o))) skipped++
                    }
                    // HyperCopy 单规则文件
                    root.has("matchRegex") -> if (!add(mapHyperCopy(root))) skipped++
                    // 本模块单条
                    root.has("regex") -> if (!add(mapTsuzuri(root))) skipped++
                    else -> return ImportResult(emptyList(), 0, error = "invalid")
                }
            }
        }
        if (out.isEmpty() && skipped == 0) {
            return ImportResult(emptyList(), 0, error = "invalid")
        }
        return ImportResult(out.take(PasscodeCustomRules.MAX_RULES), skipped)
    }

    /** 本模块导出格式的一条 → 规则。字段缺失按空处理。 */
    private fun mapTsuzuri(o: JSONObject): PasscodeCustomRules.Rule? {
        val regex = o.optString("regex", "").trim()
        val target = o.optString("target", "").trim()
        if (regex.isEmpty() || target.isEmpty()) return null
        return PasscodeCustomRules.Rule(
            id = o.optString("id").ifEmpty { autoId(regex) },
            label = o.optString("label").ifEmpty { "自定义" },
            regex = regex,
            anchored = o.optBoolean("anchored", false),
            target = target,
            platformName = o.optString("platformName").ifEmpty { null },
            platformPkg = o.optString("platformPkg").ifEmpty { null },
            accent = o.optString("accent").ifEmpty { null },
            coreGroup = o.optInt("coreGroup", 0).takeIf { it in 1..9 },
            category = PasscodeIsland.ContentCategory.fromWire(o.optString("category"))
                ?: PasscodeIsland.ContentCategory.IMAGE_TEXT,
            enabled = o.optBoolean("enabled", true),
        )
    }

    /**
     * HyperCopy 一条 → 规则。字段见其 rules.json：
     * `matchRegex`（命中）+ `target{template, packageName}`。
     *
     * 模板变量映射（它的运行时变量只有它能算的都丢弃，能静态换的都换）：
     *  - `${p1}`..`${p9}` → `$1`..`$9`（matchRegex 的捕获组）；
     *  - `${input}` / `${url:input}` → `$0`（整段命中文本）；
     *  - 其余 `${…}`（`${r1}` 重定向这类）→ 丢弃。
     * `extractionRegexes` / `triggerRegexes` 不映射 —— 该条整体跳过比「跳转目标变了」
     * 更安全：丢字段意味着跳转语义已经和原作者写的不是一回事。
     */
    private fun mapHyperCopy(o: JSONObject): PasscodeCustomRules.Rule? {
        if (o.has("extractionRegexes") || o.has("triggerRegexes")) return null
        val regex = o.optString("matchRegex", "").trim()
        if (regex.isEmpty()) return null
        val target = o.optJSONObject("target") ?: return null
        val template = target.optString("template", "")
        if (template.isEmpty()) return null
        val mapped = template
            .replace(Regex("""\$\{p([1-9])\}""")) { m -> "$" + m.groupValues[1] }
            // Regex.replace 的字符串重载会把 `$0` 当成「完整匹配组」；这里要写入
            // PasscodeCustomRules 的 `$0` 模板，所以必须用 lambda 产出字面量。
            .replace(Regex("""\$\{(?:url:)?input\}""")) { "\$0" }
            .replace(Regex("""\$\{[^}]*\}"""), "")
        if (mapped.isBlank()) return null
        val pkg = target.optString("packageName", "").ifEmpty { null }
        return PasscodeCustomRules.Rule(
            id = autoId(regex),
            label = o.optString("label").ifEmpty { pkg?.substringAfterLast('.') ?: "自定义" },
            regex = regex,
            anchored = false, // HyperCopy 的 matchRegex 是全文搜
            target = mapped,
            platformName = null, // 它没有平台名概念，回落 label
            platformPkg = pkg,
            accent = null,
            coreGroup = null,
            category = PasscodeIsland.ContentCategory.IMAGE_TEXT,
            enabled = o.optBoolean("enabled", true),
        )
    }

    private fun autoId(seed: String): String =
        "c" + Integer.toHexString(seed.hashCode()).padStart(8, '0')
}
