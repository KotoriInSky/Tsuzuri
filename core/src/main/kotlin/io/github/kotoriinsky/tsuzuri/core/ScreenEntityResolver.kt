package io.github.kotoriinsky.tsuzuri.core

import java.util.Locale

/**
 * 全局识别文字选区的纯本地实体解析器（零网络、零 Android 依赖）。
 *
 * 复用 [PasscodeRules]（118 平台口令/链接、快递单号、磁力/网盘、追踪参数清理）
 * 与 [QrJumpResolver.Action]（直接复用安全跳转链路），并补充邮箱与电话号码识别。
 */
object ScreenEntityResolver {

    private val EMAIL_RE = Regex("(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,12})(?![A-Za-z0-9._%+-])")
    // 大陆手机号（13-19 开头 11 位，允许中间空格或短横线）或 400/800 客服号
    private val MOBILE_RE = Regex("(?<!\\d)(1[3-9]\\d[\\s-]?\\d{4}[\\s-]?\\d{4})(?!\\d)")
    private val SERVICE_PHONE_RE = Regex("(?<!\\d)((?:400|800)[\\s-]?\\d{3}[\\s-]?\\d{4})(?!\\d)")
    private val LANDLINE_RE = Regex("(?<!\\d)(0\\d{2,3}-\\d{7,8})(?!\\d)")

    /**
     * 从选中的文字中提取最值得「一键直达」的一个实体动作；无实体返回 null。
     * 只扫描前 600 字，避免全选超长文档时做无谓正则开销。
     */
    fun resolve(raw: String?): QrJumpResolver.Action? {
        val text = raw?.trim()?.take(600) ?: return null
        if (text.length < 4) return null

        // 1. 优先复用 118 平台规则（链接、BV/av/淘口令、网盘、磁力链、明确前缀的快递单号）
        val matched = PasscodeRules.match(
            text,
            PasscodeRules.Options(genericUrl = true, numericTracking = false),
        )
        if (matched != null) {
            val cleaned = cleanTrackingUrl(matched.target)
            val platformName = matched.platform?.name?.takeIf { it.isNotBlank() }
            val title = when {
                platformName != null -> platformName
                matched.label.isNotBlank() -> matched.label
                else -> "网页链接"
            }
            val subtitle = compactSingleLine(
                matched.core?.takeIf { it.isNotBlank() }
                    ?: formatDisplayUrl(cleaned),
            )
            return QrJumpResolver.Action(
                kind = QrJumpResolver.Kind.PLATFORM,
                title = title,
                subtitle = subtitle,
                primaryTarget = cleaned,
                alts = matched.alts,
                copyText = cleaned,
                actionLabel = if (platformName != null) "打开" else "访问",
                accent = matched.platform?.accent,
                platformPkg = matched.platform?.pkg,
            )
        }

        // 2. 邮箱
        EMAIL_RE.find(text)?.groupValues?.getOrNull(1)?.let { email ->
            return QrJumpResolver.Action(
                kind = QrJumpResolver.Kind.URI,
                title = "电子邮箱",
                subtitle = email,
                primaryTarget = "mailto:$email",
                copyText = email,
                actionLabel = "写邮件",
                accent = "#3482FF",
            )
        }

        // 3. 电话号码（手机号 / 400 / 800 / 带区号固话）
        val phoneRaw = MOBILE_RE.find(text)?.groupValues?.getOrNull(1)
            ?: SERVICE_PHONE_RE.find(text)?.groupValues?.getOrNull(1)
            ?: LANDLINE_RE.find(text)?.groupValues?.getOrNull(1)
        if (phoneRaw != null) {
            val dial = phoneRaw.replace(Regex("[\\s-]"), "")
            return QrJumpResolver.Action(
                kind = QrJumpResolver.Kind.URI,
                title = "电话号码",
                subtitle = phoneRaw.trim(),
                primaryTarget = "tel:$dial",
                copyText = dial,
                actionLabel = "拨打",
                accent = "#07C160",
            )
        }

        return null
    }

    private fun cleanTrackingUrl(url: String): String = QrJumpResolver.cleanTrackingUrl(url)

    private fun formatDisplayUrl(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private fun compactSingleLine(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= 80) one else one.take(79) + "…"
    }
}
