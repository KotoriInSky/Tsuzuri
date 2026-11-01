package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PasscodeMainlandCoverageTest {
    @Test
    fun `抖音链接按内容身份选择模板`() {
        val cases = listOf(
            "https://www.douyin.com/video/6961737553342991651" to PasscodeIsland.ContentCategory.SHORT_VIDEO,
            "https://www.douyin.com/note/7420881080148323624" to PasscodeIsland.ContentCategory.IMAGE_TEXT,
            "https://haohuo.jinritemai.com/views/product/item2?id=123" to PasscodeIsland.ContentCategory.ECOMMERCE,
            "https://www.douyin.com/user/abc" to PasscodeIsland.ContentCategory.IMAGE_TEXT,
            "https://v.douyin.com/ABErWB/" to PasscodeIsland.ContentCategory.SHORT_VIDEO,
        )
        for ((url, category) in cases) {
            val match = PasscodeRules.match(url, PasscodeRules.Options(genericUrl = false))
            assertEquals("抖音", match?.platform?.name, url)
            assertEquals(category, match?.category, url)
        }
    }

    @Test
    fun `常见内地分享域名按内容分类`() {
        val cases = listOf(
            Triple("https://m.hupu.com/bbs/642501311.html", "虎扑", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://www.dongqiudi.com/articles/1234567", "懂球帝", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://www.zhihu.com/question/123456", "知乎", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://movie.douban.com/subject/1292052/", "豆瓣", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://mp.weixin.qq.com/s/abc123", "微信", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://qm.qq.com/q/abc123", "QQ", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://www.goofish.com/item?id=123456", "闲鱼", PasscodeIsland.ContentCategory.ECOMMERCE),
            Triple("https://www.dewu.com/product-detail.html?spuId=123", "得物", PasscodeIsland.ContentCategory.ECOMMERCE),
            Triple("https://h5.ele.me/shop/123", "饿了么", PasscodeIsland.ContentCategory.ECOMMERCE),
            Triple("https://www.mgtv.com/b/123/456.html", "芒果TV", PasscodeIsland.ContentCategory.LONG_VIDEO),
            Triple("https://www.kugou.com/song/abc.html", "酷狗音乐", PasscodeIsland.ContentCategory.MUSIC),
            Triple("https://www.kuwo.cn/play_detail/123", "酷我音乐", PasscodeIsland.ContentCategory.MUSIC),
            Triple("https://pan.quark.cn/s/abcdef123", "夸克网盘", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://www.aliyundrive.com/s/abc123", "阿里云盘", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://www.alipan.com/s/abc123", "阿里云盘", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://pan.xunlei.com/s/abc123", "迅雷", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://surl.amap.com/abc123", "高德地图", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://map.baidu.com/?shareurl=abc", "百度地图", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://m.ctrip.com/webapp/hotel/hoteldetail/123", "携程", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://www.fliggy.com/item/123", "飞猪", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://www.12306.cn/index/", "铁路12306", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://www.smzdm.com/p/123456/", "什么值得买", PasscodeIsland.ContentCategory.IMAGE_TEXT),
        )
        for ((url, name, category) in cases) {
            val match = PasscodeRules.match(url, PasscodeRules.Options(genericUrl = false))
            assertNotNull(match, url)
            assertEquals("url", match.ruleId, url)
            assertEquals(name, match.platform?.name, url)
            assertEquals(category, match.category, url)
            assertEquals(url, match.target, url)
        }
    }

    @Test
    fun `闲鱼和百度地图先于淘宝百度通用域名`() {
        assertEquals("闲鱼", PasscodeRules.match("https://2.taobao.com/item.htm?id=123")?.platform?.name)
        assertEquals("闲鱼", PasscodeRules.match("https://fleamarket.taobao.com/item.htm?id=123")?.platform?.name)
        assertEquals("百度地图", PasscodeRules.match("https://map.baidu.com/mobile/webapp/index/index/")?.platform?.name)
        assertEquals("闲鱼", PasscodeRules.match(
            "我在闲鱼发布了『二手相机』 https://m.tb.cn/h.abc123 复制打开闲鱼",
        )?.platform?.name)
        assertEquals("淘宝", PasscodeRules.match("https://m.tb.cn/h.abc123")?.platform?.name)
    }

    @Test
    fun `查询参数和相似域名不冒充新增平台`() {
        val query = PasscodeRules.match("https://example.org/open?next=goofish.com")
        assertEquals("网页", query?.platform?.name)
        val suffix = PasscodeRules.match("https://goofish.com.example.org/item/123")
        assertEquals("网页", suffix?.platform?.name)
        assertNull(PasscodeRules.match(
            "https://example.org/open?next=goofish.com",
            PasscodeRules.Options(genericUrl = false),
        ))
    }

    @Test
    fun `新平台分享文案优先显示内容标题`() {
        val idlefish = PasscodeRules.match("【闲鱼】『二手相机套装』https://www.goofish.com/item?id=123")
        assertEquals("二手相机套装", idlefish?.core)
        val hupu = PasscodeRules.match("【昨晚比赛的关键回合】https://m.hupu.com/bbs/642501311.html")
        assertEquals("昨晚比赛的关键回合", hupu?.core)
    }
}
