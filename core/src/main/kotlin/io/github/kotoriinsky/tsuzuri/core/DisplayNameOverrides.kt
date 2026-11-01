package io.github.kotoriinsky.tsuzuri.core

/**
 * 单应用**显示名覆盖**（本批 3️⃣ 的前半）：`<包名>␁<显示名>`。
 *
 * ## 存储：`StringSet`，一条覆盖一个元素
 *
 * 与扇形那边的单项图标覆盖（`ui/state/IconOverridesStore.kt` 与
 * `hook/.../smallwindow/IconOverrides.kt` 的 `<targetKey>␁kind␁value`）**同一套约定**：
 * 第一个字段永远是目标键。两个特性的配置因此在诊断页里形状一致 ——
 * 排障时不用换一套脑子。
 *
 * 用 `StringSet` 而不是「一条一行的单个字符串」：这份数据**没有顺序**
 * （谁先覆盖谁后覆盖不影响任何渲染）。[PrefSpec.AppList] 的 KDoc 早就把这条写死了 ——
 * 顺序有意义的清单（扇形项、收藏项）走 `OrderedList`，两者不要混。
 *
 * 键后缀 `name_overrides`、分隔符 `\u0001`。**分隔符取控制字符**：包名与显示名里都
 * 不可能出现它，而取 `:` 或 `|` 之类可打印字符迟早会撞上（用户可以把显示名改成任何东西，
 * 包括 `a|b`）—— 那种碰撞表现为「名字被截成两半」，且只在特定名字上复现。
 *
 * ## 三条边界（都有测试钉住）
 *
 *  1. **空白名 = 没覆盖**。清空输入框必须走「删掉这一条」，而不是存一个空名字 ——
 *     后者在面板上是一个**没有名字的格子**（图标还在、底下空白）。
 *  2. **坏条目跳一条，不丢一份**。一条手改配置留下的脏数据不该让另外 20 条覆盖失效。
 *  3. **没覆盖 = 原样返回原名**，不返回 null 让调用方自己兜：漏兜的表现就是
 *     「面板上少一个名字」，而那看起来像另一个 bug。
 */
object DisplayNameOverrides {

    /**
     * 字段分隔符。**只有这一处定义**，读写两侧都必须用它。
     *
     * 与图标覆盖的 `FIELD` 取同一个字符是刻意的：两份配置在诊断页里是同一个形状。
     */
    const val FIELD = '\u0001'

    /**
     * 解析。坏条目**跳一条**（见类注释第 2 条）。
     *
     * 同一个包出现多条时结果取**字典序最小**的那条的规则不在这里 ——
     * 集合语义本身不该有重复；真出现了就按 `Set` 的迭代顺序取最后一条，
     * 而这里不承诺哪一条（测试只断言「收敛成一条」）。
     */
    fun decode(raw: Iterable<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in raw) {
            val i = s.indexOf(FIELD)
            if (i <= 0) continue
            val key = s.substring(0, i).trim()
            val value = s.substring(i + 1).trim()
            // 空白名 = 没覆盖（第 1 条边界）。跳过而不是存一个空串
            if (key.isEmpty() || value.isEmpty()) continue
            out[key] = value
        }
        return out
    }

    /**
     * 编码。会做两件清洗，都是为了让「存进去的能被原样读出来」：
     * 丢掉空名字（同第 1 条边界）、键与值一律 trim。
     */
    fun encode(overrides: Map<String, String>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((k, v) in overrides) {
            val key = k.trim()
            val name = v.trim()
            if (key.isEmpty() || name.isEmpty()) continue
            out.add(key + FIELD + name)
        }
        return out
    }

    /**
     * 这个应用在面板上显示什么名字。
     *
     * [original] 是 `PackageManager.loadLabel` 出来的名字，也是所有失败路径的出口。
     */
    fun label(overrides: Map<String, String>, packageName: String, original: String): String =
        overrides[packageName] ?: original
}
