package io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.UserHandle
import android.util.Log
import android.view.View
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ContextSuggestion
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport

/**
 * 音乐建议长按拉起的「打开方式」框：只认带 [ContextSuggestion.EXTRA_LISTEN_PICK] 的请求，
 * 把候选筛成白名单，并且不留任何默认应用记录（「始终」与上次选择都不写）——
 * 请求是桌面启动意图，写进去的偏好会影响面很大。其余请求一律原样放行。
 */
internal object ListenPickerFilter : Feature(Catalog.require(Catalog.CONTEXT_MUSIC)) {
    private const val CONTROLLER = "com.android.intentresolver.ResolverListController"
    private const val ACTIVITY = "com.android.intentresolver.ResolverActivity"
    private val selecting = ThreadLocal<Boolean>()
    private const val DIRECT_BOOT_ANY = PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE

    override val installId get() = "${key}.picker"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName.startsWith("com.android.intentresolver")

    override fun install(ctx: HookContext): InstallReport {
        val missing = ArrayList<String>()
        val controller = runCatching { ctx.classLoader.loadClass(CONTROLLER) }.getOrNull()
        val activity = runCatching { ctx.classLoader.loadClass(ACTIVITY) }.getOrNull()
        val resolve = controller?.declaredMethods?.firstOrNull {
            it.name == "getResolversForIntentAsUser" && it.parameterTypes.size == 3 && it.parameterTypes[1] == List::class.java
        }
        val remember = controller?.declaredMethods?.firstOrNull { it.name == "setLastChosen" && it.parameterTypes.size == 3 }
        val select = activity?.declaredMethods?.firstOrNull {
            it.name == "onTargetSelected" && it.parameterTypes.size == 2 && it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (resolve == null) missing += "$CONTROLLER#getResolversForIntentAsUser"
        if (remember == null) missing += "$CONTROLLER#setLastChosen"
        if (select == null) missing += "$ACTIVITY#onTargetSelected"
        // 少了任一处都不装：只过滤不屏蔽写入，会让「始终」真的写进系统。
        if (missing.isNotEmpty()) return InstallReport(0, missing = missing)

        val dedupe = controller!!.declaredMethods.firstOrNull { it.name == "addResolveListDedupe" && it.parameterTypes.size == 3 }
        val pmField = runCatching { controller.getDeclaredField("mpm").apply { isAccessible = true } }.getOrNull()
        track(ctx.module.hook(resolve!!).intercept { chain ->
            val proceeded = chain.proceed()
            val intent = (chain.args[1] as? List<*>)?.firstOrNull { (it as? Intent)?.hasExtra(ContextSuggestion.EXTRA_LISTEN_PICK) == true } as? Intent
                ?: return@intercept proceeded
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val result = (proceeded as? MutableList<Any?>) ?: ArrayList()
                // 原查询带 MATCH_DEFAULT_ONLY，多数桌面入口不声明 DEFAULT 类别（真机只剩 Spotify 一个）。
                // 去掉该标志补查白名单，经原生的去重方法加入，保持 ResolvedComponentInfo 的构造与原逻辑一致。
                val pm = pmField?.get(chain.thisObject) as? PackageManager
                if (pm != null && dedupe != null) {
                    val query = pm.javaClass.getMethod("queryIntentActivitiesAsUser", Intent::class.java, Int::class.javaPrimitiveType, UserHandle::class.java)
                    @Suppress("UNCHECKED_CAST")
                    val extra = (query.invoke(pm, intent, DIRECT_BOOT_ANY, chain.args[2]) as? List<ResolveInfo>).orEmpty()
                        .filter { it.activityInfo?.packageName in ContextSuggestion.LISTEN_APPS }
                    dedupe.invoke(chain.thisObject, result, intent, extra)
                }
                val name = result.firstOrNull()?.javaClass?.getDeclaredField("name")?.apply { isAccessible = true }
                if (name != null) result.removeAll { (name.get(it) as? ComponentName)?.packageName !in ContextSuggestion.LISTEN_APPS }
                ctx.log(Log.INFO, "收听应用选择 候选=${result.size}")
                result
            }.getOrElse {
                ctx.log(Log.WARN, "收听应用选择过滤失败：${it.javaClass.simpleName}")
                proceeded
            }
        })
        track(ctx.module.hook(select!!).intercept { chain ->
            val ours = (chain.thisObject as? Activity)?.intent?.hasExtra(ContextSuggestion.EXTRA_LISTEN_PICK) == true
            if (!ours) return@intercept chain.proceed()
            selecting.set(true)
            try {
                chain.proceed(arrayOf(chain.args[0], false))
            } finally {
                selecting.remove()
            }
        })
        track(ctx.module.hook(remember!!).intercept { chain ->
            if (selecting.get() == true) null else chain.proceed()
        })
        // 标题（「打开方式」之类）对这个场景没信息量，用户要求去掉；拿不到方法只是标题还在，不影响选择。
        activity!!.declaredMethods.firstOrNull { it.name == "onPostListReady" && it.parameterTypes.size == 3 }?.let { ready ->
            track(ctx.module.hook(ready).intercept { chain ->
                val result = chain.proceed()
                val host = chain.thisObject as? Activity
                if (host?.intent?.hasExtra(ContextSuggestion.EXTRA_LISTEN_PICK) == true) runCatching {
                    @Suppress("DiscouragedApi")
                    val id = host.resources.getIdentifier("title", "id", host.packageName)
                    if (id != 0) host.findViewById<View>(id)?.visibility = View.GONE
                }
                result
            })
        }
        ctx.log(Log.INFO, "收听应用选择接入 hooked=$installedHooks")
        return InstallReport(installedHooks)
    }
}
