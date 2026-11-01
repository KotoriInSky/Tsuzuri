package io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.kotoriinsky.tsuzuri.capture.engine.SourceImageContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 向前台应用要某块屏幕区域里那张图的源位图（接收端在 `:capture` 的 `SourceImageServer`）。
 *
 * 阻塞调用，只能在后台线程用。拿不到（应用里没有接收端、找不到控件、超时）就返回 null 并说明原因，
 * 调用方退回截图裁图。
 */
internal object SourceImageClient {

    class Result(val bitmap: Bitmap?, val detail: String)

    private val main = Handler(Looper.getMainLooper())

    class ListResult(val rects: List<Rect>?, val detail: String)

    /**
     * 让 [pkg] 在它自己的进程里列出屏幕上真画着位图的控件位置。
     * 拿不到（没有接收端、超时）返回 rects=null，调用方退回系统控件树 / 像素算法。
     */
    fun list(context: Context, pkg: String, minSide: Int, timeoutMs: Long): ListResult {
        val t0 = SystemClock.uptimeMillis()
        val latch = CountDownLatch(1)
        var rects: List<Rect>? = null
        var info = ""
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != SourceImageContract.TX_LIST && code != SourceImageContract.TX_FAIL) {
                    return super.onTransact(code, data, reply, flags)
                }
                try {
                    data.enforceInterface(SourceImageContract.DESCRIPTOR)
                    if (code == SourceImageContract.TX_LIST) {
                        val n = data.readInt()
                        val a = data.createIntArray() ?: IntArray(0)
                        info = data.readString().orEmpty()
                        rects = (0 until minOf(n, a.size / 4)).map { i -> Rect(a[i * 4], a[i * 4 + 1], a[i * 4 + 2], a[i * 4 + 3]) }
                    } else {
                        info = data.readString().orEmpty()
                    }
                } catch (t: Throwable) {
                    info = "应答解析失败：$t"
                } finally {
                    latch.countDown()
                }
                return true
            }
        }
        val intent = Intent(SourceImageContract.ACTION).setPackage(pkg).putExtras(Bundle().apply {
            putBinder(SourceImageContract.EXTRA_CALLBACK, callback)
            putInt(SourceImageContract.EXTRA_MODE, SourceImageContract.MODE_LIST)
            putInt(SourceImageContract.EXTRA_MIN_SIDE, minSide)
        })
        try {
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            return ListResult(null, "发送请求失败：$t")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return ListResult(null, "${timeoutMs}ms 内没有应答（$pkg 里可能没有接收端）")
        }
        return ListResult(rects, "$info，全程 ${SystemClock.uptimeMillis() - t0}ms")
    }

    fun fetch(context: Context, pkg: String, rect: Rect, timeoutMs: Long): Result {
        val t0 = SystemClock.uptimeMillis()
        val latch = CountDownLatch(1)
        var pipe: ParcelFileDescriptor? = null
        var info = ""
        var failure: String? = null
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != SourceImageContract.TX_RESULT && code != SourceImageContract.TX_FAIL) {
                    return super.onTransact(code, data, reply, flags)
                }
                try {
                    data.enforceInterface(SourceImageContract.DESCRIPTOR)
                    if (code == SourceImageContract.TX_RESULT) {
                        val w = data.readInt()
                        val h = data.readInt()
                        info = "${w}x$h ${data.readString()}"
                        pipe = data.readFileDescriptor()
                    } else {
                        failure = data.readString()
                    }
                } catch (t: Throwable) {
                    failure = "应答解析失败：$t"
                } finally {
                    latch.countDown()
                }
                return true
            }
        }
        val intent = Intent(SourceImageContract.ACTION).setPackage(pkg).putExtras(Bundle().apply {
            putBinder(SourceImageContract.EXTRA_CALLBACK, callback)
            putIntArray(SourceImageContract.EXTRA_RECT, intArrayOf(rect.left, rect.top, rect.right, rect.bottom))
        })
        try {
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            return Result(null, "发送请求失败：$t")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return Result(null, "${timeoutMs}ms 内没有应答（$pkg 里可能没有接收端）")
        }
        failure?.let { return Result(null, it) }
        val fd = pipe ?: return Result(null, "应答里没有数据")
        // 读管道也计入总时限：到点就关掉读端，解码随之结束
        val remaining = (timeoutMs - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(200)
        val guard = Runnable { runCatching { fd.close() } }
        main.postDelayed(guard, remaining)
        return try {
            val bmp = ParcelFileDescriptor.AutoCloseInputStream(fd).use { BitmapFactory.decodeStream(it) }
            if (bmp == null) Result(null, "解码失败（$info）")
            else Result(bmp, "源位图 $info，${SystemClock.uptimeMillis() - t0}ms")
        } catch (t: Throwable) {
            Result(null, "读取失败（$info）：$t")
        } finally {
            main.removeCallbacks(guard)
        }
    }
}
