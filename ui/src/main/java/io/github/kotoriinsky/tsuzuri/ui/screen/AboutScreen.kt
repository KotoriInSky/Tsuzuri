package io.github.kotoriinsky.tsuzuri.ui.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.ui.BuildConfig
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.kit.AppIdentityIconSize
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit

/**
 * 关于页：照 HyperOS 设置「关于本机」的排法（2026-10-21 重写）。
 *
 *  - 顶部不进卡片：居中的**本应用 APK 启动图标**（原来是一个齿轮色块，与桌面上的图标不是同一个东西）、
 *    应用名、一行版本号；
 *  - 下面才是选项卡片：先「要做的事」（更新日志 / 报告问题 / 仓库），再「项目信息」（作者 / 许可 / 致谢 / 构建时间）。
 *  - 不写小节标题、不写一句话简介（用户 2026-10-21：小字越少越好），行右侧只留箭头。
 *
 * 运行状态在首页，开发资料留在仓库，这一页只回答「这是哪个版本、去哪反馈」。
 */
@Composable
fun AboutScreen(padding: PaddingValues, navigate: (io.github.kotoriinsky.tsuzuri.ui.nav.Dest) -> Unit) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { AppIdentityIconSize.roundToPx() }
    val icon = remember(context, iconPx) { appIcon(context, iconPx) }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        kit.AppIdentity(
            icon = icon,
            name = fpString("app.name"),
            detail = fpString("about.version.line", BuildConfig.MODULE_VERSION),
        )
        kit.Card {
            kit.ActionRow(fpString("about.changelog.title"), null, null, { openAboutUrl(context, "$REPO_URL/blob/main/CHANGELOG.md") }, navigates = true)
            kit.Divider()
            kit.ActionRow(fpString("about.feedback"), null, null, { openAboutUrl(context, "$REPO_URL/issues") }, navigates = true)
            kit.Divider()
            kit.ActionRow(fpString("about.repo"), null, null, { openAboutUrl(context, REPO_URL) }, navigates = true)
        }
        kit.VSpace(12.dp)
        kit.Card {
            kit.InfoRow(fpString("about.author"), fpString("about.author.value"))
            kit.Divider()
            kit.InfoRow(fpString("about.license"), fpString("about.license.value"))
            kit.Divider()
            // 致谢与第三方许可合并成一页（用户 2026-10-25）：项目 + 许可 + 原始仓库，全文仍链到 THIRD-PARTY.md
            kit.ActionRow(fpString("about.thanks"), null, null, { navigate(io.github.kotoriinsky.tsuzuri.ui.nav.Dest.Credits) }, navigates = true)
            kit.Divider()
            kit.InfoRow(fpString("about.build"), BuildConfig.BUILD_TIME)
        }
        kit.VSpace(24.dp)
    }
}

/**
 * 本应用的启动图标，按 [sizePx] 渲染成位图。
 *
 * 取 `PackageManager.getApplicationIcon` 而不是直接读 mipmap：它给的是桌面上看到的同一个图标
 * （自适应图标按系统遮罩裁好），换了图标资源这里自动跟着变。读不到返回 `null`，身份头不画图标位。
 */
private fun appIcon(context: Context, sizePx: Int): ImageBitmap? = runCatching {
    val d = context.packageManager.getApplicationIcon(context.packageName)
    val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, sizePx, sizePx)
    d.draw(Canvas(b))
    b.asImageBitmap()
}.getOrNull()

private const val REPO_URL = "https://github.com/KotoriInSky/Tsuzuri"

private fun openAboutUrl(context: Context, url: String) {
    val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
    if (!opened) Toast.makeText(context, context.getString(fpStringRes(context, "external.open.failed")), Toast.LENGTH_SHORT).show()
}
