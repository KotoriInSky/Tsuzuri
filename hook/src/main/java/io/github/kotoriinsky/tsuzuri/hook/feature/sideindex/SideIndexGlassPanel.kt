/*
   ---------------------------------------------------------------------------
   本文件里的 AGSL 着色器源码**移植自第三方开源库**，不是 Flyme 的反编译产物。

   出处：Kyant0/AndroidLiquidGlass —— `backdrop` 模块
        https://github.com/Kyant0/AndroidLiquidGlass
        backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt
   用到的常量：`RoundedRectSDF`（原文件 :22-48）、`DefaultHighlightShaderString`
   （:161-181）、`AmbientHighlightShaderString`（:183-204）。

   Copyright 2025 Kyant

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
   ---------------------------------------------------------------------------

   移植时**唯一一处改动**：原版 `DefaultHighlightShaderString` 里是
   `layout(color) uniform half4 color;`，这里换成 `uniform float4 color;`。
   理由：`layout(color)` 会走框架的颜色空间转换（还会做 alpha 预乘），
   而我们要的是「按自己的值精确控制强度与透明度」；换成 float4 之后
   预乘与否由本文件显式决定（见 [SideIndexGlassPanel.pushUniforms]），
   少一层不受控的语义。除此之外逐字照抄（含缩进）。
 */

package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.util.Log
import android.view.View
import kotlin.math.min

/**
 * 共享的**圆角矩形有向距离场**（SDF）与它的梯度。三段 shader 都拼这一份
 * （原版也是用字符串插值拼的，`RoundedRectSDF` 常量）。
 *
 * `sdRoundedRect` 给「点是否在圆角矩形内」，`gradSdRoundedRect` 给「该点处指向形状外的
 * 单位方向」—— 后者是 highlight 的全部依据：把方向和光照方向点乘，就得到「这一点的
 * 外法线有多朝向光」。
 */
internal const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

/**
 * **默认高光**（原版 `DefaultHighlightShaderString`，逐字 —— 除 color uniform 的类型，见文件头）。
 *
 * ## 为什么它不需要下层像素（这就是本档「免截屏」的全部依据）
 *
 * 它**没有 `uniform shader content`**，输入只有四个量：
 * `size`（矩形尺寸）、`cornerRadii`（四角半径）、`angle`（光照方向）、`falloff`（聚拢程度），
 * 再加一个 `color`。输出 = `color × pow(|cos(外法线, 光照方向)|, falloff)`：
 *
 *  - 朝光那一侧的边缘 `|cos| → 1` → 亮（**描边高光**）；
 *  - 背光那一侧 `→ 0` → 暗（把 color 换成深色就是**内阴影**）；
 *  - 矩形内部 `|cos|` 是个中间值（45° 光照下约 0.7），`falloff` 越大被压得越低 ——
 *    所以同一个 shader 既能做「整体斜向色边」，也能做「只亮边缘的勾边」。
 *
 * 于是「玻璃观感」= **已有的跨窗口模糊**（下层像素由 SurfaceFlinger 模糊，见
 * `SideIndexOverlay.applyBlurStateToWindow`）+ 这一层自绘的方向性高光。
 * 全程不碰截屏、不碰 `ScreenCapture`、不碰下层像素。
 *
 * ## 与本档无关、但容易被搞混的一条（档 C 的现状，2026-09-14 主代理真机取证）
 *
 * 档 C（真折射）在真机上**已经验通「可行」**：A17 把 API 换成了
 * `ScreenCapture.capture(ScreenCaptureParams, Executor, OutcomeReceiver)`
 * （`DisplayCaptureArgs` / `ScreenshotHardwareBuffer` 在这台机器上不存在），
 * 往返 **29ms**、`Bitmap.wrapHardwareBuffer` 约 0ms、**`BitmapShader` 能直接从
 * hardware bitmap 建**（不需要 CPU 拷贝）。所以本档**不是**「反正 C 做不了才凑合的方案」；
 * 它是「观感的大部分、代价几个 AGSL 常量」。
 *
 * ⚠️ **不能把这段话误读成「我们已经在采样下层像素」**：本文件一行截屏代码都没有。
 * 档 C 将来要自己解决「截到的图里含我们自己的浮层」这个自反馈问题 ——
 * A17 的 `ScreenCaptureParams$Builder` **没有 `setExcludeLayers`**（只有
 * `setCaptureMode / setPixelFormat / setIncludeSystemOverlays / setPreserveDisplayColors /
 * setProtectedContentPolicy / setSecureContentPolicy / setUseDisplayInstallationOrientation`），
 * 那是 C 的作业，与 B 无交集。
 */
private const val HIGHLIGHT_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float4 color;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""

/**
 * **环境高光**（原版 `AmbientHighlightShaderString`，逐字）—— **一并移植但本轮不使用**。
 *
 * 它和 [HIGHLIGHT_SHADER] 的算式一样，区别只在最后一行：
 * `return half4(t, t, t, 1.0) * intensity`（`t = step(0.0, d)`）—— 输出是一整块
 * **不透明的方向性灰白遮罩**，用法是当 alpha 遮罩去合成（原版就是把它交给
 * `HighlightElement` 当遮罩层）。我们这条路不需要遮罩管线：用 [HIGHLIGHT_SHADER]
 * 换三次 `color/angle/falloff` 就能表达高光 / 内阴影 / 色边，为它多挂一条合成管线
 * 在 SystemUI 里不划算。
 *
 * 之所以还是留在这里：**「移植了哪些、用了哪些」必须可核对** ——
 * 只留用得到的那个，下一轮的人无法判断另一个是「不需要」还是「漏了」。
 * 它是一个 `const String`，不用就不会被编译进任何调用点，没有运行期代价。
 */
@Suppress("unused")
private const val AMBIENT_HIGHLIGHT_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    float t = step(0.0, d);
    return half4(t, t, t, 1.0) * intensity;
}"""

/**
 * 侧滑索引面板的**玻璃材质层**（需求 #7 档 B：「免截屏的玻璃观感」）。
 *
 * ## 它画在哪、画什么
 *
 * 它是**面板容器最底下的一层**（[SideIndexOverlay.ensurePanel] 里第一个 addView 的），
 * 尺寸与面板完全一致（`MATCH_PARENT`），所以看起来是「应用网格垫在一块玻璃板上」：
 *
 * ```
 * 已有：屏幕级跨窗口模糊（下层像素被 SurfaceFlinger 模糊）+ 渐变遮罩
 * 本层：① 极淡的底色（玻璃的「体」）
 *       ② 三次 highlight pass（同一份 shader，不同 color/angle/falloff）：
 *          描边高光 / 内阴影 / 偏冷的色边
 * 其上：ScrollView 里的应用图标与名字（原有）
 * ```
 *
 * ⚠️ **刻意不做整屏玻璃**（也可以做，`cornerRadii` 换成屏幕圆角即可）：面板是本特性里
 * 唯一有明确圆角矩形几何的东西（高光 shader 就是为圆角矩形写的），而整屏玻璃的高光会
 * 落在屏幕物理边框上，与机身已有一圈边框叠在一起，看起来更像画歪了。这个取舍写在这里，
 * 下一轮若改成整屏，改的是这一个 View 的布局参数，不是着色器。
 *
 * ## 性能（这一档唯一需要盯的地方）
 *
 * 开销来源有且只有三处，逐条说明为什么可接受：
 *
 *  1. **shader 编译**：AGSL 是首次绘制时在 RenderThread 上编译的。本类在构造时就把
 *     三个 `RuntimeShader` 建好（**每个 pass 一个实例**），所以编译发生在
 *     「面板第一次出现」那一帧，之后跨帧复用 —— 不在 `onDraw` 里 new，也不在
 *     `onDraw` 里编译。
 *  2. **uniform 写入**：只在**尺寸变化时**写一次（`onSizeChanged` 立 dirty 标记），
 *     不是每帧。面板尺寸在一次手势里不变，所以稳定状态下每帧只做 4 次
 *     `drawRoundRect`（1 次底色 + 3 次 shader），没有分配、没有 JNI 之外的调用。
 *  3. **blend 面积**：就是面板那块（默认 1070×1053px）在 RenderThread 上的 4 次
 *     矩形混合。设备是 120Hz，**卡不卡必须真机看**（本轮没装机）—— 判据是
 *     手势期间 `dumpsys gfxinfo com.android.systemui` 的掉帧数，不是肉眼。
 *
 * ## 关态与失败路径（绝不逃逸）
 *
 *  - 默认档是 `blur`（[SideIndexLook.DEFAULT_MATERIAL]）：那一档**根本不建这个 View**
 *    （[create] 不会被调用），所以关掉之后与改动前逐位一致；
 *  - shader 构造失败（GPU 能力不足 / AGSL 在该机型上不可用）→ [create] 返回 null，
 *    面板照旧（只少一层高光）；
 *  - 画布不是硬件加速 → [onDraw] 只画底色，不碰 shader（RuntimeShader 需要硬件加速）；
 *  - 这一层整体是**观感增强**，它出任何问题都不该让浮层画不出来，所以所有平台调用
 *    都包在 `runCatching` 里，失败只记一条日志。
 */
class SideIndexGlassPanel(
    context: Context,
    /** 面板圆角（px）。面板在真机上没有背景，这个圆角是本档新增的观感值。 */
    private val cornerRadiusPx: Float,
) : View(context) {

    /**
     * 一次 highlight pass：颜色（ARGB，**未预乘**）、光照角度（度）、聚拢指数。
     *
     * 不是 `private`：它出现在 [Companion.PASSES] 的类型里（Kotlin 不允许 internal
     * 属性暴露 private 类型）。外层类是 internal，所以这里实际可见性仍是 internal。
     */
    class Pass(val r: Float, val g: Float, val b: Float, val a: Float, val angleDeg: Float, val falloff: Float)

    /** 底色：玻璃的「体」。很淡 —— 层次主要由模糊与高光提供，不靠不透明度盖住下层。 */
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GLASS_TINT
        style = Paint.Style.FILL
    }

    /** 画 shader 用的画笔。shader 每次画之前换，所以这里只有一个实例。 */
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * 三次 pass 的着色器。**构造时建好、跨帧复用**（见类注释的性能第 1、2 条）。
     * 下标与 [PASSES] 对应；某一项为 null = 那一个编译失败了（只少一层，不影响其它）。
     */
    private val shaders: Array<RuntimeShader?> = Array(PASSES.size) { i ->
        runCatching { RuntimeShader(HIGHLIGHT_SHADER) }.getOrElse { t ->
            // 编译失败：降级成「只有底色」，并让这件事**看得见**（否则只会表现为
            // 「开了玻璃档但什么都没变」，而那要排查很久）
            Log.w(TAG, "玻璃高光 shader 构造失败（第 $i 层），本层降级为纯底色", t)
            null
        }
    }

    /** 尺寸变了要重写 uniform（只有尺寸与圆角进 shader，颜色/角度是常量）。 */
    private var uniformsDirty = true
    private var reported = false
    private var shaderUnavailable = false
    private var lastSize = ""
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x70E3F0FF
        style = Paint.Style.STROKE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        uniformsDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        drawMaterial(canvas, width, height, cornerRadiusPx)
    }

    /** 预览与运行使用同一个材质绘制器；预览不伪造一套 shader。 */
    fun drawMaterial(canvas: Canvas, w: Int, h: Int, radiusPx: Float) {
        if (w <= 0 || h <= 0) return
        // 圆角不能超过短边的一半（否则 SDF 退化，高光会糊成一团）
        val r = radiusPx.coerceIn(0f, min(w, h) / 2f)

        // ① 底色。这一笔在软件画布上也成立，所以放在 shader 判断之前。
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, tintPaint)

        // ② 高光。RuntimeShader 需要硬件加速的画布（软件画布上 setShader 不生效），
        //    拿不到就只留底色 —— 观感降级，但不抛、不黑块。
        val signature = "$w/$h/$r"
        if (lastSize != signature) { uniformsDirty = true; lastSize = signature }
        if (canvas.isHardwareAccelerated && !shaderUnavailable) {
            runCatching {
                if (uniformsDirty) {
                    pushUniforms(w.toFloat(), h.toFloat(), r)
                    uniformsDirty = false
                }
                for (shader in shaders) {
                    if (shader == null) continue
                    shaderPaint.shader = shader
                    canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, shaderPaint)
                }
            }.onFailure {
                shaderUnavailable = true
                Log.w(TAG, "玻璃材质绘制失败，保留底色与边缘高光", it)
            }
        }
        // 高光须落在明确边界；软件画布或 shader 不可用时也保留可辨识的降级。
        rimPaint.strokeWidth = maxOf(1f, r / 12f)
        val inset = rimPaint.strokeWidth / 2f
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, rimPaint)
        if (!reported) {
            reported = true
            Log.i(TAG, "material=glass shaders=${if (shaderUnavailable) 0 else shaders.count { it != null }} hardware=${canvas.isHardwareAccelerated} fallback=${!canvas.isHardwareAccelerated || shaderUnavailable}")
        }
    }

    /**
     * 把尺寸/four-corner 半径/每一层的颜色写进对应的 shader。**只在尺寸变化时调**。
     *
     * ## 颜色为什么要显式预乘
     *
     * AGSL 的 `main` 返回值语义在文档里没有一句话讲清「预乘还是非预乘」，而两种可能的
     * 差别只在亮度（不会画错位置、不会崩）。这里按**预乘**输出
     * （`rgb × a × intensity`，`a × intensity`）—— 与 Skia 的 shader 输出约定一致。
     * 若真机上观感明显偏暗（表现为「高光几乎看不见」），那就是这里反了：
     * 把 `a` 从 rgb 里除掉即可（**这一条的判定必须靠真机截图，不能靠读文档**）。
     */
    private fun pushUniforms(w: Float, h: Float, r: Float) {
        for (i in shaders.indices) {
            val s = shaders[i] ?: continue
            val p = PASSES[i]
            s.setFloatUniform("size", w, h)
            s.setFloatUniform("cornerRadii", r, r, r, r)
            // 颜色：预乘（见上面的说明）
            s.setFloatUniform("color", p.r * p.a, p.g * p.a, p.b * p.a, p.a)
            s.setFloatUniform("angle", Math.toRadians(p.angleDeg.toDouble()).toFloat())
            s.setFloatUniform("falloff", p.falloff)
        }
    }

    internal companion object {
        private const val TAG = "FP-SideIndex"

        /** 面板圆角（dp）。真机面板没有背景，这个值是本档新定的观感值（不是 Flyme 的哪一项）。 */
        const val GLASS_CORNER_DP = 18f

        /**
         * 玻璃底色。**很淡**：面板下面已经是「模糊过的桌面 + 渐变遮罩」，
         * 底色只负责让高光有一个承接面，盖厚了会把模糊吃掉（那就变成一块灰板）。
         */
        const val GLASS_TINT = 0x14FFFFFF

        /**
         * 三层 pass。参数是**观感旋钮**，改这里就是改观感：
         *
         * | 层 | 作用 | 做法 |
         * | --- | --- | --- |
         * | 1 | 描边高光 | 白、光源在左上（315°）、`falloff = 1.0` → 朝左上那侧亮 |
         * | 2 | 内阴影 | 深色、方向相反（135°）、`falloff = 1.6` → 右下侧一圈暗边 |
         * | 3 | 色边 | 偏冷的浅蓝、横向（25°）、`falloff = 2.4` → 只勾在边缘（内部被压平）|
         *
         * ⚠️ 这四个数是**未在真机上看过的初值**（本轮只编译、不装机）。真机上的调法：
         * 先只看第 1 层（把另外两层的 alpha 设 0）确认「高光能看见」，
         * 再逐层加回来 —— 一次调三层会分不清是哪一层的贡献。
         */
        val PASSES = arrayOf(
            Pass(1f, 1f, 1f, 0.50f, 315f, 1.0f),
            Pass(0f, 0f, 0f, 0.30f, 135f, 1.6f),
            Pass(0.55f, 0.78f, 1f, 0.32f, 25f, 2.4f),
        )

        /**
         * 建一层玻璃面板；不可用时返回 null。**任何异常都吃掉**（本类是纯观感增强，
         * 它失败不该让浮层画不出来）。
         *
         * @param density 屏幕密度（圆角按 dp 换算）
         */
        fun create(context: Context, density: Float): View? = runCatching {
            SideIndexGlassPanel(context, GLASS_CORNER_DP * density)
        }.getOrElse { t ->
            Log.w(TAG, "玻璃材质层创建失败，本档退回纯模糊观感", t)
            null
        }
    }
}
