package io.github.kotoriinsky.tsuzuri.core

/**
 * 最小 JSON 解析器：`:core` 是纯 JVM 模块、没有 org.json（见 PasscodeIsland.kt 的说明），
 * 而网页里的 JSON-LD 与 oEmbed 清单都要在 core 里解析才能离线单测。
 *
 * 输出：对象 → `Map<String, Any?>`（保序）、数组 → `List<Any?>`、字符串、`Double`、`Boolean`、`null`。
 * 解析失败返回 null（[parse]）—— 网页里的 JSON-LD 经常是坏的，调用方按「没有」处理。
 * 只为读取设计：不做序列化，不保留数字精度以外的信息。
 */
object MiniJson {

    /** 输入上限：网页里内嵌的 JSON 偶尔很大，超过就不解析（防止慢页面拖住抓取线程）。 */
    private const val MAX_LEN = 1 shl 20

    fun parse(text: String): Any? {
        if (text.length > MAX_LEN) return null
        return try {
            val p = Parser(text)
            p.skipWs()
            val v = p.value()
            p.skipWs()
            if (p.pos != text.length) null else v
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 按路径取值：`"author.name"`、`"image.0.url"`；数字段对数组取下标。 */
    fun get(root: Any?, path: String): Any? {
        var cur = root
        for (seg in path.split('.')) {
            cur = when (cur) {
                is Map<*, *> -> cur[seg]
                is List<*> -> seg.toIntOrNull()?.let { cur.getOrNull(it) }
                else -> return null
            }
        }
        return cur
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            if (pos >= s.length) fail()
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail()
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (peek() != '"') fail()
                val k = str()
                skipWs()
                if (peek() != ':') fail()
                pos++
                out[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> fail()
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> fail()
                }
            }
        }

        private fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail()
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail()
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail()
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail()
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail()
            pos += word.length
            return v
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun fail(): Nothing = throw IllegalStateException("json")
    }
}
