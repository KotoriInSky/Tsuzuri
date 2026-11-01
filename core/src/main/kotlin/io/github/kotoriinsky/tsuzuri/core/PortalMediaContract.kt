package io.github.kotoriinsky.tsuzuri.core

import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 原媒体通道；分享保留只读原件，编辑使用独立副本。 */
object PortalMediaContract {
    private val savedTime = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT)
    private val networkHost = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,62}")
    fun savedName(timeMs: Long, format: Format): String =
        "Taplus_${savedTime.format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))}.${format.extension}"
    const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.portalmedia"
    const val SEGMENT = "media"
    const val EDIT_SEGMENT = "edit"
    const val CAPTURE_MARKER = "__tsuzuri_media_capture_v1__"
    const val IMAGE_SOURCE = "fp_media_image"
    const val VIDEO_SOURCE = "fp_media_video"
    const val LIVE_SOURCE = "fp_media_live"
    const val CAPTURE_ORIGIN = "fp_media_origin"
    /** 最后切到图片模式，让前面的 Bitmap 按字段名保留，避免 ROM 的单槽覆盖。 */
    fun bitmapFieldRule(fields: Collection<String>): String =
        (fields.filter { it.isNotBlank() && it != "viewBitmap" }.distinct() + "viewBitmap").joinToString(",")
    enum class Format(val mime: String, val extension: String) {
        JPEG("image/jpeg", "jpg"), PNG("image/png", "png"),
        GIF("image/gif", "gif"), WEBP("image/webp", "webp"), MP4("video/mp4", "mp4"),
        HEIC("image/heic", "heic"), HEIF("image/heif", "heif"), AVIF("image/avif", "avif"),
    }
    fun sniff(bytes: ByteArray): Format? {
        fun has(offset: Int, signature: ByteArray): Boolean = bytes.size >= offset + signature.size &&
            signature.indices.all { bytes[offset + it] == signature[it] }
        fun has(offset: Int, signature: String) = has(offset, signature.toByteArray(Charsets.US_ASCII))
        return when {
            has(0, "GIF87a") || has(0, "GIF89a") -> Format.GIF
            has(0, byteArrayOf(-1, -40, -1)) -> Format.JPEG
            has(0, byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> Format.PNG
            has(0, "RIFF") && has(8, "WEBP") -> Format.WEBP
            has(4, "ftyp") -> {
                // ISO BMFF 的主品牌和兼容品牌都参与判断，不能把 HEIF 当作视频。
                val size = if (bytes.size >= 12) (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[i].toLong() and 255) } else 0
                if (size < 16 || size % 4 != 0L || size > 4096) return null
                val offsets = listOf(8) + (16 until minOf(bytes.size, size.toInt()) step 4)
                fun brand(vararg names: String) = offsets.any { offset -> names.any { has(offset, it) } }
                when {
                    brand("avif", "avis") -> Format.AVIF
                    brand("heic", "heix", "hevc", "hevx") -> Format.HEIC
                    brand("mif1", "msf1") -> Format.HEIF
                    brand("isom", "iso2", "iso6", "mp41", "mp42", "avc1", "M4V ", "MSNV") -> Format.MP4
                    else -> null
                }
            }
            else -> null
        }
    }
    fun safeToken(token: String): Boolean {
        val stem = token.substringBeforeLast('.', "")
        return stem.length in 32..64 && stem.all { it in '0'..'9' || it in 'a'..'f' } &&
            Format.entries.any { token.endsWith(".${it.extension}") }
    }
    fun tokenMime(token: String): String? = if (safeToken(token)) {
        Format.entries.single { token.endsWith(".${it.extension}") }.mime
    } else null

    fun safeSegment(segment: String): Boolean = segment == SEGMENT || segment == EDIT_SEGMENT

    /** 宿主不能转授另一 UID 的写权限；仅系统编辑器可直接写高熵编辑副本。 */
    fun isDirectEditWriter(segment: String, packages: List<String>): Boolean =
        segment == EDIT_SEGMENT && PortalTempImageContract.MEDIA_EDITOR_PACKAGE in packages

    /** 已返回的媒体地址通用接收，不按 App 的 CDN 名单过滤；重定向逐跳复用判定。 */
    fun allowedUrl(value: String): Boolean = try {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".").orEmpty()
        uri.scheme in listOf("http", "https") && uri.userInfo == null &&
            uri.port in listOf(-1, 80, 443) &&
            networkHost.matches(host) &&
            host.substringAfterLast('.') !in setOf("localhost", "local", "lan", "internal", "invalid", "test")
    } catch (_: Exception) { false }
    /** 当前应用模型会返回 HTTP CDN 地址；宿主禁止明文，升级协议时保留签名参数。 */
    fun requestUrl(value: String): String? {
        if (!allowedUrl(value)) return null
        val uri = URI(value)
        if (uri.scheme != "http") return value
        return "https://${uri.rawAuthority.removeSuffix(":80")}${uri.rawPath.orEmpty()}" +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    }
    /** 标准网络图片通用接收；content URI 由实际调用方读取，不接纳 App 私有文件路径。 */
    fun imageSource(value: String): String? {
        val normalized = if (value.startsWith("//")) "https:$value" else value
        requestUrl(normalized)?.let { return it }
        return runCatching {
            val uri = URI(normalized)
            normalized.takeIf { uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null && !uri.path.isNullOrBlank() }
        }.getOrNull()
    }
    class Session {
        private val generation = AtomicLong()
        fun begin(): Long = generation.incrementAndGet()
        fun clear() { generation.incrementAndGet() }
        fun isCurrent(id: Long): Boolean = generation.get() == id
    }
}
