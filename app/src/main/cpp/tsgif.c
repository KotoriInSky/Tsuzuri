// 动图 HEIF/AVIF → GIF89a 的原生编码器（传送门保存/分享用）。
//
// 为什么原生：Kotlin 版逐帧中值切分 + 逐像素比 256 色，480² 每帧约 105ms（JVM），
// 手机上 100 帧要几十秒。这里改为：
//   1. 每帧把颜色压到 15 位（32768 桶）做直方图；
//   2. 在非空桶上做加权中值切分得到 ≤256 色；
//   3. 桶直接归属所在盒子 → 像素映射一次查表，无距离计算。
// 纯 C、不用 STL、不链 libc++；只导出 JNI 符号。
#ifndef TSGIF_HOST_TEST
#include <jni.h>
#endif
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define BINS 32768
#define EXPORT __attribute__((visibility("default")))

typedef struct { uint8_t *data; size_t size, cap; int failed; } Buf;

static void put(Buf *b, const void *p, size_t n) {
    if (b->failed) return;
    if (b->size + n > b->cap) {
        size_t cap = b->cap ? b->cap : 1 << 20;
        while (cap < b->size + n) cap *= 2;
        uint8_t *d = realloc(b->data, cap);
        if (!d) { b->failed = 1; return; }
        b->data = d; b->cap = cap;
    }
    memcpy(b->data + b->size, p, n); b->size += n;
}
static void put1(Buf *b, int v) { uint8_t c = (uint8_t) v; put(b, &c, 1); }
static void put2(Buf *b, int v) { put1(b, v & 255); put1(b, (v >> 8) & 255); }

typedef struct { int width, height; Buf out; uint32_t *hist; uint16_t *map; int *order; } Gif;

typedef struct { int from, to; } Box;

static int channel(int bin, int shift) { return (bin >> shift) & 31; }

// 盒内按某通道排序用的全局分量（单线程调用，转码在后台单线程执行）。
static int g_shift;
static int cmp_bin(const void *a, const void *b) {
    return channel(*(const int *) a, g_shift) - channel(*(const int *) b, g_shift);
}

static void encode_frame(Gif *g, const uint32_t *px, int delay_cs) {
    const int n = g->width * g->height;
    memset(g->hist, 0, BINS * sizeof(uint32_t));
    int has_alpha = 0;
    for (int i = 0; i < n; i++) {
        uint32_t c = px[i];
        if ((c >> 24) < 128) { has_alpha = 1; continue; }
        int bin = (int) (((c >> 19) & 31) << 10 | ((c >> 11) & 31) << 5 | ((c >> 3) & 31));
        g->hist[bin]++;
    }
    int used = 0;
    for (int bin = 0; bin < BINS; bin++) if (g->hist[bin]) g->order[used++] = bin;
    const int budget = has_alpha ? 255 : 256;

    Box boxes[256]; int nbox = 0;
    if (used > 0) { boxes[0].from = 0; boxes[0].to = used; nbox = 1; }
    while (nbox < budget) {
        // 选像素数×最大跨度最大的可切盒子。
        int best = -1; long best_score = 0; int best_shift = 0;
        for (int k = 0; k < nbox; k++) {
            if (boxes[k].to - boxes[k].from < 2) continue;
            int lo[3] = {31, 31, 31}, hi[3] = {0, 0, 0}; long count = 0;
            for (int i = boxes[k].from; i < boxes[k].to; i++) {
                int bin = g->order[i];
                for (int ch = 0; ch < 3; ch++) {
                    int v = channel(bin, 10 - ch * 5);
                    if (v < lo[ch]) lo[ch] = v;
                    if (v > hi[ch]) hi[ch] = v;
                }
                count += g->hist[bin];
            }
            int span = 0, shift = 10;
            for (int ch = 0; ch < 3; ch++) if (hi[ch] - lo[ch] > span) { span = hi[ch] - lo[ch]; shift = 10 - ch * 5; }
            long score = (long) span * count;
            if (span > 0 && score > best_score) { best_score = score; best = k; best_shift = shift; }
        }
        if (best < 0) break;
        Box *b = &boxes[best];
        g_shift = best_shift;
        qsort(g->order + b->from, (size_t) (b->to - b->from), sizeof(int), cmp_bin);
        // 按像素数取加权中位。
        long total = 0, acc = 0;
        for (int i = b->from; i < b->to; i++) total += g->hist[g->order[i]];
        int mid = b->from + 1;
        for (int i = b->from; i < b->to - 1; i++) {
            acc += g->hist[g->order[i]];
            if (acc * 2 >= total) { mid = i + 1; break; }
        }
        boxes[nbox].from = mid; boxes[nbox].to = b->to; b->to = mid; nbox++;
    }

    uint8_t palette[256 * 3]; memset(palette, 0, sizeof(palette));
    for (int k = 0; k < nbox; k++) {
        uint64_t r = 0, gg = 0, bl = 0, cnt = 0;
        for (int i = boxes[k].from; i < boxes[k].to; i++) {
            int bin = g->order[i]; uint32_t w = g->hist[bin];
            r += (uint64_t) ((channel(bin, 10) << 3) | 4) * w;
            gg += (uint64_t) ((channel(bin, 5) << 3) | 4) * w;
            bl += (uint64_t) ((channel(bin, 0) << 3) | 4) * w;
            cnt += w;
            g->map[bin] = (uint16_t) k;
        }
        if (cnt) { palette[k * 3] = (uint8_t) (r / cnt); palette[k * 3 + 1] = (uint8_t) (gg / cnt); palette[k * 3 + 2] = (uint8_t) (bl / cnt); }
    }
    const int transparent = has_alpha ? nbox : -1;

    Buf *o = &g->out;
    // 图形控制扩展：处置 2（恢复背景）。
    put1(o, 0x21); put1(o, 0xF9); put1(o, 4);
    put1(o, (2 << 2) | (transparent >= 0 ? 1 : 0));
    put2(o, delay_cs < 2 ? 2 : delay_cs); put1(o, transparent >= 0 ? transparent : 0); put1(o, 0);
    put1(o, 0x2C); put2(o, 0); put2(o, 0); put2(o, g->width); put2(o, g->height);
    put1(o, 0x80 | 7); put(o, palette, sizeof(palette));

    // LZW：开放寻址哈希表，满 4096 发清除码。
    const int min_code = 8, clear = 1 << min_code, end = clear + 1;
    enum { HSIZE = 5003 };
    static int32_t hkey[HSIZE]; static int16_t hcode[HSIZE];
    memset(hkey, 0xFF, sizeof(hkey));
    put1(o, min_code);
    uint8_t block[255]; int blen = 0; uint32_t bits = 0; int nbits = 0;
    int code_size = min_code + 1, next = end + 1;
#define FLUSH() do { if (blen) { put1(o, blen); put(o, block, (size_t) blen); blen = 0; } } while (0)
#define EMIT(code) do { bits |= (uint32_t) (code) << nbits; nbits += code_size; \
        while (nbits >= 8) { block[blen++] = (uint8_t) (bits & 255); bits >>= 8; nbits -= 8; if (blen == 255) FLUSH(); } } while (0)
    EMIT(clear);
#define INDEX(i) ((px[i] >> 24) < 128 ? transparent : g->map[ \
        ((px[i] >> 19) & 31) << 10 | ((px[i] >> 11) & 31) << 5 | ((px[i] >> 3) & 31)])
    int prefix = INDEX(0);
    for (int i = 1; i < n; i++) {
        int k = INDEX(i);
        int32_t key = (prefix << 8) | k;
        int h = (int) (((uint32_t) key * 2654435761u) % HSIZE);
        while (hkey[h] != -1 && hkey[h] != key) h = (h + 1) % HSIZE;
        if (hkey[h] == key) { prefix = hcode[h]; continue; }
        EMIT(prefix);
        if (next < 4096) {
            hkey[h] = key; hcode[h] = (int16_t) next++;
            if (next > (1 << code_size) && code_size < 12) code_size++;
        } else {
            EMIT(clear); memset(hkey, 0xFF, sizeof(hkey)); code_size = min_code + 1; next = end + 1;
        }
        prefix = k;
    }
    EMIT(prefix); EMIT(end);
    if (nbits > 0) { block[blen++] = (uint8_t) (bits & 255); if (blen == 255) FLUSH(); }
    FLUSH(); put1(o, 0);
#undef INDEX
#undef EMIT
#undef FLUSH
}

#ifndef TSGIF_HOST_TEST // 主机单测只编译编码核心。
#define JNI_FN(name) Java_io_github_kotoriinsky_tsuzuri_hook_feature_portal_PortalGifNative_##name

EXPORT JNIEXPORT jlong JNICALL JNI_FN(open)(JNIEnv *env, jclass cls, jint width, jint height) {
    (void) env; (void) cls;
    if (width < 1 || height < 1 || width > 65535 || height > 65535) return 0;
    Gif *g = calloc(1, sizeof(Gif));
    if (!g) return 0;
    g->width = width; g->height = height;
    g->hist = malloc(BINS * sizeof(uint32_t)); g->map = malloc(BINS * sizeof(uint16_t)); g->order = malloc(BINS * sizeof(int));
    if (!g->hist || !g->map || !g->order) { free(g->hist); free(g->map); free(g->order); free(g); return 0; }
    Buf *o = &g->out;
    put(o, "GIF89a", 6); put2(o, width); put2(o, height); put1(o, 0); put1(o, 0); put1(o, 0);
    static const uint8_t loop[] = {0x21, 0xFF, 0x0B, 'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0', 3, 1, 0, 0, 0};
    put(o, loop, sizeof(loop));
    return (jlong) (intptr_t) g;
}

EXPORT JNIEXPORT jboolean JNICALL JNI_FN(addFrame)(JNIEnv *env, jclass cls, jlong handle, jintArray pixels, jint delay_cs) {
    (void) cls;
    Gif *g = (Gif *) (intptr_t) handle;
    if (!g || (*env)->GetArrayLength(env, pixels) != g->width * g->height) return JNI_FALSE;
    jint *px = (*env)->GetPrimitiveArrayCritical(env, pixels, NULL);
    if (!px) return JNI_FALSE;
    encode_frame(g, (const uint32_t *) px, delay_cs);
    (*env)->ReleasePrimitiveArrayCritical(env, pixels, px, JNI_ABORT);
    return g->out.failed ? JNI_FALSE : JNI_TRUE;
}

/** 写入结尾并返回完整 GIF 字节；失败返回 null。无论成败都释放句柄。 */
EXPORT JNIEXPORT jbyteArray JNICALL JNI_FN(finish)(JNIEnv *env, jclass cls, jlong handle) {
    (void) cls;
    Gif *g = (Gif *) (intptr_t) handle;
    if (!g) return NULL;
    put1(&g->out, 0x3B);
    jbyteArray result = NULL;
    if (!g->out.failed && g->out.size <= 0x7FFFFFFF) {
        result = (*env)->NewByteArray(env, (jsize) g->out.size);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize) g->out.size, (const jbyte *) g->out.data);
    }
    free(g->out.data); free(g->hist); free(g->map); free(g->order); free(g);
    return result;
}

/** 放弃编码时释放句柄。 */
EXPORT JNIEXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    Gif *g = (Gif *) (intptr_t) handle;
    if (!g) return;
    free(g->out.data); free(g->hist); free(g->map); free(g->order); free(g);
}
#endif
