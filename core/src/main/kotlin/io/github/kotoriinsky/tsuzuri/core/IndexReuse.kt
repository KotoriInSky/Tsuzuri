package io.github.kotoriinsky.tsuzuri.core

/**
 * 索引重建时「哪些项可以复用上一次结果」的**纯逻辑**。
 *
 * ## 为什么需要它（这一条是被真实性能问题逼出来的）
 *
 * 我们的索引是**自己扫 PackageManager** 得来的：`queryIntentActivities` 之后，
 * 每个应用还要 `loadLabel` + `loadIcon` 各一次跨进程调用，而 `loadIcon` 还要解码位图。
 * 一百多个应用就是两百多次 IPC 加两百次解码 —— 而**每次包变化**（安装 / 卸载 / 更新，
 * 注意 `PACKAGE_REPLACED` 在每次应用更新时都会发）都会触发一次**全量重建**。
 *
 * 对照 Flyme：它的索引是**桌面数据模型的投影**（`BgDataModel.flymeDataModel.allSlideApps`），
 * 项是 `ItemInfoWithIcon`、图标早就在模型里，所以它的「全量重建」是纯内存操作
 * （`updateAllSlideAppItems()` 只是重新分组 + 排序），**不需要**增量也不花钱。
 * 我们的数据源拿不到（澎湃桌面是 Flutter + Rust、一个 dex 都没有），
 * 所以唯一的路就是**把重建变便宜**：不变的那些项，连图标一起搬过来。
 *
 * ## 判据为什么只用「包 / 组件」，不看名字
 *
 * 看名字就还得先 `loadLabel` —— 那正是要省掉的两次 IPC 之一。
 * 而「名字变了」必然意味着包被改过，那时广播会把包名放进失效集，走不到复用这一支；
 * 语言变化那种全局变化由调用方传 [Invalidation.ALL]。**所以不带名字是安全的**，
 * 而且这一条有测试钉着（[IndexReuseTest]）。
 *
 * ## 为什么放在 core
 *
 * 它是纯逻辑（不引 `android.*`），可以纯 JVM 单测 —— 而 `hook` 模块**没有单测设施**
 * （`hook/build.gradle.kts` 里没有 test 源集）。「判据写错」是这一层唯一会出错的地方，
 * 所以它必须待在能测的那一侧。
 */
object IndexReuse {

    /**
     * 复用键：**包名 + 组件名**。
     *
     * 为什么不是包名：一个包可能有多个启动入口（设置、设置快捷方式…），
     * 只按包名做键会把它们混成一个，复用时会丢入口。
     */
    fun componentKey(packageName: String, activityName: String): String = "$packageName/$activityName"

    /**
     * 失效集：这一轮重建里**哪些包的项不能复用**。
     *
     * @param all `true` = 「不知道变了什么，一律不能复用」（保守，正确性优先）。
     *   首次构建、sdcard 挂载、语言变化、拿不到包名的广播都走这一档。
     * @param packages 明确知道变了的包名。只有这些包会重新 `loadLabel` + `loadIcon`。
     */
    data class Invalidation(val all: Boolean, val packages: Set<String>) {

        /** 这个包的项能不能复用。 */
        fun canReuse(packageName: String): Boolean = !all && packageName !in packages

        /**
         * 合并一条新的失效信息。
         *
         * @param packageName 广播里带的包名；**null / 空 = 拿不到**（例如某些 ROM 不发 data），
         *   这时只能退化成 [ALL] —— 猜一个包名比多扫一遍危险得多。
         */
        fun plus(packageName: String?): Invalidation =
            if (packageName.isNullOrEmpty()) ALL else copy(packages = packages + packageName)

        companion object {
            /** 全失效：一项都不复用。 */
            val ALL = Invalidation(true, emptySet())

            /** 无失效：一切都可复用。 */
            val NONE = Invalidation(false, emptySet())
        }
    }
}
