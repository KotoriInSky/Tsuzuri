package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds

/**
 * 全部特性的清单 —— 前端的唯一数据来源。
 *
 * 界面里**不允许**出现 `if (featureKey == "corner_small_window")` 这种分支：
 * 列表页、详情页、开关、参数控件、作用域提示全部由这里生成。
 *
 * [Maturity] 必须如实填。标成 STABLE 意味着「设备上开态关态都跑过冒烟清单」。
 */
object Catalog {

    const val CORNER_SMALL_WINDOW = "corner_small_window"

    /**
     * 「小窗摆放」那四项共同的依赖：只有**选了「自定义」**才出现。
     *
     * 写成常量而不是在四项里各写一遍 `PrefDependency("window_preset", …)`：
     * 那个 hint 键一旦改字面量，四处里漏一处就是「有三项说了为什么，第四项没有」——
     * 而三处都对的时候没有任何测试会红。
     *
     * ## 2026-09-19：依赖的目标从「开关」改成了「单选里的某一个取值」
     *
     * 原来是 `window_custom`（布尔总开关），语义是「关 = 交给系统 / 开 = 用我们四个值」。
     * 用户反馈的形态是**三选一**：两个预设并列（系统默认 / Flyme），自定义再单列一档。
     * 于是依赖变成「`window_preset == custom`」——
     * 判据见 [PrefSpec.isOperable]（它按 `Catalog` 里那个参数的实际类型分派）。
     *
     * `hideWhenOff = true`：这四项是**专属调节** ——
     * 另外两个预设下它们根本不参与运算（`WindowBounds.resolvePreset` 直接给出
     * 系统矩形或实测矩形，四个滑杆一个都不读），摆出来只会让人想「我该不该调它」；
     * 用户的原话是「自定义位置打开时再提供调整」。offHintKey 仍然必须写：
     * 分组页全组隐藏时用它解释「为什么看不到」。
     */
    private val WINDOW_CUSTOM_DEPENDENCY = PrefDependency(
        toggleName = "window_preset",
        offHintKey = "pref.corner.window_dep_off",
        hideWhenOff = true,
        choiceValue = WindowBounds.PRESET_CUSTOM,
    )
    const val SIDE_INDEX = "side_index"
    const val AGG_PAYMENT = "agg_payment"
    const val PA_WIDGET_ADMISSION = "pa_widget_admission"
    const val PA_WIDGET_PICKER_INJECT = "pa_widget_picker_inject"
    const val MIUI_WIDGET_FLAG = "miui_widget_flag"
    const val WIDGET_STYLE = "widget_style"

    /**
     * 小窗多开名单：勾选的应用获得 HyperOS 的「同应用同时开多个小窗」资格。
     *
     * 机制（2026-09-24 逆向 + 真机实证）：system_server 的任务冲突处理
     * （`DisplayContentStubImpl.process`）查 `MiuiMultiWindowAdapter.isSupportFreeFormMultiTask`
     * （framework 资源名单 `SUPPORT_FREEFORM_MULTI_TASK_LIST`，小米笔记在内），
     * 命中即放行同包再建一条 freeform Task。名单外应用第二次启动会被收编进已有小窗。
     * 本特性 hook 那个查询函数，对用户勾选的包返回 true。
     */
    const val MULTI_FREEFORM = "multi_freeform"

    /** 分享面板应用行按用户清单置顶（C14）。实现见 hook 侧 `ShareOrder`。 */
    const val SHARE_ORDER = "share_order"

    /** 选中文字后的悬浮工具栏去掉「问小爱」「AI 写作」（C15）。实现见 hook 侧 `TextToolbarAiOff`。 */
    const val TEXT_TOOLBAR = "text_toolbar"

    /** 截图 / 录屏完美状态栏（演示模式 + 藏通知图标）。契约见 [CleanStatusBar]。 */
    const val CLEAN_STATUSBAR = "clean_statusbar"

    const val CONTEXT_INSTALL = ContextSuggestion.INSTALL
    const val CONTEXT_MUSIC = ContextSuggestion.MUSIC
    const val CONTEXT_SCREEN_ON = ContextSuggestion.SCREEN_ON

    /**
     * 口令上岛：复制分享口令（BV/av/cv/sm/ac/淘口令/快递单号/链接）→ 超级岛提醒 →
     * 点击直达 App（系统解析，App 优先、浏览器兜底）→ 10 秒自动消失。
     *
     * 链路是**双进程**的：system_server 侧 hook `ClipboardService` 观察剪贴板并跑
     * 本地匹配引擎（`PasscodeRules`，零网络零数据表），命中后广播给 SystemUI；
     * SystemUI 侧代发带 `miui.focus.param` extra 的普通通知上岛（岛门禁全在
     * SystemUI 进程内，以 SystemUI 身份走系统同款路径，不碰白名单）。
     * 两侧的契约在 [PasscodeIsland]。
     */
    const val PASSCODE_ISLAND = "passcode_island"

    /**
     * 传送门（taplus）图片识别卡上的按钮：分享 / 提取文字。
     *
     * 为什么做这个：澎湃的图片卡只有「保存」这一条是**零依赖**的可用操作，另外三个文字按钮
     * （购物 / 扫码 / 识物）全都转给 `com.xiaomi.scanner` 且要求联网 —— 设备上即使装了它，
     * 用户能立刻用上的也只有保存。而「分享」与「提取文字」在澎湃侧**本来就有本地能力**：
     * 前者是标准的 `ACTION_SEND`，后者是 `ContentExtentionOcrUtils` 的**端侧** OCR
     * （`ImageRecognition` 的日志明确区分 `OCR local` 与 `OCR server` 两条路）。
     */
    /**
     * 识屏三类（2026-10-25 用户拍板，按「用户在做什么」分，不按传送门 / 超级小爱分）。
     * 每类是一个总开关 + 若干子项；子项标题写明作用于传送门还是超级小爱。
     * 旧的 7 个键（portal_* / screen_recognition_style）只在 [ContentCapabilities.migration] 里读一次。
     *
     *  - 从屏幕获取文字：传送门对未接入应用的文字准入、图片卡片提取文字；超级小爱结果页的翻译 / 复制 / 分享 / 外部调用。
     *  - 从屏幕获取图片：传送门识图的选区、图源、截图时机，图片卡片的编辑 / 分享 / 播放，识物改道超级小爱。
     *  - 识屏样式：超级小爱结果页的布局，传送门复制提醒的胶囊样式。
     */
    const val SCREEN_TEXT = "screen_text"
    const val SCREEN_IMAGE = "screen_image"
    const val RECOGNITION_LOOK = "recognition_look"

    /** 全局识别：控制中心磁贴一键识别整屏文字与图片。 */
    const val GLOBAL_RECOGNITION = "global_recognition"

    /** 参数分组标识：按钮开关（文案键 `pref_group_buttons`）。 */
    const val PREF_GROUP_BUTTONS = "buttons"

    private fun p(feature: String, pref: String) = Keys.pref(feature, pref)

    /** 展示顺序 = 分组顺序 + 组内声明顺序。 */
    val features: List<FeatureSpec> = listOf(

        // ── 手势 ──────────────────────────────────────────────────────────
        FeatureSpec(
            key = CORNER_SMALL_WINDOW,
            presentation = FeaturePresentation(preview = FeaturePreview.FAN, iconToolsGroup = "corner_icon"),
            group = FeatureGroup.GESTURE,
            scope = listOf(ScopeTarget.SYSTEM_UI),
            restart = RestartPolicy.HOT,
            // 已实现（注入点签名取自设备 dex），但手势是否真能触发小窗**未在设备上验证**，
            // 所以标 EXPERIMENTAL 而不是 STABLE。
            maturity = Maturity.EXPERIMENTAL,
            // 参数模型逐条对齐 HyperGesture（`research/refapps/com.oxohang.fanfreeform/sources/
            // com/oxohang/fanfreeform/`，下文行号都指这个目录）。它把参数分成两类：
            //
            //   1. **直接配**：键在 `config/ConfigContract.java` 里有 KEY_/DEFAULT_/MIN_/MAX_ 常量，
            //      由 `xposed/GestureConfig.from` 读出来并夹取；
            //   2. **派生**：没有键，由运行时从别的参数算出来 —— 主要是 `FanRuntime.java:2058-2065`
            //      的 `selectionRadius()` / `iconDiameter()`：它们把配置值当**初值**，
            //      再按「最挤的那一层不能重叠」往上顶半径（`effectiveRadius`）、往下压图标
            //      （`effectiveIconDiameter`）。所以半径和图标大小**不是纯输入**。
            //
            // 我们这边一律按「直接配 = 进 Catalog 的 Slider；派生 = 由 FanConfig.spec() 算」，
            // 后者绝不暴露成参数（让用户配一个本该算出来的值，等于给了他一个会自我打架的旋钮）。
            prefs = listOf(
                // ⚠️ 触发区的两个百分比**基准不同**（宽 ← 屏宽、高 ← 屏高），这是 HyperGesture
                //    的实测结论。⚠️ 2026-09-20 再改一次：基准从「**当下**屏幕」
                //    换成「**竖屏**屏幕」（宽 ← 短边、高 ← 长边），横竖屏同一块热区 ——
                //    按当下轴求值时转屏会让热区变形：本机横屏变成 147×21dp（纵向只剩 21dp，
                //    离底边 8mm 的起手点直接落空）。竖屏下一个像素都不变，老用户无感。
                //    求值唯一来源是 core 的 `FanHotZone`，预览与 hook 共用。
                //
                // 默认 8 / 11，范围 5–20 / 3–20：范围来自它的运行时夹取
                // （`GestureConfig.java:236-237` 的 `clamp(hotWidthPercent, 5, 20)` /
                // `clamp(hotHeightPercent, 3, 20)`），默认来自 `GestureConfig.java:314-316`
                // 与 `ConfigProvider.java:269-270`。上一版我们取 50 / 20–100，是把它当
                // 「底部触发条」而不是「角上一块小方区」了 —— 8% × 11% 在 1220×2656 上是
                // 98×292px，而 50% × 12% 是 610×319px（整条底边都在触发区里）。
                //
                // ⚠️ 2026-09-15 按 **Flyme 本体**的实测值改：它的热区是**底角 44dp × 44dp 的方块**，
                // 贴着侧边与底边、**包含最底边**（`WindowModeGestureListener.canTriggerWindowModeAction:185`：
                // `rawX < 44dp 或 > W−44dp` 且 `rawY > H−44dp`）。
                // 44dp @1220px 屏 = 143px = **11.7% 屏宽**；@2656px 高 = 143px = **5.4% 屏高**。
                // 之前默认 8/11 是 HyperGesture 的出厂值（窄而高，≈30dp×90dp），不是 Flyme 的形状。
                //
                // ⚠️ 宽度默认 **18%（比 Flyme 的 11.7% 宽）**，依据是 2026-09-15 的实测：
                // 用户自然的起手点在 `x=1006/1220`，即**距右边缘 214px ≈ 66dp** ——
                // 连 Flyme 的 44dp 区域都盖不到（44dp 只到 x≥1077），于是扇形**根本没武装**，
                // 那次斜上滑整个归了系统（表现为"有时候拉小窗会回桌面"）。
                // 18% = 220px → 覆盖 x≥1000 / x≤220。
                //
                // 为什么敢放宽：**方向锥才是真正的判别式**（纯竖直上滑 = 回桌面，一律不接管；
                // 近水平 = 系统切换应用，也不接管），所以热区变宽只多接住"斜着往内"的那些手势，
                // 而那正是扇形的形态。Flyme 的区域是在它自己的仲裁里定的（它有 veto），
                // 不必照抄它的宽度。
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "hot_width_percent"),
                    labelKey = "pref.corner.hot_width",
                    default = 18f, min = 2f, max = 60f, steps = 57, unit = "%",
                    group = "corner_trigger",
                    recommended = 12f..25f,
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "hot_height_percent"),
                    labelKey = "pref.corner.hot_height",
                    default = 5.5f, min = 1f, max = 30f, steps = 57, unit = "%",
                    group = "corner_trigger",
                    recommended = 4f..8f,
                ),
                // ⚠️ 这里**曾经**有两个滑杆（`bottom_yield_dp` / `takeover_inward_dp`），
                // 2026-09-15 都删掉了 —— 用户拍板「不要靠加参数解决」，而且这两条本来就是同一个毛病：
                //
                // - `bottom_yield_dp`（让出底部条带）：依据错了。我引的 Flyme 规则在手势导航下
                //   `insets.bottom == 0`、**从不生效**，拿一条死规则当了参照；实测代价是「很难触发」。
                //   Flyme 本体也确实没有这条让距（热区含最底边，靠方向锥把"想回桌面"的放回去）。
                // - `takeover_inward_dp`（接管门槛）：这个值是我们**内部的判定时机**，
                //   参照实现（Flyme 的 `bottom_gesture_touch_slop = 2dp`）也只是一个内部 dimen、
                //   从不暴露给用户。做成滑杆只会让用户去调一个他无从判断的量。
                //
                // 现在：接管时机 = 代码常量 `FanTrigger.TAKEOVER_INWARD_DP`（2dp），
                // 「为什么不让出底部条带」的完整理由写在 `FanTrigger.cornerAt` 的 KDoc 里。
                // 与系统手势的仲裁由两件事承担：尽早接管 + 拥住手势时拦下回桌面转移
                // （`CornerSmallWindow.installHomeBlock`）。
                // 扇的距离闸。它的基准是**短边**（和上面两个不同）。
                // 范围 6–24 来自 `GestureConfig.java:234`（`clamp(triggerPercent, 6, 24)`），
                // 默认 20 来自 `:314` + `ConfigContract.java:174`。
                // ⚠️ 上限同样放宽到 60：本机上一轮用的是 30.7%，超过它的 24%。
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "trigger_percent"),
                    labelKey = "pref.corner.trigger_percent",
                    hintKey = "pref.corner.trigger_percent.hint",
                    default = 20f, min = 5f, max = 80f, steps = 74, unit = "%",
                    group = "corner_trigger",
                    recommended = 15f..30f,
                ),
                // 角的选择。HyperGesture 没有这个键（两个下角恒开，贴哪一角由 DOWN 落在哪个
                // 热区矩形决定，`GestureGeometry.cornerAt:76-87` 左角优先）—— 这是我们多出来的
                // 一个自由度，留着：只用一个角是真需求，而且它在探测器里有接线。
                PrefSpec.Choice(
                    key = p(CORNER_SMALL_WINDOW, "corners"),
                    labelKey = "pref.corner_small_window.corners",
                    default = "both_bottom",
                    options = listOf(
                        PrefSpec.Choice.Option("both_bottom", "pref.corner_small_window.corners.both_bottom"),
                        PrefSpec.Choice.Option("bottom_left", "pref.corner_small_window.corners.bottom_left"),
                        PrefSpec.Choice.Option("bottom_right", "pref.corner_small_window.corners.bottom_right"),
                    ),
                    group = "corner_trigger",
                ),
                // 分横竖屏启用。HyperGesture 是「每个方向一套独立开关 + 一行三元选择」：
                // `bottomEnabledFor(landscape) = landscape ? bottomLandscapeEnabled : bottomPortraitEnabled`
                // （`GestureConfig.java:455-457`），默认竖屏 true / 横屏 true
                // （`ConfigContract.java:21,17`）。它不做「一套参数算两个方向」。
                // 我们只抄「开关」，不抄「每个方向一套几何参数」：我们的百分比全都以
                // 当前屏幕的宽/高/短边为基准，本身就已经是方向无关的。
                PrefSpec.Toggle(
                    key = p(CORNER_SMALL_WINDOW, "portrait_enabled"),
                    labelKey = "pref.corner.portrait_enabled",
                    default = true,
                    group = "corner_trigger",
                ),
                PrefSpec.Toggle(
                    key = p(CORNER_SMALL_WINDOW, "landscape_enabled"),
                    labelKey = "pref.corner.landscape_enabled",
                    default = true,
                    group = "corner_trigger",
                ),
                // 「在哪些应用里**不**呼出扇形」—— 按朝向各一份（用户 2026-09-20 拍板：
                // 「竖屏和横屏应该是两个单独的黑名单」）。理由与两个方向开关同源：
                // 同一个应用在竖屏和横屏下是两种完全不同的场景（游戏横屏时要屏蔽、
                // 竖屏时可能不需要），一份名单没法同时表达。
                //
                // ⚠️ `emptyIsValid = true`：**空集 = 一个都不屏蔽**，不是「没配过 → 自动挑一批
                // 来屏蔽」。这条差别是致命的（方向正好相反），详见 `PrefSpec.AppList` 的表格。
                //
                // 判定时选哪一份用 `hook` 侧 `FanSpec.isLandscape()` —— 与守卫 4（方向开关）
                // **同一个判据**，不另判一次朝向。
                PrefSpec.AppList(
                    key = p(CORNER_SMALL_WINDOW, "blocklist_portrait"),
                    labelKey = "pref.corner.blocklist_portrait",
                    pickedLabelKey = "app_picker.section.excluded",
                    restLabelKey = "app_picker.section.not_excluded",
                    default = emptySet(),
                    minCount = 1,
                    emptyIsValid = true,
                    group = "corner_trigger",
                    dependsOn = PrefDependency(
                        toggleName = "portrait_enabled",
                        offHintKey = "pref.corner.blocklist.off_hint",
                        hideWhenOff = true,
                    ),
                ),
                PrefSpec.AppList(
                    key = p(CORNER_SMALL_WINDOW, "blocklist_landscape"),
                    labelKey = "pref.corner.blocklist_landscape",
                    pickedLabelKey = "app_picker.section.excluded",
                    restLabelKey = "app_picker.section.not_excluded",
                    default = emptySet(),
                    minCount = 1,
                    emptyIsValid = true,
                    group = "corner_trigger",
                    dependsOn = PrefDependency(
                        toggleName = "landscape_enabled",
                        offHintKey = "pref.corner.blocklist.off_hint",
                        hideWhenOff = true,
                    ),
                ),
                // 振动分两个键，不是一个 —— 这是 HyperGesture 的设计：
                //   `BottomTriggerHapticPolicy.shouldVibrate(globalHaptic, bottomTriggerHaptic)
                //      = globalHaptic && bottomTriggerHaptic`（`BottomTriggerHapticPolicy.java:8-10`）
                // 即「呼出那一下」单独可关；「选中项变化」只受全局 haptic 管。
                // 我们不抄它的 `haptic` 全局键（本项目的总开关由框架提供），
                // 所以这里两个键分工是：`haptic` = 选中反馈，`trigger_haptic` = 呼出。
                // 两个都默认 true（`ConfigContract.java:27,46`）。
                //
                // ⚠️ 这两个开关声明在**列表末尾**，与它们的分组 `corner_feedback` 一致 ——
                // 二级页的顺序由「分组第一次出现的位置」决定（见 `FeatureSpec.prefGroups`），
                // 把振动排在参数列表的最后，界面上才是最后。
                //
                // 用户自选清单。**空 = 没配过 → 走自动挑选**（最近使用 + 补全），
                // 不是「一个都不显示」。这条语义在 AppList 的 KDoc 里写死了，改动前先读它。
                // minCount 与 FanTargets.MIN_TARGETS 对齐：低于它仍走自动挑选。
                // 清单来源：**显式的模式**，不是从「集合空不空」推断出来的。
                //
                // 为什么要单独一个参数：用户想切到「手动指定」但还没选任何应用时，
                // 状态是「模式=手动 + 集合为空」—— 这个用空集表达不了，会被当成「没配过，走自动」。
                // 而用户看到的是「我明明切到手动，它还在自动挑」。
                PrefSpec.Choice(
                    key = p(CORNER_SMALL_WINDOW, "apps_mode"),
                    labelKey = "pref.corner.apps_mode",
                    hintKey = "pref.corner.apps_mode.hint",
                    default = "manual",
                    options = listOf(
                        PrefSpec.Choice.Option("auto", "pref.corner.apps_mode.auto"),
                        PrefSpec.Choice.Option("manual", "pref.corner.apps_mode.manual"),
                    ),
                    group = "corner_internal",
                ),
                // ⚠️ **键从 `apps` 换成了 `items`，这不是改名，是换类型导致的换键。**
                //
                // 老键装的是 `StringSet`（无序），新类型要的是有序字符串。同一个键换读法
                // 会让老配置读成 `ClassCastException` → 按规范回退默认值 → 用户那 16 个应用
                // **看起来凭空消失**，而且回退是静默的。所以：
                //
                //   1. 新键 `items`（有序，见 [PrefSpec.OrderedList]）；
                //   2. **老键 `apps` 不删**（可回滚）；
                //   3. 设置界面首次打开时迁移（`OrderedItems.migrateFromUnordered`）；
                //   4. hook 侧**先读新键、读不到再读老键** —— 这样即使用户没打开过设置界面，
                //      扇子也照旧用他那 16 个应用，不会静默变成自动挑选。
                //
                // 顺序在这份数据里是**有意义的**：扇形每一项的位置由序号算出来
                // （`FanMetrics.place(index)`），所以第 1 项就在第 1 个槽位。

                // ---- 扇形排列 ----
                // `fan_layout_mode` 在 HyperGesture 里是 0/1/2 三个整数，含义分别是
                //   0 = SMART    按数量自动分 1–3 排、每排尽量均衡
                //   1 = FIXED_SEVEN（**已废弃**：加载时被改写成 0，`GestureConfig.java:329-332`）
                //   2 = CUSTOM   用户显式给内/中/外三排的容量
                // 我们存字符串（`Choice` 天然是 String），顺手避开「模式 1 被折叠」这种历史包袱。
                // 默认取 `smart`：它的出厂默认是 `custom`（`ConfigContract.java:38`），
                // 但那要配三个容量才有意义，而「智能」在项数少时与它等价（见 FanArcs.plan）。
                PrefSpec.Choice(
                    key = p(CORNER_SMALL_WINDOW, "fan_layout_mode"),
                    labelKey = "pref.corner.layout_mode",
                    default = "smart",
                    options = listOf(
                        PrefSpec.Choice.Option("smart", "pref.corner.layout_mode.smart"),
                        PrefSpec.Choice.Option("custom", "pref.corner.layout_mode.custom"),
                    ),
                    group = "corner_layout",
                ),
                // 两个容量键，不是三个。HyperGesture 有三个（内/中/外），但看它的加载路径
                // `GestureConfig.java:333-338`：
                //     inner  = clamp(fan_custom_inner_count, 0, 8)
                //     middle = clamp(fan_custom_middle_count, 0, 10)
                //     [inner, middle, outer] = FanRowAllocation.normalize(targets.size(), inner, middle)
                // `fan_custom_outer_count` **根本没被读** —— 外排恒等于「总数 − 内排 − 中排」
                // （`FanRowAllocation.java:8-14`）。它的设置界面看着能调外排，是因为界面在写入时
                // 做了双向联动（`ui/FanLayoutSettingsActivity.java:187-206`：改外排就反推中排）。
                // 我们的 `PrefSpec.Slider` 是独立参数，表达不了「A+B+C=N」，所以**只暴露两个
                // 被真正读取的键**，外排在 FanArcs.plan 里派生。
                // （把它也做成滑杆就是「声明了但没接线」——用户拖它不会有任何效果。）
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "fan_custom_inner_count"),
                    labelKey = "pref.corner.custom_inner",
                    hintKey = "pref.corner.custom_inner.hint",
                    // 出厂 7 / 8，范围 0–8 / 0–10（`ConfigContract.java:34-35,369-370`）
                    default = 7f, min = 0f, max = 8f, steps = 7, unit = "个",
                    group = "corner_layout",
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "fan_custom_middle_count"),
                    labelKey = "pref.corner.custom_middle",
                    hintKey = "pref.corner.custom_middle.hint",
                    default = 8f, min = 0f, max = 10f, steps = 9, unit = "个",
                    group = "corner_layout",
                ),
                // 每屏最多几项。HyperGesture 用在**配置加载时截断列表**
                // （`FanRuntime.java:3477-3479`），范围 3–24、默认 17
                // （`GestureConfig.java:325`、`ConfigContract.java:39,372,419`）。
                // 语义差异（**我们多出来的**）：我们的清单是整机应用池（自动生成、按最近使用排序），
                // 不是用户手挑的列表，所以这个值在我们这里是**每屏槽位数**，超出靠末尾「⋯」翻页
                // （`FanTargets.composePages`）。上限 24 不是拍的：24 是三排能在 70° 扇里排满的极限，
                // 也是它的硬上限，所以两边共用同一个天花板。
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "fan_max_targets"),
                    labelKey = "pref.corner.max_targets",
                    hintKey = "pref.corner.max_targets.hint",
                    default = 17f, min = 1f, max = 40f, steps = 38, unit = "个",
                    group = "corner_layout",
                    recommended = 8f..20f,
                ),
                // 半径初值 = 短边 × 它%（`FanRuntime.java:2059`）。范围 1–150、默认 30
                // （`ConfigContract.java:124`、`GestureConfig.java:235`、`ConfigProvider.java:268`）。
                // **它是初值不是结果**：真实半径会被 effectiveRadius 往上顶到「最挤那层不重叠」。
                // 调大会让扇形更舒展 —— Flyme 实测是 69（由 830px/369dp 反推），
                // 两者差一倍多，手感只能靠手指判断。
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "selection_radius_percent"),
                    labelKey = "pref.corner.radius_percent",
                    hintKey = "pref.corner.radius_percent.hint",
                    default = 30f, min = 5f, max = 150f, steps = 28, unit = "%",
                    group = "corner_layout",
                    recommended = 25f..50f,
                ),
                // 图标基准直径（dp）。范围 34–96 来自 `GestureConfig.java:238`
                // （`clamp(iconSizeDp, 34, 96)`），默认 34（既是 `DEFAULT_ICON_SIZE_DP`
                // 也是 `MIN_ICON_SIZE_DP`，`ConfigContract.java:78,432`）。
                // 上一版我们写 24–64：下限 24 比它的下限还低（小于 34 的值它自己会夹回去，
                // 等于我们放出去一个它不接受的值），上限 64 砍掉了它一半的量程。
                // **它同样是初值不是结果**：真实直径会被 effectiveIconDiameter 压到
                // 「相邻中心距 − 间隙」为止，下限 28dp（`FanRuntime.java:2064` 传的 `density*28`）。
                // ── 小窗自己的位置与尺寸 ──
                //
                // ⚠️ 必须有个总开关。MIUI 自己的入口（第 1 级 `MiuiFreeFormManager.getActivityOptions`）
                // **会自己算 bounds**，我们的这几个参数在那条路上不生效 —— 所以「系统默认」那一档
                // 走 MIUI 的摆放（现状），另外两档才用我们自己的 bounds。
                // 没有这个开关的话，四个滑杆就是死旋钮：拖了没反应，用户只会认为「这个参数没用」。
                //
                // ## 2026-09-19：布尔开关 → 三选一（用户拍板）
                //
                // 原来是 `window_custom` 一个 Toggle：关 = 系统默认、开 = 用四个值。
                // 用户的原话是「默认是 hyperos 的，然后自定义位置默认是 flyme 的，
                // 然后能不能选两个项 + 自定义位置？」→「两种预设作为选项，
                // 然后自定义位置打开时再提供调整」。
                //
                // 所以现在是三档，两个**并列的预设** + 一档自定义：
                //
                // | 取值 | 行为 |
                // | --- | --- |
                // | `system` | `bounds = null`，交给 MIUI 算摆放（= 加这个功能之前的行为） |
                // | `flyme`  | 用下面那四个**实测值**（80 / 63.5 / 50 / 41.7），= Flyme 那块窗口 |
                // | `custom` | 用用户拖出来的四个值（下面四项，**选它才出现**） |
                //
                // 为什么 `flyme` 要单列一档、而不是「系统默认 + 自定义」两档：
                // Flyme 那块窗口的比例是这个特性存在的意义，把它降级成「默认值」等于
                // 用户一拖滑杆就再也回不到「像 Flyme 那样」—— 除非他记得住 80/63.5/50/41.7
                // 这四个量出来的小数。单列一档之后，「像 Flyme」是一次点击而不是一组参数。
                //
                // 默认取 `flyme`：与上一版 `window_custom = true` 的行为**逐像素一致**
                // （那个开关的默认在 2026-09-17 就从 false 改成了 true）。
                // 老配置的搬迁见 `ConfigMigration`（v3→v4），迁移只搬「用户真的设过开关」的那些。
                PrefSpec.Choice(
                    key = p(CORNER_SMALL_WINDOW, "window_preset"),
                    labelKey = "pref.corner.window_preset",
                    default = WindowBounds.DEFAULT_PRESET,
                    // 三项标签都很短（系统默认 / Flyme / 自定义，最长 4 个汉字）→ 界面会走
                    // `SegmentedRow`：三档平铺可见，不用点开选单。这一档是**主决策**，
                    // 藏进下拉会让「我该选哪个」多一次点击。
                    options = listOf(
                        PrefSpec.Choice.Option(
                            WindowBounds.PRESET_SYSTEM,
                            "pref.corner.window_preset.system",
                        ),
                        PrefSpec.Choice.Option(
                            WindowBounds.PRESET_FLYME,
                            "pref.corner.window_preset.flyme",
                        ),
                        PrefSpec.Choice.Option(
                            WindowBounds.PRESET_CUSTOM,
                            "pref.corner.window_preset.custom",
                        ),
                    ),
                    group = "corner_window",
                ),
                // 下面四项**都依赖上面那个三选一里是不是选了「自定义」**。
                // 不是「自定义」时它们不参与任何运算（`WindowBounds.resolvePreset` 在另外两档下
                // 根本不读它们），所以界面上必须**不可见**，
                // 否则用户就是在拖一组永远不会生效的滑杆 —— 而界面无法反驳他。
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "window_width_percent"),
                    labelKey = "pref.corner.window_width",
                    // 相对**屏幕宽**的百分比。小窗太宽就不是「小窗」了，上限压到 90。
                    // 默认值 80 / 63.5 / 50 / 41.7 是**Flyme 参考机实测**出来的
                    // （MEIZU 22 上真实小窗的面板包围盒），唯一一份在 WindowBounds。
                    // ⚠️ **只有「自定义」这一档会用这四项**：选了「Flyme」预设时真机走
                    // `WindowBounds.defaults()`（同一组常量），而不是读这四个键 ——
                    // 所以「预设值被用户拖坏」这件事结构上不可能发生。
                    // `steps = 0` = **无极滑动**（连续取值）。
                    // 这四个是「摆在哪、多大」的位置类参数，用户是照着画面**手感**调的；
                    // 有刻度的版本（原来 12/15/20 档）在真机上一档就是 5% 屏宽，
                    // 用户看到的却是「拖了几乎没变」——位置类参数要的是连续，不是整档。
                    // 与它相对：`icon_size_dp` 那种「够用就行」的量保持有刻度即可。
                    default = WindowBounds.DEFAULT_WIDTH_PERCENT, min = 10f, max = 100f,
                    steps = 0, unit = "%",
                    group = "corner_window",
                    dependsOn = WINDOW_CUSTOM_DEPENDENCY,
                    recommended = 40f..80f,
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "window_height_percent"),
                    labelKey = "pref.corner.window_height",
                    // 相对**屏幕高**的百分比（和宽度基准不同，这是刻意的：竖屏下高度才够用）。
                    default = WindowBounds.DEFAULT_HEIGHT_PERCENT, min = 10f, max = 100f,
                    steps = 0, unit = "%",
                    group = "corner_window",
                    dependsOn = WINDOW_CUSTOM_DEPENDENCY,
                    recommended = 40f..80f,
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "window_x_percent"),
                    labelKey = "pref.corner.window_x",
                    hintKey = "pref.corner.window_x.hint",
                    // 窗口**中心**的位置（0 贴左 / 100 贴右 / 50 居中）。
                    //
                    // ⚠️ 2026-09-17 语义从「左边缘」改成「中心」，`Keys.CURRENT_CONFIG_VERSION`
                    // 1 → 2，迁移见 `ConfigMigration`。改的理由：边缘位置用户猜不出来 ——
                    // 想让 80% 宽的窗口居中要填 10，填 50 反而贴右。Flyme 实测就是居中
                    // （中心落在 50.0% 屏宽），用中心表达就是 50，一眼可懂。
                    default = WindowBounds.DEFAULT_X_PERCENT, min = 0f, max = 100f,
                    steps = 0, unit = "%",
                    group = "corner_window",
                    dependsOn = WINDOW_CUSTOM_DEPENDENCY,
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "window_y_percent"),
                    labelKey = "pref.corner.window_y",
                    hintKey = "pref.corner.window_y.hint",
                    // 窗口**中心**的位置（可移动区间口径，见 WindowBounds 的 v3 说明）。
                    // 默认 41.7 而不是 50：Flyme 实测中心在 46.9% 屏高（比正中略偏上），
                    // 换成这个口径就是 41.7，见 WindowBounds 里那一段实测记录。
                    default = WindowBounds.DEFAULT_Y_PERCENT, min = 0f, max = 100f,
                    steps = 0, unit = "%",
                    group = "corner_window",
                    dependsOn = WINDOW_CUSTOM_DEPENDENCY,
                ),

                // ---- 图标与外观 ----
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "icon_alpha"),
                    labelKey = "pref.corner.icon_alpha",
                    // 图标不透明度。**只作用于图标本身，不影响选中环** ——
                    // 选中环跟着变透明的话，选中的那一项反而最看不清，正好弄反了。
                    // 下限 60%（20% 看不清图标；原来写的是「下限 20% 而不是 0」）——：0 等于把图标藏起来，那不是「调节」而是「关掉功能」，
                    // 想关掉应该关特性开关。
                    default = 100f, min = 10f, max = 100f, steps = 17, unit = "%",
                    group = "corner_icon",
                    recommended = 80f..100f,
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "icon_size_dp"),
                    labelKey = "pref.corner.icon_size",
                    hintKey = "pref.corner.icon_size.hint",
                    default = 34f, min = 16f, max = 160f, steps = 71, unit = "dp",
                    group = "corner_icon",
                    recommended = 34f..64f,
                ),
                // 图标形状。**默认必须是 `circle`**：那是加这个参数**之前**的渲染结果
                //（`FanOverlay` 原来就是硬编码 `drawCircle`），换默认值等于替所有老用户
                // 静默改了外观。
                PrefSpec.Choice(
                    key = p(CORNER_SMALL_WINDOW, "icon_shape"),
                    labelKey = "pref.corner.icon_shape",
                    default = "circle",
                    options = listOf(
                        PrefSpec.Choice.Option("circle", "pref.corner.icon_shape.circle"),
                        PrefSpec.Choice.Option("rounded_square", "pref.corner.icon_shape.rounded_square"),
                        PrefSpec.Choice.Option("squircle", "pref.corner.icon_shape.squircle"),
                        PrefSpec.Choice.Option("teardrop", "pref.corner.icon_shape.teardrop"),
                        PrefSpec.Choice.Option("original", "pref.corner.icon_shape.original"),
                    ),
                    group = "corner_icon",
                ),
                // 形状的**可调数值**（用户要求：每种形状都能自己调，不要只有几个固定枚举）。
                // 只暴露两个真正有意义的自由度：圆角占比与柔度。
                // `0` = 用该形状自己的默认值 —— 老配置没配过时，画出来的结果与参数上线前**逐像素一致**。
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "icon_shape_corner"),
                    labelKey = "pref.corner.icon_shape_corner",
                    hintKey = "pref.corner.icon_shape_corner.hint",
                    default = 0f, min = 0f, max = 50f, steps = 25, unit = "%",
                    group = "corner_internal",
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "icon_shape_exponent"),
                    labelKey = "pref.corner.icon_shape_exponent",
                    hintKey = "pref.corner.icon_shape_exponent.hint",
                    // steps=4 → 取值 0/2/4/6/8/10。**不要写更细的步进**：柔度的有效区间是
                    // [2,10]（<2 是凹的星形，属另一族），步进 0.5 会让 0–2 那一段变成四个
                    // 「拖了没反应」的死格（现在会夹取并打日志，但滑杆上仍是一段死区）。
                    default = 0f, min = 0f, max = 10f, steps = 4, unit = "",
                    group = "corner_internal",
                ),
                // 图标白描边。用户实测反馈：它一直是硬编码的（宽度还写成了 px），
                // 而且**不跟着 icon_alpha 变透明**，于是调低不透明度时「图标淡了、白圈还实心」。
                // 这两个参数让它可调；「跟着一起透明」是渲染侧的行为修正（不是参数）。
                // 默认关（2026-10-29 用户：「把白边去了」）：半透明的白边无论压在图标边缘还是贴在外面，
                // 都让图标看着发透。想要外轮廓的可以自己打开。
                PrefSpec.Toggle(
                    key = p(CORNER_SMALL_WINDOW, "icon_edge"),
                    labelKey = "pref.corner.icon_edge",
                    default = false,
                    group = "corner_icon",
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "icon_edge_width"),
                    labelKey = "pref.corner.icon_edge_width",
                    // ⚠️ 默认必须是 **1f**：上一版硬编码的是 `ICON_EDGE_STROKE_DP = 1f`
                    //（`git show HEAD:...FanOverlay.kt` 可查）。写 0.5 会让所有老用户的描边
                    // 突然细一半 —— 参数的默认值不许改变「参数上线之前」的外观。
                    default = 1f, min = 0f, max = 12f, steps = 23, unit = "dp",
                    group = "corner_internal",
                    recommended = 0.5f..2f,
                ),

                // ---- 选中反馈 ----
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "selection_scale"),
                    labelKey = "pref.corner.selection_scale",
                    // ⚠️ **同一个物理量、两种表示**：HyperGesture 的
                    // `fan_selection_scale_percent` 是「放大的百分比」（5–40，默认 22，
                    // `ConfigContract.java:43,375,422`），我们这边 `FanSpec.selectionScale`
                    // 是**倍率**（`FanOverlay` 直接乘图标直径）。这里换算：+5%…+40% → 105%…140%，
                    // 默认 22% → 122%。保持我们的乘数语义是为了**不动 FanOverlay 的绘制**。
                    default = 122f, min = 80f, max = 250f, steps = 84, unit = "%",
                    group = "corner_selection",
                    recommended = 110f..130f,
                ),
                PrefSpec.Toggle(
                    key = p(CORNER_SMALL_WINDOW, "selection_ring"),
                    labelKey = "pref.corner.selection_ring",
                    // HyperGesture 默认 false，但真机上量到「选中项有深色描环」，
                    // 形态以 Flyme 为准 → 默认 true。
                    default = true,
                    group = "corner_selection",
                ),

                // ---- 动效 ----
                // 注意这是**除数不是毫秒**（HyperGesture 的语义）：
                //   duration = max(45, base * 100 / speed)
                // 当毫秒用会得到荒谬的时长。
                // 范围 75–160、默认 75：`bottom_animation_speed` 的 `clamp(..., 75, 160)`
                // （`GestureConfig.java:382`、`ConfigContract.java:13,368`）。
                // 上一版我们取 50–200 / 默认 100，两头都出了它的量程。
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "animation_speed"),
                    labelKey = "pref.corner.anim_speed",
                    default = 75f, min = 25f, max = 400f, steps = 74, unit = "%",
                    group = "corner_internal",
                    recommended = 75f..130f,
                ),
                // 多层时各层之间「对齐」还是「错开半格」。
                // 只作用于 radialIndex 为奇数的层（三层时就是中层）—— 层的端点仍然对齐，
                // 所以视觉上是「四角对齐、中间砖砌」。
                PrefSpec.Choice(
                    key = p(CORNER_SMALL_WINDOW, "fan_row_stagger"),
                    labelKey = "pref.corner.row_stagger",
                    hintKey = "pref.corner.row_stagger.hint",
                    default = "half",
                    options = listOf(
                        PrefSpec.Choice.Option("aligned", "pref.corner.row_stagger.aligned"),
                        PrefSpec.Choice.Option("half", "pref.corner.row_stagger.half"),
                    ),
                    group = "corner_internal",
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "fan_rotation"),
                    labelKey = "pref.corner.rotation",
                    // 语义按 HyperGesture 是「展开期每项绕自身旋转」，不是整体偏转。
                    // 范围 0–90、默认 42（`ConfigContract.java:41,374,421`、
                    // `GestureConfig.java:310`），上一版我们上限只到 40 —— 等于把它默认值
                    // 当成了天花板。
                    default = 42f, min = 0f, max = 90f, steps = 89, unit = "°",
                    group = "corner_internal",
                    recommended = 30f..60f,
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "fan_edge_margin"),
                    labelKey = "pref.corner.edge_margin",
                    // 弧心离屏幕边缘的留距。**参照实现里没有这个参数**（它把弧心钉死在角上），
                    // 是我们自加的：贴边的项会被裁，尤其内层 + 82° 那一列。
                    // 默认 10dp 是推导值而不是图标半径 —— 最紧的那一项不在 0°/90°，
                    // 按整圆留距会无谓缩掉一大截。推导见 FanGeometry 里的注释。
                    // ⚠️ 不要改成 0f：用户没动过滑杆时读到的就是 0（= 钉回角上），
                    // 代码里那个 10dp 兜底只在**整个键缺失**时生效。
                    default = 10f, min = 0f, max = 100f, steps = 49, unit = "dp",
                    group = "corner_layout",
                    recommended = 6f..16f,
                ),
                // 「内容」页的顺序 = 预览 → 排布调整 → 清单入口（用户 2026-10-22），所以清单声明在排布之后
                PrefSpec.OrderedList(
                    key = p(CORNER_SMALL_WINDOW, "items"),
                    labelKey = "pref.corner.items",
                    hintKey = "pref.corner.items.hint",
                    default = emptyList(),
                    minCount = 3,
                    group = "corner_list",
                ),
                PrefSpec.Slider(
                    key = p(CORNER_SMALL_WINDOW, "fan_reveal_amount"),
                    labelKey = "pref.corner.reveal_amount",
                    hintKey = "pref.corner.reveal_amount.hint",
                    // 展开动画走过的路程比例，范围 15–130、默认 65
                    // （`ConfigContract.java:40,373`、`GestureConfig.java:309`）。
                    // 上一版我们写 20–100，上限 100 挡住了它量程的一半以上。
                    // ⚠️ 未实测：>100 时起点会越过圆心（`startFactor = 1 - amount/100` 变负），
                    // 这一条读不出意图，但代码就是这样。
                    default = 65f, min = 5f, max = 200f, steps = 38, unit = "%",
                    group = "corner_internal",
                    recommended = 40f..90f,
                ),
                // 展开动画：替代 动画速度 / 展开时旋转 / 展开行程 / 多层错开 四个滑杆 ——
                // 四个量互相耦合，单独拖哪个都容易变丑。选一个预设，界面一并写入那四个内部键
                // （DerivedPrefs），hook 与预览照旧读它们。预设值见 FanMotion。
                PrefSpec.Choice(
                    key = p(CORNER_SMALL_WINDOW, FanMotion.NAME),
                    labelKey = "pref.corner.motion",
                    default = FanMotion.DEFAULT,
                    options = listOf(
                        PrefSpec.Choice.Option(FanMotion.FLYME, "pref.corner.motion.flyme"),
                        PrefSpec.Choice.Option(FanMotion.BRISK, "pref.corner.motion.brisk"),
                        PrefSpec.Choice.Option(FanMotion.STILL, "pref.corner.motion.still"),
                    ),
                    group = "corner_motion",
                ),

                // ---- 振动 ----
                PrefSpec.Toggle(
                    key = p(CORNER_SMALL_WINDOW, "haptic"),
                    labelKey = "pref.corner_small_window.haptic",
                    default = true,
                    group = "corner_feedback",
                ),
                PrefSpec.Toggle(
                    key = p(CORNER_SMALL_WINDOW, "trigger_haptic"),
                    labelKey = "pref.corner.trigger_haptic",
                    default = true,
                    group = "corner_feedback",
                ),
                // ---- root 强制启动（活动项专用，独立开关）----
                //
                // 为什么需要它：manifest 里 `android:exported="false"` 的活动，
                // 外部（含 SystemUI）调 startActivity 会被**静默拒绝** ——
                // 实测 `am start` 打印了 "Starting: Intent" 但顶层纹丝不动，
                // 无异常无日志（HyperOS 关了 logcat）。小爱识屏页
                // （com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity）
                // 就是这一类：包名正确的是 com.miui.voiceassist，活动类名前缀却是
                // com.xiaomi.voiceassistant（小米历史遗留），两边都容易写错。
                //
                // 为什么**默认开**：这一路只在「活动被 exported 拦住」时才介入
                // （判据是 SecurityException，见 FanShortcuts.Attempt.Blocked），
                // 导出的活动完全不受影响、也不会多起一个 su 进程。而关着的后果是
                // 用户看到「点了没反应」且**不知道有个开关**——那正是这一类功能
                // 最常见的死法。开关本身写在设置里、带说明，用户随时能关。
                //
                // ⚠️ 它需要 KernelSU 授权 `com.android.systemui`（当前白名单里没有）。
                // 没授权时的表现是 su 超时/退出码 1，日志会明说这一点（见
                // ActivityRootLauncher 的 explainExit 接线）。所以「开着但没授权」
                // 是一个**有明确日志**的状态，不是静默失效。
                PrefSpec.Toggle(
                    key = p(CORNER_SMALL_WINDOW, "root_activities"),
                    labelKey = "pref.corner.root_activities",
                    hintKey = "pref.corner.root_activities.hint",
                    default = true,
                    group = "corner_internal",
                ),
            ),
        ),

        FeatureSpec(
            key = SIDE_INDEX,
            presentation = FeaturePresentation(
                preview = FeaturePreview.SIDE_INDEX,
                previewGroups = setOf("side_trigger", "side_panel", "side_material"),
                iconToolsGroup = "side_panel",
            ),
            group = FeatureGroup.GESTURE,
            // 只用 SystemUI：应用列表、浮层、启动都在这一个进程里做完，
            // 不需要把作用域扩到设置或别的应用（规范 §8 最小作用域）。
            scope = listOf(ScopeTarget.SYSTEM_UI),
            restart = RestartPolicy.PROCESS_RESTART,
            // 已实现（自建索引层，不依赖 Flutter 抽屉），但**未用真手指验证**。
            maturity = Maturity.EXPERIMENTAL,
            prefs = listOf(
                PrefSpec.Choice(
                    key = p(SIDE_INDEX, "side"),
                    group = "side_trigger",
                    labelKey = "pref.side_index.side",
                    default = "right",
                    options = listOf(
                        PrefSpec.Choice.Option("right", "pref.side_index.side.right"),
                        PrefSpec.Choice.Option("left", "pref.side_index.side.left"),
                        PrefSpec.Choice.Option("both", "pref.side_index.side.both"),
                    ),
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "trigger_width"),
                    group = "side_trigger",
                    labelKey = "pref.side_index.trigger_width",
                    hintKey = "pref.side_index.trigger_width.hint",
                    default = 20f, min = 2f, max = 120f, steps = 58, unit = "dp",
                    recommended = 12f..32f,
                ),
                // 生效范围。Appindex 是「按包名白名单」，那需要一个新的参数类型
                // （应用多选），而那个类型要等 ui/ 那边腾出来才能加。
                // 先用三档 Choice 覆盖绝大多数实际需求 —— 不引入新类型，也就不会
                // 把「表达不了」硬塞成「假装能配」。
                PrefSpec.Choice(
                    key = p(SIDE_INDEX, "scope"),
                    group = "side_trigger",
                    labelKey = "pref.side_index.scope",
                    default = "launcher_only",
                    options = listOf(
                        PrefSpec.Choice.Option("launcher_only", "pref.side_index.scope.launcher_only"),
                        PrefSpec.Choice.Option("anywhere", "pref.side_index.scope.anywhere"),
                        PrefSpec.Choice.Option("exclude_launcher", "pref.side_index.scope.exclude_launcher"),
                    ),
                ),
                // 抽屉 / 文件夹里的屏蔽（需求 #8，2026-09-19 补声明）。
                //
                // **它是行为开关，不是调节旋钮** —— 与 `corner_advanced` 里的 `root_activities` 同类：
                // 「抽屉开着、或桌面文件夹展开时，边缘手势还给不给」有明确是非，关掉后退回
                // 「与未安装模块逐位一致」（抽屉里照弹索引），不存在“调成多少合适”的问题。
                //
                // 为什么必须补上这一行：这条能力此前**从未在 Catalog 里声明**，
                // 靠 `DrawerSignal.DEFAULT_DRAWER_GATE = true` 硬默认生效 ——
                // 也就是说它在界面上处处不存在：没有开关、没有文案、搜索也搜不到。
                // 表现就是维护者自己都会问「我记得实现过，但这个包好像没有」
                // （2026-09-19 产品经理原话）。读侧代码当时甚至专门报了
                // 「未声明,用默认值」进安装报告 —— 记号在，但没人看得见，等于没有。
                //
                // ⚠️ 两条信号通道的可靠性**不对称**，hint 里如实写了：抽屉那条走
                // system_server 观察 WMS 窗口属性（稳定）；文件夹那条**只有**桌面进程内的
                // native hook 一条路，而那个补丁会被 HyperOS 页保护抹掉
                // （存活 6～11 分钟、**静默失效**）→ 后者的实际表现是
                // 「刚开机十分钟有效，之后又开始弹」。看门狗还没做，所以不要在这里承诺「必定屏蔽」。
                PrefSpec.Toggle(
                    key = p(SIDE_INDEX, DrawerSignal.DRAWER_GATE_PREF),
                    group = "side_trigger",
                    labelKey = "pref.side_index.drawer_gate",
                    hintKey = "pref.side_index.drawer_gate.hint",
                    default = DrawerSignal.DEFAULT_DRAWER_GATE,
                ),
                PrefSpec.Toggle(
                    key = p(SIDE_INDEX, "show_recent"),
                    group = "side_content",
                    labelKey = "pref.side_index.show_recent",
                    default = true,
                ),
                // ── 以下五项对齐 Appindex 的同名能力──
                PrefSpec.Toggle(
                    key = p(SIDE_INDEX, "show_handle"),
                    group = "side_handle",
                    labelKey = "pref.side_index.show_handle",
                    default = false,
                ),
                PrefSpec.Toggle(
                    key = p(SIDE_INDEX, "haptic"),
                    group = "side_internal",
                    labelKey = "pref.side_index.haptic",
                    default = true,
                ),
                // 触觉反馈的档位。**轴是「表达力」，不是「强度」** —— 见 SideIndexHaptics
                // 的类注释：Android 官方给的是三条表达力递增的路径（View 语义常量 →
                // 预定义效果 → Composition 原语），并没有「把同一次交互调强 30%」这种东西。
                //
                // 所以原来那两个按「百分比振幅 / 毫秒时长」设计的滑块（haptic_strength /
                // haptic_duration）失去了消费方：`performHapticFeedback` 不吃振幅也不吃时长，
                // 预定义效果是固定波形。留着它们的表现是「拖了没反应」—— 本项目最忌讳的那类失败。
                PrefSpec.Choice(
                    key = p(SIDE_INDEX, "haptic_mode"),
                    group = "side_trigger", // 原 side_feedback：只剩这一行，单行二级页不允许
                    labelKey = "pref.side_index.haptic_mode",
                    default = "standard",
                    options = listOf(
                        PrefSpec.Choice.Option("off", "pref.side_index.haptic_mode.off"),
                        PrefSpec.Choice.Option("light", "pref.side_index.haptic_mode.light"),
                        PrefSpec.Choice.Option("standard", "pref.side_index.haptic_mode.standard"),
                        PrefSpec.Choice.Option("strong", "pref.side_index.haptic_mode.strong"),
                    ),
                ),
                // ── 以下八项复刻自 Appindex 的设置面──
                // 消费侧已实现（SideIndexLook.kt），这里只补声明。
                // 缺声明时后端用默认值并在安装报告里列出缺失键名，不会静默失效。
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "letter_offset"),
                    group = "side_panel",
                    labelKey = "pref.side_index.letter_offset",
                    hintKey = "pref.side_index.letter_offset.hint",
                    // 默认 50（居中）而不是 Appindex 的 75：视觉形态以 Flyme 为准
                    default = 50f, min = 0f, max = 100f, steps = 19, unit = "%",
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "letter_scale"),
                    group = "side_panel",
                    labelKey = "pref.side_index.letter_scale",
                    default = 100f, min = 20f, max = 400f, steps = 37, unit = "%",
                    recommended = 80f..140f,
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "icon_size"),
                    group = "side_panel",
                    labelKey = "pref.side_index.icon_size",
                    default = 50f, min = 16f, max = 160f, steps = 71, unit = "dp",
                    recommended = 40f..64f,
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "icon_radius"),
                    group = "side_panel",
                    labelKey = "pref.side_index.icon_radius",
                    hintKey = "pref.side_index.icon_radius.hint",
                    // 默认 0 = 不裁圆角，照 Flyme（它把图标直贴）
                    default = 0f, min = 0f, max = 80f, steps = 79, unit = "dp",
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "panel_inset"),
                    group = "side_panel",
                    labelKey = "pref.side_index.panel_inset",
                    hintKey = "pref.side_index.panel_inset.hint",
                    default = 32f, min = 0f, max = 300f, steps = 74, unit = "dp",
                    recommended = 16f..64f,
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "handle_height"),
                    group = "side_handle",
                    labelKey = "pref.side_index.handle_height",
                    default = 45f, min = 10f, max = 100f, steps = 17, unit = "%",
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "handle_offset"),
                    group = "side_handle",
                    labelKey = "pref.side_index.handle_offset",
                    default = 69f, min = 0f, max = 100f, steps = 99, unit = "%",
                ),
                // ── 外观层第二批──
                // 消费侧已实现，缺声明时用默认值并在安装报告里列出键名（不会静默失效）。
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "cell_height"),
                    group = "side_panel",
                    labelKey = "pref.side_index.cell_height",
                    // 格宽 66dp 与 5 列固定照 Flyme，不开放：格宽一超屏宽，
                    // 最右一列会落到屏幕外（看不见也点不到）。格高开放纵向间距。
                    default = 108f, min = 48f, max = 240f, steps = 47, unit = "dp",
                    recommended = 96f..120f,
                ),
                PrefSpec.Toggle(
                    key = p(SIDE_INDEX, "hide_app_names"),
                    group = "side_material",
                    labelKey = "pref.side_index.hide_app_names",
                    default = false,
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "blur_radius"),
                    group = "side_material",
                    labelKey = "pref.side_index.blur_radius",
                    // 默认 20 = 改动前硬编码的值，不动它就与以前完全一致。
                    // ⚠️ 这不是高斯 σ：SurfaceFlinger 是 1/4 降采样 + Kawase 4 pass，
                    // 半径 < 10 还有交叉淡入，响应非线性。
                    default = 20f, min = 0f, max = 200f, steps = 39, unit = "dp",
                    dependsOn = PrefDependency("blur", "pref.side_index.material.required"),
                    recommended = 10f..40f,
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "bubble_anim_ms"),
                    group = "side_internal",
                    labelKey = "pref.side_index.bubble_anim_ms",
                    hintKey = "pref.side_index.bubble_anim_ms.hint",
                    // 150ms / AccelerateDecelerate 是 Niagara 的取证原值
                    default = 150f, min = 0f, max = 1500f, steps = 59, unit = "ms",
                    recommended = 100f..250f,
                ),
                PrefSpec.Toggle(
                    key = p(SIDE_INDEX, "bubble_icon_slots"),
                    group = "side_handle",
                    labelKey = "pref.side_index.bubble_icon_slots",
                    default = false,
                ),
                // ── 需求 #9：收藏项 + 最近使用数量 ──
                PrefSpec.OrderedList(
                    key = p(SIDE_INDEX, "favorites"),
                    group = "side_content",
                    labelKey = "pref.side_index.favorites",
                    hintKey = "pref.side_index.favorites.hint",
                    // 默认空 = 用户没配过 → **不显示收藏槽**（不是「自动挑选」：
                    // 「收藏」没有合理的自动答案，唯一能凑出来的是「最近使用」，
                    // 而那已经是独立槽位 ★，会变成两个内容相同的槽）。
                    // 所以默认值下字母条与改动前逐位一致 —— 空清单同时就是「关」。
                    default = emptyList(),
                    emptyLabelKey = "favorites.empty",
                    iconTools = ListIconTools.NAME_AND_ICON,
                    // 1 项就算配过：扇形那条 minCount 门槛来自「每屏槽位分页」，侧滑索引没那个问题。
                    minCount = 1,
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "recent_count"),
                    group = "side_content",
                    labelKey = "pref.side_index.recent_count",
                    // 默认 10 = 旧版硬编码的 MAX。⚠️ 区间必须与
                    // SideIndexConfig.MIN/MAX_RECENT_COUNT 一致：上限**同时**是磁盘上保留的条数，
                    // 所以「调大」能立刻看到更多历史。
                    default = 10f, min = 1f, max = 60f, steps = 58, unit = "个",
                    recommended = 6f..16f,
                ),
                // ── 需求 #5c：列数与格子宽（横向间距）+ #7B 背景材质 ──
                // ⚠️ 列数与格宽**不能各自独立乱调**：格宽 × 列数一旦超过可用屏宽，
                // 面板会被顶到 0、最右一列落到屏幕外（看不见也点不到）。
                // SideIndexLook.panelMetrics 里有联动夹取，区间也必须与它的常量一致。
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "columns"),
                    group = "side_panel",
                    labelKey = "pref.side_index.columns",
                    // steps=3 → (3+1)=4 个区间 → 只能取 3/4/5/6/7
                    default = 5f, min = 1f, max = 10f, steps = 8, unit = "列",
                    recommended = 4f..6f,
                ),
                PrefSpec.Slider(
                    key = p(SIDE_INDEX, "cell_width"),
                    group = "side_panel",
                    labelKey = "pref.side_index.cell_width",
                    // 区间与 SideIndexLook.MIN/MAX_CELL_WIDTH_DP 一致（44..96）
                    default = 66f, min = 24f, max = 200f, steps = 87, unit = "dp",
                    recommended = 56f..80f,
                ),
                PrefSpec.Choice(
                    key = p(SIDE_INDEX, "material"),
                    group = "side_material",
                    labelKey = "pref.side_index.material",
                    default = "none",
                    options = listOf(
                        PrefSpec.Choice.Option("none", "pref.side_index.material.none"),
                        PrefSpec.Choice.Option("blur", "pref.side_index.material.blur"),
                        PrefSpec.Choice.Option("glass", "pref.side_index.material.glass"),
                    ),
                ),
                // ── 桌面应用自定义（本批）──
                // 1️⃣ 哪些包算桌面。默认就是参考机上解析出来的那一个，所以不动它行为不变。
                // 为什么必须是**用户可配的集合**而不是常量：一换第三方桌面，
                // `scope=launcher_only` 的整个生效范围就废了（而且"哪些是桌面"没有可猜的答案）。
                PrefSpec.AppList(
                    key = p(SIDE_INDEX, "launcher_packages"),
                    group = "side_trigger",
                    labelKey = "pref.side_index.launcher_packages",
                    hintKey = "pref.side_index.launcher_packages.hint",
                    default = setOf("com.miui.home"),
                    minCount = 1,
                ),
                // 2️⃣ 索引里显示哪些应用。默认「全部」= 与改动前逐位一致。
                PrefSpec.Choice(
                    key = p(SIDE_INDEX, "index_filter_mode"),
                    group = "side_content",
                    labelKey = "pref.side_index.index_filter_mode",
                    default = "all",
                    options = listOf(
                        PrefSpec.Choice.Option("all", "pref.side_index.index_filter_mode_all"),
                        PrefSpec.Choice.Option("exclude", "pref.side_index.index_filter_mode_exclude"),
                        PrefSpec.Choice.Option("include", "pref.side_index.index_filter_mode_include"),
                    ),
                ),
                PrefSpec.AppList(
                    key = p(SIDE_INDEX, "index_filter_packages"),
                    group = "side_content",
                    labelKey = "pref.side_index.index_filter_packages",
                    default = emptySet(),
                    minCount = 1,
                ),
                // ⚠️ `icon_pack`（#5b 图标包）**暂不声明**：hook 侧机制已就绪（含缓存与预热），
                // 但它在这一页的入口还没接（FeatureRows 的 AppList 分支今天是禁用的只读行）。
                // 声明了就是一个「看得见、设不了」的死控件 —— 那正是本项目最忌讳的形态。
                // 等 #9 给 side_index 加一条 OrderedList（收藏项）后，照 FanIconsScreen 接入口即可。
                PrefSpec.Toggle(
                    key = p(SIDE_INDEX, "blur"),
                    group = "side_internal",
                    labelKey = "pref.side_index.blur",
                    // 默认关：模糊依赖设备能力（Appindex 也把它做成可选），
                    // 默认开会在不支持的机器上变成「开了没反应」。
                    default = false,
                ),
            ),
        ),

        // ── 负一屏 ────────────────────────────────────────────────────────
        //
        // 负一屏小部件入口归入杂项；保留原配置键与作用域。
        FeatureSpec(
            key = PA_WIDGET_ADMISSION,
            group = FeatureGroup.APPS,
            scope = listOf(ScopeTarget.PERSONAL_ASSISTANT),
            restart = RestartPolicy.HOT_RELOAD,
            maturity = Maturity.EXPERIMENTAL,
            // 1×2 / 2×3 要靠尺寸准入才加得进去。
            // 无参数：这是纯解锁，不给用户旋钮，免得他又把负一屏弄坏。
        ),

        FeatureSpec(
            key = PA_WIDGET_PICKER_INJECT,
            group = FeatureGroup.APPS,
            scope = listOf(ScopeTarget.PERSONAL_ASSISTANT),
            restart = RestartPolicy.HOT_RELOAD,
            // 一个开关装两段：列表注入（PaWidgetPickerInject，让本机 provider 出现在列表里）+
            // 详情注入（PaWidgetDetailInject，点进去由本地合成详情，不走云端）。第三段的分配与绑定
            // 是 PA 自己的通用流程，不改。
            // 保持 EXPERIMENTAL。
            maturity = Maturity.EXPERIMENTAL,
            // 无参数：与 PA_WIDGET_ADMISSION 一样是纯解锁，不给用户旋钮。
        ),

        FeatureSpec(
            key = MULTI_FREEFORM,
            group = FeatureGroup.GESTURE,
            scope = listOf(ScopeTarget.SYSTEM_SERVER),
            // system_server 里的 hook 装上后改不了自身 —— 改名单必须重启 system_server
            // （= 软重启一次）。前端会按这个策略提示用户。
            restart = RestartPolicy.PROCESS_RESTART,
            maturity = Maturity.EXPERIMENTAL,
            // 单开关、零配置：开启 = 所有应用获得多开资格（isSupportFreeFormMultiTask
            // 一律 true）。放行的唯一实际场景是「应用再唤起自己的小窗」，用户主动行为；
            // 个别应用多开下表现异常的风险由 EXPERIMENTAL 标注兜住。
        ),

        FeatureSpec(
            key = MIUI_WIDGET_FLAG,
            group = FeatureGroup.NEGATIVE_SCREEN,
            scope = listOf(ScopeTarget.SYSTEM_SERVER),
            restart = RestartPolicy.PROCESS_RESTART,
            maturity = Maturity.PARKED,
            prefs = listOf(
                PrefSpec.Toggle(
                    key = p(MIUI_WIDGET_FLAG, "system_wide"),
                    labelKey = "pref.miui_widget_flag.system_wide",
                    hintKey = "pref.miui_widget_flag.system_wide.hint",
                    default = false,
                ),
            ),
        ),

        // ── 识屏（传送门 + 超级小爱，按用户任务分三类） ─────────────────────────
        // 各类的子项由多个实现分担（见 HookRegistry 里的注释）；restart 取最常见的档位，
        // 需要更重重启的子项（用原图在 system_server）在自己的说明里写明。
        FeatureSpec(
            key = SCREEN_TEXT,
            group = FeatureGroup.SCREEN_RECOGNITION,
            scope = listOf(ScopeTarget.CONTENT_CATCHER, ScopeTarget.CONTENT_EXTENSION, ScopeTarget.VOICE_ASSIST),
            // 图片卡片按钮是往已 inflate 的行里 addView，小爱结果页改的是 View 参数：
            // uninstall 撤不掉，只能重启进程 —— HOT_RELOAD，不要改成 HOT。
            restart = RestartPolicy.HOT_RELOAD,
            maturity = Maturity.EXPERIMENTAL,
            prefs = listOf(
                PrefSpec.Toggle(key = p(SCREEN_TEXT, "app_compat"), labelKey = "pref.screen_text.app_compat", hintKey = "pref.screen_text.app_compat.hint", default = false, group = "portal"),
                PrefSpec.Toggle(key = p(SCREEN_TEXT, "ocr"), labelKey = "pref.screen_text.ocr", hintKey = "pref.screen_text.ocr.hint", default = false, group = "portal"),
                PrefSpec.Toggle(key = p(SCREEN_TEXT, "translate"), labelKey = "pref.screen_text.translate", hintKey = "pref.screen_text.translate.hint", default = false, group = "xiaoai"),
                PrefSpec.Toggle(key = p(SCREEN_TEXT, "copy_text"), labelKey = "pref.screen_text.copy_text", hintKey = "pref.screen_text.copy_text.hint", default = false, group = "xiaoai"),
                PrefSpec.Toggle(key = p(SCREEN_TEXT, "share_text"), labelKey = "pref.screen_text.share_text", hintKey = "pref.screen_text.share_text.hint", default = false, group = "xiaoai"),
                PrefSpec.Toggle(key = p(SCREEN_TEXT, "external_trigger"), labelKey = "pref.screen_text.external_trigger", hintKey = "pref.screen_text.external_trigger.hint", default = false, group = "xiaoai"),
            ),
        ),
        FeatureSpec(
            key = SCREEN_IMAGE,
            group = FeatureGroup.SCREEN_RECOGNITION,
            // SYSTEM_UI：按下时截图的「静止按住」判定；SYSTEM_SERVER：用原图时追加识图采集 APK
            scope = listOf(ScopeTarget.CONTENT_CATCHER, ScopeTarget.CONTENT_EXTENSION, ScopeTarget.SYSTEM_UI, ScopeTarget.SYSTEM_SERVER),
            restart = RestartPolicy.HOT_RELOAD,
            maturity = Maturity.EXPERIMENTAL,
            prefs = listOf(
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "expand_region"), labelKey = "pref.screen_image.expand_region", hintKey = "pref.screen_image.expand_region.hint", default = false, group = "portal_capture"),
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "screenshot_crop"), labelKey = "pref.screen_image.screenshot_crop", hintKey = "pref.screen_image.screenshot_crop.hint", default = false, group = "portal_capture"),
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "candidate_check"), labelKey = "pref.screen_image.candidate_check", hintKey = "pref.screen_image.candidate_check.hint", default = false, group = "portal_capture"),
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "press_snapshot"), labelKey = "pref.screen_image.press_snapshot", hintKey = "pref.screen_image.press_snapshot.hint", default = false, group = "portal_capture"),
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "source_bitmap"), labelKey = "pref.screen_image.source_bitmap", hintKey = "pref.screen_image.source_bitmap.hint", default = false, group = "portal_capture"),
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "edit"), labelKey = "pref.screen_image.edit", hintKey = "pref.screen_image.edit.hint", default = false, group = "portal_card"),
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "share_image"), labelKey = "pref.screen_image.share_image", hintKey = "pref.screen_image.share_image.hint", default = false, group = "portal_card"),
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "original_media"), labelKey = "pref.screen_image.original_media", hintKey = "pref.screen_image.original_media.hint", default = false, group = "portal_card"),
                // 动图 HEIF/AVIF（抖音表情包等）保存/分享时的转码格式；保存时实时读取，改完下次保存即生效。
                PrefSpec.Choice(
                    key = p(SCREEN_IMAGE, "animated_format"),
                    labelKey = "pref.screen_image.animated_format",
                    hintKey = "pref.screen_image.animated_format.hint",
                    default = "gif",
                    options = listOf(
                        PrefSpec.Choice.Option("gif", "pref.screen_image.animated_format.gif"),
                        PrefSpec.Choice.Option("webp", "pref.screen_image.animated_format.webp",
                            noteKey = "pref.screen_image.animated_format.webp.note"),
                    ),
                    group = "portal_card",
                    dependsOn = PrefDependency(toggleName = "original_media",
                        offHintKey = "pref.screen_image.animated_format.off_hint", hideWhenOff = true),
                    dropdown = true,
                ),
                PrefSpec.Toggle(key = p(SCREEN_IMAGE, "object_to_xiaoai"), labelKey = "pref.screen_image.object_to_xiaoai", hintKey = "pref.screen_image.object_to_xiaoai.hint", default = false, group = "portal_card"),
            ),
        ),
        FeatureSpec(
            key = GLOBAL_RECOGNITION,
            group = FeatureGroup.SCREEN_RECOGNITION,
            // SystemUI：受理磁贴、收起控制中心；传送门：截屏、端侧 OCR、开窗。两边都只注册接收器、不改宿主方法
            scope = listOf(ScopeTarget.SYSTEM_UI, ScopeTarget.CONTENT_EXTENSION),
            restart = RestartPolicy.HOT,
            maturity = Maturity.EXPERIMENTAL,
        ),
        FeatureSpec(
            key = RECOGNITION_LOOK,
            group = FeatureGroup.SCREEN_RECOGNITION,
            // 胶囊显隐的八项收进第三层：详情页只放「传送门胶囊」一行（用户 2026-10-26）
            presentation = FeaturePresentation(drillGroups = setOf("portal_capsules")),
            scope = listOf(ScopeTarget.CONTENT_EXTENSION, ScopeTarget.VOICE_ASSIST),
            restart = RestartPolicy.HOT_RELOAD,
            maturity = Maturity.EXPERIMENTAL,
            prefs = listOf(
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "full_bleed"), labelKey = "pref.recognition_look.full_bleed", hintKey = "pref.recognition_look.full_bleed.hint", default = false, group = "xiaoai"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "horizontal"), labelKey = "pref.recognition_look.horizontal", hintKey = "pref.recognition_look.horizontal.hint", default = false, group = "xiaoai"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "compact"), labelKey = "pref.recognition_look.compact", hintKey = "pref.recognition_look.compact.hint", default = false, group = "xiaoai"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "capsule"), labelKey = "pref.recognition_look.capsule", hintKey = "pref.recognition_look.capsule.hint", default = false, group = "portal"),
                // 传送门胶囊显隐：全部在数据层拦（卡片不创建），实现见 PortalCapsuleFilter。
                // 语义是「隐藏」：默认 false = 显示 = 原生；**不进 ContentCapabilities.names**，
                // 否则没写过的值会跟随总开关，开着识屏样式的用户一升级就全被隐藏。
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "hide_ner_url"), labelKey = "pref.recognition_look.hide_ner_url", default = false, group = "portal_capsules"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "hide_ner_tel"), labelKey = "pref.recognition_look.hide_ner_tel", default = false, group = "portal_capsules"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "hide_ner_loc"), labelKey = "pref.recognition_look.hide_ner_loc", default = false, group = "portal_capsules"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "hide_ner_mail"), labelKey = "pref.recognition_look.hide_ner_mail", default = false, group = "portal_capsules"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "hide_devices"), labelKey = "pref.recognition_look.hide_devices", hintKey = "pref.recognition_look.hide_devices.hint", default = false, group = "portal_capsules"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "hide_cloud"), labelKey = "pref.recognition_look.hide_cloud", hintKey = "pref.recognition_look.hide_cloud.hint", default = false, group = "portal_capsules"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "hide_commerce"), labelKey = "pref.recognition_look.hide_commerce", hintKey = "pref.recognition_look.hide_commerce.hint", default = false, group = "portal_capsules"),
                PrefSpec.Toggle(key = p(RECOGNITION_LOOK, "hide_expanded_recommend"), labelKey = "pref.recognition_look.hide_expanded_recommend", hintKey = "pref.recognition_look.hide_expanded_recommend.hint", default = false, group = "portal_capsules"),
            ),
        ),

        // ── 视觉与小部件 ──────────────────────────────────────────────────
        FeatureSpec(
            key = WIDGET_STYLE,
            group = FeatureGroup.APPS,
            scope = listOf(ScopeTarget.PERSONAL_ASSISTANT),
            // ⚠️ 必须是 HOT_RELOAD，**不要改成 HOT**。
            // 它改的是 View 的 setTag / setOutlineProvider，uninstall() 无法逐个撤销；
            // 只有重启负一屏进程、让 PA 重新初始化，关态才真正等于「未安装模块」。
            restart = RestartPolicy.HOT_RELOAD,
            // 已实现（三个落点都有字节码实证），但**未在设备上跑过**。
            maturity = Maturity.EXPERIMENTAL,
            prefs = listOf(
                PrefSpec.Choice(
                    key = p(WIDGET_STYLE, "scale_mode"),
                    labelKey = "pref.widget_style.scale_mode",
                    default = "auto",
                    options = listOf(
                        PrefSpec.Choice.Option("fit", "pref.widget_style.scale_mode.fit"),
                        PrefSpec.Choice.Option("fill", "pref.widget_style.scale_mode.fill"),
                        PrefSpec.Choice.Option("auto", "pref.widget_style.scale_mode.auto"),
                    ),
                ),
                PrefSpec.Toggle(
                    key = p(WIDGET_STYLE, "unify_corner"),
                    labelKey = "pref.widget_style.unify_corner",
                    hintKey = "pref.widget_style.unify_corner.hint",
                    default = true,
                ),
                PrefSpec.Toggle(
                    key = p(WIDGET_STYLE, "blur_background"),
                    labelKey = "pref.widget_style.blur_background",
                    default = false,
                ),
                // 照 Flyme 给第三方小部件统一外壳：强制圆角（与系统小部件同一套分档）+ 按格子通知尺寸，
                // 底色、字体不动；小米自己的小部件不动。2026-10-25 随「添加任意小部件」一起上架。
                PrefSpec.Toggle(
                    key = p(WIDGET_STYLE, "hyperos_shell"),
                    labelKey = "pref.widget_style.hyperos_shell",
                    hintKey = "pref.widget_style.hyperos_shell.hint",
                    default = true,
                ),
            ),
        ),

        // ── 应用与快捷方式 ────────────────────────────────────────────────
        //
        // ⚠️ 聚合支付**已在界面上整组下架**（2026-09-19 执行）：
        //    三个入口里只有一个 `lock_screen` 真的做到，另两个是**结构性做不到**
        // 留在列表里就只剩一个「只有一个选项的下拉」——
        //    那正是本项目最忌讳的「看得见、设不了」。
        //    同样只摘入口：键与 hook 实现保留，已开用户的运行行为不变。
        FeatureSpec(
            key = AGG_PAYMENT,
            group = FeatureGroup.APPS,
            scope = listOf(ScopeTarget.SYSTEM_UI),
            restart = RestartPolicy.PROCESS_RESTART,
            // 只实现了 lock_screen 一个入口（指纹快捷环那格）。
            // 另两个入口是**结构性做不到**。
            maturity = Maturity.EXPERIMENTAL,
            presentation = FeaturePresentation(listed = false),
            prefs = listOf(
                PrefSpec.Choice(
                    key = p(AGG_PAYMENT, "entry"),
                    labelKey = "pref.agg_payment.entry",
                    // 默认值必须是 lock_screen：另两个入口（长按桌面图标 / 控制中心磁贴）
                    // 已被实测判定为**结构性做不到**（桌面是 Dart/Rust；QS 磁贴需要组件声明权）。
                    // 默认值指向做不到的那个，效果就是「开箱即用时装 0 个 hook」——
                    // 和 miui_widget_flag 当初的毛病同源。
                    default = "lock_screen",
                    options = listOf(
                        PrefSpec.Choice.Option("lock_screen", "pref.agg_payment.entry.lock_screen"),
                    ),
                ),
                PrefSpec.Toggle(
                    key = p(AGG_PAYMENT, "code_first"),
                    labelKey = "pref.agg_payment.code_first",
                    hintKey = "pref.agg_payment.code_first.hint",
                    default = true,
                ),
            ),
        ),

        FeatureSpec(
            key = CLEAN_STATUSBAR,
            group = FeatureGroup.APPS,
            // SystemUI：演示模式 / 藏图标 / 录屏；system_server：截图前先换状态栏（截图服务是 com.miui.screenshot，不在作用域）
            scope = listOf(ScopeTarget.SYSTEM_UI, ScopeTarget.SYSTEM_SERVER),
            // 子项在 hook 里现读；总开关热装卸（卸载时清掉本模块的 disable 记录、退出自己开的演示模式）
            restart = RestartPolicy.HOT,
            maturity = Maturity.EXPERIMENTAL,
            presentation = FeaturePresentation(preview = FeaturePreview.STATUS_BAR),
            prefs = listOf(
                PrefSpec.Toggle(
                    key = p(CLEAN_STATUSBAR, CleanStatusBar.HIDE_NOTIFICATION_ICONS),
                    labelKey = "pref.clean_statusbar.hide_notification_icons",
                    hintKey = "pref.clean_statusbar.hide_notification_icons.hint",
                    default = true,
                ),
                PrefSpec.Toggle(
                    key = p(CLEAN_STATUSBAR, CleanStatusBar.HIDE_ISLAND),
                    labelKey = "pref.clean_statusbar.hide_island",
                    hintKey = "pref.clean_statusbar.hide_island.hint",
                    default = true,
                ),
                PrefSpec.Toggle(
                    key = p(CLEAN_STATUSBAR, CleanStatusBar.AUTO_SCREENSHOT),
                    labelKey = "pref.clean_statusbar.auto_screenshot",
                    hintKey = "pref.clean_statusbar.auto_screenshot.hint",
                    default = true,
                ),
                PrefSpec.Toggle(
                    key = p(CLEAN_STATUSBAR, CleanStatusBar.AUTO_RECORDING),
                    labelKey = "pref.clean_statusbar.auto_recording",
                    hintKey = "pref.clean_statusbar.auto_recording.hint",
                    default = true,
                ),
            ),
        ),

        FeatureSpec(
            key = CONTEXT_INSTALL,
            group = FeatureGroup.CLIPBOARD,
            scope = listOf(ScopeTarget.AI_ENGINE),
            restart = RestartPolicy.HOT,
            maturity = Maturity.EXPERIMENTAL,
        ),

        FeatureSpec(
            key = CONTEXT_MUSIC,
            group = FeatureGroup.CLIPBOARD,
            scope = listOf(ScopeTarget.AI_ENGINE, ScopeTarget.SHARE_SHEET),
            restart = RestartPolicy.HOT,
            maturity = Maturity.EXPERIMENTAL,
        ),

        FeatureSpec(
            key = CONTEXT_SCREEN_ON,
            group = FeatureGroup.CLIPBOARD,
            scope = listOf(ScopeTarget.SYSTEM_SERVER, ScopeTarget.SYSTEM_UI, ScopeTarget.AI_ENGINE),
            restart = RestartPolicy.HOT,
            maturity = Maturity.EXPERIMENTAL,
        ),

        FeatureSpec(
            key = TEXT_TOOLBAR,
            // 「杂项」组（用户 2026-10-26：选字菜单、分享排序这类零散功能放一起）
            group = FeatureGroup.APPS,
            // 工具栏画在每个 App 里，但「要不要加这两项」问的是 system_server（小爱清单的 meta-data），
            // 所以只注入 system_server —— staticScope 下注入不了任意 App（第一版因此 revert）
            scope = listOf(ScopeTarget.SYSTEM_SERVER),
            // hook 里现读总开关；但 App 侧缓存 ApplicationInfo，已打开的 App 要重开才变（文案写明）
            restart = RestartPolicy.HOT,
            maturity = Maturity.EXPERIMENTAL,
        ),

        FeatureSpec(
            key = SHARE_ORDER,
            group = FeatureGroup.APPS,
            scope = listOf(ScopeTarget.SHARE_SHEET),
            // 面板每次打开都是新的比较过程、hook 里现读清单：开关与清单都是改完下次打开面板生效
            restart = RestartPolicy.HOT,
            maturity = Maturity.EXPERIMENTAL,
            prefs = listOf(
                PrefSpec.OrderedList(
                    key = p(SHARE_ORDER, "pinned"),
                    labelKey = "pref.share_order.pinned",
                    hintKey = "pref.share_order.pinned.hint",
                    // 空 = 不改顺序（与未安装一致），没有「自动挑选」
                    default = emptyList(),
                    minCount = 1,
                    emptyLabelKey = "share_order.empty",
                    // 分享面板只有应用行，图标也由系统画
                    appsOnly = true,
                    pinnedFirst = true,
                    iconTools = ListIconTools.NONE,
                ),
            ),
        ),

        // ── 基础设施 ──────────────────────────────────────────────────────

        FeatureSpec(
            key = PASSCODE_ISLAND,
            // 归「识屏」组：本质都是「内容 → 建议」的兄弟（剪贴板口令 vs 屏幕内容），
            // 不为此新增一个用户可见分组（界面做减法）。
            group = FeatureGroup.CLIPBOARD,
            // 双进程：system_server 观察剪贴板（发射侧）+ SystemUI 代发岛通知（展示侧）。
            // 两个进程各装一个 Feature 实现，共用这一个键（HookRegistry 按进程名筛）。
            scope = listOf(ScopeTarget.SYSTEM_SERVER, ScopeTarget.SYSTEM_UI, ScopeTarget.AI_ENGINE),
            // 两侧行为都只在进程启动时装配（hook ClipboardService / 注册接收器），
            // 开关切换需要重启对应进程
            restart = RestartPolicy.PROCESS_RESTART,
            // 代码就绪但岛通知三态渲染未经真机验证（focus.param 内部字段是 P0 待调项）
            maturity = Maturity.EXPERIMENTAL,
            prefs = listOf(
                PrefSpec.Toggle(
                    key = p(PASSCODE_ISLAND, CopyDirect.ENGINE_ENABLED),
                    labelKey = "pref.copy_direct.engine",
                    hintKey = "pref.copy_direct.engine.hint",
                    default = false,
                    group = "copy_direct_interaction",
                ),
                PrefSpec.Choice(
                    key = p(PASSCODE_ISLAND, CopyDirect.MODE),
                    labelKey = "pref.copy_direct.mode",
                    hintKey = "pref.copy_direct.mode.hint",
                    default = "island",
                    options = listOf(
                        PrefSpec.Choice.Option("native", "pref.copy_direct.mode.native"),
                        // 选中超级岛时说明：岛由 SystemUI 发出（PasscodeIslandPresenter），设置 App 的通知开关
                        // 显示为关是正常的 —— 它不声明也不需要 POST_NOTIFICATIONS（真机 appops = ignore）
                        PrefSpec.Choice.Option("island", "pref.copy_direct.mode.island", noteKey = "pref.copy_direct.mode.island.note"),
                    ),
                    group = "copy_direct_interaction",
                    dependsOn = PrefDependency(CopyDirect.ENGINE_ENABLED, "pref.copy_direct.engine.required"),
                ),
                PrefSpec.Toggle(
                    key = p(PASSCODE_ISLAND, CopyDirect.SAME_APP),
                    labelKey = "pref.copy_direct.same_app",
                    hintKey = "pref.copy_direct.same_app.hint",
                    default = false,
                    group = "copy_direct_content",
                    dependsOn = PrefDependency(CopyDirect.ENGINE_ENABLED, "pref.copy_direct.engine.required"),
                ),
                PrefSpec.Screen(
                    key = p(PASSCODE_ISLAND, CopyDirect.ICON_PACK),
                    labelKey = "pref.copy_direct.icon_pack",
                    hintKey = "pref.copy_direct.icon_pack.hint",
                    group = "copy_direct_interaction",
                    dependsOn = PrefDependency(CopyDirect.ENGINE_ENABLED, "pref.copy_direct.engine.required"),
                ),
                PrefSpec.Choice(
                    key = p(PASSCODE_ISLAND, "window_preset"),
                    labelKey = "pref.passcode_island.window_preset",
                    default = "flyme",
                    group = "copy_direct_interaction",
                    options = listOf(
                        PrefSpec.Choice.Option("system", "pref.passcode_island.window_preset.system"),
                        PrefSpec.Choice.Option("flyme", "pref.passcode_island.window_preset.flyme"),
                        PrefSpec.Choice.Option("fullscreen", "pref.passcode_island.window_preset.fullscreen"),
                    ),
                ),
                PrefSpec.Toggle(
                    key = p(PASSCODE_ISLAND, "long_press_chooser"),
                    labelKey = "pref.passcode_island.long_press_chooser",
                    hintKey = "pref.passcode_island.long_press_chooser.hint",
                    default = true,
                    group = "copy_direct_interaction",
                ),
                PrefSpec.Slider(
                    key = p(PASSCODE_ISLAND, "timeout_seconds"),
                    labelKey = "pref.passcode_island.timeout_seconds",
                    default = PasscodeIsland.AUTO_CANCEL_MS / 1000f,
                    min = PasscodeIsland.MIN_TIMEOUT_SECONDS,
                    max = PasscodeIsland.MAX_TIMEOUT_SECONDS,
                    steps = 297,
                    unit = "秒",
                    group = "copy_direct_interaction",
                    recommended = 5f..30f,
                ),
                PrefSpec.Toggle(
                    key = p(PASSCODE_ISLAND, "generic_url"),
                    labelKey = "pref.passcode_island.generic_url",
                    hintKey = "pref.passcode_island.generic_url.hint",
                    default = true,
                    group = "copy_direct_content",
                ),
                PrefSpec.Toggle(
                    key = p(PASSCODE_ISLAND, "numeric_tracking"),
                    labelKey = "pref.passcode_island.numeric_tracking",
                    hintKey = "pref.passcode_island.numeric_tracking.hint",
                    default = false,
                    group = "copy_direct_content",
                ),
                // 自定义规则的编辑/导入/导出页。它不是参数行（没有可调的值），
                // 是一个完整编辑面的入口 —— 数据结构与匹配语义在 core/PasscodeCustomRules.kt。
                PrefSpec.Screen(
                    key = p(PASSCODE_ISLAND, "custom_rules"),
                    labelKey = "pref.passcode_island.custom_rules",
                    hintKey = "pref.passcode_island.custom_rules.hint",
                    group = "copy_direct_content",
                ),
            ),
        ),
    )

    private val byKey = features.associateBy { it.key }

    fun find(key: String): FeatureSpec? = byKey[key]

    /**
     * 后端实现用。传进来的都是 Catalog 自己的常量，缺失即编程错误。
     *
     * 注意：这个 `error()` 在 hook 进程里会以 ExceptionInInitializerError 形式出现，
     * 但调用点（[io.github.kotoriinsky.tsuzuri.hook.HookRegistry] 的类初始化）被入口的
     * `runCatching` 包着，所以最坏结果是「特性没装」，不会崩宿主进程。
     */
    fun require(key: String): FeatureSpec =
        byKey[key] ?: error("feature missing from Catalog: $key")

    /**
     * [MIUI_WIDGET_FLAG] 的白名单包名集合（StringSet）。
     *
     * 没有对应的 [PrefSpec]：界面暂时不提供应用多选控件。
     * 在补上之前，这个特性只能靠 `system_wide` 全量生效 —— 这是刻意的，
     * 全量注入恰恰是当初没能解决问题的那条路。
     */
    val MIUI_WIDGET_PACKAGES_KEY: String = Keys.pref(MIUI_WIDGET_FLAG, "packages")

    /** [MIUI_WIDGET_FLAG] 的「全量生效」开关。默认 false。 */
    val MIUI_WIDGET_SYSTEM_WIDE_KEY: String = Keys.pref(MIUI_WIDGET_FLAG, "system_wide")

    fun byGroup(): Map<FeatureGroup, List<FeatureSpec>> =
        FeatureGroup.entries.associateWith { g -> features.filter { it.group == g } }
            .filterValues { it.isNotEmpty() }

    /**
     * **全部**特性用到的进程（含搁置与已下架的）。
     *
     * 后端口径：`scope.list` 与「历史上可能被打开过什么」都算在内。
     */
    val requiredScopes: Set<ScopeTarget> = features.flatMap { it.scope }.toSet()

    /**
     * **界面上看得见**的特性用到的进程 —— 作用域自检与「逐项授权」用这一个。
     *
     * 为什么要有第二个集合（2026-09-19）：`requiredScopes` 把已下架的特性也算进去，
     * 于是首页会要求用户去 LSPosed 里勾上**任何一个界面开关都不会用到**的进程
     * （负一屏、system_server 这些只属于 `listed = false` 的特性）。
     * 用户照做了，也永远看不出它有什么用 —— 那正是「作用域自检」失去信任的方式。
     *
     * 判据与模块页/搜索同源（[settingsFeatures]），所以下架一个特性时，
     * 界面上要的权限会**跟着一起少一项**，不会留下孤儿。
     */
    val settingsScopes: Set<ScopeTarget> = settingsFeatures.flatMap { it.scope }.toSet()
}
