package io.github.kotoriinsky.tsuzuri.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * 视频控件的当前帧：Flyme 识屏取图的第一步就是 `TextureView` / `SurfaceView` 截帧。
 *
 * 截的是播放器自己的画面缓冲，不经过屏幕合成，所以**不带弹幕、进度条、标题这些叠在上面的控件**。
 *  - `TextureView.getBitmap()`：同步，主线程调用，尺寸是控件尺寸；
 *  - `SurfaceView` 用 `PixelCopy.Request.ofSurface`：不给目标位图时按画面缓冲原尺寸分配，
 *    片源是 1080p 就拿到 1080p，比屏幕上显示的大。
 *
 * 受保护的片源（DRM、`FLAG_SECURE`）拷不出来或是全黑；弹幕层也常常是一张透明的 TextureView /
 * SurfaceView，盖在视频上、同一个矩形 —— 两种都由 [looksLikeFrame] 判掉，换下一个候选。
 */
internal object VideoFrames {

    /** 至少这么多采样点不透明，才像一帧视频（弹幕层大半是透明的）。 */
    private const val MIN_OPAQUE = 0.9f

    /** 至少这么多采样点不是黑的（没出帧、受保护的画面是全黑）。 */
    private const val MIN_LIT = 0.03f

    private const val GRID = 24

    fun isVideo(v: View): Boolean = v is TextureView || v is SurfaceView

    /** 主线程调用；[done] 在主线程（TextureView）或 [executor] 上（SurfaceView）回调，只回调一次。 */
    fun grab(v: View, executor: Executor, done: (Bitmap?, String) -> Unit) {
        when (v) {
            is TextureView -> {
                val b = runCatching { if (v.isAvailable) v.bitmap else null }.getOrNull()
                done(b, if (b == null) "TextureView 还没有画面" else "TextureView 截帧")
            }
            is SurfaceView -> try {
                val req = PixelCopy.Request.Builder.ofSurface(v).build()
                PixelCopy.request(req, executor) { r ->
                    if (r.status == PixelCopy.SUCCESS) done(r.bitmap, "SurfaceView 截帧")
                    else done(null, "SurfaceView 拷贝失败（status=${r.status}）")
                }
            } catch (t: Throwable) {
                done(null, "SurfaceView 拷贝异常：$t")
            }
            else -> done(null, "不是视频控件")
        }
    }

    /** 不透明且不是全黑，才当成一帧视频。 */
    fun looksLikeFrame(b: Bitmap): Boolean {
        if (b.isRecycled || b.width <= 0 || b.height <= 0) return false
        var opaque = 0
        var lit = 0
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            val c = b.getPixel((b.width - 1) * gx / (GRID - 1), (b.height - 1) * gy / (GRID - 1))
            if (Color.alpha(c) >= 200) opaque++
            if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) >= 16) lit++
        }
        val n = (GRID * GRID).toFloat()
        return opaque / n >= MIN_OPAQUE && lit / n >= MIN_LIT
    }

    /**
     * 请求矩形只框了画面的一部分时裁出那一块（视频常常占满屏、点选框不出整张，用户就拖框）。
     * 框住九成以上就给整帧。[visible] 是控件在屏幕上的可见矩形，[origin] 是控件左上角的屏幕坐标。
     */
    fun cropTo(frame: Bitmap, view: View, origin: IntArray, visible: Rect, target: Rect): Bitmap {
        val inter = Rect()
        if (!inter.setIntersect(visible, target)) return frame
        if (inter.width().toLong() * inter.height() >= visible.width().toLong() * visible.height() * 9 / 10) return frame
        val sx = frame.width / view.width.toFloat()
        val sy = frame.height / view.height.toFloat()
        val l = ((inter.left - origin[0]) * sx).roundToInt().coerceIn(0, frame.width - 1)
        val t = ((inter.top - origin[1]) * sy).roundToInt().coerceIn(0, frame.height - 1)
        val r = ((inter.right - origin[0]) * sx).roundToInt().coerceIn(l + 1, frame.width)
        val btm = ((inter.bottom - origin[1]) * sy).roundToInt().coerceIn(t + 1, frame.height)
        return runCatching { Bitmap.createBitmap(frame, l, t, r - l, btm - t) }.getOrDefault(frame)
    }
}
