package io.github.kotoriinsky.tsuzuri.hook.feature.pawidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把**本机已装的小部件 provider** 塞进负一屏「添加小部件」的选择列表。
 *
 * 为什么需要它：picker 的候选列表**不是**本机 provider 列表，而是小米云端目录
 * （Retrofit `POST component/store/expand/app`）。云端没有的应用，用户根本看不到
 * —— 于是「随意添加小部件」在列表这一层就断了。本文件补的就是这一段。
 *
 * ## 注入点为什么是 `PickerListViewModel.getMData()`，而不是 `loadData` 返回之后
 *
 * ⚠️ **「`loadData` 返回之后」是错的**，以字节码为准。取证：
 *
 * ```
 * // PickerListViewModel.java:142  —— 只是一个 launch，返回时数据尚未到达
 * public final void loadData(boolean z8) { launchOnUI(new AnonymousClass1(this, z8, null)); }
 *
 * // PickerListViewModel$loadData$1.invokeSuspend（dexdump，classes2.dex）
 * 0x0026: _dataLoading.postValue(a.e)               // 转圈
 * 0x0051: PickerListRepository.loadData$default(…)  // ← 挂起，去云端拉数据
 * 0x005b: if (list.isEmpty()) { postValue(a.a); return }
 * 0x0073: getMData().size() → setMLastLoadedDataCount
 * 0x0080: getMData().addAll(list)                   // ← 云端数据进 mData
 * 0x0087: _dataLoading.postValue(a.d(false))        // ← 通知 UI（setNewInstance）
 * ```
 *
 * `mData.addAll(云端数据)` 在**协程里**（0x0080），`loadData` 一返回就注入只会得到一个
 * 还没轮到 UI 的窗口 —— 所以注入点必须挂在 `getMData()` 上：它是唯一一个
 * 「拿到 ViewModel 的列表、且 PA 自己下一件事就是把它推给 adapter」的洞口，
 * 由我们追加的元素**不需要自己 notify**（否则要反射走 fragment → RecyclerView → adapter，
 * 链路长得多、也脆得多）。
 *
 * 这条「直接往 mData 里塞 `PickerListAppData`」不是我发明的，**PA 自己就这么干**：
 * `PickerAppListFragment.moveClassicWidgetEntryToEnd()`（:219）向 `mData` 追加了一条
 * 合成条目（`expansionType="1"`、`appPackage="classic_widget_entry"`、`itemType` 走默认
 * 1000）。本文件照抄它的构造参数（见 [buildItem]）。
 *
 * ## 具体挂在哪一次 `getMData()`：**列表已经有云端数据的那一次**
 *
 * `getMData()` 的调用点不止一个，时间上分两段：
 *
 * | 调用点 | 时刻 | 我们做什么 |
 * | --- | --- | --- |
 * | `PickerListFragment.initAdapterConfig():402`（`adapter.setList(vm.getMData())`） | `onPageInAnimEnd` 里，**早于** `checkPermission()` 发云端请求 | **不动**（列表还空着，见下） |
 * | 协程内 0x0073 `getMData().size()` | 云端数据到手、还没 addAll、还没通知 UI | **不动**（见下） |
 * | `dataLoadingStatus(a.d)` → `setNewInstance(getMData())` | 云端数据已 addAll、UI 正要刷新 | ★ **在这里注入** |
 *
 * 为什么非要等列表非空（= 云端那一页已经在里面了）：**去重要靠它**。
 * 在列表还空着的时候注入，我们就看不到云端返回了哪些包，于是「本机有、云端也有」的
 * 应用会出现**两行**（例如时钟/日历这类既在云端目录、又在本机的）。等云端页进来之后
 * 再注入，就能按 `appPackage` 把重复的挡掉（策略见 [buildItems]）。
 *
 * 代价（都如实记在下面「已知边界」里）：云端目录为空/断网时**不会注入**
 * —— 但那种情况下 PA 自己就是一张网络错误页，点进去的详情页（云端驱动）同样是空的，
 * 所以这个代价实际为零。另外 `setNewInstance` 是紧接着用**我们改过的同一个 list**
 * 调用的，所以**不需要我们自己 notify**。
 *
 * 这条「直接往 mData 里塞 `PickerListAppData`」不是我发明的，**PA 自己就这么干**：
 * `PickerAppListFragment.moveClassicWidgetEntryToEnd()`（:219）向 `mData` 追加了一条
 * 合成条目（`expansionType="1"`、`appPackage="classic_widget_entry"`、`itemType` 走默认
 * 1000）。本文件照抄它的构造参数（见 [buildItem]）。
 *
 * 于是顺序是 `[云第 1 页][本机条目][云第 2 页]…`（各页都是 addAll 到尾部）。
 *
 * ## 分页计数：注入这一页会被算进「本页长度」，只可能多拉一页（已核对）
 *
 * `mLastLoadedDataCount` 在 0x0073 取的是「本页 addAll **之前**的 size」，UI 侧判
 * 「还有没有下一页」用的是 `itemCount - mLastLoadedDataCount < PAGE_SIZE`
 * （`PickerListFragment.dataLoadingStatus` :139）。我们的条目是在那之后才进去的，
 * 所以**首屏**那次判定变成 `|page1| + N < 6` —— 只会让「还有下一页」更容易成立：
 *  - 若云第 1 页本来就是满页（6 条）：原生也是 false，行为**完全一致**；
 *  - 若云第 1 页不足 6 条（小目录常见）：原生会判定「没有下一页」并立刻
 *    `onAllDataLoaded()`，我们会让它再请求一次空页才收尾 —— 代价是多一次空请求，
 *    以及 `openSource == 2` 那条「经典小部件」入口的插入时机被推迟到那时。
 *  - 第 2 页起 `mLastLoadedDataCount` 已经把我们的条目算进去了，差值恢复成真实页长。
 * 这一条是**刻意选的**：宁可多一次空请求，也不要因为去重把分页提前掐断。
 *
 * ## 已知边界（如实记，别当成 bug）
 *
 *  - **云端目录为空 / 断网时不注入**：那时 `getMData()` 只在 `initAdapterConfig` 被调到
 *    （列表为空，我们放行），`a.a` / `a.c` 两个分支都不会再读列表内容。用户看到的是
 *    PA 原生的空页/网络错误页 —— 与未安装模块时一致。
 *  - **详情页由 [PaWidgetDetailInject] 接手**（同一个开关）。以下是没有它时的情形 ——
 *    **详情页是云端驱动的**：点进去的 `PickerDetailRepository.getPickerData`
 *    对云端没有的包会直接返回 `responseCode=0`（`getPickerData$2.invokeSuspend` 0x0011:
 *    `list.isEmpty() → goto 0x037d → 空 wrapper`），因为 widget 条目**只**由云端
 *    `PickerDetailResponse` 列表生成，本机 provider 只被用来"补全"云端条目
 *    （`getAppWidgetProviderInfo` :480 的 `getInstalledProvidersForPackage`）。
 *    本文件**只保证列表里看得见**；「点进去能添加」还需要详情页侧的第二次注入。
 *
 * ## 纪律
 *
 *  - **可降级是硬要求**：类/方法/构造任何一步对不上 → 静默什么都不做，列表保持原样。
 *    这里跑在用户天天用的负一屏里，异常逃逸 = 可见事故，所以回调体内一律 try/catch。
 *  - **不在滚动/绑定路径上干活**：`getMData()` 会被 `onViewHolderExposed` 这类
 *    曝光回调按条目调到，所以热路径只有**一次引用比较**（[inject] 的第一行），
 *    真正的枚举只在「每个 ViewModel 的第一次非空调用」发生。
 *  - 关态（开关关闭）由 `HookRegistry` 保证**根本不装 hook**（规范 §9）。
 */
object PaWidgetPickerInject {

    // ── 目标：多候选取名（OTA 改名/挪包时逐个试，找不到就降级） ──────────────

    /** 候选：仓库内的实际位置 + 同级的 `viewmodel` 子包（PA 把 repository/service 放在那儿）。 */
    private val VM_CANDIDATES = listOf(
        "com.miui.personalassistant.picker.business.list.PickerListViewModel",
        "com.miui.personalassistant.picker.business.list.viewmodel.PickerListViewModel",
    )

    private val DATA_CANDIDATES = listOf(
        "com.miui.personalassistant.picker.business.list.bean.PickerListAppData",
    )

    /**
     * 类型闸门用的 repository 类。
     *
     * 为什么需要它：`PickerListViewModel<T,…>` 是泛型基类，万一 PA 哪天加一个
     * `T = 别的 bean` 的子类，往它的 `mData` 里塞 `PickerListAppData` 会让 adapter
     * 在处理元素时 ClassCastException —— **那是崩溃，不是降级**。
     * `PickerAppListRepository` 与 `T = PickerListAppData` 是绑死的（:33），
     * 用它当判据最准。取不到这个类时退化为「只信类名」（见 [isTargetVm]）。
     */
    private val REPO_CANDIDATES = listOf(
        "com.miui.personalassistant.picker.business.list.viewmodel.PickerAppListRepository",
    )

    private const val GET_DATA_NAME = "getMData"

    /** repository 字段名（`PickerListViewModel.java:43`）。取不到时按类型弱匹配兜底。 */
    private const val REPO_FIELD_NAME = "mListRepository"

    // ── 构造 `PickerListAppData` 用的常量 ─────────────────────────────────

    /** `PickerListConstant.PICKER_LIST_EXPANSION_TYPE_APP`。 */
    private const val EXPANSION_TYPE_APP = "1"

    /** `PickerListConstant.VIEW_TYPE_DEFAULT`（= `PickerListAppData` 的默认 itemType）。 */
    private const val VIEW_TYPE_DEFAULT = 1000

    /** 本机 provider 一次最多注入多少条 —— 防止某个 ROM 装了上百个 provider 把列表撑爆。 */
    private const val MAX_INJECT = 60

    private const val DIAG_LIMIT = 5

    // ── 状态 ────────────────────────────────────────────────────────────

    private val lock = Any()

    private val handles = ArrayList<HookHandle>(2)

    @Volatile
    private var targetVmClass: Class<*>? = null

    @Volatile
    private var dataCtor: Constructor<*>? = null

    @Volatile
    private var getAppPackage: Method? = null

    /** repository 类；null = 闸门不可用（按类名放行，见 [isTargetVm]）。 */
    @Volatile
    private var repoClass: Class<*>? = null

    /** repository 字段；null = 名字对不上。 */
    @Volatile
    private var repoField: Field? = null

    /**
     * 最近处理过的 `mData`。
     *
     * 用**弱引用**存放：`mData` 由 ViewModel 强持有，ViewModel 随 picker 页面销毁；
     * 我们不该用一个静态字段把整张列表（可能上百条）钉在进程里。
     * 比较时 `get()` 不分配对象，热路径够便宜。
     */
    @Volatile
    private var lastSeen: WeakReference<Any>? = null

    private val diag = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 注入过的包（= 本机有、云端目录没有）。详情页注入 [PaWidgetDetailInject] 只对这些包
     * 返回本地数据，其余照常走云端。两个 hook 在同一进程，共享这一个内存集合。
     */
    internal val injectedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ── 安装 / 卸载 ──────────────────────────────────────────────────────

    fun install(ctx: HookContext): InstallReport {
        if (handles.isNotEmpty()) return InstallReport(handles.size, notes = listOf("already"))

        val missing = mutableListOf<String>()

        val vmClass = findClass(ctx, VM_CANDIDATES)
        if (vmClass == null) {
            missing += VM_CANDIDATES
            ctx.log(Log.WARN, "负一屏 picker 注入：找不到 PickerListViewModel，跳过（列表保持原样）")
            return InstallReport(0, missing)
        }
        targetVmClass = vmClass

        val dataClass = findClass(ctx, DATA_CANDIDATES)
        if (dataClass == null) {
            missing += DATA_CANDIDATES
            ctx.log(Log.WARN, "负一屏 picker 注入：找不到 PickerListAppData，跳过")
            return InstallReport(0, missing)
        }
        val ctor = findConstructor(dataClass)
        if (ctor == null) {
            missing += "${dataClass.simpleName}.<init>(10)"
            ctx.log(Log.WARN, "负一屏 picker 注入：PickerListAppData 的 10 参构造对不上，跳过")
            return InstallReport(0, missing)
        }
        dataCtor = ctor
        getAppPackage = findGetAppPackage(dataClass)

        // 类型闸门的两个零件：取不到不算失败（只是闸门退化成「只信类名」）
        repoClass = findClass(ctx, REPO_CANDIDATES)
        repoField = findRepoField(vmClass)
        if (repoClass == null || repoField == null) {
            ctx.log(
                Log.INFO,
                "负一屏 picker 注入：repository 闸门不可用（class=${repoClass != null} " +
                    "field=${repoField != null}），退化为按类名判定"
            )
        }

        val methods = vmClass.declaredMethods.filter {
            it.parameterTypes.isEmpty() &&
                List::class.java.isAssignableFrom(it.returnType) &&
                it.name.startsWith(GET_DATA_NAME)
        }
        if (methods.isEmpty()) {
            missing += "${vmClass.simpleName}.$GET_DATA_NAME()"
            ctx.log(Log.WARN, "负一屏 picker 注入：找不到 $GET_DATA_NAME()，跳过")
            return InstallReport(0, missing)
        }

        var hooked = 0
        for (m in methods) {
            try {
                val h = ctx.module.hook(m).intercept { chain ->
                    val ret = chain.proceed()
                    try {
                        if (ret is ArrayList<*>) inject(chain.thisObject, ret, ctx)
                    } catch (t: Throwable) {
                        // 注入失败 = 列表保持原样，**绝不让异常回到 PA 的协程里**
                        note(ctx, "inject", "注入失败：${t.javaClass.simpleName}", t)
                    }
                    ret
                }
                handles.add(h)
                hooked++
                ctx.log(Log.INFO, "hooked ${vmClass.simpleName}.${m.name}()")
            } catch (t: Throwable) {
                ctx.log(Log.WARN, "hook ${m.name} failed", t)
                missing += "${vmClass.simpleName}.${m.name}()"
            }
        }

        return InstallReport(
            hooked,
            missing,
            notes = listOf(
                "cls=${vmClass.name}",
                "ctor=${ctor.parameterTypes.joinToString(",") { it.simpleName }}",
            ),
        )
    }

    fun uninstall() {
        for (h in handles) {
            try {
                h.unhook()
            } catch (_: Throwable) {
                // 卸不掉没有补救手段，也不能因此抛出去
            }
        }
        handles.clear()
        lastSeen = null
        injectedPackages.clear()
    }

    // ── 注入 ────────────────────────────────────────────────────────────

    /**
     * 往 `mData` 里补本机 provider。
     *
     * 前两行是**热路径**（`getMData()` 会被曝光回调按条目调到）：一次引用比较 +
     * 一次 `isEmpty()`，命中直接返回，不反射、不分配、不打日志。
     *
     * **列表为空就放行**（不标记已处理）：那是云端数据还没到的时刻
     * （`initAdapterConfig` 那次），等它进来我们还要再来一次 —— 见类注释。
     */
    private fun inject(vm: Any?, list: ArrayList<*>, ctx: HookContext) {
        if (lastSeen?.get() === list) return
        if (list.isEmpty()) return
        synchronized(lock) {
            if (lastSeen?.get() === list) return
            // 无论成功失败都记下来：同一个 ViewModel 不再重复做闸门判定与枚举
            lastSeen = WeakReference(list as Any)
            if (!isTargetVm(vm)) {
                note(ctx, "notTarget", "不是 PickerAppListViewModel（跳过，列表保持原样）")
                return
            }
            // 列表里已经有元素、而且不是 PickerListAppData → 说明 T 不是它，
            // 注入会让 adapter 崩，宁可什么都不做
            val head = list.firstOrNull()
            if (head != null && dataCtor?.declaringClass?.isInstance(head) != true) {
                note(ctx, "alien", "列表元素是 ${head.javaClass.name}，不是 PickerListAppData，跳过")
                return
            }
            val items = buildItems(list, ctx)
            if (items.isEmpty()) return
            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Any>).addAll(items)
            note(ctx, "inject", "已向 picker 列表注入本机小部件条目 ${items.size} 条")
        }
    }

    /**
     * 类型闸门：确认这确实是「`PickerListAppData` 那一条列表」。
     *
     * 判据一（硬）：接收者的类名与安装时 hook 的那个类完全一致
     * （`getMData()` 是 final 且无子类，见取证）。
     * 判据二（软）：`mListRepository` 是 `PickerAppListRepository`。
     * 判据二**取不到零件时不否决**（只记一条日志）—— 名字变了就整个功能静默失效，
     * 比「多一层弱判据缺失」更糟；而判据一已经把最危险的情形（另一个 bean 的列表）挡住了。
     */
    private fun isTargetVm(vm: Any?): Boolean {
        val cls = targetVmClass ?: return false
        if (vm == null || vm.javaClass != cls) return false
        val f = repoField ?: return true
        val rc = repoClass ?: return true
        val repo = try {
            f.get(vm)
        } catch (_: Throwable) {
            return true
        }
        return repo != null && rc.isInstance(repo)
    }

    /**
     * 枚举本机 provider，合成列表条目。
     *
     * 去重策略（明确写在这里，避免下一轮又有人猜）：
     *  - 粒度是**包**，不是 provider/组件 —— 列表本来就是「一个应用一行」，点进去才选具体小部件。
     *    所以同一个包下多个 provider 只出一条，`appWidgetAmount` 记它的 provider 个数。
     *  - **本机有、云端也有 → 跳过本机的**：云端那条带着服务端的版本号/图标/排序，
     *    数据更全；两条同时出现才是坏体验。判据是列表里已有的 `appPackage` 集合
     *    （调用时机保证云端那一页已经在里面了，见类注释）。
     *  - 系统应用**不做特殊过滤**：常见的（时钟/日历/设置…）几乎都在云端目录里，
     *    会被上一条去重掉；真漏掉的也是能正常添加的小部件，不该静默丢。
     */
    @Suppress("DEPRECATION")
    private fun buildItems(existing: ArrayList<*>, ctx: HookContext): List<Any> {
        val ctor = dataCtor ?: return emptyList()
        val context = appContext() ?: run {
            note(ctx, "noCtx", "拿不到 Context，跳过注入")
            return emptyList()
        }
        val awm = try {
            AppWidgetManager.getInstance(context)
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        // `getInstalledProviders()` 自 API 21 起标了 deprecated，但**本 ROM 的 PA 自己
        // 就在用它**（`picker/util/d.java:203` 的 AppWidgetUtil.h、`k5/a.java:251`），
        // 语义（当前 user 的全部 provider）正是我们要的，不去追新 API。
        val providers: List<AppWidgetProviderInfo> = try {
            awm.installedProviders
        } catch (t: Throwable) {
            note(ctx, "noProviders", "getInstalledProviders 失败：${t.javaClass.simpleName}", t)
            return emptyList()
        }
        if (providers.isEmpty()) return emptyList()

        // 云端已有的包名（小写，比较用）
        val taken = HashSet<String>(existing.size * 2 + 4)
        val getter = getAppPackage
        for (e in existing) {
            val pkg = try {
                if (getter != null && e != null && getter.declaringClass.isInstance(e)) {
                    getter.invoke(e) as? String
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
            if (pkg != null) taken.add(pkg.lowercase(Locale.ROOT))
        }

        // 按包聚合 provider 个数（LinkedHashMap：先稳定，再按应用名排序）
        val byPkg = LinkedHashMap<String, Int>()
        for (p in providers) {
            // 需要先配置的不算：PA 添加时不弹配置页，详情页那边同一个判据
            if (!PaWidgetDetailInject.isAddable(p)) continue
            val pkg = p.provider?.packageName ?: continue
            byPkg[pkg] = (byPkg[pkg] ?: 0) + 1
        }

        val pm = try {
            context.packageManager
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        // 先攒 (排序键, 条目)，再统一排序 —— 中英文混排时按应用名比按包名可读得多
        val staged = ArrayList<Pair<String, Any>>(byPkg.size)
        var skipped = 0
        for ((pkg, count) in byPkg) {
            if (taken.contains(pkg.lowercase(Locale.ROOT))) {
                skipped++
                continue
            }
            val item = try {
                buildItem(ctor, pm, pkg, count)
            } catch (t: Throwable) {
                note(ctx, "build", "$pkg 构造条目失败：${t.javaClass.simpleName}", t)
                null
            } ?: continue
            staged.add(labelOf(item) to item)
            injectedPackages.add(pkg)
            if (staged.size >= MAX_INJECT) break
        }

        if (staged.isEmpty()) {
            note(ctx, "nothing", "本机 provider 都在云端列表里（跳过 $skipped 个包），不注入")
            return emptyList()
        }
        // 一次性开销，可以接受；不在加载/绑定的每帧路径上
        val collator = try {
            Collator.getInstance(Locale.CHINA)
        } catch (_: Throwable) {
            null
        }
        if (collator != null) {
            staged.sortWith(Comparator { a, b -> collator.compare(a.first, b.first) })
        }
        note(ctx, "scan", "本机 provider 覆盖 ${byPkg.size} 个包，云端已有 $skipped 个，注入 ${staged.size} 条")
        return staged.map { it.second }
    }

    /** 从包信息合成一条 `PickerListAppData`（构造参数与 PA 自己那条合成条目对齐）。 */
    @Suppress("DEPRECATION")
    private fun buildItem(
        ctor: Constructor<*>,
        pm: PackageManager,
        pkg: String,
        widgetCount: Int,
    ): Any? {
        val ai: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
        val label = try {
            pm.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            pkg
        }
        val icon: Drawable? = try {
            ai.loadIcon(pm)
        } catch (_: Throwable) {
            null
        }
        // 版本号/版本名只给详情页做「已安装版本」比对用，拿不到就给 0 / null。
        // 这里刻意用**老 API**（`PackageInfo.versionCode` / `getPackageInfo(pkg, int)`）：
        // 它们只是 deprecated，在 compileSdk 37 上仍然解析得到，而 `ApplicationInfo.longVersionCode`
        // 在这一版 android.jar 里**根本解析不了**（编译期就报 Unresolved reference，实测）。
        val pi = try {
            pm.getPackageInfo(pkg, 0)
        } catch (_: Throwable) {
            null
        }
        val versionCode = pi?.versionCode ?: 0
        val versionName = pi?.versionName

        return ctor.newInstance(
            EXPANSION_TYPE_APP,
            pkg,
            label,
            versionCode,
            versionName,
            // appIcon 是**非空** String（云端图标 URL）。本机条目走 localAppIcon 分支
            // （`PickerAppListAdapter.convert`:47），给空串即可，但不能给 null。
            "",
            widgetCount,
            // appNamePinyin：全树搜索确认**没有任何读取点**（只有 PickerListAppData 自己的
            // getter/copy/toString），所以这里给应用名，不猜拼音算法。
            label,
            // localAppIcon：adapter 优先用它；为 null 时它会去 glide 加载 appIcon（空串）
            icon,
            VIEW_TYPE_DEFAULT,
        )
    }

    // ── 反射小工具（多候选 + 弱特征，取不到就返回 null 由调用方降级） ──────

    private fun findClass(ctx: HookContext, candidates: List<String>): Class<*>? {
        for (name in candidates) {
            try {
                return Class.forName(name, false, ctx.classLoader)
            } catch (_: Throwable) {
                // 试下一个候选
            }
        }
        return null
    }

    /**
     * 找 `PickerListAppData` 的 10 参构造（已核对的签名，见类注释）：
     * `(String expansionType, String appPackage, String appName, int appVersionCode,
     *   String appVersionName, String appIcon, int appWidgetAmount, String appNamePinyin,
     *   Drawable localAppIcon, int itemType)`
     *
     * 用**位置 + 弱类型特征**匹配而不是写死 `getConstructor(...)`：
     * 只要 PA 挪一下参数顺序（或者 `appIcon` 换成 `CharSequence`），硬匹配就整条失效。
     */
    private fun findConstructor(dataClass: Class<*>): Constructor<*>? {
        val all = dataClass.declaredConstructors.filter { it.parameterTypes.size == 10 }
        // 强特征：p0/p1/p2 是 String，p3 是 int，p6 是 int，p8 是 Drawable，p9 是 int。
        // int 用**类型名**判（`Integer.TYPE` 是 `int`，Kotlin 里 `Int::class.java` 的装箱
        // 语义容易写错，直接比名字最不容易出事）。
        val isInt = { c: Class<*> -> c.name == "int" || c.name == "java.lang.Integer" }
        val strong = all.firstOrNull { p ->
            val t = p.parameterTypes
            t[0] == String::class.java &&
                t[1] == String::class.java &&
                t[2] == String::class.java &&
                isInt(t[3]) &&
                isInt(t[6]) &&
                Drawable::class.java.isAssignableFrom(t[8]) &&
                isInt(t[9])
        }
        val pick = strong ?: all.firstOrNull()
        return pick?.also {
            try {
                it.isAccessible = true
            } catch (_: Throwable) {
                // 公开构造不需要它；失败也不影响 newInstance
            }
        }
    }

    private fun findGetAppPackage(dataClass: Class<*>): Method? = try {
        dataClass.getMethod("getAppPackage")
    } catch (_: Throwable) {
        null
    }

    /** repository 字段：先按名字，再按「类型名以 PickerListRepository 结尾」弱匹配。 */
    private fun findRepoField(vmClass: Class<*>): Field? {
        val byName = try {
            vmClass.getDeclaredField(REPO_FIELD_NAME)
        } catch (_: Throwable) {
            null
        }
        val f = byName ?: vmClass.declaredFields.firstOrNull {
            it.type.name.endsWith("PickerListRepository") ||
                it.type.name.endsWith("PickerAppListRepository")
        }
        return f?.also {
            try {
                it.isAccessible = true
            } catch (_: Throwable) {
                // 同上
            }
        }
    }

    /** 取一条 `getAppName()`，只用于排序。取不到返回空串。 */
    private fun labelOf(item: Any): String = try {
        item.javaClass.getMethod("getAppName").invoke(item) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * 宿主进程的 Context。
     *
     * 与 `SystemUiEnv.context()` 同一招：`ActivityThread.currentApplication()` 在任何
     * 应用进程里都成立（负一屏就是普通应用进程）。拿不到就什么都不做。
     */
    internal fun appContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    /**
     * 有预算的诊断日志：同 tag 最多 [DIAG_LIMIT] 条。
     *
     * 这个特性每次打开 picker 都会被走到，失败路径不设预算就会刷满日志；
     * 但一条都不留又会让「开关开了没反应」无从查起 —— 与 `PaWidgetAdmission` 同一套。
     */
    private fun note(ctx: HookContext, tag: String, msg: String, t: Throwable? = null) {
        val n = diag.computeIfAbsent(tag) { AtomicInteger(0) }.incrementAndGet()
        if (n > DIAG_LIMIT) return
        val line = "[$tag #$n] $msg"
        if (t == null) ctx.log(Log.INFO, line) else ctx.log(Log.WARN, line, t)
    }
}
