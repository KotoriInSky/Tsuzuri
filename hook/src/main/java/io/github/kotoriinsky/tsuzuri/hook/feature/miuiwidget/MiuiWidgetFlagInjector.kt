package io.github.kotoriinsky.tsuzuri.hook.feature.miuiwidget

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 把第三方小部件「伪装」成 MIUI 小部件 —— 在 Binder 出口处注入 `miuiWidget=true`。
 *
 * 为什么需要它：
 *   桌面的 Dart 层在决定「这个小部件能不能拖到负一屏」时会读 `isMiuiWidget`。
 *   实测日志（Xiaomi 17 Pro / OS4.0）：
 *     flutter: AssistantDrag.ToPA, canDragToPA=false, widget not supported by PA,
 *              span=3x1, isMiuiWidget=false
 *   而 MIUI 自家小部件是在 manifest 里声明
 *     <meta-data android:name="miuiWidget" android:value="true"/>
 *
 * 做法：
 *   在 system_server 侧 hook `ActivityInfo.writeToParcel` / `AppWidgetProviderInfo.writeToParcel`
 *   / `ApplicationInfo.writeToParcel`，在 `chain.proceed()` **之前**临时把标志位写进 metaData，
 *   proceed 之后还原 —— Binder 传出去的那份 parcel 带着标志位，而系统内的对象**不被持久污染**。
 *
 * 为什么不 hook 桌面进程：桌面是 Dart/Flutter 原生应用（无 dex、无 ART），Java 类 hook 不了；
 *   但**喂给它的数据**可以在 system_server 侧改。（实测已经把「在桌面进程里 hook
 *   Java 类」判成结构性做不到 —— 桌面进程 `libart` 映射数为 0。HyperStack 那一大块
 *   `onPackageReady("com.miui.home")` 的读侧注入因此是死代码，别照抄。）
 *
 * ⚠️ 当前状态：**搁置（PARKED）**。注入本身确认生效（12+ 次成功），但桌面仍旧报
 *   `isMiuiWidget=false`，说明真正的判据不是这三个 parcel 上的字段。
 *   默认 `system_wide=false` + 白名单为空 → 这个特性开着也什么都不做，是刻意如此。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 本轮（fix/miuiwidget-evidence）的职责：**让这条线可以被证据复核**，不改判据、不改准入。
 *
 * 早期把这条线判成走死（❌），但这个 ❌ 当时**无法复核** —— 因为：
 *   · 一条注入日志都没有 → 装上了没？注了没有？注了几次？走的哪条 hook？全看不见；
 *   · 白名单只在 `install()` 里读一次，而配置变更监听只对 `RestartPolicy.HOT` 生效
 *     （`HookRegistry.kt:237`，本特性的 restart 是 `PROCESS_RESTART`）→ 在 system_server 上
 *     改一次配置要重启整机 → 也不知道测的那一轮白名单里到底有没有那个包。
 *
 * 所以这里加了两条**证据纪律**（抄自 HyperStack，`research/decompiled/hyperstack/…/GalaxyWidgetStackModule.java`）：
 *
 *  1. **每次注入留一条去重日志 + 计数进安装报告**（见 [noteInjection]）。
 *     日志按「组件×路径」去重（照 HyperStack 的 `writeLogOnce`），且有总预算，绝不刷屏。
 *     `hooked=N` 只证明钩子装上了，证明不了注入了没有 —— 这两件事必须分开看。
 *  2. **白名单改完立刻生效**：配置变化由监听推送（[registerConfigListener]），
 *     另有一条 ≥3 秒节流的兜底懒读（[reloadIfStale]）。
 *     读失败一律按「未命中」处理（fail-closed）。
 *
 * 关于「白名单在哪读」的一个细节（不抄 HyperStack 的部分）：HyperStack 在 `isTarget()` 里
 * 同步读配置文件（本地文件，微秒级）。我们**不能**照抄 —— 配置源是 RemotePreferences，
 * 读一次可能是跨进程调用，而门控判断跑在 `ActivityInfo.writeToParcel` 这个 hook 回调里，
 * hook 的纪律是「hook 回调禁跨进程」。所以这里改成
 * **推送为主 + 轮询兜底**，热路径上仍然只读一个 volatile。
 *
 * 明确**没做**的事（免得把「没做」误读成「做了没用」）：
 *  · 判据没动 —— 注入的键、值类型（Boolean）、载体（receiver 的 `ActivityInfo.metaData`）
 *    与 `systemWide`/白名单的语义全部照旧。
 *  · 未命中（白名单里没有这个包）**不打日志**：那条路每次 parcel 都会走，打日志就是刷屏，
 *    而且键的基数不可控。要看「白名单里有没有那个包」，看 install / 热更新那两条配置日志。
 *  · 应用级 `miuiWidgetVersion` 注入**保留**：实测已证伪它的依据（桌面 Dart/Rust
 *    两库 0 命中），但删它属于改注入面，不在本轮职责内。
 *
 * 安全纪律：
 *  - 白名单为空且没开 `system_wide` 时**什么都不做**（默认安全）。
 *  - 临时改 Bundle 后必须还原；异常也要还原（finally）。还原只发生在**命中**路径上。
 *  - 高频路径（每次 parcel 写入）：判断零分配 + 节流，未命中直接 proceed。
 *  - 这是 system_server：**任何异常都不许逃逸**，任何 IO/日志都不放在热路径上。
 */
object MiuiWidgetFlagInjector : Feature(Catalog.require(Catalog.MIUI_WIDGET_FLAG)) {

    private const val KEY = "miuiWidget"
    private const val KEY_VERSION = "miuiWidgetVersion"
    private const val APPWIDGET_KEY = "android.appwidget.provider"

    /** com.mi.health 在应用级声明的就是 7（实测 manifest）。 */
    private const val MIUI_WIDGET_VERSION = 7

    /**
     * 每条日志都带这个前缀，`adb logcat | grep miuiWidget` 就能把整条线的证据一次捞出来。
     * 注入器在 system_server 里跑，日志与一堆系统噪声混在一起，没有稳定前缀等于没有日志。
     */
    private const val LOG_PREFIX = "miuiWidget: "

    /**
     * 白名单懒读的**最小**间隔。
     *
     * 与 HyperStack 的 3000ms 对齐（它是按配置文件 `mtime ^ length` 判变化 + 3 秒节流）。
     * 差别在于它的"变化判据"是文件 mtime，我们的配置源是 RemotePreferences（没有 mtime），
     * 所以**用纯时间节流**：每 ≥3 秒真的去读一次，值变了才记一条日志。
     * 代价是「改完最多 3 秒生效」，收益是判据简单、不依赖任何存储实现细节。
     *
     * 注：正常情况下**根本用不到这个节流** —— 配置变化由 [registerConfigListener] 推送，
     * 改完立刻生效。这条懒读是**兜底**：监听注册失败、或写入方不走正常通道时，
     * 它保证「最多 3 秒后也会生效」，而不是「必须重启整机」。
     */
    private const val RELOAD_INTERVAL_MS = 3_000L

    /** 本特性所有配置键的公共前缀（`miui_widget_flag.`），用来筛变化监听的 key。 */
    private val WIDGET_KEY_PREFIX = Catalog.MIUI_WIDGET_FLAG + '.'

    /** 注入日志预算（条）。用尽后只留一条提示，绝不刷屏。 */
    private const val INJECT_LOG_BUDGET = 64

    /** 准入观测日志预算（条）。**单独一份**，免得观测把注入证据挤掉。 */
    private const val OBSERVE_LOG_BUDGET = 32

    /** 配置日志里最多打几个包名（超了只打数量，避免一行日志几百个包名）。 */
    private const val LOG_MAX_NAMES = 8

    // ── 观测点（只读，见 [hookAdmissionObservers]）──────────────────────────
    private const val MIUI_APPWIDGET_SERVICE = "com.android.server.appwidget.MiuiAppWidgetServiceImpl"
    private const val APPWIDGET_SERVICE = "com.android.server.appwidget.AppWidgetServiceImpl"
    private const val SECURITY_POLICY = "com.android.server.appwidget.AppWidgetServiceImpl\$SecurityPolicy"
    private const val IPM = "android.content.pm.IPackageManager"
    private const val PROVIDER_INFO = "android.appwidget.AppWidgetProviderInfo"

    // ── 状态 ────────────────────────────────────────────────────────────────

    /** 当前生效的配置。**单个 volatile**，热路径只读它一个（理由见 [Config]）。 */
    @Volatile
    private var config: Config = Config.OFF

    /** install 时留下的框架上下文（懒读要用它的 `prefs`，记日志要用它）。卸载时清掉。 */
    @Volatile
    private var ctxRef: HookContext? = null

    /** 上次真正去读配置的时间（`uptimeMillis`）。 */
    @Volatile
    private var lastReloadMs = 0L

    /** 「单赢家」标记：同时只让一个线程去读配置（理由见 [reloadIfStale]）。 */
    private val reloading = AtomicBoolean(false)

    /** 配置变化监听器，用于 [uninstall] 时反注册。 */
    @Volatile
    private var changeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // ── 证据 ────────────────────────────────────────────────────────────────

    /** 已记过日志的「路径×组件」，用来 per-component 去重。 */
    private val injectedOnce = ConcurrentHashMap.newKeySet<String>()

    /** 已用掉的注入日志预算。 */
    private val injectLogUsed = AtomicInteger()

    /** 已记过日志的「观测点×主体」。 */
    private val observedOnce = ConcurrentHashMap.newKeySet<String>()

    /** 已用掉的观测日志预算。 */
    private val observeLogUsed = AtomicInteger()

    /** 实际写进 parcel 的注入**总次数**（同一个组件注十次就记十次）。 */
    private val nInjected = AtomicInteger()

    /** 按路径分的注入次数，下标是 [Path.ordinal]。 */
    @Volatile
    private var nByPath = AtomicIntegerArray(Path.entries.size)

    /** 真正去读过几次配置（懒读是否在跑，看这个数）。 */
    private val nReloads = AtomicInteger()

    override fun install(ctx: HookContext): InstallReport {
        ctxRef = ctx
        applyConfigImmediately(ctx)
        // 推送优先：注册成功就不必等懒读（热路径上一次跨进程读都不做）。
        registerConfigListener(ctx)

        var hooked = 0
        hooked += hookWriteToParcel(ctx, "android.content.pm.ActivityInfo", Path.ACTIVITY_INFO)
        hooked += hookWriteToParcel(ctx, "android.appwidget.AppWidgetProviderInfo", Path.PROVIDER_INFO)
        hooked += hookApplicationInfo(ctx)

        // 只观察、不改返回值的准入观测钩子。装不上不算失败（返回 0 + missing 里在案）。
        val missing = ArrayList<String>(3)
        hooked += hookAdmissionObservers(ctx, missing)

        ctx.log(Log.INFO, LOG_PREFIX + "注入器已挂上：hooked=$hooked，${describe(config)}" + configSourceNote())

        return InstallReport(hooked = hooked, missing = missing, notes = LiveNotes)
    }

    /**
     * 卸载：把 hook 摘掉，并把状态退回「未安装模块」的样子。
     *
     * 先记一条总账再清 —— 否则「这个 boot 一共注了多少次」这条证据会跟着卸载一起消失。
     */
    override fun uninstall() {
        // 注意 `super.uninstall()` 会摘掉 hook；日志要在它之前，且不能因为它抛异常而丢
        try {
            ctxRef?.log(Log.INFO, LOG_PREFIX + "注入器卸载，总账：${counts()}")
        } catch (_: Throwable) {
            // 卸载路径上也不能抛
        }
        // 反注册监听（要用 ctx.store，所以必须在 ctxRef 清空之前）
        val l = changeListener
        if (l != null) {
            changeListener = null
            try {
                ctxRef?.store?.unregisterOnSharedPreferenceChangeListener(l)
            } catch (_: Throwable) {
                // 反注册失败没有补救手段，也不能因此抛出去
            }
        }
        super.uninstall()
        ctxRef = null
        // 关态必须严格等于「未安装模块」：配置回到全关（fail-closed），计数与预算清零。
        config = Config.OFF
        injectedOnce.clear()
        observedOnce.clear()
        injectLogUsed.set(0)
        observeLogUsed.set(0)
        nInjected.set(0)
        nByPath = AtomicIntegerArray(Path.entries.size)
        nReloads.set(0)
        lastReloadMs = 0L
    }

    // ── 注入点 ──────────────────────────────────────────────────────────────

    /**
     * 应用级注入：`ApplicationInfo.metaData["miuiWidgetVersion"]`。
     *
     * 依据（实测 manifest 对比）：
     *   com.mi.health（唯一能进负一屏的第三方小部件）在 <application> 级声明了
     *       <meta-data android:name="miuiWidgetVersion" android:value="7"/>
     *   而被拒的 mark.via.gp 等**完全没有**这个键。
     *
     * ⚠️ 这条依据**已被证伪**（该键在桌面 Dart/Rust 两库中 0 命中）。
     *   保留注入是刻意的：删它属于改注入面，本轮只做「加证据」，不动判据。
     */
    private fun hookApplicationInfo(ctx: HookContext): Int {
        val cls = try {
            Class.forName("android.content.pm.ApplicationInfo", false, ctx.classLoader)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "ApplicationInfo not found", t); return 0
        }
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "writeToParcel") continue
            try {
                track(
                    ctx.module.hook(m).intercept { chain ->
                        val who = chain.thisObject
                        if (who !is ApplicationInfo || !isTarget(who.packageName)) {
                            chain.proceed()
                        } else {
                            val original = who.metaData
                            val copy = if (original == null) Bundle() else Bundle(original)
                            copy.putInt(KEY_VERSION, MIUI_WIDGET_VERSION)
                            copy.putBoolean(KEY, true)
                            who.metaData = copy
                            val swap = Swap(
                                who, original, Path.APPLICATION_INFO, who.packageName ?: "?",
                            )
                            try {
                                chain.proceed()
                            } finally {
                                swap.restore()
                            }
                            // 只有 proceed 正常返回才记账 —— 抛异常时「注入了」这条证据是假的。
                            noteInjection(swap)
                        }
                    }
                )
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "hook ApplicationInfo.writeToParcel failed", t)
            }
        }
        return n
    }

    private fun hookWriteToParcel(ctx: HookContext, className: String, path: Path): Int {
        val cls = try {
            Class.forName(className, false, ctx.classLoader)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "class not found: $className")
            return 0
        }
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "writeToParcel") continue
            try {
                track(
                    ctx.module.hook(m).intercept { chain ->
                        val swap = mark(chain.thisObject, path)
                        try {
                            chain.proceed()
                        } finally {
                            swap?.restore()
                        }
                        if (swap != null) noteInjection(swap)
                    }
                )
                n++
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "hook $className.writeToParcel failed", t)
            }
        }
        return n
    }

    // ── 准入观测（只读，绝不改返回值）────────────────────────────────────────

    /**
     * **只观察、不修改**的准入观测钩子。
     *
     * 为什么「只观察」是硬纪律（动这个函数前先读一遍）：
     *   这三处是「准入到底在哪判」这个问题的观测点：
     *     · `MiuiAppWidgetServiceImpl.isForMiui(IPackageManager, AppWidgetProviderInfo, int)`
     *       —— 实测只用于**定时刷新**判定，不是准入；
     *     · `AppWidgetServiceImpl.bindAppWidgetId(String, int, int, ComponentName, Bundle)`
     *       —— 绑定路径，内部会先过权限判定；
     *     · `AppWidgetServiceImpl$SecurityPolicy.hasCallerBindPermissionOrBindWhiteListedLocked(String)`
     *       —— **真正的权限判定**，返回 false 就是拒绝绑定。
     *   改这三处的返回值 = 直接改 system_server 的绑定/权限行为。在「判据到底是不是这个键」
     *   有结论之前，这里只负责把「系统自己怎么判的」记下来，**返回值原样透传**。
     *
     * ⚠️ 三条不可违反：
     *   1. `chain.proceed()` 先执行、结果**原样返回**；观测代码抛异常也不许影响它
     *      （所以 proceed 在 try 外面，观测包在自己的 try 里）。
     *   2. 每个「观测点×主体」只记一条，且有独立预算 —— `isForMiui` 会被定时刷新反复调用，
     *      不去重就是刷屏。
     *   3. 类/方法找不到就优雅降级：不装、记到 [InstallReport.missing] 里，
     *      **绝不因此让 install 失败**（规范：匹配失败不能静默无声）。
     */
    private fun hookAdmissionObservers(ctx: HookContext, missing: MutableList<String>): Int {
        var n = 0

        // ① MiuiAppWidgetServiceImpl.isForMiui(IPackageManager, AppWidgetProviderInfo, int)
        val isForMiui = findMethod(
            loadClass(ctx, MIUI_APPWIDGET_SERVICE),
            "isForMiui", arrayOf(IPM, PROVIDER_INFO, "int"),
        )
        if (isForMiui != null) {
            n += hookObserved(ctx, isForMiui) { chain, result ->
                if (!observeBudgetLeft()) return@hookObserved
                val info = chain.args.getOrNull(1) as? AppWidgetProviderInfo
                observe(
                    ctx, "isForMiui",
                    info?.provider?.flattenToShortString() ?: "<no-provider>",
                    "result=$result",
                )
            }
        } else {
            missing += "$MIUI_APPWIDGET_SERVICE#isForMiui(IPackageManager,AppWidgetProviderInfo,int)"
        }

        // ② AppWidgetServiceImpl.bindAppWidgetId(String, int, int, ComponentName, Bundle)
        val bindAppWidgetId = findMethod(
            loadClass(ctx, APPWIDGET_SERVICE),
            "bindAppWidgetId", arrayOf("java.lang.String", "int", "int", "android.content.ComponentName", "android.os.Bundle"),
        )
        if (bindAppWidgetId != null) {
            n += hookObserved(ctx, bindAppWidgetId) { chain, result ->
                if (!observeBudgetLeft()) return@hookObserved
                val caller = chain.args.getOrNull(0) as? String ?: "<no-caller>"
                val provider = chain.args.getOrNull(3)?.toString() ?: "<no-provider>"
                observe(ctx, "bindAppWidgetId", "$caller→$provider", "result=$result")
            }
        } else {
            missing += "$APPWIDGET_SERVICE#bindAppWidgetId(String,int,int,ComponentName,Bundle)"
        }

        // ③ AppWidgetServiceImpl$SecurityPolicy.hasCallerBindPermissionOrBindWhiteListedLocked(String)
        val permission = findMethod(
            loadClass(ctx, SECURITY_POLICY),
            "hasCallerBindPermissionOrBindWhiteListedLocked", arrayOf("java.lang.String"),
        )
        if (permission != null) {
            n += hookObserved(ctx, permission) { chain, result ->
                if (!observeBudgetLeft()) return@hookObserved
                val caller = chain.args.getOrNull(0) as? String ?: "<no-caller>"
                observe(ctx, "bindPermission", caller, "result=$result")
            }
        } else {
            missing += "$SECURITY_POLICY#hasCallerBindPermissionOrBindWhiteListedLocked(String)"
        }

        return n
    }

    /**
     * 装一个「先 proceed、再观测、原样返回」的 hook。
     *
     * 返回 0（没装上）时**不抛异常**，让 [hookAdmissionObservers] 继续装下一个观测点 ——
     * 一个观测点装不上不该拖累其余两个。
     */
    private fun hookObserved(
        ctx: HookContext,
        method: Method,
        observeBlock: (XposedInterface.Chain, Any?) -> Unit,
    ): Int = try {
        track(
            ctx.module.hook(method).intercept { chain ->
                // ① 先拿原始返回值：proceed 抛异常时按未装模块的样子往外抛，不吞。
                val result = chain.proceed()
                // ② 再观测：观测抛异常也绝不能改变这次调用的结果。
                try {
                    observeBlock(chain, result)
                } catch (_: Throwable) {
                    // 观测失败不是失败
                }
                // ③ 原样透传。
                result
            }
        )
        1
    } catch (t: Throwable) {
        ctx.log(Log.WARN, LOG_PREFIX + "观测点安装失败：${method.declaringClass.name}#${method.name}", t)
        0
    }

    /** 期望返回 null（找不到 = 优雅降级），**不抛**。 */
    private fun loadClass(ctx: HookContext, name: String): Class<*>? = try {
        Class.forName(name, false, ctx.classLoader)
    } catch (_: Throwable) {
        null
    }

    /**
     * 找方法：**名字 + 参数个数 + 每个参数的类型名**都要对上。
     *
     * 只按「名字 + 参数个数」匹配是不够的 —— 同名同参数数的重载一旦存在，
     * 就会 hook 到一个完全不同的方法上，而且**不会有任何报错**：
     * 表现是「观测日志里的参数全是 null」，最容易怀疑到 ROM 头上。
     */
    private fun findMethod(cls: Class<*>?, name: String, paramTypes: Array<String>): Method? {
        if (cls == null) return null
        return try {
            cls.declaredMethods.firstOrNull { m ->
                m.name == name && m.parameterTypes.size == paramTypes.size &&
                    m.parameterTypes.withIndex().all { (i, t) -> t.name == paramTypes[i] }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 观测预算还没用尽？用尽后**连日志参数都不要构造**（调用方据此早退，见 [observe]）。 */
    private fun observeBudgetLeft(): Boolean = observeLogUsed.get() <= OBSERVE_LOG_BUDGET

    /**
     * 记一条准入观测。去重 + 预算，两条都不能省：
     * `isForMiui` 会被定时刷新反复调用，不去重就是每个刷新周期刷屏；
     * `bindPermission` 的主体是**调用方包名**，基数不可控，没有预算就是内存/日志双泄漏。
     */
    private fun observe(ctx: HookContext, site: String, subject: String, detail: String) {
        if (!observedOnce.add(site + ':' + subject)) return
        val used = observeLogUsed.getAndIncrement()
        when {
            used < OBSERVE_LOG_BUDGET ->
                ctx.log(Log.INFO, LOG_PREFIX + "准入观测 $site $subject → $detail")
            used == OBSERVE_LOG_BUDGET ->
                ctx.log(
                    Log.INFO,
                    LOG_PREFIX + "准入观测日志已达预算（$OBSERVE_LOG_BUDGET 条），后续不再逐条记录",
                )
            // 超出预算：什么都不做（不构造、不记录）
        }
    }

    // ── 证据 ────────────────────────────────────────────────────────────────

    /**
     * 注入成功后的**唯一**记录点。两件事：
     *   1. 计数（进 [InstallReport.notes]，见 [LiveNotes]）；
     *   2. 每个「路径×组件」只打一条日志，且有总预算。
     *
     * 只在 parcel **已经成功写出之后**调用，所以「日志里有这一条」严格等价于
     * 「那个 parcel 里真的带着 `miuiWidget=true`」。这条纪律是本轮的重点：
     * `hooked=N` 只证明钩子装上了，证明不了注入了没有 —— 上一轮的 ❌ 就是这么来的。
     */
    private fun noteInjection(swap: Swap) {
        val total = nInjected.incrementAndGet()
        nByPath.incrementAndGet(swap.path.ordinal)
        // 去重键按「路径×组件」：同一个组件走两条 hook 是两条独立证据，不该互相压掉。
        if (!injectedOnce.add(swap.path.label + ':' + swap.desc)) return
        val used = injectLogUsed.getAndIncrement()
        val ctx = ctxRef
        when {
            used < INJECT_LOG_BUDGET -> ctx?.log(
                Log.INFO,
                LOG_PREFIX + "已注入 ${swap.path.label} 的 miuiWidget=true → ${swap.desc}" +
                    "（累计 $total 次 / 去重 ${injectedOnce.size} 个）",
            )
            used == INJECT_LOG_BUDGET -> ctx?.log(
                Log.INFO,
                LOG_PREFIX + "注入日志已达预算（$INJECT_LOG_BUDGET 条），后续不再逐条记录。" +
                    "总账：${counts()}",
            )
            // 超出预算：不记（计数继续走，[LiveNotes] / 卸载总账里仍有）
        }
    }

    // ── 门控（热路径）──────────────────────────────────────────────────────

    /** 把 metaData 换成带 `miuiWidget=true` 的副本；返回用于还原的句柄。 */
    private fun mark(obj: Any?, path: Path): Swap? {
        if (obj == null) return null
        // 一份快照走完整个判断：既是「未启用就早退」的粗筛，也是按包判断的依据。
        // 共用同一份快照（而不是「粗筛读一次、按包再读一次」）有两个好处：
        // 少一次时钟读；并排除「两次读之间配置被换掉」的错配。
        // `any()` 为假时（白名单空 + 没开 system_wide）**什么都不做**，这是默认态。
        val c = freshConfig()
        if (!c.any()) return null
        return when (obj) {
            is ActivityInfo -> {
                // 先做最便宜、判别力最强的一步：不是 widget provider 的 ActivityInfo 直接走掉
                if (obj.metaData?.containsKey(APPWIDGET_KEY) != true) return null
                if (!c.hit(obj.packageName)) return null
                swapMetaData(obj, path, obj.packageName + "/" + (obj.name ?: "?"))
            }
            is AppWidgetProviderInfo -> {
                val cn: ComponentName = obj.provider ?: return null
                if (!c.hit(cn.packageName)) return null
                val ai = providerInfoOf(obj) ?: return null
                swapMetaData(ai, path, cn.flattenToShortString())
            }
            else -> null
        }
    }

    private fun swapMetaData(ai: ActivityInfo, path: Path, desc: String): Swap {
        val original = ai.metaData
        val copy = if (original == null) Bundle() else Bundle(original)
        copy.putBoolean(KEY, true)
        ai.metaData = copy
        return Swap(ai, original, path, desc)
    }

    /** AppWidgetProviderInfo 里的 ActivityInfo：字段名随版本变，多候选取。 */
    private fun providerInfoOf(info: AppWidgetProviderInfo): ActivityInfo? {
        for (name in arrayOf("providerInfo", "activityInfo", "mProviderInfo")) {
            try {
                val f: Field = info.javaClass.getDeclaredField(name)
                f.isAccessible = true
                (f.get(info) as? ActivityInfo)?.let { return it }
            } catch (_: Throwable) {
                // 试下一个候选
            }
        }
        return try {
            info.activityInfo
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 门控判断。**热路径：零分配、不阻塞、不抛异常。**
     *
     * 读配置失败（[readConfig] 返回 [Config.OFF]）时 [config] 全为关 → 一律「未命中」。
     * 这是刻意的 **fail-closed**：读不到白名单就**不放行**，
     * 绝不因为读不到配置而放宽准入（规范 §9 的同一原则）。
     *
     * 一次「读到半新半旧」的错配（读 [config] 与读 `systemWide`/`targets` 之间配置被换掉）
     * 在结构上被排除了 —— 两个字段打包在同一个不可变 [Config] 里、只有一个 volatile 引用，
     * 所以热路径拿到的永远是一份**完整的**快照。
     */
    private fun isTarget(pkg: String?): Boolean = freshConfig().hit(pkg)

    /**
     * 取一份配置快照。**热路径上就是一次 `uptimeMillis()` + 一次 volatile 读**
     * （[reloadIfStale] 在节流窗口内直接返回，不做任何分配）。
     */
    private fun freshConfig(): Config {
        reloadIfStale()
        return config
    }

    // ── 白名单懒读 ──────────────────────────────────────────────────────────

    /**
     * 热路径上的节流入口：**绝大多数调用只花一次 `uptimeMillis()` + 一次 volatile 读**。
     *
     * 这是**兜底**路径（主路径是 [registerConfigListener] 的推送，见那里的注释）：
     * 监听没注册上、或配置不是走 `SharedPreferences` 正常通道写的时，靠它保证
     * 「最多 3 秒也会生效」，而不是「必须重启整机」。
     *
     * 为什么不是「每次调用都读」：`Prefs` 背后是框架的 `RemotePreferences`，
     * 一次读可能是一次**跨进程调用**，而这里挂在 `ActivityInfo.writeToParcel` 上 ——
     * system_server 最热的路径之一。对 hook 回调的纪律就是「禁跨进程」，
     * 所以这次读被压到「每 ≥3 秒、且至多一个线程」，而不是每次调用。
     *
     * 为什么不用 `synchronized`（HyperStack 的做法）：它读的是本地文件（微秒级），
     * 我们读的可能是 Binder。让别的 binder 线程排队等这次读，等于把一次远程读的延迟
     * 摊到整个 system_server 上。所以这里用**单赢家**：只有一个线程真的去读，
     * 其余线程**直接用当前值继续**，不阻塞、不分配 —— 每 3 秒最多只有一个 binder 线程
     * 会在这里等一下，而 binder 线程本来就是用来等跨进程调用的。
     * 代价是这次读进行中的其它线程看到的是最多 3 秒前的配置 —— 这个窗口不会放宽准入：
     * 能注入的唯一条件是「读了新配置」或「读了旧配置」，两者各自都满足白名单要求，
     * 不存在「新配置不该放行、旧配置也不该放行，却注入了」的组合。
     *
     * 读失败也不是问题：那时 [readConfig] 返回 [Config.OFF]（全关），
     * 是比之前**更严**的状态，不是更松。宁可少注入，不可错注入。
     */
    private fun reloadIfStale() {
        val now = SystemClock.uptimeMillis()
        if (now - lastReloadMs < RELOAD_INTERVAL_MS) return
        // 单赢家：拿不到就说明有别的线程正在读，本次直接用当前值。
        if (!reloading.compareAndSet(false, true)) return
        try {
            lastReloadMs = now
            val next = readConfig()
            val prev = config
            config = next
            if (next.systemWide != prev.systemWide || next.targets != prev.targets) {
                // 值真的变了才记。这一条就是「改完不用重启」的证据。
                // 它在 parcel 路径上，但每 ≥3 秒最多发生一次（且只在变化时），不是热路径。
                ctxRef?.log(Log.INFO, LOG_PREFIX + "配置已热更新（无需重启）：${describe(next)}")
            }
        } catch (_: Throwable) {
            // system_server：绝不逃逸。readConfig 自己已经兜了一层，这里是第二层。
        } finally {
            reloading.set(false)
        }
    }

    /** install 时立即读一次并发布（此时还没有热路径的并发，直接写即可）。 */
    private fun applyConfigImmediately(ctx: HookContext) {
        nReloads.incrementAndGet()
        config = readConfig()
        lastReloadMs = SystemClock.uptimeMillis()
        ctx.log(Log.INFO, LOG_PREFIX + "配置(install)：" + describe(config))
    }

    /**
     * 注册配置变化监听（**推送**路径）。这是「改完立刻生效」的主路径。
     *
     * 为什么非要它、而不是只靠 [reloadIfStale] 的轮询：
     *   硬纪律是「hook 回调跑在调用线程上（inline）→ 禁 IO、禁网络、**禁跨进程**」。
     *   而门控判断正是跑在 hook 回调里（`ActivityInfo.writeToParcel`）。轮询虽然节流到 ≥3 秒一次，
     *   终究还是把一次「可能是跨进程」的配置读放在了 parcel 路径上。监听回调则完全不同：
     *   它跑在框架的配置回调线程上，与 parcel 无关 —— 那里读配置是安全的。
     *
     * 于是两条路各自负责一件事：
     *   · 推送（这里）：**改完立刻生效**，且热路径上零跨进程读；
     *   · 轮询（[reloadIfStale]）：兜底。监听注册失败、或写入方不走 `SharedPreferences`
     *     的正常通道时，保证「最多 3 秒也会生效」，而不是「必须重启整机」。
     *
     * 注册失败只是降级，不是失败：记一条日志（看得见），特性照常工作。
     *
     * ⚠️ 已知的理论窗口：如果一次轮询读**开始于**变更之前、**写回于**这次推送之后，
     * 它会用旧值把新值盖掉，直到下一次轮询（≤3 秒）自愈。两个写入方写的都是
     * 「同一个源读出来的完整快照」，所以最坏情况是**短暂回到旧配置**，不会半新半旧。
     */
    private fun registerConfigListener(ctx: HookContext) {
        val store = ctx.store
        if (store == null) {
            ctx.log(Log.INFO, LOG_PREFIX + "没有原始配置存储入口（store=null），只走 ≥3s 懒读")
            return
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 只认本特性的键：别的特性的配置变化与本注入器无关，不为了它去读一次配置。
            if (key == null || !key.startsWith(WIDGET_KEY_PREFIX)) {
                return@OnSharedPreferenceChangeListener
            }
            try {
                nReloads.incrementAndGet()
                val next = readConfig()
                config = next
                lastReloadMs = SystemClock.uptimeMillis()
                ctx.log(Log.INFO, LOG_PREFIX + "配置已更新（推送 key=$key）：${describe(next)}")
            } catch (_: Throwable) {
                // 配置回调线程上也不能抛
            }
        }
        try {
            store.registerOnSharedPreferenceChangeListener(listener)
            changeListener = listener
            ctx.log(Log.INFO, LOG_PREFIX + "已注册配置变化监听：改完立刻生效，不必等懒读")
        } catch (t: Throwable) {
            ctx.log(Log.WARN, LOG_PREFIX + "注册配置变化监听失败，退回 ≥3s 懒读", t)
        }
    }

    /**
     * 读一次配置。**任何异常都不能逃逸**（system_server），失败一律回 [Config.OFF]（fail-closed）。
     *
     * 关于「读失败」和「用户把白名单清空」在这里是**同一个结果**（都 → 全关）：
     * [io.github.kotoriinsky.tsuzuri.core.Prefs] 的契约就是「读失败回退默认值」，
     * 从它的接口上区分不出这两种情况。两者都是 fail-closed 的方向，所以按同一条路处理 ——
     * 代价是一次瞬时读失败会让注入暂停到下一次读成功为止，这是刻意选的（宁可少注入）。
     */
    private fun readConfig(): Config {
        val ctx = ctxRef ?: return Config.OFF
        return try {
            val wide = ctx.prefs.rawBoolean(Catalog.MIUI_WIDGET_SYSTEM_WIDE_KEY, false)
            val set = ctx.prefs.rawStringSet(Catalog.MIUI_WIDGET_PACKAGES_KEY)
            // 拷一份：`SharedPreferences.getStringSet` 可能把**存储内部的那个 Set 实例**
            // 交出来（契约见 core `Prefs` 关于 AppList 的注释），而 [config] 会被任意
            // binder 线程读到 —— 不能让热路径去读一个别人也持有的可变集合。
            // 这个拷贝每 ≥3 秒最多一次，与热路径无关。
            Config(wide, if (set.isEmpty()) emptySet() else HashSet(set))
        } catch (_: Throwable) {
            // 兜住「读配置」这件事的一切失败：读不到 = 什么都没配 = 什么都不做。
            Config.OFF
        }
    }

    // ── 描述 / 计数 ─────────────────────────────────────────────────────────

    /** 配置源说明：直接写进日志，免得看日志的人以为它还是「读一次」的老实现。 */
    private fun configSourceNote(): String =
        "（RemotePreferences：变化监听推送 + 兜底懒读 ≥${RELOAD_INTERVAL_MS / 1000}s）"

    private fun describe(c: Config): String =
        "systemWide=${c.systemWide}, targets=${c.targets.size}" + namesOf(c.targets)

    /** 把白名单包名读出来（有上限）。上一轮「看不到白名单里有没有那个包」就卡在这。 */
    private fun namesOf(targets: Set<String>): String {
        if (targets.isEmpty()) return ""
        val head = targets.take(LOG_MAX_NAMES)
        val tail = if (targets.size > head.size) ",…" else ""
        return " [" + head.joinToString(",") + tail + "]"
    }

    private fun counts(): String = buildString {
        append("total=").append(nInjected.get())
        append(", distinct=").append(injectedOnce.size)
        for (p in Path.entries) {
            append(", ").append(p.label).append('=').append(nByPath.get(p.ordinal))
        }
        append(", reloads=").append(nReloads.get())
        append(", logs=").append(injectLogUsed.get()).append('/').append(INJECT_LOG_BUDGET)
        append(", observeLogs=").append(observeLogUsed.get()).append('/').append(OBSERVE_LOG_BUDGET)
    }

    // ── 类型 ────────────────────────────────────────────────────────────────

    /**
     * 三条注入路径。
     *
     * 把「路径」做成类型而不是字符串常量，是为了让日志里的路径标签**不可能写错**：
     * 日志的全部价值就在于能分清注入是走 `ActivityInfo`、`AppWidgetProviderInfo`
     * 还是 `ApplicationInfo` 出去的 —— 上一轮正是分不清，才无法判断
     * 「注了上万次」是发生在哪条路上。
     */
    private enum class Path(val label: String) {
        ACTIVITY_INFO("ActivityInfo"),
        PROVIDER_INFO("AppWidgetProviderInfo"),
        APPLICATION_INFO("ApplicationInfo"),
    }

    /**
     * 一次配置读取的快照。
     *
     * **故意做成不可变对象 + 单个 volatile 引用**（而不是两个 `@Volatile` 字段）：
     *  - 热路径上只读一个 volatile，且零分配；
     *  - 从结构上排除「`systemWide` 是新的、`targets` 是旧的」这种半新半旧的错配
     *    （两个字段分开读就一定存在这个窗口，而这是个判据，漂移是不可接受的）。
     */
    private class Config(val systemWide: Boolean, val targets: Set<String>) {
        /** 有任何一个包可能命中吗？用最便宜的方式早退（热路径第一问）。 */
        fun any(): Boolean = systemWide || targets.isNotEmpty()

        /**
         * 这个包命中吗？`contains` 对 `HashSet`/`EmptySet` 都不分配。
         *
         * 入参**必须**保持可空：`ActivityInfo.packageName` 是平台类型，理论上可以为 null，
         * 而这里返回 false 是唯一正确的处置 —— 绝不能让它在这里变成 NPE 逃进 system_server。
         */
        fun hit(pkg: String?): Boolean {
            if (pkg == null) return false
            return systemWide || targets.contains(pkg)
        }

        companion object {
            /** 读不到配置时的唯一状态：全关，什么都不做。**fail-closed**。 */
            val OFF = Config(false, emptySet())
        }
    }

    /**
     * 一次「临时换掉 metaData」的句柄。
     *
     * `target` 用 `Any` 是有意的：`ActivityInfo` 与 `ApplicationInfo` 没有公共的
     * 「可写 metaData」类型，为了共用同一个还原路径（以及同一份注入日志）才这么做。
     * 还原只发生在**命中**路径上，多一次类型判断的成本可以忽略。
     *
     * `path` / `desc` 只用于日志，但放在这里而不是在调用处拼 ——
     * 「注入了哪个组件」必须在**同一处**决定，否则以后加了新的注入点就会漏掉日志。
     */
    private class Swap(
        private val target: Any,
        private val original: Bundle?,
        val path: Path,
        val desc: String,
    ) {
        fun restore() {
            try {
                when (target) {
                    is ActivityInfo -> target.metaData = original
                    is ApplicationInfo -> target.metaData = original
                }
            } catch (_: Throwable) {
                // 还原失败没有补救手段，也不能因此抛出去（system_server）
            }
        }
    }

    /**
     * 安装报告里的备注（`InstallReport.notes`）。
     *
     * ⚠️ 为什么是**活的**而不是 `listOf(...)`：注入计数在 `install()` 返回**之后**才产生，
     * 而 `InstallReport` 是不可变的。用快照的话报告里永远是「装了 N 个 hook、注了 0 次」——
     * 一个**误导性的零**，比没有更糟（上一轮的 ❌ 就是被这种零喂出来的）。
     * 做成随取随算的固定长度视图后，谁在读、什么时候读，拿到的都是当时的真实计数。
     *
     * `size` 恒定是刻意的：并发读（诊断页可能从别的线程读）不会越界，
     * 也不需要锁 —— 每次 `get` 只是去读几个 Atomic，最坏情况是两次读之间计数变了。
     */
    private object LiveNotes : AbstractList<String>() {
        override val size: Int get() = 2

        override fun get(index: Int): String = when (index) {
            0 -> "config(" + describe(config) + ")"
            1 -> "evidence(" + counts() + ")"
            else -> throw IndexOutOfBoundsException("index=$index, size=$size")
        }
    }
}
