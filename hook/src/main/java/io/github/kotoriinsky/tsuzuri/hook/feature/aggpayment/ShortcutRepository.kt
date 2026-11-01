package io.github.kotoriinsky.tsuzuri.hook.feature.aggpayment

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.util.Log

/**
 * 一条可用于支付的快捷方式（shortcut 体系里的东西，不是我们自己造的）。
 *
 * [kind] 只用于排序与图标兜底，不代表我们对它有别的假设 —— 面板里显示的
 * 名字、图标全部来自 shortcut 自己发布的元数据。
 */
internal data class PaymentShortcut(
    val packageName: String,
    val id: String,
    /** shortcut 自己发布的短标签；取不到时退化成 id。 */
    val label: String,
    /** `getShortcutIconDrawable` 的结果。拿不到就是 null，面板另有兜底画法。 */
    val icon: Drawable?,
    val kind: Kind,
) {
    /** 付款码 / 扫一扫 / 其它。 */
    enum class Kind { CODE, SCAN, OTHER }

    /** 诊断与 `identity` 用的稳定键。 */
    val key: String get() = "$packageName/$id"
}

/**
 * 用标准 Shortcut 体系查「可用于支付」的快捷方式。
 *
 * ## 为什么非得跑在 SystemUI 里
 *
 * `LauncherApps` 把**调用方包名**一并传给 system_server 的 ShortcutService，服务端会拿它和
 * 调用 uid 的实际包名对账。设备实测（uid 10226 = 本机 SystemUI 的 appId）：
 *
 * ```
 * 用系统上下文（包名 "android"）查微信：
 *   java.lang.SecurityException: Calling package name mismatch
 * 用 com.android.systemui 的包上下文查微信：
 *   -> 3 个（launch_type_my_qrcode / launch_type_offline_wallet / launch_type_scan_qrcode）
 * ```
 *
 * 也就是说：**必须用 SystemUI 自己的 Context**（[io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv.context]，
 * 它拿的是 `ActivityThread.currentApplication()`）。换个上下文就整个查不到，
 * 而且失败形式是一条 SecurityException —— 所以 [query] 把它变成可诊断的返回值，
 * 绝不往外抛（P0：hook 进程里抛异常 = 宿主崩溃）。
 *
 * ## 调用方的权限（设备实测 `dumpsys package com.android.systemui`）
 *
 * `android.permission.ACCESS_SHORTCUTS: granted=true` —— 这正是跨包查询的闸门。
 *
 * ## `getIntents()` 是空的（实测）
 *
 * 通过 LauncherApps 拿回来的 `ShortcutInfo.getIntents()` 返回 **null**（shell 的
 * `cmd shortcut get-shortcuts` 却能看到 intent）。所以**不能**靠它做「直连兜底」，
 * 兜底只能是本文件里那张白名单表（微信 / 支付宝各自的私有入口）。
 * 启动主路径仍然是 `startShortcut(...)`：服务端自己拿 shortcut 里的 intent 去拉。
 */
internal class ShortcutRepository(private val context: Context) {

    private val apps: LauncherApps? = try {
        context.getSystemService(LauncherApps::class.java)
    } catch (t: Throwable) {
        null
    }

    /** 查询结果：要么有数据，要么带一个可写进日志/诊断的原因。 */
    private class Query(val items: List<ShortcutInfo>, val failure: String?)

    // ── 对外 ──────────────────────────────────────────────────────────

    /** 一次装载的结果。[failure] 非空 = 降级了，面板会显示空态而不是「假装有数据」。 */
    class Load(val items: List<PaymentShortcut>, val failure: String?) {
        val ok: Boolean get() = failure == null
    }

    /**
     * 装默认那组（微信/支付宝各两项，设备实证这四条的 id 在本 ROM 上确实存在）。
     *
     * @param codeFirst true = 付款码排在扫一扫前面（对应 `code_first` 参数）
     */
    fun load(codeFirst: Boolean): Load {
        if (apps == null) return Load(emptyList(), "no LauncherApps")

        // 按包查（一个包一次 binder 调用），再按 id 挑 —— 比「四个 id 各查一次」少 3/4 的 IPC
        val byPkg = LinkedHashMap<String, Map<String, ShortcutInfo>>()
        for (pkg in DEFAULT_TARGETS.map { it.packageName }.distinct()) {
            val q = query(pkg, null)
            if (q.failure != null) return Load(emptyList(), "${pkg}: ${q.failure}")
            byPkg[pkg] = q.items.associateBy { it.id }
        }

        val out = ArrayList<PaymentShortcut>(DEFAULT_TARGETS.size)
        for (t in DEFAULT_TARGETS) {
            val si = byPkg[t.packageName]?.get(t.id) ?: continue
            out.add(toShortcut(si, t.kind))
        }
        if (out.isEmpty()) {
            return Load(emptyList(), "目标 shortcut 一个都没查到（微信/支付宝可能未安装或未登录）")
        }

        // 排序：只在「付款码 / 扫一扫」两类之间换顺序，类内保持 DEFAULT_TARGETS 的顺序
        // （也就是微信在支付宝前 —— 与 Flyme 候选列表的次序一致）。
        // 用显式 rank 而不是 ordinal 算术：万一以后加了第三种 kind，算术式会悄悄把它排到中间。
        val ordered = out.sortedBy { kindRank(it.kind, codeFirst) }
        return Load(ordered, null)
    }

    /**
     * 列出**所有应用**发布的、看起来是「支付」的 shortcut，作为候选。
     *
     * 用途有两个：
     *  1. 默认那组一条都查不到时（例如用户只装了云闪付），退化用它，免得面板空着；
     *  2. install 时打一条诊断日志 —— 「这台机器上到底有多少个支付类快捷方式」，
     *     这是将来做「要包含哪些支付快捷方式」多选配置（需要新的 PrefSpec 类型）时的数据来源。
     *
     * 一次 binder 调用（`setPackage(null)`），设备实测 206 条、耗时 3~4ms。
     */
    fun discover(): Load {
        val q = query(null, null)
        if (q.failure != null) return Load(emptyList(), q.failure)
        val items = q.items
            .filter { isPaymentLike(it) }
            .map { toShortcut(it, kindOf(it)) }
        // 按包名去重（同一个应用只留第一条），再按标签排一下，日志读起来不刺眼
        val dedup = LinkedHashMap<String, PaymentShortcut>()
        for (i in items) dedup.putIfAbsent(i.packageName, i)
        return Load(dedup.values.toList(), null)
    }

    /**
     * 启动一条支付快捷方式。
     *
     * 顺序（这是一个**判断**，不是实测结论，理由写在下面）：
     *  1. 已知包（微信 / 支付宝）→ **直连 intent 优先**。MIUI 自己在 FOD 快捷项里用的就是
     *     这种「显式/隐式 intent 直达」的路子（`WechatPayItem.getIntent()` 直接给
     *     `WalletOfflineCoinPurseUI`），而 shortcut 那条路要过 ShortcutService 的节流与
     *     `startShortcut` 的各种校验。设备实测：从 SystemUI 的 uid 调 `startShortcut`
     *     **不抛异常**、日志里也能看到它把 intent 解成了 `com.tencent.mm/.ui.ShortCutDispatchActivity`
     *     并 START，但**探针进程里没看到界面起来** —— 探针的 uid/pid 语义和真实 SystemUI
     *     不完全一样，所以这条只能算「没验干净」，不能算「不可用」。
     *  2. 其余包（以及第 1 步失败时）→ 标准 `startShortcut`。
     *
     * @return true = 至少有一条路径没抛异常地发出去了。**不代表界面真的起来了** ——
     *         应用自己拒绝、被 keyguard 挡住、被安全中心拦下都在这个语义之外。
     */
    fun start(item: PaymentShortcut): Boolean {
        if (directIntent(item)?.let { fire(it) } == true) return true
        val la = apps ?: return false
        return try {
            // 注意：本 SDK 里 `startShortcut` 是 **void**（历史上返回过 boolean），
            // 所以只能拿「没抛异常」当成功 —— 服务端有没有真的把 activity 起来，
            // 这个 API 不告诉我们（实测日志里能看到它 START 了 ShortCutDispatchActivity）。
            la.startShortcut(item.packageName, item.id, null, null, Process.myUserHandle())
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startShortcut 失败：${item.key}", t)
            false
        }
    }

    // ── 查询 ──────────────────────────────────────────────────────────

    /**
     * 三种匹配标志一起给：动态（微信/支付宝发布的）、清单里声明的、用户固定的。
     * 少给一个就会漏掉一整类 —— 这四项目标全是 dynamic，只给 MANIFEST 会一条都查不到。
     */
    private fun query(packageName: String?, ids: List<String>?): Query {
        val la = apps ?: return Query(emptyList(), "no LauncherApps")
        return try {
            val q = LauncherApps.ShortcutQuery()
            q.setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
            if (packageName != null) q.setPackage(packageName)
            if (!ids.isNullOrEmpty()) q.setShortcutIds(ids)
            val list = la.getShortcuts(q, Process.myUserHandle())
            Query(list ?: emptyList(), null)
        } catch (t: Throwable) {
            // 绝不外抛：这里失败的表现形式是 SecurityException（包名不匹配）或
            // 服务端异常，两者都不该让 SystemUI 崩
            Query(emptyList(), t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun toShortcut(si: ShortcutInfo, kind: PaymentShortcut.Kind): PaymentShortcut {
        val label = si.shortLabel?.toString()?.takeIf { it.isNotBlank() } ?: si.id
        return PaymentShortcut(
            // getPackage() 是 non-null 的（返回的是 shortcut 自己归属的包名），
            // 但这里仍然留一道空串兜底：`discover()` 会拿它当 key，null 会在下游炸
            packageName = si.`package`.orEmpty(),
            id = si.id,
            label = label,
            icon = loadIcon(si),
            kind = kind,
        )
    }

    /**
     * 拿 shortcut 自己发布的图标。设备实测能拿到真图标（微信 156×156 BitmapDrawable，
     * 支付宝 144×144），所以面板不需要内置任何 PNG。
     *
     * 第二个参数是密度：给 0 让 framework 用「当前显示密度」解，省得自己算。
     */
    private fun loadIcon(si: ShortcutInfo): Drawable? = try {
        apps?.getShortcutIconDrawable(si, 0)
    } catch (t: Throwable) {
        null
    }

    // ── 兜底直连 ──────────────────────────────────────────────────────

    /**
     * 不走 shortcut 服务的直连 intent。
     *
     * 两条都来自设备实证的字符串（本项目调研 + 本次 `cmd shortcut get-shortcuts` 的输出）：
     *  - 微信：`act=com.tencent.mm.ui.ShortCutDispatchAction`，extra 里就是 shortcut id 本身。
     *    设备实测该 action 由 `com.tencent.mm/.ui.ShortCutDispatchActivity` 声明。
     *  - 支付宝：`alipays://platformapi/startapp?appId=<id 对应的 appId>`，
     *    appId 映射直接取自支付宝自己发布的 shortcut 的 KEY_SCHEME。
     */
    private fun directIntent(item: PaymentShortcut): Intent? = when (item.packageName) {
        PKG_WEIXIN -> Intent(WECHAT_DISPATCH_ACTION).apply {
            setPackage(PKG_WEIXIN)
            putExtra(WECHAT_LAUNCH_TYPE_EXTRA, item.id)
        }

        PKG_ALIPAY -> ALIPAY_APP_ID[item.id]?.let { appId ->
            Intent(Intent.ACTION_VIEW, Uri.parse("alipays://platformapi/startapp?appId=$appId"))
        }

        else -> null
    }

    private fun fire(intent: Intent): Boolean = try {
        intent.addFlags(
            // NEW_TASK：调用方不是 Activity
            // CLEAR_TOP：连点两次不要把跳板 activity 叠起来（MIUI 自己的 FOD 项也是这么给的）
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "直连 intent 失败：$intent", t)
        false
    }

    private companion object {
        const val TAG = "FP-AggPay"

        const val PKG_WEIXIN = "com.tencent.mm"
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

        const val WECHAT_DISPATCH_ACTION = "com.tencent.mm.ui.ShortCutDispatchAction"
        const val WECHAT_LAUNCH_TYPE_EXTRA = "LauncherUI.Shortcut.LaunchType"

        /** 支付宝 shortcut id → 它自己发布的 `KEY_APP_ID`。 */
        val ALIPAY_APP_ID = mapOf(
            "1002" to "20000056", // 收付款
            "1003" to "20000123", // 收钱
            "1001" to "10000007", // 扫一扫
        )

        /**
         * 默认呈现的四项。id 全部来自**本机**实证：
         * `adb shell cmd shortcut get-shortcuts --flags 11 <pkg>` 的输出。
         */
        val DEFAULT_TARGETS = listOf(
            Target(PKG_WEIXIN, "launch_type_offline_wallet", PaymentShortcut.Kind.CODE), // 收付款
            Target(PKG_WEIXIN, "launch_type_scan_qrcode", PaymentShortcut.Kind.SCAN),     // 扫一扫
            Target(PKG_ALIPAY, "1002", PaymentShortcut.Kind.CODE),                        // 收付款
            Target(PKG_ALIPAY, "1001", PaymentShortcut.Kind.SCAN),                        // 扫一扫
        )

        /** 「看起来是支付」的判据。只用于候选发现，不影响默认那四条。 */
        val PAY_KEYWORDS = listOf(
            "收付款", "收款", "付款", "收钱", "扫一扫", "扫码", "二维码", "支付", "钱包",
            "wallet", "pay", "qrcode", "scan",
        )

        /** 只影响两类之间的先后；其它类一律排在最后，且用稳定排序保住原顺序。 */
        fun kindRank(kind: PaymentShortcut.Kind, codeFirst: Boolean): Int = when (kind) {
            PaymentShortcut.Kind.CODE -> if (codeFirst) 0 else 1
            PaymentShortcut.Kind.SCAN -> if (codeFirst) 1 else 0
            PaymentShortcut.Kind.OTHER -> 2
        }

        fun isPaymentLike(si: ShortcutInfo): Boolean {
            if (si.`package`.isNullOrEmpty()) return false
            val text = (si.shortLabel?.toString() ?: "") + " " + si.id
            val lower = text.lowercase()
            return PAY_KEYWORDS.any { lower.contains(it) }
        }

        fun kindOf(si: ShortcutInfo): PaymentShortcut.Kind {
            val text = (si.shortLabel?.toString() ?: "") + " " + si.id
            return when {
                text.contains("扫") || text.contains("scan", true) -> PaymentShortcut.Kind.SCAN
                text.contains("付") || text.contains("收") || text.contains("pay", true) ->
                    PaymentShortcut.Kind.CODE

                else -> PaymentShortcut.Kind.OTHER
            }
        }
    }

    /** 默认目标：包名 + shortcut id + 类别。 */
    internal data class Target(
        val packageName: String,
        val id: String,
        val kind: PaymentShortcut.Kind,
    )
}
