plugins {
    alias(libs.plugins.android.library)
}

// 编译期签名桩：只给 :capture 编译用（compileOnly），**不会进任何 APK**。
// 运行时这些类由 miui-framework（启动类路径）提供。只写签名，不含任何厂商实现。
android {
    namespace = "io.github.kotoriinsky.tsuzuri.capture.stubs"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
