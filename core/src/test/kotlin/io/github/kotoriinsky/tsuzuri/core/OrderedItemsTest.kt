package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OrderedItems] 的编码 / 解码 / 迁移。
 *
 * ## 为什么这一层必须逐条钉住
 *
 * 这份数据是「用户亲手排的顺序」，而它的错误方式全是**静默的**：
 *
 *  - 编码漏掉一次清洗 → 项内部的换行把一条拆成两条，扇子里多出一个点不动的图标；
 *  - 解码把空串当成一项 → 用户看到「设置里 16 项、扇子里 17 个图标」；
 *  - 迁移方向反了（快捷方式排到应用前面）→ 老用户的清单看起来「自己重排了」，
 *    而日志里什么都不会说。
 *
 * 所以下面每一条都同时断言「结果是什么」和「为什么它是这个」。
 */
class OrderedItemsTest {

    // ── 编码 ─────────────────────────────────────────────────────────────

    @Test
    fun encodeJoinsWithNewline() {
        // 分隔符只有一处定义（`OrderedItems.SEP`）：写入侧与读取侧都从它取，
        // 手写 "\n" 字面量在两边各写一次迟早会漂移
        assertEquals("\n", OrderedItems.SEP)
        assertEquals("com.a\ncom.b", OrderedItems.encode(listOf("com.a", "com.b")))
        assertEquals("", OrderedItems.encode(emptyList()))
    }

    @Test
    fun encodeDropsBlankItems() {
        // 空白项读回来时本来就会被当成「不存在的项」，留着只会造成
        // 「配置里 N 条、界面显示 M 条」这种最难查的不一致
        assertEquals("com.a", OrderedItems.encode(listOf("com.a", "", "   ", "\t", "\n")))
        assertEquals("", OrderedItems.encode(listOf("", "  ")))
    }

    @Test
    fun encodeTurnsInnerNewlinesIntoSeparatorsThatDoNotSplitItems() {
        // 项内部出现换行（配置被手改过）会**把一条拆成两条** —— 那是最糟的失败方式：
        // 扇子里多出一个永远点不动的图标。所以编码时把它换成空格
        assertEquals("com.a b", OrderedItems.encode(listOf("com.a\nb")))
        // 关键判据：它必须还是**一条**（编码前 1 条、解码后 1 条）。变成 2 条就是那个坑
        assertEquals(1, OrderedItems.decode(OrderedItems.encode(listOf("com.a\nb"))).size)
        assertEquals(listOf("com.a b"), OrderedItems.decode(OrderedItems.encode(listOf("com.a\nb"))))
        // 清洗顺序：先换行→空格、再 trim，所以首尾的换行会一起被去掉
        assertEquals("com.a", OrderedItems.encode(listOf("\ncom.a\n")))
    }

    @Test
    fun encodeTrimsEachItem() {
        assertEquals("com.a\ncom.b", OrderedItems.encode(listOf("  com.a ", "\tcom.b\t")))
    }

    @Test
    fun encodeDeduplicatesButKeepsTheFirstOccurrence() {
        // 同一项出现两次会在扇子里画两个一模一样的图标；去重必须**保序**（不能排序），
        // 否则用户拖出来的顺序会被这一步悄悄改掉
        assertEquals("b\na\nc", OrderedItems.encode(listOf("b", "a", "c", "b", "a")))
    }

    @Test
    fun encodeThenDecodeIsStable() {
        // 往返不变量：encode 的输出解码回来 == encode 的输入（清洗之后的那个）
        val raw = listOf("com.tencent.mm", " com.tencent.mm/scan ", "", "com.a\nb", "com.b", "com.a")
        val encoded = OrderedItems.encode(raw)
        val decoded = OrderedItems.decode(encoded)
        assertEquals(decoded, OrderedItems.decode(OrderedItems.encode(decoded)))
        // ⚠️ "com.a" 与 "com.a b" 是**两个**不同的项（换行换成空格之后并不与之相等），
        // 去重只去掉真正重复的那个
        assertEquals(
            listOf("com.tencent.mm", "com.tencent.mm/scan", "com.a b", "com.b", "com.a"),
            decoded,
        )
    }

    // ── 解码 ─────────────────────────────────────────────────────────────

    @Test
    fun decodeTreatsNullAndBlankAsAnEmptyList() {
        // 「空清单 = 用户没配过」是一条隐形契约（见 OrderedItems 的类注释）：
        // 读取侧据此走自动挑选，所以这两个输入必须**等价**
        assertEquals(emptyList(), OrderedItems.decode(null))
        assertEquals(emptyList(), OrderedItems.decode(""))
        assertEquals(emptyList(), OrderedItems.decode("\n"))
        assertEquals(emptyList(), OrderedItems.decode("\n\n\n"))
        assertEquals(emptyList(), OrderedItems.decode("  \n \t "))
    }

    @Test
    fun decodeToleratesExtraBlankLinesAndWhitespace() {
        // 配置被手改过、或某个旧版本写出了多余的分隔符时，不能因此多出「空项」
        assertEquals(listOf("com.a", "com.b"), OrderedItems.decode("com.a\n\ncom.b"))
        assertEquals(listOf("com.a", "com.b"), OrderedItems.decode("  com.a  \n  com.b  "))
        assertEquals(listOf("com.a", "com.b"), OrderedItems.decode("\ncom.a\ncom.b\n"))
    }

    @Test
    fun decodeDeduplicatesKeepingOrder() {
        assertEquals(listOf("b", "a"), OrderedItems.decode("b\na\nb"))
    }

    @Test
    fun decodeKeepsShortcutItemsIntact() {
        // 快捷方式项里带着 `/`（`包名/快捷方式id`），它**不是**分隔符 ——
        // 分隔符是换行，所以这一层完全不用管它（拆分是 ShortcutEntries 的事）
        val raw = OrderedItems.encode(
            listOf("com.tencent.mm", "com.tencent.mm/launch_type_scan_qrcode", "com.eg.android.AlipayGphone"),
        )
        assertEquals(
            listOf("com.tencent.mm", "com.tencent.mm/launch_type_scan_qrcode", "com.eg.android.AlipayGphone"),
            OrderedItems.decode(raw),
        )
        // 一行一项：行数就是项数
        assertEquals(3, raw.lines().size)
    }

    // ── 从老的无序清单迁移 ───────────────────────────────────────────────

    @Test
    fun migratePutsAppsFirstAndShortcutsAfterEachGroupSorted() {
        // 老配置里本来就没有顺序（那是 Set），所以迁移只能给一个**确定的**初始顺序：
        // 应用在前、快捷方式在后，各自按字符串排序。这与 hook 侧旧行为
        // 「先应用后快捷方式」一致，组内顺序可能与旧观感略有出入（诚实记在类注释里）
        val old = setOf(
            "com.zzz.chat",
            "com.tencent.mm/launch_type_scan_qrcode",
            "com.aaa.app",
            "com.tencent.mm/pay_code",
        )
        val migrated = OrderedItems.migrateFromUnordered(old)
        assertEquals(
            listOf(
                "com.aaa.app",
                "com.zzz.chat",
                "com.tencent.mm/launch_type_scan_qrcode",
                "com.tencent.mm/pay_code",
            ),
            migrated,
        )
    }

    @Test
    fun migrateIsDeterministic() {
        // 同一个输入必须永远得到同一个顺序：Set 的迭代顺序是**实现细节**（今天像插入序、
        // 明天换版本就变了），所以迁移结果绝不能依赖它 —— 否则用户的清单会
        // 「重启一次换一个顺序」，而他从来没拖过
        val old = setOf("b", "a", "c/d", "e", "com.x/s1", "com.x/s2")
        val first = OrderedItems.migrateFromUnordered(old)
        repeat(20) { assertEquals(first, OrderedItems.migrateFromUnordered(old)) }
        assertEquals(first, OrderedItems.migrateFromUnordered(LinkedHashSet(old)))
        assertEquals(first!!.reversed().reversed(), first)
    }

    @Test
    fun migrateReturnsNullWhenThereIsNothingWorthWriting() {
        // null = 「没什么可迁的」。调用方据此**不写新键**（写空串会把「没配过」
        // 变成「配了个空的」，而这两者对消费侧的含义不同）
        assertNull(OrderedItems.migrateFromUnordered(emptySet()))
        // 纯空白项也不算内容：它们 `encode` 出来是空串，写进新键后读回来是 0 项 ——
        // 返回 `["   "]` 会让调用方以为「迁移出了 1 项」，然后写下一个空清单。
        // 清洗与 `encode` 用同一套规则（trim + 丢空），这样「结果非 null」就真的
        // 等价于「写进去会有内容」，调用方不需要再多一次 encode 判断。
        assertNull(OrderedItems.migrateFromUnordered(setOf("", "   ")))
        assertNull(OrderedItems.migrateFromUnordered(setOf("\t", " ", "\n")))
    }

    @Test
    fun migrateHandlesOnlyAppsOrOnlyShortcuts() {
        assertEquals(listOf("com.a", "com.b"), OrderedItems.migrateFromUnordered(setOf("com.b", "com.a")))
        assertEquals(
            listOf("p/s1", "p/s2"),
            OrderedItems.migrateFromUnordered(setOf("p/s2", "p/s1")),
        )
    }

    @Test
    fun migratedListEncodesAndDecodesUnchanged() {
        // 迁移的产物必须能被编码原样存下来（否则「设置界面首次打开时迁移」这一步
        // 会在写出去的一瞬间把顺序丢掉）
        val migrated = OrderedItems.migrateFromUnordered(setOf("com.b", "com.a", "com.x/s1"))!!
        assertEquals(migrated, OrderedItems.decode(OrderedItems.encode(migrated)))
        assertTrue(migrated.take(2).none { it.contains(ShortcutEntries.SEP) }, "应用必须排在前面")
    }

    @Test
    fun migrateKeepsItemsThatLookLikePackageAndId() {
        // 迁移只做「分类 + 排序」，**不丢任何项**（丢项是这一层最不能犯的错：
        // 用户勾过的应用在迁移时消失，且日志里什么都看不到）
        val old = setOf("com.a", "com.b/x", "com.c/y", "com.d")
        val migrated = OrderedItems.migrateFromUnordered(old)!!
        assertEquals(old.size, migrated.size)
        assertEquals(old.toSet(), migrated.toSet())
    }
}
