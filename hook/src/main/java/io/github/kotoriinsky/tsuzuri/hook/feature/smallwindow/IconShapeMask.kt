package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.graphics.Path
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShape
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShapeParams
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShapes

/**
 * 把图标裁成用户选的**形状**（`icon_shape`）—— hook 侧的裁剪实现。
 *
 * ## 它在链路的哪一环
 *
 * 图标从「取到」到「画出来」只有两环，形状是**外面那一环**：
 *
 * ```
 * ① 取谁的图标（回退链，一行都没动）
 *      单项覆盖 > 单项图标包 > 整体图标包（含 iconback/mask/upon 合成）> 自带图标
 * ② 裁成什么形状（这里 + [FanOverlay.circularIcon]）
 *      把 ① 的最终结果按 [IconShape] 的轮廓裁一遍
 * ```
 *
 * 也就是说：**形状不参与「谁赢」**。它拿到的永远是那条链已经决定好的那一张图，
 * 裁一刀而已 —— 所以「用户换了形状，结果图标包里的图标不生效了」这种串味不可能发生。
 *
 * ## 为什么裁剪落在绘制层，而不是取图标那条链上
 *
 * 取图标那条链产出的是**原始 drawable**（还要被图标包的 `iconback/mask/upon` 再合成一次），
 * 而屏幕上真正决定「圆 / 圆角方」的，一直是 [FanOverlay.circularIcon] 里那个硬编码的
 * `addCircle` —— 今天所有扇形图标都是被它裁成圆的。形状只要不落在这里，就会被那个圆
 * 再裁一次，圆角方形的四个角当场消失（症状是「改了没反应」）。
 *
 * 顺带两个好处：裁剪在 `show()` 里**每个图标只做一次**（展开之前，`onDraw` 只
 * `drawBitmap`，符合规范 §7.4 对动画帧的要求）；而「不透明底」由位图的 ARGB_8888 承载，
 * 形状之外的像素是透明的，不是画出来的白块。
 *
 * ## 几何全部来自契约层
 *
 * 轮廓的采样在 [IconShapes.outline]（core，纯数学）—— 这里只做「把点连成
 * `android.graphics.Path`」这一件必须有 Android 才能做的事。
 * 同一个采样函数也是**设置界面预览**用的那一个，所以预览里的方角与真机逐点一致。
 *
 * ## 线程
 *
 * 只被 [FanOverlay.circularIcon] 在 `show()` 里调（**主线程**，展开之前）。
 * `Path` 由调用方复用（每次展开建一次，不是每个图标建一次）：形状只与
 * (形状, 尺寸, 线宽) 有关，同一屏里每一项都一样。
 */
internal object IconShapeMask {

    /**
     * 一次轮廓准备的结果。
     *
     * @param clipIcons 要不要按形状裁。`false` 只有一种情况：[IconShape.ORIGINAL]（保留原图边界）
     * @param edgeCircleRadius > 0 时**细边要用 `drawCircle` 画**（缺省的正圆），
     *   值就是这个圆的半径；`0` = 按 [edge] 这条路径描边。
     *
     * 为什么正圆的细边要单独留着 `drawCircle` 这条路：它是缺省渲染路径，
     * 而 `drawCircle` 与「把圆做成 Path 再 drawPath」在 Skia 里不是同一条代码路径
     * （抗锯齿的取整可能差一个亚像素）。这次改动只加形状，不动缺省形状的样子 ——
     * 免得「没配形状的用户」看到一个像素级的变化后再也说不清是不是这里改的。
     */
    class Prepared(val clipIcons: Boolean, val edgeCircleRadius: Float)

    /**
     * 按当前形状填好两个轮廓，供这一屏的全部图标复用。
     *
     * ```
     * clip  裁切轮廓：形状**贴满**图标位图（只缩进 [inset] px，与原来那个
     *       `addCircle(center, center, center - 0.5f)` 的缩进一致）
     * edge  细边轮廓：再往里缩 [edgeInset] —— 描边是以路径为中心画的，
     *       缩进去才能让整条边留在位图里（原来的 circle 版就是这个缩进）
     * ```
     *
     * @param params **解析后的**形状参数（用户数值已经解析过：`0` → 形状自己的默认值、
     *   越界已夹取）。见 [IconShapeParams] —— 这里不再碰配置，只做「把点连成 Path」
     * @param clip 输出：裁切轮廓（会被 reset）
     * @param edge 输出：细边轮廓（会被 reset）
     * @param size 图标位图边长（px）
     * @param inset 裁切轮廓相对位图边缘的缩进（**px**）。缺省形状（正圆）用的是
     *   上一版写死的 `0.5f`（px，不是 dp）—— 保留它才能让「不配形状」的渲染逐像素不变
     * @param edgeInset 细边轮廓再往里缩多少（**px**），**负数 = 往外放**。扇形现在传的是
     *   **负的线宽一半**：整条边落在图标外面、一个图标像素都不盖（2026-10-29，用户「图标有点透」——
     *   53% 白的边压在图标最外圈，看着就是边缘半透明）。各形状共用同一个值，切形状时图标尺寸不跳
     */
    fun prepare(
        params: IconShapeParams,
        clip: Path,
        edge: Path,
        size: Float,
        inset: Float,
        edgeInset: Float,
    ): Prepared {
        clip.reset()
        edge.reset()
        val safeSize = if (size.isFinite() && size > 2f) size else 2f
        val safeInset = inset.coerceIn(0f, safeSize / 4f)
        val safeEdgeInset = if (edgeInset.isFinite()) edgeInset.coerceIn(-safeSize / 4f, safeSize / 4f) else 0f
        val center = safeSize / 2f
        val shape = params.shape

        when (shape) {
            // 正圆：**逐字保留原来的两句**（`addCircle` + `drawCircle`），而不是用
            // 96 边形去近似 —— 这是「缺省形状」的渲染路径，改它等于改掉所有人今天看到的
            // 样子。两个半径就是原来那两个表达式：`center - 0.5f`
            // 与 `center - 0.5f - 0.5dp/2`
            //
            // ⚠️ 正圆的轮廓**与 [IconShapeParams] 无关**（半径恒为半边长），
            // 所以用户拖 `icon_shape_corner` 对圆形没有影响 —— 那件事由
            // [IconShapes.resolveParams] 写成日志提示，不在这里偷偷变成别的形状。
            IconShape.CIRCLE -> {
                val clipRadius = (center - safeInset).coerceAtLeast(1f)
                clip.addCircle(center, center, clipRadius, Path.Direction.CW)
                return Prepared(clipIcons = true, edgeCircleRadius = (clipRadius - safeEdgeInset).coerceAtLeast(1f))
            }

            // 方形：不裁（保留原图边界），但要描一条方形细边 ——
            // 细边是图标边界唯一的视觉来源（深色图标压在深色壁纸上会糊成一片）
            IconShape.ORIGINAL -> {
                val o = safeInset + safeEdgeInset
                edge.addRect(o, o, safeSize - o, safeSize - o, Path.Direction.CW)
                return Prepared(clipIcons = false, edgeCircleRadius = 0f)
            }

            // 圆角方形 / 超椭圆 / 水滴：多边形成环（点数由契约层的采样函数决定）
            else -> {
                polygon(clip, params, safeSize - 2f * safeInset, safeInset)
                polygon(edge, params, safeSize - 2f * (safeInset + safeEdgeInset), safeInset + safeEdgeInset)
                return Prepared(clipIcons = true, edgeCircleRadius = 0f)
            }
        }
    }

    /**
     * 把 [IconShapes.outline] 采样出来的点连成一个闭环 [Path]。
     *
     * @param params **解析后的**形状参数（`0` 已解析成形状默认值）—— 直接用
     *   `icon_shape_corner = 0` 会把圆角画成 0（方角），那与「没配过」不是一件事
     * @param box 轮廓占据的正方形边长（px）
     * @param offset 这个正方形在图标位图里的左上角坐标 —— 形状是**居中**摆的，
     *   所以 offset =（位图边长 − box）/ 2
     */
    private fun polygon(out: Path, params: IconShapeParams, box: Float, offset: Float) {
        val safeBox = if (box.isFinite() && box > 1f) box else 1f
        val pts = IconShapes.outline(params, safeBox)
        if (pts.size < 6) {
            // 采样函数保证至少 4 个点（方形）。真拿到更少的说明契约被破坏了：
            // 退回一个圆角方形的**外接矩形**而不是什么都不画（绝不画空）
            out.addRect(offset, offset, offset + safeBox, offset + safeBox, Path.Direction.CW)
            return
        }
        out.moveTo(pts[0] + offset, pts[1] + offset)
        var i = 2
        while (i < pts.size) {
            out.lineTo(pts[i] + offset, pts[i + 1] + offset)
            i += 2
        }
        // 采样契约是「首点 ≠ 末点，闭合由调用方负责」——这里就是那个调用方
        out.close()
    }
}
