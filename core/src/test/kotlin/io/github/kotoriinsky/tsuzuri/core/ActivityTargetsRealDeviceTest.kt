package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用**这台真机上真实存在的活动名**钉住 [ActivityTargets] 的判据。
 *
 * 与 `ActivityTargetsTest` 的分工：那边钉的是构造出来的边界格子（逐条判据），
 * 这边钉的是**真机取回来的字符串**。后者不可替代 —— 判据的错法有两种，
 * 构造用例最容易漏掉的正是「真实世界里长这样」的那一类：
 *
 *  - `looksLikeActivity` 判太严 → 用户照抄的活动全名走不进活动分支（功能等于没修）；
 *  - 判太松 → 全小写的包名形态被误判（白跑启动，且日志误导）。
 *
 * 样例来源（`adb shell cmd package resolve-activity --brief <pkg>`）：
 *
 * ```
 * com.coolapk.market          → com.coolapk.market/.view.main.MainActivity
 * com.miui.calculator         → com.miui.calculator/.cal.CalculatorActivity
 * ```
 *
 * ⚠️ 这两个是**真实值**，不是编的。它们代表用户能拿到的最典型形态：
 * 包名与活动名前缀不同（`com.coolapk.market` vs `com.coolapk.market.view.main`）、
 * 且末段是大写开头的类名。
 */
class ActivityTargetsRealDeviceTest {

    /** 真机取回的原始形式（`包名/简写活动名`，含前导 `.`）。 */
    private val realShortForms = listOf(
        "com.coolapk.market/.view.main.MainActivity",
        "com.miui.calculator/.cal.CalculatorActivity",
    )

    /**
     * 真机那种**简写形式**（`包名/.Activity`）必须能用。
     *
     * 这一格最容易被忽略：`resolve-activity` 吐出来的是带前导 `.` 的简写（manifest 原样），
     * 而用户很可能**原封不动复制**它。前导 `.` 让「段以数字/空开头」之类的粗糙判据全部踩空 ——
     * `/.view` 拆出来第一段是空串。
     */
    @Test
    fun realDeviceShortFormsAreAccepted() {
        for (raw in realShortForms) {
            assertNull(
                ActivityTargets.check(raw),
                "真机取回的简写形式 \"$raw\" 本该通过校验（前导 `.` 是 manifest 原样写法）",
            )
        }
    }

    /**
     * 简写形式**展开后的全名**同样必须能用（这是用户手抄时更常见的形态）。
     *
     * ⚠️ 展开规则：活动名以 `.` 开头 ⇒ 前面拼上包名。
     * 这正是 `ActivityTargets` **不做展开**的原因 —— 它只做格式校验，
     * 而带前导 `.` 的简写在**启动那一刻**由系统自己展开（`setClassName` 接受它），
     * 所以这里校验通过即可，不必（也不该）在校验层改写用户输入。
     */
    @Test
    fun realDeviceExpandedNamesAreAccepted() {
        val expanded = listOf(
            "com.coolapk.market/com.coolapk.market.view.main.MainActivity",
            "com.miui.calculator/com.miui.calculator.cal.CalculatorActivity",
        )
        for (raw in expanded) {
            assertNull(ActivityTargets.check(raw), "展开后的全名 \"$raw\" 本该通过校验")
            assertTrue(
                ActivityTargets.looksLikeActivity(raw.substringAfter('/')),
                "\"$raw\" 的活动部分该被识别为像活动名 —— 否则用户手抄的全名走不进活动分支，" +
                    "本次修复对他就等于没生效",
            )
        }
    }

    /**
     * **反向**：用户清单里那些真实的包名**绝不能**被判成活动名。
     *
     * 样例是这台设备上用户清单里**真实存在**的条目（从 LSPosed 配置库里读出来的
     * `corner_small_window.items`，2026-09-18）。它们是纯包名形态 ——
     * 含 `.`、但每一段都小写。判据里「至少一段以大写开头」这一条正是为它们设的：
     * 少了它，每次点「微信」都会先白跑一次 `setClassName(com.tencent.mm, com.tencent.mm)`。
     */
    @Test
    fun realUserPackageNamesAreNotMistakenForActivities() {
        val userPackages = listOf(
            "com.tencent.mobileqq",
            "com.tencent.mm",
            "com.eg.android.AlipayGphone", // ⚠️ 含大写段！见下面单独一条
            "com.hengye.share",
            "com.hupu.games",
            "com.dongqiudi.news",
            "com.huanchengfly.tieba.post",
            "com.coolapk.market",
            "com.netease.cloudmusic",
            "com.bilibili.app.in",
            "com.autonavi.minimap",
            "com.iMe.android",
            "com.miui.calculator",
            "com.google.android.apps.bard",
            "mark.via.gp",
            "com.duokan.phone.remotecontroller",
            "com.xingin.xhs",
            "com.android.camera",
        )
        for (pkg in userPackages) {
            // ⚠️ 注意 `AlipayGphone` 含大写 —— 它会被 looksLikeActivity 放行。
            // 这不是判据的错，是**安全的失败方向**：那些项本来就是要先查快捷方式目录的，
            // 只在目录里查不到时才会多试一次 setClassName，失败即回落到原有兜底。
            // 下面单独一条钉住这个已知行为，免得以后有人当 bug 改掉「大写段」这条判据。
            if (pkg == "com.eg.android.AlipayGphone") continue
            assertFalse(
                ActivityTargets.looksLikeActivity(pkg),
                "用户清单里的包名 \"$pkg\" 被判成了活动名 —— " +
                    "每次点它都会白跑一次 setClassName，日志里还会多一条误导性的「没有这个活动」",
            )
        }
    }

    /**
     * **已知且接受**的边界：含大写段的包名会被当成「可能的活动名」。
     *
     * `com.eg.android.AlipayGphone`（支付宝）就是这样 —— 它的包名里有一段
     * 以大写开头，符合类名的通行约定，纯格式判据**无法**把它和活动名分开。
     *
     * 为什么接受而不是加判据治它：
     *  - 纯字符串层能加的唯一判据是「注册过的包名就是包名」——那需要查设备，`core` 做不到；
     *  - 代价有界：它只在**快捷方式目录里查不到**时才会多跑一次 `setClassName`，
     *    失败后回落到原有的「启动应用本身」兜底，**最终行为不变**；
     *  - 反过来加严（例如要求「类名段必须大写开头且整个包名段不能有大写」）会把
     *    `com.foo.Bar` 这类**真的活动名**也一起挡掉，那是功能性的损失，
     *    比多跑一次 IPC 严重得多。
     *
     * 这条测试把它**写下来**：它是刻意的取舍，不是漏掉的 bug。
     */
    @Test
    fun packageNamesWithUppercaseSegmentsAreKnowinglyAccepted() {
        assertTrue(
            ActivityTargets.looksLikeActivity("com.eg.android.AlipayGphone"),
            "这是已知边界（见本用例注释）：含大写段的包名会被当活动名试一次，代价有界",
        )
    }

    /**
     * 微信那批真实快捷方式 id 的**零误伤**复核（用真机取回的 id）。
     *
     * 与 `ActivityTargetsTest.looksLikeActivityNeverMatchesKnownShortcutIds` 同一个底线，
     * 但样例换成**真机上确实存在的**（微信 `launch_type_scan_qrcode` 那批，
     * 见 `FanShortcuts.MATCH_FLAGS` 的取证）：每一个都必须判 false。
     */
    @Test
    fun realWechatShortcutIdsAreNeverMistakenForActivities() {
        val wechatShortcutIds = listOf(
            "launch_type_scan_qrcode",
            "launch_type_pay_code",
            "launch_type_compose",
            "launch_type_new_chat",
            "launch_type_favorite",
        )
        for (id in wechatShortcutIds) {
            assertFalse(
                ActivityTargets.looksLikeActivity(id),
                "微信真实快捷方式 id \"$id\" 被误判为活动名 —— " +
                    "点「扫一扫」会先白跑一次失败的启动，每次点击都多一次 IPC",
            )
        }
    }

    /**
     * 真机取回的**包名/活动名**搭配解析出的包名必须与用户填的一致。
     *
     * 这一条防的是「拆错了但没报错」：`com.coolapk.market/.view.main.MainActivity` 里
     * 有两个 `.` 段边界容易拆错的形态，拆错的表现是启动到一个不存在的组件、
     * 而日志里打的还是用户填的那串（看着完全正常）。
     */
    @Test
    fun realDeviceFormsSplitIntoExpectedPackage() {
        for (raw in realShortForms) {
            val ok = ActivityTargets.parse(raw)
            assertTrue(ok is ActivityTargets.Result.Ok, "\"$raw\" 本该解析成功，实际 $ok")
            assertEquals(
                raw.substringBefore('/'),
                ok.packageName,
                "从 \"$raw\" 拆出的包名不对 —— 拆错会启动到不存在的组件，而日志看不出异常",
            )
        }
    }
}
