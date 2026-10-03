// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.crew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.WeGlideSettings
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The pilot's name (IGC file) and WeGlide account, as edited. */
data class PilotState(
    val loaded: Boolean = false,
    val pilot: String = "",
    val weGlideEnabled: Boolean = false,
    /** Digits only; empty if not set. */
    val pilotId: String = "",
    /** "YYYY-MM-DD", or empty. */
    val birthdate: String = "",
    val saved: Boolean = false,
    val error: String? = null,
)

class PilotViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(PilotState())
    val state: StateFlow<PilotState> = stateFlow.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val crew = core.crew()
            val weGlide = core.weGlideSettings() ?: WeGlideSettings()
            stateFlow.value = PilotState(
                loaded = true, pilot = crew?.pilot.orEmpty(), weGlideEnabled = weGlide.enabled,
                pilotId = weGlide.pilotId.takeIf { it > 0 }?.toString().orEmpty(),
                birthdate = weGlide.birthdate)
        }
    }

    fun setPilot(value: String) = stateFlow.update { it.copy(pilot = value, saved = false) }

    fun setWeGlideEnabled(value: Boolean) =
        stateFlow.update { it.copy(weGlideEnabled = value, saved = false) }

    fun setPilotId(value: String) =
        stateFlow.update { it.copy(pilotId = value.filter(Char::isDigit).take(9), saved = false) }

    fun setBirthdate(value: String) = stateFlow.update { it.copy(birthdate = value, saved = false) }

    fun save(onSaved: () -> Unit) {
        val s = stateFlow.value
        viewModelScope.launch {
            try {
                core.setCrew(s.pilot.trim(), null)
                core.setWeGlideSettings(WeGlideSettings(
                    s.weGlideEnabled, s.pilotId.toIntOrNull() ?: 0, s.birthdate))
                stateFlow.update { it.copy(saved = true, error = null) }
                onSaved()
            } catch (_: Exception) {
                stateFlow.update { it.copy(error = "Could not save") }
            }
        }
    }
}

@Composable
fun PilotScreen(viewModel: PilotViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    BackHandler(onBack = onBack)
    PilotContent(state, viewModel::setPilot, viewModel::setWeGlideEnabled,
                 viewModel::setPilotId, viewModel::setBirthdate,
                 onSave = { viewModel.save(onBack) }, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PilotContent(
    state: PilotState,
    onPilot: (String) -> Unit,
    onWeGlideEnabled: (Boolean) -> Unit,
    onPilotId: (String) -> Unit,
    onBirthdate: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    var pickDate by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Pilot & WeGlide", onBack)

        Caption("Pilot", Modifier.padding(start = 4.dp))
        InputField(state.pilot, onPilot, "Name in the IGC file",
                   capitalization = KeyboardCapitalization.Words)

        Caption("WeGlide", Modifier.padding(start = 4.dp))
        Row(Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(colors.panel, RoundedCornerShape(12.dp))
                .toggleable(state.weGlideEnabled, role = Role.Switch,
                            onValueChange = onWeGlideEnabled)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Upload flights to WeGlide", color = colors.text, fontSize = 17.sp,
                 fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(state.weGlideEnabled, onCheckedChange = null,
                   colors = SwitchDefaults.colors(checkedTrackColor = colors.selected))
        }

        if (state.weGlideEnabled) {
            InputField(state.pilotId, onPilotId, "WeGlide pilot ID",
                       keyboardType = KeyboardType.Number)
            Text("The number in your profile's address: weglide.org/user/1234.",
                 color = colors.textSecondary, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))
            Row(Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(colors.panel, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { pickDate = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Date of birth", color = colors.text, fontSize = 17.sp,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(state.birthdate.ifEmpty { "Set" }, color = colors.text, fontSize = 17.sp)
            }
            Text("WeGlide checks your ID and date of birth instead of a password.",
                 color = colors.textSecondary, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }

        state.error?.let { Text(it, color = colors.warning, fontSize = 16.sp) }
        ActionButton("Save", primary = true, enabled = state.loaded,
                     modifier = Modifier.fillMaxWidth(), onClick = onSave)
    }

    if (pickDate) {
        val initial = runCatching { LocalDate.parse(state.birthdate) }.getOrNull()
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        onBirthdate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)
                                        .toLocalDate().toString())
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}
