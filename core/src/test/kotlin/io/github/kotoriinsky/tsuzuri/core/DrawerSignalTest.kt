package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DrawerSignal] 的不变量。
 *
 * 这块逻辑的两种错法都是**静默**的：
 *  - 漏判（该抑制时判 false）→ 抽屉里照弹侧滑索引，也就是这个需求没做；
 *  - 误判（不该抑制时判 true）→ 桌面上索引完全不弹，用户看到的是「这功能坏了」。
 *
 * 所以判据全部钉在**真机实测到的原始值**上（`softInputMode` 0x33 / 0x10，
 * 以及那个会误伤的同包名同 type 的 overlay 窗口），不是自己编的样例。
 */
class DrawerSignalTest {

    // 设备实测（`dumpsys window windows`，Xiaomi 17 Pro / HyperOS 4）：
    //   桌面态：mAttrs={... sim={state=always_hidden adjust=nothing} ...} → 0x33
    //   抽屉态：mAttrs={... sim={adjust=resize}                     ...} → 0x10
    private val desktopSim = 0x33
    private val drawerSim = 0x10

    // 真机上的两个真实 title（Window{...} 里打印的就是 mAttrs.getTitle()）
    private val launcherTitle = "com.miui.home/com.miui.home.launcher.Launcher"
    private val quickSearchBoxOverlayTitle = "LauncherOverlayWindow:com.android.quicksearchbox"
    private val personalAssistantOverlayTitle = "LauncherOverlayWindow:com.miui.personalassistant"

    private val home = "com.miui.home"
    private val marker = DrawerSignal.titleMarker(home)

    @Test
    fun drawerOpenFromMeasuredValues() {
        assertFalse(DrawerSignal.isDrawerOpen(desktopSim), "实测的桌面态值必须判为「没开抽屉」")
        assertTrue(DrawerSignal.isDrawerOpen(drawerSim), "实测的抽屉态值必须判为「开着抽屉」")
    }

    @Test
    fun drawerOpenOnlyLooksAtAdjustBits() {
        // state 位（低 4 位）不该影响结论：抽屉里点搜索框会让 state 变成 VISIBLE
        assertTrue(DrawerSignal.isDrawerOpen(drawerSim or 0x1))
        assertTrue(DrawerSignal.isDrawerOpen(drawerSim or 0x3))
        // adjust 的其它三档（UNSPECIFIED 0 / PAN 0x20 / NOTHING 0x30）都不是抽屉态
        assertFalse(DrawerSignal.isDrawerOpen(0x00))
        assertFalse(DrawerSignal.isDrawerOpen(0x20))
        assertFalse(DrawerSignal.isDrawerOpen(0x30))
        assertEquals(DrawerSignal.SOFT_INPUT_ADJUST_NOTHING, desktopSim and DrawerSignal.SOFT_INPUT_MASK_ADJUST)
    }

    @Test
    fun launcherMainWindowMatchesTheRealWindow() {
        assertTrue(
            DrawerSignal.isLauncherMainWindow(home, DrawerSignal.TYPE_BASE_APPLICATION, launcherTitle, home, marker),
        )
    }

    @Test
    fun launcherMainWindowRejectsSamePackageBaseApplicationOverlay() {
        // ⚠️ 这条是实测踩出来的坑：quicksearchbox 的 LauncherOverlayWindow 的 LayoutParams
        // 同样是 pkg = com.miui.home ty=BASE_APPLICATION。只按「包名 + type」过滤会把它算进来，
        // 而它的 softInputMode 未必跟抽屉一起变 → 假信号。
        assertFalse(
            DrawerSignal.isLauncherMainWindow(
                home,
                DrawerSignal.TYPE_BASE_APPLICATION,
                quickSearchBoxOverlayTitle,
                home,
                marker,
            ),
        )
        assertFalse(
            DrawerSignal.isLauncherMainWindow(
                home,
                DrawerSignal.TYPE_BASE_APPLICATION,
                personalAssistantOverlayTitle,
                home,
                marker,
            ),
        )
    }

    @Test
    fun launcherMainWindowRejectsOtherTypesAndPackages() {
        // 桌面进程的其它窗口（真机实测共 6 个带 pkg = com.miui.home 的窗口）
        assertFalse(
            DrawerSignal.isLauncherMainWindow(home, 2035 /* MAGNIFICATION_OVERLAY */, launcherTitle, home, marker),
        )
        assertFalse(
            DrawerSignal.isLauncherMainWindow(home, 2038 /* SYSTEM_OVERLAY */, launcherTitle, home, marker),
        )
        // 别的包即使 title 形状一样也不算
        assertFalse(
            DrawerSignal.isLauncherMainWindow("com.other.home", DrawerSignal.TYPE_BASE_APPLICATION, launcherTitle, home, marker),
        )
    }

    @Test
    fun launcherMainWindowHandlesNulls() {
        // relayoutWindow 的 attrs 可以为 null（客户端只要求重新 layout）；title 也可能没设。
        // 两种都必须安静地判 false —— 抛异常会打到 system_server 的热路径上。
        // 注意 packageName 单独为 null **不是** false 的理由（它是软校验，见下一个用例）。
        assertFalse(DrawerSignal.isLauncherMainWindow(home, DrawerSignal.TYPE_BASE_APPLICATION, null, home, marker))
        assertFalse(DrawerSignal.isLauncherMainWindow(null, DrawerSignal.TYPE_BASE_APPLICATION, null, home, marker))
        // 空 title 不能因为 startsWith 的边界被当成主窗口
        assertFalse(DrawerSignal.isLauncherMainWindow(home, DrawerSignal.TYPE_BASE_APPLICATION, "", home, marker))
    }

    @Test
    fun launcherMainWindowTreatsPackageNameAsSoftCheck() {
        // packageName 是**软校验**：客户端不填时不能因此让整条信号死掉
        // （依据：framework.jar 里 LayoutParams.copyFrom 无条件拷 packageName 与 mTitle，
        //  但那是「当前这台机器」的事实，不该写成硬条件 —— 硬条件会在别的 ROM 上静默失效）
        assertTrue(
            DrawerSignal.isLauncherMainWindow(null, DrawerSignal.TYPE_BASE_APPLICATION, launcherTitle, home, marker),
        )
        // 填了且不一样 → 仍然否决
        assertFalse(
            DrawerSignal.isLauncherMainWindow("com.evil.home", DrawerSignal.TYPE_BASE_APPLICATION, launcherTitle, home, marker),
        )
    }

    @Test
    fun signalFreshness() {
        // 从来没收到过信号 = 通道没建立 → 一律不新鲜（= 不抑制，行为与未安装模块一致）
        assertFalse(DrawerSignal.isSignalFresh(0L, 1_000_000L))
        assertFalse(DrawerSignal.isSignalFresh(-1L, 1_000_000L))

        assertTrue(DrawerSignal.isSignalFresh(1_000L, 1_000L))
        assertTrue(DrawerSignal.isSignalFresh(1_000L, 1_000L + DrawerSignal.STALE_MS))
        assertFalse(DrawerSignal.isSignalFresh(1_000L, 1_000L + DrawerSignal.STALE_MS + 1))

        // 时钟倒退（重启后 uptime 归零）：按不新鲜处理，方向更安全
        assertFalse(DrawerSignal.isSignalFresh(1_000_000L, 5L))
    }

    @Test
    fun describeCoversTheFourStates() {
        val stale = DrawerSignal.STALE_MS + 1
        assertEquals(
            "drawer=unavailable(register failed)",
            DrawerSignal.describe(0L, 1_000L, false, 0, "register failed"),
        )
        assertEquals("drawer=unknown(未收到信号)", DrawerSignal.describe(0L, 1_000L, false, 0, null))
        assertEquals("drawer=unknown(通道超时)", DrawerSignal.describe(100L, 100L + stale, false, 3, null))
        assertEquals("drawer=open(已收到5条)", DrawerSignal.describe(100L, 200L, true, 5, null))
        assertEquals("drawer=closed(已收到5条)", DrawerSignal.describe(100L, 200L, false, 5, null))
    }
}
