package io.github.kotoriinsky.tsuzuri.core

import java.util.Locale

/**
 * 网址查询参数的**归一化** —— 只用于算「内容身份」，不改实际打开地址。
 *
 * 为什么需要：身份是「同内容同身份」的依据，而分享链常带**每次分享都变**的跟踪参数。
 * 今天踩到的两个实例：知乎 `oia.zhihu.com/…?km_pst=…&share_code=…&utm_psn=…`（两个岛），
 * 以及小红书 `?xsec_token=…`。
 *
 * 清单来源：**ParseHub**（`z-mio/ParseHub`，**MIT**）各 parser 的
 * `__reserved_parameters__` / `__after_clean_parameters__` 声明，见本仓库
 * [THIRD-PARTY.md](../THIRD-PARTY.md)。**只抄清单（事实），实现自写。**
 *
 * ⚠️ 只影响**身份**：打开地址仍保留原样（`xsec_token` 这类可能本身就是访问凭据，
 * 直接删会让链接失效）。
 */
object UrlCleanup {

    /** 平台 → **只保留**这些参数，其余丢弃（`__reserved_parameters__`）。 */
    private val RESERVED: Map<String, Set<String>> = mapOf(
        "bilibili.com" to setOf("p"),
        "b23.tv" to setOf("p"),
        "douyin.com" to setOf("modal_id"),
        "iesdouyin.com" to setOf("modal_id"),
        "weibo.com" to setOf("fid"),
        "weibo.cn" to setOf("fid"),
        "youtube.com" to setOf("v", "list", "index"),
        "youtu.be" to setOf("v", "list", "index"),
    )

    /** 平台 → **丢弃**这些参数（`__after_clean_parameters__`）。 */
    private val REMOVED: Map<String, Set<String>> = mapOf(
        "coolapk.com" to setOf("shareKey", "s"),
        "xiaohongshu.com" to setOf("xsec_token"),
        "xhslink.com" to setOf("xsec_token"),
        "xhslink.cn" to setOf("xsec_token"),
        "pipix.com" to setOf("share_id"),
        // 每次分享都变的分享者标记（本仓库自己的补充）
        "spotify.com" to setOf("si"),
    )

    /** 平台无关的跟踪参数前缀。 */
    private const val GENERIC_PREFIX = "utm_"

    /**
     * 平台无关的营销 / 统计参数：mpchadwick/tracking-query-params-registry（**Unlicense**，公有领域）
     * 的 `_data/params.csv`（2026-10 版，168 项）去掉 `utm_*`（走前缀）后，再**剔除**容易与内容参数
     * 撞名的短名（`cid` `sid` `si` `pp` `kb` `adid` `campid` `toolid` `customid` `rtid` `nbt` `epik`
     * `dm_i` `pl_gc` `gQT` `gPromoCode` `adgroupid` `adtype` `channable`）—— 这些在别的站上可能就是内容号，
     * 删了会把不同内容合并成一个身份。比较时不分大小写。
     */
    private val GENERIC: Set<String> = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "gad_source", "gad_campaignid", "twclid",
        "yclid", "_ga", "_gl", "srsltid", "mc_cid", "mc_eid", "_bta_tid", "_bta_c", "trk_contact",
        "trk_msg", "trk_module", "trk_sid", "gdfms", "gdftrk", "gdffi", "_ke", "_kx",
        "redirect_log_mongo_id", "redirect_mongo_id", "sb_referer_host", "mkwid", "pcrid", "ef_id",
        "s_kwcid", "msclkid", "pk_campaign", "pk_kwd", "pk_keyword", "pk_source", "pk_medium", "pk_cid",
        "pk_content", "piwik_campaign", "piwik_kwd", "piwik_keyword", "mtm_campaign", "mtm_keyword",
        "mtm_source", "mtm_medium", "mtm_content", "mtm_cid", "mtm_group", "mtm_placement",
        "matomo_campaign", "matomo_keyword", "matomo_source", "matomo_medium", "matomo_content",
        "matomo_cid", "matomo_group", "matomo_placement", "hsa_cam", "hsa_grp", "hsa_mt", "hsa_src",
        "hsa_ad", "hsa_acc", "hsa_net", "hsa_kw", "hsa_tgt", "hsa_ver", "_hsenc", "_hsmi", "__hssc",
        "__hstc", "__hsfp", "hsctatracking", "_branch_match_id", "mkevt", "mkcid", "mkrid", "igshid",
        "sms_source", "sms_click", "sms_uph", "ttclid", "ndclid", "sccid", "irclickid", "vmcid",
        "tw_source", "tw_campaign", "tw_term", "tw_content", "tw_adid", "tw_kwdid", "gadid", "fbadid",
        "ttadid", "scadid", "padid", "klar_source", "klar_cpid", "klar_adid", "bg_kw", "bg_aid_k",
        "bg_aid_v", "bg_campaign", "bg_source", "bg_source_id", "cto_pld", "rtbhc", "nb_adtype", "nb_ap",
        "nb_expid_meta", "nb_fii", "nb_klid", "nb_kwd", "nb_li_ms", "nb_lp_ms", "nb_mi", "nb_mt", "nb_pc",
        "nb_pi", "nb_placement", "nb_ppi", "nb_ti", "fbc_id", "h_ad_id", "meta_placement",
        "meta_site_source", "cq_cmp", "cq_con", "cq_med", "cq_net", "cq_plac", "cq_plt", "cq_src",
        "cq_term",
        // ── 国内站点的分享 / 统计参数（本仓库自己的补充，登记表里没有）──
        "spm", "spm_id_from", "from_spmid", "vd_source", "share_source", "share_medium", "share_plat",
        "share_session_id", "share_tag", "share_from", "share_times", "unique_k", "wxshare_count",
        "sharer_shareid", "sharer_sharetime", "sharer_shareinfo", "sharer_shareinfo_first",
    )

    private fun isGenericTracking(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.startsWith(GENERIC_PREFIX) || n in GENERIC
    }

    /**
     * 归一化后的查询串（不含 `?`）；没有可归一的返回原串。
     *
     * 保留**顺序与原始编码**：只做增删，不做重排/重编码 —— 否则会引入不可预期的差异。
     */
    fun cleanQuery(host: String, rawQuery: String?): String? {
        if (rawQuery.isNullOrEmpty()) return rawQuery
        val h = host.lowercase(Locale.ROOT)
        val reserved = RESERVED.entries.firstOrNull { h == it.key || h.endsWith("." + it.key) }?.value
        val removed = REMOVED.entries.firstOrNull { h == it.key || h.endsWith("." + it.key) }?.value
        val parts = rawQuery.split('&')
        val kept = parts.filter { part ->
            val name = part.substringBefore('=')
            if (name.isEmpty()) return@filter false
            if (isGenericTracking(name)) return@filter false
            if (removed != null && name in removed) return@filter false
            if (reserved != null) return@filter name in reserved
            true
        }
        return if (kept.size == parts.size) rawQuery else kept.joinToString("&")
    }

    /**
     * 分享出去的链接：只去掉**平台无关**的跟踪参数（[GENERIC] 与 `utm_*`），其余原样保留。
     *
     * 与 [cleanQuery] 不同，这里改的是**真要发出去的地址**，所以不动平台清单
     * （`xsec_token` 这类可能是访问凭据）。没有可去的、或解析不了时原样返回。
     */
    fun stripTracking(url: String): String {
        val hashAt = url.indexOf('#')
        val beforeHash = if (hashAt >= 0) url.substring(0, hashAt) else url
        val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
        val q = beforeHash.indexOf('?')
        if (q < 0) return url
        val parts = beforeHash.substring(q + 1).split('&')
        val kept = parts.filter { it.isNotEmpty() && !isGenericTracking(it.substringBefore('=')) }
        if (kept.size == parts.size) return url
        val base = beforeHash.substring(0, q)
        return (if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")) + fragment
    }

    /** 便于测试：返回归一化后的完整 URL 里的查询部分。 */
    fun cleanUrlQuery(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        return cleanQuery(uri.host.orEmpty(), uri.rawQuery)
    }
}
