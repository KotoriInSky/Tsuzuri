package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LetterIndex] 的不变量。
 *
 * 这块逻辑的错法都很安静：分组少一个字母 → 索引条上少一格（看不出）；行下标算错 →
 * 跳转偏几行（十几项时几乎看不出来，几百项时才明显）。所以拿测试钉住。
 */
class LetterIndexTest {

    /** 假的取首字母：取第一个字符的大写，非字母一律 `#`（真实现是 ICU 拼音，这里不需要）。 */
    private val fake: (String) -> String = { name ->
        val c = name.firstOrNull()?.uppercaseChar() ?: '#'
        if (c in 'A'..'Z') c.toString() else "#"
    }

    @Test
    fun alphabetIsAzThenOther() {
        assertEquals(27, LetterIndex.ALPHABET.size)
        assertEquals("A", LetterIndex.ALPHABET.first())
        assertEquals("Z", LetterIndex.ALPHABET[25])
        assertEquals(LetterIndex.OTHER, LetterIndex.ALPHABET.last())
    }

    @Test
    fun slotOfNormalizesBadInput() {
        // ICU 对某些字会给出双字母（`ZH`），而它不在索引条上 —— 不当成 `#` 的话，
        // 分组标题会是一个索引条上没有的字母，用户永远跳不到那一组
        assertEquals("Z", LetterIndex.slotOf("Z"))
        // 小写要折成大写，否则同一个字母会分成两组
        assertEquals("Z", LetterIndex.slotOf("z"))
        assertEquals(LetterIndex.OTHER, LetterIndex.slotOf("ZH"))
        assertEquals(LetterIndex.OTHER, LetterIndex.slotOf(""))
        assertEquals(LetterIndex.OTHER, LetterIndex.slotOf("中"))
        assertEquals(LetterIndex.OTHER, LetterIndex.slotOf("1"))
        assertEquals(LetterIndex.OTHER, LetterIndex.slotOf("#"))
    }

    @Test
    fun groupKeepsAlphabetOrderAndSkipsAbsentLetters() {
        // 顺序是 A…Z 而不是「出现顺序」：索引条是竖着画的，顺序必须和条一致，
        // 否则用户点 'C' 会跳到列表里 'A' 的上方
        val groups = LetterIndex.group(
            listOf("Zebra", "Apple", "1号店", "Cherry"),
            { it },
            fake,
        )
        assertEquals(listOf("A", "C", "Z", LetterIndex.OTHER), groups.map { it.letter })
        // 没有 B 开头的项 → 就没有 B 这一组（索引条上也不该有这一格）
        assertTrue(groups.none { it.letter == "B" })
    }

    @Test
    fun emptyInputGivesNoGroups() {
        assertTrue(LetterIndex.group(emptyList<String>(), { it }, fake).isEmpty())
    }

    @Test
    fun rowsCountIncludesHeaders() {
        val groups = LetterIndex.group(
            listOf("Apple", "Avocado", "Banana", "1号店"),
            { it },
            fake,
        )
        assertEquals(listOf("A", "B", LetterIndex.OTHER), groups.map { it.letter })
        assertEquals(listOf(2, 1, 1), groups.map { it.items.size })

        val rows = LetterIndex.rows(groups)
        // 3 个标题行 + 4 个项
        assertEquals(7, rows.count)
        // A 的标题在第 0 行，B 在第 1+2=3 行，'#' 在第 3+1+1=5 行
        assertEquals(0, rows.rowOf("A"))
        assertEquals(3, rows.rowOf("B"))
        assertEquals(5, rows.rowOf(LetterIndex.OTHER))
        assertNull(rows.rowOf("C"))
    }

    @Test
    fun rowsOfOneGroupStartAtZero() {
        val rows = LetterIndex.rows(LetterIndex.group(listOf("Apple"), { it }, fake))
        assertEquals(2, rows.count)
        assertEquals(0, rows.rowOf("A"))
    }

    @Test
    fun groupPreservesInputOrderInsideALetter() {
        // 组内顺序不在这一层定（排序是调用方的事，它才知道该按拼音还是按最近使用），
        // 所以这里必须**原样保持**，不能顺手排一下
        val groups = LetterIndex.group(listOf("Zebra", "Zoo", "Zinc"), { it }, fake)
        assertEquals(listOf("Zebra", "Zoo", "Zinc"), groups.single().items)
    }

    @Test
    fun slotAtClampsAtBothEnds() {
        // 线性分格：每格 10px，条从 y=100 开始
        assertEquals(0, LetterIndex.slotAt(y = 100f, top = 100f, itemHeight = 10f, count = 5))
        assertEquals(2, LetterIndex.slotAt(y = 125f, top = 100f, itemHeight = 10f, count = 5))
        assertEquals(4, LetterIndex.slotAt(y = 10_000f, top = 100f, itemHeight = 10f, count = 5))
        // 手指划到条上方（负偏移）夹到第 0 格，不能是负下标
        assertEquals(0, LetterIndex.slotAt(y = 0f, top = 100f, itemHeight = 10f, count = 5))
        // 参数退化（没测到高度 / 一个槽都没有）时返回 0，绝不抛异常 ——
        // 这个函数跑在拖动的每一帧上
        assertEquals(0, LetterIndex.slotAt(y = 50f, top = 0f, itemHeight = 0f, count = 5))
        assertEquals(0, LetterIndex.slotAt(y = 50f, top = 0f, itemHeight = 10f, count = 0))
    }
}
