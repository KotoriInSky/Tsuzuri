package io.github.kotoriinsky.tsuzuri.capture

import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.ImageView
import io.github.kotoriinsky.tsuzuri.capture.engine.PortalBitmapCapture

/**
 * 本进程所有窗口里显示着的 View，按绘制先后排好，并能算出每个控件**没被后画的东西盖住**的面积。
 *
 * `isShown` + `getGlobalVisibleRect` 只看自己与祖先，不管上面压着什么：酷安点开大图是一个半透明的
 * `PhotoViewV16Activity` 叠在首页 `MainActivity` 上，两个窗口都「显示着」，列图把首页的头像、轮播、
 * 缩略图全列了出来，画在大图上（2026-10-30 真机）。这里把两类东西当遮挡：
 *  - 后画、自身完全不透明（含祖先 alpha）且背景 Drawable 报 OPAQUE 的控件，扣掉它盖住的部分；
 *  - 后加的 Activity 主窗口（`TYPE_BASE_APPLICATION`），以及带 `FLAG_DIM_BEHIND` 的窗口（弹窗、底部面板）：
 *    它之前的窗口整个算被盖住。酷安大图页的黑底带透明度，不算不透明控件，只扣控件时首页照样穿透；
 *    用户定：上面开了新页面就直接屏蔽下面，压暗的内容也不让选（2026-10-30）。
 *
 * 绘制顺序按窗口添加顺序 + 子控件下标先序近似（不管 `setZ` / 自定义绘制顺序）。
 */
internal class ShownViews private constructor(val nodes: List<Node>, private val occluders: List<Occluder>) {

    /**
     * [rect] 是屏幕坐标的可见矩形；[index] 是先序号，[end] 是子树最后一个节点的先序号。
     * [content] 是图片实际画出来的范围（ImageView 按 imageMatrix 换算，与 [rect] 取交集），其余控件等于 [rect]。
     */
    class Node(val view: View, val rect: Rect, val content: Rect, val depth: Int, val index: Int) {
        var end = index
            internal set
    }

    /** 先序号大于某个节点子树末尾的遮挡，才画在它上面。 */
    class Occluder(val index: Int, val rect: Rect)

    /** [n] 的可见矩形里，没被它子树之后画的遮挡盖住的面积。 */
    fun exposedArea(n: Node): Long {
        val area = n.rect.width().toLong() * n.rect.height()
        var region: Region? = null
        for (o in occluders) {
            if (o.index <= n.end || !Rect.intersects(o.rect, n.rect)) continue
            if (o.rect.contains(n.rect)) return 0
            val r = region ?: Region(n.rect).also { region = it }
            r.op(o.rect, Region.Op.DIFFERENCE)
        }
        val r = region ?: return area
        var sum = 0L
        val it = RegionIterator(r)
        val part = Rect()
        while (it.next(part)) sum += part.width().toLong() * part.height()
        return sum
    }

    /** 可见矩形至少有 [ratio] 没被盖住。 */
    fun mostlyExposed(n: Node, ratio: Float): Boolean =
        exposedArea(n) >= n.rect.width().toLong() * n.rect.height() * ratio

    companion object {
        /** 遍历一遍；[deadline]（uptimeMillis）到了就停，[truncated] 为 true。 */
        fun collect(deadline: Long = Long.MAX_VALUE): Pair<ShownViews, Boolean> {
            val nodes = ArrayList<Node>()
            val occluders = ArrayList<Occluder>()
            val origin = IntArray(2)
            var truncated = false
            fun visit(v: View, depth: Int, alpha: Float) {
                if (truncated) return
                if (android.os.SystemClock.uptimeMillis() > deadline) { truncated = true; return }
                val visible = Rect()
                if (!v.isShown || !v.getGlobalVisibleRect(visible)) return
                // getGlobalVisibleRect 相对所在窗口；加上窗口在屏幕上的位置
                visible.offset(origin[0], origin[1])
                val node = Node(v, visible, contentRect(v, visible), depth, nodes.size)
                nodes += node
                val a = alpha * v.alpha * v.transitionAlpha
                if (a >= 0.99f && v.background?.opacity == PixelFormat.OPAQUE) occluders += Occluder(node.index, visible)
                if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i), depth + 1, a)
                node.end = nodes.size - 1
            }
            for (root in WindowInspector.getGlobalWindowViews()) {
                if (!root.isAttachedToWindow || !root.isShown) continue
                val lp = root.layoutParams as? WindowManager.LayoutParams
                val page = lp?.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                val dims = lp != null && lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 && lp.dimAmount > 0f
                if ((page || dims) && nodes.isNotEmpty()) {
                    occluders += Occluder(nodes.size, Rect(Int.MIN_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2))
                }
                root.getLocationOnScreen(origin)
                visit(root, 0, 1f)
            }
            return ShownViews(nodes, occluders) to truncated
        }

        /**
         * ImageView 里图片实际画出来的屏幕范围：大图查看器的 ImageView 铺满全屏，图只占中间一块
         * （或长图放大后上下都超出屏幕），按控件框会把黑边也算进去。拿不到就用 [visible]。
         *
         * 占屏四分之一以上的先在记录画布上画一遍量实际位置：B 站大图页的 `PinchImageView`（Fresco 系）
         * 报不出图片尺寸、也不用 imageMatrix，按控件框定出的是带上下黑边的 1220x2406（2026-10-30 真机）。
         */
        private fun contentRect(v: View, visible: Rect): Rect {
            if (v !is ImageView) return visible
            val dm = v.resources.displayMetrics
            if (visible.width().toLong() * visible.height() * 4 >= dm.widthPixels.toLong() * dm.heightPixels) {
                drawnRect(v, visible)?.let { return it }
            }
            if (v.scaleType == ImageView.ScaleType.FIT_XY) return visible
            val d = v.drawable ?: return visible
            val w = d.intrinsicWidth
            val h = d.intrinsicHeight
            if (w <= 0 || h <= 0) return visible
            val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
            v.imageMatrix.mapRect(r)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            r.offset((loc[0] + v.paddingLeft).toFloat(), (loc[1] + v.paddingTop).toFloat())
            val out = Rect()
            r.roundOut(out)
            return if (out.intersect(visible)) out else visible
        }

        /** 记录画布里 [v] 画过的面积最大的那张位图，换成屏幕坐标并与 [visible] 取交集；画不出来返回 null。 */
        private fun drawnRect(v: View, visible: Rect): Rect? {
            val drawn = PortalBitmapCapture.drawn(v) ?: return null
            val best = drawn.samples.maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() } ?: return null
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val out = Rect()
            best.visibleBounds.roundOut(out)
            out.offset(loc[0], loc[1])
            return if (out.intersect(visible)) out else null
        }
    }
}
