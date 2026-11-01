package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CopyDirectTest {
    @Test
    fun `内容网页有深链时先尝试应用内容页`() {
        val method = CopyDirect.javaClass.methods.firstOrNull { it.name == "orderedTargets" }
        assertNotNull(method, "网页候选尚未按应用内容页优先排序")
        val match = assertNotNull(PasscodeRules.match("https://m.hupu.com/bbs-share/123456.html"))
        @Suppress("UNCHECKED_CAST")
        val targets = method.invoke(CopyDirect, match) as List<String>
        assertEquals("huputiyu://bbs/topic/123456", targets.first())
        assertTrue(targets.any { it == "https://m.hupu.com/bbs-share/123456.html" })
    }

    @Test
    fun `自定义网页目标仍保持用户指定顺序`() {
        val method = CopyDirect.javaClass.methods.firstOrNull { it.name == "orderedTargets" }
        assertNotNull(method)
        val match = assertNotNull(PasscodeCustomRules.matchFirst("hello", listOf(
            PasscodeCustomRules.Rule("web", "自定义网页", "hello", true, "https://example.org"))))
        assertEquals(listOf("https://example.org"), method.invoke(CopyDirect, match))
    }

    @Test
    fun `原生入口同时接设置主进程和复制感知进程`() {
        val method = CopyDirect.javaClass.methods.firstOrNull { it.name == "supportsEngineProcess" }
        assertNotNull(method, "复制感知子进程尚未接入")
        assertEquals(true, method.invoke(CopyDirect, "com.xiaomi.aicr"))
        assertEquals(true, method.invoke(CopyDirect, "com.xiaomi.aicr:cognitionService"))
        assertEquals(false, method.invoke(CopyDirect, "com.xiaomi.aicr:searchDataService"))
        assertEquals(false, method.invoke(CopyDirect, "com.xiaomi.aicr.fake"))
    }

    @Test
    fun `视频裸号保留口令许可而网页不枚举内容应用`() {
        assertTrue(CopyDirect.platformsForAbility("jump_url").isEmpty())
        assertTrue(CopyDirect.platformsForAbility("command").any { it.packageName == "tv.danmaku.bili" })
        assertFalse(CopyDirect.platformsForAbility("command").any { it.packageName == "com.netease.cloudmusic" })
    }

    @Test
    fun `网盘不混入普通网页和口令名单`() {
        assertTrue(CopyDirect.platformsForAbility("jump_link").any { it.packageName == "com.quark.browser" })
        assertFalse(CopyDirect.platformsForAbility("command").any { it.packageName == "com.quark.browser" })
        assertFalse(CopyDirect.platformsForAbility("jump_url").any { it.packageName == "com.quark.browser" })
    }

    @Test
    fun `原生应用行使用完整名称`() {
        assertEquals("哔哩哔哩", CopyDirect.fullName("tv.danmaku.bili", "B站"))
        assertEquals("网易云音乐", CopyDirect.fullName("com.netease.cloudmusic", "网易云"))
    }

    @Test
    fun `仅登记原生口令的平台不借机进入网页名单`() {
        assertTrue(CopyDirect.platformsForAbility("command").any { it.packageName == "com.dragon.read" })
        assertFalse(CopyDirect.platformsForAbility("jump_url").any { it.packageName == "com.dragon.read" })
        assertTrue(CopyDirect.platformsForAbility("jump_url").isEmpty())
    }

    @Test
    fun `图标包与已有单选集合存储一致且默认不覆盖`() {
        assertNull(CopyDirect.config(Prefs(MapStore())).iconPack)
        val prefs = Prefs(MapStore(mapOf(CopyDirect.key(CopyDirect.ICON_PACK) to setOf("pack.z", "pack.a"))))
        assertEquals("pack.a", CopyDirect.config(prefs).iconPack)
    }

    @Test
    fun `默认关闭原生增强且未知模式回落岛`() {
        val config = CopyDirect.config(Prefs(MapStore(mapOf(CopyDirect.key(CopyDirect.MODE) to "bad"))))
        assertFalse(config.enabled)
        assertEquals(CopyDirect.Mode.ISLAND, config.mode)
    }

    @Test
    fun `原生完整商品动作优于仅启动淘宝并保留参数`() {
        val match = PasscodeRules.match("￥AbcDef1234￥")
        val module = CopyDirect.Action("taobao://", "com.taobao.taobao", CopyDirect.Strength.APP)
        val native = CopyDirect.Action("intent://item/123#Intent;package=com.taobao.taobao;S.token=abc;end", "com.taobao.taobao", CopyDirect.Strength.CONTENT)
        assertEquals(native, CopyDirect.choose(match, module, native))
    }

    @Test
    fun `自定义目标不被原生改写`() {
        val match = PasscodeCustomRules.matchFirst("hello", listOf(PasscodeCustomRules.Rule("mine", "我的", "hello", true, "https://example.com")))
        val module = CopyDirect.Action("https://example.com", null, CopyDirect.Strength.LINK)
        val native = CopyDirect.Action("taobao://item/123", "com.taobao.taobao", CopyDirect.Strength.CONTENT)
        assertEquals(module, CopyDirect.choose(match, module, native))
    }

    @Test
    fun `关闭平台后模块和原生动作都不可用`() {
        val match = PasscodeRules.match("https://b23.tv/abc")
        val action = CopyDirect.Action("https://b23.tv/abc", "tv.danmaku.bili", CopyDirect.Strength.LINK)
        assertNull(CopyDirect.choose(match, action, action, setOf("tv.danmaku.bili")))
    }

    @Test
    fun `不同平台的原生动作不能覆盖识别内容`() {
        val match = PasscodeRules.match("https://xhslink.com/a/abc")
        val module = CopyDirect.Action("https://xhslink.com/a/abc", "com.xingin.xhs", CopyDirect.Strength.LINK)
        val native = CopyDirect.Action("taobao://item/123", "com.taobao.taobao", CopyDirect.Strength.CONTENT)
        assertEquals(module, CopyDirect.choose(match, module, native))
    }

    @Test
    fun `平台清单必须从实际规则生成`() {
        val method = PasscodeRules.javaClass.methods.firstOrNull { it.name == "supportedPlatforms" }
        assertNotNull(method, "规则库尚未提供共用平台清单")
        @Suppress("UNCHECKED_CAST")
        val platforms = method.invoke(PasscodeRules) as List<PasscodeRules.Platform>
        for (sample in listOf("https://b23.tv/abc", "https://xhslink.com/a/abc", "https://pan.quark.cn/s/abc", "https://open.spotify.com/track/abc")) {
            val match = PasscodeRules.match(sample)
            assertTrue(platforms.any { it == match?.platform }, sample)
        }
        assertEquals(platforms.size, platforms.map { it.pkg ?: it.name }.distinct().size)
    }
}
