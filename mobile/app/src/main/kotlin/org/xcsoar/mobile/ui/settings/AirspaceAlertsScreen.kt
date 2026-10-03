// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.AirspaceAlerts
import org.xcsoar.mobile.core.AirspaceOption
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.core.airspaceAlerts
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.theme.XcsTheme

class AirspaceAlertsViewModel(private val core: XcsoarCore) : ViewModel() {
    private val alertsFlow = MutableStateFlow<AirspaceAlerts?>(null)
    /** The options; null until read from the core. */
    val alerts: StateFlow<AirspaceAlerts?> = alertsFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            alertsFlow.value = try {
                core.airspaceAlerts()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Change one option; shown at once, read back if the core refuses. */
    fun set(option: AirspaceOption, value: Int) {
        alertsFlow.value = alertsFlow.value?.let {
            when (option) {
                AirspaceOption.WARNINGS -> it.copy(warnings = value != 0)
                AirspaceOption.ALERT_SOUND -> it.copy(sound = value != 0)
                AirspaceOption.ALERT_VIBRATION -> it.copy(vibration = value != 0)
                AirspaceOption.AUTO_HIDE -> it.copy(autoHideSeconds = value)
            }
        }
        viewModelScope.launch {
            try {
                core.setAirspaceOption(option, value)
            } catch (_: Exception) {
                refresh()
            }
        }
    }
}

@Composable
fun AirspaceAlertsScreen(viewModel: AirspaceAlertsViewModel, onBack: () -> Unit) {
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    AirspaceAlertsContent(alerts, viewModel::set, onBack)
}

/**
 * Whether XCSoar warns of airspace, and how: a tone, a vibration, and
 * a banner that stays until acknowledged or hides itself.
 */
@Composable
fun AirspaceAlertsContent(
    alerts: AirspaceAlerts?,
    onSet: (AirspaceOption, Int) -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    val warnings = alerts?.warnings == true
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Airspace alerts", onBack)

        SettingsGroup {
            SwitchRow("Airspace warnings", "Airspace ahead, inside, or crossing the task",
                      alerts?.warnings) {
                onSet(AirspaceOption.WARNINGS, if (it) 1 else 0)
            }
        }

        SettingsGroup {
            SwitchRow("Sound", "A tone for a new warning, and when it gets worse",
                      alerts?.sound, enabled = warnings) {
                onSet(AirspaceOption.ALERT_SOUND, if (it) 1 else 0)
            }
            SwitchRow("Vibration", "Vibrate for a new warning, and when it gets worse",
                      alerts?.vibration, enabled = warnings) {
                onSet(AirspaceOption.ALERT_VIBRATION, if (it) 1 else 0)
            }
            SwitchRow("Hide after ${AirspaceAlerts.AUTO_HIDE_SECONDS} s",
                      "The banner counts down, then goes; it comes back if the " +
                          "warning gets worse",
                      alerts?.let { it.autoHideSeconds > 0 }, enabled = warnings) {
                onSet(AirspaceOption.AUTO_HIDE,
                      if (it) AirspaceAlerts.AUTO_HIDE_SECONDS else 0)
            }
        }

        if (alerts != null && !warnings)
            Text("Without warnings, the map shows \"Airspace alerts off\" as a reminder.",
                 color = colors.caution, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))
    }
}
