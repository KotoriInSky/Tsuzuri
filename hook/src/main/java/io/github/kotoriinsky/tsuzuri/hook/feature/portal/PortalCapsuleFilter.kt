package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ContentCapabilities
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Method

/**
 * 传送门胶囊显隐。
 *
 * ## 为什么全部在数据层拦，而不是把卡片 GONE
 *
 * 2026-10-26 真机实验：把收起态最底下那张卡 GONE 之后，
 * `PortalCapsuleCompat.deck()` 不按可见性过滤，停靠链断掉，剩下的卡跳到屏幕顶部。
 * 在数据层拦，卡片**根本不会被创建**，布局与原生动画看到的就是「这一项本来没有」，
 * 不会有错位、抢帧，也不用改 `deck()`。
 *
 * | 子项 | 落点 | 效果 |
 * | --- | --- | --- |
 * | NER 四类（链接 / 电话 / 地址 / 邮件） | `ClipboardUtils.generateNerEntityListByType` 返回空 | 不生成这类实体卡 |
 * | 跨设备 | `TaplusSplashFloatView.needGetRemoteDeviceList` 返回 false；`generateDeviceListByType` 返回空兜底 | 不枚举设备、不出设备卡 |
 * | 云端推荐 | `TextRecommendationUtils.tryToGetRecommendation` 短接 | **不发请求**，收起态与展开态都没有云端推荐 |
 * | 商单推荐 | `ClipboardCardUtils.getCommerceRecommendWithAsyncTask` 短接 | **不发请求** |
 * | 展开态推荐横滑 | `TaplusMainFloatView.updateRecommendCards` 改传空列表 | 只藏展开面板里那一条 |
 *
 * 云端 / 商单短接时按原生「请求返回空」的路径收尾：原生在空响应时什么都不做（不回调 onDetailReady），
 * 我们同样不回调；只额外把上一次还在飞的请求任务作废，免得它晚到的结果冒出来。
 *
 * 子项每次调用都现读配置：总开关开着时切换子项，下次触发传送门就生效。
 * 子项全关 = 不装任何 hook（[wanted]），与未安装模块一致。
 */
object PortalCapsuleFilter : Feature(Catalog.require(Catalog.RECOGNITION_LOOK)) {

    override val installId: String get() = "recognition_look:capsule_filter"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == ScopeTarget.CONTENT_EXTENSION.packageName

    /** 子项名（`recognition_look.<name>`），与 Catalog 里的声明一一对应。 */
    const val HIDE_NER_URL = "hide_ner_url"
    const val HIDE_NER_TEL = "hide_ner_tel"
    const val HIDE_NER_LOC = "hide_ner_loc"
    const val HIDE_NER_MAIL = "hide_ner_mail"
    const val HIDE_DEVICES = "hide_devices"
    const val HIDE_CLOUD = "hide_cloud"
    const val HIDE_COMMERCE = "hide_commerce"
    const val HIDE_EXPANDED_RECOMMEND = "hide_expanded_recommend"

    private val ALL = listOf(
        HIDE_NER_URL, HIDE_NER_TEL, HIDE_NER_LOC, HIDE_NER_MAIL,
        HIDE_DEVICES, HIDE_CLOUD, HIDE_COMMERCE, HIDE_EXPANDED_RECOMMEND,
    )

    /** `generateNerEntityListByType` 的类型参数 → 子项（`ClipboardUtils.transferType`：1 LOC / 2 URL / 3 TEL / 4 MAIL）。 */
    private val NER_TYPE_TO_PREF = mapOf(1 to HIDE_NER_LOC, 2 to HIDE_NER_URL, 3 to HIDE_NER_TEL, 4 to HIDE_NER_MAIL)

    private const val PKG = "com.miui.contentextension"
    private const val CLIPBOARD_UTILS = "$PKG.clipboard.utils.ClipboardUtils"
    private const val CLIPBOARD_CARD_UTILS = "$PKG.clipboard.utils.ClipboardCardUtils"
    private const val TEXT_RECOMMEND_UTILS = "$PKG.data.recognition.TextRecommendationUtils"
    private const val SPLASH_VIEW = "$PKG.text.floatview.TaplusSplashFloatView"
    private const val MAIN_VIEW = "$PKG.text.floatview.TaplusMainFloatView"

    override fun wanted(prefs: Prefs): Boolean = ALL.any { hidden(prefs, it) }

    /**
     * 子项「隐藏」是否生效：总开关开着 **且** 这一项明确写过 true。
     *
     * ⚠️ 不用 [ContentCapabilities.on]：那里「没写过就跟总开关」——
     * 已经开着识屏样式的用户一升级，八项会全部当成「隐藏」。隐藏类子项没写过 = 显示（= 原生）。
     */
    private fun hidden(prefs: Prefs, name: String): Boolean =
        prefs.isEnabled(Catalog.RECOGNITION_LOOK) && prefs.rawBoolean(Keys.pref(Catalog.RECOGNITION_LOOK, name), false)

    private fun hidden(name: String): Boolean = try {
        hidden(HookRegistry.prefs(), name)
    } catch (_: Throwable) {
        false
    }

    override fun install(ctx: HookContext): InstallReport {
        val missing = ArrayList<String>()
        fun find(cls: String, name: String, argc: Int): Method? {
            val m = runCatching { ctx.classLoader.loadClass(cls) }.getOrNull()
                ?.declaredMethods?.firstOrNull { it.name == name && it.parameterTypes.size == argc }
            if (m == null) missing.add("$cls#$name/$argc") else m.isAccessible = true
            return m
        }

        // ① NER 实体：按类型整类不生成
        find(CLIPBOARD_UTILS, "generateNerEntityListByType", 3)?.let { m ->
            track(ctx.module.hook(m).intercept { chain ->
                val type = chain.args[2] as? Int
                val pref = type?.let { NER_TYPE_TO_PREF[it] }
                if (pref != null && hidden(pref)) java.util.ArrayList<Any?>() else chain.proceed()
            })
        }

        // ② 跨设备：不去枚举设备（省一次 MiLink 查询），设备列表再兜一道
        find(SPLASH_VIEW, "needGetRemoteDeviceList", 1)?.let { m ->
            track(ctx.module.hook(m).intercept { chain ->
                if (hidden(HIDE_DEVICES)) false else chain.proceed()
            })
            // 一行判断的私有方法，AOT 下很可能内联进调用方 —— 把两个调用方打回解释执行（都是冷路径）
            for (caller in listOf("onNerReady", "showDevicesRecommendCards")) {
                runCatching {
                    ctx.classLoader.loadClass(SPLASH_VIEW).declaredMethods
                        .filter { it.name == caller }.forEach { ctx.module.deoptimize(it) }
                }
            }
        }
        find(CLIPBOARD_UTILS, "generateDeviceListByType", 3)?.let { m ->
            track(ctx.module.hook(m).intercept { chain ->
                if (hidden(HIDE_DEVICES)) java.util.ArrayList<Any?>() else chain.proceed()
            })
        }

        // ③ 云端推荐：不发请求
        find(TEXT_RECOMMEND_UTILS, "tryToGetRecommendation", 3)?.let { m ->
            track(ctx.module.hook(m).intercept { chain ->
                if (!hidden(HIDE_CLOUD)) return@intercept chain.proceed()
                dropPendingTask(m.declaringClass, "sLastRecommendationTask")
                null
            })
        }

        // ④ 商单推荐：不发请求
        find(CLIPBOARD_CARD_UTILS, "getCommerceRecommendWithAsyncTask", 3)?.let { m ->
            track(ctx.module.hook(m).intercept { chain ->
                if (!hidden(HIDE_COMMERCE)) return@intercept chain.proceed()
                dropPendingTask(m.declaringClass, "mLastCopyRecommendTask")
                null
            })
        }

        // ⑤ 展开态推荐横滑：传空列表，原逻辑据此把列表收起（setRecommendListVisibility(false)）
        find(MAIN_VIEW, "updateRecommendCards", 2)?.let { m ->
            track(ctx.module.hook(m).intercept { chain ->
                if (!hidden(HIDE_EXPANDED_RECOMMEND)) return@intercept chain.proceed()
                val args = chain.args.toTypedArray()
                args[0] = java.util.ArrayList<Any?>()
                chain.proceed(args)
            })
        }

        ctx.log(Log.INFO, "传送门胶囊显隐：hooked=$installedHooks missing=$missing")
        return InstallReport(installedHooks, missing = missing)
    }

    /** 作废上一次还在飞的异步请求：取消并清掉「最后一次任务」引用，它晚到的结果就会被原逻辑丢弃。 */
    private fun dropPendingTask(cls: Class<*>, fieldName: String) {
        runCatching {
            val f = cls.getDeclaredField(fieldName).apply { isAccessible = true }
            (f.get(null) as? android.os.AsyncTask<*, *, *>)?.cancel(true)
            f.set(null, null)
        }
    }
}
