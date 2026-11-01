#!/usr/bin/env bash
# tools/verify-release.sh —— release APK 产物校验
#
# 用法（Git Bash / Linux / macOS 均可）：
#   bash tools/verify-release.sh                    # 校验默认路径的 release APK
#   bash tools/verify-release.sh path/to/other.apk  # 校验指定 APK
#
# 为什么需要这个脚本：**构建成功 ≠ 模块可用**。下面每一条断言都对应一种
# 「Gradle 绿了、APK 也出来了，但装上 LSPosed 后什么都没发生」的故障：
#
#   ① META-INF/xposed/ 的三个元数据真的进包了 —— 防资源收缩 / 打包配置把元数据吃掉；
#   ② dex 总体积在预算内                       —— 防 R8 失效或有人引了大库；
#   ③ java_init.list 声明的入口类真在 dex 里    —— 防 R8 改了类名而 list 还是旧名；
#   ④ 版本号只有一个来源（A6）                  —— 防「关于」页显示的版本与 APK 里的漂移。
#
# 本脚本与 .github/workflows/ci.yml 的「产物校验」步骤逐条对应（④ 是本脚本多出来的）。
# 建议把 CI 那一步改成 `bash tools/verify-release.sh`，这样校验逻辑只有一份实现 ——
# 两份拷贝迟早会漂移，而「漂移的校验脚本」比没有校验更糟。
#
# 退出码：0 = 全部通过；1 = 有断言不成立（每条都会打印原因与定位方向）。

set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
DEX_BUDGET=$((6 * 1024 * 1024))   # 6MB

die() { echo "::error::$*" >&2; exit 1; }

# --- 前置：APK 与 apkanalyzer -------------------------------------------------
[ -f "$APK" ] || die "没找到 release APK：$APK（先跑 ./gradlew :app:assembleRelease）"
echo "release APK: $APK（$(stat -c%s "$APK" 2>/dev/null || wc -c <"$APK") bytes）"

# apkanalyzer 的位置不能写死 .../cmdline-tools/latest/bin：按版本号建目录
# （如 cmdline-tools/20.0/bin）才是默认形态，Windows 上还多一个 .bat 后缀。
# 所以：先找 latest，再按版本号排序取最大的一个，两种后缀都试。
find_apkanalyzer() {
    local base="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [ -n "$base" ] || return 1
    local c
    for c in "$base/cmdline-tools/latest/bin/apkanalyzer" "$base/cmdline-tools/latest/bin/apkanalyzer.bat"; do
        [ -f "$c" ] && { echo "$c"; return 0; }
    done
    local hits
    hits="$(ls -d "$base"/cmdline-tools/*/bin/apkanalyzer "$base"/cmdline-tools/*/bin/apkanalyzer.bat 2>/dev/null | sort -V || true)"
    [ -n "$hits" ] || return 1
    echo "$hits" | tail -n1
}
AA="$(find_apkanalyzer || true)"
[ -n "$AA" ] && [ -f "$AA" ] || die "找不到 apkanalyzer。请设 ANDROID_HOME，或用 sdkmanager 装 cmdline-tools。"
echo "apkanalyzer: $AA"

PKG_ENTRIES="$(unzip -Z1 "$APK")"

# === 校验 1：框架元数据必须真的进包 ==========================================
# 模块的 json/prop 元数据没有任何代码引用它，收缩器看不见引用者，最容易被误删。
# 症状：模块装上后 LSPosed 里根本看不到，或者看到了但永远不加载。
# 注意 module.prop 里的 description= 是无效键，这里只校验文件存在。
for f in META-INF/xposed/module.prop META-INF/xposed/java_init.list; do
    if ! grep -qxF "$f" <<<"$PKG_ENTRIES"; then
        die "$f 不在 release APK 里 —— 模块会被 LSPosed 当成普通 APK 忽略"
    fi
    echo "OK   已进包：$f"
done

# === 校验 2：dex 总体积预算 =================================================
# 模块 APK 的 classes.dex 会被 LSPosed 注入并 **mmap 进每一个 scope 进程**，
# 本仓库 scope.list 含 `system`（= system_server）。dex 大多少，系统每个被注入的
# 进程就多背多少 —— 这是拿系统内存换的。
# 越过预算基本只有两种原因：proguard 规则被改坏导致混淆失效，
# 或有人把大块库（典型是 material-icons-extended）引进了 :ui。两种都该在合并前拦住。
DEX_BYTES="$(unzip -l "$APK" | awk '$NF ~ /\.dex$/ { s += $1 } END { print s + 0 }')"
echo "dex 总体积：${DEX_BYTES} bytes（$(awk -v b="$DEX_BYTES" 'BEGIN { printf "%.2f", b/1048576 }') MB），预算 ${DEX_BUDGET} bytes（6 MB）"
if [ "$DEX_BYTES" -gt "$DEX_BUDGET" ]; then
    die "dex 超预算 ${DEX_BYTES} > ${DEX_BUDGET}。先确认 R8 还在生效（isMinifyEnabled / proguard-rules.pro），再查是不是新引了大依赖。"
fi

# === 校验 3：java_init.list 的入口类确实存在于 dex 中 ========================
# 最隐蔽的一种失败：R8 改了入口类名而 list 里还是旧名。表现是模块能勾选、scope
# 也配好了，但类永远加载不到，日志里什么都不打 —— 看起来像「功能没实现」。
# 本仓库用 -keep 保住 HookEntry 原名（不用 -adaptresourcefilecontents），
# 所以「list 里的字符串」必须与「dex 里的类名」逐字相等。
INIT_LIST="$(unzip -p "$APK" META-INF/xposed/java_init.list | tr -d '\r')"
echo "java_init.list 内容："
echo "$INIT_LIST"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$AA" dex packages "$APK" > "$TMP/dex-packages.txt"
echo "apkanalyzer dex packages 输出 $(wc -l < "$TMP/dex-packages.txt") 行"

FOUND=0
while IFS= read -r cls; do
    cls="$(echo "$cls" | tr -d '[:space:]')"
    [ -z "$cls" ] && continue
    case "$cls" in
        \#*) echo "warn 跳过注释行：$cls"; continue ;;
    esac
    # apkanalyzer 的类行格式：C d <methods> <fields> <bytes> <FQCN>，最后一列就是类名
    if awk -v c="$cls" '$1 == "C" && $NF == c { found = 1 } END { exit !found }' "$TMP/dex-packages.txt"; then
        echo "OK   入口类在 dex 中：$cls"
        FOUND=$((FOUND + 1))
    else
        die "java_init.list 声明的入口类 $cls 不在 dex 里 —— 模块会「装上了但不生效」"
    fi
done <<< "$INIT_LIST"
[ "$FOUND" -gt 0 ] || die "java_init.list 里没有任何入口类，模块加载不起来"
echo "产物校验通过：$FOUND 个入口类全部命中。"

# === 校验 4：版本号只有一个来源（A6） ========================================
# 版本号的唯一来源是 gradle/libs.versions.toml 的 [versions].tsuzuri。
# 这一条守两件事：
#   a) APK 的 versionCode 确实由 versionName 按 major*10000+minor*100+patch 推出来；
#   b) :ui 的 BuildConfig.MODULE_VERSION（「关于」页显示的那一行）与 versionName
#      是同一个字符串 —— R8 会把 BuildConfig 常量内联进 dex，所以在 dex 的字符串池
#      里必须能找到与 versionName 逐字相同的字面量。找不到就说明有人写死了第二份版本号。
# 说明：这是**尽力而为的漂移探测**，不是证明；但它能挡住最常见的「两处各写一份」。
VERSION_NAME="$("$AA" manifest version-name "$APK" | tr -d '\r')"
VERSION_CODE="$("$AA" manifest version-code "$APK" | tr -d '\r')"
echo "APK manifest: versionName=$VERSION_NAME versionCode=$VERSION_CODE"

if [[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    IFS='.' read -r V_MAJOR V_MINOR V_PATCH <<<"$VERSION_NAME"
    EXPECTED_CODE=$((10#$V_MAJOR * 10000 + 10#$V_MINOR * 100 + 10#$V_PATCH))
    if [ "$VERSION_CODE" != "$EXPECTED_CODE" ]; then
        die "versionCode=$VERSION_CODE 与 versionName=$VERSION_NAME 推出来的 $EXPECTED_CODE 不一致 —— versionCode 必须由版本号推导（app/build.gradle.kts）"
    fi
    echo "OK   versionCode 由 versionName 推导一致：$VERSION_NAME → $VERSION_CODE"

    # 注意：这里先把 dex 的字符串池抓进变量再 grep，**不能**写成
    # `... | tr ... | grep -q` —— grep -q 命中即退出，上游 tr 收到 SIGPIPE(141)，
    # 在 pipefail 下整条管道会变成失败，于是「明明匹配却报不匹配」。
    DEX_NAMES="$(unzip -Z1 "$APK" | grep -E '\.dex$' || true)"
    if [ -n "$DEX_NAMES" ]; then
        DEX_STRINGS="$(while IFS= read -r d; do unzip -p "$APK" "$d"; done <<<"$DEX_NAMES" | tr -c '[:print:]' '\n')"
        if grep -qxF "$VERSION_NAME" <<<"$DEX_STRINGS"; then
            echo "OK   dex 中存在与 versionName 相同的字面量（BuildConfig.MODULE_VERSION 同源）"
        else
            die "dex 字符串池里找不到版本号 \"$VERSION_NAME\" —— :ui 的 BuildConfig.MODULE_VERSION 可能被写成了另一份字面量（A6 要求两处同源）"
        fi
    fi
else
    echo "warn versionName 不是 major.minor.patch 形式（$VERSION_NAME），跳过 versionCode 推导校验"
fi

echo
echo "全部产物校验通过。"
