package io.github.kotoriinsky.tsuzuri.hook.feature.shareorder

import android.os.SystemClock
import android.content.ComponentName
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.ShortcutEntries
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 分享面板的应用行按用户清单置顶（C14）。
 *
 * ## 为什么 hook `AbstractResolverComparator.compare(ResolvedComponentInfo, ResolvedComponentInfo)`
 *
 * HyperOS 的分享面板是 `com.android.intentresolver`（`MiIntentResolver`）。应用行的顺序由这个比较器决定：
 * 系统「固定」的项之间按名称字母序（`mAzComparator`），其余走小米排序 `ApplicationStub.compare`
 * —— **没有任何一处读用户自定义顺序**（2026-10-25 反编译）。
 *
 * 做法：清单里的应用排在最前，彼此按清单顺序；不在清单里的两项交给原逻辑。
 * 这仍是一个全序（清单组 < 其余；组内各自有序），`Collections.sort` 不会因为契约被破坏而抛异常。
 *
 * 不动的东西：调用方指定的 `mPromoteToFirst`（例如「上次用的」直达项）照旧最先；
 * 顶部「直接分享」（联系人 / 会话）不经过这个比较器。
 *
 * 清单每次比较都现读（[HookRegistry.prefs] 已处理快照刷新），所以改完下次打开面板就生效，不用重启进程。
 */
internal object ShareOrder : Feature(Catalog.require(Catalog.SHARE_ORDER)) {

    private const val COMPARATOR_CLASS = "com.android.intentresolver.model.AbstractResolverComparator"
    private const val INFO_CLASS_SUFFIX = ".ResolvedComponentInfo"

    private val listSpec: PrefSpec.OrderedList =
        spec.prefs.filterIsInstance<PrefSpec.OrderedList>().first()

    private var nameField: Field? = null
    private var promoteField: Field? = null

    override fun install(ctx: HookContext): InstallReport {
        val cls = try {
            ctx.classLoader.loadClass(COMPARATOR_CLASS)
        } catch (t: Throwable) {
            ctx.log(Log.WARN, "分享排序：找不到 $COMPARATOR_CLASS", t)
            return InstallReport(0, missing = listOf(COMPARATOR_CLASS))
        }
        val method: Method = cls.declaredMethods.firstOrNull {
            it.name == "compare" && it.parameterTypes.size == 2 &&
                it.parameterTypes.all { p -> p.name.endsWith(INFO_CLASS_SUFFIX) }
        } ?: return InstallReport(0, missing = listOf("$COMPARATOR_CLASS#compare(ResolvedComponentInfo×2)"))

        val infoClass = method.parameterTypes[0]
        nameField = runCatching { infoClass.getDeclaredField("name").apply { isAccessible = true } }.getOrNull()
            ?: return InstallReport(0, missing = listOf("${infoClass.name}#name"))
        // 拿不到只是少一道保护（mPromoteToFirst 照常由原逻辑处理，只是清单项可能排到它前面）
        promoteField = runCatching { cls.getDeclaredField("mPromoteToFirst").apply { isAccessible = true } }.getOrNull()

        track(ctx.module.hook(method).intercept { chain ->
            // 只有「我们自己的判断」放进 try：proceed() 里抛的是原方法的异常，按 API 约定必须透传，
            // 不能吞掉再 proceed 一次。null = 交回原逻辑。
            val decided: Int? = try {
                decide(chain.args[0], chain.args[1], chain.thisObject)
            } catch (_: Throwable) {
                null
            }
            decided ?: chain.proceed()
        })
        ctx.log(Log.INFO, "分享排序已装上：${cls.simpleName}#compare，清单 ${pinnedOrder().size} 项")
        return InstallReport(installedHooks)
    }

    /** 清单决定得了的比较结果；`null` = 两项都不在清单里（或同属一个应用），交回原逻辑。 */
    private fun decide(a: Any?, b: Any?, comparator: Any?): Int? {
        val order = pinnedOrder()
        if (order.isEmpty()) return null
        val ca = nameField?.get(a) as? ComponentName ?: return null
        val cb = nameField?.get(b) as? ComponentName ?: return null
        val promote = promoteField?.get(comparator) as? ComponentName
        if (promote != null && (promote == ca || promote == cb)) return null
        val ra = order.indexOf(ca.packageName)
        val rb = order.indexOf(cb.packageName)
        return when {
            ra < 0 && rb < 0 -> null
            ra < 0 -> 1
            rb < 0 -> -1
            // 同一个应用的两个分享目标（如微信「发送给朋友」/「朋友圈」）保持原相对顺序
            ra == rb -> null
            else -> ra.compareTo(rb)
        }
    }

    /**
     * 清单里的应用（包名），按用户顺序。快捷方式（`包名/id`）、链接（`link:`）这类条目
     * 在分享面板里没有对应项，跳过 —— 包名按规范不含 `/` 与 `:`。
     */
    private fun pinnedOrder(): List<String> {
        // 一次排序约 40 个目标 ≈ 200 次 compare，每次都读配置 + 解析列表是纯浪费。
        // 比较器拿不到「排序开始」的时机，所以用短时快照：同一次排序内复用，
        // 用户改完清单后下一次打开分享面板必然已过期，不影响「改完即生效」。
        val now = SystemClock.uptimeMillis()
        val c = cached
        if (c != null && now - c.first < SNAPSHOT_MS) return c.second
        val fresh = HookRegistry.prefs().value(listSpec)
            .filter { !it.contains(ShortcutEntries.SEP) && !it.contains(':') }
        cached = now to fresh
        return fresh
    }

    @Volatile
    private var cached: Pair<Long, List<String>>? = null

    private const val SNAPSHOT_MS = 500L

    override fun uninstall() {
        cached = null
        nameField = null
        promoteField = null
        super.uninstall()
    }
}
