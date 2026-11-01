package io.github.kotoriinsky.tsuzuri.hook

import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule

/**
 * 安装一个特性时后端能拿到的一切。
 *
 * 刻意不放 `Context`：hook 进程里的 Context 属于宿主（SystemUI / 负一屏），
 * 拿它去启动组件、读资源是越界的，而且违反主线程红线（规范 §7.5）。
 */
class HookContext(
    val module: XposedModule,
    val classLoader: ClassLoader,
    /** 当前被注入的进程名，如 `com.android.systemui`。 */
    val processName: String,
    val prefs: Prefs,
    /**
     * 原始配置存储。特性要「改完立刻生效」就得注册变更监听，
     * 而 [Prefs] 是只读封装、拿不到监听入口。可能为 null（框架不支持远程配置时）。
     */
    val store: android.content.SharedPreferences? = null,
) {
    fun log(priority: Int, msg: String, t: Throwable? = null) {
        if (t == null) module.log(priority, TAG, msg) else module.log(priority, TAG, msg, t)
    }

    companion object {
        const val TAG = "FP-Hook"
    }
}

/**
 * 一次安装的结果，用于诊断页。
 *
 * 「装上了几个 hook 点」是这个项目最需要被看见的数字。
 * 它的正常值**不是 0** —— 0 通常意味着目标方法在 OTA 后改名了，而不是「不需要装」。
 */
data class InstallReport(
    /** 真正装上并生效的 hook 点数量。 */
    val hooked: Int,
    /** 想装但没匹配到目标的方法/类名候选。 */
    val missing: List<String> = emptyList(),
    /** 任何值得写进诊断页的备注。 */
    val notes: List<String> = emptyList(),
) {
    val isEffective: Boolean get() = hooked > 0

    fun describe(): String = buildString {
        append("hooked=").append(hooked)
        if (missing.isNotEmpty()) append(", missing=").append(missing.joinToString("|"))
        if (notes.isNotEmpty()) append(", notes=").append(notes.joinToString("|"))
    }
}

/**
 * 一个特性在后端的实现。
 *
 * 铁律：
 *  - [install] 必须幂等。重复安装（多包回调、热重载）不能装出两套 hook。
 *  - [install] **绝不能让异常逃逸** —— 这里崩了就是 SystemUI 崩，用户看到黑屏。
 *    统一走 [safeInstall]。
 *  - [uninstall] 把该特性装的 hook **全部**卸掉，关态必须与未安装模块完全一致。
 *  - 关态不是「装一个 no-op hook」，而是**根本不装**（规范 §9）。
 */
abstract class Feature(val spec: FeatureSpec) {

    val key: String get() = spec.key

    private val handles = ArrayList<HookHandle>(8)

    /** 登记一个 hook 句柄，[uninstall] 时统一释放。 */
    protected fun track(handle: HookHandle) {
        handles.add(handle)
    }

    abstract fun install(ctx: HookContext): InstallReport

    /**
     * 本实现是否在给定进程生效。默认 true（进程筛选交给 [FeatureSpec.scope]）。
     *
     * 为什么需要这个钩子：**一个键对应多份实现**的双进程特性（如口令上岛：
     * system_server 观察剪贴板 / SystemUI 代发岛通知）共用同一个 spec，
     * 而 scope 表达的是「这个键在哪些进程有意义」，表达不了「这一份实现归哪个进程」——
     * 不加这道闸，两份实现会在两个进程里都被当成候选，跑错进程的那份必然
     * `class_not_found`（它的目标类根本不在这个进程里）。
     */
    open fun supportsProcess(processName: String, isSystemServer: Boolean): Boolean = true

    /**
     * 注册表里区分「同一进程里的不同实现」用的身份。默认 = [key]。
     *
     * 识屏三类（2026-10-25 重组）里，一个键由多个实现分担，而且好几个落在同一个进程
     * （contentextension 里就有识图选区、图片卡片、原媒体、胶囊四份）。注册表原来按键去重，
     * 第二份会被 `already installed, skip` 静默跳过 —— 共享键的实现必须各给一个不同的身份。
     */
    open val installId: String get() = key

    /**
     * 这份实现在当前配置下要不要装。默认 = 总开关。
     *
     * 共享键的实现按自己负责的子项判断（全关 = 不装 hook，规范 §9），
     * 也可能跨两个键（例如图片卡片同时有图片类与文字类的按钮）。
     */
    open fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs): Boolean = prefs.isEnabled(key)

    /** 已装 hook 数，用于诊断。 */
    val installedHooks: Int get() = handles.size

    /** 卸载。重复调用必须安全。子类可重写以先摘掉自己的监听器。 */
    open fun uninstall() {
        for (h in handles) {
            try {
                h.unhook()
            } catch (_: Throwable) {
                // 卸载失败没有补救手段，也不能因此抛出去
            }
        }
        handles.clear()
    }

    companion object {
        /** 安装的统一入口：保证异常不逃逸，且把失败变成一条可诊断的报告。 */
        fun safeInstall(f: Feature, ctx: HookContext): InstallReport = try {
            f.install(ctx)
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "feature ${f.key} install crashed", t)
            InstallReport(0, notes = listOf("exception=" + t.javaClass.simpleName))
        }
    }
}
