package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.io.IOException

/**
 * 从第 0 帧起顺序硬解 HEIF/AVIF 图像序列，逐帧产出 ARGB 像素。
 *
 * 不能用 MediaMetadataRetriever.getFrame(s)AtIndex：抖音表情包是 1 个 I 帧 + 其余 B 帧，
 * 容器却把每帧都标成同步帧，按下标取帧会从 B 帧起解，丢参考帧 → 花屏（ffmpeg 复现：
 * 中途起解报 "Could not find ref with POC"，从头顺序解码则全程干净）。
 */
internal class PortalSequenceFrames(context: Context, uri: Uri, private val width: Int, private val height: Int) :
    Iterator<IntArray>, Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var pending: IntArray? = null
    private var bt709 = false
    private var fullRange = false

    init {
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("sequence_no_video_track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) { codec.release(); throw e }
        } catch (e: Exception) { extractor.release(); throw e }
    }

    override fun hasNext(): Boolean {
        if (pending == null && !outputDone) pending = decodeNext()
        return pending != null
    }

    override fun next(): IntArray {
        if (!hasNext()) throw NoSuchElementException()
        return requireNotNull(pending).also { pending = null }
    }

    private fun decodeNext(): IntArray? {
        var idle = 0
        while (!outputDone) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(index))
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                out >= 0 -> {
                    idle = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size <= 0) { codec.releaseOutputBuffer(out, false); continue }
                    val pixels = codec.getOutputImage(out)?.use { toArgb(it) }
                    codec.releaseOutputBuffer(out, false)
                    if (pixels != null) return pixels
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    bt709 = format.containsKey(MediaFormat.KEY_COLOR_STANDARD) &&
                        format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709
                    fullRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
                        format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
                }
                // 解码器卡住时不能无限空转；上层另有总时限。
                else -> if (++idle > 200) throw IOException("sequence_decoder_stalled")
            }
        }
        return null
    }

    /** YUV_420_888 → ARGB；按解码器报告的色彩标准与范围换算，裁剪到 cropRect 并对齐目标尺寸。 */
    private fun toArgb(image: Image): IntArray {
        val crop = image.cropRect
        val w = minOf(width, crop.width()); val h = minOf(height, crop.height())
        val (yPlane, uPlane, vPlane) = image.planes
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride; val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride
        // 系数 ×1024 定点：BT.601 / BT.709；limited 时 Y 减 16 后 ×255/219（1192），UV ×255/224（1166）。
        val rv = if (bt709) 1613 else 1436; val gu = if (bt709) 192 else 352
        val gv = if (bt709) 479 else 731; val bu = if (bt709) 1900 else 1815
        val out = IntArray(width * height)
        for (row in 0 until h) {
            val sy = crop.top + row; val cy = sy / 2
            for (col in 0 until w) {
                val sx = crop.left + col; val cx = sx / 2
                var y = yBuf.get(sy * yRow + sx * yPix).toInt() and 255
                val u = (uBuf.get(cy * uRow + cx * uPix).toInt() and 255) - 128
                val v = (vBuf.get(cy * vRow + cx * vPix).toInt() and 255) - 128
                y = if (fullRange) y shl 10 else (y - 16).coerceAtLeast(0) * 1192
                val (cu, cv) = if (fullRange) u to v else (u * 1166 shr 10) to (v * 1166 shr 10) // 255/224
                val r = ((y + rv * cv) shr 10).coerceIn(0, 255)
                val g = ((y - gu * cu - gv * cv) shr 10).coerceIn(0, 255)
                val b = ((y + bu * cu) shr 10).coerceIn(0, 255)
                out[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    private companion object { const val TIMEOUT_US = 10_000L }
}
