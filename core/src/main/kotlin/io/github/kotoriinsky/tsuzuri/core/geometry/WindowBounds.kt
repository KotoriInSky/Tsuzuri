package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.max

/**
 * 「小窗摆放」—— `window_preset` + 四个 `window_*` 的**唯一一份几何**。
 *
 * ## 为什么要有这个文件（而不是各写各的）
 *
 * 这五个参数在 `Catalog` 里声明了、界面上能拖、**预览也把窗口矩形画出来**，
 * 但在接线之前 hook 一次都没读过它们 —— 于是它们是死旋钮，而且预览在说谎
 * （画了一个永远不会生效的框）。
 *
 * 接线时必须避免的是**第二种**说谎：hook 自己写一份公式去算 bounds。只要有两份实现，
 * 它们迟早漂移，表现就是「预览画在这里、真机摆在那里」—— 这个项目已经在别处吃过这种亏。
 * 所以：
 *
 * ```
 * 预览（ui）  ──► WindowBounds.resolve ──┐
 *                                      ├──► 同一个 ScreenRect
 * hook 侧     ──► WindowBounds.resolve ──┘    （hook 自己把 ScreenRect 转成 android.graphics.Rect）
 * ```
 *
 * ## 语义（与 `Catalog` 的文案、`FanPreviewParams` 的 KDoc 逐字对齐）
 *
 * | 入参 | 基准 | 含义 |
 * | --- | --- | --- |
 * | `widthPercent` | **屏幕宽** | 小窗宽 = `screenW × 它%` |
 * | `heightPercent` | **屏幕高** | 小窗高 = `screenH × 它%`（与宽不同基准，是刻意的：竖屏下高度才够用） |
 * | `xPercent` | 屏幕宽 | 窗口**中心**在**可移动区间**里的比例（0 = 贴左，100 = 贴右，50 = 居中） |
 * | `yPercent` | 屏幕高 | 窗口**中心**在**可移动区间**里的比例（0 = 贴顶，100 = 贴底，50 = 居中） |
 *
 * ## ⚠️ x/y 的语义改过两次，现在是第三版 —— 两版都是被实测逼出来的
 *
 * - **v1「左/上边缘的绝对位置」**：用户猜不出来。想让 80% 宽的窗口居中要填 `10`，
 *   填 `50` 反而贴右。
 * - **v2「中心的绝对位置」**：`50` 终于是居中了，但**行程是死的** ——
 *   窗口占 80% 屏宽时水平只有 20% 屏宽的自由行程，而量程是 0–100%，
 *   于是 `x ∈ [0, 40]` 全被夹成贴左、`x ∈ [60, 100]` 全被夹成贴右。
 *   **80% 的行程拖了不动**，用户只能认为「这个参数坏了」。
 * - **v3「中心在可移动区间里的比例」（当前）**：`left = (screenW − w) × x% / 100`。
 *   0 贴左、100 贴右、50 居中，**整段行程都有效** —— 每拖一点，窗口就动一点。
 *   参考实现 HyperGesture 的 `windowPositionX/Y` 用的就是这个口径。
 *
 * 迁移（`Keys.CURRENT_CONFIG_VERSION`）由
 * [ConfigMigration][io.github.kotoriinsky.tsuzuri.core.ConfigMigration] 负责，
 * 两段换算分别是 [centerPercentFromEdgePercent]（v1→v2）与 [ratioPercentFromCenterPercent]（v2→v3），
 * **用户看到的窗口位置一个像素都不变**。
 *
 * ## 夹取规则（四条，缺一不可）
 *
 * 1. **尺寸先夹到 `[0, 屏幕尺寸]`，再夹位置**。反过来的话「窗口比屏幕还大」会算出负的
 *    `left`，而负坐标在 `setLaunchBounds` 那边是「多屏 / 虚拟屏」的语义，会把窗口丢出主屏。
 * 2. **位置夹到 `[0, 屏幕 − 窗口]`**，即整块矩形不出屏。
 *    v3 的公式本来就只落在 `[0, 屏幕 − 窗口]` 内（它乘的就是这段行程），
 *    所以这条夹取现在是**兜底**：存储里的值可能被手改成负数或 >100，
 *    那种情况下仍要保证矩形不出屏 —— 只是不再有「量程里一大段被夹成同一个值」的问题。
 * 3. **非有限值（NaN / Inf）一律退化成空矩形**，绝不产出 NaN 坐标：`setLaunchBounds`
 *    收到 NaN 会整块窗口位置未定义，而预览画 NaN 是**空白**（用户只会说「预览没了」，
 *    没有任何错误提示，是最难查的一类故障）。
 * 4. 上面的表达式顺序与「接线前的那一份」保持逐字一致（浮点结合律不成立，换个写法就可能差
 *    1ulp，而 `WindowBoundsTest` 钉的是具体数值）。
 *
 * 这几条边界都由 `WindowBoundsTest` 钉住，真机语言环境是竖屏，但公式**不假设朝向**
 * （横屏 / 折叠展开只是换一组 `screenW × screenH`，测试里两种朝向都过一遍）。
 *
 * ## 与平台最小尺寸的关系（**已知未闭合项，别当成 bug**）
 *
 * 矩形小于 [MIN_SIDE_PX] 时 [isUsable] 为 `false`，调用方按「不覆写、交给系统摆放」处理。
 * 这是**保守方向**的取舍：宁可退回 MIUI 自己的摆放，也不要让一个可疑的矩形去驱动小窗。
 *
 * ROM 自己还有一个「小窗最小尺寸」的硬限制（小于它 WM 会把窗口撑大，撑大方向是
 * 右下，于是窗口可能从右下角出屏）。**那个值本机没有实测过**，所以这里**不硬编码**它
 * —— 编一个没有依据的常量会让预览画出用户没配置过的尺寸（又是「预览说谎」）。
 * 真机实测出该值之后的收口方式：把它作为 `resolve` 的入参（而不是常量），
 * 这样预览与 hook 仍然共用同一份实现，两边一起变。
 */
object WindowBounds {

    // ─────────────────────────── 预设（三选一）───────────────────────────
    //
    // `window_preset` 的三个取值。**取舍理由**（用户 2026-09-19 拍板）：
    // 原来的形态是一个布尔总开关（关 = 交给系统 / 开 = 用四个值），于是「想用 MIUI 的摆放」
    // 和「想用我们自己量出来的那套值」只能二选一 —— 而后者是 Flyme 实测值，
    // 恰恰是这个特性存在的意义（默认就该是它）。两个预设并列之后，
    // 「系统默认」变成一个**和 Flyme 对等**的选项，而不是「把功能关掉」。

    /** `window_preset` = 系统默认：`bounds = null`，交给 MIUI 自己的入口算摆放。 */
    const val PRESET_SYSTEM = "system"

    /**
     * `window_preset` = Flyme：用 [DEFAULT_WIDTH_PERCENT] 那四个**实测值**
     * （80 / 63.5 / 50 / 41.7），也就是参考机上那块窗口的位置与大小。
     *
     * 它不读四个滑杆：那四个是 [PRESET_CUSTOM] 的专属调节。取的值就是
     * `Catalog` 里那四个参数的 `default`，来源仍然只有这一处。
     */
    const val PRESET_FLYME = "flyme"

    /** `window_preset` = 自定义：四个滑杆说了算。 */
    const val PRESET_CUSTOM = "custom"

    /** `Catalog` 到 `hook` 的兜底都用这一个顺序（下拉里的顺序也照它）。 */
    val PRESETS: List<String> = listOf(PRESET_SYSTEM, PRESET_FLYME, PRESET_CUSTOM)

    /**
     * `window_preset` 的出厂默认 —— **从 `window_custom` 的旧默认平移过来的**。
     *
     * `window_custom` 的默认在 2026-09-17 从 `false` 改成了 `true`，理由写在 `Catalog`
     * 那段长注释里（默认由 MIUI 摆放，与「像 Flyme」这个预期不符）。这一版做语义平移：
     *
     * | 旧配置 | 新预设 |
     * | --- | --- |
     * | `window_custom = true`（旧默认） | [PRESET_FLYME] |
     * | `window_custom = false` | [PRESET_SYSTEM] |
     * | 没设过（`null`） | [PRESET_FLYME]（= 跟旧默认走） |
     */
    const val DEFAULT_PRESET: String = PRESET_FLYME

    /**
     * 预设 → 那块矩形。`null` = **不覆写**，交给系统（只有 [PRESET_SYSTEM] 会给出 `null`）。
     *
     * ## 为什么这套判别只在 `core` 里有这一份
     *
     * hook 与预览**都要**回答同一个问题：「现在这块窗口在哪」。hook 不读它就摆错，
     * 预览不读它就画出「系统会自己决定」的框（或者更糟：画一个用户配了但当前预设
     * 根本不采纳的框 —— 那就是「预览说谎」）。
     *
     * 上一版这条判别散在 hook（`WindowPlacement.resolve` 里读那个布尔决定要不要
     * 读滑杆）与预览（`FanPreview.kt` 里再读一次 `windowCustom`）两处，
     * 而**语义一变就要同时改两处**，漏一处的表现是「预览画 Flyme 那块、真机摆系统那块」。
     * 收进这里之后，两侧传的是同一批输入、拿到的是同一个答案。
     *
     * ## 认不出的取值怎么办
     *
     * 与「读不到」**同一个处置**：都退回 [DEFAULT_PRESET]。两侧必须是同一条路，
     * 否则表现是「同样的配置在两台机器上一个覆写一个不覆写」，而且没有任何报错。
     *
     * ⚠️ 这里退回的是 [PRESET_FLYME]（覆写），**不是** [PRESET_SYSTEM]（不覆写）——
     * 看着像「保守方向的反面」，其实与「读不到」一致才是保守：
     * `Catalog` 的 `default` 也是 [PRESET_FLYME]，所以「键没写进配置」在设置界面显示的是
     * Flyme 那一档。如果这里改成退回 `system`，一台**从没设过这一项**的机器会变成
     * 「界面说 Flyme、真机不覆写」—— 这才是真正的不一致。想让「认不出 → 交给系统」
     * 成立，得先让 `Catalog` 的 `default` 变成 `system`（那是一次产品决策，不是这里的兜底）。
     *
     * @param sliderWidthPercent 等四个滑杆的当前值（只在 [PRESET_CUSTOM] 下被采用）
     */
    fun resolvePreset(
        preset: String?,
        screenW: Float,
        screenH: Float,
        sliderWidthPercent: Float,
        sliderHeightPercent: Float,
        sliderXPercent: Float,
        sliderYPercent: Float,
    ): ScreenRect? {
        val p = preset?.takeIf { it in PRESETS } ?: DEFAULT_PRESET
        return when (p) {
            // 系统默认：**一个滑杆都不读**（它们在这个预设下不参与运算）。
            PRESET_SYSTEM -> null
            // Flyme：四个实测值。与 `defaultsFor()` 同一个矩形，不另写一遍常量。
            // ⚠️ 必须走 `defaultsFor`（不是 `defaults`）：横屏下形状与位置是另一条公式
            // （[landscape]），走 `defaults` 会得到一块 2136×775 的横条 —— 见那边的实测。
            PRESET_FLYME -> defaultsFor(screenW, screenH)
            else -> resolveFor(
                screenW = screenW,
                screenH = screenH,
                widthPercent = sliderWidthPercent,
                heightPercent = sliderHeightPercent,
                xPercent = sliderXPercent,
                yPercent = sliderYPercent,
            )
        }
    }

    // ─────────────────────────── 出厂默认值 ───────────────────────────
    //
    // **唯一一份**：`Catalog` 的参数 `default` 引用这里，hook 的「键没声明」兜底也引用这里。
    // 从前 hook 里原样抄了一份（注释写着「抄的是 Catalog.kt」），而两处默认值一旦不一致，
    // 表现是「设置界面显示 A、真机按 B 摆」—— 编译、日志、装机全正常。
    //
    // 这几个数**不是拍脑袋的**：2026-09-17 在 Flyme 参考机（MEIZU 22 / 1200×2670）上实测，
    // 底角斜滑拉起真实小窗后用像素阈值测白色面板的包围盒（截图 `build/ui-shots/flyme_fan.png`）：
    //
    //   尺寸 960 × 1696 px = 80% 屏宽 × 63.5% 屏高
    //   左右留边**各 10%**（完全对称）→ 中心在 50.0% 屏宽
    //   上边距 15.2% / 下边距 21.3% → 中心在 46.9% 屏高
    //
    // 所以「Flyme 的样子」= 80 / 64 / 50 / 47。改这几个数之前**先回去量一遍**，
    // 不要按审美调。

    /** `window_width_percent` 的出厂默认：屏幕宽的 80%。 */
    const val DEFAULT_WIDTH_PERCENT = 80f

    /*
     * ## 同一组默认值在不同屏幕上 —— 这就是「百分比」的意思
     *
     * | 屏幕 | 窗口（px） | 四条留边 |
     * | --- | --- | --- |
     * | MEIZU 22，1200×2670（实测来源） | 960 × 1696 | 左 10.0% / 上 15.2% / 右 10.0% / 下 21.3% |
     * | Xiaomi 17 Pro，1220×2656（移植目标） | 976 × 1686 | 左 10.0% / 上 15.2% / 右 10.0% / 下 21.3% |
     *
     * **相对几何逐项一致**，这就是「适配不同分辨率」的定义：参数存的是百分比，
     * 分辨率只影响换算出来的像素数。反过来如果存绝对像素（Flyme 的 960×1696），
     * 换到 1080p 的机器上会变成 89% 宽 —— 那才是真的不对。
     *
     * 唯一不会一致的是**窗口自身的长宽比**：它会随屏幕比例差 2% 左右
     * （两台机器 2.177 vs 2.225）。那不是"没复刻"，是屏幕本身形状不同；
     * 想让形状也一致，该改的是"高度"那一项的默认值（本机约 64.9%），
     * 代价是同一份默认值在别的屏幕上就不再等于 Flyme 的比例了。
     */

    /**
     * `window_height_percent` 的出厂默认：屏幕高的 **63.5%**。
     *
     * 刻意保留小数：这是量出来的数，不是设计值。取整成 64 会让窗口比 Flyme 高 13px
     * （在 2656 高的屏上），也就意味着「默认值和 Flyme 不一样」—— 这正是这个参数
     * 存在的意义（照抄 Flyme 的观感），所以不取整。
     */
    const val DEFAULT_HEIGHT_PERCENT = 63.5f

    /**
     * `window_x_percent` 的出厂默认：水平居中。
     *
     * 两个口径下都是 50，因为居中就是「可移动区间的正中」——
     * Flyme 实测左右留边各 10%（对称），所以比例是 `120 / (1200 − 960) = 50%`。
     */
    const val DEFAULT_X_PERCENT = 50f

    /**
     * `window_y_percent` 的出厂默认：垂直方向比正中略偏上。
     *
     * 42 是**比例**口径下的实测值：Flyme 那块窗口的上边距 406px，
     * 而上下可移动区间 = 屏高 2670 − 窗高 1696 = 974px → `406 / 974 = 41.68%`。
     * （同一个位置在 v2 的「中心绝对位置」口径下是 46.9，别把两个口径的数混用。）
     */
    const val DEFAULT_Y_PERCENT = 41.7f

    // ─────────────────── 语义变更**之前**的默认值（只给迁移用）───────────────────
    //
    // v1（边缘语义）下用户「没设过」时实际生效的值。迁移要用它们来还原「当年看到的画面」：
    // 用户只设了 x 没设宽度时，那个 x 是按**当年的**默认宽度解释的，不是按今天的新默认。
    // 这几个常量一旦删掉，那批用户的窗口位置就会莫名其妙跳一下。

    /** v1 的 `window_width_percent` 默认。 */
    const val LEGACY_DEFAULT_WIDTH_PERCENT = 70f

    /** v1 的 `window_height_percent` 默认。 */
    const val LEGACY_DEFAULT_HEIGHT_PERCENT = 70f

    /** v1 的 `window_x_percent` 默认（= 左边缘落在屏幕 15% 处）。 */
    const val LEGACY_DEFAULT_X_PERCENT = 15f

    /** v1 的 `window_y_percent` 默认。 */
    const val LEGACY_DEFAULT_Y_PERCENT = 15f

    /**
     * **v2** 的 `window_y_percent` 默认（「中心绝对位置」口径）。
     * 只给 v2→v3 的迁移用：用户没设过 y 时，当年生效的是这个数。
     */
    const val V2_DEFAULT_Y_PERCENT = 47f

    /**
     * 可用的最小边长（px）。低于它的矩形**没有任何意义**（画不出、点不到），
     * 而且多半说明配置读坏了（比如键被手改成了 0）。
     *
     * 取 `1` 而不是某个「好看的」最小值：这是「数据是否可用」的判据，
     * 不是审美或平台限制 —— 见类注释最后一段。
     */
    const val MIN_SIDE_PX: Float = 1f

    /** 空矩形（0 尺寸）。退化输入的返回值，也是「不可用」的参照。 */
    val EMPTY: ScreenRect = ScreenRect(0f, 0f, 0f, 0f)

    /**
     * 四个百分比 + 屏幕尺寸 → **屏幕坐标系里的小窗矩形**（px，已夹取到不出屏）。
     *
     * 纯函数：不读配置、不看 `Context`、不碰 `android.*`（`core` 的模块边界），
     * 所以预览（Compose Preview 里没有配置存储）、hook、单测三处都能直接调。
     *
     * @param screenW 屏幕宽（px）。**当前显示的实际尺寸**（旋转/折叠后就是当下的值）。
     * @param screenH 屏幕高（px）。
     * @param xPercent 窗口**中心**的水平位置。见类注释的语义表。
     * @param yPercent 窗口**中心**的垂直位置。
     * @return 整块在 `[0,0,screenW,screenH]` 内的矩形；非法输入返回 [EMPTY]
     *   （**不是 null** —— 空矩形是「算出来了，但不可用」，语义由 [isUsable] 给）
     */
    fun resolve(
        screenW: Float,
        screenH: Float,
        widthPercent: Float,
        heightPercent: Float,
        xPercent: Float,
        yPercent: Float,
    ): ScreenRect {
        // 非有限值一律退化。放在最前面：NaN 会沿着 `right = x + w` 污染每一个坐标，
        // 而「提前返回空矩形」是一个可以被调用方检测到的状态（isUsable = false）。
        if (!screenW.isFinite() || !screenH.isFinite() ||
            !widthPercent.isFinite() || !heightPercent.isFinite() ||
            !xPercent.isFinite() || !yPercent.isFinite()
        ) {
            return EMPTY
        }
        // 百分比 → px。表达式顺序与预览接线前的那份逐字一致（浮点结合律不成立，
        // 换个写法就可能差 1ulp，而 FanPreviewTest 钉的是具体数值）。
        val w = screenW * widthPercent / 100f
        val h = screenH * heightPercent / 100f
        // 中心在**可移动区间**里的比例 → 左上角。这一行就是 v3 语义的全部：
        // 可移动区间 = 屏幕尺寸 − 窗口尺寸（窗口在水平/垂直方向各自能挪多远）。
        //
        // 为什么不是「中心在屏幕上的绝对位置」（v2）：那样窗口占 80% 宽时，
        // 量程 0–100% 里有 80% 的行程算出来的左边缘会被夹成同一个值 —— 拖了不动。
        // 这里乘的是剩余行程，所以整段量程一一对应到真实位移。
        val x = (screenW - w) * xPercent / 100f
        val y = (screenH - h) * yPercent / 100f
        // 夹取全在 ScreenRect.clampedToScreen 里（唯一样本：它先夹尺寸再夹位置，
        // 并且对负的屏幕尺寸也不会算出负边 —— 见那个函数的注释）
        return ScreenRect(x, y, x + w, y + h).clampedToScreen(max(0f, screenW), max(0f, screenH))
    }

    // ─────────────────────── 横屏：保持竖屏形状与位置 ───────────────────────
    //
    // ## 为什么横屏必须单独一条公式（实测）
    //
    // 上面那条「四个百分比 × 当先屏幕尺寸」的公式换到横屏就变成一块**横屏宽条**：
    // 本机 2670×1220 乘 80% × 63.5% = **2136 × 775**。而 Flyme 给的是一块
    // **竖屏形状的窗口**（可见 `[239,29]–[900,1170]` = 661 × 1141，靠左、竖向几乎贴满）。
    // 两者不是「数值差一点」，是**长宽比倒过来了** —— 拿同一套百分比去乘必然错。
    //
    // 用户 2026-09-20 拍板 **(a) 保持形状与位置**：横屏下的小窗仍是竖屏那块窗，
    // 只是整块等比缩小后贴左；不采用「横屏另配一套百分比」那条路（HyperGesture 的做法）。
    //
    // ## 公式（照实测反推，**单样本，见类注释末尾的未闭合项**）
    //
    // ```
    // portraitW = min(screenW, screenH)      portraitH = max(screenW, screenH)
    // aspect    = (widthPercent  × portraitW) / (heightPercent × portraitH)   // 竖屏那块窗的形状
    // h         = screenH × LANDSCAPE_HEIGHT_PERCENT / 100                    // 95.1%
    // w         = h × aspect
    // left      = screenW × LANDSCAPE_LEFT_PERCENT / 100                      // 9.0%
    // top       = (screenH − h) / 2                                           // 垂直居中
    // ```
    //
    // 用实测数核对（MEIZU 22 横屏 2670×1200、竖屏参数 80% × 63.5%）：
    // `h = 1200 × 0.951 = 1141`（实测 1141 ✓）、`w = 1141 × 0.5662 = 646`（实测 661，差 2.3%）。
    // 那 2.3% 来自「拿什么当形状基准」—— 改用设备竖屏**可用区**的比例（`1200/2069`）
    // 算出 662。两种口径只差 2%，都远好于「2136 宽的横条」，所以先按百分比口径实现
    // （它不依赖「本机的可用区有多大」这个量，换机器不会漂）。
    //
    // ## ⚠️ 横屏下 `xPercent` / `yPercent` **不进公式**（不是忘了，是刻意的）
    //
    // 位置由上面两个实测常量定死：Flyme 那块窗在横屏下就是「贴左 + 垂直居中」，
    // 与竖屏那两个滑杆说的不是同一件事（`x%=50` 在竖屏是水平居中，横屏照搬会把它推到
    // 屏幕正中，与实测的「靠左」相反）。所以这两个滑杆在横屏下不参与运算 ——
    // **日志会明确写出来**（[describeLandscape]），不让人误以为「拖了没反应」是 bug。
    // 形状那两个（`widthPercent` / `heightPercent`）**仍然有效**，它们决定窗口的胖瘦。

    /** 横屏小窗高 = 屏幕（横屏）高的 **95.1%** —— 实测 1141 / 1200。 */
    const val LANDSCAPE_HEIGHT_PERCENT = 95.1f

    /** 横屏小窗左边距 = 屏幕（横屏）宽的 **9.0%** —— 实测 239 / 2670。 */
    const val LANDSCAPE_LEFT_PERCENT = 9.0f

    /** 是否横屏。**全工程只有这一处判据**（守卫、几何重算、摆放三处共用）。 */
    fun isLandscape(screenW: Float, screenH: Float): Boolean = screenW > screenH

    /**
     * 横屏下的小窗矩形：[widthPercent] / [heightPercent] 只用来定**形状**（= 竖屏那块窗的
     * 长宽比），大小与位置由 [LANDSCAPE_HEIGHT_PERCENT] / [LANDSCAPE_LEFT_PERCENT] 定。
     *
     * 与 [resolve] 同样**先夹尺寸再夹位置**（`ScreenRect.clampedToScreen`），
     * 所以极端参数（宽调到 150% 之类）不会算出跑出屏幕的矩形。
     */
    fun landscape(
        screenW: Float,
        screenH: Float,
        widthPercent: Float,
        heightPercent: Float,
    ): ScreenRect {
        if (!screenW.isFinite() || !screenH.isFinite() ||
            !widthPercent.isFinite() || !heightPercent.isFinite()
        ) {
            return EMPTY
        }
        // 竖屏那块窗的形状。用 min/max 而不是「假设 screenW 就是短边」——
        // 调用方传进来的就是一组屏幕尺寸，这里不该再猜它有没有转过。
        val portraitW = minOf(screenW, screenH)
        val portraitH = maxOf(screenH, screenW)
        val pw = portraitW * widthPercent / 100f
        val ph = portraitH * heightPercent / 100f
        // 两个尺寸任一为 0（参数被手改成 0 / 屏幕读到 0）→ 形状无从定义，退化成空矩形
        if (pw <= 0f || ph <= 0f) return EMPTY
        val aspect = pw / ph
        val h = screenH * LANDSCAPE_HEIGHT_PERCENT / 100f
        val w = h * aspect
        val left = screenW * LANDSCAPE_LEFT_PERCENT / 100f
        val top = (screenH - h) / 2f
        return ScreenRect(left, top, left + w, top + h)
            .clampedToScreen(max(0f, screenW), max(0f, screenH))
    }

    /**
     * 按**当前朝向**选公式 —— 调用方（hook 的摆放、ui 的预览）一律走这一个入口，
     * 免得「谁该判断横屏」这件事散落到各处（散落的表现是「预览画横条、真机摆竖块」）。
     */
    fun resolveFor(
        screenW: Float,
        screenH: Float,
        widthPercent: Float,
        heightPercent: Float,
        xPercent: Float,
        yPercent: Float,
    ): ScreenRect = if (isLandscape(screenW, screenH)) {
        landscape(screenW, screenH, widthPercent, heightPercent)
    } else {
        resolve(screenW, screenH, widthPercent, heightPercent, xPercent, yPercent)
    }

    /**
     * 横屏的一句话摘要。与 [describe] 分开是因为**入参不同**：横屏下 `x%` / `y%`
     * 不进公式，把它们打出来等于在日志里编两个数（排障的人会拿去复算，然后算不对）。
     *
     * 末尾固定带一句 `（x/y 滑杆在横屏不参与）` —— 「拖了没反应」在这条链上是
     * **预期行为**，不写出来就会被当成 bug 再查一遍。
     */
    fun describeLandscape(widthPercent: Float, heightPercent: Float, rect: ScreenRect): String =
        String.format(
            java.util.Locale.US,
            "横屏 形状=%.1f%%x%.1f%% 高=%.1f%% 左=%.1f%% → [%d,%d][%d,%d] %dx%d" +
                "（x/y 滑杆在横屏不参与）",
            widthPercent,
            heightPercent,
            LANDSCAPE_HEIGHT_PERCENT,
            LANDSCAPE_LEFT_PERCENT,
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt(),
            rect.width.toInt(),
            rect.height.toInt(),
        )

    /**
     * 出厂默认的那块矩形（= Flyme 实测的 80% × 63.5%，居中偏上）。
     *
     * 抽成具名函数而不是让调用方自己拼四个常量：它现在有**三个**用处，
     * 而三处必须算出同一个矩形 —— 预览里画的那块虚线框、「要不要显示虚线框说明」的判断、
     * 以及「当前值离默认有多远」。散成三处就地拼常量，迟早只改一处。
     *
     * （`WindowBoundsTest.theDefaultsReproduceTheWindowFlymeActuallyShows` 钉的是
     * 「这四个常量拼出来的东西就是 Flyme 那块窗口」，所以这个函数的内容不由调用方决定。）
     */
    fun defaults(screenW: Float, screenH: Float): ScreenRect = resolve(
        screenW = screenW,
        screenH = screenH,
        widthPercent = DEFAULT_WIDTH_PERCENT,
        heightPercent = DEFAULT_HEIGHT_PERCENT,
        xPercent = DEFAULT_X_PERCENT,
        yPercent = DEFAULT_Y_PERCENT,
    )

    /**
     * 出厂默认矩形，**按朝向选公式** —— [resolvePreset] 与任何「给我默认那块窗」的
     * 调用点都走这一个，不要直接调 [defaults]（它在横屏下是错的形状）。
     *
     * 竖屏就是 [defaults]；横屏走 [landscape]，形状仍由那两个默认百分比定。
     */
    fun defaultsFor(screenW: Float, screenH: Float): ScreenRect =
        if (isLandscape(screenW, screenH)) {
            landscape(screenW, screenH, DEFAULT_WIDTH_PERCENT, DEFAULT_HEIGHT_PERCENT)
        } else {
            defaults(screenW, screenH)
        }

    /**
     * v1 的「左/上边缘位置」→ v2 的「中心位置」（`Keys.CURRENT_CONFIG_VERSION` 1 → 2 用）。
     *
     * 公式就是「边缘 + 自身尺寸的一半」，但有三处不能省：
     *
     *  1. **先把边缘夹到 v1 的合法区间** `[0, 100 − sizePercent]`。v1 的
     *     `resolve` 就是这么夹的，用户当年看到的画面只由**夹取之后**的那个位置决定；
     *     直接拿存储里的原值算，会把这批用户迁到一个他们从没见过的位置上。
     *  2. **尺寸 ≥ 100% 时返回 50**：那时位置参数本来就没有自由度（v1 的夹取会把
     *     左边缘压成 0），返回 50 是唯一不产生偏差的答案。
     *  3. **不做屏幕尺寸相关的运算**。这是刻意的：配置是**跨朝向共享**的，
     *     「当时那块屏多宽」无法从配置里还原，而百分比形式恰好让这个换算与屏幕尺寸无关。
     *
     * @param edgePercent v1 存下来的边缘位置（%）
     * @param sizePercent 与之配套的尺寸百分比（%）。**必须传 v1 那一个**（见类注释里
     *   那几个 `LEGACY_DEFAULT_*`：用户没设过尺寸时，当年生效的是旧默认值，不是新默认值）
     */
    fun centerPercentFromEdgePercent(edgePercent: Float, sizePercent: Float): Float {
        val half = sizePercent / 2f
        val lo = half
        val hi = 100f - half
        // 尺寸占满屏幕（或更离谱）：中心只能是屏幕中心
        if (lo >= hi) return 50f
        val edge = edgePercent.coerceIn(0f, 100f - sizePercent)
        return (edge + half).coerceIn(lo, hi)
    }

    /**
     * v2 的「中心绝对位置」→ v3 的「中心在可移动区间里的比例」。
     *
     * 先按 v2 的夹取规则还原「当年那个左边缘」，再把它换算成可移动区间里的比例：
     *
     * ```
     * left%  = clamp(centerPercent − sizePercent / 2, 0, 100 − sizePercent)
     * ratio% = left% / (100 − sizePercent) × 100
     * ```
     *
     * 三处不能省：
     *  1. **先还原夹取**。v2 下 `x = 20`（w = 80%）被夹成贴左，用户当年看到的就是贴左；
     *     不还原就会把 `20` 当成「中心在 20% 处」再换算，得到一个他从没见过的位置。
     *  2. **尺寸 ≥ 100% 时返回 50**：那时窗口占满屏幕，没有行程，比例无从定义。
     *  3. **不做屏幕尺寸相关的运算** —— 配置跨朝向共享，百分比形式让换算与屏幕无关。
     */
    fun ratioPercentFromCenterPercent(centerPercent: Float, sizePercent: Float): Float {
        val travel = 100f - sizePercent
        if (travel <= 0f) return 50f
        val left = (centerPercent - sizePercent / 2f).coerceIn(0f, travel)
        return (left / travel * 100f).coerceIn(0f, 100f)
    }

    /**
     * 把「想要的那块矩形」换算成「要交给 MIUI 的矩形」—— 抵消它的 `freeformScale`。
     *
     * ## 为什么必须补偿（真机实测，2026-09-17）
     *
     * MIUI 拿到 `setLaunchBounds` 的矩形之后，会**把尺寸乘上 freeformScale**（锚点是左上角，
     * 原点不动）。实测（Xiaomi 17 Pro，scale = 0.7）：
     *
     * ```
     * 我们交出去  [122,487][1098,2174]  976x1686
     * 屏幕上是    [122,487][ 805,1668]  683x1181    ← 宽高都正好 ×0.7
     * ```
     *
     * 两个后果，用户一眼就能看出：
     *  - **窗口偏小**：配置 80% 屏宽，实际只有 56%（683/1220）；
     *  - **位置偏左上**：只缩尺寸不动原点，整块矩形的中心跟着往左上跑。
     *
     * 所以要把**尺寸**除以 scale（原点保持不动）。给出去的是「逻辑矩形」，
     * 比最终可见的那块大，MIUI 缩完正好落在用户配的位置与大小上。
     *
     * ## 为什么敢除以它（老注释的顾虑已经不成立）
     *
     * 这份代码从前**故意不除**，理由是「万一 MIUI 没乘，除完就放大了 1/scale」。
     * 现在这个顾虑被实测消掉了：**同一台机器上量到了它确实乘 0.7**，
     * 而且 scale 是从 MIUI 自己的 `ActivityOptionsInjector` 读出来的 ——
     * 读到的就是它接下来要用的那个值，不是猜的。
     *
     * 读不到（非 MIUI 的 options）或值离谱（不在 [0.2, 1.25]，参考实现给的是 [0.35, 1.2]）
     * 时**原样返回**：宁可少补偿，也不要拿一个可疑的数把窗口算成别的尺寸。
     *
     * @param scale MIUI 的 `mFreeformScale`；`null` / 非有限 / 越界 → 不补偿
     */
    fun compensateForScale(rect: ScreenRect, scale: Float?): ScreenRect {
        if (scale == null || !scale.isFinite()) return rect
        if (scale <= 0.2f || scale > 1.25f) return rect
        if (scale == 1f) return rect
        return ScreenRect(
            rect.left,
            rect.top,
            rect.left + rect.width / scale,
            rect.top + rect.height / scale,
        )
    }

    /**
     * 这个矩形能不能拿去驱动真机的窗口摆放。
     *
     * 判据只有一条（宽高都 ≥ [MIN_SIDE_PX]）；调用方拿到 `false` 时的动作是
     * **不覆写 bounds、走 MIUI 自己的摆放**，而不是「夹到最小值再用」——
     * 后者会让窗口跳到用户没配过的尺寸上（预览也会跟着对不上）。
     */
    fun isUsable(rect: ScreenRect): Boolean =
        rect.width >= MIN_SIDE_PX && rect.height >= MIN_SIDE_PX

    /**
     * 一句话摘要，给日志用（`hook` 侧 `FP-SysUiEnv` 的 `小窗摆放：` 那行直接抄它）。
     *
     * 格式固定成「四个入参 + 出参」，因为排除「哪个参数没进公式」时，
     * 唯一需要的就是这些数：`w%=80.0 h%=64.0 x%=50.0 y%=47.0 → [122,478][1098,2178] 976x1700`。
     * 小数点用 `Locale.US`（`String.format`）—— 与几何日志同一个规矩：日志里的小数点
     * 必须是 `.`，否则拿计算器复算的人要猜本地化格式。
     *
     * ⚠️ 字段名保持 `x%` / `y%` 不变（日志解析与排障习惯依赖它），但**含义已是中心位置**
     * （v1 是左/上边缘）。这一点在 `FP-SysUiEnv` 的日志行里另外标了，见那边。
     */
    fun describe(
        widthPercent: Float,
        heightPercent: Float,
        xPercent: Float,
        yPercent: Float,
        rect: ScreenRect,
    ): String = String.format(
        java.util.Locale.US,
        "w%%=%.1f h%%=%.1f x%%=%.1f y%%=%.1f → [%d,%d][%d,%d] %dx%d",
        widthPercent,
        heightPercent,
        xPercent,
        yPercent,
        rect.left.toInt(),
        rect.top.toInt(),
        rect.right.toInt(),
        rect.bottom.toInt(),
        rect.width.toInt(),
        rect.height.toInt(),
    )
}
