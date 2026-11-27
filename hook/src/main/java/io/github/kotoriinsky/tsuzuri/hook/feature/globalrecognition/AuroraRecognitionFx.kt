package io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin

/**
 * 全局识别的等待与揭晓过渡动效接口。
 *
 * 两种实现：
 *  - [AuroraRecognitionFx]（默认「流光扫描」）：AGSL 屏幕边缘流体光晕 + 柔和光幕自下而上扫过屏幕，
 *    不遮挡画面中央、结果到达立刻衔接揭晓；
 *  - [RecognitionFx]（经典「星云粒子」）：三维粒子光球聚拢并炸向四边。
 */
internal interface RecognitionEffect {
    val active: Boolean
    val wave: Float

    /** 唤起瞬间的轻柔压感微缩比例（1.0 = 原尺寸，略小于 1.0 表示被轻轻托起）。 */
    val pressScale: Float get() = 1f

    fun start()
    fun burst(w: Int, h: Int)
    fun collapse(w: Int, h: Int)
    fun stop()
    fun burstReadyAt(): Long
    fun draw(canvas: Canvas, w: Int, h: Int)

    /**
     * 计算区域 [r]（视图坐标）在揭晓波里的归一化进度 [0, 1]：
     * 0 最先亮起，1 最后亮起。
     */
    fun revealPos(r: RectF, w: Int, h: Int): Float
}

/**
 * 流光扫描动效（AGSL RuntimeShader + Canvas 降级）：
 *
 * 1. **唤起压感**：前 340ms 画面轻轻下压到 0.992 再缓弹回 1.0，提供清晰的「已冻结当前屏」触感反馈；
 * 2. **边缘流体光晕**：以洁白为主色调（辅以极淡的冰白 / 珍珠白微光）沿屏幕四周边框流动呼吸，
 *    屏幕中央完全通透无遮挡；
 * 3. **柔光幕扫屏**：等待 OCR 时一道柔和白光幕以 1.7s 周期自下而上持续轻扫；结果到达后，
 *    一道更明亮的宽光带在 580ms 内自下而上掠过画面，所过之处文字与图片顺势点亮，随后边缘光晕自然退隐。
 */
internal class AuroraRecognitionFx(
    private val d: Float,
    private val invalidate: () -> Unit,
    private val log: (String) -> Unit,
) : RecognitionEffect {

    private enum class Phase { IDLE, SCANNING, REVEAL, COLLAPSE, DONE }

    private var phase = Phase.IDLE
    private var t0 = 0L
    private var tPhase = 0L
    private var scanHeadAtBurst = 0.08f

    override var wave: Float = -1f
        private set

    override var pressScale: Float = 1f
        private set

    override val active: Boolean
        get() = phase == Phase.SCANNING || phase == Phase.REVEAL || phase == Phase.COLLAPSE

    private val clock = TimeAnimator().apply { setTimeListener { _, _, _ -> invalidate() } }

    private var shader: RuntimeShader? = null
    private var shaderInitFailed = false
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        blendMode = BlendMode.SCREEN
    }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun burstReadyAt(): Long = if (phase == Phase.SCANNING) t0 + MIN_SCAN_MS else 0L

    override fun start() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            phase = Phase.DONE
            wave = DONE_WAVE
            pressScale = 1f
            return
        }
        t0 = SystemClock.uptimeMillis()
        tPhase = t0
        wave = -1f
        pressScale = 1f
        scanHeadAtBurst = 0.08f
        phase = Phase.SCANNING
        clock.start()
    }

    override fun burst(w: Int, h: Int) {
        if (phase != Phase.SCANNING || w <= 0 || h <= 0) {
            finish()
            return
        }
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - t0).coerceAtLeast(0L)
        val cycleU = (elapsed % SCAN_CYCLE_MS) / SCAN_CYCLE_MS.toFloat()
        val curPos = -0.06f + 1.14f * easeInOut(cycleU)
        // 若当前扫描带刚好处于底部起步区，直接从该位置丝滑接续；否则从底部重新起一道揭晓光幕与文字亮起同步
        scanHeadAtBurst = if (curPos in 0.0f..0.28f) curPos else 0.0f
        tPhase = now
        wave = 0f
        phase = Phase.REVEAL
    }

    override fun collapse(w: Int, h: Int) {
        if (phase != Phase.SCANNING) {
            finish()
            return
        }
        tPhase = SystemClock.uptimeMillis()
        phase = Phase.COLLAPSE
    }

    override fun stop() {
        clock.cancel()
        pressScale = 1f
        if (phase != Phase.DONE) phase = Phase.DONE
    }

    private fun finish() {
        clock.cancel()
        phase = Phase.DONE
        wave = DONE_WAVE
        pressScale = 1f
        invalidate()
    }

    /** 自下而上扫屏：靠近屏幕底部的元素先亮（0），靠近顶部的最后亮（1）。 */
    override fun revealPos(r: RectF, w: Int, h: Int): Float {
        if (h <= 0) return 0f
        val cy = r.centerY().coerceIn(0f, h.toFloat())
        return (1f - cy / h.toFloat()).coerceIn(0f, 1f)
    }

    override fun draw(canvas: Canvas, w: Int, h: Int) {
        if (!active || w <= 0 || h <= 0) return
        val now = SystemClock.uptimeMillis()
        val elapsedSec = (now - t0) / 1000f

        // 唤起压感微缩：前 260ms 走一个柔和的 sin 脉冲（1.0 -> 0.992 -> 1.0）
        val pressU = ((now - t0) / PRESS_MS.toFloat()).coerceIn(0f, 1f)
        pressScale = if (pressU < 1f) {
            1f - PRESS_DIP * sin(PI * pressU).toFloat()
        } else {
            1f
        }

        val edgeAlpha: Float
        val sweepPos: Float
        val sweepAlpha: Float
        when (phase) {
            Phase.SCANNING -> {
                val fadeIn = ((now - t0) / EDGE_IN_MS.toFloat()).coerceIn(0f, 1f)
                val breathe = 0.82f + 0.18f * sin(elapsedSec * 2.4f)
                edgeAlpha = easeOut(fadeIn) * breathe
                // 等待期持续自下而上轻扫，避免长耗时 OCR 期间光幕停滞
                val cycleU = ((now - t0) % SCAN_CYCLE_MS) / SCAN_CYCLE_MS.toFloat()
                sweepPos = -0.06f + 1.14f * easeInOut(cycleU)
                val cycleFade = when {
                    cycleU < 0.18f -> cycleU / 0.18f
                    cycleU > 0.72f -> ((1f - cycleU) / 0.28f).coerceIn(0f, 1f)
                    else -> 1f
                }
                sweepAlpha = easeOut(fadeIn) * 0.52f * cycleFade
            }
            Phase.REVEAL -> {
                val u = ((now - tPhase) / REVEAL_MS.toFloat()).coerceIn(0f, 1f)
                val eased = easeOutCubic(u)
                wave = eased
                sweepPos = scanHeadAtBurst + (1.18f - scanHeadAtBurst) * eased
                // 扫描带前段亮起、到达顶端前柔和消散
                val startAlpha = if (scanHeadAtBurst > 0.01f) 0.45f else 0.12f
                sweepAlpha = when {
                    u < 0.18f -> startAlpha + (1f - startAlpha) * (u / 0.18f)
                    u > 0.72f -> (1f - (u - 0.72f) / 0.28f).coerceIn(0f, 1f)
                    else -> 1f
                }
                // 边缘光晕随扫描收尾优雅淡出
                edgeAlpha = (1f - easeInOut((u - 0.35f).coerceAtLeast(0f) / 0.65f)).coerceIn(0f, 1f)
                if (u >= 1f) {
                    finish()
                    return
                }
            }
            Phase.COLLAPSE -> {
                val u = ((now - tPhase) / COLLAPSE_MS.toFloat()).coerceIn(0f, 1f)
                val k = 1f - easeOut(u)
                edgeAlpha = k
                sweepPos = 0.08f
                sweepAlpha = k * 0.4f
                if (u >= 1f) {
                    finish()
                    return
                }
            }
            else -> return
        }

        val agsl = ensureShader()
        if (agsl != null && Build.VERSION.SDK_INT >= 33) {
            agsl.setFloatUniform("uRes", w.toFloat(), h.toFloat())
            agsl.setFloatUniform("uTime", elapsedSec)
            agsl.setFloatUniform("uEdgeWidth", EDGE_WIDTH_DP * d)
            agsl.setFloatUniform("uCornerRadius", CORNER_DP * d)
            agsl.setFloatUniform("uEdgeAlpha", edgeAlpha)
            agsl.setFloatUniform("uSweep", sweepPos)
            agsl.setFloatUniform("uSweepAlpha", sweepAlpha)
            shaderPaint.shader = agsl
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shaderPaint)
        } else {
            drawFallback(canvas, w, h, edgeAlpha, sweepPos, sweepAlpha)
        }
    }

    private fun ensureShader(): RuntimeShader? {
        if (shaderInitFailed || Build.VERSION.SDK_INT < 33) return null
        shader?.let { return it }
        return try {
            RuntimeShader(AURORA_AGSL).also { shader = it }
        } catch (t: Throwable) {
            shaderInitFailed = true
            log("流光扫描 AGSL 初始化失败，回落 Canvas：$t")
            null
        }
    }

    /** 不支持 AGSL 时的 Canvas 轻量降级：一道柔光扫描带 + 顶部微光。 */
    private fun drawFallback(
        canvas: Canvas,
        w: Int,
        h: Int,
        edgeAlpha: Float,
        sweepPos: Float,
        sweepAlpha: Float,
    ) {
        val bandH = 140 * d
        val cy = h * (1f - sweepPos)
        if (sweepAlpha > 0.01f) {
            val a = (0x55 * sweepAlpha).toInt().coerceIn(0, 255)
            fallbackPaint.shader = LinearGradient(
                0f,
                cy - bandH,
                0f,
                cy + bandH * 0.6f,
                intArrayOf(
                    Color.TRANSPARENT,
                    (a shl 24) or 0xFFFFFF,
                    ((a * 2 / 3) shl 24) or 0xE6EEFF,
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.55f, 0.78f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, (cy - bandH).coerceAtLeast(0f), w.toFloat(), (cy + bandH * 0.6f).coerceAtMost(h.toFloat()), fallbackPaint)
        }
        if (edgeAlpha > 0.01f) {
            val stroke = 4 * d
            fallbackPaint.shader = null
            fallbackPaint.style = Paint.Style.STROKE
            fallbackPaint.strokeWidth = stroke
            fallbackPaint.color = (((0x70 * edgeAlpha).toInt().coerceIn(0, 255)) shl 24) or 0xFFFFFF
            canvas.drawRoundRect(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f, 28 * d, 28 * d, fallbackPaint)
            fallbackPaint.style = Paint.Style.FILL
        }
    }

    private fun easeOut(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv
    }

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    private fun easeInOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private companion object {
        const val MIN_SCAN_MS = 450L
        const val SCAN_CYCLE_MS = 1700L
        const val EDGE_IN_MS = 280L
        const val PRESS_MS = 340L
        const val PRESS_DIP = 0.008f
        const val REVEAL_MS = 580L
        const val COLLAPSE_MS = 280L
        const val EDGE_WIDTH_DP = 44f
        const val CORNER_DP = 36f
        const val DONE_WAVE = 10f

        /**
         * AGSL 着色器：
         * - 边缘流体光晕：圆角矩形内距 SDF，以洁白为主色调并带极淡的冰白 / 珍珠白微光流转；
         * - 自下而上柔光扫描幕：高斯型非对称光带（前沿略锐、尾迹柔长）。
         */
        const val AURORA_AGSL = """
            uniform float2 uRes;
            uniform float uTime;
            uniform float uEdgeWidth;
            uniform float uCornerRadius;
            uniform float uEdgeAlpha;
            uniform float uSweep;
            uniform float uSweepAlpha;

            float sdRoundBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            half3 auroraColor(float phase) {
                // 以纯白为主色调，辅以极淡的冰白 / 珍珠白微光保持流体层次
                half3 c1 = half3(0.99, 0.99, 1.00);
                half3 c2 = half3(0.86, 0.92, 1.00);
                half3 c3 = half3(0.95, 0.95, 1.00);
                float w1 = 0.5 + 0.5 * sin(phase);
                float w2 = 0.5 + 0.5 * sin(phase + 2.094);
                half3 mix1 = mix(c1, c2, half(w1 * 0.55));
                return mix(mix1, c3, half(w2 * 0.45));
            }

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / max(uRes, float2(1.0));
                float2 center = fragCoord - uRes * 0.5;
                float distToEdge = -sdRoundBox(center, uRes * 0.5, uCornerRadius);

                // 1) 屏幕四周边框流体光晕（内距越小越亮，中心完全为 0）
                float angle = atan(center.y, center.x);
                float waveEdge = 1.0 + 0.14 * sin(angle * 3.0 - uTime * 2.4) + 0.08 * cos(angle * 5.0 + uTime * 1.9);
                float normDist = max(distToEdge, 0.0) / max(uEdgeWidth * waveEdge, 1.0);
                float rimCore = exp(-normDist * 4.8) * 0.62;
                float rimHalo = exp(-normDist * 1.65) * 0.30;
                float edgeMask = (rimCore + rimHalo) * uEdgeAlpha;
                half3 edgeCol = auroraColor(angle * 1.5 - uTime * 2.2) * half(edgeMask);

                // 2) 自下而上柔光扫描幕（前沿微弯，像玻璃光波掠过）
                float yFromBottom = 1.0 - uv.y;
                float arch = 0.022 * cos((uv.x - 0.5) * 3.14159);
                float dy = (yFromBottom + arch) - uSweep;
                // 前沿略收紧，尾迹拖长柔和散开
                float spread = dy > 0.0 ? 0.042 : 0.135;
                float band = exp(-(dy * dy) / (2.0 * spread * spread));
                // 扫描带中央更透亮，边缘自然汇入流体边框
                float sideBoost = 0.78 + 0.22 * abs(uv.x - 0.5) * 2.0;
                float sweepMask = band * sideBoost * uSweepAlpha * 0.42;
                half3 sweepCol = auroraColor(uv.x * 2.6 + uTime * 2.6 + dy * 4.0) * half(sweepMask);
                // 极细的前沿亮丝
                float crest = exp(-(dy * dy) / (2.0 * 0.008 * 0.008)) * uSweepAlpha * 0.38;
                half3 crestCol = half3(1.0, 1.0, 1.0) * half(crest);

                half3 rgb = clamp(edgeCol + sweepCol + crestCol, half3(0.0), half3(1.0));
                half alpha = clamp(max(max(rgb.r, rgb.g), rgb.b), half(0.0), half(1.0));
                return half4(rgb, alpha);
            }
        """
    }
}
