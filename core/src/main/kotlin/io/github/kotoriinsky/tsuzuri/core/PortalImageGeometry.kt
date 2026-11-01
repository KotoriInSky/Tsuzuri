package io.github.kotoriinsky.tsuzuri.core

/** 图片在截图中的可见边界；完整文件尺寸不参与此处几何。 */
object PortalImageGeometry {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    fun visible(rect: Bounds, width: Int, height: Int): Bounds? {
        if (width <= 0 || height <= 0 || rect.right <= rect.left || rect.bottom <= rect.top) return null
        val clipped = Bounds(maxOf(0, rect.left), maxOf(0, rect.top), minOf(width, rect.right), minOf(height, rect.bottom))
        return clipped.takeIf { it.right > it.left && it.bottom > it.top }
    }
}
