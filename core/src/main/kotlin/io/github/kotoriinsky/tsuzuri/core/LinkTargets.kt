package io.github.kotoriinsky.tsuzuri.core

/**
 * 清单里的**链接项**（用户自己填的一段 URI / scheme）—— 校验、规范化、显示名。**纯字符串逻辑**。
 *
 * ## 为什么要有第三类项
 *
 * 前两类项（纯包名 = 应用、`包名/快捷方式 id` = 快捷方式）都要**依赖宿主应用发布了那条快捷方式**
 * 才能工作：启动走 `LauncherApps.startShortcut`，而「微信扫一扫」这种入口在本 ROM 上
 * 只是**动态且未被固定**的快捷方式，枚举时极易看不到（见 `ShortcutEntries` 的类注释与
 * `FanShortcuts.MATCH_FLAGS` 那段取证）。而它真实的 scheme **一直存在**：
 * `weixin://scanqrcode` —— 只要 `Intent.parseUri` + `startActivity` 就能起来。
 *
 * 所以第三类项存的是**链接本身**，不依赖任何应用是否发布了快捷方式。
 * 参考做法见报告里 Anywhere-（`com.absinthe.anywhere_`）的取证：它同样让用户填一段
 * URI（`SchemeEditorFragment` 的 `tiet_url_scheme`），启动时 `Intent.parseUri(str, 0)` +
 * `startActivity`（`pg1.a` / `kp0`）——**它只校验「非空」**，能不能起来全看启动那一刻。
 * 我们比它严：见下面的「安全收口」。
 *
 * ## 存储形式：`link:<uri>`（前缀）
 *
 * ```
 * com.tencent.mm                              ← 应用（存量格式，语义不变）
 * com.tencent.mm/launch_type_scan_qrcode      ← 快捷方式（存量格式，语义不变）
 * link:weixin://scanqrcode                    ← 链接（本次新增）
 * ```
 *
 * 为什么不另开一个有序清单键（例如 `links`）：扇子里**只有一份顺序**，每一项的位置由序号
 * 算出来（`FanMetrics.place(index)`）。两份清单表达不了「链接插在微信与支付宝之间」这种
 * 交错顺序，用户拖动时也就没法把链接挪到应用前面去。而多一份键就是多一条写入路径 ——
 * 这个项目已经在「同一个清单两处各写一遍」上吃过一次事故（见 `FanItems` 的类注释）。
 *
 * 前缀为什么能无歧义：Java 包名规范**不可能含 `:`**，所以「以 `link:` 开头」不可能是
 * 存量格式的合法项。判定顺序上有一处必须小心：`link:weixin://scanqrcode` **含 `/`**，
 * 所以 [ShortcutEntries.parse] 必须先认前缀、再按 `/` 拆 —— 反过来会拆出
 * 包名 `link:weixin:` 这种垃圾（见那边的注释与测试）。
 *
 * ## 安全收口（这一段是这段代码存在的**主要**理由，改之前先读完）
 *
 * 从系统界面进程 `startActivity` 一段**用户填的任意 URI**，等于给配置文件开了一个
 * **任意启动口子**：谁改动了配置（备份恢复、同步、手改、别的模块），谁就能让 SystemUI
 * 去启动任意组件 —— 而 SystemUI 是 uid 1000 的特权进程，它发起的启动会带着
 * 「SystemUI 申请的权限」去解析目标。所以这一层的规矩是**只接受隐式、且必须带 scheme**：
 *
 * | 形式 | 例子 | 处置 | 理由 |
 * | --- | --- | --- | --- |
 * | 隐式 + scheme | `weixin://scanqrcode` | **放行** | 就是本特性要的东西 |
 * | 显式组件 | `intent://x#Intent;component=com.evil/.A;end` | **拒绝** | 它点名了要启动哪个组件，等于任意启动口子 |
 * | 带 selector / 包名 | `;S.` / `;i.` / `component=` / `package=` / `launchFlags=` | **拒绝** | 同上（`Intent.parseUri` 会读这些键） |
 * | `intent:` 包裹 | `intent:weixin://x` | **拒绝** | 同上（它是显式形式的外壳） |
 * | 无 scheme | `com.android.settings/.X`、`//host/path` | **拒绝** | 没有 scheme 的东西解析出来就是显式组件 |
 * | 本地/非应用 scheme | `file:` / `data:` / `javascript:` / `blob:` / `android-app:` | **拒绝** | 前四个不是「启动一个应用」，最后一个等于 `package=` |
 * | 含空白/控制字符 | `weixin://a b` | **拒绝** | 非法 URI（要空格得百分号编码），且是拼接注入的常见形态 |
 * | 太短/太长 | `weixin:`、`weixin://`、> 1024 字符 | **拒绝** | 前者点不动，后者只会把配置撑爆 |
 *
 * **拒绝必须是「当场说」**：界面侧用它给用户报错（`LinkImportScreen`），hook 侧用它拒绝启动
 * 并打日志（`FanLinks.launch`）。任何「写进去之后再静默失败」的路径都是错的。
 *
 * 与 [ShortcutEntries] 同构的取舍：这里也**只放行「敢确定能用」的**，拿不准就拒绝，
 * 因为拒绝的代价是「用户看到一句为什么」（可自愈），放行的代价是「打开了一个任意启动口子」
 * （不可自愈）。参考实现 Anywhere- 在这里是**完全放开**的（只校验非空），我们不跟。
 *
 * ## 为什么 `content:` 不在拒绝名单里
 *
 * 它确实是上面那张表里唯一「可能让配置读到别的应用的数据」的 scheme（比如
 * `content://media/...`）。留着的理由：① 用 `ACTION_VIEW` 看一个 content URI 是真实存在的
 * 深链用法；② 它不是特权通道 —— 目标应用/提供者仍然按**自己的**授权规则决定放不放行，
 * SystemUI 并不会因为发了这条 intent 就获得额外的数据访问权（`startActivity` 不携带
 * 任何 URI 授权）。而 `file:`/`data:`/`javascript:` 是**本地资源与非应用协议**，
 * 从「启动一个应用入口」这个用途出发没有任何正当理由，所以一律拒绝。
 *
 * ## 与 FeatureFlag 的关系（为什么这套能力没有单独的开关）
 *
 * 「清单里没有 `link:` 项」= 这个能力**不参与**：hook 侧只在 [ShortcutEntries.Kind.LINK]
 * 分支里才碰它，界面侧也只有这一页会写 `link:` 项。加上扇形特性本身的总开关
 * （`f.corner_small_window`，默认关），关态下这段代码一条都不会被走到 ——
 * 行为与未安装模块完全一致。
 */
object LinkTargets {

    /**
     * 清单项的前缀。**大小写不敏感地识别**，但规范化输出一律小写。
     *
     * 识别放宽的理由：大小写敏感的代价不对等 —— 用户手改配置写成 `Link:weixin://x` 时，
     * 大小写敏感会让它被当成「一个叫 `Link:weixin:` 的包」而丢掉（日志里只有一句
     * 「不可启动」，看不出是他写错了一个字母）。而宽松识别的风险是零：`:` 不可能是
     * 合法包名的一部分，所以任何**合法**的存量项都不会因为这个前缀被误判（测试
     * `existingFormsAreNeverSeenAsLinks` 钉着这条）。
     */
    const val PREFIX = "link:"

    /** 标识符允许的字符（RFC 3986：`scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`）。 */
    private const val SCHEME_EXTRA = "+-."

    /**
     * 拒绝的 scheme（小写比较）。
     *
     * - `file` / `data` / `javascript` / `blob`：本地资源与非应用协议，不是「启动一个入口」；
     * - `android-app`：`android-app://包名` 是**点名一个包**的形式，等价于 `package=`；
     * - `intent`：`intent:` 是显式/选择器形式的外壳（见 [Rejection.EXPLICIT]）。
     */
    private val UNSAFE_SCHEMES = setOf("file", "data", "javascript", "blob", "android-app", "intent")

    /**
     * 显式/选择器形式的标志串（小写比较）。
     *
     * 这些键只出现在 `Intent` 的 URI 语法里（`#Intent;...;end`），正常深链不会带它们。
     * 逐个列出来而不是只认 `#Intent;`：`Intent.parseUri` 对**不带** `#Intent;` 的
     * `component=` 也会认（`uriString` 里的这些键会在 `parseIntent` 里被读），
     * 只堵外壳等于没堵。
     */
    private val EXPLICIT_MARKERS = arrayOf(
        "#intent;",
        ";component=",
        ";package=",
        ";s.",
        ";i.",
        ";launchflags=",
        ";selector=",
        ";action=",
        ";category=",
        ";launchbounds=",
    )

    /** URI 长度上限。配置项不该这么长，超过只会把配置文件撑爆。 */
    const val MAX_LENGTH = 1024

    /**
     * 拒绝的原因。**每一种都要能单独给用户一句人话**（界面侧映射成资源字符串，
     * hook 侧直接进日志）—— 合成一个「格式不对」等于把「为什么」藏起来，
     * 用户只会反复试同一串字符。
     */
    enum class Rejection {
        /** 前缀之后什么都没有（`link:` 或只有空白）。 */
        EMPTY,

        /** 没有 `:`，也就没有 scheme（`weixin`、`//host/path`、`com.foo/.Bar`）。 */
        NO_SCHEME,

        /** 有 `:` 但 scheme 本身不合法，或 `:` 之后没有可启动的内容（`1abc://x`、`weixin:`、`weixin://`）。 */
        BAD_SCHEME,

        /** 显式/选择器形式（`intent:`、`#Intent;`、`component=`、`package=`、`S.`…）。 */
        EXPLICIT,

        /** [UNSAFE_SCHEMES] 里的 scheme。 */
        UNSAFE_SCHEME,

        /** 含空白或控制字符（要空格得百分号编码）。 */
        ILLEGAL_CHARS,

        /** 超过 [MAX_LENGTH]。 */
        TOO_LONG,
    }

    /** 一次校验的结果。 */
    sealed interface Result {
        /** 通过：[uri] 是**去掉前缀、已 trim** 的 URI，可以直接交给 `Intent.parseUri`。 */
        data class Ok(val uri: String) : Result

        data class Rejected(val reason: Rejection) : Result
    }

    /**
     * 这条清单项是链接项吗。**只认整串开头的那个前缀**：
     * `com.foo/link:bar` 的快捷方式 id 里出现 `link:` 不算（它是快捷方式项）。
     */
    fun isLink(raw: String): Boolean =
        raw.length >= PREFIX.length && raw.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)

    /**
     * 清单项里那串 URI（去掉前缀并 trim）。**不做合法性判断** ——
     * 非法项也要能把它原样拿出来打日志（用户要照着日志里那串字符去核对）。
     */
    fun uriOf(raw: String): String =
        if (isLink(raw)) raw.substring(PREFIX.length).trim() else raw.trim()

    /**
     * 规范化后的**存储形式**（`link:` + 去掉首尾空白之后的 URI）。
     *
     * 只做 trim，不重编码：URI 的各段语义由目标应用决定，我们改一个字符就可能把它改坏
     * （例如把 `%2F` 解成 `/`）。所以「规范化」在这里只等于「去掉确定无意义的首尾空白」。
     */
    fun stored(uri: String): String = PREFIX + uri.trim()

    /**
     * 校验一段**裸 URI**（不带前缀）。`null` = 通过。
     *
     * 纯字符串实现：`core` 不许依赖 `android.*`，
     * 而这段逻辑的错法全是「静默放行一条奇怪的 URI」，所以它必须能脱离设备单测
     * （测试见 `LinkTargetsTest`，逐条钉住上表里每一行）。
     */
    fun check(uri: String): Rejection? {
        if (uri.isEmpty()) return Rejection.EMPTY
        if (uri.length > MAX_LENGTH) return Rejection.TOO_LONG

        // 空白与控制字符：URI 里不该有裸空格（要空格得 %20），
        // 而换行/制表符是拼接注入的典型形态（清单项内部本来也不允许换行）
        for (c in uri) {
            if (c.code <= 0x20 || c.code == 0x7f) return Rejection.ILLEGAL_CHARS
        }

        val colon = uri.indexOf(':')
        if (colon < 0) return Rejection.NO_SCHEME

        // scheme 语法：首字符必须是字母，其余是字母/数字/+/-/.
        for (i in 0 until colon) {
            val c = uri[i]
            val ok = if (i == 0) c.isLetterAscii() else c.isLetterAscii() || c.isDigit() || c in SCHEME_EXTRA
            if (!ok) return Rejection.BAD_SCHEME
        }
        // `:` 之后什么都没写（`weixin:` / `weixin://`）：解析出来没有可启动的东西。
        // 判据是「去掉斜杠之后还有没有内容」—— 只写 `//` 与什么都不写是同一个结果
        if (uri.substring(colon + 1).trimStart('/').isEmpty()) return Rejection.BAD_SCHEME

        val scheme = uri.substring(0, colon).lowercase()
        val rest = uri.substring(colon + 1).lowercase()

        // 显式形式先判：它比 scheme 名单更根本（`intent:` 也可能夹带 `file:`）
        if (scheme == "intent") return Rejection.EXPLICIT
        for (marker in EXPLICIT_MARKERS) {
            if (rest.contains(marker)) return Rejection.EXPLICIT
        }
        if (scheme in UNSAFE_SCHEMES) return Rejection.UNSAFE_SCHEME

        return null
    }

    /**
     * 校验一条**清单项**（带 `link:` 前缀的形式）。
     *
     * 非 `link:` 开头的输入返回 [Rejection.EMPTY] —— 调用方本该先用 [isLink] 分流，
     * 这里只是兜底：把「不是链接项」和「是空的链接项」都归到「没有可启动的 URI」，
     * 两者在启动侧的处理完全一样（拒绝 + 日志）。**不会**因为这种输入抛异常。
     */
    fun parse(raw: String): Result {
        val uri = uriOf(raw)
        val bad = check(uri)
        return if (bad == null) Result.Ok(uri) else Result.Rejected(bad)
    }

    /**
     * 清单项 → **可写入的规范形式**；拒绝时返回 `null`。
     *
     * 写入侧（界面）用它：先把用户填的东西规范化成前缀形式再写进清单，
     * 保证「写进去的」与「读回来能解析的」是同一个字符串。
     * 幂等：`normalize(normalize(x)) == normalize(x)`（测试钉着）。
     */
    fun normalize(raw: String): String? =
        when (val r = parse(raw)) {
            is Result.Ok -> stored(r.uri)
            is Result.Rejected -> null
        }

    /**
     * 这条链接在扇子里的**默认显示名**。
     *
     * ## 为什么是「猜」而不是让用户填一个名字
     *
     * 参考实现 Anywhere- 的做法是**让用户填名字**（`SchemeEditorFragment` 的
     * `tiet_app_name` 是必填项），因为它一条记录有 `appName` 字段可存。
     * 我们的清单项只有一个字符串能放（`OrderedItems` 的一条一项），多存一个名字
     * 就要么再加一个键（多一条写入路径）、要么往项里再塞一个分隔符
     * （`OrderedItems` 的注释里写死了「分隔符必须无歧义」这条约束）。
     * 两条都不值当，所以这里给一个**确定性的**默认名，并在导入界面上**当场把它显示出来**
     * （HCI「识别优于回忆」：用户不必先加进去、再去扇子里看它叫什么）。
     *
     * 取值规则（纯字符串，测试逐条钉住）：取 `:` 之后的**第一个路径段**
     * （先去掉可能的前导 `//`），取不到就退回 scheme；再取不到就退回整串。
     *
     * ```
     * weixin://scanqrcode           → scanqrcode      （`//` 之后到第一个 `/`）
     * https://example.com/a?b=c     → example.com
     * market://details?id=x         → details
     * content://media/external/i/1  → media
     * mailto:someone@example.com    → someone@example.com   （不透明形式，整段就是「第一段」）
     * weixin://                     → weixin          （后面什么都没有，退回 scheme）
     * ```
     *
     * 一律不返回空串：空标题在界面上就是一行看不见的东西（`FanItemRef.label` 也依赖它）。
     *
     * ⚠️ 刻意**不**加 `scheme:` 前缀（例如 `weixin · scanqrcode`）：扇形里每一项的标题
     * 就那么宽，加了之后短链接反而看不全；而「这是链接项」已经由行内图标与清单页的
     * 副标题表达了。
     */
    fun fallbackLabel(uri: String): String {
        val colon = uri.indexOf(':')
        val scheme = if (colon > 0) uri.substring(0, colon) else ""
        // 不带 `//` 的不透明形式（`mailto:` / `weixin:scanqrcode`）没有 host 这个概念，
        // 它的「第一段」就是冒号之后的整段 —— 与带 `//` 的形式用同一套取值规则，
        // 免得同一份 URI 走两条分支得出两个不同的名字
        val rest = if (colon >= 0) uri.substring(colon + 1).removePrefix("//") else uri
        val host = rest
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        if (host.isNotEmpty()) return host
        if (scheme.isNotEmpty()) return scheme
        // 非法项（校验失败）也会走到这里 —— 它照样要有一个能显示、能删掉的名字，
        // 所以退回整串（trim 掉空白），绝不返回空串
        return uri.trim().ifEmpty { PREFIX }
    }

    private fun Char.isLetterAscii(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
