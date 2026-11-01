package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 单应用**显示名覆盖**（本批 3️⃣ 的前半）。
 *
 * ## 存储形态：`StringSet`，每条 `<包名>␁<显示名>`
 *
 * 键后缀取 `name_overrides`，值里的分隔符取 `\u0001` —— 与扇形那边的单项图标覆盖
 * （`smallwindow/IconOverrides.kt` 的 `<targetKey>␁kind␁value`）**同一套约定**：
 * 一条覆盖 = 一个集合元素，第一个字段永远是**目标键**。这样两个特性的配置在诊断页里
 * 是同一个形状，排障时不用换一套脑子。
 *
 * 用 `StringSet` 而不是「一条一行的单个字符串」：这份数据**没有顺序**
 * （谁先覆盖谁后覆盖不影响任何东西），而 [PrefSpec.AppList] 的 KDoc 早就写死了
 * 「顺序无意义就用 Set」。顺序有意义的收藏项用的是 `OrderedList`，两者不要混。
 *
 * ## 三条必须钉住的边界
 *
 *  1. **覆盖名是空白 = 没覆盖**。用户清空输入框时若把空串存进去，面板上会出现
 *     一个**没有名字的格子**（图标还在、底下空白）—— 那不是他想要的「恢复原名」。
 *  2. **坏条目跳过而不是整份丢掉**。一条坏数据（手改配置留下的）不该让另外 20 条覆盖失效。
 *  3. **没覆盖 = 原样返回原名**，不能返回 null 让调用方自己兜 —— 调用方漏兜就是
 *     面板上少一个名字。
 */
class DisplayNameOverridesTest {

    @Test
    fun oneOverrideSurvivesTheRoundTrip() {
        val map = mapOf("com.tencent.mm" to "微信·自定")
        assertEquals(map, DisplayNameOverrides.decode(DisplayNameOverrides.encode(map)))
    }

    @Test
    fun theEncodingDropsBlankNamesAndTrimsTheRest() {
        // 「恢复原名」= 把这一条**删掉**（见页面上的「恢复默认」），
        // 而不是存一个空名字 —— 空名字在面板上就是一个没有名字的格子。
        val encoded = DisplayNameOverrides.encode(
            mapOf(
                "com.a" to "  A  ",
                "com.b" to "",
                "com.c" to "   ",
                "  " to "X",
            ),
        )
        assertEquals(setOf("com.a\u0001A"), encoded)
    }

    @Test
    fun malformedEntriesAreSkippedOneByOne() {
        // 坏一条只丢一条：整份丢掉的表现是「用户 20 个覆盖里丢了一个、
        // 而且看不出是哪个」，那是最难查的一类。
        val decoded = DisplayNameOverrides.decode(
            listOf(
                "com.a\u0001好名字",
                "没有分隔符",
                "\u0001只有名字没有包名",
                "com.b\u0001",
                "com.c\u0001另一个名字",
            ),
        )
        assertEquals(mapOf("com.a" to "好名字", "com.c" to "另一个名字"), decoded)
    }

    @Test
    fun aWhitespaceOnlyNameIsTreatedAsNoOverride() {
        assertEquals(emptyMap(), DisplayNameOverrides.decode(listOf("com.a\u0001   ")))
    }

    @Test
    fun theLabelFallsBackToTheOriginalNameWhenThereIsNoOverride() {
        assertEquals("微信", DisplayNameOverrides.label(emptyMap(), "com.tencent.mm", "微信"))
    }

    @Test
    fun theLabelUsesTheOverrideWhenItExists() {
        val overrides = DisplayNameOverrides.decode(DisplayNameOverrides.encode(mapOf("com.tencent.mm" to "聊天")))
        assertEquals("聊天", DisplayNameOverrides.label(overrides, "com.tencent.mm", "微信"))
        // 别的包不受影响
        assertEquals("淘宝", DisplayNameOverrides.label(overrides, "com.taobao.taobao", "淘宝"))
    }

    @Test
    fun theKeysAreNormalizedSoWhitespaceCannotBreakMatching() {
        // 包名前后带空格是手改配置里最常见的脏数据；不 trim 的表现是
        // 「覆盖明明配了、面板上还是原名」。
        val decoded = DisplayNameOverrides.decode(listOf(" com.tencent.mm \u0001 聊天 "))
        assertEquals(mapOf("com.tencent.mm" to "聊天"), decoded)
        assertEquals("聊天", DisplayNameOverrides.label(decoded, "com.tencent.mm", "微信"))
    }

    @Test
    fun duplicateEntriesForOnePackageCollapseToOne() {
        // 同一个包被存了两条（配置通道上理论不该发生，但集合语义本身允许）时，
        // 结果必须是**确定的**，不能依赖迭代顺序。
        val decoded = DisplayNameOverrides.decode(listOf("com.a\u0001一", "com.a\u0001二"))
        assertEquals(1, decoded.size)
        assertTrue(decoded.getValue("com.a") == "一" || decoded.getValue("com.a") == "二")
        // 同一条重复出现两次时结果显然相同
        assertEquals(mapOf("com.a" to "一"), DisplayNameOverrides.decode(listOf("com.a\u0001一", "com.a\u0001一")))
    }
}
