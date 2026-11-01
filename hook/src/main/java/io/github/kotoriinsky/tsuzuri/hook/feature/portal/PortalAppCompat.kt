package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.PortalPageAdmission
import io.github.kotoriinsky.tsuzuri.core.compat.PortalPageConfigCompat
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.util.concurrent.atomic.AtomicInteger

/**
 * 为 ROM 没有下发 PageConfig 的应用补一份通用文本抓取准入。
 *
 * 只 hook 服务端 `getPageConfig(Token)` 的返回阶段，不把模块注入第三方 App。已有任何 ROM
 * feature/catcher 都完整保留；官方 `text_pick` target 被关闭或状态读不清时也不注入。
 */
object PortalAppCompat : Feature(Catalog.require(Catalog.SCREEN_TEXT)) {

    override val installId: String get() = "screen_text:app_compat"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = !isSystemServer && (processName == io.github.kotoriinsky.tsuzuri.core.ScopeTarget.CONTENT_CATCHER.packageName)

    override fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs) = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(prefs, Catalog.SCREEN_TEXT, "app_compat")

    private const val SERVICE_CLASS = "com.miui.contentcatcher.ContentCatcherService"
    private const val TOKEN_CLASS = "miui.contentcatcher.sdk.Token"

    private val injected = AtomicInteger(0)
    private val kept = AtomicInteger(0)
    private val failed = AtomicInteger(0)

    override fun install(ctx: HookContext): InstallReport {
        val serviceClass = loadClass(SERVICE_CLASS, ctx) ?: return InstallReport(
            hooked = 0,
            missing = listOf(SERVICE_CLASS),
        )
        val tokenClass = loadClass(TOKEN_CLASS, ctx) ?: return InstallReport(
            hooked = 0,
            missing = listOf(TOKEN_CLASS),
        )
        val compat = try {
            PortalPageConfigCompat.resolve(ctx.classLoader)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "传送门 PageConfig 结构不兼容，跳过通用准入", t)
            return InstallReport(
                hooked = 0,
                missing = listOf("PageConfig/FeatureInfo/ConfigHelper"),
                notes = listOf("compat=${t.javaClass.simpleName}"),
            )
        }

        val methods = serviceClass.declaredMethods.filter {
            it.name == "getPageConfig" &&
                it.parameterTypes.contentEquals(arrayOf(tokenClass))
        }
        if (methods.size != 1) {
            ctx.log(Log.WARN, "getPageConfig(Token) 命中 ${methods.size} 个，拒绝猜测")
            return InstallReport(
                hooked = 0,
                missing = listOf("$SERVICE_CLASS.getPageConfig($TOKEN_CLASS)"),
                notes = listOf("candidates=${methods.size}"),
            )
        }

        return try {
            val method = methods.single().apply { isAccessible = true }
            track(
                ctx.module.hook(method).intercept { chain ->
                    val original = chain.proceed()
                    try {
                        val state = compat.inspect(original)
                        val verdict = PortalPageAdmission.decide(
                            enabled = true,
                            textTargetEnabled = compat.textTargetEnabled(),
                            pageConfigPresent = state.present,
                            features = state.features,
                            catchers = state.catchers,
                        )
                        if (verdict == PortalPageAdmission.Verdict.INJECT_TEXT_FALLBACK) {
                            val replacement = compat.buildTextFallback()
                            logRuntimeCount(ctx, "已补通用文本准入", injected.incrementAndGet())
                            replacement
                        } else {
                            kept.incrementAndGet()
                            original
                        }
                    } catch (t: Throwable) {
                        val count = failed.incrementAndGet()
                        if (count <= DIAG_LIMIT || count % DIAG_EVERY == 0) {
                            ctx.log(Log.WARN, "构造通用文本准入失败，保留 ROM 原配置（第 $count 次）", t)
                        }
                        original
                    }
                },
            )
            ctx.log(Log.INFO, "已挂上 getPageConfig(Token)：仅为明确无配置页面补文本抓取")
            InstallReport(
                hooked = 1,
                notes = listOf("runtimeCounters=injected/kept/failed"),
            )
        } catch (t: Throwable) {
            ctx.log(Log.ERROR, "hook 传送门页面准入失败", t)
            InstallReport(
                hooked = 0,
                missing = listOf("$SERVICE_CLASS.getPageConfig"),
                notes = listOf("exception=${t.javaClass.simpleName}"),
            )
        }
    }

    private fun loadClass(name: String, ctx: HookContext): Class<*>? = try {
        Class.forName(name, false, ctx.classLoader)
    } catch (t: Throwable) {
        ctx.log(Log.WARN, "class not found: $name，跳过", t)
        null
    }

    private fun logRuntimeCount(ctx: HookContext, action: String, count: Int) {
        if (count <= DIAG_LIMIT || count % DIAG_EVERY == 0) {
            ctx.log(
                Log.INFO,
                "$action（第 $count 次；kept=${kept.get()} failed=${failed.get()}）",
            )
        }
    }

    private const val DIAG_LIMIT = 10
    private const val DIAG_EVERY = 100
}
