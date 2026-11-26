package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenTranslationTest {

    @Test
    fun 英文自动译中文_中文自动译英文() {
        assertEquals(ScreenTranslation.LangPair("en", "zh"), ScreenTranslation.guessLangPair("Hello world, this is HyperOS."))
        assertEquals(ScreenTranslation.LangPair("zh", "en"), ScreenTranslation.guessLangPair("今天天气真不错，准备出门散步。"))
    }

    @Test
    fun 日韩俄文自动识别源语言并译成中文() {
        assertEquals(ScreenTranslation.LangPair("jp", "zh"), ScreenTranslation.guessLangPair("今日はいい天気ですね"))
        assertEquals(ScreenTranslation.LangPair("ko", "zh"), ScreenTranslation.guessLangPair("안녕하세요 반갑습니다"))
        assertEquals(ScreenTranslation.LangPair("ru", "zh"), ScreenTranslation.guessLangPair("Привет мир"))
    }

    @Test
    fun 手动指定目标语种时遵守覆盖并在同语时自动翻转() {
        assertEquals(ScreenTranslation.LangPair("zh", "jp"), ScreenTranslation.guessLangPair("你好世界", overrideDest = "jp"))
        assertEquals(ScreenTranslation.LangPair("zh", "en"), ScreenTranslation.guessLangPair("你好世界", overrideDest = "zh"))
        assertEquals(ScreenTranslation.LangPair("en", "ko"), ScreenTranslation.guessLangPair("Good morning", overrideDest = "ko"))
    }

    @Test
    fun 清理控制字符并按上限截断() {
        val cleaned = ScreenTranslation.prepareText("  Hello\u0000\r\nWorld  ", maxLen = 8)
        assertEquals("Hello\nWo", cleaned)
    }
}
