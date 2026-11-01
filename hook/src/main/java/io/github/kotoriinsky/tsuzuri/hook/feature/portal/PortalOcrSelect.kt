package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 「提取文字」的全屏自由选字：图片铺满全屏，端侧 OCR 的逐字框叠在图上，
 * 拖两端蓝色手柄选范围，点一下选中整行，复制只复制选中的部分。
 *
 * OCR 只走太乙端侧 `OcrAbility.doOCR(bitmap)`（宿主 `ContentExtentionOcrUtils.ocrAbility`），
 * 不经宿主 `getOcrResult` 的云端回落分支。返回结构：
 * `paragraphs[] → lines[] → Character[]`，每个字带 `location.box` / `poly`。
 *
 * 窗口：沿用传送门浮窗自己的窗口类型（从卡片根 View 的 LayoutParams 取），
 * 新开一个全屏窗口盖在上面；返回键 / 关闭按钮 / 复制后都会移除。
 */
internal object PortalOcrSelect {

    private const val TAG = "FP-PortalOcr"
    private val main = Handler(Looper.getMainLooper())

    /** 模块日志出口（安装时由 [PortalImageActions] 接上）；`android.util.Log` 在宿主进程里看不到。 */
    @Volatile
    var log: ((Int, String) -> Unit)? = null

    private fun i(msg: String) { log?.invoke(Log.INFO, "[$TAG] $msg") ?: Log.i(TAG, msg) }
    private fun w(msg: String, t: Throwable? = null) {
        log?.invoke(Log.WARN, "[$TAG] $msg${t?.let { "：$it" } ?: ""}") ?: Log.w(TAG, msg, t)
    }

    /** 一个字：文字、在**位图坐标**里的框、所属行号（换行用）。 */
    class Glyph(val text: String, val box: RectF, val line: Int)

    /**
     * 后台线程调用。[ocrUtils] 是宿主 `ContentExtentionOcrUtils` 单例；
     * 拿不到端侧能力或识别失败返回 null（**不回落云端**）。
     */
    fun recognize(ocrUtils: Any, bitmap: Bitmap): List<Glyph>? {
        val ability = try {
            ocrUtils.javaClass.getDeclaredField("ocrAbility").apply { isAccessible = true }.get(ocrUtils)
        } catch (t: Throwable) {
            w("取 ocrAbility 失败", t)
            return null
        } ?: return null
        val supported = try {
            ability.javaClass.getMethod("isSupport").invoke(ability) as? Boolean ?: false
        } catch (_: Throwable) {
            true // 没有这个方法就直接试 doOCR
        }
        if (!supported) {
            i("本机端侧 OCR isSupport=false")
            return null
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        val result = try {
            ability.javaClass.getMethod("doOCR", Bitmap::class.java).invoke(ability, bitmap)
        } catch (t: Throwable) {
            w("doOCR 失败", t)
            return null
        } ?: return null
        val glyphs = parse(result, bitmap.width, bitmap.height)
        i("端侧 OCR ${bitmap.width}x${bitmap.height}：${glyphs.size} 字，耗时 ${android.os.SystemClock.uptimeMillis() - t0}ms")
        return glyphs
    }

    private fun parse(result: Any, bw: Int, bh: Int): List<Glyph> {
        val out = ArrayList<Glyph>()
        var lineNo = 0
        var sampled = false
        for (para in arrayField(result, "paragraphs")) {
            for (line in arrayField(para, "lines")) {
                val lineBox = locationRect(field(line, "location"), bw, bh)
                val chars = arrayField(line, "Character")
                val lineText = field(line, "line_text") as? String ?: ""
                if (!sampled) {
                    sampled = true
                    val loc = field(line, "location")
                    i("样本行「${lineText.take(12)}」box=${(field(loc, "box") as? FloatArray)?.contentToString()} poly=${(field(loc, "poly") as? FloatArray)?.contentToString()}")
                }
                if (lineNo == 0 && chars.isNotEmpty()) {
                    val loc = field(chars[0], "location")
                    i("样本字「${field(chars[0], "char_text")}」box=${(field(loc, "box") as? FloatArray)?.contentToString()} poly=${(field(loc, "poly") as? FloatArray)?.contentToString()} 位图=${bw}x$bh")
                }
                if (chars.isNotEmpty()) {
                    for (c in chars) {
                        val t = field(c, "char_text") as? String ?: continue
                        val r = locationRect(field(c, "location"), bw, bh) ?: continue
                        out += Glyph(t, r, lineNo)
                    }
                } else if (lineBox != null && lineText.isNotEmpty()) {
                    // 没有逐字框时按行框均分，至少能拖选
                    val w = lineBox.width() / lineText.length
                    lineText.forEachIndexed { i, ch ->
                        out += Glyph(ch.toString(), RectF(lineBox.left + w * i, lineBox.top, lineBox.left + w * (i + 1), lineBox.bottom), lineNo)
                    }
                }
                lineNo++
            }
        }
        return out
    }

    /**
     * 把 `Location` 转成位图坐标的矩形。优先用 `poly`（四点八个数，取外包），
     * 否则 `box` 按 `[x1,y1,x2,y2]` 读（右下小于左上时按 `[x,y,w,h]`）。
     * 全部数值 ≤ 1.5 视为归一化坐标，乘以位图尺寸。格式未在真机核实，首行会打样本日志。
     */
    private fun locationRect(loc: Any?, bw: Int, bh: Int): RectF? {
        loc ?: return null
        val poly = field(loc, "poly") as? FloatArray
        val box = field(loc, "box") as? FloatArray
        val r = when {
            poly != null && poly.size >= 8 -> {
                val xs = poly.filterIndexed { i, _ -> i % 2 == 0 }
                val ys = poly.filterIndexed { i, _ -> i % 2 == 1 }
                RectF(xs.min(), ys.min(), xs.max(), ys.max())
            }
            box != null && box.size >= 4 ->
                if (box[2] > box[0] && box[3] > box[1]) RectF(box[0], box[1], box[2], box[3])
                else RectF(box[0], box[1], box[0] + box[2], box[1] + box[3])
            else -> return null
        }
        if (r.right <= 1.5f && r.bottom <= 1.5f) {
            r.left *= bw; r.right *= bw; r.top *= bh; r.bottom *= bh
        }
        return if (r.width() > 0 && r.height() > 0) r else null
    }

    private fun field(o: Any?, name: String): Any? = try {
        o?.javaClass?.getField(name)?.get(o)
    } catch (_: Throwable) {
        null
    }

    private fun arrayField(o: Any?, name: String): Array<*> = field(o, name) as? Array<*> ?: emptyArray<Any>()

    // ── 全屏窗口 ─────────────────────────────────────────────────────────

    /** 主线程调用。[anchor] 是卡片里任意一个 View，用来取浮窗的窗口类型与 token。 */
    /** [bitmap] 归本窗口所有：关闭或打开失败时由这里回收，调用方传进来后不要再用。 */
    fun show(anchor: View, bitmap: Bitmap, glyphs: List<Glyph>) {
        val context = anchor.context
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val hostLp = anchor.rootView.layoutParams as? WindowManager.LayoutParams
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            hostLp?.type ?: WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            token = hostLp?.token
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "FP-PortalOcrSelect"
        }
        val root = Root(context)
        val sel = SelectView(context, bitmap, glyphs, sourceRect(anchor, bitmap))
        lateinit var sheet: View
        var closing = false
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_MS
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                sel.progress = t
                root.background?.alpha = (255 * t).toInt()
                sheet.translationY = (1 - t) * sheet.height.coerceAtLeast(1)
            }
        }
        val close = {
            if (!closing) {
                closing = true
                anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        try { wm.removeView(root) } catch (_: Throwable) {}
                        // 这张图是调用方专门复制给本窗口的，窗口没了就没人再画它
                        bitmap.recycle()
                    }
                })
                anim.duration = ANIM_OUT_MS
                anim.reverse()
            }
        }
        root.onBack = close
        sel.onOutsideTap = close
        root.addView(sel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sheet = bar(context, sel, close)
        sheet.translationY = 10_000f
        root.addView(
            sheet,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        try {
            wm.addView(root, lp)
            root.requestFocus()
            root.background?.alpha = 0
            // 等第一帧布局完（拿到 sheet 高度、窗口在屏幕上的偏移）再起动画
            root.post { anim.start() }
        } catch (t: Throwable) {
            w("全屏选字窗口打开失败（type=${lp.type}）", t)
            Toast.makeText(context, "无法打开选字界面", Toast.LENGTH_SHORT).show()
            bitmap.recycle()
        }
    }

    /** 进场：放慢到能看清「图从卡片里放大出来」，眼睛有个跟随过程。 */
    private const val ANIM_MS = 560L

    /** 退场略快于进场：用户已经决定离开，不必等。 */
    private const val ANIM_OUT_MS = 420L

    /** 卡片里那张图（`capture_image`，fitCenter）在屏幕上实际画出来的矩形；取不到返回 null（改为淡入）。 */
    private fun sourceRect(anchor: View, bitmap: Bitmap): RectF? = try {
        val c = anchor.context
        val id = c.resources.getIdentifier("capture_image", "id", c.packageName)
        val v = if (id != 0) anchor.rootView.findViewById<View>(id) else null
        if (v == null || v.width == 0 || v.height == 0) null else {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val s = minOf(v.width / bitmap.width.toFloat(), v.height / bitmap.height.toFloat())
            val w = bitmap.width * s
            val h = bitmap.height * s
            val l = loc[0] + (v.width - w) / 2
            val t = loc[1] + (v.height - h) / 2
            RectF(l, t, l + w, t + h)
        }
    } catch (_: Throwable) {
        null
    }

    private class Root(context: Context) : FrameLayout(context) {
        var onBack: (() -> Unit)? = null

        init {
            isFocusableInTouchMode = true
            setBackgroundColor(0xF0101010.toInt())
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /**
     * 底部面板：白底上圆角（与传送门面板同一种样子），全选 / 复制 / 分享三格，
     * 图标复用传送门按钮那套细线自绘图标，排法与卡片按钮一致（图标在左、文字在右）。
     * 关闭走返回键或点图片以外的空白处。
     */
    private fun bar(context: Context, sel: SelectView, close: () -> Unit): View {
        val d = context.resources.displayMetrics.density
        val ink = 0xFF1F1F1F.toInt()
        val iconSide = (21.82f * d).toInt()
        fun btn(text: String, icon: PortalImageActions.PortalIcon, onClick: () -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (16 * d).toInt(), 0, (16 * d).toInt())
            addView(
                android.widget.ImageView(context).apply {
                    setImageDrawable(PortalImageActions.PortalStrokeIcon(icon, ink, iconSide))
                },
                LinearLayout.LayoutParams(iconSide, iconSide).apply { marginEnd = (6 * d).toInt() },
            )
            addView(TextView(context).apply {
                this.text = text
                setTextColor(ink)
                textSize = 15f
            })
            setOnClickListener { onClick() }
        }
        fun withText(block: (String) -> Unit) {
            val text = sel.selectedText()
            if (text.isEmpty()) Toast.makeText(context, "请选择文字", Toast.LENGTH_SHORT).show() else block(text)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 与传送门面板同一种样子：白底、上沿圆角
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                val r = 24 * d
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(0, (8 * d).toInt(), 0, (24 * d).toInt())
            val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            addView(btn("全选", PortalImageActions.PortalIcon.SELECT_ALL) { sel.selectAll() }, lp())
            addView(
                btn("复制", PortalImageActions.PortalIcon.COPY) {
                    withText { text ->
                        try {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("text", text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            close()
                        } catch (t: Throwable) {
                            // 失败时不关页：原来静默关掉，用户以为复制成功了，选区也丢了。
                            // 留在原页，用户还能改用「分享」把文字带走。
                            w("写剪贴板失败", t)
                            Toast.makeText(context, "复制失败，可以试试分享", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                lp(),
            )
            addView(
                btn("分享", PortalImageActions.PortalIcon.SHARE) {
                    withText { text ->
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        try {
                            context.startActivity(
                                android.content.Intent.createChooser(send, "分享文字")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            close()
                        } catch (t: Throwable) {
                            w("分享文字失败", t)
                        }
                    }
                },
                lp(),
            )
        }
    }

    /**
     * 图片 + 字框 + 选区。坐标约定：[glyphs] 在位图坐标，绘制时经 [scale]/[ox]/[oy] 映射到视图。
     * 选区是字序上的闭区间 [selStart, selEnd]；-1 表示未选。
     */
    @SuppressLint("ViewConstructor")
    private class SelectView(
        context: Context,
        val bitmap: Bitmap,
        val glyphs: List<Glyph>,
        /** 动画起点（屏幕坐标）；null 时只淡入。 */
        private val fromScreen: RectF?,
    ) : View(context) {
        /** 进出场进度 0..1：图从卡片原位放大到全屏位置，字框在后半段淡入。 */
        var progress = 0f
            set(v) { field = v; invalidate() }

        /** 点图片以外的空白处：关闭。 */
        var onOutsideTap: (() -> Unit)? = null

        private fun imageRect() = RectF(ox, oy, ox + bitmap.width * scale, oy + bitmap.height * scale)
        private val d = context.resources.displayMetrics.density
        private var scale = 1f
        private var ox = 0f
        private var oy = 0f
        private var selStart = -1
        private var selEnd = -1
        private var dragging = 0 // 0 无，1 起点手柄，2 终点手柄，3 新拖选

        private val blue = 0xFF3482FF.toInt()
        private val hintPaint = Paint().apply { color = 0x26FFFFFF }
        private val selPaint = Paint().apply { color = 0x663482FF }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
        private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; strokeWidth = 2 * d }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val handleR = 9 * d
        private val MAX_ZOOM = 5f
        private val touchSlop = 28 * d
        private val tmp = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val top = 48 * d
            val bottom = 96 * d
            val availH = h - top - bottom
            baseScale = minOf(w / bitmap.width.toFloat(), availH / bitmap.height.toFloat())
            baseOx = (w - bitmap.width * baseScale) / 2
            baseOy = top + (availH - bitmap.height * baseScale) / 2
            applyZoom()
        }

        // ── 双指缩放 / 单指平移（放大后）。zoom=1 时与原先完全一致。 ──
        private var baseScale = 1f
        private var baseOx = 0f
        private var baseOy = 0f
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var pinching = false
        private var lastFocusX = 0f
        private var lastFocusY = 0f
        private val scaleDetector = android.view.ScaleGestureDetector(
            context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(det: android.view.ScaleGestureDetector): Boolean {
                    pinching = true
                    dragging = -1
                    lastFocusX = det.focusX; lastFocusY = det.focusY
                    return true
                }

                override fun onScale(det: android.view.ScaleGestureDetector): Boolean {
                    val nz = (zoom * det.scaleFactor).coerceIn(1f, MAX_ZOOM)
                    val f = nz / zoom
                    // 以手指焦点为中心缩放，同时跟随焦点平移
                    panX = det.focusX - (lastFocusX - panX - baseOx) * f - baseOx
                    panY = det.focusY - (lastFocusY - panY - baseOy) * f - baseOy
                    zoom = nz
                    lastFocusX = det.focusX; lastFocusY = det.focusY
                    applyZoom()
                    return true
                }
            },
        )

        /** 由 base + zoom + pan 算出生效的 scale/ox/oy，并把图限制在不露出多余空白。 */
        private fun applyZoom() {
            scale = baseScale * zoom
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val bw = bitmap.width * baseScale
            val bh = bitmap.height * baseScale
            // 放大后图比原框大多少，就最多能平移多少
            val mx = (w - bw).coerceAtLeast(0f)
            val my = (h - bh).coerceAtLeast(0f)
            panX = panX.coerceIn(-mx, 0f)
            panY = panY.coerceIn(-my, 0f)
            ox = baseOx + panX
            oy = baseOy + panY
            invalidate()
        }

        private fun mapped(g: Glyph): RectF {
            tmp.set(ox + g.box.left * scale, oy + g.box.top * scale, ox + g.box.right * scale, oy + g.box.bottom * scale)
            return tmp
        }

        override fun onDraw(canvas: Canvas) {
            if (bitmap.isRecycled) return
            val target = RectF(ox, oy, ox + bitmap.width * scale, oy + bitmap.height * scale)
            val from = fromScreen?.let { f ->
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                RectF(f.left - loc[0], f.top - loc[1], f.right - loc[0], f.bottom - loc[1])
            }
            val p = progress
            if (from != null) {
                fun lerp(a: Float, b: Float) = a + (b - a) * p
                bmpPaint.alpha = 255
                canvas.drawBitmap(bitmap, null, RectF(lerp(from.left, target.left), lerp(from.top, target.top), lerp(from.right, target.right), lerp(from.bottom, target.bottom)), bmpPaint)
            } else {
                bmpPaint.alpha = (255 * p).toInt()
                canvas.drawBitmap(bitmap, null, target, bmpPaint)
            }
            if (p < 0.6f) return
            val overlayAlpha = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
            hintPaint.alpha = (0x26 * overlayAlpha).toInt()
            selPaint.alpha = (0x66 * overlayAlpha).toInt()
            handlePaint.alpha = (255 * overlayAlpha).toInt()
            stemPaint.alpha = handlePaint.alpha
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            glyphs.forEachIndexed { i, g ->
                canvas.drawRect(mapped(g), if (lo >= 0 && i in lo..hi) selPaint else hintPaint)
            }
            if (lo >= 0) {
                drawHandle(canvas, mapped(glyphs[lo]).let { RectF(it) }, start = true)
                drawHandle(canvas, mapped(glyphs[hi]).let { RectF(it) }, start = false)
            }
        }

        private fun drawHandle(canvas: Canvas, r: RectF, start: Boolean) {
            val x = if (start) r.left else r.right
            canvas.drawLine(x, r.top, x, r.bottom, stemPaint)
            canvas.drawCircle(x, if (start) r.top - handleR * 0.6f else r.bottom + handleR * 0.6f, handleR, handlePaint)
        }

        private fun handlePos(start: Boolean): Pair<Float, Float>? {
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            if (lo < 0) return null
            val r = RectF(mapped(glyphs[if (start) lo else hi]))
            return if (start) r.left to r.top - handleR * 0.6f else r.right to r.bottom + handleR * 0.6f
        }

        /** 离 (x,y) 最近的字；同一行优先（纵向距离权重更大），拖动时手感才稳。 */
        private fun nearest(x: Float, y: Float): Int {
            var best = -1
            var bestD = Float.MAX_VALUE
            glyphs.forEachIndexed { i, g ->
                val r = mapped(g)
                val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
                val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
                val dist = dx + dy * 3
                if (dist < bestD) { bestD = dist; best = i }
            }
            return best
        }

        private fun hit(x: Float, y: Float): Int = glyphs.indexOfFirst { RectF(mapped(it)).apply { inset(-4 * d, -4 * d) }.contains(x, y) }

        private var downX = 0f
        private var downY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (glyphs.isEmpty() || progress < 1f) return true
            scaleDetector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
            if (pinching || e.pointerCount > 1) {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    // 记下剩下那根手指的位置，接着平移不跳
                    val keep = if (e.actionIndex == 0) 1 else 0
                    downX = e.getX(keep); downY = e.getY(keep)
                    pinching = false
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) { pinching = false; dragging = 0 }
                return true
            }
            if (dragging == -1) {
                // 缩放结束后剩下的那根手指：放大时当平移用，不改选区
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        panX += e.x - downX; panY += e.y - downY
                        downX = e.x; downY = e.y
                        applyZoom()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = 0
                }
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    val s = handlePos(true)
                    val t = handlePos(false)
                    dragging = when {
                        s != null && dist(e, s) < touchSlop -> 1
                        t != null && dist(e, t) < touchSlop -> 2
                        else -> 0
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val i = nearest(e.x, e.y)
                    when (dragging) {
                        1 -> { normalize(); selStart = i }
                        2 -> { normalize(); selEnd = i }
                        0 -> if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > 12 * d) {
                            // 在图上直接拖：从按下的位置开始一段新选区
                            dragging = 3
                            selStart = nearest(downX, downY)
                            selEnd = i
                        }
                        3 -> selEnd = i
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging == 0) {
                        // 点一下：点在字上选整行，点在空白处清空
                        val i = hit(e.x, e.y)
                        if (i >= 0) {
                            val line = glyphs[i].line
                            selStart = glyphs.indexOfFirst { it.line == line }
                            selEnd = glyphs.indexOfLast { it.line == line }
                        } else if (!imageRect().contains(e.x, e.y)) {
                            onOutsideTap?.invoke()
                        } else {
                            selStart = -1; selEnd = -1
                        }
                    }
                    normalize()
                    dragging = 0
                    invalidate()
                }
            }
            return true
        }

        private fun dist(e: MotionEvent, p: Pair<Float, Float>) = Math.hypot((e.x - p.first).toDouble(), (e.y - p.second).toDouble())

        private fun normalize() {
            if (selStart > selEnd) selStart = selEnd.also { selEnd = selStart }
        }

        fun selectAll() {
            selStart = 0
            selEnd = glyphs.size - 1
            invalidate()
        }

        fun selectedText(): String {
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            if (lo < 0) return ""
            val sb = StringBuilder()
            for (i in lo..hi) {
                if (i > lo && glyphs[i].line != glyphs[i - 1].line) {
                    // 换行：上一行末尾是连字符就接回去（英文断词），否则换行
                    if (sb.endsWith("-")) sb.setLength(sb.length - 1) else sb.append('\n')
                }
                sb.append(glyphs[i].text)
            }
            return sb.toString()
        }
    }
}
