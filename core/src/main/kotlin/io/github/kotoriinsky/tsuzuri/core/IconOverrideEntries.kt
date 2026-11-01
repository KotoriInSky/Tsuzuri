package io.github.kotoriinsky.tsuzuri.core

/**
 * 单应用**图标覆盖**的键格式（本批 3️⃣ 的后半）—— 唯一的**可测**实现。
 *
 * ## 格式（与扇形那两份逐字一致，别改）
 *
 * ```
 * <targetKey> ␁ pack ␁ <图标包包名>/<drawable 名>
 * <targetKey> ␁ file ␁ <文件名> ␂ <内容指纹>
 * ```
 *
 * [FIELD] = `\u0001`（字段分隔）、[STAMP] = `\u0002`（值里的指纹分隔）。
 *
 * ## 为什么这一段在 `core`，而不是再抄一份进侧滑索引的 hook 包
 *
 * 同一套格式已经有两份实现：设置界面侧 `ui/state/IconOverridesStore.kt` 与
 * hook 侧 `hook/.../smallwindow/IconOverrides.kt`（后者的 KDoc 写着「必须与 ui 侧一致」，
 * 两个文件都在用注释维护这条契约）。两份都**没有测试**（`:hook` 没有测试源集）。
 *
 * 本批给侧滑索引加同一个能力时，照抄第三份就等于把「不可测的格式解析」再复制一次，
 * 而且那三份迟早会漂移 —— 漂移的表现是「图标没变」这类只能靠猜的问题。
 * 所以这里把它提成一份可测的实现，**侧滑索引的读写两侧都用它**；
 * 扇形那两份不动（不在本批范围内，见报告里的遗留项）。
 *
 * ## 键的粒度：**包名**（不是 `包名/activity`）
 *
 * 侧滑索引只能启动应用（它拿的是 `MAIN/LAUNCHER` 入口），而 `AppIndex.Index.byPackage`
 * 一个包只留一个入口 —— 所以面板里一个包只会出现一次，用 `包名/id` 反而会造出
 * 「同一个应用有两套覆盖、不知道哪套生效」的问题。扇形那边按 `包名/id` 是因为它要
 * 区分「微信」和「微信·扫一扫」。**格式本身对两种键都成立**（第一个字段就是目标键），
 * 所以读的时候不做任何形状假设。
 *
 * ## 三条被测试钉住的边界
 *
 *  1. **指纹只影响缓存判定**：解析不出指纹的老配置照常按「截到 [STAMP] 之前」当文件名，
 *     最坏情况是那一条显示成默认图标，而不是整个面板画不出来。
 *  2. **坏条目跳一条**，不丢一份。
 *  3. **签名与集合迭代顺序无关**，但内容一变（换了一张图 = 指纹变了）就变 ——
 *     这正是指纹那一段存在的全部理由。
 */
object IconOverrideEntries {

    /** 字段分隔符。**必须与 `IconOverridesStore.FIELD` / `IconOverrides.FIELD` 一致**。 */
    const val FIELD = '\u0001'

    /**
     * 值里的指纹分隔符。**必须与 `IconOverridesStore.STAMP` / `IconOverrides.STAMP` 一致**。
     *
     * 用另一个控制字符而不是复用 [FIELD]：值里出现第二个 [FIELD] 会让「三个字段」
     * 这个约定变得有歧义，读这段代码的人一定会数错。
     */
    const val STAMP = '\u0002'

    /** 图标来自图标包里的某一张 drawable。 */
    const val KIND_PACK = "pack"

    /** 图标来自用户从相册选的图片（已被设置界面处理成 192×192 PNG 落在模块 App 的目录里）。 */
    const val KIND_FILE = "file"

    /**
     * 一条覆盖。[value] 的含义由 [kind] 决定（见类注释）。
     *
     * 与 `IconOverridesStore.Entry` / `IconOverrides.Entry` 同构（都是「kind + value」
     * 两个字段），但**故意不共用类型**：`:hook` 不能依赖 `:ui`，而 `core` 也不该被
     * 塞进那两个包里的实现细节。真正需要共享的只有**格式**，格式在这里。
     */
    class Entry(val kind: String, val value: String) {
        override fun equals(other: Any?): Boolean =
            other is Entry && other.kind == kind && other.value == value

        override fun hashCode(): Int = kind.hashCode() * 31 + value.hashCode()

        override fun toString(): String = "$kind:$value"
    }

    /**
     * 解析。坏条目**跳一条**（第 2 条边界）。
     *
     * 认不出的 kind 照常解析（第 1 条边界的邻居）：丢掉它的表现是
     * 「用户改了图标、签名却没变、解码缓存永远不失效」—— 见 [signature]。
     */
    fun decode(raw: Iterable<String>): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (s in raw) {
            val a = s.indexOf(FIELD)
            if (a <= 0) continue
            val b = s.indexOf(FIELD, a + 1)
            if (b <= a) continue
            val key = s.substring(0, a).trim()
            val kind = s.substring(a + 1, b).trim()
            val value = s.substring(b + 1).trim()
            if (key.isEmpty() || kind.isEmpty() || value.isEmpty()) continue
            out[key] = Entry(kind, value)
        }
        return out
    }

    /** 编码。键与字段一律 trim，空字段的条目丢掉。 */
    fun encode(overrides: Map<String, Entry>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((k, e) in overrides) {
            val key = k.trim()
            val kind = e.kind.trim()
            val value = e.value.trim()
            if (key.isEmpty() || kind.isEmpty() || value.isEmpty()) continue
            out.add(key + FIELD + kind + FIELD + value)
        }
        return out
    }

    /**
     * 解码缓存的签名。**与集合的迭代顺序无关**（排序后再拼），
     * 但内容一变（换图 = 指纹变）就变。
     *
     * 与 `IconOverrides.Set.signature` 用的是同一条算法（排序后 `|` 连接）——
     * 两者各算各的，但判定语义一致：**变了就丢缓存**。
     */
    fun signature(raw: Iterable<String>): String = raw.sorted().joinToString("|")

    /**
     * `file` 值里的**文件名**（截掉指纹那一段）。
     *
     * 老配置没有指纹，[String.substringBefore] 原样返回；`""` 也原样返回 ——
     * 调用方按「这个文件名打不开」处理，退回自带图标。
     */
    fun fileName(value: String): String = value.substringBefore(STAMP)

    /**
     * `pack` 值里的 `包名` → `drawable 名`。
     *
     * 按**第一个** `/` 切（drawable 名里不可能有 `/`）。
     * 不成对（没有 `/`、`/` 在首尾）时返回 null —— 调用方退回自带图标，
     * 而不是拿一个空名字去查图标包。
     */
    fun packRef(value: String): Pair<String, String>? {
        val i = value.indexOf('/')
        if (i <= 0 || i == value.length - 1) return null
        return value.substring(0, i) to value.substring(i + 1)
    }
}
