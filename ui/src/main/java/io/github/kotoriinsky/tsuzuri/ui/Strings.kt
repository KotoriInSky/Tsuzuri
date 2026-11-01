package io.github.kotoriinsky.tsuzuri.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import java.util.concurrent.ConcurrentHashMap

/**
 * i18n 键 → 资源 id。
 *
 * 为什么不用一张手写的 Map：键有近百个，手写会漏、会写错，而且加特性时要改两处。
 * 这里让**资源名 = 键名把点换成下划线**（`feature.x.title` → `feature_x_title`），
 * 只有一条转换规则，加特性时只需要在 strings.xml 里照着键名写一行。
 *
 * 代价是查不到时不会崩、只会显示键名本身 —— 所以构建期有一道校验任务
 * （`:ui:verifyStringKeys`，见 `ui/build.gradle.kts`）：它扫源码里**所有引用 i18n 键的位置**
 * （`fpString(...)`、`labelKey=`、`hintKey=`、`Choice.Option(...)`），
 * 任何一个找不到对应资源就让构建失败，不会拖到运行时。
 * 它只判「引用了但没有资源」（会在用户眼前暴露），不判「有资源但没人用」（死键无害）。
 */
private val cache = ConcurrentHashMap<String, Int>()

@StringRes
fun fpStringRes(context: Context, key: String): Int =
    cache.getOrPut(key) {
        context.resources.getIdentifier(
            key.replace('.', '_'),
            "string",
            context.packageName,
        )
    }

/**
 * 解析 i18n 键。查不到返回键名本身。
 *
 * 返回键名而不是空串是刻意的：截图里一看就知道是哪个键漏了。
 */
@Composable
fun fpString(key: String): String {
    val res = fpStringRes(LocalContext.current, key)
    return if (res == 0) key else stringResource(res)
}

/**
 * 组合期取 [android.content.res.Resources]，供**协程 / 回调里**再格式化时使用。
 *
 * 为什么不继续用 `LocalContext.current.getString(...)`：Compose 1.12 起 lint 会报
 * `LocalContextGetResourceValueCall` —— Context 上取到的 Resources 不随配置变化更新，
 * 系统语言/字号改变后回调里可能拿到过期配置。`LocalResources.current` 是配置感知的那个。
 */
@Composable
fun fpResources(): android.content.res.Resources = LocalResources.current

/** 需要格式化参数时用。 */
@Composable
fun fpString(key: String, vararg args: Any): String {
    val res = fpStringRes(LocalContext.current, key)
    return if (res == 0) key else stringResource(res, *args)
}

/**
 * 解析 i18n 键 —— **非 Composable** 版本。查不到同样返回键名本身。
 *
 * 存在理由：搜索要在 `remember { }` 里建索引、要在组合之外比对文案，
 * 而 [fpString] 只能在 @Composable 里调。
 *
 * 两者的行为**必须一致**（都回落成键名），否则会出现
 * 「搜索按显示文案能命中、但界面上显示的是键名」这种讲不清的不一致。
 */
fun fpText(context: android.content.Context, key: String): String {
    val res = fpStringRes(context, key)
    return if (res == 0) key else context.getString(res)
}
