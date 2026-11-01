package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.kotoriinsky.tsuzuri.core.isUserToggleable
import io.github.kotoriinsky.tsuzuri.core.blockedHintKey
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.CopyDirect
import io.github.kotoriinsky.tsuzuri.core.Maturity
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems

/**
 * 特性与参数的通用行。
 *
 * 这里是「界面由 Catalog 生成」落地的地方：没有任何一处写死 featureKey 或 prefKey，
 * 加一个特性只需要在 Catalog 里加一条 + 在 strings.xml 里加两行文案。
 */

/** 特性总开关。PARKED / PLANNED 的特性禁用并带标记。 */
@Composable
fun FeatureToggleRow(spec: FeatureSpec, prefs: Prefs) {
    val kit = LocalFpKit.current
    val toggleable = spec.maturity.isUserToggleable && featureScopeReady(spec)
    kit.SwitchRow(
        title = fpString(spec.titleI18n),
        summary = fpString(spec.summaryI18n),
        checked = prefs.isEnabled(spec),
        onCheckedChange = { ConfigStore.putBoolean(io.github.kotoriinsky.tsuzuri.core.Keys.enabled(spec.key), it) },
        enabled = toggleable,
    )
}

/** 一个参数控件。按 [PrefSpec] 的密封类型分派 —— 新增类型时编译器会在这里报错。 */
@Composable
fun PrefRow(
    spec: PrefSpec,
    prefs: Prefs,
    enabled: Boolean,
    /** 参数自己的编辑页（应用多选）要跳走，所以这里需要导航入口。 */
    onNavigate: (Dest) -> Unit,
    /**
     * 拖动中的滑杆值：**只更新本地草稿**（界面与预览立刻跟手），不落盘。
     * `null` = 直接写配置（老路径，详情页那种没有预览台的地方用它）。
     */
    onDraftFloat: ((String, Float) -> Unit)? = null,
    /** 抬手：把这一项的草稿落盘（`null` = 由 `onValueChange` 直接写）。 */
    onCommitFloat: ((String) -> Unit)? = null,
    /**
     * 「此项不影响预览」那句说明（已解析好的文案），`null` = 不加。
     *
     * 由页面算好再传进来，而不是这里自己判断：判据是「**这一页**有没有预览」
     * （core 的 `PrefSpec.feedsFanPreview()` + 页面的预览分派），
     * 而这一行不知道自己在哪一页。
     */
    notFeedingPreviewNote: String? = null,
) {
    val kit = LocalFpKit.current
    // 依赖检查在这里做，而不是交给每个调用方：调用方（详情页 / 分组页）只关心
    // 「这个特性开着没」，而「这一项依赖的那个开关开着没」是参数**自己的**属性。
    // 交给调用方就等于每个页面都要记得查一遍，漏一个页面就复现一次
    // 「拖了没反应的滑杆」——正是这条要修掉的东西。
    //
    // 特性总开关关着时不查依赖（`enabled == false` 已经在禁用了，缺的是特性级的状态说明，
    // 那是页面上另一处的事）—— 所以这里只在「特性开着但依赖没满足」时给原因。
    val blocked = if (enabled && spec.dependsOn != null) {
        spec.blockedHintKey(prefs, featureKeyOf(spec))
    } else {
        null
    }
    PrefRowContent(
        spec = spec,
        prefs = prefs,
        enabled = enabled && blocked == null && io.github.kotoriinsky.tsuzuri.core.Catalog.find(featureKeyOf(spec))?.let { featureScopeReady(it) } == true,
        onNavigate = onNavigate,
        disabledHint = blocked,
        onDraftFloat = onDraftFloat,
        onCommitFloat = onCommitFloat,
        notFeedingPreviewNote = notFeedingPreviewNote,
    )
}

/**
 * 拿一个参数所属的特性 key。
 *
 * 参数键的格式是 `<特性 key>.<参数名>`（[io.github.kotoriinsky.tsuzuri.core.Keys.pref]），
 * 所以特性 key 就是第一个 `.` 之前的那一段。**不引入新的字段**是刻意的：
 * 参数身上再存一份 `featureKey`，就有了两处事实（声明处 + 存储处），
 * 而它们不一致时的表现是「依赖的开关查的是另一个特性里的同名开关」——静默且难查。
 */
private fun featureKeyOf(spec: PrefSpec): String = spec.key.substringBefore('.')

/** 带「为什么不能点」的参数控件。只在内部用，公开入口是上面那个 [PrefRow]。 */
@Composable
private fun PrefRowContent(
    spec: PrefSpec,
    prefs: Prefs,
    enabled: Boolean,
    onNavigate: (Dest) -> Unit,
    disabledHint: String?,
    onDraftFloat: ((String, Float) -> Unit)?,
    onCommitFloat: ((String) -> Unit)?,
    notFeedingPreviewNote: String?,
) {
    val kit = LocalFpKit.current
    // 依赖没满足时，把「为什么」接在原本的说明后面 —— 只把控件变灰等于把用户扔在原地，
    // 他需要知道的是「因为上面那个开关关着」，而不是「这里点不动」。
    //
    // ⚠️ `disabledHint` 是**资源键**（来自 `PrefDependency.offHintKey`），必须过一遍
    // `fpString` 才能变成文案。第一次实现时漏了这一步，界面上直接把
    // `pref.corner.window_dep_off` 画给了用户 —— 而 `verifyStringKeys` 是绿的
    // （它检查的是「键有没有对应文案」，不检查「代码有没有真的取值」）。
    val disabledText = disabledHint?.let { fpString(it) }
    val hint: String? = spec.hintKey?.let { fpString(it) }
    // 「此项不影响预览」接在最后：它是这一行**最不重要**的一句（重要的话排在前面）。
    // 只在页面确实有预览、而这一项确实不进预览时才会非空（见 PrefRow 的参数注释）。
    val summary = listOfNotNull(hint, disabledText, notFeedingPreviewNote)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
    when (spec) {
        is PrefSpec.Toggle -> kit.SwitchRow(
            title = fpString(spec.labelKey),
            summary = summary,
            checked = prefs.value(spec),
            onCheckedChange = { ConfigStore.putBoolean(spec.key, it) },
            enabled = enabled,
        )

        is PrefSpec.Slider -> kit.SliderRow(
            title = fpString(spec.labelKey),
            summary = summary,
            value = prefs.value(spec),
            range = spec.min..spec.max,
            steps = spec.steps,
            valueText = formatValue(prefs.value(spec), spec.unit),
            // 拖动中只写草稿（界面 + 预览立刻跟手），抬手才落盘 —— 见 PrefRow 的参数注释。
            // 没有草稿通道时（详情页那种场景）退回老行为：每次变化直接写配置。
            onValueChange = { v ->
                if (onDraftFloat != null) onDraftFloat(spec.key, v) else ConfigStore.putFloat(spec.key, v)
            },
            onValueChangeFinished = if (onCommitFloat != null) {
                ({ onCommitFloat(spec.key) })
            } else {
                null
            },
            enabled = enabled,
            defaultValue = spec.default,
            recommended = spec.recommended,
        )

        // 选项行 + 选中项说明是两个兄弟节点：外面一层若是 AnimatedVisibility（叠放布局）会画到同一处 ——
        // 2026-10-23 真机「超级岛由系统界面发出」压在「提示方式」标题上。所以自己包一层 Column。
        is PrefSpec.Choice -> androidx.compose.foundation.layout.Column {
            val current = prefs.value(spec)
            val index = spec.options.indexOfFirst { it.value == current }.coerceAtLeast(0)
            val labels = spec.options.map { fpString(it.labelKey) }
            // 多选一的形态分层（2026-09-18 拍板）：
            //  - ≤4 项且每项都短 → SegmentedRow，全部选项平铺可见，不用点开；
            //  - 其余 → SpinnerRow，键值行 + 弹出选单。
            // 竖排单选钮（ChoiceRow）已从契约退役 —— 同样一件事，
            // 分段一行放下就不该竖着占四行。判据用「文案长度」而不是字符数硬编码：
            // 本地化后的标签是唯一事实，中英长短不同，全部短才用分段。
            val segmented = labels.size in 2..4 && labels.all { it.length <= 6 }
            // 视觉参数（每个选项都有示意图）走图卡，优先于分段 / 下拉
            val drawings = spec.options.map { choiceIllustration(spec.key, it.value) }
            if (drawings.all { it != null }) {
                kit.ImageChoiceRow(
                    title = fpString(spec.labelKey),
                    summary = summary,
                    options = labels.zip(drawings) { label, draw -> io.github.kotoriinsky.tsuzuri.ui.kit.FpImageOption(label, draw!!) },
                    selectedIndex = index,
                    onSelect = { i -> ConfigStore.putString(spec.key, spec.options[i].value) },
                    enabled = enabled,
                )
            } else if (spec.dropdown) {
                kit.DropdownRow(
                    title = fpString(spec.labelKey),
                    summary = summary,
                    options = labels,
                    selectedIndex = index,
                    onSelect = { i -> ConfigStore.putString(spec.key, spec.options[i].value) },
                    enabled = enabled,
                )
            } else if (segmented) {
                kit.SegmentedRow(
                    title = fpString(spec.labelKey),
                    summary = summary,
                    options = labels,
                    selectedIndex = index,
                    onSelect = { i -> ConfigStore.putString(spec.key, spec.options[i].value) },
                    enabled = enabled,
                )
            } else {
                kit.SpinnerRow(
                    title = fpString(spec.labelKey),
                    summary = summary,
                    options = labels,
                    selectedIndex = index,
                    onSelect = { i -> ConfigStore.putString(spec.key, spec.options[i].value) },
                    enabled = enabled,
                )
            }
            // 选中项附带的一句说明（Option.noteKey）：只有「选了它就有一件必须知道的事」的选项才有
            spec.options.getOrNull(index)?.noteKey?.let { noteKey -> ChoiceNote(spec, current, noteKey) }
        }

        // 应用多选：这一行**只报当前状态**（自动挑选 / 已选几个），编辑在单独的页面里。
        // 为什么不在这里行内展开一个多选列表：行是高只有几十 dp 的容器，
        // 而清单有一两百项、还要搜索 —— 塞进来只能做成一个滚动区，反而比跳一页更难用。
        is PrefSpec.AppList -> {
            val count = prefs.value(spec).size
            // ⚠️ 「图标包」是 AppList 类型里的一个**特例**：它的取值也是一组包名，
            // 但候选集是**运行时枚举**出来的图标包（不是可启动应用），所以不能去
            // 应用多选页挑 —— 那里会列出两百个普通应用，用户永远找不到图标包。
            // 按**键后缀**分派（约定：`<特性>.icon_pack`），这样接线的人只要在 Catalog 里
            // 声明成 AppList 就够了，不需要新参数类型（Choice 的 options 必须静态声明，
            // 表达不了运行时列表 —— 这条理由也写在 hook 侧 FanTargets 里）。
            //
            // ⚠️ 2026-09-14：Catalog 里**已经没有** AppList 参数了（扇形清单换成了
            // OrderedList，逐条理由写在 Catalog 那条声明上）。这一支留着只为一件事：
            // sealed 类型的 when 必须穷举。所以它不再跳任何页面，只显示状态 ——
            // 跳到一个已经不适用的页面比「点了没反应」更糟。
            val iconPack = spec.key.endsWith(".icon_pack")
            kit.ActionRow(
                title = fpString(spec.labelKey),
                summary = summary,
                // 右侧直接给出「现在生效的是哪一条」，用户在跳走之前就能确认状态
                trailingText = if (iconPack) {
                    if (count == 0) {
                        fpString("icon_pack.none")
                    } else {
                        fpString("icon_pack.current", prefs.value(spec).sorted().first())
                    }
                } else if (spec.key.endsWith(".index_filter_packages") && count == 0) {
                    fpString("index_filter.empty.all")
                } else if (count == 0 && spec.emptyIsValid) {
                    // 黑名单：空集是**一份有效配置**（一个都不屏蔽），不是「没配过」。
                    // 这里如果照白名单写「自动选取」，意思就反了 —— 用户会以为系统在替他挑一批来屏蔽。
                    fpString("app_picker.state.unblocked")
                } else if (count < spec.minCount) {
                    fpString("app_picker.state.auto")
                } else {
                    fpString("app_picker.state.count", count)
                },
                // ⚠️ 2026-09-14 更正：这条分支**此前是禁用的**，理由是「Catalog 里已经没有
                // AppList 参数了」（扇形清单换成了 OrderedList）。本批（桌面应用自定义）又引入了
                // 两个 —— `launcher_packages` / `index_filter_packages` —— 而它们**必须可编辑**：
                // 用户要自己指定哪些包算桌面、哪些应用不进索引。
                // 图标包仍是特例（候选集是运行时枚举的图标包，不是可启动应用），所以它继续不可点。
                onClick = if (iconPack) null else ({ onNavigate(Dest.AppListPicker(spec)) }),
                enabled = !iconPack,
                navigates = !iconPack,
            )
        }

        // 有序清单（应用 + 快捷方式混排）。**看 / 删 / 增 / 排序 / 换图标全在清单页**，
        // 这一行只负责把人送进去。
        //
        // ⚠️ 右值原本有两条计数（「N 项设置」/「N 项 · 已改」），2026-09-18 用户拍板删掉：
        // 进组之前要回答的是「我要找的东西在不在这里」，那由**标题**回答，不是由数量回答；
        // 而「改没改过」进到清单里一眼就看见。
        // 只留「自动选取」这一条 —— 它描述的不是数量而是**当前走哪条路**
        // （清单为空 = 扇子里显示的是系统自动选的应用，不是用户选的），
        // 这正是用户会误判的那一件事（「我明明加了应用怎么没变」）。
        is PrefSpec.OrderedList -> {
            val items = FanItems.read(prefs, spec)
            kit.ActionRow(
                title = fpString(spec.labelKey),
                summary = summary,
                trailingText = if (items.isEmpty()) fpString(spec.emptyLabelKey) else null,
                onClick = if (enabled) ({ onNavigate(Dest.FanItems(spec)) }) else null,
                enabled = enabled,
                navigates = true,
            )
        }

        // 子页入口：没有可调的值，整行就是一个跳转（标题/说明全来自 Catalog 声明）。
        is PrefSpec.Screen -> kit.ActionRow(
            title = fpString(spec.labelKey),
            summary = summary,
            trailingText = null,
            onClick = if (enabled) ({
                if (spec.key == CopyDirect.key(CopyDirect.ICON_PACK)) {
                    onNavigate(Dest.IconPackPicker(spec.key.substringBeforeLast('.'), spec.labelKey,
                        previewPackage = "com.taobao.taobao"))
                } else onNavigate(Dest.PasscodeRules(spec))
            }) else null,
            enabled = enabled,
            navigates = true,
        )
    }
}

private fun formatValue(v: Float, unit: String): String {
    val n = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
    return if (unit.isEmpty()) n else "$n $unit"
}

/**
 * 该特性作用在哪些进程上。
 *
 * 注意这里用 for 循环而不是 `map { fpString(...) }`：`fpString` 是 @Composable，
 * 只能出现在 @Composable 上下文里，而 `joinToString` 的 lambda 不是。
 * 这是这个项目里最容易踩的一类编译错误。
 */
@Composable
fun ScopeSummary(spec: FeatureSpec): String {
    val names = localizedScopeNames(spec.scope)
    return fpString("common.scope.applies", names)
}

/** 把一组作用域目标拼成本地化文案。 */
@Composable
fun localizedScopeNames(scope: List<ScopeTarget>): String {
    val parts = ArrayList<String>(scope.size)
    for (target in scope) parts.add(fpString(target.labelI18n))
    return parts.joinToString("、")
}

/** 一个功能名下的全部配置键：开关 + 参数。 */
fun featureKeyScope(spec: FeatureSpec): (String) -> Boolean {
    val enabled = io.github.kotoriinsky.tsuzuri.core.Keys.enabled(spec.key)
    return { it == enabled || it.startsWith(spec.key + ".") }
}

/**
 * 把一个功能恢复出厂：开关回到默认（关）、参数全部清掉。
 * 调用方要撤销的话，先 `ConfigStore.checkpoint(featureKeyScope(spec))` 再调。
 */
fun resetFeature(spec: FeatureSpec) {
    val keys = ConfigStore.snapshot().keys.filter { it.startsWith(spec.key + ".") }.toSet() + spec.prefs.map { it.key }
    for (key in keys) ConfigStore.remove(key)
    ConfigStore.remove(io.github.kotoriinsky.tsuzuri.core.Keys.enabled(spec.key))
}

/**
 * 条件折叠：依赖满足时在父项下方展开，不满足时收起 ——
 * HyperOS 设置的画法（父开关关着，子项不在页面上；同一张卡、不缩进）。
 *
 * 页面**全部参数都画**、用 [visible] 控制，而不是先过滤再画：过滤会让行瞬间出现 / 消失，
 * 用户看不出是哪个开关带出了它们。弹簧取临界阻尼，与 MiuiX 卡片展开的观感一致。
 */
@Composable
internal fun CollapsiblePref(visible: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.expandVertically(
            androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f),
        ) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically(
            androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f),
        ) + androidx.compose.animation.fadeOut(),
    ) { content() }
}

/**
 * 选中项下方的说明。复制直达选「超级岛」时还看一眼勿扰模式。
 *
 * 2026-10-22 真机（勿扰「仅限优先通知」）：岛的两条通知都被勿扰标成拦截（`mIntercept=true`，
 * 压掉横幅 / 息屏 / 指示灯），但**超级岛照常弹出、展开**，状态栏与通知列表也照常 —— 这一档不警示。
 * 「完全静音」「仅闹钟」两档还没测，仍按「可能拦下」警示（措辞保留「可能」）。
 */
@Composable
private fun ChoiceNote(spec: PrefSpec.Choice, current: String, noteKey: String) {
    val kit = LocalFpKit.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val island = spec.key == io.github.kotoriinsky.tsuzuri.core.Keys.pref(
        io.github.kotoriinsky.tsuzuri.core.Catalog.PASSCODE_ISLAND,
        io.github.kotoriinsky.tsuzuri.core.CopyDirect.MODE,
    ) && current == "island"
    val dnd = island && runCatching {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE ||
            nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
    }.getOrDefault(false)
    if (dnd) {
        kit.Banner(fpString("pref.copy_direct.mode.island.dnd"), FpEmphasis.WARNING)
    } else {
        kit.Paragraph(fpString(noteKey), FpEmphasis.MUTED)
    }
    // 「开启通知权限」引导已撤（用户 2026-10-23：「无需开启通知权限」）：岛由 SystemUI 发出，
    // 本应用不声明 POST_NOTIFICATIONS，引导用户去开它只会误导。
}
