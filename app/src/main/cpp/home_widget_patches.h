/*
 * home_widget_patches.h —— 澎湃桌面（com.miui.home）小部件原生补丁引擎接口
 *
 * 与 core/DesktopWidget.kt 的位定义和属性名必须逐字一致。
 */
#ifndef TSUZURI_HOME_WIDGET_PATCHES_H
#define TSUZURI_HOME_WIDGET_PATCHES_H

#include <stdint.h>

/* 7-bit 开关掩码，与 core/DesktopWidget.kt 一一对应 */
#define HOME_FLAG_STYLE            (1u << 0) /* 核心风格：外框网格 + 尺寸通知 + 分档圆角 + 动画圆角 */
#define HOME_FLAG_DRAG_PA          (1u << 1) /* 桌面小部件任意拖入负一屏 */
#define HOME_FLAG_STACK            (1u << 2) /* 桌面任意小部件堆叠 */
#define HOME_FLAG_STYLE_NO_WORD    (1u << 3) /* 风格子项：跟随系统无字模式 / 标题显隐 */
#define HOME_FLAG_STYLE_DRAG_LIGHT (1u << 4) /* 风格子项：统一拖拽流光描边 */
#define HOME_FLAG_STYLE_UNIFY_SCALE (1u << 5) /* 风格子项：关闭非 MIUI 缩小系数，统一渲染比例 */
#define HOME_FLAG_OVERLAY_GATE     (1u << 6) /* 桌面遮挡原生信号：抽屉 / 文件夹 / 编辑态 / 预览 / 堆叠展开 */
#define HOME_FLAG_MASK             (HOME_FLAG_STYLE | HOME_FLAG_DRAG_PA | HOME_FLAG_STACK | \
                                    HOME_FLAG_STYLE_NO_WORD | HOME_FLAG_STYLE_DRAG_LIGHT |  \
                                    HOME_FLAG_STYLE_UNIFY_SCALE | HOME_FLAG_OVERLAY_GATE)

/* 跨进程配置通道（system_server 写，com.miui.home 只读） */
#define HOME_PROP_DEBUG   "debug.tsuzuri.home.flags"
#define HOME_PROP_SYS     "sys.tsuzuri.home.flags"
#define HOME_FALLBACK_CFG "/data/local/tmp/tsuzuri_home.cfg"

/*
 * 在 com.miui.home 进程中当 libapp.so 就绪（或已经映射）时调用。
 * 幂等、线程安全：多次调用只会初始化一次并启动一个轻量看门狗线程。
 * 当所有开关为 0 时零内存修改（不调用 mprotect）。
 */
void home_widget_patches_on_libapp_ready(void);

#endif /* TSUZURI_HOME_WIDGET_PATCHES_H */
