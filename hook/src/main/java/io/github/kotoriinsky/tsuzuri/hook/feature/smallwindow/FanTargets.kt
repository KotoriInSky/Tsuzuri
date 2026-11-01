package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.LinkTargets
import io.github.kotoriinsky.tsuzuri.core.OrderedItems
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.ShortcutEntries
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv

/**
 * 扇形里的一项。**三类**：应用、快捷方式（shortcut）、链接（`link:<uri>`）。
 *
 * [shortcutId] 非空 = 这一项是快捷方式，启动要走 `LauncherApps.startShortcut`
 * （见 [FanShortcuts.launch]），图标与标签都来自 shortcut 自己发布的元数据。
 *
 * [linkUri] 非空 = 这一项是**链接**（用户自己填的一段 URI），启动走 [FanLinks.launch]
 * （`Intent.parseUri` + `startActivity`），不依赖任何应用是否发布了快捷方式。
 * 两类字段**必须分开**（而不是复用 [shortcutId]）：链接项的 `id` 语义完全不同
 * （它不去查目录，而是直接交给 `Intent.parseUri`），混用迟早被某一侧误用，
 * 而误用的表现是「启动了一条奇怪的 intent」，不是编译错误。
 *
 * [icon] 在**后台线程**装好（见 [FanTargets.Pool.composePages]）：主线程上逐个
 * `getApplicationIcon` 是每项一次跨进程调用，正是侧滑索引那边记过的卡顿来源。
 * 装了图标包时，这里的位图已经是**图标包里的那张**（同样在后台线程解析）。
 */
internal data class FanTarget(
    val label: String,
    val packageName: String,
    val icon: Drawable? = null,
    /** 非空 = 快捷方式。取值是 `ShortcutInfo.id`。 */
    val shortcutId: String? = null,
    /**
     * 非空 = 链接项。取值是**去掉 `link:` 前缀之后**的 URI（可直接交给 `Intent.parseUri`）。
     *
     * ⚠️ 它**不表示这条链接合法**：合法性归 `LinkTargets.check`，判在启动那一刻
     * （见 [FanLinks.launch]）—— 配置可能被手改过，合成时合法不等于启动时合法。
     */
    val linkUri: String? = null,
    /**
     * 配置里的稳定键，**只有链接项需要显式给**。
     *
     * 应用/快捷方式的键可以由 [packageName] / [shortcutId] 推出来，而链接项的键是
     * 他当初被写进清单的那**整串**（`link:weixin://scanqrcode`）—— 它既不是包名也不是 id，
     * 推不出来。这个键同时是**单项图标覆盖的键**：用户给某一条链接单独换的图标就存在
     * 这一个键上（[Pool.linkIcon]），所以它必须与设置界面里那一行的键逐字相同。
     */
    val rawKey: String? = null,
) {
    /** 配置里的稳定键：应用是 `包名`，快捷方式是 `包名/快捷方式id`，链接是整串清单项。 */
    val key: String
        get() = rawKey
            ?: shortcutId?.let { FanTargets.shortcutKey(packageName, it) }
            ?: packageName

    val isShortcut: Boolean get() = shortcutId != null

    /** 是链接项吗。启动分发必须先判它（链接项**没有包名**，见 [FanShortcuts.launch] 的注释）。 */
    val isLink: Boolean get() = linkUri != null
}

/**
 * 扇形里的项从哪来。
 *
 * ## 两条路：用户手挑的清单，或自动挑选
 *
 * ```
 * 用户清单（`corner_small_window.apps`，非空且 ≥ 门槛）
 *   → 扇形只显示这些项（不足 3 个时用「最近使用 + 名称序」补足到 3）
 * 否则 = 没配过（或配的全是不可启动的包）
 *   → 自动挑选：UsageStats 最近使用 → 内存采样器 → 全部可启动应用补全
 * ```
 *
 * 走的是哪一条**必须能从日志看出来**（[Pool.listSource] / [describe] /
 * 合成时那行 INFO），因为「用户以为自己在用自己挑的清单」而实际在用自动挑选，
 * 是这一层最难查的一类问题。
 *
 * ## 清单里有三类项（应用 / 快捷方式 / 链接）
 *
 * 存储格式就是一组字符串（有序，见 `OrderedItems`），靠前缀与 `/` 区分
 * （[/] 定义在 [shortcutKey]，前缀定义在 `LinkTargets.PREFIX`）：
 *
 * ```
 * com.tencent.mm                             应用
 * com.tencent.mm/launch_type_scan_qrcode     快捷方式（微信「扫一扫」）
 * link:weixin://scanqrcode                   链接（用户自己填的一段 URI）
 * ```
 *
 * 包名按 Java 规范不含 `/`、也不含 `:`，所以前两类无歧义；**旧的纯包名配置语义完全不变，
 * 不需要迁移**。快捷方式项由设置界面的「导入快捷方式」写入；链接项由「填链接」页写入
 * （`LinkImportScreen`），也在那边当场校验（**校验不需要设备能力**，是纯字符串逻辑，
 * 所以它能在设置界面做，而快捷方式那种「id 存不存在」做不到）。
 *
 * ### 链接项（`link:`）为什么是一等公民而不是「快捷方式的另一种写法」
 *
 * 快捷方式项要靠宿主应用**发布过**那条快捷方式（启动走 `LauncherApps.startShortcut`），
 * 而「微信扫一扫」在本 ROM 上是一条**动态且未被固定**的快捷方式，枚举时极易看不到
 * （取证写在 [FanShortcuts.MATCH_FLAGS]）。它的真实 scheme `weixin://scanqrcode`
 * 却一直存在 —— 链接项直接 `Intent.parseUri` + `startActivity`，不依赖任何发布行为。
 * 分类/校验的完整论证见 `LinkTargets` 与 `ShortcutEntries` 的类注释。
 *
 * ### 非法链接项**不在这里丢**（与「不可启动的应用」不同的取舍）
 *
 * `FanLinks.launch` 会在启动前再校验一次（配置可能被手改），不通过就**拒绝启动并打日志**；
 * 但合成这一层**仍然把它画出来**（标签用 `LinkTargets.fallbackLabel`，永远不为空）。
 * 理由与「宿主不可启动的快捷方式不丢」同源：**留一个点不动的图标，比让用户填过的项
 * 凭空消失要好** —— 后者用户没有任何办法自愈。它可删、可拖、可换图标，点一下日志里
 * 会明说「没通过安全校验（哪一种）＋ 原始字符串」。
 *
 * ## 手工填的 id 也能进扇子（保留规则见 [ShortcutEntries]）
 *
 * 判定「这条快捷方式项还算不算数」的规则表在契约层
 * [io.github.kotoriinsky.tsuzuri.core.ShortcutEntries.verdictOf]（那边有整张表、理由与单测）。
 * 与从前最要紧的差别：**目录里没有 ≠ 失效** —— 用户手工填的 id、或系统还没枚举到的
 * 动态快捷方式都落在「保留并标记为自定义」那一格，否则就会出现「设置里明明填了、
 * 扇子里永远没有」。代价是可能画出点不动的图标，兜底在
 * [FanShortcuts.launch] 的三级降级（最终退化为启动宿主应用本身，每一级都有日志）。
 * 这三个数字（目录内 / 自定义 / 丢弃）都会出现在 [describe] 与那行 INFO 日志里。
 *
 * 自动挑选那一路**只产出应用**：快捷方式必须由用户显式导入才可能出现在扇子里
 * （系统不会告诉任何应用「用户最常用哪个快捷方式」）。
 *
 * ## 图标包
 *
 * 配了 `corner_small_window.icon_pack` 时，应用图标优先用图标包里的那张
 * （[IconPackResolver]，在后台线程解析一次并按包缓存）；没命中、包被卸载、
 * 解析失败一律**静默退回应用自带图标** —— 见那边的回退链注释。
 *
 * ## 为什么不是 Flyme / HyperGesture 的做法
 *
 * Flyme 用 `com.flyme.smartrecommend` 的推荐服务，HyperGesture 用用户手工选的
 * `components` JSON 清单。前者是魅族私有服务，后者我们抄了它的
 * **形态**但没抄它的存储：它的清单是 `AppTarget` 对象数组（包名 + 次数 + 排序），
 * 而我们只需要「哪些项」，所以配置里就是一组字符串（[PrefSpec.AppList]）。
 *
 * ## 自动挑选这一路（用户没配时的行为，**完全不变**）
 *
 * ```
 * 1. UsageStatsManager  →  跨重启的真实使用历史（需要 PACKAGE_USAGE_STATS）
 * 2. 内存采样器         →  读 SystemUiEnv.ForegroundTracker 的 volatile 缓存，零权限
 * 3. 按名称排序的可启动应用  →  补齐到 fan_max_targets
 * ```
 *
 * 第 1 级**在设备上确认过可行**：`dumpsys package com.android.systemui` 显示
 * `android.permission.PACKAGE_USAGE_STATS: granted=true`，也就是 SystemUI 自己
 * 拿着这个权限（它要用它渲染「最近使用」之类的东西）。真拿不到（抛异常 / 返回空）
 * 就静默退到第 2、3 级 —— 这正是侧滑索引那边 `RecentApps` 存在的理由，
 * 它当时的结论是「不做 UsageStats」，我们这里是**先试再退**，且失败有痕迹
 * （[Result.source] 会记下真正走通的那一级，写进日志）。
 *
 * ## 三态
 *
 * 和 `AppIndex.Result` 同构：「还没扫完」/「扫完了确实没有」/
 * 「查询本身失败了」必须分开，否则上层分不清「没有」和「不知道」。
 */
internal object FanTargets {

    private const val TAG = "FP-Fan"

    /** 少于这个数整个手势不工作（`GestureConfig.ready()` 的硬下限 3）。 */
    const val MIN_TARGETS = 3

    /**
     * 分页的硬上限。
     *
     * 为什么要有上限：**每一页的图标都要在展开前装好**（浮层的绘制路径不许分配、
     * 不许 IO），而页数是「总项数 ÷ 每页项数」。装机两百个应用时全量分页意味着
     * 一次合成要解两百个图标（每个一次跨进程调用），那会把一次手势前的准备拖成几百毫秒。
     *
     * 12 页 × 每页 23 项（max=24 时）≈ 276 项，已经覆盖「装机应用全都能翻到」；
     * 真超过时**会打 WARN 并在日志里给出数量**（见 composePages），不会静默丢弃。
     */
    private const val MAX_PAGES_HARD = 12

    /**
     * 自动挑选那一路的分页上限。
     *
     * ⚠️ 从 4 提到 [MAX_PAGES_HARD]：用户明确要求**不能因为「一页放不下」就把应用丢掉**
     *（「明明装了却翻不到」不可接受）。4 页 × 23 项 = 92 项，在这台设备上（144 个可启动应用）
     * 正好会把后 52 个应用挡在外面 —— 那是实测到的真实截断，不是理论风险。
     * 现在按项数反推页数（见 composePages），这一条只作为最后一道闸。
     */
    private const val MAX_PAGES = MAX_PAGES_HARD

    /**
     * 用户清单的分页上限，比自动挑选那一路宽。
     *
     * 为什么允许更多页：自动挑选的清单是我们编出来的，4 页就能覆盖「最近使用」；
     * 而用户清单是他**一个一个勾出来的** —— 勾了 20 个却只显示 20 个里的前 20 个
     * （在 max=6 时 4 页只有 20 个槽）会变成「我勾的应用不见了」。
     * 上限仍然要有：每一页的图标都要在合成时解码，页数不收口就可能在勾了几百个时
     * 把一次合成拖成几百毫秒的连续 IPC —— 8 页已经覆盖「挑出一屏以上常用应用」这个真实需求。
     */
    private const val MAX_PAGES_USER = 8

    /** UsageStats 的回看窗口。再长的历史对「最近使用」没有意义。 */
    private const val USAGE_WINDOW_DAYS = 7

    /**
     * 用户手挑的清单存在哪个键上 —— **两个键**，新键优先。
     *
     * ## 为什么是两个键（这不是「改个名」）
     *
     * 老键 `apps` 装的是 `StringSet`（**无序**），新键 `items` 装的是「一条一行的字符串」
     * （有序，见 `PrefSpec.OrderedList` / `OrderedItems`）。同一个键换读法会让老配置
     * 读成 `ClassCastException` → 按规范静默回退默认值 → **用户那 16 个应用看起来凭空消失**。
     * 所以换类型就必须换键，老键**不删**（可回滚）。
     *
     * ## 读取顺序（缺一不可）
     *
     * ```
     * 1. 新键 `items` 存在且能解出内容  → 用它（顺序 = 用户拖出来的顺序）
     * 2. 新键不存在                    → 读老键 `apps`（StringSet），
     *                                    用 OrderedItems.migrateFromUnordered 给一个确定的初始顺序
     * 3. 两个都没有                    → 空清单 = 没配过 → 自动挑选
     * ```
     *
     * 第 2 条**必须有**：用户还没打开过设置界面时，新键根本不存在，而他那 16 个应用
     * 必须照旧生效 —— 少了这一步，他的清单会静默变成「自动挑选」，而且日志里只有一句
     * 「自动挑选」，看起来跟没配过一模一样（最难查的一类）。
     *
     * ⚠️ 区分「键不存在」和「键存在但是空」：前者走第 2 步（迁移），后者是**用户主动清空**
     * （或手改配置写了个空串）→ 直接按「没配过」处理，**不回退老键**。
     * 否则「用户把清单全删了」会被老键里那份残留悄悄复活 —— 那正是 AppList 的 KDoc 里
     * 写死的「空集 ≠ 一个都不显示」那条语义的反面。
     *
     * ## 为什么直接拼键、不去 Catalog 里查
     *
     * 键名是跨进程契约（前端写、后端读），只要两边拼出来一样就行；而且这样 Catalog 那边
     * 还没接线时这里照样能读到用户的选择（接线是另一个人在做）。
     * 但**门槛与默认值**是从 Catalog 取声明里拿的（见 [userAppsMinCount] / [readUserItems]）——
     * 那是「声明了就能改，改了自动生效」的另一半。
     */
    private val ITEMS_KEY: String = Keys.pref(Catalog.CORNER_SMALL_WINDOW, "items")

    /**
     * **老键**（无序 `StringSet`）。只在新键不存在时读它，且只读不改（可回滚）。
     * 见 [ITEMS_KEY] 的说明。
     */
    private val USER_APPS_KEY: String = Keys.legacyItems(Catalog.CORNER_SMALL_WINDOW)

    /**
     * 图标包（[PrefSpec.AppList]，集合里只放一个包名；空集 = 不套用）。
     *
     * 为什么用 `AppList` 而不是 `Choice`：`Choice` 的 options 必须在 Catalog 里
     * **静态声明**，而图标包列表是运行时枚举出来的（用户装了什么就是什么），
     * 静态声明根本表达不了。`AppList` 是「一组包名」，语义上正好对得上
     *（图标包就是一个应用），代价是界面上要用**专门的**选择页（列出图标包而不是可启动应用）。
     * 集合里取第一个，多个时按字符串排序取最小 —— 只取第一个的行为写进日志，不静默丢弃。
     */
    private val ICON_PACK_KEY: String = Keys.pref(Catalog.CORNER_SMALL_WINDOW, "icon_pack")

    /**
     * 「用哪条清单」的显式模式（[PrefSpec.Choice]，`auto` / `manual`）。
     *
     * 它由 Catalog 声明（`corner_small_window.apps_mode`，默认 `auto`），这里按**同一个键**
     * 去读它的默认值与取值 —— 与 [userAppsMinCount] 同一个套路：接线的人改 Catalog 就生效，
     * 在接线之前（Catalog 还没有这一条时）也能用兜底默认值工作。
     *
     * 语义（**别弄反**）：`auto` = 集合里就算有值也不看，走自动挑选；
     * `manual` = 用集合，但**集合为空或不足门槛时仍然回退自动挑选** ——
     * 「手动但一个都没选」如果真显示空扇形，用户会以为功能坏了。
     */
    private val APPS_MODE_KEY: String = Keys.pref(Catalog.CORNER_SMALL_WINDOW, "apps_mode")

    private const val MODE_AUTO = "auto"
    private const val MODE_MANUAL = "manual"

    /**
     * 门槛的兜底值。
     *
     * Catalog 里那条 [PrefSpec.OrderedList]（键 `items`）声明了就用它的 `minCount`
     * （现在是 3，与 [MIN_TARGETS] 对齐：「低于它仍走自动挑选」）；
     * 接线之前（Catalog 还没有这一条时）用这个值 —— 它与 MIN_TARGETS 同一个数，
     * 因为少于 3 项时扇形本身就没有意义。
     */
    private const val USER_ITEMS_MIN_FALLBACK = MIN_TARGETS

    /** 清单来源的三种取值（进日志，用来回答「顺序没生效」这类问题）。 */
    private const val LIST_FROM_ITEMS = "新键 items"
    private const val LIST_FROM_LEGACY = "老键 apps(迁移)"
    private const val LIST_FROM_NONE = "无"

    /** 应用项与快捷方式项的分隔（`包名/快捷方式id`）。见 [FanShortcuts.SEP]。 */
    fun shortcutKey(pkg: String, id: String): String = pkg + FanShortcuts.SEP + id

    sealed interface Result {
        data object Loading : Result
        data object TooFew : Result
        data class Unavailable(val reason: String) : Result
        data class Ready(val pool: Pool, val source: String) : Result
    }

    /**
     * 一次扫描的产物。
     *
     * 只装**标签**和包名，图标在 [composePages] 时才按最终清单去取 —— 装机应用有一两百个，
     * 全部预取图标是侧滑索引那边的做法（它的面板要按字母翻页），这里最多只要 9 个。
     *
     * [components] 是「包名 → 启动组件（`包/类`）」，给图标包查表用：图标包的 appfilter
     * 是按**组件**索引的，而我们的清单只有包名。它在 [build] 里顺手记下来 ——
     * 那里本来就有 `ResolveInfo`，不额外花一次跨进程调用。
     */
    internal class Pool(
        private val context: Context,
        private val labels: Map<String, String>,
        /** 全部可启动包名，按显示名大小写不敏感排序。 */
        private val ordered: List<String>,
        /** `包名 → 启动组件（包/类）`。取不到的包不在里面（图标包那条路会退化成按包名猜）。 */
        private val components: Map<String, String>,
        /** 快捷方式目录（枚举在 SystemUI 侧，见 [FanShortcuts]）。 */
        private val shortcuts: FanShortcuts.Catalog,
    ) {
        private val iconCache = HashMap<String, Drawable?>()

        /** 快捷方式图标也缓存：一个 id 一处，同样只在合成时取一次。 */
        private val shortcutIconCache = HashMap<String, Drawable?>()

        /**
         * 链接项图标的缓存（键 = 清单项原样，即 `link:…` 整串）。
         *
         * 与另外两个缓存分开而不是共用 [shortcutIconCache]：那边的键是 `包名/id`，
         * 而链接项的键以 `link:` 开头 —— 共用一个表虽然在当前数据下不会撞键，
         * 但会让「清缓存」时的语义变得要靠推理（哪个键属于哪一类）。
         */
        private val linkIconCache = HashMap<String, Drawable?>()

        /**
         * 链接项的**宿主包名**缓存（键 = 清单项原样）。
         *
         * 取值是一次 `PackageManager.queryIntentActivities`（跨进程），只在**后台合成线程**
         * 上做一次（`composePages` 就是跑在那上面），之后一直复用。它是可选的：
         * 拿不到就是「这条链接当前谁也接不住」，逐级降级，绝不因此丢项
         * （见 [FanLinks.launch] 的第 6 级与 `CornerSmallWindow` 的自检）。
         */
        private val linkHostCache = HashMap<String, String?>()

        /**
         * 框架入口。**只有它能读共享目录里的自定义图标文件**
         * （见 [IconOverrides.decodeFile]）—— 拿不到时那一条链自动失效，
         * 其余图标来源照常。
         */
        private val moduleRef: io.github.libxposed.api.XposedInterface? = try {
            HookRegistry.module()
        } catch (_: Throwable) {
            null
        }

        private val launcherApps: LauncherApps? = try {
            context.getSystemService(LauncherApps::class.java)
        } catch (_: Throwable) {
            null
        }

        /** 快捷方式图标的解码密度（给 `getShortcutIconDrawable` 用，0 = 让它自己挑）。 */
        private val density: Int = try {
            context.resources.displayMetrics.densityDpi
        } catch (_: Throwable) {
            0
        }

        /**
         * 单项图标覆盖（用户长按某一项单独指定的图标）。
         *
         * 优先级最高：有覆盖就不看整体图标包（见 [IconOverrides] 的类注释）。
         */
        private var overrides: IconOverrides.Set = IconOverrides.EMPTY

        /** 覆盖的解码缓存（键 = 目标键）。换一次覆盖内容就整体丢弃，见 [setOverrides]。 */
        private val overrideIcons = HashMap<String, Drawable?>()

        /**
         * 最近一次合成用的图标包（null = 没套用 / 套用失败）。
         *
         * 每次合成重新查一次（用户可能刚换包），但 [IconPackResolver] 内部按包名缓存解析结果，
         * 所以常态下这次调用只是一次指针比较。
         */
        private var pack: IconPackResolver.Pack? = null

        /**
         * 最近一次合成用的是**哪一条来源**（用户清单 / 自动挑选）。
         *
         * 它存在的唯一理由是可诊断：`describe(Ready)` 会把它打出来，而「我选的清单没生效」
         * 这类报告必须先能在这里看出「配置到底读到没有、有没有被丢弃」。
         * 由 [composePages] 在后台线程写、主线程（打日志时）读，所以是 volatile。
         */
        @Volatile
        var listSource: String = "自动挑选（还没合成过）"
            private set

        /** 只在来源变化时打日志：合成会被「最近使用」变化频繁触发，每次都打就是刷屏。 */
        @Volatile
        private var loggedSource: String? = null

        val size: Int get() = labels.size

        /**
         * 这个包是不是有启动入口（且不是桌面自己）。
         *
         * 采样线程每秒问一次，所以它必须**便宜**：一次哈希查找，不碰 PackageManager，
         * 也不装图标（不能为了让图标缓存热起来而在后台线程做无意义的 IPC）。
         */
        fun launchable(pkg: String): Boolean = labels.containsKey(pkg)

        /**
         * 把清单合成扇形的**分页结果**。**后台线程调**（要读配置、要读图标）。
         *
         * ## 两条来源
         *
         *  - **用户手挑的清单**（有序键 `corner_small_window.items`，非空且 ≥ 门槛）：
         *    只显示这些项（应用 + 快捷方式），而且**顺序就是用户拖出来的那个顺序** ——
         *    扇形里每一项的位置由序号算出来（`FanMetrics.place(index)`），
         *    所以第 1 项就在第 1 个槽位。这就是换掉老的无序键的原因（见 [ITEMS_KEY]）；
         *    老键还在时按 [OrderedItems.migrateFromUnordered] 迁移出一个确定的初始顺序。
         *  - **自动挑选**（没配过 / 配的全是不可启动的包 / 不足门槛）：最近使用在前
         *    （本次开机后的真实顺序），其余按名称补齐。这一路的顺序与分页规则与引入
         *    用户清单之前**逐字一致**，而且**只产出应用**（快捷方式必须由用户导入）。
         *
         * @param recents 最近使用的包名，最新的在前；不在可启动清单里的会被丢掉
         * @param max 上限，就是 `fan_max_targets`
         * @return 至少一页；每页最多 `max` 项（多页时最多 `max - 1` 项）
         */
        fun composePages(recents: List<String>, max: Int): List<List<FanTarget>> {
            val limit = max.coerceAtLeast(1)

            // ── 配置在**合成时**读，不在 build 时读一次存起来 ──────────────────
            // 用户在设置里改完清单之后，`CornerSmallWindow.watchPrefs` 会重算配置并
            // 重新合成（它的前缀匹配 `corner_small_window.` 正好覆盖这个键）；
            // 若在 build 时缓存这份清单，用户改完得等下次重启 SystemUI 才生效。
            // 这一次读是 Binder 调用，但 composePages 只跑在 `fp-fan-scan` 这个后台线程上
            //（不是输入线程、也不是主线程），符合规范 §7.5 的红线。
            // 没配 / 包被卸载 / 解析失败 → packFor 返回 null，图标退回应用自带的
            setPack(configuredIconPack()?.let { IconPackResolver.packFor(context, it) })
            // 单项覆盖：读一次（换一次内容就丢一次解码缓存，见 setOverrides）
            setOverrides(IconOverrides.read(HookRegistry.prefs()))
            val decision = decide(configuredAppsMode())

            val orderedAll: ArrayList<Entry>
            val pageCap: Int
            var padded = 0

            if (decision.picked.size >= decision.threshold) {
                // ── 用用户清单 ──────────────────────────────────────────────
                orderedAll = ArrayList(decision.picked.size + MIN_TARGETS)
                val seen = HashSet<String>(decision.picked.size * 2 + 8)
                for (e in decision.picked) if (seen.add(e.key)) orderedAll.add(e)

                if (orderedAll.size < MIN_TARGETS) {
                    // 勾的不足 3 个：用「最近使用 → 名称序」补到**刚好** MIN_TARGETS 个。
                    // 为什么要补而不是拒绝生效：少于 3 项时扇形本身就没有意义
                    // 而「勾了 1 个应用 → 手势彻底没反应」
                    // 在用户眼里就是「这个设置一改就坏」。补足的项在日志与 [listSource]
                    // 里都写明了，用户不会以为那是自己选的。
                    for (p in recents) {
                        if (orderedAll.size >= MIN_TARGETS) break
                        if (labels.containsKey(p) && seen.add(p)) {
                            orderedAll.add(Entry(p, p, null))
                            padded++
                        }
                    }
                    for (p in ordered) {
                        if (orderedAll.size >= MIN_TARGETS) break
                        if (seen.add(p)) {
                            orderedAll.add(Entry(p, p, null))
                            padded++
                        }
                    }
                }
                pageCap = MAX_PAGES_USER
            } else {
                // ── 自动挑选（与从前逐字一致）────────────────────────────────
                orderedAll = ArrayList(recents.size + ordered.size)
                val seen = HashSet<String>(recents.size * 2 + 8)
                for (p in recents) {
                    if (labels.containsKey(p) && seen.add(p)) orderedAll.add(Entry(p, p, null))
                }
                for (p in ordered) if (seen.add(p)) orderedAll.add(Entry(p, p, null))
                pageCap = MAX_PAGES
            }

            val source = label(decision, padded)

            // 多页时每页留一个槽给「⋯」，这样各页槽数一致、换页不跳角度。
            //
            // 页数按**项数反推**而不是取固定值：用户明确要求「明明装了却翻不到」
            // 不可接受（自动挑选那一路尤其容易撞上 —— 装机应用就是全部候选）。
            // 先算出每页槽位，再由总项数推出需要的页数，最后只受 [MAX_PAGES_HARD] 约束；
            // 真的被截断时下面那条 WARN 会写明丢了多少。
            val perPage = if (orderedAll.size > limit) (limit - 1).coerceAtLeast(1) else limit
            val needed = (orderedAll.size + perPage - 1) / perPage
            val pageCount = if (orderedAll.size > limit) minOf(needed, MAX_PAGES_HARD, pageCap) else 1
            val shown = orderedAll.take(perPage * pageCount)

            listSource = source
            if (loggedSource != source) {
                loggedSource = source
                Log.i(
                    TAG,
                    "扇形清单来源：$source（可启动应用共 ${labels.size} 个，" +
                        "快捷方式目录 ${shortcuts.note}，图标包 ${pack?.packageName ?: "未套用"}，" +
                        "页数上限 $pageCap，实际 ${shown.size} 项）",
                )
                if (shown.size < orderedAll.size) {
                    // 截断必须留痕迹：用户勾的应用比能在扇形里翻到的更多时，
                    // 界面不会告诉他，只有日志会
                    Log.w(
                        TAG,
                        "清单有 ${orderedAll.size} 项，超过 $pageCap 页 × 每页 $perPage 的上限，只显示前 ${shown.size} 项",
                    )
                }
                if (decision.droppedShortcuts > 0) {
                    // 单单一个数量看不出「哪个应用的快捷方式没了」，所以把宿主包名列出来
                    //（只在这里打：这一整块由 loggedSource 把关，合成被频繁触发时不会刷屏）
                    Log.w(
                        TAG,
                        "丢弃了 ${decision.droppedShortcuts} 条快捷方式：活目录里查不到 " +
                            "${decision.droppedShortcutGone} 条（判定包没了/快捷方式被撤回：" +
                            decision.droppedShortcutPackages + "），" +
                            "活目录里明确禁用 ${decision.droppedShortcutOff} 条",
                    )
                }
                if (decision.keptCustom > 0) {
                    // 这一条是本次改动新增的放行口：目录里没有也留下了。
                    // 用户之后报「扇子里这个图标点不动」时，先看这里 —— 它是我们
                    // 明明没见过却放行的那些项（理由与兜底见 ShortcutEntries 的类注释）
                    Log.w(
                        TAG,
                        "保留了 ${decision.keptCustom} 条不在快捷方式目录里的项（自定义/未被枚举到）；" +
                            "它们点不动时会退化为启动宿主应用本身",
                    )
                }
            }

            return shown.chunked(perPage).map { chunk -> chunk.map { target(it) } }
        }

        /** 换图标包时清掉图标缓存（缓存里的位图来自上一个包，留着会串味）。 */
        private fun setPack(next: IconPackResolver.Pack?) {
            val prev = pack?.packageName
            pack = next
            if (prev != next?.packageName) {
                iconCache.clear()
                shortcutIconCache.clear()
                linkIconCache.clear()
            }
        }

        /**
         * 换单项覆盖时同理：内容变了就把解码缓存整块丢掉。
         *
         * 用**签名**比较而不是比较对象引用：读配置每次都构造一个新对象，
         * 按引用比会把缓存每轮都清掉（等于没有缓存，白解一遍 PNG）。
         */
        private fun setOverrides(next: IconOverrides.Set) {
            if (overrides.signature != next.signature) {
                overrides = next
                overrideIcons.clear()
                iconCache.clear()
                shortcutIconCache.clear()
                linkIconCache.clear()
            }
        }

        /**
         * 在第一次合成之前先把「用的是哪一条清单」算出来。
         *
         * 为什么需要：`应用清单：ready(...)` 那行日志在**主线程**上打，而第一次合成跑在
         * **扫描线程**上 —— 两者之间有竞争，而实测日志很可能先落地（设备上
         * 19:19:02.851 那两行就是同时的），那时 [listSource] 还是初始值，
         * 读日志的人会得到一个「自动挑选」的假象。所以 build 完就把来源按同样的规则
         * 先算一遍。此时「最近使用」还没有，所以「补足了几项」这一段要等真正的合成
         *（它会在 [loggedSource] 变化时再打一行，两行合起来就是完整信息）。
         */
        fun primeSource() {
            listSource = label(decide(configuredAppsMode()), padded = 0)
        }

        /** 清单里的一项：应用（[shortcutId] 与 [linkUri] 都为 null）、快捷方式、或链接。 */
        private class Entry(
            val key: String,
            val pkg: String,
            val shortcutId: String?,
            /** 非空 = 链接项，取值是去掉 `link:` 前缀的 URI。 */
            val linkUri: String? = null,
        )

        /** 一次「用谁的清单」的判定结果。build 与 compose 都要用，所以抽出来只写一遍。 */
        private class Decision(
            /**
             * 用户清单**原样**（顺序 = 用户顺序）。`auto` 模式或没配过时是空表。
             *
             * 类型是 `List` 而不是 `Set`：顺序在这份数据里是有意义的
             *（扇形第 N 项就落在第 N 个槽位），而 `Set` 表达的恰好是「顺序无意义」——
             * 这正是当初那个类型错误（`PrefSpec.AppList` 装着用户的顺序）留下的教训。
             */
            val stored: List<String>,
            val picked: List<Entry>,
            /** 因为「不可启动」被丢弃的纯包名条目数。 */
            val droppedApps: Int,
            /** 被丢弃的快捷方式条目数（= [droppedShortcutGone] + [droppedShortcutOff]）。 */
            val droppedShortcuts: Int,
            /**
             * [droppedShortcuts] 里「**活目录**里查不到它」的那部分。
             *
             * 只能发生在宿主不在可启动清单时 —— 那说明包没了 / 快捷方式被撤回了，
             * 是唯一能判死的情形（宿主可启动时同一格是「自定义」保留，见 [ShortcutEntries]）。
             */
            val droppedShortcutGone: Int,
            /** [droppedShortcuts] 里「目录里明确禁用」的那部分。 */
            val droppedShortcutOff: Int,
            /** [droppedShortcuts] 涉及到的**宿主包名**（去重、排序），打日志用。 */
            val droppedShortcutPackages: List<String>,
            /** 拆不出包名或 id 而被丢弃的条目数（空串 / `/id` / `包名/`）。 */
            val droppedInvalid: Int,
            /**
             * 保留的**链接项**条数（`link:` 前缀）。
             *
             * 为什么单独一个数：链接项在 [labelBody] 里既不是「应用」也不是「快捷方式」
             * （它没有包名、不查目录），混进任何一栏都会让日志里的数字对不上 ——
             * 而「数字对不上」正是这个特性排查时唯一的入口。
             */
            val keptLinks: Int,
            /**
             * [keptLinks] 里**没通过安全校验**的那部分（`LinkTargets.check` 有意见）。
             *
             * 它们**照样画出来**（见类注释「非法链接项不在这里丢」），点一下会被
             * [FanLinks.launch] 拒绝并在日志里给出原因。这个数字单独打出来，是为了让
             * 「扇子里那个图标为什么点了没反应」不必先点一次就能从合成日志里查到。
             */
            val keptBadLinks: Int,
            /** 保留的快捷方式里，目录**可用**但里面没有这条的条数（自定义 / 系统还没枚举到）。 */
            val keptCustom: Int,
            /**
             * 保留的快捷方式里，目录**不可用**（没法验证）的条数。
             *
             * 宿主可启动与不可启动两种都算进来：这一格的语义是「目录不可用」，不是「宿主状态」。
             */
            val keptUnverified: Int,
            val threshold: Int,
            /**
             * `auto` 模式下被忽略的清单条目数。
             *
             * 放在这里而不是一个进程内的字段：那字段靠「先调 [decide] 再调 [label]」的求值
             * 顺序才读得对，而 [label] 也会被主线程（[describe]）调到 —— 顺序一乱就会
             * 打出一个别的轮次的数字（表现为「日志里的条数对不上」）。
             */
            val ignored: Int,
            /** 显式模式（`auto` / `manual`）。`auto` 时 [stored] 一律不参与判定。 */
            val mode: String,
            /**
             * 这一次的清单是**从哪个键读出来的**（[LIST_FROM_ITEMS] / [LIST_FROM_LEGACY] /
             * [LIST_FROM_NONE]）。
             *
             * 为什么必须进日志：「顺序没生效」这类问题只有两种可能 —— 读的不是新键
             *（用户还没打开过设置界面，或者新键没写进去），或者顺序在别处被重排了。
             * 不报出来，这两种要靠猜。
             */
            val keySource: String,
            /**
             * 用户顺序的**前 3 项**（原样，未做任何过滤）。
             *
             * 这是「用户拖出来的顺序到底读到没有」的唯一直接证据：日志里看到
             * `顺序前3=[com.tencent.mm, com.aaa, com.bbb]` 就知道读对了，
             * 而如果它是空的、或与用户排的不一样，问题立刻定位在读取/写入那一段。
             * 只取 3 项：整份清单可能几十项，全打出来会刷屏。
             */
            val firstThree: List<String>,
        )

        /**
         * 判定：读配置 → 拆成应用项与快捷方式项 → 按可启动清单 / 快捷方式目录过滤 → 比门槛。
         *
         * 「过一遍可启动清单」这一步是必须的：勾过又被卸载的包要在这里丢掉，
         * 否则它会被当成「有这么一项」而画出一个点不动的图标。
         *
         * 快捷方式项的保留判定**全部**走契约层的 [ShortcutEntries.verdictOf]
         * （整张 8 格表与理由都在那边的类注释里，测试在 `ShortcutEntriesTest`），
         * 这里只负责把当前这一轮的状态喂给它。与从前不同的地方：
         *
         *  - 目录**可用**但里面**没有**这条 → **保留**，记为「自定义」。用户手工填的 id、
         *    或者系统还没枚举到的动态快捷方式都落在这一格 —— 旧代码在这里丢掉它，
         *    表现是「设置里明明填了，扇子里永远没有」，日志只有一句「丢弃失效快捷方式」；
         *  - 宿主不在可启动清单里 → **不能整组丢**（「没有启动入口」≠「没装」，见
         *    [ShortcutEntries] 的类注释）。按同一张表判：活目录里还有它 → 留；
         *    活目录里明确查不到或已禁用 → 丢；目录不可用 → 一律留；
         *  - 目录**不可用**（权限被收回 / 服务异常）→ **全部保留**。这时我们只是
         *    「不知道它还在不在」，删掉会让用户勾过的项凭空消失，那是更糟的失败。
         *
         * 「多了还是少了」这条不变量是硬要求：新规则保留的项只可能比旧实现多
         *（多出来的只有「宿主可启动 × 目录可用 × 目录里没有」那一格）。改动这里之后
         * 若日志里的保留条数比从前少，就是 bug，不是优化。
         *
         * ## 顺序：`picked` **就是用户顺序**
         *
         * 这是这次改动的核心。从前这里遍历的是 `ordered`（**按显示名排的可启动清单**），
         * 于是「用户拖出来的顺序」在扇子里根本不存在 —— 第 1 项不是用户放的第 1 项。
         * 现在**逐个走用户清单**（[readUserItems] 给的那份顺序），就地判断、就地收下：
         * 留下来的项的相对顺序与用户看到的一模一样。
         *
         * ⚠️ 一处**有意的行为变化**（写下来免得被当成 bug）：宿主不在可启动清单里、
         * 但被规则表保留的那些快捷方式，从前是「一律排到最后」，现在**留在用户给的位置**。
         * 理由：用户拖过的位置就是他的意图，把它挪到末尾是又一次「我的项自己动了」；
         * 而且这一支已经由 [ShortcutEntries] 的规则表保证了「不会因为宿主状态而丢项」。
         * 这类项的条数进日志（`label` 里的分布），因此仍然可查。
         */
        private fun decide(mode: String): Decision {
            // 显式 auto：清单里的值**不看**（用户明确选了自动挑选）。
            // 但仍然读一遍，只为把「忽略了多少条」写进日志 —— 静默忽略用户勾过的东西
            // 是这一层最难查的问题。
            val read = readUserItems()
            val storedAll = read.items
            val stored = if (mode == MODE_AUTO) emptyList() else storedAll
            val ignored = storedAll.size - stored.size

            var droppedInvalid = 0
            var droppedApps = 0
            var droppedShortcuts = 0
            var droppedGone = 0
            var droppedOff = 0
            var keptCustom = 0
            var keptUnverified = 0
            var keptLinks = 0
            var keptBadLinks = 0
            val droppedPackages = HashSet<String>(8)
            val picked = ArrayList<Entry>(stored.size)
            // **按用户顺序**逐项判定（见 decide 的 KDoc）
            for (s in stored) {
                // 拆分/分类是纯逻辑，在契约层（见 ShortcutEntries.parse）。它判为**非法**的
                // 那一类（空串、`/id`、`包名/`）在这里丢掉：既点不动，也补不出任何信息
                val item = ShortcutEntries.parse(s)
                when (item.kind) {
                    ShortcutEntries.Kind.APP -> {
                        // 勾过又被卸载的包在这里丢掉：留着会画出一个点不动的图标
                        if (labels.containsKey(item.pkg)) {
                            picked.add(Entry(item.pkg, item.pkg, null))
                        } else {
                            droppedApps++
                        }
                    }

                    ShortcutEntries.Kind.SHORTCUT -> {
                        val id = item.id
                        if (id == null) {
                            droppedInvalid++
                            Log.w(TAG, "清单项拆不出包名或 id，已忽略：\"${item.raw}\"")
                        } else {
                            // 宿主在不在可启动清单里决定规则表怎么判（见 verdictFor）
                            val hostLaunchable = labels.containsKey(item.pkg)
                            val v = verdictFor(item.pkg, id, hostLaunchable)
                            if (v.verdict.kept) {
                                picked.add(Entry(shortcutKey(item.pkg, id), item.pkg, id))
                                when (v.verdict) {
                                    // 「目录里没有」与「目录不可用」分开数：前者是用户自定义
                                    //（新的放行口），后者是我们没法验证。两个数字在 label 里
                                    // 都要出现，否则「我填的项到底进没进扇子」还是查不出来
                                    ShortcutEntries.Verdict.KEEP_CUSTOM -> keptCustom++
                                    ShortcutEntries.Verdict.KEEP_UNKNOWN -> keptUnverified++
                                    else -> {}
                                }
                            } else {
                                droppedShortcuts++
                                // 判死的原因分开数：
                                //  宿主可启动时只剩一种可能 —— 目录里明确禁用（「目录里没有」
                                //    在这一支是自定义保留，见 verdictOf）；
                                //  宿主不可启动时两种都可能 —— 活目录里查不到 = 包没了/快捷方式
                                //    被撤回；活目录里禁用 = 用户或应用主动禁用
                                if (v.inCatalog) droppedOff++ else droppedGone++
                                droppedPackages.add(item.pkg)
                            }
                        }
                    }

                    ShortcutEntries.Kind.LINK -> {
                        // ── 链接项：不查目录、不查可启动清单 ──────────────────────
                        // 用户填的一段 URI，能不能起来只有启动那一刻才知道（目标应用可能
                        // 没装、scheme 可能拼错、可能需要参数）。所以这里**一条过滤都不做**，
                        // 只记两件事：总数、以及没通过安全校验的条数（见 Decision.keptLinks）。
                        //
                        // ⚠️ 校验在这里**不做处置**，只计数：非法项照样保留（理由写在类注释
                        // 「非法链接项不在这里丢」）。真正的拒绝发生在 [FanLinks.launch] ——
                        // 那里每一条都有原样日志，用户照着能改。
                        val uri = item.linkUri.orEmpty()
                        // Entry.key 用**清单项原样**（`item.raw`），因为它同时是单项图标
                        // 覆盖的键：设置界面里那一行的键就是清单里的原样字符串，两边
                        // 只要有一个字符不同，「给这条链接单独换的图标」就永远不生效
                        // （而那种失效在日志里长得和「没声明覆盖」一模一样）。
                        picked.add(Entry(item.raw, "", null, uri))
                        keptLinks++
                        if (LinkTargets.check(uri) != null) {
                            keptBadLinks++
                            Log.w(
                                TAG,
                                "清单里有 1 条链接项没通过安全校验，仍显示但点了不会启动" +
                                    "（原因与原始字符串会在启动时打出来）：\"${item.raw}\"",
                            )
                        }
                    }

                    ShortcutEntries.Kind.INVALID -> {
                        droppedInvalid++
                        // 原样打出来：用户要照着日志去找自己当初填的那串字符
                        Log.w(TAG, "清单项拆不出包名或 id，已忽略：\"${item.raw}\"")
                    }
                }
            }

            return Decision(
                stored = stored,
                picked = picked,
                droppedApps = droppedApps,
                droppedShortcuts = droppedShortcuts,
                droppedShortcutGone = droppedGone,
                droppedShortcutOff = droppedOff,
                droppedShortcutPackages = droppedPackages.sorted(),
                droppedInvalid = droppedInvalid,
                keptCustom = keptCustom,
                keptUnverified = keptUnverified,
                keptLinks = keptLinks,
                keptBadLinks = keptBadLinks,
                threshold = userAppsMinCount(),
                ignored = ignored,
                mode = mode,
                keySource = read.source,
                firstThree = read.items.take(3),
            )
        }

        /**
         * 这一条快捷方式项该怎么判：把当前这一轮的状态喂给契约层的规则表。
         *
         * 这里**只做取数**，一条判定都不写 —— 上次那段「目录里没有就丢」的内联判断
         * 正是静默丢弃用户自定义项的来源，而它待在 hook 侧、没有任何测试能碰到。
         *
         * @param hostLaunchable 宿主的包**在不在可启动清单里**（[labels]），不是「装了没有」：
         *   这两件事不相等（`build` 会因为 `loadLabel` 失败、桌面自己、没有 launcher 入口
         *   而把活着的包挡在清单外），所以它只用来区分「自定义」与「包没了」，不足以判死。
         */
        private fun verdictFor(
            pkg: String,
            id: String,
            hostLaunchable: Boolean,
        ): Judged {
            val si = if (shortcuts.ok) shortcuts.get(pkg, id) else null
            // 目录不可用时 get 必然是 null，「有没有这条」那时是没有意义的值：
            // verdictOf 先看 catalogOk，所以照传不影响结论
            val inCatalog = si != null
            return Judged(
                verdict = ShortcutEntries.verdictOf(
                    hostLaunchable = hostLaunchable,
                    catalogOk = shortcuts.ok,
                    inCatalog = inCatalog,
                    catalogEnabled = si?.isEnabled,
                ),
                inCatalog = inCatalog,
            )
        }

        /**
         * 一次判定的结论 + 「目录里当时有没有这一条」。
         *
         * 后者纯粹给日志用：丢掉一条时说清是「活目录里查不到」（判定宿主已不在）还是
         * 「活目录里明确禁用」—— 两者的处理办法完全不同，合成一个数字就查不下去了。
         */
        private class Judged(val verdict: ShortcutEntries.Verdict, val inCatalog: Boolean)

        /**
         * 「用的是哪一条清单」的一句话。`padded` = 补足了几项（build 时还不知道，传 0）。
         *
         * 这一行是**唯一**能回答「我填的那条快捷方式去哪了」的地方（[describe] 会把它
         * 打进日志），所以快捷方式的三个数字必须分开写：目录里**有**的几条、
         * 目录里**没有**而被当作自定义保留的几条、**丢掉**的几条（再分原因）。
         * 只写一个「N 快捷方式」等于什么都没说 —— 这正是这个 bug 拖到现在的原因。
         */
        private fun label(decision: Decision, padded: Int): String {
            // 来源与顺序前 3 项**每一行都带上**：这是「用的是新键还是老键」「顺序到底读到没有」
            // 的唯一直接证据（顺序没生效时，没有这两个字段就只能靠猜）
            val suffix = buildString {
                append(" 来源=").append(decision.keySource)
                if (decision.firstThree.isNotEmpty()) {
                    append(" 顺序前3=").append(decision.firstThree.joinToString(">"))
                }
            }
            return labelBody(decision, padded) + suffix
        }

        private fun labelBody(decision: Decision, padded: Int): String =
            // 显式 auto：一句话说清「用户选的是自动挑选，集合里的 N 项没参与」
            if (decision.mode == MODE_AUTO) {
                buildString {
                    append("自动挑选(apps_mode=auto")
                    if (decision.ignored > 0) append("，忽略清单里 ").append(decision.ignored).append(" 项")
                    append(")")
                }
            } else if (decision.picked.size >= decision.threshold) {
                buildString {
                    // 三类的条数**必须分开数**：链接项既没有包名也不查目录，
                    // 混进「应用」那一栏会让日志里的 `N 应用` 与用户在设置里看到的
                    // 数量对不上 —— 而这个数字是排查这一层问题的唯一入口
                    val apps = decision.picked.count { it.shortcutId == null && it.linkUri == null }
                    val sc = decision.picked.count { it.shortcutId != null }
                    val links = decision.picked.count { it.linkUri != null }
                    append("用户清单(").append(apps).append("应用")
                    if (sc > 0) {
                        append("+").append(sc).append("快捷方式[目录内")
                            .append(sc - decision.keptCustom - decision.keptUnverified)
                        // 自定义 = 目录可用但里面没有 → 我们没见过它，按「用户说了算」放行
                        if (decision.keptCustom > 0) {
                            append("，自定义").append(decision.keptCustom)
                        }
                        // 未验证 = 目录不可用，没法判断 → 保留（不是「失效」）
                        if (decision.keptUnverified > 0) {
                            append("，目录不可用未验证").append(decision.keptUnverified)
                        }
                        append("]")
                    }
                    if (links > 0) {
                        append("+").append(links).append("链接")
                        // 没通过安全校验的那几条单独标出来：它们**画得出但点不动**
                        // （启动时会被拒绝并打日志），不写在这里就得先点一次才知道
                        if (decision.keptBadLinks > 0) {
                            append("[其中").append(decision.keptBadLinks).append("条没通过校验，点了不会启动]")
                        }
                    }
                    append(")")
                    if (decision.droppedApps > 0) {
                        append("+丢弃").append(decision.droppedApps).append("不可启动")
                    }
                    if (decision.droppedShortcuts > 0) {
                        // 丢掉的原因分开写：只有「活目录里明确禁用 / 明确查不到」才是判死，
                        // 而这两者的处理办法不同（一个是用户/应用主动禁用，一个是包没了），
                        // 合成一个数字就没法查了
                        append("+丢弃").append(decision.droppedShortcuts).append("快捷方式(目录里查不到")
                            .append(decision.droppedShortcutGone)
                            .append("/目录里已禁用")
                            .append(decision.droppedShortcutOff)
                            .append(")")
                    }
                    if (decision.droppedInvalid > 0) {
                        append("+丢弃").append(decision.droppedInvalid).append("格式非法")
                    }
                    if (padded > 0) append("+补足").append(padded).append("到").append(MIN_TARGETS)
                }
            } else {
                // 三种「自动挑选」的原因必须分开写：否则日志里只有「自动挑选」，
                // 看不出到底是用户没配、还是配了但一个都启动不了
                when {
                    decision.stored.isEmpty() -> "自动挑选(apps_mode=manual，但清单是空的)"
                    decision.picked.isEmpty() -> buildString {
                        val dropped = decision.droppedApps + decision.droppedShortcuts +
                            decision.droppedInvalid
                        append("自动挑选（用户清单 ").append(dropped)
                            .append(" 项全都没留下：不可启动 ").append(decision.droppedApps)
                            .append("，快捷方式目录里查不到 ").append(decision.droppedShortcutGone)
                            .append("，目录里已禁用 ").append(decision.droppedShortcutOff)
                            .append("，格式非法 ").append(decision.droppedInvalid)
                            .append("）")
                    }

                    else -> "自动挑选（用户清单 ${decision.picked.size} 项低于门槛 ${decision.threshold}）"
                }
            }

        // ── 图标（全部在合成时取好，绘制路径不碰）──────────────────────────

        /**
         * 应用图标。装了图标包就优先用包里的那张（没命中自动回退到自带图标）。
         *
         * 取不到不是错误：绘制时会用纯色圆占位。
         */
        private fun appIcon(pkg: String): Drawable? {
            if (iconCache.containsKey(pkg)) return iconCache[pkg]
            // ① 单项覆盖（图片文件 / 从图标包挑的那一个）
            //
            // ⚠️ 这里**必须传 `moduleRef`**，绝不能传 `null`：
            // `IconOverrides.decodeFile` 的第一行就是 `if (module == null) return null`
            // —— 读用户「选图片」那种覆盖要走框架的共享目录，没有 module 就没法读。
            // 传 null 会让应用项的「选图片」覆盖**永远静默失效**（直接退到整体图标包/自带图标），
            // 而快捷方式那条一直传的是 `moduleRef`，于是表现为「快捷方式能换图、应用不能」。
            // 这是定位到的真 bug。
            if (overrides.has(pkg)) {
                val single = overrides.drawable(moduleRef, context, pkg, overrideIcons)
                if (single != null) {
                    // ✅ 命中也要打：否则「没命中」和「根本没声明」在日志里长得一样
                    Log.i(TAG, "单项图标覆盖：$pkg 命中")
                    iconCache[pkg] = single
                    return single
                }
                // 声明了覆盖却解析不出图（共享目录里文件被删 / 图标包条目没了）——
                // 这一行必须打出来，否则「用户选了图但扇子里没变」只能靠猜。
                Log.w(TAG, "单项图标覆盖：$pkg 声明了覆盖但解析失败，退回图标包/自带图标")
            }
            val base = try {
                context.packageManager?.getApplicationIcon(pkg)
            } catch (t: Throwable) {
                Log.w(TAG, "取不到 $pkg 的图标，改用占位圆")
                null
            }
            val p = pack
            val d = if (p == null) {
                base
            } else {
                // 组件名在 build 时就记好了；没有的包（少数拿不到启动组件的）交给
                // IconPackResolver 自己按包名推 —— 两条路都在它内部兜底
                try {
                    p.drawable(pkg, components[pkg], base)
                } catch (t: Throwable) {
                    Log.w(TAG, "图标包应用失败（$pkg），用自带图标：" + t.javaClass.simpleName)
                    base
                }
            }
            iconCache[pkg] = d
            return d
        }

        /**
         * 快捷方式图标：用**它自己发布的**图标。
         *
         * 刻意**不套 appfilter 的替换**（图标包里不会有「扫一扫」这种条目，硬套只会
         * 变成微信的普通图标，反而分不清是哪个快捷方式）；但套**形状**
         *（iconback/mask/upon）：形状类图标包的效果要和应用图标保持一致，
         * 否则扇子里会出现「有底板的应用 + 没底板的快捷方式」这种拼接感。
         *
         * 拿不到 shortcut 图标就退成宿主应用的图标 —— 至少不是空白。
         */
        private fun shortcutIcon(pkg: String, id: String, si: ShortcutInfo?): Drawable? {
            val key = shortcutKey(pkg, id)
            if (shortcutIconCache.containsKey(key)) return shortcutIconCache[key]
            // ① 单项覆盖优先（用户可以只给「扫一扫」换图标，而不动微信本身）
            //
            // 与 [appIcon] 同一套日志口径：「命中」也打一行 —— 只有失败行的话，
            // 「没声明覆盖」和「声明了但读不到图」在日志里分不出来。
            if (overrides.has(key)) {
                val single = overrides.drawable(moduleRef, context, key, overrideIcons)
                if (single != null) {
                    Log.i(TAG, "单项图标覆盖：$key 命中")
                    shortcutIconCache[key] = single
                    return single
                }
                Log.w(TAG, "单项图标覆盖：$key 声明了覆盖但解析失败，退回自带图标")
            }
            val own = si?.let { FanShortcuts.iconOf(launcherApps, it, density) }
            val d = when {
                own == null -> appIcon(pkg)
                else -> try {
                    pack?.shapeOn(own) ?: own
                } catch (t: Throwable) {
                    own
                }
            }
            shortcutIconCache[key] = d
            return d
        }

        /**
         * 链接项的图标。**覆盖键就是清单项原样那串**（`link:…`）——
         * 「给某一条链接单独换图标」因此天然可用，与设置界面里那一行用的是同一个键。
         *
         * 回退顺序：单项覆盖 → 宿主应用图标 → null（绘制时会画占位圆）。
         *
         * 为什么退到**宿主应用**的图标：链接项自己没有图标（它不是应用、也不是快捷方式），
         * 而宿主（`weixin://scanqrcode` 的宿主是微信）是用户唯一认得出的东西。
         * 拿不到宿主（这条链接当前谁也接不住）就留给占位圆 —— 不猜一个应用去画。
         *
         * 刻意**不套图标包的 appfilter 替换**（与 [shortcutIcon] 同一条理由：
         * 图标包里不会有「扫一扫」这种条目，硬套只会变成微信的普通图标，
         * 反而分不清是哪一条链接）；形状类（iconback/mask）照套，免得扇子里出现
         * 「有底板的应用 + 没底板的链接」这种拼接感。
         */
        private fun linkIcon(rawKey: String, uri: String): Drawable? {
            if (linkIconCache.containsKey(rawKey)) return linkIconCache[rawKey]
            // ① 单项覆盖优先（与另外两项同一套日志口径：「命中」也要打一行，
            // 否则「没声明覆盖」和「声明了但读不到图」在日志里分不出来）
            if (overrides.has(rawKey)) {
                val single = overrides.drawable(moduleRef, context, rawKey, overrideIcons)
                if (single != null) {
                    Log.i(TAG, "单项图标覆盖：$rawKey 命中")
                    linkIconCache[rawKey] = single
                    return single
                }
                Log.w(TAG, "单项图标覆盖：$rawKey 声明了覆盖但解析失败，退回宿主应用图标")
            }
            val host = linkHost(rawKey, uri)
            val base = if (host == null) null else appIcon(host)
            val d = if (base == null) {
                null
            } else {
                try {
                    pack?.shapeOn(base) ?: base
                } catch (t: Throwable) {
                    base
                }
            }
            linkIconCache[rawKey] = d
            return d
        }

        /**
         * 链接项的宿主包名 —— **谁接得住这条 URI**。取不到返回 null。
         *
         * 用途只有两个（都不影响「这条链接在不在扇子里」）：
         *  1. `FanLinks.launch` 的第 6 级（MIUI 私有小窗选项要一个包名才构造得出来）；
         *  2. 上面 [linkIcon] 画谁的图标。
         *
         * 取法与启动路径同源：`Intent.parseUri(uri, 0)` + `queryIntentActivities`，
         * 所以「解析得出的宿主」通常就是「点下去会起来的那个应用」。
         * **只在后台合成线程调**（跨进程调用，规范 §7.5）；结果按清单项缓存。
         *
         * 拿不到（谁也接不住 / 包可见性被挡 / 解析抛异常）时返回 null 并**只记一次 INFO**：
         * 它不是错误 —— 目标应用可能刚装、刚卸、或者这条深链需要参数。
         * 真正的结论在启动那一刻的日志里（`FanLinks.logStartFailure` 会分开写
         * ActivityNotFound / SecurityException）。
         */
        private fun linkHost(rawKey: String, uri: String): String? {
            if (linkHostCache.containsKey(rawKey)) return linkHostCache[rawKey]
            val pm = context.packageManager
            val host = try {
                val intent = Intent.parseUri(uri, 0)
                @Suppress("DEPRECATION")
                pm?.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.firstOrNull()
                    ?.activityInfo
                    ?.packageName
            } catch (t: Throwable) {
                null
            }
            if (host == null) {
                Log.i(TAG, "链接项当前解析不出宿主应用（点了会走降级链）：\"$rawKey\"")
            }
            linkHostCache[rawKey] = host
            return host
        }

        /**
         * 清单里的一项 → 扇形里的一项（取标签 + 图标）。
         *
         * 三类的分派顺序与 `ShortcutEntries.Kind` 一致：**先判链接**（它既没有包名也没有 id，
         * 落到下面任何一支都会出问题），再判快捷方式，剩下的是应用。
         */
        private fun target(e: Entry): FanTarget {
            val linkUri = e.linkUri
            if (linkUri != null) {
                return FanTarget(
                    // 标签只有一个来源（`LinkTargets.fallbackLabel`）：清单页、单项图标页
                    // 与扇子用的是**同一个函数**，不会出现「扇子里叫 A、清单里叫 B」。
                    // 它永远不为空 —— 空标题在界面上就是一行看不见的东西
                    label = LinkTargets.fallbackLabel(linkUri),
                    // 宿主包名给出去是为了 MIUI 小窗选项与自检（见 linkHost）；
                    // 解析不出来时是空串 —— `FanLinks.launch` 与 `CornerSmallWindow`
                    // 都必须能接受空串（它们各自的注释里写了）。
                    packageName = linkHost(e.key, linkUri).orEmpty(),
                    icon = linkIcon(e.key, linkUri),
                    linkUri = linkUri,
                    rawKey = e.key,
                )
            }
            val id = e.shortcutId
            if (id == null) {
                return FanTarget(labels.getValue(e.pkg), e.pkg, appIcon(e.pkg), null)
            }
            val si = shortcuts.get(e.pkg, id)
            return FanTarget(
                // 快捷方式的标签来自它自己发布的元数据；目录不可用/条目消失时退成 id，
                // 这一项至少还能被识别（它多半也点不动 —— 但日志里看得出是谁）
                label = si?.let { FanShortcuts.labelOf(it) } ?: id,
                packageName = e.pkg,
                icon = shortcutIcon(e.pkg, id, si),
                shortcutId = id,
            )
        }

        /**
         * 读显式模式。**默认值取自 Catalog 里那条 [PrefSpec.Choice]**
         * （接线的人改默认值会生效），Catalog 还没有这一条时用 `auto` 兜底 ——
         * 与 [userAppsMinCount] 同一个套路。
         *
         * 读到未知取值（例如将来的第三种模式）一律按 `auto` 处理：
         * 猜错方向的代价是「用户以为在用清单、实际在用自动挑选」，
         * 而这两者里自动挑选是**永远不会空**的那个，失败方向是安全的。
         */
        private fun configuredAppsMode(): String {
            val spec = try {
                Catalog.require(Catalog.CORNER_SMALL_WINDOW).prefs
                    .filterIsInstance<PrefSpec.Choice>()
                    .firstOrNull { it.key == APPS_MODE_KEY }
            } catch (_: Throwable) {
                null
            }
            val mode = try {
                HookRegistry.prefs().rawString(APPS_MODE_KEY, spec?.default ?: MODE_AUTO)
            } catch (t: Throwable) {
                spec?.default ?: MODE_AUTO
            }
            if (mode != MODE_MANUAL) return MODE_AUTO
            // 校验取值确实在 Catalog 声明的选项里（防止手改配置写出别的字符串）
            if (spec != null && spec.options.none { it.value == MODE_MANUAL }) return MODE_AUTO
            return MODE_MANUAL
        }

        /** 用户配的图标包（单选：集合里取第一个，多个时取字典序最小的那个）。 */
        private fun configuredIconPack(): String? {
            val set = try {
                HookRegistry.prefs().rawStringSet(ICON_PACK_KEY)
            } catch (t: Throwable) {
                Log.w(TAG, "读图标包配置失败，这一轮不套用：" + t.javaClass.simpleName)
                return null
            }
            if (set.isEmpty()) return null
            val list = set.sorted()
            if (list.size > 1) {
                Log.w(TAG, "图标包是单选，配置里却有 ${list.size} 个：只取 ${list.first()}")
            }
            return list.first().takeIf { it.isNotBlank() }
        }
    }

    /**
     * 扫出全部可启动应用。**只允许在后台线程调**。
     *
     * 沿用侧滑索引 `AppIndex.build` 的取法（`queryIntentActivities` + `loadLabel`），
     * 但**不借它的代码**（那个文件有另一个协作者在改），也不做拼音分组 ——
     * 扇形只要 9 个，不需要索引。
     *
     * 三个刻意的取舍：
     *  - **排除桌面自己**：把启动器以小窗打开没有任何意义，而它一定在可启动清单里；
     *  - **按包名去重**：一个包可能有多个启动入口（设置、设置快捷方式…），扇形里
     *    同一个应用出现两次是明显的 bug；
     *  - **顺手记下启动组件**：图标包的 appfilter 是按 `包/类` 索引的（见 [IconPackResolver]），
     *    这里本来就有 `ResolveInfo`，白拿的 —— 否则每个图标都要多一次
     *    `getLaunchIntentForPackage` 跨进程调用。
     *
     * 同时完成两件「一次扫描只该做一次」的事：
     *  - 枚举快捷方式目录（[FanShortcuts.loadCatalog]，一次 binder 调用）；
     *  - 把目录**发布到配置**给设置界面读（[FanShortcuts.Catalog.publish]）——
     *    设置界面自己查不到别的应用的快捷方式（权限限制，见那边的注释）。
     */
    fun build(context: Context): Result {
        val pm = context.packageManager ?: return Result.Unavailable("no package manager")
        val home = resolveHome(context)

        val resolved = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
        } catch (t: Throwable) {
            return Result.Unavailable("query: " + t.javaClass.simpleName)
        }

        val labels = HashMap<String, String>(resolved.size * 2)
        val components = HashMap<String, String>(resolved.size * 2)
        for (r in resolved) {
            val ai = r.activityInfo ?: continue
            val pkg = ai.packageName
            if (pkg == home) continue
            if (labels.containsKey(pkg)) continue
            val label = try {
                r.loadLabel(pm).toString()
            } catch (_: Throwable) {
                ""
            }
            if (label.isEmpty()) continue
            labels[pkg] = label
            // 图标包查表用的键。ComponentName 拿不到就跳过（那个包会走「按包名猜」的兜底）
            runCatching {
                android.content.ComponentName(pkg, ai.name).flattenToString()
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { components[pkg] = it }
        }

        if (labels.size < MIN_TARGETS) return Result.TooFew
        // 排序键算一次：compareBy 里查 map 会让每次比较都做一次哈希查找
        val ordered = labels.entries
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value })
            .map { it.key }

        // 图标包：先把 PackageManager 交出去（Pack 不持 Context），再读配置
        IconPackResolver.attach(context)
        // 自定义图标走的是「App 写共享目录 / hook 读」的通道，先把目录内容打进日志：
        // 那条链路出问题时的现象只有「图标没变」，没有这行就只能靠猜
        IconOverrides.probe(try {
            HookRegistry.module()
        } catch (_: Throwable) {
            null
        })

        // 快捷方式目录：枚举 + 发布。两件都不允许失败影响主清单 —— loadCatalog 自己
        // 绝不抛异常，publish 也自己兜住（写不进去只影响设置界面的列表）
        val catalog = FanShortcuts.loadCatalog(context, catalogSupplementPackages())
        // 投递失败不可能是「没数据」之外的问题：publish 自己兜住异常、只在内容变了时投
        catalog.publish(context)
        // 清单里快捷方式的图标：单独一次投递，失败不影响上面的文字目录（见 FanShortcuts.publishIcons）
        try {
            FanShortcuts.publishIcons(context, catalog, readUserItems().items)
        } catch (t: Throwable) {
            Log.w(TAG, "快捷方式图标投递异常：" + t.javaClass.simpleName)
        }

        val pool = Pool(context, labels, ordered, components, catalog)
        // 先把「用的是哪一条清单」算出来，好让 `应用清单：ready(...)` 那行日志（主线程打，
        // 与第一次合成有竞争）从一开始就是对的 —— 详见 Pool.primeSource 的注释
        pool.primeSource()
        return Result.Ready(pool, source = "scan(" + labels.size + ")")
    }

    /**
     * 全局查询看不到的那几个包。
     *
     * 两个来源：
     *  1. **用户清单里出现过的包** —— 用户勾了「微信·扫一扫」这种项，就必须能在目录里
     *     找到它（否则 [Pool.decide] 会把它当失效项丢掉）；
     *  2. **常用推荐里写死的那几个包**（微信 / 支付宝）—— 它们是「导入」页的快捷入口，
     *     而用户还没导入过时清单里当然没有它们（`QUICK` 那四个 id 的归属包）。
     *
     * 只读清单里的**包名**部分（`pkg/id` 取 `/` 之前），所以纯应用项也参与 ——
     * 一个包只要有应用项，它发布的快捷方式就值得补一次查询。
     */
    private fun catalogSupplementPackages(): List<String> {
        val out = LinkedHashSet<String>(16)
        try {
            // 两条读取路径（新键 / 老键迁移）都在 readUserItems 里，这里只取包名部分
            for (s in readUserItems().items) {
                // 拆分在契约层（唯一一份，`ShortcutEntries.parse` 的测试钉着它）。
                // 非法项（`/x`）返回空串、由下面这一句跳过
                val pkg = ShortcutEntries.packageOf(s)
                if (pkg.isNotEmpty()) out.add(pkg)
            }
        } catch (_: Throwable) {
        }
        out.addAll(QUICK_PACKAGES)
        return out.toList()
    }

    /**
     * 常用推荐的归属包（与设置界面 `ShortcutImportScreen` 里那四个 id 对应）。
     * 两处必须一致：这边负责让目录里**有**它们，那边负责把它们**列出来**。
     */
    private val QUICK_PACKAGES = listOf(
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
    )

    /**
     * 读用户手挑的清单（**原样、保序**，不做可启动性过滤 —— 那是 [Pool.decide] 的事，
     * 因为只有它手里有可启动清单）。
     *
     * 三条读取路径与理由写在 [ITEMS_KEY] 上，这里只强调两处**不能省**的实现细节：
     *
     *  1. **`rawString` 而不是「按 [PrefSpec.OrderedList] 读」**：要区分「键不存在」与
     *     「键存在但内容是空」。[Prefs.value] 把这两种情况都变成「空表」，
     *     而前者必须走老键迁移、后者必须当成「用户清空了」—— 混起来就会出现
     *     「用户把清单删光了，那 16 个应用又自己回来了」；
     *  2. **读失败一律当作「没有清单」**（返回空表），与规范 §9「读配置失败回退默认值」
     *     同一条语义。这里绝不能让异常逃出去：调用它的地方是后台扫描线程，
     *     一次异常就会把 SystemUI 带走；
     *  3. **老键兜底与「用户清空了清单」在读取侧无法区分**（两种情况的键都不存在）。
     *     彻底解决要靠**写入侧**：设置界面做完那次迁移（把老键内容写进 `items`）之后，
     *     应当顺手 `remove(老键)` —— 老键一没，「还要不要回退」这个歧义就永远不存在了。
     *     在此之前，读取侧按「键不存在 = 还没迁移过」处理：宁可让少数「迁移后又清空」
     *     的用户看到那份旧清单（与本次改动之前的表现一致），也不能让「还没打开过设置界面」
     *     的用户丢失他那 16 个应用。
     */
    private fun readUserItems(): UserItems {
        val raw = try {
            HookRegistry.prefs().rawString(ITEMS_KEY, null)
        } catch (t: Throwable) {
            Log.w(TAG, "读有序清单失败，这一轮按「没配过」处理：" + t.javaClass.simpleName)
            null
        }

        if (raw != null) {
            val items = OrderedItems.decode(raw)
            // 顺序**原样**用，一个字都不许重排（第 N 项就落在第 N 个槽位）。
            // ⚠️ 这里**不打日志**：本函数每次合成都会被调（「最近使用」一变就合成），
            // 打在这里就是刷屏。顺序与来源由 [label] 汇总进 `listSource`，
            // 而那一行只在来源**变化**时打（见 [loggedSource]）。
            if (items.isNotEmpty()) return UserItems(items, LIST_FROM_ITEMS)
            // 键在、内容空：用户把清单清空了（或手改配置写了个空串）。
            // **不回退老键** —— 那会把用户刚删掉的清单复活（见 [ITEMS_KEY] 的说明）
            return UserItems(emptyList(), LIST_FROM_ITEMS)
        }

        // 新键还不存在：Catalog 声明里若给了非空默认值，以它为准（现在声明的是空表）
        val declared = orderedListSpec()?.default.orEmpty()
        if (declared.isNotEmpty()) return UserItems(declared, LIST_FROM_ITEMS)

        // 老键兜底。**这一条必须有**：用户还没打开过设置界面时新键根本不存在，
        // 而他那 16 个应用必须照旧生效 —— 否则清单会静默变成「自动挑选」。
        // ⚠️ 与「用户清空了清单」在**这一层**无法区分（两种情况的键都不存在）：
        // 见本函数 KDoc 的第 3 条 —— 需要写入侧在迁移后把老键删掉，这条歧义才彻底消失。
        val legacy = try {
            HookRegistry.prefs().rawStringSet(USER_APPS_KEY)
        } catch (t: Throwable) {
            Log.w(TAG, "读老键清单失败，这一轮按自动挑选处理：" + t.javaClass.simpleName)
            emptySet()
        }
        if (legacy.isEmpty()) return UserItems(emptyList(), LIST_FROM_NONE)

        val migrated = OrderedItems.migrateFromUnordered(legacy)
        if (migrated == null || migrated.isEmpty()) return UserItems(emptyList(), LIST_FROM_NONE)
        return UserItems(migrated, LIST_FROM_LEGACY)
    }

    /**
     * 一次「用户清单」的读取结果：**保序**的内容 + 来源（进日志）。
     *
     * @param items 用户顺序（未过滤、未去重之外的处理）；空 = 没配过
     * @param source [LIST_FROM_ITEMS] / [LIST_FROM_LEGACY] / [LIST_FROM_NONE]
     */
    private class UserItems(val items: List<String>, val source: String)

    /**
     * Catalog 里那条**有序清单**的声明（键 = [ITEMS_KEY]）；还没接线时返回 null。
     *
     * 为什么要查声明而不是写死：门槛（[PrefSpec.OrderedList.minCount]）与默认值都只有
     * 一处事实来源（Catalog），接线的人改那边就自动生效 —— 与 [configuredAppsMode] /
     * [userAppsMinCount] 同一个套路。
     */
    private fun orderedListSpec(): PrefSpec.OrderedList? = try {
        Catalog.require(Catalog.CORNER_SMALL_WINDOW).prefs
            .filterIsInstance<PrefSpec.OrderedList>()
            .firstOrNull { it.key == ITEMS_KEY }
    } catch (_: Throwable) {
        null
    }

    /**
     * 「用户配过了」的门槛。
     *
     * 权威值是 Catalog 里那条 [PrefSpec.OrderedList] 的 `minCount`
     * （现在是 3，与 [MIN_TARGETS] 对齐）；**老键**那条 [PrefSpec.AppList] 的 `minCount`
     * 也认（老版本 Catalog 里存在过，按老键查找能让那种组合继续工作）；
     * 两者都查不到时用 [USER_ITEMS_MIN_FALLBACK]。
     *
     * 这就是「直接拼键、不依赖 Catalog 条目」的另一半好处：前后端可以各自先落地。
     */
    private fun userAppsMinCount(): Int {
        orderedListSpec()?.minCount?.coerceAtLeast(1)?.let { return it }
        val legacy = try {
            Catalog.require(Catalog.CORNER_SMALL_WINDOW).prefs
                .filterIsInstance<PrefSpec.AppList>()
                .firstOrNull { it.key == USER_APPS_KEY }
                ?.minCount
                ?.coerceAtLeast(1)
        } catch (_: Throwable) {
            null
        }
        return legacy ?: USER_ITEMS_MIN_FALLBACK
    }

    /** 诊断用的一句话。 */
    fun describe(result: Result): String = when (result) {
        is Result.Loading -> "loading"
        is Result.TooFew -> "too_few"
        is Result.Unavailable -> "unavailable(" + result.reason + ")"
        // list= 这一项是「用的是用户清单还是自动挑选」在日志里的落点：
        // 只有 recents=（扫描来源）时，读日志的人分不清清单是谁决定的
        is Result.Ready -> "ready(" + result.pool.size + " apps, recents=" + result.source +
            ", list=" + result.pool.listSource + ")"
    }

    private fun resolveHome(context: Context): String? = try {
        context.packageManager
            ?.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )
            ?.activityInfo?.packageName
    } catch (_: Throwable) {
        null
    }

    /**
     * 「最近使用」的两条来源。
     */
    internal object Recents {

        /**
         * UsageStats 路径。
         *
         * @return 按最近使用时间倒序的包名；不可用（没权限 / 没数据 / 抛异常）返回 null
         */
        fun fromUsageStats(context: Context, limit: Int): List<String>? = try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val from = now - USAGE_WINDOW_DAYS * 24L * 3600_000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, now)
            if (stats.isNullOrEmpty()) {
                // 空 = 拿不到（多半是权限/AppOps 被拦），不是「没有使用记录」
                null
            } else {
                stats.sortedByDescending { it.lastTimeUsed }
                    .mapNotNull { it.packageName }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(limit)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "UsageStats 不可用，退回内存采样：" + t.javaClass.simpleName)
            null
        }

        /**
         * 内存采样器。
         *
         * 读的是 [SystemUiEnv.ForegroundTracker] 的 250ms 轮询缓存（`@Volatile` 字段，
         * 读它没有 IPC），记下本次 SystemUI 生命周期内出现过的前台包。
         *
         * 与侧滑索引那边的 `RecentApps` 是同一个思路，代码在本目录里重新组织了一遍 ——
         * 那个类是 private 的，且那个目录有另一个协作者在改，不跨目录调用。
         *
         * 代价直说：**没有跨重启的历史**（UsageStats 那一级补的就是这一段）。
         */
        class Sampler(
            private val isLaunchable: (String) -> Boolean,
            private val onChanged: () -> Unit,
        ) {
            private val packages = ArrayDeque<String>(MAX + 1)

            @Volatile
            var current: List<String> = emptyList()
                private set

            @Volatile
            private var running = false

            private var thread: Thread? = null
            private var lastSeen: String? = null

            fun start() {
                if (running) return
                running = true
                thread = Thread({
                    while (running) {
                        try {
                            val pkg = SystemUiEnv.ForegroundTracker.foreground()
                            if (pkg != null && pkg != lastSeen) {
                                lastSeen = pkg
                                if (isLaunchable(pkg) && record(pkg)) onChanged()
                            }
                        } catch (_: Throwable) {
                            // 采样出错不改变任何状态：下一拍再来
                        }
                        try {
                            Thread.sleep(SAMPLE_MS)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                }, "fp-fan-recents").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                    start()
                }
            }

            fun stop() {
                running = false
                thread?.interrupt()
                thread = null
            }

            /** @return 列表是否真的变了（没变就不用惊动主线程重建扇形清单） */
            private fun record(pkg: String): Boolean = synchronized(packages) {
                if (packages.firstOrNull() == pkg) return false
                packages.remove(pkg)
                packages.addFirst(pkg)
                while (packages.size > MAX) packages.removeLast()
                current = ArrayList(packages)
                true
            }

            private companion object {
                /** 留住够填满扇形的条数即可，多留没有意义。 */
                const val MAX = 12
                const val SAMPLE_MS = 1000L
            }
        }
    }
}

