// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel.Companion.BALLAST_STEP
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel.Companion.BUGS_STEP
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel.Companion.MAX_BUGS_PERCENT
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun FlightSetupScreen(viewModel: FlightSetupViewModel, onBack: () -> Unit) {
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    FlightSetupContent(setup, viewModel::changeBallast, viewModel::setBallast,
                       viewModel::changeBugs, onBack)
}

/** Water ballast, bugs and the resulting wing loading. */
@Composable
fun FlightSetupContent(
    setup: FlightSetup?,
    onChangeBallast: (Double) -> Unit,
    onSetBallast: (Double) -> Unit,
    onChangeBugs: (Int) -> Unit,
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
            TextButton("Back", onClick = onBack)
            Text("Flight setup", color = colors.text, fontSize = 24.sp,
                 fontWeight = FontWeight.Bold)
        }

        val ballastable = setup != null && setup.maxBallast > 0
        Caption("Water ballast", Modifier.padding(start = 4.dp))
        if (setup != null && !ballastable) {
            Text("This plane carries no water ballast.", color = colors.textSecondary,
                 fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            val ballast = setup?.let { Format.ballast(it.ballast).text } ?: Format.INVALID
            Stepper("ballast", "Ballast · l", ballast, "$ballast litres",
                    canDecrease = setup != null && setup.ballast > 0,
                    canIncrease = setup != null && setup.ballast < setup.maxBallast,
                    onDecrease = { onChangeBallast(-BALLAST_STEP) },
                    onIncrease = { onChangeBallast(+BALLAST_STEP) },
                    modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("Empty", enabled = setup != null, outlined = true,
                           modifier = Modifier.weight(1f)) { onSetBallast(0.0) }
                TextButton("Full", enabled = setup != null, outlined = true,
                           modifier = Modifier.weight(1f)) {
                    setup?.let { onSetBallast(it.maxBallast) }
                }
            }
        }

        Caption("Bugs", Modifier.padding(start = 4.dp))
        val percent = setup?.let { Format.bugsPercent(it.bugs) }
        val bugs = setup?.let { Format.bugs(it.bugs).text } ?: Format.INVALID
        Stepper("bugs", "Performance lost · %", bugs, "$bugs percent",
                canDecrease = percent != null && percent > 0,
                canIncrease = percent != null && percent < MAX_BUGS_PERCENT,
                onDecrease = { onChangeBugs(-BUGS_STEP) },
                onIncrease = { onChangeBugs(+BUGS_STEP) },
                modifier = Modifier.fillMaxWidth())
        Text("0 % is a clean glider; 50 % doubles the sink rate.",
             color = colors.textSecondary, fontSize = 15.sp,
             modifier = Modifier.padding(horizontal = 4.dp))

        setup?.wingLoading?.let {
            val value = Format.wingLoading(it)
            Row(Modifier
                    .fillMaxWidth()
                    .background(colors.panel, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Wing loading", color = colors.text, fontSize = 17.sp,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${value.text} ${value.unit}", color = colors.text,
                     style = XcsTheme.numberStyle, fontSize = 20.sp,
                     fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TextButton(
    label: String,
    enabled: Boolean = true,
    outlined: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(modifier
            .height(56.dp)
            .widthIn(min = 96.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (outlined) Modifier
                      .background(colors.control, shape)
                      .border(1.dp, colors.panelBorder, shape)
                  else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun FlightSetupPreview() {
    XcsTheme(dark = false) {
        FlightSetupContent(FlightSetup(ballast = 80.0, maxBallast = 150.0, bugs = 0.9,
                                       wingLoading = 38.2), {}, {}, {}, {})
    }
}
