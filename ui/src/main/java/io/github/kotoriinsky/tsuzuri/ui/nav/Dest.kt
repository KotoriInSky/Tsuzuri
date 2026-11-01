package io.github.kotoriinsky.tsuzuri.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.kotoriinsky.tsuzuri.core.PrefSpec

/**
 * 目的地，同时是 miuix-nav 返回栈的键。
 *
 * 返回栈的根永远是 [Home]：它承载「首页 / 设置」两个 tab（横滑分页 + 底栏），
 * [Settings] 只是 tab 的标识，不会单独入栈。其余都是下钻页，由 NavDisplay 负责推入与返回动画。
 */
sealed interface Dest : top.yukonga.miuix.kmp.nav.core.NavKey {
    data object Home : Dest
    data object Settings : Dest
    data object About : Dest
    data object Credits : Dest

    /** 设置的二级页（外观 / 预览底图 / 数据与备份 / 诊断与日志），[section] 是 `SettingsSection.id`。 */
    data class SettingsPage(val section: String) : Dest

    /** 下钻页。 */
    data object Search : Dest
    data object Setup : Dest
    data object WidgetSettings : Dest
    data object Proactive : Dest
    data class FeatureDetail(val featureKey: String, val focusPrefKey: String? = null) : Dest

    /**
     * 特性详情里的一个参数分组（二级页）。
     *
     * 带的是**分组标识**而不是分组对象：分组是从 Catalog 现算出来的（`FeatureSpec.prefGroups`），
     * 存一个会过期的快照当返回栈的 key，就会出现「返回来时页面内容还是旧的」。
     * 标识本身是稳定的（Catalog 里写死的 `group = "..."`）。
     */
    data class FeatureSection(val featureKey: String, val group: String, val focusPrefKey: String? = null) : Dest

    /**
     * 一个**参数类别**页（触发 / 内容 / 外观 / 反馈 / 高级）。
     *
     * 与 [FeatureSection] 的分工：
     *  - 类别页是详情页的**入口**（一个类别里含多个实现分组，页内按组滚动）；
     *  - [FeatureSection] 保留给「搜索直达某一组」—— 搜索命中的是确切参数，
     *    再让用户落在类别页里自己找一遍是倒退。
     *
     * 带的是类别**标识**（`PrefCategory.id`，如 `appearance`）而不是枚举：返回栈是
     * 进程内数据，存字符串更稳，也能在旧版本遗留的返回栈上优雅失效（解析不到就丢弃）。
     */
    data class FeatureCategory(
        val featureKey: String,
        val category: String,
        val focusPrefKey: String? = null,
    ) : Dest
    /**
     * 扇形里的项 —— **清单的看/删/增/排序/换图标都在这一页**（应用与快捷方式混排）。
     *
     * 带的是那条 `PrefSpec.OrderedList` 声明本身：页面拿到它就能读能写、能显示标题，
     * 不需要再去 Catalog 里查第二遍（查第二遍就有两份可能漂移的事实）。
     * 它是 data class，等值成立，当返回栈的 key 没问题。
     */
    data class FanItems(val spec: PrefSpec.OrderedList) : Dest

    /**
     * 应用多选页（**往清单里加应用**这条子流程）。
     *
     * 它是 [FanItems] 的下级页，不再是清单本身的入口 —— 两百个应用的多选与拖拽排序
     * 挤一页会互相打架（一个要长按、一个要滚动）。
     */
    data class AppPicker(val spec: PrefSpec.OrderedList) : Dest

    /**
     * 「添加」：清单的**唯一**添加入口 —— 选应用后点选它的应用本身 / 快捷方式 /
     * 预置页面 / 对外开放的页面。替代清单页原来的四个入口；填链接、填类名两条仍可从页底进入。
     */
    data class AddItem(val spec: PrefSpec.OrderedList) : Dest

    /** 图标包选择页。许可页没有用户清单，可以直接传特性键与预览应用。 */
    data class IconPackPicker(
        val featureKey: String,
        val labelKey: String,
        val spec: PrefSpec.OrderedList? = null,
        val previewPackage: String? = null,
    ) : Dest {
        constructor(spec: PrefSpec.OrderedList) : this(spec.key.substringBeforeLast('.'), spec.labelKey, spec)
    }

    /** 导入快捷方式页（**往清单里加快捷方式**这条子流程）。 */
    data class ShortcutImport(val spec: PrefSpec.OrderedList) : Dest

    /**
     * 添加链接页（**往清单里加一条 URI** 这条子流程）。
     *
     * 与前两个「增」的子流程并列，但**不依赖宿主应用发布过什么**：用户直接填一段
     * `weixin://scanqrcode` 这样的链接就能用（见 `core/LinkTargets.kt` 的类注释）。
     * 它自己带 `padding` 就够了 —— 校验失败当场在页内报错，不需要导航回调。
     */
    data class LinkImport(val spec: PrefSpec.OrderedList) : Dest

    /**
     * 添加活动页（**往清单里加一条「包名/活动全名」**这条子流程）。
     *
     * 与 [AppPicker] / [ShortcutImport] / [LinkImport] 并列的第四类「增」。
     * 它独立成一页而不是并进 [LinkImport]：两者的**校验规则完全不同**
     * （链接只放行带 scheme 的隐式 intent，活动只放行「包名/类名」这一种形状），
     * 混在一页里会出现「填了链接却按活动规则报错」这种说不清的体验。
     *
     * 它自己带 `padding` 就够了 —— 校验失败当场在页内报错，不需要导航回调。
     */
    data class ActivityImport(val spec: PrefSpec.OrderedList) : Dest

    /**
     * 单项图标总览（列出现有项，给某一项单独指定图标）。
     *
     * [focusKey] 是**一点直达**的落点：清单页点行体 = 给这一项换图标，
     * 带着它的键过来，进页后那一行就地展开并滚动到可视区 —— 用户不再需要在总览里
     * 把刚才那一行重新找一遍。`null`（搜索等入口）= 不定位，从页首看起。
     */
    data class FanIconOverrides(
        val spec: PrefSpec.OrderedList,
        val focusKey: String? = null,
    ) : Dest

    /**
     * 应用内日志查看器：设置 → 高级进来。
     *
     * 展示 `UiActionLog` 的环形缓冲（App 侧动作：写盘/迁移/草稿同步/备份/失败降级），
     * 可导出成文本。hook 侧的事归 LSPosed 模块日志 —— 高级区里那条「打开 LSPosed 管理器」。
     */
    data object LogViewer : Dest

    /**
     * 应用多选（`PrefSpec.AppList`）。侧滑索引的「哪些包算桌面」「索引里显示哪些应用」用它。
     *
     * 为什么单独一条而不是复用 [AppPicker]：那个页的入参是 `OrderedList`（有序清单，
     * 收藏/扇形那种），而这里是**无序集合**语义 —— 同一个页面类型服务两种语义，
     * 会出现「排序在这个场景下没有意义」这种讲不清的状态。
     */
    data class AppListPicker(val spec: PrefSpec.AppList) : Dest

    /** 单应用的名字与图标覆盖。入参是同一特性里的 OrderedList（与 [FanIconOverrides] 同一条路）。 */
    data class SideIndexOverrides(val spec: PrefSpec.OrderedList, val focusKey: String? = null) : Dest

    /**
     * 从当前图标包里挑一个图标贴给某一项。
     *
     * [targetKey] / [targetLabel] 一起带过来（而不是只带 key 再回查一遍）：
     * 这一页要把「正在给谁挑图标」写在标题上，而回查需要再读一遍配置与目录。
     */
    data class IconPackDrawable(
        val spec: PrefSpec.OrderedList,
        val targetKey: String,
        val targetLabel: String,
    ) : Dest

    /**
     * 自定义口令规则的编辑页（导入 / 导出 / 清空 + 只读列表）。
     *
     * 带的是那条 `PrefSpec.Screen` 声明本身（与 [FanItems] 同一条纪律）：
     * 页面从它拿标题与存储键，不需要再去 Catalog 里查第二遍。
     */
    data class PasscodeRules(val spec: PrefSpec.Screen) : Dest

    /** 下钻页的真值表 —— 决定返回键和底栏是否出现。 */
    val isTopLevel: Boolean
        get() = this is Home || this is Settings
}

/**
 * 底部导航只剩**两段**：首页 / 设置。
 *
 * 首页 = 状态 + 全部功能（用户：「状态和功能合并起来」），功能不再单占 tab；
 * 诊断入口在设置页。
 */
/** [pageTitleI18n] 是该 tab 页可折叠大标题的文案（底栏标签可以更短）。 */
enum class Tab(val dest: Dest, val labelI18n: String, val pageTitleI18n: String, val icon: ImageVector) {
    HOME(Dest.Home, "nav.home", "home.title", Icons.Filled.Home),
    // 标签用「设置」而不是「更多」：这一页的标题、入口行、搜索结果都叫「设置」，
    // 底栏再写「更多」就是在同一屏里给同一件事起两个名字。
    MORE(Dest.Settings, "nav.settings", "nav.settings", Icons.Filled.Settings);

    companion object {
        fun of(dest: Dest): Tab? = entries.firstOrNull { it.dest == dest }
    }
}
