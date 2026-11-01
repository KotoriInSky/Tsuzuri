package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 口令引擎的钉子。
 *
 * 每条用例对应一种「错了会静默改变系统行为」的边界：锚定失守 = 随手一句话弹岛；
 * 顺序颠倒 = URL 里的 BV 弹两次；门控失守 = 订单号被当快递单号。
 */
class PasscodeRulesTest {

    private fun match(text: String, options: PasscodeRules.Options = PasscodeRules.Options()) =
        PasscodeRules.match(text, options)

    // ── ② 裸口令：锚定是硬边界 ──────────────────────────────────────────

    @Test
    fun `裸 BV 号命中`() {
        val m = match("BV1GJ411x7h7")
        assertNotNull(m)
        assertEquals("bili_bv", m.ruleId)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", m.target)
    }

    @Test
    fun `分享文案里夹着的 BV 号也能命中`() {
        // 2026-09-27 修订：整段锚定会让「从聊天记录里复制的分享文案」整类漏掉
        //（用户实测反馈的正是这种形态），改成边界匹配后必须命中
        val m = match("【这视频不错】BV1GJ411x7h7 复制打开B站")
        assertNotNull(m)
        assertEquals("bili_bv", m.ruleId)
        assertEquals("BV1GJ411x7h7", m.core)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", m.target)
        // 号后直接贴中文同样命中（CJK 在 Java 的 \b 里不是词字符，实测确认）
        assertEquals("bili_bv", match("BV1GJ411x7h7挺好看")?.ruleId)
        assertEquals("bili_bv", match("「BV1GJ411x7h7」")?.ruleId)
        assertEquals("bili_bv", match("看看这个 BV1GJ411x7h7 挺好")?.ruleId)
    }

    @Test
    fun `前缀大小写都认且还原成规范大小写`() {
        val lower = match("bv1GJ411x7h7")
        assertEquals("bili_bv", lower?.ruleId)
        assertEquals("BV1GJ411x7h7", lower?.core)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", lower?.target)
        assertEquals("av170001", match("AV170001")?.core)
        assertEquals("https://www.bilibili.com/av170001", match("AV170001")?.target)
    }

    @Test
    fun `不是号形态的不误弹`() {
        // 光有前缀、号体长度不对 —— 放开锚定之后这条边界就是唯一的防线
        assertNull(match("这段是 bv 开头的字符"))
        assertNull(match("BV1GJ411x7h"))
        assertNull(match("看看 av 编码"))
    }

    @Test
    fun `裸 av 号命中`() {
        val m = match("av170001")
        assertNotNull(m)
        assertEquals("bili_av", m.ruleId)
        assertEquals("https://www.bilibili.com/av170001", m.target)
    }

    @Test
    fun `bm 音频口令`() {
        val m = match("bmBV1GJ411x7h7")
        assertNotNull(m)
        assertEquals("bili_bm", m.ruleId)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", m.target)
    }

    @Test
    fun `pixiv 的 pid 文本口令`() {
        val m = match("pid 12345")
        assertNotNull(m)
        assertEquals("pixiv_pid", m.ruleId)
        assertEquals("https://www.pixiv.net/artworks/12345", m.target)
    }

    // ── 淘口令：全文搜 + 同符号闭合 ─────────────────────────────────────

    @Test
    fun `淘口令嵌在分享文案里也能命中`() {
        val m = match("0/CbEWQyT/【某商品】复制这条信息 ￥abc123XYZ￥ 打开手淘")
        assertNotNull(m)
        assertEquals("taokouling", m.ruleId)
        assertEquals("taobao://", m.target)
    }

    @Test
    fun `淘口令符号必须同侧闭合`() {
        // ¥左￥右 = 假口令；误弹的代价是莫名拉起手淘
        assertNull(match("价格是¥100￥好像不对"))
    }

    @Test
    fun `新版口令符号集下划线与美元同侧闭合`() {
        // 2026 新版手淘口令变体：_$₳€£ 同样是闭合符（HyperCopy_Rules 社区观察）
        val m1 = match("12/_abc12def_14 复制这条信息打开手淘")
        assertNotNull(m1)
        assertEquals("taokouling", m1.ruleId)
        val m2 = match("看这个 \$abc123XYZ\$ 好价")
        assertNotNull(m2)
        assertEquals("taokouling", m2.ruleId)
        // 异侧闭合仍是假口令
        assertNull(match("价格是$100￥好像不对"))
    }

    @Test
    fun `新版全文本淘口令命中且核心取标题`() {
        // 2026 新版口令形态：\d\w:/ + 短 token + 空白 + 标题/话术
        val m = match("6e:/嘻 全棉四件套床上用品限时特价 复制这条信息打开手淘")
        assertNotNull(m)
        assertEquals("taokouling_new", m.ruleId)
        assertEquals("taobao://", m.target)
        assertEquals("淘宝", m.platform?.name)
        assertEquals("全棉四件套床上用品限时特价", m.core)
    }

    @Test
    fun `新版口令形态不被路径与比例误触`() {
        // token 结尾必须空白：路径/比例形态不弹
        assertNull(match("日志在 0x:/data/local/tmp 下"))
        assertNull(match("画幅 16:9/10 才对"))
        // Windows 盘符：字母开头，不满足 \d\w:/
        assertNull(match("装在 C:/Users/name 下"))
    }

    @Test
    fun `带 http 的新版口令先走 URL 阶段`() {
        // URL 命中即独占：新版 token 不再触发 taokouling_new
        val m = match("6e:/嘻 好物推荐 https://e.tb.cn/h.8EJggHWgQLHN0i 复制打开")
        assertEquals("url", m?.ruleId)
    }

    // ── ③ 内容号 ────────────────────────────────────────────────────────

    @Test
    fun `cv 号全文命中`() {
        val m = match("这篇专栏 cv987654 写得不错")
        assertNotNull(m)
        assertEquals("bili_cv", m.ruleId)
        assertEquals("https://www.bilibili.com/read/cv987654", m.target)
    }

    @Test
    fun `sm 号全文命中`() {
        val m = match("推荐 sm9")
        assertNotNull(m)
        assertEquals("nico", m.ruleId)
        assertEquals("https://www.nicovideo.jp/watch/sm9", m.target)
    }

    @Test
    fun `ac 号全文命中且不被 acfun 单词误触`() {
        val m = match("ac4725103")
        assertNotNull(m)
        assertEquals("acfun", m.ruleId)
        assertNull(match("我去acfun看视频"))
    }

    @Test
    fun `内容号前缀大小写都认且还原成小写号`() {
        // 转载/手打大概率是大写（用户实测反馈 SM/AC 不生效的形态之一）。
        // 号本身区分大小写，所以拼接前必须还原：拿 SM39366587 去拼 watch 页会 404。
        val sm = match("SM39366587")
        assertEquals("nico", sm?.ruleId)
        assertEquals("sm39366587", sm?.core)
        assertEquals("https://www.nicovideo.jp/watch/sm39366587", sm?.target)

        val ac = match("这个AC4725103不错")
        assertEquals("acfun", ac?.ruleId)
        assertEquals("ac4725103", ac?.core)
        assertEquals("https://www.acfun.cn/v/ac4725103", ac?.target)

        assertEquals("bili_cv", match("专栏CV987654 写得不错")?.ruleId)
        assertEquals("cv987654", match("CV987654")?.core)
    }

    @Test
    fun `so 号只认小写避免与化学式撞车`() {
        // 大写 SO 紧跟数字正好是 SO2 / SO4 的写法，认了就是天天误弹
        assertNull(match("排放 SO2 超标了"))
        assertNull(match("SO4 离子"))
        assertEquals("nico", match("so45678901")?.ruleId)
        // NM 大写是 niconico 的旧号格式，认（不与化学式冲突）
        assertEquals("nico", match("NM12345")?.ruleId)
    }

    // ── 链接里的号 = 核心（与裸号同一逻辑，2026-09-27 补齐）─────────────

    @Test
    fun `链接里的号也抽出来当核心`() {
        // 抽不出号时（如 b23.tv 短链）核心才是 null → 展示侧预加载标题补
        assertEquals("ac4725103", match("【这是标题】https://www.acfun.cn/v/ac4725103")?.core)
        assertEquals("ac4725103", match("https://www.acfun.cn/v/ac4725103")?.core)
        assertEquals("sm39366587", match("https://www.nicovideo.jp/watch/sm39366587")?.core)
        assertEquals("cv987654", match("https://www.bilibili.com/read/cv987654")?.core)
        assertEquals("av170001", match("https://www.bilibili.com/video/av170001")?.core)
        assertEquals("12345", match("https://www.pixiv.net/artworks/12345")?.core)
        assertNull(match("https://b23.tv/abcDEF")?.core)
    }

    @Test
    fun `niconico 官方短域 nico_ms 识别为 niconico`() {
        // 漏了它整条分享会落「网页链接」，岛上显示「打开浏览器」= 用户说的「没生效」
        val m = match("https://nico.ms/sm39366587")
        assertEquals("niconico 链接", m?.label)
        assertEquals(PasscodeRules.P_NICO, m?.platform)
        assertEquals("sm39366587", m?.core)
        // 子串不误判（harmonico.ms 不是 nico.ms）—— 落通用网页，不认 niconico、也没有核心
        val sub = match("https://harmonico.ms/sm39366587")
        assertNotEquals(PasscodeRules.P_NICO, sub?.platform)
        assertNull(sub?.core)
    }

    @Test
    fun `QQ 音乐歌曲页保留网页分享并提供应用直达候选`() {
        val url = "https://y.qq.com/n/ryqq/songDetail/0039MnYb0qxYhV"
        val song = match("晴天 $url?from=share")
        assertEquals("qq_music_song", song?.ruleId)
        assertEquals("$url?from=share", song?.target)
        assertEquals(PasscodeRules.P_QQ_MUSIC, song?.platform)
        assertEquals(PasscodeIsland.ContentCategory.MUSIC, song?.category)
        assertEquals(
            "qqmusic://qq.com/ui/openUrl?p=%7B%22target%22%3A%221%22%2C%22url%22%3A%22https%3A%2F%2Fy.qq.com%2Fn%2Fryqq%2FsongDetail%2F0039MnYb0qxYhV%22%7D",
            song?.alts?.single(),
        )
        assertEquals("url", match("https://y.qq.com/n/ryqq/playlist/123")?.ruleId)
        // 仿冒域名不认成 QQ 音乐。用公网后缀：`.test` 是保留后缀，2026-10-22 起非公网域名直接不弹岛
        assertEquals("url", match("https://y.qq.com.evil.com/n/ryqq/songDetail/0039MnYb0qxYhV")?.ruleId)
        assertNull(match("https://y.qq.com.evil.test/n/ryqq/songDetail/0039MnYb0qxYhV"))
    }

    // ── 快递单号：前缀开、裸数字门控 ─────────────────────────────────────

    @Test
    fun `带前缀快递单号默认开`() {
        val m = match("您的快递 SF1382024591123 已发出")
        assertNotNull(m)
        assertEquals("express_prefixed", m.ruleId)
        // 2026-10-22 用户拍板：顺丰也走负一屏快递详情，备选 菜鸟 App → 支付宝小程序 → 快递100
        assertEquals(PasscodeRules.personalAssistantExpressTarget("SF1382024591123"), m.target)
        assertEquals(
            listOf(
                PasscodeRules.cainiaoAppTarget("SF1382024591123"),
                PasscodeRules.cainiaoMiniProgramTarget("SF1382024591123"),
                "https://m.kuaidi100.com/result.jsp?nu=SF1382024591123",
            ),
            m.alts,
        )
    }

    @Test
    fun `S10 万国格式单号`() {
        // 2026-10-22 起 S10 带校验位：序号 12345678 的校验位是 5（旧样例 …789CN 校验位不对，现已不认）
        val m = match("EA123456785CN")
        assertNotNull(m)
        assertEquals("express_prefixed", m.ruleId)
        assertNull(match("EA123456789CN"))
    }

    @Test
    fun `极兔与德邦与京东变体前缀单号`() {
        // JT 极兔 13~16 位
        val jt = match("极兔速递 JT3210987654321 已揽收")
        assertNotNull(jt)
        assertEquals("express_prefixed", jt.ruleId)
        assertEquals(PasscodeRules.personalAssistantExpressTarget("JT3210987654321"), jt.target)
        // DPK 德邦 10~12 位
        val dpk = match("德邦 DPK1234567890 已到网点")
        assertEquals("express_prefixed", dpk?.ruleId)
        // JDV 京东变体
        val jdv = match("京东订单 JDV12345678901 配送中")
        assertEquals("express_prefixed", jdv?.ruleId)
        assertEquals("JDV12345678901", jdv?.core)
    }

    @Test
    fun `普通英文单词不误触快递前缀`() {
        // 「YouTube」里没有 \bYT\d 形态；「DPK」单独出现无数字尾
        assertNull(match("我在 YouTube 看视频"))
        assertNull(match("DPK 开头是什么"))
    }

    @Test
    fun `纯数字单号默认关`() {
        // 门控失守的后果：订单号/流水号被当快递弹岛
        assertNull(match("7800123456789 已发货"))
    }

    @Test
    fun `纯数字单号开且带上下文词才命中`() {
        val opt = PasscodeRules.Options(numericTracking = true)
        val m = match("韵达 7800123456789", opt)
        assertNotNull(m)
        assertEquals("express_numeric", m.ruleId)
        // 开了门控但没上下文词 → 仍然不弹
        assertNull(match("7800123456789", opt))
    }

    // ── ① URL：最高优先 + 命中即独占 ────────────────────────────────────

    @Test
    fun `短链透传不解析`() {
        val m = match("https://b23.tv/abcDEF 复制此链接打开B站")
        assertNotNull(m)
        assertEquals("url", m.ruleId)
        assertEquals("https://b23.tv/abcDEF", m.target)
        assertEquals("B 站链接", m.label)
    }

    @Test
    fun `URL 里的 BV 不再触发裸口令`() {
        // 独占失守的后果：一条分享弹两条岛
        val m = match("https://www.bilibili.com/video/BV1GJ411x7h7?p=2")
        assertNotNull(m)
        assertEquals("url", m.ruleId)
    }

    @Test
    fun `任意 http 链接默认上岛`() {
        val m = match("https://example.com/some/page")
        assertNotNull(m)
        assertEquals("url", m.ruleId)
        assertEquals("网页链接", m.label)
    }

    @Test
    fun `generic 关掉后非白名单 URL 不弹但裸口令仍工作`() {
        val opt = PasscodeRules.Options(genericUrl = false)
        assertNull(match("https://example.com/some/page", opt))
        assertNotNull(match("https://b23.tv/abcDEF", opt))
    }

    @Test
    fun `URL 尾部的中文与标点不被吞`() {
        val m = match("【新品上市】https://b23.tv/abcDEF，快来看看！")
        assertNotNull(m)
        assertEquals("https://b23.tv/abcDEF", m.target)
    }

    // ── 平台元数据与核心内容（视觉定稿 2026-09-25）────────────────────

    @Test
    fun `裸 BV 号的核心内容就是号本身且带平台元数据`() {
        val m = match("BV1GJ411x7h7")
        assertEquals("BV1GJ411x7h7", m?.core)
        val p = m?.platform
        assertEquals("B站", p?.name)
        assertEquals("tv.danmaku.bili", p?.pkg)
        assertEquals("#FB7299", p?.accent)
    }

    @Test
    fun `快递单号的核心是单号且用兜底蓝`() {
        val m = match("您的快递 SF1382024591123 已发出")
        assertEquals("SF1382024591123", m?.core)
        assertEquals("快递", m?.platform?.name)
        assertEquals("#5B7CFF", m?.platform?.accent)
    }

    @Test
    fun `淘口令带商品标题时核心是标题`() {
        val m = match("0/CbEWQyT/【某商品】复制这条信息 ￥abc123XYZ￥ 打开手淘")
        assertEquals("某商品", m?.core)
        assertEquals("淘宝", m?.platform?.name)
        assertEquals("com.taobao.taobao", m?.platform?.pkg)
    }

    @Test
    fun `纯口令无标题时核心为空`() {
        // 提不出标题 → core null，展示侧回落「打开淘宝」，不显示乱码口令
        val m = match("￥abc123XYZ￥")
        assertEquals("taokouling", m?.ruleId)
        assertNull(m?.core)
    }

    @Test
    fun `抖音短链分享取标题行`() {
        val m = match("6.88 Kfj:/ 复制打开抖音，看看【这是我最想安利的一集】 https://v.douyin.com/abc/")
        assertEquals("url", m?.ruleId)
        assertEquals("抖音", m?.platform?.name)
        assertEquals("com.ss.android.ugc.aweme", m?.platform?.pkg)
        assertEquals("这是我最想安利的一集", m?.core)
    }

    /** 真机样本 xhslink.com/m/2l5ocw1OIHx 302 到 goods-detail（2026-10-22）：按商品类展示。 */
    @Test
    fun `小红书商品详情归商品类 笔记仍是图文`() {
        val goods = PasscodeRules.match("https://www.xiaohongshu.com/goods-detail/66f309c0acbed20001b2a776?xsec_token=X")
        assertEquals(PasscodeIsland.ContentCategory.ECOMMERCE, goods?.category)
        val note = PasscodeRules.match("https://www.xiaohongshu.com/explore/68e8e3fa00000000030342ec")
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT, note?.category)
    }

    @Test
    fun `小红书分享标题在链接之前`() {
        val m = match("秋季穿搭合集 https://xhslink.com/xyz")
        assertEquals("小红书", m?.platform?.name)
        assertEquals("秋季穿搭合集", m?.core)
    }

    @Test
    fun `小红书链接后的引导话术不覆盖正文`() {
        val m = match(
            "iclr后浑浑噩噩的睡了好久，这次遇到了最痛苦的一段经... " +
                "https://xhslink.cn/o/2cTkvtdCFx4 \n来【小红书】一探究竟吧！",
        )
        assertEquals("小红书", m?.platform?.name)
        assertEquals("https://xhslink.cn/o/2cTkvtdCFx4", m?.target)
        assertEquals("iclr后浑浑噩噩的睡了好久，这次遇到了最痛苦的…", m?.core)
    }

    @Test
    fun `贴吧与微博的分享标题在网页受限时仍可上岛`() {
        assertEquals("新赛季阵容讨论", match("【新赛季阵容讨论】https://tieba.baidu.com/p/9704684816")?.core)
        assertEquals("今日比赛现场", match("今日比赛现场 https://weibo.com/7861851237/RkcqYFIJr")?.core)
        assertNull(match("https://tieba.baidu.com/p/9704684816")?.core)
    }

    @Test
    fun `B站链接核心抽 BV 号`() {
        val m = match("https://www.bilibili.com/video/BV1GJ411x7h7?p=2")
        assertEquals("BV1GJ411x7h7", m?.core)
        assertEquals("tv.danmaku.bili", m?.platform?.pkg)
    }

    @Test
    fun `普通网页没有平台与核心`() {
        val m = match("https://example.com/some/page")
        assertEquals("网页", m?.platform?.name)
        assertNull(m?.platform?.pkg)
        assertNull(m?.core)
    }

    @Test
    fun `extractTitle 引号体与话术清理`() {
        assertEquals("这是标题", PasscodeRules.extractTitle("「这是标题」https://xhslink.com/xyz"))
        // 引号前带价格前缀的行
        assertEquals("冬季加绒衬衫 免烫抗皱", PasscodeRules.extractTitle("9.9 冬季加绒衬衫 免烫抗皱 https://s.click.taobao.com/abc ￥ab12cd34￥"))
        // 纯价格/口令碎片不算标题
        assertNull(PasscodeRules.extractTitle("9.9 ￥ab12cd34￥ 复制这条信息"))
        // 超长截断
        val long = PasscodeRules.extractTitle("这是一个特别特别特别特别特别特别特别特别特别特别特别长的标题文本 https://xhslink.com/x")
        assertNotNull(long)
        assertTrue(long.length <= 25)
        assertTrue(long.endsWith("…"))
    }

    // ── 真实分享样本（2026-09-25 截图，淘宝/京东/美团补适配）──────────

    @Test
    fun `淘宝短链 e_tb_cn 识别平台且核心取标题`() {
        val t = "【淘宝】7天无理由退货 https://e.tb.cn/h.8EJggHWgQLHN0i?tk=mw3Y3TOsbey3 CZ0557 " +
            "蓝色大肥鱼鲸鱼娅亚克力立牌周边钥匙扣可爱deepseek桌面摆件礼物，点击链接直接打开 或者 淘宝搜索直接打开"
        val m = match(t)
        assertNotNull(m)
        assertEquals("淘宝", m.platform?.name)
        assertEquals("com.taobao.taobao", m.platform?.pkg)
        // 标题 = URL 之后的 CJK 片段（token 与验证码被剥掉）
        val core = m.core
        assertNotNull(core)
        assertTrue(core.startsWith("蓝色大肥鱼"), "actual=$core")
        assertFalse(core.contains("CZ0557"))
        assertFalse(core.contains("点击链接"))
    }

    @Test
    fun `京东短链 3_cn 取书名号标题`() {
        val t = "【京东】https://3.cn/-34DhGLz?tk=@C28HZ7y95G@ ZH9112 『小米平板9 Pro【国家补贴】』 " +
            "点击链接直接打开 或者复制文案打开京东"
        val m = match(t)
        assertNotNull(m)
        assertEquals("京东", m.platform?.name)
        assertEquals("com.jingdong.app.mall", m.platform?.pkg)
        assertEquals("小米平板9 Pro【国家补贴】", m.core)
    }

    @Test
    fun `美团外卖分享取书名号店名`() {
        val t = "分享给你美团外卖的『曼玲粥(九龙湖店)』，点击链接可进店查看详情 http://dpurl.cn/vGTW1Exz"
        val m = match(t)
        assertNotNull(m)
        assertEquals("美团", m.platform?.name)
        assertEquals("曼玲粥(九龙湖店)", m.core)
    }

    @Test
    fun `拼多多 yangkeduo 分享识别平台`() {
        val m = match("https://mobile.yangkeduo.com/goods2.html?ps=6tn3qq7Gvl")
        assertEquals("拼多多", m?.platform?.name)
    }

    @Test
    fun `网盘和贴吧域名优先于百度通用域名`() {
        assertEquals("网盘", match("https://pan.baidu.com/s/abc123")?.platform?.name)
        val tieba = match("https://tieba.baidu.com/p/9704684816?see_lz=0")
        assertEquals("贴吧", tieba?.platform?.name)
        assertEquals("com.baidu.tieba", tieba?.platform?.pkg)
    }

    @Test
    fun `平台标签括号不算标题`() {
        // 【小红书】是来源标签，不是标题 —— 标题应从正文取
        val t = "找搜广推实习有感 bg：学历在主页 https://xhslink.cn/o/2HF9do5niR 先复制文字，再进【小红书】看看"
        val m = match(t)
        assertEquals("小红书", m?.platform?.name)
        val core = m?.core
        assertNotNull(core)
        assertFalse(core.startsWith("小红书"), "来源标签泄漏进了标题: $core")
    }

    // ── 海外平台（2026-09-25 批次）──────────────────────────────────────

    @Test
    fun `YouTube 短链抽视频 ID`() {
        val m = match("https://youtu.be/dQw4w9WgXcQ?si=abc")
        assertEquals(PasscodeRules.P_YOUTUBE, m?.platform)
        assertEquals("dQw4w9WgXcQ", m?.core)
        assertEquals("com.google.android.youtube", m?.platform?.pkg)
    }

    @Test
    fun `YouTube watch 与 shorts 都抽 ID`() {
        assertEquals("dQw4w9WgXcQ", match("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1s")?.core)
        assertEquals("dQw4w9WgXcQ", match("https://www.youtube.com/shorts/dQw4w9WgXcQ")?.core)
    }

    @Test
    fun `music_youtube 优先判 YT Music`() {
        val m = match("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(PasscodeRules.P_YT_MUSIC, m?.platform)
        assertEquals("dQw4w9WgXcQ", m?.core)
    }

    @Test
    fun `X 推文链接抽 Snowflake ID`() {
        val m = match("https://x.com/SpaceX/status/1840000000000000000")
        assertEquals(PasscodeRules.P_X, m?.platform)
        assertEquals("1840000000000000000", m?.core)
        // twitter.com 老域同样命中
        assertEquals(PasscodeRules.P_X, match("https://twitter.com/SpaceX/status/1840000000000000000")?.platform)
    }

    @Test
    fun `box_com 不误判为 X`() {
        val m = match("https://box.com/some/page")
        // box.com 命中的应是 generic（无平台）而不是 P_X
        if (m?.platform != null) assertNotEquals(PasscodeRules.P_X, m.platform)
    }

    @Test
    fun `TikTok 短链与 Instagram reel 识别平台`() {
        val tiktok = match("https://vm.tiktok.com/ZMrKxYz/")
        assertEquals(PasscodeRules.P_TIKTOK, tiktok?.platform)
        assertEquals(PasscodeIsland.ContentCategory.SHORT_VIDEO, tiktok?.category)
        val reel = match("https://www.instagram.com/reel/CxYz123abc/")
        assertEquals(PasscodeRules.P_IG, reel?.platform)
        assertEquals(PasscodeIsland.ContentCategory.SHORT_VIDEO, reel?.category)
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT,
            match("https://www.instagram.com/p/CxYz123abc/")?.category)
    }

    @Test
    fun `Threads 使用目标机实际安装包名`() {
        val thread = match("https://www.threads.net/@instagram/post/CxYz123abc")
        assertEquals(PasscodeRules.P_THREADS, thread?.platform)
        assertEquals("com.instagram.barcelona", thread?.platform?.pkg)
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT,
            match("https://www.threads.com/@instagram/post/CxYz123abc")?.category)
    }

    @Test
    fun `Facebook 帖文视频与 Reel 使用对应模板`() {
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT,
            match("https://www.facebook.com/example/posts/10153664894881749")?.category)
        assertEquals(PasscodeIsland.ContentCategory.LONG_VIDEO,
            match("https://www.facebook.com/example/videos/3676516585958356/")?.category)
        assertEquals(PasscodeIsland.ContentCategory.LONG_VIDEO,
            match("https://www.facebook.com/watch/?v=3676516585958356")?.category)
        assertEquals(PasscodeIsland.ContentCategory.SHORT_VIDEO,
            match("https://www.facebook.com/reel/3676516585958356")?.category)
    }

    @Test
    fun `内容平台分类区分短视频长视频音乐和电商`() {
        assertEquals(PasscodeIsland.ContentCategory.LONG_VIDEO, match("https://www.bilibili.com/video/BV1GJ411x7h7")?.category)
        assertEquals(PasscodeIsland.ContentCategory.MUSIC, match("https://music.youtube.com/watch?v=dQw4w9WgXcQ")?.category)
        assertEquals(PasscodeIsland.ContentCategory.ECOMMERCE, match("https://item.taobao.com/item.htm?id=123456")?.category)
    }

    @Test
    fun `Steam appid 与 Amazon ASIN 抽核心`() {
        val s = match("https://store.steampowered.com/app/1091500/Cyberpunk_2077/")
        assertEquals(PasscodeRules.P_STEAM, s?.platform)
        assertEquals("1091500", s?.core)
        val a = match("https://www.amazon.com/dp/B0CX23V2ZK/ref=something")
        assertEquals(PasscodeRules.P_AMAZON, a?.platform)
        assertEquals("B0CX23V2ZK", a?.core)
    }

    @Test
    fun `Telegram 与 Discord 与 Reddit 平台识别`() {
        assertEquals(PasscodeRules.P_TELEGRAM, match("https://t.me/durov")?.platform)
        assertEquals(PasscodeRules.P_DISCORD, match("https://discord.gg/AbCdEf")?.platform)
        assertEquals(PasscodeRules.P_REDDIT, match("https://redd.it/1g4x9ab")?.platform)
    }

    @Test
    fun `GitHub 链接核心是 owner_repo`() {
        val m = match("https://github.com/1812z/HyperIsland")
        assertEquals(PasscodeRules.P_GITHUB, m?.platform)
        assertEquals("1812z/HyperIsland", m?.core)
    }

    // ── 防抖 ────────────────────────────────────────────────────────────

    @Test
    fun `同文窗口内重复被拦`() {
        assertTrue(
            PasscodeIsland.isDuplicate(
                lastText = "BV1GJ411x7h7", lastAtMs = 1_000L,
                text = "BV1GJ411x7h7", nowMs = 2_499L,
            )
        )
    }

    @Test
    fun `窗口外或文本变化或时钟倒退都不算重复`() {
        assertFalse(
            PasscodeIsland.isDuplicate(
                lastText = "BV1GJ411x7h7", lastAtMs = 1_000L,
                text = "BV1GJ411x7h7", nowMs = 2_500L,
            )
        )
        assertFalse(
            PasscodeIsland.isDuplicate(
                lastText = "BV1GJ411x7h7", lastAtMs = 1_000L,
                text = "别的文本", nowMs = 2_000L,
            )
        )
        assertFalse(
            PasscodeIsland.isDuplicate(
                lastText = "BV1GJ411x7h7", lastAtMs = 5_000L,
                text = "BV1GJ411x7h7", nowMs = 4_000L,
            )
        )
        assertFalse(
            PasscodeIsland.isDuplicate(
                lastText = null, lastAtMs = 0L,
                text = "BV1GJ411x7h7", nowMs = 2_000L,
            )
        )
    }

    // ── 杂项边界 ────────────────────────────────────────────────────────

    @Test
    fun `空白与超长文本直接放行`() {
        assertNull(match("   "))
        assertNull(match("a".repeat(2001)))
    }

    @Test
    fun `trim 后命中`() {
        val m = match("  BV1GJ411x7h7\n")
        assertNotNull(m)
        assertEquals("bili_bv", m.ruleId)
    }

    // ── 核心内容形态判定（展示侧靠它把号放次行、标题放大字行）──────────────

    @Test
    fun `号形态的核心是纯 ASCII 字母数字`() {
        assertTrue(PasscodeIsland.isIdLike("BV1GJ411x7h7"))
        assertTrue(PasscodeIsland.isIdLike("sm39366587"))
        assertTrue(PasscodeIsland.isIdLike("SF1382024591123"))
        assertTrue(PasscodeIsland.isIdLike("dQw4w9WgXcQ"))
        assertTrue(PasscodeIsland.isIdLike("1812z/HyperIsland"))
    }

    @Test
    fun `标题形态的核心不算号`() {
        // CJK、括号、空格任一出现就不是号 —— 判错的后果是标题被塞进次行、字小到看不清
        assertFalse(PasscodeIsland.isIdLike("全棉四件套床上用品限时特价"))
        assertFalse(PasscodeIsland.isIdLike("小米平板9 Pro【国家补贴】"))
        assertFalse(PasscodeIsland.isIdLike("曼玲粥(九龙湖店)"))
        assertFalse(PasscodeIsland.isIdLike("这是我最想安利的一集"))
        // 没有数字的纯单词也不是号
        assertFalse(PasscodeIsland.isIdLike("abc"))
        // 超过展示预算的号（owner/repo 之类）不进次行
        assertFalse(PasscodeIsland.isIdLike("a".repeat(30)))
    }

    // ── 承运商名（快递条大字行的内容，2026-09-27）──────────────────────────

    @Test
    fun `快递单号前缀认得出承运商`() {
        // 前缀 → 承运商 是行业编号常识，零网络。之前大字行放的是抓来的网页标题，
        // 实测是「单号查询结果 - 快递100」—— 占了最贵的位置却零信息。
        assertEquals("顺丰速运", PasscodeIsland.carrierName("SF1382024591123"))
        assertEquals("圆通速递", PasscodeIsland.carrierName("YT1234567890123"))
        assertEquals("极兔速递", PasscodeIsland.carrierName("JT1234567890123"))
        assertEquals("德邦快递", PasscodeIsland.carrierName("DPK1234567890"))
        assertEquals("京东物流", PasscodeIsland.carrierName("JD1234567890123"))
        // JDV / JDX 也是京东号段（规则表里同一条正则的 [A-Z]{0,2}）
        assertEquals("京东物流", PasscodeIsland.carrierName("JDX1234567890123"))
        // 万国邮联 S10：按国别码查承运商（CN = 中国邮政），查不到的给诚实的粗名
        assertEquals("中国邮政", PasscodeIsland.carrierName("RB123456785CN"))
        assertEquals("国际件", PasscodeIsland.carrierName("RB123456785GB"))
    }

    @Test
    fun `认不出承运商返回 null 而不是编一个`() {
        // 纯数字单号（中通/申通/韵达）没有任何前缀可依据 → 展示侧回落「把单号放大」
        assertNull(PasscodeIsland.carrierName("123456789012345"))
        assertNull(PasscodeIsland.carrierName(""))
        // 形似但位数不对的 S10 不算（避免把 SF1382024591123 误判成国际件）
        assertEquals("顺丰速运", PasscodeIsland.carrierName("SF1382024591123"))
    }

    @Test
    fun `承运商名大小写无关`() {
        assertEquals("顺丰速运", PasscodeIsland.carrierName("sf1382024591123"))
        assertEquals("德邦快递", PasscodeIsland.carrierName("dpk1234567890"))
    }

    // ── 快递目标候选链（菜鸟优先，2026-09-27）────────────────────────────

    @Test
    fun `快递跳转链 负一屏 菜鸟 App 支付宝小程序 快递100`() {
        val chain = PasscodeRules.expressTargetChain("SF1382024591123")
        assertEquals(4, chain.size)
        // 负一屏快递详情：显式组件 + 单号 JSON（反编译 ExpressDetailFragment，字段名 order）
        assertEquals(
            "intent:#Intent;component=com.miui.personalassistant/.service.express.activity.ExpressActivity;" +
                "S.express_start=express_detail;S.express=%7B%22order%22%3A%22SF1382024591123%22%7D;end",
            chain[0],
        )
        assertTrue(PasscodeRules.isAllowedIntentTarget(chain[0]))
        // 菜鸟 App：系统复制直达的同款链接
        assertTrue(chain[1].startsWith("cainiao://startapp/logistic?mailNo=SF1382024591123&"), chain[1])
        assertTrue(chain[2].startsWith("alipays://platformapi/startapp"), "第三级应为支付宝菜鸟小程序")
        assertTrue(chain[2].contains("appId=2021001141626787"), "小程序 id 写错就整条静默失败")
        assertTrue(chain[2].endsWith("mailNo%3DSF1382024591123"), "单号必须编码在 page 里")
        assertTrue(chain[3].startsWith("https://m.kuaidi100.com/result.jsp?nu="), "链尾应为快递100")
    }

    @Test
    fun `intent 目标只放行负一屏快递详情`() {
        assertFalse(PasscodeRules.isAllowedIntentTarget("intent:#Intent;component=com.evil/.X;end"))
        assertFalse(PasscodeRules.isAllowedIntentTarget("intent://x#Intent;scheme=https;end"))
        // 单号里只留字母数字，拼不出额外的 Intent 字段
        assertFalse(PasscodeRules.personalAssistantExpressTarget("A;S.x=1;end").contains(";S.x="))
    }

    @Test
    fun `菜鸟链用的是 page 参数而不是 query`() {
        // 真机实测：写成 &query=mailNo=… 会丢参（页面提示「物流订单号为空」），
        // 必须整体编码进 page。这条用例就是钉住这个坑。
        val u = PasscodeRules.cainiaoMiniProgramTarget("SF1382024591123")
        assertTrue(u.contains("page=pages%2Flogistic%2Flogistic%3F"))
        assertFalse(u.contains("&query="), "query 形态实测丢参，不许再出现：$u")
    }

    @Test
    fun `快递规则的 target 是链首、alts 里是兜底`() {
        val m = match("顺丰速运 SF1382024591123")
        assertNotNull(m)
        assertEquals("express_prefixed", m.ruleId)
        assertEquals("SF1382024591123", m.core)
        // 2026-10-22 用户拍板：顺丰也走负一屏快递详情，备选 菜鸟 App → 支付宝小程序 → 快递100
        assertEquals(PasscodeRules.personalAssistantExpressTarget("SF1382024591123"), m.target)
        assertEquals(
            listOf(
                PasscodeRules.cainiaoAppTarget("SF1382024591123"),
                PasscodeRules.cainiaoMiniProgramTarget("SF1382024591123"),
                "https://m.kuaidi100.com/result.jsp?nu=SF1382024591123",
            ),
            m.alts,
        )
    }

    @Test
    fun `非快递规则没有候选链`() {
        // 候选链一旦外溢到别的规则，展示侧就会莫名从 App 跳到浏览器
        assertEquals(emptyList(), match("BV1GJ411x7h7")?.alts)
        assertEquals(emptyList(), match("https://www.bilibili.com/video/BV1GJ411x7h7")?.alts)
    }
}
