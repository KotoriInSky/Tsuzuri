package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Prefs] 的读取契约。
 *
 * 为什么这些断言值钱：[Prefs] 是配置进入被注入进程的唯一入口，它读错的每一个值都等价于
 * **静默改变系统行为**（规范 §9）。所以这里不追求覆盖率，只钉死三条性质：
 *
 * 1. 读不到（store 是 null / 空 / 没有这个键）→ 默认值；
 * 2. 读出来是别的类型 → 默认值（配错类型时 `RemotePreferences` 会抛，`MapStore` 会 null）；
 * 3. 读的过程炸了 → 默认值，且异常绝不允许外泄到 hook 链上。
 */
class PrefsTest {

    /** 两种「什么都没有」的存储：框架没连上（null）与框架回了空实现（Empty）。 */
    private val emptyStores: List<KeyValueStore?> = listOf(null, KeyValueStore.Empty)

    private fun toggle(key: String): PrefSpec.Toggle =
        Catalog.require(key).prefs.filterIsInstance<PrefSpec.Toggle>().first()

    private fun slider(key: String): PrefSpec.Slider =
        Catalog.require(key).prefs.filterIsInstance<PrefSpec.Slider>().first()

    /**
     * 应用多选清单的测试用样例。
     *
     * **刻意不取 Catalog 里那条**：那一项由另一个人接线，在它存在之前这些断言也必须能跑
     * （而这个类型的语义并不依赖 Catalog 有没有声明它）。
     */
    private val appListSpec = PrefSpec.AppList(
        key = "corner_small_window.apps",
        labelKey = "pref.corner.apps",
        default = setOf("com.example.a", "com.example.b"),
        minCount = 2,
    )

    // ── 1. 读不到就是默认值 ────────────────────────────────────────────────

    /**
     * 规范 §9 的硬约束：每个特性默认关闭。所以对 Catalog 里的**全部**特性都要求 false，
     * 外加几个「根本不存在的键」——包括伪装成契约键形状的 `f.xxx`。
     * 任何一条变成 true，都等于模块在用户没同意的情况下改了系统行为。
     */
    @Test
    fun isEnabledIsFalseForAnyKeyWhenStoreIsEmpty() {
        for (store in emptyStores) {
            val prefs = Prefs(store)
            for (feature in Catalog.features) {
                assertFalse(prefs.isEnabled(feature.key), "特性 ${feature.key} 在空存储下必须默认关闭")
                assertFalse(prefs.isEnabled(feature), "特性 ${feature.key} 在空存储下必须默认关闭")
            }
            assertFalse(prefs.isEnabled(""))
            assertFalse(prefs.isEnabled("not_in_catalog"))
            assertFalse(prefs.isEnabled("f.not_in_catalog"))
        }
    }

    /**
     * 遍历 Catalog 而不是写死几个键：新增特性时这条测试**自动**覆盖它。
     * 「新特性忘了默认值」是最常见的一类回归，靠人肉记得补测试是迟早会漏的。
     */
    @Test
    fun everySpecFallsBackToItsOwnDefaultWhenStoreIsEmpty() {
        for (store in emptyStores) {
            val prefs = Prefs(store)
            for (feature in Catalog.features) {
                for (spec in feature.prefs) {
                    when (spec) {
                        is PrefSpec.Toggle -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 应回退默认值")
                        is PrefSpec.Slider -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 应回退默认值")
                        is PrefSpec.Choice -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 应回退默认值")
                        is PrefSpec.AppList -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 应回退默认值")
                        is PrefSpec.OrderedList -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 应回退默认值")
                        // 子页入口没有值可回退；它出现在这里只为穷举 sealed 类型
                        is PrefSpec.Screen -> Unit
                    }
                }
            }
        }
    }

    @Test
    fun rawReadsFallBackAndConfigVersionIsZero() {
        for (store in emptyStores) {
            val prefs = Prefs(store)
            assertFalse(prefs.rawBoolean("any.key", false))
            assertTrue(prefs.rawBoolean("any.key", true))
            assertEquals(7, prefs.rawInt("any.key", 7))
            assertEquals("x", prefs.rawString("any.key", "x"))
            assertNull(prefs.rawString("any.key", null))
            // 白名单类配置读不到必须是空集：宁可什么都不放行，也绝不能「兜底放行一个默认名单」。
            assertTrue(prefs.rawStringSet("any.key").isEmpty())
            assertEquals(0, prefs.configVersion())
        }
    }

    // ── 反向断言：上面的默认值不能是因为「Prefs 根本没看 store」而恰好通过 ──

    @Test
    fun valuesDoComeFromTheStoreWhenItHasThem() {
        val featureKey = Catalog.CORNER_SMALL_WINDOW
        val toggleSpec = toggle(featureKey)
        val expectedToggle = !toggleSpec.default
        val store = MapStore(
            mapOf(
                Keys.enabled(featureKey) to true,
                toggleSpec.key to expectedToggle,
            ),
        )
        val prefs = Prefs(store)
        assertTrue(prefs.isEnabled(featureKey))
        assertEquals(expectedToggle, prefs.value(toggleSpec))
    }

    // ── 2. 类型对不上 → 默认值 ────────────────────────────────────────────

    /**
     * 「配错类型」是真实发生过的场景：同一个键上一版存 String、这一版按 Boolean 读
     * （或者用户在前端多选里存了 StringSet，后端当 Int 读）。
     * 两条路都必须落到默认值：`MapStore` 是 `as?` 失败返回 null，
     * `RemotePreferences` 侧是直接抛异常（适配器吞掉）。**绝不能把「类型错」当成「用户打开了开关」**。
     */
    @Test
    fun wrongStoredTypeFallsBackToDefault() {
        val toggleSpec = toggle(Catalog.CORNER_SMALL_WINDOW)
        val sliderSpec = slider(Catalog.CORNER_SMALL_WINDOW)
        val store = MapStore(
            mapOf(
                Keys.enabled(Catalog.CORNER_SMALL_WINDOW) to "true", // 存成了字符串，读的是 Boolean
                toggleSpec.key to 1, // 存成了 Int，读的是 Boolean
                sliderSpec.key to "250", // 存成了字符串，读的是 Float
            ),
        )
        val prefs = Prefs(store)
        assertFalse(prefs.isEnabled(Catalog.CORNER_SMALL_WINDOW))
        assertEquals(toggleSpec.default, prefs.value(toggleSpec))
        assertEquals(sliderSpec.default, prefs.value(sliderSpec))
    }

    // ── 键的路由：读的必须是契约键 ────────────────────────────────────────

    /**
     * `isEnabled(key)` 必须去读 `f.<key>`，且默认值传 false。
     * 后端进程里跑的可能是旧版本模块，键名漂了**不会报错**，只会永远读不到 ——
     * 症状是「开关打开了没反应」，属于规范 §9 里最难查的一类。
     */
    @Test
    fun isEnabledReadsTheNamespacedFeatureKeyWithFalseDefault() {
        val store = RecordingStore()
        val result = Prefs(store).isEnabled(Catalog.SIDE_INDEX)
        assertEquals(listOf<Pair<String, Any?>>(Keys.enabled(Catalog.SIDE_INDEX) to false), store.reads)
        assertFalse(result, "store 没有这个键时必须回退 false")
    }

    @Test
    fun valueReadsThePrefSpecKeyWithItsOwnDefault() {
        val store = RecordingStore()
        val spec = toggle(Catalog.CORNER_SMALL_WINDOW)
        val result = Prefs(store).value(spec)
        assertEquals(listOf<Pair<String, Any?>>(spec.key to spec.default), store.reads)
        assertEquals(spec.default, result)
    }

    // ── 应用多选清单：空集是「没配过」，不是「读取失败」 ──────────────────

    /**
     * 这一条钉的是 [PrefSpec.AppList] 最容易写错的地方：**它有一个合法的空集**。
     *
     * 另外三种参数都只有「读到值」和「读不到」两态，所以实现里很容易顺手写成
     * `ifEmpty { default }`。那样做在用户把勾全部取消时会静默恢复成默认清单 ——
     * 一个只在「先勾几个再全取消」这条路径上出现的错误，界面完全看不出来。
     */
    @Test
    fun appListKeepsAStoredEmptySetAndOnlyFailsBackToDefaultOnError() {
        // 1. 读不到（store 是 null / 空的）→ default
        for (store in emptyStores) {
            assertEquals(appListSpec.default, Prefs(store).value(appListSpec))
        }
        // 2. 读取炸了 → default（与其它三类同一条纪律）
        assertEquals(
            appListSpec.default,
            Prefs(ThrowingStore(ClassCastException("这个键里存的不是 StringSet"))).value(appListSpec),
        )
        // 3. **存了空集 → 空集**（用户取消了全部勾选 = 回到自动挑选，而不是「恢复默认」）
        assertTrue(Prefs(MapStore(mapOf(appListSpec.key to emptySet<String>()))).value(appListSpec).isEmpty())
        // 4. 存了值 → 按存的来。反向断言：不能是因为「Prefs 根本没看 store」而通过
        val stored = setOf("com.example.z")
        assertEquals(stored, Prefs(MapStore(mapOf(appListSpec.key to stored))).value(appListSpec))
    }

    /**
     * 清单读的必须是**契约键**，且默认值传的是 spec 自己的 default
     * （不是空集）—— 键名漂了不会报错，只会永远读不到，症状是「用户选了应用但没反应」。
     */
    @Test
    fun appListReadsItsOwnKeyWithItsOwnDefault() {
        val store = RecordingStore()
        val result = Prefs(store).value(appListSpec)
        assertEquals(listOf<Pair<String, Any?>>(appListSpec.key to appListSpec.default), store.reads)
        assertEquals(appListSpec.default, result)
    }

    // ── 3. 读取炸了 → 默认值，异常不外泄 ──────────────────────────────────

    /**
     * 适配层的异常隔离：`:hook` 的 `SharedPrefsStore`、`:ui` 的 `SharedPreferencesStore`
     * 都是「每个方法 try/catch 后回退 defValue」，这条测试用同构的 [IsolatingStore] 锁住
     * 那份行为 —— 谁哪天把适配层里的 try/catch 删了，这里立刻红。
     */
    @Test
    fun isolatingStoreMakesPrefsReturnDefaultsInsteadOfThrowing() {
        val failures = listOf(
            IllegalStateException("remote prefs 反序列化失败"),
            ClassCastException("键里存的不是期望的类型"),
            RuntimeException("框架侧配置还没就绪"),
        )
        for (failure in failures) {
            val prefs = Prefs(IsolatingStore(ThrowingStore(failure)))
            for (feature in Catalog.features) {
                assertFalse(prefs.isEnabled(feature.key), "${failure::class.simpleName} 下 ${feature.key} 必须默认关闭")
                for (spec in feature.prefs) {
                    when (spec) {
                        is PrefSpec.Toggle -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 在读取失败时应回退默认值")
                        is PrefSpec.Slider -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 在读取失败时应回退默认值")
                        is PrefSpec.Choice -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 在读取失败时应回退默认值")
                        is PrefSpec.AppList -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 在读取失败时应回退默认值")
                        is PrefSpec.OrderedList -> assertEquals(spec.default, prefs.value(spec), "${spec.key} 在读取失败时应回退默认值")
                        is PrefSpec.Screen -> Unit
                    }
                }
            }
            assertTrue(prefs.rawStringSet("f.whitelist").isEmpty())
            assertEquals(0, prefs.configVersion())
        }
    }

    /**
     * 需求（规范 §9「读取一律失败即回退默认值」）：**`Prefs` 自己**也必须兜住 store 抛出的异常。
     *
     * 这条曾经是失败用例 —— `Prefs` 最初只是把读取直接转发给 store，异常隔离完全依赖
     * 「调用方传进来的 store 是否防御性实现」。那是个真实缺陷：一旦有人为图快写一个
     * 直通 `RemotePreferences` 的 store，类型不匹配抛出的 `ClassCastException` 就会
     * 沿 hook 链抛进 SystemUI —— 黑屏 + 重启，而且是在「只想读个开关」这种地方。
     *
     * 现在 `Prefs` 内部的 `safe {}` 已强制兜底，这条用例是它的回归网：
     * **谁把 `safe {}` 去掉，这条测试就会红。**
     */
    @Test
    fun prefsItselfMustNotPropagateStoreFailures() {
        // 必须显式声明元素类型：`listOf(A(), B())` 的 T 会被推断成第一个元素的类型，
        // 第二个元素在运行时被 checkcast 成 A —— 结果是测试自己抛 ClassCastException，
        // 看起来像主源码的 bug，实际是测试写错了。
        val failures: List<Throwable> = listOf(
            ClassCastException("f.corner_small_window 里存的不是 Boolean"),
            IllegalStateException("remote prefs 挂了"),
        )
        for (failure in failures) {
            val prefs = Prefs(ThrowingStore(failure))
            assertFalse(prefs.isEnabled(Catalog.CORNER_SMALL_WINDOW))
            assertEquals(toggle(Catalog.CORNER_SMALL_WINDOW).default, prefs.value(toggle(Catalog.CORNER_SMALL_WINDOW)))
            assertEquals(slider(Catalog.CORNER_SMALL_WINDOW).default, prefs.value(slider(Catalog.CORNER_SMALL_WINDOW)))
            assertEquals(0, prefs.configVersion())
        }
    }

    // ── 测试替身 ──────────────────────────────────────────────────────────

    /** 记录每次读取的「键 + 传入的默认值」，用来验证 Prefs 查的是契约键、默认值是 false/自身默认。 */
    private class RecordingStore : KeyValueStore {
        val reads = mutableListOf<Pair<String, Any?>>()

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            reads += key to defValue
            return defValue
        }

        override fun getInt(key: String, defValue: Int): Int {
            reads += key to defValue
            return defValue
        }

        override fun getFloat(key: String, defValue: Float): Float {
            reads += key to defValue
            return defValue
        }

        override fun getString(key: String, defValue: String?): String? {
            reads += key to defValue
            return defValue
        }

        override fun getStringSet(key: String, defValue: Set<String>): Set<String> {
            reads += key to defValue
            return defValue
        }
    }

    /**
     * 每次读取都抛。模拟的是「配错类型时 RemotePreferences 会抛」这条真实行为：
     * 框架侧反序列化拿不到期望类型时不是返回 null，而是抛。
     */
    private class ThrowingStore(private val failure: Throwable) : KeyValueStore {
        override fun getBoolean(key: String, defValue: Boolean): Boolean = throw failure
        override fun getInt(key: String, defValue: Int): Int = throw failure
        override fun getFloat(key: String, defValue: Float): Float = throw failure
        override fun getString(key: String, defValue: String?): String? = throw failure
        override fun getStringSet(key: String, defValue: Set<String>): Set<String> = throw failure
    }

    /** 与 `:hook` / `:ui` 里两个真实适配器同构：异常在这里被吞掉并回退默认值。 */
    private class IsolatingStore(private val source: KeyValueStore) : KeyValueStore {
        override fun getBoolean(key: String, defValue: Boolean) = runCatching { source.getBoolean(key, defValue) }.getOrDefault(defValue)
        override fun getInt(key: String, defValue: Int) = runCatching { source.getInt(key, defValue) }.getOrDefault(defValue)
        override fun getFloat(key: String, defValue: Float) = runCatching { source.getFloat(key, defValue) }.getOrDefault(defValue)
        override fun getString(key: String, defValue: String?) = runCatching { source.getString(key, defValue) }.getOrDefault(defValue)
        override fun getStringSet(key: String, defValue: Set<String>) = runCatching { source.getStringSet(key, defValue) }.getOrDefault(defValue)
    }
}
