package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds

/**
 * 配置结构迁移 —— `Keys.CURRENT_CONFIG_VERSION` 的执行者。
 *
 * ## 为什么迁移要独立成一个纯函数
 *
 * 迁移是**一次性、不可重复验证**的操作：跑错一次，那批用户的配置就被改成错的，
 * 而他们不会回来告诉你「我的窗口位置变了」。所以它必须能在单测里对任意输入组合
 * 直接跑（[plan] 不碰 `Context`、不碰 `SharedPreferences`、只吃一个
 * 「键 → 值或 null」的读取函数），而不是只能靠在真机上手动制造老配置来验证。
 *
 * ## 三条纪律
 *
 * 1. **只动用户真的设过的东西**。没设过的项由「新默认值」接管 —— 那是功能变更的正常部分，
 *    不该被迁移按旧默认值钉死（否则「按实测改默认值」这件事对老用户永远不生效）。
 *    判断「设过没有」用 [Prefs.rawFloatOrNull]（键不存在返回 `null`）。
 * 2. **迁移要保「用户当年看到的画面」**，不是保数字。语义变了就按几何换算，见
 *    [WindowBounds.centerPercentFromEdgePercent]（v1→v2）与
 *    [WindowBounds.ratioPercentFromCenterPercent]（v2→v3）。
 * 3. **读不出来就不动**。存储坏了、键是别的类型、值不是有限数 —— 一律跳过那一项。
 *
 * ## 为什么是一条链
 *
 * 同一对参数在一天之内改过两次语义（v1 边缘 → v2 中心绝对位置 → v3 中心比例），
 * 所以 [plan] 是**逐版往上走**的：`if (from < 2) …; if (from < 3) …`。
 * 不写成「按 from 分三种情况各算一遍」是因为那种分支必然会漏掉一格，
 * 而漏掉的表现是「那批用户的位置整体偏移」—— 没有任何报错。
 *
 * ## 幂等
 *
 * 版本号已经到 [Keys.CURRENT_CONFIG_VERSION] 时 [plan] 返回 `null`（什么都不做）。
 * 这让调用方可以在**每次启动**都无脑调一次 —— 包括框架刚连上、远端存储刚可用的那一刻。
 * 反过来，**降级**（存下来的版本比当前高）也返回 `null`：不做任何假设，
 * 更不把版本号改小（那是把「用户装过新版」这个事实抹掉，之后他再装回新版就会重跑迁移）。
 */
object ConfigMigration {

    /** 版本字段缺失时的值。`0` = 这份配置写在「有版本号」这件事之前，按 v1 处理。 */
    const val ABSENT_VERSION = 0

    /**
     * 一次迁移要做的事。
     *
     * [writes] 为空是**正常结果**：配置已经是对的、或者用户什么都没设过，
     * 但版本号仍要推进（否则每次启动都要重算一遍，而且以后每加一版都要把这一版再跑一次）。
     */
    data class Plan(
        /** 迁移**之前**的版本（进日志用：`配置迁移 v2→v3` 一眼看出跑了哪几段）。 */
        val fromVersion: Int,
        /** 迁移完成后写回的版本号。 */
        val version: Int,
        /** 要写的键值对。**顺序无意义**（都是独立键，互不依赖）。 */
        val writes: List<Write>,
    ) {
        /**
         * 一笔要写的键值。
         *
         * ## 为什么有两种类型
         *
         * v1–v3 的三段迁移只搬浮点（四个百分比），v4 要搬的是一句话
         * （`window_custom: Boolean` → `window_preset: String`）。
         *
         * 加一个 [StringWrite] 子类而不是把 [value] 改成 `Any`：
         * 后者的代价是**两个适配器都要改**（`Prefs` 与前端 App 的写入通道各自
         * 实现一遍这批写入），而 `Any` 会把「写错类型」从编译期错误降级成运行期错误 ——
         * 迁移是一次性的、跑错了没人回来报的代码，这里不该松。
         */
        sealed interface Write {
            val key: String

            /** 浮点写入（v1→v2、v2→v3 那四个百分比）。 */
            data class FloatWrite(override val key: String, val value: Float) : Write

            /**
             * 字符串写入（v4 的 `window_preset`）。
             *
             * ⚠️ **写入时必须把旧键一起删掉**（见 `[WindowPresetMigration.legacyKey]`）：
             * 「旧键还在、新键也写了」是一个**可以自洽但现在不该出现**的状态 ——
             * 将来若有人把旧键的读取捡回来，就会有一个优先级不明的双事实。
             * 调用方拿到 `null`（= 什么都不用做）时**也**要清旧键：那是
             * 「用户装过 3.x，但他从没动过那个开关」的常见情况，同样要收尾。
             */
            data class StringWrite(override val key: String, val value: String) : Write

            /** 删键（v5：下线参数的已存值删掉，让调好的默认值接管）。 */
            data class Remove(override val key: String) : Write
        }
    }

    /**
     * 四个键的参数名（不带特性前缀）。
     *
     * 与 `Catalog` 里声明的那几个必须一致 —— 这里再抄一遍字面量是因为迁移读的是
     * **历史键名**，而 `Catalog` 里的名字将来可能被重构掉；两者的对应关系由
     * `ConfigMigrationTest` 断言（红的含义是「有人改了参数名却没改迁移」，
     * 那会让老用户的窗口位置原地不动地错下去）。
     */
    const val WIDTH_NAME = "window_width_percent"
    const val HEIGHT_NAME = "window_height_percent"
    const val X_NAME = "window_x_percent"
    const val Y_NAME = "window_y_percent"

    // ─────────────────── v3 → v4：总开关从布尔改成三选一 ───────────────────

    /**
     * 旧的总开关参数名（`window_custom`，布尔）。**只读、只用于迁移**。
     *
     * 保留它不是为了兼容 —— 是为了**读出用户当年的选择**。这个键在新版代码里
     * 已经没有任何读取点了（`grep window_custom` 应该只剩这里与文档），
     * 所以它天然是「历史键名」而不是「第二份事实」。
     */
    const val LEGACY_CUSTOM_NAME = "window_custom"

    /** 新的三选一参数名。`Catalog` 里声明的那一条必须与它同名（`ConfigMigrationTest` 断言）。 */
    const val PRESET_NAME = "window_preset"

    /**
     * 旧开关的两个取值 → 新预设的取值。
     *
     * | `window_custom` | `window_preset` | 为什么 |
     * | --- | --- | --- |
     * | `true` | `flyme` | 当年「开」= 用我们那四个实测值（Flyme 那块窗口）。新语义下那一档就叫 `flyme` |
     * | `false` | `system` | 当年「关」= 交给 MIUI 摆放。新语义下那一档叫 `system` |
     *
     * ⚠️ 这一版**不能只搬 `true` 那些**（即「只搬用户真的设过的」那条纪律在这里
     * 要按**两个方向**都执行）：只搬 `true` 的话，当年主动把开关关掉的那批用户
     * 会掉回新默认值 `flyme` —— 也就是「他明确关掉的东西自己开了」。
     * 搬 `false` 才是在保「用户当年的画面」，与那条纪律的本意一致
     * （它防的是「把没设过的项按旧默认值钉死」，而不是「别搬 `false`」）。
     */
    const val LEGACY_CUSTOM_ON_VALUE = WindowBounds.PRESET_FLYME
    const val LEGACY_CUSTOM_OFF_VALUE = WindowBounds.PRESET_SYSTEM

    /**
     * 算出该做什么。`null` = 什么都不用做（版本已是最新 / 用户装的是更新版本）。
     *
     * @param version 存储里当前的配置版本（[Prefs.configVersion]）
     * @param readFloat 读一个键的**原始** Float；**键不存在返回 `null`**
     *   （`Prefs::rawFloatOrNull` 就是干这个的）
     * @param readBoolean 读一个键的**原始** Boolean；**键不存在返回 `null`**
     *   （`Prefs::rawBooleanOrNull`）。v3→v4 要区分「开关设成了 false」与「没设过」，
     *   所以不能再靠「给一个默认值」的读法 —— 那两种情况的默认值恰好相同。
     */
    fun plan(
        version: Int,
        readFloat: (String) -> Float?,
        readBoolean: (String) -> Boolean?,
        readString: (String) -> String? = { null },
    ): Plan? {
        // 降级 / 已是最新：不动。降级时**绝不**把版本号改小（见类注释）。
        if (version >= Keys.CURRENT_CONFIG_VERSION) return null
        // 版本字段缺失 = v1（见 ABSENT_VERSION）。这里不需要区分「全新安装」——
        // 全新安装时四个键一个都不存在，下面的分支自然什么都不写。
        val from = if (version <= ABSENT_VERSION) 1 else version
        val writes = ArrayList<Plan.Write>(3)
        // 两个轴各判各的（用户可能只设过其中一个）
        axis(
            readFloat, from, X_NAME, WIDTH_NAME,
            legacySizeDefault = WindowBounds.LEGACY_DEFAULT_WIDTH_PERCENT,
            v2SizeDefault = WindowBounds.DEFAULT_WIDTH_PERCENT,
            out = writes,
        )
        axis(
            readFloat, from, Y_NAME, HEIGHT_NAME,
            legacySizeDefault = WindowBounds.LEGACY_DEFAULT_HEIGHT_PERCENT,
            v2SizeDefault = WindowBounds.DEFAULT_HEIGHT_PERCENT,
            out = writes,
        )
        if (from < 4) preset(readBoolean, out = writes)
        if (from < 5) simplify(readFloat, readBoolean, readString, out = writes)
        return Plan(fromVersion = from, version = Keys.CURRENT_CONFIG_VERSION, writes = writes)
    }

    /** 配置读取器重载。 */
    fun plan(prefs: Prefs): Plan? =
        plan(prefs.configVersion(), { prefs.rawFloatOrNull(it) }, { prefs.rawBooleanOrNull(it) }, { prefs.rawString(it, null) })

    // ─────────────────── v4 → v5：参数精简───────────────────

    /** 下线的扇形参数：已存值删掉，Catalog 里调好的默认值接管（声明留在隐藏分组给 hook 读）。 */
    internal val V5_REMOVED_FLOATS = listOf(
        "icon_shape_corner", "icon_shape_exponent", "icon_edge_width",
        FanMotion.SPEED, FanMotion.ROTATION, FanMotion.REVEAL,
    )
    internal val V5_REMOVED_STRINGS = listOf("apps_mode", FanMotion.STAGGER)
    internal val V5_REMOVED_BOOLEANS = listOf("root_activities")

    /** 收窄了范围的滑杆：存着的值夹进新范围（范围从 Catalog 读，不另抄一份）。 */
    internal val V5_NARROWED = listOf(
        "hot_width_percent", "hot_height_percent", "trigger_percent",
        "selection_radius_percent", "icon_alpha", "fan_edge_margin",
    )

    /**
     * v4 → v5。只处理**用户真存过**的键（全新安装什么都不写，与前几版同一条规矩）：
     *
     *  1. 扇形：下线参数删键；「展开动画」不写（没写过 = Flyme 预设 = 四个键的默认值）；
     *     收窄的滑杆把越界的存值夹回范围。
     *  2. 侧滑：`haptic` 曾被关 → `haptic_mode = off`；`blur` 开着 → `material` 至少是「模糊」，
     *     关着 / 没开过 → `material = none`（与新默认一致，写下来是为了不跟着默认值漂移）；
     *     `bubble_anim_ms` 删键（固定 150ms）。`haptic` / `blur` 本身保留：它们是联动键
     *     （DerivedPrefs），值与新选项一致。
     */
    private fun simplify(
        readFloat: (String) -> Float?,
        readBoolean: (String) -> Boolean?,
        readString: (String) -> String?,
        out: MutableList<Plan.Write>,
    ) {
        val fan = Catalog.CORNER_SMALL_WINDOW
        for (n in V5_REMOVED_FLOATS) Keys.pref(fan, n).let { if (readFloat(it) != null) out.add(Plan.Write.Remove(it)) }
        for (n in V5_REMOVED_STRINGS) Keys.pref(fan, n).let { if (readString(it) != null) out.add(Plan.Write.Remove(it)) }
        for (n in V5_REMOVED_BOOLEANS) Keys.pref(fan, n).let { if (readBoolean(it) != null) out.add(Plan.Write.Remove(it)) }
        val sliders = Catalog.find(fan)?.prefs.orEmpty().filterIsInstance<PrefSpec.Slider>()
        for (n in V5_NARROWED) {
            val spec = sliders.firstOrNull { it.key == Keys.pref(fan, n) } ?: continue
            val v = readFloat(spec.key)?.takeIf { it.isFinite() } ?: continue
            val c = v.coerceIn(spec.min, spec.max)
            if (c != v) out.add(Plan.Write.FloatWrite(spec.key, c))
        }

        val side = Catalog.SIDE_INDEX
        if (readBoolean(Keys.pref(side, "haptic")) == false) {
            out.add(Plan.Write.StringWrite(Keys.pref(side, "haptic_mode"), "off"))
        }
        val materialKey = Keys.pref(side, "material")
        val blur = readBoolean(Keys.pref(side, "blur"))
        val material = readString(materialKey)
        when {
            blur == true && material == null -> out.add(Plan.Write.StringWrite(materialKey, "blur"))
            blur != true && material != null && material != "none" -> out.add(Plan.Write.StringWrite(materialKey, "none"))
        }
        Keys.pref(side, "bubble_anim_ms").let { if (readFloat(it) != null) out.add(Plan.Write.Remove(it)) }
    }

    /**
     * v3 → v4：`window_custom: Boolean` → `window_preset: String`。
     *
     * ## 为什么只搬「用户设过」的那些
     *
     * 没设过旧键的用户（全新安装、或者装了 3.x 但从来没点过那个开关）**不写新键**：
     * 新键的默认值接管（`Catalog` 里的 `WindowBounds.DEFAULT_PRESET`）。
     * 这与类注释第 1 条一致 —— 迁移的职责是保「当年的画面」，
     * 而「没设过」的用户当年看到的就是当年的默认值，由新默认值接管即可。
     *
     * ## 为什么旧的 `true` 也要写
     *
     * 新默认本来就是 `flyme`，看起来「写 `flyme` 是多余的」。仍然写，因为
     * **默认值将来还会变**（这个项目改过两次默认值了）：写下去等于把这个用户
     * 「明确选了 Flyme」这件事记下来，而不是让他跟着默认值漂移。
     */
    private fun preset(readBoolean: (String) -> Boolean?, out: MutableList<Plan.Write>) {
        val feature = Catalog.CORNER_SMALL_WINDOW
        val legacyKey = Keys.pref(feature, LEGACY_CUSTOM_NAME)
        val old = readBoolean(legacyKey) ?: return
        val value = if (old) LEGACY_CUSTOM_ON_VALUE else LEGACY_CUSTOM_OFF_VALUE
        out.add(Plan.Write.StringWrite(Keys.pref(feature, PRESET_NAME), value))
    }

    /**
     * 迁一个轴（x 或 y）。
     *
     * 只有**用户真的设过位置**时才写（见类注释第 1 条）。
     *
     * 尺寸那一项**按每一版各自的口径取**：v1→v2 用 v1 的默认尺寸，v2→v3 用 v2 的默认尺寸。
     * 只有「用户没设过尺寸」时才分得出来，而那正是最容易写错的一格 ——
     * 用错口径等于拿一把用户没见过的尺子量他的配置。
     */
    private fun axis(
        readFloat: (String) -> Float?,
        from: Int,
        positionName: String,
        sizeName: String,
        legacySizeDefault: Float,
        v2SizeDefault: Float,
        out: MutableList<Plan.Write>,
    ) {
        val feature = Catalog.CORNER_SMALL_WINDOW
        val positionKey = Keys.pref(feature, positionName)
        val raw = readFloat(positionKey)?.takeIf { it.isFinite() } ?: return

        val sizeKey = Keys.pref(feature, sizeName)
        val storedSize = readFloat(sizeKey)?.takeIf { it.isFinite() }

        var v = raw
        if (from < 2) {
            v = WindowBounds.centerPercentFromEdgePercent(v, storedSize ?: legacySizeDefault)
        }
        if (from < 3) {
            v = WindowBounds.ratioPercentFromCenterPercent(v, storedSize ?: v2SizeDefault)
        }
        out.add(Plan.Write.FloatWrite(positionKey, v))
    }
}
