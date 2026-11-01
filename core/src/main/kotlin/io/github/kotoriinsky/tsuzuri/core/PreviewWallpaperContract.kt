package io.github.kotoriinsky.tsuzuri.core

/**
 * 预览底图：**SystemUI → 设置界面**的壁纸交接契约。
 *
 * ## 为什么要绕 SystemUI
 *
 * 设置界面自己读不到壁纸：`WallpaperManager.getDrawable()` 要 `READ_WALLPAPER_INTERNAL`，
 * 只有系统应用拿得到。设备实测（2026-10-21）：
 *
 *  - `dumpsys package com.android.systemui` → `READ_WALLPAPER_INTERNAL: granted=true`；
 *  - `dumpsys wallpaper` → 桌面壁纸 `mBindSource=SET_STATIC`（**是静态图**）。
 *
 * 旧注释说「动态壁纸没有静态位图」是错的：`ImageWallpaper` 只是负责画它的服务，壁纸本身是
 * 用户设的静态图。读不到的真正原因是**权限**。所以由已经被我们寄生的 SystemUI 读、压缩、
 * 经本应用的 provider 交过来，设置界面不再需要 root。
 *
 * ## 流程
 *
 *  1. SystemUI（扇形小窗 / 侧滑索引任一开启时）在三个时机推一次：hook 加载时、
 *     `ACTION_WALLPAPER_CHANGED`、配置里的 [REQUEST_KEY] 变了（设置界面没有缓存时写它）。
 *  2. 推送 = `contentResolver.call(`[URI]`, `[METHOD_PUSH]`, null, extras)`，
 *     extras 里放 WebP 字节（[EXTRA_IMAGE]）而不是 `Bitmap`：Binder 事务上限约 1MB，
 *     960px 的 ARGB 位图本身就超了，压成 WebP 只有百 KB 量级。
 *  3. provider 校验调用方 uid 属于 SystemUI，落盘，通知界面刷新。
 */
object PreviewWallpaperContract {

    /** provider 的 authority（声明在 `ui` 的 AndroidManifest）。 */
    const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.wallpaper"

    /** `content://` 形式，给 `ContentResolver.call` 用。 */
    const val URI = "content://$AUTHORITY"

    /** 推送方法名。 */
    const val METHOD_PUSH = "push"

    /** extras：WebP 字节（`ByteArray`）。 */
    const val EXTRA_IMAGE = "image"

    /** extras：取图走的是哪条路（诊断用，见 hook 侧 `WallpaperReadCompat`）。 */
    const val EXTRA_ROUTE = "route"

    /** 只允许这个包的进程推送（provider 按调用方 uid 反查包名）。 */
    const val PUSHER_PACKAGE = "com.android.systemui"

    /**
     * 「请 SystemUI 推一次」的信号键，写在框架配置（RemotePreferences）里，值是时间戳。
     *
     * 只写远端、不进本机草稿、**不触发自动重载**（见 `ConfigStore.signal`）：
     * 为了要一张预览底图去重启 SystemUI 显然不成比例。`__` 前缀与 [Keys.CONFIG_VERSION]
     * 同一约定 —— 内部键，不是用户参数。
     */
    const val REQUEST_KEY = "__wallpaper_request"

    /** 压缩后图的**短边**上限（px）。 */
    const val TARGET_SHORT_SIDE = 960

    /** WebP 质量。预览底图通常还叠一层模糊，80 看不出损失。 */
    const val WEBP_QUALITY = 80

    /**
     * 缩放后的尺寸：短边压到 [TARGET_SHORT_SIDE]，**不放大**，等比，每边至少 1px。
     *
     * 为什么按短边而不是宽度：预览按「铺满裁切」画（`WallpaperBackdrop`），本机壁纸是
     * 2656×2656 的方图，放进竖长的屏幕框时清晰度由**高度**决定。按宽 480 缩（旧 root 路径的
     * 做法）会让框里只剩约 220px 宽、480px 高的源像素，放大两倍多，看得出糊。
     * 非正尺寸返回 `null`（调用方当作「没取到图」）。
     */
    fun targetSize(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val short = minOf(width, height)
        if (short <= TARGET_SHORT_SIDE) return width to height
        val ratio = TARGET_SHORT_SIDE.toDouble() / short
        return maxOf(1, Math.round(width * ratio).toInt()) to maxOf(1, Math.round(height * ratio).toInt())
    }
}
