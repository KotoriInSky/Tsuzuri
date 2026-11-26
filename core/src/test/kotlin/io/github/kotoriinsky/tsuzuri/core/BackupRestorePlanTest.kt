package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupRestorePlanTest {
    private fun item(key: String, type: String, value: Any?) = BackupRestorePlan.Item(key, type, value)

    @Test fun malformedTailDoesNotWriteEarlierItems() {
        var writes = 0
        assertFailsWith<IllegalArgumentException> {
            BackupRestorePlan.restore(1, listOf(item("f.a", "Boolean", true), item("a.size", "Float", "oops"))) { writes++ }
        }
        assertEquals(0, writes)
    }

    @Test fun validBackupWritesOnceAndPreservesTypes() {
        var writes = 0
        var restored: Map<String, Any> = emptyMap()
        BackupRestorePlan.restore(1, listOf(item("f.a", "Boolean", true), item("a.size", "Float", 8.5), item("a.items", "Set", listOf("a", "b")))) {
            writes++; restored = it
        }
        assertEquals(1, writes)
        assertEquals(8.5f, restored["a.size"])
        assertEquals(setOf("a", "b"), restored["a.items"])
    }

    @Test fun v2BackupIsAcceptedAndFiltersTransientInternalKeys() {
        val values = BackupRestorePlan.validate(
            2,
            listOf(
                item(Keys.CONFIG_VERSION, "Integer", 5),
                item(PreviewWallpaperContract.REQUEST_KEY, "Long", 123456L),
                item(ConfigDraftSync.DELETED_KEYS, "Set", listOf("f.old")),
                item("f.side_index", "Boolean", true),
            ),
        )
        assertEquals(5, values[Keys.CONFIG_VERSION])
        assertEquals(true, values["f.side_index"])
        assertFalse(PreviewWallpaperContract.REQUEST_KEY in values)
        assertFalse(ConfigDraftSync.DELETED_KEYS in values)
    }

    @Test fun overflowingIntegerIsRejected() {
        assertFailsWith<IllegalArgumentException> { BackupRestorePlan.validate(1, listOf(item("a.n", "Integer", Long.MAX_VALUE))) }
    }

    @Test fun nonFiniteAndFractionalIntegersAreRejected() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.5)) {
            assertFailsWith<IllegalArgumentException> { BackupRestorePlan.validate(1, listOf(item("a.n", "Integer", value))) }
        }
    }

    @Test fun duplicateAndEmptyKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> { BackupRestorePlan.validate(1, listOf(item("", "Boolean", true))) }
        assertFailsWith<IllegalArgumentException> { BackupRestorePlan.validate(1, listOf(item("a", "Boolean", true), item("a", "String", "b"))) }
    }

    @Test fun stringSetCannotCoerceObjectsOrNumbers() {
        assertFailsWith<IllegalArgumentException> { BackupRestorePlan.validate(1, listOf(item("a", "Set", listOf("ok", 3)))) }
    }

    @Test fun futureVersionAndUnknownTypeDoNotWrite() {
        var written = false
        assertFailsWith<IllegalArgumentException> {
            BackupRestorePlan.restore(BackupRestorePlan.CURRENT_VERSION + 1, listOf(item("a", "Boolean", true))) { written = true }
        }
        assertTrue(!written)
        assertFailsWith<IllegalArgumentException> { BackupRestorePlan.validate(1, listOf(item("a", "Bitmap", "x"))) }
    }

    @Test fun filterForExportStripsTransientKeysAndEnsuresConfigVersion() {
        val exported = BackupRestorePlan.filterForExport(
            mapOf(
                "f.side_index" to true,
                PreviewWallpaperContract.REQUEST_KEY to 999L,
                ConfigDraftSync.DELETED_KEYS to setOf("f.a"),
                "__custom_signal" to "x",
                "a.null" to null,
            ),
        )
        assertEquals(true, exported["f.side_index"])
        assertEquals(Keys.CURRENT_CONFIG_VERSION, exported[Keys.CONFIG_VERSION])
        assertFalse(PreviewWallpaperContract.REQUEST_KEY in exported)
        assertFalse(ConfigDraftSync.DELETED_KEYS in exported)
        assertFalse("__custom_signal" in exported)
        assertFalse("a.null" in exported)
    }

    @Test fun referencedIconFilesExtractsSafePngsAndRejectsTraversal() {
        val sep = IconOverrideEntries.FIELD
        val stamp = IconOverrideEntries.STAMP
        val overrides = setOf(
            "com.tencent.mm${sep}file${sep}fp_icon_com.tencent.mm.png${stamp}a1b2c3d4",
            "com.eg.android.AlipayGphone${sep}pack${sep}com.pack/alipay",
            "bad.path${sep}file${sep}../evil.png${stamp}deadbeef",
            "bad.ext${sep}file${sep}note.txt${stamp}12345678",
            "com.side${sep}file${sep}fp_icon_side.png",
        )
        val files = BackupRestorePlan.referencedIconFiles(
            mapOf(
                "corner_small_window.icon_overrides" to overrides,
                "other.key" to setOf("ignored"),
            ),
        )
        assertEquals(setOf("fp_icon_com.tencent.mm.png", "fp_icon_side.png"), files)
    }

    @Test fun pngMagicAndSafeIconNameValidation() {
        assertTrue(BackupRestorePlan.isSafeIconFileName("fp_icon_com.tencent.mm.png"))
        assertFalse(BackupRestorePlan.isSafeIconFileName("../a.png"))
        assertFalse(BackupRestorePlan.isSafeIconFileName("sub/a.png"))
        assertFalse(BackupRestorePlan.isSafeIconFileName("a.jpg"))
        assertFalse(BackupRestorePlan.isSafeIconFileName(""))

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        assertTrue(BackupRestorePlan.isPngBytes(png))
        assertFalse(BackupRestorePlan.isPngBytes("not a png".encodeToByteArray()))
    }

    @Test fun diffSeparatesMergeAndReplaceAllAndIgnoresConfigVersion() {
        val current = mapOf<String, Any?>(
            Keys.CONFIG_VERSION to 5,
            PreviewWallpaperContract.REQUEST_KEY to 1L,
            "f.a" to true,
            "f.b" to false,
            "f.extra" to true,
        )
        val incoming = mapOf<String, Any>(
            Keys.CONFIG_VERSION to 4,
            "f.a" to true,
            "f.b" to true,
            "f.c" to true,
        )
        val merge = BackupRestorePlan.diff(current, incoming, replaceAll = false)
        assertEquals(BackupRestorePlan.Diff(overwrite = 1, added = 1, removed = 0, unchanged = 1), merge)

        val replace = BackupRestorePlan.diff(current, incoming, replaceAll = true)
        assertEquals(BackupRestorePlan.Diff(overwrite = 1, added = 1, removed = 1, unchanged = 1), replace)
    }

    /**
     * 演进守卫：Catalog 里声明的所有功能开关和参数键都必须被备份涵盖，
     * 防止后续更新误给用户配置键加上 `__` 前缀导致静默漏出备份。
     */
    @Test fun everyCatalogKeyIsIncludedInBackupScope() {
        for (spec in Catalog.features) {
            assertTrue(
                BackupRestorePlan.isBackupConfigKey(Keys.enabled(spec.key)),
                "功能开关键未纳入备份范围：${Keys.enabled(spec.key)}",
            )
            for (pref in spec.prefs) {
                assertTrue(
                    BackupRestorePlan.isBackupConfigKey(pref.key),
                    "参数键未纳入备份范围：${pref.key}",
                )
            }
        }
    }
}
