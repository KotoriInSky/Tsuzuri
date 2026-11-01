package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 单应用**图标覆盖**的键格式（本批 3️⃣ 的后半）。
 *
 * ## 为什么这段解析放在 `core` 而不是那个特性的 `hook` 包里
 *
 * 同一套格式现在已经有两份实现：设置界面侧 `ui/state/IconOverridesStore.kt`
 * 与 hook 侧 `hook/.../smallwindow/IconOverrides.kt`（后者是只读参考，本批不许改）。
 * 两个文件都写着「必须与对方逐字一致」，而**两边都没有测试**（`:hook` 没有测试源集）。
 *
 * 本批给侧滑索引加同一个能力时，如果照这个模式再抄一份，格式就有**三**份实现、
 * 而且新抄的那一份同样不可测。所以这里把它提成 `core` 里唯一一份**可测的**实现，
 * 侧滑索引读写两侧都用它；老的两份保持不动（它们属于扇形，不在本批范围内）。
 *
 * 格式（与那两份逐字一致）：
 *
 * ```
 * <targetKey> ␁ pack ␁ <图标包包名>/<drawable 名>
 * <targetKey> ␁ file ␁ <文件名> ␂ <内容指纹>
 * ```
 *
 * 三条必须钉住的：**指纹只影响缓存判定**（解析不出指纹的老配置也得能用）、
 * **坏条目跳一条而不是丢一份**、**签名与迭代顺序无关**（否则集合换个顺序就白丢一次缓存）。
 */
class IconOverrideEntriesTest {

    @Test
    fun aPackOverrideSurvivesTheRoundTrip() {
        val map = mapOf("com.tencent.mm" to IconOverrideEntries.Entry(IconOverrideEntries.KIND_PACK, "com.x.pack/messages"))
        assertEquals(map, IconOverrideEntries.decode(IconOverrideEntries.encode(map)))
    }

    @Test
    fun aFileOverrideKeepsItsStampThroughTheRoundTrip() {
        val map = mapOf("com.tencent.mm" to IconOverrideEntries.Entry(IconOverrideEntries.KIND_FILE, "fp_icon_x.png\u0002a1b2c3d4"))
        val decoded = IconOverrideEntries.decode(IconOverrideEntries.encode(map))
        assertEquals(map, decoded)
        // 指纹不参与渲染：取文件名时必须把它截掉，否则会拼出一个不存在的文件
        assertEquals("fp_icon_x.png", IconOverrideEntries.fileName(decoded.getValue("com.tencent.mm").value))
    }

    @Test
    fun aFileValueWithoutAStampStillYieldsTheFileName() {
        // 老配置（加指纹之前写进去的）必须照常可用 —— 否则用户升上来之后
        // 之前设的图标会全部消失，而且没有任何报错。
        assertEquals("fp_icon_old.png", IconOverrideEntries.fileName("fp_icon_old.png"))
        assertEquals("", IconOverrideEntries.fileName(""))
    }

    @Test
    fun thePackReferenceIsSplitOnTheFirstSlash() {
        assertEquals("com.x.pack" to "messages", IconOverrideEntries.packRef("com.x.pack/messages"))
        // drawable 名里带斜杠不可能，但包名里带点的分割方式不能受影响
        assertEquals("com.x.pack" to "a_b", IconOverrideEntries.packRef("com.x.pack/a_b"))
        // 不成对的一律 null（调用方退回自带图标，而不是查一个空名字）
        assertNull(IconOverrideEntries.packRef("com.x.pack"))
        assertNull(IconOverrideEntries.packRef("/messages"))
        assertNull(IconOverrideEntries.packRef("com.x.pack/"))
        assertNull(IconOverrideEntries.packRef(""))
    }

    @Test
    fun malformedEntriesAreSkippedButTheRestSurvives() {
        val decoded = IconOverrideEntries.decode(
            listOf(
                "com.a\u0001pack\u0001com.p/one",
                "没有分隔符",
                "com.b\u0001pack",
                "\u0001pack\u0001com.p/two",
                "com.c\u0001pack\u0001",
                "com.d\u0001file\u0001x.png\u0002deadbeef",
            ),
        )
        assertEquals(setOf("com.a", "com.d"), decoded.keys)
        assertEquals(IconOverrideEntries.KIND_FILE, decoded.getValue("com.d").kind)
    }

    @Test
    fun anUnknownKindIsStillParsedSoTheCacheCanSeeItChange() {
        // 将来加了第三种 kind（老版本读到新版本写的配置）时，这一条必须留在 map 里：
        // 丢掉它的话，「用户改了图标」不会改变签名，解码缓存就永远不会失效。
        val decoded = IconOverrideEntries.decode(listOf("com.a\u0001shape\u0001circle"))
        assertEquals(IconOverrideEntries.Entry("shape", "circle"), decoded.getValue("com.a"))
    }

    @Test
    fun theTargetKeyIsTrimmedSoWhitespaceCannotBreakMatching() {
        val decoded = IconOverrideEntries.decode(listOf(" com.a \u0001pack\u0001com.p/one"))
        assertTrue(decoded.containsKey("com.a"))
    }

    @Test
    fun theSignatureIsStableAcrossIterationOrderAndChangesWithContent() {
        // 集合的迭代顺序是实现细节（`StringSet` 的 toString 就是同一个坑）：
        // 用顺序相关的签名会让「什么都没改」也丢掉整块解码缓存。
        val a = listOf("com.a\u0001file\u0001x.png\u00020102", "com.b\u0001pack\u0001com.p/two")
        val b = listOf("com.b\u0001pack\u0001com.p/two", "com.a\u0001file\u0001x.png\u00020102")
        assertEquals(IconOverrideEntries.signature(a), IconOverrideEntries.signature(b))

        // 同一个 target 换了一张新图（文件名不变、指纹变了）→ 签名必须变，
        // 否则用户会看到旧图（这正是指纹那一段存在的全部理由）。
        val before = listOf("com.a\u0001file\u0001x.png\u00020102")
        val after = listOf("com.a\u0001file\u0001x.png\u0002ffff")
        assertNotEquals(IconOverrideEntries.signature(before), IconOverrideEntries.signature(after))
    }

    @Test
    fun anEmptyConfigIsAnEmptyMapAndAnEmptySignature() {
        assertEquals(emptyMap(), IconOverrideEntries.decode(emptyList()))
        assertEquals("", IconOverrideEntries.signature(emptyList()))
        assertNull(IconOverrideEntries.decode(listOf("com.a\u0001pack\u0001com.p/one"))["com.b"])
    }
}
