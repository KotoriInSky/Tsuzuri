package io.github.kotoriinsky.tsuzuri.core

/**
 * 「最近使用」列表的**纯逻辑**：新项置顶、去重、按上限截断。
 *
 * ## 为什么单独抽出来（而不是留在 `SideIndex.kt` 的采样线程里）
 *
 * 顺序与截断就是这份数据的全部语义，而 hook 侧**没有测试源集**（本项目只有
 * `core/src/test`）。留在那边就只能靠真机肉眼验证「列表顺序对不对」——
 * 那是最容易看走眼的一类现象（可测逻辑一律放 `core/`）。
 *
 * ## 保留长度 ≠ 显示数量
 *
 * 这是本类唯一需要解释的设计：
 *
 *  - **保留**：进程里和盘上最多留「数量滑杆的**最大值**」那么多条。所以用户在设置里
 *    把数量从 4 调到 20，历史里那些应用**还在**，能立刻看到；反过来调小只是少显示几格，
 *    不丢数据。
 *  - **显示**：`★` 槽里放几条由 [visible] 决定。
 *
 * 反过来的做法（按配置数量截断再落盘）看着更省事，但它会让「调到 20」变成
 * 「只有一个：因为你以前调小过」—— 用户眼里就是「这个滑杆坏了」。
 *
 * **两个常量都不在本类**：保留上限由调用方（hook 侧的数量参数）传进 [push]，
 * 显示数量由参数面夹取后传给 [visible]。本类不许依赖 hook 侧的类型 ——
 * `core` 是前后端共享的契约层，那条边界由 `core/build.gradle.kts` 挡着。
 */
object RecentList {

    /**
     * 把一个包名推到最前面。
     *
     * 语义逐条（每条都有对应断言，见 `RecentListTest`）：
     *
     *  1. [pkg] 先 `trim`；空白一律**忽略**（前台轮询在异常路径上可能给空串）；
     *  2. 已经在最前面 → **返回同一个实例**。调用方靠引用相等判「没变」，
     *     于是列表没动的时候不写盘、不惊动主线程（采样线程每秒问一次）；
     *  3. 在别的位置 → 挪到最前，**不留重复**（重复会在面板里画两个一样的图标）；
     *  4. 结果截断到 [max] 条，丢的是**最旧**的；[max] ≤ 0 → 空列表
     *     （上限为 0 是「不留历史」，不是「不截断」）。
     *
     * 纯函数，不改动 [current]（那一份可能正被主线程读着）。
     */
    fun push(current: List<String>, pkg: String, max: Int): List<String> {
        val name = pkg.trim()
        if (name.isEmpty()) return current
        // 头部不变是**最常见**的一档（用户一直在用同一个应用时每秒都会走到这里），
        // 所以它放在最前面，且不分配任何东西。
        if (current.isNotEmpty() && current[0] == name && current.size <= max) return current

        // 去重 + 置顶。`max` 可能比 `current` 小，所以顺带收口。
        val out = ArrayList<String>(minOf(current.size + 1, max.coerceAtLeast(0)))
        if (max > 0) out.add(name)
        for (p in current) {
            if (p == name) continue
            if (out.size >= max) break
            out.add(p)
        }
        // 走到这里只可能是「内容没变但长度超了」或者「头部就是它但列表过长」，
        // 两种都该发一份新快照出去（长度也是可见状态）。
        return out
    }

    /**
     * `★` 槽里显示哪几条。**只影响显示**，不动保留的那份历史。
     *
     * [count] ≤ 0 → 空。界面上取不到这个值（滑杆有下限），但纯函数不依赖调用方的自觉。
     */
    fun visible(history: List<String>, count: Int): List<String> =
        if (count <= 0) emptyList() else history.take(count)
}
