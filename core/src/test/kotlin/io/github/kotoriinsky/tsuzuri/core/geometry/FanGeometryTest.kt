package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 扇形**纯几何**（`core/.../geometry/FanGeometry.kt`）的分层、角度、圆心。
 *
 * ## 为什么这些数必须钉住
 *
 * 这一层是从 hook 的 `FanGeometry.kt` 搬过来的，搬的时候要求「行为逐字不变」——
 * 而「逐字不变」不能靠读代码确认（那些公式里有不动点、有 acos 反解），
 * 只能靠**把关键性质写成断言**：
 *
 *  - 分层：`n` 项分几层、每层几项（智能模式与自定义模式两套规则）；
 *  - 半径：`ρ_i = a·R + 层序号 × 行距`（内层 0.68R 是抄来的，行距是偏离参照的那一处）；
 *  - 角度：**端点落在同一条竖直线上**（用户要的「垂直边缘对齐」）与错开量；
 *  - 圆心：`place` 与 `angleDeg` / `rowRadius` 必须自洽（绘制与命中共用这一份，
 *    一旦两者算的不是同一次，屏幕上就会出现「看到的图标」和「碰到的图标」错位）。
 *
 * 数值一律带容差：这些都是浮点 + 三角函数，逐位相等是没有意义的断言。
 */
class FanGeometryTest {

    // ── 分层 ──────────────────────────────────────────────────────────────

    @Test
    fun smartModeRoundsCountUpToWholeRows() {
        // 上游公式：层数 = max(1, min(3, (n+7)/8)) → 1–8 项 1 层、9–16 项 2 层、17–24 项 3 层
        assertEquals(1, FanArcs.plan(1, "smart", 7, 8).rows.size)
        assertEquals(1, FanArcs.plan(8, "smart", 7, 8).rows.size)
        assertEquals(2, FanArcs.plan(9, "smart", 7, 8).rows.size)
        assertEquals(2, FanArcs.plan(16, "smart", 7, 8).rows.size)
        assertEquals(3, FanArcs.plan(17, "smart", 7, 8).rows.size)
        assertEquals(3, FanArcs.plan(24, "smart", 7, 8).rows.size)
        // 0 项也走「至少一层一项」：几何不能因为一次空清单就退化成除零
        assertEquals(1, FanArcs.plan(0, "smart", 7, 8).rows.size)
        assertEquals(1, FanArcs.plan(0, "smart", 7, 8).rows[0].count)
    }

    @Test
    fun smartModeSpreadRemainderFromTheLastRowBackwards() {
        // 余数从**最后一层往前**各 +1（`fanRowCounts`）：17 项 → base 5、余 2 → 5/6/6
        val plan = FanArcs.plan(17, "smart", 7, 8)
        assertEquals(listOf(5, 6, 6), plan.rows.map { it.count })
        // 每层的 radialIndex 就是它的下标（半径阶梯与奇偶错开都由它推）
        assertEquals(listOf(0, 1, 2), plan.rows.map { it.radialIndex })
        // 24 项 = 三排各 8 项（也是 fan_max_targets 的上限）
        assertEquals(listOf(8, 8, 8), FanArcs.plan(24, "smart", 7, 8).rows.map { it.count })
        // 10 项 → 2 层、base 5、余 0
        assertEquals(listOf(5, 5), FanArcs.plan(10, "smart", 7, 8).rows.map { it.count })
        // 9 项 → 2 层、base 4、余 1 → 第二层多一个
        assertEquals(listOf(4, 5), FanArcs.plan(9, "smart", 7, 8).rows.map { it.count })
    }

    @Test
    fun smartModeNeverLosesOrInventsASlot() {
        // 分层的唯一硬不变量：各层之和恒等于项数（少一项 = 用户选的应用在扇子里消失了）
        for (n in 1..24) {
            val plan = FanArcs.plan(n, "smart", 7, 8)
            assertEquals(n, plan.rows.sumOf { it.count }, "n=$n")
            assertEquals(n, plan.rows.sumOf { it.count }, "n=$n 的层数=${plan.rows.size}")
            assertTrue(plan.rows.all { it.count > 0 }, "n=$n 不该出现空弧")
            assertTrue(plan.rows.map { it.radialIndex } == plan.rows.indices.toList(), "n=$n")
            assertTrue(plan.rows.size in 1..FanArcs.MAX_ROWS, "n=$n")
        }
    }

    @Test
    fun customModeFillsInnerThenMiddleAndPutsTheRestOutside() {
        // 容量按内 → 中 → 外依次填满，余量全塞外排（上游 `fanRows` 的 mode==2 分支）
        val plan = FanArcs.plan(17, FanArcs.LAYOUT_CUSTOM, 7, 8)
        assertTrue(plan.custom)
        assertEquals(listOf(7, 8, 2), plan.rows.map { it.count })
        assertEquals(listOf(0, 1, 2), plan.rows.map { it.radialIndex })
    }

    @Test
    fun customModeDropsEmptyRowsButKeepsRadialIndex() {
        // 内排容量 0 → 那一层**不出现**（不画空弧），但后面的层不因此前移：
        // radialIndex 必须还是 1、2，否则用户拖「内排」时外两层的半径会跟着跳
        val plan = FanArcs.plan(17, FanArcs.LAYOUT_CUSTOM, 0, 0)
        assertEquals(listOf(3, 14), plan.rows.map { it.count })
        assertEquals(listOf(1, 2), plan.rows.map { it.radialIndex })
        // 三层都配 0 时也不会一项不画：余量全在外排
        val allZero = FanArcs.plan(9, FanArcs.LAYOUT_CUSTOM, 0, 0)
        assertEquals(9, allZero.rows.sumOf { it.count })
    }

    @Test
    fun customModeNeverExceedsThePerRowCapacity() {
        // 内 ≤ 8、中 ≤ 10、外 ≤ 14（24 = 10 + 14 是内排下限的来源），且三排之和恒等于项数。
        //
        // ⚠️ 上限只在 total ≤ 8 + 10 + 14 = 32 时**三者同时**成立：再多的项数在设计上就装不下，
        // 外排（它是减出来的）会越过 14。这不是 bug —— `fan_max_targets` 的硬上限就是 24，
        // 所以真机上到不了那一档；写在这里是为了让读的人知道这条边界的出处。
        for (total in 1..32) {
            val plan = FanArcs.plan(total, FanArcs.LAYOUT_CUSTOM, 8, 10)
            val triple = IntArray(3)
            for (row in plan.rows) triple[row.radialIndex] = row.count
            assertEquals(total, triple.sum(), "total=$total")
            assertTrue(triple[0] <= FanRowAllocation.MAX_INNER, "内排超了：$total")
            assertTrue(triple[1] <= FanRowAllocation.MAX_MIDDLE, "中排超了：$total")
            assertTrue(triple[2] <= FanRowAllocation.MAX_OUTER, "外排超了：$total")
        }
        // 24 项 + 用户配 8/10 → 正好 8/10/6
        val p = FanArcs.plan(24, FanArcs.LAYOUT_CUSTOM, 8, 10)
        assertEquals(listOf(8, 10, 6), p.rows.map { it.count })
    }

    @Test
    fun modeStringOtherThanCustomFallsBackToSmart() {
        // 存的是字符串：旧版本写过别的取值、或用户手改配置时，**不能**掉进自定义分支
        val weird = FanArcs.plan(17, "SMART", 7, 8)
        assertEquals(false, weird.custom)
        assertEquals(listOf(5, 6, 6), weird.rows.map { it.count })
        val empty = FanArcs.plan(17, "", 0, 0)
        assertEquals(false, empty.custom)
    }

    // ── 容量的联动夹取 ────────────────────────────────────────────────────

    @Test
    fun normalizeKeepsTheSumEqualToTotal() {
        // 三条不变量里最要紧的一条：三排之和恒等于 total（外排是减出来的，不参与夹取）。
        // 项数上界取 25（= 内排下限开始生效的第一个值）；再往上外排会越过 14，
        // 见 customModeNeverExceedsThePerRowCapacity 的说明。
        for (total in listOf(0, 1, 3, 8, 9, 17, 24, 25)) {
            for (inner in listOf(-5, 0, 3, 8, 20)) {
                for (middle in listOf(-5, 0, 5, 10, 100)) {
                    val a = FanRowAllocation.normalize(total, inner, middle)
                    assertEquals(3, a.size)
                    assertEquals(maxOf(0, total), a.sum(), "total=$total inner=$inner middle=$middle")
                    assertTrue(a.all { it >= 0 }, "total=$total inner=$inner middle=$middle")
                    assertTrue(a[0] <= FanRowAllocation.MAX_INNER)
                    assertTrue(a[1] <= FanRowAllocation.MAX_MIDDLE)
                    assertTrue(a[2] <= FanRowAllocation.MAX_OUTER)
                }
            }
        }
    }

    @Test
    fun normalizeClampsInnerAndMiddleAtTheirEdges() {
        // 用户配的是**上限**：项数一变就被重新夹一次
        assertEquals(listOf(0, 0, 0).toIntArray().toList(), FanRowAllocation.normalize(0, 7, 8).toList())
        // 24 项、内 7 中 8 → 外 9
        assertEquals(listOf(7, 8, 9), FanRowAllocation.normalize(24, 7, 8).toList())
        // 内排超上限 → 夹到 8，剩下的重新分
        assertEquals(listOf(8, 8, 8), FanRowAllocation.normalize(24, 99, 8).toList())
        // 中排超上限 → 夹到 10
        assertEquals(listOf(8, 10, 6), FanRowAllocation.normalize(24, 8, 99).toList())
        // 小项数：内排被 total 夹住
        assertEquals(listOf(3, 0, 0), FanRowAllocation.normalize(3, 7, 8).toList())
        // 负值一律当 0（配置被手改成负数也不能算出负容量）
        assertEquals(listOf(0, 3, 14), FanRowAllocation.normalize(17, -3, -3).toList())
    }

    @Test
    fun normalizeBoundsFollowTheRowCaps() {
        // 内排的下限 = total − 中上限 − 外上限（24 是内排下限 0 的来源：10 + 14）
        assertEquals(0, FanRowAllocation.minimumInner(24))
        assertEquals(1, FanRowAllocation.minimumInner(25))
        assertEquals(3, FanRowAllocation.maximumInner(3)) // 内排不能超过总项数
        assertEquals(8, FanRowAllocation.maximumInner(30))
        // 中排同理：它的下限与上限按「扣掉内排之后剩下的」算
        assertEquals(0, FanRowAllocation.minimumMiddle(14))
        assertEquals(1, FanRowAllocation.minimumMiddle(15))
        assertEquals(10, FanRowAllocation.maximumMiddle(14))
        assertEquals(3, FanRowAllocation.maximumMiddle(3))
        // 负输入不能把边界算成负的
        assertEquals(0, FanRowAllocation.minimumInner(-5))
        assertEquals(0, FanRowAllocation.minimumMiddle(-5))
    }

    // ── 半径阶梯与错开 ───────────────────────────────────────────────────

    @Test
    fun smartSingleRowUsesTheFullRadiusButCustomDoesNot() {
        // 上游的 `custom || rows.length > 1`：智能模式单排不乘 0.68，自定义模式**即使单排也乘**
        val smart1 = FanArcs.plan(3, "smart", 7, 8)
        assertEquals(1f, FanArcs.rowScale(smart1, smart1.rows[0]))
        val custom1 = FanArcs.plan(3, FanArcs.LAYOUT_CUSTOM, 7, 8)
        assertEquals(1, custom1.rows.size)
        assertEquals(FanArcs.INNER_FACTOR, FanArcs.rowScale(custom1, custom1.rows[0]))
    }

    @Test
    fun multiRowAlwaysUsesTheInnerFactor() {
        val smart2 = FanArcs.plan(10, "smart", 7, 8)
        assertEquals(2, smart2.rows.size)
        assertTrue(smart2.rows.all { FanArcs.rowScale(smart2, it) == FanArcs.INNER_FACTOR })
    }

    @Test
    fun onlyOddRowsAreStaggeredAndOnlyWhenThereAreLayers() {
        // 奇偶规则抄参照的 `(radialIndex & 1) != 1 ? 0 : inset`：三层时是「对齐 / 半格 / 对齐」
        val three = FanArcs.plan(17, "smart", 7, 8)
        assertEquals(
            listOf(false, true, false),
            three.rows.map { FanArcs.staggered(three, it) },
        )
        val one = FanArcs.plan(5, "smart", 7, 8)
        assertEquals(listOf(false), one.rows.map { FanArcs.staggered(one, it) })
        val two = FanArcs.plan(10, "smart", 7, 8)
        assertEquals(listOf(false, true), two.rows.map { FanArcs.staggered(two, it) })
    }

    @Test
    fun rowStepIsAFunctionOfTheConfiguredIconDiameterOnly() {
        // 行距 = 配置图标直径 × 1.15（**偏离参照**的那一处：0.2R 在本机是图标直径的 1.93 倍）
        assertEquals(110f * FanArcs.ROW_STEP_FACTOR, FanArcs.rowStep(110f))
        assertEquals(0f, FanArcs.rowStep(0f))
    }

    // ── 每层的半径与角度 ─────────────────────────────────────────────────

    @Test
    fun rowRadiusIsScaleTimesRadiusPlusRowIndexTimesRowStep() {
        // ρ_i = a·R + 层序号 × 行距。层距是**常数项**（跟图标走），不是半径的比例 ——
        // 这一条是「行距与半径脱钩」的全部含义，写成断言免得以后被改回比例
        val plan = FanArcs.plan(17, "smart", 7, 8) // 5/6/6
        val m = FanArcs.metrics(plan, radius = 1000f, rowStep = 100f, stagger = 0.5f)
        assertEquals(3, m.count)
        assertEquals(680f, m.rowRadius[0], 0.01f) // 0.68 × 1000
        assertEquals(780f, m.rowRadius[1], 0.01f) // + 1 × 100
        assertEquals(880f, m.rowRadius[2], 0.01f) // + 2 × 100
        assertEquals(FanArcs.INNER_FACTOR, m.scale[0], 1e-6f)
        assertEquals(0f, m.offset[0], 1e-6f)
        assertEquals(100f, m.offset[1], 1e-6f)
        assertEquals(200f, m.offset[2], 1e-6f)
    }

    @Test
    fun singleSlotRowIsPinnedToTheMiddleOfTheFan() {
        // 一项不成弧，「端点」就是它自己 → 放扇子正中 47°（参照 `:139` 的 47.0d）
        val plan = FanArcs.plan(1, FanArcs.LAYOUT_CUSTOM, 1, 0)
        assertEquals(1, plan.rows.size)
        val m = FanArcs.metrics(plan, 500f, 100f, 0f)
        assertEquals(FanArcs.SINGLE_SLOT_DEG, m.startDeg(0), 1e-9)
        assertEquals(FanArcs.SINGLE_SLOT_DEG, m.endDeg(0), 1e-9)
        assertEquals(FanArcs.SINGLE_SLOT_DEG, m.angleDeg(0, 0), 1e-9)
        assertEquals(0.0, m.stepRadians(0), 1e-12) // 没有相邻项 → 不构成间距约束
    }

    @Test
    fun everySlotRowEndsOnTheSharedVerticalLine() {
        // 「垂直边缘对齐」是用户明确要的：各层**止角**推出端点 x 相同的角，
        // 即 ρ_i·cos(end_i) = ρ_0·cos(82°)。这一条是那件事的可复核判据。
        val plan = FanArcs.plan(24, "smart", 7, 8) // 8/8/8，三层
        val m = FanArcs.metrics(plan, 1097f, 127f, 0.5f)
        val base = m.rowRadius[0] * cos(Math.toRadians(FanArcs.END_DEG))
        assertEquals(base.toFloat(), m.endLine, 0.01f)
        for (i in 0 until m.count) {
            val x = m.rowRadius[i] * cos(Math.toRadians(m.endDeg(i)))
            assertEquals(m.endLine, x.toFloat(), 0.05f, "第 $i 层的端点 x 没落在竖直线上")
        }
        // 基准层就是 82°（它的 ratio 恒为 1）
        assertEquals(FanArcs.END_DEG, m.endDeg(0), 1e-9)
        // 外层半径更大 → 必须跨更大的角（ρ·cosθ 里 ρ 大，要保持同一个 x 就得让 cosθ 更小）
        assertTrue(m.endDeg(2) > m.endDeg(0), "外层的止角该比基准层大")
    }

    @Test
    fun staggerShiftsTheStartAngleByHalfASlotAndKeepsTheEnd() {
        // 错开只体现在**层内部**：端点仍然对齐，所以「四个角对齐、中间砖砌」
        val plan = FanArcs.plan(17, "smart", 7, 8) // 5/6/6，中层 radialIndex=1 → 错开
        val aligned = FanArcs.metrics(plan, 800f, 120f, 0f)
        val half = FanArcs.metrics(plan, 800f, 120f, 0.5f)
        // 错开为 0 时每层都从 12° 起
        for (i in 0 until aligned.count) assertEquals(FanArcs.START_DEG, aligned.startDeg(i), 1e-9)
        // 错开半格时只有奇数层（中层）被推后半格，其余层不动
        assertEquals(FanArcs.START_DEG, half.startDeg(0), 1e-9)
        assertTrue(half.startDeg(1) > FanArcs.START_DEG, "中层该被推开")
        assertEquals(FanArcs.START_DEG, half.startDeg(2), 1e-9)
        // 止角不受错开影响（它由对齐规则定，不由错开定）
        for (i in 0 until half.count) assertEquals(aligned.endDeg(i), half.endDeg(i), 1e-6, "第 $i 层")
        // 被推开的那一层，位移正好是它自己步进的 0.5 倍
        val stepDeg = Math.toDegrees(half.stepRadians(1))
        assertEquals(FanArcs.START_DEG + 0.5 * stepDeg, half.startDeg(1), 1e-6)
    }

    @Test
    fun angleDegInterpolatesBetweenStartAndEnd() {
        val plan = FanArcs.plan(3, "smart", 7, 8) // 单层 3 项 → 12°–82°，步进 35°
        val m = FanArcs.metrics(plan, 600f, 100f, 0f)
        assertEquals(12.0, m.angleDeg(0, 0), 1e-6)
        assertEquals(47.0, m.angleDeg(0, 1), 1e-6)
        assertEquals(82.0, m.angleDeg(0, 2), 1e-6)
        // 越界的 slot 被夹到端点，不抛异常（命中路径上不允许有异常）
        assertEquals(12.0, m.angleDeg(0, -5), 1e-6)
        assertEquals(82.0, m.angleDeg(0, 99), 1e-6)
    }

    // ── 圆心 ─────────────────────────────────────────────────────────────

    @Test
    fun placeWritesAllCentersConsistentlyWithAngleAndRadius() {
        // place 与 angleDeg / rowRadius 必须是**同一次算的**：绘制与命中都读它，
        // 一旦两者错位就会出现「看到的图标」和「碰到的图标」对不上
        val plan = FanArcs.plan(17, FanArcs.LAYOUT_CUSTOM, 7, 8) // 7/8/2
        val m = FanArcs.metrics(plan, 900f, 127f, 0.5f)
        val total = plan.rows.sumOf { it.count }
        val out = FloatArray(total * 2)
        for (i in 0 until total) m.place(i, out)

        // 手工按层累加算出每一项落在哪一层、哪个槽，再和 place 的结果对照
        var index = 0
        for (rowIndex in plan.rows.indices) {
            val row = plan.rows[rowIndex]
            for (slot in 0 until row.count) {
                val deg = m.angleDeg(rowIndex, slot)
                val r = m.rowRadius[rowIndex]
                assertEquals((r * cos(Math.toRadians(deg))).toFloat(), out[index * 2], 0.01f, "index=$index x")
                assertEquals((r * sin(Math.toRadians(deg))).toFloat(), out[index * 2 + 1], 0.01f, "index=$index y")
                index++
            }
        }
        assertEquals(total, index)
    }

    @Test
    fun placeStaysInsideTheLocalQuadrantOfTheCorner() {
        // 本地坐标的语义：x 朝屏内为正、y 朝上为正，所以扇形永远落在弧心的**右上**方
        // （贴右时由调用方镜像 x，不是这里的事）
        val plan = FanArcs.plan(24, "smart", 7, 8)
        val m = FanArcs.metrics(plan, 1000f, 120f, 0.5f)
        val out = FloatArray(24 * 2)
        for (i in 0 until 24) m.place(i, out)
        for (i in 0 until 24) {
            assertTrue(out[i * 2] > 0f, "index=$i 的 x 必须为正（朝屏内）")
            assertTrue(out[i * 2 + 1] > 0f, "index=$i 的 y 必须为正（朝上）")
            assertTrue(
                out[i * 2] <= m.rowRadius.last() + 0.01f && out[i * 2 + 1] <= m.rowRadius.last() + 0.01f,
                "index=$i 跑出最外层半径之外",
            )
        }
    }

    @Test
    fun placeDoesNotTouchMemoryOutsideTheSlotArray() {
        // 顶点数组的尺寸 = 槽位 × 2，place 只许写自己那两项（写越界在真机上是内存踩踏）
        val plan = FanArcs.plan(9, "smart", 7, 8) // 4/5
        val m = FanArcs.metrics(plan, 500f, 100f, 0.5f)
        val total = plan.rows.sumOf { it.count }
        val out = FloatArray(total * 2) { -1f }
        for (i in 0 until total) m.place(i, out)
        assertTrue(out.none { it == -1f }, "有槽位没被写到")
    }

    // ── 两条屏内约束（上限 / 下限）的基本方向 ─────────────────────────────

    @Test
    fun screenCapShrinksWhenTheCircleNeedsMoreRoomOrTheScreenGetsTighter() {
        // 上限是「整圆在屏内」允许的最大半径。两条性质（不必比较横竖屏：宽高比一变，
        // **两个轴**里谁更紧会跟着变，所以「窄屏上限一定更小」并不成立）：
        //
        //  1. 图标越大（keepOut 越大）→ 上限越小；
        //  2. 屏幕整体放大 → 上限跟着放大。
        val plan = FanArcs.plan(17, "smart", 7, 8)
        val m = FanArcs.metrics(plan, 800f, 120f, 0.5f)
        val smallIcon = FanArcs.effectiveRadiusCap(m, 1220f, 2656f, 32.5f, keepOut = 100f)
        val bigIcon = FanArcs.effectiveRadiusCap(m, 1220f, 2656f, 32.5f, keepOut = 400f)
        assertTrue(bigIcon.radius < smallIcon.radius, "大图标的上限必须更小")
        val biggerScreen = FanArcs.effectiveRadiusCap(m, 2440f, 5312f, 32.5f, keepOut = 100f)
        assertTrue(biggerScreen.radius > smallIcon.radius, "屏幕更大时上限该更大")
        // 必须能报出「是哪一层的哪个轴顶的」：只说「上限生效了」没法复核
        assertTrue(smallIcon.rowIndex in 0 until m.count, "rowIndex=${smallIcon.rowIndex}")
        assertTrue(smallIcon.angleDeg > 0.0)
    }

    @Test
    fun screenOverflowIsZeroForTheSolvedGeometry() {
        // 独立核验：换一套公式（逐项算屏幕坐标、四条边）再问一次「整圆在屏内吗」。
        // 正常时恒为 0 —— 这里拿求解器给出的最终几何来验，等于把「屏内」这条硬保证钉住
        val input = FanSolveInput(
            screenW = 1220f,
            screenH = 2656f,
            density = 3.25f,
            radiusPercent = 30f,
            iconDp = 34f,
            layoutMode = "smart",
            customInner = 7,
            customMiddle = 8,
            edgeMarginDp = 10f,
            staggerSlots = 0.5f,
            selectionScalePercent = 122f,
            count = 24,
        )
        val s = FanSolve.solve(input)
        val overflow = FanArcs.screenOverflow(s.metrics, 1220f, 2656f, s.edgeMarginPx, s.keepOut)
        assertEquals(0f, overflow, 0.5f, "解出来的几何有项跑到屏幕外了")
        // 上限与下限必须把最终半径夹住（这正是「屏内」那两个方向）
        assertTrue(s.capRadius >= s.radius - 0.5f, "半径超过上限 = 有项出屏")
        assertTrue(s.floorRadius <= s.radius + 0.5f, "半径低于下限 = 有项贴边")
        assertTrue(s.iconDiameter >= s.minIconDiameter - 0.01f, "图标被压到 28dp 以下")
        assertTrue(s.iconDiameter <= s.configuredDiameter + 0.01f, "图标不该被放大")
        assertEquals(s.iconDiameter / 2f + s.gap, s.hitRadius, 1e-3f)
    }

    @Test
    fun bigIconOnANarrowScreenFallsBackToShrinkingTheIcon() {
        // 降级链的第二级：屏内把半径压到「放不下配置图标」时，图标**接力缩小**。
        // 96dp 图标 + 24 项是本机实测到会触发的那一档（见 effectiveIconDiameter 的要点 3）
        val input = FanSolveInput(
            screenW = 1220f,
            screenH = 2656f,
            density = 3.25f,
            radiusPercent = 150f,
            iconDp = 96f,
            layoutMode = "smart",
            customInner = 7,
            customMiddle = 8,
            edgeMarginDp = 10f,
            staggerSlots = 0.5f,
            selectionScalePercent = 140f,
            count = 24,
        )
        val s = FanSolve.solve(input)
        assertTrue(s.configuredDiameter > s.iconDiameter, "这一档应该触发缩图标：${s.fitDesc}")
        assertTrue(s.fitDesc.startsWith("fit="), s.fitDesc)
        assertEquals(0f, FanArcs.screenOverflow(s.metrics, 1220f, 2656f, s.edgeMarginPx, s.keepOut), 0.5f)
    }

    @Test
    fun smallFanKeepsTheConfiguredRadiusAndIcon() {
        // 项数少、屏够大时不触发任何降级：半径 = 用户初值与不重叠里较大的那个
        val input = FanSolveInput(
            screenW = 1220f,
            screenH = 2656f,
            density = 3.25f,
            radiusPercent = 30f,
            iconDp = 34f,
            layoutMode = "smart",
            customInner = 7,
            customMiddle = 8,
            edgeMarginDp = 10f,
            staggerSlots = 0.5f,
            selectionScalePercent = 122f,
            count = 3,
        )
        val s = FanSolve.solve(input)
        assertEquals("fit=ok", s.fitDesc)
        assertEquals(366f, s.configuredRadius, 0.01f) // 短边 1220 × 30%
        assertTrue(s.radius >= s.configuredRadius - 0.01f)
        assertEquals(s.configuredDiameter, s.iconDiameter, 0.01f)
        assertEquals("plan=smart n=3", s.planDesc)
        // 单层单排 → 不乘 0.68
        assertEquals(s.radius, s.metrics.rowRadius[0], 0.01f)
    }

    @Test
    fun solutionPlaceMatchesTheMetricsItCarries() {
        // 求解产物里的 place 必须转发给同一份 metrics（不是另算一遍）
        val input = FanSolveInput(
            screenW = 1220f,
            screenH = 2656f,
            density = 3.25f,
            radiusPercent = 30f,
            iconDp = 34f,
            layoutMode = FanArcs.LAYOUT_CUSTOM,
            customInner = 7,
            customMiddle = 8,
            edgeMarginDp = 10f,
            staggerSlots = 0f,
            selectionScalePercent = 122f,
            count = 9,
        )
        val s = FanSolve.solve(input)
        val out = FloatArray(9 * 2)
        for (i in 0 until 9) s.place(i, out)
        val expect = FloatArray(9 * 2)
        for (i in 0 until 9) s.metrics.place(i, expect)
        assertTrue(out.contentEquals(expect))
        assertEquals(s.metrics.describe(), s.arcs)
        // 自定义模式 9 项 + 7/8 → 7/2（外排派生）
        assertEquals(listOf(7, 2), s.plan.rows.map { it.count })
    }

    @Test
    fun fmtUsesTheUsLocaleSoLogsCanBeRecomputed() {
        // 日志里的小数点必须是 `.`（否则复算的人要猜本地化格式）
        assertEquals("12.3", FanArcs.fmt(12.34))
        assertEquals("-0.5", FanArcs.fmt(-0.45))
        assertTrue(abs(FanArcs.HIT_TOLERANCE_DP - 6f) < 1e-6f)
        assertTrue(abs(FanArcs.MIN_ICON_DP - 28f) < 1e-6f)
        assertTrue(abs(FanArcs.RING_STROKE_DP - 3f) < 1e-6f)
    }
}
