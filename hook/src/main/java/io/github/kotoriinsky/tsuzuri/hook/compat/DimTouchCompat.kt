package io.github.kotoriinsky.tsuzuri.hook.compat

import android.content.Context
import android.os.Build
import io.github.kotoriinsky.tsuzuri.core.ContextSuggestion

/** 当前系统电源服务的主屏用户活动接口。 */
class DimTouchCompat private constructor(loader: ClassLoader) {
    private val service = Class.forName("com.android.server.power.PowerManagerService", false, loader)
    private val group = Class.forName("com.android.server.power.PowerGroup", false, loader)
    val activity = service.getDeclaredMethod("userActivityNoUpdateLocked", group, Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
    private val getGroup = group.getDeclaredMethod("getGroupId").apply { isAccessible = true }
    private val getSummary = group.getDeclaredMethod("getUserActivitySummaryLocked").apply { isAccessible = true }
    private val contextField = service.getDeclaredField("mContext").apply { isAccessible = true }

    fun context(instance: Any) = contextField.get(instance) as Context

    fun isDimTouch(groupObject: Any?, event: Any?): Boolean {
        if (groupObject == null || event !is Int || event != 2) return false
        return ContextSuggestion.isDimTouch(getGroup.invoke(groupObject) as Int, event, getSummary.invoke(groupObject) as Int)
    }

    companion object {
        fun create(loader: ClassLoader): DimTouchCompat {
            check(Build.VERSION.SDK_INT == 37)
            val os = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
                .invoke(null, "ro.mi.os.version.code", "0") as String
            check(os.toFloatOrNull() == 4f)
            return DimTouchCompat(loader)
        }
    }
}
