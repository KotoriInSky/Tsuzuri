package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.graphics.Rect
import android.util.Log
import android.view.View
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ContentCapabilities
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把传送门的识图选区与图源对齐 Flyme：两件事，都在 `com.miui.contentextension` 进程内。
 *
 * ## A. 选区放大：单击长按的 250×250px 方块 → 全宽 × 100dp 横带
 *
 * ## 为什么
 *
 * 用户的两个抱怨——「图片识别率不高」+「保存不了大致原图的分辨率」——是同一个根因：
 * 澎湃长按图片后，**识别用的图和保存的图是同一份**「以长按点为中心的 250×250px 方块」
 * （`TaplusMainFloatView.focusScreenCaptureView` 里硬编码的 `125`，520dpi 下 ≈77dp）。
 * 它不是「把原图压小了」，而是压根不是原图。
 *
 * Flyme 在同一场景喂给识别的是**整宽 × 100dp 横带**（`lj0.java:343-361`，
 * 以触点垂直居中、贴底自动收窄），面积是澎湃这块方块的 6.3 倍。
 *
 * ## 实现：事后改 mPickedRect，而不是重写方法
 *
 * `focusScreenCaptureView` 的全部逻辑在方法体内算好 `contentRect`、最后赋给
 * `mPickedRect`。我们 hook 这个方法，在 `proceed()` 之后反射改写 `mPickedRect`：
 *
 *  - **不碰方法的任何分支**——长按单点 / 长按拖选 / 服务预置 rect 的语义全部维持原样；
 *  - **只认 250×250**：`width==250 && height==250` 是「单击长按」分支（含两个重复代码块）
 *    的指纹；拖选分支（`Math.max(250, iAbs)`）只要用户拖得比 250 大就不会被误改——
 *    文档 §4 第一步特意强调过：**手动拖大选区本来就有效**，这条改的是「默认值被压太小」；
 *  - 服务预置的 `contentRect`（`getContentRect()` 非空且高度 ≤1000 时直接用）不会被匹配。
 *
 * 横带的几何（对齐 Flyme 那条纹带）：
 *
 *  - **整宽**：`left=0, right=截图宽`；
 *  - **100dp 高**，以**原方块中心**（= 长按点）垂直居中；
 *  - 上下越界时收窄（`top ∈ [0, 高-带高]`）——Flyme 的「贴底自动收窄」同款语义；
 *  - 边界取**截图位图**（`mScreenShot`）的尺寸而不是屏幕尺寸：这个方法跑的时候
 *    `captureScreenshot()` 已经截过图并裁去底部导航栏，选区超出位图会让
 *    `RectFactory.getPartialBitmap()` 的 `Bitmap.createBitmap` 直接抛异常。
 *
 * ## 关态与回滚
 *
 * 关态 = `HookRegistry` 根本不装这个 hook（规范 §9），选区恢复 250×250。
 * 这是纯方法拦截，没有 View 树残留，`uninstall()` 释放句柄即完全回到未安装状态，
 * 所以 `Catalog` 里 restart 用 `HOT_RELOAD`（重启 contentextension 进程生效）。
 */
object PortalImageRegion : Feature(Catalog.require(Catalog.SCREEN_IMAGE)) {

    private const val TAG = "FP-PortalRegion"

    private const val VIEW_CLS =
        "com.miui.contentextension.text.floatview.TaplusMainFloatView"

    private const val SERVICE_CLS =
        "com.miui.contentextension.services.TextContentExtensionService"

    /** 单击长按分支两个重复代码块共用的半边长（`f3/f6 = 125`）。 */
    private const val HOST_HALF_SIDE = 125

    /** 横带高度（dp）—— 对齐 Flyme `dimens.xml:2267` 的 `band = 100dp`。 */
    private const val BAND_HEIGHT_DP = 100f

    /**
     * 「位图择优」的替换阈值：服务里已有位图的**长边**不到选区长边的 2/3 时才用截图像素替换。
     * 低于这个增益不值得动（还要重画一次卡片），高于它说明 viewBitmap 明显是缩略图。
     */
    private const val REPLACE_GAIN_NUM = 3
    private const val REPLACE_GAIN_DEN = 2

    /** `mPickedRect` 字段（方法末尾赋值，识别与保存共同消费）。 */
    private var pickedRectField: Field? = null

    /** `mScreenShot` 字段（截好的全屏位图，选区边界与位图择优的图源）。 */
    private var screenShotField: Field? = null

    /** `mRecognitionCard` 字段（位图替换后要用它刷新卡片显示）。 */
    private var recognitionCardField: Field? = null

    /** 宿主服务的静态入口（getBitmap / getContentRect / setBitmap / isOtherMode）。 */
    private var serviceCls: Class<*>? = null

    /** 改大过多少次选区（诊断用）。 */
    private val enlarged = AtomicInteger(0)

    /** 「位图择优」替换过多少次（诊断用）。 */
    private val upgraded = AtomicInteger(0)

    override fun uninstall() {
        PortalPressSnapshotHost.release()
        super.uninstall()
    }

    // 键的 scope 含 SYSTEM_UI（给 press_snapshot 的 SystemUI 侧用），这一份只属于 contentextension
    override val installId: String get() = "screen_image:region"

    override fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs) =
        listOf("expand_region", "screenshot_crop", "candidate_check", "press_snapshot")
            .any { ContentCapabilities.on(prefs, Catalog.SCREEN_IMAGE, it) }

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == io.github.kotoriinsky.tsuzuri.core.ScopeTarget.CONTENT_EXTENSION.packageName

    override fun install(ctx: HookContext): InstallReport {
        val cls = try {
            Class.forName(VIEW_CLS, false, ctx.classLoader)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "class not found: $VIEW_CLS，跳过", t)
            return InstallReport(0, listOf(VIEW_CLS))
        }

        val method = cls.declaredMethods.firstOrNull {
            it.name == "focusScreenCaptureView" && it.parameterTypes.isEmpty()
        }
        if (method == null) {
            ctx.log(Log.WARN, "找不到 focusScreenCaptureView()，可能宿主已改版")
            return InstallReport(0, listOf("$VIEW_CLS.focusScreenCaptureView"))
        }

        try {
            pickedRectField = cls.getDeclaredField("mPickedRect").apply { isAccessible = true }
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "拿不到 mPickedRect 字段，本特性无法工作", t)
            return InstallReport(0, listOf("mPickedRect"))
        }
        try {
            screenShotField = cls.getDeclaredField("mScreenShot").apply { isAccessible = true }
        } catch (t: Throwable) {
            // 拿不到只是少了「按位图裁边」的精确性，仍可用屏幕尺寸兜底，不算致命
            ctx.log(Log.WARN, "拿不到 mScreenShot 字段，选区边界将按屏幕尺寸收窄", t)
        }
        try {
            recognitionCardField = cls.getDeclaredField("mRecognitionCard").apply { isAccessible = true }
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "拿不到 mRecognitionCard 字段，位图择优替换后无法刷新卡片", t)
        }
        try {
            val svc = Class.forName(SERVICE_CLS, false, ctx.classLoader)
            svc.getMethod("getBitmap")
            svc.getMethod("getContentRect")
            svc.getMethod("setBitmap", android.graphics.Bitmap::class.java)
            svc.getMethod("isOtherMode")
            serviceCls = svc
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "拿不到 $SERVICE_CLS 的静态入口，位图择优不可用", t)
            serviceCls = null
        }

        var hooked = 0
        return try {
            track(
                ctx.module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    // thisObject 是 TaplusMainFloatView：父类 BaseFloatView 不是 View，
                    // 以前按 View 强转恒为 null，放大与择优从未生效（2026-10-27 日志「view=null」）
                    try {
                        if (ContentCapabilities.on(ctx.prefs, key, "expand_region")) enlarge(chain.thisObject, ctx)
                    } catch (t: Throwable) {
                        // 改选区失败绝不能影响识屏本身 —— 宁可退回 250×250
                        ctx.log(Log.WARN, "放大选区失败（本次用宿主原选区）", t)
                    }
                    result
                },
            )
            hooked++

            val initCard = cls.declaredMethods.firstOrNull {
                it.name == "initImageCard" && it.parameterTypes.isEmpty()
            }
            if (initCard == null) {
                ctx.log(Log.WARN, "找不到 initImageCard()，位图择优不可用")
            } else {
                track(
                    ctx.module.hook(initCard).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (ContentCapabilities.on(ctx.prefs, key, "screenshot_crop")) preferScreenshotCrop(chain.thisObject, ctx)
                        } catch (t: Throwable) {
                            ctx.log(Log.WARN, "位图择优失败（保留宿主原图）", t)
                        }
                        result
                    },
                )
                hooked++
            }

            // 按下时截图（press_snapshot）的 contentextension 一半；SystemUI 一半是 PortalPressSnapshotTrigger
            val snap = PortalPressSnapshotHost.install(ctx) { track(it) }
            hooked += snap.hooked
            ctx.log(Log.INFO, "已挂上 focusScreenCaptureView + initImageCard：选区放大 + 位图择优；按下定格 hooked=${snap.hooked}")
            InstallReport(hooked = hooked, missing = snap.missing)
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "hook 传送门选区链失败", t)
            InstallReport(0, listOf(VIEW_CLS))
        }
    }

    /**
     * 把「单击长按」的 250×250 选区改写成整宽 × 100dp 横带。
     *
     * 只在 `width==250 && height==250` 时动手 —— 这是单击分支的指纹；
     * 其余来源（拖选 / 服务预置）一律原样放行。
     */
    private fun enlarge(view: Any?, ctx: HookContext) {
        if (alignToServiceImage(view, ctx)) return
        // 原媒体会话以当前图片范围定位，不能再改成全宽横带。
        if (PortalOriginalMedia.hasSession()) return skip(ctx, "原媒体会话中")
        view ?: return skip(ctx, "view=null")
        val field = pickedRectField ?: return
        val rect = field.get(view) as? Rect ?: return skip(ctx, "mPickedRect=null")
        if (rect.width() != HOST_HALF_SIDE * 2 || rect.height() != HOST_HALF_SIDE * 2) return skip(ctx, "宿主选区不是 250×250：$rect")

        // 边界：优先用截图位图的真实尺寸（它可能被导航栏逻辑裁去底部），
        // 拿不到才退回屏幕尺寸——宁可略保守，也不能让选区超出位图导致 createBitmap 抛异常。
        val bounds = bitmapBounds(view) ?: screenBounds(view) ?: return
        val band = (android.content.res.Resources.getSystem().displayMetrics.density * BAND_HEIGHT_DP).toInt()
            .coerceAtMost(bounds.second)

        val centerY = rect.centerY()
        val top = (centerY - band / 2).coerceIn(0, bounds.second - band)

        val wide = Rect(0, top, bounds.first, top + band)
        field.set(view, wide)

        val n = enlarged.incrementAndGet()
        if (n <= DIAG_LIMIT || n % DIAG_EVERY == 0) {
            ctx.log(Log.INFO, "选区 250×250@(${rect.centerX()},$centerY) → 全宽横带 $wide（第 $n 次）")
        }
    }

    private val skipped = AtomicInteger(0)

    /**
     * 选取框对齐「实际识别的图」。宿主 `initImageCard` 在服务里已有位图（App 抓到的那张图）时
     * 直接拿它去识别，**不按 mPickedRect 截图**；而框是按 mPickedRect 画的
     * （`showMainFloat → notifyShapeChanged(mPickedRect)`）。宿主只在 contentRect 高度 ≤1000 时
     * 才用它定框，长图/大图就退回 250 方块（或我们的横带），于是框和识别内容对不上。
     * 这里改为：有现成位图且有它的屏幕范围时，框 = 该范围（夹到截图内）。
     */
    private fun alignToServiceImage(view: Any?, ctx: HookContext): Boolean {
        view ?: return false
        val svc = serviceCls ?: return false
        val field = pickedRectField ?: return false
        val bmp = try { svc.getMethod("getBitmap").invoke(null) as? android.graphics.Bitmap } catch (_: Throwable) { null }
        if (bmp == null || bmp.isRecycled) return false
        val content = try { svc.getMethod("getContentRect").invoke(null) as? Rect } catch (_: Throwable) { null } ?: return false
        val (w, h) = bitmapBounds(view) ?: screenBounds(view) ?: return false
        val r = Rect(content.left.coerceIn(0, w), content.top.coerceIn(0, h), content.right.coerceIn(0, w), content.bottom.coerceIn(0, h))
        if (r.width() < MIN_REPLACE_SIDE || r.height() < MIN_REPLACE_SIDE) return false
        val old = field.get(view) as? Rect
        if (old == r) return true
        field.set(view, r)
        val n = enlarged.incrementAndGet()
        if (n <= DIAG_LIMIT || n % DIAG_EVERY == 0) ctx.log(Log.INFO, "选取框对齐识别图：$old → $r（图 ${bmp.width}x${bmp.height}，第 $n 次）")
        return true
    }

    /** 宿主的 `TaplusMainFloatView` 继承 `BaseFloatView`（不是 View），没有 `post`，回主线程走这里。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 没放大的原因（诊断「选区退回默认」用，限量打印）。 */
    private fun skip(ctx: HookContext, why: String) {
        val n = skipped.incrementAndGet()
        if (n <= DIAG_LIMIT || n % DIAG_EVERY == 0) ctx.log(Log.INFO, "选区未放大（$why，第 $n 次）")
    }

    /** 截图位图的宽高；字段不存在或还没截图时返回 null。 */
    private fun bitmapBounds(view: Any): Pair<Int, Int>? = try {
        val bmp = screenShotField?.get(view) as? android.graphics.Bitmap
        if (bmp == null || bmp.isRecycled) null else bmp.width to bmp.height
    } catch (_: Throwable) {
        null
    }

    /** 屏幕尺寸兜底。 */
    private fun screenBounds(@Suppress("UNUSED_PARAMETER") view: Any): Pair<Int, Int>? = try {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        dm.widthPixels to dm.heightPixels
    } catch (_: Throwable) {
        null
    }

    /**
     * 「位图择优」：App 侧抓到的是缩略图、而屏幕上铺得更大时，改用全分辨率截图的对应区域。
     *
     * 背景：抓取器只能拿到 App **已经加载好的位图**（聊天气泡 / 列表里常是小缩略图），
     * 而传送门自己握着全分辨率截图（`mScreenShot`）。当服务预置的 `view_rect`
     * （被长按 View 的屏幕范围，`sContentRect`）明显大于已有位图时，从截图按 view_rect
     * 裁一刀替换 —— 截图像素 = 屏幕原始像素，这才是「大致原图的分辨率」的正解，
     * 不需要任何「原始链接」（链接在目标 App 进程里，传送门进程拿不到，见类注释）。
     *
     * 触发条件刻意收紧（长边增益 ≥ 1.5 倍）：
     *  - viewBitmap 本来就是原尺寸的场景（如网页大图）不折腾；
     *  - 替换后必须重画一次卡片（`doRefreshImage`），没收益的替换纯属浪费。
     *
     * 在 `initImageCard()` **之后**动手：那时 `captureScreenshot()` 已跑完（mScreenShot
     * 就绪）、宿主已把 viewBitmap 放进服务；`view_rect` 可能超出截图下缘
     * （截图按导航栏裁过上边），逐边夹取。
     */
    private fun preferScreenshotCrop(view: Any?, ctx: HookContext) {
        // 已有位图可能含屏幕外长图；截图像素更大不代表内容更完整。
        if (PortalOriginalMedia.hasSession()) return
        view ?: return
        val svc = serviceCls ?: return
        val shot = try {
            screenShotField?.get(view) as? android.graphics.Bitmap
        } catch (_: Throwable) {
            null
        }
        if (shot == null || shot.isRecycled) return

        val current = try {
            svc.getMethod("getBitmap").invoke(null) as? android.graphics.Bitmap
        } catch (_: Throwable) {
            null
        }
        if (current == null || current.isRecycled) return

        val rect = try {
            svc.getMethod("getContentRect").invoke(null) as? Rect
        } catch (_: Throwable) {
            null
        } ?: return

        val left = rect.left.coerceIn(0, shot.width)
        val top = rect.top.coerceIn(0, shot.height)
        val right = rect.right.coerceIn(left, shot.width)
        val bottom = rect.bottom.coerceIn(top, shot.height)
        val visible = Rect(left, top, right, bottom)
        if (visible.width() < MIN_REPLACE_SIDE || visible.height() < MIN_REPLACE_SIDE) return

        // 增益够 = 已有位图是缩略图，直接用截图裁剪（原逻辑）
        if (maxOf(rect.width(), rect.height()) * REPLACE_GAIN_DEN >
            maxOf(current.width, current.height) * REPLACE_GAIN_NUM
        ) {
            replaceWithCrop(view, svc, shot, visible, current, "增益", ctx)
            return
        }

        // 增益不够：分辨率不差，但内容未必对 —— 候选图校验（后台算，主线程换）
        if (!candidateCheckEnabled(ctx)) return
        // 像素副本在主线程取：宿主回收截图也在主线程，后台线程只碰副本
        val input = PortalCandidateCheck.capture(current, shot, visible)
        PortalCandidateCheck.executor.execute {
            val t0 = android.os.SystemClock.uptimeMillis()
            val verdict = PortalCandidateCheck.judge(input)
            val cost = android.os.SystemClock.uptimeMillis() - t0
            val n = checked.incrementAndGet()
            Log.i(
                TAG,
                "候选校验 ${current.width}x${current.height} vs 选区 ${visible.width()}x${visible.height()}：" +
                    "${verdict.codes()} hamming=${verdict.hamming} 透明=${"%.2f".format(verdict.transparentRatio)} " +
                    "耗时=${cost}ms（第 $n 次）",
            )
            if (verdict.ok) return@execute
            mainHandler.post {
                try {
                    // 回到主线程时宿主可能已换图/关卡片：只替换「还是那一张」的候选
                    val still = svc.getMethod("getBitmap").invoke(null) as? android.graphics.Bitmap
                    if (still !== current || shot.isRecycled) {
                        Log.i(TAG, "候选已变化或截图已回收，放弃替换（${verdict.codes()}）")
                        return@post
                    }
                    replaceWithCrop(view, svc, shot, visible, current, "校验不合格:${verdict.codes()}", ctx)
                } catch (t: Throwable) {
                    ctx.log(Log.WARN, "校验后替换失败（保留宿主原图）", t)
                }
            }
        }
    }

    /**
     * 子项 `candidate_check`（Catalog 默认关，与新子项一律默认关的约定一致）：未写过值时用默认值。
     * 顶层开关关闭时整个特性不装，这里不会被调用，关态行为与未装模块一致。
     */
    private fun candidateCheckEnabled(ctx: HookContext): Boolean {
        val v = ctx.prefs.rawBooleanOrNull(Keys.pref(key, CANDIDATE_CHECK))
        if (v == null) {
            if (!defaultNoted) {
                defaultNoted = true
                ctx.log(Log.INFO, "子项 ${Keys.pref(key, CANDIDATE_CHECK)} 未写值，用默认值 $CANDIDATE_CHECK_DEFAULT")
            }
            return CANDIDATE_CHECK_DEFAULT
        }
        return v
    }

    private fun replaceWithCrop(
        view: Any, svc: Class<*>, shot: android.graphics.Bitmap, visible: Rect,
        current: android.graphics.Bitmap, why: String, ctx: HookContext,
    ) {
        val left = visible.left
        val top = visible.top
        val w = visible.width()
        val h = visible.height()
        val crop = try {
            android.graphics.Bitmap.createBitmap(shot, left, top, w, h)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "截图裁剪失败（保留宿主原图）", t)
            return
        }

        try {
            svc.getMethod("setBitmap", android.graphics.Bitmap::class.java).invoke(null, crop)
            val card = recognitionCardField?.get(view)
            if (card != null) {
                val otherMode = svc.getMethod("isOtherMode").invoke(null) as? Boolean ?: false
                card.javaClass.getMethod("doRefreshImage", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .invoke(card, otherMode, false)
            }
            val n = upgraded.incrementAndGet()
            Log.i(
                TAG,
                "位图择优[$why]：${current.width}x${current.height} → 截图裁剪 ${w}x$h @($left,$top)（第 $n 次）",
            )
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "写入择优位图失败（保留宿主原图）", t)
        }
    }

    private const val DIAG_LIMIT = 3
    private const val DIAG_EVERY = 20

    /** 替换后的位图至少要多大的边，太小的碎片不值得重画卡片。 */
    private const val MIN_REPLACE_SIDE = 64

    /** 候选图校验子项名（挂在 portal_image_region 下，与 Catalog 声明一致）。 */
    private const val CANDIDATE_CHECK = "candidate_check"

    /** 未声明时的默认值：开。只在「增益不够」的分支起作用，最坏结果是退回截图裁剪。 */
    private const val CANDIDATE_CHECK_DEFAULT = false

    @Volatile private var defaultNoted = false

    /** 候选校验跑过多少次（诊断用）。 */
    private val checked = AtomicInteger(0)
}
