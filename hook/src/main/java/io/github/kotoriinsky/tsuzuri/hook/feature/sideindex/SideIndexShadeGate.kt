package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.util.Log
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「通知栏 / 控制中心**已经拉开**」这条门控。
 *
 * ## 现象（2026-09-15 用户反馈 + 真机复现）
 *
 * 控制中心拉开之后，沿边缘滑动仍然会弹出侧滑索引。日志（复现那次）：
 *
 * ```text
 * 侧滑索引：抢指针：成功（系统手势已被取消）
 * 侧滑索引：起手成立：右边缘 dy=-88 dx=0
 * ```
 *
 * 比「多弹一个浮层」更糟的是那行**抢指针**：`pilferPointers()` 会把通知栏当前正在
 * 处理的那条手势**取消掉** —— 用户在面板里划快捷开关、或往下拖回去收面板，都会被我们抢走。
 * 所以这条不是观感问题，是「面板划不动 / 收不回去」。
 *
 * ## 为什么单独做成一条门控，而不是改几何
 *
 * 与需求 #8（抽屉）同形：**面板已经在场**时，任何几何调整都只是降低叠加概率
 * 而这里的判据是确定性的状态量。所以照
 * [SideIndexDrawerGate] 的形状做：输入线程**只读一个 volatile**，没有锁、没有 IPC、没有分配。
 *
 * ## 信号源：`ShadeExpansionStateManager.onPanelExpansionChanged(FZZ)`
 *
 * ⚠️ **两个踩过的坑，别再走一遍**（都是「拿名字当行为」）：
 *
 * 1. 第一版挂 `NotificationShadeWindowControllerImpl.setPanelVisible(Z)`。名字像「面板可见性」，
 *    实测却在**面板窗口刚 attach 时**就被置 `true`（SystemUI 起来后从没碰过面板，门控已是
 *    「已展开」→ 桌面上的索引被**误杀**）。它描述的是「面板窗口该可见」，不是「面板已展开」。
 * 2. 第二版挂 `NotificationPanelViewController.setExpandedHeight/setExpandedFraction(F)`。
 *    它们**时而带真值、时而带 0**：实测同一次开面板出现过 `2478.0` / `613.0`，紧接着又是
 *    `0.0`（面板其实还开着）。拿它当判据的表现就是「面板开着、索引却照常起手」。
 *    所以它们降级成**观察探针**（日志里标 `探针`），不参与判定。
 *
 * 真正的权威信号是 `ShadeExpansionStateManager.onPanelExpansionChanged(float fraction,
 * boolean tracking, boolean expanded)`：AOSP 把「展开到多少」的每次变化都送到这里，
 * 一次调用就带齐三个量，不需要反射读事件对象。取 `fraction > 0` 当判据 ——
 * 语义上是「面板不是完全收起」，**连用户正在往下拉的那一段也算**（那一段同样不该被我们抢）。
 *
 * ## 拿不到信号时怎么办：**fail-open，但必须出声**
 *
 * 类/方法找不到（别的 ROM 改了名字）时不装 hook，[blocks] 恒为 false —— 行为与「未装这条
 * 门控」完全一致，并在安装报告里留一行 `shade=missing`。刻意**不** fail-closed：
 * 判据失效就把索引整个停掉，等于用户装了个「没有索引」的模块，比原缺陷更难排查。
 *
 * ## 验证要点（改这里之前先读）
 *
 * 1. 面板**收起**时权威信号应报 `fraction=0.0`；若报非 0，加阈值或换信号；
 * 2. 面板**拉开**时权威信号必须报 `fraction>0`（这是「信号源选对了」的唯一证据）；
 * 3. 面板开着时沿边缘滑 → `起手被拒：通知栏/控制中心已展开`，且**面板不受影响**；
 * 4. 面板收起时沿边缘滑 → 索引照常 `起手成立`（对照组，缺了它等于没验）。
 */
internal object SideIndexShadeGate {

    private const val LOG_PREFIX = "侧滑索引：通知栏门控"

    /**
     * 权威信号：`(float fraction, boolean tracking, boolean expanded)`。
     *
     * 类名只写一个：这个类就是状态的持有者（它自己带 `fraction:F` / `expanded:Z` 字段），
     * 没有接口/实现两份的问题。
     */
    private const val AUTHORITY_CLASS = "com.android.systemui.shade.ShadeExpansionStateManager"
    private const val AUTHORITY_METHOD = "onPanelExpansionChanged"

    /**
     * **HyperOS 控制中心自己那条链**（2026-09-15 追加，见类注释「第三条」）。
     *
     * 类名来自真机：用户手指在控制中心里操作时，日志里一直打
     * `V/ControlCenterExpandController: on expand change …`，而同时**所有** AOSP 侧信号
     * （`ShadeExpansionStateManager` / `NotificationPanelViewController` / `MiuiQSFragment`）
     * 都报「已收起」。顺着日志 tag 在 SystemUI APK 的 dex 里找到这个 delegate：
     * 它持有 `PanelExpandController`、`addCallback(PanelExpandController$Callback)`，
     * 并有 `onExpandHeightChanged(FFZZ)V`（第一个参数 = 展开高度）。
     */
    private const val MIUI_CC_CLASS =
        "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate"
    private const val MIUI_CC_METHOD = "onExpandHeightChanged"

    /**
     * 只观察的探针 ①：`NotificationPanelViewController` 的两个 setter。
     *
     * 它们**不参与判定**（值会随面板状态漂移，见类注释），留着是因为它们能证明
     * 「另一条展开链路动没动」—— 排查「门控没挡住」时，这一行能立刻把范围缩到哪条链上。
     */
    private val PROBE_CLASS = "com.android.systemui.shade.NotificationPanelViewController"
    private val PROBE_METHODS = arrayOf("setExpandedHeight", "setExpandedFraction")

    /**
     * 只观察的探针 ②：**各条链各自的「面板收起没」getter**。
     *
     * 为什么要有这一组：2026-09-15 真机复现里，用户手指在控制中心里的那几次手势
     * 权威信号报的是 `fraction=0`（= 面板收起），而随后真拉开时它又准 —— 也就是说
     * **权威信号不一定覆盖用户那条打开路径**。这一组的作用是「下次手势把所有候选值
     * 一起打出来」，用数据选信号，而不是再猜一轮（前两个版本都是猜错的）。
     *
     * 每个候选都记**最后一次返回值**（不参与判定，只进 [snapshot]）。
     * `-1` = 没挂上 / 没被调过，`0` = 不收起（面板开着），`1` = 已收起。
     */
    private val BOOL_PROBES = arrayOf(
        "com.android.systemui.shade.NotificationPanelViewController" to "isFullyCollapsed",
        "com.android.systemui.shade.domain.interactor.PanelExpansionInteractorImpl" to "isFullyCollapsed",
        "com.android.systemui.qs.composefragment.QSFragmentCompose" to "isFullyCollapsed",
        "com.android.systemui.qs.MiuiQSFragment" to "isFullyCollapsed",
    )

    private val boolProbeValues = IntArray(BOOL_PROBES.size) { -1 }

    /** 逐帧探针日志预算（每次 SystemUI 重启重置）。 */
    private const val LOG_BUDGET = 40

    /**
     * **状态翻转**的日志预算，与 [LOG_BUDGET] **分开**。
     *
     * 为什么必须分：逐帧探针在拖动那几百帧里一次就把预算烧光（实测），于是**真正重要的那条**
     * ——「门控由开变关 / 由关变开」—— 会被埋掉。现象是「面板收起来之后索引还是不动」，
     * 而分不清到底是信号没来还是门控坏了。翻转天然很少（一次开关面板 1~2 条），给足预算。
     */
    private const val FLIP_LOG_BUDGET = 200

    private val logUsed = AtomicInteger()

    private val flipLogUsed = AtomicInteger()

    /** 收到过多少次权威调用。把「hook 没触发」与「触发了但值不变」分开。 */
    private val calls = AtomicInteger()

    /** 面板是否**不是完全收起**。**输入线程只读它**。 */
    @Volatile
    private var panelOpen = false

    /** HyperOS 控制中心是否展开（MIUI 那条链，见 [MIUI_CC_CLASS]）。**输入线程只读它**。 */
    @Volatile
    private var ccOpen = false

    /** 是否挂上了权威信号。只在权威信号存在时，[blocks] 才可能为真。 */
    @Volatile
    private var authorityReady = false

    /** 已装的 hook 句柄（权威 + 探针）。自己管生命周期，与 [SideIndexDrawerGate] 自己管接收器一致。 */
    private val handles = ArrayList<HookHandle>(4)

    /** 安装结论（进 [io.github.kotoriinsky.tsuzuri.hook.InstallReport]）。 */
    @Volatile
    var note: String = "shade=off"
        private set

    /**
     * 这条门控当前是否该拦住边缘手势。**输入线程上跑**：两次 volatile 读，
     * 无锁、无 IPC、无分配（与 `SideIndexDrawerGate.blocks` 同一条纪律）。
     *
     * **两个信号取并**：AOSP 那条（通知栏 / 从状态栏下拉）与 MIUI 那条（控制中心）
     * 在本机是两套互不相通的实现，任何一条报「开着」都得拦 —— 这是实测逼出来的，
     * 只挂 AOSP 那条时用户在控制中心里照样能划出索引。
     */
    val blocks: Boolean get() = panelOpen || ccOpen

    /**
     * 装上。可从安装路径重复调（幂等）。
     *
     * @return 进安装报告的那一节
     */
    fun start(ctx: HookContext): String {
        if (handles.isNotEmpty()) return note

        authorityReady = hookAuthority(ctx)
        val cc = hookMiuiControlCenter(ctx)
        val probes = hookProbes(ctx)
        val getters = hookBoolProbes(ctx)

        note = if (authorityReady || cc) {
            "shade=panel(aosp=$authorityReady,miuiCC=$cc,floatProbe=$probes,boolProbe=$getters)"
        } else {
            // 明确写出「两条权威信号都没挂上」而不是留一个看起来正常的报告（规范：匹配失败不能静默无声）
            "shade=missing(floatProbe=$probes,boolProbe=$getters)"
        }
        if (!authorityReady) {
            ctx.log(
                Log.WARN,
                "$LOG_PREFIX：没挂上权威信号 $AUTHORITY_CLASS#$AUTHORITY_METHOD(FZZ)，" +
                    "门控保持关闭 —— 行为与未装这条门控时一致",
            )
        }
        return note
    }

    /**
     * 卸载：摘 hook、把状态清回「收起」。
     *
     * **必须清回 false**：不清的话，卸载后（或换到没挂上这条 hook 的进程里）索引会被
     * 一个 stale 的 true 永久抑制 —— 那正是 `DrawerSignal` 注释里警告过的形态。
     */
    fun stop() {
        for (h in handles) {
            try {
                h.unhook()
            } catch (_: Throwable) {
                // 卸载路径上不抛（会在 SystemUI 里逃逸）
            }
        }
        handles.clear()
        panelOpen = false
        ccOpen = false
        authorityReady = false
        calls.set(0)
        logUsed.set(0)
        flipLogUsed.set(0)
        for (i in boolProbeValues.indices) boolProbeValues[i] = -1
        note = "shade=off"
    }

    /** 装权威信号。返回是否装上。 */
    private fun hookAuthority(ctx: HookContext): Boolean {
        val cls = loadClass(ctx, AUTHORITY_CLASS) ?: return false
        val method = findMethod(cls, AUTHORITY_METHOD, arrayOf<Class<*>>(java.lang.Float.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)) ?: return false
        return try {
            handles.add(
                ctx.module.hook(method).intercept { chain ->
                    val fraction = chain.args.getOrNull(0) as? Float ?: 0f
                    val tracking = chain.args.getOrNull(1) as? Boolean ?: false
                    val expanded = chain.args.getOrNull(2) as? Boolean ?: false
                    onPanelExpansion(fraction, tracking, expanded, ctx)
                    // 返回值原样透传：我们只是**观察**面板状态，不改 SystemUI 的行为。
                    chain.proceed()
                },
            )
            ctx.log(Log.INFO, "$LOG_PREFIX：已挂上权威信号 ${cls.name}#$AUTHORITY_METHOD(FZZ)")
            true
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "$LOG_PREFIX：装失败 ${cls.name}#$AUTHORITY_METHOD", t)
            false
        }
    }

    /**
     * 装 **HyperOS 控制中心**那条链（见 [MIUI_CC_CLASS]）。返回是否装上。
     *
     * 判据 = `onExpandHeightChanged` 的第一个参数（展开高度）> 0：
     * 与 AOSP 那条同语义 ——「不是完全收起」，**连正在往下拉的那一段也算**。
     */
    private fun hookMiuiControlCenter(ctx: HookContext): Boolean {
        val cls = loadClass(ctx, MIUI_CC_CLASS) ?: return false
        val method = findMethod(
            cls, MIUI_CC_METHOD,
            arrayOf<Class<*>>(
                java.lang.Float.TYPE, java.lang.Float.TYPE,
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
            ),
        ) ?: return false
        return try {
            handles.add(
                ctx.module.hook(method).intercept { chain ->
                    val height = chain.args.getOrNull(0) as? Float ?: 0f
                    onControlCenterExpand(height, ctx)
                    chain.proceed()
                },
            )
            ctx.log(Log.INFO, "$LOG_PREFIX：已挂上控制中心信号 ${cls.name}#$MIUI_CC_METHOD(FFZZ)")
            true
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "$LOG_PREFIX：装失败 ${cls.name}#$MIUI_CC_METHOD", t)
            false
        }
    }

    /** HyperOS 控制中心的展开高度变了。判据与 [onPanelExpansion] 同形：高度 > 0 = 面板在场。 */
    private fun onControlCenterExpand(height: Float, ctx: HookContext) {
        val open = height > 0f
        if (open == ccOpen) return
        ccOpen = open
        if (flipLogUsed.getAndIncrement() < FLIP_LOG_BUDGET) {
            ctx.log(
                Log.INFO,
                "$LOG_PREFIX：控制中心门控${if (open) "关闭" else "恢复"}" +
                    "（expandHeight=$height）",
            )
        }
    }

    /**
     * 装「面板收起没」的 getter 探针（只观察，只记最后一次返回值）。返回装上的个数。
     *
     * 参数形状固定 `()Z`：这几个名字在各自类里都是无参布尔 getter（`QSFragmentCompose` /
     * `MiuiQSFragment` / `PanelExpansionInteractorImpl` / `NotificationPanelViewController`，
     * 本机 ROM dex 实测都有 `isFullyCollapsed()Z`）。
     */
    private fun hookBoolProbes(ctx: HookContext): Int {
        var n = 0
        for ((index, spec) in BOOL_PROBES.withIndex()) {
            val (clsName, methodName) = spec
            val cls = loadClass(ctx, clsName) ?: continue
            val method = findMethod(cls, methodName, arrayOf<Class<*>>()) ?: continue
            try {
                handles.add(
                    ctx.module.hook(method).intercept { chain ->
                        val collapsed = chain.proceed() as? Boolean
                        if (collapsed != null) {
                            // 0 = 不收起（面板开着），1 = 已收起。**不参与判定**，只进 [snapshot]。
                            boolProbeValues[index] = if (collapsed) 1 else 0
                            probe("$methodName()=$collapsed（$clsName）", ctx)
                        }
                        collapsed
                    },
                )
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "$LOG_PREFIX：getter 探针装失败 $clsName#$methodName", t)
            }
        }
        return n
    }

    /**
     * 供每次手势的日志使用的一行快照：`权威 + 各 getter 候选的最后一次返回值`。
     *
     * 格式：`shadeGate[authority=false npc=1 pei=-1 qs=-1 miuiqs=-1]`
     * （`-1` = 没挂上/没被调过）。**「没被调过」本身也是结论** —— 说明那条链在这次
     * 面板开关里根本没被问过，不能当判据。
     */
    fun snapshot(): String = buildString {
        append("shadeGate[authority=").append(panelOpen).append(" miuiCC=").append(ccOpen)
        for ((index, spec) in BOOL_PROBES.withIndex()) {
            append(' ').append(shortName(spec.second, spec.first)).append('=')
            append(boolProbeValues[index])
        }
        append(']')
    }

    private fun shortName(method: String, cls: String): String = when {
        cls.endsWith("PanelExpansionInteractorImpl") -> "pei"
        cls.endsWith("QSFragmentCompose") -> "qsCompose"
        cls.endsWith("MiuiQSFragment") -> "miuiQs"
        cls.endsWith("NotificationPanelViewController") -> "npc"
        else -> method
    }

    /** 装探针（只观察）。返回装上的个数。 */
    private fun hookProbes(ctx: HookContext): Int {
        val cls = loadClass(ctx, PROBE_CLASS) ?: return 0
        var n = 0
        for (name in PROBE_METHODS) {
            val method = findMethod(cls, name, arrayOf<Class<*>>(java.lang.Float.TYPE)) ?: continue
            try {
                handles.add(
                    ctx.module.hook(method).intercept { chain ->
                        val value = chain.args.getOrNull(0) as? Float ?: 0f
                        probe("$name($value)", ctx)
                        chain.proceed()
                    },
                )
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "$LOG_PREFIX：探针装失败 ${cls.name}#$name", t)
            }
        }
        return n
    }

    /**
     * 权威信号到了。
     *
     * 判据 = `fraction > 0`：**不是**那个 `expanded` 布尔（它只在「完全展开」时为真，
     * 而我们要挡的是从「开始下拉」到「收回去」之间的整段）。`tracking` 只进日志。
     */
    private fun onPanelExpansion(fraction: Float, tracking: Boolean, expanded: Boolean, ctx: HookContext) {
        calls.incrementAndGet()
        val open = fraction > 0f
        if (open != panelOpen) {
            panelOpen = open
            // **翻转永远要留痕**（独立预算，见 [FLIP_LOG_BUDGET]）：
            // 这一行是「门控到底有没有跟着面板走」的唯一证据。
            if (flipLogUsed.getAndIncrement() < FLIP_LOG_BUDGET) {
                ctx.log(Log.INFO, "$LOG_PREFIX：门控${if (open) "关闭" else "恢复"}（面板${if (open) "已展开" else "已收起"}）")
            }
        }
        if (logUsed.getAndIncrement() < LOG_BUDGET) {
            ctx.log(
                Log.INFO,
                "$LOG_PREFIX：[权威] fraction=$fraction tracking=$tracking expanded=$expanded",
            )
        }
    }

    /** 探针命中：只记日志，**绝不参与判定**。 */
    private fun probe(text: String, ctx: HookContext) {
        if (logUsed.getAndIncrement() < LOG_BUDGET) {
            ctx.log(Log.INFO, "$LOG_PREFIX：[探针] $PROBE_CLASS#$text")
        }
    }

    /** 期望返回 null（找不到 = 优雅降级），**不抛**。 */
    private fun loadClass(ctx: HookContext, name: String): Class<*>? = try {
        Class.forName(name, false, ctx.classLoader)
    } catch (_: Throwable) {
        null
    }

    /**
     * 按「名字 + 参数个数 + 参数类型」找方法，**跳过 abstract**：接口上的抽象声明没有方法体，
     * hook 它既不会触发、又会让 [start] 误报「装上了」。
     *
     * 只按名字 + 参数个数匹配是不够的 —— 同名同参数数的重载一旦存在就会挂到完全不同的方法上，
     * 而且不会有任何报错（`MiuiWidgetFlagInjector.findMethod` 也是为这条才带上类型）。
     */
    private fun findMethod(cls: Class<*>, name: String, types: Array<Class<*>>): Method? = try {
        cls.declaredMethods.firstOrNull { m ->
            m.name == name && !Modifier.isAbstract(m.modifiers) &&
                m.parameterTypes.size == types.size &&
                m.parameterTypes.withIndex().all { (i, t) -> t == types[i] }
        }
    } catch (_: Throwable) {
        null
    }
}
