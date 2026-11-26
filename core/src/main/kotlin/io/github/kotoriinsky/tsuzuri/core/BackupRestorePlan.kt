package io.github.kotoriinsky.tsuzuri.core

/**
 * 备份与恢复的纯逻辑契约（跨版本兼容 + 全量校验 + 图标引用提取 + 差异统计）。
 *
 * 恢复先全量校验，再把完整计划交给存储层一次写入，坏尾项不能留下半份配置。
 *
 * ## 版本演进规则（每次改动配置键或新增持久化存储必读）
 *
 * 1. **新增模块配置键**：只要不以 `__` 开头（所有 `f.*` / `<feature>.*` 均满足），会自动进备份；
 *    但若改了既有键名或语义，必须同步推进 [Keys.CURRENT_CONFIG_VERSION] 并在 [ConfigMigration]
 *    补迁移 —— 恢复旧备份后会立即跑一次迁移。
 * 2. **新增内部瞬态键**：必须以 `__` 开头（如 `__wallpaper_request`、`__draft_deleted`），
 *    [isBackupConfigKey] 会自动跳过它，不污染备份也不在恢复时写回。
 * 3. **新增应用本地偏好或私有文件目录**（不在 RemotePreferences 里）：必须同步接入
 *    `ConfigBackup`（导出 / 解析 / 恢复 / 撤销）并在 `docs/02-coding-standards.md` §9.6 登记。
 */
object BackupRestorePlan {
    /** 当前备份文件结构版本（v1 = 仅模块键值，v2 = 含外观/行为与可选图标文件包）。 */
    const val CURRENT_VERSION = 2

    /** 备份 JSON 格式标识（v2 起写入，v1 无此字段但照常兼容）。 */
    const val FORMAT_ID = "tsuzuri-backup"

    const val MAX_ITEMS = 4096
    const val MAX_ICON_FILES = 256
    const val MAX_SINGLE_ICON_BYTES = 2 * 1024 * 1024
    const val MAX_TOTAL_ICON_BYTES = 32 * 1024 * 1024

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    data class Item(val key: String, val type: String, val value: Any?)

    /** 恢复前给用户看的影响统计（不含 `__cfg_version` 等内部键）。 */
    data class Diff(
        val overwrite: Int,
        val added: Int,
        val removed: Int,
        val unchanged: Int,
    )

    /**
     * 该键是否应当进入模块配置备份。
     *
     * 所有以 `__` 开头的内部键中，**只保留** [Keys.CONFIG_VERSION]（用来让未来版本恢复时
     * 正确运行 [ConfigMigration]）；其余如 `__wallpaper_request`、`__draft_deleted`
     * 均为瞬态/本地草稿同步键，不进备份也不在恢复时写回。
     */
    fun isBackupConfigKey(key: String): Boolean {
        if (key.isBlank() || key.length > 256 || key.any { it.isISOControl() }) return false
        if (key == Keys.CONFIG_VERSION) return true
        return !key.startsWith("__")
    }

    /**
     * 导出前过滤全量快照：剔除空值与非备份内部键、按键名排序，并在缺失时补写
     * [Keys.CONFIG_VERSION]，确保未来版本恢复这份备份时不会误按 v1 重跑迁移。
     */
    fun filterForExport(snapshot: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(snapshot.size + 1)
        for (key in snapshot.keys.sorted()) {
            if (!isBackupConfigKey(key)) continue
            val value = snapshot[key] ?: continue
            if (!ConfigDraftSync.isSupported(value)) continue
            out[key] = value
        }
        if (Keys.CONFIG_VERSION !in out) {
            out[Keys.CONFIG_VERSION] = Keys.CURRENT_CONFIG_VERSION
        }
        return out
    }

    fun validate(version: Int, items: List<Item>): Map<String, Any> {
        require(version in 1..CURRENT_VERSION) { "不认识的备份版本：$version" }
        require(items.size <= MAX_ITEMS) { "备份项目过多" }
        val values = linkedMapOf<String, Any>()
        val seenKeys = hashSetOf<String>()
        for ((index, item) in items.withIndex()) {
            require(item.key.isNotBlank() && item.key.length <= 256 && item.key.none { it.isISOControl() }) {
                "第 ${index + 1} 项键名无效"
            }
            require(seenKeys.add(item.key)) { "备份包含重复键：${item.key}" }
            val value = item.value
            val normalized: Any = when (item.type) {
                "Boolean" -> { require(value is Boolean); value }
                "String" -> { require(value is String); value }
                "Integer" -> {
                    require(value is Number)
                    val number = value.toDouble()
                    require(number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number == number.toInt().toDouble())
                    number.toInt()
                }
                "Long" -> {
                    require(value is Number)
                    when (value) {
                        is Long -> value
                        is Int -> value.toLong()
                        else -> {
                            val number = value.toDouble()
                            require(number.isFinite() && number >= -9223372036854775808.0 && number < 9223372036854775808.0 && number == number.toLong().toDouble())
                            number.toLong()
                        }
                    }
                }
                "Float" -> {
                    require(value is Number && value.toDouble().isFinite() && value.toFloat().isFinite())
                    value.toFloat()
                }
                "Set" -> {
                    require(value is List<*> && value.all { it is String })
                    value.filterIsInstance<String>().toSet()
                }
                else -> throw IllegalArgumentException("第 ${index + 1} 项类型不认识：${item.type}")
            }
            // 合法但属于瞬态内部键（例如旧版导出带进了 __wallpaper_request）：跳过不写入
            if (isBackupConfigKey(item.key)) {
                values[item.key] = normalized
            }
        }
        return values
    }

    fun restore(version: Int, items: List<Item>, write: (Map<String, Any>) -> Unit): Int {
        val values = validate(version, items)
        if (values.isNotEmpty()) write(values)
        return values.size
    }

    /** 与 `IconFileProvider.isSafeName` 同口径：防 Zip Slip 与非法字符。 */
    fun isSafeIconFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > 128) return false
        if (!name.endsWith(".png")) return false
        if (name == "." || name == "..") return false
        return name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }
    }

    /** PNG 魔数校验：挡掉伪装成 `.png` 的非图片文件。 */
    fun isPngBytes(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_MAGIC.size) return false
        for (i in PNG_MAGIC.indices) {
            if (bytes[i] != PNG_MAGIC[i]) return false
        }
        return true
    }

    /**
     * 从配置快照里提取所有被引用的自定义图标文件名（仅 `*.icon_overrides` 中的 `file` 项）。
     *
     * 导出时只打包这些被引用到的文件，不把历史遗留的孤儿图塞进备份包。
     */
    fun referencedIconFiles(values: Map<String, Any?>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((key, raw) in values) {
            if (!key.endsWith(".icon_overrides")) continue
            val entries = (raw as? Set<*>)?.filterIsInstance<String>() ?: continue
            for (entry in IconOverrideEntries.decode(entries).values) {
                if (entry.kind != IconOverrideEntries.KIND_FILE) continue
                val fileName = IconOverrideEntries.fileName(entry.value)
                if (isSafeIconFileName(fileName)) out.add(fileName)
            }
        }
        return out.sorted().toCollection(LinkedHashSet())
    }

    /**
     * 计算恢复对当前用户配置的影响。
     *
     * 排除 [Keys.CONFIG_VERSION] 与非备份 `__*` 键，让确认框上的数字直接对应用户的配置项。
     */
    fun diff(current: Map<String, Any?>, incoming: Map<String, Any>, replaceAll: Boolean): Diff {
        val now = current.filter { (k, v) -> k != Keys.CONFIG_VERSION && isBackupConfigKey(k) && v != null }
        val next = incoming.filterKeys { it != Keys.CONFIG_VERSION && isBackupConfigKey(it) }
        var overwrite = 0
        var added = 0
        var unchanged = 0
        for ((k, v) in next) {
            when {
                k !in now -> added++
                now[k] != v -> overwrite++
                else -> unchanged++
            }
        }
        val removed = if (replaceAll) now.keys.count { it !in next } else 0
        return Diff(overwrite = overwrite, added = added, removed = removed, unchanged = unchanged)
    }
}
