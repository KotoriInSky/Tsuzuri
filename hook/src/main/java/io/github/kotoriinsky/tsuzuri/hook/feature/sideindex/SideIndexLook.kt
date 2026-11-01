package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

/**
 * 侧滑索引的**个性化参数**（外观与触感）—— 一份不可变值。
 *
 * ## 为什么单独一个类
 *
 * 这些值有三个消费者，而且必须是**同一组数字**：
 *  - [SideIndexOverlay]（画字母条 / 面板 / 图标）
 *  - [SideIndexHandle]（画边缘把手）
 *  - [AppIndex.barLayout]（算字母条的几何 —— 输入线程命中判定用的就是它）
 *
 * 如果各读各的配置，就会出现「看到的条」和「碰到的条」不是同一根 —— 这正是
 * [AppIndex.BarLayout] 的注释里反复强调要避免的那类问题。所以在这里收成一份值，
 * 由 [SideIndex.readConfig] **一处**读出来，再整体分发下去。
 *
 * ## 「不可变」指的是这一份值本身，不是「装一次管一辈子」
 *
 * 它是 `data class`：值一变就是**另一个对象**，没有任何就地修改。
 * 参数改动时 [SideIndex.watchPrefs] 会重读出一份新的，然后按消费者的性质分两路落地
 * （见 [SideIndex] 类注释里的「参数改完立刻生效」表）：
 *
 *  - [SideIndexOverlay] 那一路**整体换代**（浮层窗口每次手势新建，换代很便宜）；
 *  - [SideIndexHandle] 那一路走 `applyLook` 的**增量**更新（它是常驻窗口，不能换代）。
 *
 * 所以每个消费者拿到的、以及它内部用的，自始至终是同一份值 —— 这才是本类存在的意义。
 *
 * ## 每一项都是从哪抄来的
 *
 * **设置项以 Appindex 为准，形态以 Flyme 为准**（上一轮定下的分工）。具体到每一项：
 *
 * | 字段 | 来源 | 默认值取自 |
 * | --- | --- | --- |
 * | [letterOffsetPercent] | Appindex `letter_prefs.letter_vertical_offset`（默认 0.75） | **Flyme**：垂直居中 = 50% |
 * | [letterScalePercent] | Appindex `letter_prefs.letter_size_progress`（默认 14 → 15dp 格高/9sp 字） | **Flyme**：16dp+1 格高 / 12dp 字 = 100% |
 * | [iconSizeDp] | Appindex `app_display_prefs.icon_size_dp`（默认 45，24–80） | **Flyme**：图标 50dp |
 * | [iconRadiusDp] | Appindex `app_display_prefs.icon_radius_dp`（默认 10，0–18） | **Flyme**：不裁圆角 = 0 |
 * | [panelInsetDp] | Appindex `app_display_prefs.icon_margin_dp`（默认 24，−120–120） | **Flyme**：`mPadding = 32dp` |
 * | [handleHeightPercent] | Appindex `trigger_prefs.trigger_height_ratio`（默认 0.45） | Appindex（Flyme 没有这个组件） |
 * | [handleOffsetPercent] | Appindex `trigger_prefs.trigger_vertical_offset`（默认 0.69） | Appindex（同上） |
 * | [cellHeightDp] | 本批需求 #5（图标尺寸/间距自由定义） | **Flyme**：格子 108dp |
 * | [columns] | 本批需求 #5c（列数自定义） | **Flyme**：每行 5 格 |
 * | [cellWidthDp] | 本批需求 #5c（横向间距 = 格宽） | **Flyme**：`mAppWidth` 66dp |
 * | [hideAppNames] | 本批需求 #4（无字模式，MIUI 的含义 = 面板不显示应用名） | 关（= 现状） |
 * | [blurRadiusDp] | 本批需求 #7 档 A（模糊强度可自定义） | Appindex `dpToPx(20)` |
 * | [material] | 本批需求 #7 档 B（免截屏玻璃观感） | 现状：`blur`（= 不加任何自绘） |
 * | [bubbleAnimMs] / [bubbleIconSlots] | 参考 Niagara | Niagara：150ms / 特殊槽用图标 |
 *
 * ## 新增各项为什么也放在这里（而不是各自的消费点）
 *
 * 它们都影响 [SideIndexOverlay] 的**构造期状态**（窗口参数在 `addView` 那一刻就定了：
 * 模糊半径走 `setBlurBehindRadius`、格子几何决定面板/视口尺寸、无字模式决定格子结构、
 * 气泡动画参数决定它自己那套动画、材质档决定要不要挂那一层高光 View），
 * 所以「改了就换代浮层」是唯一正确的生效路径 ——
 * 而换代浮层的判据就是 [SideIndexConfig.windowDiffersFrom] 里的 `look != o.look`。
 * 放在这里，这一条自动成立；散到消费点就得再补一处判据，漏了就是静默不生效。
 *
 * 曾有一项 `hapticSystemTick`（Appindex 的 `shouldUsePredefinedTick`）随触觉反馈改用
 * Android 官方实现而移除 —— 它的存在前提是「自己拼 `createOneShot` 与系统 `EFFECT_TICK` 二选一」，
 * 而那条路已经整体不存在了，档位选择改由 [SideIndexHaptics] 决定。
 *
 * **默认值为什么和 Appindex 不一样**：上面写「Flyme」的那几项，两边的默认形态是不同的
 * 两套比例（例如 Flyme 的 12dp 字配 17dp 格高，Appindex 的 9sp 字配 15dp 格高）。
 * 按分工，形态冲突以 Flyme 为准 —— 所以默认值取 Flyme 那一套，
 * 而**能力**（能调位置、能整体缩放、能改图标大小/圆角/边距）照 Appindex 补上。
 * 这样既不改用户现在看到的样子，又把 Appindex 的设置面补齐了。
 *
 * ## 每个值都有「我们自己的合法区间」
 *
 * 区间写在 [Companion] 里、读取时一律 `coerceIn`。这不是不信任用户，而是防一类
 * 具体的错：**声明（core/Catalog）和消费（这里）可能不是同一个人写的**。
 * 万一声明出来的区间和这里的语义不一致（例如把 `letter_scale` 声明成 0–1 而不是 0–100），
 * 夹一下的结果是「数值不对但界面不崩」，而不是「字母条整根跑出屏幕」。
 */
internal data class SideIndexLook(
    /** 字母条纵向位置，百分比。0 = 贴顶、50 = 居中（Flyme）、100 = 贴底。 */
    val letterOffsetPercent: Float = DEFAULT_LETTER_OFFSET_PERCENT,
    /** 字母条整体大小，百分比。100 = Flyme 原尺寸。 */
    val letterScalePercent: Float = DEFAULT_LETTER_SCALE_PERCENT,
    /** 应用图标边长，dp。 */
    val iconSizeDp: Float = DEFAULT_ICON_SIZE_DP,
    /** 应用图标圆角，dp。0 = 不裁（Flyme）。 */
    val iconRadiusDp: Float = DEFAULT_ICON_RADIUS_DP,
    /** 面板内容离屏幕边缘的距离，dp（字母条那 28dp 就落在这条缝里）。 */
    val panelInsetDp: Float = DEFAULT_PANEL_INSET_DP,
    /** 边缘把手高度，占屏幕高的百分比。 */
    val handleHeightPercent: Float = DEFAULT_HANDLE_HEIGHT_PERCENT,
    /** 边缘把手纵向位置，百分比（0 = 贴顶，100 = 贴底）。 */
    val handleOffsetPercent: Float = DEFAULT_HANDLE_OFFSET_PERCENT,

    /**
     * **格子高**（dp，需求 #5「间距自由定义」）＝ 纵向间距。
     *
     * ## 为什么横向只暴露「格宽」、纵向只暴露「格高」
     *
     * 真机的格子是**相邻**的：`ASAppGroupLayout.setupViewSpecs()` 里
     * `marginStart = (i % 5) * mAppWidth`、`topMargin = (i / 5) * mAppHeight` ——
     * 格与格之间没有任何缝。所以在「图标尺寸固定」的前提下：
     *
     *  - 格子**越宽** → 图标之间越疏 → [cellWidthDp] 就是**横向间距**；
     *  - 格子**越高** → 行与行之间越疏 → 本项就是**纵向间距**。
     *
     * 再加一个「行间距」只会在面板里造出原版没有的行间缝，而它对本条需求要防的风险
     * （面板跑出屏幕）没有任何帮助 —— 于是**刻意不加**（见 [panelMetrics] 的推理）。
     *
     * ## 联动夹取（2026-09-14 需求 #5c 起，横向不再是固定值）
     *
     * 格子尺寸与**视口行数、细条长度、跟手偏移、面板左右位置**是联动的
     * 把它们拆成几个独立滑杆，用户能调出两种坏形态：
     *
     *  1. **面板横向跑出屏幕**：面板宽 = 列数 × 格宽，一旦超过屏宽，
     *     `SideIndexOverlay.panelLeft` 的 `coerceAtLeast(0)` 会把面板顶到 0，
     *     最右那一列直接落在屏幕外 —— 看不见也点不到；
     *  2. **列窄到放不下图标**：格宽比图标还窄时，相邻两格的图标会互相压住。
     *
     * **这两条现在由 [panelMetrics] 一处夹住**（列数、格宽、下限、上限一起解），
     * 规则与证明写在那个函数的 KDoc 里，并由 `core/SideIndexPreviewTest` 扫组合钉住。
     * 默认值（5 列 × 66dp）下夹取的结果与改动前逐位相同 —— 这一点也在那里证了。
     *
     * ## 纵向还有一条独立的约束（与列数无关）
     *
     * 细条固定 4dp × 184dp，而它只在「视口 ≥ 3 行」时才出现，视口 = 3 × 格高 ——
     * 所以 3 × 格高 必须 ≥ 184 + 18 = 202dp。[MIN_CELL_HEIGHT_DP] = 88dp 就是为这条留的
     * 余量（3 × 88 = 264dp）。这条约束**不受列数影响**（细条长度是常量，
     * 只是「内容行数 > 视口行数」这个条件会随列数变），所以它仍然只由 [cellHeightPx] 兜。
     */
    val cellHeightDp: Float = DEFAULT_CELL_HEIGHT_DP,

    /**
     * **每行几格**（需求 #5c）。默认 5 = Flyme 原值（`ASAppGroupLayout` 的 5 列），
     * 区间 [MIN_COLUMNS]..[MAX_COLUMNS]。
     *
     * ⚠️ **它不是照单全收的**：`列数 × 格宽` 装不下时会先把格宽压窄，
     * 压到「装得下图标」的下限还不够就**减少生效列数** —— 规则与证明在 [panelMetrics]。
     * 按哪个数排格子（视口几行、一屏几格、细条出不出来）一律看**生效列数**，
     * 否则「看到的格子」与「碰到的格子」会错位（`itemRects` 与视图必须同源）。
     */
    val columns: Int = DEFAULT_COLUMNS,

    /**
     * **格子宽**（dp，需求 #5c）= **横向间距**（图标尺寸固定时，格子越宽图标之间越疏）。
     * 默认 66dp = Flyme 原值（`mAppWidth = dpToPx(66.0f)`），区间
     * [MIN_CELL_WIDTH_DP]..[MAX_CELL_WIDTH_DP]。
     *
     * 与 [columns] 一样会被 [panelMetrics] 联动夹取：
     *  - **下限** = 图标边长 + 左右各 3dp 内边距（「列不能窄到放不下图标」，
     *    图标上限 80dp）—— 抬格宽、**不缩图标**（缩图标会让「图标尺寸」滑杆
     *    在大档位上变成「拖了没反应」，正是本项目反复要避免的那种缺陷）；
     *  - **上限** = 屏宽 ÷ 生效列数（面板不能跑到屏幕外）。
     */
    val cellWidthDp: Float = DEFAULT_CELL_WIDTH_DP,

    /**
     * **无字模式**（需求 #4）：面板里的图标**不显示应用名**。
     *
     * 产品经理已澄清：这是 MIUI「无字模式」的含义，**不是**索引条不显示字母
     * （A–Z 照常显示，那条一字不动）。
     *
     * ⚠️ 只把名字那个 `TextView` 设成 `GONE` 是**错的**：名字占「12sp 单行（约 17dp）
     * + 4dp 上间距」= [NAME_BLOCK_DP]，只藏 View 的话这块高度还在格子里，
     * 表现是「名字没了但格子还是高的」，面板看起来空一块。所以关掉时
     * ①把名字从格子布局里整个去掉、②[cellHeightPx] 里也不再计入 [NAME_BLOCK_DP]、
     * ③图标改为整格居中（否则它会贴顶，空档全落在下面）。三件事缺一不可。
     */
    val hideAppNames: Boolean = DEFAULT_HIDE_APP_NAMES,

    /**
     * **跨窗口模糊的半径**（dp，需求 #7 档 A）。
     *
     * ⚠️ **它不是高斯 σ**。取证结论：
     * SurfaceFlinger 侧 `setBlurBehindRadius(int)` **没有任何钳制**（SurfaceFlinger 与
     * SkiaRenderEngine 都原样透传，并把它当缓存键），但**视觉响应是非线性的** ——
     * 内部走 1/4 降采样（`BlurFilter.h` 的 `kInputScale = 0.25f`）、半径小于
     * `maxCrossFadeRadius = 10.0f` 时与**未模糊的底图交叉淡入**、
     * 之后再经 Kawase 4 pass（`KawaseBlurFilter.h` 的 `kMaxPasses = 4`）**饱和**。
     *
     * 因此本条只保证一件事：**默认值 20dp 与改动前逐位一致**（改动前是硬编码的
     * `BLUR_RADIUS_DP = 20`）。区间 [MIN_BLUR_RADIUS_DP]..[MAX_BLUR_RADIUS_DP] 是
     * **保守范围**、不是标定结果 —— 真实的有效区间与曲线要靠真机拍照标定
     * （待验证：半径 0/5/10/20/40/80dp 各拍一张）。
     * 据此可以预期（**待标定，不要在文档里当结论写**）：≤10dp 落在交叉淡入区，
     * 视觉强度增长被底图稀释；≥40dp 之后 4 pass 饱和，再往上推几乎不再变化。
     *
     * 只在系统开着跨窗口模糊时才有意义（[SideIndexOverlay.crossWindowBlurEnabled]），
     * 不支持时静默降级 —— 那一条与改动前完全一样。
     */
    val blurRadiusDp: Float = DEFAULT_BLUR_RADIUS_DP,

    /**
     * **气泡的按压淡入时长**（毫秒，需求 #10）。
     *
     * 取证自 Niagara：`ScrollbarHandle.java:26-38,90-102` 用一个
     * `ValueAnimator(0f → 1f)`，在 handle **从不可见变可见**时 `start()`，
     * `onAnimationUpdate` 里只做 `setAlpha(getAnimatedFraction())` —— **纯 alpha 淡入**，
     * 时长 = `integer/scrollbar_animation_time_press` = **150ms**，且**没有显式设插值器**
     * （`ValueAnimator` 的默认插值器是 `AccelerateDecelerateInterpolator`，
     * 我们这边**显式**设成它，免得依赖「默认值」这一实现细节）。
     *
     * 0 = 不做动画（淡入退化成「直接显示」，等于改动前的形态）。
     */
    val bubbleAnimMs: Float = DEFAULT_BUBBLE_ANIM_MS,

    /**
     * **特殊槽位的气泡里显示图标而不是字母**（需求 #10）。
     *
     * Niagara 的 handle 有两种内容：普通分组用字母（`canvas.drawText`）、
     * **特殊分组（收藏/最近）用 drawable**（`ScrollbarHandle.onDraw` 的
     * `xwUvHiItEbAP` 分支，按 res id 缓存）。
     *
     * 我们这边对应的是索引条最前面那个 `★` 槽（`AppIndex.RECENT_SLOT`）——
     * 它现在是个**文本字符**，正是这条机制的第一个用户。收藏/最近的数据本身
     * 留给需求 #9 那一批，这里只做**机制**：气泡能按槽位画一个 drawable 而不是文本。
     *
     * 关（默认）= 一切照旧，气泡里还是那个 `★` 字形。
     */
    val bubbleIconSlots: Boolean = DEFAULT_BUBBLE_ICON_SLOTS,

    /**
     * **背景材质档位**（需求 #7 档 B）：[MATERIAL_BLUR]（默认）= 现在的高斯模糊，
     * [MATERIAL_GLASS] = 玻璃观感。
     *
     * ## 两个档的差别只有一层自绘的高光，模糊本身一模一样
     *
     * 取证结论：`backdrop` 的两个 highlight shader
     * （`DefaultHighlightShaderString` / `AmbientHighlightShaderString`）**完全没有
     * `uniform shader`** —— 零下层像素依赖，只用 `size / cornerRadii / angle / falloff`。
     * 所以我们**不需要任何截屏能力**：面板仍然靠已有的跨窗口模糊
     * （`SideIndexOverlay.applyBlurStateToWindow`）取得「下层被模糊」的效果，
     * 这一档只是在面板上再叠一层描边高光 / 内阴影 / 色边（[SideIndexGlassPanel]）。
     *
     * ## 为什么放在 look 里
     *
     * 它是**构造期**参数：高光那一层 View 是在建面板时挂进视图树的，
     * 而 `SideIndexConfig.windowDiffersFrom` 的判据是 `look != o.look` ——
     * 放在这里，「改了档位 → 下一代浮层生效」自动成立（换代本身很便宜：
     * 浮层每次手势新建、抬手就拆，**不重扫应用清单**）。散到消费点就得再补一处判据，
     * 漏了就是静默不生效（正是本项目最难查的一类缺陷）。
     *
     * ## 关态 = 改动前
     *
     * 默认 [MATERIAL_BLUR]，此时**一层多余的自绘都不加**（连 View 都不建），
     * 与改动前逐位一致。任何取不到 shader 的情况（RuntimeShader 编译失败、
     * 画布不是硬件加速）都**静默退回** [MATERIAL_BLUR] 的观感 ——
     * 这是一个纯观感特性，绝不能因为它让浮层画不出来。
     */
    val material: String = DEFAULT_MATERIAL,
) {

    /** 玻璃档是否生效（[material] == [MATERIAL_GLASS]，未知取值按「不生效」处理）。 */
    val glassMaterial: Boolean get() = material == MATERIAL_GLASS

    /** 字母条的纵向位置比例，`[0,1]`。 */
    val letterOffsetFraction: Float
        get() = (letterOffsetPercent / 100f).coerceIn(0f, 1f)

    /** 名字块占的高度（dp）：显示名字时是 [NAME_BLOCK_DP]，无字模式下是 0。 */
    val nameBlockDp: Float
        get() = if (hideAppNames) 0f else NAME_BLOCK_DP

    /** 字母条的整体缩放。 */
    val letterScale: Float
        get() = (letterScalePercent / 100f).coerceIn(AppIndex.MIN_BAR_SCALE, AppIndex.MAX_BAR_SCALE)

    /** 图标边长（px），已经夹进 [MIN_ICON_DP]..[MAX_ICON_DP]。 */
    fun iconSizePx(density: Float): Int =
        (iconSizeDp.coerceIn(MIN_ICON_DP, MAX_ICON_DP) * density).toInt().coerceAtLeast(1)

    /** 图标圆角（px）。0 表示不裁剪。 */
    fun iconRadiusPx(density: Float): Int =
        (iconRadiusDp.coerceIn(MIN_ICON_RADIUS_DP, MAX_ICON_RADIUS_DP) * density).toInt().coerceAtLeast(0)

    /** 面板内容离屏幕边缘的距离（px）。 */
    fun panelInsetPx(density: Float): Int =
        (panelInsetDp.coerceIn(MIN_PANEL_INSET_DP, MAX_PANEL_INSET_DP) * density).toInt().coerceAtLeast(0)

    /**
     * 面板的格子几何 —— **联动夹取**（需求 #5c，本条的难点）。
     *
     * ## 规则，按优先级
     *
     * ```
     * 0. 可用宽 A = **屏宽**（刻意不是「屏宽 − panel_inset」，理由见下）；
     * 1. 下限 minCellW = 图标边长 + 2 × 格子内边距（[CELL_CONTENT_PAD_H_DP]）
     *       ← 「列不能窄到放不下图标」（图标先夹进 24–80dp，上限 80dp）
     * 2. 生效列数 = min(用户要的列数, ⌊A / minCellW⌋)      ← 「列不能跑到屏幕外」
     * 3. 格宽上限 = ⌊A / 生效列数⌋                          ← 面板总宽 ≤ A
     * 4. 生效格宽 = clamp(用户格宽, 下限, 上限)
     * ```
     *
     * 顺序是有意的：**先保列数、再压格宽、最后才减列**。列数是用户的主诉（他拖的就是
     * 「几列」），能保住就保住；压格宽压到「装得下图标」的下限还不够时才减列 ——
     * 减列是可见的降级，缩格到图标放不下是坏掉的形态，所以宁可减列。
     *
     * ⚠️ **格宽没有「只算它自己」的入口**：上一版有一个 `cellWidthPx(density)`，
     * 那正是本轮要去掉的形态 —— 格宽与列数必须来自**同一次求解**，
     * 否则两边各自都「看起来对」而组合起来跑出屏幕。
     *
     * ## 为什么 A 是屏宽而不是「屏宽 − inset」
     *
     * `SideIndexOverlay.panelLeft` 已经把 `panel_inset` 定成**尽量满足的偏好**：
     * 贴左是 `inset.coerceIn(0, 屏宽 − 内容宽)`、贴右是 `(屏宽 − inset − 内容宽).coerceAtLeast(0)` ——
     * 两种写法都表示「装不下时 inset 让位，面板必须落在屏内」。夹取若以「屏宽 − inset」
     * 为界，就变成**更强的**约束（为了让开 inset 而减列数/压格宽），表现是
     * 「拖『面板边距』这个滑杆把列数改了」—— 一个滑杆改另一个滑杆的量，
     * 是设置界面上最难理解的一类联动。
     *
     * ## 证明
     *
     * 对任意列数 `c`、格宽 `w`、任何屏宽：
     *
     *  - `内容宽 = c × w ≤ c × ⌊屏宽 / c⌋ ≤ 屏宽`（第 3 步的上限，整数除法只会更小）；
     *  - 于是**面板右缘恒 ≤ 屏宽**：贴右时 `内容宽 ≥ 屏宽 − inset` 则 `panelLeft = 0`、
     *    右缘 = `内容宽 ≤ 屏宽`；否则 `panelLeft = 屏宽 − inset − 内容宽 > 0`、
     *    右缘 = `屏宽 − inset ≤ 屏宽`。贴左时 `panelLeft ≤ 屏宽 − 内容宽`、
     *    右缘 = `panelLeft + 内容宽 ≤ 屏宽`。**不会有列落在屏幕外**；
     *  - 第 2 步保证 `c × minCellW ≤ 屏宽`，结合第 4 步的 `w ≥ minCellW`（只要屏宽 ≥ minCellW），
     *    于是**每一列都装得下最大 80dp 的图标**。
     *
     * 单测在 `core` 侧：`core/src/test/.../SideIndexPreviewTest.kt` 把
     * 列数 3–7 × 格宽 40–100dp × 图标 24–80dp × 5 种屏宽 × 两侧的组合扫一遍
     * （1400+ 组），断言每一格都在屏内、且列宽 ≥ 图标 —— 预览的 `panelMetrics` 与本函数
     * 逐行对应，所以那一组断言钉的就是这里。
     *
     * ## 唯一一处让步，如实说明
     *
     * 屏宽 < minCellW（连「一个图标 + 内边距」都比屏幕宽，正常机型到不了）时，
     * 第 4 步的下限**让位**：格宽回到 `[1px, 上限]`，即保留用户要的格宽而不是抬到满宽 ——
     * **「不出屏」优先于「装得下图标」**（出屏的列点不到，溢出的图标只是难看；
     * 真机 `LinearLayout` 居中一个过宽的 child 本来也会溢出，见 `SideIndexOverlay.createCell`）。
     *
     * @param screenW 屏幕宽（px），真机取 `displayMetrics.widthPixels`
     * @param maxPanelRows 视口最多几行（真机 [SideIndexOverlay] 的 `PANEL_MAX_ROWS` = 3），
     *   只用来给格高算上限（见 [cellHeightPx]）
     */
    fun panelMetrics(
        density: Float,
        screenW: Int,
        screenH: Int,
        bottomSpacePx: Int,
        maxPanelRows: Int,
    ): PanelMetrics {
        val cellH = cellHeightPx(
            density = density,
            maxCellHeightPx = (screenH - bottomSpacePx).coerceAtLeast(1) / maxPanelRows.coerceAtLeast(1),
        )

        // 1. 下限：列必须装得下图标（图标先按真机区间夹一次，与 createCell 用的是同一个值）
        val padH = (CELL_CONTENT_PAD_H_DP * density).toInt()
        val iconPx = iconSizePx(density)
        val minCellW = (iconPx + 2 * padH).coerceAtLeast(1)

        // 0. 可用宽 = 屏宽（见上面的理由）
        val avail = screenW.coerceAtLeast(1)

        // 2. 生效列数
        val requested = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val maxCols = (avail / minCellW).coerceAtLeast(1)
        val cols = minOf(requested, maxCols).coerceAtLeast(1)

        // 3/4. 生效格宽。屏宽连一列图标都放不下时下限让位给「不出屏」：此时保用户要的格宽，
        //      而不是把它抬到满宽（抬到满宽是另一种「拖了没反应」）。
        val maxCellW = (avail / cols).coerceAtLeast(1)
        val lo = if (minCellW <= maxCellW) minCellW else 1
        val rawCellW = (cellWidthDp.coerceIn(MIN_CELL_WIDTH_DP, MAX_CELL_WIDTH_DP) * density)
            .toInt()
            .coerceAtLeast(1)
        val cellW = rawCellW.coerceIn(lo, maxCellW)

        // 内容块高（图标 + 名字块；无字模式下只有图标）——格子的居中余量与抬升量都要它。
        // 用 NAME_BLOCK_DP 这个**模型值**（= 17dp 名字行 + 4dp 上间距）；真机布局用的是字体
        // 量出来的行高，两者差个位 dp，由格子上的 `clipChildren = false`（照 Flyme）吸收。
        val contentH = iconPx + if (hideAppNames) 0 else (NAME_BLOCK_DP * density).toInt()

        return PanelMetrics(
            requestedColumns = requested,
            columns = cols,
            cellW = cellW,
            cellH = cellH,
            iconSize = iconPx,
            contentH = contentH,
            // 抬升能用的余量：格子居中的上侧那一份，再扣掉 [CONTENT_FIT_SAFETY_DP]
            // （吸收「名字行高模型」与真实 font padding 行高的差，见 contentH 的 KDoc）
            contentSlackPx = ((cellH - contentH - (CONTENT_FIT_SAFETY_DP * density).toInt()) / 2)
                .coerceAtLeast(0),
        )
    }

    /**
     * 格子高（px）。**联动夹取**，不是照单全收：
     *
     * ```
     * 1. 用户值夹进 [MIN_CELL_HEIGHT_DP]..[MAX_CELL_HEIGHT_DP]；
     * 2. 无字模式再减掉名字块（[nameBlockDp]）—— 名字不占位了，格子要跟着矮；
     * 3. 下限：≥ 图标边长 + 名字块。这条正是「格子高 108dp 是图标 80dp 上限的来源」
     *    （`SideIndexOverlay.createCell` 的注释：80 + 名字 17 + 间距 4 = 101 ≤ 108）。
     *    格子比内容还矮时**抬格子而不是缩图标** —— 缩图标会让「图标尺寸」滑杆
     *    在大档位上变成「拖了没反应」，那正是本项目反复要避免的那种缺陷；
     * 4. 上限：≤ 传入的 [maxCellHeightPx]（= 可用屏高 ÷ 视口最大行数，由调用方算）。
     *    这条兜的是「面板比屏幕还高」：视口 = 行数 × 格高，一旦超过屏高，
     *    `followerTop` 的 `maxTop` 会被夹成 0，面板下半截永远在屏幕外。
     * ```
     *
     * @param maxCellHeightPx 调用方按屏幕算出的上限。传 `Int.MAX_VALUE` = 不设上限
     *   （屏幕尺寸未知时用；真机上不会走到，[SideIndexOverlay] 与预览都会传入真实值）。
     */
    fun cellHeightPx(density: Float, maxCellHeightPx: Int = Int.MAX_VALUE): Int {
        val iconPx = iconSizePx(density)
        val raw = (cellHeightDp.coerceIn(MIN_CELL_HEIGHT_DP, MAX_CELL_HEIGHT_DP) * density)
            .toInt()
            .coerceAtLeast(1)
        // 下限 = 图标 + **名字当前占的那块**（无字模式下名字不占位，所以是 0）
        val floor = (iconPx + nameBlockDp * density).toInt().coerceAtLeast(1)
        val ceiling = maxCellHeightPx.coerceAtLeast(1)
        // ⚠️ 只有**无字模式**才从用户要的格高里减掉名字块：108dp 这个默认值本身
        //    就已经把名字算在里面了（Flyme 的 108 = 图标 50 + 名字 21 + 余量 37），
        //    显示名字时再减一次会把默认格高变成 87dp —— 那就违反「不动它 = 现在完全一样」。
        val shrunk = if (hideAppNames) (raw - NAME_BLOCK_DP * density).toInt() else raw
        // 顺序有意义：先减名字块（用户意图），再抬到下限（内容装得下），最后压到上限（装进屏幕）。
        // 下限已经超过上限时（极端小屏 + 大图标）以**上限**为准 —— 宁可图标压到名字，
        // 也不能让面板一半在屏幕外（前者难看，后者点不到）。
        return shrunk.coerceAtLeast(floor).coerceIn(1, ceiling)
    }

    /** 气泡按压淡入的时长（毫秒）。夹进合法区间；0 = 不做动画。 */
    fun bubbleAnimDurationMs(): Long =
        bubbleAnimMs.coerceIn(MIN_BUBBLE_ANIM_MS, MAX_BUBBLE_ANIM_MS).toLong()

    /** 跨窗口模糊的半径（px）。夹进合法区间。 */
    fun blurRadiusPx(density: Float): Int =
        (blurRadiusDp.coerceIn(MIN_BLUR_RADIUS_DP, MAX_BLUR_RADIUS_DP) * density).toInt().coerceAtLeast(0)

    /** 一句话描述，进安装报告，便于在设备上肉眼核对「设置有没有生效」。 */
    fun describe(): String =
        "letter=${letterOffsetPercent.toInt()}%/${letterScalePercent.toInt()}%" +
            " icon=${iconSizeDp.toInt()}dp/r${iconRadiusDp.toInt()}dp" +
            " panel=${panelInsetDp.toInt()}dp" +
            " handle=${handleHeightPercent.toInt()}%/${handleOffsetPercent.toInt()}%" +
            // 本批新增的几项必须也打出来，否则「界面上有滑杆、报告里看不见」
            // 会让真机核对变成猜（与 §11 那条「缺项必须看得见」同一条纪律）。
            // 格子与列数打**两个维度**：格宽（横向间距）× 格高（纵向间距），缺一个就核不出形态。
            " cell=${cellWidthDp.toInt()}x${cellHeightDp.toInt()}dp cols=$columns" +
            "/nolabel=$hideAppNames" +
            " blur=${blurRadiusDp.toInt()}dp" +
            " bubble=${bubbleAnimMs.toInt()}ms/iconslot=$bubbleIconSlots" +
            " material=$material"

    companion object {
        // ⚠️ 下面这些默认值必须与 core/Catalog 里 `side_index.*` 的 `default` 逐字一致。
        //    两处不一致的后果是「用户没改过设置，行为却和界面显示的默认值不同」。
        const val DEFAULT_LETTER_OFFSET_PERCENT = 50f
        const val DEFAULT_LETTER_SCALE_PERCENT = 100f
        const val DEFAULT_ICON_SIZE_DP = 50f
        const val DEFAULT_ICON_RADIUS_DP = 0f
        const val DEFAULT_PANEL_INSET_DP = 32f
        const val DEFAULT_HANDLE_HEIGHT_PERCENT = 45f
        const val DEFAULT_HANDLE_OFFSET_PERCENT = 69f

        // ── 格子尺寸（需求 #5）与无字模式（需求 #4）──
        //
        // 66 / 108 是 Flyme 的 `ASAppGroupLayout.setupViewSpecs()`
        // （`mAppWidth = dpToPx(66.0f)`、`mAppHeight = dpToPx(108.0f)`），
        // 默认值必须逐字保持，否则「用户没改过设置，形态却变了」。
        /** 默认格宽（dp）= Flyme 的 `mAppWidth`。可调，见 [cellWidthDp]。 */
        const val DEFAULT_CELL_WIDTH_DP = 66f

        /**
         * 格宽的合法区间（需求 #5c，[cellWidthDp] 的滑杆区间）。
         *
         * 下限 44dp：比它更窄的格子在 24dp 的小图标下都只剩一点内容盒，
         * 而且**联动夹取会把它抬回下限**（下限 = 图标 + 2×3dp）—— 声明得比夹取下限还窄
         * 只会让人以为「滑杆拖到最小却没反应是坏了」。
         * 上限 96dp：再宽就会在任何手机上都被「屏宽 ÷ 列数」压回去（1220px ÷ 5 = 244px = 75dp），
         * 也就是滑杆的后半段是死区 —— 死区正是本项目最忌讳的形态。
         * ⚠️ 与 `core/SideIndexPreview.kt` 的四个同名私有常量逐字一致。
         */
        const val MIN_CELL_WIDTH_DP = 44f
        const val MAX_CELL_WIDTH_DP = 96f

        const val DEFAULT_CELL_HEIGHT_DP = 108f

        /**
         * 列数（需求 #5c）。
         *
         * 5 是 Flyme 原值（`ASAppGroupLayout` 每行 5 格：`marginStart = (i % 5) * mAppWidth`）。
         * 3 是「还像一张网格」的下限；7 是上限 —— 再多就要靠「屏宽 ÷ (图标 + 内边距)」
         * 才能排下，5 列以上的档位在小屏上本来就会被 [panelMetrics] 压回去。
         * ⚠️ 与 `core/SideIndexPreview.kt` 的三个同名私有常量逐字一致。
         */
        const val DEFAULT_COLUMNS = 5
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 7

        /**
         * 高亮抬升量与格高的比例 = **1/6**。
         *
         * 出处：Flyme `ASAppGroupSingleAppLayout.setupViewSpecs()` 把内容块
         * `translationY 0 → -dpToPx(18)`，而格子高是 `dpToPx(108)` —— `18 / 108 = 1/6`。
         * 用比例而非常量：格高可调（88–132dp）之后，固定 18dp 在格高小的时候会把内容块
         * 顶出格子（被 ScrollView 裁掉），而「格高 / 6」自动跟着格高走。
         * 默认格高 108dp 下 `351 / 6 = 58` = `dpToPx(18) = 58`，**逐位等于 Flyme**。
         * ⚠️ 与 `core/SideIndexPreview.kt` 的 `HIGHLIGHT_LIFT_DIVISOR` 逐字一致。
         */
        const val HIGHLIGHT_LIFT_DIVISOR = 6

        /**
         * 内容块高的**模型误差**预留（dp）。**我们加的**，见 [PanelMetrics.contentSlackPx]：
         * 模型把名字那一行按 [NAME_LINE_DP] = 17dp 算，真机字体的行高含 font padding
         * （Roboto 12sp 约 17.8dp）—— 直接从抬升余量里扣掉这 1dp，
         * 于是「抬升量不超过格子里的真实余量」永远成立，不需要靠 `clipChildren` 兜。
         */
        const val CONTENT_FIT_SAFETY_DP = 1f

        /**
         * 选中格兜底底色的圆角（dp）。**我们加的**（Flyme 没有这一层）：只在抬升量为 0
         * 那一档用（见 [PanelMetrics.highlightNeedsFallback]），圆角取一个与格子/图标
         * 同量级的值即可。
         * ⚠️ 与 `core/SideIndexPreview.kt` 的 `CELL_SELECT_BG_RADIUS_DP` 逐字一致。
         */
        const val CELL_SELECT_BG_RADIUS_DP = 12f

        /**
         * 格子内容的左右内边距（dp）= Flyme 的
         * `DeviceProfileConstants.AS_APP_CONTENT_PADDING_DP = (3, 0, 3, 0)`。
         *
         * 放在这里而不是 `SideIndexOverlay` 的 Companion：它参与 [panelMetrics] 的
         * **格宽下限**（`图标 + 2 × 内边距`），而「夹取用 3dp、布局用另一个值」
         * 是不会有任何报错的那种不一致。
         */
        const val CELL_CONTENT_PAD_H_DP = 3f

        /**
         * 格高的合法区间。
         *
         * 下限 88dp：视口最多 3 行，细条（184dp + 上边距 18dp）只在视口 ≥ 3 行时出现，
         * 所以 3 × 格高 必须 ≥ 202dp；88dp 留了 62dp 余量（比刚好的 68dp 宽松，
         * 因为细条还要在面板里上下移动）。
         * 上限 132dp：真机上还会被「可用屏高 ÷ 3」压一次（见 [cellHeightPx]）。
         */
        const val MIN_CELL_HEIGHT_DP = 88f
        const val MAX_CELL_HEIGHT_DP = 132f

        /**
         * 名字块占的高度（dp）= 名字那一行（12sp，约 17dp）+ 上间距 4dp。
         *
         * 17dp 这个数出自 [SideIndexOverlay.createCell] 的注释与 Flyme 的格子预算
         * （图标 80 + 名字 17 + 间距 4 = 101 ≤ 108）。无字模式下它**不占位**
         * （见 [SideIndexLook.nameBlockDp]）。
         */
        const val NAME_LINE_DP = 17f
        const val NAME_TOP_MARGIN_DP = 4f
        const val NAME_BLOCK_DP = NAME_LINE_DP + NAME_TOP_MARGIN_DP

        const val DEFAULT_HIDE_APP_NAMES = false

        // ── 模糊强度（需求 #7 档 A）──
        /** 默认 20dp = 改动前硬编码的 `SideIndexOverlay.BLUR_RADIUS_DP`，逐位一致。 */
        const val DEFAULT_BLUR_RADIUS_DP = 20f
        const val MIN_BLUR_RADIUS_DP = 0f
        /**
         * 80dp 是**保守上界**，不是标定值 —— 取证结论说 Kawase 4 pass 会饱和，
         * 真实拐点必须靠真机拍照标定。
         */
        const val MAX_BLUR_RADIUS_DP = 80f

        // ── Niagara 气泡动画（需求 #10）──
        /** 150ms = Niagara `integer/scrollbar_animation_time_press`。 */
        const val DEFAULT_BUBBLE_ANIM_MS = 150f
        const val MIN_BUBBLE_ANIM_MS = 0f
        const val MAX_BUBBLE_ANIM_MS = 400f
        const val DEFAULT_BUBBLE_ICON_SLOTS = false

        // ── 背景材质（需求 #7 档 B）──
        /** 现在的高斯模糊（+ 遮罩）。**默认档 = 改动前的形态**。 */
        const val MATERIAL_BLUR = "blur"

        /** 玻璃观感：同一份跨窗口模糊 + 叠一层 highlight shader（[SideIndexGlassPanel]）。 */
        const val MATERIAL_GLASS = "glass"

        const val DEFAULT_MATERIAL = MATERIAL_BLUR

        // 我们自己的合法区间（也是 Catalog 该用的 min/max）。
        const val MIN_LETTER_OFFSET_PERCENT = 0f
        const val MAX_LETTER_OFFSET_PERCENT = 100f
        const val MIN_LETTER_SCALE_PERCENT = 60f
        const val MAX_LETTER_SCALE_PERCENT = 180f
        const val MIN_ICON_DP = 24f
        const val MAX_ICON_DP = 80f
        const val MIN_ICON_RADIUS_DP = 0f
        const val MAX_ICON_RADIUS_DP = 18f
        const val MIN_PANEL_INSET_DP = 0f
        const val MAX_PANEL_INSET_DP = 120f
        const val MIN_HANDLE_HEIGHT_PERCENT = 10f
        const val MAX_HANDLE_HEIGHT_PERCENT = 100f
        const val MIN_HANDLE_OFFSET_PERCENT = 0f
        const val MAX_HANDLE_OFFSET_PERCENT = 100f

        /** 全默认值。索引还没读出来、或某项没声明时都用它。 */
        val DEFAULT = SideIndexLook()
    }
}

/**
 * 面板的格子几何（px）—— [SideIndexLook.panelMetrics] 的产物：**联动夹取之后**的结果。
 *
 * ## 为什么是一个值对象而不是四个独立函数
 *
 * 列数、格宽互相约束（见 `panelMetrics` 的 KDoc：列数决定格宽的上限，格宽的下限又决定
 * 列数能排几个）。分成四个函数就必然出现「A 用新列数、B 用旧格宽」的中间态 ——
 * 而那种中间态两边都「看起来对」，只是在组合起来时跑出屏幕。一份值对象则从构造上
 * 保证消费者拿到的是一组**自洽**的数字。
 *
 * 默认值下（5 列 × 66dp × 图标 50dp）它恒等于改动前的形态：`columns = 5`、`cellW = dp(66)`、
 * `cellH = dp(108)`、`contentWidth = 5 × dp(66)`。
 *
 * ## 消费者（三处，必须是同一份）
 *
 *  - [SideIndexOverlay] 的构造期字段（面板/视口/细条/滚动步长全靠它）；
 *  - 命中表：格子矩形来自真实布局（`recordRects`），所以「看到几列 × 多宽」与
 *    「碰到几列 × 多宽」同源 —— 这正是列数可调之后最容易错位的地方；
 *  - 安装报告（[describe]）：**生效值必须看得见**，否则「拖了列数没反应」只能靠猜。
 */
internal class PanelMetrics(
    /** 用户要的列数（已夹进 [SideIndexLook.MIN_COLUMNS]..[SideIndexLook.MAX_COLUMNS]）。 */
    val requestedColumns: Int,
    /** **生效列数**（视口几行、一屏几格、细条出不出来都按它算）。 */
    val columns: Int,
    /** 生效格宽（px）。 */
    val cellW: Int,
    /** 生效格高（px）。 */
    val cellH: Int,
    /** 图标边长（px，已夹 24–80dp）。格宽的下限由它推出来，所以一并带出。 */
    val iconSize: Int,
    /**
     * 格子里**内容块**（图标 + 名字；无字模式下只有图标）的高度（px）。
     *
     * 内容块在格子里**垂直居中**（照 Flyme 的 `layout_assearch_appgroup_single_app.xml`：
     * 格子根 `gravity = 0x11` = `CENTER`，内容是 `wrap_content`），所以上下各留
     * `(cellH − contentH) / 2` —— 上侧那一份就是高亮抬升的**上限**（见 [highlightLiftPx]）。
     */
    val contentH: Int,
    /**
     * 内容块**上方**可用于抬升的余量（px）—— 保守值。
     *
     * `= (cellH − contentH − CONTENT_FIT_SAFETY_DP) / 2`，夹到 ≥0。扣 [SideIndexLook.CONTENT_FIT_SAFETY_DP]
     * 是为了吸收 [contentH] 的模型误差（模型把名字行按 17dp 算，而真机字体的行高含
     * font padding、约 17.8dp）—— 扣掉之后「抬升量不超过真实余量」仍然成立，
     * 于是**任何格高/图标组合下内容块都不会顶出格子**。
     */
    val contentSlackPx: Int,
) {
    /**
     * 高亮那一格**实际**抬起的距离（px）—— **算出来的，不是写死的 18dp**（需求：顶行高亮被裁）。
     *
     * ```
     * 上限 = 格高 / [SideIndexLook.HIGHLIGHT_LIFT_DIVISOR]   （Flyme 的 18dp ÷ 108dp = 1/6）
     * 余量 = (格高 − 内容块高) / 2                          （内容块在格里居中）
     * 抬升 = min(上限, 余量)
     * ```
     *
     * 约束取「**格子内部**的余量」而不是「这一格上方还有多少空间」，是本条修复的关键：
     * 真机抬的是格子里面的内容块（Flyme 的 `mContentLayout`），只要抬升不超过格内余量，
     * 内容块就永远待在格子内部 —— 与它在第几行、列表滚到哪里**完全无关**，
     * 顶行/中间行/末行用同一个值且都不可能越界。
     *
     * ⚠️ 若按「上方空间」夹：第一行上方空间是 0（面板顶 = 手指 y − 70dp，而格高 ≥ 88dp，
     * 所以正常使用时手指**永远**落在第一行）→ 抬升这个反馈会整体失效。
     */
    val highlightLiftPx: Int
        get() = minOf(highlightLiftIdealPx, contentSlackPx)

    /** [highlightLiftPx] 的上限那一半（px）：格高 / [SideIndexLook.HIGHLIGHT_LIFT_DIVISOR]。 */
    val highlightLiftIdealPx: Int
        get() = (cellH / SideIndexLook.HIGHLIGHT_LIFT_DIVISOR).coerceAtLeast(0)

    /** 抬升量算出来是 0（格子刚好等于内容块高）→ 消费者要改用底色表达选中。 */
    val highlightNeedsFallback: Boolean get() = highlightLiftPx <= 0

    /** 面板内容总宽（px）= 生效列数 × 生效格宽。 */
    val contentWidth: Int get() = columns * cellW

    /** 生效列数是否被夹过（用户要的与生效的不一致）。 */
    val columnsClamped: Boolean get() = columns != requestedColumns

    /**
     * 一句话描述，进安装报告（**生效/用户要的**两个列数都打）。
     *
     * 与 [SideIndexLook.describe] 的分工：那边打**参数本身**（用户配的），
     * 这边打**夹取之后真正生效的**。两个数不等时（例如「要 7 列、排下 6 列」）
     * 只有把两边都摆出来才看得出「不是没生效，是放不下」。
     */
    fun describe(): String =
        "cols=$columns/${requestedColumns}${if (columnsClamped) "(夹)" else ""}" +
            " cell=${cellW}x$cellH px content=${contentWidth}px icon=$iconSize" +
            // 高亮抬升量：模型值。真机在布局跑完之后优先用**实测**的格内余量
            // （能跟上系统字号放大），所以这里打的是模型值 + 理想值，两个都留着便于核对
            // 「抬不抬得起来、有没有被余量夹」。
            " lift=$highlightLiftPx/$highlightLiftIdealPx px(模型)/内容块${contentH}px"
}
