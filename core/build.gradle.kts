plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// 契约层：前后端共享，必须能脱离 Android 跑单元测试。
// 所以这里**不允许**出现 android.* / androidx.* / Compose 的任何类型。
dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    // ExpressBadgeTest 核对浏览器预览里嵌的几何规格：只改预览时也要重跑
    inputs.file(rootProject.file("docs/artwork/express-badges/preview.html"))
    useJUnitPlatform()
}
