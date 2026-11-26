package io.github.kotoriinsky.tsuzuri.ui.ocr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
 * - **在前台主模块进程执行模型 HTTP 下载**（避免 Android 15/16/17 后台进程网络冻结触发
 *   `Software caused connection abort`），下完后经 [OnnxOcr.METHOD_STORE_FILE] + `ParcelFileDescriptor`
 *   推送至独立组件私有目录原子落盘。
 *
 * 主模块自身零 ONNX Runtime 依赖。
 */
internal object OnnxOcrRemote {

    private const val TAG = "FP-OnnxOcrRemote"
    private const val MAX_RETRIES = 3
    private val PROVIDER_URI: Uri = Uri.parse("content://${OnnxOcr.AUTHORITY}")

    data class RemoteState(
        val pluginInstalled: Boolean,
        val pluginVersion: String = "",
        val installedTiers: Set<String> = emptySet(),
        val tierBytes: Map<String, Long> = emptyMap(),
    )

    sealed interface ModelDownloadState {
        data object Idle : ModelDownloadState
        data class Downloading(
            val tier: OnnxOcr.Tier,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : ModelDownloadState {
            val fraction: Float
                get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        }

        data class Done(val tier: OnnxOcr.Tier) : ModelDownloadState
        data class Failed(val tier: OnnxOcr.Tier, val message: String) : ModelDownloadState
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

    private val _modelDl = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val modelDownloadState: StateFlow<ModelDownloadState> = _modelDl.asStateFlow()

    private val _pluginDl = MutableStateFlow<PluginDownloadState>(PluginDownloadState.Idle)
    val pluginDownloadState: StateFlow<PluginDownloadState> = _pluginDl.asStateFlow()

    @Volatile
    private var modelCancel: AtomicBoolean? = null

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
        )
    }

    /** 在前台主模块进程执行模型下载并流式写入独立组件，彻底规避后台进程网络冻结断连。 */
    fun startModelDownload(
        context: Context,
        tier: OnnxOcr.Tier,
        mirror: OnnxOcr.Mirror,
        customMirrorUrl: String?,
    ) {
        if (_modelDl.value is ModelDownloadState.Downloading) return
        val appCtx = context.applicationContext
        val cancelled = AtomicBoolean(false)
        modelCancel = cancelled
        val files = OnnxOcr.modelFiles(tier)
        val totalExpected = tier.totalBytes
        _modelDl.value = ModelDownloadState.Downloading(tier, 0L, totalExpected)

        Thread({
            val tmpDir = File(appCtx.cacheDir, "ocr_dl_${tier.id}").apply { mkdirs() }
            var accumulated = 0L
            try {
                for (mf in files) {
                    if (cancelled.get()) throw InterruptedException("已取消")
                    val tmpFile = File(tmpDir, mf.localName)
                    val url = mirror.fileUrl(mf.repo, mf.remoteFile, customMirrorUrl)
                    Log.i(TAG, "前台下载 ${tier.id}/${mf.localName} ← $url")
                    val bytesWritten = downloadWithRetry(url, tmpFile, cancelled) { fileRead ->
                        _modelDl.value = ModelDownloadState.Downloading(
                            tier = tier,
                            downloadedBytes = accumulated + fileRead,
                            totalBytes = totalExpected,
                        )
                    }
                    if (bytesWritten < 512L) {
                        throw IllegalStateException("文件体积异常（${bytesWritten}B）")
                    }
                    pushFileToPlugin(appCtx, tier, mf.localName, tmpFile)
                    runCatching { tmpFile.delete() }
                    accumulated += bytesWritten
                }
                _modelDl.value = ModelDownloadState.Done(tier)
                Log.i(TAG, "模型 ${tier.id} 下载并同步至独立组件完成（${accumulated / 1024}KB）")
            } catch (t: Throwable) {
                val msg = if (cancelled.get()) "已取消" else (t.message ?: t.javaClass.simpleName)
                Log.w(TAG, "模型 ${tier.id} 下载失败：$msg", t)
                _modelDl.value = if (cancelled.get()) {
                    ModelDownloadState.Idle
                } else {
                    ModelDownloadState.Failed(tier, msg)
                }
            } finally {
                runCatching { tmpDir.deleteRecursively() }
                if (modelCancel === cancelled) modelCancel = null
            }
        }, "fp-ocr-fg-dl").start()
    }

    fun cancelModelDownload() {
        modelCancel?.set(true)
    }

    fun deleteTier(context: Context, tier: OnnxOcr.Tier) {
        val args = Bundle().apply { putString(OnnxOcr.EXTRA_TIER, tier.id) }
        runCatching { context.contentResolver.call(PROVIDER_URI, OnnxOcr.METHOD_DELETE_TIER, null, args) }
        if ((_modelDl.value as? ModelDownloadState.Done)?.tier == tier) {
            _modelDl.value = ModelDownloadState.Idle
        }
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
            _pluginDl.value = PluginDownloadState.Failed("请填写有效的组件 APK 地址")
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
            try {
                val written = downloadWithRetry(resolved, part, cancelled) { read, total ->
                    _pluginDl.value = PluginDownloadState.Downloading(read, total)
                }
                if (written < 64 * 1024L) {
                    part.delete()
                    throw IllegalStateException("安装包体积异常（${written}B）")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    throw IllegalStateException("安装包写入失败")
                }
                _pluginDl.value = PluginDownloadState.ReadyToInstall
                launchInstaller(appCtx)
            } catch (t: Throwable) {
                runCatching { part.delete() }
                val msg = if (cancelled.get()) "已取消" else (t.message ?: t.javaClass.simpleName)
                Log.w(TAG, "下载 OCR 组件失败：$msg", t)
                _pluginDl.value = if (cancelled.get()) PluginDownloadState.Idle else PluginDownloadState.Failed(msg)
            } finally {
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

    /** 拉起系统卸载确认页，卸载独立 OCR 组件并自动清理其私有模型目录。 */
    fun launchUninstaller(context: Context): Boolean = runCatching {
        releaseCache(context)
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${OnnxOcr.PLUGIN_PACKAGE}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.onFailure { Log.w(TAG, "拉起组件卸载页失败", it) }.getOrDefault(false)

    private fun pushFileToPlugin(context: Context, tier: OnnxOcr.Tier, fileName: String, file: File) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val args = Bundle().apply {
                putString(OnnxOcr.EXTRA_TIER, tier.id)
                putString(OnnxOcr.EXTRA_FILE_NAME, fileName)
                putParcelable(OnnxOcr.EXTRA_PFD, pfd)
            }
            val reply = context.contentResolver.call(PROVIDER_URI, OnnxOcr.METHOD_STORE_FILE, null, args)
            if (reply == null || !reply.getBoolean(OnnxOcr.OUT_OK, false)) {
                val err = reply?.getString(OnnxOcr.OUT_ERROR) ?: "组件未响应，请确认已安装最新版本"
                throw IOException("同步至推理组件失败：$err")
            }
        } finally {
            runCatching { pfd.close() }
        }
    }

    private fun downloadWithRetry(
        initialUrl: String,
        dest: File,
        cancelled: AtomicBoolean,
        onProgress: (Long) -> Unit,
    ): Long = downloadWithRetry(initialUrl, dest, cancelled) { read, _ -> onProgress(read) }

    private fun downloadWithRetry(
        initialUrl: String,
        dest: File,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_RETRIES) {
            if (cancelled.get()) throw InterruptedException("已取消")
            try {
                return downloadOne(initialUrl, dest, cancelled, onProgress)
            } catch (t: Throwable) {
                if (cancelled.get() || t is InterruptedException) throw t
                lastError = t
                Log.w(TAG, "第 $attempt/$MAX_RETRIES 次下载失败（${t.message}），准备重试")
                if (attempt < MAX_RETRIES) {
                    runCatching { Thread.sleep(800L * attempt) }
                }
            }
        }
        throw lastError ?: IOException("网络连接失败")
    }

    private fun downloadOne(
        initialUrl: String,
        dest: File,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var currentUrl = initialUrl
        var conn: HttpURLConnection? = null
        try {
            repeat(7) {
                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    useCaches = false
                    setRequestProperty("User-Agent", "Tsuzuri-OCR/1.0 (Android)")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Connection", "close")
                }
                val code = c.responseCode
                if (code in 301..308) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) throw IOException("重定向缺少 Location（HTTP $code）")
                    currentUrl = URL(URL(currentUrl), loc).toString()
                } else if (code in 200..299) {
                    conn = c
                    return@repeat
                } else {
                    c.disconnect()
                    throw IOException("服务器返回 HTTP $code")
                }
            }
            val active = conn ?: throw IOException("重定向次数过多")
            val contentLength = active.contentLengthLong.coerceAtLeast(0L)
            var written = 0L
            var lastAt = 0L
            active.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 shl 10)
                    while (true) {
                        if (cancelled.get()) throw InterruptedException("已取消")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = SystemClock.uptimeMillis()
                        if (now - lastAt >= 100L) {
                            lastAt = now
                            onProgress(written, contentLength)
                        }
                    }
                    out.flush()
                }
            }
            onProgress(written, contentLength)
            return written
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
