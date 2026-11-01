package io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.SystemClock
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 全局识别的粒子动画：粒子聚成一个缓慢旋转的三维光球，等结果；结果到了沿各自方向炸向屏幕四边，
 * 撞边处亮一下；什么都没识别到就塌缩消失。
 *
 * 「光追感」靠两层：清晰的亮核直接画在画布上，同样的内容再画进一个整层模糊的 [RenderNode] 当光晕，
 * 两层都用 [BlendMode.PLUS] 叠加（重叠处更亮）。拿不到发光节点就只画亮核（降级）。
 *
 * 所有随机量在 [start] / [burst] 时一次算好放进 FloatArray，绘制时不分配对象、不再随机。
 * 时间统一取 [SystemClock.uptimeMillis]，[TimeAnimator] 只负责逐帧触发重绘。
 */
internal class RecognitionFx(
    private val d: Float,
    private val invalidate: () -> Unit,
    private val log: (String) -> Unit,
) {

    private enum class Phase { IDLE, SPHERE, BURST, COLLAPSE, DONE }

    private var phase = Phase.IDLE
    private val n = PARTICLES
    private val rnd = Random()

    // 球面上的位置（单位向量）、半径系数、亮核半径、闪烁与噪声相位
    private val ux = FloatArray(n)
    private val uy = FloatArray(n)
    private val uz = FloatArray(n)
    private val rad = FloatArray(n)
    private val core = FloatArray(n)
    private val twinkle = FloatArray(n)
    private val noise = FloatArray(n)

    // 聚拢：出发角度、出发距离（球半径的倍数）、起步延迟
    private val gAng = FloatArray(n)
    private val gDist = FloatArray(n)
    private val gDelay = FloatArray(n)

    // 当前帧的屏幕位置、亮度、半径（炸开 / 塌缩时以此为起点）
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val pk = FloatArray(n)
    private val ps = FloatArray(n)

    // 炸开：起点、方向、距离、起步延迟、到达用时比例、散逸比例（0 = 飞到边）、横向漂移、是否已撞边
    private val bx = FloatArray(n)
    private val by = FloatArray(n)
    private val bAng = FloatArray(n)
    private val bDist = FloatArray(n)
    private val bDelay = FloatArray(n)
    private val bArrive = FloatArray(n)
    private val bStray = FloatArray(n)
    private val bDrift = FloatArray(n)
    private val bHit = BooleanArray(n)
    /** 炸开那一刻的亮度（逐帧按它乘淡出，避免在上一帧结果上反复相乘）。 */
    private val bK = FloatArray(n)

    // 撞边反光：x, y, 时刻
    private val hits = FloatArray(n * 3)
    private var hitCount = 0

    private var t0 = 0L
    private var tPhase = 0L
    private var lastDraw = 0L
    private var rot = 0f

    /**
     * 扩散圈的进度，给结果亮起用：炸开前 -1（都不亮），炸开中 0..1，结束或系统关了动画时取一个大值（都全亮）。
     * 与粒子同一套「按方向归一化」的坐标。
     */
    var wave = -1f
        private set

    private val glowPaint = Paint().apply { blendMode = BlendMode.PLUS }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; blendMode = BlendMode.PLUS; strokeCap = Paint.Cap.ROUND }
    private val glowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private var glow: RenderNode? = null
    private var glowFailed = false

    private val clock = TimeAnimator().apply { setTimeListener { _, _, _ -> invalidate() } }

    val active get() = phase == Phase.SPHERE || phase == Phase.BURST || phase == Phase.COLLAPSE

    /** 聚拢完成、并露足最少时间的时刻；早于它到的结果要等到这时再炸。 */
    fun burstReadyAt(): Long = if (phase == Phase.SPHERE) t0 + GATHER_MS + MIN_SHOW_MS else 0L

    fun start() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            phase = Phase.DONE
            wave = DONE_WAVE
            return
        }
        rnd.setSeed(SystemClock.uptimeMillis())
        val golden = (PI * (3.0 - Math.sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            // 斐波那契球面取点再加一点扰动：均匀但不规整
            val y = 1f - 2f * (i + 0.5f) / n
            val r = Math.sqrt((1f - y * y).toDouble()).toFloat()
            val th = golden * i + (rnd.nextFloat() - 0.5f) * 0.3f
            ux[i] = cos(th) * r; uy[i] = y; uz[i] = sin(th) * r
            rad[i] = 0.82f + rnd.nextFloat() * 0.18f
            core[i] = (CORE_MIN_DP + rnd.nextFloat() * (CORE_MAX_DP - CORE_MIN_DP)) * d
            twinkle[i] = rnd.nextFloat() * 2f * PI.toFloat()
            noise[i] = rnd.nextFloat() * 2f * PI.toFloat()
            gAng[i] = rnd.nextFloat() * 2f * PI.toFloat()
            gDist[i] = 2f + rnd.nextFloat() * 2f
            gDelay[i] = rnd.nextFloat() * GATHER_STAGGER_MS
        }
        rot = rnd.nextFloat() * 2f * PI.toFloat()
        hitCount = 0
        wave = -1f
        t0 = SystemClock.uptimeMillis()
        lastDraw = t0
        phase = Phase.SPHERE
        clock.start()
    }

    /** 结果到了：从当前帧的位置炸开。[w]、[h] 是画布尺寸。 */
    fun burst(w: Int, h: Int) {
        if (phase != Phase.SPHERE || w <= 0 || h <= 0) { finish(); return }
        val cx = w / 2f
        val cy = h / 2f
        val now = SystemClock.uptimeMillis()
        sphere(now, cx, cy)
        val sphereR = SPHERE_DP * d / 2
        val jitter = (JITTER_DEG * PI / 180).toFloat()
        for (i in 0 until n) {
            bx[i] = px[i]; by[i] = py[i]
            // 炸开时比球上亮一些：后半球的暗粒子也要看得见
            bK[i] = max(pk[i], BURST_MIN_LIGHT)
            val dx = px[i] - cx
            val dy = py[i] - cy
            val dist0 = hypot(dx, dy)
            val a = (if (dist0 < 0.5f * d) rnd.nextFloat() * 2f * PI.toFloat() else kotlin.math.atan2(dy, dx)) +
                (rnd.nextFloat() * 2f - 1f) * jitter
            bAng[i] = a
            bDist[i] = max(0f, edgeDistance(a, cx, cy) - dist0) + ps[i] * 2
            // 由外向里剥开：球面边缘的先走
            bDelay[i] = (1f - (dist0 / sphereR).coerceIn(0f, 1f)) * BURST_STAGGER_MS
            bArrive[i] = ARRIVE_MIN + rnd.nextFloat() * (1f - ARRIVE_MIN)
            bStray[i] = if (rnd.nextFloat() < STRAY_SHARE) 0.25f + rnd.nextFloat() * 0.35f else 0f
            bDrift[i] = (rnd.nextFloat() * 2f - 1f) * STRAY_DRIFT_DP * d
            bHit[i] = false
        }
        hitCount = 0
        wave = 0f
        tPhase = now
        phase = Phase.BURST
    }

    /** 什么都没识别到：粒子向球心塌缩、淡出。 */
    fun collapse(w: Int, h: Int) {
        if (phase != Phase.SPHERE) { finish(); return }
        sphere(SystemClock.uptimeMillis(), w / 2f, h / 2f)
        for (i in 0 until n) { bx[i] = px[i]; by[i] = py[i] }
        tPhase = SystemClock.uptimeMillis()
        phase = Phase.COLLAPSE
    }

    fun stop() {
        clock.cancel()
        if (phase != Phase.DONE) phase = Phase.DONE
    }

    private fun finish() {
        clock.cancel()
        phase = Phase.DONE
        wave = DONE_WAVE
        invalidate()
    }

    /** 从中心沿 [angle] 到画布边缘的距离。 */
    fun edgeDistance(angle: Float, cx: Float, cy: Float): Float {
        val c = abs(cos(angle))
        val s = abs(sin(angle))
        val dx = if (c > 1e-4f) cx / c else Float.MAX_VALUE
        val dy = if (s > 1e-4f) cy / s else Float.MAX_VALUE
        return min(dx, dy)
    }

    fun draw(canvas: Canvas, w: Int, h: Int) {
        if (!active || w <= 0 || h <= 0) return
        val now = SystemClock.uptimeMillis()
        val cx = w / 2f
        val cy = h / 2f
        when (phase) {
            Phase.SPHERE -> sphere(now, cx, cy)
            Phase.BURST -> if (!burstFrame(now, w, h, cx, cy)) { finish(); return }
            Phase.COLLAPSE -> if (!collapseFrame(now, cx, cy)) { finish(); return }
            else -> return
        }
        lastDraw = now
        val node = glowNode(canvas, w, h)
        if (node != null) {
            val rc = node.beginRecording(w, h)
            try { paintAll(rc, now, w, h, cx, cy, glowPass = true) } finally { node.endRecording() }
            canvas.drawRenderNode(node)
        }
        paintAll(canvas, now, w, h, cx, cy, glowPass = false)
    }

    // ── 各阶段的位置 ─────────────────────────────────────────────────────

    /** 球（含聚拢）：算出每个粒子此刻的屏幕位置、亮度、半径。 */
    private fun sphere(now: Long, cx: Float, cy: Float) {
        val dt = (now - lastDraw).coerceIn(0L, 50L)
        val waited = (now - t0 - GATHER_MS).toFloat()
        // 慢时渐进加速、呼吸渐强：节奏只增不减
        val slow = ((waited - SLOW_FROM_MS) / SLOW_RAMP_MS).coerceIn(0f, 1f)
        rot += dt / ROTATE_MS.toFloat() * 2f * PI.toFloat() * (1f + slow * (SLOW_SPEEDUP - 1f))
        val tSec = (now - t0) / 1000f
        val breath = 1f + (BREATH_AMP + slow * BREATH_AMP_SLOW) * sin(tSec * 2f * PI.toFloat() * 1000f / BREATHE_MS)
        val r = SPHERE_DP * d / 2 * breath
        val cr = cos(rot); val sr = sin(rot)
        val tilt = (TILT_DEG * PI / 180).toFloat()
        val ct = cos(tilt); val st = sin(tilt)
        for (i in 0 until n) {
            val x1 = ux[i] * cr + uz[i] * sr
            val z1 = -ux[i] * sr + uz[i] * cr
            val y2 = uy[i] * ct - z1 * st
            val z2 = uy[i] * st + z1 * ct
            val wob = 1f + NOISE_AMP * sin(noise[i] + tSec * (2.2f + (i % 3)))
            val depth = (z2 + 1f) / 2f
            val sx = cx + x1 * r * rad[i] * wob
            val sy = cy + y2 * r * rad[i] * wob
            var k = BACK_LIGHT + (1f - BACK_LIGHT) * depth
            val tw = sin(twinkle[i] + tSec * 5.3f)
            if (tw > TWINKLE_FROM) k = min(1f, k + 0.7f * (tw - TWINKLE_FROM) / (1f - TWINKLE_FROM))
            var s = core[i] * (0.6f + 0.4f * depth)
            // 聚拢：从外圈随机位置收进来，同时淡入
            val g = ease(((now - t0 - gDelay[i]) / (GATHER_MS - GATHER_STAGGER_MS)).coerceIn(0f, 1f))
            if (g < 1f) {
                val fx = cx + cos(gAng[i]) * gDist[i] * r
                val fy = cy + sin(gAng[i]) * gDist[i] * r
                px[i] = fx + (sx - fx) * g
                py[i] = fy + (sy - fy) * g
                k *= g
                s *= 0.5f + 0.5f * g
            } else {
                px[i] = sx; py[i] = sy
            }
            pk[i] = k; ps[i] = s
        }
    }

    /** @return false 表示整段结束（粒子都到边、反光也淡完） */
    private fun burstFrame(now: Long, w: Int, h: Int, cx: Float, cy: Float): Boolean {
        val tb = (now - tPhase).toFloat()
        wave = ease(tb / (BURST_MS * WAVE_SHARE))
        for (i in 0 until n) {
            val u = ((tb - bDelay[i]) / (BURST_MS * bArrive[i])).coerceIn(0f, 1f)
            val stray = bStray[i] > 0f
            val reach = if (stray) bDist[i] * bStray[i] else bDist[i]
            val e = ease(u)
            val a = bAng[i]
            px[i] = bx[i] + cos(a) * reach * e - sin(a) * bDrift[i] * e
            py[i] = by[i] + sin(a) * reach * e + cos(a) * bDrift[i] * e
            val fadeFrom = if (stray) STRAY_FADE_FROM else FADE_FROM
            val fade = if (u < fadeFrom) 1f else 1f - (u - fadeFrom) / (1f - fadeFrom)
            pk[i] = (bK[i] * fade).coerceIn(0f, 1f)
            if (!stray && u >= 1f && !bHit[i] && hitCount < n) {
                bHit[i] = true
                hits[hitCount * 3] = px[i].coerceIn(0f, w.toFloat())
                hits[hitCount * 3 + 1] = py[i].coerceIn(0f, h.toFloat())
                hits[hitCount * 3 + 2] = tb
                hitCount++
            }
        }
        return tb < BURST_MS + EDGE_GLOW_MS
    }

    private fun collapseFrame(now: Long, cx: Float, cy: Float): Boolean {
        val u = ((now - tPhase) / COLLAPSE_MS.toFloat()).coerceIn(0f, 1f)
        val e = u * u
        for (i in 0 until n) {
            px[i] = bx[i] + (cx - bx[i]) * e
            py[i] = by[i] + (cy - by[i]) * e
            pk[i] = pk[i].coerceAtMost(1f - u)
        }
        return u < 1f
    }

    // ── 绘制 ─────────────────────────────────────────────────────────────

    private fun paintAll(c: Canvas, now: Long, w: Int, h: Int, cx: Float, cy: Float, glowPass: Boolean) {
        val p = if (glowPass) glowDotPaint else corePaint
        val grow = if (glowPass) GLOW_GROW else 1f
        val burstT = if (phase == Phase.BURST) (now - tPhase).toFloat() else -1f
        for (i in 0 until n) {
            val k = pk[i]
            if (k <= 0.01f) continue
            p.alpha = (255 * k).toInt()
            val s = ps[i] * grow
            if (burstT >= 0f) {
                // 拖影：起步最长，减速后缩回一个点
                val u = ((burstT - bDelay[i]) / (BURST_MS * bArrive[i])).coerceIn(0f, 1f)
                val len = TRAIL_DP * d * (1f - u) * (1f - u) * (if (u > 0f) 1f else 0f)
                if (len > s) {
                    val a = bAng[i]
                    p.strokeWidth = s * 2f
                    c.drawLine(px[i], py[i], px[i] - cos(a) * len, py[i] - sin(a) * len, p)
                    continue
                }
            }
            c.drawCircle(px[i], py[i], s, p)
        }
        if (phase == Phase.SPHERE) drawRipples(c, now, cx, cy, glowPass)
        if (phase == Phase.BURST) drawEdgeHits(c, burstT, w, h, glowPass)
    }

    /** 慢时从球面向外的涟漪。 */
    private fun drawRipples(c: Canvas, now: Long, cx: Float, cy: Float, glowPass: Boolean) {
        val since = (now - t0 - GATHER_MS - RIPPLE_FROM_MS).toFloat()
        if (since < 0f) return
        val p = if (glowPass) glowDotPaint else corePaint
        val q = (since % RIPPLE_MS) / RIPPLE_MS
        val r0 = SPHERE_DP * d / 2
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (glowPass) 2.4f else 1.2f) * d
        p.alpha = (255 * RIPPLE_LIGHT * (1f - q) * (1f - q)).toInt()
        c.drawCircle(cx, cy, r0 * (1f + (RIPPLE_REACH - 1f) * ease(q)), p)
        p.style = Paint.Style.FILL
    }

    /** 撞边反光：贴着最近的那条边亮一小段，淡出。 */
    private fun drawEdgeHits(c: Canvas, tb: Float, w: Int, h: Int, glowPass: Boolean) {
        val p = if (glowPass) glowDotPaint else corePaint
        val half = EDGE_GLOW_LEN_DP * d / 2
        val inset = 1.5f * d
        p.strokeWidth = (if (glowPass) 4f else 1.2f) * d
        for (j in 0 until hitCount) {
            val age = tb - hits[j * 3 + 2]
            if (age < 0f || age > EDGE_GLOW_MS) continue
            val a = 1f - age / EDGE_GLOW_MS
            p.alpha = (255 * a * (if (glowPass) 0.8f else 0.5f)).toInt()
            val x = hits[j * 3]
            val y = hits[j * 3 + 1]
            val dl = x; val dr = w - x; val dt = y; val db = h - y
            when (min(min(dl, dr), min(dt, db))) {
                dl -> c.drawLine(inset, y - half, inset, y + half, p)
                dr -> c.drawLine(w - inset, y - half, w - inset, y + half, p)
                dt -> c.drawLine(x - half, inset, x + half, inset, p)
                else -> c.drawLine(x - half, h - inset, x + half, h - inset, p)
            }
        }
    }

    /** 光晕层：整层模糊后 PLUS 叠加。拿不到（软件画布、创建失败）返回 null，只画亮核。 */
    private fun glowNode(canvas: Canvas, w: Int, h: Int): RenderNode? {
        if (glowFailed || !canvas.isHardwareAccelerated) return null
        val node = glow ?: try {
            RenderNode("fp-gr-glow").apply {
                val blur = GLOW_BLUR_DP * d
                setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL))
                setUseCompositingLayer(true, glowPaint)
                alpha = GLOW_ALPHA
            }.also { glow = it }
        } catch (t: Throwable) {
            glowFailed = true
            log("发光不可用，已降级：$t")
            return null
        }
        if (node.width != w || node.height != h) node.setPosition(0, 0, w, h)
        return node
    }

    /** 先快后慢（三次）。 */
    private fun ease(t: Float): Float {
        val x = 1f - t.coerceIn(0f, 1f)
        return 1f - x * x * x
    }

    private companion object {
        const val PARTICLES = 300
        const val SPHERE_DP = 56f
        const val CORE_MIN_DP = 0.8f
        const val CORE_MAX_DP = 1.8f
        /** 背面最暗的亮度、炸开时的最低亮度。 */
        const val BACK_LIGHT = 0.25f
        const val BURST_MIN_LIGHT = 0.55f
        const val TILT_DEG = 20f
        const val ROTATE_MS = 4000L
        const val BREATHE_MS = 1200L
        const val BREATH_AMP = 0.06f
        const val NOISE_AMP = 0.04f
        /** sin 超过它的那一小段时间里粒子闪亮。 */
        const val TWINKLE_FROM = 0.985f

        const val GATHER_MS = 240L
        const val GATHER_STAGGER_MS = 80L
        /** 球成形后至少露多久再炸。 */
        const val MIN_SHOW_MS = 260L

        /** 慢时：从球成形算起多久开始加速、多久加到满、满速倍数、呼吸增量；涟漪。 */
        const val SLOW_FROM_MS = 600f
        const val SLOW_RAMP_MS = 1400f
        const val SLOW_SPEEDUP = 1.8f
        const val BREATH_AMP_SLOW = 0.03f
        const val RIPPLE_FROM_MS = 1200L
        const val RIPPLE_MS = 1600f
        const val RIPPLE_REACH = 3.5f
        const val RIPPLE_LIGHT = 0.35f

        const val BURST_MS = 600f
        const val BURST_STAGGER_MS = 60f
        const val ARRIVE_MIN = 0.75f
        const val JITTER_DEG = 12f
        const val STRAY_SHARE = 0.15f
        const val STRAY_DRIFT_DP = 20f
        const val FADE_FROM = 0.65f
        const val STRAY_FADE_FROM = 0.3f
        const val TRAIL_DP = 24f
        /** 扩散圈（结果亮起）用总时长的多少走完。 */
        const val WAVE_SHARE = 0.85f
        const val EDGE_GLOW_MS = 120f
        const val EDGE_GLOW_LEN_DP = 40f
        const val COLLAPSE_MS = 220f

        const val GLOW_BLUR_DP = 6f
        const val GLOW_ALPHA = 0.7f
        /** 光晕层里粒子画大一些，模糊后才有一圈光。 */
        const val GLOW_GROW = 1.8f

        const val DONE_WAVE = 100f
    }
}
