package io.github.kotoriinsky.tsuzuri.hook.feature.gesture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.PreviewWallpaperContract
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.compat.WallpaperReadCompat
import java.io.ByteArrayOutputStream

/**
 * SystemUI → 设置界面的**预览底图推送**（契约见 [PreviewWallpaperContract]）。
 *
 * ## 谁开、谁关
 *
 * 只给扇形小窗与侧滑索引的预览用，所以由这两个特性在 install / uninstall 里
 * [acquire] / [release]（与 [SystemUiEnv.ForegroundTracker] 同一种共享单例）。
 * 两个都关着 = 一个接收器、一个监听都不注册，与未安装模块一致（规范 §9）。
 *
 * ## 三个推送时机
 *
 *  1. 第一个持有者 [acquire] 时（= hook 加载 / 功能刚打开）；
 *  2. `ACTION_WALLPAPER_CHANGED`（用户换了壁纸）；
 *  3. 配置里的 [PreviewWallpaperContract.REQUEST_KEY] 变了（设置界面没有缓存时写它）。
 *
 * 读图、缩放、压缩都在自己的后台线程上做（壁纸解码是几十 ms 的活，绝不能占 SystemUI 主线程）。
 */
object PreviewWallpaperPush {

    private const val TAG = "FP-Wallpaper"

    private val owners = LinkedHashSet<String>()
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var receiver: BroadcastReceiver? = null
    private var store: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var appContext: Context? = null
    private var log: ((Int, String, Throwable?) -> Unit)? = null

    /** 特性 [owner] 开始需要预览底图。重复调用安全。 */
    @Synchronized
    fun acquire(owner: String, ctx: HookContext, app: Context) {
        val first = owners.isEmpty()
        owners.add(owner)
        if (!first) return
        appContext = app
        log = { p, m, t -> ctx.log(p, m, t) }
        val t = HandlerThread("tsuzuri-wallpaper").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        worker = h

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                schedulePush("壁纸已更换")
            }
        }
        val registered = runCatching {
            app.registerReceiver(r, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), null, h, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { ctx.log(Log.WARN, "预览底图：注册换壁纸广播失败（换壁纸后要重开设置页才更新）", it) }.isSuccess
        if (registered) receiver = r

        ctx.store?.let { s ->
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == PreviewWallpaperContract.REQUEST_KEY) schedulePush("设置界面请求")
            }
            runCatching { s.registerOnSharedPreferenceChangeListener(l) }
                .onSuccess { store = s; listener = l }
                .onFailure { ctx.log(Log.WARN, "预览底图：注册请求监听失败", it) }
        }
        schedulePush("特性启动（$owner）")
    }

    /** 特性 [owner] 不再需要。最后一个持有者释放时全部拆掉。重复调用安全。 */
    @Synchronized
    fun release(owner: String) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        receiver?.let { r -> appContext?.let { c -> runCatching { c.unregisterReceiver(r) } } }
        receiver = null
        listener?.let { l -> store?.let { s -> runCatching { s.unregisterOnSharedPreferenceChangeListener(l) } } }
        listener = null
        store = null
        thread?.quitSafely()
        thread = null
        worker = null
        appContext = null
        log = null
    }

    @Synchronized
    private fun schedulePush(reason: String) {
        val h = worker ?: return
        // 短时间内连着来几次（换壁纸会连发、设置界面重进页面）只推最后一次
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ push(reason) }, 300L)
    }

    private fun push(reason: String) {
        val app = appContext ?: return
        val say = log ?: return
        val started = System.nanoTime()
        val read = WallpaperReadCompat.read(app) { msg, t -> say(Log.INFO, "预览底图：$msg", t) }
        if (read == null) {
            say(Log.WARN, "预览底图：三条读取路径都失败，设置界面将用示意底图（$reason）", null)
            return
        }
        val src = read.bitmap
        val size = PreviewWallpaperContract.targetSize(src.width, src.height) ?: return
        val bytes = try {
            val scaled = if (size.first == src.width && size.second == src.height) {
                src
            } else {
                Bitmap.createScaledBitmap(src, size.first, size.second, true)
            }
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, PreviewWallpaperContract.WEBP_QUALITY, out)
                // 只回收自己建的那张；src 可能是 WallpaperManager 缓存着的对象
                if (scaled !== src) scaled.recycle()
                out.toByteArray()
            }
        } catch (t: Throwable) {
            say(Log.WARN, "预览底图：压缩失败", t)
            return
        }
        val ok = runCatching {
            app.contentResolver.call(
                Uri.parse(PreviewWallpaperContract.URI),
                PreviewWallpaperContract.METHOD_PUSH,
                null,
                Bundle().apply {
                    putByteArray(PreviewWallpaperContract.EXTRA_IMAGE, bytes)
                    putString(PreviewWallpaperContract.EXTRA_ROUTE, read.route)
                },
            )?.getBoolean("accepted", false) == true
        }.onFailure { say(Log.WARN, "预览底图：交给设置界面失败（应用未安装或 provider 拒收）", it) }.getOrDefault(false)
        val ms = (System.nanoTime() - started) / 1_000_000
        say(
            if (ok) Log.INFO else Log.WARN,
            "预览底图：${if (ok) "已推送" else "未被接收"} 源 ${src.width}×${src.height} → " +
                "${size.first}×${size.second} ${bytes.size / 1024}KB 路径=${read.route} 用时 ${ms}ms（$reason）",
            null,
        )
    }
}
