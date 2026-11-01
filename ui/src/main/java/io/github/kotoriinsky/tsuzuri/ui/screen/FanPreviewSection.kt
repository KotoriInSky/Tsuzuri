package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.geometry.FanAnimationTiming
import io.github.kotoriinsky.tsuzuri.core.geometry.FanPreviewParams
import io.github.kotoriinsky.tsuzuri.core.geometry.IconShape
import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds
import io.github.kotoriinsky.tsuzuri.core.geometry.fanPreview
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FanPreviewCanvas
import io.github.kotoriinsky.tsuzuri.ui.kit.FanPreviewKind
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.state.FanItems
import io.github.kotoriinsky.tsuzuri.ui.state.PreviewIcons
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import io.github.kotoriinsky.tsuzuri.ui.state.PreviewWallpaper
import io.github.kotoriinsky.tsuzuri.ui.state.rememberScreenShape
import kotlin.math.roundToInt

/**
 * 参数二级页里的**内嵌预览台**（预览与它控制的参数必须在同一页 ——
 * 分处两页等于逼用户来回翻）。
 *
 * ## 一页只画「这一页滑块控制的东西」
 *
 * 画哪种预览由**这一组里出现了哪些参数**推导（[previewKindOf]，按键后缀），不是按分组名硬编码：
 *
 * | 这一组里有 | 画什么 |
 * | --- | --- |
 * | `hot_width_percent` | 两个下角的热区矩形叠在**手机轮廓**上 |
 * | `window_width_percent` | 小窗矩形 |
 * | `selection_radius_percent` | 弧、每一项的真实图标、第 1 项的选中态 |
 * | `icon_shape` / `icon_alpha` / `icon_size_dp` | 同上（形状、描边、不透明度的效果只能在这里看） |
 * | `fan_reveal_amount` | 同上，并**播一遍展开动画**（速度/错开/自转实时反映） |
 *
 * 为什么按键后缀而不是分组标识：分组标识是 Catalog 里的自由文本，改一次分组名就会让预览
 * **静默消失**（页面照常渲染，只是没有预览）；而参数键是跨进程契约的一部分，不会随手改。
 *
 * ⚠️ 上面那几行后缀**只属于 `corner_small_window`**（grep 过 Catalog，并由
 * `FanPreviewCoverageTest` 钉住），所以按后缀判种类不会让别的特性「凭空长出一个预览」。
 *
 * ## 几何一个数都不在这里算
 *
 * 全部来自 `core` 的 `fanPreview()`（= 真机同一份 `FanSolve`）。这里只做一件事：
 * 把配置值读成 [FanPreviewParams]。**不许**在这里推半径/角度/窗口位置 —— 两份几何必然漂移。
 *
 * ## 取不到时的表现（不许崩、不许白屏）
 *
 *  - 这一组没有可预览的参数 → 什么都不画（不是错误）；
 *  - 该有的参数在 Catalog 里读不到 / 求解抛异常 → 一句「预览不可用」，不画假的；
 *  - 清单为空 → 按 [SAMPLE_COUNT] 项示意，并在下面如实说明这是示意；
 *  - 有项超出屏幕不再提示（用户 2026-10-27：没必要），预览照实画出即可。
 *
 * ## 2026-09-19 这一轮补的三件事（用户复核实际包时提的「实时预览根本没做」）
 *
 *  1. **画面像一台手机**：设备外框 + 底衬 + 手势条 + 真实壁纸（读不到则中性示意底图，
 *     并在卡片右上角挂一个「示意底图」标签）+ 整屏小地图（裁切时才出现，见画布）；
 *  2. **动效页播真动画**（原先只画静态终态与一层起点虚影）—— 时长与曲线取 core 的
 *     [FanAnimationTiming]，与真机同一套公式（那份公式的出处写在 `FanReveal.kt`）；
 *  3. **每个参数要么进预览、要么在参数行上标出来** —— 判据是 core 的
 *     `PrefSpec.feedsFanPreview()`，标注在 `PrefRow` 里做（`FeatureSectionScreen` 传下去）。
 *
 * @param groupSpecs 这一组自己的参数（用来决定画什么）
 * @param featureSpecs 整个特性的参数（用来读值：扇形排列那一组要用的图标直径声明在
 *   「图标与外观」组里，只拿本组参数会读不到）
 */
@Composable
fun FanPreviewSection(
    featureKey: String,
    groupSpecs: List<PrefSpec>,
    featureSpecs: List<PrefSpec>,
    prefs: Prefs,
    modifier: Modifier = Modifier,
) {
    val kind = previewKindOf(groupSpecs) ?: return
    val kit = LocalFpKit.current
    val context = LocalContext.current

    // 屏幕取**当前所在屏幕**的真实尺寸 / 圆角 / 挖孔（2026-10-21，见 rememberScreenShape）：
    // 平板、折叠屏内外屏、横屏都按真比例画，折叠 / 展开、转屏时自动重读。
    // 读不到时退回本窗口的 displayMetrics（只少了圆角与挖孔的真值，几何仍可用）。
    val metrics = context.resources.displayMetrics
    val screen = rememberScreenShape()
    val density = metrics.density
    val screenW = screen?.width ?: metrics.widthPixels.toFloat()
    val screenH = screen?.height ?: metrics.heightPixels.toFloat()

    val items = remember(prefs, featureKey) {
        FanItems.spec(featureKey)?.let { FanItems.read(prefs, it) }.orEmpty()
    }
    val maxTargets = remember(prefs, featureKey) {
        FanItems.siblingNumber(prefs, featureKey, "fan_max_targets")?.toInt()
    }

    val input = remember(kind, featureSpecs, prefs, screenW, screenH, density, items, maxTargets) {
        buildPreviewInput(kind, featureSpecs, prefs, screenW, screenH, density, items, maxTargets)
    }
    val preview = remember(input) {
        input?.let { runCatching { fanPreview(it.params) }.getOrNull() }
    }

    // 真实图标：解析走 IO（`PackageManager` 与图标包 APK 的 appfilter 都是跨进程读），
    // 解析链本身在 `:hook` 的 `FanIconPreview` —— 就是真机扇子用的那一条，
    // 所以画出来的图标与扇子里的是同一张，不是"长得像"。
    //
    // key 传的是**清单项**（应用 = 包名，快捷方式 = 包名/id），不是包名：
    // 单项覆盖就是按这个键落的，传包名会让快捷方式的覆盖失效。
    val iconCount = preview?.items?.size ?: 0
    val icons by produceState(
        initialValue = emptyList<ImageBitmap?>(),
        featureKey,
        items,
        iconCount,
        prefs,
    ) {
        value = if (iconCount == 0) {
            emptyList()
        } else {
            PreviewIcons.load(context, featureKey, items, iconCount, prefs)
        }
    }

    // 壁纸：先走 WallpaperManager，失败再走 root 读文件本体（动态壁纸下只有这条路，
    // 见 PreviewWallpaper 的类注释）。读不到就是 null —— 画布画中性示意底图，
    // 卡片右上角的标签会如实说它是怎么来的（实时读取 / root 读取 / 示意底图）。
    val wallpaper by produceState(initialValue = PreviewWallpaper.Result.NONE, AppSettings.previewWallpaper, PreviewWallpaper.revision) {
        value = if (AppSettings.previewWallpaper) PreviewWallpaper.load(context) else PreviewWallpaper.Result.NONE
    }

    // ── 动效：这一页是在调「展开动画」吗？──
    // 判据是这一组里有没有 `fan_motion`（= 展开动画预设，只有动效那一组有它；
    // 原来认的 fan_reveal_amount 已移进隐藏分组），与 previewKindOf 同一条「按键后缀认页」的规矩。
    val anim = remember(groupSpecs, featureSpecs, prefs, iconCount) {
        if (groupSpecs.none { it.key.endsWith("." + io.github.kotoriinsky.tsuzuri.core.FanMotion.NAME) }) {
            null
        } else {
            animationParamsOf(featureSpecs, prefs, iconCount)
        }
    }
    val timing = remember(anim) {
        anim?.let { FanAnimationTiming(it.speedPercent, it.count) }
    }
    // 循环播放：动效参数**只能看不能读**，所以这一页的预览一直在演。
    // ⚠️ 状态更新写在**绘制阶段读**（见画布的 elapsedMs 参数）：如果毫秒数在组合阶段被读出来，
    // 这一页会以 60Hz 重组，而拖动滑杆时还叠着草稿更新 —— 那是白烧的帧。
    val elapsed = remember(timing) { mutableFloatStateOf(0f) }
    if (timing != null) {
        LaunchedEffect(timing) {
            var origin = -1L
            val cycle = timing.totalMs + ANIM_LOOP_GAP_MS
            while (true) {
                val now = withFrameNanos { it }
                if (origin < 0L) origin = now
                elapsed.floatValue = ((now - origin) / 1_000_000f) % cycle
            }
        }
    }

    // 不挂「预览」小标题：示意图直接放在页面顶部（照 HyperOS「自由窗口」页）
    kit.VSpace(io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace.m)
    // 预览不放白卡：手机轮廓直接落在页面底色上（用户 2026-10-25：预览白底也不需要）
    androidx.compose.foundation.layout.Column(modifier.padding(horizontal = io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace.m)) {
        if (preview == null || input == null) {
            kit.Paragraph(fpString("fan_preview.unavailable"), FpEmphasis.MUTED)
            return@Column
        }
        // 来源徽标（壁纸快照 / 示意底图）删了：系统壁纸现在稳定可得，
        // 只在**想用壁纸却没拿到**时，于画布下方说一句原因（见下面 PreviewWallpaperReason）。
        kit.VSpace(6.dp)
        FanPreviewCanvas(
            preview = preview,
            kind = kind,
            modifier = Modifier.fillMaxWidth(),
            icons = icons,
            wallpaper = wallpaper.bitmap,
            animationTiming = timing,
            elapsedMs = { elapsed.floatValue },
            rotationDegrees = anim?.rotationDeg ?: 0f,
            screen = screen,
            // 「内容」页（排布 + 清单）的扇形预览约半屏高：要看得清整把扇子怎么排（用户 2026-10-22）
            height = if (groupSpecs.any { it.key.endsWith(".fan_layout_mode") }) {
                (LocalConfiguration.current.screenHeightDp * 0.5f).dp
            } else {
                null
            },
        )

        // 滑杆已经显示参数；预览仅解释系统布局与参考虚线，不重复像素读数。
        if (kind == FanPreviewKind.WINDOW) {
            if (preview.windowRect != null && input.params.windowPreset == WindowBounds.PRESET_CUSTOM &&
                preview.windowRect != WindowBounds.defaults(preview.screenW, preview.screenH)
            ) {
                kit.Paragraph(fpString("fan_preview.window.default"), FpEmphasis.MUTED)
            }
        }
        PreviewWallpaperReason(wallpaper)
        kit.VSpace(8.dp)
    }
}

/**
 * 这一组该画哪种预览；`null` = 这一组没有可预览的参数。
 *
 * 优先级是刻意的（小窗摆放 > 触发区 > 扇形）：三个都命中的组合在现在的 Catalog 里不存在
 * （它们分属不同分组），万一以后被合并到一组，画**信息量最大**的那个。
 *
 * 扇形那一档列了五个后缀：几何（`selection_radius_percent`）、形状与描边（`icon_shape`）、
 * 不透明度（`icon_alpha`）、图标大小（`icon_size_dp`）、展开行程（`fan_reveal_amount`）。
 * **它们必须都归到 FAN**——
 * 「图标与外观」那一页没有半径滑杆，只认 `selection_radius_percent` 的话，
 * 用户拖形状/描边/不透明度时会看到一个**空参数页**（一个预览都没有），
 * 而那正是这几条反馈的落点；「动效」那一页同理。
 */
internal fun previewKindOf(groupSpecs: List<PrefSpec>): FanPreviewKind? {
    fun has(suffix: String) = groupSpecs.any { it.key.endsWith(".$suffix") }
    return when {
        has("window_width_percent") -> FanPreviewKind.WINDOW
        has("hot_width_percent") -> FanPreviewKind.TRIGGER
        has("selection_radius_percent") -> FanPreviewKind.FAN
        has("icon_shape") -> FanPreviewKind.FAN
        has("icon_alpha") -> FanPreviewKind.FAN
        has("icon_size_dp") -> FanPreviewKind.FAN
        has("fan_reveal_amount") -> FanPreviewKind.FAN
        else -> null
    }
}

/** 动画所需的两个参数（读不到就整页退回静态预览，不猜默认值）。 */
private class AnimationParams(
    val speedPercent: Float,
    val rotationDeg: Float,
    val count: Int,
)

/**
 * 读 `animation_speed` / `fan_rotation`。
 *
 * 与 [buildPreviewInput] 同一条规矩：**读不到就不播动画**（而不是兜一个默认速度）——
 * 用默认速度演给用户看，等于让他照着一个不是自己配置的节奏去判断手感。
 */
private fun animationParamsOf(
    featureSpecs: List<PrefSpec>,
    prefs: Prefs,
    count: Int,
): AnimationParams? {
    fun number(suffix: String): Float? {
        val s = featureSpecs.filterIsInstance<PrefSpec.Slider>()
            .firstOrNull { it.key.endsWith(".$suffix") } ?: return null
        return runCatching { prefs.value(s) }.getOrNull()
    }
    val speed = number("animation_speed") ?: return null
    val rotation = number("fan_rotation") ?: return null
    return AnimationParams(
        speedPercent = if (speed.isFinite() && speed > 0f) speed else 100f,
        rotationDeg = rotation,
        count = count.coerceAtLeast(1),
    )
}

/** 读好的参数 + 「是不是示意」（清单为空时用固定项数）—— 界面要如实说明后者。 */
private class PreviewInput(val params: FanPreviewParams, val sampled: Boolean)

/**
 * 配置值 → [FanPreviewParams]。**任一项必需参数读不到就返回 null**（界面显示「预览不可用」）。
 *
 * 为什么不给每一项兜一个默认值：默认值会变成**第二份事实**（Catalog 一份、这里一份），
 * 改了一边另一边不动，预览就会长期用错的值而没有任何提示 —— 那正是「预览骗人」的成因，
 * 而用户会相信预览、然后发现被耍了。唯一的例外是 `icon_shape`：
 * `IconShape.of(null)` 的回退（正圆）是 core 自己定义的契约，不是这里的猜测。
 */
private fun buildPreviewInput(
    kind: FanPreviewKind,
    featureSpecs: List<PrefSpec>,
    prefs: Prefs,
    screenW: Float,
    screenH: Float,
    density: Float,
    items: List<String>,
    maxTargets: Int?,
): PreviewInput? {
    fun number(suffix: String): Float? {
        val s = featureSpecs.filterIsInstance<PrefSpec.Slider>()
            .firstOrNull { it.key.endsWith(".$suffix") } ?: return null
        return runCatching { prefs.value(s) }.getOrNull()
    }

    fun choice(suffix: String): String? {
        val c = featureSpecs.filterIsInstance<PrefSpec.Choice>()
            .firstOrNull { it.key.endsWith(".$suffix") } ?: return null
        return runCatching { prefs.value(c) }.getOrNull()
    }

    fun toggle(suffix: String): Boolean? {
        val t = featureSpecs.filterIsInstance<PrefSpec.Toggle>()
            .firstOrNull { it.key.endsWith(".$suffix") } ?: return null
        return runCatching { prefs.value(t) }.getOrNull()
    }

    // 这一组画几项：清单里有几项就画几项；空了就按示意项数（并在界面上说明）。
    // 上限跟着「每屏槽位数」走 —— 真机一屏也放不下更多
    val sampled = items.isEmpty()
    val raw = if (sampled) SAMPLE_COUNT else items.size
    val count = raw.coerceAtLeast(1).let { n ->
        if (maxTargets != null && maxTargets > 0) n.coerceAtMost(maxTargets) else n
    }

    val params = FanPreviewParams(
        screenW = screenW,
        screenH = screenH,
        density = density,
        hotWidthPercent = number("hot_width_percent") ?: return null,
        hotHeightPercent = number("hot_height_percent") ?: return null,
        corners = choice("corners") ?: return null,
        layoutMode = choice("fan_layout_mode") ?: return null,
        customInner = (number("fan_custom_inner_count") ?: 0f).toInt(),
        customMiddle = (number("fan_custom_middle_count") ?: 0f).toInt(),
        radiusPercent = number("selection_radius_percent") ?: return null,
        iconDp = number("icon_size_dp") ?: return null,
        edgeMarginDp = number("fan_edge_margin") ?: return null,
        // `fan_row_stagger` 是字符串，折成格数：`half` = 错开半格，其余（含认不出的）= 对齐。
        // 与 hook 侧读同一个键的折算一致（那边也是「不是 half 就当对齐」）
        staggerSlots = if (choice("fan_row_stagger") == "half") 0.5f else 0f,
        selectionScalePercent = number("selection_scale") ?: return null,
        iconShape = IconShape.of(choice("icon_shape")),
        // ── 渲染参数：形状数值 / 描边 / 不透明度 / 展开行程 ───────────────────
        // 这 7 个不是「可有可无的装饰」：预览要画的形状、描边、淡出、起点、选中环全靠它们。
        // 所以**读不到就返回 null**（界面说「预览不可用」），而不是在 ui 侧兜一个默认值 ——
        // 兜默认值的后果是预览用默认值画、真机用用户值画，而用户相信预览（见类注释）
        iconAlphaPercent = number("icon_alpha") ?: return null,
        revealAmountPercent = number("fan_reveal_amount") ?: return null,
        edgeEnabled = toggle("icon_edge") ?: return null,
        edgeWidthDp = number("icon_edge_width") ?: return null,
        selectionRing = toggle("selection_ring") ?: return null,
        shapeCornerPercent = number("icon_shape_corner") ?: return null,
        shapeExponent = number("icon_shape_exponent") ?: return null,
        // 档位：**读不到就返回 null**（与上面那条同规矩）。预览不能自己猜一个档 ——
        // 猜错的表现是「预览画 Flyme 那块、真机摆系统那块」，而用户相信预览。
        windowPreset = choice("window_preset") ?: return null,
        // 四个滑杆无论在哪一档都读：`system` 档下它们不参与运算（`resolvePreset` 不读），
        // 但读了不产出任何画面差别，与「读不到就不给预览」那条不冲突
        // （缺了它们会让 `custom` 档读不出几何，所以仍然要求必须存在）。
        windowWidthPercent = number("window_width_percent") ?: return null,
        windowHeightPercent = number("window_height_percent") ?: return null,
        windowXPercent = number("window_x_percent") ?: return null,
        windowYPercent = number("window_y_percent") ?: return null,
        count = count,
    )
    return PreviewInput(params, sampled)
}

/** 清单为空时的示意项数：接近出厂默认的每屏槽位数，看得出「一排几个、一共几排」。 */
private const val SAMPLE_COUNT = 7

/** 动画循环之间的停顿：演完停一下再演下一轮，否则用户看不清「一轮的终点」。 */
private const val ANIM_LOOP_GAP_MS = 500f
