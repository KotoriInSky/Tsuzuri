package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.FanIconPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 给扇形预览取**真机上会出现的那张图标**。
 *
 * ## 为什么单独一层，而不是在页面里直接调
 *
 * 取一张图标要经过四级回退 + 跨进程读资源（`PackageManager` / 图标包 APK 的 appfilter），
 * 全是 Binder 级的重活。两件事必须在这里做完，页面才干净：
 *
 *  1. **切到 IO 线程** —— 主线程调会卡顿（这个项目对动画帧有硬要求，规范 §7.4）；
 *  2. **失败的隔离** —— 任何一项解析不出来都只让那一项变 `null`（画布会退回占位形），
 *     不能让一个坏图标把整页预览打掉。
 *
 * ## 解析用的链**不是这里写的**
 *
 * 真正的判据在 `:hook` 的 [FanIconPreview]（= 真机 `FanTargets.appIcon` 那条链的同一份实现）。
 * 这里只负责「凑齐要解析哪几项」+ 转成 Compose 能画的 `ImageBitmap`。
 * 也就是说：**预览里的图标与扇子里的图标走的是同一个函数**，不存在两份实现。
 *
 * ## 清单没配过时画什么
 *
 * 约定是「按示意项数画，并在界面上如实说明这是示意」。
 * 这里给示意项配上**真实已装应用**的图标（而不是灰占位）——
 * 示意的是**数量与排布**，不是图标本身；用户看到的图标仍然是真的。
 */
object PreviewIcons {

    /**
     * 解析出来给预览画的图标边长（px）。
     *
     * 取一个比画布上实际尺寸大的固定值：真实尺寸随屏幕与参数变（几十 px 量级），
     * 而位图解码是这条链上最贵的一步，缓存一份够用的就不要再按参数重解。
     * `drawImage` 缩小时会做滤波，观感与真机的等比缩小一致。
     */
    private const val TARGET_PX = 96

    /**
     * 取 [count] 项图标，顺序与画布上的 `item.index` 对齐。
     *
     * @param configuredItems 用户配过的清单（空 = 没配过，走示意）
     * @return 长度 = `count`；解析不出的那一项是 `null`
     */
    suspend fun load(
        context: Context,
        featureKey: String,
        configuredItems: List<String>,
        count: Int,
        prefs: Prefs,
    ): List<ImageBitmap?> {
        if (count <= 0) return emptyList()
        return withContext(Dispatchers.IO) {
            val keys = try {
                if (configuredItems.isNotEmpty()) {
                    configuredItems.take(count)
                } else {
                    // 没配过 → 示意：拿真实已装应用顶上（排布是示意的，图标是真的）
                    sampleInstalled(context, count)
                }
            } catch (t: Throwable) {
                emptyList()
            }
            if (keys.isEmpty()) return@withContext emptyList()
            val bitmaps = try {
                FanIconPreview.attach(context)
                FanIconPreview.bitmaps(context, featureKey, keys, prefs, TARGET_PX)
            } catch (t: Throwable) {
                emptyList()
            }
            bitmaps.map { b -> b?.asImageBitmap() }
        }
    }

    /**
     * 示意用的一批真实应用。
     *
     * 刻意**不读配置里那 5 个「最近使用」**：那是扇子自己算出来的挑选规则，
     * 预览再算一遍就是第二份实现。这里只要几个**真的存在**的应用把形状画出来，
     * 排布由几何决定，数量由 `SAMPLE_COUNT` 决定。
     */
    private suspend fun sampleInstalled(context: Context, count: Int): List<String> {
        val catalog = loadAppCatalog(context)
        val apps = (catalog as? AppCatalog.Ready)?.apps ?: return emptyList()
        return apps.take(count).map { it.packageName }
    }
}
