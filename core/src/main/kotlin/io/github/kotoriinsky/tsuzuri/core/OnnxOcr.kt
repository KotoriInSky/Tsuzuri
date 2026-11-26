package io.github.kotoriinsky.tsuzuri.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「多语种端侧 OCR（PP-OCRv6 ONNX）」特性契约、下载源配置与纯 JVM 前后处理算法。
 *
 * 架构边界：
 *  - ONNX Runtime（含 XNNPACK 加速）拆成独立可选组件 APK（[PLUGIN_PACKAGE]），主模块零 `.so` 负担；
 *  - 经受控 [AUTHORITY] 对传送门提供识别服务、对主模块 UI 提供模型下载/管理接口，绝不注入系统进程；
 *  - 模型文件（`det.onnx`、`rec.onnx`、`rec_inference.yml`）按需下载到组件 `filesDir/onnx_ocr/<tier>/`，
 *    不进入备份包，不膨胀主 APK；
 *  - 总开关关闭、未安装组件或模型未就绪时自动回落系统自带太乙 OCR。
 */
object OnnxOcr {

    const val PREF_TIER = "tier"
    const val PREF_MIRROR = "mirror"
    const val PREF_CUSTOM_MIRROR = "custom_mirror"
    const val PREF_PLUGIN_URL = "plugin_url"
    const val PREF_BACKEND = "backend"
    const val PREF_MODELS = "models"

    /** 独立可选 OCR 推理组件包名与受控 Provider Authority。 */
    const val PLUGIN_PACKAGE = "io.github.kotoriinsky.tsuzuri.ocr"
    const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.ocr.provider"

    const val METHOD_RECOGNIZE = "recognize"
    const val METHOD_STATUS = "status"
    const val METHOD_QUERY_STATE = "query_state"
    const val METHOD_START_DOWNLOAD = "start_download"
    const val METHOD_CANCEL_DOWNLOAD = "cancel_download"
    const val METHOD_DELETE_TIER = "delete_tier"
    const val METHOD_RELEASE = "release"

    const val EXTRA_PFD = "pfd"
    const val EXTRA_TIER = "tier"
    const val EXTRA_MIRROR = "mirror"
    const val EXTRA_CUSTOM_MIRROR = "custom_mirror"
    const val EXTRA_BACKEND = "backend"

    const val OUT_OK = "ok"
    const val OUT_ENGINE = "engine"
    const val OUT_ELAPSED_MS = "elapsed_ms"
    const val OUT_ERROR = "error"
    const val OUT_TEXTS = "texts"
    const val OUT_LINES = "lines"
    const val OUT_BOXES = "boxes"
    const val OUT_READY = "ready"
    const val OUT_VERSION = "version"
    const val OUT_INSTALLED_TIERS = "installed_tiers"
    const val OUT_TIER_BYTES = "tier_bytes"
    const val OUT_DL_STATE = "dl_state"
    const val OUT_DL_TIER = "dl_tier"
    const val OUT_DL_FILE = "dl_file"
    const val OUT_DL_BYTES = "dl_bytes"
    const val OUT_DL_TOTAL = "dl_total"
    const val OUT_DL_MESSAGE = "dl_message"

    const val DL_IDLE = "idle"
    const val DL_DOWNLOADING = "downloading"
    const val DL_DONE = "done"
    const val DL_FAILED = "failed"

    /** PP-OCRv6 三个官方分档（实测自 ModelScope / Hugging Face 仓库精确大小）。 */
    enum class Tier(
        val id: String,
        val detRepo: String,
        val recRepo: String,
        val detBytes: Long,
        val recBytes: Long,
        val ymlBytes: Long,
        val langSummary: String,
    ) {
        TINY(
            id = "tiny",
            detRepo = "PaddlePaddle/PP-OCRv6_tiny_det_onnx",
            recRepo = "PaddlePaddle/PP-OCRv6_tiny_rec_onnx",
            detBytes = 1_780_590L,
            recBytes = 4_462_639L,
            ymlBytes = 55_571L,
            langSummary = "极速轻量 · 中英为主（约 6 MB）",
        ),
        SMALL(
            id = "small",
            detRepo = "PaddlePaddle/PP-OCRv6_small_det_onnx",
            recRepo = "PaddlePaddle/PP-OCRv6_small_rec_onnx",
            detBytes = 9_880_512L,
            recBytes = 21_159_378L,
            ymlBytes = 150_579L,
            langSummary = "推荐均衡 · 50 语种含中英日欧（约 30 MB）",
        ),
        MEDIUM(
            id = "medium",
            detRepo = "PaddlePaddle/PP-OCRv6_medium_det_onnx",
            recRepo = "PaddlePaddle/PP-OCRv6_medium_rec_onnx",
            detBytes = 62_032_837L,
            recBytes = 76_554_979L,
            ymlBytes = 150_580L,
            langSummary = "高精度档 · 50 语种复杂版面（约 132 MB）",
        );

        val totalBytes: Long get() = detBytes + recBytes + ymlBytes

        companion object {
            fun of(raw: String?): Tier = entries.firstOrNull { it.id == raw } ?: SMALL
        }
    }

    /** 模型下载源：国内加速源（魔搭 ModelScope / HF Mirror）+ Hugging Face 官方源 + 自定义加速源。 */
    enum class Mirror(val id: String) {
        MODELSCOPE("modelscope"),
        HF_MIRROR("hf_mirror"),
        HUGGINGFACE("huggingface"),
        CUSTOM("custom");

        fun fileUrl(repo: String, filePath: String, customTemplate: String? = null): String = when (this) {
            MODELSCOPE -> "https://modelscope.cn/api/v1/models/$repo/repo?Revision=master&FilePath=$filePath"
            HF_MIRROR -> "https://hf-mirror.com/$repo/resolve/main/$filePath"
            HUGGINGFACE -> "https://huggingface.co/$repo/resolve/main/$filePath"
            CUSTOM -> resolveCustomMirrorUrl(customTemplate, repo, filePath)
                ?: MODELSCOPE.fileUrl(repo, filePath)
        }

        companion object {
            fun of(raw: String?): Mirror = entries.firstOrNull { it.id == raw } ?: MODELSCOPE
        }
    }

    /**
     * 解析自定义模型镜像模板：
     * - 支持占位符模板（如 `https://mirror.example/api/{repo}?file={file}`）；
     * - 也支持直接填反代基地址（如 `https://my-hf.example.com`），自动拼接 `/{repo}/resolve/main/{file}`；
     * - 非 http/https 或空白返回 null，调用方回退默认魔搭源。
     */
    fun resolveCustomMirrorUrl(rawTemplate: String?, repo: String, filePath: String): String? {
        val trimmed = rawTemplate?.trim().orEmpty()
        if (!trimmed.startsWith("https://", ignoreCase = true) && !trimmed.startsWith("http://", ignoreCase = true)) {
            return null
        }
        return if (trimmed.contains("{repo}") || trimmed.contains("{file}")) {
            trimmed.replace("{repo}", repo).replace("{file}", filePath)
        } else {
            val base = trimmed.trimEnd('/')
            "$base/$repo/resolve/main/$filePath"
        }
    }

    /**
     * 组件 APK 在线下载地址解析（支持 GitHub 加速前缀镜像）：
     * - 若用户填写了自定义 URL，直接使用（校验 http/https）；
     * - 否则按所选加速前缀组合默认发布包路径。
     */
    enum class PluginMirror(val id: String, val prefix: String) {
        GHPROXY("ghproxy", "https://gh-proxy.com/"),
        GHFAST("ghfast", "https://ghfast.top/"),
        GITHUB("github", "");

        fun wrap(rawUrl: String): String {
            val u = rawUrl.trim()
            if (u.isEmpty() || prefix.isEmpty()) return u
            if (!u.startsWith("https://github.com/", ignoreCase = true)) return u
            return "$prefix$u"
        }

        companion object {
            fun of(raw: String?): PluginMirror = entries.firstOrNull { it.id == raw } ?: GHPROXY
        }
    }

    /** 推理后端：默认优先 XNNPACK（ARMv9 NEON 加速，失败自动回落通用 CPU），也可强制通用多核 CPU。 */
    enum class Backend(val id: String) {
        AUTO("auto"),
        CPU("cpu");

        companion object {
            fun of(raw: String?): Backend = entries.firstOrNull { it.id == raw } ?: AUTO
        }
    }

    data class ModelFile(
        val localName: String,
        val repo: String,
        val remoteFile: String,
        val expectedBytes: Long,
    )

    fun modelFiles(tier: Tier): List<ModelFile> = listOf(
        ModelFile("det.onnx", tier.detRepo, "inference.onnx", tier.detBytes),
        ModelFile("rec.onnx", tier.recRepo, "inference.onnx", tier.recBytes),
        ModelFile("rec_inference.yml", tier.recRepo, "inference.yml", tier.ymlBytes),
    )

    data class BoxF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    data class GlyphData(val text: String, val box: BoxF, val line: Int)

    /**
     * 解析 PP-OCRv6 `inference.yml` 中的 `character_dict:` 字符列表。
     * 精确处理 YAML 单引号转义（`''''` → `'`）、双引号与裸字符条目。
     */
    fun parseCharacterDict(yaml: String): List<String> {
        val out = ArrayList<String>(19_000)
        var inDict = false
        var baseIndent = -1
        for (rawLine in yaml.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            if (!inDict) {
                if (trimmed.startsWith("character_dict:")) {
                    inDict = true
                    baseIndent = indent
                }
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (indent < baseIndent || (!trimmed.startsWith("-") && indent <= baseIndent)) {
                break
            }
            if (!trimmed.startsWith("-")) continue
            val valueRaw = trimmed.substring(1).let { if (it.startsWith(" ")) it.substring(1) else it }
            out += unescapeYamlScalar(valueRaw)
        }
        return out
    }

    private fun unescapeYamlScalar(raw: String): String {
        if (raw.length >= 2 && raw.startsWith('\'') && raw.endsWith('\'')) {
            return raw.substring(1, raw.length - 1).replace("''", "'")
        }
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            val inner = raw.substring(1, raw.length - 1)
            val sb = StringBuilder(inner.length)
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) {
                    when (val next = inner[i + 1]) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        else -> sb.append(next)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
        return raw
    }

    /**
     * 标准 PaddleOCR CTC 解码：
     * - 索引 0 为 CTC blank；
     * - `1..dict.size` 映射到 `dict[id - 1]`；
     * - `dict.size + 1` 映射为空格 `" "`（PP-OCR `use_space_char=True` 约定）；
     * - 按每个字符首次出现到最后连续时间步的区间映射出精确的逐字框，供蓝手柄拖拽与放大镜对齐。
     */
    fun decodeCtcLine(
        argmaxIds: IntArray,
        dict: List<String>,
        lineBox: BoxF,
        lineNo: Int,
    ): List<GlyphData> {
        val steps = argmaxIds.size
        if (steps == 0 || dict.isEmpty() || lineBox.width <= 0f || lineBox.height <= 0f) return emptyList()
        val out = ArrayList<GlyphData>(steps / 2)
        var prev = 0
        var i = 0
        while (i < steps) {
            val id = argmaxIds[i]
            if (id != 0 && id != prev) {
                val startStep = i
                var endStep = i + 1
                while (endStep < steps && argmaxIds[endStep] == id) endStep++
                val ch = when {
                    id in 1..dict.size -> dict[id - 1]
                    id == dict.size + 1 -> " "
                    else -> null
                }
                if (ch != null) {
                    val x0 = lineBox.left + lineBox.width * (startStep.toFloat() / steps)
                    val x1 = lineBox.left + lineBox.width * (endStep.toFloat() / steps)
                    val minW = (lineBox.width / steps.toFloat()).coerceAtLeast(1f)
                    val r = max(x1, x0 + minW).coerceAtMost(lineBox.right)
                    out += GlyphData(ch, BoxF(x0.coerceAtLeast(lineBox.left), lineBox.top, r, lineBox.bottom), lineNo)
                }
                prev = id
                i = endStep
                continue
            }
            prev = id
            i++
        }
        return out
    }

    /**
     * 纯 Kotlin DBNet 概率图后处理（零 OpenCV 依赖）：
     * 阈值二值化 → 4 邻接连通域 → 按框内平均概率过滤低置信度噪点 → 按 [unclipRatio] 适度外扩恢复字符边缘
     * → 映射回原图坐标并按自然阅读顺序（自上而下、同行自左向右）排序。
     */
    fun extractTextBoxes(
        probMap: FloatArray,
        mapW: Int,
        mapH: Int,
        origW: Int,
        origH: Int,
        thresh: Float = 0.2f,
        boxThresh: Float = 0.45f,
        unclipRatio: Float = 1.4f,
        maxCandidates: Int = 500,
    ): List<BoxF> {
        if (mapW <= 0 || mapH <= 0 || probMap.size < mapW * mapH || origW <= 0 || origH <= 0) return emptyList()
        val total = mapW * mapH
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val rawBoxes = ArrayList<BoxF>()
        val scaleX = origW.toFloat() / mapW
        val scaleY = origH.toFloat() / mapH

        for (start in 0 until total) {
            if (visited[start] || probMap[start] <= thresh) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var minX = mapW
            var minY = mapH
            var maxX = -1
            var maxY = -1
            var probSum = 0f
            var count = 0

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % mapW
                val y = idx / mapW
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                probSum += probMap[idx]
                count++

                if (x > 0) {
                    val n = idx - 1
                    if (!visited[n] && probMap[n] > thresh) { visited[n] = true; queue[tail++] = n }
                }
                if (x + 1 < mapW) {
                    val n = idx + 1
                    if (!visited[n] && probMap[n] > thresh) { visited[n] = true; queue[tail++] = n }
                }
                if (y > 0) {
                    val n = idx - mapW
                    if (!visited[n] && probMap[n] > thresh) { visited[n] = true; queue[tail++] = n }
                }
                if (y + 1 < mapH) {
                    val n = idx + mapW
                    if (!visited[n] && probMap[n] > thresh) { visited[n] = true; queue[tail++] = n }
                }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < 3 || bh < 3 || count < 6) continue
            val meanScore = probSum / count
            if (meanScore < boxThresh) continue

            // DBNet 经典膨胀：外扩距离 d = area * unclipRatio / perimeter，略微收紧系数适配轴对齐外包框
            val area = bw.toFloat() * bh
            val perimeter = 2f * (bw + bh)
            val expand = (area * (unclipRatio - 1f) / perimeter).coerceIn(1f, min(bw, bh) * 0.65f)
            val l = ((minX - expand * 1.15f) * scaleX).coerceIn(0f, origW.toFloat())
            val t = ((minY - expand * 0.9f) * scaleY).coerceIn(0f, origH.toFloat())
            val r = ((maxX + 1 + expand * 1.15f) * scaleX).coerceIn(l + 1f, origW.toFloat())
            val b = ((maxY + 1 + expand * 0.9f) * scaleY).coerceIn(t + 1f, origH.toFloat())
            if (r - l >= 6f && b - t >= 6f) {
                rawBoxes += BoxF(l, t, r, b)
                if (rawBoxes.size >= maxCandidates) break
            }
        }

        return sortReadingOrder(rawBoxes)
    }

    /** 按视觉行从上到下、同行内从左到右排序（同一行判定：纵中心差 < 较矮框高度的一半）。 */
    fun sortReadingOrder(boxes: List<BoxF>): List<BoxF> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedBy { it.top }.toMutableList()
        for (i in 0 until sorted.size - 1) {
            var j = i
            while (j >= 0) {
                val a = sorted[j]
                val b = sorted[j + 1]
                val minH = min(a.height, b.height)
                val sameRow = kotlin.math.abs((a.top + a.bottom) * 0.5f - (b.top + b.bottom) * 0.5f) < minH * 0.55f
                if (sameRow && b.left < a.left) {
                    sorted[j] = b
                    sorted[j + 1] = a
                    j--
                } else {
                    break
                }
            }
        }
        return sorted
    }

    /** 计算检测输入尺寸（长边不超 [maxSide]，且宽高均对齐到 32 的倍数）。 */
    fun detInputSize(origW: Int, origH: Int, maxSide: Int = 960): Pair<Int, Int> {
        val longest = max(origW, origH).coerceAtLeast(1)
        val ratio = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val rw = ((origW * ratio / 32f).roundToInt() * 32).coerceAtLeast(32)
        val rh = ((origH * ratio / 32f).roundToInt() * 32).coerceAtLeast(32)
        return rw to rh
    }
}
