/*
   本文件里的 AGSL 着色器源码**移植自第三方开源库**，不是 Flyme 的反编译产物。

   出处：Kyant0/AndroidLiquidGlass 2.0.1 —— `backdrop` 模块
        https://github.com/Kyant0/AndroidLiquidGlass
        backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt
        （`RoundedRectRefractionWithDispersionShaderString`）
        backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/Lens.kt（uniform 的取法）

   Copyright 2025 Kyant — Licensed under the Apache License, Version 2.0
   http://www.apache.org/licenses/LICENSE-2.0

   着色器逐字照抄（SDF 片段与 SideIndexGlassPanel 共用同一份）。
 */

package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.min

private val REFRACTION_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}"""

/**
 * 侧滑索引面板的**折射层**（液态玻璃档的「液态」部分，档 C）。
 *
 * 面板是 SystemUI 的独立浮窗，拿不到下层像素，所以在建窗口**之前**截一次屏
 * （[io.github.kotoriinsky.tsuzuri.hook.compat.ScreenCaptureCompat]），把截图里面板背后那一块画出来，
 * 再用 RenderEffect 链「轻模糊 → Kyant 的折射 + 色散 shader」处理：边缘把背景向内压缩、带彩色色边。
 *
 * 面板跟手上下移动，所以每帧前比对屏幕位置，变了就重画（截图按屏幕坐标对齐）。
 * 截图到达前什么都不画（面板退回 [SideIndexGlassPanel] 的高光玻璃），到了之后淡入。
 * 任何一步失败都只记日志，不影响面板。
 */
class SideIndexLiquidLayer(
    context: Context,
    private val cornerRadiusPx: Float,
    density: Float,
) : View(context) {

    private val refractionHeight = REFRACTION_HEIGHT_DP * density
    private val refractionAmount = REFRACTION_AMOUNT_DP * density
    private val blurPx = BLUR_DP * density

    private var backdrop: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val loc = IntArray(2)
    private var drawnX = Int.MIN_VALUE
    private var drawnY = Int.MIN_VALUE
    private var effectSize = ""
    private val shader: RuntimeShader? = runCatching { RuntimeShader(REFRACTION_DISPERSION_SHADER) }
        .onFailure { Log.w(TAG, "折射 shader 编译失败，液态玻璃退回高光玻璃", it) }
        .getOrNull()

    private val positionWatcher = ViewTreeObserver.OnPreDrawListener {
        if (backdrop != null) {
            getLocationOnScreen(loc)
            if (loc[0] != drawnX || loc[1] != drawnY) invalidate()
        }
        true
    }

    init {
        alpha = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(positionWatcher)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(positionWatcher)
        super.onDetachedFromWindow()
    }

    /** 截图到了：挂上并淡入。只会在主线程调。 */
    fun setBackdrop(bitmap: Bitmap) {
        if (shader == null) return
        backdrop = bitmap
        invalidate()
        animate().alpha(1f).setDuration(FADE_MS).start()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = backdrop ?: return
        val s = shader ?: return
        if (width <= 0 || height <= 0) return
        updateEffect(s)
        getLocationOnScreen(loc)
        drawnX = loc[0]
        drawnY = loc[1]
        runCatching {
            canvas.drawBitmap(bmp, -loc[0].toFloat(), -loc[1].toFloat(), paint)
        }.onFailure { Log.w(TAG, "折射层绘制失败", it) }
    }

    private fun updateEffect(s: RuntimeShader) {
        val sig = "$width/$height"
        if (sig == effectSize) return
        effectSize = sig
        val r = cornerRadiusPx.coerceIn(0f, min(width, height) / 2f)
        s.setFloatUniform("size", width.toFloat(), height.toFloat())
        s.setFloatUniform("offset", 0f, 0f)
        s.setFloatUniform("cornerRadii", r, r, r, r)
        s.setFloatUniform("refractionHeight", refractionHeight)
        // Kyant 的 lens 传的是负值（向内取样：边缘是内部被压缩的镜像）
        s.setFloatUniform("refractionAmount", -refractionAmount)
        s.setFloatUniform("depthEffect", 0f)
        s.setFloatUniform("chromaticAberration", 1f)
        val lens = RenderEffect.createRuntimeShaderEffect(s, "content")
        val effect = if (blurPx > 0f) {
            RenderEffect.createChainEffect(lens, RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP))
        } else {
            lens
        }
        setRenderEffect(effect)
        // 面板是圆角的：把折射层也裁成同样的圆角，四角外不露截图
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        Log.i(TAG, "液态玻璃折射层就绪 ${width}x$height r=$r")
    }

    internal companion object {
        private const val TAG = "FP-SideIndex"
        /** 与 Kyant 示例底栏同量级（lens 24dp/24dp），面板更大所以略加。 */
        const val REFRACTION_HEIGHT_DP = 28f
        const val REFRACTION_AMOUNT_DP = 36f
        /** 轻模糊：糊成一片就没有玻璃的透明感了（Kyant 底栏用 8dp）。 */
        const val BLUR_DP = 6f
        const val FADE_MS = 120L
    }
}
