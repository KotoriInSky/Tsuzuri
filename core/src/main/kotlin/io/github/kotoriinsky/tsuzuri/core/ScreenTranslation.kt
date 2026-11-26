package io.github.kotoriinsky.tsuzuri.core

/**
 * 全局识别窗内翻译的纯本地语言判定与展示工具（零 Android 依赖）。
 *
 * 语种代码对齐小米 AIVS 翻译引擎（`zh` / `en` / `jp` / `ko` / `ru` / `fr` / `de` / `es`）。
 */
object ScreenTranslation {

    data class LangPair(val src: String, val dest: String) {
        val label: String get() = "${langName(src)} → ${langName(dest)}"
    }

    /** 允许在译文卡片内一键切换的常用目标语言。 */
    val TARGET_LANGS: List<String> = listOf("zh", "en", "jp", "ko")

    fun langName(code: String): String = when (code.lowercase()) {
        "zh", "zh-cn" -> "中文"
        "en", "en-us" -> "英文"
        "jp", "ja" -> "日文"
        "ko" -> "韩文"
        "ru" -> "俄文"
        "fr" -> "法文"
        "de" -> "德文"
        "es" -> "西文"
        else -> code.uppercase()
    }

    /**
     * 根据文本字符构成自动推断源语言与目标语言：
     *  - 含日文假名 → `jp -> zh`
     *  - 含韩文谚文 → `ko -> zh`
     *  - 含西里尔字母 → `ru -> zh`
     *  - 汉字占有效字符 ≥ 35% → `zh -> en`
     *  - 其余外文 → `en -> zh`
     *
     * 若指定了 [overrideDest]（用户在卡片里手动切目标语），则保持推断出的源语（若同语则自动翻转）。
     */
    fun guessLangPair(text: String, overrideDest: String? = null): LangPair {
        var han = 0
        var kana = 0
        var hangul = 0
        var cyrillic = 0
        var latin = 0
        for (ch in text) {
            val cp = ch.code
            when {
                cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF -> kana++
                cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF -> hangul++
                cp in 0x0400..0x04FF -> cyrillic++
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF -> han++
                (ch in 'A'..'Z') || (ch in 'a'..'z') || cp in 0x00C0..0x024F -> latin++
            }
        }
        val total = han + kana + hangul + cyrillic + latin
        val src = when {
            kana >= 2 || (kana >= 1 && kana * 5 >= total) -> "jp"
            hangul >= 2 || (hangul >= 1 && hangul * 5 >= total) -> "ko"
            cyrillic >= 2 || (cyrillic >= 1 && cyrillic * 4 >= total) -> "ru"
            total > 0 && han * 100 / total >= 35 -> "zh"
            else -> "en"
        }
        val dest = when {
            overrideDest != null && overrideDest != src -> overrideDest
            src == "zh" -> "en"
            else -> "zh"
        }
        return LangPair(src, dest)
    }

    /** 清理选中文本中的多余控制字符并截断到服务上限（2000 字），保证请求轻量快速。 */
    fun prepareText(raw: String, maxLen: Int = 2000): String =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .trim()
            .take(maxLen)
}
