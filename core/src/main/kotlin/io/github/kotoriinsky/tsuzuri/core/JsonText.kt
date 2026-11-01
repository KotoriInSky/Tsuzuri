package io.github.kotoriinsky.tsuzuri.core

/**
 * JSON 字符串里的转义还原（`\uXXXX`、`\/`、`\"`、`\\`、`\n`）。
 *
 * 抖音分享页把 URL 写成 `https:\u002F\u002Fp3-sign.douyinpic.com\u002F...`；
 * 快手那边也有转义。两处都要用，所以抽出来共用（[KuaishouShare] / [DouyinShare]）。
 */
internal object JsonText {

    private val UNICODE = Regex("""\\u([0-9A-Fa-f]{4})""")

    fun unescape(raw: String): String = raw
        .replace(UNICODE) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\n", " ")
        .replace("\\t", " ")
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
