package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 4 列图标网格：长按原地松手弹操作菜单，长按拖动直接排序（自绘，不进 [FpKit] 契约）。
 *
 * 手势状态机（放在 `verticalScroll` 内层，与滚动互不抢占）：
 *  1. 短按松手（< longPress & 移动 < touchSlop）→ 回调 [onTap]；
 *  2. 未到长按先移动 > touchSlop → 放手交给 `verticalScroll` 正常滚动；
 *  3. 按满长按阈值 → 轻震 + 格子微抬（`armed = true`），此后消费移动事件防止滚动抢占：
 *     - 手指在 [DragSlop] 内松手 → 回调 [onLongPressMenu]（由调用方弹出锚定菜单）；
 *     - 手指移出 [DragSlop] 且 [enabled] 为 true → 无缝转入 2D 拖拽排序，其余格子平滑让位，
 *       松手且落位变化时回调 [onMove]。
 *
 * 之所以在**松手**后才弹菜单，而不是长按瞬间就挂 `Popup`：
 * 挂出可聚焦的 `Popup` 窗口会向底层窗口派发 `ACTION_CANCEL`，从而打断「长按不松手直接拖」
 * 这条连贯手势。先靠缩放 + 描边给足「已拾起」的反馈，松手弹菜单、拖动即排序，两路零冲突。
 */
@Composable
fun ReorderGrid(
    keys: List<String>,
    sectionLabels: List<String?>,
    onMove: (from: Int, to: Int) -> Unit,
    onTap: (index: Int) -> Unit,
    onLongPressMenu: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    enabled: Boolean = true,
    trailingTile: (@Composable () -> Unit)? = null,
    cell: @Composable (index: Int, column: Int, lifted: Boolean, dragging: Boolean) -> Unit,
) {
    val kit = LocalFpKit.current
    val colors = kit.colors()
    val density = LocalDensity.current

    val cellHeightPx = with(density) { GridCellHeight.roundToPx() }
    val headerHeightPx = with(density) { GridHeaderHeight.roundToPx() }
    val sectionGapPx = with(density) { GridSectionGap.roundToPx() }
    val hPadPx = with(density) { GridHorizontalPad.roundToPx() }
    val vPadPx = with(density) { GridVerticalPad.roundToPx() }
    val dragSlopPx = with(density) { GridDragSlop.toPx() }
    val edgePx = with(density) { GridAutoScrollEdge.toPx() }
    val maxStepPx = with(density) { GridAutoScrollStep.toPx() }

    val scroll = rememberScrollState()

    /** 长按已触发、尚未开始拖动也尚未松手的项。 */
    var armedIndex by remember { mutableStateOf(-1) }
    /** 正在 2D 拖拽的项。 */
    var dragIndex by remember { mutableStateOf(-1) }
    /** 拖拽时的目标落位下标。 */
    var dropIndex by remember { mutableStateOf(-1) }
    /** 被拖格子的实时左上角坐标（内容坐标系）。 */
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var grabOffsetX by remember { mutableStateOf(0f) }
    var grabOffsetY by remember { mutableStateOf(0f) }

    val feedbackRef = rememberUpdatedState(fpRowFeedback())
    val onMoveRef = rememberUpdatedState(onMove)
    val onTapRef = rememberUpdatedState(onTap)
    val onLongPressMenuRef = rememberUpdatedState(onLongPressMenu)

    LaunchedEffect(keys) {
        armedIndex = -1
        dragIndex = -1
        dropIndex = -1
    }

    BoxWithConstraints(modifier) {
        val totalWidthPx = constraints.maxWidth
        val viewportPx = constraints.maxHeight
        val innerWidthPx = (totalWidthPx - hPadPx * 2).coerceAtLeast(columns)
        val cellWidthPx = innerWidthPx / columns
        val cellWidthDp = with(density) { cellWidthPx.toDp() }
        val cellHeightDp = GridCellHeight

        val layout = remember(
            keys.size,
            sectionLabels,
            cellWidthPx,
            cellHeightPx,
            headerHeightPx,
            sectionGapPx,
            hPadPx,
            vPadPx,
            columns,
            trailingTile != null,
        ) {
            computeGridLayout(
                count = keys.size,
                sectionLabels = sectionLabels,
                columns = columns,
                leftPad = hPadPx,
                topPad = vPadPx,
                bottomPad = vPadPx,
                cellWidth = cellWidthPx,
                cellHeight = cellHeightPx,
                headerHeight = headerHeightPx,
                sectionGap = sectionGapPx,
                hasTrailing = trailingTile != null,
            )
        }

        val geom = rememberUpdatedState(
            GridGeom(
                slots = layout.slots,
                cellWidth = cellWidthPx,
                cellHeight = cellHeightPx,
                viewportPx = viewportPx,
                scroll = scroll,
            ),
        )
        val edgeNow = rememberUpdatedState(edgePx)
        val stepNow = rememberUpdatedState(maxStepPx)

        val dragging = dragIndex in keys.indices
        val activeLiftIndex = when {
            dragging -> dragIndex
            armedIndex in keys.indices -> armedIndex
            else -> -1
        }
        val liftAnim = remember { Animatable(0f) }
        LaunchedEffect(activeLiftIndex >= 0) {
            liftAnim.animateTo(if (activeLiftIndex >= 0) 1f else 0f, GridLiftSpec)
        }
        val lift = liftAnim.value

        val totalHeightDp = with(density) { layout.totalHeight.toDp() }

        Box(Modifier.fillMaxSize().clipToBounds()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .height(totalHeightDp)
                    .pointerInput(keys, enabled) {
                        val touchSlop = viewConfiguration.touchSlop
                        val longPressMs = viewConfiguration.longPressTimeoutMillis

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val g0 = geom.value
                            val hit = g0.indexAt(down.position.x, down.position.y)
                            if (hit < 0) return@awaitEachGesture

                            val pointerId = down.id
                            val downPos = down.position
                            var tapped = false

                            val finishedBeforeLongPress = withTimeoutOrNull(longPressMs) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                    if (change.isConsumed) break
                                    if (!change.pressed) {
                                        if ((change.position - downPos).getDistance() < touchSlop) {
                                            tapped = true
                                        }
                                        break
                                    }
                                    if ((change.position - downPos).getDistance() > touchSlop) {
                                        break
                                    }
                                }
                                true
                            }

                            if (tapped) {
                                feedbackRef.value()
                                onTapRef.value(hit)
                                return@awaitEachGesture
                            }
                            if (finishedBeforeLongPress != null) return@awaitEachGesture

                            // 长按触发：先抬起格子，待松手或移出阈值分流到菜单 / 拖拽
                            armedIndex = hit
                            feedbackRef.value()
                            var startedDrag = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        if (startedDrag) {
                                            val d = dragIndex
                                            val t = dropIndex
                                            dragIndex = -1
                                            dropIndex = -1
                                            if (d >= 0 && t >= 0 && t != d) {
                                                onMoveRef.value(d, t)
                                                feedbackRef.value()
                                            }
                                        } else {
                                            armedIndex = -1
                                            onLongPressMenuRef.value(hit)
                                        }
                                        break
                                    }
                                    change.consume()

                                    if (!startedDrag) {
                                        if (enabled && (change.position - downPos).getDistance() >= dragSlopPx) {
                                            startedDrag = true
                                            armedIndex = -1
                                            val g = geom.value
                                            val slot = g.slots[hit]
                                            dragIndex = hit
                                            dropIndex = hit
                                            grabOffsetX = change.position.x - slot.x
                                            grabOffsetY = change.position.y - slot.y
                                            dragX = slot.x.toFloat()
                                            dragY = slot.y.toFloat()
                                        }
                                    } else {
                                        val g = geom.value
                                        dragX = change.position.x - grabOffsetX
                                        dragY = change.position.y - grabOffsetY
                                        val cx = dragX + g.cellWidth / 2f
                                        val cy = dragY + g.cellHeight / 2f
                                        val next = g.nearestSlot(cx, cy)
                                        if (next >= 0 && next != dropIndex) {
                                            dropIndex = next
                                            feedbackRef.value()
                                        }
                                        autoScrollGrid(change.position.y, g, edgeNow.value, stepNow.value)
                                    }
                                }
                            } finally {
                                armedIndex = -1
                                dragIndex = -1
                                dropIndex = -1
                            }
                        }
                    },
            ) {
                // 分段标题（内圈 / 中圈 / 外圈 / 第 N 页）
                for (hdr in layout.headers) {
                    Box(
                        Modifier
                            .offset { IntOffset(hPadPx, hdr.y) }
                            .height(GridHeaderHeight)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        kit.Body(hdr.label, FpEmphasis.MUTED, FpTextStyle.CAPTION)
                    }
                }

                // 拖拽时的落位占位：中性浅灰圆角块（MiuiX 克制风格，不画彩色边框）
                if (dragging && dropIndex in layout.slots.indices) {
                    val targetPos = layout.slots[dropIndex]
                    Box(
                        Modifier
                            .offset { targetPos }
                            .size(cellWidthDp, cellHeightDp)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .background(colors.surfaceVariant.copy(alpha = 0.65f), RoundedCornerShape(GridCellCorner)),
                    )
                }

                // 各个网格项
                for (i in keys.indices) {
                    val isDragged = i == dragIndex
                    val isArmed = i == armedIndex
                    val basePos = layout.slots[i]
                    val colIdx = ((basePos.x - hPadPx) / cellWidthPx).coerceIn(0, columns - 1)

                    val targetSlot = when {
                        !dragging || dropIndex < 0 -> i
                        isDragged -> i
                        dropIndex > dragIndex && i in (dragIndex + 1)..dropIndex -> i - 1
                        dropIndex < dragIndex && i in dropIndex until dragIndex -> i + 1
                        else -> i
                    }
                    val targetPos = layout.slots[targetSlot]
                    val targetDx = (targetPos.x - basePos.x).toFloat()
                    val targetDy = (targetPos.y - basePos.y).toFloat()

                    val animX = remember(dragIndex) { Animatable(0f) }
                    val animY = remember(dragIndex) { Animatable(0f) }
                    if (!isDragged) {
                        LaunchedEffect(targetDx, targetDy) {
                            animX.animateTo(targetDx, GridShiftSpec)
                        }
                        LaunchedEffect(targetDy, targetDx) {
                            animY.animateTo(targetDy, GridShiftSpec)
                        }
                    }

                    val drawX = if (isDragged) dragX.roundToInt() else (basePos.x + animX.value).roundToInt()
                    val drawY = if (isDragged) dragY.roundToInt() else (basePos.y + animY.value).roundToInt()
                    val lifted = isDragged || isArmed

                    Box(
                        Modifier
                            .zIndex(if (isDragged) 2f else if (isArmed) 1f else 0f)
                            .offset { IntOffset(drawX, drawY) }
                            .size(cellWidthDp, cellHeightDp)
                            .padding(3.dp)
                            .then(
                                if (lifted) {
                                    Modifier
                                        .graphicsLayer {
                                            val s = 1f + (GridLiftScale - 1f) * lift
                                            scaleX = s
                                            scaleY = s
                                        }
                                        .shadow(GridLiftElevation * lift, RoundedCornerShape(GridCellCorner))
                                        .background(colors.surface, RoundedCornerShape(GridCellCorner))
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        cell(i, colIdx, lifted, isDragged)
                    }
                }

                // 末尾「+ 添加」格
                if (trailingTile != null && layout.trailingPos != null) {
                    Box(
                        Modifier
                            .offset { layout.trailingPos }
                            .size(cellWidthDp, cellHeightDp)
                            .padding(3.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        trailingTile()
                    }
                }
            }
        }
    }
}

private data class GridHeaderPos(val label: String, val y: Int)

private class GridLayoutResult(
    val slots: Array<IntOffset>,
    val headers: List<GridHeaderPos>,
    val trailingPos: IntOffset?,
    val totalHeight: Int,
)

private fun computeGridLayout(
    count: Int,
    sectionLabels: List<String?>,
    columns: Int,
    leftPad: Int,
    topPad: Int,
    bottomPad: Int,
    cellWidth: Int,
    cellHeight: Int,
    headerHeight: Int,
    sectionGap: Int,
    hasTrailing: Boolean,
): GridLayoutResult {
    val slots = Array(count) { IntOffset.Zero }
    val headers = ArrayList<GridHeaderPos>()
    var y = topPad
    var col = 0
    for (i in 0 until count) {
        val header = sectionLabels.getOrNull(i)
        if (header != null) {
            if (col != 0) {
                y += cellHeight
                col = 0
            }
            if (i > 0) y += sectionGap
            headers.add(GridHeaderPos(header, y))
            y += headerHeight
        }
        slots[i] = IntOffset(leftPad + col * cellWidth, y)
        col++
        if (col == columns) {
            col = 0
            y += cellHeight
        }
    }
    val trailingPos = if (hasTrailing) {
        val pos = IntOffset(leftPad + col * cellWidth, y)
        col++
        if (col == columns) {
            col = 0
            y += cellHeight
        }
        pos
    } else null
    val endY = if (col != 0) y + cellHeight else y
    return GridLayoutResult(
        slots = slots,
        headers = headers,
        trailingPos = trailingPos,
        totalHeight = (endY + bottomPad).coerceAtLeast(cellHeight + topPad + bottomPad),
    )
}

private class GridGeom(
    val slots: Array<IntOffset>,
    val cellWidth: Int,
    val cellHeight: Int,
    val viewportPx: Int,
    val scroll: androidx.compose.foundation.ScrollState,
) {
    fun indexAt(x: Float, y: Float): Int {
        for (i in slots.indices) {
            val s = slots[i]
            if (x >= s.x && x < s.x + cellWidth && y >= s.y && y < s.y + cellHeight) {
                return i
            }
        }
        return -1
    }

    fun nearestSlot(cx: Float, cy: Float): Int {
        if (slots.isEmpty()) return -1
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in slots.indices) {
            val sx = slots[i].x + cellWidth / 2f
            val sy = slots[i].y + cellHeight / 2f
            val d = hypot(cx - sx, cy - sy)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }
}

private fun autoScrollGrid(y: Float, geom: GridGeom, edgePx: Float, maxStepPx: Float) {
    val top = geom.scroll.value.toFloat()
    val bottom = top + geom.viewportPx
    val step = when {
        y < top + edgePx -> ((top + edgePx) - y).coerceAtMost(maxStepPx)
        y > bottom - edgePx -> (y - (bottom - edgePx)).coerceAtMost(maxStepPx)
        else -> 0f
    }
    if (step != 0f) geom.scroll.dispatchRawDelta(if (y < top + edgePx) -step else step)
}

private val GridCellHeight: Dp = 96.dp
private val GridHeaderHeight: Dp = 28.dp
private val GridSectionGap: Dp = 6.dp
private val GridHorizontalPad: Dp = 10.dp
private val GridVerticalPad: Dp = 10.dp
private val GridCellCorner: Dp = 14.dp
private val GridDragSlop: Dp = 14.dp
private const val GridLiftScale = 1.08f
private val GridLiftElevation: Dp = 10.dp
private val GridShiftSpec = tween<Float>(durationMillis = 200, easing = FastOutSlowInEasing)
private val GridLiftSpec = tween<Float>(durationMillis = 160, easing = FastOutSlowInEasing)
private val GridAutoScrollEdge: Dp = 48.dp
private val GridAutoScrollStep: Dp = 22.dp
