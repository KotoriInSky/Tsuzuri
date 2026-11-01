package io.github.kotoriinsky.tsuzuri.core

import java.nio.charset.Charset

/**
 * HTML 的字符集探测与解码 —— **"长尾站点靠 HTTP 抓标题"的前提**。
 *
 * 为什么必须有：抓取链路原来一律 `String(bytes, Charsets.UTF_8)`。中文站点里
 * GBK / GB18030 仍然常见（尤其长尾老站），解出来是**乱码标题** —— 比没有标题更糟，
 * 用户看到一串乱码会以为模块坏了。
 *
 * 判据顺序（思路与 open-graph-scraper 的 `chardet` + `iconv-lite` 同源，
 * 只做必要的前三级，不引库）：
 *  ① 响应头 `Content-Type: …; charset=…`（最权威）
 *  ② BOM（UTF-8 / UTF-16LE / UTF-16BE）
 *  ③ HTML 头部前几 KB 里的 `<meta charset>` 与 `<meta http-equiv=… charset=…>`
 *  ④ 回落 UTF-8
 *
 * 放在 `:core` 的理由：**纯函数、无 Android 依赖**，可以用固定字节离线单测，
 * 不需要设备也不需要真发请求（抓取链路在 SystemUI 里，测不动）。
 * 约定：**永不抛异常** —— 调用方在抓取链路上，异常会变成"标题没了"。
 */
object HtmlCharset {

    /** 嗅 `<meta>` 的窗口：charset 声明必须在文档很靠前的位置才有效，4KB 足够。 */
    private const val SNIFF_BYTES = 4096

    private val CHARSET_PARAM =
        Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    /**
     * 探测字符集。[headerContentType] 传响应头原文（`text/html; charset=GBK` 这种）；
     * [size] 是**有效字节数**（调用方按上限截断过）。
     */
    fun detect(headerContentType: String?, bytes: ByteArray, size: Int = bytes.size): Charset {
        headerCharset(headerContentType)?.let { return it }
        bomCharset(bytes, size)?.let { return it }
        metaCharset(bytes, size)?.let { return it }
        return Charsets.UTF_8
    }

    /** 按探测结果解码。[size] 之外的内容不参与。 */
    fun decode(bytes: ByteArray, headerContentType: String?, size: Int = bytes.size): String {
        val safeSize = size.coerceIn(0, bytes.size)
        val charset = detect(headerContentType, bytes, safeSize)
        // BOM 只在**探测结果与之一致**时才剥掉：响应头说了别的字符集时，那几个字节是内容。
        val skip = bomLength(bytes, safeSize).takeIf { bomCharset(bytes, safeSize) == charset } ?: 0
        return String(bytes, skip, (safeSize - skip).coerceAtLeast(0), charset)
    }

    private fun headerCharset(contentType: String?): Charset? =
        contentType?.let { CHARSET_PARAM.find(it)?.groupValues?.get(1) }?.toCharsetOrNull()

    /**
     * `<meta>` 里的声明。用 ISO-8859-1 做一次"字节安全"的预解码 ——
     * 声明本身一定是 ASCII，即使正文是 GBK 也不会影响匹配。
     */
    private fun metaCharset(bytes: ByteArray, size: Int): Charset? {
        val window = minOf(size, SNIFF_BYTES).coerceAtLeast(0)
        if (window <= 0) return null
        val head = String(bytes, 0, window, Charsets.ISO_8859_1)
        return CHARSET_PARAM.find(head)?.groupValues?.get(1)?.toCharsetOrNull()
    }

    private fun String?.toCharsetOrNull(): Charset? {
        val name = this?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            if (Charset.isSupported(name)) Charset.forName(name) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun bomLength(bytes: ByteArray, size: Int): Int = when {
        size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
        size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> 2
        size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> 2
        else -> 0
    }

    private fun bomCharset(bytes: ByteArray, size: Int): Charset? = when {
        bomLength(bytes, size) == 0 -> null
        bytes[0] == 0xEF.toByte() -> Charsets.UTF_8
        bytes[0] == 0xFF.toByte() -> Charsets.UTF_16LE
        else -> Charsets.UTF_16BE
    }
}
