package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.ui.fpResources
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.icon.FpGlyphs
import io.github.kotoriinsky.tsuzuri.ui.icon.PlateColors
import io.github.kotoriinsky.tsuzuri.ui.kit.FpIcon
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpMessageHost
import io.github.kotoriinsky.tsuzuri.ui.model.AccentMode
import io.github.kotoriinsky.tsuzuri.ui.model.Appearance
import io.github.kotoriinsky.tsuzuri.ui.model.DarkMode
import io.github.kotoriinsky.tsuzuri.ui.model.Density
import io.github.kotoriinsky.tsuzuri.ui.model.FontScale
import io.github.kotoriinsky.tsuzuri.ui.model.UiStyle
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import io.github.kotoriinsky.tsuzuri.ui.state.AppearanceStore
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigBackup
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FrameworkBridge
import io.github.kotoriinsky.tsuzuri.ui.state.ReloadController
import io.github.kotoriinsky.tsuzuri.ui.state.Su
import io.github.kotoriinsky.tsuzuri.ui.state.UiActionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「恢复默认」走 kit.ConfirmDialog—— 契约补上 Dialog 之后，
 * 早期「行内两步确认」的代用品退役，与详情页/分组页同一个交互。
 */
private const val KEY_RESET_FEATURES = "features"

/** 恢复外观与行为，**不动搜索历史** —— 那是用户数据，不是设置。 */
private const val KEY_RESET_APPEARANCE = "appearance"

/** 「用 Root 重启进程」的二次确认。破坏性操作必须有这一步。 */
private const val KEY_ROOT_RESTART = "root_restart"

/**
 * 设置的二级页（用户要「分开来二级菜单，不要一个太长的菜单」）。
 *
 * [id] 是返回栈保存用的稳定键，**不能改**；改了旧的保存栈恢复时会丢掉这一页（解析不到就丢弃，不会崩）。
 */
enum class SettingsSection(
    val id: String,
    val titleI18n: String,
    val summaryI18n: String,
    val glyph: ImageVector,
    val color: Color,
) {
    /** 外观 + 界面密度/字号 + 行为开关（振动、搜索历史、自动重载）—— 都是「这个应用怎么呈现与响应」。 */
    APPEARANCE("appearance", "appearance.title", "settings.section.appearance.hint", FpGlyphs.Palette, PlateColors.Blue),
    PREVIEW("preview", "preview.title", "settings.section.preview.hint", FpGlyphs.Wallpaper, PlateColors.Teal),
    DATA("data", "settings.data.title", "settings.section.data.hint", FpGlyphs.Backup, PlateColors.Green),
    DIAGNOSTICS("advanced", "settings.advanced.title", "settings.section.advanced.hint", FpGlyphs.BugReport, PlateColors.Orange);

    companion object {
        fun of(id: String): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}

/** 设置 tab 的一级页：只放入口（色块图标 + 标题 + 一行说明 + 箭头），内容都在二级页。 */
@Composable
fun SettingsScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        // 大标题由外壳的可折叠标题栏画
        kit.VSpace(FpSpace.s)
        kit.Card {
            for (section in SettingsSection.entries) {
                kit.ActionRow(
                    title = fpString(section.titleI18n),
                    summary = fpString(section.summaryI18n),
                    trailingText = null,
                    navigates = true,
                    leading = { kit.IconPlate(FpIcon(section.glyph, section.color)) },
                    onClick = { navigate(Dest.SettingsPage(section.id)) },
                )
            }
        }
        // 「关于」单独一张卡：它不是设置项，是应用信息
        kit.VSpace(FpSpace.m)
        kit.Card {
            kit.ActionRow(
                title = fpString("about.title"),
                summary = fpString("settings.about.hint"),
                trailingText = null,
                navigates = true,
                leading = { kit.IconPlate(FpIcon(Icons.Rounded.Info, PlateColors.Gray)) },
                onClick = { navigate(Dest.About) },
            )
        }
        kit.VSpace(24.dp)
    }
}

/** 设置的二级页。 */
@Composable
fun SettingsSectionScreen(section: SettingsSection, padding: PaddingValues, navigate: (Dest) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        when (section) {
            SettingsSection.APPEARANCE -> AppearanceSection()
            SettingsSection.PREVIEW -> PreviewSection()
            SettingsSection.DATA -> DataSection()
            SettingsSection.DIAGNOSTICS -> DiagnosticsSection(navigate)
        }
        LocalFpKit.current.VSpace(24.dp)
    }
}

@Composable
private fun AppearanceSection() {
    val kit = LocalFpKit.current
    val appearance = AppearanceStore.current

    // 2026-10-25 重排（用户：「界面」这一节没有必要）：
    // 风格与颜色一张卡；栏体三项单列一节；行为保留。字号、信息密度、纯黑深色已删：
    // 字号跟随系统，深色按 Android 默认深色（用户 2026-10-25）。
    // 首个小节不挂标题：与页面大标题（外观）重复
    kit.VSpace(FpSpace.s)
    kit.Card {
        kit.SpinnerRow(
            title = fpString("appearance.style.title"),
            // 用当前选中风格自己的说明当副标题：两种风格的区别不是「哪个好看」，
            // 而是「跟随澎湃还是跟随 Google」，一句话说清比让用户切来切去试要省事
            summary = fpString(appearance.style.descI18n),
            options = UiStyle.entries.map { fpString(it.labelI18n) },
            selectedIndex = UiStyle.entries.indexOf(appearance.style),
            onSelect = { i -> AppearanceStore.update { it.copy(style = UiStyle.entries[i]) } },
        )
        kit.Divider()
        kit.SpinnerRow(
            title = fpString("appearance.dark.title"),
            summary = null,
            options = DarkMode.entries.map { fpString(it.labelI18n) },
            selectedIndex = DarkMode.entries.indexOf(appearance.dark),
            onSelect = { i -> AppearanceStore.update { it.copy(dark = DarkMode.entries[i]) } },
        )
        kit.Divider()
        kit.SpinnerRow(
            title = fpString("appearance.accent.title"),
            // 右值列已经显示当前取色，副标题不再重复一遍（真机截图上两处一模一样）。
            summary = null,
            options = AccentMode.entries.map { fpString(it.labelI18n) },
            selectedIndex = AccentMode.entries.indexOf(appearance.accent),
            onSelect = { i -> AppearanceStore.update { it.copy(accent = AccentMode.entries[i]) } },
        )
        if (appearance.accent == AccentMode.CUSTOM) {
            kit.Divider()
            CustomAccentSliders(appearance)
        }
    }

    // 栏体：只在澎湃风格下生效，M3 置灰（M3 的栏没有模糊语义；用户 2026-10-25：悬浮底栏 M3 不需要）
    kit.SectionHeader(fpString("appearance.bars.title"))
    kit.Card {
        kit.SwitchRow(
            title = fpString("appearance.blur.title"),
            summary = fpString("appearance.blur.hint"),
            checked = appearance.barBlur,
            onCheckedChange = { AppearanceStore.update { a -> a.copy(barBlur = it) } },
            enabled = appearance.style == UiStyle.MIUIX,
        )
        kit.Divider()
        kit.SwitchRow(
            title = fpString("appearance.floating.bar.title"),
            summary = fpString("appearance.floating.bar.hint"),
            checked = appearance.floatingBar,
            onCheckedChange = { AppearanceStore.update { a -> a.copy(floatingBar = it) } },
            enabled = appearance.style == UiStyle.MIUIX,
        )
        kit.Divider()
        kit.SwitchRow(
            title = fpString("appearance.glass.bar.title"),
            summary = fpString("appearance.glass.bar.hint"),
            checked = appearance.glassBars,
            onCheckedChange = { AppearanceStore.update { a -> a.copy(glassBars = it) } },
            enabled = appearance.style == UiStyle.MIUIX && appearance.floatingBar,
        )
    }

    kit.SectionHeader(fpString("settings.behavior.title"))
    kit.Card {
        kit.SwitchRow(
            title = fpString("settings.haptic"),
            summary = null,
            checked = AppSettings.hapticFeedback,
            onCheckedChange = { AppSettings.hapticFeedback = it },
        )
        kit.Divider()
        kit.SwitchRow(
            title = fpString("settings.keep.search.history"),
            summary = null,
            checked = AppSettings.keepSearchHistory,
            onCheckedChange = { AppSettings.keepSearchHistory = it },
        )
        kit.Divider()
        kit.SwitchRow(
            title = fpString("settings.auto.reload"),
            summary = null,
            checked = AppSettings.autoReload,
            onCheckedChange = { AppSettings.autoReload = it },
        )
    }
}

@Composable
private fun PreviewSection() {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    // 从「启用与权限」移到设置：它是一项界面偏好，不是权限，也不参与功能可用性判定。
    kit.VSpace(FpSpace.s)
    kit.Card {
        val wallpaper by androidx.compose.runtime.produceState(
            initialValue = io.github.kotoriinsky.tsuzuri.ui.state.PreviewWallpaper.Result.NONE,
            io.github.kotoriinsky.tsuzuri.ui.state.AppSettings.previewWallpaper,
            io.github.kotoriinsky.tsuzuri.ui.state.PreviewWallpaper.revision,
        ) {
            value = if (AppSettings.previewWallpaper) {
                io.github.kotoriinsky.tsuzuri.ui.state.PreviewWallpaper.load(context)
            } else {
                io.github.kotoriinsky.tsuzuri.ui.state.PreviewWallpaper.Result.NONE
            }
        }
        WallpaperPreviewControl(wallpaper)
    }
}

@Composable
private fun DataSection() {
    val kit = LocalFpKit.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val res = fpResources()
    val snackbar = LocalFpMessageHost.current
    val scope = rememberCoroutineScope()

    // 「要确认哪一个恢复操作」。用字符串而不是布尔：有两个独立的恢复项，
    // 而一个布尔表达不了「正在确认 A 还是 B」，也容易在切换时留下错误的高亮
    var confirming by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Map<String, Any>?>(null) }
    // 刚做完的批量改动（恢复全部默认 / 恢复备份）：文案 + 快照，给「撤销」用（交互审查 I-04）
    var undo by remember { mutableStateOf<Pair<String, ConfigStore.Checkpoint>?>(null) }
    val copiedText = fpString("settings.copied")
    val resetDoneText = fpString("settings.reset.done")

    // ── 配置备份（「恢复」同时是包名迁移的 App 侧半条链路）──
    // 文案在组合里取好：snackbar 在协程里发，fpString 那时已不在组合中。
    val exportDoneText = fpString("settings.export.done")
    val exportFailedText = fpString("settings.export.failed")
    val restoreFailedText = fpString("settings.restore.failed")
    val restoredTextRes = fpStringRes(context, "settings.restored")

    val exportConfig = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    val json = ConfigBackup.exportJson(
                        ConfigStore.snapshot(),
                        source = context.packageName + " / " + Keys.GROUP,
                        exportedAt = java.time.LocalDateTime.now().toString(),
                    )
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("打不开输出文件")
                }.isSuccess
                // 导出是用户动作：成败都进应用内日志
                UiActionLog.log(if (ok) "导出配置成功" else "导出配置失败")
                launch(Dispatchers.Main) {
                    snackbar.showSnackbar(if (ok) exportDoneText else exportFailedText)
                }
            }
        }
    }

    val restoreConfig = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val json = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("打不开输入文件")
                    ConfigBackup.parse(json)
                }
                launch(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { values ->
                            // 恢复是用户动作：每一条 putX 已各自进日志，这里记总结
                            pendingRestore = values
                        },
                        onFailure = {
                            UiActionLog.log("恢复配置失败：" + (it.message ?: it.javaClass.simpleName))
                            snackbar.showSnackbar(restoreFailedText)
                        },
                    )
                }
            }
        }
    }

    val plan = pendingRestore
    // 恢复前先说清会改到什么：覆盖几项（值不同的同名项）、新增几项；值相同的不算
    val (overwrite, added) = remember(plan) {
        if (plan == null) 0 to 0 else {
            val now = ConfigStore.snapshot()
            plan.count { (k, v) -> k in now && now[k] != v } to plan.count { (k, _) -> k !in now }
        }
    }
    kit.ConfirmDialog(
        title = fpString("settings.restore.config"),
        text = fpString("settings.restore.confirm", overwrite, added),
        confirmLabel = fpString("settings.restore.config"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = plan != null,
        onConfirm = {
            pendingRestore = null
            if (plan != null) scope.launch {
                val cp = ConfigStore.checkpoint { it in plan.keys }
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) { ConfigStore.putAll(plan) }
                when (result) {
                    // 成功的留一条可撤销的横幅：备份覆盖掉的是靠手感调出来的值，得能回去
                    ConfigStore.WriteResult.REMOTE -> undo = res.getString(restoredTextRes, plan.size) to cp
                    ConfigStore.WriteResult.LOCAL -> snackbar.showSnackbar(res.getString(fpStringRes(context, "settings.restore.local")))
                    ConfigStore.WriteResult.FAILED -> snackbar.showSnackbar(restoreFailedText)
                }
            }
        },
        onDismiss = { pendingRestore = null },
    )

    kit.VSpace(FpSpace.s)
    kit.Card {
        // 导出/恢复是文件级的（JSON，与 tools/migrate-config.py 同一格式）：
        // 复制到剪贴板的东西只能给人看，文件才能再导回来 —— 备份要可往返。
        kit.ActionRow(
            title = fpString("settings.export.config"),
            summary = fpString("settings.backup.scope"),
            trailingText = null,
            onClick = { exportConfig.launch("tsuzuri-config-backup.json") },
        )
        kit.Divider()
        kit.ActionRow(
            title = fpString("settings.restore.config"),
            summary = fpString("settings.restore.scope"),
            trailingText = null,
            onClick = { restoreConfig.launch(arrayOf("application/json")) },
        )
        kit.Divider()
        kit.ActionRow(
            title = fpString("settings.copy.config"),
            summary = null,
            trailingText = null,
            onClick = {
                clipboard.setText(AnnotatedString(ConfigStore.dump()))
                scope.launch { snackbar.showSnackbar(copiedText) }
            },
        )
    }

    // 两个恢复项**分开**做，而不是塞进一个「全部恢复」：
    // 一个是「模块不再按我的习惯跑」（要重新配开关），
    // 另一个只是「界面回到出厂观感」。混成一个按钮，用户按之前
    // 根本不知道会失去哪一样，而这两件事的代价差得很远。
    kit.VSpace(FpSpace.m)
    undo?.let { (text, cp) ->
        kit.Banner(
            text = text,
            emphasis = FpEmphasis.MUTED,
            actionLabel = fpString("common.undo"),
            onAction = {
                ConfigStore.rollback(cp)
                undo = null
            },
        )
        kit.VSpace(4.dp)
    }
    kit.Card {
        kit.ActionRow(
            title = fpString("settings.reset.all"),
            summary = null,
            trailingText = null,
            destructive = true,
            onClick = { confirming = KEY_RESET_FEATURES },
        )
        kit.Divider()
        kit.ActionRow(
            title = fpString("settings.reset.appearance"),
            summary = null,
            trailingText = null,
            destructive = true,
            onClick = { confirming = KEY_RESET_APPEARANCE },
        )
    }

    // 破坏性/批量重置一律确认框。
    kit.ConfirmDialog(
        title = fpString("settings.reset.all"),
        text = fpString("settings.reset.confirm.body"),
        confirmLabel = fpString("settings.reset.confirm.ok"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = confirming == KEY_RESET_FEATURES,
        onConfirm = {
            val scopes = Catalog.features.map { featureKeyScope(it) }
            val cp = ConfigStore.checkpoint { key -> scopes.any { it(key) } }
            for (spec in Catalog.features) resetFeature(spec)
            UiActionLog.log("恢复全部默认：${Catalog.features.size} 个功能")
            confirming = null
            undo = resetDoneText to cp
        },
        onDismiss = { confirming = null },
    )
    kit.ConfirmDialog(
        title = fpString("settings.reset.appearance"),
        text = fpString("settings.reset.appearance.confirm.body"),
        confirmLabel = fpString("settings.reset.confirm.ok"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = confirming == KEY_RESET_APPEARANCE,
        onConfirm = {
            AppearanceStore.reset()
            AppSettings.resetBehavior()
            UiActionLog.log("恢复外观与行为默认")
            confirming = null
            scope.launch { snackbar.showSnackbar(resetDoneText) }
        },
        onDismiss = { confirming = null },
    )
}

@Composable
private fun DiagnosticsSection(navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val status by FrameworkBridge.status
    // 2026-10-25 重排（用户：诊断与日志重新排布、文本重构）：只留两个入口。
    // 「打开 LSPosed」「用 Root 重启」都在模块状态页里，这里不再重复一份。
    // 模块状态的副标题直接给结论（与首页状态卡同一个判定），不用点进去才知道好坏。
    val summary = homeStatusSummary(status)
    kit.VSpace(FpSpace.s)
    kit.Card {
        kit.ActionRow(
            title = fpString("settings.setup"),
            summary = listOfNotNull(summary.title, summary.tag).joinToString(" · "),
            trailingText = null,
            navigates = true,
            onClick = { navigate(Dest.Setup) },
        )
        kit.Divider()
        kit.ActionRow(
            title = fpString("log.title"),
            summary = fpString("log.export.hint"),
            trailingText = null,
            navigates = true,
            onClick = { navigate(Dest.LogViewer) },
        )
    }
}

/**
 * 自定义主题色的 RGB 滑杆。只在选了「自定义」时出现。
 *
 * 值**直接从 AppearanceStore 派生**，不再用 `remember` 存一份本地副本 ——
 * 本地副本会和真值脱钩：外部（换成预设色、将来某处重置外观）改了 customAccentArgb，
 * 滑杆位置却停在老地方，显示的和实际生效的不是同一个颜色。
 */
@Composable
private fun CustomAccentSliders(appearance: Appearance) {
    val kit = LocalFpKit.current
    val r = (appearance.customAccentArgb shr 16 and 0xFF) / 255f
    val g = (appearance.customAccentArgb shr 8 and 0xFF) / 255f
    val b = (appearance.customAccentArgb and 0xFF) / 255f

    fun push(red: Float, green: Float, blue: Float) {
        val argb = (0xFF shl 24) or
            ((red * 255).toInt() shl 16) or
            ((green * 255).toInt() shl 8) or
            (blue * 255).toInt()
        AppearanceStore.update { it.copy(customAccentArgb = argb) }
    }

    kit.SliderRow(
        title = fpString("appearance.accent.channel.r"),
        summary = null,
        value = r,
        range = 0f..1f,
        steps = 0,
        valueText = (r * 255).toInt().toString(),
        onValueChange = { push(it, g, b) },
    )
    kit.SliderRow(
        title = fpString("appearance.accent.channel.g"),
        summary = null,
        value = g,
        range = 0f..1f,
        steps = 0,
        valueText = (g * 255).toInt().toString(),
        onValueChange = { push(r, it, b) },
    )
    kit.SliderRow(
        title = fpString("appearance.accent.channel.b"),
        summary = null,
        value = b,
        range = 0f..1f,
        steps = 0,
        valueText = (b * 255).toInt().toString(),
        onValueChange = { push(r, g, it) },
    )
}
