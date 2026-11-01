package io.github.kotoriinsky.tsuzuri.hook.feature.texttoolbar

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.HookRegistry
import io.github.kotoriinsky.tsuzuri.hook.InstallReport

/**
 * 选中文字后的悬浮工具栏：去掉「问小爱」「AI 写作」（C15）——**在 system_server 里改一个标志**。
 *
 * ## 为什么不在 App 里改
 *
 * 这条栏是 MiuixEditor 画的，框架把它加载进**每个**宿主 App 的进程（真机上第三方浏览器 Via 里就有）。
 * 模块是 `staticScope=true`，作用域只能是 `scope.list` 里的固定包，注入不了任意 App
 * —— 2026-10-26 第一版按「用户勾选的应用」做，因此不成立，已 revert。
 *
 * ## 为什么改这个标志就够
 *
 * 反编译 MiuixEditor（2026-10-26 / 10-27）：两项各有各的门，**一个开关管不住两项**。
 *
 *  - 「问小爱」（`AskHyperXiaoai`）：`isSupportAIEditorWakeUp()` 为 false 就不加。它读小爱
 *    （`com.miui.voiceassist`）清单里的 `xiaoai.supportAIEditorWakeUp && is_super_ai`。
 *  - 「AI 写作」（`AiRecognition`）：同一个函数**只决定菜单项排第几位**（0 / 17），照样会加
 *    —— 第一版只改了上面那个键，输入框里「AI 写作」一直还在（用户 2026-10-27 报告）。
 *    真正挡住它的是设备黑名单：`LLMAIWriting`（Settings.Global，真机为空）不为 1 时，
 *    去读便签（`com.miui.notes`）清单里 `ai_text_widget_device_blacklist` 指向的字符串数组，
 *    看 `Build.DEVICE` 在不在里面；**读的过程中任何异常都按「在黑名单里」处理**。
 *
 * 所以在 Binder 入口 `ComputerEngine.getApplicationInfo` 返回**拷贝**：
 *  - 小爱的拷贝里 `xiaoai.supportAIEditorWakeUp` 改成 false；
 *  - 便签的拷贝里去掉两个黑名单键 —— `getInt` 得 0，`getStringArray(0)` 抛异常，按黑名单处理。
 *
 * 只动这三个键：反编译库里读它们的只有 MiuixEditor 这两个类（2026-10-27 全库 grep）；
 * `is_super_ai` 别处可能在用，不碰。小爱、便签自己来查时原样返回。
 *
 * ## 热路径
 *
 * `getApplicationInfo` 在 system_server 里极频繁：第一步只比包名（字符串相等），不是小爱直接放行，
 * 不读配置、不打日志。出错一律按原结果返回 —— system_server 里逃出去的异常是整机重启。
 *
 * 客户端会缓存 `ApplicationInfo`：开关切换后，已经打开的 App 要重新打开才看得到变化。
 */
internal object TextToolbarAiOff : Feature(Catalog.require(Catalog.TEXT_TOOLBAR)) {

    private const val VOICE_ASSIST = "com.miui.voiceassist"
    private const val FLAG = "xiaoai.supportAIEditorWakeUp"
    private const val NOTES = "com.miui.notes"
    private val NOTES_BLACKLIST_KEYS = arrayOf("ai_text_widget_device_blacklist", "ai_text_widget_global_device_blacklist")
    private const val COMPUTER = "com.android.server.pm.ComputerEngine"

    override fun supportsProcess(processName: String, isSystemServer: Boolean) = isSystemServer

    override fun install(ctx: HookContext): InstallReport {
        val m = runCatching {
            ctx.classLoader.loadClass(COMPUTER).getDeclaredMethod(
                "getApplicationInfo", String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
        }.getOrNull() ?: return InstallReport(0, missing = listOf("$COMPUTER#getApplicationInfo(String,long,int)"))

        track(ctx.module.hook(m).intercept { chain ->
            val result = chain.proceed()
            val pkg = chain.args[0]
            if (pkg != VOICE_ASSIST && pkg != NOTES) return@intercept result
            try {
                val ai = result as? ApplicationInfo
                (if (pkg == VOICE_ASSIST) patched(ai) else patchedNotes(ai)) ?: result
            } catch (_: Throwable) {
                result
            }
        })
        ctx.log(Log.INFO, "选字工具栏 AI 项关闭：hooked=$installedHooks（$COMPUTER#getApplicationInfo）")
        return InstallReport(installedHooks)
    }

    /** 要改就返回改过的拷贝；`null` = 不改。 */
    private fun patched(ai: ApplicationInfo?): ApplicationInfo? {
        val meta = ai?.metaData ?: return null
        if (!meta.getBoolean(FLAG, false)) return null
        // 小爱自己查自己：不改（它的行为与本特性无关）
        if (Binder.getCallingUid() == ai.uid) return null
        if (!HookRegistry.prefs().isEnabled(key)) return null
        // 拷贝：返回的对象与包管理里缓存的 metaData 可能是同一个 Bundle，原地改会污染所有调用方
        return ApplicationInfo(ai).apply { metaData = Bundle(meta).apply { putBoolean(FLAG, false) } }
    }

    /** 便签：去掉 AI 写作的设备黑名单键（见类注释）。`null` = 不改。 */
    private fun patchedNotes(ai: ApplicationInfo?): ApplicationInfo? {
        val meta = ai?.metaData ?: return null
        if (NOTES_BLACKLIST_KEYS.none { meta.containsKey(it) }) return null
        if (Binder.getCallingUid() == ai.uid) return null
        if (!HookRegistry.prefs().isEnabled(key)) return null
        return ApplicationInfo(ai).apply {
            metaData = Bundle(meta).apply { NOTES_BLACKLIST_KEYS.forEach { remove(it) } }
        }
    }
}
