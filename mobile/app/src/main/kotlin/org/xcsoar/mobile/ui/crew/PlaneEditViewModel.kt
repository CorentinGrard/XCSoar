// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.crew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.PlaneEdit
import org.xcsoar.mobile.core.PlaneInfo
import org.xcsoar.mobile.core.WeGlideAircraft
import org.xcsoar.mobile.core.XcsoarCore

/** A plane being created or changed. */
data class PlaneEditState(
    /** Empty for a new plane. */
    val path: String = "",
    val registration: String = "",
    val competitionId: String = "",
    val type: String = "",
    /** The polar's name; for a new plane, empty until one is chosen. */
    val polarName: String = "",
    /** Index into [polars], -1 to keep the plane's polar. */
    val polar: Int = -1,
    val weGlideType: Int = 0,
    val weGlideName: String = "",
    val doubleSeater: Boolean = false,
    val polars: List<String> = emptyList(),
    /** Results of the WeGlide aircraft search. */
    val weGlideResults: List<WeGlideAircraft> = emptyList(),
    /** WeGlide's list has been downloaded (it can be searched). */
    val weGlideListLoaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isNew get() = path.isEmpty()
    val canSave get() = registration.isNotBlank() && (!isNew || polar >= 0) && !busy
}

class PlaneEditViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(PlaneEditState())
    val state: StateFlow<PlaneEditState> = stateFlow.asStateFlow()

    /** Start editing [plane], or a new one. */
    fun open(plane: PlaneInfo?) {
        stateFlow.value = PlaneEditState(
            path = plane?.path.orEmpty(),
            registration = plane?.registration.orEmpty(),
            competitionId = plane?.competitionId.orEmpty(),
            type = plane?.type.orEmpty(),
            polarName = plane?.polarName.orEmpty(),
            weGlideType = plane?.weGlideType ?: 0,
            doubleSeater = plane?.doubleSeater ?: false)
        viewModelScope.launch {
            val polars = core.polars()
            val all = core.searchWeGlideAircraft("", Int.MAX_VALUE)
            stateFlow.update { s ->
                s.copy(polars = polars, weGlideListLoaded = all.isNotEmpty(),
                       weGlideName = all.firstOrNull { it.id == s.weGlideType }?.name.orEmpty())
            }
        }
    }

    fun setRegistration(value: String) =
        stateFlow.update { it.copy(registration = value.uppercase()) }

    fun setCompetitionId(value: String) =
        stateFlow.update { it.copy(competitionId = value.uppercase().take(3)) }

    fun setType(value: String) = stateFlow.update { it.copy(type = value) }

    fun setDoubleSeater(value: Boolean) = stateFlow.update { it.copy(doubleSeater = value) }

    /** One of the built-in polars; it also names an untyped plane. */
    fun pickPolar(index: Int) = stateFlow.update {
        val name = it.polars[index]
        it.copy(polar = index, polarName = name, type = it.type.ifBlank { name })
    }

    fun searchWeGlide(query: String) {
        viewModelScope.launch {
            val results = core.searchWeGlideAircraft(query, 100)
            stateFlow.update { it.copy(weGlideResults = results) }
        }
    }

    /** Download WeGlide's aircraft list, then search it. */
    fun downloadWeGlideList(query: String) {
        stateFlow.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                core.updateWeGlideAircraftList()
                val results = core.searchWeGlideAircraft(query, 100)
                stateFlow.update {
                    it.copy(weGlideListLoaded = true, weGlideResults = results, busy = false)
                }
            } catch (e: Exception) {
                stateFlow.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    /** WeGlide's type; its seats come with it (from WeGlide). */
    fun pickWeGlide(aircraft: WeGlideAircraft) {
        stateFlow.update {
            it.copy(weGlideType = aircraft.id, weGlideName = aircraft.name,
                    type = it.type.ifBlank { aircraft.name }, busy = true, error = null)
        }
        viewModelScope.launch {
            try {
                val detail = core.weGlideAircraft(aircraft.id)
                stateFlow.update { it.copy(doubleSeater = detail.doubleSeater, busy = false) }
            } catch (e: Exception) {
                // the pilot can still say how many seats it has
                stateFlow.update {
                    it.copy(busy = false, error = "Seats unknown: ${e.message}")
                }
            }
        }
    }

    /** Save; [onSaved] gets the plane's path. */
    fun save(onSaved: (String) -> Unit) {
        val s = stateFlow.value
        if (!s.canSave) return
        stateFlow.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val path = core.savePlane(PlaneEdit(
                    s.path, s.registration.trim(), s.competitionId.trim(), s.type.trim(),
                    s.polar, s.weGlideType, s.doubleSeater))
                stateFlow.update { it.copy(busy = false) }
                onSaved(path)
            } catch (e: Exception) {
                stateFlow.update { it.copy(busy = false, error = "Could not save the aircraft") }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val path = stateFlow.value.path
        if (path.isEmpty()) return
        viewModelScope.launch {
            try {
                core.deletePlane(path)
                onDeleted()
            } catch (_: Exception) {
                stateFlow.update {
                    it.copy(error = "Choose another aircraft before deleting this one")
                }
            }
        }
    }
}
