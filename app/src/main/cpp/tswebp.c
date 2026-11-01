// 动图 HEIF/AVIF → 动态 WebP 的原生编码器（传送门「动图保存格式 = WebP」时用）。
//
// 用 libwebp 的 WebPAnimEncoder：帧间只编码变化区域、可混合有损/无损，
// 比逐帧 Bitmap.compress 再拼容器体积小。libwebp 源码在 third_party/libwebp（BSD-3）。
// 只导出 JNI 符号，libwebp 本身静态链接、符号隐藏。
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "src/webp/encode.h"
#include "src/webp/mux.h"

#define EXPORT __attribute__((visibility("default")))
#define JNI_FN(name) Java_io_github_kotoriinsky_tsuzuri_hook_feature_portal_PortalWebpNative_##name

typedef struct { WebPAnimEncoder *enc; WebPConfig config; int width, height, timestamp; } Anim;

static void release(Anim *a) {
    if (!a) return;
    if (a->enc) WebPAnimEncoderDelete(a->enc);
    free(a);
}

/** quality 0..100；lossless 非 0 时走无损（quality 作压缩力度）。 */
EXPORT JNIEXPORT jlong JNICALL JNI_FN(open)(JNIEnv *env, jclass cls, jint width, jint height, jint quality, jboolean lossless) {
    (void) env; (void) cls;
    if (width < 1 || height < 1 || width > WEBP_MAX_DIMENSION || height > WEBP_MAX_DIMENSION) return 0;
    Anim *a = calloc(1, sizeof(Anim));
    if (!a) return 0;
    a->width = width; a->height = height;
    WebPAnimEncoderOptions options;
    if (!WebPAnimEncoderOptionsInit(&options) || !WebPConfigInit(&a->config)) { release(a); return 0; }
    options.anim_params.loop_count = 0; // 无限循环。
    options.allow_mixed = lossless ? 0 : 1;
    options.minimize_size = 0;          // 用户点保存时同步等待，优先速度。
    a->config.lossless = lossless ? 1 : 0;
    a->config.quality = (float) (quality < 0 ? 0 : quality > 100 ? 100 : quality);
    a->config.method = 4;
    a->config.alpha_quality = 100;
    if (!WebPValidateConfig(&a->config)) { release(a); return 0; }
    a->enc = WebPAnimEncoderNew(width, height, &options);
    if (!a->enc) { release(a); return 0; }
    return (jlong) (intptr_t) a;
}

/** pixels 为 Android 的 ARGB_8888 getPixels 结果（0xAARRGGBB），与 WebPPicture.argb 布局一致。 */
EXPORT JNIEXPORT jboolean JNICALL JNI_FN(addFrame)(JNIEnv *env, jclass cls, jlong handle, jintArray pixels, jint durationMs) {
    (void) cls;
    Anim *a = (Anim *) (intptr_t) handle;
    if (!a || (*env)->GetArrayLength(env, pixels) != a->width * a->height) return JNI_FALSE;
    jint *px = (*env)->GetIntArrayElements(env, pixels, NULL);
    if (!px) return JNI_FALSE;
    WebPPicture pic;
    int ok = WebPPictureInit(&pic);
    if (ok) {
        pic.use_argb = 1;
        pic.width = a->width; pic.height = a->height;
        pic.argb = (uint32_t *) px; pic.argb_stride = a->width; // 借用 Java 数组，不拷贝。
        ok = WebPAnimEncoderAdd(a->enc, &pic, a->timestamp, &a->config);
    }
    (*env)->ReleaseIntArrayElements(env, pixels, px, JNI_ABORT);
    a->timestamp += durationMs > 0 ? durationMs : 100;
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** 组装并返回完整动态 WebP；失败返回 null。无论成败都释放句柄。 */
EXPORT JNIEXPORT jbyteArray JNICALL JNI_FN(finish)(JNIEnv *env, jclass cls, jlong handle) {
    (void) cls;
    Anim *a = (Anim *) (intptr_t) handle;
    if (!a) return NULL;
    jbyteArray result = NULL;
    WebPData data;
    WebPDataInit(&data);
    if (WebPAnimEncoderAdd(a->enc, NULL, a->timestamp, NULL) && WebPAnimEncoderAssemble(a->enc, &data) &&
        data.size <= 0x7FFFFFFF) {
        result = (*env)->NewByteArray(env, (jsize) data.size);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize) data.size, (const jbyte *) data.bytes);
    }
    WebPDataClear(&data);
    release(a);
    return result;
}

EXPORT JNIEXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    release((Anim *) (intptr_t) handle);
}
