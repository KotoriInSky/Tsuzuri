package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.kotoriinsky.tsuzuri.core.PasscodeCustomRules
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.RegexGuard
import io.github.kotoriinsky.tsuzuri.ui.fpResources
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.fpResources
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmptyState
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.CustomRuleIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 自定义口令规则页：JSON 迁移 + 单条编辑 + 可视化预览。 */
@Composable
fun PasscodeRulesScreen(spec: PrefSpec.Screen, padding: PaddingValues) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val res = fpResources()
    val scope = rememberCoroutineScope()
    val prefs = ConfigStore.observe()

    var result by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<CustomRuleIO.ImportResult?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    var deletingIndex by remember { mutableStateOf<Int?>(null) }
    var editor by remember { mutableStateOf<PasscodeCustomRules.Rule?>(null) }
    var editorVisible by remember { mutableStateOf(false) }

    val importedRes = fpStringRes(context, "passcode_rules.imported")
    val exportedRes = fpStringRes(context, "passcode_rules.exported")
    val failedText = fpString("passcode_rules.failed")
    val importFailedText = fpString("passcode_rules.import_failed")
    val exportFailedText = fpString("passcode_rules.export_failed")
    val savedText = fpString("passcode_rules.saved")
    val rules = remember(prefs) {
        PasscodeCustomRules.decode(prefs.rawString(spec.key, null))
    }

    fun writeRules(list: List<PasscodeCustomRules.Rule>): Boolean {
        val encoded = PasscodeCustomRules.encode(list)
        if (encoded == null) return false
        val outcome = if (list.isEmpty()) ConfigStore.remove(spec.key) else ConfigStore.putString(spec.key, encoded)
        return outcome != ConfigStore.WriteResult.FAILED
    }

    fun replaceAt(index: Int, rule: PasscodeCustomRules.Rule): Boolean {
        if (index !in rules.indices) return false
        return writeRules(rules.toMutableList().also { it[index] = rule })
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: error("打不开输入文件")
                CustomRuleIO.import(text)
            }.getOrElse { CustomRuleIO.ImportResult(emptyList(), 0, error = "invalid") }
            withContext(Dispatchers.Main) {
                if (!outcome.ok) result = importFailedText
                else pendingImport = outcome
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CustomRuleIO.MIME),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val snapshot = PasscodeCustomRules.decode(prefs.rawString(spec.key, null))
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(CustomRuleIO.export(snapshot).toByteArray(Charsets.UTF_8))
                } ?: error("打不开输出文件")
            }.isSuccess
            withContext(Dispatchers.Main) {
                result = if (ok) res.getString(exportedRes, snapshot.size) else exportFailedText
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        kit.VSpace(8.dp)
        result?.let {
            kit.Banner(it, if (it in setOf(failedText, importFailedText, exportFailedText)) FpEmphasis.NEGATIVE else FpEmphasis.POSITIVE)
            kit.VSpace(4.dp)
        }

        kit.Card {
            kit.ActionRow(
                title = fpString("passcode_rules.add"),
                summary = null,
                trailingText = null,
                onClick = {
                    editor = null
                    editorVisible = true
                },
            )
            kit.Divider()
            kit.ActionRow(
                title = fpString("passcode_rules.import"),
                summary = fpString("passcode_rules.import.hint"),
                trailingText = null,
                onClick = { importLauncher.launch(arrayOf(CustomRuleIO.MIME, "text/plain")) },
            )
            kit.Divider()
            kit.ActionRow(
                title = fpString("passcode_rules.export"),
                summary = fpString("passcode_rules.export.hint"),
                trailingText = null,
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                    exportLauncher.launch("tsuzuri-passcode-rules-$stamp.json")
                },
            )
            kit.Divider()
            kit.ActionRow(
                title = fpString("passcode_rules.clear"),
                summary = null,
                trailingText = null,
                onClick = { confirmingClear = true },
                enabled = rules.isNotEmpty(),
            )
        }

        kit.SectionHeader(fpString("passcode_rules.title"))
        if (rules.isEmpty()) {
            kit.Card {
                FpEmptyState(
                    titleKey = "passcode_rules.empty",
                    hintKey = "passcode_rules.empty.hint",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            kit.Card {
                rules.forEachIndexed { index, rule ->
                    if (index > 0) kit.Divider()
                    kit.CheckRow(
                        title = rule.label,
                        summary = categoryLabel(rule.category) + " · " + rule.regex,
                        checked = rule.enabled,
                        onCheckedChange = {
                            if (!replaceAt(index, rule.copy(enabled = it))) result = failedText
                        },
                    )
                    kit.ActionRow(
                        title = fpString("passcode_rules.edit"),
                        summary = rule.target,
                        trailingText = categoryLabel(rule.category),
                        navigates = true,
                        onClick = {
                            editor = rule
                            editorVisible = true
                        },
                    )
                    kit.ActionRow(
                        title = fpString("passcode_rules.delete"),
                        summary = null,
                        trailingText = null,
                        onClick = { deletingIndex = index },
                    )
                }
            }
        }
    }

    kit.ConfirmDialog(
        title = fpString("passcode_rules.import"),
        text = fpString("passcode_rules.import.confirm", rules.size, pendingImport?.rules?.size ?: 0),
        confirmLabel = fpString("passcode_rules.import.replace"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = pendingImport != null,
        onConfirm = {
            val imported = pendingImport
            pendingImport = null
            result = if (imported != null && writeRules(imported.rules)) res.getString(importedRes, imported.rules.size, imported.skipped) else failedText
        },
        onDismiss = { pendingImport = null },
    )

    kit.ConfirmDialog(
        title = fpString("passcode_rules.clear"),
        text = fpString("passcode_rules.confirm.clear"),
        confirmLabel = fpString("passcode_rules.clear"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = confirmingClear,
        onConfirm = {
            confirmingClear = false
            result = if (writeRules(emptyList())) null else failedText
        },
        onDismiss = { confirmingClear = false },
    )

    val deleteIndex = deletingIndex
    val deleteName = deleteIndex?.let { rules.getOrNull(it)?.label }
    val deleteConfirmText = if (deleteName == null) {
        null
    } else {
        fpString("passcode_rules.confirm.delete") + "：" + deleteName
    }
    kit.ConfirmDialog(
        title = fpString("passcode_rules.delete"),
        text = deleteConfirmText,
        confirmLabel = fpString("passcode_rules.delete"),
        dismissLabel = fpString("settings.cancel"),
        destructive = true,
        visible = deleteIndex != null,
        onConfirm = {
            val index = deletingIndex
            deletingIndex = null
            result = if (index != null && writeRules(rules.filterIndexed { i, _ -> i != index })) null else failedText
        },
        onDismiss = { deletingIndex = null },
    )

    if (editorVisible) {
        RuleEditorDialog(
            initial = editor,
            onDismiss = { editorVisible = false },
            onSave = { draft ->
                val existing = editor
                val list = if (existing == null) {
                    rules + draft.copy(id = draft.id.ifBlank { "manual-${System.currentTimeMillis()}" })
                } else {
                    rules.map { if (it.id == existing.id) draft.copy(id = existing.id) else it }
                }
                if (writeRules(list)) {
                    result = savedText
                    editorVisible = false
                    true
                } else {
                    result = failedText
                    false
                }
            },
        )
    }
}

@Composable
private fun RuleEditorDialog(
    initial: PasscodeCustomRules.Rule?,
    onDismiss: () -> Unit,
    onSave: (PasscodeCustomRules.Rule) -> Boolean,
) {
    val kit = LocalFpKit.current
    val key = initial?.id ?: "new"
    var label by rememberSaveable(key) { mutableStateOf(initial?.label.orEmpty()) }
    var regex by rememberSaveable(key) { mutableStateOf(initial?.regex.orEmpty()) }
    var target by rememberSaveable(key) { mutableStateOf(initial?.target.orEmpty()) }
    var platformName by rememberSaveable(key) { mutableStateOf(initial?.platformName.orEmpty()) }
    var platformPkg by rememberSaveable(key) { mutableStateOf(initial?.platformPkg.orEmpty()) }
    var accent by rememberSaveable(key) { mutableStateOf(initial?.accent.orEmpty()) }
    var coreGroup by rememberSaveable(key) { mutableStateOf(initial?.coreGroup?.toString().orEmpty()) }
    var testText by rememberSaveable(key) { mutableStateOf("") }
    var anchored by rememberSaveable(key) { mutableStateOf(initial?.anchored ?: true) }
    var enabled by rememberSaveable(key) { mutableStateOf(initial?.enabled ?: true) }
    var category by rememberSaveable(key) {
        mutableStateOf(initial?.category ?: PasscodeIsland.ContentCategory.IMAGE_TEXT)
    }
    var error by rememberSaveable(key) { mutableStateOf<String?>(null) }
    val defaultLabel = fpString("passcode_rules.default_label")
    val invalidRegexText = fpString("passcode_rules.invalid_regex")
    val riskyRegexText = fpString("passcode_rules.risky_regex")
    val invalidGroupText = fpString("passcode_rules.invalid_group")
    val failedText = fpString("passcode_rules.failed")

    val options = PasscodeIsland.ContentCategory.values().toList()
    val optionLabels = options.map(::categoryLabel)
    val selectedCategory = options.indexOf(category).coerceAtLeast(0)
    val emptyPreviewText = fpString("passcode_rules.preview.empty")
    val previewRule = PasscodeCustomRules.Rule(
        id = initial?.id.orEmpty(),
        label = label.ifBlank { defaultLabel },
        regex = regex,
        anchored = anchored,
        target = target,
        platformName = platformName.ifBlank { null },
        platformPkg = platformPkg.ifBlank { null },
        accent = accent.ifBlank { null },
        coreGroup = coreGroup.toIntOrNull()?.takeIf { it in 1..9 },
        category = category,
        enabled = enabled,
    )
    val preview = if (regex.isBlank() || target.isBlank() || testText.isBlank()) null else {
        runCatching { PasscodeCustomRules.matchFirst(testText, listOf(previewRule)) }.getOrNull()
    }

    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .imePadding()
                .background(kit.colors().surface, RoundedCornerShape(22.dp))
                .padding(horizontal = FpSpace.xl, vertical = FpSpace.l),
        ) {
            kit.Body(
                if (initial == null) fpString("passcode_rules.add") else fpString("passcode_rules.edit"),
                FpEmphasis.NORMAL,
                FpTextStyle.TITLE,
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            RuleInput(fpString("passcode_rules.field.label"), label, { label = it })
            RuleInput(fpString("passcode_rules.field.regex"), regex, { regex = it })
            RuleInput(fpString("passcode_rules.field.target"), target, { target = it })
            kit.SwitchRow(
                title = fpString("passcode_rules.field.anchored"),
                summary = fpString("passcode_rules.field.anchored.hint"),
                checked = anchored,
                onCheckedChange = { anchored = it },
            )
            kit.SwitchRow(
                title = fpString("passcode_rules.field.enabled"),
                summary = fpString("passcode_rules.field.enabled.hint"),
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
            kit.SpinnerRow(
                title = fpString("passcode_rules.field.category"),
                summary = fpString("passcode_rules.field.category.hint"),
                options = optionLabels,
                selectedIndex = selectedCategory,
                onSelect = { category = options[it] },
            )
            RuleInput(fpString("passcode_rules.field.core_group"), coreGroup, { coreGroup = it })
            RuleInput(fpString("passcode_rules.field.platform"), platformName, { platformName = it })
            RuleInput(fpString("passcode_rules.field.package"), platformPkg, { platformPkg = it })
            RuleInput(fpString("passcode_rules.field.accent"), accent, { accent = it })

            kit.SectionHeader(fpString("passcode_rules.preview"))
            kit.Body(
                categoryLabel(category) + " · " + label.ifBlank { defaultLabel },
                FpEmphasis.NORMAL,
            )
            val previewTarget = target.ifBlank { emptyPreviewText }
            kit.Body(previewTarget, FpEmphasis.MUTED, FpTextStyle.CAPTION)
            RuleInput(fpString("passcode_rules.test"), testText, { testText = it })
            val invalidRegex = regex.isNotBlank() && runCatching { Regex(regex) }.isFailure
            val riskyRegex = !invalidRegex && regex.isNotBlank() && RegexGuard.isRisky(regex)
            val previewMessage = when {
                invalidRegex -> invalidRegexText
                riskyRegex -> riskyRegexText
                regex.isBlank() || target.isBlank() -> fpString("passcode_rules.test.incomplete")
                !enabled -> fpString("passcode_rules.test.disabled")
                testText.isBlank() -> fpString("passcode_rules.test.empty")
                preview == null -> fpString("passcode_rules.test.miss")
                else -> fpString("passcode_rules.test.hit") + "：" + (preview.core ?: preview.target)
            }
            kit.Banner(previewMessage, when {
                invalidRegex || riskyRegex -> FpEmphasis.NEGATIVE
                preview != null -> FpEmphasis.POSITIVE
                else -> FpEmphasis.MUTED
            })
            if (error != null) kit.Banner(error!!, FpEmphasis.NEGATIVE)
            }
            Row(Modifier.fillMaxWidth().padding(top = FpSpace.m)) {
                kit.Button(fpString("settings.cancel"), onDismiss)
                Spacer(Modifier.width(10.dp))
                kit.Button(
                    text = fpString("passcode_rules.save"),
                    primary = true,
                    enabled = regex.isNotBlank() && target.isNotBlank(),
                    onClick = {
                        val validRegex = runCatching { Regex(regex) }.isSuccess
                        val group = coreGroup.toIntOrNull()?.takeIf { it in 1..9 }
                        if (!validRegex) {
                            error = invalidRegexText
                        } else if (RegexGuard.isRisky(regex)) {
                            error = riskyRegexText
                        } else if (coreGroup.isNotBlank() && group == null) {
                            error = invalidGroupText
                        } else {
                            val saved = onSave(previewRule.copy(coreGroup = group))
                            if (!saved) error = failedText
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RuleInput(label: String, value: String, onValueChange: (String) -> Unit) {
    val kit = LocalFpKit.current
    kit.TextInput(label = label, value = value, onValueChange = onValueChange)
}

private fun categoryLabel(category: PasscodeIsland.ContentCategory): String = when (category) {
    PasscodeIsland.ContentCategory.SHORT_VIDEO -> "短视频"
    PasscodeIsland.ContentCategory.LONG_VIDEO -> "长视频"
    PasscodeIsland.ContentCategory.IMAGE_TEXT -> "图文"
    PasscodeIsland.ContentCategory.MUSIC -> "音乐"
    PasscodeIsland.ContentCategory.ECOMMERCE -> "电商"
    PasscodeIsland.ContentCategory.EXPRESS -> "快递"
    PasscodeIsland.ContentCategory.WEB -> "网页"
}
