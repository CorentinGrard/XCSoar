// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import org.xcsoar.mobile.core.AirspaceClassInfo
import org.xcsoar.mobile.core.AirspaceOption
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.core.airspaceAlerts
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme

class AirspaceAlertsViewModel(private val core: XcsoarCore) : ViewModel() {
    private val alertsFlow = MutableStateFlow<AirspaceAlerts?>(null)
    /** The options; null until read from the core. */
    val alerts: StateFlow<AirspaceAlerts?> = alertsFlow.asStateFlow()

    private val classesFlow = MutableStateFlow<List<AirspaceClassInfo>>(emptyList())
    /** XCSoar's airspace classes, drawn and warned of or not. */
    val classes: StateFlow<List<AirspaceClassInfo>> = classesFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            alertsFlow.value = try {
                core.airspaceAlerts()
            } catch (_: Exception) {
                null
            }
            classesFlow.value = try {
                core.airspaceClasses()
            } catch (_: Exception) {
                emptyList()
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
                AirspaceOption.WARNING_TIME -> it.copy(warningSeconds = value)
                AirspaceOption.ACK_TIME -> it.copy(ackSeconds = value)
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

    /** Draw and warn of one class; shown at once, read back if refused. */
    fun setClass(code: Int, display: Boolean, warning: Boolean) {
        classesFlow.value = classesFlow.value.map {
            if (it.code == code) it.copy(display = display, warning = warning) else it
        }
        viewModelScope.launch {
            try {
                core.setAirspaceClass(code, display, warning)
            } catch (_: Exception) {
                refresh()
            }
        }
    }
}

@Composable
fun AirspaceAlertsScreen(viewModel: AirspaceAlertsViewModel, onBack: () -> Unit) {
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val classes by viewModel.classes.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    AirspaceAlertsContent(alerts, viewModel::set, onBack, classes, viewModel::setClass)
}

/** XCSoar's range for the warning and acknowledgement times, s. */
private const val MIN_SECONDS = 10
private const val MAX_SECONDS = 1000

/**
 * The next time up or down: 5 s steps up to a minute, 15 s up to five
 * minutes, then whole minutes; within XCSoar's range.
 */
internal fun stepSeconds(value: Int, up: Boolean): Int {
    val step = if (up) when {
        value < 60 -> 5
        value < 300 -> 15
        else -> 60
    } else when {
        value <= 60 -> 5
        value <= 300 -> 15
        else -> 60
    }
    val next = if (up) (value / step + 1) * step
               else ((value + step - 1) / step - 1) * step
    return next.coerceIn(MIN_SECONDS, MAX_SECONDS)
}

/** "45 s", "2:30 min". */
private fun formatSeconds(seconds: Int): String =
    if (seconds < 60) "$seconds s"
    else "%d:%02d min".format(seconds / 60, seconds % 60)

/**
 * Whether XCSoar warns of airspace and how early; how the warning
 * reaches the pilot; and which airspace classes it draws and warns of.
 */
@Composable
fun AirspaceAlertsContent(
    alerts: AirspaceAlerts?,
    onSet: (AirspaceOption, Int) -> Unit,
    onBack: () -> Unit,
    classes: List<AirspaceClassInfo> = emptyList(),
    onSetClass: (code: Int, display: Boolean, warning: Boolean) -> Unit = { _, _, _ -> },
) {
    val colors = XcsTheme.colors
    val warnings = alerts?.warnings == true
    PageLayout {
        ScreenHeader("Airspace", onBack)

        SettingsGroup {
            SwitchRow("Airspace warnings", "Airspace ahead, inside, or crossing the task",
                      alerts?.warnings) {
                onSet(AirspaceOption.WARNINGS, if (it) 1 else 0)
            }
        }
        if (alerts != null && !warnings)
            Text("Without warnings, the map shows \"Airspace alerts off\" as a reminder.",
                 color = colors.caution, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        Caption("When", Modifier.padding(start = 4.dp, top = 8.dp))
        SecondsStepper("warning time", "Warn ahead", alerts?.warningSeconds, warnings) {
            onSet(AirspaceOption.WARNING_TIME, it)
        }
        SecondsStepper("acknowledgement time", "Quiet after Ack", alerts?.ackSeconds,
                       warnings) {
            onSet(AirspaceOption.ACK_TIME, it)
        }

        Caption("How", Modifier.padding(start = 4.dp, top = 8.dp))
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

        if (classes.isNotEmpty())
            ClassList(classes, warnings, onSetClass)
    }
}

@Composable
private fun SecondsStepper(name: String, caption: String, seconds: Int?, enabled: Boolean,
                           onChange: (Int) -> Unit) {
    val text = seconds?.let(::formatSeconds) ?: "–"
    Stepper(name, caption, text, text,
            canDecrease = enabled && seconds != null && seconds > MIN_SECONDS,
            canIncrease = enabled && seconds != null && seconds < MAX_SECONDS,
            onDecrease = { seconds?.let { onChange(stepSeconds(it, up = false)) } },
            onIncrease = { seconds?.let { onChange(stepSeconds(it, up = true)) } },
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f))
}

/**
 * The classes in the pilot's airspace files first; the others behind
 * "Other classes".  Each can be drawn on the map, warned of, or both.
 */
@Composable
private fun ClassList(
    classes: List<AirspaceClassInfo>,
    warnings: Boolean,
    onSetClass: (code: Int, display: Boolean, warning: Boolean) -> Unit,
) {
    val colors = XcsTheme.colors
    var showOthers by rememberSaveable { mutableStateOf(false) }
    val (used, others) = classes.sortedBy { it.name.lowercase() }.partition { it.count > 0 }

    Caption("Airspace classes", Modifier.padding(start = 4.dp, top = 8.dp))
    if (used.isEmpty())
        Text("No airspace file is loaded (Menu → Data files).",
             color = colors.textSecondary, fontSize = 15.sp,
             modifier = Modifier.padding(horizontal = 4.dp))
    ClassGroup(used, warnings, onSetClass)

    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { showOthers = !showOthers }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (showOthers) "Hide other classes" else "Other classes (${others.size})",
             color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
    if (showOthers)
        ClassGroup(others, warnings, onSetClass)
}

@Composable
private fun ClassGroup(
    classes: List<AirspaceClassInfo>,
    warnings: Boolean,
    onSetClass: (code: Int, display: Boolean, warning: Boolean) -> Unit,
) {
    if (classes.isEmpty()) return
    val colors = XcsTheme.colors
    Column(Modifier
        .fillMaxWidth()
        .background(colors.panel, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp)) {
        classes.forEachIndexed { index, c ->
            if (index > 0) HorizontalDivider(color = colors.panelBorder)
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = colors.text, fontSize = 17.sp,
                         fontWeight = FontWeight.SemiBold, maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    if (c.count > 0)
                        Text("${c.count} in the files", color = colors.textSecondary,
                             fontSize = 14.sp)
                }
                ToggleChip("Map", c.display, enabled = true) {
                    onSetClass(c.code, it, c.warning)
                }
                ToggleChip("Warn", c.warning, enabled = warnings) {
                    onSetClass(c.code, c.display, it)
                }
            }
        }
    }
}

/** A small on/off button, 56 dp tall: filled when on. */
@Composable
private fun ToggleChip(label: String, checked: Boolean, enabled: Boolean,
                       onChange: (Boolean) -> Unit) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier
            .height(56.dp)
            .width(68.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox,
                        onValueChange = onChange)
            .padding(vertical = 8.dp)
            .background(if (checked) colors.selected else Color.Transparent, shape)
            .then(if (checked) Modifier
                  else Modifier.background(colors.control, shape)),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (checked) colors.onSelected else colors.textSecondary,
             fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
