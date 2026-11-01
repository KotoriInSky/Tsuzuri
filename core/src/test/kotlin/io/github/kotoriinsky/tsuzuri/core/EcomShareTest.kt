package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 京东 / 抖音商城的字段提取。
 *
 * ⚠️ 样本按**真机页面的真实结构**合成（字段名、相对路径、URL 编码都照实），
 * 没有搬运真实用户内容。取证结论见 [EcomShare] 的注释。
 */
class EcomShareTest {

    private val jdPage = """
        <html><head><title>Apple/苹果/苹果 iPhone 17 【需当面激活】…【图片 价格 品牌 评论】-京东</title></head>
        <body><script>window._itemOnly = ({
        "skuId":"10181383335547","skuName":"Apple/苹果/苹果 iPhone 17 支持移动联通电信 5G 双卡双待手机 青雾蓝色 256GB 官方标配",
        "imageurl":"jfs/t1/438646/34/13072/19851/6a13c004Fba91bb65/008332080f70a.jpg",
        "allnum":"0","book":"false"}, if (window._itemOnly && _itemOnly.item) {})</script></body></html>
    """.trimIndent()

    @Test
    fun `京东取 skuName 与主图并拼出 CDN 地址`() {
        val f = EcomShare.Jd.parse(jdPage)
        assertEquals(
            "Apple/苹果/苹果 iPhone 17 支持移动联通电信 5G 双卡双待手机 青雾蓝色 256GB 官方标配",
            f?.title,
        )
        assertEquals(
            "https://img14.360buyimg.com/jfs/t1/438646/34/13072/19851/6a13c004Fba91bb65/008332080f70a.jpg",
            f?.cover,
        )
    }

    @Test
    fun `京东从各种 URL 形态取 sku`() {
        assertEquals("10181383335547", EcomShare.Jd.sku("https://item.m.jd.com/product/10181383335547.html?utm_source=x"))
        assertEquals("100012043978", EcomShare.Jd.sku("https://item.jd.com/100012043978.html"))
        assertNull(EcomShare.Jd.sku("https://3.cn/-362uUfS"))
    }

    @Test
    fun `京东没有 _itemOnly 时返回 null`() {
        assertNull(EcomShare.Jd.parse(""))
        assertNull(EcomShare.Jd.parse("<html><title>京东</title></html>"))
    }

    /** 抖音商城：字段全在**跳转后 URL** 的 `goods_detail` 里（URL 编码的 JSON）。 */
    private val dyMallUrl = "https://haohuo.jinritemai.com/ecommerce/trade/detail/index.html?alkey=1128_99514375927" +
        "&goods_detail=%7B%22title%22%3A%22AIUV%E9%80%82%E7%94%A8%E5%B0%8F%E7%B1%B317promax%E5%85%89%E6%A0%85" +
        "%E6%89%8B%E6%9C%BA%E5%A3%B3%22%2C%22sales%22%3A23%2C%22img%22%3A%7B%22url_list%22%3A%5B%22https%3A%2F%2F" +
        "p3-item.ecombdimg.com%2Fimg%2Fx.png%22%5D%7D%2C%22min_price%22%3A2990%7D&id=3818580136710766755"

    @Test
    fun `抖音商城从跳转 URL 取标题封面与价格`() {
        val f = EcomShare.DouyinMall.parseFromUrl(dyMallUrl)
        assertEquals("AIUV适用小米17promax光栅手机壳", f?.title)
        assertEquals("https://p3-item.ecombdimg.com/img/x.png", f?.cover)
        assertEquals(2990, f?.price)
    }

    @Test
    fun `抖音商城非商品地址返回 null`() {
        assertNull(EcomShare.DouyinMall.parseFromUrl(""))
        assertNull(EcomShare.DouyinMall.parseFromUrl("https://haohuo.jinritemai.com/ecommerce/trade/detail/index.html?id=1"))
    }

    /** 拼多多：最终地址里只有 `goods_id` 与快照价可用；通用分享图/标题必须被识别出来。 */
    private val pddUrl = "https://mobile.yangkeduo.com/goods1.html?goods_id=1012262932814&page_from=35" +
        "&pxq_secret_key=6BEMTW5F3RI7PLQXYB7REJNNQLUYWFK5SHDIR4USL6B4VVXB55SQ" +
        "&_oak_share_snapshot_num=29900&_oak_share_time=1791266266&share_uin=MG3ZZ6HYMVIE3FMKEPNVHD62HI_GEXDA"

    @Test
    fun `拼多多取 goods_id 与快照价`() {
        assertEquals("1012262932814", EcomShare.Pdd.goodsId(pddUrl))
        assertEquals(29900, EcomShare.Pdd.snapshotPriceCents(pddUrl))
        assertEquals("¥299", EcomShare.Pdd.formatPrice(29900))
        assertEquals("¥29.90", EcomShare.Pdd.formatPrice(2990))
        assertNull(EcomShare.Pdd.formatPrice(0))
        assertNull(EcomShare.Pdd.formatPrice(null))
    }

    /** 同一商品的不同分享链（每次都变的 pxq_secret_key / share_uin）必须算出**同一个身份**。 */
    @Test
    fun `拼多多身份只认 goods_id`() {
        val another = "https://mobile.yangkeduo.com/goods1.html?goods_id=1012262932814" +
            "&pxq_secret_key=DIFFERENT&share_uin=OTHER&refer_share_id=xyz&_oak_share_time=1"
        val a = PasscodeRules.match(pddUrl)
        val b = PasscodeRules.match(another)
        assertEquals("pdd-goods:1012262932814", a?.let { PasscodeRules.duplicateIdentity(it) })
        assertEquals(
            a?.let { PasscodeRules.duplicateIdentity(it) },
            b?.let { PasscodeRules.duplicateIdentity(it) },
        )
        // 短链（只有 ps 码）拿不到 goods_id —— 由展示侧跟完重定向后再算身份
        assertNull(PasscodeRules.match("https://mobile.yangkeduo.com/goods1.html?ps=vl2KFoTxju")?.let {
            PasscodeRules.duplicateIdentity(it)
        })
    }

    /** 通用分享图/标题不是商品内容（实测 `share_logo.jpg` / `拼多多商城`）。 */
    @Test
    fun `拼多多通用分享图与标题被识别出来`() {
        assertEquals(true, EcomShare.Pdd.isGenericCover("https://funimg.pddpic.com/base/share_logo.jpg"))
        assertEquals(true, EcomShare.Pdd.isGenericTitle("拼多多商城"))
        assertEquals(false, EcomShare.Pdd.isGenericTitle("某件真商品名"))
        assertEquals(false, EcomShare.Pdd.isGenericCover("https://funimg.pddpic.com/goods/real.jpg"))
    }
}
