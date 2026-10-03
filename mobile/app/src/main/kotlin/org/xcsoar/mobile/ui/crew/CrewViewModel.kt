// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.crew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.PlaneInfo
import org.xcsoar.mobile.core.XcsoarCore

/** What the aircraft and crew picker shows. */
data class CrewState(
    /** null while loading. */
    val planes: List<PlaneInfo>? = null,
    val lastFlown: String = "",
    /** Path of the chosen plane. */
    val selected: String? = null,
    /** Co-pilots to choose from, most recent first. */
    val copilots: List<String> = emptyList(),
    /** The chosen co-pilot; empty: solo. */
    val copilot: String = "",
    val error: String? = null,
) {
    val selectedPlane get() = planes?.firstOrNull { it.path == selected }
}

/**
 * The plane and, for a two-seater, the co-pilot of the next flight.
 * Starts with the last plane flown and the last co-pilot.
 */
class CrewViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(CrewState())
    val state: StateFlow<CrewState> = stateFlow.asStateFlow()

    /** Read the planes again (after editing one); keeps the choice. */
    fun load(select: String? = null) {
        viewModelScope.launch {
            // at app start, the core may still be starting
            var list = try { core.planes() } catch (_: Exception) { null }
            for (attempt in 1..STARTUP_RETRIES) {
                if (list != null) break
                delay(RETRY_MS)
                list = try { core.planes() } catch (_: Exception) { null }
            }
            val crew = try { core.crew() } catch (_: Exception) { null }
            stateFlow.update { s ->
                val planes = list?.planes.orEmpty()
                val keep = (select ?: s.selected)?.takeIf { p -> planes.any { it.path == p } }
                s.copy(planes = planes,
                       lastFlown = list?.lastFlown.orEmpty(),
                       selected = keep ?: list?.suggested?.path,
                       copilots = if (s.planes == null) crew?.copilots.orEmpty()
                                  else (s.copilots + crew?.copilots.orEmpty()).distinct(),
                       copilot = if (s.planes == null) crew?.copilot.orEmpty() else s.copilot)
            }
        }
    }

    companion object {
        private const val RETRY_MS = 250L
        private const val STARTUP_RETRIES = 40
    }

    fun select(path: String) = stateFlow.update { it.copy(selected = path) }

    fun selectCopilot(name: String) = stateFlow.update { it.copy(copilot = name) }

    /** A new co-pilot: listed first and chosen. */
    fun addCopilot(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        stateFlow.update {
            it.copy(copilots = listOf(trimmed) + (it.copilots - trimmed), copilot = trimmed)
        }
    }

    /** Fly the chosen plane with the chosen crew; [onDone] when saved. */
    fun confirm(onDone: () -> Unit) {
        val s = stateFlow.value
        val plane = s.selectedPlane ?: return
        viewModelScope.launch {
            try {
                core.activatePlane(plane.path)
                // a single-seater flies solo, whatever was chosen before
                core.setCrew(null, if (plane.doubleSeater) s.copilot else "")
                onDone()
            } catch (e: Exception) {
                stateFlow.update { it.copy(error = e.message ?: "Could not save") }
            }
        }
    }
}
