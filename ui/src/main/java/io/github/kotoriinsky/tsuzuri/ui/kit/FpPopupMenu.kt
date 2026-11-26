package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowListPopup

/** 浮层菜单的一项。 */
data class FpPopupAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 原生浮层操作菜单（不进 [FpKit] 契约，两套风格各自走真实原生浮层）：
 *  - MiuiX 走 [WindowListPopup] + [ListPopupColumn]（弹簧展开、背景微暗、澎湃圆角）；
 *  - Material 3 走 [DropdownMenu]。
 */
@Composable
fun FpPopupMenu(
    expanded: Boolean,
    alignEnd: Boolean,
    actions: List<FpPopupAction>,
    onDismiss: () -> Unit,
) {
    val kit = LocalFpKit.current
    val colors = kit.colors()
    val feedback = fpRowFeedback()

    if (kit === MiuixKit) {
        WindowListPopup(
            show = expanded,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = if (alignEnd) PopupPositionProvider.Align.TopEnd else PopupPositionProvider.Align.TopStart,
            enableWindowDim = true,
            onDismissRequest = onDismiss,
        ) {
            ListPopupColumn {
                actions.forEach { action ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(min = 160.dp)
                            .clickable {
                                feedback()
                                onDismiss()
                                action.onClick()
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        top.yukonga.miuix.kmp.basic.Text(
                            text = action.label,
                            color = if (action.destructive) colors.negative else colors.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    } else {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(16.dp),
            containerColor = colors.surface,
            shadowElevation = 8.dp,
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            color = if (action.destructive) colors.negative else colors.onSurface,
                            fontSize = 14.sp,
                        )
                    },
                    onClick = {
                        feedback()
                        onDismiss()
                        action.onClick()
                    },
                )
            }
        }
    }
}
