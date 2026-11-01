package io.github.kotoriinsky.tsuzuri.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.ShortcutEntries

/**
 * 「可导入的快捷方式」在**设置界面**这一侧的读法。
 *
 * ## 为什么这里是「读」而不是「查」
 *
 * 设置界面查不了别的应用的快捷方式，这是系统硬性限制（不是权限没申请）：
 * 本机 `services.jar` 反编译确认 `LauncherAppsService.ensureShortcutPermission`
 * 要求调用方有 `ACCESS_SHORTCUTS` 或是**当前桌面**（`ShortcutService.hasShortcutHostPermission`
 * → `getDefaultLauncher`），而模块的设置界面两者都不是，必然
 * `SecurityException("Caller can't access shortcut information")`。
 *
 * 所以目录由 **SystemUI 侧的 hook 枚举后发布到配置**里
 *（`feature/smallwindow/FanShortcuts.kt` 的 `Catalog.publish`，它那边的注释有完整取证），
 * 这里只是把那段字符串读出来给用户挑。
 *
 * ## 目录格式
 *
 * 每个元素 `包名 \u0001 快捷方式id \u0001 标签`。为什么不用更漂亮的结构：
 * 配置存储只有 `StringSet` 这一种集合类型可用（[Prefs] 只暴露原始类型），
 * 而控制字符 `\u0001` 不会出现在正常标签里，用它是**不引入新依赖的最短路径**。
 * 解析不出来（缺字段）的条目直接丢掉 —— 那是后端版本的格式，不是错误。
 *
 * 这是**单向的派生数据**：目录永远由后端写，前端只读，所以格式变更是后端单方面的事，
 * 前后端版本不一致的后果只是「列表少了几条」。
 */
sealed interface ShortcutCatalog {
    /** 后端还没发布（还没重启过系统界面 / 框架没连上）。 */
    data object Absent : ShortcutCatalog

    /** 读到了，可能是空的（这台设备确实没有发布任何快捷方式）。 */
    data class Ready(val items: List<ImportableShortcut>) : ShortcutCatalog
}

/** 一条可导入的快捷方式。 */
data class ImportableShortcut(
    val packageName: String,
    val id: String,
    val label: String,
) {
    /** 写进配置的稳定键：`包名/快捷方式id`（与 hook 侧 `FanTargets.shortcutKey` 同一规则）。 */
    val key: String get() = "$packageName/$id"

    /** 界面上的主标题：`标签`（所属应用名由界面另行补上）。 */
    val display: String get() = label.ifBlank { id }
}

/** 目录发布到哪个键。由特性键推出（`corner_small_window.shortcut_catalog`）。 */
fun shortcutCatalogKey(featureKey: String): String = Keys.pref(featureKey, "shortcut_catalog")

/** 图标包选在哪个键上（[io.github.kotoriinsky.tsuzuri.core.PrefSpec.AppList] 类型，集合里只放一个）。 */
fun iconPackKey(featureKey: String): String = Keys.pref(featureKey, "icon_pack")

/**
 * 从后端投递过来的目录里读（见 [ShortcutCatalogStore] 的类注释：为什么它不在配置里）。
 * **Composable 里调**（要订阅变化）。
 *
 * 读失败一律当「还没有」（规范 §9：读配置失败回退默认值），界面显示空态而不是崩。
 */
@Composable
fun readShortcutCatalog(): ShortcutCatalog {
    val context = LocalContext.current
    val raw = ShortcutCatalogStore.observe(context)
    return remember(raw) { parseCatalog(raw) }
}

/** 非 Composable 场景（诊断/日志）用的一次性读取。 */
fun readShortcutCatalogOnce(context: android.content.Context): ShortcutCatalog =
    parseCatalog(ShortcutCatalogStore.raw(context))

private fun parseCatalog(raw: Set<String>): ShortcutCatalog {
    if (raw.isEmpty()) return ShortcutCatalog.Absent
    val out = ArrayList<ImportableShortcut>(raw.size)
    for (s in raw) {
        val a = s.indexOf(FIELD)
        if (a <= 0) continue
        val b = s.indexOf(FIELD, a + 1)
        if (b <= a) continue
        val pkg = s.substring(0, a)
        val id = s.substring(a + 1, b)
        val label = s.substring(b + 1)
        if (pkg.isEmpty() || id.isEmpty()) continue
        out.add(ImportableShortcut(pkg, id, label))
    }
    return ShortcutCatalog.Ready(out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.display }))
}

/** 「已选的快捷方式」= 清单里的**快捷方式项**（`包名/快捷方式 id`）。 */
fun selectedShortcuts(apps: Set<String>): List<ImportableShortcut> = apps.mapNotNull { s ->
    // ⚠️ 必须按 Kind 过滤，不能只靠「含 `/`」。清单里还有第三类键 `link:<uri>`，
    // 它**也含 `/`**，按第一个 `/` 拆会得到包名 `link:weixin:` 与 id `/scanqrcode` ——
    // 于是这一列会冒出一行标题是 `/scanqrcode` 的垃圾。移除行为本身不受影响
    // （`it.key` 拼回去仍是原串），但这一列正是用户判断「我选了什么」的依据。
    if (ShortcutEntries.parse(s).kind != ShortcutEntries.Kind.SHORTCUT) return@mapNotNull null
    val i = s.indexOf('/')
    if (i <= 0 || i == s.length - 1) return@mapNotNull null
    val pkg = s.substring(0, i)
    val id = s.substring(i + 1)
    ImportableShortcut(pkg, id, id)
}.sortedBy { it.key }

/** 目录项内部的分隔符（控制字符）。与 hook 侧 `FanShortcuts.FIELD` 必须一致。 */
private const val FIELD = '\u0001'

/**
 * 图标包预览用哪个应用：用户的清单里**第一个「应用」项**（不是快捷方式）。
 *
 * 为什么用用户自己的清单而不是写死一个「微信」：清单是用户真正会看到的那些项，
 * 预览它们才回答得了「这个包覆盖了我在用的应用吗」。清单为空（= 自动挑选）时
 * 返回 null，界面退回用该图标包自己的启动图标做示意。
 *
 * 取的是**清单里的顺序**（用户自己排的），不是排序后的第一个：拖到自己第一位的那一项
 * 正是他最在意的那个，用别的一项去预览会让预览看起来「不像我的扇子」。
 */
fun previewTargetOf(items: List<String>): String? =
    items.firstOrNull { !it.contains('/') && it.isNotBlank() }

// ── 下面这些是给「导入快捷方式 / 单项图标」两页的读法（2026-09-14 加）──────────

/** 「用哪条清单」的模式键（`<特性>.apps_mode`）。与 hook 侧 `FanTargets.APPS_MODE_KEY` 同一个键。 */
fun appsModeKey(featureKey: String): String = Keys.pref(featureKey, "apps_mode")

/**
 * 读「用哪条清单」的模式，返回的必须是**真正生效的那个值**。
 *
 * 两条实现细节都不能省：
 *  1. **默认值取自 Catalog 里那条 `PrefSpec.Choice`**（与 hook 侧
 *     `FanTargets.configuredAppsMode()` 同一个来源）—— 界面自己写死一个默认值就会
 *     和实际行为漂移，出现「界面说自动、扇子用的是清单」这种最难解释的不一致；
 *  2. 用 `ConfigStore.observe()` 读（要订阅），否则用户在上一页切完模式回到这一页
 *     看到的是旧值。
 *
 * 读不到 Catalog 条目时退回 `auto`：猜错方向的代价是「用户以为在用清单、实际自动挑选」，
 * 而自动挑选是永远不会空的那个，失败方向是安全的（同 hook 侧的注释）。
 *
 * 为什么放在这个文件：「导入快捷方式」「单项图标」两页都要用它来决定「清单是否未启用」
 * 那条提示。`AppPickerScreen` 里有一份等价的实现，
 * 那个文件同一时刻由别人重写，所以这里不引用它。
 */
@Composable
fun readAppsMode(featureKey: String): String {
    val prefs = ConfigStore.observe()
    val modeKey = remember(featureKey) { appsModeKey(featureKey) }
    val fallback = remember(featureKey) {
        try {
            io.github.kotoriinsky.tsuzuri.core.Catalog.find(featureKey)?.prefs
                ?.filterIsInstance<io.github.kotoriinsky.tsuzuri.core.PrefSpec.Choice>()
                ?.firstOrNull { it.key == modeKey }
                ?.default
        } catch (_: Throwable) {
            null
        } ?: "auto"
    }
    // `?: "auto"` 不能省：rawString 的返回类型是 String?（它的 def 允许 null），
    // 而这一层的契约是「永远给一个能用的模式」
    return prefs.rawString(modeKey, fallback) ?: "auto"
}

/**
 * 手工添加用的：由「包名 + 快捷方式 id」拼出清单键（`包名/id`，与 [ImportableShortcut.key]
 * 同一格式、与勾选写进同一个键）。
 *
 * **只查格式，不查存在性**：设置界面读不到别的应用的快捷方式（见本文件顶部的取证），
 * 所以「这个 id 在这台机器上有没有」是**不可能**在这里回答的，只会被查一下却在别处
 * 假装验过更糟。id 里不能含 `/`（那是分隔符），含了就返回 null（拼出来的键会被
 * hook 侧当成两个字段拆错）。
 */
fun customShortcutKey(packageName: String, id: String): String? {
    val pkg = packageName.trim()
    val sid = id.trim()
    if (pkg.isEmpty() || sid.isEmpty()) return null
    if (pkg.contains('/') || sid.contains('/')) return null
    return "$pkg/$sid"
}


/**
 * 这个特性**是否真的声明**了 `apps_mode`。
 *
 * 为什么需要它：那些「清单不生效，当前按自动挑选」的告警只对扇形成立 ——
 * 它要求 `apps_mode=manual` 清单才算数。侧滑索引的收藏项**没有这个模式**：
 * 空清单 = 不显示这个槽，非空就生效。那里照抄扇形那句告警就是**假警告**
 * （用户会以为自己的收藏没生效）。
 *
 * 用「有没有声明」当判据、而不是按特性键写死，这样以后再有别的清单特性也自动正确。
 */
fun hasAppsMode(featureKey: String): Boolean = try {
    Catalog.find(featureKey)?.prefs?.any { it.key == appsModeKey(featureKey) } == true
} catch (_: Throwable) {
    // 拿不到 Catalog（理论上不会）时按「有模式」处理 = 保持旧行为，不新增假警告
    true
}

/**
 * 这个特性的清单**是否真的生效**。
 *
 * - 声明了 `apps_mode` 的（扇形）：要 `manual` 才算，
 * - 没声明的（侧滑索引的收藏项）：非空清单直接生效，没有「模式」这回事。
 *
 * 各页面把它当作原来那个 `manual` 用（`!manual` → `!listTakesEffect(...)`），
 * 于是「清单不生效」的告警只在真的不生效时才出现。
 */
fun listTakesEffect(featureKey: String, manual: Boolean): Boolean =
    manual || !hasAppsMode(featureKey)
