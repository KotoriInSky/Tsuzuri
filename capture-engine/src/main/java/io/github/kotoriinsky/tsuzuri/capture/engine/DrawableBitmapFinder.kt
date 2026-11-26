package io.github.kotoriinsky.tsuzuri.capture.engine

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * 顺着 View 的 Drawable 树找它持有的源位图。
 *
 * 与 [PortalBitmapCapture] 的画布拦截互补：拦截要读 `BitmapShader` 的位图，那是隐藏接口，
 * 在第三方应用进程里多半被拦（圆角封面常见，B 站首页封面就取不到）。这里只用两类不受限的读法：
 *  - 框架 Drawable 的公开 API（`BitmapDrawable.getBitmap`、`DrawableWrapper.getDrawable`、
 *    `LayerDrawable.getDrawable(i)`、`DrawableContainer.getCurrent`）；
 *  - 应用自己的 Drawable 类（Fresco、Glide 等）用反射读字段 —— 应用代码不在隐藏接口名单里。
 *
 * 取到的是应用解码进内存的那张图（Flyme 识屏保存的也是这一张），借用不复制。
 */
object DrawableBitmapFinder {
    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 96
    /** 比这还小的多半是占位图、图标，不当成内容图。 */
    private const val MIN_SIDE = 32

    /** [view] 本身（ImageView 的图、背景、前景）里像素最多的位图；没有返回 null。 */
    @JvmStatic
    fun largest(view: View): Bitmap? {
        val found = ArrayList<Bitmap>()
        val seen = IdentityHashMap<Any, Boolean>()
        val roots = listOfNotNull((view as? ImageView)?.drawable, view.background, view.foreground)
        for (d in roots) walk(d, 0, found, seen)
        return found.filter { !it.isRecycled && it.width >= MIN_SIDE && it.height >= MIN_SIDE }
            .maxByOrNull { it.width.toLong() * it.height }
    }

    /**
     * 判断 [view] 是否承载了真实图片/动图内容（供列表阶段快速筛选）：
     * 除静态位图外，覆盖系统 [AnimatedImageDrawable]、Glide `GifDrawable`、Fresco `AnimatedDrawable2`
     * 以及分块高清大图查看器（如 `SubsamplingScaleImageView`）。
     */
    @JvmStatic
    fun hasVisualContent(view: View): Boolean {
        if (largest(view) != null) return true
        val clsName = view.javaClass.name
        if (clsName.contains("SubsamplingScaleImageView", ignoreCase = true) ||
            clsName.contains("BigImageView", ignoreCase = true) ||
            clsName.contains("LargeImageView", ignoreCase = true)
        ) return true
        val seen = IdentityHashMap<Any, Boolean>()
        val roots = listOfNotNull((view as? ImageView)?.drawable, view.background, view.foreground)
        for (d in roots) {
            if (hasAnimatedDrawable(d, 0, seen)) return true
        }
        return false
    }

    private fun hasAnimatedDrawable(node: Drawable?, depth: Int, seen: IdentityHashMap<Any, Boolean>): Boolean {
        if (node == null || depth > MAX_DEPTH || seen.put(node, true) != null) return false
        if (node is AnimatedImageDrawable) return true
        val name = node.javaClass.simpleName
        if (name.contains("GifDrawable", ignoreCase = true) ||
            name.contains("AnimatedDrawable", ignoreCase = true) ||
            name.contains("WebpDrawable", ignoreCase = true) ||
            name.contains("AvifDrawable", ignoreCase = true)
        ) return true
        when (node) {
            is LayerDrawable -> {
                for (i in 0 until node.numberOfLayers) {
                    if (hasAnimatedDrawable(node.getDrawable(i), depth + 1, seen)) return true
                }
            }
            is DrawableWrapper -> if (hasAnimatedDrawable(node.drawable, depth + 1, seen)) return true
            is DrawableContainer -> if (hasAnimatedDrawable(node.current, depth + 1, seen)) return true
        }
        return false
    }

    private fun walk(node: Any?, depth: Int, out: MutableList<Bitmap>, seen: IdentityHashMap<Any, Boolean>) {
        if (node == null || depth > MAX_DEPTH || seen.size >= MAX_NODES || seen.put(node, true) != null) return
        when (node) {
            is Bitmap -> { out += node; return }
            is BitmapDrawable -> { node.bitmap?.let { out += it }; return }
            is LayerDrawable -> for (i in 0 until node.numberOfLayers) walk(node.getDrawable(i), depth + 1, out, seen)
            is DrawableWrapper -> walk(node.drawable, depth + 1, out, seen)
            is DrawableContainer -> walk(node.current, depth + 1, out, seen)
        }
        if (node is Drawable && !isFramework(node.javaClass)) reflectFields(node, depth, out, seen)
    }

    /** 应用自己的 Drawable：沿继承链读类型为 Bitmap / Drawable / Drawable[] 的实例字段，到框架类为止。 */
    private fun reflectFields(node: Drawable, depth: Int, out: MutableList<Bitmap>, seen: IdentityHashMap<Any, Boolean>) {
        var c: Class<*>? = node.javaClass
        while (c != null && !isFramework(c)) {
            for (f in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(f.modifiers)) continue
                val t = f.type
                if (t != Bitmap::class.java && !Drawable::class.java.isAssignableFrom(t) &&
                    !(t.isArray && Drawable::class.java.isAssignableFrom(t.componentType!!))
                ) continue
                val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                if (v is Array<*>) v.forEach { walk(it, depth + 1, out, seen) } else walk(v, depth + 1, out, seen)
            }
            c = c.superclass
        }
    }

    private fun isFramework(c: Class<*>): Boolean {
        val n = c.name
        return n.startsWith("android.") || n.startsWith("java.") || n.startsWith("com.android.")
    }
}
