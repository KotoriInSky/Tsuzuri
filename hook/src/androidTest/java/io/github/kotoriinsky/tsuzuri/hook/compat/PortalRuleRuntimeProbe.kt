package io.github.kotoriinsky.tsuzuri.hook.compat

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.view.View
import io.github.kotoriinsky.tsuzuri.core.PortalMediaRules
import io.github.kotoriinsky.tsuzuri.core.PortalDouyinRules
import io.github.kotoriinsky.tsuzuri.core.PortalWeiboRules
import io.github.kotoriinsky.tsuzuri.core.PortalRuleCompiler
import io.github.kotoriinsky.tsuzuri.core.PortalRuleBindings
import io.github.kotoriinsky.tsuzuri.core.PortalRuleImporter
import io.github.kotoriinsky.tsuzuri.core.PortalRuleMedia
import io.github.kotoriinsky.tsuzuri.core.PortalRuleProgram as P
import io.github.kotoriinsky.tsuzuri.core.PortalRuleSyntax
import org.json.JSONObject
import org.json.JSONArray

/** 使用原装采集器和生产解析器验证编译计划；所有媒体地址均为不下载的合成值。 */
object PortalRuleRuntimeProbe {
    private const val PHOTO = "https://img.xhscdn.com/probe.gif?sign=a%2Fb"
    private const val VIDEO = "https://video.xhscdn.com/probe.mp4?sign=a%2Fb"
    class Model(@JvmField val photos: ArrayList<String>, @JvmField val videoInfoJson: String)
    class SourceView(context: Context, @JvmField val model: Model) : View(context)
    private fun steps(vararg raw: String) = raw.map(PortalRuleSyntax::parse)
    fun verify(context: Context) {
        val cases = linkedMapOf<String, () -> Unit>(
            "编译字段经原装提取和Parcel后配对" to { transfer(context) },
            "生产接收端选择当前项GIF来源" to {
                val model = PortalMediaRules.imageModels.first()
                val packet = Bundle().apply {
                    putString(PortalMediaRules.modelPath(model, "url_anim"), PHOTO)
                    putString(PortalMediaRules.modelPath(PortalMediaRules.imageModels[1], "livePhotoUrl"), VIDEO)
                }
                val result = checkNotNull(PortalRuleRuntimeCompat.pick(packet,
                    PortalMediaHostCompat.Page("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE)))
                check(result.source.image == PHOTO && result.source.video == null && !result.live)
            },
            "外部规则载荷保持页面和非字段操作" to {
                val pages = PortalRuleRuntimeCompat.pages("""[{"name":"Synthetic.Page","visible_images":[{"visible_view":{"params":["Synthetic.Image"]},"objects":[],"target":{"object":{"name":"${'$'}item","root":"${'$'}visibleView","params":["getAdapter()"]},"pic_url":[{"params":["url"]}]}}]}]""")
                val imported = PortalRuleImporter.importPages("synthetic.app", checkNotNull(pages))
                check(imported.issues.isEmpty() && imported.rules.size == 1)
                val result = PortalRuleCompiler.compile(imported.rules.single(), emptyMap())
                check(result.plan == null && result.issues.single().operation == "getAdapter")
            },
            "快照解析拒绝错误格式并保留签名" to {
                check(PortalRuleRuntimeCompat.tree("not json") == null)
                check(PortalRuleRuntimeCompat.pages("""{"code":500,"value":"[]"}""") == null)
                check((PortalRuleRuntimeCompat.tree("""{"url":"$PHOTO"}""") as Map<*, *>)["url"] == PHOTO)
            },
            "生产实况计划执行快照字段映射与JSON索引" to {
                val model = PortalMediaRules.imageModels.first()
                val packet = Bundle().apply {
                    putString(PortalMediaRules.modelPath(model, "original"), PHOTO)
                    putString(PortalMediaRules.modelPath(model, "livePhoto"),
                        """{"stream":{"h264":[{"master_url":"$VIDEO"}]}}""")
                }
                val result = checkNotNull(PortalRuleRuntimeCompat.pick(packet,
                    PortalMediaHostCompat.Page("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE)))
                check(result.source.image == PHOTO && result.source.video == VIDEO && result.live)
            },
            "生产接收端执行抖音同项URL列表规则" to {
                val model = PortalDouyinRules.models[2]
                fun field(leaf: String) = PortalRuleSyntax.fields("$model.$leaf".split('.'))
                val image = "https://p3.douyinpic.com/probe.jpeg"
                val video = "https://v3.douyinvod.com/probe.mp4"
                val packet = Bundle().apply {
                    putStringArrayList(field("urlList"), arrayListOf(image))
                    putStringArrayList(field("video.playAddr.urlList"), arrayListOf(video))
                }
                val result = checkNotNull(PortalRuleRuntimeCompat.pick(packet,
                    PortalMediaHostCompat.Page(PortalDouyinRules.PACKAGE, 310703, "com.ss.android.ugc.aweme.splash.SplashActivity")))
                check(result.source.image == image && result.source.video == video && result.live)
                // 包名不对应时不能误用同名 Activity 的媒体计划。
                check(PortalRuleRuntimeCompat.pick(packet,
                    PortalMediaHostCompat.Page("other.app", 1, "com.ss.android.ugc.aweme.splash.SplashActivity")) == null)
            },
            "生产接收端保留微博原GIF而非播放视频" to {
                val model = "mParent.r.picInfo"
                fun field(leaf: String) = PortalRuleSyntax.fields("$model.$leaf".split('.'))
                val image = "https://wx1.sinaimg.cn/large/probe.gif"
                val packet = Bundle().apply {
                    putString("apkName", PortalWeiboRules.PACKAGE)
                    putString(field("original.url"), image)
                    putString(field("video"), "https://f.video.weibocdn.com/gif.mp4")
                    putString(field("type"), "gif")
                }
                val result = checkNotNull(PortalRuleRuntimeCompat.pick(packet, null))
                check(result.source.image == image && result.source.video == null && !result.live)
            },
        )
        var failures = 0
        for ((name, test) in cases) try { test(); println("PASS $name") } catch (error: Throwable) {
            failures++; println("FAIL $name: ${error.javaClass.simpleName}: ${error.message}")
        }
        println("RESULT runtimeRuleTests=${cases.size} failures=$failures origin=synthetic")
        check(failures == 0)
    }
    /** 读取真实 Binder 返回值，确认生产页面配置包含来源字段；不操作来源 App。 */
    fun configs(request: PortalMediaHostCompat.Page? = null) {
        val token = Class.forName("miui.contentcatcher.sdk.Token")
        val managerType = Class.forName("miui.contentcatcher.sdk.ContentCatcherManager")
        val manager = managerType.getMethod("getInstance").invoke(null)
        val get = managerType.getMethod("getPageConfig", token)
        val requests = request?.let(::listOf) ?: listOf(
            PortalMediaHostCompat.Page("com.xingin.xhs", 9481803, PortalMediaRules.NOTE_PAGE),
            PortalMediaHostCompat.Page("com.xingin.xhs", 9481803, PortalMediaRules.COMMENT_PAGE),
            PortalMediaHostCompat.Page(PortalDouyinRules.PACKAGE, 310703, "com.ss.android.ugc.aweme.splash.SplashActivity"),
            PortalMediaHostCompat.Page(PortalWeiboRules.PACKAGE, 8166, PortalWeiboRules.PREVIEW_PAGE))
        for (page in requests) {
            val value = token.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType)
                .newInstance(page.pkg, page.activity, page.version)
            val config = get.invoke(manager, value)
            if (request != null) {
                println("PAGE package=${page.pkg} page=${page.activity} present=${config != null}")
                if (config != null) {
                    val features = config.javaClass.getMethod("getFeatures").invoke(config) as List<*>
                    val catchers = config.javaClass.getMethod("getCatchers").invoke(config) as List<*>
                    println("PAGE features=${features.size} catchers=${catchers.size}")
                    for (feature in features.filterNotNull()) {
                        val type = feature.javaClass
                        val params = type.getField("mParams").get(feature) as Map<*, *>
                        println("FEATURE name=${type.getField("name").get(feature)} enabled=${type.getField("enable").get(feature)}" +
                            " target=${type.getField("target").get(feature)} catchers=${params["catchers"]}" +
                            " fields=${(params["reflectioninfo"] as? Map<*, *>)?.size ?: 0}")
                    }
                }
                continue
            }
            checkNotNull(config)
            val features = config.javaClass.getMethod("getFeatures").invoke(config) as List<*>
            val pick = features.single { it!!.javaClass.getField("name").get(it) == "pick_mode" }!!
            val params = pick.javaClass.getField("mParams").get(pick) as Map<*, *>
            val actual = params["reflectioninfo"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val expected = checkNotNull(PortalMediaRules.forPage(page.pkg, page.version, page.activity))
            val matched = expected.count { (view, fields) ->
                val present = (actual[view] as? String)?.split(',').orEmpty().toSet()
                fields.split(',').all { it in present }
            }
            println("CONFIG package=${page.pkg} page=${page.activity} expected=${expected.size} matched=$matched")
            check(matched == expected.size) { "生产 Binder 配置未完整下发" }
        }
    }
    private fun transfer(context: Context) {
        val rule = P.Rule("probe", "synthetic.app", listOf("Synthetic.Page"), emptyList(), emptyList(),
            P.Node("\$item", "\$visibleView", emptyList()), listOf(
                P.Output(P.Kind.PHOTO, steps("photos", "[last]")),
                P.Output(P.Kind.LIVE, steps("videoInfoJson", "[toJson]", "stream", "h264", "[0]", "master_url"))))
        val plan = checkNotNull(PortalRuleCompiler.compile(rule,
            mapOf("\$item" to P.Binding(listOf("model"), "selected")),
            mapOf(listOf("model", "photos") to P.Codec.STRING_LIST, listOf("model", "videoInfoJson") to P.Codec.JSON)).plan)
        val view = SourceView(context, Model(arrayListOf("https://img.xhscdn.com/cover.jpg", PHOTO),
            """{"stream":{"h264":[{"master_url":"$VIDEO"}]}}""")).apply { layout(0, 0, 200, 200) }
        val loader = context.createPackageContext("com.miui.contentcatcher", 3).classLoader
        val type = Class.forName("com.miui.catcherpatch.ViewContentFetcher", true, loader)
        val tokenType = Class.forName("miui.contentcatcher.sdk.Token", false, loader)
        val token = tokenType.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .newInstance(rule.pkg, rule.pages.single(), 1)
        val fetcher = type.getConstructor().newInstance()
        type.getMethod("createFetcher", tokenType, Map::class.java).invoke(fetcher, token,
            mapOf(view.javaClass.name to (listOf("viewBitmap") + plan.fields).joinToString(",")))
        val before = type.getMethod("fetchViewContent", View::class.java, Int::class.javaPrimitiveType).invoke(fetcher, view, 2) as Bundle
        val parcel = Parcel.obtain()
        try {
            before.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            val packet = Bundle.CREATOR.createFromParcel(parcel)
            val values = plan.fields.associateWith { packet.get(it) }
            val result = checkNotNull(PortalRuleMedia.pick(listOf(plan), values, PortalRuleRuntimeCompat::tree))
            check(result.source.image == PHOTO && result.source.video == VIDEO && result.live)
        } finally { parcel.recycle() }
    }
    fun audit(pkg: String, raw: String) {
        val pages = checkNotNull(PortalRuleRuntimeCompat.pages(raw)) { "规则载荷格式不兼容" }
        val imported = PortalRuleImporter.importPages(pkg, pages)
        val fieldOnly = imported.rules.map { PortalRuleCompiler.compile(it, emptyMap()) }
        val compiled = imported.rules.map(PortalRuleBindings::translate)
        val issues = imported.issues + compiled.flatMap { it.issues }
        val operators = (imported.rules.flatMap { rule -> rule.nodes.flatMap { it.steps } + rule.target.steps +
            rule.outputs.flatMap { it.steps } + rule.transformers.values.flatten() }).groupingBy { it.name }.eachCount()
        val report = JSONObject().put("package", pkg).put("pages", pages.size).put("rules", imported.rules.size)
            .put("batch", imported.rules.count { it.batch }).put("importIssues", imported.issues.size)
            .put("fieldOnlyPlans", fieldOnly.count { it.plan != null })
            .put("translatedPlans", compiled.count { it.plan != null }).put("needsBinding", compiled.count { it.plan == null })
            .put("operators", JSONObject(operators)).put("issues", JSONObject(issues.groupingBy { it.operation }.eachCount()))
        report.put("entries", JSONArray(imported.rules.zip(compiled).map { (rule, result) ->
            JSONObject().put("id", rule.id).put("pages", JSONArray(rule.pages)).put("views", JSONArray(rule.views))
                .put("batch", rule.batch).put("fields", JSONArray(result.plan?.fields.orEmpty()))
                .put("diagnostics", JSONArray(result.issues.map { JSONObject().put("operation", it.operation).put("reason", it.reason) }))
        }))
        println("RULE_AUDIT $report")
        check(imported.issues.isEmpty()) { "规则结构有未支持条目" }
    }
}
