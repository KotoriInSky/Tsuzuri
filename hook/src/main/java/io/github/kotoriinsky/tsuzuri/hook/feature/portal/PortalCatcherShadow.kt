package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Process
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 识图拿源位图（system_server 一端）。
 *
 * 来源应用加载传送门抓取器时，会向包管理器查询 `com.miui.contentcatcher` 的 ApplicationInfo
 * （`ActivityThread.getPackageInfo`，flags 含 `GET_SHARED_LIBRARY_FILES`），再按它构建 ClassLoader。
 * 这里在应答里把独立安装的抓取 APK（`:capture`）追加进 `sharedLibraryFiles`：
 * `LoadedApk.makePaths` 会把它插到加载路径最前面，于是同名的 `ViewContentFetcher` 先被找到。
 *
 * 收窄条件（缺一不追加）：
 *  - 查询的包是 ContentCatcher，且请求带 `GET_SHARED_LIBRARY_FILES`；
 *  - 调用方是普通应用（appId ≥ 10000），不是 ContentCatcher 自己；
 *  - 抓取 APK 已安装且文件存在。
 * 应答对象先复制再改，不动包管理器内部的任何状态。
 */
internal object PortalCatcherShadow : Feature(Catalog.require(Catalog.SCREEN_IMAGE)) {

    override val installId: String get() = "screen_image:source_bitmap"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = isSystemServer

    override fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs) = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(prefs, Catalog.SCREEN_IMAGE, "source_bitmap")

    private const val PMS_BASE = "com.android.server.pm.IPackageManagerBase"
    private const val CATCHER_PKG = "com.miui.contentcatcher"
    private const val CAPTURE_PKG = "io.github.kotoriinsky.tsuzuri.capture"
    private const val GET_SHARED_LIBRARY_FILES = 0x400L

    private var handle: XposedInterface.HookHandle? = null
    private val injected = AtomicInteger()
    private val diag = AtomicInteger()
    private const val DIAG_LIMIT = 30

    override fun install(ctx: HookContext): InstallReport {
        val method: Method = try {
            ctx.classLoader.loadClass(PMS_BASE).getDeclaredMethod(
                "getApplicationInfo",
                String::class.java, java.lang.Long.TYPE, Integer.TYPE,
            )
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "识图源位图：找不到 $PMS_BASE#getApplicationInfo（OTA 改了签名？）", t)
            return InstallReport(0, missing = listOf("$PMS_BASE#getApplicationInfo"))
        }
        method.isAccessible = true

        handle = try {
            ctx.module.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    rewrite(chain.thisObject, method, chain.args, result as? ApplicationInfo, ctx) ?: result
                } catch (t: Throwable) {
                    ctx.log(Log.WARN, "识图源位图：改写应答失败，原样返回", t)
                    result
                }
            }
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "识图源位图：hook getApplicationInfo 失败", t)
            return InstallReport(0, missing = listOf("$PMS_BASE#getApplicationInfo"), notes = listOf("hook_threw"))
        }
        ctx.log(Log.INFO, "识图源位图已装上：应用加载 ContentCatcher 抓取器时追加 $CAPTURE_PKG")
        return InstallReport(1)
    }

    private fun rewrite(
        self: Any?,
        method: Method,
        args: List<Any?>,
        info: ApplicationInfo?,
        ctx: HookContext,
    ): ApplicationInfo? {
        if (args.firstOrNull() != CATCHER_PKG) return null
        val caller = Binder.getCallingUid()
        val flags = args.getOrNull(1) as? Long
        // 诊断：前若干次 ContentCatcher 查询无论是否追加都记一行，便于判断卡在哪个条件
        fun skip(why: String): ApplicationInfo? {
            if (diag.incrementAndGet() <= DIAG_LIMIT) {
                ctx.log(Log.INFO, "识图源位图：uid=$caller flags=0x${flags?.toString(16)} 不追加（$why）")
            }
            return null
        }
        if (info == null) return skip("应答为空")
        if (flags == null || flags and GET_SHARED_LIBRARY_FILES == 0L) return skip("未请求共享库文件")
        if (appId(caller) < Process.FIRST_APPLICATION_UID) return skip("调用方不是普通应用")
        if (appId(caller) == appId(info.uid)) return skip("调用方是 ContentCatcher 自己")
        val userId = args.getOrNull(2) as? Int ?: return skip("userId 缺失")

        val capture = capturePath(self, method, userId) ?: return skip("抓取 APK 未安装或不可读")
        if (info.sharedLibraryFiles?.contains(capture) == true) return skip("已包含")
        val out = ApplicationInfo(info)
        out.sharedLibraryFiles = arrayOf(capture) + (info.sharedLibraryFiles ?: emptyArray())
        val n = injected.incrementAndGet()
        if (n <= 20 || n % 50 == 0) ctx.log(Log.INFO, "识图源位图：为 uid=$caller 追加抓取 APK（第 $n 次）")
        return out
    }

    /** 抓取 APK 的路径。以 system 身份查，避开包可见性过滤；没装或文件不在返回 null。 */
    private fun capturePath(self: Any?, method: Method, userId: Int): String? {
        val token = Binder.clearCallingIdentity()
        return try {
            val ai = method.invoke(self, CAPTURE_PKG, 0L, userId) as? ApplicationInfo ?: return null
            ai.sourceDir?.takeIf { it.endsWith(".apk") && File(it).isFile }
        } catch (_: Throwable) {
            null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun appId(uid: Int) = uid % 100_000

    override fun uninstall() {
        try {
            handle?.unhook()
        } catch (_: Throwable) {
        }
        handle = null
    }
}
