package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 生效范围（`side_index.scope` 三档）+ **用户可配的「哪些包算桌面」**（本批 1️⃣）。
 *
 * 这些用例钉住的是一条**关系**，不是一串常量：`scope` 的三档语义没有变
 * （`launcher_only` / `anywhere` / `exclude_launcher`），变的是「什么算桌面」——
 * 以前是 `ForegroundTracker` 一个布尔，现在是「用户配的集合」优先、没配过时退回那个布尔。
 *
 * 三个最容易做歪、而且做歪了**不会有任何报错**的地方：
 *
 *  1. **没配过 ≠ 配成空**。空集（= 用户没勾过）必须走自动挑选（= 改动前的行为），
 *     而不是「一个都不算桌面」—— 后者会让 `launcher_only` 变成「永远不生效」，
 *     用户看到的就是「这个功能被我点坏了」。
 *  2. **配过就必须听用户的**。用户把桌面包名集合配成「只有 Niagara」时，
 *     系统默认桌面（`com.miui.home`）就**不算**桌面了 —— 这是「我换桌面了、
 *     别再按老桌面判」这件事在配置上的唯一表达。
 *  3. **拿不到前台包名一律不放行**（照 Appindex 的 `scopeAllowsForegroundApp` 第一条），
 *     所以 `allowAll` 那一档也不能把 `null` 放过去。
 */
class LauncherScopeTest {

    // ── 没配过：退回「系统解析出来的桌面」（= 改动前逐位一致）────────────────

    @Test
    fun unconfiguredLauncherSetUsesTheSystemsResolvedLauncher() {
        // 参考机上的现状：解析出来的桌面就是 com.miui.home，前台也是它 → 放行。
        // 这一条同时是「默认值 = 现在行为」的证据。
        assertTrue(
            LauncherScope.allows(
                scope = LauncherScope.LAUNCHER_ONLY,
                foreground = "com.miui.home",
                configuredLaunchers = emptySet(),
                resolvedHomeIsForeground = true,
            ),
        )
        assertFalse(
            LauncherScope.allows(
                scope = LauncherScope.LAUNCHER_ONLY,
                foreground = "com.tencent.mm",
                configuredLaunchers = emptySet(),
                resolvedHomeIsForeground = false,
            ),
        )
    }

    @Test
    fun unconfiguredAndUnresolvableLauncherNeverAllowsLauncherOnly() {
        // 解析桌面包名失败 → 该布尔恒为 false → launcher_only 一律不放行。
        // 这是**改动前就有的降级方向**，本批把它写出来了（以前只在 KDoc 里提过一句）：
        // 保守方向（不放行）比「什么都放行」安全，而且它现在会在安装报告里看得见。
        assertFalse(
            LauncherScope.allows(
                scope = LauncherScope.LAUNCHER_ONLY,
                foreground = "com.miui.home",
                configuredLaunchers = emptySet(),
                resolvedHomeIsForeground = false,
            ),
        )
    }

    @Test
    fun unconfiguredExcludeLauncherDegradesToAnywhereWhenTheLauncherIsUnknown() {
        // 同一件事的另一面：`exclude_launcher` 在解析失败时退化成 `anywhere`。
        // 方向可接受（用户明确说了「除桌面以外」），但**必须写得出来**，
        // 否则「除桌面以外怎么哪儿都生效」会被当成 bug 查半天。
        assertTrue(
            LauncherScope.allows(
                scope = LauncherScope.EXCLUDE_LAUNCHER,
                foreground = "com.miui.home",
                configuredLaunchers = emptySet(),
                resolvedHomeIsForeground = false,
            ),
        )
    }

    // ── 配过：以用户配的集合为准，**不再看**解析出来的那个布尔 ──────────────

    @Test
    fun configuredLauncherSetWinsOverTheResolvedLauncher() {
        // 真实场景（产品经理点名的那一个）：默认桌面还是 com.miui.home，
        // 但用户实际用的是 Niagara。他把 Niagara 勾上之后，**必须**按 Niagara 放行 ——
        // 这正是「换桌面之后 launcher_only 整个废了」的修法。
        val configured = setOf("bitpit.launcher")
        assertTrue(
            LauncherScope.allows(
                scope = LauncherScope.LAUNCHER_ONLY,
                foreground = "bitpit.launcher",
                configuredLaunchers = configured,
                // 追踪器仍然按老桌面判（它的解析发生在启动时）——配过之后它不再参与判定
                resolvedHomeIsForeground = false,
            ),
        )
    }

    @Test
    fun configuredLauncherSetAlsoExcludesTheOldHome() {
        // 「混用第三方桌面 + 系统桌面」的反面：用户只勾了 Niagara，
        // 那么躺在 com.miui.home 上时**不算**桌面 —— 语义必须对称，
        // 否则「我明明把系统桌面从清单里删了」会变成一句假话。
        assertFalse(
            LauncherScope.allows(
                scope = LauncherScope.LAUNCHER_ONLY,
                foreground = "com.miui.home",
                configuredLaunchers = setOf("bitpit.launcher"),
                resolvedHomeIsForeground = true,
            ),
        )
        // 两个都勾 = 混用；两个都算桌面
        val both = setOf("bitpit.launcher", "com.miui.home")
        assertTrue(
            LauncherScope.allows(
                LauncherScope.LAUNCHER_ONLY, "com.miui.home", both, resolvedHomeIsForeground = false,
            ),
        )
        assertTrue(
            LauncherScope.allows(
                LauncherScope.LAUNCHER_ONLY, "bitpit.launcher", both, resolvedHomeIsForeground = false,
            ),
        )
    }

    @Test
    fun configuredLauncherSetDrivesExcludeLauncherTheSameWay() {
        val configured = setOf("bitpit.launcher")
        // 在配置的桌面里 → 被排除
        assertFalse(
            LauncherScope.allows(
                LauncherScope.EXCLUDE_LAUNCHER, "bitpit.launcher", configured, false,
            ),
        )
        // 不在配置的桌面里（哪怕它是系统默认桌面）→ 放行
        assertTrue(
            LauncherScope.allows(
                LauncherScope.EXCLUDE_LAUNCHER, "com.miui.home", configured, true,
            ),
        )
    }

    // ── 拿不到前台包名：一律不放行（照 Appindex）───────────────────────────

    @Test
    fun aMissingForegroundPackageNeverAllowsAnyScope() {
        for (scope in listOf(
            LauncherScope.LAUNCHER_ONLY,
            LauncherScope.ANYWHERE,
            LauncherScope.EXCLUDE_LAUNCHER,
            "某个读不出来的取值",
        )) {
            assertFalse(
                LauncherScope.allows(scope, null, setOf("com.miui.home"), true),
                "scope=$scope 时 null 前台不该放行",
            )
        }
    }

    @Test
    fun anywhereIgnoresTheForegroundAndTheLauncherSet() {
        assertTrue(
            LauncherScope.allows(LauncherScope.ANYWHERE, "com.tencent.mm", emptySet(), false),
        )
    }

    @Test
    fun anUnknownScopeFallsBackToTheMostConservativeOne() {
        // 配置被手改过 / 将来加了新档位而没更新代码时，按最保守的一档处理。
        // 反过来（未知 = 全放行）会让一次手改配置变成「到处都弹出面板」。
        assertTrue(LauncherScope.allows("???", "com.miui.home", emptySet(), true))
        assertFalse(LauncherScope.allows("???", "com.tencent.mm", emptySet(), false))
    }

    // ── 集合的归一化 ────────────────────────────────────────────────────

    @Test
    fun theConfiguredSetIsNormalizedSoWhitespaceCannotBreakMatching() {
        // 配置通道上的值不该被信任：手改过的配置里带前后空格是非常常见的一种。
        // 不 trim 的表现是「明明勾了、包名也对，就是不生效」—— 最难查的一类。
        val normalized = LauncherScope.normalizeLaunchers(listOf(" com.miui.home ", "", "  ", "bitpit.launcher"))
        assertEquals(setOf("com.miui.home", "bitpit.launcher"), normalized)
        assertTrue(
            LauncherScope.allows(
                LauncherScope.LAUNCHER_ONLY, "com.miui.home", normalized, false,
            ),
        )
        // 全空白 = 归一化成空集 = 没配过（不是「配了一个空包名」）
        assertEquals(emptySet(), LauncherScope.normalizeLaunchers(listOf("", "   ")))
    }

    // ── 过渡性系统面（照 Appindex ForegroundAppGatePolicy）──────────────────

    @Test
    fun theSecondaryScreenLauncherIsATransientSystemSurface() {
        // 本机在桌面时 getTopVisibleTask 报的就是它（设备实测）。它绝不能参与判定，
        // 否则启动后那几秒里生效范围会被一个「不是用户应用」的面改掉结论。
        assertTrue(LauncherScope.isTransientSystemSurface("com.xiaomi.subscreencenter"))
        assertTrue(LauncherScope.isTransientSystemSurface(" com.xiaomi.subscreencenter "))
        assertFalse(LauncherScope.isTransientSystemSurface("com.miui.home"))
        assertFalse(LauncherScope.isTransientSystemSurface(null))
    }

    // ── 安装报告里那句话 ────────────────────────────────────────────────

    @Test
    fun describeMakesTheAutoFallbackVisible() {
        // 「没配过」与「配了但解析不出桌面」是两种完全不同的病因，
        // 而它们在行为上都是 launcher_only 不放行 —— 报告里必须分得开。
        assertEquals("auto", LauncherScope.describeLaunchers(emptySet()))
        // 排序后再拼：集合的迭代顺序是实现细节，报告里的那一行必须每次一样
        assertEquals("bitpit.launcher,com.miui.home", LauncherScope.describeLaunchers(
            setOf("com.miui.home", "bitpit.launcher"),
        ))
    }
}
