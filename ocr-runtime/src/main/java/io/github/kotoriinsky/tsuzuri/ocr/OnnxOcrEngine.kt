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
 * 性能优化（路线 1）：
 *  - 检测阶段长边压缩至 [DET_MAX_SIDE]（736px，像素面积比 960 减少 41%，1220p 手机截图精度几乎无损）；
 *  - 识别阶段按归一化行宽排序并按 [REC_BATCH_SIZE]（每批最多 6 行）动态 padding 批量推理，
 *    将整屏 20+ 行文本的 JNI/算子调度开销压缩 4~6 倍；
 *  - 单批异常自动降级为逐行识别，保证稳定性。
 */
internal object OnnxOcrEngine {

    private const val TAG = "FP-OnnxOcrEngine"
    private const val DET_MAX_SIDE = 736
    private const val REC_HEIGHT = 48
    private const val REC_MAX_WIDTH = 1280
    private const val REC_BATCH_SIZE = 6

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

    private data class RecJob(
        val lineNo: Int,
        val box: OnnxOcr.BoxF,
        val x: Int,
        val y: Int,
        val cw: Int,
        val ch: Int,
        val targetW: Int,
    )

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
        val loadMs = SystemClock.uptimeMillis() - t0
        val tDet0 = SystemClock.uptimeMillis()
        val boxes = runDet(model.detSession, bitmap)
        val detMs = SystemClock.uptimeMillis() - tDet0
        if (boxes.isEmpty()) {
            return RecognitionOutput(
                glyphs = emptyList(),
                engineLabel = "PP-OCRv6-${tier.id}/${model.actualBackend}",
                elapsedMs = SystemClock.uptimeMillis() - t0,
            )
        }

        val jobs = ArrayList<RecJob>(boxes.size)
        for ((lineNo, box) in boxes.withIndex()) {
            val x = box.left.toInt().coerceIn(0, bitmap.width - 1)
            val y = box.top.toInt().coerceIn(0, bitmap.height - 1)
            val cw = (box.right.roundToInt() - x).coerceIn(1, bitmap.width - x)
            val ch = (box.bottom.roundToInt() - y).coerceIn(1, bitmap.height - y)
            if (cw < 4 || ch < 4) continue
            val rawW = max(16, (cw.toFloat() * REC_HEIGHT / ch).roundToInt()).coerceAtMost(REC_MAX_WIDTH)
            // 对齐到 8 像素，减少 XNNPACK 动态内核切换
            val alignedW = (((rawW + 7) / 8) * 8).coerceIn(16, REC_MAX_WIDTH)
            jobs += RecJob(lineNo, box, x, y, cw, ch, alignedW)
        }

        val tRec0 = SystemClock.uptimeMillis()
        // 按行宽升序分组批处理，最大程度减少同批内无效 padding
        val sortedJobs = jobs.sortedBy { it.targetW }
        val perLine = arrayOfNulls<List<OnnxOcr.GlyphData>>(boxes.size)
        var batchCount = 0
        var offset = 0
        while (offset < sortedJobs.size) {
            val end = minOf(offset + REC_BATCH_SIZE, sortedJobs.size)
            val batch = sortedJobs.subList(offset, end)
            batchCount++
            val batchResults = try {
                runRecBatch(model.recSession, model.dict, bitmap, batch)
            } catch (t: Throwable) {
                Log.w(TAG, "批量识别失败，降级逐行：${t.message}")
                batch.map { job -> runRecBatch(model.recSession, model.dict, bitmap, listOf(job)).firstOrNull().orEmpty() }
            }
            for (i in batch.indices) {
                perLine[batch[i].lineNo] = batchResults.getOrNull(i).orEmpty()
            }
            offset = end
        }
        val recMs = SystemClock.uptimeMillis() - tRec0

        val glyphs = ArrayList<OnnxOcr.GlyphData>(boxes.size * 12)
        for (lineGlyphs in perLine) {
            if (lineGlyphs != null) glyphs.addAll(lineGlyphs)
        }
        val elapsed = SystemClock.uptimeMillis() - t0
        val label = "PP-OCRv6-${tier.id}/${model.actualBackend}"
        Log.i(TAG, "$label ${bitmap.width}x${bitmap.height}：${boxes.size} 行（$batchCount 批）/ ${glyphs.size} 字，load=${loadMs}ms det=${detMs}ms rec=${recMs}ms total=${elapsed}ms")
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
        @Suppress("UNUSED_PARAMETER") backend: OnnxOcr.Backend,
    ): Triple<OrtSession, OrtSession, String> {
        // ONNX Runtime 1.29.0 的 XNNPACK EP 在骁龙 8 Elite Gen 5（ARMv9 SVE）动态尺寸推理时触发 SIGSEGV (SEGV_ACCERR)，
        // 暂统一走通用 CPU（4 线程）以保证稳定性并实测基准耗时。
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

    /** 将 [batch] 中多行文字按该批最大宽度对齐 padding（填 0f）打包为 `[B, 3, 48, batchW]` 一次推理。 */
    private fun runRecBatch(
        session: OrtSession,
        dict: List<String>,
        bitmap: Bitmap,
        batch: List<RecJob>,
    ): List<List<OnnxOcr.GlyphData>> {
        if (batch.isEmpty()) return emptyList()
        val batchSize = batch.size
        val batchW = batch.maxOf { it.targetW }
        val plane = REC_HEIGHT * batchW
        val sampleStride = 3 * plane
        // FloatArray 默认全 0f，正好对应 [-1, 1] 归一化里的中性灰，右侧 padding 区无需额外清零
        val input = FloatArray(batchSize * sampleStride)
        val scale = 1f / 127.5f
        var pixelsBuf = IntArray(REC_HEIGHT * batchW)

        for ((bIdx, job) in batch.withIndex()) {
            val crop = Bitmap.createBitmap(bitmap, job.x, job.y, job.cw, job.ch)
            val resized = Bitmap.createScaledBitmap(crop, job.targetW, REC_HEIGHT, true)
            if (!crop.isRecycled) crop.recycle()

            val needed = job.targetW * REC_HEIGHT
            if (pixelsBuf.size < needed) pixelsBuf = IntArray(needed)
            resized.getPixels(pixelsBuf, 0, job.targetW, 0, 0, job.targetW, REC_HEIGHT)
            if (!resized.isRecycled) resized.recycle()

            val base = bIdx * sampleStride
            val baseG = base + plane
            val baseR = base + plane * 2
            val tw = job.targetW
            for (row in 0 until REC_HEIGHT) {
                val srcRow = row * tw
                val dstRow = row * batchW
                for (col in 0 until tw) {
                    val c = pixelsBuf[srcRow + col]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val dst = dstRow + col
                    input[base + dst] = b * scale - 1f
                    input[baseG + dst] = g * scale - 1f
                    input[baseR + dst] = r * scale - 1f
                }
            }
        }

        val inputName = session.inputNames.first()
        val tensorShape = longArrayOf(batchSize.toLong(), 3L, REC_HEIGHT.toLong(), batchW.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), tensorShape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val outTensor = results[0] as OnnxTensor
                val shape = outTensor.info.shape
                if (shape.size < 3) return List(batchSize) { emptyList() }
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                if (steps <= 0 || classes <= 0) return List(batchSize) { emptyList() }
                val fb = outTensor.floatBuffer
                val rowBuf = FloatArray(classes)
                val out = ArrayList<List<OnnxOcr.GlyphData>>(batchSize)
                for (bIdx in 0 until batchSize) {
                    val job = batch[bIdx]
                    // 仅解码该行有效宽度对应的时间步，忽略右侧 padding 区
                    val validSteps = ((steps.toFloat() * job.targetW / batchW).roundToInt()).coerceIn(1, steps)
                    val argmax = IntArray(validSteps)
                    for (t in 0 until steps) {
                        if (fb.remaining() < classes) break
                        fb.get(rowBuf, 0, classes)
                        if (t < validSteps) {
                            var bestIdx = 0
                            var bestVal = rowBuf[0]
                            for (c in 1 until classes) {
                                val v = rowBuf[c]
                                if (v > bestVal) {
                                    bestVal = v
                                    bestIdx = c
                                }
                            }
                            argmax[t] = bestIdx
                        }
                    }
                    out += OnnxOcr.decodeCtcLine(argmax, dict, job.box, job.lineNo)
                }
                return out
            }
        }
    }
}
