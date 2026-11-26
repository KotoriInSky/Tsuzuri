package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareMetaParsersTest {

    @Test
    fun `磁力链解码中文文件名与电驴链解码文件名和体积`() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=%E6%B5%8B%E8%AF%95%20Film+1080p.mkv"
        assertEquals("测试 Film 1080p.mkv", MagnetEd2kMeta.magnetCore(magnet))

        val ed2k = "ed2k://|file|%E6%B5%8B%E8%AF%95.zip|1524713390|0123456789ABCDEF0123456789ABCDEF|/"
        assertEquals("测试.zip · 1.42 GB", MagnetEd2kMeta.ed2kCore(ed2k))
    }

    @Test
    fun `网盘提取码同时支持 URL 参数与正文提取`() {
        assertEquals("9k2m", MagnetEd2kMeta.extractCloudPassword("链接: https://pan.baidu.com/s/1abc 提取码：9k2m"))
        assertEquals("ab12", MagnetEd2kMeta.extractCloudPassword("分享", "https://pan.quark.cn/s/abcdef12?pwd=ab12"))
        assertNull(MagnetEd2kMeta.extractCloudPassword("普通分享链接无密码"))
    }

    @Test
    fun `网易云单曲匿名接口解析标题歌手与高清封面并核对 ID`() {
        assertEquals("665343", NeteaseMusicShare.songId("https://music.163.com/song?id=665343"))
        assertEquals("665343", NeteaseMusicShare.songId("https://y.music.163.com/m/song?id=665343&userid=1"))
        assertNull(NeteaseMusicShare.songId("https://music.163.com/playlist?id=665343"))

        val json = """
            {"songs":[{"name":"スピカテリブル","id":665343,"artists":[{"name":"内田彩"},{"name":"南ことり"}],
            "album":{"picUrl":"http://p1.music.126.net/abc/123.jpg"}}]}
        """.trimIndent()
        val f = NeteaseMusicShare.parse(json, "665343")
        assertNotNull(f)
        assertEquals("スピカテリブル", f.title)
        assertEquals("内田彩 / 南ことり", f.author)
        assertEquals("https://p1.music.126.net/abc/123.jpg?param=320y320", f.cover)
        assertNull(NeteaseMusicShare.parse(json, "999999"))
    }

    @Test
    fun `B站直播间接口解析标题状态分区封面并兼容短号核对`() {
        assertEquals("6", BiliLiveShare.roomId("https://live.bilibili.com/6?broadcast_type=0"))
        assertEquals("7734200", BiliLiveShare.roomId("https://live.bilibili.com/blanc/7734200"))

        val json = """
            {"code":0,"data":{"room_id":7734200,"short_id":6,"live_status":1,"area_name":"游戏赛事",
            "title":"总决赛直播","user_cover":"https://i0.hdslb.com/bfs/live/cover.jpg"}}
        """.trimIndent()
        val f = BiliLiveShare.parse(json, "6")
        assertNotNull(f)
        assertEquals("总决赛直播", f.title)
        assertEquals("直播中 · 游戏赛事", f.subtitle)
        assertEquals("https://i0.hdslb.com/bfs/live/cover.jpg", f.cover)
        assertNull(BiliLiveShare.parse(json, "12345"))
    }

    @Test
    fun `夸克天翼123蓝奏公开分享元信息解析`() {
        assertEquals("abc12345", CloudShareMeta.quarkPwdId("https://pan.quark.cn/s/abc12345"))
        val q = CloudShareMeta.parseQuarkToken("""{"status":200,"code":0,"data":{"title":"固件合集"}}""")
        assertEquals("固件合集", q?.title)

        assertEquals("nEanIvr2Uv6z", CloudShareMeta.cloud189Code("https://cloud.189.cn/t/nEanIvr2Uv6z"))
        val c189 = CloudShareMeta.parseCloud189("""{"res_code":0,"fileName":"backup.zip","fileSize":10485760}""")
        assertEquals("backup.zip", c189?.title)
        assertEquals("10.0 MB", c189?.subtitle)

        assertEquals("XfT9-YxPod", CloudShareMeta.pan123ShareKey("https://www.123865.com/s/XfT9-YxPod.html"))
        val p123 = CloudShareMeta.parsePan123("""{"code":0,"data":{"Total":1,"InfoList":[{"FileName":"rom.img","Size":2147483648}]}}""")
        assertEquals("rom.img", p123?.title)
        assertEquals("2.00 GB", p123?.subtitle)

        assertTrue(CloudShareMeta.isLanzouUrl("https://wwa.lanzoux.com/iS52f26ttyab"))
        assertTrue(CloudShareMeta.isLanzouUrl("https://www.ilanzou.com/s/abcd1234"))
        val lz = CloudShareMeta.parseLanzouHtml("<title>Tsuzuri_v1.apk - 蓝奏云</title><div>文件大小：7.2 M</div><span>文件大小：7.2 MB</span>")
        assertEquals("Tsuzuri_v1.apk", lz?.title)
        assertEquals("7.2 MB", lz?.subtitle)
    }

    @Test
    fun `Steam 商店与 Bangumi 公开只读接口解析`() {
        assertEquals("1245620", SteamStoreShare.appId("https://store.steampowered.com/app/1245620/ELDEN_RING/"))
        val st = SteamStoreShare.parse(
            """{"1245620":{"success":true,"data":{"name":"艾尔登法环","is_free":false,"header_image":"https://cdn.akamai.steamstatic.com/header.jpg","price_overview":{"final_formatted":"¥ 149.00","discount_percent":50}}}}""",
            "1245620",
        )
        assertNotNull(st)
        assertEquals("艾尔登法环", st.title)
        assertEquals("¥ 149.00 (-50%)", st.subtitle)
        assertEquals("https://cdn.akamai.steamstatic.com/header.jpg", st.cover)

        assertEquals("1", BangumiShare.subjectId("https://bgm.tv/subject/1"))
        val bgm = BangumiShare.parse(
            """{"id":1,"name":"オリジナル","name_cn":"中文译名","date":"2026-04-01","rating":{"score":8.7},"images":{"common":"https://lain.bgm.tv/pic/cover/c/a.jpg"}}""",
            "1",
        )
        assertNotNull(bgm)
        assertEquals("中文译名", bgm.title)
        assertEquals("8.7 ★ · 2026", bgm.subtitle)
        assertEquals("https://lain.bgm.tv/pic/cover/c/a.jpg", bgm.cover)
    }
}
