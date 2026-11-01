package io.github.kotoriinsky.tsuzuri.core

import java.util.Locale

/**
 * 淘宝系分享短链（`m.tb.cn` / `e.tb.cn`）的**跳转地址提取**。
 *
 * 背景（2026-10-06 真机取证）：这类短链的服务端返回是 **12.7KB 空壳** —— 无 og 标签、
 * `<title></title>` 为空、没有 302（跳转靠页面里的 JS）。所以「抓网页拿标题/封面」这条路
 * 对它们是死的。但**闲鱼那版的壳里带着真正的商品地址**：
 *
 *     https://h5.m.goofish.com/item?forceFlush=1&itemId=1048929011444&hitNativeDetail=true&…
 *
 * 而这个地址**闲鱼 App 认**（`cmd package query-activities` 实测 `com.taobao.idlefish` 在列），
 * 反倒是最初那条 `m.tb.cn` **谁都不认**（只有浏览器带 BROWSABLE）—— 于是岛点下去只能开浏览器。
 *
 * 所以这里把它抠出来、**归一化成只带 `itemId` 的形式**，交给展示侧当作解析结果：
 * 打开目标与内容身份都用它。与虎扑那条（中转页给出 `huputiyu://bbs/topic/…`）是同一个路子。
 *
 * 放在 `:core` 的理由同 [HtmlCharset]：纯字符串逻辑，可用固定样本离线钉住。
 */
object TaobaoShellJump {

    /** 淘宝系**分享短域**。只有这些域才值得去壳里抠地址。 */
    fun isShareShortHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?.lowercase(Locale.ROOT) ?: return false
        return host == "tb.cn" || host.endsWith(".tb.cn")
    }

    /**
     * 抠出真正的商品地址；抠不到返回 null（调用方保持原样，行为不变）。
     *
     * - 闲鱼：壳里有 `h5.m.goofish.com/item…itemId=<数字>` → 归一化成
     *   `https://h5.m.goofish.com/item?itemId=<数字>`（**丢掉跟踪参数**，这样同一件商品
     *   被分享两次得到同一个内容身份）
     * - 淘宝：壳里若有 `item.taobao.com/item.htm?id=<数字>` 同样归一化（当前这版壳里没有，
     *   属**未被样本证实**的分支，留着以防别的形态）
     */
    fun itemUrl(html: String): String? {
        if (html.isEmpty()) return null
        if (html.contains("h5.m.goofish.com/item")) {
            GOOFISH_ID.find(html)?.groupValues?.get(1)?.let {
                return "https://h5.m.goofish.com/item?itemId=$it"
            }
        }
        TAOBAO_ITEM.find(html)?.let { m ->
            ID_PARAM.find(m.groupValues[1])?.groupValues?.get(1)?.let {
                return "https://item.taobao.com/item.htm?id=$it"
            }
        }
        return null
    }

    private val GOOFISH_ID = Regex("""itemId=(\d{6,})""")
    private val TAOBAO_ITEM = Regex("""item\.taobao\.com/item\.htm\?([^"'\s\\]+)""")
    private val ID_PARAM = Regex("""(?:^|&)id=(\d{6,})""")
}
