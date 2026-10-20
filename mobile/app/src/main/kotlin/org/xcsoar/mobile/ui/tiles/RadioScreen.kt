// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.tiles

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.RadioInfo
import org.xcsoar.mobile.core.RadioSlot
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.settings.Explanation
import org.xcsoar.mobile.ui.theme.XcsTheme

class RadioViewModel(private val core: XcsoarCore) : ViewModel() {
    private val radioFlow = MutableStateFlow<RadioInfo?>(null)
    /** The active and standby frequencies; null until read. */
    val radio: StateFlow<RadioInfo?> = radioFlow.asStateFlow()

    suspend fun read() {
        try {
            core.radio()?.let { radioFlow.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("XCSoar", "radio not read", e)
        }
    }

    /** The radio panel's buttons: 5 kHz and 1 MHz steps. */
    fun offset(slot: RadioSlot, khz: Int) = change { core.offsetRadioFrequency(slot, khz) }

    /** A frequency typed in MHz; false if it is no VHF voice channel. */
    fun set(slot: RadioSlot, mhz: String): Boolean {
        val value = mhz.trim().replace(',', '.')
        if (!isChannel(value)) return false
        change { core.setRadioFrequency(slot, value) }
        return true
    }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("XCSoar", "radio not set", e)
            }
            read()
        }
    }

    companion object {
        /**
         * A VHF voice channel in MHz, as XCSoar's RadioFrequency takes
         * it: 118.000 to 136.990, on 5 kHz steps, without the 8.33 kHz
         * steps that are no channel (kHz % 25 == 20).
         */
        fun isChannel(mhz: String): Boolean {
            val khz = mhz.toDoubleOrNull()?.let { Math.round(it * 1000) } ?: return false
            return khz in 118_000 until 137_000 && khz % 5 == 0L && khz % 25 != 20L
        }
    }
}

@Composable
fun RadioScreen(viewModel: RadioViewModel, onBack: () -> Unit) {
    val radio by viewModel.radio.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.read() }
    BackHandler(onBack = onBack)
    RadioContent(radio, viewModel::offset, viewModel::set, onBack)
}

/**
 * XCSoar's active and standby radio frequencies (its radio InfoBoxes'
 * panel): moved in 5 kHz and 1 MHz steps, or typed in.  XCSoar sends
 * them to a connected radio.
 */
@Composable
fun RadioContent(
    radio: RadioInfo?,
    onOffset: (RadioSlot, Int) -> Unit,
    onSet: (RadioSlot, String) -> Boolean,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader("Radio", onBack)

        for (slot in RadioSlot.entries) {
            Caption(if (slot == RadioSlot.ACTIVE) "Active frequency" else "Standby frequency",
                    Modifier.padding(start = 4.dp, top = 8.dp))
            Frequency(slot, radio)
            val set = radio != null && radio[slot].frequency.isNotEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, khz) in OFFSETS)
                    ActionButton(label, outlined = true, enabled = set,
                                 modifier = Modifier.weight(1f)) { onOffset(slot, khz) }
            }
        }

        Caption("Set by hand", Modifier.padding(start = 4.dp, top = 8.dp))
        var mhz by rememberSaveable { mutableStateOf("") }
        var invalid by rememberSaveable { mutableStateOf(false) }
        InputField(mhz, {
            mhz = it
            invalid = false
        }, label = if (invalid) "No channel: 118.000 to 136.990 MHz" else "Frequency · MHz",
                   keyboardType = KeyboardType.Decimal)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (slot in RadioSlot.entries)
                ActionButton(if (slot == RadioSlot.ACTIVE) "Set active" else "Set standby",
                             outlined = true, enabled = radio != null && mhz.isNotBlank(),
                             modifier = Modifier.weight(1f)) {
                    if (onSet(slot, mhz)) mhz = "" else invalid = true
                }
        }
        Explanation("Sent to a connected radio.")
    }
}

/** "−1 MHz" … "+1 MHz", as upstream's offset buttons (5 kHz and 1000 kHz). */
private val OFFSETS = listOf("−1 MHz" to -1000, "−5 kHz" to -5, "+5 kHz" to 5,
                             "+1 MHz" to 1000)

/** "122.500 MHz" and its name, in large type. */
@Composable
private fun Frequency(slot: RadioSlot, radio: RadioInfo?) {
    val colors = XcsTheme.colors
    val channel = radio?.get(slot)
    val frequency = channel?.frequency.orEmpty()
    val name = when {
        radio == null -> "Reading…"
        frequency.isEmpty() -> "None: set one below"
        else -> channel?.name.orEmpty()
    }
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .padding(16.dp)
               .clearAndSetSemantics {
                   contentDescription = "${slot.name.lowercase()} frequency " +
                       (if (frequency.isEmpty()) "none" else "$frequency megahertz") +
                       (if (name.isEmpty()) "" else ", $name")
               }) {
        Row(verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(frequency.ifEmpty { Format.INVALID }, color = colors.text, fontSize = 34.sp,
                 fontWeight = FontWeight.Bold, style = XcsTheme.numberStyle)
            if (frequency.isNotEmpty())
                Text("MHz", color = colors.textSecondary, fontSize = 17.sp,
                     modifier = Modifier.padding(bottom = 6.dp))
        }
        // always a line: the buttons below do not move when it is empty
        Text(name.ifEmpty { " " }, color = colors.textSecondary, fontSize = 15.sp)
    }
}
