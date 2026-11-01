#pragma once

/*
 * LSPosed（libxposed 现代 API）的 native 入口 ABI。
 *
 * 来源：LSPosed wiki「Native Hook」页，2026-09-14 抄录。
 * 与 `io.github.libxposed:api:102.0.0` **无关** —— 那个 aar 里没有任何 native 头文件
 * （已解包确认：只有 classes.jar / R.txt / lint.jar），native 入口是 LSPosed 框架
 * 自己扩展出来的一层。所以本文件是手抄的 ABI，不是依赖产物。
 *
 * 约定：
 *   - 库名写在 `META-INF/xposed/native_init.list`（一行一个库名）。
 *   - 入口符号必须叫 `native_init`，且必须**导出**（见下方 [[gnu::visibility]]）。
 *   - `native_init` 返回一个回调，框架在该进程每次 `dlopen` 完一个库之后回调它。
 *   - **不要修改框架传进来的 `NativeAPIEntries` 内容**（官方原文：改了会崩）。
 */

#include <stdint.h>

/* 成功返回 0 */
typedef int (*HookFunType)(void *func, void *replace, void **backup);
/* 成功返回 0 */
typedef int (*UnhookFunType)(void *func);

/* 框架在该库被 dlopen 之后回调，name 是库名，handle 是 dlopen 的句柄 */
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);
