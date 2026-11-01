package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.PreviewWallpaperContract
import java.io.File

/**
 * 接收 SystemUI 推来的预览底图（契约见 [PreviewWallpaperContract]）。
 *
 * 与 [HookStatusProvider] 同一套纪律：只开 `call`，**按真实调用 uid 反查包名**，
 * 不是 SystemUI 就拒；不接受任何别的写入。收下的只是一张缩小过的图，写进私有目录，
 * 只给本应用的预览台用。
 */
class PreviewWallpaperProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != PreviewWallpaperContract.METHOD_PUSH || extras == null) return null
        val ctx = context ?: return null
        val callers = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (PreviewWallpaperContract.PUSHER_PACKAGE !in callers) throw SecurityException("只接受系统界面推送")
        val bytes = extras.getByteArray(PreviewWallpaperContract.EXTRA_IMAGE) ?: return null
        // 上限给 Binder 事务上限（约 1MB）留余量；正常推送是百 KB 量级
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        // 先验证是一张能解的图再落盘：坏数据落进去，预览会一直显示「无法读取」
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val target = PreviewWallpaper.systemFile(ctx)
        // 先写临时文件再改名：预览台可能正在读旧文件，半截文件会解码失败
        val tmp = File(target.parentFile, target.name + ".tmp")
        val ok = runCatching {
            tmp.writeBytes(bytes)
            tmp.renameTo(target) || run { target.delete(); tmp.renameTo(target) }
        }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            return null
        }
        val route = extras.getString(PreviewWallpaperContract.EXTRA_ROUTE).orEmpty().take(40)
        Log.i("FP-Ui", "预览壁纸：收到 SystemUI 推送 ${bounds.outWidth}×${bounds.outHeight} ${bytes.size / 1024}KB 路径=$route")
        // 同进程：直接让预览台重读（界面没开时这只是改一个计数）
        PreviewWallpaper.invalidate()
        return Bundle().apply { putBoolean("accepted", true) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val MAX_BYTES = 900 * 1024
    }
}
