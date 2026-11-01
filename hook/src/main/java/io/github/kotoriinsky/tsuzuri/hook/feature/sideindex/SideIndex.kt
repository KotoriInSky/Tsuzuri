package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.kotoriinsky.tsuzuri.core.IndexReuse
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.DisplayNameOverrides
import io.github.kotoriinsky.tsuzuri.core.DrawerSignal
import io.github.kotoriinsky.tsuzuri.core.IconOverrideEntries
import io.github.kotoriinsky.tsuzuri.core.IndexAppFilter
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.LauncherScope
import io.github.kotoriinsky.tsuzuri.core.OrderedItems
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.RecentList
import io.github.kotoriinsky.tsuzuri.core.ShortcutEntries
import io.github.kotoriinsky.tsuzuri.core.geometry.SideIndexHotZone
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.drawersignal.LauncherDrawerTracker
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.OverlayArbiter
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.PreviewWallpaperPush
import io.github.kotoriinsky.tsuzuri.hook.feature.drawersignal.LauncherNativeOverlayTracker
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SwipeEventHub
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 侧滑索引：在桌面边缘纵向滑动，按**拼音首字母**快速跳到应用。
 *
 * ## 形态（照 Appindex，不照 Flyme 的注入路线）
 *
 * 澎湃的抽屉是 Flutter 渲染的，`scrollToPosition` 那条路彻底不通。
 * 所以这里走另一条：**自建索引层 —— 侧滑 → 字母 → 应用面板 → 松手启动**，
 * 完全不碰抽屉、不受 Flutter 影响。这也正是魅族 `slideappsearch` 的形态
 * （侧滑 → 字母条 → 应用组面板 → 启动），我们只是把面板做精简了。
 *
 * 一次手势的完整语义（**和参考项目 Appindex 的 `FloatingService.setupTriggerEvents`
 * 逐条对应**）：
 *
 * ```
 * DOWN  边缘带内按下            → 只记状态，不消费（还不知道用户要干什么）
 * MOVE  纵滑成立 + 在桌面        → 装甲：显示字母条 + 该字母的应用面板
 * MOVE  继续滑动                → 换字母（重建面板）；滑到图标上 → 高亮那个图标
 * UP    手指在某个图标上         → 启动它；否则什么都不做
 * ```
 *
 * 最后一条是刻意保留的：**只有手指明确落在图标上才启动**。Appindex 就是这么做的
 * （它的 UP 分支是 `findHitAppItem(...) >= 0` 才 `launchApp`），
 * 而且它是对的 —— 误启动一个应用比什么都不做糟得多。
 *
 * ## 三个线程，各自只能干自己的事
 *
 * | 线程 | 能做什么 | 不能做什么 |
 * | --- | --- | --- |
 * | 输入分发线程（[SideIndexDetector.onTouch]） | 读几何、算下标、写 volatile 字段 | IO、日志、IPC、分配、碰 View |
 * | 主线程（[SlidePerformancePolicy] 的 Handler） | 建/拆窗口、画条、画面板、启动、振动 | 做 `PackageManager` 查询 |
 * | 后台构建线程（[AppIndex.build]） | 扫应用清单、读图标 | 碰 UI |
 *
 * 中间的请求桥是 [SlidePerformancePolicy]：手势线程只写请求，主线程消费。
 *
 * ## 参数改完**立刻生效**（不许要求重启 SystemUI）
 *
 * 这是本特性的一条硬要求：这些参数是靠手感调的，
 * 「改一次重启一次」会让用户直接放弃这个功能。所以每个参数都有一条**廉价**的生效路径，
 * 而且都**不重建应用索引**（重建要 loadLabel/loadIcon 上百个包，拖一下滑杆重扫一遍不可接受）：
 *
 * | 参数 | 生效路径 | 延迟 |
 * | --- | --- | --- |
 * | `trigger_width` / `side` | [publishTrigger]：一次 volatile 引用赋值，输入线程下次 DOWN 读新值 | 无（下一次按下）|
 * | `scope` | [currentScope]：轮询每次跑都读它 | ≤ [SCOPE_POLL_MS] |
 * | `show_recent` | [publishIndex]：`★` 槽的增删（手势中只记 dirty，抬手再发）| 无 / 抬手后 |
 * | `blur` | 重探系统能力 + 换代浮层 | 无（两次手势之间完成）|
 * | 其余 look 与三项振动 | 换代浮层（[overlaySwapTick]）| 无（同上）|
 * | `show_handle` / `handle_height` / `handle_offset` | [applyHandle] → [SideIndexHandle.applyLook] 增量改窗口 | 无 |
 *
 * 三条纪律：
 *
 *  1. **参数只有一处读法**（[readConfig]）。安装与热应用各读一遍必然漂移，
 *     而漂移的表现（「重装一次才对得上」）极难从日志看出来。
 *  2. **浮层换代只在两次手势之间**（[overlaySwapTick] 判定、`onCommit`/`onCancel` 触发）。
 *     浮层窗口是每次手势新建、抬手就拆的，所以换代很便宜；但手势中途换会让
 *     「看到的」和「碰到的」不是同一份几何（同 [publishIndex] 里 lettersDirty 的理由）。
 *  3. **输入线程不读 prefs、不碰 Resources**：它读的 [trigger] 是一份
 *     不含 prefs 的几何快照（[TriggerConfig]）。
 *
 * ## 生效范围（配置 `side_index.scope`）
 *
 * 三档，判据一律来自 [SystemUiEnv.ForegroundTracker]（250ms 轮询的缓存，
 * 读它是纯内存操作、没有 IPC）：`launcher_only`（默认，只在桌面）/
 * `anywhere` / `exclude_launcher`。详见 [SideIndexScope]。
 *
 * 判定值由**主线程**的轮询算好放在 [scopeAllowed] 里，输入线程只读那一个 volatile ——
 * 这是照 Appindex 的 `scopeAllowsEdgeTrigger` 的取法：它也是被一个 250ms 的 handler
 * 更新的字段，触发器可见性和触摸判定读的是同一个值，所以「看得见但滑不动」不会发生。
 *
 * ## 抽屉门控（需求 #8，配置 `side_index.drawer_gate`）
 *
 * 上一条的 `launcher_only` **区分不了「桌面」和「抽屉」** —— 抽屉里前台包名仍然是桌面，
 * 而抽屉自带的 A-Z 索引条正好贴在我们右边缘的触发带上（截图
 * `research/captures/xiaomi-desktop-now.png`），两者会抢同一根手指。
 * 所以另有一条独立的门控：system_server 侧观察 WMS 的窗口属性 → 广播 → 这里的
 * [SideIndexDrawerGate]。它与 [scopeAllowed] **并列**（两个条件都要过），
 * 而不是塞进 [SideIndexScope] —— 后者的三档语义是「哪个应用里生效」，
 * 与「同一个应用里的哪一屏」是两件事。
 *
 * 通道拿不到信号时这条门控恒为 false，行为与未安装模块完全一致。详见 [SideIndexDrawerGate]。
 *
 * ## 与底角小窗的关系
 *
 * 两者共用 [SwipeEventHub] 的同一条事件流。本特性**主动避开底部角落区**，
 * 注册顺序也在小窗之后 —— 两个理由都为了保证「在角落开始的手势永远归小窗」。
 *
 * ## 当前状态
 *
 * ⚠️ **已实现，未在设备上用真手指验证。**
 * 特别是「边缘纵滑会不会和返回手势打架」（同一条边缘不能有两个
 * touch owner）必须在真机上确认，代码里做的只是「不抢、不吃、让出路权」。
 */
object SideIndex : Feature(Catalog.require(Catalog.SIDE_INDEX)) {

    private const val TAG = "FP-SideIndex"

    // 底部 56dp / 顶部 120dp 两个起手保留区已搬进 core 的 SideIndexHotZone（2026-10-21）：
    // 设置界面的预览与触发页的屏幕高亮要画同一块区域，常量与判定只能有一份。理由见那个文件。

    /**
     * 生效范围轮询间隔，取自 Appindex 的 `ForegroundAppPollingPolicy.REFRESH_INTERVAL_MS`
     * （它给 UsageStats 查询定的间隔），和 [SystemUiEnv.ForegroundTracker] 自己的
     * 250ms 一致 —— 两道轮询同频，才不会出现「判定值比数据源慢半拍还叠一层滞后」。
     */
    private const val SCOPE_POLL_MS = 250L

    /**
     * 换代浮层被推迟（手势正在跑）时的重试间隔。
     *
     * 正常路径上不需要它：手势结束的 `onCommit` / `onCancel` 会再叫一次
     * [postOverlaySwap]。这里兜的是「手势结束那条路也没叫到」的边角
     * （例如探测器在异常路径上直接 `release()`），
     * 免得一次参数改动**永远**不落地 —— 那正是「拖了没反应」的形态。
     */
    private const val SWAP_RETRY_MS = 1500L

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 每个实例的部件。install/uninstall 会整体换掉，所以都是可空 ──────────
    private var detector: SideIndexDetector? = null
    private var overlay: SideIndexOverlay? = null
    private var policy: SlidePerformancePolicy? = null
    private var handle: SideIndexHandle? = null
    private var recents: RecentApps? = null
    private var indexExecutor: ExecutorService? = null

    /**
     * 当前这一代的**全部参数**（[SideIndexConfig]）。唯一的写者是 [readConfig] 的调用点
     * （安装与热应用各一次）—— 见那个函数的注释：两份读法必然漂移。
     *
     * 只在主线程读（视图相关的判定）与安装线程写。**不是**输入线程读的东西：
     * 输入线程只读 [trigger]，那是一份为它准备的、不含 prefs 的几何快照。
     */
    private var config: SideIndexConfig? = null

    /**
     * 本代的 [HookContext]，给**没有参数可带**的回调路径用（[overlaySwapTick]）。
     *
     * 为什么不留着 `install` 的参数：那个 Runnable 是**复用**的实例（去重靠
     * [swapScheduled]），它不能捕获某一次调用的局部变量 —— 那正是「复用」的反面。
     * 与 `CornerSmallWindow` 里的 `hookContext` 字段同一个理由。
     */
    private var hookContext: HookContext? = null

    /** 生效范围轮询的 Runnable。每次安装重新建（它捕获本代的 scope 和 ctx），卸载时 removeCallbacks。 */
    private var scopePoller: Runnable? = null

    /**
     * 触发几何（px）。**输入分发线程在 ACTION_DOWN 读它**，见 [TriggerConfig]。
     *
     * `@Volatile`：写在主线程（热应用）或安装线程，读在输入分发线程。卸载时置
     * [TriggerConfig.DISABLED]，于是关态在输入线程这一侧是可证明的空操作。
     */
    @Volatile
    private var trigger: TriggerConfig = TriggerConfig.DISABLED

    /**
     * 当前生效范围（`side_index.scope`）。
     *
     * 轮询的 Runnable 每次跑的时候读它（而不是把 scope 捕获进闭包）—— 这样改 scope
     * 只要改这个字段，下一次 250ms 的轮询就按新档位判，不用重建 Runnable。
     * 只在主线程读写（安装线程写的那一次也是「装完才有轮询」，没有并发读者）。
     */
    private var currentScope: String = SideIndexScope.LAUNCHER_ONLY

    /**
     * **「哪些包算桌面」的生效集合**（本批 1️⃣，`side_index.launcher_packages`）。
     *
     * 与 [currentScope] 同一个模式、同一个理由：轮询的 Runnable 每次跑都读这个字段，
     * 所以改配置只要换掉它就够（下一次 250ms 的轮询按新集合判）—— 不用重建 Runnable、
     * 不用换代浮层、也不用重扫应用清单。
     *
     * 空集 = 用户没配过 → [SideIndexScope.allows] 退回系统解析出来的那个桌面
     * （= 改动前的行为，见 `core/LauncherScope` 的主/辅/退化那一节）。
     * 只在主线程读写（安装线程写的那一次发生在轮询启动之前）。
     */
    private var launcherPackages: Set<String> = emptySet()

    /**
     * **「索引里显示哪些应用」的生效配置**（本批 2️⃣）。
     *
     * 两个字段一起用：[indexFilterMode] 说清单的方向、[indexFilterPackages] 是那份清单
     * （归一化过的）。默认值（[IndexAppFilter.MODE_ALL] + 空集）下
     * [AppIndex.Index.filtered] 直接返回原快照（引用相等），这条路径零成本。
     *
     * 只在主线程读写（同 [currentScope]）。
     */
    private var indexFilterMode: String = IndexAppFilter.MODE_ALL
    private var indexFilterPackages: Set<String> = emptySet()

    /**
     * 过滤后的快照缓存。三个字段合成一个「缓存命中」判据：
     * **同一份基础快照 + 同一份过滤配置**才复用。
     *
     * 为什么要缓存而不是每次现算：过滤本身是纯内存的（一两个包的 HashMap 操作），
     * 但 [publishIndex] 会被「最近使用变化」这类高频事件触发（用户每切一个应用一次），
     * 而过滤结果在两次配置变化之间是**不变的**。缓存同时让「默认配置下零成本」这条
     * 更容易论证：基础快照引用没变就直接返回。
     */
    private var filteredIndex: AppIndex.Index? = null
    private var filteredFrom: AppIndex.Index? = null
    private var filteredKey: String = ""

    /**
     * 当前发布出去的是不是「过滤之后一个可启动应用都不剩」那一态。
     *
     * 只用来避免重复发同一份几何（[publishIndex] 会被「最近使用变化」每秒叫一次）。
     * 语义上与 `panelPlaceholder == EMPTY` 是同一件事，但那个字段的写点更多
     * （安装、三态落地、换代继承都在写），拿它当幂等判据会分不清「谁写的」。
     */
    private var appliedEmpty = false

    /**
     * 系统是否支持「跨窗口模糊」的缓存（`WindowManager.isCrossWindowBlurEnabled()`）。
     *
     * 安装时探一次；用户改 `blur` 开关时**重探一次** —— 他有可能刚在系统设置里打开
     * 跨窗口模糊，不重探就是「开了这个开关没反应」，而那是本项目最忌讳的表现。
     */
    private var crossWindowBlurSupported = false

    /**
     * 面板当前该显示的占位态。
     *
     * 为什么要在这里记一份：[onIndexBuilt] 只把结论写给**当**代的浮层，
     * 而换代（[swapOverlay]）要把它继承给新对象 —— 不记的话，用户在索引还没扫完时
     * 改一下外观，「读取应用中…」会变回空白（看起来像坏了）。
     */
    private var panelPlaceholder = SideIndexOverlay.PanelPlaceholder.NONE

    /** 参数监听器。必须留引用，[teardown] 才能注销。见 [watchPrefs]。 */
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 参数监听注册在哪个存储上（注销时要用同一个实例）。 */
    private var prefStore: SharedPreferences? = null

    /** [overlaySwapTick] 是否已经排进主线程队列（去重）。主线程读写。 */
    private var swapScheduled = false

    /**
     * **当前那一代浮层是按哪份配置建的**（只关心 [SideIndexConfig.windowDiffersFrom]
     * 覆盖的那几项）。换代之后才写它。
     *
     * 为什么要记「已应用」而不是记一个 `pendingDirty` 布尔：[postOverlaySwap] 在手势结束时
     * 都会被叫一次（commit / cancel 两条路），如果只靠一个 dirty 标记，就得保证
     * 「谁置位、谁清位」两边都不漏；直接比对「想要的 vs 已生效的」，
     * 判定只有一个来源，重复调用是天然幂等的。
     */
    private var windowConfig: SideIndexConfig? = null

    /** 轮询是否还应该继续。跨线程读写，所以是 volatile（见 [teardown] 里的竞态说明）。 */
    @Volatile
    private var scopePolling = false

    /**
     * 当前生效范围是否允许响应侧滑。
     *
     * 主线程的轮询写、输入分发线程的 [SideIndexDetector] 读。这就是 Appindex 的
     * `scopeAllowsEdgeTrigger`（它同样只被 `applyForegroundAppGate` 写）。
     */
    @Volatile
    internal var scopeAllowed = false
        private set

    /**
     * **边缘优先**：在边缘带内**连 DOWN 一起消费**，把这次触摸从桌面手里拿走。
     *
     * ## 为什么需要它
     *
     * 桌面自己的「上滑打开抽屉」阈值比我们的严格闸门（4×slop ≈ 96px）小得多。
     * 实测复现过：从右边缘上滑，日志里 `抽屉信号：open=true/false` 一条条刷 ——
     * 抽屉被反复开关，而抽屉一开，需求 #8 的门控又把索引抑制掉，
     * 于是**索引根本呼不出来**（产品经理报的「拉一次之后不能快速重新呼出」）。
     * 等我们过闸门再消费，抽屉早就开完了。
     *
     * 消费 DOWN 之后，后续 MOVE 由 [SideIndexDetector] 继续吃掉，
     * 桌面/页面的手势拿不到起点 —— 这就是「触发之后屏蔽其他手势」。
     *
     * ## 为什么只在 `launcher_only` 时为真
     *
     * 要解决的冲突是**桌面自己的抽屉手势**。在 `anywhere` / `exclude_launcher` 下抢 DOWN
     * 破坏的是**别的应用自己的边缘手势**，而我们与它们没有冲突 —— 那是无谓的破坏。
     */
    @Volatile
    internal var edgePriority = false
        private set

    /** 上一次判过的前台包名。只用来决定「要不要打那条日志」（Appindex 同样如此）。主线程读写。 */
    private var lastForeground: String? = null

    /** 上一次判过的抽屉门控结果。只用来决定「要不要打那条日志」。主线程读写。 */
    private var lastDrawerBlocked = false

    /** 一次性诊断标记：前台追踪器一个读数都给不出来时只提示一次。主线程读写。 */
    private var warnedNoForeground = false

    /**
     * 安装代数。
     *
     * 索引是在后台线程上扫的，扫完再回主线程发布。这中间用户可能已经把特性
     * 关掉又打开（框架会重入 [install]），所以回主线程时必须确认「我还是当代」，
     * 否则会把一份属于上一代的索引发布到新一代的浮层上。`@Volatile`：
     * 写在安装线程，读在主线程。
     */
    @Volatile
    private var generation = 0

    // ── 只在主线程读写的状态 ────────────────────────────────────────────

    /**
     * 基础索引（不含「最近使用」）。`@Volatile` 是因为后台采样线程要读
     * [AppIndex.Index.byPackage] 判断某个包能不能启动 —— 它是不可变对象，读到哪一代都不算坏。
     */
    @Volatile
    private var baseIndex: AppIndex.Index? = null

    /** 实际发布给浮层的快照（基础索引 + 「最近使用」）。 */
    private var appliedIndex: AppIndex.Index? = null

    private var recentEnabled = true

    /**
     * 手势进行中产生的「字母表该换了」标记，等手势结束再补发
     * （见 [publishIndex]：手势中途换字母表会让判定与绘制对不上）。
     */
    private var lettersDirty = false

    /** 应用清单变化的监听者。只有主线程读写（注册与注销都在主线程那一侧）。 */
    private var packageWatcher: BroadcastReceiver? = null

    /**
     * 语言 / 区域变化。**这条是「复用」带出来的正确性要求**：
     * 语言一换，所有应用的名字都变了，而**语言变化不发包广播** ——
     * 不显式监听的话，被复用的那些项会一直显示旧名字，而且没有任何报错。
     */
    private var localeWatcher: BroadcastReceiver? = null

    /** 清单一变就重建：请求 + 已排队，两个标记合起来去重（Appindex 的同一套做法）。 */
    @Volatile
    private var refreshPending = false
    @Volatile
    private var refreshScheduled = false

    /**
     * 这一轮重建里**哪些包的项不能复用**（见 [IndexReuse]）。
     *
     * 由包变化广播合并（主线程），由重建线程取走并重置 —— 两边跨线程，
     * 所以**必须加锁做读-改-写**：丢一条失效的表现是「那个应用的图标/名字不更新」，
     * 而**没有任何报错**。这是本轮性能改动里唯一会静默出错的地方。
     *
     * ⚠️ **初值必须是 [IndexReuse.Invalidation.NONE]，不能是 ALL。**
     * 第一次构建走的是另一条路（`install()` 里显式传 ALL），**不会取走这里的值**；
     * 若这里初值是 ALL，那条陈旧的 ALL 会一直留着，把**第一次增量更新毒掉**
     * （`ALL.plus(pkg)` 仍然是 ALL）—— 表现就是「复用 0」，增量白做。
     * 这个坑是装机后靠 `复用 0 / 重载 151` 这行日志抓到的，静态看代码看不出来。
     */
    private val invalidationLock = Any()
    private var pendingInvalidation: IndexReuse.Invalidation = IndexReuse.Invalidation.NONE

    /** 取走并重置失效集。重建线程调。 */
    private fun takeInvalidation(): IndexReuse.Invalidation =
        synchronized(invalidationLock) {
            pendingInvalidation.also { pendingInvalidation = IndexReuse.Invalidation.NONE }
        }

    /** 合并一条失效信息。包变化广播调（主线程）。 */
    private fun mergeInvalidation(packageName: String?) {
        synchronized(invalidationLock) { pendingInvalidation = pendingInvalidation.plus(packageName) }
    }

    // ── install / uninstall ───────────────────────────────────────────

    override fun install(ctx: HookContext): InstallReport {
        val appContext = SystemUiEnv.context()
            ?: return InstallReport(0, notes = listOf("no context"))

        // 幂等：框架可能重入（多包回调、热重载、改完配置重装）。
        // 先把上一代整体拆掉再装新的 —— 既不会挂出两套监听器，也不会留下上一代的窗口。
        //
        // 注意：**参数**的变化不走这里（那条路是 watchPrefs，见「参数改完立刻生效」一节），
        // 走这里的是特性**开关**（`f.side_index`）与框架的热重载 —— 后者正是
        // RestartPolicy 在讨论的那件事。
        teardown()
        val myGeneration = generation

        // 给前台追踪器接上框架的日志通道（它自己用 android.util.Log 打的话看不见，
        // 而"推送有没有注册上"是「回桌面后立刻侧滑」能否响应的前提）
        SystemUiEnv.ForegroundTracker.log = { msg -> ctx.log(Log.INFO, "侧滑索引：" + msg) }
        SystemUiEnv.ForegroundTracker.acquire(key, appContext)
        // 设置界面的预览底图由 SystemUI 读壁纸交过去（与底角小窗共用，引用计数；见 PreviewWallpaperPush）
        PreviewWallpaperPush.acquire(key, ctx, appContext)

        // 抽屉门控（需求 #8）：system_server 侧观察 WMS 的窗口属性 → 广播 → 这里的接收器。
        // **必须显式在这里启动**，不能在 [SideIndexDrawerGate.blocks] 里懒加载 ——
        // 那个函数跑在输入分发线程上，在那里注册广播接收器是明确的红线。
        val drawerNote = SideIndexDrawerGate.start(ctx, appContext)

        // 通知栏门控（2026-09-15）：面板拉开之后不再接管边缘手势。信号源是 SystemUI 自己的
        // `setPanelVisible`（同进程、一次布尔写），所以它**不依赖**桌面进程，也就不需要像抽屉
        // 那条一样绕 system_server 广播。详见 [SideIndexShadeGate]。
        val shadeNote = SideIndexShadeGate.start(ctx)

        // ⚠️ 参数**只从这里**变成运行期状态：[readConfig] 是唯一读 prefs 的地方，
        // 安装与「改完立刻生效」走的是同一份逻辑（两份读法必然漂移）。
        val cfg = readConfig(ctx)
        config = cfg
        hookContext = ctx

        // 触发几何**先**发布：输入线程在 ACTION_DOWN 读它，晚一步就是「滑了没反应」
        publishTrigger(appContext, cfg)
        recentEnabled = cfg.showRecent
        currentScope = cfg.scope
        // 本批 1️⃣/2️⃣：生效范围与索引过滤的配置也要在**开轮询之前**落到字段上 ——
        // 轮询每跑一次都读它们，晚一步就是「装完的第一次判定按默认值来」。
        launcherPackages = cfg.launcherPackages
        indexFilterMode = cfg.indexFilterMode
        indexFilterPackages = cfg.indexFilterPackages
        // 过滤缓存的缓存键也要一起失效：只改配置不改键的话，第一次发布索引会命中
        // 上一代（甚至空）的缓存 —— 表现是「筛了没反应，要重装一次才对」。
        invalidateFilterCache()
        panelPlaceholder = SideIndexOverlay.PanelPlaceholder.LOADING

        // 模糊能力探测（照 Appindex 的 configureBlurBehavior）要在建窗口**之前**拿到，
        // 因为窗口参数在 addView 那一刻就定了。
        crossWindowBlurSupported = probeCrossWindowBlur(appContext)
        val blurNote = when {
            !cfg.blurRequested -> "blur=off"
            crossWindowBlurSupported -> "blur=on"
            // 不支持就是没效果，不是不工作：面板照常显示（默认关，见 Catalog 里的说明）
            else -> "blur=unsupported(system disabled, panel unaffected)"
        }

        val ov = newOverlay(appContext, cfg)
        overlay = ov
        // 「当前浮层是按哪份配置建的」——**必须在这里就写**：不写的话换代判定的
        // 「已生效」那一侧是 null，第一次热应用会被静默丢掉（症状正是「拖了没反应」）。
        windowConfig = cfg

        /**
         * 安装时就把几何发布出去（字母表用 [AppIndex.ALPHABET]）。
         *
         * 为什么不等索引扫完：扫应用清单是几十到几百毫秒，这段时间里
         * 「手势应该有反应」——参考项目 Appindex 就是**先有字母条、面板上显示
         * 「读取应用中…」**（它的 `AppListLoadPolicy.shouldShowLoadingState` +
         * `showAppListLoadingState`）。如果这段时间连几何都没有，用户看到的是
         * 「滑了没反应」，而那是本项目最忌讳的表现（和「没装模块」分不开）。
         *
         * 索引就绪后 [publishIndex] 会把字母表换成真实的（多出「最近使用」槽），
         * 查询失败（[AppIndex.Result.Unavailable]）时 [onIndexBuilt] 会把它收回去。
         *
         * **必须转投主线程**：`install()` 跑在框架线程上，而浮层的所有方法都要求
         * 主线程（见 [SideIndexOverlay] 的线程纪律）。世代号用来挡「刚 post 就被卸载」。
         */
        mainHandler.post {
            if (myGeneration != generation) return@post
            // 主线程任务里的异常一样是 SystemUI 崩溃，兜住（几何算不出来就是没有几何）
            try {
                ov.setPlaceholder(SideIndexOverlay.PanelPlaceholder.LOADING)
                ov.publishLayout(AppIndex.ALPHABET)
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "侧滑索引：发布初始字母条几何失败，侧滑索引本次不响应", t)
            }
        }

        /**
         * 主线程侧的异常兜底。
         *
         * 「异常绝不逃逸」不只针对输入线程：这些回调是主线程 Looper 上的任务，
         * 抛出去就是 SystemUI 崩溃（用户看到黑屏），比输入线程抛出更严重。
         * 出错就把浮层收掉，然后记一条。
         */
        fun guarded(what: String, body: () -> Unit) {
            try {
                body()
            } catch (t: Throwable) {
                runCatching { overlay?.dismiss() }
                ctx.log(Log.ERROR, "侧滑索引 $what 异常，已收起浮层", t)
            }
        }

        val pol = SlidePerformancePolicy(
            // index 允许为 null：清单还没扫完时按占位态画（Appindex 的 loading 分支）
            //
            // ⚠️ 这四个回调一律读**可变的 `overlay` 字段**，不捕获上面那个 `ov`：
            // 外观参数变化时我们会整体换一代浮层（见 [overlaySwapTick]），
            // 捕获安装时那个对象就等于「新参数永远不生效」—— 正是这次要修的那个缺陷。
            onLetter = { slot, onRight ->
                guarded("渲染") { overlay?.show(appliedIndex, onRight, slot) }
            },
            onPointer = { x, y, onRight -> guarded("高亮") { overlay?.trackPointer(x, y, onRight) } },
            onCommit = { x, y, onRight ->
                guarded("启动") { overlay?.commit(x, y, onRight) }
                flushPendingLetters()
                // 这一次手势彻底结束了（commit 已经消化完）—— 现在才允许换代。
                // **必须在 commit 之后**叫：新浮层手上没有命中表（`itemRects`），
                // 先换再 commit 会让「抬手落在图标上」变成什么都不做。
                postOverlaySwap()
            },
            onCancel = {
                guarded("取消") { overlay?.dismiss() }
                flushPendingLetters()
                postOverlaySwap()
            },
            // 手势开始/结束 → 常驻把手让位（Appindex 的浮窗一起就把触发条盖住了，
            // 我们显式设 INVISIBLE，见 SideIndexHandle）。回调在主线程。
            //
            // ⚠️ 不要在这里维护「手势是否还在」，交给浮层自己读 policy.gestureActive：
            // 这条回调是**主线程任务**，排在 letterTick 后面时会误伤新手势的第一帧
            // （实测出现过 2 次误拒）。见 SideIndexOverlay.gestureAliveSource。
            onGesture = { active -> guarded("把手") { handle?.setGestureActive(active) } },
        )
        val det = SideIndexDetector(
            overlay = ov,
            policy = pol,
            // 输入线程在 DOWN 时读这一次快照（一次 volatile 引用读，无分配、无 IPC）
            triggerOf = { trigger },
            onError = { ctx.log(Log.WARN, "侧滑索引出错", it) },
            onNote = { msg -> ctx.log(Log.INFO, "侧滑索引：" + msg) },
        )

        policy = pol
        detector = det
        SwipeEventHub.addListener(det)
        val hub = SwipeEventHub.ensureInstalled(ctx)

        // 生效范围的门控：250ms 一次的主线程轮询，照 Appindex 的
        // startForegroundAppScopeMonitoring（它也是 `mainHandler.postDelayed(this, 250)`）。
        // 判定只读 ForegroundTracker 的 volatile 缓存，没有 IPC。
        startScopePolling(ctx)

        // 边缘把手：**常驻**显示，装的时候就要有；开关 / 尺寸 / 位置都要能热改（见 applyHandle）。
        // 不显示时连窗口都不建 —— 关掉这个开关就应该和参考项目里「没建过触发器」一样干净。
        applyHandle(appContext, cfg, myGeneration)

        // **参数**变化时热应用（只认 `side_index.` 前缀的键），不重启进程。
        //
        // 为什么必须有这一条：框架的 `HookRegistry.registerHotApply` 只监听 `f.` 开头的
        // **特性开关**，`side_index.*` 这些参数键在那条路上从来没有过落地的地方 ——
        // 这就是「改完任何一项都必须重启 SystemUI」的根因。
        watchPrefs(ctx, myGeneration)

        // 应用**清单变化**时重建索引（Appindex 的 `packageChangeReceiver` +
        // `PackageRefreshPolicy.isPackageListAction`）：装/卸/更新一个应用之后，
        // 面板必须能看到变化 —— 否则「刚装的 app 索引不到」会一直持续到用户重开特性。
        registerPackageWatcher(ctx, appContext)

        // 应用清单在后台扫：这里要 loadLabel/loadIcon 一百多个包，
        // 在安装路径上同步做会把 SystemUI 的这次回调拖住几百毫秒。
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fp-sideindex-build").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        indexExecutor = executor
        executor.execute {
            // 首次构建没有可复用的东西 —— 显式全失效（也是默认值，写出来是为了读代码的人）
            buildAndPublish(ctx, appContext, myGeneration, null, IndexReuse.Invalidation.ALL)
        }

        // 安装时探一次「最近使用的持久化落点能不能写」（只 stat，不写东西）。
        // 结论同时进日志与安装报告：这条路走不通时的表现与「没做这个功能」完全一样
        // （重启之后 ★ 槽还是空的），所以它必须是看得见的，不能只在写失败时才知道。
        val recentStoreNote = RecentStore.probe(appContext)

        ctx.log(
            Log.INFO,
            "侧滑索引已挂上：${cfg.describe()} $blurNote index=loading " +
                "geometry=${ov.geometryNote()} " +
                "recent_store=$recentStoreNote hub=${hub.describe()}"
        )

        return InstallReport(
            hooked = if (hub.isEffective) 1 else 0,
            missing = hub.missing,
            notes = hub.notes + listOf(
                "index=" + AppIndex.describe(AppIndex.Result.Loading),
                "handle=" + if (cfg.showHandle) "on(${cfg.side})" else "off",
                // 打**档位**而不是老的「强度%/时长ms」——那两个键已经删掉了，
                // 继续打它们会让安装报告报出一个界面上根本不存在的参数（诊断面骗人）。
                // 档位是这里唯一能回答「用户开了什么」的东西。
                // 打**档位 + 每个触发点实际走的路径**（`describe` 就是干这个的）。
                // 为什么要把路径也打出来：产品经理反馈「三档感觉不出差别」——
                // 最可能的原因是这台设备不支持 Composition 原语，"强"档**静默回落**成与"标准"同路。
                // 静态看代码看不出来（回落是设计好的优雅降级），只有把实际路径打出来才看得见。
                "haptic=" + if (cfg.hapticEnabled) {
                    SideIndexHaptics.describe(
                        SideIndexHaptics.resolveMode(
                            cfg.hapticEnabled,
                            cfg.hapticModeKey,
                            cfg.hapticStrengthPercent,
                        ),
                        overlay?.hapticDevice ?: SideIndexHaptics.DeviceSupport.UNKNOWN,
                    )
                } else {
                    "off"
                },
                blurNote,
                "scope=${cfg.scope}",
                "look=" + cfg.look.describe(),
                // 格子几何的**生效值**（需求 #5c）：列数与格宽都被联动夹取过，
                // 所以「用户配的」（在 look= 那一行）与「真正排出来的」是两个数。
                // 只打前者时，「拖了列数却没反应」只能靠数格子才能发现。
                "geometry=" + ov.geometryNote(),
                // 图标包：不打出来的话，「界面里选了包、面板上却是自带图标」这类问题
                // 在安装报告里没有任何可核对的东西（look.describe() 只覆盖外观参数）。
                "icon_pack=" + (cfg.iconPack ?: "none"),
                // 需求 #9 的两个参数。`describe()` 里也有（日志那条），但安装报告是
                // 诊断页看到的东西，必须自己带 —— 「新参数都要在安装报告里看得见」。
                "favorites=${cfg.favorites.size}条",
                "recent=" + if (cfg.showRecent) "${cfg.recentCount}条" else "off",
                "recent_store=$recentStoreNote",
                // 需求 #8 的信号状态。**通道失效的表现是静默的**（抽屉里又弹出来了、
                // 日志里一条都没有），所以它必须出现在安装报告里 —— 与 `recent_store=` 同一个理由。
                drawerNote,
                // 通知栏门控的结论（`shade=on(N)` / `shade=missing`）。**必须进报告**：
                // missing 时索引照旧会在面板上弹出，看报告就能少走一轮「是不是 ROM 问题」。
                shadeNote,
                // ── 本批 1️⃣/2️⃣/3️⃣：四项新参数，一条都不能少 ──
                // 1️⃣ `launchers=auto` 是**关键的那一格**：「用户没配过 + 系统解析不出桌面」
                //    与「配了却匹配不上」在行为上都是「launcher_only 不放行」，
                //    只有这一行能把它们分开。
                "launchers=" + LauncherScope.describeLaunchers(cfg.launcherPackages),
                // 2️⃣ 索引过滤：`off` / `exclude(N)` / `include(N)`。
                //    打的是**生效模式**（include + 空清单已经退化成 all），
                //    所以这一行与面板上的实际内容永远一致。
                "index_apps=" + if (IndexAppFilter.isActive(cfg.indexFilterMode, cfg.indexFilterPackages)) {
                    "${IndexAppFilter.effectiveMode(cfg.indexFilterMode, cfg.indexFilterPackages)}" +
                        "(${cfg.indexFilterPackages.size})"
                } else {
                    "all"
                },
                // 3️⃣ 名字与单项图标：只打条数（内容另有日志行），理由同 `favorites=`。
                "name_overrides=${cfg.nameOverrides.size}条",
                "icon_overrides=" + if (cfg.iconOverrides.size == 0) {
                    "none"
                } else {
                    "${cfg.iconOverrides.size}条"
                },
            ) + cfg.lookNotes + cfg.contentNotes,
        )
    }

    /**
     * 后台扫一遍应用清单并回主线程发布。**跑在 [indexExecutor] 上**。
     *
     * Android 给所有线程都装了默认的未捕获异常处理器（`KillApplicationHandler`），
     * 也就是说后台线程里逃出去的异常一样会杀掉 SystemUI。这里兜住。
     *
     * 首次安装与「包变化后重建」走的是同一条路（Appindex 的
     * `AppListAdapter.refreshAsync` 也是复用同一次 `loadAllApps`）。
     */
    private fun buildAndPublish(
        ctx: HookContext,
        appContext: Context,
        myGeneration: Int,
        reuse: AppIndex.Index?,
        invalidation: IndexReuse.Invalidation,
    ) {
        val result = try {
            // 带上一次的结果做复用 + 打耗时埋点：不变的那些项连图标一起搬过来，
            // 跳过 loadLabel/loadIcon 两次 IPC。一次包变化的重载项从「全部」降到「那一个」。
            AppIndex.build(appContext, reuse, invalidation) { line ->
                ctx.log(Log.INFO, "侧滑索引：" + line)
            }
        } catch (t: Throwable) {
            AppIndex.Result.Unavailable("build: " + t.javaClass.simpleName)
        }
        mainHandler.post { onIndexBuilt(ctx, myGeneration, result) }
    }

    override fun uninstall() {
        // 关态 = 和没装模块一样：摘监听器、收窗口、停线程、丢索引。
        // ForegroundTracker 与底角小窗共用：按 key 释放，两个都卸了才真正停
        teardown()
        SystemUiEnv.ForegroundTracker.release(key)
        PreviewWallpaperPush.release(key)
        super.uninstall()
    }

    /** 把这一代的全部运行时状态拆掉。可从任何线程调，且重复调安全。 */
    private fun teardown() {
        generation++

        // 抽屉门控先停：注销广播接收器、把状态清回「未知」。
        // 顺序无所谓（它只在输入线程被读，而输入线程的读只看 volatile 字段），
        // 但**必须停** —— 不停就漏一个注册在 SystemUI 上的导出接收器。
        SideIndexDrawerGate.stop()

        // 通知栏门控同理：摘 hook + **把状态清回「不在场」**。不清的话，卸载后（或换到
        // 没挂上这条 hook 的进程里）索引会被一个 stale 的 true 永久抑制。
        SideIndexShadeGate.stop()

        // 参数监听**先**摘：它是唯一一条能在别的部件都拆完之后再叫一次热应用的路径
        // （RemotePreferences 的变更通知来自框架线程）。不摘的话，下一次安装就会
        // 攒出第二个监听器 —— 每个监听器各自装一代，症状是「改一个参数生效两三次」。
        stopWatchPrefs()
        // 欠着的换代也取消：这一刻之后没有「新浮层」可换了
        mainHandler.removeCallbacks(overlaySwapTick)
        swapScheduled = false
        windowConfig = null

        detector?.let {
            SwipeEventHub.removeListener(it)
            it.release()
        }
        detector = null

        // 先停轮询再拆把手：轮询是唯一会去动把手可见性的东西
        scopePolling = false
        scopePoller?.let { mainHandler.removeCallbacks(it) }
        scopePoller = null
        scopeAllowed = false
        currentScope = SideIndexScope.LAUNCHER_ONLY

        // 触发几何收回成「任何位置都不成立」—— 关态在输入分派线程那一侧
        // 因此是可证明的空操作（见 TriggerConfig.DISABLED）。
        trigger = TriggerConfig.DISABLED

        handle?.release()
        handle = null

        stopPackageWatcher()

        recents?.stop()
        recents = null

        indexExecutor?.shutdownNow()
        indexExecutor = null

        policy?.stop()
        policy = null

        overlay?.release()
        overlay = null

        baseIndex = null
        appliedIndex = null
        lettersDirty = false
        // 本批 1️⃣/2️⃣：把生效集合与过滤配置也清回默认值（关态 = 与未安装模块一致），
        // 并把过滤缓存一起丢掉 —— 不清缓存的话，下一次安装会在第一份快照上
        // 命中上一代的过滤结果。
        launcherPackages = emptySet()
        indexFilterMode = IndexAppFilter.MODE_ALL
        indexFilterPackages = emptySet()
        invalidateFilterCache()
        panelPlaceholder = SideIndexOverlay.PanelPlaceholder.NONE
        config = null
        hookContext = null
        crossWindowBlurSupported = false
    }

    // ── 配置：一处读、处处热应用（安装与「改完立刻生效」共用同一份逻辑） ────

    /**
     * 读参数。**整个特性只有这里**把 prefs 变成 [SideIndexConfig]。
     *
     * 为什么必须是「唯一一处」：安装路径和热应用路径各读一遍，两份读法迟早漂移，
     * 而漂移的表现是「重装一次才对得上」或者「装的是 A、拖完滑杆变成 B」——
     * 这两种都极难从日志看出来，因为它们各自都是「正确」的。
     *
     * 安装报告的 notes 也从这里回去（[SideIndexConfig.lookNotes]）：
     * 「代码支持、Catalog 还没声明」的键必须是看得见的。
     */
    private fun readConfig(ctx: HookContext): SideIndexConfig {
        // ── 复刻 Appindex 的个性化项（见 SideIndexLook 顶部的对照表）──
        // 这些键**可能还没在 core/Catalog 里声明**（那一块由协作者统一加），
        // 所以不能像 prefSpec 那样「缺了就抛」—— 抛在 install 里就是
        // 「打开这个特性直接没反应」，比缺一个滑块严重得多。
        val lookReader = LookReader(ctx)
        val look = SideIndexLook(
            letterOffsetPercent = lookReader.slider(
                "letter_offset",
                SideIndexLook.DEFAULT_LETTER_OFFSET_PERCENT,
                SideIndexLook.MIN_LETTER_OFFSET_PERCENT,
                SideIndexLook.MAX_LETTER_OFFSET_PERCENT,
            ),
            letterScalePercent = lookReader.slider(
                "letter_scale",
                SideIndexLook.DEFAULT_LETTER_SCALE_PERCENT,
                SideIndexLook.MIN_LETTER_SCALE_PERCENT,
                SideIndexLook.MAX_LETTER_SCALE_PERCENT,
            ),
            iconSizeDp = lookReader.slider(
                "icon_size",
                SideIndexLook.DEFAULT_ICON_SIZE_DP,
                SideIndexLook.MIN_ICON_DP,
                SideIndexLook.MAX_ICON_DP,
            ),
            iconRadiusDp = lookReader.slider(
                "icon_radius",
                SideIndexLook.DEFAULT_ICON_RADIUS_DP,
                SideIndexLook.MIN_ICON_RADIUS_DP,
                SideIndexLook.MAX_ICON_RADIUS_DP,
            ),
            panelInsetDp = lookReader.slider(
                "panel_inset",
                SideIndexLook.DEFAULT_PANEL_INSET_DP,
                SideIndexLook.MIN_PANEL_INSET_DP,
                SideIndexLook.MAX_PANEL_INSET_DP,
            ),
            handleHeightPercent = lookReader.slider(
                "handle_height",
                SideIndexLook.DEFAULT_HANDLE_HEIGHT_PERCENT,
                SideIndexLook.MIN_HANDLE_HEIGHT_PERCENT,
                SideIndexLook.MAX_HANDLE_HEIGHT_PERCENT,
            ),
            handleOffsetPercent = lookReader.slider(
                "handle_offset",
                SideIndexLook.DEFAULT_HANDLE_OFFSET_PERCENT,
                SideIndexLook.MIN_HANDLE_OFFSET_PERCENT,
                SideIndexLook.MAX_HANDLE_OFFSET_PERCENT,
            ),
            // ── 本批新增（需求 #4/#5/#5c/#7/#10）──
            cellHeightDp = lookReader.slider(
                "cell_height",
                SideIndexLook.DEFAULT_CELL_HEIGHT_DP,
                SideIndexLook.MIN_CELL_HEIGHT_DP,
                SideIndexLook.MAX_CELL_HEIGHT_DP,
            ),
            // 列数与横向间距（需求 #5c）。两者都会被 [SideIndexLook.panelMetrics] 联动夹取，
            // 所以**生效值**另有报告（`SideIndexOverlay.geometryNote()`）：
            // 这里读的是用户配的，那边打的是真正排出来的。
            columns = lookReader.intSlider(
                "columns",
                SideIndexLook.DEFAULT_COLUMNS,
                SideIndexLook.MIN_COLUMNS,
                SideIndexLook.MAX_COLUMNS,
            ),
            cellWidthDp = lookReader.slider(
                "cell_width",
                SideIndexLook.DEFAULT_CELL_WIDTH_DP,
                SideIndexLook.MIN_CELL_WIDTH_DP,
                SideIndexLook.MAX_CELL_WIDTH_DP,
            ),
            hideAppNames = lookReader.toggle("hide_app_names", SideIndexLook.DEFAULT_HIDE_APP_NAMES),
            // 背景材质档位（需求 #7 档 B）：blur（= 改动前）/ glass。
            // 容错读：键还没声明时用默认档（blur）并把键名记进安装报告。
            material = lookReader.choice("material", SideIndexLook.DEFAULT_MATERIAL),
            blurRadiusDp = lookReader.slider(
                "blur_radius",
                SideIndexLook.DEFAULT_BLUR_RADIUS_DP,
                SideIndexLook.MIN_BLUR_RADIUS_DP,
                SideIndexLook.MAX_BLUR_RADIUS_DP,
            ),
            bubbleAnimMs = lookReader.slider(
                "bubble_anim_ms",
                SideIndexLook.DEFAULT_BUBBLE_ANIM_MS,
                SideIndexLook.MIN_BUBBLE_ANIM_MS,
                SideIndexLook.MAX_BUBBLE_ANIM_MS,
            ),
            bubbleIconSlots = lookReader.toggle(
                "bubble_icon_slots",
                SideIndexLook.DEFAULT_BUBBLE_ICON_SLOTS,
            ),
        )

        // ── 收藏项──────────────────────────────────────
        //
        // 清单里的项有两种形态（`ShortcutEntries`：应用 = 纯包名、快捷方式 = `包名/id`、
        // 链接 = `link:...`）。侧滑索引只能启动**应用**（它拿的是 MAIN/LAUNCHER 入口），
        // 所以非应用项在这里就走不下去了 —— 但**不能静默丢掉**：
        // 表现会是「我在收藏里加了 3 个，面板上只有 2 个」，而没有任何地方能看出为什么。
        // 所以跳过多少条写进安装报告（`contentNotes`）。
        // 用 `ShortcutEntries.parse` 判定而不是自己找 `/`：那个字符的分割规则
        // （先认 `link:` 前缀、再取第一个 `/`）只该有一处事实。
        val favorites = lookReader.orderList("favorites")
        val favoritesSkipped = favorites.count {
            ShortcutEntries.parse(it).kind != ShortcutEntries.Kind.APP
        }

        return SideIndexConfig(
            triggerWidthDp = ctx.prefs.value(prefSpec<PrefSpec.Slider>("trigger_width")),
            side = ctx.prefs.value(prefSpec<PrefSpec.Choice>("side")),
            showRecent = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("show_recent")),
            showHandle = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("show_handle")),
            hapticEnabled = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("haptic")),
            // 新参数面：触觉反馈的档位（关 / 轻 / 标准 / 强）。容错读 —— 键没声明时返回
            // 默认档并记进安装报告，不会静默变成「拖了档位没反应」。
            hapticModeKey = lookReader.choice(
                SideIndexHaptics.KEY_MODE,
                SideIndexHaptics.DEFAULT_MODE.name.lowercase(),
            ),
            // 旧键 haptic_strength / haptic_duration 已随参数面重构删除（理由见 Catalog 里的说明）。
            // 这里仍把**老默认值 60** 当迁移输入传下去：它落在 STANDARD，恰好等于新默认档，
            // 所以万一日后 haptic_mode 读不到，也**不会把振动意外关掉**（那个迁移表是 0..9 才判关）。
            // 60 这个数来自被删掉的 `haptic_strength` 的 Catalog 默认值，不是随手取的。
            hapticStrengthPercent = 60,
            hapticDurationMs = 0,
            blurRequested = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("blur")),
            scope = ctx.prefs.value(prefSpec<PrefSpec.Choice>("scope")),
            look = look,
            // 图标包（需求 #5）。**它不走 `prefSpec<T>`**：那个读法要求「Catalog 里一定
            // 有这一条」，缺了就抛 —— 抛在 install 里就是「打开这个特性直接没反应」。
            // 图标包是后补的能力，Catalog 里那条声明由协作者统一接线，所以这里按
            // [LookReader] 的容错读法走：读不到就用「不套用」，并把键名记进安装报告。
            iconPack = lookReader.iconPack(),
            // 收藏项 / 最近数量（需求 #9）。两条都走**容错读**（理由见 LookReader 里那两个
            // 方法）：声明还没接线时也要能用，且缺什么必须报出来。
            favorites = favorites,
            recentCount = lookReader.count(
                "recent_count",
                SideIndexConfig.DEFAULT_RECENT_COUNT,
                SideIndexConfig.MIN_RECENT_COUNT,
                SideIndexConfig.MAX_RECENT_COUNT,
            ),
            // ── 本批 1️⃣/2️⃣/3️⃣ ──────────────────────────────────────────────
            //
            // 四条都走 [LookReader] 的**容错读**，理由与 `icon_pack` 那条逐字相同：
            // 这几个键的 Catalog 声明由协作者统一接线，而「抛在 install 里」=
            // 「打开这个特性直接没反应」，比「少一个入口」严重得多。
            // 读不到就用默认值（= 改动前的行为），并把键名写进安装报告。
            //
            // 1️⃣ 哪些包算桌面。**归一化在这里做**（而不是在判定里）：
            //    `LauncherScope.allows` 每次轮询都要跑（4 次/秒），把一个集合
            //    trim/去空一遍放进去是白费；而且归一化只该有一处事实。
            launcherPackages = LauncherScope.normalizeLaunchers(lookReader.appList("launcher_packages")),
            // 2️⃣ 索引里显示哪些应用。模式与清单**必须一起归一化**：
            //    「模式 = 只保留 + 清单空」要退化成「不过滤」，两处各判一遍必然漂移。
            indexFilterMode = lookReader.contentChoice(
                "index_filter_mode",
                IndexAppFilter.MODE_ALL,
            ),
            indexFilterPackages = IndexAppFilter.normalizePackages(
                lookReader.appList("index_filter_packages"),
            ),
            // 3️⃣ 单应用的名字与图标覆盖。
            nameOverrides = DisplayNameOverrides.decode(lookReader.nameOverrides()),
            iconOverrides = SideIndexIconOverride.read(ctx.prefs),
            lookNotes = lookReader.notes(),
            contentNotes = lookReader.contentNotes() +
                (if (favoritesSkipped > 0) {
                    listOf("favorites 跳过 $favoritesSkipped 条非应用项(快捷方式/链接，侧滑索引只能启动应用)")
                } else {
                    emptyList()
                }),
        )
    }

    /**
     * 把触发几何发布给输入分派线程。可从任何线程调（一次 `@Volatile` 引用赋值）。
     *
     * 只做一件事，但它是「改参数立刻生效」最重要的一条：输入线程的 ACTION_DOWN 读的就是
     * [trigger]，所以这一次赋值之后**下一次按下**就是新几何了 —— 不需要任何延迟，
     * 也不需要重建任何东西。
     */
    private fun publishTrigger(appContext: Context, cfg: SideIndexConfig) {
        val density = appContext.resources.displayMetrics.density
        // 起步阈值用系统的 touch slop（Flyme 那侧是 Launcher3 的
        // SingleAxisSwipeDetector 在用它，值同源）。它是**倍数**的底数：
        // 起步要 4 × 它（严格策略），见 SideIndexDetector.strictStartOk。
        //
        // ⚠️ 它**不随我们的参数变化**：ViewConfiguration.getScaledTouchSlop() 是系统按
        // density 算出来的常数，与 side_index.* 任何一个键都无关（TriggerConfig 的类注释
        // 里写了这一点）。所以这里只是把它放进快照，值每次读都一样；
        // 输入分派线程那边也不用每次 DOWN 去碰 Resources。
        val touchSlopPx = ViewConfiguration.get(appContext).scaledTouchSlop.toFloat()
        trigger = cfg.trigger(
            density = density,
            touchSlopPx = touchSlopPx,
            cornerGuardPx = SideIndexHotZone.CORNER_GUARD_DP * density,
            topGuardPx = SideIndexHotZone.TOP_GUARD_DP * density,
        )
    }

    /** 图标预解析的延迟（ms）。见 [scheduleIconSweep]。 */
    private const val ICON_SWEEP_DELAY_MS = 3000L

    /**
     * **逐应用**解析面板图标的后台扫描（图标预热的第二段）。**主线程**（只负责排程）。
     *
     * ## 为什么需要第二段
     *
     * 第一段（[SideIndexIconPack.prewarm]）只解析图标包的 appfilter 映射表；
     * 真正贵的是**每个应用那一次解析** —— 真实图标包通常不覆盖普通应用，于是走
     * 「现合成一张 192×192 形状位图」那条路。实测（临时探针，同一次手势）：
     *
     * ```
     * buildPanel=9次/353ms   bindCell=80次/259ms（其中图标解析 213ms）  assemblePanel=9次/43ms
     * ```
     *
     * 也就是**换字母时 73% 的主线程时间花在这上面、平均 2.7ms/格**，而 60fps 的预算是
     * 16.7ms —— 一次换字母就爆帧，手感就是「不那么顺滑」。挪到后台之后
     * （与扇形 `FanTargets.Pool.composePages` 同一条纪律：绘制路径绝不碰图标解析），
     * 主线程那次 [SideIndexIconPack.apply] 就只是一次 HashMap 命中。
     *
     * ## 为什么延迟 3 秒、为什么清单就绪后才排
     *
     * - 清单没就绪时不知道该解析哪些应用（[baseIndex] 为 null 就直接跳过）；
     * - 开机/安装那一刻 SystemUI 本来就忙，早排等于和启动抢 CPU；晚几秒对用户没有区别
     *   （他不可能在功能还没响应时就划过一整个字母条）。
     * - 幂等：同一份清单只扫一次（[SideIndexIconPack.prewarmIcons] 按签名去重），
     *   所以清单重建、参数变化、换代时被重复调用也不会白做。
     */
    private fun scheduleIconSweep() {
        val idx = baseIndex ?: return
        val pack = config?.iconPack ?: return
        if (pack.isEmpty()) return
        val app = SystemUiEnv.context() ?: return
        val all = ArrayList<AppEntry>(idx.byPackage.size)
        for (slot in idx.letters) all.addAll(idx.apps(slot))
        if (all.isEmpty()) return
        mainHandler.postDelayed({
            // 延迟期间可能已经卸载/换配置：这里只是「尽力而为」的预热，
            // 拿旧清单扫一次最坏是多解析几个用不到的图标（缓存有上限）。
            try {
                SideIndexIconPack.prewarmIcons(app, pack, all)
            } catch (t: Throwable) {
                Log.w(TAG, "侧滑索引：图标预解析排程失败（功能不受影响，换字母时按需解析）", t)
            }
        }, ICON_SWEEP_DELAY_MS)
    }

    /**
     * 按一份配置建一个**新的**浮层对象（不发布几何、不动窗口）。
     *
     * 单独的构造点：安装与换代（[swapOverlay]）必须建出**一样**的东西，
     * 否则「装出来的」和「拖动滑杆换出来的」会在某个参数上分叉。
     */
    private fun newOverlay(appContext: Context, cfg: SideIndexConfig): SideIndexOverlay {
        val ov = SideIndexOverlay(
            appContext,
            look = cfg.look,
            hapticEnabled = cfg.hapticEnabled,
            hapticStrengthPercent = cfg.hapticStrengthPercent,
            hapticDurationMs = cfg.hapticDurationMs,
            blurRequested = cfg.blurRequested,
            // 图标包（需求 #5）。安装与换代走**同一个**构造点（见本函数的注释），
            // 所以这里加一行就同时覆盖了两条路 —— 不在这里传，那个参数就是死的。
            iconPack = cfg.iconPack,
            // 本批 3️⃣：单应用的名字与图标覆盖。同样必须走这一个构造点 ——
            // 只改 `bindCell` 而不在这里传，安装时生效、热应用时不生效（反过来也一样），
            // 那是最难查的一类「一半生效」。
            nameOverrides = cfg.nameOverrides,
            iconOverrides = cfg.iconOverrides,
        )
        // 图标包：把 appfilter 的解析（几千条 XML）挪到后台线程预热。见 [scheduleIconSweep]
        // 的两段式说明 —— 这里只做第一段（映射表），第二段（逐应用解析图标）要等清单就绪。
        if (!cfg.iconPack.isNullOrEmpty()) {
            // `prewarm` 内部已经把所有异常吃掉了（它只负责排一个后台任务）；
            // 这里再兜一层是为了「连排程失败也绝不逃逸」—— 本函数跑在框架线程上。
            try {
                SideIndexIconPack.prewarm(appContext, cfg.iconPack)
            } catch (t: Throwable) {
                Log.w(TAG, "侧滑索引：图标包预热排程失败（面板图标照常，退回自带图标）", t)
            }
        }
        scheduleIconSweep()
        // 单应用图标覆盖（本批 3️⃣）：同一个理由排一次预热 —— 那条路要读
        // `content://` 里的 PNG（跨进程 + 解码），绝不能留在第一次绑格子那一刻做。
        // 没配覆盖时 [SideIndexIconOverride.prewarm] 直接返回（一次比较）。
        try {
            SideIndexIconOverride.prewarm(appContext, cfg.iconOverrides)
        } catch (t: Throwable) {
            Log.w(TAG, "侧滑索引：单项图标覆盖预热排程失败（面板图标照常，退回自带图标）", t)
        }
        ov.crossWindowBlurEnabled = crossWindowBlurSupported
        // 「有没有手势在进行」的唯一真相源 —— 浮层那道「迟到的渲染不许复活窗口」的闸门读它。
        // 走这个函数而不是安装路径里的某一行：安装与换代都会经过这里，两处都接线才不会
        // 出现「换代之后那次容器的竞态又回来了」。读的是**字段**（`policy?.`）而不是捕获对象：
        // 安装时本函数比 `policy` 的赋值早，捕获就会永远拿到 null。
        ov.setGestureAliveSource { policy?.gestureActive == true }
        return ov
    }

    /**
     * 把一份新配置落到运行时。**主线程**（[watchPrefs] 已经把回调转投过来了）。
     *
     * ## 分三类走，因为「生效代价」不一样
     *
     * | 参数 | 走哪条路 | 下次手势前生效？ |
     * | --- | --- | --- |
     * | `trigger_width` / `side` | [publishTrigger]：一次 volatile 引用赋值 | 是（下一次 DOWN 就是新值）|
     * | `scope` | 改轮询每次读的字段 | 是（下一次 250ms 轮询）|
     * | `launcher_packages`（本批 1️⃣） | 同 `scope`：改轮询每次读的字段 | 是（下一次 250ms 轮询）|
     * | `index_filter_mode` / `index_filter_packages`（本批 2️⃣） | 纯内存重算一份快照（[publishIndex]）| 是（手势中则等抬手）|
     * | `name_overrides` / `icon_overrides`（本批 3️⃣） | 换代浮层（[postOverlaySwap]）| 是（换代在下一次手势之前完成）|
     * | `show_recent` | 重算快照（`★` 槽的增删）| 是（手势中则等抬手，见 [publishIndex]）|
     * | `blur` | 重探系统能力 + 换代浮层 | 是（换代在下一次手势之前完成）|
     * | 其余 look / 振动 | 换代浮层（[postOverlaySwap]）| 是（同上）|
     * | `show_handle` / 把手两项 | [applyHandle] 增量更新窗口 | 是（`updateViewLayout`）|
     *
     * **为什么换代要绕 [postOverlaySwap] 而不是在这里直接换**：参数变化可能落在
     * 「手势刚结束、commit 还没被主线程消费」的那个缝里，而 commit 需要**当时那个浮层**
     * 手上的命中表。多绕一次主线程任务（`post` 的 when 比已经排队的 commit 晚）就绕开了。
     *
     * ## 绝不在这里做的事
     *
     * 不重建应用索引：那要 loadLabel/loadIcon 上百个包，拖一下滑杆重扫一遍是不可接受的。
     * 索引的失效条件只有两个 —— 应用清单变化（[registerPackageWatcher]）与重新安装。
     */
    private fun applyConfig(ctx: HookContext, appContext: Context, next: SideIndexConfig) {
        val prev = config
        config = next
        if (prev == null || next == prev) return

        // 1. 触发几何（输入线程 DOWN 时读）。立刻发布：进行中的那次手势已经抄过自己那份
        //    快照了（SideIndexDetector.gestureTrigger），不会出现「半路换几何」。
        if (next.triggerWidthDp != prev.triggerWidthDp || next.side != prev.side) {
            publishTrigger(appContext, next)
        }

        // 2. 生效范围：轮询的 Runnable 每次跑都读 currentScope，所以改字段就够，
        //    不用重建 Runnable（下一次轮询按新档位判，最多滞后 SCOPE_POLL_MS）。
        if (next.scope != prev.scope) currentScope = next.scope

        // 2b. **哪些包算桌面**（本批 1️⃣）：与 scope 同一条路 —— 轮询每次跑都读
        //     launcherPackages，换掉字段就生效。不换代浮层、不重扫清单、不读文件。
        if (next.launcherPackages != prev.launcherPackages) {
            launcherPackages = next.launcherPackages
        }

        // 2c. **索引里显示哪些应用**（本批 2️⃣）：纯内存地重算一次快照就够了。
        //     不重扫应用清单（那是 loadLabel/loadIcon 上百个包）、不换代浮层
        //     （浮层不持有清单，内容每帧从快照现读）—— 与收藏项那条路逐字相同。
        //     手势进行中时 publishIndex 只记 dirty（等抬手再发），与「清单第一次扫完」
        //     是同一条路，所以不会出现「画的是新的、判定按旧的」。
        if (next.indexFilterMode != prev.indexFilterMode ||
            next.indexFilterPackages != prev.indexFilterPackages
        ) {
            indexFilterMode = next.indexFilterMode
            indexFilterPackages = next.indexFilterPackages
            invalidateFilterCache()
            publishIndex(force = true)
        }

        // 2d. 名字 / 单项图标覆盖（本批 3️⃣）走 `windowDiffersFrom` → 换代浮层：
        //     它们都在 `bindCell` 那一刻生效，而浮层对象跨手势复用（见那个函数的注释）。
        //     换代之前把解码预热排上，免得第一次滑到那个字母时在主线程上解一次图。
        if (next.iconOverrides.signature != prev.iconOverrides.signature) {
            SideIndexIconOverride.prewarm(appContext, next.iconOverrides)
        }

        // 3. 最近使用：`★` 槽的增删 → 字母表可能变。走既有的 publishIndex，
        //    它在手势进行中只记 dirty（等抬手再发），与「清单第一次扫完」是同一条路。
        if (next.showRecent != prev.showRecent) {
            recentEnabled = next.showRecent
            if (next.showRecent) {
                startRecentSampling()
            } else {
                recents?.stop()
                recents = null
            }
            publishIndex(force = true)
        }

        // 3b. 收藏项（需求 #9）：`♥` 槽的增删与内容变化。
        //     这一条是**纯内存**的 —— 收藏项是包名清单，而「包名 → 启动入口」的映射
        //     已经在 `baseIndex.byPackage` 里了（`withSpecial` 只查表）。
        //     所以：不重扫应用清单（那是 loadLabel/loadIcon 上百个包）、不换代浮层
        //     （浮层不持有清单，内容每帧从快照现读）、不读文件。
        //     这就是「新建/删除收藏之后不用重启 SystemUI 就能看到」的落地。
        if (next.favorites != prev.favorites) {
            publishIndex(force = true)
        }

        // 3c. 最近使用数量（需求 #9）：**就地改显示数量，不重建采样器**。
        //     为什么不重建：重建会造出「旧线程正在写盘、新线程刚读完盘」的窗口，
        //     新线程随后的一次写盘会把旧线程刚写进去的那一条盖掉（真丢数据）。
        //     而这里根本不需要重建 —— 保留的历史一直是按 MAX_RECENT_COUNT 留的
        //     （见 RecentApps.displayCount 的注释），所以调大数量立刻就能看到更多，
        //     调小只是少显示几格。
        if (next.recentCount != prev.recentCount) {
            recents?.applyDisplayCount(next.recentCount)
            publishIndex(force = true)
        }

        // 4. 模糊：用户**刚打开**这个开关时系统能力可能是后来才开的，所以重探一次
        //    （一次 Binder 调用，只在开关翻转时发生）。
        if (next.blurRequested != prev.blurRequested) {
            crossWindowBlurSupported = probeCrossWindowBlur(appContext)
        }

        // 5. 浮层窗口参数（外观 / 振动 / 模糊）→ 换代浮层
        if (next.windowDiffersFrom(prev)) postOverlaySwap()

        // 6. 把手：**增量**更新（不换代）—— 它是常驻窗口，拆了重建会闪，
        //    而且它自己知道怎么只改尺寸/位置（见 SideIndexHandle.applyLook）。
        if (next.handleDiffersFrom(prev)) applyHandle(appContext, next, generation)

        // 7. 图标来源变了 → 逐应用那份缓存刚被作废（见 SideIndexIconPack.prewarm 的作废段），
        //    重排一次后台预解析；不排的话「换完图标包，第一次划照样卡」。
        //    幂等：prewarmIcons 按签名去重，没换就是一次字符串比较。
        if (next.iconPack != prev.iconPack || next.iconOverrides != prev.iconOverrides) {
            scheduleIconSweep()
        }
    }

    /**
     * 请求换代浮层。**主线程**（配置回调与手势结束回调都在主线程）。
     *
     * 判据是「想要的 vs 已经生效的」（[windowConfig]），所以重复调用天然幂等：
     * 手势结束那条路（`onCommit` / `onCancel`）每次都叫它也不会有多余动作。
     *
     * **为什么用 `post` 而不是就地换代**：`post` 的 `when` 比任何**已经排队**的消息都晚，
     * 于是「参数变化恰好落在抬手与 commit 之间」时，commit 仍然打在它认识的那个浮层上。
     * 那个窗口只有一个主线程任务宽（毫秒级），但用一行 `post` 就能封掉，没必要留。
     */
    private fun postOverlaySwap() {
        val want = config ?: return
        val have = windowConfig ?: return
        if (!want.windowDiffersFrom(have)) return
        // 已经有一次排着也要重排：那一次可能是 [SWAP_RETRY_MS] 之后的定时重试
        // （手势中的那次推迟留下的），而手势一结束就该立刻落地，不该再等那个定时器。
        mainHandler.removeCallbacks(overlaySwapTick)
        swapScheduled = true
        mainHandler.post(overlaySwapTick)
    }

    /**
     * 换代的落地。复用同一个 Runnable 实例（去重靠 [swapScheduled]）。
     *
     * 若此刻手势正在跑，就**推迟到这次手势结束**：`onCommit` / `onCancel` 会再叫一次
     * [postOverlaySwap]，而 [SideIndex.flushPendingLetters] 已经在同一个位置处理过
     * 同一类问题（手势中途换字母表会让判定与绘制对不上）。这里再补一个定时重试，
     * 兜住「手势结束那条路也没叫到」的边角（例如探测器在异常路径上直接 release 了）。
     */
    private val overlaySwapTick = object : Runnable {
        override fun run() {
            swapScheduled = false
            val ctx = hookContext ?: return
            val want = config ?: return
            val have = windowConfig ?: return
            if (!want.windowDiffersFrom(have)) return
            if (policy?.gestureActive == true) {
                swapScheduled = true
                mainHandler.postDelayed(this, SWAP_RETRY_MS)
                return
            }
            val app = SystemUiEnv.context() ?: return
            try {
                swapOverlay(ctx, app, want)
            } catch (t: Throwable) {
                // 主线程任务里逃出去的异常就是 SystemUI 崩溃
                ctx.log(Log.ERROR, "侧滑索引：热应用外观参数失败，本次沿用旧浮层", t)
            }
        }
    }

    /**
     * 换一代浮层。**主线程，且只在两次手势之间**（由 [overlaySwapTick] 保证）。
     *
     * ## 为什么「换代」是廉价且安全的
     *
     * 浮层窗口是**每次手势新建、抬手就拆**的（见 `SideIndexOverlay.ensureWindow` /
     * `dismiss`）：两次手势之间它手上一个 View 都没有，跨窗口模糊之类的窗口参数
     * 是在 `addView` 那一刻才生效的。所以换掉整个对象只是换掉那几个构造参数，
     * **不重扫应用清单**（loadLabel / loadIcon 一个都不碰）。
     *
     * ## 三件事一件都不能少
     *
     *  1. 继承**字母表**：新浮层没有几何（`layout == null`），而输入线程的 DOWN 直接读它 ——
     *     不补上就是「改完参数，手势完全不响应」。沿用旧浮层当前那一份，
     *     免得「只改外观」把判定与绘制换成两份不同的字母表。
     *  2. 继承**占位态**：索引还没扫完时用户改外观，不能把「读取应用中…」变回空白。
     *  3. 通知 [SideIndexDetector] 换引用（[SideIndexDetector.setOverlay]）：它是唯一
     *     持有浮层的判定侧对象，不通知它 = 判定读旧浮层、渲染走新浮层。
     *
     * 旧浮层走 `release()` 而不是 `dismiss()`：`release()` 会把 `released` 闸门立起来，
     * 于是它手上可能还排着的渲染任务（`removeCallbacks` 摘不掉已经在跑的那个）
     * 不会把窗口重新建出来 —— 那正好违反「关态必须与未安装模块完全一致」。
     */
    private fun swapOverlay(ctx: HookContext, appContext: Context, cfg: SideIndexConfig) {
        val old = overlay
        val ov = newOverlay(appContext, cfg)
        ov.setPlaceholder(panelPlaceholder)
        if (old?.layout != null) {
            ov.publishLayout(old.layout!!.letters)
        }
        // 旧浮层没有几何 = 索引查询失败（AppIndex.Result.Unavailable）那一态：
        // 原样保持「没有几何」，因为那一态的含义就是「不知道有没有应用」，
        // 手势必须继续完全不响应（见 onIndexBuilt 的三态表）。
        detector?.setOverlay(ov)
        overlay = ov
        windowConfig = cfg
        old?.release()
        ctx.log(Log.INFO, "侧滑索引：外观参数已热应用，换了一代浮层")
    }

    /**
     * 建 / 改 / 拆边缘把手。**可从任何线程调**（安装跑在框架线程上，热应用在主线程上）：
     * 对象换手在这里做，动窗口的部分一律转投主线程。
     *
     * 三条规则：
     *  - `show_handle` 关着 → 连对象都不留（关态要与「没建过触发器」一样干净）；
     *  - 打开 → 建对象 + 幂等的 [SideIndexHandle.applyLook]（增量的增删/改尺寸）；
     *  - 已经开着 → 直接 `applyLook`，只改与参数有关的那部分。
     *
     * [myGeneration] 用来挡「post 出去之后这一代已经被卸载/重装」：
     * 不挡的话，给旧句柄的窗口操作会在 `release()` 之后跑到，
     * 于是那个把手会被重新挂出来且**没有任何字段引用它**（再也拆不掉）。
     */
    private fun applyHandle(appContext: Context, cfg: SideIndexConfig, myGeneration: Int) {
        if (!cfg.showHandle) {
            val h = handle
            handle = null
            // release 自己会判断线程（不在主线程就转投主线程）
            h?.release()
            return
        }
        val h = handle ?: SideIndexHandle(appContext).also { handle = it }
        mainHandler.post {
            if (myGeneration != generation) return@post
            // 句柄换过手（`show_handle` 被连着切过）就放弃：这个任务操作的是已经
            // 被 release 掉的那个句柄，跑下去会在一条没人引用的窗口上重建 View。
            if (handle !== h) return@post
            // 读**当前**配置而不是 post 时那一份：滑杆拖起来会连着来几十次，
            // 各读各的就会排出几十次 WindowManager 往返，而它们最后都被最后一次盖掉。
            // 读当前值之后，中间那些任务会因为「几何没变」直接跳过（见 computeGeometry）。
            val cur = config ?: return@post
            try {
                h.applyLook(
                    allowLeft = cur.allowLeft,
                    allowRight = cur.allowRight,
                    // 宽度就是判定带宽（trigger_width）—— 看得见的把手刚好覆盖有效热区
                    widthDp = cur.triggerWidthDp,
                    heightPercent = cur.look.handleHeightPercent,
                    offsetPercent = cur.look.handleOffsetPercent,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "侧滑索引：更新边缘把手失败（功能本身不受影响）", t)
            }
        }
    }

    /**
     * 参数一改就热应用 —— **不重启 SystemUI**。
     *
     * 为什么必须显式注册：框架的 `HookRegistry.registerHotApply` 只监听 `f.` 开头的
     * **特性开关**，参数键（`side_index.*`）在那条路上没有任何落点。而设置界面上是即时滑杆，
     * 用户拖完看不到变化，会当成「这个参数没用」—— 这是会直接影响调参体验的缺陷。
     * 同一个理由与同一个结构见 `CornerSmallWindow.watchPrefs`。
     *
     * 回调**可能在 Binder 线程上**（RemotePreferences 的变更通知走框架），而重读配置要读
     * `displayMetrics`、动 View 只能在主线程，所以一律转投主线程再动配置与视图。
     *
     * 注册失败**不是错误**：记一条 WARN，功能本身照常（只是参数仍然要重装才生效）——
     * 但这条 WARN 必须留下，否则「拖了没反应」会被归到 ROM 或框架头上。
     */
    private fun watchPrefs(ctx: HookContext, myGeneration: Int) {
        val store = ctx.store ?: return
        val prefix = spec.key + "."
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 只认自己这一组键：`f.*` 是特性开关，由 HookRegistry 管；
            // 别的特性（corner_small_window.* 等）有它们自己的监听。
            if (key == null || !key.startsWith(prefix)) return@OnSharedPreferenceChangeListener
            mainHandler.post {
                if (myGeneration != generation) return@post
                val app = SystemUiEnv.context() ?: return@post
                try {
                    val next = readConfig(ctx)
                    applyConfig(ctx, app, next)
                    ctx.log(Log.INFO, "侧滑索引：参数已热应用（$key）→ ${next.describe()}")
                } catch (t: Throwable) {
                    // 主线程任务里逃出去的异常就是 SystemUI 崩溃，比输入线程抛出更严重
                    ctx.log(Log.ERROR, "侧滑索引：热应用参数失败（$key）", t)
                }
            }
        }
        val ok = try {
            store.registerOnSharedPreferenceChangeListener(listener)
            true
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "侧滑索引：注册参数变更监听失败，参数改动仍需重启 SystemUI 才生效", t)
            false
        }
        if (ok) {
            prefStore = store
            prefListener = listener
        }
    }

    /** 注销参数监听。[teardown] 调，重复调安全。 */
    private fun stopWatchPrefs() {
        val l = prefListener
        val s = prefStore
        prefListener = null
        prefStore = null
        if (l == null || s == null) return
        // 已经注销过、或存储已失效时会抛，那是正常情况（清理不允许因此中断）
        runCatching { s.unregisterOnSharedPreferenceChangeListener(l) }
    }

    // ── 生效范围轮询（主线程） ─────────────────────────────────────────

    /**
     * 开始 250ms 一次的生效范围轮询。**主线程**。
     *
     * 这个 Runnable 用 `object` 而不是 lambda，是因为它要**把自己重新排进队列**
     * （`postDelayed(this, ...)`），lambda 里拿不到自身引用。
     *
     * 每次安装新建一个（它捕获本代的 ctx），[teardown] 用字段里的引用
     * `removeCallbacks`。`scopePolling` 那道闸门是给一种窄竞态兜底的：卸载恰好发生在
     * 这个 runnable 正在执行时，`removeCallbacks` 摘不掉已经在跑的那个，它会把自己
     * 再排一次 —— 下一轮看到 `scopePolling == false` 就停下，最多多跑一次。
     *
     * **生效范围读的是 [currentScope] 字段而不是捕获进闭包**：这样改 `scope` 只要改那个
     * 字段，下一轮（≤250ms）就按新档位判 —— 不用重建 Runnable，也就不会和
     * 「刚排进去的那一轮」打架。这是 `scope` 能热改的全部实现。
     */
    private fun startScopePolling(ctx: HookContext) {
        // 初始值照 Appindex 的 `startForegroundAppScopeMonitoring`：
        // `scopeAllowsEdgeTrigger = !enabledScopePackages.isEmpty()` —— 三档 scope 对应的
        // 白名单都非空，所以起点是「放行」。**但这个初值几乎立刻会被第一次轮询改写**：
        // 轮询是 post 出去的（不是 postDelayed），而它拿到 null 前台读数时会按
        // Appindex `scopeAllowsForegroundApp` 的规矩判成不放行。
        // 设备实测：SystemUI 重启后 `getTopVisibleTask` 会有几秒到二十秒给不出包名
        // （见 applyScopeGate 里那条一次性 warning），这段时间门控是关的 —— 如实记录，不粉饰。
        scopeAllowed = true
        warnedNoForeground = false
        lastForeground = null
        val poller = object : Runnable {
            override fun run() {
                try {
                    applyScopeGate(currentScope, SystemUiEnv.ForegroundTracker.foreground(), ctx)
                } catch (t: Throwable) {
                    // 主线程任务里逃出去的异常就是 SystemUI 崩溃；判定失败当作「不放行」
                    scopeAllowed = false
                    ctx.log(Log.WARN, "侧滑索引：生效范围判定失败，本次不放行", t)
                }
                if (scopePolling) mainHandler.postDelayed(this, SCOPE_POLL_MS)
            }
        }
        scopePoller = poller
        scopePolling = true
        // post 而不是 postDelayed：不然安装后的第一个 250ms 里手势会被「还没算出来」挡掉
        mainHandler.post(poller)
    }

    /**
     * 门控的落地，结构照 Appindex 的 `applyForegroundAppGate(foregroundPackage, allowed)`：
     * 记录前台包名与结论，**只在其中一个变化时**才去动把手可见性和打日志
     * （它也是 `if (packageChanged || gateChanged)`）。
     *
     * 两个细节逐条对应参考项目：
     *
     *  1. **过渡面不改结论**。`ForegroundAppGatePolicy.isTransientSystemSurface` ——
     *     HyperOS 在主屏桌面还没稳定时报上来的 `com.xiaomi.subscreencenter`
     *     （后置副屏的桌面）不是用户应用，绝不能因为它把边缘触发条收起来。
     *     设备实测本机在桌面时 `getTopVisibleTask` 就报这个包，所以这一条不是纸上功夫。
     *  2. 拿不到前台包名 → 不放行（Appindex `scopeAllowsForegroundApp` 的 `return false`）。
     *
     * 只在主线程调（轮询里）。
     */
    private fun applyScopeGate(scope: String, foreground: String?, ctx: HookContext) {
        if (SideIndexScope.isTransientSystemSurface(foreground)) return
        // 抽屉门控（需求 #8）：抽屉开着时把把手也收起来 —— 「看得见的把手」和
        // 「滑得动的热区」必须由同一个判定驱动，否则会出现「把手在抽屉里亮着、
        // 但划它没反应」（比两个都不给更让人困惑）。
        // 输入线程另有 [SideIndexDrawerGate.blocks] 做零延迟的那一份判定（见那里的注释）。
        val drawer = SideIndexDrawerGate.blocks
        // 本批 1️⃣：`launcherPackages` 是**用户配的那几个包**（空集 = 没配过，
        // 由 SideIndexScope 退回系统解析出来的桌面）。轮询每跑一次读一次这个字段，
        // 所以改配置只要换字段就生效 —— 与 scope 同一条路。
        val allowed = SideIndexScope.allows(scope, foreground, launcherPackages) && !drawer
        if (foreground == null && !warnedNoForeground) {
            // 「前台追踪器一直没有读数」是**静默失效**的典型来源：它会让生效范围
            // 恒为不放行，表现出来就是「这个特性完全没反应」，而日志里一条都没有。
            // 按原则给一条一次性的 warning（只有这一条，不进高频路径）。
            warnedNoForeground = true
            ctx.log(
                Log.WARN,
                "侧滑索引：前台追踪器读不到前台应用（ForegroundTracker.foreground() 一直为 null），" +
                    "生效范围将一直判定为不放行 —— 请检查 SystemUiEnv.foregroundPackage() 的反射调用",
            )
        }
        val gateChanged = allowed != scopeAllowed
        val packageChanged = foreground != lastForeground
        val drawerChanged = drawer != lastDrawerBlocked
        scopeAllowed = allowed
        // 边缘优先只在桌面态：冲突来自桌面自己的「上滑开抽屉」，见 [edgePriority] 的注释
        edgePriority = config?.scope == "launcher_only"
        lastDrawerBlocked = drawer
        if (!packageChanged && !gateChanged && !drawerChanged) return
        lastForeground = foreground
        handle?.setScopeAllowed(allowed)
        ctx.log(
            Log.INFO,
            "侧滑索引前台应用范围：fg=$foreground allowed=$allowed scope=$scope" +
                // 打**生效集合**：用户「明明勾了 Niagara 却不生效」时，这一行是唯一
                // 能看出「他勾的是不是 Niagara」的地方（与 auto 那两档也分得开）。
                " launchers=" + SideIndexScope.describeLaunchers(launcherPackages) +
                " drawer=" + if (drawer) "open(抑制)" else "closed",
        )
    }

    /**
     * 探测系统是否开着「跨窗口模糊」。逐行对应 Appindex 的
     * `FloatingService.configureBlurBehavior()`：
     *
     * ```java
     * if (Build.VERSION.SDK_INT >= 31) blurBehindEnabled = windowManager.isCrossWindowBlurEnabled();
     * else blurBehindEnabled = false;
     * ```
     *
     * 本模块 minSdk = 36（那个版本判断恒真），所以省掉判断而**保留调用本身** ——
     * 真正的判据是这次调用，不是版本号。
     *
     * 为什么可以在 install 里同步探：它是能力探测（每次安装一次），不是高频路径；
     * 而且结果要进安装报告。任何失败都当「不支持」—— 模糊没有就没有，
     * 浮层照常显示（静默降级）。
     */
    private fun probeCrossWindowBlur(context: Context): Boolean = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.isCrossWindowBlurEnabled() ?: false
    }.getOrDefault(false)

    // ── 索引发布（主线程） ─────────────────────────────────────────────

    /**
     * 后台构建完成。**三态在这里落地**：
     *
     * | 结果 | 浮层怎么办 | 为什么 |
     * | --- | --- | --- |
     * | [AppIndex.Result.Ready] | 换成真实字母表（多一个「最近使用」槽） | 正常 |
     * | [AppIndex.Result.Empty] | 保留安装时发布的 `ALPHABET`，面板显示「没有可启动的应用」 | 「没有」要看得见，不能和「坏了」一样什么都不显示 |
     * | [AppIndex.Result.Unavailable] | [SideIndexOverlay.clearLayout] 收回几何 | 「不知道」不能装成「没有」：没有几何 = 手势完全不响应，和没装模块一致 |
     *
     * 「扫完之后到底算哪一态」必须能被用户看出来，否则一个恒不生效的特性
     * 和「本来就没有应用」在行为上分不开 —— 这正是三态存在的意义。
     */
    private fun onIndexBuilt(ctx: HookContext, myGeneration: Int, result: AppIndex.Result) {
        if (myGeneration != generation) {
            // 这一代已经被卸载/重装了，晚到的结果直接丢
            ctx.log(Log.INFO, "侧滑索引：丢弃过期结果（" + AppIndex.describe(result) + "）")
            return
        }
        lastIndexResult = AppIndex.describe(result)
        // 主线程任务里的异常同样不能逃
        try {
            when (result) {
                is AppIndex.Result.Ready -> {
                    baseIndex = result.index
                    overlay?.setPlaceholder(SideIndexOverlay.PanelPlaceholder.NONE)
                    publishIndex(force = true)
                    startRecentSampling()
                    // 清单就绪 → 排一次后台逐应用图标预解析（见 scheduleIconSweep）。
                    // 位置必须在这里：它要拿 baseIndex，而上面那行刚把它设上。
                    scheduleIconSweep()
                }
                is AppIndex.Result.Empty -> {
                    // 字母表不换（安装时发的 ALPHABET 就是可用的），只把面板切到空态
                    overlay?.setPlaceholder(SideIndexOverlay.PanelPlaceholder.EMPTY)
                }
                is AppIndex.Result.Unavailable -> {
                    // 连几何都收回：这一类必须和「没有应用」分得开
                    overlay?.clearLayout()
                }
                is AppIndex.Result.Loading -> Unit
            }
        } catch (t: Throwable) {
            Log.e(TAG, "侧滑索引：发布索引失败", t)
        }
        ctx.log(Log.INFO, "侧滑索引：应用清单 " + AppIndex.describe(result))
        // 本批 2️⃣：过滤是**在发布那一刻**叠加的，所以「扫到几个」与「面板上有几个」
        // 是两个数。只打前者时，「我筛掉了 180 个、面板上只剩 2 个」与「过滤没生效」
        // 在日志里长得一样 —— 这一行把它们分开（只在过滤真的在管事时才打）。
        if (result is AppIndex.Result.Ready &&
            IndexAppFilter.isActive(indexFilterMode, indexFilterPackages)
        ) {
            ctx.log(
                Log.INFO,
                "侧滑索引：索引过滤=$indexFilterMode(${indexFilterPackages.size}条) " +
                    "生效后剩 ${result.index.filtered(indexFilterMode, indexFilterPackages).byPackage.size} 个应用",
            )
        }
    }

    /**
     * 合成并发布快照。主线程。
     *
     * 发布的内容有两样：浮层要画的字母表，以及输入线程要用的几何
     * （[SideIndexOverlay.publishLayout]）。两者必须来自同一份字母表 ——
     * 否则「碰到的字母」和「看到的字母」会错位。
     *
     * 快照 = 基础索引 + **两个特殊槽**（收藏项 `♥`、最近使用 `★`）。
     * 两个槽都是从**内存里已有的** `byPackage` 查表得来（`AppIndex.Index.withSpecial`），
     * 所以这一条路既不需要重扫应用清单，也不需要读 prefs —— 这正是
     * 「收藏增删 / 最近使用变化能热生效」的代价来源。
     *
     * **手势进行中只记 dirty、不发布**：输入线程在 ACTION_DOWN 就把当时的
     * 字母表抄进了自己的几何快照，中途换字母表会出现「画的是新的条、判定按旧的条」。
     * 等手势结束（[flushPendingLetters]）再补发。这一条原来只覆盖「最近使用」，
     * 现在「清单第一次扫完」「收藏项变化」也走同一条路（它是同一类问题）。
     */
    private fun publishIndex(force: Boolean) {
        val base = baseIndex ?: return
        // 2️⃣ **先过滤、再挂特殊槽**（顺序不能反）：收藏项与最近使用是按包名查
        // `byPackage` 换成条目的（`Index.entriesFor`），所以过滤必须发生在
        // **那份 byPackage 被查之前** —— 否则被用户排除的应用仍然会出现在
        // 收藏槽或 ★ 槽里，用户看到的就是「我明明排除了它，它还在」。
        val filtered = filtered(base)
        // 过滤之后一个可启动应用都不剩 → 与「扫完但没有应用」是**同一态**：
        // 字母表照旧（安装时发布的 ALPHABET 就是可用的）、面板显示「没有可启动应用」。
        // 不能发布一份**空字母表** —— 那样输入线程手上会有一份「有几何、一个字母都没有」
        // 的快照，滑了没反应而且看不出是为什么（见 onIndexBuilt 的三态表）。
        if (filtered.letters.isEmpty()) {
            appliedIndex = null
            // 已经是「没有可启动应用」那一态：不重发几何。这条路径会被「最近使用变化」
            // 每秒叫一次（用户每切一个应用一次），而这一态下没有任何东西会变。
            if (appliedEmpty) return
            if (policy?.gestureActive == true) {
                lettersDirty = true
                return
            }
            appliedEmpty = true
            overlay?.setPlaceholder(SideIndexOverlay.PanelPlaceholder.EMPTY)
            overlay?.publishLayout(AppIndex.ALPHABET)
            return
        }
        appliedEmpty = false
        // 收藏项从**当前配置**现读（不另存一份字段）：`config` 已经是参数的唯一来源，
        // 再抄一份就多出一个会漂移的事实（本文件顶部那条「只允许 readConfig 一处读 prefs」
        // 是同一个理由）。这里是一次内存读，没有 prefs 访问、没有 IPC。
        val favorites = config?.favorites ?: emptyList()
        val recent = if (recentEnabled) recents?.current ?: emptyList() else emptyList()
        // 两个都是空时 `withSpecial` 会返回 base 自己（引用相等），所以这里不必先判空 ——
        // 「没配收藏、没开最近使用」时这一次调用是零成本的，行为与改动前逐位一致。
        val applied = filtered.withSpecial(recent = recent, favorite = favorites)
        if (!force && applied === appliedIndex) return
        appliedIndex = applied
        if (policy?.gestureActive == true) {
            lettersDirty = true
            return
        }
        // 过滤把上一次的「没有可启动应用」撤掉了（用户把清单改回来了）→ 占位态要复原
        overlay?.setPlaceholder(SideIndexOverlay.PanelPlaceholder.NONE)
        overlay?.publishLayout(applied.letters)
    }

    /**
     * 过滤后的基础快照（本批 2️⃣）。**主线程**（[publishIndex] 的唯一调用者）。
     *
     * 缓存命中判据是「同一份基础快照 + 同一份过滤配置」：过滤本身是纯内存的，
     * 但 [publishIndex] 会被「最近使用变化」这类高频事件触发（用户每切一个应用一次），
     * 而过滤结果在两次配置变化之间不变。
     *
     * 默认配置（`all` + 空集）下 [AppIndex.Index.filtered] 直接返回它自己，
     * 所以这一层在关态路径上是三次比较 + 一次赋值，**零分配**。
     */
    private fun filtered(base: AppIndex.Index): AppIndex.Index {
        val key = filterCacheKey()
        val cached = filteredIndex
        if (cached != null && filteredFrom === base && filteredKey == key) return cached
        val out = base.filtered(indexFilterMode, indexFilterPackages)
        filteredIndex = out
        filteredFrom = base
        filteredKey = key
        return out
    }

    /** 过滤配置的缓存键（归一化后的两个值，拼接即可 —— 顺序用排序固定）。 */
    private fun filterCacheKey(): String =
        indexFilterMode + '\u0000' + indexFilterPackages.sorted().joinToString("\u0000")

    /** 让过滤缓存失效。配置变化与重新安装时都要调（只改配置不改键 = 命中过期缓存）。 */
    private fun invalidateFilterCache() {
        filteredIndex = null
        filteredFrom = null
        filteredKey = ""
        appliedEmpty = false
    }

    private fun startRecentSampling() {
        if (!recentEnabled || recents != null) return
        val appContext = SystemUiEnv.context() ?: return
        val tracker = RecentApps(
            appContext = appContext,
            // 读**当前**配置：这个函数在安装与开关翻转两条路上被调，
            // 拿安装时那一份会把后来拖过的数量丢掉（数量之后的变化走 applyDisplayCount，
            // 不重建采样器 —— 理由在那个字段的注释里）
            initialDisplayCount = config?.recentCount ?: SideIndexConfig.DEFAULT_RECENT_COUNT,
            isLaunchable = { pkg -> baseIndex?.byPackage?.containsKey(pkg) == true },
            // 采样线程只负责叫一声，真正的合成在主线程做
            onChanged = { mainHandler.post { onRecentsChanged() } },
        )
        recents = tracker
        tracker.start()
    }

    private fun onRecentsChanged() {
        try {
            publishIndex(force = true)
        } catch (t: Throwable) {
            Log.e(TAG, "侧滑索引：更新最近使用失败", t)
        }
    }

    /** 手势结束后把欠着的字母表发布补上（最近使用 / 清单扫完都走这里）。 */
    private fun flushPendingLetters() {
        if (!lettersDirty) return
        lettersDirty = false
        publishIndex(force = true)
    }

    // ── 应用清单变化的监听（Appindex 的 packageChangeReceiver） ─────────

    /**
     * 监听**应用清单变化**，变化时重建索引。
     *
     * 逐条对应 Appindex 的 `FloatingService`：
     *
     * ```java
     * filter.addAction(Intent.ACTION_PACKAGE_ADDED / REMOVED / CHANGED / REPLACED);
     * filter.addDataScheme("package");        // 不写这个收不到包相关的广播
     * registerReceiver(packageChangeReceiver, filter);
     * // onReceive → appListAdapter.refreshAsync(...)  （PackageRefreshPolicy.isPackageListAction）
     * ```
     *
     * 没有这一条，用户在特性开着的时候装/卸一个应用，面板里就一直是旧清单 ——
     * 直到他重开一次特性。参考项目把它当基础功能，我们也必须有。
     *
     * 重建**去重**：连续装几个应用会连着来好几条广播，用一个「已排队」标记把它们
     * 合成一次重建（Appindex 的 `AtomicBoolean refreshRequested / refreshWorkerScheduled`
     * 是同一个思路）。重建仍然跑在同一个单线程 executor 上，所以不会并发。
     *
     * 注册失败（SystemUI 里被限制、或 ROM 改了广播行为）**不是错误**：
     * 记一条日志，索引退化成「只在安装时扫一次」，功能本身照常。
     */
    private fun registerPackageWatcher(ctx: HookContext, appContext: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // 包相关广播**必须**带 data scheme，否则一条都收不到
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 广播回调跑在主线程；任何异常都不能逃出去（那就是 SystemUI 崩溃）
                try {
                    // 广播里带着**是哪个包**变了（data scheme = package）。以前这里把它丢掉了，
                    // 于是每次变化都整表重扫；现在拿它做增量：只重载那一个包的名称与图标。
                    // 取不到（data 为空 / 解析失败）时退化成全失效 —— 见 IndexReuse 的理由。
                    requestIndexRefresh(ctx, intent?.data?.schemeSpecificPart)
                } catch (t: Throwable) {
                    ctx.log(Log.WARN, "侧滑索引：包变化后重建索引的请求失败", t)
                }
            }
        }
        val ok = try {
            // RECEIVER_NOT_EXPORTED：只收系统发的（包广播就是系统发的），
            // 别的应用没法用它给我们塞事件
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            true
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "侧滑索引：注册不到包变化广播，索引只在安装时扫一次", t)
            false
        }
        if (!ok) {
            runCatching { appContext.unregisterReceiver(receiver) }
            return
        }
        packageWatcher = receiver

        // ── 语言 / 区域变化：**必须整表失效** ────────────────────────────────
        // 不能挂在上面那个 filter 上：那个 filter 声明了 `addDataScheme("package")`，
        // 而 LOCALE_CHANGED 没有 data，会被它挡掉（这正是「包广播必须带 scheme」的同一枚硬币）。
        val localeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    // 传 null = 拿不到具体包名 → 退化成全失效（见 IndexReuse）
                    requestIndexRefresh(ctx, null)
                } catch (t: Throwable) {
                    ctx.log(Log.WARN, "侧滑索引：语言变化后重建索引的请求失败", t)
                }
            }
        }
        val localeOk = runCatching {
            appContext.registerReceiver(
                localeReceiver,
                IntentFilter(Intent.ACTION_LOCALE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        if (localeOk) {
            localeWatcher = localeReceiver
        } else {
            // 注册不到就只影响「换语言后名字不更新」这一种情况，说清代价而不是静默
            ctx.log(Log.WARN, "侧滑索引：注册不到语言变化广播（换语言后应用名可能不更新）")
        }
    }

    private fun stopPackageWatcher() {
        val ctx = SystemUiEnv.context()
        // 两条一起摘：它们同属「外部世界变了 → 重建索引」这一条通路。
        // ⚠️ 不能因为第一条为 null 就提前 return —— 那会漏掉第二条。
        packageWatcher?.let { packageWatcher = null; runCatching { ctx?.unregisterReceiver(it) } }
        localeWatcher?.let { localeWatcher = null; runCatching { ctx?.unregisterReceiver(it) } }
    }

    /**
     * 请求重建索引。**主线程**（广播回调）。去重后交给 [indexExecutor]。
     *
     * 用一个「已排队」标记合并连发：`refreshPending` 是请求，
     * `refreshScheduled` 是「已经有一个任务在跑」。任务跑完再看 `refreshPending`
     * 决定要不要再扫一遍（Appindex 的 worker 是同一个循环）。
     */
    private fun requestIndexRefresh(ctx: HookContext, packageName: String?) {
        // 先把「变了哪些包」记下来 —— 必须在判断 refreshScheduled 之前，
        // 否则「正在跑的时候又来一条」就会被丢掉（那正是批量更新时的常态）。
        mergeInvalidation(packageName)
        refreshPending = true
        if (refreshScheduled) return
        val executor = indexExecutor ?: return
        refreshScheduled = true
        val appContext = SystemUiEnv.context() ?: return
        val myGeneration = generation
        try {
            executor.execute {
                while (refreshPending) {
                    refreshPending = false
                    // 复用源 = 上一次发布出来的基础索引（不含「最近使用」，那是视图层拼的）
                    buildAndPublish(ctx, appContext, myGeneration, baseIndex, takeInvalidation())
                }
                refreshScheduled = false
            }
        } catch (t: Throwable) {
            // shutdownNow 之后提交会抛 RejectedExecutionException —— 卸载竞态，正常
            refreshScheduled = false
            ctx.log(Log.INFO, "侧滑索引：重建索引任务提交失败（多半是正在卸载）")
        }
    }

    // ── 诊断 ──────────────────────────────────────────────────────────

    /** 诊断用：最近一次触发的字母。由输入线程写（只写引用，不分配）。 */
    @Volatile
    var lastLetter: String? = null
        internal set

    /** 诊断用：最近一次启动的包名。 */
    @Volatile
    var lastLaunched: String? = null
        internal set

    /**
     * 诊断用：因为「不在生效范围」或「抽屉开着」而被忽略的手势次数。唯一写者是输入线程。
     *
     * 两类原因共用一个计数器是刻意的：它们的**观察方式**完全一样（划了一下没反应，
     * 日志里没有别的东西），分开计数只会多两个没人看的字段。要区分就看
     * `applyScopeGate` 那条日志里的 `drawer=` 与 `allowed=`。
     */
    @Volatile
    var skippedOutOfScope: Long = 0

    /** 诊断用：应用清单扫描的结果（三态 + 槽位数）。 */
    @Volatile
    var lastIndexResult: String = "loading"
        private set

    /**
     * 按参数名取配置描述。
     *
     * 用真实的 `Keys.pref` 拼键而不是手写字面量 —— 键名写错在编译期发现不了，
     * 运行时只表现为「这个设置项没反应」，是最难查的一类 bug。
     *
     * **缺项会抛**：调用点必须是「Catalog 里一定有」的那些键（安装报告的 notes 会写出来）。
     */
    private inline fun <reified T : PrefSpec> prefSpec(name: String): T =
        spec.prefs.filterIsInstance<T>().first { it.key == Keys.pref(spec.key, name) }

    /**
     * 个性化参数的容错读法。[SideIndexLook] 那几个键**可能还没在 core/Catalog 里声明**
     * （那一块由协作者统一加），所以不能像 [prefSpec] 那样「缺了就抛」——
     * 抛在 install 里就是「打开这个特性直接没反应」，那是比缺一个滑块严重得多的故障。
     *
     * 规则：
     *  - 声明了 → 读它的值（键名仍然用 [Keys.pref] 拼，不手写字面量）；
     *  - 没声明 → 用 [SideIndexLook] 的默认值，并把这个键名记下来，
     *    由 [notes] 写进**安装报告**。缺项必须是看得见的，不能静默 ——
     *    否则会出现「界面上没有这个滑块，代码里却按默认值跑」这种没人能发现的偏差。
     */
    private class LookReader(private val ctx: HookContext) {

        private val missing = ArrayList<String>(8)

        fun slider(name: String, fallback: Float, min: Float, max: Float): Float {
            val s = find<PrefSpec.Slider>(name)
            if (s == null) {
                missing.add(name)
                return fallback
            }
            return ctx.prefs.value(s).coerceIn(min.coerceAtMost(max), max.coerceAtLeast(min))
        }

        fun toggle(name: String, fallback: Boolean): Boolean {
            val t = find<PrefSpec.Toggle>(name)
            if (t == null) {
                missing.add(name)
                return fallback
            }
            return ctx.prefs.value(t)
        }

        /**
         * 容错读一个**整数**滑杆（需求 #5c 的列数）。
         *
         * 读法与 [slider] 完全一样（键没声明就用 [fallback] 并把键名记进 [missing]），
         * 只是把值四舍五入成整数 —— 「每行几格」按小数读没有意义，而截断会让
         * 6.9 变成 6（同一档却少一列，只能靠数格子发现）。
         */
        fun intSlider(name: String, fallback: Int, min: Int, max: Int): Int {
            val raw = slider(name, fallback.toFloat(), min.toFloat(), max.toFloat())
            if (!raw.isFinite()) return fallback.coerceIn(min.coerceAtMost(max), max.coerceAtLeast(min))
            return Math.round(raw).coerceIn(min.coerceAtMost(max), max.coerceAtLeast(min))
        }

        /**
         * 容错读一个**单选**项，语义与 [slider] / [toggle] 一致：
         * 键还没在 `Catalog` 里声明就用 [fallback]，并把键名记进 [missing]。
         *
         * 值本身不做校验 —— 合法取值由消费方（例如 [SideIndexHaptics.resolveMode]）判定，
         * 它必须能处理「读到一个不认识的字符串」这种情况。
         */
        fun choice(name: String, fallback: String): String {
            val c = find<PrefSpec.Choice>(name)
            if (c == null) {
                missing.add(name)
                return fallback
            }
            return ctx.prefs.value(c)
        }

        /**
         * 容错读 `icon_pack`（需求 #5）。
         *
         * ## 为什么用 `rawStringSet` 而不是 `value(spec)`
         *
         * 界面侧（`IconPackPickerScreen` / `ui/state/ShortcutCatalog.iconPackKey`）与
         * 后台侧（`FanTargets`）读的是**同一个键** `Keys.pref(<特性>, "icon_pack")`，
         * 与「Catalog 里声明成什么类型」无关。所以这里也按**键**读：
         * 声明了当然好，没声明（接线还没落地）时同样能读到用户已经选过的值 ——
         * 否则会出现「选择页里选好了、面板上就是不生效」这种最难查的一类偏差。
         *
         * ## 取值规则：集合里取**字典序最小**的那个
         *
         * 与界面侧逐字一致（`IconPackPickerScreen`：`selected.sorted().firstOrNull()`；
         * hook 侧 `FanTargets.configuredIconPack` 同此）。集合里正常只有一个元素，
         * 多个时取最小而不是「静默丢掉剩下的」—— 取第一个的规则写在这里，可核对。
         *
         * ## 为什么还要兜「存成单个字符串」这一种
         *
         * Catalog 里这一条**还没声明**（由协作者统一接线），而本项目的两种清单类型
         * 存储形状不同：`PrefSpec.AppList` 是 `StringSet`（图标包选择页
         * `IconPackPickerScreen` 读的就是 `rawStringSet`），`PrefSpec.OrderedList`
         * 是**一条以 `\n` 连接的单个字符串**（见 `OrderedItems`）。声明成后者时
         * `rawStringSet` 会因为类型不符**静默返回空集**（`Prefs.safe` 把
         * `ClassCastException` 吃掉了），表现是「选择页里选好了、面板上永远不生效」——
         * 正是本项目最忌讳的那类偏差。所以这里两种形状都读：
         * 先按 `StringSet`，空就再按单字符串按 `\n` / `,` 拆。
         *
         * @return 包名；没配 / 读到空集 / 读到脏数据时 null（= 不套用，退回自带图标）
         */
        fun iconPack(): String? {
            // 声明缺失要记进报告，但**不影响读取** —— 两条路都从同一个键拿值。
            if (find<PrefSpec.AppList>("icon_pack") == null &&
                find<PrefSpec.OrderedList>("icon_pack") == null
            ) {
                missing.add("icon_pack")
            }
            val key = Keys.pref(spec.key, "icon_pack")
            val candidates = ArrayList<String>(4)
            // 形状 1：StringSet（AppList / 选择页写的）
            runCatching { ctx.prefs.rawStringSet(key) }
                .getOrDefault(emptySet())
                .forEach { candidates.add(it ?: "") }
            if (candidates.all { it.isBlank() }) {
                // 形状 2：单个字符串（OrderedList，一条一行）
                runCatching { ctx.prefs.rawString(key, null) }
                    .getOrNull()
                    ?.split('\n', ',')
                    ?.forEach { candidates.add(it) }
            }
            return candidates.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .minOrNull()
        }

        /** 安装报告的补充说明：列出「代码支持、但 Catalog 还没声明」的键。 */
        fun notes(): List<String> =
            if (missing.isEmpty()) {
                emptyList()
            } else {
                listOf("look 未声明(用默认值)=" + missing.joinToString(","))
            }

        // ── 内容参数──────────────────────
        //
        // 与上面几个 look 读法的区别只有一个：这几条的说明**逐条**写进 contentNotes
        // （而不是攒一串键名再拼一句）。它们要报的事情不是同一类 ——
        // 「键没声明」「清单是无序形状」「有条目被跳过」的排查方向完全不同，
        // 拼成一行会把三种情况说成一句没法行动的话。

        /** 内容参数的说明，进安装报告（[SideIndexConfig.contentNotes]）。 */
        private val noteLines = ArrayList<String>(4)

        fun contentNotes(): List<String> = noteLines.toList()

        /**
         * 容错读一条**有序清单**（`PrefSpec.OrderedList`，收藏项）。
         *
         * ## 为什么不像 `prefSpec<T>` 那样「缺了就抛」
         *
         * 同一个理由写在 [iconPack] 里：这个键的声明由协作者统一接线，
         * 而抛在 `install` 里就是「打开这个特性直接没反应」——
         * 比「少一个滑块」严重得多。
         *
         * ## 两种存储形状都读
         *
         * `OrderedList` 是**一条一行的单个字符串**（`OrderedItems`），
         * 而 `AppList` 是 `StringSet`。读取方按**键**读、不看声明类型，所以两种都要认 ——
         * 只认一种时，声明被写成另一种形状会让读取**静默返回空**
         * （`Prefs.safe` 把 `ClassCastException` 吃掉了），表现是
         * 「用户在清单页排好了，面板上永远没有收藏槽」。
         *
         * 无序形状只能给一个**确定**的顺序（按字符串排序），并且**要报出来**：
         * 那份顺序不是用户排的，用户看到的排列会和他拖的对不上。
         */
        fun orderList(name: String): List<String> {
            if (find<PrefSpec.OrderedList>(name) == null) {
                noteLines.add("$name 未声明(用默认值)")
            }
            val key = Keys.pref(spec.key, name)
            // 形状 1：单个字符串（OrderedList 的规范存储，一条一行、顺序即原样）
            val ordered = OrderedItems.decode(
                runCatching { ctx.prefs.rawString(key, null) }.getOrNull(),
            )
            if (ordered.isNotEmpty()) return ordered
            // 形状 2：StringSet（键被声明/写成 AppList 时）。顺序在这里**已经丢了**，
            // 给一个确定但非用户排的序，并把这件事报出去。
            val unordered = runCatching { ctx.prefs.rawStringSet(key) }
                .getOrDefault(emptySet())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (unordered.isNotEmpty()) {
                noteLines.add("$name 按无序集合读到 ${unordered.size} 条(顺序非用户排序)")
                return unordered.sorted()
            }
            return emptyList()
        }

        /**
         * 容错读一个**整数档位**（最近使用数量），并夹进 `[min, max]`。
         *
         * 读法与 [slider] 完全一样（键没声明就用 `fallback`，只把键名记进 [contentNotes]），
         * 只是返回整数：界面上它是滑杆（存的是浮点），而「显示几个」按小数读没有意义。
         * 用 `Math.round` 而不是截断：4.9 截成 4 会让「同一档却少一格」，
         * 那是一个只能靠数格子发现的偏差。
         */
        fun count(name: String, fallback: Int, min: Int, max: Int): Int {
            val s = find<PrefSpec.Slider>(name)
            if (s == null) {
                noteLines.add("$name 未声明(用默认值 $fallback)")
                return fallback.coerceIn(min.coerceAtMost(max), max.coerceAtLeast(min))
            }
            val raw = ctx.prefs.value(s)
            // 非有限值（NaN/Inf）在滑杆上取不到，但配置可以被手改；退回默认值 ——
            // `Float.NaN.toInt()` 是 0，那会变成「一格都不显示」，且没有任何报错
            if (!raw.isFinite()) return fallback.coerceIn(min, max)
            return Math.round(raw).coerceIn(min.coerceAtMost(max), max.coerceAtLeast(min))
        }

        private inline fun <reified T : PrefSpec> find(name: String): T? =
            spec.prefs.filterIsInstance<T>().firstOrNull { it.key == Keys.pref(spec.key, name) }

        // ── 本批 1️⃣/2️⃣/3️⃣ 的容错读（理由见 readConfig 里那一段）──────────────

        /**
         * 这个键在 `Catalog` 里声明了吗。
         *
         * **只看键、不看类型** —— 与 [SideIndexDrawerGate.start] 里的 `declaredInCatalog`
         * 同一条纪律：声明成什么类型不是读取方要关心的事（[appList] 两种存储形状都认），
         * 这里只用来决定要不要报「未声明,用默认值」。
         *
         * 用「有没有这个键」而不是 `find<PrefSpec.Choice>` 之类的类型查询：接线的人
         * 把清单声明成 `AppList` 还是 `OrderedList` 都不该让报告里出现一句假警告
         * （一句假警告会让下一次排障从错误的方向开始）。
         */
        private fun declared(name: String): Boolean =
            spec.prefs.any { it.key == Keys.pref(spec.key, name) }

        /**
         * 容错读一个**应用多选清单**（`PrefSpec.AppList`，本批 1️⃣/2️⃣）。
         *
         * ## 两种存储形状都读
         *
         * `AppList` 是 `StringSet`、`OrderedList` 是「一条一行的单个字符串」
         * （`OrderedItems`）。读取方按**键**读、不看声明类型，所以两种都要认 ——
         * 只认一种时，声明被写成另一种形状会让读取**静默返回空**
         * （`Prefs.safe` 把 `ClassCastException` 吃掉了），表现是
         * 「界面上勾好了、面板上永远不生效」。这一条与 [iconPack] / [orderList]
         * 里那两段是同一个理由（那两处的注释写得更长）。
         *
         * 顺序对这两个参数都没有意义（过滤是「在不在集合里」、桌面是「包名匹配」），
         * 所以读到有序形状时只报一句说明、不假装顺序有用。
         */
        fun appList(name: String): Set<String> {
            if (!declared(name)) noteLines.add("$name 未声明(用默认值 空集)")
            val key = Keys.pref(spec.key, name)
            // 形状 1：StringSet（AppList 的规范存储，选择页写的就是它）
            val unordered = runCatching { ctx.prefs.rawStringSet(key) }
                .getOrDefault(emptySet())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (unordered.isNotEmpty()) return unordered.toSet()
            // 形状 2：单个字符串（OrderedList）
            val raw = runCatching { ctx.prefs.rawString(key, null) }.getOrNull() ?: return emptySet()
            val ordered = OrderedItems.decode(raw)
            if (ordered.isNotEmpty()) {
                noteLines.add("$name 按有序字符串读到 ${ordered.size} 条(顺序对多选无意义)")
            }
            return ordered.toSet()
        }

        /**
         * 容错读一个**内容参数的单选**（索引过滤的模式，本批 2️⃣）。
         *
         * 与 [choice] 的唯一差别是缺声明时记进 [contentNotes] 而不是 `missing` ——
         * 那条纪律写在这个类的字段注释里：「外观参数缺声明」和「内容参数缺声明」的
         * 排查方向不同，合成一行会让人去外观那一组里找一个其实是内容的键。
         */
        fun contentChoice(name: String, fallback: String): String {
            if (!declared(name)) noteLines.add("$name 未声明(用默认值 $fallback)")
            val key = Keys.pref(spec.key, name)
            return runCatching { ctx.prefs.rawString(key, fallback) }.getOrNull() ?: fallback
        }

        /**
         * 读**单应用显示名覆盖**（本批 3️⃣ 前半）的原始条目。
         *
         * 返回的是**原始字符串**（解析与清洗在 `core/DisplayNameOverrides`）：
         * 这段格式解析是可测的纯逻辑，不该再在 hook 里抄一份（抄的那份没有测试）。
         */
        fun nameOverrides(): List<String> {
            if (!declared("name_overrides")) {
                noteLines.add("name_overrides 未声明(用默认值 空集)")
            }
            val key = Keys.pref(spec.key, "name_overrides")
            return runCatching { ctx.prefs.rawStringSet(key) }
                .getOrDefault(emptySet())
                .toList()
        }
    }
}

/**
 * 「最近使用」的记录器。
 *
 * ## 为什么不用 Appindex 那套
 *
 * Appindex 用 `UsageStatsManager.queryEvents` 查最近的前台应用（它的
 * `queryForegroundPackage`），那要「使用情况访问」权限。在 SystemUI 里这个权限
 * 到底放不放行，我**没有在设备上验证**；而它失败的样子是「最近使用永远是空的」——
 * 一个静默失效的功能，比没有这个功能更糟。
 *
 * 这里改成自己采样：每秒读一次 [SystemUiEnv.ForegroundTracker.foreground]
 * （那是 500ms 轮询的 `@Volatile` 缓存，读它是纯内存操作，没有 IPC），
 * 记下最近出现过、且**确实有启动入口**的包。
 * 好处：零权限依赖，不存在静默失效。
 *
 * ## 持久化（本次新增）
 *
 * 上一版的代价写在注释里：「**没有跨重启的历史**，SystemUI 重启后列表从空开始」。
 * 这一版把它落盘 —— 落点是 [RecentStore]（本进程私有目录里的一个文本文件；
 * **为什么不是配置通道**写在那个类的注释里，一句话：hook 侧写不了远程配置）。
 *
 * 两条时机纪律（规范 §7.5「跨进程/IO 不能放在触摸路径上」）：
 *
 *  - **读盘**在 [start] 的采样线程上 —— 安装那条主线程路径不碰文件；
 *    触摸路径（`SideIndexDetector.onTouch` / `SideIndexOverlay.trackPointer`）从头到尾
 *    只读内存里的不可变快照，一次 prefs 读、一次包管理器查询、一次文件 IO 都没有。
 *  - **写盘**只在列表**真的变了**的时候发生（[record] 靠 `RecentList.push` 的
 *    引用相等判「没变」），并且也在采样线程上。用户一分钟切 60 次应用最多写 60 次、
 *    每次几百字节；一直在用同一个应用则一次都不写。
 *
 * 保留上限 = [SideIndexConfig.MAX_RECENT_COUNT]（= 数量滑杆的最大值），
 * **显示**数量 = `side_index.recent_count`。两者分开的理由见 `core/RecentList`。
 *
 * 采样线程是一个 `MIN_PRIORITY` 的 daemon，99.9% 的时间在 sleep。
 */
private class RecentApps(
    /** SystemUI 自己的 Context —— [RecentStore] 只用它拿 `filesDir`。 */
    private val appContext: Context,
    initialDisplayCount: Int,
    private val isLaunchable: (String) -> Boolean,
    private val onChanged: () -> Unit,
) {

    /**
     * 保留的历史（最新在前），上限 [SideIndexConfig.MAX_RECENT_COUNT]。
     * **只在 [lock] 下读写**。
     */
    private var retained: List<String> = emptyList()

    /**
     * `★` 槽里显示几个（`side_index.recent_count`）。
     *
     * **可变，而且不重建采样线程**：数量变化时由 [applyDisplayCount] 就地改它。
     * 为什么不重建（那是更直觉的做法）：重建会造出一个「旧线程正卡在写盘中途、
     * 新线程已经读完盘」的窗口，然后新线程的写盘会把旧线程刚写进去的那一条盖掉 ——
     * 窗口很窄，但那是**真的丢数据**，而这里根本不需要有那个窗口。
     * 读它的是采样线程（volatile 读，无锁、无 IPC）。
     */
    @Volatile
    private var displayCount: Int = initialDisplayCount

    /** 最近使用的包名，最新的在前。**主线程读**。 */
    @Volatile
    var current: List<String> = emptyList()
        private set

    @Volatile
    private var running = false

    private var thread: Thread? = null

    private var lastSeen: String? = null

    private val lock = Any()

    /**
     * 显示数量变了。**主线程**（[SideIndex.applyConfig] 那条路）。
     *
     * 只重算 [current] 的可见前缀，**保留的历史一条都不动** —— 所以把数量调大能立刻
     * 看到更多应用（历史本来就按 [SideIndexConfig.MAX_RECENT_COUNT] 留着）。
     * 这正是「保留 vs 显示」分开的意义，见 `core/RecentList` 的类注释。
     */
    fun applyDisplayCount(count: Int) {
        displayCount = count
        synchronized(lock) {
            current = RecentList.visible(retained, count)
        }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            // ① 先从磁盘恢复。**文件 IO 只在这条线程上**（触摸路径上不可能出现，
            //    安装/改参数的那条主线程路径上也没有 —— 见 [RecentStore] 的时机纪律）。
            //
            // 恢复出来的历史**不按 isLaunchable 过滤**：这一刻应用清单可能还没扫完
            // （`baseIndex == null`），过滤会把整份历史一次清空。真正的过滤在发布那一侧
            // （`AppIndex.Index.withSpecial` 按包名查表，查不到的就不画到面板上），
            // 所以卸载掉的应用不会出现在面板里，而它也不该被我们从历史里抹掉 ——
            // 用户可能是暂时停用了那个应用。
            val restored = RecentStore.load(appContext, SideIndexConfig.MAX_RECENT_COUNT)
            if (restored.isNotEmpty() && running) {
                synchronized(lock) {
                    retained = restored
                    current = RecentList.visible(retained, displayCount)
                }
                // 日志要能回答「重启之后到底恢复了几条、显示几条」——
                // 「重启就丢」这类问题的证据只能从这一行读出来
                Log.i(RECENT_TAG, "最近使用：磁盘恢复 ${restored.size} 条，本次显示 ${current.size} 条")
                onChanged()
            }

            // ② 采样循环（这一版与上一版逐行相同，只是 record 里多了一次写盘）
            while (running) {
                val pkg = SystemUiEnv.ForegroundTracker.foreground()
                if (pkg != null && pkg != lastSeen) {
                    lastSeen = pkg
                    if (isLaunchable(pkg) && record(pkg)) onChanged()
                }
                try {
                    Thread.sleep(RECENT_SAMPLE_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "fp-sideindex-recent").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** @return 列表是否真的变了（变成同一个列表就不用惊动主线程，也不必写盘） */
    private fun record(pkg: String): Boolean {
        val next = synchronized(lock) {
            val candidate = RecentList.push(retained, pkg, SideIndexConfig.MAX_RECENT_COUNT)
            // 引用相等 = 什么都没变（`RecentList.push` 的契约）。这一档最常见 ——
            // 用户一直在用同一个应用时每秒都会走到这里，所以它必须零副作用：
            // 不写盘、不发通知、不分配。
            if (candidate === retained) return false
            retained = candidate
            current = RecentList.visible(candidate, displayCount)
            candidate
        }
        // 写盘放在锁外：一次文件写可能几毫秒，不该占着锁。
        // 写盘在**采样线程**上，绝不回主线程 —— 主线程只通过 onChanged 收到一条「重画」通知。
        //
        // `running` 再判一次：`stop()`（关掉 show_recent / 卸载）之后这个线程可能正卡在
        // 这里，而那时**新的**采样器可能已经在读同一个文件了（关掉再打开）。
        // 不判的话，这次写会把新采样器读到的那一条盖掉 —— 窄竞态，但代价是丢数据。
        if (!running) return true
        RecentStore.save(appContext, next)
        return true
    }

    private companion object {
        const val RECENT_SAMPLE_MS = 1000L
    }
}

/**
 * 「最近使用」的持久化落点：**SystemUI 进程私有目录下的一个文本文件**。
 *
 * ## 为什么不是配置通道（本次唯一需要解释的取舍）
 *
 * 需求原文是「走远程 prefs」。**做不到**：
 *
 *  - hook 侧用 `getRemotePreferences(group)` 读，框架级、**只读**，支持
 *    `registerOnSharedPreferenceChangeListener`，写侧要 `io.github.libxposed:service`；
 *  - `ui/.../state/ShortcutCatalogStore.kt` 的类注释记着**设备实测**的失败形态：
 *    hook 侧 `SharedPreferences.edit()` 抛 `UnsupportedOperationException`
 *    （日志：`FP-Fan: 发布快捷方式目录失败：UnsupportedOperationException`）。
 *
 * 而 `hook/build.gradle.kts` **故意不含** `io.github.libxposed:service`（那个文件里
 * 写明了 `:hook` 的依赖边界），所以 hook 进程里根本没有写入口。
 *
 * 「后端投递给模块 App 的 ContentProvider」那条路也不合用：[ShortcutCatalogStore] 的
 * 注释自己写着「这个调用会**拉起模块 App 进程**（如果它没在跑）」——
 * 而「最近使用」在用户每切一个应用时都会变，让 SystemUI 每切一次应用就唤醒一次
 * 设置界面进程，代价与收益完全不成比例（那一条通道是给「枚举一次、很少变」的
 * 快捷方式目录用的）。
 *
 * ## 于是就用「自己进程的私有目录」
 *
 * `SystemUiEnv.context()` 给的是 SystemUI 自己的 Context，`filesDir` 就是它自己的私有目录
 * （本机 SystemUI 的 sharedUserId 是 `android.uid.systemui`，不是 system）。所以：
 * **不需要任何权限、不碰系统分区、也不碰任何别的应用的数据** ——
 * 不碰用户数据（只写我们自己的一个几百字节 scratch 文件）、
 * 可回滚（删文件即可，回滚步骤 = 删掉 `files/<FILE_NAME>` 那一个文件）、
 * 也不影响任何别的进程（没有第二个读者）。
 *
 * 要避开的是 `/data/adb`（`0700 root`，SystemUI 读不到会**静默失败**），
 * 不是这里。
 *
 * ⚠️ **待真机验证**：`filesDir` 可写这件事本轮没法验（派工纪律：不许装机）。
 * 所以 [probe] 在安装时 stat 一次，把结论写进安装报告 —— 万一这台机器上不可写，
 * 报告里会是 `recent_store=unwritable(...)`，而不是安静地退回「重启就丢」那种
 * 本项目最忌讳的静默降级。[save] 的失败也会留一条 WARN。
 *
 * ## 格式与原子性
 *
 * 内容就是 `OrderedItems`（一条一行、最新在最前）—— 与配置侧**同一套编码**，
 * 不新造格式（`OrderedItemsTest` 已经钉住它的行为，这里只有「一条一行」这一个契约）。
 *
 * 写入走「先写 `.tmp` 再 rename」：rename 在同一目录内是原子的，所以进程写到一半被杀
 * 不会留下半截包名。半截包名在面板上表现为一个永远点不动的格子，而且它会被当成
 * 一个正常的「最近使用」项留在历史里 —— 值得用三行代码换掉。
 *
 * ## 与 `uninstall()` 的关系
 *
 * **不随卸载删除**。模块没装/特性关掉之后没有任何代码读这个文件，所以它不影响
 * 「关态 = 未安装模块」这条硬要求；而删掉它等于「用户每开关一次特性就清空一次历史」——
 * 那正好把这个功能的意义抵消掉。
 */
private object RecentStore {

    /**
     * 文件名。落在 `context.filesDir` 下，即真机上的
     * `/data/user/0/com.android.systemui/files/fp_side_index_recent`
     * （SystemUI 是 direct-boot 应用时也可能是 `/data/user_de/0/...`，两者都是它自己的私有目录）。
     */
    const val FILE_NAME = "fp_side_index_recent"

    private fun file(context: Context): File? = try {
        // 注意 `context.filesDir` 本身会确保目录存在（`ContextImpl.getFilesDir` 里 mkdir），
        // 所以下面 probe 里的 `canWrite` 判的是一个真的存在了的目录，不会误报
        File(context.filesDir, FILE_NAME)
    } catch (_: Throwable) {
        null
    }

    /**
     * 安装时探一次。**只 stat、不写任何东西**，结论进安装报告。
     *
     * 为什么必须有这一条：这条路走不通时的表现就是「最近使用重启后还是空」，
     * 与「没做这个功能」在界面上完全一样。把结论打在报告里，至少能一眼分清
     * 「是没生效」还是「生效了但盘上写不进去」。
     */
    fun probe(context: Context): String {
        val f = file(context) ?: return "unavailable(no filesDir)"
        val dir = f.parentFile ?: return "unavailable(no dir)"
        return when {
            !dir.isDirectory -> "unavailable(not a dir)"
            !dir.canWrite() -> "unwritable(${dir.absolutePath})"
            else -> "ok($FILE_NAME)"
        }
    }

    /** 读。**只在采样线程调**。任何失败都按「没有历史」处理（= 改动前的行为），绝不抛。 */
    fun load(context: Context, max: Int): List<String> {
        val f = file(context) ?: return emptyList()
        return try {
            if (!f.isFile) return emptyList()
            // 上限在**读回来之后**再夹一次：文件可能被手改过，或者上一版写的上限更大
            OrderedItems.decode(f.readText()).take(max)
        } catch (t: Throwable) {
            Log.w(RECENT_TAG, "最近使用：读磁盘失败，按空历史处理（功能不受影响）", t)
            emptyList()
        }
    }

    /** 写。**只在采样线程、且列表真的变了的时候调**。失败只记日志（退化成旧行为）。 */
    fun save(context: Context, packages: List<String>) {
        val f = file(context) ?: return
        try {
            val text = OrderedItems.encode(packages)
            val tmp = File(f.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                // 同目录 rename 理论上不会失败；真失败了就退回直接写 ——
                // 「偶尔非原子」比「明明变了却没存下来」好
                f.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(RECENT_TAG, "最近使用：写磁盘失败，本次不持久化（功能不受影响）", t)
        }
    }
}

/**
 * 这个文件里所有日志的 tag。
 *
 * 取值与 `SideIndex.TAG` **故意相同**（`"FP-SideIndex"`）：排障时按一个 tag 过滤就能看到
 * 完整的因果链（配置 → 采样 → 落盘），而这个文件里的两个顶层私有类
 * （[RecentApps] / [RecentStore]）够不到 `SideIndex` 内部的私有常量。
 */
private const val RECENT_TAG = "FP-SideIndex"

/**
 * 侧滑判定 + 浮层请求。
 *
 * 和 [io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.CornerSwipeDetector] 一样，
 * [onTouch] 跑在**输入分发线程**上：判定部分不分配、不打日志、不做 IPC、不碰 View。
 * 所有 UI 动作都通过 [SlidePerformancePolicy] 转给主线程。
 *
 * ## 触发链与守卫：逐条对照 Flyme 的 `SlideAppsTouchController` + `MzSlideAppsSearchPhoneImpl`
 *
 * Flyme 的取法（`SlideAppsTouchController.onControllerInterceptTouchEvent`）：DOWN 时先过
 * 一张**守卫清单**，通过才 `setDetectableScrollConditions(...)` 交给
 * `SingleAxisSwipeDetector(VERTICAL)`（`setStrictStrategy(true)`）去识别纵向单轴拖动，
 * 拖动成立后每一帧把事件转发给 `disposeMotionEvent` 显示面板。
 * 我们拿不到 Launcher3 的 TouchController 链（事件来自 WMShell 的输入监视通道，
 * 见 [SwipeEventHub]），所以**照抄判定语义、不照抄类**：
 *
 * | Flyme 的守卫（`canInterceptTouch` / `touchInEdge`） | 我们怎么办 |
 * | --- | --- |
 * | `!isInState(LauncherState.NORMAL)` 不响应（不在桌面态） | 由生效范围 [SideIndex.scopeAllowed] 覆盖（`launcher_only` = 「只在桌面」）**加上抽屉门控** [SideIndexDrawerGate]（需求 #8：桌面态里的「抽屉」这一屏也算「不是桌面态」，见下）|
 * | `!isTouchingInEdge()` 不响应 | [onTouch] 的 DOWN 分支判边缘，等价；**而且是 DOWN 时锁定整段手势**，与 `touchInEdge` 只在 `action == 0` 改判、`action == 1/3` 清位一致（`MzSlideAppsSearchPhoneImpl.java:200-219`）|
 * | 顶层有 `TYPE_ACCESSIBLE` 浮窗 → 不响应 | **信号已备好，待验证触发**（2026-09-14）：改走 native hook，挂了同族三对 —— `notifyEditEnable/Disable`（编辑态）、`notifyWidgetsPreviewShow/Hide`（小部件预览页）、`notifyStackExpandOpen/Close`（堆叠展开），与抽屉/文件夹同一条链路（共享文件 → [LauncherNativeOverlayTracker] → [SideIndexDrawerGate]），10 个靶点全部装上且指纹闸门全过；但触发路径要**多指或前置条件**（编辑态是双指捏合），adb 单指做不到 → 待手工验证 |
 * | 顶层有 `TYPE_STATUS_BAR_SWIPE_DOWN_DISALLOW` 浮窗 → 不响应 | **做不到**，同上 |
 * | `y > 高度 - 底部 insets` → 不响应 | 由 [cornerGuardPx]（56dp）覆盖，它比导航栏 insets 更大 |
 * | **顶部区域（状态栏 / 控制中心下拉区）→ 不响应** | **2026-09-14 加**：`SideIndexHotZone.TOP_GUARD_DP`（120dp）。起因是实测反馈「沿边缘下滑呼出索引，容易拉开控制中心」—— 两者抢的是同一条边缘上的同一段位移，所以顶部这一段不许起手（与 `cornerGuardPx` 对称）。**Flyme 没有这一条**，它的索引在 launcher 的 TouchController 链里、天生少吃一段系统事件；我们是从输入监视通道旁路取事件，必须自己避开系统的领地 |
 * | `isInAssistantPage()`（负一屏）→ 不响应 | **2026-10-25 改为观察负一屏 overlay 窗口自身**（收起时 `alpha=0` + `NOT_TOUCHABLE`，判据见 `LauncherDrawerObserver.onAssistantRelayout`，与抽屉合并成同一条信号；展开态属性待真机验证）。旧结论：实测负一屏是桌面自己的窗口（`LauncherOverlayWindow:com.miui.personalassistant` 属 `com.miui.home`），前台包名照样是桌面。**它和抽屉不是同一件事** —— 抽屉那条是靠「桌面主窗口的 `softInputMode` 变成 `adjust=resize`」判的（见 [SideIndexDrawerGate]），而负一屏的窗口属性实测**没有**这个变化，所以这套判据覆盖不到它 |
 * | **桌面文件夹打开** → 不响应 | **实测做不到**（2026-09-14）：全文 diff `dumpsys window windows` 在文件夹打开前后**只有布局序号与时间戳在变**（`mLayoutSeq`、`lastLaunchTime`），窗口层/任务层**没有任何语义差异**；a11y 节点数只 +1（39→40，而抽屉是 39→77，不足以当判据）。文件夹是桌面 Dart 层内部状态，窗口层不留痕 |

 * | `Workspace.isPageInTransition()` → 不响应 | **做不到**，Launcher3 内部状态 |
 * | `FlymeOneHandedController.touchInOneHandedModeRegionForApp()` → 不响应 | **做不到**，Flyme 内部单手模式 |
 *
 * ## 「桌面内部状态」这一类里，抽屉是第一条真正解决的（2026-09-14）
 *
 * 上表前两条 `TYPE_ACCESSIBLE` / `TYPE_STATUS_BAR_SWIPE_DOWN_DISALLOW` 与
 * `isInAssistantPage` / `isPageInTransition` 都属于「判据在桌面进程内部」那一类，
 * 而抽屉曾经也被归在里面。事实是：**抽屉有一条确定性的外部观测面** ——
 * 它会让桌面**主窗口**的 `softInputMode` 从 `adjust=nothing` 变成 `adjust=resize`
 * （抽屉底部有搜索框，要让输入法能顶起来）。这条属性在 WMS 里，而模块注入
 * system_server 是本来就有的事，所以它从「做不到」变成了「做得到」。
 *
 * **但抽屉那条是运气，别指望能复制到别的桌面状态上**：抽屉之所以有窗口层信号，
 * 是因为它底部有搜索框、需要让输入法把窗口顶起来，才改了 `softInputMode`。
 * **文件夹没有这个需求，所以什么也不改** —— 实测全文 diff 只有布局序号与时间戳在动。
 * 换句话说：`sim` 这个信号是「搜索框」的副产品，不是「桌面状态」的通用通道。
 *
 * 仍然做不到的那几条，原因也写清楚了：它们的判据**不是窗口属性**
 * （浮窗类型在 Launcher3 进程内、页面过渡是 Launcher3 的内部状态机），
 * 或者窗口属性实测不随该状态变化（负一屏）。要动它们就得走 native hook 桌面进程
 * 那是另一件事，不在本条范围内。
 *
 * 「做不到」的那几条不是偷懒：硬凑一个近似判据会让行为在真机上变得不可预测，
 * 按项目规矩，做不到就如实写下来（见最终报告里的未验证清单）。
 *
 * ## 角度滤波：照抄 Flyme `ASLetterBarView` 的 30°（**只作用于面板字母跟踪**）
 *
 * ```java
 * // ASLetterBarView.touch()，第 158-159 行
 * double dAtan = Math.toDegrees(Math.atan(Math.abs(x - mOldTouchX) / Math.abs(f2 - mOldTouchY)));
 * if (mOldTouchX == -1.0f || mOldTouchY == -1.0f || dAtan <= CRITICAL_ANGLE /* 30 */) {
 *     ... 接受本次采样，并在接受时更新 mOldTouchX / mOldTouchY
 * }
 * ```
 *
 * 两个要点，都照搬：
 *
 *  1. **比较的是相邻两个采样点**（`mOldTouchX/Y`），不是起点。所以手指中途拐弯不会
 *     把整段手势判废 —— 只要相邻两帧还够「竖」就继续。用起点比较会一拐就废。
 *  2. `mOldTouchX/Y` **只在采样被接受时更新**（原文 176-177 行）。所以连续几帧角度超标时，
 *     比对的仍是那个较早的点，直到角度回到 30° 内 —— 这本身就是逐点去抖。
 *
 * 我们用 `|dx| <= tan(30°) * |dy|` 表达同一个条件（代数等价、少一次 atan）。
 * 边界：`|dy| == 0` 时 Flyme 得到 `0/0 = NaN`，`NaN <= 30` 为假 → 拒绝；
 * 我们的写法在 `|dy| == 0` 时要求 `|dx| <= 0`，同样拒绝（纯横向位移），行为一致。
 *
 * ## 起步条件 = Flyme 的「严格策略」（这是防误触的核心，不是 30° 滤波）
 *
 * ### 读源码得到的结论
 *
 * `SlideAppsTouchController` 的构造里有 `singleAxisSwipeDetector.setStrictStrategy(true)`
 * （`SlideAppsTouchController.java:34`）。它的作用在 `SingleAxisSwipeDetector` 里：
 *
 * ```java
 * // SingleAxisSwipeDetector.java:109-119  shouldScrollStart(PointF p)
 * float fMax = Math.max(mTouchSlop * mTouchSlopMultiplier, Math.abs(mDir.extractOrthogonalDirection(p)));
 * if (Math.abs(mDir.extractDirection(p)) < fMax) return false;              // ① 主轴必须占优
 * if (mStrictStrategy && (Math.abs(extractDirection(p)) < mTouchSlop * 4.0f
 *                      || Math.abs(extractDirection(p)) < fMax * 3.0f)) return false;  // ② 严格策略追加
 * return canScrollNegative(extractDirection(p)) || canScrollPositive(extractDirection(p));
 * ```
 *
 * 对 `VERTICAL`（`extractDirection` = y、`extractOrthogonalDirection` = x，
 * 见同文件 `:19-43`）展开就是三条**同时**成立（下面用 `abs()` 写，避免 Markdown 表格被竖线打断）：
 *
 * | 条件 | 代数式 | 几何含义 |
 * | --- | --- | --- |
 * | ① | `abs(dy) >= max(slop, abs(dx))` | 纵轴必须比横轴长（Launcher3 基线条件，非严格模式也有）|
 * | ②a | `abs(dy) >= 4 × slop` | 最少要走 4 个 slop 才认 |
 * | ②b | `abs(dy) >= 3 × max(slop, abs(dx))` | 纵轴要比横轴（至少比 slop）长 3 倍 |
 *
 * ②b 换算成角度是 `atan(1/3) ≈ 18.43°` —— 即**偏离竖直超过 18.4° 的滑动一律不算**。
 * ②a 把「起步距离」从 1 个 slop 抬到 4 个 slop。两条合起来就是它「基本不误触返回手势」的
 * 全部秘密：**返回手势是水平内滑，它连第一个条件都过不去**。
 *
 * `p` 是 `mDisplacement`，即**相对 DOWN 点的总位移**（`BaseSwipeDetector.java:140` 里
 * `mDisplacement.set(x - mDownPos.x, y - mDownPos.y)`），不是相邻两帧的增量。
 * 而且整个判定只在 `mState != DRAGGING` 时被调用（`:145`）——
 * **一旦进入 DRAGGING 就再也不复判**，之后的横向移动不会取消它。
 *
 * 我们按这三条原样实现（[STRICT_SLOP_MULTIPLIER] / [STRICT_AXIS_RATIO]）。
 *
 * ### 30° 滤波和它的分工（别混为一谈）
 *
 * `ASLetterBarView.touch()` 的 30°（`:158-159`）**不属于起步判定**：它跑在
 * `disposeMotionEvent` → `letterBarView.touch()`（`MzSlideAppsSearchPhoneImpl.java:259`）
 * 里，也就是**面板已经显示之后**、把手指 y 映射成字母的那一步，而且只在手指落在
 * 字母条矩形内时生效（`ASLetterBarView.java:152`）。所以本类里
 * [withinCriticalAngle] 只用在**已激活后的字母跟踪**路径上，起步判定不用它 ——
 * 严格策略的 18.43° 已经把 30° 盖住了，两处都留着只会让「哪条在生效」变得难讲。
 *
 * ### 我们**没有**照抄的一条
 *
 * Flyme 起步之后不再取消（见上）。我们是**旁观 + 可消费**通道，
 * 消费 MOVE 会让下面的应用吃到 ACTION_CANCEL；理论上「起步后横向走远就撤销」更保守，
 * 但**不能这么做**：索引面板本身就要靠横向移动（手指从字母条移到图标网格上，
 * 见 [onTouch] 里 `inBarColumn` 之外的路径），一横向就撤销等于面板没法用。
 * 代价由起步闸门承担：过得了 4×slop + 3:1 的手势，已经不可能是一条返回手势了。
 */
internal class SideIndexDetector(
    /**
     * 当前这一代浮层。**可变** —— 外观参数变化时整体换代（见 [SideIndex.swapOverlay]），
     * 由 [setOverlay] 换引用。**只在主线程写**（浮层的所有方法都要求主线程），
     * 输入分派线程只读 [SideIndexOverlay.layout] 这一个 volatile 字段。
     */
    private var overlay: SideIndexOverlay,
    private val policy: SlidePerformancePolicy,
    /**
     * 触发几何的来源。**在 ACTION_DOWN 读一次**，整段手势用那一份快照
     * （见 [gestureTrigger]）。读的是一次 `@Volatile` 引用 + 不可变值，
     * 所以放在输入分派线程上是安全的（无分配、无 IPC、无锁）。
     *
     * 为什么传一个取值函数而不是传值本身：`trigger_width` / `side` 改完要**立刻**生效。
     * 传值就等于把它冻在构造期 —— 那正是这一版要修掉的缺陷。
     */
    private val triggerOf: () -> TriggerConfig,
    private val onError: (Throwable) -> Unit,
    /**
     * 起手判定的**诊断日志**（限次）。
     *
     * 2026-09-14 加的：这一轮踩了两次「改了判据但说不清为什么没响应」——
     * 索引的判据链很长（边缘、顶部、方向、门控、严格单轴），而它对**不可见**
     * （浮层没出来就是没出来，没有任何线索）。所以让它在起手那一刻把理由说出来。
     * **只在输入线程上做一次计数器比较**，超预算直接返回，不产生任何分配。
     */
    private val onNote: (String) -> Unit,
) : SwipeEventHub.Listener {

    /**
     * 起手诊断日志的预算（每次 SystemUI 重启重置）。见 [onNote]。
     *
     * 定成 400 是有原因的：验收时**真人也在同时划屏幕**，小预算会在半分钟内被烧光 ——
     * 排障的人看到的就是「日志没了」，很容易误判成「判据没跑」。
     * 2026-09-15 起每成功一次起手要打两行（抢指针 + 起手成立），所以从 200 提到 400。
     */
    private val noteBudget = java.util.concurrent.atomic.AtomicInteger(400)

    private fun note(msg: String) {
        if (noteBudget.getAndDecrement() > 0) onNote(msg)
    }

    private companion object {
        /**
         * Flyme `ASLetterBarView.CRITICAL_ANGLE = 30`（相对竖直方向的夹角上限）。
         * 下面的写法是 `degrees(atan(|dx|/|dy|)) <= 30` 的代数等价形式。
         *
         * ⚠️ 它只用于**起步之后**的字母跟踪（见类注释「30° 滤波和它的分工」），
         * 起步判定用的是严格策略的 18.43°。
         */
        val TAN_CRITICAL = kotlin.math.tan(Math.toRadians(30.0))

        /**
         * 严格策略的轴向优势：`|dy| >= fMax * 3.0f`（`SingleAxisSwipeDetector.java:114`）。
         * 3:1 换算成角度是 `atan(1/3) ≈ 18.43°` —— 偏离竖直超过这个角度的滑动一律不算。
         *
         * **这一条是「不抢返回手势」的全部依据**（返回是水平内滑，连 1:1 都过不去），
         * 所以它一个字都没动过。
         */
        const val STRICT_AXIS_RATIO = 3f

        /**
         * 起手需要的竖直位移 = **2 × slop**（原先照抄 Flyme 的 4×）。
         *
         * 为什么敢降：Flyme 那 4× 是它**唯一的**防误触手段（它的索引长在 launcher 的
         * TouchController 链上，起步距离不够就会被自己的抽屉手势抢走）。我们是旁路通道，
         * 现在有两条它没有的保险：**3:1 的方向锥**（返回手势过不去）与
         * **[SwipeEventHub.pilfer] 抢指针**（真接管，系统的同向手势会被取消）。
         * 留着 4× 的代价是实测出来的：用户报告「不太能及时触发」—— 4×slop ≈ 104px
         * 的纯竖直位移才认，手感上就是「划了没反应」。
         */
        const val START_SLOP_MULTIPLIER = 2f

        /**
         * **抢指针**需要的竖直位移 = **1 × slop**，比 [START_SLOP_MULTIPLIER] 更早一档。
         *
         * 为什么要更早：飘在外面的系统手势（桌面抽屉、控制中心/通知栏）阈值都比我们小
         * （实测桌面抽屉 42px 就已经开始翻了），等我们过起手闸门再抢，它们已经翻到一半。
         * 抢指针会让窗口收到 `ACTION_CANCEL`，那正是让它们**退回去**的信号。
         *
         * 方向锥与起手闸门**同一条**（[STRICT_AXIS_RATIO]）：抢指针若比闸门宽，
         * 就会出现「系统手势被取消了、索引又没起来」这种什么都不发生的手势。
         */
        const val GRAB_SLOP_MULTIPLIER = 1f
    }

    private var active = false
    /**
     * 这次触摸是否已经归我们（边缘优先消费了 DOWN）。见 [SideIndex.edgePriority]。
     * 一旦持有，就不会半途交还给别人 —— 别人拿到半截事件流只会更糟。
     */
    private var owns = false

    /**
     * 本次手势是否已经抢过指针（每段手势一次，见 [GRAB_SLOP_MULTIPLIER] 与
     * [SwipeEventHub.pilfer]）。抢到手之后系统的手势会被取消，我们的判定照旧继续 ——
     * 起手闸门没过就只是「什么都没发生」，不会再变成系统的抽屉/控制中心。
     */
    private var grabbed = false
    private var onRight = false
    private var startY = 0f

    /** 上一个**被接受**的采样点（Flyme `mOldTouchX/Y`）。`hasLast=false` 表示还没有。 */
    private var hasLast = false
    private var lastX = 0f
    private var lastY = 0f

    private var selected = -1

    /**
     * 本次手势固定使用的几何。
     *
     * 一次手势**必须自始至终用同一份**：中途换掉（比如横竖屏切换导致重算）
     * 就会出现「前半个手势按 A 表判定、后半个按 B 表」。所以 DOWN 时取一次快照，
     * UP/CANCEL 时清空。
     */
    private var gesture: AppIndex.BarLayout? = null

    /**
     * 本次手势的**触发几何**快照（边缘带宽 / 允许哪几条边 / 角落保留区 / touchSlop）。
     *
     * 与 [gesture] 同一个时机、同一条纪律（DOWN 取、UP/CANCEL 清），理由也一样：
     * 用户完全可以一边拖 `trigger_width` 的滑杆一边滑（`scope=anywhere` 时设置界面
     * 也在生效范围内），中途换掉就会出现「看得到的热区」和「判定的热区」不是一块。
     * 取值走 [triggerOf]，只读一次 `@Volatile` 引用 —— 输入分派线程上合规。
     */
    private var gestureTrigger: TriggerConfig? = null

    /**
     * Flyme `ASLetterBarView.touch()` 里那个角度判据：
     * `degrees(atan(|dx| / |dy|)) <= 30`，等价于 `|dx| <= tan(30°) * |dy|`。
     *
     * 纯计算、无分配，可以放在输入分发线程上。
     */
    private fun withinCriticalAngle(dx: Float, dy: Float): Boolean {
        val ady = if (dy < 0f) -dy else dy
        val adx = if (dx < 0f) -dx else dx
        return adx <= TAN_CRITICAL * ady
    }

    /**
     * 起步闸门 = Flyme 的严格单轴策略，逐条对应
     * `SingleAxisSwipeDetector.shouldScrollStart`（`:109-119`，`VERTICAL` 方向，
     * 由 `SlideAppsTouchController.java:34` 的 `setStrictStrategy(true)` 打开严格分支）。
     *
     * ```
     * fMax = max(slop, |dx|)
     * ①  |dy| >= fMax                    → 纵轴是主轴
     * ②a |dy| >= 2 × slop               → 最少走 2 个 slop（Flyme 是 4×，见 [START_SLOP_MULTIPLIER]）
     * ②b |dy| >= 3 × fMax               → 纵轴比横轴长 3 倍（偏离竖直 ≤ 18.43°）
     * ```
     *
     * 方向**不设限**：上游是 `setDetectableScrollConditions(3, false)`
     * （`SlideAppsTouchController.java:62`），而 `3 = DIRECTION_POSITIVE(1) | DIRECTION_NEGATIVE(2)`
     * （`SingleAxisSwipeDetector.java:11-13` 与 `canScrollNegative/Positive`:121-127），
     * 即上下都收。第二个参数 `false` 是 `mIgnoreSlopWhenSettling`（`:98-101`），
     * 只在「上一次拖动还在 settling 时按下」这条路径上有意义，我们没有 settling 态，不适用。
     *
     * ②b 这一条就是「侧滑索引不再抢返回手势」的全部依据：返回是水平内滑，
     * `|dy| >= 3 × |dx|` 一次都过不去。[withinCriticalAngle] 的 30° 在这里**不参与** ——
     * 它比 18.43° 松，留着只会让人分不清哪条在生效（它用在字母跟踪那一路，见类注释）。
     *
     * ⚠️ ②a 从 4× 降到 2× 的代价由 [SwipeEventHub.pilfer] 兜住：过闸就真接管，
     * 起步距离短了一倍、误判的风险却更小 —— 见那个常量的注释。
     *
     * 纯计算、无分配，可以放在输入分发线程上。
     */
    private fun strictStartOk(dx: Float, dy: Float, touchSlopPx: Float): Boolean {
        val adx = if (dx < 0f) -dx else dx
        val ady = if (dy < 0f) -dy else dy
        val fMax = if (touchSlopPx > adx) touchSlopPx else adx
        // ① 纵轴是主轴
        if (ady < fMax) return false
        // ②a 最少走这么远才算起手
        if (ady < touchSlopPx * START_SLOP_MULTIPLIER) return false
        // ②b 纵轴比横轴长 3 倍（偏离竖直 ≤ 18.43°，返回手势过不去）
        if (ady < fMax * STRICT_AXIS_RATIO) return false
        return true
    }

    /**
     * **能不能抢指针**：比起手闸门松一档、早一档（只有「最少走多远」这一条不同，
     * 方向锥完全相同 —— 理由见 [GRAB_SLOP_MULTIPLIER]）。
     *
     * 两个方向都认：这条边（左边缘，或用户配置的右边缘）上的纵向滑动整条都是我们的，
     * 上滑归我们之后桌面的抽屉就由 [SwipeEventHub.pilfer] 取消掉
     * （2026-09-14 曾实测「上滑在 x=5/40/80…每一处都打开了抽屉」，
     * 当时的结论是「上滑只能整条让出去」；那是**抢指针之前**的结论，已经不成立）。
     */
    private fun grabOk(dx: Float, dy: Float, touchSlopPx: Float): Boolean {
        val adx = if (dx < 0f) -dx else dx
        val ady = if (dy < 0f) -dy else dy
        if (ady < touchSlopPx * GRAB_SLOP_MULTIPLIER) return false
        val fMax = if (touchSlopPx > adx) touchSlopPx else adx
        return ady >= fMax * STRICT_AXIS_RATIO
    }

    /**
     * 换一代浮层（外观参数热更新）。**主线程** —— 浮层的每个方法都要求主线程，
     * 而本函数只做一次引用赋值。调用点是 [SideIndex.swapOverlay]，它保证这时
     * 没有手势在进行，所以不存在「判定读旧浮层、渲染走新浮层」的中间态。
     */
    fun setOverlay(newOverlay: SideIndexOverlay) {
        overlay = newOverlay
    }

    override fun onTouch(event: MotionEvent): Boolean {
        return try {
            handle(event)
        } catch (t: Throwable) {
            // 输入线程上绝不能抛出去。这里会打一条日志，是**异常路径**上的让步：
            // 规范禁的是高频路径上的日志，而这条路径应该永远不出现，
            // 真出现了就必须留下痕迹，否则问题是不可见的。
            release()
            onError(t)
            false
        }
    }

    private fun handle(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = false
                selected = -1
                gesture = null
                gestureTrigger = null
                hasLast = false
                owns = false
                grabbed = false

                // 几何没发布 = 索引还没就绪且未能发布 / 查询失败（Unavailable），
                // 或者已经卸载了 —— 这一帧我们什么都不做
                val l = overlay.layout ?: return false
                // 触发几何**在这一刻取一次快照**，整段手势都用它（见 [gestureTrigger]）。
                // 一次 volatile 引用读，无分配、无 IPC —— 输入分派线程上合规。
                val t = triggerOf()
                val x = event.x
                val y = event.y
                // 三道闸（底部保留区 → 顶部保留区 → 边缘带）的判定在 core 的 SideIndexHotZone，
                // 设置界面的预览与屏幕高亮画的是同一块区域；判定顺序与搬家前逐位一致（有单测钉住）。
                //
                // 底部角落是底角小窗的地盘，这里主动让开。
                // 它同时覆盖了 Flyme 守卫里的「y > 屏幕高 - 底部 insets」那一条
                // （`SlideAppsTouchController.java:104`：DOWN 落在底部系统手势条里就不拦截）。
                // 顶部：系统的下拉区（状态栏 / 控制中心）。理由见 SideIndexHotZone.TOP_GUARD_DP。
                //
                // 边缘窄区：Flyme 的 `mTouchArea` = **17dp**（`MzSlideAppsSearchPhoneImpl.setEnable:310`
                // `mTouchArea = MzCommonUtils.dpToPx(launcher, 17.0f)`，非简易模式下常态生效，
                // `FlymeEasyModeUtils.java:320` 传 `!isInEasyMode()`），而且**只在 DOWN 时判一次**：
                // `touchInEdge` 的 `action == 0` 分支置位、`action == 1/3` 清位，中间的 MOVE 直接
                // `return mTouchingInEdge`（`MzSlideAppsSearchPhoneImpl.java:200-219`）——
                // 也就是「DOWN 时锁定整段手势」，跟这里一致。
                // 我们的默认 20dp 比它宽 3dp（`trigger_width` 由 Catalog 给默认值，见报告），
                // 判定宽 3dp 对手势归属的影响远小于下面 [strictStartOk] 的方向闸门。
                // 这两个字段（[TriggerConfig.edgePx] / [TriggerConfig.allowLeft]）都是**热改**的：
                // 改了 `trigger_width` 或 `side` 之后的下一次按下就是新值，见 [publishTrigger]。
                val verdict = SideIndexHotZone.classify(
                    x = x,
                    y = y,
                    screenW = l.screenW.toFloat(),
                    screenH = l.screenH.toFloat(),
                    edgePx = t.edgePx,
                    allowLeft = t.allowLeft,
                    allowRight = t.allowRight,
                    topGuardPx = t.topGuardPx,
                    bottomGuardPx = t.cornerGuardPx,
                )
                when (verdict) {
                    SideIndexHotZone.Verdict.BOTTOM_GUARD -> {
                        note("起手被拒：落在底部保留区（小窗/手势导航的地盘）")
                        return false
                    }
                    SideIndexHotZone.Verdict.TOP_GUARD -> {
                        note("起手被拒：落在顶部保留区（状态栏/控制中心的下拉区）")
                        return false
                    }
                    SideIndexHotZone.Verdict.OUTSIDE -> {
                        note("起手被拒：不在边缘带里（x=" + x.toInt() + " 宽带=" + t.edgePx.toInt() + "）")
                        return false
                    }
                    SideIndexHotZone.Verdict.LEFT, SideIndexHotZone.Verdict.RIGHT -> Unit
                }
                val inRight = verdict == SideIndexHotZone.Verdict.RIGHT

                // 右边缘**不再整条让出**（2026-09-15 更正）。
                //
                // 2026-09-14 曾按实测把右边缘让掉（「下拉=控制中心、上滑=抽屉，两个方向都被系统占着」）——
                // 那是在**没有抢指针**的前提下测的：我们只 return true 时，系统手势照旧会执行，
                // 于是唯一的办法是退。现在起手时会 [SwipeEventHub.pilfer]，两个方向都抢得过来了
                // （`side=both` 这个参数也才不再是个谎）。
                // 右边缘那处旧冲突（抽屉自带的 A-Z 索引条）由抽屉门控 [SideIndexDrawerGate] 负责 ——
                // 抽屉一开我们连 DOWN 都不消费，见下面 owns 的三条例外。
                onRight = inRight
                startY = y
                // Flyme 在 DOWN 就把当前位置记为「上一个采样点」（它的 mOldTouchX/Y
                // 在 setDirection 里被复位成 -1，于是第一个采样无条件接受，
                // `ASLetterBarView.java:123-125,159`）。这里的 lastX/lastY 同时兼任
                // 「严格策略的位移原点」——它在起步前不会被改写（只有在字母被接受时才更新，
                // 见下面 active 分支），所以 `event.x - lastX` 就是相对 DOWN 的总位移。
                lastX = x
                lastY = y
                hasLast = true
                gesture = l
                gestureTrigger = t
                // ── 边缘优先（方案 A，产品经理定的）──────────────────────────────
                // 在这一刻就消费 DOWN，把这次触摸从桌面手里拿走。三条不抢的例外缺一不可：
                //  1. `edgePriority` 为假（scope 不是 launcher_only）——
                //     在 `anywhere` 下抢 DOWN 破坏的是别的应用自己的边缘手势；
                //  2. 生效范围不放行 —— 范围外消费掉按下，用户连点击都点不了；
                //  3. 抽屉开着 —— 抽屉自带那根 A-Z 索引条就在右边缘，
                //     抢掉它等于把需求 #8 修好的东西又弄坏。
                // ── 通知栏 / 控制中心已经在场（2026-09-15）──────────────────────
                // 放在这里（几何之后、`edgePriority` 之前）而不是并进下面那个条件里：
                // 它是一条**绝对**的拒绝，与「边缘优先/生效范围」这些策略开关无关 ——
                // 面板开着的时候，任何策略下都不该再起手。
                //
                // 关键的不只是「不起手」，还有**不抢指针**：下面 `owns=true` 会在第一个 MOVE
                // 时 `pilferPointers()`，那把面板自己那条手势也一并取消（用户看到的是
                // 「面板里的快捷开关划不动 / 面板收不回去」）。`owns=false` 一起把这条堵住。
                if (SideIndexShadeGate.blocks) {
                    note("起手被拒：通知栏/控制中心已展开")
                    owns = false
                    return false
                }
                if (!SideIndex.edgePriority || !SideIndex.scopeAllowed ||
                    SideIndexDrawerGate.blocks || OverlayArbiter.fanShowing
                ) {
                    owns = false
                    return false
                }
                // 从这里起到抬手，这次触摸归我们：即使后面没过严格闸门（比如横向走远），
                // 也继续吃掉事件，不让别人接手半截手势。
                owns = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val l = gesture ?: return false
                // 触发几何用 DOWN 时那份快照（见 [gestureTrigger]）。
                // `?: return false` 只是防御：能走到这里说明 DOWN 已经把它一起设好了。
                val t = gestureTrigger ?: return false
                if (!active) {
                    // 起步 = Flyme 的严格单轴策略（`SingleAxisSwipeDetector.shouldScrollStart:109-119`）。
                    // 三条不过任意一条就**整段告别这次手势**：不消费、不画条、不画面板。
                    // 这是「返回手势不被抢」的那道闸门 —— 水平内滑连第一条都过不去。
                    val dx0 = event.x - lastX        // lastX 起步前恒为 DOWN 点的 x
                    val dy0 = event.y - startY       // startY 恒为 DOWN 点的 y
                    // **抢指针要比起手更早**：过了 [grabOk] 就先把这次触摸从窗口手里拿走，
                    // 让桌面抽屉 / 控制中心收到 ACTION_CANCEL 退回去（它们的阈值比我们的闸门小）。
                    // 即使后面起手闸门没过，代价也只是「这次手势什么都没发生」——
                    // 而不是「我们的浮层和系统手势同时出现」（那是产品经理报的三条误触的形态）。
                    if (owns && !grabbed && grabOk(dx0, dy0, t.touchSlopPx)) {
                        grabbed = true
                        val ok = SwipeEventHub.pilfer()
                        note(
                            if (ok) "抢指针：成功（系统手势已被取消）"
                            else "抢指针：失败 " + SwipeEventHub.pilferFailure
                        )
                    }
                    if (!strictStartOk(dx0, dy0, t.touchSlopPx)) {
                        return owns
                    }
                    // 只在生效范围内响应（scope=launcher_only/anywhere/exclude_launcher）。
                    // 判定值由主线程 250ms 的轮询算好（Appindex 的 `scopeAllowsEdgeTrigger`
                    // 同样是被轮询写入的字段），这里只读一个 volatile —— 输入线程上没有任何 IPC。
                    //
                    // 抽屉门控（需求 #8）**在这里再读一次**，而不是只靠 [scopeAllowed]：
                    // 那个字段最多滞后 250ms（轮询周期），而「刚划开抽屉就立刻从右边缘划」
                    // 是这条需求最典型的场景（抽屉自带的 A-Z 索引条就在右边缘）。
                    // [SideIndexDrawerGate.blocks] 同样是纯内存读（一个 volatile + 一次时间比较），
                    // 没有 IPC，符合输入线程的纪律。
                    if (!SideIndex.scopeAllowed || SideIndexDrawerGate.blocks ||
                        OverlayArbiter.fanShowing || SideIndexShadeGate.blocks
                    ) {
                        SideIndex.skippedOutOfScope++
                        gesture = null
                        return false
                    }
                    active = true
                    // 起手成立 → 这次触摸**真的**归我们（抢指针，见 [SwipeEventHub.pilfer]）。
                    // 正常路径上上面那一档已经抢过了；这里兜住「第一帧就直接越过两道闸门」
                    // 这种一跳就到的情况（pilfer 自己保证每个手势只调一次）。
                    if (owns && !grabbed) {
                        grabbed = true
                        val ok = SwipeEventHub.pilfer()
                        note(if (ok) "起步即抢指针：成功" else "起步即抢指针：失败 " + SwipeEventHub.pilferFailure)
                    }
                    note(
                        "起手成立：" + (if (onRight) "右" else "左") + "边缘 dy=" + dy0.toInt() +
                            " dx=" + dx0.toInt() +
                            // 带上门控状态：起手成立 = 那一刻所有门控都是放行的。
                            // **没有这一格，「面板开着却还能起手」就只能靠猜**（2026-09-15 就卡在这）。
                            " 门控{shade=" + SideIndexShadeGate.blocks +
                            ",drawer=" + SideIndexDrawerGate.blocks + "} " +
                            // 各条链各自的「面板收起没」候选值一起打出来：用户报「面板开着还能起手」
                            // 时，这一格直接说明是哪条链没跟上，不用再来一轮「猜信号源」。
                            SideIndexShadeGate.snapshot()
                    )
                    policy.startGesture(onRight)
                    // 第一帧就把条和面板画出来（Flyme 的 disposeMotionEvent 也是首个拖动帧就显示，
                    // 且它随后的每一帧都 `return true` 把事件吃掉 —— `SlideAppsTouchController.java:73-79`
                    // 的 `onDrag` 无条件 `return true`）。
                    //
                    // 从这里起我们才消费 MOVE（这一帧 return true 就是第一次消费）。
                    // 会不会把返回手势弄坏？**不会**，理由有两条：
                    //  1. 能走到这里的手势已经过了 2×slop + 3:1 的闸门（见 [strictStartOk]），
                    //     返回手势是水平内滑，走不到这一行；而且此刻已经 **抢过指针**，
                    //     系统那一侧收到的是 ACTION_CANCEL；
                    //  2. 起步之后**不能**再因为「横向走远」而撤销 —— 索引面板本来就要靠横向
                    //     移动才能进入图标网格（下面 `inBarColumn` 之外的路径），一横向就撤销
                    //     等于面板没法用。这一点也和 Flyme 一致：它的 SingleAxisSwipeDetector
                    //     只在 `mState != DRAGGING` 时判（`BaseSwipeDetector.java:145`），
                    //     DRAGGING 之后不复判。
                    selected = l.indexAt(event.y)
                    policy.requestLetter(selected)
                    // 这一帧就把手指 y 送过去：面板的**位置**由手指决定
                    // （Flyme 的 touchYChange），而且字母渲染是延后一帧合并的（16ms），
                    // 指针请求是立刻 post 的 —— 于是主线程先记下 y、再建面板，
                    // 面板一建出来就落在手指该在的位置，不会先闪一下再跳
                    policy.requestPointer(event.x, event.y)
                    lastX = event.x
                    lastY = event.y
                    return true
                }

                // 中途让位（2026-09-14 修「上滑拉抽屉时索引条闪一下」）：
                // 抽屉 / 文件夹 / 桌面模态态在这一段手势里被打开了 —— 那些信号来自桌面进程内的 hook
                // （[SideIndexDrawerGate] / [LauncherNativeOverlayTracker]）。它们表示「桌面已被别的东西占了」，
                // 这时我们画的条就是噪音：条先闪一下、抽屉随后打开，用户看到的就是「误触」。
                // 收尾**照 ACTION_CANCEL 那一套**（只把 gesture 置空的话浮层会留在屏幕上 —— 这个 bug 出现过）。
                if (SideIndexDrawerGate.blocks || OverlayArbiter.fanShowing ||
                    SideIndexShadeGate.blocks
                ) {
                    SideIndex.skippedOutOfScope++
                    active = false
                    selected = -1
                    gesture = null
                    gestureTrigger = null
                    hasLast = false
                    policy.endGesture()
                    policy.requestCancel()
                    return false
                }

                // 命中算法：线性分格（AppIndex.letterAt），O(1)、零分配。
                // **只有手指还在条这一列里才换字母** —— 横向离开条（跑到面板图标上）
                // 之后纵向移动必须停止换字母，否则面板会被手指在网格里上下移动的
                // 动作反复重建、字母乱跳。Flyme 的 `ASLetterBarView.touch` 也是
                // 「矩形内才算字母、矩形外只做应用命中」（它的 else 分支走 selectApp）。
                if (l.inBarColumn(event.x, onRight)) {
                    // 30° 滤波：FP 的这一帧与上一个被接受的采样点之间的夹角
                    if (withinCriticalAngle(event.x - lastX, event.y - lastY)) {
                        val idx = l.indexAt(event.y)
                        // 接受才更新参考点 —— Flyme 的 mOldTouchX/Y 只在这里被赋值
                        lastX = event.x
                        lastY = event.y
                        if (idx != selected) {
                            selected = idx
                            policy.requestLetter(idx)
                        }
                    }
                }

                // 手指位置**每帧都送**（Flyme `ASLetterBarView.touch` 里两个分支都会
                // 把位置交给面板那一侧）：
                //  - 在条上 → 面板跟手指（`touchYChange` 是每个事件都调，不受 30° 滤波影响）；
                //  - 不在条上 → 格子高亮 + 自动滚动方向（`selectApp` → `smoothScrollDownOrUp`）。
                // 请求本身是去重的（至多一个主线程任务在排队），所以全送不会堆队列。
                policy.requestPointer(event.x, event.y)
                // 消费掉：这次滑动归我们，系统/别的手势不再看
                return true
            }

            MotionEvent.ACTION_UP -> {
                // ⚠️ 只有「持有但**没起来**」这一支可以在这里提前返回（把抬手吃掉、交还所有权）。
                // 已经起来的手势**必须继续往下走**（→ commit → dismiss）：
                // 在最前面无条件 return 会让浮层留在屏幕上不消失 ——
                // 这个 bug 真出现过（A 那笔引入的），产品经理的原话是「松开应该直接结束状态」。
                // 这次手势是不是归我们（边缘优先抢下了 DOWN）——**只要归我们，抬手必须负责收尾**：
                // 起来过 → 走下面的 commit（可能拉起应用）；没起来 → cancel（条/面板可能已经画了，
                // 必须收掉）。
                //
                // ⚠️ 这里踩过一次坑：先前「持有但没起来」直接 return true 吃掉抬手，
                // 于是**浮层收不掉** —— 产品经理报的原话就是「松开应该直接结束状态」。
                // 根因是把「持有（抢下了 DOWN）」与「起来过（过了严格闸门）」混为一谈了：
                // 抢 DOWN 是所有权，跟闸门无关；抢了就必须负责结束。
                val ownedButIdle = owns && !active
                owns = false
                if (ownedButIdle) {
                    policy.requestCancel()
                    gesture = null
                    gestureTrigger = null
                    hasLast = false
                    return true
                }
                val g = gesture
                val idx = selected
                val wasActive = active
                active = false
                selected = -1
                gesture = null
                gestureTrigger = null
                hasLast = false
                if (!wasActive) return false
                policy.endGesture()
                // 只写引用，不分配
                SideIndex.lastLetter = g?.letters?.getOrNull(idx)
                // 命中测试和启动都在主线程做（启动是跨进程调用）
                policy.requestCommit(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // 同 ACTION_UP：起来过的手势要走完整的收尾（dismiss），不能提前返回
                // 这次手势是不是归我们（边缘优先抢下了 DOWN）——**只要归我们，抬手必须负责收尾**：
                // 起来过 → 走下面的 commit（可能拉起应用）；没起来 → cancel（条/面板可能已经画了，
                // 必须收掉）。
                //
                // ⚠️ 这里踩过一次坑：先前「持有但没起来」直接 return true 吃掉抬手，
                // 于是**浮层收不掉** —— 产品经理报的原话就是「松开应该直接结束状态」。
                // 根因是把「持有（抢下了 DOWN）」与「起来过（过了严格闸门）」混为一谈了：
                // 抢 DOWN 是所有权，跟闸门无关；抢了就必须负责结束。
                val ownedButIdle = owns && !active
                owns = false
                if (ownedButIdle) {
                    policy.requestCancel()
                    gesture = null
                    gestureTrigger = null
                    hasLast = false
                    return true
                }
                val wasActive = active
                active = false
                selected = -1
                gesture = null
                gestureTrigger = null
                hasLast = false
                if (!wasActive) return false
                policy.endGesture()
                policy.requestCancel()
                return true
            }
        }
        return false
    }

    /** 卸载时把状态和浮层都收干净，可从任何线程调。 */
    fun release() {
        active = false
        selected = -1
        gesture = null
        gestureTrigger = null
        hasLast = false
        if (policy.gestureActive) policy.endGesture()
        policy.stop()
        overlay.release()
    }
}

/**
 * 抽屉门控（需求 #8：抽屉打开时不弹侧滑索引）。
 *
 * ## 为什么需要它，以及判据从哪来
 *
 * 抽屉是 Flutter 渲染的，桌面进程里一个 dex 都没有；所有纯读取的外部信号
 * （窗口 / SurfaceFlinger layer / 任务栈 / Settings / 广播 / provider / gfxinfo）
 * 在真机上都实测为**零**。
 * 唯一确定性的判据是 **WMS 侧的窗口属性**：抽屉打开时桌面主窗口把
 * `softInputMode` 从 `adjust=nothing`（0x33）改成 `adjust=resize`（0x10）。
 *
 * 这个属性只有 WMS 认，所以链条是：
 *
 * ```
 * system_server（LauncherDrawerObserver：hook WMS#relayoutWindow，只读参数）
 *        ↓ 广播（定向 com.android.systemui，要 android.permission.DUMP）
 * SystemUI 主线程（LauncherDrawerTracker：写 @Volatile）
 *        ↓ 只读
 * 输入分发线程（[blocks]）与主线程轮询（[SideIndex.applyScopeGate]）
 * ```
 *
 * ## 三个线程各自只读一个 volatile
 *
 * | 调用方 | 线程 | 读什么 |
 * | --- | --- | --- |
 * | [SideIndex.applyScopeGate] | 主线程（250ms 轮询） | [blocks] —— 决定把手可见性与那条日志 |
 * | [SideIndexDetector.onTouch] | 输入分发线程 | [blocks] —— 手势真正生效的判据 |
 *
 * 输入线程**不**等轮询：轮询最多滞后 250ms，而「刚划开抽屉就立刻从右边缘划」
 * 正是这条需求最典型的场景（抽屉自带的 A-Z 索引条就贴在右边缘）。[blocks] 是
 * 纯内存读（一个 volatile + 一次 `uptimeMillis`），仍满足输入线程的纪律。
 *
 * ## 优雅降级（方向不能反）
 *
 * 通道拿不到信号（观察器没装上 / 接收器注册失败 / 心跳超时）→ [blocks] 恒为 false
 * → 行为与**未安装模块**完全一致（抽屉里照弹）。通道状态由 `LauncherDrawerTracker.describe()`
 * 报进安装报告的 `drawer=` 字段 —— 这条信号失效的表现是静默的，报告里那个字段是
 * 唯一看得见它的地方。
 */
internal object SideIndexDrawerGate {

    /**
     * 门控开关（`side_index.drawer_gate`）当前是否打开。
     *
     * 单独一个 volatile 而不是每次去读 prefs：读 prefs 是跨进程（remote prefs），
     * 而 [blocks] 跑在输入线程上。它在 [start] 里算一次，之后不变。
     */
    @Volatile
    private var enabled = false

    /** 一次性的「键没声明」记号，进安装报告用。**只在 [start] 里写**。 */
    @Volatile
    private var readingNote: String = ""

    /**
     * 启动信号通道。**主线程/安装线程调**（它会注册广播接收器，绝不能在输入线程上做）。
     *
     * @return 进安装报告 `drawer=` 的那一段（含「键未声明」的记号）
     */
    fun start(ctx: HookContext, context: Context): String {
        val key = Keys.pref(SideIndex.spec.key, DrawerSignal.DRAWER_GATE_PREF)
        val on = ctx.prefs.rawBoolean(key, DrawerSignal.DEFAULT_DRAWER_GATE)
        enabled = on
        readingNote = if (declaredInCatalog(key)) {
            ""
        } else {
            // 与本文件其余「键还没接线」的读法同一条纪律：照常工作，但**必须报出来**
            "(未声明,用默认值:$key=${DrawerSignal.DEFAULT_DRAWER_GATE})"
        }
        if (!on) {
            // 关态 = 连接收器都不注册（规范 §9：关态不是装一个 no-op，而是根本不装）
            ctx.log(Log.INFO, "侧滑索引：抽屉门控已关闭（$key=false），不注册接收器")
            return "drawer=off(开关关闭)$readingNote"
        }
        if (!declaredInCatalog(key)) {
            ctx.log(
                Log.INFO,
                "侧滑索引：$key 未在 Catalog 里声明，按默认值（${DrawerSignal.DEFAULT_DRAWER_GATE}）启用抽屉门控$readingNote",
            )
        }
        val channel = LauncherDrawerTracker.start(context, ctx)
        // 第二条信号：桌面进程内的 native hook（抽屉 + 文件夹）写下的共享文件。
        // 两条并行、任一为真即抑制 —— 前者是「从外面猜」，后者是「桌面自己报」。
        val native = LauncherNativeOverlayTracker.start(ctx)
        return "$channel|$native" + readingNote
    }

    /**
     * 这套门控现在拦不拦手势。
     *
     * **输入分发线程只读这个**：`enabled` 与两个 tracker 内部的 volatile，
     * 加上一次单调钟取时（只在「开着且信号新鲜」时才走到）。
     *
     * 两条信号并行（两条都是 fail-closed 到「不抑制」）：
     *  - [LauncherDrawerTracker]：system_server 侧观察 WMS `softInputMode`（从外面猜）；
     *  - [LauncherNativeOverlayTracker]：桌面进程内 hook 到 `notifyDrawerOpen/Close` 与
     *    `notifyFolderOpen/Close` 后写下的共享文件（桌面自己报，抽屉与文件夹都覆盖）。
     */
    val blocks: Boolean
        get() = enabled && (
            LauncherDrawerTracker.isDrawerSuppressed() ||
                LauncherNativeOverlayTracker.isOverlaySuppressed()
            )

    /** 关态清理：注销接收器 + 把状态清回「未知」。可从任何线程调，重复调安全。 */
    fun stop() {
        enabled = false
        readingNote = ""
        LauncherDrawerTracker.stop()
        LauncherNativeOverlayTracker.stop()
    }

    /**
     * 这个键在 `Catalog` 里声明了吗。
     *
     * 只看**键**不看类型：声明成什么类型不是读取方要关心的事（与 `LookReader.iconPack`
     * 的取法一致），这里只用来决定要不要报「未声明,用默认值」。
     */
    private fun declaredInCatalog(key: String): Boolean = try {
        Catalog.find(SideIndex.spec.key)?.prefs?.any { it.key == key } == true
    } catch (_: Throwable) {
        false
    }
}

/**
 * 生效范围的判据（配置 `side_index.scope`）。
 *
 * ## 参考项目是怎么做的
 *
 * Appindex 的「范围」是**按包名的白名单**：`AppScopeSettingsActivity` 里勾选允许
 * 呼出边缘触发的应用（默认只勾桌面），判定在
 * `kotlin/com/appindex/AppScopeUiModel.kt` 的 `scopeAllowsForegroundApp`：
 *
 * ```kotlin
 * if (!usageAccessGranted) return false
 * if (foregroundPackage.isEmpty()) return false
 * return foregroundPackage in enabledPackages
 * ```
 *
 * 它的配置面是一个**包名多选**（`enabledPackages: Set<String>`），
 * `foregroundPackage in enabledPackages` 就是全部判定。
 *
 * 我们当初把它压成三档 `Choice`，是因为那时还没有「应用多选」这个参数类型可用。
 * 现在 `PrefSpec.AppList` 已经有了，所以本批（1️⃣）把两边的**折中**做完整：
 *
 * | 我们的取值 | 含义 | 等价的白名单 |
 * | --- | --- | --- |
 * | `launcher_only`（默认） | 只在桌面 | 用户配的桌面包名集合（没配过 = 系统解析出来的桌面）|
 * | `anywhere` | 全部应用（也就不必看前台是谁） | 全部 |
 * | `exclude_launcher` | 除桌面以外的全部 | 除上面那个集合以外的全部 |
 *
 * **三档保留、不删**：它是用户已经能理解的概念（「哪儿生效」是一个问题，
 * 「哪些包算桌面」是另一个问题）—— 本批只把后者的答案从常量换成集合。
 *
 * **照抄了它的一条严格性**：拿不到前台包名时不放行（原文第二条 `return false`）。
 *
 * ## 照抄的第二条策略：过渡面不改结论
 *
 * [isTransientSystemSurface] 来自 Appindex 的 `kotlin/com/appindex/ForegroundAppGatePolicy.kt`：
 *
 * ```kotlin
 * private val transientSystemSurfaces = setOf("com.xiaomi.subscreencenter")
 * ```
 *
 * 设备实测（小米 17 Pro / HyperOS 4）本机在桌面时，`getTopVisibleTask` 报上来的
 * 前台包就是 `com.xiaomi.subscreencenter` —— 后置副屏的那个桌面。参考项目对它
 * **不做判定**（`refreshForegroundAppGate` 里先 return，连结论都不改），
 * 理由写得很清楚：它是个过渡性的系统面，不是用户应用，绝不能因此把边缘触发条收起来。
 * 见 [SideIndex.applyScopeGate]。
 *
 * ## 本批 1️⃣ 之后：判据搬到了 `core/LauncherScope`，这里只剩「取数据」
 *
 * 三档语义一个字都没改，但「什么算桌面」不再是写死的那个布尔 —— 它现在是
 * **用户可配的包名集合**（`side_index.launcher_packages`）优先、没配过时退回
 * [SystemUiEnv.ForegroundTracker.isLauncherForeground]（= 改动前的行为）。
 *
 * 主 / 辅 / 退化三条路径、以及为什么「拿不到前台包名一律不放行」排在 `anywhere` 前面，
 * 全部写在 `core/LauncherScope` 的类注释里，并有 `LauncherScopeTest` 的 12 个用例钉住。
 * **这一版把那段判定搬出去，就是为了让它能被测试** —— 原来它写死在两个进程各一份的
 * hook 代码里，而「配了却不生效」这类问题恰恰都出在那几行的关系上。
 *
 * 本对象现在只负责一件事：**把 SystemUiEnv 那个数据源接上去**
 *（`ForegroundTracker.isLauncherForeground()` 是一次 volatile 读，纯内存、没有 IPC），
 * 所以它在输入分发线程上也安全（实际调用它的是主线程的轮询）。
 *
 * ## 退化（本批重新审视过的一条）
 *
 * 没配过 + 桌面包名解析失败 → 那个布尔恒为 false → `launcher_only` **一律不放行**、
 * `exclude_launcher` 退化成 `anywhere`。方向是有意的（见 `core/LauncherScope`），
 * 而且**现在看得见**：安装报告有 `launchers=auto`，`applyScopeGate` 那条日志有
 * `launchers=auto`。以前这两个地方都没有，只能靠读 KDoc。
 */
internal object SideIndexScope {
    const val LAUNCHER_ONLY = LauncherScope.LAUNCHER_ONLY
    const val ANYWHERE = LauncherScope.ANYWHERE
    const val EXCLUDE_LAUNCHER = LauncherScope.EXCLUDE_LAUNCHER

    /** 这个前台包是不是「不该参与判定」的过渡性系统面。 */
    fun isTransientSystemSurface(packageName: String?): Boolean =
        LauncherScope.isTransientSystemSurface(packageName)

    /** 生效集合的一句话描述（`auto` = 用户没配过）。透传 `core` 那一份。 */
    fun describeLaunchers(configured: Set<String>): String =
        LauncherScope.describeLaunchers(configured)

    /**
     * 该不该放行。
     *
     * @param foreground 前台包名，来自 [SystemUiEnv.ForegroundTracker.foreground]
     * @param configuredLaunchers 用户配的桌面包名集合（空集 = 没配过）
     */
    fun allows(scope: String, foreground: String?, configuredLaunchers: Set<String>): Boolean =
        LauncherScope.allows(
            scope = scope,
            foreground = foreground,
            configuredLaunchers = configuredLaunchers,
            // 只在用户没配过时参与判定（`core/LauncherScope` 的主/辅/退化那一节）
            resolvedHomeIsForeground = SystemUiEnv.ForegroundTracker.isLauncherForeground(),
        )
}
