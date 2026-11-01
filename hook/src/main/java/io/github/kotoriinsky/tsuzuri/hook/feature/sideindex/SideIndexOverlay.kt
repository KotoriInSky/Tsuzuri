package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import io.github.kotoriinsky.tsuzuri.core.DisplayNameOverrides

/**
 * 侧滑索引的浮层：**一个窗口、四层内容**。
 *
 * ```
 * FrameLayout (root, 全屏, FLAG_NOT_TOUCHABLE)
 *  ├─ dim          全屏渐变遮罩
 *  ├─ bar          字母条（28dp 宽，贴起始边，按 geometry 定位）
 *  ├─ panelGroup   应用面板容器（整体跟手指平移）
 *  │   ├ panelScroll 视口：固定 1–3 行高（Flyme 的 ScrollView）
 *  │   │   └ column  内容：若干行，行高 = 格子高
 *  │   └ thumb       细条：4dp × 184dp，只在内容超过视口时出现
 *  └─ bubble       50dp 方形大字母，浮在面板上沿外侧
 * ```
 *
 * ## 为什么是原生 View 而不是 Compose
 *
 * 这段代码跑在 **SystemUI 进程**里。往系统进程带 Compose 运行时是明确的红线
 *：给一个常驻系统进程加上几 MB 的类加载和一整套
 * recomposition 机制，换来的只是十几个方块。原生 View 够用且没有这个风险。
 *
 * ## 为什么窗口不可触摸（和参考项目不同的一处关键改动）
 *
 * Appindex 的浮窗是**可触摸**的：它的触发条是个真实的 View，手指按在上面，
 * 触摸流归它。我们不行 —— 我们的触摸来自 WMShell 的输入监视通道
 * （[io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SwipeEventHub]），再加一个
 * 能吃触摸的窗口就是**同一条边缘上的第二个 touch owner**。
 *
 * 所以这里整个窗口 `FLAG_NOT_TOUCHABLE`：它**只是画**。手指在哪、落在哪个图标上，
 * 由对 [itemRects] 的命中测试算出来 —— 这正是 Appindex 的
 * `findHitAppItem(rawX, rawY)` 干的事，只不过它顺带用了触摸回调，
 * 我们把它单独拎出来用而已。
 *
 * **这条约束对面板的滚动有直接影响**（见 [scrollTick]）：Flyme 的自动滚动是
 * 一个后台线程 + `ScrollView.scrollBy`，而那个 ScrollView 在 Flyme 里是**能被手指滚的**；
 * 我们的 ScrollView 永远收不到触摸事件，所以「手指驱动」这一步必须由我们自己的
 * 判定/渲染链路喂给它 —— 手指 y 从输入线程经 [SlidePerformancePolicy] 送到主线程，
 * 主线程再按 Flyme 的规则决定滚不滚。**不能依赖 View 的触摸**。
 *
 * ## 线程纪律（最重要的一条）
 *
 * 所有 public 方法都**只允许在主线程**调：`addView` / `removeViewImmediate` 是
 * ViewRootImpl 的操作，从别的线程调会把整棵 View 树挂到那个线程上，
 * 后续任何主线程交互都会出问题，而且往往延迟暴露、极难定位。
 * 调用方是 [SlidePerformancePolicy] 里的主线程 Handler。这里再兜一道：
 * 不在主线程就只记一条错误日志并返回，绝不继续往下走 —— 这条路径宁可什么都不做，
 * 也不能把宿主的窗口系统弄脏。
 */
internal class SideIndexOverlay(
    private val context: Context,
    /** 个性化参数（字母条位置/大小、图标大小/圆角、面板边距…），安装时读一次。 */
    private val look: SideIndexLook,
    /** 振动总开关，配置 `side_index.haptic`。 */
    private val hapticEnabled: Boolean,
    /**
     * 振动强度百分比，配置 `side_index.haptic_strength`。
     *
     * ⚠️ **已废弃，但必须继续传**：它现在只作为老值的迁移输入
     * （[SideIndexHaptics.modeFromLegacyStrength]，见 [hapticMode]）。
     * 振幅百分比在规范实现里没有对应的 API，所以 `side_index.haptic_strength`
     * 这个滑块应当从设置界面撤掉 —— 撤掉前它仍是「老用户值」的来源。
     * 删掉这个参数要同时改 [SideIndex.install]，那不在本次改动范围内。
     */
    private val hapticStrengthPercent: Int,
    /**
     * 振动时长（毫秒），配置 `side_index.haptic_duration`。
     *
     * ⚠️ **已废弃，且不再参与任何决策**（规范明确要求：有预定义效果时不要自己拼
     * duration + amplitude；`View` 常量与预定义效果都不吃时长）。
     * 之所以还留着这个参数：删掉它要同时改 [SideIndex.install] 的调用点，
     * 而本次改动只允许碰振动相关的这几行。**它现在被刻意忽略**。
     */
    private val hapticDurationMs: Int,
    /** 用户是否要模糊，配置 `side_index.blur`。真生效还要看系统支持（见 [crossWindowBlurEnabled]）。 */
    private val blurRequested: Boolean,
    /**
     * 新参数面的原始值，配置 `side_index.haptic_mode`。**容错读法**：
     * 键还没在 core/Catalog 里声明时传 null（或读到空串），此时
     * [SideIndexHaptics.resolveMode] 会回落到老键迁移，行为不退化。
     *
     * 默认值是 null 而不是某个档位，这是刻意的：「键不存在」与「键存在且等于默认档」
     * 在新实现里的行为**恰好一致**（都是默认档），所以这个默认值不会掩盖接线遗漏 ——
     * 但接线遗漏仍应被 [SideIndex] 的安装报告列出来（键名见 [SideIndexHaptics.KEY_MODE]）。
     */
    private val hapticModeKey: String? = null,
    /**
     * 图标包包名（配置 `side_index.icon_pack`，需求 #5）。null / 空 = 不套用。
     *
     * 面板图标优先用图标包里的那张（[SideIndexIconPack]，内部按包名缓存）；
     * 没命中、包被卸载、解析失败一律退回应用自带图标 —— 与扇形那一边同一套回退链。
     */
    private val iconPack: String? = null,
    /**
     * 单应用**显示名覆盖**（配置 `side_index.name_overrides`，本批 3️⃣ 前半）。
     * 空表 = 不覆盖（面板上就是应用自己的名字 = 改动前的形态）。
     *
     * 在 [bindCell] 那一刻查一次表（一次 HashMap 查，零 IPC），所以它虽然是构造参数，
     * 成本与「名字的填充」本身是同一量级。**必须是构造参数**：浮层对象跨手势复用，
     * 热应用靠换代（[SideIndexConfig.windowDiffersFrom]）—— 见那个函数的注释。
     */
    private val nameOverrides: Map<String, String> = emptyMap(),
    /**
     * 单应用**图标覆盖**（配置 `side_index.icon_overrides`，本批 3️⃣ 后半）。
     *
     * 优先级链（与扇形那边同一套，见 [SideIndexIconOverride] 的类注释）：
     * **单项覆盖 → 整体图标包（[iconPack]）→ 自带图标**。
     * 解析与缓存都在 [SideIndexIconOverride] 里，这里只做三行分派。
     */
    private val iconOverrides: SideIndexIconOverride.Set = SideIndexIconOverride.EMPTY,
) {

    /**
     * 系统是否开着「跨窗口模糊」。
     *
     * 由 [SideIndex] 在安装时探一次（对应 Appindex 的 `configureBlurBehavior()`），
     * 之后只读。为什么探测放在外面：`WindowManager.isCrossWindowBlurEnabled()`
     * 首次调用会在内部注册一个监听器（一次 Binder 调用），而这里的所有窗口操作
     * 都要求在主线程，探测却要赶在 install() 返回前拿到结果写进诊断报告。
     * `@Volatile`：写在安装线程，读在主线程。
     */
    @Volatile
    var crossWindowBlurEnabled: Boolean = false

    /** 这次的浮层是否真的加模糊：用户要 + 系统给。 */
    private val blurActive: Boolean get() = blurRequested && crossWindowBlurEnabled

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    /**
     * 条的几何：**输入线程在 ACTION_DOWN 直接读它**。
     *
     * 所以它必须是 `@Volatile` + 内容不可变，而且只在主线程写。
     * 见 [AppIndex.BarLayout] 上关于「判定与绘制共用同一组数字」的说明。
     */
    @Volatile
    var layout: AppIndex.BarLayout? = null
        private set

    /**
     * 已经卸载了。
     *
     * 卸掉之后可能还有一两个已经排进主线程队列的渲染请求（`removeCallbacks` 摘不掉
     * 已经在跑的那个），没有这道闸门它们会把窗口重新建出来 —— 那就正好违反
     * 「关态必须与未安装模块完全一致」。
     */
    @Volatile
    private var released = false

    private var root: FrameLayout? = null
    private var bar: LinearLayout? = null

    /**
     * 大字母气泡的**容器**（需求 #10 起它是 `FrameLayout` 而不是 `TextView`）。
     *
     * 为什么改成容器：Niagara 的 handle 能显示两种内容 —— 普通分组用字母
     * （`canvas.drawText`）、**特殊分组用 drawable**（`ScrollbarHandle.onDraw` 的
     * `xwUvHiItEbAP` 分支）。一个 `TextView` 表达不了「这里是个图标」，
     * 所以拆成「容器（负责窗口位置、圆底、描边、按压淡入）+ 两种内容二选一」。
     * 好处是按压动画只作用在容器上，两种内容天然一起淡入。
     */
    private var bubble: FrameLayout? = null

    /** 气泡里的文字（普通分组）。 */
    private var bubbleText: TextView? = null

    /** 气泡里的图标（特殊槽位，见 [SideIndexLook.bubbleIconSlots]）。 */
    private var bubbleIcon: ImageView? = null

    /**
     * 气泡上一次的可见性。
     *
     * Niagara 的按压动画**只在「从不可见变可见」那一刻**启动
     * （`ScrollbarHandle.setCurrentSection`：`mPrevious == null → setVisibility(0) + start()`），
     * 而不是每次换字母都放一遍 —— 换字母是连续的，每次都淡入会闪成一串。
     */
    private var bubbleWasVisible = false

    /** 面板容器：跟手指平移的就是它（Flyme 的 `ASAppGroupLayout`）。 */
    private var panel: FrameLayout? = null

    /** 液态玻璃：本次手势开始时截的屏（折射底图），和挂上它的折射层。收窗时一起清。 */
    private var liquidBackdrop: android.graphics.Bitmap? = null
    private var liquidLayer: SideIndexLiquidLayer? = null

    /** 视口。固定 1–3 行高，内容超出时由 [scrollTick] 滚动。 */
    private var panelScroll: ScrollView? = null

    /** 内容列（若干行）。 */
    private var panelColumn: LinearLayout? = null

    /** 细条。 */
    private var thumb: View? = null

    /** 面板占位内容（加载/空态）用的那一个 TextView，复用一个，避免每次重建。 */
    private var placeholderView: TextView? = null

    /**
     * 面板内容的三态占位，对应 [AppIndex.Result]（见 [setPlaceholder]）。
     *
     * [AppIndex.Result.Unavailable] **不在**这里：那是「不知道有没有应用」，
     * 此时连几何都不发布（手势完全不响应），不该画一个假装有内容的空面板。
     */
    private var placeholder = PanelPlaceholder.NONE

    /** 面板离屏幕边缘的距离（px），安装时算一次。 */
    private val panelInsetPx = look.panelInsetPx(density)

    /**
     * 视口内减掉多少底部空间（px）。
     *
     * Flyme `MzSlideAppsSearchPhoneImpl.updateBottomSpace()`：
     * `mBottomSpace = (config_navBarOpacityMode == 2) ? dpToPx(30) : 0`
     * —— 导航栏是半透明/悬浮的时候，面板底部要让它 30dp，否则最后一行会压在
     * 手势条下面。这个资源是 @hide 的 `android.integer.config_navBarOpacityMode`，
     * 所以按名字找；**找不到就退回 0**（参考项目 else 分支的行为），不是猜一个值。
     */
    private val bottomSpacePx = navBarBottomSpacePx(context)

    /**
     * 面板的格子几何（**生效列数 × 格宽 × 格高 × 图标边长**），构造期算一次。
     *
     * ## 为什么只留一个字段
     *
     * 改动前这里是 `cellW` / `cellH` / 一个 `COLUMNS` 常量三处各自算 —— 那在「列数固定 5、
     * 格宽固定 66dp」时没问题，但需求 #5c 把两者都开放之后，它们**互相约束**
     * （列数决定格宽上限、格宽下限又决定能排几列）。三个数各自算就会出现
     * 「面板按新列数排、格子还是旧格宽」这种两边都编译过的形态。
     *
     * 现在唯一来源是 [SideIndexLook.panelMetrics]：它一次解出四个数、并保证自洽
     * （规则与证明写在那个函数的 KDoc 里）。本类里凡是「面板多宽、几列、一屏几格、
     * 细条在哪」的地方**一律读这几个字段**，不再有第二处算式。
     *
     * 屏幕尺寸是构造期读的（`displayMetrics`），与真机的 `panelLeft` 用的 `l.screenW`
     * 同源；改屏幕尺寸（旋转/分屏）要新装一次才生效 —— 这是既有行为，
     * 不在本次范围内。
     */
    private val metrics = look.panelMetrics(
        density = density,
        screenW = context.resources.displayMetrics.widthPixels,
        screenH = context.resources.displayMetrics.heightPixels,
        bottomSpacePx = bottomSpacePx,
        maxPanelRows = PANEL_MAX_ROWS,
    )

    /** 生效格宽（px）。见 [metrics]。 */
    private val cellW = metrics.cellW

    /** 生效格高（px）。见 [metrics]。 */
    private val cellH = metrics.cellH

    /**
     * **生效列数**（每行几格）。见 [metrics] —— 它不是用户填的那个数：
     * 放不下时会减（`metrics.columnsClamped`），而**面板的行数、格子排布、细条显隐、
     * 滚动距离全都按这一个数算**，否则「看到的格子」与「碰到的格子」会错位。
     */
    private val columns = metrics.columns

    // ── 面板的滚动状态（全部只在主线程读写） ─────────────────────────────

    /** 当前视口高度（px）。 */
    private var panelViewportH = 0

    /** 内容可滚动的最大距离（px）。0 = 不需要滚动。 */
    private var panelMaxScroll = 0

    /** 当前滚动位置（px）。自己记账而不是问 ScrollView，细条的换算要用它。 */
    private var panelScrollY = 0

    /** 滚动方向，取值同 Flyme 的 `mDirection`：0 上滚、1 停、2 下滚。 */
    private var scrollDir = SCROLL_NONE

    /** 滚动 ticker 是否已经排队（去重，见 [ensureScrollTicker]）。 */
    private var scrollScheduled = false

    /** 最近一次已知的手指 y（屏幕绝对坐标）。面板跟手指要用它。 */
    private var fingerY = 0f

    /** 气泡是否**应该**显示（顶部空间不够时藏起来，见 [applyBubbleVisibility]）。 */
    private var bubbleShown = false

    /** 细条是否已显示 / 它的换算需要的视口高（Flyme 的 `mListViewMaxMove` 用它算）。 */
    private var thumbVisible = false
    private var thumbViewportH = 0

    /** 字母条是按哪份字母表画的。引用变了才重建（否则每换一个字母都重建 27 个 TextView）。 */
    private var barLetters: List<String>? = null
    private var barOnRight = true

    private val barItems = ArrayList<TextView>(32)

    // ── 应用面板的视图池 ────────────────────────────────────────────────
    //
    // 手指划过字母条时面板会连着重建好几次。每次都 new 出一百来个 View
    // 就是主线程上的 GC 抖动（Appindex 也是撞到这个问题之后才加的池，
    // 见它的 `showAppsForLetterPooled` / `ensureAppItems`）。

    private val rowPool = ArrayList<LinearLayout>(8)
    private val cellPool = ArrayList<Cell>(32)

    /** 当前**确实显示出来**的格子。下标与 [itemRects]、[shownEntries] 一一对应。 */
    private val shownCells = ArrayList<View>(32)
    private val shownEntries = ArrayList<AppEntry>(32)

    /**
     * 最后一次布局后的命中表。
     *
     * `itemRects` 和 `itemEntries` **必须是同一时刻的一对**：面板是「模型先变、位置后变」的
     * （`shownEntries` 在重建时就换了，矩形要等一次布局遍历才更新），
     * 只按矩形下标去查当时的应用列表，就可能启动一个和用户看到的图标不一样的应用。
     * 成对记录之后，最坏情况是「命中上一次布局时的那个应用」——恰恰是用户屏幕上看到的那个。
     *
     * ⚠️ 面板会**滚动**（[scrollPanelBy]）和**跟手指平移**（[followFinger]），
     * 这两种位移都**不会**触发一次新的布局遍历，所以矩形表必须由我们手动跟着挪
     * （[shiftItemRects]）。漏掉任何一条，「看到的格子」和「碰到的格子」就会错位。
     */
    private var itemRects: List<Rect> = emptyList()
    private var itemEntries: List<AppEntry> = emptyList()

    /**
     * 每个格子的**内容块**视图（不是格子根）—— 高亮抬起作用在它上面（见 [Cell]）。
     *
     * ⚠️ 与 [itemRects] 成对但**不同层**：矩形记的是格子根（命中判定要的是格子），
     * 抬起动的是内容块。改动前抬起动的是格子根，于是「抬起状态下若恰好录了一次矩形」
     * 会把命中的格子整体上移 18dp（一个隐蔽的错位）；现在矩形永远来自没被抬起过的根 ✓。
     */
    private var itemContents: List<View> = emptyList()

    /**
     * 是否已经挂着一个「等布局后录几何」的监听（见 [buildPanel] 尾部）。
     * 只允许一个：换字母快于布局节拍时，重复挂会在下一次布局里一起触发一堆
     * 重复的 `recordRects`（每次都是 N 次跨 Binder 的视图位置查询）。
     */
    private var rectsListenerPending = false

    private var shownSlot = -1
    private var shownKey = -1
    private var highlighted = -1

    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var lastHapticAt = -1L

    /**
     * 这次安装实际生效的档位。**装一次算一次**（和 [SideIndexLook] 同一个纪律：
     * 触觉参数在一次安装内不可变）。
     *
     * 三个输入分别是老键总开关、新键原始值、老键强度百分比 —— 优先级与回落在
     * [SideIndexHaptics.resolveMode] 里，这里不重复一份判断。
     */
    internal val hapticMode = SideIndexHaptics.resolveMode(
        hapticEnabled,
        hapticModeKey,
        hapticStrengthPercent,
    )

    /**
     * 设备能力，**一次安装探一次**。
     *
     * 为什么不在每次振动时探：这些都是 Binder 调用（`areAllEffectsSupported` /
     * `arePrimitivesSupported` / `hasAmplitudeControl` 都跨进程），而 [tick] 跑在
     * 跟随手指的路径上。这也符合 [SlidePerformancePolicy] 上「输入路径零分配、
     * 不做 IPC」那条纪律。
     *
     * 探测本身包了 `runCatching`：厂商 ROM 上这些方法可能抛（或者返回垃圾），
     * 拿不到就当「不支持」，于是这条路自动回落到 `View` 常量那条**不需要探测**的路 ——
     * 而不是「探测失败 = 没有反馈」。
     */
    internal val hapticDevice: SideIndexHaptics.DeviceSupport = probeHaptics(vibrator)

    /** 格子高亮用的插值器，取自 Flyme `ASAppGroupSingleAppLayout` 的 `mUpAnimator`。 */
    private val selectInterpolator = PathInterpolator(0.3f, 0f, 0.67f, 1f)

    /** 浮层淡入用的插值器与时长，取自 Flyme `ASearchLayout.animateShow()`。 */
    private val showInterpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f)

    /**
     * 一个应用格子。
     *
     * ## 为什么是三层而不是两层（照 Flyme 的 `layout_assearch_appgroup_single_app.xml`）
     *
     * ```
     * root    (LinearLayout, 66×cellH, gravity = CENTER ← 内容块在格里居中)   ← 命中表记的是它
     *  └ content (LinearLayout, wrap_content)                              ← 高亮抬起动的是它
     *      ├ icon (50dp)
     *      └ name (12sp)
     * ```
     *
     * 真机就是「格子（`gravity=CENTER`）里装一个 wrap_content 的内容块，抬起动画作用在
     * 内容块上」。这一层结构是「顶行高亮被裁」修复的关键：抬起必须发生在**格子内部**
     * （内容块上方有居中余量），抬整个格子则会在第一行越过视口上沿被 ScrollView 裁掉。
     *
     * [bg] 是选中兜底底色的 drawable（默认全透明）：只在抬升量算成 0 那一档才上色，
     * 见 [setHighlight]。
     */
    private class Cell(
        val root: LinearLayout,
        val content: LinearLayout,
        val icon: ImageView,
        val name: TextView,
        val bg: GradientDrawable,
    )

    /** 面板要画什么。和 [AppIndex.Result] 的三态一一对应（Unavailable 不画，见类注释）。 */
    internal enum class PanelPlaceholder { NONE, LOADING, EMPTY }

    // ── 几何发布 ──────────────────────────────────────────────────────

    /**
     * 重算并发布条的几何（不画任何东西）。**主线程**。
     *
     * 由 [SideIndex] 在索引就绪、以及「最近使用」变化时调用 —— 输入线程
     * 在 ACTION_DOWN 只读 [layout]，它自己要能拿到才可能起手势。
     * 如果几何只在我们真的画条时才发布，那第一个手势永远是空转。
     *
     * 也算清了字母条的**纵向位置与整体大小**（Appindex 的两个个性化项，
     * 默认值取 Flyme 的居中 / 原尺寸，见 [AppIndex.barLayout]）。
     */
    fun publishLayout(letters: List<String>) {
        if (released) return
        if (!onMain("publishLayout")) return
        layout = AppIndex.barLayout(
            widthPx = context.resources.displayMetrics.widthPixels,
            heightPx = context.resources.displayMetrics.heightPixels,
            density = density,
            letters = letters,
            offsetFraction = look.letterOffsetFraction,
            scale = look.letterScale,
        )
    }

    /**
     * 收回几何。**主线程**。
     *
     * 用在「索引查询本身失败」（[AppIndex.Result.Unavailable]）这一支：没有几何，
     * 输入线程的 ACTION_DOWN 直接 `return false`，手势完全不响应 ——
     * 「不知道有没有应用」和「没有应用」必须在行为上分得开，
     * 这是三态纪律在浮层这一侧的落地。
     */
    fun clearLayout() {
        if (!onMain("clearLayout")) return
        layout = null
    }

    /**
     * 设置面板的占位内容（加载中 / 没有可启动应用）。**主线程**。
     *
     * 逐行对应 Appindex 的 `AppListLoadPolicy.shouldShowLoadingState`：
     * 字母已经选中、但应用清单还没扫完时，它不显示空白而是显示「读取应用中…」
     * （`showAppListLoadingState`）。这样用户看到的是「在准备」而不是「坏了」。
     */
    fun setPlaceholder(p: PanelPlaceholder) {
        if (!onMain("setPlaceholder")) return
        placeholder = p
    }

    /**
     * 格子几何的**生效值**，进安装报告与安装日志（需求 #5c）。
     *
     * ## 为什么必须有这一条
     *
     * 列数与格宽都是**联动夹取**过的（见 [metrics]），所以「用户配的」与「真正生效的」
     * 可以不同。只打用户配的那两个数，表现是「我拖了列数、界面上的数字也变了、
     * 但划开一看还是 5 列」—— 一个只能靠数格子发现的偏差。
     * 两个数一起打（`cols=6/7(夹)`）就能一眼看出「不是没生效，是放不下」。
     *
     * **任何线程可调**（纯读一个构造期算好的不可变对象），所以安装报告那条
     * 「框架线程上拼 notes」的路径也能直接调。
     */
    fun geometryNote(): String = metrics.describe()

    // ── 手势中的渲染 ──────────────────────────────────────────────────

    /**
     * **「现在有没有手势在进行」的取值入口**（唯一真相源 = `SlidePerformancePolicy.gestureActive`，
     * 由**输入线程**在起手/抬手那一刻同步置位的 volatile）。
     *
     * ## 它堵的是什么（2026-09-15，用户报的「图标飘到状态栏」）
     *
     * 面板的渲染是**延后一帧合并**的（`SlidePerformancePolicy.letterTick`，+16ms），
     * 而抬手走的是立刻执行的路：`endGesture()` → `commit()` → [dismiss]。
     * 抬手若落在那个 16ms 窗口里，延迟的渲染才姗姗来迟 —— 而 [show] 会
     * `ensureWindow()` 把**已经拆掉的窗口重新建出来**，[buildPanel] 再用
     * `fingerY`（[dismiss] 已经把它清成 0）算位置：`followerTop(0)` 恰好是 0，
     * 于是整块面板被摆到**屏幕最顶端**（压着状态栏），气泡算出来是负值所以看不见。
     * 现场截图 `research/captures/xiaomi-20260915-085456.png`，复现手法见
     * `tools/repro-side-index-zombie.sh`（快速划过字母条 + 立刻松手）。
     *
     * 「抬手时作废欠着的渲染」那一条已经在策略层修了；这里是**边界层的兜底**，
     * 让将来任何迟到的主线程任务都复活不了窗口。
     *
     * ## 为什么是「读一个 volatile」而不是「主线程回调里维护一个布尔」
     *
     * 第一版就是布尔（`onGesture` 回调置位），真机一测就发现它**会误伤下一次手势的第一帧**：
     * 那条回调本身也是主线程任务，排在 `letterTick` 后面时，新手势的正常渲染会被判成
     * 「迟到的」而丢掉（实测日志里出现过 2 次这种误拒）。而 `gestureActive` 是起手那一刻
     * 在**输入线程**上同步写下的，于是「新手势已经开始」这件事永远早于它的任何渲染请求。
     *
     * 没接线时按「没有手势」处理（fail-closed）：宁可不画，也不画到屏幕顶端。
     */
    @Volatile
    private var gestureAliveSource: (() -> Boolean)? = null

    /** 接线。**框架线程可调**（安装/换代时各调一次），读的那一侧只在主线程读。 */
    fun setGestureAliveSource(src: () -> Boolean) {
        gestureAliveSource = src
    }

    private fun gestureAlive(): Boolean = gestureAliveSource?.invoke() == true

    /**
     * 被 [gestureAlive] 闸门挡下的迟到渲染次数（诊断）。
     *
     * 存在的理由与 `SideIndexDetector.note` 同源：这条竞态**没有可观察的产物** ——
     * 修好之后的表现是「什么都没发生」，而「什么都没发生」既可能是修好了，
     * 也可能是这次没撞上。限次（[LATE_RENDER_LOG_MAX]）的理由也一样：
     * 既不能让连试十几次的人误判成「没撞上」，也不能让判定 bug 淹掉 logcat。
     */
    private var lateRenderBlocked = 0

    /**
     * 渲染某个槽位：必要时建窗口、画字母条，然后换面板。**主线程**。
     *
     * @param index 应用快照。**允许为 null** —— 那就是「清单还没扫完」，
     *   此时按 [placeholder] 画加载/空态（Appindex 的 loading 分支就是这么做的）。
     * @param slot 命中槽位，`0 until layout.letters.size`
     */
    fun show(index: AppIndex.Index?, onRight: Boolean, slot: Int) {
        // 迟到的渲染：这次手势已经结束，绝不能把窗口又建回来（见 [gestureAliveSource]）
        if (!gestureAlive()) {
            if (lateRenderBlocked < LATE_RENDER_LOG_MAX) {
                lateRenderBlocked++
                Log.i(TAG, "渲染被拒：没有手势在进行（迟到的合并渲染，共 " + lateRenderBlocked + " 次）")
            }
            return
        }
        val l = layoutFor(index, onRight) ?: return
        val root = ensureWindow() ?: return

        if (bar == null || barLetters !== l.letters || barOnRight != onRight) {
            buildBar(l, onRight)
            barLetters = l.letters
            barOnRight = onRight
        }
        highlightBarItem(l, slot)

        // 内容变了才重建面板：同一个字母上挪动手指不该重建视图。
        // 「内容」包括占位态 —— 清单在手指按着的时候扫完（loading → 真实应用）
        // 也必须重画，否则用户会一直停在「读取应用中…」上。
        val key = panelKey(index)
        if (slot != shownSlot || key != shownKey || panel == null) {
            buildPanel(index, l, slot, onRight)
            shownSlot = slot
            shownKey = key
            // 换字母：语义是「顺着刻度条换了一格」（见 SideIndexHaptics 的语义表）
            tick(SideIndexHaptics.Trigger.LETTER)
        }
    }

    /** 当前面板该画什么。`null` 槽位（清单没就绪）时看占位态。 */
    private fun panelKey(index: AppIndex.Index?): Int =
        if (index == null) placeholder.ordinal + 1 else 0

    /**
     * 手指位置更新。**主线程**。
     *
     * 两件事，分别对应 Flyme `ASLetterBarView.touch()` 的两个分支：
     *
     * ```
     * if (mRect.contains(x, y)) {          // 手指在字母条上
     *     mCallBack.touchYChange(...);     //   → 面板跟手指（translationY）
     *     ... 换字母（30° 滤波之后）
     * } else {
     *     mCallBack.selectApp(rawX, rawY); //   → 命中高亮 + 自动滚动方向
     * }
     * ```
     *
     * `selectApp` 那一支在 `ASAppGroupLayout.selectApp` 里的落点是：
     * 命中格子就高亮；**没命中就 `smoothScrollDownOrUp(y)`** ——
     * 用同一根手指的位置决定列表往哪边滚。这里照做。
     *
     * 调用频率是「每个 MOVE 帧一次」，所以这两支都必须是 O(1)、零分配。
     */
    fun trackPointer(x: Float, y: Float, onRight: Boolean) {
        // 同 [show]：迟到的指针更新不许动一个已经没有手势的浮层（见 [gestureAliveSource]）
        if (!gestureAlive()) return
        val l = layout ?: return
        fingerY = y
        if (l.inBar(x, y, onRight)) {
            // 在条上：先跟手指，再清掉格子高亮（Appindex 的 MOVE 分支也是先
            // `updateAppHighlight(-1)` 再算字母）
            followFinger(y, l)
            setHighlight(-1)
            // 手指回到条上 = 明确地「不滚动」。Flyme 只在手指压到某个应用时
            // 显式调 `smoothScrollDownOrUp(-1)` 停滚；我们从「手指已经不在列表上」
            // 这个更强的判据停，避免上一条方向残留下来把列表一直滚下去。
            setScrollDirection(SCROLL_NONE)
            return
        }
        setHighlight(hitTest(x, y))
        // 不在条上：按手指相对视口的位置决定滚动方向（Flyme 的 smoothScrollDownOrUp）
        setScrollDirection(scrollDirectionFor(y))
    }

    /**
     * 抬手：手指落在哪个应用上就启动哪个，然后收窗。**主线程**。
     *
     * 注意「落在字母条上抬手」= 什么都不启动。这是 Appindex 的语义
     * （它的 UP 也是 `findHitAppItem` 命中才 `launchApp`），
     * 而且它是对的：误启动一个应用比什么都不做糟得多。
     */
    fun commit(x: Float, y: Float, onRight: Boolean) {
        if (released) return
        if (!onMain("commit")) return
        val idx = hitTest(x, y)
        val entry = if (idx in itemEntries.indices) itemEntries[idx] else null
        if (entry != null) {
            launch(entry)
            SideIndex.lastLaunched = entry.packageName
            // 抬手命中：语义是「确认启动这一个」。force = 抬手那一下必须响
            tick(SideIndexHaptics.Trigger.CONFIRM, force = true)
        }
        dismiss()
    }

    /**
     * 离开手势。**主线程**。停掉自动滚动的 ticker（Flyme `hide()` 里的
     * `stopScrollThread()`）。[dismiss] 也会调它，这里单独留一个入口是给
     * 「面板还在、但手指已经不在视口外」这类中间态用的。
     */
    private fun stopScroll() {
        scrollDir = SCROLL_NONE
        if (scrollScheduled) {
            main.removeCallbacks(scrollTick)
            scrollScheduled = false
        }
    }

    // ── 收窗 ──────────────────────────────────────────────────────────

    /** 收起浮层。**主线程**。手势结束、异常路径都走它。 */
    fun dismiss() {
        if (!onMain("dismiss")) return
        val w = wm ?: return
        stopScroll()
        root?.let { runCatching { w.removeViewImmediate(it) } }
        root = null
        liquidLayer = null
        liquidBackdrop?.recycle()
        liquidBackdrop = null
        bar = null
        bubble = null
        // 气泡的两种内容与「上次可见吗」也必须一起清：
        // 浮层对象是**跨手势复用**的（只有换代才换对象），留着旧引用等于把上一个窗口的
        // View 挂在字段上（下次 `bubbleIcon` 会指向已经被移除的旧窗口里的 ImageView）；
        // `bubbleWasVisible` 留在 true 更糟 —— 下一次手势的气泡会因为「上次就可见」
        // 而**跳过按压淡入**，表现为「第一次划有动画、之后都没有」（需求 #10）。
        bubbleText = null
        bubbleIcon = null
        bubbleWasVisible = false
        panel = null
        panelScroll = null
        panelColumn = null
        thumb = null
        placeholderView = null
        bubbleShown = false
        panelViewportH = 0
        panelMaxScroll = 0
        panelScrollY = 0
        fingerY = 0f
        thumbVisible = false
        thumbViewportH = 0
        barItems.clear()
        rowPool.clear()
        cellPool.clear()
        shownCells.clear()
        shownEntries.clear()
        itemRects = emptyList()
        itemEntries = emptyList()
        itemContents = emptyList()
        barLetters = null
        shownSlot = -1
        shownKey = -1
        highlighted = -1
    }

    /**
     * 卸载时收干净。可以**从任何线程**调（[SideIndex.uninstall] 跑在框架线程上）：
     * 不在主线程就转投主线程。
     *
     * 顺带把几何清掉 —— 清掉之后输入线程的 ACTION_DOWN 会直接 `return false`，
     * 关态就真的和「没装模块」一样了。
     */
    fun release() {
        released = true
        if (onMain("release")) {
            dismiss()
            layout = null
        } else {
            main.post {
                dismiss()
                layout = null
            }
        }
    }

    // ── 窗口 ──────────────────────────────────────────────────────────

    private fun ensureWindow(): FrameLayout? {
        root?.let { return it }
        val w = wm ?: return null
        val r = FrameLayout(context)

        // TYPE_APPLICATION_OVERLAY(2038)：和 Appindex 同一个层级，也是我们上一版用的。
        // 在 SystemUI（system uid）里这个层级不需要 SYSTEM_ALERT_WINDOW 的 AppOps 放行。
        //
        // FLAG_NOT_FOCUSABLE  不抢输入焦点（绝不弹输入法、不改 IME 目标）
        // FLAG_NOT_TOUCHABLE  整窗不吃触摸 —— 见类注释，这条是硬要求
        // FLAG_LAYOUT_IN_SCREEN + NO_LIMITS  让 topMargin/leftMargin 直接是屏幕绝对坐标，
        //                     字母条才可能贴到屏幕最边缘而不被状态栏挤下去
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // 手工添加的浮窗不会继承 Activity 的硬件加速设置；AGSL 必须显式开启。
        if (look.glassMaterial) flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        // Appindex 的 showFloatingWindow 在开模糊时做 `params.flags |= 6`
        // （FLAG_DIM_BEHIND | FLAG_BLUR_BEHIND），关窗口时 `&= -7` 清掉。
        // 我们这里不需要那对「加/清」，理由见 applyBlurStateToWindow 的注释。
        if (blurActive) {
            flags = flags or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
        // 显式声明「不吃系统栏 inset」：光有 LAYOUT_IN_SCREEN / NO_LIMITS 还不够，
        // 窗口内容默认仍会被状态栏与导航栏 inset 裁掉 —— 遮罩就盖不到状态栏。
        fitInsetsTypes = 0
            // 挖孔屏还要额外声明「允许铺进 display cutout」。
            //
            // 默认值 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT` 的语义是「非全屏语义的窗口
            // 要避开挖孔区域」，而我们有 FLAG_NOT_FOCUSABLE、并不算全屏窗口 ——
            // 于是屏幕顶端那一圈（正是状态栏所在）被内缩掉了，遮罩盖不到。
            // `fitInsetsTypes = 0` 管的是 system bar inset，**管不到 cutout**，两者是独立的。
            //
            // ⚠️ 这是**未在真机逐像素验证过的假设**（产品经理报的现象是「状态栏部分没有完全遮罩」）。
            // 若设了仍未盖住，剩下的解释是 z-order：TYPE_APPLICATION_OVERLAY(2038) 低于
            // 状态栏窗口，状态栏自己的图层永远在我们的遮罩之上 —— 那条路要换窗口类型，
            // 会引入新权限，得单独评估。
            // 验证方式：抓带状态栏区域的截图对比顶部像素，不要靠肉眼。
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            gravity = Gravity.TOP or Gravity.START
            applyBlurStateToWindow(this)
            // 触摸穿透的悬浮窗会被系统压到 alpha=0.8（扇形小窗真机实测），面板与图标整体发透
            io.github.kotoriinsky.tsuzuri.hook.feature.gesture.TrustedOverlay.mark(this)
        }

        // 液态玻璃：**在 addView 之前**截屏 —— A17 的截屏参数没有 setExcludeLayers，
        // 这一刻浮层还没被合成，截到的才是「面板背后」。
        if (look.glassMaterial) requestLiquidBackdrop(r)

        val ok = runCatching { w.addView(r, lp) }.isSuccess
        if (!ok) {
            Log.w(TAG, "加浮窗失败，本次侧滑索引不显示")
            return null
        }

        // 遮罩在下、字母条与面板在上
        r.addView(dimView(), FrameLayout.LayoutParams(MATCH, MATCH))
        root = r

        // 关档时把「这个 View 不参与触觉反馈」表达出来。官方文档给的「关」的表达方式就是
        // View 的这个开关。注意 API 37 里它**不是** `View` 的 public 字段了
        // （旧 AOSP 里 `View.hapticFeedbackEnabled` 是字段，现在只有
        //  `setHapticFeedbackEnabled(boolean)` / `isHapticFeedbackEnabled()` 这一对），
        // 所以老写法 `r.hapticFeedbackEnabled = false` 编译不过 —— 实测踩过。
        // 理论上我们从不触发 View 的默认触觉（整窗 FLAG_NOT_TOUCHABLE，也没有长按监听器），
        // 但关档必须关得彻底：显式置 false 比「碰巧不会触发」可信。
        r.setHapticFeedbackEnabled(hapticMode != SideIndexHaptics.Mode.OFF)

        // 淡入：Flyme `ASearchLayout.animateShow()` —— alpha 0 → 1、150ms、
        // `PathInterpolator(0.33f, 0f, 0.67f, 1f)`（Appindex 是同语义的 160ms alpha 动画，
        // 形态值取 Flyme 的）。alpha 作用在根节点上，之后加进来的条与面板都跟着淡入，
        // 所以这里设一次就够。
        //
        // 退出**不做动画**：我们的窗口是每次手势新建、抬手就拆，给退出加 150ms
        // 就意味着「抬手后窗口还得活着一会儿」，那会让「关态与未安装完全一致」
        // 多出一段尾巴（还得在卸载路径上把它掐掉）。得不偿失，Flyme 是常驻窗口才这么做的。
        r.alpha = 0f
        r.animate().alpha(1f).setDuration(SHOW_MS).setInterpolator(showInterpolator).start()
        return r
    }

    /**
     * 把模糊相关的参数写进窗口参数。
     *
     * 对应 Appindex 的 `FloatingService.configureBlurBehavior()` + `applyBlurStateToWindow()`：
     *
     * ```java
     * // configureBlurBehavior()
     * blurBehindEnabled = windowManager.isCrossWindowBlurEnabled();
     * params.dimAmount  = blurBehindEnabled ? 0.15f : 0.35f;
     * // showFloatingWindow()（开模糊的那一支）
     * params.flags |= 6;                              // FLAG_DIM_BEHIND | FLAG_BLUR_BEHIND
     * params.setBlurBehindRadius(dpToPx(20));
     * // applyBlurStateToWindow()
     * windowManager.updateViewLayout(floatingView, params);
     * ```
     *
     * **为什么没有 `updateViewLayout`**：Appindex 的浮窗是**常驻**的（`onCreate` 建一次，
     * 开关模糊靠改参数 + `updateViewLayout` 推给 WM）。我们的浮窗是**每次手势新建、抬手就拆**，
     * 参数在 `addView` 那一刻才生效，一秒后这个窗口就不存在了 —— 所以「推参数」这一步
     * 在我们的形态下没有可推的对象，直接在建窗口时算准即可。`dimAmount` 同理：
     * Appindex 用它配合 `FLAG_DIM_BEHIND` 压暗背景，我们在自己画的渐变里表达
     * （见 [dimView]），两边的取值一一对应。
     *
     * **不支持时必须静默降级**：探测失败/系统关着跨窗口模糊 → 不加这两个 flag、
     * 不加半径，浮层照常显示（默认就是「无效果」，不是「不工作」）。
     */
    /** 截一次屏给折射层；回来时这次手势的窗口已经没了就丢掉。 */
    private fun requestLiquidBackdrop(forRoot: FrameLayout) {
        if (!io.github.kotoriinsky.tsuzuri.hook.compat.ScreenCaptureCompat.available) return
        val displayId = context.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        io.github.kotoriinsky.tsuzuri.hook.compat.ScreenCaptureCompat.capture(displayId, { main.post(it) }) { bmp ->
            if (bmp == null) return@capture
            if (root !== forRoot) { bmp.recycle(); return@capture }
            liquidBackdrop?.recycle()
            liquidBackdrop = bmp
            liquidLayer?.setBackdrop(bmp)
        }
    }

    private fun applyBlurStateToWindow(lp: WindowManager.LayoutParams) {
        if (!blurActive) return
        lp.dimAmount = BLUR_DIM_AMOUNT
        // setBlurBehindRadius 是 API 31 起的公开方法；本模块 minSdk = 36，不必版本判断。
        // 半径从 `side_index.blur_radius` 来（需求 #7 档 A），默认 20dp 与改动前的
        // 硬编码常量逐位一致。⚠️ 它不是高斯 σ —— 非线性与取值区间见
        // [SideIndexLook.blurRadiusDp] 的 KDoc（真机标定前不要把它当 σ 用）。
        lp.setBlurBehindRadius(look.blurRadiusPx(density))
    }

    /**
     * 全屏渐变遮罩。
     *
     * 用窗口内的 View 而不是只靠 `FLAG_DIM_BEHIND`：后者把窗口背后的图层统一压暗，
     * 这里自己画一层渐变就可以控制哪一侧更暗。
     *
     * 两套配色**逐字对应 Appindex 的 `showFloatingWindow`**：
     *
     * ```java
     * // 不支持模糊：深色渐变
     * new GradientDrawable(TOP_BOTTOM, new int[]{-870704594, -1157627904});
     * // 支持且开着模糊：半透明版（配合 FLAG_DIM_BEHIND 的 0.15 压暗）
     * if (blurBehindEnabled) new GradientDrawable(TOP_BOTTOM, new int[]{1427773998, 1426063360});
     * ```
     *
     * `-870704594 = 0xCC1A1A2E`、`-1157627904 = 0xBB000000`；
     * `1427773998 = 0x551A1A2E`、`1426063360 = 0x55000000` —— 同一对颜色降了 alpha，
     * 因为模糊本身已经提供了「和背景分离」的层次，遮罩不需要那么实。
     *
     * 关于 `BlurUtils`：那边是「截屏 + RenderScript 高斯模糊再贴一张 ImageView」，
     * 这里**没有采用**，理由不是嫌弃它旧（`ScriptIntrinsicBlur` 确实已被弃用），
     * 而是它在 SystemUI 里**物理上做不到**：它的 `takeScreenshot` 画的是
     * `((Activity) context).getWindow().getDecorView()` —— SystemUI 的 context 不是 Activity，
     * 而且我们要模糊的是**桌面**的窗口，那个 View 树根本不在我们手里。
     * 真正在 Appindex 里生效的是 `FLAG_BLUR_BEHIND` 这条路（`BlurUtils` 在
     * `FloatingService` 里连一次都没被调用），所以照抄的是那一条。
     */
    private fun dimView(): View = View(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (blurActive) {
                intArrayOf(DIM_BLUR_TOP, DIM_BLUR_BOTTOM)
            } else {
                intArrayOf(DIM_TOP, DIM_BOTTOM)
            },
        )
    }

    // ── 字母条 ────────────────────────────────────────────────────────

    /**
     * 画字母条。尺寸与配色取自 Flyme 的 `ASLetterBarView`：
     *
     * ```java
     * mLetterHeight = dpToPx(16) + 1;                       // 格高
     * mPadding      = dpToPx(4);                            // 贴边留白
     * mLetterPaint.setTextSize(dpToPx(12));                 // 字号（固定，不随选中变化）
     * mNormalColor  = Color.argb(120, 255, 255, 255);       // 未选中：47% 白
     * mSelectedColor= -1;                                   // 选中：不透明白（= 0xFFFFFFFF）
     * // 字母横坐标：LEFT 方向 mPadding + 字号/2，RIGHT 方向 width - (mPadding + 字号/2)
     * ```
     *
     * 所以这里的字母宽度是 12dp、贴边 4dp —— 字母中心距屏幕边 `4 + 6 = 10dp`，
     * 和参考项目一致（Appindex 那边是 22dp 宽 + 6dp 留白，中心在 17dp）。
     *
     * **格高与字号来自 [AppIndex.BarLayout]**（它已经把 Appindex 的
     * `letter_vertical_offset` / `letter_size_progress` 两个个性化项算进去了，
     * 默认值等于 Flyme 原尺寸）—— 这里不再自己算，否则「画出来的条」和
     * 「输入线程判定的条」会用两组数字。
     *
     * **选中不放大字号**：Flyme 的高亮只换颜色（`onDraw` 里 `i == mNowChooseIndex`
     * 只决定 `setColor`）。Appindex 会放大到 1.4 倍，我们按形态以 Flyme 为准。
     */
    private fun buildBar(l: AppIndex.BarLayout, onRight: Boolean) {
        val r = root ?: return
        bar?.let { r.removeView(it) }
        barItems.clear()

        val edge = dp(BAR_EDGE_PADDING_DP)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (onRight) setPadding(0, 0, edge, 0) else setPadding(edge, 0, 0, 0)
        }
        for (i in l.letters.indices) {
            val tv = TextView(context).apply {
                text = l.letters[i]
                // Flyme 用的是它自带的 FlymeRegular（系统里没有这个字体），
                // 这里用系统默认字重 —— 参考项目那个字体本身也是「常规」而不是加粗
                typeface = Typeface.DEFAULT
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
                setTextColor(LETTER_IDLE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, l.letterTextPx.toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(BAR_ITEM_WIDTH_DP), l.itemHeight)
            }
            col.addView(tv)
            barItems.add(tv)
        }

        val lp = FrameLayout.LayoutParams(l.barWidth, l.itemHeight * l.count).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = l.barLeft(onRight)
            topMargin = l.barTop
        }
        r.addView(col, lp)
        bar = col
        // 字母条要压在遮罩之上、且后加的 View 在 FrameLayout 里本来就在上层，
        // 但面板可能是先加的，所以这里显式把条提到最前
        col.bringToFront()
    }

    /**
     * 高亮某个字母：**只换颜色**（Flyme `ASLetterBarView.onDraw` 的做法）。
     * 字号不动 —— 参考项目的高亮不带缩放。
     */
    private fun highlightBarItem(l: AppIndex.BarLayout, slot: Int) {
        for (i in barItems.indices) {
            barItems[i].setTextColor(if (i == slot) Color.WHITE else LETTER_IDLE)
        }
    }

    // ── 应用面板 ──────────────────────────────────────────────────────

    /**
     * 画某个槽位的应用面板。**整段照 Flyme 的 `ASAppGroupLayout`**：
     *
     * ```
     * panel (FrameLayout)                    ← setTranslationY：跟手指
     *  ├ panelScroll (ScrollView)             ← 视口：mListViewMaxHeight = mAppHeight * 3
     *  │   └ panelColumn (LinearLayout)       ← 内容：appHeight 一行
     *  └ thumb (View)                         ← 4dp × 184dp、topMargin 18dp
     * ```
     *
     * 与上一版的区别（这一版补的就是上一轮没做的那三件）：
     *
     *  1. **视口固定 1–3 行**。Flyme `showApps` 里 `layoutParams.height = mAppHeight * i2`，
     *     `i2` 取 1（≤5 个）/2（≤10 个）/3（更多）。上一版是「内容多高就多高，超出屏幕
     *     就砍掉几个应用」—— 那等于把 Flyme 的可滚动列表换成了截断。
     *  2. **细条**。Flyme `setupViewSpecs` 定了尺寸，`showApps` 决定透明度
     *     （只有 `size >= 4`，也就是内容真的超过视口时才显示）。
     *  3. **跟手指**。Flyme `MzSlideAppsSearchPhoneImpl.addListener` 的
     *     `touchYChange`：`h = y - dp(70)`，夹到 `[0, 根高 - 面板高 - 底部空间]`。
     *     上一版是把第 0 行的中心对齐到命中字母的中心（那是 Appindex 的做法，
     *     而面板定位属于**形态**）。现在改成 Flyme 的跟手指。
     */
    private fun buildPanel(index: AppIndex.Index?, l: AppIndex.BarLayout, slot: Int, onRight: Boolean) {
        val r = root ?: return
        val letter = l.letters.getOrNull(slot) ?: return
        val apps = index?.apps(letter).orEmpty()
        // 清单还没就绪时才用占位内容（Appindex 的 loading/empty 分支）
        val ph = if (index == null) placeholder else PanelPlaceholder.NONE

        val group = ensurePanel()
        // 格子尺寸来自字段（见 [cellW] / [cellH] 的注释：整份几何只有这一处来源）。
        // 这里**不再**各自 `dp(APP_WIDTH_DP)` —— 那样会在「格子高可调」之后
        // 出现「面板用了新尺寸、格子还是旧尺寸」这种两边都编译得过的不一致。
        val groupW = cellW * columns

        // ⚠️ 列数是**生效列数**（见 [columns]）：用用户填的那个数会让「几行内容、视口几行、
        // 细条出不出来」和真机排出来的格子对不上。
        val contentRows = if (ph != PanelPlaceholder.NONE) 1 else (apps.size + columns - 1) / columns

        // 视口行数：Flyme showApps 的 i2 —— ≤1 行内容给 1 行视口，更多最多 3 行
        val viewportRows = contentRows.coerceIn(1, PANEL_MAX_ROWS)
        val viewportH = cellH * viewportRows
        panelViewportH = viewportH
        panelMaxScroll = ((contentRows - viewportRows) * cellH).coerceAtLeast(0)
        // ⚠️ 重建前记下「内容原点」= 面板平移 − 滚动偏移。下面会改这两者，而矩形表
        // 只在布局跑完时才重录；换字母时结构没变（见 [assemblePanel]），**不会再有布局**，
        // 所以必须自己把矩形表挪一次。漏了这一手就会出现「看到的格子 ≠ 碰到的格子」
        // ——抬手启动的是另一个应用，这一路最严重的错法（旧代码靠「每次重建都触发一次布局」
        // 侥幸躲过了它，那是性能修复意外暴露出来的隐式依赖）。
        val oldOrigin = group.translationY.toInt() - panelScrollY
        panelScrollY = 0
        scrollDir = SCROLL_NONE
        // Flyme `showApps` 每次都 `mScrollView.smoothScrollTo(0, 0)`。
        // **必须真的把 ScrollView 归零**：它是复用的，上一个字母滚到一半时换字母，
        // 内容还停在原处、而我们的账记成了 0 —— 之后一滚动就会「跳」，
        // 而且录制出来的命中矩形会带着那个没记账的偏移。
        panelScroll?.scrollTo(0, 0)

        // 视口尺寸（Flyme 每次都写一遍 layoutParams）。
        // ⚠️ 内容列是 **ScrollView 的子节点**，LayoutParams 必须是
        // `FrameLayout.LayoutParams` —— ScrollView 继承自 FrameLayout，
        // 它 measure/layout 时会把自己的 LayoutParams 强转回来，放错类型直接 ClassCastException。
        panelScroll?.layoutParams = FrameLayout.LayoutParams(groupW, viewportH)
        panelColumn?.layoutParams = FrameLayout.LayoutParams(groupW, FrameLayout.LayoutParams.WRAP_CONTENT)

        highlighted = -1
        shownEntries.clear()
        if (ph != PanelPlaceholder.NONE) {
            fillPlaceholder(ph, groupW, cellH)
        } else {
            ensureCells(apps.size)
            for (i in apps.indices) bindCell(cellPool[i], apps[i])
            assemblePanel(apps.size, onRight, cellH)
            for (i in apps.indices) shownEntries.add(apps[i])
        }
        shownCells.clear()
        for (i in shownEntries.indices) shownCells.add(cellPool[i].root)

        // 面板尺寸（Flyme 的 group layout 是 wrap_content，我们直接算准：
        // 视口高度就是它的高度，细条（18+184dp）只在视口 ≥3 行时出现，装得下）
        val left = panelLeft(l, onRight, groupW)
        if (group.parent == null) {
            r.addView(group, FrameLayout.LayoutParams(groupW, viewportH).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = left
                topMargin = 0
            })
        } else {
            group.layoutParams = FrameLayout.LayoutParams(groupW, viewportH).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = left
                topMargin = 0
            }
        }
        group.bringToFront()
        // 字母条可能被面板盖住（面板是后加的），重新提一次
        bar?.bringToFront()

        // 跟手指（Flyme 的 touchYChange）：面板顶 = 手指 y − 70dp，两头夹
        val top = followerTop(l, viewportH, fingerY)
        group.translationY = top.toFloat()
        // 把矩形表跟着挪到新的内容原点（见上面 oldOrigin 的说明）——
        // 视口行数变了（1 行 ↔ 3 行）时 top 的夹取边界会变，这一步就是为它准备的。
        // 纯内存位移（每个 Rect 两次加法），没有任何视图查询。
        shiftItemRects(top - oldOrigin)

        applyThumb(contentRows, viewportRows, viewportH, groupW, onRight)
        updateBubble(
            letter = letter,
            panelLeft = left,
            groupW = groupW,
            onRight = onRight,
            panelTop = top,
            show = ph == PanelPlaceholder.NONE && apps.isNotEmpty(),
        )

        // 自动滚动：Flyme 在 showApps 里把方向复位成 1（停），
        // `startScrollThread` 由 `disposeMotionEvent` 在面板第一次显示时调用
        startScrollTicker()

        // 查询表**立刻**更新（entries / contents + 矩形的截断），几何位置则等布局跑完再录。
        // 拆开这两件事是这一版性能修复的关键：换字母时布局结构没变（见 [assemblePanel]），
        // 于是绝大多数换字母**不会再触发一次布局**、也就不会再有 N 次 `getLocationOnScreen`
        // —— 但「哪一格是哪个应用」必须当场跟着换，否则抬手会启动上一个字母的应用。
        rebindLookup(apps.size)

        // 真实位置只有布局跑完才准，所以几何在这里录。self-removing；
        // ⚠️ 同时只留一个：上一次渲染若没等来布局（换字母快于布局节拍时很常见），
        // 旧监听会一直挂着，下一次布局时**一起**触发一堆重复的 recordRects —— 白白多出
        // 几十次跨 Binder 的视图位置查询。用一个标记把它们收成至多一个。
        if (!rectsListenerPending) {
            rectsListenerPending = true
            group.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val vt = group.viewTreeObserver
                        runCatching { vt.removeOnGlobalLayoutListener(this) }
                        rectsListenerPending = false
                        recordRects()
                    }
                },
            )
        }
    }

    /**
     * 懒建面板容器。**主线程**。
     *
     * 结构见 [buildPanel] 的注释。两件事在这里定死、之后不再改：
     *  - 细条的尺寸（Flyme `setupViewSpecs`：4dp × 184dp、topMargin 18dp）。
     *    Flyme 还给它设了 `AS_THUMB_PADDING_DP = (15, 0, 15, 0)` 的 padding ——
     *    那个 View 没有子节点，padding 不影响背景的绘制范围，所以这里照抄尺寸而省略 padding
     *    （效果完全相同）。
     *  - 细条的颜色。**参考项目的 drawable 不在反编译产物里**（它的
     *    `mThumbView` 是在 `layout_assearch_appgroup.xml` 里定义的，那份 xml 拿不到），
     *    所以取色沿用同一组件的其它元素：字母条的未选中白（`0x78FFFFFF`）降一点 alpha，
     *    圆角 2dp（4dp 宽的条，正好是半圆）。这一点如实写在报告里。
     *
     * ## 玻璃材质层（需求 #7 档 B）
     *
     * 材质档是 `glass` 时，在**最底下**垫一层 [SideIndexGlassPanel]（`MATCH_PARENT`，
     * 跟着面板一起走），于是那一层高光正好勾在面板的圆角矩形上。
     * 默认档 `blur` 时这一段**整个不执行**（连 View 都不建）—— 关态与改动前逐位一致。
     * [SideIndexGlassPanel.create] 自己会把所有异常吃掉、失败就返回 null，
     * 所以这里不需要再兜一层（面板照常建，只是少一层高光）。
     */
    private fun ensurePanel(): FrameLayout {
        panel?.let { return it }
        val group = FrameLayout(context)
        if (look.glassMaterial) {
            // 折射层在最底下，高光玻璃叠在它上面（截图没到时只剩高光玻璃）
            runCatching { SideIndexLiquidLayer(context, SideIndexGlassPanel.GLASS_CORNER_DP * density, density) }
                .onFailure { Log.w(TAG, "折射层创建失败", it) }
                .getOrNull()?.let { liquid ->
                    group.addView(liquid, FrameLayout.LayoutParams(MATCH, MATCH))
                    liquidLayer = liquid
                    liquidBackdrop?.let(liquid::setBackdrop)
                }
            SideIndexGlassPanel.create(context, density)?.let { glass ->
                group.addView(glass, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            // Flyme 开了垂直渐隐边（`setVerticalFadingEdgeEnabled(true)`），
            // 让列表的上下沿有淡出，滚动时能看出「上面/下面还有」。默认长度即可。
            isVerticalFadingEdgeEnabled = true
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column)
        val thumbView = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(THUMB_RADIUS_DP).toFloat()
                setColor(THUMB_COLOR)
            }
        }
        group.addView(scroll)
        group.addView(
            thumbView,
            FrameLayout.LayoutParams(dp(THUMB_W_DP), dp(THUMB_H_DP)).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(THUMB_TOP_DP)
            },
        )
        panel = group
        panelScroll = scroll
        panelColumn = column
        thumb = thumbView
        return group
    }

    /**
     * 把行和格子挂进内容列。
     *
     * 行池/格子池的用法和 Appindex 一样：只增不减，按内容重新 `addView` 到行里。
     * 用不上的格子在 `removeAllViews` 之后就不在任何行里了，自然不显示。
     *
     * 两处**必须显式定死**的量：
     *  - 行的宽度 = 面板宽、高度 = 格子高。Flyme 的格子是绝对定位
     *    （`marginStart = (i % 5) * mAppWidth`、`topMargin = (i / 5) * mAppHeight`），
     *    行高恒等于格子高；我们用行容器表达同一件事，就必须把行高写成格子高，
     *    否则**视口高度（行数 × 格子高）和内容高度会对不上**，滚动距离也就错了。
     *  - 行对齐：贴右时靠右、贴左时靠左（Flyme 的 `rowGravity`），只影响最后一行不满时那几格。
     */
    private fun assemblePanel(count: Int, onRight: Boolean, cellH: Int) {
        val column = panelColumn ?: return
        val groupW = cellW * columns
        val rows = if (count <= 0) 0 else (count + columns - 1) / columns
        while (rowPool.size < rows) {
            rowPool.add(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL })
        }
        val rowGravity = if (onRight) Gravity.END else Gravity.START

        // ## 为什么是「就地复用」而不是「重建」（2026-09-15 性能）
        //
        // 原来是每个字母都 `row.removeAllViews()` + `column.removeAllViews()` 再全量 addView，
        // 外加每个 row new 一份 LayoutParams。换字母因此在主线程上做一次完整的视图树变更
        // （实测 assemblePanel ≈2.5ms/次 + 随后一整轮布局遍历），而 60fps 的预算只有 16.7ms。
        //
        // Flyme 的做法不一样（`ASAppGroupLayout.showApps`，请对照那一份）：
        // 它按 `list.size()` 遍历，**已有子视图就直接复用**（`getChildAt(i)` 非空就不 addView）、
        // 就地改 LayoutParams 的 margin、再 `showData(...)`；只有格子不够时才从 `mViewCache`
        // 取一个补上，多出来的格子是 `setVisibility(8)` 隐藏 —— **一个 view 都不摘**。
        // 这里照它的语义重写：格子永远留在原来的槽位（`i / columns` 行、第 `i % columns` 列），
        // 于是**布局结构在换字母之间完全不变**，随之而来的是 [recordRects] 的几何也可以沿用
        // （见 [rebindLookup]）。
        for (i in 0 until count) {
            val cell = cellPool[i]
            val row = rowPool[i / columns]
            cell.root.visibility = View.VISIBLE
            // 只有「换行」（列数变化 / 清单重排）才需要重新挂 —— 稳态下这一步一次都不做
            if (cell.root.parent !== row) {
                (cell.root.parent as? android.view.ViewGroup)?.removeView(cell.root)
                row.addView(cell.root)
            }
        }
        // 这一次用不到的格子：**隐藏而不是摘掉**（Flyme 的 setVisibility(8)）。
        // 留着它们的好处是下一封字母复用同一批 view，代价只是几个 GONE 的视图。
        for (i in count until cellPool.size) cellPool[i].root.visibility = View.GONE

        for (r in 0 until rows) {
            val row = rowPool[r]
            row.visibility = View.VISIBLE
            row.gravity = rowGravity
            val lp = row.layoutParams as? LinearLayout.LayoutParams
            if (lp == null) {
                row.layoutParams = LinearLayout.LayoutParams(groupW, cellH)
            } else if (lp.width != groupW || lp.height != cellH) {
                // 值真变了才写回：写回 = 请求一次布局遍历，稳态下这一步不该发生
                lp.width = groupW
                lp.height = cellH
                row.layoutParams = lp
            }
            if (row.parent == null) column.addView(row)
        }
        for (r in rows until rowPool.size) rowPool[r].visibility = View.GONE
    }

    /**
     * 画占位内容（加载中 / 没有可启动应用）。**主线程**。
     *
     * Appindex 的 `showAppListLoadingState` 就是往 app 容器里塞一个居中 TextView
     * （「读取应用中…」），宽度 wrap、12sp。我们这里把它放进**同样大小的格子**里，
     * 这样面板的尺寸与正常状态下一致，不会出现「先是小面板、清单到了突然变大」的跳动。
     */
    private fun fillPlaceholder(ph: PanelPlaceholder, groupW: Int, cellH: Int) {
        val column = panelColumn ?: return
        val tv = placeholderView ?: TextView(context).apply {
            setTextColor(PLACEHOLDER_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, PLACEHOLDER_SP)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            placeholderView = this
        }.also { placeholderView = it }
        tv.text = when (ph) {
            PanelPlaceholder.LOADING -> PLACEHOLDER_LOADING
            PanelPlaceholder.EMPTY -> PLACEHOLDER_EMPTY
            PanelPlaceholder.NONE -> ""
        }
        tv.layoutParams = LinearLayout.LayoutParams(groupW, cellH)
        column.removeAllViews()
        column.addView(tv)
    }

    private fun bindCell(cell: Cell, entry: AppEntry) {
        // 池子里复用的格子可能还带着上一次的高亮位移与兜底底色（Flyme 的 `showData`
        // 也是同一个位置 `mContentLayout.setTranslationY(0)`）。动画取消 + 复位 +
        // 底色清成透明：三件缺一不可，否则会出现「刚划过去的那一格还亮着」。
        cell.content.animate().cancel()
        cell.content.translationY = 0f
        cell.bg.setColor(Color.TRANSPARENT)
        // 图标的三层链（本批 3️⃣ 加上了最前面那一层，与扇形那边同一套优先级）：
        //
        //   1. **单项覆盖**（图片 / 图标包里指定的某一张）—— 覆盖存在就不再看整体图标包；
        //   2. **整体图标包**（需求 #5）—— 没配包 / 没覆盖 / 包坏了都退回自带图标；
        //   3. **应用自带图标**。
        //
        // 第 2 层必须走包装层（[SideIndexIconPack]）：`IconPackResolver` 的失败路径会做一次
        // `getIdentifier` 并现合成一张位图，而这里是主线程 + 每次换字母都跑几十次。
        // 第 1 层同理走 [SideIndexIconOverride]（它按「覆盖签名 + 包名」缓存，
        // 首次解码由 `prewarm` 挪到后台线程）。
        val override = if (iconOverrides.size == 0) null else iconOverrides.drawable(context, entry.packageName)
        cell.icon.setImageDrawable(
            override ?: if (iconPack.isNullOrEmpty()) {
                entry.icon
            } else {
                SideIndexIconPack.apply(context, iconPack, entry.packageName, entry.activityName, entry.icon)
            },
        )
        // 名字覆盖（本批 3️⃣ 前半）：没覆盖就是 `entry.label`（`DisplayNameOverrides.label`
        // 的出口就是那个原值 —— 调用方不需要自己判空）。
        cell.name.text = DisplayNameOverrides.label(nameOverrides, entry.packageName, entry.label)
    }

    private fun ensureCells(n: Int) {
        while (cellPool.size < n) cellPool.add(createCell())
    }

    /**
     * 一个应用格子。尺寸全部照 Flyme 的 `ASAppGroupLayout.setupViewSpecs()` +
     * `ASAppGroupSingleAppLayout.setupViewSpecs()`：
     *
     * ```java
     * mAppHeight = dpToPx(108.0f); mAppWidth = dpToPx(66.0f);   // 格子
     * icon:  layoutParams 50x50, ScaleType.FIT_XY              // 图标（**没有圆角裁剪**）
     * name:  setTextSize(dpToPx(12.0f)), topMargin = dpToPx(4) // 名字
     * content padding = AS_APP_CONTENT_PADDING_DP = (3, 0, 3, 0)
     * ```
     *
     * 图标大小与圆角是 Appindex 的两个设置项（`icon_size_dp` / `icon_radius_dp`），
     * 我们把它们补成可调：
     *  - **大小默认 50dp**（Flyme 的值，Appindex 默认 45dp）；
     *  - **圆角默认 0 = 不裁**（Flyme 就是直接 `FIT_XY` 贴上去，自适应图标自带形状；
     *    Appindex 默认 10dp 圆角裁剪）。用户想要 Appindex 那种观感就把圆角拉上去，
     *    实现方式照 Appindex：`ViewOutlineProvider` + `setClipToOutline(true)`
     *    （`FloatingService.createAppItemHolder` 里那段 `o.setRoundRect(...)`）。
     *  - 格子高 108dp（Flyme）：图标最大 80dp + 名字 17dp + 4dp 间距 = 101dp，
     *    仍然放得下 —— 这也是 [SideIndexLook.MAX_ICON_DP] 取 80 而不是更大的原因。
     *    现在格子高可调（`side_index.cell_height`），这条预算关系由
     *    [SideIndexLook.cellHeightPx] 的下限兜着（**抬格子、不缩图标**）。
     *
     * ## 无字模式（`side_index.hide_app_names`，需求 #4）
     *
     * 关掉名字时做三件事，缺一不可：
     *  1. **不把名字加进格子**（不是 `visibility = GONE`）—— 加进去就会占布局高度；
     *  2. 格子高在 [cellHeightPx][SideIndexLook.cellHeightPx] 里也不再计名字块；
     *  3. 图标改为**整格居中**（`gravity = CENTER`）。默认的 `CENTER_HORIZONTAL`
     *     会让图标贴顶（垂直方向从 0 开始排），名字一去掉，空档全落在图标下面 ——
     *     那就是产品经理说的「名字没了但格子还是高的、面板空一块」。
     */
    private fun createCell(): Cell {
        val iconSize = look.iconSizePx(density)
        val radiusPx = look.iconRadiusPx(density)
        val names = !look.hideAppNames
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { gravity = Gravity.CENTER_HORIZONTAL }
            scaleType = ImageView.ScaleType.FIT_XY
            if (radiusPx > 0) {
                setClipToOutline(true)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, radiusPx.toFloat())
                    }
                }
            }
        }
        val name = TextView(context).apply {
            setTextColor(NAME_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SP)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            // 比较：Appindex 给的是「图标宽 + 12dp」，格子本身装不下，会画到邻居格子上。
            // Flyme 的格子是 66dp（图标 50dp + 左右各 3dp 内边距 + 余量），
            // 所以夹到格子宽就既不会溢出、也能放下比「图标宽」更多的字。
            maxWidth = cellW
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(NAME_TOP_MARGIN_DP)
            }
        }
        // 内层内容块（Flyme 的 `mContentLayout`）：图标 + 名字。高亮抬起动的是它。
        // 内边距的唯一来源是 SideIndexLook.CELL_CONTENT_PAD_H_DP：它参与 panelMetrics 的
        // 「格宽下限 = 图标 + 2 × 内边距」，两处各写一份就是没有任何报错的那种不一致。
        val padH = (SideIndexLook.CELL_CONTENT_PAD_H_DP * density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // ⚠️ **不抄** Flyme cell xml 里的 `clipChildren = false`：我们的图标能调到 80dp
            // （> 66dp 的格宽），关掉裁剪会让它压到邻格上 —— 那是改动前没有的形态。
            // 纵向不越界靠的是「抬起量 ≤ 实测的格内余量」（见 setHighlight），不靠这一条。
            setPadding(padH, 0, padH, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(icon)
            if (names) addView(name)
        }
        // 选中兜底底色（默认全透明）。**必须在这里建好**：上色只改它的颜色
        // （`GradientDrawable.setColor` → invalidate，**不触发布局**），
        // 而这条路径跟着手指跑（每个 MOVE 帧都可能换高亮），绝不能 `setBackground`。
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (SideIndexLook.CELL_SELECT_BG_RADIUS_DP * density).toInt().toFloat()
            setColor(Color.TRANSPARENT)
        }
        val cellRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 内容块在格子里**垂直居中**（Flyme 的 cell xml：`gravity = 0x11` = CENTER）。
            // 显示名字与无字模式都是这一条，所以不再分情况 —— 改动前显示名字时是
            // `CENTER_HORIZONTAL`（贴顶），那是抄错的形态，也是顶行高亮被裁的根因。
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(cellW, cellH)
            setBackground(bg)
            // 内边距在内容块那一层（见上）；格子根不加 padding —— 它的高度必须是 cellH，
            // 上下留白靠 `gravity = CENTER` 表达。
            addView(content)
        }
        return Cell(cellRoot, content, icon, name, bg)
    }

    // ── 面板定位：跟手指（Flyme MzSlideAppsSearchPhoneImpl.touchYChange） ──

    /**
     * 面板内容离屏幕边缘的距离（px）。**照 Flyme `ASAppGroupLayout.setDirection`**：
     *
     * ```java
     * mPadding = dpToPx(32.0f);                                   // setupViewSpecs
     * // 贴右时：layoutParams.addRule(21)（ALIGN_PARENT_END）+ paddingEnd = 32dp
     * //         scrollView 也 addRule(21) → 它的右边界 = 父右边界 - 32dp
     * // 贴左时对称（addRule(20) + paddingStart = 32dp）
     * ```
     *
     * 于是面板内容距屏幕边 32dp，而字母条是 28dp 宽贴边放 —— 那 4dp 的缝就是
     * 细条（4dp 宽）待的地方。面板边距是设置项（Appindex `icon_margin_dp`，默认取 Flyme 的 32dp），
     * 但这条缝**始终留着**（用户 2026-10-31）：边距再小、字母条放得再大，面板也只平移让开，
     * 不压字母条，也不缩小。贴右算右缘，贴左算左缘。
     */
    private fun panelLeft(l: AppIndex.BarLayout, onRight: Boolean, groupW: Int): Int {
        val inset = maxOf(panelInsetPx, l.barMargin + l.barWidth + dp(PANEL_THUMB_GAP_DP))
        return if (onRight) {
            (l.screenW - inset - groupW).coerceAtLeast(0)
        } else {
            inset.coerceIn(0, (l.screenW - groupW).coerceAtLeast(0))
        }
    }

    /**
     * 跟手指的面板顶（px）。**逐行照 Flyme `MzSlideAppsSearchPhoneImpl.addListener`**：
     *
     * ```java
     * public void touchYChange(float y) {
     *     float h = y - mTouchSlop;                       // mTouchSlop = dpToPx(70.0f)
     *     if (h < 0) h = 0;
     *     if (group.getHeight() + h + mBottomSpace > root.getHeight())
     *         h = (root.getHeight() - group.getHeight()) - mBottomSpace;
     *     group.setTranslationY(h);
     * }
     * ```
     *
     * 也就是「面板顶 = 手指 y − 70dp」，上下两头夹住。所以面板的 70dp 之上
     * 正好是手指所在的位置 —— 面板不会跑到手指下面去。
     *
     * **一处如实说明的差异**：Flyme 传给这个回调的并不是屏幕 y，而是
     * `f + f2`（`ASLetterBarView.touch` 里 `y - 状态栏高*2`）—— 那是它自己
     * DragLayer 坐标系的补偿量（`mLetterPaddingTop` 不含状态栏、而 onDraw 从
     * `paddingTop + mStatusBarHeight` 开始画），我们拿到的 y 已经是屏幕绝对坐标，
     * 照它的算式再减两个状态栏只会把面板整体推上去。所以这里用屏幕 y，只保留
     * Flyme 真正在表达的语义：**减 70dp、两头夹**。
     */
    private fun followerTop(l: AppIndex.BarLayout, viewportH: Int, y: Float): Int {
        val maxTop = (l.screenH - viewportH - bottomSpacePx).coerceAtLeast(0)
        val raw = (y - dp(PANEL_FINGER_OFFSET_DP)).toInt().coerceIn(0, maxTop)
        return raw
    }

    /** 面板顶变化时重新应用（不触发布局遍历，只改变换 + 平移命中表）。**主线程**。 */
    private fun followFinger(y: Float, l: AppIndex.BarLayout) {
        val group = panel ?: return
        if (panelViewportH <= 0) return
        val top = followerTop(l, panelViewportH, y)
        val old = group.translationY.toInt()
        if (top == old) return
        group.translationY = top.toFloat()
        shiftItemRects(top - old)
        placeBubble(top)
    }

    // ── 细条与滚动（Flyme ASAppGroupLayout） ────────────────────────────

    /**
     * 细条。**照 Flyme `ASAppGroupLayout.setupViewSpecs` + `showApps` + 构造函数里的
     * `setOnScrollChangeListener`**：
     *
     * ```java
     * width = dpToPx(4.0f); height = dpToPx(184.0f); topMargin = dpToPx(18.0f);
     * mThumbMaxMove = (mListViewMaxHeight - 细条高) - topMargin;   // = 3*108 - 184 - 18 = 122dp
     * mListViewHideHeight = (mAppHeight * 总行数) - mListViewMaxHeight;
     * // 只在 size >= 4（总行数 ≥ 4）时显示
     * if (size >= 4) mThumbView.setAlpha(1.0f);
     * // 滚动时
     * mThumbView.setTranslationY(mThumbMaxMove * (scrollY / mListViewHideHeight));
     * ```
     *
     * **偏离一处**：Flyme 那句是**整数除法**（`i2` 和 `mListViewHideHeight` 都是 int），
     * 结果是「滚动到位之前细条一直不动、滚到底猛地跳一格」。这显然是笔误，
     * 照抄一个笔误不是「保持一致」，是把它一起抄坏 —— 这里用浮点除法，
     * 细条按真实比例移动。**其余数值原样照抄**（4dp/184dp/18dp/只在 ≥4 行时显示）。
     */
    private fun applyThumb(contentRows: Int, viewportRows: Int, viewportH: Int, groupW: Int, onRight: Boolean) {
        val tv = thumb ?: return
        val lp = tv.layoutParams as? FrameLayout.LayoutParams ?: return
        // 细条贴面板的**外侧**（靠近屏幕边的那一边）—— Flyme 里它是 ALIGN_PARENT_START/END，
        // 也就是面板内容的起始/末尾边，正好落在面板与字母条之间那 4dp 的缝里
        lp.width = dp(THUMB_W_DP)
        lp.height = dp(THUMB_H_DP)
        lp.leftMargin = if (onRight) (groupW - dp(THUMB_W_DP)).coerceAtLeast(0) else 0
        lp.topMargin = dp(THUMB_TOP_DP)
        tv.layoutParams = lp
        // 只有内容真的超过视口时才出现（Flyme 的 size >= 4；等价于总行数 > 视口行数）
        val visible = contentRows > viewportRows
        tv.alpha = if (visible) 1f else 0f
        tv.translationY = 0f
        thumbVisible = visible
        thumbViewportH = viewportH
    }

    /**
     * 手指不在字母条上时，列表该往哪边滚。**逐行照 Flyme `smoothScrollDownOrUp`**：
     *
     * ```java
     * if (f == -1.0f) mDirection = 1;                 // 显式复位成「停」
     * else if (f <  mListViewRect.top)    mDirection = 0;   // 手指在视口上方 → 往上滚
     * else if (f >  mListViewRect.bottom) mDirection = 2;   // 手指在视口下方 → 往下滚
     * // 注意：手指在视口内时**不改方向** —— 上一次的方向会保持住
     * ```
     *
     * 最后那条不是我们的推断，是原文的行为：`selectApp` 命中格子时会显式调
     * `smoothScrollDownOrUp(-1)` 把它停掉，否则方向就一直维持着。
     * 我们这里为了提高可预测性，把「手指回到视口内」也当作停（见 [scrollDirectionFor]），
     * 因为我们的视口是用 margin 算的、`contains` 的边界比 Flyme 的
     * `getGlobalVisibleRect` 更精确，不会出现「手指明明回来了却还在滚」的抖动。
     */
    private fun scrollDirectionFor(y: Float): Int {
        val group = panel ?: return SCROLL_NONE
        if (panelMaxScroll <= 0) return SCROLL_NONE
        val top = group.translationY.toInt()
        val bottom = top + panelViewportH
        return when {
            y < top -> SCROLL_UP
            y > bottom -> SCROLL_DOWN
            else -> SCROLL_NONE
        }
    }

    /**
     * 设置滚动方向，并在需要时把 ticker 叫起来。**主线程**。
     *
     * **为什么必须由我们自己驱动**：Flyme 的 `ScrollView` 是能被手指滚的
     * （`disposeMotionEvent` 里那行 `getScrollView().onTouchEvent(motionEvent)`），
     * 所以它的滚动线程只负责「手指停在视口外时持续滚」这一半。
     * 我们的窗口 `FLAG_NOT_TOUCHABLE`：ScrollView 一辈子收不到一个触摸事件，
     * 只有我们自己把手指 y 喂进来、自己算方向、自己滚。见类注释。
     */
    private fun setScrollDirection(dir: Int) {
        if (dir != SCROLL_NONE) {
            scrollDir = dir
            ensureScrollTicker()
        } else if (scrollDir != SCROLL_NONE) {
            // 手指回到视口内（或视口本来就没得滚）：停
            stopScroll()
        }
    }

    private fun startScrollTicker() {
        scrollDir = SCROLL_NONE
        if (!scrollScheduled) {
            scrollScheduled = true
            main.postDelayed(scrollTick, SCROLL_TICK_MS)
        }
    }

    private fun ensureScrollTicker() {
        if (scrollScheduled || released) return
        scrollScheduled = true
        main.postDelayed(scrollTick, SCROLL_TICK_MS)
    }

    /**
     * 自动滚动的步进。**照 Flyme `ASAppGroupLayout.lambda$new$2`（那个滚动线程）**：
     *
     * ```java
     * while (mNeedScroll) {
     *     Thread.sleep(15L);
     *     if (mDirection == 0)      mScrollView.scrollBy(0, -mAppHeight / 15);
     *     else if (mDirection == 2) mScrollView.scrollBy(0,  mAppHeight / 15);
     *     else                      mScrollView.scrollBy(0, 0);
     * }
     * ```
     *
     * 即「每 15ms 滚一格高的 1/15」—— 一行(108dp)约 225ms 滚完。
     * 差别只有一处：Flyme 那个线程起来之后一直转到 `stopScrollThread()`，
     * 我们只在**真的需要滚**的时候才把它排进主线程队列（方向一变成「停」就退出，
     * 下一次方向变化时再叫起来）。理由是我们跑在主线程上 ——
     * 让一个 15ms 的循环在整个手势期间一直转，等于白占 SystemUI 的主线程。
     *
     * 步进值用**格子高**而不是视口高（Flyme 用的是 `mAppHeight`），所以不管视口几行，
     * 滚动的线速度都一样。
     */
    private val scrollTick = object : Runnable {
        override fun run() {
            scrollScheduled = false
            if (released) return
            val dir = scrollDir
            if (dir == SCROLL_NONE) return
            // 步进 = 格子高 / 15（Flyme 用的是 `mAppHeight`）：格子高可调之后
            // 线速度**跟着格子一起变**，两者保持一致（改用 dp(APP_HEIGHT_DP) 的话
            // 格子调小之后滚动会显得「一步跨得太多」）。
            val step = (cellH / SCROLL_STEP_DIVISOR).coerceAtLeast(1)
            scrollPanelBy(if (dir == SCROLL_UP) -step else step)
            // 滚到头了也照样继续排（手指还压在视口外，方向没变），
            // 真正停下来的唯一条件是方向变成「停」（抬手/手指回到视口内）
            if (scrollDir != SCROLL_NONE) {
                scrollScheduled = true
                main.postDelayed(this, SCROLL_TICK_MS)
            }
        }
    }

    /**
     * 滚动一步并同步命中表。**主线程**。
     *
     * `@return` 这一帧是否真的动了（到边界时不动）。
     *
     * 三件必须一起做的事：滚自己、**挪命中表**、挪细条。少任何一件都会出现
     * 「看到的格子」和「碰到的格子」错位 —— 而这在抬手启动时会启动一个错的
     * 应用，是这一路最容易出的问题。
     */
    private fun scrollPanelBy(dy: Int): Boolean {
        val scroll = panelScroll ?: return false
        if (panelMaxScroll <= 0) return false
        val next = (panelScrollY + dy).coerceIn(0, panelMaxScroll)
        val delta = next - panelScrollY
        if (delta == 0) return false
        panelScrollY = next
        scroll.scrollTo(0, next)
        // 内容上移 → 格子的屏幕 y 也上移同样的量（scrollTo 不触发布局遍历，
        // 所以 onGlobalLayout 那条路不会替我们更新）
        shiftItemRects(-delta)
        updateThumbTranslation()
        return true
    }

    /** 细条按滚动比例移动。Flyme 的 `mThumbMaxMove * (scrollY / mListViewHideHeight)`。 */
    private fun updateThumbTranslation() {
        val tv = thumb ?: return
        if (!thumbVisible) {
            tv.translationY = 0f
            return
        }
        val maxMove = (thumbViewportH - dp(THUMB_H_DP) - dp(THUMB_TOP_DP)).coerceAtLeast(0)
        val fraction = if (panelMaxScroll > 0) panelScrollY.toFloat() / panelMaxScroll else 0f
        tv.translationY = maxMove * fraction
    }

    /**
     * 面板位移时同步命中表。**主线程**。
     *
     * `itemRects` 是主线程独占的（[hitTest] 的调用者都在主线程），并且 Rect 是可变对象，
     * 所以这里原地 `offset` —— 每个 MOVE 帧都会走这条路径，不分配是硬要求。
     */
    private fun shiftItemRects(dy: Int) {
        if (dy == 0) return
        val rects = itemRects
        for (i in rects.indices) rects[i].offset(0, dy)
    }

    // ── 气泡 ──────────────────────────────────────────────────────────

    /**
     * 面板上沿外侧那个 50dp 大字母。**逐条对应 Flyme `ASAppGroupLayout.setupViewSpecs`
     * 里的 `mLetterTextView` + `MzSlideAppsSearchPhoneImpl` 的 `setLetterString`**：
     *
     * ```java
     * mLetterTextView.setTextSize(dpToPx(32.0f));
     * layoutParams 50x50dp;
     * // 它装在 layout_assearch_letter_container 里，容器 padding =
     * //   AS_CURRENT_LETTER_CONTAINER_PADDING_RECT_DP = (4, 0, 4, 8)
     * // 位置在字母容器的外侧（ENTER 方向决定 start/end）
     * ```
     *
     * 摆放的**算式**沿用上一版（Appindex 的 `updateLetterBubblePosition`，
     * 面板顶上方 12dp 那个版本被我们改成 Flyme 的 8dp 容器下侧留白），
     * 只把「面板顶」换成新的跟手指结果。
     *
     * **一处已知的观感边界**：气泡在面板**外面**（Flyme 的大字母是在面板容器的
     * 顶部内嵌的），所以手指滑到字母条最上面那几个字母时（面板顶被夹到 0），
     * 气泡没有地方待。这一版的处理是**藏起来**（[applyBubbleVisibility]）——
     * 比压在图标上强，也不会像 Appindex 那样跑到屏幕外面去。
     *
     * @param show 是否要显示（清单为空/占位内容时不显示，Appindex 的
     *   `if (count <= 0) letterBubbleView.setVisibility(GONE)`）
     */
    private fun updateBubble(
        letter: String,
        panelLeft: Int,
        groupW: Int,
        onRight: Boolean,
        panelTop: Int,
        show: Boolean,
    ) {
        val r = root ?: return
        val size = dp(BUBBLE_DP)
        val box = bubble ?: frameLayoutOfBubble(r).also { bubble = it }

        bindBubbleContent(letter)

        val gap = dp(BUBBLE_GAP_DP)
        val panelEdgeX = if (onRight) panelLeft + groupW else panelLeft
        val x = if (onRight) panelEdgeX - size - gap else panelEdgeX + gap
        val screenW = layout?.screenW ?: context.resources.displayMetrics.widthPixels
        box.layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = x.coerceIn(0, (screenW - size).coerceAtLeast(0))
            // 纵向位置完全交给 translationY（见 placeBubble）：跟手指时只需要改变换，
            // 不必每帧改一次边距 —— 改边距会触发一次布局遍历
            topMargin = 0
        }
        placeBubble(panelTop)
        bubbleShown = show
        applyBubbleVisibility()
        box.bringToFront()
    }

    /**
     * 懒建气泡容器：圆底 + 描边 + 两种内容（文字 / 图标）叠在同一格里。
     *
     * 结构（与 Niagara 的 handle 是同一个模型 —— 它也是「一个圆 + 两种内容二选一」）：
     *
     * ```
     * FrameLayout (50×50dp, OVAL 底 + 2dp 描边, 位置与按压淡入都作用在它上面)
     *  ├─ TextView  ← 普通分组：字母（32sp 粗体，Flyme 值）
     *  └─ ImageView ← 特殊槽位：图标（28sp，Niagara 的 scrollbar_handle_icon_size）
     * ```
     *
     * 两者的可见性由 [bindBubbleContent] 二选一，**不是**同时显示 ——
     * Niagara 的 `onDraw` 也是这么分支的（字母走 `canvas.drawText`、图标走 `drawable.draw`）。
     */
    private fun frameLayoutOfBubble(r: FrameLayout): FrameLayout {
        val box = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BUBBLE_BG)
                setStroke(dp(2), BUBBLE_STROKE)
            }
            // 100% 不透明的内容+圆形底：让框架少做一层合成（Niagara 的
            // `ScrollbarHandle.hasOverlappingRendering() = false` 是同一个意思）。
            // 注意我们的底是半透明（0xCC222222），所以**不能**声明 false ——
            // 那会让它跳过 alpha 合成、半透明底反而出错。这里如实说明差异：不跟抄这一条。
            r.addView(this)
        }
        val tv = TextView(context).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUBBLE_SP)
            setIncludeFontPadding(false)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            box.addView(this)
        }
        val iv = ImageView(context).apply {
            // 图标边长照 Niagara 的 `scrollbar_handle_icon_size` = 28sp（它的
            // handle 直径 44sp、文字 28sp —— 图标与文字同档）。我们这边气泡是 50dp、
            // 文字 32sp，图标按同一比例取 28sp。
            val px = (BUBBLE_ICON_SP * context.resources.displayMetrics.scaledDensity).toInt()
                .coerceAtLeast(1)
            layoutParams = FrameLayout.LayoutParams(px, px, Gravity.CENTER)
            // 圆底是深色的（0xCC222222），而系统那个图形是按浅色主题画的 ——
            // 不染白会几乎看不见。染成与文字同一个白。
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
            box.addView(this)
        }
        bubbleText = tv
        bubbleIcon = iv
        return box
    }

    /**
     * 把这一格该显示的内容放进气泡：**图标或文字二选一**（需求 #10 的机制）。
     *
     * 什么时候用图标：开关 [SideIndexLook.bubbleIconSlots] 开着、**且**这个槽位是特殊槽
     * （「最近使用 `★`」或「收藏项 `♥`」，见 [slotIcon]）、**且**图标真的取到了。
     * 三条缺任何一条都退回字母 —— 一个字形的退化比一个空圆圈好，也不会让
     * 「开关开了但什么都没变」变成一个需要排查的问题（取不到图标时日志说明）。
     */
    private fun bindBubbleContent(letter: String) {
        val tv = bubbleText ?: return
        val iv = bubbleIcon ?: return
        val icon = if (look.bubbleIconSlots) slotIcon(letter) else null
        if (icon != null) {
            iv.setImageDrawable(icon)
            iv.visibility = View.VISIBLE
            tv.visibility = View.GONE
        } else {
            tv.text = letter
            tv.visibility = View.VISIBLE
            iv.visibility = View.GONE
        }
    }

    /**
     * 特殊槽位的图标。
     *
     * | 槽位 | 字形 | 图标 |
     * | --- | --- | --- |
     * | 最近使用 | `★` | `ic_menu_recent_history`（时钟） |
     * | 收藏项 | `♥` | `star_big_on`（实心星） |
     *
     * 两个都用系统自带的图形：往仓库里加资源文件要动 `hook/src/main/res/`，
     * 而这一条只要**机制**成立（Niagara 的 handle 也是「字母或 drawable 二选一」）。
     *
     * 字形与图标**故意不同源**：字形是兜底（取不到图标时 [bindBubbleContent] 会退回画它），
     * 而 `★` 已经被最近使用占用，所以收藏用的是 `♥`；图标那边星星才是「收藏」的通用符号，
     * 于是两者的对应关系是「时钟 ↔ ★」「星 ↔ ♥」—— 这是这一版唯一一处需要记住的错位，
     * 写在这里免得以后有人以为画错了。
     *
     * 判定用 `when` 而不是「跟 `★` 比一次」：新加一个特殊槽位时只需要在这里加一行，
     * 漏了的表现是「气泡里画出一个字母而不是图标」，不会有任何报错。
     *
     * 取不到就返回 null，气泡退回字母（见 [bindBubbleContent]），绝不抛。
     */
    private fun slotIcon(letter: String): Drawable? = runCatching {
        when (letter) {
            AppIndex.RECENT_SLOT -> context.getDrawable(android.R.drawable.ic_menu_recent_history)
            AppIndex.FAVORITE_SLOT -> context.getDrawable(android.R.drawable.star_big_on)
            else -> null
        }
    }.getOrNull()

    /**
     * 把气泡挪到面板上沿外侧。**主线程**，每个跟手指的帧都会走这里。
     *
     * 只写 `translationY`（不碰 layoutParams），所以不触发任何测量/布局 ——
     * 这条路径跟着手指跑，是这一版的性能要点。
     */
    private fun placeBubble(panelTop: Int) {
        val box = bubble ?: return
        box.translationY = (panelTop - dp(BUBBLE_DP) - dp(BUBBLE_TOP_GAP_DP)).toFloat()
    }

    /**
     * 气泡的可见性。两条规则：
     *
     *  1. 清单为空 / 占位内容时不显示（[updateBubble] 的 `show`）；
     *  2. **顶部空间不够时不显示**。气泡在面板上方，要占 `50dp + 8dp`，
     *     而跟手指会把面板顶一路夹到 0 —— 那时气泡只能压在图标上或者跑到屏幕外，
     *     两个都不好，所以选择不显示。
     *
     * 第 2 条是**我们补的**：Flyme 的大字母装在面板容器内部（`layout_assearch_letter_container`），
     * 不存在这个问题；我们的气泡沿用 Appindex「浮在面板上方」的摆法，就得自己处理这个边界。
     *
     * ## 按压淡入（需求 #10）
     *
     * 在「从不可见变可见」的那一刻启动，与 Niagara 逐条对应
     * （`ScrollbarHandle.setCurrentSection`：`mPrevious == null → setVisibility(0) + start()`）：
     * **纯 alpha、时长 150ms、`AccelerateDecelerateInterpolator`**。
     * 时长参数化在 `side_index.bubble_anim_ms`，0 = 不做动画（= 改动前的形态）。
     */
    private fun applyBubbleVisibility() {
        val box = bubble ?: return
        val group = panel
        val need = (dp(BUBBLE_DP) + dp(BUBBLE_TOP_GAP_DP) + dp(SAFE_EDGE_DP)).toFloat()
        val room = group != null && group.translationY >= need
        val visible = bubbleShown && room
        val was = bubbleWasVisible
        bubbleWasVisible = visible
        if (!visible) {
            box.visibility = View.GONE
            // 取消还没跑完的淡入：下次显示时要重新从 0 开始，而不是接着上一段。
            // 不取消的话「抬手后立刻再按下」会看到一段从半透明的中间态开始的淡入。
            box.animate().cancel()
            return
        }
        box.visibility = View.VISIBLE
        if (was) return
        val ms = look.bubbleAnimDurationMs()
        if (ms <= 0L) {
            // 关掉动画 = 直接显示（等于改动前：气泡没有自己的动画，只有整层的淡入）
            box.alpha = 1f
            return
        }
        box.alpha = 0f
        box.animate()
            .alpha(1f)
            .setDuration(ms)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    // ── 命中与高亮 ────────────────────────────────────────────────────

    /**
     * **只更新查询表**（哪一格是哪个应用），网格几何留给 [recordRects]。**主线程**。
     *
     * 换字母时视图结构不变（[assemblePanel] 的「就地复用」），所以格子根在屏幕上的位置
     * 与上一次布局录下的一样 —— 只需把「前 count 个矩形」留下、并按新内容重建
     * [itemEntries] / [itemContents]。这一步是几十次引用拷贝，**没有任何跨进程查询**
     * （对比 [recordRects] 里每格一次 `getLocationOnScreen`）。
     *
     * ⚠️ 矩形**必须截断到 count**：多出来的那些矩形对应的格子此刻是 `GONE`（看不见），
     * 留着它们会让抬手命中一个「用户根本看不到的格子」。
     *
     * 几何还没录过（本代第一次渲染、布局尚未跑完）时什么都不做 —— 那种情况由
     * [recordRects] 在布局回调里一次录全。
     */
    private fun rebindLookup(count: Int) {
        if (itemRects.size < count) return
        val entries = ArrayList<AppEntry>(count)
        val contents = ArrayList<View>(count)
        for (i in 0 until count) {
            if (i < shownEntries.size) entries.add(shownEntries[i])
            contents.add(cellPool[i].content)
        }
        itemRects = itemRects.subList(0, count).toList()
        itemEntries = entries
        itemContents = contents
    }

    private fun recordRects() {
        if (shownCells.isEmpty()) {
            itemRects = emptyList()
            itemEntries = emptyList()
            itemContents = emptyList()
            return
        }
        val rects = ArrayList<Rect>(shownCells.size)
        val entries = ArrayList<AppEntry>(shownCells.size)
        // 抬起动的是**内容块**（见 itemContents 的注释）：矩形仍旧记格子根
        // （命中判定要的是整格），两者下标一一对应。
        val contents = ArrayList<View>(shownCells.size)
        val loc = IntArray(2)
        for (i in shownCells.indices) {
            val v = shownCells[i]
            v.getLocationOnScreen(loc)
            rects.add(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
            contents.add(cellPool[i].content)
            // 长度由 buildPanel 保证一致；越界就跳过这一格，
            // 宁可点不到，也不能让矩形和应用错位
            if (i < shownEntries.size) entries.add(shownEntries[i])
        }
        itemRects = rects
        itemEntries = entries
        itemContents = contents
    }

    private fun hitTest(x: Float, y: Float): Int {
        // 故意用下标循环：这条路径虽然在主线程，但它跟着手指跑，
        // for-in 会为 List 生成一个迭代器对象
        val rects = itemRects
        for (i in rects.indices) {
            if (rects[i].contains(x.toInt(), y.toInt())) return i
        }
        return -1
    }

    /**
     * 高亮某一格：**抬起格子里的内容块**，而不是抬起整个格子。
     *
     * ## 为什么抬内容块（这是「顶行高亮被裁」的修复）
     *
     * 现象：选中的那一格在**顶上一行**时，抬起的那一截被 [panelScroll]（ScrollView）裁掉。
     * 根因：改动前抬的是**整个格子**（`cell.root.translationY`），而面板顶 = 手指 y − 70dp、
     * 格高 ≥ 88dp，所以正常使用时手指**永远落在第一行** —— 第一行的格子上沿正好是视口上沿，
     * 抬起 18dp 就必然越过它被裁掉。
     *
     * 修法与 Flyme 对齐（`layout_assearch_appgroup_single_app.xml` +
     * `ASAppGroupSingleAppLayout.setupViewSpecs()`）：真机抬的是格子里那个
     * `mContentLayout`（内容块），而格子根是 `gravity = CENTER`、内容块 `wrap_content` ——
     * 内容块上下各有 `(cellH − 内容块高) / 2` 的余量，**抬起量不超过余量就永远待在格子内部**，
     * 于是与第几行、滚到哪里无关，第一行也不会被裁。
     *
     * ## 抬升量是算出来的（不是写死的 18dp）
     *
     * `min(格高 / [SideIndexLook.HIGHLIGHT_LIFT_DIVISOR], 内容块上方的余量)` ——
     * 见 [PanelMetrics.highlightLiftPx] 的 KDoc。默认档（格高 108dp / 图标 50dp / 显示名字）
     * 下等于 `dp(18)`，**逐位等于 Flyme**；格高调小或图标调大时自动变小，绝不越界。
     *
     * ## 抬升量算成 0 时改用底色
     *
     * 格高贴到「刚好等于内容块高」（图标 80dp + 格高 101dp 这种组合）时，格子内部**没有**
     * 任何余量可抬 —— 那时改用一层很淡的底色表达选中（[CELL_SELECT_BG]），
     * 总不能让用户看不出自己点的是哪一格。这一层是**我们补的**（Flyme 没有）。
     *
     * ## 动效参数照 Flyme
     *
     * 100ms、`PathInterpolator(0.3,0,0.67,1)`（`mUpAnimator` / `mDownAnimator`）。
     * 抬起/放下都只动「最后一次布局时的那批 View」，保证动画落在用户真正看到的格子上。
     */
    private fun setHighlight(index: Int) {
        if (index == highlighted) return

        /**
         * 这一格**实测**的格内上方余量（px）= 内容块在格子里的 top（`gravity = CENTER`
         * 摆出来的就是它）。优先用它而不是模型值 [PanelMetrics.contentSlackPx]：
         * 名字是 **sp**，行高随系统字号放大，实测值能自动跟上（模型按 17dp 算，
         * 字号放大后会偏大 → 抬升量偏大 → 顶出格子被裁）。布局还没跑完（height == 0、
         * 复用的格子第一次绑定时）退回模型值，那一条带 [SideIndexLook.CONTENT_FIT_SAFETY_DP] 兜底。
         *
         * 这里只读两个 `int`：每个 MOVE 帧都会走，不许有任何分配。
         */
        val measuredSlack = cellPool.getOrNull(index)
            ?.content?.let { if (it.height > 0) it.top else NO_MEASURE }
            ?: NO_MEASURE
        val slack = if (measuredSlack == NO_MEASURE) metrics.contentSlackPx else measuredSlack
        val lift = minOf(metrics.highlightLiftIdealPx, slack.coerceAtLeast(0))
        val needFallback = lift <= 0

        /** 抬起量算成 0 时补一层底色：没有余量可抬时它就是唯一的选中表达。 */
        fun paintFallback(cellIndex: Int, on: Boolean) {
            if (cellIndex !in cellPool.indices) return
            cellPool[cellIndex].bg.setColor(if (on) CELL_SELECT_BG else Color.TRANSPARENT)
        }

        if (highlighted in itemContents.indices) {
            val prev = itemContents[highlighted]
            prev.animate().cancel()
            prev.animate()
                .translationY(0f)
                .setDuration(HIGHLIGHT_MS)
                .setInterpolator(selectInterpolator)
                .start()
            // 上一层（如果补过底色）要一起收掉，否则会留下一个亮着的旧格子
            paintFallback(highlighted, false)
        }
        highlighted = index
        if (index in itemContents.indices) {
            val cur = itemContents[index]
            cur.animate().cancel()
            cur.animate()
                .translationY(-lift.toFloat())
                .setDuration(HIGHLIGHT_MS)
                .setInterpolator(selectInterpolator)
                .start()
            paintFallback(index, needFallback)
            // 格子高亮落位：语义是「离散选项落到了新值」（见 SideIndexHaptics 的语义表）
            tick(SideIndexHaptics.Trigger.ITEM_FOCUS)
        }
    }

    // ── 启动与振动 ────────────────────────────────────────────────────

    private fun launch(entry: AppEntry) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(entry.packageName, entry.activityName))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
            )
        } catch (t: Throwable) {
            // 单个应用启动失败没有补救手段，也不该影响别的；记一条即可
            Log.w(TAG, "启动 " + entry.packageName + " 失败", t)
        }
    }

    /**
     * 发一次触觉反馈。**按 Android 官方规范实现**，不再自己拼 duration + amplitude。
     *
     * 官方 [Add haptic feedback to events](https://developer.android.com/develop/ui/views/haptics/haptic-feedback)
     * 把做法排了序并推荐第一种，这里逐条落实：
     *
     *  1. **首选 `View.performHapticFeedback(常量)`** —— 不需要 `VIBRATE` 权限、
     *     自动尊重系统设置 `HAPTIC_FEEDBACK_ENABLED`、**自带 fallback**（常量自己映射到
     *     该设备能做到的效果，不必我们探测设备支持），而且常量是**按交互语义**挑的
     *     （挑法全在 [SideIndexHaptics.constantFor] 的那张表里）。
     *  2. 需要更强表达时用**预定义** [VibrationEffect]（`EFFECT_TICK` / `EFFECT_CLICK`）
     *     或 `VibrationEffect.Composition` + 原语；走这两条路**必须**先验证设备支持
     *     （见 [probeHaptics]），**不支持就回落**，绝不「探测失败 = 没有反馈」。
     *
     * 官方另两条要求也照做了：UI 反馈不用长振动（预定义 tick/click 本身就是短的）；
     * 有预定义效果时不自己拼波形（上一版正是这么干的，已删除）。
     *
     * **哪一段该发什么，全部由 [SideIndexHaptics.planFor] 这个纯函数决定**，
     * 这里只做「把它的结果翻译成平台调用」这一件事 —— 判断与执行分开，
     * 判断那一半才能脱离设备被逐条验证。
     *
     * 节流沿用 [SlidePerformancePolicy.shouldEmitHaptic]（35ms，见那里的说明）。
     * 只在主线程调：`performHapticFeedback` / `vibrate` 都是跨进程调用，输入线程上禁止（规范 §7.5）。
     *
     * @param trigger 这次是哪个交互（决定用哪个语义常量 / 效果）
     * @param force true = 抬手启动那一下，不吃节流（见 [SlidePerformancePolicy.shouldEmitHaptic]）
     */
    private fun tick(trigger: SideIndexHaptics.Trigger, force: Boolean = false) {
        val plan = SideIndexHaptics.planFor(trigger, hapticMode, hapticDevice)
        if (plan.path == SideIndexHaptics.Path.NONE) return

        val now = SystemClock.uptimeMillis()
        if (!SlidePerformancePolicy.shouldEmitHaptic(lastHapticAt, now, force)) return
        lastHapticAt = now

        // performHapticFeedback 需要一个**已经挂到窗口上**的 View：本模块的 root 是
        // WindowManager.addView 加进去的，addView 返回时 mAttachInfo 已就绪。
        // root 为 null（窗口已拆/还没建）时这一跳什么都不做 —— 没有反馈是可接受的降级，
        // 但绝不能让它在主线程抛出去。
        try {
            when (plan.path) {
                SideIndexHaptics.Path.VIEW_CONSTANT ->
                    root?.performHapticFeedback(viewConstantValue(plan.constant))

                SideIndexHaptics.Path.PREDEFINED_EFFECT ->
                    vibrator?.vibrate(VibrationEffect.createPredefined(effectValue(plan.effect)))

                SideIndexHaptics.Path.COMPOSITION ->
                    vibrator?.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(primitiveValue(plan.primitive), plan.primitiveScale)
                            .compose()
                    )

                // "强"档：一次性振幅振动（比语义常量物理上更强，见 Path.ONE_SHOT 的说明）
                SideIndexHaptics.Path.ONE_SHOT ->
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(plan.oneShotMs, plan.oneShotAmplitude)
                    )

                SideIndexHaptics.Path.NONE -> Unit
            }
        } catch (t: RuntimeException) {
            // 厂商 ROM 上这些调用可能抛（例如没有实现某个预定义效果、或 vibrator 被禁用）。
            // 触觉反馈是可选功能，它失败绝不能影响「启动应用」——降级而不是上报。
            Log.w(TAG, "触觉反馈失败（" + plan.path + "/" + trigger + "），已忽略", t)
        }
    }

    // ── 纯策略 → 平台常量 ──────────────────────────────────────────────

    /**
     * [SideIndexHaptics] 是纯 Kotlin（不引 `android.*`），平台的常量值在这里落下来。
     *
     * 集中成一个 `when` 而不是散在 [tick] 里，是为了「策略里换一个常量」
     * 与「这里映射到哪个平台常量」两件事能分别被看懂。
     */
    private fun viewConstantValue(c: SideIndexHaptics.Constant): Int = when (c) {
        SideIndexHaptics.Constant.CLOCK_TICK -> HapticFeedbackConstants.CLOCK_TICK
        SideIndexHaptics.Constant.SEGMENT_TICK -> HapticFeedbackConstants.SEGMENT_TICK
        SideIndexHaptics.Constant.CONFIRM -> HapticFeedbackConstants.CONFIRM
    }

    private fun effectValue(e: SideIndexHaptics.Effect): Int = when (e) {
        SideIndexHaptics.Effect.TICK -> VibrationEffect.EFFECT_TICK
        SideIndexHaptics.Effect.CLICK -> VibrationEffect.EFFECT_CLICK
    }

    private fun primitiveValue(p: SideIndexHaptics.Primitive): Int = when (p) {
        SideIndexHaptics.Primitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
        SideIndexHaptics.Primitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
    }

    // ── 杂项 ──────────────────────────────────────────────────────────

    /**
     * 取这次渲染要用的几何：字母表没变就复用已发布的（保证输入线程的判定与这里一致）。
     *
     * 三种情况：
     *  - 已有几何、且字母表就是这次的 —— 直接用；
     *  - 已有几何、但 [index] 为 null（清单还没扫完）—— 也用已有的
     *    （那一份是 [SideIndex] 在安装时按 `AppIndex.ALPHABET` 发布的，
     *    和「还没扫完」这件事是对得上的，换掉反而会让条闪一下）；
     *  - 字母表变了 —— 重新发布（**只有主线程会走到这里**，符合发布侧的纪律）。
     */
    private fun layoutFor(index: AppIndex.Index?, onRight: Boolean): AppIndex.BarLayout? {
        val current = layout
        if (current != null && (index == null || current.letters === index.letters)) return current
        if (index != null) publishLayout(index.letters)
        return layout
    }

    /**
     * 线程闸门。不在主线程就**什么都不做**并记一条错误。
     *
     * 不抛异常：这里是从 Looper 里调出来的，抛出去就是 SystemUI 崩溃。
     * 不转投主线程：转投会把「打字错误」变成「偶发时序问题」，更难查。
     */
    private fun onMain(what: String): Boolean {
        if (Looper.myLooper() === Looper.getMainLooper()) return true
        Log.e(TAG, "$what 必须在主线程调用，已忽略（线程=" + Thread.currentThread().name + "）")
        return false
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    /** Flyme 宽度写法用不到，这里只需要「像素」；留着这行是为了让上面的调用点读起来一致。 */
    private fun ViewGroupParamsWrap(w: Int) = w

    private companion object {
        const val TAG = "FP-SideIndex"

        /**
         * 「迟到的渲染被拒」这条诊断日志的上限（每次 SystemUI 重启重置）。
         *
         * 定 20 而不是 1：复现这条竞态靠「快速划过字母条 + 立刻松手」，一个人试十几次
         * 才撞上一次很正常 —— 只打一行的话，前面几次的日志会让人误判成「没撞上」。
         * 也不能不限：万一它变成每帧都拒（判定 bug），日志会淹掉 logcat。
         */
        const val LATE_RENDER_LOG_MAX = 20

        /** 字母的宽度与贴边留白，取自 Flyme `ASLetterBarView.setupViewSpecs()`（4dp + 12dp）。 */
        const val BAR_ITEM_WIDTH_DP = 12
        const val BAR_EDGE_PADDING_DP = 4

        /**
         * 格子**内部**的排布，取自 Flyme `ASAppGroupLayout.setupViewSpecs()`
         * （图标 50dp、名字 12sp/上间距 4dp、内容左右各 3dp，
         * 见 `DeviceProfileConstants.AS_APP_CONTENT_PADDING_DP`）。
         *
         * ⚠️ 2026-09-14（需求 #5/#5c）：**格宽、格高、列数、内容内边距都不在这里** ——
         * 它们要参与「联动夹取」的推导，唯一来源是
         * [SideIndexLook.panelMetrics]（`DEFAULT_CELL_WIDTH_DP` / `DEFAULT_CELL_HEIGHT_DP` /
         * `DEFAULT_COLUMNS` / `CELL_CONTENT_PAD_H_DP`）。本类只读 [metrics] 的结果，
         * 不再有第二处算式 —— 「夹取用 A、布局用 B」是不会有任何报错的那种不一致。
         */
        const val NAME_TOP_MARGIN_DP = 4
        const val NAME_SP = 12f

        /**
         * 面板**视口**最多几行。Flyme `ASAppGroupLayout.setupViewSpecs()`：
         * `mListViewMaxHeight = mAppHeight * 3` —— 视口最高 3 行，超出的部分靠滚动。
         */
        const val PANEL_MAX_ROWS = 3

        /**
         * 跟手指的偏移。Flyme `MzSlideAppsSearchPhoneImpl.initialize`：
         * `mTouchSlop = MzCommonUtils.dpToPx(launcher, 70.0f)`（名字叫 slop，实际是面板顶
         * 相对手指往上让出的距离），`touchYChange` 里就是 `y - mTouchSlop`。
         */
        const val PANEL_FINGER_OFFSET_DP = 70

        /** 面板与字母条之间给细条留的缝，dp（Flyme：32 − 28）。 */
        const val PANEL_THUMB_GAP_DP = 4

        /** 细条的尺寸与上边距，Flyme `ASAppGroupLayout.setupViewSpecs()`：4dp × 184dp、topMargin 18dp。 */
        const val THUMB_W_DP = 4
        const val THUMB_H_DP = 184
        const val THUMB_TOP_DP = 18
        const val THUMB_RADIUS_DP = 2

        /** 自动滚动的 tick 与步长分母，Flyme 那个滚动线程：`sleep(15)` + `mAppHeight / 15`。 */
        const val SCROLL_TICK_MS = 15L
        const val SCROLL_STEP_DIVISOR = 15
        const val SCROLL_NONE = 1
        const val SCROLL_UP = 0
        const val SCROLL_DOWN = 2

        /** 大字母（Flyme 的 `mLetterTextView`）：50dp 方框、32dp 字号。 */
        const val BUBBLE_DP = 50
        const val BUBBLE_SP = 32f
        const val BUBBLE_GAP_DP = 4

        /**
         * 特殊槽位图标在气泡里的边长（sp）。
         *
         * 取自 Niagara 的 `dimen/scrollbar_handle_icon_size` = 28sp —— 它的 handle 直径
         * 44sp、文字 28sp，图标与文字同档。我们的气泡是 50dp/
         * 32sp，按同一比例取 28sp。
         */
        const val BUBBLE_ICON_SP = 28f

        /**
         * 大字母与面板上沿的距离。Flyme 那边大字母装在「字母容器」里，容器 padding 取自
         * `DeviceProfileConstants.AS_CURRENT_LETTER_CONTAINER_PADDING_RECT_DP = (4, 0, 4, 8)`
         * —— 贴边侧 4dp、下侧 8dp，所以这里用 8。
         */
        const val BUBBLE_TOP_GAP_DP = 8

        const val SAFE_EDGE_DP = 4

        /**
         * 选中一个格子时的时长与插值器，取自 Flyme
         * `ASAppGroupSingleAppLayout.setupViewSpecs()`：100ms、
         * `PathInterpolator(0.3f, 0f, 0.67f, 1f)`。
         *
         * ⚠️ **抬升距离不在这里**：Flyme 是写死的 `-dpToPx(18)`，我们**刻意偏离** ——
         * 抬升量由 [PanelMetrics.highlightLiftPx] 按「格高 / 6 ⊓ 格内余量」算出来
         * （理由：格高可调，固定 18dp 在格高小时会把内容块顶出格子被裁）。
         */
        const val HIGHLIGHT_MS = 100L

        /**
         * 选中格兜底底色（我们加的，Flyme 没有）：只在抬升量算成 0 那一档用（见 [setHighlight]）。
         * 很淡 —— 它是「没有别的表达方式可用」时的兜底，不该抢眼。
         */
        const val CELL_SELECT_BG = 0x26FFFFFF

        /** 「这一格的内容块还没被布局过」的哨兵（用 `Int.MIN_VALUE` 而不是 0，
         *  因为余量真的可以是 0 —— 那时是「没余量可抬」，要走去底色的那一支）。 */
        const val NO_MEASURE = Int.MIN_VALUE

        /** 浮层淡入：Flyme `ASearchLayout.animateShow()` 的 150ms。 */
        const val SHOW_MS = 150L

        /** 模糊的压暗量，取自 Appindex 的 `configureBlurBehavior`。
         *
         * 半径**不再是常量**：它是 `side_index.blur_radius`（需求 #7 档 A），
         * 默认值在 [SideIndexLook.DEFAULT_BLUR_RADIUS_DP]（= 20dp = 改动前这里的值）。 */
        const val BLUR_DIM_AMOUNT = 0.15f

        /** 占位文字（Appindex `showAppListLoadingState` 的「读取应用中…」）。 */
        const val PLACEHOLDER_LOADING = "读取应用中…"
        const val PLACEHOLDER_EMPTY = "没有可启动的应用"
        const val PLACEHOLDER_SP = 12f

        const val LETTER_IDLE = 0x78FFFFFF
        const val NAME_COLOR = 0xCCFFFFFF.toInt()
        const val BUBBLE_BG = 0xCC222222.toInt()
        const val BUBBLE_STROKE = 0xAAFFFFFF.toInt()
        const val PLACEHOLDER_COLOR = 0x99FFFFFF.toInt()

        /** 细条取色：字母条未选中白的降 alpha 版（参考项目的 drawable 拿不到，见 ensurePanel）。 */
        const val THUMB_COLOR = 0x66FFFFFF

        /** Appindex 的 `showFloatingWindow` 遮罩渐变（未开模糊时的那一对）。 */
        const val DIM_TOP = 0xCC1A1A2E.toInt()
        const val DIM_BOTTOM = 0xBB000000.toInt()

        /**
         * 开模糊时的那一对（Appindex 同处的 `1427773998 / 1426063360`）。
         * 同一组颜色的半透明版 —— 模糊本身已经拉开了层次，遮罩不必那么实。
         */
        const val DIM_BLUR_TOP = 0x551A1A2E
        const val DIM_BLUR_BOTTOM = 0x55000000

        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

        /**
         * 触觉能力探测，**一次安装一次**。
         *
         * ## 为什么必须探，以及为什么探不到也安全
         *
         * 官方规范对三条路径的要求不一样：
         *  - `View.performHapticFeedback` 那条**不需要探测**（常量自带 fallback，
         *    底层实现随设备不同而不同，那是平台的事）；
         *  - 预定义 `VibrationEffect` 那条**必须**先确认设备支持，否则「调了没反应」；
         *  - `Composition` + 原语那条还要额外确认 `hasAmplitudeControl()`：
         *    ERM（转子马达）没有振幅控制，给它 `scale` 只会被劣化成「通/断」，
         *    听感比一句短 tick 更糟，所以**干脆不走**。
         *
         * 探测失败（厂商 ROM 抛异常、`Vibrator` 拿不到）时返回
         * [SideIndexHaptics.DeviceSupport.UNKNOWN]（全 false）：于是后两条路自动
         * 回落到第一条路，而第一条路**不需要任何能力**。也就是「探测失败」
         * 只会让反馈变回系统默认手感，**不会变成没有反馈**。
         *
         * 探测结果在构造时算一次（见 [hapticDevice]）：这几个方法都是跨进程调用，
         * 不能放在跟着手指跑的 [tick] 里。
         */
        fun probeHaptics(vibrator: Vibrator?): SideIndexHaptics.DeviceSupport {
            val v = vibrator ?: return SideIndexHaptics.DeviceSupport.UNKNOWN
            return runCatching {
                val has = v.hasVibrator()
                SideIndexHaptics.DeviceSupport(
                    hasVibrator = has,
                    // ⚠️ areAllEffectsSupported 返回的是**状态码 int**（不是 boolean）：
                    //    VIBRATION_EFFECT_SUPPORT_YES / _UNKNOWN / _NO。
                    //    只认 YES：UNKNOWN 也当不支持，于是回落到 View 常量那条不需要探测的路
                    //    —— 「不敢确定」时选保守的那条，而不是赌设备支持。
                    supportsPredefinedTick = has &&
                        v.areAllEffectsSupported(VibrationEffect.EFFECT_TICK) ==
                        Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                    supportsPredefinedClick = has &&
                        v.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) ==
                        Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                    // 原语这两个返回的是干净的 boolean（areAllPrimitivesSupported，不是
                    // arePrimitivesSupported —— 后者给的是每个原语的 boolean[]）。
                    supportsPrimitiveTick = has && v.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                    ),
                    supportsPrimitiveClick = has && v.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                    ),
                    hasAmplitudeControl = has && v.hasAmplitudeControl(),
                )
            }.getOrDefault(SideIndexHaptics.DeviceSupport.UNKNOWN)
        }

        /**
         * 导航栏底部让位。Flyme `MzSlideAppsSearchPhoneImpl.updateBottomSpace()`：
         *
         * ```java
         * mBottomSpace = (getResources().getInteger(android.R.integer.config_navBarOpacityMode) == 2)
         *     ? dpToPx(30.0f) : 0;
         * ```
         *
         * `config_navBarOpacityMode` 是 @hide 资源，编译期拿不到，所以按名字查。
         * **查不到就返回 0**（参考项目的 else 分支），不猜。
         */
        fun navBarBottomSpacePx(context: Context): Int {
            val res = context.resources
            val id = try {
                res.getIdentifier("config_navBarOpacityMode", "integer", "android")
            } catch (_: Throwable) {
                return 0
            }
            if (id == 0) return 0
            val mode = try {
                res.getInteger(id)
            } catch (_: Throwable) {
                return 0
            }
            return if (mode == 2) (30 * res.displayMetrics.density).toInt() else 0
        }
    }
}
