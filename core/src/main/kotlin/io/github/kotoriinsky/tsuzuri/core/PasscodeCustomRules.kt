package io.github.kotoriinsky.tsuzuri.core

/**
 * 自定义口令规则（2026-09-25 用户需求：导入/导出自定义规则）。
 *
 * ## 职责边界
 *
 * 这里只有三件事：**数据结构**、**行式编解码**（[encode]/[decode]）、**匹配**
 * （[matchFirst]）。JSON 读写不在这里 —— `:core` 是纯 JVM 模块、没有 `org.json`，
 * 导入导出时的 JSON 转换由 `:ui` 完成（那里运行时自带）。
 *
 * ## 存储格式：行式而不是 JSON
 *
 * 一条规则一行、字段用 `\u001F`（ASCII 单元分隔符）隔开，整体作为**一个**
 * `rawString` 存进 prefs（键 `passcode_island.custom_rules`）。选行式的原因：
 *  - 原子读写一个字符串 = 「全量替换」语义清晰，没有「改一半」状态；
 *  - prefs 的 StringSet **不保序**（SideIndex.kt:1972 的教训），而规则的先后
 *    就是匹配优先级，必须保序；
 *  - 正则里出现 `\u001F` 的概率为零，不需要转义层。
 *
 * ## 匹配语义
 *
 * 自定义规则**先于**内置规则（[PasscodeRules.match]）：用户显式写下的规则
 * 就是要它优先生效（与 HyperCopy 的 first-match 语义一致）。同批自定义规则
 * 按列表顺序 first-match-wins —— 列表顺序即优先级，导入时保持文件顺序。
 */
object PasscodeCustomRules {

    /** 字段分隔符：ASCII 单元分隔符，正常文本不会出现。 */
    private const val SEP = "\u001F"

    /** 编码版本（将来字段变更多时用于拒绝/迁移）。 */
    private const val FORMAT_VERSION = 1

    /** 单个 prefs 键里存的最大字符数：几十条规则绰绰有余，防异常导入撑爆 prefs。 */
    const val MAX_ENCODED_LEN = 64_000

    /** 规则条数上限：超过的导入时拒绝（不是静默截断 —— 丢规则不能没有声音）。 */
    const val MAX_RULES = 50

    /**
     * 一条自定义规则。
     *
     *  - [regex]/[anchored]：命中判据。anchored = true 时对 trim 后的**整段**文本
     *    match（等价内置的「裸口令」锚定），false 时全文 find（等价「全文搜」）；
     *  - [target]：跳转目标模板。`$1`..`$9` 引用捕获组，`$0` = 整段命中文本；
     *    模板里没有组引用时原样输出（如 `taobao://`）；
     *  - [category]：展示模板类别，只允许使用内置类别；
     *  - [platformName]/[platformPkg]/[accent]：平台元数据，全空 = 用兜底（label + 兜底蓝）；
     *  - [coreGroup]：核心内容取第几个捕获组（1-based；null = 没有核心内容，
     *    展示侧回落「打开XX」/预加载标题）。
     */
    data class Rule(
        val id: String,
        val label: String,
        val regex: String,
        val anchored: Boolean,
        val target: String,
        val platformName: String? = null,
        val platformPkg: String? = null,
        val accent: String? = null,
        val coreGroup: Int? = null,
        val category: PasscodeIsland.ContentCategory = PasscodeIsland.ContentCategory.IMAGE_TEXT,
        val enabled: Boolean = true,
    )

    // ── 编解码 ────────────────────────────────────────────────────────

    /**
     * 编码为行式存储。字段为 11 列，顺序即格式契约：
     * `id | label | anchored | regex | target | platformName | platformPkg | accent | coreGroup | category | enabled`
     * 可空字段空串表示。
     *
     * 两种情况返回 null（调用方报错，不静默截断 —— 丢规则不能没有声音）：
     * 超出 [MAX_ENCODED_LEN] / 超过 [MAX_RULES] / **任何字段含分隔符或换行**
     * （分隔符注入会把一行切成错位的两行，宁可整体拒绝也不能让切列后的
     * 乱码正则进入匹配热路径）。
     */
    fun encode(rules: List<Rule>): String? {
        if (rules.isEmpty()) return ""
        if (rules.size > MAX_RULES) return null
        val sb = StringBuilder()
        sb.append(FORMAT_VERSION).append('\n')
        for (r in rules) {
            val fields = listOf(
                r.id, r.label, if (r.anchored) "1" else "0", r.regex, r.target,
                r.platformName.orEmpty(), r.platformPkg.orEmpty(), r.accent.orEmpty(),
                r.coreGroup?.toString().orEmpty(),
                r.category.wireName,
                if (r.enabled) "1" else "0",
            )
            if (fields.any { it.contains(SEP) || it.contains('\n') || it.contains('\r') }) return null
            sb.append(fields.joinToString(SEP)).append('\n')
        }
        if (sb.length > MAX_ENCODED_LEN) return null
        return sb.toString()
    }

    /**
     * 从行式存储解码。**逐行容错**：坏行（列数不足 / 正则编不过 / 版本头不认）
     * 跳过不抛 —— 存储里一条坏规则不应该连坐整批（内置引擎「命中即用、失败即跳」
     * 的同一哲学）；全部坏 → 空列表 = 没有自定义规则。
     */
    fun decode(raw: String?): List<Rule> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        val out = ArrayList<Rule>(lines.size)
        var sawHeader = false
        for ((idx, line) in lines.withIndex()) {
            val l = line.trim('\r')
            if (l.isEmpty()) continue
            if (idx == 0 && l == FORMAT_VERSION.toString()) {
                sawHeader = true
                continue
            }
            val f = l.split(SEP)
            // 旧版本是 9 列，随后追加 category（10 列）和 enabled（11 列）。
            // 多列仍视为注入产物，坏行不能影响其他规则。
            if (f.size != 9 && f.size != 10 && f.size != 11) continue
            val anchored = f[2] == "1"
            val coreGroup = f[8].toIntOrNull()
            // 正则编不过的规则在解码期就丢弃（同时预热编译缓存，第一次复制不用再编）
            RegexGuard.compile(f[3]) ?: continue
            out.add(
                Rule(
                    id = f[0], label = f[1], anchored = anchored,
                    regex = f[3], target = f[4],
                    platformName = f[5].ifEmpty { null },
                    platformPkg = f[6].ifEmpty { null },
                    accent = f[7].ifEmpty { null },
                    coreGroup = coreGroup,
                    category = PasscodeIsland.ContentCategory.fromWire(f.getOrNull(9))
                        ?: PasscodeIsland.ContentCategory.IMAGE_TEXT,
                    enabled = f.getOrNull(10)?.let { it != "0" } ?: true,
                ),
            )
        }
        // 无版本头的旧数据也能读（列契约不变时兼容；变了再靠 header 区分）
        if (!sawHeader && out.isEmpty()) return emptyList()
        return out
    }

    // ── 匹配 ─────────────────────────────────────────────────────────

    /** 模板里的 `$N` 组引用。 */
    private val GROUP_REF = Regex("""\$(\d)""")

    /**
     * 展开目标模板：`$1`..`$9` = 捕获组（缺失组 = 空串），`$0` = 整段命中文本。
     * 模板没有组引用时原样返回（如 `taobao://`）。
     */
    fun expandTarget(template: String, m: MatchResult): String =
        if (!template.contains('$')) template
        else GROUP_REF.replace(template) { ref ->
            m.groupValues.getOrNull(ref.groupValues[1].toInt()).orEmpty()
        }

    /** 单条规则的匹配时限。正常规则在 2000 字以内是微秒级，50ms 只会卡住回溯失控的写法。 */
    const val MATCH_TIMEOUT_MS = 50L

    /**
     * 用自定义规则匹配一段文本，返回第一条命中的结果。
     *
     * 单条规则编不过 / 匹配抛异常 / 超时都按「没命中」处理，继续试下一条 ——
     * 正则是用户输入，灾难性回溯不能卡死剪贴板观察线程（见 [RegexGuard]）。
     * 超时会回调 [onTimeout]，让调用方记日志；不然用户只会看到「这条规则不生效」。
     */
    fun matchFirst(
        text: String,
        rules: List<Rule>,
        timeoutMs: Long = MATCH_TIMEOUT_MS,
        onTimeout: (Rule) -> Unit = {},
    ): PasscodeRules.Match? {
        val t = text.trim()
        for (r in rules) {
            if (!r.enabled) continue
            val re = RegexGuard.compile(r.regex) ?: continue
            val m = try {
                RegexGuard.match(re, t, r.anchored, timeoutMs)
            } catch (_: RegexGuard.Timeout) {
                onTimeout(r)
                null
            } catch (_: Throwable) {
                null
            } ?: continue
            val platform = if (r.platformName == null && r.platformPkg == null && r.accent == null) {
                PasscodeRules.Platform(
                    r.label,
                    null,
                    PasscodeIslandDefaults.FALLBACK_ACCENT,
                    r.category,
                )
            } else {
                PasscodeRules.Platform(
                    r.platformName ?: r.label,
                    r.platformPkg,
                    r.accent ?: PasscodeIslandDefaults.FALLBACK_ACCENT,
                    r.category,
                )
            }
            return PasscodeRules.Match(
                ruleId = "custom:${r.id}",
                label = r.label,
                target = expandTarget(r.target, m),
                platform = platform,
                core = r.coreGroup?.let { m.groupValues.getOrNull(it) }?.takeIf { it.isNotEmpty() },
                category = r.category,
            )
        }
        return null
    }
}

/** 兜底强调色 —— 展示侧（Presenter）也用同一个值，常量放这保证两边不漂移。 */
object PasscodeIslandDefaults {
    const val FALLBACK_ACCENT = "#5B7CFF"
}
