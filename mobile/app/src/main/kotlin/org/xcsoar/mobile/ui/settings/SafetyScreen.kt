// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.xcsoar.mobile.core.SafetyOption
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale
import kotlin.math.roundToInt

class SafetyViewModel(private val core: XcsoarCore) : ViewModel() {
    private val valuesFlow = MutableStateFlow<Map<SafetyOption, Double>>(emptyMap())
    /** The margins read from the core (SI units); missing until read. */
    val values: StateFlow<Map<SafetyOption, Double>> = valuesFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            valuesFlow.value = SafetyOption.entries.mapNotNull { option ->
                try {
                    core.safetyOption(option)?.let { option to it }
                } catch (_: Exception) {
                    null
                }
            }.toMap()
        }
    }

    /** Change one margin; shown at once, read back if the core refuses. */
    fun set(option: SafetyOption, value: Double) {
        valuesFlow.value = valuesFlow.value + (option to value)
        viewModelScope.launch {
            try {
                core.setSafetyOption(option, value)
            } catch (_: Exception) {
                refresh()
            }
        }
    }
}

@Composable
fun SafetyScreen(viewModel: SafetyViewModel, onBack: () -> Unit) {
    val values by viewModel.values.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    SafetyContent(values, viewModel::set, onBack)
}

/**
 * XCSoar's safety factors: the heights the glide computer keeps above
 * the field and the terrain, the MacCready of reach and arrival
 * heights, the speed-to-fly risk factor, the order of the alternates
 * and the turn back marker.
 */
@Composable
fun SafetyContent(
    values: Map<SafetyOption, Double>,
    onSet: (SafetyOption, Double) -> Unit,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader("Safety heights", onBack)

        Caption("Margins", Modifier.padding(start = 4.dp))
        HeightStepper("arrival height", "Arrival height",
                      values[SafetyOption.ARRIVAL_HEIGHT], 2000.0) {
            onSet(SafetyOption.ARRIVAL_HEIGHT, it)
        }
        Explanation("Above the field when you land there: arrival heights and reach use it.")
        HeightStepper("terrain clearance", "Terrain clearance",
                      values[SafetyOption.TERRAIN_HEIGHT], 1000.0) {
            onSet(SafetyOption.TERRAIN_HEIGHT, it)
        }
        Explanation("Kept between the glide path and the terrain on final glide.")

        Caption("Glide computer", Modifier.padding(start = 4.dp, top = 8.dp))
        val mc = values[SafetyOption.MC]
        val mcUnit = Format.unit(UnitGroup.VERTICAL_SPEED).name
        val mcText = mc?.let { Format.macCready(it).text } ?: Format.INVALID
        Stepper("safety MacCready", "Safety MacCready · $mcUnit", mcText, "$mcText $mcUnit",
                canDecrease = mc != null && mc > 0, canIncrease = mc != null && mc < 10,
                onDecrease = {
                    mc?.let { onSet(SafetyOption.MC, Format.stepVerticalSpeed(it, -1)
                        .coerceIn(0.0, 10.0)) }
                },
                onIncrease = {
                    mc?.let { onSet(SafetyOption.MC, Format.stepVerticalSpeed(it, +1)
                        .coerceIn(0.0, 10.0)) }
                },
                modifier = Modifier.fillMaxWidth())
        Explanation("The MacCready of the reach and of the arrival heights at fields.")

        val risk = values[SafetyOption.RISK_FACTOR]
        val riskText = risk?.let { String.format(Locale.ROOT, "%.1f", it) } ?: Format.INVALID
        Stepper("risk factor", "Speed to fly risk factor", riskText, riskText,
                canDecrease = risk != null && risk > 0.05,
                canIncrease = risk != null && risk < 0.95,
                onDecrease = { risk?.let { onSet(SafetyOption.RISK_FACTOR, tenths(it - 0.1)) } },
                onIncrease = { risk?.let { onSet(SafetyOption.RISK_FACTOR, tenths(it + 0.1)) } },
                modifier = Modifier.fillMaxWidth())
        Explanation("Lowers MacCready for speed to fly as you get low: 0 off, 0.3 is " +
                        "common.")

        Caption("Alternates", Modifier.padding(start = 4.dp, top = 8.dp))
        Segmented(listOf("Nearest", "Along task", "Toward home"),
                  selected = values[SafetyOption.ALTERNATES]?.roundToInt() ?: -1,
                  onSelect = { onSet(SafetyOption.ALTERNATES, it.toDouble()) },
                  modifier = Modifier.fillMaxWidth())
        Explanation("How the alternates, the fields in reach, are sorted.")

        SettingsGroup {
            SwitchRow("Turn back marker",
                      "A green triangle on your track: the last point from which the " +
                          "target is still in reach",
                      values[SafetyOption.TURN_BACK_MARKER]?.let { it != 0.0 }) {
                onSet(SafetyOption.TURN_BACK_MARKER, if (it) 1.0 else 0.0)
            }
        }
    }
}

private fun tenths(value: Double) = ((value * 10).roundToInt() / 10.0).coerceIn(0.0, 1.0)

@Composable
private fun HeightStepper(name: String, caption: String, metres: Double?, max: Double,
                          onChange: (Double) -> Unit) {
    val value = Format.altitude(metres)
    Stepper(name, "$caption · ${value.unit}", value.text, "${value.text} ${value.unit}",
            canDecrease = metres != null && metres > 0,
            canIncrease = metres != null && metres < max,
            onDecrease = {
                metres?.let { onChange(Format.stepAltitude(it, -1).coerceIn(0.0, max)) }
            },
            onIncrease = {
                metres?.let { onChange(Format.stepAltitude(it, +1).coerceIn(0.0, max)) }
            },
            modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Explanation(text: String) {
    Text(text, color = XcsTheme.colors.textSecondary, fontSize = 15.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
}
