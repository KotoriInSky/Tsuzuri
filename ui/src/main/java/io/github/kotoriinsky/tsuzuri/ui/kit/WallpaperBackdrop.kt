package io.github.kotoriinsky.tsuzuri.ui.kit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader

/** 用 Android GPU 模糊壁纸快照；内容层另绘，文字与图标保持清晰。 */
internal class WallpaperBackdrop {
    private val node = RenderNode("TsuzuriPreviewWallpaper")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var signature = ""
    private var source: Bitmap? = null

    fun draw(canvas: Canvas, bitmap: Bitmap, left: Float, top: Float, width: Int, height: Int, blurPx: Float) {
        if (width <= 0 || height <= 0) return
        val key = "$width/$height/$blurPx"
        if (source !== bitmap || signature != key) {
            source = bitmap
            signature = key
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
            })
            paint.shader = shader
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(if (blurPx > 0f) RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP) else null)
            val recording = node.beginRecording(width, height)
            recording.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            node.endRecording()
        }
        val saved = canvas.save()
        canvas.translate(left, top)
        if (canvas.isHardwareAccelerated) canvas.drawRenderNode(node)
        else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.restoreToCount(saved)
    }
}
