package io.github.kotoriinsky.tsuzuri.hook.feature.widgetstyle

import android.content.res.Resources
import android.graphics.Outline
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.WidgetCornerTier
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小部件统一风格：把负一屏里的小部件在**容器层**统一掉 —— 圆角、背景模糊、尺寸适配方式。
 *
 * 对应 Flyme「Flyme 风格插件」的 A/B/C/D 四层里的 A（尺寸）与 C（圆角）+ D（模糊）部分，
 * 但**不搬 Flyme 的实现**：Flyme 那套（`WidgetMeasureHelper` / `WidgetTitleHelper` /
 * `RoundedCornerEnforcement`）是建立在「宿主继承 `AppWidgetHostView` 并自己重写 measure/draw」
 * 之上的，澎湃的负一屏是另一套架构，落点完全不同。这里用的是 PA **自己就有**的三个参数。
 *
 * ## 为什么是这三个落点
 *
 * 完整证据链（含字节码核对、jadx 会判错的那一处）写在
 * `research/widget-style-findings.md`，这里只留结论：
 *
 * | 参数 | 落点 | 为什么是它 |
 * | --- | --- | --- |
 * | `unify_corner` | `core/view/BaseWidgetCardView#clipChildCorner(View)` | 圆角裁剪的**唯一施加点**（`addView` / `onScreenSizeChanged` 调）。它读系统设置 `floating_icon_corner` 决定要不要圆角 → 用户改了桌面图标风格，负一屏小部件跟着变直角，这才是「不统一」的来源。半径**按 span 分三档**（PA 自己的 `w5.a.a(spanX, spanY)`），我们只把「用哪一档」固定住，**不把数值压平** —— 见 `WidgetCornerTier` |
 * | `blur_background` | `core/view/WidgetHostView#supportWidgetBackgroundBlur()` | 背景模糊的**唯一门控**。它返回 `ItemInfo.supportBackgroundBlur`，而那个字段只在解析 provider manifest meta-data 时被赋值，第三方不声明就是 `false` |
 * | `scale_mode` | `device/PALayoutFactory#onPreCreateView(...)` | PA 唯一的「适配方式」参数（密度归一）。它读 parent View 上的 `miui_widget_auto_scale_tag`，那个 tag 又来自 provider 声明的 `miuiAutoScale`（默认 true）→ 强制成同一个值就是「统一」 |
 *
 * ## 纪律
 *
 * - **关态一个 hook 都不装**（规范 §9）：三个参数各自独立开关，`scale_mode == "auto"` 时连
 *   密度归一的 hook 都不装 —— 那是 PA 的原生行为，不干预。
 * - 三个落点都**不在** `onDraw`/`measure`/`layout`/动画帧上（只在加子 View、屏幕尺寸变化、
 *   小部件 attach、RemoteViews inflate 时触发），但回调里仍然不分配对象、不打日志、不做 IO。
 * - 找不到类/方法 = 正常情况（OTA 改名），静默降级 + 记一条 `w`，绝不抛异常。
 */
object WidgetUnifiedStyle : Feature(Catalog.require(Catalog.WIDGET_STYLE)) {

    private const val PA = "com.miui.personalassistant"

    /** 圆角裁剪的施加点。子类没有重写它，所以 hook 基类即可覆盖全部卡片。 */
    private val CARD_VIEW_CANDIDATES = listOf(
        "com.miui.personalassistant.core.view.BaseWidgetCardView",
        "com.miui.personalassistant.core.view.WidgetCardView",
    )

    /** 原生小部件宿主（`AppWidgetHostView` 子类）。 */
    private val HOST_VIEW_CANDIDATES = listOf(
        "com.miui.personalassistant.core.view.WidgetHostView",
    )

    /** RemoteViews 的 `LayoutInflater.Factory2`（密度归一的施展点）。 */
    private val LAYOUT_FACTORY_CANDIDATES = listOf(
        "com.miui.personalassistant.device.PALayoutFactory",
    )

    // ── 圆角的三档取值 ───────────────────────────────────────────────────
    //
    // 「统一圆角」取 PA **自己的分档值**：原生小部件本来就是用这套值裁的
    // （`p5.d#getClipRoundCornerRadius()` 的 default 方法 → `w5.a.a(spanX, spanY)` → 三个 dimen），
    // 所以「统一」= 把**档位选择**固定住，而不是压平成另一个数。
    // 三档的**选择规则**放在 `:core` 的 `WidgetCornerTier`（纯函数，有单测逐格对照字节码），
    // 这里只负责把选出来的档**解析成像素**；名字必须运行时查资源表（规范 §6.1，OTA 会改 dimen 的值）。

    /** 资源解析失败时的兜底值。取 PA 默认档的实测值（24dp），不是我们编的。 */
    private const val FALLBACK_RADIUS_DP = 24f

    /** 密度归一的开关 tag。同样运行时解析 id，不硬编码。 */
    private const val ID_AUTO_SCALE_TAG = "miui_widget_auto_scale_tag"

    private val installed = AtomicBoolean(false)

    /** `unify_corner` 是否开着：关着时圆角 hook 只为第三方外壳服务，不碰小米小部件。 */
    @Volatile
    private var cornerForAll = false

    // ── 统一圆角的轮廓 ───────────────────────────────────────────────────

    /**
     * **单个 View** 的圆角轮廓。
     *
     * 为什么不是「全进程一个实例 + 可变半径」（这是本文件此前的问题）：
     * `getOutline` 是在**绘制**时按需回调的，半径必须**跟着 View 走**。共用一个可变实例时，
     * 每个子 View 都被 `setOutlineProvider` 指向同一个对象，于是所有卡片的圆角都等于
     * **最后一次写进去的那个半径** —— 只有「全用一个数值」时才看不出问题，一旦按 span 分档，
     * 1×1 的卡片就会显示成「最后那张大卡片的圆角」。
     *
     * 每个被裁的 View 一个实例，半径是构造时定死的 `val`：`getOutline` 里只做一次
     * `setRoundRect`，**不分配对象、不打日志、不做 IO**（规范 §7.4）。
     * 每 View 一个实例也是 PA 自己的做法（`BaseWidgetCardView.java:119` 每次 `new w5.b(半径)`），
     * 且分配只发生在 `clipChildCorner`（加子 View / 屏幕尺寸变化），不在帧上。
     */
    private class RoundOutlineProvider(val radiusPx: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            // 半径自己裁一下：1×1 的格子边长很小，半径超过半边长时在不同渲染后端上表现不一致
            val w = view.width
            val h = view.height
            val half = if (w < h) w * 0.5f else h * 0.5f
            val r = if (radiusPx < half) radiusPx else half
            outline.setRoundRect(0, 0, w, h, r)
        }
    }

    /** 三档 dimen 的 id 缓存。`getIdentifier` 是这里最贵的一步，解析一次就够（OTA 后靠 `reload` 重来）。 */
    private class TierDimenIds(val defaultId: Int, val oneByOneId: Int, val singleRowId: Int) {
        fun idFor(dimenName: String): Int = when (dimenName) {
            WidgetCornerTier.DIMEN_1X1 -> oneByOneId
            WidgetCornerTier.DIMEN_SINGLE_ROW -> singleRowId
            else -> defaultId
        }
    }

    @Volatile
    private var tierIds: TierDimenIds? = null

    // ── span 取值路径（反射，拿不到就退默认档）────────────────────────────

    /** 解析成功才有值；为 null（含解析失败）→ 后续一律退默认档，不再重试反射。 */
    @Volatile
    private var itemInfoClass: Class<*>? = null

    @Volatile
    private var spanXField: Field? = null

    @Volatile
    private var spanYField: Field? = null

    /** 「拿不到 span」的次数（只用于第一次留痕，见 [noteSpanMiss]）。 */
    @Volatile
    private var spanMisses = 0

    @Volatile
    private var scaleTagResolved = false

    @Volatile
    private var scaleTagId = 0

    // ── 安装 ─────────────────────────────────────────────────────────────

    override fun install(ctx: HookContext): InstallReport {
        // 幂等：框架可能因为多包回调 / 热重载再进来一次。重装前先把上一套摘干净，
        // 否则同一个方法会被挂两层 hook（规范 §5）。
        if (installed.get()) {
            ctx.log(Log.WARN, "repeat install, removing previous hooks first")
            super.uninstall()
        }
        installed.set(true)

        val scaleMode = ctx.prefs.value(prefSpec<PrefSpec.Choice>("scale_mode"))
        val unifyCorner = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("unify_corner"))
        val blurBackground = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("blur_background"))
        val hyperShell = ctx.prefs.value(prefSpec<PrefSpec.Toggle>("hyperos_shell"))
        cornerForAll = unifyCorner

        val missing = ArrayList<String>(3)
        val notes = ArrayList<String>(3)
        var hooked = 0

        // 第三方外壳也要经过 clipChildCorner（PA 的卡片会再裁一层），所以两者任一开着都要挂
        if (unifyCorner || hyperShell) {
            hooked += hookCornerClip(ctx, missing)
        }
        if (!unifyCorner) notes.add("corner=off")

        if (hyperShell) {
            hooked += WidgetHyperShell.install(ctx, missing)
        } else {
            notes.add("shell=off")
        }

        if (blurBackground) {
            hooked += hookBackgroundBlur(ctx, missing)
        } else {
            notes.add("blur=off")
        }

        when (scaleMode) {
            // 强制等比缩放：内容整体缩到格子里（Flyme 的「等比缩放」那一档）
            "fit" -> hooked += hookAutoScale(ctx, wantScale = true, missing = missing)
            // 强制不缩放：按第三方自己的密度渲染，铺满/溢出水格（Flyme 的 match_parent 那一档）
            "fill" -> hooked += hookAutoScale(ctx, wantScale = false, missing = missing)
            // auto = 完全跟随 provider 的 miuiAutoScale 声明 → 一个 hook 都不装
            else -> notes.add("scale=auto(不干预)")
        }

        ctx.log(
            Log.INFO,
            "widget_style 已挂上：hooked=$hooked scale=$scaleMode corner=$unifyCorner shell=$hyperShell " +
                "blur=$blurBackground missing=${missing.joinToString("|")}"
        )
        return InstallReport(hooked, missing, notes)
    }

    override fun uninstall() {
        WidgetHyperShell.uninstall()
        super.uninstall()
        installed.set(false)
        // 清缓存。注意：**已经写进 View 的 tag / 轮廓无法逐个撤销** ——
        // 这正是 Catalog 给这个特性配 `HOT_RELOAD`（换开关重启负一屏）的原因：
        // 进程重启后这些状态由 PA 自己重新初始化，关态即与未安装一致。
        tierIds = null
        itemInfoClass = null
        spanXField = null
        spanYField = null
        spanMisses = 0
        scaleTagResolved = false
        scaleTagId = 0
    }

    // ── hook 1：统一圆角 ─────────────────────────────────────────────────

    /**
     * 把 `clipChildCorner` 裁出来的圆角统一成 **PA 自己的那一档**（按 span 选，不是压平成一个数）。
     *
     * 做法是**后置**改写轮廓，而不是整个接管：
     *  - 「裁不裁」由 PA 决定（它还要看 `clipRoundCorner()` 与系统设置 `floating_icon_corner`），
     *    我们不动这个决定；
     *  - 我们只把已经在裁的那张轮廓的半径换成**该 span 对应的档位值** —— 这正是「统一圆角」
     *    的字面含义：统一**规则**，不是统一数值。压平成一个 24dp 会把 1×1 的圆角放大到被
     *    `min(w,h)/2` 夹取、接近圆形（本次修的就是这个回归）。
     *
     * 这样即使 PA 以后改了裁剪条件（比如新增一种不裁的卡片），我们也只是不生效，不会裁错。
     */
    private fun hookCornerClip(ctx: HookContext, missing: MutableList<String>): Int {
        val cls = loadClass(ctx, CARD_VIEW_CANDIDATES)
        if (cls == null) {
            missing.add("BaseWidgetCardView")
            return 0
        }
        val methods = findMethods(cls, "clipChildCorner", 1, View::class.java)
        if (methods.isEmpty()) {
            missing.add("BaseWidgetCardView#clipChildCorner")
            return 0
        }
        // span 的取值路径一次性解析（解析失败也只是退默认档，不影响挂 hook）
        resolveSpanAccess(cls)
        var n = 0
        for (m in methods) {
            try {
                track(
                    ctx.module.hook(m).intercept { chain ->
                        // 先跑原逻辑，让 PA 自己决定要不要裁
                        val result = chain.proceed()
                        try {
                            val target = chain.getArg(0) as? View
                            val shell = target != null && WidgetHyperShell.isActive && WidgetHyperShell.isThirdParty(target)
                            if (target != null && (shell || cornerForAll) && target.getClipToOutline()) {
                                val radius = tierRadiusPx(target.resources, radiusDimenName(target))
                                // 半径与当前轮廓一致就不重设（`setOutlineProvider` 会让 View 重算轮廓，
                                // 是多余的重绘）。注意 PA 通常刚在这个方法里换成了它自己的 `w5.b`，
                                // 所以这条真正防的是「PA 这次没换轮廓、但 `getClipToOutline` 仍是 true」那条路；
                                // 不命中时只是白比较一次，不分配对象。
                                val current = target.getOutlineProvider()
                                if (current !is RoundOutlineProvider || current.radiusPx != radius) {
                                    target.setOutlineProvider(RoundOutlineProvider(radius))
                                }
                            }
                        } catch (_: Throwable) {
                            // 换轮廓失败就保留 PA 原样：宁可圆角不一致，也不能让裁剪坏掉
                        }
                        result
                    }
                )
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "hook clipChildCorner failed", t)
            }
        }
        if (n == 0) missing.add("BaseWidgetCardView#clipChildCorner")
        return n
    }

    // ── 圆角：span → 档位 → 像素 ─────────────────────────────────────────

    /** 系统小部件在这个 span 上的圆角（第三方外壳用同一个值，见 [WidgetHyperShell]）。 */
    internal fun systemRadiusPx(view: View): Float = tierRadiusPx(view.resources, radiusDimenName(view))

    /**
     * 解析 span 的取值路径。**一个进程只跑一次**（`install` 时），所以反射开销不在热路径上；
     * 解析失败就让 [itemInfoClass] 保持 null —— 之后每次裁剪都会在 [radiusDimenName] 的第一行
     * 直接退默认档，不会反复重试反射。
     *
     * ## span 从哪来：被裁的那个**子 View 的 tag**
     *
     * PA 自己在 `clipChildCorner`（`BaseWidgetCardView.java:115-124`）里取半径的对象是**子 View**
     * （`p5.d dVar = (p5.d) view; dVar.getClipRoundCornerRadius()`），而 `p5.d` 的该 default 方法
     * （`p5/d.java`）就是 `w5.a.a(itemInfo.spanX, itemInfo.spanY)`，`itemInfo` 又一律来自 `getTag()`。
     * 四个实现全是「tag instanceof …」：`BaseWidgetCardView:220`、`WidgetHostView:406`、
     * `MaMlHostView:237`、`StackHostView:563`。所以**读 tag 与调 `getItemInfo()` 等价**。
     *
     * 为什么不直接调 `getItemInfo()`：`MaMlHostView`（`:237-245`）与 `StackHostView`（`:563-568`）
     * 在 tag 类型不符时**抛 IllegalArgumentException** —— 在给 View 换轮廓的这段路径上抛异常
     * 是不可接受的；直接读 tag 在同样输入下只是拿到 `null`，也就是退默认档。
     *
     * `ItemInfo` 的类名不写死（规范 §6）：从被 hook 的卡片类自己的 `getItemInfo()` 的**返回类型**上取，
     * OTA 改了包名/类名也不用改这里。字段名 `spanX`/`spanY` 解析不到就退默认档并留痕。
     */
    private fun resolveSpanAccess(cardCls: Class<*>) {
        try {
            val infoCls = cardCls.getMethod("getItemInfo").returnType
            spanXField = infoCls.getField("spanX")
            spanYField = infoCls.getField("spanY")
            itemInfoClass = infoCls
        } catch (t: Throwable) {
            // 拿不到 span 不是错误路径：圆角退默认档（= 改动前的行为），只是失去分档
            lastDiagnostic = "span 取值路径解析失败，圆角退默认档：${t.javaClass.simpleName}"
        }
    }

    /**
     * 被裁 View 的 span → dimen 资源名。
     *
     * 拿不到 span（tag 不是 ItemInfo、字段被改名、tag 还没设上）→ 默认档。
     * **不抛异常、不返回 0、也不去「猜」另一个来源**：默认档正是 PA 在 `itemInfo == null` 时
     * 自己用的那一档，也就是改动前的行为。
     */
    private fun radiusDimenName(view: View): String {
        val infoCls = itemInfoClass ?: return WidgetCornerTier.DIMEN_DEFAULT
        return try {
            val tag = view.tag
            if (tag == null || !infoCls.isInstance(tag)) {
                noteSpanMiss()
                return WidgetCornerTier.DIMEN_DEFAULT
            }
            val spanX = spanXField?.getInt(tag) ?: return WidgetCornerTier.DIMEN_DEFAULT
            val spanY = spanYField?.getInt(tag) ?: return WidgetCornerTier.DIMEN_DEFAULT
            WidgetCornerTier.dimenNameFor(spanX, spanY)
        } catch (_: Throwable) {
            WidgetCornerTier.DIMEN_DEFAULT
        }
    }

    /**
     * 「拿不到 span」**只拼一次字符串**，之后只累加计数 —— 这条路径每次裁剪都会走到，
     * 每次都拼串才是真的在热路径上分配。留痕的意义：日志里没有它就说明分档在生效；
     * 有它就说明圆角只是退回了默认档（= 改动前的行为），而**不是**代码没装上 ——
     * 这两种情况在真机日志上必须能分开。
     */
    private fun noteSpanMiss() {
        if (spanMisses == 0) {
            lastDiagnostic = "被裁 View 的 tag 不是 ItemInfo，圆角按默认档（此后同类不再记）"
        }
        spanMisses++
    }

    /**
     * 档位 → 像素。**按需读资源、不缓存像素值**：`clipChildCorner` 只在加子 View / 屏幕尺寸
     * 变化时触发（不在帧上），而 `getDimension` 走线程内的临时 `TypedValue`、不分配对象；
     * 顺带还自带了「折叠/换分辨率后按新配置重取」的正确性（旧实现按 density 缓存，配置变了会拿到旧值）。
     *
     * 解析失败的级联：该档查不到 → 退默认档 → 默认档也查不到 → PA 默认档的实测 24dp。
     */
    private fun tierRadiusPx(res: Resources, dimenName: String): Float {
        val ids = tierIds ?: resolveTierIds(res).also { tierIds = it }
        val id = ids.idFor(dimenName)
        if (id != 0) {
            try {
                return res.getDimension(id)
            } catch (_: Throwable) {
                // 落到下面的兜底
            }
        }
        if (dimenName != WidgetCornerTier.DIMEN_DEFAULT && ids.defaultId != 0) {
            try {
                return res.getDimension(ids.defaultId)
            } catch (_: Throwable) {
                // 落到下面的兜底
            }
        }
        return FALLBACK_RADIUS_DP * res.displayMetrics.density
    }

    /** 三档的 dimen 名 → id。只在首次用到时跑一遍，解析不到的档留痕（规范 §3.3）。 */
    private fun resolveTierIds(res: Resources): TierDimenIds {
        val defaultId = dimenId(res, WidgetCornerTier.DIMEN_DEFAULT)
        val oneByOneId = dimenId(res, WidgetCornerTier.DIMEN_1X1)
        val singleRowId = dimenId(res, WidgetCornerTier.DIMEN_SINGLE_ROW)
        when {
            defaultId == 0 -> lastDiagnostic =
                "resource not found: ${WidgetCornerTier.DIMEN_DEFAULT}（圆角回退 ${FALLBACK_RADIUS_DP}dp）"
            oneByOneId == 0 || singleRowId == 0 -> lastDiagnostic =
                "圆角 dimen 部分缺失（1x1=$oneByOneId single_row=$singleRowId），缺失的档回退默认档"
        }
        return TierDimenIds(defaultId, oneByOneId, singleRowId)
    }

    /** 按名字查 dimen 的 id。查不到返回 0（= 正常情况，OTA 改名了）。 */
    private fun dimenId(res: Resources, name: String): Int = try {
        res.getIdentifier(name, "dimen", PA)
    } catch (_: Throwable) {
        0
    }

    // ── hook 2：统一背景模糊 ─────────────────────────────────────────────

    /**
     * 让所有原生小部件都进入 PA 的背景模糊链路。
     *
     * `supportWidgetBackgroundBlur()` 的字节码只有一个 `iget-boolean
     * ItemInfo.supportBackgroundBlur`，是纯粹的 getter，**短接安全**；而且它就是那道
     * 「第三方 provider 没声明 supportBackgroundBlur」的门控本身，短接成 true 正是目的。
     *
     * ⚠️ 后面还有一道设备能力门控（`blur.b#g()`：`r.F(ctx) && miuix.core.util.w.a() >= 2`），
     * 不满足时 PA 自己会 return —— 这条 hook 会**静默无效**，没有副作用。
     */
    private fun hookBackgroundBlur(ctx: HookContext, missing: MutableList<String>): Int {
        val cls = loadClass(ctx, HOST_VIEW_CANDIDATES)
        if (cls == null) {
            missing.add("WidgetHostView")
            return 0
        }
        val methods = findMethods(cls, "supportWidgetBackgroundBlur", 0, null)
        if (methods.isEmpty()) {
            missing.add("WidgetHostView#supportWidgetBackgroundBlur")
            return 0
        }
        var n = 0
        for (m in methods) {
            try {
                track(ctx.module.hook(m).intercept { true })
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "hook supportWidgetBackgroundBlur failed", t)
            }
        }
        if (n == 0) missing.add("WidgetHostView#supportWidgetBackgroundBlur")
        return n
    }

    // ── hook 3：统一尺寸适配方式（密度归一）────────────────────────────────

    /**
     * 强制第三方小部件走同一种密度适配，忽略 provider 自己声明的 `miuiAutoScale`。
     *
     * `onPreCreateView(parent, name, context, attrs)` 里 PA 读 **parent** 上的
     * `miui_widget_auto_scale_tag`：tag 为空或非 `FALSE` 就缩放，是 `FALSE` 就跳过。
     * 我们在 `proceed()` **之前**改写这个 tag，PA 紧接着读它 —— 顺序天然正确。
     *
     * 只在 tag 与目标值不同时才写，避免每次 inflate 都产生一次无意义的 `setTag`。
     */
    private fun hookAutoScale(ctx: HookContext, wantScale: Boolean, missing: MutableList<String>): Int {
        val cls = loadClass(ctx, LAYOUT_FACTORY_CANDIDATES)
        if (cls == null) {
            missing.add("PALayoutFactory")
            return 0
        }
        val methods = findMethods(cls, "onPreCreateView", 4, null)
        if (methods.isEmpty()) {
            missing.add("PALayoutFactory#onPreCreateView")
            return 0
        }
        val want: Any = if (wantScale) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
        var n = 0
        for (m in methods) {
            try {
                track(
                    ctx.module.hook(m).intercept { chain ->
                        try {
                            val parent = chain.getArg(0) as? View
                            if (parent != null) {
                                val id = autoScaleTagId(parent.resources)
                                if (id != 0 && parent.getTag(id) != want) {
                                    parent.setTag(id, want)
                                }
                            }
                        } catch (_: Throwable) {
                            // 改不动就退回 PA 自己的判据，功能降级但不会坏
                        }
                        chain.proceed()
                    }
                )
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "hook onPreCreateView failed", t)
            }
        }
        if (n == 0) missing.add("PALayoutFactory#onPreCreateView")
        return n
    }

    /** 运行时解析 `miui_widget_auto_scale_tag` 的 id（部署后 OTA 会变，绝不写死）。 */
    private fun autoScaleTagId(res: Resources): Int {
        if (!scaleTagResolved) {
            scaleTagId = try {
                res.getIdentifier(ID_AUTO_SCALE_TAG, "id", PA)
            } catch (_: Throwable) {
                0
            }
            scaleTagResolved = true
            if (scaleTagId == 0) {
                // 解析不到 = 这个功能静默失效，必须留痕（规范 §3.3）
                lastDiagnostic = "resource id not found: $ID_AUTO_SCALE_TAG"
            }
        }
        return scaleTagId
    }

    // ── 反射工具 ─────────────────────────────────────────────────────────

    /** 多候选类名，逐个试；都失败返回 null（= 正常情况，不抛）。 */
    private fun loadClass(ctx: HookContext, candidates: List<String>): Class<*>? {
        for (name in candidates) {
            try {
                return Class.forName(name, false, ctx.classLoader)
            } catch (_: Throwable) {
                // 换下一个候选
            }
        }
        return null
    }

    /**
     * 按「方法名 + 参数个数 + 第 0 个参数类型」特征匹配，返回**全部**候选。
     *
     * 不硬编码单一签名（规范 §6）：OTA 后参数类型可能微调，所以类型只做弱断言；
     * 返回多个时全部挂上 —— 同名同参数个数的重载在同一类里几乎不存在，
     * 真出现了就说明我们该重新看反编译结果了。
     */
    private fun findMethods(
        cls: Class<*>,
        name: String,
        arity: Int,
        firstParam: Class<*>?,
    ): List<Method> = try {
        cls.declaredMethods.filter { m ->
            m.name == name &&
                m.parameterCount == arity &&
                (firstParam == null || firstParam.isAssignableFrom(m.parameterTypes[0]))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** 诊断用：最近一次解析失败的原因。 */
    @Volatile
    var lastDiagnostic: String? = null
        private set

    /**
     * 按参数名取配置描述。
     *
     * 用真实的 `Keys.pref` 拼键而不是手写字面量 —— 键名写错编译期发现不了，
     * 运行时只表现为「这个设置项没反应」，是最难查的一类 bug。
     */
    private inline fun <reified T : PrefSpec> prefSpec(name: String): T =
        spec.prefs.filterIsInstance<T>().first { it.key == Keys.pref(spec.key, name) }
}
