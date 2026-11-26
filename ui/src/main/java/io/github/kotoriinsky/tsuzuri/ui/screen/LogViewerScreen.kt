package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kotoriinsky.tsuzuri.ui.DrillTopBarActions
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpPopupAction
import io.github.kotoriinsky.tsuzuri.ui.kit.FpPopupMenu
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.fpRowFeedback
import io.github.kotoriinsky.tsuzuri.ui.state.UiActionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志查看器（最近 200 条动作与结果）。
 *
 * 导出 / 清空收进顶栏右上角 `⋯` 原生浮层，正文卡片全屏呈现日志条目。
 */
@Composable
fun LogViewerScreen(padding: PaddingValues) {
    val kit = LocalFpKit.current
    val colors = kit.colors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = fpRowFeedback()

    var entries by remember { mutableStateOf(UiActionLog.snapshot()) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val exportLabel = fpString("log.export")
    val clearLabel = fpString("log.clear")
    val exportDoneText = fpString("log.export.done")
    val exportFailedText = fpString("log.export.failed")
    val clearedText = fpString("log.cleared")

    LaunchedEffect(Unit) {
        while (true) {
            entries = UiActionLog.snapshot()
            delay(1500)
        }
    }

    val exportLog = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(UiActionLog.export().toByteArray(Charsets.UTF_8))
                    } ?: error("打不开输出文件")
                }.isSuccess
                withContext(Dispatchers.Main) {
                    result = if (ok) exportDoneText else exportFailedText
                }
            }
        }
    }

    DrillTopBarActions {
        Box {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = exportLabel,
                tint = colors.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        feedback()
                        menuOpen = true
                    }
                    .padding(12.dp),
            )
            FpPopupMenu(
                expanded = menuOpen,
                alignEnd = true,
                actions = buildList {
                    add(FpPopupAction(exportLabel) {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                        exportLog.launch("tsuzuri-log-$stamp.txt")
                    })
                    if (entries.isNotEmpty()) {
                        add(FpPopupAction(clearLabel, destructive = true) { confirmingClear = true })
                    }
                },
                onDismiss = { menuOpen = false },
            )
        }
    }

    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm:ss", locale) }

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(FpSpace.s)

        result?.let {
            kit.Banner(it, if (it == exportFailedText) FpEmphasis.NEGATIVE else FpEmphasis.POSITIVE)
            kit.VSpace(4.dp)
        }

        if (entries.isEmpty()) {
            FpEmptyState(
                titleKey = "log.empty",
                hintKey = "log.empty.hint",
                modifier = Modifier.weight(1f),
            )
        } else {
            kit.Card(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    itemsIndexed(entries) { idx, entry ->
                        if (idx > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(0.5.dp)
                                    .background(colors.outline.copy(alpha = 0.25f)),
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = timeFormat.format(Date(entry.time)),
                                color = colors.muted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = entry.message,
                                color = colors.onSurface,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        kit.VSpace(FpSpace.l)
    }

    kit.ConfirmDialog(
        title = fpString("log.clear"),
        text = fpString("log.confirm.text"),
        confirmLabel = fpString("log.clear"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = confirmingClear,
        onConfirm = {
            confirmingClear = false
            UiActionLog.clear()
            entries = emptyList()
            result = clearedText
        },
        onDismiss = { confirmingClear = false },
    )
}
