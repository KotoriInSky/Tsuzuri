package io.github.kotoriinsky.tsuzuri.ui.screen

import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.ui.state.ModuleScopes
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.settingsFeatures
import io.github.kotoriinsky.tsuzuri.ui.BuildConfig
import io.github.kotoriinsky.tsuzuri.ui.fpResources
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpMessageHost
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.DeviceInfo
import io.github.kotoriinsky.tsuzuri.ui.state.FrameworkBridge
import io.github.kotoriinsky.tsuzuri.ui.state.FrameworkPhase
import io.github.kotoriinsky.tsuzuri.ui.state.ReloadController
import io.github.kotoriinsky.tsuzuri.ui.state.Su
import kotlinx.coroutines.launch

/**
 * 模块状态页：所有「让模块真正跑起来」的检查集中在这一页，首页状态卡是唯一入口。
 *
 * 四步按依赖排：LSPosed 激活 → 作用域 → Root（可选）→ 让改动生效。规则：
 *  - 每一步只有**一个**主按钮，按钮**跳到能处理的地方**（系统确认框 / LSPosed / Root 管理器）；
 *    已经满足的步骤不给按钮；
 *  - 从别的应用回到这一页时**自动重新检测**（框架状态 + Root）—— 修「授权完回来状态不刷新」；
 *  - 不再被外壳自动推出来：提醒靠首页状态卡变色，入口一直在。
 *
 * 页面底部是原来首页上的只读信息（版本、设备…）。
 */
@Composable
fun SetupScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val status by FrameworkBridge.status
    val context = LocalContext.current
    val res = fpResources()
    val messages = LocalFpMessageHost.current
    val scope = rememberCoroutineScope()

    var root by remember { mutableStateOf(Su.lastKnown) }
    var busy by remember { mutableStateOf(false) }
    // 热重载没有全部成功，且 Root 可用时，才露出「用 Root 重启」这个兜底（它会黑屏）
    var reloadFailed by remember { mutableStateOf(false) }
    var confirmRootRestart by remember { mutableStateOf(false) }
    val rootManager = remember { detectRootManager(context) }

    LaunchedEffect(Unit) {
        if (root == null) root = Su.probe()
    }
    // 去 LSPosed / Root 管理器处理完回来，立刻重新检测，不用等 3 秒一次的轮询
    LifecycleResumeEffect(Unit) {
        FrameworkBridge.refresh()
        scope.launch { root = Su.probe(force = true) }
        onPauseOrDispose { }
    }

    /** 先试框架热重载；结果如实回报（成功 / 部分 / 没有目标）。 */
    fun reload() {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = ReloadController.reloadAll()
            busy = false
            reloadFailed = outcome.total > 0 && !outcome.fullyReloaded
            messages.showSnackbar(
                when {
                    outcome.total == 0 -> res.getString(fpStringRes(context, "setup.reload.none"))
                    outcome.fullyReloaded ->
                        res.getString(fpStringRes(context, "setup.reload.done"), outcome.hotOk)
                    else -> res.getString(
                        fpStringRes(context, "setup.reload.partial"),
                        outcome.hotOk,
                        outcome.hotFailed,
                    )
                },
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        // 2026-10-25 重排（用户：太分散、文案不一致；框架 / 作用域 / Root / 进程分节都不需要）：
        // 状态卡（与首页同一份结论）→ 每个作用域一行、一个状态词 → 操作 → 版本与设备。
        kit.VSpace(FpSpace.s)
        val summary = homeStatusSummary(status)
        kit.StatusCard(summary.tone, summary.title, summary.detail, summary.tag, onClick = null)

        // ── 作用域：勾没勾、进程跑没跑、是不是旧版本，合成一个词 ──
        val declared = ModuleScopes.declared
        kit.SectionHeader(fpString("status.scopes.title", declared.count { it.packageName in status.scope }, declared.size))
        kit.Card {
            declared.forEachIndexed { i, target ->
                if (i > 0) kit.Divider()
                val (word, emphasis) = scopeState(status, target)
                kit.InfoRow(fpString(target.labelI18n), fpString(word), emphasis)
            }
        }

        // ── 操作 ──
        kit.VSpace(FpSpace.m)
        kit.Card {
            kit.ActionRow(
                title = fpString("status.action.lsposed"),
                summary = null,
                trailingText = null,
                navigates = true,
                onClick = { openLsposedManager(context) },
            )
            kit.Divider()
            kit.ActionRow(
                title = fpString("onb.action.reload"),
                summary = fpString("onb.reload.hint"),
                trailingText = null,
                onClick = { reload() },
                enabled = !busy && status.connected && status.targets.isNotEmpty(),
            )
            // Root 不单列状态：有 Root 才多一个「Root 重启」，没有就不出现
            if (root == Su.State.OK) {
                kit.Divider()
                kit.ActionRow(
                    title = fpString("setup.action.root.restart"),
                    summary = fpString("setup.action.root.restart.hint"),
                    trailingText = null,
                    onClick = { confirmRootRestart = true },
                    enabled = !busy && status.targets.isNotEmpty(),
                )
            }
        }

        // ── 版本与设备 ──
        kit.SectionHeader(fpString("status.info.title"))
        kit.Card {
            kit.InfoRow(fpString("home.info.module"), BuildConfig.MODULE_VERSION, FpEmphasis.MUTED)
            kit.InfoRow(
                fpString("home.info.framework"),
                listOfNotNull(status.frameworkName, status.frameworkVersion?.takeIf(String::isNotBlank))
                    .joinToString(" ")
                    .ifEmpty { fpString("common.unknown") },
                FpEmphasis.MUTED,
            )
            kit.InfoRow(fpString("home.info.device"), DeviceInfo.deviceName, FpEmphasis.MUTED)
            kit.InfoRow(fpString("home.info.system"), DeviceInfo.osName, FpEmphasis.MUTED)
        }
        kit.VSpace(FpSpace.huge)
    }

    kit.ConfirmDialog(
        title = fpString("setup.root.confirm.title"),
        text = fpString("setup.root.confirm.body", status.targets.size),
        confirmLabel = fpString("setup.action.root.restart"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = confirmRootRestart,
        onConfirm = {
            confirmRootRestart = false
            busy = true
            scope.launch {
                val (count, rootState) = ReloadController.restartWithRoot()
                busy = false
                root = rootState
                if (count > 0) reloadFailed = false
                messages.showSnackbar(
                    if (count > 0) {
                        res.getString(fpStringRes(context, "setup.root.restarted"), count)
                    } else {
                        res.getString(fpStringRes(context, "setup.root.failed"))
                    },
                )
            }
        },
        onDismiss = { confirmRootRestart = false },
    )
}

/** 探测真实入口；无入口或启动失败给出手动路径。 */
fun openLsposedManager(context: Context) {
    for (pkg in arrayOf("org.lsposed.manager", "com.lsposed.manager")) {
        if (openPackage(context, pkg)) return
    }
    // LSPosed 2.x（Zygisk 版）**没有独立的管理器应用**：管理器寄生在系统里，靠暗码广播拉起。
    // 它自己的 action.sh 就是这一句（真机 2026-10-25 读 /data/adb/modules/zygisk_lsposed/action.sh）。
    // 这条广播是受保护的，普通应用发不出去，只能走 root；没有 root 就告诉用户拨号暗码。
    val app = context.applicationContext
    Thread {
        val ok = io.github.kotoriinsky.tsuzuri.ui.state.Su.exec(
            "am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android",
        ) == io.github.kotoriinsky.tsuzuri.ui.state.Su.State.OK
        if (!ok) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(app, app.getString(fpStringRes(app, "onb.manager.unavailable")), Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

/** 已安装的 Root 管理器（显示名取自系统，不硬编码）。 */
private data class RootManager(val packageName: String, val label: String)

/**
 * 按常见程度找第一个装着且有启动入口的 Root 管理器。
 * 包名必须同时写进 AndroidManifest 的 `<queries>`，否则 Android 11+ 上「看不见」它。
 * 改了包名的隐藏版 Magisk 找不到，这时页面只显示状态、不给按钮。
 */
private fun detectRootManager(context: Context): RootManager? {
    val pm = context.packageManager
    for (pkg in ROOT_MANAGER_PACKAGES) {
        if (pm.getLaunchIntentForPackage(pkg) == null) continue
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        return RootManager(pkg, label)
    }
    return null
}

private val ROOT_MANAGER_PACKAGES = listOf(
    "me.weishu.kernelsu",   // KernelSU
    "com.rifsxd.ksunext",   // KernelSU Next
    "com.sukisu.ultra",     // SukiSU Ultra
    "com.resukisu.resukisu", // ReSukiSU（2026-10-21 本机实装的就是它，名单里漏了导致「未检测到」）
    "com.topjohnwu.magisk", // Magisk
    "me.bmax.apatch",       // APatch
)

private fun openPackage(context: Context, pkg: String): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/**
 * 一个作用域的状态词（模块状态页每行右侧）：
 * 未勾选（黄）→ 已勾选但进程旧版本（黄）→ 已勾选但进程没在跑（灰）→ 运行中（绿）。
 * 进程名与作用域包名的对应：同名，或「包名:子进程」；system 作用域对应 system_server。
 */
private fun scopeState(status: io.github.kotoriinsky.tsuzuri.ui.state.FrameworkStatus, target: ScopeTarget): Pair<String, FpEmphasis> {
    if (target.packageName !in status.scope) return "status.scope.unchecked" to FpEmphasis.WARNING
    val pkg = target.packageName
    val procs = status.targets.filter {
        it.processName == pkg || it.processName.startsWith("$pkg:") || (pkg == "system" && it.processName == "system_server")
    }
    return when {
        procs.isEmpty() -> "status.scope.idle" to FpEmphasis.MUTED
        procs.any { it in status.staleTargets } -> "status.scope.stale" to FpEmphasis.WARNING
        else -> "status.scope.running" to FpEmphasis.POSITIVE
    }
}
