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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.WeGlideAircraft
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.theme.XcsTheme

private enum class Picker { NONE, POLAR, WEGLIDE }

/** Create or change a plane: XCSoar's plane dialog, with WeGlide's type. */
@Composable
fun PlaneEditScreen(
    viewModel: PlaneEditViewModel,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf(Picker.NONE) }

    when (picker) {
        Picker.NONE -> {
            BackHandler(onBack = onBack)
            PlaneEditContent(
                state,
                onRegistration = viewModel::setRegistration,
                onCompetitionId = viewModel::setCompetitionId,
                onType = viewModel::setType,
                onDoubleSeater = viewModel::setDoubleSeater,
                onPickPolar = { picker = Picker.POLAR },
                onPickWeGlide = { picker = Picker.WEGLIDE },
                onSave = { viewModel.save(onSaved) },
                onDelete = { viewModel.delete(onDeleted) },
                onBack = onBack)
        }
        Picker.POLAR -> {
            BackHandler { picker = Picker.NONE }
            SearchList("Polar", state.polars.withIndex().toList(), { it.value },
                       onBack = { picker = Picker.NONE }) {
                viewModel.pickPolar(it.index)
                picker = Picker.NONE
            }
        }
        Picker.WEGLIDE -> {
            BackHandler { picker = Picker.NONE }
            WeGlidePicker(state, viewModel::searchWeGlide, viewModel::downloadWeGlideList,
                          onBack = { picker = Picker.NONE }) {
                viewModel.pickWeGlide(it)
                picker = Picker.NONE
            }
        }
    }
}

@Composable
fun PlaneEditContent(
    state: PlaneEditState,
    onRegistration: (String) -> Unit,
    onCompetitionId: (String) -> Unit,
    onType: (String) -> Unit,
    onDoubleSeater: (Boolean) -> Unit,
    onPickPolar: () -> Unit,
    onPickWeGlide: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
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
        ScreenHeader(if (state.isNew) "New aircraft" else "Aircraft", onBack)

        InputField(state.registration, onRegistration, "Registration",
                   capitalization = KeyboardCapitalization.Characters)
        InputField(state.competitionId, onCompetitionId, "Competition ID",
                   capitalization = KeyboardCapitalization.Characters)
        InputField(state.type, onType, "Type")

        Caption("Polar", Modifier.padding(start = 4.dp))
        ChoiceRow(state.polarName.ifEmpty { "Choose a polar" }, onPickPolar)

        Caption("WeGlide", Modifier.padding(start = 4.dp))
        ChoiceRow(state.weGlideName.ifEmpty {
            if (state.weGlideType != 0) "Type ${state.weGlideType}" else "Choose the WeGlide type"
        }, onPickWeGlide)

        Row(Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(colors.panel, RoundedCornerShape(12.dp))
                .toggleable(state.doubleSeater, role = Role.Switch,
                            onValueChange = onDoubleSeater)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Two seats", color = colors.text, fontSize = 17.sp,
                     fontWeight = FontWeight.SemiBold)
                Text("Asks for the co-pilot before each flight", color = colors.textSecondary,
                     fontSize = 14.sp)
            }
            Switch(state.doubleSeater, onCheckedChange = null,
                   colors = SwitchDefaults.colors(checkedTrackColor = colors.selected))
        }

        state.error?.let { Text(it, color = colors.warning, fontSize = 16.sp) }

        ActionButton(if (state.busy) "…" else "Save", primary = true, enabled = state.canSave,
                     modifier = Modifier.fillMaxWidth(), onClick = onSave)
        if (!state.isNew)
            ActionButton("Delete aircraft", modifier = Modifier.fillMaxWidth(),
                         onClick = onDelete)
    }
}

@Composable
private fun ChoiceRow(text: String, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = colors.text, fontSize = 17.sp, modifier = Modifier.weight(1f),
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Change", color = colors.textSecondary, fontSize = 15.sp)
    }
}

/** A searchable list of choices. */
@Composable
private fun <T> SearchList(
    title: String,
    items: List<T>,
    label: (T) -> String,
    onBack: () -> Unit,
    header: @Composable () -> Unit = {},
    onSearch: ((String) -> Unit)? = null,
    onPick: (T) -> Unit,
) {
    val colors = XcsTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (onSearch != null) items
                else items.filter { label(it).contains(query, ignoreCase = true) }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(title, onBack)
        InputField(query, { query = it; onSearch?.invoke(it) }, "Search")
        header()
        LazyColumn(Modifier
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(14.dp))) {
            items(shown) { item ->
                Text(label(item), color = colors.text, fontSize = 17.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier
                         .fillMaxWidth()
                         .clickable(role = Role.Button) { onPick(item) }
                         .heightIn(min = 56.dp)
                         .padding(horizontal = 14.dp, vertical = 16.dp))
                HorizontalDivider(color = colors.panelBorder,
                                  modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

@Composable
private fun WeGlidePicker(
    state: PlaneEditState,
    onSearch: (String) -> Unit,
    onDownload: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (WeGlideAircraft) -> Unit,
) {
    LaunchedEffect(Unit) { onSearch("") }
    var lastQuery by rememberSaveable { mutableStateOf("") }
    SearchList("WeGlide type", state.weGlideResults, { it.name }, onBack,
               header = {
                   val colors = XcsTheme.colors
                   if (!state.weGlideListLoaded)
                       Text("Download WeGlide's list of aircraft once (internet needed).",
                            color = colors.textSecondary, fontSize = 15.sp)
                   state.error?.let { Text(it, color = colors.warning, fontSize = 15.sp) }
                   ActionButton(when {
                       state.busy -> "Downloading…"
                       state.weGlideListLoaded -> "Update the list"
                       else -> "Download the list"
                   }, outlined = true, enabled = !state.busy,
                       modifier = Modifier.fillMaxWidth()) { onDownload(lastQuery) }
               },
               onSearch = { lastQuery = it; onSearch(it) },
               onPick = onPick)
}
