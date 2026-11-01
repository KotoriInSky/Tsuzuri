package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Catalog] 的结构不变量。
 *
 * [Catalog] 是前端的**唯一数据来源**：列表页、详情页、开关、参数控件、作用域自检文案
 * 全部由它生成（界面里禁止硬编码特性）。所以这里每一条「格式」错了，后果都不是编译错误，
 * 而是界面上悄悄少一行、少一个开关，或者两个特性共用一个开关 —— 属于「看着正常、其实废了」。
 */
class CatalogTest {

    @Test
    fun recognitionIsThreeFeaturesInOneGroup() {
        // 2026-10-25 重组：传送门 + 超级小爱按用户任务收成三类（文字 / 图片 / 样式）
        // 全局识别是另起的入口，不是三类的子项：只注册接收器，热装卸
        val keys = Catalog.features.filter { it.group == FeatureGroup.SCREEN_RECOGNITION }.map { it.key }
            .filter { it != Catalog.GLOBAL_RECOGNITION }
        assertEquals(listOf(Catalog.SCREEN_TEXT, Catalog.SCREEN_IMAGE, Catalog.RECOGNITION_LOOK), keys)
        assertEquals(RestartPolicy.HOT, Catalog.require(Catalog.GLOBAL_RECOGNITION).restart)
        // 传送门的文字准入只走 contentcatcher 的准入服务，不注入任意第三方 App
        assertTrue(ScopeTarget.CONTENT_CATCHER in Catalog.require(Catalog.SCREEN_TEXT).scope)
        for (k in keys) assertEquals(RestartPolicy.HOT_RELOAD, Catalog.require(k).restart)
    }

    @Test
    fun featureKeysAreUnique() {
        // key 进配置键（`f.<key>`）。重复 = 两个特性共用一个总开关，
        // 打开 A 会连带打开 B（或反过来关掉），且界面完全看不出来。
        val duplicates = Catalog.features.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "特性 key 重复：$duplicates")
    }

    @Test
    fun everyFeatureDeclaresAScope() {
        // scope 空 = 前端「作用域自检」不会提示用户勾这个进程，后端也就装不进任何进程：
        // 结果是开关能打开、日志干干净净、什么都不发生。这是最难查的一类「功能没生效」。
        for (feature in Catalog.features) {
            assertTrue(feature.scope.isNotEmpty(), "特性 ${feature.key} 没有声明 scope")
        }
    }

    @Test
    fun everyPrefKeyIsNamespacedByItsFeatureAndGloballyUnique() {
        // 前缀 = 参数键（`<featureKey>.<pref>`）和它的特性绑死，这样删掉一个特性时
        // 它的全部键一眼可辨；全局唯一则是因为所有参数共用同一个 SharedPreferences 文件，
        // 重名会让两个特性互相覆盖对方的设置。
        val seen = mutableSetOf<String>()
        for (feature in Catalog.features) {
            for (pref in feature.prefs) {
                assertTrue(
                    pref.key.startsWith("${feature.key}."),
                    "参数键 ${pref.key} 不属于特性 ${feature.key}（少了 <featureKey>. 前缀）",
                )
                assertTrue(seen.add(pref.key), "参数键重复：${pref.key}")
            }
        }
    }

    @Test
    fun derivedKeysStayInsideTheirFeatureNamespace() {
        // 这两个键没有对应的 PrefSpec（界面暂时不给白名单多选控件），前端和后端都直接
        // 引用常量读写。`system_wide` 同时又在 prefs 里声明了一份（界面上的开关）——
        // 两份一旦漂移，就是「前端写的键后端不读」，用户看到开关打开了但没反应。
        assertTrue(Catalog.MIUI_WIDGET_PACKAGES_KEY.startsWith("${Catalog.MIUI_WIDGET_FLAG}."))
        assertTrue(Catalog.MIUI_WIDGET_SYSTEM_WIDE_KEY.startsWith("${Catalog.MIUI_WIDGET_FLAG}."))
        // 后端 `MiuiWidgetFlagInjector` 读的是常量，界面上的开关来自 prefs —— 两者必须指同一个键。
        val declaredOnUi = Catalog.require(Catalog.MIUI_WIDGET_FLAG).prefs
            .filterIsInstance<PrefSpec.Toggle>()
            .firstOrNull { it.key == Catalog.MIUI_WIDGET_SYSTEM_WIDE_KEY }
        assertNotNull(declaredOnUi, "MIUI_WIDGET_SYSTEM_WIDE_KEY 在 prefs 里没有对应的开关")
    }

    /**
     * 参数之间的依赖声明（`PrefSpec.dependsOn`）。
     *
     * 这三条必须静态可查，因为依赖写错的**表现是静默的**：
     *  - 指向一个不存在的参数 → 判断恒为 false → 那一项永远是灰的（用户以为功能坏了）；
     *  - 指向另一个特性里的同名参数 → 上面那个开关动它不动，怎么点都没反应；
     *  - 忘写 `offHintKey` → 用户看到一个灰控件而界面上没有一句解释。
     *
     * 这三种都不会让编译或运行报错，只会在真机上表现成「这个参数没用」。
     *
     * ⚠️ 判据是「目标**存在**且是 Toggle 或 Choice」—— 只查 Toggle 的话，
     * 「指向一个 Slider」这种错会漏过去：那时 [PrefSpec.isOperable] 的 `when`
     * 落进 `else` 分支、恒为 `false`，表现与「指向不存在的键」完全一样（永远灰）。
     */
    @Test
    fun everyPrefDependencyPointsAtAToggleOrChoiceInTheSameFeature() {
        val problems = ArrayList<String>()
        for (feature in Catalog.features) {
            val byKey = feature.prefs.associateBy { it.key }
            for (pref in feature.prefs) {
                val dep = pref.dependsOn ?: continue
                val targetKey = Keys.pref(feature.key, dep.toggleName)
                val target = byKey[targetKey]
                if (target == null) {
                    problems += "${pref.key} 依赖的 $targetKey 在 ${feature.key} 里不存在"
                } else if (target !is PrefSpec.Toggle && target !is PrefSpec.Choice) {
                    // 依赖只能挂在「有明确『满足 / 不满足』两种状态」的参数上。
                    // Slider / AppList / OrderedList 都在 `isOperable` 里落到 `else`（恒 false）
                    problems += "${pref.key} 依赖的 $targetKey 是 ${target::class.simpleName}，" +
                        "只有 Toggle（开着）与 Choice（选了某个取值）能当依赖目标"
                }
                if (targetKey == pref.key) {
                    problems += "${pref.key} 依赖它自己"
                }
                if (dep.offHintKey.isBlank()) {
                    problems += "${pref.key} 没写「为什么不能点」的文案键"
                }
                // Choice 依赖必须写明「选哪个取值」，否则 `isOperable` 的判据退化
                // （`choiceValue == null` → 恒 false），表现同样是「永远是灰的」。
                // 这一条是 2026-09-19 加 Choice 依赖时补的：漏写不会编译报错。
                if (target is PrefSpec.Choice && dep.choiceValue == null) {
                    problems += "${pref.key} 依赖 $targetKey（Choice）但没写 choiceValue"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("；"))
    }

    /**
     * 小窗摆放那四项**必须**挂在 `window_preset` 上，且必须匹配「自定义」那一档。
     *
     * 这条**真的发生过**：四个滑杆在开关关着时
     * 照样能拖，拖了没有任何反应（`WindowBounds.resolvePreset` 在另外两档下根本不读它们）。
     * 用户只能判断成「这个参数坏了」。
     */
    @Test
    fun theFourWindowPlacementSlidersAreGatedByTheCustomPreset() {
        val feature = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
        val presetKey = Keys.pref(feature.key, "window_preset")
        val gated = listOf(
            "window_width_percent",
            "window_height_percent",
            "window_x_percent",
            "window_y_percent",
        )
        for (name in gated) {
            val pref = feature.prefs.firstOrNull { it.key == Keys.pref(feature.key, name) }
            assertNotNull(pref, "找不到小窗参数 $name")
            assertEquals(
                presetKey,
                pref.dependencyKey(feature.key),
                "$name 必须依赖 window_preset，否则另外两档下它仍然能拖",
            )
            assertEquals(
                WindowBounds.PRESET_CUSTOM,
                pref.dependsOn?.choiceValue,
                "$name 必须只在「自定义」那一档出现",
            )
        }
        // 预设自己不能被任何东西依赖掉（否则就出现「谁也别想选自定义」的死锁组合）
        val preset = feature.prefs.first { it.key == presetKey }
        assertNull(preset.dependsOn, "预设项不能依赖别人")
    }

    /** 预设的三个取值必须与 `WindowBounds` 的常量一一对应（拼错一个字就是「这档永远选不到」）。 */
    @Test
    fun theWindowPresetOffersExactlyTheThreeKnownValues() {
        val feature = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
        val preset = assertNotNull(
            feature.prefs.filterIsInstance<PrefSpec.Choice>()
                .firstOrNull { it.key == Keys.pref(feature.key, "window_preset") },
            "Catalog 里没有 window_preset 这个 Choice",
        )
        assertEquals(
            WindowBounds.PRESETS,
            preset.options.map { it.value },
            "预设的取值范围与顺序都必须与 WindowBounds.PRESETS 一致",
        )
        // 默认值必须是三个之一 —— 不然界面默认选中第 0 项、而真机按另一个档摆放
        assertTrue(preset.default in WindowBounds.PRESETS, "默认值 ${preset.default} 不在取值集合里")
    }

    /**
     * 「档位不是自定义 → 那一项不可操作」的**行为**断言。
     *
     * 只断言声明（上一条）不够：`isOperable` 是界面真正调用的东西，
     * 它读的键拼错了、判断取反了，声明那一条照样是绿的。
     */
    @Test
    fun onlyTheCustomPresetMakesTheDependentPrefsOperable() {
        val feature = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
        val xPref = feature.prefs.first { it.key.endsWith(".window_x_percent") }
        val presetKey = Keys.pref(feature.key, "window_preset")

        // 空配置：走 `Catalog` 的默认档（`flyme`）—— ≠ custom，必须不可操作
        val off = Prefs(MapStore(emptyMap()))
        assertFalse(xPref.isOperable(off, feature.key), "默认档不是自定义 → 位置项必须不可操作")
        assertNotNull(
            xPref.blockedHintKey(off, feature.key),
            "不可操作时必须给出「为什么」",
        )

        // 显式选了另外两档：同样不可操作
        for (other in listOf(WindowBounds.PRESET_SYSTEM, WindowBounds.PRESET_FLYME)) {
            val p = Prefs(MapStore(mapOf(presetKey to other)))
            assertFalse(xPref.isOperable(p, feature.key), "$other 档下位置项必须不可操作")
        }

        val on = Prefs(MapStore(mapOf(presetKey to WindowBounds.PRESET_CUSTOM)))
        assertTrue(xPref.isOperable(on, feature.key), "选了自定义必须可操作")
        assertNull(xPref.blockedHintKey(on, feature.key), "可操作时不该有阻塞文案")

        // 没有依赖的参数任何时候都可操作
        val preset = feature.prefs.first { it.key == presetKey }
        assertTrue(preset.isOperable(off, feature.key))
    }

    /**
     * 「条件折叠」的**行为**断言：依赖不满足就收起，满足就出现，
     * 与是否声明 `hideWhenOff` 无关；显隐与可操作性同源。
     *
     * 过滤忘了调用 `isVisible`、或判据和 `isOperable` 分叉，编译都不报错 —— 表现要么是
     * 「该收起的还在」（用户对着不参与运算的滑杆白调），要么是「该出现的没了」。
     */
    @Test
    fun unmetDependencyCollapsesRowRegardlessOfHideWhenOff() {
        val feature = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
        val xPref = feature.prefs.first { it.key.endsWith(".window_x_percent") }
        val presetKey = Keys.pref(feature.key, "window_preset")

        val off = Prefs(MapStore(emptyMap()))
        assertFalse(xPref.isVisible(off, feature.key), "非自定义档时专属调节必须收起")
        val on = Prefs(MapStore(mapOf(presetKey to WindowBounds.PRESET_CUSTOM)))
        assertTrue(xPref.isVisible(on, feature.key), "选了自定义后专属调节必须出现")

        // 未声明 hideWhenOff 的依赖同样收起（以前是「禁用 + 说明」，现在统一折叠）
        val plain = PrefSpec.Slider(
            key = Keys.pref(feature.key, "window_x_percent"),
            labelKey = "test.only",
            hintKey = null,
            default = 0f, min = 0f, max = 1f, steps = 0, unit = "dp",
            dependsOn = PrefDependency("window_preset", "test.hint", choiceValue = WindowBounds.PRESET_CUSTOM),
        )
        assertFalse(plain.isVisible(off, feature.key), "未声明 hideWhenOff 的依赖不满足时也必须收起")
        assertTrue(plain.isVisible(on, feature.key))
        assertTrue(plain.isVisible(on, feature.key) == plain.isOperable(on, feature.key), "显隐与可操作性必须同源")

        // 没有依赖的项任何状态下都可见
        val preset = feature.prefs.first { it.key == presetKey }
        assertTrue(preset.isVisible(off, feature.key))
        assertTrue(preset.isVisible(on, feature.key))
    }

    @Test
    fun onlyStableAndExperimentalAreUserToggleable() {
        // 一个开了没反应的开关比没有开关更糟：用户会认为功能坏了，然后来报 bug。
        // PLANNED（还没写）和 PARKED（写了但目标没达成）必须在界面上灰显。
        assertTrue(Maturity.STABLE.isUserToggleable)
        assertTrue(Maturity.EXPERIMENTAL.isUserToggleable)
        assertFalse(Maturity.PLANNED.isUserToggleable)
        assertFalse(Maturity.PARKED.isUserToggleable)
    }

    @Test
    fun everyFeatureMaturityGateIsConsistentAcrossCatalog() {
        // 逐条特性再断言一次：上一条只验了枚举的映射，这一条保证**清单里没有漏标的**。
        for (feature in Catalog.features) {
            val expected = feature.maturity == Maturity.STABLE || feature.maturity == Maturity.EXPERIMENTAL
            assertEquals(expected, feature.maturity.isUserToggleable, "特性 ${feature.key} 的成熟度门控不一致")
        }
    }

    @Test
    fun findReturnsNullAndRequireThrowsForUnknownKey() {
        // `find` 是前端查询（用户可能从旧版的 deep link / 遗留状态进来一个已被删掉的 key）——
        // 必须返回 null 让界面走空态，而不是崩。
        assertNull(Catalog.find("no_such_feature"))
        assertNull(Catalog.find(""))
        // `require` 是后端实现用的：传的参数永远是 Catalog 自己的常量，
        // 缺了就是编程错误，必须**立刻炸**而不是安静返回 null（那会变成「特性默默没装」）。
        assertFailsWith<IllegalStateException> { Catalog.require("no_such_feature") }
        // 反向断言：存在的 key 两条路都要能拿到，否则上面两条可能因为「全都找不到」而通过。
        val existing = Catalog.features.first().key
        assertEquals(existing, Catalog.find(existing)?.key)
        assertEquals(existing, Catalog.require(existing).key)
    }

    @Test
    fun requiredScopesIsExactlyTheUnionOfFeatureScopes() {
        // 前端拿 requiredScopes 做「作用域自检」（缺哪个进程就提示用户去 LSPosed 里勾）。
        // 少一个 → 用户永远收不到提示，而特性就是不生效；多一个 → 提示一个用不到的进程，
        // 用户会把模块注入到无关进程里（规范 §8 要求最小化作用域）。
        val expected = Catalog.features.flatMap { it.scope }.toSet()
        assertEquals(expected, Catalog.requiredScopes)
        assertTrue(Catalog.requiredScopes.isNotEmpty())
    }

    @Test
    fun sliderDefaultsAreInsideTheirRange() {
        // 滑杆的 default 落在 min..max 之外时，控件显示的位置和真实值对不上，
        // 用户只要碰一下滑杆就会把值「跳」到别处 —— 表现为「我明明没动它，值变了」。
        for (feature in Catalog.features) {
            for (pref in feature.prefs.filterIsInstance<PrefSpec.Slider>()) {
                assertTrue(pref.min < pref.max, "${pref.key} 的 min(${pref.min}) 不小于 max(${pref.max})")
                assertTrue(
                    pref.default in pref.min..pref.max,
                    "${pref.key} 的默认值 ${pref.default} 不在 ${pref.min}..${pref.max} 内",
                )
                assertTrue(pref.steps >= 0, "${pref.key} 的 steps 不能为负")
            }
        }
    }

    @Test
    fun choiceDefaultsAreAmongTheirOptions() {
        // 与滑杆同类的问题：默认值不在选项里 = 界面打开时一个都没选中（或选中第一个），
        // 用户看到的「当前值」和实际读到的值不一致。
        for (feature in Catalog.features) {
            for (pref in feature.prefs.filterIsInstance<PrefSpec.Choice>()) {
                assertTrue(pref.options.isNotEmpty(), "${pref.key} 没有选项")
                assertTrue(
                    pref.default in pref.options.map { it.value },
                    "${pref.key} 的默认值 ${pref.default} 不在选项 ${pref.options.map { it.value }} 里",
                )
                val values = pref.options.map { it.value }
                assertEquals(values.size, values.toSet().size, "${pref.key} 的选项值重复")
            }
        }
    }

    @Test
    fun appListThresholdsAreSelfConsistent() {
        // 应用多选清单有三条容易写成「看着正常、其实废了」的形态：
        //  1. `minCount = 0` → 空集也算「用户配过了」，于是一个都没勾时清单是空的，
        //     而界面上看不出任何异常（症状是「扇子里一个应用都没有」）；
        //  2. `default` 自己就低于 `minCount` → 默认状态下永远进不了「用用户清单」那条路，
        //     也就是这个参数在开箱状态下是死的；
        //  3. 清单里塞空串 / 空白包名 → 消费侧按包名匹配，永远匹配不到，
        //     表现为「选了 N 个但只有 N-1 个生效」，不会有任何报错。
        for (feature in Catalog.features) {
            for (pref in feature.prefs.filterIsInstance<PrefSpec.AppList>()) {
                assertTrue(
                    pref.minCount >= 1,
                    "${pref.key} 的 minCount 必须 ≥ 1：0 会让空集被当成「用户配过了」，清单变空且界面看不出来",
                )
                if (pref.default.isNotEmpty()) {
                    assertTrue(
                        pref.default.size >= pref.minCount,
                        "${pref.key} 的默认清单只有 ${pref.default.size} 项，低于门槛 ${pref.minCount} —— 默认状态下这个参数不会生效",
                    )
                }
                val blanks = pref.default.filter { it.isBlank() }
                assertTrue(blanks.isEmpty(), "${pref.key} 的默认清单里有空包名：$blanks")
            }
        }
    }

    @Test
    fun orderedListThresholdsAreSelfConsistent() {
        // 与 AppList 同一组「看着正常、其实废了」的形态，但多一条顺序特有的：
        // 默认值里有重复项 → 扇子里会画出两个一模一样的图标（用户分不清哪个是哪个）。
        for (feature in Catalog.features) {
            for (pref in feature.prefs.filterIsInstance<PrefSpec.OrderedList>()) {
                assertTrue(
                    pref.minCount >= 1,
                    "${pref.key} 的 minCount 必须 ≥ 1：0 会让空清单被当成「用户配过了」",
                )
                if (pref.default.isNotEmpty()) {
                    assertTrue(
                        pref.default.size >= pref.minCount,
                        "${pref.key} 的默认清单只有 ${pref.default.size} 项，低于门槛 ${pref.minCount}",
                    )
                }
                assertTrue(
                    pref.default.none { it.isBlank() },
                    "${pref.key} 的默认清单里有空项：${pref.default.filter { it.isBlank() }}",
                )
                assertEquals(
                    pref.default.size,
                    pref.default.toSet().size,
                    "${pref.key} 的默认清单里有重复项，扇子里会出现两个一样的图标",
                )
            }
        }
    }

    @Test
    fun prefGroupsPartitionEveryPrefExactlyOnce() {
        // 二级页的分组是**从 Catalog 派生**的，所以这里断言它确实是一个
        // 「划分」：每一项参数恰好出现在一个分组里。
        //
        // 漏一项的后果是那一项**在界面上消失**（它不属于任何二级页），而代码、日志、
        // 编译全都正常 —— 正是 §9.2 点名的那类故障。多一项（重复放进两个分组）则会让
        // 同一个键在界面上出现两次，改了一个另一个看着没变。
        for (feature in Catalog.features) {
            val groups = feature.prefGroups()
            if (feature.prefs.isEmpty()) {
                assertTrue(groups.isEmpty(), "特性 ${feature.key} 没有参数却有分组")
                continue
            }
            val ids = groups.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "特性 ${feature.key} 的分组标识重复：$ids")
            val flattened = groups.flatMap { it.prefs }.map { it.key }
            assertEquals(
                feature.prefs.size,
                flattened.size,
                "特性 ${feature.key} 的分组项数与参数总数不一致：$ids",
            )
            assertEquals(
                feature.prefs.map { it.key }.toSet(),
                flattened.toSet(),
                "特性 ${feature.key} 的分组没有覆盖全部参数",
            )
        }
    }

    @Test
    fun cornerSmallWindowDeclaresGroupsForEveryPref() {
        // 参数多的特性（二级页那一类）**不允许**有项落进兜底分组 ——
        // 那是「声明漏了」，不是一种设计：它会显示成一组叫「其它」的参数，
        // 用户找不到东西，而维护者看不出是漏了。
        val spec = Catalog.require(Catalog.CORNER_SMALL_WINDOW)
        val ungrouped = spec.prefs.filter { it.group == null }.map { it.key }
        assertTrue(ungrouped.isEmpty(), "corner_small_window 有参数没归类：$ungrouped")
        // 顺带钉住数量：这个特性的参数是按分组重排过的，重排时弄丢一条不会有任何报错
        // 34 → 36 → **34**（2026-09-15）：中途加过 `bottom_yield_dp` / `takeover_inward_dp` 两个滑杆，
        // 当天又都删了 —— 用户拍板「不要靠加参数解决」，而且那两条本来就是同一个毛病（见 Catalog 里的说明）。
        // 接管时机现在是代码常量 `FanTrigger.TAKEOVER_INWARD_DP`。
        // 34 → **35**（2026-09-18）：加 `root_activities`（活动项的 root 兜底开关）。
        // 它是**功能开关不是调节旋钮** —— 与上面删掉的那两个滑杆不是一类东西：
        // 那两条是「把内部判定时机暴露给用户」（用户无从判断该调成多少），
        // 这一条是「一个能力给不给」（有明确是非，而且关掉后行为可预期地退化）。
        // 35 → **37**（2026-09-20）：加两份「不呼出扇形」的名单（竖屏 / 横屏各一份）。
        // 两份而不是一份是用户拍板的：同一个应用在竖屏与横屏下是两种场景
        // （游戏横屏要屏蔽、竖屏不一定），一份名单表达不了。
        // 37 → **38**（2026-10-21）：加「展开动画」预设；被它替代的四项留在隐藏分组。
        assertEquals(38, spec.prefs.size, "corner_small_window 的参数条数变了")
        // 分组顺序 = 界面上二级页的顺序，所以它必须**稳定**（这里是按常见程度排的）
        //
        // 2026-09-18 重排：`corner_icon` 曾一度 9 项，超出「一组约 8 项」。
        // 拆出 `corner_selection`（选中反馈：选中放大倍率 + 选中描环）—— 它跟着 `corner_icon`
        // 后面，因为「选中长什么样」是看完图标外观后的下一个问题。
        // 那条边界的判据：`corner_icon` = **图标本身怎么画**（透明度、尺寸、形状、圆角、
        // 指数、描边有无、描边粗细）；`corner_selection` = **被选中时额外叠什么**
        // （放大倍率、描环）。两者是「常态外观」与「选中态外观」的区别。
        assertEquals(
            listOf(
                "corner_trigger",
                // 隐藏分组：界面不显示，出现在这个位置只因 apps_mode 声明在清单组之前
                "corner_internal",
                "corner_layout",
                "corner_window",
                "corner_icon",
                "corner_selection",
                // 2026-10-22：清单移到排布之后（「内容」页 = 预览 → 排布 → 清单入口）
                "corner_list",
                "corner_motion",
                "corner_feedback",
                // corner_advanced 没了：唯一一项（用 Root 打开页面）改为自动
            ),
            spec.prefGroups().map { it.id },
        )
    }

    @Test
    fun everyPrefGroupIsRegisteredInCategories() {
        // 分组 → 类别映射表是唯一一处事实。新增一个分组却忘了登记时，
        // 编译不报错、界面也不消失（会落到「其它」），只有这里的测试能提前发现。
        val unregistered = mutableListOf<String>()
        for (feature in Catalog.features) {
            for (group in feature.prefGroups()) {
                if (group.id == PREF_GROUP_OTHER) continue
                if (group.id !in PrefCategories.of) unregistered.add("${feature.key}:${group.id}")
            }
        }
        assertTrue(unregistered.isEmpty(), "有分组没在 PrefCategories 登记：$unregistered")
    }

    @Test
    fun prefCategoriesPartitionExposedGroupsExactlyOnce() {
        // 类别层是详情页入口的来源：一个分组只能出现在一个类别里，否则同一批参数
        // 会有两个入口；暴露的分组必须**全部**出现，否则那一组参数在界面上消失。
        for (feature in Catalog.features) {
            val categories = feature.prefCategories()
            if (feature.prefs.isEmpty()) {
                assertTrue(categories.isEmpty(), "特性 ${feature.key} 没有参数却有类别")
                continue
            }
            val flattened = categories.flatMap { c -> c.groups.map { it.id } }
            assertEquals(flattened.size, flattened.toSet().size, "特性 ${feature.key} 的分组出现在多个类别里：$flattened")
            val expected = feature.prefGroups().map { it.id }.filter { PrefCategories.declarationOf(it).exposed }
            assertEquals(expected.toSet(), flattened.toSet(), "特性 ${feature.key} 的类别没有覆盖全部暴露分组")
            // 类别顺序按枚举（触发 → 内容 → 外观 → 反馈 → 高级 → 其它），对用户必须稳定
            assertEquals(
                categories.map { it.category }.sortedBy { it.ordinal },
                categories.map { it.category },
                "特性 ${feature.key} 的类别顺序与枚举顺序不一致",
            )
            // 不允许空类别行（一个点进去什么都没有的入口）
            assertTrue(categories.all { it.prefs.isNotEmpty() }, "特性 ${feature.key} 有空类别")
        }
    }

    @Test
    fun hiddenGroupsKeepTheirPrefsButStayOutOfCategories() {
        // 把手与气泡整组下架 —— 键与 hook 行为保留、存量配置不悬空，
        // 但界面上不暴露。这条测试把「保留」与「不暴露」两件事同时钉住。
        val side = Catalog.require(Catalog.SIDE_INDEX)
        assertTrue(PrefCategories.of["side_handle"]?.exposed == false, "side_handle 应登记为不暴露")
        assertTrue(side.prefs.any { it.group == "side_handle" }, "side_handle 的参数被删了（存量配置会悬空）")
        assertTrue(
            side.prefCategories().none { c -> c.groups.any { it.id == "side_handle" } },
            "side_handle 不该出现在类别视图里",
        )
    }

    @Test
    fun detailPageEntryCountShrinksToFourAndThree() {
        // 硬指标：详情页入口行数 扇形 9 → 5、侧滑索引 6 → 4。
        // 2026-10-21再减一档：扇形「高级」里唯一一项（用 Root 打开页面）改自动、
        // 侧滑「反馈」只剩「振动」一行（并入「触发」）—— 两个类别整体消失：扇形 4、侧滑 3。
        // 数量是验收判据，不是巧合 —— 它决定首屏能不能看全所有入口。
        assertEquals(4, Catalog.require(Catalog.CORNER_SMALL_WINDOW).prefCategories().size)
        assertEquals(3, Catalog.require(Catalog.SIDE_INDEX).prefCategories().size)
    }
}
