package io.github.kotoriinsky.tsuzuri.core.compat

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.HashMap
import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract

/** 媒体规则必须进入 pick_mode；image_pick_mode 不负责创建字段抓取器。 */
class PortalMediaPageCompat private constructor(
    private val pageClass: Class<*>, private val infoClass: Class<*>,
    private val features: Method, private val catchers: Method,
    private val pageCtor: Constructor<*>, private val infoCtor: Constructor<*>,
    private val infoFields: List<Field>, private val name: Field,
    private val enable: Field, private val target: Field, private val params: Field,
    private val catcherNames: Method,
) {
    fun augment(original: Any?, rules: Map<String, String>): Any? {
        if (!pageClass.isInstance(original) || rules.isEmpty()) return null
        val list = features.invoke(original) as? Collection<*> ?: return null
        if (list.any { !infoClass.isInstance(it) }) return null
        val pick = list.singleOrNull { name.get(it) == "pick_mode" } ?: return null
        val image = list.singleOrNull { name.get(it) == "image_pick_mode" } ?: return null
        if (!enable.getBoolean(pick) || !enable.getBoolean(image) ||
            listOf(pick, image).any { target.get(it) != "com.miui.contentextension" }) return null
        val pickParams = params.get(pick) as? Map<*, *> ?: return null
        val reflection = pickParams["reflectioninfo"]
        if (reflection != null && reflection !is Map<*, *>) return null
        val existing = (reflection as? Map<*, *>).orEmpty()
        if (existing.any { (key, value) -> key !is String || value !is String }) return null
        val copies = ArrayList<Any>()
        for (info in list) {
            val copy = infoCtor.newInstance()
            for (field in infoFields) field.set(copy, field.get(info))
            if (info === pick) {
                val updated = HashMap<String, Any>()
                pickParams.forEach { (k, v) -> if (k is String && v != null) updated[k] = v }
                updated["reflectioninfo"] = HashMap<String, String>().apply {
                    existing.forEach { (k, v) -> if (k is String && v is String) put(k, v) }
                    rules.forEach { (view, fields) ->
                        // 已有同名控件不能让整页新增规则失效。保留旧地址字段，但去掉会
                        // 提前返回截图 Bundle 的 Bitmap 指令；新规则已显式提供底图字段。
                        val old = (existing[view] as? String).orEmpty().split(',').filter { entry ->
                            entry.isNotBlank() && entry != "conversionBitmap" &&
                                entry.split('/').filterIndexed { index, _ -> index % 2 == 1 }.none { it == "Bitmap" }
                        }
                        put(view, PortalMediaContract.bitmapFieldRule(fields.split(',') + old))
                    }
                }
                // 空字符串会返回 null，让 ROM 跳过 createFetcher。仅分隔符经 Java split
                // 得到非 null 空列表，可初始化字段采集器，同时不设置文本位。
                updated["catchers"] = ","
                params.set(copy, updated)
                if ((catcherNames.invoke(copy) as? Collection<*>)?.isEmpty() != true) return null
            }
            copies.add(copy)
        }
        val originalCatchers = catchers.invoke(original) as? Collection<*> ?: return null
        return pageCtor.newInstance(copies, ArrayList(originalCatchers))
    }
    companion object {
        fun resolve(loader: ClassLoader, pageName: String = "miui.contentcatcher.sdk.data.PageConfig",
            infoName: String = "miui.contentcatcher.sdk.data.FeatureInfo"): PortalMediaPageCompat {
            val page = Class.forName(pageName, false, loader)
            val info = Class.forName(infoName, false, loader)
            val fields = info.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
                .onEach { it.isAccessible = true }
            fun field(n: String) = fields.single { it.name == n }
            return PortalMediaPageCompat(page, info,
                page.getMethod("getFeatures").apply { isAccessible = true },
                page.getMethod("getCatchers").apply { isAccessible = true },
                page.declaredConstructors.single { it.parameterTypes.size == 2 &&
                    it.parameterTypes.all { t -> t.isAssignableFrom(ArrayList::class.java) } }.apply { isAccessible = true },
                info.getDeclaredConstructor().apply { isAccessible = true }, fields,
                field("name"), field("enable"), field("target"), field("mParams"),
                info.getMethod("getCatcherNameList").apply { isAccessible = true })
        }
    }
}
