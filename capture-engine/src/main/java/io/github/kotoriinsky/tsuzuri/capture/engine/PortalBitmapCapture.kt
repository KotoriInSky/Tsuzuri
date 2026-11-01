package io.github.kotoriinsky.tsuzuri.capture.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Looper
import android.view.View

/** 来源进程的同步采集入口；尚无现有作用域内可接入第三方 View 的生产调用点。 */
object PortalBitmapCapture {
    data class Result(
        val bitmap: Bitmap,
        val sourceBounds: RectF,
        val visibleBounds: RectF,
        val bitmapToView: Matrix,
        val fromShader: Boolean,
        val usesWholeBitmap: Boolean,
        val boundsAreExact: Boolean,
    )
    private val active = ThreadLocal<Boolean>()

    /** 最近一次 [capture] 返回 null 的原因（只在主线程读写），诊断日志用。 */
    @JvmStatic
    var lastFailure: String? = null
        private set

    /** 一次画布记录的全部位图：[unsupported] 非空表示中途遇到无法确定遮挡的绘制，结果只能当线索用。 */
    class Drawn(val samples: List<Result>, val unsupported: String?)

    /**
     * 把 [view] 画进记录画布，返回它画过的所有位图（先画的在前，坐标为 View 本地坐标）。
     * Compose 页面里的图片不是 View，只能这样拿。绘制异常返回 null，原因在 [lastFailure]。
     */
    @JvmStatic
    fun drawn(view: View): Drawn? {
        if (Looper.myLooper() != Looper.getMainLooper() || active.get() == true || view.width <= 0 || view.height <= 0) {
            lastFailure = "前置条件不满足（线程/尺寸）"
            return null
        }
        active.set(true)
        try {
            val canvas = PortalBitmapCanvas(view.width, view.height)
            canvas.density = view.resources.displayMetrics.densityDpi
            view.draw(canvas)
            lastFailure = null
            return Drawn(canvas.samples.filter { !it.bitmap.isRecycled }.map {
                Result(it.bitmap, RectF(it.sourceBounds), RectF(it.visibleBounds), Matrix(it.bitmapToView), it.fromShader, it.usesWholeBitmap, it.boundsAreExact)
            }, if (canvas.unsupported) canvas.reason ?: "未知" else null)
        } catch (e: RuntimeException) {
            lastFailure = "绘制异常：$e"
            return null
        } catch (e: LinkageError) {
            lastFailure = "绘制异常：$e"
            return null
        } catch (_: OutOfMemoryError) {
            lastFailure = "内存不足"
            return null
        } finally { active.remove() }
    }

    /** 借用 Bitmap 不复制不回收；绘制异常、复杂裁切或无命中时交还原有回退路径。 */
    @JvmStatic
    fun capture(view: View, x: Float, y: Float): Result? {
        if (Looper.myLooper() != Looper.getMainLooper() || active.get() == true ||
            view.width <= 0 || view.height <= 0 || !x.isFinite() || !y.isFinite() ||
            x < 0 || y < 0 || x >= view.width || y >= view.height) {
            lastFailure = "前置条件不满足（线程/尺寸/坐标）"
            return null
        }
        active.set(true)
        try {
            val canvas = PortalBitmapCanvas(view.width, view.height)
            canvas.density = view.resources.displayMetrics.densityDpi
            view.draw(canvas)
            if (canvas.unsupported) {
                lastFailure = "不支持的绘制：${canvas.reason}（已记录 ${canvas.samples.size} 个位图）"
                return null
            }
            val sample = canvas.samples.lastOrNull { !it.bitmap.isRecycled && it.contains(x, y) }
            if (sample == null) {
                lastFailure = "中心点没有位图（共记录 ${canvas.samples.size} 个）"
                return null
            }
            lastFailure = null
            return Result(sample.bitmap, RectF(sample.sourceBounds), RectF(sample.visibleBounds),
                Matrix(sample.bitmapToView), sample.fromShader, sample.usesWholeBitmap, sample.boundsAreExact)
        } catch (e: RuntimeException) {
            lastFailure = "绘制异常：$e"
            return null
        } catch (e: LinkageError) {
            lastFailure = "绘制异常：$e"
            return null
        } catch (_: OutOfMemoryError) {
            lastFailure = "内存不足"
            return null
        } finally { active.remove() }
    }
}
