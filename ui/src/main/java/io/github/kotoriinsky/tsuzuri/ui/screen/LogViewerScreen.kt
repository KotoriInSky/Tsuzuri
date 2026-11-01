package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.UiActionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志查看器：诊断页下架后，App 侧动作的窗口。
 *
 * 数据来自 [UiActionLog]（进程内环形缓冲，最近 200 条）：写盘、迁移、草稿同步、
 * 备份导出/恢复、失败降级 —— 都是「动作与结局」，不装界面状态。hook 侧的事
 * 归 LSPosed 模块日志（设置 → 高级那条入口），两边各管各的进程。
 *
 * ## 刷新是轻量轮询
 *
 * 日志没有可观察的句柄（它故意不接 Compose 状态：写入方大多在 IO 线程与
 * 非组合上下文里）。查看器开着的时候每 1.5s 取一次快照 —— 排障场景里
 * 「页面开着、另一只手在调开关」是真实用法，轮询让新动作自己冒出来；
 * 不开页面就零开销。
 */
@Composable
fun LogViewerScreen(padding: PaddingValues) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var entries by remember { mutableStateOf(UiActionLog.snapshot()) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }

    // 文案在组合里取好：导出回调在协程里，fpString 那时已不在组合中
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

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(8.dp)

        result?.let {
            kit.Banner(it, if (it == exportFailedText) FpEmphasis.NEGATIVE else FpEmphasis.POSITIVE)
            kit.VSpace(4.dp)
        }

        kit.Card {
            kit.ActionRow(
                title = fpString("log.export"),
                summary = fpString("log.export.hint"),
                trailingText = null,
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                    exportLog.launch("tsuzuri-log-$stamp.txt")
                },
            )
            kit.Divider()
            kit.ActionRow(
                title = fpString("log.clear"),
                summary = null,
                trailingText = null,
                onClick = { confirmingClear = true },
            )
        }

        if (entries.isEmpty()) {
            FpEmptyState(
                titleKey = "log.empty",
                hintKey = "log.empty.hint",
                modifier = Modifier.weight(1f),
            )
        } else {
            kit.Card(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        kit.Paragraph(
                            SimpleDateFormat("HH:mm:ss", LocalConfiguration.current.locales[0]).format(Date(entry.time)),
                            FpEmphasis.MUTED,
                            FpTextStyle.CAPTION,
                        )
                        kit.Paragraph(entry.message, FpEmphasis.NORMAL, FpTextStyle.CAPTION)
                        kit.Divider()
                    }
                }
            }
        }

        kit.VSpace(16.dp)
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
