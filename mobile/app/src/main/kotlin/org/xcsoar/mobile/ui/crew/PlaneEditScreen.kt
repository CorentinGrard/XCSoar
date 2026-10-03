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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.core.PlaneDetails
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.settings.Explanation
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale
import kotlin.math.round

private enum class Picker { NONE, MODEL, POLAR }

/**
 * Create or change a plane: the pilot picks the model, which brings its
 * polar, WeGlide type and seats (XCSoar's plane dialog in one choice).
 */
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
                onDoubleSeater = viewModel::setDoubleSeater,
                onDetails = viewModel::setDetails,
                onPickModel = { picker = Picker.MODEL },
                onPickPolar = { picker = Picker.POLAR },
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
        Picker.MODEL -> {
            BackHandler { picker = Picker.NONE }
            ModelPicker(state, viewModel::searchModels, viewModel::downloadWeGlideList,
                        onBack = { picker = Picker.NONE }) {
                viewModel.pickModel(it)
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
    onDoubleSeater: (Boolean) -> Unit,
    onDetails: ((PlaneDetails) -> PlaneDetails) -> Unit,
    onPickModel: () -> Unit,
    onPickPolar: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    PageLayout(bottom = {
        ActionButton(if (state.busy) "…" else "Save", primary = true, enabled = state.canSave,
                     modifier = Modifier.weight(1f), onClick = onSave)
    }) {
        ScreenHeader(if (state.isNew) "New aircraft" else "Aircraft", onBack)

        InputField(state.registration, onRegistration, "Registration",
                   capitalization = KeyboardCapitalization.Characters)
        InputField(state.competitionId, onCompetitionId, "Competition ID",
                   capitalization = KeyboardCapitalization.Characters)

        Caption("Model", Modifier.padding(start = 4.dp))
        ChoiceRow(state.type.ifEmpty { "Choose the model" }, onPickModel)

        // what the model brings; the polar can still be changed
        if (state.type.isNotEmpty()) {
            Caption("Polar", Modifier.padding(start = 4.dp))
            if (state.polarName.isNotEmpty())
                ChoiceRow(state.polarName, onPickPolar)
            else
                ChoiceRow("No polar for this model: choose one", onPickPolar,
                          color = colors.caution)
            Text(if (state.weGlideType != 0)
                     "WeGlide: ${state.weGlideName.ifEmpty { "type ${state.weGlideType}" }}"
                 else "Not a WeGlide type: flights upload without one",
                 color = colors.textSecondary, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }

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

        state.details?.let { MassesAndLimits(it, onDetails) }

        state.error?.let { Text(it, color = colors.warning, fontSize = 16.sp) }

        if (!state.isNew)
            ActionButton("Delete aircraft", modifier = Modifier.fillMaxWidth(),
                         onClick = onDelete)
    }
}

/**
 * XCSoar's plane details and polar masses; the polar fills them in, the
 * pilot corrects them for this glider.
 */
@Composable
private fun MassesAndLimits(
    details: PlaneDetails,
    onChange: ((PlaneDetails) -> PlaneDetails) -> Unit,
) {
    Caption("Masses and limits", Modifier.padding(start = 4.dp, top = 8.dp))
    MassStepper("empty mass", "Empty mass", details.emptyMass, 0.0) { m ->
        onChange { it.copy(emptyMass = m) }
    }
    Explanation("The rigged glider without pilots and water.")
    MassStepper("reference mass", "Polar reference mass", details.referenceMass, 1.0) { m ->
        onChange { it.copy(referenceMass = m) }
    }
    Explanation("The mass the polar was measured at.")

    val ballast = Format.ballast(details.maxBallast).text
    Stepper("water ballast", "Water ballast · l", ballast, "$ballast litres",
            canDecrease = details.maxBallast > 0, canIncrease = details.maxBallast < MAX_BALLAST,
            onDecrease = {
                onChange { it.copy(maxBallast = step(it.maxBallast, -5.0, 0.0, MAX_BALLAST)) }
            },
            onIncrease = {
                onChange { it.copy(maxBallast = step(it.maxBallast, +5.0, 0.0, MAX_BALLAST)) }
            },
            modifier = Modifier.fillMaxWidth())
    if (details.maxBallast > 0) {
        val dump = details.dumpTime.toString()
        Stepper("dump time", "Dump time · s", dump, "$dump seconds",
                canDecrease = details.dumpTime > 10, canIncrease = details.dumpTime < 300,
                onDecrease = {
                    onChange { it.copy(dumpTime = step(it.dumpTime, -10, 10, 300)) }
                },
                onIncrease = {
                    onChange { it.copy(dumpTime = step(it.dumpTime, +10, 10, 300)) }
                },
                modifier = Modifier.fillMaxWidth())
        Explanation("The time to dump full ballast.")
    }

    val speed = Format.speed(details.maxSpeed)
    val speedText = if (details.maxSpeed > 0) speed.text else "None"
    Stepper("speed limit", "Speed limit · ${speed.unit}", speedText,
            if (details.maxSpeed > 0) "$speedText ${speed.unit}" else speedText,
            canDecrease = details.maxSpeed > 0, canIncrease = details.maxSpeed < MAX_SPEED,
            onDecrease = {
                onChange {
                    it.copy(maxSpeed = Format.stepSpeed(it.maxSpeed, -1).coerceIn(0.0, MAX_SPEED))
                }
            },
            onIncrease = {
                onChange {
                    it.copy(maxSpeed = Format.stepSpeed(it.maxSpeed, +1).coerceIn(0.0, MAX_SPEED))
                }
            },
            modifier = Modifier.fillMaxWidth())
    Explanation("Speed to fly stays below it (VNE or VNO); none: no limit.")

    val area = String.format(Locale.ROOT, "%.1f", details.wingArea)
    Stepper("wing area", "Wing area · m²", area, "$area square metres",
            canDecrease = details.wingArea > 0, canIncrease = details.wingArea < 40,
            onDecrease = { onChange { it.copy(wingArea = step(it.wingArea, -0.1, 0.0, 40.0)) } },
            onIncrease = { onChange { it.copy(wingArea = step(it.wingArea, +0.1, 0.0, 40.0)) } },
            modifier = Modifier.fillMaxWidth())
    Explanation("For the wing loading.")

    val handicap = details.handicap.toString()
    Stepper("handicap", "Handicap · %", handicap, "$handicap percent",
            canDecrease = details.handicap > 50, canIncrease = details.handicap < 150,
            onDecrease = { onChange { it.copy(handicap = step(it.handicap, -1, 50, 150)) } },
            onIncrease = { onChange { it.copy(handicap = step(it.handicap, +1, 50, 150)) } },
            modifier = Modifier.fillMaxWidth())
    Explanation("The contest handicap, for the scores XCSoar computes.")
}

/* the ranges of XCSoar's plane dialogs */
private const val MAX_BALLAST = 500.0
private const val MAX_SPEED = 160.0
private const val MAX_MASS = 1000.0

/** [value] + [delta] to the tenth, within [min]..[max]. */
private fun step(value: Double, delta: Double, min: Double, max: Double) =
    (round((value + delta) * 10) / 10).coerceIn(min, max)

private fun step(value: Int, delta: Int, min: Int, max: Int) =
    (value + delta).coerceIn(min, max)

@Composable
private fun MassStepper(name: String, caption: String, kg: Double, min: Double,
                        onChange: (Double) -> Unit) {
    val value = Format.mass(kg)
    Stepper(name, "$caption · ${value.unit}", value.text, "${value.text} ${value.unit}",
            canDecrease = kg > min, canIncrease = kg < MAX_MASS,
            onDecrease = { onChange(Format.stepMass(kg, -1).coerceIn(min, MAX_MASS)) },
            onIncrease = { onChange(Format.stepMass(kg, +1).coerceIn(min, MAX_MASS)) },
            modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ChoiceRow(text: String, onClick: () -> Unit,
                     color: Color = XcsTheme.colors.text) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, fontSize = 17.sp, modifier = Modifier.weight(1f),
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
    detail: (T) -> String? = { null },
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
                Column(Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(item) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(label(item), color = colors.text, fontSize = 17.sp,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detail(item)?.let {
                        Text(it, color = colors.textSecondary, fontSize = 14.sp, maxLines = 1)
                    }
                }
                HorizontalDivider(color = colors.panelBorder,
                                  modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

/**
 * WeGlide's types and XCSoar's polars in one searchable list; each row
 * says what the model brings.
 */
@Composable
private fun ModelPicker(
    state: PlaneEditState,
    onSearch: (String) -> Unit,
    onDownload: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (AircraftModel) -> Unit,
) {
    LaunchedEffect(Unit) { onSearch("") }
    var lastQuery by rememberSaveable { mutableStateOf("") }
    SearchList("Model", state.modelResults, { it.name }, onBack,
               detail = { model ->
                   listOfNotNull("WeGlide".takeIf { model.weGlide != null },
                                 if (model.polar != null) "polar" else "no polar")
                       .joinToString(" · ")
               },
               header = {
                   val colors = XcsTheme.colors
                   if (!state.weGlideListLoaded)
                       Text("Download WeGlide's list once (internet needed) for every " +
                                "model, its WeGlide type and its seats.",
                            color = colors.textSecondary, fontSize = 15.sp)
                   state.error?.let { Text(it, color = colors.warning, fontSize = 15.sp) }
                   ActionButton(when {
                       state.busy -> "Downloading…"
                       state.weGlideListLoaded -> "Update WeGlide's list"
                       else -> "Download WeGlide's list"
                   }, outlined = true, enabled = !state.busy,
                       modifier = Modifier.fillMaxWidth()) { onDownload(lastQuery) }
               },
               onSearch = { lastQuery = it; onSearch(it) },
               onPick = onPick)
}
