package io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.GlobalRecognition
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalOcrSelect
import java.lang.reflect.Method

/**
 * 全局识别的传送门一半：截整屏、端侧 OCR、找图片区域，开 [GlobalRecognitionWindow]。
 *
 * 两个 hook：
 *  - `ContentCatcherReceiver.onReceive`：只截 [GlobalRecognition.ACTION_CAPTURE] 且发送方是 SystemUI 的那条，其余原样放行；
 *  - `ToastUtils.showShortToast`：只在识别窗口开着、文案是保存成功 / 失败时截下，交给窗口在中央提示（[hookSaveToast]）。
 * 宿主自己的截图、OCR、识图卡片都只经反射**调用**，不改它们的行为。
 */
internal object GlobalRecognitionHost : Feature(Catalog.require(Catalog.GLOBAL_RECOGNITION)), GlobalRecognitionWindow.Host {

    private const val TAG = "FP-GlobalRecog"

    private const val CAPTURE_CLS = "com.miui.contentextension.utils.DisplayCaptureUtils"
    private const val OCR_CLS = "com.miui.contentextension.utils.ContentExtentionOcrUtils"
    private const val SERVICE_CLS = "com.miui.contentextension.services.TextContentExtensionService"
    private const val CARD_CLS = "com.miui.contentextension.text.cardview.TaplusRecognitionExpandedImageCard"
    private const val AI_WINDOW_CLS = "com.miui.contentextension.utils.AiWindowUtilsKt"
    private const val TOAST_CLS = "com.miui.contentextension.utils.ToastUtils"

    /** 图片区域在 1/4 缩略图上找：够分辨 8dp 的格子，一屏只要几毫秒。 */
    private const val REGION_SCALE = 4

    /** 能点中的最小图片边长（dp）：头像、应用图标、表情这类小图。 */
    private const val TAP_MIN_DP = 20

    /** 页面控件树最多等多久（从开始截屏算）：OCR 本身约 200ms，抓取器默认处理上限 8ms 外加跨进程。 */
    private const val TREE_WAIT_MS = 1500L
    /** 控件树没认出图片时重新请求的等待上限。 */
    private const val TREE_RETRY_MS = 800L

    override val installId: String get() = "global_recognition:portal"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == ScopeTarget.CONTENT_EXTENSION.packageName

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var hookCtx: HookContext? = null
    private var classLoader: ClassLoader? = null

    private var capture: Method? = null
    private var getBitmap: Method? = null
    private var setBitmap: Method? = null

    @Volatile
    private var window: GlobalRecognitionWindow? = null

    /** 本次识别的前台应用包名（来自控件树根节点）：选图时向它要源位图。拿不到控件树时为 null。 */
    @Volatile
    private var frontPackage: String? = null

    /** 向来源应用要源位图的总时限：超时就用截图裁图。 */
    private const val SOURCE_WAIT_MS = 800L

    /** 让来源应用自己列图片的等待上限（对方主线程上最多跑 40ms，其余是跨进程）。 */
    private const val LIST_WAIT_MS = 500L

    override fun install(ctx: HookContext): InstallReport {
        val missing = ArrayList<String>()
        capture = runCatching {
            Class.forName(CAPTURE_CLS, false, ctx.classLoader).getMethod("getFullScreenshot", Context::class.java)
        }.getOrNull()
        if (capture == null) return InstallReport(0, listOf("$CAPTURE_CLS.getFullScreenshot(Context)"))
        runCatching {
            val svc = Class.forName(SERVICE_CLS, false, ctx.classLoader)
            getBitmap = svc.getMethod("getBitmap")
            setBitmap = svc.getMethod("setBitmap", Bitmap::class.java)
        }.onFailure { missing += "$SERVICE_CLS.get/setBitmap" }
        val onReceive = runCatching {
            Class.forName(GlobalRecognitionTrigger.RECEIVER_CLS, false, ctx.classLoader)
                .getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
        }.getOrNull() ?: return InstallReport(0, listOf("${GlobalRecognitionTrigger.RECEIVER_CLS}.onReceive"))

        hookCtx = ctx
        classLoader = ctx.classLoader
        hookSaveToast(ctx)?.let { missing += it }
        if (PortalOcrSelect.log == null) PortalOcrSelect.log = { p, m -> ctx.log(p, m) }
        val t = HandlerThread("fp-global-recog").apply { start() }
        thread = t
        worker = Handler(t.looper)

        track(
            ctx.module.hook(onReceive).intercept { chain ->
                val intent = chain.args[1] as? Intent
                if (intent?.action != GlobalRecognition.ACTION_CAPTURE) return@intercept chain.proceed()
                // 是我们的请求就不交给宿主：宿主对它本来也什么都不做
                try {
                    val receiver = chain.thisObject as BroadcastReceiver
                    val context = chain.args[0] as Context
                    val sender = if (Build.VERSION.SDK_INT >= 34) receiver.sentFromUid else -1
                    val sysui = runCatching { context.packageManager.getPackageUid(ScopeTarget.SYSTEM_UI.packageName, 0) }.getOrDefault(-2)
                    when {
                        sender != sysui -> ctx.log(Log.WARN, "[$TAG] 拒绝来源 uid=$sender")
                        !HookRegistry.prefs().isEnabled(key) -> Unit
                        else -> start(context.applicationContext ?: context, intent.getStringExtra(GlobalRecognition.EXTRA_PACKAGE))
                    }
                } catch (t: Throwable) {
                    ctx.log(Log.WARN, "[$TAG] 处理截屏请求失败", t)
                }
                null
            },
        )
        ctx.log(Log.INFO, "[$TAG] 传送门侧已挂上 hooked=$installedHooks missing=$missing")
        return InstallReport(hooked = installedHooks, missing = missing)
    }

    /**
     * 识别窗口开着时，截下宿主保存图片后的成功 / 失败 toast（`SaveImageUtils` → `ToastUtils.showShortToast`），
     * 改由窗口在中央给提示。窗口没开、或是别的文案，原样放行。返回缺失项，拿不到方法时窗口仍靠相册监听收尾。
     */
    private fun hookSaveToast(ctx: HookContext): String? {
        val m = runCatching {
            Class.forName(TOAST_CLS, false, ctx.classLoader)
                .getDeclaredMethod("showShortToast", Context::class.java, Int::class.javaPrimitiveType)
        }.getOrNull() ?: return "$TOAST_CLS.showShortToast(Context,int)"
        track(
            ctx.module.hook(m).intercept { chain ->
                val win = window ?: return@intercept chain.proceed()
                val context = chain.args[0] as? Context ?: return@intercept chain.proceed()
                val id = chain.args[1] as? Int ?: return@intercept chain.proceed()
                val res = context.resources
                val pkg = context.packageName
                val success = when (id) {
                    res.getIdentifier("image_save_success", "string", pkg) -> true
                    res.getIdentifier("image_save_failed", "string", pkg) -> false
                    else -> return@intercept chain.proceed()
                }
                val text = runCatching { res.getString(id) }.getOrNull() ?: return@intercept chain.proceed()
                main.post { if (window === win) win.onSaveResult(success, text) }
                null
            },
        )
        return null
    }

    /** 主线程。截图在后台；窗口先开，OCR 与图片区域随后填进去。 */
    /**
     * [pkg]：SystemUI 带来的前台包名。有它就立刻让该应用列图片，与 OCR 并行；列到了就不再等系统控件树
     * （B 站首页实测控件树要 600ms 左右才到，应用内列图只要 11ms）。
     */
    private fun start(context: Context, pkg: String?) {
        if (window != null) return
        val w = worker ?: return
        val t0 = SystemClock.uptimeMillis()
        w.post {
            val shot = try {
                val raw = capture?.invoke(null, context) as? Bitmap
                when {
                    raw == null -> null
                    raw.config == Bitmap.Config.HARDWARE -> raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
                    else -> raw
                }
            } catch (t: Throwable) {
                hookCtx?.log(Log.WARN, "[$TAG] 截屏失败", t)
                null
            }
            if (shot == null) {
                main.post { toast(context, "截屏失败") }
                return@post
            }
            // 页面控件树与 OCR 并行：前台 App 的抓取器在它自己的线程里导 XML
            frontPackage = pkg
            val listing = pkg?.let { p ->
                java.util.concurrent.FutureTask { inAppRegions(context, p) }.also { Thread(it, "fp-gr-list").start() }
            }
            val tree = ScreenUiTree.request(classLoader) { hookCtx?.log(Log.WARN, "[$TAG] $it") }
            val shared = GlobalRecognitionWindow.Shot(shot)
            shared.retain() // 识别线程自己的一份：窗口先关也不会把它从手里回收掉
            main.post {
                if (window != null) { shared.release(); return@post }
                val win = GlobalRecognitionWindow(context, shared, this) { window = null }
                window = win
                win.show()
            }
            try {
                val glyphs = recognize(shot)
                // 找图顺序：来源应用按内容列出的 → 系统控件树 → 截图像素算法。
                // 像素算法补进来的多半是导航栏图标、带留白的卡片，看上去就是「还在截图上猜」，所以只作最后兜底
                val listed = runCatching { listing?.get() }.getOrNull()
                // 应用回了列表（哪怕是空的）就不再看系统控件树：那棵树导出的常是被盖住的下层页面，
                // 酷安大图页上冒出首页的 11 块就是这么来的。之后只剩截图像素算法兜底
                val regions = (listed ?: run {
                    val views = viewRegions(context, shot, tree, t0)
                    // SystemUI 没带包名时，等控件树拿到包名再请应用列一次；应用回了就以它为准
                    val late = if (pkg == null) frontPackage?.let { inAppRegions(context, it) } else null
                    late ?: views
                }).ifEmpty { regions(context, shot, glyphs.orEmpty()) }
                hookCtx?.log(Log.INFO, "[$TAG] 整屏 ${shot.width}x${shot.height}：文字 ${glyphs?.size ?: -1} 字，图片 ${regions.size} 块，${SystemClock.uptimeMillis() - t0}ms")
                hookCtx?.log(Log.INFO, "[$TAG] 图片区域：" + regions.take(12).joinToString { "[${it.left.toInt()},${it.top.toInt()} ${it.width().toInt()}x${it.height().toInt()}]" })
                main.post { window?.takeIf { it.shot === shared }?.setResults(glyphs, regions) }
            } catch (t: Throwable) {
                // 裸线程里逃出去的异常会带走宿主进程
                hookCtx?.log(Log.WARN, "[$TAG] 识别失败", t)
                main.post { window?.takeIf { it.shot === shared }?.setResults(null, emptyList()) }
            } finally {
                shared.release()
            }
        }
    }

    /** 等控件树（从开始截屏算最多 [TREE_WAIT_MS]），取图片控件边界；拿不到返回空，由像素算法兜底。 */
    private fun viewRegions(context: Context, bitmap: Bitmap, tree: ScreenUiTree.Request?, t0: Long): List<RectF> {
        tree ?: return emptyList()
        val first = parseTree(context, bitmap, tree, (TREE_WAIT_MS - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(50))
        if (first.isNotEmpty()) return first
        // 树拿到了只是没认出图：交给应用内按内容列图，不必再等一轮
        if (frontPackage != null) return first
        // 同一页面有时只导出一小段（真机 B 站首页一次 8913 字符 / 0 张，紧接着一次 115025 字符 / 6 张），再要一次
        hookCtx?.log(Log.INFO, "[$TAG] 控件树里没认出图片，重新请求一次")
        val retry = ScreenUiTree.request(classLoader) { hookCtx?.log(Log.WARN, "[$TAG] $it") } ?: return emptyList()
        return parseTree(context, bitmap, retry, TREE_RETRY_MS)
    }

    private fun parseTree(context: Context, bitmap: Bitmap, tree: ScreenUiTree.Request, waitMs: Long): List<RectF> {
        val xml = tree.await(waitMs)
        if (xml == null) {
            hookCtx?.log(Log.INFO, "[$TAG] 页面控件树没拿到（${tree.error ?: "超时"}）")
            return emptyList()
        }
        val d = context.resources.displayMetrics.density
        val r = runCatching { ScreenUiTree.imageBounds(xml, bitmap.width, bitmap.height, TAP_MIN_DP * d) }
            .onFailure { hookCtx?.log(Log.WARN, "[$TAG] 解析页面控件树失败", it) }
            .getOrDefault(emptyList())
        hookCtx?.log(Log.INFO, "[$TAG] 页面控件树 ${xml.length} 字符，图片控件 ${r.size} 个")
        frontPackage = ScreenUiTree.rootPackage(xml)
        if (r.isEmpty()) diagnoseTree(context, xml)
        return r
    }

    /**
     * 控件树里一张图都没认出来时，把类名分布打出来、整份 XML 存进传送门缓存目录，
     * 用来判断是类名对不上、树被截断，还是根本不是前台页面（2026-10-28 B 站首页 0 张）。
     */
    private fun diagnoseTree(context: Context, xml: String) {
        val classes = Regex("class=\"([^\"]*)\"").findAll(xml).map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val nodes = Regex("<node").findAll(xml).count()
        hookCtx?.log(Log.INFO, "[$TAG] 控件树诊断：节点 $nodes 个，闭合=${xml.trimEnd().endsWith("</hierarchy>")}，类名 " +
            classes.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key}×${it.value}" })
        runCatching { java.io.File(context.cacheDir, "global_recog_tree.xml").writeText(xml) }
            .onSuccess { hookCtx?.log(Log.INFO, "[$TAG] 控件树已存：${context.cacheDir}/global_recog_tree.xml") }
    }

    private fun recognize(bitmap: Bitmap): List<PortalOcrSelect.Glyph>? {
        val cl = classLoader ?: return null
        val ocr = runCatching {
            val companion = Class.forName(OCR_CLS, false, cl).getField("Companion").get(null)
            companion.javaClass.getMethod("getINSTANCE").invoke(companion)
        }.getOrNull() ?: return null
        return PortalOcrSelect.recognize(ocr, bitmap)
    }

    private fun regions(context: Context, bitmap: Bitmap, glyphs: List<PortalOcrSelect.Glyph>): List<RectF> {
        val sw = bitmap.width / REGION_SCALE
        val sh = bitmap.height / REGION_SCALE
        if (sw <= 0 || sh <= 0) return emptyList()
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bitmap) small.recycle()
        val sx = sw / bitmap.width.toFloat()
        val sy = sh / bitmap.height.toFloat()
        val text = glyphs.map {
            GlobalRecognition.Box((it.box.left * sx).toInt(), (it.box.top * sy).toInt(), (it.box.right * sx).toInt() + 1, (it.box.bottom * sy).toInt() + 1)
        }
        val d = context.resources.displayMetrics.density
        val cell = (8 * d / REGION_SCALE).toInt().coerceAtLeast(2)
        // 小到 20dp 的图标也要能点中；窗口只把够大的那些高亮出来，小的只在点击时命中
        val minSide = (TAP_MIN_DP * d / REGION_SCALE).toInt()
        return GlobalRecognition.findImageRegions(px, sw, sh, cell, text, minSide).map {
            RectF(it.left / sx, it.top / sy, it.right / sx, it.bottom / sy)
        }
    }

    // ── 给窗口用的宿主能力（全部反射，拿不到就降级） ───────────────────────

    /** 让前台应用在它自己的进程里按内容列出图片位置；没有应答返回 null（空列表是「应用说没有图」）。 */
    private fun inAppRegions(context: Context, pkg: String): List<RectF>? {
        val minSide = (TAP_MIN_DP * context.resources.displayMetrics.density).toInt()
        val r = runCatching { SourceImageClient.list(context, pkg, minSide, LIST_WAIT_MS) }
            .getOrElse { SourceImageClient.ListResult(null, "请求异常：$it") }
        hookCtx?.log(Log.INFO, "[$TAG] 应用内列图片（$pkg）：${r.rects?.size ?: "无应答"}，${r.detail}")
        return r.rects?.map { RectF(it) }
    }

    override fun requestSourceImage(context: Context, rect: RectF, done: (Bitmap?, String) -> Unit) {
        val pkg = frontPackage
        val w = worker
        if (pkg == null || w == null) { done(null, "没有前台包名（控件树没拿到）"); return }
        val box = android.graphics.Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        w.post {
            val r = try {
                SourceImageClient.fetch(context, pkg, box, SOURCE_WAIT_MS)
            } catch (t: Throwable) {
                SourceImageClient.Result(null, "请求异常：$t")
            }
            done(r.bitmap, "$pkg：${r.detail}")
        }
    }

    override fun hostBitmap(): Bitmap? = runCatching { getBitmap?.invoke(null) as? Bitmap }.getOrNull()

    override fun setHostBitmap(bitmap: Bitmap?): Boolean = runCatching { setBitmap?.invoke(null, bitmap); setBitmap != null }.getOrDefault(false)

    override fun newImageCard(context: Context): View? = try {
        val cls = Class.forName(CARD_CLS, false, classLoader)
        cls.getConstructor(Context::class.java, android.util.AttributeSet::class.java, Int::class.javaPrimitiveType)
            .newInstance(context, null, 0) as View
    } catch (t: Throwable) {
        hookCtx?.log(Log.WARN, "[$TAG] 构造识图卡片失败", t)
        null
    }

    override fun translate(context: Context, text: String): Boolean = try {
        val cls = Class.forName(AI_WINDOW_CLS, false, classLoader)
        val ok = cls.getMethod("shouldStartAiWindow", Context::class.java).invoke(null, context) as? Boolean ?: false
        if (ok) cls.getMethod("startAiWindow", Context::class.java, String::class.java).invoke(null, context, text)
        ok
    } catch (t: Throwable) {
        hookCtx?.log(Log.WARN, "[$TAG] 翻译入口不可用", t)
        false
    }

    override fun log(priority: Int, msg: String) {
        hookCtx?.log(priority, "[$TAG] $msg")
    }

    private fun toast(context: Context, msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun uninstall() {
        main.post { window?.close() }
        thread?.quitSafely()
        thread = null
        worker = null
        hookCtx = null
        super.uninstall()
    }
}
