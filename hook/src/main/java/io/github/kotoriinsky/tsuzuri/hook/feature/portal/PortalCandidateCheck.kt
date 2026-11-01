package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.kotoriinsky.tsuzuri.core.CandidateImageCheck
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 候选图校验的 Android 侧：降采样取像素，交给 core 的 [CandidateImageCheck] 判定。
 *
 * 性能：两张图都缩到长边 [CandidateImageCheck.SAMPLE_SIDE]（32px）再取像素，
 * 判定本身是 O(32×32)。整个过程在单线程后台执行器上跑，不占主线程；
 * 结果回到主线程后由调用方再确认「服务里的位图还是那一张」才替换。
 */
internal object PortalCandidateCheck {

    /** 单线程即可：一次长按只有一次校验，排队也不会积压。 */
    val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "FP-PortalCandidate").apply { isDaemon = true } }
    }

    class Sample(val px: IntArray, val w: Int, val h: Int)

    /**
     * 把 [src]（可选只取 [crop] 范围）缩到长边 ≤ 32 并取 ARGB 像素。失败返回 null。
     * HARDWARE 位图不能直接 getPixels，也不能被软件 Canvas 绘制，先拷成软件位图。
     */
    fun sample(src: Bitmap, crop: Rect? = null): Sample? = try {
        var soft = src
        if (src.config == Bitmap.Config.HARDWARE) soft = src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        val part = if (crop != null) Bitmap.createBitmap(soft, crop.left, crop.top, crop.width(), crop.height()) else soft
        val side = CandidateImageCheck.SAMPLE_SIDE
        val scale = minOf(1f, side.toFloat() / maxOf(part.width, part.height))
        val w = maxOf(1, (part.width * scale).toInt())
        val h = maxOf(1, (part.height * scale).toInt())
        val small = if (w == part.width && h == part.height) part else Bitmap.createScaledBitmap(part, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        // 只回收本函数自己创建的中间位图，绝不碰宿主的 src
        if (small !== part && small !== src) small.recycle()
        if (part !== soft && part !== src) part.recycle()
        if (soft !== src) soft.recycle()
        Sample(px, w, h)
    } catch (_: Throwable) {
        null
    }

    /** 一次校验的输入：两张图的 32px 像素副本，与宿主位图不再有任何引用关系。 */
    class Input(val c: Sample?, val r: Sample?, val cw: Int, val ch: Int, val region: Rect?)

    /**
     * 必须在主线程调用：宿主也在主线程回收 [shot]，同线程读才不会撞上回收。
     * 降采样到 32px 的开销在 1ms 以内，放主线程无感。
     */
    fun capture(candidate: Bitmap, shot: Bitmap?, region: Rect?): Input {
        val c = if (candidate.isRecycled) null else sample(candidate)
        val r = if (shot != null && !shot.isRecycled && region != null && !region.isEmpty) sample(shot, region) else null
        return Input(c, r, candidate.width, candidate.height, region)
    }

    /** 纯计算，可在任意线程跑。[Input.region] 为 null 时只做候选自身检查。 */
    fun judge(input: Input): CandidateImageCheck.Verdict {
        val c = input.c ?: return CandidateImageCheck.Verdict(listOf(CandidateImageCheck.Reason.EMPTY))
        val r = input.r
        val region = input.region
        return CandidateImageCheck.check(
            c.px, c.w, c.h, input.cw, input.ch,
            r?.px, r?.w ?: 0, r?.h ?: 0, region?.width() ?: 0, region?.height() ?: 0,
        )
    }
}
