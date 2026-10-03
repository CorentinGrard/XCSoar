// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.crew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.PlaneInfo
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * Choose the plane (and the co-pilot of a two-seater) before flying.
 * Shown when the app opens; [onBack] is null there, so the pilot has
 * to choose (or skip, when there is no plane yet).
 */
@Composable
fun CrewScreen(
    viewModel: CrewViewModel,
    onEditPlane: (PlaneInfo?) -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    if (onBack != null)
        BackHandler(onBack = onBack)
    CrewContent(state, viewModel::select, viewModel::selectCopilot, viewModel::addCopilot,
                onEditPlane, onConfirm = { viewModel.confirm(onDone) },
                onSkip = onDone, onBack = onBack)
}

@Composable
fun CrewContent(
    state: CrewState,
    onSelect: (String) -> Unit,
    onSelectCopilot: (String) -> Unit,
    onAddCopilot: (String) -> Unit,
    onEditPlane: (PlaneInfo?) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Aircraft & crew", onBack)

        val planes = state.planes
        Caption("Aircraft", Modifier.padding(start = 4.dp))
        if (planes != null && planes.isEmpty())
            Text("Add the aircraft you fly: its registration, its polar and its " +
                 "WeGlide type.", color = colors.textSecondary, fontSize = 16.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (plane in planes.orEmpty())
                PlaneRow(plane, selected = plane.path == state.selected,
                         lastFlown = plane.path == state.lastFlown,
                         onSelect = { onSelect(plane.path) },
                         onEdit = { onEditPlane(plane) })
        }
        ActionButton("Add aircraft", outlined = true, modifier = Modifier.fillMaxWidth()) {
            onEditPlane(null)
        }

        val plane = state.selectedPlane
        if (plane != null && plane.doubleSeater)
            CopilotChoice(state, onSelectCopilot, onAddCopilot)

        state.error?.let { Text(it, color = colors.warning, fontSize = 16.sp) }

        if (plane != null) {
            val crew = if (plane.doubleSeater && state.copilot.isNotEmpty())
                " with ${state.copilot}" else ""
            ActionButton("Fly ${plane.registration}$crew", primary = true,
                         modifier = Modifier.fillMaxWidth(), onClick = onConfirm)
        } else if (onBack == null && planes != null) {
            // nothing to choose yet: the pilot may still fly
            ActionButton("Not now", outlined = true, modifier = Modifier.fillMaxWidth(),
                         onClick = onSkip)
        }
    }
}

@Composable
private fun PlaneRow(
    plane: PlaneInfo,
    selected: Boolean,
    lastFlown: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(colors.panel, shape)
            .border(if (selected) 3.dp else 1.dp,
                    if (selected) colors.selected else colors.panelBorder, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listOf(plane.registration, plane.competitionId)
                     .filter { it.isNotEmpty() }.joinToString(" · "),
                 color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                plane.type.ifEmpty { plane.polarName }.ifEmpty { null },
                if (plane.doubleSeater) "two seats" else null,
                if (lastFlown) "last flown" else null)
            Text(details.joinToString(" · "), color = colors.textSecondary, fontSize = 15.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ActionButton("Edit", onClick = onEdit)
    }
}

/** Solo, a co-pilot flown with before, or a new one. */
@Composable
private fun CopilotChoice(
    state: CrewState,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    val colors = XcsTheme.colors
    Caption("Co-pilot", Modifier.padding(start = 4.dp))
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .selectableGroup()) {
        CopilotRow("Solo", state.copilot.isEmpty()) { onSelect("") }
        for (name in state.copilots)
            CopilotRow(name, state.copilot == name) { onSelect(name) }
    }

    var name by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InputField(name, { name = it }, "New co-pilot", Modifier.weight(1f),
                   capitalization = KeyboardCapitalization.Words)
        ActionButton("Add", outlined = true, enabled = name.isNotBlank()) {
            onAdd(name)
            name = ""
        }
    }
}

@Composable
private fun CopilotRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp)
            .background(if (selected) colors.selected else Color.Transparent,
                        RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (selected) colors.onSelected else colors.text, fontSize = 17.sp,
             fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
