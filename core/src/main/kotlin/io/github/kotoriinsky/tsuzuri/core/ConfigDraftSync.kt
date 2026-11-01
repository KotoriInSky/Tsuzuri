package io.github.kotoriinsky.tsuzuri.core

/**
 * 本地草稿 → 真机配置的**同步计划**（纯函数：只吃「键 → 值」）。
 *
 * ## 为什么这必须是一个能被单测钉住的函数
 *
 * 前端在**框架没连上**时会把改动写进本地草稿（`draft_config`），等框架连上再整体搬过去。
 * 这条路上真的丢过改动，而且丢得完全没有声响 —— 老规则是「**远端已经有这个键就跳过**」：
 *
 * ```
 * 框架没连上 → 用户把开关打开 → 写进草稿
 * 框架连上   → 同步时发现远端"已有"这个键（值还是旧的 false）→ 跳过
 * 结果       → 界面上开关是开的，真机上永远是关的，没有任何日志
 * ```
 *
 * （`f.pa_widget_admission` 在真机上就是这样一对：草稿 `true`、远端 `false`。）
 *
 * 现在只有一条规则：**草稿里有的键，就是「没能写到真机的那些改动」，全部写过去。**
 *
 * ## 这条规则的前提，由调用方保证
 *
 * 草稿里不能留历史值，否则下一次绑定会用它盖掉更新的远端值。所以
 * **一次成功的同步之后必须清掉草稿里已写过去的键** —— [plan] 与「清草稿」是一件事的
 * 两半，改一个必须改另一个。这条约束没法写进类型里，所以它同时写在
 * `ConfigStore.syncDraft` 的注释里，那边是唯一的调用方。
 */
object ConfigDraftSync {
    /** 只存在本机草稿，表达离线删除；不得写到远端配置。 */
    const val DELETED_KEYS = "__draft_deleted"

    /** 一条要写到远端的键值。 */
    data class Write(val key: String, val value: Any)

    /**
     * 同步计划。
     *
     * [writes] 按 key 排序：这个顺序会出现在日志里，也会被测试逐项比对，
     * 而 `SharedPreferences.all` 的顺序是不保证的。
     */
    data class Plan(val writes: List<Write>, val deletions: Set<String> = emptySet()) {
        val keys: List<String> get() = (writes.map { it.key } + deletions).sorted()
    }

    /** `SharedPreferences` 能存的那几种类型。别的一律不搬（见 [plan]）。 */
    fun isSupported(value: Any): Boolean =
        value is Boolean || value is Float || value is Int || value is Long ||
            value is String || value is Set<*>

    /** 已与远端一致的草稿也必须清除，避免下次连接时用旧值覆盖新设置。 */
    fun acknowledgedKeys(draft: Map<String, Any?>, remoteValue: (String) -> Any?): Set<String> {
        val deleted = (draft[DELETED_KEYS] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
        return buildSet {
            for ((key, value) in draft) {
                if (key == Keys.CONFIG_VERSION || key == DELETED_KEYS || key in deleted) continue
                if (value != null && isSupported(value) && remoteValue(key) == value) add(key)
            }
            deleted.filterTo(this) {
                it != Keys.CONFIG_VERSION && it != DELETED_KEYS && remoteValue(it) == null
            }
        }
    }

    /**
     * 算出要写哪些键。
     *
     * @param draft 草稿里的全部键值（`SharedPreferences.all`）
     * @param remoteValue 读远端当前值（调用方给 `{ remote.all[it] }`）—— 只用来判「要不要写」
     * @return `null` = 没什么可写。调用方据此**整次跳过写入**：每写一次都会触发一次变更通知，
     *   而前端对通知的处置是「延迟自动重载」（重启 SystemUI）—— 为一个没变的值重启系统界面
     *   是不成比例的。
     */
    fun plan(draft: Map<String, Any?>, remoteValue: (String) -> Any?): Plan? {
        val writes = ArrayList<Write>(draft.size)
        val deleted = (draft[DELETED_KEYS] as? Set<*>)?.filterIsInstance<String>().orEmpty()
            .filter { it != Keys.CONFIG_VERSION && it != DELETED_KEYS }.toSet()
        for ((key, value) in draft) {
            // 版本号描述的是「它旁边那份数据是哪一版写的」，跨存储搬运等于替远端声明一个
            // 我们没验证过的版本 —— 后果是远端的迁移被跳过，而用户存在远端的老值会按新语义
            // 被读一遍（表现：窗口位置自己跳了）。这一条在 `ConfigStore` 里也有一份说明。
            if (key == Keys.CONFIG_VERSION || key == DELETED_KEYS || key in deleted) continue
            if (value == null) continue
            // 类型不认识就别搬。`SharedPreferences` 只认上面那六种，别的值搬过去只会抛异常，
            // 而抛在同步里就意味着**整批**改动都写不进去 —— 一个坏键连坐所有好键。
            if (!isSupported(value)) continue
            if (remoteValue(key) == value) continue
            writes.add(Write(key, value))
        }
        val removals = deleted.filter { remoteValue(it) != null }.toSet()
        if (writes.isEmpty() && removals.isEmpty()) return null
        return Plan(writes.sortedBy { it.key }, removals)
    }
}
