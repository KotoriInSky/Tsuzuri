package io.github.kotoriinsky.tsuzuri.ui.tile

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.kotoriinsky.tsuzuri.core.CleanStatusBar

/**
 * 下拉快捷开关「完美状态栏」：截图前点一下进入，截完再点一下退出。
 *
 * 为什么不写共享配置：`ConfigStore` 要等主界面绑上框架服务才写得到远端，快捷开关单独起来时拿不到。
 * 所以直接给 SystemUI 发 [CleanStatusBar.ACTION]，并用 `setShareIdentityEnabled` 交出发送方身份，
 * SystemUI 侧（`CleanStatusBarFeature`）只受理本模块 uid。
 *
 * 开关状态只存在本地：演示模式也可能被别处退出（录屏结束、系统设置），那时这里的状态会落后一次，
 * 再点一下就对齐 —— 宁可多点一下，也不去轮询 SystemUI。
 */
class CleanStatusBarTile : TileService() {

    private val store get() = getSharedPreferences("clean_statusbar_tile", Context.MODE_PRIVATE)

    override fun onStartListening() {
        render(store.getBoolean(KEY_ON, false))
    }

    override fun onClick() {
        val on = !store.getBoolean(KEY_ON, false)
        store.edit().putBoolean(KEY_ON, on).apply()
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        sendBroadcast(
            Intent(CleanStatusBar.ACTION).setPackage(SYSTEM_UI).putExtra(CleanStatusBar.EXTRA_ON, on),
            null,
            options,
        )
        render(on)
    }

    private fun render(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private companion object {
        const val KEY_ON = "on"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
