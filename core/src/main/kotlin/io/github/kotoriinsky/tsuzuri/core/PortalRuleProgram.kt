package io.github.kotoriinsky.tsuzuri.core

/** 独立规则表示；不包含厂商资源，不在接收端反射来源应用对象。 */
object PortalRuleProgram {
    enum class Kind { PHOTO, LIVE, VIDEO, COVER, TYPE, BITMAP }
    enum class Codec { TEXT, JSON, STRING_LIST, NUMBER, BITMAP }
    data class Step(val name: String, val argument: String = "")
    data class Node(val name: String, val root: String, val steps: List<Step>, val viewSearch: Boolean = false,
        val visible: Boolean = false, val requiredType: String = "")
    data class Output(val kind: Kind, val steps: List<Step>)
    data class Rule(val id: String, val pkg: String, val pages: List<String>, val views: List<String>,
        val nodes: List<Node>, val target: Node, val outputs: List<Output>, val batch: Boolean = false,
        val transformers: Map<String, List<Step>> = emptyMap(), val importIssues: List<Issue> = emptyList())
    /** 绑定必须来自已核验的当前项；同一规则的所有媒体共用这个锚点。 */
    data class Binding(val fields: List<String>, val item: String, val verifiedType: String = "",
        val verifiedVisible: Boolean = false)
    data class Read(val key: String, val steps: List<Step>, val codec: Codec = Codec.TEXT)
    data class Plan(val rule: Rule, val item: String, val reads: List<Pair<Kind, Read>>,
        val variables: Map<String, Read> = emptyMap()) {
        val fields get() = (reads.map { it.second.key } + variables.values.map { it.key }).distinct()
    }
    data class Issue(val rule: String, val operation: String, val reason: String)
    data class Compilation(val plan: Plan?, val issues: List<Issue>)
    data class ImportResult(val rules: List<Rule>, val issues: List<Issue>)
}
