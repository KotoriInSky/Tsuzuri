package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PasscodeMainlandNextCoverageTest {
    @Test
    fun `资讯直播音频阅读和电商链接选择对应平台`() {
        val cases = listOf(
            Triple("https://www.toutiao.com/video/7505382061495176511/", "今日头条", PasscodeIsland.ContentCategory.LONG_VIDEO),
            Triple("https://www.ixigua.com/6996881461559165471", "西瓜视频", PasscodeIsland.ContentCategory.LONG_VIDEO),
            Triple("https://www.douyu.com/pigff", "斗鱼", PasscodeIsland.ContentCategory.LONG_VIDEO),
            Triple("https://www.huya.com/572329", "虎牙", PasscodeIsland.ContentCategory.LONG_VIDEO),
            Triple("https://www.ximalaya.com/sound/562111701", "喜马拉雅", PasscodeIsland.ContentCategory.MUSIC),
            Triple("https://www.xiaoyuzhoufm.com/episode/6a55a96dca0de6c44ae6bb29", "小宇宙", PasscodeIsland.ContentCategory.MUSIC),
            Triple("https://weread.qq.com/web/reader/732328b0718995a47324961", "微信读书", PasscodeIsland.ContentCategory.IMAGE_TEXT),
            Triple("https://m.vip.com/", "唯品会", PasscodeIsland.ContentCategory.ECOMMERCE),
        )
        for ((url, name, category) in cases) {
            val match = PasscodeRules.match(url, PasscodeRules.Options(genericUrl = false))
            assertNotNull(match, url)
            assertEquals(name, match.platform?.name, url)
            assertEquals(category, match.category, url)
            assertEquals(url, match.target, url)
        }
    }

    @Test
    fun `头条文章和视频使用不同模板`() {
        val article = PasscodeRules.match("https://www.toutiao.com/article/123?next=/video/456")
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT, article?.category)
        val video = PasscodeRules.match("https://www.toutiao.com/video/7505382061495176511/")
        assertEquals(PasscodeIsland.ContentCategory.LONG_VIDEO, video?.category)
    }

    @Test
    fun `微信读书优先于通用QQ且相似域名不冒充平台`() {
        assertEquals("微信读书", PasscodeRules.match("https://weread.qq.com/web/reader/123")?.platform?.name)
        assertEquals("网页", PasscodeRules.match("https://weread.qq.com.example.org/web/reader/123")?.platform?.name)
        assertEquals("网页", PasscodeRules.match("https://example.org/?next=xiaoyuzhoufm.com")?.platform?.name)
    }

    @Test
    fun `分享文案有标题时优先展示标题`() {
        val match = PasscodeRules.match("【一场值得回看的比赛】https://www.huya.com/572329")
        assertEquals("一场值得回看的比赛", match?.core)
    }
}
