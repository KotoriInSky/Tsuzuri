package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.kotoriinsky.tsuzuri.core.ActivityRootCommand
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * **root 路**：以 root 身份启动活动（`su` 起 uid=0 子进程执行 `am start`）。
 *
 * ## ⛔ 它**不能**解决什么问题（2026-09-18 实测，最重要的一条）
 *
 * 这个类最初是为了打开 `android:exported="false"` 的活动而写的，
 * **实测证明那行不通**：root 绕不过 `exported`。摘要：
 *
 * ```
 * $ su -c 'am start -n com.miui.voiceassist/…screenrecognition.ScreenRecognitionActivity'
 * Status: ok
 * LaunchState: UNKNOWN (0)   ← 正常冷启动会报 COLD；UNKNOWN = 受理了但没真正启动
 * WaitTime: 92               ← 92ms，根本没走启动流程
 * （dumpsys 里搜不到该活动，顶层纹丝不动）
 * ```
 *
 * **根因**：`exported` 是 PackageManager 层的**组件可见性**判定，按**调用方 uid** 算，
 * **uid 0 没有豁免**（豁免只给同 uid 或持特定系统权限的调用方）。
 * 一句话：**root 能让你做任何事，但不能让你假装成「这个应用自己」。**
 *
 * ## 那它现在有什么用
 *
 * 它绕的是**另一层**：MIUI 加在应用层的启动拦截 —— 即 `exported=true`
 * 但被 MIUI 自己拦下的活动。那一层 root 有效，因为它不经过 MIUI 加在应用层的检查。
 *
 * ## 机制（这一部分实测是通的）
 *
 * `su` 起 uid=0 子进程执行 `/system/bin/am start -n 包名/活动名`。
 * 验证：`su -c 'am start -n com.miui.calculator/.cal.CalculatorActivity'`
 * → 顶层确实变成计算器（导出的活动，root 启动成功）。
 *
 * ## 执行形态的三个硬约束（每一条都有理由）
 *
 *  1. **不用 `sh -c`**：把命令逐行写进 `su` 的 stdin（[DataOutputStream.write]）。
 *     `su` 内部 exec 用的是 argv 数组，不经 shell 解析。少一层解析 = 少一层注入面。
 *     （命令本身的安全性由 [ActivityRootCommand] 的字符白名单保证。）
 *  2. **绝不在主线程跑**：[executor] 是单线程池。`su` 是**阻塞**调用，
 *     授权弹窗出现时它会一直等到用户点选（或超时）—— 在 SystemUI 主线程上跑
 *     等于把系统界面冻住。这是本文件存在「异步」这个复杂度的全部原因。
 *  3. **必须有超时**：[TIMEOUT_SECONDS]。用户没授权时 KernelSU 会弹窗，
 *     无人应答的弹窗不该把线程池永久占住。
 *
 * ## 调用方要理解的一件事：这是**异步**的
 *
 * [launch] 立刻返回、启动结果稍后才出（走日志）。这是刻意的：
 * 主线程不能等 root。所以 root 路**不能**参与「9/10/11 级」那套同步降级 ——
 * 它是启动了 root 请求就说「已发出」，成败由日志说话。
 * [FanShortcuts] 的第 12 级因此与前面几级**语义不同**，那里有注释说明。
 */
object ActivityRootLauncher {

    /**
     * `su` 等待授权 + `am` 执行的超时（秒）。
     *
     * 13 秒的来历：KernelSU 的授权弹窗在无人操作时留给用户的决策时间就是这个量级，
     * 而 `am start` 本身在真机上是 **100ms 级**（实测）。所以这个超时实际上
     * 量的是「用户点授权弹窗的时间」，给宽一点不误伤 —— 但也不能无限，
     * 否则未授权时每次点击都占住线程池 13 秒。
     *
     * ⚠️ 用户**第一次**授权时必然会撞上这个等待（弹窗要人点），
     * 所以这一级在首次使用时「慢」是预期行为，不是 bug。
     */
    private const val TIMEOUT_SECONDS = 13L

    /**
     * 单线程池。**必须是单线程**：`su` 的授权弹窗是**串行**的，
     * 并发起多个 `su` 会在 KernelSU 侧排队、且日志交错到无法阅读。
     * 单线程还顺带保证了「同时只启动一个活动」—— 那正是用户手点扇形的实际速率。
     *
     * 用 daemon 线程：SystemUI 进程被杀时不该因为这个线程池而拖住。
     */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "fp-root-launch").apply { isDaemon = true }
        }
    }

    /**
     * 已经报过「没有 root」的标记，避免每次点击都刷一条同样的日志。
     *
     * `su` 不可用（没装 root / KernelSU 没授权）是一个**稳定状态**，
     * 不是每次点击的新事件。第一次说清楚就够，之后静默 ——
     * 否则用户点十次就得到十条一样的日志，真正有用的那条被埋掉。
     *
     * 注意它是 `@Volatile`：写在 [executor] 的线程里、读在主线程（[launch] 的早退检查）。
     */
    @Volatile
    private var rootUnavailableLogged = false

    /**
     * 上次弹 toast 的时间戳（毫秒）。**用来给 toast 限流**。
     *
     * 为什么需要：用户点一次扇形失败后会**连点**（这是人的正常反应 ——
     * 「怎么没反应？再点一下」）。每次都弹一条一样的 toast 会连成一片，
     * 反而看不清写了什么。3 秒内最多一条，够读、也不刷屏。
     *
     * 日志那边另有 [rootUnavailableLogged]（**只说一次**，比 toast 更严）——
     * 两者刻度不同是刻意的：toast 是给当下的用户看，日志是给事后排查看。
     */
    @Volatile
    private var lastToastAt = 0L

    /**
     * 以 root 身份启动一个活动。**立刻返回**，结果稍后出（走日志 + toast）。
     *
     * @param context 用于弹 toast。**必须是应用 context**（SystemUI 的），
     *   toast 失败被吞掉、不会往上抛。
     * @return true = 请求已发出（**不代表启动成功**）；false = 请求都没能发出
     *         （校验没过 / 已经确认没 root）
     */
    fun launch(hook: HookContext, context: Context?, pkg: String, activity: String): Boolean {
        // ── ① 构造命令（含字符白名单校验）──
        //
        // 校验在**这一刻**做，不只在「写入配置时」做：配置可能被手改、
        // 被云同步覆盖、或来自旧版本模块。而这里拼出来的是**以 root 执行的**东西，
        // 差一个字符就是命令注入。多校一次的成本是几微秒。
        val plan = ActivityRootCommand.plan(pkg, activity)
        if (plan == null) {
            hook.log(
                Log.WARN,
                "root 路拒绝启动：$pkg/$activity 过不了组件名校验" +
                    "（只允许 Java 标识符字符与 `.`，防的是把用户输入拼进 root 命令）",
            )
            toast(hook, context, "活动名不合法，无法用 root 启动（详见模块日志）")
            return false
        }

        // 已知没有 root：别再起线程白跑一遍（这个状态是稳定的，见 rootUnavailableLogged）。
        // ⚠️ 这里**也要弹 toast** —— 用户点一次没反应，第二次点还是没反应，
        // 而他不知道「上次已经判定过没有 root 了」。早退只省线程，不该省告知。
        if (rootUnavailableLogged) {
            toast(hook, context, "root 不可用：请在 KernelSU 里授权 com.android.systemui")
            return false
        }

        val cmd = ActivityRootCommand.commandOf(plan)
        executor.execute { run(hook, context, cmd, plan.packageName, plan.activityName) }
        return true
    }

    /**
     * 线程池里的实际执行。**这里不是主线程**，所以可以做阻塞 IO。
     *
     * 写成「写 stdin → 关 stdin → 等退出 → 读输出」的顺序，每一步的失败都单独说清：
     *
     *  - 起 `su` 失败（`IOException`）= 设备上没有 `su`（没 root）→ 记一次就够
     *  - 等待超时 = 授权弹窗没人应答（或用户在忙）→ 给出「去 KernelSU 看一眼」的动作
     *  - 退出码非 0 = `su` 明确拒绝/`am` 找不到 → 用 [ActivityRootCommand.explainExit] 的文案
     *  - 输出里有 `Error:` = **命令跑了但目标是坏的**（活动名拼错等）→ 这是最容易漏的一档
     *
     * ⚠️ **这一层里的每一档都配一条 toast**（[toast] 负责切回主线程）。
     * 理由：这个函数跑在后台线程，它的失败**用户看不见** —— `hook.log` 只进 LSPosed 模块日志，
     * 而用户眼里只有「点了没反应」。日志是给事后排查看的，toast 是给当下的用户看的，
     * 两者都要有。toast 的文案刻意**短且可操作**（说「做什么」，不是「哪里错了」）。
     */
    private fun run(hook: HookContext, context: Context?, cmd: String, pkg: String, activity: String) {
        var process: Process? = null
        try {
            process = ProcessBuilder(SU).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            // 设备上没有 su（或 SELinux 拦了）。这是稳定状态，日志只说一次
            rootUnavailableLogged = true
            hook.log(
                Log.WARN,
                "root 路不可用：无法执行 su（$pkg/$activity 这类活动需要 root 才能启动）。" +
                    "若设备有 root，检查 KernelSU 是否已授权 com.android.systemui；" +
                    "本条只提示一次，之后静默",
                t,
            )
            // toast 不受「只说一次」约束（那是日志的规则）：用户每次点都该知道为什么没反应
            toast(hook, context, "root 不可用：请在 KernelSU 里授权 com.android.systemui")
            return
        }

        val output = StringBuilder()
        try {
            // ── 写命令 ──
            // DataOutputStream 而不是 PrintWriter：这里只有 ASCII 命令，不需要编码转换层。
            // 逐行写（命令本身不含换行，见 ActivityRootCommand.commandOf 的测试）。
            DataOutputStream(process.outputStream).use { os ->
                os.write((cmd + "\n").toByteArray(Charsets.UTF_8))
                os.write("exit\n".toByteArray(Charsets.UTF_8))
                os.flush()
            }

            // ── 等退出（带超时）──
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                hook.log(
                    Log.WARN,
                    "root 路超时（${TIMEOUT_SECONDS}s）未返回：$pkg/$activity —— " +
                        "多半是 KernelSU 的授权弹窗在等待确认。打开 KernelSU 授权 " +
                        "com.android.systemui 后重试（只需授权一次）",
                )
                toast(hook, context, "等待 root 授权超时：请到 KernelSU 授权 com.android.systemui 后重试")
                return
            }

            // ── 读输出（**必须在 waitFor 之后**：进程结束前 readText 会阻塞）──
            val text = try {
                process.inputStream.readBytes().toString(Charsets.UTF_8)
            } catch (t: Throwable) {
                ""
            }
            output.append(text)

            val exit = process.exitValue()
            report(hook, context, exit, output.toString(), pkg, activity)
        } catch (t: Throwable) {
            hook.log(Log.WARN, "root 路执行异常：$pkg/$activity —— ${t.javaClass.simpleName}", t)
            toast(hook, context, "root 启动出错：" + t.javaClass.simpleName + "（详见模块日志）")
        } finally {
            // 兜底：任何早退路径都不能漏掉子进程（否则会攒一堆僵尸 su）
            try {
                process?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 退出码 + 输出 → 日志 **+ toast**。**判定的两层都要看**（理由见
     * [ActivityRootCommand.outputIndicatesFailure]）：`am start` 在「活动名拼错」时
     * **退出码仍是 0**，只看退出码会说成功。
     *
     * toast 的文案分两档（这是刻意的，用户要采取的动作不同）：
     *  - **授权类失败**（退出码 1 / 127）→ 指向 KernelSU 授权，那是可操作的下一步；
     *  - **目标类失败**（活动名错、组件不存在）→ 指向核对活动名，改配置即可。
     * 合成一句「启动失败」等于把「往哪改」藏起来。
     */
    private fun report(
        hook: HookContext,
        context: Context?,
        exitCode: Int,
        output: String,
        pkg: String,
        activity: String,
    ) {
        val summary = ActivityRootCommand.summarizeOutput(output)

        if (!ActivityRootCommand.isSuccess(exitCode)) {
            val why = ActivityRootCommand.explainExit(exitCode) ?: "退出码 $exitCode"
            hook.log(
                Log.WARN,
                "root 路失败（退出码 $exitCode）：$pkg/$activity —— $why" +
                    (if (summary.isEmpty()) "" else "；am 输出：$summary"),
            )
            // 1 = su 被拒绝/超时，127 = am 不存在：两者都说明「root 这一层现在不可用」，
            // 记成稳定状态，后续点击不再白起线程（但 toast 每次照弹，见 launch 的注释）
            if (exitCode == 1 || exitCode == 126 || exitCode == 127) rootUnavailableLogged = true
            toast(
                hook,
                context,
                if (exitCode == 1) "root 被拒绝：请在 KernelSU 里授权 com.android.systemui"
                else "root 启动失败（退出码 $exitCode，详见模块日志）",
            )
            return
        }

        // ── 退出码 0，但输出可能是失败 ──
        if (ActivityRootCommand.outputIndicatesFailure(summary)) {
            hook.log(
                Log.WARN,
                "root 路命令跑完了但**没启动起来**：$pkg/$activity —— am 说：$summary。" +
                    "多半是活动全名写错，或该活动已改名（对照 `cmd package resolve-activity --brief $pkg` 复核）",
            )
            toast(hook, context, "没找到这个活动：请核对活动名（详见模块日志）")
            return
        }

        // 走通
        hook.log(
            Log.INFO,
            "第 ${FanShortcuts.LEVEL_ACTIVITY_ROOT} 级（" + FanShortcuts.describeLevel(FanShortcuts.LEVEL_ACTIVITY_ROOT) +
                "）已发出：$pkg/$activity" + (if (summary.isEmpty()) "" else "；am 说：$summary"),
        )
    }

    /** `su` 的可执行名。走 `PATH` 而非绝对路径。 */
    private const val SU = "su"

    /**
     * 两次 toast 之间的最小间隔（毫秒）。理由见 [lastToastAt]。
     *
     * 3 秒是个体感值：短到「换个活动再点一次」也能看到提示，
     * 长到连点同一下不会刷成一串。
     */
    private const val TOAST_INTERVAL_MS = 3000L

    /** toast 必须从主线程弹，而这个类的失败分支都跑在 [executor] 的线程里 —— 靠它切回去。 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 给别的类用的提示入口（目前只有 [FanShortcuts.rootFallback] 的「开关没开」那一档）。
     *
     * 存在的理由是**收口**：toast 的限流、切主线程、失败吞掉这套规矩只该有一份实现。
     * 让调用方自己 `Toast.makeText` 就等于在别处复制一份，而复制出来的那份
     * 迟早会漏掉其中一条（最可能漏掉的是限流 —— 漏了也不报错，只是刷屏）。
     */
    fun notice(hook: HookContext, context: Context?, msg: String) = toast(hook, context, msg)

    /**
     * 弹一条提示。**这是这一层唯一"给用户看"的出口**（其余全是 `hook.log`，只进模块日志）。
     *
     * 三件事：
     *
     *  1. **切主线程**：调用方都在后台线程，而 `Toast.show()` 从后台线程调有风险
     *     （依赖进程有一个可用的 Looper，SystemUI 里有但不应假设）。
     *     `Looper.getMainLooper()` 一定存在，所以 [mainHandler] 是安全的锚点。
     *  2. **限流**：[TOAST_INTERVAL_MS] 内最多一条。用户点一次没反应会**连点**，
     *     每次都弹会连成一片看不清内容。
     *  3. **失败吞掉**：弹 toast 失败（context 已死、SystemUI 正在重启等）不值得往上抛 ——
     *     抛出去会污染「启动失败」这条主线索（同 `PortalImageActions.toast` 的做法）。
     *
     * `context` 为 null 时直接跳过（调用方拿不到 context 的路径不该因此崩）。
     */
    private fun toast(hook: HookContext, context: Context?, msg: String) {
        if (context == null) return
        val now = System.currentTimeMillis()
        if (now - lastToastAt < TOAST_INTERVAL_MS) return
        lastToastAt = now
        try {
            mainHandler.post {
                try {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                    // 弹 toast 失败不往上抛（见上）
                }
            }
        } catch (t: Throwable) {
            hook.log(Log.WARN, "投递 toast 失败：" + t.javaClass.simpleName)
        }
    }

    /**
     * 测试与诊断用：root 路是否已被判定为不可用。
     *
     * 只有 [launch] 的早退与这里读标记的逻辑需要它 ——
     * 正常路径不需要"问一句能不能 root"，那是启动时才该付的成本。
     */
    fun isKnownUnavailable(): Boolean = rootUnavailableLogged

    /** 诊断用：重置「已知不可用」标记（用户在 KernelSU 里授权完可调一次，不必重启 SystemUI）。 */
    fun resetAvailability() {
        rootUnavailableLogged = false
    }
}
