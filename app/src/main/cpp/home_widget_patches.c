/*
 * home_widget_patches.c —— 澎湃桌面（com.miui.home / libapp.so）原生指令补丁引擎
 *
 * 设计约束：
 *  1. 仅在 com.miui.home 进程中运行；所有特性默认关闭（flags=0 时不调用 mprotect、零写页）。
 *  2. 只做原位 32-bit / 64-bit 指令替换，绝不调用 C trampoline（避开 Dart AOT x15 栈指针 ABI 雷区）。
 *  3. 每个落点自带 4 条前置指令 + 原始目标指令 + 4 条后置指令指纹校验（36B / 40B）；
 *     先 O(1) 校验 9239 快速偏移，不匹配时回退全代码段唯一性扫描（hits != 1 一律跳过，OTA 安全）。
 *  4. 关开关时干净恢复原始字节；内置低频看门狗线程对抗 HyperOS 6~11 分钟代码页 MADV_DONTNEED 重置。
 */

#include "home_widget_patches.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/system_properties.h>
#include <unistd.h>

#define TAG "FPNative"
#define WATCHDOG_INTERVAL_SEC 2u

static void hlog(int prio, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(prio, TAG, fmt, ap);
    va_end(ap);
}

typedef struct {
    const char *name;
    uint32_t feature_bit;
    uintptr_t hint_vaddr;
    uint8_t word_count; /* 1 or 2 */
    uint32_t pre[4];
    uint32_t orig[2];
    uint32_t patch[2];
    uint32_t post[4];
    /* 运行时状态 */
    uintptr_t resolved_addr;
    uint8_t resolved;
    uint8_t applied;
} patch_slot_t;

/*
 * 10 处实测落点（共 11 条 AArch64 指令），全部在 MiuiHome 9239 libapp.so
 * （exec 段 vaddr=0x890000, size=0x13b6710）上验证全段唯一匹配（hits=1）。
 *
 * 注意：故意不动 isMiuiWidget 本身，保留 MiuiWidgetGetxController.enforceRoundedCorners
 * 对第三方小部件的 !isMiuiWidget == true 强裁圆角路径。
 */
static patch_slot_t g_slots[] = {
    /* ── 1. 桌面第三方小部件风格统一 (HOME_FLAG_STYLE) ── */
    {
        .name = "S1_top_pad",
        .feature_bit = HOME_FLAG_STYLE,
        .hint_vaddr = 0x10dfce4u,
        .word_count = 1,
        .pre = {0xd65f03c0u, 0xf85f83a0u, 0xb84fb001u, 0x8b1c8021u},
        .orig = {0x372002c1u, 0u},      /* tbnz w1, #4, +0x58 */
        .patch = {0xd503201fu, 0u},     /* nop */
        .post = {0xf9403f40u, 0xf9541400u, 0x6b16001fu, 0x540001e1u},
    },
    {
        .name = "S2_width",
        .feature_bit = HOME_FLAG_STYLE,
        .hint_vaddr = 0x10e00c0u,
        .word_count = 1,
        .pre = {0x14000049u, 0xf85f83a0u, 0xb84fb003u, 0x8b1c8063u},
        .orig = {0x36200623u, 0u},      /* tbz w3, #4, +0xc4 */
        .patch = {0x14000031u, 0u},     /* b +0xc4 */
        .post = {0xf9403f41u, 0xf9541421u, 0x6b16003fu, 0x540001c1u},
    },
    {
        .name = "S3_height",
        .feature_bit = HOME_FLAG_STYLE,
        .hint_vaddr = 0x10dfe7cu,
        .word_count = 1,
        .pre = {0x14000042u, 0xf85f83a0u, 0xb84fb003u, 0x8b1c8063u},
        .orig = {0x36200523u, 0u},      /* tbz w3, #4, +0xa4 */
        .patch = {0x14000029u, 0u},     /* b +0xa4 */
        .post = {0xf9403f41u, 0xf9541421u, 0x6b16003fu, 0x540001c1u},
    },
    {
        .name = "S4_radius",
        .feature_bit = HOME_FLAG_STYLE,
        .hint_vaddr = 0x0d99190u,
        .word_count = 1,
        .pre = {0x6b10001fu, 0x54000380u, 0xb84fb001u, 0x8b1c8021u},
        .orig = {0x37200261u, 0u},      /* tbnz w1, #4, +0x4c */
        .patch = {0xd503201fu, 0u},     /* nop */
        .post = {0xf841f002u, 0xf8427003u, 0x937f7840u, 0xeb80045fu},
    },

    /* ── 2. 桌面小部件任意拖入负一屏 (HOME_FLAG_DRAG_PA) ── */
    {
        .name = "D1_span",
        .feature_bit = HOME_FLAG_DRAG_PA,
        .hint_vaddr = 0x0d7cc10u,       /* 8 字节对齐，64-bit 原子写入两条指令 */
        .word_count = 2,
        .pre = {0x97ecafebu, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u},
        .orig = {0xa9bf79fdu, 0xaa0f03fdu},  /* stp x29, x30, [sp, #-16]!; mov x29, x15 */
        .patch = {0x910082c0u, 0xd65f03c0u}, /* add x0, x22, #0x20 (Dart true); ret */
        .post = {0xd10081efu, 0xf81f03a1u, 0xf81e83a2u, 0xf100043fu},
    },
    {
        .name = "D2_item_size",
        .feature_bit = HOME_FLAG_DRAG_PA,
        .hint_vaddr = 0x14ea95cu,
        .word_count = 1,
        .pre = {0xf1001a1fu, 0x54000188u, 0xb84fb001u, 0x8b1c8021u},
        .orig = {0x372000a1u, 0u},      /* tbnz w1, #4, +0x14 */
        .patch = {0xd503201fu, 0u},     /* nop */
        .post = {0xf841f001u, 0xf8427002u, 0x97e248aau, 0x14000002u},
    },
    {
        .name = "D3_can_single",
        .feature_bit = HOME_FLAG_DRAG_PA,
        .hint_vaddr = 0x0d7c82cu,
        .word_count = 1,
        .pre = {0x54000040u, 0x943b0ed5u, 0xb84fb040u, 0x8b1c8000u},
        .orig = {0x37200500u, 0u},      /* tbnz w0, #4, +0xa0 */
        .patch = {0xd503201fu, 0u},     /* nop */
        .post = {0xaa0403e1u, 0xaa0603e2u, 0x940000f6u, 0x37200480u},
    },
    {
        .name = "D4_can_stack",
        .feature_bit = HOME_FLAG_DRAG_PA,
        .hint_vaddr = 0x0d7c5ccu,
        .word_count = 1,
        .pre = {0x14000002u, 0x9100c2c0u, 0xf85e83a2u, 0xb8047040u},
        .orig = {0x37200780u, 0u},      /* tbnz w0, #4, +0xf0 */
        .patch = {0xd503201fu, 0u},     /* nop */
        .post = {0xb8443040u, 0x8b1c8000u, 0x37200720u, 0xf85f83a1u},
    },

    /* ── 3. 桌面任意小部件堆叠 (HOME_FLAG_STACK) ── */
    {
        .name = "K1_stack_drag",
        .feature_bit = HOME_FLAG_STACK,
        .hint_vaddr = 0x0ba8b84u,
        .word_count = 1,
        .pre = {0xf122581fu, 0x54000301u, 0xb84fb020u, 0x8b1c8000u},
        .orig = {0x362002a0u, 0u},      /* tbz w0, #4, +0x54 */
        .patch = {0x14000015u, 0u},     /* b +0x54 */
        .post = {0xf9403f40u, 0xf952ac00u, 0xf9402370u, 0x6b10001fu},
    },
    {
        .name = "K2_stack_tgt",
        .feature_bit = HOME_FLAG_STACK,
        .hint_vaddr = 0x0ba8bf8u,
        .word_count = 1,
        .pre = {0xf122587fu, 0x54000301u, 0xb84fb004u, 0x8b1c8084u},
        .orig = {0x362002a4u, 0u},      /* tbz w4, #4, +0x54 */
        .patch = {0x14000015u, 0u},     /* b +0x54 */
        .post = {0xf9403f40u, 0xf952ac00u, 0xf9402370u, 0x6b10001fu},
    },
};

#define SLOT_COUNT (sizeof(g_slots) / sizeof(g_slots[0]))

typedef struct {
    uintptr_t base;
    uintptr_t exec_start;
    uintptr_t exec_end;
    int found;
} libapp_region_t;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_engine_started = 0;
static libapp_region_t g_libapp = {0};
static int g_slots_resolved = 0;
static uint32_t g_last_flags = 0xffffffffu;
static char g_last_source[16] = "none";

/* ---------- 定位 libapp.so 的可执行段 ---------- */

static int ends_with(const char *s, const char *suffix) {
    if (s == NULL || suffix == NULL) return 0;
    size_t slen = strlen(s);
    size_t suflen = strlen(suffix);
    if (slen < suflen) return 0;
    return strcmp(s + (slen - suflen), suffix) == 0;
}

static int find_libapp_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    libapp_region_t *out = (libapp_region_t *)data;
    const char *n = info->dlpi_name;
    if (n == NULL || n[0] == '\0') return 0;
    /* 严格匹配 libapp.so，绝不误匹配 libapp_launcher.so */
    if (!(strcmp(n, "libapp.so") == 0 || ends_with(n, "/libapp.so"))) return 0;

    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type == PT_LOAD && (ph->p_flags & PF_X) != 0 && ph->p_memsz >= 0x100000u) {
            out->base = (uintptr_t)info->dlpi_addr;
            out->exec_start = (uintptr_t)info->dlpi_addr + (uintptr_t)ph->p_vaddr;
            out->exec_end = out->exec_start + (uintptr_t)ph->p_memsz;
            out->found = 1;
            return 1;
        }
    }
    return 0;
}

static int locate_libapp(void) {
    if (g_libapp.found) return 1;
    libapp_region_t reg;
    memset(&reg, 0, sizeof(reg));
    dl_iterate_phdr(find_libapp_cb, &reg);
    if (!reg.found) return 0;
    g_libapp = reg;
    hlog(ANDROID_LOG_INFO, "home_patches: libapp.so base=0x%lx exec=[0x%lx..0x%lx) size=0x%lx",
         (unsigned long)reg.base, (unsigned long)reg.exec_start,
         (unsigned long)reg.exec_end, (unsigned long)(reg.exec_end - reg.exec_start));
    return 1;
}

/* ---------- 落点指纹匹配与解析 ---------- */

static int slot_surroundings_match(const patch_slot_t *s, uintptr_t addr) {
    size_t target_bytes = (size_t)s->word_count * 4u;
    if (addr < g_libapp.exec_start + 16u) return 0;
    if (addr + target_bytes + 16u > g_libapp.exec_end) return 0;

    const uint32_t *pre = (const uint32_t *)(addr - 16u);
    if (pre[0] != s->pre[0] || pre[1] != s->pre[1] ||
        pre[2] != s->pre[2] || pre[3] != s->pre[3]) {
        return 0;
    }
    const uint32_t *post = (const uint32_t *)(addr + target_bytes);
    if (post[0] != s->post[0] || post[1] != s->post[1] ||
        post[2] != s->post[2] || post[3] != s->post[3]) {
        return 0;
    }
    return 1;
}

static int slot_target_matches_words(const patch_slot_t *s, uintptr_t addr, const uint32_t words[2]) {
    const uint32_t *cur = (const uint32_t *)addr;
    for (uint8_t i = 0; i < s->word_count; i++) {
        if (cur[i] != words[i]) return 0;
    }
    return 1;
}

static int slot_matches_at(const patch_slot_t *s, uintptr_t addr) {
    if (!slot_surroundings_match(s, addr)) return 0;
    return slot_target_matches_words(s, addr, s->orig) ||
           slot_target_matches_words(s, addr, s->patch);
}

static int resolve_all_slots(void) {
    if (g_slots_resolved) return 1;
    if (!locate_libapp()) return 0;

    int ok_count = 0;
    int fast_count = 0;
    int scan_count = 0;

    for (size_t i = 0; i < SLOT_COUNT; i++) {
        patch_slot_t *s = &g_slots[i];
        s->resolved = 0;
        s->resolved_addr = 0;
        s->applied = 0;

        /* 快速路径：9239 已知偏移直接 O(1) 验证完整窗口 */
        uintptr_t hint_addr = g_libapp.base + s->hint_vaddr;
        if (slot_matches_at(s, hint_addr)) {
            s->resolved_addr = hint_addr;
            s->resolved = 1;
            s->applied = (uint8_t)slot_target_matches_words(s, hint_addr, s->patch);
            ok_count++;
            fast_count++;
            continue;
        }

        /* OTA 兜底：全可执行段扫描，要求全局唯一命中（hits == 1） */
        uintptr_t first = g_libapp.exec_start + 16u;
        uintptr_t last = g_libapp.exec_end - ((size_t)s->word_count * 4u + 16u);
        uintptr_t found_addr = 0;
        int hits = 0;
        for (uintptr_t p = first; p <= last; p += 4u) {
            const uint32_t *pre = (const uint32_t *)(p - 16u);
            if (pre[0] != s->pre[0]) continue;
            if (slot_matches_at(s, p)) {
                found_addr = p;
                hits++;
                if (hits > 1) break;
            }
        }
        if (hits == 1) {
            s->resolved_addr = found_addr;
            s->resolved = 1;
            s->applied = (uint8_t)slot_target_matches_words(s, found_addr, s->patch);
            ok_count++;
            scan_count++;
            hlog(ANDROID_LOG_INFO, "home_patches: slot %s resolved via scan vaddr=0x%lx",
                 s->name, (unsigned long)(found_addr - g_libapp.base));
        } else {
            hlog(ANDROID_LOG_WARN, "home_patches: slot %s UNRESOLVED (hits=%d), skipped safely",
                 s->name, hits);
        }
    }

    g_slots_resolved = 1;
    hlog(ANDROID_LOG_INFO, "home_patches: resolve done ok=%d/%zu (fast=%d scan=%d)",
         ok_count, SLOT_COUNT, fast_count, scan_count);
    return 1;
}

/* ---------- 安全的原位指令写与恢复 ---------- */

static int write_slot_words(patch_slot_t *s, const uint32_t want[2]) {
    if (!s->resolved || s->resolved_addr == 0) return 0;
    uintptr_t addr = s->resolved_addr;

    /* 已经是目标内容 → 不碰内存页 */
    if (slot_target_matches_words(s, addr, want)) return 1;

    /* 写入前再验一次前后指纹，防止页映射异常 */
    if (!slot_surroundings_match(s, addr)) {
        hlog(ANDROID_LOG_WARN, "home_patches: slot %s surroundings changed, abort write", s->name);
        return 0;
    }

    long page_sz_raw = sysconf(_SC_PAGESIZE);
    uintptr_t page_sz = (page_sz_raw > 0) ? (uintptr_t)page_sz_raw : 4096u;
    size_t write_len = (size_t)s->word_count * 4u;
    uintptr_t page_start = addr & ~(page_sz - 1u);
    uintptr_t page_end = (addr + write_len + page_sz - 1u) & ~(page_sz - 1u);
    size_t map_len = (size_t)(page_end - page_start);

    if (mprotect((void *)page_start, map_len, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        hlog(ANDROID_LOG_WARN, "home_patches: mprotect(RWX) failed for %s errno=%d", s->name, errno);
        return 0;
    }

    if (s->word_count == 2 && (addr & 7u) == 0u) {
        /* 8 字节对齐双指令：单次 64-bit 原子写入，避免中间态 */
        uint64_t combined = (uint64_t)want[0] | ((uint64_t)want[1] << 32);
        __atomic_store_n((uint64_t *)addr, combined, __ATOMIC_RELEASE);
    } else {
        for (uint8_t i = 0; i < s->word_count; i++) {
            __atomic_store_n(((uint32_t *)addr) + i, want[i], __ATOMIC_RELEASE);
        }
    }

    __builtin___clear_cache((char *)addr, (char *)addr + write_len);

    if (mprotect((void *)page_start, map_len, PROT_READ | PROT_EXEC) != 0) {
        hlog(ANDROID_LOG_WARN, "home_patches: mprotect(RX) restore failed for %s errno=%d", s->name, errno);
    }
    return slot_target_matches_words(s, addr, want);
}

/* ---------- 跨进程配置读取（严格校验，异常即回退 0） ---------- */

static int parse_flags_str(const char *s, uint32_t *out) {
    if (s == NULL || s[0] == '\0') return 0;
    uint32_t v = 0;
    int digits = 0;
    for (const char *p = s; *p != '\0'; p++) {
        if (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') continue;
        if (*p < '0' || *p > '9') return 0;
        v = v * 10u + (uint32_t)(*p - '0');
        digits++;
        if (digits > 2 || v > HOME_FLAG_MASK) return 0;
    }
    if (digits == 0) return 0;
    *out = v;
    return 1;
}

static int read_fallback_file(uint32_t *out) {
    int fd = open(HOME_FALLBACK_CFG, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    char buf[128];
    ssize_t r = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (r <= 0) return 0;
    buf[r] = '\0';

    /* 严格格式：tsuzuri_home v=1 flags=<0..7> */
    if (strncmp(buf, "tsuzuri_home ", 13) != 0) return 0;
    const char *vpos = strstr(buf, "v=1");
    const char *fpos = strstr(buf, "flags=");
    if (vpos == NULL || fpos == NULL) return 0;
    fpos += 6;
    char num[8] = {0};
    size_t i = 0;
    while (fpos[i] >= '0' && fpos[i] <= '9' && i < sizeof(num) - 1) {
        num[i] = fpos[i];
        i++;
    }
    if (i == 0) return 0;
    return parse_flags_str(num, out);
}

static uint32_t read_desired_flags(const char **source_out) {
    uint32_t v = 0;
    /* 1. 可选兜底/调试文件优先（不存在时开销极低） */
    if (read_fallback_file(&v)) {
        *source_out = "file";
        return v;
    }
    /* 2. 主属性：debug.tsuzuri.home.flags */
    char prop[PROP_VALUE_MAX] = {0};
    if (__system_property_get(HOME_PROP_DEBUG, prop) > 0 && parse_flags_str(prop, &v)) {
        *source_out = "prop_debug";
        return v;
    }
    /* 3. 备选属性：sys.tsuzuri.home.flags */
    memset(prop, 0, sizeof(prop));
    if (__system_property_get(HOME_PROP_SYS, prop) > 0 && parse_flags_str(prop, &v)) {
        *source_out = "prop_sys";
        return v;
    }
    *source_out = "default_off";
    return 0u;
}

/* ---------- 调和状态 + 守护代码页不被重置 ---------- */

static void reconcile_locked(void) {
    const char *src = "none";
    uint32_t desired = read_desired_flags(&src);

    /*
     * 全部关闭且从未打过补丁：零足迹，连 libapp.so 扫描都延后到真的有开关打开再做，
     * 除非想在首次初始化时记录一次状态。
     */
    int any_applied_before = 0;
    for (size_t i = 0; i < SLOT_COUNT; i++) {
        if (g_slots[i].applied) any_applied_before = 1;
    }

    if (desired == 0u && !any_applied_before) {
        if (g_last_flags != 0u || strcmp(g_last_source, src) != 0) {
            hlog(ANDROID_LOG_INFO, "home_patches: flags=0 (%s), all features OFF (zero mprotect)", src);
            g_last_flags = 0u;
            snprintf(g_last_source, sizeof(g_last_source), "%s", src);
        }
        return;
    }

    if (!resolve_all_slots()) return;

    int flags_changed = (desired != g_last_flags) || (strcmp(g_last_source, src) != 0);
    int reapplied_after_reset = 0;
    int applied_total = 0;
    int style_ok = 0, drag_ok = 0, stack_ok = 0;

    for (size_t i = 0; i < SLOT_COUNT; i++) {
        patch_slot_t *s = &g_slots[i];
        if (!s->resolved) continue;
        int want_on = (desired & s->feature_bit) != 0u;
        if (want_on) {
            int was_patched_in_mem = slot_target_matches_words(s, s->resolved_addr, s->patch);
            if (!was_patched_in_mem) {
                if (write_slot_words(s, s->patch)) {
                    if (s->applied && !flags_changed) {
                        reapplied_after_reset++;
                    }
                    s->applied = 1;
                }
            } else {
                s->applied = 1;
            }
            if (s->applied) {
                applied_total++;
                if (s->feature_bit == HOME_FLAG_STYLE) style_ok++;
                else if (s->feature_bit == HOME_FLAG_DRAG_PA) drag_ok++;
                else if (s->feature_bit == HOME_FLAG_STACK) stack_ok++;
            }
        } else if (s->applied || slot_target_matches_words(s, s->resolved_addr, s->patch)) {
            if (write_slot_words(s, s->orig)) {
                s->applied = 0;
            }
        }
    }

    if (flags_changed) {
        hlog(ANDROID_LOG_INFO,
             "home_patches: flags %u->%u (%s) applied=%d/%zu (style=%d/4 drag_pa=%d/4 stack=%d/2)",
             g_last_flags == 0xffffffffu ? 0u : g_last_flags,
             desired, src, applied_total, SLOT_COUNT, style_ok, drag_ok, stack_ok);
        g_last_flags = desired;
        snprintf(g_last_source, sizeof(g_last_source), "%s", src);
    } else if (reapplied_after_reset > 0) {
        hlog(ANDROID_LOG_INFO,
             "home_patches: watchdog re-applied %d reset slot(s) (flags=%u applied=%d/%zu)",
             reapplied_after_reset, desired, applied_total, SLOT_COUNT);
    }
}

static void *watchdog_thread(void *arg) {
    (void)arg;
    prctl(PR_SET_NAME, "tsuzuri_wd", 0, 0, 0);
    for (;;) {
        sleep(WATCHDOG_INTERVAL_SEC);
        pthread_mutex_lock(&g_lock);
        reconcile_locked();
        pthread_mutex_unlock(&g_lock);
    }
    return NULL;
}

void home_widget_patches_on_libapp_ready(void) {
    pthread_mutex_lock(&g_lock);
    /* 无论看门狗是否已起，首次 libapp 就绪时同步调和一次，保证冷启动首屏布局就能命中 */
    reconcile_locked();
    if (!g_engine_started) {
        g_engine_started = 1;
        pthread_t tid;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        int rc = pthread_create(&tid, &attr, watchdog_thread, NULL);
        pthread_attr_destroy(&attr);
        if (rc != 0) {
            g_engine_started = 0;
            hlog(ANDROID_LOG_WARN, "home_patches: watchdog pthread_create failed rc=%d", rc);
        } else {
            hlog(ANDROID_LOG_INFO, "home_patches: watchdog started (interval=%us)", WATCHDOG_INTERVAL_SEC);
        }
    }
    pthread_mutex_unlock(&g_lock);
}
