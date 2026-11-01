package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect
import io.github.kotoriinsky.tsuzuri.core.geometry.SideIndexHotZone

/**
 * 侧滑索引的**预览场景**（设置界面里那两个状态开关）。
 *
 * 真机上这两个状态是同一次手势里的先后两段，但**看到的几何量完全不同**：
 *
 *  - [BAR]：手指压在字母条上。真机在 `trackPointer` 里走「在条上」那一支
 *    （`SideIndexOverlay.kt:367-376`）—— 面板跟手指、**格子高亮被显式清掉**
 *    （`setHighlight(-1)`），能看到的是字母条的选中态 + 大字母气泡。
 *  - [PANEL]：手指移到面板上（横向离开那 28dp 的命中列）。真机走 `else` 支
 *    （`SideIndexOverlay.kt:378-380`）—— 命中格子高亮、内容超过视口时出现细条。
 *
 * 没有面板那一段，用户就永远看不到「格子多大、图标圆角多少、面板留了多少缝」，
 * 而那正是这个特性里最值得调的东西。所以场景是**必须能切**的。
 */
enum class SideIndexPreviewScene {
    /** 手指在字母条上：条 + 大字母气泡。 */
    BAR,

    /** 手指拖到面板上：条 + 面板 + 高亮格子 + 细条。 */
    PANEL,
}

/**
 * 预览里用到的**参考实现原色**（ARGB）。
 *
 * 为什么这些颜色放在 `core` 而不是绘制层：它们不是「当前风格的主题色」，
 * 而是参考实现里**为这套浮层专门定义**的一组值（Appindex 的 `colors.xml` 与
 * `showFloatingWindow` 的渐变）—— 也就是说**颜色本身也是被预览的对象之一**。
 * 有语义的状态色（字母选中/未选中）不走这里，走设置界面自己的 Kit 配色，
 * 这样两套风格下预览都还是那一套界面的一部分。
 *
 * 每一项后面都写了它在 `hook/` 侧的出处：改那边时这里必须跟着改，
 * 否则预览一套颜色、真机另一套，而这种差异在截图里最难看出来。
 */
object SideIndexPreviewInk {
    /** 字母条未选中：`SideIndexOverlay.kt:1598`（47% 白，照 Flyme）。 */
    const val LETTER_IDLE = 0x78FFFFFF

    /** 字母条选中：`SideIndexOverlay.kt:677` 用的是 `Color.WHITE`（Flyme 只换颜色、不缩放）。 */
    const val LETTER_SELECTED = -1

    /** 应用名：`SideIndexOverlay.kt:1599` `NAME_COLOR`。 */
    const val NAME = 0xCCFFFFFF.toInt()

    /** 大字母气泡：`SideIndexOverlay.kt:1600-1601`（50dp 圆 + 2dp 描边）。 */
    const val BUBBLE_BG = 0xCC222222.toInt()
    const val BUBBLE_STROKE = 0xAAFFFFFF.toInt()

    /** 细条：`SideIndexOverlay.kt:1605`（未选中白降 alpha）。 */
    const val THUMB = 0x66FFFFFF

    /**
     * **选中格的兜底底色**（我们加的，参考实现里没有）。
     *
     * 只用在一个很窄的档位上：抬升量被算成 0 时（格子刚好等于内容块高，
     * 见 [SideIndexPreview.highlightNoLiftFallback]）—— 那时没有「抬起」可画，
     * 总不能让用户看不到自己选的是哪一格。真机侧同一处是
     * `SideIndexOverlay.CELL_SELECT_BG`，改这里必须同时改那边。
     */
    const val CELL_SELECT_BG = 0x26FFFFFF

    /** 边缘把手：`SideIndexHandle.kt:274-275`（Appindex `trigger_preview_color/_border`）。 */
    const val HANDLE_FILL = 0x44FF6B00
    const val HANDLE_BORDER = 0xFFFF6B00.toInt()

    /**
     * 全屏渐变遮罩（**未开模糊**那一对）：`SideIndexOverlay.kt:1608-1609`，
     * 与 Appindex `showFloatingWindow` 的 `-870704594 / -1157627904` 逐字相等。
     *
     * 开跨窗口模糊时真机换成 `DIM_BLUR_TOP/BOTTOM`（`SideIndexOverlay.kt:1615-1616`）——
     * 那一档预览画不出来（要不要模糊取决于 SystemUI 进程里的探针），所以预览一律按
     * 「未开模糊」这一对画，这一点写在报告里。
     */
    const val DIM_TOP = 0xCC1A1A2E.toInt()
    const val DIM_BOTTOM = 0xBB000000.toInt()
}

/**
 * 预览的**全部输入**：屏幕尺寸 + 读好的参数值 + 要看哪一段。
 *
 * ## 为什么不传 `Prefs`
 *
 * 与 `FanPreviewParams` 同一条理由（那边写了完整论证）：`ui` 与 `hook` 读配置的方式不同，
 * 而预览必须能在 Compose Preview（没有任何配置存储）里跑起来。所以由**调用方**把值读好传进来，
 * 参数名与 `core/Catalog` 里 `side_index.*` 的键名一一对应。
 *
 * ## 为什么不给几何参数默认值
 *
 * 几何量（[letters] / [iconSizeDp] / [panelInsetDp] …）**一个默认值都不给**：
 * 漏传必须编译不过。给默认值就会静默用默认值，而「预览用默认值、真机用用户值」
 * 正是「预览骗人」最直接的成因。只有两个量例外，两者都写了理由：
 * [panelTouchFraction]（纯交互演示量，真机上它就是用户手指的位置）与
 * [bottomSpacePx]（真机从资源里读，设置界面不该依赖那个资源）。
 *
 * @param screenW 屏幕宽（px）。取整块显示器的 bounds（与 hook 的
 *   `context.resources.displayMetrics.widthPixels` 同一口径）。
 * @param screenH 屏幕高（px）。
 * @param density 密度（px/dp）。
 * @param letters 字母条上的槽位，**顺序即显示顺序**（`AppIndex.Index.letters`）。
 *   带不带「最近使用」的 `★` 由调用方决定（`AppIndex.Index.withRecents` 决定加不加）。
 * @param slot 当前选中的槽位下标（超范围会夹到 `0..count-1`；[letters] 为空时是 `-1`）。
 * @param appCount 选中槽位下有几个应用。它决定面板**视口几行**（1–3）与细条出不出来。
 * @param scene 看哪一段（见 [SideIndexPreviewScene]）。
 * @param letterOffsetPercent `side_index.letter_offset`：字母条纵向位置，0 贴顶 / 50 居中 / 100 贴底。
 * @param letterScalePercent `side_index.letter_scale`：整条缩放（真机夹到 60–180%）。
 * @param iconSizeDp `side_index.icon_size`：应用图标边长（真机夹到 24–80dp）。
 * @param iconRadiusDp `side_index.icon_radius`：图标圆角（真机夹到 0–18dp，0 = 不裁）。
 * @param panelInsetDp `side_index.panel_inset`：面板内容离屏幕边的距离（真机夹到 0–120dp）。
 * @param onRight 预览哪一侧（`side_index.side`：`right` → `true`、`left` → `false`；
 *   `both` 时真机两侧各有一个窗口、形态镜像，预览画一侧即可）。
 * @param showHandle `side_index.show_handle`：画不画边缘把手。
 * @param handleWidthDp `side_index.trigger_width`：把手宽度（同时也是判定带宽）。
 * @param handleHeightPercent `side_index.handle_height`：把手高度占屏高的百分比。
 * @param handleOffsetPercent `side_index.handle_offset`：把手纵向位置（0 贴顶 / 100 贴底）。
 * @param panelTouchFraction **只看 [SideIndexPreviewScene.PANEL]**：手指在屏幕纵向上的位置
 *   （`0` = 最上、`1` = 最下）。真机上这就是用户手指的 y，而面板顶 = 手指 y − 70dp
 *   （`SideIndexOverlay.followerTop`）。给默认值 0.5 是因为它是个**交互演示量**：
 *   用户在设置界面里拖的是滑杆而不是这根手指；没有它，预览就只能画面板贴在屏幕上沿的样子。
 * @param bottomSpacePx 导航栏底部让位（px）。真机由 `SideIndexOverlay.navBarBottomSpacePx`
 *   从 `config_navBarOpacityMode` 读出来（该值 == 2 时 30dp，否则 0）。
 *   默认 0 = 不预留，即参考项目在「非半透明导航栏」机型上的行为。
 * @param cellHeightDp `side_index.cell_height`（需求 #5）：格子高（真机夹到 88–132dp，
 *   再按「可用屏高 ÷ 3」与「图标 + 名字块」联动夹取，见 [cellHeightPx]）。
 * @param hideAppNames `side_index.hide_app_names`（需求 #4，无字模式）：面板里的图标
 *   **不显示应用名**。它同时改变三件事：名字块不占格子高度、格子跟着矮、图标整格居中。
 * @param bubbleAnimMs `side_index.bubble_anim_ms`（需求 #10）：气泡按压淡入的时长（ms）。
 *   预览**不做动画**（同「预览画终态」那条约定），只把这个值带出来供界面显示。
 * @param bubbleIconSlots `side_index.bubble_icon_slots`（需求 #10）：特殊槽位
 *   （`★` = 最近使用、`♥` = 收藏项）的气泡里画**图标**而不是字母。
 * @param columns `side_index.columns`（需求 #5c）：**每行几格**。真机夹到 3–7，默认 5
 *   （Flyme 原值）。它不是「照单全收」的：放不下时**生效列数**会被减少
 *   （见 [SideIndexPreviewPanel.columns] 与 [PanelMetrics]）。
 * @param bothSides `side_index.side == both`：热区（[SideIndexPreview.hotZones]）画两侧。
 *   面板与把手仍只画 [onRight] 那一侧（真机两侧形态镜像）。默认 `false` 是为了不改旧调用点。
 * @param cellWidthDp `side_index.cell_width`（需求 #5c）：**格子宽 = 横向间距**。真机夹到
 *   44–96dp，默认 66dp（Flyme 原值）。图标尺寸固定时，格子越宽图标之间越疏 ——
 *   这就是「横向间距」在这套几何里的表达方式（真机格子横向相邻，
 *   `marginStart = (i % 列数) * mAppWidth`，没有额外的横向缝）。
 *   与 [columns] 一样不是照单全收：会被夹进「装得下图标」与「面板不出屏」之间。
 *
 * ## 本批新增几项**给了默认值**（与「几何参数一律不给默认值」那一条的冲突，如实说明）
 *
 * 上面那条纪律的目的写在 [SideIndexPreviewParams] 的注释里：漏传必须编译不过，
 * 否则会出现「预览用默认值、真机用用户值」。但本批新增的这几项还有另一个约束：
 * 读配置、构造本类的调用点（`ui/screen/FeatureDetailScreen.kt` 的
 * `buildSideIndexPreview`）由**另一个协作者接线**，而本批不允许改那个文件
 * （见任务书里的文件边界）。给默认值 = 「接线之前，预览按默认值画」——
 * 而默认值恰好等于**改动前的形态**（cell 108dp、显示名字、150ms、字母、
 * 5 列、格宽 66dp），所以接线前预览不会骗人，只是**看不到这几项的效果**。
 *
 * ⚠️ 因此：**这几项的接线没做完之前，「预览」这一页对它们是瞎的**。
 * 接线时把值从 `prefs` 读出来传进去即可（片段见报告），读法与同文件里
 * 那几个 `num(...)` / `flag(...)` 一样。
 */
class SideIndexPreviewParams(
    val screenW: Float,
    val screenH: Float,
    val density: Float,
    val letters: List<String>,
    val slot: Int,
    val appCount: Int,
    val scene: SideIndexPreviewScene,
    val letterOffsetPercent: Float,
    val letterScalePercent: Float,
    val iconSizeDp: Float,
    val iconRadiusDp: Float,
    val panelInsetDp: Float,
    val onRight: Boolean,
    val showHandle: Boolean,
    val handleWidthDp: Float,
    val handleHeightPercent: Float,
    val handleOffsetPercent: Float,
    val panelTouchFraction: Float = 0.5f,
    val bottomSpacePx: Float = 0f,
    val cellHeightDp: Float = DEFAULT_CELL_HEIGHT_DP,
    val hideAppNames: Boolean = false,
    val bubbleAnimMs: Float = DEFAULT_BUBBLE_ANIM_MS,
    val bubbleIconSlots: Boolean = false,
    val columns: Int = DEFAULT_COLUMNS,
    val cellWidthDp: Float = DEFAULT_CELL_WIDTH_DP,
    val bothSides: Boolean = false,
)

/**
 * 字母条的几何（px）。**逐行复刻 `AppIndex.barLayout`**（不是「照它的样子画一个」）。
 *
 * `SideIndexOverlay.buildBar` 拿的是同一个 `AppIndex.BarLayout`，所以这里是同一个公式的
 * 第二份实现 —— 两份必须逐字对应，理由与 `LetterIndex` 顶部那段一样（两边跑在不同进程，
 * 不能共用代码，但**语义必须一致**）。每个字段后面都标了 `hook/` 侧的出处。
 */
class SideIndexPreviewBar(
    /** 槽位（顺序即显示顺序）。 */
    val letters: List<String>,
    /** 单格高度（px）。`AppIndex.kt:536`。 */
    val itemHeight: Float,
    /** 条的上边缘（px）。`AppIndex.kt:546-547`。 */
    val barTop: Float,
    /** 命中列宽（px）= 28dp。**只用于命中判定**，而它同时也是画出来的容器宽度（见 [barLeft]）。 */
    val barWidth: Float,
    /** 条离屏幕边的距离（px）= 0（`AppIndex.kt:398`）。 */
    val barMargin: Float,
    /** 画出来的**字母格宽**（px）= 12dp（`SideIndexOverlay.kt:1523`）。 */
    val itemWidth: Float,
    /** 条的贴边留白（px）= 4dp，留在**靠屏内那一侧**（`SideIndexOverlay.kt:1524`）。 */
    val edgePadding: Float,
    /** 字号（px）—— 真机是 `COMPLEX_UNIT_PX`（`SideIndexOverlay.kt:652`），**不随系统字号缩放**。 */
    val letterTextPx: Float,
    /** 屏幕宽（px）。 */
    val screenW: Float,
    /** 屏幕高（px）。 */
    val screenH: Float,
    /** 贴哪一侧。 */
    val onRight: Boolean,
    /** 当前选中的槽位（已夹到合法范围；[letters] 为空时是 `-1`）。 */
    val slot: Int,
) {
    /** 槽位数。 */
    val count: Int get() = letters.size

    /** 整条的高度（px）= 格高 × 格数。 */
    val height: Float get() = itemHeight * count

    /**
     * 条的左边缘（px）。对应 `AppIndex.BarLayout.barLeft`：
     * 贴右时 `screenW - margin - width`，用的是**命中列宽**（28dp）——
     * `buildBar` 给那个容器的也是这个宽度（`SideIndexOverlay.kt:659-663`），
     * 所以「画出来的条」和「碰得到的条」刚好同宽。
     */
    val barLeft: Float get() = if (onRight) screenW - barMargin - barWidth else barMargin

    /**
     * 字母格的左边缘（px）。容器里按 `gravity = CENTER_HORIZONTAL` 居中
     * （`SideIndexOverlay.kt:640`），所以是「去掉贴边留白后的盒子」再居中。
     */
    val letterLeft: Float
        get() {
            val box = (barWidth - edgePadding - itemWidth).toInt() / 2
            return if (onRight) barLeft + box else barLeft + edgePadding + box
        }

    /** 字母的横向中心（px）。真机的字母是居中的 `TextView`，所以中心就是格子中心。 */
    val letterCenterX: Float get() = letterLeft + itemWidth / 2f

    /** 第 [index] 格的中心 y（px）。 */
    fun letterCenterY(index: Int): Float = barTop + itemHeight * index + itemHeight / 2f

    /**
     * 手指 y → 槽位。**直接复用 [LetterIndex.slotAt]** —— 它已被注明与 hook 侧
     * `AppIndex.letterAt` 是同一个算法（那边照搬 Appindex 的 `calculateSelectedLetter`），
     * 而那个函数存在的意义就是「两个进程的手感必须一致」。在这里再写第三遍没有任何好处。
     */
    fun indexAt(y: Float): Int = LetterIndex.slotAt(y, barTop, itemHeight, count)

    /**
     * 手指横向是否还在条这一列里。对应 `AppIndex.BarLayout.inBarColumn`：
     * **换字母只认这个判据**（纵向是选择量，横向一旦离开就停止换字母）。
     */
    fun inBarColumn(x: Float): Boolean {
        val left = barLeft
        return x >= left && x <= left + barWidth
    }

    /** 手指是否落在字母条上（对应 `AppIndex.BarLayout.inBar`）。 */
    fun inBar(x: Float, y: Float): Boolean {
        if (count <= 0) return false
        return inBarColumn(x) && y >= barTop && y <= barTop + height
    }

    /** 整条的矩形（命中列宽 × 整条高）。 */
    fun rect(): ScreenRect = ScreenRect(barLeft, barTop, barLeft + barWidth, barTop + height)

    override fun toString(): String =
        "bar(${count}格 h=${itemHeight.toInt()} top=${barTop.toInt()} ${if (onRight) "右" else "左"})"
}

/** 面板里的一个格子。 */
class SideIndexPreviewCell(
    /** 应用在清单里的下标（0 起，与 `assemblePanel` 的塞入顺序一致）。 */
    val index: Int,
    /** 第几行（0 起）。 */
    val row: Int,
    /** 第几列（0 起）。**最后一行不满时按行对齐规则算过**（不是简单的 `index % 5`）。 */
    val column: Int,
    /** 格子矩形（66×108dp）。命中判定用的就是它（`recordRects` + `hitTest`）。 */
    val cellRect: ScreenRect,
    /**
     * 图标矩形。横向在格子里居中、**纵向跟着内容块一起在格子里居中**
     * （`iconTop = cellTop + (cellH − contentH) / 2`，照 Flyme 的 cell xml `gravity=CENTER`）。
     * 边长 = 真机算出来的 iconSize。
     */
    val iconRect: ScreenRect,
    /** 应用名文字的上边缘（px）= 图标下沿 + 4dp（`SideIndexOverlay.kt:971`）。 */
    val nameTop: Float,
    /**
     * 这一格在不在视口里。`false` = 被 `ScrollView` 裁掉了（真机上要滚动才看得到）。
     * 预览**照实报位置**、由绘制层裁 —— 与真机「位置照算、显示被裁」是同一条。
     */
    val inViewport: Boolean,
) {
    override fun toString(): String = "cell$index(r$row,c$column)"
}

/** 面板的几何（px）。逐行复刻 `buildPanel` / `panelLeft` / `followerTop` / `applyThumb`。 */
class SideIndexPreviewPanel(
    /**
     * **生效列数**（= 每行几格）。默认 5（Flyme 原值），但它**不是**用户填的那个数：
     * 放不下时会被减少（见 [requestedColumns]、[SideIndexPreviewMetrics] 的夹取规则）。
     * 面板的行数、格子排布、细条显隐全部按**这一个数**算 —— `itemRects` 与视图同源。
     */
    val columns: Int,
    /**
     * 用户**要的**列数（`side_index.columns`，已夹进 3–7）。只有当它不等于 [columns]
     * 时才是「被夹过」—— 设置界面据此可以说明「这个组合放不下，已按 N 列排」，
     * 而不是让用户以为自己拖的滑杆没反应。
     */
    val requestedColumns: Int,
    /** 格子宽（px）。默认 66dp，但会被夹进「装得下图标」与「面板不出屏」之间。 */
    val cellW: Float,
    /** 格子高（px）= 108dp（默认）。 */
    val cellH: Float,
    /** 内容总宽（px）= 格子宽 × 列数。 */
    val contentWidth: Float,
    /** 内容行数 = `ceil(appCount / 生效列数)`。**0 = 这个字母下一个应用都没有**。 */
    val contentRows: Int,
    /** 视口行数 = `contentRows.coerceIn(1, 3)`（Flyme `showApps` 的 `i2`）。 */
    val viewportRows: Int,
    /** 面板左边缘（px）= 屏幕边 ± `panel_inset`。 */
    val left: Float,
    /** 面板顶（px）= 手指 y − 70dp、两头夹（`followerTop`）。 */
    val top: Float,
    /** 图标边长（px）—— 已按 24–80dp 夹取再乘密度。 */
    val iconSize: Float,
    /** 图标圆角（px）—— 0 = 不裁。 */
    val iconRadius: Float,
    /** 格子内容左右内边距（px）= 3dp。 */
    val contentPadH: Float,
    /** 应用名上间距（px）= 4dp。 */
    val nameTopMargin: Float,
    /** 应用名字号（**sp**，真机是 `COMPLEX_UNIT_SP`，所以它随系统字号缩放）。 */
    val nameFontSp: Float,
    /**
     * 格子里**内容块**（图标 + 名字；无字模式下只有图标）的高度（px）。
     *
     * 内容块在格子里**垂直居中** —— 照 Flyme 的 `layout_assearch_appgroup_single_app.xml`：
     * 格子根的 `gravity = 0x11`（`CENTER`）、里面那个内容 LinearLayout 是 `wrap_content`。
     * 所以格子上下各留 `(cellH − contentH) / 2`，而上侧那一份正是**高亮抬升的上限**
     * （见 [highlightLiftPx]）：抬升量不超过它，内容块就永远待在格子内部，**不会被裁**。
     */
    val contentH: Float,
    /**
     * 高亮那一格**实际**抬起的距离（px）。**算出来的，不是写死的 18dp** ——
     * 算式与理由见 [sideIndexHighlightLiftPx]（默认格高下逐位等于 Flyme 的 18dp）。
     *
     * 抬起作用在**格子里的内容块**上（不是整格）：看起来是「图标连着名字向上弹一下」，
     * 与 Flyme 的 `mContentLayout.setTranslationY(-dp(18))` 是同一种表达。
     */
    val highlightLiftPx: Float,
    /** 抬升量的**理想值**（px）= 格高 / 6。诊断用：与 [highlightLiftPx] 不等就是被余量夹过。 */
    val highlightLiftIdealPx: Float,
    /** 选中格兜底底色的圆角（px）。对应 `SideIndexOverlay.CELL_SELECT_BG_RADIUS_DP`。 */
    val selectBgRadius: Float,
    /**
     * 面板里画不画应用名（需求 #4 无字模式）。
     *
     * 绘制层据此决定画不画那条名字占位条。它**同时**决定了格高
     * （无字模式下 [cellH] 里不再计入名字块）与图标的纵向位置
     * （真机那边格子 `gravity = CENTER`，图标整格居中）。
     */
    val namesVisible: Boolean,
    /** 最大滚动距离（px）= `(内容行数 − 视口行数) × 格子高`，夹到 ≥0。 */
    val maxScrollPx: Float,
    /** 所有格子（含被视口裁掉的那些）。 */
    val cells: List<SideIndexPreviewCell>,
    /**
     * 手指命中的格子下标；`-1` = 没命中任何格子。
     *
     * 手指在字母条上（[SideIndexPreviewScene.BAR]）时**恒为 -1** —— 真机那一条分支里
     * `setHighlight(-1)` 是显式调用的（`SideIndexOverlay.kt:371`），不是「恰好没命中」。
     */
    val highlighted: Int,
    /** 细条矩形（px）。`null` = 内容没超过视口，真机上它 `alpha = 0`（`applyThumb:1087-1088`）。 */
    val thumb: ScreenRect?,
    /** 细条上边距（px）= 18dp。 */
    val thumbTopGap: Float,
    /** 细条圆角（px）= 2dp（4dp 宽的条，正好是半圆）。 */
    val thumbRadius: Float,
) {
    /** 视口矩形（px）—— 面板真正看得见的那一块，超出的部分由 ScrollView 裁掉。 */
    val viewport: ScreenRect
        get() = ScreenRect(left, top, left + contentWidth, top + cellH * viewportRows)

    /** 内容矩形（px）—— 所有行拼起来的总高，滚动范围由它与视口决定。 */
    val contentRect: ScreenRect
        get() = ScreenRect(left, top, left + contentWidth, top + cellH * contentRows)

    override fun toString(): String =
        "panel(${columns}列 视口${viewportRows}行 内容${contentRows}行)"
}

/**
 * 大字母气泡（那个 50dp 的圆）。对应 `updateBubble` + `placeBubble` + `applyBubbleVisibility`。
 *
 * 它是**圆**而不是方角：真机用 `GradientDrawable.OVAL`（`SideIndexOverlay.kt:1284`），
 * 50dp 只是它的布局尺寸。
 */
class SideIndexPreviewBubble(
    /** 当前字母。没有槽位时是空串。 */
    val text: String,
    /** 边长（px）= 50dp（即圆的直径）。 */
    val size: Float,
    /** 字号（**sp**，真机是 `COMPLEX_UNIT_SP`，随系统字号缩放）。 */
    val fontSp: Float,
    /** 横向位置（px，左边缘，已夹进屏幕）。 */
    val left: Float,
    /** 纵向位置（px，上边缘）= 面板顶 − 50dp − 8dp。 */
    val top: Float,
    /** 距面板的横向缝隙（px）= 4dp。 */
    val gap: Float,
    /** 距屏幕上沿的留白（px）= 8dp。 */
    val topGap: Float,
    /** 画不画。两个成因见 [SideIndexPreview] 的类注释。 */
    val visible: Boolean,
    /**
     * 按压淡入的时长（ms，需求 #10）—— `side_index.bubble_anim_ms` 原样带出。
     *
     * 预览**不做动画**（同「预览画终态」那条约定），这个值只是让设置界面能显示它、
     * 也让 `describe()` 里有据可查。真机行为逐条对应 Niagara：
     * 纯 alpha 淡入、`AccelerateDecelerateInterpolator`、默认 150ms。
     */
    val pressFadeMs: Float,
    /**
     * 这一槽的气泡里画的是**图标**而不是字母（收藏项复用同一机制）。
     *
     * 真机条件：开关 `side_index.bubble_icon_slots` 开着、且这一槽是特殊槽
     * （`AppIndex.RECENT_SLOT` = `★` 或 `AppIndex.FAVORITE_SLOT` = `♥`）、且图标取到了。
     * 预览没有真实图标，画的是一个**中性的圆角方块占位**（与面板里的图标占位同一套画法）。
     *
     * 槽位判定收在 [SPECIAL_SLOTS] 一处 —— 加第三个特殊槽时不该有人再改这里。
     */
    val iconSlot: Boolean,
) {
    /** 气泡的矩形（px）。 */
    fun rect(): ScreenRect = ScreenRect(left, top, left + size, top + size)

    /** 圆心 x（px）。 */
    val centerX: Float get() = left + size / 2f

    /** 圆心 y（px）。 */
    val centerY: Float get() = top + size / 2f

    override fun toString(): String =
        "bubble('$text' @${left.toInt()},${top.toInt()} visible=$visible)"
}

/** 边缘把手（对应 `SideIndexHandle.attach`）。 */
class SideIndexPreviewHandle(
    /** 把手矩形（px）。 */
    val rect: ScreenRect,
) {
    override fun toString(): String = "handle(${rect.width.toInt()}x${rect.height.toInt()})"
}

/**
 * 预览的产物：**字母条 / 面板 / 气泡 / 把手分别画在屏幕的哪里**。
 *
 * ## 与真机**有意的**差别（写清楚，免得被当成 bug）
 *
 *  - **不含动画态**：真机有淡入（150ms）、格子高亮抬起（100ms，抬升量**算出来的** ——
 *    见 [sideIndexHighlightLiftPx]）、自动滚动。预览画的是**终态**
 *    （高亮格的内容块已经抬到位、滚动位置恒为 0）—— 与 `FanPreview` 同一条约定。
 *  - **把手与浮层在真机上不会同时出现**：手势进行中把手被显式隐藏
 *    （`SideIndexHandle.isVisible = attached && scopeAllowed && !gestureActive`）。
 *    预览照画，因为「把手长什么样」正是用户在设置里要调的东西，不画就调不了。
 *  - **遮罩一律按「未开模糊」那一对颜色**（见 [SideIndexPreviewInk.DIM_TOP]）。
 *  - **滚动位置恒为 0**：与 Flyme 每次 `showApps` 都 `smoothScrollTo(0, 0)` 一致
 *    （`SideIndexOverlay.kt:730`），所以预览看到的永远是「刚选中一个字母」的那一帧。
 *  - **占位态（「读取应用中…」）不在预览里**：预览的假数据是固定项数的真实态。
 *  - **不含字母条那 30° 滤波**：Appindex 按角度滤波后才换字母，预览只画当前槽位。
 */
class SideIndexPreview(
    val screenW: Float,
    val screenH: Float,
    val density: Float,
    /** 这一帧看的是哪一段（[SideIndexPreviewParams.scene] 原样带出）。 */
    val scene: SideIndexPreviewScene,
    /** 预览的是哪一侧。 */
    val onRight: Boolean,
    /** 字母条的几何。 */
    val bar: SideIndexPreviewBar,
    /** 面板的几何（**永远有值**，退化时是 1 行空视口）。 */
    val panel: SideIndexPreviewPanel,
    /** 大字母气泡。 */
    val bubble: SideIndexPreviewBubble,
    /** 边缘把手；`null` = `show_handle` 关着。 */
    val handle: SideIndexPreviewHandle?,
    /** 手指 x（px）—— 场景决定它在条上还是在面板上。 */
    val fingerX: Float,
    /** 手指 y（px）。 */
    val fingerY: Float,
    /**
     * 高亮格子**实际**抬起的距离（px）。没有高亮格（[highlighted] = -1）时是 0。
     *
     * 单独给出来是因为它**不属于格子的几何**：格子矩形照原位报（命中判定用的就是原位），
     * 抬起只是绘制时对**格子里的内容块**做的 `translationY` —— 与 Flyme 的
     * `ASAppGroupSingleAppLayout` 把 `mContentLayout` 抬起 18dp 是同一种表达。
     * 绘制层据此把那一格的内容往上挪。**它是算出来的**（见 [sideIndexHighlightLiftPx]），
     * 不是 Flyme 那个写死的 18dp。
     */
    val highlightLiftPx: Float,
    /** 抬升量的理想值（px）= 格高 / 6；默认格高下就是 Flyme 的 18dp。诊断用。 */
    val highlightLiftIdealPx: Float,
    /**
     * **抬升量算出来是 0**（格子刚好等于内容块高 → [SideIndexPreviewPanel.contentH] 顶满），
     * 所以真机与预览改用**底色**表达选中（[SideIndexPreviewInk.CELL_SELECT_BG]）。
     *
     * 这一档在正常机型上很窄（要「图标 80dp + 格高贴到下限 101dp」这种组合才到得了），
     * 但到得了 —— 那时没有任何「抬起」可画，总不能让用户看不出选的是哪一格。
     * 只有真的有高亮格且抬升量为 0 时才是 `true`。
     */
    val highlightNoLiftFallback: Boolean,
    /**
     * 真实起手热区（px）：整条边缘带扣掉上下保留区，**与 hook 判定同一份算式**
     * （[io.github.kotoriinsky.tsuzuri.core.geometry.SideIndexHotZone.zones]）。
     * 与 [handle] 无关 —— 把手只是看得见的提示条，能不能起手看这里。
     */
    val hotZones: List<ScreenRect> = emptyList(),
) {

    override fun toString(): String =
        "SideIndexPreview(${screenW.toInt()}x${screenH.toInt()} slots=${bar.count} " +
            "panel=${panel.columns}x${panel.viewportRows} scene=$scene " +
            "mode=${if (onRight) "R" else "L"})"

    /** 一句话摘要（诊断/日志用，与 `SideIndexLook.describe()` 同一个风格）。 */
    fun describe(): String =
        "letter=${bar.itemHeight.toInt()}px/${bar.letterTextPx.toInt()}px top=${bar.barTop.toInt()}" +
            " panel=${panel.contentWidth.toInt()}x${panel.viewport.height.toInt()}" +
            " cell=${panel.cellW.toInt()}x${panel.cellH.toInt()}" +
            // 列数打「生效/用户要的」两个：两者不等就是被夹过（放不下），
            // 只打一个数会让「我拖了列数却没反应」变成一个需要猜的现象。
            " cols=${panel.columns}/${panel.requestedColumns}" +
            " nolabel=${!panel.namesVisible}" +
            " inset=${panel.left.toInt()} icon=${panel.iconSize.toInt()}r${panel.iconRadius.toInt()}" +
            " hit=${panel.highlighted} bubble=${bubble.visible}/${bubble.pressFadeMs.toInt()}ms" +
            // 抬升量打「实际/理想」两个：不等就是被格子里的余量夹过（格高小或图标大），
            // 为 0 时真机与预览改用底色（见 highlightNoLiftFallback）—— 这些量
            // 「一眼看不出但会影响手感」，必须进诊断。
            " lift=${highlightLiftPx.toInt()}/${highlightLiftIdealPx.toInt()}px" +
            (if (highlightNoLiftFallback) "(底色)" else "") +
            "/iconslot=${bubble.iconSlot} thumb=${panel.thumb != null}" +
            " handle=${handle != null}"
}

// ── 复刻自 hook 的常量（每一组都标了出处） ────────────────────────────────
//
// 为什么在 core 里再写一遍、而不是让两边共享一份：这些常量定义在
// `hook/feature/sideindex/` 几个类**内部的 `private companion object`** 里，
// 要共用就得先把它们搬进 core 并改 hook —— 那是接线工作，而这一次的边界是
// 「只加预览、不动 hook」。所以这里逐字复刻，报告里给出对照表。
// **等哪天有人动 hook 时，正确的做法是把这几组常量搬进 core**，
// 让 AppIndex / SideIndexOverlay / SideIndexHandle 都读 core 这一份。

/** 单格高度基准（dp）。`AppIndex.kt:407`；`barLayout` 里那个原样的 `+1` 在 `AppIndex.kt:536`。 */
private const val BAR_ITEM_DP = 16f

/** 字母字号基准（dp）。`AppIndex.kt:410`。 */
private const val BAR_TEXT_DP = 12f

/** 字号相对格高的上限。`AppIndex.kt:424`。 */
private const val BAR_TEXT_MAX_RATIO = 0.8f

/** 命中列宽（dp）。`AppIndex.kt:434`。 */
private const val BAR_WIDTH_DP = 28f

/** 条离屏幕边的距离（dp）。`AppIndex.kt:398` `BAR_MARGIN_DP = 0`。 */
private const val BAR_MARGIN_DP = 0f

/** 条最高占屏幕的比例。`AppIndex.kt:444`。 */
private const val BAR_MAX_HEIGHT_FRACTION = 0.82f

/** 整体缩放的合法区间。`AppIndex.kt:568-569`。 */
private const val MIN_BAR_SCALE = 0.6f
private const val MAX_BAR_SCALE = 1.8f

/** 字母格宽与贴边留白（dp）。`SideIndexOverlay.kt:1523-1524`。 */
private const val BAR_ITEM_WIDTH_DP = 12f
private const val BAR_EDGE_PADDING_DP = 4f

/** 面板格子与名字（dp / sp）。`SideIndexLook.kt` 的 Companion（`CELL_WIDTH_DP` /
 * `DEFAULT_CELL_HEIGHT_DP` / `MIN/MAX_CELL_WIDTH_DP` / `MIN/MAX_COLUMNS`）与
 * `SideIndexOverlay.kt` / `SideIndexLook.kt` 的 `CELL_CONTENT_PAD_H_DP` /
 * `NAME_TOP_MARGIN_DP` / `NAME_SP`。
 *
 * ⚠️ 2026-09-14（需求 #5）：格宽/格高从 `SideIndexOverlay.kt` 的 Companion **搬到了
 * `SideIndexLook.kt`** —— 它们要参与「格子高可调 + 联动夹取」的推导。
 *
 * ⚠️ 2026-09-14（需求 #5c）：**列数与格宽也进了推导**，所以这四个区间常量也必须
 * 与 `SideIndexLook` 逐字一致（默认值不一致 = 「用户没改设置，预览和真机却是两套形态」）。 */
private const val DEFAULT_COLUMNS = 5
private const val MIN_COLUMNS = 3
private const val MAX_COLUMNS = 7
private const val DEFAULT_CELL_WIDTH_DP = 66f
private const val MIN_CELL_WIDTH_DP = 44f
private const val MAX_CELL_WIDTH_DP = 96f
private const val APP_CONTENT_PAD_H_DP = 3f
private const val NAME_TOP_MARGIN_DP = 4f
private const val NAME_SP = 12f

/** 格高的默认值与合法区间、名字块高度（dp）。`SideIndexLook.kt` 的
 * `DEFAULT_CELL_HEIGHT_DP` / `MIN` / `MAX_CELL_HEIGHT_DP`、`NAME_BLOCK_DP`
 * （= 名字行 17dp + 上间距 4dp）。 */
private const val DEFAULT_CELL_HEIGHT_DP = 108f
private const val MIN_CELL_HEIGHT_DP = 88f
private const val MAX_CELL_HEIGHT_DP = 132f
private const val NAME_LINE_DP = 17f
private const val NAME_BLOCK_DP = NAME_LINE_DP + NAME_TOP_MARGIN_DP

/** 气泡按压淡入的默认时长与合法区间（ms）。`SideIndexLook.kt` 的三个同名常量
 * （Niagara `integer/scrollbar_animation_time_press` = 150ms）。 */
private const val DEFAULT_BUBBLE_ANIM_MS = 150f
private const val MIN_BUBBLE_ANIM_MS = 0f
private const val MAX_BUBBLE_ANIM_MS = 400f

/**
 * 「最近使用」槽位的字形。真机侧是 `AppIndex.RECENT_SLOT`（`"★"`），同一个字符串 ——
 * 预览用它判定「这一槽是特殊槽、气泡里画图标而不是字母」（需求 #10）。
 */
private const val RECENT_SLOT = "★"

/**
 * 「收藏项」槽位的字形。真机侧是 `AppIndex.FAVORITE_SLOT`（`"♥"`），
 * 同一个字符串 —— 两侧各写一遍是刻意的（两个进程不共用代码，但**语义必须一致**，
 * 同 `LetterIndex` 顶部那条注记）。
 *
 * 为什么是 `♥` 而不是 `☆` / `★`：`★` 已经被「最近使用」占了，两个槽位必须是**不同**的
 * 字形 —— 否则字母条上会出现两个看起来一模一样的格子，而用户分不出哪个是哪个。
 */
private const val FAVORITE_SLOT = "♥"

/**
 * 特殊槽位（气泡里画图标而不是字母的那些）。
 *
 * 用集合而不是「跟 `★` 比一次」：落下来是**两个**槽位，而它们共用
 * 同一个开关 `side_index.bubble_icon_slots`。收成一处是为了「以后再加一个特殊槽」
 * 只改这里 —— 上一版只认 `★`，新槽位会在预览里悄悄退回字母而真机上是有图标的，
 * 那种不一致没有任何报错。
 */
private val SPECIAL_SLOTS = setOf(RECENT_SLOT, FAVORITE_SLOT)

/** 面板视口最多几行。`SideIndexOverlay.kt:1542`（Flyme `mListViewMaxHeight = mAppHeight * 3`）。 */
private const val PANEL_MAX_ROWS = 3

/** 跟手指的偏移（dp）。`SideIndexOverlay.kt:1549`。 */
private const val PANEL_FINGER_OFFSET_DP = 70f

/** 细条（dp）。`SideIndexOverlay.kt:1552-1555`。 */
private const val THUMB_W_DP = 4f
private const val THUMB_H_DP = 184f
private const val THUMB_TOP_DP = 18f
private const val THUMB_RADIUS_DP = 2f

/** 大字母（dp / sp）。`SideIndexOverlay.kt:1565-1574`。 */
private const val BUBBLE_DP = 50f
private const val BUBBLE_SP = 32f
private const val BUBBLE_GAP_DP = 4f
private const val BUBBLE_TOP_GAP_DP = 8f
private const val SAFE_EDGE_DP = 4f

/**
 * 高亮抬升量与格高的比例 = **1/6**。
 *
 * 出处：Flyme `ASAppGroupSingleAppLayout.setupViewSpecs()` 是
 * `translationY 0 → -dpToPx(18)`，而格子高是 `dpToPx(108)` —— `18 / 108 = 1/6`。
 * 用比例而不是常量，是为了让「格高可调」（`side_index.cell_height`，88–132dp）
 * 之后抬升量跟着变：格高变大 → 抬得更多；格高变小 → 抬得更少（否则会顶出格子被裁）。
 * 默认格高 108dp 下 `108/6 = 18dp`，与 Flyme 逐位相同。
 */
private const val HIGHLIGHT_LIFT_DIVISOR = 6

/**
 * 内容块高的**模型误差**预留（dp）。对应 `SideIndexLook.CONTENT_FIT_SAFETY_DP` ——
 * 模型把名字行按 [NAME_LINE_DP] = 17dp 算，真机字体的行高含 font padding（约 17.8dp），
 * 从抬升余量里扣掉这 1dp，「抬升量不超过格子里的真实余量」才成立。
 */
private const val CONTENT_FIT_SAFETY_DP = 1f

/** 选中兜底底色的圆角（dp）。对应 `SideIndexOverlay.CELL_SELECT_BG_RADIUS_DP`。 */
private const val CELL_SELECT_BG_RADIUS_DP = 12f

/** 图标的合法区间（dp）。`SideIndexLook.kt:114-115`。 */
private const val MIN_ICON_DP = 24f
private const val MAX_ICON_DP = 80f

/** 图标圆角的合法区间（dp）。`SideIndexLook.kt:116-117`。 */
private const val MIN_ICON_RADIUS_DP = 0f
private const val MAX_ICON_RADIUS_DP = 18f

/** 面板边距的合法区间（dp）。`SideIndexLook.kt:118-119`。 */
private const val MIN_PANEL_INSET_DP = 0f
private const val MAX_PANEL_INSET_DP = 120f

/** 把手高度比例的合法区间。`SideIndexHandle.kt:270-271`。 */
private const val MIN_HANDLE_HEIGHT_RATIO = 0.1f
private const val MAX_HANDLE_HEIGHT_RATIO = 1f

/** 各滑块的出厂默认值（**只在非有限输入时兜底**，见 [finiteOr]）。 */
private const val DEFAULT_LETTER_OFFSET_PERCENT = 50f
private const val DEFAULT_LETTER_SCALE_PERCENT = 100f
private const val DEFAULT_ICON_DP = 50f
private const val DEFAULT_PANEL_INSET_DP = 32f
private const val PANEL_THUMB_GAP_DP = 4f
private const val DEFAULT_HANDLE_WIDTH_DP = 20f
private const val DEFAULT_HANDLE_HEIGHT_PERCENT = 45f
private const val DEFAULT_HANDLE_OFFSET_PERCENT = 69f

/**
 * 非有限值退回出厂默认。
 *
 * **这是一处有意的、与真机算式的偏差**，理由写清楚：真机那几处 `coerceIn` 对 NaN 是
 * **穿透**的（`NaN.coerceIn(a, b)` 返回 NaN），接着 `(NaN * density).toInt()` 在 Kotlin 里
 * 是 `0`，于是 `AppIndex.barLayout` 会得到 1px 的格高、`SideIndexLook.iconSizePx` 会得到
 * 1px 的图标 —— 一根看不见的条。真机上这一档到不了（Catalog 的滑杆只会写出合法有限值），
 * 而预览是**界面线程上的纯函数**：NaN 画出来就是「整块预览莫名其妙没了」，
 * 没有任何错误提示，用户只会以为功能坏了。所以预览在非有限输入下退到出厂默认并画得出来。
 *
 * **有限值（含越界）照真机逐字夹取**，不走这条 —— 那才是用户真能调出来的档位。
 */
private fun finiteOr(value: Float, fallback: Float): Float =
    if (value.isFinite()) value else fallback

/**
 * **给预览用的纯函数入口**：输入屏幕尺寸 + 读好的参数值，输出「条 / 面板 / 气泡 / 把手画在哪」。
 *
 * ## 为什么是一个纯函数 + 几个值类型
 *
 * 与 `fanPreview()` 完全同构（那边写了完整论证）：`ui` 不许依赖 `hook`
 * （`settings.gradle.kts` 的模块边界），而预览必须与真机**逐个数字一致**，
 * 所以几何在 `core` 里复刻一份，由 `ui` 读配置后调用、由 `hook` 侧的实现作为唯一事实来源。
 *
 * ## 逐条对应关系（改这里之前先看这几处）
 *
 * | 这一份 | 真机 |
 * | --- | --- |
 * | [SideIndexPreviewBar] 的全部字段 | `AppIndex.barLayout`（`AppIndex.kt:520-565`） |
 * | 字母格宽 / 贴边留白 / 容器宽度 | `SideIndexOverlay.buildBar`（`SideIndexOverlay.kt:632-668`） |
 * | 面板格子 / 视口行数 | `SideIndexOverlay.buildPanel`（`SideIndexOverlay.kt:713-723`） |
 * | **格子宽高**（`cellW` / `cellH`） | `SideIndexLook.panelMetrics` —— 2026-09-14 起格子尺寸的**唯一来源**在 `SideIndexLook`（需求 #5/#5c），`SideIndexOverlay` 的构造期字段 `cellW` / `cellH` / `COLUMNS` 读的就是它 |
 * | **生效列数 / 格宽下限**（`panelMetrics`） | `SideIndexLook.panelMetrics`（需求 #5c 的联动夹取，规则与证明在那边和 [panelMetrics] 各写一份、逐字对应） |
 * | **无字模式**（`namesVisible` + 图标纵向居中） | `SideIndexOverlay.createCell`（名字不进布局 + `gravity = CENTER`，需求 #4） |
 * | 格子内部（图标/名字） | `SideIndexOverlay.createCell`（`SideIndexOverlay.kt:941-986`） |
 * | 面板左右位置 | `SideIndexOverlay.panelLeft`（`SideIndexOverlay.kt:1005-1012`） |
 * | 面板顶（跟手指） | `SideIndexOverlay.followerTop`（`SideIndexOverlay.kt:1037-1041`） |
 * | 最后一个不满行的对齐 | `SideIndexOverlay.assemblePanel`（`SideIndexOverlay.kt:860-878`） |
 * | 细条位置与显隐 | `SideIndexOverlay.applyThumb`（`SideIndexOverlay.kt:1076-1092`） |
 * | 气泡位置与显隐 | `SideIndexOverlay.updateBubble` / `placeBubble` / `applyBubbleVisibility`（`SideIndexOverlay.kt:1267-1337`） |
 * | **气泡按压淡入时长**（`pressFadeMs`） | `SideIndexLook.bubbleAnimMs` → `SideIndexOverlay.applyBubbleVisibility`（纯 alpha、`AccelerateDecelerateInterpolator`、默认 150ms，需求 #10） |
 * | **特殊槽位用图标**（`iconSlot`） | `SideIndexOverlay.bindBubbleContent`（`★` 槽走 `slotIcon`，需求 #10） |
 * | 手指命中格子 | `SideIndexOverlay.recordRects` + `hitTest`（`SideIndexOverlay.kt:1341-1374`） |
 * | **高亮抬升量**（[sideIndexHighlightLiftPx]） | `PanelMetrics.highlightLiftPx`（`SideIndexLook`；「顶行高亮被裁」的修复）|
 * | **内容块在格子里居中**（[SideIndexPreviewPanel.contentH]） | `SideIndexOverlay.createCell`（格子 `gravity = CENTER` + 内层内容块，照 Flyme 的 `layout_assearch_appgroup_single_app.xml`）|
 * | 把手 | `SideIndexHandle.attach`（`SideIndexHandle.kt:112-134`） |
 * | 手指 → 槽位 | [LetterIndex.slotAt] = `AppIndex.letterAt`（同一个算法） |
 *
 * **本批新增、预览里没有对应物的一项**：`side_index.blur_radius`（需求 #7）。预览一直按
 * 「未开模糊」那一对遮罩色画（见 [SideIndexPreviewInk.DIM_TOP]），而模糊半径**改变的是
 * 下层像素的模糊程度**，不是任何一块几何 —— 预览画不出「模糊的多少」，
 * 硬画一个假的模糊只会让预览不可信。所以它只在 `describe()` / 设置界面的文字里体现。
 */
fun sideIndexPreview(params: SideIndexPreviewParams): SideIndexPreview {
    // 屏幕尺寸在真机侧一路是 Int（`displayMetrics.widthPixels`，`AppIndex.BarLayout.screenW` 就是 Int）。
    // 这里保持同一口径：先取整，之后的加减乘除全在整数上做（截断方向与真机一致）。
    val screenW = (if (params.screenW.isFinite()) params.screenW else 0f).toInt()
    val screenH = (if (params.screenH.isFinite()) params.screenH else 0f).toInt()
    val density = finiteOr(params.density, 1f).let { if (it > 0f) it else 1f }

    /** 真机的 `dp()`：`(v * density).toInt()` —— **截断**（`SideIndexOverlay.kt:254` 同一条）。 */
    fun dp(v: Float): Int = (v * density).toInt()

    val bar = barLayout(params, screenW, screenH, density, ::dp)

    // 格子的几何（列数 × 格宽 × 格高）**先算**：它决定面板的宽度，而面板的左右位置、
    // 手指位置、气泡位置全都由面板宽度推出来。真机那边同样是「构造期算一次、处处用它」
    // （`SideIndexOverlay` 的 `cellW` / `cellH` / `COLUMNS` 三个字段）。
    val metrics = panelMetrics(params, screenW, screenH, density, ::dp)

    // 面板的左右与尺寸**与手指无关**（真机 `panelLeft` 只读 inset 与屏幕宽），
    // 所以先算出来，再用它算出「手指在面板中间」这个位置。
    val panelWidth = metrics.cellW * metrics.columns
    val panelLeft = panelLeft(params, screenW, panelWidth, bar, density, ::dp)

    // ── 手指在哪 ────────────────────────────────────────────────────────
    // 真机的手指位置来自输入分发线程；预览由场景推出来：
    //  - BAR：手指压在该槽位的字母格中心（正是真机 `BarLayout.inBar` 成立的那一段）；
    //  - PANEL：手指在面板横向中点、纵向由 `panelTouchFraction` 给（真机 `else` 支那一段）。
    val fingerX = when (params.scene) {
        SideIndexPreviewScene.BAR ->
            if (bar.slot >= 0) bar.letterCenterX else screenW / 2f
        SideIndexPreviewScene.PANEL -> panelLeft + panelWidth / 2f
    }
    val fingerY = when (params.scene) {
        SideIndexPreviewScene.BAR ->
            if (bar.slot >= 0) bar.letterCenterY(bar.slot) else screenH / 2f
        SideIndexPreviewScene.PANEL ->
            screenH * finiteOr(params.panelTouchFraction, 0.5f).coerceIn(0f, 1f)
    }

    val panel = panelLayout(
        params = params,
        screenW = screenW,
        screenH = screenH,
        density = density,
        dp = ::dp,
        panelLeft = panelLeft,
        fingerX = fingerX,
        fingerY = fingerY,
        // 手指在条上时高亮**恒为空**：真机那条分支里 `setHighlight(-1)` 是显式调的
        // （`SideIndexOverlay.kt:371`），不是「恰好没命中」。
        hitTestEnabled = params.scene == SideIndexPreviewScene.PANEL,
        metrics = metrics,
    )

    val bubble = bubbleLayout(
        params = params,
        screenW = screenW,
        panelLeft = panelLeft,
        panelContentWidth = panelWidth,
        panelTop = panel.top,
        letter = if (bar.slot >= 0) bar.letters[bar.slot] else "",
        dp = ::dp,
    )

    return SideIndexPreview(
        screenW = screenW.toFloat(),
        screenH = screenH.toFloat(),
        density = density,
        scene = params.scene,
        onRight = params.onRight,
        bar = bar,
        panel = panel,
        bubble = bubble,
        handle = handleLayout(params, screenW, screenH, ::dp),
        hotZones = SideIndexHotZone.zones(
            screenW = screenW.toFloat(),
            screenH = screenH.toFloat(),
            density = density,
            widthDp = finiteOr(params.handleWidthDp, DEFAULT_HANDLE_WIDTH_DP),
            allowLeft = params.bothSides || !params.onRight,
            allowRight = params.bothSides || params.onRight,
        ),
        fingerX = fingerX,
        fingerY = fingerY,
        // 高亮抬升量与「抬不起来要改用底色」：**只在真的有高亮格时才有意义**
        // （没有高亮格时抬升量是 0，但那不是「算成为 0」，不该当成兜底态）。
        highlightLiftPx = if (panel.highlighted >= 0) panel.highlightLiftPx else 0f,
        highlightLiftIdealPx = panel.highlightLiftIdealPx,
        highlightNoLiftFallback = panel.highlighted >= 0 && panel.highlightLiftPx <= 0f,
    )
}

/** 字母条：逐行复刻 `AppIndex.barLayout`（`AppIndex.kt:520-565`）。 */
private fun barLayout(
    params: SideIndexPreviewParams,
    screenW: Int,
    screenH: Int,
    density: Float,
    dp: (Float) -> Int,
): SideIndexPreviewBar {
    val width = dp(BAR_WIDTH_DP)
    val margin = dp(BAR_MARGIN_DP)
    val count = params.letters.size
    val safeScale = (finiteOr(params.letterScalePercent, DEFAULT_LETTER_SCALE_PERCENT) / 100f)
        .coerceIn(MIN_BAR_SCALE, MAX_BAR_SCALE)

    // Flyme: mLetterHeight = dpToPx(16) + 1 —— 那个 +1 是它的原样，照抄
    val base = ((BAR_ITEM_DP * safeScale * density).toInt() + 1).coerceAtLeast(1)
    val maxTotal = (screenH * BAR_MAX_HEIGHT_FRACTION).toInt()
    val item = if (count > 0 && base * count > maxTotal) {
        (maxTotal / count).coerceAtLeast(1)
    } else {
        base
    }
    val total = item * count
    // 位置：`span = (screenH - total).coerceAtLeast(0)`、`top = span × fraction`。
    // fraction = 0.5 与 Flyme 原来的 `(heightPx - total) / 2` 完全相等。
    val span = (screenH - total).coerceAtLeast(0)
    val offsetFraction = (finiteOr(params.letterOffsetPercent, DEFAULT_LETTER_OFFSET_PERCENT) / 100f)
        .coerceIn(0f, 1f)
    val top = (span * offsetFraction).toInt().coerceIn(0, span)

    // 字号：Flyme 固定 12dp；缩放后再按格高的 0.8 封顶（避免放大后字溢出格子）
    val textPx = minOf(
        (BAR_TEXT_DP * safeScale * density).toInt(),
        (item * BAR_TEXT_MAX_RATIO).toInt(),
    ).coerceAtLeast(1)

    return SideIndexPreviewBar(
        letters = params.letters,
        itemHeight = item.toFloat(),
        barTop = top.toFloat(),
        barWidth = width.toFloat(),
        barMargin = margin.toFloat(),
        itemWidth = dp(BAR_ITEM_WIDTH_DP).toFloat(),
        edgePadding = dp(BAR_EDGE_PADDING_DP).toFloat(),
        letterTextPx = textPx.toFloat(),
        screenW = screenW.toFloat(),
        screenH = screenH.toFloat(),
        onRight = params.onRight,
        // `letters.getOrNull(slot)` 的语义：越界不崩；空清单没有槽位
        slot = if (count == 0) -1 else params.slot.coerceIn(0, count - 1),
    )
}

/** 面板左边缘（px）。逐行复刻 `SideIndexOverlay.panelLeft`（`SideIndexOverlay.kt:1005-1012`）。 */
private fun panelLeft(
    params: SideIndexPreviewParams,
    screenW: Int,
    contentWidth: Int,
    bar: SideIndexPreviewBar,
    density: Float,
    dp: (Float) -> Int,
): Int {
    // 字母条与细条的位置始终留着，面板只平移不缩小（与 SideIndexOverlay.panelLeft 同一条）
    val barSlot = bar.barMargin.toInt() + bar.barWidth.toInt() + dp(PANEL_THUMB_GAP_DP)
    val inset = maxOf(insetPx(params, density, dp), barSlot)
    return if (params.onRight) {
        (screenW - inset - contentWidth).coerceAtLeast(0)
    } else {
        inset.coerceIn(0, (screenW - contentWidth).coerceAtLeast(0))
    }
}

/**
 * 面板的格子几何（px）：**生效列数 / 格宽 / 格高**。
 *
 * 逐行复刻 `SideIndexLook.panelMetrics`（hook 侧是唯一事实来源），
 * 与 `SideIndexOverlay` 的构造期字段 `cellW` / `cellH` / `COLUMNS` 是同一份算式。
 */
private class SideIndexPreviewMetrics(
    /** 用户要的列数（已夹进 3–7）。 */
    val requestedColumns: Int,
    /** **生效列数**。 */
    val columns: Int,
    /** 生效格宽（px）。 */
    val cellW: Int,
    /** 生效格高（px）。 */
    val cellH: Int,
    /**
     * 图标边长（px，已夹 24–80dp）。
     *
     * 放在这里而不是在 [panelLayout] 里再算一遍：格宽的**下限**就是由它推出来的
     * （`minCellW = 图标 + 2 × 内边距`），两处各算一遍就是「夹取用的是 A、画出来的是 B」
     * 这种没有任何报错的不一致。
     */
    val iconSize: Int,
)

/**
 * 联动夹取（需求 #5c）。**这一段是本条需求的核心**，规则按优先级排：
 *
 * ```
 * 0. 可用宽 A = **屏宽**（刻意不是「屏宽 − panel_inset」，理由见下）；
 * 1. 下限 minCellW = 图标边长 + 2 × 格子内边距   ← 「列不能窄到放不下图标」
 *    · 图标是用户自己调的（24–80dp），格宽必须装得下它；装不下就**抬格宽**（不缩图标，
 *      与本项目 cellHeightPx 的下限同一条取舍）；
 * 2. 生效列数 = min(用户要的列数, ⌊A / minCellW⌋)  ← 「列不能跑到屏幕外」
 *    · 只有「抬到 minCellW 之后放不下」时才减列数 —— 列数是用户的主诉，
 *      能保住就保住；保不住时减列而不是缩格（缩格会一路缩到图标放不下）；
 * 3. 格宽上限 = ⌊A / 生效列数⌋                     ← 面板总宽 ≤ A
 * 4. 生效格宽 = clamp(用户格宽, minCellW, 上限)
 * ```
 *
 * ## 为什么 A 是屏宽而不是「屏宽 − inset」
 *
 * `panelLeft` 已经把 `panel_inset` 定成**尽量满足的偏好**：贴左是
 * `inset.coerceIn(0, 屏宽 − 内容宽)`、贴右是 `(屏宽 − inset − 内容宽).coerceAtLeast(0)` ——
 * 两种写法都表示「装不下时 inset 让位，面板必须落在屏内」。夹取若以「屏宽 − inset」
 * 为界，就变成了**更强的**约束（为了让开 inset 而减列数/缩格宽），
 * 表现是「拖『面板边距』这个滑杆把列数改了」—— 一个滑杆改另一个滑杆的量，
 * 在设置界面上是最难理解的一类联动。所以夹取只以屏宽为界，inset 仍然按原语义让位。
 *
 * ## 证明（写在这里是因为它必须能被核对，而不是「大概不会出屏」）
 *
 * 对任意列数 `c` 与格宽 `w`（任何合法取值、任何屏幕）：
 *
 *  - `内容宽 = c × w ≤ c × ⌊屏宽 / c⌋ ≤ 屏宽`（第 3 步给出的上限，整数除法只会让它更小）；
 *  - 于是**面板右缘恒 ≤ 屏宽**：
 *    贴右时若 `内容宽 ≥ 屏宽 − inset`，则 `panelLeft = 0`、右缘 = `内容宽 ≤ 屏宽`；
 *    否则 `panelLeft = 屏宽 − inset − 内容宽 > 0`、右缘 = `屏宽 − inset ≤ 屏宽`。
 *    贴左时 `panelLeft ≤ 屏宽 − 内容宽`，右缘 = `panelLeft + 内容宽 ≤ 屏宽`。
 *    **任何组合下都不会有列落在屏幕外**（出屏的列是「看不见也点不到」，是本条需求点名要防的）。
 *  - 第 2 步保证 `c × minCellW ≤ 屏宽`，再结合第 4 步的 `w ≥ minCellW`
 *    （只要 `屏宽 ≥ minCellW`），于是**每一列都装得下最大 80dp 的图标**。
 *
 * ## 唯一一处让步，如实说明
 *
 * `屏宽 < minCellW`（连「一个图标 + 内边距」都比屏幕还宽）时，第 4 步的下限**让位**：
 * 格宽回到 `[1px, 上限]`，即保留用户要的格宽而不是被抬到满宽 —— **「不出屏」优先于
 * 「装得下图标」**：出屏的列用户根本碰不到，而溢出的图标只是难看（真机的 `LinearLayout`
 * 居中一个过宽的 child 本来也会溢出，见 `SideIndexOverlay.createCell` 的注释）。
 * 这一档在正常屏宽下到不了，并有单测钉住它的行为
 * （`narrowScreensShrinkTheGridInsteadOfPushingColumnsOffScreen`）。
 *
 * ## 默认值下逐位一致
 *
 * 默认 `columns = 5`、`cell_width = 66dp`、`icon_size = 50dp`、本机 1220px 宽 →
 * `minCellW = 162 + 18 = 180`、`生效列数 = min(5, ⌊1220/180⌋ = 6) = 5`、
 * `格宽 = clamp(214, 180, ⌊1220/5⌋ = 244) = 214` ——
 * 与改动前的「5 列 × 66dp」逐位相同。
 */
private fun panelMetrics(
    params: SideIndexPreviewParams,
    screenW: Int,
    screenH: Int,
    density: Float,
    dp: (Float) -> Int,
): SideIndexPreviewMetrics {
    val bottomSpace = if (params.bottomSpacePx.isFinite()) params.bottomSpacePx.toInt() else 0
    val cellH = cellHeightPx(
        params,
        density,
        dp,
        (screenH - bottomSpace).coerceAtLeast(1) / PANEL_MAX_ROWS,
    )

    // 1. 下限：列必须装得下图标（图标先按真机区间夹一次，与 createCell 用的是同一个值）
    val padH = dp(APP_CONTENT_PAD_H_DP)
    val iconPx = dp(
        finiteOr(params.iconSizeDp, DEFAULT_ICON_DP).coerceIn(MIN_ICON_DP, MAX_ICON_DP),
    ).coerceAtLeast(1)
    val minCellW = (iconPx + 2 * padH).coerceAtLeast(1)

    // 0. 可用宽 = 屏宽（见上面的理由）
    val avail = screenW.coerceAtLeast(1)

    // 2. 生效列数
    val requested = params.columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    val maxCols = (avail / minCellW).coerceAtLeast(1)
    val columns = minOf(requested, maxCols).coerceAtLeast(1)

    // 3/4. 生效格宽。屏宽连一列图标都放不下时下限让位给「不出屏」（见上面那段说明）：
    //      此时保用户要的格宽，而不是把它抬到满宽（抬到满宽是另一种「拖了没反应」）。
    val maxCellW = (avail / columns).coerceAtLeast(1)
    val lo = if (minCellW <= maxCellW) minCellW else 1
    val rawCellW = (finiteOr(params.cellWidthDp, DEFAULT_CELL_WIDTH_DP)
        .coerceIn(MIN_CELL_WIDTH_DP, MAX_CELL_WIDTH_DP) * density)
        .toInt()
        .coerceAtLeast(1)
    val cellW = rawCellW.coerceIn(lo, maxCellW)

    return SideIndexPreviewMetrics(
        requestedColumns = requested,
        columns = columns,
        cellW = cellW,
        cellH = cellH,
        iconSize = iconPx,
    )
}

/**
 * 面板内缩（px）= `SideIndexLook.panelInsetPx`（`SideIndexLook.kt:86-87`）：
 * 先按 0–120dp 夹取，再乘密度、取整、夹到 ≥0。
 */
private fun insetPx(params: SideIndexPreviewParams, density: Float, dp: (Float) -> Int): Int =
    dp(
        finiteOr(params.panelInsetDp, DEFAULT_PANEL_INSET_DP)
            .coerceIn(MIN_PANEL_INSET_DP, MAX_PANEL_INSET_DP),
    ).coerceAtLeast(0)

/** 面板：逐行复刻 `buildPanel` / `createCell` / `followerTop` / `applyThumb` / `assemblePanel`。 */
private fun panelLayout(
    params: SideIndexPreviewParams,
    screenW: Int,
    screenH: Int,
    density: Float,
    dp: (Float) -> Int,
    panelLeft: Int,
    fingerX: Float,
    fingerY: Float,
    hitTestEnabled: Boolean,
    /** 联动夹取的结果（见 [panelMetrics]）。真机侧同一份值来自 `SideIndexLook.panelMetrics`。 */
    metrics: SideIndexPreviewMetrics,
): SideIndexPreviewPanel {
    val cellW = metrics.cellW
    val columns = metrics.columns
    val groupW = cellW * columns

    // 内容行数：`(apps.size + 列数 - 1) / 列数`（`SideIndexOverlay.kt:717` 的后一支；
    // 前一支是「清单没就绪」的占位态，预览不画那种态 —— 见 SideIndexPreview 的类注释）。
    // ⚠️ 这里的**列数必须是生效列数**：用用户要的那个数会让「视口几行、细条出不出来、
    // 一屏几格」全部和真机排出来的格子对不上 —— 那正是「看到的格子 ≠ 碰到的格子」。
    val apps = params.appCount.coerceAtLeast(0)
    val contentRows = if (apps <= 0) 0 else (apps + columns - 1) / columns
    val viewportRows = contentRows.coerceIn(1, PANEL_MAX_ROWS)

    // 导航栏底部让位与格子高：**顺序与真机一致** —— 真机的 cellH 是在构造时算的
    // （`SideIndexOverlay` 的 `cellH` 字段），那里用的就是「屏高 − 底部让位」，
    // 所以这里也必须先有 bottomSpace 再算 cellH。
    val bottomSpace = if (params.bottomSpacePx.isFinite()) params.bottomSpacePx.toInt() else 0
    val cellH = metrics.cellH
    val viewportH = cellH * viewportRows
    val maxScroll = ((contentRows - viewportRows) * cellH).coerceAtLeast(0)

    // 面板顶：`followerTop`（`SideIndexOverlay.kt:1037-1041`）—— 手指 y − 70dp、两头夹。
    // 真机原文是 `(y - dp(70)).toInt().coerceIn(0, maxTop)`：**先减再截断**。
    // 注意 `dp(70)` 是 **Int**（70 × 3.25 = 227.5 → 227），这里保持同一条。
    val maxTop = (screenH - viewportH - bottomSpace).coerceAtLeast(0)
    val rawTop = (if (fingerY.isFinite()) fingerY else 0f) - dp(PANEL_FINGER_OFFSET_DP)
    val top = rawTop.toInt().coerceIn(0, maxTop)

    // 图标尺寸与圆角：`SideIndexLook.iconSizePx` / `iconRadiusPx`（`SideIndexLook.kt:78-83`）。
    // 大小取自 [metrics]（格宽下限用的就是同一个数，见那个字段的注释）。
    val iconSize = metrics.iconSize
    val iconRadius = dp(
        finiteOr(params.iconRadiusDp, 0f).coerceIn(MIN_ICON_RADIUS_DP, MAX_ICON_RADIUS_DP),
    ).coerceAtLeast(0)

    val padH = dp(APP_CONTENT_PAD_H_DP)
    val nameTopMargin = dp(NAME_TOP_MARGIN_DP)

    // 内容块高度，以及它在格子里的居中位置。**逐行对应真机**：真机的格子
    // `gravity = CENTER`（Flyme 的 cell xml）+ 内容是 `wrap_content`，所以「图标 + 名字」
    // 整块在格里垂直居中，上下各留 `(cellH − contentH) / 2`。
    // ⚠️ 改动前这一处是「显示名字时图标贴格子顶」—— 那是抄错的形态（真机是居中的），
    // 也正是「顶行高亮被裁」的根因：贴顶的格子里没有上方余量，一抬手就顶出格子。
    val contentH = contentHeightPx(params, density, iconSize)
    // 抬升量：**算出来的**（上限 = 格高/6，再夹到格内余量内），见 sideIndexHighlightLiftPx
    val lift = sideIndexHighlightLiftPx(cellH, contentH, (CONTENT_FIT_SAFETY_DP * density).toInt())
    val liftIdeal = sideIndexHighlightLiftIdealPx(cellH)
    // 图标在格子里横向居中：容器去掉 padding 之后按 `gravity = CENTER_HORIZONTAL` 居中
    // （`SideIndexOverlay.kt:945` / `974-981`）。图标比内容盒还宽时偏移为负 ——
    // 真机就是这样（LinearLayout 居中一个过宽的 child），照抄，不替它夹。
    val iconInset = (cellW - 2 * padH - iconSize) / 2

    // 最后一行不满时的对齐：`assemblePanel` 的 `rowGravity = if (onRight) END else START`
    // （`SideIndexOverlay.kt:867-875`）。整排要贴到「贴边侧」，所以列号得重算 ——
    // 直接写 `index % 5` 会把最后一行的格子画到真机的空位上。
    val partial = if (apps <= 0) 0 else apps % columns
    val lastRow = contentRows - 1

    val cells = ArrayList<SideIndexPreviewCell>(apps)
    for (i in 0 until apps) {
        val row = i / columns
        val column = if (params.onRight && partial != 0 && row == lastRow) {
            columns - partial + (i % columns)
        } else {
            i % columns
        }
        val cellLeft = panelLeft + column * cellW
        val cellTop = top + row * cellH
        val cellRect = ScreenRect(
            cellLeft.toFloat(),
            cellTop.toFloat(),
            (cellLeft + cellW).toFloat(),
            (cellTop + cellH).toFloat(),
        )
        val iconLeft = cellLeft + padH + iconInset
        // 内容块在格内**纵向居中**（`gravity = CENTER`，Flyme 的 cell xml —— 显示名字与
        // 无字模式都是这一条；无字模式尤其明显：名字没了、空档上下均分）。
        // 整数除法与 `LinearLayout` 的居中算式同量级，个位像素的差异写在报告的「已知差异」里。
        // 内容块居中：用**诚实**的内容块高（safety 只是抬升量的保守量，不参与居中）
        val iconTop = cellTop + ((cellH - contentH) / 2).coerceAtLeast(0)
        val iconRect = ScreenRect(
            iconLeft.toFloat(),
            iconTop.toFloat(),
            (iconLeft + iconSize).toFloat(),
            (iconTop + iconSize).toFloat(),
        )
        cells.add(
            SideIndexPreviewCell(
                index = i,
                row = row,
                column = column,
                cellRect = cellRect,
                iconRect = iconRect,
                // 名字没了就不画，`nameTop` 仍然照「假如有名字」报出来 ——
                // 绘制层只看 [SideIndexPreviewPanel.namesVisible]。
                nameTop = iconRect.bottom + nameTopMargin,
                inViewport = row < viewportRows,
            ),
        )
    }

    // 细条：`applyThumb`（`SideIndexOverlay.kt:1076-1092`）。它是**面板容器的子节点**
    // （因此跟着面板的 translationY 一起走），贴在面板外侧那条缝里：
    // 贴右时 `groupW - 4dp`、贴左时 0。只有内容真的超过视口时才出现。
    val thumbW = dp(THUMB_W_DP)
    val thumb = if (contentRows > viewportRows) {
        val thumbLeft = panelLeft + (if (params.onRight) (groupW - thumbW).coerceAtLeast(0) else 0)
        val thumbTop = top + dp(THUMB_TOP_DP)
        ScreenRect(
            thumbLeft.toFloat(),
            thumbTop.toFloat(),
            (thumbLeft + thumbW).toFloat(),
            (thumbTop + dp(THUMB_H_DP)).toFloat(),
        )
    } else {
        null
    }

    return SideIndexPreviewPanel(
        columns = columns,
        requestedColumns = metrics.requestedColumns,
        cellW = cellW.toFloat(),
        cellH = cellH.toFloat(),
        contentWidth = groupW.toFloat(),
        contentRows = contentRows,
        viewportRows = viewportRows,
        left = panelLeft.toFloat(),
        top = top.toFloat(),
        iconSize = iconSize.toFloat(),
        iconRadius = iconRadius.toFloat(),
        contentPadH = padH.toFloat(),
        nameTopMargin = nameTopMargin.toFloat(),
        contentH = contentH.toFloat(),
        highlightLiftPx = lift.toFloat(),
        highlightLiftIdealPx = liftIdeal.toFloat(),
        selectBgRadius = dp(CELL_SELECT_BG_RADIUS_DP).toFloat(),
        nameFontSp = NAME_SP,
        namesVisible = !params.hideAppNames,
        maxScrollPx = maxScroll.toFloat(),
        cells = cells,
        highlighted = if (hitTestEnabled) hitTest(cells, fingerX, fingerY) else -1,
        thumb = thumb,
        thumbTopGap = dp(THUMB_TOP_DP).toFloat(),
        thumbRadius = dp(THUMB_RADIUS_DP).toFloat(),
    )
}

/**
 * 格子高（px）。**逐行复刻** `SideIndexLook.cellHeightPx`（`SideIndexLook.kt`）——
 * 那个函数是真机侧的唯一实现，这里保持同一条算式与**同一条夹取顺序**：
 *
 * ```
 * 1. 用户值（`side_index.cell_height`）夹进 88..132dp；
 * 2. 无字模式减掉名字块（名字不占位了，格子要跟着矮）；
 * 3. 抬到下限：≥ 图标边长 + 名字块（格子装不下就抬格子，**不缩图标**）；
 * 4. 压到上限：≤ 可用屏高 ÷ 3（视口最多 3 行，超过就有一半在屏幕外）。
 * ```
 *
 * 与真机一致的那三条边界值也要一致：默认 `cell_height = 108dp`、
 * 默认图标 50dp、默认显示名字 → `108 - 0` 后夹进 `[50 + 21, 屏高/3]` = 108 ——
 * **与改动前的固定 108dp 逐位相同**（这是「不动它 = 现在完全一样」的证据）。
 *
 * @param maxCellHeightPx 可用屏高 ÷ 视口最大行数（真机在 `SideIndexOverlay` 的
 *   `cellH` 字段里算，用的是 `displayMetrics.heightPixels − bottomSpace`）。
 */
private fun cellHeightPx(
    params: SideIndexPreviewParams,
    density: Float,
    dp: (Float) -> Int,
    maxCellHeightPx: Int,
): Int {
    val iconPx = dp(
        finiteOr(params.iconSizeDp, DEFAULT_ICON_DP).coerceIn(MIN_ICON_DP, MAX_ICON_DP),
    ).coerceAtLeast(1)
    val nameBlockDp = if (params.hideAppNames) 0f else NAME_BLOCK_DP
    val raw = (finiteOr(params.cellHeightDp, DEFAULT_CELL_HEIGHT_DP)
        .coerceIn(MIN_CELL_HEIGHT_DP, MAX_CELL_HEIGHT_DP) * density)
        .toInt()
        .coerceAtLeast(1)
    val floor = (iconPx + nameBlockDp * density).toInt().coerceAtLeast(1)
    val ceiling = maxCellHeightPx.coerceAtLeast(1)
    // 只有**无字模式**才从用户要的格高里减掉名字块（108dp 这个默认值已经把名字算在里面了，
    // 显示名字时再减一次会把默认格高变成 87dp —— 与真机 `SideIndexLook.cellHeightPx` 逐字对应）。
    val shrunk = if (params.hideAppNames) (raw - NAME_BLOCK_DP * density).toInt() else raw
    return shrunk.coerceAtLeast(floor).coerceIn(1, ceiling)
}

/**
 * 格子里**内容块**的高度（px）：图标 + 名字块（无字模式下只有图标）。
 *
 * 逐行复刻 `SideIndexLook.panelMetrics` 的 `contentH`。名字块用 [NAME_BLOCK_DP]（17+4dp）
 * 这个**模型值**，而真机布局用的是字体量出来的行高 —— 两者差个位 dp（Roboto 12sp 含
 * font padding 约 17.8dp），这一点写在报告的「已知差异」里；真机那边用
 * `clipChildren = false`（照 Flyme 的 cell xml）把这个量级的误差吸收掉。
 */
private fun contentHeightPx(params: SideIndexPreviewParams, density: Float, iconPx: Int): Int =
    iconPx + if (params.hideAppNames) 0 else (NAME_BLOCK_DP * density).toInt()

/**
 * 高亮格子的**抬升量**（px）—— 需求：**算出来的，不是写死的 18dp**。
 *
 * ```
 * 上限 = 格高 / 6              （Flyme 的 18dp ÷ 108dp；格高可调时跟着变）
 * 余量 = (格高 − 内容块高) / 2   （内容块在格里居中，见 [contentHeightPx]）
 * 抬升 = min(上限, 余量)
 * ```
 *
 * ## 约束为什么是「格子内部的余量」
 *
 * 真机抬的是**格子里面的内容块**（Flyme 的 `mContentLayout`），不是整个格子 ——
 * 所以只要抬升量不超过内容块在格子上方的余量，内容块就永远待在**格子内部**，
 * 与它在第几行、列表滚到哪里**完全无关**。这是比「按行夹取」更强的保证：
 * 顶行、中间行、末行用的是**同一个**抬升量，而且都不可能越界。
 *
 * ## 为什么不按「这一格上方还有多少空间」夹（那是产品经理的初版方向）
 *
 * 那样算的话第一行上方空间是 0（面板顶 = 手指 y − 70dp、格高 ≥ 88dp，
 * 所以**正常使用时手指永远落在第一行**）→ 第一行永远抬不起来 → 这个反馈整体失效，
 * 只能靠底色代替。而真机（Flyme）能抬起第一行，靠的是内容块在格子内部居中留下的余量。
 * 所以我们照它的结构来。
 *
 * ## 与 Flyme 的差异，如实写
 *
 *  - Flyme：固定 18dp；格子固定 108dp（余量恰好 ≈18dp），所以刚好躲开裁切；
 *  - 我们：格高可调（88–132dp），固定 18dp 在格高小的时候会顶出格子（被 ScrollView 裁），
 *    所以改成「格高 / 6，再夹进余量」。**默认档（108dp 格 / 50dp 图标 / 显示名字）下
 *    抬升量逐位等于 Flyme 的 18dp**（`dp(108)/6 = (108×3.25).toInt()/6 = 351/6 = 58`
 *    = `(18×3.25).toInt() = 58`）；格高调小/图标调大时它自动变小，绝不越界。
 *
 * @param cellH 生效格高（px）
 * @param contentH 内容块高（px，[contentHeightPx]）
 */
internal fun sideIndexHighlightLiftPx(cellH: Int, contentH: Int, safetyPx: Int): Int {
    val ideal = sideIndexHighlightLiftIdealPx(cellH)
    // 余量里扣掉 safetyPx：内容块高的模型误差（名字行模型 17dp vs 真机含 font padding 约 17.8dp）
    // —— 与 hook 侧 PanelMetrics.contentSlackPx 同一份算式。
    val slack = ((cellH - contentH - safetyPx) / 2).coerceAtLeast(0)
    return minOf(ideal, slack)
}

/** [sideIndexHighlightLiftPx] 的上限那一半（px）：格高 / 6。 */
internal fun sideIndexHighlightLiftIdealPx(cellH: Int): Int =
    (cellH / HIGHLIGHT_LIFT_DIVISOR).coerceAtLeast(0)

/** 手指命中的格子下标。逐行对应 `SideIndexOverlay.hitTest`（`SideIndexOverlay.kt:1366-1374`）。 */
private fun hitTest(cells: List<SideIndexPreviewCell>, x: Float, y: Float): Int {
    // 真机是 `rect.contains(x.toInt(), y.toInt())`（`Rect` 的整数包含）——
    // 这里保持同一条：先把手指取整，再判 `left <= x < right`。
    if (!x.isFinite() || !y.isFinite()) return -1
    val xi = x.toInt().toFloat()
    val yi = y.toInt().toFloat()
    for (cell in cells) {
        if (cell.cellRect.contains(xi, yi)) return cell.index
    }
    return -1
}

/** 大字母气泡：`updateBubble` + `placeBubble` + `applyBubbleVisibility`（`SideIndexOverlay.kt:1267-1337`）。 */
private fun bubbleLayout(
    params: SideIndexPreviewParams,
    screenW: Int,
    panelLeft: Int,
    panelContentWidth: Int,
    panelTop: Float,
    letter: String,
    dp: (Float) -> Int,
): SideIndexPreviewBubble {
    val size = dp(BUBBLE_DP)
    val gap = dp(BUBBLE_GAP_DP)
    val topGap = dp(BUBBLE_TOP_GAP_DP)
    val safeEdge = dp(SAFE_EDGE_DP)

    // 横向：贴面板**靠屏幕边那一侧**的边（贴右 → 面板右缘、贴左 → 面板左缘），
    // 再让开 size + gap；最后夹进屏幕（`updateBubble:1293-1298`）
    val panelEdgeX = if (params.onRight) panelLeft + panelContentWidth else panelLeft
    val rawX = if (params.onRight) panelEdgeX - size - gap else panelEdgeX + gap
    val left = rawX.coerceIn(0, (screenW - size).coerceAtLeast(0))
    // 纵向：面板顶往上让出 50dp + 8dp（`placeBubble:1317`）
    val top = panelTop.toInt() - size - topGap

    // 可见的两条规则（`applyBubbleVisibility:1331-1337`）：
    //  1. 这个字母下一个应用都没有 → 不显示（`updateBubble` 的 `show` 参数）；
    //  2. 顶部空间不够 → 不显示（面板顶被夹到 0 时，气泡只能压在图标上或出屏，两个都不好）。
    val hasApps = params.appCount > 0
    val room = top >= (size + topGap + safeEdge)
    return SideIndexPreviewBubble(
        text = letter,
        size = size.toFloat(),
        fontSp = BUBBLE_SP,
        left = left.toFloat(),
        top = top.toFloat(),
        gap = gap.toFloat(),
        topGap = topGap.toFloat(),
        visible = hasApps && room,
        // 按压淡入时长：夹到 0–400ms（与 SideIndexLook.bubbleAnimDurationMs 同区间；
        // 非有限值退回默认 150ms —— 预览不许产出 NaN）。
        pressFadeMs = finiteOr(params.bubbleAnimMs, DEFAULT_BUBBLE_ANIM_MS)
            .coerceIn(MIN_BUBBLE_ANIM_MS, MAX_BUBBLE_ANIM_MS),
        // 特殊槽位用图标：开关开着 **且** 这一槽是特殊槽（★ = 最近、♥ = 收藏）。
        // 真机还有第三条「图标真的取到了」，预览没有真实图标 —— 那条差异写在报告里。
        iconSlot = params.bubbleIconSlots && letter in SPECIAL_SLOTS,
    )
}

/** 边缘把手：`SideIndexHandle.attach`（`SideIndexHandle.kt:112-134`）。 */
private fun handleLayout(
    params: SideIndexPreviewParams,
    screenW: Int,
    screenH: Int,
    dp: (Float) -> Int,
): SideIndexPreviewHandle? {
    if (!params.showHandle) return null
    // `Math.max(1, (int)(screenHeight * ratio))` —— Appindex 原文，比例夹到 10%–100%
    val ratio = (finiteOr(params.handleHeightPercent, DEFAULT_HANDLE_HEIGHT_PERCENT) / 100f)
        .coerceIn(MIN_HANDLE_HEIGHT_RATIO, MAX_HANDLE_HEIGHT_RATIO)
    val height = (screenH * ratio).toInt().coerceAtLeast(1)
    val offset = (finiteOr(params.handleOffsetPercent, DEFAULT_HANDLE_OFFSET_PERCENT) / 100f)
        .coerceIn(0f, 1f)
    val top = ((screenH - height) * offset).toInt()
    // `(widthDp * density).toInt().coerceAtLeast(1)` —— 0 也是「有一个 1px 的窗口」，不是「没有」
    val width = dp(finiteOr(params.handleWidthDp, DEFAULT_HANDLE_WIDTH_DP)).coerceAtLeast(1)
    val rect = if (params.onRight) {
        ScreenRect(
            (screenW - width).toFloat(),
            top.toFloat(),
            screenW.toFloat(),
            (top + height).toFloat(),
        )
    } else {
        ScreenRect(0f, top.toFloat(), width.toFloat(), (top + height).toFloat())
    }
    return SideIndexPreviewHandle(rect)
}
