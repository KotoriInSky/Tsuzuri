pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tsuzuri"

// 模块边界 = 前后端边界，用 Gradle 强制，而不是靠自觉。
//
//   :core  纯 Kotlin/JVM。前后端共享的「单一事实来源」：特性清单、参数描述、配置读写。
//          不依赖 Android，也不依赖 Compose —— 它是契约层。
//   :hook  LSPosed 后端。被注入进 SystemUI / 负一屏 / system_server 的代码。
//          **绝对不能依赖 :ui** —— 否则 Compose 会被带进 system_server。
//   :ui    前端。Compose 设置界面，跑在模块 App 自己的进程里。
//   :app   打包壳。只有 Manifest 和 META-INF/xposed，依赖 :hook + :ui，产出 APK。
include(":core")
include(":hook")
include(":ui")
include(":app")
// 识图采集：引擎、编译桩、注入来源应用的独立小 APK
include(":capture-engine")
include(":capture-stubs")
include(":capture")
