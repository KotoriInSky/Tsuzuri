import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.kotoriinsky.tsuzuri.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
        // Kit 契约测试要在设备/模拟器上跑（两套风格双跑），所以必须有 runner。
        // 2026-10-17 补上：此前这里既没有 runner 也没有 androidTest 依赖，
        // 导致 FpKitContractTest 从未编译过。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 关于页要显示构建时间：出问题时用户截图里的这一行能立刻定位到是哪个包。
        // 注意：Kotlin DSL 里 `java.*` 会被解析成 java 扩展，必须用顶部的 import。
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$stamp\"")
        // 版本号来自 gradle/libs.versions.toml 的 [versions].tsuzuri —— 与 :app 的
        // versionName 是同一个值，这里**不再写第二份字面量**，否则「关于」页显示的版本
        // 会和 APK 里的漂移。
        buildConfigField("String", "MODULE_VERSION", "\"${libs.versions.tsuzuri.get()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core"))

    // 图标解析链（图标包解析 / 四级回退 / 形状裁切）住在 :hook 里，而它是**真机唯一生效的那一份**。
    // 预览要显示「真的会出现的那张图」，就必须走同一条路径 —— 所以这里直接依赖 :hook，
    // 而不是在 :ui 里再写一份（`ui/state/IconPackCatalog.kt` 原来就重写了一遍 action 清单与
    // appfilter 解析，两份清单逐字节相同却必须手动保持同步，这正是要消掉的漂移源）。
    //
    // 为什么这条边是安全的：
    //   1) 方向没被违反 —— 规矩只禁止 `:hook → :ui`（那会把 Compose 带进 system_server）；
    //   2) 零 dex 增量 —— `:app` 本来就 `implementation(project(":hook"))`，
    //      :hook 的类早已在 APK 里，这里只是多一条编译期引用；
    //   3) 只有被引用到的类才会加载，设置进程不会因为这条边而加载 HookRegistry 之类的入口。
    implementation(project(":hook"))

    // 读框架状态 + 写远程配置。只在设置界面进程里用，不进 hook。
    implementation(libs.libxposed.service)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // 液态玻璃（实验）：底栏按真实背景做折射/模糊。只有「浮层」用，页面内容仍是澎湃风格。
    implementation(libs.kyant.backdrop)

    // MiuiX：Material 3 之外的第二种风格。
    // 刻意**不引** material-icons-extended 和 miuix-icons：
    //   - extended 图标库会给 APK 加几 MB dex，而这个 APK 的 dex 是要被
    //     mmap 进 system_server 和 SystemUI 的，能小就小。
    //   - 图标统一走 material-icons-core，两套风格共用一套 ImageVector，
    //     Kit 只负责摆位置和上色。
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    // 导航：两套风格共用（转场与风格无关）
    implementation(libs.miuix.nav)

    // M3 自定义取色的完整色调盘。只引纯算法库（无 UI 依赖），见 libs.versions.toml 的注释。
    implementation(libs.material.color.utilities)

    debugImplementation(libs.compose.ui.tooling)

    // ── instrumentation 测试（两套 Kit 的契约测试）──
    // 这两条边只在测试构建里存在，不进 APK 的 dex。
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // createComposeRule 需要它提供测试 Activity 的 manifest
    debugImplementation(libs.compose.ui.test.manifest)
}

// ─────────────────────────────────────────────────────────────────────────────
// i18n 键校验：源码里引用到的键，必须在 strings.xml 里有对应资源。
//
// 为什么需要这道闸：`fpString` 查不到资源时**不崩、也不报错**，它把键名本身画到界面上。
// 于是「参数在 Catalog 里声明了、文案忘了写」这种漏项会一路过编译、过装机、
// 过冒烟，表现得只是「有个地方文字怪怪的」—— 直到有人在真机截图上看见
// `pref.corner.edge_margin` 才被发现（实测漏过这一次）。
//
// Strings.kt 的注释一直声称有这么一道校验，但此前**并不存在**。这就是它的实现。
// 扫描范围必须包含 `:core`（Catalog 里所有的 labelKey / hintKey 都在那儿）与
// `:hook`（诊断类文案）；资源则以 `:ui` / `:app` 的 values/*.xml 为准。
//
// 不判「资源没人用」（死键无害，而且动态拼接的族判不准），只判**反向**：
// 引用了却没有资源 —— 那才是会出现在用户眼前的故障。
val verifyStringKeys by tasks.registering {
    group = "verification"
    description = "校验源码里引用的 i18n 键都在 strings.xml 里有对应资源"

    val resDirs = listOf(
        layout.projectDirectory.dir("src/main/res/values"),
        layout.projectDirectory.dir("../app/src/main/res/values"),
    )
    val srcDirs = listOf(
        layout.projectDirectory.dir("src/main/java"),
        layout.projectDirectory.dir("../core/src/main/kotlin"),
        layout.projectDirectory.dir("../hook/src/main/java"),
    )

    inputs.files(resDirs.map { it.asFileTree.matching { include("*.xml") } })
    inputs.files(srcDirs.map { it.asFileTree.matching { include("**/*.kt") } })
    outputs.upToDateWhen { false }

    doLast {
        val resources = mutableSetOf<String>()
        for (dir in resDirs) {
            if (!dir.asFile.exists()) continue
            for (f in dir.asFileTree.matching { include("*.xml") }.files) {
                Regex("<string\\s+name=\"([^\"]+)\"").findAll(f.readText())
                    .forEach { resources.add(it.groupValues[1]) }
            }
        }

        // 资源名里不许有点号：fpStringRes 查找前把键里的 `.` 换成 `_`，带点号的资源**永远查不到**，
        // 界面上会直接露出键名。动态拼接的键（如 `labelKey + "." + value`）上面的引用扫描看不见，
        // 只能在声明侧拦。2026-10-21 真机实见：「小窗位置」的值显示成 pref.corner.window_preset.flyme。
        val dotted = resources.filter { '.' in it }.sorted()
        if (dotted.isNotEmpty()) {
            throw GradleException(
                "字符串资源名不能含点号（fpStringRes 会把键里的 . 换成 _ 去查，带点号的资源永远查不到）：\n" +
                    dotted.joinToString("\n") { "  - $it" },
            )
        }

        // 只看 i18n 槽位：其它字符串（包名、intent action、文档路径）不是资源键
        val patterns = listOf(
            Regex("fpString\\(\\s*\"([^\"]+)\""),
            Regex("\\b(?:labelKey|hintKey|titleKey)\\s*=\\s*\"([^\"]+)\""),
            Regex("Option\\(\\s*\"[^\"]*\"\\s*,\\s*\"([^\"]+)\"\\s*\\)"),
        )

        val missing = sortedMapOf<String, String>()
        for (dir in srcDirs) {
            if (!dir.asFile.exists()) continue
            for (f in dir.asFileTree.matching { include("**/*.kt") }.files) {
                val text = f.readText()
                for (p in patterns) {
                    for (m in p.findAll(text)) {
                        val key = m.groupValues[1]
                        // 动态拼接（`$`）与拼接前缀（以 `.` 结尾）不可能有静态资源
                        if (key.contains('$') || key.endsWith(".") || !key.contains('.')) continue
                        if (key.replace('.', '_') !in resources) missing.putIfAbsent(key, f.name)
                    }
                }
            }
        }

        if (missing.isNotEmpty()) {
            val lines = missing.entries.joinToString("\n") { "  - ${it.key}   （${it.value}）" }
            throw GradleException(
                "有 ${missing.size} 个 i18n 键在 strings.xml 里没有对应资源，" +
                    "它们会以键名的形式显示给用户：\n$lines\n" +
                    "请在 ui/src/main/res/values/ 下补上（资源名 = 键名的点换成下划线）。",
            )
        }
        // ── 拼接键盲区──
        // `pref.group.<id>` / `pref.category.<id>` 是运行时拼出来的，上面的正则按设计跳过
        // 含 `$` 的键，所以这类键漏写文案时静态上查不到。这里反过来做：
        // 从 Catalog / PrefCategory 里抽出**字面量 id**，逐个断言资源存在。
        val catalogFile = layout.projectDirectory.file("../core/src/main/kotlin/io/github/kotoriinsky/tsuzuri/core/Catalog.kt").asFile
        val catalogText = catalogFile.readText()
        val groupIds = Regex("group\\s*=\\s*\"([a-z0-9_]+)\"").findAll(catalogText)
            .map { it.groupValues[1] }.toSet() +
            // 常量形式的分组（`group = PREF_GROUP_BUTTONS`）只扫字面量会漏 —— 2026-10-17 实测漏了「按钮开关」。
            Regex("PREF_GROUP_[A-Z_]+\\s*=\\s*\"([a-z0-9_]+)\"").findAll(catalogText)
                .map { it.groupValues[1] }.toSet()
        val groupMissing = groupIds.filter { "pref_group_$it" !in resources }
        if (groupMissing.isNotEmpty()) {
            throw GradleException(
                "有分组标识没有对应文案（界面标题会显示键名）：\n" +
                    groupMissing.joinToString("\n") { "  - $it  → 需要 pref_group_$it" } +
                    "\n请在 ui/src/main/res/values/strings.xml 补齐。",
            )
        }

        val categoryFile = layout.projectDirectory.file("../core/src/main/kotlin/io/github/kotoriinsky/tsuzuri/core/PrefCategory.kt").asFile
        val enumBody = Regex("enum class PrefCategory\\s*\\{([\\s\\S]*?;)").find(categoryFile.readText())
            ?.groupValues?.get(1)
            ?: throw GradleException("verifyStringKeys: 读不到 PrefCategory 枚举体（结构变了？）")
        val categoryIds = Regex("([A-Z][A-Z_]+)\\s*[,;]").findAll(enumBody)
            .map { it.groupValues[1].lowercase() }.toSet()
        val categoryMissing = categoryIds.filter { "pref_category_$it" !in resources }
        if (categoryMissing.isNotEmpty()) {
            throw GradleException(
                "有参数类别没有对应文案：\n" +
                    categoryMissing.joinToString("\n") { "  - $it  → 需要 pref_category_$it" },
            )
        }

        logger.lifecycle(
            "verifyStringKeys: ${resources.size} 个资源，引用的键全部有对应文案 ✓ " +
                "（另含 ${groupIds.size} 个分组、${categoryIds.size} 个类别的拼接键）",
        )
    }
}

// 每次构建都过一遍：这道闸的价值就在于「漏写在编译阶段失败」，而不是等到有人看截图。
tasks.named("preBuild") { dependsOn(verifyStringKeys) }
