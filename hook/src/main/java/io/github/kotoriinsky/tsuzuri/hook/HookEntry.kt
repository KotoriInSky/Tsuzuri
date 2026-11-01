package io.github.kotoriinsky.tsuzuri.hook

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 唯一入口（`META-INF/xposed/java_init.list` 里声明的就是它）。
 *
 * 铁律：
 *  - 本类只做分发，不写业务逻辑。
 *  - 构造与静态初始化里**禁止调用任何框架 API**（会抛 "Framework not attached"）。
 *  - 安装必须幂等（最多四条路径会走到 [installOnce]，靠 `HookRegistry` 的 installed 集合挡住）。
 *
 * ## 四条安装路径，为什么需要这么多
 *
 * 这是被设备实测逼出来的，不是防御性编程：
 *
 * | 路径 | 实测结果 |
 * | --- | --- |
 * | [onModuleLoaded] + 延迟取 ClassLoader | ✅ **SystemUI 唯一真正生效的一条** |
 * | [onPackageLoaded] | ❌ SystemUI 上不回调 |
 * | [onPackageReady] | ❌ SystemUI 上不回调 |
 * | [onSystemServerStarting] | ✅ 生效（system_server，日志已确认） |
 *
 * 具体现象：SystemUI 连续四次软重启，日志里**只有** `onModuleLoaded` 那一行，
 * 两个 package 回调一条都没有 —— 说明 LSPosed 对 SystemUI 的注入时机与普通应用不同，
 * 拿不到「包已就绪」这个生命周期点。
 *
 * 所以 [onModuleLoaded] 不能只用来记日志：那个回调**没有 ClassLoader**，
 * 只能先排队，等宿主起来之后通过 `ActivityThread.currentApplication()` 把它的取出来。
 *
 * 后两条 package 回调仍然保留：它们在普通应用进程（例如负一屏）上是正常工作的，
 * 多挂一条的成本只是一次幂等检查。**哪条先到就用哪条。**
 */
class HookEntry : XposedModule() {

    @Volatile private var processName: String = "unknown"

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        log(
            Log.INFO, TAG,
            "loaded into process=$processName systemServer=${param.isSystemServer}"
        )
        // native 入口探针（LSPosed 的 META-INF/xposed/native_init.list）。
        //
        // **框架不会自动 dlopen 那行库名** —— 官方 wiki 的原文是「把库名写进 native_init，
        // 然后由模块自己 System.loadLibrary」。所以必须在这里显式加载一次，否则 .so 永远进不来。
        //
        // 两个刻意的收窄，都是 2026-09-14 那次桌面崩溃之后定的：
        //  - 只在 SystemUI 里试（第一个验证点；就算加载出问题，炸的也只是 SystemUI 自己重启）；
        //  - 必须 persist.fp.native.probe=1 才做，默认构建零足迹（不加载、不探测）。
        if (param.processName == NATIVE_PROBE_PROCESS && isNativeProbeEnabled()) {
            loadNativeProbe()
        }
        // system_server 走 onSystemServerStarting（那个回调有 ClassLoader，且实测会触发）
        if (!param.isSystemServer) scheduleDeferredInstall()
    }

    /** 读 `persist.fp.native.probe`；读不到 / 反射失败一律按「关」处理。 */
    private fun isNativeProbeEnabled(): Boolean = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, NATIVE_PROBE_PROP, "0") as? String) == "1"
    }.getOrDefault(false)

    private fun loadNativeProbe() {
        runCatching {
            System.loadLibrary("fpnative")
            log(Log.INFO, TAG, "native 探针库已加载（libfpnative.so）")
        }.onFailure {
            log(Log.WARN, TAG, "native 探针库加载失败（$NATIVE_PROBE_PROCESS）", it)
        }
    }

    /**
     * 等宿主起来，取它的 ClassLoader，然后安装。
     *
     * 为什么要重试而不是 `post` 一次：`onModuleLoaded` 发生的时刻早于
     * `ActivityThread` 把 Application 挂上，那时 `currentApplication()` 还是 null。
     * 300ms 一次、最多 12 次（≈3.6s）够覆盖最慢的启动；超时就放弃并记一条 warning ——
     * **不抛异常，也不无限重试**。
     */
    private fun scheduleDeferredInstall() {
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                val cl = SystemUiEnv.context()?.classLoader
                if (cl != null) {
                    installOnce(processName, cl)
                    return
                }
                if (++attempts < MAX_ATTEMPTS) {
                    handler.postDelayed(this, RETRY_INTERVAL_MS)
                } else {
                    log(
                        Log.WARN, TAG,
                        "拿不到宿主 ClassLoader，本进程不安装（process=$processName）"
                    )
                }
            }
        }
        handler.postDelayed(task, RETRY_INTERVAL_MS)
    }

    /**
     * 包加载回调。
     *
     * **不做 `isFirstPackage` 判断** —— 那是多余的（`installed` 集合已保证只装一次），
     * 而且它正是当初 SystemUI 装不上的原因之一。
     */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        installOnce(param.packageName, param.defaultClassLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        installOnce(param.packageName, param.classLoader)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        runCatching {
            hostClassLoader = param.classLoader
            HookRegistry.installForProcess(
                module = this,
                classLoader = param.classLoader,
                processName = processName,
                isSystemServer = true,
            )
        }.onFailure {
            log(Log.ERROR, TAG, "install failed in system_server", it)
        }
    }

    /** 幂等安装。重复调用由 `HookRegistry` 的 installed 集合挡住。 */
    private fun installOnce(packageName: String, classLoader: ClassLoader) {
        runCatching {
            hostClassLoader = classLoader
            HookRegistry.installForProcess(
                module = this,
                classLoader = classLoader,
                processName = processName,
                // ⚠️ 不能硬编码 false：system_server 里安装系统 Provider 时也会触发
                // onPackage* 回调（实测 2026-09-24：installSystemProviders → getClassLoader
                // → onPackageReady，packageName=providers.settings），误判成普通进程会让
                // Presenter 装进 system_server（NotificationManager 未就绪直接 NPE）。
                // 判据用进程名 == "system"（onModuleLoaded 的 param.processName，实测就是它）；
                // uid==SYSTEM_UID 不行 —— com.miui.contentcatcher 等系统服务进程同样是
                // uid 1000，误判会让它们也装 Watcher（实测 19:11 白装了一回）。
                isSystemServer = processName == "system",
            )
        }.onFailure {
            log(Log.ERROR, TAG, "install failed in $processName (pkg=$packageName)", it)
        }
    }

    /**
     * 热重载前的回调：返回 true 表示「这次重载允许继续」。
     *
     * 为什么要显式实现：libxposed API 102 的热重载有三个前提 ——
     *  ① `java_init.list` 里**恰好一个** Java 入口类（本项目满足）；
     *  ② 重写本方法并返回 true（默认实现返回 false = 永远不热重载）；
     *  ③ 在 [onHotReloaded] 里**重新安装 hook**（框架默认只 unhook，模块会变空）。
     * 三缺一，`XposedService.hotReloadModule()` 在真机上就会回 UNSUPPORTED / FAILED ——
     * 这正是 2026-10-17 之前「点重载没反应」的根因（当时 `autoHotReload=false` 且
     * 两个回调都没实现）。
     *
     * 这里不做任何状态保存：本模块的配置在 LSPosed 的 RemotePreferences 里，
     * 重载后重新读即可；把状态塞进 Bundle 反而多一条可能不一致的副本。
     *
     * ⚠️ **必须先 [HookRegistry.uninstallAll]**（2026-10-22 真机实锤）：框架只撤 hook，
     * 模块自己注册的广播接收器、prefs 监听、Handler 定时任务都留在进程里，而且跑的是
     * **旧代码**。之前每热重载一次，SystemUI 就多挂一个口令岛接收器（`dumpsys activity
     * broadcasts` 见两个 BroadcastFilter），同一次复制被新旧两份代码各处理一遍、同 id 互相覆盖
     * —— 日志里一条 `template=18` 一条 `titleSp=17`，屏幕上显示的是后到的旧样式。
     * 这个回调跑在**旧**代码里，正是它清理自己的唯一时机。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "hot reload 请求：process=$processName，先卸载旧代码注册的资源")
        runCatching { HookRegistry.uninstallAll() }.onFailure {
            // 卸不干净也要放行重载：不放行等于这次装机完全不生效，比多留一个接收器更糟
            log(Log.WARN, TAG, "hot reload 前卸载失败：process=$processName", it)
        }
        return true
    }

    /**
     * 热重载完成：**必须重新安装**，否则本进程里模块等于没装（框架会先 unhook 全部旧 hook）。
     *
     * 用 `param.defaultClassLoader`（宿主进程的 ClassLoader）而不是模块自己的：
     * hook 目标类都属于宿主；模块代码已经被框架换成了新 ClassLoader 里的版本。
     * 安装走同一个 [HookRegistry.installForProcess]，它的幂等集合在重载后是新的，
     * 因此会真正重装一遍。
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        processName = param.processName
        log(Log.INFO, TAG, "hot reload 完成：process=$processName，重装 hook")
        // ⚠️ HotReloadedParam 只继承 ModuleLoadedParam（只有 processName / isSystemServer），
        // **没有 ClassLoader**。所以用首次安装时缓存下来的宿主 ClassLoader：
        // 进程没变、宿主 ClassLoader 也没变，只有模块自己的代码被换成了新版本。
        val classLoader = hostClassLoader
            ?: SystemUiEnv.context()?.classLoader
            ?: ClassLoader.getSystemClassLoader()
        runCatching {
            hostClassLoader = classLoader
            HookRegistry.installForProcess(
                module = this,
                classLoader = classLoader,
                processName = processName,
                isSystemServer = param.isSystemServer || processName == "system",
            )
        }.onFailure {
            // 重装失败 = 这个进程里功能全没了。必须打 ERROR，并且让上层能用
            // 「用 Root 重启进程」兜底（uid/进程名已在日志里，用户能定位）。
            log(Log.ERROR, TAG, "hot reload 重装失败：process=$processName", it)
        }
    }

    companion object {
        const val TAG = "FP-Entry"
        private const val RETRY_INTERVAL_MS = 300L
        private const val MAX_ATTEMPTS = 12

        /**
         * 首次安装时拿到的宿主 ClassLoader。热重载的回调里拿不到它
         * （HotReloadedParam 只有进程名），而重装 hook 必须用同一个宿主 ClassLoader。
         */
        @Volatile private var hostClassLoader: ClassLoader? = null

        /** native 探针的开关与试验进程。见 [onModuleLoaded] 里的说明。 */
        private const val NATIVE_PROBE_PROP = "persist.fp.native.probe"
        private const val NATIVE_PROBE_PROCESS = "com.android.systemui"
    }
}
