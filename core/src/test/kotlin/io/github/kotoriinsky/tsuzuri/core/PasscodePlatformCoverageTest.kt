package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PasscodePlatformCoverageTest {
    @Test
    fun `酷安分享保留标题并选择图文模板`() {
        val match = PasscodeRules.match("【一款好用的应用】https://www.coolapk.com/feed/67059783")
        assertEquals("酷安", match?.platform?.name)
        assertEquals("com.coolapk.market", match?.platform?.pkg)
        assertEquals(PasscodeIsland.ContentCategory.IMAGE_TEXT, match?.category)
        assertEquals("一款好用的应用", match?.core)
    }

    @Test
    fun `Spotify 与 Apple Music 歌曲链接选择音乐模板`() {
        val spotify = PasscodeRules.match("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
        assertEquals("com.spotify.music", spotify?.platform?.pkg)
        assertEquals(PasscodeIsland.ContentCategory.MUSIC, spotify?.category)

        val apple = PasscodeRules.match(
            "https://music.apple.com/us/album/never-gonna-give-you-up/1773292758?i=1773293184",
        )
        assertEquals("com.apple.android.music", apple?.platform?.pkg)
        assertEquals(PasscodeIsland.ContentCategory.MUSIC, apple?.category)
    }
}
