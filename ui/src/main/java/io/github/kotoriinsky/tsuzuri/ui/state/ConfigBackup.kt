package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import io.github.kotoriinsky.tsuzuri.core.BackupRestorePlan
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.ui.BuildConfig
import io.github.kotoriinsky.tsuzuri.ui.model.AccentMode
import io.github.kotoriinsky.tsuzuri.ui.model.Appearance
import io.github.kotoriinsky.tsuzuri.ui.model.DarkMode
import io.github.kotoriinsky.tsuzuri.ui.model.UiStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 完整备份与恢复（v2 `.zip` 归档包 + 100% 兼容 v1/v2 `.json`）。
 *
 * ## 归档包结构（`tsuzuri-backup-yyyyMMdd-HHmmss.zip`）
 *
 * ```
 * ├── backup.json            # version=2, appVersion, configVersion, source, exported,
 * │                          # items[], appearance{}, appSettings{}
 * └── icons/<name>.png       # 被当前配置引用的自定义图标 PNG（仅存在时打包）
 * ```
 *
 * ## 演进纪律
 *
 * 每次新增模块外持久化存储（新的本地 SharedPreferences 或 filesDir 子目录），
 * 必须同步补齐本类的导出与解析，并同步更新 `docs/02-coding-standards.md` §9.6 的清单。
 */
object ConfigBackup {

    private const val MANIFEST_ENTRY = "backup.json"
    private const val ICONS_PREFIX = "icons/"
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 36 * 1024 * 1024

    /** 解析完成的完整备份对象（已全量校验，随时可直接展示摘要并执行恢复）。 */
    data class Archive(
        val version: Int,
        val appVersion: String?,
        val configVersion: Int?,
        val source: String?,
        val exportedAt: String?,
        val config: Map<String, Any>,
        val appearance: Appearance?,
        val behavior: AppSettings.BehaviorSnapshot?,
        val icons: Map<String, ByteArray>,
        val isZipBundle: Boolean,
    ) {
        /** 扣除内部 `__cfg_version` 后的模块配置项数量。 */
        val userConfigCount: Int
            get() = config.keys.count { it != Keys.CONFIG_VERSION }

        val hasAppPrefs: Boolean
            get() = appearance != null || behavior != null
    }

    /** 一次完整导出的统计摘要（用于提示与日志）。 */
    data class ExportSummary(
        val configCount: Int,
        val iconCount: Int,
    )

    /** 恢复前的状态快照，用于整批撤销（模块配置 + 外观 + 行为）。 */
    data class RestoreCheckpoint(
        val config: ConfigStore.Checkpoint?,
        val appearance: Appearance?,
        val behavior: AppSettings.BehaviorSnapshot?,
    )

    /** 带时间戳的默认备份文件名，避免多次导出互相覆盖。 */
    fun defaultFileName(now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        return "tsuzuri-backup-$stamp.zip"
    }

    /**
     * 导出完整 `.zip` 备份包（模块配置 + 应用外观与行为 + 自定义图标 PNG）。
     *
     * 内部自动过滤 `__wallpaper_request` 等瞬态键并补齐 `__cfg_version`，
     * 自定义图标只打包当前配置实际引用到的安全 `.png` 文件。
     */
    fun exportZip(
        context: Context,
        output: OutputStream,
        source: String = context.packageName + " / " + Keys.GROUP,
        appVersion: String = BuildConfig.MODULE_VERSION,
        exportedAt: String = java.time.LocalDateTime.now().withNano(0).toString(),
    ): ExportSummary {
        val filtered = BackupRestorePlan.filterForExport(ConfigStore.snapshot())
        val appearance = AppearanceStore.current
        val behavior = AppSettings.snapshotBehavior()
        val iconNames = BackupRestorePlan.referencedIconFiles(filtered)
        val icons = IconOverridesStore.readIconFiles(context, iconNames)

        val json = exportJson(
            all = filtered,
            source = source,
            exportedAt = exportedAt,
            appVersion = appVersion,
            appearance = appearance,
            behavior = behavior,
        )

        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for ((name, bytes) in icons) {
                zip.putNextEntry(ZipEntry(ICONS_PREFIX + name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.finish()
        }

        val userCount = filtered.keys.count { it != Keys.CONFIG_VERSION }
        return ExportSummary(configCount = userCount, iconCount = icons.size)
    }

    /** 导出 JSON（v2 完整元数据；无外观/行为参数时仍产出兼容结构）。 */
    fun exportJson(
        all: Map<String, Any?>,
        source: String,
        exportedAt: String,
        appVersion: String? = BuildConfig.MODULE_VERSION,
        appearance: Appearance? = null,
        behavior: AppSettings.BehaviorSnapshot? = null,
    ): String {
        val filtered = BackupRestorePlan.filterForExport(all)
        val items = JSONArray()
        for ((key, value) in filtered) {
            val item = JSONObject()
            item.put("key", key)
            when (value) {
                is Boolean -> { item.put("type", "Boolean"); item.put("value", value) }
                is Int -> { item.put("type", "Integer"); item.put("value", value) }
                is Long -> { item.put("type", "Long"); item.put("value", value) }
                is Float -> { item.put("type", "Float"); item.put("value", value.toDouble()) }
                is Double -> { item.put("type", "Float"); item.put("value", value) }
                is String -> { item.put("type", "String"); item.put("value", value) }
                is Set<*> -> {
                    item.put("type", "Set")
                    item.put("value", JSONArray(value.map { it.toString() }.sorted()))
                }
                else -> continue
            }
            items.put(item)
        }
        val root = JSONObject()
        root.put("format", BackupRestorePlan.FORMAT_ID)
        root.put("version", BackupRestorePlan.CURRENT_VERSION)
        if (!appVersion.isNullOrBlank()) root.put("appVersion", appVersion)
        root.put("configVersion", (filtered[Keys.CONFIG_VERSION] as? Int) ?: Keys.CURRENT_CONFIG_VERSION)
        root.put("source", source)
        root.put("exported", exportedAt)
        root.put("items", items)
        if (appearance != null) root.put("appearance", encodeAppearance(appearance))
        if (behavior != null) root.put("appSettings", encodeBehavior(behavior))
        return root.toString(2)
    }

    /**
     * 自动识别输入流格式（`.zip` 完整包或 `.json` 旧/新版备份）并全量校验。
     *
     * 按文件头魔数判断，不依赖文件后缀或系统 MIME（微信/QQ 转发文件常丢 MIME）。
     */
    fun parseStream(input: InputStream): Archive {
        val raw = readBounded(input, MAX_ARCHIVE_BYTES)
        require(raw.isNotEmpty()) { "备份文件为空" }
        return if (isZipMagic(raw)) {
            parseZip(raw)
        } else {
            require(raw.size <= MAX_JSON_BYTES) { "备份文件过大" }
            parseJsonArchive(raw.toString(Charsets.UTF_8), icons = emptyMap(), isZipBundle = false)
        }
    }

    /** 兼容旧调用方：直接解析纯 JSON 中的模块配置键值。 */
    fun parse(json: String): Map<String, Any> =
        parseJsonArchive(json, icons = emptyMap(), isZipBundle = false).config

    /** 兼容旧调用方：解析并逐键写回。 */
    fun restore(json: String, write: (key: String, value: Any) -> Unit): Int {
        val values = parse(json)
        values.forEach { (key, value) -> write(key, value) }
        return values.size
    }

    private fun parseZip(raw: ByteArray): Archive {
        var manifestJson: String? = null
        val icons = LinkedHashMap<String, ByteArray>()
        var totalIconBytes = 0

        ZipInputStream(ByteArrayInputStream(raw)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                require(".." !in entryName.split('/')) { "备份包包含非法路径：$entryName" }
                if (!entry.isDirectory) {
                    when {
                        entryName == MANIFEST_ENTRY -> {
                            require(manifestJson == null) { "备份包包含重复的清单文件" }
                            val bytes = readBounded(zis, MAX_JSON_BYTES)
                            manifestJson = bytes.toString(Charsets.UTF_8)
                        }
                        entryName.startsWith(ICONS_PREFIX) -> {
                            val iconName = entryName.removePrefix(ICONS_PREFIX)
                            if (iconName.isNotEmpty()) {
                                require(BackupRestorePlan.isSafeIconFileName(iconName)) { "备份包包含非法图标名：$iconName" }
                                require(icons.size < BackupRestorePlan.MAX_ICON_FILES) { "备份包内图标数量过多" }
                                val bytes = readBounded(zis, BackupRestorePlan.MAX_SINGLE_ICON_BYTES)
                                totalIconBytes += bytes.size
                                require(totalIconBytes <= BackupRestorePlan.MAX_TOTAL_ICON_BYTES) { "备份包内图标总大小超限" }
                                require(BackupRestorePlan.isPngBytes(bytes)) { "图标文件格式无效：$iconName" }
                                icons[iconName] = bytes
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val json = requireNotNull(manifestJson) { "备份包缺少 $MANIFEST_ENTRY" }
        return parseJsonArchive(json, icons = icons, isZipBundle = true)
    }

    private fun parseJsonArchive(
        json: String,
        icons: Map<String, ByteArray>,
        isZipBundle: Boolean,
    ): Archive {
        require(json.length <= MAX_JSON_BYTES) { "备份文件过大" }
        val root = JSONObject(json)
        val version = root.getInt("version")
        val items = root.getJSONArray("items")
        val raw = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val value = item.get("value")
            BackupRestorePlan.Item(
                item.getString("key"),
                item.getString("type"),
                if (value is JSONArray) (0 until value.length()).map(value::get) else value,
            )
        }
        val config = BackupRestorePlan.validate(version, raw)
        val appearance = root.optJSONObject("appearance")?.let(::decodeAppearance)
        val behavior = root.optJSONObject("appSettings")?.let(::decodeBehavior)
        val configVersion = root.optInt("configVersion", -1).takeIf { it > 0 }
            ?: (config[Keys.CONFIG_VERSION] as? Int)
        return Archive(
            version = version,
            appVersion = root.optString("appVersion").ifBlank { null },
            configVersion = configVersion,
            source = root.optString("source").ifBlank { null },
            exportedAt = root.optString("exported").ifBlank { null },
            config = config,
            appearance = appearance,
            behavior = behavior,
            icons = icons,
            isZipBundle = isZipBundle,
        )
    }

    private fun encodeAppearance(a: Appearance): JSONObject = JSONObject()
        .put(Appearance.KEY_STYLE, a.style.name)
        .put(Appearance.KEY_DARK, a.dark.name)
        .put(Appearance.KEY_ACCENT, a.accent.name)
        .put(Appearance.KEY_CUSTOM, a.customAccentArgb)
        .put(Appearance.KEY_BAR_BLUR, a.barBlur)
        .put(Appearance.KEY_FLOATING_BAR, a.floatingBar)
        .put(Appearance.KEY_GLASS_BARS, a.glassBars)

    private fun decodeAppearance(o: JSONObject): Appearance {
        val base = Appearance()
        return base.copy(
            style = enumOr(o.optString(Appearance.KEY_STYLE, ""), base.style),
            dark = enumOr(o.optString(Appearance.KEY_DARK, ""), base.dark),
            accent = enumOr(o.optString(Appearance.KEY_ACCENT, ""), base.accent),
            customAccentArgb = if (o.has(Appearance.KEY_CUSTOM)) o.optInt(Appearance.KEY_CUSTOM, base.customAccentArgb) else base.customAccentArgb,
            barBlur = o.optBoolean(Appearance.KEY_BAR_BLUR, base.barBlur),
            floatingBar = o.optBoolean(Appearance.KEY_FLOATING_BAR, base.floatingBar),
            glassBars = o.optBoolean(Appearance.KEY_GLASS_BARS, base.glassBars),
        )
    }

    private fun encodeBehavior(b: AppSettings.BehaviorSnapshot): JSONObject = JSONObject()
        .put("hapticFeedback", b.hapticFeedback)
        .put("keepSearchHistory", b.keepSearchHistory)
        .put("autoReload", b.autoReload)
        .put("previewWallpaper", b.previewWallpaper)

    private fun decodeBehavior(o: JSONObject): AppSettings.BehaviorSnapshot = AppSettings.BehaviorSnapshot(
        hapticFeedback = o.optBoolean("hapticFeedback", true),
        keepSearchHistory = o.optBoolean("keepSearchHistory", false),
        autoReload = o.optBoolean("autoReload", false),
        previewWallpaper = o.optBoolean("previewWallpaper", true),
    )

    private inline fun <reified T : Enum<T>> enumOr(raw: String, fallback: T): T =
        if (raw.isBlank()) fallback else runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)

    private fun isZipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            require(total <= maxBytes) { "备份文件过大" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
