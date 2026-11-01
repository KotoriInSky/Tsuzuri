package io.github.kotoriinsky.tsuzuri.core

/** 自行分析得到的候选字段路径；按页面与数据结构尝试，不靠单一版本号准入。 */
object PortalMediaRules {
    private val notePlans by lazy { PortalMediaRulePlans.create(NOTE_PAGE) }
    private val videoPlans by lazy { PortalMediaRulePlans.create(VIDEO_PAGE) }
    private val commentPlans by lazy { PortalMediaRulePlans.create(COMMENT_PAGE) }
    fun compiled(page: String): List<PortalRuleProgram.Plan> = when (page) {
        NOTE_PAGE -> notePlans
        VIDEO_PAGE -> videoPlans
        COMMENT_PAGE -> commentPlans
        else -> PortalDouyinRules.compiled(page).ifEmpty { PortalWeiboRules.compiled(page) }
    }
    const val NOTE_PAGE = "com.xingin.matrix.notedetail.NoteDetailActivity"
    const val VIDEO_PAGE = "com.xingin.matrix.detail.activity.DetailFeedActivity"
    const val COMMENT_PAGE = "com.xingin.comment.media.browser.CommentMediaBrowserActivity"
    const val BROWSER_IMAGE = "com.xingin.matrix.notedetail.imagecontent.imagegallery.browser.item.ImageBrowserLargeImageView"
    const val MATRIX_BROWSER_IMAGE = "com.xingin.matrix.imagebrowser.itembinder.largeimage.BrowserLargeImageView"
    const val COMMENT_BROWSER_IMAGE = "com.xingin.comment.media.browser.image.item.large.CommentBrowserLargeImageView"
    const val NOTE_IMAGE = "com.xingin.matrix.notedetail.imagecontent.imagegallery.util.NoteXYImageView"
    const val LIVE_VIEW = "com.xingin.matrix.notedetail.imagecontent.imagegallery.itembinder.livephoto.child.AsyncPhotoNoteLivePhotoItemView"
    const val PHOTO_VIEW = "com.xingin.matrix.notedetail.imagecontent.imagegallery.itembinder.photonote.child.AsyncPhotoNoteItemView"
    const val PLAYER_VIEW = "com.xingin.redplayer.ui.RedPlayerView"
    const val VIDEO_VIEW = "com.xingin.matrix.detail.item.video.widget.VideoViewV2"
    private fun field(names: String, leaf: String = "String") = PortalRuleSyntax.fields(names.split('.'), leaf)

    /** 读取同一当前项内的配对模型，不读取适配器全列表，避免错配到相邻照片。 */
    val imageModels: List<String> = (0..3).flatMap { depth ->
        val parent = "mParent.".repeat(depth)
        listOf("${parent}mLayoutParams.mViewHolder.c.w.imageBean",
            "${parent}mLayoutParams.mViewHolder.c.j._value.h.imageBean",
            // 真机实况也使用普通照片组件；其当前项在 u，绑定处理器在 k。
            "${parent}mLayoutParams.mViewHolder.c.u.imageBean",
            "${parent}mLayoutParams.mViewHolder.c.k._value.h.imageBean",
            // LinkerViewHolder.c 是事件流；三种控制器的当前项字段已在当前 APK 核对。
            "${parent}mLayoutParams.mViewHolder.a.controller.D.imageBean",
            "${parent}mLayoutParams.mViewHolder.a.controller.s.imageBean",
            "${parent}mLayoutParams.mViewHolder.a.controller.d.imageBean",
            // 真机普通照片 Holder 的控制器是 ad9.s；d 是绑定状态，d.a 才是 laa.b。
            "${parent}mLayoutParams.mViewHolder.a.controller.d.a.imageBean",
            // 图片浏览器 qc9.v.n 直接是当前 ImageBean，不再经过 laa.b。
            "${parent}mLayoutParams.mViewHolder.a.controller.n")
    }
    val modelImageFields = listOf("url_anim", "original", "url_size_large", "url")
    // livePhoto 是原始 Parcelable 模型；服务端提取其首段 JSON，不依赖播放后才有的 transient 缓存。
    val modelVideoFields = listOf("livePhotoUrl", "livePhoto", "videoInfoFromLivePhoto.videoInfoJson",
        "videoInfoFromLivePhoto.mediaV2", "videoInfoFromLivePhoto.url")
    /** 评论浏览器的双击监听持有当前控制器；不取列表第一项或邻项。 */
    val commentModels = listOf("w.a.h") + (0..4).flatMap { depth ->
        val parent = "mParent.".repeat(depth)
        listOf("${parent}mLayoutParams.mViewHolder.a.controller.h",
            "${parent}mLayoutParams.mViewHolder.a.controller.i",
            "${parent}mLayoutParams.mViewHolder.a.controller.m",
            "${parent}mLayoutParams.mViewHolder.a.controller.j")
    }
    /** CommentSinglePictureView.b 是当前 CommentPictureInfo。 */
    val commentPictureModels = (0..2).map { "mParent.".repeat(it) + "b" }
    val imagePaths = listOf(field("a", "Uri"), field("b.a"),
        field("mDraweeHolder.e.r.mSourceUri", "Uri"))
    // Controller.o 是 CloseableReference，不是 CloseableStaticBitmap：必须穿过
    // SharedReference.a，才能在 CloseableStaticBitmap.c 读到未按 View 裁剪的位图。
    val bitmapPaths = listOf(field("mDrawable.mBitmapState.mBitmap", "Object"),
        // 大图 View 的底图 Drawable 存在父类 n，不是 ImageView.mDrawable。
        field("n.mBitmapState.mBitmap", "Object"),
        field("e.b.a", "Object"),
        field("mDraweeHolder.e.o.b.a.c", "Object"))
    val playerPrefixes = (0..4).flatMap { depth ->
        val parent = "mParent.".repeat(depth)
        listOf("${parent}redPlayerDataSource", "${parent}redPlayer.B", "${parent}a.redPlayerDataSource")
    }
    val playerVideoFields = listOf("C.a", "B", "a")
    val playerCoverField = "d"
    fun modelPath(model: String, leaf: String): String = field("$model.$leaf")
    fun playerPath(prefix: String, leaf: String): String = field("$prefix.$leaf")

    fun forPage(pkg: String, version: Int, page: String): Map<String, String>? {
        if (pkg == PortalDouyinRules.PACKAGE && version > 0) return PortalDouyinRules.forPage(page)
        if (pkg == PortalWeiboRules.PACKAGE && version > 0) return PortalWeiboRules.forPage(page)
        if (pkg != "com.xingin.xhs" || version <= 0 || page !in listOf(NOTE_PAGE, VIDEO_PAGE, COMMENT_PAGE)) return null
        val views = listOf(NOTE_IMAGE, LIVE_VIEW, PHOTO_VIEW, PLAYER_VIEW, VIDEO_VIEW,
            BROWSER_IMAGE, MATRIX_BROWSER_IMAGE, COMMENT_BROWSER_IMAGE,
            "com.xingin.widgets.XYImageView",
            "com.xingin.comment.consumer.list.view.CommentSinglePictureView",
            "com.xingin.comment.media.browser.feed.item.image.v2.horizontal.item.livephoto.MediaFeedItemHorizontalLivePhotoView",
            "com.xingin.matrix.detail.item.video.player.VideoItemPlayerView",
            "com.xingin.matrix.detail.item.video.player.VideoItemLandscapePlayerView",
            "android.view.TextureView", "android.view.SurfaceView",
            "com.facebook.drawee.view.SimpleDraweeView")
        return views.associateWith { view ->
            val plans = compiled(page).filter { plan -> when (view) {
                BROWSER_IMAGE, MATRIX_BROWSER_IMAGE -> plan.item.endsWith("controller.n")
                COMMENT_BROWSER_IMAGE -> plan.item in commentModels
                "com.xingin.comment.consumer.list.view.CommentSinglePictureView" -> plan.item in commentPictureModels
                NOTE_IMAGE, LIVE_VIEW, PHOTO_VIEW -> plan.item in imageModels
                PLAYER_VIEW, VIDEO_VIEW,
                "com.xingin.matrix.detail.item.video.player.VideoItemPlayerView",
                "com.xingin.matrix.detail.item.video.player.VideoItemLandscapePlayerView" -> plan.item in playerPrefixes
                "com.xingin.comment.media.browser.feed.item.image.v2.horizontal.item.livephoto.MediaFeedItemHorizontalLivePhotoView" ->
                    plan.item in commentModels || plan.item in playerPrefixes
                "com.xingin.widgets.XYImageView" -> plan.item in imageModels
                else -> true
            } }
            PortalMediaContract.bitmapFieldRule(buildList {
                addAll(imagePaths)
                addAll(plans.flatMap { it.fields })
                // 直接读 Bitmap 字段，避免 ROM 的 Bitmap 指令提前返回并丢掉媒体 URL。
                addAll(bitmapPaths)
            })
        }
    }
}
