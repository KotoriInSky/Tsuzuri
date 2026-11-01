package io.github.kotoriinsky.tsuzuri.ui.screen

import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import androidx.compose.runtime.setValue
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.FrameworkBridge

@Composable
internal fun featureScopeReady(spec: FeatureSpec): Boolean {
    val status by FrameworkBridge.status
    return status.connected && spec.scope.all { it.packageName in status.scope }
}

/**
 * 申请作用域：框架弹系统确认框；不成功就打开 LSPosed 让用户自己勾。
 * 首页横幅、详情页横幅、模块状态页共用这一个入口，结果都用 Toast 说清楚。
 */
internal fun requestScopes(context: Context, targets: List<ScopeTarget>) {
    if (targets.isEmpty()) return
    val packages = targets.map { it.packageName }
    FrameworkBridge.requestScopes(packages) { ok, message ->
        val text = if (ok) {
            context.getString(fpStringRes(context, "setup.request.done"), packages.size)
        } else {
            context.getString(fpStringRes(context, "setup.request.failed"), message.orEmpty())
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            if (!ok) openLsposedManager(context)
            FrameworkBridge.refresh()
        }
    }
}

/**
 * 缺作用域时的横幅（详情页）。按钮**直接申请**，不再先跳模块状态页 ——
 * 用户在这一页想要的是「让这个功能能用」，多跳一页就多一次流失（用户 2026-10-25：提示要够明显直接）。
 */
@Composable
internal fun ScopeRequirement(spec: FeatureSpec, onFix: () -> Unit) {
    val status by FrameworkBridge.status
    if (featureScopeReady(spec)) return
    val context = LocalContext.current
    val kit = LocalFpKit.current
    if (!status.connected) {
        kit.Banner(fpString("home.inactive.banner"), FpEmphasis.NEGATIVE, fpString("home.inactive.banner.action")) {
            openLsposedManager(context)
        }
        return
    }
    val missing = spec.scope.filter { it.packageName !in status.scope }
    kit.Banner(
        fpString("feature.scope.required", missing.map { fpString(it.labelI18n) }.joinToString("、")),
        FpEmphasis.NEGATIVE,
        fpString("home.scope.banner.action"),
    ) { requestScopes(context, missing) }
}

/**
 * 首页 / 主动智能页的功能行。有参数的功能是入口行（`已开启 ›`），开关和缺作用域的提示
 * 都在详情页；没有参数的功能是开关行，缺作用域（或模块没激活）时开关置灰，
 * 点灰色开关弹框说明，并一键去解决（用户 2026-10-27）。原来首页开关不看作用域，
 * 能拨开但什么都不生效，而详情页同一个开关是灰的 —— 两处说法不一（交互审查 I-02）。
 */
@Composable
internal fun ScopedFeatureRow(spec: FeatureSpec, prefs: io.github.kotoriinsky.tsuzuri.core.Prefs, onOpen: () -> Unit) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val status by FrameworkBridge.status
    val ready = featureScopeReady(spec)
    var asking by androidx.compose.runtime.remember(spec.key) { androidx.compose.runtime.mutableStateOf(false) }
    kit.FeatureRow(
        title = fpString(spec.titleI18n),
        summary = fpString(spec.summaryI18n),
        icon = featureIcon(spec),
        checked = prefs.isEnabled(spec),
        onCheckedChange = { ConfigStore.putBoolean(io.github.kotoriinsky.tsuzuri.core.Keys.enabled(spec.key), it) },
        onClick = onOpen.takeIf { spec.prefs.isNotEmpty() },
        stateText = fpString(if (prefs.isEnabled(spec)) "feature.state.on" else "feature.state.off"),
        chip = featureStatusChip(spec, prefs),
        switchEnabled = ready,
        onBlockedSwitch = { asking = true },
    )
    if (ready) return
    val connected = status.connected
    val missing = spec.scope.filter { it.packageName !in status.scope }
    kit.ConfirmDialog(
        title = fpString(if (connected) "scope.gate.title" else "scope.gate.inactive.title"),
        text = if (connected) fpString("scope.gate.body", localizedScopeNames(missing)) else null,
        confirmLabel = fpString(if (connected) "scope.gate.action" else "home.inactive.banner.action"),
        dismissLabel = fpString("settings.cancel"),
        visible = asking,
        onConfirm = {
            asking = false
            if (connected) requestScopes(context, missing) else openLsposedManager(context)
        },
        onDismiss = { asking = false },
    )
}
