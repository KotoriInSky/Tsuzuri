package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 搜索别名的回归测试。
 *
 * 这张表一旦失效，症状是「某个词突然搜不到」，没有任何报错 —— 所以只能靠
 * 单测钉住。谁改了文案忘了同步 `SearchAliases`，应当在这里补一条而不是删测试。
 */
class SearchAliasesTest {

    @Test
    fun 原词永远命中() {
        // 即便别名表完全没覆盖，照着界面上的词原样输入也要能搜到
        assertTrue(SearchAliases.matches("振动反馈", "振动"))
        assertTrue(SearchAliases.matches("图标不透明度", "不透明度"))
        assertTrue(SearchAliases.matches("窗口位置", "窗口"))
    }

    @Test
    fun 口语词经别名命中界面用词() {
        // 界面写「振动」，用户打「震动」—— 这是真踩到过的一条
        assertTrue(SearchAliases.matches("振动反馈", "震动"))
        assertTrue(SearchAliases.matches("振动强度", "振感"))
        assertTrue(SearchAliases.matches("图标不透明度", "透明"))
        assertTrue(SearchAliases.matches("图标描边", "白边"))
    }

    @Test
    fun 不匹配时不能误命中() {
        assertFalse(SearchAliases.matches("图标大小", "震动"))
        assertFalse(SearchAliases.matches("振动反馈", "圆角"))
    }

    @Test
    fun 模糊搜索_子序列命中打漏字的词() {
        // 「标大」是「图标大小」打漏了中间的字，但顺序还在 —— 子序列命中
        assertTrue(SearchAliases.matches("图标大小", "标大"))
        // 同理口语顺序词：振动反馈里「动反」按顺序出现
        assertTrue(SearchAliases.matches("振动反馈", "动反"))
    }

    @Test
    fun 模糊搜索_顺序错了不命中() {
        // 子序列要求顺序，「小大」在「图标大小」里是反的，不能命中
        assertFalse(SearchAliases.matches("图标大小", "小大"))
        assertFalse(SearchAliases.matches("振动反馈", "反馈振"))
    }

    @Test
    fun 空白查询视为命中全部() {
        assertTrue(SearchAliases.matches("随便什么", ""))
        assertTrue(SearchAliases.matches("随便什么", "   "))
        assertEquals(emptyList<String>(), SearchAliases.expand(""))
    }

    @Test
    fun 展开结果包含原词() {
        // 原词必须在第一个：调用方可能只取第一个当主命中词
        val expanded = SearchAliases.expand("震动")
        assertEquals("震动", expanded.first())
        assertTrue(expanded.contains("振动"))
    }

    @Test
    fun 没有别名的词展开后只剩自己() {
        assertEquals(listOf("图标"), SearchAliases.expand("图标"))
    }
}
