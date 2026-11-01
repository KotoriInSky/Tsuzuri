package io.github.kotoriinsky.tsuzuri.hook.compat

import android.app.Application
import android.content.Context
import org.json.JSONObject

/** 小爱 4.12.26 的胶囊入口；厂商符号集中在此处。 */
class ContextCapsuleCompat private constructor(private val loader: ClassLoader, val context: Context) {
    private fun clazz(name: String): Class<*> = listOf(name, "defpackage.$name").firstNotNullOfOrNull {
        runCatching { Class.forName(it, false, loader) }.getOrNull()
    } ?: error("缺少符号 $name")

    private val cueClass = Class.forName("com.xiaomi.ai.bubble.core.model.CueData", false, loader)
    private val managerClass = clazz("um0")
    private val queueClass = clazz("ql0")
    private val fromJson = cueClass.getMethod("fromJson", String::class.java)
    private val show = managerClass.getDeclaredMethod("l", List::class.java)
    val foregroundMethod = managerClass.getDeclaredMethod("a", cueClass).apply { isAccessible = true }
    val clickMethod = managerClass.getDeclaredMethod("b", String::class.java).apply { isAccessible = true }
    private val cueId = cueClass.getMethod("getCueId")

    fun id(cue: Any?) = cue?.let { cueId.invoke(it) as? String }

    /** 胶囊收起态的长按（BubbleView 构造时挂的 jm0，分支 0）；小爱用它弹「为什么推荐」。 */
    val longClickMethod = clazz("jm0").getDeclaredMethod("onLongClick", android.view.View::class.java)

    /** 字段名被混淆，按类型取：int 是分支号，KeyEvent.Callback 是所属视图。只在长按时解析，失败不影响其他场景。 */
    fun longClickCueId(listener: Any?): String? {
        if (listener == null) return null
        val fields = listener.javaClass.declaredFields.onEach { it.isAccessible = true }
        val branch = fields.single { it.type == Int::class.javaPrimitiveType }
        val owner = fields.single { it.type == android.view.KeyEvent.Callback::class.java }
        if (branch.getInt(listener) != 0) return null
        val bubble = owner.get(listener) ?: return null
        if (bubble.javaClass.name != "com.xiaomi.ai.bubble.core.bubbleview.view.BubbleView") return null
        return id(bubble.javaClass.getMethod("getCueData").invoke(bubble))
    }

    private fun manager(): Any? = queueClass.getField("f").get(null)?.let { queueClass.getField("e").get(it) }

    private fun readyManager(): Any {
        manager()?.let { return it }
        val initializer = Class.forName("com.xiaomi.ai.bubble.core.bridge.a", false, context.classLoader)
        val proxy = initializer.getDeclaredMethod("a", Context::class.java).invoke(null, context)
        val processor = proxy.javaClass.getDeclaredField("perceptionProcessor").apply { isAccessible = true }.get(proxy)
        val processorClass = clazz("cm0")
        return processorClass.getDeclaredMethod("a", processorClass).invoke(null, processor)
            ?: error("原生胶囊未就绪")
    }

    /** [iconDrawable] 为「包名:资源名」，走小爱 IconLoader 的 pkg_drawable；与 [iconPackage] 二选一。 */
    fun show(id: String, title: String, source: String, iconPackage: String?, iconDrawable: String? = null) {
        val card = JSONObject().put("title", JSONObject().put("text", title))
        if (iconPackage != null) card.put("startIcon", JSONObject().put("type", "application").put("value", iconPackage))
        else if (iconDrawable != null) card.put("startIcon", JSONObject().put("type", "pkg_drawable").put("value", iconDrawable))
        val display = JSONObject().put("briefCard", card).put("expandable", false)
            .put("position", JSONObject().put("category", "global"))
            .put("settings", JSONObject().put("source_package", source))
        val data = JSONObject().put("cueId", id).put("eventId", id).put("category", id)
            .put("type", "action").put("aliveTime", 10).put("display", display).put("support_aggregate", false)
        show.invoke(readyManager(), listOf(fromJson.invoke(null, data.toString())))
    }

    fun dismiss(id: String) {
        val current = manager() ?: return
        val state = managerClass.getField("e").get(current)
        state.javaClass.getDeclaredMethod("p", String::class.java).invoke(state, id)
    }

    /** 小爱在 cognitionService 里算完候选后构造这个事件；意图模型 z45 在主进程，这里拦不到它。 */
    fun musicConstructor(): java.lang.reflect.Constructor<*> =
        Class.forName("com.xiaomi.aireco.entity.event.cognition.ListenAudioEvent", false, loader)
            .getDeclaredConstructor(String::class.java, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)

    fun musicEvent(event: Any?): Pair<Boolean, String>? {
        if (event?.javaClass?.name != "com.xiaomi.aireco.entity.event.cognition.ListenAudioEvent") return null
        return (event.javaClass.getMethod("getStartListen").invoke(event) as Boolean) to
            (event.javaClass.getMethod("getPredictApp").invoke(event) as String)
    }

    companion object {
        fun create(loader: ClassLoader): ContextCapsuleCompat {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo("com.xiaomi.aicr", 0)
            check(version.longVersionCode == 2030041226L && version.versionName == "4.12.26")
            val os = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
                .invoke(null, "ro.mi.os.version.code", "0") as String
            check(os.toFloatOrNull() == 4f)
            return ContextCapsuleCompat(loader, app)
        }
    }
}
