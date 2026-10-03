// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.weather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.style.TextOverflow
import org.xcsoar.mobile.core.RaspInfo
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.WeatherStation
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.settings.Explanation
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.roundToInt

data class WeatherState(
    val stations: List<WeatherStation> = emptyList(),
    /** The code being typed. */
    val code: String = "",
    val updating: Boolean = false,
    val error: String? = null,
    /** The RASP forecast's fields and what the map shows. */
    val rasp: RaspInfo = RaspInfo(),
    /** The configured RASP file (a path), if any. */
    val raspFile: String? = null,
    val raspUpdating: Boolean = false,
    val raspError: String? = null,
) {
    val canAdd get() = WeatherStation.isValidCode(code) &&
        stations.none { it.code == code } && stations.size < WeatherStation.MAX_STATIONS
}

/**
 * XCSoar's weather: the RASP forecast on the map, and METAR and TAF of
 * the pilot's stations from NOAA.
 *
 * @param updateRasp downloads the named RASP file again and uses it
 */
class WeatherViewModel(
    private val core: XcsoarCore,
    private val updateRasp: suspend (name: String) -> Unit = {},
) : ViewModel() {
    private val stateFlow = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = stateFlow.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val stations = try { core.weatherStations() } catch (_: Exception) { emptyList() }
            stateFlow.update { it.copy(stations = stations) }
        }
        loadRasp()
    }

    private fun loadRasp() {
        viewModelScope.launch {
            val rasp = try { core.raspInfo() } catch (_: Exception) { RaspInfo() }
            val file = try { core.dataStatus().rasp.files.firstOrNull() } catch (_: Exception) { null }
            stateFlow.update { it.copy(rasp = rasp, raspFile = file) }
        }
    }

    /** Show field [index] (-1: none), at the same time if it has it. */
    fun selectRaspField(index: Int) {
        val rasp = stateFlow.value.rasp
        val time = rasp.time?.takeIf { rasp.fields.getOrNull(index)?.times?.contains(it) == true }
        setRasp(index, time)
    }

    /** The next or previous time of the field; before the first: now. */
    fun stepRaspTime(direction: Int) {
        val rasp = stateFlow.value.rasp
        val times = listOf(null) + (rasp.selected?.times ?: return)
        val i = (times.indexOf(rasp.time) + direction).coerceIn(0, times.lastIndex)
        setRasp(rasp.field, times[i])
    }

    private fun setRasp(field: Int, time: String?) {
        stateFlow.update { it.copy(rasp = it.rasp.copy(field = field, time = time)) }
        viewModelScope.launch {
            try {
                core.setRasp(field, time)
            } catch (_: Exception) {
                loadRasp()
            }
        }
    }

    /** Today's forecast: the same file, downloaded again. */
    fun updateRaspFile() {
        val path = stateFlow.value.raspFile ?: return
        if (stateFlow.value.raspUpdating) return
        stateFlow.update { it.copy(raspUpdating = true, raspError = null) }
        viewModelScope.launch {
            val error = try {
                updateRasp(path.substringAfterLast('/'))
                null
            } catch (e: Exception) {
                e.message ?: "Download failed"
            }
            stateFlow.update { it.copy(raspUpdating = false, raspError = error) }
            loadRasp()
        }
    }

    fun setCode(value: String) =
        stateFlow.update { it.copy(code = value.trim().uppercase().take(4), error = null) }

    /** Add the typed station and download its weather at once. */
    fun add() {
        val code = stateFlow.value.code
        if (!stateFlow.value.canAdd) return
        viewModelScope.launch {
            val added = try { core.addWeatherStation(code) } catch (_: Exception) { false }
            if (!added) {
                stateFlow.update { it.copy(error = "Could not add $code") }
                return@launch
            }
            stateFlow.update { it.copy(code = "") }
            load()
            update()
        }
    }

    fun remove(code: String) {
        viewModelScope.launch {
            try {
                core.removeWeatherStation(code)
            } catch (_: Exception) {
            }
            load()
        }
    }

    fun update() {
        if (stateFlow.value.updating) return
        stateFlow.update { it.copy(updating = true, error = null) }
        viewModelScope.launch {
            val error = try {
                core.updateWeather()
                null
            } catch (_: Exception) {
                "No weather received: check the internet connection and the codes"
            }
            val stations = try { core.weatherStations() } catch (_: Exception) { null }
            stateFlow.update {
                it.copy(stations = stations ?: it.stations, updating = false, error = error)
            }
        }
    }
}

@Composable
fun WeatherScreen(viewModel: WeatherViewModel, onDownloadRasp: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.load()
        viewModel.update()
    }
    BackHandler(onBack = onBack)
    WeatherContent(state, viewModel::setCode, viewModel::add, viewModel::remove,
                   viewModel::update, onBack,
                   onRaspField = viewModel::selectRaspField,
                   onRaspTime = viewModel::stepRaspTime,
                   onUpdateRasp = viewModel::updateRaspFile,
                   onDownloadRasp = onDownloadRasp)
}

@Composable
fun WeatherContent(
    state: WeatherState,
    onCode: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onUpdate: () -> Unit,
    onBack: () -> Unit,
    /** The code of the station shown whole; tests set it. */
    initiallyExpanded: String? = null,
    onRaspField: (Int) -> Unit = {},
    onRaspTime: (Int) -> Unit = {},
    onUpdateRasp: () -> Unit = {},
    onDownloadRasp: () -> Unit = {},
) {
    val colors = XcsTheme.colors
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    PageLayout(bottom = {
        ActionButton(if (state.updating) "Updating…" else "Update METAR and TAF", primary = true,
                     enabled = state.stations.isNotEmpty() && !state.updating,
                     modifier = Modifier.weight(1f), onClick = onUpdate)
    }) {
        ScreenHeader("Weather", onBack)

        RaspSection(state, onRaspField, onRaspTime, onUpdateRasp, onDownloadRasp)

        Caption("METAR and TAF", Modifier.padding(start = 4.dp, top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            InputField(state.code, onCode, "ICAO code, e.g. LFMT",
                       modifier = Modifier.weight(1f),
                       capitalization = KeyboardCapitalization.Characters)
            ActionButton("Add", enabled = state.canAdd, onClick = onAdd)
        }
        state.error?.let { Text(it, color = colors.warning, fontSize = 15.sp) }

        if (state.stations.isEmpty())
            Explanation("Add the airports whose METAR and TAF you want. XCSoar " +
                            "downloads them from NOAA (internet needed); the map " +
                            "shows the stations.")

        for (station in state.stations) {
            StationCard(station, expanded = expanded == station.code,
                        onToggle = {
                            expanded = if (expanded == station.code) null else station.code
                        },
                        onRemove = { onRemove(station.code) })
        }
    }
}

@Composable
private fun StationCard(
    station: WeatherStation,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = XcsTheme.colors
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(12.dp))
               .clickable(role = Role.Button, onClick = onToggle)
               .heightIn(min = 56.dp)
               .padding(horizontal = 14.dp, vertical = 10.dp),
           verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(station.code, color = colors.text, fontSize = 18.sp,
                     fontWeight = FontWeight.Bold)
                station.name?.let {
                    Text(it, color = colors.textSecondary, fontSize = 14.sp, maxLines = 1)
                }
            }
            Text("Remove", color = colors.textSecondary, fontSize = 15.sp,
                 modifier = Modifier
                     .clickable(role = Role.Button, onClick = onRemove)
                     .padding(8.dp))
        }

        if (!station.downloaded) {
            Text("Not downloaded yet", color = colors.textSecondary, fontSize = 15.sp)
            return@Column
        }

        summary(station)?.let {
            Text(it, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(listOfNotNull(station.metarTime?.let { "METAR ${utcTime(it)}" },
                           station.tafTime?.let { "TAF ${utcTime(it)}" })
                 .joinToString(" · "),
             color = colors.textSecondary, fontSize = 14.sp)

        if (expanded)
            Text(station.text ?: station.metar.orEmpty(), color = colors.text,
                 fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                 modifier = Modifier.padding(top = 4.dp))
    }
}

/** Wind, QNH and temperatures, in the pilot's units. */
private fun summary(s: WeatherStation): String? {
    val parts = mutableListOf<String>()
    val bearing = s.windBearing
    val windSpeed = s.windSpeed
    if (bearing != null && windSpeed != null) {
        val speed = Format.windSpeed(windSpeed)
        parts += if (windSpeed < 0.5) "Calm"
                 else "${bearing.roundToInt()}° ${speed.text} ${speed.unit}"
    }
    s.qnh?.let { val p = Format.pressure(it); parts += "QNH ${p.text} ${p.unit}" }
    s.temperature?.let { t ->
        val text = Format.temperature(t)
        parts += s.dewPoint?.let { "${text.text}/${Format.temperature(it).text} ${text.unit}" }
            ?: "${text.text} ${text.unit}"
    }
    if (s.cavok) parts += "CAVOK"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** "2026-10-03T18:30:00Z" → "18:30 UTC" */
internal fun utcTime(iso: String) =
    if (iso.length >= 16) "${iso.substring(11, 16)} UTC" else iso

/**
 * The RASP forecast on the map (RASPDialog): a field, a time, and
 * today's file.
 */
@Composable
private fun RaspSection(
    state: WeatherState,
    onField: (Int) -> Unit,
    onTime: (Int) -> Unit,
    onUpdate: () -> Unit,
    onDownload: () -> Unit,
) {
    val colors = XcsTheme.colors
    val rasp = state.rasp
    Caption("Forecast on the map (RASP)", Modifier.padding(start = 4.dp))

    if (state.raspFile == null) {
        Explanation("Thermals, wind and clouds forecast over the map, from " +
                        "thermalmap.info for many countries. New every day.")
        ActionButton("Download a forecast", outlined = true,
                     modifier = Modifier.fillMaxWidth(), onClick = onDownload)
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.raspFile.substringAfterLast('/'), color = colors.textSecondary,
             fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
             modifier = Modifier.weight(1f).padding(start = 4.dp))
        ActionButton(if (state.raspUpdating) "Downloading…" else "Today's",
                     outlined = true, enabled = !state.raspUpdating, onClick = onUpdate)
    }
    state.raspError?.let { Text(it, color = colors.warning, fontSize = 15.sp) }

    if (rasp.fields.isEmpty()) {
        Explanation("The file has no forecast: download today's.")
        return
    }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Off", rasp.field < 0) { onField(-1) }
        rasp.fields.forEachIndexed { i, field ->
            // thermalmap.info names its fields like "Windspeed_1000m"
            Chip(field.label.replace('_', ' '), rasp.field == i) { onField(i) }
        }
    }

    val field = rasp.selected ?: return
    val times = listOf(null) + field.times
    val index = times.indexOf(rasp.time)
    val text = rasp.time ?: "Now"
    Stepper("forecast time", "Forecast time · local", text, text,
            canDecrease = index > 0, canIncrease = index < times.lastIndex,
            onDecrease = { onTime(-1) }, onIncrease = { onTime(+1) },
            modifier = Modifier.fillMaxWidth())
    field.help?.let { Explanation(it) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Box(Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) colors.selected else colors.panel,
                        RoundedCornerShape(24.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) colors.onSelected else colors.text,
             fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
