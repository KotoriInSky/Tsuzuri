package io.github.kotoriinsky.tsuzuri.hook.feature.screenrecognition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ContentCapabilities
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.util.concurrent.atomic.AtomicInteger

/**
 * 小爱识屏页呈现改造：全屏铺满 + 气泡横排（带文字、紧凑）+ 常驻翻译/提取文字。
 *
 * ## 为什么只改样式，不改识别
 *
 * 用户拍板（2026-09-17）：「识别人物 / 淘宝同款」这些气泡本身是**小爱云识别**的结果，
 * 识别部分不动，只改呈现。识屏页的页面生命周期、云链路、网络门禁全部维持原样 ——
 * 所以没有走「改道传送门」重路线，那条保留为后续可选档。
 *
 * ## 取证（三轮实测定案）
 *
 *  - 窗口**本来就是全屏**（dumpsys 实测 `frame=[0,0][1220,2656]`）；「圆角卡 +
 *    外面一圈」来自**布局层**：`cv_screen_recognition_border`（圆角描边 View）+
 *    `fl_screen_recognition_container`（截图父容器被约束成卡片大小居中）；
 *  - `mask_view`（ScreenRecognitionMaskView）**是圈选交互层本体**：内部含
 *    TrailMaskView / CircleHighLightView / UnderlineHighLightView，且
 *    dispatchTouchEvent 在这里拦截圈选手势 —— GONE 它 = 圈选死（实测踩过）；
 *  - 气泡点击链路：`bs1.u.initEntity` 点击 → `sr1.b.clickBubble(tipBubble)` →
 *    发 `TIP_CLICKED` 广播 → presenter `clickTip` → `clickAction.execute(presenter)`。
 *    常驻项直接复用这条链：翻译用宿主自带 `TextTranslateAction`（需 setSelectedText），
 *    复制用 `UnSupportAction`（execute 是 no-op）+ content 前缀标记，
 *    在 clickBubble 拦截层自己写剪贴板；
 *  - 识别全文来自 TEXT 类型实体（xn0.b），按 box 的 y 坐标排序拼接。
 *
 * ## 实现（五个 hook 点）
 *
 *  1. `ScreenRecognitionActivity.onResume` **after**：页面一打开就截图卡片铺满
 *     （不再等识别结果，消除卡片→全屏跳变）；
 *  2. `EntityBubbleRV.setLayoutManager` **after**（thisObject 类名过滤）：气泡横排 +
 *     宽度撑满；post 里再做一次铺满兜底（结果就绪路径，幂等）；
 *  3. `EntityViewHolder`（混淆名 `bs1.u`）`initEntity` **after**：气泡缩小
 *     （图标压到 [ICON_SIZE_PX]、文字压到 [TEXT_SIZE_DIP]、内边距减半），
 *     **保留文字说明**（一轮曾隐藏文字，用户要求恢复）；
 *  4. `zr1.d.createHybImageStructureResultFrom` **after**（唯一组装 TipBubble
 *     UI 状态的路径，baksmali 取证）：把「问小爱 / 翻译 / 提取文字 / 分享」
 *     四个常驻气泡插到云端结果前面（问小爱不依赖文本，其余三个用
 *     arg0.getTotalText() 的识别全文）；`sr1.b.clickBubble` 拦截
 *     复制/分享标记前缀，自己完成动作不广播。
 *  5. 运行时广播接收器（RECEIVER_EXPORTED + token 校验）：外部广播
 *     [TRIGGER_ACTION] 后反射调宿主识屏入口（ScreenRecognitionHelper，混淆名
 *     `db`）的 startScreenRecognition 全空重载 —— adb / Tasker / 宏工具都能当
 *     快捷方式调起识屏。
 *
 * 上文的混淆名（`bs1.u`、`sr1.b`、`zr1.d`）是旧版取证名，随宿主版本漂移；
 * 现在由 [RecognitionClassLocator] 按方法签名定位，0 个或多个候选都让该子能力降级跳过
 * 并记日志，不影响其它子能力。
 *
 * ## 关态与回滚
 *
 * 关态 = `HookRegistry` 根本不装（规范 §9）。卸载 unhook 后新打开的识屏页恢复原样；
 * 已经开着的识屏页要重开 —— `Catalog` 里因此用 `HOT_RELOAD`，与 PORTAL_IMAGE_* 同档。
 */
object RecognitionBubbleStyle : Feature(Catalog.require(Catalog.RECOGNITION_LOOK)) {

    override val installId: String get() = "recognition_look:xiaoai"

    /** 结果页子项分属两类（2026-10-25 重组）：布局在「识屏样式」，文字操作在「从屏幕获取文字」。 */
    private val LOOK_SUBS = listOf("full_bleed", "horizontal", "compact")
    private val TEXT_SUBS = listOf("translate", "copy_text", "share_text", "external_trigger")

    private fun pick(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs, name: String) =
        ContentCapabilities.on(prefs, if (name in TEXT_SUBS) Catalog.SCREEN_TEXT else Catalog.RECOGNITION_LOOK, name)

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = !isSystemServer && (processName == io.github.kotoriinsky.tsuzuri.core.ScopeTarget.VOICE_ASSIST.packageName)

    override fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs) = LOOK_SUBS.any { pick(prefs, it) } || TEXT_SUBS.any { pick(prefs, it) }

    private const val TAG = "FP-RecStyle"

    /** 识屏页里的实体气泡列表（类名未混淆，取证自 APK 布局树与反编译源码）。 */
    private const val RV_CLS = "com.xiaomi.voiceassistant.screenrecognition.view.EntityBubbleRV"

    private const val LM_CLS = "androidx.recyclerview.widget.LinearLayoutManager"

    /** 识屏 Activity（类名未混淆）。onResume 是「打开即铺满」的挂点。 */
    private const val ACT_CLS =
        "com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity"

    /**
     * 三个混淆类（EntityViewHolder / 气泡点击分发 / 结果组装）不再写死名字，
     * 由 [RecognitionClassLocator] 按方法签名定位。
     * 结果组装的 createHybImageStructureResultFrom(*, Bitmap) 是**唯一**把 TipBubble
     * 列表装进 UI 状态的路径；arg0 的 `getTotalText()` 直接给识别全文。
     */
    private val LOCATOR_SPECS = listOf(
        RecognitionClassLocator.VIEW_HOLDER,
        RecognitionClassLocator.CLICK_DISPATCH,
        RecognitionClassLocator.STRUCTURE,
    )

    /**
     * 定位一项；失败记日志 + 计入 missed（写规格标签和原因，日志里能看出几个候选）。
     * 三项共用一次扫描（locate 内缓存）。
     */
    private fun locateMethod(
        ctx: HookContext,
        spec: RecognitionClassLocator.Spec,
        missed: MutableList<String>,
    ): java.lang.reflect.Method? {
        val all = RecognitionClassLocator.locate(ctx.classLoader, LOCATOR_SPECS)
        val r = all[spec.label]
        if (r?.method == null) {
            val reason = r?.reason ?: "未定位"
            ctx.log(Log.WARN, "定位 ${spec.label} 失败：$reason（${RecognitionClassLocator.lastScanSummary}），该子能力降级跳过")
            missed += "${spec.label}($reason)"
            return null
        }
        ctx.log(Log.INFO, "定位 ${spec.label} → ${r.cls?.name}（${r.via}；${RecognitionClassLocator.lastScanSummary}）")
        return r.method
    }

    /** 宿主包名（getIdentifier 解析 id 用，不硬编码资源数值）。 */
    private const val HOST_PKG = "com.miui.voiceassist"

    /** 截图卡片两件套 + 动画期残留视觉层（uiautomator dump / 布局树取证，资源名未混淆）。 */
    private const val BORDER_ID = "cv_screen_recognition_border"
    private const val SHOT_CONTAINER_ID = "fl_screen_recognition_container"

    /**
     * `iv_blue_bg`：原卡片底部的蓝色渐变 ImageView（气泡背后的衬底）。
     * 铺满后它的约束把它缩在左下角，识屏 PAG 动画一停就露出来 —— 用户看到的
     * 「左下角彩色块」。它在布局里只是装饰衬底（气泡 item 有自己的胶囊背景），
     * 铺满时直接 GONE。
     */
    private const val BLUE_BG_ID = "iv_blue_bg"

    /** PAG 识别扫描动画（全屏光效）。动画放完若宿主不回收会留最后一帧，结果就绪时 GONE 兜底。 */
    private const val ANIM_ID = "recognition_animation"

    /**
     * `cl_answer_xiaoai`：底部工具条（`iv_exit` 退出识屏 + `cl_query` 问小爱）。
     *
     * ★ 它是截图容器「矮一截」的**唯一原因**：容器的底部约束是
     * `layout_constraintBottom_toTopOf = cl_answer_xiaoai`，所以容器高度被卡在
     * `屏高 - 工具条高`（实测 2656 - 211 = 2445 / 2656 - 136 = 2520，随宿主版本浮动）。
     *
     * **处理方式：GONE 掉它**，让底部约束的锚点消失 —— 容器随即自然占满整屏，
     * 不需要给容器做任何补偿（负 margin 那套方向是错的，实测会把容器拽到 2867）。
     * 退出识屏由系统返回键 / 手势承担；「问小爱」在识屏结果气泡里本就另有入口。
     */
    private const val ANSWER_BAR_ID = "cl_answer_xiaoai"

    // ---- 断开 ConstraintLayout 约束（改 LayoutParams 的 public 字段，不反射 ConstraintSet）----

    /**
     * 约束锚点字段（`ConstraintLayout.LayoutParams` 的 public int，反编译核实）。
     * 全部写成 [CONSTRAINT_UNSET](-1) 即「无约束」。
     */
    private val CONSTRAINT_ANCHOR_FIELDS = arrayOf(
        "topToTop", "topToBottom",
        "bottomToBottom", "bottomToTop",
        "startToStart", "startToEnd",
        "endToEnd", "endToStart",
        "leftToLeft", "leftToRight",
        "rightToRight", "rightToLeft",
        "baselineToBaseline", "baselineToTop", "baselineToBottom",
        "circleConstraint",
    )

    /**
     * `0dp`（约束尺寸）下会限制实际大小的字段：最大/最小尺寸。清零后不再设上限。
     * 不动 `matchConstraintDefaultWidth/Height` —— 它的取值语义另有含义（SPREAD 等），
     * 改成 0 反而会引入新问题。
     */
    private val CONSTRAINT_SIZE_FIELDS = arrayOf(
        "matchConstraintMaxWidth", "matchConstraintMaxHeight",
        "matchConstraintMinWidth", "matchConstraintMinHeight",
    )

    /** ConstraintSet / LayoutParams 里的「无约束」值（反编译核实 = -1）。 */
    private const val CONSTRAINT_UNSET = -1

    /**
     * `ConstraintLayout.LayoutParams` 的**运行时类名**。
     *
     * 不能直接 `import androidx.constraintlayout.widget.ConstraintLayout` 比较 ——
     * 宿主 APK 里的 androidx 被 R8 混淆/重打包，类名可能与编译期依赖不一致，
     * 且宿主 androidx 版本变化会让类加载失败。用字符串比类名最稳。
     */
    private const val CONSTRAINT_LP_CLS =
        "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"

    // ---- 进树前铺满（View.setLayoutParams，治本层）----

    /** 目标 id 懒解析缓存（见 [inflateTargetId]）。0 = 还没解析出来。 */
    @Volatile
    private var inflateTargetIdCache: Int? = null

    /** 进树前铺满成功改写的次数（诊断用）。 */
    private val inflatePatchedCount = AtomicInteger(0)

    /** id 还没解析出来时是否已经记过一笔（避免 inflate 期每个视图都判断）。 */
    @Volatile
    private var inflateProbeSeen = false

    /**
     * 挂在 decorView 上的 tag：标记「已经挂了 pre-draw 铺满监听」。
     *
     * 为什么要去重：`onCreate` 与 `onResume` 都会尝试挂，Activity 复用/重建时
     * 还会再来一轮。`ViewTreeObserver` 的监听不会自动去重，叠多个就会
     * 在 `onPreDraw` 里重复改 layoutParams（性能白烧，还可能互相打架）。
     */
    private val PRE_DRAW_TAG = "FP_REC_PREDRAW".hashCode()

    /**
     * 挂在 decorView 上的 tag：标记「已登记 attach 后挂 pre-draw」。
     *
     * 为什么需要：`onCreate` 时 decor 常未 attach（实测 `decor=0x0`），
     * 那时挂的 `OnPreDrawListener` 收不到任何回调（哑监听）。改成先登记
     * `addOnAttachStateChangeListener`、attach 后再挂 pre-draw —— 这个 tag
     * 用来防止 `onCreate`/`onResume` 各登记一份。
     */
    private val ATTACH_TAG = "FP_REC_ATTACH".hashCode()

    /**
     * 判定「实测已占满整屏」时允许的像素误差。
     *
     * 为什么需要容差：部分 ROM 会因为圆角 / 手势条 inset 让容器少 1–2px，
     * 严格相等会把它判成「还没铺满」→ 每帧都重写 LayoutParams，
     * 与宿主互相 requestLayout（白烧 CPU，还可能让画面抖）。
     */
    private const val GEOMETRY_TOLERANCE_PX = 2

    /**
     * 挂在截图容器上的 view tag：标记「尺寸监听已挂过」。
     * 用资源 id 之外的稳定 int（`View.setTag(int, Object)` 要求非 0 且唯一）。
     */
    private val SIZE_WATCH_TAG = "FP_REC_SIZE_WATCH".hashCode()

    // ---- 常驻气泡（翻译 / 提取文字）----

    /** 复制/分享动作的内容前缀标记：UnSupportAction.execute 是 no-op，由我们在 clickBubble 拦截。 */
    private const val COPY_PREFIX = "FP_COPY##"
    private const val SHARE_PREFIX = "FP_SHARE##"

    /** 常驻气泡图标（宿主资源，aapt2 资源表取证）。 */
    private const val ICON_TRANSLATE = "ic_translation_search"
    private const val ICON_COPY = "ic_copy"
    private const val ICON_QUERY = "ai_search_xiaoai_icon"
    private const val ICON_SHARE = "ic_material_menu_share"

    /** 常驻气泡文案（宿主是中文环境；写成常量便于改）。 */
    private const val LABEL_TRANSLATE = "翻译"
    private const val LABEL_COPY = "复制识屏文字"
    private const val LABEL_QUERY = "问小爱"
    private const val LABEL_SHARE = "分享识屏文字"

    // ---- 气泡紧凑化参数（手感值，真机可调）----

    /** 缩小后的图标边长（px，520dpi 下约 17dp）。 */
    private const val ICON_SIZE_PX = 48

    /** 缩小后的文字字号（dp）。 */
    private const val TEXT_SIZE_DIP = 11f

    /** LinearLayoutManager.HORIZONTAL（androidx 常量，宿主类加载器里取）。 */
    private const val HORIZONTAL = 0

    /** 宿主 application context（剪贴板/Toast 用），首次 RV post 时缓存。 */
    private var appContext: android.content.Context? = null

    // ---- 外部快捷方式（广播触发识屏）----

    /**
     * 外部调起识屏的广播 action：`adb shell am broadcast` / Tasker / 宏工具都能发。
     * 接收器装在宿主**主进程**里，收到后反射调 ScreenRecognitionHelper（混淆名 db）
     * 单例的 startScreenRecognition 全空重载 —— bitmap 传 null 时宿主自己截屏
     * （canReflectScreenCapture 反射 android.window.ScreenCapture），与悬浮球
     * FloatMultiInputContainer 的全空调用同一条路，校验链/Toast 全部复用原生。
     */
    private const val TRIGGER_ACTION = "io.github.kotoriinsky.tsuzuri.action.SCREEN_RECOGNITION"

    /** 触发口令：广播必须带 `--es fp_token <值>`，防止设备上任意 app 无意触发。 */
    private const val TRIGGER_TOKEN = "flymeport-recognize"
    private const val TRIGGER_TOKEN_KEY = "fp_token"

    /** 识屏入口类：ScreenRecognitionHelper（jadx 取证原名；混淆名 `db` 随版本会漂移）。 */
    private const val HELPER_CLS = "com.xiaomi.voiceassistant.db"

    /** 已注册的触发接收器（幂等 + 卸载时反注册）。 */
    private var triggerReceiver: BroadcastReceiver? = null

    /** 已改过多少次排布（诊断用；低频路径，逐条打日志没负担，仍限流）。 */
    private val patched = AtomicInteger(0)

    override fun install(ctx: HookContext): InstallReport {
        var hooked = 0
        val missed = mutableListOf<String>()

        fun chosen(name: String) = pick(ctx.prefs, name)
        if (chosen("full_bleed")) {
            hooked += installInflateTimeFullBleed(ctx, missed)
            hooked += installImmediateFullBleed(ctx, missed)
            hooked += installDecorationSuppressor(ctx, missed)
        }
        if (chosen("horizontal")) hooked += installHorizontalLayout(ctx, missed)
        if (chosen("compact")) hooked += installCompactBubbles(ctx, missed)
        if (listOf("translate", "copy_text", "share_text").any { chosen(it) }) hooked += installPersistentBubbles(ctx, missed)
        if (chosen("external_trigger")) hooked += installExternalTrigger(ctx, missed)
        ctx.log(Log.INFO, "独立效果=" + ContentCapabilities.names.getValue(key).filter { chosen(it) }.joinToString())

        if (missed.isNotEmpty()) {
            // 降级原因落日志：让「为什么没生效」可查，而不是静默失效
            ctx.log(Log.WARN, "部分 hook 未挂上（宿主改版/混淆名变化）: $missed")
        }
        val report = InstallReport(hooked, missed)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun publish(attempt: Int) {
            val app = hostAppContext()
            if (app == null) {
                if (attempt < 5) main.postDelayed({ publish(attempt + 1) }, 1000)
                return
            }
            appContext = app
            runCatching {
                app.contentResolver.call(android.net.Uri.parse("content://io.github.kotoriinsky.tsuzuri.hookstatus"), "report", key,
                    android.os.Bundle().apply { putInt("hooked", report.hooked); putStringArrayList("missing", ArrayList(report.missing)) })
            }.onFailure { ctx.log(Log.WARN, "适配状态上报失败", it) }
        }
        main.post { publish(0) }
        return report
    }

    /**
     * 卸载：除了 unhook，还要把运行时广播接收器反注册 ——
     * 关态必须与未安装模块完全一致（规范 §9）。
     */
    override fun uninstall() {
        triggerReceiver?.let { r ->
            triggerReceiver = null
            hostAppContext()?.let { c ->
                runCatching { c.unregisterReceiver(r) }
                    .onFailure { Log.w(TAG, "触发接收器反注册失败（不影响 unhook）", it) }
            }
        }
        super.uninstall()
    }

    /**
     * hook 点 0（**最早的一个**）：在**视图进树之前**就把截图容器的布局参数换掉。
     *
     * ## 为什么必须有这么一层（2026-09-18，用户截图实锤「小卡」）
     *
     * 之前所有方案（`onCreate`/`onResume` 里改、`decor.post`、`OnPreDraw`）都在
     * 「宿主已经摆过一次布局**之后**」动手 —— 差别只是「早一帧还是晚一帧」。
     * 只要宿主有一次 `measure/layout/draw` 用的是 XML 原参数，那一帧就是小卡。
     * 用户给的截图正是这个瞬间：整块内容缩在中间、上下大片留白、左右有圆角。
     *
     * ## 容器为什么天生是「小卡」（aapt2 取证，`res/69n.xml`）
     *
     * ```
     * E: include                              ← id = fl_screen_recognition_container
     *   layout_width  = 0dp                   ← 0dp = 约束尺寸，不是「撑满」
     *   layout_height = 0dp
     *   layout_marginTop    = 4dp             ← screen_recognition_image_margin_top
     *   layout_marginBottom = 18dp            ← screen_recognition_image_margin_bottom
     *   layout_constraintBottom_toTopOf = cl_answer_xiaoai   ← 锚在底部工具条顶部
     *   layout_constraintTop_toTopOf    = parent
     * ```
     *
     * 即：`高 = 屏高 − 工具条高 − 4dp − 18dp`。这就是「矮一截」+「下移」的**全部**原因，
     * 不需要任何补偿性猜测 —— 只要把 width/height 换成 MATCH_PARENT、margin 清零、
     * 并把底部约束解开（避免求解器仍按 `toTopOf` 算），它自然就是整屏。
     *
     * ## 为什么挂 `View.setLayoutParams`（framework 层）
     *
     * `LayoutInflater` 在 inflate 每个节点时，把 XML 里解析出来的
     * `LayoutParams` 通过 **`View.setLayoutParams(LayoutParams)`** 赋给视图 ——
     * 这是**唯一**的入口，且发生在**视图被 addView 进树之前**。
     * 在这里改掉，宿主拿到的第一手参数就是全屏的，**不存在小卡这一帧**。
     *
     * 选它而不是 `LayoutInflater.Factory2`：宿主是 AppCompat（`FragmentActivity`），
     * 它会在自己的 `onCreate` 里 `installViewFactory()` **覆盖**已有 Factory2，
     * 包装顺序很脆；而 `setLayoutParams` 是 framework 方法，不受宿主
     * androidx 混淆影响（与 [installDecorationSuppressor] 同一个立论基础，
     * 那里已经实测生效）。
     *
     * ## 与其它 hook 点是什么关系
     *
     * 这一层是**治本**（首帧就不可能错），后面几层保留为**兜底**（防宿主在运行中
     * 重新 inflate / 重新 setLayoutParams，例如横竖屏切换、页面重建）。
     * 全部幂等，重复执行无副作用。
     */
    private fun installInflateTimeFullBleed(ctx: HookContext, missed: MutableList<String>): Int {
        val m = try {
            View::class.java.getDeclaredMethod(
                "setLayoutParams",
                android.view.ViewGroup.LayoutParams::class.java,
            )
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "找不到 View.setLayoutParams，进树前铺满降级为 pre-draw", t)
            missed += "View.setLayoutParams"
            return 0
        }
        return try {
            track(
                ctx.module.hook(m).intercept { chain ->
                    val v = chain.thisObject as? View
                    val lp = chain.getArg(0) as? android.view.ViewGroup.LayoutParams
                    if (v != null && lp != null) {
                        // 解析目标 id 之前一律放行（行为与未装模块一致）
                        if (inflateTargetIdOf(v, ctx)) {
                            // ★ 就地改参数：宽度/高度换 MATCH_PARENT、margin 清零。
                            //   注意必须改**同一个对象**（宿主/父容器可能已经持有引用），
                            //   不能新建 LayoutParams 替换 —— 那样约束字段会丢。
                            val before = "${lp.width}x${lp.height}"
                            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            (lp as? android.view.ViewGroup.MarginLayoutParams)
                                ?.setMargins(0, 0, 0, 0)
                            // 解开 ConstraintLayout 的底部锚点：否则求解器仍可能按
                            // `constraintBottom_toTopOf=cl_answer_xiaoai` 把它压矮。
                            clearBottomAnchor(lp, ctx)
                            inflatePatchedCount.incrementAndGet()
                            ctx.log(
                                Log.INFO,
                                "进树前已把截图容器改为全屏参数（$before → MATCH_PARENT，margin/底部锚点已清）",
                            )
                        } else if (inflateProbeWanted(v, ctx)) {
                            // 目标 id 尚未解析出来：先记下「这里有一个待处理的视图」，
                            // 等 id 解析成功后由后续兜底路径收口（仅记一条，不刷屏）
                            inflateProbeSeen = true
                        }
                    }
                    chain.proceed()
                },
            )
            ctx.log(Log.INFO, "已挂上进树前铺满（View.setLayoutParams 过滤，id 懒解析）")
            1
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "hook View.setLayoutParams 失败（降级为 pre-draw）", t)
            missed += "View.setLayoutParams"
            0
        }
    }

    /**
     * 这个 View 是不是「截图容器进树那一刻」。
     *
     * 判定只用 **id**（`fl_screen_recognition_container`）—— 名字稳定、不随视图类混淆。
     * 用懒解析缓存，避免每次 setUpLayoutParams 都走一次 `getIdentifier`（那很贵：
     * inflate 期间每个节点都会调一次这个方法）。
     *
     * @return true 表示「是目标容器，应该改」
     */
    private fun inflateTargetIdOf(v: View, ctx: HookContext): Boolean {
        val id = v.id
        if (id == View.NO_ID) return false
        val target = inflateTargetId(ctx)
        return target != 0 && id == target
    }

    /** 目标 id 懒解析 + 缓存（首次请求时 `ActivityThread.currentApplication()` 可能仍是 null）。 */
    private fun inflateTargetId(ctx: HookContext): Int {
        inflateTargetIdCache?.let { return it }
        val app = hostAppContext() ?: return 0
        synchronized(this) {
            inflateTargetIdCache?.let { return it }
            val id = try {
                app.resources.getIdentifier(SHOT_CONTAINER_ID, "id", HOST_PKG)
            } catch (t: Throwable) {
                0
            }
            if (id != 0) {
                inflateTargetIdCache = id
                ctx.log(Log.INFO, "截图容器 id 已解析: $SHOT_CONTAINER_ID=$id")
            }
            return id
        }
    }

    /**
     * id 没解析出来时，判断「这个视图值不值得记一笔」—— 只在**同一个进程里**
     * 记一次，避免 inflate 期每个视图都触发一次无意义的判断。
     */
    private fun inflateProbeWanted(v: View, ctx: HookContext): Boolean {
        if (inflateProbeSeen) return false
        // 容器是个 include 出来的 FrameLayout 系；宽高都是 0dp 的才像
        val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return false
        return lp.width == 0 && lp.height == 0
    }

    /**
     * 清掉 ConstraintLayout 的**底部锚点**（`layout_constraintBottom_*`）。
     *
     * 为什么必要：宽度/高度写成 `MATCH_PARENT` 对普通 ViewGroup 就够了，但
     * ConstraintLayout 的 `LayoutParams` 是它自己的子类，**约束字段仍在生效**。
     * 实测（`res/69n.xml`）容器的 `constraintBottom_toTopOf = cl_answer_xiaoai`
     * 且 `constraintTop_toTopOf = parent` —— 上下两个锚点同时存在时，
     * 即使 height=MATCH_PARENT，求解器也会按锚点算。把底部锚点解掉（置 UNSET），
     * 只留顶/左/右锚点，才是真正的「铺满」。
     *
     * 走反射改字段（不 import `ConstraintLayout`，避免宿主 androidx 版本差异导致
     * 类加载失败）；字段名被 R8 混淆，按 [CONSTRAINT_ANCHOR_FIELDS] 白名单逐个试。
     * 改不到不算失败 —— 上层还有 pre-draw / 布局监听兜底。
     */
    private fun clearBottomAnchor(lp: android.view.ViewGroup.LayoutParams, ctx: HookContext) {
        try {
            // 只处理 ConstraintLayout.LayoutParams（父类是普通 LayoutParams）
            if (lp.javaClass.name != CONSTRAINT_LP_CLS) return
            var cleared = false
            for (name in CONSTRAINT_ANCHOR_FIELDS) {
                if (!name.startsWith("bottom")) continue
                if (setIntField(lp, name, CONSTRAINT_UNSET, ctx)) cleared = true
            }
            if (cleared) ctx.log(Log.INFO, "已解开截图容器的底部约束锚点（cause=$CONSTRAINT_LP_CLS）")
        } catch (t: Throwable) {
            // 非 ConstraintLayout 参数：正常（普通 FrameLayout 父容器），不需要处理
        }
    }

    /**
     * hook 点 1（兜底）：识屏 Activity 一起一落两次铺满。
     *
     * 为什么挂了 [installInflateTimeFullBleed] 之后这一层还要留：
     * 进树前那一层只能改「inflate 那一刻」的参数。宿主在运行中仍可能
     * **重新 inflate**（横竖屏切换、页面重建、结果刷新时重建视图），
     * 那时视图已经是新的对象、参数又被 XML 重置 —— 需要这一层再收口。
     *
     * 同时它也是「进树前那层没赶上」时的补救（例如宿主动态 `addView` 而非 inflate）。
     */
    private fun installImmediateFullBleed(ctx: HookContext, missed: MutableList<String>): Int {
        val actCls = try {
            Class.forName(ACT_CLS, false, ctx.classLoader)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "class not found: $ACT_CLS，跳过打开即铺满", t)
            missed += ACT_CLS
            return 0
        }
        var hooked = 0
        // onCreate：抢在首帧之前（OnPreDraw 一挂上就能拦第一次 draw）
        hooked += hookActivityLifecycle(ctx, actCls, "onCreate", missed) { activity, decor ->
            // 视图树还可能在 onCreate 中途 inflate，pre-draw 是最稳的时机
            armPreDrawFullBleed(activity, decor, ctx)
            // 同时再走一次「常规」路径：已 attach 的情况下（onCreate 之后重建）立即生效
            decor.post { applyFullBleedSafely(activity, decor, ctx) }
        }
        // onResume：兜住复用 / 重建
        hooked += hookActivityLifecycle(ctx, actCls, "onResume", missed) { activity, decor ->
            armPreDrawFullBleed(activity, decor, ctx)
            decor.post { applyFullBleedSafely(activity, decor, ctx) }
        }
        return hooked
    }

    /**
     * 在下一帧绘制**之前**执行铺满，直到容器**实测**占满整屏（不是改完参数就走）。
     *
     * 为什么用 `OnPreDraw` 而不是 `decor.post`：`post` 排的是消息队列，落到
     * 下一轮 choreographer 之后，中间**已经画过一帧**；`OnPreDraw` 挂在
     * ViewTreeObserver 上，在 `performDraw` 之前回调，改完 LayoutParams 后
     * 返回 `false` 会中止这一帧并请求重新布局 —— 于是**那一帧也不会画成小卡片**。
     *
     * ★ 摘监听的条件（2026-09-18 修正，这是「动画开始仍跳一下小卡」的根因）：
     * 第一版用的是「**策略**已生效」（`isFullBleedNow`：宽高是 MATCH_PARENT、
     * margin 全 0）就 `detach()`。但**布局参数对 ≠ 实测尺寸对** ——
     * 首次 pre-draw 时 LayoutParams 可能早就被 `onCreate` 那次调用改成
     * MATCH_PARENT 了（或 XML 本来就那样），于是监听在第一帧就自行摘掉、
     * **一次都没真正改过**，紧接着宿主按自身约束把容器摆成 2520 →
     * 用户看到「跳一下小卡」。
     *
     * 为什么是「不稳定 / 某个应用第一次识别才出现」：LayoutParams 是否已被
     * 规整过、以及首帧时父容器测量到没到，都取决于宿主本次的启动路径 ——
     * 热路径上前面某次调用已经把参数规整过了，所以只在冷启动时暴露。
     *
     * → 现在摘监听要求**实测高度 == 根视图高度**（几何收敛），
     *   并且**从未生效过就不摘**（至少真正改过一次）。
     */
    private fun armPreDrawFullBleed(
        activity: android.app.Activity,
        decor: View,
        ctx: HookContext,
    ) {
        try {
            // ★ 未 attach 的 decor 挂 pre-draw 是「哑监听」（2026-09-18 实测踩到）：
            //   `onCreate` 时 decor 还没 attach 到 Window，ViewTreeObserver 不会派发
            //   onPreDraw。日志铁证：`pre-draw 已挂上（decor=0x0）` 之后整整 12 秒
            //   没有一条回调，宿主已把小卡完整画出来 → 那正是用户截图里的一帧。
            //   所以：没 attach 就先登记一个 attach 监听，等 attach 后再挂 pre-draw。
            if (!decor.isAttachedToWindow) {
                // 幂等：同一 decor 只登记一次
                if (decor.getTag(ATTACH_TAG) != null) return
                decor.setTag(ATTACH_TAG, java.lang.Boolean.TRUE)
                decor.addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            runCatching {
                                v.removeOnAttachStateChangeListener(this)
                                v.setTag(ATTACH_TAG, null)
                                ctx.log(Log.INFO, "decor 已 attach，改挂 pre-draw 铺满")
                                armPreDrawFullBleed(activity, v, ctx)
                            }
                        }

                        override fun onViewDetachedFromWindow(v: View) = Unit
                    },
                )
                ctx.log(Log.INFO, "decor 尚未 attach（${decor.width}x${decor.height}），已登记 attach 后挂 pre-draw")
                return
            }
            val vto = decor.viewTreeObserver
            if (!vto.isAlive) {
                ctx.log(Log.WARN, "pre-draw 未挂：decor 的 ViewTreeObserver 已失效")
                return
            }
            // 幂等：同一次打开可能 onCreate/onResume 都触发，避免叠加多个监听
            val key = PRE_DRAW_TAG
            if (decor.getTag(key) != null) {
                ctx.log(Log.INFO, "pre-draw 已在监听中（跳过重复挂载）")
                return
            }
            decor.setTag(key, java.lang.Boolean.TRUE)
            ctx.log(
                Log.INFO,
                "pre-draw 已挂上（decor=${decor.width}x${decor.height} " +
                    "container=${decorContainerSize(decor, ctx)}）",
            )
            // 本次监听「是否真的动手改过」——没改过就不许摘（见上面注释）
            var everApplied = false
            // 尺寸连续为 0 的帧数：超过阈值就当「永远不会测量」直接放行，避免死循环
            var zeroFrames = 0
            vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!vto.isAlive) {
                        detach()
                        return true
                    }
                    // 还没测量：放过这一帧，下一帧再来。
                    // ★ 但不能无限等：宿主可能在首帧后才真正 inflate 内容，
                    //   此时 decor 已 attach 但一直 0 → 长期返回 false 会让画面卡死。
                    //   等够 60 帧（约 1 秒）就放行，交给后面的兜底路径。
                    if (decor.width <= 0 || decor.height <= 0) {
                        zeroFrames++
                        if (zeroFrames > 60) {
                            ctx.log(
                                Log.WARN,
                                "pre-draw 放弃：decor 连续 $zeroFrames 帧尺寸为 0，交给兜底路径",
                            )
                            detach()
                        }
                        return true
                    }
                    zeroFrames = 0
                    // 已经**实测**占满且此前真的改过 → 任务完成，摘监听不再干预
                    if (everApplied && isDecorGeometricallyFull(decor, ctx)) {
                        detach()
                        ctx.log(Log.INFO, "pre-draw 铺满完成，监听已摘除")
                        return true
                    }
                    // 还没铺满：改一次，然后**中止本帧**（返回 false = 不画这一帧），
                    // 用户看不到「小卡片」那帧，下次绘制就是全屏。
                    val before = decorContainerSize(decor, ctx)
                    applyFullBleedSafely(activity, decor, ctx)
                    everApplied = true
                    val after = decorContainerSize(decor, ctx)
                    ctx.log(
                        Log.INFO,
                        "pre-draw 铺满：容器 $before → $after（root=${decor.width}x${decor.height}）",
                    )
                    if (isDecorGeometricallyFull(decor, ctx)) {
                        detach()
                        ctx.log(Log.INFO, "pre-draw 铺满完成，监听已摘除")
                        return true
                    }
                    return false
                }

                private fun detach() {
                    runCatching { vto.removeOnPreDrawListener(this) }
                    decor.setTag(key, null)
                }
            })
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "挂 pre-draw 铺满失败（回退到 post 时机）", t)
        }
    }

    /** 铺满 + 窗口全屏化，异常不外抛（所有调用点都不该因为铺满失败而影响识屏）。 */
    private fun applyFullBleedSafely(
        activity: android.app.Activity,
        decor: View,
        ctx: HookContext,
    ) {
        try {
            makeWindowFullBleed(activity, decor, ctx)
            expandScreenshotCard(decor, ctx)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "打开即铺满失败（保持宿主原样）", t)
        }
    }

    /**
     * 从 decor 视角判断「截图容器是否已按全屏策略就位」。
     * 找不到容器时返回 true（没有可干预的对象，无需再插手）。
     */
    private fun isDecorFullBleed(root: View, ctx: HookContext): Boolean {
        return try {
            val id = root.resources.getIdentifier(SHOT_CONTAINER_ID, "id", HOST_PKG)
            if (id == 0) return true
            val v = root.findViewById<View>(id) ?: return true
            isFullBleedNow(v)
        } catch (t: Throwable) {
            true
        }
    }

    /**
     * 容器实测尺寸的字符串（诊断日志用；找不到时返回 `"?"`）。
     * 不用来判定成功与否 —— 判定看 [isDecorGeometricallyFull]。
     */
    private fun decorContainerSize(root: View, ctx: HookContext): String {
        return try {
            val id = root.resources.getIdentifier(SHOT_CONTAINER_ID, "id", HOST_PKG)
            if (id == 0) return "?"
            val v = root.findViewById<View>(id) ?: return "?"
            "${v.width}x${v.height}"
        } catch (t: Throwable) {
            "?"
        }
    }

    /**
     * 容器是否**实测**占满整屏（几何收敛，不是只看布局参数）。
     *
     * 为什么要跟 [isDecorFullBleed] 分开：布局参数写对了不代表宿主这一轮就按
     * 它摆放 —— ConstraintLayout 仍可能按自己的约束求解出 2520。
     * 「动画开始跳一下小卡」就是因为只看参数就放行（详见 [armPreDrawFullBleed]）。
     *
     * 容器找不到时返回 true（没对象可干预）。
     * 允许 1px 误差（某些 ROM 会因圆角/inset 少一像素，不该判为失败）。
     */
    private fun isDecorGeometricallyFull(root: View, ctx: HookContext): Boolean {
        return try {
            val id = root.resources.getIdentifier(SHOT_CONTAINER_ID, "id", HOST_PKG)
            if (id == 0) return true
            val v = root.findViewById<View>(id) ?: return true
            val dh = root.height - v.height
            val dw = root.width - v.width
            // 高度是主要矛盾（底部工具条吃掉的），宽度顺带校验
            dh <= GEOMETRY_TOLERANCE_PX && dw <= GEOMETRY_TOLERANCE_PX
        } catch (t: Throwable) {
            true
        }
    }

    /**
     * 按名字 hook 识屏 Activity 的生命周期方法（沿父类链找**声明处**）。
     *
     * 为什么必须沿父类链找：直接 hook 继承来的 `Activity.onResume` 拿到的
     * declaring class 是 `android.app.Activity`，会命中进程里**所有** Activity。
     *
     * ★ 参数匹配不能写死「零参数」：`onResume()` / `onStart()` 是零参数，但
     * `onCreate(Bundle)` / `onWindowFocusChanged(Z)` 带参数。第一版按
     * `parameterTypes.isEmpty()` 找 `onCreate` → 永远找不到（实测日志
     * 「找不到 onCreate 声明，跳过该铺满挂点」）。现在按方法名 + 允许的参数集匹配。
     */
    private fun hookActivityLifecycle(
        ctx: HookContext,
        actCls: Class<*>,
        methodName: String,
        missed: MutableList<String>,
        onAfter: (android.app.Activity, View) -> Unit,
    ): Int {
        // 只接受这几种签名，避免误命中同名但语义不同的方法
        val okArity = when (methodName) {
            "onCreate" -> setOf(1) // (Bundle)
            "onWindowFocusChanged" -> setOf(1) // (boolean)
            else -> setOf(0)
        }
        var cls: Class<*>? = actCls
        var m: java.lang.reflect.Method? = null
        while (cls != null) {
            m = cls.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size in okArity
            }
            if (m != null) break
            cls = cls.superclass
        }
        if (m == null) {
            ctx.log(Log.WARN, "找不到 $methodName 声明，跳过该铺满挂点")
            missed += "$ACT_CLS.$methodName"
            return 0
        }
        return try {
            track(
                ctx.module.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val activity = chain.thisObject as? android.app.Activity
                        val decor = activity?.window?.decorView
                        if (activity != null && decor != null) onAfter(activity, decor)
                    } catch (t: Throwable) {
                        ctx.log(Log.WARN, "$methodName 铺满回调异常", t)
                    }
                    result
                },
            )
            ctx.log(Log.INFO, "已挂上识屏 $methodName：打开即铺满")
            1
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "hook 识屏 $methodName 失败", t)
            missed += "$ACT_CLS.$methodName"
            0
        }
    }

    /** hook 点 2：气泡横排 + 结果就绪时的铺满兜底与动画残留清理（幂等）。 */
    private fun installHorizontalLayout(ctx: HookContext, missed: MutableList<String>): Int {
        val rvCls = try {
            Class.forName(RV_CLS, false, ctx.classLoader)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "class not found: $RV_CLS，跳过", t)
            missed += RV_CLS
            return 0
        }

        // setLayoutManager 是 RecyclerView 的继承公开方法 —— 拿到的 Method 的
        // declaring class 是 RecyclerView，会命中进程里所有 RecyclerView。
        // **必须**在回调里按 thisObject 的实际类名过滤（调用频率 = 每个 RecyclerView
        // 初始化一次，过滤成本可忽略）。
        val setLm = rvCls.methods.firstOrNull {
            it.name == "setLayoutManager" && it.parameterTypes.size == 1
        }
        if (setLm == null) {
            ctx.log(Log.WARN, "找不到 setLayoutManager(LayoutManager)，可能宿主已改版")
            missed += "$RV_CLS.setLayoutManager"
            return 0
        }

        val lmCls = try {
            Class.forName(LM_CLS, false, ctx.classLoader)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "class not found: $LM_CLS，跳过", t)
            missed += LM_CLS
            return 0
        }

        return try {
            track(
                ctx.module.hook(setLm).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val lm = chain.getArg(0) ?: return@intercept result
                        val rv = chain.thisObject as? View ?: return@intercept result
                        // 过滤：只碰识屏页的气泡列表，别的 RecyclerView 一律不碰
                        if (rv.javaClass.name != RV_CLS) return@intercept result
                        if (!lmCls.isInstance(lm)) {
                            // presenter 用了自定义 LayoutManager：不动它，只记一条
                            ctx.log(Log.WARN, "气泡列表的 LayoutManager 不是 LinearLayoutManager（${lm.javaClass.name}），跳过横排")
                            return@intercept result
                        }
                        // 缓存 appContext：常驻气泡的剪贴板/Toast 要用
                        if (appContext == null) {
                            appContext = rv.context.applicationContext
                        }
                        // 原地改方向：不换实例，presenter 持有的引用继续有效
                        lm.javaClass.getMethod("setOrientation", Int::class.javaPrimitiveType)
                            .invoke(lm, HORIZONTAL)
                        // 宽度撑满与铺满兜底都要 attach 之后再做
                        rv.post {
                            widen(rv)
                            val activity = findActivity(rv.context)
                            val root = rv.rootView
                            if (pick(ctx.prefs, "full_bleed")) {
                                if (activity != null) makeWindowFullBleed(activity, root, ctx)
                                expandScreenshotCard(root, ctx)
                                hideScanAnimation(root, ctx)
                            }
                        }
                        val n = patched.incrementAndGet()
                        if (n <= 3 || n % 20 == 0) {
                            ctx.log(Log.INFO, "识屏气泡已改横排（第 $n 次）")
                        }
                    } catch (t: Throwable) {
                        // 改样式失败绝不能影响识屏本身 —— 宁可保持原样
                        ctx.log(Log.WARN, "横排/铺满改造失败（保持宿主原样）", t)
                    }
                    result
                },
            )
            ctx.log(Log.INFO, "已挂上 EntityBubbleRV.setLayoutManager：横排 + 铺满兜底")
            1
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "hook 识屏气泡列表失败", t)
            missed += "$RV_CLS.setLayoutManager"
            0
        }
    }

    /** hook 点 3：气泡紧凑化（保留文字；图标缩小 + 字号缩小 + 内边距减半）。 */
    private fun installCompactBubbles(ctx: HookContext, missed: MutableList<String>): Int {
        val initEntity = locateMethod(ctx, RecognitionClassLocator.VIEW_HOLDER, missed) ?: return 0
        return try {
            track(
                ctx.module.hook(initEntity).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        // androidx.recyclerview 不在模块编译类路径，ViewHolder 用反射拿 itemView
                        val item = findItemView(chain.thisObject) ?: return@intercept result
                        compactBubbleItem(item)
                    } catch (t: Throwable) {
                        ctx.log(Log.WARN, "气泡缩小失败（保持原样）", t)
                    }
                    result
                },
            )
            ctx.log(Log.INFO, "已挂上 EntityViewHolder.initEntity：气泡紧凑化（带文字）")
            1
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "hook 气泡紧凑化失败", t)
            missed += RecognitionClassLocator.VIEW_HOLDER.label
            0
        }
    }

    /**
     * 单个气泡 item 紧凑化：图标压到 [ICON_SIZE_PX]、文字压到 [TEXT_SIZE_DIP]、
     * 内边距减半。文字**保留**（用户明确要求恢复文字说明）。
     */
    private fun compactBubbleItem(item: View) {
        val res = item.resources
        val iconId = res.getIdentifier("icon", "id", HOST_PKG)
        if (iconId != 0) {
            val icon = item.findViewById<android.widget.ImageView>(iconId)
            icon?.let {
                it.layoutParams?.let { lp ->
                    if (lp.width != ICON_SIZE_PX || lp.height != ICON_SIZE_PX) {
                        lp.width = ICON_SIZE_PX
                        lp.height = ICON_SIZE_PX
                        it.layoutParams = lp
                    }
                }
            }
        }
        val contentId = res.getIdentifier("content", "id", HOST_PKG)
        if (contentId != 0) {
            val tv = item.findViewById<android.widget.TextView>(contentId)
            tv?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP)
        }
        // 内边距减半（手感值；对 0 边无影响）
        item.setPadding(
            item.paddingLeft / 2,
            item.paddingTop / 2,
            item.paddingRight / 2,
            item.paddingBottom / 2,
        )
    }

    /** hook 点 4：常驻气泡（问小爱/翻译/提取文字/分享）注入 + 标记点击拦截。 */
    private fun installPersistentBubbles(ctx: HookContext, missed: MutableList<String>): Int {
        var n = 0

        // 4a. createHybImageStructureResultFrom after：把常驻项插到 UI 状态的
        // TipBubble 列表头部。全文从 arg0.getTotalText() 拿（baksmali 取证：
        // TEXT 实体不在 entities 列表里，全文在 xn0.e 上，按实体找文本是死路）。
        val create = locateMethod(ctx, RecognitionClassLocator.STRUCTURE, missed)
        if (create != null) {
            try {
                track(
                    ctx.module.hook(create).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val state = result
                            if (state != null) {
                                val entities = chain.getArg(0)
                                val totalText = entities?.javaClass?.methods
                                    ?.firstOrNull { it.name == "getTotalText" && it.parameterTypes.isEmpty() }
                                    ?.invoke(entities) as? String ?: ""
                                injectPersistent(totalText, state, ctx)
                            }
                        } catch (t: Throwable) {
                            ctx.log(Log.WARN, "常驻气泡注入失败（保持云端原样）", t)
                        }
                        result
                    },
                )
                n++
                ctx.log(Log.INFO, "已挂上 ${create.declaringClass.name}.${create.name}：常驻项注入")
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "hook ${create.name} 失败", t)
                missed += RecognitionClassLocator.STRUCTURE.label
            }
        }

        // 4b. clickBubble 拦截：识别我们的标记气泡（提取文字/分享），
        // UnSupportAction.execute 是 no-op，这里自己完成动作后不再广播
        val click = locateMethod(ctx, RecognitionClassLocator.CLICK_DISPATCH, missed) ?: return n
        return try {
            track(
                ctx.module.hook(click).intercept { chain ->
                    val tip = chain.getArg(0)
                    if (tip != null && handleMarkedClick(tip)) {
                        return@intercept null
                    }
                    chain.proceed()
                },
            )
            n++
            if (n > 0) ctx.log(Log.INFO, "已挂上气泡工厂 clickBubble：提取文字/分享动作")
            n
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "hook clickBubble 失败", t)
            missed += RecognitionClassLocator.CLICK_DISPATCH.label
            n
        }
    }

    /**
     * 往识屏 UI 状态（xr1.d）的 TipBubble 列表头部插入常驻项
     * （参考 华为智慧识屏/魅族 Aicy/OPPO 小布识屏 的常驻集，按宿主已有能力取舍）：
     *  - 「问小爱」：宿主 ImageCustomTextQueryAction(TotalImage) → queryEvent 全屏问，
     *    原生链路零自研，**不依赖识别文本**；
     *  - 「翻译」：宿主 TextTranslateAction + setSelectedText(全文)；
     *  - 「提取文字」：UnSupportAction no-op + COPY_PREFIX 标记，clickBubble 拦截写剪贴板；
     *  - 「分享」：UnSupportAction no-op + SHARE_PREFIX 标记，clickBubble 拦截起系统分享。
     * 全文来自 xn0.e.getTotalText()（baksmali 取证）；没有文本时只注入「问小爱」。
     */
    private fun injectPersistent(totalText: String?, state: Any, ctx: HookContext) {
        val cl = state.javaClass.classLoader ?: return

        // 找 xr1.d 上装 TipBubble 的列表字段：优先按泛型签名（List<TipBubble>），
        // 混淆后签名可能被剥 —— 回退到按元素类型探测
        val tipField = state.javaClass.declaredFields.firstOrNull { f ->
            f.type == List::class.java && (f.getGenericType() as? java.lang.reflect.ParameterizedType)
                ?.actualTypeArguments?.firstOrNull()?.typeName?.endsWith("TipBubble") == true
        } ?: state.javaClass.declaredFields.filter { it.type == List::class.java }.firstOrNull { f ->
            f.isAccessible = true
            (f.get(state) as? List<*>)?.firstOrNull()?.javaClass?.name?.endsWith("TipBubble") == true
        }
        if (tipField == null) {
            Log.w(TAG, "UI 状态上找不到 TipBubble 列表字段，跳过常驻项注入")
            return
        }
        tipField.isAccessible = true
        val current = tipField.get(state) as? MutableList<Any?>
        val list: MutableList<Any?> = current ?: ArrayList(
            (tipField.get(state) as? List<Any?>) ?: emptyList(),
        )

        val text = totalText?.trim().orEmpty()
        val modelPkg = "com.xiaomi.voiceassistant.screenrecognition.model"
        val bubbleCls = Class.forName("$modelPkg.Bubble", false, cl)
        val tipCls = Class.forName("$modelPkg.TipBubble", false, cl)
        val bubbleCtor = bubbleCls.constructors.first { it.parameterTypes.size == 5 }
        val tipCtor = tipCls.constructors.first { it.parameterTypes.size == 3 }

        val res = appContext?.resources
        fun iconId(name: String): Int? =
            res?.getIdentifier(name, "drawable", HOST_PKG)?.takeIf { it != 0 }

        val injected = mutableListOf<Any>()
        fun addBubble(type: String, label: String, icon: String?, action: Any) {
            val bubble = bubbleCtor.newInstance(null, null, icon?.let { iconId(it) }, label, action)
            injected.add(tipCtor.newInstance(null, type, bubble))
        }

        val queryCls = Class.forName(
            "com.xiaomi.voiceassistant.screenrecognition.bubble.ImageCustomTextQueryAction", false, cl,
        )
        val picRectCls = Class.forName(
            "com.xiaomi.voiceassistant.screenrecognition.bubble.PicRectType", false, cl,
        )
        val totalImage = java.lang.Enum.valueOf(
            @Suppress("UNCHECKED_CAST") (picRectCls as Class<out Enum<*>>), "TotalImage",
        )
        val queryAction = queryCls.constructors.first { it.parameterTypes.size == 2 }
            .newInstance(false, totalImage)
        addBubble("fp_query", LABEL_QUERY, ICON_QUERY, queryAction)

        if (text.isNotBlank()) {
            // 翻译：宿主自带 TextTranslateAction，setSelectedText(全文) 后走原生翻译页
            val ttaCls = Class.forName(
                "com.xiaomi.voiceassistant.screenrecognition.bubble.TextTranslateAction", false, cl,
            )
            val translateAction = ttaCls.constructors.first { it.parameterTypes.size == 1 }
                .newInstance(false)
            ttaCls.methods.first { it.name == "setSelectedText" }.invoke(translateAction, text)
            if (pick(ctx.prefs, "translate")) addBubble("fp_translate", LABEL_TRANSLATE, ICON_TRANSLATE, translateAction)

            // 提取文字 / 分享：UnSupportAction（execute 是 no-op），content 带标记前缀
            val unsupCls = Class.forName(
                "com.xiaomi.voiceassistant.screenrecognition.bubble.UnSupportAction", false, cl,
            )
            val unsupCtor = unsupCls.constructors.first { it.parameterTypes.size == 3 }
            if (pick(ctx.prefs, "copy_text")) addBubble(
                "fp_copy_text", LABEL_COPY, ICON_COPY,
                unsupCtor.newInstance(false, COPY_PREFIX + text, null),
            )
            if (pick(ctx.prefs, "share_text")) addBubble(
                "fp_share", LABEL_SHARE, ICON_SHARE,
                unsupCtor.newInstance(false, SHARE_PREFIX + text, null),
            )
        } else {
            Log.i(TAG, "识别全文为空，仅注入问小爱常驻项")
        }

        // 整段插到头部，保持 injected 的「问小爱、翻译、复制、分享」顺序
        list.addAll(0, injected)
        if (current == null) tipField.set(state, list)
        Log.i(TAG, "常驻气泡已注入 ${injected.size} 个（识别全文 ${text.length} 字）")
    }

    /**
     * clickBubble 拦截：识别我们注入的标记气泡（提取文字/分享）——
     * UnSupportAction.execute 是 no-op，这里按 content 前缀自己完成动作后
     * 不再广播（原生链路完全不感知）。返回 true 表示已拦截。
     */
    private fun handleMarkedClick(tip: Any): Boolean {
        return try {
            val bubble = tip.javaClass.methods.first { it.name == "getBubble" }
                .invoke(tip) ?: return false
            val action = bubble.javaClass.methods.first { it.name == "getClickAction" }
                .invoke(bubble) ?: return false
            if (!action.javaClass.name.endsWith("UnSupportAction")) return false
            val content = action.javaClass.methods.first { it.name == "getContent" }
                .invoke(action) as? String ?: return false
            val context = appContext ?: return false
            when {
                content.startsWith(COPY_PREFIX) -> {
                    val text = content.removePrefix(COPY_PREFIX)
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager ?: return false
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(LABEL_COPY, text))
                    android.widget.Toast.makeText(context, "已复制识别文字", android.widget.Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "识别文字已复制（${text.length} 字）")
                    true
                }
                content.startsWith(SHARE_PREFIX) -> {
                    val text = content.removePrefix(SHARE_PREFIX)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, text)
                    val chooser = android.content.Intent.createChooser(intent, LABEL_SHARE)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                    Log.i(TAG, "识别文字已起分享（${text.length} 字）")
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "标记气泡动作执行失败", t)
            false
        }
    }

    /**
     * hook 点 5：外部快捷方式。在主进程注册一个运行时广播接收器
     * （RECEIVER_EXPORTED + token 校验），收到 [TRIGGER_ACTION] 后在主线程反射调
     * 宿主识屏入口的 startScreenRecognition 全空重载。
     *
     * 为什么不走 Activity intent：识屏入口不是导出组件，helper 里的校验链
     * （锁屏重试 / DKT / 连续翻译 / 网络）与 Toast 提示全部要复用 —— 直接调
     * helper 的全空参数重载是最小改动；截屏由 helper 自己完成（bitmap=null）。
     */
    private fun installExternalTrigger(ctx: HookContext, missed: MutableList<String>): Int {
        // 只在主进程注册（作用域分发已按进程名筛过，这里再挡一层防漂移）
        if (ctx.processName != HOST_PKG) {
            ctx.log(Log.WARN, "外部触发：非主进程（${ctx.processName}），跳过")
            return 0
        }
        if (triggerReceiver != null) return 1 // 幂等：热重载重入不重复注册

        // 安装回调可能早于 Application 挂上 —— 能拿到宿主 context 就直接注册
        val existing = appContext ?: hostAppContext()
        if (existing != null) {
            registerTriggerReceiver(ctx, existing)
            ctx.log(
                Log.INFO,
                "外部快捷方式已就绪：adb shell am broadcast -a $TRIGGER_ACTION --es $TRIGGER_TOKEN_KEY $TRIGGER_TOKEN -p $HOST_PKG",
            )
            return 1
        }
        // 兜底：挂 Application.attach，宿主 Application 一拿到 Context 就注册
        // （handle 走统一 unhook；receiver 注册动作幂等，attach 只会走一次）
        return try {
            val attach = Class.forName("android.app.Application", false, ctx.classLoader)
                .getDeclaredMethod("attach", Context::class.java)
            track(
                ctx.module.hook(attach).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        if (triggerReceiver == null) {
                            val base = chain.getArg(0) as? Context ?: return@intercept result
                            registerTriggerReceiver(ctx, base.applicationContext ?: base)
                            ctx.log(Log.INFO, "外部快捷方式已就绪（attach 兜底注册）")
                        }
                    } catch (t: Throwable) {
                        ctx.log(Log.WARN, "外部快捷方式兜底注册失败", t)
                    }
                    result
                },
            )
            1
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "外部快捷方式安装失败（不影响其它子能力）", t)
            missed += "$TRIGGER_ACTION receiver"
            0
        }
    }

    /** 注册触发接收器（幂等）。EXPORTED 是刻意的：发送方在宿主进程之外。 */
    private fun registerTriggerReceiver(ctx: HookContext, context: Context) {
        if (triggerReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    if (intent.action != TRIGGER_ACTION) return
                    if (intent.getStringExtra(TRIGGER_TOKEN_KEY) != TRIGGER_TOKEN) {
                        Log.w(TAG, "识屏触发广播 token 不匹配，忽略")
                        return
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        startRecognitionViaHelper()
                    } else {
                        Handler(Looper.getMainLooper()).post { startRecognitionViaHelper() }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "识屏触发广播处理失败", t)
                }
            }
        }
        val filter = IntentFilter(TRIGGER_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        triggerReceiver = receiver
        ctx.log(Log.INFO, "识屏触发接收器已注册（action=$TRIGGER_ACTION）")
    }

    /**
     * 反射调 ScreenRecognitionHelper（混淆名 db）单例的 startScreenRecognition
     * 全空重载：校验链（锁屏 / DKT / 连续翻译 / 网络）与 Toast 提示全部走宿主
     * 原生；bitmap=null 时 helper 自己截屏。失败一律记日志，不抛出。
     */
    private fun startRecognitionViaHelper() {
        try {
            val context = hostAppContext() ?: return
            val dbCls = Class.forName(HELPER_CLS, false, context.classLoader)
            // 单例字段：static 且类型就是 helper 自己（混淆名 f81138a 会漂移，按类型找）
            val instance = dbCls.declaredFields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == dbCls }
                .firstOrNull()?.apply { isAccessible = true }?.get(null)
            if (instance == null) {
                Log.w(TAG, "识屏入口单例（$HELPER_CLS 静态自类型字段）找不到，宿主可能已改版")
                return
            }
            // 4 参重载（FloatMultiInputContainer 同款全空调用）；查不到退化到 5 参
            val m = dbCls.methods.firstOrNull {
                it.name == "startScreenRecognition" && it.parameterTypes.size == 4
            } ?: dbCls.methods.firstOrNull {
                it.name == "startScreenRecognition" && it.parameterTypes.size == 5
            }
            if (m == null) {
                Log.w(TAG, "startScreenRecognition 方法找不到（宿主可能已改版）")
                return
            }
            val args = arrayOfNulls<Any?>(m.parameterTypes.size)
            val ok = m.invoke(instance, *args) as? Boolean == true
            Log.i(
                TAG,
                "外部触发识屏：startScreenRecognition 返回 $ok" +
                    if (ok) "" else "（false=锁屏/离线/翻译中，宿主已 Toast 提示）",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "外部触发识屏失败", t)
        }
    }

    /**
     * 宿主 Application context（`ActivityThread.currentApplication()` 反射，
     * 与 SystemUiEnv / LauncherDrawerObserver 同一招；进程未起好时为 null）。
     */
    private fun hostAppContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    /**
     * 反射取 ViewHolder.itemView：RecyclerView.ViewHolder 是宿主类加载器里的类，
     * 模块编译期引用不到 —— 沿父类链找名为 itemView 的字段。
     */
    private fun findItemView(holder: Any?): View? {
        var cls: Class<*>? = holder?.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("itemView")
                f.isAccessible = true
                return f.get(holder) as? View
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    /**
     * 气泡行撑满可用宽度：竖堆的 wrap 宽度在横排下会挤在左上角。
     */
    private fun widen(rv: View) {
        val lp = rv.layoutParams ?: return
        if (lp.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) return
        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        rv.requestLayout()
    }

    /**
     * 截图区域按**原始物理屏幕坐标**铺满（0,0 → 1220,2656），全程不留小卡片。
     *
     * ★ 设计定调（2026-09-18，用户拍板）：
     * **一切按原始全屏坐标做，不要在识屏的任何阶段跳出小窗口。**
     * 底边信息量少，不需要识别，也**不允许**把内容整体下移去迁就它。
     *
     * 三件套：
     *  1. `cv_screen_recognition_border`（圆角描边 View）→ GONE（卡感的「线」）；
     *  2. `cl_answer_xiaoai`（底部工具条）→ GONE —— **这是关键**，
     *     截图容器的底部约束是 `bottom_toTopOf = cl_answer_xiaoai`，
     *     工具条一 GONE，锚点消失，容器**自然**回到整屏高，不需要任何补偿；
     *  3. `fl_screen_recognition_container`（截图父容器）→ MATCH_PARENT + 清 margin。
     *
     * ⚠️ 曾经走过的弯路（别再走）：
     *  - 先用「负 bottomMargin 抵消工具条占位」把容器**往下拉长**。
     *    方向是错的 —— 那是「承认容器该缩着，只想把它拽长」，而且实测会把容器
     *    拉到 **2867**（超出屏幕 211px），因为工具条 visibility 在页面打开过程中
     *    会闪变（XML 默认 INVISIBLE），补偿值跟着抖。
     *  - 正确做法是让**约束本身失效**：把工具条 GONE 掉，容器就是整屏。
     *    这条路不依赖工具条当前是否可见、也不依赖它多高，天然稳定。
     *
     * 坐标系为什么必须是整屏：容器 2520 时，里面的 `screen_shot`
     * （MATCH×MATCH + FIT_CENTER）会把 2656 高的冻结截图**缩到 0.9488 并居中**，
     * 而 `mask_view` 上的高亮框用的是**绝对屏幕坐标** → 两边错位。
     * 容器 = 整屏后 FIT_CENTER 不缩放，两边坐标系 1:1 重合，位置就对了。
     */
    private fun expandScreenshotCard(rootView: View, ctx: HookContext) {
        try {
            val root = rootView.rootView
            val res = rootView.resources
            // 1. 描边：整个卡感的「线」，隐藏即可
            hideById(root, res, BORDER_ID, "识屏描边", ctx)
            // 保留原退出与问小爱入口；只解除截图容器的底边锚点。
            root.findViewById<View>(res.getIdentifier(ANSWER_BAR_ID, "id", HOST_PKG))?.bringToFront()
            // 3. 截图容器：撑满父布局 + 清 margins
            val flId = res.getIdentifier(SHOT_CONTAINER_ID, "id", HOST_PKG)
            if (flId == 0) {
                ctx.log(Log.WARN, "截图容器 id 解析不到: $SHOT_CONTAINER_ID")
                return
            }
            val container = root.findViewById<View>(flId) ?: return
            val rootW = root.width
            val rootH = root.height
            val full = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            if (rootW > 0 && rootH > 0) {
                val lp = container.layoutParams ?: return
                val changed = lp.width != full || lp.height != full ||
                    !hasNoMargins(container)
                lp.width = full
                lp.height = full
                (lp as? android.view.ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
                container.layoutParams = lp
                detachConstraints(container, ctx)
                watchContainerSize(container, ctx)
                if (changed) {
                    ctx.log(
                        Log.INFO,
                        "识屏截图容器已铺满整屏（root=${rootW}x$rootH " +
                            "实测=${container.width}x${container.height}）",
                    )
                }
            } else {
                // 尺寸还没出来（onCreate 阶段）：交给 pre-draw / 布局监听在首帧前补。
                // 这里**不做任何补偿性猜测** —— 猜出来的差值会抖（见上面弯路记录）。
                watchContainerSize(container, ctx)
            }
            // 4. 蓝色渐变衬底：原卡片下部的装饰层，铺满后缩在左下角，
            //    动画一停就露出来（= 用户看到的「左下角彩色块」）→ GONE
            hideById(root, res, BLUE_BG_ID, "蓝色渐变衬底", ctx)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "截图按全屏坐标铺满失败（保持宿主原样）", t)
        }
    }

    /** 按资源名把视图设成 GONE（幂等；id 解析不到只记日志，不抛）。 */
    private fun hideById(
        root: View,
        res: android.content.res.Resources,
        name: String,
        label: String,
        ctx: HookContext,
    ) {
        val id = res.getIdentifier(name, "id", HOST_PKG)
        if (id == 0) {
            ctx.log(Log.WARN, "$label id 解析不到: $name")
            return
        }
        root.findViewById<View>(id)?.let {
            if (it.visibility != View.GONE) {
                it.visibility = View.GONE
                ctx.log(Log.INFO, "已隐藏$label: $name")
            }
        }
    }

    /** 四周 margin 是否都已清零（判定「策略是否已生效」，不看像素）。 */
    private fun hasNoMargins(v: View): Boolean {
        val mlp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return true
        return mlp.leftMargin == 0 && mlp.topMargin == 0 &&
            mlp.rightMargin == 0 && mlp.bottomMargin == 0
    }

    /**
     * hook `View.setVisibility`，把宿主**反复重新显示**的装饰层按回去。
     *
     * 为什么需要（2026-09-18，用户报「某个功能选完之后返回仍然有动画残留」）：
     * [expandScreenshotCard] 里的 GONE 是**一次性**的 —— 只在铺满那几趟执行。
     * 但实测（模块日志统计）`iv_blue_bg` 的「已隐藏」打印了 **103 次**：
     * 宿主在「选完功能返回 / 结果刷新 / 动画阶段」会不停把装饰层重新显示出来，
     * 我们只在少数时机按回去 → 中间那些窗口就是用户看到的残留。
     *
     * 修法：**一次性 hook `setVisibility`**，只要宿主对目标 id 设 `VISIBLE`，
     * 当场改写成 `GONE` 并返回（不调用原方法）。这样装饰层再也显示不出来，
     * 也不需要轮询、不需要在每次铺满时重复处理。
     *
     * 为什么不用 `addOnLayoutChangeListener` 轮询：轮询只在布局发生时被叫到，
     * 而 `setVisibility(VISIBLE)` **不一定**伴随尺寸变化（`INVISIBLE`↔`VISIBLE`
     * 尺寸一样）→ 轮询会漏。hook 是同步的，漏不掉。
     *
     * ★ 「拿不到 Context 就不装」是错的（2026-09-18 实测踩到）：
     * 首次安装这个 hook 时 `ActivityThread.currentApplication()` 还是 null
     * （宿主刚 fork 出来），于是整条路径降级、什么都不做，日志
     * 「拿不到宿主 Context，装饰层抑制降级为一次性 GONE」。
     * → 改成**懒解析**：hook 无条件装上，第一次真正被回调时再解析 id；
     *   在 id 解析出来之前一律 `proceed()`（行为与未装模块一致），
     *   解析出来之后就永久生效。
     */
    private fun installDecorationSuppressor(ctx: HookContext, missed: MutableList<String>): Int {
        val m = try {
            View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "找不到 View.setVisibility，装饰层抑制降级为一次性 GONE", t)
            missed += "View.setVisibility"
            return 0
        }
        return try {
            track(
                ctx.module.hook(m).intercept { chain ->
                    val v = chain.thisObject as? View
                    val want = chain.getArg(0) as? Int
                    if (v != null && want != null && want != View.GONE) {
                        val label = suppressLabelOf(v, ctx)
                        if (label != null) {
                            // 直接吞掉：不调用原方法，等价于「宿主永远显示不出来」
                            ctx.log(Log.INFO, "已拦下宿主要显示的$label（id=${v.id}）")
                            return@intercept null
                        }
                    }
                    chain.proceed()
                },
            )
            ctx.log(Log.INFO, "已挂上装饰层抑制（setVisibility 过滤，id 懒解析）")
            1
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "hook View.setVisibility 失败（降级为一次性 GONE）", t)
            missed += "View.setVisibility"
            0
        }
    }

    /**
     * 要抑制的装饰层：`资源 id 数值 → 中文标签`。**懒解析 + 缓存**。
     *
     * 为什么懒解析：见 [installDecorationSuppressor] 的注释 —— 安装时机太早，
     * `ActivityThread.currentApplication()` 还是 null。
     *
     * 多线程：`setVisibility` 会被渲染线程/主线程同时调到，故用 `@Volatile` +
     * 双检锁。解析失败不缓存（返回 null，下次再试），避免把「暂时拿不到」固化。
     */
    @Volatile
    private var suppressIdCache: Map<Int, String>? = null

    private fun suppressLabelOf(v: View, ctx: HookContext): String? {
        val id = v.id
        if (id == View.NO_ID) return null
        suppressIdCache?.let { return it[id] }
        val appCtx = hostAppContext() ?: return null
        synchronized(this) {
            suppressIdCache?.let { return it[id] }
            val res = try {
                appCtx.resources
            } catch (t: Throwable) {
                return null
            }
            val map = mutableMapOf<Int, String>()
            for ((name, label) in listOf(BLUE_BG_ID to "蓝色渐变衬底")) {
                val rid = try {
                    res.getIdentifier(name, "id", HOST_PKG)
                } catch (t: Throwable) {
                    0
                }
                if (rid != 0) map[rid] = label
            }
            if (map.isEmpty()) return null
            suppressIdCache = map
            ctx.log(Log.INFO, "装饰层抑制目标已解析: ${map.entries.joinToString { "${it.value}=${it.key}" }}")
            return map[id]
        }
    }

    /**
     * 把视图在 ConstraintLayout 里的**四边约束真正断开**（不是改 layoutParams）。
     *
     * 为什么必须这么做（2026-09-18 取证，aapt2 dump `res/69n.xml`）：
     * `fl_screen_recognition_container` 在 `activity_screen_recognition_container`
     * 里的声明是
     * ```
     * width=0dp  height=0dp
     * marginTop / marginBottom
     * constraintTop_toTopOf=parent, Start_toStartOf=parent, End_toEndOf=parent
     * constraintBottom_toTopOf=0x7f0a02df（工具条）   ← 这是「卡片高度」的来源
     * ```
     * `0dp` 在 ConstraintLayout 里是「**约束尺寸**」，它的实际大小由约束求解得出。
     * 只把 `LayoutParams.width/height` 写成 MATCH_PARENT 是**无效的**：
     * ConstraintLayout 下一轮 `requestLayout()` 会用 ConstraintSet 里的约束重算，
     * 容器被拉回「工具条上方 + 上下 margin」的卡片形态。宿主每出一批识别结果都会
     * 重建布局 → 「卡片 → 全屏」反复跳变（用户报的「先跳出小界面再回到全屏」）。
     *
     * 断开方式（迭代过两版，第二版才是对的）：
     *
     *  - **第一版（失败）**：`Class.forName("androidx.constraintlayout.widget.ConstraintSet")`
     *    + 反射 `connect(...)`/`applyTo(...)`。实测 `ClassNotFoundException` ——
     *    无论用 `ctx.classLoader` 还是 `parent.javaClass.classLoader` 都加载不到
     *    （ConstraintSet 所在的库不在 app 的 loader 可达路径上）。
     *  - **第二版（采用）**：ConstraintLayout 的约束**就存在 `LayoutParams` 的
     *    public 字段上**（反编译核实：`topToTop` / `bottomToBottom` /
     *    `startToStart` / `endToEnd` / `baselineToBaseline` / `*To*` 全是 `public int`）。
     *    容器在 ConstraintLayout 里的 LayoutParams 就是这个类型 ——
     *    直接把这些字段写成 **UNSET(-1)**，再 `container.layoutParams = lp` 即可。
     *    不依赖任何类加载器，也不需要额外类。
     *
     * 还要清 `matchConstraintMax/MinWidth|Height` 与 `matchConstraintPercent*`：
     * 它们只在 `layout_width/height = 0dp`（约束尺寸）时生效，会给出尺寸上限。
     * 我们已把宽高改成 MATCH_PARENT，理论上它们不再起作用，但一并清零更稳。
     *
     * 约束断掉后容器是 MATCH×MATCH、父容器也已是全屏 → 自然铺满。
     * 幂等：字段已是 UNSET 时重复执行无副作用。
     */
    private fun detachConstraints(container: View, ctx: HookContext) {
        try {
            val lp = container.layoutParams ?: return
            // 非 ConstraintLayout 的 LayoutParams 没有这些字段 —— 逐字段 try 即可，
            // 一个也写不进去就等于「不是约束布局」，无事发生。
            var touched = 0
            // 四边 + 基线：全部 UNSET（-1 = 无约束）
            for (f in CONSTRAINT_ANCHOR_FIELDS) {
                if (setIntField(lp, f, CONSTRAINT_UNSET, ctx)) touched++
            }
            // 0dp 约束尺寸的上限/百分比：清零
            for (f in CONSTRAINT_SIZE_FIELDS) {
                if (setIntField(lp, f, 0, ctx)) touched++
            }
            if (touched == 0) {
                // 不是 ConstraintLayout 的 LayoutParams（或字段名在宿主版本里变了）——
                // 不改 layoutParams 也能靠 MATCH_PARENT 生效，这里只记一条便于排查
                val p = container.parent
                ctx.log(
                    Log.WARN,
                    "容器约束断不开：lp=${lp.javaClass.name} " +
                        "parent=${p?.javaClass?.name} " +
                        "parentLp=${(p as? View)?.layoutParams?.javaClass?.name}",
                )
                return
            }
            container.layoutParams = lp
            container.requestLayout()
        } catch (t: Throwable) {
            // 断约束失败不能让识屏本身出问题 —— 保持现状（回到「反复覆盖」的旧行为）
            ctx.log(Log.WARN, "断开截图容器约束失败（回退到改 layoutParams 的老办法）", t)
        }
    }

    /** 反射写一个 public int 字段；字段不存在返回 false（不抛异常）。 */
    private fun setIntField(target: Any, name: String, value: Int, ctx: HookContext): Boolean {
        return try {
            // 字段声明在 ConstraintLayout.LayoutParams 上 —— 沿类的继承链找
            var c: Class<*>? = target.javaClass
            while (c != null) {
                val f = try {
                    c.getField(name)
                } catch (t: Throwable) {
                    null
                }
                if (f != null && f.type == Int::class.javaPrimitiveType) {
                    f.setInt(target, value)
                    return true
                }
                c = c.superclass
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 给截图容器挂「尺寸纠正」监听：布局收敛过程中若尺寸还不对，就再置一次全屏。
     *
     * 为什么需要：`onCreate` 阶段视图树刚 inflate，`decor` 还没测量（宽高为 0），
     * 那一刻写 MATCH_PARENT 虽然能写进去，但宿主随后还会自己 requestLayout；
     * 而结果逐批到达时宿主会不断重建布局，每次都可能把容器拉回卡片态。
     *
     * 判据是**策略**而不是**像素**（见 [ensureFullBleed] 的注释）：
     * 只检查「宽高是不是 MATCH_PARENT + margin 是不是全 0」，
     * 是就不动手 —— 天然收敛，不会与宿主互相 requestLayout 刷个没完。
     */
    private fun watchContainerSize(container: View, ctx: HookContext) {
        try {
            // 用 tag 去重：这条路径每次重建都会走到，重复 add 监听会越积越多
            if (container.getTag(SIZE_WATCH_TAG) != null) return
            container.setTag(SIZE_WATCH_TAG, java.lang.Boolean.TRUE)
            container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val root = v.rootView
                if (root.width <= 0 || root.height <= 0) return@addOnLayoutChangeListener
                if (isFullBleedNow(v)) return@addOnLayoutChangeListener
                v.post {
                    try {
                        val before = "${v.width}x${v.height}"
                        ensureFullBleed(v)
                        ctx.log(
                            Log.INFO,
                            "识屏截图容器尺寸纠正（root=${root.width}x${root.height} " +
                                "$before → ${v.width}x${v.height}）",
                        )
                    } catch (t: Throwable) {
                        ctx.log(Log.WARN, "截图容器尺寸纠正失败（保持原样）", t)
                    }
                }
            }
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "挂截图容器尺寸监听失败（只靠 onCreate 一次铺满）", t)
        }
    }

    /**
     * 把容器置成 `MATCH_PARENT` + 四周 margin 清零。
     *
     * 唯一实现：主路径 [expandScreenshotCard]、pre-draw 兜底 [armPreDrawFullBleed]、
     * 布局监听 [watchContainerSize] 三处都调它 —— **必须只有一份**。
     * 曾经主路径和监听各写一套（一个设负 margin、一个清零），结果互相把对方的
     * 改动冲掉，表现为「闪一下全屏又回到小卡片」。
     *
     * 为什么不再动 margin 做补偿：容器的底部约束是
     * `bottom_toTopOf = cl_answer_xiaoai`（底部工具条）。要让容器占满整屏，
     * **正确做法是把工具条自己 GONE 掉、让约束失效**（见 [expandScreenshotCard] 第 2 步），
     * 而不是给容器塞负 margin 把它硬拽长 —— 后者会把容器拽到 2867（超出屏幕 211px）。
     */
    private fun ensureFullBleed(v: View) {
        val lp = v.layoutParams ?: return
        val full = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = full
        lp.height = full
        (lp as? android.view.ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
        v.layoutParams = lp
    }

    /** 容器策略是否已生效（MATCH_PARENT + margin 全 0）。 */
    private fun isFullBleedNow(v: View): Boolean {
        val lp = v.layoutParams ?: return false
        val full = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        if (lp.width != full || lp.height != full) return false
        return hasNoMargins(v)
    }

    /**
     * 结果就绪时 GONE 掉 PAG 扫描动画：动画放完若宿主不回收会留最后一帧
     * （光效残影）。铺满阶段不碰它 —— 扫描动画照常播放，只在出结果后清理。
     */
    private fun hideScanAnimation(rootView: View, ctx: HookContext) {
        try {
            val res = rootView.resources
            val animId = res.getIdentifier(ANIM_ID, "id", HOST_PKG)
            if (animId == 0) {
                ctx.log(Log.WARN, "动画层 id 解析不到: $ANIM_ID")
                return
            }
            rootView.rootView.findViewById<View>(animId)?.let {
                if (it.visibility != View.GONE) {
                    it.visibility = View.GONE
                    ctx.log(Log.INFO, "已清理识别动画层: $ANIM_ID")
                }
            }
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "清理识别动画层失败（保持原样）", t)
        }
    }

    /**
     * 窗口全屏化兜底：实测窗口本来就是全屏（见 [expandScreenshotCard] 注释），
     * 这里保留为幂等兜底 —— 若某版本宿主真用了悬浮卡窗口，此路径仍能接住。
     */
    private fun makeWindowFullBleed(activity: android.app.Activity, rootView: View, ctx: HookContext) {
        try {
            val window = activity.window ?: return
            val lp = window.attributes
            val full = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            if (lp.width != full || lp.height != full) {
                lp.width = full
                lp.height = full
                window.attributes = lp
                // 根布局（WRAP 高）撑满，让 MATCH 高的截图够到屏幕底
                val root = rootView.rootView
                root.layoutParams?.let { p ->
                    if (p.height != full) {
                        p.height = full
                        root.layoutParams = p
                    }
                }
                ctx.log(Log.INFO, "识屏窗口已改为全屏铺满（去除圆角卡框）")
            }
            // 悬浮卡窗口可能带背后压暗 —— 一并清掉（幂等）
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "识屏窗口全屏化失败（保持悬浮卡原样）", t)
        }
    }

    /** 沿 ContextWrapper 链找宿主 Activity（识屏页的 RV context 就是它）。 */
    private fun findActivity(context: android.content.Context): android.app.Activity? {
        var c: android.content.Context = context
        while (c !is android.app.Activity) {
            c = (c as? android.content.ContextWrapper)?.baseContext ?: return null
        }
        return c
    }
}
