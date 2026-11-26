package io.github.kotoriinsky.tsuzuri.ui.ocr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主模块 UI 侧对独立 OCR 组件（`io.github.kotoriinsky.tsuzuri.ocr`）的轻量客户端：
 * - 检测独立组件安装状态与版本；
 * - 支持按镜像源/自定义地址在线下载组件 APK 并拉起系统安装器；
 * - 经 [OnnxOcr.AUTHORITY] 跨进程查询组件内模型状态、下发模型下载/取消/删除/释放指令。
 *
 * 主模块自身零 ONNX Runtime 依赖。
 */
internal object OnnxOcrRemote {

    private const val TAG = "FP-OnnxOcrRemote"
    private val PROVIDER_URI: Uri = Uri.parse("content://${OnnxOcr.AUTHORITY}")

    data class RemoteState(
        val pluginInstalled: Boolean,
        val pluginVersion: String = "",
        val installedTiers: Set<String> = emptySet(),
        val tierBytes: Map<String, Long> = emptyMap(),
        val dlState: String = OnnxOcr.DL_IDLE,
        val dlTier: String = "",
        val dlFile: String = "",
        val dlBytes: Long = 0L,
        val dlTotal: Long = 0L,
        val dlMessage: String = "",
    ) {
        val isDownloadingModel: Boolean get() = dlState == OnnxOcr.DL_DOWNLOADING
        val dlFraction: Float
            get() = if (dlTotal > 0L) (dlBytes.toFloat() / dlTotal).coerceIn(0f, 1f) else 0f
    }

    sealed interface PluginDownloadState {
        data object Idle : PluginDownloadState
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : PluginDownloadState {
            val fraction: Float
                get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        }

        data object ReadyToInstall : PluginDownloadState
        data class Failed(val message: String) : PluginDownloadState
    }

    private val _pluginDl = MutableStateFlow<PluginDownloadState>(PluginDownloadState.Idle)
    val pluginDownloadState: StateFlow<PluginDownloadState> = _pluginDl.asStateFlow()

    @Volatile
    private var pluginCancel: AtomicBoolean? = null

    fun isPluginInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(OnnxOcr.PLUGIN_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun queryState(context: Context): RemoteState {
        if (!isPluginInstalled(context)) return RemoteState(pluginInstalled = false)
        val pkgVer = runCatching {
            context.packageManager.getPackageInfo(OnnxOcr.PLUGIN_PACKAGE, 0).versionName.orEmpty()
        }.getOrDefault("")
        val reply = runCatching {
            context.contentResolver.call(PROVIDER_URI, OnnxOcr.METHOD_QUERY_STATE, null, null)
        }.getOrNull() ?: return RemoteState(pluginInstalled = true, pluginVersion = pkgVer)
        if (!reply.getBoolean(OnnxOcr.OUT_OK, false)) {
            return RemoteState(pluginInstalled = true, pluginVersion = pkgVer)
        }
        val tiers = reply.getStringArrayList(OnnxOcr.OUT_INSTALLED_TIERS).orEmpty().toSet()
        val rawBytes = reply.getLongArray(OnnxOcr.OUT_TIER_BYTES) ?: LongArray(0)
        val byteMap = buildMap<String, Long> {
            OnnxOcr.Tier.entries.forEachIndexed { idx, tier ->
                put(tier.id, rawBytes.getOrNull(idx) ?: 0L)
            }
        }
        return RemoteState(
            pluginInstalled = true,
            pluginVersion = reply.getString(OnnxOcr.OUT_VERSION).orEmpty().ifBlank { pkgVer },
            installedTiers = tiers,
            tierBytes = byteMap,
            dlState = reply.getString(OnnxOcr.OUT_DL_STATE) ?: OnnxOcr.DL_IDLE,
            dlTier = reply.getString(OnnxOcr.OUT_DL_TIER).orEmpty(),
            dlFile = reply.getString(OnnxOcr.OUT_DL_FILE).orEmpty(),
            dlBytes = reply.getLong(OnnxOcr.OUT_DL_BYTES, 0L),
            dlTotal = reply.getLong(OnnxOcr.OUT_DL_TOTAL, 0L),
            dlMessage = reply.getString(OnnxOcr.OUT_DL_MESSAGE).orEmpty(),
        )
    }

    fun startModelDownload(
        context: Context,
        tier: OnnxOcr.Tier,
        mirror: OnnxOcr.Mirror,
        customMirrorUrl: String?,
    ): Boolean {
        val args = Bundle().apply {
            putString(OnnxOcr.EXTRA_TIER, tier.id)
            putString(OnnxOcr.EXTRA_MIRROR, mirror.id)
            putString(OnnxOcr.EXTRA_CUSTOM_MIRROR, customMirrorUrl.orEmpty())
        }
        return runCatching {
            context.contentResolver.call(PROVIDER_URI, OnnxOcr.METHOD_START_DOWNLOAD, null, args)
                ?.getBoolean(OnnxOcr.OUT_OK, false) == true
        }.getOrDefault(false)
    }

    fun cancelModelDownload(context: Context) {
        runCatching { context.contentResolver.call(PROVIDER_URI, OnnxOcr.METHOD_CANCEL_DOWNLOAD, null, null) }
    }

    fun deleteTier(context: Context, tier: OnnxOcr.Tier) {
        val args = Bundle().apply { putString(OnnxOcr.EXTRA_TIER, tier.id) }
        runCatching { context.contentResolver.call(PROVIDER_URI, OnnxOcr.METHOD_DELETE_TIER, null, args) }
    }

    fun releaseCache(context: Context) {
        runCatching { context.contentResolver.call(PROVIDER_URI, OnnxOcr.METHOD_RELEASE, null, null) }
    }

    fun cancelPluginDownload() {
        pluginCancel?.set(true)
    }

    /** 按用户配置的组件 APK 地址（可套用镜像加速前缀）流式下载并拉起系统安装器。 */
    fun startPluginDownload(context: Context, rawUrl: String, mirror: OnnxOcr.PluginMirror) {
        if (_pluginDl.value is PluginDownloadState.Downloading) return
        val resolved = mirror.wrap(rawUrl)
        if (!resolved.startsWith("https://", ignoreCase = true) && !resolved.startsWith("http://", ignoreCase = true)) {
            _pluginDl.value = PluginDownloadState.Failed("请先填写有效的组件 APK 下载地址（http/https）")
            return
        }
        val appCtx = context.applicationContext
        val cancelled = AtomicBoolean(false)
        pluginCancel = cancelled
        _pluginDl.value = PluginDownloadState.Downloading(0L, 0L)

        Thread({
            val dir = File(appCtx.cacheDir, "ocr_plugin").apply { mkdirs() }
            val part = File(dir, "ocr-runtime.apk.part")
            val target = File(dir, "ocr-runtime.apk")
            var conn: HttpURLConnection? = null
            try {
                var currentUrl = resolved
                repeat(7) {
                    val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("User-Agent", "Tsuzuri-OCR-Installer/1.0 (Android)")
                    }
                    val code = c.responseCode
                    if (code in 301..308) {
                        val loc = c.getHeaderField("Location")
                        c.disconnect()
                        if (loc.isNullOrBlank()) throw IllegalStateException("重定向缺少 Location（HTTP $code）")
                        currentUrl = URL(URL(currentUrl), loc).toString()
                    } else if (code in 200..299) {
                        conn = c
                        return@repeat
                    } else {
                        c.disconnect()
                        throw IllegalStateException("服务器返回 HTTP $code")
                    }
                }
                val active = conn ?: throw IllegalStateException("重定向次数过多")
                val total = active.contentLengthLong.coerceAtLeast(0L)
                var written = 0L
                var lastAt = 0L
                active.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 shl 10)
                        while (true) {
                            if (cancelled.get()) throw InterruptedException("已取消下载")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            val now = SystemClock.uptimeMillis()
                            if (now - lastAt >= 120L) {
                                lastAt = now
                                _pluginDl.value = PluginDownloadState.Downloading(written, total)
                            }
                        }
                        out.flush()
                    }
                }
                if (written < 64 * 1024L) {
                    part.delete()
                    throw IllegalStateException("下载文件体积异常（${written}B），请检查链接是否为 APK")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    throw IllegalStateException("安装包落盘失败")
                }
                _pluginDl.value = PluginDownloadState.ReadyToInstall
                launchInstaller(appCtx)
            } catch (t: Throwable) {
                runCatching { part.delete() }
                val msg = if (cancelled.get()) "已取消" else (t.message ?: t.javaClass.simpleName)
                Log.w(TAG, "下载 OCR 组件失败：$msg", t)
                _pluginDl.value = if (cancelled.get()) PluginDownloadState.Idle else PluginDownloadState.Failed(msg)
            } finally {
                runCatching { conn?.disconnect() }
                if (pluginCancel === cancelled) pluginCancel = null
            }
        }, "fp-ocr-apk-dl").start()
    }

    fun launchInstaller(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(OcrPluginInstallProvider.APK_URI, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    }.onFailure { Log.w(TAG, "拉起组件安装器失败", it) }.getOrDefault(false)
}
