package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram.Step
import java.util.regex.Pattern

object PortalRuleValues {
    fun evaluate(value: Any?, steps: List<Step>, variables: Map<String, Any?> = emptyMap(),
        transformers: Map<String, List<Step>> = emptyMap(), decodeJson: (String) -> Any? = { null }): Any? =
        runCatching { walk(value, steps, variables, transformers, decodeJson, 0) }.getOrNull()

    private fun walk(value: Any?, steps: List<Step>, variables: Map<String, Any?>,
        transformers: Map<String, List<Step>>, decodeJson: (String) -> Any?, depth: Int): Any? {
        if (depth > 16 || steps.size > 128) return null
        var current = value
        fun pattern(raw: String) = raw.takeIf { it.length <= 512 }?.let(Pattern::compile)
        for (step in steps) {
            val item = current ?: return null
            if (item is List<*> && item.size > 1024) return null
            current = when (step.name) {
                "field", "key" -> (item as? Map<*, *>)?.get(step.argument)
                "json" -> (item as? String)?.takeIf { it.length <= 256 * 1024 }?.let(decodeJson)
                "last" -> (item as? List<*>)?.lastOrNull()
                "index" -> {
                    val index = if (step.argument.startsWith('$')) (variables[step.argument] as? Number)?.toInt()
                        else step.argument.toIntOrNull()
                    index?.takeIf { it >= 0 }?.let { (item as? List<*>)?.getOrNull(it) }
                }
                "pattern" -> (item as? List<*>)?.filterIsInstance<String>()?.firstOrNull {
                    it.length <= 4096 && pattern(step.argument)?.matcher(it)?.find() == true
                }
                "split" -> (item as? String)?.takeIf { it.length <= 256 * 1024 }
                    ?.let { pattern(step.argument)?.split(it, 0)?.toList() }
                "mod" -> {
                    val divisor = (variables[step.argument] as? Number)?.toInt()
                    if (item is Number && divisor != null && divisor != 0) item.toInt() % divisor else null
                }
                "transform" -> {
                    val operations = transformers[step.argument] ?: return null
                    (item as? List<*>)?.map { walk(it, operations, variables, transformers, decodeJson, depth + 1) }
                }
                "filter" -> (item as? List<*>)?.indexOfFirst { it == true }
                "matches" -> primitive(item)?.takeIf { it.length <= 4096 }
                    ?.let { pattern(step.argument)?.matcher(it)?.matches() }
                "size" -> when (item) { is Collection<*> -> item.size; is Map<*, *> -> item.size; else -> null }
                "toString" -> primitive(item)
                else -> null
            }
        }
        return current
    }
    private fun primitive(value: Any): String? = when (value) {
        is CharSequence, is Number, is Boolean -> value.toString()
        else -> null
    }
}
