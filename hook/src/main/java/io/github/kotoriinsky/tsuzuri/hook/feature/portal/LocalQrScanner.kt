package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import io.github.kotoriinsky.tsuzuri.core.QrJumpResolver
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纯本地二维码解码与跳转工具（基于 Apache-2.0 的 ZXing Core，零网络依赖）。
 *
 * 供「全局识别（整屏扫描）」与「传送门图片识别卡（单图扫描）」共用：
 *  - 自动按长边上限缩图控制耗时（一屏约 20~60ms）；
 *  - 先用 [QRCodeMultiReader] 扫多码，再退回 [QRCodeReader] 与反色亮度源，兼顾暗色/反白码；
 *  - 识别结果交给 [QrJumpResolver] 匹配 118 平台规则（含追踪参数清理）与安全 URI 白名单。
 */
internal object LocalQrScanner {

    private const val TAG = "FP-LocalQr"
    private const val MAX_DECODE_EDGE = 1280

    data class Detection(
        val rawText: String,
        val action: QrJumpResolver.Action,
        /** 二维码在输入位图坐标系中的外接矩形；无法定位时为 null。 */
        val bounds: RectF?,
    )

    private val HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /** 扫描位图中的所有二维码；异常或无码一律返回空列表，绝不向宿主抛异常。 */
    fun scanAll(bitmap: Bitmap): List<Detection> {
        if (bitmap.isRecycled || bitmap.width < 24 || bitmap.height < 24) return emptyList()
        return try {
            val origW = bitmap.width
            val origH = bitmap.height
            val longest = max(origW, origH)
            val scale = if (longest > MAX_DECODE_EDGE) MAX_DECODE_EDGE.toFloat() / longest else 1f
            val work = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (origW * scale).roundToInt().coerceAtLeast(24),
                    (origH * scale).roundToInt().coerceAtLeast(24),
                    true,
                )
            } else {
                bitmap
            }
            try {
                val w = work.width
                val h = work.height
                val pixels = IntArray(w * h)
                work.getPixels(pixels, 0, w, 0, 0, w, h)
                val source = RGBLuminanceSource(w, h, pixels)
                val rawResults = decodeResults(source)
                if (rawResults.isEmpty()) return emptyList()
                val invScale = 1f / scale
                val seen = LinkedHashSet<String>()
                buildList {
                    for (r in rawResults) {
                        val txt = r.text?.trim() ?: continue
                        if (txt.isEmpty() || !seen.add(txt)) continue
                        val action = QrJumpResolver.resolve(txt) ?: continue
                        val rect = resultBounds(r, invScale, origW.toFloat(), origH.toFloat())
                        add(Detection(rawText = txt, action = action, bounds = rect))
                    }
                }
            } finally {
                if (work !== bitmap && !work.isRecycled) work.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "本地二维码扫描失败：$t")
            emptyList()
        }
    }

    /** 扫描单张图片（返回第一个有效结果）。 */
    fun scanFirst(bitmap: Bitmap): Detection? = scanAll(bitmap).firstOrNull()

    private fun decodeResults(source: LuminanceSource): List<Result> {
        val multi = QRCodeMultiReader()
        runCatching { multi.decodeMultiple(BinaryBitmap(HybridBinarizer(source)), HINTS) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it.toList() }

        val single = QRCodeReader()
        runCatching { single.decode(BinaryBitmap(HybridBinarizer(source)), HINTS) }
            .getOrNull()?.let { return listOf(it) }

        // 二次尝试：全局直方图二值化（处理大面积纯色背景下低对比度二维码）
        runCatching { single.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), HINTS) }
            .getOrNull()?.let { return listOf(it) }

        // 三次尝试：反色亮度源（暗黑主题/反白二维码）
        val inverted = source.invert()
        runCatching { single.decode(BinaryBitmap(HybridBinarizer(inverted)), HINTS) }
            .getOrNull()?.let { return listOf(it) }

        return emptyList()
    }

    private fun resultBounds(result: Result, invScale: Float, maxW: Float, maxH: Float): RectF? {
        val pts = result.resultPoints ?: return null
        if (pts.size < 2) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (p in pts) {
            if (p == null) continue
            minX = min(minX, p.x * invScale)
            minY = min(minY, p.y * invScale)
            maxX = max(maxX, p.x * invScale)
            maxY = max(maxY, p.y * invScale)
        }
        if (minX >= maxX || minY >= maxY) return null
        // ZXing 返回的是三个定位角中心点，外扩约 18% 覆盖完整二维码模块与静区
        val pad = max(maxX - minX, maxY - minY) * 0.18f
        return RectF(
            (minX - pad).coerceIn(0f, maxW),
            (minY - pad).coerceIn(0f, maxH),
            (maxX + pad).coerceIn(0f, maxW),
            (maxY + pad).coerceIn(0f, maxH),
        )
    }

    /** 依次尝试主目标与备选链接跳转（有已知平台包名时优先直达应用）；全部解析失败返回 false。 */
    fun launch(context: Context, action: QrJumpResolver.Action): Boolean {
        val candidates = buildList {
            action.primaryTarget?.takeIf { it.isNotBlank() }?.let { add(it) }
            for (alt in action.alts) {
                if (alt.isNotBlank() && alt !in this) add(alt)
            }
        }
        val pkgCandidates = buildList {
            action.platformPkg?.takeIf { it.isNotBlank() }?.let { pkg ->
                add(pkg)
                // B站同时存在国内版与国际/Play 版，主包不可用时顺带尝试 Play 版
                if (pkg == "tv.danmaku.bili") add("com.bilibili.app.in")
            }
        }
        val pm = context.packageManager
        for (target in candidates) {
            val uri = Uri.parse(target)
            for (pkg in pkgCandidates) {
                val pkgIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val canHandle = runCatching {
                    pm.queryIntentActivities(pkgIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                        .any { it.activityInfo?.packageName == pkg }
                }.getOrDefault(false)
                if (canHandle && runCatching { context.startActivity(pkgIntent); true }.getOrDefault(false)) {
                    Log.i(TAG, "二维码直达应用成功：${action.title} ($pkg) -> $target")
                    return true
                }
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "二维码跳转成功：${action.title} -> $target")
                return true
            }
        }
        Log.w(TAG, "二维码跳转失败（无可用应用）：${action.title} targets=$candidates")
        return false
    }

    /** 写入系统剪贴板。 */
    fun copy(context: Context, text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("二维码内容", text))
        true
    }.getOrDefault(false)

    /** 解析品牌强调色，无效时回退到系统蓝。 */
    fun accentColor(accent: String?, fallback: Int = 0xFF3482FF.toInt()): Int {
        if (accent.isNullOrBlank()) return fallback
        return runCatching { Color.parseColor(accent) }.getOrDefault(fallback)
    }
}
