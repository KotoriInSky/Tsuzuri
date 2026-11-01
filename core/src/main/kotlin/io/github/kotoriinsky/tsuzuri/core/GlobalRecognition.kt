package io.github.kotoriinsky.tsuzuri.core

/**
 * 「全局识别」的契约与图片区域算法。
 *
 * 链路：界面侧磁贴 → SystemUI（[ACTION_TILE]，按发送方 uid 只受理本模块）→ 收起控制中心、等 [COLLAPSE_WAIT_MS]
 * → 传送门（[ACTION_CAPTURE]，接收方要求 `STATUS_BAR_SERVICE`）→ 截整屏、端侧 OCR、开窗。
 */
object GlobalRecognition {

    const val ACTION_TILE = "io.github.kotoriinsky.tsuzuri.action.GLOBAL_RECOGNITION"
    const val ACTION_CAPTURE = "io.github.kotoriinsky.tsuzuri.GLOBAL_RECOGNITION_CAPTURE"

    /**
     * [ACTION_CAPTURE] 附带的前台应用包名（SystemUI 在收起面板后查主屏可见任务填入）。
     * 传送门拿到它就能立刻让该应用列图片，与 OCR 并行，不必先等系统控件树。
     */
    const val EXTRA_PACKAGE = "package"

    /** 收起控制中心到截图之间的等待：收起动画约 300ms，再留一两帧。 */
    const val COLLAPSE_WAIT_MS = 450L

    /** 像素矩形（左上闭、右下开）。 */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
        fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    }

    /**
     * 在整屏截图里找「不是文字的高细节矩形」——照片、缩略图、头像。
     *
     * 没有端侧检测接口可用，所以按格子统计：亮度极差大或颜色多的格子算「有细节」，
     * 4 邻接连成块，取外包矩形。文字框覆盖的格子是中性的：不算细节、也不算空白（填充率里扣掉），
     * 这样图上压的字只在图里留个洞，成段文字也不会被当成图。
     *
     * @param pixels 缩小后的 ARGB 像素（行优先），[w]×[h]
     * @param cell 格子边长（缩小后的像素）
     * @param text 文字框，坐标已换算到缩小后的像素
     * @param minSide 结果最小边长（缩小后的像素）
     * @return 缩小后坐标里的矩形，按面积从大到小
     */
    fun findImageRegions(
        pixels: IntArray,
        w: Int,
        h: Int,
        cell: Int,
        text: List<Box>,
        minSide: Int,
    ): List<Box> {
        require(pixels.size >= w * h && cell > 0)
        val cw = w / cell
        val ch = h / cell
        if (cw == 0 || ch == 0) return emptyList()
        // 0 空白，1 细节，2 文字（中性）
        val kind = IntArray(cw * ch)
        val colors = HashSet<Int>()
        for (cy in 0 until ch) for (cx in 0 until cw) {
            val cellBox = Box(cx * cell, cy * cell, (cx + 1) * cell, (cy + 1) * cell)
            if (text.any { it.intersects(cellBox) }) { kind[cy * cw + cx] = 2; continue }
            var lo = 255
            var hi = 0
            colors.clear()
            for (y in cellBox.top until cellBox.bottom) {
                val row = y * w
                for (x in cellBox.left until cellBox.right) {
                    val p = pixels[row + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val l = (r * 3 + g * 6 + b) / 10
                    if (l < lo) lo = l
                    if (l > hi) hi = l
                    colors.add(((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4))
                }
            }
            if (hi - lo >= DETAIL_RANGE || colors.size >= DETAIL_COLORS) kind[cy * cw + cx] = 1
        }

        val seen = BooleanArray(cw * ch)
        val out = ArrayList<Box>()
        val stack = ArrayDeque<Int>()
        for (start in kind.indices) {
            if (kind[start] != 1 || seen[start]) continue
            var minX = cw; var minY = ch; var maxX = -1; var maxY = -1
            var busy = 0
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val x = i % cw
                val y = i / cw
                if (kind[i] == 1) busy++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                fun visit(nx: Int, ny: Int) {
                    if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) return
                    val j = ny * cw + nx
                    // 不穿过文字格：否则图下方的说明文字会被并进图里。图上压的字留成「洞」，外包矩形照样盖得住
                    if (!seen[j] && kind[j] == 1) { seen[j] = true; stack.addLast(j) }
                }
                visit(x - 1, y); visit(x + 1, y); visit(x, y - 1); visit(x, y + 1)
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            var textCells = 0
            for (y in minY..maxY) for (x in minX..maxX) if (kind[y * cw + x] == 2) textCells++
            val total = bw * bh
            val neutral = total - textCells
            if (bw * cell < minSide || bh * cell < minSide) continue
            if (neutral <= 0 || busy.toFloat() / neutral < MIN_FILL) continue
            if (textCells.toFloat() / total > MAX_TEXT_SHARE) continue
            out += Box(minX * cell, minY * cell, (maxX + 1) * cell, (maxY + 1) * cell)
        }
        return out.sortedByDescending { it.width.toLong() * it.height }
    }

    /** 格内亮度极差达到这个值算有细节（0..255）。 */
    private const val DETAIL_RANGE = 48

    /** 格内 12 位量化颜色达到这个数算有细节（渐变、照片）。 */
    private const val DETAIL_COLORS = 12

    /** 外包矩形里（扣除文字格）细节格至少占多少。 */
    private const val MIN_FILL = 0.6f

    /** 外包矩形里文字格最多占多少；超过多半是带底色的文字块。 */
    private const val MAX_TEXT_SHARE = 0.35f
}
