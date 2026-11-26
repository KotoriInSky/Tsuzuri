package io.github.kotoriinsky.tsuzuri.ocr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PP-OCRv6 ONNX 模型本地存储与加速源下载管理器（运行在独立 OCR 组件进程）。
 *
 * 模型存放于组件私有目录 `filesDir/onnx_ocr/<tier>/`，不进入主模块备份包；
 * 下载时写 `.part` 临时文件完成后原子重命名，支持魔搭 / HF Mirror / Hugging Face 与自定义加速源模板。
 */
internal object OnnxOcrModelStore {

    private const val TAG = "FP-OnnxOcrStore"
    private const val DIR_ROOT = "onnx_ocr"
    private const val MAX_REDIRECTS = 6
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    data class Snapshot(
        val state: String,
        val tierId: String = "",
        val fileLabel: String = "",
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val message: String = "",
    )

    @Volatile
    private var current: Snapshot = Snapshot(OnnxOcr.DL_IDLE)

    @Volatile
    private var cancelFlag: AtomicBoolean? = null

    fun snapshot(): Snapshot = current

    fun tierDir(context: Context, tier: OnnxOcr.Tier): File =
        File(File(context.applicationContext.filesDir, DIR_ROOT), tier.id)

    fun isInstalled(context: Context, tier: OnnxOcr.Tier): Boolean {
        val dir = tierDir(context, tier)
        return OnnxOcr.modelFiles(tier).all { mf ->
            val f = File(dir, mf.localName)
            f.isFile && f.length() >= (mf.expectedBytes / 2).coerceAtLeast(1024L)
        }
    }

    fun installedBytes(context: Context, tier: OnnxOcr.Tier): Long {
        val dir = tierDir(context, tier)
        if (!dir.isDirectory) return 0L
        return dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sumOf { it.length() }
    }

    fun deleteTier(context: Context, tier: OnnxOcr.Tier) {
        OnnxOcrEngine.releaseIfTier(tier)
        runCatching { tierDir(context, tier).deleteRecursively() }
        if (current.state == OnnxOcr.DL_DONE && current.tierId == tier.id) {
            current = Snapshot(OnnxOcr.DL_IDLE)
        }
    }

    /** 接收主模块前台下载完传入的模型文件流，写入组件私有目录并原子重命名。 */
    fun storeFileFromStream(
        context: Context,
        tier: OnnxOcr.Tier,
        fileName: String,
        input: java.io.InputStream,
    ): Long {
        val allowed = OnnxOcr.modelFiles(tier).any { it.localName == fileName }
        require(allowed) { "不允许的模型文件名：$fileName" }
        val dir = tierDir(context, tier).apply { mkdirs() }
        val part = File(dir, "$fileName.part")
        val target = File(dir, fileName)
        var written = 0L
        try {
            FileOutputStream(part).use { out ->
                val buf = ByteArray(64 shl 10)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                }
                out.flush()
            }
            if (written < 512L) {
                part.delete()
                throw IllegalStateException("文件体积异常：${written}B")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                throw IllegalStateException("原子重命名失败：$fileName")
            }
            OnnxOcrEngine.releaseIfTier(tier)
            return written
        } catch (t: Throwable) {
            runCatching { part.delete() }
            throw t
        }
    }

    fun cancelDownload() {
        cancelFlag?.set(true)
    }

    fun startDownload(
        context: Context,
        tier: OnnxOcr.Tier,
        mirror: OnnxOcr.Mirror,
        customMirrorUrl: String?,
    ): Boolean {
        if (current.state == OnnxOcr.DL_DOWNLOADING) return false
        val appCtx = context.applicationContext
        val cancelled = AtomicBoolean(false)
        cancelFlag = cancelled
        val files = OnnxOcr.modelFiles(tier)
        val totalExpected = tier.totalBytes
        current = Snapshot(
            state = OnnxOcr.DL_DOWNLOADING,
            tierId = tier.id,
            fileLabel = files.first().localName,
            downloadedBytes = 0L,
            totalBytes = totalExpected,
        )

        Thread({
            val dir = tierDir(appCtx, tier).apply { mkdirs() }
            var accumulated = 0L
            try {
                for (mf in files) {
                    if (cancelled.get()) throw InterruptedException("已取消下载")
                    val target = File(dir, mf.localName)
                    if (target.isFile && target.length() == mf.expectedBytes) {
                        accumulated += target.length()
                        current = Snapshot(
                            state = OnnxOcr.DL_DOWNLOADING,
                            tierId = tier.id,
                            fileLabel = mf.localName,
                            downloadedBytes = accumulated,
                            totalBytes = totalExpected,
                        )
                        continue
                    }
                    val part = File(dir, "${mf.localName}.part")
                    val url = mirror.fileUrl(mf.repo, mf.remoteFile, customMirrorUrl)
                    Log.i(TAG, "开始下载 ${tier.id}/${mf.localName} ← $url")
                    val bytesWritten = downloadOne(url, part, cancelled) { fileRead ->
                        current = Snapshot(
                            state = OnnxOcr.DL_DOWNLOADING,
                            tierId = tier.id,
                            fileLabel = mf.localName,
                            downloadedBytes = accumulated + fileRead,
                            totalBytes = totalExpected,
                        )
                    }
                    if (bytesWritten < 512L) {
                        part.delete()
                        throw IllegalStateException("文件 ${mf.localName} 体积异常（${bytesWritten}B）")
                    }
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.delete()
                        throw IllegalStateException("落盘重命名失败：${mf.localName}")
                    }
                    accumulated += bytesWritten
                }
                OnnxOcrEngine.releaseIfTier(tier)
                current = Snapshot(
                    state = OnnxOcr.DL_DONE,
                    tierId = tier.id,
                    downloadedBytes = accumulated,
                    totalBytes = totalExpected,
                )
                Log.i(TAG, "模型 ${tier.id} 下载完成，共 ${accumulated / 1024}KB")
            } catch (t: Throwable) {
                dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { runCatching { it.delete() } }
                val msg = if (cancelled.get()) "已取消下载" else (t.message ?: t.javaClass.simpleName)
                Log.w(TAG, "模型 ${tier.id} 下载未完成：$msg", t)
                current = if (cancelled.get()) {
                    Snapshot(OnnxOcr.DL_IDLE)
                } else {
                    Snapshot(state = OnnxOcr.DL_FAILED, tierId = tier.id, message = msg)
                }
            } finally {
                if (cancelFlag === cancelled) cancelFlag = null
            }
        }, "fp-ocr-plugin-dl").start()
        return true
    }

    private fun downloadOne(
        initialUrl: String,
        dest: File,
        cancelled: AtomicBoolean,
        onProgress: (Long) -> Unit,
    ): Long {
        var currentUrl = initialUrl
        var conn: HttpURLConnection? = null
        try {
            repeat(MAX_REDIRECTS + 1) {
                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "Tsuzuri-OCR/1.0 (Android)")
                    setRequestProperty("Accept", "*/*")
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
            var written = 0L
            var lastReportAt = 0L
            active.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 shl 10)
                    while (true) {
                        if (cancelled.get()) throw InterruptedException("已取消下载")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        val now = SystemClock.uptimeMillis()
                        if (now - lastReportAt >= 120L) {
                            lastReportAt = now
                            onProgress(written)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(written)
            return written
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
