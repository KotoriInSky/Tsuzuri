package io.github.kotoriinsky.tsuzuri.core

/**
 * 从封面 / 站点图标里取一个强调色，给「打开」按钮与 highlightColor。
 *
 * 只在规则没有自己的平台色时用（通用网页、自定义规则）—— 否则所有普通网页的按钮都是同一个默认蓝。
 *
 * 做法刻意简单，不引 Palette / material-color-utilities：
 *  1. 丢掉透明、太灰、太暗、近白的像素（它们是背景和描边，不是「这张图的颜色」）；
 *  2. 按色相分 12 桶，每个像素按饱和度加权，取权重最大的桶的平均色；
 *  3. 有彩像素太少（灰度图、黑白 logo）返回 null，调用方用默认色；
 *  4. 按钮上是白字：与白色对比度不到 [MIN_CONTRAST] 就逐步压暗。
 *
 * 输入是已缩小的 ARGB 像素（展示侧先把位图缩到几十像素见方），纯整数运算，可离线单测。
 */
object CoverAccent {

    /** 白字在按钮底色上的最低对比度（WCAG 大号文字 / 图形口径）。 */
    const val MIN_CONTRAST = 3.0

    private const val BUCKETS = 12

    /** 有彩像素占比低于这个值就当灰度图。 */
    private const val MIN_CHROMATIC_RATIO = 0.05

    fun pick(argb: IntArray): String? {
        if (argb.isEmpty()) return null
        val weight = DoubleArray(BUCKETS)
        val sumR = DoubleArray(BUCKETS)
        val sumG = DoubleArray(BUCKETS)
        val sumB = DoubleArray(BUCKETS)
        var opaque = 0
        var chromatic = 0
        for (p in argb) {
            if ((p ushr 24) < 128) continue
            opaque++
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < 50) continue
            val s = (max - min).toDouble() / max
            if (s < 0.25) continue
            chromatic++
            val bucket = (hue(r, g, b, max, min) / (360.0 / BUCKETS)).toInt().coerceIn(0, BUCKETS - 1)
            weight[bucket] += s
            sumR[bucket] += r * s
            sumG[bucket] += g * s
            sumB[bucket] += b * s
        }
        if (opaque == 0 || chromatic.toDouble() / opaque < MIN_CHROMATIC_RATIO) return null
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        val w = weight[best]
        var r = sumR[best] / w
        var g = sumG[best] / w
        var b = sumB[best] / w
        repeat(30) {
            if (contrastWithWhite(r, g, b) >= MIN_CONTRAST) return hex(r, g, b)
            r *= 0.9
            g *= 0.9
            b *= 0.9
        }
        return hex(r, g, b)
    }

    private fun hue(r: Int, g: Int, b: Int, max: Int, min: Int): Double {
        val d = (max - min).toDouble()
        val h = when (max) {
            r -> ((g - b) / d).mod(6.0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return h * 60
    }

    /** WCAG 2 对比度：(L白 + 0.05) / (L + 0.05)。 */
    fun contrastWithWhite(r: Double, g: Double, b: Double): Double =
        1.05 / (luminance(r, g, b) + 0.05)

    private fun luminance(r: Double, g: Double, b: Double): Double {
        fun ch(c: Double): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
    }

    private fun hex(r: Double, g: Double, b: Double): String {
        fun c(v: Double) = Math.round(v).toInt().coerceIn(0, 255)
        return String.format(java.util.Locale.ROOT, "#%02X%02X%02X", c(r), c(g), c(b))
    }
}
