package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.ConfigDraftSync
import io.github.kotoriinsky.tsuzuri.core.ConfigMigration
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.KeyValueStore
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.core.RestartPolicy
import io.github.kotoriinsky.tsuzuri.core.settingsFeatures
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 配置读写。前端的「后端」。
 *
 * 三条设计决定：
 *
 * 1. **配置不在 App 私有目录里，也不在 `/data/adb` 里。**
 *    走框架的 RemotePreferences —— 这是唯一能让 SystemUI / 负一屏
 *    （不同 uid、不同 SELinux 域）读到同一份配置的通道，而且是官方支持的。
 *
 * 2. **框架没连上时写到本地草稿区，不报错、也不假装成功。**
 *    模块没启用时用户照样能进去翻开关，改动先存本地；等框架连上（`onFrameworkBound`）
 *    再同步过去，同步规则见 [ConfigDraftSync]。
 *
 *    ⚠️ 「不假装成功」这件事必须有界面兜着：写进草稿之后**界面照样显示新值**，
 *    而真机读的是远端 —— 不说出来的话，用户看到的就是「我明明打开了，真机没反应」。
 *    所以降级状态由 [degraded] 暴露出来，`ConfigChannelBanner` 负责显示。
 *
 * 3. **改动只写不读回缓存。** 每次读都直接问 SharedPreferences，
 *    因为后端可能同时也在改（例如热重载后写回状态）。缓存会造成两边不一致。
 */
object ConfigStore {

    private const val DRAFT = "draft_config"

    private lateinit var appContext: Context
    private var remote: SharedPreferences? = null

    /** 每写一次 +1。Compose 读它来订阅变化 —— SharedPreferences 本身不是可观察的。 */
    private var revision by mutableIntStateOf(0)

    /**
     * 通道是否降级（写的东西到不了真机）。Compose 状态：界面上的提示条要跟着变。
     * 初始为 `false`：`init()` 时还没绑定框架，此时报「降级」会把用户吓一跳 ——
     * 真相通常只是「还没绑上」，而 [degraded] 在真正写入失败或绑定时没有远端时才会置位。
     */
    private var channelDegraded by mutableStateOf(false)

    fun init(context: Context) {
        appContext = context.applicationContext
        // 框架还没连上时也要迁移：用户可能是「模块没启用时进来调过参数」的那一批，
        // 那份配置存在本地草稿里，同样带着老语义。
        migrate()
    }

    private val draft: SharedPreferences
        get() = appContext.getSharedPreferences(DRAFT, Context.MODE_PRIVATE)

    /** 框架服务绑定成功后调。 */
    fun onFrameworkBound(service: XposedService) {
        remote = try {
            service.getRemotePreferences(Keys.GROUP)
        } catch (t: Throwable) {
            android.util.Log.w("FP-Config", "getRemotePreferences failed", t)
            UiActionLog.log("框架连接但取存储失败：" + t.javaClass.simpleName)
            null
        }
        // 框架连上 = 通道恢复。还有没有待同步的项由 [syncDraft] 判定（它成功时会清掉这个标记）。
        channelDegraded = remote == null
        revision++
        syncDraft()
        // **顺序不能反**：先同步草稿、再迁移。反过来会先给远端盖上版本号，
        // 之后迁移看到「已是最新」就直接跳过（远端那些老值原地不动地错下去）。
        migrate()
    }

    /**
     * 跑一次配置结构迁移（[ConfigMigration]）。幂等，[init] 与「框架连上」两处都调
     * —— 本地草稿与远端各是一份配置，各要迁一次，而版本号存在各自那份里。
     *
     * ## 为什么不走 [edit]（即不触发自动重载）
     *
     * 迁移发生在**启动时**，而自动重载会去重启 SystemUI / 负一屏。为了几个参数值
     * 在开屏那一刻重启系统界面，代价完全不成比例 —— 而且**不需要**：这几个键是
     * 「摆放」类参数，`SystemUiEnv.WindowPlacement.resolve` 每次划手势都重读，
     * 从来没有缓存（规范 §9.1 对手感类参数的要求）。
     *
     * 反过来说，如果哪天有人给这几个键加了缓存，这里就得补一次重载 ——
     * 所以这句话写在这儿，别删。
     */
    private fun migrate() {
        val capabilityWrites = io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.migration(prefs())
        if (capabilityWrites.isNotEmpty()) {
            try {
                val editor = (remote ?: draft).edit()
                capabilityWrites.forEach { (key, value) -> editor.putBoolean(key, value) }
                editor.apply()
                revision++
            } catch (t: Throwable) {
                android.util.Log.w("FP-Config", "独立效果选择迁移失败，保留旧语义", t)
            }
        }
        val plan = try {
            ConfigMigration.plan(prefs())
        } catch (t: Throwable) {
            // 迁移失败不能拦住 App 启动：最坏的结果是这批用户的窗口位置还是老语义
            // （而他们几乎都是没动过这几项的 —— 那四个键一个都不存在，本来就没什么可迁）
            android.util.Log.w("FP-Config", "config migration failed", t)
            return
        }
        if (plan == null) return
        val sp = remote ?: draft
        // 旧的总开关键（`window_custom`）在新版里**不再被任何代码读取**。
        // v4 迁移跑过之后必须把它删掉，否则存储里会留着一个「看着还生效、其实没人读」
        // 的键 —— 将来谁把旧键的读取捡回来，就多出一个优先级不明的双事实。
        //
        // 这里**无条件**删（不管 plan.writes 空不空）：`writes` 为空恰好是
        // 「用户装过 3.x 但从没动过那个开关」那种常见情况，同样要收尾。
        // 传进来的 `sp` 只可能是这两个存储，所以不会误删到别处。
        try {
            val e = sp.edit()
            for (w in plan.writes) {
                // 两种写入类型分开处理，而不是统一 `putXxx`：类型写错在存储里
                // 表现成「读不出来 → 回退默认值」，静默且不可逆。
                when (w) {
                    is ConfigMigration.Plan.Write.FloatWrite -> e.putFloat(w.key, w.value)
                    is ConfigMigration.Plan.Write.StringWrite -> e.putString(w.key, w.value)
                    is ConfigMigration.Plan.Write.Remove -> e.remove(w.key)
                }
            }
            e.remove(Keys.pref(Catalog.CORNER_SMALL_WINDOW, ConfigMigration.LEGACY_CUSTOM_NAME))
            e.putInt(Keys.CONFIG_VERSION, plan.version)
            // ⚠️ `remove` 不算「改了一项」：上面那个 `writes` 为空的判断是按
            // 「用户有没有东西要搬」来的，删旧键是收尾，不该让日志说「改了 0 项」时
            // 却出现一条迁移消息（那会让排障的人以为有值被搬动过）
            e.apply()
            if (plan.writes.isNotEmpty()) {
                val msg = "配置迁移 v" + plan.fromVersion + "→v" + plan.version + "：改了 " +
                    plan.writes.size + " 项（" + plan.writes.joinToString { it.key.substringAfterLast('.') } + "）"
                android.util.Log.i("FP-Config", msg)
                UiActionLog.log(msg)
            }
        } catch (t: Throwable) {
            android.util.Log.w("FP-Config", "config migration write failed", t)
            UiActionLog.log("配置迁移写盘失败：" + t.javaClass.simpleName)
        }
        revision++
    }

    fun onFrameworkLost() {
        remote = null
        // 通道断了：之后每一次写入都只能进草稿，界面上必须说得出来
        channelDegraded = true
        revision++
        UiActionLog.log("框架断开，改动进草稿")
    }

    /**
     * 改动有没有真的到达真机。
     *
     * `true` = 现在写的东西只落在本地草稿里，hook 读不到（框架没连上，或远端写失败过）。
     * 这个标记**必须**被界面用起来 —— 它是「界面显示 A、真机按 B」这一整类问题的唯一出口。
     */
    val degraded: Boolean get() = channelDegraded

    /** 还压在草稿里、没送到真机的项数（`__cfg_version` 不算）。 */
    val pendingCount: Int
        get() = try {
            val remoteAll = remote?.all
            ConfigDraftSync.plan(draft.all) { remoteAll?.get(it) }?.keys?.size ?: 0
        } catch (_: Throwable) {
            0
        }

    /**
     * 再试一次把改动送到真机。给「通道降级」那条提示上的按钮用。
     *
     * 两条路都可能坏，所以两件都做：① 重新取一次远程存储（写失败会让它变 null）；
     * ② 服务本身没绑上时让桥重绑一次 —— 只重取一次存储是没用的，那时根本没有服务。
     */
    fun retryChannel() {
        remote = try {
            FrameworkBridge.current?.getRemotePreferences(Keys.GROUP)
        } catch (t: Throwable) {
            android.util.Log.w("FP-Config", "retry getRemotePreferences failed", t)
            null
        }
        if (remote == null) FrameworkBridge.refresh()
        revision++
        syncDraft()
    }

    val isRemote: Boolean get() = remote != null

    /** 当前生效的存储：优先框架，退回本地草稿。 */
    private val store: KeyValueStore
        get() {
            val sp = remote ?: draft
            return SharedPreferencesStore(sp)
        }

    /**
     * 在 Composable 里读配置。
     *
     * 先读一次 [revision] 建立订阅，否则写了配置界面不会刷新 ——
     * 这个坑很隐蔽：值是对的，就是不重绘。
     *
     * 返回的对象**按 revision 缓存**（`remember`）：`Prefs` 的实例身份是好几处
     * 昂贵副作用的缓存键（预览的图标解码就是按 `prefs` 做 `produceState` 的 key）。
     * 每次重组都造一个新的等价对象，会让那些副作用**每次重组都重跑一遍** ——
     * 表现是「预览里的图标偶尔闪一下、拖动时更热」，而功能看着是对的。
     */
    @Composable
    fun observe(): Prefs {
        @Suppress("UNUSED_EXPRESSION")
        revision
        return remember(revision) { Prefs(store) }
    }

    /** 非 Composable 场景（导出诊断信息等）。 */
    fun prefs(): Prefs = Prefs(store)

    enum class WriteResult { REMOTE, LOCAL, FAILED }

    /** 恢复计划一次提交，提交失败保留草稿并向调用方返回实际保存范围。 */
    fun putAll(values: Map<String, Any>): WriteResult {
        require(values.values.all(ConfigDraftSync::isSupported))
        fun writeTo(target: SharedPreferences): Boolean {
            val editor = target.edit()
            for ((key, value) in values) when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            if (target === draft) {
                val deleted = draft.getStringSet(ConfigDraftSync.DELETED_KEYS, emptySet()).orEmpty() - values.keys
                editor.putStringSet(ConfigDraftSync.DELETED_KEYS, deleted)
            }
            return editor.commit()
        }
        val current = remote
        val result = if (current != null && runCatching { writeTo(current) }.getOrDefault(false)) {
            WriteResult.REMOTE
        } else {
            remote = null
            channelDegraded = true
            if (runCatching { writeTo(draft) }.getOrDefault(false)) WriteResult.LOCAL else WriteResult.FAILED
        }
        lastWriteFailed = result == WriteResult.FAILED
        if (result == WriteResult.REMOTE) {
            channelDegraded = !discardDraft(values.keys) || pendingCount > 0
        }
        revision++
        if (result == WriteResult.REMOTE) scheduleAutoReload()
        UiActionLog.log("批量恢复 ${values.size} 项：$result")
        return result
    }

    // ---------------- 写 ----------------

    // Int/Long 以前没有写入方（界面控件只产 Boolean/Float/String/Set）；
    // 「恢复配置」要写回备份里的全部类型，所以补齐（ConfigBackup.restore 用）。
    //
    // 每个写入方都先记一条动作日志：值截短 —— 日志是摘要不是数据容器，
    // Set 这种可能很大的值只记规模。
    fun putBoolean(key: String, value: Boolean): WriteResult {
        UiActionLog.log("写 $key = $value")
        val toggled = Catalog.settingsFeatures.firstOrNull { Keys.enabled(it.key) == key }
        val before = toggled?.let { prefs().isEnabled(it) }
        return edit(key) { editor ->
            // 第一次打开「总开关 + 独立效果」类特性：没写过的子项按推荐组合写。
            // 原来这里把子项写成总开关的**旧值**（= false），新用户打开功能后什么都不发生。
            // 关掉总开关时不碰子项：用户的选择要留着给下次打开用。
            if (value) {
                io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.names.keys
                    .firstOrNull { key == Keys.enabled(it) }
                    ?.let { feature ->
                        io.github.kotoriinsky.tsuzuri.core.ContentCapabilities.onEnable(prefs(), feature)
                            .forEach { (child, on) -> editor.putBoolean(child, on) }
                    }
            }
            editor.putBoolean(key, value)
        }.also { result ->
            if (toggled != null && before != null && result == WriteResult.REMOTE) {
                trackPendingReload(toggled.key, toggled.restart, before, value)
            }
        }
    }

    fun putInt(key: String, value: Int): WriteResult {
        UiActionLog.log("写 $key = $value")
        return edit(key) { it.putInt(key, value) }
    }

    fun putLong(key: String, value: Long): WriteResult {
        UiActionLog.log("写 $key = $value")
        return edit(key) { it.putLong(key, value) }
    }

    fun putFloat(key: String, value: Float): WriteResult {
        UiActionLog.log("写 $key = $value")
        return edit(key) { it.putFloat(key, value) }
    }

    fun putString(key: String, value: String): WriteResult {
        UiActionLog.log("写 $key = " + value.take(40))
        return edit(key) { editor ->
            // 合并项：一个可见选项联动写几个内部键，同一次提交 —— hook 照旧读内部键
            io.github.kotoriinsky.tsuzuri.core.DerivedPrefs.writes(key, value).forEach { (k, v) ->
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Float -> editor.putFloat(k, v)
                    is String -> editor.putString(k, v)
                }
            }
            editor.putString(key, value)
        }
    }

    fun putStringSet(key: String, value: Set<String>): WriteResult {
        UiActionLog.log("写 $key = ${value.size} 项")
        return edit(key) { it.putStringSet(key, value) }
    }

    fun remove(key: String): WriteResult {
        UiActionLog.log("删 $key")
        return edit(key, deleting = true) { editor ->
            // 恢复默认时联动键一起删：否则「选项显示默认、内部键还是旧值」
            io.github.kotoriinsky.tsuzuri.core.DerivedPrefs.removals(key).forEach { editor.remove(it) }
            editor.remove(key)
        }
    }

    var lastWriteFailed by mutableStateOf(false)
        private set

    /**
     * 写一个**跨进程信号**（不是用户参数）：只写框架远端，不进本机草稿、不触发自动重载、
     * 不改 [revision]（界面不需要因此重组）。框架没连上就返回 false —— 信号是给 hook 进程的，
     * 存进草稿等连上再同步只会在之后某个时刻莫名触发一次。
     *
     * 目前只有预览底图的推送请求在用（`PreviewWallpaperContract.REQUEST_KEY`）：
     * 为了要一张预览底图去重启 SystemUI 显然不成比例，所以不能走 [edit]。
     */
    fun signal(key: String, value: Long): Boolean {
        val target = remote ?: return false
        return runCatching { target.edit().putLong(key, value).commit() }
            .onFailure { android.util.Log.w("FP-Config", "信号写入失败：$key", it) }
            .getOrDefault(false)
    }

    private fun edit(key: String, deleting: Boolean = false, block: (SharedPreferences.Editor) -> Unit): WriteResult {
        val target = remote
        if (target != null) {
            try {
                val editor = target.edit()
                block(editor)
                check(editor.commit()) { "远端未确认保存" }
                channelDegraded = !discardDraft(setOf(key)) || pendingCount > 0
                lastWriteFailed = false
                revision++
                scheduleAutoReload()
                return WriteResult.REMOTE
            } catch (t: Throwable) {
                android.util.Log.w("FP-Config", "远端保存失败，保留本机草稿", t)
                channelDegraded = true
                remote = null
            }
        } else channelDegraded = true
        val ok = runCatching {
            val editor = draft.edit()
            block(editor)
            val deleted = draft.getStringSet(ConfigDraftSync.DELETED_KEYS, emptySet()).orEmpty().toMutableSet()
            if (deleting) deleted.add(key) else deleted.remove(key)
            editor.putStringSet(ConfigDraftSync.DELETED_KEYS, deleted)
            editor.commit()
        }.getOrDefault(false)
        lastWriteFailed = !ok
        revision++
        UiActionLog.log(if (ok) "已保存本机草稿：$key" else "保存失败：$key")
        return if (ok) WriteResult.LOCAL else WriteResult.FAILED
    }

    // ---------------- 撤销 ----------------

    /**
     * 批量改动（恢复默认、恢复备份）之前的快照，用来撤销（交互审查 I-03 / I-04）。
     *
     * 只记 [scope] 里的键：配置里还有跨进程信号这类非用户参数，撤销不能把它们也拨回去。
     */
    class Checkpoint internal constructor(
        internal val scope: (String) -> Boolean,
        internal val before: Map<String, Any?>,
    )

    fun checkpoint(scope: (String) -> Boolean): Checkpoint =
        Checkpoint(scope, snapshot().filterKeys(scope))

    /** 把 [scope] 内的键拨回快照时的样子：快照后新出现的删掉，变过的写回。 */
    fun rollback(cp: Checkpoint): WriteResult {
        val now = snapshot().filterKeys(cp.scope)
        // 先删后写：remove 会连带删联动键（DerivedPrefs），先写再删会把刚写回的值又删掉
        val dropped = now.keys - cp.before.keys
        for (key in dropped) remove(key)
        val restore = HashMap<String, Any>()
        for ((key, value) in cp.before) {
            if (value != null && now[key] != value && ConfigDraftSync.isSupported(value)) restore[key] = value
        }
        UiActionLog.log("撤销：删 ${dropped.size} 项，写回 ${restore.size} 项")
        return if (restore.isEmpty()) WriteResult.REMOTE else putAll(restore)
    }

    // ---------------- 待重载 ----------------

    /**
     * 拨过、但还没重载生效的功能开关：功能键 → 拨之前的值（= 目标进程里正在跑的那个值）。
     *
     * 为什么要有（交互审查 I-01）：首页拨开关只是写配置，非热装卸的功能要重载后才生效，
     * 而「改完自动重载」默认关。原来只有详情页在「这一页里拨过」时给一行提示，离页即丢，
     * 首页拨的完全没有 —— 用户看到的就是「开了没反应」。
     *
     * 记拨之前的值而不是只记键：拨过去又拨回来 = 与正在跑的一致，不该再提示。
     * 只在内存里：App 被杀后丢失，代价是少一次提示，不会误报。
     */
    private var reloadBaseline by mutableStateOf<Map<String, Boolean>>(emptyMap())

    /** 待重载的功能数。Compose 状态，状态卡跟着变。 */
    val pendingReloadCount: Int get() = reloadBaseline.size

    fun isReloadPending(featureKey: String): Boolean = featureKey in reloadBaseline

    /** 重载成功后由 [ReloadController] 调用。 */
    fun clearPendingReload() {
        reloadBaseline = emptyMap()
    }

    private fun trackPendingReload(featureKey: String, restart: RestartPolicy, before: Boolean, after: Boolean) {
        // 热装卸的功能拨了即生效；开着自动重载的由 [scheduleAutoReload] 负责
        if (restart == RestartPolicy.HOT || AppSettings.autoReload) return
        val base = reloadBaseline[featureKey]
        reloadBaseline = when {
            base == null && before != after -> reloadBaseline + (featureKey to before)
            base != null && base == after -> reloadBaseline - featureKey
            else -> return
        }
    }

    // ---------------- 改完自动重载 ----------------

    /**
     * 自动重载的延迟。
     *
     * 必须有：用户拖一次滑杆会产生几十次写入，而每次重载都是一次跨进程请求
     * （框架那边要重启目标进程里的模块代码）。没有延迟就会变成重载风暴 ——
     * 既拖慢界面，也正是规范里点名禁止的那种行为。
     */
    private const val AUTO_RELOAD_DELAY_MS = 900L

    private val reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingReload: Job? = null

    /**
     * 配置改完之后，如果用户开了「改完自动重载」，就让目标进程重读一次。
     *
     * 放在 [edit] 里而不是各页面里：写入点有八九处（开关、滑杆、单选、批量恢复默认），
     * 靠每个页面自己记得调用是必然会漏的。
     */
    private fun scheduleAutoReload() {
        if (!AppSettings.autoReload) return
        pendingReload?.cancel()
        pendingReload = reloadScope.launch {
            delay(AUTO_RELOAD_DELAY_MS)
            // 传 null = 让桥自己挑（优先挑跑着旧代码的那个进程）
            val ok = FrameworkBridge.requestReload(null)
            android.util.Log.i(
                "FP-Config",
                if (ok) "auto reload requested" else "auto reload skipped: no reloadable target",
            )
            UiActionLog.log(if (ok) "自动重载：已请求" else "自动重载：无可重载目标，已跳过")
            if (ok) clearPendingReload()
        }
    }

    /** 仅清已确认的键；未完成的离线删除与其他草稿继续保留。 */
    private fun discardDraft(keys: Set<String>): Boolean = runCatching {
        if (keys.isEmpty()) return@runCatching true
        val deleted = draft.getStringSet(ConfigDraftSync.DELETED_KEYS, emptySet()).orEmpty() - keys
        val editor = draft.edit()
        keys.forEach { editor.remove(it) }
        if (deleted.isEmpty()) editor.remove(ConfigDraftSync.DELETED_KEYS)
        else editor.putStringSet(ConfigDraftSync.DELETED_KEYS, deleted)
        check(editor.commit()) { "本机草稿未确认清理" }
        true
    }.getOrElse {
        android.util.Log.w("FP-Config", "已保存远端，但本机草稿清理失败", it)
        false
    }

    /**
     * 本地草稿 → 真机的同步。
     *
     * 规则只有一条：**草稿里有的键全部写过去**（为什么、以及老规则错在哪，
     * 见 [ConfigDraftSync] 的类注释）。老规则是「远端已经有这个键就跳过」，
     * 结果是这批改动**永远**到不了真机，而且没有任何日志。
     *
     * ## 写成功之后必须清草稿 —— 这两步是一件事
     *
     * 留着已搬过去的键，下一次绑定就会用这些**历史值**盖掉更新的远端值
     * （用户在框架连着的时候又改了一次，那次是直接写远端的，草稿并不知道）。
     * 清掉之后草稿的语义就干净了：**里面只有「没送到真机的改动」**，
     * [ConfigDraftSync] 那条「全部写过去」的规则才成立。
     */
    private fun syncDraft() {
        val r = remote ?: return
        val draftAll: Map<String, *>
        val remoteAll: Map<String, *>
        val plan = try {
            draftAll = draft.all
            remoteAll = r.all
            ConfigDraftSync.plan(draftAll) { remoteAll[it] }
        } catch (t: Throwable) {
            android.util.Log.w("FP-Config", "读草稿/远端失败，跳过同步", t)
            return
        }
        val acknowledged = ConfigDraftSync.acknowledgedKeys(draftAll) { remoteAll[it] }
        if (plan == null) {
            // 无需远端写入的历史草稿也必须清理，防止下次连接覆盖更新后的值。
            channelDegraded = !discardDraft(acknowledged)
            revision++
            return
        }
        try {
            val e = r.edit()
            for (w in plan.writes) when (val v = w.value) {
                is Boolean -> e.putBoolean(w.key, v)
                is Float -> e.putFloat(w.key, v)
                is Int -> e.putInt(w.key, v)
                is Long -> e.putLong(w.key, v)
                is String -> e.putString(w.key, v)
                is Set<*> -> e.putStringSet(w.key, v.map { it.toString() }.toSet())
                else -> {}
            }
            plan.deletions.forEach { e.remove(it) }
            check(e.commit()) { "远端未确认同步" }
        } catch (t: Throwable) {
            android.util.Log.w("FP-Config", "草稿同步失败，改动仍只在本机", t)
            UiActionLog.log("草稿同步失败：" + t.javaClass.simpleName)
            channelDegraded = true
            return
        }
        // 顺序不能反：写成功了才清。反过来的话，写失败就等于把用户改动丢了。
        channelDegraded = !discardDraft(plan.keys.toSet() + acknowledged)
        lastWriteFailed = false
        val msg = "草稿已同步到真机（" + plan.keys.size + " 项）：" +
            plan.keys.joinToString { it.substringAfterLast('.') }
        android.util.Log.i("FP-Config", msg)
        UiActionLog.log(msg)
    }

    /** 导出成可复制的文本，诊断页用。 */
    fun dump(): String {
        val sp = remote ?: draft
        return try {
            sp.all.entries.sortedBy { it.key }
                .joinToString("\n") { (k, v) -> "$k = ${render(v)}" }
        } catch (t: Throwable) {
            "读取配置失败：" + t.javaClass.simpleName
        }
    }

    /** 全量键值快照，文件级备份用（[ConfigBackup.exportJson] 的输入）。 */
    fun snapshot(): Map<String, Any?> = try {
        (remote ?: draft).all.filterKeys { it != ConfigDraftSync.DELETED_KEYS }
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun render(v: Any?): String = when (v) {
        null -> "null"
        is Set<*> -> v.joinToString(", ", "[", "]")
        else -> v.toString()
    }
}

/** `SharedPreferences` → [KeyValueStore] 的适配器。带类型兜底。 */
private class SharedPreferencesStore(private val sp: SharedPreferences) : KeyValueStore {
    override fun contains(key: String): Boolean = try { sp.contains(key) } catch (_: Throwable) { false }
    override fun getBoolean(key: String, defValue: Boolean) =
        try {
            sp.getBoolean(key, defValue)
        } catch (_: Throwable) {
            defValue
        }

    override fun getInt(key: String, defValue: Int) =
        try {
            sp.getInt(key, defValue)
        } catch (_: Throwable) {
            defValue
        }

    override fun getFloat(key: String, defValue: Float) =
        try {
            sp.getFloat(key, defValue)
        } catch (_: Throwable) {
            defValue
        }

    override fun getString(key: String, defValue: String?) =
        try {
            sp.getString(key, defValue)
        } catch (_: Throwable) {
            defValue
        }

    override fun getStringSet(key: String, defValue: Set<String>): Set<String> =
        try {
            sp.getStringSet(key, defValue) ?: defValue
        } catch (_: Throwable) {
            defValue
        }
}
