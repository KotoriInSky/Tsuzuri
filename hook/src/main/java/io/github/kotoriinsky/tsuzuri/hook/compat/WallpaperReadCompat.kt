package io.github.kotoriinsky.tsuzuri.hook.compat

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable

/**
 * 在 **SystemUI 进程里**读桌面壁纸（预览底图用）。三条路按顺序试，全部反射、全部可降级：
 *
 *  1. `WallpaperManager.getBitmapAsUser(userId, hardware=false, FLAG_SYSTEM, returnDefault=true)` ——
 *     AOSP 隐藏 API。SystemUI 持有 `READ_WALLPAPER_INTERNAL`（真机 dumpsys 已确认），
 *     又是平台签名应用，不受隐藏 API 限制。`hardware=false`：硬件位图不能 `compress`。
 *  2. `WallpaperManager.getDrawable()` —— 公开 API，同一个权限门。
 *  3. `com.miui.miwallpaper.MiuiWallpaperManager.sInstance.getMiuiWallpaperPreview(1)` ——
 *     MIUI 壁纸服务给的预览图（反编译 SystemUI 确认：`static volatile sInstance`，
 *     `final Bitmap getMiuiWallpaperPreview(int)`）。**传 1（FLAG_SYSTEM = 桌面）**；
 *     SystemUI 自己的 `DesktopWallpaperManager` 传的是 4，那是它的别的用途，不是桌面壁纸。
 *
 * 任何一条抛异常或返回 null 都只是「这条路不通」，换下一条；三条都不通返回 null。
 */
object WallpaperReadCompat {

    /** 读到的位图与走的是哪条路（诊断日志与 provider 落盘都带上它）。 */
    class Read(val bitmap: Bitmap, val route: String)

    fun read(context: Context, onFail: (String, Throwable?) -> Unit): Read? {
        val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull()
        if (wm != null) {
            bitmapAsUser(wm, onFail)?.let { return Read(it, "getBitmapAsUser") }
            drawable(wm, onFail)?.let { return Read(it, "getDrawable") }
        }
        miuiPreview(context, onFail)?.let { return Read(it, "MiuiWallpaperManager") }
        return null
    }

    /** 当前前台用户。SystemUI 是单例进程（跑在 user 0），多用户下要读的是**当前**用户的壁纸。 */
    private fun currentUser(): Int = runCatching {
        android.app.ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
    }.getOrElse { android.os.Process.myUid() / 100000 }

    private fun bitmapAsUser(wm: WallpaperManager, onFail: (String, Throwable?) -> Unit): Bitmap? = try {
        val m = wm.javaClass.getMethod(
            "getBitmapAsUser",
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        (m.invoke(wm, currentUser(), false, WallpaperManager.FLAG_SYSTEM, true) as? Bitmap)
            .also { if (it == null) onFail("getBitmapAsUser 返回 null", null) }
    } catch (t: Throwable) {
        onFail("getBitmapAsUser 不可用", t)
        null
    }

    private fun drawable(wm: WallpaperManager, onFail: (String, Throwable?) -> Unit): Bitmap? = try {
        ((wm.drawable as? BitmapDrawable)?.bitmap)
            .also { if (it == null) onFail("getDrawable 不是位图", null) }
    } catch (t: Throwable) {
        onFail("getDrawable 失败", t)
        null
    }

    private fun miuiPreview(context: Context, onFail: (String, Throwable?) -> Unit): Bitmap? = try {
        val cls = Class.forName("com.miui.miwallpaper.MiuiWallpaperManager", false, context.classLoader)
        val instance = cls.getDeclaredField("sInstance").apply { isAccessible = true }.get(null)
        if (instance == null) {
            onFail("MiuiWallpaperManager 未初始化", null)
            null
        } else {
            val m = cls.getDeclaredMethod("getMiuiWallpaperPreview", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            (m.invoke(instance, WallpaperManager.FLAG_SYSTEM) as? Bitmap)
                .also { if (it == null) onFail("getMiuiWallpaperPreview 返回 null", null) }
        }
    } catch (t: Throwable) {
        onFail("MiuiWallpaperManager 不可用", t)
        null
    }
}
