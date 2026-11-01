package io.github.kotoriinsky.tsuzuri.core

/** 原生胶囊中的场景建议标识与准入规则。 */
object ContextSuggestion {
    const val CUE_PREFIX = "tsuzuri.context."
    const val INSTALL = "context_install"
    const val MUSIC = "context_music"
    const val SCREEN_ON = "context_screen_on"

    fun ownsCue(id: String) = id.startsWith(CUE_PREFIX)

    /** 连接耳机时认作「听」的应用：音乐、本地播放器、播客与有声书。顺序即会话命中时的优先级。 */
    val LISTEN_APPS = listOf(
        "com.netease.cloudmusic", "com.tencent.qqmusic", "com.miui.player", "com.luna.music",
        "com.kugou.android", "cn.kuwo.player", "cmccwm.mobilemusic",
        "com.spotify.music", "com.apple.android.music", "com.google.android.apps.youtube.music",
        "com.amazon.mp3", "com.aspiro.tidal", "deezer.android.app", "com.soundcloud.android", "com.qobuz.music",
        "com.salt.music", "com.hiby.music", "com.maxmpz.audioplayer", "in.krosbits.musicolet",
        "com.neutroncode.mp", "com.fiio.music", "com.foobar2000.foobar2000",
        "com.ximalaya.ting.android", "app.podcast.cosmos", "fm.qingting.qtradio", "com.yuezhan.lrts",
        "com.miui.fm", "com.shinyv.cnr", "au.com.shiftyjelly.pocketcasts",
    )

    /** 先认正在播放/暂停的白名单会话，否则沿用小爱按使用排行给出的音乐类候选；都不可启动就不建议。 */
    fun pickListenApp(sessions: List<String>, predicted: String, launchable: (String) -> Boolean): String? =
        sessions.firstOrNull { it in LISTEN_APPS && launchable(it) }
            ?: predicted.takeIf { it.isNotBlank() && launchable(it) }

    fun shouldSuggestInstall(replacing: Boolean, launchable: Boolean) = !replacing && launchable

    const val LISTEN_PICKER_TITLE = "选择收听应用"

    /** 长按胶囊拉起「打开方式」时的标记；IntentResolver 只对带它的请求过滤并屏蔽偏好写入。 */
    const val EXTRA_LISTEN_PICK = "io.github.kotoriinsky.tsuzuri.extra.LISTEN_PICK"

    fun openTitle(applicationName: String) = "打开$applicationName"

    fun isDimTouch(group: Int, event: Int, summary: Int) = group == 0 && event == 2 && summary and 2 != 0

    fun keepScreenOn(source: String, foreground: String?, interactive: Boolean, locked: Boolean) =
        source.isNotBlank() && source == foreground && interactive && !locked

    fun acceptScreenOnStop(senderUid: Int, systemUiUid: Int, active: Boolean) =
        active && systemUiUid >= 0 && senderUid == systemUiUid
}
