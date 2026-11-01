package io.github.kotoriinsky.tsuzuri.ui.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 下拉快捷开关「全局识别」：点一下识别当前整屏。
 *
 * 请求经 [GlobalRecognitionRequest] 发给 SystemUI，由它收起控制中心、等面板收完再叫醒传送门截屏
 * —— 在这里直接截，截到的是控制中心自己。
 * 特性关着时 SystemUI 侧没有接收器，点了什么都不发生。锁屏时先解锁，不在锁屏上截图。
 */
class GlobalRecognitionTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { GlobalRecognitionRequest.send(this) } else GlobalRecognitionRequest.send(this)
    }
}
