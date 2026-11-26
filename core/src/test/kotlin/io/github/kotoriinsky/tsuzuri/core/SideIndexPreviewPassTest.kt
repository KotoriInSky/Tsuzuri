package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SideIndexPreviewPassTest {

    @Test fun allowsOnlyModulePackageWithinLeaseWindow() {
        val now = 1_000_000L
        val until = now + SideIndexPreviewPass.LEASE_MS
        assertTrue(SideIndexPreviewPass.isActive(SideIndexPreviewPass.ALLOWED_PACKAGE, until, now))
        assertFalse(SideIndexPreviewPass.isActive("com.miui.home", until, now))
        assertFalse(SideIndexPreviewPass.isActive(null, until, now))
    }

    @Test fun rejectsExpiredRevokedOrExcessiveLease() {
        val now = 1_000_000L
        assertFalse(SideIndexPreviewPass.isActive(SideIndexPreviewPass.ALLOWED_PACKAGE, 0L, now))
        assertFalse(SideIndexPreviewPass.isActive(SideIndexPreviewPass.ALLOWED_PACKAGE, now, now))
        assertFalse(SideIndexPreviewPass.isActive(SideIndexPreviewPass.ALLOWED_PACKAGE, now - 1L, now))
        assertFalse(
            SideIndexPreviewPass.isActive(
                SideIndexPreviewPass.ALLOWED_PACKAGE,
                now + SideIndexPreviewPass.MAX_LEASE_MS + 1L,
                now,
            ),
        )
    }

    /** 备份守卫（docs/02 §9.6）：临时通行信号键绝不能污染用户配置备份。 */
    @Test fun signalKeyIsExcludedFromUserBackup() {
        assertFalse(BackupRestorePlan.isBackupConfigKey(SideIndexPreviewPass.SIGNAL_KEY))
    }
}
