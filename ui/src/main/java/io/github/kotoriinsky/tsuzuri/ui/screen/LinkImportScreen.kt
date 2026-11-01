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
import io.github.kotoriinsky.tsuzuri.core.LinkTargets
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
 * 填链接（**链接型自定义项**，清单里的第三类）。
 *
 * ## 这一页回答的问题
 *
 * 「微信扫一扫」这类入口的真实形态是**一段链接**（`weixin://scanqrcode`），
 * 而不是一条必须由宿主应用发布的快捷方式 —— 后者在本 ROM 上常常枚举不到
 * （取证写在 `FanShortcuts.MATCH_FLAGS` 与 core 的 `LinkTargets` 类注释里）。
 * 所以这一页让用户直接填链接，通过校验之后写进**同一份清单**
 * （`corner_small_window.items`，与「添加快捷方式」「添加应用」写的是同一个键）。
 *
 * 参考实现 Anywhere-（`com.absinthe.anywhere_`）也是「让用户填一段 URI」的形态，
 * 但它**只校验非空**，能不能起来全看启动那一刻。我们比它严一档，见下面「安全」。
 *
 * ## 校验为什么能在设置界面做（而快捷方式那种做不到）
 *
 * 校验是**纯字符串逻辑**（`LinkTargets.check`，在 core、不依赖 `android.*`，有单测）。
 * 对比「导入快捷方式」那一页：它的 `id 到底存不存在`只有 SystemUI 查得到（权限限制），
 * 所以那一页只能「写进去再说」；而链接的合法性这一页**当场就能判**，也就必须当场说
 *（要求的正是这个：**非法 scheme 当场报错，而不是写进去再静默失败**）。
 *
 * 拒绝的原因**逐条分开显示**（缺协议头 / 协议头不合法 / 带显式组件 / 不支持的协议 /
 * 含空白 / 太长）：合成一句「格式不对」等于把「为什么」藏起来，用户只会反复试同一串字符。
 *
 * ## 安全（这一页是收口的第一道，`FanLinks.launch` 是第二道）
 *
 * 从系统界面进程启动一段**用户填的任意 URI** = 给配置文件开了任意启动口子
 * （SystemUI 是 uid 1000 的特权进程）。所以这里只放行**带 scheme 的隐式 intent**：
 * `component=` / `package=` / `intent:` 包裹 / `S.` selector 这类显式形式一律拒绝，
 * `file:` / `data:` / `javascript:` 这类本地与非应用协议也拒绝。完整取舍表在
 * `LinkTargets` 的类注释里。**两道校验都必须在**：配置可能被手改、被同步覆盖、
 * 来自旧版本 —— 写入时合法不等于启动时合法。
 *
 * ## 写入口只有一个
 *
 * 写入一律走 [FanItems]（它保证「编码一致 + 空清单删键」两条契约），所以
 * 「顺序就是扇子里的槽位顺序」与「改完立刻生效」（配置通道）
 * 自动成立 —— 加一条链接之后不需要重启任何进程。
 *
 * ## 链接项的显示名
 *
 * 由 `LinkTargets.fallbackLabel` 算出来（如 `weixin://scanqrcode` → `scanqrcode`），
 * **当场显示给用户看**（「扇子里显示为「scanqrcode」」）—— 用户不必先加进去、
 * 再去扇子里确认它叫什么（HCI「识别优于回忆」）。扇子与清单页用的是同一个函数，
 * 三处名字必然一致（见 `FanItemRef.label`）。
 */
@Composable
fun LinkImportScreen(
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

    // 清单里现有的链接项。带**在完整清单里的下标**（撤销要插回原处，顺序才不会变）。
    // 分类只此一处（core 的 LinkTargets.isLink），所以不会出现「界面认它是链接、
    // hook 认它是别的」这种两边都编译得过的漂移
    val links = remember(items) {
        items.withIndex().filter { LinkTargets.isLink(it.value) }.map { it.index to it.value }
    }

    var input by rememberSaveable { mutableStateOf("") }
    /** 被移除的那一条 + 它原来的下标。 */
    var removed by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val typed = input.trim()
    // 空输入不报错（还没开始填就说「格式不对」是噪音），null = 「还没填」
    val result = remember(typed) {
        if (typed.isEmpty()) null else LinkTargets.parse(typed)
    }
    // 可以写进清单的规范形式（`link:` + URI）；null = 没通过校验
    val normalized = (result as? LinkTargets.Result.Ok)?.let { LinkTargets.stored(it.uri) }
    val joined = normalized != null && normalized in items

    Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
        kit.VSpace(8.dp)

        // ── 状态区（固定区域，列表自己滚动，所以这几条永远看得见）──
        if (links.isNotEmpty() && items.size < spec.minCount) {
            // 门槛与模式两条直接复用「扇形里的项」那一页的文案：同一件事在两页
            // 用不同的话说，用户会以为是两件事
            kit.Banner(
                fpString("fan_items.below_min", spec.minCount - items.size),
                FpEmphasis.WARNING,
            )
            kit.VSpace(4.dp)
        } else if (!listTakesEffect(featureKey, manual) && links.isNotEmpty()) {
            kit.Banner(fpString("fan_items.mode.auto"), FpEmphasis.WARNING)
            kit.VSpace(4.dp)
        } else if (links.isNotEmpty()) {
            kit.Banner(fpString("link_import.count", links.size), FpEmphasis.ACCENT)
            kit.VSpace(4.dp)
        }

        removed?.let { (key, index) ->
            kit.Banner(
                text = fpString("link_import.removed", displayOf(key)),
                emphasis = FpEmphasis.MUTED,
                actionLabel = fpString("link_import.undo"),
                // 插回原下标（不是追加到末尾）：撤销就该回到原样，包括顺序
                onAction = {
                    FanItems.write(spec.key, FanItems.inserted(items, index, key))
                    removed = null
                },
            )
            kit.VSpace(4.dp)
        }

        kit.SectionHeader(fpString("link_import.section.new"))
        kit.Paragraph(fpString("link_import.policy"), FpEmphasis.MUTED)
        kit.VSpace(6.dp)

        kit.TextInput(
            value = input,
            modifier = Modifier.padding(horizontal = FpSpace.xxl),
            keyboardType = KeyboardType.Uri,
            label = fpString("link_import.input.label"),
            onValueChange = { input = it },
        )
        kit.VSpace(6.dp)

        // ── 点一下填入的示例（省掉用户去翻「微信的 scheme 到底怎么写」）──
        kit.Paragraph(fpString("link_import.preset.hint"), FpEmphasis.MUTED, FpTextStyle.CAPTION)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = FpSpace.l),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (preset in LINK_PRESETS) {
                kit.Button(
                    text = fpString(preset.labelKey),
                    onClick = { input = preset.uri },
                    // 高亮 = 「输入框里现在就是它」，而不是「已经加进清单」——
                    // 后者由下面那行状态文字说。两件事分开表达（与「添加快捷方式」页同一口径）
                    primary = typed == preset.uri,
                )
            }
        }
        kit.VSpace(6.dp)

        // ── 校验结果：**每一类原因一句人话**，就在输入框下面 ──
        when (val r = result) {
            null -> kit.Paragraph(fpString("link_import.state.need"), FpEmphasis.MUTED)

            is LinkTargets.Result.Rejected -> kit.Paragraph(
                rejectionText(r.reason),
                FpEmphasis.WARNING,
            )

            // 通过了：把**扇子里会显示的那个名字**当场打出来（识别优于回忆）。
            // 已经在清单里时改说那一句：否则用户会对着一个按不动的「添加」按钮猜原因
            is LinkTargets.Result.Ok -> kit.Paragraph(
                if (joined) {
                    fpString("link_import.state.joined")
                } else {
                    fpString("link_import.preview", LinkTargets.fallbackLabel(r.uri))
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
                text = fpString("link_import.add"),
                onClick = {
                    // 空清单分支由 FanItems 兜住（写空 = 删键），这里只需要给它一个有序清单
                    normalized?.let { FanItems.write(spec.key, FanItems.appended(items, it)) }
                },
                primary = true,
                enabled = normalized != null && !joined,
            )
            kit.Button(
                text = fpString("link_import.remove"),
                onClick = {
                    normalized?.let { key ->
                        // 撤销记的是**完整清单里的下标**，不是链接子列表里的下标
                        val index = items.indexOf(key)
                        FanItems.write(spec.key, FanItems.without(items, key))
                        if (index >= 0) removed = key to index
                    }
                },
                enabled = joined,
            )
        }
        kit.VSpace(6.dp)

        // ── 已在清单里的链接：可看、可删。顺序与图标在「扇形里的项」那一页调 ──
        kit.SectionHeader(fpString("link_import.section.current"))
        kit.Card {
            if (links.isEmpty()) {
                kit.Paragraph(fpString("link_import.empty"), FpEmphasis.MUTED)
                kit.Paragraph(fpString("link_import.empty.hint"), FpEmphasis.MUTED, FpTextStyle.CAPTION)
            } else {
                Column(Modifier.fillMaxWidth()) {
                    links.forEach { (_, key) ->
                        val uri = LinkTargets.uriOf(key)
                        val bad = LinkTargets.check(uri) != null
                        kit.ActionRow(
                            title = displayOf(key),
                            // 副标题是**清单项原样**：排障时只有它和配置里的那一行对得上
                            summary = key,
                            // 手改配置写进去的非法项照样列在这儿，但**明说它点了不会启动** ——
                            // 「填了却没反应」正是这个页面要消灭的那类静默失败
                            trailingText = if (bad) {
                                fpString("link_import.row.bad")
                            } else {
                                fpString("common.edit")
                            },
                            onClick = { input = uri },
                        )
                    }
                }
            }
        }

        kit.VSpace(16.dp)
    }
}

/**
 * 这一条链接在界面上的显示名。**与扇子、清单页同一个函数**（`LinkTargets.fallbackLabel`），
 * 所以三处不会出现两种名字。
 *
 * 用 `uriOf` 而不是 `parse(...)`：手改配置写进去的非法串也要有一个能显示、能删掉的名字
 *（`fallbackLabel` 对任何输入都返回非空串），绝不能因为「它不合法」就画一行空标题。
 */
private fun displayOf(key: String): String = LinkTargets.fallbackLabel(LinkTargets.uriOf(key))

/**
 * 校验失败的原因 → 文案。**每一种都单独一句**（见页面注释）。
 *
 * `when` 是穷尽的：`LinkTargets.Rejection` 将来多一种原因时这里编译不过，
 * 逼着补一句人话 —— 比运行期落到 `else` 显示一个键名好。
 */
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

/**
 * 一个示例链接：[uri] 是**真实存在**的 scheme（不是编出来的占位符）。
 *
 * 名字带前缀（`LinkPreset` / `LINK_PRESETS`）而不是就叫 `Preset`：Kotlin 的顶层 `private`
 * 只保证**文件内**可见，同名同类在同一个包里仍然是重名冲突（JVM 类名会撞），
 * 而「添加快捷方式」那一页里已经有一个 `Preset` 了。
 *
 * 只放两条：微信扫一扫（`weixin://scanqrcode` —— 本特性存在的理由就是它，
 * 见 `LinkTargets` 的类注释）与网页链接（`https://` 是给「把某个网页挂到扇子里」
 * 这个真实用法起个头）。**不列我们没验证过的 scheme** ——
 * 那等于把「猜」伪装成「已知可用」（与「添加快捷方式」页里 `PRESETS` 同一条纪律）。
 */
private class LinkPreset(val uri: String, val labelKey: String)

private val LINK_PRESETS = listOf(
    LinkPreset("weixin://scanqrcode", "link_import.preset.scan"),
    LinkPreset("https://", "link_import.preset.web"),
)
