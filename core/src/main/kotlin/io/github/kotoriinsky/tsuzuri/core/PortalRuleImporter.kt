package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P

/** 读取外部规则的结构；原始规则仅用于本地转换，不作为产品资源分发。 */
object PortalRuleImporter {
    private val kinds = linkedMapOf("pic_type" to P.Kind.TYPE, "pic_url" to P.Kind.PHOTO,
        "live_photo_url" to P.Kind.LIVE, "video_url" to P.Kind.VIDEO, "video_cover" to P.Kind.COVER)
    fun importPages(pkg: String, pages: List<*>): P.ImportResult {
        val rules = ArrayList<P.Rule>()
        val issues = ArrayList<P.Issue>()
        fun issue(id: String, operation: String, reason: String) { issues.add(P.Issue(id, operation, reason)) }
        fun objectMap(value: Any?, id: String, key: String): Map<*, *>? =
            (value as? Map<*, *>) ?: run { issue(id, key, "对象格式不合法"); null }
        fun list(value: Any?, id: String, key: String): List<*> = when (value) {
            null -> emptyList<Any>()
            is List<*> -> value
            else -> { issue(id, key, "应为列表"); emptyList<Any>() }
        }
        fun strings(value: Any?, id: String, key: String): List<String> = list(value, id, key).mapNotNull {
            (it as? String) ?: run { issue(id, key, "列表含非字符串"); null }
        }
        fun unknown(map: Map<*, *>, allowed: Set<String>, id: String) {
            map.keys.filter { it !in allowed }.forEach { issue(id, it.toString(), "未支持的结构字段") }
        }
        fun operations(value: Any?, id: String): List<P.Step> {
            val map = objectMap(value, id, "collector") ?: return emptyList()
            unknown(map, setOf("params"), id)
            return strings(map["params"], id, "params").map(PortalRuleSyntax::parse)
        }
        fun node(value: Any?, id: String): P.Node? {
            val map = objectMap(value, id, "object") ?: return null
            unknown(map, setOf("name", "root", "type", "params", "visible", "cls"), id)
            val name = map["name"] as? String
            val root = map["root"] as? String
            if (name.isNullOrEmpty() || root.isNullOrEmpty()) { issue(id, "root", "缺少对象别名或根对象"); return null }
            val type = (map["type"] as? Number)?.toInt() ?: 0
            if (type !in 0..1) issue(id, "type", "未知对象类型")
            val params = strings(map["params"], id, "params")
            return P.Node(name, root, params.map { if (type == 1) P.Step("selector", it) else PortalRuleSyntax.parse(it) },
                type == 1, map["visible"] == true, map["cls"] as? String ?: "")
        }
        if (pages.size > 128) return P.ImportResult(emptyList(), listOf(P.Issue(pkg, "limit", "页面数量超出预算")))
        pages.forEachIndexed { pageIndex, value ->
            val pageId = "$pkg/page$pageIndex"
            val page = objectMap(value, pageId, "page") ?: return@forEachIndexed
            val pageIssueStart = issues.size
            unknown(page, setOf("name", "names", "all_images", "visible_images", "transformers"), pageId)
            val names = ((page["name"] as? String)?.let(::listOf).orEmpty() + strings(page["names"], pageId, "names")).distinct()
            if (names.isEmpty()) issue(pageId, "name", "页面名称为空")
            val transformerMap = page["transformers"]?.let { objectMap(it, pageId, "transformers") }.orEmpty()
            val transformers = transformerMap.mapNotNull { (key, body) ->
                if (key is String) key to operations(body, pageId)
                else { issue(pageId, "transformers", "转换器别名必须是字符串"); null }
            }.toMap()
            val pageIssues = issues.drop(pageIssueStart)
            for (groupName in listOf("visible_images", "all_images")) {
                list(page[groupName], pageId, groupName).forEachIndexed groupLoop@{ groupIndex, groupValue ->
                    val groupId = "$pageId/$groupName$groupIndex"
                    val start = issues.size
                    val group = objectMap(groupValue, groupId, "group") ?: return@groupLoop
                    val batch = groupName == "all_images"
                    unknown(group, setOf("objects", if (batch) "targets" else "target", "visible_view"), groupId)
                    val nodes = list(group["objects"], groupId, "objects").mapNotNull { node(it, groupId) }
                    val view = group["visible_view"]?.let { objectMap(it, groupId, "visible_view") }
                    if (view != null) unknown(view, setOf("params"), groupId)
                    val views = if (view == null) emptyList() else strings(view["params"], groupId, "visible_view")
                    val groupIssues = issues.drop(start)
                    val targets = if (batch) list(group["targets"], groupId, "targets") else listOf(group["target"])
                    targets.forEachIndexed targetLoop@{ targetIndex, targetValue ->
                        val id = "$groupId/target$targetIndex"
                        val targetStart = issues.size
                        val target = objectMap(targetValue, id, "target") ?: return@targetLoop
                        unknown(target, kinds.keys + "object", id)
                        val root = node(target["object"], id) ?: return@targetLoop
                        val outputs = kinds.flatMap { (key, kind) -> list(target[key], id, key).map { P.Output(kind, operations(it, id)) } }
                        rules.add(P.Rule(id, pkg, names, views, nodes, root, outputs, batch, transformers,
                            pageIssues + groupIssues + issues.drop(targetStart)))
                    }
                }
            }
        }
        return P.ImportResult(rules, issues)
    }
}
