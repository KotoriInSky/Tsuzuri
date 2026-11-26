package io.github.kotoriinsky.tsuzuri.capture.engine

import java.net.URI
import java.util.Locale

/**
 * 纯本地 CDN 缩略图 URL → 原图 URL 升级器（零网络、零外部依赖）。
 *
 * 永远返回「升级候选 + 原 URL 兜底」的有序列表：调用方优先尝试升级后的原图地址，
 * 一旦遇到 404/403 自动回退原地址，保证原图成功率只增不减。
 */
object ImageUrlUpgrader {

    private val BILI_AT_SUFFIX = Regex("@[^/?#]+$")
    private val WEIBO_SIZE_SEGMENT = Regex("^/(?:orj360|orj480|mw690|mw1024|bmiddle|thumbnail|thumb150|thumb180|wap360|wap720|square)/")
    private val ZHIHU_SIZE_SUFFIX = Regex("_(?:720w|b|l|hd|qhd|xs|s|m)(?=\\.[A-Za-z0-9]+$)", RegexOption.IGNORE_CASE)
    private val DOUBAN_SIZE_SEGMENT = Regex("/view/(?:photo|richtext)/(?:m|s|sqxs|small|medium)/")
    private val TWIMG_NAME_PARAM = Regex("([?&])name=[^&]+", RegexOption.IGNORE_CASE)

    /** 返回按优先级排序的下载地址列表（去重）；无升级规则时仅返回 `[url]`。 */
    fun candidates(url: String): List<String> {
        val raw = url.trim()
        if (raw.isEmpty()) return emptyList()
        val upgraded = upgradeOrNull(raw)
        return if (upgraded != null && upgraded != raw) listOf(upgraded, raw) else listOf(raw)
    }

    /** 若命中已知主流图床的缩略图样式，返回对应的原图 URL；否则返回 null。 */
    fun upgradeOrNull(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.rawPath ?: return null

        // 1. B 站 CDN：i0.hdslb.com/bfs/xxx.jpg@672w_378h_1c.webp → 去掉 @ 后的缩放转码参数
        if (host.endsWith(".hdslb.com") || host == "hdslb.com") {
            val cleaned = BILI_AT_SUFFIX.replace(path, "")
            if (cleaned != path && cleaned.contains('.')) {
                return rebuild(scheme, uri.rawAuthority, cleaned, uri.rawQuery)
            }
            return null
        }

        // 2. 微博图床：wx*.sinaimg.cn/orj360/xxx.jpg → /large/xxx.jpg（保留 .gif/.jpg）
        if (host.endsWith(".sinaimg.cn")) {
            val replaced = WEIBO_SIZE_SEGMENT.replaceFirst(path, "/large/")
            if (replaced != path) {
                return rebuild(scheme, uri.rawAuthority, replaced, uri.rawQuery)
            }
            return null
        }

        // 3. X / Twitter：pbs.twimg.com/media/xxx?format=jpg&name=small → name=orig
        if (host == "pbs.twimg.com" && path.startsWith("/media/")) {
            val q = uri.rawQuery
            val newQuery = when {
                q.isNullOrEmpty() -> "name=orig"
                TWIMG_NAME_PARAM.containsMatchIn("?$q") ->
                    TWIMG_NAME_PARAM.replace("?$q", "$1name=orig").removePrefix("?")
                else -> "$q&name=orig"
            }
            return rebuild(scheme, uri.rawAuthority, path, newQuery)
        }

        // 4. 知乎图床：pic*.zhimg.com/v2-xxx_720w.jpg → /v2-xxx.jpg
        if (host.endsWith(".zhimg.com")) {
            val cleaned = ZHIHU_SIZE_SUFFIX.replace(path, "")
            if (cleaned != path) {
                return rebuild(scheme, uri.rawAuthority, cleaned, uri.rawQuery)
            }
            return null
        }

        // 5. 豆瓣图床：img*.doubanio.com/view/photo/m/public/p123.webp → /view/photo/l/public/p123.webp
        if (host.endsWith(".doubanio.com")) {
            val replaced = DOUBAN_SIZE_SEGMENT.replace(path) { m ->
                m.value.replace(Regex("/(?:m|s|sqxs|small|medium)/$"), "/l/")
            }
            if (replaced != path) {
                return rebuild(scheme, uri.rawAuthority, replaced, uri.rawQuery)
            }
            return null
        }

        return null
    }

    private fun rebuild(scheme: String, authority: String?, path: String, query: String?): String? {
        if (authority.isNullOrBlank()) return null
        return buildString {
            append(scheme).append("://").append(authority).append(path)
            if (!query.isNullOrEmpty()) append('?').append(query)
        }
    }
}
