package io.github.kotoriinsky.tsuzuri.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.isVisible
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import io.github.kotoriinsky.tsuzuri.ui.state.ReloadController
import kotlinx.coroutines.launch

@Composable
fun WidgetSettingsScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val prefs = ConfigStore.observe()
    val features = listOf(Catalog.PA_WIDGET_PICKER_INJECT, Catalog.PA_WIDGET_ADMISSION).map(Catalog::require)
    val style = Catalog.require(Catalog.WIDGET_STYLE)
    val values = (features + style).map { prefs.isEnabled(it).toString() } + style.prefs.map {
        when (it) {
            is PrefSpec.Toggle -> prefs.value(it).toString()
            is PrefSpec.Choice -> prefs.value(it)
            else -> ""
        }
    }
    var appliedValues by remember { mutableStateOf(values) }
    var reloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        kit.VSpace(FpSpace.s)
        ScopeRequirement(features.first()) { navigate(Dest.Setup) }
        kit.Card {
            FeatureToggleRow(features[0], prefs)
            kit.Divider()
            FeatureToggleRow(features[1], prefs)
        }
        kit.VSpace(FpSpace.m)
        kit.Card {
            FeatureToggleRow(style, prefs)
            style.prefs.filter { it.isVisible(prefs, style.key) }.forEach { pref ->
                kit.Divider()
                PrefRow(pref, prefs, enabled = prefs.isEnabled(style), onNavigate = navigate)
            }
        }
        if (!AppSettings.autoReload && values != appliedValues) {
            kit.VSpace(FpSpace.m)
            kit.Card {
                kit.ActionRow(
                    title = fpString("feature.reload.pending"),
                    summary = null,
                    trailingText = null,
                    enabled = !reloading,
                    onClick = {
                        reloading = true
                        scope.launch {
                            val outcome = ReloadController.reloadAll()
                            reloading = false
                            if (outcome.anyReloaded) appliedValues = values
                            else navigate(Dest.Setup)
                        }
                    },
                )
            }
        }
    }
}
