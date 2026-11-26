package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.ocr.OnnxOcrRemote
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 多语种 OCR 单页内容区（直接内联在「多语种 OCR」详情页内）：
 * - 独立推理组件状态、在线安装、下载源（含一级域名与自定义模板）、释放缓存与卸载组件；
 * - 模型区块（左对齐标注「基于 PaddleOCRv6」），单卡三行支持下载 / 取消 / 切换 / 删除。
 */
@Composable
fun OnnxOcrDetailContent() {
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
    val modelDl by OnnxOcrRemote.modelDownloadState.collectAsState()
    val pluginDl by OnnxOcrRemote.pluginDownloadState.collectAsState()
    val isDownloadingModel = modelDl is OnnxOcrRemote.ModelDownloadState.Downloading

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(refreshTick, modelDl is OnnxOcrRemote.ModelDownloadState.Done) {
        remote = withContext(Dispatchers.IO) { OnnxOcrRemote.queryState(context) }
    }

    val mirrorLabels = remember {
        listOf(
            "魔搭（modelscope.cn）",
            "HF 镜像（hf-mirror.com）",
            "Hugging Face（huggingface.co）",
            "自定义",
        )
    }
    val selectedMirrorIndex = OnnxOcr.Mirror.entries.indexOf(activeMirror).coerceAtLeast(0)
    val pluginMirrorLabels = remember {
        listOf(
            "GHProxy（gh-proxy.com）",
            "GHFast（ghfast.top）",
            "GitHub 直连（github.com）",
        )
    }

    // 区块 1：推理组件与下载源
    kit.VSpace(FpSpace.m)
    kit.Card {
        kit.InfoRow(
            title = "推理组件",
            value = when {
                remote.pluginInstalled && remote.pluginVersion.isNotBlank() -> "v${remote.pluginVersion}"
                remote.pluginInstalled -> "已安装"
                else -> "未安装"
            },
            emphasis = if (remote.pluginInstalled) FpEmphasis.POSITIVE else FpEmphasis.WARNING,
        )

        if (remote.pluginInstalled) {
            kit.Divider()
            kit.DropdownRow(
                title = "下载源",
                summary = null,
                options = mirrorLabels,
                selectedIndex = selectedMirrorIndex,
                onSelect = { idx ->
                    val chosen = OnnxOcr.Mirror.entries.getOrNull(idx) ?: return@DropdownRow
                    ConfigStore.putString(mirrorKey, chosen.id)
                },
                enabled = !isDownloadingModel,
            )
            if (activeMirror == OnnxOcr.Mirror.CUSTOM) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
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
            }
            kit.Divider()
            kit.ActionRow(
                title = "释放缓存",
                summary = null,
                trailingText = null,
                onClick = { OnnxOcrRemote.releaseCache(context) },
            )
            kit.Divider()
            kit.ActionRow(
                title = "卸载组件",
                summary = null,
                trailingText = null,
                destructive = true,
                enabled = !isDownloadingModel,
                onClick = { OnnxOcrRemote.launchUninstaller(context) },
            )
        } else {
            kit.Divider()
            kit.DropdownRow(
                title = "组件镜像",
                summary = null,
                options = pluginMirrorLabels,
                selectedIndex = pluginMirrorIdx,
                onSelect = { pluginMirrorIdx = it },
                enabled = pluginDl !is OnnxOcrRemote.PluginDownloadState.Downloading,
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                            LinearProgressIndicator(
                                progress = { pds.fraction },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = colors.accent,
                                trackColor = colors.outline.copy(alpha = 0.25f),
                                strokeCap = StrokeCap.Round,
                                drawStopIndicator = {},
                            )
                            kit.Body(
                                text = "${formatMb(pds.downloadedBytes)} / ${formatMb(pds.totalBytes)}",
                                emphasis = FpEmphasis.MUTED,
                                style = FpTextStyle.CAPTION,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = colors.accent,
                                trackColor = colors.outline.copy(alpha = 0.25f),
                                strokeCap = StrokeCap.Round,
                            )
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
                        CompactPill(
                            text = "取消",
                            containerColor = colors.surfaceVariant,
                            contentColor = colors.onSurface,
                            onClick = { OnnxOcrRemote.cancelPluginDownload() },
                        )
                    } else {
                        CompactPill(
                            text = "下载安装",
                            containerColor = colors.accent,
                            contentColor = colors.onAccent,
                            onClick = {
                                val chosen = OnnxOcr.PluginMirror.entries.getOrElse(pluginMirrorIdx) {
                                    OnnxOcr.PluginMirror.GHPROXY
                                }
                                OnnxOcrRemote.startPluginDownload(context, pluginUrlDraft, chosen)
                            },
                        )
                        CompactPill(
                            text = "检测",
                            containerColor = colors.surfaceVariant,
                            contentColor = colors.onSurface,
                            onClick = { refreshTick++ },
                        )
                    }
                }
            }
        }
    }

    if (modelDl is OnnxOcrRemote.ModelDownloadState.Failed) {
        val failed = modelDl as OnnxOcrRemote.ModelDownloadState.Failed
        kit.VSpace(FpSpace.s)
        kit.Banner(
            text = "下载失败：${failed.message}",
            emphasis = FpEmphasis.NEGATIVE,
        )
    }

    // 区块 2：模型（靠左对齐，明确标注基于 PaddleOCRv6）
    kit.SectionHeader(title = "模型 · 基于 PaddleOCRv6")

    kit.Card {
        OnnxOcr.Tier.entries.forEachIndexed { idx, tier ->
            if (idx > 0) kit.Divider()
            val installed = tier.id in remote.installedTiers
            val diskBytes = remote.tierBytes[tier.id] ?: 0L
            val dl = modelDl as? OnnxOcrRemote.ModelDownloadState.Downloading
            val isDownloadingThis = dl?.tier == tier
            val isCurrent = activeTier == tier

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (installed && !isCurrent && !isDownloadingModel) {
                            Modifier.clickable { ConfigStore.putString(tierKey, tier.id) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val title = when (tier) {
                                OnnxOcr.Tier.TINY -> "Tiny"
                                OnnxOcr.Tier.SMALL -> "Small"
                                OnnxOcr.Tier.MEDIUM -> "Medium"
                            }
                            Text(
                                text = title,
                                color = colors.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (installed && isCurrent) {
                                kit.Chip(label = "当前", emphasis = FpEmphasis.POSITIVE)
                            } else if (isDownloadingThis && dl != null) {
                                kit.Chip(label = "${(dl.fraction * 100).toInt()}%", emphasis = FpEmphasis.ACCENT)
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        val subtitle = when {
                            isDownloadingThis && dl != null -> "${formatMb(dl.downloadedBytes)} / ${formatMb(dl.totalBytes)}"
                            installed && diskBytes > 0L -> "${tier.langSummary.substringBefore(" · ")} · ${formatMb(diskBytes)}"
                            else -> tier.langSummary
                        }
                        Text(
                            text = subtitle,
                            color = colors.muted,
                            fontSize = 13.sp,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            isDownloadingThis -> {
                                CompactPill(
                                    text = "取消",
                                    containerColor = colors.surfaceVariant,
                                    contentColor = colors.onSurface,
                                    onClick = { OnnxOcrRemote.cancelModelDownload() },
                                )
                            }
                            installed -> {
                                if (!isCurrent) {
                                    CompactPill(
                                        text = "使用",
                                        containerColor = colors.accent.copy(alpha = 0.14f),
                                        contentColor = colors.accent,
                                        onClick = { ConfigStore.putString(tierKey, tier.id) },
                                    )
                                }
                                CompactPill(
                                    text = "删除",
                                    containerColor = colors.negative.copy(alpha = 0.10f),
                                    contentColor = colors.negative,
                                    enabled = !isDownloadingModel,
                                    onClick = {
                                        OnnxOcrRemote.deleteTier(context, tier)
                                        refreshTick++
                                    },
                                )
                            }
                            else -> {
                                val highlight = remote.pluginInstalled && tier == OnnxOcr.Tier.SMALL
                                CompactPill(
                                    text = "下载",
                                    containerColor = if (highlight) colors.accent else colors.surfaceVariant,
                                    contentColor = if (highlight) colors.onAccent else colors.onSurface,
                                    enabled = remote.pluginInstalled && !isDownloadingModel,
                                    onClick = {
                                        ConfigStore.putString(tierKey, tier.id)
                                        OnnxOcrRemote.startModelDownload(
                                            context = context,
                                            tier = tier,
                                            mirror = activeMirror,
                                            customMirrorUrl = customMirrorDraft,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                if (isDownloadingThis && dl != null) {
                    LinearProgressIndicator(
                        progress = { dl.fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = colors.accent,
                        trackColor = colors.outline.copy(alpha = 0.25f),
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }

    kit.VSpace(FpSpace.l)
}

/** 列表行右侧的紧凑胶囊操作按钮（32dp 高），适配 HyperOS 设置行密度。 */
@Composable
private fun CompactPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatMb(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
