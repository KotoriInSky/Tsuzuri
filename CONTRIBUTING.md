# 贡献指南

## 反馈问题

请通过 [Issue](https://github.com/KotoriInSky/Tsuzuri/issues) 反馈，并提供：

- LSPosed 日志，可在 LSPosed 管理器的日志页导出
- 设备型号、系统版本和 Tsuzuri 版本
- 复现步骤

上传前请检查日志中是否有隐私内容。

代码改动请提交 [Pull Request](https://github.com/KotoriInSky/Tsuzuri/pulls)。

## 环境

- JDK 21
- Android SDK 37
- 依赖版本统一写在 `gradle/libs.versions.toml`

```bash
./gradlew :core:test :ui:verifyStringKeys :app:assembleDebug :app:assembleRelease
```

提交前请确保以上命令通过。改动混淆规则、依赖或版本号后，再运行：

```bash
bash tools/verify-release.sh
```

## 开发约定

- `:hook` 不能依赖 `:ui`，Xposed API 只能出现在 `:hook`。
- `:core` 不能依赖 Android、Compose 或 Xposed。
- 新功能必须有独立开关，默认关闭。关闭后不安装对应 hook。
- 厂商私有类通过反射访问，匹配失败时要安静降级。
- `com.miui.home` 没有 ART，不要尝试 hook。
- 不修改系统分区，不写入桌面数据。
- 不提交反编译产物，也不提交厂商的代码、图标、字体或动画。
- 不硬编码设备序列号。
- 界面文案、注释和提交信息使用中文，标识符使用英文。

## 验证

行为改动要在真机上检查：

1. 功能开启和关闭时都正常。
2. 状态栏、控制中心、锁屏和负一屏正常。
3. 重启 SystemUI 后恢复正常。
4. 卸载模块后没有残留改动。

请在 PR 中说明验证设备和系统版本。没有真机验证时也请写明。

## 提交

提交信息使用 Conventional Commits，标题和正文用中文说明改动原因：

```text
fix(sideindex): 修复横屏时面板遮挡字母条
```

一个提交只做一件事，不要把格式化或重命名混入功能改动。

## 发布一个版本

1. 修改 `gradle/libs.versions.toml` 中的 `tsuzuri` 版本号。
2. 更新 `CHANGELOG.md`。
3. 配置签名后构建，并运行 `bash tools/verify-release.sh`。
4. 打 tag，在 GitHub Releases 上传 release 和 debug 两个 APK。

签名通过用户级 `~/.gradle/gradle.properties` 或环境变量配置：

| Gradle 属性 | 环境变量 |
| --- | --- |
| `fp.keystore.path` | `FP_KEYSTORE_PATH` |
| `fp.keystore.alias` | `FP_KEYSTORE_ALIAS` |
| `fp.keystore.password` | `FP_KEYSTORE_PASSWORD` |

不要把 keystore 或口令提交到仓库。未配置签名时，release 包使用 debug 签名，不能对外分发。
