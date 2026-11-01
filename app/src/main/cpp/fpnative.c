/*
 * fpnative.c —— LSPosed native 入口探针（观察用，零副作用）
 *
 * 为什么有这个文件
 * ----------------
 * 桌面 `com.miui.home` 是无 ART 的 Dart/Rust 原生进程，Java hook 进不去，
 * 只能靠 native 入口。跑通这条路有两条候选：
 *   (A) 独立 Zygisk Next 模块：`/data/adb/modules/<mod>/zn_modules.txt`
 *       —— 曾在 experiments/zn-probe 里“跑通”，但 2026-09-14 实测它把设备搞崩了：
 *          桌面崩溃的 pc 就在那个模块自己的 .so 里，且该模块被注入了**每一个普通应用进程**。
 *   (B) 模块 APK 自带的 native 入口：`META-INF/xposed/native_init.list`（本文件这条路）
 *       —— LSPosed 框架原生支持，**单包安装**，作用域由 scope.list 管，不额外注册 ZN 模块。
 *
 * 这个探针只回答两件事，别让它干别的：
 *   1. 我们的 .so 到底会不会被框架加载？加载到**哪个进程**、以什么身份？
 *   2. 在桌面进程里，「读配置 / 回报状态」三条候选通道（文件 / unix socket / Binder）哪条通？
 *
 * 纪律（上一条探针的教训）
 * ------------------------
 *   - **不装任何 hook**（不碰 `hook_func`）、不改任何返回值、不起线程。
 *   - **默认完全惰性**：加载后只打一行「我是谁」；所有通道探测都要 `persist.fp.native.probe=1`
 *     显式打开。关掉时行为与「模块没装 native 部分」完全一致。
 *   - 唯一的例外是 `persist.fp.native.listen=1` 时的 `bind+listen`（见 maybe_listen）：
 *     它只为了回答「桌面连得过来吗」这一个问题，**不 accept、不起线程、不读数据**。
 *   - 探测动作全部是只读或必然失败的尝试（open 一个只读文件、试着 write 一个文件、
 *     connect 一个不存在 listener 的 socket、dlopen libbinder 取个 service 指针）。
 *     不启动服务、不注册 receiver、不发广播。
 */

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <sys/un.h>
#include <unistd.h>

#include "native_hook.h"

#define TAG "FPNative"

/* 探测开关：默认关。任何通道探测都必须显式打开它。 */
#define PROP_PROBE "persist.fp.native.probe"
/* 「在本进程 listen 一个抽象 socket」的单独开关（默认关），用途见 maybe_listen()。 */
#define PROP_LISTEN "persist.fp.native.listen"

/* 配置进（root 写好、桌面进程只读）与状态出的候选路径。
 * ⚠️ `SOCK_NAME` 必须与桌面侧探针（experiments/hyos-entry/fphyos.c）里的**完全一致** ——
 * 第一次测的时候两边写成了不同的名字，结果桌面 connect 到一个没人 listen 的名字上，
 * 拿到 ECONNREFUSED，白测一轮。改名字要两边一起改。 */
#define CFG_PATH "/data/local/tmp/fp_native_cfg.txt"
#define OUT_PATH "/data/local/tmp/fp_native_out.txt"
#define SOCK_NAME "fp_hyos_probe"

static void logi(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, ap);
    va_end(ap);
}

static int probe_enabled(void) {
    char v[PROP_VALUE_MAX] = {0};
    int n = __system_property_get(PROP_PROBE, v);
    return n > 0 && v[0] == '1';
}

/* 该进程是谁：cmdline + uid + 线程名。三个都要，因为 hyos_spawner fork 出的子进程
 * 在 specialize 前后 cmdline 才变成 com.miui.home。 */
static void identity(char *cmdline, size_t cmdline_sz, int *uid, char *tname, size_t tname_sz) {
    cmdline[0] = '\0';
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t r = read(fd, cmdline, cmdline_sz - 1);
        if (r > 0) cmdline[r] = '\0'; /* cmdline 是 \0 分隔的，第一个 \0 就是进程名 */
        close(fd);
    }
    *uid = (int)getuid();
    tname[0] = '\0';
    int fd2 = open("/proc/self/comm", O_RDONLY | O_CLOEXEC);
    if (fd2 >= 0) {
        ssize_t r2 = read(fd2, tname, tname_sz - 1);
        if (r2 > 0) {
            tname[r2] = '\0';
            size_t l = strlen(tname);
            if (l > 0 && tname[l - 1] == '\n') tname[l - 1] = '\0';
        }
        close(fd2);
    }
}

/* ---------- 三条候选通道：只探测可达性，不做任何持久化 ---------- */

static void probe_read_config(void) {
    int fd = open(CFG_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        logi("CH cfg read: FAIL errno=%d", errno);
        return;
    }
    char buf[96] = {0};
    ssize_t r = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (r < 0) r = 0;
    buf[r] = '\0';
    logi("CH cfg read: OK len=%d head=%s", (int)r, buf);
}

static void probe_write_state(void) {
    int fd = open(OUT_PATH, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) {
        logi("CH out write: FAIL errno=%d", errno);
        return;
    }
    ssize_t r = write(fd, "fp native probe\n", 16);
    close(fd);
    logi("CH out write: OK wrote=%d", (int)r);
}

static void probe_unix_socket(void) {
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) {
        logi("CH sock: socket() FAIL errno=%d", errno);
        return;
    }
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    /* 抽象命名空间：\0 开头，不落文件系统，所以不受目录权限限制 */
    memcpy(sa.sun_path + 1, SOCK_NAME, strlen(SOCK_NAME));
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCK_NAME));
    if (connect(s, (struct sockaddr *)&sa, len) < 0) {
        logi("CH sock connect: FAIL errno=%d", errno);
        close(s);
        return;
    }
    ssize_t r = write(s, "hello", 5);
    logi("CH sock connect: OK write=%d", (int)r);
    close(s);
}

static void probe_binder(void) {
    void *h = dlopen("libbinder.so", RTLD_NOW | RTLD_LOCAL);
    if (h == NULL) {
        logi("CH binder: dlopen FAIL errno=%d", errno);
        return;
    }
    typedef void *(*get_service_t)(const char *);
    get_service_t get_service = (get_service_t)dlsym(h, "AServiceManager_getService");
    if (get_service == NULL) {
        logi("CH binder: dlsym FAIL");
        dlclose(h);
        return;
    }
    void *svc = get_service("activity");
    logi("CH binder: AServiceManager_getService(\"activity\") = %p", svc);
    /* 不 dlclose：进程可能仍在用；探针不改变进程的库生命周期 */
}

/* ---------- 「状态出」通道的最小验证：在本进程 bind+listen 一个抽象 socket ---------- */

/*
 * 桌面进程（uid 10148 / privapp）连到 `\0fp_hyos_probe` 时返回的是 ECONNREFUSED（没人 listen），
 * **不是 EACCES** —— 说明那一步没被 SELinux 拦住，只差一个 listener。这条通道能不能用，
 * 取决于「launcher → 本进程（SystemUI）」这条 unix stream 连接许不许，而这只有真的 listen 起来才知道。
 *
 * 所以这里**只 bind + listen**：不 accept、不起线程、不读数据。连接会停在 backlog 里，
 * 而对面 `connect()` 成功本身就回答了问题。跑完之后这个 fd 就留着不动。
 */
static int g_listen_fd = -1;

static void maybe_listen(void) {
    if (g_listen_fd >= 0) return;
    if (!probe_enabled()) return;
    char v[PROP_VALUE_MAX] = {0};
    int n = __system_property_get(PROP_LISTEN, v);
    if (n <= 0 || v[0] != '1') return;

    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) {
        logi("LISTEN socket FAIL errno=%d", errno);
        return;
    }
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    memcpy(sa.sun_path + 1, SOCK_NAME, strlen(SOCK_NAME));
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCK_NAME));
    if (bind(s, (struct sockaddr *)&sa, len) < 0) {
        logi("LISTEN bind FAIL errno=%d", errno);
        close(s);
        return;
    }
    if (listen(s, 4) < 0) {
        logi("LISTEN listen FAIL errno=%d", errno);
        close(s);
        return;
    }
    g_listen_fd = s;
    logi("LISTEN ok: abstract \"%s\" 已就绪 fd=%d", SOCK_NAME, s);
}

/* ---------- 框架回调 ---------- */

static int g_probed_in_this_process;

static void probe_all_once(void) {
    if (g_probed_in_this_process) return;
    g_probed_in_this_process = 1;
    probe_read_config();
    probe_write_state();
    probe_unix_socket();
    probe_binder();
}

static void on_lib_loaded(const char *name, void *handle) {
    if (!probe_enabled()) return;
    logi("dlopen name=%s handle=%p", name != NULL ? name : "(null)", handle);

    /* 桌面链路的终点库：它在谁手里被 dlopen，就说明我们确实在桌面进程里 */
    if (name != NULL && (strstr(name, "libapp_launcher.so") != NULL ||
                         strstr(name, "libapp.so") != NULL)) {
        char cmdline[128], tname[32];
        int uid = -1;
        identity(cmdline, sizeof(cmdline), &uid, tname, sizeof(tname));
        logi("LAUNCHER lib loaded: cmdline=%s uid=%d comm=%s", cmdline, uid, tname);
        probe_all_once();
    }
}

/* 入口符号：名字必须是 native_init，必须导出。 */
__attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    char cmdline[128], tname[32];
    int uid = -1;
    identity(cmdline, sizeof(cmdline), &uid, tname, sizeof(tname));

    /* 这一行**不受开关控制**：它唯一的作用是回答「模块的 native 库被加载进哪些进程」，
     * 而这个问题只有真的加载了才答得出来。默认构建下，这是本库唯一的输出。 */
    logi("native_init pid=%d uid=%d cmdline=%s comm=%s entries=%p ver=%u",
         (int)getpid(), uid, cmdline, tname, (const void *)entries,
         entries != NULL ? entries->version : 0u);

    if (entries != NULL && entries->version < 2U) {
        /* 版本对不上就别往下走：宁可什么都不做，也不要按猜的布局调函数 */
        logi("native_init: unexpected entries->version=%u, staying inert", entries->version);
        return NULL;
    }

    /* 「状态出」通道的最小验证：需要 persist.fp.native.listen=1（默认关）。 */
    maybe_listen();

    /* 顺带测一次「本进程（SystemUI）能不能读那个共享文件」——
     * 桌面侧已经证明能写（0666）、能读（0644），而消费这些状态的正是 SystemUI，
     * 所以 SystemUI 这一侧的读能力才是那条通道成不成立的关键。 */
    if (probe_enabled()) probe_read_config();

    /* 注意：这里**故意不调用** entries->hook_func —— 本探针不装 hook。
     * hook_func 的签名 (func, replace, backup) 是 inline hook，装错了就是崩桌面。 */

    return on_lib_loaded;
}
