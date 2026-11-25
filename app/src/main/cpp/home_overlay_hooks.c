/*
 * home_overlay_hooks.c —— 澎湃桌面（com.miui.home / libapp.so）遮挡状态原生观察器
 *
 * 靶点：DesktopServiceImpl.notify{Drawer,Folder,Edit,WidgetsPreview,StackExpand}*（共 10 个 Dart 函数）
 * 版本：MiuiHome RELEASE-8.01.02.9239（全可执行段 48B 序言指纹均已验证 hits=1）。
 *
 * 安全纪律：
 *  1. 关态（want_on == 0）：绝不调 hook_func、绝不写代码页、绝不起发包线程。
 *  2. 裸汇编包装完整保存 x0..x17 + lr + q0..q7（尤其保护 Dart AOT 栈指针 x15），
 *     hook 回调只置内存 volatile，绝不做 IO / 锁 / syscall。
 *  3. 抗 HyperOS 6~11 分钟代码页 MADV_DONTNEED 重置：首次 hook 成功后备份入口补丁字节，
 *     看门狗检测到被还原为原序言时原位写回并清 icache，绝不重复调 hook_func 避免 trampoline 泄漏。
 *  4. 跨进程回传走 127.0.0.1:49731 本地回环 UDP（单包零 root），状态变化 ≤50ms 推送 + 1s 心跳；
 *     若兼容路径 /data/local/tmp/fp_launcher_state.txt 已存在且可写则一并更新。
 */

#include "home_overlay_hooks.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define TAG "FPNative"

static void ovlog(int prio, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(prio, TAG, fmt, ap);
    va_end(ap);
}

static HookFunType g_hook_fn = NULL;

void home_overlay_hooks_set_api(HookFunType hook_fn) {
    g_hook_fn = hook_fn;
}

#if !defined(__aarch64__)

int home_overlay_hooks_is_active(void) { return 0; }
void home_overlay_hooks_reconcile(uintptr_t libapp_base, uintptr_t exec_start, uintptr_t exec_end, int want_on) {
    (void)libapp_base;
    (void)exec_start;
    (void)exec_end;
    (void)want_on;
}

#else /* __aarch64__ */

enum {
    OV_DRAWER  = 0,
    OV_FOLDER  = 1,
    OV_PREVIEW = 2,
    OV_EDIT    = 3,
    OV_STACK   = 4,
    OV_COUNT   = 5,
};

static volatile int g_ov_active = 0;
static volatile int g_ov_state[OV_COUNT];
static volatile uint32_t g_ov_seq = 0;
static volatile int g_ov_dirty = 0;
static volatile int g_hit_logs = 0;

static inline void record_ov(int which, int on, const char *label) {
    if (!__atomic_load_n(&g_ov_active, __ATOMIC_RELAXED)) return;
    int prev = __atomic_exchange_n(&g_ov_state[which], on, __ATOMIC_RELEASE);
    if (prev != on) {
        __atomic_add_fetch(&g_ov_seq, 1u, __ATOMIC_RELEASE);
        __atomic_store_n(&g_ov_dirty, 1, __ATOMIC_RELEASE);
    }
    int logged = __atomic_load_n(&g_hit_logs, __ATOMIC_RELAXED);
    if (logged < 10 && __atomic_compare_exchange_n(&g_hit_logs, &logged, logged + 1, 0,
                                                   __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
        ovlog(ANDROID_LOG_INFO, "home_overlay: HIT %s -> %d (seq=%u)",
              label, on, __atomic_load_n(&g_ov_seq, __ATOMIC_RELAXED));
    }
}

__attribute__((used, noinline)) static void ov_cb_drawer_open(void)  { record_ov(OV_DRAWER,  1, "notifyDrawerOpen"); }
__attribute__((used, noinline)) static void ov_cb_drawer_close(void) { record_ov(OV_DRAWER,  0, "notifyDrawerClose"); }
__attribute__((used, noinline)) static void ov_cb_folder_open(void)  { record_ov(OV_FOLDER,  1, "notifyFolderOpen"); }
__attribute__((used, noinline)) static void ov_cb_folder_close(void) { record_ov(OV_FOLDER,  0, "notifyFolderClose"); }
__attribute__((used, noinline)) static void ov_cb_edit_open(void)    { record_ov(OV_EDIT,    1, "notifyEditEnable"); }
__attribute__((used, noinline)) static void ov_cb_edit_close(void)   { record_ov(OV_EDIT,    0, "notifyEditDisable"); }
__attribute__((used, noinline)) static void ov_cb_prev_open(void)    { record_ov(OV_PREVIEW, 1, "notifyWidgetsPreviewShow"); }
__attribute__((used, noinline)) static void ov_cb_prev_close(void)   { record_ov(OV_PREVIEW, 0, "notifyWidgetsPreviewHide"); }
__attribute__((used, noinline)) static void ov_cb_stack_open(void)   { record_ov(OV_STACK,   1, "notifyStackExpandOpen"); }
__attribute__((used, noinline)) static void ov_cb_stack_close(void)  { record_ov(OV_STACK,   0, "notifyStackExpandClose"); }

/* 跳板指针：供裸汇编尾跳。 */
static void *g_orig_drawer_open  __attribute__((used)) = NULL;
static void *g_orig_drawer_close __attribute__((used)) = NULL;
static void *g_orig_folder_open  __attribute__((used)) = NULL;
static void *g_orig_folder_close __attribute__((used)) = NULL;
static void *g_orig_edit_open    __attribute__((used)) = NULL;
static void *g_orig_edit_close   __attribute__((used)) = NULL;
static void *g_orig_prev_open    __attribute__((used)) = NULL;
static void *g_orig_prev_close   __attribute__((used)) = NULL;
static void *g_orig_stack_open   __attribute__((used)) = NULL;
static void *g_orig_stack_close  __attribute__((used)) = NULL;

/*
 * 裸汇编包装：保存全部调用者寄存器（x0..x17, lr, q0..q7，特别保护 Dart AOT 栈指针 x15）
 * → 调纯内存 C 回调 → 完整恢复 → 尾跳至原函数跳板。
 */
#define FP_OV_WRAPPER(fn_name, cb_fn, orig_var)                                         \
    __attribute__((naked, used)) static void fn_name(void) {                            \
        __asm__ __volatile__(                                                           \
            "sub sp, sp, #288\n"                                                        \
            "stp x0, x1, [sp, #0]\n"                                                    \
            "stp x2, x3, [sp, #16]\n"                                                   \
            "stp x4, x5, [sp, #32]\n"                                                   \
            "stp x6, x7, [sp, #48]\n"                                                   \
            "stp x8, x9, [sp, #64]\n"                                                   \
            "stp x10, x11, [sp, #80]\n"                                                 \
            "stp x12, x13, [sp, #96]\n"                                                 \
            "stp x14, x15, [sp, #112]\n"                                                \
            "stp x16, x17, [sp, #128]\n"                                                \
            "str lr, [sp, #144]\n"                                                      \
            "stp q0, q1, [sp, #160]\n"                                                  \
            "stp q2, q3, [sp, #192]\n"                                                  \
            "stp q4, q5, [sp, #224]\n"                                                  \
            "stp q6, q7, [sp, #256]\n"                                                  \
            "bl " #cb_fn "\n"                                                           \
            "ldp x0, x1, [sp, #0]\n"                                                    \
            "ldp x2, x3, [sp, #16]\n"                                                   \
            "ldp x4, x5, [sp, #32]\n"                                                   \
            "ldp x6, x7, [sp, #48]\n"                                                   \
            "ldp x8, x9, [sp, #64]\n"                                                   \
            "ldp x10, x11, [sp, #80]\n"                                                 \
            "ldp x12, x13, [sp, #96]\n"                                                 \
            "ldp x14, x15, [sp, #112]\n"                                                \
            "ldp x16, x17, [sp, #128]\n"                                                \
            "ldr lr, [sp, #144]\n"                                                      \
            "ldp q0, q1, [sp, #160]\n"                                                  \
            "ldp q2, q3, [sp, #192]\n"                                                  \
            "ldp q4, q5, [sp, #224]\n"                                                  \
            "ldp q6, q7, [sp, #256]\n"                                                  \
            "add sp, sp, #288\n"                                                        \
            "adrp x16, " #orig_var "\n"                                                 \
            "ldr x16, [x16, :lo12:" #orig_var "]\n"                                     \
            "br x16\n");                                                                \
    }

FP_OV_WRAPPER(ov_wrap_drawer_open,  ov_cb_drawer_open,  g_orig_drawer_open)
FP_OV_WRAPPER(ov_wrap_drawer_close, ov_cb_drawer_close, g_orig_drawer_close)
FP_OV_WRAPPER(ov_wrap_folder_open,  ov_cb_folder_open,  g_orig_folder_open)
FP_OV_WRAPPER(ov_wrap_folder_close, ov_cb_folder_close, g_orig_folder_close)
FP_OV_WRAPPER(ov_wrap_edit_open,    ov_cb_edit_open,    g_orig_edit_open)
FP_OV_WRAPPER(ov_wrap_edit_close,   ov_cb_edit_close,   g_orig_edit_close)
FP_OV_WRAPPER(ov_wrap_prev_open,    ov_cb_prev_open,    g_orig_prev_open)
FP_OV_WRAPPER(ov_wrap_prev_close,   ov_cb_prev_close,   g_orig_prev_close)
FP_OV_WRAPPER(ov_wrap_stack_open,   ov_cb_stack_open,   g_orig_stack_open)
FP_OV_WRAPPER(ov_wrap_stack_close,  ov_cb_stack_close,  g_orig_stack_close)

#define ENTRY_BACKUP_WORDS 6u

typedef struct {
    const char *name;
    uintptr_t hint_vaddr;
    void *wrapper;
    void **orig_slot;
    uint32_t fp[12]; /* 48 字节序言指纹（MiuiHome 9239，全段唯一命中 hits=1） */
    /* 运行时状态 */
    uintptr_t addr;
    uint8_t resolved;
    uint8_t hooked_once;
    uint8_t active_in_mem;
    uint8_t entry_words;
    uint32_t orig_entry[ENTRY_BACKUP_WORDS];
    uint32_t hooked_entry[ENTRY_BACKUP_WORDS];
} ov_slot_t;

static ov_slot_t g_ov_slots[] = {
    {
        .name = "notifyDrawerOpen",
        .hint_vaddr = 0x15bbd54u,
        .wrapper = (void *)ov_wrap_drawer_open,
        .orig_slot = &g_orig_drawer_open,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x9142ff62u, 0xf944b442u, 0x97cce9feu, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyDrawerClose",
        .hint_vaddr = 0x15bbc2cu,
        .wrapper = (void *)ov_wrap_drawer_close,
        .orig_slot = &g_orig_drawer_close,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x9142ff62u, 0xf9459c42u, 0x97ccea48u, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyFolderOpen",
        .hint_vaddr = 0x0a81364u,
        .wrapper = (void *)ov_wrap_folder_open,
        .orig_slot = &g_orig_folder_open,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x9140a762u, 0xf942d442u, 0x97f9d47au, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyFolderClose",
        .hint_vaddr = 0x0a7e770u,
        .wrapper = (void *)ov_wrap_folder_close,
        .orig_slot = &g_orig_folder_close,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x9140a762u, 0xf941b042u, 0x97f9df77u, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyEditEnable",
        .hint_vaddr = 0x11d4070u,
        .wrapper = (void *)ov_wrap_edit_open,
        .orig_slot = &g_orig_edit_open,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x91429b62u, 0xf942cc42u, 0x97dc8937u, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyEditDisable",
        .hint_vaddr = 0x095e074u,
        .wrapper = (void *)ov_wrap_edit_close,
        .orig_slot = &g_orig_edit_close,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x91403362u, 0xf9423442u, 0x97fe6136u, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyWidgetsPreviewShow",
        .hint_vaddr = 0x11348e8u,
        .wrapper = (void *)ov_wrap_prev_open,
        .orig_slot = &g_orig_prev_open,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x9140db62u, 0xf9429042u, 0x97df0719u, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyWidgetsPreviewHide",
        .hint_vaddr = 0x0eab730u,
        .wrapper = (void *)ov_wrap_prev_close,
        .orig_slot = &g_orig_prev_close,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x9140db62u, 0xf942a042u, 0x97e92b87u, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyStackExpandOpen",
        .hint_vaddr = 0x0af7038u,
        .wrapper = (void *)ov_wrap_stack_open,
        .orig_slot = &g_orig_stack_open,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x9140bb62u, 0xf9453042u, 0x97f7fd45u, 0xf85f83a0u, 0xb8407002u},
    },
    {
        .name = "notifyStackExpandClose",
        .hint_vaddr = 0x0af4a8cu,
        .wrapper = (void *)ov_wrap_stack_close,
        .orig_slot = &g_orig_stack_close,
        .fp = {0xa9bf79fdu, 0xaa0f03fdu, 0xd100a1efu, 0xaa0103e0u, 0xf81f83a1u, 0x91402b61u,
               0xf942d021u, 0x9140bb62u, 0xf943bc42u, 0x97f806b0u, 0xf85f83a0u, 0xb8407002u},
    },
};

#define OV_SLOT_COUNT (sizeof(g_ov_slots) / sizeof(g_ov_slots[0]))

/* ---------- 本地回环 UDP 发送线程（单包零 root；仅在首次开启时拉起） ---------- */

static int g_sender_started = 0;
static int g_udp_sock = -1;
static struct sockaddr_in g_udp_dst;
static int g_udp_ok_logged = 0;
static int g_udp_err_logged = 0;

static long long mono_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void send_snapshot(int force_clear) {
    int d = force_clear ? 0 : __atomic_load_n(&g_ov_state[OV_DRAWER],  __ATOMIC_ACQUIRE);
    int f = force_clear ? 0 : __atomic_load_n(&g_ov_state[OV_FOLDER],  __ATOMIC_ACQUIRE);
    int p = force_clear ? 0 : __atomic_load_n(&g_ov_state[OV_PREVIEW], __ATOMIC_ACQUIRE);
    int e = force_clear ? 0 : __atomic_load_n(&g_ov_state[OV_EDIT],    __ATOMIC_ACQUIRE);
    int s = force_clear ? 0 : __atomic_load_n(&g_ov_state[OV_STACK],   __ATOMIC_ACQUIRE);
    uint32_t seq = __atomic_load_n(&g_ov_seq, __ATOMIC_ACQUIRE);

    char buf[192];
    int n = snprintf(buf, sizeof(buf),
                     "fpoverlay v=1 seq=%u ts=%lld drawer=%d folder=%d preview=%d edit=%d stack=%d\n",
                     seq, mono_ms(), d, f, p, e, s);
    if (n <= 0 || n >= (int)sizeof(buf)) return;

    /* 1. 主通道：本地回环 UDP（非阻塞，SystemUI 重启后自然继续收包） */
    if (g_udp_sock < 0) {
        int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) {
            memset(&g_udp_dst, 0, sizeof(g_udp_dst));
            g_udp_dst.sin_family = AF_INET;
            g_udp_dst.sin_port = htons((uint16_t)OVERLAY_UDP_PORT);
            inet_pton(AF_INET, OVERLAY_UDP_HOST, &g_udp_dst.sin_addr);
            g_udp_sock = fd;
        } else if (!g_udp_err_logged) {
            g_udp_err_logged = 1;
            ovlog(ANDROID_LOG_WARN, "home_overlay: UDP socket() failed errno=%d", errno);
        }
    }
    if (g_udp_sock >= 0) {
        ssize_t r = sendto(g_udp_sock, buf, (size_t)n, MSG_DONTWAIT,
                           (const struct sockaddr *)&g_udp_dst, sizeof(g_udp_dst));
        if (r == (ssize_t)n) {
            if (!g_udp_ok_logged) {
                g_udp_ok_logged = 1;
                ovlog(ANDROID_LOG_INFO, "home_overlay: UDP channel active -> %s:%d",
                      OVERLAY_UDP_HOST, OVERLAY_UDP_PORT);
            }
        } else if (!g_udp_err_logged) {
            g_udp_err_logged = 1;
            ovlog(ANDROID_LOG_WARN, "home_overlay: UDP sendto failed errno=%d", errno);
        }
    }

    /* 2. 兼容兜底：若旧的共享文件已经预建并可写，顺手同步；不存在则静默跳过 */
    int ffd = open(OVERLAY_FILE_PATH, O_WRONLY | O_TRUNC | O_CLOEXEC);
    if (ffd >= 0) {
        ssize_t wr = write(ffd, buf, (size_t)n);
        (void)wr;
        close(ffd);
    }
}

static void *overlay_sender_thread(void *arg) {
    (void)arg;
    prctl(PR_SET_NAME, "tsuzuri_ov", 0, 0, 0);
    long long last_sent = 0;
    for (;;) {
        if (!__atomic_load_n(&g_ov_active, __ATOMIC_ACQUIRE)) {
            usleep(400000);
            continue;
        }
        long long now = mono_ms();
        int dirty = __atomic_exchange_n(&g_ov_dirty, 0, __ATOMIC_ACQ_REL);
        if (dirty || (now - last_sent >= 1000)) {
            send_snapshot(0);
            last_sent = now;
        }
        usleep(50000); /* 50ms 节拍，事件推送延迟 <50ms */
    }
    return NULL;
}

static void ensure_sender_started(void) {
    if (g_sender_started) return;
    g_sender_started = 1;
    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    int rc = pthread_create(&tid, &attr, overlay_sender_thread, NULL);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        g_sender_started = 0;
        ovlog(ANDROID_LOG_WARN, "home_overlay: sender pthread_create failed rc=%d", rc);
    }
}

/* ---------- 入口指令备份 / 自愈 / 恢复 ---------- */

static int words_equal(uintptr_t addr, const uint32_t *want, uint8_t count) {
    const uint32_t *cur = (const uint32_t *)addr;
    for (uint8_t i = 0; i < count; i++) {
        if (cur[i] != want[i]) return 0;
    }
    return 1;
}

static int write_entry_words(ov_slot_t *s, const uint32_t *want, int activating) {
    if (!s->hooked_once || s->addr == 0 || s->entry_words == 0) return 0;
    if (words_equal(s->addr, want, s->entry_words)) return 1;

    long page_sz_raw = sysconf(_SC_PAGESIZE);
    uintptr_t page_sz = (page_sz_raw > 0) ? (uintptr_t)page_sz_raw : 4096u;
    size_t write_len = (size_t)s->entry_words * 4u;
    uintptr_t page_start = s->addr & ~(page_sz - 1u);
    uintptr_t page_end = (s->addr + write_len + page_sz - 1u) & ~(page_sz - 1u);
    size_t map_len = (size_t)(page_end - page_start);

    if (mprotect((void *)page_start, map_len, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        ovlog(ANDROID_LOG_WARN, "home_overlay: mprotect(RWX) failed for %s errno=%d", s->name, errno);
        return 0;
    }

    uint32_t *dst = (uint32_t *)s->addr;
    if (activating) {
        /* 激活：先写后续字面量/跳转目标，最后写第 0 条入口指令 */
        for (uint8_t i = 1; i < s->entry_words; i++) {
            __atomic_store_n(dst + i, want[i], __ATOMIC_RELAXED);
        }
        __atomic_store_n(dst, want[0], __ATOMIC_RELEASE);
    } else {
        /* 还原：先恢复第 0 条入口指令，再恢复后续指令 */
        __atomic_store_n(dst, want[0], __ATOMIC_RELEASE);
        for (uint8_t i = 1; i < s->entry_words; i++) {
            __atomic_store_n(dst + i, want[i], __ATOMIC_RELAXED);
        }
    }

    __builtin___clear_cache((char *)s->addr, (char *)s->addr + write_len);

    if (mprotect((void *)page_start, map_len, PROT_READ | PROT_EXEC) != 0) {
        ovlog(ANDROID_LOG_WARN, "home_overlay: mprotect(RX) failed for %s errno=%d", s->name, errno);
    }
    return words_equal(s->addr, want, s->entry_words);
}

static int g_ov_resolved = 0;
static int g_ov_last_want = -1;

static void resolve_ov_slots(uintptr_t libapp_base, uintptr_t exec_start, uintptr_t exec_end) {
    if (g_ov_resolved) return;
    int ok = 0;
    for (size_t i = 0; i < OV_SLOT_COUNT; i++) {
        ov_slot_t *s = &g_ov_slots[i];
        s->resolved = 0;
        s->addr = 0;
        uintptr_t cand = libapp_base + s->hint_vaddr;
        if (cand < exec_start || cand + sizeof(s->fp) > exec_end) continue;
        if (memcmp((const void *)cand, s->fp, sizeof(s->fp)) == 0) {
            s->addr = cand;
            s->resolved = 1;
            ok++;
        } else {
            ovlog(ANDROID_LOG_WARN, "home_overlay: slot %s prologue mismatch at +0x%lx (OTA changed?), skipped",
                  s->name, (unsigned long)s->hint_vaddr);
        }
    }
    g_ov_resolved = 1;
    ovlog(ANDROID_LOG_INFO, "home_overlay: resolve done ok=%d/%zu", ok, OV_SLOT_COUNT);
}

int home_overlay_hooks_is_active(void) {
    return __atomic_load_n(&g_ov_active, __ATOMIC_RELAXED) != 0;
}

void home_overlay_hooks_reconcile(uintptr_t libapp_base, uintptr_t exec_start, uintptr_t exec_end, int want_on) {
    int want = want_on ? 1 : 0;
    if (!want && !home_overlay_hooks_is_active()) {
        if (g_ov_last_want != 0) {
            g_ov_last_want = 0;
        }
        return;
    }

    if (want && g_hook_fn == NULL) {
        if (g_ov_last_want != 1) {
            g_ov_last_want = 1;
            ovlog(ANDROID_LOG_WARN, "home_overlay: hook_func is NULL, cannot install overlay hooks");
        }
        return;
    }

    resolve_ov_slots(libapp_base, exec_start, exec_end);

    int active_count = 0;
    int reapplied = 0;
    int newly_hooked = 0;

    if (want) {
        ensure_sender_started();
        __atomic_store_n(&g_ov_active, 1, __ATOMIC_RELEASE);

        for (size_t i = 0; i < OV_SLOT_COUNT; i++) {
            ov_slot_t *s = &g_ov_slots[i];
            if (!s->resolved) continue;

            if (!s->hooked_once) {
                /* 首次安装前备份原始 24B */
                memcpy(s->orig_entry, (const void *)s->addr, sizeof(s->orig_entry));
                void *orig = NULL;
                int rc = g_hook_fn((void *)s->addr, s->wrapper, &orig);
                if (rc == 0 && orig != NULL) {
                    *s->orig_slot = orig;
                    memcpy(s->hooked_entry, (const void *)s->addr, sizeof(s->hooked_entry));
                    uint8_t diff_end = 0;
                    for (uint8_t w = 0; w < ENTRY_BACKUP_WORDS; w++) {
                        if (s->hooked_entry[w] != s->orig_entry[w]) diff_end = w + 1;
                    }
                    if (diff_end == 0) diff_end = 4;
                    s->entry_words = diff_end;
                    s->hooked_once = 1;
                    s->active_in_mem = 1;
                    newly_hooked++;
                    active_count++;
                } else {
                    ovlog(ANDROID_LOG_WARN, "home_overlay: hook_func(%s) failed rc=%d orig=%p",
                          s->name, rc, orig);
                }
            } else {
                if (words_equal(s->addr, s->hooked_entry, s->entry_words)) {
                    s->active_in_mem = 1;
                    active_count++;
                } else if (words_equal(s->addr, s->orig_entry, s->entry_words)) {
                    if (write_entry_words(s, s->hooked_entry, 1)) {
                        if (s->active_in_mem && g_ov_last_want == 1) {
                            reapplied++;
                        }
                        s->active_in_mem = 1;
                        active_count++;
                    }
                } else {
                    ovlog(ANDROID_LOG_WARN, "home_overlay: slot %s unexpected bytes, skipped", s->name);
                }
            }
        }

        if (g_ov_last_want != 1) {
            g_ov_last_want = 1;
            __atomic_store_n(&g_ov_dirty, 1, __ATOMIC_RELEASE);
            ovlog(ANDROID_LOG_INFO, "home_overlay: ON active=%d/%zu (new=%d)",
                  active_count, OV_SLOT_COUNT, newly_hooked);
        } else if (reapplied > 0) {
            ovlog(ANDROID_LOG_INFO, "home_overlay: watchdog re-applied %d reset hook(s) (active=%d/%zu)",
                  reapplied, active_count, OV_SLOT_COUNT);
        }
    } else {
        /* 关闭：先停发包并清零状态，再发送一帧全 0 解除 SystemUI 抑制，最后还原入口字节 */
        __atomic_store_n(&g_ov_active, 0, __ATOMIC_RELEASE);
        for (int k = 0; k < OV_COUNT; k++) {
            __atomic_store_n(&g_ov_state[k], 0, __ATOMIC_RELEASE);
        }
        __atomic_add_fetch(&g_ov_seq, 1u, __ATOMIC_RELEASE);
        send_snapshot(1);

        int restored = 0;
        for (size_t i = 0; i < OV_SLOT_COUNT; i++) {
            ov_slot_t *s = &g_ov_slots[i];
            if (!s->hooked_once) continue;
            if (write_entry_words(s, s->orig_entry, 0)) {
                s->active_in_mem = 0;
                restored++;
            }
        }
        g_ov_last_want = 0;
        ovlog(ANDROID_LOG_INFO, "home_overlay: OFF restored=%d/%zu", restored, OV_SLOT_COUNT);
    }
}

#endif /* __aarch64__ */
