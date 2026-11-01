package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 借 [sokoko-org/nonebot-plugin-parser-lite](https://github.com/sokoko-org/nonebot-plugin-parser-lite)
 * （**MIT**）的 URL 形态补的一批平台规则。
 *
 * 只抄**事实**（主机名与路径形态来自它的 `@handle(...)` 声明），实现是自己写的；
 *
 * 形态出处（它的 parsers/ 目录）：
 * - `duitang/__init__.py`：`handle("duitang.com/blog", params={"id": ...})`
 * - `doubao/__init__.py`：`handle("doubao.com/video-sharing", ...)`
 * - 小黑盒/米游社/LOFTER/TapTap：主机名为公开常识，其中小黑盒的包名由真机 App Link 实测确认。
 */
class BorrowedPlatformRulesTest {

    private fun match(url: String) = PasscodeRules.match(url)

    @Test
    fun `借来的平台能识别出平台名`() {
        assertEquals("小黑盒", match("https://xiaoheihe.cn/app/bbs/link/12345")?.platform?.name)
        assertEquals("com.max.xiaoheihe", match("https://xiaoheihe.cn/app/bbs/link/12345")?.platform?.pkg)
        assertEquals("米游社", match("https://www.miyoushe.com/ys/article/12345")?.platform?.name)
        assertEquals("LOFTER", match("https://somebody.lofter.com/post/1")?.platform?.name)
        assertEquals("TapTap", match("https://www.taptap.cn/app/12345")?.platform?.name)
        assertEquals("堆糖", match("https://www.duitang.com/blog?id=12345")?.platform?.name)
        assertEquals("豆包", match("https://www.doubao.com/video-sharing?share_id=1&video_id=2")?.platform?.name)
    }

    /** `taptap.cn` 与 `taptap.com` 两种域都要认。 */
    @Test
    fun `taptap 两种域名`() {
        assertEquals("TapTap", match("https://taptap.com/app/1")?.platform?.name)
        assertEquals("TapTap", match("https://taptap.cn/moment/1")?.platform?.name)
    }

    /** 包名留空的平台不能瞎猜包名（展示侧会回落到域名启发式/系统解析）。 */
    @Test
    fun `未确认包名的平台留空`() {
        assertEquals(null, match("https://www.duitang.com/blog?id=1")?.platform?.pkg)
        assertEquals(null, match("https://www.doubao.com/video-sharing?share_id=1")?.platform?.pkg)
    }

    /**
     * 第二批（同上，形态来自它的 `@handle` 声明）：
     * `buff/__init__.py`（`buff.163.com`）、`ds/__init__.py`（`ds.163.com` 的
     * `article/<feed_id>` 与 `feed/<feed_id>`）、`zlb/__init__.py`（`zlb.ink/topic/<id>`）、
     * `illu/__init__.py`（`illund.com/share.html`）、`fiveeplay/__init__.py`
     * （`csgo.5eplay.com/forum/{forum,share}/<id>`）、`linuxdo/__init__.py`（`linux.do/topic/<id>`）、
     * `wmpvp/__init__.py`（`news.wmpvp.com/community-detail.html?id=<数字>`）。
     * NGA 的 `read.php?tid=<数字>` 三个域来自 fllesser 的公开测试链接清单。
     */
    @Test
    fun `第二批借来的平台也能识别`() {
        assertEquals("网易BUFF", match("https://buff.163.com/news/share/12345")?.platform?.name)
        assertEquals("网易大神", match("https://ds.163.com/article/abc123")?.platform?.name)
        assertEquals("网易大神", match("https://ds.163.com/feed/abc123")?.platform?.name)
        assertEquals("壁吧专楼吧", match("https://zlb.ink/topic/12345")?.platform?.name)
        assertEquals("ILLU", match("https://illund.com/share.html?articleId=abc")?.platform?.name)
        assertEquals("5EPlay", match("https://csgo.5eplay.com/forum/share/12345")?.platform?.name)
        assertEquals("LinuxDo", match("https://linux.do/topic/12345")?.platform?.name)
        assertEquals("完美世界", match("https://news.wmpvp.com/community-detail.html?id=12345")?.platform?.name)
    }

    /** NGA 三个域（`nga.178.com` / `bbs.nga.cn` / `ngabbs.com`）都要认。 */
    @Test
    fun `NGA 三个域名`() {
        assertEquals("NGA", match("https://nga.178.com/read.php?tid=45263995")?.platform?.name)
        assertEquals("NGA", match("https://bbs.nga.cn/read.php?tid=45263995")?.platform?.name)
        assertEquals("NGA", match("https://ngabbs.com/read.php?tid=45263995")?.platform?.name)
    }
}
