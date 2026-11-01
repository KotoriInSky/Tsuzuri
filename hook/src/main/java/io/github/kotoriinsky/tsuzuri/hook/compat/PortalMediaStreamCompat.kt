package io.github.kotoriinsky.tsuzuri.hook.compat

import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract
import io.github.kotoriinsky.tsuzuri.core.PortalRuleValues
import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram

/** 小红书当前视频模型的流结构；未知结构返回空，由调用侧尝试同项直链。 */
object PortalMediaStreamCompat {
    fun urls(raw: String): List<String> {
        if (PortalMediaContract.allowedUrl(raw)) return listOf(raw)
        val json = PortalRuleRuntimeCompat.tree(raw) as? Map<*, *> ?: return emptyList()
        val media = json["media"] as? Map<*, *> ?: json
        fun field(name: String) = PortalRuleProgram.Step("field", name)
        // 先尝试兼容性较好的 H.264，同编码按尺寸排序；保留其它流供下载失败时回退。
        val codecs = listOf("h264", "h265").map { codec ->
            val array = PortalRuleValues.evaluate(media, listOf(field("stream"), field(codec))) as? List<*> ?: emptyList<Any>()
            array.filterIsInstance<Map<*, *>>()
                .filter { PortalMediaContract.allowedUrl(it["master_url"] as? String ?: "") }
                .sortedByDescending {
                    dimension(it["width"]) * dimension(it["height"])
                }
                .map { it["master_url"] as String }
        }
        // 限制重试数量时也保留两种编码，避免 H.264 的多个清晰度挤掉 H.265。
        return (codecs.mapNotNull { it.firstOrNull() } + codecs.flatMap { it.drop(1) }).distinct()
    }
    private fun dimension(value: Any?) = ((value as? Number)?.toLong() ?: (value as? String)?.toLongOrNull() ?: 0L).coerceIn(0, 100_000)
}
