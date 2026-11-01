package io.github.kotoriinsky.tsuzuri.core

object PortalRuleSyntax {
    private val fieldName = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    fun parse(raw: String): PortalRuleProgram.Step {
        fun step(name: String, argument: String = "") = PortalRuleProgram.Step(name, argument)
        return when {
            raw == "[super]" -> step("super")
            raw == "[last]" -> step("last")
            raw == "[toJson]" -> step("json")
            raw.startsWith("[pattern]") -> step("pattern", raw.removePrefix("[pattern]"))
            raw.startsWith("[split]") -> step("split", raw.removePrefix("[split]"))
            raw.startsWith("[") && raw.endsWith("]") -> {
                val index = raw.substring(1, raw.lastIndex)
                if (index.toIntOrNull() != null || index.matches(Regex("\\$[A-Za-z0-9_]+"))) step("index", index)
                else step("unknown", raw)
            }
            raw.startsWith("{") && '}' in raw -> {
                val end = raw.indexOf('}')
                val name = raw.substring(1, end)
                val argument = raw.substring(end + 1)
                when (name) {
                    "_findAncestor" -> step("ancestor", argument)
                    "_findDescendant" -> step("descendant", argument)
                    "_findChildOfAncestor" -> step("ancestorChild", argument)
                    "_mod" -> step("mod", argument)
                    "_transformList" -> step("transform", argument)
                    "_filterList" -> if (argument.isEmpty()) step("filter") else step("unknown", raw)
                    "_regexMatch" -> step("matches", argument)
                    else -> if (!name.startsWith('_') && argument.isEmpty()) step("key", name) else step("unknown", raw)
                }
            }
            raw.endsWith("()") -> step(raw.removeSuffix("()"))
            '(' in raw -> step("method", raw)
            fieldName.matches(raw) -> step("field", raw)
            else -> step("unknown", raw)
        }
    }
    fun fields(names: List<String>, leaf: String = "String"): String {
        require(names.isNotEmpty() && names.all(fieldName::matches)) { "字段链含非法名称" }
        require(leaf in listOf("String", "Uri", "Object")) { "不能下发会触发 Drawable 转换的类型指令" }
        return names.mapIndexed { index, name -> "$name/${if (index == names.lastIndex) leaf else "Object"}" }.joinToString("/")
    }
}
