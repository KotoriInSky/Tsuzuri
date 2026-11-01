package io.github.kotoriinsky.tsuzuri.core

/**
 * 侧滑索引的**生效范围**：`scope` 三档 + 「哪些包算桌面」。
 *
 * ## 这一版改了什么（本批 1️⃣）
 *
 * `scope` 的三档语义**一个字都没改**：
 *
 * | 取值 | 含义 |
 * | --- | --- |
 * | `launcher_only`（默认） | 只在桌面 |
 * | `anywhere` | 哪儿都生效 |
 * | `exclude_launcher` | 除桌面以外 |
 *
 * 改的是「**什么算桌面**」这个判据的来源。以前它只有一条路：`ForegroundTracker`
 * 在启动时用 `PackageManager` 解析一次 `ACTION_MAIN/CATEGORY_HOME`，之后一直用那个包名
 * 和前台包名比对（一个布尔 [resolvedHomeIsForeground]）。那条路有真实的缺陷 ——
 * **用户换第三方桌面之后它还是老包名**，于是 `launcher_only` 整个失效
 * （产品经理点名的场景：他在用 Niagara）。
 *
 * 现在多了一条优先路：**用户在设置里勾的那几个包名**（[configuredLaunchers]）。
 *
 * ## 主 / 辅 / 退化，一条一条说清
 *
 *  1. **主**：用户配过（集合非空）→ **只**认这个集合。`ForegroundTracker` 那个布尔
 *     完全不参与 —— 这是「我换桌面了、别再按老桌面判」的唯一表达方式。
 *     用户只勾 Niagara 时，`com.miui.home` 就**不算**桌面（对称，不是单向的）。
 *  2. **辅**（没配过 = 集合为空）：退回 [resolvedHomeIsForeground]，**与改动前逐位一致**。
 *     参考机上解析出来的就是 `com.miui.home`，所以默认值下的行为没有任何变化。
 *  3. **退化**（没配过**并且**解析不出桌面包名）：那个布尔恒为 false →
 *     `launcher_only` **一律不放行**、`exclude_launcher` **退化成 `anywhere`**。
 *     方向是有意的：不知道桌面是哪一个时，「只在桌面生效」必须保守；
 *     而用户明确选了「除桌面以外」，多生效总好过整个失效。
 *     这条退化**现在看得见**了 —— 安装报告里有 `launcher_packages=auto`，
 *     日志里还有一行「未配置且解析不出桌面」的提示（原来只有 KDoc 里的一句话）。
 *
 * ## 「拿不到前台包名」与「过渡性系统面」
 *
 * 两条都是从参考项目 Appindex 逐条抄来的策略，都不是保守主义：
 *
 *  - `foreground == null` → **不放行**（`Appindex/AppScopeUiModel.scopeAllowsForegroundApp`
 *    的第二条 `return false`）。不知道用户在哪个应用里，就没有依据说「这里该生效」。
 *  - [isTransientSystemSurface]（`Appindex/ForegroundAppGatePolicy`）：HyperOS 在主屏
 *    桌面还没稳定时会报 `com.xiaomi.subscreencenter`（后置副屏的桌面），**本机实测在桌面
 *    时确实报的就是它**。它是过渡性的系统面、不是用户应用，绝不能因为它把结论改掉。
 *
 * ## 为什么放在 `core`（纯逻辑、可测）
 *
 * 判定的**全部**输入都是显式参数，没有隐藏状态、不碰 `ForegroundTracker`、
 * 不需要 `Context` —— 于是它可以被 `LauncherScopeTest` 逐条钉住。
 * 真机上「配了却不生效」这类问题，绝大多数都出在这几行的关系上，
 * 而它们以前是写死在两个进程各一份的 hook 代码里的。
 */
object LauncherScope {

    const val LAUNCHER_ONLY = "launcher_only"
    const val ANYWHERE = "anywhere"
    const val EXCLUDE_LAUNCHER = "exclude_launcher"

    /** 照 Appindex `ForegroundAppGatePolicy.transientSystemSurfaces`。 */
    private val TRANSIENT_SYSTEM_SURFACES = setOf("com.xiaomi.subscreencenter")

    /** 这个前台包是不是「不该参与判定」的过渡性系统面。 */
    fun isTransientSystemSurface(packageName: String?): Boolean =
        packageName?.trim() in TRANSIENT_SYSTEM_SURFACES

    /**
     * 归一化用户配的桌面包名集合。
     *
     * 配置通道上的值不该被信任（可以被手改、可以被别的版本写过）：前后空白一律 trim、
     * 空项丢掉。不做这一步的表现是「清单里明明有 `com.miui.home`，就是不生效」——
     * 一个只差一个空格的偏差，肉眼在诊断页上都看不出来。
     *
     * **全空白归一化成空集**（= 没配过），而不是「配了一个空包名」：
     * 后者会走到「用户配过」那一支，于是 `launcher_only` 永远不放行。
     */
    fun normalizeLaunchers(packages: Iterable<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (p in packages) {
            val t = p?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    /**
     * 该不该放行。
     *
     * @param scope 配置里的档位。**未知取值按 `launcher_only` 处理**（最保守的一档）——
     *   反过来（未知 = 全放行）会让一次手改配置变成「到处都弹出面板」。
     * @param foreground 前台包名。`null` 一律不放行，见类注释。
     * @param configuredLaunchers 用户配的桌面包名集合（**非空 = 听用户的**）。
     *   调用方应当先用 [normalizeLaunchers] 过一遍。
     * @param resolvedHomeIsForeground 系统解析出来的那个桌面是不是前台。
     *   **只在 [configuredLaunchers] 为空时参与判定**（见类注释的主/辅/退化）。
     */
    fun allows(
        scope: String?,
        foreground: String?,
        configuredLaunchers: Set<String>,
        resolvedHomeIsForeground: Boolean,
    ): Boolean {
        // Appindex 的 `scopeAllowsForegroundApp` 第一条：拿不到前台包名 → 不放行。
        // 注意它排在 `anywhere` **前面** —— 「哪儿都生效」也不该在「不知道在哪儿」时生效。
        if (foreground == null) return false
        val isLauncher = if (configuredLaunchers.isEmpty()) {
            resolvedHomeIsForeground
        } else {
            foreground in configuredLaunchers
        }
        return when (scope) {
            ANYWHERE -> true
            EXCLUDE_LAUNCHER -> !isLauncher
            // 未知取值一律按最保守的一档处理（配置读不出来时默认值就是 launcher_only）
            else -> isLauncher
        }
    }

    /**
     * 安装报告里那一行（`launcher_packages=...`）。
     *
     * 空集打 `auto` 而不是空字符串是有意的：「用户没配过」和「配了但配出来是空」
     * 在报告里必须分得开，否则「我明明配了却不生效」没有第二个可核对的地方。
     */
    fun describeLaunchers(configuredLaunchers: Set<String>): String =
        if (configuredLaunchers.isEmpty()) "auto" else configuredLaunchers.sorted().joinToString(",")
}
