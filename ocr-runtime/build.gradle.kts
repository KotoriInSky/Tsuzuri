plugins {
    alias(libs.plugins.android.application)
}

// 端侧多语种 OCR 独立推理组件 APK（PP-OCRv6 + ONNX Runtime）。
//
// 为什么拆成独立 APK（而不是直接打进 :app 主模块）：
//   - libonnxruntime.so 单个架构就占 ~30MB，不启用多语种 OCR 的用户不应承担主模块体积膨胀；
//   - Android 10+ W^X / SELinux 禁止 targetSdk>=29 的 App 直接 dlopen filesDir 下的 .so，
//     拆成独立可选组件 APK 后，原生库落在系统 /data/app/.../lib/arm64/，100% 合法可加载；
//   - 主模块仅保留轻量 IPC 协议与 UI，未来切换 QNN / MNN / 新一代推理引擎也无需改动主包。
val ocrVersionName = libs.versions.tsuzuri.get()
val ocrVersionParts = ocrVersionName.split(".")
val ocrVersionCode = ocrVersionParts[0].toInt() * 10000 +
    ocrVersionParts[1].toInt() * 100 +
    ocrVersionParts[2].toInt()

android {
    namespace = "io.github.kotoriinsky.tsuzuri.ocr"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kotoriinsky.tsuzuri.ocr"
        minSdk = 36
        targetSdk = 36
        versionCode = ocrVersionCode
        versionName = ocrVersionName
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    packaging {
        jniLibs {
            // 独立组件不是 LSPosed native hook，启用压缩可把 ~31MB 的 libonnxruntime.so 压到 ~14MB，方便在线下载
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.onnxruntime.android)
}
