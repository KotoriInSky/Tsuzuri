package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DoubanTopicShareTest {

    @Test
    fun `dispatch 壳解出小组帖与个人帖 id`() {
        assertEquals("502926560", DoubanTopicShare.topicId(
            "https://www.douban.com/doubanapp/dispatch?uri=%2Fgroup%2Ftopic%2F502926560%3F_spm_id%3DMjk3NDc4NjQx&_i=927155413b780ad"))
        assertEquals("502398781", DoubanTopicShare.topicId(
            "https://www.douban.com/doubanapp/dispatch?uri=%2Ftopic%2F502398781%3F_spm_id%3DMTgxMDE5MjA1&_i=927155573b780ad"))
    }

    @Test
    fun `直接的帖子地址`() {
        assertEquals("342931655", DoubanTopicShare.topicId("https://www.douban.com/group/topic/342931655/"))
        assertEquals("342931655", DoubanTopicShare.topicId("https://m.douban.com/group/topic/342931655/?_spm_id=x"))
    }

    @Test
    fun `条目页与外站不认`() {
        assertNull(DoubanTopicShare.topicId("https://movie.douban.com/subject/1292052/"))
        assertNull(DoubanTopicShare.topicId("https://www.douban.com/doubanapp/dispatch?uri=%2Fmovie%2F1292052"))
        assertNull(DoubanTopicShare.topicId("https://evil.com/group/topic/342931655/"))
    }

    @Test
    fun `图片只认 doubanio`() {
        assertEquals("https://img3.doubanio.com/view/group_topic/l/public/p1.jpg",
            DoubanTopicShare.imageUrl("https://img3.doubanio.com/view/group_topic/l/public/p1.jpg"))
        assertNull(DoubanTopicShare.imageUrl("http://img3.doubanio.com/a.jpg"))
        assertNull(DoubanTopicShare.imageUrl("https://evil.com/a.jpg"))
        assertNull(DoubanTopicShare.imageUrl(""))
    }
}
