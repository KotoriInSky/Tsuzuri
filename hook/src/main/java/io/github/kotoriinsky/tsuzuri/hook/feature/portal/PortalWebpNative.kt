package io.github.kotoriinsky.tsuzuri.hook.feature.portal

/** libtswebp.so（libwebp WebPAnimEncoder）的 JNI 入口；只在保存/分享动图为 WebP 时按需加载。 */
internal object PortalWebpNative {
    val available: Boolean by lazy { runCatching { System.loadLibrary("tswebp") }.isSuccess }

    @JvmStatic external fun open(width: Int, height: Int, quality: Int, lossless: Boolean): Long
    @JvmStatic external fun addFrame(handle: Long, pixels: IntArray, durationMs: Int): Boolean
    @JvmStatic external fun finish(handle: Long): ByteArray?
    @JvmStatic external fun close(handle: Long)
}
