package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.method.TransformationMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalCapsuleCompat
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 统一收起态外观、入退场、停靠与闲置期限；原生操作和展开内容保留。 */
object PortalCapsuleStyle : Feature(Catalog.require(Catalog.RECOGNITION_LOOK)) {

    override val installId: String get() = "recognition_look:capsule"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = !isSystemServer && (processName == io.github.kotoriinsky.tsuzuri.core.ScopeTarget.CONTENT_EXTENSION.packageName)

    override fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs) = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(prefs, Catalog.RECOGNITION_LOOK, "capsule")
    private val main = Handler(Looper.getMainLooper())
    private val styled = WeakHashMap<ViewGroup, CardState>()
    private val scaffolds = WeakHashMap<View, NodeState>()
    private val sessions = WeakHashMap<Any, Session>()
    @Volatile private var active = false
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
    private data class NodeState(
        val view: WeakReference<View>, val params: ViewGroup.LayoutParams?, val padding: IntArray,
        val background: Drawable?, val outline: ViewOutlineProvider?, val clip: Boolean,
        val minHeight: Int, val elevation: Float, val id: Int, val gravity: Int?, var visibility: Int,
        val textSize: Float?, val colors: android.content.res.ColorStateList?, val font: Typeface?,
        val fontPadding: Boolean?, val maxWidth: Int?,
        val maxLines: Int?, val singleLine: Boolean?, val ellipsize: TextUtils.TruncateAt?,
        val transformation: TransformationMethod?,
        val showDividers: Int?, val baselineAligned: Boolean?,
    )
    private data class CardState(val nodes: List<NodeState>, val observer: WeakReference<ViewTreeObserver>,
        val listener: ViewTreeObserver.OnPreDrawListener)
    private class Session(owner: Any, val bridge: PortalCapsuleCompat) {
        val owner = WeakReference(owner)
        val originalWindow = WindowManager.LayoutParams().apply { copyFrom(bridge.params(owner)) }
        var touching = false
        var hasResults = false
        var timer: Runnable? = null
        var insets: PortalCapsuleCompat.InsetsHandle? = null
    }

    override fun install(ctx: HookContext): InstallReport {
        val bridge = runCatching { PortalCapsuleCompat(ctx.classLoader) }.getOrElse {
            ctx.log(Log.WARN, "传送门胶囊符号降级：${it.message}")
            return InstallReport(0, notes = listOf("传送门小卡符号不兼容"))
        }
        active = true
        var appearance: PortalCapsuleCompat.Appearance? = null
        var configuration: Configuration? = null
        fun style(owner: Any): PortalCapsuleCompat.Appearance {
            val root = bridge.root(owner)
            if (configuration != root.resources.configuration) {
                appearance = bridge.appearance(root.context)
                configuration = Configuration(root.resources.configuration)
            }
            return requireNotNull(appearance)
        }
        fun anchor(owner: Any, look: PortalCapsuleCompat.Appearance) {
            val root = bridge.root(owner)
            root.getChildAt(0)?.let { outer ->
                scaffolds.getOrPut(outer) { snapshot(outer) }
                outer.setPaddingRelative(0, 0, 0, 0)
                val outerParams = outer.layoutParams as? RelativeLayout.LayoutParams
                if (outerParams != null) outer.layoutParams = RelativeLayout.LayoutParams(outerParams).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                }
            }
            bridge.node(owner, "loading_card")?.let { loading ->
                scaffolds.getOrPut(loading) { snapshot(loading) }
                val loadingParams = loading.layoutParams as? RelativeLayout.LayoutParams
                if (loadingParams != null) loading.layoutParams = RelativeLayout.LayoutParams(loadingParams).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                }
            }
            val params = bridge.params(owner)
            params.gravity = Gravity.BOTTOM or Gravity.END
            params.x = look.edge
            // 原生传送门窗口较窄；允许主标题使用小爱模板的完整文本宽度。
            params.width = minOf(look.maxTextWidth + look.paddingHorizontal * 2 + look.iconSize + look.iconGap,
                root.resources.displayMetrics.widthPixels - look.edge * 2)
            // 选中与复制使用同一固定槽位，避免复制后为小爱让位导致纵向跳动。
            params.y = look.bottom + look.radius * 2 + look.gap
            // 避免 WRAP_CONTENT 改高时 Surface 先绘制旧位置、下一帧才完成重排。
            params.height = (root.resources.displayMetrics.heightPixels - params.y - look.radius * 2)
                .coerceAtLeast(look.radius * 2)
        }
        fun arrange(owner: Any, look: PortalCapsuleCompat.Appearance) {
            val deck = bridge.deck(owner)
            // ABOVE 必须引用有效 ID；动态附加卡的原始 ID 可能是 NO_ID。
            deck.forEach { view ->
                scaffolds.getOrPut(view) { snapshot(view) }
                if (view.id == View.NO_ID) view.id = View.generateViewId()
            }
            deck.forEachIndexed { index, view ->
                scaffolds.getOrPut(view) { snapshot(view) }
                val params = view.layoutParams as? RelativeLayout.LayoutParams ?: return@forEachIndexed
                view.layoutParams = RelativeLayout.LayoutParams(params).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    removeRule(RelativeLayout.BELOW)
                    removeRule(RelativeLayout.ABOVE)
                    removeRule(RelativeLayout.ALIGN_PARENT_TOP)
                    removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    if (index == 0) addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    else addRule(RelativeLayout.ABOVE, deck[index - 1].id)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    topMargin = 0
                    bottomMargin = if (index == 0) 0 else look.gap
                }
                // 只给纵向推荐列表加卡片间距，横向胶囊里的图标和文字必须保留居中布局。
                if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) {
                    view.gravity = Gravity.END
                    view.showDividers = LinearLayout.SHOW_DIVIDER_NONE
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        scaffolds.getOrPut(child) { snapshot(child) }
                        val childParams = child.layoutParams as? LinearLayout.LayoutParams ?: continue
                        child.layoutParams = LinearLayout.LayoutParams(childParams).apply {
                            gravity = Gravity.END
                            topMargin = if (i == 0) 0 else look.gap
                        }
                    }
                }
            }
        }
        fun present(owner: Any, loading: Boolean): Boolean {
            if (bridge.isFinishing(owner)) return true
            val look = style(owner)
            val root = bridge.root(owner)
            val ready = bridge.smallCards(owner).filter { card ->
                val parts = bridge.parts(card)!!
                if (loading) parts.title == null else bridge.primaryText(card, parts) != null
            }
            if (ready.isEmpty()) return false
            if (!loading) {
                bridge.beginResults(owner)
                bridge.smallCards(owner).filter { bridge.parts(it)?.title == null }.forEach { it.visibility = View.GONE }
            }
            val session = sessions.getOrPut(owner) { Session(owner, bridge) }
            anchor(owner, look)
            if (!loading) arrange(owner, look)
            root.alpha = 1f
            root.translationX = 0f
            root.translationY = 0f
            val textWidth = (bridge.params(owner).width - look.paddingHorizontal * 2 - look.iconSize - look.iconGap).coerceAtLeast(look.iconSize)
            ready.forEachIndexed { index, card ->
                if (!styled.containsKey(card)) {
                    decorate(card, bridge.parts(card)!!, look, textWidth)
                    selectText(card, bridge)
                    ctx.log(Log.INFO, "传送门胶囊样式 styled=1 kind=${card.javaClass.simpleName}")
                }
                if (card.visibility != View.VISIBLE) {
                    card.animate().cancel()
                    card.visibility = View.VISIBLE
                    card.alpha = 1f
                    card.translationY = 0f
                    // 布局完成后按实际宽度从右侧入场；不对 Y 做动画。
                    card.translationX = root.width.toFloat()
                    val cardReference = WeakReference(card)
                    card.post { cardReference.get()?.let { target ->
                        if (active && !bridge.isFinishing(owner)) {
                            runCatching { bridge.enter(target, look.edge, index * 30L) }.onFailure {
                                target.animate().translationX(0f).setDuration(200L).setInterpolator(easing).start()
                                ctx.log(Log.WARN, "传送门入场弹簧降级：${it.javaClass.simpleName}")
                            }
                        }
                    } }
                }
            }
            bridge.update(owner)
            val rootReference = WeakReference(root)
            val primaryReference = WeakReference(if (loading) ready.first() else bridge.deck(owner).firstOrNull())
            val observer = root.viewTreeObserver
            val observerReference = WeakReference(observer)
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    observerReference.get()?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
                    val primary = primaryReference.get()
                    if (active && rootReference.get()?.isAttachedToWindow == true && primary != null) {
                        val location = IntArray(2)
                        primary.getLocationOnScreen(location)
                        ctx.log(Log.INFO, "传送门胶囊锚点 stage=${if (loading) "loading" else "results"} bottom=${location[1] + primary.height} horizontal=true")
                    }
                    return true
                }
            })
            if (!loading && !session.hasResults) {
                session.hasResults = true
                arm(session, ctx)
                ctx.log(Log.INFO, "传送门胶囊显示 count=${ready.size} anchor=bottom_end timeout=${timeout() / 1000}s")
            }
            return true
        }
        bridge.bindingMethods.forEach { (method, device) ->
            track(ctx.module.hook(method).intercept { chain ->
                val card = chain.thisObject as? ViewGroup
                val content = card?.let(bridge::parts)
                val state = card?.let(styled::get)
                // 复用卡片重新绑定前先恢复原生可见性，避免设备名/主标题互相残留。
                if (active && content != null) state?.nodes?.forEach { saved ->
                    saved.view.get()?.takeIf { it === content.title || it === content.description }
                        ?.let { it.visibility = saved.visibility }
                }
                val result = chain.proceed()
                runCatching {
                    if (active && card != null && content != null) {
                        bridge.markBinding(card, device)
                        state?.nodes?.forEach { saved ->
                            saved.view.get()?.takeIf { it === content.title || it === content.description }
                                ?.let { saved.visibility = it.visibility }
                        }
                        if (state != null) selectText(card, bridge)
                    }
                }.onFailure { ctx.log(Log.WARN, "传送门单行内容保留原生：${it.javaClass.simpleName}") }
                result
            })
        }
        track(ctx.module.hook(bridge.initViewMethod).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                if (active) {
                    val owner = chain.thisObject!!
                    val session = sessions.getOrPut(owner) { Session(owner, bridge) }
                    session.insets = bridge.restrictTouches(owner)
                    // 在 addToWindow 之前确定锚点，首帧就位于最终位置。
                    anchor(owner, style(owner))
                }
            }.onFailure { ctx.log(Log.WARN, "传送门初始位置保留原生：${it.javaClass.simpleName}") }
            result
        })
        track(ctx.module.hook(bridge.loadingMethod).intercept { chain ->
            // 原生等待动画的结束回调会 inflate 结果并启动识别/复制分支，必须保留。
            val result = chain.proceed()
            runCatching { if (active) present(chain.thisObject!!, true) }.onFailure {
                ctx.log(Log.WARN, "传送门等待卡样式降级：${it.javaClass.simpleName}")
            }
            result
        })
        bridge.showMethods.forEach { method ->
            track(ctx.module.hook(method).intercept { chain ->
                val handled = active && runCatching { present(chain.thisObject!!, false) }.onFailure {
                    ctx.log(Log.WARN, "传送门胶囊呈现保留原生：${it.javaClass.simpleName} ${it.message}")
                }.getOrDefault(false)
                if (handled) null else chain.proceed()
            })
        }
        track(ctx.module.hook(bridge.hideMethod).intercept { chain ->
            val owner = chain.thisObject!!
            val session = sessions[owner]
            if (!active || session == null) return@intercept chain.proceed()
            session.timer?.let(main::removeCallbacks)
            if (!bridge.isFinishing(owner)) {
                bridge.beginHide(owner)
                val root = bridge.root(owner)
                val cancelTask = chain.args[0] == true
                root.animate().cancel()
                bridge.smallCards(owner).forEach { runCatching { bridge.cancelAnimation(it) } }
                root.animate().alpha(0f).translationX((root.width + style(owner).edge).toFloat()).setDuration(200L)
                    .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                        runCatching { bridge.finish(owner, cancelTask) }
                    }.start()
            }
            null
        })
        track(ctx.module.hook(bridge.touchMethod).intercept { chain ->
            val session = sessions[chain.thisObject]
            val event = chain.args[0] as MotionEvent
            if (active && session != null) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { session.touching = true; session.timer?.let(main::removeCallbacks) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        session.touching = false
                        if (session.hasResults) arm(session, ctx)
                    }
                    // 固定停靠，避免原生按旧顶部坐标拖动把底部锚点反向移动。
                    MotionEvent.ACTION_MOVE -> return@intercept false
                }
            }
            chain.proceed()
        })
        track(ctx.module.hook(bridge.destroyMethod).intercept { chain ->
            if (bridge.isSplash(chain.thisObject!!)) sessions.remove(chain.thisObject)?.let { session ->
                session.timer?.let(main::removeCallbacks)
                session.insets?.let { runCatching { bridge.restoreTouches(it) } }
            }
            chain.proceed()
        })
        return InstallReport(installedHooks, notes = listOf("收起组件含动态附加卡，统一单行/动画/右下停靠/复制直达停留时长"))
    }

    private fun timeout() = PasscodeIsland.timeoutMs(HookRegistry.prefs().rawFloatOrNull(Keys.pref(Catalog.PASSCODE_ISLAND, "timeout_seconds")))
    private fun arm(session: Session, ctx: HookContext) {
        session.timer?.let(main::removeCallbacks)
        session.timer = Runnable {
            val owner = session.owner.get() ?: return@Runnable
            if (!active || session.touching || session.bridge.isFinishing(owner)) return@Runnable
            ctx.log(Log.INFO, "传送门胶囊闲置收起 expired=1")
            runCatching { session.bridge.hideMethod.invoke(owner, true) }
        }.also { main.postDelayed(it, timeout()) }
    }

    private fun copyParams(params: ViewGroup.LayoutParams): ViewGroup.LayoutParams = when (params) {
        is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(params)
        is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(params)
        is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params)
        else -> ViewGroup.LayoutParams(params)
    }
    private fun snapshot(view: View) = NodeState(WeakReference(view), view.layoutParams?.let(::copyParams),
        intArrayOf(view.paddingStart, view.paddingTop, view.paddingEnd, view.paddingBottom), view.background,
        view.outlineProvider, view.clipToOutline, view.minimumHeight, view.elevation, view.id, (view as? LinearLayout)?.gravity,
        view.visibility,
        (view as? TextView)?.textSize, (view as? TextView)?.textColors, (view as? TextView)?.typeface,
        (view as? TextView)?.includeFontPadding, (view as? TextView)?.maxWidth,
        (view as? TextView)?.maxLines, (view as? TextView)?.isSingleLine, (view as? TextView)?.ellipsize,
        (view as? TextView)?.transformationMethod, (view as? LinearLayout)?.showDividers,
        (view as? LinearLayout)?.isBaselineAligned)
    private fun decorate(card: ViewGroup, parts: PortalCapsuleCompat.Parts, look: PortalCapsuleCompat.Appearance, textWidth: Int) {
        val nodes = listOfNotNull(card, parts.icon, parts.textGroup, parts.title, parts.description).distinct().map(::snapshot)
        val background = look.background.constantState?.newDrawable(card.resources)?.mutate() ?: look.background.mutate()
        val reference = WeakReference(card)
        val listener = ViewTreeObserver.OnPreDrawListener {
            reference.get()?.let { if (active && it.background !== background) it.background = background }
            true
        }
        styled[card] = CardState(nodes, WeakReference(card.viewTreeObserver), listener)
        card.background = background
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        card.clipToOutline = true
        card.elevation = 0f
        (card as? LinearLayout)?.let { row ->
            row.gravity = Gravity.CENTER_VERTICAL
            // 图标与文字按中心对齐，避免旧文字基线参与单行高度测量。
            row.isBaselineAligned = false
        }
        card.setPaddingRelative(look.paddingHorizontal, look.paddingVertical, look.paddingHorizontal, look.paddingVertical)
        card.minimumHeight = 0
        card.layoutParams = copyParams(card.layoutParams).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT; height = ViewGroup.LayoutParams.WRAP_CONTENT }
        parts.icon.layoutParams = copyParams(parts.icon.layoutParams).apply {
            width = look.iconSize; height = look.iconSize
            if (this is LinearLayout.LayoutParams) marginEnd = look.iconGap
        }
        parts.textGroup?.let { group -> group.layoutParams = copyParams(group.layoutParams).apply {
            if (this is ViewGroup.MarginLayoutParams) marginStart = look.iconGap
        } }
        fun text(view: TextView?) { view ?: return
            view.layoutParams = copyParams(view.layoutParams).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                if (this is LinearLayout.LayoutParams) { weight = 0f; marginStart = 0 }
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, look.textSize)
            view.setTextColor(look.textColor)
            view.typeface = look.font
            view.includeFontPadding = look.fontPadding
            view.maxWidth = minOf(look.maxTextWidth, textWidth)
            view.setSingleLine(true)
            view.maxLines = 1
            view.ellipsize = TextUtils.TruncateAt.END
        }
        text(parts.title)
        text(parts.description)
        card.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun selectText(card: ViewGroup, bridge: PortalCapsuleCompat) {
        val parts = bridge.parts(card) ?: return
        val primary = bridge.primaryText(card, parts) ?: return
        listOfNotNull(parts.title, parts.description).forEach { text ->
            text.visibility = if (text === primary) View.VISIBLE else View.GONE
        }
    }

    override fun uninstall() {
        active = false
        val previousSessions = sessions.values.toList()
        previousSessions.forEach { it.timer?.let(main::removeCallbacks) }
        sessions.clear()
        val previous = styled.values.toList()
        styled.clear()
        val previousScaffolds = scaffolds.values.toList()
        scaffolds.clear()
        super.uninstall()
        main.post {
            previous.forEach { state ->
            state.observer.get()?.let { if (it.isAlive) it.removeOnPreDrawListener(state.listener) }
            }
            // 卡片与容器可能重叠，先恢复卡片，再还原更早记录的布局规则。
            (previous.flatMap { it.nodes } + previousScaffolds).forEach { saved -> saved.view.get()?.let { view ->
                view.animate().cancel()
                view.alpha = 1f; view.translationX = 0f
                saved.params?.let { view.layoutParams = it }
                view.setPaddingRelative(saved.padding[0], saved.padding[1], saved.padding[2], saved.padding[3])
                view.background = saved.background; view.outlineProvider = saved.outline; view.clipToOutline = saved.clip
                view.minimumHeight = saved.minHeight; view.elevation = saved.elevation
                view.id = saved.id
                if (view is LinearLayout) {
                    saved.gravity?.let { view.gravity = it }
                    saved.showDividers?.let { view.showDividers = it }
                    saved.baselineAligned?.let { view.isBaselineAligned = it }
                }
                if (view is TextView) {
                    view.visibility = saved.visibility
                    saved.textSize?.let { view.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
                    saved.colors?.let(view::setTextColor); view.typeface = saved.font
                    saved.fontPadding?.let { view.includeFontPadding = it }; saved.maxWidth?.let { view.maxWidth = it }
                    saved.singleLine?.let(view::setSingleLine)
                    saved.maxLines?.takeIf { it >= 0 }?.let { view.maxLines = it }
                    view.ellipsize = saved.ellipsize
                    view.transformationMethod = saved.transformation
                }
            } }
            previousSessions.forEach { session -> session.owner.get()?.let { owner -> runCatching {
                session.insets?.let(session.bridge::restoreTouches)
                session.bridge.smallCards(owner).forEach(session.bridge::cancelAnimation)
                session.bridge.params(owner).copyFrom(session.originalWindow)
                session.bridge.update(owner)
            } } }
        }
    }
}
