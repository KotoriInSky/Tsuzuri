package io.github.kotoriinsky.tsuzuri.capture

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * WebView 里的图片：Flyme 识屏有专门的 `WebViewInfoPicker`，我们原来遇到 WebView
 * 只能退回截图像素算法去猜。
 *
 * 在来源应用进程里对它的 WebView 跑一段只读脚本，列出视口里的 `<img>` 和 CSS 背景图：
 * 位置换成屏幕坐标交给全局识别，地址留在本进程，用户选中某张图时按地址把**原文件**取回来
 * （带上页面自己的 Cookie、UA 和 Referer，与页面加载那张图时是同一个请求）。
 *
 * 只覆盖 `android.webkit.WebView`；X5、XWeb 等自带内核的 WebView 不是它的子类，列图时只记日志。
 * 页面关了 JavaScript 时脚本不执行，回调拿到 null，按「没有图」处理。
 */
internal object WebImages {

    class Item(val rect: Rect, val src: String)

    /** 文件超过这个大小就不取（原图再大也不该在一次点选里搬几十 MB）。 */
    private const val MAX_BYTES = 24 shl 20

    /** 最多跟几次跳转（图床常有一跳到 CDN）。 */
    private const val MAX_REDIRECTS = 3

    /** 列表在多久内可以直接复用（选图时不必再跑一次脚本）。 */
    private const val CACHE_MS = 30_000L

    /** 网页里比这还小（CSS 像素）的图不列，大多是图标、跟踪像素。 */
    private const val MIN_CSS = 16

    /** 最多扫多少个元素：长页面也不能把来源应用的主线程拖住。 */
    private const val MAX_ELEMENTS = 4000

    private class Cached(val at: Long, val items: List<Item>)

    private val cache = WeakHashMap<View, Cached>()
    private val evalMethodCache = WeakHashMap<Class<*>, java.lang.reflect.Method?>()

    /** 判断 [v] 是否为系统 `WebView` 或具备 `evaluateJavascript` 的三方内核（X5 / XWeb / UC 等）。 */
    fun isSupportedWebView(v: View): Boolean =
        v is WebView || findEvalMethod(v.javaClass) != null

    private fun findEvalMethod(cls: Class<*>): java.lang.reflect.Method? {
        if (evalMethodCache.containsKey(cls)) return evalMethodCache[cls]
        val found = runCatching {
            var c: Class<*>? = cls
            var hit: java.lang.reflect.Method? = null
            while (c != null && hit == null && c != View::class.java && c != Any::class.java) {
                hit = c.methods.firstOrNull { m ->
                    m.name == "evaluateJavascript" &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == String::class.java &&
                        m.parameterTypes[1].isInterface
                }
                c = c.superclass
            }
            hit
        }.getOrNull()
        evalMethodCache[cls] = found
        return found
    }

    private fun evalJs(wv: View, script: String, onResult: (String?) -> Unit) {
        if (wv is WebView) {
            wv.evaluateJavascript(script) { onResult(it) }
            return
        }
        val method = findEvalMethod(wv.javaClass) ?: run { onResult(null); return }
        val cbType = method.parameterTypes[1]
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            cbType.classLoader,
            arrayOf(cbType),
        ) { _, m, args ->
            if (m.name == "onReceiveValue") {
                onResult(args?.firstOrNull() as? String)
            }
            null
        }
        method.invoke(wv, script, proxy)
    }

    fun readUserAgent(wv: View): String? =
        if (wv is WebView) {
            runCatching { wv.settings.userAgentString }.getOrNull()
        } else {
            runCatching {
                val settings = wv.javaClass.getMethod("getSettings").invoke(wv) ?: return@runCatching null
                settings.javaClass.getMethod("getUserAgentString").invoke(settings) as? String
            }.getOrNull()
        }

    fun readUrl(wv: View): String? =
        if (wv is WebView) {
            runCatching { wv.url }.getOrNull()
        } else {
            runCatching { wv.javaClass.getMethod("getUrl").invoke(wv) as? String }.getOrNull()
        }

    /**
     * 只读脚本：返回视口尺寸、视觉视口偏移（CSS 像素）和视口内每张图的 [左, 上, 右, 下, 地址]，
     * 坐标相对布局视口；同源 iframe 里的图加上 iframe 的位置。不定义全局变量、不改 DOM。
     */
    private val SCRIPT = """
        (function(){try{
          var vv=window.visualViewport,W=vv?vv.width:innerWidth,H=vv?vv.height:innerHeight,
              ox=vv?vv.offsetLeft:0,oy=vv?vv.offsetTop:0,out=[],seen=0;
          function bestSrcset(ss,base){
            if(!ss)return null;var parts=ss.split(','),bestUrl=null,bestScore=-1;
            for(var i=0;i<parts.length;i++){
              var seg=parts[i].trim();if(!seg)continue;
              var sp=seg.split(/\s+/),u=sp[0],d=sp[1]||'',sc=1;
              if(/^\d+w$/.test(d))sc=parseInt(d,10);
              else if(/^\d+(\.\d+)?x$/.test(d))sc=parseFloat(d)*1000;
              if(sc>bestScore){try{bestUrl=new URL(u,base).href;bestScore=sc;}catch(x){}}
            }
            return bestUrl;
          }
          function bestImgSrc(e,doc){
            var best=null;
            if(e.parentElement&&e.parentElement.tagName==='PICTURE'){
              var srcs=e.parentElement.getElementsByTagName('SOURCE');
              for(var k=0;k<srcs.length;k++){
                var cand=bestSrcset(srcs[k].getAttribute('srcset'),doc.baseURI);
                if(cand){best=cand;break;}
              }
            }
            return best||bestSrcset(e.getAttribute('srcset'),doc.baseURI)||e.currentSrc||e.src;
          }
          function bg(e,doc){var s=doc.defaultView.getComputedStyle(e).backgroundImage;
            if(!s||s==='none')return null;var m=/url\(["']?([^"')]+)["']?\)/.exec(s);
            if(!m)return null;try{return new URL(m[1],doc.baseURI).href}catch(x){return null}}
          function walk(doc,dx,dy,d){
            var all=doc.getElementsByTagName('*');
            for(var i=0;i<all.length&&seen<$MAX_ELEMENTS;i++){
              var e=all[i];seen++;var r=e.getBoundingClientRect();
              var l=r.left+dx,t=r.top+dy,rr=r.right+dx,b=r.bottom+dy;
              if(r.width<$MIN_CSS||r.height<$MIN_CSS||rr<=ox||b<=oy||l>=ox+W||t>=oy+H)continue;
              var tag=e.tagName,src=null;
              if(tag==='IMG'){if(e.complete&&e.naturalWidth>=$MIN_CSS)src=bestImgSrc(e,doc)}
              else if(tag==='IFRAME'){if(d<3){try{var cd=e.contentDocument;if(cd)walk(cd,l+e.clientLeft,t+e.clientTop,d+1)}catch(x){}}continue}
              else src=bg(e,doc);
              if(src)out.push([l,t,rr,b,src]);
            }
          }
          walk(document,0,0,0);
          return JSON.stringify({w:W,h:H,ox:ox,oy:oy,items:out});
        }catch(x){return null}})()
    """.trimIndent()

    /** 主线程调用。列出 [wv] 视口里的图片，屏幕坐标、按可见矩形裁过；[done] 在主线程回调。 */
    fun query(wv: View, done: (List<Item>) -> Unit) {
        try {
            evalJs(wv, SCRIPT) { raw ->
                val items = runCatching { parse(wv, raw) }.getOrDefault(emptyList())
                cache[wv] = Cached(SystemClock.uptimeMillis(), items)
                done(items)
            }
        } catch (t: Throwable) {
            done(emptyList())
        }
    }

    /** 最近一次列表（[CACHE_MS] 内），没有返回 null。 */
    fun cached(wv: View): List<Item>? =
        cache[wv]?.takeIf { SystemClock.uptimeMillis() - it.at <= CACHE_MS }?.items

    private fun parse(wv: View, raw: String?): List<Item> {
        if (raw == null || raw == "null") return emptyList()
        // evaluateJavascript 把脚本返回的字符串再按 JSON 编码了一次
        val json = JSONObject(JSONTokener(raw).nextValue() as? String ?: return emptyList())
        val vw = json.getDouble("w")
        if (vw <= 0) return emptyList()
        val k = wv.width / vw
        val ox = json.getDouble("ox")
        val oy = json.getDouble("oy")
        val origin = IntArray(2).also { wv.getLocationOnScreen(it) }
        val clip = Rect(origin[0], origin[1], origin[0] + wv.width, origin[1] + wv.height)
        val arr: JSONArray = json.getJSONArray("items")
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            val r = Rect(
                origin[0] + ((a.getDouble(0) - ox) * k).roundToInt(),
                origin[1] + ((a.getDouble(1) - oy) * k).roundToInt(),
                origin[0] + ((a.getDouble(2) - ox) * k).roundToInt(),
                origin[1] + ((a.getDouble(3) - oy) * k).roundToInt(),
            )
            val full = r.width().toLong() * r.height()
            if (full <= 0 || !r.intersect(clip)) continue
            val src = a.getString(4)
            if (!src.startsWith("http://") && !src.startsWith("https://") && !src.startsWith("data:image/") && !src.startsWith("blob:")) continue
            // 被 WebView 边缘裁掉大半的不要（与原生控件同一个门槛）
            if (r.width().toLong() * r.height() < full * 6 / 10) continue
            out += Item(r, src)
        }
        return out
    }

    /**
     * 主线程调用：在 [wv] 内把 `blob:` 图片异步读成 DataURL 字节数组（兼容 Telegram Web、加密图床与现代 SPA）。
     */
    fun fetchBlob(wv: View, blobUrl: String, done: (ByteArray?) -> Unit) {
        val quoted = JSONObject.quote(blobUrl)
        val js = """
            (function(){
              var k='__tsuzuri_blob_'+Date.now()+'_'+Math.floor(Math.random()*1e6);
              window[k]=null;
              fetch($quoted).then(function(r){return r.blob()}).then(function(b){
                if(b.size>$MAX_BYTES){window[k]='ERR';return;}
                var fr=new FileReader();
                fr.onloadend=function(){window[k]=typeof fr.result==='string'?fr.result:'ERR';};
                fr.onerror=function(){window[k]='ERR';};
                fr.readAsDataURL(b);
              }).catch(function(){window[k]='ERR';});
              return k;
            })()
        """.trimIndent()
        try {
            evalJs(wv, js) { rawKey ->
                val key = runCatching { JSONTokener(rawKey).nextValue() as? String }.getOrNull()
                if (key.isNullOrBlank()) {
                    done(null)
                    return@evalJs
                }
                pollBlob(wv, key, 0, done)
            }
        } catch (_: Throwable) {
            done(null)
        }
    }

    private fun pollBlob(wv: View, key: String, attempt: Int, done: (ByteArray?) -> Unit) {
        if (attempt > 12) {
            done(null)
            return
        }
        wv.postDelayed({
            try {
                val qk = JSONObject.quote(key)
                evalJs(wv, "(function(){var v=window[$qk];if(v!==null&&v!==undefined){delete window[$qk];return v;}return null;})()") { raw ->
                    val v = runCatching { if (raw == null || raw == "null") null else JSONTokener(raw).nextValue() as? String }.getOrNull()
                    when {
                        v == null -> pollBlob(wv, key, attempt + 1, done)
                        v == "ERR" || !v.startsWith("data:") -> done(null)
                        else -> {
                            val comma = v.indexOf(',')
                            val bytes = if (comma > 0 && v.substring(0, comma).endsWith(";base64")) {
                                runCatching { Base64.decode(v.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
                            } else {
                                null
                            }
                            done(bytes)
                        }
                    }
                }
            } catch (_: Throwable) {
                done(null)
            }
        }, 35L)
    }

    /**
     * 后台线程调用：取 [src] 的原文件字节，到 [deadline]（uptime）还没取完就放弃。
     * [ua]、[referer] 要在主线程预先读好（WebView 的方法只能在主线程调）。
     */
    fun download(src: String, ua: String?, referer: String?, deadline: Long, watchdog: Handler): ByteArray? {
        if (src.startsWith("data:")) {
            val comma = src.indexOf(',')
            if (comma < 0 || !src.substring(0, comma).endsWith(";base64")) return null
            return runCatching { Base64.decode(src.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
        }
        var url = URL(src)
        repeat(MAX_REDIRECTS + 1) {
            val r = fetchOnce(url, ua, referer, deadline, watchdog) ?: return null
            if (r.bytes != null) return r.bytes
            // 手动跟随跳转：每一跳都重新做地址检查
            url = r.redirect ?: return null
        }
        return null
    }

    private class Hop(val bytes: ByteArray?, val redirect: URL?)

    private fun fetchOnce(url: URL, ua: String?, referer: String?, deadline: Long, watchdog: Handler): Hop? {
        if (url.protocol != "http" && url.protocol != "https") return null
        // 与传送门「完整媒体」同一条规矩：不访问本机与局域网地址
        val addrs = runCatching { java.net.InetAddress.getAllByName(url.host) }.getOrNull() ?: return null
        if (addrs.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }) return null
        val left = deadline - SystemClock.uptimeMillis()
        if (left <= 0) return null
        val conn = url.openConnection() as HttpURLConnection
        // 超时只管单次连接 / 单次读；总时限靠到点断开
        val cut = Runnable { runCatching { conn.disconnect() } }
        watchdog.postDelayed(cut, left)
        return try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = left.toInt()
            conn.readTimeout = left.toInt()
            ua?.let { conn.setRequestProperty("User-Agent", it) }
            referer?.let { conn.setRequestProperty("Referer", it) }
            runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()?.let { conn.setRequestProperty("Cookie", it) }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return null
                return Hop(null, runCatching { URL(url, loc) }.getOrNull() ?: return null)
            }
            if (code !in 200..299) return null
            if (conn.contentLengthLong > MAX_BYTES) return null
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val chunk = ByteArray(64 shl 10)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) return null
                }
            }
            Hop(buf.toByteArray(), null)
        } catch (t: Throwable) {
            null
        } finally {
            watchdog.removeCallbacks(cut)
            runCatching { conn.disconnect() }
        }
    }

    /** 只读尺寸，不解码像素；不是位图格式（如 SVG）返回 null。 */
    fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** 屏幕上可见、且不支持 `evaluateJavascript` 的自绘 WebView 类名，给日志用。 */
    fun foreignWebViewName(v: View): String? {
        if (isSupportedWebView(v)) return null
        val n = v.javaClass.name
        return if (n.contains("WebView", ignoreCase = true)) n else null
    }
}
