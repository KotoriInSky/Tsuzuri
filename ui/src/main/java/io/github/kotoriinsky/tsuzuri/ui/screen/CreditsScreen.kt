package io.github.kotoriinsky.tsuzuri.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit

/** 一个被致谢的项目：名字、许可、原始仓库。名字与许可是专有名词，不进字符串资源。 */
private class Credit(val name: String, val license: String, val url: String)

/**
 * 致谢与开源许可（用户 2026-10-25：致谢做成可点的清单，和第三方许可合并）。
 *
 * 源数据仍按「怎么用它」分三组写（便于对照 THIRD-PARTY.md），界面上合成一张卡、按名字排序。
 * 每行 = 项目名 + 许可，点了打开原始仓库。完整的用途与落点说明仍在仓库的 THIRD-PARTY.md（最后一行）。
 * 新增依赖或移植代码时，这里与 THIRD-PARTY.md 要一起改。
 */
private val GROUPS: List<Pair<String, List<Credit>>> = listOf(
    "credits.group.runtime" to listOf(
        Credit("LSPosed", "GPL-3.0", "https://github.com/LSPosed/LSPosed"),
        Credit("libxposed", "Apache-2.0", "https://github.com/libxposed/api"),
        Credit("MiuiX", "Apache-2.0", "https://github.com/compose-miuix-ui/miuix"),
        Credit("Jetpack Compose / AndroidX", "Apache-2.0", "https://github.com/androidx/androidx"),
        Credit("MaterialKolor", "Apache-2.0", "https://github.com/jordond/MaterialKolor"),
        Credit("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    ),
    "credits.group.included" to listOf(
        Credit("AndroidLiquidGlass（Kyant）", "Apache-2.0", "https://github.com/Kyant0/AndroidLiquidGlass"),
        Credit("Material Icons", "Apache-2.0", "https://github.com/google/material-design-icons"),
        Credit("libwebp", "BSD-3-Clause", "https://chromium.googlesource.com/webm/libwebp"),
        Credit("tracking_number_data", "MIT", "https://github.com/jkeen/tracking_number_data"),
        Credit("oembed", "MIT", "https://github.com/iamcal/oembed"),
        Credit("Public Suffix List", "MPL-2.0", "https://publicsuffix.org/list/"),
        Credit("tracking-query-params-registry", "Unlicense", "https://github.com/mpchadwick/tracking-query-params-registry"),
    ),
    "credits.group.reference" to listOf(
        Credit("astrbot_plugin_parser", "MIT", "https://github.com/Zhalslar/astrbot_plugin_parser"),
        Credit("nonebot-plugin-parser-lite", "MIT", "https://github.com/sokoko-org/nonebot-plugin-parser-lite"),
        Credit("nonebot-plugin-parser", "MIT", "https://github.com/fllesser/nonebot-plugin-parser"),
        Credit("ParseHub", "MIT", "https://github.com/z-mio/ParseHub"),
        Credit("FxEmbed", "MIT", "https://github.com/FxEmbed/FxEmbed"),
        Credit("parse-video", "MIT", "https://github.com/wujunwei928/parse-video"),
        Credit("lux", "MIT", "https://github.com/iawia002/lux"),
        Credit("metascraper", "MIT", "https://github.com/microlinkhq/metascraper"),
        Credit("besticon", "MIT", "https://github.com/mat/besticon"),
        Credit("yt-dlp", "Unlicense", "https://github.com/yt-dlp/yt-dlp"),
    ),
)

private val CREDITS: List<Credit> = GROUPS.flatMap { it.second }.sortedBy { it.name.lowercase() }

private const val THIRD_PARTY_URL = "https://github.com/KotoriInSky/Tsuzuri/blob/main/THIRD-PARTY.md"

@Composable
fun CreditsScreen(padding: PaddingValues) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    fun open(url: String) {
        val ok = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
        if (!ok) Toast.makeText(context, context.getString(fpStringRes(context, "external.open.failed")), Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        // 不分组（用户 2026-10-25）：一张卡按名字排序列出全部项目
        kit.VSpace(FpSpace.s)
        kit.Card {
            CREDITS.forEachIndexed { i, c ->
                if (i > 0) kit.Divider()
                kit.ActionRow(c.name, c.license, null, { open(c.url) }, navigates = true)
            }
        }
        kit.VSpace(FpSpace.m)
        kit.Card {
            kit.ActionRow(fpString("credits.full"), null, null, { open(THIRD_PARTY_URL) }, navigates = true)
        }
        kit.VSpace(FpSpace.huge)
    }
}
