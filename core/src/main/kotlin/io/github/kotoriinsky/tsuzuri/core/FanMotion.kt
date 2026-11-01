package io.github.kotoriinsky.tsuzuri.core

/**
 * 扇形小窗的「展开动画」预设。
 *
 * 替代原来的四个滑杆：动画速度 / 展开时旋转 / 展开行程 / 多层错开。四个量互相耦合
 * （旋转大而行程短会甩出残影、速度快而行程长会显得飘），单独拖哪一个都容易变丑，
 * 而「默认值就在动画速度的下限」说明那个范围本身就不对。
 *
 * 用户只选一个预设；界面写入时由 [DerivedPrefs] 一并写那四个内部键（它们留在 Catalog 的
 * 隐藏分组 `corner_internal`），hook 与预览照旧读四个键 —— 动画实现一行不改。
 *
 * ⚠️ [FLYME] 必须与四个内部键在 Catalog 里的默认值**完全相同**：没选过预设的用户
 * 读到的就是那四个默认值，两边不一致就是「显示 Flyme、实际不是」（`FanMotionTest` 钉住）。
 * [BRISK] / [STILL] 是起点值，需真机看过再定。
 */
object FanMotion {
    const val NAME = "fan_motion"

    const val FLYME = "flyme"
    const val BRISK = "brisk"
    const val STILL = "still"
    const val DEFAULT = FLYME

    /** 四个内部键的参数名（不带特性前缀）。 */
    const val SPEED = "animation_speed"
    const val ROTATION = "fan_rotation"
    const val REVEAL = "fan_reveal_amount"
    const val STAGGER = "fan_row_stagger"

    class Values(val speedPercent: Float, val rotationDeg: Float, val revealPercent: Float, val stagger: String)

    val presets: Map<String, Values> = mapOf(
        // 出厂默认（= 原四个滑杆的默认值，取证见 Catalog 里那四条的注释）
        FLYME to Values(speedPercent = 75f, rotationDeg = 42f, revealPercent = 65f, stagger = "half"),
        // 更快、转得少、行程短：手感利落
        BRISK to Values(speedPercent = 130f, rotationDeg = 20f, revealPercent = 45f, stagger = "half"),
        // 不旋转：只做径向展开
        STILL to Values(speedPercent = 75f, rotationDeg = 0f, revealPercent = 65f, stagger = "half"),
    )

    /** 预设 [choice] 对应的四个键值（带特性前缀）；未知取值 → 空。 */
    fun writes(choice: String): Map<String, Any> {
        val v = presets[choice] ?: return emptyMap()
        val f = Catalog.CORNER_SMALL_WINDOW
        return mapOf(
            Keys.pref(f, SPEED) to v.speedPercent,
            Keys.pref(f, ROTATION) to v.rotationDeg,
            Keys.pref(f, REVEAL) to v.revealPercent,
            Keys.pref(f, STAGGER) to v.stagger,
        )
    }

    /** 四个内部键（带特性前缀）。恢复默认时与 [NAME] 一起删。 */
    val internalKeys: Set<String>
        get() = listOf(SPEED, ROTATION, REVEAL, STAGGER).map { Keys.pref(Catalog.CORNER_SMALL_WINDOW, it) }.toSet()
}

/**
 * 「用户可见的一个选项 → 一并写入的内部键」。
 *
 * 为什么不改 hook 去读新选项：hook 与预览按 Catalog 声明读那些内部键的地方有十几处，
 * 联动写在**唯一的写入通道**（ConfigStore）里只有一处，hook 一行不动，风险最小。
 *
 * | 可见选项 | 一并写入 |
 * | --- | --- |
 * | 扇形 `fan_motion` | 四个动画键（[FanMotion]） |
 * | 侧滑 `material`（无 / 模糊 / 玻璃） | `blur` = 选的不是「无」 |
 * | 侧滑 `haptic_mode`（关 / 轻 / 标准 / 强） | `haptic` = 选的不是「关」 |
 *
 * 恢复默认（删掉可见选项的键）时，[removals] 给出要一起删的内部键 —— 否则会出现
 * 「选项显示默认、内部键还是旧值」。
 */
object DerivedPrefs {
    private val material = Keys.pref(Catalog.SIDE_INDEX, "material")
    private val hapticMode = Keys.pref(Catalog.SIDE_INDEX, "haptic_mode")
    private val motion = Keys.pref(Catalog.CORNER_SMALL_WINDOW, FanMotion.NAME)

    fun writes(key: String, value: String): Map<String, Any> = when (key) {
        motion -> FanMotion.writes(value)
        material -> mapOf(Keys.pref(Catalog.SIDE_INDEX, "blur") to (value != "none"))
        hapticMode -> mapOf(Keys.pref(Catalog.SIDE_INDEX, "haptic") to (value != "off"))
        else -> emptyMap()
    }

    fun removals(key: String): Set<String> = when (key) {
        motion -> FanMotion.internalKeys
        material -> setOf(Keys.pref(Catalog.SIDE_INDEX, "blur"))
        hapticMode -> setOf(Keys.pref(Catalog.SIDE_INDEX, "haptic"))
        else -> emptySet()
    }
}
