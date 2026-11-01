package io.github.kotoriinsky.tsuzuri.ui.search

import android.content.Context
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.FeatureSpec
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.SearchAliases
import io.github.kotoriinsky.tsuzuri.core.prefGroups
import io.github.kotoriinsky.tsuzuri.core.settingsFeatures
import io.github.kotoriinsky.tsuzuri.ui.fpText

/**
 * 一次搜索命中。
 *
 * [pref] 为 `null` 表示命中的是**特性本身**（标题或摘要），不是某个具体参数 ——
 * 那时跳转落到特性详情页；否则落到参数所在的分组页。
 */
data class ParamHit(
    val feature: FeatureSpec,
    val group: String?,
    val pref: PrefSpec?,
)

/**
 * 在 Catalog 上做**全局**参数搜索。
 *
 * 为什么是全局而不是「某个特性内搜索」：用户来搜的时候往往并不确定这个参数
 * 属于哪个特性。限定在当前特性里搜，搜不到就等于没有 —— 而真相只是「它在别处」。
 * （LSPosed 管理器与 HyperCeiler 也都把搜索放在功能列表这一层。）
 *
 * 为什么在 ui 层而不在 core：匹配要比对**界面上显示的那句话**，而文案在
 * `strings.xml` 里，只有 Android 侧拿得到；`core` 是纯 Kotlin，碰不到资源。
 * 能放进 core 的只有那张别名表（[SearchAliases]），它已被单测钉住。
 */
object CatalogSearch {

    /**
     * @return 命中列表，顺序 = 特性声明顺序 → 分组顺序 → 参数顺序。
     *         空白查询返回空表（调用方据此决定画普通列表还是搜索结果）。
     */
    fun search(
        context: Context,
        query: String,
        features: List<FeatureSpec> = Catalog.settingsFeatures,
    ): List<ParamHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val out = ArrayList<ParamHit>()
        // 第一轮：参数名 / 参数说明命中
        for (feature in features) {
            for (group in feature.prefGroups()) {
                for (pref in group.prefs) {
                    val label = fpText(context, pref.labelKey)
                    val hint = pref.hintKey?.let { fpText(context, it) }.orEmpty()
                    if (SearchAliases.matches(label, q) || SearchAliases.matches(hint, q)) {
                        out.add(ParamHit(feature, group.id, pref))
                    }
                }
            }
        }
        // 第二轮兜底：一个参数都没命中时，再看是不是在搜**特性名**
        // （否则搜「传送门」会零结果 —— 它下面每一项参数名里都没有这三个字）
        if (out.isEmpty()) {
            for (feature in features) {
                val title = fpText(context, feature.titleI18n)
                val summary = fpText(context, feature.summaryI18n)
                if (SearchAliases.matches(title, q) || SearchAliases.matches(summary, q)) {
                    out.add(ParamHit(feature, null, null))
                }
            }
        }
        return out
    }
}
