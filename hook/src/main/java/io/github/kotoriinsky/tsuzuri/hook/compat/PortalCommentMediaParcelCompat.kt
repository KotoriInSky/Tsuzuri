package io.github.kotoriinsky.tsuzuri.hook.compat

import android.os.Bundle
import android.os.Parcel
import io.github.kotoriinsky.tsuzuri.core.PortalDouyinRules
import io.github.kotoriinsky.tsuzuri.core.PortalRuleSyntax
import org.json.JSONArray

/** 将当前评论的媒体列表转成基础值，避免跨进程加载评论 Parcelable 类。 */
object PortalCommentMediaParcelCompat {
    private val fields = (PortalDouyinRules.commentModels + PortalDouyinRules.OLD_COMMENT_MODEL +
        PortalDouyinRules.listCommentModels)
        .map { PortalRuleSyntax.fields(it.split('.')) }.toSet()
    private val mapField by lazy {
        Class.forName("android.os.BaseBundle").getDeclaredField("mMap").apply { isAccessible = true }
    }
    fun normalize(bundle: Bundle): Pair<Int, Int> {
        val keys = bundle.keySet().filter { it in fields }
        var accepted = 0
        for (key in keys) {
            val json = runCatching {
                val raw = (mapField.get(bundle) as? Map<*, *>)?.get(key) ?: return@runCatching null
                snapshot(raw)
            }.getOrNull()
            bundle.remove(key)
            if (json != null) { bundle.putString(key, json); accepted++ }
        }
        return accepted to (keys.size - accepted)
    }
    private fun snapshot(raw: Any): String? {
        if (raw is String) return raw.takeIf { it.length <= 256 * 1024 && it.trimStart().startsWith('[') }
        val parcel = Parcel.obtain()
        return try {
            // LazyValue 原样复制 wire 字节，不使用 readParcelable 或来源类加载器。
            parcel.writeValue(raw)
            if (parcel.dataSize() !in 16..512 * 1024) return null
            parcel.setDataPosition(0)
            if (parcel.readInt() != 11) return null // VAL_LIST，ROM 的 ArrayList 实际按列表传输。
            if (parcel.readInt() != parcel.dataAvail()) return null
            val count = parcel.readInt()
            if (count !in 1..32) return null
            val images = (0 until count).map {
                require(parcel.readInt() == 4) // 每项 VAL_PARCELABLE 有独立长度。
                val length = parcel.readInt()
                require(length in 4..parcel.dataAvail())
                val end = parcel.dataPosition() + length
                require(parcel.readString() == "com.ss.android.ugc.aweme.comment.model.CommentImageStruct")
                val origin = url(parcel)
                repeat(4) { url(parcel) } // crop、medium、thumb、download；保留原图而非缩略图。
                val motion = live(parcel)
                require(parcel.dataPosition() == end)
                mapOf("originUrl" to origin, "liveVideo" to motion)
            }
            if (parcel.dataAvail() != 0) return null
            JSONArray(images).toString().takeIf { it.length <= 256 * 1024 }
        } finally { parcel.recycle() }
    }
    private fun strings(parcel: Parcel): List<String>? {
        val count = parcel.readInt()
        if (count == -1) return null
        require(count in 0..32)
        return (0 until count).map { checkNotNull(parcel.readString()).also { require(it.length <= 4096) } }
    }
    /** 当前 UrlModel 的 Parcelable 顺序已核验；不执行其 CREATOR。 */
    private fun url(parcel: Parcel): Map<String, Any?>? {
        val cls = parcel.readString() ?: return null
        require(cls == "com.ss.android.ugc.aweme.base.model.UrlModel")
        parcel.readString() // uri。
        val urls = strings(parcel)
        parcel.readInt(); parcel.readInt(); parcel.readString(); parcel.readLong()
        repeat(3) { parcel.readString() } // hash、aK、checkSum。
        strings(parcel); parcel.readString() // downUrlList、背景地址。
        require(parcel.readString() == null) // 尚未核验非空 CompressedUrlList 的协议。
        return mapOf("urlList" to urls)
    }
    private fun live(parcel: Parcel): Map<String, Any?>? {
        val cls = parcel.readString() ?: return null
        require(cls == "com.ss.android.ugc.aweme.comment.model.CommentImageLiveVideoStruct")
        parcel.readString() // vid。
        val urls = strings(parcel)
        parcel.readString() // duration。
        return mapOf("urlList" to urls)
    }
}
