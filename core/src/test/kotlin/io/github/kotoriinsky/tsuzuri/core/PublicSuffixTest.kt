package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 公共后缀表的钉子：数据文件没打进包 = 判定全部退化（[PublicSuffix.loaded] 为 false）；
 * 规则（例外 / 通配）写错 = 注册域算错，岛上显示的域名就错。
 */
class PublicSuffixTest {

    @Test
    fun `数据文件随包加载`() {
        assertTrue(PublicSuffix.loaded, "public_suffix_list.dat 没打进 core 的资源")
    }

    @Test
    fun `注册域`() {
        assertEquals("weibo.cn", PublicSuffix.registrableDomain("m.weibo.cn"))
        assertEquals("bbc.co.uk", PublicSuffix.registrableDomain("news.bbc.co.uk"))
        assertEquals("example.com.cn", PublicSuffix.registrableDomain("www.example.com.cn"))
        // 私有段（github.io）也是后缀：每个用户站点是独立注册域
        assertEquals("user.github.io", PublicSuffix.registrableDomain("blog.user.github.io"))
        assertEquals("co.uk", PublicSuffix.suffixOf("bbc.co.uk"))
        assertNull(PublicSuffix.registrableDomain("co.uk"))
    }

    @Test
    fun `通配与例外规则`() {
        // 表里有 `*.ck` 与 `!www.ck`：www.ck 本身是注册域
        assertEquals("www.ck", PublicSuffix.registrableDomain("www.ck"))
        assertEquals("foo.bar.ck", PublicSuffix.registrableDomain("a.foo.bar.ck"))
    }

    @Test
    fun `非公网主机`() {
        for (h in listOf("localhost", "127.0.0.1", "192.168.1.1", "printer.local", "intranet", "::1", "nas.lan")) {
            assertEquals(false, PublicSuffix.isPublicHost(h), h)
        }
        assertEquals(true, PublicSuffix.isPublicHost("example.org"))
    }

    @Test
    fun `本机与内网地址`() {
        for (h in listOf("localhost", "dev.localhost", "127.0.0.1", "192.168.1.1", "[::1]", "::1", "intranet", "nas.lan", "printer.local")) {
            assertTrue(PublicSuffix.isLocalHost(h), h)
        }
        for (h in listOf("example.org", "a.b", "localhost.example.com")) {
            assertEquals(false, PublicSuffix.isLocalHost(h), h)
        }
    }

    /** 用户 2026-10-22：本机 / 内网地址也弹岛，交给浏览器打开；只有不存在的后缀不弹。 */
    @Test
    fun `识别所有链接时 本机与内网地址弹岛 假后缀不弹`() {
        for (u in listOf("https://localhost:8080/api", "http://192.168.1.1/login", "http://nas.local/share")) {
            val m = assertNotNull(PasscodeRules.match(u), u)
            assertEquals(PasscodeRules.P_GENERIC, m.platform, u)
        }
        assertNull(PasscodeRules.match("https://a.notatld/x"))
        assertNotNull(PasscodeRules.match("https://example.org/a"))
        // 已知平台不受影响
        assertNotNull(PasscodeRules.match("https://b23.tv/AbCdEf1"))
    }
}
