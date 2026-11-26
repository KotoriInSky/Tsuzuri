package io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.kotoriinsky.tsuzuri.core.GlobalRecognition
import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract
import io.github.kotoriinsky.tsuzuri.core.PortalMotionPhoto
import io.github.kotoriinsky.tsuzuri.core.QrJumpResolver
import io.github.kotoriinsky.tsuzuri.core.ScreenEntityResolver
import io.github.kotoriinsky.tsuzuri.core.ScreenTranslation
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.LocalQrScanner
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalImageActions
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalOcrSelect
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

/**
 * 全局识别的整屏窗口（传送门进程内）：截图 1:1 铺满并压暗，识别出的文字行与图片区域按原亮度挖出来。
 *
 *  - 点文字选整行，在文字上拖动选一段，两端手柄微调；底栏 全选 / 复制 / 分享 / 翻译。
 *  - 点图片区域选中该图，在空白处拖动画框；底部换成传送门原生识图卡片（扫码、识物、保存，
 *    以及 [PortalImageActions] 开着时加的编辑 / 分享一行）。
 *
 * 窗口在 Activity 之上，所以凡是会拉起别的界面的动作，点下后都要把自己关掉，否则新界面被压在下面。
 */
internal class GlobalRecognitionWindow(
    private val context: Context,
    val shot: Shot,
    private val host: Host,
    private val fxStyle: String = GlobalRecognition.FX_AURORA,
    private val onClosed: () -> Unit,
) {

    /** 宿主能力，由 [GlobalRecognitionHost] 反射实现。 */
    interface Host {
        fun hostBitmap(): Bitmap?
        fun setHostBitmap(bitmap: Bitmap?): Boolean
        fun newImageCard(context: Context): View?
        fun translate(context: Context, text: String): Boolean
        fun log(priority: Int, msg: String)

        /** 向来源应用要 [rect] 里那张图的源位图或原文件字节；回调可能在任意线程。 */
        fun requestSourceImage(context: Context, rect: RectF, done: (SourceImageClient.Result) -> Unit)
    }

    /** 截图被窗口和识别线程同时持有，最后一个放手的人回收。 */
    class Shot(val bitmap: Bitmap) {
        private val refs = AtomicInteger(1)
        fun retain() { refs.incrementAndGet() }
        fun release() { if (refs.decrementAndGet() == 0 && !bitmap.isRecycled) bitmap.recycle() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = context.resources.displayMetrics.density
    private val palette = Palette.of(context)
    private val root = Root(context)
    private val screen = ScreenView(context)
    private val bottom = FrameLayout(context)

    /** 跟随系统深浅色的底部面板与提示卡配色。 */
    private data class Palette(
        val dark: Boolean,
        val sheetBg: Int,
        val sheetStroke: Int,
        val grip: Int,
        val ink: Int,
        val inkSecondary: Int,
        val pillBg: Int,
        val confirmBg: Int,
        val confirmStroke: Int,
        val spinner: Int,
    ) {
        companion object {
            fun of(context: Context): Palette {
                val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                return if (dark) {
                    Palette(
                        dark = true,
                        sheetBg = 0xFF1E2025.toInt(),
                        sheetStroke = 0x1FFFFFFF,
                        grip = 0x4DFFFFFF,
                        ink = 0xFFF2F4F8.toInt(),
                        inkSecondary = 0xFFA2A8B4.toInt(),
                        pillBg = 0xFF2D313A.toInt(),
                        confirmBg = 0xFF23262D.toInt(),
                        confirmStroke = 0x26FFFFFF,
                        spinner = 0xFFB0B6C2.toInt(),
                    )
                } else {
                    Palette(
                        dark = false,
                        sheetBg = 0xFFFDFDFE.toInt(),
                        sheetStroke = 0x12000000,
                        grip = 0x33000000,
                        ink = 0xFF141518.toInt(),
                        inkSecondary = 0xFF666B75.toInt(),
                        pillBg = 0xFFF0F2F6.toInt(),
                        confirmBg = Color.WHITE,
                        confirmStroke = 0x12000000,
                        spinner = 0xFF8A8A8A.toInt(),
                    )
                }
            }
        }
    }

    /**
     * 文字栏、加载中、识图卡片共用的底部面板：外壳只有这一个，内容按选中项换。
     * 从隐藏到出现滑入、到隐藏滑出；已经在显示时只换内容，不重播进出动画。
     *
     * 两种内容同一个高度，按钮行都顶对齐；选中时全部展开，下滑收起到只露把手和
     * 第一排（[COLLAPSED_DP]），上滑或点把手再展开（A7）。收起 = 整块往下移出屏幕一截，内容不重排。
     */
    private val sheet = SheetLayout(context).apply {
        background = sheetBackground()
        setPadding(0, (12 * d).toInt(), 0, (24 * d).toInt())
        minimumHeight = (SHEET_DP * d).toInt()
        // 吃掉面板空白处的触摸，免得漏到下面的整屏画布上变成「点空白清除选中」
        isClickable = true
        visibility = View.GONE
    }
    private val grip = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = 2 * d
            setColor(palette.grip)
        }
    }
    private var sheetContent: View? = null
    private var sheetShown = false

    /** 面板是否收起。每次选中新东西都全部展开（用户：选中直接全部展开），想看底下再手动下滑收起。 */
    private var collapsed = false

    /** 手指还按在整屏画布上时选中的变化先记着，抬手再出面板（选完再动画，拖选途中面板不弹来弹去）。 */
    private var deferredSelection: SelectionState? = null
    private var textBar: View? = null
    private var textEntitySlot: FrameLayout? = null
    private var textTranslateSlot: FrameLayout? = null
    private var translateSeq = 0
    private var translateOverlayEnabled = true
    private var activeAnimatedPreview: AnimatedImageDrawable? = null
    private var closing = false
    private var attached = false

    private data class SelectionState(
        val image: RectF? = null,
        val hasText: Boolean = false,
        val qr: LocalQrScanner.Detection? = null,
        val preferSource: Boolean = true,
    )

    /** 第一次把识图位图换成选区之前，宿主原来那张；关窗时还原。 */
    private var savedHostBitmap: Bitmap? = null
    private var swappedHost = false
    private var ourCrop: Bitmap? = null

    /** 正在等源位图的那次选中；用户在等的时候改选了别的，就丢弃晚到的结果。 */
    private var pendingImage: RectF? = null

    fun show() {
        // 有选中时返回键先清除选中，没有才关窗：误按也不会丢掉整屏识别结果
        root.onBack = { if (!screen.clearSelection()) close() }
        // 识别的是打开那一刻的截图：中途转屏，截图和屏幕就对不上了（字框、图片位置全错位），直接关窗
        val orientation = context.resources.configuration.orientation
        root.onRotate = { now ->
            if (now != orientation) {
                host.log(Log.INFO, "屏幕方向变了（$orientation → $now），关闭识别窗口")
                close()
            }
        }
        root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bottom.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(grip, FrameLayout.LayoutParams((36 * d).toInt(), (4 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = -(6 * d).toInt()
        })
        // 截图上让时底下露出的那一条不能是透明（会透出后面真实的应用），用黑色垫着
        root.setBackgroundColor(Color.BLACK)
        root.addView(closeButton(), FrameLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = statusBarHeight() + (8 * d).toInt()
            marginEnd = (16 * d).toInt()
        })
        screen.onSelection = { image, hasText, qr, preferSource ->
            val state = SelectionState(image, hasText, qr, preferSource)
            if (screen.touching) deferredSelection = state else onSelection(state)
        }
        screen.onTouchEnd = {
            deferredSelection?.let { state -> deferredSelection = null; onSelection(state) }
            if (screen.selectedText().isNotEmpty() && sheetShown) refreshTextEntity()
            updateAvoid()
        }
        for (type in TYPES) {
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                title = "FP-GlobalRecognition"
            }
            try {
                wm.addView(root, lp)
                attached = true
                host.log(Log.INFO, "整屏窗口已打开 type=$type")
                break
            } catch (t: Throwable) {
                host.log(Log.WARN, "整屏窗口打开失败 type=$type：$t")
            }
        }
        if (!attached) {
            toast("无法打开识别界面")
            finish()
            return
        }
        root.requestFocus()
        root.post {
            // 截图已经是整屏，把真状态栏 / 导航栏藏起来，免得叠在截图的状态栏上
            runCatching {
                root.windowInsetsController?.apply {
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                }
            }
            screen.enter()
        }
    }

    fun setResults(
        glyphs: List<PortalOcrSelect.Glyph>?,
        regions: List<RectF>,
        qrs: List<LocalQrScanner.Detection> = emptyList(),
    ) {
        if (closing) return
        screen.setResults(glyphs.orEmpty(), regions, qrs)
        when {
            glyphs == null && regions.isEmpty() && qrs.isEmpty() -> toast("本地识别不可用，可以拖动框选图片")
            qrs.isNotEmpty() && !screen.hasSelection() -> {
                // 画面含二维码时自动亮出第一个二维码的直达卡片，一点即跳
                screen.selectQr(qrs.first())
            }
        }
    }

    fun close() {
        if (closing) return
        closing = true
        if (!attached) { finish(); return }
        root.animate().alpha(0f).setDuration(ANIM_OUT_MS).setInterpolator(ANIM_OUT).withEndAction {
            runCatching { wm.removeView(root) }
            finish()
        }.start()
    }

    private fun finish() {
        stopAnimatedPreview()
        stopSaveWatch()
        screen.stop()
        restoreHostBitmap()
        shot.release()
        onClosed()
    }

    private fun stopAnimatedPreview() {
        runCatching { activeAnimatedPreview?.stop() }
        activeAnimatedPreview = null
    }

    // ── 选择 → 底部 ─────────────────────────────────────────────────────

    /** 没选东西时底部什么都不放：底栏会挡住屏幕最下面那几行字。 */
    private fun onSelection(state: SelectionState) {
        pendingImage = null
        stopAnimatedPreview()
        clearTranslationCard()
        // 改选了别的：之前那次保存再完成也不该清掉新的选中
        stopSaveWatch()
        if (state.image != null || state.hasText || state.qr != null) collapsed = false
        when {
            state.qr != null -> showQrBar(state.qr)
            state.image != null -> showCard(state.image, state.preferSource)
            state.hasText -> showTextBar()
            else -> setSheet(null)
        }
        updateAvoid()
    }

    private fun clearTranslationCard() {
        translateSeq++
        screen.clearTranslationOverlay()
        textTranslateSlot?.apply {
            removeAllViews()
            visibility = View.GONE
        }
    }

    /** 发起一次窗内静默翻译（不关闭当前识屏窗口），并在成功后默认原位覆盖显示译文。 */
    private fun startInWindowTranslate(rawText: String, overrideDest: String? = null) {
        val text = ScreenTranslation.prepareText(rawText)
        if (text.isEmpty()) {
            toast("请选择文字")
            return
        }
        val slot = textTranslateSlot ?: return
        val seq = ++translateSeq
        val pair = ScreenTranslation.guessLangPair(text, overrideDest)
        val selBounds = screen.selectedTextBounds()
        slot.removeAllViews()
        slot.visibility = View.VISIBLE
        slot.addView(translationLoadingCard(pair.label))
        updateAvoid()

        SilentTranslateClient.translateAsync(
            context = context,
            rawText = text,
            overrideDest = overrideDest,
            log = host::log,
        ) { outcome ->
            if (closing || seq != translateSeq) return@translateAsync
            slot.removeAllViews()
            when (outcome) {
                is SilentTranslateClient.Outcome.Success -> {
                    val actualPair = ScreenTranslation.LangPair(outcome.srcLang, outcome.destLang)
                    if (translateOverlayEnabled && selBounds != null) {
                        screen.setTranslationOverlay(selBounds, outcome.translated)
                    } else {
                        screen.clearTranslationOverlay()
                    }
                    slot.addView(
                        translationResultCard(
                            rawText = text,
                            translated = outcome.translated,
                            pair = actualPair,
                            selBounds = selBounds,
                        ),
                    )
                    host.log(Log.INFO, "窗内翻译完成 ${actualPair.label}（${outcome.translated.length} 字）")
                }
                is SilentTranslateClient.Outcome.NeedCta -> {
                    screen.clearTranslationOverlay()
                    slot.addView(translationErrorCard(rawText, "需先在系统翻译里同意服务协议，或改用系统词典"))
                }
                is SilentTranslateClient.Outcome.Failure -> {
                    screen.clearTranslationOverlay()
                    slot.addView(translationErrorCard(rawText, outcome.reason))
                }
            }
            updateAvoid()
        }
    }

    private fun translationLoadingCard(langLabel: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (14 * d).toInt()
        setPadding(pad, (12 * d).toInt(), pad, (12 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(palette.pillBg)
        }
        addView(
            ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(0xFF3482FF.toInt())
            },
            LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()).apply { marginEnd = (10 * d).toInt() },
        )
        addView(
            TextView(context).apply {
                text = "正在翻译（$langLabel）…"
                setTextColor(palette.inkSecondary)
                textSize = 13f
                isSingleLine = true
            },
        )
    }

    private fun translationErrorCard(rawText: String, reason: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (14 * d).toInt()
        setPadding(pad, (10 * d).toInt(), pad, (10 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(palette.pillBg)
        }
        addView(
            TextView(context).apply {
                text = reason
                setTextColor(palette.inkSecondary)
                textSize = 12.5f
                maxLines = 2
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            TextView(context).apply {
                text = "系统词典"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(0xFF3482FF.toInt())
                }
                setOnClickListener {
                    if (host.translate(context, rawText)) close() else toast("系统词典不可用")
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (10 * d).toInt()
            },
        )
    }

    private fun translationResultCard(
        rawText: String,
        translated: String,
        pair: ScreenTranslation.LangPair,
        selBounds: RectF?,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padH = (14 * d).toInt()
        setPadding(padH, (10 * d).toInt(), padH, (12 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(palette.pillBg)
        }

        // 顶部工具行：点击语种循环切换目标语言；右侧提供原位覆盖开关、复制、关闭
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun chip(label: String, active: Boolean = false, onClick: () -> Unit): TextView = TextView(context).apply {
            text = label
            textSize = 11.5f
            setTextColor(if (active) Color.WHITE else palette.ink)
            typeface = if (active) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(if (active) 0xFF3482FF.toInt() else palette.sheetBg)
            }
            setOnClickListener { onClick() }
        }

        top.addView(
            TextView(context).apply {
                text = "${pair.label} ▾"
                setTextColor(0xFF3482FF.toInt())
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                setOnClickListener {
                    val idx = ScreenTranslation.TARGET_LANGS.indexOf(pair.dest.lowercase())
                    val nextDest = ScreenTranslation.TARGET_LANGS[(idx + 1) % ScreenTranslation.TARGET_LANGS.size]
                    startInWindowTranslate(rawText, overrideDest = nextDest)
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (selBounds != null) {
            top.addView(
                chip("原位", active = translateOverlayEnabled) {
                    translateOverlayEnabled = !translateOverlayEnabled
                    if (translateOverlayEnabled) screen.setTranslationOverlay(selBounds, translated)
                    else screen.clearTranslationOverlay()
                    // 刷新卡片高亮状态
                    textTranslateSlot?.let { s ->
                        s.removeAllViews()
                        s.addView(translationResultCard(rawText, translated, pair, selBounds))
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (6 * d).toInt()
                },
            )
        }
        top.addView(
            chip("复制") {
                try {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("translation", translated))
                    confirm("已复制译文", true)
                } catch (_: Throwable) {
                    toast("复制失败")
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6 * d).toInt()
            },
        )
        top.addView(
            TextView(context).apply {
                text = "✕"
                setTextColor(palette.inkSecondary)
                textSize = 13f
                setPadding((6 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
                setOnClickListener { clearTranslationCard() }
            },
        )
        addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 译文正文（限高可滚动）
        val body = TextView(context).apply {
            text = translated
            setTextColor(palette.ink)
            textSize = 14.5f
            setLineSpacing(2 * d, 1.15f)
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(context).apply {
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * d).toInt()
            },
        )
        scroll.post {
            val maxH = (132 * d).toInt()
            if (scroll.height > maxH) {
                scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = maxH }
            }
        }
    }

    private fun showQrBar(detection: LocalQrScanner.Detection) {
        setSheet(qrBar(detection))
    }

    private fun showTextBar() {
        val bar = textBar ?: textBar().also { textBar = it }
        refreshTextEntity()
        setSheet(bar)
    }

    /** 根据当前选中文字刷新实体直达条（118 平台口令/链接、邮箱、电话），无实体时隐藏。 */
    private fun refreshTextEntity() {
        val slot = textEntitySlot ?: return
        val action = ScreenEntityResolver.resolve(screen.selectedText())
        slot.removeAllViews()
        if (action == null) {
            slot.visibility = View.GONE
            return
        }
        slot.visibility = View.VISIBLE
        slot.addView(entityBanner(action))
    }

    private fun entityBanner(action: QrJumpResolver.Action): View {
        val accent = LocalQrScanner.accentColor(action.accent)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (14 * d).toInt()
            val padV = (10 * d).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(palette.pillBg)
            }
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(accent)
                    }
                },
                LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt()).apply {
                    marginEnd = (10 * d).toInt()
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = action.title
                            setTextColor(palette.ink)
                            textSize = 13.5f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            isSingleLine = true
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = action.subtitle
                            setTextColor(palette.inkSecondary)
                            textSize = 11.5f
                            isSingleLine = true
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val actionBtn = TextView(context).apply {
                text = action.actionLabel
                setTextColor(Color.WHITE)
                textSize = 12.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(accent)
                }
                setOnClickListener {
                    if (action.canLaunch && LocalQrScanner.launch(context, action)) {
                        close()
                    } else if (LocalQrScanner.copy(context, action.copyText)) {
                        confirm("已复制", true)
                    } else {
                        toast("无法打开此内容")
                    }
                }
            }
            addView(
                actionBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (10 * d).toInt()
                },
            )
            setOnClickListener { actionBtn.performClick() }
        }
    }

    /** 换面板内容；null 收起。 */
    private fun setSheet(content: View?) {
        if (content == null) {
            if (!sheetShown) return
            sheetShown = false
            sheet.animate().cancel()
            updateAvoid()
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(SHEET_OUT_MS).setInterpolator(SHEET_OUT)
                .withEndAction {
                    // 收的过程中又选了东西就不动它
                    if (!sheetShown) {
                        sheetContent?.let { sheet.removeView(it) }
                        sheetContent = null
                        sheet.visibility = View.GONE
                    }
                }.start()
            return
        }
        val oldHeight = if (sheetShown) sheet.height else 0
        if (sheetContent !== content) {
            sheetContent?.let { sheet.removeView(it) }
            (content.parent as? ViewGroup)?.removeView(content)
            sheet.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sheetContent = content
            grip.bringToFront()
        }
        if (!sheetShown) {
            sheetShown = true
            sheet.animate().cancel()
            if (sheet.visibility != View.VISIBLE) {
                // 第一次出现还没布局过，先量出高度，从屏幕外正好一个面板高的位置滑上来
                sheet.measure(
                    View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                sheet.translationY = sheet.measuredHeight.toFloat()
                sheet.visibility = View.VISIBLE
            }
            settleSheet(sheet.measuredHeight.takeIf { sheet.height == 0 } ?: sheet.height)
            return
        }
        // 已在显示、内容变高（加载中 → 识图卡片）：顶边从原来的位置升上去，而不是一下跳高
        sheet.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                val grow = v.height - oldHeight
                if (grow <= 0 || !sheetShown) return
                v.animate().cancel()
                v.translationY += grow.toFloat()
                settleSheet(v.height)
            }
        })
    }

    /** 收起时面板往下移出屏幕的距离；[height] 是面板高度。 */
    private fun restOffset(height: Int): Float =
        if (collapsed) (height - COLLAPSED_DP * d).coerceAtLeast(0f) else 0f

    /** 面板滑到当前收起 / 展开状态该在的位置，截图同时让位。 */
    private fun settleSheet(height: Int = sheet.height) {
        sheet.animate().cancel()
        sheet.animate().translationY(restOffset(height)).setDuration(SHEET_IN_MS).setInterpolator(SHEET_IN).start()
        updateAvoid(height)
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        if (sheetShown) settleSheet()
    }

    /**
     * 选中项被面板挡住时，整张截图往上让，正好露在面板上方；没被挡住就回原位。
     * 按面板**要停到的位置**算，不按动画中途的位置；手指还按着时不动，抬手再算。
     */
    private fun updateAvoid(height: Int = sheet.height) {
        if (screen.touching) return
        val sel = screen.selectionBounds()
        var target = 0f
        if (sel != null && sheetShown && root.height > 0) {
            val sheetTop = root.height - (height - restOffset(height))
            val need = sheetTop - AVOID_MARGIN_DP * d - sel.bottom
            if (need < 0f) {
                // 不能让选中项的顶部顶出状态栏
                val room = sel.top - statusBarHeight() - AVOID_MARGIN_DP * d
                target = maxOf(need, -room.coerceAtLeast(0f))
            }
        }
        if (screen.translationY == target) return
        screen.animate().cancel()
        screen.animate().translationY(target).setDuration(SHEET_IN_MS).setInterpolator(SHEET_IN).start()
    }

    /**
     * 面板外壳：上下拖动在收起 / 展开之间切换，点把手一带也切换。
     * 横向或很小的移动不拦截，按钮照常能点。
     */
    @SuppressLint("ViewConstructor")
    private inner class SheetLayout(context: Context) : FrameLayout(context) {
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downY = 0f
        private var downX = 0f
        private var startT = 0f
        private var dragging = false
        private var tracker: android.view.VelocityTracker? = null

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startT = translationY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(e.rawX - downX)) dragging = true
                }
            }
            return dragging
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val t = tracker ?: android.view.VelocityTracker.obtain().also { tracker = it }
            t.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startT = translationY; dragging = false; animate().cancel() }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && kotlin.math.abs(dy) > slop) dragging = true
                    if (dragging) translationY = (startT + dy).coerceIn(0f, (height - COLLAPSED_DP * d).coerceAtLeast(0f))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        t.computeCurrentVelocity(1000)
                        val v = t.yVelocity
                        val max = (height - COLLAPSED_DP * d).coerceAtLeast(1f)
                        setCollapsed(if (kotlin.math.abs(v) > FLING_DP * d) v > 0 else translationY > max / 2)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && e.y < GRIP_ZONE_DP * d) {
                        // 点把手一带：切换
                        setCollapsed(!collapsed)
                    }
                    dragging = false
                    t.recycle(); tracker = null
                }
            }
            return true
        }
    }

    /** 等源位图时的面板内容。取图通常一两百毫秒，转圈晚一点才淡入，免得一闪而过。 */
    private fun loadingContent(): View = FrameLayout(context).apply {
        minimumHeight = (LOADING_DP * d).toInt()
        // 收起时也看得见：放在第一排按钮的位置
        setPadding(0, (HOST_ROW_TOP_DP * d).toInt() - (12 * d).toInt(), 0, 0)
        addView(ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.spinner)
            alpha = 0f
            animate().alpha(1f).setStartDelay(SPINNER_DELAY_MS).setDuration(SHEET_IN_MS).start()
        }, FrameLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt(), Gravity.CENTER))
    }

    /**
     * 先向来源应用要这张图的源位图（应用解码进内存的那张，不带叠加文字、通常比屏幕上大），
     * 最多等 [GlobalRecognitionHost] 的时限；拿不到就用截图裁图。等待期间选中框已经画出来了。
     * 手动自由画框或拉手柄微调后 [preferSource] 为 false，直接按精确选区裁图。
     */
    private fun showCard(rect: RectF, preferSource: Boolean) {
        pendingImage = rect
        if (!preferSource) {
            showCardWith(rect, result = null)
            return
        }
        setSheet(loadingContent())
        host.requestSourceImage(context, rect) { res ->
            main.post {
                if (pendingImage !== rect || closing) { res.bitmap?.recycle(); return@post }
                pendingImage = null
                host.log(Log.INFO, if (res.bitmap != null) "选图用 ${res.detail}" else "选图用截图裁图：${res.detail}")
                showCardWith(rect, res)
            }
        }
    }

    private fun showCardWith(rect: RectF, result: SourceImageClient.Result?) {
        val source = result?.bitmap
        val crop = source ?: crop(rect)
        if (crop == null) { setSheet(null); return }
        if (!swappedHost) {
            savedHostBitmap = host.hostBitmap()
            swappedHost = true
        }
        if (!host.setHostBitmap(crop)) {
            toast("识图卡片不可用")
            setSheet(null)
            return
        }
        ourCrop = crop
        val c = host.newImageCard(context)
        if (c == null) {
            toast("识图卡片不可用")
            setSheet(null)
            return
        }
        compactCard(c)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val qrSlot = FrameLayout(context)
        val header = imageHeaderBar(crop, result, qrSlot)
        container.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            c,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setSheet(container)
        closeAfterActions(c)
        // 对选定/微调后的高分裁图做一次局部二维码精扫（补齐整屏初扫可能漏掉的超小码/反色码）
        scanCroppedQrAsync(crop, qrSlot)
    }

    /** 图片面板顶部的轻量信息与动作条：显示实况/原图/动图格式与尺寸、原文件无损保存、一键复制图片与局部二维码直达。 */
    private fun imageHeaderBar(crop: Bitmap, result: SourceImageClient.Result?, qrSlot: FrameLayout): View {
        val rawBytes = result?.rawBytes
        val liveVideo = result?.liveVideoBytes
        val isLive = rawBytes != null && liveVideo != null
        val mime = result?.mime
        val animated = result?.animated == true && rawBytes != null
        val fmtTag = when (mime) {
            "image/gif" -> "GIF"
            "image/webp" -> "WebP"
            "image/avif" -> "AVIF"
            "image/heic" -> "HEIC"
            "image/heif" -> "HEIF"
            "image/jpeg" -> "JPEG"
            "image/png" -> "PNG"
            else -> null
        }
        val label = when {
            isLive -> "实况照片 ${crop.width}×${crop.height}"
            animated -> "${fmtTag ?: "动图"} 动图 ${crop.width}×${crop.height}"
            rawBytes != null -> "原图${fmtTag?.let { " $it" }.orEmpty()} ${crop.width}×${crop.height}"
            result?.bitmap != null -> "原图 ${crop.width}×${crop.height}"
            else -> "选区 ${crop.width}×${crop.height}"
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (20 * d).toInt()
            setPadding(padH, (10 * d).toInt(), padH, (2 * d).toInt())

            if (isLive) {
                // 实况照片双环徽标点
                addView(
                    View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0x33FF9F0A)
                            setStroke((1.8f * d).toInt().coerceAtLeast(2), 0xFFFF9F0A.toInt())
                        }
                    },
                    LinearLayout.LayoutParams((12 * d).toInt(), (12 * d).toInt()).apply {
                        marginEnd = (8 * d).toInt()
                    },
                )
            } else if (animated && rawBytes != null) {
                runCatching {
                    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(rawBytes)))
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                        activeAnimatedPreview = drawable
                        addView(
                            ImageView(context).apply {
                                setImageDrawable(drawable)
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                clipToOutline = true
                                outlineProvider = object : android.view.ViewOutlineProvider() {
                                    override fun getOutline(v: View, o: android.graphics.Outline) {
                                        o.setRoundRect(0, 0, v.width, v.height, 8 * d)
                                    }
                                }
                            },
                            LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply {
                                marginEnd = (10 * d).toInt()
                            },
                        )
                    }
                }
            }

            addView(
                TextView(context).apply {
                    text = label
                    setTextColor(palette.inkSecondary)
                    textSize = 12f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                qrSlot,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (8 * d).toInt()
                },
            )
            if (isLive && rawBytes != null && liveVideo != null) {
                addView(
                    TextView(context).apply {
                        text = "存实况照片"
                        setTextColor(Color.WHITE)
                        textSize = 12.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                        background = GradientDrawable().apply {
                            cornerRadius = 16 * d
                            setColor(0xFFFF9F0A.toInt())
                        }
                        setOnClickListener {
                            saveMotionPhotoToGallery(rawBytes, liveVideo)
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (8 * d).toInt()
                    },
                )
            } else if (rawBytes != null) {
                val saveLabel = if (animated) "存原动图" else "存原图"
                addView(
                    TextView(context).apply {
                        text = saveLabel
                        setTextColor(Color.WHITE)
                        textSize = 12.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                        background = GradientDrawable().apply {
                            cornerRadius = 16 * d
                            setColor(0xFF3482FF.toInt())
                        }
                        setOnClickListener {
                            saveRawMediaToGallery(rawBytes, mime, animated)
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (8 * d).toInt()
                    },
                )
            }
            addView(
                TextView(context).apply {
                    text = "复制图片"
                    setTextColor(palette.ink)
                    textSize = 12.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 16 * d
                        setColor(palette.pillBg)
                    }
                    setOnClickListener {
                        PortalImageActions.copyBitmapToClipboard(context, crop) { ok ->
                            if (closing) return@copyBitmapToClipboard
                            if (ok) {
                                confirm("已复制图片", true)
                                host.log(Log.INFO, "已复制选区图片 ${crop.width}x${crop.height}")
                            } else {
                                toast("复制图片失败")
                            }
                        }
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** 把主静图 JPEG 与短视频 MP4 拼成标准 Google/MIUI Motion Photo 实况照片写入系统图库。 */
    private fun saveMotionPhotoToGallery(jpegBytes: ByteArray, videoBytes: ByteArray) {
        val name = PortalMediaContract.savedName(System.currentTimeMillis(), PortalMediaContract.Format.JPEG)
        Thread({
            val saved = try {
                val prefix = PortalMotionPhoto.jpegPrefix(jpegBytes, videoBytes.size.toLong(), 0L)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tsuzuri")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert MediaStore 返回 null")
                try {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(prefix)
                        os.write(videoBytes)
                    } ?: throw IllegalStateException("openOutputStream 返回 null")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    true
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
            } catch (t: Throwable) {
                host.log(Log.WARN, "保存实况照片失败：$t")
                false
            }
            main.post {
                if (closing) return@post
                if (saved) {
                    screen.clearSelection()
                    confirm("实况照片已保存", true)
                    host.log(Log.INFO, "已合成保存实况照片 $name (img=${jpegBytes.size}B + vid=${videoBytes.size}B)")
                } else {
                    toast("保存实况照片失败")
                }
            }
        }, "fp-gr-save-live").start()
    }

    /** 把原文件字节（GIF / 动态 WebP / HEIF・AVIF / 无损原图）原样写入系统图库，不经过重压拍扁。 */
    private fun saveRawMediaToGallery(bytes: ByteArray, mime: String?, animated: Boolean) {
        val fmt = PortalMediaContract.sniff(bytes)
            ?: when (mime) {
                "image/gif" -> PortalMediaContract.Format.GIF
                "image/webp" -> PortalMediaContract.Format.WEBP
                "image/avif" -> PortalMediaContract.Format.AVIF
                "image/heic" -> PortalMediaContract.Format.HEIC
                "image/heif" -> PortalMediaContract.Format.HEIF
                "image/png" -> PortalMediaContract.Format.PNG
                else -> PortalMediaContract.Format.JPEG
            }
        val name = PortalMediaContract.savedName(System.currentTimeMillis(), fmt)
        Thread({
            val saved = try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, fmt.mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tsuzuri")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert MediaStore 返回 null")
                try {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("openOutputStream 返回 null")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    true
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
            } catch (t: Throwable) {
                host.log(Log.WARN, "保存原媒体失败：$t")
                false
            }
            main.post {
                if (closing) return@post
                if (saved) {
                    screen.clearSelection()
                    confirm(if (animated) "原动图已保存" else "原图已保存", true)
                    host.log(Log.INFO, "已无损保存原媒体 $name (${fmt.mime}, ${bytes.size}B)")
                } else {
                    toast("保存原文件失败")
                }
            }
        }, "fp-gr-save-raw").start()
    }

    private fun scanCroppedQrAsync(crop: Bitmap, qrSlot: FrameLayout) {
        if (crop.isRecycled) return
        val copy = runCatching { crop.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        val slotRef = java.lang.ref.WeakReference(qrSlot)
        Thread({
            val det = try {
                LocalQrScanner.scanFirst(copy)
            } catch (_: Throwable) {
                null
            } finally {
                if (!copy.isRecycled) copy.recycle()
            }
            if (det == null) return@Thread
            main.post {
                val slot = slotRef.get() ?: return@post
                if (closing || !slot.isAttachedToWindow || ourCrop !== crop) return@post
                val action = det.action
                val accent = LocalQrScanner.accentColor(action.accent)
                slot.removeAllViews()
                slot.addView(
                    TextView(context).apply {
                        text = if (action.canLaunch) action.actionLabel else "复制码内容"
                        setTextColor(Color.WHITE)
                        textSize = 12.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                        background = GradientDrawable().apply {
                            cornerRadius = 16 * d
                            setColor(accent)
                        }
                        setOnClickListener {
                            if (action.canLaunch && LocalQrScanner.launch(context, action)) {
                                close()
                            } else if (LocalQrScanner.copy(context, action.copyText)) {
                                confirm("已复制", true)
                            } else {
                                toast("无法打开此二维码")
                            }
                        }
                    },
                )
            }
        }, "fp-gr-crop-qr").start()
    }

    /**
     * 不要卡片里的大图预览：选中的那块已经在屏幕上框出来了。而且预览区在布局里是 `0dp + weight=1`，
     * 放进自适应高度的面板会被撑到几乎整屏（2026-10-27 真机用户反馈「圈选的图全屏展示」）。
     * 只藏预览图本身；保存按钮若还在这个区域里（图片卡片按钮没开时），区域收成按钮大小照样能点。
     */
    private fun compactCard(card: View) {
        card.background = null
        val res = context.resources
        fun id(name: String) = res.getIdentifier(name, "id", context.packageName)
        card.findViewById<View>(id("capture_image"))?.visibility = View.GONE
        card.findViewById<View>(id("capture_image_null"))?.visibility = View.GONE
        card.findViewById<View>(id("capture_image_group"))?.let { g ->
            g.background = null
            (g.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
                g.layoutParams = lp
            }
        }
    }

    /**
     * 卡片里的按钮多数会拉起别的界面（扫码、识物、编辑、分享）：点下后关窗。
     *
     * 保存例外：留在本界面，等真正存进相册后清除选中、收起卡片。
     * 不能点下就清：宿主可能先弹「普通 / 超清」二选一，收卡片会把这个弹窗的来源一起收掉。
     * 结果提示：宿主的成功 / 失败 toast 被截下，改在本界面中央显示（[onSaveResult]）；截不到时仍是宿主自己弹。
     * 保存按钮可能是宿主原来的 `edit_image`，也可能是 [PortalImageActions] 换上的那一格（tag 区分）。
     */
    private fun closeAfterActions(card: View) {
        val saveId = context.resources.getIdentifier("edit_image", "id", context.packageName)
        fun walk(v: View) {
            if (v.hasOnClickListeners()) {
                val isSave = (saveId != 0 && v.id == saveId) || v.tag == SAVE_CELL_TAG
                v.setOnTouchListener { view, e ->
                    if (e.actionMasked == MotionEvent.ACTION_UP && view.isEnabled &&
                        e.x in 0f..view.width.toFloat() && e.y in 0f..view.height.toFloat()
                    ) {
                        if (isSave) watchSave() else main.postDelayed(::close, CLOSE_AFTER_ACTION_MS)
                    }
                    false
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(card)
    }

    private var saveWatch: ContentObserver? = null
    private val saveWatchTimeout = Runnable { stopSaveWatch() }

    /** 盯相册：保存后有新图写入就清除选中。超过 [SAVE_WATCH_MS] 没动静（取消了二选一、保存失败）就不管了。 */
    private fun watchSave() {
        stopSaveWatch()
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                stopSaveWatch()
                if (closing) return
                host.log(Log.INFO, "保存完成，清除选中")
                screen.clearSelection()
            }
        }
        try {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (t: Throwable) {
            host.log(Log.WARN, "监听相册失败，保存后不自动清除选中：$t")
            return
        }
        saveWatch = observer
        main.postDelayed(saveWatchTimeout, SAVE_WATCH_MS)
    }

    private fun stopSaveWatch() {
        main.removeCallbacks(saveWatchTimeout)
        saveWatch?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        saveWatch = null
    }

    /**
     * 宿主保存完成（[GlobalRecognitionHost] 截下了它的成功 / 失败 toast）：在本界面中央给结果，
     * 成功时清除选中、收起卡片（与相册监听同一个效果，这条更准，到了就不必再等监听）。[text] 用宿主自己的文案。
     */
    fun onSaveResult(success: Boolean, text: String) {
        if (closing) return
        stopSaveWatch()
        host.log(Log.INFO, "保存${if (success) "完成" else "失败"}（宿主回调）")
        if (success) screen.clearSelection()
        confirm(text, success)
    }

    private var confirmView: View? = null

    /**
     * 屏幕中央的结果提示（参照 Flyme 识屏「已保存到图库」，图形自绘）：白色圆角块，
     * 成功时左边一个蓝色对勾。淡入并从 0.9 放大到 1，停留后淡出。系统 toast 压在暗幕上不显眼，也不像这个界面的一部分。
     */
    private fun confirm(text: String, ok: Boolean) {
        confirmView?.let { it.animate().cancel(); root.removeView(it) }
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (20 * d).toInt()
            val padV = (14 * d).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(palette.confirmBg)
                setStroke((1 * d).toInt().coerceAtLeast(1), palette.confirmStroke)
                cornerRadius = 16 * d
            }
            elevation = 8 * d
            if (ok) addView(View(context).apply { background = CheckDrawable(0xFF3482FF.toInt(), d) },
                LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply { marginEnd = (10 * d).toInt() })
            addView(TextView(context).apply {
                this.text = text
                setTextColor(palette.ink)
                textSize = 15f
                isSingleLine = true
            })
            alpha = 0f
            scaleX = CONFIRM_FROM_SCALE
            scaleY = CONFIRM_FROM_SCALE
        }
        confirmView = v
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(CONFIRM_IN_MS).setInterpolator(SHEET_IN).withEndAction {
            v.animate().alpha(0f).setStartDelay(CONFIRM_HOLD_MS).setDuration(CONFIRM_OUT_MS).withEndAction {
                root.removeView(v)
                if (confirmView === v) confirmView = null
            }.start()
        }.start()
    }

    /** 蓝底圆 + 白色对勾。 */
    private class CheckDrawable(private val color: Int, private val d: Float) : android.graphics.drawable.Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@CheckDrawable.color }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = minOf(b.width(), b.height()) / 2f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            canvas.drawCircle(cx, cy, r, fill)
            path.reset()
            path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
            path.lineTo(cx - r * 0.1f, cy + r * 0.32f)
            path.lineTo(cx + r * 0.45f, cy - r * 0.3f)
            canvas.drawPath(path, tick)
        }

        override fun setAlpha(alpha: Int) { fill.alpha = alpha; tick.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun crop(r: RectF): Bitmap? {
        val b = shot.bitmap
        if (b.isRecycled) return null
        val box = Rect(r.left.toInt().coerceIn(0, b.width - 1), r.top.toInt().coerceIn(0, b.height - 1), r.right.toInt().coerceIn(1, b.width), r.bottom.toInt().coerceIn(1, b.height))
        if (box.width() <= 0 || box.height() <= 0) return null
        return try {
            val out = Bitmap.createBitmap(b, box.left, box.top, box.width(), box.height())
            // 宿主会长期持有这张（直到下次识图），不能和窗口的截图是同一个对象
            if (out === b) b.copy(Bitmap.Config.ARGB_8888, false) else out
        } catch (t: Throwable) {
            host.log(Log.WARN, "裁剪选区失败：$t")
            null
        }
    }

    /** 宿主位图仍是我们放的那张才还原；晚一点做，扫码等动作可能还在读它。 */
    private fun restoreHostBitmap() {
        if (!swappedHost) return
        val mine = ourCrop
        val saved = savedHostBitmap
        main.postDelayed({
            if (host.hostBitmap() === mine) host.setHostBitmap(saved?.takeIf { !it.isRecycled })
        }, RESTORE_DELAY_MS)
        swappedHost = false
    }

    // ── 底栏 ────────────────────────────────────────────────────────────

    /**
     * 二维码直达面板：左栏展示平台/类型标题与清洗后的摘要，右侧提供「直达」与「复制」按钮。
     */
    private fun qrBar(detection: LocalQrScanner.Detection): View {
        val action = detection.action
        val accent = LocalQrScanner.accentColor(action.accent)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (20 * d).toInt()
            setPadding(padH, (18 * d).toInt(), padH, (14 * d).toInt())

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // 品牌色圆点，与超级岛语言统一
            header.addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(accent)
                    }
                },
                LinearLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt()).apply {
                    marginEnd = (10 * d).toInt()
                },
            )
            header.addView(
                TextView(context).apply {
                    text = action.title
                    setTextColor(palette.ink)
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(
                TextView(context).apply {
                    text = action.subtitle
                    setTextColor(palette.inkSecondary)
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (6 * d).toInt()
                },
            )

            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            fun pill(label: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(if (primary) Color.WHITE else palette.ink)
                typeface = if (primary) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                gravity = Gravity.CENTER
                val ph = (18 * d).toInt()
                val pv = (9 * d).toInt()
                setPadding(ph, pv, ph, pv)
                background = GradientDrawable().apply {
                    cornerRadius = 20 * d
                    setColor(if (primary) accent else palette.pillBg)
                }
                setOnClickListener { onClick() }
            }

            if (action.canLaunch) {
                buttons.addView(
                    pill("复制", primary = false) {
                        if (LocalQrScanner.copy(context, action.copyText)) {
                            confirm("已复制", true)
                            host.log(Log.INFO, "复制二维码内容 ${action.copyText.length} 字")
                        } else {
                            toast("复制失败")
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (10 * d).toInt()
                    },
                )
                buttons.addView(
                    pill(action.actionLabel, primary = true) {
                        if (LocalQrScanner.launch(context, action)) {
                            close()
                        } else if (LocalQrScanner.copy(context, action.copyText)) {
                            confirm("未找到应用，已复制链接", true)
                        } else {
                            toast("未找到可打开此链接的应用")
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            } else {
                buttons.addView(
                    pill(action.actionLabel, primary = true) {
                        if (LocalQrScanner.copy(context, action.copyText)) {
                            confirm("已复制", true)
                            host.log(Log.INFO, "复制二维码内容 ${action.copyText.length} 字")
                        } else {
                            toast("复制失败")
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }

            addView(
                buttons,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (14 * d).toInt()
                },
            )
        }
    }

    /**
     * 文字操作栏。按钮规格照抄传送门识图卡片那排（`card_view_taplus_recognition_image_expanded.xml`）：
     * 字号 13.82sp、纯黑、图标槽 21.82dp、图标与文字间距 5.1dp、行上下内边距 25.1 / 21.82dp，
     * 选字和选图切换时两种面板的按钮看起来是一套。
     */
    private fun textBar(): View {
        val ink = palette.ink
        val iconSide = (HOST_ICON_SLOT_DP * d).toInt()
        fun btn(text: String, icon: PortalImageActions.PortalIcon, onClick: () -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                ImageView(context).apply { setImageDrawable(PortalImageActions.PortalStrokeIcon(icon, ink, iconSide)) },
                LinearLayout.LayoutParams(iconSide, iconSide).apply { marginEnd = (HOST_ICON_GAP_DP * d).toInt() },
            )
            addView(TextView(context).apply {
                this.text = text
                setTextColor(ink)
                textSize = HOST_TEXT_SP
                isSingleLine = true
            })
            setOnClickListener { onClick() }
        }
        fun withText(block: (String) -> Unit) {
            val text = screen.selectedText()
            if (text.isEmpty()) toast("请选择文字") else block(text)
        }
        val row = GapRow(context, (HOST_BUTTON_GAP_DP * d).toInt()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // 与识图卡片同样落在面板外壳的内边距之内，两种面板的按钮行离顶边一样远
            setPadding(0, (HOST_ROW_TOP_DP * d).toInt(), 0, (HOST_ROW_BOTTOM_DP * d).toInt())
            val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            addView(btn("全选", PortalImageActions.PortalIcon.SELECT_ALL) { screen.selectAll(); refreshTextEntity() }, lp())
            addView(btn("复制", PortalImageActions.PortalIcon.COPY) {
                withText { text ->
                    try {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("text", text))
                        confirm("已复制", true)
                        host.log(Log.INFO, "复制文字 ${text.length} 字")
                        // 留在本界面：复制不会跳走，用户常要连着复制几段
                        screen.clearSelection()
                    } catch (t: Throwable) {
                        host.log(Log.WARN, "写剪贴板失败：$t")
                        toast("复制失败，可以试试分享")
                    }
                }
            }, lp())
            addView(btn("分享", PortalImageActions.PortalIcon.SHARE) {
                withText { text ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, "分享文字").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        close()
                    } catch (t: Throwable) {
                        host.log(Log.WARN, "分享文字失败：$t")
                    }
                }
            }, lp())
            addView(btn("翻译", PortalImageActions.PortalIcon.TRANSLATE) {
                withText { text ->
                    startInWindowTranslate(text)
                }
            }, lp())
        }
        val entitySlot = FrameLayout(context).apply {
            val padH = (16 * d).toInt()
            setPadding(padH, (12 * d).toInt(), padH, 0)
            visibility = View.GONE
        }
        val translateSlot = FrameLayout(context).apply {
            val padH = (16 * d).toInt()
            setPadding(padH, (10 * d).toInt(), padH, 0)
            visibility = View.GONE
        }
        textEntitySlot = entitySlot
        textTranslateSlot = translateSlot
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(translateSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(entitySlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun sheetBackground() = GradientDrawable().apply {
        setColor(palette.sheetBg)
        setStroke((1 * d).toInt().coerceAtLeast(1), palette.sheetStroke)
        val r = 24 * d
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    private fun closeButton(): View = TextView(context).apply {
        text = "✕"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 16f
        contentDescription = "关闭"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66000000)
        }
        setOnClickListener { close() }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else (32 * d).toInt()
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 按钮行：按内容宽排、整排居中。传送门那排是三格、间距 [maxGap]（56dp）；
     * 我们四格，按「和那排一样宽」均分间距，屏幕放不下再缩，而不是撑出屏幕或挤成等分格。
     */
    private class GapRow(context: Context, private val maxGap: Int) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cells = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            if (cells.size > 1 && avail > 0) {
                val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                val content = cells.sumOf { it.measure(unspecified, heightMeasureSpec); it.measuredWidth }
                // 整排宽度对齐识图卡片那排（三格 + 两个 56dp）：选字、选图切换时按钮行一样宽。
                // 直接用 56dp 排四格太散（真机：几乎撑满整行），四格各让一点，但不小于 MIN_GAP_DP
                val hostSpan = content * HOST_CELLS / cells.size + maxGap * (HOST_CELLS - 1)
                val minGap = (MIN_GAP_DP * resources.displayMetrics.density).toInt()
                val gap = ((hostSpan - content) / (cells.size - 1)).coerceIn(minGap, maxGap)
                    .coerceAtMost(((avail - content) / (cells.size - 1)).coerceAtLeast(0))
                cells.forEachIndexed { i, v ->
                    val lp = v.layoutParams as LayoutParams
                    lp.marginEnd = if (i < cells.size - 1) gap else 0
                    // LinearLayout 量的是 left/rightMargin；不立即换算，改的 marginEnd 这一轮不生效（真机：间距全为 0）
                    lp.resolveLayoutDirection(layoutDirection)
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private companion object {
            /** 识图卡片那排的格数。 */
            const val HOST_CELLS = 3
            const val MIN_GAP_DP = 24f
        }
    }

    private class Root(context: Context) : FrameLayout(context) {
        var onBack: (() -> Unit)? = null
        var onRotate: ((Int) -> Unit)? = null

        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            onRotate?.invoke(newConfig.orientation)
        }

        init {
            isFocusableInTouchMode = true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    // ── 整屏画布 ────────────────────────────────────────────────────────

    /**
     * 坐标约定：字框与图片区域都在**截图坐标**（= 屏幕坐标）；窗口若不在 (0,0)，绘制和触摸都减去窗口在屏幕上的偏移。
     * 文字选区是字序上的闭区间 [selStart, selEnd]，-1 表示未选；图片选区是 [image]。两者互斥。
     */
    @SuppressLint("ViewConstructor")
    private inner class ScreenView(context: Context) : View(context) {
        var onSelection: ((RectF?, Boolean, LocalQrScanner.Detection?, Boolean) -> Unit)? = null

        /** 抬手（含取消）时回调；手指按着期间 [touching] 为 true。 */
        var onTouchEnd: (() -> Unit)? = null
        var touching = false
            private set

        private var glyphs: List<PortalOcrSelect.Glyph> = emptyList()
        private var lines: List<RectF> = emptyList()
        /** 与 [lines] 一一对应的行号（Glyph.line）。 */
        private var lineIds: List<Int> = emptyList()

        /**
         * 选中过渡：0→1 走 [SELECT_MS]。选中标记在前 [SELECT_FADE_MS] 里淡入，
         * 图片同时有一下 1.0 → 1.02 → 1.0 的轻放大；其余可选区域跟着降到 [OTHERS_DIM] 的亮度。
         */
        private var focus = 1f
        private val focusAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SELECT_MS
            interpolator = LinearInterpolator()
            addUpdateListener { focus = it.animatedValue as Float; invalidate() }
        }
        private var regions: List<RectF> = emptyList()
        private var qrDetections: List<LocalQrScanner.Detection> = emptyList()
        private var selectedQr: LocalQrScanner.Detection? = null
        private var ready = false
        private var selStart = -1
        private var selEnd = -1
        private var image: RectF? = null
        private var dragRect: RectF? = null
        private var transOverlayBounds: RectF? = null
        private var transOverlayText: String? = null
        private val transBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (palette.dark) 0xF01E2025.toInt() else 0xF2FFFFFF.toInt()
            setShadowLayer(8 * d, 0f, 2 * d, 0x55000000)
        }
        private val transStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * d
            color = if (palette.dark) 0x33FFFFFF else 0x22000000
        }
        private val transTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.ink
            textSize = 14f * d
        }

        private var ox = 0f
        private var oy = 0f
        private var dim = 0f

        /**
         * 识别过渡动效：默认流光扫描（[AuroraRecognitionFx]），可在设置里切换回经典星云粒子（[RecognitionFx]）。
         * 文字和图片跟着 [RecognitionEffect.wave] 顺势点亮，亮起时先轻提亮再回落。
         */
        private val fx: RecognitionEffect = when (fxStyle) {
            GlobalRecognition.FX_PARTICLES -> RecognitionFx(d, ::invalidate) { host.log(Log.WARN, it) }
            else -> AuroraRecognitionFx(d, ::invalidate) { host.log(Log.WARN, it) }
        }
        private var lineAt = FloatArray(0)
        private var regionAt = FloatArray(0)
        private val litPaint = Paint().apply { color = Color.WHITE }

        private val blue = 0xFF3482FF.toInt()
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val selPaint = Paint().apply { color = 0x663482FF }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
        private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; strokeWidth = 2 * d }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1 * d; color = 0x99FFFFFF.toInt() }
        /** 图片位置的描边：比文字行的细边更显眼，带一圈暗影，浅色图片压在暗底上也看得出边界。 */
        private val imageOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 白边配深色暗影：白底页面上浅灰边等于没有（2026-10-28 真机截图），靠暗影把边勾出来
            style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE
            setShadowLayer(3 * d, 0f, 0f, 0xB3000000.toInt())
        }
        /** 图片选区四角 L 形微调手柄：纯白圆头粗线配深色暗影，深浅底都清晰可拖拽。 */
        private val cropHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
            setShadowLayer(4 * d, 0f, 1 * d, 0xCC000000.toInt())
        }
        private val cropHandlePath = Path()
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val badgePath = Path()
        private val badgeBox = RectF()
        private val imageStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = blue }
        private val dashStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE
            pathEffect = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
        }
        private val handleR = 9 * d
        private val radius = 6 * d
        private val slop = 12 * d
        /** 按在候选图或字上时的点击容差：长按习惯的人手指会慢慢挪，12dp 不够（2026-10-27 真机两次「点」都被当成画框）。 */
        private val tapSlop = 24 * d
        private var onTarget = false
        private val handleSlop = 28 * d
        private val cropCornerSlop = 24 * d
        private val cropEdgeSlop = 16 * d
        private val tmp = RectF()
        private val clip = Path()

        private val enterAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_IN_MS
            addUpdateListener { dim = it.animatedValue as Float; invalidate() }
        }
        private val startBurst = Runnable {
            lineAt = FloatArray(lines.size) { fx.revealPos(RectF(toView(lines[it])), width, height) }
            regionAt = FloatArray(regions.size) { fx.revealPos(RectF(toView(regions[it])), width, height) }
            fx.burst(width, height)
            invalidate()
        }

        fun enter() {
            enterAnim.start()
            if (!ready) fx.start()
        }

        fun stop() {
            hideMagnifier()
            removeCallbacks(startBurst)
            enterAnim.cancel()
            focusAnim.cancel()
            fx.stop()
        }

        fun setResults(
            g: List<PortalOcrSelect.Glyph>,
            r: List<RectF>,
            qrs: List<LocalQrScanner.Detection> = emptyList(),
        ) {
            glyphs = g
            val byLine = g.groupBy { it.line }
            lineIds = byLine.keys.toList()
            lines = byLine.values.map { line ->
                RectF(line.minOf { it.box.left }, line.minOf { it.box.top }, line.maxOf { it.box.right }, line.maxOf { it.box.bottom })
            }
            regions = r
            qrDetections = qrs
            ready = true
            if (lines.isEmpty() && regions.isEmpty() && qrs.isEmpty()) {
                // 什么都没识别到：光球塌缩消失，不炸，也不弹文字
                fx.collapse(width, height)
                return
            }
            // 光球至少露一会儿再炸，识别太快时不至于一闪而过；系统关了动画时 burstReadyAt 为 0，不等
            val wait = (fx.burstReadyAt() - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
            removeCallbacks(startBurst)
            postDelayed(startBurst, wait)
        }

        fun hasSelection(): Boolean = selStart >= 0 || image != null || selectedQr != null

        fun selectQr(det: LocalQrScanner.Detection) {
            selStart = -1; selEnd = -1; image = null
            selectedQr = det
            focusAnim.start()
            onSelection?.invoke(null, false, det, true)
            invalidate()
        }

        /** 位置在 [at] 的可选项此刻的亮度：扩散圈没到不亮，到了渐亮，炸完（含系统关了动画）全亮。 */
        private fun revealAt(at: Float): Float {
            val w = fx.wave
            if (w < 0f) return 0f
            return ((w - at) / REVEAL_BAND + 1f).coerceIn(0f, 1f)
        }

        /** 刚被扩散圈扫过时的额外亮度（「被光照了一下」），全亮之后在 [LIT_BANDS] 个带宽内回落到 0。 */
        private fun litAt(at: Float): Float {
            val x = (fx.wave - at) / REVEAL_BAND
            if (x < 0f || x > LIT_BANDS) return 0f
            return LIT_BOOST * (1f - x / LIT_BANDS)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            ox = -loc[0].toFloat()
            oy = -loc[1].toFloat()
        }

        private fun toView(r: RectF): RectF = tmp.apply { set(r.left + ox, r.top + oy, r.right + ox, r.bottom + oy) }

        override fun onDraw(canvas: Canvas) {
            val b = shot.bitmap
            if (b.isRecycled) return
            val ps = fx.pressScale
            val scaled = kotlin.math.abs(ps - 1f) > 0.0005f
            if (scaled) {
                canvas.save()
                canvas.scale(ps, ps, width / 2f, height / 2f)
            }
            canvas.drawBitmap(b, ox, oy, null)
            canvas.drawColor(((0xA0 * dim).toInt() shl 24))
            // 可选的东西按原亮度挖出来；每一项跟着扩散圈各自亮起
            // 先文字后图片：图片（含头像、图标这类小图）在最上层，不被旁边文字行外扩的高亮盖住（2026-10-28 用户反馈）
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            val fade = (focus * SELECT_MS / SELECT_FADE_MS).coerceAtMost(1f)
            // 有选中项时其余可选区域降亮度，主次分明（A5）；选中所在的行保持全亮
            val others = if (lo >= 0 || image != null || selectedQr != null) 1f - (1f - OTHERS_DIM) * fade else 1f
            val selLines = if (lo >= 0) glyphs[lo].line..glyphs[hi].line else IntRange.EMPTY
            for ((i, r) in lines.withIndex()) {
                var k = revealAt(lineAt.getOrElse(i) { 0f })
                if (k <= 0f) continue
                if (lineIds.getOrNull(i)?.let { it in selLines } != true) k *= others
                bmpPaint.alpha = (255 * k).toInt()
                spot(canvas, b, r, 3 * d, litAt(lineAt.getOrElse(i) { 0f }))
            }
            for ((i, r) in regions.withIndex()) {
                var k = revealAt(regionAt.getOrElse(i) { 0f })
                if (k <= 0f) continue
                k *= others
                bmpPaint.alpha = (255 * k).toInt()
                outline.alpha = (0x99 * k).toInt()
                imageOutline.alpha = (255 * k).toInt()
                val small = r.width() < SPOT_MIN_DP * d || r.height() < SPOT_MIN_DP * d
                // 全屏的底图（大图查看器里那张）不整块提亮：否则把它上面文字行的高亮全盖没了，只给细边与角标
                val backdrop = isBackdrop(r)
                if (!backdrop) spot(canvas, b, r, 0f, litAt(regionAt.getOrElse(i) { 0f }))
                // 小图只给细边：满屏都是粗白框反而看不出哪些是大图
                canvas.drawRoundRect(toView(r), radius, radius, if (small || backdrop) outline else imageOutline)
                // 角标只给够大的图：头像这类小图上一个角标就把内容盖没了（2026-10-28 真机截图）
                if (r.width() >= BADGE_MIN_DP * d && r.height() >= BADGE_MIN_DP * d) drawImageBadge(canvas, toView(r), k)
            }
            dragRect?.let { r ->
                bmpPaint.alpha = 255
                spot(canvas, b, r, 0f)
                canvas.drawRect(toView(r), dashStroke)
            }
            image?.let { r ->
                bmpPaint.alpha = 255
                val v = RectF(toView(r))
                // 选中时轻轻放大一下再回来（B4）；正在拖手柄微调时保持 1:1 跟手
                val sc = if (mode == 7) 1f else 1f + SELECT_POP * kotlin.math.sin(Math.PI * focus).toFloat()
                canvas.save()
                canvas.scale(sc, sc, v.centerX(), v.centerY())
                spot(canvas, b, r, 0f)
                imageStroke.alpha = (255 * fade).toInt()
                canvas.drawRoundRect(v, radius, radius, imageStroke)
                drawCropHandles(canvas, v, fade)
                canvas.restore()
            }
            for (det in qrDetections) {
                val r = det.bounds ?: continue
                val isSelected = selectedQr === det
                val accent = LocalQrScanner.accentColor(det.action.accent, blue)
                bmpPaint.alpha = if (isSelected) 255 else (255 * others).toInt()
                spot(canvas, b, r, 4 * d)
                imageStroke.color = accent
                imageStroke.alpha = if (isSelected) (255 * fade).toInt() else (210 * others).toInt()
                canvas.drawRoundRect(toView(r), radius, radius, imageStroke)
                imageStroke.color = blue
            }
            if (lo >= 0) {
                selPaint.alpha = (0x66 * fade).toInt()
                handlePaint.alpha = (255 * fade).toInt()
                stemPaint.alpha = (255 * fade).toInt()
                for (i in lo..hi) canvas.drawRect(toView(glyphs[i].box), selPaint)
                drawHandle(canvas, RectF(toView(glyphs[lo].box)), true)
                drawHandle(canvas, RectF(toView(glyphs[hi].box)), false)
            }
            drawTranslationOverlay(canvas)
            fx.draw(canvas, width, height)
            if (scaled) canvas.restore()
        }

        /** 原位译文覆盖气泡：把译文直接按原文字选区位置贴回画面，自动适应宽度与深浅色。 */
        private fun drawTranslationOverlay(canvas: Canvas) {
            val box = transOverlayBounds ?: return
            val text = transOverlayText?.takeIf { it.isNotBlank() } ?: return
            val v = RectF(toView(box))
            val padH = 10 * d
            val padV = 7 * d
            val minW = minOf(width - 32 * d, maxOf(v.width(), 160 * d))
            val maxW = (width - 32 * d).coerceAtLeast(120 * d)
            val contentW = (minW - padH * 2).coerceIn(80 * d, maxW - padH * 2).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, transTextPaint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2 * d, 1.12f)
                .setIncludePad(false)
                .build()
            val cardW = minOf(maxW, maxOf(v.width() + padH * 2, (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) }?.plus(padH * 2) ?: minW))
            val cardH = maxOf(v.height() + padV * 2, layout.height + padV * 2)
            val left = v.left.coerceIn(16 * d, (width - 16 * d - cardW).coerceAtLeast(16 * d))
            val top = v.top.coerceIn(statusBarHeight() + 8 * d, (height - 160 * d - cardH).coerceAtLeast(statusBarHeight() + 8 * d))
            val rect = RectF(left, top, left + cardW, top + cardH)
            canvas.drawRoundRect(rect, 12 * d, 12 * d, transBgPaint)
            canvas.drawRoundRect(rect, 12 * d, 12 * d, transStrokePaint)
            canvas.save()
            canvas.translate(rect.left + padH, rect.top + padV)
            layout.draw(canvas)
            canvas.restore()
        }

        fun setTranslationOverlay(bounds: RectF, text: String) {
            transOverlayBounds = RectF(bounds)
            transOverlayText = text
            invalidate()
        }

        fun clearTranslationOverlay() {
            if (transOverlayBounds == null && transOverlayText == null) return
            transOverlayBounds = null
            transOverlayText = null
            invalidate()
        }

        /** 当前文字选区在截图坐标中的外包矩形（不含两端手柄扩展），供原位译文贴图定位。 */
        fun selectedTextBounds(): RectF? {
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            if (lo < 0 || hi >= glyphs.size) return null
            val out = RectF(glyphs[lo].box)
            for (i in lo + 1..hi) out.union(glyphs[i].box)
            return out
        }

        /** 绘制图片选区四角的 L 形拖拽手柄与四边中点微妙短条。 */
        private fun drawCropHandles(canvas: Canvas, v: RectF, alpha: Float) {
            val a = (255 * alpha).toInt().coerceIn(0, 255)
            if (a <= 0) return
            cropHandlePaint.alpha = a
            val arm = minOf(18 * d, v.width() * 0.28f, v.height() * 0.28f).coerceAtLeast(8 * d)
            val p = cropHandlePath
            p.reset()
            // 左上
            p.moveTo(v.left, v.top + arm); p.lineTo(v.left, v.top); p.lineTo(v.left + arm, v.top)
            // 右上
            p.moveTo(v.right - arm, v.top); p.lineTo(v.right, v.top); p.lineTo(v.right, v.top + arm)
            // 左下
            p.moveTo(v.left, v.bottom - arm); p.lineTo(v.left, v.bottom); p.lineTo(v.left + arm, v.bottom)
            // 右下
            p.moveTo(v.right - arm, v.bottom); p.lineTo(v.right, v.bottom); p.lineTo(v.right, v.bottom - arm)
            // 足够宽/高时在四边中点补一小段短杠，提示四边同样可拉
            if (v.width() >= 96 * d) {
                val bar = 10 * d
                p.moveTo(v.centerX() - bar, v.top); p.lineTo(v.centerX() + bar, v.top)
                p.moveTo(v.centerX() - bar, v.bottom); p.lineTo(v.centerX() + bar, v.bottom)
            }
            if (v.height() >= 96 * d) {
                val bar = 10 * d
                p.moveTo(v.left, v.centerY() - bar); p.lineTo(v.left, v.centerY() + bar)
                p.moveTo(v.right, v.centerY() - bar); p.lineTo(v.right, v.centerY() + bar)
            }
            canvas.drawPath(p, cropHandlePaint)
        }

        /** 检测按压点是否落在当前图片选区的四角或四边微调热区。 */
        private fun cropHandleAt(x: Float, y: Float): GlobalRecognition.CropHandle? {
            val r = image ?: return null
            val v = RectF(toView(r))
            val corners = listOf(
                Triple(GlobalRecognition.CropHandle.TOP_LEFT, v.left, v.top),
                Triple(GlobalRecognition.CropHandle.TOP_RIGHT, v.right, v.top),
                Triple(GlobalRecognition.CropHandle.BOTTOM_LEFT, v.left, v.bottom),
                Triple(GlobalRecognition.CropHandle.BOTTOM_RIGHT, v.right, v.bottom),
            )
            corners.minByOrNull { hypot(x - it.second, y - it.third) }?.let { (h, cx, cy) ->
                if (hypot(x - cx, y - cy) <= cropCornerSlop) return h
            }
            if (y in (v.top + cropEdgeSlop)..(v.bottom - cropEdgeSlop)) {
                if (kotlin.math.abs(x - v.left) <= cropEdgeSlop) return GlobalRecognition.CropHandle.LEFT
                if (kotlin.math.abs(x - v.right) <= cropEdgeSlop) return GlobalRecognition.CropHandle.RIGHT
            }
            if (x in (v.left + cropEdgeSlop)..(v.right - cropEdgeSlop)) {
                if (kotlin.math.abs(y - v.top) <= cropEdgeSlop) return GlobalRecognition.CropHandle.TOP
                if (kotlin.math.abs(y - v.bottom) <= cropEdgeSlop) return GlobalRecognition.CropHandle.BOTTOM
            }
            return null
        }

        /** 把截图上 [r] 这块按原亮度画回来（圆角）；[lit] > 0 时再叠一层白，表现刚被光扫过。 */
        private fun spot(canvas: Canvas, b: Bitmap, r: RectF, inflate: Float, lit: Float = 0f) {
            val v = RectF(toView(r)).apply { inset(-inflate, -inflate) }
            canvas.save()
            clip.reset()
            clip.addRoundRect(v, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawBitmap(b, ox, oy, bmpPaint)
            if (lit > 0f) {
                litPaint.alpha = (255 * lit).toInt()
                canvas.drawRect(v, litPaint)
            }
            canvas.restore()
        }

        /**
         * 图片角标：贴在图片右边缘、上下居中（位置参照 Flyme 识屏，图形自绘）。
         *
         * 圆底用半透明黑 + 一圈淡白边，深浅图片上都分得出；里面是白色「相框 + 两座山 + 太阳」。
         * 山和太阳用**实心**画，线条只留外框：第一版全用描边，24dp 里线宽一粗就糊成一块白方块
         * （2026-10-28 真机截图），看不出是图片。每次绘制前显式设 style，不依赖 Paint 上次的状态。
         */
        private fun drawImageBadge(canvas: Canvas, v: RectF, alpha: Float) {
            val r = BADGE_DP * d / 2
            val cx = v.right - BADGE_MARGIN_DP * d - r
            val cy = v.centerY()
            if (cx - r < v.left) return
            val a = (255 * alpha).toInt()
            val p = badgePaint
            p.style = Paint.Style.FILL
            p.color = 0x99000000.toInt(); p.alpha = (0x99 * alpha).toInt()
            canvas.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d
            p.color = Color.WHITE; p.alpha = (0x66 * alpha).toInt()
            canvas.drawCircle(cx, cy, r - 0.5f * d, p)
            // 相框：宽 1.1r、高 0.84r，四角都在圆底里
            val hw = r * 0.55f
            val hh = r * 0.42f
            badgeBox.set(cx - hw, cy - hh, cx + hw, cy + hh)
            // 相框用「外框减内框」的镂空路径实心画：描边版真机上两次都画成了实心白块（原因未明），
            // 改成纯填充后不再依赖 STROKE
            val t = 1.4f * d
            badgePath.reset()
            badgePath.fillType = Path.FillType.EVEN_ODD
            badgePath.addRoundRect(badgeBox, 1.5f * d, 1.5f * d, Path.Direction.CW)
            badgePath.addRoundRect(badgeBox.left + t, badgeBox.top + t, badgeBox.right - t, badgeBox.bottom - t, 0.5f * d, 0.5f * d, Path.Direction.CW)
            p.style = Paint.Style.FILL; p.alpha = a
            canvas.drawPath(badgePath, p)
            // 山：两座，底边贴相框内侧
            val inset = 1.4f * d
            val bottom = badgeBox.bottom - inset
            val left = badgeBox.left + inset
            val right = badgeBox.right - inset
            val span = right - left
            badgePath.reset()
            badgePath.fillType = Path.FillType.WINDING
            badgePath.moveTo(left, bottom)
            badgePath.lineTo(left + span * 0.35f, bottom - hh * 1.05f)
            badgePath.lineTo(left + span * 0.58f, bottom - hh * 0.55f)
            badgePath.lineTo(left + span * 0.74f, bottom - hh * 0.8f)
            badgePath.lineTo(right, bottom)
            badgePath.close()
            canvas.drawPath(badgePath, p)
            // 太阳：右上角
            canvas.drawCircle(left + span * 0.78f, badgeBox.top + inset + hh * 0.28f, hh * 0.22f, p)
        }

        private fun drawHandle(canvas: Canvas, r: RectF, start: Boolean) {
            val x = if (start) r.left else r.right
            canvas.drawLine(x, r.top, x, r.bottom, stemPaint)
            canvas.drawCircle(x, if (start) r.top - handleR * 0.6f else r.bottom + handleR * 0.6f, handleR, handlePaint)
        }

        private fun handlePos(start: Boolean): Pair<Float, Float>? {
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            if (lo < 0) return null
            val r = RectF(toView(glyphs[if (start) lo else hi].box))
            return if (start) r.left to r.top - handleR * 0.6f else r.right to r.bottom + handleR * 0.6f
        }

        private fun glyphAt(x: Float, y: Float): Int =
            glyphs.indexOfFirst { RectF(toView(it.box)).apply { inset(-4 * d, -4 * d) }.contains(x, y) }

        /** 离 (x,y) 最近的字；同一行优先（纵向距离权重更大），拖动时手感才稳。 */
        private fun nearest(x: Float, y: Float): Int {
            var best = -1
            var bestD = Float.MAX_VALUE
            glyphs.forEachIndexed { i, g ->
                val r = toView(g.box)
                val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
                val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
                val dist = dx + dy * 3
                if (dist < bestD) { bestD = dist; best = i }
            }
            return best
        }

        /** 盖住大半屏的图：大图查看器里的那张。在它上面点字选字，点别处才选它。 */
        private fun isBackdrop(r: RectF): Boolean =
            r.width() * r.height() > shot.bitmap.width.toFloat() * shot.bitmap.height * BACKDROP_RATIO

        /** 包含该点的图片区域里面积最小的那块（大图里嵌小图时点的是小图）。 */
        private fun regionAt(x: Float, y: Float): RectF? =
            regions.filter { RectF(toView(it)).contains(x, y) }.minByOrNull { it.width() * it.height() }

        /** 包含该点的二维码检测结果（带 8dp 容差，小二维码也能稳稳点中）。 */
        private fun qrAt(x: Float, y: Float): LocalQrScanner.Detection? =
            qrDetections.firstOrNull { det ->
                val r = det.bounds ?: return@firstOrNull false
                RectF(toView(r)).apply { inset(-8 * d, -8 * d) }.contains(x, y)
            }

        // 0 无，1 起点手柄，2 终点手柄，3 拖选文字，4 画框；待定：5 按在字上，6 按在别处；7 调整图片选区手柄
        private var mode = 0
        private var activeCropHandle: GlobalRecognition.CropHandle? = null
        private var cropOrigin: GlobalRecognition.BoxF? = null

        /**
         * 拖手柄、在字上拖选时的放大镜（Flyme 识屏同样用系统 `Magnifier`）：
         * 手指盖住的正是要对齐的那个字。横向跟手指，纵向钉在当前端点那个字的行中线上，换行时才跳。
         */
        private val magnifier by lazy { runCatching { android.widget.Magnifier.Builder(this).build() }.getOrNull() }

        private fun showMagnifier(x: Float, end: Int) {
            val g = glyphs.getOrNull(end) ?: return
            val r = toView(g.box)
            runCatching { magnifier?.show(x.coerceIn(r.left - r.width(), r.right + r.width()), r.centerY()) }
        }

        private fun hideMagnifier() {
            runCatching { magnifier?.dismiss() }
        }
        private var downX = 0f
        private var downY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (dim < 1f || e.pointerCount > 1) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = e.x; downY = e.y
                    val s = handlePos(true)
                    val t = handlePos(false)
                    val cropHandle = cropHandleAt(e.x, e.y)
                    mode = when {
                        cropHandle != null -> {
                            activeCropHandle = cropHandle
                            cropOrigin = image?.let { GlobalRecognition.BoxF(it.left, it.top, it.right, it.bottom) }
                            7
                        }
                        s != null && hypot(e.x - s.first, e.y - s.second) < handleSlop -> 1
                        t != null && hypot(e.x - t.first, e.y - t.second) < handleSlop -> 2
                        glyphAt(e.x, e.y) >= 0 -> 5
                        else -> 6
                    }
                    onTarget = mode == 5 || regionAt(e.x, e.y) != null || qrAt(e.x, e.y) != null
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = hypot(e.x - downX, e.y - downY) > (if (onTarget) tapSlop else slop)
                    when (mode) {
                        1 -> { normalize(); selStart = nearest(e.x, e.y); showMagnifier(e.x, selStart) }
                        2 -> { normalize(); selEnd = nearest(e.x, e.y); showMagnifier(e.x, selEnd) }
                        3 -> { selEnd = nearest(e.x, e.y); showMagnifier(e.x, selEnd) }
                        4 -> dragRect = shotRect(downX, downY, e.x, e.y)
                        5 -> if (moved) {
                            mode = 3
                            selectText(glyphAt(downX, downY), nearest(e.x, e.y))
                            showMagnifier(e.x, selEnd)
                        }
                        6 -> if (moved) {
                            mode = 4
                            dragRect = shotRect(downX, downY, e.x, e.y)
                        }
                        7 -> {
                            val h = activeCropHandle
                            val orig = cropOrigin
                            if (h != null && orig != null) {
                                val next = GlobalRecognition.adjustCropBox(
                                    origin = orig,
                                    handle = h,
                                    dx = e.x - downX,
                                    dy = e.y - downY,
                                    maxW = shot.bitmap.width.toFloat(),
                                    maxH = shot.bitmap.height.toFloat(),
                                    minSide = MIN_FRAME_DP * d,
                                )
                                image = RectF(next.left, next.top, next.right, next.bottom)
                            }
                        }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    // 先放开：点按在这里触发的选中要立即生效，不走「抬手再出面板」
                    touching = false
                    hideMagnifier()
                    when (mode) {
                        5, 6 -> tap(downX, downY)
                        4 -> {
                            val r = dragRect
                            dragRect = null
                            if (r != null && r.width() >= MIN_FRAME_DP * d && r.height() >= MIN_FRAME_DP * d) {
                                selectImage(r, preferSource = false)
                            } else {
                                tap(downX, downY)
                            }
                        }
                        7 -> {
                            val adjusted = image?.let { RectF(it) }
                            activeCropHandle = null
                            cropOrigin = null
                            if (adjusted != null) {
                                // 微调后的选区直接裁截图，完整保留用户微调的边界
                                selectImage(adjusted, preferSource = false, pop = false)
                            }
                        }
                    }
                    normalize()
                    mode = 0
                    invalidate()
                    onTouchEnd?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideMagnifier()
                    dragRect = null; activeCropHandle = null; cropOrigin = null; mode = 0; touching = false; invalidate()
                    onTouchEnd?.invoke()
                }
            }
            return true
        }

        /** 点按：二维码优先，其次图片优先于文字（封面上压的大字不该抢走封面）；全屏底图例外，字优先。 */
        private fun tap(x: Float, y: Float) {
            val qr = qrAt(x, y)
            val g = glyphAt(x, y)
            val r = regionAt(x, y)?.takeUnless { g >= 0 && isBackdrop(it) }
            host.log(Log.INFO, "点击 (${x.toInt()},${y.toInt()})：码=${qr != null} 字=${g >= 0} 图=${r?.let { "${it.width().toInt()}x${it.height().toInt()}" }}")
            when {
                qr != null -> selectQr(qr)
                r != null -> selectImage(RectF(r))
                g >= 0 -> {
                    val line = glyphs[g].line
                    selectText(glyphs.indexOfFirst { it.line == line }, glyphs.indexOfLast { it.line == line })
                }
                else -> clear()
            }
        }

        /** 视图坐标的两点 → 截图坐标的矩形。 */
        private fun shotRect(x0: Float, y0: Float, x1: Float, y1: Float) =
            RectF(minOf(x0, x1) - ox, minOf(y0, y1) - oy, maxOf(x0, x1) - ox, maxOf(y0, y1) - oy)

        private fun selectText(a: Int, b: Int) {
            image = null
            selectedQr = null
            selStart = a; selEnd = b
            if (a >= 0) focusAnim.start()
            onSelection?.invoke(null, a >= 0, null, true)
        }

        private fun selectImage(r: RectF, preferSource: Boolean = true, pop: Boolean = true) {
            selStart = -1; selEnd = -1
            selectedQr = null
            image = r
            if (pop) focusAnim.start() else focus = 1f
            onSelection?.invoke(r, false, null, preferSource)
        }

        private fun clear() {
            selStart = -1; selEnd = -1; image = null; selectedQr = null
            onSelection?.invoke(null, false, null, true)
        }

        /** 当前选中项在本视图坐标里（不含平移）的范围，文字选区含两端手柄；没选中返回 null。 */
        fun selectionBounds(): RectF? {
            selectedQr?.bounds?.let { return RectF(toView(it)) }
            image?.let { return RectF(toView(it)) }
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            if (lo < 0 || hi >= glyphs.size) return null
            val out = RectF(toView(glyphs[lo].box))
            for (i in lo + 1..hi) out.union(toView(glyphs[i].box))
            out.top -= handleR * 2
            out.bottom += handleR * 2
            return out
        }

        /** 清除选中（底部面板随之收起）。@return 原来是否选着东西 */
        fun clearSelection(): Boolean {
            if (selStart < 0 && image == null && selectedQr == null) return false
            clear()
            invalidate()
            return true
        }

        private fun normalize() {
            if (selStart > selEnd) selStart = selEnd.also { selEnd = selStart }
        }

        fun selectAll() {
            if (glyphs.isEmpty()) return
            selectText(0, glyphs.size - 1)
            invalidate()
        }

        fun selectedText(): String {
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            if (lo < 0) return ""
            val sb = StringBuilder()
            for (i in lo..hi) {
                if (i > lo && glyphs[i].line != glyphs[i - 1].line) {
                    // 换行：上一行末尾是连字符就接回去（英文断词），否则换行
                    if (sb.endsWith("-")) sb.setLength(sb.length - 1) else sb.append('\n')
                }
                sb.append(glyphs[i].text)
            }
            return sb.toString()
        }
    }

    private companion object {
        /** 先用传送门主面板同款 TYPE_PHONE（宿主持有 INTERNAL_SYSTEM_WINDOW），不行再退悬浮窗。 */
        @Suppress("DEPRECATION")
        val TYPES = intArrayOf(WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        const val ANIM_IN_MS = 220L
        /** 关窗淡出（B5）：180ms 太急，参照 Flyme 的 400ms 取 300ms，曲线 (0.25, 0.1, 0.25, 1)。 */
        const val ANIM_OUT_MS = 300L
        val ANIM_OUT = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
        /** 选中过渡总时长、选中标记淡入时长、图片轻放大幅度、其余区域降到的亮度（B4 / A5）。 */
        const val SELECT_MS = 220L
        const val SELECT_FADE_MS = 120L
        const val SELECT_POP = 0.02f
        const val OTHERS_DIM = 0.5f
        /** 可选项从开始亮到全亮，在扩散进度上占多宽。 */
        const val REVEAL_BAND = 0.12f
        /** 刚亮起时额外叠的白（约 +15% 亮度），以及在几个带宽内回落。 */
        const val LIT_BOOST = 0.15f
        const val LIT_BANDS = 1.5f
        const val CLOSE_AFTER_ACTION_MS = 250L
        /** 点保存后盯相册多久；含二选一弹窗里犹豫的时间。 */
        const val SAVE_WATCH_MS = 15_000L
        /** [PortalImageActions] 换上的保存格的 tag（它的 TAG_PREFIX + 「保存」）。 */
        const val SAVE_CELL_TAG = "fp_portal_action_保存"
        /** 中央结果提示：淡入放大、停留、淡出，共约 1.5s（Flyme「已保存到图库」的节奏）。 */
        const val CONFIRM_IN_MS = 180L
        const val CONFIRM_HOLD_MS = 1100L
        const val CONFIRM_OUT_MS = 250L
        const val CONFIRM_FROM_SCALE = 0.9f
        /** 底部面板滑入 / 滑出：先快后慢、无回弹。 */
        const val SHEET_IN_MS = 250L
        const val SHEET_OUT_MS = 200L
        val SHEET_IN = PathInterpolator(0.2f, 0f, 0f, 1f)
        val SHEET_OUT = PathInterpolator(0.4f, 0f, 1f, 1f)
        /** 传送门识图卡片按钮那排的规格（`card_recognition_operate_*`、`size_60` / `size_69`、`card_recognition_image_operate_margin`）。 */
        const val HOST_TEXT_SP = 13.82f
        const val HOST_ICON_SLOT_DP = 21.82f
        const val HOST_ICON_GAP_DP = 5.1f
        const val HOST_ROW_TOP_DP = 25.1f
        const val HOST_ROW_BOTTOM_DP = 21.82f
        const val HOST_BUTTON_GAP_DP = 56f
        /** 加载中面板的高度与转圈淡入前的等待。 */
        const val LOADING_DP = 56
        /** 面板统一高度、收起后露出的高度（把手 + 第一排按钮）、让位时离面板顶边的余量、甩动阈值、把手可点范围。 */
        const val SHEET_DP = 145
        const val COLLAPSED_DP = 76
        const val AVOID_MARGIN_DP = 12
        const val FLING_DP = 600
        const val GRIP_ZONE_DP = 28
        const val SPINNER_DELAY_MS = 150L
        const val RESTORE_DELAY_MS = 1500L
        /** 图片区域至少这么大（dp）才在识别完成时高亮。 */
        const val SPOT_MIN_DP = 48
        /** 图片角标的直径与离图片右边缘的距离（dp）。 */
        const val BADGE_DP = 28
        const val BADGE_MARGIN_DP = 8
        /** 图片两边都不小于这个（dp）才画角标。 */
        const val BADGE_MIN_DP = 96
        /** 图片区域超过整屏这个比例就当底图（与采集端 `MAX_SCREEN_RATIO` 同一条线）。 */
        const val BACKDROP_RATIO = 0.8f

        /** 框小于这个边长（dp）当成手抖，不算框选。 */
        const val MIN_FRAME_DP = 24
    }
}
