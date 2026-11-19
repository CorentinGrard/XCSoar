// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.GeoPosition
import org.xcsoar.mobile.core.PlaneInfo
import org.xcsoar.mobile.core.RegionFiles
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.core.UnitSettings
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.Format

/** The first start's steps, in order. */
enum class WelcomeStep { UNITS, CREW, DATA }

/** A file of the data step and how its download goes. */
data class RegionFileState(
    val file: RepositoryFile,
    val chosen: Boolean = true,
    /** 0..1 while downloading, -1 when the size is unknown; null otherwise. */
    val progress: Float? = null,
    val done: Boolean = false,
    val error: String? = null,
)

data class WelcomeState(
    val step: WelcomeStep = WelcomeStep.UNITS,
    /** null until the core answered. */
    val units: UnitSettings? = null,
    /** The preset chosen on the units step; -1 for none. */
    val preset: Int = -1,
    val pilot: String = "",
    /** null while loading. */
    val planes: List<PlaneInfo>? = null,
    /** Path of the chosen plane. */
    val plane: String? = null,
    /** XCSoar's repository; null while it loads. */
    val repository: List<RepositoryFile>? = null,
    val repositoryError: String? = null,
    /** Lower case country code; null if none could be guessed. */
    val country: String? = null,
    val files: List<RegionFileState> = emptyList(),
    /** The country list is open (to choose the region by hand). */
    val choosingCountry: Boolean = false,
) {
    val downloading: Boolean get() = files.any { it.progress != null }

    /** Every chosen file is downloaded and loaded. */
    val downloaded: Boolean get() = files.any { it.done } && files.none { it.chosen && !it.done }
}

/**
 * The first start: units, the pilot and glider, then the map, airspace
 * and waypoints for where the phone is, from XCSoar's repository.
 * Every step can be left for later; the app then never asks again.
 *
 * @param index downloads the repository index; returns its path
 * @param download downloads a file into XCSoarData; returns its path
 * @param position the phone's position, if it knows one
 * @param regionHints country codes for where the phone is, best first
 */
class WelcomeViewModel(
    private val core: XcsoarCore,
    private val index: suspend () -> String,
    private val download: suspend (RepositoryFile, (Float) -> Unit) -> String,
    private val position: suspend () -> GeoPosition?,
    private val regionHints: () -> List<String>,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(WelcomeState())
    val state: StateFlow<WelcomeState> = stateFlow.asStateFlow()

    private var downloadJob: Job? = null
    private var started = false

    /** Read what the steps start from; once (the page calls it when shown). */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val units = retrying { core.units() }
            val crew = retrying { core.crew() }
            stateFlow.update {
                it.copy(units = units, pilot = crew?.pilot.orEmpty(),
                        preset = units?.let { u -> guessPreset(u) } ?: -1)
            }
        }
        loadPlanes()
    }

    /**
     * The phone's country's preset, by XCSoar's preset order
     * (src/Units/UnitsStore.cpp: European, British, American,
     * Australian), rather than the core's guess from the language: an
     * English phone in France flies in metres.
     */
    private fun guessPreset(units: UnitSettings): Int {
        val index = when (regionHints().firstOrNull()?.lowercase()) {
            null -> return units.preset
            "gb" -> 1
            "us" -> 2
            "au", "nz" -> 3
            else -> 0
        }
        return if (index < units.presets.size) index else units.preset
    }

    /** Read the planes again (after one was added); [select] chooses one. */
    fun loadPlanes(select: String? = null) {
        viewModelScope.launch {
            val list = retrying { core.planes() }
            stateFlow.update { s ->
                val planes = list?.planes.orEmpty()
                val keep = (select ?: s.plane)?.takeIf { p -> planes.any { it.path == p } }
                s.copy(planes = planes, plane = keep ?: list?.suggested?.path)
            }
        }
    }

    /** [read] until it answers: at app start, the core may still be starting. */
    private suspend fun <T> retrying(read: suspend () -> T?): T? {
        repeat(STARTUP_RETRIES) {
            try {
                read()?.let { return it }
            } catch (_: Exception) {
                // not started yet
            }
            delay(RETRY_MS)
        }
        return null
    }

    private companion object {
        const val RETRY_MS = 250L
        const val STARTUP_RETRIES = 40
    }

    fun choosePreset(index: Int) = stateFlow.update { it.copy(preset = index) }

    fun setPilot(name: String) = stateFlow.update { it.copy(pilot = name) }

    fun choosePlane(path: String) = stateFlow.update { it.copy(plane = path) }

    fun back() = stateFlow.update {
        when {
            it.choosingCountry -> it.copy(choosingCountry = false)
            it.step == WelcomeStep.DATA -> it.copy(step = WelcomeStep.CREW)
            else -> it.copy(step = WelcomeStep.UNITS)
        }
    }

    /** The step's choices take effect; the next step opens. */
    fun next() {
        val s = stateFlow.value
        when (s.step) {
            WelcomeStep.UNITS -> {
                stateFlow.update { it.copy(step = WelcomeStep.CREW) }
                if (s.preset >= 0 && s.preset != s.units?.preset)
                    viewModelScope.launch {
                        try {
                            core.applyUnitPreset(s.preset)
                            core.units()?.let { u ->
                                Format.units = u
                                stateFlow.update { it.copy(units = u) }
                            }
                        } catch (_: Exception) {
                            // the units page can still set them
                        }
                    }
            }
            WelcomeStep.CREW -> {
                stateFlow.update { it.copy(step = WelcomeStep.DATA) }
                viewModelScope.launch {
                    try {
                        core.setCrew(s.pilot.trim(), null)
                        s.plane?.let { core.activatePlane(it) }
                    } catch (_: Exception) {
                        // Aircraft & crew can still set them
                    }
                }
                if (s.repository == null) loadRepository()
            }
            WelcomeStep.DATA -> {}
        }
    }

    /** Download XCSoar's index and suggest the files for where the phone is. */
    fun loadRepository() {
        stateFlow.update { it.copy(repositoryError = null) }
        viewModelScope.launch {
            try {
                val files = core.repositoryFiles(index())
                    ?: error("downloads need the native core")
                val here = position()
                val country = RegionFiles.country(files, here, regionHints())
                stateFlow.update {
                    it.copy(repository = files, country = country,
                            files = suggestions(files, country, here),
                            choosingCountry = country == null)
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(repositoryError = e.message ?: e.toString())
                }
            }
        }
    }

    private suspend fun suggestions(files: List<RepositoryFile>, country: String?,
                                    here: GeoPosition? = null): List<RegionFileState> =
        if (country == null) emptyList()
        else RegionFiles.suggest(files, country, here ?: position()).files
            .map { RegionFileState(it) }

    fun openCountries(open: Boolean) = stateFlow.update { it.copy(choosingCountry = open) }

    /** The pilot chose the region by hand. */
    fun chooseCountry(country: String) {
        val files = stateFlow.value.repository ?: return
        viewModelScope.launch {
            val list = suggestions(files, country)
            stateFlow.update {
                it.copy(country = country, files = list, choosingCountry = false)
            }
        }
    }

    fun toggleFile(name: String) = stateFlow.update { s ->
        s.copy(files = s.files.map {
            if (it.file.name == name && !it.done) it.copy(chosen = !it.chosen) else it
        })
    }

    /** Download the chosen files one after the other and use each. */
    fun downloadChosen() {
        if (stateFlow.value.downloading) return
        downloadJob = viewModelScope.launch {
            for (entry in stateFlow.value.files.filter { it.chosen && !it.done }) {
                val name = entry.file.name
                fun change(f: (RegionFileState) -> RegionFileState) = stateFlow.update { s ->
                    s.copy(files = s.files.map { if (it.file.name == name) f(it) else it })
                }
                change { it.copy(progress = 0f, error = null) }
                try {
                    val path = download(entry.file) { p -> change { it.copy(progress = p) } }
                    entry.file.dataFile?.let { core.setDataFile(it, path) }
                    change { it.copy(progress = null, done = true) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    change { it.copy(progress = null) }
                    throw e
                } catch (e: Exception) {
                    change { it.copy(progress = null, error = e.message ?: e.toString()) }
                }
            }
        }
    }
}
