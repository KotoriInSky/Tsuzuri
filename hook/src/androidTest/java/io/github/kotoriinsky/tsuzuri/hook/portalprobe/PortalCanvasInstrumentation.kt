package io.github.kotoriinsky.tsuzuri.hook.portalprobe

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.ImageView
import io.github.kotoriinsky.tsuzuri.capture.engine.PortalBitmapCapture
import io.github.kotoriinsky.tsuzuri.capture.engine.PortalBitmapShaderCompat
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalSourceProtocolProbe
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalRuleTranslationProbe
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalRuleRuntimeProbe

/** 不依赖第三方测试框架；结果带真实用例数，供脚本严格判断成功与失败。 */
class PortalCanvasInstrumentation : Instrumentation() {
    private var probeContext: Context? = null
    private var previewTests = false
    override fun getTargetContext(): Context = probeContext ?: super.getTargetContext()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        previewTests = arguments?.getString("preview") == "true"
        start()
    }

    override fun onStart() {
        val result = if (previewTests) PortalVideoPreviewProbe.run(this) else runCases { runOnMainSync(it) }
        finish(if (result.getInt("failures") == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun runCases(onMain: (() -> Unit) -> Unit): Bundle {
        val output = StringBuilder()
        var failures = 0
        val cases = linkedMapOf<String, () -> Unit>(
            "未展开长图保留源像素" to ::longImage,
            "ImageView中央裁切仍保留整图" to ::centerCrop,
            "绘制矩阵映射与可见范围" to ::matrixMapping,
            "圆角Shader采集或不可读回退" to ::roundShader,
            "Shader矩阵映射或不可读回退" to ::shaderMatrix,
            "Shader不可读取时不误选背景" to ::unreadableShader,
            "纹理子区域不冒充完整图片" to ::sourceRegion,
            "相邻图片按触点命中" to ::neighborImages,
            "重叠图片选择最上层" to ::overlappingImages,
            "透明图层不覆盖有效素材" to ::transparentImage,
            "恢复绘制状态后不沿用旧裁切" to ::restoreClip,
            "路径裁切不宣称精确边界" to ::pathClip,
            "绘制异常不发布半成品" to ::drawFailure,
            "回收位图不会成为候选" to ::recycledImage,
            "纯色视图不会变成图片" to ::solidView,
            "空控件与越界触点拒绝采集" to ::invalidTarget,
            "采集不改动源位图与View尺寸" to ::keepsSource,
            "绘制递归不会重复采集" to ::recursiveCapture,
            "后台线程不操作View" to ::backgroundCapture,
            "候选超限不发布截断结果" to ::tooManyImages,
        )
        for ((name, test) in cases) {
            try {
                var error: Throwable? = null
                onMain { try { test() } catch (e: Throwable) { error = e } }
                error?.let { throw it }
                output.append("PASS $name\n")
            } catch (e: Throwable) {
                failures++
                output.append("FAIL $name: ${e.javaClass.simpleName}: ${e.message}\n")
            }
        }
        return Bundle().apply {
            putInt("tests", cases.size)
            putInt("failures", failures)
            putString("stream", output.toString())
            image { bitmap -> putBoolean("shaderReader", PortalBitmapShaderCompat.bitmap(
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)) === bitmap) }
        }
    }

    companion object {
        /** 独立 app_process 探针不设置 hidden_api_policy，用来区分访问限制与采集逻辑。 */
        @JvmStatic
        fun main(args: Array<String>) {
            if (args.size == 4 && args[0] == "config") {
                PortalRuleRuntimeProbe.configs(io.github.kotoriinsky.tsuzuri.hook.compat.PortalMediaHostCompat.Page(
                    args[1], args[2].toInt(), args[3]))
                return
            }
            if (args.contentEquals(arrayOf("config"))) {
                PortalRuleRuntimeProbe.configs()
                return
            }
            if (args.size == 2) {
                PortalRuleRuntimeProbe.audit(args[0], java.io.File(args[1]).readText())
                return
            }
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
            val context = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
            if (args.contentEquals(arrayOf("bitmap-source"))) {
                PortalSourceProtocolProbe.verify(context)
                return
            }
            val probe = PortalCanvasInstrumentation().apply { probeContext = context }
            val result = probe.runCases { it() }
            print(result.getString("stream"))
            println("RESULT tests=${result.getInt("tests")} failures=${result.getInt("failures")} shaderReader=${result.getBoolean("shaderReader")}")
            check(result.getInt("tests") == 20 && result.getInt("failures") == 0)
            PortalSourceProtocolProbe.verify(context)
            PortalRuleTranslationProbe.verify(context)
            PortalRuleRuntimeProbe.verify(context)
        }
    }

    private fun view(width: Int = 100, height: Int = 80, draw: (Canvas) -> Unit): View =
        object : View(targetContext) { override fun onDraw(canvas: Canvas) = draw(canvas) }
            .apply { layout(0, 0, width, height) }

    private inline fun image(width: Int = 100, height: Int = 400, test: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.density = targetContext.resources.displayMetrics.densityDpi
        try { bitmap.eraseColor(Color.RED); test(bitmap) } finally { bitmap.recycle() }
    }

    private fun requireCapture(view: View, x: Float = 25f, y: Float = 25f) =
        checkNotNull(PortalBitmapCapture.capture(view, x, y)) { "没有取得目标源素材" }

    private fun centerCrop() = image(100, 6000) { bitmap ->
        val target = ImageView(targetContext).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layout(0, 0, 100, 80)
        }
        val result = requireCapture(target)
        check(result.bitmap === bitmap && result.bitmap.height == 6000)
        check(result.visibleBounds == RectF(0f, 0f, 100f, 80f))
        check(result.usesWholeBitmap)
    }

    private fun matrixMapping() = image(200, 300) { bitmap ->
        val result = requireCapture(view(120, 200) { canvas ->
            val saved = canvas.save()
            canvas.translate(10f, 20f)
            canvas.scale(0.5f, 0.5f)
            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, 200f, 300f), Paint())
            canvas.restoreToCount(saved)
        })
        check(result.bitmap === bitmap)
        check(result.visibleBounds == RectF(10f, 20f, 110f, 170f)) { "矩阵后的可见范围错误：${result.visibleBounds}" }
        val point = floatArrayOf(200f, 300f)
        result.bitmapToView.mapPoints(point)
        check(point.contentEquals(floatArrayOf(110f, 170f)))
    }

    private fun roundShader() = image { bitmap ->
        val paint = Paint().apply { shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val target = view { it.drawRoundRect(RectF(0f, 0f, 80f, 60f), 12f, 12f, paint) }
        if (PortalBitmapShaderCompat.bitmap(paint.shader) == null) {
            check(PortalBitmapCapture.capture(target, 25f, 25f) == null)
            return@image
        }
        val result = requireCapture(target)
        check(result.bitmap === bitmap && result.fromShader && result.usesWholeBitmap)
        check(!result.boundsAreExact)
        check(result.visibleBounds == RectF(0f, 0f, 80f, 60f))
    }

    private fun shaderMatrix() = image { bitmap ->
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(0.5f, 0.5f); postTranslate(10f, 10f) })
        }
        val target = view { it.drawRect(RectF(10f, 10f, 60f, 60f), Paint().apply { this.shader = shader }) }
        if (PortalBitmapShaderCompat.bitmap(shader) == null) {
            check(PortalBitmapCapture.capture(target, 25f, 25f) == null)
            return@image
        }
        val result = requireCapture(target)
        val point = floatArrayOf(100f, 100f)
        result.bitmapToView.mapPoints(point)
        check(point.contentEquals(floatArrayOf(60f, 60f)))
    }

    private fun unreadableShader() = image { background -> image { foreground ->
        val shader = BitmapShader(foreground, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val result = PortalBitmapCapture.capture(view { canvas ->
            canvas.drawBitmap(background, 0f, 0f, Paint())
            canvas.drawRect(0f, 0f, 80f, 80f, Paint().apply { this.shader = shader })
        }, 25f, 25f)
        if (PortalBitmapShaderCompat.bitmap(shader) == null) check(result == null) { "不可读前景导致误选背景" }
        else check(result?.bitmap === foreground)
    } }

    private fun sourceRegion() = image(64, 64) { bitmap ->
        val result = requireCapture(view { it.drawBitmap(bitmap, Rect(16, 16, 48, 48), RectF(0f, 0f, 80f, 80f), Paint()) })
        check(result.bitmap === bitmap && !result.usesWholeBitmap)
        check(result.sourceBounds == RectF(16f, 16f, 48f, 48f))
    }

    private fun neighborImages() = image { first -> image { second ->
        val result = requireCapture(view { canvas ->
            canvas.drawBitmap(first, null, RectF(0f, 0f, 50f, 50f), Paint())
            canvas.drawBitmap(second, null, RectF(60f, 0f, 100f, 40f), Paint())
        })
        check(result.bitmap === first) { "错配到相邻图片" }
    } }

    private fun overlappingImages() = image { first -> image { second ->
        val result = requireCapture(view { canvas ->
            canvas.drawBitmap(first, null, RectF(0f, 0f, 80f, 80f), Paint())
            canvas.drawBitmap(second, null, RectF(10f, 10f, 40f, 40f), Paint())
        })
        check(result.bitmap === second)
    } }

    private fun transparentImage() = image { first -> image { second ->
        val result = requireCapture(view { canvas ->
            canvas.drawBitmap(first, 0f, 0f, Paint())
            canvas.drawBitmap(second, 0f, 0f, Paint().apply { alpha = 0 })
        })
        check(result.bitmap === first)
    } }

    private fun restoreClip() = image { bitmap ->
        val result = requireCapture(view { canvas ->
            val saved = canvas.save()
            canvas.clipRect(Rect(0, 0, 10, 10))
            canvas.drawBitmap(bitmap, 0f, 0f, Paint())
            canvas.restoreToCount(saved)
            canvas.drawBitmap(bitmap, 0f, 0f, Paint())
        })
        check(result.visibleBounds == RectF(0f, 0f, 100f, 80f))
    }

    private fun pathClip() = image { bitmap ->
        val result = requireCapture(view { canvas ->
            canvas.clipPath(Path().apply { addCircle(30f, 30f, 20f, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, 0f, 0f, Paint())
        })
        check(!result.boundsAreExact)
    }

    private fun drawFailure() = image { bitmap ->
        val result = PortalBitmapCapture.capture(view { canvas ->
            canvas.drawBitmap(bitmap, 0f, 0f, Paint())
            throw IllegalStateException("合成控件绘制失败")
        }, 25f, 25f)
        check(result == null)
    }

    private fun recycledImage() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        check(PortalBitmapCapture.capture(view { it.drawBitmap(bitmap, 0f, 0f, Paint()) }, 25f, 25f) == null)
    }

    private fun solidView() {
        check(PortalBitmapCapture.capture(view { it.drawRect(0f, 0f, 80f, 80f, Paint()) }, 25f, 25f) == null)
    }

    private fun invalidTarget() {
        val target = View(targetContext)
        check(PortalBitmapCapture.capture(target, 0f, 0f) == null)
        image { bitmap ->
            val drawn = view { it.drawBitmap(bitmap, 0f, 0f, Paint()) }
            check(PortalBitmapCapture.capture(drawn, -1f, 25f) == null)
            check(PortalBitmapCapture.capture(drawn, Float.NaN, 25f) == null)
            check(PortalBitmapCapture.capture(drawn, 100f, 25f) == null)
        }
    }

    private fun keepsSource() = image { bitmap ->
        bitmap.setPixel(99, 399, Color.BLUE)
        val target = view { it.drawBitmap(bitmap, 0f, 0f, Paint()) }
        repeat(3) { check(requireCapture(target).bitmap === bitmap) }
        check(!bitmap.isRecycled && bitmap.getPixel(99, 399) == Color.BLUE)
        check(target.width == 100 && target.height == 80)
    }

    private fun recursiveCapture() = image { bitmap ->
        val target = object : View(targetContext) {
            override fun onDraw(canvas: Canvas) {
                check(PortalBitmapCapture.capture(this, 25f, 25f) == null)
                canvas.drawBitmap(bitmap, 0f, 0f, Paint())
            }
        }.apply { layout(0, 0, 100, 80) }
        check(requireCapture(target).bitmap === bitmap)
    }

    private fun backgroundCapture() = image { bitmap ->
        val target = view { it.drawBitmap(bitmap, 0f, 0f, Paint()) }
        var result: PortalBitmapCapture.Result? = null
        val worker = Thread { result = PortalBitmapCapture.capture(target, 25f, 25f) }
        worker.start()
        worker.join(1000)
        check(!worker.isAlive && result == null)
    }

    private fun tooManyImages() = image { bitmap ->
        check(PortalBitmapCapture.capture(view { canvas ->
            repeat(129) { canvas.drawBitmap(bitmap, 0f, 0f, Paint()) }
        }, 25f, 25f) == null)
    }

    private fun capture(view: View): Any {
        val type = try {
            Class.forName("io.github.kotoriinsky.tsuzuri.capture.engine.PortalBitmapCapture")
        } catch (_: ClassNotFoundException) {
            throw AssertionError("源 Bitmap / Canvas 采集入口尚未实现")
        }
        return type.getMethod("capture", View::class.java, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType).invoke(null, view, 25f, 25f)
            ?: throw AssertionError("没有取得目标源 Bitmap")
    }

    private fun longImage() {
        val bitmap = Bitmap.createBitmap(100, 6000, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            bitmap.setPixel(99, 5999, Color.BLUE)
            val view = object : View(targetContext) {
                override fun onDraw(canvas: Canvas) { canvas.drawBitmap(bitmap, 0f, 0f, Paint()) }
            }
            view.layout(0, 0, 100, 80)
            val raster = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(raster))
                check(raster.height == 80)
                val result = capture(view)
                val source = result.javaClass.getMethod("getBitmap").invoke(result) as Bitmap
                check(source.width == 100 && source.height == 6000) { "源长图被显示高度裁切" }
                check(source.getPixel(99, 5999) == Color.BLUE) { "不可见尾部像素丢失" }
            } finally { raster.recycle() }
        } finally { bitmap.recycle() }
    }
}
