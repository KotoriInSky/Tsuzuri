package io.github.kotoriinsky.tsuzuri.hook.feature.passcodeisland

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PasscodeCustomRules
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.core.PasscodeRules
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在 **system_server** 里观察剪贴板变更，命中口令就广播给 SystemUI。
 *
 * ## 为什么落在 system_server
 *
 * Android 10 起**后台进程读剪贴板被 AppOp 拦死**，而系统里唯一能看到每一次
 * 剪贴板写入的地方就是 `ClipboardService` 本身 —— 它跑在 system_server 里，
 * 我们站在服务内部，`setPrimaryClip` 的**第一个参数就是要匹配的文本**，
 * 不需要任何权限调用、不产生任何额外的 IPC。模块本来就注入 system_server
 * （`scope.list` 的 `system`，实测先例：`LauncherDrawerObserver`）。
 *
 * ## hook 点：`ClipboardService.ClipboardImpl#setPrimaryClip`
 *
 * A17 起 binder 实现在内部类 `ClipboardImpl` 上（签名 5 参：
 * `clip, callingPackage, attributionTag, userId, deviceId`），老 AOSP 的
 * 「外层类直接方法」已不存在。解析规则见 [findHookMethods]：内部类优先、
 * 外层兜底、`setPrimaryClipAsPackage` 变体一并挂，全部不匹配才降级
 * （`hooked=0`，SystemUI 侧收不到广播，行为与未安装模块完全一致）。
 *
 * ## 频率与纪律
 *
 * `setPrimaryClip` 只在用户真正复制/剪切时触发（不是 `relayoutWindow` 那种
 * 每秒百次的热路径），但仍守同一条红线：hook 体里只做「取文本 + 判长短 +
 * 转投后台线程」，**匹配引擎、广播、一切分配都离开 hook 体**。
 * 异常连 `Throwable` 一起兜住 —— 这里抛出去的最坏后果是整个系统重启。
 */
internal object PasscodeIslandWatcher {

    private const val TAG = "FP-Passcode"

    private const val CLIPBOARD_CLASS = "com.android.server.clipboard.ClipboardService"
    private const val SET_METHOD = "setPrimaryClip"

    /** 广播连续失败的退避：1s/2s/4s/8s 封顶（开机早期 AMS 未就绪是已知现象，见观察器先例）。 */
    private fun retryDelayMs(failures: Int): Long =
        (1000L shl (failures - 1).coerceIn(0, 3)).coerceAtMost(8000L)

    @Volatile
    private var hookCtx: HookContext? = null

    @Volatile
    private var appContext: Context? = null

    private val handles = ArrayList<XposedInterface.HookHandle>(4)

    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null

    // ── 防抖状态（同文 60s 内不再弹）──────────────────────

    @Volatile
    private var lastText: String? = null

    @Volatile
    private var lastIdentity: String? = null

    @Volatile
    private var lastAtMs = 0L

    // ── 「同一次复制被写了两次」的合并状态（只在 handler 线程读写）────────
    //
    // 实测（2026-10-06 用户报「知乎跳两个岛」）：知乎一次「复制」在 181ms 内先写
    // `www.zhihu.com/question/…/answer/…`、再写 `oia.zhihu.com/km_paid_content/share?…`。
    // 两条 URL 不同 → 身份不同 → 防抖拦不住 → 两个岛。所以这里**先压一个极短窗口**，
    // 同平台的后一条把前一条顶掉，只发剪贴板的**最终**内容。

    /** 还在合并窗口里、尚未发出的那条信号。 */
    private var pendingSignal: PasscodeRules.Match? = null

    private var pendingSource: String? = null
    private var pendingPlatformPkg: String? = null
    private var pendingAtMs = 0L
    private var pendingSend: Runnable? = null

    // ── 引擎开关（后台匹配前刷新，保存后下一次复制立即生效）────────────

    @Volatile
    private var genericUrl = true

    @Volatile
    private var numericTracking = false

    /**
     * 自定义规则（用户导入）。**先于内置规则**：用户显式写下的规则就是要它优先生效。
     * 每次复制在后台线程刷新一次，避免把用户的规则修改绑在 system_server 重启上。
     */
    @Volatile
    private var customRules: List<PasscodeCustomRules.Rule> = emptyList()

    private val signalSeq = AtomicInteger()

    private val broadcastFailures = AtomicInteger()

    fun install(ctx: HookContext): InstallReport {
        if (handles.isNotEmpty()) return InstallReport(handles.size, notes = listOf("already installed"))

        hookCtx = ctx
        appContext = resolveSystemContext(ctx)

        reloadSettings(ctx, logChanges = false)

        val cls = loadClipboardClass(ctx)
            ?: return InstallReport(0, missing = listOf(CLIPBOARD_CLASS), notes = listOf("class_not_found"))

        val methods = findHookMethods(ctx)
        if (methods.isEmpty()) {
            return InstallReport(
                0,
                missing = listOf("$CLIPBOARD_CLASS#setPrimaryClip(<ClipData>...)"),
                notes = listOf("method_not_found"),
            )
        }

        var hooked = 0
        for (m in methods) {
            try {
                ctx.module.hook(m).intercept { chain ->
                    // hook 体：只取文本 + 转投后台。所有重活（匹配/广播）都不在这里。
                    try {
                        onClipSet(chain)
                    } catch (_: Throwable) {
                        // 剪贴板路径上不允许任何异常逃逸（规范 §7.1）
                    }
                    chain.proceed()
                }.also { handles.add(it) }
                hooked++
            } catch (t: Throwable) {
                ctx.log(Log.ERROR, "口令观察器：hook #$SET_METHOD 某个重载失败", t)
            }
        }
        if (hooked == 0) {
            return InstallReport(0, missing = listOf("$CLIPBOARD_CLASS#$SET_METHOD"), notes = listOf("hook_failed"))
        }

        val ht = HandlerThread("fp-passcode").apply {
            isDaemon = true
            start()
        }
        thread = ht
        handler = Handler(ht.looper)

        val sig = describe(methods)
        ctx.log(
            Log.INFO,
            "口令观察器已装上：$sig genericUrl=$genericUrl numericTracking=$numericTracking customRules=${customRules.size}",
        )
        return InstallReport(hooked, notes = listOf("watcher=$sig", "rules=${io.github.kotoriinsky.tsuzuri.core.BuiltinRules.summary}"))
    }

    fun uninstall() {
        for (h in handles) {
            try {
                h.unhook()
            } catch (_: Throwable) {
            }
        }
        handles.clear()
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        hookCtx = null
        appContext = null
        lastText = null
        lastIdentity = null
        lastAtMs = 0L
        customRules = emptyList()
        signalSeq.set(0)
        broadcastFailures.set(0)
    }

    // ── hook 体（轻）──────────────────────────────────────────────────

    private fun onClipSet(chain: XposedInterface.Chain) {
        val clip = chain.getArg(0) as? ClipData ?: return
        if (clip.itemCount <= 0) return
        // 文本项可能为 null（纯图片/URI 剪贴板）；styled text 场景 getText() 也会给 null ——
        // 都不是口令，直接放行
        val text = clip.getItemAt(0)?.text as? CharSequence ?: return
        val s = text.toString()
        if (s.length < 2 || s.length > 2000) return

        // 便宜的去重：先比防抖再排队，绝大多数重复复制在这一行就结束
        val now = SystemClock.uptimeMillis()
        if (PasscodeIsland.isDuplicate(lastText, lastAtMs, s, now)) return
        val currentCtx = hookCtx ?: return
        val h = handler ?: return
        h.post {
            try {
                // 配置通过 RemotePreferences 到达 system_server；在后台线程读取，
                // 因此保存后下一次复制立即使用新值，不阻塞 ClipboardService hook。
                reloadSettings(currentCtx, logChanges = true)
                // 原生增强负责同一事件的识别和单选分发，旧观察器保留安装但不重复发送。
                if (io.github.kotoriinsky.tsuzuri.core.CopyDirect.config(
                        io.github.kotoriinsky.tsuzuri.hook.HookRegistry.prefs(),
                    ).enabled) return@post
                val checkAt = SystemClock.uptimeMillis()
                if (PasscodeIsland.isDuplicate(lastText, lastAtMs, s, checkAt)) return@post
                val m = PasscodeCustomRules.matchFirst(s, customRules, onTimeout = { r ->
                    Log.w(TAG, "自定义规则匹配超时，已跳过：id=${r.id} label=${r.label} 时限=${PasscodeCustomRules.MATCH_TIMEOUT_MS}ms")
                })
                    ?: PasscodeRules.match(
                        s,
                        PasscodeRules.Options(genericUrl = genericUrl, numericTracking = numericTracking),
                    ) ?: return@post
                val identity = PasscodeRules.duplicateIdentity(m)
                if (!io.github.kotoriinsky.tsuzuri.core.CopyDirect.config(
                        io.github.kotoriinsky.tsuzuri.hook.HookRegistry.prefs(),
                    ).allows(m.platform)) return@post
                if (identity != null &&
                    PasscodeIsland.isDuplicate(lastIdentity, lastAtMs, identity, checkAt)
                ) return@post
                lastText = s
                lastIdentity = identity
                lastAtMs = checkAt
                postSignalCoalesced(m, s, checkAt, currentCtx)
            } catch (t: Throwable) {
                currentCtx.log(Log.WARN, "口令观察器：后台匹配失败", t)
            }
        }
    }

    /** 从远程配置刷新口令引擎；只在后台线程调用。 */
    private fun reloadSettings(ctx: HookContext, logChanges: Boolean) {
        val nextGenericUrl = ctx.prefs.rawBoolean(Keys.pref(Catalog.PASSCODE_ISLAND, "generic_url"), true)
        val nextNumericTracking = ctx.prefs.rawBoolean(Keys.pref(Catalog.PASSCODE_ISLAND, "numeric_tracking"), false)
        val nextCustomRules = PasscodeCustomRules.decode(
            runCatching {
                ctx.prefs.rawString(Keys.pref(Catalog.PASSCODE_ISLAND, "custom_rules"), null)
            }.getOrNull(),
        )
        val changed = genericUrl != nextGenericUrl ||
            numericTracking != nextNumericTracking || customRules != nextCustomRules
        genericUrl = nextGenericUrl
        numericTracking = nextNumericTracking
        customRules = nextCustomRules
        if (logChanges && changed) {
            ctx.log(
                Log.INFO,
                "口令观察器配置已热更新：genericUrl=$genericUrl " +
                    "numericTracking=$numericTracking customRules=${customRules.size}",
            )
        }
    }

    // ── 后台线程（匹配完成后的广播）────────────────────────────────────

    private fun postSignal(m: PasscodeRules.Match, source: String) {
        val h = handler ?: return
        h.post { sendBroadcast(m, source) }
    }

    /**
     * 发信号前先压一个极短窗口（[PasscodeIsland.COPY_BURST_MS]），用于合并「同一次复制的两次写入」。
     *
     * - **同一平台**在窗口内又来一条 → 丢掉前一条，只发这一条（剪贴板最终内容）；
     * - **不同平台**或超出窗口 → 立刻把前一条发出去（不能吞掉用户另一次复制）；
     * - **非 http 目标**（淘口令 / 快递号 / 裸号）**不压窗口**，直接发 —— 那条路追求即时，
     *   而且这类信号的身份本来就一样，交给展示侧 60s 去重即可。
     *
     * 只在本文件的 handler 线程上调用（[onClipSet] 的 `h.post {}` 里），所以这些字段不需要锁。
     */
    private fun postSignalCoalesced(
        m: PasscodeRules.Match,
        source: String,
        nowMs: Long,
        ctx: HookContext,
    ) {
        val h = handler ?: return
        if (!m.target.startsWith("http", ignoreCase = true)) {
            postSignal(m, source)
            return
        }
        val pkg = m.platform?.pkg
        val prev = pendingSignal
        val prevPkg = pendingPlatformPkg
        val prevAt = pendingAtMs
        if (prev != null) {
            pendingSend?.let { h.removeCallbacks(it) }
            pendingSend = null
            if (PasscodeIsland.isSameCopyBurst(prevPkg, prevAt, pkg, nowMs)) {
                ctx.log(
                    Log.INFO,
                    "口令观察器：判定为同一次复制的第二次写入，只发后一条 " +
                        "（丢了 ${prev.target}，发 ${m.target}）",
                )
            } else {
                // 不同平台/超窗口：前一条立刻照发，不能因为要等窗口就把它吞掉
                postSignal(prev, pendingSource.orEmpty())
            }
        }
        pendingSignal = m
        pendingSource = source
        pendingPlatformPkg = pkg
        pendingAtMs = nowMs
        val r = Runnable {
            pendingSignal = null
            pendingSource = null
            pendingPlatformPkg = null
            pendingAtMs = 0L
            pendingSend = null
            postSignal(m, source)
        }
        pendingSend = r
        h.postDelayed(r, PasscodeIsland.COPY_BURST_MS)
    }

    private fun sendBroadcast(m: PasscodeRules.Match, source: String) {
        val ctx = hookCtx ?: return
        val context = appContext ?: run {
            ctx.log(Log.WARN, "口令观察器：system_server 里拿不到 Context，口令信号发不出去")
            return
        }
        val seq = signalSeq.incrementAndGet()
        val snippet = source.take(60)
        val sent = try {
            val intent = Intent(PasscodeIsland.ACTION)
                .setPackage(PasscodeIsland.SYSTEM_UI_PACKAGE)
                .putExtra(PasscodeIsland.EXTRA_RULE, m.ruleId)
                .putExtra(PasscodeIsland.EXTRA_LABEL, m.label)
                .putExtra(PasscodeIsland.EXTRA_TARGET, m.target)
                // 跳转目标的备选（顺序即优先级）。只有展示侧有 PackageManager，
                // 「依次试哪个」由它判 —— 这里是唯一的传递点，漏了就整条链失效。
                .putStringArrayListExtra(
                    PasscodeIsland.EXTRA_TARGET_ALTS,
                    ArrayList(m.alts),
                )
                .putExtra(PasscodeIsland.EXTRA_SNIPPET, snippet)
                .putExtra(PasscodeIsland.EXTRA_SOURCE_TEXT, source.take(PasscodeIsland.SOURCE_TEXT_MAX))
                // 视觉定稿 2026-09-25：平台元数据（名称/包名/强调色）+ 核心内容
                .putExtra(PasscodeIsland.EXTRA_PLATFORM_NAME, m.platform?.name)
                .putExtra(PasscodeIsland.EXTRA_PLATFORM_PKG, m.platform?.pkg)
                .putExtra(PasscodeIsland.EXTRA_ACCENT, m.platform?.accent)
                .putExtra(PasscodeIsland.EXTRA_CORE, m.core)
                .putExtra(PasscodeIsland.EXTRA_CATEGORY, m.category.wireName)
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
            true
        } catch (t: Throwable) {
            // 已知现象：onSystemServerStarting 装上的 hook 早于 AMS 构造，
            // 早期 sendBroadcast 会 NPE —— 用户复制口令一定发生在开机完成后，
            // 短退避重试即可（同 LauncherDrawerObserver 的处理）
            val n = broadcastFailures.incrementAndGet()
            if (n == 1) {
                ctx.log(Log.WARN, "口令观察器：广播暂时发不出去（启动早期 AMS 未就绪属预期），将自动重试：$t")
            }
            val h = handler
            if (h != null && !h.hasCallbacks(resend)) {
                resendTarget = m to source
                h.postDelayed(resend, retryDelayMs(n))
            }
            false
        }
        if (sent && broadcastFailures.getAndSet(0) > 0) {
            ctx.log(Log.INFO, "口令观察器：广播已恢复")
        }
        if (sent) {
            ctx.log(Log.INFO, "口令信号：rule=${m.ruleId} label=${m.label} target=${m.target} seq=$seq")
        }
    }

    private var resendTarget: Pair<PasscodeRules.Match, String>? = null

    private val resend = Runnable {
        val t = resendTarget ?: return@Runnable
        resendTarget = null
        sendBroadcast(t.first, t.second)
    }

    // ── 安装期解析（冷路径）────────────────────────────────────────────

    /**
     * 解析剪贴板写入入口。
     *
     * ⚠️ HyperOS 4（A17，2026-09-24 services.jar 反编译实证）：binder 方法不再
     * 直接挂在 `ClipboardService` 上，挪进了内部类 **`ClipboardImpl`**
     * （`private class ClipboardImpl extends IClipboard.Stub`），外层类上一个
     * `setPrimaryClip` 都没有 —— 只按老 AOSP 结构在外层找必然 `method_not_found`。
     * 所以两处都试：内部类优先（新布局），外层兜底（老布局）；
     * `setPrimaryClipAsPackage` 是部分 App 走的带 sourcePackage 变体，一并挂。
     */
    private fun findHookMethods(ctx: HookContext): List<Method> {
        val outer = loadClipboardClass(ctx) ?: return emptyList()
        val impl = runCatching {
            Class.forName("$CLIPBOARD_CLASS\$ClipboardImpl", false, ctx.classLoader)
        }.getOrNull() ?: outer.declaredClasses.firstOrNull {
            runCatching { it.superclass?.name == "android.content.IClipboard\$Stub" }.getOrDefault(false)
        }
        val names = setOf(SET_METHOD, "setPrimaryClipAsPackage")
        val found = LinkedHashMap<String, Method>()
        for (c in listOfNotNull(impl, outer)) {
            for (m in c.declaredMethods) {
                if (m.name in names && m.parameterTypes.firstOrNull() == ClipData::class.java) {
                    found.putIfAbsent("${m.declaringClass.simpleName}.${m.name}(${m.parameterTypes.size})", m)
                }
            }
        }
        return found.values.toList()
    }

    private fun describe(methods: List<Method>): String =
        methods.joinToString("|") { m ->
            "${m.declaringClass.simpleName}#${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }

    private fun loadClipboardClass(ctx: HookContext): Class<*>? {
        val loaders = listOfNotNull(
            ctx.classLoader,
            PasscodeIslandWatcher::class.java.classLoader,
            ClassLoader.getSystemClassLoader(),
        ).distinct()
        for (l in loaders) {
            runCatching { Class.forName(CLIPBOARD_CLASS, false, l) }.getOrNull()?.let { return it }
        }
        return runCatching { Class.forName(CLIPBOARD_CLASS) }.getOrNull()
    }

    private fun resolveSystemContext(ctx: HookContext): Context? {
        val at = try {
            Class.forName("android.app.ActivityThread")
        } catch (_: Throwable) {
            ctx.log(Log.WARN, "口令观察器：找不到 ActivityThread，口令信号通道会不可用")
            return null
        }
        runCatching { at.getMethod("currentApplication").invoke(null) as? Context }
            .getOrNull()?.let { return it }
        return runCatching { at.getMethod("getSystemContext").invoke(null) as? Context }
            .onFailure { ctx.log(Log.INFO, "口令观察器：getSystemContext 也不可用（${it.javaClass.simpleName}）") }
            .getOrNull()
    }
}
