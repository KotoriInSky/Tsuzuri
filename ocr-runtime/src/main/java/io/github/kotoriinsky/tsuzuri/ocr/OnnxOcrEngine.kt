package io.github.kotoriinsky.tsuzuri.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PP-OCRv6 端侧推理引擎（ONNX Runtime + XNNPACK ARMv9 NEON 加速）。
 *
 * 运行在独立 OCR 组件进程内，绝不注入系统进程，也不膨胀主模块 APK。
 * 线程安全：按 `(tier, backend)` 懒加载并缓存 `OrtSession` 与字符字典，支持随时释放。
 */
internal object OnnxOcrEngine {

    private const val TAG = "FP-OnnxOcrEngine"
    private const val DET_MAX_SIDE = 960
    private const val REC_HEIGHT = 48
    private const val REC_MAX_WIDTH = 1280

    data class RecognitionOutput(
        val glyphs: List<OnnxOcr.GlyphData>,
        val engineLabel: String,
        val elapsedMs: Long,
    )

    private class LoadedModel(
        val tier: OnnxOcr.Tier,
        val backend: OnnxOcr.Backend,
        val detSession: OrtSession,
        val recSession: OrtSession,
        val dict: List<String>,
        val actualBackend: String,
    ) {
        fun close() {
            runCatching { detSession.close() }
            runCatching { recSession.close() }
        }
    }

    private val lock = Any()
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    @Volatile
    private var cached: LoadedModel? = null

    fun release() {
        synchronized(lock) {
            cached?.close()
            cached = null
        }
    }

    fun releaseIfTier(tier: OnnxOcr.Tier) {
        synchronized(lock) {
            if (cached?.tier == tier) {
                cached?.close()
                cached = null
            }
        }
    }

    fun recognize(
        context: Context,
        bitmap: Bitmap,
        tier: OnnxOcr.Tier,
        backend: OnnxOcr.Backend,
    ): RecognitionOutput? {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return null
        val t0 = SystemClock.uptimeMillis()
        val model = ensureLoaded(context, tier, backend) ?: return null
        val boxes = runDet(model.detSession, bitmap)
        if (boxes.isEmpty()) {
            return RecognitionOutput(
                glyphs = emptyList(),
                engineLabel = "PP-OCRv6-${tier.id}/${model.actualBackend}",
                elapsedMs = SystemClock.uptimeMillis() - t0,
            )
        }
        val glyphs = ArrayList<OnnxOcr.GlyphData>(boxes.size * 12)
        for ((lineNo, box) in boxes.withIndex()) {
            val lineGlyphs = runRecLine(model.recSession, model.dict, bitmap, box, lineNo)
            glyphs.addAll(lineGlyphs)
        }
        val elapsed = SystemClock.uptimeMillis() - t0
        val label = "PP-OCRv6-${tier.id}/${model.actualBackend}"
        Log.i(TAG, "$label ${bitmap.width}x${bitmap.height}：${boxes.size} 行 / ${glyphs.size} 字，${elapsed}ms")
        return RecognitionOutput(glyphs, label, elapsed)
    }

    private fun ensureLoaded(
        context: Context,
        tier: OnnxOcr.Tier,
        backend: OnnxOcr.Backend,
    ): LoadedModel? = synchronized(lock) {
        cached?.takeIf { it.tier == tier && it.backend == backend }?.let { return it }
        if (!OnnxOcrModelStore.isInstalled(context, tier)) return null
        cached?.close()
        cached = null
        val dir = OnnxOcrModelStore.tierDir(context, tier)
        val detFile = File(dir, "det.onnx")
        val recFile = File(dir, "rec.onnx")
        val ymlFile = File(dir, "rec_inference.yml")
        val dict = OnnxOcr.parseCharacterDict(ymlFile.readText(Charsets.UTF_8))
        if (dict.isEmpty()) {
            Log.w(TAG, "字典解析为空：${ymlFile.absolutePath}")
            return null
        }
        val (det, rec, actual) = loadSessions(detFile.absolutePath, recFile.absolutePath, backend)
        LoadedModel(tier, backend, det, rec, dict, actual).also { cached = it }
    }

    private fun loadSessions(
        detPath: String,
        recPath: String,
        backend: OnnxOcr.Backend,
    ): Triple<OrtSession, OrtSession, String> {
        if (backend == OnnxOcr.Backend.AUTO) {
            try {
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(1)
                    addXnnpack(mapOf("intra_op_num_threads" to "4"))
                }
                val det = env.createSession(detPath, opts)
                val rec = try {
                    env.createSession(recPath, opts)
                } catch (t: Throwable) {
                    runCatching { det.close() }
                    throw t
                }
                opts.close()
                Log.i(TAG, "已启用 XNNPACK 加速加载模型")
                return Triple(det, rec, "XNNPACK")
            } catch (t: Throwable) {
                Log.i(TAG, "XNNPACK 不可用，自动回落通用 CPU：${t.message}")
            }
        }
        val cpuOpts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
        }
        val det = env.createSession(detPath, cpuOpts)
        val rec = try {
            env.createSession(recPath, cpuOpts)
        } catch (t: Throwable) {
            runCatching { det.close() }
            throw t
        }
        cpuOpts.close()
        return Triple(det, rec, "CPU")
    }

    private fun runDet(session: OrtSession, bitmap: Bitmap): List<OnnxOcr.BoxF> {
        val (rw, rh) = OnnxOcr.detInputSize(bitmap.width, bitmap.height, DET_MAX_SIDE)
        val scaled = if (bitmap.width == rw && bitmap.height == rh) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, rw, rh, true)
        }
        val pixels = IntArray(rw * rh)
        scaled.getPixels(pixels, 0, rw, 0, 0, rw, rh)
        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()

        val plane = rw * rh
        val input = FloatArray(3 * plane)
        val m0 = 0.485f; val m1 = 0.456f; val m2 = 0.406f
        val s0 = 1f / (255f * 0.229f); val s1 = 1f / (255f * 0.224f); val s2 = 1f / (255f * 0.225f)
        val b0 = m0 / 0.229f; val b1 = m1 / 0.224f; val b2 = m2 / 0.225f
        for (i in 0 until plane) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            input[i] = b * s0 - b0
            input[plane + i] = g * s1 - b1
            input[plane * 2 + i] = r * s2 - b2
        }

        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1L, 3L, rh.toLong(), rw.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val outTensor = results[0] as OnnxTensor
                val shape = outTensor.info.shape
                val outH = shape.getOrNull(shape.size - 2)?.toInt() ?: rh
                val outW = shape.getOrNull(shape.size - 1)?.toInt() ?: rw
                val fb = outTensor.floatBuffer
                val prob = FloatArray(outW * outH)
                fb.get(prob, 0, minOf(prob.size, fb.remaining()))
                return OnnxOcr.extractTextBoxes(prob, outW, outH, bitmap.width, bitmap.height)
            }
        }
    }

    private fun runRecLine(
        session: OrtSession,
        dict: List<String>,
        bitmap: Bitmap,
        box: OnnxOcr.BoxF,
        lineNo: Int,
    ): List<OnnxOcr.GlyphData> {
        val x = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val y = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val cw = (box.right.roundToInt() - x).coerceIn(1, bitmap.width - x)
        val ch = (box.bottom.roundToInt() - y).coerceIn(1, bitmap.height - y)
        if (cw < 4 || ch < 4) return emptyList()

        val crop = Bitmap.createBitmap(bitmap, x, y, cw, ch)
        val targetW = max(16, (cw.toFloat() * REC_HEIGHT / ch).roundToInt()).coerceAtMost(REC_MAX_WIDTH)
        val resized = Bitmap.createScaledBitmap(crop, targetW, REC_HEIGHT, true)
        if (!crop.isRecycled) crop.recycle()

        val pixels = IntArray(targetW * REC_HEIGHT)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, REC_HEIGHT)
        if (!resized.isRecycled) resized.recycle()

        val plane = targetW * REC_HEIGHT
        val input = FloatArray(3 * plane)
        val scale = 1f / 127.5f
        for (i in 0 until plane) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            input[i] = b * scale - 1f
            input[plane + i] = g * scale - 1f
            input[plane * 2 + i] = r * scale - 1f
        }

        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1L, 3L, REC_HEIGHT.toLong(), targetW.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val outTensor = results[0] as OnnxTensor
                val shape = outTensor.info.shape
                if (shape.size < 3) return emptyList()
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                if (steps <= 0 || classes <= 0) return emptyList()
                val fb = outTensor.floatBuffer
                val argmax = IntArray(steps)
                val row = FloatArray(classes)
                for (t in 0 until steps) {
                    if (fb.remaining() < classes) break
                    fb.get(row, 0, classes)
                    var bestIdx = 0
                    var bestVal = row[0]
                    for (c in 1 until classes) {
                        val v = row[c]
                        if (v > bestVal) {
                            bestVal = v
                            bestIdx = c
                        }
                    }
                    argmax[t] = bestIdx
                }
                return OnnxOcr.decodeCtcLine(argmax, dict, box, lineNo)
            }
        }
    }
}
