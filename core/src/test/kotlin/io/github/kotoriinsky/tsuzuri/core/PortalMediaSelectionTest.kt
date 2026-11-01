package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalMediaSelectionTest {
    @Test fun standardWechatGifKeepsItsSignedOriginalAddress() {
        val path = "mmbiz.qpic.cn/mmbiz_gif/example%2Fimage/0?wx_fmt=gif&sign=a%2Fb%2B%3D&wxfrom=5"
        assertEquals(PortalMediaSelection.Source("https://$path", null),
            PortalMediaSelection.pick(mapOf("imgUrl" to "//$path")))
    }
    @Test fun standardXImageKeepsItsOriginalSizeParameter() {
        val image = "https://pbs.twimg.com/media/example?format=jpg&name=orig"
        assertEquals(PortalMediaSelection.Source(image, null),
            PortalMediaSelection.pick(mapOf("imgUrl" to image)))
    }
    @Test fun standardProtocolRelativeImageKeepsOriginalBytesRoute() {
        assertEquals(PortalMediaSelection.Source("https://wx1.sinaimg.cn/large/a.gif", null),
            PortalMediaSelection.pick(mapOf("imgUrl" to "//wx1.sinaimg.cn/large/a.gif")))
    }
    @Test fun standardContentUriDoesNotRequireAnyAppPrivateModel() {
        val uri = "content://media/external/images/media/42"
        assertEquals(PortalMediaSelection.Source(uri, null), PortalMediaSelection.pick(mapOf("imgUrl" to uri)))
    }
    @Test fun standardWebImageDoesNotRequireAnyAppModel() {
        val image = "https://images.example.org/large/standard.gif?signature=a%2Fb%2B%3D"
        assertEquals(PortalMediaSelection.Source(image, null), PortalMediaSelection.pick(mapOf("imgUrl" to image)))
        assertNull(PortalMediaSelection.pick(mapOf("imgUrl" to "file:///private/image.jpg")))
    }
    private val image = "https://sns-img-bd.xhscdn.com/a"
    private val video = "https://sns-video-bd.xhscdn.com/a.mp4"
    @Test fun selectsLivePairFromOneCurrentItem() {
        val model = PortalMediaRules.imageModels[1]
        val selected = PortalMediaSelection.pick(mapOf(
            PortalMediaRules.modelPath(model, "url") to image,
            PortalMediaRules.modelPath(model, "livePhotoUrl") to video,
        ))
        assertEquals(PortalMediaSelection.Source(image, video), selected)
    }
    @Test fun neverPairsDifferentAncestorModels() {
        val values = mapOf(
            PortalMediaRules.modelPath(PortalMediaRules.imageModels[0], "url") to image,
            PortalMediaRules.modelPath(PortalMediaRules.imageModels[3], "livePhotoUrl") to video,
        )
        assertEquals(PortalMediaSelection.Source(image, null), PortalMediaSelection.pick(values))
    }
    @Test fun playerKeepsVideoAndItsOwnCover() {
        val prefix = PortalMediaRules.playerPrefixes[0]
        assertEquals(PortalMediaSelection.Source(image, video), PortalMediaSelection.pick(mapOf(
            PortalMediaRules.playerPath(prefix, "C.a") to video,
            PortalMediaRules.playerPath(prefix, "d") to image,
        )))
    }
    @Test fun unrelatedAndDisallowedAddressesDoNotBecomeSources() {
        assertNull(PortalMediaSelection.pick(mapOf("unrelated" to image)))
        assertNull(PortalMediaSelection.pick(mapOf(PortalMediaRules.imagePaths[0] to "file:///private")))
    }
    @Test fun selectsLivePairBoundThroughOrdinaryPhotoPresenter() {
        // 真机父链：图片 → FrameLayout → AsyncPhotoNoteItemView → RecyclerView 当前项。
        val prefix = "mParent/Object/mParent/Object/mParent/Object/mLayoutParams/Object/mViewHolder/Object/c/Object"
        val values = mapOf(
            "$prefix/k/Object/_value/Object/h/Object/imageBean/Object/url/String" to image,
            "$prefix/k/Object/_value/Object/h/Object/imageBean/Object/livePhotoUrl/String" to video,
        )
        val rules = PortalMediaRules.forPage("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE)!!
        val configured = rules.getValue(PortalMediaRules.NOTE_IMAGE).split(',').toSet()
        assertEquals(PortalMediaSelection.Source(image, video), PortalMediaSelection.pick(values.filterKeys { it in configured }))
    }
    @Test fun selectsCurrentPhotoFromLinkerHolderInsteadOfItsEventSubject() {
        // 当前进程的 c 是事件流，a 才是 Linker，媒体模型属于它的 controller。
        val prefix = "mParent/Object/mParent/Object/mParent/Object/mLayoutParams/Object/mViewHolder/Object/a/Object/controller/Object/D/Object/imageBean/Object"
        val values = mapOf("$prefix/url/String" to image, "$prefix/livePhotoUrl/String" to video)
        val rules = PortalMediaRules.forPage("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE)!!
        val configured = rules.getValue(PortalMediaRules.NOTE_IMAGE).split(',').toSet()
        assertEquals(PortalMediaSelection.Source(image, video), PortalMediaSelection.pick(values.filterKeys { it in configured }))
    }
    @Test fun nearestStaticItemDoesNotBecomeAnotherItemsLivePhoto() {
        val near = PortalMediaRules.imageModels[0]
        val far = PortalMediaRules.imageModels.last()
        val values = mapOf(
            PortalMediaRules.modelPath(near, "url") to image,
            PortalMediaRules.modelPath(far, "url") to "$image-other",
            PortalMediaRules.modelPath(far, "livePhotoUrl") to video,
        )
        assertEquals(PortalMediaSelection.Source(image, null), PortalMediaSelection.pick(values))
    }
    @Test fun originalAddressTakesPriorityOverDisplayThumbnail() {
        val model = PortalMediaRules.imageModels[0]
        assertEquals(PortalMediaSelection.Source(image, null), PortalMediaSelection.pick(mapOf(
            PortalMediaRules.modelPath(model, "original") to image,
            PortalMediaRules.modelPath(model, "url") to "$image-thumbnail",
        )))
    }
    @Test fun browserModelKeepsItsLiveIdentityWithoutImageBeanWrapper() {
        val model = "mParent.mParent.mParent.mLayoutParams.mViewHolder.a.controller.n"
        val values = mapOf(
            PortalMediaRules.modelPath(model, "original") to image,
            PortalMediaRules.modelPath(model, "livePhotoUrl") to video,
        )
        val source = PortalMediaSelection.pick(values)!!
        assertEquals(PortalMediaSelection.Source(image, video), source)
        assertTrue(PortalMediaSelection.isLive(source, values))
    }
    @Test fun failedStreamCanFallBackOnlyWithinTheSameCurrentItem() {
        val model = PortalMediaRules.imageModels[0]
        val json = "{\"stream\":{\"h264\":[]}}"
        val values = mapOf(
            PortalMediaRules.modelPath(model, "original") to image,
            PortalMediaRules.modelPath(model, "videoInfoFromLivePhoto.videoInfoJson") to json,
            PortalMediaRules.modelPath(model, "videoInfoFromLivePhoto.url") to video,
            PortalMediaRules.modelPath(PortalMediaRules.imageModels.last(), "livePhotoUrl") to "$video-unrelated",
        )
        val source = PortalMediaSelection.pick(values)!!
        assertEquals(listOf(json, video), source.videoReferences)
        assertTrue(PortalMediaSelection.isLive(source, values))
    }
}
