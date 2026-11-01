package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.kotoriinsky.tsuzuri.ui.fpResources
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.fpResources
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpMessageHost
import io.github.kotoriinsky.tsuzuri.ui.kit.Paragraph
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import io.github.kotoriinsky.tsuzuri.ui.state.PreviewWallpaper
import kotlinx.coroutines.launch

/**
 * 预览底图设置。
 *
 * 重写要点（2026-10-17，用户反馈「使用用户壁纸等交互完全没有做好」）：
 *  - **不再申请 `READ_EXTERNAL_STORAGE`**。目标 SDK 37 上这条权限已被 `READ_MEDIA_*` 取代，
 *    申请必被拒；而 `WallpaperManager.drawable` 本来就不需要它。旧代码弹一次注定失败的
 *    授权框，用户点了「允许」也还是看不到壁纸 —— 这是最伤信任的一种交互。
 *  - 两条来源如实标注：相册自选（用户完全掌控）> 系统壁纸（由 SystemUI 读取后推过来，
 *    2026-10-21 起不再需要 root）。读不到时说明原因，并给一条**现在就能做**的路：选一张相册图。
 *  - 开关只负责「要不要用真实壁纸」；来源与操作是它下面独立的两行。
 */
/** 想用壁纸却没拿到时的一行原因；拿到了、或用户关了「使用当前壁纸」时什么都不画。 */
@Composable
fun PreviewWallpaperReason(result: PreviewWallpaper.Result) {
    if (!AppSettings.previewWallpaper || result.source != PreviewWallpaper.Source.NONE) return
    LocalFpKit.current.Paragraph(fpString(result.reasonKey ?: PreviewWallpaper.REASON_UNREADABLE), FpEmphasis.MUTED)
}

@Composable
fun WallpaperPreviewControl(result: PreviewWallpaper.Result) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val res = fpResources()
    val messages = LocalFpMessageHost.current
    val scope = rememberCoroutineScope()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = PreviewWallpaper.saveCustom(context, uri)
            messages.showSnackbar(
                res.getString(
                    fpStringRes(context, if (ok) "preview.wallpaper.saved" else "preview.wallpaper.save.failed"),
                ),
            )
        }
    }

    kit.SwitchRow(
        title = fpString("preview.wallpaper.use"),
        summary = null,
        checked = AppSettings.previewWallpaper,
        onCheckedChange = { enabled ->
            AppSettings.previewWallpaper = enabled
            PreviewWallpaper.invalidate()
        },
    )

    if (!AppSettings.previewWallpaper) return

    // 来源行只在没拿到壁纸时出现（说原因）；拿到了就不再写「系统壁纸快照」
    PreviewWallpaperReason(result)

    kit.Divider()
    kit.ActionRow(
        title = fpString("preview.wallpaper.pick"),
        summary = fpString("preview.wallpaper.pick.hint"),
        trailingText = null,
        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
    if (PreviewWallpaper.hasCustom(context)) {
        kit.Divider()
        kit.ActionRow(
            title = fpString("preview.wallpaper.clear"),
            summary = null,
            trailingText = null,
            onClick = {
                PreviewWallpaper.clearCustom(context)
                scope.launch { messages.showSnackbar(res.getString(fpStringRes(context, "preview.wallpaper.cleared"))) }
            },
        )
    }
}
