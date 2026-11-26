package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenEntityResolverTest {

    @Test
    fun 识别B站BV号与链接并直达() {
        val action = ScreenEntityResolver.resolve("推荐看看这个视频 BV1xx411c7mD 太强了")
        assertNotNull(action)
        assertEquals(QrJumpResolver.Kind.PLATFORM, action.kind)
        assertTrue(action.canLaunch)
        assertTrue(action.primaryTarget!!.contains("BV1xx411c7mD"))
    }

    @Test
    fun 识别网页链接并清理追踪参数() {
        val action = ScreenEntityResolver.resolve("详情请看 https://example.com/post?id=42&utm_source=weibo&utm_medium=share")
        assertNotNull(action)
        assertEquals("https://example.com/post?id=42", action.primaryTarget)
        assertEquals("打开", action.actionLabel)
    }

    @Test
    fun 识别电子邮箱与电话号码() {
        val email = ScreenEntityResolver.resolve("商务合作请联系 hello.world@example.com 谢谢")
        assertNotNull(email)
        assertEquals("mailto:hello.world@example.com", email.primaryTarget)
        assertEquals("写邮件", email.actionLabel)

        val mobile = ScreenEntityResolver.resolve("快递员电话：138-0013-8000")
        assertNotNull(mobile)
        assertEquals("tel:13800138000", mobile.primaryTarget)
        assertEquals("拨打", mobile.actionLabel)

        val hotline = ScreenEntityResolver.resolve("客服热线 400-800-8820")
        assertNotNull(hotline)
        assertEquals("tel:4008008820", hotline.primaryTarget)
    }

    @Test
    fun 普通文字不误触发实体() {
        assertNull(ScreenEntityResolver.resolve("今天天气不错，适合出门散步"))
        assertNull(ScreenEntityResolver.resolve("订单编号 20261126"))
    }
}
