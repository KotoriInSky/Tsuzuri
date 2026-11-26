package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnnxOcrTest {

    @Test
    fun 解析Yaml字典含单引号转义与日文假名() {
        val yaml = """
            Global:
              model_name: PP-OCRv6_small_rec
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '!'
              - ''''
              - A
              - あ
              - カ
              - 中
              - "hello\nworld"
            OtherSection:
              key: value
        """.trimIndent()
        val dict = OnnxOcr.parseCharacterDict(yaml)
        assertEquals(listOf("!", "'", "A", "あ", "カ", "中", "hello\nworld"), dict)
    }

    @Test
    fun ctc去重解码与空格映射并生成逐字框() {
        val dict = listOf("H", "i", "あ")
        // 0=blank, 1=H, 2=i, 4=space(dict.size+1), 3=あ
        val ids = intArrayOf(0, 1, 1, 0, 2, 4, 3, 3, 0)
        val glyphs = OnnxOcr.decodeCtcLine(ids, dict, OnnxOcr.BoxF(100f, 20f, 280f, 60f), lineNo = 2)
        assertEquals("Hi あ", glyphs.joinToString("") { it.text })
        assertTrue(glyphs.all { it.line == 2 })
        // 坐标单调递增且落在行框内
        for (i in 1 until glyphs.size) {
            assertTrue(glyphs[i].box.left >= glyphs[i - 1].box.left)
        }
        assertTrue(glyphs.first().box.left >= 100f)
        assertTrue(glyphs.last().box.right <= 280f)
    }

    @Test
    fun dbnet概率图提取文本行并按阅读顺序排序() {
        val w = 64
        val h = 64
        val prob = FloatArray(w * h)
        // 右上角一行
        for (y in 8..14) for (x in 36..54) prob[y * w + x] = 0.85f
        // 左上角同行
        for (y in 9..15) for (x in 6..24) prob[y * w + x] = 0.82f
        // 下面第二行
        for (y in 34..42) for (x in 10..48) prob[y * w + x] = 0.90f
        // 低置信度噪点（应被过滤）
        for (y in 52..56) for (x in 52..56) prob[y * w + x] = 0.25f

        val boxes = OnnxOcr.extractTextBoxes(prob, w, h, origW = 640, origH = 640)
        assertEquals(3, boxes.size)
        // 第一块应是左上角，第二块右上角，第三块下排
        assertTrue(boxes[0].left < boxes[1].left)
        assertTrue(boxes[2].top > boxes[0].bottom)
    }

    @Test
    fun 镜像源下载地址构造正确() {
        val urlMs = OnnxOcr.Mirror.MODELSCOPE.fileUrl("PaddlePaddle/PP-OCRv6_small_det_onnx", "inference.onnx")
        assertEquals(
            "https://modelscope.cn/api/v1/models/PaddlePaddle/PP-OCRv6_small_det_onnx/repo?Revision=master&FilePath=inference.onnx",
            urlMs,
        )
        val urlHfm = OnnxOcr.Mirror.HF_MIRROR.fileUrl("PaddlePaddle/PP-OCRv6_small_det_onnx", "inference.onnx")
        assertEquals(
            "https://hf-mirror.com/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
            urlHfm,
        )
        val urlHf = OnnxOcr.Mirror.HUGGINGFACE.fileUrl("PaddlePaddle/PP-OCRv6_small_det_onnx", "inference.onnx")
        assertEquals(
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
            urlHf,
        )
        assertEquals(
            "https://my.mirror.example/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx",
            OnnxOcr.Mirror.CUSTOM.fileUrl("PaddlePaddle/PP-OCRv6_small_det_onnx", "inference.onnx", "https://my.mirror.example/"),
        )
        assertEquals(
            "https://cdn.example/dl?r=PaddlePaddle/PP-OCRv6_small_det_onnx&f=inference.onnx",
            OnnxOcr.Mirror.CUSTOM.fileUrl("PaddlePaddle/PP-OCRv6_small_det_onnx", "inference.onnx", "https://cdn.example/dl?r={repo}&f={file}"),
        )
        assertEquals(
            urlMs,
            OnnxOcr.Mirror.CUSTOM.fileUrl("PaddlePaddle/PP-OCRv6_small_det_onnx", "inference.onnx", "invalid"),
        )
        assertEquals(
            "https://gh-proxy.com/https://github.com/a/b/releases/download/v1/ocr.apk",
            OnnxOcr.PluginMirror.GHPROXY.wrap("https://github.com/a/b/releases/download/v1/ocr.apk"),
        )
    }

    @Test
    fun 检测输入尺寸对齐32倍数() {
        val (w, h) = OnnxOcr.detInputSize(1220, 2656, maxSide = 960)
        assertEquals(0, w % 32)
        assertEquals(0, h % 32)
        assertTrue(w <= 960 && h <= 960)
    }
}
