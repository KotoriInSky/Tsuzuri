plugins {
    alias(libs.plugins.android.library)
}

// 源 Bitmap 采集引擎：记录 View 绘制时的 drawBitmap 输入。
// 同时被 :hook（实验与 instrumentation 测试）和 :capture（注入来源应用的抓取 APK）使用。
// 只依赖 Android 框架与 Kotlin 标准库 —— 它会被加载进第三方应用，不能带别的依赖。
android {
    namespace = "io.github.kotoriinsky.tsuzuri.capture.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
