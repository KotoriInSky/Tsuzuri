package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 预览 API（`fanPreview`）—— **设置界面画的就是这个**。
 *
 * 这一层要钉住的不是「某个数等于多少」，而是三件事：
 *
 *  1. **预览与真机同源**：所有派生量都来自同一个求解器，所以这里对「屏内」「半径被谁夹住」
 *     的断言，与真机那份几何是同一套判据（也正因为如此，预览不会是「好看的谎言」）；
 *  2. **参数怎么进、怎么出**：每个入参的单位与基准（宽用屏宽、高用屏高、半径用短边）
 *     都在断言里出现过一次，改错了会当场红；
 *  3. **`system` 档就是 `null`**（「交给系统摆放」是一个有语义的状态，
 *     不能用一个占位矩形假装它是我们摆的；`flyme` / `custom` 两档都必须给出矩形）。
 */
class FanPreviewTest {

    /** 本机（Xiaomi 17 Pro）的真实尺寸：1220 × 2656、520dpi → density 3.25。 */
    private fun params(
        count: Int = 17,
        layoutMode: String = "smart",
        customInner: Int = 7,
        customMiddle: Int = 8,
        radiusPercent: Float = 30f,
        iconDp: Float = 34f,
        hotWidthPercent: Float = 8f,
        hotHeightPercent: Float = 11f,
        edgeMarginDp: Float = 10f,
        staggerSlots: Float = 0.5f,
        corners: String = "both_bottom",
        windowPreset: String = WindowBounds.PRESET_CUSTOM,
        windowXPercent: Float = 15f,
        windowYPercent: Float = 15f,
        windowWidthPercent: Float = 70f,
        windowHeightPercent: Float = 70f,
        iconShape: IconShape = IconShape.CIRCLE,
        selectionScalePercent: Float = 122f,
        iconAlphaPercent: Float = 100f,
        revealAmountPercent: Float = 65f,
        edgeEnabled: Boolean = true,
        edgeWidthDp: Float = 0.5f,
        shapeCornerPercent: Float = 0f,
        shapeExponent: Float = 0f,
    ) = FanPreviewParams(
        screenW = 1220f,
        screenH = 2656f,
        density = 3.25f,
        hotWidthPercent = hotWidthPercent,
        hotHeightPercent = hotHeightPercent,
        corners = corners,
        layoutMode = layoutMode,
        customInner = customInner,
        customMiddle = customMiddle,
        radiusPercent = radiusPercent,
        iconDp = iconDp,
        edgeMarginDp = edgeMarginDp,
        staggerSlots = staggerSlots,
        selectionScalePercent = selectionScalePercent,
        iconShape = iconShape,
        iconAlphaPercent = iconAlphaPercent,
        revealAmountPercent = revealAmountPercent,
        edgeEnabled = edgeEnabled,
        edgeWidthDp = edgeWidthDp,
        shapeCornerPercent = shapeCornerPercent,
        shapeExponent = shapeExponent,
        windowPreset = windowPreset,
        windowWidthPercent = windowWidthPercent,
        windowHeightPercent = windowHeightPercent,
        windowXPercent = windowXPercent,
        windowYPercent = windowYPercent,
        count = count,
    )

    @Test
    fun hotSpotWidthIsRelativeToTheWidthAndHeightToTheHeight() {
        // ⚠️ 这两个百分比的基准**不同**（上游实测结论）：宽 × 屏宽、高 × 屏高。
        // 想当然地都按短边算，横竖屏下热区尺寸会全错 —— 所以这条单独钉
        val p = fanPreview(params())
        assertEquals(0.08f * 1220f, p.hotLeft.width, 0.01f)
        assertEquals(0.11f * 2656f, p.hotLeft.height, 0.01f)
        // 左下角贴屏幕左下、右下角贴屏幕右下（热区不跟着弧心的留距挪）
        assertEquals(0f, p.hotLeft.left, 0.01f)
        assertEquals(2656f, p.hotLeft.bottom, 0.01f)
        assertEquals(1220f, p.hotRight.right, 0.01f)
        assertEquals(p.hotLeft.width, p.hotRight.width, 0.01f)
        assertEquals(p.hotLeft.height, p.hotRight.height, 0.01f)
        // 两个热区在底边上互不重叠（左角优先的判据据此成立）
        assertTrue(p.hotLeft.right <= p.hotRight.left)
        // 角上那一点确实在热区里
        assertTrue(p.hotLeft.contains(10f, 2650f))
        assertTrue(p.hotRight.contains(1210f, 2650f))
        assertTrue(!p.hotLeft.contains(1220f - 5f, 2650f))
    }

    @Test
    fun cornerOnlyEnabledCornersAreMarked() {
        assertTrue(fanPreview(params(corners = "both_bottom")).leftEnabled)
        assertTrue(fanPreview(params(corners = "both_bottom")).rightEnabled)
        assertTrue(fanPreview(params(corners = "bottom_left")).leftEnabled)
        assertTrue(!fanPreview(params(corners = "bottom_left")).rightEnabled)
        assertTrue(!fanPreview(params(corners = "bottom_right")).leftEnabled)
        assertTrue(fanPreview(params(corners = "bottom_right")).rightEnabled)
    }

    @Test
    fun arcCenterIsPushedInlandByTheEdgeMargin() {
        // 弧心**不在屏幕角上**：`fan_edge_margin` 把它沿两个轴往屏内推（用户反馈「贴边的项被裁」）
        val p = fanPreview(params(edgeMarginDp = 10f))
        assertEquals(32.5f, p.edgeMarginPx, 0.01f) // 10dp × 3.25
        assertEquals(32.5f, p.cornerLeftX, 0.01f)
        assertEquals(1220f - 32.5f, p.cornerRightX, 0.01f)
        assertEquals(2656f - 32.5f, p.cornerY, 0.01f)
        assertEquals(p.cornerLeftX, p.cornerX(false), 0.01f)
        assertEquals(p.cornerRightX, p.cornerX(true), 0.01f)
        // 留距改成 0 就是「钉回屏幕角」（参照实现的行为）
        assertEquals(0f, fanPreview(params(edgeMarginDp = 0f)).cornerLeftX, 0.01f)
    }

    @Test
    fun radiusIsTheDerivedValueNotTheConfiguredOne() {
        // 预览绝不能直接显示 `selection_radius_percent` 乘出来的那个数：
        // 那是**初值**，真正画出来的是四条约束解完之后的那个（本机默认参数下两者相等）
        val small = fanPreview(params(count = 3))
        assertEquals(366f, small.configuredRadius, 0.01f) // 短边 1220 × 30%
        assertTrue(small.radius >= small.configuredRadius - 0.01f)
        // 「屏内」那条下限会把半径往上顶（3 项时 floor ≈ 426 > 初值 366）
        assertTrue(small.radius > 300f)
        // 项数一多，半径被「不重叠」顶得更大
        val many = fanPreview(params(count = 24))
        assertTrue(many.radius > small.radius, "24 项的半径该比 3 项大：${many.radius} vs ${small.radius}")
        // 派生量的四个数都要报出来（与真机日志同一组量）
        assertTrue(many.requiredRadius > 0f)
        assertTrue(many.capRadius >= many.radius - 0.5f, "上限必须夹住最终半径")
        assertTrue(many.floorRadius <= many.radius + 0.5f, "下限必须低于最终半径")
        assertEquals(small.iconDiameter, small.configuredIconDiameter, 0.01f) // 3 项不缩图标
        assertEquals(34f * 3.25f, small.configuredIconDiameter, 0.01f)
    }

    @Test
    fun itemCountAndOrderFollowTheGivenCount() {
        // 「第 1 项在第 1 个槽位」：传入几项就画几项，顺序由调用方给的清单决定
        for (n in listOf(1, 3, 17, 24)) {
            val p = fanPreview(params(count = n))
            assertEquals(n, p.count, "n=$n")
            assertEquals(n, p.items.size)
            assertEquals((0 until n).toList(), p.items.map { it.index })
        }
        // 分层与真机同一套规则：17 项 → 5/6/6
        assertEquals(listOf(5, 6, 6), fanPreview(params(count = 17)).plan.rows.map { it.count })
        // 每项的 rowIndex / slotInRow 与 plan 对得上，且顺序连续
        val p = fanPreview(params(count = 17))
        var expectRow = 0
        var expectSlot = 0
        for (item in p.items) {
            while (expectSlot >= p.plan.rows[expectRow].count) {
                expectRow++
                expectSlot = 0
            }
            assertEquals(expectRow, item.rowIndex, "item${item.index}")
            assertEquals(expectSlot, item.slotInRow, "item${item.index}")
            expectSlot++
        }
    }

    @Test
    fun singleSlotLandsInTheMiddleOfTheFanAndThreeSlotsSpanTheSpan() {
        // 1 项 → 47°（扇子正中）；3 项 → 12° / 47° / 82°。这两个是纯几何的锚点，
        // 预览要是把它们画错了，用户一眼就能看出来（三个图标挤在一头）
        val one = fanPreview(params(count = 1))
        assertEquals(FanArcs.SINGLE_SLOT_DEG, one.items[0].angleDeg, 1e-6)

        val three = fanPreview(params(count = 3))
        assertEquals(12.0, three.items[0].angleDeg, 1e-6)
        assertEquals(47.0, three.items[1].angleDeg, 1e-6)
        assertEquals(82.0, three.items[2].angleDeg, 1e-6)
        // 角度越大越靠上、越靠侧边（y 更小、离弧心的横向偏移更小）
        assertTrue(three.items[0].centerLeftX > three.items[2].centerLeftX)
        assertTrue(three.items[0].centerY > three.items[2].centerY)
    }

    @Test
    fun itemCentersAreTheCornerPlusTheLocalOffset() {
        // 屏幕坐标 = 弧心 + 本地偏移（贴右时 x 镜像），y 不镜像 —— 与真机 itemX/itemY 同一条规则
        val p = fanPreview(params(count = 17))
        val r0 = p.items[0]
        // 第一项在内层（半径 = 0.68 × R），角度 12°
        val innerRadius = 0.68f * p.radius
        assertEquals((p.cornerLeftX + innerRadius * cos(Math.toRadians(12.0))).toFloat(), r0.centerLeftX, 0.05f)
        assertEquals((p.cornerRightX - innerRadius * cos(Math.toRadians(12.0))).toFloat(), r0.centerRightX, 0.05f)
        assertEquals((p.cornerY - innerRadius * Math.sin(Math.toRadians(12.0))).toFloat(), r0.centerY, 0.05f)
        // 左右两角关于屏幕中线镜像（x 轴），y 相同
        for (item in p.items) {
            assertEquals(1220f - item.centerLeftX, item.centerRightX, 0.01f, "item${item.index}")
            assertEquals(item.centerLeftX, item.centerX(false), 0.01f)
            assertEquals(item.centerRightX, item.centerX(true), 0.01f)
        }
    }

    @Test
    fun everyItemIsFullyOnScreenForTheDefaultIconSize() {
        // 「屏内」那条硬保证在预览里也要成立：任何一项都不许跑到屏幕外
        // （用户看不到的项 = 等于他选的应用丢了）。
        // 34dp 是出厂图标大小，覆盖 1/3/9/17/24 项五档都不该出现越界
        for (n in listOf(1, 3, 9, 17, 24)) {
            val p = fanPreview(params(count = n, iconDp = 34f))
            assertEquals(0f, p.screenOverflowPx, 0.5f, "n=$n 有项出屏：${p.fitDesc}")
            for (item in p.items) {
                val r = item.hitRadius
                assertTrue(item.centerLeftX - r >= -1f, "n=$n 左角 item${item.index} 出血")
                assertTrue(item.centerRightX + r <= 1220f + 1f, "n=$n 右角 item${item.index} 出血")
                assertTrue(item.centerY - r >= -1f, "n=$n item${item.index} 顶到屏幕外")
                assertTrue(item.centerY + r <= 2656f + 1f, "n=$n item${item.index} 掉出底边")
            }
        }
    }

    @Test
    fun oversizedIconsAreHonestlyReportedAsOverConstrained() {
        // ⚠️ **已知的过约束档**（96dp 图标 + 3 项、单层）：`keepOut` 远大于弧心留距时，
        // 「屏内·靠近弧心那一侧」要求的最小半径（止角项 ρ·cos82°）会超过
        // 「屏内·远离弧心那一侧」允许的最大半径（起角项 ρ·cos12°）—— **下限 > 上限**，
        // 物理上无解。求解器按写死的优先级（屏内 > 不重叠 > 图标大小）**保上限**，
        // 于是最靠侧边的那一项在选中放大时会出屏，表现为 `fit=!核验出屏NNpx`。
        //
        // 这是**搬动之前就存在**的行为（这次搬动是纯文本移动，一个字符没改），
        // 所以这里钉的是「它确实这么表现」，而不是断言成 0：
        // 断言 0 会红，而顺手改降级顺序（比如让图标继续缩小去救半径）是**产品决定**，
        // 不该藏在一次重构里。先如实记录，等有人真去决定「屏内 vs 图标大小」谁让位。
        val p = fanPreview(params(count = 3, iconDp = 96f))
        assertTrue(p.screenOverflowPx > 0.5f, "这一档本应是过约束：overflow=${p.screenOverflowPx}")
        assertTrue(p.fitDesc.startsWith("fit=!核验出屏"), "过约束必须在日志里如实报出来：${p.fitDesc}")
        // 保上限是写死的优先级 → 上限必须仍然夹住半径（宁可挤也不许无限制地撑大）
        assertTrue(p.capRadius >= p.radius - 0.5f)
        assertTrue(p.floorRadius > p.capRadius, "这一档的定义就是下限 > 上限")
    }

    @Test
    fun customLayoutShowsTheDerivedOuterRow() {
        // 自定义模式：预览必须显示**夹取之后**的三排数量（用户配的是上限），
        // 否则「我明明把内排调成 8，为什么还是 7 个」在预览里看不见
        val p = fanPreview(params(count = 17, layoutMode = "custom", customInner = 7, customMiddle = 8))
        assertEquals(listOf(7, 8, 2), p.plan.rows.map { it.count })
        assertTrue(p.planDesc.startsWith("plan=custom"))
        assertEquals("plan=smart n=17", fanPreview(params(count = 17)).planDesc)
    }

    @Test
    fun windowRectIsNullOnlyForTheSystemPreset() {
        // `system` 档 = 「交给系统摆放」，是一个有语义的状态：预览这时**不该**画窗口框
        assertNull(fanPreview(params(windowPreset = WindowBounds.PRESET_SYSTEM)).windowRect)

        // `flyme` 档用四个实测值 —— **滑杆传什么都不影响它**（这是当初把它单列一档的理由）
        val flyme = fanPreview(
            params(
                windowPreset = WindowBounds.PRESET_FLYME,
                windowWidthPercent = 31f,
                windowHeightPercent = 22f,
                windowXPercent = 0f,
                windowYPercent = 0f,
            ),
        )
        assertEquals(
            WindowBounds.defaults(1220f, 2656f),
            flyme.windowRect,
            "Flyme 档的矩形必须就是 `WindowBounds.defaults()`（不读滑杆）",
        )

        // `custom` 档才读滑杆
        val on = fanPreview(params(windowPreset = WindowBounds.PRESET_CUSTOM))
        val rect = on.windowRect
        assertNotNull(rect)
        assertEquals(1220f * 0.70f, rect.width, 0.01f)
        assertEquals(2656f * 0.70f, rect.height, 0.01f)
        // ⚠️ 2026-09-17 起 x/y 是「窗口在**可移动区间**里的比例」（v1 是左边缘、v2 是中心
        // 的绝对位置）。`params()` 的默认位置是 15% —— 在 70% 宽的窗口上就是
        // 「可移动区间的前 15%」，即左边缘 = (屏宽 − 窗宽) × 15%。
        assertEquals((1220f - 1220f * 0.70f) * 0.15f, rect.left, 0.01f)
        assertEquals((2656f - 2656f * 0.70f) * 0.15f, rect.top, 0.01f)
        // 居中那一档：50% 就是正中，与窗口尺寸无关
        val centered = fanPreview(
            params(windowPreset = WindowBounds.PRESET_CUSTOM, windowXPercent = 50f, windowYPercent = 50f),
        ).windowRect!!
        assertEquals(1220f / 2f, centered.centerX, 0.01f)
        assertEquals(2656f / 2f, centered.centerY, 0.01f)
        assertEquals(1220f / 2f - 1220f * 0.70f / 2f, centered.left, 0.01f)

        // 认不出的取值 → 走默认档（与「键没声明」同一个处置），不是「一个框都不画」：
        // 后者会让用户以为功能没了，而真机那边 hook 走的是同一个函数、同一个兜底
        assertEquals(
            WindowBounds.defaults(1220f, 2656f),
            fanPreview(params(windowPreset = "谁知道呢")).windowRect,
        )
    }

    @Test
    fun windowRectIsClampedInsideTheScreen() {
        // 中心拖到 100% 时窗口会整块跑出屏幕 —— 必须夹回来
        // （「中心到边界 = 贴边」，夹取之后右边正好贴住屏幕右边）
        val right = fanPreview(
            params(windowPreset = WindowBounds.PRESET_CUSTOM, windowXPercent = 100f, windowYPercent = 100f),
        ).windowRect!!
        assertEquals(1220f - 1220f * 0.70f, right.left, 0.01f)
        assertEquals(2656f - 2656f * 0.70f, right.top, 0.01f)
        assertEquals(1220f, right.right, 0.01f)
        assertEquals(2656f, right.bottom, 0.01f)
        // 上限：窗口比屏幕还大时夹到屏幕尺寸，不出现负的 left
        val huge = ScreenRect(-500f, -500f, 5000f, 5000f).clampedToScreen(1220f, 2656f)
        assertEquals(ScreenRect(0f, 0f, 1220f, 2656f), huge)
    }

    @Test
    fun shapeIsPassedThroughAndDefaultsAreExplicit() {
        // 形状只是**透传**给预览的绘制层（与真机同一份 IconShapes.outline）
        assertEquals(IconShape.SQUIRCLE, fanPreview(params(iconShape = IconShape.SQUIRCLE)).iconShape)
        assertEquals(IconShape.CIRCLE, fanPreview(params()).iconShape)
        // `null` / 认不出的取值 → 圆形（= 加这个参数之前的行为），绝不画空
        assertEquals(IconShape.CIRCLE, IconShape.of(null))
        assertEquals(IconShape.CIRCLE, IconShape.of("  "))
        assertEquals(IconShape.CIRCLE, IconShape.of("圆圆圆"))
        assertEquals(IconShape.TEARDROP, IconShape.of(" teardrop "))
    }

    @Test
    fun selectionScaleAndRingWidthComeFromTheSameConstantsAsTheHook() {
        val p = fanPreview(params(selectionScalePercent = 122f))
        assertEquals(1.22f, p.selectionScale, 1e-4f)
        // 描环线宽 = 3dp × density；命中圆半径 = 图标半径 + 6dp（与真机同一组常量）
        assertEquals(FanArcs.RING_STROKE_DP * 3.25f, p.ringStrokePx, 0.01f)
        assertEquals(p.iconDiameter / 2f + FanArcs.HIT_TOLERANCE_DP * 3.25f, p.hitRadius, 0.01f)
        assertEquals(p.hitRadius, p.items[0].hitRadius, 0.01f)
    }

    @Test
    fun degenerateInputsProduceNoNaNAndNeverThrow() {
        // 预览是**界面线程上的纯函数**：屏幕尺寸退化（0 / 负）时它绝不能抛
        // （那会把设置界面打崩），也不能产出 NaN（NaN 画出来就是空白，用户看到的是
        // 「预览不见了」，且没有任何错误提示）。判据就两条：不抛、坐标有限。
        for ((w, h) in listOf(0f to 0f, -100f to -100f, 1f to 1f, 1220f to 0f)) {
            val p = fanPreview(
                FanPreviewParams(
                    screenW = w, screenH = h, density = 3.25f,
                    hotWidthPercent = 8f, hotHeightPercent = 11f, corners = "both_bottom",
                    layoutMode = "smart", customInner = 7, customMiddle = 8,
                    radiusPercent = 30f, iconDp = 34f, edgeMarginDp = 10f,
                    staggerSlots = 0.5f, selectionScalePercent = 122f,
                    iconShape = IconShape.CIRCLE, windowPreset = WindowBounds.PRESET_CUSTOM,
                    windowWidthPercent = 70f, windowHeightPercent = 70f,
                    windowXPercent = 15f, windowYPercent = 15f, count = 3,
                ),
            )
            assertEquals(3, p.count, "屏幕 ${w}x$h")
            for (item in p.items) {
                assertTrue(item.centerLeftX.isFinite() && item.centerY.isFinite(), "屏幕 ${w}x$h")
                assertTrue(item.angleDeg.isFinite(), "屏幕 ${w}x$h")
            }
            assertTrue(p.radius.isFinite() && p.iconDiameter.isFinite(), "屏幕 ${w}x$h")
            assertTrue(p.hotLeft.width.isFinite() && p.hotRight.top.isFinite(), "屏幕 ${w}x$h")
            // 小窗矩形照样夹在屏幕内（尺寸为 0 时退化成空矩形，而不是负宽）
            assertTrue((p.windowRect?.width ?: 0f) >= 0f, "屏幕 ${w}x$h")
        }
    }

    @Test
    fun shapeParamsAreResolvedFromTheUserPercentages() {
        // 预览必须拿到**解析后**的形状参数（`0` 已解析成形状默认值），否则
        // 用户拖了圆角滑块、预览不动而真机在动（见 FanPreviewParams 的 KDoc）
        val d = fanPreview(params(iconShape = IconShape.ROUNDED_SQUARE))
        assertEquals(IconShape.ROUNDED_SQUARE, d.iconShapeParams.shape)
        assertEquals(IconShapes.ROUNDED_SQUARE_CORNER, d.iconShapeParams.cornerFraction, 0f)
        assertEquals("", d.iconShapeParams.clampNote)

        // 30% → 0.3（Catalog 的滑块量程 0–50%）
        val custom = fanPreview(
            params(iconShape = IconShape.ROUNDED_SQUARE, shapeCornerPercent = 30f),
        )
        assertEquals(0.3f, custom.iconShapeParams.cornerFraction, 1e-6f)

        // 柔度只对超椭圆有意义；给了值就生效，给别的形状就只在 clampNote 里提示
        val sq = fanPreview(params(iconShape = IconShape.SQUIRCLE, shapeExponent = 8f))
        assertEquals(8f, sq.iconShapeParams.exponent, 1e-6f)
        val wrongShape = fanPreview(params(iconShape = IconShape.CIRCLE, shapeCornerPercent = 30f))
        assertTrue(wrongShape.iconShapeParams.clampNote.isNotEmpty(), wrongShape.iconShapeParams.clampNote)

        // 这三组参数**不许**动几何：形状/描边/透明度都不是几何量
        val base = fanPreview(params(count = 17))
        for (p in listOf(custom, sq, fanPreview(params(count = 17, iconAlphaPercent = 20f)))) {
            assertEquals(base.radius, p.radius, 0.001f)
            assertEquals(base.iconDiameter, p.iconDiameter, 0.001f)
            assertEquals(base.items[0].centerLeftX, p.items[0].centerLeftX, 0.001f)
        }
    }

    @Test
    fun edgeWidthIsConvertedFromDensityIndependentPixels() {
        // ② 的单位修正：`icon_edge_width` 是 **dp**，预览给的是 px（= dp × density）。
        // 上一版 0.5 被当 px 用，所以这条断言本身就是那次修正的证据
        val def = fanPreview(params())
        assertTrue(def.iconEdgeEnabled)
        assertEquals(0.5f * 3.25f, def.iconEdgeWidthPx, 0.001f)
        for (dp in listOf(0f, 0.5f, 1f, 4f)) {
            assertEquals(
                dp * 3.25f,
                fanPreview(params(edgeWidthDp = dp)).iconEdgeWidthPx,
                0.001f,
                "dp=$dp",
            )
        }
        // 关掉：状态如实带出（宽度照样给，预览可以据此画成灰色示意）
        val off = fanPreview(params(edgeEnabled = false))
        assertTrue(!off.iconEdgeEnabled)
        assertTrue(off.iconEdgeWidthPx > 0f)
        // 非法值退默认值（dp），绝不产 NaN —— NaN 会让预览整块画不出来
        for (bad in listOf(Float.NaN, -3f)) {
            val p = fanPreview(params(edgeWidthDp = bad))
            assertTrue(p.iconEdgeWidthPx.isFinite() && p.iconEdgeWidthPx >= 0f, "dp=$bad")
        }
    }

    @Test
    fun itemsCarryTheRevealStartPoint() {
        // 动效预览的起点：真机插值是 `圆心 + (终点 − 圆心) × revealFactor`，
        // `revealFactor` 从 (1 − reveal_amount%) 走到 1 —— 预览直接用现成的起点坐标，
        // 不再自己推一遍（两份实现必然漂移）
        val p = fanPreview(params(count = 17, revealAmountPercent = 65f))
        assertEquals(0.35f, p.revealStartFactor, 1e-6f)
        for (item in p.items) {
            // 起点 = 弧心 + (终点 − 弧心) × 0.35（左右两角 x 各自镜像、y 共用）
            assertEquals(
                (p.cornerLeftX + (item.centerLeftX - p.cornerLeftX) * 0.35f).toFloat(),
                item.startCenterLeftX,
                0.01f,
                "item${item.index}",
            )
            assertEquals(
                (p.cornerRightX + (item.centerRightX - p.cornerRightX) * 0.35f).toFloat(),
                item.startCenterRightX,
                0.01f,
                "item${item.index}",
            )
            assertEquals(
                (p.cornerY + (item.centerY - p.cornerY) * 0.35f).toFloat(),
                item.startCenterY,
                0.01f,
                "item${item.index}",
            )
            assertEquals(item.startCenterLeftX, item.startCenterX(false), 0.001f)
            assertEquals(item.startCenterRightX, item.startCenterX(true), 0.001f)
            // 起点离弧心更近（0.35 倍），且标量 = 终点距离 × 0.35
            val dEnd = kotlin.math.hypot(
                (item.centerLeftX - p.cornerLeftX).toDouble(),
                (item.centerY - p.cornerY).toDouble(),
            ).toFloat()
            assertEquals(dEnd * 0.35f, item.startRadiusPx, 0.05f, "item${item.index}")
        }
        // 行程 0 → 起点系数 = 1（起点就是终点，不动画）；行程 100 → 起点落在弧心上
        val none = fanPreview(params(revealAmountPercent = 0f))
        assertEquals(1f, none.revealStartFactor, 1e-6f)
        assertEquals(none.items[0].centerLeftX, none.items[0].startCenterLeftX, 0.001f)
        assertEquals(none.items[0].centerY, none.items[0].startCenterY, 0.001f)
        val all = fanPreview(params(revealAmountPercent = 100f))
        assertEquals(0f, all.items[0].startRadiusPx, 0.01f)
        assertEquals(all.cornerLeftX, all.items[0].startCenterLeftX, 0.01f)
        assertEquals(all.cornerY, all.items[0].startCenterY, 0.01f)
        // 非有限值退默认行程（65%）而不是产出 NaN 坐标
        val nan = fanPreview(params(revealAmountPercent = Float.NaN))
        assertEquals(0.35f, nan.revealStartFactor, 1e-6f)
        assertTrue(nan.items[0].startCenterLeftX.isFinite())
    }

    @Test
    fun iconAlphaIsPassedThroughToThePreview() {
        // ③ 的不透明度：原样带出（预览画半透明图标用它）。
        // ⚠️ 它同样**不许**动几何 —— 图标透明与否和它画在哪是两个正交的问题
        assertEquals(100f, fanPreview(params()).iconAlphaPercent, 0f)
        assertEquals(1f, fanPreview(params()).iconAlpha, 1e-6f)
        val half = fanPreview(params(iconAlphaPercent = 20f))
        assertEquals(20f, half.iconAlphaPercent, 0f)
        assertEquals(0.2f, half.iconAlpha, 1e-6f)
        // 越界/非有限值都要收敛到可画的区间（NaN 会让 Paint.alpha 变成 0 → 什么都看不见）
        assertEquals(100f, fanPreview(params(iconAlphaPercent = 250f)).iconAlphaPercent, 0f)
        assertEquals(0f, fanPreview(params(iconAlphaPercent = -5f)).iconAlphaPercent, 0f)
        assertEquals(1f, fanPreview(params(iconAlphaPercent = Float.NaN)).iconAlpha, 1e-6f)
    }

    @Test
    fun describeIsReadableForLogsAndDebugging() {
        val p = fanPreview(params(count = 24))
        val text = p.toString()
        assertTrue(text.startsWith("FanPreview(1220x2656"), text)
        assertTrue(text.contains("n=24"), text)
        assertTrue(p.arcs.contains("step="), p.arcs)
        assertTrue(p.capReason.isNotEmpty())
        assertTrue(p.fitDesc.startsWith("fit="), p.fitDesc)
    }
}
