package io.github.kotoriinsky.tsuzuri.core

/**
 * **root 路**：构造一条「以 root 身份启动某个活动」的命令。**纯字符串逻辑。**
 *
 * ## 它解决的是什么问题
 *
 * 有些活动在 manifest 里写的是 `android:exported="false"`，意味着**只有它自己的进程
 * 能启动它**。外部（包括 SystemUI）调用 `startActivity` 会被系统**静默拒绝** ——
 * 设备实测（2026-09-18）：
 *
 * ```
 * am start -n com.miui.voiceassist/com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity
 * → Starting: Intent { cmp=... }      # 打印了，看着像成功
 * → 顶层纹丝不动                        # 但什么都没发生
 * → 无异常、无日志                      # HyperOS 关了 logcat，连线索都没有
 * ```
 *
 * 而同一个活动**以 root 身份**启动就能开 —— 因为 root 绕过的是
 * **调用方的 uid 归属检查**（`exported=false` 拦的是「别的应用」，不拦「系统自己」）。
 * 这就是本对象存在的全部理由。
 *
 * ## 为什么命令是 `am start -n pkg/act` 而不是别的
 *
 * 三条路都试过，只有这条对：
 *
 * | 手段 | 为什么不行 |
 * | --- | --- |
 * | `ActivityManager.startActivity`（root 侧 binder） | 仍是「root 应用进程」在调，`exported` 检查照旧 |
 * | `am start` 普通调用 | 与上面同罪 —— `am` 是以**调用它的进程**的身份去请求 AMS 的 |
 * | **`su -c 'am start ...'`** | ✅ 子进程 uid 变成 0，绕开归属检查 |
 *
 * ## 安全：这是全项目**唯一**会把用户输入拼进 shell 的地方
 *
 * 用户能在设置里手填「包名/活动名」，那串东西最终要变成一个**以 root 身份执行的命令**。
 * 所以有下面这些硬规矩 —— 每一条都对应一种真实的错法：
 *
 *  1. **不经过 shell 解析**：不给 `sh -c` 一个完整命令行，而是把命令**逐行写进 su 的 stdin**
 *     （`su` 自己 exec 时用的是 argv 数组，不经 shell）。少一层解析 = 少一层注入面。
 *     `;` / `|` / `` ` `` / `$()` 只有在被 shell 解析时才有意义，这里是纯字节写入。
 *  2. **字符白名单，不是黑名单**：包名与活动名都过 [ActivityTargets.check]，
 *     而那个校验只允许 Java 标识符字符（`字母 数字 _ $ .`）。
 *     白名单意味着「想不到的元字符」也不必担心 —— 它压根过不来。
 *  3. **校验在构造命令这一刻再做一次**（不是只依赖「写入配置时校验过」）：
 *     配置可能被手改、被云同步覆盖、或来自旧版本模块。写入侧和读取侧**各自**把关。
 *  4. **`Runnable` 形态而不是 `String`**：本对象输出的是 [Plan]（已经过校验的三元组），
 *     不是拼好的字符串给调用方随便用 —— 调用方拿不到「往命令里塞东西」的口子。
 *
 * ⚠️ **本对象只负责「命令长什么样」，不负责执行。** 执行在 `hook` 侧
 * （`ActivityRootLauncher`），因为 `core` 不许依赖 `android.*`，而执行要 `Process`。
 * 这个分工同样是为了可测：命令构造的每一种错法都能在 JVM 上钉住，
 * 不需要一台能 root 的设备。
 */
object ActivityRootCommand {

    /**
     * `am` 的绝对路径。
     *
     * **不用裸 `am`**：`su` 建立的环境里 `PATH` 不保证包含 `/system/bin`，
     * 而「命令没找到」的表现是**一个空输出、退出码 127**，看起来像「启动失败」——
     * 会把排查引向错误的方向（怀疑活动名写错了）。写死路径则这条错法整个消失。
     *
     * `/system/bin/am` 是 AOSP 标准位置，HyperOS 上实测存在。
     */
    const val AM_PATH = "/system/bin/am"

    /**
     * 启动时给活动附加的 flag。
     *
     * `0x10000000` = `FLAG_ACTIVITY_NEW_TASK`。**必须加**，理由与
     * [io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.FanShortcuts] 里
     * `setClassName` 那条路一致：从非 Activity 上下文发起启动时，没有 task 归属
     * 会直接抛 `AndroidRuntimeException`。`am` 在 shell 里跑，同样是「没有 Activity 上下文」。
     *
     * ⚠️ 这是**十六进制字面量**而不是 `Intent.FLAG_ACTIVITY_NEW_TASK` ——
     * `core` 不许依赖 `android.*`。值取自 `android.content.Intent`，不可变。
     */
    const val FLAG_NEW_TASK = 0x10000000

    /** 启动成功/失败的用户可见输出上限（超长的 stdout 不值得我们读，也避免日志被刷爆）。 */
    const val MAX_OUTPUT = 4096

    /**
     * 一条**已校验**的启动计划。拿到它就意味着「包里装的东西可以安全地拼进命令」。
     *
     * 刻意做成数据类（而不是直接给字符串）：调用方只能按字段取值去拼，
     * 拿不到「往命令里塞任意内容」的入口 —— 这是 [ActivityRootCommand] 的安全边界。
     */
    data class Plan(val packageName: String, val activityName: String) {
        /**
         * `am start` 的 `-n` 参数值（`包名/活动名`）。
         *
         * 前导 `.` 的简写（`com.foo/.Bar`）**原样保留**：`am` 接受这个形式
         * （与 `Intent.setClassName` 同一套展开规则），我们没有理由替用户展开它 ——
         * 展开就要知道包名，而包名就在旁边，看起来好像能做，但**做错了没有任何好处**：
         * 保留原样时用户填什么日志里就是什么，对不上能直接看出是自己填错了。
         */
        val component: String get() = packageName + ShortcutEntries.SEP + activityName
    }

    /**
     * 校验并构造一条启动计划。**任何一处不合法就返回 null，不返回「尽力而为」的结果。**
     *
     * 校验直接复用 [ActivityTargets.check] —— 刻意**不**另写一套 root 专用校验：
     * 两套校验迟早会漂移，而漂移的方向一定是「root 那套更松」，
     * 因为它是后写的、且只被用来「让命令能拼出来」。
     *
     * ## 为什么先把两段各自 trim 再拼
     *
     * 这个函数拿到的是**分开的两段**，而 [ActivityTargets.check] 的 trim 只作用于
     * **整串的两端**：`"  com.foo  "` + `"/"` + `"  com.foo.Bar  "` 拼起来后
     * 中间那些空格**不在串的端点上**，trim 不掉，于是活动名过不了校验、
     * 用户看到「活动名不合法」—— 而他实际上只是复制时多带了空格。
     *
     * 所以这里**先各自 trim 再拼**，让「被校验的形态」与「拼进命令的形态」
     * 严格是同一个串（这也是安全上的必要条件：校验一个东西、执行另一个东西，
     * 中间那点差异就是注入面）。trim 只吃空白，不会把一个非法串变成合法串。
     *
     * @return null = 这两个串过不了活动名校验（含包名非法、含空白、含 `/`、含 shell 元字符），
     *         调用方**必须**当失败处理并记日志
     */
    fun plan(packageName: String, activityName: String): Plan? {
        val pkg = packageName.trim()
        val act = activityName.trim()
        val raw = pkg + ShortcutEntries.SEP + act
        if (ActivityTargets.check(raw) != null) return null
        // check 只 trim 不拆分，这里按同样的规则取出两段（此时已知恰好一个 `/`）
        val sep = raw.indexOf(ShortcutEntries.SEP)
        return Plan(raw.substring(0, sep), raw.substring(sep + 1))
    }

    /**
     * 一条 `am start` 命令行（**不含换行**，换行由写入方补）。
     *
     * 形态刻意与参考实现（`com.x7890.shortcutcreator` 的 `RootOpenActivity`）一致：
     * `am start -n 包名/活动名`。**不带 `--user`**：su 起来的是 root（user 0），
     * 而模块的 [io.github.kotoriinsky.tsuzuri.core.FeatureFlag] 场景全是单用户/主用户。
     * 多带一个 `--user 0` 在分身场景下会启动到错误的用户，
     * 但那是**当前不做的事**（做的时候要连 `FanTarget` 里的 user 一起传下来）。
     *
     * `--activity-clear-top` **不加**：它会把已在栈顶的同一个活动清掉重建，
     * 用户点两次同一项时表现会变得奇怪（第一次是「打开」，第二次是「重开」）。
     *
     * @return 已校验的 [Plan] 对应的命令行；[Plan] 一定非空所以不会返回 null
     */
    fun commandOf(plan: Plan): String = "$AM_PATH start -n ${plan.component}"

    /**
     * 判断 `am` 的输出是否代表**启动请求被受理**。
     *
     * `am start` 的成败判据是**退出码**（见 [isSuccess]），但退出码在
     * 「命令跑起来了但目标是坏的」时仍然可能是 0，所以还要看输出里有没有
     * `Error` / `Exception` 这类字眼 —— `am` 把失败写成
     * `Error: Activity not started, unable to resolve Intent {...}` 并且**退出码仍是 0**。
     *
     * 这是实测出来的：只看退出码会把「活动名拼错」判成成功，
     * 而那时用户看到的是「什么都没发生」+ 日志说「成功」，比失败更难查。
     */
    fun outputIndicatesFailure(output: String): Boolean {
        val text = output.lowercase()
        return text.contains("error:") ||
            text.contains("exception") ||
            text.contains("unable to resolve") ||
            text.contains("does not exist") ||
            text.contains("permission denial") ||
            text.contains("securityexception")
    }

    /**
     * 退出码 → 是不是成功。
     *
     * `su` 自己的退出码约定：0 = 命令跑完；非 0 常见的是 1（用户拒绝/超时）
     * 或 127（`am` 不存在 —— 但我们用了绝对路径，所以出现 127 说明设备环境异常）。
     */
    fun isSuccess(exitCode: Int): Boolean = exitCode == 0

    /**
     * 退出码 → 一句人话。**每种失败都要说清「用户能做什么」**——
     * 与 [ActivityTargets.Rejection] 的每格一文案同一条纪律。
     *
     * @return null = 成功，不需要解释
     */
    fun explainExit(exitCode: Int): String? = when (exitCode) {
        0 -> null
        // KernelSU / Magisk 的 su 在被拒绝、超时、或用户在弹窗上点了拒绝时返回 1。
        // 这一档最需要可操作性：用户多半是「没授权 SystemUI」或「授权弹窗被忽略了」
        1 -> "su 被拒绝或超时（检查 KernelSU 是否已授权 com.android.systemui；" +
            "授权弹窗若出现需要手动确认）"
        126 -> "su 找到但无法执行（权限或 SELinux 拦截）"
        127 -> "$AM_PATH 不存在或无法执行（设备环境异常，不是配置问题）"
        else -> "su 异常退出，退出码 $exitCode"
    }

    /**
     * 输出里**值得记进日志**的那一行（`am` 会把结果写在这里）。
     *
     * `am start` 在 shell 里跑时标准输出形如：
     * ```
     * Starting: Intent { cmp=com.foo/.Bar flg=0x10000000 }
     * ```
     * 失败时是：
     * ```
     * Error: Activity not started, unable to resolve Intent { ... }
     * ```
     * 后者**必须**被看见 —— 它是「命令跑了但目标不对」的唯一线索。
     *
     * @return 已截断（[MAX_OUTPUT]）并去掉首尾空白的输出；空输出返回空串
     */
    fun summarizeOutput(output: String): String {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length <= MAX_OUTPUT) trimmed else trimmed.substring(0, MAX_OUTPUT) + "…（已截断）"
    }
}
