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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kotoriinsky.tsuzuri.ui.fpString
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

/**
 * 澎湃（MiuiX）风格实现。
 *
 * 和 [M3Kit] 的差别不只是配色：
 *  - 列表项用 `SwitchPreference` / `ArrowPreference`，它们自带澎湃那种
 *    卡片内分隔、按压反馈和摘要缩进，用 Material 组件拼不出来。
 *  - 圆角、内边距、字号都走 MiuiX 的 token，不硬编码数值。
 */
object MiuixKit : FpKit {

    @Composable
    override fun colors(): FpColors {
        val c = MiuixTheme.colorScheme
        val dark = c.surface.isDarkSurface()
        return FpColors(
            accent = c.primary,
            onAccent = c.onPrimary,
            surface = c.surfaceContainer,
            surfaceVariant = c.surfaceContainerHigh,
            onSurface = c.onSurface,
            muted = c.onSurfaceVariantSummary,
            positive = FpStatusColors.positive(dark),
            warning = FpStatusColors.warning(dark),
            negative = c.error,
            outline = c.dividerLine,
        )
    }

    // ── 2026-10-17 界面重写：总览页 / 列表行 / 状态徽标 ──────────────────────
    //
    // 这一段的共同判据：**只用 Miuix 的语义色与组件画**，不引入第三方形状/色调，
    // 这样「界面风格 = 澎湃」时整屏都是同一套观感，切到 M3 时由另一份实现接管。

    /** 徽标底色 = 语义色压暗到 13%，在浅色与深色下都能和卡片底分开。 */
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
        // HyperOS 设置首页的画法：实心色块 + 白色图形，深浅色下色块不变
        Box(
            Modifier
                .size(FpSize.colorPlate)
                .clip(RoundedCornerShape(FpSize.colorPlateRadius))
                .background(icon.color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon.glyph, contentDescription = null, tint = Color.White, modifier = Modifier.size(FpSize.colorPlateGlyph))
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
        val scheme = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
        val modifier = Modifier.fillMaxWidth().padding(horizontal = FpSpace.m)
        // 绿卡照 KSU「工作中」卡（浅 #DFFAE4 / 深 #1A3825，水印 #36D167、110dp、外偏 27/31dp）；
        // 黄卡、红卡同排版只换底色与水印（三角感叹号 / 圆圈叉，用户 2026-10-25）
        val dark = scheme.surface.isDarkSurface()
        val bg = when (tone) {
            FpEmphasis.POSITIVE -> if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
            FpEmphasis.WARNING -> if (dark) Color(0xFF3A3115) else Color(0xFFFFF3D3)
            else -> if (dark) Color(0xFF3D1F1E) else Color(0xFFFDE2DF)
        }
        top.yukonga.miuix.kmp.basic.Card(
            modifier = modifier,
            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = bg),
            onClick = onClick,
            showIndication = onClick != null,
            pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Tilt,
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
                StatusWatermark(tone, statusWatermarkColor(tone))
                // 三行纵排（KSU 把模式标签压在左下角，全宽卡上会和版本行重叠，所以排成第三行）
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    detail?.let {
                        Text(it, fontSize = 15.sp, color = scheme.onSurface, modifier = Modifier.padding(top = 1.dp))
                    }
                    tag?.let {
                        Text(
                            it,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
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
        val scheme = MiuixTheme.colorScheme
        FpImageChoiceLayout(
            header = {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    Text(title, color = scheme.onSurface, fontSize = 16.sp)
                    if (summary != null) Text(summary, color = scheme.onSurfaceVariantSummary, fontSize = 13.sp)
                }
            },
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            enabled = enabled,
            colors = FpIllustrationColors(
                accent = scheme.primary,
                line = scheme.onSurfaceVariantSummary,
                fill = scheme.onSurfaceVariantSummary.copy(alpha = 0.18f),
                surface = scheme.surface,
            ),
            cardColor = scheme.secondaryContainer,
            label = { text, selected ->
                Text(
                    text,
                    color = if (selected) scheme.primary else scheme.onSurface,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }

    @Composable
    override fun Toast(message: String) {
        // HyperOS 的 Toast：居中、深色半透明胶囊、白字，不带按钮
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xE6202020))
                    .padding(horizontal = 20.dp, vertical = 11.dp),
            )
        }
    }

    @Composable
    override fun SearchEntry(placeholder: String, onClick: () -> Unit) {
        val scheme = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
        // HyperOS 设置首页的搜索框：整条胶囊、浅灰底、放大镜 + 灰字
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FpSpace.m)
                .clip(RoundedCornerShape(50))
                .background(scheme.secondaryContainer)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = scheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(10.dp))
            Text(placeholder, color = scheme.onSurfaceVariantSummary, fontSize = 16.sp, maxLines = 1)
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
        // 与普通 Card 同一条页边（12dp），内容 16dp —— 状态卡的文字与列表行文字同一条左基准。
        Column(Modifier.fillMaxWidth().padding(horizontal = FpSpace.m)) {
            top.yukonga.miuix.kmp.basic.Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = FpSize.heroRadius,
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
                            Text(
                                title,
                                color = c.onSurface,
                                fontSize = FpText.heroTitle,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (detail != null) {
                                Text(detail, color = c.muted, fontSize = FpText.heroDetail, lineHeight = 18.sp)
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
                            // 主操作用**主色按钮**：默认 Button 是浅灰的，放在状态卡里
                            // 和旁边的文字一样弱，用户会以为它不可点（真机 2026-10-17 观感）。
                            top.yukonga.miuix.kmp.basic.Button(
                                onClick = onPrimary,
                                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text(primaryLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                            if (secondaryLabel != null && onSecondary != null) {
                                Spacer(Modifier.width(FpSpace.s))
                                TextButton(secondaryLabel, onSecondary)
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
                Text(
                    title,
                    color = if (enabled) c.onSurface else c.muted,
                    fontSize = FpText.rowTitle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                // 状态不用胶囊标签（HyperOS 设置不用）：写在摘要前面，警告色只给需要处理的状态。
                // 放在前面而不是末尾，是因为摘要一行放不下时末尾会被省略号吃掉。
                if (summary != null || chip != null) {
                    Text(
                        buildAnnotatedString {
                            if (chip != null) {
                                withStyle(SpanStyle(color = if (chip.emphasis == FpEmphasis.WARNING) c.warning else c.muted)) {
                                    append(chip.label)
                                }
                                if (summary != null) append(" · ")
                            }
                            if (summary != null) append(summary)
                        },
                        color = c.muted,
                        fontSize = FpText.rowSummary,
                        lineHeight = 18.sp,
                        // 放不下就换行，不截成省略号（用户 2026-10-21）；两行是上限，
                        // 再长说明文案该改短，而不是让行无限变高
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(FpSpace.m))
            if (onClick != null) {
                if (stateText != null) {
                    Text(stateText, color = MiuixTheme.colorScheme.onSurfaceVariantActions, fontSize = 14.sp, maxLines = 1)
                    Spacer(Modifier.width(FpSpace.s))
                }
                Icon(
                    MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.size(width = 10.dp, height = 16.dp),
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

    @Composable
    override fun Scaffold(
        title: String,
        subtitle: String?,
        onBack: (() -> Unit)?,
        actions: @Composable RowScope.() -> Unit,
        bottom: FpBottomBar?,
        content: @Composable (PaddingValues) -> Unit,
    ) {
        // 半透明栏体（不是模糊）：见 KitSupport.fpScaffoldBarBackground 的注释。
        val translucent = LocalFpBarBlur.current
        // 空标题 = 纯容器（tab 外层只要底栏）；有标题就是 HyperOS 设置的大标题：
        // 跟滚动联动折叠成居中小标题（alpha = 1 − 3f，小标题弹簧 1.0/0.3 淡入），
        // 这套逻辑 MiuiX 的 TopAppBar + MiuixScrollBehavior 已与系统一致。
        val hasTopBar = title.isNotEmpty()
        val scrollBehavior = MiuixScrollBehavior()
        top.yukonga.miuix.kmp.basic.Scaffold(
            // 折叠由正文的嵌套滚动驱动：页面里任何 verticalScroll / LazyColumn 都能带动标题
            modifier = if (hasTopBar) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
            topBar = {
                if (!hasTopBar) return@Scaffold

                TopAppBar(
                    title = title,
                    largeTitle = title,
                    subtitle = subtitle ?: "",
                    modifier = Modifier.fpScaffoldBarBackground(translucent),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (onBack != null) {
                            // 不用 IconButton：它的签名随 MiuiX 版本变过两次，
                            // 而这里要的东西（一个可点的返回图标）用 foundation 就能表达。
                            Row(
                                Modifier
                                    .clickable(onClick = onBack)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    // HyperOS 的返回键是「<」而不是 Material 的箭头
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    // 与 M3Kit 保持一致：返回键一定要有无障碍名
                                    contentDescription = fpString("nav.back"),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    },
                    actions = actions,
                )
            },
            bottomBar = {
                if (bottom != null) {
                    NavigationBar(modifier = Modifier.fpScaffoldBarBackground(translucent)) {
                        bottom.items.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = index == bottom.selectedIndex,
                                onClick = { bottom.onSelect(index) },
                                icon = item.icon,
                                label = item.label,
                            )
                        }
                    }
                }
            },
            content = content,
        )
    }

    override val cardMargin: Dp = 12.dp

    @Composable
    override fun Card(modifier: Modifier, onClick: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
        val base = modifier.fillMaxWidth().padding(horizontal = cardMargin)
        if (onClick == null) {
            top.yukonga.miuix.kmp.basic.Card(modifier = base, content = { CompositionLocalProvider(LocalFpCardContent provides true) { content() } })
        } else {
            val feedback = fpRowFeedback()
            top.yukonga.miuix.kmp.basic.Card(
                modifier = base,
                // MiuiX 的 Card 自己处理点击，拿不到我们的 Modifier 链路，
                // 所以「点一下要振动」只能在这个回调里补 —— 和 M3Kit 走的是同一个 fpRowFeedback
                onClick = { feedback(); onClick() },
                showIndication = true,
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
        // 和 M3Kit 一样在回调里补振动，而不是给控件加别的手势：
        // 两套风格都必须做到「一次切换 = 一次振动」，多一次少一次都是可感知的差异。
        val feedback = fpRowFeedback()
        SwitchPreference(
            checked = checked,
            onCheckedChange = { feedback(); onCheckedChange(it) },
            title = title,
            summary = summary,
            startAction = leading,
            enabled = enabled,
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
        // MiuiX 的 SliderPreference 不接 valueRange/steps，所以量程转换自己做：
        // 界面上给用户看的是真实值，控件内部只关心 0..1 的比例。
        val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
        val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
        val keyPoints = listOfNotNull(defaultValue, recommended?.start, recommended?.endInclusive)
            .map { ((it - range.start) / span).coerceIn(0f, 1f) }
            .distinct()
        // 无障碍：SliderPreference 内部是 0..1 比例，读屏原样念出「0.27586207」。
        // 必须 clearAndSetSemantics 整个接管：只做 mergeDescendants 时，真机无障碍树里内层滑杆
        // 仍是一个独立的 SeekBar 节点（2026-10-27 uiautomator 实测每行两个），读屏照样能聚焦到它。
        // 这里给一个节点：标题 + 界面上的 valueText + 真实量程，「调整」动作按真实值量化后
        // 走与拖动相同的写入路径。触摸不受影响（语义不参与手势分发）。
        Box(
            Modifier.clearAndSetSemantics {
                contentDescription = listOfNotNull(title, summary).joinToString("，")
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range, steps)
                if (enabled) {
                    setProgress { target ->
                        onValueChange(quantize(target.coerceIn(range), range, steps))
                        onValueChangeFinished?.invoke()
                        true
                    }
                }
            },
        ) {
        SliderPreference(
            value = fraction,
            // ⚠️ **必须自己按 `steps` 量化**：SliderPreference 不收 `steps`，
            // 直接 `start + f * span` 会写出连续浮点值。
            // 后果实测过：`trigger_width`（min 8 / max 48 / steps 4，合法刻度是 8/16/24/32/40/48）
            // 在库里存成了 `12.4561767578125` —— 既不是任何一格，也让「声明了刻度」这件事失真。
            // 更麻烦的是两套风格行为不一致：M3 走 Compose 的 `steps` 会量化，MiuiX 不会，
            // 同一份 Catalog 在两种风格下能取到的值不同。
            //
            // Compose 的语义：`steps` = 中间刻度数，所以总区间数是 `steps + 1`。
            onValueChange = { f -> onValueChange(quantize(range.start + f * span, range, steps)) },
            // MiuiX 的 SliderPreference 有同名的 `onValueChangeFinished`，所以「抬手才写配置」两套 Kit 语义一致。
            // 传 `?: {}` 而不是直接传可空值：那个参数在库里的可空性没写进签名，
            // 给一个非空 lambda 在两种写法下都成立。
            onValueChangeFinished = onValueChangeFinished ?: {},
            title = title,
            summary = summary,
            valueText = valueText,
            enabled = enabled,
            // 默认值与推荐范围两端画成轨道里的关键点，拖到附近会吸附（2026-10-22 用户：
            // 「在滑杆轨道内部快速确认」）。SliderPreference 的值是 0..1 比例，关键点也换算成比例
            showKeyPoints = keyPoints.isNotEmpty(),
            keyPoints = keyPoints,
        )
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
        CheckboxPreference(
            title = title,
            checked = checked,
            onCheckedChange = { feedback(); onCheckedChange(it) },
            summary = summary,
            // 图标走 startAction：它在 M3 那边是 ListItem 的 leadingContent，
            // 两套风格下图标都在文字的**前面**，位置一致
            startAction = leading,
            // 勾选框放**尾部**（MiuiX 的默认是 Start）。这一条是为了和 M3Kit 对齐：
            // 同一个界面在「澎湃」下勾选框在左、在「Material」下在右的话，
            // 用户切一次风格就要重新找一次控件（本项目最不该出现的差异）。
            checkboxLocation = CheckboxLocation.End,
            enabled = enabled,
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
        val feedback = fpRowFeedback()
        val scheme = MiuixTheme.colorScheme
        val value: (@Composable RowScope.() -> Unit)? = trailingText?.let { text ->
            {
                Text(
                    text,
                    color = scheme.onSurfaceVariantActions,
                    fontSize = 14.sp,
                    // 与 M3Kit 的 ActionRow 同一条规则（那边的注释写得详细）：
                    // 右值列有宽度上限 + 单行省略号。这里放的多半是包名，
                    // 不约束的话它会和标题互相挤。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = FpTrailingValueWidth),
                )
            }
        }
        // 破坏性动作用 error 色，与普通行区分（澎湃的「恢复默认」是红字）。
        val titleColor = if (destructive) {
            top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(color = scheme.error)
        } else {
            top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor()
        }
        // 箭头只由 [navigates] 决定：`ArrowPreference` 会无条件画箭头，
        // `BasicComponent` 是它的底座、少了那只箭头。原来按「有没有右值」分支，
        // 于是有值的行自带 MiuiX 箭头、无值的导航行另画一只 Material 箭头，
        // 同一列出现两种箭头，漏传 navigates 的行又一只都没有。
        if (navigates) {
            ArrowPreference(
                title = title,
                titleColor = titleColor,
                summary = summary,
                // startAction 就是 CheckboxPreference.startAction 那个位置（行首、文字之前），
                // 与 M3Kit 的 ListItem.leadingContent 对齐
                startAction = leading,
                onClick = onClick?.let { click -> { feedback(); click() } },
                enabled = enabled,
                endActions = value ?: {},
                modifier = modifier,
            )
        } else {
            BasicComponent(
                title = title,
                titleColor = titleColor,
                summary = summary,
                startAction = leading,
                endActions = value,
                onClick = onClick?.let { click -> { feedback(); click() } },
                enabled = enabled,
                modifier = modifier,
            )
        }
    }

    @Composable
    override fun InfoRow(title: String, value: String, emphasis: FpEmphasis) {
        // HyperOS 设置的「标题 …… 灰色值」：标题是正常行字号，值靠右、灰色（状态色只给需要注意的值）。
        // 上一版值紧贴标题、左对齐，长标题会被挤成两行（真机：「未检测到 Root 管理器」）。
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f, fill = false).padding(end = 16.dp),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 16.sp,
            )
            if (value.isNotEmpty()) {
                Text(
                    value,
                    color = emphasis.toMiuixColor(),
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }

    @Composable
    private fun FpEmphasis.toMiuixColor(): Color {
        val c = MiuixTheme.colorScheme
        val dark = c.surface.isDarkSurface()
        return when (this) {
            FpEmphasis.NORMAL -> c.onSurface
            FpEmphasis.MUTED -> c.onSurfaceVariantSummary
            FpEmphasis.ACCENT -> c.primary
            FpEmphasis.POSITIVE -> FpStatusColors.positive(dark)
            FpEmphasis.WARNING -> FpStatusColors.warning(dark)
            FpEmphasis.NEGATIVE -> c.error
        }
    }

    @Composable
    override fun Badge(text: String, emphasis: FpEmphasis) {
        val c = colors()
        val bg = when (emphasis) {
            FpEmphasis.NORMAL, FpEmphasis.MUTED -> c.surfaceVariant
            FpEmphasis.ACCENT -> c.accent
            FpEmphasis.POSITIVE -> c.positive
            FpEmphasis.WARNING -> c.warning
            FpEmphasis.NEGATIVE -> c.negative
        }
        val fg = if (emphasis == FpEmphasis.ACCENT) c.onAccent else Color.White
        FpBadge(bg, fg, BadgeCornerMiuix) {
            Text(text, color = fg, fontSize = 11.sp)
        }
    }

    @Composable
    override fun Body(text: String, emphasis: FpEmphasis, style: FpTextStyle) {
        Text(
            text = text,
            color = emphasis.toMiuixColor(),
            fontSize = when (style) {
                FpTextStyle.TITLE -> 18.sp
                FpTextStyle.SUBTITLE -> 16.sp
                FpTextStyle.BODY -> 15.sp
                FpTextStyle.CAPTION -> 13.sp
            },
        )
    }

    @Composable
    override fun Button(text: String, onClick: () -> Unit, primary: Boolean, enabled: Boolean) {
        if (primary) {
            top.yukonga.miuix.kmp.basic.Button(
                onClick = onClick,
                enabled = enabled,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text)
            }
        } else {
            TextButton(text = text, onClick = onClick, enabled = enabled)
        }
    }

    @Composable
    override fun Banner(
        text: String,
        emphasis: FpEmphasis,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ) {
        val accent = colors().accent
        val action = if (actionLabel != null) onAction else null
        // HyperOS 设置首页顶部那种主题色提示条：浅主题色底、主题色文字、右侧箭头，不带图标
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(accent.copy(alpha = 0.10f))
                .then(if (action != null) Modifier.clickable { action() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), color = accent, fontSize = 14.sp)
            if (action != null && actionLabel != null) {
                Spacer(Modifier.width(8.dp))
                Text(actionLabel, color = accent, fontSize = 14.sp)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(FpSize.chevron),
                )
            }
        }
    }

    @Composable
    override fun Divider() {
        // HyperOS 4 设置的卡片内**没有分割线**（2026-10-21 真机截图：设置首页、显示与亮度、
        // 声音与触感都是），行与行靠行高与留白分隔。页面照常调用 Divider()，MiuiX 下不画；
        // M3 风格保留它自己的分割线（Material 列表的惯例）。
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
        Column(Modifier.padding(top = 4.dp)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                Text(title, color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp)
                if (summary != null) {
                    Text(
                        summary,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                }
            }
            val feedback = fpRowFeedback()
            val scheme = MiuixTheme.colorScheme
            // HyperOS「声音与触感」顶部「声音 / 触感」的画法：等宽胶囊并排、留缝，
            // 选中 = 实心主题色 + 白字，未选 = 浅灰底。MiuiX 没有现成的分段单选，
            // 用 foundation 的 selectable 拼 —— 读屏角色仍然是 RadioButton。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                options.forEachIndexed { index, option ->
                    val selected = index == selectedIndex
                    val bg by androidx.compose.animation.animateColorAsState(
                        if (selected) scheme.primary else scheme.secondaryContainer,
                        label = "segment-bg",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .background(bg)
                            .selectable(
                                selected = selected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { feedback(); onSelect(index) },
                            )
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option,
                            color = if (selected) scheme.onPrimary else scheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    @Composable
    override fun DropdownRow(
        title: String,
        summary: String?,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        enabled: Boolean,
    ) {
        // 照 HyperOS 设置「急速相机 · 打开相机并拍照 ⇕」：MiuiX 的原位下拉。
        val feedback = fpRowFeedback()
        top.yukonga.miuix.kmp.preference.WindowDropdownPreference(
            title = title,
            summary = summary,
            items = options,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index -> feedback(); onSelect(index) },
            enabled = enabled,
        )
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
        // HyperOS 设置的选择行：右侧灰色当前值 + `›`（照「屏幕刷新率 默认 ›」），
        // 点开是底部弹出的单选列表。上一版用 WindowSpinnerPreference，右侧是上下箭头，
        // 与设置里的写法不一致。
        val scheme = MiuixTheme.colorScheme
        val feedback = fpRowFeedback()
        var open by remember { mutableStateOf(false) }
        ArrowPreference(
            title = title,
            summary = summary,
            enabled = enabled,
            onClick = { feedback(); open = true },
            endActions = {
                Text(
                    options.getOrNull(selectedIndex).orEmpty(),
                    color = scheme.onSurfaceVariantActions,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            },
        )
        top.yukonga.miuix.kmp.window.WindowDialog(show = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    title,
                    color = scheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                options.forEachIndexed { index, option ->
                    val selected = index == selectedIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .selectable(selected = selected, role = Role.RadioButton) {
                                feedback()
                                open = false
                                onSelect(index)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option,
                            modifier = Modifier.weight(1f),
                            color = if (selected) scheme.primary else scheme.onSurface,
                            fontSize = 16.sp,
                        )
                        if (selected) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
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
        val c = colors()
        // MiuiX 的 WindowDialog 是声明式的：show 控制显隐（不是 if-visible 那种
        // 「组合存在才渲染」），所以把 visible 直接交给它。
        top.yukonga.miuix.kmp.window.WindowDialog(
            show = visible,
            onDismissRequest = onDismiss,
        ) {
            // HyperOS 的底部确认框：标题与说明居中，下方两个等宽按钮，
            // 取消 = 浅灰底，确认 = 实心（破坏性动作用警示红，如「恢复默认」）。
            val scheme = MiuixTheme.colorScheme
            Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Text(
                    title,
                    color = c.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text,
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(color = scheme.secondaryContainer),
                    ) {
                        Text(dismissLabel, color = scheme.onSurface, fontSize = 16.sp)
                    }
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                            color = if (destructive) c.negative else c.accent,
                        ),
                    ) {
                        Text(confirmLabel, color = c.onAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
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
        top.yukonga.miuix.kmp.basic.Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
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
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    Text(status, color = tint, fontSize = 20.sp)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
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
        top.yukonga.miuix.kmp.basic.TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = label,
            useLabelAsPlaceholder = false,
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
        // 与首页 [SearchEntry] 同一个样子：整条胶囊、浅灰底、放大镜 + 灰字（用户 2026-10-22：
        // 选应用页的搜索框对齐首页）。MiuiX 的 TextField 是带边框的输入框，观感差得远，所以自己拼。
        val scheme = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = FpSpace.m)
                .clip(RoundedCornerShape(50))
                .background(scheme.secondaryContainer)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = scheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (value.isEmpty()) {
                    Text(placeholder, color = scheme.onSurfaceVariantSummary, fontSize = 16.sp, maxLines = 1)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = scheme.onSurface, fontSize = 16.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClear).padding(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = fpString("search.clear"),
                        tint = scheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

