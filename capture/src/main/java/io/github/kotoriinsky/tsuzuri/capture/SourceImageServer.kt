package io.github.kotoriinsky.tsuzuri.capture

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.webkit.WebView
import io.github.kotoriinsky.tsuzuri.capture.engine.DrawableBitmapFinder
import io.github.kotoriinsky.tsuzuri.capture.engine.PortalBitmapCapture
import io.github.kotoriinsky.tsuzuri.capture.engine.SourceImageContract
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 来源应用进程里的「按屏幕区域取源位图」接收端。
 *
 * 影子抓取器在每个 Activity 建拦截器时被实例化（contentcatcher `Interceptor` 构造里 newInstance），
 * 第一次实例化时在本进程注册一个广播接收端；全局识别对某张图点保存 / 分享 / 识图时，
 * 传送门把这张图的屏幕矩形发过来，这里找到对应的 View、取出它持有的源位图，经管道回传 PNG。
 *
 * 取图顺序：视频控件的当前帧 → 原生控件的源位图 → WebView 里那张图的原文件。
 *
 * 运行在第三方应用进程里：
 *  - 只接受持有 [SourceImageContract.SENDER_PERMISSION] 的发送方（系统签名权限）；
 *  - 只读 View 与位图，不改任何对象；位图复制后才交出，绝不回收对方的；
 *  - 任何异常都吞掉，只回一个失败原因，绝不让对方崩。
 */
object SourceImageServer {
    private const val TAG = StockFetcher.TAG

    /** 找 View 时与请求矩形的交并比门槛。 */
    private const val MIN_IOU = 0.5f

    /** 回传位图长边上限。 */
    private const val MAX_SIDE = 4096

    /** 列图片在主线程上的时间上限：超时就把已找到的先回去，不拖住来源应用。 */
    private const val LIST_BUDGET_MS = 40L

    /** 控件露出不到自身面积的这个比例就不列（与 Flyme 识屏同一门槛，`IMAGE_ON_SCREEN_THRESHOLD`）。 */
    private const val MIN_VISIBLE_RATIO = 0.6f

    /**
     * 盖住大半屏的当背景，不列 —— 除非它就是 ImageView / 视频本身（大图查看器里的那张图）：
     * 窗口那边把这种全屏图当「底图」，点在字上仍选字。
     */
    private const val MAX_SCREEN_RATIO = 0.8f

    /** 列图片时等 WebView 脚本的上限（传送门那边总共等 500ms）。 */
    private const val WEB_LIST_BUDGET_MS = 250L

    /** 选中网页图片时取原文件的上限，从收到请求算起（传送门那边总共等 800ms，还要留出传输）。 */
    private const val WEB_FETCH_BUDGET_MS = 550L

    private val registered = AtomicBoolean()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** PixelCopy 回调、下载看门狗用的后台线程；不放主线程，免得和来源应用抢。 */
    private val io by lazy { Handler(HandlerThread("FP-SourceImage-io").apply { start() }.looper) }

    /** 影子抓取器构造时调用；每个进程只注册一次，失败下次再试。 */
    @JvmStatic
    fun ensureRegistered() {
        if (!registered.compareAndSet(false, true)) return
        try {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application
            app.registerReceiver(
                Receiver,
                IntentFilter(SourceImageContract.ACTION),
                SourceImageContract.SENDER_PERMISSION,
                main,
                Context.RECEIVER_EXPORTED,
            )
            Log.i(TAG, "区域取图接收端已注册：进程=${app.packageName}")
        } catch (t: Throwable) {
            registered.set(false)
            Log.w(TAG, "区域取图接收端注册失败", t)
        }
    }

    private object Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cb = runCatching { intent.extras?.getBinder(SourceImageContract.EXTRA_CALLBACK) }.getOrNull() ?: return
            try {
                if (intent.getIntExtra(SourceImageContract.EXTRA_MODE, SourceImageContract.MODE_FETCH) == SourceImageContract.MODE_LIST) {
                    list(context, intent.getIntExtra(SourceImageContract.EXTRA_MIN_SIDE, 0), cb)
                    return
                }
                val r = intent.getIntArrayExtra(SourceImageContract.EXTRA_RECT)
                if (r == null || r.size != 4) { fail(cb, "请求缺少矩形"); return }
                handle(Rect(r[0], r[1], r[2], r[3]), cb)
            } catch (t: Throwable) {
                Log.w(TAG, "区域取图失败", t)
                fail(cb, "异常：$t")
            }
        }
    }

    private fun handle(target: Rect, cb: IBinder) {
        val t0 = android.os.SystemClock.uptimeMillis()
        tryVideo(videoHits(target), 0, target, cb, t0, ArrayList())
    }

    private class VideoHit(val view: View, val origin: IntArray, val visible: Rect)

    /** 与 [target] 对得上的视频控件：交并比够，或者 [target] 九成落在它里面（视频占满屏时用户只框一块）。 */
    private fun videoHits(target: Rect): List<VideoHit> {
        val out = ArrayList<VideoHit>()
        val area = target.width().toLong() * target.height()
        forEachShown { v, visible ->
            if (VideoFrames.isVideo(v)) {
                val inter = Rect()
                if (inter.setIntersect(visible, target) &&
                    (iou(visible, target) >= MIN_IOU || inter.width().toLong() * inter.height() * 10 >= area * 9)
                ) out += VideoHit(v, IntArray(2).also { v.getLocationOnScreen(it) }, Rect(visible))
            }
        }
        // 越靠上层（后遍历到）的越先试：弹幕层若是透明的，会被判掉再试下面的
        return out.asReversed()
    }

    private fun tryVideo(hits: List<VideoHit>, i: Int, target: Rect, cb: IBinder, t0: Long, notes: MutableList<String>) {
        if (i >= hits.size) { tryNative(target, cb, t0, notes); return }
        val h = hits[i]
        VideoFrames.grab(h.view, { io.post(it) }) { frame, how ->
            main.post {
                guarded(cb) {
                    val soft = frame?.let { f -> softCopy(f).also { if (it !== f) f.recycle() } }
                    if (soft != null && VideoFrames.looksLikeFrame(soft)) {
                        val out = VideoFrames.cropTo(soft, h.view, h.origin, h.visible, target)
                        if (out !== soft) soft.recycle()
                        sendBitmap(cb, out, "$how ${h.view.javaClass.simpleName}", h.view.javaClass.name, t0)
                    } else {
                        soft?.recycle()
                        notes += if (frame == null) how else "$how 不像视频帧（透明或全黑）"
                        tryVideo(hits, i + 1, target, cb, t0, notes)
                    }
                }
            }
        }
    }

    private fun tryNative(target: Rect, cb: IBinder, t0: Long, notes: MutableList<String>) {
        val view = findView(target)
        // 控件自己持有位图（ImageView、带位图的 Drawable）最可靠，先取；
        // 否则（Compose、自绘控件）在整窗记录里找，再退回画布拦截与网页
        if (view != null && view !is WebView && DrawableBitmapFinder.largest(view) != null) {
            pick(view)?.let { picked ->
                softCopy(picked.first)?.let { sendBitmap(cb, it, "${picked.second} ${view.javaClass.simpleName}", view.javaClass.name, t0); return }
            }
        }
        if (tryDrawn(target, cb, t0, notes)) return
        when {
            view == null -> notes += "没有与 $target 对得上的控件"
            view is WebView -> {}
            else -> {
                val picked = pick(view)
                if (picked != null) {
                    val copy = softCopy(picked.first)
                    if (copy != null) { sendBitmap(cb, copy, "${picked.second} ${view.javaClass.simpleName}", view.javaClass.name, t0); return }
                    notes += "复制位图失败"
                } else {
                    notes += "${view.javaClass.name} 里没取到位图（${PortalBitmapCapture.lastFailure}）"
                }
            }
        }
        tryWeb(target, cb, t0, notes)
    }

    /**
     * 把没被盖住的窗口整个画进记录画布，在画过的位图里找与 [target] 对得上的那张。
     * Compose（含类名混淆的，贴吧Lite）与自绘控件里的图不是 View，只能这样拿。
     * 长图查看器常常一边画整张底图、一边叠高清分块，分块与 [target] 的交并比低，自然落选，取到的是整张底图。
     */
    private fun tryDrawn(target: Rect, cb: IBinder, t0: Long, notes: MutableList<String>): Boolean {
        val shown = ShownViews.collect().first
        for (root in shown.nodes.asReversed()) {
            if (root.depth != 0 || !Rect.intersects(root.rect, target) || !shown.mostlyExposed(root, MIN_VISIBLE_RATIO)) continue
            val drawn = PortalBitmapCapture.drawn(root.view) ?: run { notes += "整窗绘制失败（${PortalBitmapCapture.lastFailure}）"; continue }
            val origin = IntArray(2).also { root.view.getLocationOnScreen(it) }
            val best = drawn.samples
                .filter { iou(screenRect(it, origin), target) >= MIN_IOU }
                .maxByOrNull { it.sourceBounds.width() * it.sourceBounds.height() }
            if (best == null) {
                val near = drawn.samples.sortedByDescending { iou(screenRect(it, origin), target) }.take(3)
                    .joinToString { "${it.bitmap.width}x${it.bitmap.height}@${screenRect(it, origin)}" }
                notes += "整窗记录里 ${drawn.samples.size} 张位图都对不上（最接近：$near）"
                continue
            }
            val src = best.bitmap
            val crop = if (best.usesWholeBitmap) null else Rect().also { best.sourceBounds.roundOut(it) }
            val copy = runCatching {
                if (crop == null || !crop.intersect(0, 0, src.width, src.height)) softCopy(src)
                else softCopy(Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height()))
            }.getOrNull()
            if (copy == null) { notes += "整窗记录的位图复制失败"; continue }
            sendBitmap(cb, copy, "整窗记录 ${src.width}x${src.height}${if (crop != null) " 裁 $crop" else ""}", root.view.javaClass.name, t0)
            return true
        }
        return false
    }

    /** 记录画布里一张位图在屏幕上的范围（[origin] 是被画的 View 在屏幕上的位置）。 */
    private fun screenRect(s: PortalBitmapCapture.Result, origin: IntArray): Rect {
        val r = Rect()
        s.visibleBounds.roundOut(r)
        r.offset(origin[0], origin[1])
        return r
    }

    /** 一张位图按原样画出来的完整范围（含画到屏幕外的部分），屏幕坐标。 */
    private fun fullRect(s: PortalBitmapCapture.Result, origin: IntArray): Rect {
        val f = RectF(s.sourceBounds)
        s.bitmapToView.mapRect(f)
        val r = Rect()
        f.roundOut(r)
        r.offset(origin[0], origin[1])
        return r
    }

    /** [target] 中心落在哪个 WebView 里，就在它的图片列表里找那张，按地址取原文件。 */
    private fun tryWeb(target: Rect, cb: IBinder, t0: Long, notes: MutableList<String>) {
        var found: WebView? = null
        forEachShown { v, visible -> if (v is WebView && visible.contains(target.centerX(), target.centerY())) found = v }
        val web = found
        if (web == null) { fail(cb, notes.joinToString("；")); return }
        val onItems = { items: List<WebImages.Item> ->
            val best = items.maxByOrNull { iou(it.rect, target) }?.takeIf { iou(it.rect, target) >= MIN_IOU }
                ?: items.filter { it.rect.contains(target.centerX(), target.centerY()) }
                    .minByOrNull { it.rect.width().toLong() * it.rect.height() }
            if (best == null) {
                notes += "WebView 里 ${items.size} 张图都对不上"
                fail(cb, notes.joinToString("；"))
            } else {
                val ua = runCatching { web.settings.userAgentString }.getOrNull()
                val referer = runCatching { web.url }.getOrNull()
                Thread({ sendWeb(cb, best.src, ua, referer, t0) }, "FP-SourceImage").start()
            }
        }
        val cached = WebImages.cached(web)
        if (cached != null) onItems(cached) else WebImages.query(web) { items -> guarded(cb) { onItems(items) } }
    }

    /**
     * 异步回调（PixelCopy、WebView 脚本）不在接收端的 try 里：异常一律在这里吞掉、回失败原因，
     * 绝不抛进来源应用的主线程。
     */
    private fun guarded(cb: IBinder, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "区域取图失败", t)
            fail(cb, "异常：$t")
        }
    }

    /** 后台线程：下载网页图片原文件。不大于 [MAX_SIDE] 就原样转发字节（JPEG / WebP 不必重新编码），否则缩小后转 PNG。 */
    private fun sendWeb(cb: IBinder, src: String, ua: String?, referer: String?, t0: Long) {
        val label = src.substringBefore('?').takeLast(80)
        try {
            val bytes = WebImages.download(src, ua, referer, t0 + WEB_FETCH_BUDGET_MS, io)
            if (bytes == null) { fail(cb, "网页图片没取到（超时、失败或过大）：$label"); return }
            val size = WebImages.bounds(bytes)
            if (size == null) { fail(cb, "网页图片不是位图格式：$label"); return }
            val (w, h) = size
            if (max(w, h) <= MAX_SIDE) {
                sendStream(cb, w, h, "网页原图", label, t0) { it.write(bytes) }
                return
            }
            val sample = Integer.highestOneBit(max(w, h) / MAX_SIDE).coerceAtLeast(1)
            val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            if (decoded == null) { fail(cb, "网页图片解码失败：$label"); return }
            val copy = softCopy(decoded)
            if (copy !== decoded) decoded.recycle()
            if (copy == null) { fail(cb, "网页图片缩小失败：$label"); return }
            sendBitmap(cb, copy, "网页原图（缩小）", label, t0)
        } catch (t: Throwable) {
            fail(cb, "网页图片异常：$t")
        }
    }

    /**
     * 回传一张我们自己的位图（已复制，回传后回收）。
     * 不透明的用 JPEG（质量 100）：PNG 编码占了取图全程的大半（720x540 约 145ms），
     * Flyme 超过 64 万像素也是换 JPEG。带透明的（贴纸、表情）仍用 PNG，免得透明处变黑。
     */
    private fun sendBitmap(cb: IBinder, copy: Bitmap, how: String, what: String, t0: Long) {
        val opaque = isOpaque(copy)
        val format = if (opaque) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        sendStream(cb, copy.width, copy.height, "$how ${if (opaque) "JPEG" else "PNG"}", what, t0, { copy.recycle() }) {
            copy.compress(format, 100, it)
        }
    }

    /** 采样 24x24 个点，全部不透明才算不透明位图（`hasAlpha` 对 ARGB_8888 几乎总是 true，不能直接用）。 */
    private fun isOpaque(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return true
        val n = 24
        for (gy in 0 until n) for (gx in 0 until n) {
            val c = b.getPixel((b.width - 1) * gx / (n - 1), (b.height - 1) * gy / (n - 1))
            if (android.graphics.Color.alpha(c) < 255) return false
        }
        return true
    }

    /** 先回结果（尺寸、说明、管道读端），再在后台往写端写图片字节：主线程只做找控件与复制。 */
    private fun sendStream(
        cb: IBinder, w: Int, h: Int, how: String, what: String, t0: Long,
        after: () -> Unit = {}, write: (java.io.OutputStream) -> Unit,
    ) {
        val pipe = ParcelFileDescriptor.createPipe()
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SourceImageContract.DESCRIPTOR)
            data.writeInt(w)
            data.writeInt(h)
            data.writeString(how)
            data.writeFileDescriptor(pipe[0].fileDescriptor)
            cb.transact(SourceImageContract.TX_RESULT, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            runCatching { pipe[1].close() }
            after()
            Log.w(TAG, "区域取图回传失败", t)
            return
        } finally {
            data.recycle()
            pipe[0].close()
        }
        Log.i(TAG, "区域取图：$what → ${w}x$h（$how，${android.os.SystemClock.uptimeMillis() - t0}ms）")
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use(write)
            } catch (t: Throwable) {
                Log.w(TAG, "区域取图写出失败", t)
            } finally {
                after()
            }
        }, "FP-SourceImage").start()
    }

    /**
     * 列出本进程屏幕上「真画着位图」的控件：与 Flyme 识屏一样按内容判断，不看类名 ——
     * 系统控件树只能按类名猜，小红书详情页的大图就因此漏掉。
     * 只做 Drawable 树判断（便宜），不逐个画进拦截画布；超过 [LIST_BUDGET_MS] 就把已找到的先回去。
     */
    private fun list(context: Context, minSide: Int, cb: IBinder) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val dm = context.resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        val found = ArrayList<Rect>()
        val webViews = ArrayList<WebView>()
        val foreign = LinkedHashSet<String>()
        var videos = 0
        var covered = 0
        // 遍历与判断共用一份时间预算：超时就把已找到的先回去
        val deadline = t0 + LIST_BUDGET_MS
        val (shown, cut) = ShownViews.collect(deadline)
        var truncated = cut
        for (n in shown.nodes) {
            if (android.os.SystemClock.uptimeMillis() > deadline) { truncated = true; break }
            val v = n.view
            val visible = n.content
            val w = visible.width()
            val h = visible.height()
            WebImages.foreignWebViewName(v)?.let { foreign += it }
            // 被上层盖住的不列：大图查看器底下的列表页
            if (v is WebView) { if (shown.mostlyExposed(n, MIN_VISIBLE_RATIO)) webViews += v else covered++; continue }
            val isVideo = VideoFrames.isVideo(v)
            if (w >= minSide && h >= minSide &&
                (w.toLong() * h <= screenArea * MAX_SCREEN_RATIO || v is ImageView || isVideo) &&
                n.rect.width().toLong() * n.rect.height() >= v.width.toLong() * v.height * MIN_VISIBLE_RATIO &&
                found.none { it == visible }
            ) {
                // 视频控件不看 Drawable：画面在它自己的缓冲里
                if (!isVideo && DrawableBitmapFinder.largest(v) == null) continue
                if (shown.exposedArea(n) < v.width.toLong() * v.height * MIN_VISIBLE_RATIO) { covered++; continue }
                found += Rect(visible)
                if (isVideo) videos++
            }
        }
        // 不是 View 的图（Compose，含类名混淆的；自绘控件）：没被盖住的窗口整个画一遍记录画布，把画过的大位图列出来
        // 控件自己持有的位图已由上面按控件判断过（含故意不列的：被盖住、背景图），
        // 与它们对得上的不再列；大的先列，被已列出范围包住的（长图的高清分块）不再单列
        var drawnCount = 0
        if (!truncated && android.os.SystemClock.uptimeMillis() <= deadline) {
            val owned = ArrayList<Rect>()
            for (n in shown.nodes) if (n.view !is WebView && DrawableBitmapFinder.largest(n.view) != null) { owned += n.rect; if (n.content != n.rect) owned += n.content }
            for (root in shown.nodes) {
                if (root.depth != 0 || !shown.mostlyExposed(root, MIN_VISIBLE_RATIO)) continue
                if (android.os.SystemClock.uptimeMillis() > deadline) { truncated = true; break }
                val t1 = android.os.SystemClock.uptimeMillis()
                val drawn = PortalBitmapCapture.drawn(root.view)
                if (drawn == null) { Log.i(TAG, "列图片：整窗绘制失败（${PortalBitmapCapture.lastFailure}）"); continue }
                val origin = IntArray(2).also { root.view.getLocationOnScreen(it) }
                val rects = drawn.samples.mapNotNull { s ->
                    val r = screenRect(s, origin)
                    if (!r.intersect(root.rect) || r.width() < minSide || r.height() < minSide) return@mapNotNull null
                    if (owned.any { iou(it, r) >= 0.8f }) return@mapNotNull null
                    // 大半在屏幕外的不列（与按控件判断同一门槛）；比整屏还大的长图不按这个算
                    val full = fullRect(s, origin)
                    val fullArea = full.width().toLong() * full.height()
                    if (fullArea <= screenArea && r.width().toLong() * r.height() < fullArea * MIN_VISIBLE_RATIO) return@mapNotNull null
                    r
                }.sortedByDescending { it.width().toLong() * it.height() }
                var added = 0
                for (r in rects) {
                    if (found.any { f -> Rect(f).let { it.intersect(r) && it.width().toLong() * it.height() * 10 >= r.width().toLong() * r.height() * 8 } }) continue
                    found += r
                    added++
                }
                drawnCount += added
                Log.i(TAG, "列图片·整窗：${root.view.javaClass.simpleName} 画了 ${drawn.samples.size} 张位图，新增 $added 张，" +
                    "${android.os.SystemClock.uptimeMillis() - t1}ms" + (drawn.unsupported?.let { "（遇到 $it）" } ?: ""))
            }
        }
        if (foreign.isNotEmpty()) Log.i(TAG, "列图片：有非系统内核的 WebView，没有列：${foreign.joinToString()}")
        val native = found.size
        val head = "$native 张（视频 $videos，整窗记录 $drawnCount）" + if (covered > 0) "，被遮住不列 $covered 个" else ""
        if (webViews.isEmpty()) { replyList(cb, found, head, t0, truncated); return }
        // WebView 的脚本是异步的：都回来或到点就回，不阻塞主线程
        var pending = webViews.size
        var replied = false
        var web = 0
        val finish = Runnable {
            if (!replied) {
                replied = true
                replyList(cb, found, "$head + 网页 $web 张" + if (pending > 0) "（$pending 个 WebView 超时）" else "", t0, truncated)
            }
        }
        main.postDelayed(finish, WEB_LIST_BUDGET_MS)
        for (wv in webViews) WebImages.query(wv) { items ->
            if (!replied) runCatching {
                for (item in items) {
                    val r = item.rect
                    if (r.width() >= minSide && r.height() >= minSide &&
                        r.width().toLong() * r.height() <= screenArea * MAX_SCREEN_RATIO &&
                        found.none { it == r }
                    ) { found += r; web++ }
                }
                if (--pending == 0) { main.removeCallbacks(finish); finish.run() }
            }
        }
    }

    private fun replyList(cb: IBinder, found: List<Rect>, what: String, t0: Long, truncated: Boolean) {
        val info = "$what，${android.os.SystemClock.uptimeMillis() - t0}ms" + if (truncated) "，超时截断" else ""
        Log.i(TAG, "列图片：$info")
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SourceImageContract.DESCRIPTOR)
            data.writeInt(found.size)
            data.writeIntArray(IntArray(found.size * 4).also { a ->
                found.forEachIndexed { i, r -> a[i * 4] = r.left; a[i * 4 + 1] = r.top; a[i * 4 + 2] = r.right; a[i * 4 + 3] = r.bottom }
            })
            data.writeString(info)
            cb.transact(SourceImageContract.TX_LIST, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            Log.w(TAG, "列图片回传失败", t)
        } finally {
            data.recycle()
        }
    }

    /** 遍历本进程所有窗口里显示着、且大半没被上层盖住的 View，给出它在屏幕上的可见矩形。 */
    private fun forEachShown(f: (View, Rect) -> Unit) {
        val shown = ShownViews.collect().first
        for (n in shown.nodes) if (shown.mostlyExposed(n, MIN_VISIBLE_RATIO)) f(n.view, n.rect)
    }

    /** 本进程所有窗口里，可见区域与 [target] 交并比最高的 View；分数相差不到 0.02 时取更深的（外层容器与图片控件常常同框）。 */
    private fun findView(target: Rect): View? {
        data class Hit(val view: View, val score: Float, val depth: Int)
        val hits = ArrayList<Hit>()
        val shown = ShownViews.collect().first
        for (n in shown.nodes) {
            // 大图查看器里请求的是图片画出来的范围，比控件框小
            val s = max(iou(n.rect, target), iou(n.content, target))
            // 被上层盖住的不算：否则大图查看器里框选会取到底下列表页同位置的图
            if (s >= MIN_IOU && shown.mostlyExposed(n, MIN_VISIBLE_RATIO)) hits += Hit(n.view, s, n.depth)
        }
        val top = hits.maxOfOrNull { it.score } ?: return null
        return hits.filter { it.score >= top - 0.02f }.maxByOrNull { it.depth }?.view
    }

    /** 先看控件自己持有的位图（应用解码进内存的那张），再用画布拦截；本身没有就到子控件里找。 */
    private fun pick(view: View): Pair<Bitmap, String>? {
        DrawableBitmapFinder.largest(view)?.let { return it to "Drawable 树" }
        PortalBitmapCapture.capture(view, view.width / 2f, view.height / 2f)?.let { return it.bitmap to "画布拦截" }
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val c = view.getChildAt(i)
            if (c.isShown && c.width * c.height * 2 >= view.width * view.height) pick(c)?.let { return it }
        }
        return null
    }

    private fun softCopy(src: Bitmap): Bitmap? = runCatching {
        if (src.isRecycled) return null
        val scale = min(1f, MAX_SIDE.toFloat() / max(src.width, src.height))
        val soft = if (src.config == Bitmap.Config.HARDWARE || src.config == null) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(soft, (soft.width * scale).roundToInt(), (soft.height * scale).roundToInt(), true)
        } else if (soft === src) src.copy(Bitmap.Config.ARGB_8888, false) else soft
        if (soft !== src && soft !== out) soft.recycle()
        out
    }.getOrNull()

    private fun iou(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val inter = i.width().toLong() * i.height()
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }

    private fun fail(cb: IBinder, why: String) {
        Log.i(TAG, "区域取图未成：$why")
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SourceImageContract.DESCRIPTOR)
            data.writeString(why)
            cb.transact(SourceImageContract.TX_FAIL, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            Log.w(TAG, "区域取图回传失败原因失败", t)
        } finally {
            data.recycle()
        }
    }
}
