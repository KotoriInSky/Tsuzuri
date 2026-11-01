package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 支付宝小程序分享口令。
 *
 * ⚠️ 样本用**真机取证的原始值**（两条真实分享链跳转后的地址片段 + 真实分享文案），
 * 但**未真机走完展示侧**（接线在下一笔）。
 */
class AlipayMiniProgramTest {

    /** 顺丰速运小程序：真实跳转后的地址（`scheme=` 是百分号编码的 alipays://）。 */
    private val sfRedirect =
        "https://render.alipay.com/p/s/i/?scheme=%61%6C%69%70%61%79%73%3A%2F%2F%70%6C%61%74%66%6F%72%6D%61%70%69%2F%73%74%61%72%74%61%70%70%3F%61%70%70%49%64%3D%32%30%31%39%30%35%30%37%36%34%33%32%31%39%31%37%26%70%61%67%65%3D%70%61%67%65%73%25%32%46%74%61%62%42%61%72%25%32%46%69%6E%64%65%78%25%32%46%69%6E%64%65%78"

    /** 京东快递小程序：同一机制，另一段 `scheme`。 */
    private val jdRedirect =
        "https://render.alipay.com/p/s/i/?scheme=%61%6C%69%70%61%79%73%3A%2F%2F%70%6C%61%74%66%6F%72%6D%61%70%69%2F%73%74%61%72%74%61%70%70%3F%61%70%70%49%64%3D%32%30%32%31%30%30%34%31%30%37%36%37%38%31%32%31"

    @Test
    fun `从跳转后的地址解出顺丰小程序目标与 appId`() {
        val t = AlipayMiniProgram.targetFromRedirectUrl(sfRedirect)
        assertEquals("2019050764321917", t?.let(AlipayMiniProgram::appId))
        assertEquals(true, t?.startsWith("alipays://platformapi/startapp"))
    }

    @Test
    fun `从跳转后的地址解出京东快递小程序目标与 appId`() {
        val t = AlipayMiniProgram.targetFromRedirectUrl(jdRedirect)
        assertEquals("2021004107678121", t?.let(AlipayMiniProgram::appId))
    }

    /** 真实分享文案（带形近字与 emoji 规避过滤）——名字段是明文。 */
    @Test
    fun `从分享文案取小程序名`() {
        val sf = "https://www.wmslz.com/s/2uVvyhy37pf#🔍f.u.制🔎此消息，打开💯吱付寳💯🔍搜，体验顺丰速运小程序  z:/s CZ5949 \$265"
        val jd = "https://www.wmslz.com/s/RLmMCd7376I#💪复值💪此消息，打开📸支怤宝📸搜一搜，体验京东快递小程序  i:/d CZ8742 \$872"
        assertEquals("顺丰速运", AlipayMiniProgram.nameFromText(sf))
        assertEquals("京东快递", AlipayMiniProgram.nameFromText(jd))
    }

    @Test
    fun `不是小程序跳转页时返回 null`() {
        assertNull(AlipayMiniProgram.targetFromRedirectUrl(""))
        assertNull(AlipayMiniProgram.targetFromRedirectUrl("https://www.wmslz.com/s/abc"))
        // scheme 参数存在但不是小程序
        assertNull(
            AlipayMiniProgram.targetFromRedirectUrl(
                "https://render.alipay.com/p/s/i/?scheme=alipays%3A%2F%2Fplatformapi%2Fother",
            ),
        )
        assertNull(AlipayMiniProgram.nameFromText("就是一条普通文案"))
    }
}
