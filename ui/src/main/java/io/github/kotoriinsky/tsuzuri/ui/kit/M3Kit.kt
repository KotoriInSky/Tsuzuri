package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.ui.fpString

/**
 * Material 3 风格实现。
 *
 * 这里是「参考实现」：所有能用 M3 标准组件表达的东西都用标准组件，
 * 不自己画控件 —— 自己画的控件在 OTA 换 M3 版本时最容易走形。
 */
object M3Kit : FpKit {

    @Composable
    override fun colors(): FpColors {
        val cs = MaterialTheme.colorScheme
        val dark = cs.surface.isDarkSurface()
        return FpColors(
            accent = cs.primary,
            onAccent = cs.onPrimary,
            surface = cs.surfaceContainer,
            surfaceVariant = cs.surfaceContainerHigh,
            onSurface = cs.onSurface,
            muted = cs.onSurfaceVariant,
            positive = FpStatusColors.positive(dark),
            warning = FpStatusColors.warning(dark),
            negative = cs.error,
            outline = cs.outlineVariant,
        )
    }

    // ── 2026-10-17 界面重写：与 MiuixKit 同一套语义、M3 的表达方式 ──────────
    //
    // 实现纪律与 MiuixKit 一致：只用 Material3 的语义色与组件画，
    // 两套实现的**布局结构相同**（图标底板 / 标题 / 摘要 / 徽标 / 开关），
    // 差别只在控件观感 —— 这样切换风格时页面不会「换了个 App」。

    private fun chipTint(colors: FpColors, emphasis: FpEmphasis): Color = when (emphasis) {
        FpEmphasis.NORMAL -> colors.onSurface
        FpEmphasis.MUTED -> colors.muted
        FpEmphasis.ACCENT -> colors.accent
        FpEmphasis.POSITIVE -> colors.positive
        FpEmphasis.WARNING -> colors.warning
        FpEmphasis.NEGATIVE -> colors.negative
    }

    @Composable
    override fun Chip(label: String, emphasis: FpEmphasis) {
        val c = colors()
        val tint = chipTint(c, emphasis)
        Box(
            Modifier
                .heightIn(min = FpSize.chipHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(tint.copy(alpha = 0.13f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = tint,
                fontSize = FpText.chip,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }

    @Composable
    override fun IconPlate(icon: FpIcon) {
        // M3：同色的色调底板（图形取语义色），与 MiuiX 的实心色块共用同一份颜色
        Box(
            Modifier
                .size(FpSize.plate)
                .clip(RoundedCornerShape(FpSize.plateRadius))
                .background(icon.color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon.glyph, contentDescription = null, tint = icon.color, modifier = Modifier.size(FpSize.plateIcon))
        }
    }

    @Composable
    override fun AppIdentity(icon: ImageBitmap?, name: String, detail: String?) =
        AppIdentityLayout(colors(), icon, name, detail)

    @Composable
    override fun PageHeader(title: String, subtitle: String?, actions: @Composable RowScope.() -> Unit) {
        val c = colors()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = FpSpace.xl, end = FpSpace.s, top = FpSpace.s, bottom = FpSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = c.onSurface, fontSize = FpText.pageTitle, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = c.muted,
                        fontSize = FpText.pageSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }

    @Composable
    override fun SectionHeader(
        title: String,
        count: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ) {
        val c = colors()
        Row(
            Modifier
                .fillMaxWidth()
                // 起始点与卡片内文字（卡片 12dp 外边 + 行内 16dp）对齐。
                .padding(start = 28.dp, end = FpSpace.l, top = FpSpace.xl, bottom = FpSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = c.muted, fontSize = FpText.section, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (count != null) {
                Text(count, color = c.muted, fontSize = FpText.caption)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(FpSpace.m))
                Text(
                    actionLabel,
                    color = c.accent,
                    fontSize = FpText.caption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    @Composable
    override fun StatusCard(
        tone: FpEmphasis,
        title: String,
        detail: String?,
        tag: String?,
        onClick: (() -> Unit)?,
    ) {
        val scheme = MaterialTheme.colorScheme
        // 三态同排版只换容器色：正常 = 次要容器 + 对勾水印；黄 = 浅黄；红 = 错误容器。水印：对勾 / 三角感叹号 / 圆圈叉（用户 2026-10-25）
        val dark = scheme.surface.luminance() < 0.5f
        val bg = when (tone) {
            FpEmphasis.POSITIVE -> scheme.secondaryContainer
            FpEmphasis.WARNING -> if (dark) Color(0xFF3A3115) else Color(0xFFFFF3D3)
            else -> scheme.errorContainer
        }
        val fg = when (tone) {
            FpEmphasis.POSITIVE -> scheme.onSecondaryContainer
            FpEmphasis.WARNING -> scheme.onSurface
            else -> scheme.onErrorContainer
        }
        androidx.compose.material3.Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FpSpace.m)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
                StatusWatermark(
                    tone,
                    if (tone == FpEmphasis.POSITIVE) scheme.primary.copy(alpha = 0.8f) else statusWatermarkColor(tone),
                )
                Column(Modifier.fillMaxWidth().padding(FpSpace.l)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = fg)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = fg) }
                    tag?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleSmall,
                            color = fg,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    override fun ImageChoiceRow(
        title: String,
        summary: String?,
        options: List<FpImageOption>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        enabled: Boolean,
    ) {
        val scheme = MaterialTheme.colorScheme
        FpImageChoiceLayout(
            header = {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                    if (summary != null) {
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            },
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            enabled = enabled,
            colors = FpIllustrationColors(
                accent = scheme.primary,
                line = scheme.onSurfaceVariant,
                fill = scheme.onSurfaceVariant.copy(alpha = 0.18f),
                surface = scheme.surface,
            ),
            cardColor = scheme.surfaceContainerHigh,
            label = { text, selected ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) scheme.primary else scheme.onSurface,
                    maxLines = 1,
                )
            },
        )
    }

    @Composable
    override fun Toast(message: String) {
        androidx.compose.material3.Snackbar { Text(message) }
    }

    @Composable
    override fun SearchEntry(placeholder: String, onClick: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FpSpace.m)
                .clip(RoundedCornerShape(50))
                .background(scheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }

    @Composable
    override fun HeroCard(
        title: String,
        detail: String?,
        emphasis: FpEmphasis,
        icon: ImageVector?,
        chips: List<FpChip>,
        primaryLabel: String?,
        onPrimary: (() -> Unit)?,
        secondaryLabel: String?,
        onSecondary: (() -> Unit)?,
    ) {
        val c = colors()
        val tint = chipTint(c, emphasis)
        Column(Modifier.fillMaxWidth().padding(horizontal = FpSpace.m)) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(FpSize.heroRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(FpSpace.l)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (icon != null) {
                                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(Modifier.width(FpSpace.m))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = c.onSurface, fontSize = FpText.heroTitle, fontWeight = FontWeight.SemiBold)
                            if (detail != null) {
                                Text(detail, color = c.muted, fontSize = FpText.heroDetail)
                            }
                        }
                    }
                    if (chips.isNotEmpty()) {
                        Spacer(Modifier.height(FpSpace.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(FpSpace.s)) {
                            chips.forEach { Chip(it.label, it.emphasis) }
                        }
                    }
                    if (primaryLabel != null && onPrimary != null) {
                        Spacer(Modifier.height(FpSpace.l))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Button(onClick = onPrimary) {
                                Text(primaryLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            if (secondaryLabel != null && onSecondary != null) {
                                Spacer(Modifier.width(FpSpace.s))
                                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun FeatureRow(
        title: String,
        summary: String?,
        icon: FpIcon?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        onClick: (() -> Unit)?,
        stateText: String?,
        chip: FpChip?,
        enabled: Boolean,
        switchEnabled: Boolean,
        onBlockedSwitch: (() -> Unit)?,
    ) {
        val c = colors()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = FpSize.rowMin)
                .clickable(enabled = enabled && onClick != null, onClick = { onClick?.invoke() })
                .padding(horizontal = FpSpace.l, vertical = FpSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconPlate(icon)
                Spacer(Modifier.width(FpSpace.m))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = if (enabled) c.onSurface else c.muted,
                        fontSize = FpText.rowTitle,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (chip != null) {
                        Spacer(Modifier.width(FpSpace.s))
                        Chip(chip.label, chip.emphasis)
                    }
                }
                if (summary != null) {
                    Text(
                        summary,
                        color = c.muted,
                        fontSize = FpText.rowSummary,
                        // 与 MiuixKit 一致：最多两行，放不下换行而不是截断
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(FpSpace.m))
            if (onClick != null) {
                if (stateText != null) {
                    Text(stateText, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1)
                    Spacer(Modifier.width(FpSpace.s))
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = c.muted,
                    modifier = Modifier.size(FpSize.chevron),
                )
            } else {
                // 置灰的开关仍接一层点击：点了说明为什么开不了、去哪里解决（用户 2026-10-27）
                Box {
                    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled && switchEnabled)
                    if (!switchEnabled && onBlockedSwitch != null) {
                        Box(Modifier.matchParentSize().clickable(onClick = onBlockedSwitch))
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Scaffold(
        title: String,
        subtitle: String?,
        onBack: (() -> Unit)?,
        actions: @Composable RowScope.() -> Unit,
        bottom: FpBottomBar?,
        content: @Composable (PaddingValues) -> Unit,
    ) {
        // 空标题 = 纯容器；有标题就用 M3 的大标题栏，随滚动折叠（与 MiuiX 同一结构，外观各自）
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        androidx.compose.material3.Scaffold(
            modifier = if (title.isNotEmpty()) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
            topBar = {
                if (title.isEmpty()) return@Scaffold
                LargeTopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Column {
                            Text(text = title, style = MaterialTheme.typography.titleLarge)
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    // 返回键必须有无障碍名：contentDescription = null 会让
                                    // TalkBack 只念「按钮」，读屏用户根本不知道点了会去哪
                                    contentDescription = fpString("nav.back"),
                                )
                            }
                        }
                    },
                    actions = actions,
                )
            },
            bottomBar = {
                if (bottom != null) {
                    NavigationBar {
                        bottom.items.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = index == bottom.selectedIndex,
                                onClick = { bottom.onSelect(index) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
            content = content,
        )
    }

    override val cardMargin: Dp = 16.dp

    @Composable
    override fun Card(modifier: Modifier, onClick: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
        val base = modifier.fillMaxWidth().padding(horizontal = cardMargin)
        val shape = MaterialTheme.shapes.large
        if (onClick == null) {
            androidx.compose.material3.Card(
                modifier = base,
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                content = { CompositionLocalProvider(LocalFpCardContent provides true) { content() } },
            )
        } else {
            androidx.compose.material3.Card(
                modifier = base.fpRowClickable { onClick() },
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                content = { CompositionLocalProvider(LocalFpCardContent provides true) { content() } },
            )
        }
    }

    @Composable
    override fun SwitchRow(
        title: String,
        summary: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        enabled: Boolean,
        leading: (@Composable () -> Unit)?,
    ) {
        val feedback = fpRowFeedback()
        val toggle: (Boolean) -> Unit = { feedback(); onCheckedChange(it) }
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
            leadingContent = leading,
            trailingContent = {
                // onCheckedChange = null 是 Material 3 的既定写法：开关自己不处理点击，
                // 整行的 toggleable 统一处理。这样点哪儿都只回调一次，
                // 语义树上也只有一个开关节点（否则读屏会把同一件事念两遍）
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            },
            // 整行可点。M3 的 ListItem 默认只有尾部的 Switch 能点，而 MiuiX 的
            // SwitchPreference 整行都能点 —— 同一个界面在两套风格下「哪里能点」不一样，
            // 是本项目最不该出现的差异（改一个设置要试两次）。
            modifier = Modifier.toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = toggle,
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    @Composable
    override fun SliderRow(
        title: String,
        summary: String?,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        valueText: String,
        onValueChange: (Float) -> Unit,
        enabled: Boolean,
        onValueChangeFinished: (() -> Unit)?,
        defaultValue: Float?,
        recommended: ClosedFloatingPointRange<Float>?,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    // 读屏念界面上的「18 %」而不是裸数值（与 MiuixKit 一致）
                    modifier = Modifier.semantics { stateDescription = valueText },
                    valueRange = range,
                    steps = steps,
                    enabled = enabled,
                    onValueChangeFinished = onValueChangeFinished,
                )
                // 关键点叠在轨道上；轨道两端各让出半个滑块（10dp）
                SliderKeyPoints(
                    range = range,
                    points = listOfNotNull(defaultValue, recommended?.start, recommended?.endInclusive).distinct(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    trackInset = 10.dp,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }

    @Composable
    override fun CheckRow(
        title: String,
        summary: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        leading: (@Composable () -> Unit)?,
        enabled: Boolean,
    ) {
        val feedback = fpRowFeedback()
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
            leadingContent = leading,
            trailingContent = {
                // 与 SwitchRow 同一个理由：`onCheckedChange = null` 让整行的 toggleable
                // 统一处理点击，语义树上只有一个复选节点（否则读屏会念两遍，点两下有两份状态）
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            },
            modifier = Modifier.toggleable(
                value = checked,
                enabled = enabled,
                // 角色必须是 Checkbox 而不是 Switch：读屏靠它念出「勾选 / 未勾选」，
                // 而多选项念成「开 / 关」会让用户以为勾一个就等于开一个功能
                role = Role.Checkbox,
                onValueChange = { feedback(); onCheckedChange(it) },
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    @Composable
    override fun ActionRow(
        title: String,
        summary: String?,
        trailingText: String?,
        onClick: (() -> Unit)?,
        enabled: Boolean,
        leading: (@Composable () -> Unit)?,
        modifier: Modifier,
        navigates: Boolean,
        destructive: Boolean,
    ) {
        val scheme = MaterialTheme.colorScheme
        ListItem(
            headlineContent = {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (destructive) scheme.error else Color.Unspecified,
                )
            },
            supportingContent = summary?.let {
                // **单行**省略号，不是两行。副标题在清单页放的是**配置键**（`com.tencent.mobileqq`
                // 这种没有空格的串），两行时会从词中间硬断（`…mobil` / `eqq`）——
                // 既难看，又让那一行比邻居高：`ReorderList` 的行高是量出来的，
                // 高度参差会让拖拽的「让位」与落位看起来在抖。
                { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            // 与 CheckRow 的 leading 是同一个位置（ListItem.leadingContent），
            // 所以「行首图标」在两种行型之间不会跳
            leadingContent = leading,
            trailingContent = if (trailingText != null || navigates) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (trailingText != null) {
                            Text(
                                trailingText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = FpTrailingValueWidth),
                            )
                        }
                        // 导航行没有右值时也要有箭头（与澎湃风格一致）。
                        if (navigates) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                null
            },            // 调用方的 modifier 放在**前面**：它带来的 semantics（例如 customActions）
            // 与后面的点击修饰符落在同一条链 = 同一个语义节点，读屏才能同时看到两者
            modifier = modifier.then(
                if (onClick != null) {
                    Modifier.fpRowClickable(enabled = enabled) { onClick() }
                } else {
                    Modifier
                },
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    @Composable
    override fun InfoRow(title: String, value: String, emphasis: FpEmphasis) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = emphasis.toM3Color(),
                modifier = Modifier.weight(1.4f),
            )
        }
    }

    @Composable
    private fun FpEmphasis.toM3Color(): Color = when (this) {
        FpEmphasis.NORMAL -> MaterialTheme.colorScheme.onSurface
        FpEmphasis.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
        FpEmphasis.ACCENT -> MaterialTheme.colorScheme.primary
        FpEmphasis.POSITIVE -> FpStatusColors.positive(MaterialTheme.colorScheme.surface.isDarkSurface())
        FpEmphasis.WARNING -> FpStatusColors.warning(MaterialTheme.colorScheme.surface.isDarkSurface())
        FpEmphasis.NEGATIVE -> MaterialTheme.colorScheme.error
    }

    @Composable
    override fun Badge(text: String, emphasis: FpEmphasis) {
        val c = colors()
        val bg = when (emphasis) {
            FpEmphasis.NORMAL -> c.surfaceVariant
            FpEmphasis.MUTED -> c.surfaceVariant
            FpEmphasis.ACCENT -> c.accent
            FpEmphasis.POSITIVE -> c.positive
            FpEmphasis.WARNING -> c.warning
            FpEmphasis.NEGATIVE -> c.negative
        }
        val fg = when (emphasis) {
            FpEmphasis.ACCENT -> c.onAccent
            else -> Color.White
        }
        FpBadge(bg, fg, BadgeCornerM3) {
            Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }

    @Composable
    override fun Body(text: String, emphasis: FpEmphasis, style: FpTextStyle) {
        Text(
            text = text,
            style = when (style) {
                FpTextStyle.TITLE -> MaterialTheme.typography.titleMedium
                FpTextStyle.SUBTITLE -> MaterialTheme.typography.titleSmall
                FpTextStyle.BODY -> MaterialTheme.typography.bodyMedium
                FpTextStyle.CAPTION -> MaterialTheme.typography.bodySmall
            },
            color = emphasis.toM3Color(),
        )
    }

    @Composable
    override fun Button(text: String, onClick: () -> Unit, primary: Boolean, enabled: Boolean) {
        if (primary) {
            androidx.compose.material3.Button(onClick = onClick, enabled = enabled) { Text(text) }
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(text) }
        }
    }

    @Composable
    override fun Banner(
        text: String,
        emphasis: FpEmphasis,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ) {
        // 统一主题色提示条：不按严重程度换色
        val bg = colors().accent
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(bg.copy(alpha = 0.14f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }

    @Composable
    override fun Divider() {
        // 与澎湃一致：左端从图标底板起，右端通到卡片边缘。
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }

    @Composable
    override fun VSpace(height: Dp) {
        Spacer(Modifier.height(height))
    }

    @Composable
    override fun SegmentedRow(
        title: String,
        summary: String?,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        enabled: Boolean,
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val feedback = fpRowFeedback()
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = index == selectedIndex,
                        onClick = { feedback(); onSelect(index) },
                        enabled = enabled,
                        // itemShape 让首段左圆角、末段右圆角、中段直角 —— 分段的「段」感
                        // 由 M3 统一给出，不自己算圆角
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size,
                        ),
                        label = {
                            // 单行省略：分段段的宽度 = 均分，长文案挤爆时截断而不是换行
                            Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
        }
    }

    @Composable
    override fun SpinnerRow(
        title: String,
        summary: String?,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        enabled: Boolean,
    ) {
        var expanded by remember { mutableStateOf(false) }
        val feedback = fpRowFeedback()
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
            trailingContent = {
                // 菜单的锚点是这段「当前值」文本：菜单从值的下方弹出，方向自然
                Box {
                    Text(
                        options.getOrElse(selectedIndex) { "" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(max = 144.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        options.forEachIndexed { index, option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    expanded = false
                                    feedback()
                                    onSelect(index)
                                },
                                enabled = enabled,
                                // 选中项前面画勾。material-icons-core 没有 Check（只有 49 个
                                // 基础图标，见 master 备忘），用 Canvas 画 —— 项目惯例
                                // （DragHandle 也是自绘）。
                                leadingIcon = if (index == selectedIndex) {
                                    {
                                        val checkColor = MaterialTheme.colorScheme.primary
                                        androidx.compose.foundation.Canvas(
                                            Modifier.size(18.dp),
                                        ) {
                                            val w = size.width
                                            val h = size.height
                                            val stroke = 2.dp.toPx()
                                            drawLine(
                                                checkColor,
                                                androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.55f),
                                                androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.78f),
                                                strokeWidth = stroke,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            )
                                            drawLine(
                                                checkColor,
                                                androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.78f),
                                                androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.25f),
                                                strokeWidth = stroke,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { expanded = true },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    @Composable
    override fun ConfirmDialog(
        title: String,
        text: String?,
        confirmLabel: String,
        dismissLabel: String,
        destructive: Boolean,
        visible: Boolean,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        if (!visible) return
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = text?.let { t -> { Text(t) } },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        confirmLabel,
                        color = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        )
    }

    @Composable
    override fun StatusHero(
        status: String,
        subtitle: String?,
        emphasis: FpEmphasis,
        icon: androidx.compose.ui.graphics.vector.ImageVector?,
    ) {
        val c = colors()
        val tint = when (emphasis) {
            FpEmphasis.POSITIVE -> c.positive
            FpEmphasis.WARNING -> c.warning
            FpEmphasis.NEGATIVE -> c.negative
            FpEmphasis.ACCENT -> c.accent
            else -> c.onSurface
        }
        androidx.compose.material3.Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = c.surface),
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.size(14.dp))
                }
                Column {
                    Text(
                        status,
                        style = MaterialTheme.typography.titleLarge,
                        color = tint,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                        )
                    }
                }
            }
        }
    }

    @Composable
    override fun TextInput(
        value: String, label: String, onValueChange: (String) -> Unit,
        modifier: Modifier, keyboardType: KeyboardType, singleLine: Boolean, isError: Boolean,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = { Text(label) },
            isError = isError,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        )
    }

    @Composable
    override fun SearchField(
        value: String,
        placeholder: String,
        onValueChange: (String) -> Unit,
        onClear: () -> Unit,
        modifier: Modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text(placeholder) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = fpString("search.clear"))
                    }
                }
            },
        )
    }
}

