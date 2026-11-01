package io.github.kotoriinsky.tsuzuri.hook.feature.aggpayment

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 聚合支付面板：**一个窗口、两层内容**。
 *
 * ```
 * FrameLayout (root, 全屏)
 *  ├─ scrim  全屏纯黑遮罩，点它收起
 *  └─ card   居中卡片：标题 / N 个快捷方式 / 提示行
 * ```
 *
 * ## 为什么是原生 View 而不是 Compose
 *
 * 同 [io.github.kotoriinsky.tsuzuri.hook.feature.sideindex.SideIndexOverlay]：这段代码跑在
 * **SystemUI 进程**里，往系统进程带 Compose 运行时是红线。
 * 一个卡片加几个图标，原生 View 完全够用。
 *
 * ## 和 SideIndexOverlay 的关键差别：这个窗口**必须吃触摸**
 *
 * 侧滑索引的浮层是 `FLAG_NOT_TOUCHABLE`（它靠输入监视通道判命中）。本面板相反 ——
 * 用户就是要点上面的图标，所以窗口必须可触摸，这是它能工作的前提。
 * 代价是：**我们在锁屏上开了一个吃触摸的全屏窗口**，所以
 *  - 只有 [show] 之后它才存在，[dismiss] 立刻摘掉（不留常驻窗口）；
 *  - 只收点击，不收长按以外的任何手势；不吃返回键（`FLAG_NOT_FOCUSABLE`，不碰 IME/焦点）。
 *
 * ## 层级与 flag
 *
 * `TYPE_APPLICATION_OVERLAY`(2038)：WindowManager 的层级表里它在 keyguard 之上，
 * 所以在锁屏上也画得出来（这正是本特性要的形态）。设备侧不需要
 * `SYSTEM_ALERT_WINDOW` 的 AppOps 放行 —— SystemUI 本身就持有该权限（实测 granted=true）。
 *
 * 线程纪律（和浮层一致，硬要求）：[show]/[dismiss] **只允许在主线程**调，
 * `addView` / `removeViewImmediate` 是 ViewRootImpl 的操作，从别的线程调会把整棵
 * View 树挂到那个线程上，后续问题延迟暴露且极难定位。不在主线程就记一条错误并返回，
 * 绝不继续往下走。[release] 例外 —— 它可能从框架线程调（[AggregatePayment.uninstall]），
 * 内部自己转投主线程。
 */
internal class PaymentPanel(
    private val context: Context,
    /** 点了某一项。由 [AggregatePayment] 注入（内部走 ShortcutRepository.start）。 */
    private val onPick: (PaymentShortcut) -> Unit,
    /** 长按某一项，只用来把它的来源信息显示在提示行上（见下方注释）。 */
    private val onInfo: (PaymentShortcut) -> Unit,
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    /**
     * 已经卸载了。
     *
     * 卸掉之后可能还有已经排进主线程队列的 show 请求，没有这道闸门它们会把窗口
     * 重新建出来 —— 那就正好违反「关态必须与未安装模块完全一致」。
     */
    @Volatile
    private var released = false

    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    private var grid: LinearLayout? = null
    private var hint: TextView? = null

    /** 格子池。面板每次 show 都换内容，但格子数不会超过默认那四条 + 兜底候选。 */
    private val cells = ArrayList<Cell>(8)

    /** 当前这一屏的条目，下标与 [cells] 一一对应。 */
    private var shown: List<PaymentShortcut> = emptyList()

    private class Cell(
        val root: LinearLayout,
        val iconBox: FrameLayout,
        val icon: ImageView,
        val initial: TextView,
        val label: TextView,
    )

    // ── 对外 ──────────────────────────────────────────────────────────

    /**
     * 显示面板。**主线程**。
     *
     * @param items 已经排好序的条目（排序是 [ShortcutRepository] 的事）。
     *              空列表也会显示 —— 显示的是「没有可用的支付快捷方式」，
     *              而不是什么都不弹：什么都不弹会被当成「模块坏了」。
     */
    fun show(items: List<PaymentShortcut>) {
        if (released) return
        if (!onMain("show")) return
        val r = ensureWindow() ?: return

        val c = card ?: return
        // 空列表也要说清楚是「没有」而不是「坏了」—— 弹一个只有标题的卡片比不弹更让人困惑
        hint?.text = if (items.isEmpty()) HINT_EMPTY else HINT_IDLE
        buildGrid(items)
        // 卡片按内容重新量一次，避免上一次的尺寸残留
        c.requestLayout()
        r.bringToFront()
    }

    /** 收起面板。**主线程**。点遮罩、点完条目、异常路径都走它。 */
    fun dismiss() {
        if (!onMain("dismiss")) return
        val w = wm ?: return
        root?.let { runCatching { w.removeViewImmediate(it) } }
        root = null
        card = null
        grid = null
        hint = null
        cells.clear()
        shown = emptyList()
    }

    /**
     * 卸载时收干净。可以**从任何线程**调：不在主线程就转投主线程。
     *
     * 转投有个前提：`released` 先置位，所以转投过去的 `dismiss` 之后即使再有
     * 排队的 show 进来也会被闸门挡掉。
     */
    fun release() {
        released = true
        if (onMain("release")) {
            dismiss()
        } else {
            main.post { dismiss() }
        }
    }

    // ── 窗口 ──────────────────────────────────────────────────────────

    private fun ensureWindow(): FrameLayout? {
        root?.let { return it }
        val w = wm ?: return null

        val r = FrameLayout(context)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // 不抢输入焦点：绝不弹输入法、不改 IME 目标。代价是收不到返回键，
            // 所以「怎么关」必须靠点遮罩 —— 提示行会写出来。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // 不需要 FLAG_SHOW_WHEN_LOCKED：那个 flag 是给 Activity 窗口用的，
        // 而 TYPE_APPLICATION_OVERLAY 在 WindowManager 的层级表里本来就排在 keyguard 之上。

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        if (runCatching { w.addView(r, lp) }.isFailure) {
            // 加不上就什么都不显示。这是降级，不是错误路径 —— 记一条就够了。
            Log.w(TAG, "加面板窗口失败，本次不显示")
            return null
        }

        r.addView(scrim(), FrameLayout.LayoutParams(MATCH, MATCH))
        val c = buildCard()
        r.addView(
            c,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )
        root = r
        card = c
        return r
    }

    /**
     * 遮罩：全屏纯黑 + 点击收起。
     *
     * 自己做 View 而不是 `FLAG_DIM_BEHIND`：后者要额外的 flag、且会把整个窗口背后的
     * 图层统一压暗，控制不了「卡片周围更暗一点」这种层次。
     */
    private fun scrim(): View = View(context).apply {
        background = GradientDrawable().apply { setColor(SCRIM) }
        isClickable = true
        setOnClickListener { dismiss() }
    }

    /**
     * 卡片。竖排三段：标题 / 图标网格 / 提示行。
     *
     * `isClickable = true` + 空监听：把落在卡片上的点击吃掉，
     * 否则穿透到下面的遮罩会把面板关掉（点图标时手抖一下就会踩到）。
     */
    private fun buildCard(): LinearLayout {
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(PAD_H), dp(PAD_TOP), dp(PAD_H), dp(PAD_BOTTOM))
            background = GradientDrawable().apply {
                cornerRadius = dp(CARD_RADIUS).toFloat()
                setColor(CARD_BG)
            }
            setClipToOutline(true)
            isClickable = true
            setOnClickListener { /* 吃掉点击，见上 */ }
        }

        c.addView(TextView(context).apply {
            text = TITLE
            setTextColor(TITLE_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            includeFontPadding = false
        })

        grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(TITLE_GAP) }
        }
        c.addView(grid)

        hint = TextView(context).apply {
            text = HINT_IDLE
            setTextColor(HINT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HINT_SP)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            maxWidth = dp(CARD_MAX_W - PAD_H * 2)
        }
        c.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(HINT_GAP) })

        return c
    }

    // ── 网格 ──────────────────────────────────────────────────────────

    private fun buildGrid(items: List<PaymentShortcut>) {
        val g = grid ?: return
        shown = items
        val rows = if (items.isEmpty()) 0 else (items.size + COLUMNS - 1) / COLUMNS
        // 行池：行数只增不减，用不上的行 removeAllViews 之后就空着不显示
        while (g.childCount < rows) {
            g.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
        }
        for (i in 0 until g.childCount) {
            (g.getChildAt(i) as? LinearLayout)?.removeAllViews()
        }
        while (cells.size < items.size) cells.add(createCell())

        for (i in items.indices) {
            val row = g.getChildAt(i / COLUMNS) as? LinearLayout ?: continue
            bind(cells[i], items[i])
            row.addView(cells[i].root)
        }
    }

    /**
     * 一个格子：图标 + 名字。
     *
     * 图标拿不到（`getShortcutIconDrawable` 返回 null）时退回**纯色圆角块 + 首字**：
     * 这是刻意选的兜底 —— 「聚合支付」的价值就是用户不看图标也知道自己在点哪个应用，
     * 全空的方块会让人以为坏了。
     */
    private fun createCell(): Cell {
        val size = dp(ICON_DP)
        val radius = dp(ICON_RADIUS).toFloat()

        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val initial = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, INITIAL_SP)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER }
            visibility = View.GONE
        }
        val iconBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setClipToOutline(true)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            addView(icon)
            addView(initial)
        }
        val label = TextView(context).apply {
            setTextColor(LABEL_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            // 夹到格子宽度：名字长了会画到邻居格子上（原生 View 没有 Compose 那种裁剪）
            maxWidth = dp(CELL_W - CELL_GAP)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(CELL_GAP), dp(CELL_GAP), dp(CELL_GAP), dp(CELL_GAP))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(iconBox)
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(LABEL_GAP) })
        }
        return Cell(root, iconBox, icon, initial, label)
    }

    private fun bind(cell: Cell, item: PaymentShortcut) {
        val icon = item.icon
        if (icon != null) {
            cell.icon.setImageDrawable(icon)
            cell.icon.visibility = View.VISIBLE
            cell.initial.visibility = View.GONE
            // 有真图标就不画底：图标自带形状，加底会多一圈边框
            cell.iconBox.background = null
        } else {
            cell.icon.setImageDrawable(null)
            cell.icon.visibility = View.GONE
            cell.initial.text = item.label.firstOrNull()?.toString() ?: "?"
            cell.initial.visibility = View.VISIBLE
            cell.iconBox.background = GradientDrawable().apply {
                cornerRadius = dp(ICON_RADIUS).toFloat()
                setColor(fallbackColor(item.packageName))
            }
        }
        cell.label.text = item.label

        cell.root.setOnClickListener {
            // 先收面板再启动：启动是跨进程调用，可能卡一下；面板留着会显得「点了没反应」
            dismiss()
            onPick(item)
        }
        cell.root.setOnLongClickListener {
            // 长按的语义：把这一条例子的**来源**（包名 + shortcut id）写在提示行上。
            //
            // 为什么不是「固定/取消固定」：`requestPinShortcut` 的调用方必须被 framework
            // 认成 shortcut host（默认桌面），`disableShortcuts` 更是只能由发布者自己调；
            // 我们在 SystemUI 里两者都不是 —— 那两条路在本进程里注定抛异常或者静默无效，
            // 做出来就是「按了没反应」。所以这里给一个一定成立、且对用户/对我们都有用的信息。
            hint?.text = "来源：${item.packageName} · ${item.id}"
            onInfo(item)
            true
        }
    }

    // ── 杂项 ──────────────────────────────────────────────────────────

    /**
     * 兜底色。按包名算，同一个应用永远是同一个颜色 —— 不要随机：
     * 随机色会让同一次会话里两个格子换色，看着像 bug。
     * 取的是**低饱和深色**，白色首字压得住。
     */
    private fun fallbackColor(pkg: String): Int {
        var h = 0
        for (ch in pkg) h = h * 31 + ch.code
        val hue = ((h % 360) + 360) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.55f))
    }

    /**
     * 线程闸门。不在主线程就记一条错误并返回，不抛、也不转投。
     *
     * 不转投的理由同浮层：转投会把「调用方写错了」变成「偶发时序问题」，更难查。
     */
    private fun onMain(what: String): Boolean {
        if (Looper.myLooper() === Looper.getMainLooper()) return true
        Log.e(TAG, "$what 必须在主线程调用，已忽略（线程=${Thread.currentThread().name}）")
        return false
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        const val TAG = "FP-AggPay"

        const val TITLE = "聚合支付"
        const val HINT_IDLE = "点空白处关闭 · 长按查看来源"
        const val HINT_EMPTY = "没有可用的支付快捷方式（微信 / 支付宝未安装或未登录）"

        const val COLUMNS = 4
        const val ICON_DP = 52
        const val ICON_RADIUS = 14
        const val CELL_W = 76
        const val CELL_GAP = 6
        const val LABEL_GAP = 6
        const val LABEL_SP = 12f
        const val INITIAL_SP = 22f

        const val PAD_H = 20
        const val PAD_TOP = 18
        const val PAD_BOTTOM = 16
        const val TITLE_GAP = 16
        const val HINT_GAP = 14
        const val CARD_MAX_W = 360
        const val CARD_RADIUS = 24

        const val TITLE_SP = 17f
        const val HINT_SP = 11.5f

        /** 卡片底色。比 Appindex 那套浮层浅一点：面板是「弹出来给你点」的，不是背景板。 */
        const val CARD_BG = 0xF2202024.toInt()
        const val SCRIM = 0x99000000.toInt()
        const val TITLE_COLOR = 0xFFF2F2F2.toInt()
        const val LABEL_COLOR = 0xFFE6E6E6.toInt()
        const val HINT_COLOR = 0x99FFFFFF.toInt()

        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}
