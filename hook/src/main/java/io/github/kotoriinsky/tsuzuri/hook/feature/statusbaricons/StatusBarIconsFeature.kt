package io.github.kotoriinsky.tsuzuri.hook.feature.statusbaricons

import android.util.Log
import android.view.View
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.core.StatusBarIcons
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 状态栏通知图标：超级岛挡住时直接消失、隐藏省略点。契约见 [StatusBarIcons]。
 *
 * ## 岛（`NotificationIconContainerInject$MiuiIconState.applyView`）
 *
 * 每次排版先把图标标成 `inIslandState = 20`（不在岛下），超出岛左缘的再标成 `10`。
 * 原逻辑在两态之间切换时播 Folme 动画（缩到 0.4 + 模糊 30 + 透明 ↔ 还原），动画目标挂在
 * 视图 tag 上；`applyToView(view, false)` 见到 tag 就**改动画目标**而不是直接赋值，
 * tag 残留时图标会停在半缩半糊。
 *
 * 这里在**状态切换的那一次**先调宿主自己的 `IconState.cancelAnimations(view)`（停 Folme +
 * 清五个 tag），再以「不动画」走原逻辑 —— 原逻辑于是直接赋最终值：进岛 alpha 0，出岛
 * 缩放 1、模糊 0、alpha 1。另外在**稳态**（不在岛下、可见、没有动画 tag）发现缩放 < 1
 * 或模糊 > 0 的，当场还原并记一行，用来证实 / 兜住「偶尔停在糊的样子」。
 *
 * ## 省略点（`NotificationIconContainer.calculateIconXTranslations`）
 *
 * 算完位置后，把 `visibleState == 1`（圆点）的图标改成 `2`（隐藏）。只动状态栏那个容器
 * （`mIsStaticLayout = true`；通知架上的图标容器是非静态排版，不碰）。
 */
internal object StatusBarIconsFeature : Feature(Catalog.require(Catalog.STATUSBAR_ICONS)) {

    private const val TAG = "FP-StatusIcons"
    private const val CONTAINER = "com.android.systemui.statusbar.phone.NotificationIconContainer"
    private const val ICON_STATE = "$CONTAINER\$IconState"
    private const val MIUI_ICON_STATE = "com.android.systemui.statusbar.phone.NotificationIconContainerInject\$MiuiIconState"
    private const val FOLME_PROPS = "com.android.systemui.statusbar.anim.FolmeAnimatableProperty\$Companion"
    private const val BLUR_EFFECT = "com.android.systemui.statusbar.views.IBlurEffect"

    private const val IN_ISLAND = 10
    private const val OUT_ISLAND = 20
    private const val STATE_ICON = 0
    private const val STATE_DOT = 1
    private const val STATE_HIDDEN = 2

    private const val LOG_BUDGET = 40
    @Volatile private var logged = 0

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == PasscodeIsland.SYSTEM_UI_PACKAGE

    private fun sub(name: String): Boolean = try {
        val prefs = HookRegistry.prefs()
        prefs.isEnabled(key) && prefs.rawBoolean(Keys.pref(key, name), true)
    } catch (_: Throwable) {
        false
    }

    private fun trace(msg: String) {
        if (logged >= LOG_BUDGET) return
        logged++
        Log.i(TAG, msg)
    }

    override fun install(ctx: HookContext): InstallReport {
        val missing = ArrayList<String>()
        val cl = ctx.classLoader
        fun cls(n: String) = runCatching { Class.forName(n, false, cl) }.getOrNull()
        fun field(c: Class<*>?, n: String): Field? = c?.let { runCatching { it.getDeclaredField(n).apply { isAccessible = true } }.getOrNull() }

        val containerCls = cls(CONTAINER)
        val iconStateCls = cls(ICON_STATE)
        val miuiStateCls = cls(MIUI_ICON_STATE)
        val blurCls = cls(BLUR_EFFECT)

        // ── 岛 ─────────────────────────────────────────────────────────
        val applyView = miuiStateCls?.declaredMethods?.firstOrNull {
            it.name == "applyView" && it.parameterTypes.size == 4 && it.parameterTypes[0] == View::class.java
        }
        val fInIsland = field(miuiStateCls, "inIslandState")
        val fBefore = field(miuiStateCls, "beforeInIslandState")
        val fDelegate = field(miuiStateCls, "stateDelegate")
        val fVisible = field(iconStateCls, "visibleState")
        val cancelAnims: Method? = iconStateCls?.let { c ->
            runCatching { c.getMethod("cancelAnimations", View::class.java) }.getOrNull()
        }
        val getBlur = blurCls?.let { runCatching { it.getMethod("getBlurRadius") }.getOrNull() }
        val setBlur = blurCls?.let { runCatching { it.getMethod("setBlurRadius", Int::class.javaPrimitiveType) }.getOrNull() }
        // 动画 tag：有 tag = Folme 动画在跑（或残留），稳态检查只在没有 tag 时还原
        val animTags: List<Int> = runCatching {
            val companion = cls(FOLME_PROPS)!!
            listOf("TRANSLATION_X", "SCALE_X", "SCALE_Y", "ALPHA", "BLUR").map { n ->
                val prop = companion.getDeclaredField(n).apply { isAccessible = true }.get(null)!!
                prop.javaClass.getDeclaredField("animatorTag").apply { isAccessible = true }.getInt(prop)
            }
        }.getOrDefault(emptyList())

        if (applyView == null || fInIsland == null || fBefore == null || fDelegate == null || cancelAnims == null) {
            missing.add("$MIUI_ICON_STATE#applyView/inIslandState/stateDelegate/cancelAnimations")
        } else {
            applyView.isAccessible = true
            track(ctx.module.hook(applyView).intercept { chain ->
                val view = chain.args[0] as? View
                if (view == null || !sub(StatusBarIcons.ISLAND_INSTANT)) return@intercept chain.proceed()
                val st = chain.thisObject ?: return@intercept chain.proceed()
                val now = fInIsland.getInt(st)
                val before = fBefore.getInt(st)
                if (now != before && (now == IN_ISLAND || before == IN_ISLAND)) {
                    // 进 / 出岛：停掉宿主的动画并清 tag，再走「不动画」分支，原逻辑直接赋最终值
                    runCatching { cancelAnims.invoke(fDelegate.get(st), view) }
                    val result = chain.proceed(arrayOf(view, false, chain.args[2], chain.args[3]))
                    if (now == OUT_ISLAND) restoreIfStuck(view, getBlur, setBlur)?.let { trace("出岛还原：$it") }
                    trace("岛${if (now == IN_ISLAND) "遮挡 → 直接隐藏" else "让出 → 直接恢复"} ${view.javaClass.simpleName}@${System.identityHashCode(view)}")
                    return@intercept result
                }
                val result = chain.proceed()
                // 稳态兜底：不在岛下、可见、没有动画 tag，却还缩着 / 糊着 → 当场还原
                if (now == OUT_ISLAND && view.visibility == View.VISIBLE &&
                    (fVisible?.let { f -> fDelegate.get(st)?.let { f.getInt(it) } } ?: STATE_ICON) == STATE_ICON &&
                    animTags.none { view.getTag(it) != null }
                ) {
                    restoreIfStuck(view, getBlur, setBlur)?.let { trace("稳态发现残留并还原：$it") }
                }
                result
            })
        }

        // ── 省略点 ─────────────────────────────────────────────────────
        val calc = containerCls?.let { c -> runCatching { c.getDeclaredMethod("calculateIconXTranslations") }.getOrNull() }
        val fStates = field(containerCls, "mIconStates")
        val fStatic = field(containerCls, "mIsStaticLayout")
        if (calc == null || fStates == null || fVisible == null) {
            missing.add("$CONTAINER#calculateIconXTranslations/mIconStates/visibleState")
        } else {
            calc.isAccessible = true
            track(ctx.module.hook(calc).intercept { chain ->
                val result = chain.proceed()
                if (!sub(StatusBarIcons.HIDE_OVERFLOW_DOT)) return@intercept result
                val container = chain.thisObject ?: return@intercept result
                if (fStatic?.getBoolean(container) == false) return@intercept result
                val states = fStates.get(container) as? Map<*, *> ?: return@intercept result
                var hidden = 0
                for (s in states.values) {
                    if (s != null && fVisible.getInt(s) == STATE_DOT) {
                        fVisible.setInt(s, STATE_HIDDEN)
                        hidden++
                    }
                }
                if (hidden > 0) trace("省略点 → 隐藏 $hidden 个")
                result
            })
        }

        ctx.log(Log.INFO, "状态栏通知图标：hooked=$installedHooks missing=$missing animTags=${animTags.size}")
        return InstallReport(installedHooks, missing = missing)
    }

    /** 缩放 / 模糊没回到原样就直接还原。@return 还原前的值；本来就正常返回 null。 */
    private fun restoreIfStuck(view: View, getBlur: Method?, setBlur: Method?): String? {
        val blur = runCatching { getBlur?.invoke(view) as? Int }.getOrNull() ?: 0
        if (view.scaleX >= 0.999f && view.scaleY >= 0.999f && blur == 0) return null
        val was = "scale=${"%.2f".format(view.scaleX)} blur=$blur alpha=${"%.2f".format(view.alpha)}"
        view.scaleX = 1f
        view.scaleY = 1f
        if (blur != 0) runCatching { setBlur?.invoke(view, 0) }
        return was
    }

    override fun uninstall() {
        logged = 0
        super.uninstall()
    }
}
