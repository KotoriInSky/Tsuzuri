package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ActivityPresets] 的收录纪律。
 *
 * 这个文件的测试**不测功能**（预置库没有逻辑），测的是**它没写错**：
 *
 *  1. **每一条都必须过 [ActivityTargets.check]** —— 写错一个字符的预置项
 *     等于把一条点不动的 bug 直接塞进用户的输入框，而用户会以为是模块坏了
 *     （他没填错任何东西，是我们的数据错了）。
 *  2. **不能有重复的目标串** —— 界面上会出现两个按钮填同一串，看着像 bug。
 *  3. **不能有空 labelKey** —— 界面按这个键取文案，取不到就是一行空白标题。
 *  4. **必须能被规范化**（[ActivityTargets.stored]）且往返一致 ——
 *     预置项填进输入框后走的是用户手填的同一条路径，这里先证明它过得了。
 *
 * ⚠️ **这些测试证明不了「这个活动真的存在」** —— 那只能在真机上 `am start` 验
 *（本轮就踩过：凭印象写的支付宝扫码活动实测不存在）。
 * 所以本文件是**必要条件，不是充分条件**；收录时的人工实测纪律写在
 * [ActivityPresets] 的类注释里，两者缺一不可。
 */
class ActivityPresetsTest {

    @Test
    fun everyPresetPassesValidation() {
        for (p in ActivityPresets.ALL) {
            assertNull(
                ActivityTargets.check(p.target),
                "预置项 ${p.labelKey} 没通过校验：${p.target}",
            )
        }
    }

    @Test
    fun everyPresetCanBeNormalizedAndRoundTrips() {
        for (p in ActivityPresets.ALL) {
            val parsed = ActivityTargets.parse(p.target)
            assertTrue(
                parsed is ActivityTargets.Result.Ok,
                "预置项 ${p.labelKey} 解析失败：${p.target}",
            )
            val stored = ActivityTargets.stored(parsed.packageName, parsed.activityName)
            // 规范化后的串必须**再解析一次**还是同一个结果（幂等，写入侧会反复调）
            val again = ActivityTargets.parse(stored)
            assertTrue(again is ActivityTargets.Result.Ok, "规范化后不可再解析：$stored")
            assertEquals(
                parsed.packageName to parsed.activityName,
                again.packageName to again.activityName,
                "预置项 ${p.labelKey} 规范化不是幂等的",
            )
        }
    }

    @Test
    fun presetTargetsAreUnique() {
        val targets = ActivityPresets.ALL.map { it.target }
        assertEquals(
            targets.size,
            targets.toSet().size,
            "预置项目标串有重复：" + targets.groupBy { it }.filter { it.value.size > 1 }.keys,
        )
    }

    @Test
    fun presetLabelKeysAreUniqueAndNonEmpty() {
        for (p in ActivityPresets.ALL) {
            assertTrue(p.labelKey.isNotBlank(), "预置项 ${p.target} 的 labelKey 是空的")
        }
        val keys = ActivityPresets.ALL.map { it.labelKey }
        assertEquals(
            keys.size,
            keys.toSet().size,
            "预置项 labelKey 有重复：" + keys.groupBy { it }.filter { it.value.size > 1 }.keys,
        )
    }

    @Test
    fun presetsAreNotPrefixed() {
        // 预置项存的是**裸形式**（不带 activity: 前缀），与用户手填一致；
        // 前缀由 ActivityTargets.stored 在写入时补。带了前缀的话
        // 填进输入框会出现「activity:activity:…」这种双重前缀。
        for (p in ActivityPresets.ALL) {
            assertTrue(
                !ActivityTargets.isActivity(p.target),
                "预置项 ${p.labelKey} 不该带前缀：${p.target}",
            )
        }
    }

    @Test
    fun everyPresetHasAPackageName() {
        for (p in ActivityPresets.ALL) {
            assertTrue(
                p.packageName.contains('.'),
                "预置项 ${p.labelKey} 的包名不像包名：${p.packageName}",
            )
        }
    }

    @Test
    fun targetOfFindsExistingPresetAndReturnsNullForUnknown() {
        val first = ActivityPresets.ALL.first()
        assertEquals(first.target, ActivityPresets.targetOf(first.labelKey))
        assertNull(ActivityPresets.targetOf("activity_import.preset.does_not_exist"))
    }
}
