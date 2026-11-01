package io.github.kotoriinsky.tsuzuri.capture.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region

/**
 * 只记录绘制输入，不分配与 View 同尺寸的输出图，不放大、裁切或拼接源 Bitmap。
 * 坐标均为传入 View 的本地坐标。候选借用应用的像素，调用方不能回收该 Bitmap。
 * 必须在持有目标 View 的来源进程执行；消费进程的 Bitmap 不能替代此入口。
 */
@Suppress("DEPRECATION")
internal class PortalBitmapCanvas(private val viewWidth: Int, private val viewHeight: Int) : Canvas() {
    data class Sample(
        val bitmap: Bitmap,
        val sourceBounds: RectF,
        val visibleBounds: RectF,
        val bitmapToView: Matrix,
        val fromShader: Boolean,
        val boundsAreExact: Boolean,
    ) {
        val usesWholeBitmap: Boolean get() = sourceBounds == RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        fun contains(x: Float, y: Float): Boolean {
            if (!visibleBounds.contains(x, y)) return false
            val inverse = Matrix()
            if (!bitmapToView.invert(inverse)) return false
            val point = floatArrayOf(x, y)
            inverse.mapPoints(point)
            return sourceBounds.contains(point[0], point[1])
        }
    }

    /** [depth]：这次 save 之后的保存层数，restore 时按它对账。 */
    private data class State(val clip: RectF, val alpha: Int, val exact: Boolean, val depth: Int)
    private var clip = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    private var alpha = 255
    private var exact = true
    private val states = ArrayList<State>()
    val samples = ArrayList<Sample>()
    var unsupported = false
        private set(value) {
            // 记下第一次判定「不支持」的绘制调用名，诊断用（B 站封面为何取不到）
            if (value && !field) reason = Throwable().stackTrace.getOrNull(1)?.methodName
            field = value
        }
    /** 第一次判为不支持的那个绘制调用（方法名），没有则为 null。 */
    var reason: String? = null
        private set

    override fun getWidth() = viewWidth
    override fun getHeight() = viewHeight
    override fun save(): Int {
        val count = super.save()
        states.add(State(RectF(clip), alpha, exact, getSaveCount()))
        return count
    }
    /**
     * 只有弹出的正是我们记过的那一层才恢复状态：框架还有不经这些重载的保存（View 淡出边缘的
     * `saveUnclippedLayer` 等），按「有 restore 就弹一个」会错位，最后抛异常，贴吧Lite 整窗记录就这样失败过（2026-10-30）。
     */
    override fun restore() {
        val depth = getSaveCount()
        super.restore()
        if (states.isEmpty() || states.last().depth != depth) return
        val state = states.removeAt(states.lastIndex)
        clip = state.clip
        alpha = state.alpha
        exact = state.exact
    }
    override fun restoreToCount(saveCount: Int) {
        require(saveCount in 1..getSaveCount())
        while (getSaveCount() > saveCount) restore()
    }
    override fun saveLayer(bounds: RectF?, paint: Paint?): Int = save().also {
        alpha = alpha * (paint?.alpha ?: 255) / 255
    }
    override fun saveLayer(bounds: RectF?, paint: Paint?, flags: Int) = saveLayer(bounds, paint)
    override fun saveLayer(l: Float, t: Float, r: Float, b: Float, paint: Paint?) = saveLayer(null, paint)
    override fun saveLayer(l: Float, t: Float, r: Float, b: Float, paint: Paint?, flags: Int) = saveLayer(null, paint)
    override fun saveLayerAlpha(bounds: RectF?, alpha: Int) = save().also { this.alpha = this.alpha * alpha.coerceIn(0, 255) / 255 }
    override fun saveLayerAlpha(bounds: RectF?, alpha: Int, flags: Int) = saveLayerAlpha(bounds, alpha)
    override fun saveLayerAlpha(l: Float, t: Float, r: Float, b: Float, alpha: Int) = saveLayerAlpha(null, alpha)
    override fun saveLayerAlpha(l: Float, t: Float, r: Float, b: Float, alpha: Int, flags: Int) = saveLayerAlpha(null, alpha)

    private fun matrix() = Matrix().also { super.getMatrix(it) }
    private fun mapped(bounds: RectF) = RectF(bounds).also { matrix().mapRect(it) }
    override fun getClipBounds(bounds: Rect): Boolean {
        val inverse = Matrix()
        if (!matrix().invert(inverse) || clip.isEmpty) { bounds.setEmpty(); return false }
        RectF(clip).apply { inverse.mapRect(this); roundOut(bounds) }
        return !bounds.isEmpty
    }
    override fun clipRect(l: Float, t: Float, r: Float, b: Float): Boolean {
        val transformed = mapped(RectF(l, t, r, b))
        if (!clip.intersect(transformed)) clip.setEmpty()
        if (!matrix().rectStaysRect()) exact = false
        return !clip.isEmpty
    }
    override fun clipRect(rect: Rect) = clipRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    override fun clipRect(rect: RectF) = clipRect(rect.left, rect.top, rect.right, rect.bottom)
    override fun clipRect(l: Int, t: Int, r: Int, b: Int) = clipRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    override fun clipRect(l: Float, t: Float, r: Float, b: Float, op: Region.Op): Boolean {
        if (op != Region.Op.INTERSECT) { unsupported = true; return false }
        return clipRect(l, t, r, b)
    }
    override fun clipRect(rect: Rect, op: Region.Op) = clipRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), op)
    override fun clipRect(rect: RectF, op: Region.Op) = clipRect(rect.left, rect.top, rect.right, rect.bottom, op)
    override fun clipPath(path: Path): Boolean {
        exact = false
        val bounds = RectF()
        path.computeBounds(bounds, true)
        return clipRect(bounds)
    }
    override fun clipPath(path: Path, op: Region.Op): Boolean {
        if (op != Region.Op.INTERSECT) { unsupported = true; return false }
        return clipPath(path)
    }
    override fun clipOutRect(l: Float, t: Float, r: Float, b: Float): Boolean { unsupported = true; return false }
    override fun clipOutRect(rect: Rect) = clipOutRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    override fun clipOutRect(rect: RectF) = clipOutRect(rect.left, rect.top, rect.right, rect.bottom)
    override fun clipOutRect(l: Int, t: Int, r: Int, b: Int) = clipOutRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    override fun clipOutPath(path: Path): Boolean { unsupported = true; return false }
    override fun quickReject(l: Float, t: Float, r: Float, b: Float) = !RectF.intersects(clip, mapped(RectF(l, t, r, b)))
    override fun quickReject(rect: RectF) = quickReject(rect.left, rect.top, rect.right, rect.bottom)
    override fun quickReject(path: Path) = false
    override fun quickReject(l: Float, t: Float, r: Float, b: Float, type: EdgeType) = quickReject(l, t, r, b)
    override fun quickReject(rect: RectF, type: EdgeType) = quickReject(rect)
    override fun quickReject(path: Path, type: EdgeType) = quickReject(path)

    private fun record(bitmap: Bitmap, source: RectF, transform: Matrix, bounds: RectF, paint: Paint?, shader: Boolean, shapeExact: Boolean = true) {
        if (bitmap.isRecycled || alpha == 0 || paint?.alpha == 0 || source.isEmpty) return
        if (samples.size >= 128) { unsupported = true; return }
        val visible = mapped(bounds)
        if (!visible.intersect(clip)) return
        val combined = Matrix().apply { setConcat(matrix(), transform) }
        samples.add(Sample(bitmap, RectF(source), visible, combined, shader,
            exact && shapeExact && combined.rectStaysRect()))
    }
    private fun full(bitmap: Bitmap) = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        // Canvas 的此重载会按 density 自动缩放；明确使用相同的比例记录显示位置。
        val factor = if (density != 0 && bitmap.density != 0) density.toFloat() / bitmap.density else 1f
        val transform = Matrix().apply { setScale(factor, factor); postTranslate(left, top) }
        val source = full(bitmap)
        val bounds = RectF(source).also { transform.mapRect(it) }
        record(bitmap, source, transform, bounds, paint, false)
    }
    override fun drawBitmap(bitmap: Bitmap, transform: Matrix, paint: Paint?) {
        val source = full(bitmap)
        record(bitmap, source, transform, RectF(source).also { transform.mapRect(it) }, paint, false)
    }
    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        if (bitmap.isRecycled) return
        val source = src?.let(::RectF) ?: full(bitmap)
        if (source.isEmpty || dst.isEmpty) return
        val transform = Matrix().apply { setRectToRect(source, dst, Matrix.ScaleToFit.FILL) }
        record(bitmap, source, transform, dst, paint, false)
    }
    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) = drawBitmap(bitmap, src, RectF(dst), paint)

    private fun shader(bounds: RectF, paint: Paint, shapeExact: Boolean = true) {
        if (alpha == 0 || paint.alpha == 0) return
        val input = paint.shader
        val bitmap = PortalBitmapShaderCompat.bitmap(input)
        if (bitmap == null) {
            // 不可读图片可能挡住先前的 Bitmap，不能因此把背景发布为选中的图片。
            if (input is BitmapShader && RectF.intersects(clip, mapped(bounds))) unsupported = true
            return
        }
        val local = Matrix()
        paint.shader?.getLocalMatrix(local)
        record(bitmap, full(bitmap), local, bounds, paint, true, shapeExact)
    }
    override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) = shader(RectF(l, t, r, b), paint)
    override fun drawRect(rect: Rect, paint: Paint) = shader(RectF(rect), paint)
    override fun drawRect(rect: RectF, paint: Paint) = shader(rect, paint)
    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) = shader(rect, paint, false)
    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: Paint) = shader(RectF(l, t, r, b), paint, false)
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) = shader(RectF(cx-radius, cy-radius, cx+radius, cy+radius), paint, false)
    override fun drawOval(oval: RectF, paint: Paint) = shader(oval, paint, false)
    override fun drawOval(l: Float, t: Float, r: Float, b: Float, paint: Paint) = shader(RectF(l, t, r, b), paint, false)
    override fun drawPath(path: Path, paint: Paint) = shader(RectF().also { path.computeBounds(it, true) }, paint, false)
    override fun drawPaint(paint: Paint) {
        val bounds = Rect()
        if (getClipBounds(bounds)) shader(RectF(bounds), paint)
    }
    override fun drawBitmapMesh(bitmap: Bitmap, meshWidth: Int, meshHeight: Int, verts: FloatArray, vertOffset: Int, colors: IntArray?, colorOffset: Int, paint: Paint?) { unsupported = true }
    override fun drawBitmap(colors: IntArray, offset: Int, stride: Int, x: Float, y: Float, width: Int, height: Int, hasAlpha: Boolean, paint: Paint?) { unsupported = true }
    override fun drawBitmap(colors: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int, hasAlpha: Boolean, paint: Paint?) { unsupported = true }
}
