package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PasscodeArticleAppTargetsTest {
    @Test
    fun `虎扑帖子保留网页并提供已验证的详情路由`() {
        val url = "https://m.hupu.com/bbs-share/642665473.html?share=share"
        val match = PasscodeRules.match(url)
        assertEquals(url, match?.target)
        assertEquals(listOf("huputiyu://bbs/topic/642665473"), match?.alts)
        assertEquals(
            listOf("huputiyu://bbs/topic/642501311"),
            PasscodeRules.match("https://m.hupu.com/bbs/642501311.html")?.alts,
        )
    }

    @Test
    fun `懂球帝文章保留网页并提供已验证的详情路由`() {
        val url = "https://m.dongqiudi.com/article/6412230.html?frm=copy"
        val match = PasscodeRules.match(url)
        assertEquals(url, match?.target)
        assertEquals(listOf("dongqiudi:///news/6412230"), match?.alts)
        assertEquals(
            listOf("dongqiudi:///news/1234567"),
            PasscodeRules.match("https://www.dongqiudi.com/articles/1234567")?.alts,
        )
    }

    @Test
    fun `非文章路径没有详情路由`() {
        assertEquals(emptyList(), PasscodeRules.match("https://m.hupu.com/")?.alts)
        assertEquals(emptyList(), PasscodeRules.match("https://m.dongqiudi.com/team/6412230")?.alts)
        assertEquals(emptyList(), PasscodeRules.match("https://hupu.com.example.org/bbs/642665473.html")?.alts)
    }

    @Test
    fun `展示侧能从旧广播的网页目标补算详情路由`() {
        assertEquals(
            listOf("huputiyu://bbs/topic/642665473"),
            PasscodeRules.articleAppTargets("https://m.hupu.com/bbs-share/642665473.html", "com.hupu.games"),
        )
        assertEquals(
            listOf("dongqiudi:///news/6412230"),
            PasscodeRules.articleAppTargets("https://m.dongqiudi.com/article/6412230.html", "com.dongqiudi.news"),
        )
        assertEquals(
            emptyList(),
            PasscodeRules.articleAppTargets("https://m.hupu.com/bbs-share/642665473.html", "mark.via.gp"),
        )
        assertEquals(
            listOf("com.baidu.tieba://unidispatch/pb?tid=6157983019"),
            PasscodeRules.articleAppTargets("https://tieba.baidu.com/p/6157983019?see_lz=0", "com.baidu.tieba"),
        )
        assertEquals(emptyList(), PasscodeRules.articleAppTargets("https://tieba.baidu.com/f?kw=x", "com.baidu.tieba"))
        assertEquals(
            listOf("sinaweibo://detail?mblogid=OabcdEfgh"),
            PasscodeRules.articleAppTargets("https://weibo.com/1234567890/OabcdEfgh", "com.sina.weibo"),
        )
        assertEquals(
            listOf("sinaweibo://detail?mblogid=5200000000000000"),
            PasscodeRules.articleAppTargets("https://m.weibo.cn/detail/5200000000000000", "com.sina.weibo"),
        )
        assertEquals(emptyList(), PasscodeRules.articleAppTargets("https://weibo.com/u/1234567890", "com.sina.weibo"))
    }
}
