plugins {
    alias(libs.plugins.android.application)
}

// 注入来源应用的抓取 APK。
//
// system_server 在应用查询 com.miui.contentcatcher 时，把本 APK 追加到
// ApplicationInfo.sharedLibraryFiles；LoadedApk 会把它排在原厂 APK 前面，
// 于是应用加载到的 com.miui.catcherpatch.ViewContentFetcher 是本 APK 里的包装类。
//
// ⚠️ 本 APK 会和原厂 ContentCatcher 共用同一个 ClassLoader 路径（排在前面）：
//   - **禁止混淆 / 重打包**：R8 会生成单字母类名，正好撞上原厂的 a…z 顶层包，遮住原厂类。
//   - **禁止加依赖**：除 :capture-engine 与 Kotlin 标准库外什么都不带。
android {
    namespace = "io.github.kotoriinsky.tsuzuri.capture"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kotoriinsky.tsuzuri.capture"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":capture-engine"))
    compileOnly(project(":capture-stubs"))
}
