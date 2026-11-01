package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ShortcutEntries] 的保留规则表与拆分边界。
 *
 * 这一层错法的共同点是**不报错，只是项不见了**：用户在设置里手工填了一条快捷方式，
 * 扇形里却永远没有它，而日志只给出一句「丢弃 N 个失效快捷方式」——看不出是「真的失效」
 * 还是「我们没见过它」。
 *
 * 所以整张表逐格钉住，并且额外钉一条**不变量**：新规则保留的项只可能比旧实现多，
 * 不可能少。旧实现（`acceptsShortcut` 加「宿主不在可启动清单时排到最后、优先保住」那一支）
 * 是这个特性的既有行为，倒退回去的表现就是用户的项又一次凭空消失 ——
 * 而这种错误在日志里只表现为一个变小的数字，肉眼看不出来。
 */
class ShortcutEntriesTest {

    // ── 拆分：清单项 → (包名, 快捷方式 id?) ───────────────────────────────

    @Test
    fun plainPackageIsAnApp() {
        // 存量配置全是这种（纯包名）。分类错 = 所有老用户的清单一次性失效
        val item = ShortcutEntries.parse("com.tencent.mm")
        assertEquals(ShortcutEntries.Kind.APP, item.kind)
        assertEquals("com.tencent.mm", item.pkg)
        assertEquals(null, item.id)
    }

    @Test
    fun firstSlashSeparatesPackageFromShortcutId() {
        // 按**第一个** `/` 切。按最后一个切的话 `com.tencent.mm/a/b` 会拆出包名 `com.tencent.mm/a`，
        // 于是它被当成「这个应用没装」丢掉 —— 而它其实是一条合法的快捷方式
        val one = ShortcutEntries.parse("com.tencent.mm/launch_type_scan_qrcode")
        assertEquals(ShortcutEntries.Kind.SHORTCUT, one.kind)
        assertEquals("com.tencent.mm", one.pkg)
        assertEquals("launch_type_scan_qrcode", one.id)

        val many = ShortcutEntries.parse("com.tencent.mm/a/b")
        assertEquals(ShortcutEntries.Kind.SHORTCUT, many.kind)
        assertEquals("com.tencent.mm", many.pkg)
        assertEquals("a/b", many.id)
    }

    @Test
    fun slashAtEitherEndAndEmptyStringAreInvalid() {
        // `/x`（没有包名）和 `x/`（没有 id）都点不动，保留它们只会在扇子里多画一个
        // 永远失败的图标；空串则是设置界面写坏之后的残留
        for (raw in listOf("/x", "com.tencent.mm/", "")) {
            assertEquals(ShortcutEntries.Kind.INVALID, ShortcutEntries.parse(raw).kind, raw)
            assertEquals(null, ShortcutEntries.parse(raw).id, raw)
        }
        // 但包名前缀要能取出来：`com.tencent.mm/` 这一条虽然非法，「用户提过这个包」
        // 这个信息在补查目录时仍然有用（见 packageOf 的测试）
        assertEquals("com.tencent.mm", ShortcutEntries.parse("com.tencent.mm/").pkg)
        assertEquals("", ShortcutEntries.parse("/x").pkg)
        assertEquals("", ShortcutEntries.parse("").pkg)
    }

    @Test
    fun parseKeepsTheRawStringForLogging() {
        // 日志里要能打出「丢掉的到底是哪一条」，而那必须**原样**是用户当初写的那串：
        // 拆坏的项（`/x`、`com.tencent.mm/`）正是最需要原样出现在日志里的那一类，
        // 打我们拆出来的碎片等于没打
        assertEquals("/x", ShortcutEntries.parse("/x").raw)
        assertEquals("com.tencent.mm/", ShortcutEntries.parse("com.tencent.mm/").raw)
        assertEquals("com.tencent.mm/x", ShortcutEntries.parse("com.tencent.mm/x").raw)
    }

    @Test
    fun packageOfIgnoresTheShortcutId() {
        // 它决定「要不要为这个包补一次快捷方式查询」。取错（例如把整串拿去查）→
        // 那个包发布的快捷方式压根不在目录里 → 用户勾过的项被判成「目录里没有」
        assertEquals("com.tencent.mm", ShortcutEntries.packageOf("com.tencent.mm"))
        assertEquals("com.tencent.mm", ShortcutEntries.packageOf("com.tencent.mm/scan"))
        assertEquals("com.tencent.mm", ShortcutEntries.packageOf("com.tencent.mm/"))
        assertEquals("", ShortcutEntries.packageOf("/scan"))
        assertEquals("", ShortcutEntries.packageOf(""))
    }

    // ── 保留规则表，逐格 ─────────────────────────────────────────────────
    //
    // | 宿主可启动 | 目录可用 | 目录里有 | 目录里启用 | 结果        | 与旧实现 |
    // | 否         | 否       | —        | —          | 保留(未知)  | 一致(留) |
    // | 否         | 是       | 是       | 是         | 保留        | 一致(留) |
    // | 否         | 是       | 是       | 否         | 丢弃        | 一致(丢) |
    // | 否         | 是       | 否       | —          | 丢弃        | 一致(丢) |
    // | 是         | 否       | —        | —          | 保留(未知)  | 一致(留) |
    // | 是         | 是       | 否       | —          | 保留(自定义)| 多留     |
    // | 是         | 是       | 是       | 是         | 保留        | 一致(留) |
    // | 是         | 是       | 是       | 否         | 丢弃        | 一致(丢) |

    /** 一格：四个输入 + 新规则期望的结论 + 旧实现在同一格的结果（回归防线）。 */
    private class Cell(
        val hostLaunchable: Boolean,
        val catalogOk: Boolean,
        val inCatalog: Boolean,
        val catalogEnabled: Boolean?,
        val expected: ShortcutEntries.Verdict,
        /** 旧实现（`acceptsShortcut` + 「宿主不在可启动清单时排到最后、优先保住」）。 */
        val oldKept: Boolean,
    ) {
        override fun toString(): String =
            "hostLaunchable=$hostLaunchable catalogOk=$catalogOk " +
                "inCatalog=$inCatalog catalogEnabled=$catalogEnabled"
    }

    private val table = listOf(
        // ── 宿主不在可启动清单里：与旧实现**逐字一致**（活目录才是判死的唯一依据）──
        // 目录不可用 → 什么都不知道，绝不丢。旧代码就是留的，倒退回去就是 bug
        Cell(false, false, false, null, ShortcutEntries.Verdict.KEEP_UNKNOWN, oldKept = true),
        // 活目录里还有它且没禁用 → 它活着（宿主只是没有启动入口），留着
        Cell(false, true, true, true, ShortcutEntries.Verdict.KEEP_IN_CATALOG, oldKept = true),
        // 活目录里明确禁用了 → 丢
        Cell(false, true, true, false, ShortcutEntries.Verdict.DROP, oldKept = false),
        // 活目录里查不到 → 认定包没了 / 快捷方式被撤回了，丢
        Cell(false, true, false, null, ShortcutEntries.Verdict.DROP, oldKept = false),
        // ── 宿主可启动：只有第 6 格比旧实现多放行（本次的核心改动）──
        Cell(true, false, false, null, ShortcutEntries.Verdict.KEEP_UNKNOWN, oldKept = true),
        // 用户手工填的 id、或系统还没枚举到的：旧代码在这里丢掉它，
        // 表现就是「设置里明明填了，扇子里永远没有」
        Cell(true, true, false, null, ShortcutEntries.Verdict.KEEP_CUSTOM, oldKept = false),
        Cell(true, true, true, true, ShortcutEntries.Verdict.KEEP_IN_CATALOG, oldKept = true),
        Cell(true, true, true, false, ShortcutEntries.Verdict.DROP, oldKept = false),
    )

    @Test
    fun everyCellOfTheTableFollowsTheNewRule() {
        // 逐格断言（8 格）。逐格而不是只测几条边界：这四列全是布尔量，
        // 组合起来只有 8 种，而「哪一格判错」决定了是「项消失」还是「死图标留下」
        for (cell in table) {
            val verdict = ShortcutEntries.verdictOf(
                cell.hostLaunchable, cell.catalogOk, cell.inCatalog, cell.catalogEnabled,
            )
            assertEquals(cell.expected, verdict, "$cell")
        }
    }

    @Test
    fun everyCellKeepsAtLeastWhatTheOldImplementationKept() {
        // 回归防线：旧实现在每一格留了就**必须**还留着。少留一格 = 用户的项凭空消失，
        // 而那种退化在日志里只是数字变小，看不出是哪一格变了
        for (cell in table) {
            val verdict = ShortcutEntries.verdictOf(
                cell.hostLaunchable, cell.catalogOk, cell.inCatalog, cell.catalogEnabled,
            )
            assertTrue(!cell.oldKept || verdict.kept, "旧实现留着、新规则却丢了：$cell")
        }
        // 多留的那一格只能是「宿主可启动 × 目录可用 × 目录里没有」（第 6 格）——
        // 别的格多留就意味着某条死不掉的东西被留下来了
        val more = table.filter { cell ->
            val kept = ShortcutEntries.verdictOf(
                cell.hostLaunchable, cell.catalogOk, cell.inCatalog, cell.catalogEnabled,
            ).kept
            kept && !cell.oldKept
        }
        assertEquals(1, more.size, "比旧实现多放行的格子数不对：$more")
        assertEquals(ShortcutEntries.Verdict.KEEP_CUSTOM, ShortcutEntries.verdictOf(
            more.single().hostLaunchable,
            more.single().catalogOk,
            more.single().inCatalog,
            more.single().catalogEnabled,
        ))
    }

    @Test
    fun unlaunchableHostWithUnavailableCatalogIsKept() {
        // 单独钉住这一格（它是最容易被「优化」掉的一格）：宿主没有启动入口 + 目录不可用
        // → 我们只知道「它可能还在」。旧实现的选择是留下（宁可点不动，也不让用户勾过的
        // 项凭空消失），**任何把这一格改成丢弃的改动都是行为倒退**
        assertEquals(
            ShortcutEntries.Verdict.KEEP_UNKNOWN,
            ShortcutEntries.verdictOf(
                hostLaunchable = false,
                catalogOk = false,
                inCatalog = false,
                catalogEnabled = null,
            ),
        )
        // 目录不可用时 inCatalog/catalogEnabled 是**没有意义的值**（目录是空的，
        // 查出来必然是「没有这条」）——不能因为看到「没有」就顺手判失效
        assertEquals(
            ShortcutEntries.Verdict.KEEP_UNKNOWN,
            ShortcutEntries.verdictOf(
                hostLaunchable = false,
                catalogOk = false,
                inCatalog = true,
                catalogEnabled = false,
            ),
        )
    }

    @Test
    fun unlaunchableHostIsKeptWhenTheLiveCatalogStillListsIt() {
        // 「没有启动入口」≠「没装」：loadLabel 抛异常/返回空串的包、桌面自己、
        // 以及只发布快捷方式而没有 launcher 入口的应用都落在这里。
        // 活目录里还有它 → 它就是活的，和宿主有没有启动入口无关
        assertEquals(
            ShortcutEntries.Verdict.KEEP_IN_CATALOG,
            ShortcutEntries.verdictOf(
                hostLaunchable = false,
                catalogOk = true,
                inCatalog = true,
                catalogEnabled = true,
            ),
        )
        // 启用状态拿不到（null）也按活的处理：丢一条活的比留一条死的更糟
        assertEquals(
            ShortcutEntries.Verdict.KEEP_IN_CATALOG,
            ShortcutEntries.verdictOf(
                hostLaunchable = false,
                catalogOk = true,
                inCatalog = true,
                catalogEnabled = null,
            ),
        )
    }

    @Test
    fun unlaunchableHostIsDroppedOnlyWhenTheCatalogSaysSo() {
        // 只有**活目录**能判死：明确禁用，或里面压根查不到它（包被卸载 /
        // 快捷方式被应用撤回）。这是这一支唯一允许丢项的两个情形
        assertEquals(
            ShortcutEntries.Verdict.DROP,
            ShortcutEntries.verdictOf(
                hostLaunchable = false, catalogOk = true, inCatalog = true, catalogEnabled = false,
            ),
        )
        assertEquals(
            ShortcutEntries.Verdict.DROP,
            ShortcutEntries.verdictOf(
                hostLaunchable = false, catalogOk = true, inCatalog = false, catalogEnabled = null,
            ),
        )
    }

    @Test
    fun keepsWhenTheCatalogIsUnavailable() {
        // 宿主可启动、但目录枚举失败（权限被收回 / LauncherApps 抛异常）：我们
        // **不知道**它还在不在。此时丢掉 = 用户勾过的项凭空消失，而且不会自己回来
        // （配置已经被改写了）——比「暂时点不动」严重得多
        assertEquals(
            ShortcutEntries.Verdict.KEEP_UNKNOWN,
            ShortcutEntries.verdictOf(
                hostLaunchable = true,
                catalogOk = false,
                inCatalog = false,
                catalogEnabled = null,
            ),
        )
        assertEquals(
            ShortcutEntries.Verdict.KEEP_UNKNOWN,
            ShortcutEntries.verdictOf(
                hostLaunchable = true,
                catalogOk = false,
                inCatalog = true,
                catalogEnabled = false,
            ),
        )
    }

    @Test
    fun keepsAndMarksCustomWhenTheCatalogDoesNotListIt() {
        // **本次修的就是这一格**。用户手工填的 id、或系统还没枚举到的动态快捷方式。
        // 旧代码在这里丢掉它，表现是「设置里明明填了/勾了，扇子里永远没有」，
        // 日志只有一句「丢弃失效快捷方式」——这正是「静默丢弃用户自定义项」那个 bug
        assertEquals(
            ShortcutEntries.Verdict.KEEP_CUSTOM,
            ShortcutEntries.verdictOf(
                hostLaunchable = true,
                catalogOk = true,
                inCatalog = false,
                catalogEnabled = null,
            ),
        )
    }

    @Test
    fun keepsEnabledEntryFromTheCatalog() {
        // 形态与旧代码一致（旧代码只在「目录里有且启用」时才保留）——
        // 这一格退化的话，所有正常导入的快捷方式都会消失
        assertEquals(
            ShortcutEntries.Verdict.KEEP_IN_CATALOG,
            ShortcutEntries.verdictOf(
                hostLaunchable = true,
                catalogOk = true,
                inCatalog = true,
                catalogEnabled = true,
            ),
        )
    }

    @Test
    fun dropsOnlyWhenTheCatalogExplicitlySaysDisabled() {
        // 与「目录里没有」那一格是一对：目录里**有**且明确禁用 → 这才是真的失效。
        // 若把「有但禁用」也保留，用户主动禁用的入口会一直挂在扇子里，
        // 而且再也没有任何输入能区分「禁用」和「我们自己没见过」
        assertEquals(
            ShortcutEntries.Verdict.DROP,
            ShortcutEntries.verdictOf(
                hostLaunchable = true,
                catalogOk = true,
                inCatalog = true,
                catalogEnabled = false,
            ),
        )
    }

    @Test
    fun unknownEnabledStateIsKept() {
        // 目录里有这条、但拿不到它的启用状态（将来换查法 / 字段读失败）时**保留**：
        // 丢一条活的比留一条死的更糟，与「目录不可用」那一格同一条理由。
        // 宿主两态都要测：这个取值在旧实现里根本不存在（`ShortcutInfo.isEnabled` 是原始类型），
        // 所以它是新规则多出来的 fail-open，不能只在一条分支上生效
        for (host in listOf(true, false)) {
            assertEquals(
                ShortcutEntries.Verdict.KEEP_IN_CATALOG,
                ShortcutEntries.verdictOf(
                    hostLaunchable = host,
                    catalogOk = true,
                    inCatalog = true,
                    catalogEnabled = null,
                ),
                "hostLaunchable=$host",
            )
        }
    }

    @Test
    fun newRuleNeverKeepsFewerItemsThanTheOldOne() {
        // 全枚举（2×2×2×3 共 24 组）把不变量钉死：**新规则保留的 ⊇ 旧实现保留的**。
        // 旧实现 = `acceptsShortcut`（目录不可用一律留；否则要「目录里有且启用」）
        // 加「宿主不在可启动清单时排到最后、优先保住」那一支
        for (host in listOf(true, false)) {
            for (catalogOk in listOf(true, false)) {
                for (inCatalog in listOf(true, false)) {
                    for (enabled in listOf(true, false, null)) {
                        val where = "host=$host catalogOk=$catalogOk inCatalog=$inCatalog enabled=$enabled"
                        val verdict = ShortcutEntries.verdictOf(host, catalogOk, inCatalog, enabled)
                        val oldKept = if (!catalogOk) true else (inCatalog && enabled == true)
                        assertTrue(!oldKept || verdict.kept, "旧实现留着、新规则却丢了：$where")
                        // 多留只允许出现在两处：「宿主可启动但目录里没有」（本次放行口），
                        // 与「启用状态未知」（旧实现里这个取值不存在的新 fail-open）
                        if (verdict.kept && !oldKept) {
                            assertTrue(
                                catalogOk && (!inCatalog || enabled == null),
                                "多留了一格不该留的：$where",
                            )
                        }
                        // 四种结论的适用范围也钉一下：某个组合不能悄悄退化成表上没有的判定
                        // （那种错在日志里表现为「数字对不上」，看不出是判定错了）
                        if (verdict == ShortcutEntries.Verdict.KEEP_CUSTOM) {
                            assertTrue(host && catalogOk && !inCatalog, "自定义只能来自「宿主可启动且目录可用但没这条」：$where")
                        }
                        if (verdict == ShortcutEntries.Verdict.KEEP_UNKNOWN) {
                            assertFalse(catalogOk, "「未知」只能来自目录不可用：$where")
                        }
                    }
                }
            }
        }
    }

    // ── 第三类项：链接（`link:<uri>`）─────────────────────────────────────
    //
    // 这一段全部是**追加**的：上面每一格的语义一个字都没动（存量配置里有应用与快捷方式，
    // 它们的分类结果必须是**逐字不变**的 —— 那正是这个文件开头那条「不需要迁移」的承诺）。

    @Test
    fun linkItemIsItsOwnKind() {
        val item = ShortcutEntries.parse("link:weixin://scanqrcode")
        assertEquals(ShortcutEntries.Kind.LINK, item.kind)
        // 没有宿主包：`catalogSupplementPackages` 靠这个空串跳过它（不为它花一次 binder）
        assertEquals("", item.pkg)
        // 不是快捷方式：id 必须是 null（启动侧就是靠它区分走 startShortcut 还是走链接）
        assertEquals(null, item.id)
        // URI 存进独立字段（去掉前缀），而不是复用 id —— 复用会让启动侧误把它当快捷方式 id
        assertEquals("weixin://scanqrcode", item.linkUri)
        // 日志要原样打出用户当初写的那一串
        assertEquals("link:weixin://scanqrcode", item.raw)
    }

    @Test
    fun linkPrefixIsCheckedBeforeTheSlashSplit() {
        // **这条是最容易写错的一处**：`link:weixin://scanqrcode` 里含 `/`，
        // 先按 `/` 拆会得到包名 `link:weixin:`（一个永远查不到、永远点不动的项），
        // 而那一格的日志只会说「不可启动」——看不出是「用户填的链接被拆坏了」
        val item = ShortcutEntries.parse("link:weixin://scanqrcode")
        assertEquals(ShortcutEntries.Kind.LINK, item.kind)
        assertEquals(null, item.id)
        assertTrue(item.pkg != "link:weixin:", "链接项被按 / 拆了：pkg=${item.pkg}")
        // 反例：快捷方式 id 里出现 `link:` **不算**链接项（前缀只认整串开头那一个）
        val sc = ShortcutEntries.parse("com.foo/link:bar")
        assertEquals(ShortcutEntries.Kind.SHORTCUT, sc.kind)
        assertEquals("com.foo", sc.pkg)
        assertEquals("link:bar", sc.id)
        assertEquals(null, sc.linkUri)
    }

    @Test
    fun packageOfIsEmptyForLinkItems() {
        // 「用户提过这个包」这个信息在补查快捷方式目录时有用，而链接项**没有包**：
        // 取错（例如把 scheme 当包名）会为每个链接多发一次无意义的 binder 调用
        assertEquals("", ShortcutEntries.packageOf("link:weixin://scanqrcode"))
        assertEquals("", ShortcutEntries.packageOf("link:https://example.com/a"))
    }

    @Test
    fun linkDetectionIsExactlyLinkTargetsIsLink() {
        // 分类与校验是两件事：这里只钉住「分类判据唯一」——
        // 两处各判一次的话，哪天只改了一边，表现是「有的链接项被当应用丢掉」，
        // 而那种退化在日志里只表现为一个变小的数字
        for (raw in listOf(
            "link:weixin://scanqrcode",
            "LINK:weixin://scanqrcode",
            "link:",
            "link",
            "com.tencent.mm",
            "com.tencent.mm/launch_type_scan_qrcode",
            "com.foo/link:bar",
            "/x",
            "",
            "com.tencent.mm/",
        )) {
            val isLink = ShortcutEntries.parse(raw).kind == ShortcutEntries.Kind.LINK
            assertEquals(LinkTargets.isLink(raw), isLink, raw)
        }
    }

    @Test
    fun existingFormsKeepTheirExactOldClassification() {
        // **向后兼容的回归防线**：这一串里每一行的分类都与引入链接项之前**逐字一致**
        // （APP / SHORTCUT / INVALID 三分类）。任何一行变了，存量配置里就有用户的项
        // 会被当成别的东西处理，而编译、构建、日志全都正常
        val expected = mapOf(
            "com.tencent.mm" to ShortcutEntries.Kind.APP,
            "com.eg.android.AlipayGphone" to ShortcutEntries.Kind.APP,
            "com.tencent.mm/launch_type_scan_qrcode" to ShortcutEntries.Kind.SHORTCUT,
            "com.eg.android.AlipayGphone/1001" to ShortcutEntries.Kind.SHORTCUT,
            "com.foo/a/b" to ShortcutEntries.Kind.SHORTCUT,
            "" to ShortcutEntries.Kind.INVALID,
            "/x" to ShortcutEntries.Kind.INVALID,
            "com.tencent.mm/" to ShortcutEntries.Kind.INVALID,
        )
        for ((raw, kind) in expected) {
            assertEquals(kind, ShortcutEntries.parse(raw).kind, raw)
            assertEquals(null, ShortcutEntries.parse(raw).linkUri, "存量项不该有 linkUri：$raw")
        }
    }

    @Test
    fun linkItemsFollowTheSameKeepRuleAsShortcuts() {
        // 链接项与快捷方式项共用同一张保留规则表（没有「宿主可启动」这个概念，
        // 所以调用侧一律按宿主不可启动那一支判 —— 见 FanTargets 的注释）：
        // 这里只钉住「表本身对新 kind 没有特殊分支」，避免有人日后给它开一条静默丢弃的路
        assertEquals(
            ShortcutEntries.Verdict.KEEP_UNKNOWN,
            ShortcutEntries.verdictOf(
                hostLaunchable = false, catalogOk = false, inCatalog = false, catalogEnabled = null,
            ),
        )
    }
}
