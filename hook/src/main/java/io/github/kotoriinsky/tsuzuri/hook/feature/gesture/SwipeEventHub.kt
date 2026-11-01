package io.github.kotoriinsky.tsuzuri.hook.feature.gesture

import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 系统手势事件流的分发中枢。
 *
 * ## 为什么要它
 *
 * 「底角呼出小窗」和「侧滑索引」都要读同一条全局触摸流。如果各自去挂一次
 * `MulWinSwitchEventController`，就会有两个 proxy 在同一份事件上抢判定，
 * 而且顺序取决于谁先注册 —— 这正是「同一条边缘不能有两个 touch owner」。
 * 所以这里只挂一次，内部按订阅者顺序分发，谁先消费谁负责。
 *
 * ## 挂的是什么
 *
 * 设备实测（`/system_ext/framework/Miui-WindowManager-Shell.jar` 的 dex）：
 *
 * ```
 * MulWinSwitchEventController                      PUBLIC class
 *   static INSTANCE : MulWinSwitchEventController
 *   mEventHandlers : java.util.List                // 实例字段，按顺序遍历
 *   getInstance() : MulWinSwitchEventController
 *   registerEventHandler(EventHandler)
 *   createEventReceiver(Context)                   // 必须调，否则输入通道不存在
 *   $EventHandler  PUBLIC INTERFACE
 *     interceptEventWhenHandled() : boolean
 *     onEvent(MotionEvent, InputMonitor) : boolean
 * ```
 *
 * 用 `java.lang.reflect.Proxy` 实现这个接口（它在运行时才可见，编译期拿不到）。
 * 注册后**把 proxy 挪到 mEventHandlers 头部** —— 列表是顺序遍历的，放后面
 * 会被系统自己的处理先吃掉事件。
 *
 * ## 「消费」（return true）**不等于**「接管」—— 这是 2026-09-15 定案的根因
 *
 * 反编译同一份 dex（`research/decompiled/wms` 的
 * `MulWinSwitchEventController.java:51-55`）可以看到这个返回值到底管什么：
 *
 * ```java
 * for (EventHandler h : mEventHandlers) {
 *     if (h.onEvent(motionEvent, mInputMonitor) && h.interceptEventWhenHandled()) break;
 * }
 * finishInputEvent(inputEvent, true);
 * ```
 *
 * 它只做两件事：**跳出这条 handler 链**，以及把监视器这一侧的事件标记成 handled。
 * **窗口（桌面、控制中心、任务中心、导航栏）照样收到完整的手势** ——
 * 想要真把触摸从窗口手里拿走，框架里只有一个办法：`InputMonitor.pilferPointers()`。
 *
 * 系统自己那条 handler 就是这么干的（`MulWinSwitchEventHandler.java:412, 462`：
 * `inputMonitor.pilferPointers()`，注释与调用点都在那份源码里）。我们此前只 return true，
 * 于是**每个边缘手势都同时发生两件事**：我们的浮层出来 + 系统的手势照旧执行 ——
 * 产品经理报的「下滑呼出索引容易拉开控制中心 / 上滑容易拉开抽屉 / 呼出扇形容易拉开任务中心」
 * 三条是同一个原因，而不是三个几何冲突。当时的应对是**不断往后退**（只认向下起手、
 * 右边缘整条让出、方向锥从 0.25 收到 0.6），退到用户反馈「不太能及时触发」。
 * 有了 [pilfer] 就不必再退：判据可以回到参考实现（Flyme）的宽松值。
 *
 * ⚠️ `pilferPointers()` 是一次 **oneway binder 调用**（输入线程上的 IPC）。
 * 这条通道上 IPC 本来是红线（ §7.5），所以它被限死在**每个手势最多一次**、
 * 且只在已经决定接管的那一刻调 —— 不是每帧路径。
 *
 * ## 纪律
 *
 * `onEvent` 跑在输入分发线程上，属于高频路径（规范 §7.4）：禁 IO、禁日志、禁分配。
 * 所以下面每个 listener 的返回值都是纯计算，日志只出现在状态切换的那一帧。
 */
object SwipeEventHub {

    private const val TAG = "FP-SwipeHub"

    private const val CONTROLLER =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController"

    /** 一个手势订阅者。返回 true 表示这次事件被我消费了，后面的订阅者和系统都收不到。 */
    fun interface Listener {
        fun onTouch(event: MotionEvent): Boolean
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var installed = false

    /**
     * 系统的输入监视器。
     *
     * 类型写 `Any?` 而不是 `android.view.InputMonitor`：后者是 @hide 类，
     * 公开 SDK 里没有，写真实类型根本编译不过。除了 [pilfer] 那一次反射调用，
     * 我们不用它的任何方法。
     *
     * **每次事件都刷新**：值取自 proxy 收到的 `onEvent(event, inputMonitor)` 第二个实参
     * —— 那一定是当前有效的那个（`EventReceiver` 被重建过的话，安装时存下的旧引用就是野的）。
     */
    @Volatile
    private var inputMonitor: Any? = null

    /** `android.view.InputMonitor.pilferPointers()`，安装时解析一次。见 [pilfer]。 */
    private var pilferMethod: java.lang.reflect.Method? = null

    /**
     * 本次手势是否已经抢过指针。只在输入线程读写，DOWN 时复位 ——
     * 一个手势抢一次就够，重复抢没有额外效果，只是多一次 binder 调用。
     */
    private var pilferedThisGesture = false

    /** 抢成功的次数（诊断：安装报告与手势汇总行都打它）。 */
    @Volatile
    var pilferCount: Long = 0
        private set

    /** 抢失败的原因（诊断，只记第一次）。失败时**不影响判定**，只是系统手势不会被取消。 */
    @Volatile
    var pilferFailure: String? = null
        private set

    /** 事件处理器被调用的总次数，用于诊断「挂上去了但没事件」这种情况。 */
    @Volatile
    var eventCount: Long = 0
        private set

    /** 每个手势的诊断日志开没开。安装时读一次系统日志级别，输入线程只读这个布尔。 */
    @Volatile
    private var diagLog = false

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * 安装事件通道。**幂等** —— 两个特性都调它也只挂一次。
     */
    fun ensureInstalled(ctx: HookContext): InstallReport {
        if (installed) {
            return InstallReport(1, notes = listOf("already installed, events=$eventCount"))
        }
        synchronized(this) {
            if (installed) return InstallReport(1, notes = listOf("already installed"))
            diagLog = runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)

            val cls = try {
                Class.forName(CONTROLLER, false, ctx.classLoader)
            } catch (t: Throwable) {
                // 找不到 = 这个 ROM 没有这套多窗切换实现。降级退出，不抛异常。
                ctx.log(Log.WARN, "$CONTROLLER 不存在，手势特性无法工作（这是个正常结果，不是错误）")
                return InstallReport(0, missing = listOf(CONTROLLER))
            }

            val instance = try {
                val field = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }
                field.get(null)
            } catch (t: Throwable) {
                runCatching { cls.getMethod("getInstance").invoke(null) }.getOrNull()
            }
            if (instance == null) {
                return InstallReport(0, notes = listOf("no INSTANCE"))
            }

            val iface = try {
                Class.forName("$CONTROLLER\$EventHandler", false, ctx.classLoader)
            } catch (t: Throwable) {
                return InstallReport(0, missing = listOf("EventHandler"))
            }

            val proxy = Proxy.newProxyInstance(ctx.classLoader, arrayOf(iface)) { self, method, args ->
                when (method.name) {
                    "onEvent" -> {
                        // 监视器每次都重新取（见 [inputMonitor] 的注释）。一次引用写，无分配。
                        inputMonitor = args?.getOrNull(1)
                        dispatch(args?.getOrNull(0) as? MotionEvent)
                    }
                    // 我们消费掉的事件不再传给别人。返回 true 才是「吃掉」。
                    // ⚠️ 注意这只对**这条 handler 链**成立，窗口照样收得到 —— 见类注释，
                    // 真要拿走手势必须调 [pilfer]。
                    "interceptEventWhenHandled" -> true
                    "toString" -> "TsuzuriSwipeProxy"
                    // 必须按**身份**比较：宿主的 `list.remove(proxy)` 靠 equals 找元素。
                    // 恒 false 时删不掉，`prioritize` 的 add(0) 会让 proxy 在链表里出现两次，
                    // 未消费的事件被处理两遍。
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> args?.getOrNull(0) === self
                    else -> null
                }
            }

            val ok = try {
                cls.getMethod("registerEventHandler", iface).invoke(instance, proxy)
                prioritize(cls, instance, proxy)
                true
            } catch (t: Throwable) {
                ctx.log(Log.ERROR, "registerEventHandler 失败", t)
                false
            }
            if (!ok) return InstallReport(0, notes = listOf("register failed"))
            registered = Registration(cls, instance, proxy)

            // 输入通道可能还没建（用户没开过多窗功能）—— 显式建一次。
            val ctxObj = SystemUiEnv.context()
            if (ctxObj != null) {
                runCatching {
                    cls.getMethod("createEventReceiver", android.content.Context::class.java)
                        .invoke(instance, ctxObj)
                }.onFailure { ctx.log(Log.INFO, "createEventReceiver 未成功（可能已存在）") }
            }

            // 通道存在性的一次探测（报告里那行 `monitor=`）。真正的抢指针用的是 onEvent
            // 每帧刷新的那个引用 —— 这里只是**安装期**的确认，让「通道没建起来」与
            // 「判定没过」在报告里就能分开。
            inputMonitor = runCatching {
                cls.getMethod("getInputMonitor").invoke(instance)
            }.getOrNull()

            installed = true
            // 抢指针用的方法：@hide，公开 SDK 里没有。拿不到不影响其它功能 ——
            // 只是系统手势不会被取消（退化成「消费但窗口照样收到」的老行为）。
            pilferMethod = runCatching {
                Class.forName("android.view.InputMonitor", false, ctx.classLoader)
                    .getMethod("pilferPointers")
            }.onFailure {
                ctx.log(Log.WARN, "InputMonitor.pilferPointers 拿不到，边缘手势无法从窗口手里接管", it)
            }.getOrNull()

            return InstallReport(
                1,
                notes = listOf(
                    "handlers=" + handlerCount(cls, instance),
                    "monitor=" + (inputMonitor != null),
                    "pilfer=" + (pilferMethod != null),
                ),
            )
        }
    }

    /**
     * **把这次触摸从窗口手里拿走**（`InputMonitor.pilferPointers()`）。
     *
     * 为什么必须有它、以及为什么只 return true 不够：见类注释「消费不等于接管」。
     * 调用时机由调用方决定（判定「这次边缘手势归我」之后立刻调），本函数只保证：
     *
     *  - **每个手势最多一次**（DOWN 时复位）—— 重复调没有额外效果，只是白付一次 IPC；
     *  - **失败不抛异常**：拿不到方法 / 拿不到监视器 / 系统拒绝，都只记一次诊断，
     *    返回 false，调用方照常继续（退化成老行为，不是「手势不生效」）。
     *
     * 只在**输入分发线程**上调（与 [dispatch] 同线程）。
     *
     * @return 是否真的抢到手（false = 系统手势不会被取消）
     */
    fun pilfer(): Boolean {
        if (pilferedThisGesture) return true
        pilferedThisGesture = true
        val method = pilferMethod
        if (method == null) {
            failPilfer("InputMonitor.pilferPointers 不存在")
            return false
        }
        val m = inputMonitor
        if (m == null) {
            failPilfer("onEvent 还没给过 InputMonitor")
            return false
        }
        return try {
            method.invoke(m)
            pilferCount++
            dgPilfered = true
            true
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            failPilfer(cause.javaClass.simpleName + ": " + cause.message)
            false
        }
    }

    /**
     * 抢失败只记一次日志。
     *
     * 为什么不静默：抢失败的表现是「我们的浮层出来了，系统手势也照旧执行」——
     * 与「抢成功但我们判定错了」在用户眼里一模一样，而两者的修法完全不同。
     * 一条日志就足以分开它们。次数不限的原因同 [diagNegativeDevice]：
     * 失败往往是环境级的（权限/版本），一条就够，但也要能看出它是持续的还是一次的。
     */
    private fun failPilfer(reason: String) {
        if (pilferFailure != null) return
        pilferFailure = reason
        Log.w(TAG, "抢指针失败（这次手势不会取消系统手势）：$reason")
    }

    /** 把我们的 proxy 挪到列表头部 —— 列表顺序就是处理优先级。 */
    private fun prioritize(cls: Class<*>, instance: Any, proxy: Any) {
        runCatching {
            val field = cls.getDeclaredField("mEventHandlers").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val list = field.get(instance) as? MutableList<Any> ?: return
            list.removeAll { it === proxy }
            list.add(0, proxy)
        }
    }

    /** 安装时登记的宿主对象与 proxy，卸载时按身份摘除用。 */
    private class Registration(val cls: Class<*>, val instance: Any, val proxy: Any)

    @Volatile
    private var registered: Registration? = null

    /**
     * 从宿主的 handler 链里摘掉 proxy，复位安装状态。
     *
     * 不摘的后果：热重载后新代码再注册一个 proxy，旧 proxy（旧 classloader）
     * 留在 `mEventHandlers` 里继续在输入线程上跑，旧 classloader 也回收不掉。
     * 先试宿主自己的 `unRegisterEventHandler`（反编译所见拼写，大小写不敏感地找），
     * 再按身份兜底清一遍列表。
     */
    fun uninstall() {
        synchronized(this) {
            val r = registered ?: run {
                installed = false
                return
            }
            runCatching {
                r.cls.methods.firstOrNull { it.name.equals("unregisterEventHandler", ignoreCase = true) && it.parameterCount == 1 }
                    ?.invoke(r.instance, r.proxy)
            }
            runCatching {
                val field = r.cls.getDeclaredField("mEventHandlers").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (field.get(r.instance) as? MutableList<Any>)?.removeAll { it === r.proxy }
            }
            registered = null
            installed = false
            inputMonitor = null
            pilferMethod = null
        }
    }

    private fun handlerCount(cls: Class<*>, instance: Any): Int = runCatching {
        val field = cls.getDeclaredField("mEventHandlers").apply { isAccessible = true }
        (field.get(instance) as? List<*>)?.size ?: -1
    }.getOrDefault(-1)

    /**
     * 分发给订阅者。
     *
     * 这个函数在输入线程上被高频调用，所以：不分配、不打日志、不加锁
     * （listeners 是 CopyOnWriteArrayList，遍历不加锁）。
     */
    private fun dispatch(event: MotionEvent?): Boolean {
        if (event == null) return false
        eventCount++
        // 只认触摸屏事件；鼠标/轨迹球之类直接放过
        // ⚠️ 不要按 deviceId 过滤。
        //
        // 这里原来有一条 `if (event.deviceId < 0) return false`，理由是「鼠标/轨迹球放过」——
        // 纯粹是猜的，没有证据。而 `adb shell input motionevent` 注入的事件 deviceId 就是 -1，
        // 结果是我们自己把可注入的测试事件丢掉了，导致「注入验不了手势」这个结论一直站不住：
        // 分不清是「通道没给」还是「我们自己丢了」。
        //
        // 这条通道挂的是触摸输入监视器，本来就只有触摸事件；真要区分输入源应该看 `event.source`，
        // 而不是 deviceId。现在保留计数仅供诊断，不再拦截。
        if (event.deviceId < 0) diagNegativeDevice++

        // ── 诊断：每个手势汇总一条日志 ──
        // 存在的理由：装机后「划了但什么都没发生」时，日志里既没有「手势被识别」
        // 也没有「手势被拒绝」，无法区分「事件没到我们这里」和「到了但判定没过」。
        // 这两者的排查方向完全不同（前者要查通道，后者要查参数）。
        // 代价是每个手势一次整数自增 + 一次 Log.i，不在每帧路径上。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dgMove = 0
                dgDownX = event.x
                dgDownY = event.y
                // **`edgeFlags` 的实测点**（2026-09-15 加，只测量、不影响任何判定）。
                //
                // 为什么测它：Flyme 判断「这次起手是在系统手势条里」用的**不是自造的 dp**，
                // 而是框架给的 `MotionEvent.edgeFlags & EDGE_NAV_BAR(256)`
                // （`com/android/launcher3/Utilities.java:76`，以及它那一串 NavBar*TouchController）。
                // 我们此前是自拍了一个 24dp 让距 —— 那条已经删了（依据是死规则），
                // 如果这个 flag 在我们的通道里真的有值，就该用它来替代几何判断。
                //
                // 判据：从**屏幕最底部 1~2px** 起手划一次、再从**屏幕中部**划一次，
                // 比较这两行的 `edge=0x…`：出现 `0x100`（或底边非零）就说明这条信号可用。
                dgDownEdgeFlags = event.edgeFlags
                dgDeviceId = event.deviceId
                dgConsumed = false
                // 每个手势抢一次指针（见 [pilfer]）
                pilferedThisGesture = false
                // 位移的绝对值峰值（相对 DOWN 点）。存在的理由见下面 UP 那条日志。
                dgMaxAbsDx = 0f
                dgMaxAbsDy = 0f
                dgPilfered = false
            }
            MotionEvent.ACTION_MOVE -> {
                dgMove++
                val adx = kotlin.math.abs(event.x - dgDownX)
                val ady = kotlin.math.abs(event.y - dgDownY)
                if (adx > dgMaxAbsDx) dgMaxAbsDx = adx
                if (ady > dgMaxAbsDy) dgMaxAbsDy = ady
            }
        }

        var consumed = false
        for (l in listeners) {
            if (l.onTouch(event)) {
                consumed = true
                break
            }
        }
        if (consumed) dgConsumed = true

        val a = event.actionMasked
        // 诊断日志按需打开：输入线程上每个手势拼一长串字符串 + Log，
        // 违背本函数「不分配、不打日志」的约定。平时不打；排障时
        // `adb shell setprop log.tag.FP-SwipeHub DEBUG` 后重载即可（安装时读一次，见 [diagLog]）。
        if (diagLog && (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)) {
            // 为什么要把「最大横/纵位移」打出来：两个手势的防误触闸门都是一条**位移判据**
            // （侧滑索引：严格单轴 4×slop + 3:1；底角扇形：4×slop 后才判方向 + 消费前复判方向锥）。
            // 没有这两个数，真机上「划了但没反应」只能靠猜；有了它们，用户划一次就能从日志算出
            // 自己那次手势**该不该**过闸（一次整数/浮点比较），从而区分「闸门太严」和「通道没给事件」。
            Log.i(
                TAG,
                "手势：DOWN(" + dgDownX.toInt() + "," + dgDownY.toInt() + ") dev=" + dgDeviceId +
                    " MOVE=" + dgMove + " 最大位移=(" + dgMaxAbsDx.toInt() + "," + dgMaxAbsDy.toInt() + ")" +
                    " UP(" + event.x.toInt() + "," + event.y.toInt() + ")" +
                    " 被消费=" + dgConsumed + " 抢指针=" + dgPilfered +
                    " edge=0x" + Integer.toHexString(dgDownEdgeFlags) +
                    " 累计=" + eventCount
            )
        }
        if (diagLog && a == MotionEvent.ACTION_DOWN) Log.i(TAG, "手势起点 deviceId=" + dgDeviceId)
        return consumed
    }

    // ── 诊断状态（只在输入线程读写，无需同步）──
    private var dgMove = 0
    private var dgDownX = 0f
    private var dgDownY = 0f

    /** 本次手势 DOWN 的 `edgeFlags`（只测量用，见 DOWN 分支里的说明）。 */
    private var dgDownEdgeFlags = 0
    private var dgMaxAbsDx = 0f
    private var dgMaxAbsDy = 0f
    private var dgDeviceId = -1
    private var dgConsumed = false
    private var dgPilfered = false

    /** 因 deviceId < 0 而被丢掉的次数。用来区分「通道没给事件」和「事件被我们过滤了」。 */
    @Volatile
    var diagNegativeDevice: Long = 0
        private set

    /** 诊断用：累计分发的事件数。 */
    val dispatched: Long get() = eventCount

    /** 主线程判断，用于日志（输入线程上不做这件事）。 */
    fun isOnMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
}
