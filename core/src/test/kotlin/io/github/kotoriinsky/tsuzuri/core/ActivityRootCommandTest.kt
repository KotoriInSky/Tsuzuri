package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ActivityRootCommand] 的逐格钉死。
 *
 * 这个对象的错法分两类，都在这里钉住：
 *
 *  - **安全类**（放行了一个能改变命令语义的字符）—— 严重，且**不会自己暴露**：
 *    命令照样跑、照样成功，只是跑的不是用户写的那个东西；
 *  - **判定类**（把成功说成失败 / 反之）—— 直接决定用户看到的结论对不对。
 *
 * 与 [ActivityTargetsTest] 的分工：那边管「这串东西是不是合法的包名/活动名」，
 * 这边只做**拼接与判定**，所以绝大多数非法输入在这里被拒的原因是「它过不了
 * [ActivityTargets.check]」而不是本对象自己的逻辑 —— 这正是刻意复用校验的结果。
 */
class ActivityRootCommandTest {

    // ── 构造：合法输入 ────────────────────────────────────────────────

    @Test
    fun fullQualifiedNameProducesPlainComponent() {
        val plan = ActivityRootCommand.plan(
            "com.miui.voiceassist",
            "com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity",
        )
        assertNotNull(plan)
        assertEquals(
            "com.miui.voiceassist/com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity",
            plan.component,
        )
    }

    /**
     * 真机实测：`resolve-activity` 吐出来的就是这种**前导 `.` 简写**，
     * 它是用户唯一能直接复制的形态（见 [ActivityTargets.isValidClassName] 的注释）。
     * root 路必须原样接受 —— 否则「root 能开 exported=false 的活动」这个卖点
     * 在用户实际复制到的格式上直接失效。
     */
    @Test
    fun leadingDotShorthandIsAccepted() {
        val plan = ActivityRootCommand.plan("com.miui.calculator", ".cal.CalculatorActivity")
        assertNotNull(plan)
        assertEquals("com.miui.calculator", plan.packageName)
        assertEquals(".cal.CalculatorActivity", plan.activityName)
        assertEquals("com.miui.calculator/.cal.CalculatorActivity", plan.component)
    }

    @Test
    fun innerClassDollarSignIsPreserved() {
        // 内部类（`$`）是合法类名，不能被「规范化」掉 —— 去掉就不是同一个类了
        val plan = ActivityRootCommand.plan("com.foo", "com.foo.Outer\$Inner")
        assertNotNull(plan)
        assertEquals("com.foo/com.foo.Outer\$Inner", plan.component)
    }

    @Test
    fun commandUsesAbsoluteAmPath() {
        val plan = ActivityRootCommand.plan("com.foo", "com.foo.Bar")
        assertNotNull(plan)
        // 绝对路径：su 的环境里 PATH 不保证有 /system/bin（见 AM_PATH 注释）
        assertEquals("/system/bin/am start -n com.foo/com.foo.Bar", ActivityRootCommand.commandOf(plan))
    }

    @Test
    fun commandContainsNoNewline() {
        // 逐行写进 su 的 stdin 时，命令本身含换行会把一条命令切成两条
        val plan = ActivityRootCommand.plan("com.foo", "com.foo.Bar")
        assertNotNull(plan)
        assertFalse(ActivityRootCommand.commandOf(plan).contains('\n'))
    }

    // ── 安全：注入面逐格拒绝 ──────────────────────────────────────────
    //
    // 这一组是**本文件最重要的部分**。每一个用例都对应一种真实的注入形态 ——
    // 它们必须全部被拒，且拒绝的理由是「过不了校验」而不是「拼接时转义了」
    // （转义是会被绕过的，白名单不会）。

    @Test
    fun shellMetacharactersInActivityAreRejected() {
        val attacks = listOf(
            "com.foo.Bar;rm -rf /", // 分号：命令分隔
            "com.foo.Bar|id", // 管道
            "com.foo.Bar`id`", // 反引号：命令替换
            "com.foo.Bar\$(id)", // $()：命令替换
            "com.foo.Bar&&reboot", // 逻辑与
            "com.foo.Bar>/sdcard/pwn", // 重定向
            "com.foo.Bar\nid", // 换行：新命令
            "com.foo.Bar\r\nid", // CRLF
            "com.foo.Bar'id'", // 单引号
            "com.foo.Bar\"id\"", // 双引号
            // ⚠️ 注意这里**没有**「尾随空格」与「前导空格」。
            // 它们会被 trim 掉，剩下的部分仍要过完整校验 —— 见
            // [surroundingWhitespaceIsTrimmedNotRejected]。
            // 把它们当攻击列在这里是错的：`"com.foo.Bar "` trim 之后就是
            // `"com.foo.Bar"`，那是合法组件名，拒绝它反而会误伤
            // 「用户从终端复制时多带了一个空格」这个高频场景。
        )
        for (bad in attacks) {
            assertNull(
                ActivityRootCommand.plan("com.foo", bad),
                "活动名 \"$bad\" 含能改变命令语义的字符，必须被拒绝",
            )
        }
    }

    /**
     * 首尾空白**被 trim 掉**，不是被拒绝。这是刻意的（初版用例在这里写错过）。
     *
     * 安全上没问题：trim 之后剩下的部分**仍要过完整校验**，所以
     * `"com.foo.Bar ; id"` 里的 `;`（在中间，trim 不掉）照样被拒。
     * 而收益是实打实的 —— 用户从终端/网页复制组件名时带前导或尾随空格是常态，
     * 为这个报「格式不对」会让他不知道该删哪里。
     */
    @Test
    fun surroundingWhitespaceIsTrimmedNotRejected() {
        val plan = ActivityRootCommand.plan("  com.foo  ", "  com.foo.Bar  ")
        assertNotNull(plan, "首尾空白应当被 trim 掉而不是拒绝")
        assertEquals("com.foo", plan.packageName)
        assertEquals("com.foo.Bar", plan.activityName)

        // 但空白**夹在中间**时它连着别的东西，仍必须被拒
        assertNull(ActivityRootCommand.plan("com.foo", "com.foo.Bar ; id"))
        assertNull(ActivityRootCommand.plan("com.foo", "com.foo .Bar"))
    }

    @Test
    fun shellMetacharactersInPackageAreRejected() {
        val attacks = listOf(
            "com.foo;id",
            "com.foo|id",
            "com.foo\$(id)",
            "com.foo`id`",
            "com.foo/bar", // 包名里出现 `/`：会改变 -n 的参数结构
            "com.foo bar", // 包名里的空格（中间的，trim 不掉）
            "com..foo", // 空段
            "com.1foo", // 段以数字开头
            "com.foo-", // `-` 不是标识符字符
        )
        for (bad in attacks) {
            assertNull(
                ActivityRootCommand.plan(bad, "com.also.Bar"),
                "包名 \"$bad\" 非法，必须被拒绝",
            )
        }
    }

    /**
     * ⚠️ **单段包名被接受** —— 这是 [ActivityTargets.isValidPackage] 的既有行为，
     * 不是本对象引入的（那个校验只要求「每段合法」，没要求「至少两段」）。
     *
     * 这里把它写成一条**钉子**而不是「顺手修掉」：改校验会连带影响
     * [ActivityTargets] 已有的 26 条用例与真机行为，而收益是零 ——
     * 单段名 `foo` 拼出来是 `foo/com.also.Bar`，`am` 拿到后找不到这个包，
     * 会输出明确的错误（`unable to resolve`）。**坏输入有一个清晰的失败路径**，
     * 不需要在校验层提前拦。
     */
    @Test
    fun singleSegmentPackageIsAcceptedByDesign() {
        assertNotNull(ActivityRootCommand.plan("foo", "com.also.Bar"))
    }

    /**
     * ⚠️ **包名里的大写字母是合法的** —— 这条容易被想当然地写错（我第一版就写错过：
     * 把 `com.foo.Bar` 当成了非法包名）。
     *
     * Java 包名规范只要求「段非空、不以数字开头、只含标识符字符」，
     * **没有**「必须全小写」这一条（那只是约定）。而 Android 世界里反例很多 ——
     * 支付宝就是 `com.eg.android.AlipayGphone`。
     *
     * 如果给它加「必须小写」的校验，这些应用的活动项会**全部无法通过 root 路**，
     * 而报错是「包名不合法」—— 用户对着一个自己填对了的包名不知道改什么。
     */
    @Test
    fun uppercaseInPackageNameIsLegitimate() {
        val plan = ActivityRootCommand.plan("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone.AlipayLogin")
        assertNotNull(plan, "包名里的大写是合法的（支付宝就是反例），不能被拒")
        assertEquals("com.eg.android.AlipayGphone", plan.packageName)
    }

    /**
     * **零误伤底线**：真实世界里存在的包名与活动名一个都不能被拒。
     *
     * 白名单最怕的不是「漏了一个攻击」，而是「拦了一个正常用法」——
     * 后者用户的感受是「我明明填对了却说格式不对」，而且不知道改什么。
     * 所以这里的样例全部取自真机（`pm list packages` / `resolve-activity`）。
     */
    @Test
    fun realWorldPackageAndActivityNamesAreAccepted() {
        val cases = listOf(
            "com.miui.voiceassist" to "com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity",
            "com.coolapk.market" to ".view.main.MainActivity",
            "com.miui.calculator" to ".cal.CalculatorActivity",
            "com.tencent.mm" to "com.tencent.mm.ui.LauncherUI",
            "com.eg.android.AlipayGphone" to "com.eg.android.AlipayGphone.AlipayLogin",
            "com.android.settings" to ".Settings",
        )
        for ((pkg, act) in cases) {
            assertNotNull(
                ActivityRootCommand.plan(pkg, act),
                "$pkg/$act 是真机上的真实组件，不能被拒",
            )
        }
    }

    /**
     * `..` 必须在活动名里被拒。
     *
     * 它在 `-n 包名/活动名` 里不会被 shell 解释（我们不经过 shell），
     * 但它会**改变组件名解析的语义** —— `com.foo/../Bar` 在不同实现下可能
     * 被规范化成别的东西。规矩是「不确定就拒绝」（[ActivityTargets] 第 1 条纪律）。
     */
    @Test
    fun dotDotIsRejectedInActivityName() {
        assertNull(ActivityRootCommand.plan("com.foo", "com.foo..Bar"))
        // `..` 单独成段也是空的段，同样过不了
        assertNull(ActivityRootCommand.plan("com.foo", ".."))
    }

    @Test
    fun emptyPartsAreRejected() {
        assertNull(ActivityRootCommand.plan("", "com.foo.Bar"))
        assertNull(ActivityRootCommand.plan("com.foo", ""))
        assertNull(ActivityRootCommand.plan("   ", "com.foo.Bar"))
    }

    /**
     * 校验只做**一次**且与 [ActivityTargets] 完全一致 —— 守住「不另写一套 root 专用校验」。
     *
     * 测法是拿 [ActivityTargets.check] 当参照物：任何输入两边必须给同一个答案。
     * 哪天有人给 root 路加了「宽松一点」的分支，这条会红。
     */
    @Test
    fun validationMatchesActivityTargetsExactly() {
        val samples = listOf(
            "com.foo" to "com.foo.Bar",
            "com.foo" to ".Bar",
            "com.foo" to "com.foo.Bar;id",
            "com.foo" to "com.foo.Bar\n",
            "" to "com.foo.Bar",
            "com.foo" to "Bar", // 无 `.`：ActivityTargets 拒
            "com.1foo" to "com.foo.Bar", // 段以数字开头：拒
        )
        for ((pkg, act) in samples) {
            val viaTargets = ActivityTargets.check(pkg + ShortcutEntries.SEP + act) == null
            val viaRoot = ActivityRootCommand.plan(pkg, act) != null
            assertEquals(viaTargets, viaRoot, "两边对 $pkg/$act 的判断必须一致")
        }
    }

    // ── 判定：输出与退出码 ────────────────────────────────────────────

    /**
     * **`am start` 失败时退出码仍是 0** —— 这是实测出来的坑。
     *
     * 只看退出码会把「活动名拼错」判成成功，于是日志说成功、用户说没反应，
     * 比直接报失败更难查。所以输出必须参与判定。
     */
    @Test
    fun amFailureTextIsDetectedEvenThoughItExitsZero() {
        val failures = listOf(
            "Error: Activity not started, unable to resolve Intent { act=android.intent.action.MAIN }",
            "Exception occurred while executing 'start':",
            "java.lang.SecurityException: Permission Denial: starting Intent",
            "Error: Activity class {com.foo/com.foo.Bar} does not exist.",
        )
        for (out in failures) {
            assertTrue(
                ActivityRootCommand.outputIndicatesFailure(out),
                "输出 \"$out\" 代表失败，不能被当成成功",
            )
        }
    }

    @Test
    fun amSuccessTextIsNotDetectedAsFailure() {
        val ok = listOf(
            "Starting: Intent { cmp=com.foo/.Bar flg=0x10000000 }",
            // 空输出：命令受理了但不打印（某些 ROM 的 am 静默成功）
            "",
            // `Warning:` 不是失败（`am` 会用它提示无害的事，例如已有一个实例在跑）
            "Warning: Activity not started, its current task has been brought to the front",
        )
        for (out in ok) {
            assertFalse(
                ActivityRootCommand.outputIndicatesFailure(out),
                "输出 \"$out\" 不该被判成失败",
            )
        }
    }

    /**
     * ⚠️ 上面那条用例里的 `Warning: ... not started ...` 是个**真实的两难**：
     * 文本里有 "not started" 但那是 `am` 的**成功**路径（活动已在栈顶，被带到前台）。
     * 所以判据刻意只认 `Error:` / `unable to resolve` 这类**明确**的失败词，
     * 不认 "not started" —— 用宽松判据的结果是「用户点了一下，活动正常打开，
     * 日志却说失败」，那种日志比没有日志更坏。
     *
     * 这条把那个取舍写成断言，防止以后有人「顺手」把 "not started" 加进失败词。
     */
    @Test
    fun warningNotStartedIsDeliberatelyTreatedAsSuccess() {
        val ambiguous = "Warning: Activity not started, its current task has been brought to the front"
        assertFalse(ActivityRootCommand.outputIndicatesFailure(ambiguous))
        // 但明确的 Error: 前缀仍然是失败（两者可以共存于同一句话里）
        assertTrue(ActivityRootCommand.outputIndicatesFailure("Error: $ambiguous"))
    }

    @Test
    fun exitCodeZeroIsSuccess() {
        assertTrue(ActivityRootCommand.isSuccess(0))
        assertNull(ActivityRootCommand.explainExit(0))
    }

    /**
     * 非 0 退出码各有一句**可操作**的解释。
     *
     * 1 是最重要的一档：用户没授权 SystemUI（或忽略/拒绝了授权弹窗）时 `su` 返回 1，
     * 而这条日志是他唯一能看到的线索。所以它必须点名「去 KernelSU 授权 SystemUI」，
     * 而不是「su 失败」这种没有下一步的话。
     */
    @Test
    fun nonZeroExitCodesEachHaveActionableExplanation() {
        for (code in listOf(1, 126, 127, 137)) {
            val msg = ActivityRootCommand.explainExit(code)
            assertNotNull(msg, "退出码 $code 必须有解释")
            assertTrue(msg.isNotEmpty())
        }
        // 1 = 授权问题，必须点名 KernelSU 与授权对象
        val denied = ActivityRootCommand.explainExit(1)
        assertNotNull(denied)
        assertTrue("KernelSU" in denied, "退出码 1 的解释要点名 KernelSU，实际：$denied")
        assertTrue("com.android.systemui" in denied, "退出码 1 的解释要点名要授权的包名")
        assertFalse(ActivityRootCommand.isSuccess(1))
        assertFalse(ActivityRootCommand.isSuccess(127))
    }

    // ── 输出摘要 ──────────────────────────────────────────────────────

    @Test
    fun blankOutputSummarizesToEmptyString() {
        assertEquals("", ActivityRootCommand.summarizeOutput(""))
        assertEquals("", ActivityRootCommand.summarizeOutput("   \n  "))
    }

    @Test
    fun overlongOutputIsTruncated() {
        val long = "x".repeat(ActivityRootCommand.MAX_OUTPUT + 500)
        val out = ActivityRootCommand.summarizeOutput(long)
        assertTrue(out.length < long.length, "超长输出必须被截断，否则日志会被刷爆")
        assertTrue(out.endsWith("…（已截断）"))
    }

    @Test
    fun normalOutputIsPreservedVerbatim() {
        val line = "Starting: Intent { cmp=com.foo/.Bar flg=0x10000000 }"
        assertEquals(line, ActivityRootCommand.summarizeOutput("  $line\n"))
    }

    /**
     * `FLAG_NEW_TASK` 的值必须与 `Intent.FLAG_ACTIVITY_NEW_TASK` 一致。
     *
     * `core` 不能依赖 `android.*`（所以只能写字面量），但**写错了没有任何编译期检查** ——
     * 错一位就是一个「看起来对、行为怪」的 flag。这条用反射把它钉在 android 的常量上。
     *
     * 反射在纯 JVM 单测里跑不了（没有 android.jar 的运行时），所以这里只做**数值断言**
     * 并对证来源注释 —— 真正的一致性由真机验证覆盖（`dumpsys` 里看 flg）。
     */
    @Test
    fun flagNewTaskHasExpectedValue() {
        assertEquals(0x10000000, ActivityRootCommand.FLAG_NEW_TASK)
        // 十六进制里只有一位是 1，其余全 0 —— flag 值的形态正确
        assertEquals(1, Integer.bitCount(ActivityRootCommand.FLAG_NEW_TASK))
    }

    /**
     * 不变量：**只要 plan 非空，拼出来的命令就一定「形状正确」**——
     * 恰好一个 `/`、不以 `-` 开头、没有空白。
     *
     * 「不以 `-` 开头」这条是为了防止 `am start -n` 的参数被当成**另一个选项**：
     * 组件名的白名单本来已经禁掉了大多数，但这条不变量把它变成一个**结构性**保证，
     * 以后放宽校验时也不会漏。
     */
    @Test
    fun validPlansAlwaysProduceWellFormedCommand() {
        val cases = listOf(
            "com.foo" to "com.foo.Bar",
            "com.miui.voiceassist" to "com.xiaomi.voiceassistant.screenrecognition.ScreenRecognitionActivity",
            "com.miui.calculator" to ".cal.CalculatorActivity",
            "com.foo" to "com.foo.Outer\$Inner",
        )
        for ((pkg, act) in cases) {
            val plan = ActivityRootCommand.plan(pkg, act)
            assertNotNull(plan)
            val cmd = ActivityRootCommand.commandOf(plan)
            val component = cmd.substringAfter("start -n ")
            assertEquals(1, component.count { it == '/' }, "组件名里必须恰好一个 /：$component")
            assertFalse(component.startsWith("-"), "组件名不能以 - 开头：$component")
            for (c in component) {
                assertFalse(c.isWhitespace(), "组件名不能含空白：$component")
            }
        }
    }
}
