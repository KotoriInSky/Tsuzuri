package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PortalTempImageContract
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Method

/**
 * 给传送门的**图片识别卡**重整操作区：一行「编辑 / 分享 / 保存」。
 *
 * ## 形态（用户逐步提要求定下来的）
 *
 * 原始布局（`card_view_taplus_recognition_image_expanded.xml`）：
 *
 * ```
 *   扫码   识物                     ← 一排文字按钮（转小米扫描器，要联网）
 *   ┌──────────────┐
 *   │   截图预览      │
 *   │           [保存] │            ← 图片右下角的圆形图标（edit_image）
 *   └──────────────┘
 * ```
 *
 * 用户的要求依次是：按钮不要跟原来那排并排 → **新起一行**；保存也拉上来；
 * 要「编辑 / 分享 / 保存」这种**直接作用于图片**的功能，复制与提取文字先去掉了；
 * 并且要**自动排列好**。最终形态：
 *
 * ```
 *   扫码   识物                     ← 原有那排，一个字都没动
 *   编辑   分享   保存              ← 我们插的新行（等宽三等分，居中）
 *   ┌──────────────┐
 *   │   截图预览      │            ← 图片区，右下角的保存图标已搬走
 *   └──────────────┘
 * ```
 *
 * 三者来源不同，这一点对排查很重要：
 *
 * | 按钮 | 来源 | 说明 |
 * | --- | --- | --- |
 * | 编辑 | **本模块新增** | `ACTION_EDIT` 调系统图片编辑器 |
 * | 分享 | **本模块新增** | `ACTION_SEND` |
 * | 保存 | **宿主原有** —— 把 `edit_image` 整个 View **搬**过来 | 点击仍是它自己的 `doSaveImage`，我们只换位置 |
 *
 * 「搬」而不是「重建」是刻意的：`edit_image` 的点击处理挂在卡片自己身上
 * （构造时 `imageView.setOnClickListener(this)`），**View 实例搬走、listener 跟着走**，
 * 宿主那套「普通保存 / 超清保存 / 弹窗二选一」的逻辑一行都不用碰、也不会被我们改坏。
 *
 * ## 关于「提取文字」
 *
 * 用户说「先不要了」，所以默认关（参数仍在，想要随时能开）。端侧 OCR 入口保留着：
 * `ContentExtentionOcrUtils.getOcrResult(bitmap)`，判据是 `ImageRecognition` 把
 * `OCR local` 与 `OCR server` 分成两条日志 —— 我们只用本地那条，
 * `Pair.first == false`（要回落服务端）时当失败处理、不碰云。
 *
 * ## 关于 URI：编辑与分享只使用模块私有临时图
 *
 * 两者都必须给外部组件一个 `content://` URI（`file://` 在 Android 7+ 会
 * `FileUriExposedException`）。源图写入模块 App 的私有目录，由
 * `PortalTempImageProvider` 临时暴露，**不进入 MediaStore，也不会出现在图库**。
 * 用户在编辑器或分享目标里明确保存时，正式图片由对方生成；未保存的私有源图由
 * Provider 的超龄清理回收。
 *
 * ## 落点为什么是构造函数 + enableOperations
 *
 * 卡片是纯 Java 的 `LinearLayout` 子类，按钮行是**本地 XML**（不是云端下发的卡片），
 * 所以「加按钮 / 搬按钮」= 改这棵 View 树。三个构造重载都汇聚到
 * `(Context, AttributeSet, int)`，**只 hook 它一个**，否则会装三次、动三遍。
 * 在 `proceed()` **之后**动手：那时 `inflate` 已经跑完，`findViewById` 拿得到锚点。
 *
 * 另外 hook `enableOperations(boolean)` 同步可用状态：原按钮在无图时会被整排设成
 * `enabled=false, alpha=0.4`，我们新加的两个必须同款，
 * 否则「没识别到图」时它们看起来还能点。
 *
 * ## 关态与回滚
 *
 * 关掉开关 = `HookRegistry` 根本不装这个 hook（不是装 no-op）。
 * 但**已经改过的 View 树不会自己复原**：新加的行撤不掉、保存按钮也搬不回原位。
 * 所以 `Catalog` 里它的 `restart` 必须是 `HOT_RELOAD`：重启 `com.miui.contentextension`
 * 让卡片重新构造，关态才真正等于「未安装模块」。
 */
object PortalImageActions : Feature(Catalog.require(Catalog.SCREEN_IMAGE)) {

    override val installId: String get() = "screen_image:card_actions"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = !isSystemServer && (processName == io.github.kotoriinsky.tsuzuri.core.ScopeTarget.CONTENT_EXTENSION.packageName)

    override fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs) = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(prefs, Catalog.SCREEN_IMAGE, "edit") || io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(prefs, Catalog.SCREEN_IMAGE, "share_image") || io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(prefs, Catalog.SCREEN_IMAGE, "object_to_xiaoai") || io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(prefs, Catalog.SCREEN_TEXT, "ocr")

    private const val TAG = "FP-PortalImage"

    private const val CARD_CLS =
        "com.miui.contentextension.text.cardview.TaplusRecognitionExpandedImageCard"
    private const val SERVICE_CLS =
        "com.miui.contentextension.services.TextContentExtensionService"
    private const val OCR_CLS =
        "com.miui.contentextension.utils.ContentExtentionOcrUtils"

    /** 锚点按钮的 id 名（「识物」）。我们的新行插在它所在那排的**下面**。 */
    private const val ANCHOR_ID_NAME = "recognition_object"

    /**
     * 取「按钮间距」时的样板按钮（扫码）。
     *
     * 为什么不能用锚点（识物）自己：布局里只有前两个按钮写了 `layout_marginEnd`，
     * 最后一个（识物）没有 —— 见 [hostButtonGap]。
     */
    private const val HOST_GAP_SAMPLE_ID = "recognition_scan_qr"

    /** 宿主原有的保存按钮（圆形图标）—— 它要被搬进新行。 */
    private const val SAVE_ID_NAME = "edit_image"

    /** 保存图标所在的图片容器（把保存按钮从它里面摘出来）。 */
    private const val GROUP_ID_NAME = "capture_image_group"

    /** 我们按钮的 tag 前缀，用于幂等判断与状态同步。 */
    private const val TAG_PREFIX = "fp_portal_action_"

    /** 我们自己那一行的 tag —— 幂等判断用（同一个卡片只插一行）。 */
    private const val ROW_TAG = "fp_portal_action_row"

    /** 宿主那排中间的「购物」格：默认 INVISIBLE 占位，我们拿第一个按钮补进这个空位。 */
    private const val SHOPPING_ID_NAME = "recognition_shopping"

    /** 按钮行自动收紧时间距的下限；再小就看不出是几个按钮了，改为等分。 */
    private const val MIN_GAP_DP = 12

    /** 新行与上面那排之间的间距（原按钮行自带的大 padding 已经挪到新行底部）。 */
    private const val ROW_TOP_GAP_DP = 10

    /**
     * 分享的开关键。
     *
     * ⚠️ 刻意**不沿用** `share`：上一版这个键的语义是「可选的分享按钮」且默认关，
     * 真机上已经存下了 `share=false`；而这一版它是「编辑 / 分享 / 保存」三件套之一、
     * 默认开。沿用旧键会让旧值静默盖住新默认（实测就是这样：代码写 true，
     * 日志读出来还是 false），而那个 false 并不是用户选的，是上一版默认值留下的。
     */
    private const val PREF_SHARE = "share_image"

    private const val PREF_EDIT = "edit"
    private const val PREF_OCR = "ocr"

    /**
     * 「识物」改道超级小爱。宿主的识物是把图交给小米扫一扫的 `miui.intent.action.screenscanner`，
     * 扫一扫 15.9.78 起那条识物链路用户实测已不可用；小爱的 `ShareReceiverActivity` 收图片分享
     * 后走它自己的识图（`startImageUpload`，c15 反编译）。默认关，关时识物保持宿主原逻辑。
     */
    private const val PREF_OBJECT_XIAOAI = "object_to_xiaoai"
    private const val DEFAULT_OBJECT_XIAOAI = false
    private const val XIAOAI_PKG = "com.miui.voiceassist"
    private const val XIAOAI_SHARE_ACTIVITY = "com.xiaomi.voiceassistant.ShareReceiverActivity"

    /** 编辑与分享默认开。 */
    private const val DEFAULT_EDIT = true
    private const val DEFAULT_SHARE = true

    /** 提取文字：用户说「先不要了」，默认关（代码留着，随时能开）。 */
    private const val DEFAULT_OCR = false

    /** 原实现在无图时给按钮的透明度（与 `enableOperations` 的实现一致）。 */
    private const val ALPHA_DISABLED = 0.4f

    /** 主线程 handler：OCR 与写文件都在后台线程，回主线程才弹 Toast / 起 Activity。 */
    private val main = Handler(Looper.getMainLooper())

    /** 最近一张卡片：全屏选字要借它的窗口类型 / token。弱引用，卡片关掉就放手。 */
    @Volatile
    private var lastCard: java.lang.ref.WeakReference<View>? = null

    /**
     * 预先解析好的宿主静态入口。
     *
     * 在 `install` 里解析一次存下来，而不是每次点击都 `Class.forName`：
     * 点击发生在 UI 线程上，那里不该做类查找；而且这些类在宿主进程里本来就已加载。
     */
    @Volatile
    private var getBitmapMethod: Method? = null

    /**
     * `ContentExtentionOcrUtils` 的类与方法签名。
     *
     * ⚠️ **只存签名，不存实例** —— 它的构造会走 `App.getInstance()`，而模块安装发生在
     * 进程启动早期，那时宿主的 Application 还没就绪，首次 `getINSTANCE()` 会抛异常
     * （实测症状就是安装报告里的 `ocrEntry=false`，而按钮看起来又是好的）。
     * 单例推迟到点击时再取，见 [ocrInstance]。
     */
    @Volatile
    private var ocrCls: Class<*>? = null

    @Volatile
    private var ocrMethod: Method? = null

    @Volatile
    private var ocrInstanceCache: Any? = null

    /** 构造函数 hook 被调用的次数（= 造了几张卡片）。诊断用。 */
    private val cardsBuilt = java.util.concurrent.atomic.AtomicInteger(0)

    /** 哪些按钮要加（保存按钮不在这里 —— 它是宿主原有的，只搬家）。 */
    private data class Buttons(val edit: Boolean, val share: Boolean, val ocr: Boolean, val objectXiaoai: Boolean) {
        override fun toString() = "edit=$edit share=$share ocr=$ocr objectXiaoai=$objectXiaoai"
    }

    /** 一个新增按钮的声明：标签（也是 tag）、自绘图标、动作。 */
    private data class ButtonSpec(
        val label: String,
        val icon: PortalIcon,
        val invoke: (Context) -> Unit,
    )

    /**
     * 我们自己的三个图标。
     *
     * 为什么自绘而不是用 `android.R.drawable.*`：系统图标是 holo 时代的**实心粗描边**，
     * 而宿主那一排图标是**白色细线**（保存那个 vector 实测 `strokeWidth=4`、
     * viewport 80、`#ffffff`）。混在一行里一眼就能看出不是一套 —— 这就是用户说的
     * 「风格统一」要解决的问题。
     *
     * 自绘而不是放进模块资源：现代 libxposed API **没有 `getModuleContext()`**，
     * 要用模块自己的 drawable 得手工造 `Resources`（`AssetManager.addAssetPath`），
     * 代价大且容易踩坑；而这里只有三个几何很简单的图标，代码画反而更可控
     */
    internal enum class PortalIcon { EDIT, SHARE, SAVE, TEXT, COPY, SELECT_ALL, TRANSLATE }

    /**
     * 自绘的细线图标 —— 几何参数逐项对齐宿主保存图标那个 vector
     * （`icon_recognition_image_save.xml`：viewport 80、`strokeWidth="4"`、圆头圆角）。
     *
     * 画法是把画布缩放到 80 见方后直接用视口坐标画，所以下面那些数字可以和那个 vector 的
     * `pathData` 对着读，改起来不用换算。
     *
     * ⚠️ **颜色必须来自主题，不能写死白色**。第一版写死 `#ffffff`，结果在浅色模式下
     * 整个看不见 —— 宿主的保存图标之所以在浅色背景上也可见，是因为它自带一层
     * 半透明灰圆底提供对比；我们的图标是纯线条，没有那层底，就得靠自己取对颜色。
     * 现在颜色从锚点按钮的 `currentTextColor` 来（那来自 `@color/color_recognition_operate`，
     * 本身就是跟随深浅模式的主题色），所以深浅模式下都自适配。
     */
    internal class PortalStrokeIcon(
        private val kind: PortalIcon,
        color: Int,
        private val side: Int,
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            // 视口单位下的线宽：与保存图标的 strokeWidth="4" 同值
            strokeWidth = STROKE_WIDTH
            this.color = color
        }

        /**
         * 当前图标 path 的**实际包围盒**（从下面各 draw 方法的坐标算出来的）。
         *
         * ⚠️ 改了 path 坐标就要同步改这里 —— 归一化（见 [draw]）按它算缩放比，
         * 对不上就会让图标偏小或被裁掉一角。
         */
        private val kindBounds: RectF
            get() = when (kind) {
                PortalIcon.EDIT -> RectF(24f, 22f, 58f, 58f)
                PortalIcon.SHARE -> RectF(18f, 14f, 62f, 66f)
                PortalIcon.SAVE -> RectF(26f, 22f, 54f, 58f)
                PortalIcon.TEXT -> RectF(30f, 20f, 50f, 60f)
                PortalIcon.COPY -> RectF(19f, 18f, 62f, 62f)
                PortalIcon.SELECT_ALL -> RectF(20f, 20f, 60f, 60f)
                PortalIcon.TRANSLATE -> RectF(16f, 15f, 64f, 66f)
            }

        override fun draw(canvas: Canvas) {
            // 在**槽位**里居中画「图标本体」大小的图：槽位 21.82dp、本体 20dp，
            // 两侧各留一点 —— 宿主的 webp 也是这样内缩在 60px 画布里的。
            // 槽位补齐之后，我们的按钮总宽才和宿主那排一样，整排才会落在同一列。
            val slot = bounds.width().toFloat()
            val body = slot * (ICON_SIZE_DP / ICON_SLOT_DP)
            val inset = (slot - body) / 2f
            val scale = body / VIEWPORT
            canvas.save()
            canvas.translate(bounds.left + inset, bounds.top + inset)
            canvas.scale(scale, scale)

            // 把内容**归一化**到同一个视觉尺寸并居中。
            //
            // 为什么不能只乘一个固定倍数：三个图标在视口里占的比例本来就不同 ——
            // 分享约 65%，铅笔与保存只有 45%。统一放大 1.4 倍之后，分享到了 18.2dp
            // 而另两个还是 12.6dp（用户截图实测：分享「差不多」，编辑/保存明显偏小）。
            // 所以按各自的包围盒算缩放比，让最长边都落到 [CONTENT_SIZE]。
            val box = kindBounds
            val fit = CONTENT_SIZE / maxOf(box.width(), box.height())
            canvas.translate(VIEWPORT / 2f, VIEWPORT / 2f)
            canvas.scale(fit, fit)
            canvas.translate(-box.centerX(), -box.centerY())
            // 线宽反向补偿：不补的话，被放得越多的图标线就越粗
            // （铅笔/保存要放 1.9 倍、分享只放 1.3 倍，最终会差出 0.6dp 的线宽）
            paint.strokeWidth = STROKE_WIDTH / fit

            when (kind) {
                PortalIcon.EDIT -> drawEdit(canvas)
                PortalIcon.SHARE -> drawShare(canvas)
                PortalIcon.SAVE -> drawSave(canvas)
                PortalIcon.TEXT -> drawText(canvas)
                PortalIcon.COPY -> drawCopy(canvas)
                PortalIcon.SELECT_ALL -> drawSelectAll(canvas)
                PortalIcon.TRANSLATE -> drawTranslate(canvas)
            }
            canvas.restore()
        }

        /** 铅笔：五边形轮廓（笔尾切口 → 笔身 → 笔尖）。 */
        private fun drawEdit(canvas: Canvas) {
            val body = Path().apply {
                moveTo(50f, 22f)
                lineTo(58f, 30f)
                lineTo(32f, 56f)
                lineTo(24f, 58f)
                lineTo(26f, 50f)
                close()
            }
            canvas.drawPath(body, paint)
        }

        /** 分享：三个圆点 + 两条连线（标准的「分享网络」形状）。 */
        private fun drawShare(canvas: Canvas) {
            canvas.drawCircle(54f, 22f, 8f, paint)
            canvas.drawCircle(54f, 58f, 8f, paint)
            canvas.drawCircle(26f, 40f, 8f, paint)
            canvas.drawLine(33.5f, 36f, 46.5f, 26.5f, paint)
            canvas.drawLine(33.5f, 44f, 46.5f, 53.5f, paint)
        }

        /** 提取文字：一个「T」加一横 —— 与分享的三点形状不会看混。 */
        private fun drawText(canvas: Canvas) {
            // 插入光标（I-beam）：竖线贯通，上下两端各是一对向外弯的短弧，左右对称、上下等长
            val top = Path().apply {
                moveTo(30f, 20f); quadTo(40f, 20f, 40f, 26f)
                moveTo(50f, 20f); quadTo(40f, 20f, 40f, 26f)
            }
            val bottom = Path().apply {
                moveTo(30f, 60f); quadTo(40f, 60f, 40f, 54f)
                moveTo(50f, 60f); quadTo(40f, 60f, 40f, 54f)
            }
            canvas.drawPath(top, paint)
            canvas.drawLine(40f, 26f, 40f, 54f, paint)
            canvas.drawPath(bottom, paint)
        }

        /**
         * 复制：前后两张圆角卡片，后一张只露出左上两条边。
         * 圆角放大到和宿主识图图标一样圆润（前卡 r=10、后卡拐角同半径），卡片接近方形 ——
         * 上一版窄高、圆角小，和宿主那排放在一起显得硬。
         */
        private fun drawCopy(canvas: Canvas) {
            // 用 Path 而不是 drawRoundRect：真机上 drawRoundRect 这一笔被画成了实心块（同一支 STROKE 画笔，
            // 其余 drawPath 都正常），改走和其他图标一样的路径画法
            val front = Path().apply {
                moveTo(40f, 28f); lineTo(52f, 28f); quadTo(62f, 28f, 62f, 38f)
                lineTo(62f, 52f); quadTo(62f, 62f, 52f, 62f)
                lineTo(40f, 62f); quadTo(30f, 62f, 30f, 52f)
                lineTo(30f, 38f); quadTo(30f, 28f, 40f, 28f)
                close()
            }
            canvas.drawPath(front, paint)
            val back = Path().apply {
                moveTo(19f, 47f); lineTo(19f, 30f)
                quadTo(19f, 18f, 31f, 18f); lineTo(47f, 18f)
            }
            canvas.drawPath(back, paint)
        }

        /** 翻译：左上一个简化的「文」，右下一个「A」，两字错开不重叠。 */
        private fun drawTranslate(canvas: Canvas) {
            val wen = Path().apply {
                moveTo(16f, 22f); lineTo(42f, 22f)
                moveTo(29f, 15f); lineTo(29f, 22f)
                moveTo(22f, 28f); quadTo(27f, 41f, 41f, 48f)
                moveTo(36f, 28f); quadTo(31f, 41f, 16f, 49f)
            }
            val a = Path().apply {
                moveTo(44f, 66f); lineTo(54f, 40f); lineTo(64f, 66f)
                moveTo(48f, 58f); lineTo(60f, 58f)
            }
            canvas.drawPath(wen, paint)
            canvas.drawPath(a, paint)
        }

        /** 全选：四角取景框。 */
        private fun drawSelectAll(canvas: Canvas) {
            val c = Path().apply {
                moveTo(20f, 32f); lineTo(20f, 20f); lineTo(32f, 20f)
                moveTo(48f, 20f); lineTo(60f, 20f); lineTo(60f, 32f)
                moveTo(60f, 48f); lineTo(60f, 60f); lineTo(48f, 60f)
                moveTo(32f, 60f); lineTo(20f, 60f); lineTo(20f, 48f)
            }
            canvas.drawPath(c, paint)
        }

        /**
         * 保存：向下的箭头 + 托盘。
         *
         * 语义与宿主原来那个保存图标一致（它也是「箭头 + 托盘」），但去掉那层半透明圆底 ——
         * 那一层是宿主 vector 自带的，会让这个按钮比编辑/分享「胖一圈」，
         * 正是要统一掉的东西（用户：风格应该完美一致）。
         */
        private fun drawSave(canvas: Canvas) {
            canvas.drawLine(40f, 22f, 40f, 46f, paint)
            canvas.drawLine(30f, 36f, 40f, 46f, paint)
            canvas.drawLine(50f, 36f, 40f, 46f, paint)
            val tray = Path().apply {
                moveTo(26f, 42f)
                lineTo(26f, 58f)
                lineTo(54f, 58f)
                lineTo(54f, 42f)
            }
            canvas.drawPath(tray, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = side

        override fun getIntrinsicHeight(): Int = side
    }

    override fun install(ctx: HookContext): InstallReport {
        PortalOcrSelect.log = { p, m -> ctx.log(p, m) }
        val want = Buttons(
            // 编辑 / 分享 / 识物改道属于「图片」，提取文字属于「文字」（2026-10-25 重组）
            edit = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(ctx.prefs, Catalog.SCREEN_IMAGE, PREF_EDIT),
            share = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(ctx.prefs, Catalog.SCREEN_IMAGE, PREF_SHARE),
            ocr = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(ctx.prefs, Catalog.SCREEN_TEXT, PREF_OCR),
            objectXiaoai = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(ctx.prefs, Catalog.SCREEN_IMAGE, PREF_OBJECT_XIAOAI),
        )
        if (!want.edit && !want.share && !want.ocr && !want.objectXiaoai) {
            // 全关 = 这个特性就只剩「把保存按钮搬个位置」这件事，不值得动 View 树
            return InstallReport(0, notes = listOf("三个开关全关，不装 hook（保存按钮保持原位）"))
        }

        val cls = try {
            Class.forName(CARD_CLS, false, ctx.classLoader)
        } catch (t: Throwable) {
            // 找不到是正常情况（传送门改版/未安装），降级退出，不抛异常
            ctx.log(Log.WARN, "class not found: $CARD_CLS，跳过")
            return InstallReport(0, listOf(CARD_CLS))
        }

        resolveHostEntryPoints(ctx)

        var hooked = 0
        val ctor = cls.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
        if (ctor == null) {
            ctx.log(Log.WARN, "找不到 (Context,AttributeSet,int) 构造函数")
        } else {
            try {
                track(
                    ctx.module.hook(ctor).intercept { chain ->
                        // 原构造照常执行（proceed 的返回值对构造器恒为 null，见 API 102 §3.5）
                        val result = chain.proceed()
                        try {
                            val card = chain.thisObject as? View
                            if (card != null) {
                                val n = cardsBuilt.incrementAndGet()
                                val added = rebuildRow(card, want, ctx)
                                // 头几张卡打全量日志（含每一步的失败原因），之后只报总数
                                if (n <= DIAG_LIMIT) {
                                    ctx.log(Log.INFO, "卡片#$n 操作区：$want 装好=$added")
                                }
                            }
                        } catch (t: Throwable) {
                            // 改 View 树失败绝不能影响卡片本身 —— 用户宁可按钮不在，
                            // 也不能没有识屏（这是这个特性唯一的存在前提）
                            ctx.log(Log.WARN, "整理操作区失败（原卡片不受影响）", t)
                        }
                        result
                    },
                )
                hooked++
            } catch (t: Throwable) {
                ctx.log(Log.ERROR, "hook 构造方法失败", t)
            }
        }

        // 状态同步：无图时卡片用 `enableOperations(false)` 把按钮变灰（alpha 0.4），
        // 我们新加的两个必须同款 —— 否则「没识别到图」时它们看起来还能点。
        val enable = cls.declaredMethods.firstOrNull {
            it.name == "enableOperations" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (enable == null) {
            ctx.log(Log.WARN, "找不到 enableOperations(boolean)，按钮不会随「无图」变灰")
        } else {
            try {
                track(
                    ctx.module.hook(enable).intercept { chain ->
                        val result = chain.proceed()
                        try {
                            syncEnabled(chain.thisObject as? View, chain.args[0] as? Boolean ?: true)
                        } catch (t: Throwable) {
                            ctx.log(Log.WARN, "同步按钮可用状态失败", t)
                        }
                        result
                    },
                )
                hooked++
            } catch (t: Throwable) {
                ctx.log(Log.ERROR, "hook enableOperations 失败", t)
            }
        }

        if (hooked == 0) {
            // 与其它特性同一条纪律：装出 0 个 hook 必须当成失败（会触发重试），
            // 而不是「不需要装」—— 历史上这个错误让人查了很久。
            return InstallReport(0, listOf(CARD_CLS), notes = listOf("两个落点都没装上"))
        }

        ctx.log(
            Log.INFO,
            "已挂上：$want hooked=$hooked ocrEntry=${ocrMethod != null} " +
                "bitmapEntry=${getBitmapMethod != null}（在「识物」那排下面新起一行：编辑/分享/保存）",
        )
        return InstallReport(
            hooked = hooked,
            notes = listOf(
                want.toString(),
                "ocrEntry=" + (ocrMethod != null),
                "bitmapEntry=" + (getBitmapMethod != null),
            ),
        )
    }

    /** 解析 `TextContentExtensionService.getBitmap()` 与 OCR 的**类/方法签名**（不取实例）。 */
    private fun resolveHostEntryPoints(ctx: HookContext) {
        try {
            getBitmapMethod = Class.forName(SERVICE_CLS, false, ctx.classLoader)
                .getMethod("getBitmap")
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "拿不到 $SERVICE_CLS.getBitmap，编辑/分享将不可用", t)
        }
        try {
            val cls = Class.forName(OCR_CLS, false, ctx.classLoader)
            ocrCls = cls
            ocrMethod = cls.getDeclaredMethod("getOcrResultByTaiYi", Bitmap::class.java).apply { isAccessible = true }
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "拿不到 $OCR_CLS.getOcrResultByTaiYi，纯本地识文不可用，禁止回退云入口", t)
        }
    }

    /**
     * 取 `ContentExtentionOcrUtils` 的单例（点击时才取，取到就缓存）。
     *
     * 形态是 `class` + `companion object`（jadx：`public static final Companion Companion`，
     * `Companion.getINSTANCE()` 返回 `INSTANCE$delegate` 的 lazy 值），所以走
     * `Companion.getINSTANCE()` 而不是找静态 `INSTANCE` 字段。
     */
    private fun ocrInstance(): Any? {
        ocrInstanceCache?.let { return it }
        val cls = ocrCls ?: return null
        return try {
            val companion = cls.getField("Companion").get(null)
            companion.javaClass.getMethod("getINSTANCE").invoke(companion).also {
                ocrInstanceCache = it
            }
        } catch (t: Throwable) {
            // 到这个点还失败，只可能是宿主改版 —— 记一次就够，别每次点击都刷
            Log.w(TAG, "取本地 OCR 单例失败", t)
            null
        }
    }

    /** 新行里按钮的视觉顺序 = 列表顺序（保存由 [moveSaveButton] 追加到最后）。 */
    private fun buttonSpecs(on: Buttons): List<ButtonSpec> = buildList {
        if (on.edit) {
            add(ButtonSpec("编辑", PortalIcon.EDIT, ::doEdit))
        }
        if (on.share) {
            add(ButtonSpec("分享", PortalIcon.SHARE, ::doShare))
        }
        if (on.ocr) {
            // 用户说「先不要了」，默认关；留着是因为端侧 OCR 入口是通的
            add(ButtonSpec("提取文字", PortalIcon.TEXT, ::doOcr))
        }
    }

    /**
     * 重整操作区：在「识物」那排下面插一行，放「编辑 / 分享 / 保存」。
     *
     * 布局要点：
     *  - 卡片本身是垂直 LinearLayout（`setOrientation(1)`），子 View 顺序是
     *    `[原按钮行, 图片区]`，新行插在 index+1 —— **不动原来那排的构成**。
     *  - 原按钮行的 `paddingBottom` 本来就是「按钮行与图片区之间」的留白，
     *    现在中间多了一行，所以把它**挪到新行底部**（原行置 0），否则双倍空隙。
     *  - 三个按钮**等宽三等分**（`weight=1` + 居中）—— 用户要的「自动排列好」。
     *
     * @return 这一行里最终有几个按钮
     */
    private fun rebuildRow(card: View, on: Buttons, ctx: HookContext): Int {
        val context = card.context ?: return 0
        val parent = card as? ViewGroup ?: return 0
        lastCard = java.lang.ref.WeakReference(card)
        // 幂等：同一个卡片只插一行
        if (parent.findViewWithTag<View>(ROW_TAG) != null) return 0

        val anchorId = context.resources.getIdentifier(ANCHOR_ID_NAME, "id", context.packageName)
        val saveId = context.resources.getIdentifier(SAVE_ID_NAME, "id", context.packageName)
        val groupId = context.resources.getIdentifier(GROUP_ID_NAME, "id", context.packageName)
        if (anchorId == 0 || saveId == 0 || groupId == 0) {
            ctx.log(Log.WARN, "拿不到 id（anchor=$anchorId save=$saveId group=$groupId），本次不动")
            return 0
        }
        val anchor = card.findViewById<TextView>(anchorId)
        if (anchor == null) {
            ctx.log(Log.WARN, "找不到锚点按钮 $ANCHOR_ID_NAME")
            return 0
        }
        val anchorRow = anchor.parent as? ViewGroup
        if (anchorRow == null) {
            ctx.log(Log.WARN, "锚点按钮没有父容器")
            return 0
        }
        val rowIndex = (0 until parent.childCount).firstOrNull { parent.getChildAt(it) === anchorRow }
            ?: (parent.childCount - 1)

        // 直接观测而不是猜：这一行到底继承到了什么 gravity、被摆进哪个容器。
        // （之前三轮改动量出来偏差一点没变，就是因为我在猜布局参数。）
        val inheritedGravity: Int = (anchorRow as? LinearLayout)?.gravity ?: -1
        ctx.log(
            Log.INFO,
            "行诊断：anchorRow=${anchorRow.javaClass.simpleName} gravity=$inheritedGravity " +
                "rowIndex=$rowIndex parent=${parent.javaClass.simpleName} " +
                "childCount=${parent.childCount} anchorPadEnd=${anchor.compoundDrawablePadding}",
        )

        if (on.objectXiaoai) {
            // 只换点击，不动外观；宿主后续 enableTextView 只改可用态，不会再设监听
            anchor.setOnClickListener { doAskXiaoai(context) }
        }

        val newRow = FitRow(context, anchorRow).apply {
            tag = ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            // 对齐方式抄原来那排，两行才是同一种视觉。
            // `gravity` 的默认值是 -1（未设置），那时才回落 CENTER。
            gravity = if (inheritedGravity >= 0) inheritedGravity else Gravity.CENTER
        }
        val bottomPad = anchorRow.paddingBottom
        anchorRow.setPadding(
            anchorRow.paddingLeft,
            anchorRow.paddingTop,
            anchorRow.paddingRight,
            0,
        )
        newRow.setPadding(
            0,
            (context.resources.displayMetrics.density * ROW_TOP_GAP_DP).toInt(),
            0,
            bottomPad,
        )
        // ⚠️ 必须显式指定 MATCH_PARENT：不指定的话新行只按内容宽（WRAP_CONTENT）算，
        // 而它在垂直 LinearLayout 里会被**靠左**摆 —— 表现是整排比原来那排左移约 80px
        // （实测我们那排的图标中心 145/475/805 vs 宿主的 226/562/899）。
        // 宽度对了之后，`gravity=center` 才真正把整排居中。
        parent.addView(
            newRow,
            rowIndex + 1,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 顺序：编辑 / 分享 /（提取文字）/ 保存 —— 保存由宿主原来那个转发而来，排最后
        val cells = ArrayList<View>(4)
        for (spec in buttonSpecs(on)) {
            appendCell(newRow, context, anchor, spec)?.let { cells.add(it) }
        }
        appendSaveCell(card, newRow, context, anchor, saveId, groupId, ctx)?.let { cells.add(it) }
        if (cells.isEmpty()) {
            // 一个按钮都没放进去 —— 把空行收掉，别在卡片上留一条空白
            parent.removeView(newRow)
            return 0
        }
        fillHostSlot(anchorRow, newRow, ctx)
        newRow.requestLayout()
        return cells.size
    }

    /**
     * 追加一个按钮格：**图标与文字装进一个小横向容器**，格子本身等宽。
     *
     * 为什么不直接用 `TextView` + `drawableStart`（第一版的做法）：真机截图显示
     * `drawableStart` 在 `width=0 + weight=1` 的格子里会被推到最左边、文字单独居中，
     * 于是图标和文字之间空出一大段，和上面那排「图标紧贴文字」的样子对不上。
     * 换成「ImageView + TextView 包一层」，两者就是一个整体，居中由容器负责，
     * 不再依赖 TextView 对 compound drawable 的排版行为。
     *
     * 间距抄锚点的 `compoundDrawablePadding` —— 与原来那排同一个值。
     *
     * @return 新加的格子；已有同名按钮（幂等命中）或创建失败时返回 null
     */
    private fun appendCell(
        row: ViewGroup,
        context: Context,
        anchor: TextView,
        spec: ButtonSpec,
    ): View? {
        val tag = TAG_PREFIX + spec.label
        // 幂等：同一张卡的同名按钮只加一次
        if (row.findViewWithTag<View>(tag) != null) return null

        val side = iconSlot(context, anchor)
        val cell = LinearLayout(context).apply {
            this.tag = tag
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = anchor.visibility
            isEnabled = anchor.isEnabled
            alpha = anchor.alpha
        }
        cell.addView(
            ImageView(context).apply {
                setImageDrawable(PortalStrokeIcon(spec.icon, iconTint(anchor), side))
                layoutParams = LinearLayout.LayoutParams(side, side).apply {
                    marginEnd = anchor.compoundDrawablePadding
                }
            },
        )
        cell.addView(labelView(context, anchor, spec.label))

        cell.setOnClickListener {
            try {
                spec.invoke(context)
            } catch (t: Throwable) {
                Log.w(TAG, "按钮「${spec.label}」执行失败", t)
            }
        }
        // 等宽：weight=1 让这一行自己排匀，不用手算间距
        addCellToRow(row, cell, anchor)
        return cell
    }

    /**
     * 把宿主原有的保存按钮换成我们自己的一格（图标 + 「保存」）。
     *
     * 为什么是「换」而不是「搬」：用户要求三个按钮风格**完美一致**，而宿主那个保存图标
     * 自带一层半透明圆底、比另两个「胖一圈」，连尺寸都不一样 —— 搬过来也统一不了。
     * 所以摘掉它、用同一套自绘图标重画一格。
     *
     * **功能一行都没改**：宿主那个 View 实例仍然保留着（它的点击处理在构造时挂在卡片上），
     * 新格子点击时 `performClick()` 转发给它 —— 「普通保存 / 超清保存 / 弹窗二选一」
     * 那套逻辑照原样跑。它被摘出 View 树后，宿主后续对它的 `setVisibility` 等操作
     * 自然落空（无害），我们这格的可用状态由 [syncEnabled] 负责。
     *
     * @return 新加的格子；找不到原按钮时返回 null（那时保存保持原位，不影响功能）
     */
    private fun appendSaveCell(
        card: View,
        row: ViewGroup,
        context: Context,
        anchor: TextView,
        saveId: Int,
        groupId: Int,
        ctx: HookContext,
    ): View? {
        val tag = TAG_PREFIX + LABEL_SAVE
        if (row.findViewWithTag<View>(tag) != null) return null

        val group = card.findViewById<ViewGroup>(groupId)
        val original = card.findViewById<View>(saveId)
        if (group == null || original == null) {
            ctx.log(Log.WARN, "找不到保存按钮或它的容器，保存留在原位")
            return null
        }
        return try {
            group.removeView(original)

            val side = iconSlot(context, anchor)
            val cell = LinearLayout(context).apply {
                this.tag = tag
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = anchor.visibility
                isEnabled = anchor.isEnabled
                alpha = anchor.alpha
            }
            cell.addView(
                ImageView(context).apply {
                    setImageDrawable(PortalStrokeIcon(PortalIcon.SAVE, iconTint(anchor), side))
                    layoutParams = LinearLayout.LayoutParams(side, side).apply {
                        marginEnd = anchor.compoundDrawablePadding
                    }
                },
            )
            cell.addView(labelView(context, anchor, LABEL_SAVE))
            // 转发给宿主原按钮，逻辑保持原样
            cell.setOnClickListener { original.performClick() }

            addCellToRow(row, cell, anchor)
            cell
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "替换保存按钮失败（它可能还在原位）", t)
            null
        }
    }

    /**
     * 把格子放进那一行：宽度自适应 + **抄原来那排的按钮间距**。
     *
     * 为什么不用 `weight=1` 等宽（第一版的做法）：宿主那排是
     * 「内容宽 + 固定间距（56dp）」、整排居中；等宽三等分会把按钮推开 ——
     * 实测我们那排的图标中心落在 155 / 561 / 968，宿主是 226 / 562 / 899：
     * **中间那个碰巧对上，左右两个各偏约 70px**。用户看到的「图标没对齐」就是这个。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun addCellToRow(row: ViewGroup, cell: View, anchor: TextView) {
        row.addView(
            cell,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    /**
     * 自动补位：宿主那排的「购物」格不可见时，把我们这排的第一格搬进它的位置，
     * 购物格设 GONE 让出空间；宿主之后若把购物显示出来，就把我们那格搬回第二排行首。
     * 这样「扫码 / 编辑 / 识物」一排满三格，第二排剩下的按列对齐，不会一排挤四个。
     */
    private fun fillHostSlot(hostRow: ViewGroup, ourRow: ViewGroup, ctx: HookContext) {
        val c = hostRow.context
        val id = c.resources.getIdentifier(SHOPPING_ID_NAME, "id", c.packageName)
        val shopping = if (id != 0) hostRow.findViewById<View>(id) else null
        if (shopping == null || shopping.parent !== hostRow || shopping.visibility == View.VISIBLE) return
        val first = ourRow.getChildAt(0) ?: return
        if (ourRow.childCount < 2) return // 只有一格时留在第二排居中，不必拆
        try {
            val idx = hostRow.indexOfChild(shopping)
            ourRow.removeView(first)
            val lp = LinearLayout.LayoutParams(shopping.layoutParams as ViewGroup.MarginLayoutParams)
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            shopping.visibility = View.GONE
            hostRow.addView(first, idx + 1, lp)
            hostRow.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (shopping.visibility != View.VISIBLE || first.parent !== hostRow) return
                    hostRow.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    hostRow.removeView(first)
                    ourRow.addView(
                        first, 0,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                }
            })
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "补位失败（按钮留在第二排）", t)
        }
    }

    /**
     * 按钮行：**两端对齐到上面那排的外沿**（扫码左沿 ~ 识物右沿），中间均分间距。
     *
     * 宿主那排是「扫码 /（购物，不可见但占位）/ 识物」三格；我们这排二到四格，
     * 用同一段跨度两端对齐，两排的左右边缘就是一条线，格数不同也不显乱。
     * 跨度装不下（大字号 / 窄屏）就用整行宽度；间距仍小于 [MIN_GAP_DP] 时退成等分格、文字省略。
     *
     * ⚠️ 不能信 widthMeasureSpec：卡片用 UNSPECIFIED 量这一行（实测间距全为 0 就是这个原因），
     * 所以可用宽度取**已经量过的宿主那排**的宽度。
     */
    private class FitRow(context: Context, private val hostRow: ViewGroup) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cells = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
            val full = hostRow.measuredWidth.takeIf { it > 0 } ?: MeasureSpec.getSize(widthMeasureSpec)
            if (cells.isEmpty() || full <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val exact = MeasureSpec.makeMeasureSpec(full, MeasureSpec.EXACTLY)
            // 先按内容宽量一遍，拿到每格的自然宽度
            cells.forEach { lpOf(it).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT; weight = 0f; marginEnd = 0 } }
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val content = cells.sumOf { it.measure(unspecified, heightMeasureSpec); it.measuredWidth }
            val slots = cells.size - 1
            val minGap = (resources.displayMetrics.density * MIN_GAP_DP).toInt()
            val hostSpan = hostSpan()
            val span = if (hostSpan in (content + minGap * slots)..(full - paddingLeft - paddingRight)) hostSpan
                else full - paddingLeft - paddingRight
            val gap = if (slots == 0) 0 else (span - content) / slots
            if (cells.size <= 3 && hostColumnStarts().size == 3) {
                // 列对齐交给 onLayout，这里只要内容宽
            } else if (slots == 0 || gap >= minGap) {
                cells.dropLast(1).forEach { lpOf(it).marginEnd = gap }
            } else {
                cells.forEach { lpOf(it).apply { width = 0; weight = 1f } }
            }
            super.onMeasure(exact, heightMeasureSpec)
        }

        /**
         * 一到三格时按宿主那排的**列左沿**摆：两排共用一套三列网格，上下对齐。
         * 三格占 1/2/3 列，两格占 1/3 列（左右对称），一格居中列。
         */
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val cells = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
            val cols = hostColumnStarts()
            if (cols.size != 3 || cells.isEmpty() || cells.size > 3) return
            val pick = when (cells.size) { 1 -> listOf(1); 2 -> listOf(0, 2); else -> listOf(0, 1, 2) }
            val dx = hostRow.left - left
            // 按左沿对齐：每格都是「图标在左 + 文字」，左沿对齐 = 两排图标上下对成一条线
            cells.forEachIndexed { i, v ->
                val w = v.measuredWidth
                val nl = (cols[pick[i]] + dx).coerceIn(0, maxOf(0, width - w))
                v.layout(nl, v.top, nl + w, v.bottom)
            }
        }

        /** 宿主那排各占位格（含 INVISIBLE、含我们补进去的那格）的左沿 x，相对宿主那排。 */
        private fun hostColumnStarts(): List<Int> =
            (0 until hostRow.childCount).map { hostRow.getChildAt(it) }
                .filter { it.visibility != View.GONE && it.width > 0 }
                .map { it.left + it.paddingLeft }

        /** 宿主那排占位格（含 INVISIBLE）从第一格左沿到最后一格右沿的宽度；量不到返回 0。 */
        private fun hostSpan(): Int {
            val kids = (0 until hostRow.childCount).map { hostRow.getChildAt(it) }.filter { it.visibility != View.GONE }
            if (kids.isEmpty() || kids.any { it.measuredWidth == 0 && it.visibility == View.VISIBLE }) return 0
            return kids.sumOf { v ->
                val lp = v.layoutParams as? MarginLayoutParams
                v.measuredWidth + (lp?.marginStart ?: 0) + (lp?.marginEnd ?: 0)
            } - ((kids.last().layoutParams as? MarginLayoutParams)?.marginEnd ?: 0)
        }

        private fun lpOf(v: View) = v.layoutParams as LayoutParams
    }

    /**
     * 原来那排的**按钮间距**（像素）。
     *
     * ⚠️ 不能直接抄锚点（识物）自己的 `marginEnd`：布局里只有**前两个**按钮写了
     * `layout_marginEnd="@dimen/card_recognition_image_operate_margin"`（56dp），
     * **最后一个（识物）没有** —— 它后面不需要间距。而我们的锚点正是识物，抄它得到 0，
     * 于是三个格子紧挨在一起（用户原话「三部分挤在一起了」）。
     * 所以单独去找「扫码」那个按钮当样板。
     *
     * 取运行时的值而不是写死 56dp：折叠屏上宿主会用 `updateMargin` 把它换成
     * `size_154` / `size_300`，抄运行时值就自动跟上了。
     */
    private fun hostButtonGap(anchor: TextView): Int {
        val ctx = anchor.context
        val id = ctx.resources.getIdentifier(HOST_GAP_SAMPLE_ID, "id", ctx.packageName)
        if (id == 0) return 0
        val sample = anchor.rootView.findViewById<View>(id) ?: return 0
        return (sample.layoutParams as? LinearLayout.LayoutParams)?.marginEnd ?: 0
    }

    /** 按锚点按钮的字体、字号、颜色做文字标签 —— 和原来那排同一个视觉。 */
    private fun labelView(context: Context, anchor: TextView, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.textSize)
            setTextColor(anchor.textColors)
            typeface = anchor.typeface
            isSingleLine = true
            ellipsize = anchor.ellipsize ?: android.text.TextUtils.TruncateAt.END
        }

    /**
     * 把新加按钮的可用状态对齐到原按钮那一套（`enableOperations` 的语义）。
     *
     * 卡片无图时会把整排按钮设成 `enabled=false, alpha=0.4` —— 我们跟着做，
     * 否则「没识别到图」时我们的按钮看起来还能点，点了却没反应。
     * 保存按钮不用管：它会自己灰（`refresh()` 直接操作 `mEditImage`）。
     */
    private fun syncEnabled(card: View?, enabled: Boolean) {
        card ?: return
        for (label in LABELS) {
            val v = card.findViewWithTag<View>(TAG_PREFIX + label) ?: continue
            v.isEnabled = enabled
            v.alpha = if (enabled) 1.0f else ALPHA_DISABLED
        }
    }

    // ── 编辑 ──────────────────────────────────────────────────────────────

    /**
     * 用 **HyperOS 自带的图片编辑器**打开这张图。
     *
     * 显式指定组件，而不是发一个裸的 `ACTION_EDIT` —— 后者要走系统解析，
     * 设备上只要有别的 App 也声明了「EDIT + image 类型」，就会弹「打开方式」选择器，
     * 而用户要的是「直接调 hyperos 的图片编辑功能」。
     *
     * 目标组件实测自 `pm query-activities`（action 取 EDIT、MIME 取 image）：
     *
     * ```
     * name        = com.miui.gallery.editor.photo.app.ExternalPhotoEditor
     * packageName = com.miui.mediaeditor          ← 注意：在 mediaeditor 包里，
     *                                                类名带 gallery 是历史原因
     * isDefault   = true                          ← 它就是这套 ROM 的默认图片编辑器
     * processName = com.miui.mediaeditor:photo_editor
     * ```
     *
     * 编辑器要**写**回图片，所以读权限之外再给 `FLAG_GRANT_WRITE_URI_PERMISSION`；
     * 从宿主进程起 Activity 必须带 `NEW_TASK`。
     *
     * 三层回落（逐级放宽，任何一层成功就停）：
     * 显式组件 → 只限包的 `ACTION_EDIT` → 通用 `ACTION_EDIT`（交给系统选）。
     * 第一层失败通常只是 OTA 改了 Activity 名，第二层仍会落到同一个 App。
     *
     * ⚠️ **这段注释里刻意不写出 MIME 通配符的原文**（那个「斜杠 + 星号」的序列）。
     * Kotlin 的**块注释可以嵌套**，注释里出现它会让词法分析器开启一个嵌套注释，
     * 要额外一个闭合符号才结束 —— 后面的代码于是整片被吞进注释，报出来的是一堆
     * 毫不相干的 `Unresolved reference`（连 `STROKE_WIDTH`、`doEdit` 都"找不到"），
     * 看着像文件被改坏。这个坑真踩过一次（见 2026-09-16 的提交），别再写回去。
     *
     * 顺带记一条：**注释里连那个闭合符号本身也不能写** —— 写了它会当场闭合注释。
     * 我就是在写上面这段说明的时候又踩了一次（编译报 `Expecting member declaration`）。
     */
    private fun doEdit(context: Context) {
        if (PortalOriginalMedia.edit(context) { uri, mime -> startEditor(context, uri, mime) }) return
        if (!PortalOriginalMedia.editingAllowed(context)) return
        val bitmap = currentBitmap() ?: return
        Thread {
            val uri = try {
                publishPrivateImage(context, bitmap, PortalTempImageContract.EDIT_SEGMENT)
            } catch (t: Throwable) {
                Log.w(TAG, "写编辑源图失败", t)
                null
            }
            main.post {
                if (uri == null) {
                    toast(context, "编辑失败：无法写入图片")
                    return@post
                }
                if (!startEditor(context, uri)) {
                    deletePrivateImage(context, uri)
                    toast(context, "没有可用的图片编辑器")
                }
            }
        }.start()
    }

    /**
     * 按「显式组件 → 限包 → 交给系统」的顺序把编辑器拉起来，任一层成功即停。
     *
     * 写成三段直白的调用而不是 `listOf` + `withIndex` 循环：后者在这个文件里
     * 触发了 `iterator()` 歧义（`List<() -> Intent>` 的类型推断失败），
     * 而那个错误会让**整个 object 的符号解析一起失败** —— 表现是一大片
     * `Unresolved reference`（连 `STROKE_WIDTH`、`doEdit` 这些毫不相干的都"找不到"），
     * 看着像文件被改坏了，其实只是这一处的级联假象。
     */
    private fun startEditor(context: Context, uri: Uri, mime: String = "image/*"): Boolean {
        if (tryStart(context, editIntent(uri, mime).setClassName(HYPER_EDITOR_PKG, HYPER_EDITOR_ACTIVITY), 1)) {
            return true
        }
        if (tryStart(context, editIntent(uri, mime).setPackage(HYPER_EDITOR_PKG), 2)) {
            return true
        }
        return tryStart(context, editIntent(uri, mime), 3)
    }

    /** `ACTION_EDIT` + 临时图 URI：编辑器要**写**回图片，所以读权限之外还要给写权限。 */
    private fun editIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(uri, mime)
        clipData = ClipData.newRawUri("image", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // 从宿主进程起 Activity 必须带 NEW_TASK（没有 Activity 栈可挂）
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 试一层；失败不抛，交给下一层。 */
    private fun tryStart(context: Context, intent: Intent, level: Int): Boolean = try {
        context.startActivity(intent)
        if (level > 1) Log.i(TAG, "图片编辑器回落到第 $level 层（上一层没起来）")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "第 $level 层启动图片编辑器失败", t)
        false
    }

    // ── 分享 ──────────────────────────────────────────────────────────────

    /**
     * 分享当前识别的图片。
     *
     * 分享源图写进模块私有 Provider，并通过 Intent URI grant 交给最终接收方；
     * 取消选择或接收方不保存，都不会在图库里留下模块创建的副本。
     */
    private fun doShare(context: Context) {
        if (PortalOriginalMedia.share(context)) return
        val bitmap = currentBitmap() ?: return
        Thread {
            val uri = try {
                publishPrivateImage(context, bitmap, PortalTempImageContract.SHARE_SEGMENT)
            } catch (t: Throwable) {
                Log.w(TAG, "写分享源图失败", t)
                null
            }
            main.post {
                if (uri == null) {
                    toast(context, "分享失败：无法写入图片")
                    return@post
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("image", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "分享图片").apply {
                    clipData = ClipData.newRawUri("image", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(chooser)
                } catch (t: Throwable) {
                    deletePrivateImage(context, uri)
                    Log.w(TAG, "没有可接收图片的应用", t)
                    toast(context, "没有可接收图片的应用")
                }
            }
        }.start()
    }

    /** 识物改道：把当前图片直接交给超级小爱的分享入口（显式组件，不弹选择器）。 */
    private fun doAskXiaoai(context: Context) {
        val bitmap = currentBitmap() ?: return
        Thread {
            val uri = try {
                publishPrivateImage(context, bitmap, PortalTempImageContract.SHARE_SEGMENT)
            } catch (t: Throwable) {
                Log.w(TAG, "写识物源图失败", t)
                null
            }
            main.post {
                if (uri == null) {
                    toast(context, "识物失败：无法写入图片")
                    return@post
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    setClassName(XIAOAI_PKG, XIAOAI_SHARE_ACTIVITY)
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("image", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(send)
                    Log.i(TAG, "识物改道超级小爱：已发出 $uri")
                } catch (t: Throwable) {
                    deletePrivateImage(context, uri)
                    Log.w(TAG, "超级小爱不接收图片", t)
                    toast(context, "超级小爱不可用")
                }
            }
        }.start()
    }

    // ── 提取文字（本地 OCR，默认关） ──────────────────────────────────────

    /** 走宿主自己的**端侧** OCR，结果直接进剪贴板。识别在后台线程（可能要几百毫秒）。 */
    /**
     * 端侧 OCR 后打开全屏自由选字（[PortalOcrSelect]），不再整段复制。
     * 只调太乙端侧 `doOCR`；宿主 `getOcrResult` 端侧失败会上传图片，不走它。
     */
    private fun doOcr(context: Context) {
        val instance = ocrInstance()
        val card = lastCard?.get()
        if (instance == null || card == null) {
            PortalOcrSelect.log?.invoke(Log.WARN, "提取文字：ocrUtils=${instance != null} card=${card != null}")
            toast(context, "本地识文不可用")
            return
        }
        val bitmap = currentBitmap() ?: run {
            PortalOcrSelect.log?.invoke(Log.WARN, "提取文字：取不到当前图片")
            toast(context, "没有可识别的图片")
            return
        }
        // 宿主随时可能换图/回收，选字界面用自己的副本
        val copy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            PortalOcrSelect.log?.invoke(Log.WARN, "提取文字：复制图片失败 $t")
            null
        } ?: return
        PortalOcrSelect.log?.invoke(Log.INFO, "提取文字：开始识别 ${copy.width}x${copy.height}")
        toast(context, "正在识别文字…")
        Thread {
            // 整体兜底：裸线程里逃出去的异常会带走宿主进程
            val glyphs = try {
                PortalOcrSelect.recognize(instance, copy)
            } catch (t: Throwable) {
                PortalOcrSelect.log?.invoke(Log.WARN, "提取文字：识别线程异常 $t")
                null
            }
            main.post {
                when {
                    glyphs == null -> { copy.recycle(); toast(context, "本地识文不可用") }
                    glyphs.isEmpty() -> { copy.recycle(); toast(context, "未识别出文字") }
                    // 交给选字窗口后由它在关闭时回收
                    else -> PortalOcrSelect.show(card, copy, glyphs)
                }
            }
        }.start()
    }

    private fun copyTextToClipboard(context: Context, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            cm.setPrimaryClip(ClipData.newPlainText("识屏文字", text))
        } catch (t: Throwable) {
            Log.w(TAG, "写入剪贴板失败", t)
        }
    }

    // ── 私有临时图：编辑与分享共用，但绝不进入 MediaStore ────────────────

    /**
     * 把图片写进模块私有 Provider，返回一次性的 `content://` URI。
     *
     * 这条路径和 MediaStore 完全分离：编辑器/分享目标只能拿到一个临时文件，
     * 不会因为模块准备输入图而在图库里出现副本。编辑器或接收方自己点击保存
     * 时产生的正式图片不属于本文件，也不会被本模块清理。
     */
    private fun publishPrivateImage(
        context: Context,
        bitmap: Bitmap,
        segment: String,
    ): Uri? {
        if (!PortalTempImageContract.isSafeSegment(segment)) return null
        val base = Uri.parse("content://${PortalTempImageContract.AUTHORITY}/$segment")
        val uri = context.contentResolver.insert(
            base,
            ContentValues().apply { put("mime_type", "image/png") },
        ) ?: return null
        return try {
            val ok = context.contentResolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            } == true
            if (!ok) throw IllegalStateException("压缩图片失败")
            uri
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            Log.w(TAG, "写私有临时图失败 segment=$segment", t)
            null
        }
    }

    /** 启动编辑器/分享失败时立即回收，不给模块私有目录留下无主文件。 */
    private fun deletePrivateImage(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "删除私有临时图失败", t)
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private fun currentBitmap(): Bitmap? = try {
        getBitmapMethod?.invoke(null) as? Bitmap
    } catch (t: Throwable) {
        Log.w(TAG, "取当前图片失败", t)
        null
    }

    private fun toast(context: Context, msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // 弹 Toast 失败不值得往上抛
        }
    }

    /**
     * 图标颜色：跟随锚点按钮的文字色。
     *
     * 那个颜色来自宿主的 `@color/color_recognition_operate`，本身就是跟随深浅模式的
     * 主题色 —— 所以不用自己去判 `uiMode`，也不会出现在某种模式下看不见的情况。
     * `currentTextColor` 在极少数情况下会是 0（尚未解析），这时回落到 ColorStateList 的默认色。
     */
    private fun iconTint(anchor: TextView): Int {
        val current = anchor.currentTextColor
        return if (current != 0) current else anchor.textColors.defaultColor
    }

    /**
     * 图标**槽位**的边长（像素）—— 注意不是图标线条的尺寸。
     *
     * 宿主那排每个按钮的 drawable 槽位是 `@dimen/size_60` = **21.82dp**
     * （图标 webp 本身只有 20dp，槽位两侧各留约 0.9dp）。
     * **槽位宽度决定按钮总宽**：我们给 20dp 就比宿主窄一点，
     * 整排窄约 6px、居中后两端各自差几个 dp —— 用户说的「图标和文字的组合差几个 dp」就是这个。
     *
     * ⚠️ `intrinsicWidth` 返回的是 **mdpi 基准的 dp 值**（21.82），必须乘 density
     * 才是屏幕像素 —— 第一版直接当 px 用，图标画出来只有一半大（踩过一次）。
     */
    private fun iconSlot(context: Context, anchor: TextView): Int {
        val density = context.resources.displayMetrics.density
        val dp = anchor.compoundDrawablesRelative.firstOrNull { it != null }?.intrinsicWidth ?: 0
        return if (dp in 1..60) (dp * density).toInt() else (ICON_SLOT_DP * density).toInt()
    }

    /** 我们新加的按钮标签（状态同步时按它找）。保存虽是宿主的功能，但图标由我们重画，一并纳入。 */
    private val LABELS = listOf("编辑", "分享", "提取文字", LABEL_SAVE)

    /** 保存按钮的标签。 */
    private const val LABEL_SAVE = "保存"

    /** HyperOS 图片编辑器的包与组件（实测自 `pm query-activities` 的 EDIT + image 查询）。 */
    private const val HYPER_EDITOR_PKG = "com.miui.mediaeditor"
    private const val HYPER_EDITOR_ACTIVITY = "com.miui.gallery.editor.photo.app.ExternalPhotoEditor"

    /** 自绘图标的视口边长 —— 与宿主保存图标那个 vector 一致，画法可以直接对着读。 */
    private const val VIEWPORT = 80f

    /**
     * 自绘图标的线宽（视口单位）。
     *
     * **8** 是量出来的，不是估的：宿主那两个原图标（`icon_image_recognition_qrcode` /
     * `_object` 的 webp）实测**线宽 6px @60px 画布**（占画布 10%），
     * 而画布是 60px@xxhdpi = 20dp，所以实际线宽 = **2.0dp**。
     * 我们的图标也是 20dp，于是 `STROKE_WIDTH / VIEWPORT × 20dp = 2.0dp` → 8。
     *
     * 上一版是 5.6（≈1.4dp），比宿主细三成 —— 视觉上就是「发灰、不够实」，
     * 用户当初感觉像有一层遮罩，实际差的是这个。
     * 归一化时还会除以各图标自己的缩放比做补偿，所以三个图标**最终**线宽一致。
     */
    private const val STROKE_WIDTH = 8f

    /**
     * 归一化后每个图标内容的最长边（视口单位）。
     *
     * 72/80 = 视口的 90%；换算到屏幕是 `72/80 × 20dp = 18dp` —— 落在宿主
     * 那两枚（17.8 / 20.3dp）之间。
     */
    private const val CONTENT_SIZE = 72f

    /** 图标**本体**的直径（dp）—— 宿主 webp 实测 60px@xxhdpi = 20dp。 */
    private const val ICON_SIZE_DP = 20f

    /**
     * 图标**槽位**的边长（dp）—— 宿主 `@dimen/size_60` 的实测值。
     * 槽位比本体宽（两侧各留约 0.9dp），它决定按钮总宽。
     */
    private const val ICON_SLOT_DP = 21.82f

    private const val DIAG_LIMIT = 3
}
