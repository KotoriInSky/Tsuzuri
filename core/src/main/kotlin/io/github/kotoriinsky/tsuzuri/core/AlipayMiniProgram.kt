package io.github.kotoriinsky.tsuzuri.core

/**
 * 支付宝**小程序分享口令**（`https://<短链>/s/<code>#…体验<名字>小程序…`）。
 *
 * 真机取证（2026-10-06，用户给的两条真实样本）：
 *
 * ```
 * https://www.wmslz.com/s/2uVvyhy37pf   →302→  https://render.alipay.com/p/s/i/?scheme=<百分号编码>
 *   解码 → alipays://platformapi/startapp?appId=2019050764321917&page=pages%2FtabBar%2Findex%2Findex…
 *          （分享文案写「体验**顺丰速运**小程序」）
 * https://www.wmslz.com/s/RLmMCd7376I   →302→  同上，appId=2021004107678121（「体验**京东快递**小程序」）
 * ```
 *
 * 页面本身只有 14KB 的支付宝壳（`<title>支付宝</title>`、无 og）→ **目标只能从跳转后的地址取**
 * —— 与抖音商城 `goods_detail` 同一个套路。
 *
 * **判定按"跳转后地址里有没有 `scheme=` 且解出来是 `alipays://…startapp`"，不硬编码短链域名**：
 * 换一个分享服务（`wmslz.com` 只是其中一个）也照样成立。
 */
object AlipayMiniProgram {

    private val SCHEME_PARAM = Regex("""[?&]scheme=([^&]+)""")
    private val APP_ID = Regex("""[?&]appId=(\d{6,32})""")

    /** 分享文案里的「…体验<名字>小程序」/「打开<名字>小程序」。 */
    private val NAME_IN_TEXT = Regex("""([\u4e00-\u9fa5A-Za-z0-9]{2,14})小程序""")

    /**
     * 这几个是**动词**不是名字 —— 正则贪婪匹配会把它们一起吞进来
     * （实测把「体验顺丰速运」整个当成了名字，被用例当场抓到）。
     */
    private val LEADING_VERBS = listOf("体验", "打开", "搜索", "搜一搜", "查看", "试试", "使用", "进入", "复制")

    /**
     * 从**跳转后的最终地址**解出小程序目标（`alipays://platformapi/startapp?…`）；
     * 不是支付宝小程序跳转页时返回 null。
     */
    fun targetFromRedirectUrl(finalUrl: String): String? {
        if (finalUrl.isEmpty()) return null
        val raw = SCHEME_PARAM.find(finalUrl)?.groupValues?.get(1) ?: return null
        val decoded = PipixShare.percentDecode(raw)
        if (!decoded.startsWith("alipays://")) return null
        return decoded.takeIf { it.contains("startapp") }
    }

    /** 从小程序目标里取 `appId`（日志与身份用）。 */
    fun appId(target: String): String? = APP_ID.find(target)?.groupValues?.get(1)

    /**
     * 从分享文案里取小程序名 —— 岛上大字行的内容（「体验顺丰速运小程序」→「顺丰速运」）。
     *
     * 注意这些分享文案会**用形近字/emoji 规避过滤**（`吱付寳`、`复值` 等），
     * 但「<名字>小程序」这一段是明文，取它就够。
     */
    fun nameFromText(text: String): String? {
        val raw = NAME_IN_TEXT.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val name = LEADING_VERBS.firstOrNull { raw.startsWith(it) }?.let { raw.removePrefix(it) } ?: raw
        return name.trim().takeIf { it.isNotEmpty() }
    }
}
