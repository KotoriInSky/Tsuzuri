// 插件在根项目统一声明、子项目只 apply —— 否则 Gradle 会报
// "The Kotlin Gradle plugin was loaded multiple times in different subprojects"。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
