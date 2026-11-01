package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P

/** 微博图片浏览器持有当前 OriginalPicItem，直接读取同项 PicInfo 的完整文件来源。 */
object PortalWeiboRules {
    const val PACKAGE = "com.sina.weibo"
    const val PREVIEW_PAGE = "com.sina.weibo.preview.MediaPreviewActivity"
    const val LIVE_VIEW = "com.sina.weibo.photoalbum.imageviewer.v2.item.livephoto.ImageViewerLivePhotoItemView"
    const val PHOTO_VIEW = "com.sina.weibo.photoalbum.imageviewer.v2.item.normal.ImageViewerNormalItemViewV2"
    private val views = listOf(LIVE_VIEW, PHOTO_VIEW,
        "com.sina.weibo.photoalbum.imageviewer.view.SplitDraggableImageView",
        "com.sina.weibo.photoalbum.imageviewer.view.gif.GifVideoView",
        "com.sina.weibo.photoalbum.imageviewer.view.gif.ImageViewerGifImageView",
        "com.sina.weibo.photoalbum.imageviewer.v2.item.livephoto.DragLivePhotoTouchFrameLayout",
        "com.sina.weibo.player.view.VideoTextureView", "android.widget.ImageView",
        "android.view.TextureView", "android.view.SurfaceView")
    val models = (0..10).flatMap { depth -> listOf("m.picInfo", "r.picInfo").map { "mParent.".repeat(depth) + it } }
    private fun read(model: String, path: String) = P.Read(PortalRuleSyntax.fields("$model.$path".split('.')), emptyList())
    private val plans = models.mapIndexed { index, model ->
        val rule = P.Rule("weiboPreview$index", PACKAGE, listOf(PREVIEW_PAGE), views, emptyList(),
            P.Node("\$current", "\$visibleView", emptyList()), emptyList())
        P.Plan(rule, model, listOf(P.Kind.TYPE to read(model, "type"),
            P.Kind.PHOTO to read(model, "original.url"), P.Kind.LIVE to read(model, "video")))
    }
    fun compiled(page: String) = if (page == PREVIEW_PAGE) plans else emptyList()
    fun forPage(page: String): Map<String, String>? {
        if (page != PREVIEW_PAGE) return null
        return views.associateWith { view ->
            val selected = when (view) {
                LIVE_VIEW -> plans.filter { it.item == "m.picInfo" }
                PHOTO_VIEW -> plans.filter { it.item == "r.picInfo" }
                else -> plans.filter { it.item.startsWith("mParent.") }
            }
            PortalMediaContract.bitmapFieldRule(selected.flatMap { it.fields } +
                PortalRuleSyntax.fields(listOf("mDrawable", "mBitmapState", "mBitmap"), "Object"))
        }
    }
}
