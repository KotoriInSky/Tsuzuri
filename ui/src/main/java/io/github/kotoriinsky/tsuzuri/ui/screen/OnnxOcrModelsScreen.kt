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

/** 多语种 OCR 独立推理组件与 PP-OCRv6 模型管理页。 */
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

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(refreshTick) {
        remote = withContext(Dispatchers.IO) { OnnxOcrRemote.queryState(context) }
        while (remote.isDownloadingModel) {
            delay(350L)
            remote = withContext(Dispatchers.IO) { OnnxOcrRemote.queryState(context) }
        }
    }

    val mirrorLabels = remember { listOf("魔搭", "HF 镜像", "HF 官方", "自定义") }
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
        // 推理组件
        kit.Card {
            if (remote.pluginInstalled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "推理组件",
                        color = colors.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    kit.Chip(
                        label = if (remote.pluginVersion.isNotBlank()) "v${remote.pluginVersion}" else "已安装",
                        emphasis = FpEmphasis.POSITIVE,
                    )
                }
                kit.ActionRow(
                    title = "释放缓存",
                    summary = null,
                    trailingText = null,
                    onClick = { OnnxOcrRemote.releaseCache(context) },
                )
            } else {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "推理组件",
                            color = colors.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        kit.Chip(label = "未安装", emphasis = FpEmphasis.WARNING)
                    }
                    kit.SegmentedRow(
                        title = "镜像",
                        summary = null,
                        options = pluginMirrorLabels,
                        selectedIndex = pluginMirrorIdx,
                        onSelect = { pluginMirrorIdx = it },
                        enabled = pluginDl !is OnnxOcrRemote.PluginDownloadState.Downloading,
                    )
                    kit.TextInput(
                        value = pluginUrlDraft,
                        label = "组件 APK 地址",
                        onValueChange = {
                            pluginUrlDraft = it
                            ConfigStore.putString(pluginUrlKey, it.trim())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (val pds = pluginDl) {
                        is OnnxOcrRemote.PluginDownloadState.Downloading -> {
                            if (pds.totalBytes > 0L) {
                                LinearProgressIndicator(progress = { pds.fraction }, modifier = Modifier.fillMaxWidth())
                                kit.Body(
                                    text = "${formatMb(pds.downloadedBytes)} / ${formatMb(pds.totalBytes)}",
                                    emphasis = FpEmphasis.MUTED,
                                    style = FpTextStyle.CAPTION,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                kit.Body(
                                    text = formatMb(pds.downloadedBytes),
                                    emphasis = FpEmphasis.MUTED,
                                    style = FpTextStyle.CAPTION,
                                )
                            }
                        }
                        is OnnxOcrRemote.PluginDownloadState.Failed -> {
                            kit.Body(text = pds.message, emphasis = FpEmphasis.NEGATIVE, style = FpTextStyle.CAPTION)
                        }
                        else -> Unit
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pluginDl is OnnxOcrRemote.PluginDownloadState.Downloading) {
                            kit.Button(
                                text = "取消",
                                onClick = { OnnxOcrRemote.cancelPluginDownload() },
                                primary = false,
                            )
                        } else {
                            kit.Button(
                                text = "下载安装",
                                onClick = {
                                    val chosen = OnnxOcr.PluginMirror.entries.getOrElse(pluginMirrorIdx) {
                                        OnnxOcr.PluginMirror.GHPROXY
                                    }
                                    OnnxOcrRemote.startPluginDownload(context, pluginUrlDraft, chosen)
                                },
                                primary = true,
                            )
                            kit.Button(
                                text = "检测",
                                onClick = { refreshTick++ },
                                primary = false,
                            )
                        }
                    }
                }
            }
        }

        // 模型下载源
        kit.Card {
            kit.SegmentedRow(
                title = "下载源",
                summary = null,
                options = mirrorLabels,
                selectedIndex = selectedMirrorIndex,
                onSelect = { idx ->
                    val chosen = OnnxOcr.Mirror.entries.getOrNull(idx) ?: return@SegmentedRow
                    ConfigStore.putString(mirrorKey, chosen.id)
                },
                enabled = !remote.isDownloadingModel,
            )
            if (activeMirror == OnnxOcr.Mirror.CUSTOM || remote.dlState == OnnxOcr.DL_FAILED) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (activeMirror == OnnxOcr.Mirror.CUSTOM) {
                        kit.TextInput(
                            value = customMirrorDraft,
                            label = "镜像地址或 {repo}/{file} 模板",
                            onValueChange = {
                                customMirrorDraft = it
                                ConfigStore.putString(customMirrorKey, it.trim())
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (remote.dlState == OnnxOcr.DL_FAILED) {
                        kit.Body(
                            text = "${remote.dlTier}：${remote.dlMessage}",
                            emphasis = FpEmphasis.NEGATIVE,
                            style = FpTextStyle.CAPTION,
                        )
                    }
                }
            }
        }

        // PP-OCRv6 三档模型
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
                                OnnxOcr.Tier.SMALL -> "PP-OCRv6 Small"
                                OnnxOcr.Tier.MEDIUM -> "PP-OCRv6 Medium"
                            }
                            Text(
                                text = title,
                                color = colors.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            val subtitle = if (installed && diskBytes > 0L) {
                                "${tier.langSummary.substringBefore(" · ")} · 已占 ${formatMb(diskBytes)}"
                            } else {
                                tier.langSummary
                            }
                            kit.Body(
                                text = subtitle,
                                emphasis = FpEmphasis.MUTED,
                                style = FpTextStyle.CAPTION,
                            )
                        }
                        val (badge, badgeEmphasis) = when {
                            isDownloadingThis -> "${(remote.dlFraction * 100).toInt()}%" to FpEmphasis.ACCENT
                            installed && isCurrent -> "当前" to FpEmphasis.POSITIVE
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
                            text = "${formatMb(remote.dlBytes)} / ${formatMb(remote.dlTotal)}",
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
                                    text = "取消",
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
                                        text = "使用",
                                        onClick = { ConfigStore.putString(tierKey, tier.id) },
                                        primary = true,
                                    )
                                }
                                kit.Button(
                                    text = "删除",
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
                                    text = "下载",
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
