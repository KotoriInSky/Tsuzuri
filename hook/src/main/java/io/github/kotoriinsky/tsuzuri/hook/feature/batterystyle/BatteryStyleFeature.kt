package io.github.kotoriinsky.tsuzuri.hook.feature.batterystyle

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.kotoriinsky.tsuzuri.core.BatteryStyle
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.PasscodeIsland
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 状态栏电池样式。契约见 [BatteryStyle]。
 *
 * ## 怎么换掉宿主的画法
 *
 * `MiuiBatteryMeterView` 是自己测量 / 排版的 LinearLayout（只认得自己的几个子视图）。
 *  - `onMeasure`：先跑原逻辑拿到高度，再按样式算宽度，`setMeasuredDimension` 覆盖；
 *  - `onLayout`：不跑原逻辑，把原有子视图都排成 0×0（宿主之后怎么切换它们的可见性都画不出来），
 *    自绘的 [BatteryStyleDrawable] 挂在 `overlay` 上并按内容区摆好；
 *  - 电量 / 充电 / 省电 / 深浅色回调之后刷新状态并重绘；宽度变了（纯数字 99→100、开始充电）才重排。
 *
 * 颜色取宿主百分比文字（`mBatteryPercentView`）的当前颜色：宿主的两条着色路径
 * （`mUseTint` 与深 / 浅色表）都会把最终色写到它上面。
 *
 * 关闭（或总开关关掉）：摘掉 overlay、`requestLayout`，宿主原逻辑接管，与未安装一致。
 */
internal object BatteryStyleFeature : Feature(Catalog.require(Catalog.BATTERY_STYLE)) {

    private const val TAG = "FP-Battery"
    private const val METER = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"

    private val main = Handler(Looper.getMainLooper())
    /** 见过的电池视图 → 它的自绘（null = 见过但当前没换）。弱引用，视图销毁自然释放。 */
    private val seen: MutableMap<View, BatteryStyleDrawable?> = Collections.synchronizedMap(WeakHashMap())
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var hookCtx: HookContext? = null

    private var fLevel: Field? = null
    private var fCharging: Field? = null
    private var fPowerSave: Field? = null
    private var fPercent: Field? = null
    private var fQuick: Field? = null
    private var setMeasured: Method? = null

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == PasscodeIsland.SYSTEM_UI_PACKAGE

    /** 当前样式；总开关关了或没选 = null（交还宿主）。 */
    private fun currentStyle(): String? = try {
        val prefs = HookRegistry.prefs()
        if (!prefs.isEnabled(key)) null
        else prefs.rawString(Keys.pref(key, BatteryStyle.STYLE), BatteryStyle.NUMBER)
            ?.takeIf { it in BatteryStyle.STYLES }
    } catch (_: Throwable) {
        null
    }

    private fun numberOutside(): Boolean = try {
        HookRegistry.prefs().rawBoolean(Keys.pref(key, BatteryStyle.NUMBER_OUTSIDE), false)
    } catch (_: Throwable) {
        false
    }

    private fun colored(): Boolean = try {
        HookRegistry.prefs().rawBoolean(Keys.pref(key, BatteryStyle.COLORED), true)
    } catch (_: Throwable) {
        true
    }

    override fun install(ctx: HookContext): InstallReport {
        hookCtx = ctx
        val cls = runCatching { Class.forName(METER, false, ctx.classLoader) }.getOrNull()
            ?: return InstallReport(0, missing = listOf(METER))
        fun field(n: String) = runCatching { cls.getDeclaredField(n).apply { isAccessible = true } }.getOrNull()
        fLevel = field("mLevel"); fCharging = field("mCharging"); fPowerSave = field("mPowerSave"); fPercent = field("mBatteryPercentView"); fQuick = field("mQuickCharging")
        setMeasured = runCatching {
            View::class.java.getDeclaredMethod("setMeasuredDimension", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
        val missing = ArrayList<String>()
        if (fLevel == null || fCharging == null || fPercent == null || setMeasured == null) {
            return InstallReport(0, missing = listOf("$METER 字段 mLevel/mCharging/mBatteryPercentView 或 setMeasuredDimension"))
        }

        val onMeasure = cls.declaredMethods.firstOrNull { it.name == "onMeasure" && it.parameterTypes.size == 2 }
        val onLayout = cls.declaredMethods.firstOrNull { it.name == "onLayout" && it.parameterTypes.size == 5 }
        if (onMeasure == null || onLayout == null) return InstallReport(0, missing = listOf("$METER#onMeasure/onLayout"))

        track(ctx.module.hook(onMeasure.apply { isAccessible = true }).intercept { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? ViewGroup ?: return@intercept result
            val style = currentStyle()
            if (style == null) {
                seen.putIfAbsent(view, null)
                return@intercept result
            }
            runCatching {
                val d = drawableFor(view, style)
                val contentH = view.measuredHeight - view.paddingTop - view.paddingBottom
                if (contentH > 0) {
                    val w = view.paddingStart + d.contentWidth() + view.paddingEnd
                    setMeasured!!.invoke(view, w, view.measuredHeight)
                }
            }.onFailure { Log.w(TAG, "测量失败，交还宿主", it) }
            result
        })

        track(ctx.module.hook(onLayout.apply { isAccessible = true }).intercept { chain ->
            val view = chain.thisObject as? ViewGroup ?: return@intercept chain.proceed()
            val style = currentStyle()
            val d = seen[view]
            if (style == null || d == null) {
                if (d != null) detach(view, d)
                return@intercept chain.proceed()
            }
            runCatching {
                for (i in 0 until view.childCount) view.getChildAt(i).layout(0, 0, 0, 0)
                pinTranslation(view)
                refresh(view, d)
                d.setBounds(view.paddingStart, view.paddingTop, view.width - view.paddingEnd, view.height - view.paddingBottom)
                view.invalidate()
                null
            }.getOrElse {
                Log.w(TAG, "排版失败，交还宿主", it)
                detach(view, d)
                chain.proceed()
            }
        })

        // 宿主的宽度变化监听（MiuiBatteryMeterView$2）：宽度一变就用 Folme 把整个电池视图按宽度差
        // 平移（给电池岛过渡用）。换样式会改宽度，于是纯数字整体左移、横向整体右移（真机 2026-11-25）。
        // 我们接管期间跳过它，并把平移归零；交还宿主后照常执行。
        runCatching { Class.forName("$METER\$2", false, ctx.classLoader) }.getOrNull()
            ?.declaredMethods?.firstOrNull { it.name == "onLayoutChange" && it.parameterTypes.size == 9 }
            ?.let { m ->
                track(ctx.module.hook(m.apply { isAccessible = true }).intercept { chain ->
                    val v = chain.args[0] as? ViewGroup
                    if (v != null && seen[v] != null && currentStyle() != null) {
                        pinTranslation(v)
                        null
                    } else {
                        chain.proceed()
                    }
                })
            } ?: missing.add("$METER\$2#onLayoutChange")

        // 状态 / 深浅色回调：之后刷新；宽度可能变的（电量、充电）顺带判断是否重排
        for ((name, argc) in listOf(
            "onBatteryLevelChanged" to 3, "onChargeStateChanged" to 2, "onPowerSaveChanged" to 1,
            "onDarkChangedInternal" to 0, "onLightDarkTintChanged" to 3,
        )) {
            val m = cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == argc }
            if (m == null) { missing.add(name); continue }
            track(ctx.module.hook(m.apply { isAccessible = true }).intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? ViewGroup ?: return@intercept result
                val d = seen[view]
                when {
                    d != null -> {
                        val before = d.contentWidth()
                        refresh(view, d)
                        if (d.contentWidth() != before) view.requestLayout()
                        view.invalidate()
                    }
                    // 热装后还没排过版的实例：有样式就请它重排一次
                    !seen.containsKey(view) && currentStyle() != null -> { seen[view] = null; view.requestLayout() }
                }
                result
            })
        }

        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == null || k.contains(key)) main.post { relayoutAll() }
        }
        runCatching { ctx.store?.registerOnSharedPreferenceChangeListener(listener) }
        // 热装：已存在的电池视图要等下一次电量 / 深浅色回调才会被看见 —— 满电插着充电时可能一直等不到。
        // 从当前进程的窗口根视图里把它们找出来，主动请求重排一次。
        main.post { adoptExisting(cls); relayoutAll() }
        ctx.log(Log.INFO, "电池样式：hooked=$installedHooks missing=$missing style=${currentStyle()}")
        return InstallReport(installedHooks, missing = missing)
    }

    private fun drawableFor(view: ViewGroup, style: String): BatteryStyleDrawable {
        val existing = seen[view]
        val d = existing ?: BatteryStyleDrawable(view.resources).also {
            view.overlay.add(it)
            seen[view] = it
            Log.i(TAG, "接管电池视图 ${System.identityHashCode(view)} style=$style")
        }
        d.style = style
        d.colored = colored()
        d.numberOutside = numberOutside()
        refresh(view, d)
        return d
    }

    private fun refresh(view: View, d: BatteryStyleDrawable) {
        runCatching {
            d.batteryLevel = fLevel!!.getInt(view)
            d.charging = fCharging!!.getBoolean(view)
            d.powerSave = fPowerSave?.getBoolean(view) ?: false
            d.quickCharging = fQuick?.getBoolean(view) ?: false
            (fPercent!!.get(view) as? TextView)?.let { tv ->
                d.baseColor = tv.currentTextColor
                d.typeface = tv.typeface
                d.textPx = tv.textSize
            }
            currentStyle()?.let { d.style = it }
            d.colored = colored()
            d.numberOutside = numberOutside()
        }
    }

    /** 遍历 `WindowManagerGlobal.mViews`，收下所有已经挂着的电池视图。失败就等回调，不影响功能。 */
    private fun adoptExisting(cls: Class<*>) {
        runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val roots = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<*>
            var n = 0
            fun walk(v: View) {
                if (cls.isInstance(v)) { seen.putIfAbsent(v, null); n++ }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            for (r in roots.toList()) (r as? View)?.let(::walk)
            Log.i(TAG, "热装收下已有电池视图 $n 个")
        }.onFailure { Log.w(TAG, "找不到已有电池视图，等回调", it) }
    }

    /** 停掉宿主挂在电池视图上的 Folme 平移，并归零。 */
    private fun pinTranslation(view: View) {
        if (view.translationX == 0f) return
        runCatching {
            Class.forName("miuix.animation.Folme", false, view.javaClass.classLoader)
                .getMethod("clean", View::class.java).invoke(null, view)
        }
        view.translationX = 0f
    }

    private fun detach(view: ViewGroup, d: BatteryStyleDrawable) {
        runCatching { view.overlay.remove(d) }
        seen[view] = null
        view.requestLayout()
        Log.i(TAG, "交还电池视图 ${System.identityHashCode(view)}")
    }

    /** 样式 / 开关变了：所有见过的电池视图重新测量排版（换样式、交还宿主都走这里）。 */
    private fun relayoutAll() {
        val views = synchronized(seen) { seen.keys.toList() }
        for (v in views) {
            val vg = v as? ViewGroup ?: continue
            val d = seen[vg]
            if (currentStyle() == null && d != null) detach(vg, d) else vg.requestLayout()
        }
    }

    override fun uninstall() {
        runCatching { hookCtx?.store?.unregisterOnSharedPreferenceChangeListener(listener) }
        listener = null
        super.uninstall()
        // hook 摘掉之后宿主原 onLayout 接管；这里只把 overlay 拿掉
        main.post {
            val entries = synchronized(seen) { seen.entries.map { it.key to it.value } }
            for ((v, d) in entries) {
                if (d != null) runCatching { (v as ViewGroup).overlay.remove(d) }
                v.requestLayout()
            }
            seen.clear()
        }
    }
}
