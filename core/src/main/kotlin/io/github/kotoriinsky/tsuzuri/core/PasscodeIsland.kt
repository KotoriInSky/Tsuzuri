package io.github.kotoriinsky.tsuzuri.core

/**
 * 「口令上岛」：**跨进程契约** + **口令匹配引擎**。
 *
 * ## 链路
 *
 * system_server 里 hook `ClipboardService`（剪贴板每一次变更都从这里过）→
 * 用 [PasscodeRules] 本地匹配（零网络、零数据表）→ 命中就定向广播给 SystemUI →
 * SystemUI 代发一条带 `miui.focus.param` extra 的普通通知上超级岛 →
 * 按用户设置的超时自动消失（`timeoutAfter` + `postDelayed` 兜底）。
 *
 * ## 这个文件里放什么
 *
 * 契约（action / 权限 / extra / 超时 / 防抖）与**纯函数引擎**。放 `:core` 的理由与
 * [DrawerSignal] 完全同构：system_server 的观察侧与 SystemUI 的展示侧必须共用
 * 同一份判据与同一份规则表（写两份必然漂移，漂移的表现是「发了没人认」或
 * 「两边弹得不一样」—— 静默，无报错）；且 `:core` 能脱离 Android 跑单元测试，
 * 而正则的每一个边界（锚定、边界符、闭合符号）都有明确的错法，必须钉住。
 *
 * focus.param 的 **JSON 组装不在**这里：`:core` 是纯 JVM 模块，没有 `org.json`；
 * 组装在 `:hook` 的 SystemUI 侧完成（那里运行时自带 org.json）。
 */
object PasscodeIsland {

    /** 超级岛展示模板。规则只选择已有类别，不允许自定义 RemoteViews 布局。 */
    enum class ContentCategory(val wireName: String) {
        SHORT_VIDEO("short_video"),
        LONG_VIDEO("long_video"),
        IMAGE_TEXT("image_text"),
        MUSIC("music"),
        ECOMMERCE("ecommerce"),
        EXPRESS("express"),
        WEB("web"),
        ;

        companion object {
            fun fromWire(value: String?): ContentCategory? =
                values().firstOrNull { it.wireName == value }
        }
    }

    /**
     * 广播 action。**system_server 发、SystemUI 收。**
     *
     * 走广播而不是 remote prefs 的理由见 [DrawerSignal.ACTION] 的注释
     * （那是用户配置通道；运行时事件落盘 + stale 残留都是坑）。
     * ⚠️ **这是对外契约**：改名字 = 旧发新收 = Receiver 注册成功但零日志，
     * 判据是必须看到 SystemUI 侧「第一条已送达」。
     */
    const val ACTION = "io.github.kotoriinsky.tsuzuri.action.PASSCODE_ISLAND"

    /**
     * 接收方注册要求的权限。理由见 [DrawerSignal.PERMISSION]：
     * SystemUI（uid 10226）与 system_server（uid 1000）跨 uid，接收器必须
     * `RECEIVER_EXPORTED`；不挂权限的话任何第三方应用都能伪造一条口令广播
     * 随意弹出系统级岛通知。`DUMP` 是 signature|privileged|development。
     */
    const val PERMISSION = "android.permission.DUMP"

    /** 广播只投给 SystemUI，不做全系统广播。 */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** 命中的规则 id（[PasscodeRules] 里的 `Rule.id`），诊断用。 */
    const val EXTRA_RULE = "rule"

    /** 岛上标题（「B 站视频」「快递」…）。 */
    const val EXTRA_LABEL = "label"

    /**
     * 跳转目标。http(s) URL 或自定义 scheme（淘口令 = `taobao://`）。
     * SystemUI 侧用 `ACTION_VIEW` 直接交给系统解析 —— App 优先、浏览器兜底
     * 是 Android 的默认行为，不需要我们排序。
     */
    const val EXTRA_TARGET = "target"

    /**
     * 跳转目标的**候选链**（`StringArrayList`），2026-09-27 新增。
     *
     * 为什么需要：同一条口令在不同设备上「谁接得住」不一样（快递单号最典型 ——
     * 装了支付宝可以走菜鸟小程序直达物流页，没装就只能走网页）。候选链把
     * 「依次试哪个」交给**展示侧**判断，因为只有那里有 `PackageManager`。
     *
     * 展示侧顺序：`[EXTRA_TARGET] + [EXTRA_TARGET_ALTS]` 取第一个能解析的；
     * 一个都解析不到 → 整条不上岛（与原来的单目标行为一致）。
     */
    const val EXTRA_TARGET_ALTS = "target_alts"

    /** 岛上正文（口令原文的截断摘要）。 */
    const val EXTRA_SNIPPET = "snippet"

    /**
     * 用户复制的**原文**（不截断、不解析，上限 [SOURCE_TEXT_MAX] 字符）。
     * 岛上「分享」按钮发的就是它 —— 用户要分享的是自己复制的那段口令/链接，
     * 不是我们解析、清洗之后的目标地址。缺失时展示侧退回旧的「标题 + 链接」。
     */
    const val EXTRA_SOURCE_TEXT = "source_text"

    /** 原文上限：广播走 Binder，事务缓冲有限，长文截在这里。 */
    const val SOURCE_TEXT_MAX = 4000

    /**
     * 展示层核心内容（2026-09-25 视觉定稿）：引擎匹配到什么就亮什么 ——
     * B 站 = BV 号、快递 = 单号、淘宝/抖音/小红书分享 = 标题行（口令原文/短链不展示）。
     * 可能为 null（普通网页没有核心）——展示侧回落「打开XX」单行。
     */
    const val EXTRA_CORE = "core"

    /** 平台强调色，`#RRGGBB`（B站 #FB7299 / 抖音 #FE2C55 / 淘宝 #FF6900 / 兜底蓝 #5B7CFF）。 */
    const val EXTRA_ACCENT = "accent"

    /** 短平台名（「B站」「淘宝」），岛上「打开XX」的 XX。普通网页没有 → 走 appLabel。 */
    const val EXTRA_PLATFORM_NAME = "platform_name"

    /** 目标 App 包名（岛内/AOD 图标的来源）。普通网页 null → 展示侧按域名启发式解析。 */
    const val EXTRA_PLATFORM_PKG = "platform_pkg"

    /** 展示模板类别，跨 system_server / SystemUI 传递。 */
    const val EXTRA_CATEGORY = "category"

    /**
     * 上岛通知多久后自动取消。
     *
     * 10 秒是对齐 Flyme 原版：剪贴板建议的超时就是 `w(data, 10000L)`
     * （com.meizu.suggestion 的 o.java）。
     * 口令是**瞬时意图**：10 秒内没理它，它就不值得再打扰。
     */
    const val AUTO_CANCEL_MS = 10_000L
    const val MIN_TIMEOUT_SECONDS = 2f
    const val MAX_TIMEOUT_SECONDS = 300f

    fun timeoutMs(seconds: Float?): Long {
        val selected = seconds?.takeIf { it.isFinite() } ?: AUTO_CANCEL_MS / 1000f
        return (selected.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS).toInt() * 1000L)
    }

    /**
     * 仅合并同一次复制产生的重复 hook / 相同内容信号；再次复制应能重新上岛。
     */
    const val DEBOUNCE_MS = 1_500L

    /**
     * 这段文本是不是防抖窗口内的重复。
     *
     * 纯函数（时间由调用方传入）：`:core` 不许碰时钟，测试才能钉住边界
     * （窗口内重复 = true；窗口外/文本变了/时钟倒退 = false）。
     */
    fun isDuplicate(
        lastText: String?,
        lastAtMs: Long,
        text: String,
        nowMs: Long,
        windowMs: Long = DEBOUNCE_MS,
    ): Boolean {
        if (lastText == null) return false
        if (lastText != text) return false
        if (lastAtMs <= 0L || nowMs < lastAtMs) return false
        return nowMs - lastAtMs < windowMs
    }

    /**
     * 同一次复制被 App **写了两次**的合并窗口。
     *
     * 实测（2026-10-06 用户报「知乎跳两个岛」，发射侧日志）：知乎一次「复制」在 **181ms** 内
     * 先写 `www.zhihu.com/question/…/answer/…`、再写 `oia.zhihu.com/km_paid_content/share?…`。
     * 两条 URL 不同 → [PasscodeLinkIdentity] 算出的身份不同 → 去重拦不住 → **两个岛**；
     * 而且后者的 `km_pst`/`share_code` 是**每次复制都变**的临时令牌，也不能拿来当身份。
     * 同一次复制的两次写入应当只出一条岛（取**最后**那条 = 剪贴板最终内容）。
     */
    const val COPY_BURST_MS = 400L

    /**
     * 两条信号是不是「同一次复制的两次写入」：**同一平台** + 落在 [COPY_BURST_MS] 窗口内。
     *
     * 纯函数（时间由调用方传入），边界与 [isDuplicate] 同样可离线钉住。
     * 平台相同才合并：用户连着复制两条**不同平台**的链接时必须各出一条岛。
     */
    fun isSameCopyBurst(
        prevPlatformPkg: String?,
        prevAtMs: Long,
        platformPkg: String?,
        nowMs: Long,
        windowMs: Long = COPY_BURST_MS,
    ): Boolean {
        if (prevPlatformPkg.isNullOrBlank() || platformPkg.isNullOrBlank()) return false
        if (prevPlatformPkg != platformPkg) return false
        if (prevAtMs <= 0L || nowMs < prevAtMs) return false
        return nowMs - prevAtMs < windowMs
    }

    /**
     * [EXTRA_CORE] 里装的是「可复制的号」还是「人类可读的标题」。
     *
     * 展示侧靠它分流（视觉定稿 v4）：**号当大字很怪，标题当次行又浪费** ——
     * 号（`BV1GJ411x7h7` / `ac4725103` / `SF1382024591123`）走平台色的次行，
     * 标题（`全棉四件套床上用品限时特价`）走大字行。
     *
     * 判据：号一定是**纯 ASCII 字母数字**（CJK 标题、带空格的网页标题都不满足），
     * 且至少含一位数字（排除纯单词）。放 `:core` 的理由与规则引擎同构：这是
     * **跨进程契约的一部分**（观察侧产出 core、展示侧消费它的形态），且纯函数可测。
     */
    fun isIdLike(core: String): Boolean =
        core.length in 4..24 &&
            core.any { it.isDigit() } &&
            core.all { it.code < 0x80 && (it.isLetterOrDigit() || it == '_' || it == '-' || it == '/' || it == '.') }

    // ── 快递单号 → 承运商名（大字行的内容，2026-09-27）────────────────────

    /**
     * 快递单号 → 承运商中文名。**大字行放这个，不再放抓来的网页标题。**
     *
     * 为什么要有它：快递条的大字行原来是预加载抓的 `<title>`，真机实测拿到的是
     * 「单号查询结果 - 快递100」—— 占着最贵的视觉位置却零信息。
     * 承运商名是**单号前缀里本来就带着的公开事实**（规则表里正用它在做匹配），
     * 零网络、零失败路径，比抓网页可靠得多。
     *
     * 前缀 → 承运商 的对应是行业编号常识，不是任何一家的资产（同 [RULES] 的说明）。
     * 返回 null = 认不出承运商（纯数字单号 / 万国 S10 双字母前缀），
     * 展示侧回落「把单号放大」—— 不编一个假名字。
     */
    fun carrierName(number: String, text: String = ""): String? {
        val n = number.trim().uppercase()
        // 国际件先判：云途 YT+16 必须在圆通 YT 之前；UPS / 亚马逊 / DHL 件号前缀不会与国内撞
        ExpressIntl.prefixedCarrier(n)?.let { return it }
        if (n.all(Char::isDigit)) ExpressIntl.numericCarrier(n, text)?.let { return it }
        return when {
            n.startsWith("DPK") -> "德邦快递"
            n.startsWith("SF") -> "顺丰速运"
            n.startsWith("YT") -> "圆通速递"
            n.startsWith("JT") -> "极兔速递"
            // JD / JDV / JDX 都是京东物流的号段（规则表里同一条正则）
            n.startsWith("JD") -> "京东物流"
            // 万国邮联 S10（两字母 + 9 位数字 + 两字母）：国别码查表（tracking_number_data 的
            // s10.json），查不到的给个诚实的粗名，别假装知道
            S10_NUMBER.matches(n) -> ExpressIntl.s10Carrier(n) ?: "国际件"
            else -> null
        }
    }

    /** 万国邮联 S10 单号形态（与 [RULES] 里 express_prefixed 的最后一段同一形态）。 */
    private val S10_NUMBER = Regex("""[A-Z]{2}\d{9}[A-Z]{2}""")

    fun isS10Shape(number: String): Boolean = S10_NUMBER.matches(number)
}

/**
 * 口令匹配引擎。
 *
 * ## 匹配顺序 = first-match-wins，一次剪贴板事件最多一条结果
 *
 * ```
 * ① URL / 短链     —— 命中即独占：URL 里的 BV/av 已由链接表达，不再往下
 * ② 号形态（边界）  —— 高熵内容号：BV / av / bm / cv / sm / ac / 带前缀快递单号
 * ③ 上下文门控      —— 纯数字单号：必须同文含「单号/快递/取件」等词（防撞订单号）
 * ```
 *
 * 排序理由：精确度递减、误弹风险递增。链接自带完整目标最可信；
 * 内容号最弱所以垫底。
 *
 * ## 「号形态」为什么不用整段锚定（2026-09-27 修订）
 *
 * 第一版把 BV/av/bm 写成 `^…$` 整段锚定，代价是**真实分享文案里的号全部漏掉**：
 * 「【标题】BV1GJ411x7h7 复制打开B站」这种从聊天记录里复制来的文本，整段锚定必然 MISS
 * —— 而用户实际会复制的正是这种（不是干净的一段号）。改成「前后不是字母数字即可命中」
 * 后，误弹风险由**内容号自身的信息量**兜住：BV 是 12 位、av 至少 6 位数字、
 * 快递单号带承运商前缀，随手一句话里凑不出这种形态。
 * 唯一保留锚定的是 pixiv `pid`：它只有「pid + 4 位数字」，和日志里的进程号毫无区别，
 * 全文搜会天天误弹（见 [RULES] 里的说明）。
 *
 * ## 数据来源与合规
 *
 * 规则格式参照 Flyme，数据全部来自**公开事实**：
 * - 内容号的 URL 拼接是各平台的公开约定（b23.tv/ac 号/sm 号）；
 * - 快递单号前缀（SF/JD/YT、S10 万国标准）是行业编号格式，不是任何一家的资产
 *   —— Flyme 自己的单号正则只在云端（实测拿不到）；
 * - 校验位强校验（tracking_number_data，MIT）留作二期，v1 只做形态匹配。
 */
object PasscodeRules {

    /**
     * 平台元数据（2026-09-25 视觉定稿）：**强调色跟平台走，岛内图标用目标 App 图标**。
     *
     *  - [name]：短平台名，「打开XX」的 XX（「B站」「淘宝」）；
     *  - [pkg]：目标 App 包名 = 岛内/AOD 图标来源（SystemUI 侧经 PackageManager 解析；
     *    null = 无固定目标 App——普通网页按域名启发式、快递不按 App 上色）；
     *  - [accent]：平台强调色 `#RRGGBB`，注入 `highlightColor` 与通知正文 span。
     *    快递/普通网页没有品牌色 → 通用兜底蓝。
     */
    data class Platform(
        val name: String,
        val pkg: String?,
        val accent: String,
        val category: PasscodeIsland.ContentCategory = PasscodeIsland.ContentCategory.IMAGE_TEXT,
    )

    /**
     * 内置规则表（[BuiltinRules]，`passcode_rules.json`）里的平台，按 id 建一次：`P_*` 与各表共用同一实例，
     * `platform == P_DOUYIN` 这类比较照旧成立。读不到时给占位（名字即 id、无包名、兜底蓝），保证类初始化不失败。
     */
    private val BUILTIN_PLATFORMS: Map<String, Platform> =
        BuiltinRules.checked?.platforms?.mapValues { (_, p) -> Platform(p.name, p.pkg, p.accent, p.category) }.orEmpty()

    private fun builtin(id: String): Platform = BUILTIN_PLATFORMS[id] ?: Platform(id, null, "#5B7CFF")

    // ── 平台常量：数据在 passcode_rules.json 的 platforms 里，这里只按 id 取──

    val P_BILI = builtin("bili")
    val P_TAOBAO = builtin("taobao")
    val P_TMALL = builtin("tmall")
    val P_JD = builtin("jd")
    val P_MEITUAN = builtin("meituan")
    val P_DIANPING = builtin("dianping")
    val P_PDD = builtin("pdd")
    val P_DOUYIN = builtin("douyin")
    val P_XHS = builtin("xhs")
    val P_NETEASE = builtin("netease")
    val P_KUAISHOU = builtin("kuaishou")
    val P_WEIBO = builtin("weibo")
    val P_CHANNELS = builtin("channels")
    val P_ZHIHU = builtin("zhihu")
    val P_BAIDU = builtin("baidu")
    val P_TIEBA = builtin("tieba")
    val P_COOLAPK = builtin("coolapk")

    // ── 借 sokoko-org/nonebot-plugin-parser-lite（MIT）的 URL 形态补的一批 ──────────
    // 只抄**事实**（主机名与路径形态），实现是自己写的；
    // 包名只有**有把握**的才写：写错不会误钉（展示侧会先 canHandlePackage 校验，
    // 不通过就回落到域名启发式/系统解析），但不确定的一律留 null 更干净。
    val P_HEYBOX = builtin("heybox")
    val P_MIYOUSHE = builtin("miyoushe")
    val P_LOFTER = builtin("lofter")
    val P_TAPTAP = builtin("taptap")
    val P_DUITANG = builtin("duitang")
    val P_DOUBAO = builtin("doubao")
    val P_BUFF = builtin("buff")
    val P_DS = builtin("ds")
    val P_ZLB = builtin("zlb")
    val P_ILLU = builtin("illu")
    val P_5EPLAY = builtin("5eplay")
    val P_LINUXDO = builtin("linuxdo")
    val P_WMPVP = builtin("wmpvp")
    val P_NGA = builtin("nga")
    // 借 ParseHub（MIT）的形态补的两个平台
    val P_PIPIX = builtin("pipix")
    val P_ZUIYOU = builtin("zuiyou")
    val P_BAIDUPAN = builtin("baidupan")
    val P_HUPU = builtin("hupu")
    val P_DONGQIUDI = builtin("dongqiudi")
    val P_DOUBAN = builtin("douban")
    val P_QQ = builtin("qq")
    val P_XIANYU = builtin("xianyu")
    val P_DEWU = builtin("dewu")
    val P_ELEME = builtin("eleme")
    val P_MANGO = builtin("mango")
    val P_KUGOU = builtin("kugou")
    val P_KUWO = builtin("kuwo")
    val P_QUARKPAN = builtin("quarkpan")
    val P_ALIYUNPAN = builtin("aliyunpan")
    val P_XUNLEI = builtin("xunlei")
    val P_AMAP = builtin("amap")
    val P_BAIDUMAP = builtin("baidumap")
    val P_CTRIP = builtin("ctrip")
    val P_FLIGGY = builtin("fliggy")
    val P_RAIL12306 = builtin("rail12306")
    // ── 借 wujunwei928/parse-video 与 iawia002/lux（均 MIT）的分享域补的一批（2026-10-22）──
    // 只借分享域这一事实；包名只写有把握的，没把握留 null（展示侧按域名找 App）。
    // 品牌色不确定的一律用兜底蓝，不编颜色。已停运的（微视、度小视、秒拍、音悦台、半次元）不收。
    val P_HUOSHAN = builtin("huoshan")
    val P_HAOKAN = builtin("haokan")
    val P_PEAR = builtin("pear")
    val P_PIPIGX = builtin("pipigx")
    val P_MEIPAI = builtin("meipai")
    val P_DOUPAI = builtin("doupai")
    val P_KGE = builtin("kge")
    val P_SIXROOM = builtin("sixroom")
    val P_XINPIANCHANG = builtin("xinpianchang")
    val P_SOHU_TV = builtin("sohu_tv")
    val P_CCTV = builtin("cctv")
    val P_GEEKBANG = builtin("geekbang")
    val P_TANGDOU = builtin("tangdou")
    val P_NETEASE_NEWS = builtin("netease_news")
    val P_SMZDM = builtin("smzdm")
    val P_TOUTIAO = builtin("toutiao")
    val P_XIGUA = builtin("xigua")
    val P_DOUYU = builtin("douyu")
    val P_HUYA = builtin("huya")
    val P_XIMALAYA = builtin("ximalaya")
    val P_XIAOYUZHOU = builtin("xiaoyuzhou")
    val P_WEREAD = builtin("weread")
    val P_VIPSHOP = builtin("vipshop")
    val P_TT_VIDEO = builtin("tt_video")
    val P_QQ_MUSIC = builtin("qq_music")
    val P_YOUKU = builtin("youku")
    val P_IQIYI = builtin("iqiyi")
    val P_PIXIV = builtin("pixiv")
    // niconico / AcFun 都是视频站：以前没写类别，落到默认的图文模板（2026-10-22 探针发现）
    val P_NICO = builtin("nico")
    val P_ACFUN = builtin("acfun")
    val P_EXPRESS = builtin("express")
    val P_GENERIC = Platform("网页", null, "#5B7CFF", PasscodeIsland.ContentCategory.WEB)

    // ── 海外平台（2026-09-25 批次，规则形态参考 yt-dlp/各平台公开 URL 约定）──
    // 包名 = Google Play 主包（国内应用商店可能没有，图标解析失败时展示侧回落）。
    val P_YOUTUBE = builtin("youtube")
    val P_YT_MUSIC = builtin("yt_music")
    val P_SPOTIFY = builtin("spotify")
    val P_APPLE_MUSIC = builtin("apple_music")
    val P_X = builtin("x")
    val P_IG = builtin("ig")
    val P_TIKTOK = builtin("tiktok")
    val P_REDDIT = builtin("reddit")
    val P_TELEGRAM = builtin("telegram")
    val P_TWITCH = builtin("twitch")
    val P_NETFLIX = builtin("netflix")
    val P_AMAZON = builtin("amazon")
    val P_STEAM = builtin("steam")
    val P_DISCORD = builtin("discord")
    val P_GITHUB = builtin("github")
    val P_GMAPS = builtin("gmaps")
    val P_FB = builtin("fb")
    val P_THREADS = builtin("threads")
    val P_PIN = builtin("pin")
    val P_EBAY = builtin("ebay")
    val P_SC = builtin("sc")
    val P_VIMEO = builtin("vimeo")

    /**
     * 分享类平台集合：这些平台的分享文案里**标题才是核心内容**（口令/短链对用户是乱码）。
     * URL 命中这些平台时对整段文案跑 [extractTitle]；没有可靠核心 → null 走预加载。
     */
    val SHARE_PLATFORMS: Set<Platform> =
        BuiltinRules.checked?.platforms?.values?.filter { it.share }?.map { builtin(it.id) }?.toSet().orEmpty()

    /** 一条匹配结果：引擎对剪贴板文本的全部输出。 */
    data class Match(
        /** 命中的规则 id，进广播 extra 与日志，排障时用它对回规则表。 */
        val ruleId: String,
        /** 岛上标题，如「B 站视频」。 */
        val label: String,
        /** 跳转目标：http(s) URL 或自定义 scheme。 */
        val target: String,
        /** 平台元数据（名称/包名/强调色）。null = 普通网页，展示侧自行解析。 */
        val platform: Platform? = null,
        /**
         * 展示层核心内容（视觉定稿 2026-09-25）：**规则匹配到的最关键字段**——
         * B 站放 BV 号、快递放单号、淘宝/抖音/小红书分享放标题行。
         * null = 没有核心（普通网页），展示侧回落单行「打开XX」。
         */
        val core: String? = null,
        /**
         * 候选链的**备选**（不含 [target] 本身），顺序即优先级：展示侧取
         * 「第一个能解析的」。空 = 只有 [target] 一个目标（绝大多数规则）。
         */
        val alts: List<String> = emptyList(),
        /** 展示模板类别；旧广播缺失时由展示侧按规则和目标回落推断。 */
        val category: PasscodeIsland.ContentCategory = categoryFor(ruleId, platform, target),
    )

    /** 内置/旧版本规则没有显式类别时的兼容推断。 */
    fun categoryFor(ruleId: String, platform: Platform?, target: String): PasscodeIsland.ContentCategory {
        if (ruleId.startsWith("express")) return PasscodeIsland.ContentCategory.EXPRESS
        if (platform != null) {
            if (platform == P_DOUYIN) {
                val uri = runCatching { java.net.URI(target) }.getOrNull()
                val host = uri?.host?.lowercase(java.util.Locale.ROOT).orEmpty()
                val path = uri?.path.orEmpty().lowercase(java.util.Locale.ROOT)
                if (host == "haohuo.jinritemai.com" && path.startsWith("/views/product/")) {
                    return PasscodeIsland.ContentCategory.ECOMMERCE
                }
                if (host == "mall.douyin.com" && path.startsWith("/goods/")) {
                    return PasscodeIsland.ContentCategory.ECOMMERCE
                }
                // 抖音商城分享短链的真实落点（实测 2026-10-06）：
                // `haohuo.jinritemai.com/ecommerce/trade/detail/index.html?…&goods_detail=…`
                if (path.startsWith("/ecommerce/trade/detail")) {
                    return PasscodeIsland.ContentCategory.ECOMMERCE
                }
                // 图文/图集：`/note/<号>` 与**分享页形态** `/share/note/<号>/`（`slides` 也是图集）。
                // 分享页前缀必须一起认 —— 短链重定向后落到的就是 `iesdouyin.com/share/note/…`，
                // 只写 `^/note/` 会把它漏掉、当成短视频（那样就不取封面了）。
                if (Regex("""^/(?:share/)?(?:note|slides)/[0-9]{10,20}(?:/|$)""").containsMatchIn(path)) {
                    return PasscodeIsland.ContentCategory.IMAGE_TEXT
                }
                if (Regex("""^/(?:share/)?video/[0-9]{10,20}(?:/|$)""").containsMatchIn(path) ||
                    host in setOf("v.douyin.com", "jx.douyin.com", "www.iesdouyin.com")
                ) {
                    return PasscodeIsland.ContentCategory.SHORT_VIDEO
                }
                return PasscodeIsland.ContentCategory.IMAGE_TEXT
            }
            // B 站专栏与动态**不是长视频**（平台默认类别是 LONG_VIDEO）：专栏是图文，
            // 动态/opus 按图文模板走 —— 探针实测 `/read/cv…` 与 `t.bilibili.com/<号>`
            // 原来都被归成长视频。
            if (platform == P_BILI) {
                val uri = runCatching { java.net.URI(target) }.getOrNull()
                val path = uri?.path.orEmpty().lowercase(java.util.Locale.ROOT)
                val host = uri?.host.orEmpty().lowercase(java.util.Locale.ROOT)
                if (path.startsWith("/read/cv") || path.startsWith("/opus/") ||
                    path.startsWith("/dynamic/") || host == "t.bilibili.com"
                ) {
                    return PasscodeIsland.ContentCategory.IMAGE_TEXT
                }
                // 音频 `au` 与音频合辑 `am`（形态参考 yt-dlp BilibiliAudioIE / BilibiliAudioAlbumIE，Unlicense）
                if (path.startsWith("/audio/au") || path.startsWith("/audio/am")) {
                    return PasscodeIsland.ContentCategory.MUSIC
                }
            }
            // 快手：**图集**（`subBiz=BROWSE_SLIDE_PHOTO` / `shareResourceType=PHOTO_OTHER`）
            // 按图文走。平台默认是短视频模板，而短视频模板**不显示封面**（Presenters 的
            // supportsCover），图集的价值恰恰在首图。真机样本：`v.kuaishou.com/nA6zqmo2`
            // 落到 `…&subBiz=BROWSE_SLIDE_PHOTO&shareResourceType=PHOTO_OTHER&…`。
            // ⚠️ 视频分享的 `subBiz` 取什么值**未验证**；不命中的一律维持短视频模板。
            if (platform == P_KUAISHOU) {
                val query = runCatching {
                    java.net.URI(target).rawQuery.orEmpty().lowercase(java.util.Locale.ROOT)
                }.getOrDefault("")
                if (query.contains("slide_photo") || query.contains("photo_other")) {
                    return PasscodeIsland.ContentCategory.IMAGE_TEXT
                }
            }
            // 小红书商品分享：短链 xhslink.com/m/… 302 到 `/goods-detail/<24hex>`（2026-10-22 实测）。
            // 页面是 8.7KB 的前端壳（只有 `<title>小红书</title>`），抓不到商品名，但至少按商品类展示。
            if (platform == P_XHS && target.contains("/goods-detail/", ignoreCase = true)) {
                return PasscodeIsland.ContentCategory.ECOMMERCE
            }
            // 知乎视频 `/zvideo/<号>`（yt-dlp ZhihuIE）：平台默认图文，视频要走带封面的视频模板
            if (platform == P_ZHIHU && target.contains("/zvideo/", ignoreCase = true)) {
                return PasscodeIsland.ContentCategory.LONG_VIDEO
            }
            if (platform == P_YOUTUBE && target.contains("/shorts/", ignoreCase = true)) {
                return PasscodeIsland.ContentCategory.SHORT_VIDEO
            }
            if (platform == P_IG && target.contains("/reel/", ignoreCase = true)) {
                return PasscodeIsland.ContentCategory.SHORT_VIDEO
            }
            if (platform == P_FB) {
                val path = runCatching { java.net.URI(target).path.orEmpty().lowercase() }.getOrDefault("")
                if (path.startsWith("/reel/")) return PasscodeIsland.ContentCategory.SHORT_VIDEO
                if ("/videos/" in path || path.startsWith("/watch")) {
                    return PasscodeIsland.ContentCategory.LONG_VIDEO
                }
            }
            if (platform == P_TOUTIAO && runCatching {
                    java.net.URI(target).path.orEmpty().startsWith("/video/")
                }.getOrDefault(false)) {
                return PasscodeIsland.ContentCategory.LONG_VIDEO
            }
            return platform.category
        }
        return if (target.startsWith("http", ignoreCase = true)) PasscodeIsland.ContentCategory.WEB
        else PasscodeIsland.ContentCategory.IMAGE_TEXT
    }

    /** 引擎开关。 */
    data class Options(
        /**
         * 任意 http(s) 链接是否上岛。用户拍板「http 也加入」（2026-09-24），
         * 所以默认 true；关掉后只剩已知短链域白名单会弹。
         */
        val genericUrl: Boolean = true,
        /** 纯数字快递单号（④门控）。默认 false：裸数字与订单号冲突。 */
        val numericTracking: Boolean = false,
    )

    /** 文本长度上限：再长的剪贴板内容不可能是口令，省掉无谓的正则回溯。 */
    private const val MAX_LEN = 2000

    /** ④门控的上下文词。出现任一个才允许纯数字单号上岛。 */
    val CONTEXT_WORDS = listOf("单号", "快递", "取件", "包裹", "韵达", "中通", "申通", "圆通", "邮政", "件")

    /**
     * 已知短链/内容域 → 岛上标题 + 平台元数据。genericUrl 关闭时它是唯一放行的
     * URL 白名单。注意 b23.tv 这类短链**不解析**：原样透传，App Links 自己会跳
     * **顺序敏感**：pan.baidu.com 必须在 baidu.com 之前（首个命中生效）。
     *
     * 电商/生活服务的分享短链域是 2026-09-25 从真实分享样本补的（e.tb.cn、3.cn、
     * yangkeduo、dpurl 等 —— 淘宝京东拼多多美团分享用的都是自有短链域，不带主域）。
     */
    private val HOST_RULES: List<RuleSet.HostRule> = BuiltinRules.checked?.hosts?.map { it.rule }.orEmpty()


    private fun urlHost(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()

    fun isSharedTaobaoShortLink(url: String): Boolean = urlHost(url) in setOf("m.tb.cn", "e.tb.cn")

    private val XHS_NOTE_PATH = Regex("""^/(?:explore|discovery/item)/([A-Za-z0-9]{24})(?:/|$)""")

    /** 拼多多商品号（`goods_id`）—— 分享链里**唯一稳定**的字段，见 [duplicateIdentity]。 */
    private val PDD_GOODS_ID = Regex("""[?&]goods_id=(\d{6,})""")

    /**
     * 小红书同一笔记的分享文案和跟踪参数会变化，防抖按内容身份计算。
     *
     * 拼多多同理（2026-10-06 真机实测）：同一商品的分享链带着**每次都变**的
     * `pxq_secret_key` / `share_uin` / `refer_share_id` / `_oak_share_time`，
     * 只有 `goods_id` 稳定 —— 身份只认它，别的参数一律不参与。
     */
    fun duplicateIdentity(match: Match): String? {
        if (match.platform == P_PDD) {
            PDD_GOODS_ID.find(match.target)?.let { return "pdd-goods:${it.groupValues[1]}" }
        }
        if (match.platform != P_XHS) return null
        val uri = runCatching { java.net.URI(match.target) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
        val path = uri.path?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        if (host == "xhslink.cn" || host == "xhslink.com") return "xhs-short:$host$path"
        if (host != "xiaohongshu.com" && !host.endsWith(".xiaohongshu.com")) return null
        val noteId = XHS_NOTE_PATH.find(path)?.groupValues?.get(1)
        return noteId?.let { "xhs-note:$it" } ?: "xhs-url:${match.target}"
    }

    /** 淘宝和闲鱼共用的短域只有在真实落到闲鱼站点后才能重新归类。 */
    fun redirectedMatch(sourceTarget: String, resolvedUrl: String): Match? {
        if (!isSharedTaobaoShortLink(sourceTarget)) return null
        val resolved = runCatching { java.net.URI(resolvedUrl) }.getOrNull() ?: return null
        if (resolved.scheme != "https") return null
        if (hostLabel(resolved.host?.lowercase(java.util.Locale.ROOT), resolvedUrl)?.second != P_XIANYU) return null
        return match(resolvedUrl)?.takeIf { it.platform == P_XIANYU }
    }

    /** X 正文查询必须使用完整状态页身份，避免把伪装域名交给第三方接口。 */
    fun xStatusIdentity(url: String): Pair<String, String>? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.")
        if (uri.scheme !in setOf("http", "https") || host !in setOf("x.com", "twitter.com")) return null
        val path = Regex("""^/([A-Za-z0-9_]{1,15})/status/(\d{1,20})(?:/|$)""")
            .find(uri.path.orEmpty()) ?: return null
        return path.groupValues[1] to path.groupValues[2]
    }

    /** 无效商品分享落到商城首页时，不把首页的通用 OG 标题冒充商品内容。 */
    fun isPddHomepageFallback(sourceUrl: String, finalUrl: String): Boolean {
        val source = runCatching { java.net.URI(sourceUrl) }.getOrNull() ?: return false
        val final = runCatching { java.net.URI(finalUrl) }.getOrNull() ?: return false
        val hosts = setOf("mobile.yangkeduo.com", "yangkeduo.com", "p.pinduoduo.com", "pinduoduo.com")
        if (source.host?.lowercase(java.util.Locale.ROOT) !in hosts ||
            final.host?.lowercase(java.util.Locale.ROOT) !in hosts
        ) return false
        if (source.path !in setOf("/goods.html", "/goods2.html")) return false
        return final.path.isNullOrEmpty() || final.path == "/"
    }

    /**
     * 链接 → 岛上标题 + 平台：按 [HOST_RULES] 的顺序取第一个主机名（与路径前缀）吻合的。
     * 只看 URI 的主机名与路径：分享链接的查询参数里常嵌着别的平台 URL。
     */
    private fun hostLabel(host: String?, url: String): Pair<String, Platform>? {
        host ?: return null
        val path = runCatching { java.net.URI(url).rawPath }.getOrNull().orEmpty()
        return HOST_RULES.firstOrNull { RuleSet.hostRuleMatches(it, host, path) }?.let { it.label to builtin(it.platform) }
    }

    private val IDLEFISH_SHARE_MARKER = Regex("""【闲鱼】|我在闲鱼|打开闲鱼""")

    private fun idlefishShortLink(text: String, host: String?): Pair<String, Platform>? {
        if (host != "m.tb.cn" && host != "e.tb.cn") return null
        if (!IDLEFISH_SHARE_MARKER.containsMatchIn(text)) return null
        return "闲鱼链接" to P_XIANYU
    }

    private const val FALLBACK_URL_LABEL = "网页链接"

    /**
     * 快递单号的跳转目标候选链（2026-10-22 用户拍板，顺丰也一样）。顺序 = 优先级，
     * 展示侧取第一个「有人接得住」的（见 [PasscodeIsland.EXTRA_TARGET_ALTS]）：
     *  1. **负一屏快递详情**（[personalAssistantExpressTarget]）：系统自带，不用登录，
     *     不往任何账号写东西；数据由小米 / 快递100 提供。顺丰会先要求验证手机号。
     *  2. **菜鸟 App**（[cainiaoAppTarget]）：与系统复制直达对快递单号给的跳转同款链接。
     *     会把包裹导入菜鸟账号，所以不当链首。
     *  3. 支付宝菜鸟小程序（[cainiaoMiniProgramTarget]）。
     *  4. 快递100 网页兜底。
     *
     * 以前顺丰 / 京东链首是自家 App（`com.sf-express://`、`openapp.jdlogistic://virtual`），
     * 但只能进 App 首页、看不到这个单号，2026-10-22 起不再用。
     */
    fun expressTargetChain(number: String): List<String> =
        listOf(expressHeadTarget(number)) + expressAltTargets(number)

    internal fun expressHeadTarget(number: String): String = personalAssistantExpressTarget(number)

    internal fun expressAltTargets(number: String): List<String> =
        listOf(cainiaoAppTarget(number), cainiaoMiniProgramTarget(number), expressWebTarget(number))

    /** 负一屏（智能助理）快递详情页的组件；展示侧只放行这一个显式组件（见 [isAllowedIntentTarget]）。 */
    const val PA_EXPRESS_COMPONENT = "com.miui.personalassistant/.service.express.activity.ExpressActivity"

    /**
     * 负一屏快递详情：显式组件 + `express_start=express_detail` + `express={"order":"<单号>"}`。
     * 取证（2026-10-22，反编译 `ExpressActivity` / `ExpressDetailFragment` + 真机）：详情页把 `express`
     * 按快递条目 JSON 解析，单号字段名 `order`（别名 `orderNumber`）；不给承运商代码 `cpCode`
     * 也能查（走快递100 自动识别），所以只给单号。
     * 这不是 URL，用 `intent:` URI 承载，展示侧按白名单解析（不放行任意 Intent）。
     */
    fun personalAssistantExpressTarget(number: String): String {
        val json = "{\"order\":\"" + number.filter { it.isLetterOrDigit() } + "\"}"
        val enc = java.net.URLEncoder.encode(json, "UTF-8").replace("+", "%20")
        return "intent:#Intent;component=$PA_EXPRESS_COMPONENT;S.express_start=express_detail;S.express=$enc;end"
    }

    /** `intent:` 目标只放行负一屏快递详情（规则文件的模板本来就不许写 intent，这是展示侧的第二道门）。 */
    fun isAllowedIntentTarget(target: String): Boolean =
        target.startsWith("intent:#Intent;component=$PA_EXPRESS_COMPONENT;")

    /**
     * 菜鸟 App 物流详情：系统复制直达对快递单号给出的原生跳转（2026-10-22 引擎日志实录），
     * 带小米「复制单号」的来源参数。
     */
    fun cainiaoAppTarget(number: String): String =
        "cainiao://startapp/logistic?mailNo=$number&querySourceId=68719476759" +
            "&bizId=xiaomifuzhidanhao&from=xiaomifuzhidanhao&bizScene=fuzhizhida"

    /**
     * 支付宝菜鸟裹裹小程序 + 单号（`appId` 是公开的小程序 id）。
     * ⚠️ 参数**必须编码进 `page`**，写成独立的 `&query=mailNo=…` 实测丢参。
     */
    fun cainiaoMiniProgramTarget(number: String): String =
        "alipays://platformapi/startapp?appId=$CAINIAO_MINI_APP_ID&page=" +
            "pages%2Flogistic%2Flogistic%3FappName%3DGUOGUO%26mailNo%3D" + number

    /** 链尾：快递100 m 站（单号查询是公开服务，选它不引 SDK、不写死 App 包名）。 */
    internal fun expressWebTarget(number: String) = "https://m.kuaidi100.com/result.jsp?nu=$number"

    /** 京东快递官方 H5 查件页（`https://3.cn/363a-qzx` 分享链 302 的落点，真机取证）。 */
    private const val JD_EXPRESS_PAGE = "https://kd.jd.com/express/"

    /** 顺丰官方查件页（设备实测 200 / 22KB；`m.sf-express.com` 那条自签证书走不通）。 */
    private const val SF_EXPRESS_PAGE = "https://www.sf-express.com/chn/sc/waybill"

    /** 顺丰速运小程序（真机取证）。 */
    private const val SF_MINI_APP_ID = "2019050764321917"

    /** 京东快递小程序（真机取证）。 */
    private const val JD_EXPRESS_MINI_APP_ID = "2021004107678121"

    private const val CAINIAO_MINI_APP_ID = "2021001141626787"

    /**
     * 百度网盘的 App scheme（2026-10-06 真机取证）。
     *
     * **为什么是 target 而不是 alts**：实测 `com.baidu.netdisk` **没有声明** `pan.baidu.com`
     * ——它的 App Links 只验证了 `snsyun.baidu.com`，`dumpsys package` 里 `pan.baidu.com`
     * 的匹配数是 **0**。也就是说 https 目标一定由浏览器接住（实测落到 `mark.via.gp`），
     * 放在 `alts` 里的 scheme 永远轮不到（候选链首个能解析的就停）。
     *
     * `baiduyun://` / `bdnetdisk://` 都解析到**单一目标**
     * `com.baidu.netdisk/.ui.WapLauncherActivity`（对比：`quark://`、`thunder://` 会落到
     * 系统的应用选择器，所以那两个不能用这条路）。
     *
     * ⚠️ 只在**取到像号的分享码**时才换（见 [match]）：scheme 目标的 URL 身份算不出来，
     * 不设门的话不同分享会撞成同一条通知。拉起来之后 App 是否自己识别剪贴板**待真机验证**。
     */
    private const val BAIDU_PAN_SCHEME = "baiduyun://"

    /**
     * 一条口令规则（由 passcode_rules.json 的 passcodes 转来，模板与白名单见 [RuleCapabilities]）。
     * [anchored] = true 时正则自带 `^…$`。[core] 的第一个参数是**剪贴板整段文本**（标题行提取需要上下文）。
     */
    private class Rule(
        val id: String,
        val label: String,
        val anchored: Boolean,
        val regex: Regex,
        val requiresContext: Boolean = false,
        val platform: Platform? = null,
        val core: (String, MatchResult) -> String? = { _, _ -> null },
        /** 目标候选链的备选（[target] 之后的兜底，顺序即优先级）。 */
        val alts: (MatchResult) -> List<String> = { emptyList() },
        /** 形态命中后的二次判据（校验位 / 上下文）；不通过就试同一段文本里的下一处命中。 */
        val accept: (String, MatchResult) -> Boolean = { _, _ -> true },
        val target: (MatchResult) -> String,
    )

    /**
     * URL 规则单独放：它在顺序上第一且**命中即独占**（return，不再往下），
     * 与 [RULES] 的「逐条尝试」语义不同。
     *
     * 字符类不含 CJK 与空白：分享文案里「https://xxx 复制此链接…」会在空白处
     * 正确截断，不会把尾部的中文说明吞进 URL。
     */
    private val URL_REGEX = Regex("""https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%\-]+""")

    private val HUPU_ARTICLE_PATH = Regex("""^/bbs(?:-share)?/([0-9]{6,12})\.html/?$""")
    private val DONGQIUDI_ARTICLE_PATH = Regex("""^/articles?/([0-9]{5,12})(?:\.html)?/?$""")

    /**
     * 贴吧帖子 `/p/<tid>`。官方贴吧**不认网页地址**（只认自家 scheme），帖子直达走
     * `com.baidu.tieba://unidispatch/pb?tid=<tid>`（2026-10-22 真机：进 PbActivity 帖子页；
     * 贴吧 Lite 也认这个 scheme，长按「打开方式」里两个都在）。
     */
    private val TIEBA_THREAD_PATH = Regex("""^/p/([0-9]{6,12})/?$""")

    /**
     * 微博单条：`weibo.com/<uid>/<bid>`、`m.weibo.cn/detail|status/<id>`。真机（2026-10-23，微博 16.9.1）：
     * 这些 https 地址**微博 App 不认领**（只有浏览器带 BROWSABLE），点「打开」会先进浏览器、再由网页跳回微博 ——
     * 用户报「有些时候会跳转再到正文」。微博认的是 `sinaweibo://detail?mblogid=`（`.activityproxy.ProxyActivity`）。
     */
    private val WEIBO_POST_PATH = Regex("""^/(?:[0-9]{5,12}|detail|status)/([A-Za-z0-9]{8,20})/?$""")
    private val WEIBO_HOSTS = setOf("weibo.com", "www.weibo.com", "m.weibo.cn", "weibo.cn")

    /**
     * 这些平台的直达深链要**排在网页地址前面**：它们的 App 也接网页地址，但接的是**内置浏览器**
     * （微博 16.9.1 真机：`weibo.com/<uid>/<id>` 指定包名后落到 `.browser.WeiboBrowser`，先载网页版再跳正文 ——
     * 用户 2026-10-23 报「有些时候会跳转再到正文」）。其余平台仍是网页地址优先、深链兜底。
     */
    fun prefersAppTarget(platformPkg: String?): Boolean = platformPkg == P_WEIBO.pkg

    fun articleAppTargets(url: String, platformPkg: String?): List<String> {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return emptyList()
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return emptyList()
        if (uri.scheme?.lowercase(java.util.Locale.ROOT) !in setOf("http", "https") || uri.rawUserInfo != null) {
            return emptyList()
        }
        val path = uri.rawPath.orEmpty()
        val appTarget = when {
            platformPkg == P_HUPU.pkg && (host == "hupu.com" || host.endsWith(".hupu.com")) ->
                HUPU_ARTICLE_PATH.matchEntire(path)?.groupValues?.get(1)?.let { "huputiyu://bbs/topic/$it" }
            platformPkg == P_DONGQIUDI.pkg && (host == "dongqiudi.com" || host.endsWith(".dongqiudi.com")) ->
                DONGQIUDI_ARTICLE_PATH.matchEntire(path)?.groupValues?.get(1)?.let { "dongqiudi:///news/$it" }
            platformPkg == P_WEIBO.pkg && host in WEIBO_HOSTS ->
                WEIBO_POST_PATH.matchEntire(path)?.groupValues?.get(1)?.let { "sinaweibo://detail?mblogid=$it" }
            platformPkg == P_TIEBA.pkg && host == "tieba.baidu.com" ->
                TIEBA_THREAD_PATH.matchEntire(path)?.groupValues?.get(1)?.let { "com.baidu.tieba://unidispatch/pb?tid=$it" }
            else -> null
        }
        return listOfNotNull(appTarget)
    }

    private val QQ_MUSIC_SONG_URL = Regex(
        """^https?://y\.qq\.com/n/ryqq/songDetail/([A-Za-z0-9]{14})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun qqMusicSongTarget(mid: String): String {
        val songUrl = "https://y.qq.com/n/ryqq/songDetail/$mid"
        val params = java.net.URLEncoder.encode("""{"target":"1","url":"$songUrl"}""", "UTF-8")
        return "qqmusic://qq.com/ui/openUrl?p=$params"
    }

    private val RULES: List<Rule> = BuiltinRules.checked?.passcodes.orEmpty().map { p ->
        Rule(
            p.rule.id, p.rule.label, anchored = p.rule.anchored, regex = p.regex,
            requiresContext = p.rule.requiresContext,
            platform = p.rule.platform?.let(::builtin),
            core = { text, m -> p.core?.expand(text, m, RuleCapabilities.CORE_BUILDERS)?.ifEmpty { null } },
            alts = { m -> p.alts.mapNotNull { it.expand("", m, RuleCapabilities.ALT_BUILDERS) } },
            accept = { text, m -> p.accept?.invoke(text, RuleTemplate.defaultValue(m)) ?: true },
        ) { m -> p.target.expand("", m, RuleCapabilities.TARGET_BUILDERS).orEmpty() }
    }

    // ── 标题行提取（视觉定稿：分享类平台的核心内容是标题，不是口令/短链）──

    private val TITLE_BRACKET = Regex("""【([^【】]{2,48})】""")
    private val TITLE_QUOTE = Regex("""[「“]([^「」””]{2,48})[」”]""")
    /** 《书名/歌名》—— 网易云、豆瓣、微信读书这类分享体（探针实测网易云 core=null）。 */
    private val TITLE_ANGLE = Regex("""《([^《》]{1,48})》""")
    /** 京东分享体『标题』——内层可含【】（如『小米平板9 Pro【国家补贴】』），必须先于【】查。 */
    private val TITLE_QUOTE_CN = Regex("""『([^『』]{2,48})』""")
    /** 口令 token（标题清理用）：符号集与 taokouling 规则保持同一份。 */
    private val TAO_TOKEN = Regex("""[_$￥₳€£][0-9A-Za-z]{8,14}[_$￥₳€£]""")

    /** 新版口令 token（含结尾空白）：与 taokouling_new 规则同一形态，标题清理时剥掉。 */
    private val NEW_TAO_TOKEN = Regex("""\d[A-Za-z0-9]:/[0-9A-Za-z\u4e00-\u9fa5]{1,16}\s""")
    private val SHARE_NOISE = Regex(
        """(复制此链接|复制这条信息|复制这条消息|复制整段话|复制打开|打开手?淘|打开抖音|""" +
            """打开小红书|打开快手|浏览器打开|复制到浏览器|长按复制|手指长按|看看这个|快来看看)[^\dA-Za-z\u4e00-\u9fa5]*""",
    )
    /**
     * URL **之后**的分享话术起点：从这里截断（淘宝体：短链 → 标题 → 话术）。
     *
     * 末尾三个是**小红书专属尾部**（2026-10-06 用户提供的三条真实分享文案实测）：
     * `把内容复制好，打开【小红书】即可看到笔记详情。` / `存好内容，去【小红书】一睹为快~`。
     * 小红书的标题在链接**前面**，尾部是固定说明 —— 不在这里截断，说明文字就会被
     * 当成标题（旧实现把「存好内容 去【小红书】一睹为快」显示在大字行上）。
     */
    private val SHARE_TAIL = Regex(
        """(点击链接|浏览器打开|淘宝搜索|复制文案|复制此链接|复制打开|复制整段|或者|或用|长按|来【小红书】|""" +
            """把内容复制好|存好内容|一睹为快|去【小红书】)""",
    )
    private val LEADING_PRICE = Regex("""^[0-9]+(?:\.[0-9]+)?\s*""")

    /** 淘口令的固定分隔符 `/:/`（清理用，它不是内容）。 */
    private val TAO_SEP = Regex("""/:/""")

    /** 「复制打开淘宝」「点击查看京东」这类**动词 + 平台名**：整段剥掉。 */
    private val SHARE_VERB_TAIL = Regex(
        """(?:复制|点击)?(?:打开|查看|前往)(?:淘宝|天猫|京东|拼多多|抖音|小红书|快手|支付宝|闲鱼|美团|得物)""",
    )

    /**
     * **尾部话术**：URL 之后只跟了这些词时，那不是标题。
     *
     * 真实样本（2026-10-06 探针）：`知乎回答：如何评价… https://… 来源：知乎`
     * —— 旧实现把「来源：知乎」当成了标题。
     */
    private val TAIL_NOISE = Regex("""^(?:来源|出自|via|Via|著作权|版权|原文|查看更多|详情)[:：]?""")

    /**
     * **平台尾巴**：分享文案常在标题后接平台标注。
     *
     * 真实样本（2026-10-06 探针）：小红书的 `【周末去哪儿 | 小红书 - 你的生活指南】`
     * 旧实现把整串当标题；B 站的是 `【标题-哔哩哔哩】`（无空格）。
     */
    private val PLATFORM_SUFFIX = Regex(
        """\s*[|｜]\s*(?:小红书|哔哩哔哩|B站|微博|知乎|抖音|快手|贴吧|豆瓣|虎扑|懂球帝|网易云音乐|QQ音乐).*$""" +
            """|\s*[-—]\s*(?:哔哩哔哩|小红书|知乎|微博|豆瓣|贴吧).*$""",
    )

    /** **平台前缀**：`知乎回答：…`、`微博正文：…` 这类，整段剥掉。 */
    private val PLATFORM_PREFIX = Regex(
        """^(?:知乎(?:回答|问题|专栏|想法)?|微博(?:正文|视频)?|豆瓣|贴吧|虎扑|懂球帝|小红书|抖音|快手|哔哩哔哩|B站)[:：]\s*""",
    )

    /**
     * 【】里是**来源标签**而不是标题的词：淘宝/京东分享的固定前缀（【淘宝】7天无理由退货）。
     * 命中这些词的括号段跳过，找后面的真标题。
     */
    private val PLATFORM_TAG_WORDS = setOf(
        "淘宝", "天猫", "京东", "拼多多", "美团", "外卖", "大众点评", "点评", "抖音", "小红书",
        "快手", "微博", "知乎", "B站", "哔哩哔哩", "分享", "优惠", "团购", "电影", "酒店", "机票", "福利",
        "虎扑", "懂球帝", "豆瓣", "微信", "闲鱼", "得物", "饿了么", "芒果TV", "酷狗音乐", "酷我音乐",
        "什么值得买",
        "今日头条", "西瓜视频", "斗鱼", "虎牙", "喜马拉雅", "小宇宙", "微信读书", "唯品会",
    )

    /**
     * 【】里是不是**来源标签**（不是标题）。
     *
     * 除了精确匹配平台名，还要认 `小红书app` / `抖音APP` 这类写法 —— 真实样本
     * （2026-10-06 用户提供的分享文案）：`点击链接或者复制本条信息打开【小红书app】查看`
     * 旧实现把「小红书app」当成了笔记标题。
     */
    private fun isPlatformTag(tag: String): Boolean =
        tag in PLATFORM_TAG_WORDS ||
            tag.removeSuffix("app").removeSuffix("APP").trim() in PLATFORM_TAG_WORDS

    /** 标题**开头**的平台标签：`【小红书】UGREEN…` → `UGREEN…`。 */
    private val LEADING_PLATFORM_TAG = Regex("""^【([^】]{1,14})】\s*""")

    /**
     * 电商分享的**促销外壳**：闲鱼体 `快来捡漏【商品名】` —— 外壳是平台话术，【】里才是商品名。
     *
     * 真实样本（2026-10-06 用户提供）：
     * `【闲鱼】https://m.tb.cn/… 「快来捡漏【个人自用华为Pura X，黑色12+512G国行，外屏官方换】」`
     * 旧实现把「快来捡漏【…】」整串显示在大字行上，24 字预算里前 4 个字被话术吃掉。
     * ⚠️ 只登记**验证过**的这一种；见到别的促销外壳再加。
     */
    private val PROMO_WRAPPER = Regex("""^(?:快来捡漏|速来捡漏)""")

    /**
     * 从分享文案里提取标题行。纯函数、零数据表。优先级按真实分享样本（2026-09-25）：
     *  ① 『标题』 —— 京东体（内层可含【】）；
     *  ② 「标题」/“标题” —— 引号体（美团体：分享给你美团外卖的『…』也在 ①）；
     *  ③ 【标题】 —— 抖音/微博/B站分享格式；【淘宝】【京东】这类平台标签跳过；
     *  ④ URL 之后的文案 —— 淘宝体（短链后跟标题，再跟话术），剥 token 与话术；
     *  ⑤ URL 之前的文案 —— 原逻辑兜底。
     * 提不出来（纯口令无标题）返回 null —— 展示侧回落平台名或预加载，不难看。
     */
    fun extractTitle(text: String): String? {
        val t = text.trim()
        TITLE_QUOTE_CN.find(t)?.let { return polish(it.groupValues[1]) }
        TITLE_QUOTE.find(t)?.let { return polish(it.groupValues[1]) }
        TITLE_BRACKET.findAll(t)
            .firstOrNull { !isPlatformTag(it.groupValues[1].trim()) }
            ?.let { return polish(it.groupValues[1]) }
        // 《歌名/书名》放在【】**之后**：避免「【《三体》读后感】」这类真标题被截短。
        TITLE_ANGLE.find(t)?.let { return polish(it.groupValues[1]) }
        afterUrlTitle(t)?.let { return polish(it) }
        val httpIdx = t.indexOf("http")
        val before = (if (httpIdx >= 0) t.substring(0, httpIdx) else t)
            .replace(TAO_TOKEN, " ")
            .replace(NEW_TAO_TOKEN, " ")
            // `/:/` 是淘口令的固定分隔符，「复制打开淘宝」是动词+平台名 —— 都不是标题。
            // 剥干净后整段只剩它们 → 下一步 firstOrNull 拿不到内容 → 返回 null，
            // 展示侧回落「打开淘宝」（视觉定稿：不显示乱码口令）。
            .replace(TAO_SEP, " ")
            .replace(SHARE_VERB_TAIL, " ")
        val line = SHARE_NOISE.replace(before, " ")
            .split('\n')
            .firstOrNull { it.isNotBlank() }
            ?.replace(LEADING_PRICE, "")
            ?.trim()
            ?: return null
        return polish(line)
    }

    /**
     * URL 之后的候选文案（淘宝体标题在短链后面）。处理：
     *  ① 截断到分享话术起点（「点击链接直接打开 或者 淘宝搜索直接打开」）；
     *  ② 按空白/逗号分词，只留**含 CJK** 的片段 —— tk=值、验证码「CZ0557」、
     *     价格数字都是拉丁字符，过滤即剥净。
     */
    private fun afterUrlTitle(text: String): String? {
        val m = URL_REGEX.find(text) ?: return null
        val after = text.substring(m.range.last + 1).trim()
        if (after.isEmpty()) return null
        val cut = SHARE_TAIL.find(after)?.range?.first ?: after.length
        val parts = after.take(cut)
            .split(Regex("""[\s,，、]+"""))
            .filter { it.isNotEmpty() && it.any { c -> c in '\u4e00'..'\u9fa5' } }
            // URL 之后只跟了「来源：知乎」这类话术时**不是标题** —— 返回 null 让上层
            // 回落到 URL **之前**的文案（真实样本见 TAIL_NOISE 的注释）。
            .filterNot { TAIL_NOISE.containsMatchIn(it) }
        if (parts.isEmpty()) return null
        return parts.joinToString(" ")
    }

    /** 收尾：剥平台外壳、去尾部/首部分隔符；太短或纯价格/乱码的丢弃；超长截断。 */
    private fun polish(s: String): String? {
        fun edges(v: String) = v.trim()
            // 首部的句读也是噪声：真实样本 `。？你敢信这是ai生成图 …` 起手就是标点
            .trimStart('。', '？', '?', '！', '!', '，', ',', '、', '；', ';', '：', ':', '~')
            .trimEnd('：', ':', '-', '—', '|', '，', ',', '。', '~').trim()
        // 平台尾巴与前缀都是**文案外壳**，先剥掉再判长度与截断 —— 否则
        // 「周末去哪儿 | 小红书 - 你的生活指南」会整串占满大字行（2026-10-06 探针实测）。
        var x = edges(PLATFORM_PREFIX.replace(edges(PLATFORM_SUFFIX.replace(edges(s), "")), ""))
        // `【小红书】UGREEN…` 这种**开头**的平台标签同样要剥（它不是标题的一部分）
        x = edges(LEADING_PLATFORM_TAG.replace(x) { m ->
            if (isPlatformTag(m.groupValues[1].trim())) "" else m.value
        })
        // `快来捡漏【商品名】`：先剥促销外壳，再脱掉**整段被【】包住**的外层（只脱一层，
        // 且只在没有嵌套【】时 —— 避免动到「【周末去哪儿】」这种本就合法的方括号标题）
        x = edges(PROMO_WRAPPER.replace(x, ""))
        if (x.length > 2 && x.startsWith("【") && x.endsWith("】") &&
            !x.substring(1, x.length - 1).contains('【')
        ) {
            x = edges(x.substring(1, x.length - 1))
        }
        if (x.isEmpty()) return null
        val hasCjk = x.any { it in '\u4e00'..'\u9fa5' }
        // 「9.9」「aB3xYz12」这类价格/口令碎片不该当标题（至少要像一句话）
        if (!hasCjk && x.length < 8) return null
        if (hasCjk && x.length < 2) return null
        if (x.length > 24) {
            // 别把**代理对**（emoji 占两个 char）截成半个 —— 会渲染成「?」
            // （2026-10-06 用例抓到的真实样本：`UGREEN 绿联 · … 支架 😆`）
            var cut = 24
            if (Character.isHighSurrogate(x[cut - 1])) cut -= 1
            x = x.take(cut).trimEnd() + "…"
        }
        return x
    }

    /**
     * 匹配入口。**纯函数**：不碰时钟、不碰剪贴板、不分配广播 —— 全部由调用方做。
     */
    /** 从实际规则生成支持列表；新增规则会自动进入应用选择和原生接入。 */
    fun supportedPlatforms(): List<Platform> =
        (HOST_RULES.map { builtin(it.platform) } + RULES.mapNotNull { it.platform } + listOf(P_GENERIC, P_EXPRESS))
            .distinctBy { it.pkg ?: it.name }

    /** 裸口令/内容编号能力独立于网址能力，供原生许可页分类使用。 */
    fun commandPlatforms(): List<Platform> = RULES.mapNotNull { it.platform }.distinctBy { it.pkg ?: it.name }

    /** 网址能力只取实际主机规则，不借口令平台登记来推断支持。 */
    fun urlPlatforms(): List<Platform> = HOST_RULES.map { builtin(it.platform) }
        .distinctBy { it.pkg ?: it.name }

    fun match(text: String, options: Options = Options()): Match? {
        val t = text.trim()
        if (t.length < 2 || t.length > MAX_LEN) return null

        // ① URL / 短链：命中即独占（返回，不再检查裸口令 —— URL 里的 BV 已由链接表达）
        URL_REGEX.find(t)?.let { m ->
            val url = m.value.trimEnd('.', ',', ')', ';', ':')
            QQ_MUSIC_SONG_URL.matchEntire(url)?.let { song ->
                return Match(
                    "qq_music_song", "QQ音乐链接", url, P_QQ_MUSIC,
                    alts = listOf(qqMusicSongTarget(song.groupValues[1])),
                )
            }
            val host = urlHost(url)
            val known = idlefishShortLink(t, host) ?: hostLabel(host, url)
            // 未知平台的链接：公网域名与本机 / 内网地址弹岛（后者交给浏览器，用户 2026-10-22 定），
            // 只有后缀不存在的主机名（`https://a.b`）不弹。公共后缀表没加载时 isPublicHost 返回 null → 放行。
            val h = host.orEmpty()
            val genericOk = options.genericUrl &&
                (PublicSuffix.isPublicHost(h) != false || PublicSuffix.isLocalHost(h))
            if (known != null || genericOk) {
                val platform = known?.second ?: P_GENERIC
                // 核心内容：先从链接里抽「可复制的号」（B 站 BV/av/cv、AcFun 的 ac、
                // niconico 的 sm、海外视频 ID…，与裸号同一逻辑）；抽不到再看是不是
                // 分享文案（含未知短域）抽标题行；都没有 → null，展示侧预加载标题补
                val core = known?.let { contentCore(it.second, url) }
                    ?: if (platform in SHARE_PLATFORMS || platform == P_GENERIC) extractTitle(t) else null
                // 百度网盘：App 接不住自家 https（实测声明数 0），https 目标一定被浏览器
                // 接住，所以 scheme 必须当 target（理由见 [BAIDU_PAN_SCHEME]）。门是
                // `isIdLike(core)`：只有取到「像号」的分享码时才换 —— scheme 目标的 URL
                // 身份算不出来，不设门就会让不同分享撞成同一条通知（单测钉住）。
                val panApp = platform == P_BAIDUPAN && core != null && PasscodeIsland.isIdLike(core)
                return Match(
                    "url", urlLabel(known), if (panApp) BAIDU_PAN_SCHEME else url, platform, core,
                    if (panApp) listOf(url) else articleAppTargets(url, platform.pkg),
                )
            }
            // 非白名单域且 generic 关着 → 这条 URL 不弹，继续往下试裸口令/内容号
        }

        // ② 号形态 / 口令 / 内容号：RULES 表序即优先序
        //    （整段锚定的规则写在表头，所以一次遍历就能保持先后关系）
        for (r in RULES) {
            if (r.requiresContext && !options.numericTracking) continue
            val m = r.regex.findAll(t).firstOrNull { r.accept(t, it) } ?: continue
            if (r.requiresContext && !t.containsContextWord()) continue
            return Match(r.id, r.label, r.target(m), r.platform, r.core(t, m), r.alts(m))
        }
        return null
    }

    /** ④门控判据：同文出现任一上下文词。 */
    private fun String.containsContextWord(): Boolean =
        PasscodeRules.CONTEXT_WORDS.any { contains(it) }

    /** URL 的岛上标题：已知域用白名单名，其余按 generic 兜底。 */
    private fun urlLabel(known: Pair<String, Platform>?): String = known?.first ?: FALLBACK_URL_LABEL

    /**
     * 链接的核心字段 = **可复制的号**（与裸号规则同一逻辑，得出来的号也完全一样）。
     *
     * 形态依据各平台公开 URL 约定（yt-dlp extractor 体系同源）：
     *  - B 站：`/video/BV…`、`/video/av…`、`/read/cv…`；
     *  - niconico：`/watch/sm…`（`sm|nm|so`，短域 nico.ms 同形）；
     *  - AcFun：`/v/ac…`；pixiv：`/artworks/…` 或 `illust_id=`；
     *  - YouTube：`watch?v=` / `youtu.be/` / `/shorts/` / `/live/` / `/embed/` 后的 11 位 ID；
     *  - X：`/status/` 后的 Snowflake ID（twitter.com 与 x.com 同 ID）；
     *  - Steam：`/app/` 后的 AppID；Amazon：`/dp/` 后的 ASIN；eBay：`/itm/` 后的 ItemID；
     *  - Reddit：`/comments/` 后的 base36 post ID；GitHub：owner/repo。
     * 提不出来 → null（展示侧回落预加载的页面标题）。
     */
    private fun contentCore(p: Platform, url: String): String? = when (p) {
        // B 站：视频号（BV/av）、专栏（cv）、以及**动态/图文**（`t.bilibili.com/<号>`、
        // `/dynamic/`、`/opus/`）—— 探针实测动态原来 core=null。
        P_BILI -> firstId(
            url,
            Regex("""BV[0-9a-zA-Z]{10}"""),
            // 番剧 / 课程 ep、ss，番剧条目 md，音频 au、合辑 am（yt-dlp bilibili.py 各 IE 的 _VALID_URL）
            Regex("""(?<=/play/)(?:ep|ss)\d+"""),
            Regex("""(?<=/media/)md\d+"""),
            Regex("""(?<=/audio/)a[um]\d+"""),
            Regex("""cv\d+"""),
            Regex("""av\d{6,}"""),
            Regex("""(?:t\.bilibili\.com/|/dynamic/|/opus/)(\d{6,20})"""),
        )
        // lv = 直播（yt-dlp NiconicoLiveIE）
        P_NICO -> firstId(url, Regex("""(?:sm|nm|so|lv)\d+"""))
        // aa = 番剧（yt-dlp AcFunBangumiIE）
        P_ACFUN -> firstId(url, Regex("""ac\d+"""), Regex("""(?<=/bangumi/)aa\d+"""))
        // ── 以下几条的形态参考 yt-dlp 对应 IE 的 _VALID_URL（Unlicense），实现自写 ──
        P_TIKTOK -> firstId(url, Regex("""/video/(\d{8,20})"""))
        // 腾讯视频：剧集页 `/x/cover/<剧>/<集>.html` 取集号；单页 `/x/page/<号>`；只有剧时取剧号
        P_TT_VIDEO -> firstId(
            url,
            Regex("""/x/cover/\w+/(\w+)\.html"""),
            Regex("""/x/page/(\w+)\.html"""),
            Regex("""/x/cover/(\w+)\.html"""),
        )
        P_YOUKU -> firstId(
            url,
            Regex("""/v_show/id_([A-Za-z0-9]+)"""),
            Regex("""/player\.php/sid/([A-Za-z0-9]+)"""),
            Regex("""/show/id_([0-9a-z]+)"""),
        )
        P_IQIYI -> firstId(url, Regex("""/(v_[0-9a-z]+)\.html"""))
        P_PIXIV -> firstId(url, Regex("""/artworks/(\d+)"""), Regex("""illust_id=(\d+)"""))
        P_YOUTUBE, P_YT_MUSIC ->
            firstId(url, Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})"""))
        P_X -> firstId(url, Regex("""/status/(\d{5,20})"""))
        P_STEAM -> firstId(url, Regex("""/app/(\d{2,10})"""))
        P_AMAZON -> firstId(url, Regex("""/dp/([A-Z0-9]{10})""", RegexOption.IGNORE_CASE))
        P_EBAY -> firstId(url, Regex("""/itm/(?:[^/?]*-)?(\d{9,13})"""))
        P_REDDIT -> firstId(url, Regex("""/comments/([a-z0-9]{4,10})""", RegexOption.IGNORE_CASE))
        P_GITHUB -> firstId(url, Regex("""github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)"""))
        // 百度网盘分享码：`/s/<码>` 与旧形态 `?surl=<码>`。它既是岛上要显示的核心
        // （可复制的号），也是**内容身份的来源** —— 网盘这条的 target 会换成 App
        // scheme，URL 身份无从算起。
        P_BAIDUPAN -> firstId(
            url,
            Regex("""/s/([0-9A-Za-z_-]{4,})"""),
            Regex("""[?&]surl=([0-9A-Za-z_-]{4,})"""),
        )
        // 抖音长链：号在路径里（探针实测 `/video/6961737553342991651` 原来 core=null）。
        // 抽到号之后，短链展开得到的长链与本条**合并成同一内容身份**（同一作品的两种写法）。
        P_DOUYIN -> firstId(url, Regex("""/(?:video|note|slides)/(\d{10,20})"""))
        // 网易云歌曲 ID：它同时修掉一个身份问题 —— 分享链常带 `?userid=`，两个用户分享同一首歌
        // 的 URL 不同；有了内容号就合并成一条（「保留查询参数」的本意是区分内容，不是区分分享者）。
        //
        // ⚠️ 歌单 / 专辑 / 歌手 / MV / 电台也是 `?id=<数字>`（yt-dlp neteasemusic.py 各 IE）。
        // 以前一律取裸数字，`song?id=123` 与 `playlist?id=123` 会被当成**同一条内容**去重。
        // 歌曲保持裸数字（与旧身份兼容），其余类型带上类型前缀。
        P_NETEASE -> neteaseId(url)
        // 网盘分享码：夸克/迅雷与百度同形（`/s/<码>`）。⚠️ 这个形态来自分享链的通行约定，
        // **还没有真实样本逐条验证**，错了也只是抽不到号（与改动前一样），不会更差。
        P_QUARKPAN, P_XUNLEI -> firstId(url, Regex("""/s/([0-9A-Za-z_-]{6,})"""))
        else -> null
    }?.let { id ->
        // owner/repo 这类可能超长的截断到展示预算内
        if (id.length > 24) id.take(24) + "…" else id
    }

    private val NETEASE_SONG = Regex("""(?:/song/|\bsong\?(?:[^#]*&)?id=)(\d+)""")
    private val NETEASE_KIND = Regex(
        """\b(album|artist|playlist|toplist|mv|djradio|program|dj)\?(?:[^#]*&)?id=(\d+)""",
    )

    private fun neteaseId(url: String): String? {
        NETEASE_SONG.find(url)?.let { return it.groupValues[1] }
        NETEASE_KIND.find(url)?.let { return it.groupValues[1] + it.groupValues[2] }
        // 旧形态兜底（如 `m/song?id=` 以外的未知路径）：行为与改动前一致
        return firstId(url, Regex("""[?&]id=(\d+)"""))
    }

    /**
     * 按顺序试若干正则，取第一个命中：**有捕获组就用组 1，没有就用整段**。
     * 这样「号本身带前缀」（ac4725103 / sm39366587 / cv987654）与「只要数字」
     * （pid / ASIN / AppID）两种核心形态能共用一条取值路径。
     */
    private fun firstId(url: String, vararg regexes: Regex): String? {
        for (r in regexes) {
            val m = r.find(url) ?: continue
            val g1 = m.groupValues.getOrNull(1).orEmpty()
            val v = if (g1.isNotEmpty()) g1 else m.value
            if (v.isNotEmpty()) return v
        }
        return null
    }
}
