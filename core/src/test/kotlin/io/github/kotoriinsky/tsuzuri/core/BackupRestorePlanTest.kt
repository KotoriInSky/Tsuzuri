package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertFailsWith<IllegalArgumentException> { BackupRestorePlan.restore(2, listOf(item("a", "Boolean", true))) { written = true } }
        assertTrue(!written)
        assertFailsWith<IllegalArgumentException> { BackupRestorePlan.validate(1, listOf(item("a", "Bitmap", "x"))) }
    }
}
