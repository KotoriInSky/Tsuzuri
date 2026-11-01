package io.github.kotoriinsky.tsuzuri.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 图卡单选的**布局**（两套 Kit 共用，外观差异只在颜色与文字样式，由调用方传入）。
 *
 * 画法照 HyperOS「显示与亮度」顶部的浅色 / 深色模式卡：方形图卡 + 下方标签，
 * 选中 = 主题色 2dp 描边 + 标签主题色。≤ 3 项等分整行；更多项（如 5 种图标形状）横向滚动，
 * 每张固定 84dp —— 等分会把 5 张挤到 50dp，示意图看不清。
 */
@Composable
internal fun FpImageChoiceLayout(
    header: @Composable () -> Unit,
    options: List<FpImageOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
    colors: FpIllustrationColors,
    cardColor: Color,
    label: @Composable (text: String, selected: Boolean) -> Unit,
) {
    val feedback = fpRowFeedback()
    val scrolls = options.size > 3
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).alpha(if (enabled) 1f else 0.5f)) {
        header()
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                val border by animateColorAsState(if (selected) colors.accent else Color.Transparent, label = "image-choice")
                val shape = RoundedCornerShape(16.dp)
                Column(
                    (if (scrolls) Modifier.width(84.dp) else Modifier.weight(1f))
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                            feedback()
                            onSelect(index)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(shape)
                            .background(cardColor)
                            .border(2.dp, border, shape)
                            .padding(10.dp),
                    ) {
                        Canvas(Modifier.fillMaxSize()) { option.draw(this, colors) }
                    }
                    Box(Modifier.padding(top = 6.dp)) { label(option.label, selected) }
                }
            }
        }
    }
}
