package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LauncherOverlaySignal] 的纯逻辑测试。
 *
 * 重点在**脏数据**：那个文件是 0666 world-writable（桌面进程要能写），
 * 别的应用理论上也能往里写东西 —— 解析必须严格，且任何异常都只能退化成「未知」。
 */
class LauncherOverlaySignalTest {

    private val t0 = 1_000_000L

    private fun line(drawer: Int, folder: Int, ts: Long = t0, seq: Long = 7L) =
        "fpoverlay v=1 seq=$seq ts=$ts drawer=$drawer folder=$folder"

    @Test
    fun parsesOpenAndClosed() {
        val s = LauncherOverlaySignal.parse(line(1, 0))
        assertEquals(true, s?.drawerOpen)
        assertEquals(false, s?.folderOpen)
        assertEquals(7L, s?.seq)
        assertEquals(t0, s?.tsMs)
    }

    @Test
    fun blocksOnlyWhenSomethingIsOpen() {
        val drawer = LauncherOverlaySignal.parse(line(1, 0))
        val folder = LauncherOverlaySignal.parse(line(0, 1))
        val none = LauncherOverlaySignal.parse(line(0, 0))
        assertTrue(LauncherOverlaySignal.blocks(drawer, t0 + 100))
        assertTrue(LauncherOverlaySignal.blocks(folder, t0 + 100))
        assertFalse(LauncherOverlaySignal.blocks(none, t0 + 100))
    }

    @Test
    fun unknownMeansNotBlocking() {
        // 没读到 / 解析失败 → 未知 → 不抑制（fail-closed 的方向是「照弹」）
        assertFalse(LauncherOverlaySignal.blocks(null, t0))
        assertTrue(LauncherOverlaySignal.isStale(null, t0))
    }

    @Test
    fun staleMeansNotBlocking() {
        val open = LauncherOverlaySignal.parse(line(1, 0))
        assertTrue(LauncherOverlaySignal.blocks(open, t0 + LauncherOverlaySignal.MAX_AGE_MS - 1))
        assertFalse(LauncherOverlaySignal.blocks(open, t0 + LauncherOverlaySignal.MAX_AGE_MS + 1))
        assertTrue(LauncherOverlaySignal.isStale(open, t0 + LauncherOverlaySignal.MAX_AGE_MS + 1))
    }

    @Test
    fun futureTimestampIsAlsoStale() {
        val open = LauncherOverlaySignal.parse(line(1, 0, ts = t0 + 60_000))
        assertTrue(LauncherOverlaySignal.isStale(open, t0))
        assertFalse(LauncherOverlaySignal.blocks(open, t0))
    }

    @Test
    fun rejectsForeignContent() {
        assertNull(LauncherOverlaySignal.parse(""))
        assertNull(LauncherOverlaySignal.parse("hello from root"))
        assertNull(LauncherOverlaySignal.parse("fpoverlay"))                       // 没有字段
        assertNull(LauncherOverlaySignal.parse("notfpoverlay v=1 seq=1 ts=0 drawer=0 folder=0"))
        assertNull(LauncherOverlaySignal.parse(line(1, 0).replace(" v=1", " v=2"))) // 版本不认识
    }

    @Test
    fun rejectsMalformedFields() {
        assertNull(LauncherOverlaySignal.parse("fpoverlay v=1 seq=abc ts=$t0 drawer=0 folder=0"))
        assertNull(LauncherOverlaySignal.parse("fpoverlay v=1 seq=1 ts=xyz drawer=0 folder=0"))
        assertNull(LauncherOverlaySignal.parse("fpoverlay v=1 seq=1 ts=$t0 drawer=2 folder=0"))
        assertNull(LauncherOverlaySignal.parse("fpoverlay v=1 seq=1 ts=$t0 drawer=0"))           // 缺 folder
        assertNull(LauncherOverlaySignal.parse("fpoverlay v=1 seq=1 ts=$t0 drawer=0 folder=on")) // 不是 0/1
        assertNull(LauncherOverlaySignal.parse("fpoverlay v=1 seq=1 ts=$t0 drawer=0 folder=0 junk"))
    }

    @Test
    fun toleratesExtraWhitespaceAndUnknownKeys() {
        val s = LauncherOverlaySignal.parse(
            "  fpoverlay   v=1 seq=3 ts=$t0  drawer=1 folder=1  future=whatever \n",
        )
        assertEquals(true, s?.drawerOpen)
        assertEquals(true, s?.folderOpen)
    }

    @Test
    fun modeFieldsAreOptionalAndValidated() {
        // 旧写入方（只有 drawer/folder）照样读
        val legacy = LauncherOverlaySignal.parse(line(1, 0))
        assertEquals(false, legacy?.editOpen)
        assertEquals(false, legacy?.previewOpen)
        assertTrue(LauncherOverlaySignal.blocks(legacy, t0 + 10))

        // 新模式字段：出现即生效
        val edit = LauncherOverlaySignal.parse("${line(0, 0)} edit=1")
        assertTrue(edit?.editOpen == true)
        assertTrue(LauncherOverlaySignal.blocks(edit, t0 + 10))

        val preview = LauncherOverlaySignal.parse("${line(0, 0)} preview=1 stack=0")
        assertTrue(preview?.previewOpen == true)
        assertTrue(LauncherOverlaySignal.blocks(preview, t0 + 10))

        val stack = LauncherOverlaySignal.parse("${line(0, 0)} stack=1")
        assertTrue(LauncherOverlaySignal.blocks(stack, t0 + 10))

        // 全部关着 → 不抑制
        val none = LauncherOverlaySignal.parse("${line(0, 0)} preview=0 edit=0 stack=0")
        assertFalse(LauncherOverlaySignal.blocks(none, t0 + 10))

        // 坏值一样当未知（整条作废，退化成「不抑制」）
        assertNull(LauncherOverlaySignal.parse("${line(1, 0)} edit=yes"))
        assertFalse(LauncherOverlaySignal.blocks(LauncherOverlaySignal.parse("${line(1, 0)} stack=x"), t0 + 10))
    }

    @Test
    fun describeIsReportable() {
        assertEquals("unknown(没读到信号)", LauncherOverlaySignal.describe(null, t0))
        assertTrue(LauncherOverlaySignal.describe(LauncherOverlaySignal.parse(line(1, 0)), t0 + 10)
            .startsWith("drawer=true folder=false"))
        assertTrue(LauncherOverlaySignal.describe(
            LauncherOverlaySignal.parse(line(1, 0)),
            t0 + LauncherOverlaySignal.MAX_AGE_MS + 10,
        ).startsWith("stale("))
    }
}
