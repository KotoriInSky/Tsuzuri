package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.ocr.OnnxOcrRemote
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 多语种端侧 OCR 环境组件与模型管理页：
 * 1. 独立 OCR 推理组件（`io.github.kotoriinsky.tsuzuri.ocr`）状态检测与在线镜像下载安装；
 * 2. 模型加速源选择（魔搭 / HF Mirror / Hugging Face / 自定义加速源模板）；
 * 3. PP-OCRv6 Tiny / Small / Medium 三档模型一键下载、进度同步、切换与清理。
 */
@Composable
fun OnnxOcrModelsScreen(@Suppress("UNUSED_PARAMETER") spec: PrefSpec.Screen, padding: PaddingValues) {
    val kit = LocalFpKit.current
    val colors = kit.colors()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = ConfigStore.observe()

    val tierKey = Keys.pref(Catalog.ONNX_OCR, OnnxOcr.PREF_TIER)
    val mirrorKey = Keys.pref(Catalog.ONNX_OCR, OnnxOcr.PREF_MIRROR)
    val customMirrorKey = Keys.pref(Catalog.ONNX_OCR, OnnxOcr.PREF_CUSTOM_MIRROR)
    val pluginUrlKey = Keys.pref(Catalog.ONNX_OCR, OnnxOcr.PREF_PLUGIN_URL)

    val activeTier = OnnxOcr.Tier.of(prefs.rawString(tierKey, OnnxOcr.Tier.SMALL.id))
    val activeMirror = OnnxOcr.Mirror.of(prefs.rawString(mirrorKey, OnnxOcr.Mirror.MODELSCOPE.id))
    val savedCustomMirror = prefs.rawString(customMirrorKey, "").orEmpty()
    val savedPluginUrl = prefs.rawString(pluginUrlKey, "").orEmpty()
    var customMirrorDraft by remember(savedCustomMirror) { mutableStateOf(savedCustomMirror) }
    var pluginUrlDraft by remember(savedPluginUrl) { mutableStateOf(savedPluginUrl) }
    var pluginMirrorIdx by remember { mutableIntStateOf(0) }

    var refreshTick by remember { mutableIntStateOf(0) }
    var remote by remember { mutableStateOf(OnnxOcrRemote.RemoteState(pluginInstalled = false)) }
    val pluginDl by OnnxOcrRemote.pluginDownloadState.collectAsState()

    // 从系统安装器返回时自动重新检测独立组件是否已装好
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 初次进入或手动刷新时拉取独立组件状态；仅在组件内正在下载模型时轻量轮询进度
    LaunchedEffect(refreshTick) {
        remote = withContext(Dispatchers.IO) { OnnxOcrRemote.queryState(context) }
        while (remote.isDownloadingModel) {
            delay(350L)
            remote = withContext(Dispatchers.IO) { OnnxOcrRemote.queryState(context) }
        }
    }

    val mirrorLabels = remember { listOf("魔搭加速", "HF 镜像", "HF 官方", "自定义") }
    val selectedMirrorIndex = OnnxOcr.Mirror.entries.indexOf(activeMirror).coerceAtLeast(0)
    val pluginMirrorLabels = remember { listOf("GHProxy", "GHFast", "直连") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 卡片 1：独立 OCR 推理组件状态 & 在线配置安装
        kit.Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "独立 OCR 推理组件",
                            color = colors.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        kit.Body(
                            text = if (remote.pluginInstalled) {
                                "ONNX Runtime（XNNPACK）独立进程就绪，主模块零体积负担"
                            } else {
                                "推理引擎独立封装在可选组件 APK（${OnnxOcr.PLUGIN_PACKAGE}），需先安装再下载模型"
                            },
                            emphasis = FpEmphasis.MUTED,
                            style = FpTextStyle.CAPTION,
                        )
                    }
                    kit.Chip(
                        label = if (remote.pluginInstalled) {
                            if (remote.pluginVersion.isNotBlank()) "已安装 v${remote.pluginVersion}" else "已安装"
                        } else {
                            "未安装"
                        },
                        emphasis = if (remote.pluginInstalled) FpEmphasis.POSITIVE else FpEmphasis.WARNING,
                    )
                }

                if (!remote.pluginInstalled) {
                    kit.SegmentedRow(
                        title = "组件加速镜像",
                        summary = "仅对 GitHub Release 链接自动套用加速前缀",
                        options = pluginMirrorLabels,
                        selectedIndex = pluginMirrorIdx,
                        onSelect = { pluginMirrorIdx = it },
                        enabled = pluginDl !is OnnxOcrRemote.PluginDownloadState.Downloading,
                    )
                    kit.TextInput(
                        value = pluginUrlDraft,
                        label = "组件 APK 地址（可填直链或 Release URL）",
                        onValueChange = {
                            pluginUrlDraft = it
                            ConfigStore.putString(pluginUrlKey, it.trim())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (val pds = pluginDl) {
                        is OnnxOcrRemote.PluginDownloadState.Downloading -> {
                            if (pds.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { pds.fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                kit.Body(
                                    text = "正在下载组件：${formatMb(pds.downloadedBytes)} / ${formatMb(pds.totalBytes)}",
                                    emphasis = FpEmphasis.MUTED,
                                    style = FpTextStyle.CAPTION,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                kit.Body(
                                    text = "正在下载组件：已接收 ${formatMb(pds.downloadedBytes)}",
                                    emphasis = FpEmphasis.MUTED,
                                    style = FpTextStyle.CAPTION,
                                )
                            }
                        }
                        is OnnxOcrRemote.PluginDownloadState.ReadyToInstall -> {
                            kit.Body(
                                text = "组件安装包已就绪，请在系统安装器中完成安装，返回本页自动刷新。",
                                emphasis = FpEmphasis.POSITIVE,
                                style = FpTextStyle.CAPTION,
                            )
                        }
                        is OnnxOcrRemote.PluginDownloadState.Failed -> {
                            kit.Body(
                                text = "下载失败：${pds.message}",
                                emphasis = FpEmphasis.NEGATIVE,
                                style = FpTextStyle.CAPTION,
                            )
                        }
                        else -> Unit
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pluginDl is OnnxOcrRemote.PluginDownloadState.Downloading) {
                            kit.Button(
                                text = "取消下载",
                                onClick = { OnnxOcrRemote.cancelPluginDownload() },
                                primary = false,
                            )
                        } else {
                            kit.Button(
                                text = "在线下载并安装",
                                onClick = {
                                    val chosenMirror = OnnxOcr.PluginMirror.entries.getOrElse(pluginMirrorIdx) {
                                        OnnxOcr.PluginMirror.GHPROXY
                                    }
                                    OnnxOcrRemote.startPluginDownload(context, pluginUrlDraft, chosenMirror)
                                },
                                primary = true,
                            )
                            kit.Button(
                                text = "重新检测",
                                onClick = { refreshTick++ },
                                primary = false,
                            )
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        kit.Button(
                            text = "释放推理缓存",
                            onClick = { OnnxOcrRemote.releaseCache(context) },
                            primary = false,
                        )
                        kit.Button(
                            text = "刷新状态",
                            onClick = { refreshTick++ },
                            primary = false,
                        )
                    }
                }
            }
        }

        // 卡片 2：模型下载加速源（含自定义镜像模板）
        kit.Card {
            kit.SegmentedRow(
                title = "模型下载加速源",
                summary = "国内优先魔搭或 HF 镜像，也支持自定义反代域名或 URL 模板",
                options = mirrorLabels,
                selectedIndex = selectedMirrorIndex,
                onSelect = { idx ->
                    val chosen = OnnxOcr.Mirror.entries.getOrNull(idx) ?: return@SegmentedRow
                    ConfigStore.putString(mirrorKey, chosen.id)
                },
                enabled = !remote.isDownloadingModel,
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (activeMirror == OnnxOcr.Mirror.CUSTOM) {
                    kit.TextInput(
                        value = customMirrorDraft,
                        label = "自定义镜像前缀或模板（支持 {repo}/{file}）",
                        onValueChange = {
                            customMirrorDraft = it
                            ConfigStore.putString(customMirrorKey, it.trim())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                kit.Body(
                    text = "模型保存在独立 OCR 组件私有目录，不随备份导出；下载后在「全局识别」与图片「提取文字」热生效。总开关关闭时自动退回系统太乙 OCR。",
                    emphasis = FpEmphasis.MUTED,
                    style = FpTextStyle.CAPTION,
                )
                when (remote.dlState) {
                    OnnxOcr.DL_DONE -> {
                        kit.Body(
                            text = "已就绪：PP-OCRv6 ${remote.dlTier.uppercase(Locale.ROOT)} 下载完成",
                            emphasis = FpEmphasis.POSITIVE,
                            style = FpTextStyle.CAPTION,
                        )
                    }
                    OnnxOcr.DL_FAILED -> {
                        kit.Body(
                            text = "上次下载失败（${remote.dlTier}）：${remote.dlMessage}。可切换上方其他加速源重试。",
                            emphasis = FpEmphasis.NEGATIVE,
                            style = FpTextStyle.CAPTION,
                        )
                    }
                }
            }
        }

        // 卡片 3–5：PP-OCRv6 三档模型
        for (tier in OnnxOcr.Tier.entries) {
            val installed = tier.id in remote.installedTiers
            val diskBytes = remote.tierBytes[tier.id] ?: 0L
            val isDownloadingThis = remote.isDownloadingModel && remote.dlTier == tier.id
            val isCurrent = activeTier == tier

            kit.Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val title = when (tier) {
                                OnnxOcr.Tier.TINY -> "PP-OCRv6 Tiny"
                                OnnxOcr.Tier.SMALL -> "PP-OCRv6 Small（推荐）"
                                OnnxOcr.Tier.MEDIUM -> "PP-OCRv6 Medium"
                            }
                            Text(
                                text = title,
                                color = colors.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            kit.Body(
                                text = tier.langSummary,
                                emphasis = FpEmphasis.MUTED,
                                style = FpTextStyle.CAPTION,
                            )
                        }
                        val (badge, badgeEmphasis) = when {
                            isDownloadingThis -> "下载中" to FpEmphasis.ACCENT
                            installed && isCurrent -> "当前使用" to FpEmphasis.POSITIVE
                            installed -> "已下载" to FpEmphasis.ACCENT
                            else -> "未下载" to FpEmphasis.MUTED
                        }
                        kit.Chip(label = badge, emphasis = badgeEmphasis)
                    }

                    if (isDownloadingThis) {
                        LinearProgressIndicator(
                            progress = { remote.dlFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        kit.Body(
                            text = "${remote.dlFile} · ${formatMb(remote.dlBytes)} / ${formatMb(remote.dlTotal)} (${(remote.dlFraction * 100).toInt()}%)",
                            emphasis = FpEmphasis.MUTED,
                            style = FpTextStyle.CAPTION,
                        )
                    } else if (installed) {
                        kit.Body(
                            text = "本地占用 ${formatMb(diskBytes)}（det.onnx + rec.onnx + 字典）",
                            emphasis = FpEmphasis.MUTED,
                            style = FpTextStyle.CAPTION,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            isDownloadingThis -> {
                                kit.Button(
                                    text = "取消下载",
                                    onClick = {
                                        OnnxOcrRemote.cancelModelDownload(context)
                                        refreshTick++
                                    },
                                    primary = false,
                                )
                            }
                            installed -> {
                                if (!isCurrent) {
                                    kit.Button(
                                        text = "设为当前",
                                        onClick = { ConfigStore.putString(tierKey, tier.id) },
                                        primary = true,
                                    )
                                }
                                kit.Button(
                                    text = "删除模型",
                                    onClick = {
                                        OnnxOcrRemote.deleteTier(context, tier)
                                        refreshTick++
                                    },
                                    primary = false,
                                    enabled = !remote.isDownloadingModel,
                                )
                            }
                            else -> {
                                kit.Button(
                                    text = if (remote.pluginInstalled) {
                                        "一键下载（${formatMb(tier.totalBytes)}）"
                                    } else {
                                        "请先安装上方推理组件"
                                    },
                                    onClick = {
                                        ConfigStore.putString(tierKey, tier.id)
                                        OnnxOcrRemote.startModelDownload(
                                            context = context,
                                            tier = tier,
                                            mirror = activeMirror,
                                            customMirrorUrl = customMirrorDraft,
                                        )
                                        refreshTick++
                                    },
                                    primary = remote.pluginInstalled && tier == OnnxOcr.Tier.SMALL,
                                    enabled = remote.pluginInstalled && !remote.isDownloadingModel,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMb(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
