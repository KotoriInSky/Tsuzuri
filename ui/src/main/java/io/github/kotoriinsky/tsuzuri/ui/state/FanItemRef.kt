package io.github.kotoriinsky.tsuzuri.ui.state

import io.github.kotoriinsky.tsuzuri.core.LinkTargets
import io.github.kotoriinsky.tsuzuri.core.ShortcutEntries

/**
 * 清单里的**一项**在界面上的样子（标题、副标题、行首图标用哪个包）。
 *
 * 抽出来是因为「扇形里的项」与「单项图标」两页都要画同一份清单：
 * 两页各写一遍「快捷方式标签查不到就退回 id」这类决定，迟早只在其中一处被改，
 * 而那时两边都编译得过。
 *
 * ## 三类（应用 / 快捷方式 / 链接）
 *
 * 分类只有一处（[ShortcutEntries.parse]，与 hook 侧同一个实现），所以这一页与扇子里
 * **不可能**对同一串清单项给出两种解释。三类的显示名规则：
 *
 * | 类别 | 主标题 | 副标题（由调用方给，通常是 `ref.key`） |
 * | --- | --- | --- |
 * | 应用 | 已装应用的显示名（查不到退回包名） | `com.tencent.mm` |
 * | 快捷方式 | `应用名 · 标签`（标签查不到退回 id） | `com.tencent.mm/launch_type_scan_qrcode` |
 * | 链接 | [LinkTargets.fallbackLabel]（如 `scanqrcode`） | `link:weixin://scanqrcode` |
 *
 * 链接项的标题**刻意与扇子里用的是同一个函数**（`LinkTargets.fallbackLabel`）：
 * 两处各写一套取名规则的话，用户会在清单里看到 `scanqrcode`、在扇子里看到别的，
 * 而那看起来像「加错了项」。
 */
class FanItemRef(val key: String) {

    /** 一次解析，三处取值都从它来（分类只此一处，见类注释）。 */
    private val item: ShortcutEntries.Item = ShortcutEntries.parse(key)

    /** 快捷方式（`包名/id`）。 */
    val isShortcut: Boolean = item.kind == ShortcutEntries.Kind.SHORTCUT

    /**
     * 链接项（`link:<uri>`）。
     *
     * 界面**不因为它是链接就特殊对待**（照样能删、能拖、能换图标）——这个标记只用于
     * 决定标题，以及让调用方知道「行首图标没有可画的应用」。
     */
    val isLink: Boolean = item.kind == ShortcutEntries.Kind.LINK

    /** 行首图标画谁的：应用 = 自己，快捷方式 = 宿主应用，**链接 = 空串**（没有宿主）。 */
    val packageName: String = item.pkg

    /**
     * 链接项的 URI（去掉 `link:` 前缀）；非链接项为 null。
     *
     * 界面这一侧**不校验它**：校验只发生在写入那一刻（[LinkImportScreen]）与启动那一刻
     * （`FanLinks.launch`）。这里读到的可能是手改配置写进去的非法串 ——
     * 它照样要有一个能显示、能删掉的名字（[label] 用 `fallbackLabel`，永不为空）。
     */
    val linkUri: String? = item.linkUri

    /**
     * 主标题。
     *
     *  - 应用：已装应用的显示名；查不到（被卸载了）就退回包名；
     *  - 快捷方式：`应用名 · 标签`；标签查不到退回 id；
     *  - 链接：[LinkTargets.fallbackLabel]（宿主段 → scheme → 整串，永不为空串）。
     *
     * 一律**不隐藏查不到的项**：它照样是用户清单里的一条，照样能删能拖能换图标。
     */
    fun label(installed: Map<String, String>, shortcutLabels: Map<String, String>): String {
        // 链接项必须先判：它的 `packageName` 是空串，落到下面两支会得出空标题
        //（界面上就是一行看不见的东西，用户连删都点不到）
        if (isLink) return LinkTargets.fallbackLabel(linkUri.orEmpty())
        val app = installed[packageName] ?: packageName
        if (!isShortcut) return app
        val id = key.substringAfter('/', key)
        return app + " · " + (shortcutLabels[key] ?: id)
    }

    companion object {
        /** 清单 → 界面行（**保持清单里的顺序**：那就是扇形里的槽位顺序）。 */
        fun of(keys: List<String>): List<FanItemRef> = keys.map { FanItemRef(it) }

        /** 已装应用：包名 → 显示名。 */
        fun installedLabels(catalog: AppCatalog): Map<String, String> =
            (catalog as? AppCatalog.Ready)?.apps?.associate { it.packageName to it.label }.orEmpty()

        /** 快捷方式：清单键 → 标签（目录读不到时是空表，标签退回 id）。 */
        fun shortcutLabels(catalog: ShortcutCatalog): Map<String, String> = when (catalog) {
            is ShortcutCatalog.Ready -> catalog.items.associate { it.key to it.display }
            ShortcutCatalog.Absent -> emptyMap()
        }
    }
}
