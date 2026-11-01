package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 最右与皮皮虾的字段提取。
 *
 * ⚠️ 最右的样本是**按真机返回体的真实结构**合成的（`data.post` 与评论里的 `imgs` 都在，
 * 用来钉住"必须限定在 post 内"这条）；皮皮虾**没有真实样本**，按 ParseHub 的字段路径合成。
 * 两者都没有真机端到端验证。来源与许可见 [ZuiyouShare] / [PipixShare] 的类注释。
 */
class ZuiyouAndPipixTest {

    /** 关键是评论里也有 `imgs`（ParseHub 的坑），必须只取 post 内的。 */
    private val zuiyouJson = """
        {"ret":1,"data":{"hotreviews":[{"id":1,"review":"评论内容","imgs":[{"urls":{"360":{"urls":["https://review.example/r.jpg"]}}}]}],
        "post":{"id":393346270,"content":"终于懂古代说书人为什么受欢迎","member":{"id":53231361,"name":"九羡少女心","sign":"签名"},
        "imgs":[{"id":2446640594,"h":960,"w":540,"video":1,"fmt":"jpeg",
        "urls":{"360":{"h":640,"w":360,"urls":["https://img.example/small.jpg"]},"1080":{"urls":["https://img.example/large.jpg"]}}}]}}}
    """.trimIndent()

    @Test
    fun `最右取文案作者与首图，且不误取评论配图`() {
        val f = ZuiyouShare.parse(zuiyouJson)
        assertEquals("终于懂古代说书人为什么受欢迎", f?.title)
        assertEquals("九羡少女心", f?.author)
        assertEquals("https://img.example/large.jpg", f?.cover)
    }

    @Test
    fun `最右从 URL 取 pid`() {
        assertEquals("393346270", ZuiyouShare.pid("https://share.xiaochuankeji.cn/hybrid/share/post?pid=393346270&zy_to=applink"))
        assertNull(ZuiyouShare.pid("https://share.xiaochuankeji.cn/hybrid/share/post"))
    }

    @Test
    fun `最右没有 post 时返回 null`() {
        assertNull(ZuiyouShare.parse(""))
        assertNull(ZuiyouShare.parse("""{"ret":0,"data":{}}"""))
    }

    /** 皮皮虾：`RENDER_DATA` 是 URL 编码的 JSON，要解码后再取 `item` 字段。 */
    @Test
    fun `皮皮虾解码 RENDER_DATA 并取字段`() {
        val inner = """{"ppxItemDetail":{"item":{"item_type":1,"content":"皮皮虾文案","cover":{"download_list":[{"url":"https://img.example/ppx.jpg"}]}}}}"""
        val encoded = inner.replace("{", "%7B").replace("}", "%7D").replace("\"", "%22").replace(":", "%3A").replace(",", "%2C")
        val html = """<html><body><script id="RENDER_DATA" type="application/json">$encoded</script></body></html>"""
        val f = PipixShare.parse(html)
        assertEquals("皮皮虾文案", f?.title)
        assertEquals("https://img.example/ppx.jpg", f?.cover)
        assertEquals(false, f?.isVideo)
    }

    @Test
    fun `皮皮虾没有 RENDER_DATA 时返回 null`() {
        assertNull(PipixShare.parse(""))
        assertNull(PipixShare.parse("<html><title>皮皮虾</title></html>"))
    }

    /** 百分号解码**不得**把 `+` 变成空格（`URLDecoder` 的坑）。 */
    @Test
    fun `百分号解码保留加号`() {
        assertEquals("a+b c", PipixShare.percentDecode("a+b%20c"))
        assertEquals("中文", PipixShare.percentDecode("%E4%B8%AD%E6%96%87"))
        assertTrue(PipixShare.isPipixUrl("h5.pipix.com"))
    }
}
