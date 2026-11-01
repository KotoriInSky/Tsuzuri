package io.github.kotoriinsky.tsuzuri.hook.feature.pawidget

import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport

/**
 * 拆掉负一屏对小部件尺寸的硬编码黑名单 —— 让 1x2 / 2x3 / 8x4 这些尺寸能加进去。
 *
 * 依据（PA 反编译产物，versionName 25.40.34.00）：
 *
 *   // PickerDetailUtil.java:485
 *   isUnSupportAddToPASize(style, spanX, spanY) =
 *         ((spanX == -1 || spanY == -1) && (style == 8 || style == 5 || style == 7))
 *      || (spanX == 1 && spanY == 2) || (spanX == 2 && spanY == 3);
 *
 *   // PickerDetailUtil.java:459
 *   isFromPAAndUnSupportSize(openSource, style, spanX, spanY) = ...
 *
 * 这两个方法返回 true 时会弹「负一屏暂不支持添加该尺寸的小部件」。让它们恒返回 false。
 *
 * 注意：Kotlin 默认参数会生成 `xxx$default` 合成方法，而 5 个调用点**全都走 $default**，
 *      所以必须按**前缀**匹配，把两个方法连同它们的 $default 一起 hook。
 *
 * 风险：极低 —— 纯判定函数，无副作用。关掉时（registry 根本不装 hook）行为与未安装模块一致。
 */
object PaWidgetAdmission : Feature(Catalog.require(Catalog.PA_WIDGET_ADMISSION)) {


    private const val UTIL =
        "com.miui.personalassistant.picker.business.detail.utils.PickerDetailUtil"

    /** 按前缀匹配，覆盖 `$default` 合成方法。 */
    private val METHOD_NAMES = listOf("isUnSupportAddToPASize", "isFromPAAndUnSupportSize")

    override fun install(ctx: HookContext): InstallReport {
        val cls = try {
            Class.forName(UTIL, false, ctx.classLoader)
        } catch (t: Throwable) {
            // 找不到 = 正常情况（PA 改版了），降级退出，不抛异常
            ctx.log(Log.WARN, "class not found: $UTIL，跳过")
            return InstallReport(0, listOf(UTIL))
        }

        val missing = mutableListOf<String>()
        var hooked = 0
        // 用局部计数做节流，避免 picker 路径上刷日志
        val calls = java.util.concurrent.atomic.AtomicInteger(0)

        for (name in METHOD_NAMES) {
            val candidates = cls.declaredMethods.filter {
                (it.name == name || it.name.startsWith(name + "$")) &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            if (candidates.isEmpty()) {
                missing.add(name)
                continue
            }
            for (m in candidates) {
                try {
                    // 不调 chain.proceed() = 短接，直接返回 false
                    track(
                        ctx.module.hook(m).intercept { _ ->
                            if (calls.incrementAndGet() <= DIAG_LIMIT) {
                                ctx.log(Log.INFO, "suppressed ${m.name}() -> false")
                            }
                            false
                        }
                    )
                    hooked++
                    ctx.log(
                        Log.INFO, "hooked ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
                    )
                } catch (t: Throwable) {
                    ctx.log(Log.WARN, "hook ${m.name} failed", t)
                    missing.add(m.name)
                }
            }
        }

        return InstallReport(hooked, missing, notes = listOf("cls=$UTIL"))
    }

    private const val DIAG_LIMIT = 5
}
