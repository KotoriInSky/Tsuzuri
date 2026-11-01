package io.github.kotoriinsky.tsuzuri.ui.state

import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置的**文件级**备份与恢复。
 *
 * ## 为什么有这个（而不只是「复制配置」）
 *
 * 包名迁移（`com.flymeport.hyperos4` → `io.github.kotoriinsky.tsuzuri`）之后，
 * LSPosed 远端配置的存储键 = 模块包名 + 分组名，老用户的全部开关留在旧存储里
 * 读不到。迁移路径定的是「位置迁移」：键名与值格式零改动，用
 * `tools/migrate-config.py`（root 读 lspd 数据库）导出 JSON，再由**设置页的
 * 「恢复配置」**把这份 JSON 逐键写进新分组 —— 本对象就是那半条链路的 App 侧。
 * 「导出」用同一个格式，让没有 root 的用户也能靠 SAF 文件互拷完成备份/搬家。
 *
 * ## 格式契约（与 migrate-config.py 互为镜像，改一处必须同步另一处）
 *
 * ```
 * {
 *   "version": 1,                       // 文件结构版本
 *   "source": "io.github.kotoriinsky.tsuzuri / tsuzuri",
 *   "exported": "2026-09-18T…",
 *   "items": [ {"key","type","value"} ] // type: Boolean|Integer|Long|Float|String|Set
 * }
 * ```
 *
 * ## 为什么 type 是字符串标注而不是猜
 *
 * SharedPreferences 的 `all` 里 Int/Long/Float 都是 Number，JSON 解析回来又全是
 * Double/Integer/Long 之一 —— **不靠原 type 标注就恢复不出原类型**：
 * 把 `Float` 写成 Int，滑杆读值那侧的浮点语义就断了（`rawFloat` 拿到 8 而不是 8.0）。
 */
object ConfigBackup {

    /** 文件结构版本。结构变化时 +1，恢复时拒收不认识的版本。 */
    private const val VERSION = 1

    /** 导出 JSON。`all` 是配置存储的全量键值（`SharedPreferences.all`）。 */
    fun exportJson(all: Map<String, Any?>, source: String, exportedAt: String): String {
        val items = JSONArray()
        for ((key, value) in all) {
            val item = JSONObject()
            item.put("key", key)
            when (value) {
                null -> continue // 空键不进备份：恢复侧写不回一个「不存在」
                is Boolean -> { item.put("type", "Boolean"); item.put("value", value) }
                is Int -> { item.put("type", "Integer"); item.put("value", value) }
                is Long -> { item.put("type", "Long"); item.put("value", value) }
                is Float -> { item.put("type", "Float"); item.put("value", value.toDouble()) }
                is Double -> { item.put("type", "Float"); item.put("value", value) }
                is String -> { item.put("type", "String"); item.put("value", value) }
                is Set<*> -> {
                    item.put("type", "Set")
                    item.put("value", JSONArray(value.map { it.toString() }))
                }
                // 不认识的类型跳过：备份少一项好过整个导出失败
                else -> continue
            }
            items.put(item)
        }
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("source", source)
        root.put("exported", exportedAt)
        root.put("items", items)
        return root.toString(2)
    }

    /**
     * 解析并逐键写回。返回恢复的项数。
     *
     * [write] 由调用方提供（写进 [ConfigStore] 走它统一的降级/自动重载路径），
     * 本类不持有存储 —— 它只负责「文件 ↔ 类型正确的值」这一段纯转换。
     *
     * @throws IllegalArgumentException 版本不认识、结构缺失或单条类型不对时。
     *         调用方（设置页）就地 toast，不让用户对一个静默的按钮猜原因。
     */
    fun parse(json: String): Map<String, Any> {
        require(json.length <= 2_000_000) { "备份文件过大" }
        val root = JSONObject(json)
        val version = root.getInt("version")
        val items = root.getJSONArray("items")
        val raw = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val value = item.get("value")
            io.github.kotoriinsky.tsuzuri.core.BackupRestorePlan.Item(
                item.getString("key"), item.getString("type"),
                if (value is JSONArray) (0 until value.length()).map(value::get) else value,
            )
        }
        return io.github.kotoriinsky.tsuzuri.core.BackupRestorePlan.validate(version, raw)
    }

    fun restore(json: String, write: (key: String, value: Any) -> Unit): Int {
        val values = parse(json)
        values.forEach { (key, value) -> write(key, value) }
        return values.size
    }
}
