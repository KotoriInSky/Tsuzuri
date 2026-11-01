package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * B 层标题提取：2026-10-06 用**真实分享文案**探针发现的三个缺陷。
 *
 * 三条都来自"我来试一试"那批语料，不是构造的边界用例：
 *  ① 小红书文案把「| 小红书 - 你的生活指南」当成了标题的一部分；
 *  ② 知乎文案把 URL 之后的「来源：知乎」当成了标题；
 *  ③ 淘口令把固定分隔符 `/:/` 与「复制打开淘宝」残留当成标题。
 */
class PasscodeShareTitleTest {

    @Test
    fun `分享文案的平台尾巴被剥掉`() {
        assertEquals("周末去哪儿", PasscodeRules.extractTitle("【周末去哪儿 | 小红书 - 你的生活指南】"))
        // B 站这条尾巴没有空格，也要认
        assertEquals("一分钟看懂量子计算", PasscodeRules.extractTitle("【一分钟看懂量子计算-哔哩哔哩】"))
    }

    @Test
    fun `平台前缀被剥掉`() {
        assertEquals("如何评价这次的更新？", PasscodeRules.extractTitle("知乎回答：如何评价这次的更新？"))
    }

    @Test
    fun `小红书文案的 core 不含平台尾巴`() {
        val m = PasscodeRules.match(
            "【周末去哪儿 | 小红书 - 你的生活指南】 " +
                "https://www.xiaohongshu.com/discovery/item/65f81234567890abcdef1234 " +
                "复制本条信息，打开【小红书】App查看精彩内容！",
        )
        assertEquals("周末去哪儿", m?.core)
    }

    @Test
    fun `URL 之后只有来源话术时回落到 URL 之前的标题`() {
        val m = PasscodeRules.match(
            "知乎回答：如何评价这次的更新？" +
                "https://www.zhihu.com/question/19550269/answer/123456789 来源：知乎",
        )
        assertEquals("如何评价这次的更新？", m?.core)
    }

    /** 纯口令没有标题 → core 为 null，展示侧回落「打开淘宝」，不显示乱码口令。 */
    @Test
    fun `纯淘口令没有标题`() {
        assertNull(PasscodeRules.match("2￥MF1234abcd￥/:/ 复制打开淘宝")?.core)
    }

    /** 防误伤：这几条在探针里本来就是对的，改完必须保持。 */
    @Test
    fun `正常标题不被平台剥离误伤`() {
        assertEquals(
            "秋冬加厚保暖外套",
            PasscodeRules.match(
                "【淘宝】https://m.tb.cn/h.abc123?tk=AbCdEf 「秋冬加厚保暖外套」" +
                    "点击链接直接打开 或者 淘宝搜索直接打开",
            )?.core,
        )
        assertEquals(
            "小米平板 9 Pro",
            PasscodeRules.match("【京东】https://3.cn/1aBcDe 「小米平板 9 Pro」点击链接直接打开")?.core,
        )
        assertEquals(
            "昨晚比赛的关键回合",
            PasscodeRules.match("【昨晚比赛的关键回合】https://tieba.baidu.com/p/9704684816")?.core,
        )
        assertEquals(
            "纯棉四件套床上用品",
            PasscodeRules.match(
                "拼多多【纯棉四件套床上用品】https://mobile.yangkeduo.com/goods.html?goods_id=123456789",
            )?.core,
        )
    }

    /**
     * 小红书的三条**真实分享文案**（2026-10-06 用户提供）。
     *
     * 小红书与其它平台相反：**标题在链接前面**，链接后面是固定说明。旧实现按
     * 「URL 之后的文案」优先，于是把说明文字当成了标题（大字行上是
     * 「存好内容 去【小红书】一睹为快」这种），真标题反被丢掉。
     */
    @Test
    fun `小红书真实文案取链接前面的标题`() {
        assertEquals(
            "你敢信这是ai生成图 孩子们我们绘圈还有未来吗",
            PasscodeRules.match(
                "。？你敢信这是ai生成图 孩子们我们绘圈还有未来吗 " +
                    "https://xhslink.cn/o/7kW7kmVhPDA 把内容复制好，打开【小红书】即可看到笔记详情。",
            )?.core,
        )
        assertEquals(
            "教大家怎麼樣開直播",
            PasscodeRules.match(
                "教大家怎麼樣開直播 https://xhslink.cn/o/4liW35WFZp 存好内容，去【小红书】一睹为快~",
            )?.core,
        )
    }

    /** `【小红书app】` 是**说明文字里的**标签，不是标题；同一条文案的真标题在链接前面。 */
    @Test
    fun `小红书文案里的小红书app标签被跳过`() {
        // 超过 24 字要截断，且**不能把末尾 emoji 截成半个**（会渲染成「?」）
        assertEquals(
            "UGREEN 绿联 · 金属折叠手机旋转支架…",
            PasscodeRules.match(
                "【小红书】UGREEN 绿联 · 金属折叠手机旋转支架 😆 5v69pnf2YBf 😆 " +
                    "https://xhslink.com/m/2l5ocw1OIHx " +
                    "点击链接或者复制本条信息打开【小红书app】查看 HU7553",
            )?.core,
        )
    }

    /** 开头句读是噪声（真实样本 `。？你敢信…`），平台标签在开头同样要剥。 */
    @Test
    fun `标题开头的句读与平台标签被剥掉`() {
        assertEquals("今天天气不错", PasscodeRules.extractTitle("。？今天天气不错"))
        assertEquals("今天天气不错", PasscodeRules.extractTitle("【小红书】今天天气不错"))
        // 防误伤：不是平台名的方括号内容照旧当标题
        assertEquals("小红色的裙子", PasscodeRules.extractTitle("【小红色的裙子】"))
    }

    /**
     * 电商促销外壳（2026-10-06 用户提供的**真实**闲鱼/淘宝分享文案）。
     *
     * 闲鱼体是 `「快来捡漏【商品名】」` —— 外壳是平台话术，【】里才是商品名。
     * 旧实现把「快来捡漏【…】」整串放进大字行，24 字预算里前 4 个字被话术吃掉。
     *
     * 另注：这类分享短链（`m.tb.cn` / `e.tb.cn`）在真机上返回的是 **12.7KB 空壳** ——
     * 无 og 标签、`<title></title>` 为空、跳转靠 JS，所以**岛上只有分享文案可用**
     * （抓不到完整商品名与封面）。改进只能落在文案提取上。
     */
    @Test
    fun `电商促销外壳与商品名外的方括号被剥掉`() {
        assertEquals(
            "个人自用华为Pura X，黑色12+512G国行…",
            PasscodeRules.match(
                "【闲鱼】https://m.tb.cn/h.8BhbGii?tk=wVqQTKagZas CZ356 " +
                    "「快来捡漏【个人自用华为Pura X，黑色12+512G国行，外屏官方换】」\n点击链接直接打开",
            )?.core,
        )
        // 防误伤：本来就正常的方括号标题（没有促销外壳）不受影响
        assertEquals(
            "秋冬加厚保暖外套",
            PasscodeRules.match("【淘宝】https://m.tb.cn/h.abc123 「秋冬加厚保暖外套」点击链接直接打开")?.core,
        )
    }

    /** 共用短域（`m.tb.cn`）靠文案里的 `【闲鱼】`/`【淘宝】` 区分平台 —— 实测确认。 */
    @Test
    fun `共用短域按文案标签区分闲鱼与淘宝`() {
        val xianyu = PasscodeRules.match(
            "【闲鱼】https://m.tb.cn/h.8BhbGii?tk=wVqQTKagZas CZ356 「快来捡漏【二手手机】」",
        )
        assertEquals("闲鱼", xianyu?.platform?.name)
        assertEquals("com.taobao.idlefish", xianyu?.platform?.pkg)

        val taobao = PasscodeRules.match(
            "【淘宝】https://m.tb.cn/h.8BhbGii?tk=wVqQTKagZas CZ356 「新品耳机」",
        )
        assertEquals("淘宝", taobao?.platform?.name)
        assertEquals("com.taobao.taobao", taobao?.platform?.pkg)
    }
}
