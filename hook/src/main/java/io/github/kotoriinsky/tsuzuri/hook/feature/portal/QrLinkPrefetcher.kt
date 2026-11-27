package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import io.github.kotoriinsky.tsuzuri.core.HtmlCharset
import io.github.kotoriinsky.tsuzuri.core.PageMetaExtract
import io.github.kotoriinsky.tsuzuri.core.PageTitle
import io.github.kotoriinsky.tsuzuri.core.PublicSuffix
import io.github.kotoriinsky.tsuzuri.core.QrJumpResolver
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * 二维码链接分层安全预加载器：
 *
 *  - 跳过非 HTTP(S)、非标准端口及含登录/支付/核销/一次性 token 特征的敏感链接（不发请求、不误触状态）；
 *  - 手动跟踪最多 3 跳重定向，每跳前用 [InetAddress.getAllByName] 拦截内网/回环/保留地址（防 SSRF）；
 *  - 仅读取 `text/html` 前 64KB 提取网页标题（复用了 [HtmlCharset]、[PageMetaExtract]、[PageTitle]），
 *    遇到 APK 安装包或非网页流立即断开并给出风险/真实域名提示，不执行 JS、不下载文件。
 */
internal object QrLinkPrefetcher {

    private const val TAG = "FP-QrPrefetch"
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val READ_TIMEOUT_MS = 1500
    private const val MAX_REDIRECTS = 3
    private const val MAX_HTML_BYTES = 64 * 1024
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val SENSITIVE_PATH_OR_QUERY = Regex(
        """(^|[/&?_=-])(login|signin|sign_in|auth|oauth|sso|pay|payment|checkout|cashier|qrlogin|scanlogin|confirm|verify|redeem|ticket|token|unsubscribe)([=/&?_.-]|$)""",
        RegexOption.IGNORE_CASE,
    )

    data class Preview(
        val title: String?,
        val finalUrl: String,
        val isRiskyDownload: Boolean = false,
        val note: String? = null,
    )

    private val cache = LruCache<String, Preview>(16)
    private val main = Handler(Looper.getMainLooper())

    fun shouldPrefetch(action: QrJumpResolver.Action): Boolean {
        if (action.kind != QrJumpResolver.Kind.PLATFORM) return false
        val target = action.primaryTarget?.trim() ?: return false
        val uri = runCatching { Uri.parse(target) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val port = uri.port
        if (port != -1 && port != 80 && port != 443) return false
        val pathAndQuery = buildString {
            uri.encodedPath?.let(::append)
            uri.encodedQuery?.let { append('?').append(it) }
        }
        if (SENSITIVE_PATH_OR_QUERY.containsMatchIn(pathAndQuery)) return false
        return true
    }

    fun prefetchAsync(action: QrJumpResolver.Action, onDone: (Preview) -> Unit) {
        if (!shouldPrefetch(action)) return
        val target = action.primaryTarget ?: return
        synchronized(cache) { cache.get(target) }?.let { cached ->
            main.post { onDone(cached) }
            return
        }
        Thread({
            val result = runCatching { fetchSync(target) }.getOrNull() ?: return@Thread
            synchronized(cache) { cache.put(target, result) }
            main.post { onDone(result) }
        }, "fp-qr-prefetch").start()
    }

    /** 格式化安全副标题：若短链跨域名重定向，同时展示「原域名 → 最终域名」防钓鱼。 */
    fun formatSubtitle(action: QrJumpResolver.Action, preview: Preview?): String {
        if (preview == null) return action.subtitle
        val origUrl = action.primaryTarget ?: return action.subtitle
        val origUri = runCatching { Uri.parse(origUrl) }.getOrNull()
        val finalUri = runCatching { Uri.parse(preview.finalUrl) }.getOrNull()
        val origHost = origUri?.host?.removePrefix("www.")?.lowercase(Locale.ROOT)
        val finalHost = finalUri?.host?.removePrefix("www.")?.lowercase(Locale.ROOT)

        if (preview.isRiskyDownload) {
            val hostLabel = finalHost ?: origHost ?: action.subtitle
            return "安装包下载（注意来源）· $hostLabel"
        }

        val origRoot = origHost?.let { PublicSuffix.registrableDomain(it) ?: it }
        val finalRoot = finalHost?.let { PublicSuffix.registrableDomain(it) ?: it }
        if (origRoot != null && finalRoot != null && origRoot != finalRoot) {
            val shortFinal = preview.finalUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .removeSuffix("/")
                .let { if (it.length > 48) it.take(47) + "…" else it }
            return "$origHost → $shortFinal"
        }
        return preview.note ?: action.subtitle
    }

    private fun fetchSync(startUrl: String): Preview? {
        var currentUrl = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            val uri = runCatching { URI(currentUrl) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            if (isBlockedHost(host)) {
                Log.w(TAG, "拦截内网/保留地址预加载：$host")
                return null
            }

            if (uri.path?.lowercase(Locale.ROOT)?.endsWith(".apk") == true) {
                return Preview(title = null, finalUrl = currentUrl, isRiskyDownload = true)
            }

            val conn = (uri.toURL().openConnection() as? HttpURLConnection) ?: return null
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")

                val code = conn.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = conn.getHeaderField("Location")?.takeIf { it.isNotBlank() } ?: return null
                    val nextUrl = runCatching { uri.resolve(loc).toString() }.getOrNull() ?: return null
                    val nextUri = runCatching { Uri.parse(nextUrl) }.getOrNull() ?: return null
                    val nextPathAndQuery = buildString {
                        nextUri.encodedPath?.let(::append)
                        nextUri.encodedQuery?.let { append('?').append(it) }
                    }
                    if (SENSITIVE_PATH_OR_QUERY.containsMatchIn(nextPathAndQuery)) {
                        return Preview(title = null, finalUrl = currentUrl)
                    }
                    currentUrl = nextUrl
                    continue
                }

                if (code !in 200..299) {
                    return if (currentUrl != startUrl) Preview(title = null, finalUrl = currentUrl) else null
                }

                val contentType = conn.contentType.orEmpty().lowercase(Locale.ROOT)
                if (contentType.contains("application/vnd.android.package-archive")) {
                    return Preview(title = null, finalUrl = currentUrl, isRiskyDownload = true)
                }
                if (!contentType.contains("text/html") && !contentType.contains("application/xhtml")) {
                    return if (currentUrl != startUrl) Preview(title = null, finalUrl = currentUrl) else null
                }

                val buf = ByteArray(MAX_HTML_BYTES)
                var off = 0
                conn.inputStream.use { stream ->
                    while (off < buf.size) {
                        val n = stream.read(buf, off, buf.size - off)
                        if (n <= 0) break
                        off += n
                    }
                }
                if (off <= 0) return Preview(title = null, finalUrl = currentUrl)
                val html = HtmlCharset.decode(buf, contentType, off)
                val extracted = PageMetaExtract.extract(html, currentUrl)
                val cleanedTitle = extracted.title
                    ?.let { PageTitle.clean(it, currentUrl) ?: it }
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf { it.length >= 2 }
                return Preview(title = cleanedTitle, finalUrl = currentUrl)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        return Preview(title = null, finalUrl = currentUrl)
    }

    private fun isBlockedHost(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host).any { addr ->
            addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress ||
                (addr.address.size == 16 && (addr.address[0].toInt() and 0xfe) == 0xfc)
        }
    }.getOrDefault(true)
}
