package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore

/** 归入「状态栏」二级页的特性键（按日常高频程度排序）。 */
internal val STATUSBAR_FEATURE_KEYS: List<String> = listOf(
    Catalog.BATTERY_STYLE,
    Catalog.STATUSBAR_ICONS,
    Catalog.CLEAN_STATUSBAR,
)

/** 「状态栏」二级页：首页只留一个入口，电池样式 / 通知图标 / 完美状态栏收在这里。 */
@Composable
fun StatusBarSettingsScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val features = STATUSBAR_FEATURE_KEYS.mapNotNull { Catalog.find(it) }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        kit.VSpace(FpSpace.s)
        kit.Card {
            for (spec in features) {
                ScopedFeatureRow(spec, prefs) { navigate(Dest.FeatureDetail(spec.key)) }
            }
        }
        kit.VSpace(FpSpace.huge)
    }
}
