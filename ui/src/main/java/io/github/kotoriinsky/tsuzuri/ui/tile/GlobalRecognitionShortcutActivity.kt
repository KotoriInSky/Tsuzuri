package io.github.kotoriinsky.tsuzuri.ui.tile

import android.app.Activity
import android.os.Bundle

/**
 * 「全局识别」应用快捷方式的跳板：长按桌面图标、或把快捷方式放进扇形小窗都走这里。
 *
 * 自己不显示任何东西（`Theme.NoDisplay`），发完请求立刻结束；SystemUI 那边会等
 * [io.github.kotoriinsky.tsuzuri.core.GlobalRecognition.COLLAPSE_WAIT_MS] 再截屏，这个空界面早已退场。
 */
class GlobalRecognitionShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlobalRecognitionRequest.send(this)
        finish()
    }
}
