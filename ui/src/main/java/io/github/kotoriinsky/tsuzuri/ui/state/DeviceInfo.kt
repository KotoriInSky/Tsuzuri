package io.github.kotoriinsky.tsuzuri.ui.state

import android.os.Build

/**
 * 设备/系统的**展示名**，只用于首页底部信息卡。
 *
 * Build.MODEL 在澎湃上是内部型号（`Xiaomi 25098PN5AC`），而 KernelSU 首页显示的是
 * 市场名（`Xiaomi 17 Pro`）—— 那个来自 `ro.product.marketname`。这是隐藏 API，
 * 只能反射读；读不到就退回 Build.MODEL，绝不因为一个展示字段崩掉。
 */
object DeviceInfo {

    private fun prop(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 市场名优先；没有再退回厂商 + 型号。 */
    val deviceName: String
        get() = prop("ro.product.marketname")
            ?: listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")

    /** 系统名：优先澎湃自己的版本名，再拼 Android 版本号。 */
    val osName: String
        get() {
            val android = "Android " + Build.VERSION.RELEASE
            val mi = prop("ro.mi.os.version.name")
            return if (mi.isNullOrBlank()) android else "$mi · $android"
        }
}
