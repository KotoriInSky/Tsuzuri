package io.github.kotoriinsky.tsuzuri.ui.screen

import io.github.kotoriinsky.tsuzuri.ui.state.ModuleScopes
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.state.ReloadController
import io.github.kotoriinsky.tsuzuri.ui.state.Su
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.FeatureGroup
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.settingsFeatures
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpErrorState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FrameworkBridge
import io.github.kotoriinsky.tsuzuri.ui.state.FrameworkPhase

/**
 * 首页，照 HyperOS 设置首页排：
 *  1. 搜索入口（大标题由外壳的可折叠标题栏画）；
 *  2. 状态卡（KernelSU 两态）：正常是绿卡，有问题是普通卡 + 最要紧的一项。点了都进「模块状态」页；
 *  3. 按分组的功能卡：小节标题 + 卡片，**开关留在行内**，不多加一层页面
 *     （上一次撤掉分组，就是因为「合并入口」把开关藏进了二级页）。
 *
 * 版本 / 设备这类只读信息不在首页：它们在「模块状态」页里，首页只放用户要做决定的东西。
 */
@Composable
fun HomeScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val status by FrameworkBridge.status

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        kit.SearchEntry(fpString("search.hint")) { navigate(Dest.Search) }
        kit.VSpace(FpSpace.m)

        // 作用域 / 激活问题**只由状态卡说**（红 / 黄 / 绿）：不另加横幅、不另加错误卡
        val summary = homeStatusSummary(status)
        val context = LocalContext.current
        val reloadScope = rememberCoroutineScope()
        kit.StatusCard(
            tone = summary.tone,
            title = summary.title,
            detail = summary.detail,
            tag = summary.tag,
            onClick = when (summary.target) {
                StatusTarget.LSPOSED -> ({ openLsposedManager(context) })
                StatusTarget.SETUP -> ({ navigate(Dest.Setup) })
                // 待重载：就地热重载（不黑屏），一个都没成功才去模块状态页
                StatusTarget.RELOAD -> ({
                    reloadScope.launch {
                        val o = ReloadController.reloadAll()
                        val text = when {
                            o.total == 0 -> context.getString(fpStringRes(context, "setup.reload.none"))
                            o.fullyReloaded -> context.getString(fpStringRes(context, "setup.reload.done"), o.hotOk)
                            else -> context.getString(fpStringRes(context, "setup.reload.partial"), o.hotOk, o.hotFailed)
                        }
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                        if (!o.anyReloaded) navigate(Dest.Setup)
                    }
                })
            },
        )

        val prefs = ConfigStore.observe()
        val groups = Catalog.settingsFeatures.groupBy { it.group }
        // 枚举顺序即展示顺序；空组（如已下架的负一屏）不占小节标题
        for (group in FeatureGroup.entries) {
            val features = groups[group] ?: continue
            if (group == FeatureGroup.CLIPBOARD) {
                // 主动智能合并成一个入口，各项收进二级页
                kit.VSpace(FpSpace.m)
                kit.Card {
                    // 走 FeatureRow 而不是 ActionRow：两者行首间距不同，同一页的标题会对不齐
                    kit.FeatureRow(
                        title = fpString(group.titleI18n),
                        summary = fpString("proactive_summary"),
                        icon = groupIcon(group),
                        checked = false,
                        onCheckedChange = {},
                        onClick = { navigate(Dest.Proactive) },
                    )
                }
                continue
            }
            kit.SectionHeader(title = fpString(group.titleI18n))
            // HyperOS 设置的卡片内没有分割线，靠行距与色块图标分隔
            kit.Card {
                for (spec in features) {
                    if (spec.key == Catalog.PA_WIDGET_ADMISSION) {
                        kit.FeatureRow(
                            title = fpString("widget_settings_title"),
                            summary = fpString("widget_settings_summary"),
                            icon = featureIcon(spec),
                            checked = false,
                            onCheckedChange = {},
                            onClick = { navigate(Dest.WidgetSettings) },
                        )
                        continue
                    }
                    if (spec.key == Catalog.PA_WIDGET_PICKER_INJECT || spec.key == Catalog.WIDGET_STYLE) continue
                    ScopedFeatureRow(spec, prefs) { navigate(Dest.FeatureDetail(spec.key)) }
                }
            }
        }

        kit.VSpace(FpSpace.huge)
    }
}

/** 状态卡点了去哪：红卡 / 缺作用域的黄卡去 LSPosed 处理，待重载就地重载，其它去模块状态页。 */
internal enum class StatusTarget { LSPOSED, SETUP, RELOAD }

/** 状态卡要显示的内容。判定集中在这里，模块状态页用同一份结论，两处不会说法不一。 */
internal data class HomeStatusSummary(
    val tone: FpEmphasis,
    val title: String,
    val detail: String?,
    val tag: String?,
    val target: StatusTarget = StatusTarget.SETUP,
)

/**
 * 按「不处理就用不了」的程度取**最要紧的一项**：
 * 未激活 / 读不到框架（红）→ 缺作用域（黄）→ 没有进程加载（黄）→ 进程待重载（黄）
 * → 拨过开关待重载（黄）→ 运行中（绿）。
 */
@Composable
internal fun homeStatusSummary(status: io.github.kotoriinsky.tsuzuri.ui.state.FrameworkStatus): HomeStatusSummary {
    // 作用域覆盖率只算「本模块需要的那些里勾了几个」。直接用 status.scope.size 会出现
    // 7/6 这种读数（用户还勾了别的进程），一眼就是错的。真机 2026-10-17 实见。
    val granted = ModuleScopes.declared.count { it.packageName in status.scope }
    val missing = status.missingScopes
    val stale = status.staleTargets.size
    return when {
        status.isLoading -> HomeStatusSummary(FpEmphasis.POSITIVE, fpString("home.hero.loading"), null, null)
        !status.connected || status.phase == FrameworkPhase.ERROR -> HomeStatusSummary(
            FpEmphasis.NEGATIVE,
            fpString("home.status.inactive"),
            fpString("home.status.inactive.detail"),
            null,
            StatusTarget.LSPOSED,
        )
        missing.isNotEmpty() -> HomeStatusSummary(
            FpEmphasis.WARNING,
            fpString("home.status.scope.partial"),
            // 第二行点名哪些没勾（用户 2026-10-25）
            fpString("home.status.scope.missing", missing.map { fpString(it.labelI18n) }.joinToString("、")),
            fpString("home.status.tag", granted, ModuleScopes.declared.size),
            StatusTarget.LSPOSED,
        )
        status.targets.isEmpty() -> HomeStatusSummary(FpEmphasis.WARNING, fpString("home.issue.targets"), null, null)
        stale > 0 -> HomeStatusSummary(
            FpEmphasis.WARNING,
            fpString("home.status.stale", stale),
            fpString("home.status.stale.detail"),
            null,
        )
        ConfigStore.pendingReloadCount > 0 -> HomeStatusSummary(
            FpEmphasis.WARNING,
            fpString("home.status.pending", ConfigStore.pendingReloadCount),
            null,
            null,
            StatusTarget.RELOAD,
        )
        else -> HomeStatusSummary(
            tone = FpEmphasis.POSITIVE,
            title = fpString("home.status.ok"),
            detail = listOfNotNull(status.frameworkName, status.frameworkVersion?.takeIf(String::isNotBlank))
                .joinToString(" ")
                .ifEmpty { null },
            tag = fpString("home.status.tag", granted, ModuleScopes.declared.size),
        )
    }
}

/**
 * 首页右上角：重启目标进程（用户 2026-10-25）。
 *
 * 有 Root 时直接用 Root 重启（最可靠，系统界面会闪一下，所以先确认）；
 * 没有 Root 时退回框架热重载，并如实报告成功几个。
 */
@Composable
internal fun HomeRestartAction() {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val status by FrameworkBridge.status
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val enabled = !busy && status.connected && status.targets.isNotEmpty()

    androidx.compose.material3.Icon(
        imageVector = androidx.compose.material.icons.Icons.Rounded.Refresh,
        contentDescription = fpString("home.restart.desc"),
        tint = if (enabled) kit.colors().onSurface else kit.colors().muted,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled) { confirm = true }
            .padding(12.dp),
    )

    kit.ConfirmDialog(
        title = fpString("setup.root.confirm.title"),
        text = fpString("setup.root.confirm.body", status.targets.size),
        confirmLabel = fpString("setup.action.root.restart"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = confirm,
        onConfirm = {
            confirm = false
            busy = true
            scope.launch {
                val (count, root) = ReloadController.restartWithRoot()
                val text = when {
                    count > 0 -> context.getString(fpStringRes(context, "setup.root.restarted"), count)
                    root != Su.State.OK -> {
                        val o = ReloadController.reloadAll()
                        when {
                            o.total == 0 -> context.getString(fpStringRes(context, "setup.reload.none"))
                            o.fullyReloaded -> context.getString(fpStringRes(context, "setup.reload.done"), o.hotOk)
                            else -> context.getString(fpStringRes(context, "setup.reload.partial"), o.hotOk, o.hotFailed)
                        }
                    }
                    else -> context.getString(fpStringRes(context, "setup.root.failed"))
                }
                busy = false
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = { confirm = false },
    )
}
