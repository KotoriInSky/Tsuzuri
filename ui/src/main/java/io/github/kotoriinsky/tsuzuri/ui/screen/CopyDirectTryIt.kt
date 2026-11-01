package io.github.kotoriinsky.tsuzuri.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.CopyDirectSamples
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit

/**
 * 复制直达的「试一下」：往剪贴板写一条示例链接，走**真实链路**
 * （剪贴板 → system_server 识别 → SystemUI 弹岛），用户当场看到真实效果。
 *
 * 为什么不是「申请通知权限」：岛是 SystemUI 自己发的（`PasscodeIslandPresenter`），
 * 真机两条渠道都开着且系统层不可关，设置 App 的通知权限与它无关 —— 请求它只会误导。
 *
 * 示例从 [CopyDirectSamples] 随机抽（10 个平台各一条、内容随包不联网，每次岛的样子不同），
 * 且不与上一条相同：识别侧 60 秒内同一内容去重，连抽同一条看起来就是「坏了」。
 * 「识别所有链接」关着时只抽有平台规则的那几条，保证点了一定有岛。
 */
@Composable
internal fun CopyDirectTryIt(prefs: Prefs, enabled: Boolean) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    var previous by remember { mutableStateOf<String?>(null) }
    kit.Card {
        kit.ActionRow(
            title = fpString("copy_direct.try"),
            summary = fpString("copy_direct.try.hint"),
            trailingText = null,
            enabled = enabled,
            onClick = {
                val generic = prefs.rawBoolean(Keys.pref(Catalog.PASSCODE_ISLAND, "generic_url"), true)
                val url = CopyDirectSamples.pick(previous, genericUrl = generic)
                previous = url
                // 复制成功本身不提示：Android 13 起系统会自己弹「已复制」，再弹一个就重复了
                runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Tsuzuri", url))
                }
            },
        )
    }
}
