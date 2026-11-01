package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 长按拖拽排序 + 左右滑动删除的列表（**自绘**，不进 [FpKit] 契约）。
 *
 * ## 为什么不进契约、也不引第三方库
 *
 * 与 [LetterBar] 同一条理由：两套风格在这件事上没有各自的表达方式，进契约就是
 * 两处实现 + 两处契约测试，而两份实现唯一会做的事就是**手感漂移**。
 *
 * 也不引 `SwipeToDismissBox` / reorderable 之类的库：`SwipeToDismissBox` 在 MiuiX 风格下
 * 没有对应物（照 `LetterBar` 的先例：这类东西自己画，不进契约），而 reorderable 库的问题是
 * 这个 APK 的 dex 要 mmap 进 system_server，体积预算只有 6MB（`tools/verify-release.sh` 会卡），
 * 一个排序库不值这个价。
 *
 * ## 手势判定顺序（⚠️ 这条决定实现顺序，改动前必须读懂）
 *
 * 同一行上有两个手势：**长按 → 纵向拖拽排序**、**水平拖动 → 删除**。它们的判定顺序是：
 *
 * ```
 * 1. 手指按下后不动（< 500ms）       → 什么都不做（点击交给行内内容）
 * 2. 先超过「水平 touch slop」       → 判为删除手势（本组件消费这次手势，长按作废）
 * 3. 先到 500ms 长按                 → 进入拖拽排序态，此后**整场手势都归排序**
 *                                      （即使接下来往左右划，也不会删）
 * 4. 拖拽态下松手                   → 落位（见下面的「取消的路径」）
 * 5. 删除手势松手                   → 水平位移 ≥ [SwipeDeleteThreshold] 才删，否则静默归位
 * ```
 *
 * 实现上靠的是**修饰符链的顺序**：水平手势的 `pointerInput` 挂在长按那个**前面**
 * （= 相对更外层）。指针事件在 Main 阶段是**由内向外**派的，所以长按拖拽（更内层）
 * 先拿到事件并消费，水平手势看到「已被消费」就自己取消。
 * **反过来挂（水平在外层先消费）会出现「想排序结果删了」** —— 那是删除，是不可逆的破坏。
 *
 * 第 3 条是有意偏保守的：按满 500ms 之后再往左右划，用户已经在表达「我要排序」了，
 * 这时删掉一项比「划了没反应」糟得多。反过来的代价只是「想删但按久了要重划一次」。
 *
 * ## 取消的路径（都是「不写盘 / 不删」）
 *
 *  - **拖回原位**：落位下标 == 原下标 → 不写盘（`onMove` 根本不会被调用）；
 *  - **松手在清单外面**：落位下标算不出来（`-1`）→ 不写盘；
 *  - **手势被打断**（来电、返回手势、被回收）：`onDragCancel` → 只清状态；
 *  - **左右滑没到阈值就松手**：不下发 `onDelete`，位移归零（等于用户反悔）；
 *  - **删除本身**：不做二次确认，但调用方**必须**给一条能撤销的提示
 *    （`FanItemsScreen` 的「已移除…/撤销」就是这条路，且撤销要插回原下标）。
 *
 * ## 振动反馈（三条，都走 [fpRowFeedback]）
 *
 *  - **拾起**：长按进入拖拽态的那一下。没有它，用户判断不了「长按到底生效没有」，
 *    常见反应是松手重按一次 —— 于是这一项被反复拿起又放下。
 *  - **跨过一项**：落位下标真的变了才振。⚠️ **不能每帧振**：拖动期间每帧都会进回调，
 *    按当前位移无条件振就是持续震动，「过了一个」这件事反而没有了信号。
 *  - **落位**：只有顺序真的变了才振。拖回原位的松手不振 —— 那一下会让人以为顺序变了。
 *
 * 另外左右滑**跨过删除阈值**也振一次：阈值是「松手就删」的分界，而底色要等下一帧才画出来，
 * 靠它告知太晚。同样只在状态翻转时振（手指停在阈值附近不能变成连续震动）。
 *
 * 振动开关由 [fpRowFeedback] 统一裁决（用户在设置里关掉振动、两套 Kit 表现一致），
 * 这里只负责「什么时候该振」。
 *
 * ## 动画（「手感」基本上就是这一节）
 *
 * 三处，各有各的时长与理由：
 *
 *  - **让位**（[ShiftSpec]，220ms FastOutSlowIn）：拖过一项，被让开的那一行要
 *    **移动过去**而不是**闪现过去**。这是手感最主要的来源 —— 瞬间跳的实现会让
 *    「列表重排」变成一串看不清因果的闪动，用户读不出「我把它拖到哪儿了」。
 *  - **抬起**（[LiftSpec]，180ms）：放大与阴影同时长出来。抬起来这件事要有过程，
 *    一帧到位的话看起来像「那一行忽然换了个样式」，不像「被拿起来了」。
 *  - **跟手**：被拖那一行**不**动画，逐帧跟手指。给它加动画的唯一效果就是
 *    「手指底下拖出残影」。
 *
 * 220 / 180 取自 M3 的 `durationShort4`(220ms) 与 Standard 缓动（`FastOutSlowInEasing`）。
 * 不要再慢：拖拽是**连续**操作，一次让位超过 ~250ms 就会积累成「列表跟不上手」。
 *
 * ## ⚠️ 每行必须用 `Column` 包，不能用 `Box`
 *
 * `row` 画的是「一行」，但**展开态的一行会发出多个兄弟节点**（行体 + 分割线 + 按钮行 + …）。
 * `Box` 的子节点默认全部对齐 TopStart，也就是**互相重叠**：非展开态只有一个节点所以看不出来，
 * 一展开就是「两组按钮叠在一起、还压着行本身的标题与右值」。
 * 用 `Column` 才让它们纵向依次排布、行高随之变大，[onSizeChanged] 也才量得到真实高度
 * （兄弟行的「让位」偏移依赖它）。
 *
 * ## 为什么不假设行高
 *
 * 拖拽的下标全靠「每项的顶边」算出来，而**两套 Kit 的行高不一样**
 * （Material 3 的 `ListItem` 与澎湃的 `ArrowPreference` 差得不小）、
 * 展开的那一项还会变高。所以顶边是从 [onSizeChanged] **量出来的**，
 * 不是假设的常量 —— 假设错了的表现是「拖到 A 落到 B」，而且只在一种风格下复现。
 * 没量到的那一帧用 [FallbackRowHeight] 兜底（第一帧而已，长按要 500ms，量不到才怪）。
 *
 * ## 手势怎么拿到的（这条决定实现顺序，不要改）
 *
 * 两个 `pointerInput` 都必须挂在 `verticalScroll` **里面**（修饰符链里更靠后），
 * 而且**长按那个必须最后挂**（最内层）。理由见上面的「手势判定顺序」：
 * 内层的拖拽先消费，外层的滚动/水平手势看到变化已被消费就会自己取消。
 * 反过来挂（挂在滚动外面）会出现「拖着拖着列表也在滚」—— 两个手势同时活着。
 *
 * ## 跟手与自动滚动
 *
 * 被拖项的顶边**直接由手指的内容坐标算**（`手指 y − 按下时手指在行内的偏移`），
 * 不是累加 delta。差别在边缘自动滚动时：内容滚了以后手指的内容坐标会变，
 * 直接算就自然跟住了手指；累加 delta 会让这一项在滚动时从手指底下漂出去。
 *
 * @param keys 每项的稳定键（顺序 = 显示顺序 = 写盘顺序）
 * @param onMove 松手落位时回调 `(from, to)`；`from == to` 时**不会**被调用
 * @param enabled 是否允许**排序**（清单未启用、只有一项时传 false）。
 *   注意它**不管删除** —— 删除由 [onDelete] 是否为 null 决定：
 *   「只有一项时不能排序」是对的，但「只有一项时不能删」不是。
 * @param onDelete 左右滑过阈值时回调它的下标。`null` = 这一份清单不支持滑删
 *   （此时水平手势根本不挂）。⚠️ 调用方要负责给撤销路径
 * @param row 画一行。`dragging` = 这一行正在被拖 —— 调用方据此禁用行内的点击
 *            （长按之后又抬手会同时触发「拖拽」和「点击」，那是这个组件唯一
 *            会让用户困惑的地方，所以状态交回给调用方表达）
 *
 * ⚠️ 调用方必须给它一个**有界的高度**（`Modifier.weight(1f)` 或固定 `height`）：
 * 高度无界时（例如直接放进一个纵向滚动的 `Column`）视口高度会是无穷大，
 * 边缘自动滚动的判据随之失效。
 */
@Composable
fun ReorderList(
    keys: List<String>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDelete: ((index: Int) -> Unit)? = null,
    row: @Composable (index: Int, dragging: Boolean) -> Unit,
) {
    if (keys.isEmpty()) return

    val colors = LocalFpKit.current.colors()
    val density = LocalDensity.current
    val fallbackPx = with(density) { FallbackRowHeight.roundToPx() }
    val edgePx = with(density) { AutoScrollEdge.toPx() }
    val maxStepPx = with(density) { AutoScrollStep.toPx() }
    val swipeThresholdPx = with(density) { SwipeDeleteThreshold.toPx() }

    /** 量出来的行高。键是清单项的键，删掉项之后残留的条目无害。 */
    val heights = remember { mutableStateMapOf<String, Int>() }
    val scroll = rememberScrollState()

    /** 被拖项在原清单里的下标；`-1` = 没在拖。 */
    var dragIndex by remember { mutableStateOf(-1) }

    /** 正在被左右滑的是哪一项；`-1` = 没有。与 [dragIndex] **不会同时 >= 0**（见类注释）。 */
    var swipeIndex by remember { mutableStateOf(-1) }

    /** 这一项当前的横向位移（px），带正负（左右都能滑）。 */
    var swipeDx by remember { mutableStateOf(0f) }

    /** 横向位移是否已经过了删除阈值。只为「跨过阈值振一次」服务（见 [feedbackRef]）。 */
    var swipeArmed by remember { mutableStateOf(false) }

    // 振动回调。走 [fpRowFeedback] 而不是直接调 `LocalHapticFeedback`：
    // 用户能在设置里关掉振动，两套 Kit 也必须表现一致（那条理由写在 KitSupport 的 KDoc 里）。
    //
    // 为什么套 rememberUpdatedState 而不是直接捕获：手势 block 是在 pointerInput **启动时**
    // 创建的，开关改了不会重建它 —— 直接捕获会让「刚关掉振动，这一次拖动还在振」。
    // 也**不能**把开关写进 pointerInput 的键：那会重建手势、把正在进行的拖动打断
    // （与下面 `onDelete` 那条同一个坑）。
    val feedbackRef = rememberUpdatedState(fpRowFeedback())

    // `onDelete` 每次重组都是新的 lambda 实例，直接写进 pointerInput 的键会让手势
    // **每次重组都重启**（划到一半被别处的状态变化打断）。所以键只用「有没有」，
    // 回调本身走 rememberUpdatedState 取最新的那一个
    val canDelete = onDelete != null
    val deleteRef = rememberUpdatedState(onDelete)

    // 清单变了（增删、撤销、拖完落地）→ 正在滑的那一项可能已经不存在或换了位置。
    // 而 pointerInput 因键变化被销毁时**不会**走 onDragCancel（协程是被取消的），
    // 不在这里归零就会留下一行永远偏着。键用 `keys` 的**结构相等**，
    // 所以用户没动清单时不会打断正在进行的手势
    LaunchedEffect(keys) {
        swipeIndex = -1
        swipeDx = 0f
        swipeArmed = false
    }

    /** 被拖项在**内容坐标**里的顶边（跟手就是它）。 */
    var dragTop by remember { mutableStateOf(0f) }

    /** 手指按下的位置相对被拖项顶边的偏移（跟手要减掉它，否则一行会跳到指尖下）。 */
    var grabOffset by remember { mutableStateOf(0f) }

    /** 落位下标；`-1` = 落在清单外面（= 取消）。 */
    var dropIndex by remember { mutableStateOf(-1) }

    BoxWithConstraints(modifier) {
        val viewportPx = constraints.maxHeight

        // 顶边表（内容坐标）。每次都重算：项数只有几十，比维护一份增量便宜也更难写错
        val n = keys.size
        val tops = IntArray(n)
        val hs = IntArray(n)
        var acc = 0
        for (i in 0 until n) {
            tops[i] = acc
            hs[i] = heights[keys[i]] ?: fallbackPx
            acc += hs[i]
        }
        val totalPx = acc

        // 手势回调是在 pointerInput 的 block 启动时创建的（键不变就不重建），
        // 直接捕获这些数组会拿到**下第一帧的旧值**。rememberUpdatedState 保证回调里
        // 读到的永远是最近一次组合算出来的表
        val geom = rememberUpdatedState(Geom(tops, hs, viewportPx, scroll))
        val stepPx = rememberUpdatedState(maxStepPx)
        val edgeNow = rememberUpdatedState(edgePx)

        val dragging = dragIndex in 0 until n
        val draggedH = if (dragging) hs[dragIndex] else 0

        // ── 抬起进度（0 = 平放，1 = 完全抬起）──
        //
        // 只要**一个**值，不需要每行一份：同一时刻只有一行被拖。
        // 放大与阴影都由它驱动，于是「拿起来」是一个 180ms 的动作而不是一帧突变。
        //
        // 松手时它回落到 0，但那一刻 keys 已经重排、没有任何行再吃这个值，
        // 所以回落是看不见的 —— 这不是疏忽，而是「放下」本就该由清单重排来表达。
        // ⚠️ 没有用 animateFloatAsState：本模块只带 animation-core（foundation 传递进来），
        // 不带 animation 那件 artifact，那个符号解析不到。Animatable 本来也更适合这里 ——
        // 见下面「让位」那条：需要一个能**整颗重建**的值。
        val liftAnim = remember { Animatable(0f) }
        LaunchedEffect(dragging) {
            liftAnim.animateTo(if (dragging) 1f else 0f, LiftSpec)
        }
        val lift = liftAnim.value

        /** 这一行要额外偏移多少（内容坐标）。被拖项跟手，其余项让位。 */
        fun shiftOf(i: Int): Int {
            if (!dragging || dropIndex < 0) return 0
            if (i == dragIndex) return (dragTop - tops[dragIndex]).roundToInt()
            val d = dragIndex
            val t = dropIndex
            return when {
                // 往下拖：被拖项原来占的那一格空出来，`d` 之后到落点之间的项整体上移
                t > d && i > d && i <= t -> -draggedH
                // 往上拖：落点到 `d` 之间的项整体下移
                t < d && i >= t && i < d -> draggedH
                else -> 0
            }
        }

        /** 落位指示线的内容坐标。 */
        fun indicatorTop(): Int = when {
            !dragging || dropIndex < 0 || dropIndex == dragIndex -> -1
            // 往下：插在落点那一项的**下面**；往上：插在落点那一项的**上面**
            dropIndex > dragIndex -> tops[dropIndex] + hs[dropIndex]
            else -> tops[dropIndex]
        }

        Box(Modifier.fillMaxSize().clipToBounds()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    // ① 水平手势（删除）挂在长按那个**前面** = 相对更外层。
                    //    两者同时等待时，更内层的长按拖拽在 Main 阶段先拿到事件并消费，
                    //    这里看到「已消费」就自己取消 —— 判定顺序见类注释
                    .pointerInput(keys, canDelete) {
                        if (!canDelete) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                // 已经进入拖拽排序态：整场手势归排序，水平这一路不参与
                                if (dragIndex >= 0) return@detectHorizontalDragGestures
                                val g = geom.value
                                val i = indexAt(offset.y, g.tops, g.hs)
                                if (i >= 0) {
                                    swipeIndex = i
                                    swipeDx = 0f
                                    swipeArmed = false
                                }
                            },
                            onHorizontalDrag = { change, dx ->
                                if (swipeIndex < 0 || dragIndex >= 0) {
                                    return@detectHorizontalDragGestures
                                }
                                // 消费掉：这一场手势是我们的，免得列表同时横向也跟着动
                                change.consume()
                                swipeDx += dx
                                // 跨过删除阈值时振一次。**只在状态翻转的那一帧振** ——
                                // 按当前位移每帧都判一次的话，手指停在阈值附近会变成连续震动。
                                // 这一下是必要的：阈值是「松手就删」的分界，光靠底色（还没画出来）
                                // 告知得太晚了。
                                val armed = abs(swipeDx) >= swipeThresholdPx
                                if (armed != swipeArmed) {
                                    swipeArmed = armed
                                    if (armed) feedbackRef.value()
                                }
                            },
                            onDragEnd = {
                                val s = swipeIndex
                                val passed = s >= 0 && abs(swipeDx) >= swipeThresholdPx
                                swipeIndex = -1
                                swipeDx = 0f
                                swipeArmed = false
                                // 只有过了阈值才删。没到阈值 = 用户反悔 → 静默归位（不做回弹动画，
                                // 位移消失本身就是「没删」的反馈）
                                if (passed) deleteRef.value?.invoke(s)
                            },
                            onDragCancel = {
                                swipeIndex = -1
                                swipeDx = 0f
                                swipeArmed = false
                            },
                        )
                    }
                    // ② 长按拖拽排序。⚠️ 必须挂在 verticalScroll 与上面那个之后（= 最内层）。
                    //    理由见类注释「手势判定顺序」
                    .pointerInput(keys, enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val g = geom.value
                                val i = indexAt(offset.y, g.tops, g.hs)
                                if (i >= 0) {
                                    dragIndex = i
                                    dragTop = g.tops[i].toFloat()
                                    grabOffset = offset.y - g.tops[i]
                                    dropIndex = i
                                    // 「拾起」振一次：长按 500ms 之后必须有个即时反馈告诉用户
                                    // 「已经拿起来了」，否则他会以为长按没生效、然后松手重按
                                    feedbackRef.value()
                                }
                            },
                            onDrag = { change, _ ->
                                val d = dragIndex
                                if (d >= 0) {
                                    val g = geom.value
                                    val y = change.position.y
                                    dragTop = y - grabOffset
                                    val h = g.hs[d]
                                    // 落下点的中点决定落位；落在清单首尾之外 = 取消
                                    val mid = dragTop + h / 2f
                                    val total = g.tops[g.tops.size - 1] + g.hs[g.hs.size - 1]
                                    val next = if (mid < 0f || mid > total.toFloat()) {
                                        -1
                                    } else {
                                        dropIndexAt(mid, d, g.hs)
                                    }
                                    // 跨过一项（落位下标真的变了）才振。**不能每帧振** ——
                                    // 拖动期间每帧都会进这里，那样是持续震动而不是「过了一个」
                                    if (next != dropIndex) {
                                        dropIndex = next
                                        feedbackRef.value()
                                    }
                                    autoScroll(y, g, edgeNow.value, stepPx.value)
                                }
                            },
                            onDragEnd = {
                                val d = dragIndex
                                val t = dropIndex
                                dragIndex = -1
                                dropIndex = -1
                                // 拖回原位（t == d）与落在外面（t < 0）都不写盘 ——
                                // 一次没改变顺序的拖动不该在配置里留下一次写入
                                if (d >= 0 && t >= 0 && t != d) {
                                    onMove(d, t)
                                    // 只有真的改动了顺序才振（「落位」的确认）。没改动的松手
                                    // 不该有反馈：那会让人以为顺序变了
                                    feedbackRef.value()
                                }
                            },
                            onDragCancel = {
                                dragIndex = -1
                                dropIndex = -1
                            },
                        )
                    },
            ) {
                for (i in 0 until n) {
                    val key = keys[i]
                    val isDragged = i == dragIndex
                    val isSwiped = i == swipeIndex
                    // 过了阈值就换底色：松手会删掉这一项这件事，必须在松手**之前**看得见
                    // （HCI「立刻反馈」；只有颜色不够 —— 位移本身就是第二个信号）
                    val swipeReady = isSwiped && abs(swipeDx) >= swipeThresholdPx

                    // ── 让位偏移：兄弟行要「移动过去」，不是「闪现过去」──
                    //
                    // ⚠️ 用 Animatable 而不是 animateFloatAsState，原因是**松手那一帧必须立刻归零**：
                    // 松手后调用方重排 keys，这一行本来就换了位置；此时再动画回 0 等于
                    // 「先多偏一格、再滑回来」，是一次朝错误方向的跳动（而且只在松手时可见，
                    // 是那种「说不清哪里怪但就是不对」的手感）。
                    // remember(dragIndex) 让每次「拿起/放下」都换一个从 0 出发的 Animatable，
                    // 于是放下的那一帧天然就是 0 —— 动画只负责拖动**过程中**的让位。
                    val targetShift = shiftOf(i)
                    val shiftAnim = remember(dragIndex) { Animatable(0f) }
                    // 被拖的那一行**不**动画：它必须逐帧跟住手指，动画会让手指底下拖影
                    if (!isDragged) {
                        LaunchedEffect(targetShift) {
                            shiftAnim.animateTo(targetShift.toFloat(), ShiftSpec)
                        }
                    }
                    val shift = if (isDragged) targetShift.toFloat() else shiftAnim.value

                    // ⚠️ 这里必须是 Column，**不能改成 Box**。
                    //
                    // `row` 画的是「一行」，但一行在展开态下会发出**多个兄弟节点**
                    // （行体 + 分割线 + 若干按钮行）。Box 的子节点默认全部对齐到 TopStart，
                    // 也就是**互相重叠** —— 非展开态只有一个节点所以看不出来，一展开就是
                    // 「两组按钮叠在一起、还压着行本身的标题」。
                    // 用 Column 才让它们按纵向依次排布、行高随之变大（也让 onSizeChanged 量到真高度，
                    // 兄弟行的「让位」偏移才是对的）。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // 被拖的那一项要压在其它项上面（让位动画里它是「抬起来」的那个）
                            .zIndex(if (isDragged) 1f else 0f)
                            // 纵向 = 拖拽的跟手/让位；横向 = 左右滑删除的跟手（只作用于被滑那一行）
                            .offset {
                                IntOffset(
                                    if (isSwiped) swipeDx.roundToInt() else 0,
                                    shift.roundToInt(),
                                )
                            }
                            .onSizeChanged { heights[key] = it.height }
                            .then(
                                when {
                                    isDragged -> Modifier
                                        .graphicsLayer {
                                            // 由 lift 驱动：拾起是 180ms 的过渡，不是一帧跳到 1.05
                                            val s = 1f + (LiftScale - 1f) * lift
                                            scaleX = s
                                            scaleY = s
                                        }
                                        // 阴影也随 lift 长出来：抬起 == 变高 + 变实，两者要同时发生
                                        .shadow(LiftElevation * lift, RoundedCornerShape(LiftCorner))
                                        .background(colors.surface, RoundedCornerShape(LiftCorner))

                                    swipeReady -> Modifier.background(
                                        colors.negative.copy(alpha = DeleteTintAlpha),
                                        RoundedCornerShape(DeleteCorner),
                                    )

                                    else -> Modifier
                                },
                            ),
                    ) {
                        row(i, isDragged)
                    }
                }
            }

            // 落位指示线：画在**外层** Box 里（它的坐标是视口坐标，所以要减掉滚动量）。
            // 画在 Column 里的话它会被挤出列表的可视区，用户就看不到落点了
            val lineTop = indicatorTop()
            if (lineTop >= 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, lineTop - scroll.value) }
                        .height(IndicatorHeight)
                        .background(colors.accent, RoundedCornerShape(IndicatorHeight / 2)),
                )
            }
        }
    }
}

/**
 * 可见的拖拽把手（六点握把）。画在行的**最右边**，行首留给 [ReorderRemoveButton]。
 *
 * ## 为什么自己画而不是引图标
 *
 * `material-icons-core` 只有 49 个图标，里面**没有** `DragHandle`；为它去引
 * `material-icons-extended`（几百个图标）不值 —— 这个 APK 的 dex 要 mmap 进
 * system_server，体积有预算（`tools/verify-release.sh` 会卡）。六点握把本来就是
 * 两列三行的圆点，十几行就够，而且两套风格在这一处没有任何表达差异。
 *
 * ## ⚠️ 它为什么不绑独立手势
 *
 * 排序手势挂在**整行**上（见本文件类注释「手势判定顺序」），所以按在把手上长按
 * 与按在行上长按是同一件事 —— 把手**是真能拖的**，只是行上别处也能拖。
 * 那是能力的**超集**而不是假把手：用户照着把手的暗示去拖，一定拖得动。
 *
 * 反过来（只认把手）要付出的代价是把手只有 20dp 宽、命中率很差，
 * 而这里的手势本来已经排除了「想删结果删了」那条最糟的路径。
 *
 * @param enabled `false` = 这份清单此刻拖不动（例如只剩一项）。仍然占位，
 *                只是画成不可用色 —— 留着位置比让行内容左右跳要好
 */
@Composable
fun ReorderGrip(
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFpKit.current.colors()
    val tint = if (enabled) colors.muted else colors.outline.copy(alpha = GripDisabledAlpha)
    Canvas(modifier.size(GripSize, GripSize)) {
        val r = GripDotRadius.toPx()
        val gap = GripDotGap.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        for (col in 0..1) {
            val x = cx + if (col == 0) -gap / 2f else gap / 2f
            for (row in -1..1) {
                drawCircle(tint, r, Offset(x, cy + row * gap))
            }
        }
    }
}

/**
 * 行首的移除按钮（红底 ⊖）。
 *
 * 左右滑看不出来，靠一行手势说明兜底又是在替控件道歉。滑删仍保留，
 * 是同一件事的快捷手势，两者由调用方接到同一条能撤销的路径上。
 *
 * 自己画的理由同 [ReorderGrip]：`material-icons-core` 没有这个图形。
 */
@Composable
fun ReorderRemoveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFpKit.current.colors()
    val feedback = fpRowFeedback()
    Box(
        modifier
            .size(GripSize)
            .clickable(role = Role.Button, onClickLabel = label) {
                feedback()
                onClick()
            }
            .semantics { contentDescription = label },
    ) {
        Canvas(Modifier.size(RemoveDiameter).align(Alignment.Center)) {
            drawCircle(colors.negative)
            val half = size.width * RemoveBarRatio
            val cy = size.height / 2f
            drawLine(
                Color.White,
                Offset(size.width / 2f - half, cy),
                Offset(size.width / 2f + half, cy),
                strokeWidth = RemoveBarWidth.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 红底圆的直径（比行首图标小一号，不和图标抢）。 */
private val RemoveDiameter: Dp = 20.dp

/** 横杠半长占直径的比例。 */
private const val RemoveBarRatio = 0.25f

private val RemoveBarWidth: Dp = 2.dp

/** 把手的点击区尺寸（握把本身只有 9dp 宽，外面这圈留白是命中区）。 */
private val GripSize: Dp = 32.dp

/** 两个圆点圆心之间的距离（同时也是行间距）。 */
private val GripDotGap: Dp = 6.dp

private val GripDotRadius: Dp = 1.5.dp

/** 拖不动时握把的不透明度。仍然画出来 —— 它同时是「这里本来能排」的说明。 */
private const val GripDisabledAlpha = 0.4f

/**
 * 手势要用的几何快照。
 *
 * 一次性打包传进回调（而不是分别 rememberUpdatedState 四个值）：它们必须**同一次组合**
 * 的值才不会互相错位 —— 行高表更新了而视口高度还是旧的，落位会算错。
 */
private class Geom(
    val tops: IntArray,
    val hs: IntArray,
    val viewportPx: Int,
    val scroll: androidx.compose.foundation.ScrollState,
)

/** 手指 y（内容坐标）落在第几项上；`-1` = 不在任何一项上（按在列表末尾的空白处）。 */
private fun indexAt(y: Float, tops: IntArray, hs: IntArray): Int {
    if (y < 0f) return -1
    for (i in tops.indices) {
        if (y < (tops[i] + hs[i]).toFloat()) return i
    }
    return -1
}

/**
 * 落位下标：**先把它从清单里拿掉，再看插到第几位**。
 *
 * 判据是「哪几项的**中点**在我上面」—— 与 [io.github.kotoriinsky.tsuzuri.ui.state.FanItems.moved]
 * 的 `remove(from)` + `add(to)` 语义严格对应，所以这个函数给出的 `to` 可以直接喂给它。
 */
private fun dropIndexAt(mid: Float, from: Int, hs: IntArray): Int {
    var acc = 0f
    var idx = 0
    for (j in hs.indices) {
        if (j == from) continue
        val h = hs[j].toFloat()
        if (mid > acc + h / 2f) idx++
        acc += h
    }
    return idx
}

/**
 * 手指贴到上下边缘时滚动列表。
 *
 * 用 [androidx.compose.foundation.ScrollState.dispatchRawDelta] 而不是 `scrollBy`：
 * 后者是挂起函数，在 `onDrag`（非挂起）里只能 `launch` 一个协程，
 * 而拖动期间每个事件都会 launch 一次 —— 滚动动画互相打断，手感是抖的。
 * `dispatchRawDelta` 正是滚动组件自己在拖动里用的那条路。
 *
 * 滚动速度与「越界多少」成正比（越靠边越快），上限 [AutoScrollStep]。
 */
private fun autoScroll(y: Float, geom: Geom, edgePx: Float, maxStepPx: Float) {
    val top = geom.scroll.value.toFloat()
    val bottom = top + geom.viewportPx
    val step = when {
        y < top + edgePx -> ((top + edgePx) - y).coerceAtMost(maxStepPx)
        y > bottom - edgePx -> (y - (bottom - edgePx)).coerceAtMost(maxStepPx)
        else -> 0f
    }
    if (step != 0f) geom.scroll.dispatchRawDelta(if (y < top + edgePx) -step else step)
}

/** 指示线的高度。3dp 与扇形选中描环同量级（可见但不喧宾夺主）。 */
private val IndicatorHeight: Dp = 3.dp

/**
 * 被拖起来的行放大一点点 —— 「抬起来」这件事除了阴影还要有形状变化（HCI：不要只靠颜色）。
 *
 * ⚠️ 1.02 → 1.05（2026-09-18）：原值在 72dp 的行上只有 1.4px 的位移，
 * **等于没抬** —— 用户看不到「拿起来了」，于是怀疑长按到底生效没有
 * （本文件类注释里写「拾起振动」就是为了解决这个问题，但视觉上仍然缺一份）。
 * 1.05 在同样的行高上是 3.6px，加上 8dp 阴影足够读出层次。
 */
private const val LiftScale = 1.05f

private val LiftElevation: Dp = 8.dp

private val LiftCorner: Dp = 16.dp

/**
 * 让位动画。
 *
 * ⚠️ 这是「手感不对」的主因（2026-09-18 修）：原来的让位是 `shiftOf()` 直接赋值，
 * 兄弟行**瞬间跳**到新位置。ItemTouchHelper（Android View 侧那个打磨过多年的实现）
 * 与 M3 的做法都是**平滑过渡** —— 拖过一项，被让开的那一行要「移动过去」而不是「闪现过去」。
 *
 * 220ms / FastOutSlowIn 对齐 M3 的 `durationShort4` + Standard 缓动：
 * 比这个更慢会让人觉得列表跟不上手，更快就看不出「让位」这个动作本身。
 */
private val ShiftSpec = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

/** 抬起的缩放动画。与让位同族，稍快一点 —— 「拾起」是要立刻回应长按的。 */
private val LiftSpec = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)

/** 没量到行高时用的兜底值（两套 Kit 的两行行高都在这个量级）。 */
private val FallbackRowHeight: Dp = 72.dp

/**
 * 左右滑多少才删。
 *
 * 与 Material 的 `SwipeToDismiss`（半个行宽）不同，这里用**固定 dp**：本组件的行宽在
 * 两套 Kit 下不一样（澎湃的行有内缩留白），按比例算会让「滑多远」在两套风格里手感不同。
 * 88dp 比一个拇指的自然横扫短、又明显长于误触 —— 它的作用不是让人滑到底，
 * 而是把「手滑了一下」与「我要删」分开。
 */
private val SwipeDeleteThreshold: Dp = 88.dp

/** 过了阈值时那一行的底色不透明度（够看见「要删了」，又不遮住行里的字）。 */
private const val DeleteTintAlpha = 0.18f

/** 底色圆角：与拖起来那一行的圆角同量级。 */
private val DeleteCorner: Dp = 16.dp

/** 手指进入上下这个范围内就开始自动滚动。 */
private val AutoScrollEdge: Dp = 48.dp

/** 自动滚动的单次步长上限。 */
private val AutoScrollStep: Dp = 24.dp
