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
import org.xcsoar.mobile.core.PlaneDetails
import org.xcsoar.mobile.core.PlaneEdit
import org.xcsoar.mobile.core.PlaneInfo
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
    /** Masses, ballast and limits; null until the plane has a polar. */
    val details: PlaneDetails? = null,
    val polars: List<String> = emptyList(),
    /** WeGlide's types and XCSoar's polars ([aircraftModels]). */
    val models: List<AircraftModel> = emptyList(),
    /** The models matching the pilot's search. */
    val modelResults: List<AircraftModel> = emptyList(),
    /** WeGlide's list has been downloaded (its types are models). */
    val weGlideListLoaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isNew get() = path.isEmpty()
    /** A plane needs a polar: the model's, or one the pilot chose. */
    val canSave get() = registration.isNotBlank() && polarName.isNotEmpty() && !busy
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
            doubleSeater = plane?.doubleSeater ?: false,
            details = plane?.details)
        viewModelScope.launch {
            val polars = core.polars()
            val all = core.searchWeGlideAircraft("", Int.MAX_VALUE)
            val models = aircraftModels(all, polars)
            stateFlow.update { s ->
                s.copy(polars = polars, models = models, modelResults = models,
                       weGlideListLoaded = all.isNotEmpty(),
                       weGlideName = all.firstOrNull { it.id == s.weGlideType }?.name.orEmpty())
            }
        }
    }

    fun setRegistration(value: String) =
        stateFlow.update { it.copy(registration = value.uppercase()) }

    fun setCompetitionId(value: String) =
        stateFlow.update { it.copy(competitionId = value.uppercase().take(3)) }

    fun setDoubleSeater(value: Boolean) = stateFlow.update { it.copy(doubleSeater = value) }

    /** One of the built-in polars, when the model has none (or another). */
    fun pickPolar(index: Int) {
        stateFlow.update {
            val name = it.polars[index]
            it.copy(polar = index, polarName = name, type = it.type.ifBlank { name })
        }
        loadPolarDetails(index)
    }

    /** A detail changed by the pilot. */
    fun setDetails(change: (PlaneDetails) -> PlaneDetails) =
        stateFlow.update { s -> s.copy(details = s.details?.let(change)) }

    /**
     * The values a new polar brings, like XCSoar's PlaneGlue::ApplyPolar():
     * the plane keeps its dump time, and its speed, area and handicap
     * where the polar has none.
     */
    private fun loadPolarDetails(index: Int) {
        viewModelScope.launch {
            val info = try {
                core.polar(index)
            } catch (_: Exception) {
                null
            } ?: return@launch
            stateFlow.update { s ->
                if (s.polar != index) return@update s
                val old = s.details
                s.copy(details = PlaneDetails(
                    emptyMass = info.emptyMass,
                    referenceMass = info.referenceMass,
                    maxBallast = info.maxBallast,
                    dumpTime = old?.dumpTime ?: 120,
                    maxSpeed = info.maxSpeed.takeIf { it > 0 } ?: old?.maxSpeed ?: 0.0,
                    wingArea = info.wingArea.takeIf { it > 0 } ?: old?.wingArea ?: 0.0,
                    handicap = info.handicap.takeIf { it > 0 } ?: old?.handicap ?: 100))
            }
        }
    }

    fun searchModels(query: String) =
        stateFlow.update { it.copy(modelResults = it.models.search(query)) }

    /** Download WeGlide's aircraft list: its types become models. */
    fun downloadWeGlideList(query: String) {
        stateFlow.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                core.updateWeGlideAircraftList()
                val all = core.searchWeGlideAircraft("", Int.MAX_VALUE)
                stateFlow.update {
                    val models = aircraftModels(all, it.polars)
                    it.copy(weGlideListLoaded = true, models = models,
                            modelResults = models.search(query), busy = false)
                }
            } catch (e: Exception) {
                stateFlow.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    /**
     * The plane is a [model]: its type, XCSoar's polar for it (none: the
     * pilot chooses one), its WeGlide type and, from WeGlide, its seats.
     */
    fun pickModel(model: AircraftModel) {
        val weGlide = model.weGlide
        stateFlow.update {
            it.copy(type = model.name,
                    polar = model.polar ?: -1,
                    polarName = model.polar?.let { i -> it.polars[i] }.orEmpty(),
                    weGlideType = weGlide?.id ?: 0, weGlideName = weGlide?.name.orEmpty(),
                    busy = weGlide != null, error = null)
        }
        model.polar?.let(::loadPolarDetails)
        if (weGlide == null) return
        viewModelScope.launch {
            try {
                val detail = core.weGlideAircraft(weGlide.id)
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
                s.details?.let { core.setPlaneDetails(path, it) }
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
