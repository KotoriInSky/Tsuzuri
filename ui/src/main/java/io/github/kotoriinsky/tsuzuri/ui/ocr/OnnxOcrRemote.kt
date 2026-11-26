package io.github.kotoriinsky.tsuzuri.ui.ocr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主模块 UI 侧对独立 OCR 组件（`io.github.kotoriinsky.tsuzuri.ocr`）的轻量客户端：
 * - 检测独立组件安装状态与版本、支持在线镜像下载安装与一键卸载；
 * - **在前台主模块进程执行 4 线程 HTTP Range 分块并发下载**（避免后台进程网络冻结触发
 *   `Software caused connection abort`，同时突破单连接限速）；
 * - 百度飞桨官方北京 BOS 直连源自动解析 `.tar` 归档抽出模型并推送至独立组件私有目录原子落盘。
 */
internal object OnnxOcrRemote {

    private const val TAG = "FP-OnnxOcrRemote"
    private const val MAX_RETRIES = 3
    private const val RANGE_THREADS = 4
    private const val RANGE_MIN_BYTES = 1L * 1024L * 1024L
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

    private data class ProbeResult(
        val finalUrl: String,
        val contentLength: Long,
        val supportsRange: Boolean,
    )

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

    /** 在前台主模块进程执行 4 线程分块下载，并同步至独立组件私有目录。 */
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
        val bceBundles = if (mirror == OnnxOcr.Mirror.BCEBOS) OnnxOcr.bcebosTarBundles(tier) else null
        val totalExpected = bceBundles?.sumOf { it.expectedBytes } ?: tier.totalBytes
        _modelDl.value = ModelDownloadState.Downloading(tier, 0L, totalExpected)

        Thread({
            val tmpDir = File(appCtx.cacheDir, "ocr_dl_${tier.id}").apply { mkdirs() }
            var accumulated = 0L
            try {
                if (bceBundles != null) {
                    // 百度飞桨官方北京 BOS 直连归档：4 线程分块下载 .tar 后流式抽文件推送组件
                    for ((idx, bundle) in bceBundles.withIndex()) {
                        if (cancelled.get()) throw InterruptedException("已取消")
                        val tarFile = File(tmpDir, "bundle_$idx.tar")
                        Log.i(TAG, "BOS 4 线程拉取 ${tier.id} 归档 ← ${bundle.url}")
                        val written = downloadSmart(bundle.url, tarFile, cancelled) { read, _ ->
                            _modelDl.value = ModelDownloadState.Downloading(tier, accumulated + read, totalExpected)
                        }
                        if (written < 1024L) throw IOException("归档体积异常（${written}B）")
                        extractAndPushTar(appCtx, tier, tarFile, bundle.targets, tmpDir)
                        runCatching { tarFile.delete() }
                        accumulated += written
                    }
                } else {
                    val files = OnnxOcr.modelFiles(tier)
                    for (mf in files) {
                        if (cancelled.get()) throw InterruptedException("已取消")
                        val tmpFile = File(tmpDir, mf.localName)
                        val url = mirror.fileUrl(mf.repo, mf.remoteFile, customMirrorUrl)
                        Log.i(TAG, "前台拉取 ${tier.id}/${mf.localName} ← $url")
                        val written = downloadSmart(url, tmpFile, cancelled) { read, _ ->
                            _modelDl.value = ModelDownloadState.Downloading(tier, accumulated + read, totalExpected)
                        }
                        if (written < 512L) throw IOException("文件体积异常（${written}B）")
                        pushFileToPlugin(appCtx, tier, mf.localName, tmpFile)
                        runCatching { tmpFile.delete() }
                        accumulated += written
                    }
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

    private fun extractAndPushTar(
        context: Context,
        tier: OnnxOcr.Tier,
        tarFile: File,
        targets: Map<String, String>,
        tmpDir: File,
    ) {
        val found = HashSet<String>()
        BufferedInputStream(FileInputStream(tarFile), 64 shl 10).use { bis ->
            OnnxOcr.parseTarEntries(bis) { entryName, _, stream ->
                val baseName = entryName.substringAfterLast('/')
                val destName = targets[baseName] ?: return@parseTarEntries
                val outFile = File(tmpDir, destName)
                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(64 shl 10)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                    }
                    fos.flush()
                }
                pushFileToPlugin(context, tier, destName, outFile)
                runCatching { outFile.delete() }
                found += destName
            }
        }
        val missing = targets.values.filter { it !in found }
        if (missing.isNotEmpty()) {
            throw IOException("归档缺少文件：${missing.joinToString()}")
        }
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

    /** 按用户配置的组件 APK 地址（可套用镜像加速前缀）4 线程下载并拉起系统安装器。 */
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
                val written = downloadSmart(resolved, part, cancelled) { read, total ->
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

    /** 探测服务端是否支持 Range；≥ 1MB 且支持时启用 4 线程分块并发，否则平滑回退单线程重试。 */
    private fun downloadSmart(
        initialUrl: String,
        dest: File,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val probe = runCatching { probeHead(initialUrl) }.getOrNull()
        if (probe != null && probe.supportsRange && probe.contentLength >= RANGE_MIN_BYTES) {
            return try {
                downloadMultiRange(probe.finalUrl, probe.contentLength, dest, cancelled, onProgress)
            } catch (t: Throwable) {
                if (cancelled.get() || t is InterruptedException) throw t
                Log.w(TAG, "分块并发下载异常，回退单线程重试：${t.message}")
                downloadWithRetry(probe.finalUrl, dest, cancelled, onProgress)
            }
        }
        return downloadWithRetry(probe?.finalUrl ?: initialUrl, dest, cancelled, onProgress)
    }

    private fun probeHead(initialUrl: String): ProbeResult {
        var currentUrl = initialUrl
        repeat(7) {
            val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
                setRequestProperty("User-Agent", "Tsuzuri-OCR/1.0 (Android)")
                setRequestProperty("Connection", "close")
            }
            try {
                val code = c.responseCode
                if (code in 301..308) {
                    val loc = c.getHeaderField("Location")
                    if (loc.isNullOrBlank()) throw IOException("HEAD 重定向缺 Location（$code）")
                    currentUrl = URL(URL(currentUrl), loc).toString()
                } else if (code in 200..299) {
                    val len = c.contentLengthLong
                    val acceptRanges = c.getHeaderField("Accept-Ranges").orEmpty()
                    return ProbeResult(currentUrl, len, acceptRanges.contains("bytes", ignoreCase = true))
                } else {
                    throw IOException("HEAD 返回 $code")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("HEAD 重定向过多")
    }

    private fun downloadMultiRange(
        finalUrl: String,
        totalBytes: Long,
        dest: File,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        RandomAccessFile(dest, "rw").use { it.setLength(totalBytes) }
        val downloaded = AtomicLong(0L)
        val lastReportAt = AtomicLong(0L)
        val chunkSize = (totalBytes + RANGE_THREADS - 1) / RANGE_THREADS
        val pool = Executors.newFixedThreadPool(RANGE_THREADS)
        val futures = ArrayList<Future<*>>(RANGE_THREADS)
        try {
            for (i in 0 until RANGE_THREADS) {
                val start = i * chunkSize
                if (start >= totalBytes) break
                val end = minOf(totalBytes - 1, start + chunkSize - 1)
                futures += pool.submit {
                    downloadRangeChunk(finalUrl, dest, start, end, totalBytes, cancelled, downloaded, lastReportAt, onProgress)
                }
            }
            for (f in futures) f.get()
            onProgress(totalBytes, totalBytes)
            return totalBytes
        } catch (t: Throwable) {
            for (f in futures) f.cancel(true)
            val cause = (t as? java.util.concurrent.ExecutionException)?.cause ?: t
            throw cause
        } finally {
            pool.shutdownNow()
        }
    }

    private fun downloadRangeChunk(
        url: String,
        dest: File,
        start: Long,
        end: Long,
        totalBytes: Long,
        cancelled: AtomicBoolean,
        downloaded: AtomicLong,
        lastReportAt: AtomicLong,
        onProgress: (Long, Long) -> Unit,
    ) {
        val expectedLen = end - start + 1
        var chunkDone = 0L
        var lastError: Throwable? = null
        for (attempt in 1..MAX_RETRIES) {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException("已取消")
            var conn: HttpURLConnection? = null
            try {
                val reqStart = start + chunkDone
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    useCaches = false
                    setRequestProperty("User-Agent", "Tsuzuri-OCR/1.0 (Android)")
                    setRequestProperty("Range", "bytes=$reqStart-$end")
                    setRequestProperty("Connection", "close")
                }
                conn = c
                val code = c.responseCode
                if (code != HttpURLConnection.HTTP_PARTIAL && !(code == HttpURLConnection.HTTP_OK && reqStart == 0L)) {
                    throw IOException("Range 返回 HTTP $code")
                }
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(reqStart)
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 shl 10)
                        while (chunkDone < expectedLen) {
                            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                                throw InterruptedException("已取消")
                            }
                            val toRead = minOf(buf.size.toLong(), expectedLen - chunkDone).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            chunkDone += n
                            val totalNow = downloaded.addAndGet(n.toLong())
                            val now = SystemClock.uptimeMillis()
                            val prev = lastReportAt.get()
                            if (now - prev >= 90L && lastReportAt.compareAndSet(prev, now)) {
                                onProgress(totalNow, totalBytes)
                            }
                        }
                    }
                }
                if (chunkDone >= expectedLen) return
                throw IOException("分块未读满（$chunkDone/$expectedLen）")
            } catch (t: Throwable) {
                if (cancelled.get() || t is InterruptedException) throw t
                lastError = t
                if (attempt < MAX_RETRIES) runCatching { Thread.sleep(600L * attempt) }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        throw lastError ?: IOException("分块下载失败")
    }

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
