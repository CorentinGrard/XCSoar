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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.SoundOption
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
import kotlin.math.roundToInt

class VarioSoundViewModel(private val core: XcsoarCore) : ViewModel() {
    private val valuesFlow = MutableStateFlow<Map<SoundOption, Int>?>(null)
    /** The sound options; null until read, empty if the device has no vario sound. */
    val values: StateFlow<Map<SoundOption, Int>?> = valuesFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            valuesFlow.value = SoundOption.entries.mapNotNull { option ->
                try {
                    core.soundOption(option)?.let { option to it }
                } catch (_: Exception) {
                    null
                }
            }.toMap()
        }
    }

    /** Change one option; shown at once, read back if the core refuses. */
    fun set(option: SoundOption, value: Int) {
        valuesFlow.value = valuesFlow.value.orEmpty() + (option to value)
        viewModelScope.launch {
            try {
                core.setSoundOption(option, value)
            } catch (_: Exception) {
                refresh()
            }
        }
    }
}

@Composable
fun VarioSoundScreen(viewModel: VarioSoundViewModel, onBack: () -> Unit) {
    val values by viewModel.values.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    VarioSoundContent(values, viewModel::set, onBack)
}

/**
 * XCSoar's vario tone: on or off, its volume, vario or speed to fly in
 * cruise, and a quiet band around zero.
 */
@Composable
fun VarioSoundContent(
    values: Map<SoundOption, Int>?,
    onSet: (SoundOption, Int) -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    PageLayout {
        ScreenHeader("Vario sound", onBack)

        if (values != null && values.isEmpty()) {
            Text("This device has no sound output for the vario.",
                 color = colors.textSecondary, fontSize = 16.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))
            return@PageLayout
        }

        val on = values?.get(SoundOption.VARIO)?.let { it != 0 }
        SettingsGroup {
            SwitchRow("Vario sound", "A tone that rises with lift; also the speaker " +
                          "button on the map", on) {
                onSet(SoundOption.VARIO, if (it) 1 else 0)
            }
        }

        val volume = values?.get(SoundOption.VARIO_VOLUME)
        Stepper("volume", "Volume · %", volume?.toString() ?: Format.INVALID,
                "${volume ?: Format.INVALID} percent",
                canDecrease = volume != null && volume > 0,
                canIncrease = volume != null && volume < 100,
                onDecrease = { volume?.let { onSet(SoundOption.VARIO_VOLUME, step5(it, -1)) } },
                onIncrease = { volume?.let { onSet(SoundOption.VARIO_VOLUME, step5(it, +1)) } },
                modifier = Modifier.fillMaxWidth())
        Explanation("The phone's media volume applies on top.")

        Caption("Tone", Modifier.padding(start = 4.dp, top = 8.dp))
        Segmented(listOf("Vario", "Auto"),
                  selected = values?.get(SoundOption.VARIO_SWITCHING) ?: -1,
                  onSelect = { onSet(SoundOption.VARIO_SWITCHING, it) },
                  modifier = Modifier.fillMaxWidth())
        Explanation("Auto plays speed to fly in cruise. It needs an airspeed sensor; " +
                        "without one, it stays on the vario.")

        Caption("Quiet around zero", Modifier.padding(start = 4.dp, top = 8.dp))
        val deadBand = values?.get(SoundOption.VARIO_DEAD_BAND)?.let { it != 0 }
        SettingsGroup {
            SwitchRow("Dead band", "Silent while the lift is between the two values below",
                      deadBand) {
                onSet(SoundOption.VARIO_DEAD_BAND, if (it) 1 else 0)
            }
        }
        val enabled = deadBand == true
        DeadBandStepper("lower edge", "From", values?.get(SoundOption.VARIO_DEAD_BAND_MIN),
                        -500, 0, enabled) {
            onSet(SoundOption.VARIO_DEAD_BAND_MIN, it)
        }
        DeadBandStepper("upper edge", "To", values?.get(SoundOption.VARIO_DEAD_BAND_MAX),
                        0, 200, enabled) {
            onSet(SoundOption.VARIO_DEAD_BAND_MAX, it)
        }
    }
}

/** 5 % volume steps. */
private fun step5(value: Int, direction: Int) =
    ((value / 5 + direction) * 5).coerceIn(0, 100)

/** A dead band edge in cm/s, shown and stepped in the lift unit. */
@Composable
private fun DeadBandStepper(name: String, caption: String, cms: Int?, min: Int, max: Int,
                            enabled: Boolean, onChange: (Int) -> Unit) {
    val unit = Format.unit(UnitGroup.VERTICAL_SPEED).name
    val text = cms?.let { Format.vario(it / 100.0).text } ?: Format.INVALID
    fun step(direction: Int) = cms?.let {
        onChange((Format.stepVerticalSpeed(it / 100.0, direction) * 100).roundToInt()
                     .coerceIn(min, max))
    }
    Stepper(name, "$caption · $unit", text, "$text $unit",
            canDecrease = enabled && cms != null && cms > min,
            canIncrease = enabled && cms != null && cms < max,
            onDecrease = { step(-1) }, onIncrease = { step(+1) },
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f))
}
