package io.github.kotoriinsky.tsuzuri.ui.state

import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ScopeTarget

/**
 * 模块**声明**的作用域：APK 里 `META-INF/xposed/scope.list` 的每一行（LSPosed 列出、让用户勾的就是这些）。
 *
 * 首页「作用域 n/N」与「未勾选」都按它算（用户 2026-10-25：显示 6/6，但 LSPosed 里要勾的不止 6 个）。
 * 原来按「界面可见特性用到的作用域」算，比 LSPosed 里那份清单少，两边对不上。
 * 读不到清单（不该发生）就退回原来的算法。
 */
object ModuleScopes {
    val declared: List<ScopeTarget> by lazy {
        val packages = runCatching {
            ModuleScopes::class.java.classLoader!!.getResourceAsStream("META-INF/xposed/scope.list")!!
                .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }.getOrDefault(emptyList())
        val byPackage = ScopeTarget.entries.associateBy { it.packageName }
        packages.mapNotNull { byPackage[it] }.ifEmpty { Catalog.settingsScopes.toList() }
    }
}
