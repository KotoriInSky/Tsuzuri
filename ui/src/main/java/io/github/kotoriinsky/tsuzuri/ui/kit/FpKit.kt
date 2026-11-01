package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.roundToInt

/**
 * 两套风格共用的语义配色。
 *
 * 这里列的每一个字段，**两套风格都必须能真实表达**。
 * 表达不了的东西不许进这个类 —— 例如「澎湃那种卡片内 12dp 的分隔留白」，
 * Material 3 里没有对应概念，硬塞进来只会让 M3 实现变成伪造。
 */
@Immutable
data class FpColors(
    val accent: Color,
    val onAccent: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val muted: Color,
    val positive: Color,
    val warning: Color,
    val negative: Color,
    val outline: Color,
)

enum class FpEmphasis { NORMAL, MUTED, ACCENT, POSITIVE, WARNING, NEGATIVE }

/** 状态徽标的数据形态。[emphasis] 决定底色与文字色，两套风格用同一套语义映射。 */
@Immutable
data class FpChip(val label: String, val emphasis: FpEmphasis)

/**
 * 行首色块图标：白色图形 + 语义色底。
 * MiuiX 画成 HyperOS 设置那样的实心色块；M3 画成同色的色调底板 —— 图形与颜色两套共用。
 */
@Immutable
data class FpIcon(val glyph: ImageVector, val color: Color)

/** 图卡选项示意图用的颜色，由各风格的 Kit 按主题给出（示意图本身不认识主题）。 */
@Immutable
data class FpIllustrationColors(val accent: Color, val line: Color, val fill: Color, val surface: Color)

/**
 * 图卡选项的一项：标签 + 一张小示意图（在图卡画布上画，坐标系就是画布大小）。
 * 用于「看得见的形态」类参数（图标形状、生效边缘、小窗位置…）。
 */
@Immutable
data class FpImageOption(
    val label: String,
    val draw: androidx.compose.ui.graphics.drawscope.DrawScope.(FpIllustrationColors) -> Unit,
)

enum class FpTextStyle { TITLE, SUBTITLE, BODY, CAPTION }

/** 底部导航的一项。 */
@Immutable
data class FpNavItem(val label: String, val icon: ImageVector)

@Immutable
data class FpBottomBar(
    val items: List<FpNavItem>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
)

/**
 * 界面组件契约。**页面只依赖它，不依赖任何具体风格。**
 *
 * 这是「MiuiX / Material 3 可切换」能成立的全部秘密：
 * 每个页面写一次，两个 Kit 各实现一次，切换只是换一个 [LocalFpKit] 的值。
 *
 * 三条纪律：
 *  1. 只暴露两套风格都能表达的语义。MiuiX 独有的能力（模糊、squircle）不进这里。
 *  2. 组件数量保持少。加一个组件 = 两处实现 + 两处契约测试，是有成本的决定。
 *  3. Kit 里**不允许**出现业务逻辑（读配置、算状态），它只负责画。
 *
 * ⚠️ 实现这个接口时有一个必踩的坑：**成员函数会遮蔽同名 import**。
 * 比如 `M3Kit.Card()` 里写 `Card(...)` 调用的是它自己，不是 `material3.Card`，
 * 结果是无限递归 → `StackOverflowError` → 界面白屏崩溃（本项目第一次装机就是这么崩的）。
 * 所以实现里凡是要调与成员同名的 Compose 组件，一律写**全限定名**。
 */
interface FpKit {

    @Composable fun colors(): FpColors

    @Composable fun Scaffold(
        title: String,
        subtitle: String? = null,
        onBack: (() -> Unit)? = null,
        actions: @Composable RowScope.() -> Unit = {},
        bottom: FpBottomBar? = null,
        content: @Composable (PaddingValues) -> Unit,
    )

    /** 卡片容器。列表项要放进它里面才会得到各风格该有的分组留白。 */
    @Composable fun Card(
        modifier: Modifier = Modifier,
        onClick: (() -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    )

    @Composable fun SwitchRow(
        title: String,
        summary: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        enabled: Boolean = true,
        /** 行首插槽，语义同 [CheckRow] 的 `leading`（应用行画图标）。 */
        leading: (@Composable () -> Unit)? = null,
    )

    @Composable fun SliderRow(
        title: String,
        summary: String?,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        valueText: String,
        onValueChange: (Float) -> Unit,
        enabled: Boolean = true,
        /**
         * 松手那一刻（Compose 的 `Slider.onValueChangeFinished` / MiuiX 的同名参数）。
         *
         * 为什么它进了契约（2026-09-19）：拖动期间**不能写配置** ——
         * 远程配置要过 Binder，一次长滑会写几十次，而配置写入还会顺带触发自动重载。
         * 于是拖动只更新本地草稿（含预览），抬手才落盘。
         * 这个回调是「抬手」的**唯一**可靠信号；用延时去猜（debounce）会在
         * 「拖完立刻返回上一页」时把最后一次改动丢掉，而那种丢失用户看不出来。
         */
        onValueChangeFinished: (() -> Unit)? = null,
        /** 默认值与推荐范围两端，画成轨道里的关键点（MiuiX 自带 keyPoints，M3 见 [SliderKeyPoints]）。null = 不标。 */
        defaultValue: Float? = null,
        recommended: ClosedFloatingPointRange<Float>? = null,
    )

    /**
     * 多选行（应用多选清单这类「勾一批东西」的界面用它）。
     *
     * 与 [SwitchRow] 分开而不是共用一个带 `multi` 开关的实现：开关表达「这件事开不开」，
     * 勾选框表达「这一批里哪些算数」，读屏念出来的角色（`Switch` / `Checkbox`）也不一样 ——
     * 混成一个组件会让读屏把多选念成开关。
     *
     * `leading` 是**唯一一个插槽**，它支撑的是「一行里要画应用图标」这件事：
     * 两套风格都有明确的对应物（M3 `ListItem.leadingContent` / MiuiX
     * `CheckboxPreference.startAction`），所以放进契约不会让任何一种风格失真。
     * 除此之外不再开别的插槽（比如尾部的自定义控件）—— 那些两套风格表达不了同一个意思。
     */
    @Composable fun CheckRow(
        title: String,
        summary: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        leading: (@Composable () -> Unit)? = null,
        enabled: Boolean = true,
    )

    /**
     * 可点击的行；右侧的**值**可选。
     *
     * 右值与箭头是两件事：右值说「现在是什么」，箭头说「点了会进到另一层」，
     * 箭头**只由 [navigates] 决定**，两套风格含义相同。行内就地生效的动作（填入、
     * 展开、恢复默认）不画箭头。
     *
     * `leading` 与 [CheckRow] 的那个是**同一个语义槽**（M3 `ListItem.leadingContent` /
     * MiuiX `ArrowPreference.startAction`），两套风格下都画在文字之前。
     * 为什么动作行也需要它：单项图标那一页要在行首画出「这一项现在长什么样」，
     * 而那一行是**动作**（点开换图标）不是勾选 —— 用 [CheckRow] 会给出一个骗人的勾选框。
     * 两套底层组件本来就有这个能力（[CheckRow] 已经在用），所以放进契约不会让谁失真。
     *
     * `modifier` 直通给底层行组件（先于内部的点击修饰符，所以与点击落在**同一个**
     * 语义节点上）：清单页的行要靠它挂 `customActions`（读屏的「上移 / 下移 / 移除」，
     * 把视觉按钮层转成语义层）。除此之外依然不开别的插槽
     * （例如尾部自定义控件）：那才是两套风格表达不了同一个意思的东西。
     */
    @Composable fun ActionRow(
        title: String,
        summary: String?,
        trailingText: String?,
        onClick: (() -> Unit)?,
        enabled: Boolean = true,
        leading: (@Composable () -> Unit)? = null,
        modifier: Modifier = Modifier,
        /**
         * 点了会跳到另一页（或外部页面）。有没有右值都要画箭头 ——
         * 澎湃设置里「能进去」的行都有箭头，而重写前设置页六处可跳转行全是素行，
         * 和详情页的类别行（带箭头）自相矛盾（真机 2026-10-17 评审）。
         */
        navigates: Boolean = false,
        /** 破坏性动作（恢复默认 / 清空）：标题用警示色，与普通行区分。 */
        destructive: Boolean = false,
    )

    /**
     * 单行搜索输入框。
     *
     * 刻意不暴露 `leadingIcon` / `trailingIcon` 这类插槽：两套风格的输入框内部结构差得远，
     * 暴露插槽就等于把某一种风格的实现细节写进契约。需要图标时由 Kit 自己决定怎么画。
     */
    @Composable fun SearchField(
        value: String,
        placeholder: String,
        onValueChange: (String) -> Unit,
        onClear: () -> Unit,
        modifier: Modifier = Modifier,
    )

    /** 表单输入有持久标签，不附带搜索图标；键盘类型表达真实输入用途。 */
    @Composable fun TextInput(
        value: String,
        label: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
        keyboardType: KeyboardType = KeyboardType.Text,
        singleLine: Boolean = true,
        isError: Boolean = false,
    )

    /** 只读的「标签 — 值」行，用于诊断页。 */
    @Composable fun InfoRow(
        title: String,
        value: String,
        emphasis: FpEmphasis = FpEmphasis.MUTED,
    )

    @Composable fun Badge(text: String, emphasis: FpEmphasis)

    @Composable fun Body(
        text: String,
        emphasis: FpEmphasis = FpEmphasis.NORMAL,
        style: FpTextStyle = FpTextStyle.BODY,
    )

    @Composable fun Button(
        text: String,
        onClick: () -> Unit,
        primary: Boolean = false,
        enabled: Boolean = true,
    )

    /**
     * 提示条：页面标题下的一条**主题色**提示。不带图标，不按严重程度换色 ——
     * [emphasis] 保留只为不改调用点，已不影响外观。有动作时整条可点、右侧箭头。
     */
    @Composable fun Banner(
        text: String,
        emphasis: FpEmphasis = FpEmphasis.WARNING,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    )

    /**
     * 分段单选：**≤ 4 项、每项 2–4 个字**的互斥选择，一行占满。
     *
     * 与 SpinnerRow 的分工：选项是「看得见的形态」（能用预览图表达的视觉参数）走
     * 预览选择组件（页面层组合，不进契约）；选项是**短文案**且数量少（≤4）走这里 ——
     * 全部选项平铺可见，不用点开；数量多或文案长走 [SpinnerRow]。
     * （多选一组件分层：预览选择 > SegmentedRow > SpinnerRow。）
     */
    @Composable fun SegmentedRow(
        title: String,
        summary: String?,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        enabled: Boolean = true,
    )

    /**
     * 弹出式单选：选项**无法预览**（语义类别，如「跟随系统 / 强制中文 / 强制英文」）
     * 或数量多时的键值行 —— 行内显示当前值，点开弹出菜单选择。
     *
     * 相比竖排单选钮（已退役）省一整屏；与 [SegmentedRow] 相比不要求选项可见。
     */
    @Composable fun SpinnerRow(
        title: String,
        summary: String?,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        enabled: Boolean = true,
    )

    /**
     * HyperOS 设置的原位下拉：右侧当前值 + 上下箭头，点开浮层贴着这一行弹出，选中项着色带勾。
     * 只给 Catalog 里显式声明 `dropdown = true` 的 Choice 用；没有对应控件的套件退回 [SpinnerRow]。
     */
    @Composable fun DropdownRow(
        title: String,
        summary: String?,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        enabled: Boolean = true,
    ) = SpinnerRow(title, summary, options, selectedIndex, onSelect, enabled)

    /**
     * 破坏性 / 恢复默认类操作的二次确认。
     *
     * 全局规则：所有「恢复默认」点了以后必须先确认再改。`visible = false`
     * 时不渲染任何东西；`onConfirm` / `onDismiss` 触发后调用方负责把 `visible` 置回
     * false（状态归调用方，Kit 不持有）。
     *
     * `destructive = true` 时确认按钮用警示色（重置/删除），false 用强调色（普通确认）。
     */
    @Composable fun ConfirmDialog(
        title: String,
        text: String?,
        confirmLabel: String,
        dismissLabel: String,
        destructive: Boolean = false,
        visible: Boolean,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    )

    /**
     * 主页大状态卡（KernelSU 样式）：一行大字状态 + 一行说明 + 状态色。
     *
     * 它是**状态的展示位**，不是操作入口 —— 可跳转的权限项放在它下面的普通行里，
     * 各自用 [ActionRow]（带自己的跳转语义），不塞进大卡。
     */
    @Composable fun StatusHero(
        status: String,
        subtitle: String?,
        emphasis: FpEmphasis,
        icon: ImageVector? = null,
    )

    // ── 2026-10-17 界面重写新增：总览页 / 列表行 / 状态徽标 ────────────────────
    //
    // 这一组不是为了「多几个控件」，而是为了让页面**不再各拼各的**：
    // 重写前首页与模块页各写一套状态文案与行布局，同一个「已开启」在三个页面
    // 有三种画法（文字、徽标、开关），用户看不出哪个是状态、哪个能点。
    // 加进来的每个组件都对应一条重写时要固定的层级：
    //   PageHeader  → 顶层页的大标题（一屏只有一个）
    //   SectionHeader → 区块标题 + 数量（数量让用户知道这个区块有多大）
    //   HeroCard    → 页面唯一的主状态 + 唯一的主操作
    //   FeatureRow  → 「功能」列表的一行：图标 / 标题 / 摘要 / 状态徽标 / 开关
    //   Chip / IconPlate → 上面四个共用的最小视觉单元

    /** 状态徽标：短文案 + 语义色。**不承载点击**，点它不会发生任何事。 */
    @Composable fun Chip(label: String, emphasis: FpEmphasis)

    /** 行首色块图标：给列表行一个可扫读的视觉锚点。颜色由调用方按语义给，不随开关变灰。 */
    @Composable fun IconPlate(icon: FpIcon)

    /**
     * 关于页的身份头：居中大图标 + 名称 + 一行版本（HyperOS「关于本机」的排法，不放进卡片）。
     *
     * [icon] 是**本应用 APK 的启动图标**（调用方从 PackageManager 渲染），`null` = 读不到时不画图标位。
     */
    @Composable fun AppIdentity(icon: ImageBitmap?, name: String, detail: String?)

    /**
     * 顶层页的大标题 + 一行副标题 + 右侧动作。
     *
     * 只给三个顶层 tab（状态 / 功能 / 设置）用；下钻页沿用 [Scaffold] 的小标题栏 ——
     * 下钻是「聚焦做一件事」，再来一个大标题只会把内容往下挤。
     */
    @Composable fun PageHeader(
        title: String,
        subtitle: String?,
        actions: @Composable RowScope.() -> Unit = {},
    )

    /**
     * 区块标题。`count` 是「这个区块里有几项」，`actionLabel` 是区块级动作（如「全部重载」）。
     * 两者都可空；都空时就是一行小标题。
     */
    @Composable fun SectionHeader(
        title: String,
        count: String? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    )

    /**
     * 页面唯一的主状态卡：一行结论 + 一行细节 + 状态徽标 + 最多两个动作。
     *
     * 纪律：**一个页面只允许有一个 HeroCard**。两个大卡并列时，用户无法判断
     * 哪个是「现在能不能用」的结论 —— 重写前的首页正是把结论、待办、重载
     * 画成了三块同样重的卡。
     */
    /**
     * 首页状态卡，照 KernelSU `HomeMiuix.kt` / `HomeMaterial.kt` 的**两态**结构：
     * 三种状态**同一排版**，只换底色（用户 2026-10-25）：
     *  - [tone] = POSITIVE：绿卡 + 右下对勾水印（运行中）；
     *  - WARNING：黄卡（缺作用域 / 进程待重载）；
     *  - NEGATIVE：红卡（模块未激活 / 读不到框架）。
     * 右下角水印：绿 = 圆圈对勾，黄 = 三角感叹号，红 = 圆圈叉（用户 2026-10-25 改定）。
     */
    @Composable fun StatusCard(
        tone: FpEmphasis,
        title: String,
        detail: String?,
        tag: String?,
        onClick: (() -> Unit)?,
    )

    /**
     * 图卡单选：照 HyperOS「显示与亮度」顶部「浅色 / 深色模式」——并排的小图卡，选中项主题色描边、
     * 标签变主题色。≤ 3 项等分整行，更多项横向滚动。用于视觉参数；文字类选择仍走分段 / 下拉。
     */
    @Composable fun ImageChoiceRow(
        title: String,
        summary: String?,
        options: List<FpImageOption>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        enabled: Boolean = true,
    )

    /**
     * 操作结果提示（「已恢复默认」「已导出」）的外观。显隐与计时由 [FpMessageHost] 管，这里只画一条。
     * MiuiX = HyperOS 的居中深色小胶囊；M3 = Material 的 Snackbar。
     */
    @Composable fun Toast(message: String)

    /**
     * 首页大标题下的搜索入口（HyperOS 设置首页的胶囊搜索框）：**不可输入**，点一下进搜索页。
     * 真正可输入的框是 [SearchField]，在搜索页里。
     */
    @Composable fun SearchEntry(placeholder: String, onClick: () -> Unit)

    @Composable fun HeroCard(
        title: String,
        detail: String?,
        emphasis: FpEmphasis,
        icon: ImageVector?,
        chips: List<FpChip> = emptyList(),
        primaryLabel: String? = null,
        onPrimary: (() -> Unit)? = null,
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null,
    )

    /**
     * 「功能」列表的一行：左侧图标底板，中间标题 + 摘要 + 状态徽标，右侧二选一：
     *
     *  - [onClick] 非空 = 入口行：`状态 ›`，开关在下一层；
     *  - [onClick] 为空 = 开关行：行内开关，当场生效，行体不可点。
     *
     * 箭头和开关不同行，与 HyperOS 设置一致。
     * 重写前整行只有一个「已开启 ›」文字，用户必须先点进去才知道开关在哪 ——
     * 这是 LSPosed 模块里最常被抱怨的一层。
     */
    @Composable fun FeatureRow(
        title: String,
        summary: String?,
        icon: FpIcon?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        onClick: (() -> Unit)?,
        /** 入口行右侧的当前状态（「已开启」）。开关行不用。 */
        stateText: String? = null,
        chip: FpChip? = null,
        enabled: Boolean = true,
        /** 开关单独置灰（行体仍可点进详情），例如缺作用域。 */
        switchEnabled: Boolean = true,
        /** 点置灰的开关时做什么（说明原因并引导）；`null` = 点了没反应。 */
        onBlockedSwitch: (() -> Unit)? = null,
    )

    @Composable fun Divider()

    @Composable fun VSpace(height: Dp)

    /** [Card] 左右的外边距。长列表要把卡片拆进 LazyColumn 的行时用（[FpCardSegment]）。 */
    val cardMargin: Dp
}

val LocalFpKit = staticCompositionLocalOf<FpKit> {
    error("FpKit 未提供：AppTheme 之外的任何地方都不能直接用 Kit")
}

/**
 * 把滑杆的值**按声明的刻度量化**。
 *
 * ## 为什么这件事必须两套 Kit 共用一个实现
 *
 * `PrefSpec.Slider` 声明了 `min` / `max` / `steps`（`steps` = 中间刻度数，
 * 与 Compose 同义，所以总区间数是 `steps + 1`）。
 * M3 风格的 [FpKit.SliderRow] 把 `steps` 直接交给 Compose 的 `Slider`，由它量化；
 * **MiuiX 的 `SliderPreference` 不收 `steps`**，所以那边得自己量化。
 *
 * 两处各写一遍的后果实测过：MiuiX 下 `trigger_width`（min 8 / max 48 / steps 4，
 * 合法刻度只有 8/16/24/32/40/48）存成了 `12.4561767578125` —— 既不是任何一格，
 * 也让「声明了刻度」这件事失真：**同一份 Catalog 在两种风格下能取到的值不同**。
 *
 * @param steps ≤ 0 表示连续，原样返回（与 Compose 的语义一致）
 */
internal fun quantize(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    if (steps <= 0) return value
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val step = span / (steps + 1)
    val ticks = ((value - range.start) / step).roundToInt()
    return (range.start + ticks * step).coerceIn(range.start, range.endInclusive)
}
