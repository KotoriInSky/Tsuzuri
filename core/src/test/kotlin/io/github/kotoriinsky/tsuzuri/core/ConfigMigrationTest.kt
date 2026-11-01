package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 配置结构迁移（[ConfigMigration]）。
 *
 * 这一层**必须**能被单测直接钉住，理由写在它自己的类注释里：迁移是一次性的，
 * 跑错了没人会回来报 bug —— 那批用户只会觉得「窗口位置自己变了」然后就不用了。
 *
 * `window_x/y_percent` 一天之内改过两次语义，所以这里既要测**单步**（v1→v2、v2→v3），
 * 也要测**整条链**（v1 一路到 v3）—— 只测单步的话，「从 v1 直接跳到最新版」那条路
 * 是不是真的走了两步，没有任何东西保证。
 *
 * 钉住的对外承诺：
 *
 *  1. **幂等**：版本已到就什么都不做（调用方据此可以在每次启动无脑调一遍）；
 *  2. **不降级**：存下来的版本比当前高时不动（用户装过更新的版本）；
 *  3. **只动用户设过的东西**：没设过的项交给新默认值，不被迁移按旧默认值钉死；
 *  4. **保画面**：迁完之后，用户当年那块窗口还在原来的地方（同一个中心，或同样贴边）。
 */
class ConfigMigrationTest {

    private val feature = Catalog.CORNER_SMALL_WINDOW

    private fun key(name: String) = Keys.pref(feature, name)

    /** 用一张内存表当配置。键不存在 = 没设过。 */
    private fun plan(vararg values: Pair<String, Float>, version: Int = 1) =
        ConfigMigration.plan(
            version = version,
            readFloat = { k -> values.toMap()[k] },
            // 浮点那几段测试不涉及布尔键 —— 恒返回 null（= 「没设过」）
            readBoolean = { null },
        )

    /** 同上，但带旧的总开关（v3 → v4 那一段要用）。 */
    private fun planWithToggle(
        custom: Boolean?,
        version: Int = 3,
        vararg values: Pair<String, Float>,
    ) = ConfigMigration.plan(
        version = version,
        readFloat = { k -> values.toMap()[k] },
        readBoolean = { k ->
            if (k == key(ConfigMigration.LEGACY_CUSTOM_NAME)) custom else null
        },
    )

    private fun ConfigMigration.Plan.floatValueOf(name: String): Float? =
        writes.filterIsInstance<ConfigMigration.Plan.Write.FloatWrite>()
            .firstOrNull { it.key == key(name) }?.value

    /** 新键（`window_preset`）写出来的字符串值。 */
    private fun ConfigMigration.Plan.presetValue(): String? =
        writes.filterIsInstance<ConfigMigration.Plan.Write.StringWrite>()
            .firstOrNull { it.key == key(ConfigMigration.PRESET_NAME) }?.value

    private fun ConfigMigration.Plan.valueOf(name: String): Float? = floatValueOf(name)

    /** 同上，但**必须**存在 —— 断言迁移写了这一项。 */
    private fun ConfigMigration.Plan.required(name: String): Float =
        assertNotNull(valueOf(name), "迁移应该写出 $name")

    // ───────────────────────── 承诺 1 / 2：幂等与不降级 ─────────────────────────

    @Test
    fun alreadyCurrentVersionDoesNothing() {
        assertNull(plan(version = Keys.CURRENT_CONFIG_VERSION))
        assertNull(plan(version = Keys.CURRENT_CONFIG_VERSION + 5))
    }

    @Test
    fun aVersionFromTheFutureIsNeverDowngraded() {
        // 用户装过更新的版本又退回来：那份配置的「含义」我们不知道，唯一安全的动作是别碰它。
        // 尤其**不能**把版本号改小 —— 那会让用户下次装回新版时重跑一遍已跑过的迁移。
        assertNull(
            ConfigMigration.plan(version = 99, readFloat = { 15f }, readBoolean = { null }),
        )
    }

    // ───────────────────────── 承诺 3：只动用户设过的东西 ─────────────────────────

    @Test
    fun freshInstallWritesNothingButStillAdvancesTheVersion() {
        // 全新安装：四个键一个都不存在。什么都不用写，但版本号要推进，
        // 否则每次启动都要重算一遍（而且以后每加一版迁移都要把这一版再跑一次）
        val p = assertNotNull(plan(version = 0))
        assertEquals(Keys.CURRENT_CONFIG_VERSION, p.version)
        assertTrue(p.writes.isEmpty(), "全新安装不该写任何东西：${p.writes}")
        // 版本字段缺失（0）按 v1 处理，不是按「未知」处理
        val missing = assertNotNull(
            ConfigMigration.plan(
                ConfigMigration.ABSENT_VERSION,
                readFloat = { null },
                readBoolean = { null },
            ),
        )
        assertEquals(Keys.CURRENT_CONFIG_VERSION, missing.version)
        assertTrue(missing.writes.isEmpty())
    }

    @Test
    fun anUntouchedPositionIsLeftAloneSoTheNewDefaultApplies() {
        // 用户只调过尺寸、没动过位置：位置**必须保持"没设过"**。
        // 迁移在这里写一笔就等于把新默认值钉成常量 —— 将来再按实测调默认值时，
        // 这批用户就永远跟不上了。
        val p = assertNotNull(
            plan(
                key(ConfigMigration.WIDTH_NAME) to 60f,
                key(ConfigMigration.HEIGHT_NAME) to 50f,
            ),
        )
        assertTrue(p.writes.isEmpty(), "没设过的位置不该被写：${p.writes}")
    }

    // ───────────────────────── 单步：v1 → v2 ─────────────────────────

    @Test
    fun aPositionSetInV1BecomesTheCenterItActuallyWas() {
        // 老用户里最常见的一档：位置是 v1 默认的 15%（左边缘），宽度也是 v1 默认的 70%。
        // v1→v2 换算出来是中心 50%；再走 v2→v3 得比例 50% —— 也就是「居中」。
        // （这就是为什么 v1 老用户迁完之后看到的是一块居中的窗口）
        val p = assertNotNull(
            plan(
                key(ConfigMigration.X_NAME) to 15f,
                key(ConfigMigration.WIDTH_NAME) to 70f,
            ),
        )
        assertEquals(50f, p.valueOf(ConfigMigration.X_NAME))
        // 高度那一项没设过 → 一个字都不写
        assertNull(p.valueOf(ConfigMigration.Y_NAME))
    }

    @Test
    fun aSizeThatWasNeverSetIsReadWithTheLegacyDefaultNotTheNewOne() {
        // ⚠️ 这条是整套迁移里最容易写错的地方：用户只设了位置。
        // 那个位置当年是按**当时的**默认尺寸（70%）解释的，不是按今天的新默认（80%）。
        // 拿新默认去换算，等于用一把他没见过的尺子量他的配置。
        //
        // 手工算：v1 左边缘 10% + 宽 70% → 中心 45%；
        //         v2→v3 用**v2 的**宽度默认 80% → 比例 (45−40)/20 = 25%。
        // 若哪一步用错了尺寸口径，这里会得到 50%（用 80 算 v1）或别的数。
        val x = assertNotNull(
            plan(key(ConfigMigration.X_NAME) to 10f),
        ).valueOf(ConfigMigration.X_NAME)
        assertEquals(25f, assertNotNull(x), 0.05f)

        // 高度：v1 上边缘 15% + 高 70% → 中心 50%；
        //       v2→v3 用 v2 的高度默认 63.5% → 比例 (50−31.75)/36.5 = 50%
        val y = assertNotNull(
            plan(key(ConfigMigration.Y_NAME) to 15f),
        ).valueOf(ConfigMigration.Y_NAME)
        assertEquals(50f, assertNotNull(y), 0.05f)
    }

    // ───────────────────────── 单步：v2 → v3 ─────────────────────────

    @Test
    fun v2CenterBecomesTheRatioThatReproducesTheSameEdge() {
        // v2 存的 47%（中心在屏高 47% 处）+ 高度 63.5% → 上边距 47−31.75 = 15.25% 屏高；
        // v3 里要复现同一个上边距，比例 = 15.25 / (100−63.5) = 41.78%
        val y = assertNotNull(
            plan(
                key(ConfigMigration.Y_NAME) to 47f,
                key(ConfigMigration.HEIGHT_NAME) to 63.5f,
                version = 2,
            ),
        ).valueOf(ConfigMigration.Y_NAME)
        assertEquals(41.78f, assertNotNull(y), 0.05f)
        // 而 41.7 正是新默认值 —— 也就是说「没动过 y 的老用户」与「新用户」看到的是同一块地方
        assertEquals(WindowBounds.DEFAULT_Y_PERCENT, assertNotNull(y), 0.15f)
    }

    @Test
    fun v2ValuesThatWereClampedStayAgainstTheSameEdge() {
        // v2 的 20%（宽 80%）当年被夹成贴左；换算必须还原那次夹取（比例 0），
        // 否则用户会看到一个他从没设过的位置
        val left = assertNotNull(
            plan(key(ConfigMigration.X_NAME) to 20f, key(ConfigMigration.WIDTH_NAME) to 80f, version = 2),
        ).valueOf(ConfigMigration.X_NAME)
        assertEquals(0f, left!!, 0.05f)

        // 反过来：100% 当年是贴右
        val right = assertNotNull(
            plan(key(ConfigMigration.X_NAME) to 100f, key(ConfigMigration.WIDTH_NAME) to 80f, version = 2),
        ).valueOf(ConfigMigration.X_NAME)
        assertEquals(100f, right!!, 0.05f)

        // 居中不变
        val center = assertNotNull(
            plan(key(ConfigMigration.X_NAME) to 50f, key(ConfigMigration.WIDTH_NAME) to 80f, version = 2),
        ).valueOf(ConfigMigration.X_NAME)
        assertEquals(50f, center!!, 0.05f)
    }

    @Test
    fun v2WithoutAStoredSizeUsesTheV2DefaultSize() {
        // 没设过宽度 → v2 下当年生效的是 80%
        val x = assertNotNull(
            plan(key(ConfigMigration.X_NAME) to 60f, version = 2),
        ).valueOf(ConfigMigration.X_NAME)
        // (60 − 40) / 20 = 100%
        assertEquals(100f, x!!, 0.05f)
    }

    // ───────────────────────── 单步：v3 → v4（总开关换形态）─────────────────────────

    @Test
    fun theOldPlacementToggleBecomesThePresetItMeant() {
        // v3 的 `window_custom` 两个取值各自对应一个预设。**两个方向都要搬**：
        // 只搬 `true` 的话，当年主动把开关关掉（想用 MIUI 摆放）的用户会掉回新默认
        // `flyme` —— 也就是「他明确关掉的东西自己开了」，且不会有任何报错。
        assertEquals(
            WindowBounds.PRESET_FLYME,
            assertNotNull(planWithToggle(custom = true)).presetValue(),
            "旧开关为 true = 当年用我们那四个实测值 → 应当搬成 flyme",
        )
        assertEquals(
            WindowBounds.PRESET_SYSTEM,
            assertNotNull(planWithToggle(custom = false)).presetValue(),
            "旧开关为 false = 当年交给系统摆放 → 应当搬成 system",
        )
    }

    @Test
    fun anUntouchedToggleLeavesTheNewKeyAbsentSoTheNewDefaultApplies() {
        // 「没设过」与「设成了 false」必须分开：前者交给新默认值（默认档），
        // 后者要搬成 system。`rawBoolean(key, false)` 把这两种读成同一个值 ——
        // 这正是为此引入 `Prefs.rawBooleanOrNull` 的原因。
        val p = assertNotNull(planWithToggle(custom = null))
        assertNull(p.presetValue(), "没设过旧开关的用户不该被写新键（交给新默认值接管）")
        // 版本号照常推进（否则每次启动都要重算一遍）
        assertEquals(Keys.CURRENT_CONFIG_VERSION, p.version)
    }

    @Test
    fun aV1ConfigGoesThroughAllFourSteps() {
        // 一条链上跑满四段：v1 的位置 → v2 的中心 → v3 的比例 → v4 的预设。
        // 少了 v4 那一段的表现是「位置搬对了、档位回到默认」——
        // 对当年关掉开关的用户就是「窗口不再归系统管了」。
        val p = assertNotNull(
            planWithToggle(
                custom = false,
                version = 1,
                key(ConfigMigration.X_NAME) to 15f,
                key(ConfigMigration.WIDTH_NAME) to 70f,
            ),
        )
        assertNotNull(p.floatValueOf(ConfigMigration.X_NAME), "位置那一段必须照常走")
        assertEquals(WindowBounds.PRESET_SYSTEM, p.presetValue(), "v4 那一段也必须走")
    }

    @Test
    fun theMigrationAndCatalogAgreeOnTheNewKeyNameAndValues() {
        // 迁移写的是 `window_preset` 这个字面量，`Catalog` 里声明的也必须同名同值。
        // 不一致的后果：写进存储的键**没人读** → 用户的档位永远是默认档，
        // 而迁移日志会说「改了 1 项」（看着完全正常）。
        val feature = Catalog.require(feature)
        val preset = assertNotNull(
            feature.prefs.filterIsInstance<PrefSpec.Choice>()
                .firstOrNull { it.key == key(ConfigMigration.PRESET_NAME) },
            "Catalog 里没有 ${ConfigMigration.PRESET_NAME} 这个 Choice",
        )
        val values = preset.options.map { it.value }.toSet()
        for (v in listOf(ConfigMigration.LEGACY_CUSTOM_ON_VALUE, ConfigMigration.LEGACY_CUSTOM_OFF_VALUE)) {
            assertTrue(v in values, "迁移会写出 `$v`，但 Catalog 的选项里没有它")
        }
        // 旧键**必须已经不在 Catalog 里**：它留着的话，`isOperable` 之类会把它当成
        // 一个「真实存在的依赖目标」，而新版没有任何代码会去写它（永远停在老值上）。
        val legacyKey = key(ConfigMigration.LEGACY_CUSTOM_NAME)
        assertNull(
            feature.prefs.firstOrNull { it.key == legacyKey },
            "旧键 $legacyKey 已经从界面上退役，不该再出现在 Catalog 里",
        )
    }

    // ───────────────────────── 整条链：v1 → v3 ─────────────────────────

    @Test
    fun aV1ConfigGoesThroughEveryStepInsteadOfBeingReinterpreted() {
        // 「从 v1 直接跳到 v3」必须在实现里真的走两步。判据用**屏幕上的位置**：
        // 迁完之后那块矩形必须还在用户当年的地方（同一个中心）。
        val screenW = 1220f
        val screenH = 2656f
        for ((edgeX, edgeY, width, height) in listOf(
            listOf(15f, 15f, 70f, 70f),   // v1 的默认组合
            listOf(10f, 20f, 60f, 50f),
            listOf(25f, 5f, 80f, 40f),
        )) {
            val p = assertNotNull(
                plan(
                    key(ConfigMigration.X_NAME) to edgeX,
                    key(ConfigMigration.Y_NAME) to edgeY,
                    key(ConfigMigration.WIDTH_NAME) to width,
                    key(ConfigMigration.HEIGHT_NAME) to height,
                ),
                "v1 的这组值应该产出写入：$edgeX/$edgeY/$width/$height",
            )
            // v1 当年的矩形（用 v1 的公式，宽高就是当年存的那两个）
            val oldW = screenW * width / 100f
            val oldH = screenH * height / 100f
            val oldLeft = (screenW * edgeX / 100f).coerceIn(0f, screenW - oldW)
            val oldTop = (screenH * edgeY / 100f).coerceIn(0f, screenH - oldH)
            // 迁移后的值，用**新公式**解析（宽高就是当年存的那两个：迁移不动尺寸）
            val newRect = WindowBounds.resolve(
                screenW, screenH, width, height,
                p.valueOf(ConfigMigration.X_NAME)!!,
                p.valueOf(ConfigMigration.Y_NAME)!!,
            )
            assertEquals(oldLeft + oldW / 2f, newRect.centerX, 1.5f, "水平中心：$edgeX/$width")
            assertEquals(oldTop + oldH / 2f, newRect.centerY, 1.5f, "垂直中心：$edgeY/$height")
        }
    }

    // ───────────────────────── 异常输入：宁可不迁，不可迁错 ─────────────────────────

    @Test
    fun garbageValuesAreSkippedInsteadOfWrittenBack() {
        // 存储是可以被手改的。NaN / Inf 进了换算会一路传成 NaN 写回配置，
        // 而 NaN 在 WindowBounds 里是「空矩形」→ 小窗退回 MIUI 摆放（静默失效）。
        // 所以一律按「没设过」跳过。
        val nan = assertNotNull(
            plan(
                key(ConfigMigration.X_NAME) to Float.NaN,
                key(ConfigMigration.WIDTH_NAME) to 70f,
            ),
        )
        assertTrue(nan.writes.isEmpty(), "NaN 位置应被跳过：${nan.writes}")

        val inf = assertNotNull(
            plan(
                key(ConfigMigration.X_NAME) to 15f,
                key(ConfigMigration.WIDTH_NAME) to Float.POSITIVE_INFINITY,
            ),
        )
        // 尺寸非法 → 退回该步的默认尺寸（v1 的 70、v2 的 80），位置照常迁移
        assertNotNull(inf.valueOf(ConfigMigration.X_NAME), "位置本身合法，应该照常迁移")
    }

    // ───────────────────────── 防漂移：迁移里的键名与默认值 ─────────────────────────

    @Test
    fun theKeysTheMigrationReadsAreTheOnesCatalogDeclares() {
        // 迁移读的是**历史键名**，所以这里抄了一遍字面量。这条断言是两者的唯一粘合剂：
        // 有人把参数名改了却没改迁移 → 这里红。不红的话后果是那几个键读不到
        // （静默当成「没设过」），老用户的位置就停在错误的语义上，而且没有任何报错。
        val sliders = Catalog.require(feature).prefs
            .filterIsInstance<PrefSpec.Slider>()
            .associateBy { it.key.substringAfter('.') }
        for (name in listOf(
            ConfigMigration.WIDTH_NAME,
            ConfigMigration.HEIGHT_NAME,
            ConfigMigration.X_NAME,
            ConfigMigration.Y_NAME,
        )) {
            assertNotNull(sliders[name], "Catalog 里没有名为 $name 的滑杆参数")
        }
        // 顺带把「默认值只有一个来源」钉住：Catalog 的那四个 default 必须就是
        // WindowBounds 里那几个实测值（hook 的兜底也引用同一处）
        assertEquals(WindowBounds.DEFAULT_WIDTH_PERCENT, sliders[ConfigMigration.WIDTH_NAME]!!.default)
        assertEquals(WindowBounds.DEFAULT_HEIGHT_PERCENT, sliders[ConfigMigration.HEIGHT_NAME]!!.default)
        assertEquals(WindowBounds.DEFAULT_X_PERCENT, sliders[ConfigMigration.X_NAME]!!.default)
        assertEquals(WindowBounds.DEFAULT_Y_PERCENT, sliders[ConfigMigration.Y_NAME]!!.default)
    }

    @Test
    fun theLegacyDefaultsAreInsideTheSlidersRangeSoTheConversionIsAlwaysMeaningful() {
        val sliders = Catalog.require(feature).prefs
            .filterIsInstance<PrefSpec.Slider>()
            .associateBy { it.key.substringAfter('.') }
        val cases = listOf(
            ConfigMigration.WIDTH_NAME to WindowBounds.LEGACY_DEFAULT_WIDTH_PERCENT,
            ConfigMigration.HEIGHT_NAME to WindowBounds.LEGACY_DEFAULT_HEIGHT_PERCENT,
            ConfigMigration.X_NAME to WindowBounds.LEGACY_DEFAULT_X_PERCENT,
            ConfigMigration.Y_NAME to WindowBounds.LEGACY_DEFAULT_Y_PERCENT,
        )
        for ((name, legacy) in cases) {
            val spec = assertNotNull(sliders[name])
            assertTrue(
                legacy in spec.min..spec.max,
                "$name 的 v1 默认值 $legacy 不在量程 ${spec.min}..${spec.max} 内",
            )
        }
    }
}
