package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 包名 → 应用名（显示名）。**只用于显示，存储里永远还是包名。**
 *
 * ## 为什么不能直接用 [loadAppCatalog]
 *
 * [AppCatalog] 扫的是 `ACTION_MAIN` + `CATEGORY_LAUNCHER` —— 也就是「有启动入口的应用」。
 * 而**图标包正好是一个没有启动入口的应用**（它只声明 `org.adw.launcher.THEMES` 之类的私有
 * action，桌面上看不到图标）。所以图标包的包名在 [AppCatalog] 里**根本查不到**，
 * 拿它当名字解析源的话，图标包名单会永远显示包名 —— 正是用户截图里看到的那一幕。
 *
 * 这里走的是另一条路：`PackageManager.getApplicationInfo(pkg).loadLabel(pm)`，
 * **只要这个包对我们可见**（可见性由 `AndroidManifest` 的 `<queries>` 决定，
 * 图标包那几条 action 已经声明了）就能拿到名字，不需要它有启动入口。
 *
 * ## 为什么要缓存
 *
 * `loadLabel` 每个包都要跨进程读一次 `resources.arsc`，几十个包连续做会让列表首屏可见地卡一下。
 * 缓存是无上限的 [HashMap]：键是设备上的包名，量级是几十到几百，不值得为它设淘汰策略。
 *
 * ## 取不到时返回什么
 *
 * **返回包名本身**，不返回空串、也不返回「未知」。理由：包名是用户排障时唯一能对上号的东西
 * （他截图问「这个是哪个」时，包名至少能搜），而空串会让那一行看起来像渲染坏了。
 */
class AppNames(private val context: Context) {

    private val cache = HashMap<String, String>()

    /** 取这个包的显示名。取不到（未安装 / 不可见 / PM 异常）时返回 [pkg] 本身。 */
    suspend fun label(pkg: String): String {
        cache[pkg]?.let { return it }
        val name = withContext(Dispatchers.IO) { resolveLabel(pkg) }
        cache[pkg] = name
        return name
    }

    /**
     * 一次解析一批（列表页用）。
     *
     * 顺序与入参一致，且**不重复解析**同一个包 —— 图标包候选里同一个包只出现一次是常态，
     * 但调用方传进来的可能是「当前选中的 + 列表里的全部」，那时会有重叠。
     */
    suspend fun labels(pkgs: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>(pkgs.size * 2)
        for (p in pkgs) out[p] = label(p)
        return out
    }

    /**
     * 与 [label] 同一个结果，但多带一个「这个名字是不是真的解析出来了」的信息。
     *
     * 需要它的理由：显示层要区分两种行 —— **还没解析完**（跨进程读 `loadLabel` 是一个
     * 真实存在的异步窗口）和**解析完了但取不到**（未安装 / 不可见，此时 `label == pkg`）。
     * 只拿到一个字符串是分不开这两者的，而它们在界面上应该有完全不同的表现。
     */
    suspend fun resolve(pkg: String): ResolvedName = ResolvedName(pkg, label(pkg))

    private fun resolveLabel(pkg: String): String = try {
        val pm = context.packageManager ?: return pkg
        @Suppress("DEPRECATION")
        val info = pm.getApplicationInfo(pkg, 0)
        // 读不到名字时 `loadLabel` 会给空串（不是 null），所以判空的是**结果**而不是调用
        info.loadLabel(pm).toString().takeIf { it.isNotBlank() } ?: pkg
    } catch (_: Throwable) {
        pkg
    }
}

/**
 * 「解析出来的名字」与「原始包名」的关系。
 *
 * 显示层要同时处理两种行：**还没解析完**（只有包名，名字还没读回来 —— 读名字要跨进程，
 * 是一个真实的异步窗口）和**解析完了**（名字 == 包名说明真的取不到）。
 * 把这两种画成同一种样子是错的：前者应该在下一帧就变，后者永远不会变，
 * 而用户看到的都是「这里怎么是个包名」。
 */
data class ResolvedName(val packageName: String, val label: String) {
    /** 名字和包名一样 = 我们没解析出来（未安装 / 不可见），此时显示包名是对的。 */
    val unresolved: Boolean get() = label == packageName
}
