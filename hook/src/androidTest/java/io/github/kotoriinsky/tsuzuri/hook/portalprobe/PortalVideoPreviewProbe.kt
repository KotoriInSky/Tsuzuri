package io.github.kotoriinsky.tsuzuri.hook.portalprobe

import android.app.Instrumentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PortalVideoPreviewProbe {
    private fun onMain(runner: Instrumentation, action: () -> Unit) {
        var failure: Throwable? = null
        runner.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    fun run(runner: Instrumentation): Bundle {
        val output = StringBuilder()
        var failures = 0
        runner.sendStatus(0, Bundle().apply { putString("stream", "开始自有视频窗口测试\n") })
        check(PortalPreviewActivity.ready.await(10, TimeUnit.SECONDS)) { "自有测试窗口未创建" }
        val activity = checkNotNull(PortalPreviewActivity.current)
        val cases = linkedMapOf<String, () -> Unit>(
            "首帧前保留封面且首帧后取得有效视频像素" to { verify(runner, activity, false, false) },
            "播放错误保留封面且释放播放器" to { verify(runner, activity, true, false) },
            "旧会话不显示迟到的视频帧" to { verify(runner, activity, false, true) },
            "首帧回调在绘制结束后才移除加载控件" to { verifyDeferred(runner, activity, false) },
            "播放错误在当前回调结束后才移除视频控件" to { verifyDeferred(runner, activity, true) },
        )
        try {
            for ((name, test) in cases) try {
                runner.sendStatus(0, Bundle().apply { putString("stream", "开始：$name\n") })
                test(); output.append("PASS $name\n")
            } catch (error: Throwable) {
                failures++; output.append("FAIL $name: ${error.javaClass.simpleName}: ${error.message}\n")
            }
        } finally { onMain(runner) { activity.finish() } }
        return Bundle().apply {
            putInt("tests", cases.size); putInt("failures", failures); putString("stream", output.toString())
        }
    }
    private fun verifyDeferred(runner: Instrumentation, activity: PortalPreviewActivity, error: Boolean) {
        val type = Class.forName("io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalVideoPreview")
        val done = CountDownLatch(1)
        var preview: TextureView? = null
        try {
            onMain(runner) {
                val group = RelativeLayout(activity)
                val loading = android.view.View(activity)
                group.addView(loading)
                val changed: (Boolean, Boolean) -> Unit = { _, frame ->
                    if (frame) { group.removeView(loading); done.countDown() }
                }
                val failed: (Int, Int) -> Unit = { _, _ ->
                    group.removeView(loading); done.countDown()
                }
                preview = type.constructors.single().newInstance(activity,
                    Uri.fromFile(File(activity.cacheDir, "missing.mp4")), { true }, changed, failed) as TextureView
                group.addView(preview)
                val texture = SurfaceTexture(0)
                try {
                    if (error) type.getMethod("onSurfaceTextureAvailable", SurfaceTexture::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(preview, texture, 320, 240)
                    else {
                        type.getDeclaredField("prepared").apply { isAccessible = true }.setBoolean(preview, true)
                        type.getMethod("onSurfaceTextureUpdated", SurfaceTexture::class.java).invoke(preview, texture)
                    }
                    check(group.childCount == 2) { "TextureView 回调尚未返回就修改了父容器子视图" }
                } finally { texture.release() }
            }
            check(done.await(5, TimeUnit.SECONDS)) { "延迟后的界面更新未执行" }
        } finally { onMain(runner) { preview?.let { type.getMethod("dispose").invoke(it) } } }
    }
    private fun verify(runner: Instrumentation, activity: PortalPreviewActivity, invalid: Boolean, stale: Boolean) {
        val type = Class.forName("io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalVideoPreview")
        val file = File(activity.cacheDir, "portal-preview-probe.mp4")
        var preview: TextureView? = null
        var valid = true
        val first = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var frameCallbacks = 0
        try {
            activity.assets.open("portal-preview-green.mp4").use { source -> file.outputStream().use(source::copyTo) }
            onMain(runner) {
                val group = RelativeLayout(activity)
                val poster = ImageView(activity).apply { setBackgroundColor(Color.RED) }
                group.addView(poster, RelativeLayout.LayoutParams(320, 240))
                val changed: (Boolean, Boolean) -> Unit = { _, frame ->
                    if (frame) { frameCallbacks++; first.countDown() }
                }
                val error: (Int, Int) -> Unit = { _, _ -> failed.countDown() }
                val factory = type.getConstructor(Context::class.java, Uri::class.java,
                    kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function2::class.java,
                    kotlin.jvm.functions.Function2::class.java)
                preview = factory.newInstance(activity, Uri.fromFile(if (invalid) File(activity.cacheDir, "missing.mp4") else file),
                    { valid }, changed, error) as TextureView
                check(preview!!.alpha == 0f) { "视频首帧前已覆盖封面" }
                group.addView(preview, RelativeLayout.LayoutParams(320, 240))
                activity.setContentView(group, ViewGroup.LayoutParams(320, 240))
                if (stale) valid = false
            }
            if (invalid) {
                check(failed.await(8, TimeUnit.SECONDS)) { "播放错误未返回" }
                onMain(runner) {
                    check(preview!!.alpha == 0f && type.getMethod("isPlaying").invoke(preview) == false)
                }
            } else if (stale) {
                onMain(runner) {
                    val late = SurfaceTexture(0)
                    try { type.getMethod("onSurfaceTextureUpdated", SurfaceTexture::class.java).invoke(preview, late) }
                    finally { late.release() }
                    check(preview!!.alpha == 0f)
                    type.getMethod("dispose").invoke(preview)
                }
                check(frameCallbacks == 0) { "过期会话发布了视频帧" }
            } else {
                check(first.await(8, TimeUnit.SECONDS)) { "视频首帧未到达" }
                onMain(runner) {
                    check(preview!!.alpha == 1f)
                    val bitmap = checkNotNull(preview!!.bitmap)
                    try {
                        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        check(Color.green(pixel) > 150 && Color.red(pixel) < 40) { "首帧仍是空白或黑色" }
                    } finally { bitmap.recycle() }
                    type.getMethod("toggle").invoke(preview)
                    check(type.getMethod("isPlaying").invoke(preview) == false)
                    type.getMethod("toggle").invoke(preview)
                    check(type.getMethod("isPlaying").invoke(preview) == true)
                }
            }
        } finally {
            onMain(runner) {
                preview?.let { type.getMethod("dispose").invoke(it) }
                activity.setContentView(android.view.View(activity))
            }
            file.delete()
        }
    }
}
