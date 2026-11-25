/*
 * home_overlay_hooks.h —— 澎湃桌面（com.miui.home / libapp.so）遮挡状态原生观察器
 *
 * 捕获 5 组桌面模态态（抽屉 / 文件夹 / 编辑态 / 小部件预览 / 堆叠展开），
 * 通过本地回环 UDP（127.0.0.1:49731）实时推送给 SystemUI 的侧滑索引门控，
 * 零 root 依赖、零预建文件；默认全关时零 hook、零写页、零线程。
 */
#ifndef TSUZURI_HOME_OVERLAY_HOOKS_H
#define TSUZURI_HOME_OVERLAY_HOOKS_H

#include <stdint.h>
#include "native_hook.h"

/* 与 core/LauncherOverlaySignal.kt 保持逐字一致 */
#define OVERLAY_UDP_HOST  "127.0.0.1"
#define OVERLAY_UDP_PORT  49731
#define OVERLAY_FILE_PATH "/data/local/tmp/fp_launcher_state.txt"

/* 在 native_init 里保存 LSPosed 提供的 inline hook 入口。 */
void home_overlay_hooks_set_api(HookFunType hook_fn);

/* 当前是否已有任一遮挡观察 hook 处于激活态（供看门狗决定是否能早退）。 */
int home_overlay_hooks_is_active(void);

/*
 * 与 home_widget_patches 的看门狗共用节拍调用（须在 libapp.so 已定位后调用，内部自带锁保护）。
 * want_on = (desired_flags & HOME_FLAG_OVERLAY_GATE) != 0。
 */
void home_overlay_hooks_reconcile(uintptr_t libapp_base, uintptr_t exec_start, uintptr_t exec_end, int want_on);

#endif /* TSUZURI_HOME_OVERLAY_HOOKS_H */
