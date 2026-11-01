package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 索引里的应用过滤（本批 2️⃣）。
 *
 * ## 为什么是「模式 + 一份清单」而不是白名单或黑名单二选一
 *
 * 「只显示这几个」和「除了这几个都显示」是两种真实需求，而且**同一份清单表达不了两者**
 * （一份包名清单既可能是要保留的、也可能是要排除的，只看值分不出来）。
 * 两条路各配一份清单则会出现「两边都配了、谁赢？」这种只能靠文档回答的问题。
 * 所以：一个模式 + 一份清单 —— 模式说清清单的**方向**，清单只有一份，
 * 不存在「哪一边优先」。
 *
 * ## 为什么默认（`all`）是「一个都不动」
 *
 * 「不动它 = 与现在逐位一致」是本项目的硬要求：默认模式 + 空清单必须让**每一个**
 * 已安装应用都照旧出现在索引里。
 *
 * ## `include` + 空清单 = 不过滤（而不是「什么都不显示」）
 *
 * 这条来自两条已有的项目契约，两边指向同一个结论：
 *  - [PrefSpec.AppList] 的 KDoc：「空集 = 用户没配过，消费侧走自动挑选」；
 *  - 「防错优先于报错」：清空清单不该变成一个空面板 ——
 *    用户会认为「我把功能点坏了」。
 * 所以「只保留」模式下一个都没勾时，退化成「不过滤」并且**报告里看得见**。
 */
class IndexAppFilterTest {

    // ── 默认：不动任何东西 ──────────────────────────────────────────────

    @Test
    fun theDefaultModeKeepsEveryApplication() {
        for (mode in listOf(null, "", IndexAppFilter.MODE_ALL, "某个不认识的取值")) {
            assertEquals(
                IndexAppFilter.MODE_ALL,
                IndexAppFilter.effectiveMode(mode, emptySet()),
                "mode=$mode 应当归一成不过滤（读不出来的配置绝不改变系统行为）",
            )
            assertFalse(IndexAppFilter.isActive(mode, emptySet()), "mode=$mode 不该被认为是「配过」")
            assertTrue(IndexAppFilter.keeps(mode, emptySet(), "com.tencent.mm"))
        }
    }

    // ── 排除 ──────────────────────────────────────────────────────────

    @Test
    fun excludeModeHidesOnlyTheListedApplications() {
        val lists = setOf("com.taobao.taobao")
        assertFalse(IndexAppFilter.keeps(IndexAppFilter.MODE_EXCLUDE, lists, "com.taobao.taobao"))
        assertTrue(IndexAppFilter.keeps(IndexAppFilter.MODE_EXCLUDE, lists, "com.tencent.mm"))
        assertTrue(IndexAppFilter.isActive(IndexAppFilter.MODE_EXCLUDE, lists))
    }

    @Test
    fun excludeModeWithAnEmptyListChangesNothing() {
        // 选了「排除」但一个都没勾 = 没配过 → 与改动前逐位一致
        assertFalse(IndexAppFilter.isActive(IndexAppFilter.MODE_EXCLUDE, emptySet()))
        assertTrue(IndexAppFilter.keeps(IndexAppFilter.MODE_EXCLUDE, emptySet(), "com.tencent.mm"))
    }

    // ── 只保留 ────────────────────────────────────────────────────────

    @Test
    fun includeModeKeepsOnlyTheListedApplications() {
        val lists = setOf("com.tencent.mm", "com.tencent.mobileqq")
        assertTrue(IndexAppFilter.keeps(IndexAppFilter.MODE_INCLUDE, lists, "com.tencent.mm"))
        assertFalse(IndexAppFilter.keeps(IndexAppFilter.MODE_INCLUDE, lists, "com.taobao.taobao"))
        assertTrue(IndexAppFilter.isActive(IndexAppFilter.MODE_INCLUDE, lists))
    }

    @Test
    fun includeModeWithAnEmptyListFallsBackToNoFiltering() {
        // 关键的一条：**不是**「什么都不显示」。空清单 = 用户没配过，
        // 表现必须是和改动前一模一样（全部应用都在），而不是一个空面板。
        assertEquals(IndexAppFilter.MODE_ALL, IndexAppFilter.effectiveMode(IndexAppFilter.MODE_INCLUDE, emptySet()))
        assertFalse(IndexAppFilter.isActive(IndexAppFilter.MODE_INCLUDE, emptySet()))
        assertTrue(IndexAppFilter.keeps(IndexAppFilter.MODE_INCLUDE, emptySet(), "com.tencent.mm"))
    }

    // ── 归一化与边界 ───────────────────────────────────────────────────

    @Test
    fun theListIsNormalizedSoWhitespaceCannotBreakMatching() {
        val lists = IndexAppFilter.normalizePackages(listOf(" com.tencent.mm ", "", "   "))
        assertEquals(setOf("com.tencent.mm"), lists)
        assertTrue(IndexAppFilter.keeps(IndexAppFilter.MODE_INCLUDE, lists, "com.tencent.mm"))
        // 全空白 = 空清单 = 没配过
        assertFalse(IndexAppFilter.isActive(IndexAppFilter.MODE_INCLUDE, IndexAppFilter.normalizePackages(listOf(" "))))
    }

    @Test
    fun aMissingPackageNameIsNotSilentlyKeptInIncludeMode() {
        // 拿不到包名的项不该在「只保留」模式下幸存 —— 它连自己是谁都不知道，
        // 留在索引里就等于「用户明确排除了它，它却还在」。
        assertFalse(IndexAppFilter.keeps(IndexAppFilter.MODE_INCLUDE, setOf("com.tencent.mm"), null))
        // 反过来，排除模式下拿不到包名就照常保留（不改行为的那一侧）
        assertTrue(IndexAppFilter.keeps(IndexAppFilter.MODE_EXCLUDE, setOf("com.tencent.mm"), null))
    }

    @Test
    fun theModeIsTrimmedAndCaseFoldedTheSameWayTheConfigIs() {
        // 配置可以被手改；` EXCLUDE ` 这种写法必须和 `exclude` 等价 ——
        // 不一致的表现是「模式选了、清单也填了，可就是不生效」。
        assertEquals(IndexAppFilter.MODE_EXCLUDE, IndexAppFilter.normalizeMode(" EXCLUDE "))
        assertEquals(IndexAppFilter.MODE_INCLUDE, IndexAppFilter.normalizeMode("Include"))
        assertEquals(IndexAppFilter.MODE_ALL, IndexAppFilter.normalizeMode(null))
        assertEquals(IndexAppFilter.MODE_ALL, IndexAppFilter.normalizeMode("  "))
    }
}
