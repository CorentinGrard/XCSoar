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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.TeamCodeInfo
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.ValueList
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.settings.Explanation
import org.xcsoar.mobile.ui.theme.XcsTheme

class TeamCodeViewModel(private val core: XcsoarCore) : ViewModel() {
    private val infoFlow = MutableStateFlow<TeamCodeInfo?>(null)
    /** The team code dialog's values; null until read. */
    val info: StateFlow<TeamCodeInfo?> = infoFlow.asStateFlow()

    /** Reads it once a second while the page shows (the codes move). */
    suspend fun refreshWhileShown() {
        while (true) {
            read()
            delay(REFRESH_MS)
        }
    }

    private suspend fun read() {
        try {
            core.teamCode()?.let { infoFlow.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("XCSoar", "team code not read", e)
        }
    }

    /** The mate's code ("" forgets it); false if it is not a team code. */
    fun setMateCode(code: String): Boolean {
        val value = code.trim().uppercase()
        if (!isTeamCode(value)) return false
        infoFlow.value = infoFlow.value?.copy(mate = value)
        viewModelScope.launch {
            try {
                core.setTeamMateCode(value)
            } catch (e: Exception) {
                Log.w("XCSoar", "team code not set", e)
            }
            read()
        }
        return true
    }

    fun setReference(waypointId: Int?) {
        viewModelScope.launch {
            try {
                core.setTeamReference(waypointId)
            } catch (e: Exception) {
                Log.w("XCSoar", "team code reference not set", e)
            }
            read()
        }
    }

    companion object {
        const val REFRESH_MS = 1_000L

        /** Up to 7 letters and digits, as the core takes them; "" too. */
        fun isTeamCode(code: String) =
            code.length <= 7 && code.all { it in 'A'..'Z' || it in '0'..'9' }
    }
}

@Composable
fun TeamCodeScreen(viewModel: TeamCodeViewModel, onPickReference: () -> Unit,
                   onBack: () -> Unit) {
    val info by viewModel.info.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshWhileShown() }
    BackHandler(onBack = onBack)
    TeamCodeContent(info, viewModel::setMateCode, onPickReference, onBack)
}

/**
 * XCSoar's team code dialog: this glider's code to read out on the
 * radio, and where the team mate is from the code they read out.  Both
 * count from the same reference waypoint.
 */
@Composable
fun TeamCodeContent(
    info: TeamCodeInfo?,
    onMateCode: (String) -> Boolean,
    onPickReference: () -> Unit,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader("Team code", onBack)

        OwnCode(info)

        Caption("Team mate", Modifier.padding(start = 4.dp, top = 8.dp))
        ValueList(buildList {
            add("Mate code" to info?.mate.orEmpty())
            add("Range" to info?.range.orEmpty())
            add("Bearing" to info?.bearing.orEmpty())
            add("Rel. bearing" to info?.relativeBearing.orEmpty())
            info?.flarmLock?.takeIf { it.isNotEmpty() }?.let { add("Flarm Lock" to it) }
        })
        var code by rememberSaveable { mutableStateOf("") }
        var invalid by rememberSaveable { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InputField(code, {
                code = it
                invalid = false
            }, label = if (invalid) "Letters and digits, up to 7" else "Mate code",
                       capitalization = KeyboardCapitalization.Characters,
                       modifier = Modifier.weight(1f))
            ActionButton("Set code", outlined = true, enabled = info != null) {
                if (onMateCode(code)) code = "" else invalid = true
            }
        }

        Caption("Reference waypoint", Modifier.padding(start = 4.dp, top = 8.dp))
        ValueList(listOf("Waypoint" to info?.reference?.name.orEmpty()))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Set WP", outlined = true, enabled = info != null,
                         onClick = onPickReference)
            Explanation("Every glider of the team needs the same one.")
        }
    }
}

/** This glider's code in large type, or why there is none. */
@Composable
private fun OwnCode(info: TeamCodeInfo?) {
    val colors = XcsTheme.colors
    val own = info?.own.orEmpty()
    val reference = info?.reference
    val detail = when {
        info == null -> "Reading…"
        reference == null -> "Set a reference waypoint first"
        own.isEmpty() -> "Needs a GPS fix"
        else -> "From ${reference.name}"
    }
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .padding(16.dp)
               .clearAndSetSemantics {
                   contentDescription =
                       if (own.isNotEmpty()) "Own code ${own.toList().joinToString(" ")}, $detail"
                       else "No own code, $detail"
               }) {
        Caption("Own code")
        Text(own.ifEmpty { Format.INVALID }, color = colors.text, fontSize = 34.sp,
             fontWeight = FontWeight.Bold, style = XcsTheme.numberStyle)
        Text(detail, color = colors.textSecondary, fontSize = 15.sp)
    }
}
