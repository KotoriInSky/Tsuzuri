package io.github.kotoriinsky.tsuzuri.hook.feature.pawidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.libxposed.api.XposedInterface.HookHandle
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 负一屏添加链的**第二段**：点进「本机注入的应用」后，详情页列出它的小部件。
 *
 * 前情：添加链分三段 —— ① 应用列表、② 应用详情（有哪些小部件/尺寸）、③ 分配 + 绑定。
 * ① 由 [PaWidgetPickerInject] 补上；③ 本来就是通用的（provider 信息从本机查，
 * 走标准 `bindAppWidgetIdIfAllowed`）；只剩 ②：详情同样来自云端
 * （`PickerDetailService` = Retrofit `@POST("component/store/impl/tail")`），
 * 云端没有的包要么抛 `ApiException`（常量里有 32007「不在应用商店」），要么返回空列表，
 * 两种情况详情页都是空的。
 *
 * ## 注入点：`PickerDetailRepository.getMPickerDetailService()` 的返回值
 *
 * `getPickerData` 每次都经这个私有 getter 取服务对象，再调 `getPickerDetail(param, continuation)`。
 * 我们在 getter 返回后把服务对象换成一个 [Proxy]：
 *  - `param.info.appPackage` 在 [PaWidgetPickerInject.injectedPackages] 里
 *    （= 列表注入时确认云端没有的包）→ **直接返回本地合成的 `List<PickerDetailResponse>`**。
 *    Kotlin 挂起函数允许不挂起、直接返回结果，所以不发网络请求；
 *  - 其余包、以及 `toString` / `hashCode` 这类方法 → 原样转发给真实服务（返回挂起标记也照样交回）。
 *
 * 之后 PA 自己的转换协程（`getPickerData$2`）照常跑：尺寸合法性、安装状态、独立进程、
 * 编辑能力判断、点「添加」后的 `getAddTargetItemInfo` 与绑定 —— **一行都不复刻**，
 * 不会和 PA 的版本漂移。
 *
 * ## 合成数据怎么填（本轮又核对了转换协程的 fallback 导出）
 *
 *  - 用**全参主构造**（29 参），不用 `$default` 合成构造：后者的 `isPay` 默认是 **true**，
 *    会被当成付费条目。
 *  - `appVersionCode` 填本机已安装版本 → `getTargetAppInstallStatus` 判为「已安装」(1)。
 *  - `style` / `originStyle` 由 provider 尺寸换算（[STYLES]）；换算不出合法档位就不生成。
 *  - `appIcon` / 预览图给**本地 PNG 的绝对路径**：详情页用 Glide 按字符串加载
 *    （`l0.i` → `u6/c.f` → `Glide.asBitmap().load(String)`），本地路径是 Glide 支持的输入；
 *    `appIcon` 为空串时 PA 会显示一个通用占位图，所以宁可写文件也不给空。
 *
 * ## 只放行「不需要先配置」的 provider
 *
 * PA 添加时**不会弹配置页**（`AppWidgetItemInfo.needsConfigure()` 全树无调用点）。
 * 声明了 configure Activity 又没标「配置可选」的小部件，加进来多半是空白 —— 这类直接不列，
 * 列表注入那边用同一个判据（[isAddable]），避免出现「点进去什么都没有」的应用。
 *
 * ## 纪律
 *
 *  - 关态由 `HookRegistry` 保证不装 hook；代理内任何异常 → 转发给真实服务，等于没装。
 *  - 本地集合为空（列表注入没开或没注入任何包）时代理全部转发。
 */
object PaWidgetDetailInject {

    private const val REPO_CLASS =
        "com.miui.personalassistant.picker.business.detail.PickerDetailRepository"
    private const val SERVICE_CLASS =
        "com.miui.personalassistant.picker.business.detail.service.PickerDetailService"
    private const val RESPONSE_CLASS =
        "com.miui.personalassistant.picker.business.detail.bean.PickerDetailResponse"
    private const val WIDGET_CLASS =
        "com.miui.personalassistant.picker.business.detail.bean.PickerDetailResponseWidget"
    private const val TIP_CLASS =
        "com.miui.personalassistant.picker.business.detail.bean.PickerDetailTipStr"

    private const val UTIL_CLASS =
        "com.miui.personalassistant.picker.business.detail.utils.PickerDetailUtil"
    /** `g.a(ItemInfo, style)`：按档位写 spanX/spanY（含小格模式的折半），PA 自己添加时也调它。 */
    private const val SPAN_SETTER_CLASS = "com.miui.personalassistant.picker.util.g"

    private const val GETTER_NAME = "getMPickerDetailService"
    private const val CALL_NAME = "getPickerDetail"

    /** `PickerDetailFragment` 按 implType 1 / 5 走小部件分支；1 = 普通小部件。 */
    private const val IMPL_TYPE_WIDGET = 1

    /** `getTargetAppInstallStatus` 的「已安装」。 */
    private const val INSTALL_STATUS_INSTALLED = 1

    /** 主构造参数个数（`PickerDetailResponse.java:680`）；`$default` 合成构造是 31。 */
    private const val RESPONSE_CTOR_ARGS = 29
    private const val WIDGET_CTOR_ARGS = 12

    /** implUniqueCode 前缀，用来在日志 / 埋点里一眼认出是我们合成的。 */
    private const val CODE_PREFIX = "tsuzuri:"

    private const val PREVIEW_DIR = "tsuzuri_widget_preview"
    private const val PREVIEW_MAX_PX = 720

    private const val DIAG_LIMIT = 5

    /**
     * (spanX, spanY) → PA 的尺寸档位（`PickerDetailUtil.getItemInfoSizeStyle` 的反函数）。
     * 顺序 = 「放不下时向上取整」的尝试顺序：面积从小到大。
     */
    private val STYLES = listOf(
        Triple(1, 1, 9),
        Triple(2, 1, 6),
        Triple(1, 2, 5),
        Triple(4, 1, 3),
        Triple(2, 2, 1),
        Triple(2, 3, 7),
        Triple(4, 2, 2),
        Triple(4, 4, 4),
    )


    private val handles = ArrayList<HookHandle>(2)

    /**
     * 规范比例的三档（2×2 / 4×2 / 4×4，HyperOS 小部件设计规范）。
     * 详情页按原尺寸显示（预览不拉伸），**真正插入时**换到最近的这一档，靠 Android 的缩放能力适配。
     */
    private val SPEC_STYLES = listOf(Triple(2, 2, 1), Triple(4, 2, 2), Triple(4, 4, 4))

    /** implUniqueCode → 插入时用的规范档位。只记可缩放、且缩放范围容得下的。 */
    private val insertStyles = ConcurrentHashMap<String, Int>()

    @Volatile
    private var spanSetter: Method? = null

    @Volatile
    private var serviceIface: Class<*>? = null

    @Volatile
    private var responseCtor: Constructor<*>? = null

    @Volatile
    private var widgetCtor: Constructor<*>? = null

    @Volatile
    private var tipCtor: Constructor<*>? = null

    /** 真实服务 → 代理。PA 的服务对象来自 lazy 委托，通常只有一个；弱引用免得钉住旧 repository。 */
    private val proxies = WeakHashMap<Any, Any>()

    private val diag = ConcurrentHashMap<String, AtomicInteger>()

    fun install(ctx: HookContext): InstallReport {
        if (handles.isNotEmpty()) return InstallReport(handles.size, notes = listOf("already"))

        val missing = mutableListOf<String>()
        val cl = ctx.classLoader
        val repo = load(cl, REPO_CLASS)
        val iface = load(cl, SERVICE_CLASS)
        val response = load(cl, RESPONSE_CLASS)
        val widget = load(cl, WIDGET_CLASS)
        val tip = load(cl, TIP_CLASS)
        listOf(REPO_CLASS to repo, SERVICE_CLASS to iface, RESPONSE_CLASS to response, WIDGET_CLASS to widget, TIP_CLASS to tip)
            .filter { it.second == null }
            .forEach { missing += it.first }
        if (repo == null || iface == null || response == null || widget == null || tip == null || !iface.isInterface) {
            ctx.log(Log.WARN, "负一屏详情注入：PA 详情页的类对不上（$missing），跳过（详情页保持原样）")
            return InstallReport(0, missing)
        }

        val rc = response.declaredConstructors.firstOrNull { isResponseCtor(it) }
        val wc = widget.declaredConstructors.firstOrNull { isWidgetCtor(it) }
        val tc = tip.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 2 && it.parameterTypes.all { t -> t == String::class.java }
        }
        if (rc == null) missing += "${response.simpleName}.<init>($RESPONSE_CTOR_ARGS)"
        if (wc == null) missing += "${widget.simpleName}.<init>($WIDGET_CTOR_ARGS)"
        if (tc == null) missing += "${tip.simpleName}.<init>(String,String)"
        if (rc == null || wc == null || tc == null) {
            ctx.log(Log.WARN, "负一屏详情注入：构造签名对不上（$missing），跳过")
            return InstallReport(0, missing)
        }
        listOf(rc, wc, tc).forEach { runCatching { it.isAccessible = true } }
        responseCtor = rc
        widgetCtor = wc
        tipCtor = tc
        serviceIface = iface

        val getter = repo.declaredMethods.firstOrNull {
            it.name == GETTER_NAME && it.parameterTypes.isEmpty() && iface.isAssignableFrom(it.returnType)
        } ?: repo.declaredMethods.firstOrNull {
            // 名字变了时按「无参、返回 PickerDetailService」弱匹配
            it.parameterTypes.isEmpty() && it.returnType == iface
        }
        if (getter == null) {
            missing += "${repo.simpleName}.$GETTER_NAME()"
            ctx.log(Log.WARN, "负一屏详情注入：找不到 $GETTER_NAME()，跳过")
            return InstallReport(0, missing)
        }

        try {
            val h = ctx.module.hook(getter).intercept { chain ->
                val real = chain.proceed()
                try {
                    if (real != null) wrap(real, ctx) else real
                } catch (t: Throwable) {
                    note(ctx, "wrap", "包装服务对象失败：${t.javaClass.simpleName}", t)
                    real
                }
            }
            handles.add(h)
            ctx.log(Log.INFO, "hooked ${repo.simpleName}.${getter.name}()")
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "hook ${getter.name} failed", t)
            missing += "${repo.simpleName}.${getter.name}()"
        }
        hookInsertSize(ctx, missing)
        return InstallReport(handles.size, missing, notes = listOf("getter=${getter.name}", "snap=${spanSetter != null}"))
    }

    /**
     * 插入时把尺寸换成规范档位：`PickerDetailUtil.getAddTargetItemInfo(detail)` 按 `originStyle`
     * （= 原尺寸）建好 ItemInfo 后，对我们合成的条目再按 [insertStyles] 重设一次 span。
     * 详情页的预览框同样按 `originStyle`，所以预览保持原比例，不被拉伸。
     */
    private fun hookInsertSize(ctx: HookContext, missing: MutableList<String>) {
        val util = load(ctx.classLoader, UTIL_CLASS)
        val setterCls = load(ctx.classLoader, SPAN_SETTER_CLASS)
        val target = util?.declaredMethods?.firstOrNull { it.name == "getAddTargetItemInfo" && it.parameterCount == 1 }
        val setter = setterCls?.declaredMethods?.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 2 &&
                it.parameterTypes[1].name == "int" && it.parameterTypes[0].simpleName == "ItemInfo"
        }
        if (target == null || setter == null) {
            missing += "getAddTargetItemInfo / g.a(ItemInfo,int)"
            ctx.log(Log.INFO, "负一屏详情注入：插入尺寸换档不可用，按原尺寸插入")
            return
        }
        setter.isAccessible = true
        spanSetter = setter
        try {
            handles.add(
                ctx.module.hook(target).intercept { chain ->
                    val item = chain.proceed()
                    try {
                        val detail = chain.getArg(0)
                        val code = detail?.javaClass?.getMethod("getImplUniqueCode")?.invoke(detail) as? String
                        val style = code?.let { insertStyles[it] }
                        if (item != null && style != null && setter.parameterTypes[0].isInstance(item)) {
                            setter.invoke(null, item, style)
                            note(ctx, "snap", "$code 插入时换到规范档位 style=$style")
                        }
                    } catch (t: Throwable) {
                        note(ctx, "snap", "换档失败，按原尺寸插入：${t.javaClass.simpleName}", t)
                    }
                    item
                }
            )
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "hook getAddTargetItemInfo failed", t)
        }
    }

    /**
     * 原尺寸 → 插入用的规范档位：宽高比最接近的一档（同样接近取面积最近），
     * 且必须落在 provider 的缩放范围里；不可缩放、或范围容不下 → null（按原尺寸插入）。
     */
    private fun insertStyleFor(context: Context, info: AppWidgetProviderInfo, bx: Int, by: Int): Int? {
        if (SPEC_STYLES.any { it.first == bx && it.second == by }) return null
        val h = (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
        val v = (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
        if (!h && !v) return null
        val dm = context.resources.displayMetrics
        fun cellsW(px: Int): Int = cellsOf(px, dm.density, horizontal = true)
        fun cellsH(px: Int): Int = cellsOf(px, dm.density, horizontal = false)
        val minX = if (h) cellsW(info.minResizeWidth.takeIf { it > 0 } ?: info.minWidth) else bx
        val minY = if (v) cellsH(info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight) else by
        var maxX = if (h) 4 else bx
        var maxY = if (v) 4 else by
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (h && info.maxResizeWidth > 0) maxX = cellsW(info.maxResizeWidth)
            if (v && info.maxResizeHeight > 0) maxY = cellsH(info.maxResizeHeight)
        }
        val ratio = bx.toFloat() / by
        return SPEC_STYLES
            .filter { (x, y, _) -> x in minX..maxX && y in minY..maxY }
            .minWithOrNull(
                compareBy<Triple<Int, Int, Int>> { kotlin.math.abs(kotlin.math.ln((it.first.toFloat() / it.second) / ratio)) }
                    .thenBy { kotlin.math.abs(it.first * it.second - bx * by) }
            )?.third
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
        synchronized(proxies) { proxies.clear() }
        insertStyles.clear()
        spanSetter = null
    }

    /**
     * 判据：provider 能不能「绑定后直接用」。
     *
     * PA 添加时不弹配置页，所以声明了 configure 又没标「配置可选」的不放行。
     * 列表注入也用它（同一个判据，两段才一致）。
     */
    fun isAddable(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
    }

    // ── 代理 ────────────────────────────────────────────────────────────

    private fun wrap(real: Any, ctx: HookContext): Any {
        val iface = serviceIface ?: return real
        if (Proxy.isProxyClass(real.javaClass) && Proxy.getInvocationHandler(real) is Handler) return real
        synchronized(proxies) {
            proxies[real]?.let { return it }
            val p = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), Handler(real, ctx))
            proxies[real] = p
            return p
        }
    }

    private class Handler(private val real: Any, private val ctx: HookContext) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == CALL_NAME && args != null && args.isNotEmpty()) {
                val local = try {
                    localResponse(args[0], ctx)
                } catch (t: Throwable) {
                    note(ctx, "local", "合成详情失败，转发云端：${t.javaClass.simpleName}", t)
                    null
                }
                if (local != null) return local
            }
            return try {
                if (args == null) method.invoke(real) else method.invoke(real, *args)
            } catch (e: InvocationTargetException) {
                // 把真实服务抛的异常原样交回（PA 自己会 catch ApiException）
                throw e.targetException ?: e
            }
        }
    }

    /** 命中本地集合 → 合成的列表；否则 null（= 转发云端）。 */
    private fun localResponse(param: Any?, ctx: HookContext): List<Any>? {
        val local = PaWidgetPickerInject.injectedPackages
        if (local.isEmpty() || param == null) return null
        val pkg = appPackageOf(param) ?: return null
        if (!local.contains(pkg)) return null
        val context = PaWidgetPickerInject.appContext() ?: return null
        val items = buildResponses(context, pkg, ctx)
        if (items.isEmpty()) {
            note(ctx, "empty", "$pkg 没有可直接添加的 provider，转发云端")
            return null
        }
        note(ctx, "detail", "$pkg 本地合成详情 ${items.size} 条，未请求云端")
        return items
    }

    /** `PickerDetailQueryParam.getInfo().getAppPackage()`。 */
    private fun appPackageOf(param: Any): String? {
        val info = param.javaClass.getMethod("getInfo").invoke(param) ?: return null
        return info.javaClass.getMethod("getAppPackage").invoke(info) as? String
    }

    // ── 合成 ────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun buildResponses(context: Context, pkg: String, ctx: HookContext): List<Any> {
        val rc = responseCtor ?: return emptyList()
        val wc = widgetCtor ?: return emptyList()
        val tc = tipCtor ?: return emptyList()
        val awm = AppWidgetManager.getInstance(context) ?: return emptyList()
        val providers = awm.getInstalledProvidersForPackage(pkg, null).filter { isAddable(it) }
        if (providers.isEmpty()) return emptyList()

        val pm = context.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        val appName = pm.getApplicationLabel(ai).toString()
        val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val versionCode = pi?.versionCode ?: 0
        val versionName = pi?.versionName ?: ""
        val dir = File(context.cacheDir, PREVIEW_DIR).apply { mkdirs() }
        val stamp = pi?.lastUpdateTime ?: 0L
        val appIcon = runCatching {
            writePng(File(dir, "$pkg-$stamp-icon.png"), ai.loadIcon(pm))
        }.getOrNull() ?: ""

        val density = context.resources.displayMetrics.densityDpi
        val out = ArrayList<Any>()
        var sort = 0
        for (info in providers) {
            val cn = info.provider ?: continue
            val title = runCatching { info.loadLabel(pm) }.getOrNull()?.takeIf { it.isNotBlank() } ?: appName
            val preview = runCatching {
                val d = info.loadPreviewImage(context, density) ?: info.loadIcon(context, density)
                writePng(File(dir, "${cn.flattenToShortString().replace('/', '_')}-$stamp.png"), d)
            }.getOrNull() ?: appIcon
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { info.loadDescription(context)?.toString() }.getOrNull() ?: ""
            } else {
                ""
            }
            for ((x, y, style) in sizesOf(context, info)) {
                val code = "$CODE_PREFIX${cn.flattenToShortString()}:${x}x$y"
                val snap = insertStyleFor(context, info, x, y)
                if (snap != null) insertStyles[code] = snap else insertStyles.remove(code)
                val widget = wc.newInstance(
                    cn.className, title, desc, preview, preview,
                    sort++, x, y,
                    null, // editConfig
                    INSTALL_STATUS_INSTALLED,
                    false, // isIndependentProcessWidget：PA 的转换协程会按本机信息重算
                    null, // customEditOptions
                )
                out += rc.newInstance(
                    code,
                    "", // abilityCode
                    0L, // abilityVersion
                    IMPL_TYPE_WIDGET,
                    style,
                    style,
                    pkg,
                    appIcon,
                    appName,
                    versionName, // appInstalledVersionName（转换协程会覆盖）
                    versionCode,
                    "", // appDownloadUrl：已安装，不用下载
                    versionName,
                    "", "", "", // publisher / permission / privacy
                    tc.newInstance("", ""),
                    null, // supperAppInfo
                    widget,
                    null, // mamlImplInfo
                    false, // isPay —— $default 构造里默认是 true，所以必须走主构造
                    null, // priceInCent
                    false, // isBought
                    false, // isAuthorityPass（与 PA 自己的 21 参构造一致）
                    null, // drmResult
                    null, // adInfo
                    null, // groupId
                    null, // groupName
                    false, // useAppIcon
                )
            }
        }
        if (out.isEmpty()) note(ctx, "size", "$pkg 的 provider 尺寸都换算不出 PA 的档位")
        return out
    }

    /**
     * provider → 要列出的 (spanX, spanY, style)。
     *
     * 基础尺寸优先用 `targetCellWidth/Height`（Android 12+），否则按 `minWidth/minHeight`
     * 用 AOSP 的「n 格 = 70n − 30 dp」折算；放不进 PA 的档位就向上取最近的一档。
     * 不额外生成其它尺寸：预览图只有原尺寸一张，换档会被拉伸。
     */
    private fun sizesOf(context: Context, info: AppWidgetProviderInfo): List<Triple<Int, Int, Int>> {
        val dm = context.resources.displayMetrics
        fun cellsW(px: Int): Int = cellsOf(px, dm.density, horizontal = true)
        fun cellsH(px: Int): Int = cellsOf(px, dm.density, horizontal = false)

        var bx = 0
        var by = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bx = info.targetCellWidth
            by = info.targetCellHeight
        }
        if (bx <= 0 || by <= 0) {
            bx = cellsW(info.minWidth)
            by = cellsH(info.minHeight)
        }
        // 只给小部件原本的尺寸：预览图是按这个尺寸画的，换成别的档位会被 PA 的预览框拉伸。
        // PA 只认 STYLES 里的档位，原尺寸不在其中（如 3×2）时才向上取最近的一档。
        val exact = STYLES.firstOrNull { (x, y, _) -> x == bx && y == by }
        val pick = exact ?: STYLES.firstOrNull { (x, y, _) -> x >= bx && y >= by } ?: return emptyList()
        return listOf(pick)
    }

    /**
     * dp → 负一屏格数，宽高分开按阈值分档（负一屏没有 3 格，归到 2 或 4）：
     *
     * | | 1 格 | 2 格 | 4 格 |
     * | --- | --- | --- | --- |
     * | 宽 | ≤ 80dp | ≤ 200dp | 更大 |
     * | 高 | ≤ 105dp | ≤ 220dp | 更大 |
     *
     * 界线参照 Android 官方 5×4 网格估算表（宽 2 格 130dp、3 格 203dp；高 1 行 102dp、2 行 220dp），
     * 宽度 3 格以下归 2、以上归 4（国产「4×2」多写 300dp 宽，官方表会算成 5 格）。
     *
     * 不用 AOSP 老公式「n 格 = 70n − 30 dp」：130×115dp 的 2×2 会被算成 3×3 再取成 4×4。
     * 上一版高度 1 行界线 80dp 太低：UC 书架 312×100dp、Google 股票 140×90dp 被算成 2 行
     * （真机 dumpsys 实读，用户确认）。
     */
    private fun cellsOf(px: Int, density: Float, horizontal: Boolean): Int {
        if (px <= 0) return 1
        val dp = px / density
        val one = if (horizontal) 80f else 105f
        val two = if (horizontal) 200f else 220f
        return when {
            dp <= one -> 1
            dp <= two -> 2
            else -> 4
        }
    }

    /** 画成 PNG 写进 PA 自己的缓存目录，返回绝对路径。文件已存在就直接复用。 */
    private fun writePng(file: File, d: Drawable?): String? {
        if (file.isFile && file.length() > 0) return file.absolutePath
        d ?: return null
        var w = d.intrinsicWidth.takeIf { it > 0 } ?: PREVIEW_MAX_PX
        var h = d.intrinsicHeight.takeIf { it > 0 } ?: PREVIEW_MAX_PX
        val scale = minOf(1f, PREVIEW_MAX_PX.toFloat() / maxOf(w, h))
        w = maxOf(1, (w * scale).toInt())
        h = maxOf(1, (h * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bounds = d.copyBounds()
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        d.bounds = bounds
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return null
        }
        return file.absolutePath
    }

    // ── 反射小工具 ──────────────────────────────────────────────────────

    private fun load(cl: ClassLoader, name: String): Class<*>? = try {
        Class.forName(name, false, cl)
    } catch (_: Throwable) {
        null
    }

    private fun isInt(c: Class<*>) = c.name == "int"

    /** 29 参主构造：p0/p1 String、p2 long、p3..p5 int、p10 int、p20/p22/p23/p28 boolean。 */
    private fun isResponseCtor(c: Constructor<*>): Boolean {
        val t = c.parameterTypes
        return t.size == RESPONSE_CTOR_ARGS &&
            t[0] == String::class.java && t[1] == String::class.java &&
            t[2].name == "long" && isInt(t[3]) && isInt(t[4]) && isInt(t[5]) &&
            isInt(t[10]) &&
            t[20].name == "boolean" && t[22].name == "boolean" && t[23].name == "boolean" && t[28].name == "boolean"
    }

    /** 12 参：5 个 String、3 个 int、List、int、boolean、Bundle。 */
    private fun isWidgetCtor(c: Constructor<*>): Boolean {
        val t = c.parameterTypes
        return t.size == WIDGET_CTOR_ARGS &&
            (0..4).all { t[it] == String::class.java } &&
            isInt(t[5]) && isInt(t[6]) && isInt(t[7]) &&
            List::class.java.isAssignableFrom(t[8]) && isInt(t[9]) && t[10].name == "boolean"
    }

    private fun note(ctx: HookContext, tag: String, msg: String, t: Throwable? = null) {
        val n = diag.computeIfAbsent(tag) { AtomicInteger(0) }.incrementAndGet()
        if (n > DIAG_LIMIT) return
        val line = "[detail:$tag #$n] $msg"
        if (t == null) ctx.log(Log.INFO, line) else ctx.log(Log.WARN, line, t)
    }
}
