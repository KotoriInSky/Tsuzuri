package io.github.kotoriinsky.tsuzuri.core

import kotlin.math.max
import kotlin.math.min

/**
 * 传送门识图「候选图校验」的纯函数部分（无 Android 依赖）。
 *
 * ## 为什么
 *
 * ROM 从来源 App 取到的候选位图（`viewBitmap`）经常不是屏幕上那张图：占位图、纯色图、
 * 透明图、尺寸对不上、或干脆是同一 View 早先加载的另一张图。小布识屏在消费侧拿截图的
 * 对应区域做校验，逐项筛掉不合格候选再决定用候选还是截图。
 * 这里把「逐项筛」写成纯函数：输入是**已经降采样**的 ARGB 像素数组 + 原始尺寸，
 * 输出结论 + 原因码，hook 侧只负责取像素和打日志。
 *
 * ## 输入约定
 *
 *  - `candidate` / `region`：降采样后的 ARGB_8888 像素（`Bitmap.getPixels` 的格式），
 *    行优先，长度 = 宽 × 高。降采样由调用方做（建议长边 ≤ [SAMPLE_SIDE]），这里不做缩放以外的假设。
 *  - `candidateW/H`：候选位图**原始**尺寸（降采样前），用于「过小」与宽高比判定。
 *  - `regionW/H`：选区在截图上的**原始**像素尺寸（可见区域）。
 *  - `region` 为 null 表示拿不到截图，只做候选自身的检查。
 */
object CandidateImageCheck {

    /** 原因码。日志里直接打 `name`。 */
    enum class Reason {
        /** 像素为空、尺寸非法或数组长度对不上。 */
        EMPTY,
        /** 原始短边过小（< [MIN_SIDE]），多半是图标/占位。 */
        TOO_SMALL,
        /** 透明像素占比过高，多半是还没解码完的占位或透明蒙层。 */
        MOSTLY_TRANSPARENT,
        /** 近纯色（灰度极差很小），多半是占位底色。 */
        SOLID_COLOR,
        /** 宽高比与选区可见区域差得太多，不是同一张图（或只是局部）。 */
        ASPECT_MISMATCH,
        /** 与截图对应区域的均值哈希差太大，内容不是屏幕上那张。 */
        NOT_SIMILAR,
    }

    /**
     * 结论。[reasons] 为空 = 合格；[hamming] 是均值哈希距离（-1 = 未计算），便于真机调参。
     */
    data class Verdict(val reasons: List<Reason>, val hamming: Int = -1, val transparentRatio: Double = 0.0) {
        val ok: Boolean get() = reasons.isEmpty()
        fun codes(): String = if (ok) "OK" else reasons.joinToString("|") { it.name }
    }

    /** 建议的降采样长边。32 足够算 8×8 均值哈希，且对 1080p 截图裁剪的缩放 < 1ms 量级。 */
    const val SAMPLE_SIDE = 32

    /** 原始短边下限（px）。低于它的候选在 520dpi 屏上 < 10dp，只可能是图标/占位。 */
    const val MIN_SIDE = 24

    /** alpha < [ALPHA_OPAQUE_MIN] 视为透明。 */
    const val ALPHA_OPAQUE_MIN = 16

    /** 透明像素占比上限。正常照片几乎为 0；带圆角/透明边的贴纸常在 10%–30%，留足余量。 */
    const val MAX_TRANSPARENT_RATIO = 0.6

    /**
     * 近纯色判定：不透明像素灰度的 P95 − P5 小于该值（0–255）。
     * 取分位数而不是 max−min，是为了抗占位图上的一两个噪点/图标角标。
     * 8 ≈ 3% 灰阶，正常内容图远大于此（真机待调参）。
     */
    const val SOLID_SPREAD_MAX = 8

    /**
     * 宽高比容差：`max(ar1/ar2, ar2/ar1)` 超过它判不符。
     * 选区（view_rect）可能被截图下缘/导航栏裁掉一截，或 View 用 centerCrop 显示，
     * 所以给得很宽：1.6 ≈ 3:4 对 16:9 时仍放行（≈1.78 拒绝）。真机待调参。
     */
    const val ASPECT_TOLERANCE = 1.6

    /**
     * 8×8 均值哈希的汉明距离上限（共 64 位）。业界常用经验值：≤10 视为同图、>20 视为不同图；
     * 这里候选与截图之间还隔着缩放、centerCrop 与 JPEG 压缩，取宽松的 22（真机待调参）。
     */
    const val MAX_HAMMING = 22

    /**
     * 截图区域本身近纯色时（灰度分位差 < 此值）不做相似度判定：均值哈希在平坦图上
     * 只反映噪声，会把合格候选误杀。
     */
    const val REGION_FLAT_SPREAD = 12

    private const val HASH_SIDE = 8

    fun check(
        candidate: IntArray?, sampleW: Int, sampleH: Int,
        candidateW: Int, candidateH: Int,
        region: IntArray? = null, regionSampleW: Int = 0, regionSampleH: Int = 0,
        regionW: Int = 0, regionH: Int = 0,
    ): Verdict {
        if (candidate == null || sampleW <= 0 || sampleH <= 0 || candidate.size < sampleW * sampleH ||
            candidateW <= 0 || candidateH <= 0
        ) return Verdict(listOf(Reason.EMPTY))

        val reasons = ArrayList<Reason>(3)
        if (min(candidateW, candidateH) < MIN_SIDE) reasons += Reason.TOO_SMALL

        val n = sampleW * sampleH
        var transparent = 0
        for (i in 0 until n) if ((candidate[i] ushr 24) < ALPHA_OPAQUE_MIN) transparent++
        val tRatio = transparent.toDouble() / n
        if (tRatio > MAX_TRANSPARENT_RATIO) {
            reasons += Reason.MOSTLY_TRANSPARENT
        } else if (spread(candidate, n) < SOLID_SPREAD_MAX) {
            reasons += Reason.SOLID_COLOR
        }

        val regionOk = region != null && regionSampleW > 0 && regionSampleH > 0 &&
            region.size >= regionSampleW * regionSampleH && regionW > 0 && regionH > 0
        if (regionOk && aspectRatioOff(candidateW, candidateH, regionW, regionH) > ASPECT_TOLERANCE) {
            reasons += Reason.ASPECT_MISMATCH
        }

        var hamming = -1
        // 候选已判空/纯色/透明时哈希无意义；截图区域平坦时也跳过（见 REGION_FLAT_SPREAD）
        if (regionOk && reasons.none { it != Reason.ASPECT_MISMATCH && it != Reason.TOO_SMALL } &&
            spread(region!!, regionSampleW * regionSampleH) >= REGION_FLAT_SPREAD
        ) {
            hamming = java.lang.Long.bitCount(
                averageHash(candidate, sampleW, sampleH) xor averageHash(region, regionSampleW, regionSampleH),
            )
            if (hamming > MAX_HAMMING) reasons += Reason.NOT_SIMILAR
        }
        return Verdict(reasons, hamming, tRatio)
    }

    /** 宽高比偏离倍数（≥1）。 */
    fun aspectRatioOff(w1: Int, h1: Int, w2: Int, h2: Int): Double {
        val a = w1.toDouble() / h1
        val b = w2.toDouble() / h2
        return max(a / b, b / a)
    }

    /** 灰度（BT.601 权重，只看 RGB；透明像素由调用处另行处理）。 */
    private fun gray(p: Int): Int = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /** 不透明像素灰度的 P95 − P5；没有不透明像素返回 0。 */
    internal fun spread(px: IntArray, n: Int): Int {
        val hist = IntArray(256)
        var count = 0
        for (i in 0 until n) {
            val p = px[i]
            if ((p ushr 24) < ALPHA_OPAQUE_MIN) continue
            hist[gray(p)]++
            count++
        }
        if (count == 0) return 0
        val lo = percentile(hist, count, 0.05)
        val hi = percentile(hist, count, 0.95)
        return hi - lo
    }

    private fun percentile(hist: IntArray, count: Int, q: Double): Int {
        val target = (count * q).toInt().coerceIn(0, count - 1)
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc > target) return v
        }
        return 255
    }

    /** 8×8 均值哈希：按块平均灰度，高于全局均值记 1。透明像素按白色计（与卡片白底一致）。 */
    internal fun averageHash(px: IntArray, w: Int, h: Int): Long {
        val sums = LongArray(HASH_SIDE * HASH_SIDE)
        val cnts = IntArray(HASH_SIDE * HASH_SIDE)
        for (y in 0 until h) {
            val by = y * HASH_SIDE / h
            for (x in 0 until w) {
                val p = px[y * w + x]
                val g = if ((p ushr 24) < ALPHA_OPAQUE_MIN) 255 else gray(p)
                val b = by * HASH_SIDE + x * HASH_SIDE / w
                sums[b] += g.toLong()
                cnts[b]++
            }
        }
        val avg = DoubleArray(sums.size) { if (cnts[it] == 0) 0.0 else sums[it].toDouble() / cnts[it] }
        // 小于 8px 的边会有空块：用相邻已填块补齐，避免空块全部落在 0 侧造成假差异
        for (i in avg.indices) if (cnts[i] == 0) avg[i] = avg[(i - 1).coerceAtLeast(0)]
        val mean = avg.average()
        var bits = 0L
        for (i in avg.indices) if (avg[i] > mean) bits = bits or (1L shl i)
        return bits
    }
}
