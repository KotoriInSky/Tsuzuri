package io.github.kotoriinsky.tsuzuri.hook.compat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import io.github.kotoriinsky.tsuzuri.core.PortalMediaRules
import io.github.kotoriinsky.tsuzuri.core.PortalMediaSelection

/** 用原装 extractor 验证分层翻译；合成字段不代表真实 App 具有相同路径。 */
object PortalRuleTranslationProbe {
    private const val PHOTO = "https://sns-img-bd.xhscdn.com/probe.jpg?sign=a%2Fb"
    private const val VIDEO = "https://sns-video-bd.xhscdn.com/probe.mp4?sign=a%2Fb"
    private const val OTHER_VIDEO = "https://sns-video-bd.xhscdn.com/other.mp4"
    private const val JSON = "{\"stream\":{\"h264\":[{\"master_url\":\"$VIDEO\",\"width\":100,\"height\":200}],\"h265\":[{\"master_url\":\"$OTHER_VIDEO\"}]}}"

    class VideoModel(@JvmField val videoInfoJson: String)
    class ImageModel(@JvmField val original: String, @JvmField val livePhotoUrl: String = "",
        @JvmField val videoInfoFromLivePhoto: VideoModel? = null)
    class Item(@JvmField val imageBean: ImageModel)
    class Binding(@JvmField val h: Item)
    class State(@JvmField val _value: Binding)
    class Presenter(@JvmField val w: Item, @JvmField val j: State)
    class Holder(@JvmField val c: Presenter)
    class Params(@JvmField val mViewHolder: Holder) : ViewGroup.LayoutParams(200, 200)
    class SourceView(context: Context, @JvmField val sourceShader: BitmapShader,
        @JvmField val sourcePaint: Paint) : View(context) {
        override fun onDraw(canvas: Canvas) {
            canvas.drawRoundRect(0f, 0f, 200f, 200f, 12f, 12f, sourcePaint)
        }
    }

    fun verify(context: Context) {
        val loader = context.createPackageContext("com.miui.contentcatcher",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
        val type = Class.forName("com.miui.catcherpatch.ViewContentFetcher", true, loader)
        val tokenType = Class.forName("miui.contentcatcher.sdk.Token", false, loader)
        val token = tokenType.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .newInstance("io.github.kotoriinsky.tsuzuri.hook.test", "SyntheticRulePage", 1)
        val source = Bitmap.createBitmap(300, 6000, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        source.setPixel(299, 5999, Color.BLUE)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val view = SourceView(context, shader, Paint().apply { this.shader = shader }).apply {
            layout(0, 0, 200, 200)
        }
        val configured = checkNotNull(PortalMediaRules.forPage("com.xingin.xhs", 9481803,
            PortalMediaRules.NOTE_PAGE))[PortalMediaRules.NOTE_IMAGE]!!.split(',').toSet()
        fun path(model: Int, leaf: String) = PortalMediaRules.modelPath(PortalMediaRules.imageModels[model], leaf)
        fun bind(near: ImageModel, far: ImageModel = ImageModel("")) {
            view.layoutParams = Params(Holder(Presenter(Item(near), State(Binding(Item(far))))))
        }
        fun fetch(fields: List<String>): Bundle {
            val fetcher = type.getConstructor().newInstance()
            type.getMethod("createFetcher", tokenType, Map::class.java)
                .invoke(fetcher, token, mapOf(view.javaClass.name to (listOf("viewBitmap") + fields).joinToString(",")))
            return type.getMethod("fetchViewContent", View::class.java, Int::class.javaPrimitiveType)
                .invoke(fetcher, view, 2) as Bundle
        }
        fun transferred(fields: List<String>, test: (Bundle) -> Unit) {
            val before = fetch(fields)
            val parcel = Parcel.obtain()
            try {
                before.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val after = Bundle.CREATOR.createFromParcel(parcel)
                try { test(after) } finally {
                    after.getParcelable("viewBitmap", Bitmap::class.java)?.takeIf { it !== source }?.recycle()
                }
            } finally { parcel.recycle() }
        }
        fun values(bundle: Bundle): Map<String, String> = bundle.keySet().mapNotNull { key ->
            (bundle.get(key) as? CharSequence)?.let { key to it.toString() }
        }.toMap()
        fun verifyShader(field: String) = transferred(listOf(field)) { bundle ->
            val bitmap = checkNotNull(bundle.getParcelable("viewBitmap", Bitmap::class.java))
            check(bitmap.width == 300 && bitmap.height == 6000 && bitmap.getPixel(299, 5999) == Color.BLUE)
            check(view.width == 200 && view.height == 200)
        }
        val cases = linkedMapOf<String, () -> Unit>(
            "Shader字段翻译取得完整长图并经Parcel保留尾部" to {
                verifyShader("sourceShader/Object/mBitmap/Object")
            },
            "Paint到Shader的字段链取得完整长图" to {
                verifyShader("sourcePaint/Object/mShader/Object/mBitmap/Object")
            },
            "当前项规则传回JSON后由生产解析器取得配对视频" to {
                bind(ImageModel(PHOTO, videoInfoFromLivePhoto = VideoModel(JSON)))
                val fields = listOf(path(0, "original"), path(0, "videoInfoFromLivePhoto.videoInfoJson"))
                check(fields.all { it in configured })
                transferred(fields) { bundle ->
                    val extracted = values(bundle)
                    val selected = checkNotNull(PortalMediaSelection.pick(extracted))
                    check(selected.image == PHOTO && selected.video == JSON)
                    check(PortalMediaSelection.isLive(selected, extracted))
                    check(PortalMediaStreamCompat.urls(checkNotNull(selected.video)) == listOf(VIDEO, OTHER_VIDEO))
                }
            },
            "字段翻译不把其它模型视频配给当前静态照片" to {
                bind(ImageModel(PHOTO), ImageModel("$PHOTO-other", OTHER_VIDEO))
                val fields = listOf(path(0, "original"), path(0, "livePhotoUrl"),
                    path(1, "original"), path(1, "livePhotoUrl"))
                check(fields.all { it in configured })
                transferred(fields) { bundle ->
                    val selected = checkNotNull(PortalMediaSelection.pick(values(bundle)))
                    check(selected.image == PHOTO && selected.video == null)
                }
            },
        )
        var failures = 0
        try {
            for ((name, test) in cases) {
                try { test(); println("PASS $name") } catch (error: Throwable) {
                    failures++
                    println("FAIL $name: ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            println("RESULT translatedTests=${cases.size} failures=$failures origin=synthetic")
            check(failures == 0)
        } finally { source.recycle() }
    }
}
