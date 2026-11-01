package io.github.kotoriinsky.tsuzuri.hook.feature.copydirect

import android.app.Dialog
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kotoriinsky.tsuzuri.core.CopyDirect
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 沿用原生「更多设置」的行、字体、图标槽和留白，仅追加一个分享动作。 */
internal object CopyDirectMenu {
    private val added = WeakHashMap<Dialog, WeakReference<LinearLayout>>()
    fun addShare(dialog: Dialog, action: () -> Unit): Boolean {
        val context = dialog.context
        val id = context.resources.getIdentifier("btn_more_settings", "id", CopyDirect.ENGINE_PACKAGE)
        val template = dialog.findViewById<LinearLayout>(id) ?: return false
        val parent = template.parent as? ViewGroup ?: return false
        val children = (0 until template.childCount).map { template.getChildAt(it) }
        val label = children.filterIsInstance<TextView>().firstOrNull() ?: return false
        if (children.none { it is ImageView }) return false
        // 直接复用宿主布局，避免 onCreate 尚未布局时复制到未解析的水平留白。
        val layoutId = context.resources.getIdentifier("dialog_explanation", "layout", CopyDirect.ENGINE_PACKAGE)
        if (layoutId == 0) return false
        val root = LayoutInflater.from(context).inflate(layoutId, null)
        val row = root.findViewById<LinearLayout>(id) ?: return false
        (row.parent as? ViewGroup)?.removeView(row)
        row.id = View.NO_ID
        val rowChildren = (0 until row.childCount).map { row.getChildAt(it) }
        rowChildren.forEach { it.id = View.NO_ID }
        rowChildren.filterIsInstance<ImageView>().firstOrNull()?.apply {
            setImageDrawable(ShareIcon(label.currentTextColor))
        } ?: return false
        rowChildren.filterIsInstance<TextView>().firstOrNull()?.apply {
            text = "分享"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, label.textSize)
            setTextColor(label.textColors)
            typeface = label.typeface
            includeFontPadding = label.includeFontPadding
            gravity = label.gravity
        } ?: return false
        row.contentDescription = "分享"
        row.setOnClickListener { action(); dialog.dismiss() }
        parent.addView(row, parent.indexOfChild(template))
        added[dialog] = WeakReference(row)
        return true
    }

    fun restoreAll() {
        added.values.toList().forEach { reference -> reference.get()?.let { (it.parent as? ViewGroup)?.removeView(it) } }
        added.clear()
    }

    /** 自绘单色分享符号，颜色跟随原生菜单文字与深色模式。 */
    private class ShareIcon(color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            canvas.drawLine(7.8f, 10.6f, 16f, 6.3f, paint)
            canvas.drawLine(7.8f, 13.4f, 16f, 17.7f, paint)
            canvas.drawCircle(5.5f, 12f, 2.6f, paint)
            canvas.drawCircle(18.4f, 5f, 2.6f, paint)
            canvas.drawCircle(18.4f, 19f, 2.6f, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("兼容 Drawable 抽象方法") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
