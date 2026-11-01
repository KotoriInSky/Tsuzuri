package io.github.kotoriinsky.tsuzuri.core.geometry

import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「这一项进不进预览」这件事的看门人。
 *
 * 两条断言，分别挡两种错：
 *
 *  1. **清单里写了、但谁都不读** —— [FAN_PREVIEW_READS] 的每一项都必须能在
 *     [FanPreviewParams] 上找到对应的字段（反射查真实字段名）。写错一个后缀、
 *     或者删掉了某个参数的读取却留着清单，都会在这里失败。
 *  2. **读的参数忘了声明** —— `corner_small_window` 里**不进预览**的参数必须逐个列在
 *     [KNOWN_NOT_FEEDING] 里（带原因）。新加一个参数而没想清楚它进不进预览，
 *     测试就会失败，而不是等到用户在真机上拖半天发现没反应。
 */
class FanPreviewCoverageTest {

    /** 扇形特性里**确实不进**预览的参数。判据：它们不影响画出来的几何/图标，只影响行为。 */
    private val KNOWN_NOT_FEEDING: Map<String, String> = mapOf(
        // 触发的判定阈值与方向开关：决定「划多长算触发」「这个方向响不响应」，
        // 不影响热区与扇形的画法（热区框本身由 hot_* 两个百分比控制，那两个是进预览的）。
        "trigger_percent" to "触发阈值，只影响判定不影响画法",
        "portrait_enabled" to "竖屏是否响应，行为开关",
        "landscape_enabled" to "横屏是否响应，行为开关",
        // 清单来源：手动指定还是自动补全 —— 影响「画哪些图标」，不影响「画成什么样」。
        // 注意 items（清单本身）是进预览的：它决定画几项、画哪些图标。
        "apps_mode" to "清单来源，只影响取哪些应用（清单长度由 items 进预览）",
        // 振动：看不见的东西。处置是给「真实试触区」而不是画一个假的振动动画。
        "haptic" to "振动反馈，只可能靠手感验证",
        "trigger_haptic" to "触发那一下的振动，同上",
        // 高级：允许在哪些界面响应 → 行为范围，不影响画法。
        "root_activities" to "允许响应的界面白名单，行为范围",
        // 黑名单：决定「在哪些应用里根本不呼出」。预览画的是扇形**长什么样**，
        // 而这个参数决定的是「这次手势有没有发生」—— 发生之前没有东西可画。
        "blocklist_portrait" to "竖屏不响应的应用，决定手势发不发生（画不出来）",
        "blocklist_landscape" to "横屏不响应的应用，同上",
    )

    @Test
    fun everyDeclaredReadMapsToARealFanPreviewParamsField() {
        val fields = FanPreviewParams::class.java.declaredFields.map { it.name }.toSet()
        for (suffix in FAN_PREVIEW_READS) {
            // 动画那两项本来就不进参数对象（它们定的是时长/倾角，按帧算），
            // 允许它们没有字段 —— 但**只允许**这两项，别的都必须在参数对象上找得到。
            if (suffix in FAN_PREVIEW_ANIMATION_READS) continue
            val expected = previewParamsFieldOf(suffix)
            assertTrue(
                expected in fields,
                "清单里的 `$suffix` 折算成字段 `$expected`，但 FanPreviewParams 上没有这个字段 —— " +
                    "要么后缀写错了，要么这一项其实没进预览（清单在 FanPreviewCoverage.kt）",
            )
        }
    }

    /**
     * `corner_small_window` 的每个参数：要么进预览，要么在 [KNOWN_NOT_FEEDING] 里写明原因。
     *
     * 这条断言的价值在于**新参数**：加了参数却既没接进预览、也没写「为什么不用」，
     * 用户就会在真机上拖到一个什么都不动的滑杆。
     */
    @Test
    fun everyCornerParameterIsEitherPreviewedOrExplicitlyExcluded() {
        val spec = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
        val unexplained = spec.prefs
            .filter { !it.feedsFanPreview() }
            .filter { it.key.substringAfterLast('.') !in KNOWN_NOT_FEEDING }
            .map { it.key }
        assertEquals(
            emptyList(),
            unexplained,
            "这些参数既不驱动预览、也没有在 KNOWN_NOT_FEEDING 里说明原因：" +
                "要么接进预览，要么写明「为什么它不影响画面」（见 FanPreviewCoverageTest）",
        )
    }

    /** 反向检查：那份「不进预览」的清单不能留已经不存在的键（改参数后忘了清理）。 */
    @Test
    fun notFeedingListHasNoStaleEntries() {
        val spec = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
        val names = spec.prefs.map { it.key.substringAfterLast('.') }.toSet()
        for (name in KNOWN_NOT_FEEDING.keys) {
            assertTrue(name in names, "KNOWN_NOT_FEEDING 里的 `$name` 在 Catalog 里已经没有对应参数了")
        }
    }

    /** 抽查几条：进/不进必须与界面上看到的一致。 */
    @Test
    fun spotChecksMatchWhatThePreviewActuallyDraws() {
        fun feeds(key: String) = Catalog.require(Catalog.CORNER_SMALL_WINDOW).prefs
            .first { it.key == key }
        assertTrue(feeds("corner_small_window.selection_ring").feedsFanPreview(), "描环开关决定画不画那圈")
        assertTrue(feeds("corner_small_window.selection_scale").feedsFanPreview(), "选中放大是几何")
        assertTrue(feeds("corner_small_window.items").feedsFanPreview(), "清单决定画几项")
        assertTrue(feeds("corner_small_window.animation_speed").feedsFanPreview(), "动效页靠它定时长")
        assertTrue(!feeds("corner_small_window.haptic").feedsFanPreview(), "振动画不出来")
    }

    /** 每个参数都能被判据覆盖到（不依赖 PrefSpec 的具体类型）。 */
    @Test
    fun coverageJudgementWorksForEveryPrefKind() {
        val spec = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
        val kinds = spec.prefs.map { it::class.simpleName }.toSet()
        assertTrue(kinds.size >= 3, "扇形特性的参数类型太少（$kinds），这条断言失去意义")
        for (p: PrefSpec in spec.prefs) {
            // 不抛异常即为通过：判据只读 key 的后缀。
            p.feedsFanPreview()
        }
    }
}
