package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 图标形状的几何（[IconShapes.outline]）与取值解析。
 *
 * 为什么这些断言值能写死：轮廓是**纯数学**，与图标大小无关（除了 [IconShapes.outline] 的
 * `size` 缩放）。所以「圆心到原点的最近距离」这类量可以直接算出解析解来对照 ——
 * 那正是最有价值的一类断言：它抓的是「圆角半径接错了一个 2 倍」这种一眼看不出的错。
 */
class IconShapeTest {

    // ── 取值解析 ─────────────────────────────────────────────────────────

    @Test
    fun unknownOrMissingValuesFallBackToCircle() {
        // 回退方向必须是**加这个参数之前的行为**（圆形）：键没声明、配置被手改、
        // 未来多了个取值，用户看到的都还是原来那个圆，绝不会出现「形状变空」
        assertEquals(IconShape.CIRCLE, IconShape.of(null))
        assertEquals(IconShape.CIRCLE, IconShape.of(""))
        assertEquals(IconShape.CIRCLE, IconShape.of("   "))
        assertEquals(IconShape.CIRCLE, IconShape.of("rounded"))
        assertEquals(IconShape.CIRCLE, IconShape.of("ROUNDED_SQUARE_2"))
        assertEquals(IconShape.DEFAULT, IconShape.CIRCLE)
    }

    @Test
    fun valuesAreTheOnesWrittenIntoTheConfig() {
        // 这些字符串是**跨进程契约**（设置界面写、hook 读），发布后不可改 —— 改一个字母，
        // 老配置里的形状就会静默回退成圆（用户会说「我选的方角自己变回去了」）
        assertEquals("circle", IconShape.CIRCLE.value)
        assertEquals("rounded_square", IconShape.ROUNDED_SQUARE.value)
        assertEquals("squircle", IconShape.SQUIRCLE.value)
        assertEquals("teardrop", IconShape.TEARDROP.value)
        assertEquals("original", IconShape.ORIGINAL.value)
        // 解析要容忍大小写与空白（配置文件可能被手改过），但**不模糊匹配**
        for (s in IconShape.entries) {
            assertEquals(s, IconShape.of(s.value))
            assertEquals(s, IconShape.of("  " + s.value.uppercase() + "  "))
        }
    }

    // ── 轮廓的通用性质 ───────────────────────────────────────────────────

    @Test
    fun everyShapeStaysInsideItsBoxAndHasNoDuplicateNeighbours() {
        // 轮廓的契约：点在 [0,size]² 里、首尾不重复、没有零长度线段
        // （零长度线段会让 android.graphics.Path 画出小毛刺，在圆角处特别明显）
        for (shape in IconShape.entries) {
            val pts = IconShapes.outline(shape, 200f)
            // 至少是个像样的多边形：方形只需 4 个点（它的直边不需要采样），
            // 圆形/超椭圆按 samples 走，圆角形状按四角分摊
            assertTrue(pts.size >= 4 * 2, "$shape 点数太少（只有 ${pts.size / 2} 个点）")
            assertEquals(0, pts.size % 2, "$shape 的数组必须是 [x,y,...]")
            var i = 0
            while (i < pts.size) {
                val x = pts[i]
                val y = pts[i + 1]
                assertTrue(x.isFinite() && y.isFinite(), "$shape 有非有限坐标")
                assertTrue(x in -0.01f..200.01f && y in -0.01f..200.01f, "$shape 的点不在画布内：($x,$y)")
                i += 2
            }
            // 首点 ≠ 末点（闭合多边形由调用方的 close() 负责）
            val lastX = pts[pts.size - 2]
            val lastY = pts[pts.size - 1]
            assertTrue(pts[0] != lastX || pts[1] != lastY, "$shape 首尾重合")
            // 相邻点不得重合
            for (k in 0 until pts.size / 2) {
                val n = (k + 1) % (pts.size / 2)
                assertTrue(
                    pts[k * 2] != pts[n * 2] || pts[k * 2 + 1] != pts[n * 2 + 1],
                    "$shape 第 $k 与下一点重合",
                )
            }
        }
    }

    @Test
    fun circleTouchesAllFourEdgesAndKeepsTheRadius() {
        // 正圆：每个点到中心的距离恒等于半边长（这是「= 现状」的基准形状，最不能错的一个）
        val pts = IconShapes.outline(IconShape.CIRCLE, 200f)
        for (i in 0 until pts.size / 2) {
            val d = hypot(pts[i * 2] - 100f, pts[i * 2 + 1] - 100f)
            assertEquals(100f, d, 0.01f, "第 $i 个点不在圆上")
        }
        // 四条边的中点必须在轮廓上（贴满画布）
        assertTrue(hasPoint(pts, 200f, 100f), "右中")
        assertTrue(hasPoint(pts, 0f, 100f), "左中")
        assertTrue(hasPoint(pts, 100f, 0f), "上中")
        assertTrue(hasPoint(pts, 100f, 200f), "下中")
    }

    @Test
    fun roundedSquareCutsTheCornerWithExactlyTheDeclaredRadius() {
        // 圆角方形：角上的圆弧半径 = 半边长 × 0.45 = 45px，圆心在 (45,45)。
        // 于是「轮廓上离原点最近的点」= 角心沿对角线往原点退一个半径：
        //   (45,45) − 45·(1/√2, 1/√2) = (13.18, 13.18) → x + y = 26.36
        // 这个数是解析解，接错半径（比如忘了乘半边长、或把 0.45 当成相对边长）会明显对不上
        val pts = IconShapes.outline(IconShape.ROUNDED_SQUARE, 200f)
        var minSum = Float.MAX_VALUE
        for (i in 0 until pts.size / 2) minSum = minOf(minSum, pts[i * 2] + pts[i * 2 + 1])
        assertEquals(26.36f, minSum, 0.5f)
        // 直边贴满画布：**直边不采样**（采样点只在四段圆弧上），所以判据是「切点位置」
        // 而不是「边的中点」—— 上边是 (45,0) → (155,0) 这一段，中点 (100,0) 不是采样点
        assertTrue(hasPoint(pts, 45f, 0f, tol = 0.5f), "上边的左切点")
        assertTrue(hasPoint(pts, 155f, 0f, tol = 0.5f), "上边的右切点")
        assertTrue(hasPoint(pts, 45f, 200f, tol = 0.5f), "下边的左切点")
        assertTrue(hasPoint(pts, 155f, 200f, tol = 0.5f), "下边的右切点")
        assertTrue(hasPoint(pts, 0f, 45f, tol = 0.5f), "左边的上切点")
        assertTrue(hasPoint(pts, 200f, 155f, tol = 0.5f), "右边的下切点")
        // 四条边都顶到画布边界上（图形的外接矩形 = 画布）
        assertEquals(0f, pts.filterIndexed { i, _ -> i % 2 == 0 }.min(), 0.01f)
        assertEquals(0f, pts.filterIndexed { i, _ -> i % 2 == 1 }.min(), 0.01f)
        assertEquals(200f, pts.filterIndexed { i, _ -> i % 2 == 0 }.max(), 0.01f)
        assertEquals(200f, pts.filterIndexed { i, _ -> i % 2 == 1 }.max(), 0.01f)
        // 四个**角尖**都不该出现在轮廓上（那说明圆角没生效）
        assertTrue(!hasPoint(pts, 0f, 0f) && !hasPoint(pts, 200f, 0f), "角没被切")
    }

    @Test
    fun shapesAreOrderedFromSquareToRoundByHowMuchTheyCutTheCorner() {
        // 「离角有多近」是这三档能被用户分出来的量化依据。列表顺序（角上切得少 → 多）：
        //
        //   方形（0） < 圆角方形（角心离原点 13.18） < 超椭圆 n=4（15.91） < 正圆（29.29）
        //
        // ⚠️ 超椭圆在**角上**切得比圆角方形更多，这不是写反了：它的特征是
        // 「切角更深、但四条直边更长，且整条轮廓的曲率是连续的（没有切线突变）」——
        // 也正是 iOS 图标那种「方中带圆」的观感来源。两者视觉上确实不同（一个像
        // 「带圆角的方」，一个像「被压圆的方」），所以两个选项都留着。
        val square = minCornerSum(IconShape.ORIGINAL)
        val rounded = minCornerSum(IconShape.ROUNDED_SQUARE)
        val squircle = minCornerSum(IconShape.SQUIRCLE)
        val circle = minCornerSum(IconShape.CIRCLE)
        assertEquals(0f, square, 0.01f)
        assertTrue(rounded > square, "圆角方形该比方形切得多：$rounded vs $square")
        assertTrue(squircle > rounded, "超椭圆在角上切得比圆角方形更多：$squircle vs $rounded")
        assertTrue(circle > squircle, "正圆切得最多：$circle vs $squircle")
        // 正圆的最紧点在 (100−100/√2, 同) → 2 × 29.29 = 58.58
        assertEquals(58.58f, circle, 0.3f)
    }

    @Test
    fun squircleFollowsTheLameCurveExponent() {
        // t = 45° 时 x = h + h·(cos45°)^(2/4) = 100 + 100·0.8409 = 184.09
        // 指数写错（比如用了 2 而不是 4）这个数会变成 100 + 70.71 = 170.71，一眼可见
        val pts = IconShapes.outline(IconShape.SQUIRCLE, 200f)
        var best = Float.MAX_VALUE
        for (i in 0 until pts.size / 2) {
            val d = abs(pts[i * 2] - 184.09f) + abs(pts[i * 2 + 1] - 184.09f)
            best = minOf(best, d)
        }
        assertTrue(best < 2f, "对角线上没有落在超椭圆上的点（最近偏差 $best）")
        // 四条边的中点仍然贴满画布
        assertTrue(hasPoint(pts, 200f, 100f), "右中")
        assertTrue(hasPoint(pts, 100f, 0f), "上中")
    }

    @Test
    fun teardropHasThreeRoundCornersAndOneSharpCornerAtTheBottomRight() {
        // 三个角取满半径（= 正圆的一部分）、右下角半径 0 → **恰好**落在 (200,200)。
        // 尖角必须是一个确切的点（不是「大概在那个方向」），否则水滴看起来像被啃了一口
        val pts = IconShapes.outline(IconShape.TEARDROP, 200f)
        assertTrue(hasPoint(pts, 200f, 200f, tol = 0.01f), "右下角不是尖的")
        // 左上角是圆的：角心 (100,100)、半径 100 → 最近点 x + y = 2×(100−70.71) = 58.58
        var minSum = Float.MAX_VALUE
        for (i in 0 until pts.size / 2) {
            // 只取左上半区（水位线以下/右的是尖角那一段直边）
            if (pts[i * 2] > 100f || pts[i * 2 + 1] > 100f) continue
            minSum = minOf(minSum, pts[i * 2] + pts[i * 2 + 1])
        }
        assertEquals(58.58f, minSum, 0.5f)
        // 尖角那一侧是**直的**：右上圆弧的终点 (200,100) 与尖角 (200,200) 之间是一条竖直线
        assertTrue(hasPoint(pts, 200f, 100f, tol = 0.5f), "右上圆弧该在 (200,100) 收尾")
        assertTrue(!hasPoint(pts, 200f, 0f), "右上角不该是直角（那说明三圆角没生效）")
    }

    @Test
    fun originalIsTheFullSquare() {
        // 「不裁」的轮廓就是画布本身（四条边四个角），只有描边会用得上它
        val pts = IconShapes.outline(IconShape.ORIGINAL, 200f)
        for (corner in listOf(0f to 0f, 200f to 0f, 200f to 200f, 0f to 200f)) {
            assertTrue(hasPoint(pts, corner.first, corner.second, tol = 0.01f), "缺角 $corner")
        }
        // 直角方块：最紧点就是原点本身
        assertEquals(0f, minCornerSum(IconShape.ORIGINAL), 0.01f)
    }

    @Test
    fun outlineScalesWithTheRequestedSize() {
        // 轮廓是**比例无关**的：同一个形状在 100px 与 400px 的画布上只是缩放
        val small = IconShapes.outline(IconShape.ROUNDED_SQUARE, 100f)
        val big = IconShapes.outline(IconShape.ROUNDED_SQUARE, 400f)
        assertEquals(small.size, big.size)
        for (i in small.indices) assertEquals(small[i] * 4f, big[i], 0.01f, "第 $i 个坐标")
        // 非法尺寸（0 / 负 / NaN）不能算出 NaN 轮廓 —— 那会让 Path 直接抛异常，图标整项不画
        for (bad in listOf(0f, -5f, Float.NaN)) {
            val pts2 = IconShapes.outline(IconShape.CIRCLE, bad)
            assertTrue(pts2.all { it.isFinite() }, "size=$bad")
        }
    }

    @Test
    fun sampleCountIsHonouredWithinReason() {
        // 采样点数只要「够密、够画平滑轮廓」：调用方不能依赖精确个数，
        // 但也不许给一个退化成三角形的轮廓（圆角会变成折线）
        for (shape in listOf(IconShape.CIRCLE, IconShape.SQUIRCLE)) {
            assertEquals(32 * 2, IconShapes.outline(shape, 200f, samples = 32).size, "$shape")
            assertEquals(96 * 2, IconShapes.outline(shape, 200f).size, "$shape 默认值")
        }
        // 圆角/水滴按四分之一分摊：samples=8 时每段至少 2 步 → 4 角 × 3 点 = 12 个点（24 个数）
        val few = IconShapes.outline(IconShape.ROUNDED_SQUARE, 200f, samples = 8)
        assertEquals(12 * 2, few.size)
        // 极端小的 samples 被夹到 8，不能返回空数组（空轮廓 = 画不出图标）
        for (shape in IconShape.entries) {
            val pts = IconShapes.outline(shape, 200f, samples = 1)
            assertTrue(pts.size >= 8, "$shape 返回了退化轮廓：${pts.size}")
        }
    }

    @Test
    fun paramsMatchTheDocumentedConstants() {
        // 参数表本身也是契约：0.45（iOS 连续圆角的近似）、4.0（Lamé 指数）
        assertEquals(IconShapes.ROUNDED_SQUARE_CORNER, IconShapes.paramsOf(IconShape.ROUNDED_SQUARE).cornerFraction)
        assertEquals(IconShapes.SQUIRCLE_EXPONENT, IconShapes.paramsOf(IconShape.SQUIRCLE).exponent)
        assertEquals(1f, IconShapes.paramsOf(IconShape.CIRCLE).cornerFraction)
        assertEquals(0f, IconShapes.paramsOf(IconShape.ORIGINAL).cornerFraction)
        assertEquals(2f, IconShapes.paramsOf(IconShape.ORIGINAL).exponent)
        assertTrue(IconShapes.paramsOf(IconShape.TEARDROP).teardropPointsBottomRight)
        assertEquals(0.45f, IconShapes.ROUNDED_SQUARE_CORNER)
        assertEquals(4f, IconShapes.SQUIRCLE_EXPONENT)
    }

    // ── 用户可调数值（圆角 / 柔度）────────────────────────────────────────

    @Test
    fun unsetValuesResolveToEachShapesOwnDefaults() {
        // **回归防线**：这两个数值键是老配置里**根本不存在**的（读出来是 0），
        // 而「0 = 用形状自己的默认值」必须是**结构上**成立的 ——
        // 只要这条成立，没见过这两个滑块的老配置画出来的像素就与参数上线前一致。
        for (shape in IconShape.entries) {
            val base = IconShapes.paramsOf(shape)
            val resolved = IconShapes.resolveParams(shape, cornerPercent = 0f, exponent = 0f)
            assertSameParams(base, resolved, "$shape")
            // 没夹取、也没「无效」提示：老配置不该在日志里冒出任何一句警告
            assertEquals("", resolved.clampNote, "$shape 无配置时不该有提示")
            // 逐点一致的**可执行证据**：两条入口给出同一个数组（不是「应该一样」）
            assertTrue(
                IconShapes.outline(resolved, 200f).contentEquals(IconShapes.outline(shape, 200f)),
                "$shape 的两条采样入口结果不同",
            )
        }
        // 负数 / NaN / 无穷 也算「没配过」（配置文件可能被手改过），方向是安全的
        for (bad in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertSameParams(
                IconShapes.paramsOf(IconShape.ROUNDED_SQUARE),
                IconShapes.resolveParams(IconShape.ROUNDED_SQUARE, bad, bad),
                "corner=$bad",
            )
        }
    }

    @Test
    fun explicitValuesActuallyChangeTheOutline() {
        // 「拖了滑杆预览不动」是这次要修的毛病之一，所以每个数值都要有一条**能看出差别**的断言
        val default = minCornerSum(IconShape.ROUNDED_SQUARE)
        val sharp = IconShapes.resolveParams(IconShape.ROUNDED_SQUARE, cornerPercent = 20f, exponent = 0f)
        // 圆角 20% → 半径 = 半边长 × 0.2 = 20px，角心 (20,20) 离原点 20·(1−1/√2) = 5.86 → x+y = 11.72
        val sharpSum = minCornerSum(sharp)
        assertEquals(11.72f, sharpSum, 0.4f)
        assertTrue(sharpSum < default, "圆角调小该更方：$sharpSum vs $default")
        // 圆角拉满 = 正圆（半径 = 半边长）
        val round = IconShapes.resolveParams(IconShape.ROUNDED_SQUARE, cornerPercent = 100f, exponent = 0f)
        assertEquals(58.58f, minCornerSum(round), 0.3f)

        // 柔度：超椭圆指数 8 比 4 更方（对角线上的点更靠外）
        fun diagMax(exp: Float): Float {
            val pts = IconShapes.outline(
                IconShapes.resolveParams(IconShape.SQUIRCLE, 0f, exp),
                200f,
            )
            var m = 0f
            for (i in 0 until pts.size / 2) m = maxOf(m, minOf(pts[i * 2], pts[i * 2 + 1]))
            return m
        }
        assertTrue(diagMax(8f) > diagMax(4f), "指数越大越接近方形（对角线更靠外）")
        assertTrue(diagMax(2f) < diagMax(4f))

        // 水滴：三个圆角共用同一个可调半径（缺省 1.0 = 上一版写死的满半径），
        // 尖角在右下**不跟着变**
        val tear = IconShapes.outline(
            IconShapes.resolveParams(IconShape.TEARDROP, cornerPercent = 50f, exponent = 0f),
            200f,
        )
        assertTrue(hasPoint(tear, 200f, 200f, tol = 0.01f), "尖角必须还在 (200,200)")
        var tearSum = Float.MAX_VALUE
        for (i in 0 until tear.size / 2) {
            if (tear[i * 2] > 100f || tear[i * 2 + 1] > 100f) continue
            tearSum = minOf(tearSum, tear[i * 2] + tear[i * 2 + 1])
        }
        assertTrue(tearSum < 58.58f, "圆角调小之后左上角该更尖（离角更近）：$tearSum")
        // 半径 50（100 × 50%）→ 角心 (50,50) 沿对角线退 50：2×(50−35.36) = 29.29
        assertEquals(29.29f, tearSum, 0.3f)
    }

    @Test
    fun outOfRangeValuesAreClampedAndExplained() {
        // 越界必须**夹取 + 留痕**：静默夹取会让「拖到底没反应」查不出来
        val corner = IconShapes.resolveParams(IconShape.ROUNDED_SQUARE, cornerPercent = 180f, exponent = 0f)
        assertEquals(IconShapes.CORNER_FRACTION_MAX, corner.cornerFraction, 0f)
        assertTrue(corner.clampNote.contains("180"), corner.clampNote)
        assertTrue(corner.clampNote.contains("越界"), corner.clampNote)

        // 柔度的下限是 2（< 2 的那一族是**凹**的星形，属于另一个形状家族）
        val low = IconShapes.resolveParams(IconShape.SQUIRCLE, 0f, exponent = 1.5f)
        assertEquals(IconShapes.EXPONENT_MIN, low.exponent, 0f)
        assertTrue(low.clampNote.contains("1.5"), low.clampNote)
        // 上限 10
        val high = IconShapes.resolveParams(IconShape.SQUIRCLE, 0f, exponent = 99f)
        assertEquals(IconShapes.EXPONENT_MAX, high.exponent, 0f)
        assertTrue(high.clampNote.contains("99"), high.clampNote)
        // 界内的值**不留痕**（否则日志里会天天刷一句没意义的提示）
        assertEquals("", IconShapes.resolveParams(IconShape.SQUIRCLE, 0f, exponent = 6f).clampNote)
        assertEquals("", IconShapes.resolveParams(IconShape.ROUNDED_SQUARE, 30f, 0f).clampNote)
    }

    @Test
    fun valuesForShapesThatIgnoreThemAreReportedInsteadOfSilentlyIgnored() {
        // 正圆与超椭圆的轮廓**用不到圆角数值**（一个半径恒定、一个由指数决定）。
        // 用户把它们拖到 30% 时形状不会变 —— 那必须留一条提示，否则就是
        // 「拖到底没反应」的另一个来源
        for (shape in listOf(IconShape.CIRCLE, IconShape.SQUIRCLE)) {
            val r = IconShapes.resolveParams(shape, cornerPercent = 30f, exponent = 0f)
            assertTrue(r.clampNote.isNotEmpty(), "$shape 该报「圆角无效」")
            assertEquals(IconShapes.paramsOf(shape).cornerFraction, r.cornerFraction, 0f, "$shape 参数不变")
        }
        // 反之，对不用指数的形状也一样
        for (shape in listOf(IconShape.CIRCLE, IconShape.ROUNDED_SQUARE, IconShape.ORIGINAL)) {
            val r = IconShapes.resolveParams(shape, 0f, exponent = 6f)
            assertTrue(r.clampNote.isNotEmpty(), "$shape 该报「柔度无效」")
            assertEquals(IconShapes.paramsOf(shape).exponent, r.exponent, 0f, "$shape 参数不变")
        }
        // 形状声明了圆角才用得到 → `original`（真方角）是**用得到**的那个，别误报
        assertEquals("", IconShapes.resolveParams(IconShape.ORIGINAL, cornerPercent = 30f, 0f).clampNote)
    }

    /** 逐字段比较（[IconShapeParams] 不是 data class，没有 equals）。 */
    private fun assertSameParams(expected: IconShapeParams, actual: IconShapeParams, msg: String) {
        assertEquals(expected.shape, actual.shape, msg)
        assertEquals(expected.cornerFraction, actual.cornerFraction, 0f, msg)
        assertEquals(expected.exponent, actual.exponent, 0f, msg)
        assertEquals(expected.teardropPointsBottomRight, actual.teardropPointsBottomRight, msg)
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun hasPoint(pts: FloatArray, x: Float, y: Float, tol: Float = 0.01f): Boolean {
        for (i in 0 until pts.size / 2) {
            if (abs(pts[i * 2] - x) <= tol && abs(pts[i * 2 + 1] - y) <= tol) return true
        }
        return false
    }

    /** 轮廓上「离某个角最近」的那个点的到角距离（用 x+y 表示左上角）。 */
    private fun minCornerSum(shape: IconShape): Float =
        minCornerSum(IconShapes.paramsOf(shape))

    /** 同上，按**显式参数**采样（用户调过数值的那条路）。 */
    private fun minCornerSum(params: IconShapeParams): Float {
        val pts = IconShapes.outline(params, 200f)
        var min = Float.MAX_VALUE
        for (i in 0 until pts.size / 2) min = minOf(min, pts[i * 2] + pts[i * 2 + 1])
        return min
    }

    @Test
    fun sampledPointsAreOrderedClockwiseInScreenCoordinates() {
        // 点序是顺时针（y 朝下）：从右上角出发的圆，第一个点在右下 → 角度递增方向为屏幕顺时针。
        // 顺序反了在实心填充上看不出来，但描边与「沿轮廓做动画」时会出现奇怪的接缝
        val pts = IconShapes.outline(IconShape.CIRCLE, 200f)
        val first = pts[0] to pts[1]
        val second = pts[2] to pts[3]
        assertTrue(first.first > 100f, "第 0 点该在右半边：$first")
        assertTrue(second.second > first.second, "第二个点该往下走：$second vs $first")
        // 采样点的角度必须是单调递增的（±2π 的绕圈）
        var prev = Math.atan2((first.second - 100f).toDouble(), (first.first - 100f).toDouble())
        var turns = 0.0
        for (i in 1 until pts.size / 2) {
            val a = Math.atan2((pts[i * 2 + 1] - 100f).toDouble(), (pts[i * 2] - 100f).toDouble())
            var d = a - prev
            while (d < -Math.PI) {
                d += 2 * Math.PI
                turns += 1
            }
            while (d > Math.PI) d -= 2 * Math.PI
            assertTrue(d > 0, "第 $i 点的角度不是单调递增的")
            prev = a
        }
        assertEquals(1.0, turns, 1e-9)
    }
}
