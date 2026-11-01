package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kotoriinsky.tsuzuri.core.PreviewWallpaperContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 预览台要用**当前桌面的那张壁纸**。
 *
 * ## 设置界面自己读不到，由 SystemUI 交过来
 *
 * 读壁纸要 `READ_WALLPAPER_INTERNAL`，只有系统应用拿得到；本应用拿不到，
 * `WallpaperManager.getDrawable()` 在这里只会抛 SecurityException 或返回 null。
 * （旧注释把原因写成「HyperOS 是动态壁纸、没有静态位图」，是错的：真机 `dumpsys wallpaper`
 * 显示 `mBindSource=SET_STATIC`，`ImageWallpaper` 只是负责画它的服务。）
 *
 * 所以现在由 SystemUI（我们本来就寄生在里面，且持有该权限）读图、缩到短边 960、
 * 压成 WebP，经 [PreviewWallpaperProvider] 落到本应用私有目录。契约见
 * [PreviewWallpaperContract]。**不再需要 root**：原来的 `su -c cp /data/system/users/0/wallpaper`
 * 那条路与「Root 授权未通过」提示一起删掉了。
 *
 * ## 取图顺序
 *
 *  1. **相册自选**：用户明确挑的那张，不该被系统壁纸盖掉（否则「选一张图」这个操作看起来没反应）；
 *  2. **SystemUI 推送**的系统壁纸；
 *  3. 都没有 → `bitmap = null`，预览画**中性示意底图**并标注「示意底图」。
 *     绝不兜一张看着像真壁纸的默认图 —— 用户会相信预览。
 *
 * 没有推送文件时向 SystemUI 发一次请求（[requestPush]），推到之后 provider 会调 [invalidate]，
 * 预览自动换成真壁纸。
 */
object PreviewWallpaper {
    var revision by mutableIntStateOf(0)
        private set

    private const val TAG = "FP-Ui"

    /** 没读到时的原因（i18n 键）。 */
    const val REASON_NOT_PUSHED = "fan_preview.wallpaper.not_pushed"
    const val REASON_UNREADABLE = "fan_preview.wallpaper.missing"

    /** 壁纸从哪来 —— 界面据此挂标签（来源必须可见）。 */
    enum class Source {
        /** SystemUI 读到并推过来的系统壁纸。 */
        SYSTEM,

        /** 用户从相册挑的自定义底图（存在应用私有目录里，不改系统壁纸）。 */
        CUSTOM,

        /** 都没拿到，画的是示意底图。 */
        NONE,
    }

    /**
     * 读壁纸的结果。来源和位图绑在一起返回：界面既要画图、又要如实标注来源，
     * 分两次取会在极端情况下不一致 ——「标签说系统壁纸、图其实是示意」最伤信任。
     */
    class Result(
        val bitmap: ImageBitmap?,
        val source: Source,
        /** 没读到时的原因（i18n 键），`null` = 读到了。 */
        val reasonKey: String? = null,
    ) {
        companion object {
            val NONE = Result(null, Source.NONE, REASON_UNREADABLE)
        }
    }

    @Volatile
    private var cached: Result = Result.NONE

    @Volatile
    private var resolved: Boolean = false

    /** 本进程里已经请求过一次推送（避免每次进页面都写配置）。 */
    @Volatile
    private var requested: Boolean = false

    /** 读一次就够；换了壁纸 / 收到新推送由 [invalidate] 清。 */
    suspend fun load(context: Context): Result = withContext(Dispatchers.IO) {
        if (resolved) return@withContext cached
        val r = read(context)
        cached = r
        resolved = true
        r
    }

    /** 壁纸变化 / 从后台回来 / 收到 SystemUI 推送时调用。 */
    fun invalidate() {
        cached = Result.NONE
        resolved = false
        revision++
    }

    // ── 自定义底图：用户从相册挑一张当预览底图 ─────────────────────────────
    //
    // Photo Picker 由系统代理读取，不需要任何权限。它只作为「预览底图」使用，
    // 不改系统壁纸 —— 这一点界面上必须写清。

    private const val CUSTOM_FILE = "preview_wallpaper_custom.bin"

    /** 用户是否指定过自定义底图。 */
    fun hasCustom(context: Context): Boolean = customFile(context).exists()

    /** 从相册选一张图存进私有目录。 */
    suspend fun saveCustom(context: Context, uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                customFile(context).outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            invalidate()
            true
        }.getOrDefault(false)
    }

    /** 清掉自定义底图，回到「系统壁纸 / 示意底图」。 */
    fun clearCustom(context: Context) {
        customFile(context).delete()
        invalidate()
    }

    private fun customFile(context: Context) = File(context.cacheDir, CUSTOM_FILE)

    // ── SystemUI 推送 ─────────────────────────────────────────────────────

    private const val SYSTEM_FILE = "preview_wallpaper_system.webp"

    /** 推送文件（provider 写、这里读）。放 filesDir：cacheDir 可能被系统清，清了就要再请求一次。 */
    internal fun systemFile(context: Context) = File(context.filesDir, SYSTEM_FILE)

    /**
     * 请 SystemUI 推一次：写配置里的信号键（只写远端、不触发自动重载，见 [ConfigStore.signal]）。
     * 本进程只请求一次；SystemUI 侧在换壁纸时会自己推，不需要我们反复要。
     */
    private fun requestPush() {
        if (requested) return
        requested = ConfigStore.signal(PreviewWallpaperContract.REQUEST_KEY, System.currentTimeMillis())
        Log.i(TAG, "预览壁纸：没有推送文件，" + if (requested) "已请求 SystemUI 推送" else "请求失败（框架未连接）")
    }

    private fun read(context: Context): Result {
        decode(customFile(context))?.let { return Result(it.asImageBitmap(), Source.CUSTOM) }
        val f = systemFile(context)
        if (!f.exists() || f.length() <= 0L) {
            requestPush()
            return Result(null, Source.NONE, REASON_NOT_PUSHED)
        }
        val bmp = decode(f) ?: return Result(null, Source.NONE, REASON_UNREADABLE)
        Log.i(TAG, "预览壁纸：读取 SystemUI 推送 " + bmp.width + "×" + bmp.height + "（" + f.length() / 1024 + "KB）")
        return Result(bmp.asImageBitmap(), Source.SYSTEM)
    }

    /**
     * 解码。推送文件已由 SystemUI 缩好，自选图可能是相机原图，所以统一按
     * [PreviewWallpaperContract.TARGET_SHORT_SIDE] 抽样（先读尺寸算 `inSampleSize`，不整张解进内存）。
     */
    private fun decode(f: File): Bitmap? = try {
        if (!f.exists() || f.length() <= 0L) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                val short = minOf(bounds.outWidth, bounds.outHeight)
                while (short / (sample * 2) >= PreviewWallpaperContract.TARGET_SHORT_SIDE) sample *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "预览壁纸：解码失败 " + t.javaClass.simpleName)
        null
    }
}
