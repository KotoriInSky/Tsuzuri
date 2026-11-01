package io.github.kotoriinsky.tsuzuri.hook.feature.gesture

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect
import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry

/**
 * SystemUI 进程里的环境获取。
 *
 * 手势特性必须在 SystemUI 里跑，原因不只是「那里有窗口权限」：
 * MIUI 对第三方应用调小窗有闸门（`MiuiMultiWindowUtils.checkAuthority`），
 * 而它对 **system uid 直接放行**。寄生 SystemUI = 拿到 system uid = 绕过闸门。
 * 这是本项目的一个通用手段，凡是 MIUI 用 checkAuthority 之类拦第三方的地方都能用同一招。
 */
object SystemUiEnv {

    private const val TAG = "FP-SysUiEnv"

    @Volatile
    private var cached: Context? = null

    /**
     * 拿一个 SystemUI 进程的 Context。
     *
     * 优先用 `ActivityThread.currentApplication()` —— 它在任何应用进程里都成立，
     * 不依赖任何类的加载顺序。SystemUI 的 Context 没有 Activity 依赖，
     * 用来查 PackageManager / 启 Activity 都够。
     *
     * 失败返回 null，调用方**必须**按「没有 Context 就不做事」处理 ——
     * 这里拿不到 Context 说明进程环境和我们预期的不一样，硬来只会崩宿主。
     */
    fun context(): Context? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = try {
                val at = Class.forName("android.app.ActivityThread")
                val app = at.getMethod("currentApplication").invoke(null)
                app as? Context
            } catch (t: Throwable) {
                null
            }
            cached = ctx
            return ctx
        }
    }

    /**
     * 前台应用的包名。
     *
     * 用 `getTopVisibleTask(new int[]{1}, ...)`，理由（都是设备实测得来）：
     *  - `getRunningTasks()` 在新版本上只能看到自己的任务，拿不到别人的。
     *  - `getFocusedRootTaskInfo()` 服务端有 `enforceTaskPermission(MANAGE_ACTIVITY_TASKS)` 权限门。
     *    SystemUI 恰好持有该权限，能用；但多一层依赖不如少一层。
     *  - `getTopVisibleTask` **没有权限检查**，而且本 ROM 的 WMShell 自己就在用它
     *    （`MulWinSwitchGestureHandler.startFreeformSwitch`），说明这条路在本机是活的。
     *
     * 第一个参数 `{1}` 是 windowingMode 过滤，1 = FULLSCREEN —— 只取全屏任务，
     * 这样小窗/分屏在前台时不会被误判成「当前应用」。
     */
    fun foregroundPackage(): String? {
        val task = runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager")
            val service = atm.getMethod("getService").invoke(null)
            val m = service.javaClass.getMethod(
                "getTopVisibleTask",
                IntArray::class.java,
                IntArray::class.java,
                Array<String>::class.java,
                Array<String>::class.java,
            )
            m.invoke(service, intArrayOf(WINDOWING_MODE_FULLSCREEN), null, null, null)
        }.getOrNull() ?: return null

        // MIUI 这条路径只保证 baseActivity 非空；topActivity 是否被填充没有实测到，
        // 所以优先 topActivity，退化用 baseActivity。
        for (field in arrayOf("topActivity", "baseActivity")) {
            val name = runCatching {
                val f = task.javaClass.getField(field)
                (f.get(task) as? android.content.ComponentName)?.packageName
            }.getOrNull()
            if (!name.isNullOrEmpty()) return name
        }
        return null
    }

    /**
     * 系统的小窗窗口模式。
     *
     * AOSP 公开常量 `WINDOWING_MODE_FREEFORM = 5`。设备实测确认：
     * `am start --windowingMode 5` 起出来的任务在 dumpsys 里是 `mode=freeform`
     * （对照：1 = fullscreen、6 = multi-window）。注意别和 MIUI 专属的
     * `WINDOWING_MODE_HOVER = 101` 搞混。
     */
    private const val WINDOWING_MODE_FREEFORM = 5

    /** 只取全屏任务。 */
    private const val WINDOWING_MODE_FULLSCREEN = 1

    /**
     * 以小窗方式启动某个包。
     *
     * 走三级候选，从「最懂 MIUI」到「最保守」：
     *
     *  1. `miui.app.MiuiFreeFormManager.getActivityOptions(ctx, pkg, true, false)`
     *     —— MIUI 自己的入口，会算好位置、避让区、圆角等。**首选**。
     *  2. `android.util.MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true, false)`
     *     —— 上一层的实际实现，已从 miui-framework.jar 的 dex 里确认存在。
     *  3. `ActivityOptions.makeBasic().setLaunchWindowingMode(5)` + 自算 bounds
     *     —— 完全公开 API。MIUI 可能因为「未授权」而拒绝，所以只当兜底。
     *
     * 全程不抛异常：任何一级失败就降级，全失败返回 false 并让调用方记日志。
     *
     * ## 自定义摆放（`window_preset` + 四个 `window_*`）
     *
     * 只要算出了矩形，**每一级拿到 options 之后都会把 bounds 覆写成那一个**
     * （第 1/2 级也会 —— MIUI 自己算的 bounds 被覆盖掉）。矩形由契约层唯一的
     * [WindowBounds.resolvePreset] 算出来，与设置界面的预览**是同一个函数**
     * （两份实现必然漂移）。
     *
     * **选「系统默认」时一行都不走**：那个档 `resolve` 回 `bounds = null`，
     * 后面每一级的代码与加这个功能之前**逐位一致**。详见 [WindowPlacement]。
     *
     * @return 实际走通的那一级编号（1/2/3），全失败返回 0
     */
    fun launchInSmallWindow(
        hook: HookContext,
        context: Context,
        pkg: String,
    ): Int {
        val intent = context.packageManager?.getLaunchIntentForPackage(pkg) ?: run {
            hook.log(Log.WARN, "没有 $pkg 的启动 Intent，放弃")
            return 0
        }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        // 摆放决策**在进循环之前算一次**（五个键读一次、屏幕尺寸量一次），
        // 三级都用同一个矩形 —— 每级各算一次会出现「前两级一个位置、兜底级另一个位置」。
        val placement = WindowPlacement.resolve(context)
        hook.log(Log.INFO, "小窗摆放：${placement.desc}")
        Log.i(TAG, "小窗摆放：$pkg ${placement.desc}")

        for (level in 1..3) {
            val options = when (level) {
                1 -> miuiOptions(hook, context, pkg, "miui.app.MiuiFreeFormManager")
                2 -> miuiOptions(hook, context, pkg, "android.util.MiuiMultiWindowUtils")
                else -> publicOptions(context)
            } ?: continue

            // **每一级都设**：强制这次启动新开一条 Task（2026-09-24 新增，理由见
            // [forceLaunchNewTask]）。没有它，目标界面会被复用进该应用已有的全屏 Task
            // —— 实测（Xiaomi 17 Pro，2026-09-24）：微信全屏时从小窗点公众号页面，
            // Activity 全部合并进微信唯一的 fullscreen Task，全系统 mode=freeform 计数 = 0。
            forceLaunchNewTask(hook, options)

            // 只有开着时才碰 options（关态 = 今天的行为，一个字节都不差）
            //
            // ⚠️ 交出去的**不是**用户配的那块矩形，而是**尺寸除以 freeformScale** 之后的
            // 「逻辑矩形」：MIUI 拿到之后会把尺寸乘回 scale（实测 0.7，锚点左上角），
            // 不除的话窗口在屏幕上只有配置值的 70%、中心还会偏向左上。
            // 实测数据与推导写在 [WindowBounds.compensateForScale] 的注释里。
            val scale = freeformScaleOf(options)
            val logical = WindowPlacement.withFreeformScale(placement.bounds, scale)
            val applied = logical?.let { applyCustomBounds(options, it) }

            try {
                context.startActivity(intent, options.toBundle())
                if (logical != null) {
                    logPlacementApplied(hook, level, placement, logical, applied, scale)
                }
                return level
            } catch (t: Throwable) {
                hook.log(Log.WARN, "第 $level 级启动失败，往下试", t)
            }
        }
        return 0
    }

    /**
     * 把我们的矩形写进 `options`。
     *
     * `setLaunchBounds` 是 `@UnsupportedAppUsage`（公开 SDK 里没有这个方法），只能反射 ——
     * 与 [publicOptions] 里那一句用的是同一个方法名。
     *
     * **失败不放弃这一级**：保留 MIUI 自己算的摆放照常启动。宁可位置/尺寸不是用户配的，
     * 也不要「划了没反应」—— 后者用户会当成整个手势坏了，而前者至少功能是通的，
     * 而且日志里有 WARN 可查。
     *
     * @return true = 覆写成功（这一次小窗的摆放由我们决定），false = 方法不可用
     */
    private fun applyCustomBounds(options: ActivityOptions, bounds: Rect): Boolean = runCatching {
        ActivityOptions::class.java
            .getMethod("setLaunchBounds", Rect::class.java)
            .invoke(options, bounds)
    }.isSuccess

    /**
     * 把「扇形小窗摆放」应用到一个已构造好的 options 上（含 freeformScale 补偿）。
     *
     * 摆放规则只有一份（[WindowPlacement]：`window_preset` + 四个百分比 + 尺寸补偿），
     * 应用项（[launchInSmallWindow]）与快捷方式直连（`FanShortcuts` 的直连映射分支）
     * 都必须走这里 —— 否则两条入口弹出的小窗尺寸不一致，而用户眼里那是同一个功能
     * （「扇形小窗的设置」应该对所有小窗生效，不是只对应用项生效）。
     *
     * 与 [launchInSmallWindow] 内联版本的行为差异只有日志：这里不带级别编号。
     *
     * @return 实际交出去的逻辑矩形（null = 未覆写：系统档 / 矩形不可用，交给 MIUI）
     */
    internal fun applyFanPlacement(
        hook: HookContext,
        context: Context,
        options: ActivityOptions,
    ): Rect? = applyPlacement(hook, options, WindowPlacement.resolve(context))

    internal fun applyPasscodePlacement(
        hook: HookContext,
        context: Context,
        options: ActivityOptions,
        preset: String,
    ): Rect? = applyPlacement(hook, options, WindowPlacement.resolve(context, preset))

    private fun applyPlacement(hook: HookContext, options: ActivityOptions, placement: WindowPlacement.Resolved): Rect? {
        if (placement.bounds == null) {
            hook.log(Log.INFO, "直连小窗摆放：${placement.desc} → 不覆写，交给 MIUI")
            return null
        }
        val scale = freeformScaleOf(options)
        val logical = WindowPlacement.withFreeformScale(placement.bounds, scale)
        if (logical == null) {
            hook.log(Log.INFO, "直连小窗摆放：${placement.desc} → 补偿后矩形不可用，交给 MIUI")
            return null
        }
        val applied = applyCustomBounds(options, logical)
        if (applied) {
            hook.log(
                Log.INFO,
                "直连小窗摆放：已覆写 bounds=${fmtRect(logical)}" +
                    (if (scale != null && scale != 1f) "（freeformScale=$scale 已补偿）" else ""),
            )
        } else {
            hook.log(
                Log.WARN,
                "直连小窗摆放：setLaunchBounds 不可用（本来要写 ${fmtRect(logical)}），保留 MIUI 自己的摆放",
            )
        }
        return logical
    }

    /**
     * `ActivityOptions.setForceLaunchNewTask()` —— 强制这次启动**新开一条 Task**，
     * 不让目标界面被复用进该应用已有的全屏 Task。
     *
     * ## 为什么必须有它（2026-09-24 实测 + 逆向）
     *
     * 现象（Xiaomi 17 Pro）：微信全屏时从小窗入口点公众号页面，Activity **全部合并进
     * 微信已有的全屏 Task**（`dumpsys`：微信唯一一条 `mode=fullscreen` 的 Task，sz=3；
     * 全系统 `mode=freeform` 计数 = 0）。
     *
     * 根因（`research/decompiled/miui-services/` 的 `ActivityStarterImpl`）：
     * system_server 有三处只看同一个组合条件
     * `getLaunchWindowingMode()==5 && getForceLaunchNewTask()`：
     *
     *  - `interceptComputeTargetTask()`（:1675）→ true = **拦截**「找可复用 Task」；
     *  - `resolveReusableTask()`（:1685）→ `Pair(true, null)` = 要新建、不复用；
     *  - `MiuiFreeFormManagerService`（:3496）→ 早退，不塞进已有全屏 Task。
     *
     * 不设它，`resolveReusableTask` 走复用分支 → Activity 塞回原 Task（合并形态）。
     * 这也是「启动后 windowingMode != 5 就重试」那条事后兜底存在的根本原因 ——
     * 源头在复用决策，不在启动失败。
     *
     * HyperOS 自己就在用这个开关：小窗标题栏菜单的「在新窗口打开」
     * （`MiuiCaptionClickListener:200/244`）、`SoScSplitScreenController:570`、
     * `MultipleSplitUIController:228` —— 全在 WMShell（= 本进程，SystemUI）。
     * 官方代码经它跨进程传给 system_server，说明 `toBundle()` 必然序列化该字段。
     *
     * ## 失败策略：不放弃这一级
     *
     * 与 [applyCustomBounds] 同理：宁可这次启动退回合并形态（功能仍可用），
     * 也不要「划了没反应」。成败各记一条 —— 对比实验靠这两行 + dumpsys 说话。
     *
     * @return true = 已设上；false = 方法不可用（本次启动可能合并进已有 Task）
     *
     * `internal` 而非 private：**四个消费点共用这一份实现** —— 本类的 1/2/3 级，
     * 以及 [io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.FanShortcuts] 的
     * 4/5（快捷方式）与 9/10（活动）、
     * [io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.FanLinks] 的 6/7（链接）。
     * 反射与方法语义只该有一份，别处要用就调这里。
     */
    internal fun forceLaunchNewTask(hook: HookContext, options: ActivityOptions): Boolean =
        runCatching {
            options.javaClass.getMethod("setForceLaunchNewTask").invoke(options)
        }.fold(
            onSuccess = {
                hook.log(Log.INFO, "已设 forceLaunchNewTask（新开 Task，不复用已有全屏 Task）")
                true
            },
            onFailure = { t ->
                hook.log(
                    Log.WARN,
                    "setForceLaunchNewTask 不可用：${t.javaClass.simpleName}" +
                        "（本次启动可能合并进该应用已有的全屏 Task）",
                    t,
                )
                false
            },
        )

    /**
     * 第 N 级启动成功之后，把「这一级到底用了谁的摆放」记下来。
     *
     * 真机验证靠的就是这一行 + [verifyFreeformLaunch] 那行（后者打的是**实际** bounds）：
     * 把这两行放在一起看，才能区分「我们算错了」「MIUI 又按自己的算法改了」
     * 「MIUI 乘了 freeformScale」。
     */
    private fun logPlacementApplied(
        hook: HookContext,
        level: Int,
        placement: WindowPlacement.Resolved,
        logical: Rect,
        applied: Boolean?,
        scale: Float?,
    ) {
        // 三个矩形都打出来：**配置值**（用户拖出来的）、**交出去的值**（补偿后）、
        // 以及 [verifyFreeformLaunch] 那行打的**实际值**。少任何一个，
        // 「实际比配置小一截」就只能靠猜是补偿错了、还是 MIUI 又改了。
        val desired = placement.bounds?.let { fmtRect(it) } ?: "?"
        val compensated = if (scale != null && scale != 1f) {
            "（配置 $desired，已按 freeformScale=$scale 补偿）"
        } else {
            ""
        }
        if (applied == true) {
            hook.log(Log.INFO, "小窗摆放：第 $level 级已覆写 bounds=${fmtRect(logical)}$compensated")
        } else {
            hook.log(
                Log.WARN,
                "小窗摆放：第 $level 级 setLaunchBounds 不可用（本来要写 ${fmtRect(logical)}$compensated），" +
                    "保留 MIUI 自己的摆放",
            )
        }
    }

    /** 矩形 → 日志用的 `[l,t][r,b] WxH`；`null` → `?`（自检那条线上任务可能还没出现）。 */
    private fun fmtRect(r: Rect?): String = if (r == null) {
        "?"
    } else {
        "[" + r.left + "," + r.top + "][" + r.right + "," + r.bottom + "] " +
            r.width() + "x" + r.height()
    }

    /**
     * 探测 MIUI 给小窗乘的那个缩放（`ActivityOptionsInjector.mFreeformScale`），**只用于日志**。
     *
     * 参考实现读它来决定「bounds 要不要除以 scale」（`HyperOsFreeformBridge.readFreeformScale`，
     * 反编译产物在 `research/refapps/com.oxohang.fanfreeform/`）；我们**不参与计算**，
     * 理由见 [WindowPlacement] 的注释。留这一行的目的只有一个：
     * 真机验证时「实际 bounds 比请求的小一截」是不是它造成的，要靠这个数判断。
     *
     * 拿不到就返回 null —— 那不是错误（非 MIUI 的 options 上本来就没有这个注入器），
     * 所以**不记日志**。
     */
    private fun freeformScaleOf(options: ActivityOptions): Float? = try {
        val injector = options.javaClass
            .getMethod("getActivityOptionsInjector")
            .invoke(options) ?: return null
        try {
            injector.javaClass.getField("mFreeformScale").getFloat(injector)
        } catch (_: Throwable) {
            injector.javaClass.getMethod("getFreeformScale").invoke(injector) as? Float
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * `window_preset` + 四个 `window_*`：**这一次小窗摆在哪**。
     *
     * ## 放在哪一级（为什么不干脆只走公开 API 那一级）
     *
     * 三级候选里，第 1/2 级（`MiuiFreeFormManager` / `MiuiMultiWindowUtils`）**自己会算 bounds**，
     * 所以「用我们的矩形」只有两条路：
     *
     *  1. 拿到 options **之后**覆写 `setLaunchBounds(我们的 Rect)`；
     *  2. 不去调第 1/2 级，直接走公开 API 的 `makeBasic().setLaunchWindowingMode(5)`。
     *
     * **这里选 1**，理由是参考实现走的正是这条路且在本机验证过可用：
     * `HyperOsFreeformBridge.launchIntentNow`（`HyperOsFreeformBridge.java:288-306`）先调
     * `MiuiFreeFormManager.getActivityOptions(ctx, pkg, true, false)`，再
     * `options.setLaunchBounds(自定义 Rect)`。而方案 2 的代价是**丢掉 MIUI 塞进 options 的
     * 那一堆东西**（避让区、圆角、freeformScale、`setForceLaunchNewTask`…）——
     * 我们只想换「摆在哪、多大」，不想连「小窗长什么样」一起换掉。
     *
     * 所以三个级别**统一在拿到 options 之后覆写**（[applyCustomBounds]），而不是某一级特殊化。
     *
     * ## bounds 的单位：交出去的是**补偿过**的矩形（尺寸 ÷ freeformScale）
     *
     * `setLaunchBounds` 收的矩形在 MIUI 上会被**乘一个 `freeformScale`**（小窗缩放，
     * 从 `ActivityOptionsInjector.mFreeformScale` 读）。也就是实际窗口 = 我们给的矩形 × scale。
     *
     * 这一点**从"猜"变成了"量到"**（2026-09-17，Xiaomi 17 Pro，scale = 0.7）：
     *
     * ```
     * 交出去  [122,487][1098,2174]  976x1686
     * 屏幕上  [122,487][ 805,1668]  683x1181     ← 宽高正好 ×0.7，原点不动
     * ```
     *
     * 所以现在**除以**它（[WindowBounds.compensateForScale]）。从前不除的理由是
     * 「万一 MIUI 没乘，除完会放大 1/scale 倍、右下角溢出屏幕」—— 那个可能性被实测排除了：
     * 而且 scale 读的就是 MIUI 接下来要用的那个值，不是兜底常量。
     * 读不到（非 MIUI 的 options）或值越界时**不补偿**，退回从前的行为。
     *
     * 补偿只动**尺寸**、不动原点 —— 实测 MIUI 的缩放锚点就是左上角。
     *
     * ## 屏幕尺寸取哪一个
     *
     * 取**当前显示的实际尺寸**：`WindowManager.getCurrentWindowMetrics().bounds`，
     * 拿不到才退 `resources.displayMetrics`（与 `CornerSmallWindow.displayMetricsOf`
     * 同一口径；那个函数是 private 的调不到，所以这里是第二份取法 —— 但**几何仍然只有一份**，
     * 也就是 [WindowBounds.resolvePreset]，这里只负责「屏幕多大」这一个输入）。
     *
     * 旋转 / 折叠展开后拿到的是**当下**那一块屏的尺寸，不是某个启动时的静态值；
     * 日志里把选到的值与来源一起打出来（`scr=1220x2656(bounds)`），复算的人不用猜。
     *
     * ## 三档预设
     *
     * `window_preset` 一个键定档（2026-09-19 起，之前是一个布尔 `window_custom`）：
     *
     * | 取值 | 行为 |
     * | --- | --- |
     * | `system` | **立刻返回** `bounds = null`（四个滑杆一个都不读），交给 MIUI 自己摆 |
     * | `flyme`  | 用四个**实测值**（80 / 63.5 / 50 / 41.7），= Flyme 那块窗口 |
     * | `custom` | 读四个滑杆，按用户拖出来的值摆 |
     *
     * 三档的判别**不在这里**：它是 [WindowBounds.resolvePreset] 一个函数，
     * 预览侧读的是同一个 —— 上一版这条判别散在 hook 与预览两处，
     * 语义一变就要同时改两处（漏一处 = 预览画 Flyme 那块、真机摆系统那块）。
     *
     * 默认档是 `flyme`（`WindowBounds.DEFAULT_PRESET`）。键**没声明**
     * （Catalog 被另一个协作者改掉了 / 键名写错）或读到认不出的取值时也走这个默认，
     * 并把 `(未声明,用默认值)` / 原值写进日志 —— 静默用默认值是这个项目点名要避免的一类偏差。
     */
    private object WindowPlacement {

        /** `Catalog` 里的参数名（特性前缀由 [Keys.pref] 补，不手写字面量）。 */
        const val PRESET_NAME = "window_preset"
        const val WIDTH_NAME = "window_width_percent"
        const val HEIGHT_NAME = "window_height_percent"
        const val X_NAME = "window_x_percent"
        const val Y_NAME = "window_y_percent"

        /**
         * 键**没声明**时用的档。
         *
         * 必须与 `Catalog` 里 `window_preset` 的 default 一致（现在是 `flyme`）。
         * 不一致的后果很具体：Catalog 被改坏 / 键没声明时，界面按「Flyme」显示、
         * 真机按「系统」摆放 —— 用户看到的是一个永远不生效的单选。
         *
         * 取值**不在这里写**：唯一来源是 [WindowBounds.DEFAULT_PRESET]（`Catalog` 也引用它）。
         * 从前这里原样抄了四个百分比常量（注释写着「抄的是 Catalog.kt」）——
         * 那种"两份字面量靠注释保持一致"的约定一旦漂移，表现是
         * 「设置界面显示 A、真机按 B 摆」，编译、日志、装机全都正常。
         */
        const val DEFAULT_PRESET = WindowBounds.DEFAULT_PRESET
        const val DEFAULT_WIDTH_PERCENT = WindowBounds.DEFAULT_WIDTH_PERCENT
        const val DEFAULT_HEIGHT_PERCENT = WindowBounds.DEFAULT_HEIGHT_PERCENT
        const val DEFAULT_X_PERCENT = WindowBounds.DEFAULT_X_PERCENT
        const val DEFAULT_Y_PERCENT = WindowBounds.DEFAULT_Y_PERCENT

        /** 一次解析的结果。[bounds] 为 `null` = **不覆写**（系统档 / 键没声明 / 矩形不可用）。 */
        class Resolved(val bounds: Rect?, val desc: String)

        /** 屏幕尺寸 + 来源标记（进日志用，见类注释）。 */
        private class Screen(val w: Float, val h: Float, val desc: String)

        /**
         * 读一次配置 + 算一次矩形。**主线程**（`CornerSmallWindow.onCommit` 的回调链）。
         *
         * 每次启动都重读：这几个是「摆放」类参数，用户可能连着调几档再划一次，
         * 所以不能缓存（规范 §9.1 的手感类参数要求）。
         *
         * ## 读几个键**由档位决定**（不是「全都读了再挑」）
         *
         * `system` 档下四个滑杆**一个都不读**：它们在这个档位不参与运算，读了只会让
         * 日志里出现一堆与本次摆放无关的数，而排障的人分不清哪个数真的进了公式。
         * `flyme` 档同理 —— 它用的是常量，读滑杆还会让人以为「拖滑杆能改 Flyme 预设」。
         */
        fun resolve(context: Context, presetOverride: String? = null): Resolved {
            val undeclared = ArrayList<String>(2)
            val preset = presetOverride ?: readChoice(PRESET_NAME, DEFAULT_PRESET, undeclared)
            val screen = screenOf(context)

            // `system`：不覆写，交给 MIUI。**连屏幕尺寸都不用算了**（提前返回）
            if (preset == WindowBounds.PRESET_SYSTEM) {
                return Resolved(null, "window_preset=system${undeclaredNote(undeclared)}（交给 MIUI 摆放）")
            }
            if (preset == WindowBounds.PRESET_FLYME) {
                // 实测值那一档：`resolvePreset` 内部走 `defaults()`。
                // 日志里把那块矩形打出来（`describe` 要四个入参，这里正好就是那四个默认值）
                val rect = WindowBounds.resolvePreset(
                    preset, screen.w, screen.h,
                    WindowBounds.DEFAULT_WIDTH_PERCENT,
                    WindowBounds.DEFAULT_HEIGHT_PERCENT,
                    WindowBounds.DEFAULT_X_PERCENT,
                    WindowBounds.DEFAULT_Y_PERCENT,
                )
                // `flyme` 档的矩形来自常量，理论上永远可用；仍然过一遍 `isUsable`
                // 是因为「屏幕尺寸读到 0」时它会退化成空矩形（`screenOf` 拿不到
                // WindowMetrics 又拿不到 displayMetrics 的极端情况），
                // 那时不覆写比交一个空矩形出去好。
                if (rect == null || !WindowBounds.isUsable(rect)) {
                    return Resolved(null, "window_preset=flyme 但矩形不可用（${screen.desc}）→ 不覆写，交给 MIUI")
                }
                val detail = describeRect(
                    screen,
                    WindowBounds.DEFAULT_WIDTH_PERCENT,
                    WindowBounds.DEFAULT_HEIGHT_PERCENT,
                    WindowBounds.DEFAULT_X_PERCENT,
                    WindowBounds.DEFAULT_Y_PERCENT,
                    rect,
                ) + undeclaredNote(undeclared)
                val suffix = if (WindowBounds.isLandscape(screen.w, screen.h)) {
                    "（横屏：保持竖屏形状，位置由实测常量定）"
                } else {
                    "（x/y 为中心位置）"
                }
                return Resolved(toRect(rect), "window_preset=flyme（Flyme 实测值）$detail$suffix")
            }

            // `custom`（或读到认不出的取值 → `resolvePreset` 会按默认档处理，
            // 但那不该悄悄发生：认不出的值一并收进 `undeclared`，日志里看得见）
            if (preset !in WindowBounds.PRESETS) {
                undeclared.add("$PRESET_NAME=$preset")
            }
            val widthPercent = readSlider(WIDTH_NAME, DEFAULT_WIDTH_PERCENT, undeclared)
            val heightPercent = readSlider(HEIGHT_NAME, DEFAULT_HEIGHT_PERCENT, undeclared)
            val xPercent = readSlider(X_NAME, DEFAULT_X_PERCENT, undeclared)
            val yPercent = readSlider(Y_NAME, DEFAULT_Y_PERCENT, undeclared)

            val rect = WindowBounds.resolvePreset(
                preset, screen.w, screen.h,
                widthPercent, heightPercent, xPercent, yPercent,
            )
            val detail = describeRect(
                screen, widthPercent, heightPercent, xPercent, yPercent,
                rect ?: WindowBounds.EMPTY,
            ) + undeclaredNote(undeclared)

            if (rect == null || !WindowBounds.isUsable(rect)) {
                // 不可用 = 不覆写。**不夹到最小值再用**：那会让窗口跳到用户没配过的尺寸上，
                // 而预览画的还是用户配的那个（又变成「预览说谎」，见规范 §9.0 第 1 条）
                return Resolved(null, "window_preset=$preset 但矩形不可用（$detail）→ 不覆写，交给 MIUI")
            }
            // 日志里点明语义：同样的 `x%=50` 在 v1 是「左边缘落在半屏」，
            // 在 v2 是「窗口居中」。不写这一句，翻旧日志排障的人会把两版读成同一个意思。
            val suffix = if (WindowBounds.isLandscape(screen.w, screen.h)) {
                "（横屏：位置由实测常量定）"
            } else {
                "（x/y 为中心位置）"
            }
            return Resolved(toRect(rect), "window_preset=$preset $detail$suffix")
        }

        /**
         * 一句话摘要，**按朝向换一套字段**。
         *
         * 横屏下 `x%` / `y%` **不进公式**（位置由 [WindowBounds] 的实测常量定，见
         * [WindowBounds.landscape]），把它们打出来等于在日志里编两个数 ——
         * 排障的人会拿去复算，然后算不对。
         */
        private fun describeRect(
            screen: Screen,
            widthPercent: Float,
            heightPercent: Float,
            xPercent: Float,
            yPercent: Float,
            rect: ScreenRect,
        ): String = if (WindowBounds.isLandscape(screen.w, screen.h)) {
            screen.desc + " " + WindowBounds.describeLandscape(widthPercent, heightPercent, rect)
        } else {
            screen.desc + " " + WindowBounds.describe(
                widthPercent, heightPercent, xPercent, yPercent, rect,
            )
        }

        /**
         * 读一个 `Choice`（三档预设）。
         *
         * ⚠️ **不按 `Catalog` 的 options 夹取**，与 [readSlider] 的处置不同：
         * 滑杆的 min/max 是量程（超出量程的值在几何上无意义，夹取是修复），
         * 而单选的 options 是**取值集合** —— 认不出的取值绝不能「就近夹到某一档」，
         * 那会静默地把用户的配置解释成另一个意思；正确处置是原样带出去，
         * 由 [WindowBounds.resolvePreset] 按默认档兜底，并在日志里点名。
         */
        private fun readChoice(name: String, default: String, undeclared: MutableList<String>): String {
            val spec = findSpec<PrefSpec.Choice>(name)
            if (spec == null) {
                undeclared.add(name)
                return default
            }
            return runCatching { HookRegistry.prefs().value(spec) }.getOrDefault(default)
        }

        /** 读一个 Slider，语义与 [readChoice] 相同（夹取按 `Catalog` 的 min/max）。 */
        private fun readSlider(name: String, default: Float, undeclared: MutableList<String>): Float {
            val spec = findSpec<PrefSpec.Slider>(name)
            if (spec == null) {
                undeclared.add(name)
                return default
            }
            return runCatching {
                HookRegistry.prefs().value(spec).coerceIn(spec.min, spec.max)
            }.getOrDefault(default)
        }

        /** 在 `corner_small_window` 的声明里找这个键（键名用 [Keys.pref] 拼，不手写字面量）。 */
        private inline fun <reified T : PrefSpec> findSpec(name: String): T? {
            val key = Keys.pref(Catalog.CORNER_SMALL_WINDOW, name)
            return Catalog.find(Catalog.CORNER_SMALL_WINDOW)
                ?.prefs
                ?.filterIsInstance<T>()
                ?.firstOrNull { it.key == key }
        }

        /** `(未声明,用默认值)` —— 与几何日志同一个记号，grep 得到。 */
        private fun undeclaredNote(undeclared: List<String>): String =
            if (undeclared.isEmpty()) "" else " (未声明,用默认值:" + undeclared.joinToString("|") + ")"

        /** 屏幕的**真实**尺寸（宽、高，px）+ 来源标记。见类注释最后一段。 */
        private fun screenOf(context: Context): Screen {
            val dm = context.resources.displayMetrics
            val bounds = runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                val b = wm?.currentWindowMetrics?.bounds
                if (b != null && b.width() > 0 && b.height() > 0) b else null
            }.getOrNull()
            return if (bounds != null) {
                Screen(
                    bounds.width().toFloat(),
                    bounds.height().toFloat(),
                    "scr=" + bounds.width() + "x" + bounds.height() + "(bounds)",
                )
            } else {
                Screen(
                    dm.widthPixels.toFloat(),
                    dm.heightPixels.toFloat(),
                    "scr=" + dm.widthPixels + "x" + dm.heightPixels + "(dm)",
                )
            }
        }

        /**
         * [ScreenRect] → `android.graphics.Rect`。
         *
         * 用 `toInt()`（截断）而不是四舍五入：`left/top` 已经 ≥ 0、`right/bottom` 已经 ≤ 屏幕，
         * 截断只会让矩形**变小**，绝不会因为进位多出 1px 跑到屏幕外 —— 方向是保守的那一边。
         */
        private fun toRect(r: ScreenRect): Rect =
            Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())

        /** `android.graphics.Rect` → [ScreenRect]。与 [toRect] 是同一个往返，
         *  只做类型搬运 —— 几何计算仍然全部在 core 里（一份实现）。 */
        private fun toScreenRect(r: Rect): ScreenRect =
            ScreenRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())

        /**
         * 「配置的那块矩形」→「交给 MIUI 的矩形」：补偿它的 freeformScale。
         *
         * 为什么放在这一层而不是调用方：类型转换（`Rect` ↔ `ScreenRect`）这里已经有了，
         * 而且这确实是「摆放」的一部分 —— 几何公式本身仍然只有 `core` 一份
         * （[WindowBounds.compensateForScale]），这里只负责搬运。
         *
         * `scale` 读不到（非 MIUI 的 options）或越界时**原样返回**，退回不做补偿的行为。
         */
        fun withFreeformScale(bounds: Rect?, scale: Float?): Rect? =
            bounds?.let { toRect(WindowBounds.compensateForScale(toScreenRect(it), scale)) }

    }

    /** MIUI 私有入口的调用：`getActivityOptions(Context, String, boolean, boolean)`。 */
    private fun miuiOptions(
        hook: HookContext,
        context: Context,
        pkg: String,
        className: String,
    ): ActivityOptions? = try {
        val cls = Class.forName(className, false, context.classLoader)
        val m = cls.getMethod(
            "getActivityOptions",
            Context::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        // 参数语义（jadx 反编译 MiuiFreeFormManager / MiuiMultiWindowUtils 确认）：
        //   第 3 个 = noCheck、第 4 个 = isMiniFreeformMode
        //
        // **noCheck 必须为 true**，这不是可调项：MIUI 的闸门在
        // `MiuiMultiWindowUtils.supportFreeFromWindow` ——
        //   return (ctx != null && "com.android.systemui".equals(ctx.getPackageName())
        //           && sAppList.get(pkg) == null) ? false : true;
        // `sAppList` 是硬编码小名单（微信 / QQ / WhatsApp / 飞书 / 钉钉 / 短信）。
        // 也就是说：**从 SystemUI 调用且目标不在那个名单里时，getActivityOptions 直接返回 null**。
        // 传 noCheck=true 才绕过它。
        //
        // 顺带纠正一个流传的说法：所谓「闸门是 checkAuthority / 第三方白名单」并不准确 ——
        // checkAuthority 对 system uid（SystemUI 是 1000）无条件放行，它根本不是障碍。
        m.invoke(null, context, pkg, true, false) as? ActivityOptions
    } catch (t: Throwable) {
        hook.log(Log.INFO, "$className 不可用：${t.javaClass.simpleName}")
        null
    }

    /**
     * 纯公开 API 的兜底：自由窗口模式 + 自算一个「小窗尺寸」的 bounds。
     *
     * 尺寸按屏幕短边的 70% 取方形，居中偏上 —— 这只是个不至于难看的默认值，
     * 真正的摆放应该交给 MIUI 自己的算法（也就是上面两级）。
     */
    private fun publicOptions(context: Context): ActivityOptions? = try {
        val metrics = context.resources.displayMetrics
        val side = (minOf(metrics.widthPixels, metrics.heightPixels) * 0.7f).toInt()
        val left = (metrics.widthPixels - side) / 2
        val top = (metrics.heightPixels - side) / 3
        val opts = ActivityOptions.makeBasic()
        // setLaunchWindowingMode / setLaunchBounds 是 @UnsupportedAppUsage，
        // 公开 SDK 里没有这两个方法，只能反射调 —— 写真实调用编译不过。
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, WINDOWING_MODE_FREEFORM)
        }
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchBounds", Rect::class.java)
                .invoke(opts, Rect(left, top, left + side, top + side))
        }
        opts
    } catch (t: Throwable) {
        null
    }



    /**
     * 小窗启动的**事后自检与补救**。
     *
     * ## 为什么必须有这个
     *
     * `startActivity(intent, freeformOptions)` **不抛异常不代表小窗起来了**。
     * MIUI 的闸门、黑名单、目标不支持 resize、options 被忽略……
     * 任何一种都会让应用以全屏起来，而我们这边完全无感。
     * 结果就是用户看到「划了一下，什么都没发生」，
     * 而 logcat 里也是干干净净 —— 这种故障没法排查，也没法验证。
     *
     * ## 做法（照 HyperGesture 的思路）
     *
     * 轮询最多 [TIMEOUT_MS] 毫秒找出刚启动的那个任务：
     *  - windowingMode == 5（freeform）→ 成功
     *  - 找到了但不是 5 → 调 `setTaskResizeable(taskId, 2)` 补救一次，再判
     *  - 一直没出现 → 判定失败
     *
     * 结论写进日志（`via L?` 之后那句），**这样「手势有反应但小窗没起来」
     * 和「手势根本没反应」在日志上能区分开**。这两者的排查方向完全不同。
     *
     * ## 为什么要连 bounds 一起打（自定义摆放那条线靠它验收）
     *
     * 「我们请求的 bounds」由 [launchInSmallWindow] 那一侧打出（`小窗摆放：第 N 级已覆写 bounds=…`），
     * 而**实际摆在哪**只有任务本身知道：MIUI 可能按自己的算法再改一次、可能乘了 `freeformScale`、
     * 也可能目标不支持 resize 直接拒绝。所以这里把任务当前的
     * `configuration.windowConfiguration.getBounds()` 也打出来 ——
     * 「请求的」与「实际的」对不上时，能立刻分清是**我们算错了**还是**系统改了**。
     * 这里是纯读取，不做任何写操作。
     *
     * 跑在后台线程上：它要轮询 + 做 Binder 调用，绝不能在输入线程或主线程上等。
     */
    fun verifyFreeformLaunch(
        hook: HookContext,
        pkg: String,
        timeoutMs: Long = VERIFY_TIMEOUT_MS,
    ) {
        Thread({
            val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
            var repaired = false
            var lastMode = -1
            var lastBounds: Rect? = null
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val task = findTaskOf(pkg)
                if (task != null) {
                    val mode = windowingModeOf(task)
                    lastMode = mode
                    lastBounds = boundsOf(task)
                    if (mode == WINDOWING_MODE_FREEFORM) {
                        hook.log(
                            Log.INFO,
                            "小窗自检：$pkg 已在 freeform（mode=5 bounds=${fmtRect(lastBounds)})",
                        )
                        return@Thread
                    }
                    if (!repaired) {
                        // 只补救一次。反复改同一任务的 resizeable 会把系统惹毛，
                        // 而且说明这条路本身对目标不成立，再试也是白试。
                        repaired = true
                        val taskId = taskIdOf(task)
                        if (taskId >= 0) {
                            hook.log(
                                Log.INFO,
                                "小窗自检：$pkg 起来了但不是 freeform（mode=$mode，" +
                                    "bounds=${fmtRect(lastBounds)}），" +
                                    "对 task $taskId 补救一次 setTaskResizeable"
                            )
                            callSetTaskResizeable(taskId)
                        }
                    }
                }
                try {
                    Thread.sleep(VERIFY_POLL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            hook.log(
                Log.WARN,
                "小窗自检失败：$pkg 在 ${timeoutMs}ms 内没有以 freeform 出现" +
                    "（最后观察到的 mode=$lastMode bounds=${fmtRect(lastBounds)}，" +
                    "mode=-1 表示没找到任务）。" +
                    "这条日志说明手势判定是好的，问题在启动这一段。"
            )
        }, "fp-freeform-verify").apply {
            isDaemon = true
            start()
        }
    }

    /** 找出某个包当前的任务。用 `getTasks` 而不是 `getTopVisibleTask`：
     *  后者只给「可见的顶层」，而我们要找的正是那个可能没起来的。 */
    private fun findTaskOf(pkg: String): Any? = runCatching {
        val atm = Class.forName("android.app.ActivityTaskManager")
        val service = atm.getMethod("getService").invoke(null)
        // getTasks(int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId)
        val m = service.javaClass.getMethod(
            "getTasks",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        @Suppress("UNCHECKED_CAST")
        val tasks = m.invoke(service, 20, false, true, 0) as? List<Any> ?: return null
        tasks.firstOrNull { t -> taskPackageOf(t) == pkg }
    }.getOrNull()

    private fun taskPackageOf(task: Any): String? = runCatching {
        val f = task.javaClass.getField("realActivity")
        (f.get(task) as? android.content.ComponentName)?.packageName
    }.getOrNull()

    private fun taskIdOf(task: Any): Int = runCatching {
        task.javaClass.getField("taskId").getInt(task)
    }.getOrDefault(-1)

    /** windowingMode 在 TaskInfo.configuration.windowConfiguration 里，不是直接字段。 */
    private fun windowingModeOf(task: Any): Int = runCatching {
        val config = task.javaClass.getField("configuration").get(task) ?: return -1
        val wc = config.javaClass.getField("windowConfiguration").get(config) ?: return -1
        wc.javaClass.getMethod("getWindowingMode").invoke(wc) as? Int ?: -1
    }.getOrDefault(-1)

    /**
     * 任务**当前**的 bounds（`windowConfiguration.getBounds()`）。
     *
     * 与 [windowingModeOf] 同一条反射路径（同一个 `windowConfiguration` 对象），
     * 只是取另一个方法：**这是真实摆放的ground truth** —— 我们请求的 bounds 由
     * [launchInSmallWindow] 那一侧打出，两者对上才叫「自定义摆放生效了」。
     * 拿不到返回 null（不猜）。
     */
    private fun boundsOf(task: Any): Rect? = runCatching {
        val config = task.javaClass.getField("configuration").get(task) ?: return null
        val wc = config.javaClass.getField("windowConfiguration").get(config) ?: return null
        wc.javaClass.getMethod("getBounds").invoke(wc) as? Rect
    }.getOrNull()

    /** 补救：把任务改成可 resize。`2` = RESIZE_MODE_RESIZEABLE（AOSP 公开常量）。 */
    private fun callSetTaskResizeable(taskId: Int) {
        runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager")
            val service = atm.getMethod("getService").invoke(null)
            service.javaClass
                .getMethod("setTaskResizeable", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(service, taskId, 2)
        }
    }

    private const val VERIFY_TIMEOUT_MS = 3000L
    private const val VERIFY_POLL_MS = 200L

    /**
     * 前台应用追踪器。
     *
     * ## 为什么需要缓存，而不是触发时现查
     *
     * `foregroundPackage()` 走的是 Binder 跨进程调用。手势判定跑在**输入分发线程**上，
     * 每个 `ACTION_DOWN` 都调一次 IPC 是明确的红线（规范 §7.5：hook 回调里禁止跨进程调用）。
     *
     * 所以这里用一个 500ms 的后台轮询把结果缓存成普通字段，手势代码只读 volatile 字段 —— 零 IPC。
     * 轮询间隔与判定方式沿用验证过的参数（Appindex 的 250ms/10s 组合是针对
     * 「前台应用判定」这个更严的场景，我们只关心「是不是桌面」，500ms 足够）。
     *
     * ## 代价与取舍
     *
     * ⚠️ **这里原先写着「用户从应用回到桌面再侧滑，中间不可能短于 500ms」—— 那个假设是错的**，
     * 真机手感直接推翻它（产品经理报的「拉一次之后不能快速重新呼出」就是这个）。
     * 现在及时性由推送负责，本轮的轮询只作兜底。
     */
    object ForegroundTracker {

    // 注意它**嵌套在 SystemUiEnv 里**，不是顶层 object。
    // 理由：它要用 SystemUiEnv.foregroundPackage()，而把它放到外面之后
    // 那个私有函数就不可见了（第一版就踩了这个，编译直接报 unresolved）。


        /**
         * 轮询间隔。
         *
         * 250ms 取自 Appindex 项目在真机上验证过的参数
         * （`ForegroundAppPollingPolicy.REFRESH_INTERVAL_MS`；同一份 policy 里还有
         * `QUERY_WINDOW_MS = 10s` 和 `REALTIME_SIGNAL_HOLD_MS = 2s`，那两个是给
         * UsageStats 查询窗口和「实时信号优先」用的 —— 我们只判断「是不是桌面」，
         * 用不到那两层，所以这里只沿用间隔）。
         */
        /**
         * 兜底轮询间隔。**从 250ms 放长到 1000ms**：及时性由 [startPushListener] 的推送负责，
         * 轮询只用来补推送漏掉的情况。放长的直接收益是 IPC 从 4 次/秒降到 1 次/秒。
         */
        private const val INTERVAL_MS = 1000L

        /** 首个结果出来之前的探测间隔。见轮询循环里那段注释。 */
        private const val WARMUP_INTERVAL_MS = 60L

        @Volatile
        private var current: String? = null

        /** 桌面（Launcher）的包名，用来判断「现在是不是在桌面」。 */
        @Volatile
        private var homePackage: String? = null

        private var thread: Thread? = null

        @Volatile
        private var running = false

        /** 缓存的前台包名。可能滞后最多 [INTERVAL_MS] 毫秒。 */
        fun foreground(): String? = current

        /** 当前是否停在桌面。这是侧滑索引生效的前提。 */
        fun isLauncherForeground(): Boolean {
            val home = homePackage ?: return false
            return current == home
        }

        /**
         * 推送源：`ITaskStackListener` 的动态代理。
         *
         * ## 为什么必须有它 —— 这是产品经理亲身体验到的那条缺陷的真因
         *
         * 原实现只有 250ms 轮询，代码注释里的假设是「用户从应用回到桌面再侧滑，
         * 中间不可能短于 500ms」。**这个假设被真机手感直接推翻**：
         * 从应用按 HOME 回桌面、**不到 250ms 内**就从边缘划 —— 那一刻缓存里还是上一个应用，
         * `launcher_only` 判不放行 → 索引不响应，而且方案 A 也不会抢 DOWN
         * → 桌面趁机把抽屉打开。表现就是「拉一次之后不能快速重新呼出」+「偶发」。
         *
         * `ITaskStackListener.onTaskMovedToFront` 是**系统推的**，延迟从 250ms 降到「下一次调度」。
         *
         * ## 为什么用 AIDL 接口而不是 `TaskStackListener`
         *
         * `android.app.TaskStackListener` 是**抽象类**，动态代理只能代理接口；
         * 而 `android.app.ITaskStackListener` 是 AIDL 接口 —— 同一份回调，可以代理。
         *
         * ## 轮询为什么**不删**
         *
         * 推送会漏：分屏 / 画中画 / 弹窗 / 注册本身失败。漏掉的后果是「手势随机失效」——
         * 比慢得多更糟。所以轮询降级为**兜底**（间隔同时放长，省 IPC）。
         */
        private var stackListener: Any? = null

        /**
         * 日志出口。由 `SideIndex.install` 注入（它是唯一能拿到 `HookContext` 的地方）。
         *
         * ⚠️ **不能直接用 `android.util.Log`**：本项目实测过，模块在 SystemUI 里用
         * `android.util.Log` 打的日志在 **logcat 与 LSPosed 日志里都看不到**。
         * 而「推送到底注册上没有」必须能被看见 —— 它决定了「回桌面后立刻侧滑」响不响应。
         */
        @Volatile
        var log: ((String) -> Unit)? = null

        private fun note(msg: String) {
            log?.invoke(msg) ?: android.util.Log.i("FP-Foreground", msg)
        }

        /** 注册推送源。**失败不致命**：轮询还在，只是回到「最多 1s 滞后」。 */
        private fun startPushListener() {
            try {
                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val atm = atmClass.getMethod("getService").invoke(null)
                if (atm == null) {
                    // ⚠️ 这条早退原先**不记任何日志** —— 于是「推送没注册上」在日志里
                    // 完全看不见（第一版就栽在这里）。静默的早退等于没做。
                    note("前台推送注册失败：ActivityTaskManager.getService() 返回 null")
                    return
                }
                val listenerClass = Class.forName("android.app.ITaskStackListener")
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                ) { _, method, args ->
                    try {
                        if (method.name == "onTaskMovedToFront") {
                            // 直接从这个参数里取包名（零额外 IPC）
                            taskTopPackage(args?.firstOrNull())?.let { current = it }
                        }
                        // 其余回调（onTaskStackChanged / onTaskDescriptionChanged 等）**什么都不做**：
                        // 它们可能被高频调用，而这里刻意不发起 IPC —— 轮询会在下一个周期补上。
                    } catch (_: Throwable) {
                        // 回调在 Binder 线程上，异常绝不能逃出去
                    }
                    null
                }
                // ⚠️ 找方法要在 **getService() 返回的那个对象**上找，不是在 ActivityTaskManager 上：
                // `registerTaskStackListener(ITaskStackListener)` 是 **AIDL 服务接口**的方法，
                // 工具类 ActivityTaskManager 上只有接抽象类 TaskStackListener 的那个重载。
                // 实测报错：NoSuchMethodException: ActivityTaskManager.registerTaskStackListener[...]
                atm.javaClass.getMethod("registerTaskStackListener", listenerClass).invoke(atm, proxy)
                stackListener = proxy
                note("前台推送已注册（TaskStackListener）—— 回桌面后立刻侧滑可即时响应")
            } catch (t: Throwable) {
                // 说清代价：回落到轮询 = 产品经理报的那个症状仍在
                note("前台推送注册失败，回落到轮询（回桌面后立刻侧滑可能不响应）：" + t)
            }
        }

        private fun stopPushListener() {
            val proxy = stackListener ?: return
            stackListener = null
            try {
                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val atm = atmClass.getMethod("getService").invoke(null) ?: return
                val listenerClass = Class.forName("android.app.ITaskStackListener")
                atm.javaClass.getMethod("unregisterTaskStackListener", listenerClass).invoke(atm, proxy)
            } catch (_: Throwable) {
                // 注销失败只漏一个监听器，进程结束时一并消失
            }
        }

        /** 从 `RunningTaskInfo` 反射取「栈顶 Activity 的包名」。取不到返回 null（交给轮询）。 */
        private fun taskTopPackage(info: Any?): String? = try {
            val component = info?.javaClass?.getMethod("getTopActivity")?.invoke(info)
            (component?.javaClass?.getMethod("getPackageName")?.invoke(component) as? String)
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }

        fun start(context: Context) {
            note("前台追踪器 start：running=$running push=" + (stackListener != null))
            // ⚠️ **推送注册必须在 running 闸门之外。**
            // 这个 tracker 是**与底角小窗共用的单例**：谁先装谁把 `running` 置 true，
            // 侧滑索引后装时就被那道闸门挡在外面 —— 于是推送这条路径**从来没被注册过**。
            // 实测定到的方式：在 start 入口打一条 running 的日志，装机后看到它一律是 true。
            // 幂等由 `stackListener == null` 自己保证，与 `running` 无关。
            if (stackListener == null) startPushListener()
            if (running) return
            running = true
            homePackage = resolveHomePackage(context)
            startPushListener()
            thread = Thread({
                while (running) {
                    try {
                        // 只在新值非空时覆盖。**不要把 null 写进去** ——
                        // 一次查询失败就把缓存清空，会让手势在系统繁忙时随机失效。
                        queryForeground()?.let { current = it }
                    } catch (_: Throwable) {
                        // 查不到就保持上一次的值，不要把缓存清空 ——
                        // 清空会让手势在系统繁忙时随机失效
                    }
                    try {
                        // 首个结果出来之前用更短的间隔：实测 SystemUI 重启后要 6~20 秒
                        // 才出第一个结果，而这段时间 launcher_only 一律判不放行，
                        // 表现为「刚重启完怎么划都没反应」。拿到之后回常规间隔。
                        Thread.sleep(if (current == null) WARMUP_INTERVAL_MS else INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "fp-foreground").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        /** 当前在用的功能（按 key）。最后一个 release 时停。 */
        private val owners = HashSet<String>()

        /**
         * 功能安装时调。同一个 owner 重复 acquire 只算一次（热应用会重装）。
         * 原来只有 [stop] 由 HookRegistry 在「全部卸载」时调：只开扇形、运行中关掉它，
         * 每秒轮询 + 任务栈监听会一直留到进程重启。
         */
        fun acquire(owner: String, context: Context) {
            synchronized(owners) { owners.add(owner) }
            start(context)
        }

        /** 功能卸载时调；没人用了就停。 */
        fun release(owner: String) {
            val idle = synchronized(owners) { owners.remove(owner); owners.isEmpty() }
            if (idle) stop()
        }

        fun stop() {
            synchronized(owners) { owners.clear() }
            running = false
            thread?.interrupt()
            thread = null
            stopPushListener()
            current = null
            homePackage = null
        }

        private fun resolveHomePackage(context: Context): String? = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (_: Throwable) {
            null
        }

        private fun queryForeground(): String? = foregroundPackage()
    }
}
