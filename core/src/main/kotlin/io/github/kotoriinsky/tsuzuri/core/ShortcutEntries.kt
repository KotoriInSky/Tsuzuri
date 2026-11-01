package io.github.kotoriinsky.tsuzuri.core

/**
 * 扇形小窗清单项的**分类**与「这条快捷方式项该不该保留」的判定 —— 纯逻辑。
 *
 * ## 为什么放在契约层
 *
 * 这段逻辑的错法全是**不报错、项悄悄消失**：用户在设置里手工填了一条快捷方式，
 * 扇形里永远没有它，日志里只有一句「丢弃 N 个失效快捷方式」——读不出是「真的失效」
 * 还是「我们没见过它」。判断依据又全是布尔量（宿主在不在、目录可不可用、目录里有没有），
 * 所以它必须能**脱离设备**跑单测（`core` 不允许依赖 `android.*`），
 * 测试见 `ShortcutEntriesTest`，整张规则表**逐格**钉住（还钉了一条「保留项只多不少」的不变量）。
 *
 * ## 清单的存储格式
 *
 * ```
 * com.tencent.mm                              ← 应用
 * com.tencent.mm/launch_type_scan_qrcode      ← 快捷方式（`包名/快捷方式 id`）
 * link:weixin://scanqrcode                    ← 链接（用户自己填的一段 URI，见 [LinkTargets]）
 * ```
 *
 * [SEP] 必须与 hook 侧 `FanShortcuts.SEP` 一致（两边都是 `/`）。包名按 Java 包名规范
 * 不含 `/`，所以**取第一个** [SEP] 拆分无歧义 —— 这正是这个格式当初被选中的理由：
 * 旧的纯包名配置**不需要迁移**，语义逐字不变。
 *
 * **链接项（第三类）**：前缀 `link:`（[LinkTargets.PREFIX]），存的是「前缀 + URI 本体」。
 * 判定必须**先认前缀、再按 `/` 拆** —— `link:weixin://scanqrcode` 里含 `/`，
 * 反过来拆会得到包名 `link:weixin:` 这种垃圾。第三类的语义见 [Kind.LINK] 与 [LinkTargets]，
 * 存量两类项的语义**一个字都没有变**（测试 `existingFormsAreNeverSeenAsLinks` 钉着）。
 *
 * ## 保留规则（[verdictOf]）
 *
 * | 宿主可启动 | 目录可用 | 目录里有 | 目录里启用 | 结果 | 与旧实现的关系 |
 * | --- | --- | --- | --- | --- | --- |
 * | 否 | 否 | — | — | 保留（[Verdict.KEEP_UNKNOWN]） | 一致（旧：留） |
 * | 否 | 是 | 是 | 是 | 保留（[Verdict.KEEP_IN_CATALOG]） | 一致（旧：留） |
 * | 否 | 是 | 是 | 否 | 丢弃 | 一致（旧：丢） |
 * | 否 | 是 | 否 | — | 丢弃 | 一致（旧：丢） |
 * | 是 | 否 | — | — | 保留（[Verdict.KEEP_UNKNOWN]） | 一致（旧：留） |
 * | 是 | 是 | 否 | — | 保留并标记自定义（[Verdict.KEEP_CUSTOM]） | **多留**（旧：丢） |
 * | 是 | 是 | 是 | 是 | 保留（[Verdict.KEEP_IN_CATALOG]） | 一致（旧：留） |
 * | 是 | 是 | 是 | 否 | 丢弃 | 一致（旧：丢） |
 *
 * ### 设计目标：保留项数只可能比旧实现**多**，不可能少
 *
 * 旧实现（hook 侧 `FanTargets.Pool.acceptsShortcut` 加「宿主不在可启动清单时排到最后、
 * 优先保住」那一支）的行为是：目录不可用 → 留；目录可用 → 只有「目录里有且启用」才留。
 * 上表与它**逐格**对照过，唯一多放行的一格是「宿主可启动 × 目录可用 × 目录里没有」。
 * 这条不变量有专门的用例钉着（`newRuleNeverKeepsFewerItemsThanTheOldOne`）：倒退回去的
 * 表现是用户的项又一次凭空消失，而那种错误在日志里只表现为一个变小的数字，肉眼看不出来。
 *
 * ### 为什么「宿主不可启动」不能直接丢
 *
 * 「不在可启动清单里」≠「没装」。hook 侧扫描有三条路会把**活着**的应用挡在清单外：
 * `loadLabel` 抛异常或返回空串（`if (label.isEmpty()) continue`）、桌面自己
 * （`if (pkg == home) continue`）、以及「发布了快捷方式但没有 launcher 入口」的应用。
 * 把它们的快捷方式一起丢掉，就是「我导入的项又不见了」这条 bug 换个入口重演。
 * 所以这一支只在**活目录给出证据**时才敢判死：目录可用却查不到它 —— 那说明包没了
 * （或快捷方式被应用撤回了），这是唯一能证明它死了的情形。
 *
 * ### 第 6 行的**代价**（写清楚，因为它是这套规则里唯一「可能留坏项」的一行）
 *
 * 「目录里没有也保留」意味着我们会画出可能点不动的图标：用户手工填的 id 打错了、
 * 那条快捷方式真的被应用撤回了、宿主应用暂时不可用 —— 这些情况在扇子这一层看不见。
 * 兜底在启动侧：`FanShortcuts.launch` 的三级降级链
 * （带小窗选项的 `startShortcut` → 不带 options 的 `startShortcut` → 启动应用本身），
 * 每一级都留日志，所以「点了没反应」在日志里能追到断在哪一级。
 *
 * 取舍的理由：**留一个点不动的图标，比让用户填过/勾过的项凭空消失要好**。后者用户
 * 没有任何办法自愈（他以为是自己填错了，重填一遍还是消失），而前者至少点一下会退化成
 * 启动宿主应用本身 —— 正是这次要修的 bug 的反面。
 */
object ShortcutEntries {

    /** 应用项与快捷方式项的分隔符（`包名/快捷方式 id`）。必须与 `FanShortcuts.SEP` 一致。 */
    const val SEP = '/'

    /** 一条清单项是什么。 */
    enum class Kind {
        /** 纯包名 = 应用。存量配置全是这种。 */
        APP,

        /** `包名/快捷方式 id` = 快捷方式。 */
        SHORTCUT,

        /**
         * `link:<uri>` = **链接项**（用户自己填的一段 URI / scheme）。本次新增的第三类。
         *
         * 它**不依赖任何应用发布快捷方式**：启动直接走 `Intent.parseUri` + `startActivity`
         * （`FanLinks.launch`），所以「微信扫一扫」（真实 scheme `weixin://scanqrcode`）
         * 不必等微信把它发布成快捷方式就能用。
         *
         * 与另外两类的三处不同（都影响调用方）：
         *  1. [Item.pkg] 是**空串**（链接不属于任何包），所以
         *     `catalogSupplementPackages` 那条「按包补查快捷方式目录」不会为它花一次 binder；
         *  2. 它**没有宿主应用**，因此启动失败时**没有「退化为启动应用本身」这一级**兜底
         *     （见 [LinkTargets] 与 `FanLinks.launch` 的降级链）；
         *  3. 它的合法性由 [LinkTargets.check] 判定（只放行隐式 + 带 scheme 的形式，
         *     这是安全收口，不是格式洁癖）。
         */
        LINK,

        /** 拆不出合法语义（空串、`/id`、`包名/`）。**唯一该丢的一类**。 */
        INVALID,
    }

    /**
     * 「这条快捷方式项该不该保留」的结论。
     *
     * 分成四种而不是「保留/丢弃」两种，是因为**保留的原因必须能进日志**：
     * 「目录里有的」「我们自己没见过、当自定义留下的」「目录不可用、没法验证的」
     * 是三种完全不同的信号 —— 用户报「我填的项没进扇子」时，看的就是这个分布。
     */
    enum class Verdict {
        /** 明确失效：**活目录**判它死了（里面查不到这条，或里面明确禁用）。 */
        DROP,

        /** 目录里**有**这条，且没被禁用。最正常的一类。 */
        KEEP_IN_CATALOG,

        /** 目录可用，但里面**没有**这条 —— 用户手工填的 / 系统还没枚举到的。本次新放行的一类。 */
        KEEP_CUSTOM,

        /** 目录**不可用**（枚举失败 / 权限被收回），我们不知道它还在不在，一律保留。 */
        KEEP_UNKNOWN;

        /**
         * 便捷判断：只有 [DROP] 是丢。
         *
         * 写在枚举里而不是让调用方写 `!= Verdict.DROP`：调用方那边用的是
         * `if (v.kept) picked.add(...) else dropped++`，多写几遍总有写反的一天，
         * 而**写反的代价是静默丢掉用户填的项** —— 正是这套规则要防的事。
         */
        val kept: Boolean get() = this != DROP
    }

    /**
     * 一条清单项拆开之后的样子。
     *
     * [raw] 原样保留：日志要打的正是它。拆坏的项（`/x`）尤其需要原样出现，
     * 打我们拆出来的碎片等于没打 —— 用户会照着日志里的东西去找，找不到同一个字符串。
     */
    class Item(
        val raw: String,
        /** 宿主包名。非法项可能是空串（`/x`）或一个不完整的包名（`com.tencent.mm/`）。 */
        val pkg: String,
        /** 非空 = 快捷方式，取值是 `ShortcutInfo.id`。 */
        val id: String?,
        val kind: Kind,
        /**
         * 非空 = 链接项，取值是**去掉前缀之后**的 URI（[LinkTargets.uriOf] 的结果，已 trim）。
         *
         * 与 [id] 分开而不是复用 `id`：两者的语义完全不同（一个是快捷方式 id 要去查目录，
         * 一个是 URI 直接交给 `Intent.parseUri`），共用一个字段迟早会被某一侧误用
         * —— 而误用的表现是「启动了一条奇怪的 intent」，不是编译错误。
         *
         * ⚠️ 它**不表示这条链接合法**：合法性归 [LinkTargets.check]（这里只管分类）。
         * hook 侧必须在启动前再过一次校验（配置可能被手改过），见 `FanLinks.launch`。
         */
        val linkUri: String? = null,
    )

    /**
     * 拆分并分类一条清单项。
     *
     * 判定分两步，**顺序不能反**：
     *
     *  1. **先认 `link:` 前缀**（[LinkTargets.isLink]）→ [Kind.LINK]。必须在前，
     *     因为 `link:weixin://scanqrcode` 里含 `/`，先按 `/` 拆会得到包名
     *     `link:weixin:`（一个永远查不到、永远点不动的垃圾项），而那一格的日志
     *     只会说「不可启动」——看不出是「用户填的链接被拆坏了」；
     *  2. 再按**第一个** [SEP] 的位置判前两类：
     *
     *  - 没有 [SEP] → 应用（空串除外，那是设置界面写坏了的残留）；
     *  - [SEP] 在首位（`/x`，没有包名）或末位（`com.tencent.mm/`，没有 id）→ 非法：
     *    两者都点不动，保留它们只会多画一个永远失败的图标。**但包名前缀照样取出来**
     *    （[pkg]），因为「用户提过这个包」这个信息在补查快捷方式目录时还有用；
     *  - 其余 → 快捷方式，id 取第一个 [SEP] 之后**全部**内容（`com.foo/a/b` 的 id 是 `a/b`，
     *    快捷方式 id 里出现 `/` 并不违反任何约定，切成最后一段反而会把合法项判废）。
     *
     * 链接项的 [Item.pkg] 是**空串**（没有宿主），[Item.linkUri] 非空。
     */
    fun parse(raw: String): Item {
        // ① 链接项优先：前缀判定必须在「按 `/` 拆」之前（见上面的 KDoc 第 1 条）
        if (LinkTargets.isLink(raw)) {
            return Item(raw, "", null, Kind.LINK, LinkTargets.uriOf(raw))
        }
        val i = raw.indexOf(SEP)
        if (i < 0) {
            return if (raw.isEmpty()) {
                Item(raw, "", null, Kind.INVALID)
            } else {
                Item(raw, raw, null, Kind.APP)
            }
        }
        if (i == 0 || i == raw.length - 1) {
            return Item(raw, raw.substring(0, i), null, Kind.INVALID)
        }
        return Item(raw, raw.substring(0, i), raw.substring(i + 1), Kind.SHORTCUT)
    }

    /**
     * 一条清单项提到的**包名**（应用项就是它自己，快捷方式项取 `/` 之前）。
     *
     * 用途只有一个：决定要不要为这个包**补一次**快捷方式查询（`catalogSupplementPackages`）。
     * 全局查询看不到「动态且没被调用方固定过」的快捷方式（设备实测：微信那三条），
     * 只对「用户提过的包」补查，才不会变成全机几百个包 × 一次 binder 的无意义开销。
     *
     * 非法项返回空串由**调用方**跳过（`com.tencent.mm/` 这种仍会返回包名 —— 用户提过它）。
     * **链接项也返回空串**（[Kind.LINK] 没有宿主包）：它不是「用户提过的包」，
     * 所以不该为它补查目录 —— 补了也只会浪费一次 binder。
     */
    fun packageOf(raw: String): String = parse(raw).pkg

    /**
     * 这条快捷方式项该不该保留。四个输入的语义与整张表见类注释，这里只记**取值的坑**：
     *
     * @param hostLaunchable 宿主应用的包在**已安装的可启动清单**里吗。注意它不是「装了」：
     *   一个没有启动入口（图标）的应用也会是 `false`，而它的快捷方式是好的 ——
     *   所以 `false` 只表示「不能凭这一项断定它还活着」，**不足以判死**；
     *   真正的判死依据只有活目录（见下一段）。它现在的唯一用途是区分两种「目录可用但
     *   查不到」：宿主可启动 → 用户自定义（保留），宿主不可启动 → 包没了（丢弃）。
     * @param catalogOk 这次快捷方式目录枚举成功了吗。
     * @param inCatalog 目录里有这一条吗。**`catalogOk=false` 时它是没有意义的值**
     *   （目录是空的，查出来必然是「没有」），不能因此判失效 —— 那些分支先短路。
     * @param catalogEnabled 目录里那一条启用了吗；`null` = 拿不到状态。
     *   `null` 按**保留**处理：丢一条活的比留一条死的更糟（与「目录不可用」同一条理由）。
     */
    fun verdictOf(
        hostLaunchable: Boolean,
        catalogOk: Boolean,
        inCatalog: Boolean,
        catalogEnabled: Boolean?,
    ): Verdict {
        if (!hostLaunchable) {
            // 不能直接丢（详见类注释「为什么宿主不可启动不能直接丢」）：只有**活目录**
            // 给出的证据才作数。目录不可用 → 什么都不知道，绝不丢
            return when {
                catalogOk && inCatalog && catalogEnabled != false -> Verdict.KEEP_IN_CATALOG
                // 活目录里查不到它 → 包没了 / 快捷方式被撤回了，这是唯一能判死的情形
                catalogOk -> Verdict.DROP
                else -> Verdict.KEEP_UNKNOWN
            }
        }
        if (!catalogOk) return Verdict.KEEP_UNKNOWN
        // 宿主活着，只是目录里没有它 → 用户手工填的 / 系统还没枚举到的，放行
        if (!inCatalog) return Verdict.KEEP_CUSTOM
        return if (catalogEnabled == false) Verdict.DROP else Verdict.KEEP_IN_CATALOG
    }
}
