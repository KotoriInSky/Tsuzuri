package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ActivityTargets] 的校验表、自动识别判据与显示名。
 *
 * 这一层错法的两种方向**都不报错**：
 *
 *  - **放行太多**：一串不是活动名的东西被拿去 `setClassName`，白跑一次启动，
 *    日志里出现一条「组件不存在」——用户以为是应用没装；
 *  - **拒绝太多**：合法的活动项被拒，用户看到一句「格式不对」但不知道哪不对，
 *    反复试同一串字符（这是要消灭的「静默失败」的另一种形态）。
 *
 * 所以下面**逐条**钉住类注释里那张表的每一行，并额外钉四条不变量：
 * ① 存量三类项（应用 / 快捷方式 / 链接）永远不会被认成活动项；
 * ② [ActivityTargets.looksLikeActivity] 对**存量快捷方式 id** 一律返回 false（零误伤底线）；
 * ③ 通过校验的串一定能被规范化成一个稳定形式；
 * ④ 规范化是幂等的（写入侧会反复调它）。
 */
class ActivityTargetsTest {

    // ── 前缀识别：整串开头才算 ──────────────────────────────────────────

    @Test
    fun prefixIsDetectedOnlyAtTheStartOfTheWholeItem() {
        assertTrue(ActivityTargets.isActivity("activity:com.foo/.Bar"))
        // 大小写不敏感（与 LinkTargets.PREFIX 同款理由）
        assertTrue(ActivityTargets.isActivity("ACTIVITY:com.foo/.Bar"))
        assertTrue(ActivityTargets.isActivity("Activity:com.foo/.Bar"))
        // 前缀后面什么都没有也算「是活动项」（校验会判 EMPTY）：
        // 判定的是「这条项自称是活动」，不是「它合法」
        assertTrue(ActivityTargets.isActivity("activity:"))
        // 短于前缀 / 没有冒号：都不是
        assertFalse(ActivityTargets.isActivity("activit"))
        assertFalse(ActivityTargets.isActivity("activity"))
        // ⚠️ 前缀是**整串**的属性，不是子串的属性：
        // `com.foo/activity:bar` 是「快捷方式项，id 是 activity:bar」，不是活动项
        assertFalse(ActivityTargets.isActivity("com.foo/activity:bar"))
    }

    @Test
    fun targetOfStripsPrefixAndTrims() {
        assertEquals("com.foo/.Bar", ActivityTargets.targetOf("activity:com.foo/.Bar"))
        assertEquals("com.foo/.Bar", ActivityTargets.targetOf("activity:  com.foo/.Bar  "))
        // 裸形式：原样返回（trim 掉首尾空白）
        assertEquals("com.foo/.Bar", ActivityTargets.targetOf("  com.foo/.Bar  "))
        // 非法项也要能原样拿出来打日志（用户要照着核对）
        assertEquals("/", ActivityTargets.targetOf("activity:/"))
    }

    // ── 校验表：逐格钉住 ────────────────────────────────────────────────

    @Test
    fun validFormsAreAccepted() {
        assertNull(ActivityTargets.check("com.tencent.mm/com.tencent.mm.ui.LauncherUI"))
        // 简写形式很常见（manifest 里写 .MainActivity，展开后是包限定名）
        assertNull(ActivityTargets.check("com.android.settings/com.android.settings.Settings"))
        // 内部类：`$` 是合法的 Java 标识符字符
        assertNull(ActivityTargets.check("com.foo/com.foo.Outer\$Inner"))
        // 下划线开头的段合法
        assertNull(ActivityTargets.check("com.foo/com.foo._Bar"))
        // 只有一个点也能成立（短包名）
        assertNull(ActivityTargets.check("a.b/a.b.C"))
    }

    /**
     * **带前导 `.` 的简写形式**必须放行。
     *
     * 这是 manifest 里最常见的写法（`android:name=".MainActivity"` = 本包下这个类），
     * 也是 `adb shell cmd package resolve-activity --brief <pkg>` 吐出来的形态 ——
     * 也就是**用户查活动名时唯一能直接复制到的形式**。
     *
     * ⚠️ 这一格是被 `ActivityTargetsRealDeviceTest` 补上的：构造用例里原本只有全限定名，
     * 漏掉了这个真机最常见的形态。拦掉它的后果是「用户照着系统告诉他的格式填，
     * 却被说格式不对」，而那种拒绝没有任何自愈路径。
     *
     * 只放过**最前面**那一个空段：中间的空段（`com..Bar`）仍然拒绝 —— 那是真的写错了。
     */
    @Test
    fun leadingDotShorthandIsAccepted() {
        assertNull(ActivityTargets.check("com.coolapk.market/.view.main.MainActivity"))
        assertNull(ActivityTargets.check("com.miui.calculator/.cal.CalculatorActivity"))
        assertNull(ActivityTargets.check("com.foo/.MainActivity"))
        // 中间的空段仍然拒绝（不是前导 `.` 那一格）
        assertEquals(ActivityTargets.Rejection.BAD_ACTIVITY, ActivityTargets.check("com.foo/com..Bar"))
        // 孤立的 `.` 不算简写（没有任何类名）
        assertEquals(ActivityTargets.Rejection.BAD_ACTIVITY, ActivityTargets.check("com.foo/."))
    }

    @Test
    fun emptyAndMissingSeparatorAreRejected() {
        assertEquals(ActivityTargets.Rejection.EMPTY, ActivityTargets.check(""))
        assertEquals(ActivityTargets.Rejection.EMPTY, ActivityTargets.check("   "))
        // 没有 `/`：分不出包名与活动名
        assertEquals(ActivityTargets.Rejection.NO_SEPARATOR, ActivityTargets.check("com.foo.Bar"))
    }

    /**
     * ⚠️ **`parse` 的入参必须是「包名/活动名」整串，不能只给活动名**（真机踩到）。
     *
     * 2026-09-18 的模块日志里出现了这么一句：
     *
     * ```
     * 活动项 "com.miui.voiceassistProxy/…ScreenRecognitionActivity"
     *   格式不合法（缺少 `/`，应写成「包名/活动全名」）
     * ```
     *
     * 那条配置里**明明有 `/`** —— 报出的原因指向一个用户没犯的错。
     * 根因在 `FanShortcuts`：(b) 分支用「像不像活动名」的判据（`id`，已被拆掉包名的那段）
     * 去调 `parse`，而 `parse` 要的正是包名。已修（改为传 `target.key`）。
     *
     * 这条测试把「`parse` 遇到裸活动名会报 `NO_SEPARATOR`」钉住 ——
     * 老实说这个行为**是对的**（没有包名确实解析不出组件），
     * 它是故意留在这里的**症状**：以后有人再想「让 parse 宽容一点，
     * 只给活动名也能过」时，会先看到这条注释指向的真实事故 ——
     * 正确做法是**调用方传对串**，不是让校验变松。
     */
    @Test
    fun parsingBareActivityNameReportsMissingSeparator() {
        // 单独一个活动名（没有包名）解析不出组件 —— 这是对的，但它不该被误当成
        // 「用户少打了一个 /」，所以调用方必须传整串（见上面的真实事故）
        val r = ActivityTargets.parse("com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity")
        assertEquals(ActivityTargets.Rejection.NO_SEPARATOR, (r as ActivityTargets.Result.Rejected).reason)

        // 整串（带包名）才能解析成功 —— 那才是调用方该传的东西
        val ok = ActivityTargets.parse(
            "com.miui.voiceassist/com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity",
        )
        assertTrue(ok is ActivityTargets.Result.Ok)
    }

    /**
     * 回归：**纯空白必须报 `EMPTY`，不能报 `NO_SEPARATOR`**。
     *
     * 真实踩到过：`check` 先判 `isEmpty()` 才 trim，`"   "` 字符数不是 0，
     * 于是掉到下面判成 `NO_SEPARATOR` —— 用户手滑粘了个空格，
     * 界面上却显示「分不出包名与活动名」，跟他实际犯的错毫无关系。
     *
     * 这类 bug 的共性：**报出的原因指向错误的修复动作**。用户会去检查
     * 「是不是少打了个 `/`」，而不是「是不是多了空格」。
     */
    @Test
    fun whitespaceOnlyReportsEmptyNotMissingSeparator() {
        for (blank in listOf("\t", "\n", " \t \n ", "    ")) {
            assertEquals(
                ActivityTargets.Rejection.EMPTY,
                ActivityTargets.check(blank),
                "纯空白 \"${blank.replace("\n", "\\n").replace("\t", "\\t")}\" 应报 EMPTY（什么都没填），" +
                    "报 NO_SEPARATOR 会把用户引向错误的修复方向",
            )
        }
    }

    /**
     * 回归：**首尾空白不该影响前缀识别**。
     *
     * 真实踩到过：`targetOf` 先判前缀后 trim，而前缀是「整串开头」的属性 ——
     * `"  activity:com.foo/.Bar"` 首字符是空格、判不出前缀，于是走裸形式分支，
     * trim 后字符串里还留着字面量 `activity:`，那个冒号过不了包名校验，
     * 用户看到「包名不合法」。
     *
     * 与之对照：[ActivityTargets.isActivity] 对同一输入**仍然**返回 false
     * —— 这是刻意的：分类要严格（前缀必须是整串开头），解析要宽容（吃掉边界空白）。
     */
    @Test
    fun surroundingWhitespaceDoesNotBreakPrefixParsing() {
        val expected = ActivityTargets.Result.Ok("com.foo", "com.foo.Bar")
        // 前缀后、`/` 前后、整串首尾，各种空白位置都要能解析
        for (raw in listOf(
            "activity:com.foo/com.foo.Bar",
            "  activity:com.foo/com.foo.Bar",
            "activity:  com.foo/com.foo.Bar",
            "activity:com.foo/com.foo.Bar  ",
            "  activity:  com.foo/com.foo.Bar  ",
            "\tactivity:\tcom.foo/com.foo.Bar\t",
        )) {
            assertEquals(expected, ActivityTargets.parse(raw), "带空白的写法解析出来不是预期的包名/活动名：\"$raw\"")
        }
        // 严格的那一面保持不变：分类不因为多几个空格就改变
        assertFalse(
            ActivityTargets.isActivity("  activity:com.foo/com.foo.Bar"),
            "isActivity 是分类用的严格判据，不该被首尾空白影响",
        )
    }

    /**
     * 回归：**复制时多带的首尾换行不该被当成「活动名非法」**。
     *
     * 控制字符扫描原本扫的是未 trim 的原串，用户从别处复制活动名时
     * 末尾常带一个换行 —— 那会被判成 `BAD_ACTIVITY`（含控制字符），
     * 而真实原因是「多了个不可见的换行」。现在先 trim 再扫，
     * 只有**夹在中间**的控制字符才算是注入/手误。
     */
    @Test
    fun trailingNewlineIsTrimmedButInnerControlCharStillRejected() {
        assertNull(
            ActivityTargets.check("com.foo/com.foo.Bar\n"),
            "末尾换行（复制粘贴的常见副作用）应被 trim 掉，而不是判成活动名非法",
        )
        assertNull(ActivityTargets.check("\ncom.foo/com.foo.Bar"))
        // 夹在中间的仍然是硬错误：那是拼接注入的典型形态
        assertEquals(
            ActivityTargets.Rejection.BAD_ACTIVITY,
            ActivityTargets.check("com.foo/com.foo.Bar\nBaz"),
        )
    }

    @Test
    fun emptyPartsAreRejected() {
        assertEquals(ActivityTargets.Rejection.EMPTY_PART, ActivityTargets.check("/com.foo.Bar"))
        assertEquals(ActivityTargets.Rejection.EMPTY_PART, ActivityTargets.check("com.foo/"))
    }

    @Test
    fun extraSeparatorIsRejected() {
        // `setClassName` 只吃「包名 + 单个类名」，多一段会启动到语义不明的地方
        assertEquals(
            ActivityTargets.Rejection.EXTRA_SEPARATOR,
            ActivityTargets.check("com.foo/com.foo/a"),
        )
    }

    @Test
    fun badPackageIsRejected() {
        // 段为空
        assertEquals(ActivityTargets.Rejection.BAD_PACKAGE, ActivityTargets.check("com..foo/com.foo.Bar"))
        // 以数字开头（Java 标识符不允许）
        assertEquals(ActivityTargets.Rejection.BAD_PACKAGE, ActivityTargets.check("com.1foo/com.foo.Bar"))
        // `.` 开头
        assertEquals(ActivityTargets.Rejection.BAD_PACKAGE, ActivityTargets.check(".com.foo/com.foo.Bar"))
        // 含非法字符
        assertEquals(ActivityTargets.Rejection.BAD_PACKAGE, ActivityTargets.check("com-foo/com.foo.Bar"))
    }

    @Test
    fun badActivityNameIsRejected() {
        // 不含 `.`：不带包的活动名在 manifest 里合法，但**用户只写简单名我们解析不出包**
        // （活动名不知道它属于哪个包），那不是一条能启动的项
        assertEquals(
            ActivityTargets.Rejection.BAD_ACTIVITY,
            ActivityTargets.check("com.foo/MainActivity"),
        )
        // 段为空
        assertEquals(ActivityTargets.Rejection.BAD_ACTIVITY, ActivityTargets.check("com.foo/com..Bar"))
        // 以数字开头
        assertEquals(ActivityTargets.Rejection.BAD_ACTIVITY, ActivityTargets.check("com.foo/com.1Bar"))
        // 含非法字符（连字符）
        assertEquals(ActivityTargets.Rejection.BAD_ACTIVITY, ActivityTargets.check("com.foo/com.foo.Bar-Baz"))
    }

    @Test
    fun whitespaceAndControlCharsAreRejected() {
        // 裸空格
        assertEquals(
            ActivityTargets.Rejection.BAD_ACTIVITY,
            ActivityTargets.check("com.foo/com.foo.Bar Baz"),
        )
        // 换行（配置项内部本来也不允许换行，且是拼接注入的典型形态）
        assertEquals(
            ActivityTargets.Rejection.BAD_ACTIVITY,
            ActivityTargets.check("com.foo/com.foo.Bar\nBaz"),
        )
        // 制表符
        assertEquals(
            ActivityTargets.Rejection.BAD_ACTIVITY,
            ActivityTargets.check("com.foo/com.foo.Bar\tBaz"),
        )
        // 非 ASCII 字母也不算合法标识符字符（Java 规范定义的集合是 ASCII 标识符）
        assertEquals(
            ActivityTargets.Rejection.BAD_ACTIVITY,
            ActivityTargets.check("com.foo/com.foo.活动"),
        )
    }

    @Test
    fun tooLongIsRejected() {
        val pkg = "com.foo"
        val act = "com.foo." + "A".repeat(ActivityTargets.MAX_LENGTH)
        assertEquals(ActivityTargets.Rejection.TOO_LONG, ActivityTargets.check("$pkg/$act"))
    }

    // ── parse：带前缀与不带前缀都能过 ───────────────────────────────────

    @Test
    fun parseAcceptsBothPrefixedAndBareForms() {
        val expected = ActivityTargets.Result.Ok(
            "com.tencent.mm",
            "com.tencent.mm.ui.LauncherUI",
        )
        assertEquals(expected, ActivityTargets.parse("activity:com.tencent.mm/com.tencent.mm.ui.LauncherUI"))
        // **裸形式也能过** —— 这正是「不加前缀也能生效」那条路要用到的能力
        assertEquals(expected, ActivityTargets.parse("com.tencent.mm/com.tencent.mm.ui.LauncherUI"))
    }

    @Test
    fun parseReportsRejectionWithReason() {
        val r = ActivityTargets.parse("activity:com.foo/MainActivity")
        assertTrue(r is ActivityTargets.Result.Rejected)
        assertEquals(ActivityTargets.Rejection.BAD_ACTIVITY, r.reason)
    }

    // ── 自动识别判据：looksLikeActivity ─────────────────────────────────

    @Test
    fun looksLikeActivityAcceptsPackageQualifiedClassNames() {
        assertTrue(ActivityTargets.looksLikeActivity("com.tencent.mm.ui.LauncherUI"))
        assertTrue(ActivityTargets.looksLikeActivity("com.android.settings.Settings"))
        assertTrue(ActivityTargets.looksLikeActivity("com.foo.Outer\$Inner"))
    }

    @Test
    fun looksLikeActivityRejectsNamesWithoutDot() {
        // 快捷方式 id 的典型形态：没有 `.` → 一律不是活动名
        assertFalse(ActivityTargets.looksLikeActivity("scan"))
        assertFalse(ActivityTargets.looksLikeActivity("launch_type_scan_qrcode"))
        assertFalse(ActivityTargets.looksLikeActivity(""))
    }

    @Test
    fun looksLikeActivityRejectsAllLowercaseDottedIds() {
        // 含 `.` 但没有任何一段以大写开头 → 不像类名（快捷方式 id 可能是 `foo.bar`）
        assertFalse(ActivityTargets.looksLikeActivity("com.tencent.mm"))
        assertFalse(ActivityTargets.looksLikeActivity("a.b.c"))
        // 以数字开头的段（非法标识符）
        assertFalse(ActivityTargets.looksLikeActivity("com.1foo.Bar"))
    }

    /**
     * **零误伤底线**：存量里真实存在的快捷方式 id 一律不能被认成活动名。
     *
     * 为什么这条是底线而不是「尽量」：被误认的后果是用户点「微信扫一扫」时，
     * 我们先去试 `setClassName(com.tencent.mm, launch_type_scan_qrcode)` ——
     * 白跑一次启动、日志里多一条「组件不存在」，然后才走回快捷方式那条路。
     * 虽然最终结果不算错，但每次点击都多一次失败的 IPC + 一条误导日志。
     */
    @Test
    fun looksLikeActivityNeverMatchesKnownShortcutIds() {
        val knownShortcutIds = listOf(
            "launch_type_scan_qrcode",
            "scan",
            "compose",
            "settings",
            "new_chat",
            "pay_code",
            "favorite",
            "share",
        )
        for (id in knownShortcutIds) {
            assertFalse(
                ActivityTargets.looksLikeActivity(id),
                "快捷方式 id \"$id\" 被误判为活动名 —— 这会让每次点击多跑一次失败的启动",
            )
        }
    }

    // ── 显示名兜底 ──────────────────────────────────────────────────────

    @Test
    fun fallbackLabelTakesLastSegment() {
        assertEquals(
            "LauncherUI",
            ActivityTargets.fallbackLabel("com.tencent.mm.ui.LauncherUI"),
        )
        // 内部类：取最后一段（`Outer$Inner`）
        assertEquals("Outer\$Inner", ActivityTargets.fallbackLabel("com.foo.Outer\$Inner"))
        // 没有 `.`：原样返回（不该返回空）
        assertEquals("MainActivity", ActivityTargets.fallbackLabel("MainActivity"))
        // 空串：原样返回（调用方会走别的兜底）
        assertEquals("", ActivityTargets.fallbackLabel(""))
        // 首尾空白先 trim
        assertEquals("Bar", ActivityTargets.fallbackLabel("  com.foo.Bar  "))
    }

    // ── 不变量 ──────────────────────────────────────────────────────────

    /**
     * 不变量 ①：存量三类项（应用 / 快捷方式 / 链接）永远不会被认成活动项。
     *
     * 与 `ShortcutEntriesTest.existingFormsAreNeverSeenAsLinks` 同一条纪律：
     * 前缀策略能成立，唯一的依据就是「存量格式不可能长得像新前缀」。
     */
    @Test
    fun existingFormsAreNeverSeenAsActivities() {
        val existing = listOf(
            "com.tencent.mm",
            "com.tencent.mm/launch_type_scan_qrcode",
            "link:weixin://scanqrcode",
            "com.android.settings/com.android.settings.Settings",
        )
        // 前三条是存量格式；第四条是本次的**裸活动名**写法 —— 它**不该**被判成「显式声明」，
        // 而应该走 looksLikeActivity 那条自动识别路（这是两条路共存的关键）
        for (item in existing) {
            assertFalse(
                ActivityTargets.isActivity(item),
                "存量项 \"$item\" 被误判成 activity: 前缀形式",
            )
        }
    }

    /** 不变量 ②：通过校验 ⇒ 能规范化 ⇒ 再 parse 还是同一个结果（往返稳定）。 */
    @Test
    fun checkThenStoreThenParseRoundTrips() {
        val samples = listOf(
            "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
            "com.android.settings/com.android.settings.Settings",
            "com.foo/com.foo.Outer\$Inner",
        )
        for (s in samples) {
            assertNull(ActivityTargets.check(s), "样例 \"$s\" 本该通过校验")
            val ok = ActivityTargets.parse(s) as ActivityTargets.Result.Ok
            val stored = ActivityTargets.stored(ok.packageName, ok.activityName)
            assertEquals(
                ok,
                ActivityTargets.parse(stored),
                "规范化之后 parse 的结果变了：\"$s\" → \"$stored\"",
            )
        }
    }

    /** 不变量 ③：规范化是幂等的（写入侧会反复调它）。 */
    @Test
    fun storedIsIdempotent() {
        val ok = ActivityTargets.parse("  activity:  com.foo/com.foo.Bar  ") as ActivityTargets.Result.Ok
        val once = ActivityTargets.stored(ok.packageName, ok.activityName)
        val twice = ActivityTargets.stored(
            (ActivityTargets.parse(once) as ActivityTargets.Result.Ok).packageName,
            (ActivityTargets.parse(once) as ActivityTargets.Result.Ok).activityName,
        )
        assertEquals(once, twice)
    }

    /** 不变量 ④：`looksLikeActivity` 放行的串一定也能过 `check`（不会「值得试」但「不合法」）。 */
    @Test
    fun anythingLooksLikeActivityIsAlsoValidAsTarget() {
        val samples = listOf(
            "com.foo.Bar",
            "com.tencent.mm.ui.LauncherUI",
            "com.foo.Outer\$Inner",
            "a.b.C",
        )
        for (id in samples) {
            if (ActivityTargets.looksLikeActivity(id)) {
                assertNull(
                    ActivityTargets.check("com.host/$id"),
                    "looksLikeActivity 放行了 \"$id\"，但它过不了 check —— 两条判据不自洽",
                )
            }
        }
    }

    @Test
    fun rejectionReasonsAreAllDistinct() {
        // 每种原因要能单独给用户一句人话；重复的枚举值说明表里有冗余行
        val reasons = ActivityTargets.Rejection.entries
        assertEquals(reasons.size, reasons.toSet().size)
        assertNotNull(reasons.firstOrNull { it == ActivityTargets.Rejection.EMPTY })
    }
}
