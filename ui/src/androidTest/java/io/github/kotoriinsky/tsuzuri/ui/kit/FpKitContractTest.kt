package io.github.kotoriinsky.tsuzuri.ui.kit

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kotoriinsky.tsuzuri.ui.R
import io.github.kotoriinsky.tsuzuri.ui.model.Appearance
import io.github.kotoriinsky.tsuzuri.ui.model.UiStyle
import io.github.kotoriinsky.tsuzuri.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kit 契约测试：**同一组断言分别跑两套风格**。
 *
 * 组织方式（这是本文件最重要的部分）：断言全部写在基类里，两个具体子类各自把
 * 一种风格传进去 —— [M3KitContractTest] / [MiuixKitContractTest]。
 *
 * ```
 * abstract class FpKitContractTest(style)
 *   ├── M3KitContractTest    : FpKitContractTest(UiStyle.M3)
 *   └── MiuixKitContractTest : FpKitContractTest(UiStyle.MIUIX)
 * ```
 *
 * 为什么不用 Parameterized：Compose 的测试规则是 `@get:Rule`，由 runner 在**每个测试类**
 * 上准备一次；参数化 runner 与它组合起来很脆。而「抽象基类 + 两个空子类」是 JUnit4
 * 里最稳的双跑写法：子类没有构造参数，规则各建一份，互不干扰。
 *
 * 断言都走**语义**而不是像素：点得动、回调收到什么、屏幕上有没有这段文字。
 * 两套风格长得完全不一样（M3 是 ListItem+Switch，MiuiX 是 SwitchPreference），
 * 但语义必须一致 —— 这也是用户唯一能感知到的「行为一致」。
 *
 * ⚠️ 这些测试需要设备/模拟器（instrumentation）。没有连设备时它们不会被执行，
 * 也不该被假装执行过。
 */
@RunWith(AndroidJUnit4::class)
abstract class FpKitContractTest(
    private val style: UiStyle,
    /** 仅用于让失败信息读起来知道是哪套风格挂了。 */
    private val kitName: String,
) {

    @get:Rule
    val rule = createComposeRule()

    /** 测试里要用的文案直接从资源取，避免在测试里手抄一份中文，抄错了会误报。 */
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 用**真实主题入口**渲染，而不是直接把 Kit 塞进 CompositionLocal。
     * 理由是这才会连带验出「风格 → Kit」的接线是否接反 —— 那正是最隐蔽的一种错。
     */
    private fun setKitContent(
        haptic: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            AppTheme(Appearance(style = style), hapticFeedback = haptic) {
                Column { content() }
            }
        }
    }

    /** 供断言信息里区分是哪套风格挂了（两套风格跑的是同一份代码，报错必须能分辨）。 */
    private fun tag(message: String): String = "[$kitName] $message"

    // ─────────────────────────────────────────────────────────────
    // 接线
    // ─────────────────────────────────────────────────────────────

    @Test
    fun theme_providesTheExpectedKit() {
        var seen: FpKit? = null
        setKitContent { seen = LocalFpKit.current }
        rule.waitForIdle()
        val expected = if (style == UiStyle.M3) M3Kit else MiuixKit
        // 接反了的话（例如选了 M3 却给了 MiuixKit）这里直接失败
        assertTrue(tag("主题给出的 Kit 不是 $kitName 对应的实现"), seen === expected)
    }

    // ─────────────────────────────────────────────────────────────
    // 开关：点整行要能切换，且回调收到的新值要对
    // ─────────────────────────────────────────────────────────────

    @Test
    fun switchRow_clickingTheRow_flipsValue() {
        val title = "switch-row-${style.name}"
        val checks = mutableStateOf<Boolean?>(null)
        var checked by mutableStateOf(false)

        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.SwitchRow(
                    title = title,
                    summary = null,
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        checks.value = it
                    },
                )
            }
        }

        // 点「标题所在的那一行」而不是点开关本体：两套风格都必须整行可点。
        // 这是契约的一部分 —— M3 的 ListItem 默认只有尾部 Switch 可点，两套风格
        // 在这一点上不一致的话，用户会以为「有时候点不动」。
        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle {
            assertEquals("点击后应回调一次 true", true, checks.value)
            assertTrue("可见状态应跟着变", checked)
        }

        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals("再点一次应回到 false", false, checks.value) }
    }

    // ─────────────────────────────────────────────────────────────
    // 可点击行：回调要触发，右侧值要显示
    // ─────────────────────────────────────────────────────────────

    @Test
    fun actionRow_click_invokesCallbackOnce() {
        val title = "action-row-${style.name}"
        val trailing = "trailing-${style.name}"
        var clicks by mutableStateOf(0)

        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.ActionRow(
                    title = title,
                    summary = null,
                    trailingText = trailing,
                    onClick = { clicks += 1 },
                )
            }
        }

        rule.onNodeWithText(trailing).assertExists()
        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals("ActionRow 的回调应被调用一次", 1, clicks) }
    }

    @Test
    fun actionRow_withoutCallback_isNotClickable() {
        val title = "action-row-disabled-${style.name}"
        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.ActionRow(title = title, summary = null, trailingText = null, onClick = null)
            }
        }
        rule.onNodeWithText(title).assertExists()
        // 没有回调的行不该变成可点的 —— 点了没反应比不可点更糟
        rule.onNode(hasText(title) and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun actionRow_rendersLeadingSlot() {
        val title = "action-leading-${style.name}"
        val slot = "action-leading-slot-${style.name}"
        val trailing = "action-leading-trailing-${style.name}"
        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.ActionRow(
                    title = title,
                    summary = null,
                    trailingText = trailing,
                    onClick = {},
                    leading = { kit.Body(slot) },
                )
            }
        }
        // 单项图标那一页整页靠这个槽画「这一项现在长什么样」：槽被忽略的话那一页会
        // 「文字全对、一个图标都没有」；而且左侧插槽与右侧值必须能**同时**画出来
        //（CheckRow 的左槽是拿勾选框换的，动作行不能有那个取舍）
        rule.onNodeWithText(slot).assertExists()
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText(trailing).assertExists()
    }

    // ─────────────────────────────────────────────────────────────
    // 列表项数量：卡片里有几行就必须只画几行
    // ─────────────────────────────────────────────────────────────

    @Test
    fun card_withThreeRows_rendersExactlyThreeItems() {
        val prefix = "row-${style.name}"
        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                for (i in 1..3) {
                    if (i > 1) kit.Divider()
                    kit.ActionRow(
                        title = "$prefix $i",
                        summary = null,
                        trailingText = null,
                        onClick = {},
                    )
                }
            }
        }

        for (i in 1..3) {
            rule.onNodeWithText("$prefix $i").assertExists()
        }
        // 多画一行和少画一行都要在这里失败
        rule.onNodeWithText("$prefix 4").assertDoesNotExist()
    }

    // ─────────────────────────────────────────────────────────────
    // 多选行（应用多选清单那种界面用它）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun checkRow_clickingTheRow_flipsValue() {
        val title = "check-row-${style.name}"
        val checks = mutableStateOf<Boolean?>(null)
        var checked by mutableStateOf(false)

        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.CheckRow(
                    title = title,
                    summary = null,
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        checks.value = it
                    },
                )
            }
        }

        // 和 SwitchRow 一样点「整行」：MiuiX 的 CheckboxPreference 天然整行可点，
        // M3 那边必须靠 ListItem 上的 toggleable 补上 —— 少了它，用户在澎湃下点得动、
        // 在 Material 下点不动（同一件事两套风格行为不一致，是本项目最不该有的差异）
        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle {
            assertEquals("点击后应回调一次 true", true, checks.value)
            assertTrue("可见状态应跟着变", checked)
        }

        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals("再点一次应回到 false", false, checks.value) }
    }

    @Test
    fun checkRow_rendersLeadingSlot() {
        val title = "check-leading-${style.name}"
        val slot = "leading-${style.name}"
        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.CheckRow(
                    title = title,
                    summary = null,
                    checked = false,
                    onCheckedChange = {},
                    leading = { kit.Body(slot) },
                )
            }
        }
        // 插槽内容必须真的被画出来：应用多选那一页整页都靠这个槽画图标，
        // 槽被忽略的话那一页会「文字全对、一个图标都没有」——很难判断是没加载完还是坏了
        rule.onNodeWithText(slot).assertExists()
        rule.onNodeWithText(title).assertExists()
    }

    @Test
    fun disabledCheckRow_doesNotReportClicks() {
        val title = "check-disabled-${style.name}"
        var calls by mutableStateOf(0)
        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.CheckRow(
                    title = title,
                    summary = null,
                    checked = false,
                    onCheckedChange = { calls += 1 },
                    enabled = false,
                )
            }
        }
        rule.onNodeWithText(title).assertExists()
        val clickable = rule.onAllNodes(hasText(title) and hasClickAction()).fetchSemanticsNodes()
        assertTrue("禁用的多选行不应暴露点击动作", clickable.isEmpty())
        rule.runOnIdle { assertEquals("禁用的多选行不应回调", 0, calls) }
    }

    // ─────────────────────────────────────────────────────────────
    // 文本类组件
    // ─────────────────────────────────────────────────────────────

    @Test
    fun bodyAndBadge_renderTheirText() {
        val body = "body-${style.name}"
        val badge = "badge-${style.name}"
        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.Body(body)
                kit.Badge(badge, FpEmphasis.WARNING)
            }
        }
        rule.onNodeWithText(body).assertExists()
        rule.onNodeWithText(badge).assertExists()
    }

    @Test
    fun banner_showsText_andRunsAction() {
        val text = "banner-${style.name}"
        val action = "do-${style.name}"
        var clicked by mutableStateOf(false)
        setKitContent {
            val kit = LocalFpKit.current
            kit.Banner(
                text = text,
                emphasis = FpEmphasis.WARNING,
                actionLabel = action,
                onAction = { clicked = true },
            )
        }
        rule.onNodeWithText(text).assertExists()
        rule.onNodeWithText(action).performClick()
        rule.runOnIdle { assertTrue("Banner 的动作按钮应触发回调", clicked) }
    }

    @Test
    fun infoRow_showsBothTitleAndValue() {
        val title = "info-title-${style.name}"
        val value = "info-value-${style.name}"
        setKitContent {
            LocalFpKit.current.Card { LocalFpKit.current.InfoRow(title, value) }
        }
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText(value).assertExists()
    }

    // ─────────────────────────────────────────────────────────────
    // 加载态 vs 空态：两者必须是**两段不同的文字**
    // ─────────────────────────────────────────────────────────────

    @Test
    fun loadingAndEmpty_areDistinctStatements() {
        val loading = context.getString(R.string.common_loading)
        val empty = context.getString(R.string.empty_no_modules)

        setKitContent {
            Column {
                FpLoadingState()
                FpEmptyState(titleKey = "empty.no.modules")
            }
        }

        rule.onNodeWithText(loading).assertExists()
        rule.onNodeWithText(empty).assertExists()
        // 这条断言本身才是重点：如果哪天有人把加载态也写成「没有内容」，
        // 用户就再也分不清「还没读到」和「读到了是空的」
        assertNotEquals("加载态与空态不能是同一句话", loading, empty)
    }

    @Test
    fun errorState_showsHintAndRetry() {
        val retry = "retry-${style.name}"
        val title = context.getString(R.string.state_framework_error)
        var retried by mutableStateOf(false)

        setKitContent {
            FpErrorState(
                titleKey = "state.framework.error",
                hintKey = "state.framework.error.hint",
                detail = "TestError",
                actionLabel = retry,
                onAction = { retried = true },
            )
        }

        rule.onNodeWithText(title).assertExists()
        // detail 是给排障用的技术原因，必须真的画出来，否则「出错」就不可查
        rule.onNodeWithText("TestError").assertExists()
        rule.onNodeWithText(retry).performClick()
        rule.runOnIdle { assertTrue("错误态必须能用同一个按钮重试", retried) }
    }

    @Test
    fun emptyState_offersAnActionWhenProvided() {
        val action = "fix-${style.name}"
        var acted by mutableStateOf(false)
        setKitContent {
            FpEmptyState(
                titleKey = "empty.modules.filtered",
                hintKey = "empty.modules.filtered.hint",
                actionLabel = action,
                onAction = { acted = true },
            )
        }
        rule.onNodeWithText(action).performClick()
        rule.runOnIdle { assertTrue("空态给的出口应可点", acted) }
    }

    // ─────────────────────────────────────────────────────────────
    // 点击振动开关
    // ─────────────────────────────────────────────────────────────

    @Test
    fun clickFeedbackLocal_isOffWhenThemeSaysOff() {
        val title = "haptic-off-${style.name}"
        var seen: Boolean? = null
        var clicks by mutableStateOf(0)

        setKitContent(haptic = false) {
            val kit = LocalFpKit.current
            seen = LocalFpClickFeedback.current
            kit.Card {
                kit.ActionRow(title = title, summary = null, trailingText = null, onClick = { clicks += 1 })
            }
        }

        rule.waitForIdle()
        assertEquals("振动关闭时应为 false", false, seen)
        // 关键：关掉振动只是不发振动，点击本身必须照常工作
        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals("关闭振动不影响回调", 1, clicks) }
    }

    @Test
    fun clickFeedbackLocal_isOnWhenThemeSaysOn() {
        val title = "haptic-on-${style.name}"
        var seen: Boolean? = null
        var clicks by mutableStateOf(0)

        // 开着振动时点击不能抛异常（振动在测试环境里是空操作，但要保证不依赖权限）
        setKitContent(haptic = true) {
            val kit = LocalFpKit.current
            seen = LocalFpClickFeedback.current
            kit.Card {
                kit.ActionRow(title = title, summary = null, trailingText = null, onClick = { clicks += 1 })
            }
        }

        rule.waitForIdle()
        assertEquals("振动打开时应为 true", true, seen)
        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun disabledSwitchRow_doesNotReportClicks() {
        val title = "switch-disabled-${style.name}"
        var calls by mutableStateOf(0)
        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.SwitchRow(
                    title = title,
                    summary = null,
                    checked = false,
                    onCheckedChange = { calls += 1 },
                    enabled = false,
                )
            }
        }
        rule.onNodeWithText(title).assertExists()
        // 规划中 / 已搁置的特性用的是这一态：灰显且怎么点都不该有反应
        val clickableRows = rule.onAllNodes(hasText(title) and hasClickAction()).fetchSemanticsNodes()
        assertTrue("禁用行不应暴露点击动作", clickableRows.isEmpty())
        rule.runOnIdle { assertEquals("禁用行不应回调", 0, calls) }
    }

    // ─────────────────────────────────────────────────────────────
    // 2026-10-17 新增组件：FeatureRow / PageHeader / SectionHeader / HeroCard
    //
    // 这几个组件是本轮界面重写的骨架，也是**两套 Kit 最容易画歪**的地方：
    // 它们各自由两套实现分别拼装（Miuix 的 Switch/Text、M3 的 Switch/Text），
    // 没有这组断言时，「澎湃下点行体进详情、M3 下点行体只开关」这种漂移不会被发现。
    // ─────────────────────────────────────────────────────────────

    @Test
    fun featureRow_rowAndSwitchAreDifferentActions() {
        val title = "feature-row-${style.name}"
        val chip = "chip-${style.name}"
        var clicks by mutableStateOf(0)
        var flips by mutableStateOf(0)
        var checked by mutableStateOf(false)

        setKitContent {
            val kit = LocalFpKit.current
            kit.Card {
                kit.FeatureRow(
                    title = title,
                    summary = "summary-${style.name}",
                    icon = FpIcon(Icons.Filled.Star, androidx.compose.ui.graphics.Color.Blue),
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        flips += 1
                    },
                    onClick = { clicks += 1 },
                    chip = FpChip(chip, FpEmphasis.WARNING),
                )
            }
        }

        // MiuiX 把状态写进摘要行（「状态 · 摘要」），M3 是独立徽标：按子串找两者都成立
        rule.onNodeWithText(chip, substring = true).assertExists()
        // 点行体 = 进详情（不开关）
        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle {
            assertEquals("点行体应触发一次进详情", 1, clicks)
            assertEquals("点行体不应拨动开关", false, checked)
        }
        // 点开关 = 只开关（不导航）
        rule.onNode(isToggleable()).performClick()
        rule.runOnIdle {
            assertEquals("点开关应回调一次", 1, flips)
            assertEquals("点开关后应变为开", true, checked)
            assertEquals("点开关不应触发导航", 1, clicks)
        }
    }

    @Test
    fun pageHeader_rendersTitleSubtitleAndAction() {
        val title = "page-title-${style.name}"
        val subtitle = "page-subtitle-${style.name}"
        val action = "page-action-${style.name}"
        var acted by mutableStateOf(false)

        setKitContent {
            val kit = LocalFpKit.current
            kit.PageHeader(title = title, subtitle = subtitle, actions = {
                kit.Button(action, { acted = true })
            })
        }

        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText(subtitle).assertExists()
        rule.onNodeWithText(action).performClick()
        rule.runOnIdle { assertTrue("页头动作应可点", acted) }
    }

    @Test
    fun sectionHeader_rendersCountAndBlockAction() {
        val title = "section-title-${style.name}"
        val count = "3 items-${style.name}"
        val action = "section-action-${style.name}"
        var acted by mutableStateOf(false)

        setKitContent {
            LocalFpKit.current.SectionHeader(
                title = title,
                count = count,
                actionLabel = action,
                onAction = { acted = true },
            )
        }

        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText(count).assertExists()
        rule.onNodeWithText(action).performClick()
        rule.runOnIdle { assertTrue("区块级动作应可点", acted) }
    }

    @Test
    fun heroCard_rendersConclusionChipsAndSinglePrimaryAction() {
        val conclusion = "全部就绪-${style.name}"
        val detail = "LSPosed 2.2.0-${style.name}"
        val chipOk = "框架已连接-${style.name}"
        val chipWait = "3 个待重载-${style.name}"
        val primary = "重载目标进程-${style.name}"
        var reloaded by mutableStateOf(false)

        setKitContent {
            LocalFpKit.current.HeroCard(
                title = conclusion,
                detail = detail,
                emphasis = FpEmphasis.POSITIVE,
                icon = Icons.Filled.CheckCircle,
                chips = listOf(
                    FpChip(chipOk, FpEmphasis.POSITIVE),
                    FpChip(chipWait, FpEmphasis.WARNING),
                ),
                primaryLabel = primary,
                onPrimary = { reloaded = true },
            )
        }

        rule.onNodeWithText(conclusion).assertExists()
        rule.onNodeWithText(detail).assertExists()
        rule.onNodeWithText(chipOk).assertExists()
        rule.onNodeWithText(chipWait).assertExists()
        rule.onNodeWithText(primary).performClick()
        rule.runOnIdle { assertTrue("主操作应可点", reloaded) }
    }
}

/** Material 3 风格下的全部契约断言。 */
class M3KitContractTest : FpKitContractTest(UiStyle.M3, "M3Kit")

/** 澎湃（MiuiX）风格下的**同一组**契约断言。 */
class MiuixKitContractTest : FpKitContractTest(UiStyle.MIUIX, "MiuixKit")
