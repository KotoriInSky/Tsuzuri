package io.github.kotoriinsky.tsuzuri.core.compat

import io.github.kotoriinsky.tsuzuri.core.PortalPageAdmission
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.HashMap

/**
 * `com.miui.contentcatcher` 页面配置对象的反射适配器。
 *
 * 厂商类型只在这里出现为类名与 [Any]，调用侧不直接依赖 ROM SDK。解析失败由调用侧视为
 * 不兼容并停止安装；运行时结构读不清则返回不确定状态，让准入判定保持 ROM 原结果。
 */
class PortalPageConfigCompat private constructor(
    private val pageConfigClass: Class<*>,
    private val featureInfoClass: Class<*>,
    private val getFeatures: Method,
    private val getCatchers: Method,
    private val pageConfigCtor: Constructor<*>,
    private val featureInfoCtor: Constructor<*>,
    private val featureName: Field,
    private val featureTarget: Field,
    private val featureJobTag: Field,
    private val featureEnable: Field,
    private val featureParams: Field,
    private val helperSingleton: Field,
    private val helperTargets: Field,
) {

    data class ClassNames(
        val pageConfig: String = "miui.contentcatcher.sdk.data.PageConfig",
        val featureInfo: String = "miui.contentcatcher.sdk.data.FeatureInfo",
        val configHelper: String = "com.miui.contentcatcher.service.config.a",
    )

    data class PageState(
        val present: Boolean,
        val features: PortalPageAdmission.ListState,
        val catchers: PortalPageAdmission.ListState,
    )

    fun inspect(pageConfig: Any?): PageState {
        if (pageConfig == null) {
            return PageState(
                present = false,
                features = PortalPageAdmission.ListState.ABSENT,
                catchers = PortalPageAdmission.ListState.ABSENT,
            )
        }
        if (!pageConfigClass.isInstance(pageConfig)) {
            return PageState(
                present = true,
                features = PortalPageAdmission.ListState.UNREADABLE,
                catchers = PortalPageAdmission.ListState.UNREADABLE,
            )
        }
        return PageState(
            present = true,
            features = listState(getFeatures, pageConfig),
            catchers = listState(getCatchers, pageConfig),
        )
    }

    /** 只有唯一匹配的官方文本 target 才返回其状态；缺失、重复或结构异常均返回 null。 */
    fun textTargetEnabled(): Boolean? = try {
        val helper = helperSingleton.get(null) ?: return null
        val targets = helperTargets.get(helper) as? Iterable<*> ?: return null
        val matches = targets.filterNotNull().filter(::isTextTarget)
        if (matches.size != 1) return null

        val booleanFields = instanceFields(matches.single().javaClass).filter {
            it.type == Boolean::class.javaPrimitiveType || it.type == Boolean::class.javaObjectType
        }
        if (booleanFields.size != 1) return null
        booleanFields.single().apply { isAccessible = true }.get(matches.single()) as? Boolean
    } catch (_: Throwable) {
        null
    }

    fun buildTextFallback(): Any {
        val feature = featureInfoCtor.newInstance()
        featureName.set(feature, PICK_MODE)
        featureTarget.set(feature, CONTENT_EXTENSION)
        featureJobTag.set(feature, TEXT_PICK)
        featureEnable.set(feature, true)

        @Suppress("UNCHECKED_CAST")
        val params = (featureParams.get(feature) as? MutableMap<String, Any>)
            ?: HashMap<String, Any>().also { featureParams.set(feature, it) }
        params[CATCHERS_KEY] = TEXT_CATCHERS

        return pageConfigCtor.newInstance(
            arrayListOf(feature),
            ArrayList<Any>(),
        )
    }

    private fun isTextTarget(target: Any): Boolean = try {
        val strings = instanceFields(target.javaClass)
            .filter { it.type == String::class.java }
            .mapNotNull {
                it.isAccessible = true
                it.get(target) as? String
            }
        CONTENT_EXTENSION in strings && TEXT_PICK in strings
    } catch (_: Throwable) {
        false
    }

    private fun listState(method: Method, pageConfig: Any): PortalPageAdmission.ListState = try {
        when (val value = method.invoke(pageConfig)) {
            null -> PortalPageAdmission.ListState.ABSENT
            is Collection<*> -> if (value.isEmpty()) {
                PortalPageAdmission.ListState.EMPTY
            } else {
                PortalPageAdmission.ListState.NON_EMPTY
            }
            else -> PortalPageAdmission.ListState.UNREADABLE
        }
    } catch (_: Throwable) {
        PortalPageAdmission.ListState.UNREADABLE
    }

    companion object {
        private const val PICK_MODE = "pick_mode"
        private const val CONTENT_EXTENSION = "com.miui.contentextension"
        private const val TEXT_PICK = "text_pick"
        private const val CATCHERS_KEY = "catchers"
        private const val TEXT_CATCHERS = "WebViewCatcher,NativeTextCatcher"

        fun resolve(
            loader: ClassLoader,
            names: ClassNames = ClassNames(),
        ): PortalPageConfigCompat {
            val pageConfigClass = Class.forName(names.pageConfig, false, loader)
            val featureInfoClass = Class.forName(names.featureInfo, false, loader)
            val configHelperClass = Class.forName(names.configHelper, false, loader)

            val getFeatures = pageConfigClass.getMethod("getFeatures").apply { isAccessible = true }
            val getCatchers = pageConfigClass.getMethod("getCatchers").apply { isAccessible = true }
            val pageConfigCtor = pageConfigClass.declaredConstructors.singleOrNull { ctor ->
                val types = ctor.parameterTypes
                types.size == 2 &&
                    types.all { it.isAssignableFrom(ArrayList::class.java) }
            }?.apply { isAccessible = true }
                ?: error("找不到 PageConfig(ArrayList, ArrayList)")
            val featureInfoCtor = featureInfoClass.getDeclaredConstructor().apply { isAccessible = true }

            val helperSingleton = allFields(configHelperClass).singleOrNull {
                Modifier.isStatic(it.modifiers) && it.type == configHelperClass
            }?.apply { isAccessible = true }
                ?: error("找不到唯一的 ConfigHelper 单例字段")
            val helperTargets = instanceFields(configHelperClass).singleOrNull {
                Iterable::class.java.isAssignableFrom(it.type)
            }?.apply { isAccessible = true }
                ?: error("找不到唯一的 ConfigHelper target 列表")

            return PortalPageConfigCompat(
                pageConfigClass = pageConfigClass,
                featureInfoClass = featureInfoClass,
                getFeatures = getFeatures,
                getCatchers = getCatchers,
                pageConfigCtor = pageConfigCtor,
                featureInfoCtor = featureInfoCtor,
                featureName = requiredField(featureInfoClass, "name", String::class.java),
                featureTarget = requiredField(featureInfoClass, "target", String::class.java),
                featureJobTag = requiredField(featureInfoClass, "jobTag", String::class.java),
                featureEnable = requiredField(featureInfoClass, "enable", Boolean::class.javaPrimitiveType!!),
                featureParams = requiredField(featureInfoClass, "mParams", HashMap::class.java),
                helperSingleton = helperSingleton,
                helperTargets = helperTargets,
            )
        }

        private fun requiredField(owner: Class<*>, name: String, type: Class<*>): Field =
            allFields(owner).singleOrNull { it.name == name && type.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?: error("找不到 ${owner.name}.$name")

        private fun allFields(type: Class<*>): List<Field> = buildList {
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                addAll(current.declaredFields)
                current = current.superclass
            }
        }

        private fun instanceFields(type: Class<*>): List<Field> =
            allFields(type).filterNot { Modifier.isStatic(it.modifiers) }
    }
}
