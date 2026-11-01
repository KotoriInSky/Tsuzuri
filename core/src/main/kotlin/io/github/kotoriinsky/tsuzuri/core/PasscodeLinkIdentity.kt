package io.github.kotoriinsky.tsuzuri.core

import java.net.URI
import java.util.Locale

/**
 * 用解析后的内容身份合并长链、短链和带文案的同一条分享。
 *
 * 为什么需要它：同一条内容会以「裸号 / 短链 / 长链 / 标题＋链接＋复制话术」几种形态
 * 进剪贴板。按原文去重会把它们当成不同事件 —— 用户看到的是**两条岛**。
 * 展示侧因此按内容身份去重，并且用身份推导通知 id（同内容 = 同一条通知）。
 */
object PasscodeLinkIdentity {

    /**
     * 从**已经匹配出来的字段**算身份，不再重跑一遍规则引擎。
     *
     * 优先用平台内容号（`content:<包名>:<号>`）：B 站的长链 / 短链 / 分享文案里带的是同一个
     * BV 号，号相同就是同一条内容。`core` 不是「号」形态（例如是标题、含 CJK）时回落到
     * [fromUrl] —— URL 身份保留查询参数与片段，因为参数可能就是内容本身或访问凭据。
     *
     * 号规则（裸 BV / 淘口令 / 快递单号）以前拿不到身份（旧实现只在 `rule == "url"` 时算），
     * 所以「同一个号的不同写法」根本没有去重，这次一并覆盖。
     */
    fun fromMatch(platformPkg: String?, core: String?, url: String?): String? {
        val pkg = platformPkg?.takeIf { it.isNotBlank() }
        val id = core?.trim()?.takeIf { it.isNotEmpty() }
        if (pkg != null && id != null && PasscodeIsland.isIdLike(id)) return "content:$pkg:$id"
        return url?.let { fromUrl(it) }
    }

    fun fromUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https") || uri.rawUserInfo != null) return null

        val match = PasscodeRules.match(url)
        match?.let(PasscodeRules::duplicateIdentity)?.let { return it }
        val contentId = match?.core?.takeIf { core ->
            core.length in 4..24 && core.all { it.code < 0x80 &&
                (it.isLetterOrDigit() || it == '_' || it == '-' || it == '/' || it == '.')
            }
        }
        val platform = match?.platform?.pkg
        if (platform != null && contentId != null) return "content:$platform:$contentId"

        // 未知平台保留查询参数与片段；它们可能就是内容或访问凭据。
        // 但**身份**要先把「每次分享都变」的跟踪参数归一掉（[UrlCleanup]，清单借自 ParseHub/MIT）
        // —— 否则同一条内容换个 token 就变两个身份，用户看到两个岛。
        val authority = if (host.contains(':')) "[$host]" else host
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = UrlCleanup.cleanQuery(host, uri.rawQuery)?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "url:$scheme://$authority$port$path$query$fragment"
    }
}
