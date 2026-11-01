package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P

/** 将当前照片项绑定到原装字段通道，保留 Flyme 的照片/实况 URL 列表选择顺序。 */
object PortalDouyinRules {
    const val PACKAGE = "com.ss.android.ugc.aweme"
    const val PAGER = "com.bytedance.android.horizon.view.HorizonViewPager"
    const val COMMENT_IMAGE = "com.ss.android.ugc.aweme.comment.commentfeed.view.image.CommentPhotoDraweeView"
    // 31.7.2 评论实况大图播放面（VideoPinchViewContainer 的子控件）；原装 extractor 只按精确类名匹配，混淆名随版本会变。
    const val LIVE_SURFACE = "X.S3q"
    const val LIST_IMAGE = "com.ss.android.ugc.aweme.base.ui.RemoteImageView"
    val pages = setOf("com.bytedance.ies.ugc.aweme.photos.detail.flow.page.FlowPageActivity",
        "com.ss.android.ugc.aweme.detail.ultra.ui.UltraDetailActivity",
        "com.ss.android.ugc.aweme.detail.ui.DetailActivity", "com.ss.android.ugc.aweme.splash.SplashActivity")
    val views = listOf(PAGER, "com.bytedance.lighten.loader.SmartImageView",
        "com.bytedance.ies.ugc.aweme.photos.ui.scale.assemble.AssembleScaleView",
        "com.ss.android.ugc.aweme.feed.widget.CoverImageView",
        "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView",
        "android.view.TextureView", "android.view.SurfaceView", COMMENT_IMAGE, LIVE_SURFACE, LIST_IMAGE)

    // HandyViewPager.d 是 adapter；primary 是 setPrimaryItem 维护的当前 Holder，f 是绑定项。
    // 不传 adapter 的对象列表，避免 Parcelable/Serializable 跨应用模型导致采集进程崩溃。
    private val holders = (0..8).map { "mParent.".repeat(it) + "d.primary" }
    val models = holders.map { "$it.f.urlStruct" }
    val commentModels = holders.map { "$it.f.aweme.commentFeedOuterComment.imageList" }
    const val OLD_COMMENT_MODEL = "LLZZZZ.t.LLZZZZ.h.commentFeedOuterComment.imageList"
    private fun listRead(model: String, suffix: String, vararg operations: String) = P.Read(
        PortalRuleSyntax.fields("$model.$suffix".split('.')), operations.map(PortalRuleSyntax::parse), P.Codec.STRING_LIST)
    private val cache = pages.associateWith { page -> models.mapIndexed { index, model ->
        val target = P.Node("\$current", "\$visibleView", emptyList())
        val rule = P.Rule("douyinPhoto$index", PACKAGE, listOf(page), views, emptyList(), target, emptyList())
        P.Plan(rule, model, listOf(
            P.Kind.PHOTO to listRead(model, "urlList", "[pattern].*\\.gif(?:[?~].*)?$"),
            P.Kind.PHOTO to listRead(model, "urlList", "[pattern].*\\.jpeg.*"),
            P.Kind.PHOTO to listRead(model, "urlList", "[last]"),
            P.Kind.LIVE to listRead(model, "video.playAddr.urlList", "[last]"),
            P.Kind.LIVE to listRead(model, "video.playAddrH265.urlList", "[last]"),
            P.Kind.LIVE to listRead(model, "video.h264PlayAddr.urlList", "[last]")))
    } }
    private fun commentPlan(page: String, model: String, indexPath: String?): P.Plan {
        val rule = P.Rule("douyinComment$model", PACKAGE, listOf(page), views, emptyList(),
            P.Node("\$current", "\$visibleView", emptyList()), emptyList())
        val index = P.Step("index", if (indexPath == null) "0" else "\$index")
        fun read(vararg fields: String) = P.Read(PortalRuleSyntax.fields(model.split('.')),
            listOf(P.Step("json"), index) + fields.map { P.Step("field", it) } + P.Step("last"), P.Codec.JSON)
        val variables = indexPath?.let { mapOf("\$index" to P.Read(PortalRuleSyntax.fields(it.split('.')),
            emptyList(), P.Codec.NUMBER)) }.orEmpty()
        return P.Plan(rule, model, listOf(P.Kind.PHOTO to read("originUrl", "urlList"),
            P.Kind.LIVE to read("liveVideo", "urlList")), variables)
    }
    private val commentCache = pages.associateWith { page ->
        commentModels.mapIndexed { index, model -> commentPlan(page, model, "${holders[index]}.e") } +
            commentPlan(page, OLD_COMMENT_MODEL, null)
    }
    // 评论列表单图：image_cover → fr3 → ha8 → 列表项根，经 RecyclerView 的 mViewHolder 取当前评论。
    // 新样式 ViewHolder（Pyu 子类）存于 b，CommentViewHolderOldStyle 存于 c。多图层级不同且无图片下标，不登记。
    val listCommentModels = listOf("b", "c").map { "mParent.mParent.mParent.mLayoutParams.mViewHolder.$it.imageList" }
    // 评论表情包（GIF）：RemoteImageView 直接挂在 ha8 下，往上 2 层到列表项根；Comment.emoji 是 sticker。
    val emojiModels = listOf("b", "c").map { "mParent.mParent.mLayoutParams.mViewHolder.$it.emoji" }
    private fun emojiPlan(page: String, model: String): P.Plan {
        val rule = P.Rule("douyinEmoji$model", PACKAGE, listOf(page), views, emptyList(),
            P.Node("\$current", "\$visibleView", emptyList()), emptyList())
        return P.Plan(rule, model, listOf(
            // 实测 31.7.2：animateUrl 为 3 个 ~tplv…:q86.heif（动图序列）加末尾 1 个 q86.jpeg 静态兜底，不能取 [last]。
            P.Kind.PHOTO to listRead(model, "animateUrl.urlList", "[pattern].*\\.gif(?:[?~].*)?$"),
            P.Kind.PHOTO to listRead(model, "animateUrl.urlList", "[pattern].*\\.(?:heif|heic|avif|awebp|webp)(?:\\?.*)?$"),
            P.Kind.PHOTO to listRead(model, "animateUrl.urlList", "[0]"),
            P.Kind.PHOTO to listRead(model, "staticUrl.urlList", "[last]")))
    }
    private val listCache = pages.associateWith { page ->
        listCommentModels.map { commentPlan(page, it, null) } + emojiModels.map { emojiPlan(page, it) }
    }
    // 评论模型先于作品封面执行；只读取当前评论和当前页索引，不配对主作品视频。
    fun compiled(page: String): List<P.Plan> =
        commentCache[page].orEmpty() + listCache[page].orEmpty() + cache[page].orEmpty()
    private val uriPaths = listOf("mRequest.LIZ", "mRequest.LIZIZ").map {
        PortalRuleSyntax.fields(it.split('.'), "Uri")
    }
    private val bitmapPaths = listOf("mBitmapCloseableReference.mSharedReference.LIZ.mBitmap",
        "mDrawable.mBitmapState.mBitmap").map { PortalRuleSyntax.fields(it.split('.'), "Object") }
    fun forPage(page: String): Map<String, String>? {
        if (page !in pages) return null
        return views.associateWith { view ->
            val photos = cache.getValue(page)
            val comments = commentCache.getValue(page)
            val plans = when (view) {
                PAGER -> comments.take(1) + photos.take(1)
                COMMENT_IMAGE -> comments.takeLast(1)
                LIST_IMAGE -> listCache.getValue(page)
                else -> comments.drop(1).dropLast(1) + photos.drop(1)
            }
            PortalMediaContract.bitmapFieldRule(plans.flatMap { it.fields } + uriPaths + bitmapPaths)
        }
    }
    fun standardPaths(): List<String> = uriPaths
}
