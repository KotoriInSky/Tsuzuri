package io.github.kotoriinsky.tsuzuri.core.geometry

/**
 * 清单里第 N 项落在扇子的**哪一页、哪一圈**（排序时让用户知道「第 5 项」在扇子上的位置）。
 *
 * 规则与真机逐条对应：
 *  - 分页：hook 侧 `FanTargets` 按「每页数量」`chunked(perPage)`；
 *  - 每页单独排圈：[FanArcs.plan]（智能 / 自定义两种模式），第 N 项先填内圈再往外（`FanSolution.place`
 *    按 `rows[0]` 起逐层累加）。
 *
 * 清单不够数时扇子会用「最近使用」补位（不在清单里），这里只算清单本身的位置。
 */
object FanSlots {

    /** 一项的位置。[ring] 0 = 内圈；[rings] = 这一页共几圈。 */
    class Slot(val page: Int, val ring: Int, val rings: Int, val pages: Int) {
        /** 这一项是不是一段（新的一页，或同页里新的一圈）的第一项。只有一页一圈时全是 false（不用标）。 */
        var startsSection: Boolean = false
            internal set
    }

    fun of(total: Int, perPage: Int, layoutMode: String, inner: Int, middle: Int): List<Slot> {
        if (total <= 0) return emptyList()
        val per = perPage.coerceAtLeast(1)
        val pages = (total + per - 1) / per
        val out = ArrayList<Slot>(total)
        for (page in 0 until pages) {
            val count = minOf(per, total - page * per)
            val rows = FanArcs.plan(count, layoutMode, inner, middle).rows.filter { it.count > 0 }
            for ((ring, row) in rows.withIndex()) {
                repeat(row.count) { i ->
                    out.add(Slot(page, ring, rows.size, pages).also { it.startsSection = i == 0 && (pages > 1 || rows.size > 1) })
                }
            }
        }
        return out
    }
}
