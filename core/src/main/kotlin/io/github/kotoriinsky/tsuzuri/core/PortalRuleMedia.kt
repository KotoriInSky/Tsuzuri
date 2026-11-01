package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P

object PortalRuleMedia {
    data class Selected(val source: PortalMediaSelection.Source, val live: Boolean, val item: String)
    fun pick(plans: List<P.Plan>, values: Map<String, Any?>,
        decodeJson: (String) -> Any? = { null }): Selected? {
        for (plan in plans) {
            val variables = LinkedHashMap<String, Any?>()
            fun value(read: P.Read): Any? {
                val raw = values[read.key]
                val accepted = when (read.codec) {
                    P.Codec.STRING_LIST -> (raw as? List<*>)?.takeIf { it.size <= 1024 && it.all { item -> item is String } }
                    P.Codec.NUMBER -> (raw as? Number) ?: (raw as? String)?.toLongOrNull()
                    P.Codec.TEXT, P.Codec.JSON -> (raw as? CharSequence)?.toString()?.takeIf { it.length <= 256 * 1024 }
                    P.Codec.BITMAP -> null
                }
                return PortalRuleValues.evaluate(accepted, read.steps, variables, plan.rule.transformers, decodeJson)
            }
            plan.variables.forEach { (name, read) -> variables[name] = value(read) }
            val outputs = plan.reads.map { (kind, read) -> kind to (value(read) as? String) }
            fun url(kind: P.Kind) = outputs.firstNotNullOfOrNull { (type, raw) ->
                raw?.takeIf { type == kind && PortalMediaContract.allowedUrl(it) }
            }
            val photo = url(P.Kind.PHOTO)
            // Flyme 的 gif 类型优先于实况：保留原 GIF，不跟随旁支视频或 JPEG 封面。
            val gif = outputs.any { it.first == P.Kind.TYPE && it.second.equals("gif", ignoreCase = true) }
            if (photo != null && gif) return Selected(PortalMediaSelection.Source(photo, null), false, plan.item)
            val videos = outputs.mapNotNull { (kind, raw) -> raw?.takeIf {
                kind in listOf(P.Kind.LIVE, P.Kind.VIDEO) &&
                    (PortalMediaContract.allowedUrl(it) || it.trimStart().startsWith('{'))
            } }.distinct()
            if (photo != null) return Selected(PortalMediaSelection.Source(photo, videos.firstOrNull(), videos.drop(1)),
                videos.isNotEmpty() && outputs.any { it.first == P.Kind.LIVE && it.second in videos }, plan.item)
            // 实况缺少照片时不能变成其它项的独立视频；播放器则可以返回视频及自己的封面。
            if (videos.isNotEmpty() && outputs.any { it.first == P.Kind.VIDEO && it.second in videos })
                return Selected(PortalMediaSelection.Source(url(P.Kind.COVER), videos.first(), videos.drop(1)), false, plan.item)
        }
        return null
    }
}
