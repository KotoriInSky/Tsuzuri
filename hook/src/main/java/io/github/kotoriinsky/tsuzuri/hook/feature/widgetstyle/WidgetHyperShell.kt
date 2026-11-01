package io.github.kotoriinsky.tsuzuri.hook.feature.widgetstyle

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.RemoteViews
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.libxposed.api.XposedInterface.HookHandle
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 给**没适配过的第三方小部件**统一外壳，照 Flyme 的做法只管两件事：
 *
 * | 做什么 | 怎么做 |
 * | --- | --- |
 * | 圆角 | 强制裁剪宿主视图，半径用负一屏系统小部件同一套按尺寸分档的值 |
 * | 尺寸 | 布局尺寸变化时用 `updateAppWidgetSize` 把实际 dp 尺寸告诉小部件，响应式的自己重排 |
 *
 * 底色、字体、尺寸档位都不动 —— 内容尊重开发者，只让边框和大小与系统小部件对齐。
 *
 * **只作用于第三方**：provider 的 receiver 声明了 `miuiWidget` 元数据的小米小部件一概不动
 * （判据见 [isThirdParty]）。`Outline` 只能画普通圆角矩形，做不出平滑曲线，是近似。
 */
internal object WidgetHyperShell {

    private const val META_MIUI_WIDGET = "miuiWidget"

    private val handles = ArrayList<HookHandle>(1)

    /** provider → 是否第三方。查 PackageManager 一次就缓存。 */
    private val thirdParty = ConcurrentHashMap<ComponentName, Boolean>()

    /** 每个宿主视图的状态（弱引用，随视图回收）。 */
    private val states = WeakHashMap<View, HostState>()

    private class HostState {
        var lastWDp = -1
        var lastHDp = -1
        var listener: View.OnLayoutChangeListener? = null
        /** 小部件刚更新过、还没取色 */
        var fillDirty = false
    }

    fun install(ctx: HookContext, missing: MutableList<String>): Int {
        if (handles.isNotEmpty()) return handles.size
        val m = try {
            AppWidgetHostView::class.java.getDeclaredMethod("updateAppWidget", RemoteViews::class.java)
        } catch (t: Throwable) {
            missing.add("AppWidgetHostView#updateAppWidget")
            return 0
        }
        try {
            handles.add(
                ctx.module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    try {
                        val host = chain.thisObject as? AppWidgetHostView
                        // RemoteViews 可能异步展开，等这一轮消息跑完再套外壳
                        if (host != null && isThirdParty(host)) host.post { applySafely(host, ctx) }
                    } catch (_: Throwable) {
                        // 套不上外壳 = 保持原样
                    }
                    r
                }
            )
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "hook updateAppWidget failed", t)
            missing.add("AppWidgetHostView#updateAppWidget")
        }
        return handles.size
    }

    fun uninstall() {
        for (h in handles) {
            try {
                h.unhook()
            } catch (_: Throwable) {
                // 卸不掉也不能抛
            }
        }
        handles.clear()
        synchronized(states) { states.clear() }
    }

    val isActive: Boolean get() = handles.isNotEmpty()

    /**
     * 是不是需要套外壳的第三方小部件：宿主视图（或它的子视图是宿主）对应的 provider
     * 没在 receiver 上声明 `miuiWidget`。查不到 provider 一律当作「不是」。
     */
    fun isThirdParty(view: View): Boolean {
        val host = view as? AppWidgetHostView
            ?: (view as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is AppWidgetHostView } as? AppWidgetHostView }
            ?: return false
        val cn = host.appWidgetInfo?.provider ?: return false
        return thirdParty.getOrPut(cn) {
            try {
                @Suppress("DEPRECATION")
                val ri = host.context.packageManager.getReceiverInfo(cn, PackageManager.GET_META_DATA)
                ri.metaData?.containsKey(META_MIUI_WIDGET) != true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun applySafely(host: AppWidgetHostView, ctx: HookContext) {
        try {
            apply(host)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "套用小部件外壳失败：${t.javaClass.simpleName}", t)
        }
    }

    private fun apply(host: AppWidgetHostView) {
        val radius = WidgetUnifiedStyle.systemRadiusPx(host)

        // 圆角：裁宿主自己（PA 的卡片可能还会裁一层，那一层在 clipChildCorner 的 hook 里用同一个值）
        val op = host.outlineProvider
        if (op !is SpecOutline || op.radius != radius) host.outlineProvider = SpecOutline(radius)
        host.clipToOutline = true

        // 尺寸通知 + 取色补齐：挂一次常驻布局监听；取色只在「小部件刚更新过」后的那次布局做一次
        val st = synchronized(states) { states.getOrPut(host) { HostState() } }
        st.fillDirty = true
        if (st.listener == null) {
            val l = View.OnLayoutChangeListener { v, l0, t0, r0, b0, _, _, _, _ ->
                val h = v as AppWidgetHostView
                notifySize(h, st, r0 - l0, b0 - t0)
                if (st.fillDirty) h.post { fillToSpec(h, st) }
            }
            st.listener = l
            host.addOnLayoutChangeListener(l)
        }
        if (host.width > 0) {
            notifySize(host, st, host.width, host.height)
            host.post { fillToSpec(host, st) }
        }
    }

    /**
     * 补到规范尺寸：外框已按系统小部件的规范尺寸占位，但很多小部件把背景画在小一圈的内层
     * （Glance 的 `Modifier.padding`、自带外边距的布局；实测 Claude 四边各 8dp），露出透明边。
     *
     * **先取色，再决定怎么补**：
     * 1. 取色：把小部件内容（不含外框背景）缩小画一遍，从四条边中点往里找第一个不透明像素，
     *    记下每边的留白和那里的颜色。
     * 2. 决定：
     *    - 四边都贴边 → 本来就撑满，不补；
     *    - 不透明的边不到 3 条，或留白超过 [FILL_MAX_DP] → 透明小部件／不是统一留白，不补；
     *    - 至少 3 边颜色一致 → 用这个颜色铺外框底，留白与小部件背景连成一片。
     *
     * 每次小部件更新只算一次（[HostState.fillDirty]），颜色没变就不碰背景，不会自激重排。
     */
    private fun fillToSpec(host: AppWidgetHostView, st: HostState) {
        if (!st.fillDirty) return
        val content = host.getChildAt(0)
        val w = host.width
        val h = host.height
        if (content == null || w <= 0 || h <= 0) {
            Log.i(FILL_TAG, "跳过：未就绪 child=${content != null} size=${w}x$h")
            return
        }
        st.fillDirty = false

        // 1. 取色
        val scale = (FILL_SAMPLE_PX / maxOf(w, h).toFloat()).coerceAtMost(1f)
        val bw = (w * scale).toInt().coerceAtLeast(8)
        val bh = (h * scale).toInt().coerceAtLeast(8)
        val maxGap = (FILL_MAX_DP * host.resources.displayMetrics.density * scale).toInt() + 1
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val gaps = IntArray(4) { -1 }
        val colors = ArrayList<Int>(4)
        try {
            val c = Canvas(bmp)
            c.scale(bw.toFloat() / w, bh.toFloat() / h)
            c.translate(content.left.toFloat(), content.top.toFloat())
            content.draw(c)
            // 四条边：起点 x、y，步进 dx、dy
            val probes = arrayOf(
                intArrayOf(0, bh / 2, 1, 0), intArrayOf(bw - 1, bh / 2, -1, 0),
                intArrayOf(bw / 2, 0, 0, 1), intArrayOf(bw / 2, bh - 1, 0, -1),
            )
            for ((i, p) in probes.withIndex()) {
                var x = p[0]
                var y = p[1]
                var n = 0
                while (n <= maxGap && x in 0 until bw && y in 0 until bh && Color.alpha(bmp.getPixel(x, y)) < FILL_OPAQUE) {
                    x += p[2]; y += p[3]; n++
                }
                if (n > maxGap || x !in 0 until bw || y !in 0 until bh) continue
                gaps[i] = n
                // 再往里一格，避开背景边缘的抗锯齿
                colors.add(bmp.getPixel((x + p[2]).coerceIn(0, bw - 1), (y + p[3]).coerceIn(0, bh - 1)))
            }
        } finally {
            bmp.recycle()
        }

        // 2. 决定怎么补
        val best = colors.groupBy { it and 0xF8F8F8 }.maxByOrNull { it.value.size }?.value
        val decision = when {
            gaps.all { it == 0 } -> null                         // 本来就贴边
            gaps.count { it >= 0 } < 3 -> null                    // 透明小部件或留白太大
            best == null || best.size < 3 -> null                 // 四边颜色对不上
            else -> best[0] or (0xFF shl 24)
        }
        Log.i(FILL_TAG, "取色 gaps=${gaps.joinToString(",")} colors=${colors.joinToString(",") { Integer.toHexString(it) }} → ${decision?.let { Integer.toHexString(it) } ?: "不补"}")
        val cur = host.background
        if (decision == null) {
            if (cur is GapFill) host.background = null
        } else if (cur !is GapFill || cur.color != decision) {
            host.background = GapFill(decision)
        }
    }

    /** 我们铺的底色；单独的类型用来认出并撤掉。 */
    private class GapFill(color: Int) : ColorDrawable(color)

    private const val FILL_TAG = "TsuzuriWidgetFill"
    private const val FILL_SAMPLE_PX = 160f
    private const val FILL_MAX_DP = 24
    private const val FILL_OPAQUE = 0xF0

    private fun notifySize(host: AppWidgetHostView, st: HostState, wPx: Int, hPx: Int) {
        if (wPx <= 0 || hPx <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val d = host.resources.displayMetrics.density
        val wDp = (wPx / d).toInt()
        val hDp = (hPx / d).toInt()
        if (wDp == st.lastWDp && hDp == st.lastHDp) return
        st.lastWDp = wDp
        st.lastHDp = hDp
        try {
            host.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp.toFloat(), hDp.toFloat())))
        } catch (_: Throwable) {
            // provider 拒收也没关系，只是不重排
        }
    }

    /** 每个视图一个实例，半径定死（理由同 WidgetUnifiedStyle.RoundOutlineProvider）。 */
    class SpecOutline(val radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width
            val h = view.height
            val half = if (w < h) w * 0.5f else h * 0.5f
            outline.setRoundRect(0, 0, w, h, if (radius < half) radius else half)
        }
    }
}
