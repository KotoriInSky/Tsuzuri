# LSPosed native 入口库（只观察、不装 hook）。
#
# 关键点：
#   - 只导出 native_init 一个符号 → 默认隐藏可见性，靠源码里的 [[gnu::visibility("default")]] 打开。
#   - 不链接 libc++（纯 C，无 STL），减少被 hook 进程里引入新运行时的面。
#   - 依赖只有 liblog（打日志）与 libdl（dlopen/dlsym 探 Binder 可达性）。
cmake_minimum_required(VERSION 3.22.1)
project(fpnative C)

add_library(fpnative SHARED fpnative.c)

target_compile_options(fpnative PRIVATE -Wall -Wextra -fvisibility=hidden -O2)
target_link_options(fpnative PRIVATE -Wl,--no-undefined)

target_link_libraries(fpnative log dl)

# 传送门动图转 GIF 编码器：只在用户保存/分享动图 HEIF 时由 contentextension 按需加载。
add_library(tsgif SHARED tsgif.c)
target_compile_options(tsgif PRIVATE -Wall -Wextra -fvisibility=hidden -O3)
target_link_options(tsgif PRIVATE -Wl,--no-undefined -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/tsnative.map)

# 传送门动图转动态 WebP：libwebp 1.5.0（BSD-3，third_party/libwebp，官方发布包 sha256
# 7d6fab70cf844bf6769077bd5d7a74893f8ffd4dfb42861745750c63c2a5c92c）静态编进 libtswebp.so。
# 不用 libwebp 自带的 CMake，直接列源码；demux 未引入。
set(WEBP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libwebp)
file(GLOB WEBP_SOURCES
    ${WEBP_DIR}/src/enc/*.c ${WEBP_DIR}/src/dec/*.c ${WEBP_DIR}/src/dsp/*.c
    ${WEBP_DIR}/src/utils/*.c ${WEBP_DIR}/src/mux/*.c ${WEBP_DIR}/sharpyuv/*.c)
add_library(tswebp SHARED tswebp.c ${WEBP_SOURCES})
target_include_directories(tswebp PRIVATE ${WEBP_DIR})
target_compile_options(tswebp PRIVATE -fvisibility=hidden -O2 -ffunction-sections -fdata-sections -w)
target_link_options(tswebp PRIVATE -Wl,--no-undefined -Wl,--gc-sections
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/tsnative.map)
target_link_libraries(tswebp m)
