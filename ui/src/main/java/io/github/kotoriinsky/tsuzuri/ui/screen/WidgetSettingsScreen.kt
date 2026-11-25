package io.github.kotoriinsky.tsuzuri.ui.screen

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.core.PrefSpec
import io.github.kotoriinsky.tsuzuri.core.isVisible
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.fpStringRes
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.nav.Dest
import io.github.kotoriinsky.tsuzuri.ui.state.AppSettings
import io.github.kotoriinsky.tsuzuri.ui.state.ConfigStore
import io.github.kotoriinsky.tsuzuri.ui.state.ReloadController
import io.github.kotoriinsky.tsuzuri.ui.state.Su
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WidgetSettingsScreen(padding: PaddingValues, navigate: (Dest) -> Unit) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val prefs = ConfigStore.observe()

    val desktopFeatures = listOf(
        Catalog.DESKTOP_WIDGET_STYLE,
        Catalog.DESKTOP_WIDGET_DRAG_PA,
        Catalog.DESKTOP_WIDGET_STACK,
    ).map(Catalog::require)

    val paFeatures = listOf(Catalog.PA_WIDGET_PICKER_INJECT, Catalog.PA_WIDGET_ADMISSION).map(Catalog::require)
    val style = Catalog.require(Catalog.WIDGET_STYLE)
    val paValues = (paFeatures + style).map { prefs.isEnabled(it).toString() } + style.prefs.map {
        when (it) {
            is PrefSpec.Toggle -> prefs.value(it).toString()
            is PrefSpec.Choice -> prefs.value(it)
            else -> ""
        }
    }
    var appliedPaValues by remember { mutableStateOf(paValues) }
    var reloading by remember { mutableStateOf(false) }
    var restartingHome by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        kit.SectionHeader(title = fpString("widget.section.desktop"))
        ScopeRequirement(desktopFeatures.first()) { navigate(Dest.Setup) }
        kit.Card {
            desktopFeatures.forEachIndexed { idx, spec ->
                if (idx > 0) kit.Divider()
                FeatureToggleRow(spec, prefs)
            }
            kit.Divider()
            kit.ActionRow(
                title = fpString("widget.desktop.restart.action"),
                summary = fpString("widget.desktop.restart.summary"),
                trailingText = null,
                enabled = !restartingHome,
                onClick = {
                    restartingHome = true
                    scope.launch {
                        val state = withContext(Dispatchers.IO) { Su.exec("killall com.miui.home") }
                        restartingHome = false
                        val key = if (state == Su.State.OK) {
                            "widget.desktop.restart.done"
                        } else {
                            "widget.desktop.restart.need_root"
                        }
                        Toast.makeText(context, context.getString(fpStringRes(context, key)), Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        kit.SectionHeader(title = fpString("widget.section.pa"))
        ScopeRequirement(paFeatures.first()) { navigate(Dest.Setup) }
        kit.Card {
            FeatureToggleRow(paFeatures[0], prefs)
            kit.Divider()
            FeatureToggleRow(paFeatures[1], prefs)
        }
        kit.VSpace(FpSpace.m)
        kit.Card {
            FeatureToggleRow(style, prefs)
            style.prefs.filter { it.isVisible(prefs, style.key) }.forEach { pref ->
                kit.Divider()
                PrefRow(pref, prefs, enabled = prefs.isEnabled(style), onNavigate = navigate)
            }
        }
        if (!AppSettings.autoReload && paValues != appliedPaValues) {
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
                            if (outcome.anyReloaded) appliedPaValues = paValues
                            else navigate(Dest.Setup)
                        }
                    },
                )
            }
        }
        kit.VSpace(FpSpace.huge)
    }
}
