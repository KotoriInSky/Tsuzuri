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
import io.github.kotoriinsky.tsuzuri.core.FeatureGroup
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.settingsFeatures
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore

/** 「场景建议」二级页：收录打开新应用 / 音乐推荐 / 临时常亮三项（复制直达已在首页独立一行）。 */
@Composable
fun ProactiveScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val features = Catalog.settingsFeatures
        .filter { it.group == FeatureGroup.CLIPBOARD && it.key != Catalog.PASSCODE_ISLAND }
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
