package io.github.kotoriinsky.tsuzuri.hook.compat

import android.content.Context
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kotoriinsky.tsuzuri.core.CopyDirect
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** 适配已取证的传送门 5.1.5 与引擎 4.12.26，不加载另一应用的代码。 */
class PortalCapsuleCompat(loader: ClassLoader) {
    private val deviceCards = WeakHashMap<ViewGroup, Boolean>()
    private val recommend = Class.forName("com.miui.contentextension.text.cardview.TaplusRecommendBaseCard", false, loader)
    /** 以原生绑定来源区分设备卡，避免按标题或图标猜测。 */
    val bindingMethods = listOf(
        "bindRecommendInfo" to "com.miui.contentextension.data.recognition.TextRecommendationInfo",
        "bindNerInfo" to "com.miui.contentextension.data.recognition.Entity",
        "bindDeviceInfo" to "com.miui.contentextension.clipboard.model.DeviceInfo",
    ).associate { (name, parameter) ->
        recommend.getDeclaredMethod(name, Class.forName(parameter, false, loader)).apply { isAccessible = true } to
            (name == "bindDeviceInfo")
    }
    private val splash = Class.forName("com.miui.contentextension.text.floatview.TaplusSplashFloatView", false, loader)
    val initViewMethod = splash.getDeclaredMethod("initView").apply { isAccessible = true }
    val loadingMethod = splash.getDeclaredMethod("lambda\$initView\$0").apply { isAccessible = true }
    val showMethods = listOf("showRecognitionCards", "showEditorCards", "showRecommendCards",
        "showNerRecommendCards", "showOtherNerRecommendCards", "showDevicesRecommendCards").map {
        splash.getDeclaredMethod(it).apply { isAccessible = true }
    }
    val hideMethod = splash.getDeclaredMethod("hideSplashFloatView", Boolean::class.javaPrimitiveType)
    val touchMethod = splash.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent::class.java)
    private val base = splash.superclass
    val destroyMethod = base.getDeclaredMethod("onDestroy")
    private fun field(name: String, owner: Class<*> = base) = owner.getDeclaredField(name).apply { isAccessible = true }
    private val rootField = field("mMainView")
    private val paramsField = field("wmParams")
    private val serviceField = field("mService")
    private val finishingField = field("mIsFinishing")
    private val loadingField = field("mIsLoading", splash)
    private val clipboardField = field("mIsClipboardView", splash)
    private val update = base.getMethod("updateViewLayout")
    private val cancel = Class.forName("com.miui.contentextension.services.TextContentExtensionService", false, loader)
        .getMethod("cancelTask")
    private val folme = Class.forName("miuix.animation.Folme", false, loader)
    private val use = folme.getMethod("use", View::class.java)
    private val clean = folme.getMethod("clean", View::class.java)
    private val state = Class.forName("miuix.animation.IFolme", false, loader).getMethod("state")
    private val to = Class.forName("miuix.animation.IStateStyle", false, loader)
        .getMethod("to", Array<Any>::class.java)
    private val config = Class.forName("miuix.animation.base.AnimConfig", false, loader)
    private val easeStyle = Class.forName("miuix.animation.utils.EaseManager\$EaseStyle", false, loader)
    private val setEase = config.getMethod("setEase", easeStyle)
    private val setDelay = config.getMethod("setDelay", Long::class.javaPrimitiveType)
    private val spring = Class.forName("miuix.animation.FolmeEase", false, loader)
        .getMethod("spring", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    private val translationX = Class.forName("miuix.animation.property.ViewProperty", false, loader)
        .getField("TRANSLATION_X").get(null)
    private val insetsListener = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
    private val insetsInfo = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
    private val addInsets = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", insetsListener)
    private val removeInsets = ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", insetsListener)
    private val setTouchable = insetsInfo.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
    private val touchableRegion = insetsInfo.getField("touchableRegion")

    fun isSplash(owner: Any) = splash.isInstance(owner)
    fun root(owner: Any) = rootField.get(owner) as ViewGroup
    fun params(owner: Any) = paramsField.get(owner) as WindowManager.LayoutParams
    fun isClipboard(owner: Any) = clipboardField.getBoolean(owner)
    fun isFinishing(owner: Any) = finishingField.getBoolean(owner)
    fun update(owner: Any) { update.invoke(owner) }
    fun beginResults(owner: Any) { loadingField.setBoolean(owner, false) }
    fun beginHide(owner: Any) { finishingField.setBoolean(owner, true) }
    fun finish(owner: Any, cancelTask: Boolean) {
        root(owner).visibility = View.INVISIBLE
        if (cancelTask) cancel.invoke(serviceField.get(owner))
    }

    /** 小爱 BubbleContainerView 的入场契约：宽度 + 边距，spring(0.82, 0.4)，逐卡 30ms。 */
    fun enter(card: View, edge: Int, delay: Long) {
        card.translationX = (card.width + edge).toFloat()
        card.translationY = 0f
        card.alpha = 1f
        val animation = config.getDeclaredConstructor().newInstance()
        setEase.invoke(animation, spring.invoke(null, 0.82f, 0.4f))
        setDelay.invoke(animation, delay)
        to.invoke(state.invoke(use.invoke(null, card)), arrayOf(translationX, 0f, animation) as Any)
    }
    fun cancelAnimation(card: View) { clean.invoke(null, card) }

    class InsetsHandle(val observer: WeakReference<ViewTreeObserver>, val listener: Any)
    /** 固定窗口只接受卡片区域的输入，空白处仍交给下层应用。 */
    fun restrictTouches(owner: Any): InsetsHandle {
        val reference = WeakReference(owner)
        val listener = Proxy.newProxyInstance(insetsListener.classLoader, arrayOf(insetsListener)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "PortalCapsuleInsets"
                "onComputeInternalInsets" -> {
                    val info = requireNotNull(args?.firstOrNull())
                    setTouchable.invoke(info, 3) // TOUCHABLE_INSETS_REGION，已核对本机 framework。
                    val region = touchableRegion.get(info) as Region
                    region.setEmpty()
                    reference.get()?.let { current ->
                        val root = root(current)
                        val origin = IntArray(2)
                        root.getLocationOnScreen(origin)
                        smallCards(current).filter { it.visibility == View.VISIBLE }.forEach { card ->
                            val position = IntArray(2)
                            card.getLocationOnScreen(position)
                            // 使用最终停靠区域，入场横移不扩大窗口的可触摸范围。
                            val x = position[0] - origin[0] - card.translationX.toInt()
                            val y = position[1] - origin[1] - card.translationY.toInt()
                            region.op(Rect(x, y, x + card.width, y + card.height), Region.Op.UNION)
                        }
                    }
                    null
                }
                else -> null
            }
        }
        val observer = root(owner).viewTreeObserver
        addInsets.invoke(observer, listener)
        return InsetsHandle(WeakReference(observer), listener)
    }
    fun restoreTouches(handle: InsetsHandle) {
        handle.observer.get()?.let { if (it.isAlive) removeInsets.invoke(it, handle.listener) }
    }

    fun node(owner: Any, name: String): View? {
        val root = root(owner)
        return root.findViewById(root.resources.getIdentifier(name, "id", root.context.packageName))
    }

    /** 底部主提示固定，其余结果向上排列；保留原生容器以供后到的推荐继续填充。 */
    fun deck(owner: Any): List<View> {
        val known = if (isClipboard(owner)) {
            listOfNotNull(node(owner, "clipboard_card"), node(owner, "ner_group"), node(owner, "devices_group"))
        } else {
            listOfNotNull(node(owner, "recognition_card"), node(owner, "recognition_card_2"), node(owner, "recommend_group"))
        }
        val parent = known.firstOrNull()?.parent as? ViewGroup ?: return known
        // 真机存在无固定 ID 的小爱入口卡。按已适配的卡片结构收集同级节点，
        // 不能只认 XML 中的三个 ID，否则固定高窗口会把附加卡留在顶部。
        val extra = (0 until parent.childCount).mapNotNull { index ->
            val child = parent.getChildAt(index) as? ViewGroup ?: return@mapNotNull null
            if (child in known) return@mapNotNull null
            val content = parts(child) ?: return@mapNotNull null
            child.takeIf { !content.title?.text.isNullOrEmpty() || !content.description?.text.isNullOrEmpty() }
        }
        return known.take(1) + extra + known.drop(1)
    }

    data class Parts(val icon: View, val textGroup: View?, val title: TextView?, val description: TextView?)
    fun markBinding(card: ViewGroup, device: Boolean) { deviceCards[card] = device }
    /** 设备卡显示目标名称，其余显示主标题；缺失时用另一项避免空胶囊。 */
    fun primaryText(card: ViewGroup, content: Parts): TextView? {
        val candidates = if (deviceCards[card] == true) listOf(content.description, content.title)
            else listOf(content.title, content.description)
        return candidates.firstOrNull { !it?.text.isNullOrBlank() }
    }
    fun parts(card: ViewGroup): Parts? {
        fun child(name: String): View? = card.findViewById(card.resources.getIdentifier(name, "id", card.context.packageName))
        return when (card.javaClass.simpleName) {
            "TaplusClipboardShrinkCard", "TaplusRecognitionShrinkCard" -> Parts(
                child("taplus_recognition_icon") ?: return null, null, child("taplus_recognition_text") as? TextView, null)
            "TaplusRecommendShrinkCard" -> Parts(child("shrink_recommend_icon") ?: return null,
                child("shrink_recommend_text_group"), child("shrink_recommend_title") as? TextView,
                child("shrink_recommend_abstract") as? TextView)
            "TaplusLoadingCard" -> Parts(child("splash_progress_bar") ?: return null, null, null, null)
            else -> null
        }
    }
    fun smallCards(owner: Any): List<ViewGroup> = buildList {
        fun visit(view: View) {
            if (view !is ViewGroup) return
            if (parts(view) != null) add(view) else (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
        }
        visit(root(owner))
    }

    data class Appearance(
        val background: Drawable,
        val paddingHorizontal: Int,
        val paddingVertical: Int,
        val iconSize: Int,
        val iconGap: Int,
        val textSize: Float,
        val textColor: android.content.res.ColorStateList,
        val maxTextWidth: Int,
        val font: Typeface,
        val fontPadding: Boolean,
        val descriptionSize: Float,
        val descriptionColor: android.content.res.ColorStateList,
        val descriptionFont: Typeface,
        val edge: Int,
        val bottom: Int,
        val gap: Int,
        val radius: Int,
    )

    /** 运行时读取本机胶囊模板；资源缺失或版本变化时整项降级原生。 */
    fun appearance(context: Context): Appearance {
        @Suppress("DEPRECATION")
        fun version(packageName: String) = context.packageManager.getPackageInfo(packageName, 0).versionName
        check(version(context.packageName) == "5.1.5") { "传送门版本未验证" }
        check(version(CopyDirect.ENGINE_PACKAGE) == "4.12.26") { "胶囊版本未验证" }
        val engine = context.createPackageContext(CopyDirect.ENGINE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        val res = engine.resources
        fun id(name: String, type: String) = res.getIdentifier(name, type, CopyDirect.ENGINE_PACKAGE)
            .also { check(it != 0) { "缺少胶囊资源 $name" } }
        val template = LayoutInflater.from(engine).inflate(id("view_bubble", "layout"), null)
        val row = template.findViewById<LinearLayout>(id("collapsed_layout", "id"))
        val icon = template.findViewById<ImageView>(id("bubble_icon", "id"))
        val title = template.findViewById<TextView>(id("bubble_title", "id"))
        val description = template.findViewById<TextView>(id("bubble_description", "id"))
        fun font(weight: Int, fallback: Typeface) = runCatching {
            // 本机 BubbleView 标题同样读取系统可变字体，权重 450；不复制字体文件。
            val systemFont = Font.Builder(File("/system/fonts/MiSansVF.ttf"))
                .setFontVariationSettings("'wght' $weight").build()
            Typeface.CustomFallbackBuilder(FontFamily.Builder(systemFont).build()).build()
        }.getOrElse { Typeface.create(fallback, weight, false) }
        fun dimension(name: String) = res.getDimensionPixelSize(id(name, "dimen"))
        return Appearance(
            res.getDrawable(id("bubble_background", "drawable"), engine.theme),
            row.paddingStart, row.paddingTop, icon.layoutParams.width,
            (icon.layoutParams as LinearLayout.LayoutParams).marginEnd,
            title.textSize, title.textColors, title.maxWidth, font(450, title.typeface), title.includeFontPadding,
            description.textSize, description.textColors, font(330, description.typeface),
            dimension("bubble_edge_margin"), dimension("bubble_default_to_bottom_space"),
            dimension("bubble_space"), dimension("bubble_background_radius"),
        )
    }
}
