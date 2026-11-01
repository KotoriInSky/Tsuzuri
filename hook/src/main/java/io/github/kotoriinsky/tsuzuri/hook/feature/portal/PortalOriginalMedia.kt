package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PortalGifEncoder
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract
import io.github.kotoriinsky.tsuzuri.core.PortalMediaAcquisition
import io.github.kotoriinsky.tsuzuri.core.PortalMediaSelection
import io.github.kotoriinsky.tsuzuri.core.PortalMotionPhoto
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalMediaHostCompat
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalMediaStreamCompat
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalRuleRuntimeCompat
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalBitmapSourceCompat
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalImageSelectionCompat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 在原图片卡中呈现动态内容，原文件在后台下载；编辑传完整副本，OCR 继续使用静态图。 */
object PortalOriginalMedia {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "PortalMediaIO").apply { isDaemon = true } }
    private val actions = Executors.newSingleThreadExecutor { r -> Thread(r, "PortalMediaAction").apply { isDaemon = true } }
    private val session = PortalMediaContract.Session()
    private const val LOADING_TAG = "fp_portal_media_loading"
    private const val PLAY_TAG = "fp_portal_media_play"
    private const val VIDEO_TAG = "fp_portal_media_video"
    private const val NOTE_TAG = "fp_portal_media_note"
    private val cards = Collections.synchronizedMap(WeakHashMap<View, Long>())
    @Volatile private var state: State? = null
    private data class State(val id: Long, val source: PortalMediaSelection.Source, val live: Boolean, val ctx: HookContext,
        val host: PortalMediaHostCompat, val context: Context, val acquisition: PortalMediaAcquisition, val target: Rect?,
        @Volatile var fallback: Bitmap?, var partial: Boolean = false, var screenPrepared: Boolean = false,
        var failed: Boolean = false, var deferredShow: (() -> Unit)? = null, var deadline: Runnable? = null,
        @Volatile var ready: Ready? = null,
        @Volatile var error: Boolean = false, @Volatile var task: Future<*>? = null,
        var visibleTask: Future<*>? = null,
        @Volatile var prepared: Ready? = null,
        @Volatile var connection: HttpURLConnection? = null)
    private data class Asset(val uri: Uri, val format: PortalMediaContract.Format)
    private const val ANIMATED_BUDGET_MS = 20_000L
    /** 动态 WebP 有损质量；表情包在 90 下与原图几乎无差。 */
    private const val WEBP_QUALITY = 90
    private val SEQUENCE_FORMATS = setOf(PortalMediaContract.Format.HEIC, PortalMediaContract.Format.HEIF,
        PortalMediaContract.Format.AVIF)
    private data class Ready(var image: Asset?, val video: Asset?, val motion: Asset?,
        val preview: Drawable?, val bitmap: Bitmap?, val live: Boolean, val note: String? = null) {
        val export get() = motion ?: video ?: image
        /** 动图 HEIF/AVIF（如抖音表情包）：图库与聊天应用兼容差，保存/分享转 GIF，编辑用首帧。 */
        val sequence get() = motion == null && video == null && preview is AnimatedImageDrawable &&
            image?.format in SEQUENCE_FORMATS
        var converted: Asset? = null
        var still: Asset? = null
        // 素材缺项与完整度只留诊断，成功操作文案只描述实际保存的文件。
        val saveMessage get() = when {
            live -> "实况已保存"
            video != null -> "视频已保存"
            else -> "图片已保存"
        }
    }
    fun clear() {
        session.clear()
        val previous = state
        state = null
        previous?.acquisition?.cancel()
        previous?.deadline?.let(main::removeCallbacks)
        previous?.task?.cancel(true)
        previous?.visibleTask?.cancel(true)
        previous?.connection?.let { connection -> worker.execute { runCatching { connection.disconnect() } } }
        main.post {
            (previous?.ready?.preview as? AnimatedImageDrawable)?.stop()
            val obsolete = cards.filterValues { it == previous?.id }.keys.toList()
            obsolete.forEach { card ->
                clearPreview(card)
                cards.remove(card)
            }
        }
    }

    fun hasSession(): Boolean = state != null
    fun capture(bundle: Bundle, context: Context, ctx: HookContext, host: PortalMediaHostCompat,
        page: PortalMediaHostCompat.Page? = null) {
        clear()
        val clock = PortalMediaAcquisition(SystemClock.elapsedRealtime())
        val compiled = PortalRuleRuntimeCompat.pick(bundle, page)
        val source = compiled?.source
        ctx.log(android.util.Log.INFO, "原媒体采集：编译规则=${compiled != null} 图片=${source?.image != null} 视频=${source?.video != null}")
        val selected = source ?: PortalMediaSelection.Source(null, null)
        val live = compiled?.live == true
        val fallback = ownBitmap(PortalBitmapSourceCompat.pick(bundle), ctx)
        val webImage = PortalImageSelectionCompat.webImage(bundle)
        val active = State(session.begin(), selected, live, ctx, host, context, clock,
            PortalImageSelectionCompat.target(bundle), fallback)
        state = active
        if (webImage) ctx.log(android.util.Log.INFO, "网页图片使用触点选框，完整来源保持原尺寸")
        ctx.log(android.util.Log.INFO, "媒体来源配对：实况=$live 视频候选=${selected.videoReferences.size} 位图=${fallback != null}")
        // ROM 的自定义字段分支不必包含 Bitmap。占位只用于创建已有图片卡，绝不用于保存。
        if (bundle.getParcelable("viewBitmap", Bitmap::class.java) == null)
            bundle.putParcelable("viewBitmap", Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        bundle.remove("imgUrl") // 原装分支会同步联网；我们统一在后台下载原文件。
        active.deadline = Runnable {
            if (session.isCurrent(active.id)) finishFallback(active, true)
        }.also { main.postDelayed(it, clock.remainingMs(SystemClock.elapsedRealtime()).toLong()) }
        if (source == null) {
            main.post { if (session.isCurrent(active.id)) finishFallback(active, false) }
        } else active.task = worker.submit {
            try {
                val ready = download(context.applicationContext, active, ctx)
                main.post {
                    if (session.isCurrent(active.id) && active.acquisition.completeOriginal(SystemClock.elapsedRealtime())) {
                        finish(active, ready)
                    } else discard(context, ready, active.ready)
                }
            } catch (e: Exception) {
                if (!session.isCurrent(active.id)) return@submit
                ctx.log(android.util.Log.WARN, "原媒体获取未完成：${failureReason(e)}")
                main.post {
                    if (session.isCurrent(active.id)) {
                        active.failed = true
                        finishFallback(active, false)
                    }
                }
            }
        }
    }

    private fun ownBitmap(bitmap: Bitmap?, ctx: HookContext): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return null
        if (bitmap.width.toLong() * bitmap.height > 40_000_000) {
            ctx.log(android.util.Log.WARN, "位图超出暂存内存预算，保留原文件或截图分支")
            return null
        }
        return try { bitmap.copy(Bitmap.Config.ARGB_8888, false) }
        catch (_: OutOfMemoryError) { ctx.log(android.util.Log.WARN, "位图副本内存不足"); null }
    }
    fun align(owner: Any, host: PortalMediaHostCompat, ctx: HookContext) {
        val active = state ?: return
        host.align(owner, active.target)?.let {
            ctx.log(android.util.Log.INFO, "图片选区已按可见范围定位：${it.width()}x${it.height()}")
        }
    }
    fun screenPrepared(owner: Any, host: PortalMediaHostCompat) {
        val active = state ?: return
        active.screenPrepared = true
        if (active.fallback == null && active.acquisition.status == PortalMediaAcquisition.Status.ACQUIRING) {
            active.fallback = try { host.screenshotCandidate(owner) } catch (_: OutOfMemoryError) { null }
            active.partial = active.fallback != null
            if (active.fallback == null) {
                val target = active.target ?: runCatching { host.touchRect(active.context) }.getOrNull()
                active.visibleTask = worker.submit {
                    val candidate = try {
                        budget(active)
                        host.visibleCandidate(active.context, target)
                    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                    main.post {
                        if (session.isCurrent(active.id) && active.acquisition.status == PortalMediaAcquisition.Status.ACQUIRING &&
                            active.acquisition.remainingMs(SystemClock.elapsedRealtime()) > 0) {
                            active.fallback = candidate
                            active.partial = candidate != null
                            if (active.failed || active.source.image == null && active.source.video == null) finishFallback(active, false)
                        } else candidate?.recycle()
                    }
                }
                return
            }
        }
        if (active.source.image == null && active.source.video == null || active.failed) finishFallback(active, false)
    }
    /** 只保存普通回调，不跨线程保留 libxposed Chain。 */
    fun deferShow(show: () -> Unit): Boolean {
        val active = state ?: return false
        if (active.acquisition.status != PortalMediaAcquisition.Status.ACQUIRING) return false
        active.deferredShow = show
        return true
    }
    fun beforeShow() {
        val active = state ?: return
        if (active.acquisition.status == PortalMediaAcquisition.Status.COMPLETED) active.host.updateBitmap(active.ready?.bitmap)
    }
    private fun finishFallback(active: State, expired: Boolean) {
        if (!session.isCurrent(active.id)) return
        if (active.fallback == null && !active.screenPrepared && !expired) return
        if (!expired && active.fallback == null && active.prepared == null && active.visibleTask?.isDone == false) return
        if (!active.acquisition.completeFallback()) return
        active.task?.cancel(true)
        active.connection?.let { connection -> worker.execute { runCatching { connection.disconnect() } } }
        val bitmap = active.fallback?.takeUnless { it.isRecycled }
        active.error = bitmap == null && active.prepared == null
        // 来源证据留在诊断日志，图片卡不向用户展示位图实现细节。
        if (!active.partial && bitmap != null && active.prepared == null)
            active.ctx.log(android.util.Log.INFO, "使用 ROM 位图备选，完整度未确认")
        val note = if (active.partial) "仅可见部分" else null
        finish(active, active.prepared ?: bitmap?.let { Ready(null, null, null, BitmapDrawable(active.context.resources, it), it, false, note) })
    }
    private fun finish(active: State, ready: Ready?) {
        if (!session.isCurrent(active.id)) return
        active.deadline?.let(main::removeCallbacks)
        active.visibleTask?.cancel(true)
        active.ready = ready
        active.partial = ready?.note == "仅可见部分"
        active.host.updateBitmap(ready?.bitmap)
        active.ctx.log(android.util.Log.INFO, "媒体会话完成：原文件=${ready?.export != null} 实况=${ready?.live == true} 局部=${active.partial} 可用=${ready != null}")
        ready?.note?.let { active.ctx.log(android.util.Log.WARN, "媒体备选：$it") }
        val show = active.deferredShow
        active.deferredShow = null
        show?.let { runCatching(it).onFailure { active.ctx.log(android.util.Log.WARN, "展示图片卡失败", it) } }
        val bound = cards.filterValues { it == active.id }.keys.toList()
        bound.forEach { view ->
            runCatching { active.host.refresh?.invoke(view) }.onFailure { active.ctx.log(android.util.Log.WARN, "更新媒体卡失败", it) }
        }
        if (show != null || bound.isNotEmpty()) {
            if (ready == null) Toast.makeText(active.context, "未取得可用图片", Toast.LENGTH_SHORT).show()
        }
    }
    /** 用户主动重选时旧原文件失效，不能继续保存另一张图片。 */
    fun reselect(context: Context, host: PortalMediaHostCompat, ctx: HookContext) {
        val bitmap = ownBitmap(host.currentBitmap(), ctx)
        clear()
        val active = State(session.begin(), PortalMediaSelection.Source(null, null), false, ctx, host, context,
            PortalMediaAcquisition(SystemClock.elapsedRealtime()), null, bitmap, partial = true, screenPrepared = true)
        state = active
        finishFallback(active, false)
    }
    private fun discard(context: Context, ready: Ready, retained: Ready?) {
        val kept = listOfNotNull(retained?.image, retained?.video, retained?.motion).map { it.uri }
        val uris = listOfNotNull(ready.image, ready.video, ready.motion, ready.converted, ready.still).map { it.uri }
            .distinct().filter { it !in kept }
        worker.execute { uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } } }
        if (ready.preview !== retained?.preview) (ready.preview as? AnimatedImageDrawable)?.stop()
    }

    fun refresh(card: View, host: PortalMediaHostCompat, ctx: HookContext) {
        val active = state ?: return
        if (cards.put(card, active.id) != active.id) clearPreview(card)
        val context = card.context
        val image = named(card, "capture_image") as? ImageView ?: return
        val group = named(card, "capture_image_group") as? ViewGroup ?: return
        loading(group, active.ready == null && !active.error)
        card.findViewWithTag<View>("fp_portal_action_编辑")?.let { cell ->
            if (cell is ViewGroup) (0 until cell.childCount).map { cell.getChildAt(it) }.filterIsInstance<TextView>()
                .forEach { it.text = "编辑" }
        }
        active.ready?.let { ready ->
            group.findViewWithTag<View>(NOTE_TAG)?.let(group::removeView)
            ready.preview?.let {
                image.setImageDrawable(it)
                (it as? AnimatedImageDrawable)?.let { animation ->
                    animation.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    if (image.isAttachedToWindow) animation.start()
                    else image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            if (session.isCurrent(active.id)) animation.start()
                            v.removeOnAttachStateChangeListener(this)
                        }
                        override fun onViewDetachedFromWindow(v: View) = Unit
                    })
                }
            }
            if (ready.video != null) {
                val uri = ready.video.uri
                val button = group.findViewWithTag<ImageView>(PLAY_TAG) ?: ImageView(context).apply {
                    tag = PLAY_TAG
                    contentDescription = "播放"
                    setImageDrawable(PlayIcon())
                    group.addView(this, RelativeLayout.LayoutParams(dp(group, 48), dp(group, 48))
                        .apply { addRule(RelativeLayout.CENTER_IN_PARENT) })
                }
                val playing = group.findViewWithTag<PortalVideoPreview>(VIDEO_TAG)
                button.visibility = if (playing?.isPlaying == true && playing.hasFrame) View.GONE else View.VISIBLE
                button.bringToFront()
                button.setOnClickListener { play(group, uri, active.id, ctx) }
                image.setOnClickListener { play(group, uri, active.id, ctx) }
            }
            return
        }
    }
    private fun play(group: ViewGroup, uri: Uri, id: Long, ctx: HookContext) {
        if (!session.isCurrent(id)) return
        val button = group.findViewWithTag<ImageView>(PLAY_TAG)
        val existing = group.findViewWithTag<PortalVideoPreview>(VIDEO_TAG)
        if (existing != null) {
            existing.toggle()
            return
        }
        loading(group, true)
        lateinit var video: PortalVideoPreview
        video = PortalVideoPreview(group.context, uri, { session.isCurrent(id) },
            changed = onChange@{ playing, frame ->
                if (!session.isCurrent(id) || video.parent !== group) return@onChange
                loading(group, playing && !frame)
                button?.visibility = if (playing && frame) View.GONE else View.VISIBLE
                if (frame) ctx.log(android.util.Log.INFO, "视频预览已有首帧：纹理可见=$frame 播放=$playing")
            }, failed = onFailure@{ what, extra ->
                ctx.log(android.util.Log.WARN, "媒体播放失败：what=$what extra=$extra")
                if (!session.isCurrent(id) || video.parent !== group) return@onFailure
                loading(group, false)
                button?.visibility = View.VISIBLE
                group.removeView(video)
                Toast.makeText(group.context, "无法播放此视频", Toast.LENGTH_SHORT).show()
            }).apply {
            tag = VIDEO_TAG
            contentDescription = "暂停或继续播放"
            setOnClickListener { play(group, uri, id, ctx) }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    dispose()
                    group.post {
                        if (v.parent === group) group.removeView(v)
                        if (session.isCurrent(id) && group.findViewWithTag<View>(VIDEO_TAG) == null) {
                            button?.visibility = View.VISIBLE; loading(group, false)
                        }
                    }
                }
            })
        }
        // 纹理按 fitCenter 变换；封面始终保留在下层，首帧到达后才显示视频。
        group.addView(video, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            .apply { addRule(RelativeLayout.CENTER_IN_PARENT) })
        button?.bringToFront()
        group.findViewWithTag<View>(LOADING_TAG)?.bringToFront()
    }
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density + 0.5f).toInt()
    private fun loading(group: ViewGroup, visible: Boolean) {
        val existing = group.findViewWithTag<View>(LOADING_TAG)
        if (!visible) { existing?.let(group::removeView); return }
        if (existing != null) return
        group.addView(ProgressBar(group.context).apply {
            tag = LOADING_TAG
            contentDescription = "加载中"
        }, RelativeLayout.LayoutParams(dp(group, 24), dp(group, 24)).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END); addRule(RelativeLayout.ALIGN_PARENT_TOP)
            marginEnd = dp(group, 12); topMargin = dp(group, 12)
        })
    }
    private fun clearPreview(card: View) {
        val play = card.findViewWithTag<View>(PLAY_TAG)
        val video = card.findViewWithTag<PortalVideoPreview>(VIDEO_TAG)
        if (play != null || video != null) {
            // 只有动态预览接管过点击才恢复宿主；静态图片保持原有点击处理。
            named(card, "capture_image")?.setOnClickListener(card as? View.OnClickListener)
        }
        video?.dispose()
        listOfNotNull(video, play, card.findViewWithTag<View>(LOADING_TAG), card.findViewWithTag<View>(NOTE_TAG)).forEach {
            (it.parent as? ViewGroup)?.removeView(it)
        }
    }
    private class PlayIcon : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var drawableAlpha = 255
        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
            paint.color = 0x88000000.toInt()
            paint.alpha = 136 * drawableAlpha / 255
            canvas.drawCircle(24f, 24f, 22f, paint)
            paint.color = 0xffffffff.toInt()
            paint.alpha = drawableAlpha
            path.rewind(); path.moveTo(19f, 14f); path.lineTo(34f, 24f); path.lineTo(19f, 34f); path.close()
            canvas.drawPath(path, paint)
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    /** 返回 true 表示当前是媒体会话，禁止回落到旧的 PNG 分享/保存。 */
    fun share(context: Context): Boolean {
        val active = state ?: return false
        val ready = active.ready ?: return pending(context, active)
        actions.execute {
            try {
                val asset = exportAsset(context, ready, active.ctx)
                main.post {
                    if (!session.isCurrent(active.id)) return@post
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = asset.format.mime
                        putExtra(Intent.EXTRA_STREAM, asset.uri)
                        clipData = ClipData.newRawUri("媒体", asset.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { context.startActivity(Intent.createChooser(send, "分享媒体").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: Exception) { Toast.makeText(context, "没有可接收此媒体的应用", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                active.ctx.log(android.util.Log.WARN, "准备媒体分享失败：${e.javaClass.simpleName}")
                main.post { if (session.isCurrent(active.id)) Toast.makeText(context, "分享失败，请重试", Toast.LENGTH_SHORT).show() }
            }
        }
        return true
    }
    fun editingAllowed(context: Context): Boolean {
        val active = state ?: return true
        if (active.ready?.bitmap != null || active.fallback != null) return true
        pending(context, active)
        return false
    }
    /** 完整图片/实况复制到私有编辑目录，绝不把配对视频转成 PNG 或提前写入图库。 */
    fun edit(context: Context, startEditor: (Uri, String) -> Boolean): Boolean {
        val active = state ?: return false
        val ready = active.ready ?: return pending(context, active)
        if (ready.bitmap == null && ready.motion == null && ready.image == null) return false
        actions.execute {
            var copy: Uri? = null
            try {
                if (!session.isCurrent(active.id)) return@execute
                // 编辑器只处理静态图，动图序列传首帧 PNG，避免编辑器只认 HEIF 首帧或打不开。
                val asset = if (ready.sequence) stillAsset(context, ready)
                    else ready.motion ?: ready.image ?: exportAsset(context, ready, active.ctx)
                val base = Uri.parse("content://${PortalMediaContract.AUTHORITY}/${PortalMediaContract.EDIT_SEGMENT}")
                copy = context.contentResolver.insert(base, ContentValues().apply { put("extension", asset.format.extension) })
                    ?: throw IOException("编辑副本不可写")
                context.contentResolver.openInputStream(asset.uri).use { input ->
                    context.contentResolver.openOutputStream(copy, "w").use { output ->
                        requireNotNull(input).copyTo(requireNotNull(output))
                    }
                }
                val uri = copy
                main.post {
                    if (!session.isCurrent(active.id)) {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        return@post
                    }
                    if (startEditor(uri, asset.format.mime)) {
                        active.ctx.log(android.util.Log.INFO, "原媒体编辑已交付：格式=${asset.format.extension} 实况=${ready.live}")
                    } else {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        Toast.makeText(context, "没有可用的图片编辑器", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                copy?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                active.ctx.log(android.util.Log.WARN, "准备媒体编辑失败：${e.javaClass.simpleName}")
                main.post {
                    if (session.isCurrent(active.id)) Toast.makeText(context, "编辑失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }
    fun save(context: Context, ctx: HookContext): Boolean {
        val active = state ?: return false
        val ready = active.ready ?: return pending(context, active)
        actions.execute {
            var saved: Uri? = null
            try {
                val asset = exportAsset(context, ready, ctx)
                val video = asset.format == PortalMediaContract.Format.MP4
                val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, PortalMediaContract.savedName(System.currentTimeMillis(), asset.format))
                    put(MediaStore.MediaColumns.MIME_TYPE, asset.format.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Tsuzuri" else "Pictures/Tsuzuri")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                saved = context.contentResolver.insert(collection, values) ?: throw IOException("媒体库不可写")
                context.contentResolver.openInputStream(asset.uri).use { input ->
                    requireNotNull(input)
                    context.contentResolver.openOutputStream(saved).use { output -> input.copyTo(requireNotNull(output)) }
                }
                context.contentResolver.update(saved, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                ctx.log(android.util.Log.INFO, "原媒体保存完成：格式=${asset.format.extension} 实况=${ready.live}")
                main.post { Toast.makeText(context, ready.saveMessage, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                saved?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                ctx.log(android.util.Log.WARN, "保存原媒体失败：${e.javaClass.simpleName}")
                main.post { Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show() }
            }
        }
        return true
    }
    private fun pending(context: Context, active: State): Boolean {
        Toast.makeText(context, if (active.error) "原媒体加载失败，请重新识别" else "原媒体正在加载，请稍候", Toast.LENGTH_SHORT).show()
        return true
    }
    /** 位图只在用户操作时编码，下载阻塞不能延迟三秒后的静态展示。 */
    private fun exportAsset(context: Context, ready: Ready, ctx: HookContext): Asset {
        if (ready.sequence) {
            val wanted = animatedFormat(ctx)
            ready.converted?.takeIf { it.format == wanted }?.let { return it }
            try {
                return transcodeAnimated(context, ready, ctx, wanted).also { converted ->
                    ready.converted?.let { old -> runCatching { context.contentResolver.delete(old.uri, null, null) } }
                    ready.converted = converted
                }
            } catch (e: Exception) {
                // 转码失败仍交付原文件，不让保存/分享整体失败。
                ctx.log(android.util.Log.WARN, "动图转码失败，改用原文件：${failureReason(e)}")
            } catch (_: OutOfMemoryError) {
                ctx.log(android.util.Log.WARN, "动图转码内存不足，改用原文件")
            }
        }
        ready.export?.let { return it }
        return pngAsset(context, ready.bitmap).also { ready.image = it }
    }
    /** 「动图保存格式」设置；每次保存时读取，改完下次保存即生效。 */
    private fun animatedFormat(ctx: HookContext): PortalMediaContract.Format {
        val spec = Catalog.find(Catalog.SCREEN_IMAGE)?.prefs?.filterIsInstance<PrefSpec.Choice>()
            ?.firstOrNull { it.key == Keys.pref(Catalog.SCREEN_IMAGE, "animated_format") }
        val value = spec?.let { runCatching { ctx.prefs.value(it) }.getOrNull() } ?: "gif"
        return if (value == "webp") PortalMediaContract.Format.WEBP else PortalMediaContract.Format.GIF
    }
    private fun stillAsset(context: Context, ready: Ready): Asset =
        ready.still ?: pngAsset(context, ready.bitmap).also { ready.still = it }
    private fun pngAsset(context: Context, source: Bitmap?): Asset {
        val bitmap = source?.takeUnless { it.isRecycled } ?: throw IOException("没有可用位图")
        val base = Uri.parse("content://${PortalMediaContract.AUTHORITY}/${PortalMediaContract.SEGMENT}")
        val uri = context.contentResolver.insert(base, ContentValues().apply { put("extension", "png") })
            ?: throw IOException("图片暂存不可写")
        try {
            context.contentResolver.openOutputStream(uri, "w").use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(it))) throw IOException("图片编码失败")
            }
            return Asset(uri, PortalMediaContract.Format.PNG)
        } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
    }
    /** HEIF/AVIF 图像序列顺序硬解，保持原尺寸与全部帧，编码为 GIF 或动态 WebP。 */
    private fun transcodeAnimated(context: Context, ready: Ready, ctx: HookContext,
        requested: PortalMediaContract.Format): Asset {
        val source = requireNotNull(ready.image)
        val started = SystemClock.elapsedRealtime()
        // WebP 原生库不可用时退回 GIF，不交付未转码的 HEIF。
        val format = if (requested == PortalMediaContract.Format.WEBP && !PortalWebpNative.available)
            PortalMediaContract.Format.GIF else requested
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, source.uri)
            val total = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                ?: throw MediaFailure("sequence_no_frames")
            if (total < 2) throw MediaFailure("sequence_single_frame")
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // 原生编码后瓶颈在解码而非编码：保持原尺寸、不抽帧（用户要求动图尺寸不变）。
            val delay = if (duration > 0) (duration / total).toInt() else 100
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?.takeIf { it > 0 } ?: throw MediaFailure("sequence_no_size")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?.takeIf { it > 0 } ?: throw MediaFailure("sequence_no_size")
            // 从第 0 帧顺序硬解（见 PortalSequenceFrames）：按下标取帧会从 B 帧起解而花屏。
            val frames = PortalSequenceFrames(context, source.uri, width, height)
            val base = Uri.parse("content://${PortalMediaContract.AUTHORITY}/${PortalMediaContract.SEGMENT}")
            val uri = context.contentResolver.insert(base, ContentValues().apply { put("extension", format.extension) })
                ?: run { frames.close(); throw IOException("图片暂存不可写") }
            var produced = 0
            fun frameAt(@Suppress("UNUSED_PARAMETER") index: Int): IntArray {
                if (SystemClock.elapsedRealtime() - started > ANIMATED_BUDGET_MS) throw MediaFailure("animated_timeout")
                if (!frames.hasNext()) throw MediaFailure("sequence_frame_null")
                produced++
                return frames.next()
            }
            val webp = format == PortalMediaContract.Format.WEBP
            val native = webp || PortalGifNative.available
            try {
                context.contentResolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output)
                    if (native) {
                        val handle = if (webp) PortalWebpNative.open(width, height, WEBP_QUALITY, false)
                            else PortalGifNative.open(width, height)
                        if (handle == 0L) throw MediaFailure("animated_native_open")
                        var owned = true
                        try {
                            // 以实际解出的帧为准；元数据帧数只作上限。
                            while (produced < total && frames.hasNext()) {
                                val pixels = frameAt(produced)
                                val added = if (webp) PortalWebpNative.addFrame(handle, pixels, delay)
                                    else PortalGifNative.addFrame(handle, pixels, (delay + 5) / 10)
                                if (!added) throw MediaFailure("animated_native_frame")
                            }
                            owned = false // finish 无论成败都会释放句柄。
                            val bytes = if (webp) PortalWebpNative.finish(handle) else PortalGifNative.finish(handle)
                            output.write(bytes ?: throw MediaFailure("animated_native_finish"))
                        } finally { if (owned) { if (webp) PortalWebpNative.close(handle) else PortalGifNative.close(handle) } }
                    } else {
                        PortalGifEncoder.encode(width, height, total, ::frameAt, { delay },
                            java.io.BufferedOutputStream(output, 64 * 1024))
                    }
                }
                ctx.log(android.util.Log.INFO, "动图已转码：格式=${format.extension}（设置=${requested.extension}） " +
                    "编码=${if (native) "native" else "kotlin"} 帧=$produced/$total 尺寸=${width}x$height " +
                    "耗时=${SystemClock.elapsedRealtime() - started}ms")
                return Asset(uri, format)
            } catch (e: Throwable) { context.contentResolver.delete(uri, null, null); throw e }
            finally { frames.close() }
        } finally { retriever.release() }
    }
    /** 所有候选、重定向、预览和暂存共用一个截止时间；超时不会回填已经展示的会话。 */
    private fun budget(active: State): Int {
        if (!session.isCurrent(active.id) || Thread.currentThread().isInterrupted ||
            active.acquisition.status != PortalMediaAcquisition.Status.ACQUIRING) throw MediaFailure("session_ended")
        return active.acquisition.remainingMs(SystemClock.elapsedRealtime()).takeIf { it > 0 }
            ?: throw MediaFailure("deadline")
    }
    private fun download(context: Context, active: State, ctx: HookContext): Ready {
        budget(active)
        val dir = File(context.cacheDir, "tsuzuri_media_${active.id}").apply { mkdirs() }
        val published = ArrayList<Uri>()
        fun publishAsset(file: File, format: PortalMediaContract.Format) = publish(context, file, format, active).also {
            published.add(it.uri)
        }
        fun <T> attempt(part: String, action: () -> T): T? = try {
            budget(active)
            action().also { budget(active) }
        } catch (e: Exception) {
            budget(active)
            ctx.log(android.util.Log.WARN, "媒体${part}不可用：${failureReason(e)}")
            null
        } catch (_: OutOfMemoryError) {
            budget(active)
            ctx.log(android.util.Log.WARN, "媒体${part}内存不足")
            null
        }
        try {
            val imageFile = active.source.image?.let { address -> attempt("照片") {
                fetch(address, File(dir, "image"), active).also {
                    if (format(it) == PortalMediaContract.Format.MP4) throw MediaFailure("format_not_image")
                }
            } }
            val imageFormat = imageFile?.let(::format)
            var bitmap = active.fallback
            var preview: Drawable? = imageFile?.let { file -> attempt("预览") {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio = minOf(1f, 1440f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                }
            } }
            if (imageFile != null) bitmap = attempt("静态预览") {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(imageFile)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio = minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                }
            } ?: bitmap
            val image = imageFile?.let { publishAsset(it, requireNotNull(imageFormat)) }
            if (imageFile != null) {
                // 只记录路径末段的格式线索，不写完整地址与签名参数。
                val tail = active.source.image?.substringBefore('?')?.substringAfterLast('/')?.takeLast(48)
                ctx.log(android.util.Log.INFO, "媒体照片：格式=$imageFormat 动图=${preview is AnimatedImageDrawable} " +
                    "大小=${imageFile.length()} 末段=$tail")
            }
            if (image != null) {
                // 配对视频超时时仍可用已经取得的完整图片，不能退成裁剪截图。
                budget(active)
                active.prepared = Ready(image, null, null, preview, bitmap, false,
                    if (active.live) "实况视频未获取" else null)
            }
            val embedded = if (imageFile != null && imageFormat == PortalMediaContract.Format.JPEG)
                attempt("内嵌实况") { embeddedVideo(imageFile, File(dir, "embedded.mp4"), active) } else null
            var downloadedVideo: File? = embedded
            // GIF 优先保持自身动画，不被旁支视频候选覆盖。
            if (embedded == null && imageFormat != PortalMediaContract.Format.GIF) {
                val videoCandidates = active.source.videoReferences.flatMap { PortalMediaStreamCompat.urls(it).take(2) }.distinct().take(6)
                if (active.source.video != null) ctx.log(android.util.Log.INFO, "媒体视频解析：地址数=${videoCandidates.size}")
                for ((index, address) in videoCandidates.withIndex()) {
                    downloadedVideo = attempt("视频候选${index + 1}") {
                        fetch(address, File(dir, "video"), active).also {
                            if (format(it) != PortalMediaContract.Format.MP4) throw MediaFailure("format_not_mp4")
                        }
                    }
                    if (downloadedVideo != null) break
                }
            }
            val videoFile = downloadedVideo
            val video = videoFile?.let { publishAsset(it, PortalMediaContract.Format.MP4) }
            if (bitmap == null && videoFile != null) {
                attempt("视频封面") {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(videoFile.absolutePath)
                        bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        preview = bitmap?.let { BitmapDrawable(context.resources, it) }
                    } finally { retriever.release() }
                }
            }
            val motion = if (embedded != null) image else if (active.live && imageFile != null && videoFile != null &&
                imageFormat != PortalMediaContract.Format.GIF) attempt("实况封装") {
                val jpegFile = if (imageFormat == PortalMediaContract.Format.JPEG) imageFile else {
                    val full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(imageFile)) { decoder, info, _ ->
                        if (info.size.width.toLong() * info.size.height > 40_000_000) throw MediaFailure("bitmap_size_limit")
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    try {
                        File(dir, "cover.jpg").also { file -> file.outputStream().use { out ->
                            if (!full.compress(Bitmap.CompressFormat.JPEG, 98, out)) throw IOException("封面编码失败")
                        } }
                    } finally { full.recycle() }
                }
                budget(active)
                val motionFile = File(dir, "motion.jpg")
                motionFile.outputStream().use { output ->
                    // 从空 JPEG 生成 SOI + APP1 元数据，再流式复制原照片主体，避免整文件进入堆内存。
                    val prefix = PortalMotionPhoto.jpegPrefix(byteArrayOf(-1, -40, -1, -39), videoFile.length(), 0)
                    output.write(prefix, 0, prefix.size - 2)
                    jpegFile.inputStream().use { input ->
                        if (input.read() != 255 || input.read() != 216) throw MediaFailure("format_not_jpeg")
                        copyBudgeted(input, output, active)
                    }
                    videoFile.inputStream().use { copyBudgeted(it, output, active) }
                }
                publishAsset(motionFile, PortalMediaContract.Format.JPEG)
            } else null
            if (image == null && video == null) throw MediaFailure("no_complete_media")
            val note = if (active.live && motion == null) when {
                video != null && image != null -> "实况封装失败"
                video != null -> "实况照片未获取"
                else -> "实况视频未获取"
            } else null
            if (preview == null) preview = bitmap?.let { BitmapDrawable(context.resources, it) }
            budget(active)
            return Ready(image, video, motion, preview, bitmap, motion != null, note)
        } catch (e: Exception) {
            val retained = listOfNotNull(active.prepared?.image?.uri, active.prepared?.video?.uri, active.prepared?.motion?.uri)
            published.filter { it !in retained }.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            throw e
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
    private fun embeddedVideo(image: File, target: File, active: State): File? {
        budget(active)
        val header = image.inputStream().use { it.readNBytes(512 * 1024) }
        val length = PortalMotionPhoto.embeddedVideoLength(header, image.length()) ?: return null
        RandomAccessFile(image, "r").use { input ->
            input.seek(image.length() - length)
            val signature = ByteArray(minOf(4096L, length).toInt())
            input.readFully(signature)
            if (PortalMediaContract.sniff(signature) != PortalMediaContract.Format.MP4) throw MediaFailure("embedded_not_mp4")
            input.seek(image.length() - length)
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    budget(active)
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count <= 0) throw MediaFailure("embedded_truncated")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        budget(active)
        return target
    }
    private fun format(file: File): PortalMediaContract.Format = file.inputStream().use { input ->
        PortalMediaContract.sniff(input.readNBytes(4096)) ?: throw MediaFailure("format_unknown")
    }
    private fun publish(context: Context, file: File, format: PortalMediaContract.Format, active: State): Asset {
        budget(active)
        val base = Uri.parse("content://${PortalMediaContract.AUTHORITY}/${PortalMediaContract.SEGMENT}")
        val uri = context.contentResolver.insert(base, ContentValues().apply { put("extension", format.extension) })
            ?: throw IOException("媒体暂存不可写")
        try {
            context.contentResolver.openOutputStream(uri, "w").use { output ->
                file.inputStream().use { copyBudgeted(it, requireNotNull(output), active) }
            }
            budget(active)
        } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
        return Asset(uri, format)
    }
    private fun copyBudgeted(input: InputStream, output: OutputStream, active: State, connection: HttpURLConnection? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val remaining = budget(active)
            if (connection != null) connection.readTimeout = remaining
            val count = input.read(buffer)
            budget(active)
            if (count < 0) break
            total += count
            if (total > 512L * 1024 * 1024) throw MediaFailure("size_limit")
            output.write(buffer, 0, count)
        }
        if (total == 0L) throw MediaFailure("empty_media")
        return total
    }
    private fun fetch(address: String, target: File, active: State): File {
        budget(active)
        if (address.startsWith("content://")) {
            val uri = PortalMediaContract.imageSource(address)?.let(Uri::parse) ?: throw MediaFailure("invalid_uri")
            // 只用实际消费进程已获授权的 ContentResolver，权限不足即回退，不读取私有路径。
            active.context.contentResolver.openInputStream(uri).use { input ->
                target.outputStream().use { copyBudgeted(requireNotNull(input), it, active) }
            }
            return target
        }
        var url = address
        repeat(5) {
            budget(active)
            url = PortalMediaContract.requestUrl(url) ?: throw MediaFailure("invalid_url")
            val uri = URI(url)
            // 来源域名通用接收；实际连接前检查解析结果，重定向也不能进入本机或局域网。
            if (InetAddress.getAllByName(uri.host).any { resolved ->
                resolved.isAnyLocalAddress || resolved.isLoopbackAddress || resolved.isLinkLocalAddress ||
                    resolved.isSiteLocalAddress || resolved.isMulticastAddress ||
                    (resolved.address.size == 16 && (resolved.address[0].toInt() and 0xfe) == 0xfc)
            }) throw MediaFailure("local_media_host")
            val remaining = budget(active)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            active.connection = connection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = remaining
                connection.readTimeout = remaining
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                budget(active)
                if (code in listOf(301, 302, 303, 307, 308)) {
                    url = URI(url).resolve(connection.getHeaderField("Location") ?: throw MediaFailure("redirect_no_location")).toString()
                } else {
                    if (code != 200) throw MediaFailure("http_$code")
                    val expected = connection.contentLengthLong
                    if (expected > 512L * 1024 * 1024) throw MediaFailure("size_limit")
                    val total = connection.inputStream.use { input -> target.outputStream().use { output ->
                        copyBudgeted(input, output, active, connection)
                    } }
                    if (expected >= 0 && total != expected) throw MediaFailure("truncated_media")
                    budget(active)
                    return target
                }
            } finally { active.connection = null; connection.disconnect() }
        }
        throw MediaFailure("too_many_redirects")
    }
    private class MediaFailure(val reason: String) : IOException(reason)
    private fun failureReason(error: Exception) = (error as? MediaFailure)?.reason ?: error.javaClass.simpleName
    private fun named(root: View, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id == 0) null else root.findViewById(id)
    }
}
