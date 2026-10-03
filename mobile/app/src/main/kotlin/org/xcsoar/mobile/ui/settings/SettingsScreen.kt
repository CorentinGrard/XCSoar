// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPilot: () -> Unit,
    onUnits: () -> Unit,
    /** null when the core draws no map */
    onMap: (() -> Unit)?,
    onDataFiles: () -> Unit,
    onAirspaceAlerts: () -> Unit,
    onSafety: () -> Unit,
    onVarioSound: () -> Unit,
) {
    BackHandler(onBack = onBack)
    SettingsContent(onBack, onPilot, onUnits, onMap, onDataFiles, onAirspaceAlerts, onSafety,
                    onVarioSound)
}

/**
 * Everything set once on the ground, in one place.  Settings that are
 * planned but not built yet are listed, marked "Soon", so the page does
 * not change shape when they arrive.
 */
@Composable
fun SettingsContent(
    onBack: () -> Unit,
    onPilot: () -> Unit = {},
    onUnits: () -> Unit = {},
    onMap: (() -> Unit)? = {},
    onDataFiles: () -> Unit = {},
    onAirspaceAlerts: () -> Unit = {},
    onSafety: () -> Unit = {},
    onVarioSound: () -> Unit = {},
) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader("Settings", onBack)

        Section("Pilot and aircraft", listOf(
            Setting("Pilot & WeGlide", "Name, WeGlide ID", onPilot),
            Setting("Polar and masses")))
        Section("Display", listOf(
            Setting("Units", "Altitude, speed, lift…", onUnits),
            Setting("Map", "Terrain, topography, trail", onMap)))
        Section("Flying", listOf(
            Setting("Safety heights", "Arrival, terrain, safety MC", onSafety),
            Setting("Airspace", "Warnings, classes, sound", onAirspaceAlerts),
            Setting("Vario sound")))
        Section("Data and devices", listOf(
            Setting("Data files", "Map, airspace, waypoints", onDataFiles),
            Setting("Devices"),
            Setting("Profiles")))
    }
}

/** One setting; [onClick] null means it is not built yet ("Soon"). */
private class Setting(val title: String, val detail: String? = null,
                      val onClick: (() -> Unit)? = null)

@Composable
private fun Section(title: String, settings: List<Setting>) {
    val colors = XcsTheme.colors
    Caption(title, Modifier.padding(start = 4.dp, top = 8.dp))
    Column(Modifier
        .fillMaxWidth()
        .background(colors.panel, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp)) {
        settings.forEachIndexed { index, setting ->
            if (index > 0) HorizontalDivider(color = colors.panelBorder)
            SettingRow(setting)
        }
    }
}

@Composable
private fun SettingRow(setting: Setting) {
    val colors = XcsTheme.colors
    val onClick = setting.onClick
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null)
                      Modifier.clickable(role = Role.Button, onClick = onClick)
                  else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(setting.title, color = colors.text, fontSize = 17.sp,
             fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
             overflow = TextOverflow.Ellipsis)
        if (onClick == null)
            Text("SOON", color = colors.textSecondary, fontSize = 11.sp,
                 fontWeight = FontWeight.Bold, letterSpacing = 0.06.em,
                 modifier = Modifier
                     .border(1.dp, colors.panelBorder, RoundedCornerShape(8.dp))
                     .padding(horizontal = 6.dp, vertical = 2.dp))
        else if (setting.detail != null)
            Text(setting.detail, color = colors.textSecondary, fontSize = 14.sp, maxLines = 1)
    }
}
