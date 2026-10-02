// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.units

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.UnitSettings
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun UnitsScreen(viewModel: UnitsViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    UnitsContent(Format.units, viewModel::preset, viewModel::set, onBack)
}

/** The groups the pilot sets, in XCSoar's units panel order. */
private val GROUPS = listOf(
    UnitGroup.HORIZONTAL_SPEED to "Aircraft and wind speed",
    UnitGroup.DISTANCE to "Distance",
    UnitGroup.VERTICAL_SPEED to "Lift",
    UnitGroup.ALTITUDE to "Altitude",
    UnitGroup.TASK_SPEED to "Task speed",
    UnitGroup.WING_LOADING to "Wing loading",
    UnitGroup.MASS to "Mass",
    UnitGroup.PRESSURE to "Pressure",
    UnitGroup.TEMPERATURE to "Temperature",
)

/** XCSoar's presets and a unit per group; applies at once. */
@Composable
fun UnitsContent(
    units: UnitSettings,
    onPreset: (Int) -> Unit,
    onSet: (UnitGroup, Int) -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier
                    .height(56.dp)
                    .widthIn(min = 96.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) {
                Text("Back", color = colors.text, fontSize = 16.sp,
                     fontWeight = FontWeight.SemiBold)
            }
            Text("Units", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        if (units.presets.isNotEmpty()) {
            Caption("Preset", Modifier.padding(start = 4.dp))
            Segments(units.presets.mapIndexed { i, p -> i to p.name }, units.preset, onPreset)
        }

        for ((group, label) in GROUPS) {
            val choices = units.choices(group)
            if (choices.size < 2)
                continue
            Caption(label, Modifier.padding(start = 4.dp))
            Segments(choices.map { it.unit to it.name }, units.unitOf(group).unit) {
                onSet(group, it)
            }
        }
    }
}

/** One row of choices; the selected one filled. */
@Composable
private fun Segments(choices: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .selectableGroup()) {
        for ((value, label) in choices) {
            val isSelected = value == selected
            Box(Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(isSelected, role = Role.RadioButton) { onSelect(value) }
                    .padding(4.dp)
                    .background(if (isSelected) colors.selected else Color.Transparent,
                                RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center) {
                Text(label, color = if (isSelected) colors.onSelected else colors.text,
                     fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}
