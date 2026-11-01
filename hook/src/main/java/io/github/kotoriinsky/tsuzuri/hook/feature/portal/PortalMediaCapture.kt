package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract
import io.github.kotoriinsky.tsuzuri.core.PortalMediaRules
import io.github.kotoriinsky.tsuzuri.core.compat.PortalMediaPageCompat
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalMediaHostCompat
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalMediaParcelCompat
import io.github.kotoriinsky.tsuzuri.hook.compat.PortalCommentMediaParcelCompat
import java.net.URI

/** 服务端下发纯字段规则，消费端复用图片卡；不注入第三方应用。 */
object PortalMediaCapture : Feature(Catalog.require(Catalog.SCREEN_IMAGE)) {

    override val installId: String get() = "screen_image:original_media"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = !isSystemServer && (processName == io.github.kotoriinsky.tsuzuri.core.ScopeTarget.CONTENT_CATCHER.packageName || processName == io.github.kotoriinsky.tsuzuri.core.ScopeTarget.CONTENT_EXTENSION.packageName)

    override fun wanted(prefs: io.github.kotoriinsky.tsuzuri.core.Prefs) = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.on(prefs, Catalog.SCREEN_IMAGE, "original_media")
    override fun install(ctx: HookContext): InstallReport {
        val server = ctx.processName == "com.miui.contentcatcher"
        val host = try { PortalMediaHostCompat(ctx.classLoader, server) } catch (e: Exception) {
            ctx.log(Log.WARN, "原媒体宿主结构不兼容", e)
            return InstallReport(0, missing = listOf("PortalMediaHostCompat"))
        }
        if (server) {
            val pageCompat = PortalMediaPageCompat.resolve(ctx.classLoader)
            track(ctx.module.hook(requireNotNull(host.pageConfig)).intercept { chain ->
                val original = chain.proceed()
                try {
                    val page = host.page(requireNotNull(chain.args.firstOrNull()))
                    val rules = PortalMediaRules.forPage(page.pkg, page.version, page.activity)
                    val out = rules?.let { pageCompat.augment(original, it) }
                    if (out != null) ctx.log(Log.INFO, "已补媒体采集规则：views=${rules.size} plans=${PortalMediaRules.compiled(page.activity).size} version=${page.version}")
                    out ?: original
                } catch (e: Exception) { ctx.log(Log.WARN, "媒体规则不兼容，保持 ROM 配置", e); original }
            })
            track(ctx.module.hook(requireNotNull(host.contentEvent)).intercept { chain ->
                try {
                    val content = requireNotNull(chain.args.firstOrNull())
                    val page = host.contentPage(content)
                    val bundles = host.contentBundles(content)
                    host.markWebImage(content, bundles)
                    bundles.forEach {
                        if (page?.pkg == "com.xingin.xhs") {
                            val (accepted, discarded) = PortalMediaParcelCompat.normalize(it)
                            if (accepted + discarded > 0) ctx.log(Log.INFO,
                                "媒体原始模型转换：可用=$accepted 不兼容=$discarded")
                        }
                        if (page?.pkg == "com.ss.android.ugc.aweme") {
                            val (accepted, discarded) = PortalCommentMediaParcelCompat.normalize(it)
                            if (accepted + discarded > 0) ctx.log(Log.INFO,
                                "评论媒体快照转换：可用=$accepted 不兼容=$discarded")
                        }
                        trace(ctx, "服务接收", page, it)
                    }
                } catch (e: Exception) { ctx.log(Log.WARN, "采集结构统计失败：${e.javaClass.simpleName}") }
                chain.proceed()
            })
            return InstallReport(2, notes = listOf("rules=compiled-current-item; thirdPartyHooks=0; captureTrace=true"))
        }
        track(ctx.module.hook(requireNotNull(host.pickImage)).intercept { chain ->
            val context = chain.thisObject as Context
            if (!host.supports(context)) {
                PortalOriginalMedia.clear()
                ctx.log(Log.WARN, "原媒体宿主版本未验证，保持 ROM 图片链路")
                return@intercept chain.proceed()
            }
            try {
                val bundle = host.imageBundle(requireNotNull(chain.args.firstOrNull()))
                if (bundle != null) {
                    trace(ctx, "小窗接收", host.resultPage(requireNotNull(chain.args.firstOrNull())), bundle)
                    PortalOriginalMedia.capture(bundle, context, ctx, host, host.resultPage(requireNotNull(chain.args.firstOrNull())))
                }
                else PortalOriginalMedia.clear()
            } catch (e: Exception) { PortalOriginalMedia.clear(); ctx.log(Log.WARN, "原媒体字段解析失败", e) }
            chain.proceed()
        })
        track(ctx.module.hook(requireNotNull(host.pickText)).intercept { chain ->
            PortalOriginalMedia.clear()
            chain.proceed()
        })
        track(ctx.module.hook(requireNotNull(host.focus)).intercept { chain ->
            val result = chain.proceed()
            runCatching { PortalOriginalMedia.align(requireNotNull(chain.thisObject), host, ctx) }
                .onFailure { ctx.log(Log.WARN, "选区定位失败，保留 ROM 范围", it) }
            result
        })
        track(ctx.module.hook(requireNotNull(host.prepare)).intercept { chain ->
            val result = chain.proceed()
            runCatching { PortalOriginalMedia.screenPrepared(requireNotNull(chain.thisObject), host) }
                .onFailure { ctx.log(Log.WARN, "截图备选不可用", it) }
            result
        })
        track(ctx.module.hook(requireNotNull(host.show)).intercept { chain ->
            val service = requireNotNull(chain.thisObject)
            val mode = chain.args[0] as Boolean
            if (PortalOriginalMedia.deferShow { requireNotNull(host.show).invoke(service, mode) }) null
            else { PortalOriginalMedia.beforeShow(); chain.proceed() }
        })
        track(ctx.module.hook(requireNotNull(host.selectionRefresh)).intercept { chain ->
            if (chain.args[1] == true && PortalOriginalMedia.hasSession()) {
                val view = chain.thisObject as? View
                if (view != null) PortalOriginalMedia.reselect(view.context, host, ctx)
            }
            chain.proceed()
        })
        track(ctx.module.hook(requireNotNull(host.refresh)).intercept { chain ->
            val result = chain.proceed()
            try { (chain.thisObject as? View)?.let { PortalOriginalMedia.refresh(it, host, ctx) } }
            catch (e: Exception) { ctx.log(Log.WARN, "媒体预览更新失败", e) }
            result
        })
        track(ctx.module.hook(requireNotNull(host.save)).intercept { chain ->
            val context = (chain.thisObject as? View)?.context
            if (context != null && PortalOriginalMedia.save(context, ctx)) null else chain.proceed()
        })
        track(ctx.module.hook(requireNotNull(host.cancel)).intercept { chain ->
            PortalOriginalMedia.clear()
            chain.proceed()
        })
        ctx.log(Log.INFO, "原媒体小窗已接入：hooked=9 deadline=3000 bitmap=true region=true display=after_capture")
        return InstallReport(9)
    }
    /** 只记录结构、已声明路径和地址来源，不记录地址正文、笔记内容或完整规则。 */
    private fun trace(ctx: HookContext, phase: String, page: PortalMediaHostCompat.Page?, bundle: Bundle) {
        val keys = bundle.keySet()
        val bitmap = bundle.getParcelable("viewBitmap", Bitmap::class.java)
        ctx.log(Log.INFO, "采集结构：阶段=$phase 包=${page?.pkg} 页=${page?.activity} 版本=${page?.version}" +
            " 字段规则=${bundle.containsKey("apkParamList")} 规则项=${bundle.getStringArray("apkParamList")?.size ?: 0}" +
            " 路径键=${keys.count { '/' in it }} 位图=${bundle.containsKey("viewBitmap")} 地址=${bundle.containsKey("imgUrl")}" +
            " 类型=${bundle.getInt("catchType", 0)} 位图尺寸=${bitmap?.width ?: 0}x${bitmap?.height ?: 0}")
        val rules = page?.let { PortalMediaRules.forPage(it.pkg, it.version, it.activity) } ?: return
        val knownPaths = rules.values.flatMap { it.split(',') }.toSet()
        keys.filter { '/' in it && it in knownPaths }.sorted().take(24).forEach { key ->
            val value = bundle.get(key)
            val raw = when (value) {
                is CharSequence -> value.toString()
                is Uri -> value.toString()
                else -> null
            }
            val uri = raw?.let { runCatching { URI(it) }.getOrNull() }
            val scheme = uri?.scheme?.takeIf { it in listOf("http", "https", "file", "content", "res", "asset", "data") }
                ?: if (uri?.scheme != null) "其它" else "无"
            // 仅网络地址记录 DNS 主机，路径、查询参数、JSON 和本地文件名均不输出。
            val host = uri?.host?.takeIf { scheme in listOf("http", "https") } ?: "无"
            ctx.log(Log.INFO, "媒体字段：阶段=$phase 路径=$key 类型=${value?.javaClass?.name}" +
                " 长度=${raw?.length ?: -1} 协议=$scheme 主机=$host" +
                " 可下载=${raw?.let(PortalMediaContract::allowedUrl) ?: false}" +
                " JSON=${raw?.trimStart()?.startsWith('{') ?: false}")
        }
    }
    override fun uninstall() { PortalOriginalMedia.clear(); super.uninstall() }
}
