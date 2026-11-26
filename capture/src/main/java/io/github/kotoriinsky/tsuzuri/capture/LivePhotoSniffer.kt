package io.github.kotoriinsky.tsuzuri.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.kotoriinsky.tsuzuri.capture.engine.DrawableBitmapFinder
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * 进程内通用 Live Photo（实况照片）短视频轨嗅探器。
 *
 * Android 各大 App 的实况本质几乎都是「静图 View + 同容器内叠放的 1~3 秒短视频播放器
 * （ExoPlayer / Media3 / IjkPlayer / 自研 LivePhotoView）」。
 *
 * 本类仅在同时满足「目标区域存在静图 + 同容器存在短片播放器（或类名含 LivePhoto）」时才介入，
 * 并且严格限定视频体积（4KB..16MB）与 MP4/MOV `ftyp` 魔数，避免误把普通长视频当成实况。
 */
internal object LivePhotoSniffer {

    private const val TAG = StockFetcher.TAG
    private const val MIN_VIDEO_BYTES = 4 shl 10
    private const val MAX_VIDEO_BYTES = 16 shl 20
    private const val LIVE_IOU = 0.55f

    sealed class VideoSource {
        class LocalBytes(val bytes: ByteArray, val how: String) : VideoSource()
        class RemoteUrl(val url: String, val how: String) : VideoSource()
    }

    data class PairCandidate(
        val stillView: View,
        val videoSource: VideoSource,
    )

    /**
     * 主线程快速检查 [target] 区域是否存在「静图 + 实况短片」配对；
     * 未命中立刻返回 null，不拖慢普通图片/视频采集路径。
     */
    fun detect(
        target: Rect,
        shown: ShownViews,
        iouFn: (Rect, Rect) -> Float,
    ): PairCandidate? {
        var stillView: View? = null
        val videoCandidates = ArrayList<View>()
        val liveNamedViews = ArrayList<View>()

        for (n in shown.nodes) {
            if (!shown.mostlyExposed(n, 0.55f)) continue
            val score = maxOf(iouFn(n.rect, target), iouFn(n.content, target))
            if (score < LIVE_IOU) continue
            val v = n.view
            val clsName = v.javaClass.name
            if (clsName.contains("LivePhoto", ignoreCase = true) ||
                clsName.contains("MotionPhoto", ignoreCase = true)
            ) {
                liveNamedViews += v
            }
            if (VideoFrames.isVideo(v) ||
                clsName.contains("PlayerView", ignoreCase = true) ||
                clsName.contains("VideoView", ignoreCase = true)
            ) {
                videoCandidates += v
            } else if (stillView == null && DrawableBitmapFinder.hasVisualContent(v)) {
                stillView = v
            }
        }

        // 必须同时具备视频播放器或明确的 LivePhoto 容器，且能找到对应静图（或由 LivePhoto 容器本身找子 ImageView）
        if (videoCandidates.isEmpty() && liveNamedViews.isEmpty()) return null
        val resolvedStill = stillView ?: liveNamedViews.firstNotNullOfOrNull(::findChildStillView) ?: return null

        val searchRoots = LinkedHashSet<View>()
        searchRoots.addAll(liveNamedViews)
        searchRoots.addAll(videoCandidates)
        // 顺带查静图和视频共同的近邻父容器（最多往上 3 层）
        var p = resolvedStill.parent as? View
        repeat(3) {
            val cur = p ?: return@repeat
            searchRoots += cur
            p = cur.parent as? View
        }
        for (v in videoCandidates) {
            var vp = v.parent as? View
            repeat(2) {
                val cur = vp ?: return@repeat
                searchRoots += cur
                vp = cur.parent as? View
            }
        }

        val ctx = resolvedStill.context
        for (root in searchRoots) {
            val video = extractVideoSource(ctx, root)
            if (video != null) {
                return PairCandidate(resolvedStill, video)
            }
        }
        return null
    }

    /** 后台线程：把 [VideoSource] 变成已校验过 `ftyp` 魔数的 MP4 字节数组。 */
    fun materializeVideo(source: VideoSource, deadline: Long, watchdog: Handler): ByteArray? = when (source) {
        is VideoSource.LocalBytes -> source.bytes.takeIf(::isValidMp4)
        is VideoSource.RemoteUrl -> {
            val bytes = WebImages.download(source.url, null, null, deadline, watchdog)
            bytes?.takeIf { it.size in MIN_VIDEO_BYTES..MAX_VIDEO_BYTES && isValidMp4(it) }
        }
    }

    /** 把静图统一准备为 JPEG 字节（Motion Photo XMP 容器要求主图为 JPEG）。 */
    fun ensureJpegBytes(raw: MediaSourceFinder.Found.Raw?, fallbackBitmap: Bitmap?): Triple<ByteArray, Int, Int>? {
        if (raw != null && raw.mime == "image/jpeg" && !raw.animated && isJpeg(raw.bytes)) {
            return Triple(raw.bytes, raw.width, raw.height)
        }
        val bmp = fallbackBitmap
            ?: raw?.let { android.graphics.BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) }
            ?: return null
        if (bmp.isRecycled) return null
        return try {
            val out = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)) return null
            val bytes = out.toByteArray()
            if (!isJpeg(bytes)) null else Triple(bytes, bmp.width, bmp.height)
        } catch (t: Throwable) {
            Log.w(TAG, "实况主图转 JPEG 失败", t)
            null
        } finally {
            if (bmp !== fallbackBitmap && !bmp.isRecycled) bmp.recycle()
        }
    }

    // ── 播放器数据源探测 ──────────────────────────────────────────────────

    private fun extractVideoSource(context: Context, view: View): VideoSource? {
        // 1. ExoPlayer / Media3 PlayerView.getPlayer().getCurrentMediaItem().localConfiguration.uri
        extractExoPlayerUri(view)?.let { uri ->
            resolveVideoModel(context, uri, "ExoPlayer/Media3 实况")?.let { return it }
        }
        // 2. 常见播放器 / 实况容器字段名浅层扫描
        val seen = IdentityHashMap<Any, Boolean>()
        return scanVideoFields(context, view, 0, seen)
    }

    private fun extractExoPlayerUri(view: View): Uri? {
        val player = runCatching { view.javaClass.getMethod("getPlayer").invoke(view) }.getOrNull()
            ?: findFieldByTypeHint(view, "Player")
            ?: return null
        val item = runCatching { player.javaClass.getMethod("getCurrentMediaItem").invoke(player) }.getOrNull()
            ?: return null
        val localConfig = readField(item, "localConfiguration")
            ?: readField(item, "playbackProperties")
            ?: return null
        return readField(localConfig, "uri") as? Uri
    }

    private fun findFieldByTypeHint(obj: Any, typeHint: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && !isFramework(c)) {
            for (f in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (!f.type.name.contains(typeHint, ignoreCase = true)) continue
                runCatching { f.isAccessible = true; f.get(obj) }.getOrNull()?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    private val VIDEO_FIELD_NAMES = setOf(
        "livePhotoUrl", "liveVideoUrl", "motionVideoUrl", "videoUrl", "videoUri",
        "mUri", "uri", "mVideoPath", "videoPath", "playUrl", "mPlayUrl",
        "dataSource", "mDataSource", "currentVideoPath", "mp4Path", "mp4Url",
    )

    private fun scanVideoFields(
        context: Context,
        obj: Any?,
        depth: Int,
        seen: IdentityHashMap<Any, Boolean>,
    ): VideoSource? {
        if (obj == null || depth > 2 || seen.put(obj, true) != null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null && !isFramework(c)) {
            for (f in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(f.modifiers)) continue
                val fn = f.name
                val interested = fn in VIDEO_FIELD_NAMES ||
                    fn.contains("live", ignoreCase = true) ||
                    fn.contains("video", ignoreCase = true) ||
                    fn.contains("player", ignoreCase = true)
                if (!interested) continue
                val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
                resolveVideoModel(context, v, "实况容器(${obj.javaClass.simpleName}.$fn)")?.let { return it }
                if (depth < 2 && v !is CharSequence && v !is Number && v !is Boolean && !isFramework(v.javaClass)) {
                    // 支持嵌套的 MediaItem / DataSource / Player 模型
                    extractExoPlayerUriFromObj(v)?.let { uri ->
                        resolveVideoModel(context, uri, "播放器模型")?.let { return it }
                    }
                    scanVideoFields(context, v, depth + 1, seen)?.let { return it }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun extractExoPlayerUriFromObj(obj: Any): Uri? {
        val item = runCatching { obj.javaClass.getMethod("getCurrentMediaItem").invoke(obj) }.getOrNull()
            ?: return null
        val localConfig = readField(item, "localConfiguration") ?: readField(item, "playbackProperties") ?: return null
        return readField(localConfig, "uri") as? Uri
    }

    private fun resolveVideoModel(context: Context, model: Any, how: String): VideoSource? = when (model) {
        is File -> readLocalVideo(model, "$how(File)")
        is Uri -> resolveVideoUri(context, model, how)
        is String -> {
            val s = model.trim()
            when {
                (s.startsWith("http://") || s.startsWith("https://")) && looksLikeVideoUrl(s) ->
                    VideoSource.RemoteUrl(s, how)
                s.startsWith("file://") -> resolveVideoUri(context, Uri.parse(s), how)
                s.startsWith("content://") -> resolveVideoUri(context, Uri.parse(s), how)
                s.startsWith("/") && (s.endsWith(".mp4", true) || s.endsWith(".mov", true) || s.contains("live", true)) ->
                    readLocalVideo(File(s), "$how(path)")
                else -> null
            }
        }
        else -> null
    }

    private fun resolveVideoUri(context: Context, uri: Uri, how: String): VideoSource? = when (uri.scheme?.lowercase()) {
        "http", "https" -> uri.toString().takeIf(::looksLikeVideoUrl)?.let { VideoSource.RemoteUrl(it, how) }
        "file" -> uri.path?.let { readLocalVideo(File(it), "$how(file)") }
        "content" -> readContentVideo(context, uri, "$how(content)")
        else -> null
    }

    private fun looksLikeVideoUrl(url: String): Boolean {
        val low = url.lowercase()
        val base = low.substringBefore('?')
        return base.endsWith(".mp4") || base.endsWith(".mov") ||
            low.contains("mp4") || low.contains("livephoto") || low.contains("live_photo") || low.contains("video")
    }

    private fun readLocalVideo(file: File, how: String): VideoSource.LocalBytes? = runCatching {
        if (!file.isFile || !file.canRead()) return null
        val len = file.length()
        if (len < MIN_VIDEO_BYTES || len > MAX_VIDEO_BYTES) return null
        val bytes = file.readBytes()
        if (!isValidMp4(bytes)) null else VideoSource.LocalBytes(bytes, how)
    }.getOrNull()

    private fun readContentVideo(context: Context, uri: Uri, how: String): VideoSource.LocalBytes? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 shl 10)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_VIDEO_BYTES) return null
            }
            val bytes = out.toByteArray()
            if (bytes.size >= MIN_VIDEO_BYTES && isValidMp4(bytes)) VideoSource.LocalBytes(bytes, how) else null
        }
    }.getOrNull()

    private fun findChildStillView(v: View): View? {
        if (DrawableBitmapFinder.hasVisualContent(v)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findChildStillView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte()

    /** 校验 ISO BMFF MP4/MOV `ftyp` 魔数，并排除 HEIC/HEIF/AVIF 静态图品牌。 */
    fun isValidMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        if (bytes[4] != 'f'.code.toByte() || bytes[5] != 't'.code.toByte() ||
            bytes[6] != 'y'.code.toByte() || bytes[7] != 'p'.code.toByte()
        ) return false
        val boxSize = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        if (boxSize < 16 || boxSize > 4096) return false
        val end = minOf(bytes.size, boxSize)
        val brands = HashSet<String>()
        var pos = 8
        while (pos + 4 <= end) {
            if (pos != 12) brands += String(bytes, pos, 4, Charsets.US_ASCII)
            pos += 4
        }
        if ("avif" in brands || "avis" in brands || "heic" in brands || "heix" in brands || "mif1" in brands || "msf1" in brands) {
            return false
        }
        return brands.any { it in setOf("isom", "iso2", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "MSNV", "qt  ") }
    }

    private fun readField(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && !isFramework(c)) {
            runCatching {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            }
            c = c.superclass
        }
        return null
    }

    private fun isFramework(c: Class<*>): Boolean {
        val n = c.name
        return n.startsWith("android.") || n.startsWith("java.") || n.startsWith("com.android.")
    }
}
