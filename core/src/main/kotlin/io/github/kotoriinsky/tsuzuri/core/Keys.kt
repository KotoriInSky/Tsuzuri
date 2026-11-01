package io.github.kotoriinsky.tsuzuri.core

/**
 * 配置键的命名规则，全项目只有这一处定义。
 *
 * 键名是**跨进程契约**：前端写、后端读，两边版本可能不一致（用户可能只更新了 APK
 * 没重启 SystemUI，或者反过来）。所以键名一旦发布就不能改语义，要改就换新键 + 迁移。
 */
object Keys {

    /**
     * RemotePreferences 的分组名。前端 `getRemotePreferences(GROUP)`，后端同名。
     *
     * 历史：0.1.x 时是 `"flymeport"`；包名迁到 io.github.kotoriinsky.tsuzuri 时同步改为
     * `"tsuzuri"`。分组跟着模块走（存储键 = 模块包名 + 分组名），所以改名后的首次读取
     * 是空存储 —— 旧配置靠「导出/恢复」通道搬过来（`tools/migrate-config.py` 导出，
     * 设置页恢复写入），键名与值格式一个都没变，搬过来即可用。
     */
    const val GROUP = "tsuzuri"

    /** 配置结构版本，见 [Prefs.configVersion]。 */
    const val CONFIG_VERSION = "__cfg_version"

    /**
     * 当前配置结构版本。加/改键时 +1，并在前端做迁移（见 [ConfigMigration]）。
     *
     * ## 历史
     *
     * - **1**：初版。
     * - **2**（2026-09-17）：`window_x_percent` / `window_y_percent` 的语义从
     *   「窗口左/上边缘的位置」改成「窗口**中心**的位置」。键名没变、类型没变，
     *   变的是**同一个数读出来是什么意思** —— 这正是版本号存在的理由：
     *   不迁移的话，老用户存的 `15` 会从「左边缘 15%」变成「中心在 15%」，
     *   窗口会**跳到一个他从没设过的位置**，而且没有任何报错。
     * - **3**（2026-09-17，同日）：同一对参数再从「中心的**绝对位置**」改成
     *   「中心在**可移动区间**里的比例」。v2 的问题是**行程是死的**：窗口占 80% 屏宽时
     *   水平只有 20% 屏宽的自由行程，而量程是 0–100% —— `x ∈ [0,40]` 全被夹成贴左、
     *   `x ∈ [60,100]` 全被夹成贴右，**80% 的行程拖了不动**，看起来就是「这个参数坏了」。
     *   改完 50 仍然是居中，但整段量程一一对应到真实位移。
     * - **4**（2026-09-19）：小窗摆放的**总开关换了形态** —— `window_custom`（布尔）
     *   退役，改成一个三选一的 `window_preset`（`system` / `flyme` / `custom`）。
     *   这一版与前两版有两处不同，都要留意：
     *   1. **键名变了**（`window_custom` → `window_preset`）。旧键不再被任何代码读取，
     *      所以不迁移的后果是「**换回出厂默认**」而不是「位置跳一下」——
     *      对当年把开关关掉（`false`，想用 MIUI 摆放）的那批用户，就是
     *      「什么都没动，窗口却不再归系统管了」，同样没有任何报错。
     *   2. 类型也变了（`Boolean` → `String`）。两个键**不同名**所以不会互相污染，
     *      迁移是「读旧键 → 写新键」，旧键留着不删（可回滚：退版本后旧键还在）。
     *
     * - **5**（2026-10-21）：参数精简—— 扇形 9 个参数下线（删已存值、默认值接管）、
     *   4 个动画参数合成「展开动画」预设、6 个滑杆收窄（越界存值夹回）；侧滑「振动反馈」并入
     *   「振动强度」、「背景模糊」并入「背景」（无 / 模糊 / 玻璃）、「气泡淡入」下线。
     *
     * ⚠️ 前两版是**语义**变化（键名与类型都没变），所以**必须**靠版本号 + 迁移兜住：
     * 只改公式不迁移，用户存在配置里的数会被按新语义重新解释一遍，窗口自己跳位置。
     */
    const val CURRENT_CONFIG_VERSION = 5

    /** 特性总开关：`f.<featureKey>`。 */
    fun enabled(featureKey: String) = "f.$featureKey"

    /** 特性参数：`<featureKey>.<prefKey>`，写进 [PrefSpec.key] 的就是它。 */
    fun pref(featureKey: String, prefKey: String) = "$featureKey.$prefKey"

    /**
     * **有序清单的老键**（键后缀）。理由见 [io.github.kotoriinsky.tsuzuri.core.OrderedItems] 的类注释：
     * 老键装的是无序 `StringSet`，换类型必须换键，而**读取侧（hook）与迁移侧（设置界面）
     * 都要读它** —— 两边各写一遍字面量，改了一边没改另一边，表现是「用户清单凭空消失」，
     * 且编译、日志、装机全正常。所以只留这一处事实。
     */
    const val LEGACY_ITEMS_PREF = "apps"

    /** 老清单的完整键。**只读、只用于迁移**（`OrderedItems.migrateFromUnordered`）。 */
    fun legacyItems(featureKey: String) = pref(featureKey, LEGACY_ITEMS_PREF)
}
