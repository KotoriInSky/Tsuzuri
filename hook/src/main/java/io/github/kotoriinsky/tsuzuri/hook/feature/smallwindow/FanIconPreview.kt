package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「真机上会出现的那张图标」的**唯一入口** —— 真机的扇形与设置界面的预览都调它。
 *
 * ## 为什么要有这个类（而不是让预览自己拼一遍）
 *
 * 取一张图标在这条链上要经过四级回退（优先级写在 [IconOverrides] 的类注释里）：
 *
 * ```
 * ① 单项自定义（图片文件）      ② 单项图标包（挑一个 drawable）
 * ③ 整体图标包（含 iconback / iconmask / iconupon 合成）   ④ 应用自带图标
 * ```
 *
 * 这四级里，①②③ 全是**运行时数据**：用户配置、已装图标包、图标包 APK 的 appfilter。
 * 预览如果在 `:ui` 里再拼一遍，那两份实现必然漂移（这个项目已经在多处吃过亏），
 * 而漂移的表现是「预览里长这样、真机上长那样」——**比没有预览更糟**，
 * 因为用户会相信预览。
 *
 * 所以这里是 `FanTargets.appIcon` 那条链的**同一份实现**的对外出口：
 * 真机侧走 `FanTargets`（带自己的 memo 缓存与日志），预览侧走这里。
 *
 * ## 形状与描边**不在这里**
 *
 * 形状裁切发生在这一层**之后**，而且轮廓采样 `IconShapes.outline` 本来就在 `core`
 * 里（纯数学），真机与预览共用同一份 —— 所以预览侧拿到位图后自己裁形即可，
 * 不需要把 `FanOverlay.circularIcon` 那套绘制搬出来。
 * 唯一的例外是 [COVER_SCALE]：真机为了让图标出血到形状外，会先把图标按它放大再裁，
 * 预览必须用**同一个系数**，否则同一张图在两边大小不一样。所以它定义在这里，
 * `FanOverlay` 反过来引用它（不许写两个字面量）。
 *
 * ## 线程
 *
 * 解析要走 `PackageManager` 与图标包 APK 的资源（都是 Binder / 跨进程读），
 * **绝不能在主线程调**。[bitmaps] 由调用方在 IO 线程调。
 */
object FanIconPreview {

    private const val TAG = "FP-Fan"

    /** 配置里「整体图标包」那一项的键后缀（与 `FanTargets.configuredIconPack` 同一个）。 */
    private const val PACK_PREF = "icon_pack"

    /**
     * 图标按 **cover** 放大到「图标直径 × 它」再裁形（`FanOverlayView.java:642`）。
     *
     * 1.16 等价于「只保留图标原始尺寸的中央 1/1.16 ≈ 86%」，也就是故意让图标出血。
     * 这个值**只有一处**：`FanOverlay` 的 `ICON_COVER_SCALE` 反过来引用它，
     * 免得预览与真机各写一个 1.16 之后哪天只改了一边。
     */
    const val COVER_SCALE = 1.16f

    /** 预热 `PackageManager`。幂等，次次都调没关系（与 `IconPackResolver.attach` 同义）。 */
    fun attach(context: Context) {
        IconPackResolver.attach(context)
    }

    /**
     * 一次解析一批项（按传入顺序返回，解析不出的那一项是 `null`，**不抛异常**）。
     *
     * @param featureKey 特性键，用来读它自己的「整体图标包」配置
     * @param keys 清单项键：应用 = 包名；快捷方式 = `包名/id`；链接 = `link:<uri>`（无宿主，返回 null）
     * @param targetPx 目标位图边长（px）。调用方按预览画布上实际要画多大给
     * @return 与 [keys] 等长的列表；每一项是「真机上会出现的那张图」按 cover 放大后的方形位图，
     *   未裁形（形状由调用方按 `IconShapes.outline` 裁）
     */
    fun bitmaps(
        context: Context,
        featureKey: String,
        keys: List<String>,
        prefs: Prefs,
        targetPx: Int,
    ): List<Bitmap?> {
        if (keys.isEmpty()) return emptyList()
        val size = targetPx.coerceIn(16, 512)
        // 配置读失败一律回退默认值（规范 §9）：这里退成「没有覆盖」，而不是让预览整块空掉
        val overrides = try {
            IconOverrides.read(prefs)
        } catch (t: Throwable) {
            Log.w(TAG, "预览：读单项图标覆盖失败，按无覆盖处理：" + t.javaClass.simpleName)
            IconOverrides.EMPTY
        }
        val pack = configuredPack(context, featureKey, prefs)
        // 覆盖解析的缓存要与真机侧分开：那边的 key 是包名，这边可能只解析其中几项
        val overrideCache = HashMap<String, Drawable?>()
        return keys.map { key ->
            val pkg = hostPackageOf(key) ?: return@map null
            val d = drawableOf(context, key, pkg, overrides, overrideCache, pack) ?: return@map null
            raster(d, size)
        }
    }

    /**
     * 这一项画谁的图标。
     *
     * 与 `FanItemRef.packageName` 同一条规则（应用 = 自己、快捷方式 = 宿主、链接 = 空），
     * 但**不依赖 `:ui`** —— 这个类住在 `:hook`，`:ui` 反过来依赖它。
     * 链接项返回 `null`：它没有宿主应用，真机上画的是链接自己的占位图标。
     */
    private fun hostPackageOf(key: String): String? = when {
        key.startsWith("link:") -> null
        else -> key.substringBefore('/').ifEmpty { null }
    }

    /** 四级回退链 —— 与 `FanTargets.appIcon` 同一份判据（见类注释）。 */
    private fun drawableOf(
        context: Context,
        key: String,
        pkg: String,
        overrides: IconOverrides.Set,
        overrideCache: MutableMap<String, Drawable?>,
        pack: IconPackResolver.Pack?,
    ): Drawable? {
        // ① ② 单项覆盖。`module` 传 null 是安全的：读「选图片」那种覆盖走的是
        // content://（`IconFileProvider`），**不再需要**框架的共享目录
        // （那条老通道在真机上从来没成功过，见 IconOverrides 的类注释）。
        if (overrides.has(key)) {
            overrides.drawable(null, context, key, overrideCache)?.let { return it }
        }
        // ④ 应用自带图标（回退链的底）
        val base = try {
            context.packageManager?.getApplicationIcon(pkg)
        } catch (t: Throwable) {
            Log.w(TAG, "预览：取不到 $pkg 的图标：" + t.javaClass.simpleName)
            null
        }
        // ③ 整体图标包
        if (pack == null) return base
        return try {
            pack.drawable(pkg, null, base)
        } catch (t: Throwable) {
            Log.w(TAG, "预览：图标包应用失败（$pkg）：" + t.javaClass.simpleName)
            base
        }
    }

    /** 用户选的整体图标包（`<特性>.icon_pack`，存的是 StringSet，取排序后第一个）。 */
    private fun configuredPack(
        context: Context,
        featureKey: String,
        prefs: Prefs,
    ): IconPackResolver.Pack? = try {
        prefs.rawStringSet(Keys.pref(featureKey, PACK_PREF)).sorted().firstOrNull()
            ?.let { IconPackResolver.packFor(context, it) }
    } catch (t: Throwable) {
        Log.w(TAG, "预览：读图标包配置失败：" + t.javaClass.simpleName)
        null
    }

    /**
     * 把 drawable 栅格化成 `size × size` 的方形位图。
     *
     * 与 `FanOverlay.circularIcon` 的区别只有一处：**这里不裁形** ——
     * 预览的形状裁切发生在 Compose 那侧（用同一份 `IconShapes.outline` 采样），
     * 在这里先裁一刀等于裁两次。其余（cover 放大、居中、ARGB_8888）逐项对齐。
     */
    private fun raster(d: Drawable, size: Int): Bitmap? = try {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        // `intrinsic` 为 0 的 drawable（部分自适应图标）会让除法炸掉，兜成 1
        val iw = max(1, d.intrinsicWidth)
        val ih = max(1, d.intrinsicHeight)
        val cover = COVER_SCALE * size
        val scale = max(cover / iw, cover / ih)
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        d.setBounds(
            (size - dw) / 2,
            (size - dh) / 2,
            (size + dw) / 2,
            (size + dh) / 2,
        )
        d.draw(c)
        b
    } catch (t: Throwable) {
        Log.w(TAG, "预览：图标栅格化失败：" + t.javaClass.simpleName)
        null
    }
}
