package io.github.kotoriinsky.tsuzuri.hook.feature.aggpayment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 聚合支付：把微信 / 支付宝各自发布的**支付类 shortcut** 聚到一个面板里，一键直达。
 *
 * ## 形态：走 HyperOS 自己的「指纹快捷」（FOD quick open）
 *
 * Flyme 的聚合支付是**锁屏指纹区**的快捷启动项。HyperOS 有一模一样的东西，
 * 而且**是我们这台设备上真实存在、可挂载的**：
 *
 * ```
 * com.miui.keyguard.biometrics.fod.MiuiGxzwQuickOpenUtil.generateQuickOpenItem(RectF, Region, Context, int)
 *     id 1001 -> WechatPayItem       (微信付款码)
 *     id 1002 -> WechatScanItem      (微信扫一扫)
 *     id 1003 -> XiaoaiItem
 *     id 1004 -> AlipayPayItem       (支付宝付款码)
 *     id 1005 -> AlipayScanItem      (支付宝扫一扫)
 *     id 1006..1008 -> QrCode/Search/AddEvent（国际版默认那组）
 *   DEFAULT_ITEM_ID_LIST（CN）= {1001,1002,1003,1004,1005}
 *
 * com.miui.keyguard.biometrics.fod.MiuiGxzwQuickOpenView$7.onClick(View)
 *     -> handleQucikOpenItemTouchUp(IQuickOpenItem)   // 松手提交
 * ```
 *
 * 证据来源：本机 `/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk`（与 research/apks
 * 里那份字节数完全一致）反编译；另外 SystemUI 的 `shared_prefs/quick_open.xml`
 * 在本机是活的（`sp_fod_show_quick_open_press_count=4`），说明这套环状快捷菜单确实在跑。
 *
 * ### 我们做了什么
 *
 * **劫持 id 1001 那一格**（= CN 默认项里的第 0 项「微信付款码」，正好是 Flyme 把
 * 「聚合支付」插到候选列表第 0 位的同一种取法）：
 *
 *  1. `generateQuickOpenItem` 返回后，把该实例登记进 [ours]，并换掉它的图标；
 *  2. 该实例的 `getTitle/getSubTitle/getTag` 返回我们的文案；
 *  3. `handleQucikOpenItemTouchUp` 收到 [ours] 里的实例时**短接** MIUI 自己的启动逻辑，
 *     改成弹 [PaymentPanel]。
 *
 * `IQuickOpenItem` 是**抽象类**（不是接口），所以没法用 `Proxy` 造实例、也没法在
 * `:hook` 里继承它（编译期拿不到 SystemUI 的内部类）。所以走这条「借壳」路线：
 * 用 MIUI 自己的实例，改它的表现，换它的提交动作。
 *
 * ## 入口：三个选项里只有 `lock_screen` 能落地
 *
 * 参数 `entry` 有三个值，但三条路的可行性差得很远（都写在下面对应的注释里）：
 *  - `lock_screen`  → **本文件实现的这条**
 *  - `quick_tile`   → 做不到（不是「感觉难」，理由见 [ENTRY_QUICK_TILE_REASON]）
 *  - `long_press_icon` → 做不到（理由见 [ENTRY_LONG_PRESS_ICON_REASON]）
 *
 * 配到做不到的那两个时，[install] 返回 `hooked=0` + 一条 note 说明，
 * **一个 hook 都不装** —— 装了没反应比不装更糟（`miui_widget_flag` 的教训）。
 *
 * ## 关态
 *
 * [uninstall] 摘掉全部 hook、收掉面板、丢掉缓存，与「未安装模块」完全一致。
 * 注意：被我们改过图标/文案的那个 FOD 项实例**是 MIUI 自己创建的**，
 * 我们在卸载时把 [ours] 清空 —— 之后 MIUI 再建出来的项就是它自己的样子（正常态）；
 * 已经在屏幕上的那一环会在松手/取消后自行销毁，不需要我们去改它的 View。
 */
object AggregatePayment : Feature(Catalog.require(Catalog.AGG_PAYMENT)) {

    private const val TAG = "FP-AggPay"

    // ── entry 的三个取值 ───────────────────────────────────────────────
    private const val ENTRY_LONG_PRESS_ICON = "long_press_icon"
    private const val ENTRY_QUICK_TILE = "quick_tile"
    private const val ENTRY_LOCK_SCREEN = "lock_screen"

    /** `quick_tile` 做不到的理由：两条路都要动本特性目录以外的文件。 */
    private const val ENTRY_QUICK_TILE_REASON =
        "quick_tile 需要 manifest 组件（无法在 aggpayment/ 内提供）或编译期继承 QSTileImpl（:hook 拿不到 SystemUI 内部类）"

    /** `long_press_icon` 做不到的理由：桌面本体是 native。 */
    private const val ENTRY_LONG_PRESS_ICON_REASON =
        "桌面长按菜单由 native 渲染实现，SystemUI 作用域里没有桌面图标的长按入口"

    // ── HyperOS FOD 指纹快捷的落点 ─────────────────────────────────────
    private const val CLS_UTIL = "com.miui.keyguard.biometrics.fod.MiuiGxzwQuickOpenUtil"
    private const val CLS_VIEW = "com.miui.keyguard.biometrics.fod.MiuiGxzwQuickOpenView"
    private const val CLS_ITEM = "com.miui.keyguard.biometrics.fod.item.IQuickOpenItem"
    private const val CLS_WECHAT_ITEM = "com.miui.keyguard.biometrics.fod.item.WechatPayItem"

    private const val M_GENERATE = "generateQuickOpenItem"
    private const val M_COMMIT = "handleQucikOpenItemTouchUp" // ← MIUI 自己的拼写（少了 i）
    private const val M_GET_VIEW = "getView"
    private const val M_GET_TITLE = "getTitle"
    private const val M_GET_SUBTITLE = "getSubTitle"
    private const val M_GET_TAG = "getTag"

    /** 被劫持的 FOD 项 id：1001 = 微信付款码，CN 默认环的第 0 格。 */
    private const val HIJACK_ITEM_ID = 1001

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 已经被我们接管的 FOD 项实例。
     *
     * **必须是 identity 比较**（`IdentityHashMap`）：`IQuickOpenItem` 没重写 equals，
     * 但这条链路上任何一个环节将来重写了它，`Set<Any>` 就会变成「值相等即命中」，
     * 那会让别人的项也被我们接管 —— 用身份集合把这个可能性直接掐掉。
     */
    private val ours: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    )

    @Volatile
    private var panel: PaymentPanel? = null

    @Volatile
    private var repository: ShortcutRepository? = null

    /** 预加载好的条目。面板只读它，不在主线程做 IPC。 */
    @Volatile
    private var cached: List<PaymentShortcut> = emptyList()

    @Volatile
    private var lastLoadFailure: String? = null

    /** 上次预加载的时间。用来给「每次打开都后台刷一次」限流。 */
    @Volatile
    private var lastLoadAt = 0L

    /** 有没有跑着的预加载任务。避免用户连点几下就起一串线程。 */
    private val loading = AtomicBoolean(false)

    /**
     * 装载代数。后台线程装载完成后要确认「我还是当代」，
     * 否则会把上一代的条目发布到新一代的面板上（同 [io.github.kotoriinsky.tsuzuri.hook.feature.sideindex.SideIndex]）。
     */
    @Volatile
    private var generation = 0

    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fp-aggpay-load").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private var codeFirst = true

    /** 诊断用：最近一次 shortcut 装载结果。 */
    @Volatile
    var lastShortcutResult: String = "idle"
        private set

    // ── install / uninstall ───────────────────────────────────────────

    override fun install(ctx: HookContext): InstallReport {
        // 幂等：框架可能重入（多包回调、热重载、改完配置重装）。先拆上一代再装新的。
        teardown()

        val entry = ctx.prefs.value(prefSpec<PrefSpec.Choice>("entry"))
        codeFirst = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("code_first"))

        return when (entry) {
            ENTRY_LOCK_SCREEN -> installLockScreen(ctx)
            ENTRY_QUICK_TILE -> notImplemented(ctx, entry, ENTRY_QUICK_TILE_REASON)
            ENTRY_LONG_PRESS_ICON -> notImplemented(ctx, entry, ENTRY_LONG_PRESS_ICON_REASON)
            else -> {
                ctx.log(Log.WARN, "未知入口 entry=$entry，不装任何 hook")
                InstallReport(0, missing = listOf("entry=$entry"))
            }
        }
    }

    override fun uninstall() {
        teardown()
        super.uninstall()
    }

    /** 拆掉这一代的全部运行时状态。可从任何线程调，重复调安全。 */
    private fun teardown() {
        generation++
        ours.clear()
        panel?.release()
        panel = null
        repository = null
        cached = emptyList()
        lastLoadFailure = null
        lastLoadAt = 0L
        lastShortcutResult = "idle"
        // 线程池不重建：它是个 daemon，闲着不花钱；重建反而可能和上一代的任务抢
    }

    // ── 未实现的入口 ──────────────────────────────────────────────────

    /**
     * 一个 hook 都不装，只把「为什么做不到」交回去。
     *
     * 这里刻意不抛异常、也不装一个假 hook：诊断页要能看见 `hooked=0` + 原因，
     * 用户看到的是「这个特性没生效」，而不是「开了之后一切正常但按下去没反应」。
     */
    private fun notImplemented(ctx: HookContext, entry: String, reason: String): InstallReport {
        ctx.log(Log.WARN, "entry=$entry 在当前实现里做不到：$reason")
        return InstallReport(0, missing = listOf("entry=$entry"), notes = listOf(reason))
    }

    // ── lock_screen 入口 ──────────────────────────────────────────────

    private fun installLockScreen(ctx: HookContext): InstallReport {
        val app = SystemUiEnv.context()
            ?: return InstallReport(0, notes = listOf("no context"))

        // 先把所有落点解析齐，全齐了才开始挂 ——
        // 半套 hook（比如图标改了但提交没接管）会让用户点了之后跳出微信付款码，
        // 这比完全不做更糟。
        val utilCls = loadClass(ctx, CLS_UTIL) ?: return missing(CLS_UTIL, ctx)
        val viewCls = loadClass(ctx, CLS_VIEW) ?: return missing(CLS_VIEW, ctx)
        val itemCls = loadClass(ctx, CLS_ITEM) ?: return missing(CLS_ITEM, ctx)
        val wechatItemCls = loadClass(ctx, CLS_WECHAT_ITEM) ?: return missing(CLS_WECHAT_ITEM, ctx)

        val mGenerate = utilCls.declaredMethods.firstOrNull {
            it.name == M_GENERATE && it.parameterTypes.size == 4 &&
                it.parameterTypes[3] == Int::class.javaPrimitiveType &&
                it.returnType == itemCls
        } ?: return missing("$M_GENERATE(RectF,Region,Context,int)", ctx)

        val mCommit = viewCls.declaredMethods.firstOrNull {
            it.name == M_COMMIT && it.parameterTypes.size == 1 && it.parameterTypes[0] == itemCls
        } ?: return missing(M_COMMIT, ctx)

        val mGetView = method(itemCls, M_GET_VIEW) ?: return missing("$CLS_ITEM#$M_GET_VIEW", ctx)
        val mTitle = method(wechatItemCls, M_GET_TITLE) ?: return missing(M_GET_TITLE, ctx)
        val mSubTitle = method(wechatItemCls, M_GET_SUBTITLE) ?: return missing(M_GET_SUBTITLE, ctx)
        val mTag = method(wechatItemCls, M_GET_TAG) ?: return missing(M_GET_TAG, ctx)

        val p = PaymentPanel(
            context = app,
            onPick = { item -> launch(item, ctx) },
            onInfo = { item -> ctx.log(Log.INFO, "面板长按：${item.key}") },
        )
        panel = p
        val repo = ShortcutRepository(app)
        repository = repo

        val missingList = ArrayList<String>(4)
        var hooked = 0

        // ① 提交：收到我们的实例就弹面板，**不调 proceed()**，MIUI 自己那套不执行
        val hCommit = hookOrNull(ctx, mCommit) { chain ->
            val item = chain.getArg(0)
            if (item != null && ours.contains(item)) {
                // hook 回调本身就在主线程（View.OnClickListener），这里再 post 一层是双保险：
                // show 对线程有硬要求，宁可在队列里多排一次
                mainHandler.post { showPanel(ctx) }
                true
            } else {
                chain.proceed()
            }
        }
        if (hCommit != null) hooked++ else missingList.add(M_COMMIT)

        // ② 生成：把 id 1001 那一格改成我们的表现
        val hGenerate = hookOrNull(ctx, mGenerate) { chain ->
            val id = chain.getArg(3) as? Int
            val created = chain.proceed()
            if (id == HIJACK_ITEM_ID && created != null) {
                ours.add(created)
                // 原件已经给 ImageView 设好了「微信付款码」的图标，这里换掉
                runCatching {
                    val view = mGetView.invoke(created) as? ImageView
                    view?.setImageDrawable(aggregateIcon(app, FOD_ICON_PX))
                }.onFailure { ctx.log(Log.WARN, "换 FOD 图标失败", it) }
            }
            created
        }
        if (hGenerate != null) hooked++ else missingList.add(M_GENERATE)

        // ③ 文案：名称/副标题/埋点 tag 三处都要换，否则界面上会写「微信付款码」而点下去是聚合面板
        for ((m, value) in listOf(
            mTitle to TITLE,
            mSubTitle to SUBTITLE,
            mTag to TAG_VALUE,
        )) {
            val h = hookOrNull(ctx, m) { chain ->
                val self = chain.thisObject
                if (self != null && ours.contains(self)) value else chain.proceed()
            }
            if (h != null) hooked++ else missingList.add(m.name)
        }

        // 关键路径（提交 + 生成）缺一条就不算成功：留着一个「图标改了但提交没接管」的半套 hook，
        // 用户点下去会跳出微信自己的付款码 —— 那比完全不做更糟。
        if (hCommit == null || hGenerate == null) {
            // 此时可能已经挂了标题类 hook；它们只在「实例进了 ours」时才生效，而 ours 永远是空的。
            // 但是为了「关态 == 未安装」这条纪律，还是一起摘掉更干净。
            uninstall()
            ctx.log(Log.ERROR, "FOD 关键落点没挂上，整个入口撤销：$missingList")
            return InstallReport(0, missingList, notes = listOf("fod hook 不完整，已回滚"))
        }

        // shortcut 预加载放后台：install 跑在框架回调上，这里要 load 图标（几次 IPC/IO），
        // 同步做会把 SystemUI 的这次启动拖住。
        preload(ctx, force = true)

        ctx.log(
            Log.INFO,
            "聚合支付（锁屏指纹快捷）已挂上：hooked=$hooked codeFirst=$codeFirst shortcuts=loading"
        )
        return InstallReport(
            hooked = hooked,
            missing = missingList,
            notes = listOf(
                "entry=$ENTRY_LOCK_SCREEN",
                "shortcuts=loading",
                // 说给诊断页听：条目目前是内置默认四项，用户选不了
                "无「要包含哪些支付快捷方式」的参数（缺 PrefSpec 多选类型），用内置默认四项",
            ),
        )
    }

    // ── 面板 ──────────────────────────────────────────────────────────

    /** 弹面板。**主线程**。没有可用条目时也弹（显示空态），见 [PaymentPanel.show]。 */
    private fun showPanel(ctx: HookContext) {
        val p = panel ?: return
        val snapshot = cached
        p.show(snapshot)
        if (snapshot.isEmpty() && lastLoadFailure != null) {
            ctx.log(Log.WARN, "面板没有条目：$lastLoadFailure")
        }
        // 后台刷一次，让**下一次**打开是新的（限流，见 [preload]）
        preload(ctx, force = false)
    }

    private fun launch(item: PaymentShortcut, ctx: HookContext) {
        val repo = repository ?: return
        val ok = repo.start(item)
        ctx.log(Log.INFO, "启动 ${item.key} -> ${if (ok) "已送出" else "失败"}")
    }

    // ── shortcut 预加载 ───────────────────────────────────────────────

    /**
     * 后台装载 shortcut 列表。**可以在任何线程调。**
     *
     * @param force true = 忽略限流（安装时用）
     */
    private fun preload(ctx: HookContext, force: Boolean) {
        val repo = repository ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastLoadAt < RELOAD_MIN_INTERVAL_MS) return
        if (!loading.compareAndSet(false, true)) return
        lastLoadAt = now
        val myGeneration = generation

        // 线程池是 daemon。后台线程里逃出去的异常同样会杀进程（Android 给所有线程都装了
        // 默认未捕获处理器），所以这里连 Throwable 一起兜住。
        try {
            loader.execute {
                val load = try {
                    repo.load(codeFirst)
                } catch (t: Throwable) {
                    ShortcutRepository.Load(emptyList(), "load: " + t.javaClass.simpleName)
                }
                // 顺带把「本机还有哪些支付类快捷方式」记一条 —— 这是将来做
                // 「要包含哪些支付快捷方式」多选配置时的数据来源（目前没有对应的 PrefSpec 类型）
                val discovered = try {
                    repo.discover()
                } catch (t: Throwable) {
                    ShortcutRepository.Load(emptyList(), t.javaClass.simpleName)
                }
                mainHandler.post {
                    loading.set(false)
                    if (myGeneration != generation) return@post // 过期结果，丢
                    cached = load.items
                    lastLoadFailure = load.failure
                    lastShortcutResult = describe(load, discovered)
                    ctx.log(Log.INFO, "shortcut 装载：$lastShortcutResult")
                }
            }
        } catch (t: Throwable) {
            // 线程池被 shutdown 过（卸载竞态）：正常情况，放开闸门等下一代
            loading.set(false)
        }
    }

    private fun describe(load: ShortcutRepository.Load, discovered: ShortcutRepository.Load): String {
        val head = if (load.ok) "default=${load.items.size}" else "default=0(${load.failure})"
        val others = if (discovered.ok) {
            val pkgs = discovered.items.map { it.packageName }.distinct()
            "candidates=${discovered.items.size}/${pkgs.size}pkg"
        } else {
            "candidates=0(${discovered.failure})"
        }
        return "$head $others"
    }

    // ── FOD 项图标 ────────────────────────────────────────────────────

    /**
     * 环里那一格的图标：绿蓝渐变圆角块 + 白色「付」。
     *
     * 为什么不复用微信/支付宝的图标：这一格代表的是**聚合**，用任何一家的图标
     * 都会让用户以为点下去只有那一家。所以自己画一个中性标记。
     *
     * 为什么用 Bitmap 现画而不是放 PNG：这一段跑在 SystemUI 进程里，模块自己的资源
     * 在那边要么读不到、要么要额外绕资源路径；而这里只需要一个方块加一个字。
     * 画一次（几百微秒）就够，之后由 ImageView 复用同一张图。
     *
     * @param sizePx 只决定清晰度。环里的 `ImageView` 是 `FIT_XY`，会被拉成格子大小。
     */
    private fun aggregateIcon(context: Context, sizePx: Int): Drawable {
        val size = sizePx.coerceIn(48, 512)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = size * 0.06f
        val rect = RectF(pad, pad, size - pad, size - pad)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 左上微信绿 → 右下支付宝蓝：一眼能看出「两家都在里面」
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                WECHAT_GREEN, ALIPAY_BLUE, Shader.TileMode.CLAMP,
            )
        }
        val radius = size * 0.26f
        canvas.drawRoundRect(rect, radius, radius, fill)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.46f
            typeface = Typeface.DEFAULT_BOLD
        }
        // drawText 的 y 是基线，要减掉 (ascent+descent)/2 才是视觉居中
        val fm = text.fontMetrics
        canvas.drawText("付", rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, text)

        return BitmapDrawable(context.resources, bmp)
    }

    // ── hook 工具 ─────────────────────────────────────────────────────

    private fun loadClass(ctx: HookContext, name: String): Class<*>? = try {
        Class.forName(name, false, ctx.classLoader)
    } catch (t: Throwable) {
        // 找不到 = 正常情况（系统改版），由调用方降级
        ctx.log(Log.WARN, "class not found: $name")
        null
    }

    private fun method(cls: Class<*>, name: String): Method? =
        cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }

    private fun missing(what: String, ctx: HookContext): InstallReport {
        ctx.log(Log.WARN, "FOD 落点缺失：$what，聚合支付不装")
        return InstallReport(0, missing = listOf(what))
    }

    /**
     * 挂一个 hook。失败返回 null（调用方决定这是不是致命）。
     *
     * `intercept` 里连 `Throwable` 都兜住：hook 回调抛出去 = SystemUI 崩。
     * 这是「异常绝不逃逸」的最后一层，不是第一层 —— 各分支自己也要先 try。
     */
    private inline fun hookOrNull(
        ctx: HookContext,
        m: Method,
        crossinline body: (XposedInterface.Chain) -> Any?,
    ): XposedInterface.HookHandle? = try {
        val h = ctx.module.hook(m).intercept { chain ->
            try {
                body(chain)
            } catch (t: Throwable) {
                ctx.log(Log.ERROR, "聚合支付 hook ${m.name} 异常，已忽略本次", t)
                chain.proceed()
            }
        }
        track(h)
        ctx.log(Log.INFO, "hooked ${m.declaringClass.simpleName}#${m.name}")
        h
    } catch (t: Throwable) {
        ctx.log(Log.WARN, "hook ${m.declaringClass.simpleName}#${m.name} 失败", t)
        null
    }

    /** 按参数名取配置描述。用真实的 `Keys.pref` 拼键 —— 手写字面量写错在编译期发现不了。 */
    private inline fun <reified T : PrefSpec> prefSpec(name: String): T =
        spec.prefs.filterIsInstance<T>().first { it.key == Keys.pref(spec.key, name) }

    // ── 常量 ──────────────────────────────────────────────────────────

    private const val TITLE = "聚合支付"
    private const val SUBTITLE = "微信 / 支付宝 · 付款码与扫一扫"
    private const val TAG_VALUE = "AggregatePayment"

    /** 两次后台刷新之间至少要隔这么久。打开面板一次就是一次刷新，太频繁没意义。 */
    private const val RELOAD_MIN_INTERVAL_MS = 30_000L

    /** FOD 图标画多少像素。环里的格子是 FIT_XY，尺寸只影响清晰度。 */
    private const val FOD_ICON_PX = 192

    private const val WECHAT_GREEN = 0xFF2FB344.toInt()
    private const val ALIPAY_BLUE = 0xFF1677FF.toInt()
}
