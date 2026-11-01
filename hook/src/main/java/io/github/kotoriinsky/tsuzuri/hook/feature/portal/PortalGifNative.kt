package io.github.kotoriinsky.tsuzuri.hook.feature.portal

/** libtsgif.so 的 JNI 入口；只在保存/分享动图时于 contentextension 按需加载。 */
internal object PortalGifNative {
    /** 加载失败只尝试一次，之后直接走 Kotlin 编码器兜底。 */
    val available: Boolean by lazy { runCatching { System.loadLibrary("tsgif") }.isSuccess }

    @JvmStatic external fun open(width: Int, height: Int): Long
    @JvmStatic external fun addFrame(handle: Long, pixels: IntArray, delayCs: Int): Boolean
    @JvmStatic external fun finish(handle: Long): ByteArray?
    @JvmStatic external fun close(handle: Long)
}
