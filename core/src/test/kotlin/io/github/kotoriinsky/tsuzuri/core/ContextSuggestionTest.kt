package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContextSuggestionTest {
    private fun policy(): Any {
        val type = runCatching { Class.forName("io.github.kotoriinsky.tsuzuri.core.ContextSuggestion") }.getOrNull()
        assertNotNull(type, "场景建议规则尚未接入")
        return type.getField("INSTANCE").get(null)
    }

    @Test
    fun `新应用只有真正安装且可启动时才提示`() {
        val rules = policy()
        val method = rules.javaClass.getMethod("shouldSuggestInstall", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        assertTrue(method.invoke(rules, false, true) as Boolean)
        assertFalse(method.invoke(rules, true, true) as Boolean)
        assertFalse(method.invoke(rules, false, false) as Boolean)
    }

    @Test
    fun `应用建议使用系统名称并隔离自己的胶囊身份`() {
        val rules = policy()
        val title = rules.javaClass.getMethod("openTitle", String::class.java)
        val owns = rules.javaClass.getMethod("ownsCue", String::class.java)
        assertEquals("打开哔哩哔哩", title.invoke(rules, "哔哩哔哩"))
        assertTrue(owns.invoke(rules, "tsuzuri.context.install.abc") as Boolean)
        assertFalse(owns.invoke(rules, "copy_direct.abc") as Boolean)
        assertFalse(owns.invoke(rules, "tsuzuri.contextual.abc") as Boolean)
    }

    @Test
    fun `只有主屏变暗后的触摸产生常亮建议`() {
        val rules = policy()
        val method = rules.javaClass.getMethod("isDimTouch", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        assertTrue(method.invoke(rules, 0, 2, 2) as Boolean)
        assertFalse(method.invoke(rules, 1, 2, 2) as Boolean)
        assertFalse(method.invoke(rules, 0, 1, 2) as Boolean)
        assertFalse(method.invoke(rules, 0, 2, 1) as Boolean)
    }

    @Test
    fun `常亮只在原应用前台且屏幕解锁时保留`() {
        val rules = policy()
        val method = rules.javaClass.getMethod("keepScreenOn", String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        assertTrue(method.invoke(rules, "app.a", "app.a", true, false) as Boolean)
        assertFalse(method.invoke(rules, "app.a", "app.b", true, false) as Boolean)
        assertFalse(method.invoke(rules, "app.a", null, true, false) as Boolean)
        assertFalse(method.invoke(rules, "app.a", "app.a", false, false) as Boolean)
        assertFalse(method.invoke(rules, "app.a", "app.a", true, true) as Boolean)
    }

    @Test
    fun `只有系统界面可关闭当前临时常亮`() {
        val rules = policy()
        val method = rules.javaClass.getMethod("acceptScreenOnStop", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        assertTrue(method.invoke(rules, 10225, 10225, true) as Boolean)
        assertFalse(method.invoke(rules, 12345, 10225, true) as Boolean)
        assertFalse(method.invoke(rules, 10225, 10225, false) as Boolean)
    }

    @Test
    fun `耳机建议优先白名单会话，其次小爱候选`() {
        val all: (String) -> Boolean = { true }
        assertEquals("com.spotify.music",
            ContextSuggestion.pickListenApp(listOf("com.bilibili.app.in", "com.spotify.music"), "com.netease.cloudmusic", all))
        assertEquals("com.netease.cloudmusic",
            ContextSuggestion.pickListenApp(listOf("com.bilibili.app.in"), "com.netease.cloudmusic", all))
        assertEquals("com.netease.cloudmusic",
            ContextSuggestion.pickListenApp(listOf("com.salt.music"), "com.netease.cloudmusic") { it != "com.salt.music" })
        assertEquals(null, ContextSuggestion.pickListenApp(emptyList(), "", all))
        assertTrue("com.ximalaya.ting.android" in ContextSuggestion.LISTEN_APPS)
        assertFalse("com.google.android.youtube" in ContextSuggestion.LISTEN_APPS)
    }
}
