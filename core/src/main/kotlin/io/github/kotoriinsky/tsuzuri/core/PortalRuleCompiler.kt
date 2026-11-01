package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P

object PortalRuleCompiler {
    private val receiver = setOf("field", "key", "last", "json", "pattern", "split", "index", "mod",
        "transform", "filter", "matches", "size", "toString")
    private data class Expression(val fields: List<String>, val steps: List<P.Step> = emptyList(),
        val codec: P.Codec = P.Codec.TEXT, val item: String)

    /** 源端只执行字段；已验证快照之后的操作才进入接收端。失败不输出半条规则。 */
    fun compile(rule: P.Rule, bindings: Map<String, P.Binding>, snapshots: Map<List<String>, P.Codec> = emptyMap(),
        wireFields: Map<List<String>, List<String>> = emptyMap()): P.Compilation {
        val issues = ArrayList<P.Issue>()
        fun reject(operation: String, reason: String): Nothing? {
            issues.add(P.Issue(rule.id, operation, reason))
            return null
        }
        if (rule.importIssues.isNotEmpty()) return P.Compilation(null, rule.importIssues)
        if (wireFields.keys.any { it !in snapshots }) {
            reject("snapshot", "等价传输字段缺少已核验的快照声明")
            return P.Compilation(null, issues)
        }
        if (rule.batch) { reject("all_images", "当前项采集不执行批量列表"); return P.Compilation(null, issues) }
        if (rule.outputs.isEmpty()) { reject("target", "缺少媒体输出"); return P.Compilation(null, issues) }
        val nodes = (rule.nodes + rule.target).associateBy { it.name }
        if (nodes.size != rule.nodes.size + 1) {
            reject("objects", "对象别名重复"); return P.Compilation(null, issues)
        }
        val resolved = HashMap<String, Expression>()
        val visiting = HashSet<String>()
        val variables = LinkedHashMap<String, P.Read>()
        lateinit var resolve: (String) -> Expression?
        fun read(expression: Expression): P.Read? = runCatching {
            val codec = snapshots[expression.fields] ?: expression.codec
            P.Read(PortalRuleSyntax.fields(wireFields[expression.fields] ?: expression.fields,
                if (codec == P.Codec.BITMAP) "Object" else "String"),
                expression.steps, codec)
        }.getOrElse { reject("field", "字段链不合法或没有来源字段") }
        fun transformer(name: String, chain: Set<String> = emptySet()): Boolean {
            if (name in chain || chain.size >= 16) { reject("transform", "转换器依赖循环或超出预算"); return false }
            val steps = rule.transformers[name] ?: run { reject("transform", "缺少转换器 $name"); return false }
            if (steps.size > 128) { reject("transform", "转换器操作数量超出预算"); return false }
            for (step in steps) {
                if (step.name !in receiver) { reject("transform", "转换器包含来源操作"); return false }
                if (step.name == "transform" && !transformer(step.argument, chain + name)) return false
                if (step.name in setOf("index", "mod") && step.argument.startsWith('$')) {
                    val variable = resolve(step.argument) ?: return false
                    variables[step.argument] = read(variable) ?: return false
                }
            }
            return true
        }
        fun apply(start: Expression, steps: List<P.Step>): Expression? {
            if (steps.size > 128) return reject("limit", "操作数量超出预算")
            var out = start
            for (step in steps) {
                if (step.name == "field" && out.steps.isEmpty()) { out = out.copy(fields = out.fields + step.argument); continue }
                if (step.name !in receiver) return reject(step.name, "需要已核验的来源字段绑定，不能删除此操作")
                val codec = snapshots[out.fields] ?: out.codec.takeIf { out.steps.isNotEmpty() }
                    ?: return reject(step.name, "此来源对象没有已核验的可传输快照")
                if (step.name in setOf("index", "mod") && step.argument.startsWith('$')) {
                    val variable = resolve(step.argument) ?: return null
                    variables[step.argument] = read(variable) ?: return null
                }
                if (step.name == "transform" && !transformer(step.argument)) return null
                out = out.copy(steps = out.steps + step, codec = codec)
            }
            return out
        }
        resolve = resolver@{ name ->
            resolved[name]?.let { return@resolver it }
            val node = nodes[name]
            bindings[name]?.let { binding ->
                if (node != null && node.requiredType.isNotEmpty() && node.requiredType != binding.verifiedType)
                    return@resolver reject("cls", "对象类型约束缺少等价绑定证明")
                if (node?.visible == true && !binding.verifiedVisible)
                    return@resolver reject("visible", "可见性约束缺少等价绑定证明")
                val expression = Expression(binding.fields, item = binding.item)
                resolved[name] = expression
                return@resolver expression
            }
            if (name == "\$visibleView") return@resolver Expression(emptyList(), item = rule.id)
            if (node == null) return@resolver reject(name, "未定义根对象")
            if (node.requiredType.isNotEmpty() || node.visible)
                return@resolver reject("constraint", "对象类型或可见性约束需要已核验绑定")
            if (!visiting.add(name)) return@resolver reject(name, "对象依赖循环")
            val expression = try {
                if (node.viewSearch) reject("findView", "需要来源控件绑定")
                else resolve(node.root)?.let { apply(it, node.steps) }
            } finally { visiting.remove(name) }
            expression?.also { resolved[name] = it }
        }
        val target = resolve(rule.target.name) ?: return P.Compilation(null, issues)
        val reads = rule.outputs.mapNotNull { output ->
            apply(target, output.steps)?.let(::read)?.let { output.kind to it }
        }
        // 一项无法等价转换时整条停用，避免只留下封面或错误的动态部分。
        val plan = if (issues.isEmpty() && reads.size == rule.outputs.size)
            P.Plan(rule, target.item, reads, variables) else null
        return P.Compilation(plan, issues)
    }
}
