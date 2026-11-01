package io.github.kotoriinsky.tsuzuri.core

/**
 * 清单里的**活动项**（用户手工填的「包名 + 活动全名」）—— 格式校验、规范化、显示名。**纯字符串逻辑**。
 *
 * ## 它解决的是什么问题
 *
 * 用户填 `com.tencent.mm/com.tencent.mm.ui.LauncherUI` 想直接呼出某个页面，
 * 结果只打开了应用本身。根因写在 [ShortcutEntries] 的类注释：
 * 裸 `包名/活动名` 与 `包名/快捷方式id` **在字符串层面无法区分**，
 * 于是活动名被当成快捷方式 id 查了一遍目录、查不到、静默退化成「启动应用本身」。
 *
 * **本对象不改变那个判定**（分类仍归 [ShortcutEntries]），它只回答一件事：
 * **「这串 id 够不够格被当成活动名去试一次 `setClassName`？」**
 *
 * ## 为什么需要「够不够格」这一层（而不是拿到什么都试）
 *
 * 真正的判据在**启动那一刻**（`LauncherApps.startShortcut` 抛
 * `ActivityNotFoundException` = 「这不是一条真快捷方式」），那是不需要猜的权威证据
 * 但启动前加一道**纯格式**的门仍然值得：
 *
 *  - 「试一次 `setClassName`」的代价是一次跨进程的启动尝试 —— 对明显不是类名的 id
 *    （`launch_type_scan_qrcode` 这种全小写带下划线的快捷方式 id）白跑一次是浪费；
 *  - 更重要的是**日志质量**：判为「够格但启动失败」与「压根不像活动名」是两种
 *    完全不同的结论，用户看到的东西也不同（前者说「组件不存在/不允许启动」，
 *    后者说「这条不是活动，也不是快捷方式，检查拼写」）。
 *
 * ⚠️ **本对象放行 ≠ 一定能启动**。它只做格式判断，不查任何目录、不查 `exported`
 * （设置界面查不到别的应用的完整活动清单）。
 * 「找不到这个组件」「这个组件不允许被启动」都只能在启动时刻才知道，
 * 由 `FanShortcuts` 的启动分级把它说清楚。
 *
 * ## 三条纪律（与 [LinkTargets] 同构）
 *
 *  1. **拿不准就拒绝**：放行一个不像活动名的串，代价是白跑一次启动 + 一条误导日志；
 *     拒绝一个真活动名，代价是用户看到一句「格式不对」并且改得回来。后者可自愈。
 *  2. **拒绝必须带原因**：每种原因映射成一句人话（界面报错 / 日志），
 *     合成一句「格式不对」等于把「为什么」藏起来。
 *  3. **只做 trim，不重编码**：类名里的 `$`（内部类）与 `.` 都是语义字符，改一个就坏了。
 *
 * ## 与 `activity:` 前缀的关系（**前缀是可选写法，不是必须**）
 *
 * 启动侧的自动识别（`startShortcut` 失败后试 `setClassName`）**不需要前缀**，
 * 所以存量裸活动名不加前缀就能生效。前缀的价值只有一个：**更早表态**——
 * 带前缀的项在解析期就被判为 [ShortcutEntries.Kind.ACTIVITY]，
 * 日志里一眼能看出用户意图，不必先失败一次再推断。
 *
 * 代价对比（写给以后想删掉前缀的人）：
 *
 * | | 裸活动名 | `activity:` 前缀 |
 * | --- | --- | --- |
 * | 存量配置 | 直接生效 | 需手改 |
 * | 日志可读性 | 靠「先失败再识别」推断 | 解析期即明确 |
 * | 误判风险 | 有（见 [looksLikeActivity]） | 无（用户显式声明） |
 *
 * 两条路**并存**，互不冲突：前缀优先，裸的走启动侧识别。这是刻意的取舍，
 * 不是「两套没收拾干净」。
 */
object ActivityTargets {

    /**
     * 清单项的前缀。**大小写不敏感地识别**，但规范化输出一律小写。
     *
     * 与 [LinkTargets.PREFIX] 同一条理由：`:` 不可能出现在合法 Java 包名里，
     * 所以任何**合法**的存量项都不会因为这个前缀被误判（`ShortcutEntriesTest`
     * 的 `existingFormsAreNeverSeenAsActivities` 钉着这条）。
     * 大小写敏感的话，用户手改配置写成 `Activity:com.a/.B` 会被当成
     * 「一个叫 `Activity:com.a` 的包」而丢掉，日志里只有一句「不可启动」。
     */
    const val PREFIX = "activity:"

    /** 包名与类名允许的字符（Java 标识符 + `.` 与内部类的 `$`）。 */
    private const val NAME_EXTRA = "_$"

    /** 活动全名长度上限。配置项不该这么长，超过只会把配置文件撑爆。 */
    const val MAX_LENGTH = 512

    /**
     * 拒绝的原因。**每一种都要能单独给用户一句人话**（见 [LinkTargets.Rejection] 的同款理由）。
     */
    enum class Rejection {
        /** 前缀之后什么都没有（`activity:` 或只有空白）。 */
        EMPTY,

        /** 没有 `/`，分不出包名与活动名（`com.foo.Bar`）。 */
        NO_SEPARATOR,

        /** `/` 在首位（`/com.foo.Bar`）或末位（`com.foo/`）。 */
        EMPTY_PART,

        /** 包名不合法（段为空、以数字开头、含非法字符）。 */
        BAD_PACKAGE,

        /** 活动名不合法（不含 `.`、段为空、以数字开头、含非法字符）。 */
        BAD_ACTIVITY,

        /** 活动名里出现 `/`（`com.foo/a/b`）—— `am start -n` 的分隔符，多一个就改变语义。 */
        EXTRA_SEPARATOR,

        /** 超过 [MAX_LENGTH]。 */
        TOO_LONG,
    }

    /** 一次校验的结果。 */
    sealed interface Result {
        /**
         * 通过。[packageName] 与 [activityName] 都已 trim，可直接交给
         * `Intent().setClassName(pkg, act)`。
         */
        data class Ok(val packageName: String, val activityName: String) : Result

        data class Rejected(val reason: Rejection) : Result
    }

    /**
     * 这条清单项是**显式声明**的活动项吗（即带 `activity:` 前缀）。
     *
     * **只认整串开头的那个前缀**（与 [LinkTargets.isLink] 同款）：
     * `com.foo/activity:bar` 的快捷方式 id 里出现 `activity:` 不算 —— 它是快捷方式项，
     * 前缀是**整串**的属性，不是子串的属性。
     */
    fun isActivity(raw: String): Boolean =
        raw.length >= PREFIX.length &&
            raw.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)

    /**
     * 清单项里那串「包名/活动名」（去掉前缀并 trim）。
     * **不做合法性判断** —— 非法项也要能原样拿出来打日志。
     *
     * ⚠️ 前缀识别发生在 **trim 之前**：`isActivity("  activity:x")` 是 `false`
     * （前缀是**整串开头**的属性，首字符是空格就不是开头）。
     * 于是带前导空白的输入会走裸形式分支、trim 后仍带着字面量 `activity:`，
     * 这个冒号过不了 `isValidPackage`，用户看到的是「包名不合法」——
     * 一个和真实原因（多了个空格）毫不相干的提示。
     *
     * 所以这里**先 trim 再判前缀**。这与 [isActivity] 的语义不冲突：
     * [isActivity] 回答的是「这条项自称是活动吗」（给分类用，严格），
     * 而 [targetOf] 回答的是「把它拆开长什么样」（给解析用，宽容）——
     * 宽容在这里没有代价：trim 掉的只是空白，不会把一个快捷方式项
     * 变成活动项（前缀仍然是整串开头的字面量）。
     */
    fun targetOf(raw: String): String {
        val trimmed = raw.trim()
        return if (isActivity(trimmed)) trimmed.substring(PREFIX.length).trim() else trimmed
    }

    /**
     * 规范化后的**存储形式**（`activity:` + `包名/活动名`）。
     *
     * 只 trim，不做别的加工（见类注释第 3 条纪律）。
     */
    fun stored(packageName: String, activityName: String): String =
        PREFIX + packageName.trim() + ShortcutEntries.SEP + activityName.trim()

    /**
     * 校验一段**裸的**「包名/活动名」（不带前缀）。`null` = 通过。
     *
     * 纯字符串实现：`core` 不许依赖 `android.*`，而这段逻辑的错法全是
     * 「静默放行一个点不动的项」，所以它必须能脱离设备单测
     * （测试见 `ActivityTargetsTest`，逐格钉住下面每一条判据）。
     */
    fun check(target: String): Rejection? {
        // 先 trim 再判空：`"   "` 的**真实原因**是「什么都没填」，
        // 而它字符数不是 0 —— 不 trim 就会掉到下面判 NO_SEPARATOR，
        // 用户看到「分不出包名与活动名」，与他实际犯的错（多打了个空格）无关。
        // （这条是被 `emptyAndMissingSeparatorAreRejected` 抓出来的真 bug）
        val body = target.trim()
        if (body.isEmpty()) return Rejection.EMPTY
        if (target.length > MAX_LENGTH) return Rejection.TOO_LONG

        val sep = body.indexOf(ShortcutEntries.SEP)
        if (sep < 0) return Rejection.NO_SEPARATOR

        val pkg = body.substring(0, sep)
        val act = body.substring(sep + 1)

        if (pkg.isEmpty() || act.isEmpty()) return Rejection.EMPTY_PART

        // 活动名里再有 `/`：`setClassName` 只吃「包名 + 单个类名」，
        // 多一段就会启动到一个语义不明的地方（而日志里看不出来）
        if (act.indexOf(ShortcutEntries.SEP) >= 0) return Rejection.EXTRA_SEPARATOR

        // 空白与控制字符：类名里不该有（换行/制表符是拼接注入的典型形态）
        // 扫的是 trim 后的 body：首尾空白已经被上面吃掉了，留在这里扫
        // 会把「用户复制时多带了个换行」误判成「活动名非法」
        for (c in body) {
            if (c.code <= 0x20 || c.code == 0x7f) return Rejection.BAD_ACTIVITY
        }

        if (!isValidPackage(pkg)) return Rejection.BAD_PACKAGE
        if (!isValidClassName(act)) return Rejection.BAD_ACTIVITY

        return null
    }

    /**
     * 校验一条**清单项**（可能是带前缀的，也可能是裸的）。
     *
     * 非 `activity:` 开头的输入**照样按裸形式校验** —— 这正是「不加前缀也能生效」
     * 那条路要用到的能力：调用方拿到的可能是 [ShortcutEntries] 判为
     * `KEEP_CUSTOM` 的快捷方式项，它到底是不是活动名，只有这里能回答。
     */
    fun parse(raw: String): Result {
        val target = targetOf(raw)
        val bad = check(target)
        if (bad != null) return Result.Rejected(bad)
        val sep = target.indexOf(ShortcutEntries.SEP)
        return Result.Ok(target.substring(0, sep), target.substring(sep + 1))
    }

    /**
     * 「这串 id 像不像一个活动全名」—— **自动识别的判据**。
     *
     * 判据只有两条，都直接来自 Java 语言规范：
     *
     *  1. **必须含 `.`**：活动类名几乎总是包限定名（`com.foo.Bar`），
     *     而快捷方式 id 是应用自定义的短串（`launch_type_scan_qrcode`、`scan`）。
     *     这条把绝大多数快捷方式 id 挡在外面，且**零误伤**——
     *     Android 允许活动类名不带包（manifest 里 `android:name=".MainActivity"`
     *     展开后**一定**是包限定名），所以「合法的活动全名必然含 `.`」成立。
     *  2. **至少有一段以大写字母开头**（类名的通行约定）。
     *
     * ⚠️ **这一条是启发式，会误判**（诚实写在文档里）：某个应用的快捷方式 id
     * 恰好长成 `a.B` 的形状时会被当成活动名去试。代价是白跑一次 `setClassName`，
     * 然后落到「组件不存在」的日志 —— **不会静默做错事**，因为启动成功与否会说话。
     * 这正是它敢用启发式、而 [ShortcutEntries] 的分类不敢用启发式的原因：
     * 分类错了会静默丢掉用户的项（不可自愈），这里是启动期的一次尝试（可自愈）。
     *
     * @return true = 值得试一次 `setClassName`；false = 明显不是，不必白跑
     */
    fun looksLikeActivity(id: String): Boolean {
        if (id.indexOf('.') < 0) return false
        if (check("x" + ShortcutEntries.SEP + id) != null) return false
        // 至少一段以大写开头（`com.foo.Bar` → `foo` 小写、`Bar` 大写）
        return id.split('.').any { seg -> seg.isNotEmpty() && seg[0].isUpperAscii() }
    }

    /**
     * 显示名兜底。活动全名太长（`com.tencent.mm.plugin.scanner.ui.BaseScanUI`），
     * 面板上放不下，退回**最后一段**（`BaseScanUI`）。
     *
     * 这只是**兜底**：用户能起别名（`DisplayNameOverrides`），
     * 那个优先级更高。不给兜底的话，面板上会出现一个超长的截断名字。
     */
    fun fallbackLabel(activityName: String): String {
        val trimmed = activityName.trim()
        if (trimmed.isEmpty()) return trimmed
        val last = trimmed.substringAfterLast('.')
        return last.ifEmpty { trimmed }
    }

    /** Java 包名：`.` 分段，每段非空、不以数字开头、只含标识符字符。 */
    private fun isValidPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        for (seg in pkg.split('.')) {
            if (seg.isEmpty()) return false
            if (seg[0].isDigitAscii()) return false
            for (c in seg) if (!c.isIdentifierChar()) return false
        }
        return true
    }

    /**
     * Java 类名（包限定或**带前导 `.` 的简写**）：`.` 分段，除允许的**前导空段**外
     * 每段非空、不以数字开头、只含标识符字符。
     *
     * **要求含 `.`**：不带包的活动名在 manifest 里合法（会展开成包限定名），
     * 但用户在配置里只写简单名我们**解析不出包**（活动名不知道它属于哪个包）——
     * 那不是一条能启动的项。所以这里要求包限定形式，拒绝简单名。
     *
     * ## 为什么允许前导 `.`（这条是真机数据逼出来的）
     *
     * `android:name=".MainActivity"` 是 manifest 里**最常见的写法**，表示「本包下的这个类」。
     * 于是 `adb shell cmd package resolve-activity --brief <pkg>` 吐出来的就是这种简写：
     *
     * ```
     * com.coolapk.market    → com.coolapk.market/.view.main.MainActivity
     * com.miui.calculator   → com.miui.calculator/.cal.CalculatorActivity
     * ```
     *
     * 用户查活动名时看到的、能直接复制的，正是**这个形态**。
     * 而 `Intent.setClassName(pkg, ".view.main.MainActivity")` 是**合法且正确**的
     * —— 系统会按标准规则把前导 `.` 展开成包名。所以这一条必须放行：
     * 拦掉它等于「用户照着系统告诉他的格式填，却被说格式不对」，
     * 而那种拒绝**没有任何自愈路径**（用户不知道该改什么）。
     *
     * ⚠️ 这条是被 `ActivityTargetsRealDeviceTest` 抓出来的：构造用例里我只写了
     * 全限定名（`com.foo.Bar`），漏掉了这个**真机最常见的形态**。
     *
     * 只允许**最前面**那一个空段（`/.Foo`）。中间的空段（`com..Bar`）仍然拒绝 ——
     * 那是真的写错了，且展开规则也救不了它。
     */
    private fun isValidClassName(act: String): Boolean {
        if (act.isEmpty()) return false
        if (act.indexOf('.') < 0) return false
        val segs = act.split('.')
        // 前导 `.` 会在最前面产生一个空段（`".Foo".split('.')` → ["", "Foo"]）——
        // 只放过它，其余空段照旧拒绝
        val start = if (segs.isNotEmpty() && segs[0].isEmpty()) 1 else 0
        if (start == 1 && segs.size == 1) return false // 就是一个孤立的 "."
        for (i in start until segs.size) {
            val seg = segs[i]
            if (seg.isEmpty()) return false
            if (seg[0].isDigitAscii()) return false
            for (c in seg) if (!c.isIdentifierChar()) return false
        }
        return true
    }

    // ── 字符判据（刻意不用 Char.isLetter()：那是 Unicode 感知的，
    //    而 Java 标识符的**合法集合由语言规范定义**，含中文的串不是合法类名）──

    private fun Char.isLetterAscii(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isDigitAscii(): Boolean = this in '0'..'9'

    private fun Char.isUpperAscii(): Boolean = this in 'A'..'Z'

    /** Java 标识符字符：ASCII 字母、数字、`_`、`$`。 */
    private fun Char.isIdentifierChar(): Boolean = isLetterAscii() || isDigitAscii() || this in NAME_EXTRA
}
