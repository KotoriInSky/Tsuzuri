# 第三方参考与许可

本项目自身以 [MIT 许可](LICENSE) 发布。下面直接包含的第三方内容（MIT、Apache 2.0、MPL 2.0、BSD-3-Clause）均与 MIT 兼容，按各自要求保留声明；App 的「关于 → 第三方许可」链接到本文件。

本仓库是 HyperOS 4 的 LSPosed 模块，**没有复制**「参考」一节所列项目的代码；
引用它们的地方都是**事实**（域名、URL 形态、接口端点、字段名）与**思路**，实现均为自写。
为了合规与致谢，这里逐项声明来源与许可。

## 框架与运行依赖

通过 Maven 依赖使用，或在运行时由框架提供，均未修改。

| 项目 | 许可 | 用途 |
| --- | --- | --- |
| [LSPosed](https://github.com/LSPosed/LSPosed) | GPL-3.0 | 运行时框架，不随模块分发 |
| [libxposed/api](https://github.com/libxposed/api) | Apache-2.0 | 模块 API，`compileOnly`，不打进 APK |
| [libxposed/service](https://github.com/libxposed/service) | Apache-2.0 | 设置界面写入远端配置 |
| [Kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 | 语言与标准库 |
| [AndroidX 与 Jetpack Compose](https://github.com/androidx/androidx) | Apache-2.0 | 设置界面 |
| [MiuiX](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 | HyperOS 风格组件 |
| [Backdrop（Kyant0/AndroidLiquidGlass）](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | 液态玻璃 |
| [MaterialKolor（material-color-utilities）](https://github.com/jordond/MaterialKolor) | MIT | Material 3 取色算法 |

## 参考并已声明许可的项目

| 项目 | 许可 | 我们借用了什么 | 落在哪里 |
| --- | --- | --- | --- |
| [Zhalslar/astrbot_plugin_parser](https://github.com/Zhalslar/astrbot_plugin_parser) | MIT | 平台 URL 形态与字段名（逐平台读代码取证） | `core/…/passcode_rules.json` 的部分域名与口令规则 |
| [sokoko-org/nonebot-plugin-parser-lite](https://github.com/sokoko-org/nonebot-plugin-parser-lite) | **MIT** | ① 抖音 `ensure_ttwid()` 的做法（**这直接让抖音从"做不到"变成能做到**）；② 一批平台的 URL 形态（`@handle(...)` 声明） | `core/DouyinShare.kt`、`core/…/passcode_rules.json` 的 hosts |
| [fllesser/nonebot-plugin-parser](https://github.com/fllesser/nonebot-plugin-parser) | MIT | 公开测试链接清单（真实分享 URL，用于真机取证取样） | 未进入仓库，只用于真机取证 |
| [z-mio/ParseHub](https://github.com/z-mio/ParseHub) | **MIT** | 各平台**查询参数清理清单**（`__reserved_parameters__` / `__after_clean_parameters__`）→ 用于内容身份归一化；另有皮皮虾/最右/酷安的做法待落地 | `core/UrlCleanup.kt` |
| [FxEmbed/FxEmbed](https://github.com/FxEmbed/FxEmbed) | MIT | X/Twitter 的公开只读接口 | X 链接的元信息获取 |
| [wujunwei928/parse-video](https://github.com/wujunwei928/parse-video) | MIT | `parser/vars.go` 里各平台的分享域（`VideoShareUrlDomain`） | `core/…/passcode_rules.json` 的 hosts、`PasscodeParseVideoShapesTest` |
| [iawia002/lux](https://github.com/iawia002/lux) | MIT | `extractors` 各站点的注册域（极客时间、糖豆、网易新闻） | 同上 |
| [microlinkhq/metascraper](https://github.com/microlinkhq/metascraper) | MIT | title / image / author / description 各规则包里「按什么顺序从哪儿取」的优先级（只借 meta、`<title>`、JSON-LD 部分） | `core/PageMetaExtract.kt` |
| [mat/besticon](https://github.com/mat/besticon) | MIT | 「网站最好的图标是哪个」的挑选思路：apple-touch-icon / icon 按声明尺寸取最大 | `core/PageMetaExtract.kt` 的 `bestIcon` |
| [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp) | **Unlicense**（公有领域，无署名义务，此处为致谢） | 各中文平台抽取器 `_VALID_URL` 的链接形态与 `_TESTS` 样例链接 | `core/…/passcode_rules.json`、`core/PasscodeIsland.kt` 的 `contentCore`、`PasscodeYtDlpShapesTest` |

## 直接包含其代码/数据的项目

| 项目 | 许可 | 包含了什么 | 落在哪里 |
| --- | --- | --- | --- |
| [jkeen/tracking_number_data](https://github.com/jkeen/tracking_number_data) | **MIT**（Copyright (c) 2017 Jeff Keen） | S10 / UPS / FedEx / DHL / USPS / 亚马逊 / 云途的单号形态、校验位算法与参数、S10 国别码对应的承运商 | `core/ExpressIntl.kt`（文件头附完整版权与 MIT 许可全文） |
| [iamcal/oembed](https://github.com/iamcal/oembed) | **MIT**（Copyright (c) 2016 Cal Henderson） | `providers.json` 原样（393 家站点的 oEmbed 链接形态与接口地址） | `core/src/main/resources/.../oembed_providers.json`，许可全文在同目录 `oembed_providers.LICENSE.txt`；读取见 `core/OEmbed.kt` |
| [mpchadwick/tracking-query-params-registry](https://github.com/mpchadwick/tracking-query-params-registry) | **Unlicense**（公有领域，无署名义务，此处为致谢） | `_data/params.csv` 的参数名（去掉 `utm_*` 与易撞名的短名后 138 项） | `core/UrlCleanup.kt` 的 `GENERIC` |
| [Public Suffix List](https://publicsuffix.org/list/) | **MPL-2.0** | `public_suffix_list.dat` 原样（VERSION 2026-10-20_08-40-56_UTC） | `core/src/main/resources/.../public_suffix_list.dat`（文件头自带 MPL 声明；MPL 按文件生效，只约束这一个数据文件） |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | **Apache License 2.0**（Copyright 2025 Kyant） | backdrop 的 AGSL 着色器（圆角 SDF、高光、折射 + 色散）；示例 App 的 LiquidBottomTabs 及其拖动 / 高光工具类 | `hook/.../sideindex/SideIndexGlassPanel.kt`、`SideIndexLiquidLayer.kt`、`ui/.../glass/kyant/`（文件头注明来源与改动） |
| [libwebp](https://chromium.googlesource.com/webm/libwebp) 1.5.0 | **BSD-3-Clause**（Copyright (c) 2010, Google Inc.）及附带的专利授权 | `src/`（enc / dec / dsp / utils / mux）与 `sharpyuv/` 源码原样，未修改；构建脚本自写，demux 未引入。官方发布包 sha256 `7d6fab70cf844bf6769077bd5d7a74893f8ffd4dfb42861745750c63c2a5c92c` | `app/src/main/cpp/third_party/libwebp/`（同目录 `COPYING`、`PATENTS`、`AUTHORS` 原样保留），静态编进 `libtswebp.so`，供传送门动图保存为 WebP |
| [Material Icons](https://github.com/google/material-design-icons)（经 `androidx.compose.material:material-icons-extended` 1.7.8 源码） | **Apache License 2.0** | 16 个 Rounded 图标的矢量路径数据（原样） | `ui/.../ui/icon/FpGlyphs.kt`（脚本生成，文件头注明来源） |

> Apache 2.0 要求随分发附上许可声明并标明修改；路径数据未修改，仅换了容器（`object FpGlyphs` 的懒加载属性）。
> 许可全文：<https://www.apache.org/licenses/LICENSE-2.0>。HyperCeiler 风格的「不复制」原则不适用于此项 ——
> 这是许可允许的直接使用。

> MIT 的要求是：**在副本或实质性部分中保留版权声明与许可声明**。
> 我们的做法是：不复制其代码（跨语言，本来也是重写），并在本文件与相关文档中注明来源；
> 若将来真的贴入其代码片段，必须同时附上该项目的完整版权头与 MIT 许可全文。

## 只作对照、**未使用其任何代码**的项目

| 项目 | 许可 | 说明 |
| --- | --- | --- |
| [NanmiCoder/MediaCrawler](https://github.com/NanmiCoder/MediaCrawler) | **非商业学习使用许可证 1.1**（非标准开源） | 只用来**交叉验证**"某平台的接口要不要签名/登录"。因为它的许可**不允许商用**、且它是"浏览器自动化 + 登录态"的架构，我们**不借鉴其代码**、只登记事实 |

## 设计与格式参考

| 项目 | 许可 | 说明 |
| --- | --- | --- |
| [ReChronoRain/HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) | AGPL-3.0 | 参考设置页组织方式，未使用其代码 |
| [1812z/HyperIsland](https://github.com/1812z/HyperIsland) | MIT | 超级岛方案对照，未使用其代码 |
| [1812z/HyperCopy](https://github.com/1812z/HyperCopy) | 未声明 | 自定义规则可导入其 `rules.json` 格式，未使用其代码 |

## 明确不复制的来源

- **Flyme / 澎湃 OS 的反编译产物**：只读行为。`research/` 目录不入库（`.gitignore`），
  也不把厂商的代码或资源（图标、字体、动画、MAML）带进本仓库。
