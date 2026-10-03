// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.MapOption
import org.xcsoar.mobile.core.TerrainRamp
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun MapSettingsScreen(viewModel: MapSettingsViewModel, onBack: () -> Unit) {
    val options by viewModel.options.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    MapSettingsContent(options, viewModel::set, onBack)
}

/** Terrain, its colours, topography and the trail; saved in the profile. */
@Composable
fun MapSettingsContent(
    options: Map<MapOption, Int>,
    onSet: (MapOption, Int) -> Unit,
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
            Text("Map", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        SettingsGroup {
            SwitchRow("Terrain", "Shaded relief from the map file",
                      options[MapOption.TERRAIN]?.let { it != 0 }) {
                onSet(MapOption.TERRAIN, if (it) 1 else 0)
            }
            SwitchRow("Topography", "Roads, rivers, lakes and towns",
                      options[MapOption.TOPOGRAPHY]?.let { it != 0 }) {
                onSet(MapOption.TOPOGRAPHY, if (it) 1 else 0)
            }
        }

        Caption("Terrain colours", Modifier.padding(start = 4.dp))
        SettingsGroup(Modifier.selectableGroup()) {
            for (ramp in TerrainRamp.entries)
                ChoiceRow(ramp.label, options[MapOption.TERRAIN_RAMP] == ramp.code) {
                    onSet(MapOption.TERRAIN_RAMP, ramp.code)
                }
        }

        Caption("Trail", Modifier.padding(start = 4.dp))
        Row(Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(colors.panel, RoundedCornerShape(12.dp))
                .selectableGroup()) {
            for ((label, value) in listOf("Off" to 0, "Short" to 2, "Long" to 1, "Full" to 3)) {
                val selected = options[MapOption.TRAIL] == value
                Box(Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected, role = Role.RadioButton) {
                            onSet(MapOption.TRAIL, value)
                        }
                        .padding(4.dp)
                        .background(if (selected) colors.selected else Color.Transparent,
                                    RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) colors.onSelected else colors.text,
                         fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .height(56.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier
                .size(22.dp)
                .border(2.dp, if (selected) colors.selected else colors.textSecondary, CircleShape)
                .padding(5.dp)
                .background(if (selected) colors.selected else Color.Transparent, CircleShape))
        Text(label, color = colors.text, fontSize = 17.sp)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MapSettingsPreview() {
    XcsTheme(dark = false) {
        MapSettingsContent(mapOf(MapOption.TERRAIN to 1, MapOption.TERRAIN_RAMP to 11,
                                 MapOption.TOPOGRAPHY to 1, MapOption.TRAIL to 2), { _, _ -> }, {})
    }
}
