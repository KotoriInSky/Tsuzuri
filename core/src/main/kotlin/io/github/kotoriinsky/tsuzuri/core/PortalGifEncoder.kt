package io.github.kotoriinsky.tsuzuri.core

import java.io.OutputStream

/**
 * 把逐帧 ARGB 像素编码为循环播放的 GIF89a，供动图 HEIF/AVIF 保存与分享时兼容图库和聊天应用。
 * 每帧独立 256 色调色板（中值切分），alpha < 128 视为透明；不做抖动，表情包这类低色彩动图足够。
 */
object PortalGifEncoder {
    fun encode(width: Int, height: Int, frames: List<IntArray>, delaysMs: List<Int>, out: OutputStream) {
        require(frames.isNotEmpty() && frames.size == delaysMs.size) { "帧与时长数量不一致" }
        encode(width, height, frames.size, frames::get, delaysMs::get, out)
    }

    /** 逐帧回调取像素，整段动图不同时驻留内存。 */
    fun encode(width: Int, height: Int, count: Int, frameAt: (Int) -> IntArray, delayAt: (Int) -> Int, out: OutputStream) {
        require(width in 1..65535 && height in 1..65535) { "尺寸越界" }
        require(count > 0) { "没有帧" }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(out, width); short(out, height)
        out.write(0x00); out.write(0); out.write(0) // 无全局调色板，每帧自带。
        // NETSCAPE2.0：无限循环。
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B) + "NETSCAPE2.0".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(3, 1, 0, 0, 0))
        for (index in 0 until count) {
            val pixels = frameAt(index)
            require(pixels.size == width * height) { "帧像素数与尺寸不符" }
            val (palette, indices, transparent) = quantize(pixels)
            // 图形控制扩展：处置方式 2（恢复背景），时长单位 1/100 秒，浏览器对 <2 会按 10 处理。
            val delay = ((delayAt(index) + 5) / 10).coerceIn(2, 65535)
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write((2 shl 2) or (if (transparent >= 0) 1 else 0))
            short(out, delay); out.write(if (transparent >= 0) transparent else 0); out.write(0)
            out.write(0x2C); short(out, 0); short(out, 0); short(out, width); short(out, height)
            out.write(0x80 or 7) // 局部调色板，256 项。
            for (i in 0 until 256) {
                val c = palette.getOrElse(i) { 0 }
                out.write(c shr 16 and 255); out.write(c shr 8 and 255); out.write(c and 255)
            }
            lzw(indices, 8, out)
        }
        out.write(0x3B)
    }

    private fun short(out: OutputStream, value: Int) { out.write(value and 255); out.write(value shr 8 and 255) }

    private class Box(val colors: IntArray, val from: Int, val to: Int) {
        fun span(shift: Int): Int {
            var lo = 255; var hi = 0
            for (i in from until to) { val v = colors[i] shr shift and 255; if (v < lo) lo = v; if (v > hi) hi = v }
            return hi - lo
        }
    }

    /** 返回 (调色板, 每像素索引, 透明索引或 -1)。 */
    internal fun quantize(pixels: IntArray): Triple<IntArray, ByteArray, Int> {
        val hasAlpha = pixels.any { it ushr 24 < 128 }
        val opaque = pixels.filter { it ushr 24 >= 128 }.map { it and 0xFFFFFF }.distinct().toIntArray()
        val budget = if (hasAlpha) 255 else 256
        val palette: IntArray = if (opaque.size <= budget) opaque else {
            val boxes = mutableListOf(Box(opaque, 0, opaque.size))
            while (boxes.size < budget) {
                val target = boxes.filter { it.to - it.from > 1 }.maxByOrNull { b -> maxOf(b.span(16), b.span(8), b.span(0)) }
                    ?: break
                val shift = listOf(16, 8, 0).maxBy { target.span(it) }
                val sorted = target.colors.copyOfRange(target.from, target.to).sortedBy { it shr shift and 255 }
                sorted.forEachIndexed { i, c -> target.colors[target.from + i] = c }
                val mid = (target.from + target.to) / 2
                boxes.remove(target)
                boxes += Box(target.colors, target.from, mid); boxes += Box(target.colors, mid, target.to)
            }
            boxes.map { b ->
                var r = 0L; var g = 0L; var bl = 0L
                for (i in b.from until b.to) { val c = b.colors[i]; r += c shr 16 and 255; g += c shr 8 and 255; bl += c and 255 }
                val n = b.to - b.from
                ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
            }.toIntArray()
        }
        val transparent = if (hasAlpha) palette.size else -1
        val cache = HashMap<Int, Int>()
        val indices = ByteArray(pixels.size) { i ->
            val p = pixels[i]
            if (p ushr 24 < 128) transparent.toByte() else cache.getOrPut(p and 0xFFFFFF) { nearest(palette, p) }.toByte()
        }
        val full = if (hasAlpha) palette + 0 else palette
        return Triple(full, indices, transparent)
    }

    private fun nearest(palette: IntArray, color: Int): Int {
        val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
        var best = 0; var bestDistance = Int.MAX_VALUE
        for ((i, c) in palette.withIndex()) {
            val dr = (c shr 16 and 255) - r; val dg = (c shr 8 and 255) - g; val db = (c and 255) - b
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestDistance) { bestDistance = d; best = i; if (d == 0) break }
        }
        return best
    }

    /** 标准 GIF 变长 LZW，码表满 4096 时发清除码。 */
    private fun lzw(indices: ByteArray, minCodeSize: Int, out: OutputStream) {
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize; val end = clear + 1
        val block = ByteArray(255); var blockLength = 0
        var bitBuffer = 0; var bitCount = 0
        fun flushBlock() { if (blockLength > 0) { out.write(blockLength); out.write(block, 0, blockLength); blockLength = 0 } }
        fun emit(code: Int, size: Int) {
            bitBuffer = bitBuffer or (code shl bitCount); bitCount += size
            while (bitCount >= 8) {
                block[blockLength++] = (bitBuffer and 255).toByte(); bitBuffer = bitBuffer ushr 8; bitCount -= 8
                if (blockLength == 255) flushBlock()
            }
        }
        val table = HashMap<Int, Int>()
        var codeSize = minCodeSize + 1; var next = end + 1
        emit(clear, codeSize)
        var prefix = indices[0].toInt() and 255
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 255
            val key = (prefix shl 8) or k
            val found = table[key]
            if (found != null) { prefix = found; continue }
            emit(prefix, codeSize)
            if (next < 4096) {
                table[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear, codeSize); table.clear(); codeSize = minCodeSize + 1; next = end + 1
            }
            prefix = k
        }
        emit(prefix, codeSize)
        emit(end, codeSize)
        if (bitCount > 0) { block[blockLength++] = (bitBuffer and 255).toByte(); if (blockLength == 255) flushBlock() }
        flushBlock()
        out.write(0)
    }
}
