package io.github.kotoriinsky.tsuzuri.core

/**
 * 电商详情页的字段提取（京东 / 抖音商城）。
 *
 * 两者都是 **2026-10-06 用户给的真实分享链**实测出来的。
 */
object EcomShare {

    // ── 京东 ─────────────────────────────────────────────────────────────

    /**
     * 京东商品页：`item.m.jd.com/product/<sku>.html`（短链 `3.cn/…` 302 后落到这里）。
     *
     * 实测（真实链 `3.cn/-362uUfS`，跳转到 `item.m.jd.com/product/10181383335547.html`）：
     *
     * - 页面 **297160 字节**，**无 og 标签**，`<title>` 是商品名但带尾巴
     *   （`…【图片 价格 品牌 评论】-京东`）；
     * - `window._itemOnly = ({…"skuId":"10181383335547","skuName":"Apple/苹果/…",
     *   "imageurl":"jfs/t1/438646/34/13072/19851/xxx.jpg",…})` 在 **offset 14365**（96KB 上限内）；
     * - **`skuName` 比 `<title>` 干净**，`imageurl` 是主图（**相对路径**，要拼 CDN 主机）。
     */
    object Jd {
        data class Fields(val title: String?, val cover: String?)

        private val SKU_PATH = Regex("""/(?:product/)?(\d{6,})\.html""")

        fun sku(url: String): String? = SKU_PATH.find(url)?.groupValues?.get(1)

        private val SKU_NAME = Regex(""""skuName"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
        private val IMAGE_URL = Regex(""""imageurl"\s*:\s*"((?:[^"\\]|\\.)*)["]""")

        /** 京东图片 CDN（`imageurl` 只给相对路径）；几个 `imgNN` 主机同源，取一个即可。 */
        private const val IMG_HOST = "https://img14.360buyimg.com/"

        fun parse(html: String): Fields? {
            if (html.isEmpty()) return null
            // 只在 _itemOnly 附近的窗口里找，避免抓到页面其它位置的同名字段
            val at = html.indexOf("_itemOnly").takeIf { it >= 0 } ?: return null
            val block = html.substring(at, minOf(at + 20_000, html.length))
            val title = SKU_NAME.find(block)?.groupValues?.get(1)?.let(JsonText::unescape)
            val image = IMAGE_URL.find(block)?.groupValues?.get(1)?.let(JsonText::unescape)
            val fields = Fields(
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                cover = image?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                    when {
                        raw.startsWith("https://") -> raw
                        raw.startsWith("//") -> "https:$raw"
                        else -> IMG_HOST + raw.trimStart('/')
                    }
                },
            )
            if (fields.title == null && fields.cover == null) return null
            return fields
        }
    }

    // ── 抖音商城 ─────────────────────────────────────────────────────────

    /**
     * 抖音商城：**数据就在短链跳转后的 URL 里**，不必再抓页面。
     *
     * 实测（真实链 `v.douyin.com/W0Q7x4V_7m0/`）：302 到
     * `haohuo.jinritemai.com/ecommerce/trade/detail/index.html?…&goods_detail=%7B%22title%22%3A%22
     * AIUV…%22%2C%22sales%22%3A23%2C%22img%22%3A%7B%22url_list%22%3A%5B%22https%3A%2F%2Fp3-item.ecombdimg.com…%22%5D%7D%2C%22min_price%22%3A2990%7D`
     * —— 解码后是 `{"title":…,"sales":…,"img":{"url_list":[…]},"min_price":…,"max_price":…}` ✓
     *
     * 而**页面本身是 11905 字节的壳**（`<title></title>`、无 `_ROUTER_DATA`）→ 抓页面没用，
     * URL 就是最好的来源（零额外请求）。
     */
    object DouyinMall {
        data class Fields(val title: String?, val cover: String?, val price: Int?)

        private val GOODS_DETAIL = Regex("""[?&]goods_detail=([^&]+)""")
        private val TITLE = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)["]""")
        private val IMG_URL = Regex(""""url_list"\s*:\s*\[\s*"((?:[^"\\]|\\.)*)["]""")
        private val MIN_PRICE = Regex(""""min_price"\s*:\s*(\d+)""")

        /** 从**跳转后的最终地址**里取字段；不是抖音商城地址时返回 null。 */
        fun parseFromUrl(url: String): Fields? {
            if (url.isEmpty() || !url.contains("goods_detail=")) return null
            val raw = GOODS_DETAIL.find(url)?.groupValues?.get(1) ?: return null
            val json = PipixShare.percentDecode(raw)
            val title = TITLE.find(json)?.groupValues?.get(1)?.let(JsonText::unescape)
            val cover = IMG_URL.find(json)?.groupValues?.get(1)?.let(JsonText::unescape)
            val price = MIN_PRICE.find(json)?.groupValues?.get(1)?.toIntOrNull()
            val fields = Fields(
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                cover = cover?.trim()?.takeIf { it.startsWith("https://") },
                price = price?.takeIf { it > 0 },
            )
            if (fields.title == null && fields.cover == null) return null
            return fields
        }
    }

    // ── 拼多多 ───────────────────────────────────────────────────────────

    /**
     * 拼多多：**内容层拿不到**（实测 `rawData` 首部 `needLogin:true`，`AccessToken`×4、
     * `prefetch`×9，JS 里还有 `react_anti_co_*.js` 反爬签名 —— 登录 + 反爬两道门），
     * 但分享链的**最终地址**（展示侧跟完重定向后拿到）里有可用的稳定信息：
     *
     *     goods1.html?goods_id=1012262932814&…&_oak_share_snapshot_num=29900&…
     *
     * - `goods_id`：内容身份（见 `PasscodeRules.duplicateIdentity` 的 `pdd-goods:` 分支）；
     * - `_oak_share_snapshot_num`：**分享快照价，单位分**（29900 = ¥299.00）。
     *
     * 另外它的通用分享信息**必须屏蔽**（实测 `og:title=拼多多商城`、
     * `og:image=https://funimg.pddpic.com/base/share_logo.jpg`）—— 那是平台 logo，
     * 不是商品内容；宁可不显示，也别显示错的。
     */
    object Pdd {
        private val GOODS_ID = Regex("""[?&]goods_id=(\d{6,})""")
        private val SNAPSHOT_PRICE = Regex("""[?&]_oak_share_snapshot_num=(\d{1,12})""")

        /** 拼多多的通用分享标语（不是商品名）。 */
        private const val GENERIC_TITLE = "拼多多商城"

        /** 通用分享图（不是商品主图）。 */
        private val GENERIC_COVER = Regex("""^https?://[a-z0-9.\-]*pddpic\.com/(?:base/)?(?:share_logo|logo)""")

        fun goodsId(url: String): String? = GOODS_ID.find(url)?.groupValues?.get(1)

        /** 分享快照价（分）；拿不到返回 null。 */
        fun snapshotPriceCents(url: String): Int? =
            SNAPSHOT_PRICE.find(url)?.groupValues?.get(1)?.toIntOrNull()

        fun isGenericTitle(title: String?): Boolean = title?.trim() == GENERIC_TITLE

        fun isGenericCover(coverUrl: String?): Boolean =
            coverUrl != null && GENERIC_COVER.containsMatchIn(coverUrl)

        /** 分 → `¥299` / `¥299.50`（整数价省掉小数）。 */
        fun formatPrice(cents: Int?): String? {
            if (cents == null || cents <= 0) return null
            val yuan = cents / 100
            val fen = cents % 100
            return if (fen == 0) "¥$yuan" else "¥$yuan.${fen.toString().padStart(2, '0')}"
        }
    }
}
