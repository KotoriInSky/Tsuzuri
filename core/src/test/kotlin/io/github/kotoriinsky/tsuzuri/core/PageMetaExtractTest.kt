package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 通用元信息抽取与 oEmbed 的钉子。字段优先级参考 metascraper（MIT），样本 HTML 为手写最小形态。
 */
class PageMetaExtractTest {

    private val page = "https://example.com/post/1"

    @Test
    fun `OG 优先于 Twitter 与 title`() {
        val html = """
            <html><head><title>Title Tag - Example</title>
            <meta name="twitter:title" content="Twitter Title">
            <meta content="OG &amp; Title" property="og:title">
            <meta property="og:image" content="/img/cover.jpg">
            <meta name="author" content="张三">
            <meta name="description" content="desc">
            </head></html>
        """.trimIndent()
        val f = PageMetaExtract.extract(html, page)
        assertEquals("OG & Title", f.title)
        assertEquals("https://example.com/img/cover.jpg", f.image)
        assertEquals("张三", f.author)
        assertEquals("desc", f.description)
    }

    @Test
    fun `没有 OG 时依次退到 twitter 与 title`() {
        assertEquals("Twitter Title", PageMetaExtract.extract(
            """<meta name="twitter:title" content="Twitter Title"><title>T</title>""", page).title)
        assertEquals("只有标题", PageMetaExtract.extract("<title> 只有标题 </title>", page).title)
    }

    @Test
    fun `JSON-LD 的 headline 作者 图片 含 graph 与数组`() {
        val html = """
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[
              {"@type":"WebSite","name":"站点"},
              {"@type":"VideoObject","name":"视频名","thumbnailUrl":["//cdn.example.com/t.jpg"],
               "author":[{"@type":"Person","name":"作者甲"}]}]}</script>
            <script type="application/ld+json">{ broken json </script>
        """.trimIndent()
        val f = PageMetaExtract.extract(html, page)
        // 取 name 时跳过 WebSite 这类非内容节点，否则标题会变成站名
        assertEquals("视频名", f.title)
        assertEquals("https://cdn.example.com/t.jpg", f.image)
        assertEquals("作者甲", f.author)
    }

    @Test
    fun `article author 是链接时不当作者`() {
        val f = PageMetaExtract.extract(
            """<meta property="article:author" content="https://www.facebook.com/someone">""", page)
        assertNull(f.author)
    }

    @Test
    fun `itemprop 与同名 name 不撞键`() {
        val f = PageMetaExtract.extract(
            """<meta itemprop="image" content="https://a.example/i.png"><meta name="image" content="x">""", page)
        assertEquals("https://a.example/i.png", f.image)
    }

    @Test
    fun `oEmbed 发现链接`() {
        val html = """<link rel="alternate" type="application/json+oembed"
            href="https://example.com/oembed?url=https%3A%2F%2Fexample.com%2Fpost%2F1&amp;format=json" title="x">"""
        assertEquals(
            "https://example.com/oembed?url=https%3A%2F%2Fexample.com%2Fpost%2F1&format=json",
            PageMetaExtract.extract(html, page).oembedUrl,
        )
    }

    @Test
    fun `oEmbed 提供方清单随包加载并能匹配`() {
        assertTrue(OEmbed.loaded, "oembed_providers.json 没打进 core 的资源")
        val yt = assertNotNull(OEmbed.endpointFor("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(yt.startsWith("https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com"), yt)
        assertTrue(yt.endsWith("&format=json"))
        assertNotNull(OEmbed.endpointFor("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(
            "https://vimeo.com/api/oembed.json?url=https%3A%2F%2Fvimeo.com%2F1&format=json",
            OEmbed.requestUrl("https://vimeo.com/api/oembed.{format}", "https://vimeo.com/1"),
        )
        assertNull(OEmbed.endpointFor("https://example.org/nothing"))
    }

    @Test
    fun `oEmbed 返回解析`() {
        val r = assertNotNull(OEmbed.parse(
            """{"title":"Never Gonna Give You Up","author_name":"Rick Astley","thumbnail_url":"https://i.ytimg.com/vi/x/hqdefault.jpg","type":"video"}"""))
        assertEquals("Rick Astley", r.author)
        assertNull(OEmbed.parse("""{"type":"rich"}"""))
        assertNull(OEmbed.parse("not json"))
    }

    @Test
    fun `MiniJson 基本形态`() {
        val v = MiniJson.parse("""{"a":[1,{"b":"c中\n"}],"t":true,"n":null}""")
        assertEquals("c中\n", MiniJson.get(v, "a.1.b"))
        assertEquals(true, MiniJson.get(v, "t"))
        assertNull(MiniJson.parse("""{"a":1,}"""))
    }

    @Test
    fun `站点图标挑最大的 跳过 SVG 没有就用 favicon_ico`() {
        val html = """
            <link rel="icon" href="/f16.png" sizes="16x16">
            <link rel="icon" type="image/svg+xml" href="/logo.svg">
            <link rel="apple-touch-icon" href="/apple.png">
            <link rel="icon" href="/f96.png" sizes="96x96 48x48">
        """
        assertEquals("https://example.org/apple.png", PageMetaExtract.extract(html, "https://example.org/a/b").icon)
        val big = """<link rel="shortcut icon" href="//cdn.example.org/i.png" sizes="256x256"><link rel="apple-touch-icon" href="/a.png">"""
        assertEquals("https://cdn.example.org/i.png", PageMetaExtract.extract(big, "https://example.org/").icon)
        assertEquals("https://example.org/favicon.ico", PageMetaExtract.extract("<title>x</title>", "https://example.org/a").icon)
    }
}
