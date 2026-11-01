package io.github.kotoriinsky.tsuzuri.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import io.github.kotoriinsky.tsuzuri.ui.state.AppearanceStore
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.FrameworkBridge
import io.github.kotoriinsky.tsuzuri.ui.state.PreviewWallpaper
import io.github.kotoriinsky.tsuzuri.ui.state.loadAppCatalog

/**
 * 唯一 Activity。
 *
 * 它只做三件事：初始化三个 store、启动框架桥、把界面交给 Compose。
 * 业务逻辑一律不放这里 —— Activity 会被重建，而这里没有值得活在重建里的东西。
 *
 * ⚠️ 2026-10-17 删掉了 `requestWallpaperAccess()`（申请 `READ_EXTERNAL_STORAGE`）：
 * 目标 SDK 37 上这条权限已被 `READ_MEDIA_*` 取代、申请必被拒，而
 * `WallpaperManager.drawable` 读当前壁纸本来就不需要它。旧代码会弹一次注定失败的
 * 授权框 —— 用户点了「允许」也看不到壁纸，是比不给入口更糟的交互。
 * 现在预览底图的三条来源见 [PreviewWallpaper]（相册自选 / 系统壁纸 / root 读文件）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ConfigStore.init(this)
        AppearanceStore.init(this)
        // ⚠️ 之前漏了这句：WallpaperPreviewControl 切换「使用当前壁纸」直接写
        // AppSettings.previewWallpaper，而 AppSettings.prefs 是 lateinit ——
        // 真机点一下就 UninitializedPropertyAccessException 崩溃（19:28 实测）。
        AppSettings.init(this)

        // 只注册一次；重复调用由 FrameworkBridge 内部的 AtomicBoolean 挡住
        FrameworkBridge.start()

        // 应用清单在后台先扫一遍：首次进快捷清单 / 选应用时不用再等几秒（loadLabel 一两百个包），
        // 结果进进程内缓存，见 loadAppCatalog（用户 2026-10-22 报清单「要加载、有点卡手」）
        val appContext = applicationContext
        Thread({ kotlinx.coroutines.runBlocking { loadAppCatalog(appContext) } }, "fp-app-catalog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()

        setContent { AppRoot() }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚在 LSPosed 管理器里改了作用域，回来要立刻反映
        FrameworkBridge.refresh()
        // 也可能刚换了壁纸 -- 预览台要用当前那张，所以这里把缓存作废（下次进页面重读一次）
        PreviewWallpaper.invalidate()
    }
}
