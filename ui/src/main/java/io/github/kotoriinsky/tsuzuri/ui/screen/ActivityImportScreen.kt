package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.ActivityPresets
import io.github.kotoriinsky.tsuzuri.core.ActivityTargets
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpPopupAction
import io.github.kotoriinsky.tsuzuri.ui.kit.FpPopupMenu
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.fpClampSingleLine
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.listTakesEffect
import io.github.kotoriinsky.tsuzuri.ui.state.readAppsMode

/**
 * 自定义页面（活动型自定义项，写入与快捷清单相同的有序键）。
 *
 * - 校验走 `core/ActivityTargets.parse`，7 类拒绝原因逐条明示；
 * - 输入、实时校验、预置胶囊与主操作收在一张卡片内，与 [LinkImportScreen] 排版一致；
 * - 已有活动段仅在存在活动项时出现，点行弹出原生浮层菜单（编辑 / 移除 + 撤销）。
 */
@Composable
fun ActivityImportScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val items = FanItems.read(prefs, spec)
    val manual = readAppsMode(featureKey) == "manual"

    val activities = remember(items) {
        items.withIndex().filter { ActivityTargets.isActivity(it.value) }
            .map { it.index to it.value }
    }

    var input by rememberSaveable { mutableStateOf("") }
    var removed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var menuKey by remember { mutableStateOf<String?>(null) }

    val typed = input.trim()
    val result = remember(typed) {
        if (typed.isEmpty()) null else ActivityTargets.parse(typed)
    }
    val normalized = (result as? ActivityTargets.Result.Ok)
        ?.let { ActivityTargets.stored(it.packageName, it.activityName) }
    val joined = normalized != null && normalized in items

    val editLabel = fpString("common.edit")
    val removeLabel = fpString("activity_import.remove")

    Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
        kit.VSpace(FpSpace.s)

        if (activities.isNotEmpty() && items.size < spec.minCount) {
            kit.Banner(
                fpString("fan_items.below_min", spec.minCount - items.size),
                FpEmphasis.WARNING,
            )
            kit.VSpace(4.dp)
        } else if (!listTakesEffect(featureKey, manual) && activities.isNotEmpty()) {
            kit.Banner(fpString("fan_items.mode.auto"), FpEmphasis.WARNING)
            kit.VSpace(4.dp)
        }

        removed?.let { (key, index) ->
            kit.Banner(
                text = fpString("activity_import.removed", displayOf(key)),
                emphasis = FpEmphasis.MUTED,
                actionLabel = fpString("activity_import.undo"),
                onAction = {
                    FanItems.write(spec.key, FanItems.inserted(items, index, key))
                    removed = null
                },
            )
            kit.VSpace(4.dp)
        }

        // ── 输入与预置卡片 ──
        kit.Card {
            Column(
                Modifier.fillMaxWidth().padding(FpSpace.l),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                kit.TextInput(
                    value = input,
                    keyboardType = KeyboardType.Uri,
                    label = fpString("activity_import.input.label"),
                    isError = result is ActivityTargets.Result.Rejected,
                    onValueChange = { input = it },
                )

                when (val r = result) {
                    null -> Unit
                    is ActivityTargets.Result.Rejected -> kit.Body(
                        rejectionText(r.reason),
                        FpEmphasis.WARNING,
                        FpTextStyle.CAPTION,
                    )
                    is ActivityTargets.Result.Ok -> kit.Body(
                        if (joined) fpString("activity_import.state.joined")
                        else fpString(
                            "activity_import.preview",
                            ActivityTargets.fallbackLabel(r.activityName),
                        ),
                        if (joined) FpEmphasis.MUTED else FpEmphasis.POSITIVE,
                        FpTextStyle.CAPTION,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (preset in ActivityPresets.ALL) {
                        PresetPill(
                            label = fpString(preset.labelKey),
                            selected = typed == preset.target,
                            onClick = { input = preset.target },
                        )
                    }
                }

                kit.Button(
                    text = if (joined) fpString("activity_import.state.joined") else fpString("activity_import.add"),
                    onClick = {
                        normalized?.let {
                            FanItems.write(spec.key, FanItems.appended(items, it))
                            input = ""
                        }
                    },
                    primary = true,
                    enabled = normalized != null && !joined,
                )
            }
        }

        kit.VSpace(6.dp)
        kit.Paragraph(fpString("activity_import.example"), FpEmphasis.MUTED, FpTextStyle.CAPTION)

        // ── 已添加活动（无项时不占空卡）──
        if (activities.isNotEmpty()) {
            kit.SectionHeader(fpString("activity_import.section.current"))
            kit.Card {
                activities.forEachIndexed { idx, (_, key) ->
                    if (idx > 0) kit.Divider()
                    val target = ActivityTargets.targetOf(key)
                    val bad = ActivityTargets.check(target) != null
                    Box {
                        kit.ActionRow(
                            title = displayOf(key),
                            summary = fpClampSingleLine(target, 38),
                            trailingText = if (bad) fpString("activity_import.row.bad") else null,
                            onClick = { menuKey = key },
                        )
                        FpPopupMenu(
                            expanded = menuKey == key,
                            alignEnd = true,
                            actions = listOf(
                                FpPopupAction(editLabel) { input = target },
                                FpPopupAction(removeLabel, destructive = true) {
                                    val fullIndex = items.indexOf(key)
                                    FanItems.write(spec.key, FanItems.without(items, key))
                                    if (fullIndex >= 0) removed = key to fullIndex
                                },
                            ),
                            onDismiss = { menuKey = null },
                        )
                    }
                }
            }
        }

        kit.VSpace(FpSpace.xl)
    }
}

private fun displayOf(key: String): String =
    ActivityTargets.fallbackLabel(ActivityTargets.targetOf(key))

@Composable
private fun rejectionText(reason: ActivityTargets.Rejection): String = when (reason) {
    ActivityTargets.Rejection.EMPTY -> fpString("activity_import.err.empty")
    ActivityTargets.Rejection.NO_SEPARATOR -> fpString("activity_import.err.no_separator")
    ActivityTargets.Rejection.EMPTY_PART -> fpString("activity_import.err.empty_part")
    ActivityTargets.Rejection.BAD_PACKAGE -> fpString("activity_import.err.bad_package")
    ActivityTargets.Rejection.BAD_ACTIVITY -> fpString("activity_import.err.bad_activity")
    ActivityTargets.Rejection.EXTRA_SEPARATOR -> fpString("activity_import.err.extra_separator")
    ActivityTargets.Rejection.TOO_LONG ->
        fpString("activity_import.err.too_long", ActivityTargets.MAX_LENGTH)
}
