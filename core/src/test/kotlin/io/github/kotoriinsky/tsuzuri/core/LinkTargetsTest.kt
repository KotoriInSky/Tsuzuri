package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LinkTargets] 的校验表、规范化与显示名。
 *
 * 这一层错法的两种方向**都不报错**：
 *
 *  - **放行太多**：一条显式/本地 URI 被写进配置，SystemUI（uid 1000）去启动它 ——
 *    等于给配置文件开了一个任意启动口子，而且**没有任何日志会说这件事发生了**；
 *  - **拒绝太多**：合法的深链被拒，用户看到一句「格式不对」但不知道哪不对，
 *    反复试同一串字符（这是要消灭的「静默失败」的另一种形态）。
 *
 * 所以下面**逐条**钉住类注释里那张表的每一行，并额外钉三条不变量：
 * ① 存量两类项永远不会被认成链接；② 通过校验的串一定能被规范化成一个稳定形式；
 * ③ 规范化是幂等的（写入侧会反复调它）。
 */
class LinkTargetsTest {

    // ── 前缀识别：先认前缀，再按 `/` 拆 ─────────────────────────────────

    @Test
    fun prefixIsDetectedOnlyAtTheStartOfTheWholeItem() {
        assertTrue(LinkTargets.isLink("link:weixin://scanqrcode"))
        // 大小写不敏感：用户手改配置写成 `Link:` 时，大小写敏感会让它被当成
        // 「一个叫 Link:weixin: 的包」丢掉，日志只有一句「不可启动」
        assertTrue(LinkTargets.isLink("LINK:weixin://x"))
        assertTrue(LinkTargets.isLink("Link:weixin://x"))
        // 前缀后面什么都没有也算「是链接项」（校验会判它 EMPTY）——
        // 判定的是「这条项自称是链接」，不是「它合法」
        assertTrue(LinkTargets.isLink("link:"))
        // 短于前缀 / 没有冒号：都不是
        assertFalse(LinkTargets.isLink("lin"))
        assertFalse(LinkTargets.isLink("link"))
        assertFalse(LinkTargets.isLink(""))
    }

    @Test
    fun existingFormsAreNeverSeenAsLinks() {
        // **这条是向后兼容的防线。** `:` 不可能是合法包名的一部分（Java 包名规范），
        // 所以任何存量的合法项都不可能以 `link:` 开头。退化的后果是
        // 「用户清单里的应用/快捷方式被当成链接去启动」，而这在编译期与日志里全是正常的
        for (raw in listOf(
            "com.tencent.mm",
            "com.tencent.mm/launch_type_scan_qrcode",
            "com.eg.android.AlipayGphone/1001",
            "com.foo/a/b",
        )) {
            assertFalse(LinkTargets.isLink(raw), raw)
            assertEquals(ShortcutEntries.Kind.APP, ShortcutEntries.parse("com.tencent.mm").kind)
        }
        // 快捷方式 id 里出现 `link:` 不算链接项：前缀只认整串开头那一个
        val sc = ShortcutEntries.parse("com.foo/link:bar")
        assertEquals(ShortcutEntries.Kind.SHORTCUT, sc.kind, "id 里的 link: 不该改变分类")
        assertEquals("com.foo", sc.pkg)
        assertEquals("link:bar", sc.id)
    }

    @Test
    fun linkUriIsTakenAfterThePrefixAndTrimmed() {
        assertEquals("weixin://scanqrcode", LinkTargets.uriOf("link:weixin://scanqrcode"))
        assertEquals("weixin://scanqrcode", LinkTargets.uriOf("link:  weixin://scanqrcode  "))
        // 非链接项的输入：原样 trim 返回（调用方本该先用 isLink 分流，这里只是兜底）
        assertEquals("com.tencent.mm", LinkTargets.uriOf("  com.tencent.mm "))
    }

    // ── 校验：放行的形式 ───────────────────────────────────────────────

    /** 本特性存在的理由就是这一类（真实 scheme，不依赖应用发布快捷方式）。 */
    private val accepted = listOf(
        "weixin://scanqrcode",
        "alipays://platformapi/startapp?appId=20000056",
        "weixin://dl/business/?t=abc",
        "https://example.com/a/b?c=d#e",
        "http://miui.com",
        "market://details?id=com.tencent.mm",
        "content://media/external/images/media/1",
        "mailto:someone@example.com",
        // scheme 允许 `+` `-` `.`（RFC 3986）与数字（首字符必须字母）
        "my-app.v2+x://open",
        "abc1://x",
    )

    @Test
    fun implicitUriWithSchemeIsAccepted() {
        for (uri in accepted) {
            assertNull(LinkTargets.check(uri), "该放行的被拒了：$uri")
        }
    }

    // ── 校验：拒绝的形式（逐条对应类注释里的那张表）────────────────────

    @Test
    fun emptyInputIsRejected() {
        assertEquals(LinkTargets.Rejection.EMPTY, LinkTargets.check(""))
        // 前缀后面只有空白：trim 之后是空的（`link:   ` 是用户手滑的常见形态）
        assertEquals(LinkTargets.Rejection.EMPTY, LinkTargets.check(LinkTargets.uriOf("link:   ")))
        val r = LinkTargets.parse("link:   ")
        assertTrue(r is LinkTargets.Result.Rejected && r.reason == LinkTargets.Rejection.EMPTY)
    }

    @Test
    fun withoutSchemeIsRejected() {
        // 没有 scheme 的东西解析出来就是显式组件（`com.foo/.Bar`）或空协议相对地址
        assertEquals(LinkTargets.Rejection.NO_SCHEME, LinkTargets.check("weixin"))
        assertEquals(LinkTargets.Rejection.NO_SCHEME, LinkTargets.check("//host/path"))
        assertEquals(LinkTargets.Rejection.NO_SCHEME, LinkTargets.check("com.android.settings/.Settings"))
        assertEquals(LinkTargets.Rejection.NO_SCHEME, LinkTargets.check("com.tencent.mm/launch_type_scan_qrcode"))
    }

    @Test
    fun malformedOrEmptySchemeIsRejected() {
        assertEquals(LinkTargets.Rejection.BAD_SCHEME, LinkTargets.check("1abc://x"))
        assertEquals(LinkTargets.Rejection.BAD_SCHEME, LinkTargets.check("weixin:"))
        // 只写 `//`：去掉斜杠之后没有内容，与「什么都不写」是同一个结果
        assertEquals(LinkTargets.Rejection.BAD_SCHEME, LinkTargets.check("weixin://"))
        assertEquals(LinkTargets.Rejection.BAD_SCHEME, LinkTargets.check("%zz://x"))
        assertEquals(LinkTargets.Rejection.BAD_SCHEME, LinkTargets.check("-abc://x"))
    }

    @Test
    fun explicitAndSelectorFormsAreRejected() {
        // 这一类是**安全收口**的核心：点名组件/包 = 任意启动口子
        for (uri in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "intent:weixin://scanqrcode",
            "INTENT:weixin://scanqrcode",
            "weixin://scanqrcode#Intent;component=com.evil/.A;end",
            "weixin://scanqrcode;component=com.evil/.A",
            "weixin://scanqrcode;package=com.evil",
            "weixin://scanqrcode;S.com.evil/.A",
            "weixin://scanqrcode;i.flag=1",
            "weixin://scanqrcode;launchFlags=0x10000000",
            "weixin://scanqrcode;selector=com.evil/.A",
            "weixin://scanqrcode;action=android.intent.action.VIEW",
            "weixin://scanqrcode;category=android.intent.category.LAUNCHER",
            "weixin://scanqrcode;launchBounds=0,0,100,100",
        )) {
            assertEquals(LinkTargets.Rejection.EXPLICIT, LinkTargets.check(uri), uri)
        }
    }

    @Test
    fun localAndPackageAddressingSchemesAreRejected() {
        for (uri in listOf(
            "file:///sdcard/x.png",
            "File:///sdcard/x.png",
            "data:text/html,<b>x</b>",
            "javascript:alert(1)",
            "blob:https://example.com/abc",
            // `android-app://包名` 是点名一个包的形式，等价于 package=
            "android-app://com.evil",
            "android-app://com.evil/https/example.com",
        )) {
            assertEquals(LinkTargets.Rejection.UNSAFE_SCHEME, LinkTargets.check(uri), uri)
        }
    }

    @Test
    fun whitespaceAndControlCharsAreRejected() {
        // 裸空格必须百分号编码；换行/制表符是拼接注入的典型形态
        assertEquals(LinkTargets.Rejection.ILLEGAL_CHARS, LinkTargets.check("weixin://a b"))
        assertEquals(LinkTargets.Rejection.ILLEGAL_CHARS, LinkTargets.check("weixin://a\tb"))
        assertEquals(LinkTargets.Rejection.ILLEGAL_CHARS, LinkTargets.check("weixin://a\nb"))
        assertEquals(LinkTargets.Rejection.ILLEGAL_CHARS, LinkTargets.check(" "))
    }

    @Test
    fun tooLongIsRejected() {
        val long = "https://example.com/" + "a".repeat(LinkTargets.MAX_LENGTH)
        assertEquals(LinkTargets.Rejection.TOO_LONG, LinkTargets.check(long))
        // 边界：正好等于上限的放行（宁可只拒「超过」的，别在边界上多拒一条）
        val exact = "https://e.co/" + "a".repeat(LinkTargets.MAX_LENGTH - "https://e.co/".length)
        assertEquals(LinkTargets.MAX_LENGTH, exact.length)
        assertNull(LinkTargets.check(exact))
    }

    @Test
    fun rejectionIsCheckedBeforeTheSchemeAllowList() {
        // 顺序有意义：`file:` 这种既「不安全」又可能夹带显式参数时，
        // 报出来的原因必须是**先判的那个**（否则用户按提示改了一处还是错）
        assertEquals(
            LinkTargets.Rejection.EXPLICIT,
            LinkTargets.check("file:///x#Intent;component=com.evil/.A;end"),
        )
    }

    // ── 规范化：写入侧反复调用它 ───────────────────────────────────────

    @Test
    fun normalizeRoundTripsAndIsIdempotent() {
        for (uri in accepted) {
            val stored = LinkTargets.normalize("link:" + uri)
            assertNotNull(stored, "该能规范化：$uri")
            // 存进去的必须能被原样读出来（`uriOf` 就是读取侧做的事）
            assertEquals(uri, LinkTargets.uriOf(stored), uri)
            assertTrue(stored.startsWith(LinkTargets.PREFIX), stored)
            // 幂等：写入侧会先规范化再写，读回来再规范化必须不变
            assertEquals(stored, LinkTargets.normalize(stored), uri)
            // 规范化之后一定过校验（否则就是「写进去的读回来点不动」）
            assertEquals(LinkTargets.Result.Ok(uri), LinkTargets.parse(stored))
        }
    }

    @Test
    fun normalizeReturnsNullForRejectedInput() {
        for (raw in listOf("link:", "link:weixin", "link:intent://x#Intent;end", "link:file:///x", "")) {
            assertNull(LinkTargets.normalize(raw), raw)
        }
    }

    @Test
    fun normalizeAlwaysWritesTheCanonicalLowercasePrefix() {
        // 用户/手改配置写成 `LINK:` 也照收，但写回去的必须是小写规范形式 ——
        // 否则同一个链接会有两种拼法，去重（`OrderedItems.encode` 的 distinct）就失效，
        // 扇子里会出现两个一模一样的项
        assertEquals("link:weixin://scanqrcode", LinkTargets.normalize("LINK:weixin://scanqrcode"))
        assertEquals("link:weixin://scanqrcode", LinkTargets.normalize("link:weixin://scanqrcode"))
    }

    // ── 显示名 ────────────────────────────────────────────────────────

    @Test
    fun fallbackLabelUsesTheHostThenTheScheme() {
        assertEquals("scanqrcode", LinkTargets.fallbackLabel("weixin://scanqrcode"))
        assertEquals("example.com", LinkTargets.fallbackLabel("https://example.com/a?b=c"))
        assertEquals("details", LinkTargets.fallbackLabel("market://details?id=x"))
        assertEquals("media", LinkTargets.fallbackLabel("content://media/external/images/media/1"))
        // 不透明形式（没有 `//`）：「第一段」就是冒号之后那一段
        assertEquals("someone@example.com", LinkTargets.fallbackLabel("mailto:someone@example.com"))
        assertEquals("scanqrcode", LinkTargets.fallbackLabel("weixin:scanqrcode"))
        assertEquals("weixin", LinkTargets.fallbackLabel("weixin://"))
    }

    @Test
    fun fallbackLabelIsNeverEmpty() {
        // 空标题在界面上就是一行看不见的东西（清单页要靠它显示、靠它删除）。
        // 故意连非法输入也覆盖：它们同样得有一个能显示的名字
        for (uri in accepted + listOf("", "weixin", "link:", "1abc://x", "://")) {
            assertTrue(LinkTargets.fallbackLabel(uri).isNotEmpty(), "空标题：\"$uri\"")
        }
    }

    @Test
    fun storedFormIsExactlyWhatTheUiWritesAndTheHookKeysOn() {
        // **跨模块的键契约**（这是本次接线唯一新增的耦合点，必须钉住）：
        // 界面写入的、hook 侧当作「单项图标覆盖键」的、清单页显示为副标题的，
        // 必须是**同一个字符串**。三处只要有一个字符不同（例如某一步顺手加了
        // 规范化/小写化），「给某一条链接单独换的图标」就永远不生效 ——
        // 而那种失效在日志里长得和「没声明覆盖」一模一样（所以它必须靠测试，不靠日志）。
        val stored = LinkTargets.stored("weixin://scanqrcode")
        assertEquals("link:weixin://scanqrcode", stored)
        // hook 侧合成时用的是 `ShortcutEntries.parse(...).raw`（FanTargets 的 Entry.key）
        assertEquals(stored, ShortcutEntries.parse(stored).raw)
        // 而覆盖键就是它（FanTarget.rawKey）——原样，未经第二次加工
        assertEquals(ShortcutEntries.Kind.LINK, ShortcutEntries.parse(stored).kind)
        assertEquals("weixin://scanqrcode", ShortcutEntries.parse(stored).linkUri)
        // 手改配置写成大写前缀时，**键也仍然是原样那一串**（我们不做隐式规范化）：
        // 规范化只发生在写入那一刻（`normalize`），已经躺在配置里的字符串不重写 ——
        // 否则界面显示一个键、覆盖存另一个键，用户「换图标没反应」就再也查不出来了
        val upper = "LINK:weixin://scanqrcode"
        assertEquals(upper, ShortcutEntries.parse(upper).raw)
        assertTrue(LinkTargets.isLink(upper))
        val upperUri = ShortcutEntries.parse(upper).linkUri
        assertEquals("weixin://scanqrcode", upperUri)
        assertNotNull(upperUri)
        // 前缀大小写不影响**校验**（校验只看 `:` 之后那一段），所以大写前缀的项照样能启动
        assertEquals(null, LinkTargets.check(upperUri))
    }

    @Test
    fun storedKeepsTheUriVerbatimApartFromSurroundingSpace() {
        // 「规范化」只去掉确定无意义的首尾空白：URI 各段语义由目标应用决定，
        // 我们改一个字符就可能把它改坏（例如把 `%2F` 解成 `/`）
        assertEquals("link:weixin://a%2Fb?x=1", LinkTargets.stored("  weixin://a%2Fb?x=1 "))
    }
}
