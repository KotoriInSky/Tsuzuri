package io.github.kotoriinsky.tsuzuri.core.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「自定义小窗位置与尺寸」（[WindowBounds]）—— **这一层是预览与真机共用的唯一一份公式**，
 * 所以要钉的不是「某个数等于多少」，而是六类不会被上游参数挡住的性质：
 *
 *  1. **基准**：宽 × 屏宽、高 × 屏高；x/y 是「窗口中心在**可移动区间**里的比例」；
 *  2. **整段量程都有效**（v3 存在的唯一理由）：x 每加一点，左边缘就必须动一点。
 *     v2 的「中心绝对位置」语义下 `x ∈ [0,40]` 全被夹成贴左 —— 80% 的行程是死的；
 *  3. **端点语义**：0 = 贴左/贴顶，100 = 贴右/贴底；
 *  4. **不出屏**：任何合法百分比下四条边都在 `[0, 屏幕]` 内；
 *  5. **退化不产 NaN**：NaN / Inf / 0 或负的屏幕 → 空矩形（可被 [WindowBounds.isUsable] 判掉）；
 *  6. **最小尺寸**：低于 [WindowBounds.MIN_SIDE_PX] 一律判为不可用 —— 调用方据此
 *     「不覆写、交给系统摆放」（保守方向）。
 *
 * 外加三条**防漂移**断言：
 *  - 预览的 `windowRect` 必须就是这里算出来的那个值；
 *  - 出厂默认值必须复现 Flyme 实测那块窗口（默认值存在的全部意义）；
 *  - 两段换算（[WindowBounds.centerPercentFromEdgePercent] v1→v2、
 *    [WindowBounds.ratioPercentFromCenterPercent] v2→v3）必须**保住用户当年看到的位置**——
 *    老配置是不可复现的输入，迁错了没人会来报 bug。
 *
 * 真机语言环境是竖屏，但公式**不假设朝向**，所以横屏尺寸也过一遍。
 */
class WindowBoundsTest {

    /** 本机（Xiaomi 17 Pro）的真实尺寸：1220 × 2656。 */
    private val w = 1220f
    private val h = 2656f

    @Test
    fun widthUsesTheScreenWidthAndHeightUsesTheScreenHeight() {
        // ⚠️ 两个百分比的基准**不同**（上游实测结论）：宽 × 屏宽、高 × 屏高。
        // 想当然都按短边算，横竖屏下尺寸会全错。
        val r = WindowBounds.resolve(w, h, 70f, 70f, 50f, 50f)
        assertEquals(w * 0.70f, r.width, 0.01f)
        assertEquals(h * 0.70f, r.height, 0.01f)
        // x/y = 50 在**任何**窗口尺寸下都是「居中」（v3 语义：可移动区间的正中）
        assertEquals(w / 2f, r.centerX, 0.01f)
        assertEquals(h / 2f, r.centerY, 0.01f)
        // 相对量而不是绝对像素：屏幕大一倍，窗口也大一倍
        val big = WindowBounds.resolve(w * 2f, h * 2f, 70f, 70f, 50f, 50f)
        assertEquals(r.width * 2f, big.width, 0.01f)
        assertEquals(r.height * 2f, big.height, 0.01f)
    }

    @Test
    fun theWholeSliderTravelActuallyMovesTheWindow() {
        // **这条就是 v3 的全部意义**，也是「小窗位置有点问题」那个真缺陷的回归测试。
        //
        // v2 的语义是「中心落在屏幕 x% 处」，而窗口占 80% 屏宽时水平只有 20% 屏宽的
        // 自由行程 —— 于是 x∈[0,40] 全被夹成贴左、x∈[60,100] 全被夹成贴右。
        // 用户拖 80% 的行程，窗口一动不动，只能认为「这个参数坏了」。
        //
        // 断言写成「相邻刻度的位移必须 > 0」：这正是死行程的反面。
        for (sizePercent in listOf(20f, 50f, 80f, 95f)) {
            var previous = -1f
            for (step in 0..20) {
                val x = step * 5f
                val left = WindowBounds.resolve(w, h, sizePercent, 40f, x, 50f).left
                assertTrue(
                    left > previous,
                    "尺寸 $sizePercent%：x 从 ${x - 5f} 到 $x 时左边缘没有移动（$previous → $left）",
                )
                previous = left
            }
            // 整段行程正好覆盖 [0, 屏幕 − 窗口]：不多不少
            assertEquals(0f, WindowBounds.resolve(w, h, sizePercent, 40f, 0f, 50f).left, 0.01f)
            assertEquals(
                w - w * sizePercent / 100f,
                WindowBounds.resolve(w, h, sizePercent, 40f, 100f, 50f).left,
                0.01f,
            )
        }
    }

    @Test
    fun percentageEndsAreFlushAgainstTheScreenEdges() {
        // 端点语义：0 = 贴左/贴顶，100 = 贴右/贴底
        val left = WindowBounds.resolve(w, h, 70f, 70f, 0f, 0f)
        assertEquals(0f, left.left, 0.01f)
        assertEquals(0f, left.top, 0.01f)
        val right = WindowBounds.resolve(w, h, 70f, 70f, 100f, 100f)
        assertEquals(w, right.right, 0.01f)
        assertEquals(h, right.bottom, 0.01f)
        // 居中是**唯一**一个不依赖窗口尺寸的位置
        for (sizePercent in listOf(30f, 70f, 90f)) {
            val c = WindowBounds.resolve(w, h, sizePercent, sizePercent, 50f, 50f)
            assertEquals(w / 2f, c.centerX, 0.01f, "宽 $sizePercent% 时 x%=50 必须居中")
            assertEquals(h / 2f, c.centerY, 0.01f)
        }
    }

    @Test
    fun oversizedWindowIsClampedToTheScreenNotIntoNegativeCoordinates() {
        // 尺寸超过屏幕（正常情况下 Catalog 的 max 挡着，但存储是可以被手改的）：
        // 必须夹成整屏，**不能**出现负的 left —— 负坐标在 setLaunchBounds 那边
        // 是「多屏/虚拟屏」的语义，会把小窗丢到主屏之外
        val huge = WindowBounds.resolve(w, h, 500f, 500f, 50f, 50f)
        assertEquals(ScreenRect(0f, 0f, w, h), huge)
        assertTrue(huge.left >= 0f && huge.top >= 0f)
    }

    @Test
    fun outOfRangePercentIsStillClampedInsideTheScreen() {
        // v3 的公式本来就落在 [0, 屏幕 − 窗口] 内（它乘的就是这段行程），
        // 所以夹取现在是**兜底**：存储里的值被手改成负数或 >100 时仍不能出屏
        for (x in listOf(-50f, -1f, 101f, 500f)) {
            val r = WindowBounds.resolve(w, h, 70f, 70f, x, x)
            assertTrue(r.left >= 0f && r.top >= 0f, "x%=$x → $r")
            assertTrue(r.right <= w + 0.01f && r.bottom <= h + 0.01f, "x%=$x → $r")
        }
    }

    @Test
    fun noPercentageCombinationLeavesTheScreenInEitherOrientation() {
        // 「不出屏」是硬保证：任何合法百分比组合、两种朝向都要成立。
        val sizes = listOf(30f, 70f, 90f, 95f, 100f)
        val positions = listOf(0f, 1f, 15f, 50f, 99f, 100f)
        for ((sw, sh) in listOf(w to h, h to w, 1080f to 2400f, 1f to 1f)) {
            for (wp in sizes) for (hp in sizes) for (xp in positions) for (yp in positions) {
                val r = WindowBounds.resolve(sw, sh, wp, hp, xp, yp)
                val note = "屏幕 ${sw}x$sh w%=$wp h%=$hp x%=$xp y%=$yp → $r"
                assertTrue(r.width.isFinite() && r.height.isFinite(), note)
                assertTrue(r.width >= 0f && r.height >= 0f, note)
                assertTrue(r.left >= 0f && r.top >= 0f, note)
                assertTrue(r.right <= sw + 0.01f && r.bottom <= sh + 0.01f, note)
            }
        }
    }

    @Test
    fun degenerateInputsDegradeToAnEmptyRectInsteadOfNaN() {
        // 预览画 NaN = 空白（用户看到「预览没了」，没有任何提示）；真机的 setLaunchBounds
        // 收到 NaN 会让窗口位置未定义。所以非法输入必须**明确**退化成空矩形。
        val nan = Float.NaN
        for (r in listOf(
            WindowBounds.resolve(nan, h, 70f, 70f, 50f, 50f),
            WindowBounds.resolve(w, nan, 70f, 70f, 50f, 50f),
            WindowBounds.resolve(w, h, nan, 70f, 50f, 50f),
            WindowBounds.resolve(w, h, 70f, nan, 50f, 50f),
            WindowBounds.resolve(w, h, 70f, 70f, nan, 50f),
            WindowBounds.resolve(w, h, 70f, 70f, 50f, nan),
            WindowBounds.resolve(Float.POSITIVE_INFINITY, h, 70f, 70f, 50f, 50f),
            WindowBounds.resolve(w, h, Float.NEGATIVE_INFINITY, 70f, 50f, 50f),
        )) {
            assertEquals(WindowBounds.EMPTY, r)
            assertTrue(r.left.isFinite() && r.right.isFinite(), "$r")
        }
        for ((sw, sh) in listOf(0f to 0f, -100f to -100f, 1220f to 0f, 0f to 2656f)) {
            val r = WindowBounds.resolve(sw, sh, 70f, 70f, 50f, 50f)
            assertTrue(r.width >= 0f && r.height >= 0f, "屏幕 ${sw}x$sh → $r")
            assertTrue(r.left >= 0f && r.top >= 0f, "屏幕 ${sw}x$sh → $r")
            assertFalse(WindowBounds.isUsable(r), "屏幕 ${sw}x$sh 算出的矩形必须判为不可用：$r")
        }
        assertEquals(WindowBounds.EMPTY, WindowBounds.resolve(0f, 0f, 70f, 70f, 50f, 50f))
    }

    @Test
    fun minimumUsableSideIsOnePixel() {
        assertEquals(1f, WindowBounds.MIN_SIDE_PX)
        assertFalse(WindowBounds.isUsable(WindowBounds.EMPTY), "空矩形必须判为不可用")
        assertFalse(WindowBounds.isUsable(ScreenRect(0f, 0f, 0.5f, 100f)), "宽不足")
        assertFalse(WindowBounds.isUsable(ScreenRect(0f, 0f, 100f, 0.5f)), "高不足")
        assertTrue(WindowBounds.isUsable(ScreenRect(0f, 0f, 1f, 1f)), "刚好 1px 算可用")
        assertFalse(WindowBounds.isUsable(WindowBounds.resolve(0f, 0f, 70f, 70f, 50f, 50f)))
        assertTrue(WindowBounds.isUsable(WindowBounds.resolve(w, h, 70f, 70f, 50f, 50f)))
    }

    @Test
    fun defaultsAreTheFlymeMeasuredPreset() {
        // 出厂默认值是**实测**出来的，不是审美。有人「顺手」调成好看的数字时这条会红。
        assertEquals(80f, WindowBounds.DEFAULT_WIDTH_PERCENT)
        assertEquals(63.5f, WindowBounds.DEFAULT_HEIGHT_PERCENT)
        assertEquals(50f, WindowBounds.DEFAULT_X_PERCENT)
        // y 在 v3 的**比例**口径下是 41.7（Flyme 上边距 406 ÷ 可移动区间 974）。
        // 别把 v2 的 47 混进来 —— 那是同一个位置的另一种口径。
        assertEquals(41.7f, WindowBounds.DEFAULT_Y_PERCENT)
        val defaults = WindowBounds.defaults(w, h)
        assertTrue(WindowBounds.isUsable(defaults), "出厂默认必须是一个可用矩形：$defaults")
        assertEquals(w / 2f, defaults.centerX, 0.01f, "水平必须居中")
    }

    @Test
    fun theDefaultsReproduceTheWindowFlymeActuallyShows() {
        // 「默认值和 Flyme 一样」是可证伪的断言，不是一句口头承诺。
        //
        // 实测来源：Flyme 参考机（MEIZU 22 / 1200×2670）上底角斜滑拉起的真实小窗，
        // 截屏后用亮度跳变测出面板四边（截图 `build/ui-shots/flyme_fan.png`）：
        //   left=120 right=1079 top=406 bottom≈2095（底部含拖拽把手）
        //
        // 断言用的是**四条留边的百分比**而不是绝对像素（换块屏就不一样了）——
        // 那才是「位置一样」的定义。容差 0.4% ≈ 10px（像素测量的噪声量级）。
        val flymeW = 1200f
        val flymeH = 2670f
        val flyme = WindowBounds.defaults(flymeW, flymeH)
        assertEquals(100f * 120f / flymeW, 100f * flyme.left / flymeW, 0.4f, "左边距")
        assertEquals(100f * 406f / flymeH, 100f * flyme.top / flymeH, 0.4f, "上边距")
        assertEquals(100f * 960f / flymeW, 100f * flyme.width / flymeW, 0.4f, "窗口宽")
        assertEquals(100f * 1696f / flymeH, 100f * flyme.height / flymeH, 0.4f, "窗口高")
        // 水平居中：左右留边相等
        assertEquals(flyme.left, flymeW - flyme.right, 0.5f)
        // ⚠️ 垂直方向**不**居中，而且这是 Flyme 本身就如此（上 15.2% / 下 21.3%）。
        // 把默认值改成"居中"会与实测不符 —— 想居中就把参数拖到 50，别改默认值。
        assertTrue(
            flyme.top < flymeH - flyme.bottom - 40f,
            "Flyme 的窗口在正中偏上，默认值不应居中：top=${flyme.top}",
        )
    }

    @Test
    fun legacyDefaultsAreTheValuesThatWereReallyInEffectInV1() {
        // 迁移要用它们还原「用户当年看到的画面」。它们**不能**跟着新默认值一起改。
        assertEquals(70f, WindowBounds.LEGACY_DEFAULT_WIDTH_PERCENT)
        assertEquals(70f, WindowBounds.LEGACY_DEFAULT_HEIGHT_PERCENT)
        assertEquals(15f, WindowBounds.LEGACY_DEFAULT_X_PERCENT)
        assertEquals(15f, WindowBounds.LEGACY_DEFAULT_Y_PERCENT)
        // v2 的 y 默认（中心绝对位置口径）只服务于 v2→v3 那一步
        assertEquals(47f, WindowBounds.V2_DEFAULT_Y_PERCENT)
    }

    @Test
    fun edgeToCenterConversionKeepsTheCenterUsersActuallySaw() {
        // v1→v2：v1 的默认值（15% 左边缘 + 70% 宽）对应的中心是 50%
        assertEquals(50f, WindowBounds.centerPercentFromEdgePercent(15f, 70f), 0.01f)
        assertEquals(35f, WindowBounds.centerPercentFromEdgePercent(0f, 70f), 0.01f)
        assertEquals(65f, WindowBounds.centerPercentFromEdgePercent(100f, 70f), 0.01f)
        // 夹取：v1 里「左边缘 100%」会被夹成贴右，中心因此是 65% 而不是 100%
        assertEquals(
            WindowBounds.centerPercentFromEdgePercent(999f, 70f),
            WindowBounds.centerPercentFromEdgePercent(100f, 70f),
            0.01f,
        )
        // 尺寸 ≥ 屏幕：位置参数没有自由度（v1 会把左边缘夹成 0），只能给 50
        assertEquals(50f, WindowBounds.centerPercentFromEdgePercent(30f, 100f), 0.01f)
        assertEquals(50f, WindowBounds.centerPercentFromEdgePercent(30f, 500f), 0.01f)
        for (size in listOf(30f, 50f, 64f, 70f, 80f, 90f, 95f, 100f)) {
            for (edge in listOf(-10f, 0f, 15f, 33f, 50f, 77f, 100f, 150f)) {
                val c = WindowBounds.centerPercentFromEdgePercent(edge, size)
                assertTrue(
                    c >= size / 2f - 0.01f && c <= 100f - size / 2f + 0.01f,
                    "size%=$size edge%=$edge → 中心 $c 越界",
                )
            }
        }
    }

    @Test
    fun centerToRatioConversionKeepsTheWindowWhereUsersSawIt() {
        // v2→v3。等价性判据是**左边缘**（那才是屏幕上看得见的东西），不是那个百分比：
        // 换算前后用各自版本的公式算出的 left 必须一致。
        for ((size, center) in listOf(
            80f to 50f, 80f to 47f, 80f to 20f, 80f to 100f, 80f to 40f,
            63.5f to 47f, 70f to 15f, 70f to 65f, 30f to 33f, 95f to 3f,
        )) {
            // v2 的 left（百分比口径）
            val v2Left = (center - size / 2f).coerceIn(0f, 100f - size)
            val ratio = WindowBounds.ratioPercentFromCenterPercent(center, size)
            // v3 的 left
            val v3Left = (100f - size) * ratio / 100f
            assertEquals(v2Left, v3Left, 0.05f, "size%=$size center%=$center")
        }
        // 端点与退化
        assertEquals(50f, WindowBounds.ratioPercentFromCenterPercent(50f, 80f), 0.01f, "居中仍是 50")
        assertEquals(0f, WindowBounds.ratioPercentFromCenterPercent(20f, 80f), 0.01f, "v2 的贴左")
        assertEquals(100f, WindowBounds.ratioPercentFromCenterPercent(100f, 80f), 0.01f, "v2 的贴右")
        assertEquals(50f, WindowBounds.ratioPercentFromCenterPercent(30f, 100f), 0.01f, "没有行程")
        assertEquals(50f, WindowBounds.ratioPercentFromCenterPercent(30f, 500f), 0.01f)
        for (size in listOf(20f, 50f, 63.5f, 80f, 95f, 99f)) {
            for (center in listOf(-10f, 0f, 20f, 50f, 80f, 100f, 120f)) {
                val r = WindowBounds.ratioPercentFromCenterPercent(center, size)
                assertTrue(r in 0f..100f, "size%=$size center%=$center → 比例 $r 越界")
            }
        }
    }

    @Test
    fun theEdgeConversionIsExactlyWhatResolveDidInV1() {
        // 交叉验证 v1 那一段：用 **v1 的公式**独立算一遍中心，与换算结果一致
        for ((size, edge) in listOf(
            70f to 15f, 70f to 0f, 70f to 100f, 80f to 10f, 40f to 60f, 95f to 3f,
        )) {
            val leftPx = (w * edge / 100f).coerceIn(0f, w - w * size / 100f)
            val widthPx = w * size / 100f
            val v1CenterPercent = (leftPx + widthPx / 2f) / w * 100f
            assertEquals(
                v1CenterPercent,
                WindowBounds.centerPercentFromEdgePercent(edge, size),
                0.02f,
                "size%=$size edge%=$edge",
            )
        }
    }

    @Test
    fun freeformScaleCompensationKeepsTheVisibleRectWhatTheUserConfigured() {
        // MIUI 会把 `setLaunchBounds` 的矩形**按 freeformScale 缩放**（实测 0.7，锚点左上角），
        // 不补偿的话窗口在屏幕上只有配置值的 70%、中心还会偏向左上。
        // 判据：补偿后交给 MIUI 的矩形 × scale，必须等于用户配的那块矩形。
        val desired = WindowBounds.defaults(w, h)
        for (scale in listOf(0.35f, 0.5f, 0.7f, 0.85f, 1.2f)) {
            val logical = WindowBounds.compensateForScale(desired, scale)
            // 原点不动（实测 MIUI 的缩放锚点就是左上角）
            assertEquals(desired.left, logical.left, 0.01f, "scale=$scale")
            assertEquals(desired.top, logical.top, 0.01f, "scale=$scale")
            // 尺寸 ÷ scale 之后，乘回来正好是配置值
            assertEquals(desired.width, logical.width * scale, 0.5f, "scale=$scale")
            assertEquals(desired.height, logical.height * scale, 0.5f, "scale=$scale")
        }
    }

    @Test
    fun freeformScaleCompensationIsSkippedForMissingOrAbsurdScales() {
        // 读不到（非 MIUI 的 options）或值越界时**原样返回**：宁可少补偿，
        // 也不要拿一个可疑的数把窗口算成别的尺寸（参考实现给的合法区间是 [0.35, 1.2]）
        val desired = WindowBounds.defaults(w, h)
        for (scale in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 0.1f, 1.5f, 3f)) {
            assertEquals(desired, WindowBounds.compensateForScale(desired, scale), "scale=$scale")
        }
        // 正好 1.0 时本来就无需补偿
        assertEquals(desired, WindowBounds.compensateForScale(desired, 1f))
    }

    @Test
    fun describeIsReadableForLogs() {
        // 日志里要能直接看出「哪几个参数算出了这个矩形」—— 排查「改了没反应」时
        // 第一件事就是确认参数有没有进公式。小数点必须是 `.`（固定 Locale.US）
        val r = WindowBounds.defaults(w, h)
        val d = WindowBounds.describe(80f, 63.5f, 50f, 41.7f, r)
        assertTrue(d.startsWith("w%=80.0 h%=63.5 x%=50.0 y%=41.7"), d)
        assertTrue(d.contains("[122,404]"), d)
        assertTrue(d.contains("976x1686"), d)
    }

    /**
     * 三档预设（`window_preset`）—— 这一层是**唯一**的判别实现。
     *
     * 上一版这条判别散在 hook 与预览两处（各自读一个布尔），语义一变就要同时改两处；
     * 漏一处的表现是「预览画 Flyme 那块、真机摆系统那块」，而且编译、日志全正常。
     * 所以这里要钉的不是「某个数」，而是**三档之间的边界**：
     * 哪一档读滑杆、哪一档不读。
     */
    @Test
    fun onlyTheCustomPresetReadsTheSliders() {
        // 把四个滑杆都给一组**极端值**，然后看它有没有影响到结果
        val sliders = listOf(31f, 97f, 0f, 100f)

        // system：无论滑杆是什么，都是 null（交给系统）
        assertNull(
            WindowBounds.resolvePreset(
                WindowBounds.PRESET_SYSTEM, w, h, sliders[0], sliders[1], sliders[2], sliders[3],
            ),
            "`system` 档必须给出 null（交给系统），滑杆读都不该读",
        )

        // flyme：无论滑杆是什么，都是那四个实测值
        for (v in listOf(sliders, listOf(80f, 63.5f, 50f, 41.7f))) {
            assertEquals(
                WindowBounds.defaults(w, h),
                WindowBounds.resolvePreset(
                    WindowBounds.PRESET_FLYME, w, h, v[0], v[1], v[2], v[3],
                ),
                "flyme 档必须恒等于 defaults()，与滑杆无关（否则用户一拖滑杆就回不到 Flyme 了）",
            )
        }

        // custom：滑杆说了算 —— 与 defaults() 明确不同，否则上面那条断言可能因为「恰好相等」而空转
        val custom = assertNotNull(
            WindowBounds.resolvePreset(
                WindowBounds.PRESET_CUSTOM, w, h, sliders[0], sliders[1], sliders[2], sliders[3],
            ),
        )
        assertEquals(WindowBounds.resolve(w, h, sliders[0], sliders[1], sliders[2], sliders[3]), custom)
        assertTrue(custom != WindowBounds.defaults(w, h), "这组测试值必须与出厂默认不同，否则断言没有区分力")
    }

    @Test
    fun theDefaultPresetIsTheFlymeOneSoItMatchesTheCatalogDefault() {
        // 出厂默认那一档必须是 `flyme`：`resolvePreset` 与 `Catalog` 的 `default` 是**同一个值**，
        // 否则「键没写进配置」这台机器上会出现「界面选了 Flyme、真机不覆写」。
        // 这条红了先看 `Catalog` 的 `window_preset` 默认值，别只改这里。
        assertEquals(WindowBounds.PRESET_FLYME, WindowBounds.DEFAULT_PRESET)
        assertTrue(WindowBounds.DEFAULT_PRESET in WindowBounds.PRESETS, "默认档必须是三档之一")
    }

    @Test
    fun anUnknownPresetFallsBackToTheDefaultOne() {
        // 认不出的取值（用户装过更新版本 / 键被手改）→ 走默认档。
        // ⚠️ 这条与「读不到」**必须**是同一个处置：两处不一致时的表现是
        // 「同样的配置在两台机器上一个覆写一个不覆写」，而且没有任何报错。
        val garbage = WindowBounds.resolvePreset("谁知道呢", w, h, 31f, 97f, 0f, 100f)
        assertEquals(WindowBounds.resolvePreset(null, w, h, 31f, 97f, 0f, 100f), garbage)
        assertEquals(WindowBounds.defaults(w, h), garbage)
    }

    @Test
    fun previewWindowRectComesFromTheSameImplementation() {
        // 防漂移：预览的 `windowRect` **必须**就是这里算出来的那个值。
        // 这条红了 = 有人在 ui 或 hook 里另写了一份公式（这个项目在别处吃过这个亏）
        for ((wp, hp, xp, yp) in listOf(
            listOf(80f, 63.5f, 50f, 41.7f),
            listOf(70f, 70f, 15f, 15f),
            listOf(30f, 20f, 0f, 0f),
            listOf(90f, 95f, 100f, 100f),
            listOf(55f, 40f, 62f, 8f),
        )) {
            val preview = fanPreview(previewParams(wp, hp, xp, yp)).windowRect
            assertNotNull(preview, "w%=$wp h%=$hp x%=$xp y%=$yp：custom 档就该给出矩形")
            assertEquals(
                WindowBounds.resolve(w, h, wp, hp, xp, yp),
                preview,
                "w%=$wp h%=$hp x%=$xp y%=$yp",
            )
        }
        assertEquals(
            null,
            fanPreview(previewParams(80f, 63.5f, 50f, 41.7f, preset = WindowBounds.PRESET_SYSTEM)).windowRect,
        )
    }

    // ─────────────── 横屏：形状与位置（方案 a「保持形状」）───────────────

    @Test
    fun landscapeKeepsThePortraitShapeInsteadOfAWideBar() {
        // 横屏 2670 × 1200（MEIZU 22 实测朝向）。
        // Flyme 那块窗可见 `[239,29]–[900,1170]` = 661 × 1141：竖向几乎贴满、横向只占四分之一。
        //
        // 容差取 25px：实测是单样本（只有一个应用），形状基准差 2% 就够吃满这个量，
        // 钉「数量级与方向」而不是钉死那个像素值 —— 换一个应用再量一次后可能要收窄。
        val r = WindowBounds.landscape(2670f, 1200f, 80f, 63.5f)
        assertTrue(WindowBounds.isUsable(r), "必须算出可用矩形：$r")
        assertEquals(1141f, r.height, 25f, "高 = 横屏高的 95.1%")
        assertEquals(239f, r.left, 25f, "靠左（实测左边距 239）")
        assertEquals(29f, r.top, 25f, "垂直居中（实测上下留边 29 / 30）")

        // ★ 这条是**方向**断言，也是这整段改动存在的理由：
        //   旧公式（一套百分比 × 当下屏幕）在横屏给出的是一块横条，形状正好是反的。
        val bar = WindowBounds.resolve(2670f, 1200f, 80f, 63.5f, 50f, 41.7f)
        assertTrue(bar.width > bar.height, "旧公式在横屏是横条：$bar")
        assertTrue(r.width < r.height, "新公式必须是竖屏形状：$r")
        // 宽度只占横屏约四分之一，不再有「80% 宽的横条」
        assertTrue(r.width < 2670f * 0.35f, "宽度应远小于屏宽：$r")
    }

    @Test
    fun resolvePresetAndDefaultsFollowTheOrientation() {
        // 三个预设在**两种朝向**下都要给出形状正确的矩形（横屏下不许出现横条）。
        for (preset in listOf(
            WindowBounds.PRESET_FLYME,
            WindowBounds.PRESET_CUSTOM,
        )) {
            val portrait = WindowBounds.resolvePreset(preset, w, h, 80f, 63.5f, 50f, 41.7f)
            assertNotNull(portrait, "$preset 竖屏")
            // 横屏：同一块屏转过来（宽高互换）
            val landW = h
            val landH = w
            val land = WindowBounds.resolvePreset(preset, landW, landH, 80f, 63.5f, 50f, 41.7f)
            assertNotNull(land, "$preset 横屏")
            assertTrue(
                land.width < land.height,
                "$preset 横屏必须是竖屏形状：$land",
            )
            // 位置由实测常量定：靠左 + 垂直居中（与竖屏那两个滑杆无关）
            assertTrue(land.left < land.width, "$preset 横屏应贴左：$land")
            assertEquals(
                (landH - land.height) / 2f,
                land.top,
                1f,
                "$preset 横屏应垂直居中：$land",
            )
        }
        // system 档在两个朝向下都不覆写
        assertNull(WindowBounds.resolvePreset(WindowBounds.PRESET_SYSTEM, h, w, 80f, 63.5f, 50f, 41.7f))
        // 认不出的取值 → 默认档（= flyme），横屏下同样是竖屏形状
        val bogus = WindowBounds.resolvePreset("???", h, w, 80f, 63.5f, 50f, 41.7f)
        assertNotNull(bogus)
        assertTrue(bogus.width < bogus.height, "兜底档在横屏也必须是竖屏形状：$bogus")
    }

    @Test
    fun landscapeDegradesToEmptyInsteadOfNaN() {
        for ((sw, sh) in listOf(0f to 0f, -100f to -100f, 2670f to 0f, 0f to 1200f)) {
            val r = WindowBounds.landscape(sw, sh, 80f, 63.5f)
            assertTrue(r.left.isFinite() && r.top.isFinite(), "屏幕 ${sw}x$sh → $r")
            assertFalse(WindowBounds.isUsable(r), "屏幕 ${sw}x$sh 必须判为不可用：$r")
        }
        // 参数被手改成 0 → 形状无从定义 → 空矩形（不是 NaN，也不是一块退化成线的窗口）
        assertEquals(WindowBounds.EMPTY, WindowBounds.landscape(2670f, 1200f, 0f, 63.5f))
        assertEquals(WindowBounds.EMPTY, WindowBounds.landscape(2670f, 1200f, 80f, 0f))
        assertEquals(
            WindowBounds.EMPTY,
            WindowBounds.landscape(2670f, 1200f, Float.NaN, 63.5f),
        )
        // 尺寸调到离谱的大：必须被夹进屏幕，不能算出跑出屏幕的矩形
        val huge = WindowBounds.landscape(2670f, 1200f, 150f, 150f)
        assertTrue(
            huge.left >= 0f && huge.top >= 0f && huge.right <= 2670f && huge.bottom <= 1200f,
            "超大参数必须夹进屏幕：$huge",
        )
    }

    /** 只为了让预览跑起来的一份最小参数集；与这两个参数无关的量取本机默认值。 */
    private fun previewParams(
        widthPercent: Float,
        heightPercent: Float,
        xPercent: Float,
        yPercent: Float,
        preset: String = WindowBounds.PRESET_CUSTOM,
    ) = FanPreviewParams(
        screenW = w,
        screenH = h,
        density = 3.25f,
        hotWidthPercent = 8f,
        hotHeightPercent = 11f,
        corners = "both_bottom",
        layoutMode = "smart",
        customInner = 7,
        customMiddle = 8,
        radiusPercent = 30f,
        iconDp = 34f,
        edgeMarginDp = 10f,
        staggerSlots = 0.5f,
        selectionScalePercent = 122f,
        iconShape = IconShape.CIRCLE,
        windowPreset = preset,
        windowWidthPercent = widthPercent,
        windowHeightPercent = heightPercent,
        windowXPercent = xPercent,
        windowYPercent = yPercent,
        count = 17,
    )
}
