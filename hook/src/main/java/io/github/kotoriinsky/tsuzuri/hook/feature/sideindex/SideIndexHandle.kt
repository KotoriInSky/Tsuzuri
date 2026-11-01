package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 边缘把手：常驻在屏幕边缘的那个窄条，告诉用户「这里能侧滑」。
 *
 * 它对应 Appindex 的**触发区**（`FloatingService.createTriggerArea()` 里的
 * `triggerView` / `triggerViewLeft`）。在参考项目里那个 View 是**可触摸**的：
 * 手指按在上面触摸流就归它，它因此既是「提示」也是「热区」。
 *
 * ## 我们**必须**把它做成不可触摸（这是本文件最要紧的一条）
 *
 * 我们的触摸来自 WMShell 的输入监视通道
 * （[io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SwipeEventHub]）。如果把手还能吃触摸，
 * 同一条边缘上就有了**两个 touch owner**：
 * 手指落在把手上时事件归 View、落在把手外的 1px 归输入监视器，行为会随手指落在
 * 哪一列而跳变，而且我们**拿不到** View 那条流（它不会经过 `MulWinSwitchEventController`），
 * 于是「看得见的把手」和「真正能触发的手势」不再是同一块区域。所以整个窗口
 * `FLAG_NOT_TOUCHABLE`：它**只是画**，判定仍然全部在 [SideIndexDetector] 里。
 *
 * ## 几何（全部取自 Appindex 的 createTriggerArea）
 *
 * ```java
 * int   triggerWidth   = prefs.getInt("trigger_width", 12);
 * float heightRatio    = prefs.getFloat("trigger_height_ratio", 0.45f);
 * float verticalOffset = prefs.getFloat("trigger_vertical_offset", 0.69f);
 * int screenHeight = getResources().getDisplayMetrics().heightPixels;
 * int triggerHeight = Math.max(1, (int) (screenHeight * heightRatio));
 * lp.gravity = 8388661;                       // TOP|RIGHT|RELATIVE_LAYOUT_DIRECTION
 * lp.y = (int) ((screenHeight - triggerHeight) * verticalOffset);
 * ```
 *
 * 左把手是同一个窗口参数、`gravity = 8388659`（TOP|LEFT）。在 Kotlin 里这两个数
 * 就是 `Gravity.TOP or Gravity.END` / `Gravity.TOP or Gravity.START`，取值完全相等。
 * 宽度用我们自己的 `trigger_width`（dp）—— 它就是 [SideIndexDetector] 的判定带宽，
 * 所以**看得见的把手刚好覆盖有效热区**，不会出现「看着在把手外面也能触发」。
 *
 * ## 外观从哪来
 *
 * Appindex 的运行期把手是**全透明**的（`view.setBackgroundColor(0)`），
 * 它只有在设置页的预览里才有颜色，用的是自己 colors.xml 里那对专门给触发区用的颜色：
 *
 * ```xml
 * <color name="trigger_preview_color">#44ff6b00</color>   <!-- 27% 橙，填充 -->
 * <color name="trigger_preview_border">#ffff6b00</color>  <!-- 全不透明橙，1dp 描边 -->
 * ```
 *
 * 我们照用这两个值：把手**必须看得见**才有存在的意义（用户要的正是一个
 * 「这里能滑」的提示），而这两个数是参考项目里唯一一组为「触发区」定义的颜色。
 * 参考项目没给圆角，所以这里也不加圆角 —— 不发明参考里没有的东西。
 *
 * ## 可见性
 *
 * 两条规则叠在一起（对应 Appindex 的 `applyTriggerVisibility`）：
 *  1. **生效范围**不允许时隐藏（它的 `scopeAllowsEdgeTrigger`，见 [SideIndexScope]）；
 *  2. **手势进行中隐藏**（参考项目里浮窗一起就把触发条盖住了，我们显式设 INVISIBLE）。
 *
 * 一律用 `INVISIBLE` 而不是 removeView：窗口是可复用的，每次手势拆装一次窗口
 * 就是每一次手势多两次 WindowManager 往返（而 `invisible` 的窗口不参与合成）。
 *
 * ## 参数改完立刻生效
 *
 * 把手相关的四项（`show_handle` / 本类几何用的 `trigger_width` / `handle_height` /
 * `handle_offset`，外加决定哪几条边的 `side`）**全部靠 [applyLook] 热改**，不重启进程：
 *
 *  - `show_handle` 关 → [SideIndex] 直接 `release()` 本对象（连窗口都不留）；
 *  - `show_handle` 开 → 重新建对象再 [applyLook]；
 *  - 其余四项 → 对**已有**窗口 [applyLook]：边变了增删窗口，尺寸/位置走 `updateViewLayout`。
 *
 * 它和浮层的处理方式**故意不同**：浮层是每次手势新建、抬手就拆的，所以整个换代很便宜
 * （见 `SideIndex.overlaySwapTick`）；而把手是**常驻**窗口，换代 = 拆一次建一次 =
 * 两次 WindowManager 往返 + 肉眼可见的闪一下。所以这里只能增量改。
 *
 * ## 线程
 *
 * 所有 public 方法都只允许在**主线程**调（`addView` / `removeViewImmediate` 是
 * ViewRootImpl 的操作）。唯一的例外是 [release]：它由 [SideIndex.uninstall] 在框架
 * 线程上调用，所以它自己负责转投主线程。
 */
internal class SideIndexHandle(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val density = context.resources.displayMetrics.density

    /** 只用来把 [release]（可能来自框架线程）转投主线程。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 主线程侧的可见性 / 生命周期只在这里读写。 */
    private var attached = false

    /** 上一次真正落到视图上的可见性，只用于「变化时才打日志」。 */
    private var appliedVisibility = View.VISIBLE

    private var rightView: View? = null
    private var leftView: View? = null

    /**
     * 当前**应该**有哪几条边（来自配置 `side`）。
     *
     * 与 [rightView] / [leftView] 分开记：前者是「意图」，后者是「现实」——
     * 增删窗口只有在**意图变了**的时候才发生，其余的变化（尺寸、位置）
     * 走 `updateViewLayout`，那是**一条** WindowManager 往返，拆了重建是两条。
     */
    private var wantLeft = false
    private var wantRight = false

    /** 上一次落地的几何（px）。用来判断「这次要不要动窗口」。[geomValid] = false 表示还没算过。 */
    private var geomWidthPx = 0
    private var geomHeightPx = 0
    private var geomTop = 0
    private var geomValid = false

    /**
     * 当前生效范围是否允许显示。
     *
     * 由 [SideIndex] 的主线程轮询写入（对应 Appindex 的 `scopeAllowsEdgeTrigger`，
     * 它也是被一个 250ms 的轮询 handler 更新的）。写和读都在主线程，这里不用
     * `@Volatile` 是刻意的：只有主线程会碰它，加 volatile 只会掩盖线程错误。
     */
    private var scopeAllowed = false

    /** 手势进行中。由 [SlidePerformancePolicy.onGesture] 在主线程回调写入。 */
    private var gestureActive = false

    /**
     * 建 / 改窗口。**主线程，幂等** —— 装的时候叫一次，之后每次参数变化（`show_handle`
     * 之外的五项里任一项）再叫一次，**不需要重启进程**。
     *
     * ## 为什么必须幂等
     *
     * 上一版这个函数是「建窗口」的一次性入口：再调一次就会**再加一遍 View**，
     * 于是同一条边上叠出两个把手，而且旧的再也没有引用能拆掉它。
     * 现在它同时是热应用入口，所以每一步都按「现实 vs 意图」对齐：
     *
     *  - 该有而没有的边 → 建（新建时直接用当前几何）；
     *  - 不该有却有的边 → 拆；
     *  - 尺寸与位置 → [updateGeometry] 的 `updateViewLayout`（不拆窗口，见那里的注释）。
     *
     * ## 参数
     *
     * @param widthDp `trigger_width`，同时是判定带宽，所以把手宽度和热区宽度一致
     * @param heightPercent `handle_height`，Appindex `trigger_height_ratio`（默认 45）
     * @param offsetPercent `handle_offset`，Appindex `trigger_vertical_offset`（默认 69）
     */
    fun applyLook(
        allowLeft: Boolean,
        allowRight: Boolean,
        widthDp: Float,
        heightPercent: Float,
        offsetPercent: Float,
    ) {
        if (!onMain("applyLook")) return
        val w = wm ?: return

        // 先算几何：新建的窗口要直接落在正确的位置上（免得「先出现在旧位置再跳一下」）
        val geomChanged = computeGeometry(widthDp, heightPercent, offsetPercent)
        wantLeft = allowLeft
        wantRight = allowRight

        if (allowRight) {
            if (rightView == null) rightView = addSide(w, Gravity.END)
        } else if (rightView != null) {
            removeSide(rightView)
            rightView = null
        }
        if (allowLeft) {
            if (leftView == null) leftView = addSide(w, Gravity.START)
        } else if (leftView != null) {
            removeSide(leftView)
            leftView = null
        }

        // 尺寸/位置只在**真的变了**的时候推：`handle_height` 这类滑杆拖起来会连着来几十次，
        // 每次都推一遍会让 WM 做几十次无意义的 relayout（同 setScopeAllowed 的理由）。
        if (geomChanged) updateGeometry(w)

        // attached 反映**现实**而不是意图：两边都没建出来（addView 失败）时，
        // 它是 false —— 于是 isVisible 也是 false，可见性日志不会报出一个不存在的把手。
        attached = rightView != null || leftView != null
        applyVisibility()
    }

    /**
     * 算出当前的窗口几何（px），并返回它**相对上一次**变了没有。
     *
     * 三个数的算法与 Appindex `createTriggerArea` 逐行对应，全部写在这里、
     * 一份来源（[addSide] 与 [updateGeometry] 都读这一份结果，见 [geomValid]）。
     */
    private fun computeGeometry(widthDp: Float, heightPercent: Float, offsetPercent: Float): Boolean {
        val metrics = context.resources.displayMetrics
        val widthPx = (widthDp * density).toInt().coerceAtLeast(1)
        // Math.max(1, screenHeight * 0.45) —— Appindex 原文
        val ratio = (heightPercent / 100f).coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO)
        val heightPx = (metrics.heightPixels * ratio).toInt().coerceAtLeast(1)
        // lp.y = (screenHeight - triggerHeight) * 0.69
        val offset = (offsetPercent / 100f).coerceIn(0f, 1f)
        val top = ((metrics.heightPixels - heightPx) * offset).toInt()

        val changed = !geomValid || widthPx != geomWidthPx || heightPx != geomHeightPx || top != geomTop
        geomWidthPx = widthPx
        geomHeightPx = heightPx
        geomTop = top
        geomValid = true
        return changed
    }

    /**
     * 把算好的几何推给**已经存在**的窗口。**主线程**。
     *
     * 用 `updateViewLayout` 而不是「拆了重建」：窗口是可复用的（见类注释里
     * 「一律用 INVISIBLE 而不是 removeView」那条），改 `lp` 再推给 WM 只有一次往返，
     * 而且**不会闪**。`lp` 就地改是安全的 —— 它本来就是从 `v.layoutParams` 取回来的同一个对象。
     */
    private fun updateGeometry(w: WindowManager) {
        for (v in listOfNotNull(rightView, leftView)) {
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: continue
            lp.width = geomWidthPx
            lp.height = geomHeightPx
            lp.y = geomTop
            // 窗口可能已经被别处移除（卸载竞态）—— 那不是错误，但必须看得见
            runCatching { w.updateViewLayout(v, lp) }
                .onFailure { Log.w(TAG, "把手尺寸/位置更新失败（窗口可能已被移除）") }
        }
    }

    private fun addSide(w: WindowManager, horizontal: Int): View? {
        val v = View(context).apply { background = gripDrawable() }

        // FLAG_NOT_FOCUSABLE  不抢焦点
        // FLAG_NOT_TOUCHABLE  见类注释：同一条边缘不能有第二个 touch owner
        // FLAG_LAYOUT_IN_SCREEN + NO_LIMITS  让 y 直接是屏幕绝对坐标（不然会被状态栏挤下来）
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            geomWidthPx,
            geomHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Appindex 的 8388661 / 8388659（TOP|RIGHT / TOP|LEFT 的 RELATIVE_LAYOUT_DIRECTION 写法）
            gravity = Gravity.TOP or horizontal
            x = 0
            y = geomTop
        }
        return if (runCatching { w.addView(v, lp) }.isSuccess) {
            v
        } else {
            // 加不上就是没有提示条，功能本身不受影响（判定不依赖它），记一条即可
            Log.w(TAG, "边缘把手加不上窗口，本次不显示（功能不受影响）")
            null
        }
    }

    /** 拆掉一个窗口。已经在别处被移除时会抛，清理不允许因此中断。 */
    private fun removeSide(v: View?) {
        val w = wm ?: return
        if (v == null) return
        runCatching { w.removeViewImmediate(v) }
    }

    /**
     * 生效范围变化。**主线程**（由 [SideIndex] 的轮询回调）。
     *
     * 和 Appindex 的 `applyForegroundAppGate` 一样，只在**真的变了**的时候才动视图：
     * 那个轮询是 250ms 一次，每次都写一遍可见性会让 WM 反复做无意义的 relayout。
     */
    fun setScopeAllowed(allowed: Boolean) {
        if (!onMain("setScopeAllowed")) return
        if (allowed == scopeAllowed) return
        scopeAllowed = allowed
        applyVisibility()
    }

    /** 手势开始/结束。**主线程**（由 [SlidePerformancePolicy] 的主线程 tick 回调）。 */
    fun setGestureActive(active: Boolean) {
        if (!onMain("setGestureActive")) return
        if (active == gestureActive) return
        gestureActive = active
        applyVisibility()
    }

    /** 当前是否显示。诊断用（主线程读）。 */
    val isVisible: Boolean get() = attached && scopeAllowed && !gestureActive

    /**
     * 拆掉窗口。可以**从任何线程**调（[SideIndex.uninstall] 跑在框架线程上）：
     * 不在主线程就转投主线程。
     */
    fun release() {
        if (onMain("release")) {
            releaseNow()
        } else {
            mainHandler.post { releaseNow() }
        }
    }

    private fun releaseNow() {
        removeSide(rightView)
        removeSide(leftView)
        rightView = null
        leftView = null
        // 现实与意图都清空：release 之后如果又被 applyLook 叫到（热应用装上了新的一代），
        // 它必须**从头重建**，而不是以为窗口还在（那会让「关掉再打开」什么都不显示）
        wantLeft = false
        wantRight = false
        geomValid = false
        attached = false
        scopeAllowed = false
        gestureActive = false
    }

    private fun applyVisibility() {
        // Appindex 的 applyTriggerVisibility 是 View.VISIBLE : View.INVISIBLE
        // （不是 GONE —— 它要保持窗口存在，只改可见性）
        val v = if (isVisible) View.VISIBLE else View.INVISIBLE
        // 只在**状态真的翻转**的那一帧打一条：把手突然不见了是最容易让人困惑的故障，
        // 「为什么不见」必须能从日志里读出来（attached / scope / gesture 三个因子里是哪个）。
        if (v != appliedVisibility) {
            appliedVisibility = v
            Log.i(
                TAG,
                "把手可见性=" + (if (v == View.VISIBLE) "VISIBLE" else "INVISIBLE") +
                    "（attached=" + attached + " scope=" + scopeAllowed +
                    " gesture=" + gestureActive + "）",
            )
        }
        rightView?.visibility = v
        leftView?.visibility = v
    }

    /**
     * 把手的样子：Appindex `colors.xml` 里专为触发区定义的那对颜色。
     *
     * 填充 `#44ff6b00`（27% 橙）+ 1dp 描边 `#ffff6b00`，圆角 0（参考项目没有圆角）。
     * 描边是 1dp 而不是更多：把手只有十几 dp 宽，粗描边会把填充吃掉。
     */
    private fun gripDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(HANDLE_FILL)
        setStroke(dp(1), HANDLE_BORDER)
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    /**
     * 线程闸门。和 [SideIndexOverlay.onMain] 同一套规矩：不在主线程就什么都不做并记一条。
     * 只对 [release] 例外（它会转投主线程，见那里的注释）。
     */
    private fun onMain(what: String): Boolean {
        if (Looper.myLooper() === Looper.getMainLooper()) return true
        Log.e(TAG, "把手 $what 必须在主线程调用，已忽略（线程=" + Thread.currentThread().name + "）")
        return false
    }

    private companion object {
        const val TAG = "FP-SideIndex"

        /** 高度比例与纵向偏移的合法区间（也是 Catalog 该用的 min/max）。 */
        const val MIN_HEIGHT_RATIO = 0.1f
        const val MAX_HEIGHT_RATIO = 1f

        /** Appindex `colors.xml`：trigger_preview_color / trigger_preview_border。 */
        const val HANDLE_FILL = 0x44FF6B00
        const val HANDLE_BORDER = 0xFFFF6B00.toInt()
    }
}
