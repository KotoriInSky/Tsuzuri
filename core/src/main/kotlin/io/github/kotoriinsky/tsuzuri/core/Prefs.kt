package io.github.kotoriinsky.tsuzuri.core

/**
 * 配置存储的抽象。
 *
 * 为什么要有这一层：[`core`] 是纯 JVM 模块，拿不到 `android.content.SharedPreferences`。
 * 前端读到的是 libxposed service 给的 `RemotePreferences`，后端读到的是框架给的
 * `getRemotePreferences()`，两者都是 `SharedPreferences` —— 但类型到不了这里。
 * 所以这里只描述「能读能写键值对」这件事，两边的适配器各自实现。
 *
 * 这一层顺便让配置逻辑可以脱离设备做单元测试（见 `PrefsTest`），
 * 而配置读取是「读错就静默改系统行为」的高危路径，必须能测。
 */
interface KeyValueStore {
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getInt(key: String, defValue: Int): Int
    fun getFloat(key: String, defValue: Float): Float
    fun getString(key: String, defValue: String?): String?

    /** 白名单类配置用它。读不到一律返回空集，绝不放行。 */
    fun getStringSet(key: String, defValue: Set<String>): Set<String>

    /**
     * 这个键**存在吗**（不管值是什么）。
     *
     * 只有 [Prefs.rawBooleanOrNull] 用它，理由写在那个函数上：布尔的取值空间只有两个，
     * 没有 `NaN` 那种「不可能出现的合法值」可以当哨兵，只能先问「在不在」。
     *
     * ⚠️ **`contains == true` 不等于「读得出来」**：键可能是别的类型
     * （用户装过更新版本 / 被手改过），那时 `getBoolean` 会抛。
     * 所以调用方必须把读取本身也包在兜底里 —— [Prefs] 的 [safe] 就是干这个的。
     *
     * 基类给了一个**保守的默认实现**（读不到就报「不在」），
     * 这样三个进程各自的适配器不需要同时改一遍；但**能准确回答的适配器应当覆写它**
     * （`android.content.SharedPreferences` 自带 `contains`，就是准确的）。
     * 默认实现保守的代价是「键存在但类型不对时被当成不存在」—— 那恰好是
     * [Prefs.rawBooleanOrNull] 想要的处置（按「没设过」处理，见那个函数）。
     */
    fun contains(key: String): Boolean = false

    /** 什么都没有的存储：框架没连上、配置读不到时用它，行为等价于「全部默认值」。 */
    object Empty : KeyValueStore {
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getString(key: String, defValue: String?) = defValue
        override fun getStringSet(key: String, defValue: Set<String>) = defValue
        override fun contains(key: String) = false
    }
}

/** 调试用：内存实现，给单元测试和 Compose Preview 用。 */
class MapStore(private val map: Map<String, Any?> = emptyMap()) : KeyValueStore {
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
    override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValue: Set<String>) =
        @Suppress("UNCHECKED_CAST")
        (map[key] as? Set<String>) ?: defValue
    // 准确回答（不是走基类那个保守默认）：单测要能区分「设成 false」与「没设过」
    override fun contains(key: String) = map.containsKey(key)
}

/**
 * 类型化配置读取器。
 *
 * 铁律（规范 §9）：**任何读取失败都回退默认值**，绝不因为读不到配置就改变系统行为。
 * 这条契约由 [safe] 在类内部强制执行，不依赖调用方自觉；`store` 也允许为 null。
 */
class Prefs(private val store: KeyValueStore?) {

    private val s: KeyValueStore get() = store ?: KeyValueStore.Empty

    /**
     * 配置读取的统一入口：**任何失败都回退默认值，绝不向外抛**。
     *
     * 为什么不能只靠调用方 try/catch：这个类的契约就是「读配置不会出事」，
     * 而配置读失败的表现形式很杂 —— 类型不匹配（用户在旧版本存过 String、
     * 现在按 Boolean 读）会抛 `ClassCastException`，远程实现在框架侧出错会抛别的。
     * 一旦漏出去，宿主进程（SystemUI / 负一屏 / system_server）就崩了，
     * 而且是在「只是想读个开关」这种最不该出事的地方崩。
     *
     * 这里连 `Throwable` 一起兜住是刻意的：在这个位置，`Error` 的正确处置
     * 也不是「把系统进程崩掉」。
     */
    private inline fun <T> safe(default: T, read: () -> T): T =
        try {
            read()
        } catch (_: Throwable) {
            default
        }

    /**
     * 叠一层「正在拖动、还没落盘」的浮点值，得到一个**预览用**的读取器。
     *
     * ## 为什么需要它
     *
     * 滑杆原来每移动一像素就写一次配置（远程配置要过 Binder），而预览读的正是这份配置 ——
     * 于是「实时预览」与「不要写几十次盘」变成互相矛盾的两件事。解法是**分开两条路**：
     *
     * ```
     * 拖动中  ──写草稿──►  界面显示 + 预览重算     （本地，无 IO）
     * 抬手     ──写配置──►  RemotePreferences      （一次，真机据此重装 hook）
     * ```
     *
     * 所以预览与参数行都读 `prefs.withFloats(draft)`，而落盘只发生在抬手那一刻。
     *
     * 空草稿返回 `this`（不是新对象）：预览那边的图标解码等副作用是按 `Prefs`
     * **实例**做缓存的，多造一个等价对象就会让它们重算一遍。
     */
    fun withFloats(overrides: Map<String, Float>): Prefs =
        if (overrides.isEmpty()) this else Prefs(OverlayStore(store, overrides))

    /** 读特性总开关。缺失 = 关（规范 §9：每个特性默认关闭）。 */
    fun isEnabled(featureKey: String): Boolean =
        safe(false) { s.getBoolean(Keys.enabled(featureKey), false) }

    fun isEnabled(feature: FeatureSpec): Boolean = isEnabled(feature.key)

    fun value(pref: PrefSpec.Toggle): Boolean = safe(pref.default) { s.getBoolean(pref.key, ContentCapabilities.legacyDefault(this, pref.key, pref.default)) }

    fun value(pref: PrefSpec.Slider): Float = safe(pref.default) { s.getFloat(pref.key, pref.default) }

    fun value(pref: PrefSpec.Choice): String =
        safe(pref.default) { s.getString(pref.key, pref.default) } ?: pref.default

    /**
     * 读包名清单（应用多选）。
     *
     * 与上面三个参数不同：这里**读到的空集是合法值**（语义是「用户没配过，走自动挑选」），
     * 所以绝不能写成 `ifEmpty { pref.default }` —— 那会把「用户主动取消全部勾选」
     * 悄悄变成「恢复成默认清单」。只有**读取失败**（[safe] 兜住的那条路：
     * store 是 null / 键不存在时返回的 default 之外的所有异常）才回退 [PrefSpec.AppList.default]。
     *
     * 注意 `getStringSet` 返回的可能是**存储内部的那个 Set 实例**（`SharedPreferences`
     * 的实现就是这么给的），调用方只许读、不许改 —— 需要改就先 `toList()/toMutableSet()`。
     *
     * `?: pref.default` 在类型上确实多余（[KeyValueStore.getStringSet] 声明的是非空），
     * 保留它是刻意的：实现来自三个不同进程的适配器（其中可能有用 Java 写的），
     * 真返回了 null 也绝不能让 null 顺着非空类型漏出去 —— 那会在很远的地方
     * （某个 `.size` / `for (x in set)`）变成 NPE，而这里是唯一能挡住它的地方。
     */
    @Suppress("USELESS_ELVIS")
    fun value(pref: PrefSpec.AppList): Set<String> =
        safe(pref.default) { s.getStringSet(pref.key, pref.default) } ?: pref.default

    /**
     * 读**有序**清单（[PrefSpec.OrderedList]）。
     *
     * 与 [value] 的 AppList 重载同一条语义：**空清单是合法值**（= 用户没配过，
     * 消费侧走自动挑选），所以绝不能写成 `ifEmpty { pref.default }` ——
     * 那会把「用户把清单全删了」悄悄变成「恢复成默认清单」。
     *
     * 键缺失时返回 [PrefSpec.OrderedList.default]（正常就是空表），
     * **读取失败**（抛异常）也回退默认值 —— 这就是 [safe] 的契约。
     */
    fun value(pref: PrefSpec.OrderedList): List<String> = safe(pref.default) {
        val raw = s.getString(pref.key, null) ?: return@safe pref.default
        OrderedItems.decode(raw)
    }

    /**
     * 原始 Float 读取，**键不存在时返回 `null`**（其余 `raw*` 都只能给默认值，分不出
     * 「没设过」和「设成了默认值」，而迁移必须分得出来：只迁移**用户真的设过**的那些项，
     * 没设过的项应该跟着新默认值走，而不是被迁移按旧默认值钉死）。
     *
     * 用 `NaN` 当哨兵而不是加一个 `contains(key)`：`KeyValueStore` 是三个进程各自的适配器
     * 实现（其中可能有 Java 写的），加一个方法就要三处都改一遍；而 `NaN` 在
     * [io.github.kotoriinsky.tsuzuri.core.geometry.WindowBounds.resolve] 那边**本来就是非法值**
     * （会退化成空矩形），所以它当哨兵不会与任何合法配置混淆。
     * 类型不匹配（旧版本存过 String）时适配器抛异常 → [safe] 兜成 `null` → 同样按「没设过」
     * 处理，这是保守方向。
     */
    @Suppress("USELESS_ELVIS")
    fun rawFloatOrNull(key: String): Float? = safe<Float?>(null) {
        val v = s.getFloat(key, Float.NaN)
        if (v.isNaN()) null else v
    }

    /**
     * 原始 Boolean 读取，**键不存在时返回 `null`**（理由与 [rawFloatOrNull] 逐条相同）。
     *
     * 这里不能用 `NaN` 那种哨兵，因为布尔的取值空间只有两个 —— 所以实现方式不同：
     * 先 `getInt` 探一次（`SharedPreferences` 对类型不匹配会抛 `ClassCastException`，
     * 被 [safe] 兜成 `null`），探不到再 `getBoolean`。
     *
     * 为什么需要它：v3→v4 的迁移要把「用户把开关设成了 `false`」和
     * 「用户从来没设过这个开关」分开 —— 前者要搬成 `system`，
     * 后者要留给新默认值接管。而 `rawBoolean(key, false)` 把这两种情况读成同一个值，
     * 表现是「当年主动关掉摆放覆写的用户，升级后又打开了」，静默且不可逆。
     */
    fun rawBooleanOrNull(key: String): Boolean? = safe<Boolean?>(null) {
        // 类型不匹配（用户装过更新版本、键被手改成 String）时 `contains` 为真但读会抛 ——
        // 那正是要兜住的，所以用 `getInt` 探而不是只在 `contains` 上做判断。
        if (!s.contains(key)) return@safe null
        s.getBoolean(key, false)
    }

    /** 原始读取，给 UI 的通用绑定用。 */
    fun rawBoolean(key: String, def: Boolean) = safe(def) { s.getBoolean(key, def) }
    fun rawInt(key: String, def: Int) = safe(def) { s.getInt(key, def) }
    fun rawString(key: String, def: String?) = safe(def) { s.getString(key, def) }
    fun rawStringSet(key: String, def: Set<String> = emptySet()) = safe(def) { s.getStringSet(key, def) }

    /**
     * 配置版本。前端 App 升级后可能新增/改名键，靠这个字段决定要不要做迁移。
     * 读到比当前版本高 = 用户装过更新的版本 → 不做任何假设，全部走默认值。
     */
    fun configVersion(): Int = safe(0) { s.getInt(Keys.CONFIG_VERSION, 0) }
}

/**
 * [Prefs.withFloats] 的实现：**只拦 Float**，其余一律直通底层存储。
 *
 * 为什么只拦 Float：拖动中的值只有滑杆会产生，滑杆全是 Float（`PrefSpec.Slider.value()`）。
 * 开关/单选/清单在拖动这条路上根本不存在（它们是点击，点完即落盘），
 * 所以没有「草稿态」需要表达 —— 多拦一类只会让「界面显示的值」与「真机的值」
 * 多一处可能不一致的地方。
 */
private class OverlayStore(
    private val delegate: KeyValueStore?,
    private val overrides: Map<String, Float>,
) : KeyValueStore {

    private val d: KeyValueStore get() = delegate ?: KeyValueStore.Empty

    override fun getBoolean(key: String, defValue: Boolean) = d.getBoolean(key, defValue)
    override fun getInt(key: String, defValue: Int) = d.getInt(key, defValue)
    override fun getString(key: String, defValue: String?) = d.getString(key, defValue)
    override fun getStringSet(key: String, defValue: Set<String>) = d.getStringSet(key, defValue)

    // 草稿只拦 Float，所以「在不在」这件事直接转发 —— 覆写它是为了让
    // 「适配器能准确回答 contains」这件事在草稿通道上也不被基类的保守默认吃掉
    // （否则 `rawBooleanOrNull` 在预览通道上恒返回 null，而预览正好要用它读预设）。
    override fun contains(key: String) = d.contains(key)

    override fun getFloat(key: String, defValue: Float): Float =
        overrides[key] ?: d.getFloat(key, defValue)
}
