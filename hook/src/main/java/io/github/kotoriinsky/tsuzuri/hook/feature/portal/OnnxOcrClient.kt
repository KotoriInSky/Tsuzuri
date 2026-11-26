package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry

/**
 * 传送门侧的端侧多语种 OCR 客户端：经 [OnnxOcr.AUTHORITY] 把图片交给 Tsuzuri 自身进程执行
 * PP-OCRv6（ONNX Runtime / QNN NPU），拿回精确逐字框；总开关关着、模型未下载或异常一律返回 null，
 * 调用方自动回落系统太乙 OCR。
 */
internal object OnnxOcrClient {

    private const val TAG = "FP-OnnxOcr"
    private val URI = Uri.parse("content://${OnnxOcr.AUTHORITY}")

    fun isEnabled(): Boolean = HookRegistry.prefs().isEnabled(Catalog.ONNX_OCR)

    /** 后台线程调用。成功返回逐字列表，未启用/未装模型/失败返回 null。 */
    fun recognize(
        context: Context,
        bitmap: Bitmap,
        log: ((Int, String) -> Unit)? = PortalOcrSelect.log,
    ): List<PortalOcrSelect.Glyph>? {
        if (!isEnabled() || bitmap.isRecycled) return null
        val prefs = HookRegistry.prefs()
        val tier = OnnxOcr.Tier.of(prefs.rawString(Keys.pref(Catalog.ONNX_OCR, OnnxOcr.PREF_TIER), OnnxOcr.Tier.SMALL.id))
        val backend = OnnxOcr.Backend.of(prefs.rawString(Keys.pref(Catalog.ONNX_OCR, OnnxOcr.PREF_BACKEND), OnnxOcr.Backend.AUTO.id))
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val writer = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
                    os.flush()
                }
            } catch (_: Throwable) {
                runCatching { writeEnd.close() }
            }
        }, "fp-onnx-ocr-pipe")
        writer.start()

        return try {
            val args = Bundle().apply {
                putParcelable(OnnxOcr.EXTRA_PFD, readEnd)
                putString(OnnxOcr.EXTRA_TIER, tier.id)
                putString(OnnxOcr.EXTRA_BACKEND, backend.id)
            }
            val reply = context.contentResolver.call(URI, OnnxOcr.METHOD_RECOGNIZE, null, args)
            if (reply == null || !reply.getBoolean(OnnxOcr.OUT_OK, false)) {
                val err = reply?.getString(OnnxOcr.OUT_ERROR) ?: "no_reply"
                log?.invoke(Log.INFO, "[$TAG] 未走 PP-OCRv6（$err），回落系统太乙 OCR")
                return null
            }
            val texts = reply.getStringArrayList(OnnxOcr.OUT_TEXTS) ?: return null
            val lines = reply.getIntArray(OnnxOcr.OUT_LINES) ?: return null
            val boxes = reply.getFloatArray(OnnxOcr.OUT_BOXES) ?: return null
            val count = minOf(texts.size, lines.size, boxes.size / 4)
            val engine = reply.getString(OnnxOcr.OUT_ENGINE) ?: "PP-OCRv6"
            val elapsed = reply.getLong(OnnxOcr.OUT_ELAPSED_MS, -1L)
            log?.invoke(Log.INFO, "[$TAG] $engine 识别 $count 字，耗时 ${elapsed}ms")
            List(count) { i ->
                PortalOcrSelect.Glyph(
                    text = texts[i],
                    box = RectF(boxes[i * 4], boxes[i * 4 + 1], boxes[i * 4 + 2], boxes[i * 4 + 3]),
                    line = lines[i],
                )
            }
        } catch (t: Throwable) {
            log?.invoke(Log.WARN, "[$TAG] 调用 PP-OCRv6 服务失败，回落系统太乙 OCR：$t")
            null
        } finally {
            runCatching { readEnd.close() }
        }
    }
}
