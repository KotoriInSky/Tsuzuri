package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.ActivityPresets
import io.github.kotoriinsky.tsuzuri.core.ActivityTargets
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpTextStyle
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.readAppsMode
import io.github.kotoriinsky.tsuzuri.ui.state.listTakesEffect

/**
 * 填活动（**活动型自定义项**，清单里的第四类）。
 *
 * ## 这一页回答的问题
 *
 * 「微信扫一扫」「小爱识屏」这类页面有自己的**活动全名**
 * （`com.tencent.mm/.plugin.scanner.ui.BaseScanUI`），把它挂进扇子就能一步直达 ——
 * 但这个名字**既查不到也猜不出**：
 *
 *  - **查不到**：`android:exported="false"` 的活动对外不可见，
 *    `queryIntentActivities` 返回不了（实测小爱只列出 3 个活动，识屏页不在其中，
 * ）。这是系统设计，不是我们的权限问题。
 *  - **猜不出**：**包名与活动类名前缀可以完全不同** —— 小爱是最好的例子：
 *    安装包名是 `com.miui.voiceassist`，而识屏页的类名却是
 *    `com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity`。
 *    小米历史遗留（包名改过名、类名没跟着改）。
 *
 * 所以这一页做三件事把它变得可用：
 *
 *  1. **当场校验**（[ActivityTargets.parse]，纯逻辑在 core、有单测）——
 *     错在哪一条**逐条分开说**，而不是写进去再静默失败；
 *  2. **预置项一键填入**（[ActivityPresets.ALL]）—— 常用页面不用自己查类名；
 *  3. **现有项可看可删**，顺序与图标在「扇形里的项」那一页调。
 *
 * ## 为什么能和「导入快捷方式」不同，在设置界面当场校验
 *
 * 校验是**纯字符串逻辑**（`ActivityTargets.check` 在 core、不依赖 `android.*`）。
 * 对比「导入快捷方式」那一页：`id 到底存不存在`只有 SystemUI 查得到（权限限制），
 * 所以那一页只能「写进去再说」。而活动名的**格式**合法性这一页当场就能判，
 * 也就必须当场说 —— 与 `LinkImportScreen` 同一条理由。
 *
 * ⚠️ **但「格式合法」不等于「点了能开」**：有些页面需要前置状态
 * （小爱识屏要有截图来源）。这一页**只保证格式**，
 * 能不能开由启动那一刻的日志回答（`FanShortcuts` 分级日志 + toast）。
 * 不要在这里暗示「填对了就一定好使」。
 *
 * ## 包名也要填对，而且这个错特别难自查
 *
 * 格式校验**判断不了包名指向的那个包里有没有这个活动**（那要包管理器查询，
 * 属启动期）。实测踩过：`com.miui.voiceassistProxy` 是**真实存在的包**
 * （小米预装的代理壳），但它里面只有一个 `MainActivity`，没有识屏页 ——
 * 于是「包名真、活动名真，拼在一起不存在」。
 * 所以预置项、以及下面那句提示，都在帮用户避开这一条。
 *
 * ## 写入口只有一个
 *
 * 写入一律走 [FanItems]（保证「编码一致 + 空清单删键」两条契约），
 * 所以「顺序就是扇子里的槽位顺序」与「改完立刻生效」（配置通道）
 * 自动成立 —— 加一条活动之后不需要重启任何进程。
 */
@Composable
fun ActivityImportScreen(
    spec: PrefSpec.OrderedList,
    padding: PaddingValues,
) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()

    val featureKey = remember(spec) { FanItems.featureKeyOf(spec) }
    /** 清单原样（含它现在的顺序）。增删都以它为准。 */
    val items = FanItems.read(prefs, spec)
    /** `auto` 时这份清单存着但不生效 —— 必须显式说出来。 */
    val manual = readAppsMode(featureKey) == "manual"

    // 清单里现有的活动项。带**在完整清单里的下标**（撤销要插回原处，顺序才不会变）。
    // 分类只此一处（core 的 ActivityTargets.isActivity），所以不会出现
    // 「界面认它是活动、hook 认它是别的」这种两边都编译得过的漂移
    val activities = remember(items) {
        items.withIndex().filter { ActivityTargets.isActivity(it.value) }
            .map { it.index to it.value }
    }

    var input by rememberSaveable { mutableStateOf("") }
    /** 被移除的那一条 + 它原来的下标。 */
    var removed by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val typed = input.trim()
    // 空输入不报错（还没开始填就说「格式不对」是噪音），null = 「还没填」。
    // parse 接受带前缀与不带前缀两种形式，这里传的是用户原样输入
    val result = remember(typed) {
        if (typed.isEmpty()) null else ActivityTargets.parse(typed)
    }
    // 可以写进清单的规范形式（`activity:` + 包名/活动名）；null = 没通过校验
    val normalized = (result as? ActivityTargets.Result.Ok)
        ?.let { ActivityTargets.stored(it.packageName, it.activityName) }
    val joined = normalized != null && normalized in items

    Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
        kit.VSpace(8.dp)

        // ── 状态区（固定区域，列表自己滚动，所以这几条永远看得见）──
        if (activities.isNotEmpty() && items.size < spec.minCount) {
            // 门槛与模式两条直接复用「扇形里的项」那一页的文案：同一件事在两页
            // 用不同的话说，用户会以为是两件事
            kit.Banner(
                fpString("fan_items.below_min", spec.minCount - items.size),
                FpEmphasis.WARNING,
            )
            kit.VSpace(4.dp)
        } else if (!listTakesEffect(featureKey, manual) && activities.isNotEmpty()) {
            kit.Banner(fpString("fan_items.mode.auto"), FpEmphasis.WARNING)
            kit.VSpace(4.dp)
        } else if (activities.isNotEmpty()) {
            kit.Banner(fpString("activity_import.count", activities.size), FpEmphasis.ACCENT)
            kit.VSpace(4.dp)
        }

        removed?.let { (key, index) ->
            kit.Banner(
                text = fpString("activity_import.removed", displayOf(key)),
                emphasis = FpEmphasis.MUTED,
                actionLabel = fpString("activity_import.undo"),
                // 插回原下标（不是追加到末尾）：撤销就该回到原样，包括顺序
                onAction = {
                    FanItems.write(spec.key, FanItems.inserted(items, index, key))
                    removed = null
                },
            )
            kit.VSpace(4.dp)
        }

        kit.SectionHeader(fpString("activity_import.section.new"))

        // ⚠️ 这一句是**本页最重要的一行**：包名与类名前缀可以完全不同（小爱就是），
        // 以及「组件存在与否这一页判不了」。写在输入框正上方，用户填之前就看见。
        kit.Paragraph(fpString("activity_import.policy"), FpEmphasis.MUTED)
        kit.VSpace(4.dp)
        kit.Paragraph(fpString("activity_import.example"), FpEmphasis.MUTED, FpTextStyle.CAPTION)
        kit.VSpace(6.dp)

        kit.TextInput(
            value = input,
            modifier = Modifier.padding(horizontal = FpSpace.xxl),
            keyboardType = KeyboardType.Uri,
            label = fpString("activity_import.input.label"),
            onValueChange = { input = it },
        )
        kit.VSpace(6.dp)

        // ── 点一下填入的预置项（省掉用户去查「微信扫一扫的类名到底是什么」）──
        kit.Paragraph(fpString("activity_import.preset.hint"), FpEmphasis.MUTED, FpTextStyle.CAPTION)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = FpSpace.l),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (preset in ActivityPresets.ALL) {
                kit.Button(
                    text = fpString(preset.labelKey),
                    onClick = { input = preset.target },
                    // 高亮 = 「输入框里现在就是它」，而不是「已经加进清单」——
                    // 后者由下面那行状态文字说（与「填链接」页同一口径）
                    primary = typed == preset.target,
                )
            }
        }
        kit.VSpace(6.dp)

        // ── 校验结果：**每一类原因一句人话**，就在输入框下面 ──
        when (val r = result) {
            null -> kit.Paragraph(fpString("activity_import.state.need"), FpEmphasis.MUTED)

            is ActivityTargets.Result.Rejected -> kit.Paragraph(
                rejectionText(r.reason),
                FpEmphasis.WARNING,
            )

            // 通过了：把**扇子里会显示的那个名字**当场打出来（识别优于回忆）。
            // 已经在清单里时改说那一句：否则用户会对着一个按不动的「添加」按钮猜原因
            is ActivityTargets.Result.Ok -> kit.Paragraph(
                if (joined) {
                    fpString("activity_import.state.joined")
                } else {
                    fpString(
                        "activity_import.preview",
                        ActivityTargets.fallbackLabel(r.activityName),
                    )
                },
                if (joined) FpEmphasis.MUTED else FpEmphasis.POSITIVE,
            )
        }

        kit.VSpace(6.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FpSpace.l),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            kit.Button(
                text = fpString("activity_import.add"),
                onClick = {
                    // 空清单分支由 FanItems 兜住（写空 = 删键），这里只需要给它一个有序清单
                    normalized?.let { FanItems.write(spec.key, FanItems.appended(items, it)) }
                },
                primary = true,
                enabled = normalized != null && !joined,
            )
            kit.Button(
                text = fpString("activity_import.remove"),
                onClick = {
                    normalized?.let { key ->
                        // 撤销记的是**完整清单里的下标**，不是活动子列表里的下标
                        val index = items.indexOf(key)
                        FanItems.write(spec.key, FanItems.without(items, key))
                        if (index >= 0) removed = key to index
                    }
                },
                enabled = joined,
            )
        }
        kit.VSpace(6.dp)

        // ── 已在清单里的活动：可看、可删。顺序与图标在「扇形里的项」那一页调 ──
        kit.SectionHeader(fpString("activity_import.section.current"))
        kit.Card {
            if (activities.isEmpty()) {
                kit.Paragraph(fpString("activity_import.empty"), FpEmphasis.MUTED)
                kit.Paragraph(
                    fpString("activity_import.empty.hint"),
                    FpEmphasis.MUTED,
                    FpTextStyle.CAPTION,
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    activities.forEach { (_, key) ->
                        val target = ActivityTargets.targetOf(key)
                        val bad = ActivityTargets.check(target) != null
                        kit.ActionRow(
                            title = displayOf(key),
                            // 副标题是**清单项原样**：排障时只有它和配置里的那一行对得上
                            summary = key,
                            // 手改配置写进去的非法项照样列在这儿，但**明说它点了不会启动** ——
                            // 「填了却没反应」正是这个页面要消灭的那类静默失败
                            trailingText = if (bad) {
                                fpString("activity_import.row.bad")
                            } else {
                                fpString("common.edit")
                            },
                            onClick = { input = target },
                        )
                    }
                }
            }
        }

        kit.VSpace(16.dp)
    }
}

/**
 * 这一条活动在界面上的显示名。**与扇子、清单页同一个函数**
 *（`ActivityTargets.fallbackLabel`），所以三处不会出现两种名字。
 *
 * 用 `targetOf` 而不是 `parse(...)`：手改配置写进去的非法串也要有一个能显示、
 * 能删掉的名字（`fallbackLabel` 对任何输入都返回非空串），
 * 绝不能因为「它不合法」就画一行空标题。
 */
private fun displayOf(key: String): String =
    ActivityTargets.fallbackLabel(ActivityTargets.targetOf(key))

/**
 * 校验失败的原因 → 文案。**每一种都单独一句**（见页面注释）。
 *
 * `when` 是穷尽的：`ActivityTargets.Rejection` 将来多一种原因时这里编译不过，
 * 逼着补一句人话 —— 比运行期落到 `else` 显示一个键名好。
 */
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
