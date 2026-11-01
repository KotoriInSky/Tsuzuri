package io.github.kotoriinsky.tsuzuri.hook.feature.gesture

/**
 * 边缘手势浮层之间的互斥：**扇形小窗 ↔ 侧滑索引**。
 *
 * ## 为什么需要它
 *
 * Flyme 的准入清单里有一条「顶层有 `TYPE_ACCESSIBLE` 浮窗 → 不接管」
 * （`SlideAppsTouchController.canInterceptTouch`），
 * 语义是：**已经有浮层在场时，新的边缘手势不接管**。
 *
 * 我们有两个会呼出的边缘浮层（扇形小窗、侧滑索引），它们都在 SystemUI 里、
 * 读同一路输入（[SwipeEventHub]），但**原先谁也不认识谁** —— 于是「扇形开着再从边缘划，
 * 索引又叠上来」「索引手势进行到一半，另一角又冒出扇子」这类冲突是自己造出来的。
 * Flyme 那条守卫管不了这个：它的扇形在 `com.flyme.systemuitools` 进程里，
 * 根本不在同一个浮层表里。
 *
 * ## 用法
 *
 * 两个标志，各自在自己的生命周期里写、对方在读：
 *
 * - 扇形：[FanOverlay] 显示/隐藏时写 [fanShowing]；索引那边在**接管之前**查它。
 * - 索引：手势生命周期（`SlidePerformancePolicy.startGesture/endGesture`）写 [indexShowing]；
 *   扇形那边在**武装之前**与**提交之前**各查一次。
 *
 * 都只在输入线程上**读**，所以是 `@Volatile` 布尔 + 纯内存判断，没有锁、没有 IPC
 * —— 与 `SideIndexDrawerGate.blocks` 同一条纪律（输入线程不许有 IPC）。
 *
 * ⚠️ 只做「已经呼出的浮层不被另一个手势叠加」这一件事。**不做**方向/几何仲裁：
 * 同一次 DOWN 落在两边的触发区时怎么分工，由各自的几何守卫决定（索引有
 * `cornerGuardPx` 避开底部角，扇形有锥内方向判定且纯竖直播弃不消费），
 * 那边的依据是 Flyme 实测的几何，不要在这里再叠一层。
 */
object OverlayArbiter {

    /** 扇形小窗浮层在场（`FanOverlay.show()` 置起、`dismissInternal()` 清掉）。 */
    @Volatile
    var fanShowing: Boolean = false

    /**
     * 扇形**这一次手势**是否已经接管（方向锥确认、抢到指针那一刻起）。
     *
     * 与 [fanShowing] 的区别很关键：[fanShowing] 是"浮层在场"（画出来之后），
     * 而这个标志**比它更早**——要拦的是**系统那条「上滑回桌面」**，它在浮层画出来之前
     * 就可能已经发起了（`MulWinSwitchGestureHandler.handleFirstMove` → `startHomeTransition`）。
     *
     * 写方：扇形手势生命周期（接管时置起；DOWN/抬手/多指结束清掉）。
     * 读方：`CornerSmallWindow.installHomeBlock()` 那条 hook —— 它决定要不要拦下
     * `MulWinSwitchTransition.startHomeTransition()`。
     *
     * 为什么用"这次手势归我们"而不是"浮层在场"作条件：**纯竖直上滑（回桌面）永远不会被
     * 扇形接管**（朝内分量不够），所以这种情况下标志是 false，那条 hook 原样放行 ——
     * 正常回桌面不受影响。只有"用户想划扇形"的那次手势才会把回桌面拦下。
     */
    @Volatile
    var fanOwnsGesture: Boolean = false

    /**
     * **系统最近一次发起「上滑回桌面」的时刻**（`SystemClock.uptimeMillis()`，0 = 从没发生过）。
     *
     * 写方：`CornerSmallWindow.installHomeBlock()` 那条 hook —— 它**放行**时记一笔
     * （拦下时不记，因为那次根本没发起）。
     * 读方：扇形判定 —— 用来实现「**一次手势只出一个结果**」：
     *
     * 回桌面一旦发起就**没法撤销**（转移已经在跑），所以不指望把它拉回来；
     * 正确做法是**谁先到就归谁** —— 如果回桌面在这次手势里已经发起了，那这次就不出扇子，
     * 让用户拿到干净的"回桌面"，而不是"桌面 + 扇子"叠在一起。
     *
     * 判据要比**本次手势的 DOWN 时刻**（不是"最近 1 秒"这种含糊窗口）：
     * 上一次手势造成的回桌面不该影响这一次 —— 用户先回桌面、再从容划扇子是合法用法。
     */
    @Volatile
    var homeStartedAtMs: Long = 0L

    /**
     * 侧滑索引这一次手势是否在接管中（`startGesture` 置起、`endGesture` 清掉）。
     *
     * 是「手势生命周期」而不是「面板可见」：抬手之后面板可能还在，但那次手势已经结束了，
     * 不该再挡住扇形 —— Flyme 的 `onDragEnd` 也是抬手就把索引收掉（`hide()`）。
     */
    @Volatile
    var indexShowing: Boolean = false
}
