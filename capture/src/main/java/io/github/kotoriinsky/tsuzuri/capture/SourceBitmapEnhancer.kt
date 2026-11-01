package io.github.kotoriinsky.tsuzuri.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import io.github.kotoriinsky.tsuzuri.capture.engine.DrawableBitmapFinder
import io.github.kotoriinsky.tsuzuri.capture.engine.PortalBitmapCapture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 原厂抓取器给出了位图时，试着换成 View 绘制时用到的源位图。
 *
 * 只在源位图像素明显更多时替换；取不到、不更大、太大都保留原厂结果。
 * 这里运行在第三方应用进程里：任何异常都吞掉并保留原结果，绝不让对方崩。
 */
object SourceBitmapEnhancer {
    private const val TAG = StockFetcher.TAG

    /** 回传给传送门的位图长边上限：Bundle 走 Binder，过大会传不过去。 */
    private const val MAX_SIDE = 4096

    /** 源位图过大时不复制（复制是为了脱离硬件位图，内存翻倍）。 */
    private const val MAX_SOURCE_PIXELS = 24_000_000L

    /** 只有像素数比原厂结果多出这个倍数才值得替换。 */
    private const val MIN_GAIN = 1.2f

    /** 绘制必须在主线程；抓取可能在工作线程调用，等主线程最多这么久。 */
    private const val MAIN_WAIT_MS = 250L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    @JvmStatic
    fun enhance(view: View?, bundle: Bundle?): Bundle? {
        if (view == null || bundle == null) return bundle
        try {
            // 位图不一定在 viewBitmap 里：命中反射规则时（如小红书），字段名是规则路径本身，
            // 例如 `mDrawable/Object/mBitmapState/Object/mBitmap/Object`。取所有位图字段里最大的那张。
            @Suppress("DEPRECATION")
            val bitmaps = bundle.keySet().mapNotNull { k -> (bundle.get(k) as? Bitmap)?.let { k to it } }
            if (bitmaps.isEmpty() || VideoFrames.isVideo(view) || view is android.webkit.WebView) logShape(view, bundle)
            val (key, old) = bitmaps.maxByOrNull { it.second.width.toLong() * it.second.height } ?: return bundle
            // 规则已读到原图地址时不必做：传送门的「完整媒体」会自己下载原文件（小红书实测 原文件=true）
            logUrls(bundle)
            val result = onMain { PortalBitmapCapture.capture(view, view.width / 2f, view.height / 2f) }
            // 画布拦截取不到时（圆角封面要读 BitmapShader 的隐藏字段，第三方进程里常被拦），按 Drawable 树再找一次
            val held = if (result == null) onMain { DrawableBitmapFinder.largest(view) } else null
            if (result == null && held == null) {
                Log.i(TAG, "未取到源位图（${view.javaClass.name} ${view.width}x${view.height}，${PortalBitmapCapture.lastFailure}），保留原厂 ${old.width}x${old.height}")
                return bundle
            }
            val src = result?.bitmap ?: held!!
            val region = result?.sourceBounds?.let { r ->
                Rect(
                    r.left.roundToInt().coerceIn(0, src.width), r.top.roundToInt().coerceIn(0, src.height),
                    r.right.roundToInt().coerceIn(0, src.width), r.bottom.roundToInt().coerceIn(0, src.height),
                )
            } ?: Rect(0, 0, src.width, src.height)
            val gain = region.width().toFloat() * region.height() / (old.width.toFloat() * old.height)
            if (region.isEmpty || gain < MIN_GAIN) {
                Log.i(TAG, "源位图 ${src.width}x${src.height} 区域 ${region.width()}x${region.height()} 不比原厂 ${old.width}x${old.height} 大，保留原厂")
                return bundle
            }
            if (src.width.toLong() * src.height > MAX_SOURCE_PIXELS) {
                Log.i(TAG, "源位图 ${src.width}x${src.height} 过大，保留原厂")
                return bundle
            }
            // 硬件位图先拷成软件位图：传送门那边要裁切、OCR，硬件位图做不了
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val cropped = if (region.width() == soft.width && region.height() == soft.height) soft
                else Bitmap.createBitmap(soft, region.left, region.top, region.width(), region.height())
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(cropped.width, cropped.height))
            val out = when {
                scale < 1f -> Bitmap.createScaledBitmap(
                    cropped, (cropped.width * scale).roundToInt(), (cropped.height * scale).roundToInt(), true,
                )
                // 不能把来源应用自己的位图直接交出去（它随时可能复用 / 回收）
                cropped === src -> src.copy(Bitmap.Config.ARGB_8888, false)
                else -> cropped
            }
            // 中间副本用完即回收；绝不回收来源应用自己的 src
            if (soft !== src && soft !== out && soft !== cropped) soft.recycle()
            if (cropped !== src && cropped !== out && cropped !== soft) cropped.recycle()
            bundle.putParcelable(key, out)
            Log.i(
                TAG,
                "已换成源位图[$key]：原厂 ${old.width}x${old.height} → ${out.width}x${out.height}" +
                    "（源 ${src.width}x${src.height} ${src.config} 来自=${if (result != null) "画布拦截" else "Drawable 树"}）",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "源位图替换失败，保留原厂结果", t)
        }
        return bundle
    }

    /**
     * 诊断：长按落在视频控件、WebView，或原厂没给位图时，原厂抓取结果长什么样 ——
     * 决定长按能不能接上全局识别的视频截帧与网页取图。只记键名、类型和控件结构，不记内容。
     */
    private fun logShape(view: View, bundle: Bundle) {
        @Suppress("DEPRECATION")
        val keys = bundle.keySet().joinToString { k -> "$k:${bundle.get(k)?.javaClass?.simpleName}" }
        var videos = 0
        var webs = 0
        fun visit(v: View, depth: Int) {
            if (VideoFrames.isVideo(v)) videos++
            if (v is android.webkit.WebView) webs++
            if (v is android.view.ViewGroup && depth < 12) for (i in 0 until v.childCount) visit(v.getChildAt(i), depth + 1)
        }
        visit(view, 0)
        Log.i(TAG, "长按结果形态：${view.javaClass.name} ${view.width}x${view.height}，子树视频控件 $videos、WebView $webs，键=[$keys]")
    }

    /** 诊断：规则取到的地址字段是否有值（只记是否为空与长度，不记内容）。 */
    private fun logUrls(bundle: Bundle) {
        @Suppress("DEPRECATION")
        val urls = bundle.keySet().filter { it.endsWith("/String") }
            .joinToString(" ") { k -> "${k.substringBeforeLast("/String").substringAfterLast("/")}=${(bundle.get(k) as? String)?.length ?: 0}" }
        if (urls.isNotEmpty()) Log.i(TAG, "规则地址字段（长度）：$urls")
    }

    private fun <T> onMain(block: () -> T?): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var value: T? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                value = block()
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) value else null
    }
}
