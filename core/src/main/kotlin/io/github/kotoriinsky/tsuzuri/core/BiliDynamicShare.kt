package io.github.kotoriinsky.tsuzuri.core

/**
 * B 站**动态 / opus** 页的封面提取。
 *
 * 真机取证（2026-10-06，样本 `https://www.bilibili.com/opus/998440765151510535`，25431 字节）：
 * 页面里没有 `og:image`，但内嵌 JSON 有
 *
 *     "pic":{"pics":[{"url":"http:\u002F\u002Fi0.hdslb.com\u002Fbfs\u002Fnew_dyn\u002F….jpg",
 *                      "width":1680,"height":1680,…}]}
 *
 * 两个坑：① URL 用 `\u002F` 转义（同抖音）；② 给的是 **http**，要改成 https 再下
 *（同小红书 `imageList` 的处理）。
 *
 * 标题这一层拿不到「内容标题」—— 动态页的 `<title>` 是「<作者>的动态 - 哔哩哔哩」，
 * 属于**作者级**信息，所以这里只补封面。
 *
 * ⚠️ 未真机验证（按用户 2026-10-06 指示"先补齐、不卡验证"）。
 */
object BiliDynamicShare {

    private val PICS = Regex(""""pic"\s*:\s*\{\s*"pics"\s*:\s*\[\s*\{[^{}]{0,400}?"url"\s*:\s*"((?:[^"\\]|\\.)*)["]""")

    /** 动态里的**第一张图**（封面）。取不到返回 null。 */
    fun firstImage(html: String): String? {
        if (html.isEmpty()) return null
        val raw = PICS.find(html)?.groupValues?.get(1) ?: return null
        val url = JsonText.unescape(raw).trim()
        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> null
        }
    }
}
