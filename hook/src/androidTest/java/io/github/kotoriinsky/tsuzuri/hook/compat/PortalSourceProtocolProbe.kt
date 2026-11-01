package io.github.kotoriinsky.tsuzuri.hook.compat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import io.github.kotoriinsky.tsuzuri.core.PortalMediaRules

/** 只在独立 app_process 探针执行原装采集器，目标 View 是合成控件。 */
object PortalSourceProtocolProbe {
    class BitmapState(@JvmField val mBitmap: Bitmap)
    class DrawableHolder(@JvmField val mBitmapState: BitmapState)
    class ThumbReference(@JvmField val a: Bitmap)
    class ThumbHolder(@JvmField val b: ThumbReference)
    class SourceView(context: Context, @JvmField val sourceBitmap: Bitmap,
        thumbnail: Bitmap = sourceBitmap) : View(context) {
        @JvmField val mDrawable = DrawableHolder(BitmapState(sourceBitmap))
        @JvmField val e = ThumbHolder(ThumbReference(thumbnail))
        override fun onDraw(canvas: Canvas) { canvas.drawBitmap(sourceBitmap, 0f, 0f, Paint()) }
    }

    fun verify(context: Context) {
        val loader = context.createPackageContext("com.miui.contentcatcher",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
        val type = Class.forName("com.miui.catcherpatch.ViewContentFetcher", true, loader)
        val token = Class.forName("miui.contentcatcher.sdk.Token", false, loader)
        val fixtureToken = token.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .newInstance("io.github.kotoriinsky.tsuzuri.hook.test", "SyntheticBitmapPage", 1)
        val source = Bitmap.createBitmap(300, 2000, Bitmap.Config.ARGB_8888)
        val thumbnail = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888)
        source.density = context.resources.displayMetrics.densityDpi
        try {
            val view = SourceView(context, source, thumbnail).apply { layout(0, 0, 200, 200) }
            fun fetch(rule: String): Bundle {
                val fetcher = type.getConstructor().newInstance()
                type.getMethod("createFetcher", token, Map::class.java)
                    .invoke(fetcher, fixtureToken, mapOf(view.javaClass.name to rule))
                return type.getMethod("fetchViewContent", View::class.java, Int::class.javaPrimitiveType)
                    .invoke(fetcher, view, 2) as Bundle
            }
            val raster = checkNotNull(fetch("conversionBitmap").getParcelable("viewBitmap", Bitmap::class.java))
            try {
                check(raster.width == 200 && raster.height == 200)
                println("PASS 原装conversionBitmap按View尺寸重绘：200x200")
            } finally { raster.recycle() }
            val byField = fetch("viewBitmap,sourceBitmap/Object").getParcelable("viewBitmap", Bitmap::class.java)
            check(byField === source && byField.height == 2000)
            println("PASS 原装字段规则可读取已持有的完整Bitmap：300x2000")
            val production = PortalMediaRules.forPage("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE)!!
                .getValue(PortalMediaRules.NOTE_IMAGE)
            val packet = fetch(production)
            check(packet.getParcelable("mDrawable/Object/mBitmapState/Object/mBitmap/Object", Bitmap::class.java) === source) {
                "完整底图没有保留为独立字段，最后一个缩略图已覆盖它"
            }
            check(packet.getParcelable("e/Object/b/Object/a/Object", Bitmap::class.java) === thumbnail)
            val parcel = android.os.Parcel.obtain()
            try {
                parcel.writeBundle(packet)
                parcel.setDataPosition(0)
                val received = checkNotNull(parcel.readBundle(Bitmap::class.java.classLoader))
                val chosen = checkNotNull(PortalBitmapSourceCompat.pick(received))
                check(chosen.width == 300 && chosen.height == 2000) { "接收端选中了缩略图" }
            } finally { parcel.recycle() }
            println("PASS 生产规则保留完整底图和缩略图的独立来源")
            val method = fetch("viewBitmap,sourceBitmap/Object/getWidth/Object").getParcelable("viewBitmap", Bitmap::class.java)
            check(method == null)
            val reserved = type.getMethod("reservedFunction", Map::class.java, Int::class.javaPrimitiveType)
                .invoke(type.getConstructor().newInstance(), mapOf("canvasClass" to "PortalBitmapCanvas"), 2)
            check(reserved == null)
            println("PASS 已查入口没有方法执行或自定义Canvas回调")
            val imageRect = android.graphics.Rect(10, 20, 310, 2020)
            val native = Bundle().apply {
                putParcelable("view_rect", imageRect)
                putParcelable("viewBitmap", source)
            }
            check(PortalImageSelectionCompat.target(native) == imageRect)
            val web = Bundle(native).apply {
                putString(io.github.kotoriinsky.tsuzuri.core.PortalMediaContract.CAPTURE_ORIGIN, "WebViewCatcher")
            }
            check(PortalImageSelectionCompat.target(web) == null && !web.containsKey("view_rect"))
            check(PortalBitmapSourceCompat.pick(web) === source && source.height == 2000)
            val standard = Bundle(native).apply { putString("imgUrl", "https://mmbiz.qpic.cn/synthetic.gif") }
            check(PortalImageSelectionCompat.target(standard) == null && standard.containsKey("imgUrl"))
            check(native.containsKey("view_rect") && !source.isRecycled)
            println("PASS 网页容器不覆盖触点选框，网页及原生完整底图保留")
            println("RESULT selectionTests=1 failures=0")
            println("RESULT romTests=4 failures=0 customCanvas=false")
        } finally { source.recycle(); thumbnail.recycle() }
    }
}
