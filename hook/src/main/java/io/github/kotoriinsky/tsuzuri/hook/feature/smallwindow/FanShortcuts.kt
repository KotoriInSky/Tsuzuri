package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.ActivityTargets
import io.github.kotoriinsky.tsuzuri.core.ShortcutEntries
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv

/**
 * 快捷方式（shortcut）目标：枚举、发布、启动。
 *
 * ## 为什么枚举必须在这里（而不是设置界面）
 *
 * 设置界面（模块自己的 APK）**查询不了别的应用的快捷方式**，这是系统硬性限制，
 * 不是权限没申请。本机 `services.jar` 反编译（`research/tmp_shortcut/sources/
 * com/android/server/pm/LauncherAppsService.java:862-871`）：
 *
 * ```java
 * private void ensureShortcutPermission(int callerUid, int callerPid, String callingPackage) {
 *     verifyCallingPackage(callingPackage, callerUid);
 *     if (!mShortcutServiceInternal.hasShortcutHostPermission(userId, callingPackage, pid, uid))
 *         throw new SecurityException("Caller can't access shortcut information");
 * }
 * ```
 *
 * 而 `hasShortcutHostPermission`（`ShortcutService.java:2356-2376`）只有两条路能进：
 * `ACCESS_SHORTCUTS` 权限（signature|privileged，普通应用拿不到）或**当前桌面**
 * （`getDefaultLauncher`）。模块的设置界面两者都不是，必然抛
 * `SecurityException`。
 *
 * SystemUI 两条路都占：本机 `dumpsys package com.android.systemui` 实测
 * `android.permission.ACCESS_SHORTCUTS: granted=true`。所以**目录只能在 SystemUI 里枚举**，
 * 再把结果发布到配置里给设置界面读（见 [Catalog.publish]）。
 *
 * 这也是 HyperGesture 的做法（它的 `xposed/ShortcutHostRuntime.java:340-384` 在
 * SystemUI/MiuiHome 里查询，再 `report_shortcuts` 给应用进程的 ContentProvider），
 * 只是我们用配置通道代替它的 ContentProvider —— 项目里唯一被证明可用的跨进程通道
 * 就是 `getRemotePreferences()`。
 *
 * ## 存储格式：`包名/快捷方式 id`
 *
 * 扇形的清单是一组字符串，两种项共用同一个键：
 *
 * ```
 * com.tencent.mm                              ← 应用（旧配置的存量格式，语义不变）
 * com.tencent.mm/launch_type_scan_qrcode      ← 快捷方式
 * ```
 *
 * **分割点取第一个 `/`**：包名按 Java 包名规范不可能含 `/`，所以这个格式无歧义，
 * 而且旧的纯包名配置**不需要迁移**（这正是选它的理由）。
 *
 * ## 与聚合支付那份实现的区别（刻意不共用代码）
 *
 * `feature/aggpayment/ShortcutRepository.kt` 做的是同一件事的另一半：它只查
 * 微信/支付宝四个**写死的** id、只用于支付面板，而且启动时优先直连 intent。
 * 这里要的是「任意应用的任意快捷方式」+「进扇形」，存储、生命周期、失败语义都不同；
 * 跨特性复用会让两个特性互相牵制（一个改另一个就崩），所以这里重新组织了一遍，
 * 只**照抄它验证过的结论**（包上下文才能查、`getShortcuts` 的三组 flags、
 * `getShortcutIconDrawable` 能拿到真图标）。
 */
internal object FanShortcuts {

    private const val TAG = "FP-Fan"

    /**
     * 应用项与快捷方式项的分隔符。
     *
     * **单源引用**契约层的 [ShortcutEntries.SEP]，不再自己写一遍 `/`：这个字符同时决定
     * 「怎么拆清单项」（`ShortcutEntries.parse`）、「怎么拼键」（[FanTargets.shortcutKey]）、
     * 以及设置界面那一侧的所有读写。两处各写一个字面量，哪天只改了一边，结果是**全盘拆错**
     * （用户勾的项全部认不出），而编译和日志都正常。
     */
    const val SEP = ShortcutEntries.SEP

    /** 目录项内部的分隔符（控制字符，正常标签里不会出现）。 */
    private const val FIELD = '\u0001'

    /** 目录发布上限。26 个字母的极端情况也就几千条，收口到 600 免得把配置文件撑爆。 */
    private const val MAX_CATALOG = 600

    /**
     * 按包补充查询的包数上限。
     *
     * 每一次都是一次 binder 调用（实测几毫秒），而这一段跑在安装/配置变更的后台线程上：
     * 24 个包约几十毫秒，可以接受；上百个包就该换别的做法了（例如只补「清单里的包」）。
     */
    private const val MAX_SUPPLEMENT_PACKAGES = 32

    /**
     * 目录投递给前端的地址。**必须与 `ui/.../state/ShortcutCatalogStore.kt` 里的一致**
     * （那边的 `<provider>` 声明在 `ui/src/main/AndroidManifest.xml`）。
     *
     * 为什么不是配置键：项目的配置通道（框架的 `getRemotePreferences()`）在 hook 侧
     * **只读**，设备实测如此
     *（`edit()` 抛 `UnsupportedOperationException`）。所以后端→前端只能另开一条口子，
     * 形态照 HyperGesture 的 `ShortcutHostRuntime`（它也是往模块 App 的
     * ContentProvider 里 `call("report_shortcuts")`）。
     */
    private const val PROVIDER_AUTHORITY = "io.github.kotoriinsky.tsuzuri.shortcutcatalog"
    private const val METHOD_PUBLISH = "publish_catalog"
    private const val EXTRA_ENTRIES = "entries"

    /**
     * 清单里快捷方式的**图标**，单独一次投递（用户 2026-10-23：单项图标页显示的是宿主应用图标）。
     * 设置界面拿不到别的应用的快捷方式图标（同 [loadCatalog] 那条权限限制），只能由这里画好送过去。
     *
     * 风险控制（与用户确认过的方案）：
     *  - **只送清单里的快捷方式**，不送整个目录 —— 量小一两个数量级，也只有用户主动加进扇子的
     *    动态快捷方式（可能是联系人头像）才会落到设置 App 里；
     *  - **与文字目录分开调用**：图标这一次失败或超限，导入页的文字目录不受影响；
     *  - 每张 [ICON_PX] 边长 PNG，单张超过 [ICON_MAX_BYTES] 就不送，总量到 [ICONS_MAX_BYTES] 就停 ——
     *    binder 事务缓冲整个进程共用约 1MB，单次远低于它；送不到的项界面退回宿主应用图标。
     */
    private const val METHOD_PUBLISH_ICONS = "publish_icons"
    private const val EXTRA_ICON_KEYS = "icon_keys"
    private const val EXTRA_ICON_PREFIX = "icon:"
    private const val ICON_PX = 96
    private const val ICON_MAX_BYTES = 32 * 1024
    private const val ICONS_MAX_BYTES = 256 * 1024

    /**
     * 一次枚举的产物。
     *
     * [failure] 非空 = 降级了（权限被收回 / 服务异常 / 没 LauncherApps），
     * 上层据此**保留**配置里已有的快捷方式项（宁可点不动，也不能让用户勾过的项凭空消失）。
     */
    class Catalog(
        /** 键 = `包名/快捷方式 id`。 */
        private val items: Map<String, ShortcutInfo>,
        val failure: String?,
        /** 描述，写日志用。 */
        val note: String,
    ) {
        val ok: Boolean get() = failure == null

        val size: Int get() = items.size

        fun get(pkg: String, id: String): ShortcutInfo? = items[FanTargets.shortcutKey(pkg, id)]

        /**
         * 把目录投递给设置界面（「导入快捷方式」页的列表就来自它）。
         *
         * 只在内容**真的变了**时才投递：这个调用会拉起模块 App 进程（如果它没在跑），
         * 每次重载都投一次是没必要的唤醒。上一次投的内容记在进程内（[lastPublished]）。
         *
         * @return 是否真的投递了
         */
        fun publish(context: Context): Boolean {
            // 权限都没拿到就别投递：空目录会让设置界面显示「设备上没有快捷方式」，
            // 那是错的 —— 界面应该显示「后端暂时拿不到」
            if (failure != null) return false

            val encoded = items.values
                .asSequence()
                .filter { it.`package` != null && it.isEnabled }
                .map { si ->
                    // label 里理论上不该出现分隔符；真出现了就换掉，绝不能让它破坏结构
                    si.`package` + FIELD + si.id + FIELD + labelOf(si).replace(FIELD, ' ')
                }
                .sorted()
                .take(MAX_CATALOG)
                .toHashSet()

            if (encoded == lastPublished) return false
            return try {
                val extras = Bundle().apply { putStringArrayList(EXTRA_ENTRIES, ArrayList(encoded)) }
                val result = context.contentResolver.call(
                    Uri.parse("content://" + PROVIDER_AUTHORITY),
                    METHOD_PUBLISH,
                    null,
                    extras,
                )
                lastPublished = encoded
                Log.i(
                    TAG,
                    "快捷方式目录已投递：${encoded.size} 条，接收方=" +
                        (result?.getBoolean("ok") ?: false),
                )
                true
            } catch (t: Throwable) {
                // 投不进去（Provider 被禁、进程起不来）不影响扇形本身，只影响设置界面的列表。
                // 记一条 warn，别让「导入列表是空的」变成无从查起的问题。
                Log.w(TAG, "投递快捷方式目录失败：" + t.javaClass.simpleName + " " + t.message)
                false
            }
        }
    }

    /**
     * 把 [wanted]（清单项，`包名/id`）里的快捷方式图标投递给设置界面。**后台线程调**。
     * 内容没变（同一组键 + 同一批 `lastChangedTimestamp`）就不投。
     */
    fun publishIcons(context: Context, catalog: Catalog, wanted: Collection<String>) {
        if (!catalog.ok) return
        val la = try {
            context.getSystemService(LauncherApps::class.java)
        } catch (_: Throwable) {
            null
        } ?: return
        val targets = wanted.asSequence()
            .distinct()
            .map { ShortcutEntries.parse(it) }
            .filter { it.kind == ShortcutEntries.Kind.SHORTCUT && it.id != null }
            .mapNotNull { catalog.get(it.pkg, it.id!!) }
            .toList()
        val signature = targets.map { FanTargets.shortcutKey(it.`package`, it.id) + "@" + it.lastChangedTimestamp }.toSet()
        if (signature == lastPublishedIcons) return
        val extras = Bundle()
        val keys = ArrayList<String>(targets.size)
        var total = 0
        for (si in targets) {
            val key = FanTargets.shortcutKey(si.`package`, si.id)
            val bytes = try {
                val d = iconOf(la, si, 0) ?: continue
                val bmp = android.graphics.Bitmap.createBitmap(ICON_PX, ICON_PX, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, ICON_PX, ICON_PX)
                d.draw(canvas)
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                out.toByteArray()
            } catch (t: Throwable) {
                Log.w(TAG, "编码快捷方式图标失败：$key " + t.javaClass.simpleName)
                continue
            }
            if (bytes.size > ICON_MAX_BYTES || total + bytes.size > ICONS_MAX_BYTES) continue
            total += bytes.size
            keys.add(key)
            extras.putByteArray(EXTRA_ICON_PREFIX + key, bytes)
        }
        extras.putStringArrayList(EXTRA_ICON_KEYS, keys)
        try {
            context.contentResolver.call(Uri.parse("content://" + PROVIDER_AUTHORITY), METHOD_PUBLISH_ICONS, null, extras)
            lastPublishedIcons = signature
            Log.i(TAG, "快捷方式图标已投递：${keys.size}/${targets.size} 张，共 ${total / 1024}KB")
        } catch (t: Throwable) {
            Log.w(TAG, "投递快捷方式图标失败：" + t.javaClass.simpleName + " " + t.message)
        }
    }

    @Volatile
    private var lastPublishedIcons: Set<String>? = null

    /** 上一次投递成功的内容（进程内去重用；SystemUI 重启后重置，最多多投一次）。 */
    @Volatile
    private var lastPublished: Set<String>? = null

    /**
     * 查询标志。**每一项都不能少**，其中最后一项是设备实测逼出来的：
     *
     * ```
     * DYNAMIC | MANIFEST | PINNED | CACHED  —— 四类快捷方式本身
     * PINNED_BY_ANY_LAUNCHER                —— 见下面那段
     * ```
     *
     * ## 为什么必须有 `FLAG_MATCH_PINNED_BY_ANY_LAUNCHER`
     *
     * 设备实测（本次）：只给前四个 flag 时，**支付宝的 1000–1003 拿得到、微信的三条拿不到**。
     * 原因在 `ShortcutService.ShortcutPackage.findAll` 的这条过滤
     *（`research/tmp_shortcut/sources/com/android/server/pm/ShortcutPackage.java:635-654`）：
     *
     * ```java
     * boolean isPinnedByCaller = callingLauncher == null ||
     *         (pinnedByCallerSet != null && pinnedByCallerSet.contains(si.getId()));
     * if (!getPinnedByAnyLauncher && si.isFloating() && !si.isCached() && !isPinnedByCaller) return;
     * ```
     *
     * 调用方是 **SystemUI 而不是当前桌面**，`pinnedByCallerSet`（谁固定过这条快捷方式）是空的，
     * 于是所有「动态且未被固定」的快捷方式（微信的收付款 / 扫一扫正是这一类）全被丢掉；
     * 而支付宝那四条是 **pinned**（isFloating 为 false），所以能拿到 ——
     * 这解释了「同一个查询里有的应用有、有的没有」这种最容易被误判成随机现象的行为。
     *
     * 这个 flag 让服务端用 `getPinnedByAnyLauncher=true` 走另一条分支（正是它的用途），
     * 前提是调用方有 `ACCESS_SHORTCUTS` —— SystemUI 有（设备实测 granted=true）。
     */
    private val MATCH_FLAGS =
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
            LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED or
            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER

    /** 一次枚举的结果：要么有数据，要么有一个可写进日志的原因。 */
    private class Load(val items: Map<String, ShortcutInfo>, val failure: String?)

    /**
     * 枚举全部快捷方式。**后台线程调**（一次 binder 调用，实测 3~4ms / 200 余条）。
     *
     * 必须用 **SystemUI 自己的 Context**：`LauncherApps` 把调用方包名一并传给
     * ShortcutService 对账，换个 Context 就是 `Calling package name mismatch`
     * （聚合支付那边的设备实测结论，本项目已记录）。
     */
    fun loadCatalog(context: Context, alsoPackages: Collection<String> = emptyList()): Catalog {
        val la = try {
            context.getSystemService(LauncherApps::class.java)
        } catch (t: Throwable) {
            null
        } ?: return Catalog(emptyMap(), "no LauncherApps", "none")

        val result = try {
            val q = LauncherApps.ShortcutQuery()
            // 三组 flags 一起给：动态（微信/支付宝发布的那种）、清单声明的、用户固定的。
            // 少给一组就会整类漏掉 —— 聚合支付那边实测这四项目标全是 dynamic。
            // 加 CACHED：已装应用之外的缓存快捷方式（系统自带的少量条目），
            // 与 HyperGesture 的 `setQueryFlags(27)` 取值一致。
            q.setQueryFlags(MATCH_FLAGS)
            val list = la.getShortcuts(q, Process.myUserHandle())
            val map = HashMap<String, ShortcutInfo>((list?.size ?: 0) * 2)
            for (si in list.orEmpty()) {
                val pkg = si.`package` ?: continue
                map[FanTargets.shortcutKey(pkg, si.id)] = si
            }

            // ── 按包补一遍 ─────────────────────────────────────────────
            // **为什么全局查询不够（设备实测）**：同一次全局查询拿到了支付宝的 1000–1003
            // （它们是 pinned 的），**却没拿到微信的三条 dynamic 快捷方式** ——
            // ShortcutService 在 `ShortcutPackage.findAll` 里有一条过滤：
            //   `if (!getPinnedByAnyLauncher && si.isFloating() && !si.isCached() && !isPinnedByCaller)`
            // 就把「没被调用方固定过的浮动快捷方式」丢掉了（`ShortcutService.java:635-654`）。
            // 调用方是 SystemUI 而不是桌面，`pinnedByCallerSet` 基本是空的，于是微信这种
            // 「动态 + 未固定」的全都不可见。**按包查一次就能看到**（聚合支付那边实测过微信三条）。
            //
            // 只补「用户清单里出现过的包」+「常用推荐里写死的那几个包」，
            // 不做全机 144 个包 × 一次 binder —— 那是几百毫秒的无意义开销。
            val supplemental = alsoPackages.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains('/') }
                .distinct()
                .take(MAX_SUPPLEMENT_PACKAGES)
            for (pkg in supplemental) {
                val one = try {
                    val q1 = LauncherApps.ShortcutQuery()
                    q1.setQueryFlags(MATCH_FLAGS)
                    q1.setPackage(pkg)
                    la.getShortcuts(q1, Process.myUserHandle())
                } catch (_: Throwable) {
                    null
                }
                for (si in one.orEmpty()) {
                    val p = si.`package` ?: continue
                    map[FanTargets.shortcutKey(p, si.id)] = si
                }
            }
            Load(map, null)
        } catch (t: Throwable) {
            // 绝不外抛：这里是 SecurityException（比如 ROM 收回了 ACCESS_SHORTCUTS）或
            // 服务端异常。跑在后台扫描线程上，放出去就是 SystemUI 崩溃。
            Load(emptyMap(), t.javaClass.simpleName + ": " + t.message)
        }

        val failure = result.failure
        val note = if (failure == null) {
            "catalog(" + result.items.size + ")"
        } else {
            "catalog_failed($failure)"
        }
        if (failure != null) Log.w(TAG, "快捷方式目录不可用：$failure")
        return Catalog(result.items, failure, note)
    }

    /** 快捷方式的显示名：短标签 → 长标签 → id。 */
    fun labelOf(si: ShortcutInfo): String =
        si.shortLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: si.longLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: si.id

    /** 快捷方式自己发布的图标。取不到就返回 null（上层退回应用图标）。 */
    fun iconOf(la: LauncherApps?, si: ShortcutInfo, density: Int): Drawable? = try {
        la?.getShortcutIconDrawable(si, density)
    } catch (t: Throwable) {
        Log.w(TAG, "取快捷方式图标失败：${si.`package`}/$si.id " + t.javaClass.simpleName)
        null
    }

    // ── 启动 ──────────────────────────────────────────────────────────

    /** 走通了哪一条路。 */
    private const val LEVEL_SHORTCUT = 4

    /** 快捷方式启动了，但小窗选项没被接受（多半是全屏）。 */
    private const val LEVEL_SHORTCUT_PLAIN = 5

    /** 链接项（`link:<uri>`）带 MIUI 私有小窗选项启动成功。见 [FanLinks.launch]。 */
    const val LEVEL_LINK_FREEFORM = 6

    /** 链接项启动了，但只有公开 API 的小窗选项（MIUI 私有那档不可用）。 */
    const val LEVEL_LINK_PUBLIC = 7

    /** 链接项启动了，但没有任何小窗选项（多半是全屏）。 */
    const val LEVEL_LINK_PLAIN = 8

    /** **活动项**（用户填的「包名/活动全名」）带小窗选项启动成功。见 [launchActivity]。 */
    const val LEVEL_ACTIVITY_FREEFORM = 9

    /** 活动项启动了，但只有公开 API 的小窗选项（MIUI 私有那档不可用）。 */
    const val LEVEL_ACTIVITY_PUBLIC = 10

    /** 活动项启动了，但没有任何小窗选项（多半是全屏）。 */
    const val LEVEL_ACTIVITY_PLAIN = 11

    /**
     * **活动项通过 root 强制启动**（普通路径被 `exported="false"` 拦住后的兜底）。
     * 见 [ActivityRootLauncher]。
     *
     * ⚠️ 这一级与 1–11 级有**语义上的不同**：前面每一级都是**同步**的
     * （调用返回时已经知道成没成），而 root 路是**异步**的 ——
     * `su` 不能在主线程等，所以 [ActivityRootLauncher.launch] 返回时只知道
     * 「请求发出去了」，真正的成败要等日志（那是刻意的，见那个类的注释）。
     *
     * 因此这一级**不参与** 9/10/11 的降级链，它是最后一步兜底：
     * 前三级都被 `SecurityException` 挡住时才走到这里。
     */
    const val LEVEL_ACTIVITY_ROOT = 12

    /**
     * 直连映射（[SHORTCUT_DIRECT_LAUNCH]）命中后的启动。编号 13 在活动链之后，
     * 但**执行顺序在 4/5 级之前**（它是快捷方式项的第一尝试）—— 编号不动既有值，
     * 只新增；日志里「直连映射命中」那行说明它发生了。
     */
    const val LEVEL_SHORTCUT_DIRECT = 13

    /** 一条都没走通。 */
    const val LEVEL_FAILED = 0

    /**
     * 级别编号 → 一句人话，供日志直接用。
     *
     * ⚠️ **这里的编号刻意不与 [SystemUiEnv.launchInSmallWindow] 的 1/2/3 重叠。**
     * 上一版让它们共用同一套编号（注释里还写着「方便日志对齐」），后果是同一条日志里
     * 「走通第 3 级」有两种相反的含义：在这边是「没带小窗选项地启动了快捷方式」，
     * 在小窗那边是「公开 API 的小窗启动成功」—— 而后者才是小窗/全屏的分水岭。
     * 读日志的人分不清的东西等于没记，所以这里从 4 开始编号，并把含义一起打出来。
     *
     * 4/5 = 快捷方式（[FanShortcuts.launch]），6/7/8 = 链接（[FanLinks.launch]），
     * 9/10/11 = 活动项（[launchActivity]，用户填的「包名/活动全名」），
     * 12 = 活动项的 root 兜底（[ActivityRootLauncher]，**异步**，见该常量注释）。
     * 编号常量与这份文案**都只在这个文件里**：两张表各写一份的话，哪天只改了一边，
     * 日志里就会出现「走通第 7 级：应用小窗启动第 7 级」这种自相矛盾的句子。
     */
    fun describeLevel(level: Int): String = when (level) {
        LEVEL_SHORTCUT -> "快捷方式带小窗选项启动"
        LEVEL_SHORTCUT_PLAIN -> "快捷方式启动了但小窗选项没被接受"
        LEVEL_LINK_FREEFORM -> "链接带 MIUI 私有小窗选项启动"
        LEVEL_LINK_PUBLIC -> "链接带公开 API 小窗选项启动"
        LEVEL_LINK_PLAIN -> "链接启动了但没有小窗选项（多半是全屏）"
        LEVEL_ACTIVITY_FREEFORM -> "活动带 MIUI 私有小窗选项启动"
        LEVEL_ACTIVITY_PUBLIC -> "活动带公开 API 小窗选项启动"
        LEVEL_ACTIVITY_PLAIN -> "活动启动了但没有小窗选项（多半是全屏）"
        LEVEL_ACTIVITY_ROOT -> "活动通过 root 强制启动（普通路径被 exported 拦住；结果见后续日志）"
        LEVEL_SHORTCUT_DIRECT -> "快捷方式直连 intent 启动（绕过 startShortcut 的 launcher-intent Task 根，同应用 Task 得以保留）"
        LEVEL_FAILED -> "失败"
        // 1/2/3 = SystemUiEnv.launchInSmallWindow（MiuiFreeFormManager → MiuiMultiWindowUtils → 公开 API）
        else -> "应用小窗启动第 $level 级"
    }

    /**
     * 快捷方式**直连映射**（Flyme 式）：`包名/shortcutId → 直连 Intent`。
     *
     * 命中的项不再走 [android.app.LauncherApps.startShortcut]（MIUI 那条通道会把
     * 新 Task 的根定成 launcher intent，同应用 singleTask 时底下的旧 Task 会被清掉），
     * 而是直接 `startActivity(直连 Intent)`（带 forceLaunchNewTask 的小窗 options）
     * —— Task 根 = 目标页本身，与 Flyme 的快捷付款同构。
     *
     * 只收录**真机验证过**的条目（`am start` 直开 + 截图确认页面内容），
     * 见 [launch] 里直连分支的完整论证。没验证过的宁可缺，错一条就是「点了没反应」。
     *
     * 所有条目统一用 **action + 包名 + extras** 形态（`ShortCutDispatchAction` 是
     * 微信的 shortcut 分发 action，不是组件名 —— 组件形态 `am start -n` 报
     * "does not exist"）。两种形态的区别只在表达，运行时等价。
     */
    private val SHORTCUT_DIRECT_LAUNCH: Map<String, Intent> = mapOf(
        // 微信「收付款」：完整付款码页面（条码 + 二维码 + 支付方式，截图实证 2026-09-24）
        "com.tencent.mm/launch_type_offline_wallet" to
            Intent("com.tencent.mm.ui.ShortCutDispatchAction")
                .setPackage("com.tencent.mm")
                .putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_offline_wallet"),
        // 微信「扫一扫」：扫码页 BaseScanUI（action 形态实测 topResumedActivity 即它）
        "com.tencent.mm/launch_type_scan_qrcode" to
            Intent("com.tencent.mm.ui.ShortCutDispatchAction")
                .setPackage("com.tencent.mm")
                .putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_scan_qrcode"),
    )

    /**
     * 启动扇形里的一项。**这里是三项（应用 / 快捷方式 / 链接 / 活动）唯一的入口。**
     *
     * 链接 → 直接交给 [FanLinks.launch]（`Intent.parseUri` + `startActivity`，
     * 它有自己的安全收口与 6/7/8 三级降级）。
     *
     * 应用 → 直接复用 [SystemUiEnv.launchInSmallWindow]（三级降级的小窗启动）。
     *
     * 快捷方式 → **先查 [SHORTCUT_DIRECT_LAUNCH] 直连映射**：命中则走活动链直启
     * （Task 根 = 目标页，绕开 MIUI startShortcut 的 launcher-intent Task 根，见该表注释）；
     * 未命中才走 `LauncherApps.startShortcut`：本 ROM 上
     * `ShortcutInfo.getIntents()` 返回 null（聚合支付那边的设备实测），所以拿不到
     * shortcut 自己的 intent，也就没法自己 `startActivity`。小窗选项由
     * [freeformOptions] 提供。
     *
     * **活动项 → 见下一节。** 它没有独立的 `Kind`，解析期与快捷方式无法区分
     * （[ShortcutEntries.parse] 是纯字符串逻辑，没有查目录的能力），
     * 所以判定落在**启动这一刻**。
     *
     * ⚠️ 顺序不能反：链接项**没有 `shortcutId`、也没有包名**，先判 `shortcutId == null`
     * 会把它当成「包名是空串的应用」交给 `launchInSmallWindow` —— 那要么抛异常、
     * 要么启动一个随机的东西。所以链接分支必须在最前面（`ShortcutEntries.Kind` 三分类
     * 是同一个顺序：先认 `link:` 前缀）。
     *
     * ## 活动项是怎么被认出来的（不加前缀也能生效的那条路）
     *
     * 用户在设置里填 `com.tencent.mm/com.tencent.mm.ui.LauncherUI`，[ShortcutEntries.parse]
     * 见到第一个 `/` 就拆，把它判成一条 `KEEP_CUSTOM` 的快捷方式项 —— 这是**对的**：
     * 裸「包名/活动名」与「包名/快捷方式 id」在字符串层面完全无法区分，而 parse 是纯字符串
     * 逻辑，不该为了猜这个去依赖设备能力。于是活动名一路作为 `shortcutId` 传到这里。
     *
     * 接下来三步：
     *
     * ```
     * ① startShortcut 成功                       → 4/5 级，它确实是条快捷方式
     * ② 失败，且异常是 ActivityNotFoundException  → 【关键】权威证据：这不是快捷方式
     *                                              （官方语义：shortcut 不存在/被禁用/
     *                                                接收活动不存在）→ 它不是快捷方式，
     *                                                那就按**活动**再试一次 [launchActivity]
     * ③ 其它异常                                 → 按原样退化
     * ```
     *
     * 第 ② 步**不需要猜活动名长什么样**，这是它比启发式可靠的地方：
     * 异常类型本身就是「这不是一条真快捷方式」的判据。所以**存量的裸活动名一个字都不用改**
     * 就能生效 —— 用户填的正是活动全名时，`startShortcut` 必然抛
     * `ActivityNotFoundException`（目录里没有一条 id 恰好等于那个活动全名的快捷方式），
     * 于是我们转去 `setClassName`，成功即命中。
     *
     * 前缀 `activity:`（[io.github.kotoriinsky.tsuzuri.core.ActivityTargets.PREFIX]）**是可选的**：
     * 它让解析期就能表态（日志更早明确、不必先失败一次），但没有它也能走通。
     *
     * @param allowRoot 活动被 `exported="false"` 拦住时，是否允许降级到 root 强制启动
     *   （第 12 级，见 [rootFallback]）。由设置里的独立开关控制，
     *   **只在活动项真正被拦住时才有意义** —— 别的路径不受它影响。
     * @return 走通的级别，0 = 全部失败（调用方负责记日志）
     */
    fun launch(hook: HookContext, context: Context, target: FanTarget, allowRoot: Boolean = false): Int {
        if (target.isLink) return FanLinks.launch(hook, context, target)

        val id = target.shortcutId
        if (id == null) return SystemUiEnv.launchInSmallWindow(hook, context, target.packageName)

        // ── 直连映射（Flyme 式）：命中即**跳过 startShortcut**，直接 startActivity 直连 Intent ──
        //
        // 为什么必须绕开 startShortcut：MIUI 的 startShortcut 通道把新 Task 的根定成
        // **launcher intent**（LauncherUI，2026-09-24 真机 dumpsys 实证：baseIntent=
        // act=MAIN cat=LAUNCHER），而微信主界面是 singleTask —— 新 Task 的 LauncherUI
        // 一落地，旧 Task 里的同名 Activity 被系统清掉、旧 Task 移除，**底下的微信留不住**，
        // 「主界面全屏 + 付款码小窗」的并行不成立。Flyme 没有这个坑，因为它的快捷付款
        // 直接 startActivity 到目标页，Task 根 = 目标页本身。
        //
        // 为什么是硬编码表而不是通用机制：Android 的 shortcut 规范**刻意**不给调用方
        // 真实 intent（`ShortcutInfo.getIntents()` 只回给属主，连桌面在 API 25+ 也被
        // 赶到 startShortcut —— 防 intent 里的敏感 extras 泄漏），实测本 ROM
        // 亦然。所以「全部快捷方式通用直连」没有规范内通道，只能收录**真机验证过**的
        // 条目。其余场景不需要进表 —— 支付宝系走链接项（URI）、
        // 微信扫一扫也可走 link:weixin://scanqrcode、
        // 任意带 extras 的页面用活动项，用户配置层面都能表达。
        SHORTCUT_DIRECT_LAUNCH["${target.packageName}/$id"]?.let { direct ->
            hook.log(
                Log.INFO,
                "快捷方式直连映射命中：$id → ${direct.action}" +
                    "（绕过 startShortcut 的 launcher-intent Task 根，保住底下同应用 Task）",
            )
            val opts = freeformOptions(context, target.packageName)
                ?.also { SystemUiEnv.forceLaunchNewTask(hook, it) }
                // 摆放追随扇形小窗设置（同一份规则，含 freeformScale 补偿）——
                // 否则直连弹出的小窗尺寸与应用项入口不一致
                ?.also { SystemUiEnv.applyFanPlacement(hook, context, it) }
            try {
                // Intent(direct) 拷贝：startActivity 会污染原 intent（见 tryStartActivity 的注释）
                context.startActivity(
                    Intent(direct).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    opts?.toBundle(),
                )
                return LEVEL_SHORTCUT_DIRECT
            } catch (t: Throwable) {
                hook.log(Log.WARN, "快捷方式直连启动失败，退回 startShortcut 链：${target.key}", t)
            }
        }

        val la = try {
            context.getSystemService(LauncherApps::class.java)
        } catch (t: Throwable) {
            null
        }

        val opts = freeformOptions(context, target.packageName)
            ?.also { SystemUiEnv.forceLaunchNewTask(hook, it) }
        var notAShortcut = false

        if (la != null) {
            try {
                la.startShortcut(
                    target.packageName,
                    id,
                    null,
                    opts?.toBundle(),
                    Process.myUserHandle(),
                )
                return LEVEL_SHORTCUT
            } catch (t: Throwable) {
                // 【关键分岔】ActivityNotFoundException 不是「启动失败」，是
                // **「这不是一条快捷方式」**（官方语义：shortcut 不存在 / 被禁用 /
                // 接收活动不存在）。它是判活动项唯一的权威证据，所以必须与其它异常分开 ——
                // 合成一句「启动失败」就等于把这条证据丢掉，那就只能回去猜活动名长什么样。
                if (t is ActivityNotFoundException) {
                    notAShortcut = true
                } else {
                    hook.log(Log.WARN, "带小窗选项启动快捷方式失败，退化为普通方式：${target.key}", t)
                }
            }
            if (!notAShortcut) {
                try {
                    // 不带 options：ROM 忽略了 options 或 MIUI 的私有入口不可用时，
                    // 至少让快捷方式能用（此时多半以全屏起来）
                    la.startShortcut(target.packageName, id, null, null, Process.myUserHandle())
                    return LEVEL_SHORTCUT_PLAIN
                } catch (t: Throwable) {
                    if (t is ActivityNotFoundException) {
                        notAShortcut = true
                    } else {
                        hook.log(Log.WARN, "启动快捷方式失败：${target.key}", t)
                    }
                }
            }
        }

        // ── ② 判定：它是不是一个活动？ ────────────────────────────────────
        //
        // 两条入口，都要求「目录里根本没有这条 id」这个证据（notAShortcut）：
        //   a) 带 `activity:` 前缀 —— 用户在解析期就显式声明了，意图明确；
        //   b) 裸形式，但 id 长得像个活动全名（[ActivityTargets.looksLikeActivity]）。
        //
        // 加了 (b) 的重试是这次修复的实质：存量裸活动名因此**零改动生效**。
        // 它是一次启发式，但**代价有界**：判错的后果只是白跑一次启动（`setClassName`
        // 失败），随后仍然落到下面「退化为启动应用本身」这一级 —— 最终行为与从前相同，
        // 只是多了一行日志。而判对的收益是用户想要的那个页面**真的打开了**。
        //
        // ⚠️ **(b) 分支的「判据」与「解析对象」是两个不同的串，这不是笔误**：
        //
        //   - **判据用 `id`**：[looksLikeActivity] 问的是「这串东西像不像活动名」，
        //     而 `id` 正是被拆掉包名之后的那一段（`com.foo.Bar` 形态）。
        //   - **解析必须用 `target.key`**：[ActivityTargets.parse] 要从
        //     `包名/活动名` 里**取出包名**，而 `id` 里没有包名 —— 传 `id` 进去
        //     会因「缺少 `/`」被拒，用户看到的是
        //     「活动项 ... 格式不合法（缺少 `/`，应写成「包名/活动全名」）」，
        //     而他的配置里**明明有那个 `/`**。这句误导性提示是真机日志里抓出来的
        //     （2026-09-18，配置项 `com.miui.voiceassistProxy/…ScreenRecognitionActivity`）：
        //     报出的原因指向一个用户没犯的错，照着它改只会把配置改坏。
        val activity = when {
            ActivityTargets.isActivity(target.key) ->
                ActivityTargets.parse(target.key)

            // 判据用 id（像不像活动名），解析用 key（它才有包名）—— 见上面的说明
            notAShortcut && ActivityTargets.looksLikeActivity(id) ->
                ActivityTargets.parse(target.key)

            else -> null
        }

        if (activity is ActivityTargets.Result.Ok) {
            val level = launchActivity(hook, context, activity.packageName, activity.activityName, allowRoot)
            if (level != LEVEL_FAILED) return level
            // 活动也起不来：**不再往下退化**。用户填的是一个具体页面，
            // 「打开应用首页」不是他要的东西 —— 那正是这次要修的 bug 本身。
            // 直接返回失败，让调用方记下完整原因（[launchActivity] 已经打了日志）。
            hook.log(
                Log.WARN,
                "活动项 \"${target.key}\" 启动失败，不再退化为「启动应用本身」" +
                    "（打开首页不是用户要的那个页面）。请核对活动全名是否写对、" +
                    "或者该活动是否需要参数",
            )
            // ⚠️ 必须给用户一个提示。**这条正是用户最初报告的场景**
            // （「快捷手势呼出 activity 只能呼出 app」）：以前这里的表现是
            // 「打开了应用首页」或「什么都没发生」，两者都让人以为功能坏了。
            // 现在明确说「开不了这个页面」——比静默强得多，而且指向了下一步。
            //
            // 与 [launchActivity] 内部可能已经弹过的 toast 不冲突：都走
            // [ActivityRootLauncher.notice]，有 3 秒限流，一次点击最多一条。
            ActivityRootLauncher.notice(
                hook,
                context,
                "打不开这个页面（详见模块日志）—— 可能活动名写错了，或该页面不允许被外部打开",
            )
            return LEVEL_FAILED
        }

        // 带前缀却没通过校验：说清是哪一条不合法（用户的输入能改得回来）
        if (activity is ActivityTargets.Result.Rejected) {
            hook.log(
                Log.WARN,
                "活动项 \"${target.key}\" 格式不合法（" + describeRejection(activity.reason) +
                    "），且它不是一条可用快捷方式，退化为启动应用本身",
            )
            // 格式错误是**用户改得回来的**那一类（改配置就行），所以 toast 直接指向改法。
            // 这与上一档的「开不了」不同：那是环境不允许，这是输入有误。
            ActivityRootLauncher.notice(hook, context, "活动项格式不对：" + describeRejection(activity.reason))
        }

        // 兜底：按应用启动。用户点的是「微信的扫一扫」却打开了微信，
        // 不算正确，但比「什么都不发生」好，而且日志里能看见走了哪一级。
        hook.log(Log.WARN, "快捷方式不可用，退化为启动应用本身：${target.packageName}")
        val fallback = SystemUiEnv.launchInSmallWindow(hook, context, target.packageName)
        // 连「打开这个应用」都没成 —— 这是最彻底的一档失败（应用被卸载 / 包名写错）。
        // 用户面前是「点了完全没反应」，所以必须说一句。
        // 成功的那一档**不弹**：打开了应用虽然不是你点的那一项，但有可见反馈，
        // 再弹一条「其实我打开的是应用本身」只会让人困惑（日志里已经有了）。
        if (fallback == LEVEL_FAILED) {
            ActivityRootLauncher.notice(hook, context, "启动失败：找不到「${target.packageName}」")
        }
        return fallback
    }

    /**
     * 按**活动**启动（`setClassName` 到具体页面）。**只在 [launch] 判定「这不是快捷方式」后调。**
     *
     * 三级降级，与链接那条路（[FanLinks.launch] 的 6/7/8）同构：MIUI 私有小窗选项 →
     * 公开 API 小窗选项 → 不带选项。**每级都用 `new Intent()` 重新构造**：
     * 一个 `Intent` 被失败的 `startActivity` 用过之后，不能假设它还能干净地重用
     * （且 `startActivity` 会往里塞 `FLAG_ACTIVITY_*`）—— 重新构造的成本可以忽略。
     *
     * `FLAG_ACTIVITY_NEW_TASK` **必须加**：SystemUI 启动别的活动时没有 task 归属，
     * 不加会直接抛 `AndroidRuntimeException`（「Calling startActivity() from outside of
     * an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag」）——
     * 而那个异常会被当成「活动不存在」的误导信号。
     *
     * @return 走通的级别（9/10/11），0 = 三级都没走通且**不该**降级到 root
     */
    private fun launchActivity(
        hook: HookContext,
        context: Context,
        pkg: String,
        activity: String,
        allowRoot: Boolean,
    ): Int {
        // 第 9 级：MIUI 私有小窗选项
        val miui = miuiOptions(context, pkg)?.also { SystemUiEnv.forceLaunchNewTask(hook, it) }
        if (miui != null) {
            val r = tryStartActivity(hook, context, pkg, activity, miui, LEVEL_ACTIVITY_FREEFORM)
            if (r is Attempt.Ok) return r.level
            if (r is Attempt.Blocked) {
                // 被 exported 拦住：换小窗选项也照样被拦（拦的是调用方 uid，与 options 无关），
                // 所以不必再试 10/11 级，直接降级到 root（若开关允许）
                return rootFallback(hook, context, pkg, activity, allowRoot)
            }
        }
        // 第 10 级：公开 API 的小窗选项
        val pub = publicOptions(context)?.also { SystemUiEnv.forceLaunchNewTask(hook, it) }
        if (pub != null) {
            val r = tryStartActivity(hook, context, pkg, activity, pub, LEVEL_ACTIVITY_PUBLIC)
            if (r is Attempt.Ok) return r.level
            if (r is Attempt.Blocked) return rootFallback(hook, context, pkg, activity, allowRoot)
        }
        // 第 11 级：不带任何选项（多半以全屏起来，但至少打开了那个页面）
        val r = tryStartActivity(hook, context, pkg, activity, null, LEVEL_ACTIVITY_PLAIN)
        if (r is Attempt.Ok) return r.level
        if (r is Attempt.Blocked) return rootFallback(hook, context, pkg, activity, allowRoot)
        return LEVEL_FAILED
    }

    /**
     * 活动被 `android:exported="false"` 拦住时的兜底：**以 root 身份启动**（第 12 级）。
     *
     * ## 为什么只有 [Attempt.Blocked] 才走到这里
     *
     * 「被 exported 拦住」是 root 路**唯一**能解决的情况（root 绕的就是这个归属检查）。
     * 而另外两种失败 root **帮不上忙**：
     *
     *  - `ActivityNotFoundException`（活动不存在）：包没装 / 类名拼错 / 应用改名 ——
     *    换成 root 执行同样的组件名，结果一样是「找不到」。白起一个 `su` 进程。
     *  - 其它异常：原因不明，用 root 去撞一个不理解的失败只会把线索搅乱。
     *
     * 所以这一档的判据是**异常类型**，与 [launch] 里用 `ActivityNotFoundException`
     * 判「这不是快捷方式」是同一种纪律：拿确定的证据分岔，不猜。
     *
     * ## 它是异步的，所以「走通」的含义与前面几级不同
     *
     * [ActivityRootLauncher.launch] 立刻返回（`su` 不能在主线程等），
     * 返回 true 只代表「请求发出去了」。所以这里返回 [LEVEL_ACTIVITY_ROOT]
     * 的意思是「**已交给 root 路**」，最终成败在日志里 ——
     * 这也是为什么第 12 级的文案写的是「已发出」而不是「启动成功」。
     */
    private fun rootFallback(
        hook: HookContext,
        context: Context,
        pkg: String,
        activity: String,
        allowRoot: Boolean,
    ): Int {
        if (!allowRoot) {
            // 开关关着：**必须**留下这条日志 + toast。否则用户看到的是「点了没反应」，
            // 而他其实只需要去设置里打开那个开关 —— 不说的话这个开关等于不存在。
            // 这一档尤其值得弹 toast：它是「一次性告知就能永久解决」的那类问题，
            // 而用户从日志里读不到它（HyperOS 关了 logcat，模块日志要他主动去翻）。
            hook.log(
                Log.WARN,
                "活动 \"$pkg/$activity\" 被 exported=\"false\" 拦住，普通路径开不了。" +
                    "root 强制启动的开关是关的，所以到此为止 —— " +
                    "要开这类页面请在设置里打开「用 root 强制启动活动」",
            )
            // 这一条 toast 复用 [ActivityRootLauncher.toast]：它是本特性里
            // 唯一「给用户看的出口」（限流、切主线程、失败吞掉都在那边做了）。
            // 不另起一个提示类 —— 多一处文案源就是多一处会漂移的地方。
            ActivityRootLauncher.notice(hook, context, "需要 root：请在设置里打开「用 root 强制启动活动」")
            return LEVEL_FAILED
        }
        val sent = ActivityRootLauncher.launch(hook, context, pkg, activity)
        return if (sent) LEVEL_ACTIVITY_ROOT else LEVEL_FAILED
    }

    /**
     * 一次 `startActivity` 尝试的结果。
     *
     * 比上一版多带出「失败是不是被 `exported` 拦的」这个信息 —— 那是
     * [rootFallback] 唯一关心的判据（见那里的注释）。做成类型而不是
     * 「返回 Int + 出参布尔」，是为了让编译器逼着调用方处理三种情况。
     */
    private sealed interface Attempt {
        /** 启动了。[level] 是走通的那一级。 */
        data class Ok(val level: Int) : Attempt

        /**
         * 被 `SecurityException` 拦住 —— manifest 里没有 `android:exported="true"`。
         * **这是 root 路唯一能解决的情况。**
         */
        data object Blocked : Attempt

        /** 其它失败（组件不存在、原因不明）。root 帮不上忙。 */
        data object Failed : Attempt
    }

    /**
     * 试一级：`setClassName` 到具体活动并启动。**每次都重新构造 Intent**（见 [launchActivity]）。
     *
     * @return [Attempt.Ok] 走通 / [Attempt.Blocked] 被 exported 拦 / [Attempt.Failed] 其它
     */
    private fun tryStartActivity(
        hook: HookContext,
        context: Context,
        pkg: String,
        activity: String,
        options: ActivityOptions?,
        level: Int,
    ): Attempt = try {
        val intent = Intent()
            .setClassName(pkg, activity)
            // SystemUI 没有 task 归属，不加这个标志会抛 AndroidRuntimeException
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (options == null) {
            context.startActivity(intent)
        } else {
            context.startActivity(intent, options.toBundle())
        }
        Attempt.Ok(level)
    } catch (t: Throwable) {
        logActivityFailure(hook, t, pkg, activity, level)
        if (isExportedBlocked(t)) Attempt.Blocked else Attempt.Failed
    }

    /**
     * 这个异常是不是「组件存在但没导出」。
     *
     * ## 为什么不能直接判 `t is SecurityException`
     *
     * 真机实测（2026-09-18，小爱识屏活动）显示：**HyperOS 从 SystemUI 启动一个
     * `exported=false` 的活动时，不抛 `SecurityException`** —— 它把启动**静默吞掉**：
     *
     * ```
     * am start  → "Starting: Intent {...}" 打印了，退出码 0，无异常
     * dumpsys   → 顶层纹丝不动
     * ```
     *
     * 所以「被拦住」这件事在 SystemUI 这一侧**拿不到异常证据**（这也是当初
     * 用户报告「点了没反应」的直接原因）。那么判据只能退一步：
     *
     *  - `SecurityException`（或它的子类，如 `SecurityException` 包装的
     *    `PermissionDenial`）：**确定**被拦住；
     *  - 其它异常：走到 [Attempt.Failed]，**不**降级到 root ——
     *    因为无法确认 root 能不能帮上忙，而白起 `su` 的代价是 13 秒的线程占用。
     *
     * ⚠️ 这个取舍的后果要诚实写清：在这一 ROM 上，「静默被吞」的情况**不会**走到
     * root 路。它反而会被 [Attempt.Ok] 处理（因为没异常）→ 用户看到「活动启动了」
     * 但界面没变。这是**已知的边界**，也是为什么 root 路的开关值得默认打开：
     * 让用户能主动选择「我就是要 root 起」，而不必依赖异常证据。
     */
    private fun isExportedBlocked(t: Throwable): Boolean = t is SecurityException

    /**
     * 活动启动失败的日志。**异常类型必须分开写**（与 [FanLinks.logStartFailure] 同一条纪律）：
     * 它们的处置完全不同 —— 用户要采取的下一步动作不一样。
     *
     * 这三条对应「活动项为什么点了没反应」的全部可能，也是这次修复里用户唯一能看到的东西
     * （HyperOS 关了 `android.util.Log`，所以这些走 `hook.log` 进 LSPosed 模块日志）。
     */
    private fun logActivityFailure(
        hook: HookContext,
        t: Throwable,
        pkg: String,
        activity: String,
        level: Int,
    ) {
        val reason = when (t) {
            // 包没装 / 活动全名拼错 / 活动改名了。**最常见**，所以要给出「怎么改」
            is ActivityNotFoundException ->
                "没有这个活动（核对活动全名是否逐字写对；应用可能升级后改了类名）"

            // 活动存在但没导出。链接那条路同样会有这一档（见 FanLinks），
            // 处置是「换个活动」或「接受它不能被外部启动」——不是我们写错了
            is SecurityException ->
                "该活动不允许被外部启动（manifest 里没有 android:exported=\"true\"）"

            else -> t.javaClass.simpleName + ": " + t.message
        }
        hook.log(
            Log.WARN,
            "第 $level 级（" + describeLevel(level) + "）启动活动失败：" +
                "$pkg/$activity —— $reason",
            t,
        )
    }

    /**
     * 校验失败原因 → 一句人话。**写在 `hook` 侧**是因为 [ActivityTargets.Rejection]
     * 只有枚举（`core` 不许依赖 `android.*` 但不影响它，纯粹是文案归属：
     * 这句话只会出现在日志里，界面上另有自己的提示）。
     *
     * 每种原因都要能单独说清「往哪个方向改」—— 合成一句「格式不对」等于把「为什么」藏起来，
     * 而用户照着改的那一步正是这次修复要保住的东西。
     */
    private fun describeRejection(reason: ActivityTargets.Rejection): String = when (reason) {
        ActivityTargets.Rejection.EMPTY -> "活动名是空的"
        ActivityTargets.Rejection.NO_SEPARATOR -> "缺少 `/`，应写成「包名/活动全名」"
        ActivityTargets.Rejection.EMPTY_PART -> "`/` 前面或后面是空的"
        ActivityTargets.Rejection.BAD_PACKAGE -> "包名不合法"
        ActivityTargets.Rejection.BAD_ACTIVITY -> "活动名不合法（要写**带包的全名**，如 com.foo.Bar）"
        ActivityTargets.Rejection.EXTRA_SEPARATOR -> "活动名里多了一个 `/`"
        ActivityTargets.Rejection.TOO_LONG -> "太长了"
    }

    /**
     * MIUI 私有的小窗 `ActivityOptions`（**只试私有的那两个类**，拿不到返回 null）。
     *
     * ⚠️ 这段反射是**从 [SystemUiEnv.launchInSmallWindow] 的第 1/2 级抄来的**：
     * 那两个助手（`miuiOptions` / `publicOptions`）是 private，而 `startShortcut`
     * 需要一个 `Bundle`（不是 Intent），没法复用它那句 `startActivity`。
     * 抄一份的代价是两份反射会漂移 —— 如果哪天 `SystemUiEnv` 把「算 options」暴露出来，
     * 这里应该改成调它。
     *
     * 参数语义与那边一致：第 3 个 = noCheck（**必须 true**，MIUI 对 SystemUI 调用方
     * 有一条硬编码小名单闸门，见 SystemUiEnv 的注释）、第 4 个 = isMiniFreeformMode。
     *
     * 为什么拆成公开的一档（调用方是自己决定降级顺序的 [FanLinks]）：
     * [FanLinks.launch] 要**把「只有公开 API 那档」当成独立的一级记进日志** ——
     * 合成一句「用了小窗选项」就看不出 MIUI 私有入口到底通没通。
     */
    fun miuiOptions(context: Context, pkg: String): ActivityOptions? {
        for (className in MIUI_OPTION_CLASSES) {
            val opts = try {
                val cls = Class.forName(className, false, context.classLoader)
                val m = cls.getMethod(
                    "getActivityOptions",
                    Context::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                m.invoke(null, context, pkg, true, false) as? ActivityOptions
            } catch (t: Throwable) {
                null
            }
            if (opts != null) return opts
        }
        return null
    }

    /** 一档到底的 options（私有 → 公开）。快捷方式那条路用它，行为与从前逐字一致。 */
    private fun freeformOptions(context: Context, pkg: String): ActivityOptions? =
        miuiOptions(context, pkg) ?: publicOptions(context)

    /**
     * 公开 API 的兜底，逐条对应 `SystemUiEnv.publicOptions` 的取值。
     *
     * [FanLinks.launch] 直接把它当第 7 级用（那一级要能在日志里单独认出来）。
     */
    fun publicOptions(context: Context, withBounds: Boolean = true): ActivityOptions? = try {
        val m = context.resources.displayMetrics
        val side = (minOf(m.widthPixels, m.heightPixels) * 0.7f).toInt()
        val left = (m.widthPixels - side) / 2
        val top = (m.heightPixels - side) / 3
        val opts = ActivityOptions.makeBasic()
        // 这两个方法是 @UnsupportedAppUsage，公开 SDK 里没有，只能反射
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, WINDOWING_MODE_FREEFORM)
        }
        if (withBounds) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setLaunchBounds", Rect::class.java)
                    .invoke(opts, Rect(left, top, left + side, top + side))
            }
        }
        opts
    } catch (t: Throwable) {
        null
    }

    /** 与 `SystemUiEnv.WINDOWING_MODE_FREEFORM` 同一个值（`am start --windowingMode 5` 实测）。 */
    private const val WINDOWING_MODE_FREEFORM = 5

    private val MIUI_OPTION_CLASSES = arrayOf(
        "miui.app.MiuiFreeFormManager",
        "android.util.MiuiMultiWindowUtils",
    )
}
