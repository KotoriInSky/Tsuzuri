package io.github.kotoriinsky.tsuzuri.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.util.SparseArray
import android.view.View
import android.widget.ImageView
import io.github.kotoriinsky.tsuzuri.capture.engine.ImageUrlUpgrader
import java.io.File
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.IdentityHashMap

/**
 * 进程内通用原图与动图（GIF / 动态 WebP / HEIF / AVIF / 高清大图）探测器。
 *
 * 无需逐个 App 编写私有字段规则，按「零联网原字节 > 本地原文件 > 远程原图 URL」的优先级通吃主流载体：
 *  1. **Glide `GifDrawable`**：直接读 `GifDecoder` 内存中的原始 GIF `ByteBuffer`（零流量、100% 原版动图）；
 *  2. **高清大图/长图查看器**（`SubsamplingScaleImageView` / `BigImageView` 等）：直接读本地原图 `File`/`Uri`；
 *  3. **Glide / Fresco / Coil 请求源**：从 `View` tag / `DraweeHolder` 读取原始 `File` / `Uri` / `URL`，
 *     本地文件直接回传原字节，远程 URL 经 [ImageUrlUpgrader] 优先升级原图后下载原文件字节。
 *
 * 运行在第三方应用进程内：全部只读，任何反射/IO 异常均静默吞掉并回落既有位图路径。
 */
internal object MediaSourceFinder {

    private const val TAG = StockFetcher.TAG
    private const val MAX_BYTES = 24 shl 20
    private const val MAX_DEPTH = 8

    /** 探测到的媒体源：要么已是完整字节，要么是一个可供后台下载的远程 URL。 */
    sealed class Found {
        class Raw(
            val bytes: ByteArray,
            val width: Int,
            val height: Int,
            val mime: String,
            val animated: Boolean,
            val how: String,
        ) : Found()

        class RemoteUrl(
            val url: String,
            val headers: Map<String, String> = emptyMap(),
            val how: String,
        ) : Found()
    }

    /** 魔数探测结果（纯本地，零依赖）。 */
    data class Sniffed(val mime: String, val animated: Boolean)

    /**
     * 主线程调用（快速、零网络 IO）：从 [view] 尝试提取原文件字节或原图地址。
     * 注意：本地 `File` 若体积较小（≤ 24MB）会同步读出字节并校验图片魔数；如果只拿到远程 URL，
     * 返回 [Found.RemoteUrl] 由调用方在后台线程下载。
     */
    fun find(view: View): Found? {
        // 1. Glide GifDrawable 内存原始 GIF ByteBuffer（最快、0 IO、动图 100% 无损）
        extractGlideGif(view)?.let { return it }

        // 2. 分块大图查看器（SubsamplingScaleImageView / PhotoView / BigImageView）本地原图 File/Uri
        extractLargeViewSource(view)?.let { return it }

        // 3. Fresco DraweeHolder 原始 URI（本地 file:// / content:// 或远程 URL）
        extractFrescoSource(view)?.let { return it }

        // 4. Glide / Coil 等挂在 View tag 上的请求模型（File / Uri / GlideUrl / String URL）
        extractTagRequestSource(view)?.let { return it }

        return null
    }

    /** 后台线程调用：按 [ImageUrlUpgrader] 顺序下载远程原图字节并校验图片/动图格式。 */
    fun downloadRemote(
        remote: Found.RemoteUrl,
        deadline: Long,
        watchdog: Handler,
    ): Found.Raw? {
        val urls = ImageUrlUpgrader.candidates(remote.url)
        val ua = remote.headers["User-Agent"] ?: remote.headers["user-agent"]
        val referer = remote.headers["Referer"] ?: remote.headers["referer"]
        for ((idx, candidate) in urls.withIndex()) {
            val bytes = WebImages.download(candidate, ua, referer, deadline, watchdog) ?: continue
            val raw = bytesToRaw(
                bytes,
                if (idx == 0 && urls.size > 1) "${remote.how}·原图升级" else remote.how,
            )
            if (raw != null) return raw
        }
        return null
    }

    /** 把任意字节数组做图片魔数 + 尺寸探测，转成 [Found.Raw]；不是合法图片返回 null。 */
    fun bytesToRaw(bytes: ByteArray, how: String): Found.Raw? {
        if (bytes.size < 16 || bytes.size > MAX_BYTES) return null
        val sniff = sniffImage(bytes) ?: return null
        val size = WebImages.bounds(bytes) ?: return null
        return Found.Raw(
            bytes = bytes,
            width = size.first,
            height = size.second,
            mime = sniff.mime,
            animated = sniff.animated,
            how = how,
        )
    }

    // ── 1. Glide GifDrawable 内存原始 GIF ─────────────────────────────────

    private fun extractGlideGif(view: View): Found.Raw? {
        val seen = IdentityHashMap<Any, Boolean>()
        val roots = listOfNotNull((view as? ImageView)?.drawable, view.background, view.foreground)
        for (d in roots) {
            val buf = findGifBuffer(d, 0, seen) ?: continue
            val bytes = copyBuffer(buf) ?: continue
            bytesToRaw(bytes, "Glide GifDrawable")?.let { return it }
        }
        return null
    }

    private fun findGifBuffer(node: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): ByteBuffer? {
        if (node == null || depth > MAX_DEPTH || seen.put(node, true) != null) return null
        val clsName = node.javaClass.name
        if (clsName.contains("GifDrawable", ignoreCase = true)) {
            // Glide GifDrawable.getBuffer() 直接返回底层只读 ByteBuffer
            runCatching { node.javaClass.getMethod("getBuffer").invoke(node) as? ByteBuffer }
                .getOrNull()?.let { return it }
            // 混淆场景：顺着 state -> frameLoader -> gifDecoder 找 ByteBuffer 字段
            findByteBufferField(node, 0, IdentityHashMap())?.let { return it }
        }
        when (node) {
            is LayerDrawable -> {
                for (i in 0 until node.numberOfLayers) {
                    findGifBuffer(node.getDrawable(i), depth + 1, seen)?.let { return it }
                }
            }
            is DrawableWrapper -> findGifBuffer(node.drawable, depth + 1, seen)?.let { return it }
            is DrawableContainer -> findGifBuffer(node.current, depth + 1, seen)?.let { return it }
        }
        return null
    }

    private fun findByteBufferField(obj: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): ByteBuffer? {
        if (obj == null || depth > 4 || seen.put(obj, true) != null) return null
        if (obj is ByteBuffer) return obj
        var c: Class<*>? = obj.javaClass
        while (c != null && !isFramework(c)) {
            for (f in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
                if (v is ByteBuffer) return v
                if (!v.javaClass.isPrimitive && v !is CharSequence && v !is Number && v !is Boolean && !isFramework(v.javaClass)) {
                    findByteBufferField(v, depth + 1, seen)?.let { return it }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun copyBuffer(buf: ByteBuffer): ByteArray? = runCatching {
        val dup = buf.asReadOnlyBuffer()
        dup.position(0)
        val len = dup.remaining()
        if (len < 16 || len > MAX_BYTES) return null
        ByteArray(len).also { dup.get(it) }
    }.getOrNull()

    // ── 2. 高清大图/长图查看器（SubsamplingScaleImageView 等）────────────────

    private fun extractLargeViewSource(view: View): Found? {
        val name = view.javaClass.name
        if (!name.contains("SubsamplingScaleImageView", ignoreCase = true) &&
            !name.contains("BigImageView", ignoreCase = true) &&
            !name.contains("LargeImageView", ignoreCase = true) &&
            !name.contains("PhotoView", ignoreCase = true)
        ) return null
        var c: Class<*>? = view.javaClass
        while (c != null && !isFramework(c)) {
            for (f in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(f.modifiers)) continue
                val fn = f.name
                if (fn != "uri" && fn != "mUri" && fn != "file" && fn != "mFile" &&
                    fn != "mCurrentImageFile" && fn != "currentImageFile" && fn != "imageUri"
                ) continue
                val v = runCatching { f.isAccessible = true; f.get(view) }.getOrNull() ?: continue
                resolveModel(view.context, v, "大图原文件(${view.javaClass.simpleName})")?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    // ── 3. Fresco DraweeHolder 原始 URI ───────────────────────────────────

    private fun extractFrescoSource(view: View): Found? {
        val holder = readFieldAny(view, "mDraweeHolder") ?: return null
        val controller = readFieldAny(holder, "mController")
            ?: runCatching { holder.javaClass.getMethod("getController").invoke(holder) }.getOrNull()
            ?: return null
        // 顺着 controller 浅搜包含 ImageRequest / mSourceUri 的字段（兼容 Fresco 常见版本与轻度混淆）
        val seen = IdentityHashMap<Any, Boolean>()
        val uri = findUriInObject(controller, 0, seen) ?: return null
        return resolveModel(view.context, uri, "Fresco 原图")
    }

    private fun findUriInObject(obj: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): Uri? {
        if (obj == null || depth > 3 || seen.put(obj, true) != null) return null
        if (obj is Uri) return obj.takeIf { isMediaUri(it) }
        // 常见公开方法 getSourceUri()
        runCatching { obj.javaClass.getMethod("getSourceUri").invoke(obj) as? Uri }
            .getOrNull()?.takeIf { isMediaUri(it) }?.let { return it }
        var c: Class<*>? = obj.javaClass
        while (c != null && !isFramework(c)) {
            for (f in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
                if (v is Uri && isMediaUri(v)) return v
                val tn = v.javaClass.name
                if (tn.contains("ImageRequest", ignoreCase = true) ||
                    tn.contains("Drawee", ignoreCase = true) ||
                    f.name == "mSourceUri" || f.name == "mImageRequest"
                ) {
                    findUriInObject(v, depth + 1, seen)?.let { return it }
                }
            }
            c = c.superclass
        }
        return null
    }

    // ── 4. Glide / Coil View Tag 请求源 ───────────────────────────────────

    private fun extractTagRequestSource(view: View): Found? {
        val tags = collectViewTags(view)
        for (tag in tags) {
            if (tag == null) continue
            val clsName = tag.javaClass.name
            // Glide SingleRequest / ThumbnailRequestCoordinator
            if (clsName.contains("bumptech.glide", ignoreCase = true) ||
                clsName.contains("SingleRequest", ignoreCase = true)
            ) {
                extractGlideRequest(view.context, tag, 0, IdentityHashMap())?.let { return it }
            }
            // Coil ViewTargetRequestManager / ImageRequest
            if (clsName.contains("coil", ignoreCase = true)) {
                extractCoilRequest(view.context, tag, 0, IdentityHashMap())?.let { return it }
            }
            // 部分 App 直接把图片 URL / File / Uri 存在 View tag 里
            if (tag is File || tag is Uri) {
                resolveModel(view.context, tag, "ViewTag 原图")?.let { return it }
            }
        }
        return null
    }

    private fun extractGlideRequest(
        context: Context,
        req: Any,
        depth: Int,
        seen: IdentityHashMap<Any, Boolean>,
    ): Found? {
        if (depth > 3 || seen.put(req, true) != null) return null
        // SingleRequest.model 或其混淆字段
        readFieldAny(req, "model")?.let { model ->
            resolveModel(context, model, "Glide 原图")?.let { return it }
        }
        var c: Class<*>? = req.javaClass
        while (c != null && !isFramework(c)) {
            for (f in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(req) }.getOrNull() ?: continue
                when {
                    v is File || v is Uri -> resolveModel(context, v, "Glide 原图")?.let { return it }
                    v is String && isHttpUrl(v) -> resolveModel(context, v, "Glide 原图")?.let { return it }
                    v.javaClass.name.contains("GlideUrl", ignoreCase = true) ->
                        resolveGlideUrl(v)?.let { return it }
                    v.javaClass.name.contains("Request", ignoreCase = true) ->
                        extractGlideRequest(context, v, depth + 1, seen)?.let { return it }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun resolveGlideUrl(glideUrl: Any): Found.RemoteUrl? {
        val url = runCatching { glideUrl.javaClass.getMethod("toStringUrl").invoke(glideUrl) as? String }.getOrNull()
            ?: glideUrl.toString()
        if (!isHttpUrl(url)) return null
        @Suppress("UNCHECKED_CAST")
        val headers = runCatching {
            glideUrl.javaClass.getMethod("getHeaders").invoke(glideUrl) as? Map<String, String>
        }.getOrNull().orEmpty()
        return Found.RemoteUrl(url, headers, "Glide 原图")
    }

    private fun extractCoilRequest(
        context: Context,
        obj: Any,
        depth: Int,
        seen: IdentityHashMap<Any, Boolean>,
    ): Found? {
        if (depth > 4 || seen.put(obj, true) != null) return null
        readFieldAny(obj, "data")?.let { data ->
            resolveModel(context, data, "Coil 原图")?.let { return it }
        }
        var c: Class<*>? = obj.javaClass
        while (c != null && !isFramework(c)) {
            for (f in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
                if (v is File || v is Uri || (v is String && isHttpUrl(v))) {
                    resolveModel(context, v, "Coil 原图")?.let { return it }
                } else if (v.javaClass.name.contains("coil", ignoreCase = true)) {
                    extractCoilRequest(context, v, depth + 1, seen)?.let { return it }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun collectViewTags(view: View): List<Any?> {
        val out = ArrayList<Any?>()
        out += view.tag
        runCatching {
            val f = View::class.java.getDeclaredField("mKeyedTags")
            f.isAccessible = true
            val arr = f.get(view) as? SparseArray<*> ?: return@runCatching
            for (i in 0 until arr.size()) out += arr.valueAt(i)
        }
        return out
    }

    // ── 模型解析与本地文件读取 ─────────────────────────────────────────────

    private fun resolveModel(context: Context, model: Any, how: String): Found? {
        return when (model) {
            is File -> readLocalFile(model, "$how(本地文件)")
            is Uri -> resolveUri(context, model, how)
            is String -> when {
                isHttpUrl(model) -> Found.RemoteUrl(model, how = how)
                model.startsWith("file://") -> resolveUri(context, Uri.parse(model), how)
                model.startsWith("content://") -> resolveUri(context, Uri.parse(model), how)
                model.startsWith("/") -> readLocalFile(File(model), "$how(本地路径)")
                else -> null
            }
            else -> {
                if (model.javaClass.name.contains("GlideUrl", ignoreCase = true)) {
                    resolveGlideUrl(model)
                } else {
                    null
                }
            }
        }
    }

    private fun resolveUri(context: Context, uri: Uri, how: String): Found? = when (uri.scheme?.lowercase()) {
        "http", "https" -> Found.RemoteUrl(uri.toString(), how = how)
        "file" -> uri.path?.let { readLocalFile(File(it), "$how(file)") }
        "content" -> readContentUri(context, uri, "$how(content)")
        else -> null
    }

    private fun readLocalFile(file: File, how: String): Found.Raw? = runCatching {
        if (!file.isFile || !file.canRead()) return null
        val len = file.length()
        if (len < 16 || len > MAX_BYTES) return null
        val bytes = file.readBytes()
        bytesToRaw(bytes, how)
    }.getOrNull()

    private fun readContentUri(context: Context, uri: Uri, how: String): Found.Raw? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(32 shl 10)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_BYTES) return null
            }
            bytesToRaw(buf.toByteArray(), how)
        }
    }.getOrNull()

    // ── 魔数与动图探测 ────────────────────────────────────────────────────

    /**
     * 轻量图片魔数与动图探测：识别 JPEG / PNG / GIF（恒视为动图或保留原文件）/
     * WebP（检测 VP8X ANIM 标记区分动图与静图）/ HEIC・HEIF・AVIF（检测 `msf1`/`avis` 序列品牌）。
     */
    fun sniffImage(bytes: ByteArray): Sniffed? {
        fun match(offset: Int, sig: ByteArray): Boolean =
            bytes.size >= offset + sig.size && sig.indices.all { bytes[offset + it] == sig[it] }
        fun matchAscii(offset: Int, s: String): Boolean = match(offset, s.toByteArray(Charsets.US_ASCII))

        return when {
            matchAscii(0, "GIF87a") || matchAscii(0, "GIF89a") -> Sniffed("image/gif", animated = true)
            match(0, byteArrayOf(-1, -40, -1)) -> Sniffed("image/jpeg", animated = false)
            match(0, byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> Sniffed("image/png", animated = false)
            matchAscii(0, "RIFF") && matchAscii(8, "WEBP") -> {
                // VP8X 标志位 bit 1（0x02）表示含 ANIM 动图帧
                val animated = bytes.size >= 21 && matchAscii(12, "VP8X") && ((bytes[20].toInt() and 0x02) != 0)
                Sniffed("image/webp", animated = animated)
            }
            bytes.size >= 16 && matchAscii(4, "ftyp") -> {
                val boxSize = ((bytes[0].toInt() and 0xFF) shl 24) or
                    ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    (bytes[3].toInt() and 0xFF)
                if (boxSize < 16 || boxSize > 4096) return null
                val end = minOf(bytes.size, boxSize)
                val brands = HashSet<String>()
                var pos = 8
                while (pos + 4 <= end) {
                    if (pos != 12) brands += String(bytes, pos, 4, Charsets.US_ASCII)
                    pos += 4
                }
                when {
                    "avis" in brands -> Sniffed("image/avif", animated = true)
                    "avif" in brands -> Sniffed("image/avif", animated = false)
                    "msf1" in brands || "hevc" in brands -> Sniffed("image/heif", animated = "msf1" in brands)
                    "heic" in brands || "heix" in brands -> Sniffed("image/heic", animated = false)
                    "mif1" in brands -> Sniffed("image/heif", animated = false)
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun readFieldAny(obj: Any, name: String): Any? {
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

    private fun isHttpUrl(s: String): Boolean = s.startsWith("http://") || s.startsWith("https://")

    private fun isMediaUri(uri: Uri): Boolean =
        uri.scheme?.lowercase() in setOf("http", "https", "file", "content")

    private fun isFramework(c: Class<*>): Boolean {
        val n = c.name
        return n.startsWith("android.") || n.startsWith("java.") || n.startsWith("com.android.")
    }

    /** 辅助：判断 [view] 当前是否挂着系统 [AnimatedImageDrawable]。 */
    fun hasNativeAnimatedDrawable(view: View): Boolean {
        val roots = listOfNotNull((view as? ImageView)?.drawable, view.background, view.foreground)
        return roots.any { it is AnimatedImageDrawable }
    }
}
