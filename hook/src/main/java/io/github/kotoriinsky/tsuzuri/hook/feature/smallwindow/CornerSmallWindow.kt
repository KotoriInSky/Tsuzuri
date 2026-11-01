package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.OverlayArbiter
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.PreviewWallpaperPush
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SwipeEventHub
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 底角呼出小窗：从屏幕下角**斜着朝屏内上滑**，弹出 1–3 层同心弧的应用菜单，
 * 手指滑到某个图标上选中它，抬手把那一项**以小窗启动**。
 *
 * ```
 * DOWN  落在底部两角的矩形里            → 只记状态，不消费（还不知道用户要干什么）
 * MOVE  位移 ≥ 4×slop 且方向在锥内      → 记住方向，仍未消费（4×slop 见 [FanStrict]）
 * MOVE  位移 ≥ 距离闸且方向**仍在**锥内 → 弹出扇形，从这一帧起事件归我们
 * MOVE  扇上滑动                        → 命中变成图标圆（主线程高亮 + 振动）
 * UP    手指落在某个图标圆里            → 以小窗启动它并收窗；没落在任何圆里 → 什么都不做
 * ```
 *
 * ## 形态来自哪
 *
 * **HyperGesture（`com.oxohang.fanfreeform`）**，几何、命中、动画、守卫逐条对应见
 * [FanSpec] / [FanArcs] / [FanTrigger] 的注释（都写了具体文件与行号）。
 * 上一版照的是 Flyme（`research/decompiled/systemuitools/.../windowmode/`），
 * 真机上手感不对，已整体作废 —— `:core` 的 `FanLayout.kt` **留着不动**但不再被引用。
 *
 * ## 与上一版的区别（这不是「拿前台应用变小窗」）
 *
 * 旧形态是「斜滑 → 把当前前台应用缩成小窗」，那是错的：Flyme 的做法是**先弹出应用菜单、
 * 选中哪个才把哪个变小窗**。所以 `CornerGesture`（旧形态的判据）在这一版里不再使用 ——
 * `:core` 里那个类**没有删**（去留由另一个人定），只是这里不再引用。
 *
 * ## 三级线程，各干各的
 *
 * | 线程 | 能做什么 | 不能做什么 |
 * | --- | --- | --- |
 * | 输入分发线程（[CornerFanDetector.onTouch]） | 读几何、算位移、写 volatile | IO、日志、IPC、分配、碰 View |
 * | 主线程（[FanGestureBridge] 的 Handler） | 建/拆窗口、命中、启动、振动 | 做 PackageManager 全量查询 |
 * | 后台线程（[FanTargets] 构建 + [FanGuards] 轮询） | 扫应用清单、读图标、轮询息屏/锁屏/IME | 碰 UI |
 *
 * ## 触摸所有权
 *
 * **我们在过闸之前一个事件都不消费**，过闸之后才把这次手势吃掉，而且浮层本身
 * 是完全不可触摸的窗口（见 [FanOverlay]）。这条纪律同时解决两件事：
 *
 *  1. 底部两角在触发区里的**正常点击**永远归应用
 *     （`shouldPassthroughCapturedCornerTap`）要求的「轻点放行」；
 *  2. 屏幕上任何一条边缘都只有一个我们这边的 touch owner。
 *
 * 已知的、通道本身的代价：WMShell 的输入监视是**旁观**通道，我们只能在过闸后拒绝后续事件，
 * 过闸之前应用已经收到了那些 MOVE（Flyme 的角窗口是真实 owner，所以它能做到「应用什么都看不到」）。
 *
 * ## 和系统的全面屏手势的关系（照 Flyme 的做法压误触）
 *
 * 底部两角是**系统回桌面手势的起手区**（`y` 落在底边、向上滑），所以这个特性的触发区
 * 和它天然重叠。Flyme 那边（`SlideAppsTouchController.java:102-107`）遇到同类问题时的做法是
 * 干脆不在底部条带里响应：`motionEvent.getY() > dragLayer.getHeight() - insets.bottom → 不拦截`。
 * **那一条我们不能抄**：扇形就是要从底角起手（这是它的形态），照抄等于关掉这个特性。
 * 所以我们的做法是「起手区重叠，但判定必须严」：
 *
 * 1. 方向锥（[FanTrigger.directionOk]，逐字来自 `GestureArbitrator.java:5-6,18-29`）：
 *    `inward >= 0.25 × upward`（偏离竖直 ≥ 14.04°）且 `upward >= 0.08 × inward`
 *    （偏离水平 ≥ 4.57°）。**纯竖直上滑必定在锥外** —— 那是回桌面，我们一个事件都不消费。
 *    上界（4.57°）也合适：底角不是返回手势区（返回走左右边缘的中段），近水平内滑没人这么用。
 *    下界 14.04° 是唯一保护「斜着往上的拇指弧线」那一侧的参数，但它**不可能再靠角度区分**
 *    20°–45° 的拇指弧线（那正是用户想回桌面时的常见角度）—— 所以真正的改善放在下面两条。
 * 2. 方向判定推迟到 **4×slop**（[FanRuntime.strictFindPx2] / [FanStrict]）：
 *    原来 32px 处判方向，那是噪声，判错了会被记住。
 * 3. **消费前复判一次方向锥**（[CornerFanDetector.onMove]）：起手斜、中途拐直 → 不消费。
 *
 * ## 当前状态
 *
 * ⚠️ **已实现，未用真手指验证。** 能验的是「装上、类解析到、几何算出来、日志打出来」；
 * 「斜滑弹出的手感」必须真人试。守卫里能做的都做了，做不到的三条在报告里列了证据。
 */
object CornerSmallWindow : Feature(Catalog.require(Catalog.CORNER_SMALL_WINDOW)) {

    /**
     * 日志 tag。**internal 而不是 private**：起手诊断的辅助函数在嵌套类
     * [CornerFanDetector] 里（按下判据在那儿），跨类要取到同一个 tag —— 与其在那边
     * 再写一份字符串字面量，不如让它可见，保持单一来源。
     */
    internal const val TAG = "FP-Fan"

    /** 「拦下回桌面」那条日志的预算（每次安装重置）。见 [installHomeBlock]。 */
    private const val HOME_BLOCK_LOG_MAX = 8

    /** 「不呼出扇形」的两份名单的参数名（竖屏 / 横屏各一份）。键由 [Keys.pref] 拼。 */
    private const val BLOCKLIST_PORTRAIT = "blocklist_portrait"
    private const val BLOCKLIST_LANDSCAPE = "blocklist_landscape"

    /** 已打过的「拦下回桌面」条数。 */
    private var homeBlockedCount = 0

    /** 主线程 handler。**internal**：`CornerFanDetector` 里那条"扇子起来之后"的延迟判据要用它。 */
    internal val mainHandler = Handler(Looper.getMainLooper())

    // ── 每一代安装的部件。install/uninstall 整体换掉，所以全部可空 ─────────

    private var runtime: FanRuntime? = null
    private var detector: CornerFanDetector? = null
    private var bridge: FanGestureBridge? = null
    private var overlay: FanOverlay? = null
    private var guards: FanGuards? = null
    private var executor: ExecutorService? = null
    private var sampler: FanTargets.Recents.Sampler? = null

    /**
     * 「用户已解锁」广播的接收器。见 [registerUnlockWatcher]。
     *
     * 它是 [startScan] 那条退避重试的**语义正确的**那一半：解锁正是「应用清单从
     * direct-boot 视图变成完整视图」的时刻，不受「用户多久才解锁」影响。
     */
    private var unlockWatcher: BroadcastReceiver? = null

    /**
     * 旋转监听的接收器。见 [watchRotation]。
     *
     * 没有它的后果是**具体的**：几何只在装机 / 配置变 / 清单变时算一次，
     * 所以转屏之后热区、半径、命中圆、方向判据全是**上一次发布时**那一套 ——
     * 这就是「横竖屏切换下响应慢」的根因。
     */
    private var rotationWatcher: DisplayManager.DisplayListener? = null

    /**
     * 转屏后**补一次**几何重算的延迟（ms）。
     *
     * 为什么不是「事件到了就够」：旋转刚发生的那一刻 `WindowMetrics` 有时还报旧尺寸
     * （WM 与 DisplayManager 的更新顺序不保证），只算一次会白算。
     * 补一次是**幂等**的（[republishGeometry] 比对尺寸，没变就返回），代价可以忽略。
     */
    private const val ROTATION_RECHECK_MS = 200L

    private var pool: FanTargets.Pool? = null
    private var config: FanConfig? = null

    /** 主线程用。启动 Activity 需要 Context，而 [HookContext] 里刻意没有它（规范 §5）。 */
    private var appContext: Context? = null
    private var hookContext: HookContext? = null

    /** UsageStats 那一路的「最近使用」；拿不到就是空表（采样器兜底）。 */
    private var usageRecents: List<String> = emptyList()

    /**
     * 安装代数。
     *
     * 清单扫描、列表重建、延迟补救都跨越线程边界，中间用户可能已经关掉又打开（框架会重入
     * [install]）。回主线程时必须确认「我还是当代」，否则会把上一代的清单/窗口发到新一代上。
     */
    @Volatile
    private var generation = 0

    /** 几何/清单的快照，输入线程读它。 */
    internal class FanRuntime {
        @Volatile
        var spec: FanSpec? = null

        /**
         * **方向判定**要走的距离（平方，px²）= `(4 × ViewConfiguration.scaledTouchSlop)²`。
         *
         * 为什么要它（防误触）：[FanSpec.decisionPx] 是 `max(touchSlop, 10dp)`
         * （`FanGeometry.kt:621`，上游 `FanRuntime.java:402`），在 3.25 密度下约 32px ——
         * 那个距离上的位移方向基本是**噪声**（第一帧的抖动、指肚接触面变化）。
         * 方向一旦在这一帧被判成「斜滑」，[CornerFanDetector] 会把它记成 DECIDED 并**一直保留**，
         * 于是「先抖一下再笔直往上划（回桌面）」也会走到距离闸，然后被我们消费掉。
         *
         * 这里改用 Flyme 严格策略里的最短距离 `mTouchSlop * 4.0f`
         * （`SingleAxisSwipeDetector.shouldScrollStart:114`，由 `setStrictStrategy(true)` 打开，
         * `SlideAppsTouchController.java:34`）：方向判定推迟到累计走了 4 个 slop 之后再做，
         * 判据才代表「整段路径的走向」而不是「第一帧的抖动」。代价只有 ~32dp 的延迟，
         * 远小于距离闸（[FanSpec.triggerPx]，默认短边的 25%）。
         *
         * 默认 [Float.MAX_VALUE] = 永远不判（发布几何之前手势完全不生效，失败方向是安全的）。
         */
        @Volatile
        var strictFindPx2: Float = Float.MAX_VALUE

        /**
         * 扇形的分页内容（至少一页）。输入线程只读它的**引用和页数**，
         * 不做任何遍历 —— 判定不需要知道有哪些应用。
         */
        @Volatile
        var pages: List<List<FanTarget>> = emptyList()

        /** 可启动应用的总数（不是一页的数量）。守卫用它判「少于 3 项不工作」。 */
        @Volatile
        var poolSize: Int = 0

        /**
         * 竖屏下「不呼出扇形」的包名。**空 = 一个都不屏蔽**
         * （黑名单语义，见 `PrefSpec.AppList.emptyIsValid` —— 与「空 = 走自动挑选」相反）。
         *
         * 主线程**整体替换引用**、输入线程只读：集合本身是不可变的，
         * 所以输入线程上的 `contains` 是纯内存读（无 IPC、无锁）。
         */
        @Volatile
        var blockedPortrait: Set<String> = emptySet()

        /**
         * 横屏下那份名单。**与竖屏那份是两份独立的配置**（用户 2026-09-20 拍板）——
         * 同一个应用在竖屏与横屏下是两种场景（比如游戏：横屏要屏蔽、竖屏不用）。
         */
        @Volatile
        var blockedLandscape: Set<String> = emptySet()
    }

    // ── install / uninstall ───────────────────────────────────────────

    override fun install(ctx: HookContext): InstallReport {
        val app = SystemUiEnv.context()
            ?: return InstallReport(0, notes = listOf("no context"))

        // 幂等：框架可能重入（多包回调、热重载、改配置后重装）。先把上一代整体拆掉。
        teardown()
        val myGeneration = generation

        SystemUiEnv.ForegroundTracker.acquire(key, app)
        // 设置界面的预览底图由 SystemUI 读壁纸交过去（与侧滑索引共用，引用计数；见 PreviewWallpaperPush）
        PreviewWallpaperPush.acquire(key, ctx, app)

        val rt = FanRuntime()
        val ov = FanOverlay(app)
        val gd = FanGuards(app)
        val cfg = readConfig(ctx)

        runtime = rt
        overlay = ov
        guards = gd
        config = cfg
        watchPrefs(ctx)
        refreshBlocklists(ctx)
        appContext = app
        hookContext = ctx
        gd.start()

        val br = FanGestureBridge(
            onShow = { right, x, y -> guarded(ctx, "展开") { onShow(rt, ov, right, x, y) } },
            onPointer = { x, y -> guarded(ctx, "高亮") { ov.pointer(x, y) } },
            onCommit = { x, y, allowHover -> guarded(ctx, "启动") { onCommit(rt, ov, x, y, allowHover) } },
            onCancel = { guarded(ctx, "取消") { ov.dismiss() } },
        )
        bridge = br

        val det = CornerFanDetector(
            runtime = rt,
            overlay = ov,
            guards = gd,
            bridge = br,
            onError = { ctx.log(Log.WARN, "[$TAG] 手势判定异常", it) },
        )
        detector = det
        SwipeEventHub.addListener(det)
        val hub = SwipeEventHub.ensureInstalled(ctx)
        val homeBlock = installHomeBlock(ctx)
        val imeWatch = FanImeState.install(ctx) { track(it) }

        // 应用清单在后台扫：要 loadLabel 上百个包，在安装路径上同步做会把这次回调拖住几百毫秒。
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fp-fan-scan").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        executor = exec
        startScan(ctx, myGeneration, attempt = 1)
        registerUnlockWatcher(ctx, app)
        watchRotation(ctx)

        ctx.log(
            Log.INFO,
            "[$TAG] 底角扇形已挂上：hub=${hub.describe()} " +
                "corners=${cfg.corners} hot=${cfg.hotWidthPercent.toInt()}x${cfg.hotHeightPercent.toInt()}% " +
                "trigger=${cfg.triggerPercent.toInt()}% max=${cfg.maxTargets} " +
                "orient=${cfg.portraitEnabled}/${cfg.landscapeEnabled} " +
                "layout=${cfg.layoutMode}(${cfg.customInner}/${cfg.customMiddle}) haptic=${cfg.haptic}/${cfg.triggerHaptic}",
        )

        return InstallReport(
            // ⚠️ 计数口径：hub 那条输入通道 + 「拦回桌面」那条 hook 各自算一个，
            // 任一为 0 都要能从这行看出来（`hooked=0` 当失败处理）。
            hooked = (if (hub.isEffective) 1 else 0) + homeBlock,
            missing = hub.missing,
            notes = hub.notes + listOf("corners=" + cfg.corners, "homeBlock=" + homeBlock, "imeWatch=" + imeWatch),
        )
    }

    /**
     * 拦住「上滑回桌面」那个转移 —— **扇形接管之后不让系统再把人送回桌面**。
     *
     * 落点（`research/decompiled/wms/.../miuimultiwinswitch/MulWinSwitchTransition.java:495-506`）：
     * 方法末尾那句 `mContext.startActivityAsUser(getHomeIntent(mContext), ...)` 就是**真正去桌面**。
     * 而它开头本来就有一句同类守卫（`isHomeVisible() && 动画中 → "startHomeTransition but home visible"; return`）
     * —— 也就是说「在特定条件下直接早退」是这套代码自己的既有语义，我们只是多加一个条件。
     *
     * 条件 = [OverlayArbiter.fanOwnsGesture]（这次触摸已被扇形接管）。**纯竖直上滑不会被接管**
     * → 标志为 false → 原样放行，正常回桌面完好。
     *
     * ⚠️ 它拦不住"在我们接管**之前**就已经发起"的那次转移 —— 那只能靠接管更早
     * （接管门槛已收到 **总位移 2dp** 的常量，见 `FanTrigger.TAKEOVER_SLOP_DP`）。
     * 这条 hook 覆盖的是接管之后系统才发起的。
     * ⚠️ 找不到类/方法就静默跳过（ROM 改版），但**必须进 missing**，不能悄悄少装。
     */
    private fun installHomeBlock(ctx: HookContext): Int {
        val cls = try {
            Class.forName(
                "com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchTransition",
                false,
                ctx.classLoader,
            )
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "[$TAG] 找不到 MulWinSwitchTransition，拦回桌面未装")
            return 0
        }
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "startHomeTransition") continue
            try {
                track(
                    ctx.module.hook(m).intercept { chain ->
                        if (OverlayArbiter.fanOwnsGesture) {
                            // 有预算地留痕：这条是"呼出扇形后真的把回桌面拦下来了"的唯一证据
                            if (homeBlockedCount < HOME_BLOCK_LOG_MAX) {
                                homeBlockedCount++
                                ctx.log(
                                    Log.INFO,
                                    "[$TAG] 拦下回桌面：本次手势已归扇形（第 " + homeBlockedCount + " 次）",
                                )
                            }
                            null // void 方法：直接早退，不发起回桌面转移
                        } else {
                            // 放行 = 系统这次**真的**要回桌面了 → 记下时刻。
                            // 扇形那边据此判断"本次手势已被系统先拿走"（见 OverlayArbiter.homeStartedAtMs）。
                            OverlayArbiter.homeStartedAtMs = android.os.SystemClock.uptimeMillis()
                            chain.proceed()
                        }
                    },
                )
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "[$TAG] hook startHomeTransition 失败", t)
            }
        }
        if (n == 0) ctx.log(Log.WARN, "[$TAG] startHomeTransition 一个都没匹配到")
        return n
    }

    override fun uninstall() {
        // 关态 = 和没装模块完全一致：摘监听、收窗口、停线程、丢清单、停守卫。
        // ForegroundTracker 与侧滑索引共用：按 key 释放，两个都卸了才真正停
        teardown()
        SystemUiEnv.ForegroundTracker.release(key)
        PreviewWallpaperPush.release(key)
        super.uninstall()
    }

    /** 把这一代拆掉。可从任何线程调，重复调安全。 */
    private fun teardown() {
        generation++

        detector?.let {
            SwipeEventHub.removeListener(it)
            it.reset()
        }
        detector = null

        guards?.stop()
        guards = null
        FanImeState.reset()

        sampler?.stop()
        sampler = null

        unlockWatcher?.let { runCatching { appContext?.unregisterReceiver(it) } }
        unlockWatcher = null

        // 必须在 `appContext` 被清掉之前注销（注销要用它拿 DisplayManager）
        rotationWatcher?.let { l ->
            runCatching {
                (appContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.unregisterDisplayListener(l)
            }
        }
        rotationWatcher = null

        executor?.shutdownNow()
        executor = null

        bridge?.stop()
        bridge = null

        // release 内部会判断线程：不在主线程就转投主线程
        overlay?.release()
        overlay = null

        runtime?.let {
            it.spec = null
            it.pages = emptyList()
            it.poolSize = 0
        }
        runtime = null

        pool = null
        config = null
        usageRecents = emptyList()
        appContext = null
        hookContext = null
    }

    // ── 配置 ──────────────────────────────────────────────────────────

    /**
     * 配置一改就重算 —— 不重启 SystemUI。
     *
     * 为什么必须显式注册：`readConfig` 原来只在 install 时跑一次，改任何一个参数
     * 都得重启进程才生效，而设置界面上是即时滑杆 —— 用户拖完看不到变化，
     * 会当成「这个参数没用」。这是会直接影响调参体验的缺陷。
     *
     * 回调可能在 Binder 线程上（RemotePreferences 的变更通知走框架），而重算几何
     * 要读 displayMetrics、要动 View，必须回主线程。
     */
    private fun watchPrefs(ctx: HookContext) {
        val store = ctx.store ?: return
        val prefix = spec.key + "."
        runCatching {
            store.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == null || !key.startsWith(prefix)) return@registerOnSharedPreferenceChangeListener
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        config = readConfig(ctx)
                        // 名单类参数也必须「改完立刻生效」——
                        // 它不在 FanConfig 里，所以单独刷一次。
                        refreshBlocklists(ctx)
                        requestPublish(generation)
                        Log.i(TAG, "配置已重算：" + key)
                    }.onFailure { Log.w(TAG, "配置重算失败: " + key, it) }
                }
            }
        }.onFailure { Log.w(TAG, "注册配置监听失败，参数需重启进程才生效", it) }
    }

    /**
     * 读参数。**只有这里**把 prefs 变成 [FanConfig]。
     *
     * 参数模型照 HyperGesture（`com.oxohang.fanfreeform`）：它把参数分成
     * **直接配**（有 `KEY_*` 常量、由 `GestureConfig.from` 读）与
     * **派生**（无键，运行时由别的参数算）两类。这里只读**直接配**的那些；
     * 派生量（真实半径 / 真实图标直径 / 外排数量）全部在 [FanConfig.spec] 里算，
     * 一个都不做成 pref —— 那会让用户去调一个本该算出来的值。
     */
    private fun readConfig(ctx: HookContext): FanConfig = FanConfig(
        // ── 触发区 ──
        // ⚠️ 宽高的**基准不同**：宽相对屏幕宽、高相对屏幕高
        hotWidthPercent = slider(ctx, "hot_width_percent"),
        hotHeightPercent = slider(ctx, "hot_height_percent"),
        // 距离闸的基准是**短边**（和上面两个不同）
        triggerPercent = slider(ctx, "trigger_percent"),
        corners = ctx.prefs.value(prefSpec<PrefSpec.Choice>("corners")),
        portraitEnabled = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("portrait_enabled")),
        landscapeEnabled = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("landscape_enabled")),
        // ── 振动：两个键各管一段 ──
        // 呼出那一下（trigger_haptic）、选中项变化（haptic）。
        // HyperGesture 是 `globalHaptic && bottomTriggerHaptic`，我们没有全局 haptic 键
        // （框架的总开关管开了没开），所以这里两段独立。
        triggerHaptic = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("trigger_haptic")),
        haptic = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("haptic")),
        // ── 排列 ──
        layoutMode = ctx.prefs.value(prefSpec<PrefSpec.Choice>("fan_layout_mode")),
        maxTargets = slider(ctx, "fan_max_targets").toInt(),
        // 自定义模式下内/中排的容量。**没有外排**：它是派生量
        // （`FanRowAllocation.normalize` 里 `outer = total - inner - middle`），
        // 暴露成滑杆就是「声明了但没接线」。
        customInner = slider(ctx, "fan_custom_inner_count").toInt(),
        customMiddle = slider(ctx, "fan_custom_middle_count").toInt(),
        // ── 半径/图标：都是**初值**，实际值由 spec() 修正（防重叠）──
        radiusPercent = slider(ctx, "selection_radius_percent"),
        iconDp = slider(ctx, "icon_size_dp"),
        // ── 动效 ──
        rotationDeg = slider(ctx, "fan_rotation"),
        animationSpeed = slider(ctx, "animation_speed"),
        revealAmount = slider(ctx, "fan_reveal_amount"),
        selectionScalePercent = slider(ctx, "selection_scale"),
        selectionRing = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("selection_ring")),
    )

    /**
     * 读一个滑杆，并**按 Catalog 声明的范围夹取**。
     *
     * 这是 HyperGesture 参数模型里我们原先缺的一环：它在加载时对**每一个**值调 clamp
     * （`GestureConfig.java:212-256` 的构造函数里逐条 `clamp(...)`），所以
     * 「范围」是运行时强制的，不只是界面上的刻度。我们原来只靠界面不越界，
     * 于是旧版本存下的值（比如上一版 `hot_width_percent` 的量程是 20–100）
     * 会原样留在新版本里，声明的 min/max 形同虚设。
     *
     * 范围从 [PrefSpec.Slider] 自己读，不另抄一份常量 —— 单一事实来源。
     */
    private fun slider(ctx: HookContext, name: String): Float {
        val s = prefSpec<PrefSpec.Slider>(name)
        return ctx.prefs.value(s).coerceIn(s.min, s.max)
    }

    /**
     * 按参数名取配置描述。
     *
     * 用真实的 `Keys.pref` 拼键而不是手写字面量 —— 键名写错在编译期发现不了，
     * 运行时只表现为「这个设置项没反应」，是最难查的一类 bug。
     */
    private inline fun <reified T : PrefSpec> prefSpec(name: String): T =
        spec.prefs.filterIsInstance<T>().first { it.key == Keys.pref(spec.key, name) }

    /**
     * 把两份「不呼出扇形」的名单读进运行时。**主线程**。
     *
     * 走 [watchPrefs] 那条链路（名单在 `corner_small_window.` 前缀下，改了会触发回调），
     * 所以用户在界面上勾完/取消完**立刻生效**，不需要重启进程。
     *
     * 读不到（键没声明 / 存储异常）→ 空集 = **一个都不屏蔽** = 与没装模块一致的那个方向。
     */
    private fun refreshBlocklists(ctx: HookContext) {
        val rt = runtime ?: return
        rt.blockedPortrait = readBlocklist(ctx, BLOCKLIST_PORTRAIT)
        rt.blockedLandscape = readBlocklist(ctx, BLOCKLIST_LANDSCAPE)
    }

    private fun readBlocklist(ctx: HookContext, name: String): Set<String> {
        val s = runCatching { prefSpec<PrefSpec.AppList>(name) }.getOrNull() ?: return emptySet()
        return runCatching { ctx.prefs.value(s) }.getOrDefault(emptySet())
    }

    // ── 清单（主线程） ─────────────────────────────────────────────────

    /**
     * 后台扫描结束。只有 [FanTargets.Result.Ready] 才会发布清单 ——
     * 其余两种都是「没有清单」，手势会因为 `state.spec == null` 而完全不生效。
     */
    /**
     * 起一次应用清单扫描（后台线程），扫完回主线程发布。
     *
     * ## 为什么必须能重试（2026-09-15 修「重启后扇形整个开机周期不生效」）
     *
     * 这段代码在**开机极早期**就会跑（模块在 SystemUI 起来 2 秒内装完），而那时设备通常
     * **还没首次解锁** —— PackageManager 处于 direct-boot 视图，只暴露 direct-boot 感知的
     * 应用，于是 `FanTargets.build` 只扫到 2 个包、返回 `TooFew`。原来那条路是
     * 「扫一次，不可用就 return」，于是**这一代永远拿不到 spec**，
     * [CornerFanDetector.onDown] 的第一道几何守卫（`runtime.spec == null`）直接把自己挡住。
     *
     * 设备实证（`/data/adb/lspd/log/modules_2026-09-15T09:15:50.642258.log`，一次真实重启）：
     *
     * ```
     * 09:15:57  底角扇形已挂上：hub=hooked=1, notes=handlers=3|monitor=true|pilfer=true …
     * 09:15:58  应用清单不可用：too_few          ← 从此再没有任何扫描记录
     * 09:16:17  侧滑索引：应用清单 ready(slots=27, apps=151)   ← 索引靠包变化广播重扫，恢复了
     * ```
     *
     * 现象就是**用户报的**「重启后好像要重新开启一次模块才生效」：关开一次 = 重启 SystemUI，
     * 那时早已解锁 → 扫到 151 个包 → 扇形正常。索引不受影响（它有包变化监听），
     * 所以症状表现为「索引能用、扇形不能用」，很容易被误判成 ROM 或配置问题。
     *
     * 退避 [SCAN_RETRY_BASE_MS] 起、每次翻倍，最多 [SCAN_RETRY_MAX] 次（约两分钟）——
     * 覆盖「用户从开机到首次解锁」这段时间。到点仍不可用就**明确记一条 WARN**（不静默）：
     * 这条链路此前一处日志都没有，于是「重启后扇形没反应」只能靠猜。
     */
    private fun startScan(ctx: HookContext, myGeneration: Int, attempt: Int) {
        if (myGeneration != generation) return
        val app = appContext ?: return
        val exec = executor ?: return
        exec.execute {
            // 后台线程逃出去的异常一样会杀掉 SystemUI（系统给所有线程装了未捕获处理器）。兜住。
            val result = try {
                FanTargets.build(app)
            } catch (t: Throwable) {
                FanTargets.Result.Unavailable("build: " + t.javaClass.simpleName)
            }
            val usage = if (result is FanTargets.Result.Ready) {
                FanTargets.Recents.fromUsageStats(app, USAGE_RECENT_LIMIT)
            } else {
                null
            }
            mainHandler.post { onScanDone(ctx, myGeneration, result, usage, attempt) }
        }
    }

    /**
     * **用户已解锁**广播：开机后「应用清单从 direct-boot 视图变成完整视图」的那一刻。
     *
     * ## 为什么要有这条（与 [startScan] 的退避重试是同一件事的两半）
     *
     * 退避重试解决「扫得太早」的**一般情形**，但它的窗口是有限的（约两分钟）——
     * 如果用户开机后很久才解锁，重试用光之后清单仍然是 direct-boot 视图，扇形依旧不生效。
     * 解锁广播是**语义正确**的那一半：它一响，扫描就一定能拿到完整清单，与解锁早晚无关。
     * 两条合起来，这个故障类别才算关掉（退避负责「用户解锁很快」与「解锁广播被 ROM 挡掉」，
     * 广播负责「解锁很晚」）。
     *
     * ## 只在清单还没就绪时才重扫
     *
     * 清单已经好了就什么都不做：解锁时重扫一遍要 loadLabel 上百个包
     * （虽然只在后台线程，但白做就是白做）。
     *
     * ## 注册失败不是错误
     *
     * 收不到就退化成「只有退避重试」，记一条即可 —— 与侧滑索引的包变化监听同一条纪律
     * （见 `SideIndex.registerPackageWatcher`，包括 `RECEIVER_NOT_EXPORTED` 的用法：
     * 只收系统发的广播，别的应用没法用它给我们塞事件）。
     */
    private fun registerUnlockWatcher(ctx: HookContext, appContext: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 广播回调跑在主线程；任何异常都不能逃出去（那就是 SystemUI 崩溃）
                try {
                    if (pool != null) {
                        ctx.log(Log.INFO, "[$TAG] 用户已解锁，但应用清单已就绪，不重扫")
                        return
                    }
                    ctx.log(Log.INFO, "[$TAG] 用户已解锁 → 重扫应用清单（开机早期那次是 direct-boot 视图）")
                    startScan(ctx, generation, attempt = 1)
                } catch (t: Throwable) {
                    ctx.log(Log.WARN, "[$TAG] 解锁后重扫清单的请求失败", t)
                }
            }
        }
        val ok = try {
            appContext.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                Context.RECEIVER_NOT_EXPORTED,
            )
            true
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "[$TAG] 注册不到解锁广播，清单只靠安装时的扫描 + 退避重试", t)
            false
        }
        if (!ok) {
            runCatching { appContext.unregisterReceiver(receiver) }
            return
        }
        unlockWatcher = receiver
        // 注册成功也留一条：不然「注册失败静默降级」与「广播根本没来」在日志里长得一样 ——
        // 而这正是这一轮排障反复撞到的形态（功能不生效，却没有任何一处说得出为什么）。
        ctx.log(Log.INFO, "[$TAG] 解锁广播已注册（清单就绪前的兜底重扫）")
    }

    private fun onScanDone(
        ctx: HookContext,
        myGeneration: Int,
        result: FanTargets.Result,
        usage: List<String>?,
        attempt: Int,
    ) {
        if (myGeneration != generation) {
            ctx.log(Log.INFO, "[$TAG] 丢弃过期清单：" + FanTargets.describe(result))
            return
        }
        lastScanResult = FanTargets.describe(result)
        try {
            if (result !is FanTargets.Result.Ready) {
                // 见 [startScan]：开机早期（未首次解锁）扫到的是 direct-boot 视图，必须重试
                if (attempt < SCAN_RETRY_MAX) {
                    val delay = SCAN_RETRY_BASE_MS shl (attempt - 1)
                    ctx.log(
                        Log.INFO,
                        "[$TAG] 应用清单不可用：" + FanTargets.describe(result) +
                            " → " + delay + "ms 后重试（第 " + (attempt + 1) + "/" + SCAN_RETRY_MAX + " 次）",
                    )
                    mainHandler.postDelayed({ startScan(ctx, myGeneration, attempt + 1) }, delay)
                } else {
                    ctx.log(
                        Log.WARN,
                        "[$TAG] 应用清单重试 " + attempt + " 次仍不可用（" + FanTargets.describe(result) +
                            "），本次开机扇形不生效；重启 SystemUI 即可恢复",
                    )
                }
                return
            }
            pool = result.pool
            usageRecents = usage ?: emptyList()
            lastRecentsSource = if (usage == null) "sampler" else "usage+" + usage.size

            // 「最近使用」的实时采样：只在清单就绪后启动（它要用清单判「这个包能不能启动」）
            val s = FanTargets.Recents.Sampler(
                isLaunchable = { pkg -> pool?.launchable(pkg) == true },
                onChanged = { mainHandler.post { onRecentsChanged(myGeneration) } },
            )
            sampler = s
            s.start()

            requestPublish(myGeneration)
        } catch (t: Throwable) {
            Log.e(TAG, "发布应用清单失败", t)
        }
        ctx.log(Log.INFO, "[$TAG] 应用清单：${FanTargets.describe(result)} recents=$lastRecentsSource")
    }

    private fun onRecentsChanged(myGeneration: Int) {
        if (myGeneration != generation) return
        try {
            requestPublish(myGeneration)
        } catch (t: Throwable) {
            Log.e(TAG, "更新扇形清单失败", t)
        }
    }

    /**
     * 请求重建扇形清单。
     *
     * **两段式**：合成（含取图标）在后台线程做，几何与发布在主线程做。
     * 理由：`Pool.compose` 里可能要给新进榜的应用取图标（跨进程调用），
     * 而它每次「最近使用」变化都会被叫到 —— 一个应用图标几毫秒，攒在 SystemUI 的
     * 主线程上就是掉帧。几何必须回主线程是因为它要读 `displayMetrics`。
     */
    private fun requestPublish(myGeneration: Int) {
        if (myGeneration != generation) return
        val p = pool ?: return
        val cfg = config ?: return
        val exec = executor ?: return
        val recents = mergedRecents()
        exec.execute {
            val pages = try {
                p.composePages(recents, cfg.maxTargets)
            } catch (t: Throwable) {
                Log.e(TAG, "合成扇形清单失败", t)
                emptyList()
            }
            mainHandler.post { publish(myGeneration, pages, p.size) }
        }
    }

    /** 采样器在前（本次开机后的真实顺序），UsageStats 在后（补跨重启的历史）。 */
    private fun mergedRecents(): List<String> {
        val live = sampler?.current ?: emptyList()
        if (usageRecents.isEmpty()) return live
        val merged = ArrayList<String>(live.size + usageRecents.size)
        merged.addAll(live)
        merged.addAll(usageRecents)
        return merged
    }

    /**
     * 发布清单与几何。**主线程**。
     *
     * 两样东西必须同时发布：浮层要画的应用列表，以及输入线程要用的几何
     * （[FanRuntime.spec]）。**槽位数在这里算一次**，然后同时喂给几何和浮层：
     * 层分配、半径修正、命中圆全都由项数推出来，两边各算一次迟早会不一致
     * （症状是「看到的图标」和「碰到的图标」错位，或者扇子边缘按不到）。
     */
    private fun publish(myGeneration: Int, pages: List<List<FanTarget>>, poolSize: Int) {
        if (myGeneration != generation) return
        val rt = runtime ?: return

        if (poolSize < FanTargets.MIN_TARGETS || pages.isEmpty() || pages[0].isEmpty()) {
            // 少于 3 项整个手势不工作（GestureConfig.ready()）
            rt.pages = emptyList()
            rt.poolSize = poolSize
            rt.spec = null
            Log.w(TAG, "可启动应用不足 ${FanTargets.MIN_TARGETS} 个，手势不生效")
            return
        }

        val spec = specFor(pages) ?: return
        rt.pages = pages
        rt.poolSize = poolSize
        applySpec(rt, spec, pages, poolSize, "扇形几何就绪")
        Log.i(TAG, lastSummary + " " + pages[0].joinToString(" / ") { it.label })
    }

    /**
     * 按**当下屏幕**给这份清单算一份几何。**主线程**。
     *
     * 首次发布与转屏重算共用它 —— 两者唯一的区别是「清单从哪来」（参数 / 已发布的那份），
     * 几何这一步完全一样。分成两个函数写，迟早只有一处跟着屏幕尺寸改。
     */
    private fun specFor(pages: List<List<FanTarget>>): FanSpec? {
        val cfg = config ?: return null
        val app = appContext ?: return null
        if (pages.isEmpty() || pages[0].isEmpty()) return null
        val metrics = displayMetricsOf(app)
        // 多页时每页末位是「⋯」翻页槽（见 FanOverlay 的注释），它也是一个槽、也参与几何
        val slots = pages[0].size + if (pages.size > 1) 1 else 0
        return cfg.spec(
            screenW = metrics[0],
            screenH = metrics[1],
            density = app.resources.displayMetrics.density,
            touchSlop = touchSlopOf(app),
            count = slots,
        )
    }

    /**
     * 把一份几何写进运行时。**主线程**。
     *
     * 写序不能反（见 [FanRuntime.strictFindPx2] 的注释）：两个字段都是 volatile，
     * 输入线程先读 `spec` 再读严格距离，反过来会露出「新 spec + 旧距离」的窗口。
     */
    private fun applySpec(
        rt: FanRuntime,
        spec: FanSpec,
        pages: List<List<FanTarget>>,
        poolSize: Int,
        headline: String,
    ) {
        val app = appContext ?: return
        val strictFind = touchSlopOf(app) * FanStrict.FIND_SLOP_MULTIPLIER
        rt.strictFindPx2 = strictFind * strictFind
        rt.spec = spec
        lastSummary = "pool=" + poolSize + " pages=" + pages.size +
            " first=" + pages[0].size + " " + screenDesc + " " + spec.describe() +
            " 严格判定距离=" + strictFind.toInt() + "px(4×slop)"
        Log.i(TAG, headline + "：" + lastSummary)
    }

    /**
     * 转屏后**只重算几何**（用户点名的「快速切换呼出形态」）。
     *
     * ## 为什么不重跑清单
     *
     * 清单（`pages`）与项数跟朝向**无关**：应用没变、图标没变、页数没变。
     * 重跑一次 `composePages` 是「异步 + 上百次图标解码」，纯浪费，
     * 而且要等它回来 —— 那就不是「立刻生效」了。
     *
     * ## 幂等
     *
     * 旋转事件可能连着来好几条（甚至尺寸没变也会来），所以先比对新旧屏幕尺寸，
     * 相同就直接返回 —— 否则一次转屏会在日志里刷出好几条几何。
     */
    private fun republishGeometry(myGeneration: Int) {
        if (myGeneration != generation) return
        val rt = runtime ?: return
        val pages = rt.pages
        if (pages.isEmpty() || pages[0].isEmpty()) return
        val old = rt.spec ?: return
        val spec = specFor(pages) ?: return
        if (old.screenW == spec.screenW && old.screenH == spec.screenH) return
        applySpec(rt, spec, pages, rt.poolSize, "转屏重算几何")
    }

    /**
     * 转屏监听：**屏幕一转就重算几何**，不等重启进程。
     *
     * ## 为什么必须显式注册
     *
     * [publish] 只在装机 / 配置变 / 清单变时跑，转屏**不**触发它。于是横屏下的
     * 热区宽高、半径、命中圆、守卫 4 的方向判据全是竖屏那一套 ——
     * 用户感知到的就是「横竖屏切换下呼出形态不对 / 要等一会儿才对」。
     *
     * ## 为什么用 `DisplayManager` 而不是 `onConfigurationChanged`
     *
     * 前者在显示参数变化的那一刻就回调（更早），后者要等 `Resources` 重建完。
     * 「快速响应」是这次的需求，所以取早的那一路。
     *
     * ## 失败方向是安全的
     *
     * 拿不到 `DisplayManager` / 注册抛异常 → 记一条 WARN，退化成「重启进程才生效」
     * （= 加这个监听之前的行为），不会让手势坏掉。
     */
    private fun watchRotation(ctx: HookContext) {
        val app = appContext ?: return
        val myGeneration = generation
        val dm = runCatching {
            app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        }.getOrNull()
        if (dm == null) {
            ctx.log(Log.WARN, "[$TAG] 拿不到 DisplayManager：转屏不会重算几何（退化为重启进程才生效）")
            return
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                // 回调不在主线程；几何要读 WindowMetrics、要动 View，必须转过来
                mainHandler.post {
                    // 转屏时浮层若在显示 → 先收（照 Flyme `AppLauncherWindow.N()`：
                    // 转屏 stopForwarding + 收窗重建）。扇子画在旧几何上 =
                    // 「看到的图标和碰到的图标错位」的最快形态，不赌 WindowMetrics 的时序。
                    if (overlay?.visible == true) {
                        Log.i(TAG, "转屏 → 收起展开中的扇形（旧几何作废）")
                        overlay?.dismiss()
                    }
                    republishGeometry(myGeneration)
                }
                // 兜底：旋转刚发生时 WindowMetrics 可能还是旧尺寸，稍后按幂等判据再确认一次
                mainHandler.postDelayed({ republishGeometry(myGeneration) }, ROTATION_RECHECK_MS)
            }
        }
        try {
            dm.registerDisplayListener(listener, mainHandler)
            rotationWatcher = listener
            ctx.log(Log.INFO, "[$TAG] 旋转监听已注册（转屏立刻重算几何）")
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "[$TAG] 注册旋转监听失败，转屏不会重算几何", t)
        }
    }

    /** 两次「起手时核对出朝向不一致」之间的最小间隔（ms）。防 ROM 异常时每次 DOWN 刷日志。 */
    private const val GEOMETRY_REFRESH_MIN_INTERVAL_MS = 1000L

    @Volatile
    private var lastGeometryRefreshRequestMs = 0L

    /**
     * **起手时的兜底核对**：`spec` 的朝向与 Resources 报的不一致时请求重算。主线程。
     *
     * 为什么有它：`watchRotation` 是**推**的通道，ROM 可能吞事件 / 迟到（未在真机验证过）。
     * 这是第二条独立的**拉**通道 —— 输入线程每次 DOWN 顺手比对一次，不等事件。
     *
     * 频率预算：重算是幂等的，但「重算读到旧尺寸 → 什么都没改 → 下次 DOWN 又不一致」
     * 会在异常窗口里每次 DOWN 都来一遍，所以限流（日志跟着重算走，也在主线程）。
     */
    internal fun requestGeometryRefresh(reason: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastGeometryRefreshRequestMs < GEOMETRY_REFRESH_MIN_INTERVAL_MS) return
        lastGeometryRefreshRequestMs = now
        mainHandler.post {
            Log.i(TAG, reason + " → 重算几何")
            republishGeometry(generation)
        }
    }

    /**
     * **当下**的朝向（`Resources.configuration`）。纯内存读、无分配、无 IPC ——
     * 输入线程上唯一付得起的「直接读屏幕状态」的方式。
     *
     * 注意它只能给**布尔**：热区 / 半径 / 命中圆要的是像素宽高，那个只能靠重算。
     * 读不到 / 未定义（`ORIENTATION_UNDEFINED`）→ `null` = 交给 [watchRotation] 负责。
     */
    internal fun liveOrientationIsLandscape(): Boolean? = runCatching {
        when (appContext?.resources?.configuration?.orientation) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> true
            android.content.res.Configuration.ORIENTATION_PORTRAIT -> false
            else -> null
        }
    }.getOrNull()

    /**
     * 屏幕的**真实**尺寸（宽、高，px）。
     *
     * 用 `WindowManager.getCurrentWindowMetrics().getBounds()` 而不是
     * `Resources.displayMetrics`：输入事件给的是整块显示器的坐标（含状态栏/导航栏），
     * 而 displayMetrics 有可能已经扣掉了系统栏 —— 两套坐标混用会让「底部两角」
     * 在垂直方向差出一条导航栏的高度。参考实现取的也是 `getCurrentWindowMetrics().getBounds()`
     * （`FanTriggerCapture.java:156`）。
     *
     * 拿不到（反射/API 不可用）就退回 displayMetrics。
     *
     * [screenDesc] 记下**到底用了哪一套、值是多少**：几何日志里的 `hot=` 是从这个值乘出来的，
     * 不写清楚的话复算的人（和我自己）都得猜。
     */
    private fun displayMetricsOf(context: Context): FloatArray {
        val dm = context.resources.displayMetrics
        val bounds = runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val b = wm?.currentWindowMetrics?.bounds
            if (b != null && b.width() > 0 && b.height() > 0) b else null
        }.getOrNull()
        screenDesc = if (bounds != null) {
            "scr=" + bounds.width() + "x" + bounds.height() + "(bounds)"
        } else {
            "scr=" + dm.widthPixels + "x" + dm.heightPixels + "(dm)"
        }
        return floatArrayOf(
            (bounds?.width() ?: dm.widthPixels).toFloat(),
            (bounds?.height() ?: dm.heightPixels).toFloat(),
        )
    }

    private fun touchSlopOf(context: Context): Float = try {
        android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    } catch (_: Throwable) {
        // 拿不到就用 8dp 估 —— 它只是方向判定的最小位移，量级对了就够了
        8f * context.resources.displayMetrics.density
    }

    // ── 启动（主线程） ─────────────────────────────────────────────────

    private fun onShow(rt: FanRuntime, ov: FanOverlay, openToRight: Boolean, x: Float, y: Float) {
        val s = rt.spec ?: return
        val pages = rt.pages
        if (pages.isEmpty()) return
        val shown = ov.show(s, pages, openToRight, x, y)
        if (shown) {
            openedCount++
            FanImeState.hideIfVisible()
        } else {
            missCount++
        }
    }

    private fun onCommit(rt: FanRuntime, ov: FanOverlay, x: Float, y: Float, allowHover: Boolean) {
        val hit = ov.commit(x, y, allowHover)
        lastHit = hit?.label
        if (hit == null) return
        val app = appContext ?: return
        val ctx = hookContext ?: return

        // ⚠️ 扇子里的项有**两类**（应用 / 快捷方式），必须走 [FanShortcuts.launch] 分发：
        // 它按 `shortcutId` 是不是 null 决定用 `LauncherApps.startShortcut` 还是既有那套
        // 三级降级的小窗启动。**不能只按包名启动** —— 用户点的是「微信·扫一扫」，
        // 只按包名启动会打开微信本身，而那看起来像「快捷方式没生效」，
        // 排查时又很容易被怀疑到目录/权限头上（真实发生过：这一行原来只传了包名）。
        //
        // `allowRoot` **每次点击现读**（不是缓存在 rt 里）：这个开关的作用是
        // 「导出的活动开不了 → 换成 root 试」，用户发现开不了时会立刻去设置里打开它，
        // 而缓存住就意味着他还得重启 SystemUI 才能生效 —— 那正是这个项目
        // 一直在消灭的体验。读一次 RemotePreferences 的成本远低于一次启动。
        val allowRoot = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("root_activities"))
        val level = FanShortcuts.launch(ctx, app, hit, allowRoot)
        lastLaunch = hit.key
        lastLevel = level
        if (level == 0) {
            Log.w(TAG, "启动失败（快捷方式与应用两条路都没走通）：${hit.key}")
            return
        }
        Log.i(
            TAG,
            "以小窗启动：${hit.label} (${hit.key}) 走通第 $level 级（" +
                FanShortcuts.describeLevel(level) + "）",
        )

        // **启动了不等于起来了**：MIUI 的闸门 / 目标不支持 resize / options 被忽略，
        // 任何一种都会让应用以全屏起来，而我们这边完全无感。自检把结论写进日志
        // （成功 = 「小窗自检：xxx 已在 freeform（mode=5）」，失败 = WARN + 观察到的 mode），
        // 这样这条链路才有可验证的证据，而不是只有「hook 挂上了」。
        //
        // 它内部起自己的后台线程做轮询 + Binder 调用，所以在主线程上这么调是安全的
        // （规范禁的是输入线程/主线程上的同步 IPC，不是「有个后台线程在跑」）。
        //
        // 只对**应用项**做这个自检，三个条件缺一不可：
        //
        //  - 快捷方式走 `startShortcut`，不经过 `verifyFreeformLaunch` 查的那条启动路径；
        //  - 链接项（`link:<uri>`）走 `FanLinks.launch` 起的是**别人**的 Activity，
        //    它的 `packageName` 是空串（宿主是启动那一刻才解析的）——
        //    拿空包名去查会超时，然后打一条「小窗自检失败」的 WARN，
        //    把一次成功的启动报成失败；这正是「`hooked=0` 要当失败」的反面：
        //    **一条会误报失败的日志比没有日志更糟**，它会让真问题被淹掉。
        if (!hit.isShortcut && !hit.isLink && hit.packageName.isNotEmpty()) {
            SystemUiEnv.verifyFreeformLaunch(ctx, hit.packageName)
        }
    }

    // ── 诊断（给诊断页/日志用） ────────────────────────────────────────

    /** 最近一次算几何用的屏幕尺寸 + 来源（`bounds` / `dm`），写进几何日志。 */
    @Volatile
    private var screenDesc: String = "scr=?"

    @Volatile
    var lastSummary: String? = null
        private set

    @Volatile
    var lastScanResult: String = "loading"
        private set

    @Volatile
    var lastRecentsSource: String = "none"
        private set

    @Volatile
    var lastHit: String? = null
        private set

    @Volatile
    var lastLaunch: String? = null
        private set

    /** 最近一次启动走通第几级（1/2/3），0 = 全失败。 */
    @Volatile
    var lastLevel: Int = -1
        private set

    /** 扇形成功弹出的次数。由主线程写。 */
    @Volatile
    var openedCount: Long = 0
        private set

    /** 想弹但没弹出来的次数（窗口建不出来等）。 */
    @Volatile
    var missCount: Long = 0
        private set

    /** 主线程回调里的统一兜底：异常绝不逃逸（抛出去就是 SystemUI 崩）。 */
    private inline fun guarded(ctx: HookContext, what: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            runCatching { overlay?.dismiss() }
            ctx.log(Log.ERROR, "[$TAG] $what 异常，已收起扇形", t)
        }
    }

    private const val USAGE_RECENT_LIMIT = 12

    /**
     * 应用清单扫描失败后的退避基数（ms）。第 N 次重试等待 `基数 << (N-1)`：
     * 2s / 4s / 8s / 16s / 32s —— 见 [startScan] 记的那次真实重启。
     */
    private const val SCAN_RETRY_BASE_MS = 2000L

    /** 扫描最多几次（含首次）。6 次 ≈ 两分钟，够覆盖「开机 → 用户首次解锁」。 */
    private const val SCAN_RETRY_MAX = 6
}

/**
 * 输入分发线程 → 主线程的请求桥。
 *
 * ## 为什么必须有
 *
 * [CornerFanDetector.onTouch] 跑在**输入分发线程**上，而 `WindowManager.addView` /
 * `removeViewImmediate` / `View.invalidate` 只能在主线程。从输入线程直接碰 UI 会把整棵
 * View 树挂到那个线程上，之后主线程的任何交互都会出问题 —— 而且这类错往往延迟暴露。
 *
 * 所以输入线程只做两件事：写 `@Volatile` 字段、保证**至多 post 一个**可复用的 Runnable；
 * 主线程读最新的字段值。手指快速划过扇形时一秒能产生上百个 MOVE，每个都 post
 * 就会在主线程队列里堆几十个「重画」任务，全是白做的（侧滑索引那边同样处理）。
 *
 * 去重的安全性来自**单写者纪律**：`showScheduled` 只有输入线程写 true、只有主线程写 false，
 * 两边都不做「读-改-写」，所以不会丢更新。最坏情况是某一帧的重画被跳过，下一帧自愈。
 *
 * 这里**零分配**：Runnable 复用同一批实例，字段全是基本类型。
 */
internal class FanGestureBridge(
    private val onShow: (Boolean, Float, Float) -> Unit,
    private val onPointer: (Float, Float) -> Unit,
    private val onCommit: (Float, Float, Boolean) -> Unit,
    private val onCancel: () -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var showPending = false

    @Volatile
    private var pointerPending = false

    @Volatile
    private var px = 0f

    @Volatile
    private var py = 0f

    @Volatile
    private var commitAllowHover = true

    private var openRight = false
    private var showScheduled = false
    private var pointerScheduled = false

    // ── 输入线程 ──────────────────────────────────────────────────────

    /** 过闸了：请主线程弹出扇形。 */
    fun show(openToRight: Boolean, x: Float, y: Float) {
        openRight = openToRight
        px = x
        py = y
        showPending = true
        if (!showScheduled) {
            showScheduled = true
            main.post(showTick)
        }
    }

    /** 手指位置变了：请主线程重新命中 + 重画。 */
    fun pointer(x: Float, y: Float) {
        px = x
        py = y
        pointerPending = true
        if (!pointerScheduled) {
            pointerScheduled = true
            main.post(pointerTick)
        }
    }

    /** 抬手：请主线程做命中测试并启动。[allowHover] 见 [FanOverlay.commit]。 */
    fun commit(x: Float, y: Float, allowHover: Boolean) {
        px = x
        py = y
        commitAllowHover = allowHover
        main.post(commitTick)
    }

    /** 取消（多指、系统抢走、异常）。 */
    fun cancel() {
        main.post(cancelTick)
    }

    /**
     * 卸载时清队列。可从任何线程调。`removeCallbacks` 只能摘掉还没跑的；
     * 已经在跑的那个之后会被浮层自己的 released 闸门挡掉。
     */
    fun stop() {
        showScheduled = false
        pointerScheduled = false
        showPending = false
        pointerPending = false
        main.removeCallbacks(showTick)
        main.removeCallbacks(pointerTick)
        main.removeCallbacks(commitTick)
        main.removeCallbacks(cancelTick)
    }

    // ── 主线程 ────────────────────────────────────────────────────────

    private val showTick = Runnable {
        showScheduled = false
        if (!showPending) return@Runnable
        showPending = false
        onShow(openRight, px, py)
    }

    private val pointerTick = Runnable {
        pointerScheduled = false
        if (!pointerPending) return@Runnable
        pointerPending = false
        onPointer(px, py)
    }

    private val commitTick = Runnable { onCommit(px, py, commitAllowHover) }

    private val cancelTick = Runnable { onCancel() }
}

/**
 * 防误触用的**严格判据**常量。全部照 Flyme 的严格单轴策略取，来源写在每个常量上。
 *
 * 为什么要一个单独的 object：这些数是「比参考实现更严」的那一层，
 * 和 [FanSpec] 里那些「逐字对应上游」的几何参数性质不同 ——
 * 混在一起会让后人分不清哪个数改了就偏离了参考手感。
 */
internal object FanStrict {

    /**
     * 方向判定的最短距离倍数 = `mTouchSlop * 4.0f`。
     *
     * 出处：`com/android/launcher3/touch/SingleAxisSwipeDetector.java:114`
     * （`shouldScrollStart` 里 `mStrictStrategy` 分支的第一条），
     * 由 `SlideAppsTouchController.java:34` 的 `setStrictStrategy(true)` 打开。
     * Flyme 用它把「起步距离」从 1 个 slop 抬到 4 个 slop —— 我们把这同一条
     * 用在**方向判定**上（我们不需要起步距离，我们本来就不消费）。
     */
    const val FIND_SLOP_MULTIPLIER = 4f
}

/**
 * 底角斜滑的判定状态机。**跑在输入分发线程上**：判定部分无 IO、无日志、无 IPC、无分配。
 *
 * 五态：
 *
 * ```
 * IDLE / DEAD ──DOWN 落在触发区──▶ ARMED ──位移≥方向判定距离且方向在锥内──▶ DECIDED
 *                                  │                                        │
 *                                  │◀── 方向不对 / 多指 / 守卫不满足 ──▶ DEAD │
 *                                  └────────位移≥距离闸─────────────────▶ FAN
 * ```
 *
 * `FAN` 之前的每一态都**不消费**事件（`return false`），`FAN` 之后全消费。
 * 这就是「触发区里的轻点放行」在本通道下的实现方式，见类注释里的说明。
 *
 * ## 防误触：两层严格化（都照 Flyme 的语义，不照抄它的类）
 *
 * 1. **方向判定推迟到 4×slop 之后**（`FanRuntime.strictFindPx2`，出处见 [FanStrict]）。
 *    原来在 32px 处判方向，那是噪声；判错了会被一直记住，于是「先抖再笔直上划（回桌面）」
 *    也会走到距离闸被消费。
 * 2. **消费前再确认一次方向锥**：见 [onMove] 里 `d2 >= triggerPx2` 那一帧。
 *    Flyme 只在起步判一次就不再复判（`BaseSwipeDetector.java:145` 只在
 *    `mState != DRAGGING` 时调 `shouldScrollStart`），因为它是**真 owner**，误判的代价
 *    被它自己的窗口层兜住；我们是旁观+消费通道，误判会直接打断正在进行的系统手势，
 *    所以这里比 Flyme 多判一次 —— **这是有意的偏离**，写在下面。
 */
internal class CornerFanDetector(
    private val runtime: CornerSmallWindow.FanRuntime,
    private val overlay: FanOverlay,
    private val guards: FanGuards,
    private val bridge: FanGestureBridge,
    private val onError: (Throwable) -> Unit,
) : SwipeEventHub.Listener {

    private companion object {
        const val IDLE = 0
        const val ARMED = 1
        const val DECIDED = 2
        const val FAN = 3
        const val DEAD = 4

        /**
         * 起手诊断的**总预算**（每个 detector 实例 = 每次安装一份）。见 [noteCornerMiss]。
         *
         * 定成常量而不是滑杆：它是诊断用的，不是手感参数 —— 手感参数都在 `Catalog` 里。
         *
         * ⚠️ 24 → 200（2026-09-15）：24 条被前几次尝试用光之后，后面的手势**全是静默的**，
         * 于是"我试了一些，但日志里什么都没有"——正是那一次把结论拖后了一轮。
         * 有「只在角附近记」这条过滤在，200 条不会把日志刷爆（每次安装最多 200 行）。
         */
        const val CORNER_DIAG_MAX = 200

        /**
         * 起手后的**判定超时**（ms）—— 照 Flyme 的 `GESTURE_TRIGGER_TIME_OUT = 300`
         * （`GestureListenerBase.java:38`）。见 ARMED 分支里的说明。
         */
        const val ARM_TIMEOUT_MS = 300L

        /**
         * **守卫诊断**自己的预算（见 [noteGuardReject]）。
         *
         * 为什么不与起手诊断共用那 24 条：守卫 1 里的「输入法可见」会在**每一次**底部触摸上
         * 触发，打字就能把那 24 条烧光、把真正要看的手势轨迹挤掉。所以守卫这一路用小预算 +
         * 「在角附近才记」这条过滤。
         */
        const val GUARD_DIAG_MAX = 8
    }

    /** 起手诊断已用掉的条数（新 detector = 新预算，不需要额外重置）。 */
    private var cornerDiagCount = 0

    /** 守卫诊断已用掉的条数（独立预算，见 [GUARD_DIAG_MAX]）。 */
    private var guardDiagCount = 0

    private var phase = IDLE
    private var openToRight = false
    private var downX = 0f
    private var downY = 0f

    /**
     * 本次手势是不是**悬停续选**（上一次抬手未选中、菜单悬停着，这次 DOWN 被接住当选图手势）。
     *
     * 只影响抬手时的走向：续选手势**落空 = 收窗**（悬停只给一次再选机会，不无限循环），
     * 首次手势落空 = 进入悬停。见 [onDownDuringHover] 与 [FanOverlay.commit]。
     */
    private var fromHover = false

    /**
     * 本次手势 DOWN 的**事件时刻**（`MotionEvent.eventTime`，见 [ARM_TIMEOUT_MS]）。
     *
     * 用事件自带的时间戳而不是 `SystemClock.uptimeMillis()`：同一串事件的时间基准一致，
     * 不受跨线程/调度抖动影响。
     */
    private var downEventTimeMs = 0L

    /**
     * 本次手势的按下时刻（`uptimeMillis`）。
     *
     * 用来判「系统那条回桌面是不是**在这次手势里**发起的」：只有发起时刻 ≥ 按下时刻才算，
     * 上一次手势造成的回桌面不该影响这一次（先回桌面、再从容划扇子是合法用法）。
     */
    private var downAtMs = 0L

    override fun onTouch(event: MotionEvent): Boolean = try {
        handle(event)
    } catch (t: Throwable) {
        // 输入线程上绝不抛出去。这里是**异常路径**上的让步：规范禁的是高频路径上的日志，
        // 而这条路径应该永远不出现，真出现就必须留痕迹，否则问题是不可见的。
        phase = IDLE
        onError(t)
        false
    }

    /** 卸载时复位。可从任何线程调。 */
    fun reset() {
        phase = IDLE
    }

    private fun handle(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onDown(event)
            MotionEvent.ACTION_MOVE -> return onMove(event)
            MotionEvent.ACTION_UP -> return endGesture(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> return endGesture(Float.NaN, Float.NaN)
            // 多指直接终止（Flyme 用 `action == 5 || action == 6`）
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return onExtraPointer()
        }
        return false
    }

    /**
     * 按下。
     *
     * 守卫的顺序是「最便宜的先判」，全部是内存读（息屏/锁屏/IME 由 [FanGuards] 的后台轮询
     * 提前取好，这里不做 IPC —— 输入线程上任何 IPC 都是红线）。
     */
    private fun onDown(event: MotionEvent): Boolean {
        phase = IDLE
        fromHover = false
        // 上一次手势可能没走到 endGesture（比如事件流被系统掐断）——新的一次 DOWN 来了，
        // 说明上一次已经结束。这里兜底清掉，否则「拦回桌面」会一直是开的（回桌面就坏了）。
        OverlayArbiter.fanOwnsGesture = false

        // 守卫 1：息屏 / 锁屏 / 输入法可见
        if (!guards.ok) {
            noteGuardReject(
                "守卫1（" + (if (!guards.interactive) "息屏" else "") +
                    (if (guards.locked) "锁屏" else "") +
                    (if (guards.imeVisible) "输入法可见" else "") + "）",
                event.x, event.y, runtime.spec,
            )
            return false
        }
        // 守卫 2：扇形已经展开了。
        // 正常路径上它是被本次手势的 UP/CANCEL 收掉的，还留着说明上一次手势没有正常结束
        // （比如另一根手指按了 home —— Flyme 用私有的 MEIZU_FLAG_INTERCEPT_HOME_KEY 拦 home，
        // 我们只能在下一次触摸时把它收掉）。所以这里**先收窗再放行这次按下**。
        //
        // ⚠️ 例外：**悬停**（2026-09-20 拍板）。抬手未选中时菜单故意保留着 ——
        // 这次 DOWN 要当成「续选手势」接住，而不是把菜单收掉。见 [onDownDuringHover]。
        if (overlay.visible) {
            if (overlay.hovering) {
                return onDownDuringHover(event)
            }
            bridge.cancel()
            noteGuardReject("守卫2（上一次没收窗）", event.x, event.y, runtime.spec)
            return false
        }
        // 守卫 2.5：侧滑索引正在接管这次手势 → 不武装。
        // Flyme 那边靠「顶层有浮窗就不接管」这条守卫（它的扇形在另一个进程，管不到我们这层），
        // 我们两个浮层都在 SystemUI 里、读同一路输入，所以要显式让位（见 OverlayArbiter）。
        if (OverlayArbiter.indexShowing) {
            noteGuardReject("守卫2.5（侧滑索引正在接管）", event.x, event.y, runtime.spec)
            return false
        }
        // 守卫 3：几何与清单就绪（不足 3 项时 spec 会被清掉，见 publish）
        val spec = runtime.spec
        if (spec == null) {
            noteGuardReject("守卫3（几何还没就绪）", event.x, event.y, null)
            return false
        }
        if (runtime.poolSize < FanTargets.MIN_TARGETS) {
            noteGuardReject("守卫3（清单不足 " + runtime.poolSize + " 项）", event.x, event.y, spec)
            return false
        }

        // 守卫 4：当前方向启用了没有。
        // HyperGesture 是 `bottomEnabledFor(landscape) = landscape ? bottomLandscapeEnabled
        // : bottomPortraitEnabled`（`GestureConfig.java:455-457`），`isLandscape()` 比的是
        // display bounds 的长宽（`FanRuntime.java:2919-2923`）。
        // 放在这里而不是 publish 里算：按下每次都发生，这样「转屏后立刻生效」，
        // 且判定是两次布尔读 + 一次比较。
        // ⚠️ 前提是 spec 的屏幕尺寸是**当下**的 —— 由 `watchRotation` 保证（转屏重算几何）。
        // ⚠️ 判据只此一处（[FanSpec.isLandscape]）：守卫 5 与摆放都调同一个函数。
        val landscape = spec.isLandscape()
        if (if (landscape) !spec.landscapeEnabled else !spec.portraitEnabled) {
            noteGuardReject("守卫4（当前方向未启用）", event.x, event.y, spec)
            return false
        }

        // 起手时的兜底核对（拉）：Resources 的 configuration 与 spec 不一致 =
        // DisplayListener 那条（推）被吞了 / 还没到。本手势**继续**用 spec ——
        // 失败方向与旧行为一致（几何过期的手势本来就会因为热区对不上而落空），
        // 重算安排到主线程，下一个手势就是对的。
        val live = CornerSmallWindow.liveOrientationIsLandscape()
        if (live != null && live != landscape) {
            CornerSmallWindow.requestGeometryRefresh(
                "起手核对：spec 判为" + (if (landscape) "横" else "竖") +
                    "屏，Resources 报" + (if (live) "横" else "竖") + "屏",
            )
        }

        // 守卫 5：当前前台应用在这个朝向下被拉黑了吗。
        //
        // 两份名单各管一个朝向（用户 2026-09-20 拍板），选哪一份用**与守卫 4 同一个**
        // `landscape` —— 另判一次朝向就是两处口径，漂移起来是静默的。
        //
        // ⚠️ 三条纪律：
        //  - 前台包名来自 `ForegroundTracker`（后台 1s 轮询 + `@Volatile` 缓存），
        //    这里**不做任何 IPC** —— 输入线程上 IPC 是红线；
        //  - 空名单直接跳过（`isEmpty` 短路），普通用户根本不会走到 `contains`；
        //  - 拿不到前台包名 → **放行**（不屏蔽）。失败方向必须是「与没装模块一致」，
        //    而不是「宁可错杀」—— 屏蔽错了的表现是「这个功能坏了」，用户不会来查名单。
        val blocked = if (landscape) runtime.blockedLandscape else runtime.blockedPortrait
        if (blocked.isNotEmpty()) {
            val pkg = SystemUiEnv.ForegroundTracker.foreground()
            if (pkg != null && blocked.contains(pkg)) {
                noteGuardReject(
                    "守卫5（前台 " + pkg + " 在" + (if (landscape) "横" else "竖") + "屏黑名单里）",
                    event.x, event.y, spec,
                )
                return false
            }
        }

        // 贴哪一角：**由触发区的矩形决定，左角优先**（`GestureGeometry.cornerAt:76-87`，
        // 本 App 调它时 inset 传 0）。上一版用的是 Flyme 的「屏幕中点分左右」，那不是同一套
        // 判据：`hot_width_percent` 调到 > 50% 时两角热区重叠，两套规则会给出不同的角，
        // 于是「朝内」的方向和扇子贴的角互相矛盾（表现为在热区里怎么划都不出来）。
        val corner = FanTrigger.cornerAt(event.x, event.y, spec)
        if (corner == null) {
            noteCornerMiss(event.x, event.y, spec)
            return false
        }
        noteCornerArmed(event.x, event.y, spec)

        phase = ARMED
        openToRight = corner == FanCorner.RIGHT
        downX = event.x
        downY = event.y
        downAtMs = android.os.SystemClock.uptimeMillis()
        downEventTimeMs = event.eventTime
        fromHover = false
        // **不消费 DOWN**：这时还不知道用户要干什么。触发区是屏幕底部那条带，
        // 里面全是正常点击，吃掉 DOWN 就是「底部两角的点击随机失效」。
        return false
    }

    /**
     * **悬停续选**：菜单悬停着，这次 DOWN 落在了图标上 → 把整条手势接住当选图手势。
     * （2026-09-20 拍板「悬停 + 可再选 + 超时」。）
     *
     * 三种走向：
     *  - DOWN 落在**图标圆**里 → 整条手势归我们（直接进 `FAN`，不再过锥/距离闸——
     *    扇形已经在屏上了，用户就是在点它）；MOVE 跟着高亮，UP 命中 → 启动。
     *  - DOWN 落在**扇形外** → 收掉悬停，这个 DOWN **原样放行** ——
     *    点别处的正常操作（开应用、打字）不受悬停影响。这比 Flyme 好：
     *    它悬停期间菜单点不动、新手势也被拒。
     *  - 守卫已经不满足（息屏/锁屏/IME）或几何失效 → 收掉悬停，放行。
     *
     * 为什么在输入线程上直接调 `hitTest`：纯内存数学（与 [FanTrigger.cornerAt] 同级），
     * 无 IPC、无分配。
     */
    private fun onDownDuringHover(event: MotionEvent): Boolean {
        val spec = runtime.spec
        if (spec == null || !guards.ok) {
            bridge.cancel()
            return false
        }
        if (spec.hitTest(event.x, event.y, openToRight) < 0) {
            bridge.cancel()
            return false
        }
        phase = FAN
        downX = event.x
        downY = event.y
        downAtMs = android.os.SystemClock.uptimeMillis()
        downEventTimeMs = event.eventTime
        fromHover = true
        // 先摘悬停超时：别让它在手势做到一半时把扇子收走
        overlay.endHover()
        // 这次手势从头到尾都归我们（连 DOWN 一起消费），回桌面转移也不许发起
        OverlayArbiter.fanOwnsGesture = true
        return true
    }

    /**
     * 起手点**为什么没被当成扇形起手** —— 有预算的逐次诊断（2026-09-15 加）。
     *
     * 为什么需要：按下判据的四个分支以前全是**静默返回**，于是「很难触发 / 容易回桌面」
     * 这类反馈只能靠感觉争，拿不到「手指落在哪、被哪一条挡住」这种能直接定参数的事实。
     * 只在**角上、且离底不远**的按下记录，避免底栏/键盘附近的正常点击刷屏；
     * 总预算见 [CORNER_DIAG_MAX]，只在 [install] 时归零。
     */
    private fun noteCornerMiss(x: Float, y: Float, spec: FanSpec) {
        if (cornerDiagCount >= CORNER_DIAG_MAX) return
        val fromBottom = spec.screenH - y
        val inCornerX = x <= spec.hotWidth || x >= spec.screenW - spec.hotWidth
        // 角**外侧**也要记：用户 2026-09-15 报的「偏内一些就没反应」正是这一格
        // （x 超出了角宽 → 我们根本不武装 → 底部上滑整个归系统）。
        // 只记「离边不远」的，免得底栏中间的点击刷屏。
        val nearEdge = x <= spec.hotWidth * 2f || x >= spec.screenW - spec.hotWidth * 2f
        if (!nearEdge || fromBottom > spec.hotHeight + 60f * spec.density) return
        cornerDiagCount++
        val why = when {
            fromBottom > spec.hotHeight -> "在热区上方"
            !inCornerX -> "在角外侧"
            else -> "该角未启用"
        }
        Log.i(
            CornerSmallWindow.TAG,
            "起手未成立：距底=" + dpText(fromBottom, spec.density) + " 原因=" + why +
                " 热区高=" + dpText(spec.hotHeight, spec.density) +
                " 角宽=" + dpText(spec.hotWidth, spec.density) +
                " xy=(" + x.toInt() + "," + y.toInt() + ")",
        )
    }

    /**
     * 起手被**守卫**挡下的诊断（独立小预算，见 [GUARD_DIAG_MAX]）。
     *
     * 守卫链（1=息屏/锁屏/输入法、2=上一次没收窗、2.5=索引接管、3=几何/清单、4=方向开关）
     * 每一步以前都是**静默返回**，于是「我明明是照着正确姿势划的，怎么没反应」这类反馈
     * 完全查不下去 —— 有这条就能直接看是哪一关挡的。
     */
    private fun noteGuardReject(why: String, x: Float, y: Float, spec: FanSpec?) {
        if (guardDiagCount >= GUARD_DIAG_MAX) return
        if (spec != null) {
            val nearEdge = x <= spec.hotWidth * 2f || x >= spec.screenW - spec.hotWidth * 2f
            val fromBottom = spec.screenH - y
            if (!nearEdge || fromBottom > spec.hotHeight + 60f * spec.density) return
        }
        guardDiagCount++
        Log.i(CornerSmallWindow.TAG, "起手被守卫挡：" + why + " xy=(" + x.toInt() + "," + y.toInt() + ")")
    }

    /** 起手成立（有预算）：用来区分「根本没起手」和「起手了但方向锥没过」。 */
    private fun noteCornerArmed(x: Float, y: Float, spec: FanSpec) {
        if (cornerDiagCount >= CORNER_DIAG_MAX) return
        cornerDiagCount++
        Log.i(
            CornerSmallWindow.TAG,
            "起手成立：角=" + (if (x <= spec.hotWidth) "左" else "右") +
                " 距底=" + dpText(spec.screenH - y, spec.density),
        )
    }

    /**
     * **扇子起来之后**的结局（2026-09-15 加）。
     *
     * 为什么补这一段：用户报「有时候**拉起小窗时**会响应返回桌面」——那是**已经接管之后**才回桌面
     * （扇子画出来了、人却回了桌面）。前面的诊断只覆盖"怎么起来的"，起来之后发生了什么一个都没记，
     * 于是这个现象在日志里完全看不见。
     *
     * 判据用 [SystemUiEnv.ForegroundTracker]：它由后台线程每秒轮询、这里读的是**缓存**
     * （`foreground()` 与 `isLauncherForeground()` 都是 `@Volatile` 读）→ 输入线程上无 IPC。
     * 提交/取消之后前台**变成了桌面** = 系统那条回桌面手势终究还是赢了。
     * 延迟 1.2s 再读一次：跟踪器 1s 轮询，立刻读很可能还是旧值。
     */
    private fun noteFanEnd(committed: Boolean, d2: Float, density: Float) {
        if (cornerDiagCount >= CORNER_DIAG_MAX) return
        cornerDiagCount++
        val what = if (committed) "扇子提交" else "扇子取消"
        Log.i(
            CornerSmallWindow.TAG,
            what + "：位移=" + dpText(Math.sqrt(d2.toDouble()).toFloat(), density) +
                " 前台=" + (SystemUiEnv.ForegroundTracker.foreground() ?: "?"),
        )
        CornerSmallWindow.mainHandler.postDelayed({
            if (SystemUiEnv.ForegroundTracker.isLauncherForeground()) {
                Log.i(
                    CornerSmallWindow.TAG,
                    "⚠️ " + what + "之后前台变成了桌面 —— 系统那条回桌面手势赢了（" +
                        "扇子出现距离=" + dpText(triggerPx(), density) + "）",
                )
            }
        }, 1200L)
    }

    /** 当前距离闸（px）。只给上面那条诊断用。 */
    private fun triggerPx(): Float = runtime.spec?.triggerPx ?: 0f

    /** 诊断用的 dp 文本（1 位小数）。只在上面两条诊断路径上调用，不在每帧路径上。 */
    private fun dpText(px: Float, density: Float): String =
        String.format(java.util.Locale.US, "%.1fdp(%dpx)", px / density, px.toInt())

    /**
     * **方向锥那一关**的逐手势轨迹（有预算，与起手诊断共用 [CORNER_DIAG_MAX]）。
     *
     * 为什么需要：2026-09-15 用户报「偏内一些还是容易回桌面」「还会触发全面屏切换应用」，
     * 而这两条都要先回答「锥到底判过了没有、仰角是多少、有没有抢到指针」——
     * 以前这些**一个都没记**（只有起手那一关有痕迹），于是只能靠猜。
     * 一条手势最多 2～3 行；预算用完即静默（不影响判定）。
     */
    private fun noteDecision(
        stage: String,
        inward: Float,
        upward: Float,
        d2: Float,
        density: Float,
        extra: String = "",
    ) {
        if (cornerDiagCount >= CORNER_DIAG_MAX) return
        cornerDiagCount++
        val dist = Math.sqrt(d2.toDouble()).toFloat()
        val ang = Math.toDegrees(Math.atan2(upward.toDouble(), inward.toDouble())).toFloat()
        Log.i(
            CornerSmallWindow.TAG,
            stage + "：仰角=" + String.format(java.util.Locale.US, "%.1f", ang) + "°" +
                " 位移=" + dpText(dist, density) +
                " inward=" + inward.toInt() + " upward=" + upward.toInt() + extra,
        )
    }

    private fun onMove(event: MotionEvent): Boolean = when (phase) {
        FAN -> {
            if (event.pointerCount != 1) {
                phase = DEAD
                bridge.cancel()
                return true
            }
            bridge.pointer(event.x, event.y)
            true
        }

        ARMED, DECIDED -> {
            if (event.pointerCount != 1) {
                phase = DEAD
                return false
            }
            val spec = runtime.spec
            if (spec == null) {
                phase = DEAD
                return false
            }
            // 朝屏内的水平分量：贴左是 x 增大、贴右是 x 减小
            val inward = if (openToRight) downX - event.x else event.x - downX
            val upward = downY - event.y
            val d2 = inward * inward + upward * upward

            if (phase == ARMED) {
                // 这一次手势中途索引接管了 → 放弃本次（不消费，交给索引）
                if (OverlayArbiter.indexShowing) {
                    phase = DEAD
                    return false
                }
                // **300ms 超时**（照 Flyme 补上，2026-09-15 第三轮）。
                //
                // Flyme 是 `GESTURE_TRIGGER_TIME_OUT = 300` 配 `PENDING_CHECK` 状态
                // （`WindowModeGestureListener.java:120-122`）：按下之后 300ms 内没走出判定距离
                // 就 CANCELED。它专治「按住、想一想、再上滑」——那种手势的"起手方向"已经没有意义了。
                // 放弃 = 一个事件都不消费，与 Flyme 的 CANCELED 同义。
                if (event.eventTime - downEventTimeMs > ARM_TIMEOUT_MS) {
                    noteDecision(
                        "超时(按住超过 " + ARM_TIMEOUT_MS + "ms 才动) → 放弃、不消费",
                        inward, upward, d2, spec.density,
                    )
                    phase = DEAD
                    return false
                }
                // 位移还不够就已经能看出方向不对 → 直接放弃这次手势，
                // 但**不消费**：用户可能只是在正常地划动/滚动。
                //
                // **纯竖直上滑（回桌面）永远走不到接管那一步** —— 它的朝内分量只有抖动，
                // 于是全程停在 ARMED、一个事件都不消费。这就是
                // 「上滑回桌面绝不被我们吃掉」的实现位置。
                // **早判**（2026-09-15 第二次改）：从「走了 4×slop（≈32dp）再看总位移的角度」
                // 改成「**朝内分量够几 dp 就判**」。
                //
                // 为什么要早：拇指的扇形轨迹是「起手斜、随后拐直」，到 32dp 时总位移那条弦
                // **已经接近竖直** → 被判成"回桌面"、不接管 → 系统把人送回家（用户第三次反馈）。
                // Flyme 在 **2dp** 就判方向，抓的就是"起手那一瞬朝内"。
                //
                // 判别式是**「朝内走了几 dp」**而不是角度：回桌面 = 手指往上、朝内只有抖动
                // （0～2dp）；扇形 = 一定会真实往屏内走。
                // 门槛 = **总位移 2dp**（Flyme 的量法，见 [FanTrigger.TAKEOVER_SLOP_DP]）
                //      + 朝内分量过噪声地板 [FanTrigger.TAKEOVER_MIN_INWARD_DP]：
                //   · 没到门槛 → 停在 ARMED、**一个事件都不消费**（纯竖直走这条 → 回桌面完好）；
                //   · 够了但角度在锥外（太陡/太平）→ DEAD、不消费；
                //   · 够了且角度在锥内 → 接管。
                // ⚠️ 这里原本写成"朝内 ≥ 2dp"，那是**错的抄法**：起手越接近竖直、朝内攒得越慢，
                // 接管就越晚（实测有一次到 45dp 才接管 → 系统先到 → 还是回桌面）。
                // Flyme 量的是**总位移**（`squaredHypot > 2dp²`，`WindowModeGestureListener.java:123`）。
                if (d2 < spec.takeoverSlopPx * spec.takeoverSlopPx || inward < spec.takeoverMinInwardPx) {
                    // 还没真正往屏内走。若位移已经很大而方向明显不对，早点放弃，
                    // 免得用户斜滑到一半再拐弯时反复判。
                    if (d2 >= runtime.strictFindPx2 && !FanTrigger.directionOk(inward, upward)) {
                        noteDecision("方向锥未过(未接管，这一条手势归系统)", inward, upward, d2, spec.density)
                        phase = DEAD
                    }
                    return false
                }
                if (!FanTrigger.directionOk(inward, upward)) {
                    // 方向反了（往屏幕外、往下、或纯水平/纯垂直）。彻底放弃本次，
                    // 免得用户斜滑到一半又拐弯时反复触发。
                    noteDecision("方向锥未过(未接管，这一条手势归系统)", inward, upward, d2, spec.density)
                    phase = DEAD
                    return false
                }
                // **方向锥确认 = 这次触摸归我们**（2026-09-15 加）。
                //
                // 为什么在这里、而不是等到下面的距离闸（[FanSpec.triggerPx]，
                // 默认短边的 12%，本机 147px）：距离闸只是「扇子什么时候画出来」，
                // 而系统的同向手势（任务中心 / 回桌面）在这之间早就展开了。
                // 抢指针会让那条手势收到 `ACTION_CANCEL` —— 系统自己那条 handler 也是这么干的
                // （`MulWinSwitchEventHandler.java:412`），我们此前只 `return true`，
                // 那不会打断窗口，于是「扇子和任务中心同时出现」。
                //
                // 代价（有意接受的）：若用户的手势先斜后直（拇指从底角划到一半改成竖直回桌面），
                // 这次回桌面会被取消、扇子也起不来 —— 要重划一次。宁可偶尔重划，
                // 也不要「系统手势被我们打断了却什么都没发生」之外的第三种形态（两个同时出现）。
                // 抢之前先让位：**系统在这次手势里已经发起回桌面** → 这次不接管。
                // 回桌面发起后没法撤销，所以"谁先到归谁"——让用户拿到干净的桌面，
                // 而不是"桌面 + 扇子"两个结果叠在一起（用户 2026-09-15 提的场景）。
                if (OverlayArbiter.homeStartedAtMs >= downAtMs && downAtMs != 0L) {
                    noteDecision("系统已先回桌面 → 本次不出扇（谁先到归谁）", inward, upward, d2, spec.density)
                    phase = DEAD
                    return false
                }
                SwipeEventHub.pilfer()
                noteDecision("方向锥确认→已抢指针", inward, upward, d2, spec.density)
                // 这次触摸归我们 → 从现在起，系统那条「上滑回桌面」转移不许发起
                // （见 CornerSmallWindow.installHomeBlock 与 OverlayArbiter.fanOwnsGesture）。
                OverlayArbiter.fanOwnsGesture = true
                phase = DECIDED
            }

            // 出扇前再让一次位：抢指针与出扇之间还隔着 `triggerPx`（本机 109px），
            // 系统那条回桌面完全可能在这段里发起（它比我们只数总距离要快）。
            if (d2 >= spec.triggerPx2 && OverlayArbiter.homeStartedAtMs >= downAtMs && downAtMs != 0L) {
                noteDecision("系统在出扇前已回桌面 → 不出扇（谁先到归谁）", inward, upward, d2, spec.density)
                phase = DEAD
                return false
            }
            if (d2 >= spec.triggerPx2) {
                // **这里原来有一次「消费前的方向复判」，2026-09-15 第三轮删掉了。**
                //
                // 它当初的用途：判「DOWN 到当前点的总位移」是否还在锥内，用来把
                // "起手斜、中途拐直（其实想回桌面）"的用户放回去。现在删掉，两个理由：
                // ① **Flyme 判一次就定**（`checkWindowModeGesture` 只在 PENDING_CHECK 时判，
                //    之后 TRIGGERED/CANCELED 不再改口）—— 那次复判是我们为了补偿"没有否决权"
                //    自己加的；
                // ② 它的代价正好压在**真扇形**上：弧线起手、后来变直的扇形也会被拒
                //    （用户抱怨的"很难触发"里可能就有它一份）。而现在我们已经有了
                //    `installHomeBlock` 拦回桌面 —— "吃掉系统手势"这个顾虑由它兜住了。
                noteDecision("扇形展开", inward, upward, d2, spec.density)
                phase = FAN
                bridge.show(openToRight, event.x, event.y)
                // 从这一帧起事件归我们
                return true
            }
            return false
        }

        else -> false
    }

    /**
     * 抬手 / 取消。
     *
     * @param x,y 抬手位置；取消时传 NaN（只用来区分两种结束）
     */
    private fun endGesture(x: Float, y: Float): Boolean {
        val wasFan = phase == FAN
        val endedAs = phase
        phase = IDLE
        OverlayArbiter.fanOwnsGesture = false
        if (!wasFan) {
            // 抬手时还没出扇：**快划**（还没走到 4×slop 就抬手）是「很容易回桌面」最可能的形态，
            // 必须能和「起手都没成立」区分开 —— 所以这里把抬手时的 phase 也记一笔。
            if (endedAs == ARMED || endedAs == DECIDED) {
                val inw = if (x.isNaN()) 0f else if (openToRight) downX - x else x - downX
                val upw = if (y.isNaN()) 0f else downY - y
                noteDecision(
                    if (endedAs == ARMED) "抬手时还没判方向(未接管)" else "抬手时已抢指针但未出扇",
                    inw, upw, inw * inw + upw * upw, runtime.spec?.density ?: 1f,
                )
            }
            // 没形成手势：**一个事件都不消费**。轻点、长按、往上直着划（系统主屏手势）
            // 在这一路都是原样归系统和应用的 —— 即「轻点放行」。
            return false
        }
        if (x.isNaN()) bridge.cancel() else bridge.commit(x, y, allowHover = !fromHover)
        fromHover = false
        // 扇子**起来之后**的结局也要记：用户反馈的「有时候拉起小窗时会响应返回桌面」
        // 正是这一段 —— 接管成功、扇子画出来了，人却回了桌面。此前这一段一个字段都没记。
        val inw = if (x.isNaN()) 0f else if (openToRight) downX - x else x - downX
        val upw = if (y.isNaN()) 0f else downY - y
        noteFanEnd(!x.isNaN(), inw * inw + upw * upw, runtime.spec?.density ?: 1f)
        return true
    }

    private fun onExtraPointer(): Boolean {
        val wasFan = phase == FAN
        phase = DEAD
        OverlayArbiter.fanOwnsGesture = false
        if (wasFan) {
            bridge.cancel()
            return true
        }
        return false
    }
}

/**
 * 手势守卫：息屏 / 锁屏 / 输入法可见。
 *
 * ## 为什么不能在判定时查
 *
 * 这几样都是跨进程查询：`PowerManager.isInteractive()`、`KeyguardManager.isKeyguardLocked()`。
 * 而判定的入口在**输入分发线程**上，那里做 IPC 是明确红线（规范 §7.5）。所以提前在后台线程取好，
 * 判定时只读两个 `@Volatile` 布尔 —— 零 IPC、零分配。
 *
 * ## 为什么改成事件驱动
 *
 * 原来每 250ms 轮询一次 = 特性开着就每秒 8 次 Binder 调用，息屏时也照跑。
 * 两个状态只在亮屏 / 息屏 / 解锁时变：监听这三条广播，事件到了再查一次。
 * 另留一个**亮屏时**每 [FALLBACK_MS] 一次的兜底刷新：「延时锁屏」这类锁上了却没有广播的情况，
 * 最多晚几秒被纠正；息屏时完全不查。
 */
internal class FanGuards(private val context: Context) {

    @Volatile
    var interactive = true
        private set

    @Volatile
    var locked = false
        private set

    /** 只用于日志；输入法可见**不再拒绝手势**，改为展开时收起输入法（见 [FanImeState]）。 */
    val imeVisible: Boolean get() = FanImeState.visible

    /** 输入线程读这个：读两个 volatile + 一次与运算，无 IPC、无分配、无锁。 */
    val ok: Boolean get() = interactive && !locked

    private var thread: android.os.HandlerThread? = null
    private var handler: Handler? = null
    private var receiver: android.content.BroadcastReceiver? = null

    private val power by lazy {
        runCatching { context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager }.getOrNull()
    }
    private val keyguard by lazy {
        runCatching { context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager }.getOrNull()
    }

    /** 查一次两个状态（后台线程）。失败就保持上一次的值：宁可多响应一次，也不要随机把功能关掉。 */
    private fun refresh() {
        try {
            interactive = power?.isInteractive() ?: true
            locked = keyguard?.isKeyguardLocked ?: false
        } catch (_: Throwable) {
        }
    }

    private val fallback = object : Runnable {
        override fun run() {
            refresh()
            // 息屏后停，等亮屏广播再起；广播没注册上时只能一直刷（否则息屏一次就再也醒不过来）
            if (interactive || receiver == null) handler?.postDelayed(this, FALLBACK_MS)
        }
    }

    fun start() {
        if (thread != null) return
        val t = android.os.HandlerThread("fp-fan-guards", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        interactive = false
                        h.removeCallbacks(fallback)
                    }
                    else -> {
                        // 亮屏 / 解锁：查一次，并（重新）开始亮屏期间的兜底刷新
                        h.removeCallbacks(fallback)
                        fallback.run()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        runCatching { context.registerReceiver(r, filter, null, h, Context.RECEIVER_EXPORTED) }
            .onSuccess { receiver = r }
        // 首次取值 + 启动兜底；广播注册失败时兜底刷新就是唯一来源，功能仍可用
        h.post(fallback)
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        handler?.removeCallbacks(fallback)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        /** 亮屏期间的兜底刷新间隔：原来 250ms 轮询的 1/20。 */
        const val FALLBACK_MS = 5_000L
    }
}

/**
 * 输入法可见状态 + 收起输入法。
 *
 * ## 为什么不再查 `getInputMethodWindowVisibleHeight()`
 *
 * 实测（2026-10-25 logcat）IMMS 对 SystemUI 的这次调用每 250ms 打一条
 * `Ignoring getInputMethodWindowVisibleHeight of uid 10225` —— 它只回答**当前焦点客户端**，
 * SystemUI 永远拿到 0，所以「输入法可见」这条守卫从来没生效过。
 *
 * 改为观察 SystemUI 自己的 `CommandQueue.setImeWindowStatus(displayId, vis, backDisposition, showSwitcher)`
 * （`research/decompiled/hyperos-systemui/.../statusbar/CommandQueue.java:1455`）——
 * 导航栏就是靠它知道输入法在不在的，`vis` 的 bit1 是 `InputMethodService.IME_VISIBLE`。
 * 只观察不修改（原样 `proceed`）。
 *
 * ## 行为（Flyme 对照）
 *
 * 输入法开着时**不拒绝**手势：扇形照常展开，同时收起输入法。收起用注入一次 BACK ——
 * 只在确知输入法可见时才注入，此时 BACK 先被输入法消费，不会把应用退回上一页。
 */
internal object FanImeState {

    private const val TAG = "FP-Fan"
    private const val IME_VISIBLE = 0x2

    @Volatile
    var visible = false
        private set

    /** 返回装上的 hook 数；0 = 这条信号没有，表现退化为「输入法开着也照常展开、不收起」。 */
    fun install(ctx: HookContext, track: (HookHandle) -> Unit): Int {
        visible = false
        val cls = try {
            Class.forName("com.android.systemui.statusbar.CommandQueue", false, ctx.classLoader)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "[$TAG] 找不到 CommandQueue，输入法可见信号未装")
            return 0
        }
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "setImeWindowStatus") continue
            val p = m.parameterTypes
            if (p.size != 4 || p[0] != Int::class.javaPrimitiveType || p[1] != Int::class.javaPrimitiveType) continue
            try {
                track(
                    ctx.module.hook(m).intercept { chain ->
                        try {
                            val display = chain.getArg(0) as Int
                            if (display == android.view.Display.DEFAULT_DISPLAY) {
                                visible = ((chain.getArg(1) as Int) and IME_VISIBLE) != 0
                            }
                        } catch (_: Throwable) {
                        }
                        chain.proceed()
                    },
                )
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "[$TAG] hook setImeWindowStatus 失败", t)
            }
        }
        if (n == 0) ctx.log(Log.WARN, "[$TAG] setImeWindowStatus 一个都没匹配到，输入法信号未装")
        return n
    }

    fun reset() {
        visible = false
    }

    /** 输入法可见时注入一次 BACK 收起它。主线程调。 */
    fun hideIfVisible() {
        if (!visible) return
        try {
            val now = android.os.SystemClock.uptimeMillis()
            val im = Class.forName("android.hardware.input.InputManagerGlobal")
                .getMethod("getInstance").invoke(null)
            val inject = im.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                val ev = android.view.KeyEvent(
                    now, now, action, android.view.KeyEvent.KEYCODE_BACK, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    android.view.KeyEvent.FLAG_FROM_SYSTEM or android.view.KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    android.view.InputDevice.SOURCE_KEYBOARD,
                )
                inject.invoke(im, ev, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
            }
            Log.i(TAG, "扇形展开：输入法可见，已注入 BACK 收起")
        } catch (t: Throwable) {
            Log.w(TAG, "收起输入法失败（扇形照常展开）", t)
        }
    }
}
