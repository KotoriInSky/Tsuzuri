package io.github.kotoriinsky.tsuzuri.core

/**
 * 桌面**遮挡状态**信号（来自桌面进程内的 native hook）。
 *
 * 与 [DrawerSignal]（WMS `softInputMode` 启发式）的区别：那条是**从外面猜**桌面被谁挡住，
 * 这条是桌面自己把「抽屉开着 / 文件夹开着」**报出来**（hook 到的是
 * `DesktopServiceImpl.notifyDrawerOpen/Close` 与 `notifyFolderOpen/Close` 这四个函数）。两条信号并行使用，任一为真即视为被遮挡。
 *
 * 传递方式是**一个共享文件**（桌面写、SystemUI 读）：抽象 socket 被 SELinux 拒
 * （`platform_app_36 → platform_app` 缺 connectto 规则），而 `/data/local/tmp` 下的文件
 * 两个进程都能访问（实测）。
 *
 * ⚠️ 这个文件是 **0666**（桌面进程要能写），**别的应用理论上也能写**。
 * 所以 [parse] 是**严格校验**，任何不合格式的内容一律当「未知」，而不是当「没遮挡」之外的东西 ——
 * 未知与过期都走 [blocks] 的 fail-closed 分支。
 */
object LauncherOverlaySignal {

    /** 与 `experiments/hyos-entry/fphyos.c` 里的路径必须一致。 */
    const val FILE_PATH: String = "/data/local/tmp/fp_launcher_state.txt"

    /** 文件里的魔法串：用来把「我们的文件」和任何别的残留内容区分开。 */
    const val MAGIC: String = "fpoverlay"

    /**
     * 多久没更新就算过期（毫秒，单调钟）。
     *
     * 写入方（native 侧）约 200ms 一次、并且每秒至少发一次心跳；给到 3 秒的余量，
     * 覆盖桌面重启、调度抖动这些情况。过期 = 未知 = 不抑制（宁可照弹也不误伤）。
     */
    const val MAX_AGE_MS: Long = 3000L

    /**
     * 一次快照。
     *
     * 前两个字段是 v1 就有的（抽屉 / 文件夹）；后三个是 2026-09-14 扩的，对应 Flyme 准入清单里
     * 「顶层有浮窗 → 不接管」那一类**桌面内部的模态态**：
     *
     * - [previewOpen]：小部件预览页（桌面自己的一屏，Flyme 那边算 `TYPE_ACCESSIBLE` 浮窗）
     * - [editOpen]：编辑态（长按桌面/图标后的那一屏，同上的浮窗）
     * - [stackOpen]：堆叠展开
     *
     * 三个新字段**可缺省**（缺省 = false），所以旧版写入方的文件照样能读。
     */
    data class State(
        val drawerOpen: Boolean,
        val folderOpen: Boolean,
        val seq: Long,
        /** **单调钟**毫秒（native 侧 `CLOCK_MONOTONIC`，Java 侧 `System.nanoTime()/1e6`）。 */
        val tsMs: Long,
        val previewOpen: Boolean = false,
        val editOpen: Boolean = false,
        val stackOpen: Boolean = false,
    ) {
        /** 桌面上有任何「模态态」压着 → 边缘手势该让位。 */
        val overlayOpen: Boolean
            get() = drawerOpen || folderOpen || previewOpen || editOpen || stackOpen
    }

    /**
     * 解析文件内容。**任何一处不合规就返回 `null`**（= 未知）：
     * 魔法串不对、缺字段、取值不是 0/1、版本不认识、数字字段不是数字。
     */
    fun parse(text: String): State? {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts[0] != MAGIC) return null
        val kv = HashMap<String, String>(8)
        for (p in parts.drop(1)) {
            val i = p.indexOf('=')
            if (i <= 0) return null
            kv[p.substring(0, i)] = p.substring(i + 1)
        }
        if (kv["v"] != "1") return null
        val seq = kv["seq"]?.toLongOrNull() ?: return null
        val ts = kv["ts"]?.toLongOrNull() ?: return null
        val drawer = bit(kv["drawer"]) ?: return null
        val folder = bit(kv["folder"]) ?: return null
        // 这三个可缺省（旧写入方没有）；一旦出现就必须是 0/1，坏值同样当未知
        val preview = optionalBit(kv["preview"]) ?: return null
        val edit = optionalBit(kv["edit"]) ?: return null
        val stack = optionalBit(kv["stack"]) ?: return null
        return State(
            drawerOpen = drawer,
            folderOpen = folder,
            seq = seq,
            tsMs = ts,
            previewOpen = preview,
            editOpen = edit,
            stackOpen = stack,
        )
    }

    /** 过期判定：[state] 为空、时间戳在未来、或落后超过 [MAX_AGE_MS] 都算过期。 */
    fun isStale(state: State?, nowMonoMs: Long): Boolean {
        if (state == null) return true
        val age = nowMonoMs - state.tsMs
        return age < -MAX_AGE_MS || age > MAX_AGE_MS
    }

    /**
     * 现在要不要**抑制**侧滑索引。
     *
     * fail-closed 的方向是「不抑制」：拿不到 / 过期 / 解析失败 → `false`
     * → 行为与**未安装模块**一致（抽屉/文件夹里照弹）。这条信号失效的表现是静默的，
     * 所以 [describe] 的字符串会进安装报告 —— 那是唯一看得见它的地方。
     */
    fun blocks(state: State?, nowMonoMs: Long): Boolean =
        state != null && !isStale(state, nowMonoMs) && state.overlayOpen

    /** 进安装报告用（`overlay=` 字段）。 */
    fun describe(state: State?, nowMonoMs: Long): String {
        if (state == null) return "unknown(没读到信号)"
        if (isStale(state, nowMonoMs)) {
            return "stale(落后 ${nowMonoMs - state.tsMs}ms)"
        }
        val extra = buildString {
            if (state.previewOpen) append(" preview=1")
            if (state.editOpen) append(" edit=1")
            if (state.stackOpen) append(" stack=1")
        }
        return "drawer=${state.drawerOpen} folder=${state.folderOpen}$extra seq=${state.seq}"
    }

    private fun bit(v: String?): Boolean? = when (v) {
        "0" -> false
        "1" -> true
        else -> null
    }

    /** 可缺省字段：不出现 = false；出现但不是 0/1 → null（当未知，整条快照作废）。 */
    private fun optionalBit(v: String?): Boolean? = if (v == null) false else bit(v)
}
