package io.github.kotoriinsky.tsuzuri.core

object PortalMediaSelection {
    data class Source(val image: String?, val video: String?, val videoFallbacks: List<String> = emptyList()) {
        /** 候选只属于同一当前项；一个流失效时仍可尝试该项的其它流。 */
        val videoReferences get() = (listOfNotNull(video) + videoFallbacks).distinct()
    }
    fun isLive(source: Source, values: Map<String, String>): Boolean = source.video != null &&
        PortalMediaRules.imageModels.any { model ->
            PortalMediaRules.modelImageFields.any { values[PortalMediaRules.modelPath(model, it)] == source.image } &&
                PortalMediaRules.modelVideoFields.any { values[PortalMediaRules.modelPath(model, it)] == source.video }
        }
    fun pick(values: Map<String, String>): Source? {
        fun url(key: String) = values[key]?.takeIf(PortalMediaContract::allowedUrl)
        fun rawVideo(key: String) = values[key]?.takeIf {
            PortalMediaContract.allowedUrl(it) || (it.length <= 256 * 1024 && it.trimStart().startsWith('{'))
        }
        val models = PortalMediaRules.imageModels.map { model ->
            val videos = PortalMediaRules.modelVideoFields.mapNotNull { rawVideo(PortalMediaRules.modelPath(model, it)) }.distinct()
            Source(PortalMediaRules.modelImageFields.firstNotNullOfOrNull { url(PortalMediaRules.modelPath(model, it)) },
                videos.firstOrNull(), videos.drop(1))
        }
        // 优先最近的当前项，不能为了凑实况而跳到更远祖先的配对模型。
        models.firstOrNull { it.image != null }?.let { return it }
        for (prefix in PortalMediaRules.playerPrefixes) {
            val video = PortalMediaRules.playerVideoFields.firstNotNullOfOrNull { url(PortalMediaRules.playerPath(prefix, it)) }
            if (video != null) return Source(url(PortalMediaRules.playerPath(prefix, PortalMediaRules.playerCoverField)), video)
        }
        // WebViewCatcher 的标准图片来源适用于任意应用，不需要专项模型和版本规则。
        // content:// 只来自标准 imgUrl；模型与网页网络地址使用相同的通用校验。
        return (PortalMediaRules.imagePaths).firstNotNullOfOrNull(::url)?.let { Source(it, null) }
            ?: values["imgUrl"]?.let(PortalMediaContract::imageSource)?.let { Source(it, null) }
    }
}
