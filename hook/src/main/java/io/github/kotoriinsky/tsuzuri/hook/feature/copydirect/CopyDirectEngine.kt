package io.github.kotoriinsky.tsuzuri.hook.feature.copydirect

import android.app.Dialog
import android.app.Activity
import android.app.Application
import android.widget.ImageView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.CopyDirect
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PasscodeCustomRules
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.core.PasscodeRules
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.compat.CopyDirectCompat
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** 只消费原生已准入的复制事件；后台解析后单选呈现，关闭时全部卸载。 */
object CopyDirectEngine : Feature(Catalog.require(Catalog.PASSCODE_ISLAND)) {
    private data class Payload(val intent: Intent, val sourceText: String, val shareOnly: Boolean = false)
    private val payloads = ConcurrentHashMap<String, Payload>()
    private val nativeShares = ConcurrentHashMap<String, Payload>()
    private val sourceSnapshots = ConcurrentHashMap<String, String>()
    private val longPress = ThreadLocal<Payload?>()
    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var compat: CopyDirectCompat? = null
    @Volatile private var config = CopyDirect.Config()
    @Volatile private var custom: List<PasscodeCustomRules.Rule> = emptyList()
    private var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var context: HookContext? = null
    private var browserReceiver: BroadcastReceiver? = null
    private var activities: Application.ActivityLifecycleCallbacks? = null
    private var capsuleIcons: CopyDirectCapsuleIcons? = null

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = !isSystemServer && CopyDirect.supportsEngineProcess(processName)

    override fun install(ctx: HookContext): InstallReport {
        if (!CopyDirect.config(ctx.prefs).enabled) return InstallReport(0, notes = listOf("原生增强关闭"))
        val bridge = runCatching { CopyDirectCompat.create(ctx.classLoader) }.getOrElse {
            ctx.log(Log.WARN, "复制直达兼容检查失败：${it.message}")
            return InstallReport(0, notes = listOf("原生引擎版本或符号不兼容"))
        }
        if (installedHooks > 0) return InstallReport(installedHooks)
        context = ctx
        compat = bridge
        worker = HandlerThread("TsuzuriCopyDirect").apply { start() }
        val background = Handler(worker!!.looper)
        val appIcons = CopyDirectAppIcons()
        capsuleIcons = CopyDirectCapsuleIcons(bridge, background, main,
            { config.iconPack.takeIf { config.enabled } }, { ctx.log(Log.INFO, it) })
        var appPage: Pair<WeakReference<Any>, List<*>>? = null
        fun decorate(fragment: Any, apps: List<*>) {
            val rows = bridge.appRows(fragment, apps)
            if (rows.isEmpty()) return
            val pack = config.iconPack
            background.post {
                runCatching { appIcons.resolve(bridge.context, rows, pack) }.onSuccess { appearances ->
                    main.post {
                        if (!config.enabled || config.iconPack != pack) return@post
                        runCatching { bridge.decorateApps(fragment, appearances) }.onSuccess { count ->
                            ctx.log(Log.INFO, "复制直达原生应用行 decorated=$count themed=${appearances.count { it.themed }} pack=${pack ?: "native"}")
                        }.onFailure { ctx.log(Log.WARN, "复制直达应用行降级：${it.javaClass.simpleName} ${it.message}") }
                    }
                }.onFailure { ctx.log(Log.WARN, "复制直达图标解析降级：${it.javaClass.simpleName}") }
            }
        }
        fun refresh() {
            val prefs = HookRegistry.prefs()
            val previous = config
            config = CopyDirect.config(prefs)
            custom = PasscodeCustomRules.decode(prefs.rawString(Keys.pref(Catalog.PASSCODE_ISLAND, "custom_rules"), null))
            if (config != previous) main.post { clearCues(bridge) }
            if (config.enabled && config.iconPack != previous.iconPack) main.post {
                capsuleIcons?.refresh()
                appPage?.let { (page, apps) -> page.get()?.let { decorate(it, apps) } }
            }
        }
        var displayedBrowser = bridge.defaultBrowser()
        fun refreshBrowser() {
            if (!config.enabled) return
            val browser = bridge.defaultBrowser()
            main.post {
                val page = appPage?.first?.get() ?: return@post
                if (browser != displayedBrowser && bridge.isWebPage(page)) {
                    displayedBrowser = browser
                    runCatching { bridge.renderBrowser(page) }.onFailure {
                        ctx.log(Log.WARN, "复制直达浏览器刷新降级：${it.javaClass.simpleName}")
                    }
                    ctx.log(Log.INFO, "复制直达默认浏览器 changed=${browser ?: "unset"}")
                }
            }
        }
        val browserPageVisible = AtomicBoolean(false)
        val browserPoll = object : Runnable {
            override fun run() {
                if (!browserPageVisible.get() || !config.enabled) return
                refreshBrowser()
                background.postDelayed(this, 1000L)
            }
        }
        refresh()
        // 默认应用变更由系统通知；从设置返回时再核对一次，覆盖厂商未发广播的情况。
        browserReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refreshBrowser() }
        }
        bridge.context.registerReceiver(browserReceiver, IntentFilter("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"),
            null, background, Context.RECEIVER_EXPORTED)
        activities = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (bridge.isAppsActivity(activity)) {
                    browserPageVisible.set(true)
                    background.removeCallbacks(browserPoll)
                    background.post(browserPoll)
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) {
                if (bridge.isAppsActivity(activity)) {
                    browserPageVisible.set(false)
                    background.removeCallbacks(browserPoll)
                }
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        (bridge.context as Application).registerActivityLifecycleCallbacks(activities)
        listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> background.post { refresh() } }
        ctx.store?.registerOnSharedPreferenceChangeListener(listener)
        val original = ctx.module.getInvoker(bridge.eventMethod).setType(XposedInterface.Invoker.Type.ORIGIN)
        track(ctx.module.hook(bridge.eventMethod).intercept { chain ->
            if (!config.enabled || chain.args[0] != "1.7.3") return@intercept chain.proceed()
            val args = chain.args.toTypedArray()
            val receiver = chain.thisObject
            val items = runCatching { JSONArray(args[1] as String) }.getOrNull() ?: run {
                ctx.log(Log.WARN, "复制直达事件 arguments=array_failed")
                return@intercept chain.proceed()
            }
            fun field(name: String): String = (0 until items.length()).firstNotNullOfOrNull {
                items.optJSONObject(it)?.takeIf { obj -> obj.has(name) }?.optString(name)
            }.orEmpty()
            val text = field("text")
            val source = field("clipSource")
            val foreground = field("package")
            ctx.log(Log.INFO, "复制直达事件入口 items=${items.length()} length=${text.length} sourcePresent=${source.isNotEmpty()} foregroundPresent=${foreground.isNotEmpty()} sameSource=${source == foreground}")
            if (text.isBlank() || text.length > 2000 || (source.isNotEmpty() && foreground.isNotEmpty() && source != foreground) ||
                field("label") == "is_hce_ignore" || source == "com.milink.service") return@intercept chain.proceed()
            ctx.log(Log.INFO, "复制直达原生事件 received=1")
            val eventId = args[2] as String
            sourceSnapshots[eventId] = text
            main.postDelayed({ sourceSnapshots.remove(eventId); nativeShares.entries.removeIf { it.value.sourceText == text } }, 120_000L)
            background.post {
                val resume = {
                    runCatching { original.invoke(receiver, *args) }.onFailure {
                        ctx.log(Log.WARN, "复制直达恢复原生事件失败：${it.javaClass.simpleName}")
                    }
                    Unit
                }
                val handled = runCatching { process(bridge, text, source, args[2] as String, ctx, resume) }.getOrElse {
                    ctx.log(Log.WARN, "复制直达解析失败：${it.javaClass.simpleName}")
                    false
                }
                if (!handled) resume()
            }
            null
        })
        track(ctx.module.hook(bridge.appsMethod).intercept { chain ->
            val result = chain.proceed()
            val ability = (chain.args[1] as? String)?.removePrefix("settings.copy_text_jump_app.")
            if (!config.enabled || ability !in setOf("command", "jump_link", "jump_url")) return@intercept result
            runCatching {
                if (ability == "jump_url") {
                    val browser = bridge.defaultBrowser()
                    val apps = listOf(bridge.browserApp())
                    displayedBrowser = browser
                    ctx.log(Log.INFO, "复制直达网页许可 browser=${browser ?: "unset"} total=1")
                    return@runCatching apps
                }
                val apps = (result as? List<*>)?.filterNotNull().orEmpty().toMutableList()
                val packages = apps.map { bridge.supportedPackage(it) }.toMutableSet()
                val originalCount = apps.size
                CopyDirect.platformsForAbility(ability!!, custom).filter { it.packageName != null }.forEach {
                    val pkg = it.packageName!!
                    if (packages.add(pkg)) apps.add(bridge.supportedApp(pkg, it.name))
                }
                ctx.log(Log.INFO, "复制直达许可列表 ability=$ability original=$originalCount added=${apps.size - originalCount} total=${apps.size}")
                apps
            }.getOrElse { result }
        })
        track(ctx.module.hook(bridge.iconMethod).intercept { chain ->
            val result = chain.proceed()
            if (config.enabled) capsuleIcons?.bind(chain.thisObject!!, chain.args[0] as? String,
                chain.args[1] as? String, chain.args[2] as ImageView)
            result
        })
        track(ctx.module.hook(bridge.bubbleBindMethod).intercept { chain ->
            val result = chain.proceed()
            if (config.enabled) runCatching {
                val (id, event) = bridge.copyCueIdentity(chain.args[0]!!) ?: return@runCatching
                sourceSnapshots[event]?.let { text ->
                    if (!payloads.containsKey(id)) {
                        nativeShares[id] = Payload(shareIntent(text), text, true)
                        ctx.log(Log.INFO, "复制直达原生分享 snapshot=1")
                    }
                }
            }
            result
        })
        track(ctx.module.hook(bridge.appsRenderMethod).intercept { chain ->
            val result = chain.proceed()
            if (config.enabled) runCatching {
                val fragment = chain.args[0]!!
                val apps = chain.args[1] as List<*>
                appPage = WeakReference(fragment) to apps
                decorate(fragment, apps)
            }.onFailure { ctx.log(Log.WARN, "复制直达应用行降级：${it.javaClass.simpleName} ${it.message}") }
            result
        })
        track(ctx.module.hook(bridge.clickMethod).intercept { chain ->
            val id = chain.args.firstOrNull() as? String
            val payload = id?.let(payloads::get)
            if (!config.enabled || payload == null) return@intercept chain.proceed()
            runCatching {
                CopyDirectLauncher.launch(ctx, bridge.context, payload.intent, payload.shareOnly)
                bridge.dismiss(id)
                payloads.remove(id)
            }.onFailure { ctx.log(Log.WARN, "复制直达打开失败：${it.javaClass.simpleName}") }
            null
        })
        track(ctx.module.hook(bridge.longClickMethod).intercept { chain ->
            longPress.set(bridge.longClickId(chain.thisObject)?.let { payloads[it] ?: nativeShares[it] })
            try { chain.proceed() } finally { longPress.remove() }
        })
        track(ctx.module.hook(bridge.menuMethod).intercept { chain ->
            val result = chain.proceed()
            val payload = longPress.get()
            if (payload != null && config.enabled) runCatching {
                if (CopyDirectMenu.addShare(chain.thisObject as Dialog) { share(bridge, payload) }) {
                    ctx.log(Log.INFO, "复制直达菜单 handlers=1")
                }
            }
            result
        })
        ctx.log(Log.INFO, "复制直达原生接入 process=${ctx.processName} hooked=$installedHooks platforms=${CopyDirect.platforms(custom).size}")
        return InstallReport(installedHooks, notes = listOf("aicr=4.12.26", "共享平台=${CopyDirect.platforms(custom).size}", "rules=${io.github.kotoriinsky.tsuzuri.core.BuiltinRules.summary}"))
    }

    private fun process(bridge: CopyDirectCompat, text: String, source: String, event: String, ctx: HookContext, resume: () -> Unit): Boolean {
        val current = config
        if (!current.enabled) return false
        val prefs = HookRegistry.prefs()
        val match = PasscodeCustomRules.matchFirst(text, custom, onTimeout = { r ->
            ctx.log(Log.WARN, "自定义规则匹配超时，已跳过：id=${r.id} label=${r.label} 时限=${PasscodeCustomRules.MATCH_TIMEOUT_MS}ms")
        }) ?: PasscodeRules.match(text, PasscodeRules.Options(
            genericUrl = prefs.rawBoolean(Keys.pref(Catalog.PASSCODE_ISLAND, "generic_url"), true),
            numericTracking = prefs.rawBoolean(Keys.pref(Catalog.PASSCODE_ISLAND, "numeric_tracking"), false),
        ))
        if (!current.allows(match?.platform) || match?.ruleId in current.disabled) return true
        val native = bridge.nativeResult(text, source)
        if (native.status == 0 && !bridge.copyTag(native.tag)) return false
        if (native.status == -104 && source != match?.platform?.pkg) return true
        val nativeSameApp = native.status == -105 || (native.status == -104 && source == match?.platform?.pkg)
        if (nativeSameApp && !current.sameAppShare) return true
        if (native.status !in setOf(0, -103, -104, -105, -106)) return false
        val moduleIntent = match?.let { CopyDirectIntentResolver.resolve(bridge.context, CopyDirect.orderedTargets(it), it.platform?.pkg) }
        fun action(intent: Intent?, nativeAction: Boolean): CopyDirect.Action? = intent?.takeIf { it.resolveActivity(bridge.context.packageManager) != null }?.let {
            val data = it.data
            val strength = when {
                (data?.scheme !in setOf("http", "https") && (data?.path?.isNotEmpty() == true || data?.query?.isNotEmpty() == true)) ||
                    // 显式组件 + 参数（负一屏快递详情）同样是直达内容；否则会输给系统给的菜鸟 App 链接
                    (it.component != null && it.extras?.isEmpty == false) ||
                    (nativeAction && native.tag != 11 && (it.extras?.isEmpty == false || it.action != Intent.ACTION_VIEW)) -> CopyDirect.Strength.CONTENT
                data?.scheme in setOf("http", "https") -> CopyDirect.Strength.LINK
                else -> CopyDirect.Strength.APP
            }
            CopyDirect.Action(it.toUri(Intent.URI_INTENT_SCHEME), it.`package` ?: it.resolveActivity(bridge.context.packageManager)?.packageName, strength)
        }
        val local = action(moduleIntent, false)
        // 原生网页也先询问目标应用与域名专属处理器，最后保留默认浏览器回退。
        val nativeIntent = native.intent?.let {
            if (native.tag == 11) {
                val unpinned = Intent(it).setComponent(null).setPackage(null).addCategory(Intent.CATEGORY_BROWSABLE)
                CopyDirectIntentResolver.resolve(bridge.context, listOf(unpinned.toUri(Intent.URI_INTENT_SCHEME)), match?.platform?.pkg)
            } else it
        }
        val remote = action(nativeIntent, true)
        ctx.log(
            Log.INFO,
            "复制直达候选 local=${local != null} native=${remote != null} status=${native.status} tag=${native.tag} " +
                "nativeIntent=${nativeIntent?.toUri(Intent.URI_INTENT_SCHEME)?.take(400)}",
        )
        if (remote?.packageName in current.disabled || local?.packageName in current.disabled) return true
        // 已接管后出了问题，原生还能不能「接得住」：原生候选必须能解析出实际目标。
        // 网页（tag 11）不走原生兜底 —— 原生的网页唯一目标是小米浏览器，没装时它接下来弹的是
        // 「获取小米浏览器应用」拉新胶囊，而不是打开链接。
        val nativeCanAct = remote != null && native.tag != 11
        val selected = CopyDirect.choose(match, local, remote, current.disabled) ?: return false
        val chosen = if (selected === remote) nativeIntent!! else moduleIntent!!
        val targetPackage = selected.packageName?.takeUnless { it in setOf("android", "com.android.intentresolver") }
        val category = if (match != null) CopyDirect.abilityFor(match) else bridge.category(native.tag, targetPackage)
        val permissionPackage = if (category == "jump_url") CopyDirect.DEFAULT_BROWSER else targetPackage
        if (!bridge.allowed(permissionPackage, category)) return true
        val shareOnly = nativeSameApp || (targetPackage != null && source == targetPackage)
        if (shareOnly && !current.sameAppShare) return true
        val id = "tsuzuri-copy:" + java.util.UUID.randomUUID()
        val clickIntent = if (shareOnly) shareIntent(text) else chosen
        val seconds = (PasscodeIsland.timeoutMs(prefs.rawFloatOrNull(Keys.pref(Catalog.PASSCODE_ISLAND, "timeout_seconds"))) / 1000).toInt()
        main.post {
            if (config != current || !bridge.allowed(permissionPackage, category)) {
                if (!config.enabled) resume()
                return@post
            }
            if (current.mode == CopyDirect.Mode.NATIVE) {
                payloads[id] = Payload(clickIntent, text, shareOnly)
                val fallbackName = CopyDirect.platforms(custom).firstOrNull { it.packageName == targetPackage }?.name ?: "应用"
                val name = targetPackage?.let { bridge.applicationName(it, fallbackName) } ?: fallbackName
                runCatching { bridge.showCue(id, event, if (shareOnly) "分享${name}内容" else "在${name}中打开", source, targetPackage, seconds, category) }.onFailure {
                    payloads.remove(id)
                    ctx.log(Log.WARN, "复制直达渲染失败：${it.javaClass.simpleName} 交回原生=$nativeCanAct")
                    if (nativeCanAct) resume()
                    return@post
                }
                // 自检：700ms 后胶囊不在仓库里。⚠️ 这**不能**证明注入失败 —— 用户点空白、前台变化扫描、
                // 超时都会把胶囊正常移走。所以只在原生接得住时才交回原生；
                // 接不住就静默丢弃：宁可不弹，也不弹一个错的（「获取小米浏览器应用」）。
                main.postDelayed({
                    val accepted = runCatching { bridge.contains(id) }.getOrDefault(false)
                    ctx.log(if (accepted) Log.INFO else Log.WARN, "复制直达原生渲染 accepted=$accepted 交回原生=${!accepted && nativeCanAct}")
                    if (!accepted && config == current) {
                        payloads.remove(id)
                        runCatching { bridge.dismiss(id) }
                        if (nativeCanAct) resume()
                    }
                }, 700L)
                // 原生长按可能暂停超时；只在 cue 消失后释放文本快照。
                main.postDelayed({ prunePayloads(bridge) }, (seconds + 2) * 1000L)
            } else {
                val target = match?.target ?: chosen.dataString ?: "copydirect:$id"
                val signal = Intent(PasscodeIsland.ACTION).setPackage(PasscodeIsland.SYSTEM_UI_PACKAGE)
                    .putExtra(PasscodeIsland.EXTRA_RULE, match?.ruleId ?: "native:${native.tag}")
                    .putExtra(PasscodeIsland.EXTRA_LABEL, if (shareOnly) "分享内容" else match?.label ?: "复制直达")
                    .putExtra(PasscodeIsland.EXTRA_TARGET, target)
                    .putExtra(PasscodeIsland.EXTRA_SNIPPET, text)
                    .putExtra(PasscodeIsland.EXTRA_SOURCE_TEXT, text.take(PasscodeIsland.SOURCE_TEXT_MAX))
                    .putExtra(PasscodeIsland.EXTRA_CORE, match?.core)
                    .putExtra(PasscodeIsland.EXTRA_PLATFORM_NAME, match?.platform?.name)
                    .putExtra(PasscodeIsland.EXTRA_PLATFORM_PKG, targetPackage)
                    .putExtra(PasscodeIsland.EXTRA_ACCENT, match?.platform?.accent)
                    .putExtra(PasscodeIsland.EXTRA_CATEGORY, match?.category?.wireName)
                    .putExtra(CopyDirect.EXTRA_ENGINE_RESULT, true)
                    .putExtra(CopyDirect.EXTRA_INTENT, clickIntent.toUri(Intent.URI_INTENT_SCHEME))
                bridge.context.sendBroadcast(signal)
                ctx.log(Log.INFO, "复制直达岛分发 count=1")
            }
        }
        return true
    }

    private fun clearCues(bridge: CopyDirectCompat) {
        payloads.keys.toList().forEach { runCatching { bridge.dismiss(it) } }
        payloads.clear()
        nativeShares.clear()
        sourceSnapshots.clear()
        CopyDirectMenu.restoreAll()
    }

    private fun prunePayloads(bridge: CopyDirectCompat) {
        payloads.keys.toList().filter { !runCatching { bridge.contains(it) }.getOrDefault(false) }.forEach(payloads::remove)
    }

    private fun shareIntent(text: String): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text), "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun share(bridge: CopyDirectCompat, payload: Payload) {
        context?.log(Log.INFO, "复制直达分享 clicked=1")
        runCatching {
            bridge.context.startActivity(shareIntent(payload.sourceText))
            context?.log(Log.INFO, "复制直达分享 chooser=1")
        }.onFailure { context?.log(Log.WARN, "复制直达分享失败：${it.javaClass.simpleName}") }
    }

    override fun uninstall() {
        config = CopyDirect.Config()
        browserReceiver?.let { receiver -> compat?.context?.let { runCatching { it.unregisterReceiver(receiver) } } }
        browserReceiver = null
        activities?.let { (compat?.context as? Application)?.unregisterActivityLifecycleCallbacks(it) }
        activities = null
        listener?.let { context?.store?.unregisterOnSharedPreferenceChangeListener(it) }
        listener = null
        val previousIcons = capsuleIcons
        val previousBridge = compat
        capsuleIcons = null
        compat = null
        main.post { previousIcons?.refresh(); previousBridge?.let(::clearCues) }
        worker?.quitSafely()
        worker = null
        super.uninstall()
    }
}
