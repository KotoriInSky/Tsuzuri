package io.github.kotoriinsky.tsuzuri.ui.tile

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import io.github.kotoriinsky.tsuzuri.core.GlobalRecognition

/**
 * 磁贴和快捷方式共用的触发：只发一条带身份的广播给 SystemUI（它只受理本模块 uid），
 * 由它收起面板、等收完再叫醒传送门截屏。
 */
internal object GlobalRecognitionRequest {
    private const val SYSTEM_UI = "com.android.systemui"

    fun send(context: Context) {
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        context.sendBroadcast(Intent(GlobalRecognition.ACTION_TILE).setPackage(SYSTEM_UI), null, options)
    }
}
