package io.github.kotoriinsky.tsuzuri.ui.state

import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.OrderedItems
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.ShortcutEntries

/**
 * 扇形清单（[PrefSpec.OrderedList]）在设置界面这一侧的**唯一**读写入口。
 *
 * ## 为什么要有这一层，而不是各页自己 `ConfigStore.putString`
 *
 * 清单有三条**必须处处一致**的契约，分散在四个页面里各写一遍必然漂移：
 *
 *  1. **空清单 = 删键**，不是写空串（`OrderedItems` 的 KDoc 与 `PrefSpec.AppList` 的契约）。
 *     写空串的后果是「用户以为清空了，其实留下一个空值」，语义上既不是「没配过」
 *     也不是「配了空的」；
 *  2. **顺序就是扇形里的槽位顺序**（`FanMetrics.place(index)`），所以任何一次写入
 *     都必须是 `OrderedItems.encode`（它去掉重复与空项），不许自己 `joinToString`；
 *  3. **新键为空时回退老键**（`<特性>.apps`，见 [read]）。老键是 `StringSet`，
 *     换读法会 `ClassCastException` → 静默回退默认值 → 用户那 16 个应用**看起来凭空消失**。
 *     所以：新键优先、老键兜底、老键不删。
 *
 * ## 老键的名字为什么写在这里
 *
 * `apps` → `items` 这次换键是**换类型导致的**（无序集合 → 有序字符串），理由写在
 * `Catalog` 那条 `PrefSpec.OrderedList` 的注释里。这里只承认「老键叫什么」这一个事实：
 * 它不会随特性变（同一个特性里同时出现 `apps` 和 `items` 两个键是不可能的），
 * 而多写一处就会多一处漂移。
 */
object FanItems {

    /** 当前键的参数名（`<特性>.items`）。 */
    private const val PREF = "items"

    fun key(featureKey: String): String = Keys.pref(featureKey, PREF)

    /**
     * 老键的完整键（`<特性>.apps`）。**只用于读与迁移，永远不写、不删。**
     *
     * 名字取自 `Keys.legacyItems` 而不是在这里再写一遍字面量：hook 侧读的是同一个键，
     * 两边各写一遍、只改了一边的话，表现是「用户清单凭空消失」，而编译和日志全正常。
     */
    fun legacyKey(featureKey: String): String = Keys.legacyItems(featureKey)

    /** 清单声明属于哪个特性（`corner_small_window.items` → `corner_small_window`）。 */
    fun featureKeyOf(spec: PrefSpec.OrderedList): String = spec.key.substringBeforeLast('.')

    /**
     * 从一个特性里找出清单声明（`Catalog` 里那条 [PrefSpec.OrderedList]）。
     *
     * 给「手里只有特性键」的调用方用（参数二级页里的预览要读清单项数，它拿到的是
     * 分组 / 特性，而不是清单那条声明）。找不到返回 null —— 调用方按「读不到」处理，
     * 绝不猜一个键出来。
     */
    fun spec(featureKey: String): PrefSpec.OrderedList? =
        runCatching { Catalog.find(featureKey) }.getOrNull()
            ?.prefs?.filterIsInstance<PrefSpec.OrderedList>()?.firstOrNull()

    /** 老键里的内容（无序）。没有 → 空集。 */
    fun readLegacy(prefs: Prefs, featureKey: String): Set<String> =
        prefs.rawStringSet(legacyKey(featureKey))

    /**
     * 读清单（**界面显示顺序的唯一来源**）。
     *
     * 新键有内容就用它；新键为空时回退老键并给出一个确定的初始顺序
     * （`OrderedItems.migrateFromUnordered`：应用在前、快捷方式在后，各自按字符串排序）。
     *
     * 这条回退与 hook 侧是**同一条规则**，所以即使用户从没打开过这一页，
     * 界面上看到的清单和扇子里用的清单也是同一份 —— 界面显示 0 项、扇子却在用 16 项
     * 是最难解释的一类不一致。
     */
    fun read(prefs: Prefs, spec: PrefSpec.OrderedList): List<String> {
        val current = prefs.value(spec)
        if (current.isNotEmpty()) return current
        val legacy = readLegacy(prefs, featureKeyOf(spec))
        return OrderedItems.migrateFromUnordered(legacy) ?: spec.default
    }

    /**
     * 要不要做一次性迁移：**新键还没写过、老键里有内容**。
     *
     * 「新键还没写过」判的是 `rawString(...) == null`（键不存在），不是「值为空」——
     * 我们从来不会把空清单写成空串（[write] 走 `remove`），所以两种情况实际不会同时出现，
     * 但判「键在不在」才是这条规则的原意。
     */
    fun needsMigration(prefs: Prefs, spec: PrefSpec.OrderedList): Boolean {
        if (prefs.rawString(spec.key, null) != null) return false
        return readLegacy(prefs, featureKeyOf(spec)).isNotEmpty()
    }

    /**
     * 迁移：老键的内容按确定的初始顺序**写进新键**。老键不动（可回滚）。
     *
     * 只在升级那一刻做一次：做完新键就有值了，[needsMigration] 随即为 false ——
     * 这正是 `AppSettings.readHistory` 那个写法（「只在升级那一刻承认它一次，
     * 随即写成保序的形式」）。
     *
     * @return 迁移后的清单；没做迁移时返回 null
     */
    fun migrate(prefs: Prefs, spec: PrefSpec.OrderedList): List<String>? {
        if (!needsMigration(prefs, spec)) return null
        val migrated = OrderedItems.migrateFromUnordered(readLegacy(prefs, featureKeyOf(spec)))
            ?: return null
        write(spec.key, migrated)
        return migrated
    }

    /**
     * 写清单。**空清单 = 删键**（见类注释第 1 条）。
     *
     * 写入走 [ConfigStore]，所以「改完立刻生效」那条硬规则自动成立：
     * 配置通道会通知 hook 侧重算，不需要重启进程。
     */
    fun write(key: String, items: List<String>) {
        if (items.isEmpty()) {
            ConfigStore.remove(key)
        } else {
            ConfigStore.putString(key, OrderedItems.encode(items))
        }
    }

    /** 追加一项。已经在清单里就原样返回（不重复加、也不改变它的位置）。 */
    fun appended(items: List<String>, item: String): List<String> =
        if (item in items) items else items + item

    /** 按原下标插回去（撤销「移除」用它，顺序才不会变）。 */
    fun inserted(items: List<String>, index: Int, item: String): List<String> {
        if (item in items) return items
        val out = items.toMutableList()
        out.add(index.coerceIn(0, out.size), item)
        return out
    }

    /** 去掉一项。 */
    fun without(items: List<String>, item: String): List<String> = items.filterNot { it == item }

    /**
     * 把第 [from] 项移到第 [to] 位（就是拖拽落位那一步）。
     *
     * 语义与 [ReorderList][io.github.kotoriinsky.tsuzuri.ui.kit.ReorderList] 传进来的下标一致：
     * **先把 from 拿出来，再插进 to** —— 所以 `to == from` 是无操作（拖回原位不写盘）。
     */
    fun moved(items: List<String>, from: Int, to: Int): List<String> {
        if (from == to || from !in items.indices) return items
        val out = items.toMutableList()
        val item = out.removeAt(from)
        out.add(to.coerceIn(0, out.size), item)
        return out
    }

    /** 这一项是快捷方式吗（应用 = 纯包名、快捷方式 = `包名/id`，见 `ShortcutEntries`）。 */
    fun isShortcut(key: String): Boolean =
        ShortcutEntries.parse(key).kind == ShortcutEntries.Kind.SHORTCUT

    /** 这一项画谁的图标：应用 = 自己，快捷方式 = 宿主应用（它自己的图标在设置界面拿不到）。 */
    fun packageOf(key: String): String = ShortcutEntries.parse(key).pkg

    /**
     * 读同一个特性里某个**滑杆**参数的当前值（按键后缀找，如 `fan_max_targets`）。
     *
     * 用途只有一个：清单页要判断「项数超过每屏槽位数了吗」——
     * 超过就意味着扇子里会出现「⋯」翻页，而这件事必须显式告诉用户
     * （分页/截断要可发现，否则用户以为**自己选的应用丢了**）。
     *
     * 按后缀找而不是另写死一个键：这里不需要知道特性键，也就不会在换特性时失效；
     * 后缀找不到（声明被改了）返回 null，调用方**不提示**，绝不让界面崩。
     */
    fun siblingNumber(prefs: Prefs, featureKey: String, suffix: String): Float? {
        val spec = runCatching { Catalog.find(featureKey) }.getOrNull() ?: return null
        val slider = spec.prefs.filterIsInstance<PrefSpec.Slider>()
            .firstOrNull { it.key.endsWith(".$suffix") } ?: return null
        return prefs.value(slider)
    }
}
