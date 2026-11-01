package io.github.kotoriinsky.tsuzuri.hook.compat

import android.os.Bundle
import android.os.Parcel

/** 将已核对协议的媒体 Parcelable 转成 JSON；不加载来源 APK，也不执行其 CREATOR。 */
object PortalMediaParcelCompat {
    private const val VIDEO_INFO = "com.xingin.entities.video.VideoInfoV2"
    private const val LIMIT = 512 * 1024
    private val mapField by lazy {
        Class.forName("android.os.BaseBundle").getDeclaredField("mMap").apply { isAccessible = true }
    }

    /** 返回可用 JSON 数与丢弃数。失败时移除本模块新增的未知 Parcelable，保留其它采集字段。 */
    fun normalize(bundle: Bundle): Pair<Int, Int> {
        val keys = bundle.keySet().filter { it.endsWith("/imageBean/Object/livePhoto/String") ||
            it.endsWith("/controller/Object/n/Object/livePhoto/String") }
        if (keys.isEmpty()) return 0 to 0
        var accepted = 0
        for (key in keys) {
            val json = runCatching {
                val raw = (mapField.get(bundle) as? Map<*, *>)?.get(key) ?: return@runCatching null
                leadingJson(raw)
            }.getOrNull()
            bundle.remove(key)
            if (json != null) { bundle.putString(key, json); accepted++ }
        }
        return accepted to (keys.size - accepted)
    }

    private fun leadingJson(raw: Any): String? {
        if (raw is String) return raw.takeIf { it.length <= 256 * 1024 && it.trimStart().startsWith('{') }
        val parcel = Parcel.obtain()
        return try {
            // writeValue 识别 Parcel.LazyValue，复制原始值而不触发第三方 Parcelable 反序列化。
            parcel.writeValue(raw)
            if (parcel.dataSize() !in 12..LIMIT) return null
            parcel.setDataPosition(0)
            if (parcel.readInt() != 4) return null // Android 的 VAL_PARCELABLE。
            val length = parcel.readInt()
            if (length < 8 || length != parcel.dataAvail()) return null
            if (parcel.readString() != VIDEO_INFO) return null
            // 已核对当前 APK 的 writeToParcel：第一项就是 Gson 编码的完整 media JSON。
            parcel.readString()?.takeIf { it.length <= 256 * 1024 && it.trimStart().startsWith('{') }
        } finally { parcel.recycle() }
    }
}
