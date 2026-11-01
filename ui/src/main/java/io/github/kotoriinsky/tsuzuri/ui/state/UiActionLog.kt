package io.github.kotoriinsky.tsuzuri.ui.state

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI 侧动作日志：诊断页下架之后，**App 这半边**的排障落点。
 *
 * ## 为什么是进程内的环形缓冲，而不是文件
 *
 * 诊断页时代的教训是「平时没人看，出事时没得看」——写文件需要清理策略、
 * 需要考虑磁盘、需要在没有事情发生时也一直滚；而这里要回答的问题从来只有
 * 「刚才那几下操作，App 都做了什么」。环形缓冲只保留最近 [CAPACITY] 条，
 * 内存上界恒定，不用任何清理策略；出事时用户从设置 → 高级进来，
 * 一眼看到时间序的动作列表，或导出成文本贴进 issue。
 *
 * 范围刻意收窄：只记**动作与它们的结局**（写盘、迁移、草稿同步、导出恢复、
 * 失败降级），不记界面状态、不记读取。hook 侧的事归 LSPosed 模块日志
 * （`ctx.log` 那条既有纪律），两边各管各的进程，正好互补。
 *
 * ## 线程安全
 *
 * 写盘动作发生在 IO 协程里（备份导出/恢复、外观存储），主线程也在写
 * （开关、滑杆）——所有访问走同一把锁。消息在写入方截断到 [MAX_LINE]：
 * 这里的消息是给人看的摘要，不是数据的容器（图标包名清单这类大值只记规模）。
 */
object UiActionLog {

    /** 一条日志。`time` 是 epoch 毫秒 —— 显示/导出时才格式化。 */
    data class Entry(val time: Long, val message: String)

    /** 容量上界。200 条足够回溯一次完整的排障会话，内存开销可忽略。 */
    private const val CAPACITY = 200

    /** 单条消息的长度上界：消息是摘要，超长的值只该记规模。 */
    private const val MAX_LINE = 200

    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()

    /** 记一条。任何线程可调；空消息与纯空白丢弃。 */
    fun log(message: String) {
        val trimmed = message.trim().take(MAX_LINE)
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            buffer.addLast(Entry(System.currentTimeMillis(), trimmed))
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
    }

    /** 快照，**新的在前**（查看器按这个顺序显示）。 */
    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList().asReversed() }

    /**
     * 导出文本：时间序（旧的在前，读 issue 时从上往下就是事情发生的顺序），
     * 一行一条 `时间  消息`。与显示共用同一份缓冲，所以导出的就是看到的。
     */
    fun export(): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val lines = synchronized(lock) { buffer.toList() }
        return lines.joinToString("\n") { fmt.format(Date(it.time)) + "  " + it.message }
    }

    /** 清空。查看器的「清空」用；无返回值 —— 缓冲本来就是易失的。 */
    fun clear() = synchronized(lock) { buffer.clear() }
}
