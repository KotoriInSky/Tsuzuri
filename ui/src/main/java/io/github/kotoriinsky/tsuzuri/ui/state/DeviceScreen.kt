package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import android.util.Log
import android.view.Display
import android.view.RoundedCorner
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect
import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenShape

/**
 * **当前所在屏幕**的真实形状，给预览与「触发」页高亮用（见 [ScreenShape]）。
 *
 * 读法（minSdk 36，全是公开 API）：
 *  - 尺寸：`WindowManager.maximumWindowMetrics.bounds` —— 整块屏幕，不是设置界面这个窗口
 *    （分屏 / 小窗里窗口比屏幕小，热区却是按整屏算的）；
 *  - 圆角：`Display.getRoundedCorner(位置)`，四角各读一次（有的机型上下不一样）；
 *  - 挖孔：`maximumWindowMetrics.windowInsets.displayCutout.boundingRects`。
 * 三者都按**当前朝向**给，所以转屏后直接重读就是对的。
 *
 * 什么时候重读：以配置里的朝向、屏幕 dp 尺寸、密度、最小宽度与所在屏幕 id 为键。
 * 转屏由 Activity 自己处理（manifest 的 `configChanges` 含 `screenSize`），配置变 → 重组 → 重读；
 * 折叠 / 展开会换屏幕或改 `smallestScreenSize`（不在 `configChanges` 里 → Activity 重建），同样重读。
 *
 * 与 SystemUI 那侧的口径：hook 用的是 SystemUI 的 `displayMetrics`；两者在全屏时应当一致，
 * 是否逐像素一致要真机核对。
 */
@Composable
fun rememberScreenShape(): ScreenShape? {
    val context = LocalContext.current
    val view = LocalView.current
    val config = LocalConfiguration.current
    val display = view.display
    return remember(
        context, display?.displayId, config.orientation,
        config.screenWidthDp, config.screenHeightDp, config.smallestScreenWidthDp, config.densityDpi,
    ) {
        readScreenShape(context, display)
    }
}

private val CORNER_POSITIONS = intArrayOf(
    RoundedCorner.POSITION_TOP_LEFT,
    RoundedCorner.POSITION_TOP_RIGHT,
    RoundedCorner.POSITION_BOTTOM_RIGHT,
    RoundedCorner.POSITION_BOTTOM_LEFT,
)

/** 读一次。任何一步失败都不抛：尺寸读不到返回 `null`（预览显示「无法生成」），其余部分走 [ScreenShape.of] 的退路。 */
fun readScreenShape(context: Context, display: Display?): ScreenShape? = try {
    val metrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
    val b = metrics.bounds
    val d = display ?: context.display
    val corners = CORNER_POSITIONS.map { pos -> runCatching { d.getRoundedCorner(pos)?.radius?.toFloat() }.getOrNull() }
    // 挖孔优先取**真实轮廓**（cutoutPath）的外框：boundingRects 是「从屏幕边到孔下沿」的整条带，
    // 本机是 74×150 —— 按它画会成一个竖长的水滴，而真孔是个圆（真机首轮验证发现，2026-10-21）。
    // 轮廓读不到时才退回 boundingRects。
    val cutout = metrics.windowInsets.displayCutout
    val fromPath = runCatching {
        cutout?.cutoutPath?.let { p ->
            val r = android.graphics.RectF()
            p.computeBounds(r, true)
            if (r.width() > 0f && r.height() > 0f) listOf(ScreenRect(r.left - b.left, r.top - b.top, r.right - b.left, r.bottom - b.top)) else null
        }
    }.getOrNull()
    val cutouts = fromPath ?: cutout?.boundingRects.orEmpty().map {
        ScreenRect(
            (it.left - b.left).toFloat(), (it.top - b.top).toFloat(),
            (it.right - b.left).toFloat(), (it.bottom - b.top).toFloat(),
        )
    }
    ScreenShape.of(
        width = b.width().toFloat(),
        height = b.height().toFloat(),
        density = context.resources.displayMetrics.density,
        corners = corners,
        cutouts = cutouts,
    ).also { s ->
        val holes = s?.cutouts?.joinToString { "${it.left.toInt()},${it.top.toInt()}-${it.right.toInt()},${it.bottom.toInt()}" }
        Log.i("FP-Ui", "预览屏幕：$s display=${d.displayId} 挖孔=[$holes]" + if (fromPath != null) "（轮廓）" else "（外接矩形）")
    }
} catch (t: Throwable) {
    Log.w("FP-Ui", "预览屏幕：读取失败 " + t.javaClass.simpleName, t)
    null
}
