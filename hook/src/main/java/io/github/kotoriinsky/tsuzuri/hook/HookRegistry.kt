package io.github.kotoriinsky.tsuzuri.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.DrawerSignal
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.RestartPolicy
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.hook.feature.drawersignal.LauncherDrawerObserver
import io.github.kotoriinsky.tsuzuri.hook.feature.miuiwidget.MiuiWidgetFlagInjector
import io.github.kotoriinsky.tsuzuri.hook.feature.multifreeform.MultiFreeformWhitelist
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.hook.feature.passcodeisland.PasscodeIslandPresenter
import io.github.kotoriinsky.tsuzuri.hook.feature.passcodeisland.PasscodeIslandWatcher
import io.github.kotoriinsky.tsuzuri.hook.feature.copydirect.CopyDirectEngine
import io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion.AppSuggestion
import io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion.DimTouchObserver
import io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion.ScreenOnSuggestion
import io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion.ScreenOnStatus
import io.github.kotoriinsky.tsuzuri.hook.feature.aggpayment.AggregatePayment
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SwipeEventHub
import io.github.kotoriinsky.tsuzuri.hook.feature.pawidget.PaWidgetAdmission
import io.github.kotoriinsky.tsuzuri.hook.feature.pawidget.PaWidgetDetailInject
import io.github.kotoriinsky.tsuzuri.hook.feature.pawidget.PaWidgetPickerInject
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalAppCompat
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalMediaCapture
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalImageActions
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalImageRegion
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalPressSnapshotTrigger
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalCatcherShadow
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalCapsuleStyle
import io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalCapsuleFilter
import io.github.kotoriinsky.tsuzuri.hook.feature.screenrecognition.RecognitionBubbleStyle
import io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition.GlobalRecognitionHost
import io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition.GlobalRecognitionTrigger
import io.github.kotoriinsky.tsuzuri.hook.feature.shareorder.ShareOrder
import io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion.ListenPickerFilter
import io.github.kotoriinsky.tsuzuri.hook.feature.cleanstatusbar.CleanStatusBarFeature
import io.github.kotoriinsky.tsuzuri.hook.feature.cleanstatusbar.CleanStatusBarScreenshot
import io.github.kotoriinsky.tsuzuri.hook.feature.sideindex.SideIndex
import io.github.kotoriinsky.tsuzuri.hook.feature.texttoolbar.TextToolbarAiOff
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.CornerSmallWindow
import io.github.kotoriinsky.tsuzuri.hook.feature.widgetstyle.WidgetUnifiedStyle
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 把「当前在哪个进程」翻译成「该装哪些特性」。
 *
 * 分发表是 [io.github.kotoriinsky.tsuzuri.core.Catalog] 里每个特性的 `scope` 字段 ——
 * 加特性不用改这里。
 *
 * 为什么要按**进程名**而不是包名分发：一个进程可能加载多个包（SystemUI 会加载插件包），
 * 而 `onPackageReady` 是按包回调的。按进程名筛 + 幂等标记，才能做到「一个进程装一次」。
 */
object HookRegistry {

    private const val TAG = "FP-Registry"

    /** 后端全部特性的实现。没实现的特性不在这里出现。 */
    private val all: List<Feature> = listOf(
        // ⚠️ 顺序 = 事件优先级。SwipeEventHub 按注册顺序分发，
        // 小窗必须排在侧滑索引前面 —— 在底部角落开始的手势归小窗。
        CornerSmallWindow,
        SideIndex,
        AggregatePayment,
        PaWidgetAdmission,
        PaWidgetPickerFeature,
        WidgetUnifiedStyle,
        MiuiWidgetFlagInjector,
        // 与手势无关，所以放最后 —— 不影响 SwipeEventHub 的优先级
        PortalAppCompat,
        PortalMediaCapture,
        PortalImageActions,
        PortalImageRegion,
        // ↑↓ 识屏三类（SCREEN_TEXT / SCREEN_IMAGE / RECOGNITION_LOOK）由这些实现分担：
        // 各自用 supportsProcess 限定进程、wanted 按自己负责的子项判断装不装、
        // installId 区分同进程的多份实现（见 Feature.installId）。
        // PortalPressSnapshotTrigger 只在 SystemUI；它在 contentextension 的另一半由 PortalImageRegion 一起装。
        PortalPressSnapshotTrigger,
        PortalCatcherShadow,
        PortalCapsuleStyle,
        PortalCapsuleFilter,
        RecognitionBubbleStyle,
        // 全局识别：SystemUI 受理磁贴、传送门截屏开窗，同一个键按进程各装一份
        GlobalRecognitionTrigger,
        GlobalRecognitionHost,
        MultiFreeformWhitelist,
        // 口令上岛是双进程特性（system_server 观察剪贴板 / SystemUI 代发岛通知），
        // 两个实现共用 PASSCODE_ISLAND 一个键 —— HookRegistry 按进程名筛 scope，
        // 每个进程只装属于自己的那一个，互不相见。
        PasscodeIslandServerFeature,
        PasscodeIslandUiFeature,
        CopyDirectEngine,
        AppSuggestion(Catalog.CONTEXT_INSTALL),
        AppSuggestion(Catalog.CONTEXT_MUSIC),
        ListenPickerFilter,
        DimTouchObserver,
        ScreenOnSuggestion,
        ScreenOnStatus,
        ShareOrder,
        TextToolbarAiOff,
        CleanStatusBarFeature,
        CleanStatusBarScreenshot,
    )

    /**
     * [PaWidgetPickerInject] 的最小外壳。
     *
     * 它的实现文件刻意**不继承 `Feature`**（派工纪律：`Catalog` / `HookRegistry` 这类接线文件由主代理独占，
     * agent 不碰）—— 所以开关 / 作用域 / 卸载在这里接上，逻辑一行都不在这里。
     * 同一个开关装两段：列表注入（看得见）+ [PaWidgetDetailInject] 详情注入（点进去能加）。
     * 两段缺一不可，所以不拆成两个开关。
     */
    private object PaWidgetPickerFeature : Feature(Catalog.require(Catalog.PA_WIDGET_PICKER_INJECT)) {
        override fun install(ctx: HookContext): InstallReport {
            val list = PaWidgetPickerInject.install(ctx)
            val detail = PaWidgetDetailInject.install(ctx)
            return InstallReport(
                list.hooked + detail.hooked,
                list.missing + detail.missing,
                notes = list.notes + detail.notes + "list=${list.hooked} detail=${detail.hooked}",
            )
        }

        override fun uninstall() {
            PaWidgetDetailInject.uninstall()
            PaWidgetPickerInject.uninstall()
            super.uninstall()
        }
    }

    /**
     * [PasscodeIslandWatcher] 的外壳：只在 system_server 生效
     * （spec.scope 是键级的双进程合集，进程亲和由 [Feature.supportsProcess] 收窄）。
     */
    private object PasscodeIslandServerFeature : Feature(Catalog.require(Catalog.PASSCODE_ISLAND)) {
        override fun supportsProcess(processName: String, isSystemServer: Boolean) = isSystemServer

        override fun install(ctx: HookContext): InstallReport = PasscodeIslandWatcher.install(ctx)

        override fun uninstall() {
            PasscodeIslandWatcher.uninstall()
            super.uninstall()
        }
    }

    /**
     * [PasscodeIslandPresenter] 的外壳：只在 SystemUI 生效（进程名显式限定 ——
     * 「非 system_server」不够，其他 uid-1000 进程会漏进来）。
     * 它没有方法 hook，一个「注册成功的接收器」就是它的一份安装量。
     */
    private object PasscodeIslandUiFeature : Feature(Catalog.require(Catalog.PASSCODE_ISLAND)) {
        override fun supportsProcess(processName: String, isSystemServer: Boolean) =
            !isSystemServer && processName == PasscodeIsland.SYSTEM_UI_PACKAGE

        override fun install(ctx: HookContext): InstallReport = PasscodeIslandPresenter.install(ctx)

        override fun uninstall() {
            PasscodeIslandPresenter.uninstall()
            super.uninstall()
        }
    }

    /** 本进程已安装的特性 key。防止多包回调 / 热重载重复安装。 */
    private val installed = ConcurrentHashMap.newKeySet<String>()

    /**
     * 抽屉观察器在 [installed] / [reports] 里的 key。
     *
     * 带 `infra:` 前缀是刻意的：它不是 `Catalog` 里的特性，两个集合里都混着特性 key，
     * 前缀让「哪个是特性、哪个是基础设施」在诊断页与日志里一眼可分。
     */
    private const val KEY_DRAWER_OBSERVER = "infra:drawer_observer"

    /** 最近一次安装结果，供诊断读取。 */
    private val reports = ConcurrentHashMap<String, InstallReport>()

    private var prefsRef: SharedPreferences? = null

    /**
     * 本进程的框架入口。
     *
     * 为什么需要这个进程级入口：框架的**共享数据目录**（`/data/adb/lspd/modules/<user>/<包>/files/`）
     * 只能通过 `XposedInterface.openRemoteFile` / `listRemoteFiles` 读写，而这两个方法在
     * `XposedModule` 实例上。扇形的自定义图标要读那个目录（用户从相册选的图由设置界面写进去），
     * 而它的入口 `FanTargets.build(context)` 拿不到 module —— 与 [prefs] 完全同一条理由，
     * 给它一个进程级入口，而不是去改调用点的签名（那个文件有另一个协作者在改）。
     */
    @Volatile
    private var moduleRef: XposedModule? = null

    /** 注册监听时用的那个 store（[refreshPrefs] 会换掉 [prefsRef]，注销必须找回原对象）。 */
    @Volatile
    private var listenStore: SharedPreferences? = null
    @Volatile
    private var dirtyListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile
    private var hotListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile
    private var hotLocal: Map<String, List<Feature>> = emptyMap()
    @Volatile
    private var hotCtx: HookContext? = null

    /**
     * 「收到的变更通知还没被读路径消费」。
     *
     * 用标记 + 懒重取，而不是在通知里直接重取：拖一次滑杆会产生几十次通知
     * （`onValueChange` 每帧都写），每次通知都跨进程取一遍配置是纯浪费。
     */
    @Volatile
    private var prefsDirty = false

    /**
     * 安装当前进程该有的全部特性。
     *
     * @param processName 当前进程名（`onModuleLoaded` 给的那个）
     * @return key → 安装报告
     */
    fun installForProcess(
        module: XposedModule,
        classLoader: ClassLoader,
        processName: String,
        isSystemServer: Boolean,
    ): Map<String, InstallReport> {
        val store = try {
            module.getRemotePreferences(Keys.GROUP)
        } catch (t: Throwable) {
            // 框架不给（embedded / 版本不符）→ 空配置，全部走默认值 = 全部关闭。
            module.log(Log.WARN, TAG, "getRemotePreferences failed, falling back to defaults", t)
            null
        }
        prefsRef = store
        moduleRef = module

        // ── 变更通知 → 标脏，下次读配置时重取快照 ────────────────────────────────
        // **为什么必须有这一段**（2026-09-17 实测，两条独立证据）：
        //  · 界面里把「垂直位置」拖到 1.9% 之后，hook 读到的仍是 47（默认值）；
        //    **重启 SystemUI 之后**才读到 1.9。
        //  · 界面里把「自定义位置」打开之后，hook 仍然按 `window_custom=off` 摆放
        //    （日志原文），而界面上那一项是开的。
        // 两次都收到了变更通知（`CornerSmallWindow` 会打「配置已重算」），
        // 所以缺的不是通知，而是**值**：`getRemotePreferences()` 返回的对象把配置
        // 缓存在自己的 map 里，之后就再也不刷新了。
        //
        // 后果是所有「改完立即生效」的承诺都不成立：用户拖完滑杆看不到变化，
        // 只能认为参数坏了 —— 这正是反复出现的「调了没反应」的真身。
        //
        // 这里只把通知当**脏标记**：监听器仍然挂在原来的对象上（框架通知的就是它），
        // 各特性的监听照常工作；读路径（[prefs]）在脏了之后重取一次。
        //
        // 只注册**一次**：本函数每加载一个包都会再走一遍，不加保护监听会成倍增长。
        // 监听存进字段 —— 既是为了在 [uninstallAll] 里注销，也防止框架若弱引用持有时被 GC。
        if (store != null && dirtyListener == null) runCatching {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null) return@OnSharedPreferenceChangeListener
                val wasDirty = prefsDirty
                prefsDirty = true
                // 只在第一次标脏时重取 + 记一行：拖一次滑杆会产生几十次通知，
                // 每次都重取既没必要，也会把日志刷满。
                if (!wasDirty) {
                    val before = runCatching { store.all[key] }.getOrNull()
                    refreshPrefs()
                    val after = runCatching { prefsRef?.all?.get(key) }.getOrNull()
                    module.log(
                        Log.INFO,
                        TAG,
                        "配置快照已重取：$key 旧=$before 新=$after 可刷新=${before != after}",
                    )
                }
            }
            store.registerOnSharedPreferenceChangeListener(listener)
            dirtyListener = listener
            listenStore = store
        }

        val prefs = Prefs(store?.let { SharedPrefsStore(it) })
        val ctx = HookContext(module, classLoader, processName, prefs, store)

        val mine = all.filter { f ->
            val self = if (isSystemServer) ScopeTarget.SYSTEM_SERVER else null
            val scopeMatch = f.spec.scope.any { target ->
                target == self || (!isSystemServer && (target.packageName == processName ||
                    (target == ScopeTarget.AI_ENGINE && io.github.kotoriinsky.tsuzuri.core.CopyDirect.supportsEngineProcess(processName)) ||
                    // 分享面板跑在子进程 `com.android.intentresolver:chooser`（2026-10-26 真机 ps 实测），
                    // 按包名精确匹配永远对不上。只给这一个作用域放行子进程，不改全局规则。
                    (target == ScopeTarget.SHARE_SHEET && processName.startsWith(target.packageName + ":"))))
            }
            // spec.scope 只筛「键」，双进程特性的多份实现在这里再分一道进程亲和
            scopeMatch && f.supportsProcess(processName, isSystemServer)
        }

        // system_server 专属的信号源**先于** `mine.isEmpty()` 那条早退安装：
        // 它们不在 Catalog 的特性表里（理由见 [systemServerInfra]），
        // 万一某天 Catalog 里一个 SYSTEM_SERVER 作用域的特性都没有，
        // 这条通路也不该跟着一起消失。
        if (isSystemServer) installSystemServerInfra(ctx)

        if (mine.isEmpty()) return emptyMap()

        module.log(
            Log.INFO, TAG,
            "process=$processName systemServer=$isSystemServer candidates=${mine.joinToString(",") { it.key }}"
        )

        for (f in mine) {
            // 按 installId 去重：同一个键可以有多份实现落在同一进程（识屏三类），见 [Feature.installId]
            val id = f.installId
            if (installed.contains(id)) {
                module.log(Log.INFO, TAG, "already installed, skip: $id")
                continue
            }
            if (!f.wanted(prefs)) {
                // 关态：**根本不装 hook**，而不是装 no-op（规范 §9）
                module.log(Log.INFO, TAG, "disabled by config, skip: $id")
                continue
            }
            val report = Feature.safeInstall(f, ctx)
            reports[id] = report
            if (report.isEffective) {
                installed.add(id)
                module.log(Log.INFO, TAG, "installed $id: ${report.describe()}")
            } else {
                // **不记入 installed**：一次「装出 0 个 hook」很可能是宿主还没就绪
                // （实测：过早的 package 回调里 ActivityThread.currentApplication() 仍是 null，
                // 于是 install 拿不到 Context 而空手而归）。记进去就等于把重试永久挡死，
                // 表现为「开关打开了但功能永远不生效」—— 最难查的一类故障。
                // 代价只是后续回调会再试一次，而回调次数是有界的。
                module.log(
                    Log.WARN, TAG,
                    "install produced nothing for $id, will retry on next callback: ${report.describe()}"
                )
            }
        }

        registerHotApply(module, ctx, mine)
        return reports.toMap()
    }

    /**
     * system_server 侧的**基础设施**组件（不是用户可见特性）。
     *
     * ## 为什么需要一条不走 Catalog 的通路
     *
     * 模块的进程闸门（上面那个 `mine`）是按 `FeatureSpec.scope` 分发的，而 `Catalog`
     * 由主代理独占，要加一条 `ScopeTarget.SYSTEM_SERVER`
     * 的特性必须动那个文件。而这里的组件**不是**一个特性：它没有界面、没有独立开关，
     * 存在的全部意义是给 `side_index` 提供「抽屉开着吗」这一条信号输入
     * （判据是 WMS 侧的窗口属性，只有 system_server 看得到，见 [LauncherDrawerObserver]）。
     *
     * ## 闸门挂在 side_index 上
     *
     *  - `f.side_index` 没开 → 观察器**根本不装**，system_server 里零开销（规范 §9）；
     *  - 开了但 `side_index.drawer_gate` = false → 也不装。
     *
     * 设备实测依据（这一条通路的必要性）：
     * `/data/adb/lspd/log/modules_*.log` 里
     * `(system)[io.github.kotoriinsky.tsuzuri,FP-Registry,...] process=system systemServer=true` —— 模块
     * 本来就注入 system_server，`HookRegistry` 在那里的分发表也确实跑过。
     */
    private fun installSystemServerInfra(ctx: HookContext) {
        // 🔍 **临时诊断**（排查「抽屉观察器为什么没信号」）。
        // 为什么放在这里、且无条件打：这个函数此前**每一条**路径都有日志
        // （未开特性 / 闸门关 / 装成功 / 装出 0 个 hook），而设备日志里一条都没有 ——
        // 那说明要么它没被调用，要么日志没落到我以为的地方。这条先定这个案，
        // 顺便把两个闸门的实际取值打出来（`isEnabled` 是否真读到了特性开关）。
        ctx.log(
            Log.INFO,
            "抽屉观察器：安装入口到达 已装=" + installed.contains(KEY_DRAWER_OBSERVER) +
                " f.side_index=" + ctx.prefs.isEnabled(Catalog.SIDE_INDEX) +
                " gate=" + ctx.prefs.rawBoolean(
                    Keys.pref(Catalog.SIDE_INDEX, DrawerSignal.DRAWER_GATE_PREF),
                    DrawerSignal.DEFAULT_DRAWER_GATE,
                ),
        )
        if (installed.contains(KEY_DRAWER_OBSERVER)) return

        if (!ctx.prefs.isEnabled(Catalog.SIDE_INDEX)) {
            ctx.log(Log.INFO, "system_server：side_index 未开启，跳过抽屉观察器（不装任何 hook）")
            return
        }
        val gateKey = Keys.pref(Catalog.SIDE_INDEX, DrawerSignal.DRAWER_GATE_PREF)
        if (!ctx.prefs.rawBoolean(gateKey, DrawerSignal.DEFAULT_DRAWER_GATE)) {
            ctx.log(Log.INFO, "system_server：$gateKey=false，跳过抽屉观察器（不装任何 hook）")
            return
        }

        val report = try {
            LauncherDrawerObserver.install(ctx)
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "system_server：抽屉观察器安装崩溃，本次没有抽屉信号", t)
            InstallReport(0, notes = listOf("exception=" + t.javaClass.simpleName))
        }
        reports[KEY_DRAWER_OBSERVER] = report
        if (report.isEffective) {
            installed.add(KEY_DRAWER_OBSERVER)
            ctx.log(Log.INFO, "installed $KEY_DRAWER_OBSERVER: ${report.describe()}")
        } else {
            // 与特性同一条纪律：装出 0 个 hook 不能当成「装上了」。
            // 这里**没有重试机会**（system_server 只有一个安装回调），所以这条日志是
            // 「抽屉门控为什么没生效」的唯一线索，必须是 WARN 而不是 INFO。
            ctx.log(
                Log.WARN,
                "install produced nothing for $KEY_DRAWER_OBSERVER（system_server 侧不会重试）：" +
                    report.describe(),
            )
        }
    }

    /**
     * 配置变化时对 [RestartPolicy.HOT] 的特性热应用。
     *
     * 必须显式重装 —— hook 链不会因为配置变了自动重算（规范 §9）。
     * 只处理 HOT：需要重启进程的特性在这里什么都不做，由前端提示用户。
     */
    private fun registerHotApply(module: XposedModule, ctx: HookContext, mine: List<Feature>) {
        val store = listenStore ?: prefsRef ?: return
        // ⚠️ 只认**本进程的候选**（已按 scope + supportsProcess 筛过的 mine）。
        // 2026-10-26 真机：原来按全局 byKey 查，打开一个 SystemUI 特性时，它被热装进了
        // system_server、小爱、aicr —— 每个注入了模块的进程都装一份（dumpsys activity broadcasts 里
        // 那几个进程都挂着它的接收器）。所有 HOT 特性都受影响。
        // 一个键可以有多份实现（识屏三类等），所以按键分组，逐个按 installId 处理。
        hotLocal = mine.groupBy { it.key }
        hotCtx = ctx
        // 只注册一次；之后的调用只刷新上面两个字段。
        if (hotListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || !key.startsWith("f.")) return@OnSharedPreferenceChangeListener
            val featureKey = key.removePrefix("f.")
            val impls = hotLocal[featureKey] ?: return@OnSharedPreferenceChangeListener
            val c = hotCtx ?: return@OnSharedPreferenceChangeListener
            for (f in impls) {
                if (f.spec.restart != RestartPolicy.HOT) continue
                // 必须与初装同一个键（installId），否则多实现特性关不掉、开时重复装
                val id = f.installId
                try {
                    val want = f.wanted(c.prefs)
                    val has = installed.contains(id)
                    when {
                        want && !has -> {
                            val report = Feature.safeInstall(f, c)
                            reports[id] = report
                            if (report.isEffective) {
                                installed.add(id)
                                module.log(Log.INFO, TAG, "hot-applied on: $id: ${report.describe()}")
                            } else {
                                module.log(Log.WARN, TAG, "hot-apply produced nothing for $id: ${report.describe()}")
                            }
                        }
                        !want && has -> {
                            f.uninstall()
                            installed.remove(id)
                            reports.remove(id)
                            module.log(Log.INFO, TAG, "hot-applied off: $id")
                        }
                    }
                } catch (t: Throwable) {
                    module.log(Log.ERROR, TAG, "hot apply failed for $id", t)
                }
            }
        }
        try {
            store.registerOnSharedPreferenceChangeListener(listener)
            hotListener = listener
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "registerOnSharedPreferenceChangeListener failed", t)
        }
    }

    /** 卸载本进程装过的一切。给诊断用的「重启前清理」留口子。 */
    fun uninstallAll() {
        for (f in all) {
            try {
                f.uninstall()
            } catch (_: Throwable) {
            }
        }
        // system_server 侧的信号源（不在 `all` 里，所以上面那个循环碰不到它）
        try {
            LauncherDrawerObserver.uninstall()
        } catch (_: Throwable) {
            // 卸不掉没有补救手段。它只是个只读观察器 + 一条广播，留着不会出事 ——
            // **不能因此抛出去**，system_server 里逃出去的异常是整个系统重启。
        }
        // 前台应用轮询在这里统一停 —— 它是两个手势特性共用的单例，
        // 让任何一个特性自己停都会把另一个弄坏，只有「全卸」这个时机是安全的。
        try {
            SystemUiEnv.ForegroundTracker.stop()
        } catch (_: Throwable) {
            // 停不掉也没有补救手段；它只是个只读轮询，留着不会出事
        }
        // 手势链表里的 proxy：不摘掉的话热重载后旧代码仍在输入线程上跑
        try {
            SwipeEventHub.uninstall()
        } catch (_: Throwable) {
        }
        // 配置监听：不注销的话旧代码的 hot-apply 仍挂在 RemotePreferences 上
        listenStore?.let { st ->
            dirtyListener?.let { runCatching { st.unregisterOnSharedPreferenceChangeListener(it) } }
            hotListener?.let { runCatching { st.unregisterOnSharedPreferenceChangeListener(it) } }
        }
        dirtyListener = null
        hotListener = null
        listenStore = null
        hotCtx = null
        hotLocal = emptyMap()
        installed.clear()
        reports.clear()
    }

    /** 诊断快照：key → 报告。 */
    fun snapshot(): Map<String, InstallReport> = reports.toMap()

    fun installedKeys(): Set<String> = installed.toSet()

    /**
     * 本进程当前生效的**只读**配置。
     *
     * 为什么需要这个进程级入口：`FanTargets`（扇形的应用清单）要读一个
     * 「用户手挑的包名集合」，但它的入口是 `build(context)` 与
     * `Pool.composePages(recents, max)` —— 两个签名都被调用点固定住了
     * （`CornerSmallWindow` 那边有另一个协作者在改），为了多传一个 `Prefs`
     * 去改那两处签名，会把两条独立的改动搅在一起。所以这里给一个只读入口。
     *
     * 语义与「读配置失败一律回退默认值」完全一致：框架没连上时 [prefsRef] 是 null →
     * 空存储 → 每个键都读到默认值（对 AppList 而言就是空集 = 自动挑选）。
     */
    fun prefs(): Prefs {
        // 有变更通知来过 → 先把快照换新（见 [installForProcess] 里那一段的实测记录）。
        // `prefsDirty` 是 @Volatile：通知可能在 Binder 线程上来，而读发生在主线程。
        if (prefsDirty) refreshPrefs()
        return Prefs(prefsRef?.let { SharedPrefsStore(it) })
    }

    /**
     * 重新取一次框架的配置快照。
     *
     * 取不到（框架不给 / 抛异常）就**保留旧快照**：宁可读到上一次的值，
     * 也不要因为一次 Binder 抖动把配置清空 —— 那会让所有特性瞬间"全部关闭"。
     */
    @Synchronized
    private fun refreshPrefs() {
        prefsDirty = false
        val m = moduleRef ?: return
        val fresh = runCatching { m.getRemotePreferences(Keys.GROUP) }.getOrNull() ?: return
        prefsRef = fresh
    }

    /**
     * 框架入口（可能为 null：embedded / 版本不符时框架不给）。
     * 调用方必须按「拿不到就不做事」处理 —— 目前唯一的用途是读共享目录里的自定义图标。
     */
    fun module(): XposedModule? = moduleRef
}
