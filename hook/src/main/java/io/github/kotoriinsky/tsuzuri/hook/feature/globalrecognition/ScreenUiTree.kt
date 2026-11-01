package io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition

import android.graphics.RectF
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 向前台 App 要一份页面控件树，取出图片控件的真实边界。
 *
 * 传送门长按识图靠的是 contentcatcher 注入在每个 App 里的抓取器（抓手指下那个 View）。
 * 同一个抓取器还有一条按需通道：小爱「屏幕上下文」用的 `ContentCatcherManager.onUIAgentEvent`，
 * `uiAgentType=0` 时由前台 Activity 里的抓取器（`z.j.h` → `AICollectorController.buildXMLRequest`）
 * 导出整页 XML，节点带 `class` 与 `bounds`（`Rect.toShortString`，屏幕坐标）。
 * system_server 侧 `SecurityManagerService.enforceUIAgent` 只要求调用方是系统应用或平台签名，传送门满足。
 *
 * 回调接口 `miui.security.IUIAgentCallback` 只有一个单向方法 `onResult(Bundle)`（事务号 1），
 * 自己写一个 Binder 收，再用框架的 `Stub.asInterface` 包成接口类型交出去 —— 不需要编译期依赖。
 */
internal object ScreenUiTree {

    private const val MANAGER_CLS = "miui.contentcatcher.sdk.ContentCatcherManager"
    private const val CALLBACK_CLS = "miui.security.IUIAgentCallback"

    /** 一次请求的句柄：后台线程发出，[await] 取结果。 */
    class Request internal constructor(private val latch: CountDownLatch, private val box: Array<String?>, private val err: Array<String?>) {
        fun await(timeoutMs: Long): String? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return box[0]
        }
        val error: String? get() = err[0]
    }

    fun request(classLoader: ClassLoader?, log: (String) -> Unit): Request? {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        val err = arrayOfNulls<String>(1)
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                try {
                    data.enforceInterface(CALLBACK_CLS)
                    val b = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                    val pfd = b?.let { @Suppress("DEPRECATION") it.getParcelable<ParcelFileDescriptor>("content") }
                    if (pfd != null) {
                        box[0] = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
                    } else {
                        err[0] = "code=${b?.getInt("code")} reason=${b?.getString("reason")}"
                    }
                } catch (t: Throwable) {
                    err[0] = t.toString()
                } finally {
                    latch.countDown()
                }
                return true
            }
        }
        return try {
            val cl = classLoader ?: ClassLoader.getSystemClassLoader()
            val cbCls = Class.forName(CALLBACK_CLS, false, cl)
            val stub = Class.forName("$CALLBACK_CLS\$Stub", false, cl)
            val callback = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val mgrCls = Class.forName(MANAGER_CLS, false, cl)
            val mgr = mgrCls.getMethod("getInstance").invoke(null)
            // 请求会发给所有前台页面、先回的算数；不限定屏幕时背屏桌面（subscreencenter，displayId=1）
            // 常常抢先，拿回来的是背屏的 21 个节点（2026-10-28 真机）。抓取器按 displayId 过滤（contentcatcher z/j.f）
            val args = Bundle().apply { putInt("uiAgentType", 0); putInt("displayId", android.view.Display.DEFAULT_DISPLAY) }
            mgrCls.getMethod("onUIAgentEvent", Bundle::class.java, cbCls).invoke(mgr, args, callback)
            Request(latch, box, err)
        } catch (t: Throwable) {
            log("请求页面控件树失败：$t")
            null
        }
    }

    /**
     * 从 XML 里挑图片控件：类名含 `Image`（ImageView 及其子类、Fresco 的 DraweeView 等，
     * 无障碍类名多半报成 `android.widget.ImageView`），边长至少 [minSide] 像素。
     * 盖住大半屏的当背景图丢掉，否则点哪里都会选中它（图片优先于文字）。
     */
    fun imageBounds(xml: String, screenW: Int, screenH: Int, minSide: Float): List<RectF> {
        val out = ArrayList<RectF>()
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        val screenArea = screenW.toFloat() * screenH
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev != XmlPullParser.START_TAG) continue
            val cls = p.getAttributeValue(null, "class") ?: continue
            if (!cls.contains("Image", ignoreCase = true)) continue
            val r = parseBounds(p.getAttributeValue(null, "bounds") ?: continue) ?: continue
            if (!r.intersect(0f, 0f, screenW.toFloat(), screenH.toFloat())) continue
            if (r.width() < minSide || r.height() < minSide) continue
            if (r.width() * r.height() > screenArea * 0.8f) continue
            if (out.none { it == r }) out += r
        }
        return out
    }

    /** 页面所属的包名（根节点的 `package` 属性）：全局识别向它要源位图。 */
    fun rootPackage(xml: String): String? =
        Regex("package=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** `Rect.toShortString()`：`[l,t][r,b]`。 */
    private fun parseBounds(s: String): RectF? {
        val n = Regex("-?\\d+").findAll(s).map { it.value.toFloat() }.toList()
        if (n.size < 4) return null
        return RectF(n[0], n[1], n[2], n[3]).takeIf { it.width() > 0 && it.height() > 0 }
    }
}
