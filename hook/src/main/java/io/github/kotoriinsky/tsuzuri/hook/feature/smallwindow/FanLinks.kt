package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.LinkTargets
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv

/**
 * 链接项（`link:<uri>`）的启动：`Intent.parseUri` + `startActivity`，带小窗选项与三级降级。
 *
 * ## 为什么需要这条路（与快捷方式那条的区别）
 *
 * 快捷方式项要靠 `LauncherApps.startShortcut` 启动（`FanShortcuts.launch`）——它要求
 * 宿主应用**发布过**那条快捷方式。而用户想要的「微信扫一扫」在本 ROM 上是一条
 * **动态且未被固定**的快捷方式，枚举时极易看不到（取证写在 `FanShortcuts.MATCH_FLAGS`）。
 * 它的真实 scheme `weixin://scanqrcode` 却一直可用，直接 `parseUri` + `startActivity`
 * 就能起来。所以链接项走这条路，**不依赖任何应用是否发布了快捷方式**。
 *
 * 参考实现（报告里有完整取证）：Anywhere-（`com.absinthe.anywhere_`）的
 * `SchemeEditorFragment` + `pg1.a`：
 *
 * ```java
 * Intent uri = Intent.parseUri(str, 0);      // flags = 0，不是 URI_INTENT_SCHEME
 * uri.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
 * context.startActivity(uri);
 * ```
 *
 * 我们照抄它的**形态**，但在两处收紧（理由见下）：只放行隐式 intent；
 * 多一条 `publicOptions` 的中间档。
 *
 * ## ⚠️ 为什么 flags 用 `0`，而不是 brief 建议的 `URI_INTENT_SCHEME`
 *
 * 原本的建议是 `Intent.parseUri(uri, URI_INTENT_SCHEME)`（因为 `S.` 之类的
 * selector 参数只有那个 flag 才会被解析）。这里**刻意用 0**，取舍如下：
 *
 *  - `URI_INTENT_SCHEME` 的作用是**让 `intent://...#Intent;component=...;end` 这类
 *    显式/带选择器的写法生效** —— 那正是我们要拒绝的东西（见 [LinkTargets] 的「安全收口」）。
 *    用 0 时这类字符串解析出来只是一个 scheme 为 `intent` 的普通数据 URI，
 *    `startActivity` 找不到接收者，**不会**变成任意组件启动；
 *  - 于是「拒绝显式形式」这件事有**两层**保障：校验层（[LinkTargets.check] 拒绝字符串）
 *    与解析层（flags=0 让显式语义根本无法生效）。而按 brief 用 `URI_INTENT_SCHEME` 的话，
 *    第二层就没有了 —— 一旦哪天配置被手改成 `intent://…;component=com.evil/.A;end`，
 *    它就真的会被启动。**宁可拒绝一条奇怪的 URI，也不要启动它**（派工 brief 的原话）。
 *  - 代价：`S.` 之类的 selector 一律不支持。这是**有意的**能力削减，不是遗漏。
 *
 * 第三层保障在 [launch] 里：解析出来的 Intent **如果是显式的（`component` / `package`
 * 非空），直接拒绝启动**。这一层不依赖 flags 的取值，也不依赖 ROM 的 `parseUri` 实现。
 *
 * ## 一处**已知但可接受**的粗糙（写下来，免得被当成 bug 查）
 *
 * `flags = 0` 时 `parseUri` 对带 `#` 的串走的是「Intent 参数」那条老路径，因此
 * `https://example.com/a#section` 这种带**纯文本 fragment** 的链接，解析出来的 data
 * 不一定保留 `#section`（fragment 可能在解析中被吃掉）。**刻意不为此改成
 * `URI_INTENT_SCHEME`**（那会把 `intent:` / `S.` 这类显式形式变成真的能启动，
 * 见上一段）；也**刻意不拒绝所有 `#`**：带 fragment 的网页深链是真实用法，
 * 而 fragment 本身**不可能**让 intent 变成显式的（只有 `#Intent;` 能，它已被校验层拒绝）。
 * 影响面：极少数带 fragment 的链接可能丢一小段参数或落到首页 —— 不影响安全性，
 * 真机验证时若发现某条链接带 fragment 起不来，看这里。
 *
 * ## 降级链（每一级都留日志）
 *
 * ```
 * 6  MIUI 私有 options（MiuiFreeFormManager → MiuiMultiWindowUtils）+ startActivity
 * 7  公开 API 的 options（windowingMode=5 + bounds）+ startActivity
 * 8  不带 options 的 startActivity（此时多半以全屏起来，但至少能用）
 * 0  一条都没走通（调用方 CornerSmallWindow 会记一条 WARN）
 * ```
 *
 * 编号从 6 开始，**与 [FanShortcuts] 的 4/5、`SystemUiEnv.launchInSmallWindow` 的 1/2/3
 * 都不重叠** —— 上一版让两处共用编号，结果同一条日志里「走通第 3 级」有两种相反的含义
 * （见 `FanShortcuts.describeLevel` 的注释）。级别常量与「级别 → 人话」的映射**都只在
 * `FanShortcuts` 那一处**（[FanShortcuts.LEVEL_LINK_FREEFORM] 等），这里引用它，
 * 本文件里只有下面那三个 private 别名。
 *
 * ## 与快捷方式那条路的**关键差别**：没有「退化为启动宿主应用」这一级
 *
 * 快捷方式那条路最后能退化成「启动宿主应用本身」（打开微信而不是微信扫一扫）：
 * 不算正确，但比什么都不发生好。链接项**没有宿主**这个确定概念（URI 可能谁也接不住），
 * 所以三级用完就是失败。理由：猜一个应用去启动（比如按 scheme 猜一个包）等于
 * 「用户点了 A，我们打开了 B」，那比一条明确的失败日志更难排查。
 */
internal object FanLinks {

    private const val TAG = "FP-Fan"

    /**
     * 走通了哪一条路。**常量定义在 [FanShortcuts]**（那边还有 `describeLevel` 的文案）——
     * 编号与「第几级是什么意思」必须同源，两处各写一份的话，哪天只改了一边，
     * 日志里就会出现自相矛盾的句子（详见 `FanShortcuts.describeLevel` 的注释）。
     */
    private const val LEVEL_FREEFORM = FanShortcuts.LEVEL_LINK_FREEFORM
    private const val LEVEL_PUBLIC = FanShortcuts.LEVEL_LINK_PUBLIC
    private const val LEVEL_PLAIN = FanShortcuts.LEVEL_LINK_PLAIN

    /**
     * 启动扇形里的一条链接。
     *
     * **调用约定与 [FanShortcuts.launch] 完全一致**：返回走通的级别，0 = 全部失败
     * （调用方负责记一条 WARN）。所有异常都在这里吃掉 —— 这一段跑在触摸/输入线程上
     * （`CornerSmallWindow.onCommit`），放出去就是 SystemUI 崩溃。
     *
     * @param target 必须带 [FanTarget.linkUri]；[FanTarget.packageName] 是**解析出来的
     *   宿主包名**（`FanTargets` 在后台合成时解析，可能为空 —— 那表示这条链接当前
     *   谁也接不住，MIUI 那条小窗入口拿不到 options，会直接退到第 7 级）
     */
    fun launch(hook: HookContext, context: Context, target: FanTarget): Int {
        val uri = target.linkUri ?: return FanShortcuts.LEVEL_FAILED
        val raw = target.key

        // ── 第一层：字符串校验（纵深防御，见类注释）────────────────────────
        // 界面侧写入前校验过，但配置可能被手改 / 被同步覆盖 / 来自旧版本，
        // 所以启动前再判一次。**拒绝要留原样的整串**，否则用户拿着日志去核对时找不到
        // 自己当初填的那串字符（与 `ShortcutEntries` 的日志口径一致）。
        val bad = LinkTargets.check(uri)
        if (bad != null) {
            hook.log(
                Log.WARN,
                "链接项没通过安全校验（${bad.name}），不启动：\"$raw\"。" +
                    "界面里删掉它，或改成「带 scheme 的隐式链接」（例如 weixin://scanqrcode）",
            )
            return FanShortcuts.LEVEL_FAILED
        }

        // 解析必须自己 catch：`parseUri` 对畸形串会抛 URISyntaxException
        val parsed = try {
            // flags = 0（理由见类注释）；URI 走 `data`，动作是 ACTION_VIEW
            Intent.parseUri(uri, 0)
        } catch (t: Throwable) {
            hook.log(Log.WARN, "链接解析失败，不启动：\"$raw\"" +
                "（" + t.javaClass.simpleName + "）", t)
            return FanShortcuts.LEVEL_FAILED
        }

        // ── 第二层：解析结果校验 ──────────────────────────────────────────
        // 判的是**最终要启动的那个 Intent**，而不是输入字符串 —— 这一层不依赖
        // `parseUri` 的 flags 取值，也不依赖 ROM 的实现细节
        if (parsed.component != null || parsed.`package` != null) {
            hook.log(
                Log.WARN,
                "链接解析出了显式组件（component=" + parsed.component +
                    ", package=" + parsed.`package` + "），拒绝启动：\"$raw\"",
            )
            return FanShortcuts.LEVEL_FAILED
        }

        // 从非 Activity 的 Context 启动必须带 NEW_TASK。
        // **刻意不加 RESET_TASK_IF_NEEDED**（SystemUiEnv 那条路会加）：那是「进 launcher 入口」
        // 语义，用在深链上会把目标已有的任务栈重置 —— 链接要的是「把那个页面带起来」
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val host = target.packageName.takeIf { it.isNotEmpty() }

        // ── 第 6 级：MIUI 私有小窗选项 ────────────────────────────────────
        // 有宿主包名时才试：MIUI 那两个助手要一个包名去查它的应用清单
        //（`getActivityOptions(Context, String, ...)`），拿不到宿主就没法构造。
        // 宿主是 `FanTargets` 在后台线程用 PackageManager 解析出来的（触摸线程上禁止 IPC，
        // 规范 §7.5），解析不出来时这一级直接跳过、由第 7 级兜底
        if (host != null) {
            val opts = FanShortcuts.miuiOptions(context, host)
                ?.also { SystemUiEnv.forceLaunchNewTask(hook, it) }
            if (opts != null) {
                val level = tryStart(hook, context, parsed, opts, LEVEL_FREEFORM, raw)
                if (level != FanShortcuts.LEVEL_FAILED) return level
            } else {
                hook.log(Log.INFO, "链接项拿不到 MIUI 私有小窗选项（$host），退到公开 API：$raw")
            }
        } else {
            hook.log(Log.INFO, "链接项没有解析出宿主应用，跳过 MIUI 小窗入口：$raw")
        }

        // ── 第 7 级：公开 API 的小窗选项 ─────────────────────────────────
        FanShortcuts.publicOptions(context)
            ?.also { SystemUiEnv.forceLaunchNewTask(hook, it) }
            ?.let { opts ->
            val level = tryStart(hook, context, parsed, opts, LEVEL_PUBLIC, raw)
            if (level != FanShortcuts.LEVEL_FAILED) return level
        }

        // ── 第 8 级：不带 options ────────────────────────────────────────
        // ROM 忽略了 options / 小窗入口不可用时的最后一级：至少让链接能用
        //（此时多半以全屏起来，日志里能看出来）
        try {
            context.startActivity(parsed)
            hook.log(Log.INFO, "链接已启动，但不带小窗选项（多半是全屏）：$raw")
            return LEVEL_PLAIN
        } catch (t: Throwable) {
            logStartFailure(hook, t, raw, "不带小窗选项")
        }

        // 三级都没走通。**这里没有「退化为启动某个应用」**（理由见类注释）
        hook.log(
            Log.WARN,
            "链接启动失败：\"$raw\" 三级都没走通。" +
                "若这条日志里前两级报的是 ActivityNotFound，说明设备上没有任何应用接得住这个 URI" +
                "（scheme 拼错 / 应用没装 / 该 scheme 需要参数）",
        )
        return FanShortcuts.LEVEL_FAILED
    }

    /**
     * 试一级：带 options 启动，失败就按异常类型记日志。
     *
     * @return 走通返回 [level]，失败返回 [FanShortcuts.LEVEL_FAILED]（调用方继续往下试）
     */
    private fun tryStart(
        hook: HookContext,
        context: Context,
        intent: Intent,
        options: ActivityOptions,
        level: Int,
        raw: String,
    ): Int = try {
        context.startActivity(intent, options.toBundle())
        level
    } catch (t: Throwable) {
        logStartFailure(hook, t, raw, "第 $level 级（" + FanShortcuts.describeLevel(level) + "）")
        FanShortcuts.LEVEL_FAILED
    }

    /**
     * 启动失败的日志。**异常类型必须分开写**：它们的处置完全不同 ——
     * `ActivityNotFoundException` = 没有任何应用接得住这个 URI（用户要改链接或去装应用）；
     * `SecurityException` = 目标组件不允许被启动（链接本身没错，是对方不开放）。
     * 合成一句「启动失败」等于把这两种处置方式一起藏起来。
     */
    private fun logStartFailure(hook: HookContext, t: Throwable, raw: String, where: String) {
        val reason = when (t) {
            is ActivityNotFoundException ->
                "没有应用接得住这个 URI（检查 scheme 拼写、应用是否装了、是否缺少参数）"

            is SecurityException ->
                "目标拒绝被启动（需要权限或导出的组件限制）"

            else -> t.javaClass.simpleName + ": " + t.message
        }
        hook.log(Log.WARN, "$where 启动链接失败：\"$raw\" —— $reason", t)
    }
}
