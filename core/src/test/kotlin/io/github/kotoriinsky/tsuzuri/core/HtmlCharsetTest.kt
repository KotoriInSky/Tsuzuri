package io.github.kotoriinsky.tsuzuri.core

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 字符集探测：长尾站点靠 HTTP 抓标题的前提。
 *
 * 全部用**固定字节**做（不联网、不需要设备）—— 这正是把它放进 `:core` 的理由：
 * 真实的 GBK 页面在真机上不好造，但字节可以造。
 */
class HtmlCharsetTest {

    private val gbk = Charset.forName("GBK")
    private val text = "<html><head><title>中文标题</title></head></html>"

    @Test
    fun `响应头 charset 最权威`() {
        assertEquals(text, HtmlCharset.decode(text.toByteArray(gbk), "text/html; charset=GBK"))
    }

    @Test
    fun `没有响应头时看 meta charset`() {
        val html = "<html><head><meta charset=\"gbk\"><title>中文标题</title></head></html>"
        assertEquals(html, HtmlCharset.decode(html.toByteArray(gbk), "text/html"))
    }

    @Test
    fun `http-equiv 形态也认`() {
        val html = "<html><head>" +
            "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb18030\">" +
            "<title>中文标题</title></head></html>"
        assertEquals(html, HtmlCharset.decode(html.toByteArray(Charset.forName("GB18030")), null))
    }

    @Test
    fun `UTF-8 BOM 被剥掉且不影响解码`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(text, HtmlCharset.decode(bom + text.toByteArray(Charsets.UTF_8), "text/html"))
    }

    @Test
    fun `没有任何线索时回落 UTF-8`() {
        assertEquals(text, HtmlCharset.decode(text.toByteArray(Charsets.UTF_8), "text/html"))
    }

    /** 声明了一个不存在的字符集时**退回 UTF-8**，不能抛（抛了就是"标题没了"）。 */
    @Test
    fun `不可用的 charset 名字回落 UTF-8`() {
        val html = "<meta charset=\"totally-not-a-charset\"><title>中文标题</title>"
        assertEquals(html, HtmlCharset.decode(html.toByteArray(Charsets.UTF_8), "text/html; charset=@@@"))
    }

    /** 只解有效字节数：缓冲区尾部没读到的部分不能混进来。 */
    @Test
    fun `只解码有效字节数`() {
        val real = text.toByteArray(Charsets.UTF_8)
        val padded = real + ByteArray(16) { 0x41 }
        assertEquals(text, HtmlCharset.decode(padded, null, real.size))
    }

    /** 响应头说 UTF-8 且内容带 BOM：BOM 是字节序标记不是内容，应当剥掉。 */
    @Test
    fun `响应头说 UTF-8 且带 BOM 时剥掉 BOM`() {
        val raw = "\uFEFFAB".toByteArray(Charsets.UTF_8) // EF BB BF 41 42
        assertEquals("AB", HtmlCharset.decode(raw, "text/html; charset=UTF-8"))
    }

    /**
     * 真冲突：响应头说别的字符集时，开头那三个字节就是**内容**，不能按 BOM 剥掉。
     * 用单字节字符集断言（GBK 是变长编码，插 3 字节会让正文错位，"尾巴是中文"不成立）。
     */
    @Test
    fun `响应头与 BOM 冲突时那三个字节当内容`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val decoded = HtmlCharset.decode(bom + "AB".toByteArray(Charsets.ISO_8859_1), "text/html; charset=ISO-8859-1")
        assertNotEquals("AB", decoded, "三个字节被当成 BOM 吃掉了")
        assertTrue(decoded.endsWith("AB"), decoded)
    }
}
