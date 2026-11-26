package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kotoriinsky.tsuzuri.core.LinkTargets
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
import io.github.kotoriinsky.tsuzuri.ui.kit.fpRowFeedback
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.listTakesEffect
import io.github.kotoriinsky.tsuzuri.ui.state.readAppsMode

/**
 * 自定义链接（链接型自定义项，写入与快捷清单相同的有序键）。
 *
 * - 校验走 `core/LinkTargets.parse`，6 类拒绝原因逐条明示；
 * - 输入、实时校验、常用胶囊预置与主操作收在一张卡片内，空输入时不展示噪音提示；
 * - 已有链接段仅在存在链接项时出现，点行弹出原生浮层菜单（编辑 / 移除 + 撤销）。
 */
@Composable
fun LinkImportScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    val items = FanItems.read(prefs, spec)
    val manual = readAppsMode(featureKey) == "manual"

    val links = remember(items) {
        items.withIndex().filter { LinkTargets.isLink(it.value) }.map { it.index to it.value }
    }

    var input by rememberSaveable { mutableStateOf("") }
    var removed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var menuKey by remember { mutableStateOf<String?>(null) }

    val typed = input.trim()
    val result = remember(typed) {
        if (typed.isEmpty()) null else LinkTargets.parse(typed)
    }
    val normalized = (result as? LinkTargets.Result.Ok)?.let { LinkTargets.stored(it.uri) }
    val joined = normalized != null && normalized in items

    val editLabel = fpString("common.edit")
    val removeLabel = fpString("link_import.remove")

    Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
        kit.VSpace(FpSpace.s)

        if (links.isNotEmpty() && items.size < spec.minCount) {
            kit.Banner(
                fpString("fan_items.below_min", spec.minCount - items.size),
                FpEmphasis.WARNING,
            )
            kit.VSpace(4.dp)
        } else if (!listTakesEffect(featureKey, manual) && links.isNotEmpty()) {
            kit.Banner(fpString("fan_items.mode.auto"), FpEmphasis.WARNING)
            kit.VSpace(4.dp)
        }

        removed?.let { (key, index) ->
            kit.Banner(
                text = fpString("link_import.removed", displayOf(key)),
                emphasis = FpEmphasis.MUTED,
                actionLabel = fpString("link_import.undo"),
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
                    label = fpString("link_import.input.label"),
                    isError = result is LinkTargets.Result.Rejected,
                    onValueChange = { input = it },
                )

                when (val r = result) {
                    null -> Unit
                    is LinkTargets.Result.Rejected -> kit.Body(
                        rejectionText(r.reason),
                        FpEmphasis.WARNING,
                        FpTextStyle.CAPTION,
                    )
                    is LinkTargets.Result.Ok -> kit.Body(
                        if (joined) fpString("link_import.state.joined")
                        else fpString("link_import.preview", LinkTargets.fallbackLabel(r.uri)),
                        if (joined) FpEmphasis.MUTED else FpEmphasis.POSITIVE,
                        FpTextStyle.CAPTION,
                    )
                }

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (preset in LINK_PRESETS) {
                        PresetPill(
                            label = fpString(preset.labelKey),
                            selected = typed == preset.uri,
                            onClick = { input = preset.uri },
                        )
                    }
                }

                kit.Button(
                    text = if (joined) fpString("link_import.state.joined") else fpString("link_import.add"),
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
        kit.Paragraph(fpString("link_import.policy"), FpEmphasis.MUTED, FpTextStyle.CAPTION)

        // ── 已添加链接（无项时不占空卡）──
        if (links.isNotEmpty()) {
            kit.SectionHeader(fpString("link_import.section.current"))
            kit.Card {
                links.forEachIndexed { idx, (_, key) ->
                    if (idx > 0) kit.Divider()
                    val uri = LinkTargets.uriOf(key)
                    val bad = LinkTargets.check(uri) != null
                    Box {
                        kit.ActionRow(
                            title = displayOf(key),
                            summary = fpClampSingleLine(uri, 38),
                            trailingText = if (bad) fpString("link_import.row.bad") else null,
                            onClick = { menuKey = key },
                        )
                        FpPopupMenu(
                            expanded = menuKey == key,
                            alignEnd = true,
                            actions = listOf(
                                FpPopupAction(editLabel) { input = uri },
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

/** 轻量胶囊预置芯片（自定义链接与自定义页面共用）。 */
@Composable
internal fun PresetPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFpKit.current.colors()
    val feedback = fpRowFeedback()
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.surfaceVariant)
            .clickable {
                feedback()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.accent else colors.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private fun displayOf(key: String): String = LinkTargets.fallbackLabel(LinkTargets.uriOf(key))

@Composable
private fun rejectionText(reason: LinkTargets.Rejection): String = when (reason) {
    LinkTargets.Rejection.EMPTY -> fpString("link_import.err.empty")
    LinkTargets.Rejection.NO_SCHEME -> fpString("link_import.err.no_scheme")
    LinkTargets.Rejection.BAD_SCHEME -> fpString("link_import.err.bad_scheme")
    LinkTargets.Rejection.EXPLICIT -> fpString("link_import.err.explicit")
    LinkTargets.Rejection.UNSAFE_SCHEME -> fpString("link_import.err.unsafe_scheme")
    LinkTargets.Rejection.ILLEGAL_CHARS -> fpString("link_import.err.illegal_chars")
    LinkTargets.Rejection.TOO_LONG ->
        fpString("link_import.err.too_long", LinkTargets.MAX_LENGTH)
}

private class LinkPreset(val uri: String, val labelKey: String)

private val LINK_PRESETS = listOf(
    LinkPreset("weixin://scanqrcode", "link_import.preset.scan"),
    LinkPreset("https://", "link_import.preset.web"),
)
