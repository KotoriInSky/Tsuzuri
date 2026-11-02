plugins {
    alias(libs.plugins.android.application)
    // 注意：AGP 9.0 起内置 Kotlin 支持，**不能再加** org.jetbrains.kotlin.android 插件，
    // 否则报 "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0"
}

// ---------------------------------------------------------------------------
// 版本号：单一来源
// 值写在 gradle/libs.versions.toml 的 [versions].tsuzuri。
// :ui 的 BuildConfig.MODULE_VERSION 读的是同一个值，所以「关于」页显示的版本
// 与 APK 里的 versionName 不可能漂移 —— 改版本号只改 TOML 那一行。
// ---------------------------------------------------------------------------
val fpVersionName = libs.versions.tsuzuri.get()

// versionCode 由版本号**推出来**，不再另写一份：规则 major*10000 + minor*100 + patch
// （1.2.3 → 10203，0.1.0 → 100）。推论：minor / patch 只允许 0..99，
// major 允许 0..2147（Android versionCode 上限 2100000000）。
// 这条规则保证「版本号变大 ⇒ versionCode 必然变大」，覆盖安装不会被系统拒。
// 写法必须是三段 major.minor.patch —— 少一段直接在这里报错，不留给运行时猜。
val fpVersionParts = fpVersionName.split(".")
require(fpVersionParts.size == 3 && fpVersionParts.all { it.toIntOrNull() != null }) {
    "版本号必须写成 major.minor.patch（当前 libs.versions.toml 里是 \"$fpVersionName\"）"
}
val fpVersionCode = fpVersionParts[0].toInt() * 10000 +
    fpVersionParts[1].toInt() * 100 +
    fpVersionParts[2].toInt()

// ---------------------------------------------------------------------------
// 发布签名
//
// 读取优先级：Gradle 属性（gradle.properties 或命令行 -P）→ 环境变量。
//   fp.keystore.path      / FP_KEYSTORE_PATH
//   fp.keystore.alias     / FP_KEYSTORE_ALIAS
//   fp.keystore.password  / FP_KEYSTORE_PASSWORD
//
// 三样齐全**且** keystore 文件真的存在 → 用它签 release。
// 否则 → **回落到 debug 签名 + 打印 warning**，绝不因为「没配签名材料」而让构建失败：
// 本模块个人自用，「能装上」优先于「签名正规」，但没有配的人必须知道自己拿到的
// 是 debug 签名的包（不能对外分发，也不能覆盖安装别人用正规签名打的包）。
//
// 仓库里不放任何 keystore 或密码；*.jks / *.keystore / *.p12 已被 .gitignore 排除。
// 建议把三个属性写进用户级 ~/.gradle/gradle.properties，而不是本仓库的 gradle.properties。
// ---------------------------------------------------------------------------
fun fpSecret(propName: String, envName: String): String? =
    (findProperty(propName) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }

val fpKeystorePath = fpSecret("fp.keystore.path", "FP_KEYSTORE_PATH")
val fpKeystoreAlias = fpSecret("fp.keystore.alias", "FP_KEYSTORE_ALIAS")
val fpKeystorePassword = fpSecret("fp.keystore.password", "FP_KEYSTORE_PASSWORD")
val fpKeystoreFile = fpKeystorePath?.let { file(it) }

val fpMissingSigningProps = listOfNotNull(
    "fp.keystore.path".takeIf { fpKeystorePath == null },
    "fp.keystore.alias".takeIf { fpKeystoreAlias == null },
    "fp.keystore.password".takeIf { fpKeystorePassword == null },
    "fp.keystore.path 指向的文件不存在（$fpKeystorePath）".takeIf {
        fpKeystorePath != null && fpKeystoreFile?.exists() != true
    },
)
val fpHasReleaseSigning = fpMissingSigningProps.isEmpty()

android {
    namespace = "io.github.kotoriinsky.tsuzuri"
    compileSdk = 37
    // 版本写死，避免换机器 / 换环境时静默换 NDK —— 这个 .so 是要进桌面进程的，
    // 优化等级和 ABI 都必须可复现。
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.kotoriinsky.tsuzuri"
        minSdk = 36
        targetSdk = 36
        versionCode = fpVersionCode
        versionName = fpVersionName

        // native 入口库（LSPosed 的 META-INF/xposed/native_init.list 机制）要用 NDK 编。
        // 只出 arm64-v8a：目标机是 arm64，多出一份 ABI 只会让 APK 变大。
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // 纯 C、不用 STL，所以不链 libc++_shared —— 少一个会被 hook 进程引入的运行时。
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    signingConfigs {
        // 只有材料齐了才建这个 config —— 建了却不填内容会让 AGP 在打包时报错。
        if (fpHasReleaseSigning) {
            create("release") {
                storeFile = requireNotNull(fpKeystoreFile) { "fp.keystore.path 未配置或指向的文件不存在" }
                storePassword = fpKeystorePassword
                keyAlias = fpKeystoreAlias
                keyPassword = fpKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            // 开混淆是硬性要求，不是优化：模块 dex 会被 mmap 进 system_server，
            // 不开时 32MB，开了之后只剩个零头（见 app/proguard-rules.pro 顶部说明）。
            isMinifyEnabled = true
            // 资源不裁：META-INF/xposed/module.prop 是框架读的，
            // 资源收缩器看不见它的引用者，有被误删的风险，收益又很小。
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (fpHasReleaseSigning) {
                logger.lifecycle(
                    "release 签名：使用 fp.keystore.path 指定的 keystore（alias=$fpKeystoreAlias）",
                )
                signingConfigs.getByName("release")
            } else {
                // 用 warning 而不是 error 是有意的：缺签名材料不该拦住本地构建。
                logger.warn(
                    "warning: release 未配置发布签名，回落到 debug 签名" +
                        "（缺：${fpMissingSigningProps.joinToString("、")}）。" +
                        "此产物不可对外分发。配置方式见 CONTRIBUTING.md「发布一个版本」。",
                )
                signingConfigs.getByName("debug")
            }
        }
        // 测性能用：和 release 同样混淆、不可调试，但用 debug 签名，能直接覆盖装在 debug 包上。
        // debug 包可调试，ART 会关掉不少优化，在它上面测出的掉帧不代表用户手里的版本。
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // java_init.list 里写的是入口类的全限定名，混淆会改类名，
    // 必须让 AGP 把资源文件里的字符串一起改写，否则框架加载不到入口。
    androidResources {
        // .so 不压缩：框架要能直接 mmap 出来 dlopen（native_init.list 那条路）。
        noCompress += "so"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // :app 是纯打包壳：自己不写代码，只把后端和前端装进同一个 APK。
    implementation(project(":hook"))
    implementation(project(":ui"))
}
