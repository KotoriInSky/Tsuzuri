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

/** 「主动智能」二级页：首页只留一个入口，各项开关收在这里（用户 2026-10-27 要求合并）。 */
@Composable
fun ProactiveScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val features = Catalog.settingsFeatures.filter { it.group == FeatureGroup.CLIPBOARD }
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
