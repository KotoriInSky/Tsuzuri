package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.ExpressBadge.Cmd
import io.github.kotoriinsky.tsuzuri.core.ExpressBadge.Shape
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ExpressBadgeTest {

    @Test
    fun `每个能认出的承运商都有专门徽标`() {
        val samples = listOf(
            "SF1382024591123", "YT1234567890123", "JT1234567890123", "DPK1234567890", "JDX1234567890123",
            "RA123456785CN", "1Z5R89390357567127", "TBA000000000000", "YT2229521272164446", "JJD0099999999",
        )
        for (n in samples) {
            val name = PasscodeIsland.carrierName(n)
            assertNotEquals(ExpressBadge.GENERIC, ExpressBadge.forCarrier(name), "$n → $name 没有徽标")
        }
        assertEquals(ExpressBadge.GENERIC, ExpressBadge.forCarrier(null))
        assertEquals("顺", ExpressBadge.forCarrier("顺丰速运").text)
    }

    @Test
    fun `按 App 名称认官方 App 只认完整名字`() {
        assertTrue(ExpressBadge.appLabelMatches("圆通速递", "圆通速递"))
        assertTrue(ExpressBadge.appLabelMatches("中通快递", "中通快递"))
        assertTrue(ExpressBadge.appLabelMatches("极兔速递", "极兔快递"))
        assertTrue(ExpressBadge.appLabelMatches("DHL", "DHL Express"))
        assertTrue(ExpressBadge.appLabelMatches("FedEx", "fedex"))
        for ((c, l) in listOf("顺丰速运" to "顺丰同城", "京东物流" to "京东", "DHL" to "DHLx", "圆通速递" to "圆通妈妈商城")) {
            assertTrue(!ExpressBadge.appLabelMatches(c, l), "$c / $l")
        }
    }

    @Test
    fun `颜色格式与字数`() {
        val color = Regex("""^#[0-9A-F]{6}$""")
        for (b in ExpressBadge.all) {
            assertTrue(color.matches(b.background) && color.matches(b.foreground) && color.matches(b.accent), b.toString())
            assertTrue(b.text.length in 1..5, b.toString())
            assertNotEquals(b.background, b.foreground)
            // 岛底接近 #111，扁平风格没有描边兜底：底色最亮的通道不能低于 0x3A，否则边缘糊进岛里
            val maxChannel = listOf(1, 3, 5).maxOf { b.background.substring(it, it + 2).toInt(16) }
            assertTrue(maxChannel >= 0x3A, "底色太暗，贴在岛上看不出边：$b")
        }
    }

    @Test
    fun `不同承运商的图形互不相同（四地邮政共用信封）`() {
        val posts = setOf("中国邮政", "香港邮政", "中华邮政", "澳门邮政")
        val byGlyph = ExpressBadge.BY_CARRIER.filterKeys { it !in posts }.values.map { it.glyph } + ExpressBadge.GENERIC.glyph
        assertEquals(byGlyph.size, byGlyph.toSet().size, "有两家用了同一个图形：$byGlyph")
        assertTrue(posts.all { ExpressBadge.forCarrier(it).glyph == ExpressBadge.Glyph.ENVELOPE })
        assertTrue(ExpressBadge.Glyph.ENVELOPE !in byGlyph)
        // 表里的图形都用上了，没有死图形
        assertEquals(ExpressBadge.Glyph.entries.toSet(), ExpressBadge.all.map { it.glyph }.toSet())
    }

    @Test
    fun `图形都能解析且落在 24 网格内`() {
        for (g in ExpressBadge.Glyph.entries) {
            assertTrue(g.marks.isNotEmpty(), g.name)
            assertTrue(g.marks.first().role != ExpressBadge.Role.CUT, "${g.name} 第一笔就镂空，没有可擦的东西")
            for (m in g.marks) {
                assertTrue(m.alpha in 0.05f..1f, "$g $m")
                assertTrue(m.fill || m.stroke > 0f, "$g 既不填充也不描边：$m")
                assertTrue(m.dot == 0f || !m.fill, "$g 圆点虚线只用于描边：$m")
                // 扁平风格：整套只有两档线宽、一档圆角化描边
                if (!m.fill) assertTrue(m.stroke == 2.2f || m.stroke == 1.4f, "$g 线宽不在统一档位：$m")
                else assertTrue(m.stroke == 0f || m.stroke == 1.2f, "$g 圆角化描边不在统一档位：$m")
                val pts = points(m.shape)
                val pad = m.stroke / 2f
                for ((x, y) in pts) {
                    assertTrue(x - pad >= 0f && x + pad <= 24f && y - pad >= 0f && y + pad <= 24f, "$g 出界 ($x,$y)：$m")
                }
            }
        }
    }

    @Test
    fun `path 解析器`() {
        assertEquals(
            listOf(Cmd.Move(1f, 2f), Cmd.Line(3f, 2f), Cmd.Line(3f, 5.5f), Cmd.Cubic(1f, 1f, 2f, 2f, 3f, 3f), Cmd.Quad(4f, 4f, 5f, 5f), Cmd.Close),
            ExpressBadge.parsePath("M1 2 H3 V5.5 C1 1 2 2 3 3 Q4 4 5 5 Z"),
        )
        assertFailsWith<IllegalArgumentException> { ExpressBadge.parsePath("m1 2 l3 4") }
        assertFailsWith<IllegalArgumentException> { ExpressBadge.parsePath("M1 2 L3 4 5 6") }
        assertFailsWith<IllegalArgumentException> { ExpressBadge.parsePath("M1 2 A1 1 0 0 1 3 4") }
        assertFailsWith<IllegalArgumentException> { ExpressBadge.parsePath("L1 2") }
    }

    /**
     * 浏览器预览 `docs/artwork/express-badges/preview.html` 里嵌了一份规格 JSON，必须与这里逐字一致。
     * 不一致时把应有的 JSON 写到 `core/build/express-badges-spec.json`，照着替换预览里
     * `/*SPEC-BEGIN*/` 与 `/*SPEC-END*/` 之间的内容即可。
     */
    @Test
    fun `预览与 Kotlin 同一份几何`() {
        val expected = specJson()
        File("build").mkdirs()
        File("build/express-badges-spec.json").writeText(expected)
        val html = File("../docs/artwork/express-badges/preview.html")
        if (!html.isFile) fail("找不到 ${html.absolutePath}")
        val text = html.readText()
        val begin = text.indexOf("/*SPEC-BEGIN*/")
        val end = text.indexOf("/*SPEC-END*/")
        assertTrue(begin >= 0 && end > begin, "preview.html 缺 SPEC 标记")
        val embedded = text.substring(begin + "/*SPEC-BEGIN*/".length, end).trim()
        assertEquals(expected, embedded, "preview.html 的规格与 Kotlin 不一致，按 core/build/express-badges-spec.json 更新")
    }

    // ── 工具 ──

    private fun points(s: Shape): List<Pair<Float, Float>> = when (s) {
        is Shape.Circle -> listOf(s.cx - s.r to s.cy - s.r, s.cx + s.r to s.cy + s.r)
        is Shape.Rect -> listOf(s.x to s.y, s.x + s.w to s.y + s.h)
        is Shape.Path -> ExpressBadge.parsePath(s.d).flatMap {
            when (it) {
                is Cmd.Move -> listOf(it.x to it.y)
                is Cmd.Line -> listOf(it.x to it.y)
                is Cmd.Quad -> listOf(it.x1 to it.y1, it.x to it.y)
                is Cmd.Cubic -> listOf(it.x1 to it.y1, it.x2 to it.y2, it.x to it.y)
                Cmd.Close -> emptyList()
            }
        }
    }

    private fun n(f: Float): String = f.toString()
    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun shapeJson(s: Shape): String = when (s) {
        is Shape.Path -> "\"k\":\"p\",\"d\":${q(s.d)}"
        is Shape.Circle -> "\"k\":\"c\",\"cx\":${n(s.cx)},\"cy\":${n(s.cy)},\"r\":${n(s.r)}"
        is Shape.Rect -> "\"k\":\"r\",\"x\":${n(s.x)},\"y\":${n(s.y)},\"w\":${n(s.w)},\"h\":${n(s.h)},\"rx\":${n(s.rx)}"
    }

    private fun specJson(): String {
        val st = ExpressBadge.Style
        val style = listOf(
            "GRID" to st.GRID, "CORNER" to st.CORNER, "GLYPH_SPAN" to st.GLYPH_SPAN, "DOT_ON" to st.DOT_ON,
        ).joinToString(",") { (k, v) -> "${q(k)}:${n(v)}" }
        val glyphs = ExpressBadge.Glyph.entries.joinToString(",\n") { g ->
            "  ${q(g.name)}:[" + g.marks.joinToString(",") { m ->
                "{${shapeJson(m.shape)},\"role\":${q(m.role.name)},\"fill\":${m.fill},\"sw\":${n(m.stroke)},\"a\":${n(m.alpha)},\"dot\":${n(m.dot)}}"
            } + "]"
        }
        val rows = ExpressBadge.BY_CARRIER.entries.map { it.key to it.value } + ("通用" to ExpressBadge.GENERIC)
        val badges = rows.joinToString(",\n") { (name, b) ->
            "  {\"carrier\":${q(name)},\"text\":${q(b.text)},\"bg\":${q(b.background)},\"fg\":${q(b.foreground)}," +
                "\"accent\":${q(b.accent)},\"glyph\":${q(b.glyph.name)}}"
        }
        return "{\"style\":{$style},\n\"glyphs\":{\n$glyphs\n},\n\"badges\":[\n$badges\n]}"
    }
}
