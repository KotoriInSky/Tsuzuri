package io.github.kotoriinsky.tsuzuri.hook.feature.passcodeisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.core.PasscodeIslandTemplate
import io.github.kotoriinsky.tsuzuri.core.PublicSuffix
import io.github.kotoriinsky.tsuzuri.core.CoverAccent
import io.github.kotoriinsky.tsuzuri.core.ExpressBadge
import io.github.kotoriinsky.tsuzuri.core.UrlCleanup
import io.github.kotoriinsky.tsuzuri.core.OEmbed
import io.github.kotoriinsky.tsuzuri.core.PageMetaExtract
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.HtmlCharset
import io.github.kotoriinsky.tsuzuri.core.AcfunShare
import io.github.kotoriinsky.tsuzuri.core.AlipayMiniProgram
import io.github.kotoriinsky.tsuzuri.core.BiliDynamicShare
import io.github.kotoriinsky.tsuzuri.core.DouyinShare
import io.github.kotoriinsky.tsuzuri.core.EcomShare
import io.github.kotoriinsky.tsuzuri.core.TiebaShare
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.KuaishouShare
import io.github.kotoriinsky.tsuzuri.core.PageTitle
import io.github.kotoriinsky.tsuzuri.core.PipixShare
import io.github.kotoriinsky.tsuzuri.core.TaobaoShellJump
import io.github.kotoriinsky.tsuzuri.core.BiliVideoShare
import io.github.kotoriinsky.tsuzuri.core.CopyDirectSamples
import io.github.kotoriinsky.tsuzuri.core.DoubanTopicShare
import io.github.kotoriinsky.tsuzuri.core.WeiboApi
import io.github.kotoriinsky.tsuzuri.core.ZhihuShare
import io.github.kotoriinsky.tsuzuri.core.ZuiyouShare
import io.github.kotoriinsky.tsuzuri.core.PasscodeLinkIdentity
import io.github.kotoriinsky.tsuzuri.core.PasscodeRules
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.FanShortcuts
import org.json.JSONObject

/**
 * 在 **SystemUI** 里接收口令信号，代发一条带 `miui.focus.param` extra 的普通通知上超级岛。
 *
 * ## 为什么是「SystemUI 代发」而不是自己发通知
 *
 * 超级岛的门禁（`NotificationSettingsManager.canShowFocusState`：每包开关 + 云端黑白名单）
 * 全部收敛在 SystemUI 进程内，而系统场景（手电筒/验证码）的岛通知就是
 * SystemUI 自己发的 —— 以 SystemUI 的身份走同一条路，**身份天然可信，完全不碰白名单**。
 *
 * ## 自动消失的三重保障
 *
 * 1. `Notification.timeoutAfter`（标准 API，超时系统自动 cancel）——
 *    岛渲染链是否尊重它 = 待验证；
 * 2. `Handler.postDelayed` 到点主动 `cancel(id)` —— 全在掌控内，是**兜底**；
 * 3. 点击后保留到超时，以便退出目标应用后仍可再次打开或分享。
 *
 * 默认 10 秒对齐 Flyme 原版（剪贴板建议超时 `w(data, 10000L)`）。
 *
 * ## 跳转
 *
 * ACTION_VIEW 先由系统确定实际目标 App；点击通知后由 SystemUI 用同一 Intent
 * 尝试小窗启动，选项不可用时普通打开。
 * 解析不到任何目标（例如淘口令但没装手淘）→ **不上岛**：弹一条点不动的岛
 * 比不弹更糟。
 */
internal object PasscodeIslandPresenter {

    private const val TAG = "FP-Passcode"

    private const val CHANNEL_ID = "tsuzuri_passcode"

    /**
     * 旧版「通知栏留痕」那条标准通知的通道（2026-10-06 ~ 10-22）。现在只发岛，
     * 留着这个 id 只为在 [ensureChannel] 里把它从系统设置中删掉。
     */
    private const val CHANNEL_ID_SHADE = "tsuzuri_passcode_shade"

    /**
     * 插件 `Const.Param.SHOW_NOTIFICATION = "show_notification"`（默认 true）。
     *
     * `FocusNotificationController:1094` 在**岛渲染完成之后**才判它：
     * ```java
     * boolean z = this.focusNotifPreHandler.onNotificationPosted(...);   // 岛在这里已经建好
     * fetchAuthResult(...);
     * if (... || !extras.getBoolean("show_notification", true)) return true;  // 只是"通知栏要不要它"
     * ```
     * 所以置 false = **焦点通知不进通知栏，岛与 AOD 不受影响**。
     *
     * 靠它把「岛」和「通知栏那一行」拆成两条通知：焦点这条隐藏，通知栏那条走系统标准模板。
     * 不拆的话，只要通知是焦点通知，SystemUI 的行绑定就拿不到标准模板
     * （`NotificationRowContentBinderImpl:1583-1595` 只认 `FocusUtils.getFocusView()`，
     * 没有 `miui.focus.rv` 就返回 null = 空白行），而且 ROM 还会主动隐藏标准边框
     * （`EXTRA_CUSTOM_HIDE_BORDER = "miui.customHideBorder"`，插件 `FocusNotifPreHandler:137`）
     * —— 那一行永远不可能像系统通知。
     */
    private const val EXTRA_SHOW_NOTIFICATION = "show_notification"

    /** focus.param 的 JSON 组装在 hook 层（`:core` 是纯 JVM 没有 org.json）。 */
    private const val EXTRA_FOCUS_PARAM = "miui.focus.param"

    /** 息屏岛 RemoteViews 的 extra 键（FocusCoordinator/AodFocusControllerV2 的登记判据）。 */
    private const val EXTRA_RV_AOD = "miui.focus.rvAod"

    // ⚠️ 焦点通知上**不放** `miui.focus.rv` / `rvNight` / `rv.island.expand`（2026-10-22 起）。
    // 插件 `FocusNotifUtils.hasCustomFocusView()` 只认 `miui.focus.rv`：有它就走自绘
    // `createCustomView`，没有才走 `TemplateFactoryV3` 官方模板链。展开态现在由 param_v2 的
    // A 区 `iconTextInfo` + E 区 `picInfo` + D 区 `textButton` 拼成官方模板 18，
    // 再塞任何一个自绘键都会把整条通知拉回自绘路径。

    /**
     * 岛图片 Bundle 与其中的图片键（图片走 `miui.focus.pics`）。
     * param_v2 里的 `pic` / `src` 按键名引用这里的 Icon parcelable ——
     * 引用了键却没给图，模板会隐藏整个图位，所以「有图才引用」。
     */
    private const val EXTRA_FOCUS_PICS = "miui.focus.pics"
    private const val KEY_ISLAND_ICON = "key_island_icon"
    private const val KEY_ISLAND_COVER = "key_island_cover"

    /** 按钮的 extra Bundle 与其中的 Action 键（对齐性能模式岛 s17 的命名）。 */
    private const val EXTRA_FOCUS_ACTIONS = "miui.focus.actions"
    private const val OPEN_ACTION_KEY = "miui.focus.action_open"
    private const val SHARE_ACTION_KEY = "miui.focus.action_share"
    private const val ACTION_OPEN_SMALL_WINDOW = "io.github.kotoriinsky.tsuzuri.action.OPEN_PASSCODE_WINDOW"
    private const val EXTRA_OPEN_ID = "notification_id"

    @Volatile
    private var appContext: Context? = null

    private var hookContext: HookContext? = null

    private var receiver: BroadcastReceiver? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 到点自动取消的定时任务（兜底 #2），uninstall 时全部撤掉。 */
    private val cancelRunnables = HashMap<Int, Runnable>()

    /** 本进程发过的通知 id，uninstall 时全部 cancel（关态 = 未安装模块）。 */
    private val notifiedIds = LinkedHashSet<Int>()
    private val resolvedIntents = LinkedHashMap<String, Intent>()
    private var copyConfigListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var copyConfigStore: android.content.SharedPreferences? = null

    /** 展示侧去重：**内容身份 → 上一次上岛的时刻**（窗口 = 那条岛的存活时长）。 */
    private val publishedIdentities = HashMap<String, Long>()

    /** 通知 id → 内容身份：岛被取消时要把身份记录一起清掉，否则「岛没了也复制不出来」。 */
    private val identityById = HashMap<Int, String>()

    /** 只保存仍在显示的岛的目标，点击广播不能从外部携带任意跳转链接。 */
    private val launchTargets = HashMap<Int, LaunchTarget>()

    /**
     * 一条岛的目标。[ambiguous] = **系统判定有歧义**（多个候选、没有唯一 handler）：
     * 此时 [intent] 故意不带包名，点击由系统弹选择器决定，并且必须走全屏
     * （选择器塞进 freeform 小窗会很怪）。判据见 [systemResolvePackage]。
     */
    /** [webUrl]：候选链里的网页地址（长按换应用时用它，App 私有 scheme 只有一个接收方）。 */
    private class LaunchTarget(val intent: Intent, val pkg: String, val ambiguous: Boolean, val webUrl: String? = null)

    /** 状态栏小图标的单色兜底；岛与通知中心只用实际目标 App 图标。 */
    private var monoBmp: android.graphics.Bitmap? = null

    /**
     * 目标 App 图标缓存：pkg → (彩色全图, 单色剪影)。null 也缓存（坏包不反复打接口）。
     * 用户拍板 2026-09-25：**所有图标位全部用目标 App 的图标**，不出现 Tsuzuri/系统图标 ——
     * 彩色图进岛/胶囊/通知中心 largeIcon，剪影进状态栏 smallIcon（原生规范：单色渲染）。
     */
    // 有界：每个包两张位图，一直装新目标会无限涨；按插入顺序淘汰，读不改结构
    private val targetIconCache = boundedMap<String, Pair<android.graphics.Bitmap?, android.graphics.Bitmap?>>(32)

    /** MIUISystemUIPlugin 的 APK 路径（桌面图标管线 maml AppIconsHelper 的宿主）。 */
    private const val MIUI_PLUGIN_APK = "/product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk"

    /** 桌面图标管线的反射入口（懒加载一次；null + tried = 探测过且不可用）。 */
    @Volatile
    private var mamlGetIconMethod: java.lang.reflect.Method? = null

    @Volatile
    private var mamlMethodTried = false

    /** 插件 APK 的 classloader（AppIconsHelper/AppIconsManager 共用），成功加载后缓存。 */
    @Volatile
    private var pluginClassLoader: ClassLoader? = null

    /**
     * 目标 App 包名 extra —— **胶囊/AOD 图标的正确通道**（2026-09-27 修正 09-25 的错误修法）。
     *
     * 09-25 的修法是 hook 插件的 `miui.systemui.drawable.AppIconsManager.getAppIcon`：
     * 查询包名是 SystemUI 时把返回值换成目标 App 图标。**那条路从原理上不可能生效** ——
     * 该类在 `MIUISystemUIPlugin.apk` 里，我们只能另建 `PathClassLoader` 去加载它，
     * 两套 classloader 得到的是**两个不同的 Class 对象**；岛上渲染走的是插件框架自己
     * 加载的那份，我们 hook 的这份永远不会被调用（症状就是「hook 挂上了、图标照旧」）。
     *
     * 正确通道在参数侧、不需要 hook（反编译实锤 + 设备 APK 取证）：
     *  - `FocusNotificationController` 组装岛数据时 `miui.pkg.name` 取自
     *    `NotificationUtil.getSbnTargetPkg()`；
     *  - 后者对**在替代通知白名单里的包**改读 `notification.extras["miui.targetPkg"]`；
     *  - 设备 MiuiSystemUI.apk 的 `config_canSendSubstituteNotificationPackages`
     *    里就有 `com.android.systemui`（aapt2 dump 实测，8 项白名单）。
     *
     * 所以我们只要在 extras 里写上目标 App 包名，插件里每一处
     * `getAppIcon(miui.pkg.name)` 都会自己拿到正确的目标 App 桌面图标 ——
     * 胶囊 / AOD / 图钉一次全修，且不引入任何 hook（少一层崩 SystemUI 的风险）。
     */
    private const val EXTRA_TARGET_PKG = "miui.targetPkg"

    fun install(ctx: HookContext): InstallReport {
        if (receiver != null) return InstallReport(1, notes = listOf("already installed"))

        val context = hostAppContext()
            ?: return InstallReport(0, notes = listOf("no_host_context"))
        appContext = context
        monoBmp = assetBitmap("icon/notify_mono.png")
        if (monoBmp == null) {
            ctx.log(Log.WARN, "口令岛：通知小图标 assets 读取失败，回落系统默认")
        }

        ensureChannel(context)

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    when (intent.action) {
                        PasscodeIsland.ACTION -> onSignal(c, intent)
                        ACTION_OPEN_SMALL_WINDOW -> openInSmallWindow(c, intent.getIntExtra(EXTRA_OPEN_ID, Int.MIN_VALUE))
                    }
                } catch (t: Throwable) {
                    // SystemUI 进程里的异常 = 用户看到黑屏，绝不让它逃逸（Feature 铁律）
                    android.util.Log.e(TAG, "口令岛：处理信号失败", t)
                }
            }
        }
        val filter = IntentFilter(PasscodeIsland.ACTION).apply { addAction(ACTION_OPEN_SMALL_WINDOW) }
        val handler = Handler(Looper.getMainLooper())
        // 三档注册形状从安全往下试（同 LauncherDrawerTracker）：发送方是 system_server（uid 1000），
        // 接收方 SystemUI（uid 10226）跨 uid → 只能 EXPORTED，所以必须挂 DUMP 权限挡第三方伪造
        // 完整 Intent 仅接收具有 DUMP 权限的系统发送方，不能回退无权限导出。
        val attempts = listOf(Triple(PasscodeIsland.PERMISSION, Context.RECEIVER_EXPORTED, "exported+dump"))
        var lastError: Throwable? = null
        for ((perm, flags, note) in attempts) {
            try {
                context.registerReceiver(r, filter, perm, handler, flags)
                receiver = r
                hookContext = ctx
                var previous = io.github.kotoriinsky.tsuzuri.core.CopyDirect.config(io.github.kotoriinsky.tsuzuri.hook.HookRegistry.prefs())
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key.startsWith("passcode_island.") || key == Keys.enabled(Catalog.PASSCODE_ISLAND)) {
                        handler.post {
                            val current = io.github.kotoriinsky.tsuzuri.core.CopyDirect.config(io.github.kotoriinsky.tsuzuri.hook.HookRegistry.prefs())
                            if (current != previous) {
                                notifiedIds.toList().forEach { context.cancelNotification(it) }
                                notifiedIds.clear()
                                resolvedIntents.clear()
                                publishedIdentities.clear()
                                previous = current
                            }
                        }
                    }
                }
                copyConfigListener = listener
                copyConfigStore = ctx.store
                ctx.store?.registerOnSharedPreferenceChangeListener(listener)
                ctx.log(Log.INFO, "口令岛接收器已注册（$note），等待 system_server 的口令信号")
                PasscodeIslandLongPress.install(ctx)
                // 接收器注册成功 ≠ 通道通：判据是第一条信号真的送达
                return InstallReport(1, notes = listOf("receiver=$note", "psl=${io.github.kotoriinsky.tsuzuri.core.PublicSuffix.loaded}", "rules=${io.github.kotoriinsky.tsuzuri.core.BuiltinRules.summary}"))
            } catch (t: Throwable) {
                lastError = t
            }
        }
        return InstallReport(0, notes = listOf("register_failed:" + (lastError?.javaClass?.simpleName ?: "?")))
    }

    fun uninstall() {
        PasscodeIslandLongPress.uninstall()
        copyConfigListener?.let { copyConfigStore?.unregisterOnSharedPreferenceChangeListener(it) }
        copyConfigListener = null
        copyConfigStore = null
        receiver?.let { r ->
            appContext?.let { c ->
                runCatching { c.unregisterReceiver(r) }
            }
        }
        receiver = null
        hookContext = null
        for ((id, r) in cancelRunnables) {
            mainHandler.removeCallbacks(r)
            appContext?.cancelNotification(id)
        }
        // 一次事件发两条通知（焦点 + 标准行），cancelRunnables 的 key 只是其中之一，
        // 所以还要按 notifiedIds 全撤一遍 —— 否则关掉模块后通知栏会留下那条标准行。
        notifiedIds.forEach { appContext?.cancelNotification(it) }
        cancelRunnables.clear()
        notifiedIds.clear()
        resolvedIntents.clear()
        publishedIdentities.clear()
        identityById.clear()
        launchTargets.clear()
        targetIconCache.clear()
        synchronized(pageTitleCache) { pageTitleCache.clear() }
        synchronized(pageSubtitleCache) { pageSubtitleCache.clear() }
        synchronized(coverCache) { coverCache.clear() }
        synchronized(finalUrlCache) { finalUrlCache.clear() }
        synchronized(metaDone) { metaDone.clear() }
        synchronized(publishedTargets) { publishedTargets.clear() }
        appContext = null
    }

    // ── 信号 → 岛通知 ──────────────────────────────────────────────────

    private fun onSignal(context: Context, intent: Intent) {
        val copyConfig = io.github.kotoriinsky.tsuzuri.core.CopyDirect.config(
            io.github.kotoriinsky.tsuzuri.hook.HookRegistry.prefs(),
        )
        val engineResult = intent.getBooleanExtra(io.github.kotoriinsky.tsuzuri.core.CopyDirect.EXTRA_ENGINE_RESULT, false)
        if (engineResult && (!copyConfig.enabled || copyConfig.mode != io.github.kotoriinsky.tsuzuri.core.CopyDirect.Mode.ISLAND)) return
        if (copyConfig.enabled && !engineResult) return
        if (intent.getStringExtra(PasscodeIsland.EXTRA_PLATFORM_PKG) in copyConfig.disabled) return
        val rule = intent.getStringExtra(PasscodeIsland.EXTRA_RULE)
        val label = intent.getStringExtra(PasscodeIsland.EXTRA_LABEL)
        val target = intent.getStringExtra(PasscodeIsland.EXTRA_TARGET)
        intent.getStringExtra(io.github.kotoriinsky.tsuzuri.core.CopyDirect.EXTRA_INTENT)?.let { serialized ->
            runCatching { Intent.parseUri(serialized, Intent.URI_INTENT_SCHEME) }.getOrNull()?.let { parsed ->
                if (target != null) {
                    resolvedIntents[target] = parsed
                    while (resolvedIntents.size > 64) resolvedIntents.remove(resolvedIntents.keys.first())
                }
            }
        }
        if (rule == null || label == null || target == null) {
            // 缺必需 extra = 整条信号不可用。**这里必须留痕**：静默 return 会让
            // 「广播发了、什么也没发生」变成一类无法定位的故障 —— 判据全在日志里，
            // 而这条路径一句话都不打（2026-10-06 真机实测踩到）。
            Log.w(
                TAG,
                "口令信号缺必需 extra，丢弃：rule=${rule != null} label=${label != null} " +
                    "target=${target != null} action=${intent.action}",
            )
            return
        }
        val snippet = intent.getStringExtra(PasscodeIsland.EXTRA_SNIPPET) ?: ""
        intent.getStringExtra(PasscodeIsland.EXTRA_SOURCE_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
            synchronized(sourceTexts) { sourceTexts[target] = text }
        }
        val core = intent.getStringExtra(PasscodeIsland.EXTRA_CORE)
        val accent = intent.getStringExtra(PasscodeIsland.EXTRA_ACCENT)
        val platformName = intent.getStringExtra(PasscodeIsland.EXTRA_PLATFORM_NAME)
        val platformPkg = intent.getStringExtra(PasscodeIsland.EXTRA_PLATFORM_PKG)
        val category = PasscodeIsland.ContentCategory.fromWire(
            intent.getStringExtra(PasscodeIsland.EXTRA_CATEGORY),
        ) ?: PasscodeRules.categoryFor(rule, null, target)
        // 候选链供快递和 QQ 音乐歌曲使用，见 EXTRA_TARGET_ALTS。
        val alts = intent.getStringArrayListExtra(PasscodeIsland.EXTRA_TARGET_ALTS).orEmpty()
        // 本机 / 内网地址不抓：不可达时只会白等连接超时（直接按「打开」显示，用户 2026-10-22）
        if (category != PasscodeIsland.ContentCategory.EXPRESS && target.startsWith("http", ignoreCase = true) &&
            !PublicSuffix.isLocalHost(Uri.parse(target).host.orEmpty())
        ) {
            scheduleMetaFetch(
                context, rule, label, target, snippet, core, accent, platformName, platformPkg,
                category, alts,
            )
        } else {
            postIsland(
                context, rule, label, target, snippet, core, accent, platformName, platformPkg,
                category, alts = alts,
            )
        }
    }

    /**
     * 信号目标 → 用户复制的原文（[PasscodeIsland.EXTRA_SOURCE_TEXT]），给「分享」用。
     * 按目标存而不是沿参数往下传：异步取元数据那条路径会把目标换成展开后的地址，
     * 原始目标落进 `alts`，所以查的时候主目标与备选都试一遍。容量有界，最旧的先出。
     */
    private val sourceTexts = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }

    private fun sourceTextFor(primary: String, alts: List<String>): String? = synchronized(sourceTexts) {
        sourceTexts[primary] ?: alts.firstNotNullOfOrNull { sourceTexts[it] }
    }

    private fun postIsland(
        context: Context,
        rule: String,
        label: String,
        primaryTarget: String,
        snippet: String,
        core: String?,
        accent: String?,
        platformName: String?,
        platformPkg: String?,
        category: PasscodeIsland.ContentCategory,
        /** 跳转目标的备选，顺序即优先级（见 [PasscodeIsland.EXTRA_TARGET_ALTS]）。 */
        alts: List<String> = emptyList(),
        // 本次解析完成后带入；超时则字段独立降级。
        pageTitle: String? = null,
        pageSubtitle: String? = null,
        cover: android.graphics.Bitmap? = null,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 目标候选链：**只有展示侧有 PackageManager**，「依次试哪个」只能在这里做。
        // 顺序 = 优先级（快递单号最典型：菜鸟小程序 → 快递100 网页）。
        // 一个都解析不到 → 整条不上岛（点不动的岛 = 最差的体验；淘口令未装手淘就是
        // 走这里静默降级）。单目标的规则（chain 只有一个元素）行为与
        // 改动前**完全一致**。
        val declaredPkg = platformPkg?.takeIf { it.isNotBlank() }
        // system_server 的旧 hook 可继续发来没有候选的广播；展示侧补算后即可软重载生效。
        val appTargets = PasscodeRules.articleAppTargets(primaryTarget, declaredPkg)
        val chain = if (PasscodeRules.prefersAppTarget(declaredPkg)) {
            (appTargets + alts.filter { !it.startsWith("http") } + listOf(primaryTarget) + alts).distinct()
        } else {
            (listOf(primaryTarget) + alts + appTargets).distinct()
        }
        val engineIntent = resolvedIntents[primaryTarget]
        val resolvable = if (engineIntent != null) emptyList() else chain.filter { canResolve(context, it) }
        val target = if (engineIntent != null) primaryTarget else resolvable.firstOrNull { candidate ->
            declaredPkg != null && canHandlePackage(context, candidate, declaredPkg)
        } ?: resolvable.firstOrNull()
        // 候选链里的 intent: 目标（负一屏快递详情）按显式 Intent 打开
        val explicitIntent = engineIntent ?: target?.let(::allowedIntentTarget)
        if (target == null) {
            Log.i(TAG, "口令岛跳过：跳转目标无人接管 rule=$rule chain=$chain")
            return
        }
        // 内容身份 = 去重键，同时也是**通知 id 的来源**：同一条内容的不同写法（裸号 / 短链 /
        // 长链 / 分享文案）必须落到同一个 id。以前 id 直接取 target.hashCode()，短链和它展开后
        // 的长链是两个 id → 两条通知 → 用户看到两个岛（2026-10-06 反馈）。
        val identity = PasscodeLinkIdentity.fromMatch(platformPkg, core, target)
        val id = identity?.hashCode() ?: target.hashCode()

        // 走到这里 = 目标一定有人接（上面就是判它），不再查第二遍
        val viewIntent = explicitIntent?.let(::Intent) ?: Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse(target))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // 所有图标位 = 目标 App 图标（用户拍板 2026-09-25「全部改成目标应用的图标，
        // 不要给我系统界面的」）：规则引擎带包名的平台直接用；普通网页回落域名启发式
        // （pickDomainApp，内含默认浏览器兜底）。彩色图 → 岛/胶囊/通知中心 largeIcon；
        // 剪影 → 状态栏 smallIcon。胶囊/AOD 的图标见 EXTRA_TARGET_PKG 的说明（不需要 hook）。
        // 空串要当「没有」处理：`platformPkg ?: 启发式` 会把 "" 当成有值，静默跳过域名解析
        // （2026-09-27 排查时踩到过：手工投递空 extra，图标位整片空白）
        // ── 谁来打开：平台 App 优先，其次问系统，**有歧义时不钉死** ─────────
        // ① 「平台声明的包名能接住」是最明确的「本地应用」信号 —— 直接钉，一点直达、
        //    不弹选择器（B 站/淘口令/百度网盘 scheme 都走这条，行为与改动前完全一致）。
        // ② 没有平台 App 时**问系统**（App Links 的权威）：验证过的 App Link 给唯一
        //    handler，多个候选给 ResolverActivity。系统说「有歧义」时绝不能 setPackage
        //    —— 2026-10-06 实测：小黑盒那条带 AutoVerify 的分享链
        //    （`c.xiaoheihe.cn/v*/bbs/app/api/web/share`）系统判为歧义，而我们的启发式
        //    选中 `mark.via.gp` 并钉死，结果**既跳过 App Links 候选、也跳过系统选择器**，
        //    用户连「用小黑盒打开」都看不到。设计本来就是
        //    「优先 App 再浏览器 —— 一行代码都不用写」。
        val systemPkg = systemResolvePackage(context, viewIntent)
        val declaredHandles = declaredPkg?.takeIf {
            it.isNotBlank() && canHandlePackage(context, viewIntent.dataString.orEmpty(), it)
        }
        // 系统给的若是**通用网页处理器**（对 example.org 也接得住的浏览器/WebView），
        // 那它只是「兜底」，不代表这条链接有专属 App。这一档也交给选择器 —— 用户
        // 2026-10-06 拍板：没有直接 App 规则时，别无脑钉默认浏览器，让选择器兜底
        //（选择器里自然包含浏览器，等于把浏览器兜底覆盖掉，同时给 App Links 候选一个机会）。
        val systemIsGenericWeb = systemPkg != null &&
            canHandlePackage(context, GENERIC_WEB_PROBE, systemPkg)
        // ② 「**域名专属 App**」优先（用户 2026-10-06 拍板：能直达就直达，少一步）。
        //    系统给的 best 常常是默认浏览器，但那不代表没有 App —— 查一遍「对这个域名
        //    的匹配强于对 example.org 的匹配」的包（通用网页处理器会被这个判据排除）。
        //    有就钉它，一点直达；没有再谈歧义。
        //    本机 / 内网地址不找「专属 App」，直接钉默认浏览器（用户 2026-10-22：「localhost 弹浏览器就可以了」）。
        //    真机：钉钉声明了 localhost 的处理器，系统对 https://localhost:8080 给的是选择器，
        //    不排除的话会被钉到钉钉；默认浏览器取系统对普通网页（example.org）的默认处理器。
        //    只看 http(s)：App 深链的「主机名」常是单段词（`alipays://platformapi/…`、`openapp.jdlogistic://virtual`），
        //    按主机名判会被当成内网地址钉给浏览器 —— 2026-10-22 真机：菜鸟小程序与京东快递全部打不开。
        val isWeb = viewIntent.data?.scheme?.lowercase() in setOf("http", "https")
        val localBrowser = if (isWeb && PublicSuffix.isLocalHost(viewIntent.data?.host.orEmpty())) {
            systemResolvePackage(
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse(GENERIC_WEB_PROBE)).addCategory(Intent.CATEGORY_BROWSABLE),
            )?.takeUnless { it.isResolverPackage() }
        } else {
            null
        }
        val domainApp = if (declaredHandles == null && localBrowser == null) domainAppFor(context, viewIntent)?.takeIf { pkg -> !canHandlePackage(context, GENERIC_WEB_PROBE, pkg) } else null
        val ambiguous = declaredHandles == null && localBrowser == null && domainApp == null &&
            (systemPkg.isResolverPackage() || systemIsGenericWeb)
        // 图标仍需要唯一包名（用户拍板：所有图标位 = 目标 App 图标）。歧义时用启发式的
        // 结果**只用于画图标**，不再拿它决定打开者（可能与实际打开的略有出入，属已知取舍，
        // 日志里带 ambiguous 标记可对）。
        val iconPkg = declaredHandles
            ?: localBrowser
            ?: domainApp
            ?: (if (ambiguous) null else systemPkg?.takeUnless { it.isResolverPackage() })
            // 平台 App 装了但接不住这条网页（公众号文章、美团短链）：仍按默认处理器打开，
            // 图标用平台 App 的，别画成浏览器
            ?: declaredPkg?.takeIf { it.isNotBlank() && isInstalled(context, it) }
            ?: resolveTargetPackage(context, viewIntent, declaredPkg)
        if (iconPkg == null) {
            Log.i(TAG, "口令岛跳过：无法确定实际打开应用 rule=$rule target=$target")
            return
        }
        if (explicitIntent != null) {
            // 原生动作的包、URI 和参数整体复用，不重新猜测接收方。
        } else if (!ambiguous) {
              viewIntent.setPackage(iconPkg)
          } else {
              // 通用网页（没有专属 App）→ **用系统默认浏览器兜底**。
              // 用户 2026-10-06 拍板：「链接用默认浏览器为最后，不要用迅雷」。
              // 之前这里留空交给系统解析，结果是弹**选择器**（sys=com.android.intentresolver），
              // 目标还可能被判给迅雷这类下载器。现在显式取「浏览器类应用（CATEGORY_APP_BROWSER）
              // 的默认项」并钉住 —— 迅雷不声明这个 category，自然被排除；取不到则仍交给系统。
              // ⚠️ 用户 2026-10-06 报："怎么现在 http 变成 chrome 了？应该按照默认浏览器"。
              // 原因：我一开始用 CATEGORY_APP_BROWSER 探测"默认浏览器"，它返回的是 Chrome，
              // 而这台机器**对 URL 的默认处理器**其实是 `mark.via.gp`（真机日志里 sys=mark.via.gp）。
              // 所以改成：**先信系统对这个 URL 的默认处理器**（`systemPkg`，非选择器时），
              // 只有它给的是选择器（没有唯一默认）时才退到 CATEGORY_APP_BROWSER 探测。
              val browserPkg = systemPkg?.takeIf { !it.isResolverPackage() }
                  ?: context.packageManager
                      .resolveActivity(
                          android.content.Intent(android.content.Intent.ACTION_MAIN)
                              .addCategory(android.content.Intent.CATEGORY_APP_BROWSER),
                          android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                      )
                      ?.activityInfo?.packageName
                      // 装了多个浏览器且没设默认时这里给的也是选择器，钉它没有意义
                      ?.takeUnless { it.isResolverPackage() }
              if (browserPkg != null) {
                  viewIntent.setPackage(browserPkg)
                  Log.i(TAG, "口令岛通用网页改钉默认浏览器：pkg=$browserPkg")
              }
          }
        Log.i(
            TAG,
            if (ambiguous) {
                "口令岛打开目标无专属 App（按默认处理器打开，通常是浏览器）：rule=$rule " +
                    "target=$target icon=$iconPkg sys=${systemPkg ?: "none"}"
            } else {
                "口令岛打开目标已钉定：rule=$rule target=$target pkg=$iconPkg " +
                    "declared=${declaredHandles != null} local=${localBrowser != null} domain=${domainApp != null} sys=${systemPkg ?: "none"}"
            },
        )
        val iconPair = appIcon(context, iconPkg)
        val targetIconBmp = iconPair.first ?: run {
            Log.w(TAG, "口令岛跳过：目标应用图标不可用 pkg=$iconPkg")
            return
        }
        val targetMonoBmp = iconPair.second
        val targetIcon = android.graphics.drawable.Icon.createWithBitmap(targetIconBmp)

        // 展开态按内容类型选次行；可选字段缺失时不保留占位。
        val coreText = core?.takeIf { it.isNotEmpty() }
        val express = category == PasscodeIsland.ContentCategory.EXPRESS
        if (express && coreText.isNullOrBlank()) return
        val cachedTitle = if (!express && target.startsWith("http")) {
            synchronized(pageTitleCache) { pageTitleCache[target] }
        } else {
            null
        }
        val titleText = if (express) null else pageTitle ?: cachedTitle
        // 传原文：纯数字国际单号（FedEx / DHL / USPS）的承运商名只在文案里
        val carrier = coreText?.takeIf { express }?.let { PasscodeIsland.carrierName(it, snippet) }
        // 网页的「封面」是站点图标（fetchPageMeta 里只给 WEB 抓图标，不抓 og:image）。
        // 快递的「封面」是承运商标识：装了官方 App 用它的图标（系统当场取，不随包带 logo），
        // 没装就用自绘徽标；右上角小标仍是负责打开的 App。打开的就是官方 App 时只显示它一个。
        val coverBmp = if (express) {
            val carrierPkg = carrier?.let { carrierAppPackage(context, it) }
            when {
                carrierPkg != null && carrierPkg == iconPkg -> null
                carrierPkg != null -> appIcon(context, carrierPkg).first ?: expressBadgeBitmap(ExpressBadge.forCarrier(carrier))
                else -> expressBadgeBitmap(ExpressBadge.forCarrier(carrier))
            }
        } else if (category.supportsCover() || category == PasscodeIsland.ContentCategory.WEB) {
            cover ?: synchronized(coverCache) { coverCache[target] }
        } else {
            null
        }
        val coreIsId = coreText != null && PasscodeIsland.isIdLike(coreText)
        val musicSeparator = Regex("""\s+[-–—|｜]\s+""")
        val musicCoreParts = coreText?.takeUnless { coreIsId }
            ?.takeIf { category == PasscodeIsland.ContentCategory.MUSIC }
            ?.split(musicSeparator, limit = 3)
        val musicPageParts = titleText
            ?.takeIf { category == PasscodeIsland.ContentCategory.MUSIC }
            ?.split(musicSeparator, limit = 3)
        val musicTitle = musicPageParts?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: musicCoreParts?.firstOrNull()?.takeIf { it.isNotBlank() }
        val musicArtist = musicPageParts?.getOrNull(1)?.takeIf {
                it.isNotBlank() && musicTitle.equals(musicPageParts.firstOrNull(), ignoreCase = true)
            }
            ?: musicCoreParts?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val sharedTitle = coreText?.takeIf {
            !coreIsId && category in setOf(
                PasscodeIsland.ContentCategory.SHORT_VIDEO,
                PasscodeIsland.ContentCategory.IMAGE_TEXT,
                PasscodeIsland.ContentCategory.ECOMMERCE,
            )
        }
        val headTitle = (if (express) carrier ?: "快递" else null)
            ?: musicTitle
            ?: titleText
            ?: sharedTitle
            ?: coreText?.takeIf { !coreIsId }
            // 抓不到内容标题：已知平台显示「打开 XXX」（用户 2026-10-22 定），内容号挪到次行（idLine）。
            // 以前内容号排在这之前，YouTube 的大字行是 `dQw4w9WgXcQ`、抖音是 19 位作品号。
            ?: platformName?.takeIf { category != PasscodeIsland.ContentCategory.WEB && it.isNotBlank() }
                ?.let(PasscodeIslandTemplate::fallbackTitle)
            ?: coreText
            ?: label.takeIf { category != PasscodeIsland.ContentCategory.WEB && it.isNotBlank() }
            ?: domainOf(target)
            ?: label.takeIf { it.isNotBlank() }
            ?: return
        val displayTitle = headTitle
        // 认不出承运商时大字行就是单号本身 → 不再重复占次行
        val idLine = coreText?.takeIf { coreIsId && it != headTitle }
        val secondaryText = when (category) {
            PasscodeIsland.ContentCategory.EXPRESS -> coreText?.takeIf { it != headTitle }
            // 抓不到标题时大字行已是域名 → 次行给路径（分得清是哪一页）
            PasscodeIsland.ContentCategory.WEB ->
                PasscodeIslandTemplate.webPathLine(target)?.takeIf { titleText == null }
                    ?: domainOf(target)?.takeIf { it != headTitle }
            PasscodeIsland.ContentCategory.MUSIC -> pageSubtitle ?: musicArtist
            PasscodeIsland.ContentCategory.LONG_VIDEO -> pageSubtitle ?: idLine
            // 社区类（Reddit 的 r/版块）优先于帖主：先说在哪，再说是谁（用户 2026-10-22）
            PasscodeIsland.ContentCategory.IMAGE_TEXT -> PasscodeIslandTemplate.communityLine(target) ?: pageSubtitle
            // 商品标题抓不到（大字行是「打开小红书」）时，次行说清这是商品，比再显示一遍域名有用
            PasscodeIsland.ContentCategory.ECOMMERCE -> idLine?.takeIf { titleText == null }
                ?: PasscodeIslandTemplate.categoryLabel(category).takeIf {
                    platformName != null && headTitle == PasscodeIslandTemplate.fallbackTitle(platformName)
                }
            else -> idLine?.takeIf { titleText == null }
        }
        // 展开态的超长处理交给官方模板，
        // 不再按估算列宽自己缩字号 / 裁切；通知中心那条是标准模板，同样自带省略。
        val expanded = PasscodeIslandTemplate.expanded(
            category = category,
            title = displayTitle,
            secondary = secondaryText,
            hasCover = coverBmp != null,
            platformName = platformName,
            domain = domainOf(target),
        )

        val timeoutMs = PasscodeIsland.timeoutMs(
            hookContext?.prefs?.rawFloatOrNull(Keys.pref(Catalog.PASSCODE_ISLAND, "timeout_seconds")),
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setAutoCancel(false)
            .setTimeoutAfter(timeoutMs)
        // 胶囊/AOD 图标通道：声明「这条通知是替哪个 App 发的」（见 EXTRA_TARGET_PKG）。
        // 只在**图标真的解析出来了**才声明 —— 声明了未安装的包名，插件会解析失败并
        // 直接隐藏整个图标容器，比回落还糟。
        builder.extras.putCharSequence(EXTRA_TARGET_PKG, iconPkg)
        // 状态栏小图标 = 目标 App 图标的单色剪影（官方规范：状态栏只渲染 alpha 着单色）。
        // 前景层提取失败/目标缺失时才回落 Tsuzuri 书签剪影，绝不出现 SystemUI 默认图标。
        val smallIconBmp = targetMonoBmp ?: monoBmp
        builder.setSmallIcon(
            smallIconBmp?.let { android.graphics.drawable.Icon.createWithBitmap(it) }
                ?: android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_dialog_info),
        )
        // 平台强调色：「打开」按钮底色与 param highlightColor 共用一色。规则里的值先校验，
        // 解析不了就用默认蓝 —— 模板侧解析失败会静默丢掉整个颜色字段。
        // 规则没有自己的颜色（通用网页 / 自定义规则只有默认蓝）时，从封面或站点图标取主色。
        val ruleAccent = accent?.takeIf {
            try {
                android.graphics.Color.parseColor(it)
                true
            } catch (_: Throwable) {
                false
            }
        }?.takeUnless { it.equals(PasscodeRules.P_GENERIC.accent, ignoreCase = true) }
        // 没有平台色、也没有封面：从目标 App 图标取色（用户 2026-10-22）。
        // 有歧义时图标只是浏览器 / 平台的示意，取它的颜色没有意义
        val accentHex = ruleAccent ?: coverBmp?.let(::coverAccent)
            ?: targetIconBmp.takeUnless { ambiguous }?.let(::coverAccent)
            ?: PasscodeRules.P_GENERIC.accent
        // 通知中心用独立层级，次行只放补充信息；整条通知仍只有一个点击目标。
        builder.setContentTitle(displayTitle)
        builder.setContentText(secondaryText ?: "打开")
        // 通知中心图标位 = 目标 App 图标（用户拍板 2026-09-25：全部用目标应用图标）。
        // 代发身份下通知头部的应用图标位是 SystemUI 的（改不了），用 largeIcon 压过去。
        builder.setLargeIcon(targetIconBmp)
        // PendingIntent.getActivity 的 options 在这台设备上没有形成 freeform Task。
        // 点击时让 SystemUI 用与扇形小窗相同的 startActivity 链发起目标。
        val openIntent = Intent(ACTION_OPEN_SMALL_WINDOW)
            .setPackage(context.packageName)
            .putExtra(EXTRA_OPEN_ID, id)
        val openPi = PendingIntent.getBroadcast(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val shareTarget = if (rule == "qq_music_song") primaryTarget else target
        // 分享 = 用户复制的原文（2026-10-25 用户反馈：之前分享出去的是解析后的链接）。
        // 只有拿不到原文（旧版发送端）才退回「标题 + 清洗后的链接」。
        val sourceText = sourceTextFor(primaryTarget, alts)
        val shareText = if (!sourceText.isNullOrBlank()) {
            sourceText
        } else if (Uri.parse(shareTarget).scheme?.lowercase() in setOf("http", "https")) {
            // 分享出去的地址去掉平台无关的跟踪参数（utm_* / fbclid / spm…），平台专属参数不动
            "$displayTitle\n${UrlCleanup.stripTracking(shareTarget)}"
        } else {
            snippet.ifBlank { displayTitle }
        }
        val shareIntent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            },
            "分享",
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            identifier = target
        }
        val sharePi = PendingIntent.getActivity(
            context,
            id,
            shareIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.setContentIntent(openPi)
        // 岛图片 Bundle：目标 App 图标恒有；封面只在展开态方图用封面时才放（「有图才引用」）。
        val coverIcon = coverBmp?.takeIf { expanded.picture == PasscodeIslandTemplate.Picture.COVER }
            ?.let(android.graphics.drawable.Icon::createWithBitmap)
        builder.extras.putBundle(
            EXTRA_FOCUS_PICS,
            android.os.Bundle().apply {
                putParcelable(KEY_ISLAND_ICON, targetIcon)
                if (coverIcon != null) putParcelable(KEY_ISLAND_COVER, coverIcon)
            },
        )
        // 摘要态 B 区只放静态短摘要；完整标题留给展开态和通知中心。
        val capsuleText = summaryText(category, headTitle, coreText)
        val paramJson = buildFocusParam(
            capsuleText,
            snippet,
            expanded,
            pictureKey = if (coverIcon != null) KEY_ISLAND_COVER else KEY_ISLAND_ICON,
            openIntent,
            shareIntent,
            accentHex,
            timeoutMs,
        )
        builder.extras.putString(EXTRA_FOCUS_PARAM, paramJson)
        // ⚠️ 不要往 miui.focus.param.custom 塞 param_v2 —— 2026-09-25 实测：
        // extras 带 miui.focus.rv 时解析端改读 custom 键（DynamicIslandController:1461），
        // 它期望的不是 param_v2 同构 JSON；塞错 schema 会让解析整体失败，
        // 回落默认值（timeout 720min、enableFloat=false）→ removeFocusNotificationByKey
        // 直接摘岛 —— 表现是「通知发了、岛不渲染」。custom 的正确结构待官方协议页确认。
        // 「分享」「打开」两枚按钮的实体 Action（textButton.action 按键名到这里取 Parcelable，
        // 优先于 actionIntent；结构对齐性能模式岛 s17）
        val openAction = Notification.Action.Builder(null, "打开", openPi).build()
        val shareAction = Notification.Action.Builder(null, "分享", sharePi).build()
        builder.extras.putBundle(
            EXTRA_FOCUS_ACTIONS,
            android.os.Bundle().apply {
                putParcelable(OPEN_ACTION_KEY, openAction)
                putParcelable(SHARE_ACTION_KEY, shareAction)
            },
        )
        // 息屏岛：FocusCoordinator 只认带 rvAod RemoteViews 的焦点通知（见 buildAodRemoteViews 注释）
        builder.extras.putParcelable(
            EXTRA_RV_AOD,
            buildAodRemoteViews(displayTitle, secondaryText ?: "打开"),
        )

        // 这条焦点通知**不进通知栏**：通知栏那一行由下面第二条标准通知承载。
        builder.extras.putBoolean(EXTRA_SHOW_NOTIFICATION, false)

        val notification = builder.build()
        // 当初是为过**自绘视图**的鉴权门（`isPromotedOngoing`）加的；2026-10-22 展开态改走
        // 官方模板链后理论上不再需要，但模板链在代发身份下的鉴权还没真机验过，
        // 验证通过前先保留。framework 隐藏 API，反射调用；方法不存在 = 老 ROM，忽略。
        try {
            Notification::class.java.getDeclaredMethod("setPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(notification, true)
        } catch (_: Throwable) {
        }
        // 同内容去重：窗口 = **这条岛的存活时长**。岛还挂在屏幕上时再复制一次同内容，
        // 用户要的是「还是那一条」，不是第二条岛。不同内容则**允许并存**（用户 2026-10-06
        // 拍板：先复制 A 的链接再复制 B 的链接，两条岛都在是期望行为）。
        // 以前窗口取的是 hook 侧的 1.5s 防抖，而岛能活 10~60s —— 窗口外重复复制必出第二条。
        val now = SystemClock.uptimeMillis()
        publishedIdentities.entries.removeAll { now < it.value || now - it.value >= timeoutMs }
        if (identity != null && identity in publishedIdentities) {
            Log.i(TAG, "口令岛跳过重复内容：rule=$rule identityHash=${identity.hashCode()} 窗口=${timeoutMs}ms")
            return
        }
        // 只发焦点通知这一条（用户 2026-10-22：「只跳岛，不跳传统通知」）。
        // 以前（A′，2026-10-06）另发一条标准通知在通知栏留痕；改走官方模板 18 后，
        // 下拉通知栏时岛卡片本身就在顶部，两条内容完全重复。
        val launchTarget = Intent(viewIntent)
        val webUrl = chain.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
        launchTargets[id] = LaunchTarget(launchTarget, iconPkg, ambiguous, webUrl)
        nm.notify(id, notification)
        if (identity != null) {
            publishedIdentities[identity] = now
            identityById[id] = identity
        }
        notifiedIds.add(id)
        scheduleFallbackCancel(nm, listOf(id), timeoutMs)
        Log.i(TAG, "口令岛上岛：rule=$rule category=$category id=$id " +
            "iconPkg=${iconPkg ?: "none"} template=18 picture=${expanded.picture} " +
            "appMark=${expanded.showAppMark} cover=${coverBmp != null}")
    }

    // ── 网页元信息预加载（标题 + 封面）────────────────────────────────

    /** 抓过的 URL 标题缓存（跨岛复用：同链接防抖窗口内二次复制直接带标题上岛）。 */
    private val pageTitleCache = boundedMap<String, String>(64)

    private val pageSubtitleCache = boundedMap<String, String>(64)

    /** 抓过的 URL 封面缩略图缓存（同上；位图尺寸见 [fetchCover]）。 */
    // 以下几个缓存都有上限：原来只增不减，封面是位图，复制得多了就一直占着内存
    private val coverCache = boundedMap<String, android.graphics.Bitmap>(16)

    private val finalUrlCache = boundedMap<String, String>(64)

    /**
     * 已经抓过（无论成败）的 URL。**这是防死循环的闸门**：预加载走的是
     * 「同 id 重发」，重发会再进 [postIsland]；若把条件写成「没封面就抓」，
     * 抓不到封面的页面（纯文字页、无 og:image）会每 2.5 秒重抓一次，岛永远在闪。
     */
    private val metaDone: MutableSet<String> = java.util.Collections.newSetFromMap(boundedMap<String, Boolean>(64))

    /**
     * 原始 target → **首次发布时实际用过的地址**（短链可能是短链本身，也可能是展开后的地址）。
     *
     * 为什么需要它：内容身份是按「用哪个地址发布」算的，而短链的**展开前/展开后是两个身份**
     * （`xhs-short:xhslink.cn/…` vs `xhs-note:<24hex>`）。首次发布常走超时路径（那时还没展开完），
     * 后续同一条信号若改用展开后的地址发布，身份就变了 —— 去重失效，用户看到**两个岛**
     * （2026-10-06 用户报「小红书复制一条链接出现两个岛」）。
     */
    private val publishedTargets = HashMap<String, String>()

    private class MetaTarget(val url: String, val redirected: PasscodeRules.Match?)

    private fun metaTarget(
        context: Context,
        rule: String,
        source: String,
        resolvedUrl: String?,
        platformPkg: String?,
    ): MetaTarget {
        // 解析出的目标只认两种：① http(s) 链接；② **我们自己解出来的深链** —— 目前只有
        // 支付宝小程序 `alipays://platformapi/startapp?…`（短链 302 到 render.alipay.com 的
        // `scheme=` 参数里，见 [AlipayMiniProgram]，2026-10-06 实测）。
        // 只放行这一个前缀，别的 scheme 一律不认，避免把任意深链当成解析结果。
        val candidate = resolvedUrl?.takeIf {
            it.startsWith("https://") ||
                (source.startsWith("http://") && it.startsWith("http://")) ||
                it.startsWith("alipays://platformapi/startapp")
        } ?: return MetaTarget(source, null)
        val redirected = if (rule == "url" && candidate != source) {
            PasscodeRules.redirectedMatch(source, candidate)
                ?: PasscodeRules.match(candidate)?.takeIf { it.platform != PasscodeRules.P_GENERIC }
        } else {
            null
        }
        if (redirected == null && !platformPkg.isNullOrBlank() &&
            !canHandlePackage(context, candidate, platformPkg)
        ) return MetaTarget(source, null)
        // 重定向到「谁都不认识」的地址时别丢掉原链接：原 host 可能挂着 App Link。
        // 2026-10-06 实测：小黑盒 `c.xiaoheihe.cn/…` 被重定向到应用宝页
        // `a.app.qq.com/o/simple.jsp?pkgname=com.max.xiaoheihe`，打开目标因此从
        // 「小黑盒接得住」变成「只有浏览器接得住」（ADR-42 §决定 3：内容身份用于去重，
        // **不代替实际打开 URL**）。重定向命中了已知平台时不走这条，避免动到
        // b23.tv→bilibili、m.tb.cn→闲鱼 这些已验的语义。
        if (redirected == null && domainAppFor(context, source) != null) return MetaTarget(source, null)
        return MetaTarget(candidate, redirected)
    }

    private fun scheduleMetaFetch(
        context: Context,
        rule: String,
        label: String,
        target: String,
        snippet: String,
        core: String?,
        accent: String?,
        platformName: String?,
        platformPkg: String?,
        category: PasscodeIsland.ContentCategory,
        alts: List<String>,
    ) {
        fun publish(resolvedUrl: String?, title: String?, subtitle: String?, cover: android.graphics.Bitmap?) {
            val selected = metaTarget(context, rule, target, resolvedUrl, platformPkg)
            val redirect = selected.redirected
            // 短链展开前后是**两个不同的内容身份**（`xhs-short:xhslink.cn/…` vs
            // `xhs-note:<24hex>`），而身份是按「用哪个地址发布」算的。所以这里把
            // **首次发布实际用过的地址**记成锚点，之后的发布一律复用它 ——
            // 否则同一条信号的第二次发布（缓存路径）会换成展开后的地址，去重失效，
            // 用户看到两个岛（2026-10-06 用户报「小红书复制一条链接出现两个岛」）。
            val anchored = synchronized(publishedTargets) {
                publishedTargets.getOrPut(target) { selected.url }
            }
            postIsland(
                context, rule, redirect?.label ?: label, anchored, snippet, core ?: redirect?.core,
                redirect?.platform?.accent ?: accent, redirect?.platform?.name ?: platformName,
                redirect?.platform?.pkg ?: platformPkg, redirect?.category ?: category,
                alts = listOf(target) + alts, pageTitle = title, pageSubtitle = subtitle, cover = cover,
            )
        }
        if (synchronized(metaDone) { metaDone.contains(target) }) {
            // 缓存命中：用**首次发布用过的地址**，而不是「展开后的地址」。
            // 2026-10-06 用户报「小红书复制一条链接出现两个岛」：首次发布常常走超时路径
            //（那时还没展开完，用的是短链），而这条缓存路径改用了展开后的地址 ——
            // 身份从 `xhs-short:…` 变成 `xhs-note:…`，去重就失效了。
            val cachedTarget = synchronized(publishedTargets) { publishedTargets[target] }
                ?: synchronized(finalUrlCache) { finalUrlCache[target] }
                ?: target
            publish(
                cachedTarget, synchronized(pageTitleCache) { pageTitleCache[target] },
                synchronized(pageSubtitleCache) { pageSubtitleCache[target] },
                synchronized(coverCache) { coverCache[target] },
            )
            return
        }
        val published = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = Runnable {
            if (published.compareAndSet(false, true) && appContext != null) {
                try {
                    val cachedTarget = synchronized(finalUrlCache) { finalUrlCache[target] } ?: target
                    publish(
                        cachedTarget, synchronized(pageTitleCache) { pageTitleCache[target] },
                        synchronized(pageSubtitleCache) { pageSubtitleCache[target] },
                        synchronized(coverCache) { coverCache[target] },
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "口令岛：解析超时后发布失败", t)
                }
            }
        }
        val timeoutMs = when {
            metaSocialIdentity(target) != null -> 4_500L
            category == PasscodeIsland.ContentCategory.LONG_VIDEO && youtubeVideoId(target) != null -> 3_500L
            // 快手要抓 183KB 的分享页 + 下封面；2 秒兜底会把结果丢掉（同 YouTube 的理由）
            isKuaishouTarget(target) -> 3_500L
            // 抖音要**注册 ttwid + 抓分享页 + 下封面**三步；实测首次跑（冷缓存）会超过 2 秒，
            // 超时后结果被丢弃、岛只剩标题（2026-10-06 实测踩到）。给足 4 秒。
            DouyinShare.isDouyinHost(Uri.parse(target).host.orEmpty()) -> 4_000L
            // 京东：商品页 **297KB** + 主图，2 秒兜底必超（实测：标题上了岛、封面没下来）
            Uri.parse(target).host?.let {
                it == "3.cn" || it.endsWith(".3.cn") || it == "jd.com" || it.endsWith(".jd.com")
            } == true -> 4_000L
            PasscodeRules.isSharedTaobaoShortLink(target) || PasscodeRules.xStatusIdentity(target) != null -> 3_000L
            else -> 2_000L
        }
        mainHandler.postDelayed(timeout, timeoutMs)
        Thread {
            val meta = fetchPageMeta(target, category) { finalUrl ->
                synchronized(finalUrlCache) { finalUrlCache[target] = finalUrl }
            }
            val finalTarget = metaTarget(context, rule, target, meta.finalUrl, platformPkg).url
            meta.title?.let { synchronized(pageTitleCache) { pageTitleCache[target] = it } }
            meta.subtitle?.let { synchronized(pageSubtitleCache) { pageSubtitleCache[target] = it } }
            meta.cover?.let { synchronized(coverCache) { coverCache[target] = it } }
            if (finalTarget != target) synchronized(finalUrlCache) { finalUrlCache[target] = finalTarget }
            synchronized(metaDone) { metaDone.add(target) }
            mainHandler.post {
                if (published.compareAndSet(false, true) && appContext != null) {
                    mainHandler.removeCallbacks(timeout)
                    try {
                        publish(finalTarget, meta.title, meta.subtitle, meta.cover)
                    } catch (t: Throwable) {
                        Log.e(TAG, "口令岛：解析后发布失败", t)
                    }
                }
            }
        }.start()
    }

    /** 页面元信息；短链跳转后的最终地址也参与原生 App 解析。 */
    private class PageMeta(
        val title: String?,
        val cover: android.graphics.Bitmap?,
        val finalUrl: String?,
        val subtitle: String? = null,
    )

    private class PageHtml(val text: String, val finalUrl: String)

    /** 抓一页拿齐两项：标题解析在 HTML 里，封面要再来一次 GET（og:image 是外链）。 */
    private fun fetchPageMeta(
        url: String,
        category: PasscodeIsland.ContentCategory,
        onFinalUrl: (String) -> Unit = {},
    ): PageMeta {
        // 「试一下」的随包样例：内容提前存好，不联网（见 CopyDirectSamples）
        CopyDirectSamples.preset(url)?.let { s ->
            val cover = CopyDirectSamples.coverBytes(s)?.let { downscaleCover(it) }
            s.finalUrl?.let(onFinalUrl)
            return PageMeta(s.title, cover, s.finalUrl ?: url, s.subtitle)
        }
        fetchPlatformMeta(url, category, strict = true)?.let { return it }
        var page = fetchHtml(url) ?: run {
            // 页面本身取不到（超时 / 被墙 / 反爬）时，提供方清单里的 oEmbed 接口有时还通
            val o = fetchOEmbed(OEmbed.endpointFor(url)) ?: return PageMeta(null, null, null)
            return PageMeta(
                o.title?.let { PageTitle.clean(it, url) ?: it },
                o.thumbnail?.takeIf { category.supportsCover() }?.let { fetchCover(it, url) },
                null,
                o.author,
            )
        }
        // 诊断（2026-10-06）：支付宝小程序口令那条没生效，先看清"取到的最终地址"到底是不是
        // 跳转后的 `render.alipay.com/…?scheme=…`。临时日志，定位完就撤。
        Log.i(TAG, "口令岛取页：$url → ${page.finalUrl}（${page.text.length} 字节）")
        // 淘宝系分享短链（m.tb.cn / e.tb.cn）的服务端返回是**空壳**，但闲鱼那版的壳里带着
        // **真正的商品地址**（`h5.m.goofish.com/item?…itemId=…`）—— 而 `m.tb.cn` 本身
        // **谁都不认**（实测系统里只有浏览器带 BROWSABLE），那个商品地址却闲鱼 App 认。
        // 所以把抠出来的地址当成「解析结果」，后面的打开目标与内容身份都用它。
        // （2026-10-06 用户问「为什么还会优先默认浏览器」—— 这就是根因与修法。）
        if (TaobaoShellJump.isShareShortHost(page.finalUrl)) {
            TaobaoShellJump.itemUrl(page.text)?.let { jump ->
                Log.i(TAG, "口令岛淘宝系短链找到真实商品地址：$jump")
                page = PageHtml(page.text, jump)
            }
        }
        // 支付宝**小程序分享口令**：短链 302 到 `render.alipay.com/p/s/i/?scheme=<alipays://…>`，
        // 而页面本身是 14KB 的支付宝壳（`<title>支付宝</title>`、无 og）→ **目标只在跳转后的地址里**。
        // 与抖音商城 `goods_detail` 同一个套路；这里同样把解出来的目标当"解析结果"。
        // （2026-10-06 用户给的两条真实链：顺丰 `appId=2019050764321917`、
        //   京东快递 `appId=2021004107678121` —— 见 [AlipayMiniProgram]。）
        AlipayMiniProgram.targetFromRedirectUrl(page.finalUrl)?.let { mini ->
            Log.i(TAG, "口令岛支付宝小程序：解出 appId=${AlipayMiniProgram.appId(mini)}")
            page = PageHtml(page.text, mini)
        }
        if (PasscodeRules.isPddHomepageFallback(url, page.finalUrl)) return PageMeta(null, null, null)
        onFinalUrl(page.finalUrl)
        val resolved = PasscodeRules.match(page.finalUrl)
            ?.takeIf { it.platform != PasscodeRules.P_GENERIC }
        val effectiveCategory = resolved?.category ?: category
        if (page.finalUrl != url && resolved != null) {
            fetchPlatformMeta(page.finalUrl, effectiveCategory)?.let { return it }
        }
        fetchXiaohongshuMeta(page)?.let { return it }
        fetchKuaishouMeta(page, effectiveCategory)?.let { return it }
        fetchAcfunMeta(page, effectiveCategory)?.let { return it }
        fetchPipixMeta(page, effectiveCategory)?.let { return it }
        fetchEcomMeta(page, effectiveCategory)?.let { return it }
        val htmlTitle = TITLE_TAG.find(page.text)?.groupValues?.get(1)
        val ogTitle = metaValue(page.text, "og:title")
        // 通用字段（2026-10-22）：OG → Twitter → <title> → JSON-LD，顺序参考 metascraper，见 PageMetaExtract。
        // 标题 / 作者 / 该有的封面缺了，再用 oEmbed 补一次：页面声明的接口优先，其次提供方清单。
        val fields = PageMetaExtract.extract(page.text, page.finalUrl)
        val oembed = if (fields.title == null || fields.author == null ||
            (effectiveCategory.supportsCover() && fields.image == null)
        ) {
            fetchOEmbed(fields.oembedUrl ?: OEmbed.endpointFor(page.finalUrl))
        } else {
            null
        }
        val rawTitle = fields.title ?: oembed?.title
        // 带上 finalUrl：长尾站点的站名尾巴按**页面自己的域名**剥（见 PageTitle）
        // 标题**就是平台名**时视为「没有标题」：知乎盐选分享页只有 4.9KB 的 open-in-app 壳，
        // 里面 `<title>知乎</title>` —— 大字行显示「知乎」等于没显示（2026-10-06 用户报
        // 「知乎没有正常解析」）。这类页面真正的去处是拉起 App，不是给内容。
        val platformLabel = resolved?.platform?.name
        val title = rawTitle?.let { PageTitle.clean(it, page.finalUrl) }
            ?.takeIf { platformLabel == null || it != platformLabel }
            ?: rawTitle?.trim()?.takeIf {
                effectiveCategory == PasscodeIsland.ContentCategory.MUSIC && it.isNotEmpty()
            }
        val artist = if (effectiveCategory == PasscodeIsland.ContentCategory.MUSIC) {
            metaValue(page.text, "music:musician_description")?.let(::cleanPageTitle)
                ?: ogTitle?.let {
                    htmlTitle?.split(Regex("""\s+[-–—]\s+"""), limit = 3)
                        ?.getOrNull(1)?.let(::cleanPageTitle)
                }
        } else {
            null
        }
        val fullTitle = if (title != null && artist != null) "$title - $artist" else title
        // B 站**动态 / opus** 页没有 og:image，但内嵌 JSON 的首图可用（见 [BiliDynamicShare]）。
        // 只在 og:image 缺失时兜底，且只在动态页上启用 —— 标题仍走上面的泛路径
        //（动态页的 `<title>` 是「<作者>的动态 - 哔哩哔哩」，属作者级信息）。
        val dynamicCover = if (isBiliDynamicPage(page.finalUrl)) {
            BiliDynamicShare.firstImage(page.text)
        } else {
            null
        }
        val cover = if (effectiveCategory.supportsCover()) {
            (ogImage(page.text, page.finalUrl) ?: dynamicCover ?: fields.image ?: oembed?.thumbnail)?.let {
                fetchCover(it, page.finalUrl)
            }
        } else if (effectiveCategory == PasscodeIsland.ContentCategory.WEB) {
            // 普通网页的左图用站点图标，替代所有网页都一样的浏览器图标。
            // 太小的（16 / 32px 的 favicon.ico）放大到 224 会糊，宁可不用。
            fields.icon?.let { fetchCover(it, page.finalUrl, minSide = ICON_MIN_SIDE) }
        } else {
            null
        }
        val author = (if (effectiveCategory == PasscodeIsland.ContentCategory.IMAGE_TEXT) {
            xiaohongshuAuthor(page.text, page.finalUrl)
        } else {
            null
        }) ?: fields.author ?: oembed?.author
        return PageMeta(fullTitle, cover, page.finalUrl, author)
    }

    /**
     * 请求一次 oEmbed 接口并解析（[OEmbed]）。地址为空、请求失败、返回不是 oEmbed 都返回 null。
     * 用 [fetchJson] 的 1.2 秒超时：这是补缺，不能把整条抓取拖长。
     */
    private fun fetchOEmbed(endpoint: String?): OEmbed.Result? {
        endpoint ?: return null
        val result = fetchJson(endpoint)?.let { OEmbed.parse(it.toString()) }
        Log.d(TAG, "口令岛 oEmbed：host=${Uri.parse(endpoint).host} 结果=${result != null}")
        return result
    }

    private fun fetchPlatformMeta(
        url: String,
        category: PasscodeIsland.ContentCategory,
        strict: Boolean = false,
    ): PageMeta? {
        if (metaSocialIdentity(url) != null) {
            return fetchMetaSocialPostMeta(url, category) ?: PageMeta(null, null, url)
        }
        // 微博这两类形态的网页都是壳（实测 888B / 3192B），走只读接口（见 WeiboApi 注释）。
        if (WeiboApi.articleId(url) != null) {
            fetchWeiboArticleMeta(url, category)?.let { return it }
        }
        if (WeiboApi.videoFid(url) != null) {
            fetchWeiboVideoMeta(url, category)?.let { return it }
        }
        // B 站视频：网页标题是当前分 P 的（多 P 稿件会显示成 P1 标题），稿件标题走接口（见 BiliVideoShare）
        BiliVideoShare.apiUrl(url)?.let { api ->
            fetchBiliVideoMeta(api, url, category)?.let { return it }
        }
        // 最右：走它自己的 share API（做法借 ParseHub，MIT）。酷安**不做 C 层** ——
        // 实测它的分享页只剩 6361 字节的壳（桌面 UA 也一样），用户 2026-10-06 拍板保持现状。
        if (ZuiyouShare.pid(url) != null) {
            fetchZuiyouMeta(url, category)?.let { return it }
        }
        if (category == PasscodeIsland.ContentCategory.IMAGE_TEXT) {
            val uri = Uri.parse(url)
            ZhihuShare.kind(url)?.let { kind ->
                return fetchZhihuMeta(url, kind) ?: if (strict) PageMeta(null, null, null) else null
            }
            DoubanTopicShare.topicId(url)?.let { id ->
                return fetchDoubanTopicMeta(url, id) ?: if (strict) PageMeta(null, null, null) else null
            }
            if (tiebaThreadId(uri) != null) {
                return fetchTiebaMeta(url) ?: if (strict) PageMeta(null, null, null) else null
            }
            if (weiboPostIdentity(uri) != null) {
                return fetchWeiboMeta(url) ?: if (strict) PageMeta(null, null, null) else null
            }
            if (hupuThreadId(uri) != null) {
                return fetchHupuMeta(url) ?: if (strict) PageMeta(null, null, null) else null
            }
            if (dongqiudiArticleId(uri) != null) {
                return fetchDongqiudiMeta(url) ?: if (strict) PageMeta(null, null, null) else null
            }
            if (doubanSubjectIdentity(uri) != null) {
                return fetchDoubanSubjectMeta(url) ?: if (strict) PageMeta(null, null, null) else null
            }
            fetchXPostMeta(url)?.let { return it }
        }
        if (category == PasscodeIsland.ContentCategory.MUSIC) {
            fetchQqSongMeta(url)?.let { return it }
            if (Uri.parse(url).host == "music.apple.com") {
                return fetchAppleMusicMeta(url) ?: if (strict) PageMeta(null, null, null) else null
            }
            if (spotifyTrackId(Uri.parse(url)) != null) {
                return fetchSpotifyTrackMeta(url) ?: if (strict) PageMeta(null, null, null) else null
            }
        }
        if (category == PasscodeIsland.ContentCategory.SHORT_VIDEO ||
            category == PasscodeIsland.ContentCategory.LONG_VIDEO
        ) {
            fetchYoutubeMeta(url, category)?.let { return it }
        }
        // 抖音：短链第一次调用时 URL 里没有作品号（返回 null），等 fetchHtml 重定向出
        // `iesdouyin.com/share/{video|note}/<号>/` 后第二次调用才命中。
        if ((category == PasscodeIsland.ContentCategory.SHORT_VIDEO ||
                category == PasscodeIsland.ContentCategory.IMAGE_TEXT) &&
            DouyinShare.isDouyinHost(Uri.parse(url).host.orEmpty())
        ) {
            fetchDouyinMeta(url, category)?.let { return it }
        }
        return null
    }

    private data class MetaSocialIdentity(val site: String, val id: String)

    private fun metaSocialIdentity(url: String): MetaSocialIdentity? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
        val parts = uri.pathSegments
        val instagram = host == "instagram.com" || host.endsWith(".instagram.com")
        val threads = host == "threads.net" || host.endsWith(".threads.net") ||
            host == "threads.com" || host.endsWith(".threads.com")
        val facebook = host == "facebook.com" || host.endsWith(".facebook.com")
        return when {
            instagram -> {
                val index = parts.indexOfFirst { it == "p" || it == "reel" || it == "tv" }
                parts.getOrNull(index + 1)?.takeIf {
                    index >= 0 && it.matches(Regex("[A-Za-z0-9_-]{5,20}"))
                }?.let { MetaSocialIdentity("instagram", it) }
            }
            threads -> parts.getOrNull(2)?.takeIf {
                parts.getOrNull(0)?.startsWith("@") == true && parts.getOrNull(1) == "post" &&
                    it.matches(Regex("[A-Za-z0-9_-]{5,20}"))
            }?.let { MetaSocialIdentity("threads", it) }
            facebook -> {
                val id = uri.getQueryParameter("story_fbid") ?: uri.getQueryParameter("v")
                    ?: parts.lastOrNull()?.takeIf {
                        parts.any { part -> part == "posts" || part == "videos" || part == "reel" }
                    }
                id?.takeIf { it.matches(Regex("(?:[0-9]{8,}|pfbid[A-Za-z0-9]{10,})")) }
                    ?.let { MetaSocialIdentity("facebook", it) }
            }
            else -> null
        }
    }

    /** Meta 的公开帖子预览只在抓取器响应中包含内容；校验规范地址后才采用。 */
    private fun fetchMetaSocialPostMeta(
        url: String,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val identity = metaSocialIdentity(url) ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        val previewUrl = if (identity.site == "threads") {
            Uri.parse(url).buildUpon().authority("www.threads.com").build().toString()
        } else {
            url
        }
        val page = fetchHtml(previewUrl) ?: run {
            Log.w(TAG, "公开预览不可用：site=${identity.site} stage=page")
            return null
        }
        val pageMs = SystemClock.elapsedRealtime() - startedAt
        val canonical = metaValue(page.text, "og:url")?.let(::decodeMetaText) ?: run {
            Log.w(TAG, "公开预览不可用：site=${identity.site} stage=canonical")
            return null
        }
        if (metaSocialIdentity(canonical) != identity) {
            Log.w(TAG, "公开预览不可用：site=${identity.site} stage=identity")
            return null
        }
        val heading = metaValue(page.text, "og:title")?.let(::decodeMetaText).orEmpty()
        val description = (metaValue(page.text, "og:description")
            ?: metaValue(page.text, "description"))?.let(::decodeMetaText)
        val title = when (identity.site) {
            "instagram" -> heading.substringAfter(" on Instagram: ", "")
                .trim(' ', '"', '\n').let(::cleanPageTitle)
            else -> description?.let(::cleanPageTitle)
        }
        val author = when (identity.site) {
            "instagram" -> heading.substringBefore(" on Instagram: ", "")
            "threads" -> heading.substringBefore(" (", "")
            else -> heading
        }.trim().takeIf { it.isNotEmpty() && it != "Instagram" && it != "Threads" && it != "Facebook" }
        val cover = if (category.supportsCover()) {
            ogImage(page.text, page.finalUrl)?.let(::decodeMetaText)?.takeIf { image ->
                val imageUri = Uri.parse(image)
                val host = imageUri.host.orEmpty()
                imageUri.scheme == "https" &&
                    (host == "cdninstagram.com" || host.endsWith(".cdninstagram.com") ||
                        host == "fbcdn.net" || host.endsWith(".fbcdn.net"))
            }?.let { fetchCover(it, url) }
        } else {
            null
        }
        Log.i(TAG, "公开预览：site=${identity.site} title=${title != null} cover=${cover != null} " +
            "pageMs=$pageMs totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return PageMeta(title, cover, url, author)
    }

    private fun decodeMetaText(value: String): String = android.text.Html.fromHtml(
        value, android.text.Html.FROM_HTML_MODE_COMPACT,
    ).toString().replace("\uFFFC", " ").replace(Regex("""\s+"""), " ").trim()

    private fun youtubeVideoId(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.")
        val id = when (host) {
            "youtu.be" -> uri.pathSegments.firstOrNull()
            "youtube.com", "youtube-nocookie.com" -> when (uri.pathSegments.firstOrNull()) {
                "watch" -> uri.getQueryParameter("v")
                "shorts", "live", "embed" -> uri.pathSegments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    /** YouTube oEmbed 给标题；长视频同时从官方缩略图域抓封面。 */
    private fun fetchYoutubeMeta(url: String, category: PasscodeIsland.ContentCategory): PageMeta? {
        val id = youtubeVideoId(url) ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        val coverTask = if (category == PasscodeIsland.ContentCategory.LONG_VIDEO) {
            java.util.concurrent.FutureTask<android.graphics.Bitmap?> {
                fetchCover("https://i.ytimg.com/vi/$id/hqdefault.jpg", url)
            }.also { Thread(it, "FP-YouTubeCover").start() }
        } else {
            null
        }
        return try {
            val encoded = java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
            val json = fetchJson("https://www.youtube.com/oembed?url=$encoded&format=json") ?: return null
            val title = json.optString("title").trim().takeIf { it.isNotEmpty() } ?: return null
            val apiMs = SystemClock.elapsedRealtime() - startedAt
            val cover = try {
                coverTask?.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
            val author = json.optString("author_name").trim().takeIf {
                category == PasscodeIsland.ContentCategory.LONG_VIDEO && it.isNotEmpty()
            }
            Log.i(TAG, "YouTube 预览：category=$category cover=${cover != null} " +
                "apiMs=$apiMs totalMs=${SystemClock.elapsedRealtime() - startedAt}")
            PageMeta(title, cover, url, author)
        } catch (_: Throwable) {
            null
        } finally {
            coverTask?.cancel(true)
        }
    }

    /** FxEmbed 公开 API 按状态 ID 返回正文和媒体；响应身份不一致时直接降级。 */
    private fun fetchXPostMeta(url: String): PageMeta? {
        val (handle, id) = PasscodeRules.xStatusIdentity(url) ?: return null
        val json = fetchJson("https://api.fxtwitter.com/$handle/status/$id", maxBytes = 96 * 1024)
            ?: return null
        if (json.optInt("code") != 200) return null
        val post = json.optJSONObject("tweet") ?: return null
        if (post.optString("id") != id) return null
        val title = cleanPageTitle(post.optString("text")) ?: return null
        val author = post.optJSONObject("author")?.optString("name")
            ?.trim()?.takeIf { it.isNotEmpty() }
        val media = post.optJSONObject("media")
        val image = media?.optJSONArray("photos")?.optJSONObject(0)?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: media?.optJSONArray("videos")?.optJSONObject(0)?.optString("thumbnail_url")
                ?.takeIf { it.isNotBlank() }
        val cover = image?.let { source ->
            val imageUri = Uri.parse(source)
            if (imageUri.scheme != "https" || imageUri.host != "pbs.twimg.com") return@let null
            val thumbnail = imageUri.buildUpon().clearQuery().apply {
                imageUri.queryParameterNames.filterNot { it == "name" }.forEach { key ->
                    imageUri.getQueryParameters(key).forEach { value -> appendQueryParameter(key, value) }
                }
                appendQueryParameter("name", "small")
            }.build().toString()
            fetchCover(thumbnail, url)
        }
        return PageMeta(title, cover, url, author)
    }

    /** QQ 音乐的网页是无封面的应用壳；歌曲接口返回可核对的歌名、歌手和专辑编号。 */
    private fun fetchQqSongMeta(url: String): PageMeta? {
        val uri = Uri.parse(url)
        if (uri.host != "y.qq.com" || !uri.path.orEmpty().contains("/songDetail/")) return null
        val mid = uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9]{14}")) } ?: return null
        return try {
            val json = fetchJson(
                "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=$mid&format=json",
            ) ?: return null
            val song = json.optJSONArray("data")?.optJSONObject(0) ?: return null
            if (song.optString("mid") != mid) return null
            val name = song.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
            val artists = song.optJSONArray("singer")
            val artist = (0 until (artists?.length() ?: 0))
                .mapNotNull { artists?.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                .joinToString("、")
            val albumMid = song.optJSONObject("album")?.optString("mid")
                ?.takeIf { it.matches(Regex("[A-Za-z0-9]{14}")) }
            val albumName = song.optJSONObject("album")?.optString("name")
                ?.trim()?.takeIf { it.isNotEmpty() && it != name }
            val cover = albumMid?.let {
                fetchCover("https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg", url)
            }
            val subtitle = listOfNotNull(artist.takeIf { it.isNotEmpty() }, albumName).joinToString(" · ")
                .takeIf { it.isNotEmpty() }
            PageMeta(if (artist.isNotEmpty()) "$name - $artist" else name, cover, url, subtitle)
        } catch (t: Throwable) {
            Log.d(TAG, "QQ 音乐歌曲元信息不可用：cause=${t.javaClass.simpleName}")
            null
        }
    }

    /** Apple Music 网页常给通用播放器标题，按歌曲或专辑 ID 查询公开目录。 */
    private fun fetchAppleMusicMeta(url: String): PageMeta? {
        val uri = Uri.parse(url)
        val trackId = uri.getQueryParameter("i")?.takeIf { it.matches(Regex("\\d{5,20}")) }
        val albumId = uri.lastPathSegment?.takeIf { it.matches(Regex("\\d{5,20}")) }
        val id = trackId ?: albumId ?: return null
        val region = uri.pathSegments.firstOrNull()
            ?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.lowercase() ?: "us"
        val json = fetchJson("https://itunes.apple.com/lookup?id=$id&country=$region") ?: return null
        val entries = json.optJSONArray("results") ?: return null
        val item = (0 until entries.length()).mapNotNull(entries::optJSONObject)
            .firstOrNull { it.optString(if (trackId != null) "trackId" else "collectionId") == id }
            ?: return null
        val name = item.optString(if (trackId != null) "trackName" else "collectionName")
            .trim().takeIf { it.isNotEmpty() } ?: return null
        val artist = item.optString("artistName").trim().takeIf { it.isNotEmpty() }
        val artwork = item.optString("artworkUrl100")
            .takeIf { it.startsWith("https://") }
            ?.replace("100x100bb", "300x300bb")
        val cover = artwork?.let { fetchCover(it, url) }
        return PageMeta(if (artist != null) "$name - $artist" else name, cover, url, artist)
    }

    private fun spotifyTrackId(uri: Uri): String? {
        if (uri.host != "open.spotify.com" || uri.pathSegments.size != 2 ||
            uri.pathSegments[0] != "track"
        ) return null
        return uri.pathSegments[1].takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
    }

    /** Spotify 歌曲嵌入页比主站详情页小；结构化初始状态包含艺人和封面。 */
    private fun fetchSpotifyTrackMeta(url: String): PageMeta? {
        return try {
            val trackId = spotifyTrackId(Uri.parse(url)) ?: return null
            val page = fetchHtml("https://open.spotify.com/embed/track/$trackId") ?: return null
            val state = SPOTIFY_NEXT_DATA.find(page.text)?.groupValues?.get(1) ?: return null
            val entity = JSONObject(state).optJSONObject("props")?.optJSONObject("pageProps")
                ?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity") ?: return null
            if (entity.optString("type") != "track" || entity.optString("id") != trackId ||
                entity.optString("uri") != "spotify:track:$trackId"
            ) return null
            val name = entity.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
            val artists = entity.optJSONArray("artists")
            val artist = (0 until (artists?.length() ?: 0))
                .mapNotNull { artists?.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                .joinToString("、")
            val images = entity.optJSONObject("visualIdentity")?.optJSONArray("image")
            val image = (0 until (images?.length() ?: 0))
                .mapNotNull { images?.optJSONObject(it) }
                .firstOrNull { it.optInt("maxWidth") >= 224 }
                ?.optString("url")
            val cover = image?.takeIf {
                val host = Uri.parse(it).host.orEmpty()
                it.startsWith("https://") && (host.endsWith(".spotifycdn.com") || host == "i.scdn.co")
            }?.let { fetchCover(it, url) }
            PageMeta(if (artist.isNotEmpty()) "$name - $artist" else name, cover, url,
                artist.takeIf { it.isNotEmpty() })
        } catch (_: Throwable) {
            null
        }
    }

    /** 桌面帖页可能返回 403；旧移动页能按帖子 ID 提供真实标题和所在贴吧。 */
    private fun tiebaThreadId(uri: Uri): String? {
        if (uri.host != "tieba.baidu.com") return null
        return Regex("""^/p/(\d+)/?$""").matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
    }

    private fun fetchTiebaMeta(url: String): PageMeta? {
        val uri = Uri.parse(url)
        val threadId = tiebaThreadId(uri) ?: return null
        val page = fetchHtml("https://tieba.baidu.com/mo/m?kz=$threadId") ?: return null
        if (!page.text.contains("kz:\"$threadId\"") && !page.text.contains("tid=$threadId")) return null
        val rawTitle = TITLE_TAG.find(page.text)?.groupValues?.get(1) ?: return null
        val title = cleanPageTitle(rawTitle)?.takeUnless { it == "百度贴吧" } ?: return null
        val forum = TIEBA_FORUM.find(page.text)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
        // 镇楼图当封面（用户 2026-10-22：「拿镇楼图就行」），一楼没图就还是 App 图标
        val cover = TiebaShare.firstFloorImage(page.text)?.let { fetchCover(it, url) }
        return PageMeta(title, cover, url, forum?.let { if (it.endsWith("吧")) it else "${it}吧" })
    }

    private fun hupuThreadId(uri: Uri): String? {
        if (uri.host !in setOf("m.hupu.com", "bbs.hupu.com")) return null
        return Regex("""^/(?:bbs(?:-share)?/)?(\d{6,12})(?:\.html)?/?$""")
            .matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
    }

    private fun fetchHupuMeta(url: String): PageMeta? {
        val threadId = hupuThreadId(Uri.parse(url)) ?: return null
        val page = fetchHtml("https://m.hupu.com/bbs/$threadId.html") ?: return null
        val data = SPOTIFY_NEXT_DATA.find(page.text)?.groupValues?.get(1) ?: return null
        return runCatching {
            val props = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps")
                ?: return@runCatching null
            if (props.optString("tid") != threadId) return@runCatching null
            val info = props.optJSONObject("threadInfo") ?: return@runCatching null
            val detail = info.optJSONObject("t_detail") ?: return@runCatching null
            if (detail.optString("tid") != threadId) return@runCatching null
            val title = cleanPageTitle(
                info.optJSONObject("t_desc")?.optString("title")
                    ?: detail.optString("title"),
            ) ?: return@runCatching null
            val author = info.optJSONObject("t_author")?.optString("username")
                ?.trim()?.takeIf { it.isNotEmpty() }
            val image = HUPU_CONTENT_IMAGE.findAll(detail.optString("content"))
                .firstNotNullOfOrNull { match ->
                    val candidate = match.groupValues[1].replace("&amp;", "&")
                    val host = Uri.parse(candidate).host?.lowercase(java.util.Locale.ROOT)
                    candidate.takeIf {
                        host == "hoopchina.com.cn" || host?.endsWith(".hoopchina.com.cn") == true
                    }
                }
            PageMeta(title, image?.let { fetchCover(it, url) }, url, author)
        }.getOrNull()
    }

    private fun fetchXiaohongshuMeta(page: PageHtml): PageMeta? {
        val uri = Uri.parse(page.finalUrl)
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
        if (host != "xiaohongshu.com" && !host.endsWith(".xiaohongshu.com")) return null
        val noteId = Regex("""^/(?:explore|discovery/item)/([0-9a-fA-F]{24})/?$""")
            .matchEntire(uri.path.orEmpty())?.groupValues?.get(1) ?: return null
        val marker = "window.__INITIAL_STATE__="
        val start = page.text.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        val end = page.text.indexOf("</script>", start).takeIf { it > start } ?: return null
        return runCatching {
            val state = JSONObject(page.text.substring(start, end).trimEnd(';'))
            val note = state.optJSONObject("noteData")?.optJSONObject("data")?.optJSONObject("noteData")
                ?: state.optJSONObject("note")?.optJSONObject("noteDetailMap")
                    ?.optJSONObject(noteId)?.optJSONObject("note")
                ?: return@runCatching null
            val actualId = note.optString("noteId").ifBlank { note.optString("id") }
            if (!actualId.equals(noteId, ignoreCase = true)) return@runCatching null
            val title = note.optString("title").takeIf { it.isNotBlank() }
                ?: note.optString("desc").lineSequence().firstOrNull { it.isNotBlank() }
            val author = note.optJSONObject("user")?.let { user ->
                user.optString("nickName").ifBlank { user.optString("nickname") }
            }?.trim()?.takeIf { it.isNotEmpty() }
            val images = note.optJSONArray("imageList")
            val image = (0 until (images?.length() ?: 0)).firstNotNullOfOrNull { index ->
                val raw = images?.optJSONObject(index)?.optString("url").orEmpty()
                val candidate = raw.replaceFirst("http://", "https://")
                val imageHost = Uri.parse(candidate).host?.lowercase(java.util.Locale.ROOT)
                candidate.takeIf {
                    candidate.startsWith("https://") &&
                        (imageHost == "xhscdn.com" || imageHost?.endsWith(".xhscdn.com") == true)
                }
            }
            PageMeta(title?.let(::cleanPageTitle), image?.let { fetchCover(it, page.finalUrl) },
                page.finalUrl, author)
        }.getOrNull()
    }

    /**
     * ⚠️ **抖音不做专用解析**（2026-10-06 真机取证）。
     *
     * 参考实现从分享页的 `window._ROUTER_DATA.loaderData` → `video_(id)/page` →
     * `videoInfoRes.item_list[0]` 取 `desc` / `author.nickname` / `video.cover`。
     * 用设备上的 curl 实测（iOS UA / Android UA × `iesdouyin` / `m.douyin` × 带参不带参，
     * 五种组合）：`_ROUTER_DATA` **在**，但里面 `item_list` / `nickname` / `url_list` / `play_addr`
     * **全是 0** —— 现在只有 `renderInSSR:1` 的水合壳，内容由前端 JS 另取。
     * 而 `www.douyin.com/video/<id>` 更是直接返回 21KB 的 JS 保护脚本壳（连 `_ROUTER_DATA` 都没有）。
     *
     * 所以这条**不是"没做"，是当前结构性做不到**（除非接签名接口 / 第三方解析站，两条都不做）。
     * 岛对抖音分享回落到「打开抖音」单行 + 平台图标 —— 与未安装模块时的观感一致。
     * 若将来抖音恢复 SSR 载荷，这段注释就是复现步骤。
     */

    /** 快手域名（短链 `v.kuaishou.com` / 落地页 `v.m.chenzhongtech.com`）。 */
    private fun isKuaishouHost(host: String): Boolean {
        val h = host.lowercase(java.util.Locale.ROOT)
        return h == "kuaishou.com" || h.endsWith(".kuaishou.com") ||
            h == "chenzhongtech.com" || h.endsWith(".chenzhongtech.com") ||
            h == "gifshow.com" || h.endsWith(".gifshow.com")
    }

    private fun isKuaishouTarget(url: String): Boolean =
        Uri.parse(url).host?.let(::isKuaishouHost) == true

    /**
     * 快手：内容在分享页的 `window.INIT_STATE` 里（顶层键被位移混淆，**内层字段名是明文**）。
     * 纯解析部分放在 [`KuaishouShare`]（`:core`，可离线单测）。
     *
     * 内容 ID 核对：落地地址 `/fw/photo/<短号>` 里的号必须出现在页面里
     * （真机上它出现在封面 URL 的 `clientCacheKey=` 参数上）—— 与小红书 / X 同一条纪律。
     */
    private fun fetchKuaishouMeta(
        page: PageHtml,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        if (!isKuaishouTarget(page.finalUrl)) return null
        val shareId = kuaishouPhotoId(page.finalUrl)
        if (shareId != null && !page.text.contains(shareId)) return null
        val fields = KuaishouShare.parse(page.text) ?: return null
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            // 类别不支持封面时（短视频 / 网页模板）**别下**：下了也会被 postIsland 丢掉
            //（真机上就是这么白烧了 26KB —— 图集改成图文之后才用得上，见 categoryFor）
            if (category.supportsCover()) {
                fields.cover?.let { fetchCover(it, page.finalUrl) }
            } else {
                null
            },
            page.finalUrl,
            fields.author,
        )
    }

    /** 落地地址里的快手作品短号：`/fw/photo/<号>` 或 `?photoId=`。 */
    private fun kuaishouPhotoId(url: String): String? {
        val uri = Uri.parse(url)
        Regex("""/fw/photo/([0-9A-Za-z_-]{6,})""").find(uri.path.orEmpty())
            ?.groupValues?.get(1)?.let { return it }
        return uri.getQueryParameter("photoId")?.takeIf { it.length >= 6 }
    }

    // ── 抖音：分享页必须带**匿名 ttwid** ────────────────────────────────

    /** 匿名 ttwid cookie 的内存缓存（1 小时）。 */
    private var ttwid: String? = null
    private var ttwidAtMs = 0L

    /**
     * 抖音的匿名 `ttwid` cookie —— **这是抖音能解析的关键**。
     *
     * 同一张分享页：不带它 `_ROUTER_DATA` 里**没有 `item_list`**，带上立刻有内容
     * （真机实测 2026-10-06：视频 item_list 0→1、图文 0→1 且页面 33KB→123KB）。
     * 注册端点匿名可用、**不需要登录**（POST 一次即得 cookie，缓存 1 小时）。
     *
     * 端点与请求体取自参考实现（`ttwid.bytedance.com/ttwid/union/register/`）。
     */
    private fun ensureTtwid(): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        ttwid?.let { if (now - ttwidAtMs < TTWID_TTL_MS) return it }
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(TTWID_REGISTER_URL).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(TTWID_BODY.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                Log.d(TAG, "口令岛抖音：ttwid 注册返回 ${conn.responseCode}")
                return null
            }
            val setCookie = conn.headerFields.entries
                .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                ?.value?.firstOrNull().orEmpty()
            val value = setCookie.split(';')
                .firstOrNull { it.trim().startsWith("ttwid=") }
                ?.substringAfter("ttwid=")?.trim()?.takeIf { it.isNotEmpty() }
            if (value == null) {
                Log.d(TAG, "口令岛抖音：ttwid 注册成功但没拿到 cookie")
                return null
            }
            ttwid = value
            ttwidAtMs = now
            value
        } catch (t: Throwable) {
            Log.d(TAG, "口令岛抖音：ttwid 注册失败 ${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 抖音元信息。唯一**带内容**的地址形态是 `www.iesdouyin.com/share/{video|note}/<号>/`
     * —— `www.douyin.com/video/<号>` 即使带 cookie 也只有 JS 壳（实测 72914 字节、无 `_ROUTER_DATA`）。
     *
     * 解析在 [`DouyinShare`]（`:core`，可离线单测）；这里只做 HTTP、**内容号核对**与封面下载。
     */
    private fun fetchDouyinMeta(url: String, category: PasscodeIsland.ContentCategory): PageMeta? {
        val shareUrl = DouyinShare.shareUrl(url) ?: return null
        val wantId = DouyinShare.awemeId(url)
        val cookie = ensureTtwid() ?: return null
        val page = fetchHtml(shareUrl, cookie = "ttwid=$cookie")
        if (page == null) {
            Log.d(TAG, "口令岛抖音：分享页抓取失败 $shareUrl")
            return null
        }
        val fields = DouyinShare.parse(page.text)
        if (fields == null) {
            Log.d(TAG, "口令岛抖音：分享页没有 item_list（长度=${page.text.length} cookie=${cookie.length}）")
            return null
        }
        if (wantId != null && fields.awemeId != null && fields.awemeId != wantId) {
            Log.d(TAG, "口令岛抖音：内容号不一致 want=$wantId got=${fields.awemeId}")
            return null
        }
        Log.i(
            TAG,
            "口令岛抖音：解析成功 title=${fields.title != null} author=${fields.author != null} " +
                "cover=${fields.cover != null} id=${fields.awemeId}",
        )
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            // 类别不支持封面时别下（同快手：下了也会被 postIsland 丢掉）
            if (category.supportsCover()) fields.cover?.let { fetchCover(it, page.finalUrl) } else null,
            page.finalUrl,
            fields.author,
        )
    }

    /**
     * AcFun：页面**没有 og:image**，但 `window.videoInfo` 里标题与封面都是明文
     * （见 [AcfunShare]，真机取证 2026-10-06）。标题比 `<title>` 干净（后者带站点尾巴）。
     *
     * ⚠️ 未真机验证（按用户 2026-10-06 指示"先补齐、不卡验证"）。
     */
    private fun fetchAcfunMeta(
        page: PageHtml,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val host = Uri.parse(page.finalUrl).host?.lowercase(java.util.Locale.ROOT) ?: return null
        if (host != "acfun.cn" && !host.endsWith(".acfun.cn")) return null
        val fields = AcfunShare.parse(page.text) ?: return null
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            if (category.supportsCover()) fields.cover?.let { fetchCover(it, page.finalUrl) } else null,
            page.finalUrl,
        )
    }

    /** 是不是 B 站**动态 / opus** 页（只有这类页面需要从内嵌 JSON 取首图当封面）。 */
    private fun isBiliDynamicPage(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
        if (host != "www.bilibili.com" && host != "bilibili.com" && host != "t.bilibili.com") return false
        val path = uri.path.orEmpty()
        return path.startsWith("/opus/") || path.startsWith("/dynamic/")
    }

    // ── 微博：头条文章与视频（fid）走只读接口 ────────────────────────────

    /** 带文本体的 POST（微博这两个只读接口都是 POST）。返回响应体（截断到 200KB）。 */
    private fun postText(url: String, body: String, contentType: String, referer: String): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("Referer", referer)
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                Log.d(TAG, "口令岛微博：接口返回 ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(200_000)
                val n = reader.read(buf)
                if (n <= 0) null else String(buf, 0, n)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "口令岛微博：接口请求失败 ${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 微博**头条文章**：网页是壳（实测 `card.weibo.com/article/…` 只有 3192 字节），
     * 走只读接口 `card.weibo.com/article/m/aj/detail`（端点与字段来自本地参考实现，
     * 见 [`WeiboApi`]）。**未真机验证**。
     */
    private fun fetchWeiboArticleMeta(
        url: String,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val id = WeiboApi.articleId(url) ?: return null
        val body = "_rid=" + java.util.UUID.randomUUID() + "&id=" + id +
            "&_t=" + System.currentTimeMillis()
        val json = postText(
            "https://card.weibo.com/article/m/aj/detail",
            body,
            "application/x-www-form-urlencoded",
            "https://card.weibo.com/",
        ) ?: return null
        val fields = WeiboApi.parseArticle(json) ?: return null
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            if (category.supportsCover()) fields.cover?.let { fetchCover(it, url) } else null,
            url,
            fields.author,
        )
    }

    /**
     * 微博**视频（fid）**：网页是壳（实测 `video.weibo.com/show?fid=` 只有 888 字节），
     * 走 `h5.video.weibo.com/api/component`（体是**原样**的 `data={…}`，不是 URL 编码 ——
     * 与参考实现一致）。**未真机验证**。
     */
    private fun fetchWeiboVideoMeta(
        url: String,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val fid = WeiboApi.videoFid(url) ?: return null
        val body = """data={"Component_Play_Playinfo":{"oid":"$fid"}}"""
        val json = postText(
            "https://h5.video.weibo.com/api/component?page=/show/$fid",
            body,
            "application/x-www-form-urlencoded",
            "https://h5.video.weibo.com/show/$fid",
        ) ?: return null
        val fields = WeiboApi.parseVideo(json) ?: return null
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            if (category.supportsCover()) fields.cover?.let { fetchCover(it, url) } else null,
            url,
            fields.author,
        )
    }

    // ── 皮皮虾 / 最右（做法借 ParseHub，MIT）──────────────

    /**
     * 皮皮虾：分享页里 `<script id="RENDER_DATA">` 是 **URL 编码的 JSON**
     * （见 [`PipixShare`]）。⚠️ **无真实样本**，按 ParseHub 的字段路径实现，端到端未验证。
     */
    private fun fetchPipixMeta(
        page: PageHtml,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val host = Uri.parse(page.finalUrl).host?.lowercase(java.util.Locale.ROOT) ?: return null
        if (!PipixShare.isPipixUrl(host)) return null
        val fields = PipixShare.parse(page.text) ?: return null
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            if (category.supportsCover()) fields.cover?.let { fetchCover(it, page.finalUrl) } else null,
            page.finalUrl,
        )
    }

    /**
     * 最右：URL 取 `pid` → `POST share.xiaochuankeji.cn/planck/share/post/detail_h5` 体 `{"pid":…}`
     * （做法与字段见 [`ZuiyouShare`]）。**未真机验证**。
     */
    private fun fetchZuiyouMeta(
        url: String,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val pid = ZuiyouShare.pid(url) ?: return null
        val json = postText(
            "https://share.xiaochuankeji.cn/planck/share/post/detail_h5",
            """{"pid":$pid}""",
            "application/json",
            "https://share.xiaochuankeji.cn/",
        ) ?: return null
        val fields = ZuiyouShare.parse(json) ?: return null
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            if (category.supportsCover()) fields.cover?.let { fetchCover(it, url) } else null,
            url,
            fields.author,
        )
    }

    private fun fetchBiliVideoMeta(
        api: String,
        url: String,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        // 响应随分 P 数增长（155 P 的样本 41KB），默认 32KB 上限会截断
        val json = fetchJson(api, maxBytes = 512 * 1024, requestHeaders = mapOf("User-Agent" to UA)) ?: return null
        if (json.optInt("code", -1) != 0) {
            Log.d(TAG, "口令岛 B 站视频：接口 code=${json.optInt("code", -1)}")
            return null
        }
        val data = json.optJSONObject("data") ?: return null
        val title = data.optString("title").trim().takeIf { it.isNotEmpty() }?.let(::cleanPageTitle) ?: return null
        val author = data.optJSONObject("owner")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        val cover = if (category.supportsCover()) {
            BiliVideoShare.coverUrl(data.optString("pic"))?.let { fetchCover(it, url) }
        } else null
        return PageMeta(title, cover, url, author)
    }

    // ── 电商详情（京东 / 抖音商城）────────────────────────────────────────

    /**
     * 京东：`window._itemOnly.skuName` 比 `<title>` 干净（后者带 `【图片 价格 品牌 评论】-京东`），
     * `imageurl` 是主图（相对路径，拼 CDN 主机）。`_itemOnly` 在页面 **offset 14365**（96KB 上限内）。
     * 见 [`EcomShare.Jd`]。
     */
    private fun fetchJdMeta(
        page: PageHtml,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val host = Uri.parse(page.finalUrl).host?.lowercase(java.util.Locale.ROOT) ?: return null
        if (host != "jd.com" && !host.endsWith(".jd.com")) return null
        val fields = EcomShare.Jd.parse(page.text) ?: return null
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            if (category.supportsCover()) fields.cover?.let { fetchCover(it, page.finalUrl) } else null,
            page.finalUrl,
        )
    }

    /**
     * 抖音商城：**数据就在短链跳转后的 URL 里**（`goods_detail=` 是一段 URL 编码的 JSON），
     * 页面本身只有 11905 字节的壳 → 这里**零额外请求**，直接解析 `page.finalUrl`。
     * 见 [`EcomShare.DouyinMall`]。
     */
    private fun fetchDouyinMallMeta(
        page: PageHtml,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val fields = EcomShare.DouyinMall.parseFromUrl(page.finalUrl) ?: return null
        Log.i(
            TAG,
            "口令岛抖音商城：从跳转地址取到商品 title=${fields.title != null} " +
                "cover=${fields.cover != null} price=${fields.price}",
        )
        return PageMeta(
            fields.title?.let(::cleanPageTitle),
            if (category.supportsCover()) fields.cover?.let { fetchCover(it, page.finalUrl) } else null,
            page.finalUrl,
            // 价格进次行（`min_price` 单位是分，实测 2990 = ¥29.90）
            EcomShare.Pdd.formatPrice(fields.price),
        )
    }

    /**
     * 拼多多：**内容层拿不到**（见 [`EcomShare.Pdd`]），但展示侧跟完重定向后的**最终地址**里有
     * `goods_id`（内容身份，见 `duplicateIdentity` 的 `pdd-goods:` 分支）与
     * `_oak_share_snapshot_num`（快照价）。
     *
     * **关键**：它的通用分享信息必须屏蔽 —— 实测 `og:title=拼多多商城`、
     * `og:image=…/base/share_logo.jpg`（平台 logo）；显示出来就是错的，所以这里直接返回
     * `title=null, cover=null`（让岛回落分享文案 + 平台图标），并且**不再走泛路径**。
     */
    private fun fetchPddMeta(
        page: PageHtml,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? {
        val host = Uri.parse(page.finalUrl).host?.lowercase(java.util.Locale.ROOT) ?: return null
        if (!host.endsWith("yangkeduo.com") && !host.endsWith("pinduoduo.com")) return null
        if (EcomShare.Pdd.goodsId(page.finalUrl) == null) return null
        val price = EcomShare.Pdd.formatPrice(EcomShare.Pdd.snapshotPriceCents(page.finalUrl))
        Log.i(TAG, "口令岛拼多多：goods_id 命中，快照价=$price（通用分享图/标题已屏蔽）")
        return PageMeta(null, null, page.finalUrl, price)
    }

    /** 电商：拼多多（屏蔽通用图/标题 + 快照价）→ 京东（页面结构）→ 抖音商城（跳转地址）。 */
    private fun fetchEcomMeta(
        page: PageHtml,
        category: PasscodeIsland.ContentCategory,
    ): PageMeta? = fetchPddMeta(page, category)
        ?: fetchJdMeta(page, category)
        ?: fetchDouyinMallMeta(page, category)

    private fun dongqiudiArticleId(uri: Uri): String? {
        if (uri.host !in setOf("dongqiudi.com", "www.dongqiudi.com", "m.dongqiudi.com")) return null
        return Regex("""^/articles?/(\d{6,12})(?:\.html)?/?$""")
            .matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
    }

    private fun fetchDongqiudiMeta(url: String): PageMeta? {
        val articleId = dongqiudiArticleId(Uri.parse(url)) ?: return null
        val json = fetchJson("https://api.dongqiudi.com/v2/article/detail/$articleId", 512 * 1024)
            ?: return null
        if (json.optInt("code", -1) != 0) return null
        val article = json.optJSONObject("data") ?: return null
        if (article.optString("article_id") != articleId) return null
        val title = cleanPageTitle(article.optString("title")) ?: return null
        val author = article.optString("writer").trim().takeIf { it.isNotEmpty() }
        val thumbnail = article.optString("thumb").takeIf { image ->
            image.startsWith("https://") && Uri.parse(image).host?.let { host ->
                host == "qunliao.info" || host.endsWith(".qunliao.info") ||
                    host == "dongqiudi.com" || host.endsWith(".dongqiudi.com")
            } == true
        }
        val cover = thumbnail?.let { fetchCover(it, url) }
        return PageMeta(title, cover, url, author)
    }

    private fun doubanSubjectIdentity(uri: Uri): Pair<String, String>? {
        val host = uri.host ?: return null
        val path = uri.path.orEmpty()
        val mobile = Regex("""^/(movie|book)/subject/(\d{5,10})/?$""").matchEntire(path)
        if (host == "m.douban.com" && mobile != null) {
            return mobile.groupValues[1] to mobile.groupValues[2]
        }
        val type = when (host) {
            "movie.douban.com" -> "movie"
            "book.douban.com" -> "book"
            else -> return null
        }
        val id = Regex("""^/subject/(\d{5,10})/?$""")
            .matchEntire(path)?.groupValues?.get(1) ?: return null
        return type to id
    }

    private fun fetchDoubanSubjectMeta(url: String): PageMeta? {
        val (type, id) = doubanSubjectIdentity(Uri.parse(url)) ?: return null
        val page = fetchHtml("https://m.douban.com/$type/subject/$id/") ?: return null
        val canonical = metaValue(page.text, "og:url") ?: return null
        if (doubanSubjectIdentity(Uri.parse(canonical)) != (type to id)) return null
        val title = metaValue(page.text, "og:title")?.let(::cleanPageTitle) ?: return null
        val image = ogImage(page.text, page.finalUrl)?.takeIf { candidate ->
            val host = Uri.parse(candidate).host.orEmpty()
            candidate.startsWith("https://") && (host == "doubanio.com" || host.endsWith(".doubanio.com"))
        }
        return PageMeta(title, image?.let { fetchCover(it, page.finalUrl) }, url)
    }

    /** 知乎：只有 App 的 UA 拿得到整页（见 [ZhihuShare]），[fetchHtml] 按主机切 UA。 */
    private fun fetchZhihuMeta(url: String, kind: ZhihuShare.Kind): PageMeta? {
        val page = fetchHtml(url) ?: return null
        val fields = ZhihuShare.parse(kind, page.text) ?: run {
            Log.d(TAG, "口令岛知乎：$kind 页面没解析出标题（${page.text.length} 字节）")
            return null
        }
        val cover = fields.image?.let { fetchCover(it, page.finalUrl) }
        return PageMeta(cleanPageTitle(fields.title) ?: fields.title, cover, url, fields.subtitle)
    }

    /** 豆瓣小组帖 / 个人帖：与贴吧同一排布 —— 标题、小组名（个人帖用作者）、楼主首图（见 [DoubanTopicShare]）。 */
    private fun fetchDoubanTopicMeta(url: String, id: String): PageMeta? {
        val json = fetchJson(
            DoubanTopicShare.apiUrl(id),
            maxBytes = 256 * 1024,
            requestHeaders = mapOf("User-Agent" to UA, "Referer" to "https://m.douban.com/"),
        ) ?: return null
        if (json.optString("id") != id) return null
        val title = json.optString("title").trim().takeIf { it.isNotEmpty() }?.let(::cleanPageTitle) ?: return null
        val group = json.optJSONObject("group")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        val author = json.optJSONObject("author")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        val image = DoubanTopicShare.imageUrl(
            json.optJSONArray("photos")?.optJSONObject(0)?.optJSONObject("image")
                ?.optJSONObject("large")?.optString("url"),
        ) ?: DoubanTopicShare.imageUrl(json.optString("cover_url"))
        return PageMeta(title, image?.let { fetchCover(it, url) }, url, group ?: author)
    }

    /** 微博网页会落访客页；移动端详情 JSON 可直接按公开 bid 读取。 */
    private fun weiboPostIdentity(uri: Uri): Pair<String?, String>? {
        val host = uri.host?.removePrefix("www.") ?: return null
        val segments = uri.pathSegments
        val (uid, bid) = when {
            host in setOf("weibo.com", "weibo.cn", "m.weibo.cn") &&
                segments.size == 2 && segments[0].all(Char::isDigit) ->
                segments[0] to segments[1]
            host in setOf("weibo.cn", "m.weibo.cn") &&
                segments.size == 2 && segments[0] in setOf("detail", "status") ->
                null to segments[1]
            else -> return null
        }
        // bid（`H0YMiaafH`）或纯数字 mid（`4302336342422693`，2026-10-23 用户给的样例就是这种）都认
        if (!bid.matches(Regex("[A-Za-z0-9]{8,12}")) && !bid.matches(Regex("[0-9]{14,20}"))) return null
        return uid to bid
    }

    private fun fetchWeiboMeta(url: String): PageMeta? {
        val (uid, bid) = weiboPostIdentity(Uri.parse(url)) ?: return null
        val json = fetchJson(
            "https://m.weibo.cn/statuses/show?id=$bid",
            maxBytes = 512 * 1024,
            requestHeaders = mapOf(
                "User-Agent" to UA,
                "Referer" to "https://m.weibo.cn/",
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        ) ?: return null
        if (json.optInt("ok") != 1) return null
        val post = json.optJSONObject("data") ?: return null
        if (post.optString("bid") != bid && post.optString("mid") != bid && post.optString("id") != bid) return null
        val user = post.optJSONObject("user")
        if (uid != null && user?.optString("id") != uid) return null
        val text = android.text.Html.fromHtml(
            post.optString("text"), android.text.Html.FROM_HTML_MODE_COMPACT,
        ).toString().replace("\uFFFC", " ").replace(Regex("""\s+"""), " ").trim()
        // 开头的 `#话题#` 挤掉了正文（2026-10-23 样例：「#英雄联盟S8##S8总决赛#班师得胜…」），只剩话题时保留原文
        val body = text.replace(Regex("""^(?:#[^#\s]{1,40}#\s*)+"""), "").trim().ifEmpty { text }
        val title = cleanPageTitle(body) ?: return null
        val author = user?.optString("screen_name")?.trim()?.takeIf { it.isNotEmpty() }
        val image = post.optJSONArray("pics")?.optJSONObject(0)?.let { pic ->
            pic.optJSONObject("large")?.optString("url")?.takeIf { it.isNotBlank() }
                ?: pic.optString("url")
        } ?: post.optJSONObject("page_info")?.optJSONObject("page_pic")?.optString("url")
        val cover = image?.takeIf {
            it.startsWith("https://") && Uri.parse(it).host?.endsWith(".sinaimg.cn") == true
        }?.let { fetchCover(it, "https://m.weibo.cn/detail/$bid") }
        return PageMeta(title, cover, url, author)
    }

    private fun fetchJson(
        url: String,
        maxBytes: Int = 32 * 1024,
        requestHeaders: Map<String, String> = emptyMap(),
    ): JSONObject? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            requestHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            if (conn.responseCode != 200) return null
            val buf = ByteArray(maxBytes)
            var size = 0
            conn.inputStream.use { stream ->
                while (size < buf.size) {
                    val read = stream.read(buf, size, buf.size - size)
                    if (read <= 0) break
                    size += read
                }
            }
            if (size <= 0) return null
            JSONObject(String(buf, 0, size, Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 小红书笔记的作者在页面初始状态里，与当前 note id 绑定，避免误取推荐内容作者。 */
    private fun xiaohongshuAuthor(html: String, pageUrl: String): String? {
        return try {
            val host = Uri.parse(pageUrl).host.orEmpty()
            if (host != "xiaohongshu.com" && !host.endsWith(".xiaohongshu.com")) return null
            val id = Uri.parse(pageUrl).lastPathSegment ?: return null
            val marker = "window.__INITIAL_STATE__="
            val start = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
            val end = html.indexOf("</script>", start).takeIf { it > start } ?: return null
            JSONObject(html.substring(start, end).trimEnd(';'))
                .optJSONObject("note")?.optJSONObject("noteDetailMap")?.optJSONObject(id)
                ?.optJSONObject("note")?.optJSONObject("user")?.optString("nickname")
                ?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    /** 抓网页 HTML（预加载用）；小红书初始状态在 96KB 之后，给笔记页单独留足空间。 */
    private fun fetchHtml(url: String, cookie: String? = null): PageHtml? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val host = Uri.parse(url).host.orEmpty()
            val metaPreview = host == "instagram.com" || host.endsWith(".instagram.com") ||
                host == "threads.net" || host.endsWith(".threads.net") ||
                host == "threads.com" || host.endsWith(".threads.com") ||
                host == "facebook.com" || host.endsWith(".facebook.com")
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                when {
                    metaPreview || host == "fb.watch" || host == "fb.me" -> UA_META_PREVIEW
                    host.endsWith(".xiaohongshu.com") -> UA_DESKTOP
                    host == "zhihu.com" || host.endsWith(".zhihu.com") -> UA_ZHIHU
                    else -> UA
                },
            )
            // 抖音分享页**必须带匿名 ttwid cookie** 才有内容（实测 item_list 0→1）；
            // 其它调用方不传 cookie，行为不变。必须在 responseCode（= 发请求）之前设置。
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            val responseCode = conn.responseCode
            val finalUrl = conn.url.toString()
            val finalUri = Uri.parse(finalUrl)
            if (finalUri.path.orEmpty().startsWith("/404/") ||
                finalUri.host.orEmpty().startsWith("visitor.") ||
                finalUri.host.orEmpty().startsWith("passport.")
            ) return null
            if (responseCode !in 200..299) {
                return if (finalUrl != url) PageHtml("", finalUrl) else null
            }
            val type = conn.contentType ?: ""
            if (!type.contains("html", ignoreCase = true)) {
                return if (finalUrl != url) PageHtml("", finalUrl) else null
            }
            val isXiaohongshu = finalUri.host?.let { it == "xiaohongshu.com" ||
                it.endsWith(".xiaohongshu.com") } == true
            // 快手的关键字段在页面 **91~94%** 处（真机 183KB 页 → 约 172KB），96KB 会
            // 直接读不到（2026-10-06 实测偏移：headUrl 171449 / caption 171757）。
            val isKuaishou = finalUri.host?.let(::isKuaishouHost) == true
            val buf = ByteArray(
                when {
                    isXiaohongshu -> 192 * 1024
                    isKuaishou -> 256 * 1024
                    // 知乎回答的正文首图在约 122KB 处（真机 199KB 页）
                    finalUri.host?.endsWith("zhihu.com") == true -> 256 * 1024
                    else -> 96 * 1024
                },
            )
            var off = 0
            conn.inputStream.use { s ->
                while (off < buf.size) {
                    val n = s.read(buf, off, minOf(if (metaPreview) 8 * 1024 else buf.size, buf.size - off))
                    if (n <= 0) break
                    off += n
                    if (metaPreview && off >= 8 * 1024) {
                        val preview = HtmlCharset.decode(buf, type, off)
                        if (metaValue(preview, "og:url") != null &&
                            metaValue(preview, "og:title") != null &&
                            (metaValue(preview, "og:description") != null ||
                                metaValue(preview, "og:image") != null)
                        ) break
                    }
                }
            }
            // 按响应头 / BOM / `<meta charset>` 探测字符集再解码：中文站里 GBK/GB18030
            // 仍然常见（长尾老站更多），一律按 UTF-8 解会得到**乱码标题**。
            PageHtml(HtmlCharset.decode(buf, type, off), finalUrl)
        } catch (t: Throwable) {
            Log.d(TAG, "口令岛网页元信息不可用：host=${Uri.parse(url).host} cause=${t.javaClass.simpleName}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    // og:image 的**属性顺序不固定**（property 可能在 content 后面），所以不能用一个
    // 大正则一把梭：先切开所有 <meta …>，命中了 og:image 的那个再从里面取 content。
    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val META_NAME = Regex("""(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val META_OG_IMAGE =
        Regex("""(?:property|name)\s*=\s*["']\s*og:image(?::url)?\s*["']""", RegexOption.IGNORE_CASE)
    private val META_CONTENT = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun metaValue(html: String, key: String): String? =
        META_TAG.findAll(html).firstNotNullOfOrNull { tag ->
            val name = META_NAME.find(tag.value)?.groupValues?.get(1)
            if (!name.equals(key, ignoreCase = true)) return@firstNotNullOfOrNull null
            META_CONTENT.find(tag.value)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }

    /** 从 HTML 里取内容图的绝对 URL（协议相对 / 站内相对都补全）。 */
    private fun ogImage(html: String, pageUrl: String): String? {
        val isXiaohongshu = Uri.parse(pageUrl).host?.let {
            it == "xiaohongshu.com" || it.endsWith(".xiaohongshu.com")
        } == true
        for (tag in META_TAG.findAll(html)) {
            if (!META_OG_IMAGE.containsMatchIn(tag.value)) continue
            // 属性值里的 `&amp;` 要还原：头条封面是签名地址，原样带着 `&amp;` 请求直接 403（2026-10-22 真机）
            val src = META_CONTENT.find(tag.value)?.groupValues?.get(1)?.trim().orEmpty().replace("&amp;", "&")
            val url = when {
                src.startsWith("http") -> src
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> originOf(pageUrl) + src
                else -> continue
            }
            // 小红书第一张 og:image 常是 picasso-static 的平台默认图，后续才是笔记配图。
            if (isXiaohongshu && Uri.parse(url).host?.endsWith(".xhscdn.com") != true) continue
            return if (isXiaohongshu && url.startsWith("http://")) {
                url.replaceFirst("http://", "https://")
            } else {
                url
            }
        }
        return null
    }

    /** 取 URL 的 `scheme://host`（封面下载要拿它当 Referer、补相对路径）。 */
    private fun originOf(url: String): String = try {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}"
    } catch (_: Throwable) {
        "https://"
    }

    /**
     * 下载封面并压成缩略图。尺寸按官方模板 18 新图文组件的建议（方图 ≥ 224×224px）：
     * 224² ARGB ≈ 196KB，与 App 图标（216² ARGB ≈ 186KB）合计仍远低于 binder 事务上限；
     * 再大只是白烧流量。
     */
    private fun fetchCover(imageUrl: String, pageUrl: String, minSide: Int = 0): android.graphics.Bitmap? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val imageUri = Uri.parse(imageUrl)
            val requestUrl = if (imageUri.host?.endsWith(".music.126.net") == true &&
                imageUri.getQueryParameter("param") == null
            ) {
                imageUri.buildUpon().scheme("https").appendQueryParameter("param", "224y224").build().toString()
            } else {
                imageUrl
            }
            conn = java.net.URL(requestUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            // 封面可能是大图：抖音商城 CDN 那张主图实测 **1.1MB / 1200×1200 PNG**，
            // 2.5 秒读不完（2026-10-06 实测）。
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            // 部分 CDN（B 站 i0.hdslb.com 等）校验 Referer，缺了直接 403
            conn.setRequestProperty("Referer", originOf(pageUrl) + "/")
            val type = conn.contentType ?: ""
            if (!type.contains("image", ignoreCase = true)) return null
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            var truncated = false
            conn.inputStream.use { s ->
                while (total < COVER_MAX_BYTES) {
                    val n = s.read(chunk)
                    if (n <= 0) break
                    out.write(chunk, 0, n)
                    total += n
                }
                // 到了上限**还能读出东西** = 图被截断。半张图绝不能拿去渲染：PNG 截断后
                // 仍能解码出**上半部分**，用户看到的就是「封面显示不全」（2026-10-06 实测：
                // 抖音商城那张 1200×1200 主图有 **1.1MB**，而上限当时是 512KB）。
                if (total >= COVER_MAX_BYTES && s.read() > 0) truncated = true
            }
            if (truncated) {
                Log.d(
                    TAG,
                    "口令岛封面被截断（超过 ${COVER_MAX_BYTES / 1024}KB），整张丢弃：host=${imageUri.host}",
                )
                return null
            }
            downscaleCover(out.toByteArray(), minSide)
        } catch (t: Throwable) {
            Log.d(TAG, "口令岛封面不可用：host=${Uri.parse(imageUrl).host} cause=${t.javaClass.simpleName}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 采样解码后中心方裁，位图本身带圆角（官方建议圆角由图片自带，见 [roundCorners]）。 */
    private fun downscaleCover(bytes: ByteArray, minSide: Int = 0): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (minOf(bounds.outWidth, bounds.outHeight) < minSide) {
            Log.d(TAG, "口令岛图标太小不用：${bounds.outWidth}×${bounds.outHeight}")
            return null
        }
        var sample = 1
        // 方裁取的是短边，所以按短边采样 —— 按宽采样时 16:9 大图的高会被采到 224 以下
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_W) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return try {
            val cropped = cropToSquare(decoded)
            val rounded = roundCorners(cropped)
            if (rounded !== cropped) cropped.recycle()
            rounded
        } finally {
            decoded.recycle()
        }
    }

    /**
     * 中心方裁到 [COVER_W]²。七类内容（含长视频，用户 2026-10-22 选方案 A）统一进模板 18 的方图位。
     *
     * **为什么必须自己裁**（2026-09-27 真机实测）：岛上的渲染链对图片按
     * 「适配放入」处理 —— 4:3 的 B 站封面进 114dp 的位以后**左右各留 14.8dp 黑边**。
     * 出图前就裁成方图，塞进方图位里必然铺满，不依赖渲染链怎么解释 scaleType。
     */
    private fun cropToSquare(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val dstW = COVER_W
        val dstH = COVER_W
        val out = android.graphics.Bitmap.createBitmap(
            dstW, dstH, android.graphics.Bitmap.Config.ARGB_8888,
        )
        // 源矩形 = 目标比例下能取到的最大内接区域，居中
        val srcW: Float
        val srcH: Float
        val left: Float
        val top: Float
        if (src.width * dstH > src.height * dstW) {
            srcH = src.height.toFloat()
            srcW = srcH * dstW / dstH
            left = (src.width - srcW) / 2f
            top = 0f
        } else {
            srcW = src.width.toFloat()
            srcH = srcW * dstH / dstW
            left = 0f
            top = (src.height - srcH) / 2f
        }
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(
            src,
            android.graphics.Rect(
                left.toInt(),
                top.toInt(),
                (left + srcW).toInt().coerceIn(1, src.width),
                (top + srcH).toInt().coerceIn(1, src.height),
            ),
            android.graphics.Rect(0, 0, dstW, dstH),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    /** 官方建议圆角取图片边长的 1/4（48dp 图配 12dp），224px 方图即 56px。 */
    private fun roundCorners(src: android.graphics.Bitmap): android.graphics.Bitmap = try {
        val out = android.graphics.Bitmap.createBitmap(
            src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(out)
        val r = src.width / 4f
        val path = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
                r, r, android.graphics.Path.Direction.CW,
            )
        }
        canvas.clipPath(path)
        canvas.drawBitmap(src, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        out
    } catch (_: Throwable) {
        src
    }

    /** 承运商 → 已装的官方 App 包名（或 null），10 分钟有效：按名称匹配要扫一遍桌面应用，不能每次都扫。 */
    private val carrierAppCache = HashMap<String, Pair<String?, Long>>()

    private fun carrierAppPackage(context: Context, carrier: String): String? {
        val now = SystemClock.uptimeMillis()
        synchronized(carrierAppCache) {
            carrierAppCache[carrier]?.takeIf { now - it.second < 10 * 60_000L }?.let { return it.first }
        }
        val pm = context.packageManager
        val pkg = try {
            ExpressBadge.KNOWN_APPS[carrier]?.firstOrNull { pm.getLaunchIntentForPackage(it) != null }
                ?: pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                ).firstOrNull { ri ->
                    ExpressBadge.appLabelMatches(carrier, ri.loadLabel(pm).toString())
                }?.activityInfo?.packageName
        } catch (_: Throwable) {
            null
        }
        synchronized(carrierAppCache) { carrierAppCache[carrier] = pkg to now }
        return pkg
    }

    /** 承运商徽标（扁平几何图形，绘制见 [ExpressBadgePainter]，几何在 core 的 [ExpressBadge]）。画失败返回 null（回落到 App 图标）。 */
    private fun expressBadgeBitmap(badge: ExpressBadge.Badge): android.graphics.Bitmap? = ExpressBadgePainter.paint(badge, COVER_W)

    /** 封面输出边长（px），也是解码采样的下限。 */
    private const val COVER_W = 224
    private const val COVER_MAX_BYTES = 2 * 1024 * 1024

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    private const val UA_META_PREVIEW = "facebookexternalhit/1.1"

    /** 知乎对浏览器 / 爬虫 UA 一律给 403 校验页，只放行自家 App 的 UA（真机 2026-10-23，见 ZhihuShare）。 */
    private const val UA_ZHIHU =
        "com.zhihu.android/Futureve/10.12.0 Mozilla/5.0 (Linux; Android 14) ZhihuHybrid"

    private val TITLE_TAG = Regex("""<title[^>]*>([\s\S]{1,300}?)</title>""", RegexOption.IGNORE_CASE)
    private val SPOTIFY_NEXT_DATA = Regex(
        """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE,
    )
    private val TIEBA_FORUM = Regex("forum_name:\\s*\"([^\"]{1,50})\"")
    private val HUPU_CONTENT_IMAGE = Regex(
        """<img\b[^>]*?\ssrc\s*=\s*["'](https://[^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * HTML 实体与常见清洗：去标签、解码实体、剥站点尾巴、截断。
     *
     * 逻辑已搬到 `:core` 的 [`PageTitle`]（纯字符串，能用固定样本离线钉住）；
     * 这里保留同签名入口，调用点不必逐个改。**通用抓取路径**另有一处直接调
     * `PageTitle.clean(raw, pageUrl)`，以便按页面域名剥长尾站点的站名尾巴。
     */
    private fun cleanPageTitle(raw: String): String? = PageTitle.clean(raw)

    /**
     * 兜底 #2：到点主动取消。岛渲染链若不认 `timeoutAfter`，这条保证照样消失。
     *
     * 一次事件是**两条**通知（焦点 + 通知栏标准行），所以这里收一对 id：一起取消、
     * 一起清身份记录。`cancelRunnables` 只按第一个 id 记账（一对只排一个定时任务）。
     */
    private fun scheduleFallbackCancel(nm: NotificationManager, ids: List<Int>, timeoutMs: Long) {
        val key = ids.first()
        cancelRunnables.remove(key)?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            cancelRunnables.remove(key)
            for (id in ids) {
                launchTargets.remove(id)
                notifiedIds.remove(id)
                // 岛已经消失 → 身份记录一并清掉，否则「岛没了，再复制同内容反而上不了岛」
                identityById.remove(id)?.let { publishedIdentities.remove(it) }
                try {
                    nm.cancel(id)
                } catch (_: Throwable) {
                }
            }
        }
        cancelRunnables[key] = r
        mainHandler.postDelayed(r, timeoutMs)
    }

    private fun Context.cancelNotification(id: Int) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
    }

    /** 这条通知是不是我们发的、还挂着的口令岛（[PasscodeIslandLongPress] 用来认按钮）。 */
    fun ownsIsland(id: Int): Boolean = id in notifiedIds && launchTargets.containsKey(id)

    /**
     * 长按「打开」：弹系统的「打开方式」（小米样式，带「仅一次 / 总是」），列出能接这条链接的 App。
     *
     * 打开哪个地址：原本的打开目标（去掉我们钉的包名）能被两个以上 App 接住就用它
     *（`com.baidu.tieba://` 官方贴吧和贴吧 Lite 都认）；否则用候选链里的网页地址 ——
     * `huputiyu://` 这类私有 scheme 只有一个接收方，选择框会直接跳进去（2026-10-22 真机）。
     * 显式组件的目标（负一屏快递详情）没有可选项 → 返回 false，长按不消费。
     *
     * 选择框直接指定 `com.android.intentresolver/.ResolverActivity`：系统只在「没有默认 App」时才弹它，
     * 用户点过「总是」以后普通启动就不再弹；显式拉起则每次都列全（真机 adb 验证）。
     * 拉不起来（换 ROM / 类名变了）退到 `Intent.createChooser`（分享样式的底部面板）。
     */
    fun openWithChooser(context: Context, id: Int): Boolean {
        val launch = launchTargets[id] ?: return false
        val pm = context.packageManager
        fun handlers(i: Intent) = runCatching {
            pm.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
        val original = Intent(launch.intent).takeIf { it.component == null && it.data != null }
            ?.apply { setPackage(null); flags = 0 }
        val web = launch.webUrl?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).addCategory(Intent.CATEGORY_BROWSABLE) }
        val target = original?.takeIf { handlers(it).size >= 2 } ?: web ?: original ?: return false
        val resolver = Intent(target)
            .setComponent(android.content.ComponentName(RESOLVER_PKG, "$RESOLVER_PKG.ResolverActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 只有一个 App 能接时，ResolverActivity 和 createChooser **都会直接打开它**（真机：普通网页只装了
        // Via，长按就跳进了 Via —— 用户 2026-10-22 报「长按和点击没区别」）。这时不跳，提示一句「只有 XX 能打开」。
        val only = handlers(target)
        if (only.size < 2) {
            val name = only.firstOrNull()?.let { pkg ->
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            }
            android.widget.Toast.makeText(
                context,
                if (name != null) "只有「$name」能打开这条链接" else "没有其他应用能打开这条链接",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            Log.i(TAG, "口令岛长按：只有一个可选应用，不弹选择框 id=$id pkg=${only.firstOrNull()}")
            return true
        }
        val chooser = Intent.createChooser(target, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (attempt in listOf(resolver, chooser)) {
            try {
                context.startActivity(attempt)
                Log.i(TAG, "口令岛长按：已弹出选择框 id=$id via=${attempt.component?.shortClassName ?: "chooser"} scheme=${target.data?.scheme}")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "口令岛长按：选择框打开失败 id=$id via=${attempt.component?.shortClassName ?: "chooser"}", t)
            }
        }
        return false
    }

    private const val RESOLVER_PKG = "com.android.intentresolver"

    private fun openInSmallWindow(context: Context, id: Int) {
        val launch = launchTargets[id] ?: return
        val target = launch.intent
        val pkg = launch.pkg
        val hook = hookContext
        val openMode = hook?.prefs?.rawString(Keys.pref(Catalog.PASSCODE_ISLAND, "window_preset"), "flyme")
            ?.takeIf { it in setOf("system", "flyme", "fullscreen") } ?: "flyme"
        // 无专属 App（歧义）= 系统只会给浏览器这类通用处理器。**直接交给系统打开**
        //（用户 2026-10-06 拍板：能直达就直达，少一步 —— 曾经试过显式包
        // `Intent.createChooser` 强制弹选择器，用户试完觉得多一步，改回直达）。
        // 选择器本身是系统 UI，塞进 freeform 小窗会很怪，所以这一档**无视用户的窗口档位**强制全屏。
        // 支付宝 alipays:// 由 SchemeLauncherActivity 接（singleTask + 独立 taskAffinity），它再把小程序拉进
        // 主任务：小窗模式下会先同时出现两个小窗、跳板窗约 2 秒后自己收回（用户 2026-10-22 报，
        // 真机 dumpsys 抽样实锤）。跳板的任务归属改不了，所以支付宝一律全屏打开。
        val trampoline = target.data?.scheme.equals("alipays", ignoreCase = true)
        if (launch.ambiguous || openMode == "fullscreen" || trampoline) {
            try {
                context.startActivity(Intent(target))
                Log.i(
                    TAG,
                    if (launch.ambiguous) {
                        "口令岛已全屏打开（无专属 App，按默认处理器）：id=$id 图标位=$pkg"
                    } else {
                        "口令岛已全屏打开：pkg=$pkg id=$id"
                    },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "口令岛目标打开失败：pkg=$pkg id=$id", t)
            }
            return
        }
        if (hook != null) {
            val options = listOfNotNull(
                FanShortcuts.miuiOptions(context, pkg),
                FanShortcuts.publicOptions(context, withBounds = false),
            )
            for (option in options) {
                SystemUiEnv.forceLaunchNewTask(hook, option)
                SystemUiEnv.applyPasscodePlacement(hook, context, option, openMode)
                try {
                    context.startActivity(Intent(target), option.toBundle())
                    Log.i(TAG, "口令岛已请求小窗打开：pkg=$pkg id=$id mode=$openMode")
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "口令岛小窗启动失败，尝试下一级：pkg=$pkg id=$id", t)
                }
            }
        }
        try {
            context.startActivity(Intent(target))
            Log.i(TAG, "口令岛已回退普通打开：pkg=$pkg id=$id")
        } catch (t: Throwable) {
            Log.e(TAG, "口令岛目标打开失败：pkg=$pkg id=$id", t)
        }
    }

    private fun ensureChannel(context: Context) {
        // 通知服务在极早启动的进程里可能尚未就绪（实测 system_server 误装时
        // getNotificationChannel 的缓存 binder 为 null → NPE）。通道建不成只影响
        // 提示样式，不值得让安装失败 —— 吞掉，通知用默认通道行为发送。
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "口令上岛", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        // 岛是瞬时提醒，不该在通知栏长期留档；超时后它也不该发出声音轰炸
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
            }
            // 旧版的「通知栏留痕」通道已不用（2026-10-22 只发岛），删掉免得设置里留一条空通道
            nm.deleteNotificationChannel(CHANNEL_ID_SHADE)
        }
    }

    /**
     * `miui.focus.param`（param_v2）的岛参数。
     *
     * 字段结构不再靠猜：顶层必须是 `{"param_v2": {...}}` 包一层（第一版直接把
     * param_island 放顶层是错的），内部字段逐项对齐两处**实测可用**的来源：
     *  - HyperIsland（MIT）ScreenRecorderHook / islanddispatch 的构建代码与《模板库 AI 版》；
     *  - 本机 SystemUI 插件反编译的解析端+ 官方开发指南（pId=2131）。
     *
     * - 摘要态：大岛 A 区 = 目标图标，B 区 = 文本组件 `textInfo`（静态短摘要）；小岛 = 图标 + 短摘要。
     * - 展开态 = 官方模板 18：A 区 `iconTextInfo`（方图 + 主 / 次文本）、
     *   E 区 `picInfo` type 1（右上目标 App 图标，方图是封面时才给）、D 区 `textButton`（分享 / 打开）。
     *
     * 深色岛读 `srcDark` / `picDark`，浅色读 `src` / `pic`（`ModuleNewImageTextViewHolder`），
     * 两套都给同一张图 —— 只给一套，另一种主题下图位会被整个隐藏。
     * 组装错误的最坏表现是「岛不渲染、回落普通通知」，不会崩 SystemUI。
     */
    private fun buildFocusParam(
        capsuleText: String,
        shareText: String,
        expanded: PasscodeIslandTemplate.Expanded,
        pictureKey: String,
        openIntent: Intent,
        shareIntent: Intent,
        accent: String,
        timeoutMs: Long,
    ): String {
        val iconPicInfo = JSONObject().put("type", 1).put("pic", KEY_ISLAND_ICON).put("picDark", KEY_ISLAND_ICON)
        // 摘要态：摄像头左侧 A 区只放目标图标；右侧 B 区放静态短摘要（纯文字用文本组件，
        // 不用 imageTextInfoRight type 2 —— 那是「带最右侧图标」的图文组件）。
        val bigLeft = JSONObject()
            .put("type", 1)
            .put("picInfo", iconPicInfo)
            .put("textInfo", JSONObject().put("title", ""))
        // 小岛（胶囊）只放图标：摘要态不要文字（用户 2026-10-22「摘要态不要文字都可以」）。
        // 短摘要仍进 ticker / aodTitle / shareData —— 那些是无障碍、息屏与拖拽分享用的。
        val smallArea = JSONObject()
            .put("picInfo", iconPicInfo)
        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("updatable", false)
            .put("enableFloat", true)
            .put("islandFirstFloat", true)
            .put("business", "tsuzuri_passcode")
            .put("content", capsuleText)
            // notifyId 用「包名+id」字符串 —— 解析端按它判重/更新
            .put("notifyId", "${PasscodeIsland.SYSTEM_UI_PACKAGE}$CHANNEL_ID")
            .put("ticker", capsuleText)
            // 息屏：官方要求 aodTitle 必填；aodPic 默认应用图标，代发身份下必须显式给
            .put("aodTitle", capsuleText)
            .put("tickerPic", KEY_ISLAND_ICON)
            .put("tickerPicDark", KEY_ISLAND_ICON)
            .put("aodPic", KEY_ISLAND_ICON)
            .put(
                "param_island",
                JSONObject()
                    .put("islandPriority", 1)
                    // 岛上**拖拽/分享**的载荷（官方字段）。
                    // `shareContent` 放**原文**（snippet）—— 分享出去必须是用户复制进来那一份，
                    // 不能用我们提炼的短标题（用户 2026-10-06：「不要只是个标题」）。
                    .put(
                        "shareData",
                        JSONObject()
                            .put("title", capsuleText)
                            .put("content", capsuleText)
                            .put("shareContent", shareText),
                    )
                    .put("islandTimeout", (timeoutMs / 1000L).toInt())
                    // islandProperty=2 = 点击摘要态先展开（EXPAND 态渲染下面的模板 18），再点按钮跳转
                    .put("islandProperty", 2)
                    // 平台强调色：口令/核心内容的官方染色通道
                    .put("highlightColor", accent)
                    .put("bigIslandArea", JSONObject().put("imageTextInfoLeft", bigLeft))
                    .put("smallIslandArea", smallArea),
            )
            // ── 展开态：官方模板 18 ──
            // A 区 新图文组件：方图（封面或目标 App 图标）+ 主文本 + 次要文本（官方必传，core 保证非空）。
            // animIconInfo.type=0 = 静态图（1/2 是视频 / 动效）。
            .put(
                "iconTextInfo",
                JSONObject()
                    .put(
                        "animIconInfo",
                        JSONObject().put("type", 0).put("src", pictureKey).put("srcDark", pictureKey),
                    )
                    .put("title", expanded.title)
                    .put("content", expanded.content),
            )
            // D 区 按钮组件 4：两枚文字按钮。「分享」不写颜色，用系统默认的中性次按钮；
            // 「打开」用平台色底 + 白字作主按钮（对齐 PDF 模板 18 示例：次按钮灰、主按钮高亮）。
            .put(
                "textButton",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("action", SHARE_ACTION_KEY)
                            .put("actionTitle", "分享")
                            .put("actionIntentType", 1)
                            .put("actionIntent", shareIntent.toUri(Intent.URI_INTENT_SCHEME)),
                    )
                    .put(
                        JSONObject()
                            .put("action", OPEN_ACTION_KEY)
                            .put("actionTitle", "打开")
                            .put("actionTitleColor", "#FFFFFF")
                            .put("actionTitleColorDark", "#FFFFFF")
                            .put("actionBgColor", accent)
                            .put("actionBgColorDark", accent)
                            .put("actionIntentType", 2)
                            .put("actionIntent", openIntent.toUri(Intent.URI_INTENT_SCHEME)),
                    ),
            )
        // E 区 识别图形组件 1：右上目标 App 图标。方图本身就是 App 图标时不给，避免两个同样的图标。
        if (expanded.showAppMark) paramV2.put("picInfo", iconPicInfo)
        return JSONObject().put("param_v2", paramV2).toString()
    }

    /**
     * 息屏（AOD）岛的 RemoteViews。
     *
     * 息屏链路（反编译实锤）：FocusCoordinator.onEntryAdded 只有在
     * `extras.getParcelable("miui.focus.rvAod") instanceof RemoteViews` 时才把通知登记进
     * `mAodFocusNotifications`，AodFocusControllerV2.applyAodFocusView 随后把这个
     * RemoteViews 直接 apply 渲染 —— **不需要 hook 任何东西，备齐这个 extra 就走通息屏**。
     *
     * 布局用 android 框架的公共布局（`two_line_list_item` + `text1`/`text2` 都是公开 API），
     * 由 SystemUI 的 Context 充气，零自有资源依赖。充气失败时 applyAodFocusView 自己
     * 会吞异常返回 null —— 最坏表现就是息屏不显示，亮屏岛不受影响。
     */
    private fun buildAodRemoteViews(label: String, snippet: String): RemoteViews {
        val rv = RemoteViews("android", android.R.layout.two_line_list_item)
        rv.setTextViewText(android.R.id.text1, label)
        rv.setTextViewText(android.R.id.text2, snippet)
        return rv
    }

    /** 宿主 Application context（同 RecognitionBubbleStyle / LauncherDrawerObserver 的招）。 */
    private fun hostAppContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    /**
     * 这个跳转目标有没有人接得住（`queryIntentActivities` 非空）。
     *
     * 候选链（[PasscodeIsland.EXTRA_TARGET_ALTS]）靠它逐个试 —— **只有展示侧有
     * `PackageManager`**，所以「依次尝试」这件事只能在这里做，引擎侧给不了答案。
     * 任何异常当「接不住」（宁可降级也不让异常逃逸）。
     */
    /** 白名单里的 `intent:` 目标解析成显式 Intent；不在白名单或解析失败返回 null。 */
    private fun allowedIntentTarget(target: String): Intent? =
        target.takeIf { it.startsWith("intent:") && PasscodeRules.isAllowedIntentTarget(it) }
            ?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }

    private fun canResolve(context: Context, target: String): Boolean = if (target.startsWith("intent:")) {
        allowedIntentTarget(target)?.resolveActivity(context.packageManager) != null
    } else try {
        context.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_VIEW).setData(Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )
            .isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    private fun canHandlePackage(context: Context, target: String, pkg: String): Boolean = try {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse(target))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(pkg),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ).any { it.activityInfo?.packageName == pkg }
    } catch (_: Throwable) {
        false
    }

    /**
     * 这条规则是不是快递单号（`express_prefixed` / `express_numeric`）。
     *
     * **按规则 id 判，不靠 core 的形态猜**：纯数字单号（中通/申通/韵达）和订单号
     * 形态完全一样，形态猜不出来；而「快递不抓网页标题、大字行放承运商」这条
     * 分流必须稳。
     */
    /** 岛上显示的域名：优先注册域（`m.weibo.cn` → `weibo.cn`）；公共后缀表不可用时退回去掉 www 的主机名。 */
    private fun domainOf(target: String): String? = try {
        Uri.parse(target).host?.let { host ->
            PublicSuffix.registrableDomain(host) ?: host.removePrefix("www.")
        }?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** 从封面取「打开」按钮色：先缩到 [ACCENT_SAMPLE]² 再交给 [CoverAccent]。 */
    private fun coverAccent(bmp: android.graphics.Bitmap): String? = try {
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, ACCENT_SAMPLE, ACCENT_SAMPLE, true)
        val px = IntArray(ACCENT_SAMPLE * ACCENT_SAMPLE)
        small.getPixels(px, 0, ACCENT_SAMPLE, 0, 0, ACCENT_SAMPLE, ACCENT_SAMPLE)
        if (small !== bmp) small.recycle()
        CoverAccent.pick(px)
    } catch (_: Throwable) {
        null
    }

    private const val ACCENT_SAMPLE = 24

    /** 站点图标的最小边长：再小放大到方图位就糊了。 */
    private const val ICON_MIN_SIDE = 96

    private fun summaryText(
        category: PasscodeIsland.ContentCategory,
        title: String,
        core: String?,
    ): String {
        val short = (core ?: title).trim().trim('【', '】', '「', '」', '《', '》')
        if (short.length in 2..4 && short.any { it.code > 0x7f } &&
            short.none { it == '/' || it == ':' }
        ) return short
        return PasscodeIslandTemplate.categoryLabel(category)
    }

    // 短视频 2026-10-22 起也用头图（用户：「能拿到头图更好」），左图是封面、App 图标退到角标
    private fun PasscodeIsland.ContentCategory.supportsCover(): Boolean = when (this) {
        PasscodeIsland.ContentCategory.SHORT_VIDEO,
        PasscodeIsland.ContentCategory.LONG_VIDEO,
        PasscodeIsland.ContentCategory.IMAGE_TEXT,
        PasscodeIsland.ContentCategory.MUSIC,
        PasscodeIsland.ContentCategory.ECOMMERCE,
        -> true
        else -> false
    }

    /**
     * 判「通用网页处理器」的探针 URL：浏览器 / WebView 对**任何** http(s) 都接得住，
     * 所以一个包连它都接，就说明它不是这条链接的「专属 App」，只是兜底。
     */
    private const val GENERIC_WEB_PROBE = "https://example.org"

    /** 抖音匿名 ttwid 的注册端点（匿名可用，不需要登录）与请求体。 */
    private const val TTWID_REGISTER_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
    private const val TTWID_TTL_MS = 60L * 60 * 1000
    private const val TTWID_BODY =
        """{"region":"cn","aid":1768,"needFid":false,"service":"www.douyin.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}"""

    /**
     * 这条链接有没有**专门为它声明**的 App —— 浏览器等通用网页处理器会被排除。
     *
     * 判据：同一包对该 URL 的 match 必须**强于**它对 [GENERIC_WEB_PROBE] 的 match。
     * 与 [pickDomainApp] 的区别是**不做 `resolveActivity` 兜底**：那个兜底会返回浏览器，
     * 而这里要的是「有没有专属 App」这个是非判断（[metaTarget] 用它决定要不要保留原链接）。
     */
    private fun domainAppFor(context: Context, intent: Intent): String? = try {
        val pm = context.packageManager
        val flag = android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        val genericIntent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse(GENERIC_WEB_PROBE))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val genericMatches = pm.queryIntentActivities(genericIntent, flag)
            .mapNotNull { result ->
                result.activityInfo?.packageName?.let { it to result.match }
            }.toMap()
        // 同一包对目标域名的匹配强于通用网页时，说明它声明了这个站点的 App Link。
        pm.queryIntentActivities(intent, flag)
            .filter { result ->
                val pkg = result.activityInfo?.packageName ?: return@filter false
                // 2026-10-22 真机：美团 dpurl.cn、公众号文章在这里选出了 com.android.intentresolver，
                // 岛上画成安卓默认图标 —— 选择器不是 App
                !pkg.isResolverPackage() && result.match > (genericMatches[pkg] ?: 0)
            }
            .maxWithOrNull(compareBy<android.content.pm.ResolveInfo> { it.match }.thenBy { it.priority })
            ?.activityInfo?.packageName
    } catch (_: Throwable) {
        null
    }

    private fun domainAppFor(context: Context, url: String): String? =
        domainAppFor(
            context,
            Intent(Intent.ACTION_VIEW).setData(Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
        )

    /**
     * 先选对目标域名有专门匹配的 App，再走系统默认解析。
     * 比较同一包对目标 URL 与 [GENERIC_WEB_PROBE] 的 match 强度，通配网页处理器不会被误选；
     * 两次探针都带 BROWSABLE，避开此前盒马通配 webview 被误当目标 App 的问题。
     */
    private fun pickDomainApp(context: Context, intent: Intent): String? = try {
        domainAppFor(context, intent)
            ?: context.packageManager
                .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                // 多浏览器且无默认时 resolveActivity 给的是解析器/选择器 —— 那不是 App
                ?.takeIf { it != "android" && !it.contains("resolver", ignoreCase = true) }
    } catch (_: Throwable) {
        null
    }

    /**
     * 系统对这条链接的解析结果 —— **App Links 的权威答案**（我们自己的启发式只是近似）。
     *
     * 返回解析器包名（A12+ 是 `com.android.intentresolver`，老 ROM 是 `android`）表示
     * **有多个候选、没有唯一 handler**；返回 null 表示没人接得住。调用方据此决定要不要
     * `setPackage` 钉死（见 [isResolverPackage]）。
     */
    private fun systemResolvePackage(context: Context, intent: Intent): String? = try {
        context.packageManager
            .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    } catch (_: Throwable) {
        null
    }

    /**
     * 系统给的答案是「选择器」吗 —— 即**有歧义**。
     *
     * 判据同 [pickDomainApp] 里过滤解析器那一处（那里是为了不把选择器当成 App）。
     * `null` **不算歧义**：没人接得住时维持原来的启发式 + 钉定行为，避免把
     * 「谁也打不开」误变成「弹一个空选择器」。
     */
    private fun String?.isResolverPackage(): Boolean =
        this != null && (this == "android" || contains("resolver", ignoreCase = true))

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /** 已声明且可处理的目标 App 优先；其余交给系统原生默认解析。 */
    private fun resolveTargetPackage(
        context: Context,
        intent: Intent,
        declaredPkg: String?,
    ): String? {
        if (!declaredPkg.isNullOrBlank() && canHandlePackage(context, intent.dataString ?: return null, declaredPkg)) {
            return declaredPkg
        }
        pickDomainApp(context, intent)?.let { return it }
        if (intent.data?.scheme !in listOf("http", "https")) return null
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("https://example.org"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val browserPkg = pickDomainApp(context, browserIntent) ?: return null
            browserPkg.takeIf { pkg ->
                context.packageManager.queryIntentActivities(
                    Intent(intent).setPackage(pkg),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                ).any { it.activityInfo?.packageName == pkg }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 目标 App 图标缓存读取：pkg → (彩色全图, 单色剪影)，未命中才加载。 */
    private fun appIcon(context: Context, pkg: String): Pair<android.graphics.Bitmap?, android.graphics.Bitmap?> {
        if (targetIconCache.containsKey(pkg)) return targetIconCache[pkg]!!
        val pair = loadAppIcon(context, pkg)
        targetIconCache[pkg] = pair
        return pair
    }

    /**
     * 目标 App 图标 = **桌面图标**（用户拍板 2026-09-25：主题化，与桌面一致，
     * 不用 APK 原始图标 —— 之前两态不一致的根源：展开岛走 APK 原始资源、
     * 胶囊态走插件默认管线出桌面图标）。统一链：
     *  1. MIUISystemUIPlugin 的 `com.miui.maml.util.AppIconsHelper.getIconDrawable` ——
     *     与超级岛默认图标同一条桌面管线（MamlAdaptiveIcon，主题引擎加工后的样子）；
     *     插件类不在 SystemUI 主 classpath，用 PathClassLoader 挂插件 APK 反射调；
     *  2. 回落 `getApplicationIcon` —— HyperOS 预合成图标，观感接近桌面。
     * 彩色全图进岛/胶囊/通知中心；自适应图标的前景层另提一份**单色剪影**给状态栏
     * （官方规范：状态栏只渲染 alpha 着单色）。
     */
    private fun loadAppIcon(
        context: Context,
        pkg: String,
    ): Pair<android.graphics.Bitmap?, android.graphics.Bitmap?> = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        val drawable = mamlDesktopIcon(context, info) ?: pm.getApplicationIcon(info)
        Pair(toDrawableBitmap(drawable), monoSilhouette(drawable))
    } catch (_: Throwable) {
        Pair(null, null)
    }

    /**
     * 自适应图标前景层 → 白色单色剪影（状态栏 smallIcon 用）。
     * 只保留 alpha 通道、RGB 全置白 —— 与原生通知图标的渲染语义一致。
     * 非 AdaptiveIconDrawable（含 maml 管线拿不到前景层的情况）返回 null，调用方回落。
     */
    private fun monoSilhouette(d: android.graphics.drawable.Drawable): android.graphics.Bitmap? = try {
        val fg = (d as? android.graphics.drawable.AdaptiveIconDrawable)?.foreground ?: return null
        val size = 96
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val inset = (size * 0.18f).toInt()
        fg.setBounds(inset, inset, size - inset, size - inset)
        fg.draw(canvas)
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        for (i in px.indices) px[i] = (px[i] ushr 24) shl 24 or 0x00FFFFFF
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        bmp
    } catch (_: Throwable) {
        null
    }

    /**
     * MIUI 桌面图标管线（maml AppIconsHelper）。类在 MIUISystemUIPlugin.apk 里，
     * SystemUI 主 classpath 没有 → PathClassLoader 挂 APK 只加载这一个类，Method 缓存。
     * 插件不存在/类签名变化/加载失败一律返回 null（上层回落），异常不逃逸。
     */
    private fun mamlDesktopIcon(
        context: Context,
        info: android.content.pm.ApplicationInfo,
    ): android.graphics.drawable.Drawable? {
        if (mamlGetIconMethod == null && !mamlMethodTried) {
            mamlMethodTried = true
            try {
                val cl = pluginClassLoader
                    ?: dalvik.system.PathClassLoader(MIUI_PLUGIN_APK, javaClass.classLoader)
                        .also { pluginClassLoader = it }
                mamlGetIconMethod = Class.forName("com.miui.maml.util.AppIconsHelper", true, cl)
                    .getMethod(
                        "getIconDrawable",
                        Context::class.java,
                        android.content.pm.PackageItemInfo::class.java,
                        android.content.pm.PackageManager::class.java,
                    )
            } catch (_: Throwable) {
            }
        }
        return try {
            mamlGetIconMethod?.invoke(null, context, info, context.packageManager) as? android.graphics.drawable.Drawable
        } catch (_: Throwable) {
            null
        }
    }

    /** Drawable → 方形位图，整张原样绘制（桌面管线给的已是遮罩/主题后的最终形态）。 */
    private fun toDrawableBitmap(d: android.graphics.drawable.Drawable): android.graphics.Bitmap {
        if (d is android.graphics.drawable.BitmapDrawable && d.bitmap != null) return d.bitmap
        val size = 216
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bmp
    }

    /**
     * 从模块 APK 的 assets 读位图成 [android.graphics.drawable.Icon]。
     *
     * 为什么走 assets 而不是资源 id：hook 模块没有自己的 res（资源都在 `:ui`，
     * 依赖方向 `:ui → :hook` 不允许反查 R），而宿主进程里按包名 getIdentifier
     * 依赖「模块包已安装且版本一致」，多一层时序。模块类由模块 APK 的
     * ClassLoader 加载，`getResourceAsStream("assets/...")` 直接流读 APK 内条目，
     * 零外部依赖。读不到返回 null（调用方回落），异常不逃逸。
     */
    private fun assetIcon(path: String): android.graphics.drawable.Icon? =
        assetBitmap(path)?.let { android.graphics.drawable.Icon.createWithBitmap(it) }

    private fun assetBitmap(path: String): android.graphics.Bitmap? = try {
        val bytes = javaClass.classLoader
            ?.getResourceAsStream("assets/$path")
            ?.use { it.readBytes() }
            ?: return null
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Throwable) {
        null
    }
}

/**
 * 有上限的表：满了按**插入顺序**淘汰最旧的一项。
 * 用插入顺序而不是访问顺序：访问顺序下 `get` 也会改结构，没加锁的读方会出并发问题。
 */
private fun <K, V> boundedMap(max: Int): MutableMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, false) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
}
