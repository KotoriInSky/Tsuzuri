package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 国际单号的钉子。合法样例取自 jkeen/tracking_number_data 各 courier 的 `test_numbers.valid`（MIT）；
 * 非法样例是把合法样例的校验位改掉 —— 校验位就是这些规则防误识别的关键。
 */
class ExpressIntlTest {

    private fun match(text: String) = PasscodeRules.match(text)

    @Test
    fun `S10 校验位`() {
        assertTrue(ExpressIntl.isValidS10("RB123456785GB"))
        assertTrue(ExpressIntl.isValidS10("RB123456785US"))
        assertFalse(ExpressIntl.isValidS10("RB123456789GB"))
        assertEquals("中国邮政", PasscodeIsland.carrierName("EA123456785CN"))
        assertEquals("香港邮政", PasscodeIsland.carrierName("RB123456785HK"))
    }

    @Test
    fun `UPS 1Z 校验位 大小写都认`() {
        for (n in listOf("1Z5R89390357567127", "1Z879E930346834440")) {
            val m = assertNotNull(match("UPS $n"), n)
            assertEquals("express_intl", m.ruleId)
            assertEquals(n, m.core)
            assertEquals("UPS", PasscodeIsland.carrierName(n))
        }
        assertEquals("1Z5R89390357567127", match("1z5r89390357567127")?.core)
        assertNull(match("1Z5R89390357567128"))
    }

    @Test
    fun `亚马逊 云途 DHL 件号`() {
        assertEquals("亚马逊物流", PasscodeIsland.carrierName(assertNotNull(match("TBA000000000000")?.core)))
        val yun = assertNotNull(match("云途 YT2229521272164446"))
        assertEquals("express_intl", yun.ruleId)
        assertEquals("云途物流", PasscodeIsland.carrierName("YT2229521272164446"))
        assertEquals("DHL", PasscodeIsland.carrierName(assertNotNull(match("JJD0099999999")?.core)))
    }

    @Test
    fun `圆通 京东不被国际规则抢走`() {
        assertEquals("圆通速递", PasscodeIsland.carrierName("YT1234567890123"))
        assertEquals("express_prefixed", match("YT1234567890123")?.ruleId)
        assertEquals("京东物流", PasscodeIsland.carrierName("JDX1234567890123"))
        assertEquals("express_prefixed", match("JDX1234567890123")?.ruleId)
    }

    @Test
    fun `纯数字国际单号必须有承运商名且校验通过`() {
        val fedex = assertNotNull(match("FedEx 单号 986578788855"))
        assertEquals("express_intl_numeric", fedex.ruleId)
        assertEquals("FedEx", PasscodeIsland.carrierName("986578788855", "FedEx 单号 986578788855"))
        // 没有承运商名：不认（与国内订单号同形）
        assertNull(match("986578788855"))
        // 有承运商名但校验位错：不认
        assertNull(match("FedEx 986578788856"))
        assertNotNull(match("DHL 3318810025"))
        assertNull(match("DHL 3318810026"))
        assertNotNull(match("USPS 03071790000523483741"))
        assertNull(match("USPS 03071790000523483742"))
    }

    @Test
    fun `校验算法本身`() {
        assertTrue(ExpressIntl.mod7("331881002", 5))
        assertTrue(ExpressIntl.weighted("98657878885", 5, intArrayOf(3, 1, 7, 3, 1, 7, 3, 1, 7, 3, 1), 11, 10))
        assertTrue(ExpressIntl.mod10("0307179000052348374", 1, evens = 3, odds = 1))
    }
}
