plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.kotoriinsky.tsuzuri.hook"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
        // 独立测试 APK 仅在自有进程运行合成素材，无须安装或启动产品 UI。
        testInstrumentationRunner = "io.github.kotoriinsky.tsuzuri.hook.portalprobe.PortalCanvasInstrumentation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":capture-engine"))

    // Xposed API 永远 compileOnly：LSPosed 会检测并拒绝加载把 API 打进 APK 的模块
    compileOnly(libs.libxposed.api)

    // ⚠️ 这里**永远不要**加 project(":ui")。
    // :hook 的代码会被注入 system_server / SystemUI，一旦引用 Compose，
    // 这些类就可能被真加载进系统进程。要靠 Gradle 边界挡住，不靠自觉。
}
