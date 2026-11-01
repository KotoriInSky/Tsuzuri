package io.github.kotoriinsky.tsuzuri.hook.compat

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.OutcomeReceiver
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.Executor

/**
 * 截一次屏，拿 hardware bitmap（侧滑索引液态玻璃的折射底图）。
 *
 * A17 的形状（2026-09-14 真机探针）：
 * `ScreenCapture.capture(ScreenCaptureParams, Executor, OutcomeReceiver)`，
 * `ScreenCaptureParams.Builder(int displayId).build()`，结果有 `getHardwareBuffer()` / `getColorSpace()`。
 * 全是 @hide，所以只走反射；任何一步找不到就返回不可用，调用方退回无折射的玻璃。
 *
 * 参数类型不写死类名：从 `capture` 方法的第一个参数取 Params 类，再找它的 `$Builder`，
 * 这样 Params 是顶层类还是 `ScreenCapture$…` 内部类都能对上。
 */
object ScreenCaptureCompat {
    private const val TAG = "FP-ScreenCapture"

    private class Api(val capture: Method, val builderCtor: java.lang.reflect.Constructor<*>, val build: Method)

    private val api: Api? by lazy {
        runCatching {
            val sc = Class.forName("android.window.ScreenCapture")
            val capture = sc.declaredMethods.first {
                it.name == "capture" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == Executor::class.java &&
                    it.parameterTypes[2] == OutcomeReceiver::class.java
            }.apply { isAccessible = true }
            val params = capture.parameterTypes[0]
            val builder = Class.forName(params.name + "\$Builder", false, params.classLoader)
            val ctor = builder.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
            val build = builder.getDeclaredMethod("build").apply { isAccessible = true }
            Api(capture, ctor, build)
        }.onFailure { Log.w(TAG, "截屏 API 不可用，液态玻璃退回无折射", it) }.getOrNull()
    }

    val available: Boolean get() = api != null

    /**
     * 异步截屏。[onDone] 在 [executor] 上回调，失败给 null。
     * 拿到的是 HARDWARE 配置的 Bitmap，可直接建 BitmapShader（实测）。
     */
    fun capture(displayId: Int, executor: Executor, onDone: (Bitmap?) -> Unit) {
        val a = api ?: return onDone(null)
        val start = SystemClock.uptimeMillis()
        runCatching {
            val params = a.build.invoke(a.builderCtor.newInstance(displayId))
            val receiver = object : OutcomeReceiver<Any, Throwable> {
                override fun onResult(result: Any) {
                    val bmp = runCatching {
                        val hb = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as HardwareBuffer
                        val cs = runCatching { result.javaClass.getMethod("getColorSpace").invoke(result) as? ColorSpace }.getOrNull()
                        Bitmap.wrapHardwareBuffer(hb, cs).also { hb.close() }
                    }.onFailure { Log.w(TAG, "截屏结果转 Bitmap 失败", it) }.getOrNull()
                    Log.i(TAG, "截屏完成 ${SystemClock.uptimeMillis() - start}ms ok=${bmp != null} size=${bmp?.width}x${bmp?.height}")
                    onDone(bmp)
                }

                override fun onError(error: Throwable) {
                    Log.w(TAG, "截屏失败", error)
                    onDone(null)
                }
            }
            a.capture.invoke(null, params, executor, receiver)
        }.onFailure {
            Log.w(TAG, "截屏调用失败", it)
            onDone(null)
        }
    }
}
