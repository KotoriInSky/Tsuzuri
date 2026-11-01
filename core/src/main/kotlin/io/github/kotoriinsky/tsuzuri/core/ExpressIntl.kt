/*
 * 单号形态与校验位算法移植自 jkeen/tracking_number_data（couriers 目录下各 JSON），MIT 许可：
 *
 * Copyright (c) 2017 Jeff Keen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.github.kotoriinsky.tsuzuri.core

/**
 * 国际快递单号：形态 + 校验位（2026-10-22，来源见文件头与 THIRD-PARTY.md）。
 *
 * 原数据的正则允许数字之间夹空格（打印面单的抄写习惯）；剪贴板里的单号几乎都是连续的，
 * 这里只认连续形态 —— 夹空格的写法与普通数字串太难区分。
 *
 * 两档：
 *  - **自带前缀**（UPS `1Z`、亚马逊 `TB?`、云途 `YT`+16、DHL 件号 `J..`）：前缀区分度够，校验通过即认；
 *  - **纯数字**（FedEx / DHL / USPS）：与国内订单号同形，必须同一段文字里出现**承运商名**，
 *    且校验位通过才认。没有承运商名就不认 —— 宁可漏，不可吵。
 */
object ExpressIntl {

    /** 万国邮联 S10：两字母业务码 + 8 位序号 + 1 位校验 + 两字母国别码。 */
    fun isValidS10(number: String): Boolean {
        if (number.length != 13) return false
        val serial = number.substring(2, 10)
        if (!serial.all(Char::isDigit) || !number[10].isDigit()) return false
        val weights = intArrayOf(8, 6, 4, 2, 3, 5, 9, 7)
        val sum = serial.indices.sumOf { (serial[it] - '0') * weights[it] }
        val check = when (val c = 11 - sum % 11) {
            10 -> 0
            11 -> 5
            else -> c
        }
        return check == number[10] - '0'
    }

    /** S10 国别码 → 承运商（原数据 s10.json 的 `Courier` 查表，只收中文用户常见的几家）。 */
    fun s10Carrier(number: String): String? = when (number.takeLast(2)) {
        "CN" -> "中国邮政"
        "HK" -> "香港邮政"
        "TW" -> "中华邮政"
        "MO" -> "澳门邮政"
        "JP" -> "日本邮便"
        "US" -> "美国邮政"
        else -> null
    }

    /**
     * 原数据的 `mod10`：字母按 `(ASCII - 3) % 10` 折成数字；**0 起的奇数位**乘 [odds]、偶数位乘 [evens]；
     * 校验位 = 10 - 和 % 10（和为 10 的倍数时校验位为 0）。
     */
    fun mod10(serial: String, check: Int, evens: Int, odds: Int): Boolean {
        var total = 0
        for ((i, c) in serial.withIndex()) {
            val x = if (c.isDigit()) c - '0' else (c.code - 3) % 10
            total += x * if (i % 2 == 1) odds else evens
        }
        val m = total % 10
        return (if (m == 0) 0 else 10 - m) == check
    }

    /** 原数据的 `mod7`：序号 % 7 == 校验位。 */
    fun mod7(serial: String, check: Int): Boolean =
        serial.toBigIntegerOrNull()?.mod(7.toBigInteger())?.toInt() == check

    /** 原数据的 `sum_product_with_weightings_and_modulo`：Σ(位 × 权) % m1 % m2 == 校验位。 */
    fun weighted(serial: String, check: Int, weights: IntArray, m1: Int, m2: Int): Boolean {
        if (serial.length != weights.size) return false
        val sum = serial.indices.sumOf { (serial[it] - '0') * weights[it] }
        return sum % m1 % m2 == check
    }

    /** 自带前缀的国际单号（不需要上下文）。命中返回承运商名。 */
    fun prefixedCarrier(number: String): String? {
        val n = number.uppercase()
        return when {
            UPS.matches(n) -> "UPS".takeIf { mod10(n.substring(2, 17), n[17] - '0', evens = 1, odds = 2) }
            AMAZON.matches(n) -> "亚马逊物流"
            YUNEXPRESS.matches(n) -> "云途物流"
            DHL_PIECE.matches(n) -> "DHL"
            else -> null
        }
    }

    /**
     * 纯数字国际单号：**同文出现承运商名**且校验通过才返回承运商名。
     * [text] 是剪贴板整段文本（找承运商名用）。
     */
    fun numericCarrier(number: String, text: String): String? {
        val t = text.lowercase()
        val n = number
        if (FEDEX_WORDS.any { it in t }) {
            if (n.length == 12 && weighted(n.take(11), n[11] - '0', FEDEX12_WEIGHTS, 11, 10)) return "FedEx"
            if (n.length == 15 && mod10(n.take(14), n[14] - '0', evens = 1, odds = 3)) return "FedEx"
        }
        if (DHL_WORDS.any { it in t } && n.length in 10..11 && mod7(n.dropLast(1), n.last() - '0')) return "DHL"
        if (USPS_WORDS.any { it in t } && (n.length == 20 || n.length == 22) &&
            mod10(n.dropLast(1), n.last() - '0', evens = 3, odds = 1)
        ) return "美国邮政"
        return null
    }

    private val UPS = Regex("""1Z[A-Z0-9]{15}[0-9]""")
    private val AMAZON = Regex("""TB[ACM]\d{12}""")
    private val YUNEXPRESS = Regex("""YT\d{16}""")
    // `JD…` 是京东物流（PasscodeRules 的 express_prefixed 先认），DHL 件号排除它
    private val DHL_PIECE = Regex("""J(?!D)[A-Z]{2,3}\d{9,10}""")

    /** 规则表用的整体形态：任一自带前缀的国际单号（UPS 大小写都认，校验在 [prefixedCarrier]）。 */
    val PREFIXED = Regex("""\b(1[Zz][A-Za-z0-9]{15}[0-9]|TB[ACM]\d{12}|YT\d{16}|J(?!D)[A-Z]{2,3}\d{9,10})\b""")

    /** 规则表用的整体形态：10~22 位纯数字（承运商与校验在 [numericCarrier]）。 */
    val NUMERIC = Regex("""(?<!\d)(\d{10,22})(?!\d)""")

    private val FEDEX12_WEIGHTS = intArrayOf(3, 1, 7, 3, 1, 7, 3, 1, 7, 3, 1)
    private val FEDEX_WORDS = listOf("fedex", "联邦快递", "联邦")
    private val DHL_WORDS = listOf("dhl", "敦豪")
    private val USPS_WORDS = listOf("usps", "美国邮政")
}
