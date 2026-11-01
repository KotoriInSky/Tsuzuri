package io.github.kotoriinsky.tsuzuri.capture.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 厂商 getBitmap 与 AOSP mBitmap 都非公开 API；不可访问时明确返回无候选。 */
object PortalBitmapShaderCompat {
    private val getter: Method? by lazy {
        runCatching { BitmapShader::class.java.getDeclaredMethod("getBitmap").apply { isAccessible = true } }
            .getOrNull()
    }
    private val field: Field? by lazy {
        runCatching { BitmapShader::class.java.getDeclaredField("mBitmap").apply { isAccessible = true } }
            .getOrNull()
    }

    fun bitmap(shader: Shader?): Bitmap? {
        if (shader !is BitmapShader) return null
        val value = runCatching { getter?.invoke(shader) as? Bitmap }.getOrNull()
            ?: runCatching { field?.get(shader) as? Bitmap }.getOrNull()
        return value?.takeUnless { it.isRecycled }
    }
}
