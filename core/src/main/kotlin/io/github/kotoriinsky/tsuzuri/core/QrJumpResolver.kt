package io.github.kotoriinsky.tsuzuri.core

import java.util.Locale

/**
 * 识屏本地二维码解码结果的意图解析器（纯 JVM，零 Android 依赖）。
 *
 * 复用 [PasscodeRules] 内置 118 平台规则（含追踪参数清理、网盘提取码与磁力/电驴链），
 * 同时补充扫码高频场景：微信/支付宝/QQ 私有 scheme、电话/邮件/地理坐标、`WIFI:` 配置码与纯文本。
 */
object QrJumpResolver {

    enum class Kind {
        /** 命中 [PasscodeRules]（平台深链或已清洗网页链接）。 */
        PLATFORM,
        /** 其他受信任的可跳转 URI（如 `weixin://`、`alipays://`、`tel:`、`mailto:`）。 */
        URI,
        /** `WIFI:T:...;S:...;P:...;;` 无线网络配置码。 */
        WIFI,
        /** 普通文本内容（仅复制，不跳转）。 */
        TEXT,
    }

    data class Action(
        val kind: Kind,
        /** 卡片主标题，如「打开 B站」「Wi-Fi：Home5G」「二维码内容」。 */
        val title: String,
        /** 辅助摘要（清洗后链接、核心标题、Wi-Fi 密码或文本截断）。 */
        val subtitle: String,
        /** 主跳转目标；纯文本或无跳转目标的 Wi-Fi 码为 null。 */
        val primaryTarget: String?,
        /** 候选跳转目标（优先顺序在 [primaryTarget] 之后）。 */
        val alts: List<String> = emptyList(),
        /** 点击「复制」时写入剪贴板的文本。 */
        val copyText: String,
        /** 右侧主动作按钮文案，如「打开」「复制密码」「复制」。 */
        val actionLabel: String,
        /** 品牌强调色（`#RRGGBB`），null 表示使用界面默认强调色。 */
        val accent: String? = null,
        /** 已知目标包名，便于日志或优先提示。 */
        val platformPkg: String? = null,
    ) {
        val canLaunch: Boolean get() = !primaryTarget.isNullOrBlank()
    }

    private data class KnownScheme(
        val label: String,
        val actionLabel: String,
        val accent: String? = null,
        val pkg: String? = null,
    )

    /** 扫码场景常见且安全的非 HTTP scheme（显式白名单，拒绝 `javascript:` / `file:` / `content:`）。 */
    private val SAFE_SCHEMES: Map<String, KnownScheme> = mapOf(
        "weixin" to KnownScheme("微信", "打开微信", "#07C160", "com.tencent.mm"),
        "wxp" to KnownScheme("微信收款码", "打开微信", "#07C160", "com.tencent.mm"),
        "alipays" to KnownScheme("支付宝", "打开支付宝", "#1677FF", "com.eg.android.AlipayGphone"),
        "alipayqr" to KnownScheme("支付宝", "打开支付宝", "#1677FF", "com.eg.android.AlipayGphone"),
        "mqqapi" to KnownScheme("QQ", "打开 QQ", "#12B7F5", "com.tencent.mobileqq"),
        "mqqopensdkapi" to KnownScheme("QQ", "打开 QQ", "#12B7F5", "com.tencent.mobileqq"),
        "tbopen" to KnownScheme("淘宝", "打开淘宝", "#FF6900", "com.taobao.taobao"),
        "taobao" to KnownScheme("淘宝", "打开淘宝", "#FF6900", "com.taobao.taobao"),
        "openapp.jdmobile" to KnownScheme("京东", "打开京东", "#E1251B", "com.jingdong.app.mall"),
        "snssdk1128" to KnownScheme("抖音", "打开抖音", "#FE2C55", "com.ss.android.ugc.aweme"),
        "bilibili" to KnownScheme("B站", "打开 B站", "#FB7299", "tv.danmaku.bili"),
        "xhsdiscover" to KnownScheme("小红书", "打开小红书", "#FF2442", "com.xingin.xhs"),
        "tel" to KnownScheme("电话", "拨打", "#07C160"),
        "mailto" to KnownScheme("邮件", "写邮件", "#3482FF"),
        "smsto" to KnownScheme("短信", "发短信", "#07C160"),
        "sms" to KnownScheme("短信", "发短信", "#07C160"),
        "geo" to KnownScheme("位置", "打开地图", "#3482FF"),
        "otpauth" to KnownScheme("两步验证密钥", "导入验证器", "#5B7CFF"),
        "upi" to KnownScheme("UPI 支付", "去支付", "#5B7CFF"),
    )

    private val SCHEME_RE = Regex("^([a-zA-Z][a-zA-Z0-9+.-]{1,31}):(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** 将二维码原始字符串解析为可直接展示与执行的动作；空白返回 null。 */
    fun resolve(raw: String?): Action? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty()) return null

        // 1. 优先识别标准 Wi-Fi 配置二维码（避免被普通文本或奇怪 URL 误匹配）
        parseWifi(text)?.let { return it }

        // 2. 接入内置 118 平台规则（含通用 URL、参数清理、网盘与磁力/电驴链）
        val matched = PasscodeRules.match(
            text,
            PasscodeRules.Options(genericUrl = true, numericTracking = false),
        )
        if (matched != null) {
            val cleanedTarget = cleanTrackingUrl(matched.target)
            val platformName = matched.platform?.name?.takeIf { it.isNotBlank() }
            val title = when {
                platformName != null -> "打开 $platformName"
                matched.label.isNotBlank() -> matched.label
                else -> "打开链接"
            }
            val subtitle = compactSingleLine(
                matched.core?.takeIf { it.isNotBlank() }
                    ?: formatDisplayUrl(cleanedTarget),
            )
            return Action(
                kind = Kind.PLATFORM,
                title = title,
                subtitle = subtitle,
                primaryTarget = cleanedTarget,
                alts = matched.alts,
                copyText = cleanedTarget,
                actionLabel = if (platformName != null) "打开" else "访问",
                accent = matched.platform?.accent,
                platformPkg = matched.platform?.pkg,
            )
        }

        // 3. 安全白名单 URI（微信/支付宝二维码、电话、邮件、OTP 等）
        val schemeMatch = SCHEME_RE.matchEntire(text)
        if (schemeMatch != null) {
            val scheme = schemeMatch.groupValues[1].lowercase(Locale.ROOT)
            val body = schemeMatch.groupValues[2].trim()
            val known = SAFE_SCHEMES[scheme]
            if (known != null && body.isNotEmpty()) {
                val subtitle = when (scheme) {
                    "tel", "mailto", "sms", "smsto" -> body.substringBefore('?')
                    else -> compactSingleLine(text)
                }
                return Action(
                    kind = Kind.URI,
                    title = known.label,
                    subtitle = subtitle,
                    primaryTarget = text,
                    copyText = if (scheme == "tel" || scheme == "mailto") subtitle else text,
                    actionLabel = known.actionLabel,
                    accent = known.accent,
                    platformPkg = known.pkg,
                )
            }
        }

        // 4. 兜底纯文本
        return Action(
            kind = Kind.TEXT,
            title = "二维码内容",
            subtitle = compactSingleLine(text),
            primaryTarget = null,
            copyText = text,
            actionLabel = "复制",
        )
    }

    private fun parseWifi(text: String): Action? {
        if (!text.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = splitWifiFields(text.substring(5))
        val ssid = fields["S"].orEmpty()
        if (ssid.isEmpty()) return null
        val password = fields["P"].orEmpty()
        val auth = fields["T"].orEmpty().uppercase(Locale.ROOT)
        val subtitle = when {
            password.isNotEmpty() -> "密码：$password"
            auth == "NOPASS" || auth.isEmpty() -> "无密码（开放网络）"
            else -> "加密：$auth"
        }
        return Action(
            kind = Kind.WIFI,
            title = "Wi-Fi：$ssid",
            subtitle = subtitle,
            primaryTarget = null,
            copyText = password.ifEmpty { ssid },
            actionLabel = if (password.isNotEmpty()) "复制密码" else "复制名称",
            accent = "#3482FF",
        )
    }

    /** 按 ZXing `WIFI:` 规范拆分字段，支持 `\;`、`\:`、`\\` 转义。 */
    private fun splitWifiFields(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val token = StringBuilder()
        var escaped = false
        fun flush() {
            val raw = token.toString()
            token.setLength(0)
            val sep = raw.indexOf(':')
            if (sep <= 0) return
            val k = raw.substring(0, sep).uppercase(Locale.ROOT)
            val v = raw.substring(sep + 1)
            if (k !in out) out[k] = v
        }
        for (ch in body) {
            if (escaped) {
                token.append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == ';') {
                flush()
            } else {
                token.append(ch)
            }
        }
        if (token.isNotEmpty()) flush()
        return out
    }

    /** 清理 HTTP(S) 链接里的跟踪参数；保留小红书等携带访问凭据域名的原参数。 */
    private fun cleanTrackingUrl(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return url
        }
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase(Locale.ROOT) ?: return url
        if (host.endsWith("xiaohongshu.com") || host.endsWith("xhslink.com") || host.endsWith("xhslink.cn")) {
            return url
        }
        val rawQuery = uri.rawQuery ?: return url
        val cleaned = UrlCleanup.cleanQuery(host, rawQuery)
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val hashIdx = url.indexOf('#', qIdx)
        val prefix = url.substring(0, qIdx).let {
            if (cleaned.isNullOrEmpty() && it.endsWith("/") && host.endsWith("bilibili.com") && it.contains("/video/")) {
                it.removeSuffix("/")
            } else {
                it
            }
        }
        val fragment = if (hashIdx >= 0) url.substring(hashIdx) else ""
        return if (cleaned.isNullOrEmpty()) "$prefix$fragment" else "$prefix?$cleaned$fragment"
    }

    private fun formatDisplayUrl(target: String): String {
        val stripped = target.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        return stripped.ifEmpty { target }
    }

    private fun compactSingleLine(s: String, maxLen: Int = 96): String {
        val flat = s.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= maxLen) flat else flat.substring(0, maxLen - 1) + "…"
    }
}
