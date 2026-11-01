package io.github.kotoriinsky.tsuzuri.core

/**
 * 传送门编辑/分享的私有临时图片通道。
 *
 * 图片只存在模块应用的私有目录，通过 Provider 临时暴露给外部组件，
 * 不进入 MediaStore，因此不会出现在图库中。
 */
object PortalTempImageContract {
    const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.portaltemp"
    const val EDIT_SEGMENT = "edit"
    const val SHARE_SEGMENT = "share"
    const val PNG_SUFFIX = ".png"
    const val MEDIA_EDITOR_PACKAGE = "com.miui.mediaeditor"

    fun uri(segment: String, token: String): String =
        "content://$AUTHORITY/$segment/$token"

    fun isSafeSegment(segment: String): Boolean =
        segment == EDIT_SEGMENT || segment == SHARE_SEGMENT

    fun segmentOf(pathSegments: List<String>): String? {
        if (pathSegments.size !in 1..2) return null
        return pathSegments[0].takeIf(::isSafeSegment)
    }

    fun isDirectAccessCaller(segment: String, packages: List<String>): Boolean =
        segment == EDIT_SEGMENT && packages.any { it == MEDIA_EDITOR_PACKAGE }

    fun isTokenCapabilityAllowed(segment: String, write: Boolean): Boolean =
        segment == SHARE_SEGMENT && !write

    fun isSafeToken(token: String): Boolean {
        if (token.isEmpty() || token.length > 96 || !token.endsWith(PNG_SUFFIX)) return false
        return token.dropLast(PNG_SUFFIX.length).isNotEmpty() &&
            token.dropLast(PNG_SUFFIX.length).all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
