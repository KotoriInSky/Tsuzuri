package io.github.kotoriinsky.tsuzuri.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 「试一下」样例池（[CopyDirectSamples]）。 */
class CopyDirectSamplesTest {

    @Test
    fun everySampleIsRecognizedWithDefaultOptions() {
        for (s in CopyDirectSamples.ALL) assertNotNull(PasscodeRules.match(s), s)
    }

    @Test
    fun tenBundledSamplesEachFindTheirOwnContent() {
        assertEquals(10, CopyDirectSamples.SAMPLES.size)
        for (s in CopyDirectSamples.SAMPLES) {
            val target = assertNotNull(PasscodeRules.match(s.text), s.text).target
            assertEquals(s.key, CopyDirectSamples.preset(target)?.key, s.text)
            if (s.cover != null) assertTrue((CopyDirectSamples.coverBytes(s)?.size ?: 0) > 1000, "${s.key} 封面缺失")
        }
    }

    @Test
    fun platformSamplesStillWorkWhenGenericLinksAreOff() {
        // 「识别所有链接」关着时，有平台规则的样例仍要能弹 —— 界面据此决定要不要提示用户
        val strict = PasscodeRules.Options(genericUrl = false)
        val recognized = CopyDirectSamples.ALL.filter { PasscodeRules.match(it, strict) != null }
        assertTrue(recognized.size >= 4, "严格模式下只认出 ${recognized.size} 条：$recognized")
    }

    @Test
    fun samplesShowSeveralDifferentPlatforms() {
        val platforms = CopyDirectSamples.ALL.mapNotNull { PasscodeRules.match(it)?.platform?.pkg }.toSet()
        assertTrue(platforms.size >= 3, "平台太少，彩蛋没有变化：$platforms")
    }

    @Test
    fun pickNeverRepeatsThePreviousOne() {
        val r = Random(7)
        var prev: String? = null
        repeat(200) {
            val next = CopyDirectSamples.pick(prev, r)
            assertNotEquals(prev, next)
            prev = next
        }
        // 覆盖到整个池子（不是总在两条之间来回）
        val seen = (1..300).map { CopyDirectSamples.pick(null, r) }.toSet()
        assertEquals(CopyDirectSamples.ALL.toSet(), seen)
    }

    @Test
    fun genericOffOnlyPicksPlatformSamples() {
        val r = Random(3)
        val strict = PasscodeRules.Options(genericUrl = false)
        repeat(100) {
            val s = CopyDirectSamples.pick(null, r, genericUrl = false)
            assertNotNull(PasscodeRules.match(s, strict), s)
        }
    }
}
