// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A stand-in for the real core: plays a synthetic flight (cruise, then
 * a thermal, repeated) once per [tick] of real time.  For UI previews,
 * UI tests and running the app without the native library.
 */
class FakeXcsoarCore(
    private val scope: CoroutineScope,
    private val tick: Long = 1000,
) : XcsoarCore {
    private val state = MutableStateFlow<FlightState?>(null)
    override val flightState: StateFlow<FlightState?> = state.asStateFlow()

    private val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CoreEvent> = eventFlow.asSharedFlow()

    private var job: Job? = null
    private var macCready = 1.0
    private var ballast = 0.0
    private var bugs = 1.0
    private var qnh: Double? = null

    override suspend fun start() {
        if (job != null) return
        job = scope.launch {
            var second = 0
            var wasCircling = false
            while (true) {
                val s = syntheticState(second, macCready).copy(ballast = ballast, bugs = bugs, qnh = qnh)
                state.value = s
                if (s.circling != wasCircling) {
                    eventFlow.emit(CoreEvent.GlideComputer(
                        if (s.circling) GlideComputerEvent.FLIGHTMODE_CLIMB
                        else GlideComputerEvent.FLIGHTMODE_CRUISE))
                    wasCircling = s.circling
                }
                second++
                delay(tick)
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
    }

    override suspend fun setMacCready(macCready: Double) {
        require(macCready in 0.0..5.0) { "MacCready $macCready" }
        this.macCready = macCready
        state.value = state.value?.copy(macCready = macCready)
    }

    override suspend fun setBallast(litres: Double) {
        require(litres in 0.0..FAKE_MAX_BALLAST) { "ballast $litres" }
        ballast = litres
        state.value = state.value?.copy(ballast = litres)
    }

    override suspend fun setBugs(bugs: Double) {
        require(bugs in 0.5..1.0) { "bugs $bugs" }
        this.bugs = bugs
        state.value = state.value?.copy(bugs = bugs)
    }

    override suspend fun setQnh(hpa: Double) {
        require(hpa in FlightState.MIN_QNH..FlightState.MAX_QNH) { "QNH $hpa" }
        qnh = hpa
        state.value = state.value?.copy(qnh = hpa)
    }

    private val mapOptions = mutableMapOf(
        MapOption.TERRAIN to 1, MapOption.TERRAIN_RAMP to TerrainRamp.PASTEL.code,
        MapOption.TOPOGRAPHY to 1, MapOption.TRAIL to 2)

    private val soundOptions = mutableMapOf(
        SoundOption.VARIO to 0, SoundOption.VARIO_VOLUME to 80,
        SoundOption.VARIO_SWITCHING to 0, SoundOption.VARIO_DEAD_BAND to 0,
        SoundOption.VARIO_DEAD_BAND_MIN to -30, SoundOption.VARIO_DEAD_BAND_MAX to 10)

    override suspend fun soundOption(option: SoundOption): Int? = soundOptions[option]

    override suspend fun setSoundOption(option: SoundOption, value: Int) {
        soundOptions[option] = value
    }

    override suspend fun mapOption(option: MapOption): Int? = mapOptions[option]

    override suspend fun setMapOption(option: MapOption, value: Int) {
        mapOptions[option] = value
    }

    private val airspaceOptions = mutableMapOf(
        AirspaceOption.WARNINGS to 1, AirspaceOption.ALERT_SOUND to 1,
        AirspaceOption.ALERT_VIBRATION to 1, AirspaceOption.AUTO_HIDE to 0,
        AirspaceOption.WARNING_TIME to 30, AirspaceOption.ACK_TIME to 30)

    private var classes = listOf(
        AirspaceClassInfo(1, "Restricted", display = true, warning = true, count = 12),
        AirspaceClassInfo(2, "Prohibited", display = true, warning = true, count = 4),
        AirspaceClassInfo(3, "Danger Area", display = true, warning = true, count = 21),
        AirspaceClassInfo(7, "Class D", display = true, warning = true, count = 9),
        AirspaceClassInfo(9, "Control Zone", display = true, warning = true, count = 6),
        AirspaceClassInfo(12, "Class E", display = true, warning = false, count = 3),
        AirspaceClassInfo(15, "Class G", display = false, warning = false))

    /* XCSoar's defaults */
    private val safety = mutableMapOf(
        SafetyOption.ARRIVAL_HEIGHT to 300.0, SafetyOption.TERRAIN_HEIGHT to 150.0,
        SafetyOption.MC to 0.5, SafetyOption.RISK_FACTOR to 0.0,
        SafetyOption.ALTERNATES to 0.0, SafetyOption.TURN_BACK_MARKER to 1.0)

    override suspend fun safetyOption(option: SafetyOption): Double? = safety[option]

    override suspend fun setSafetyOption(option: SafetyOption, value: Double) {
        safety[option] = value
    }

    private var rasp = RaspInfo()

    override suspend fun raspInfo(): RaspInfo = rasp

    override suspend fun setRasp(field: Int, time: String?) {
        rasp = rasp.copy(field = field, time = time)
    }

    private val stations = mutableListOf<WeatherStation>()

    override suspend fun weatherStations(): List<WeatherStation> = stations.toList()

    override suspend fun addWeatherStation(code: String): Boolean {
        val c = code.uppercase()
        if (!WeatherStation.isValidCode(c) || stations.any { it.code == c } ||
            stations.size >= WeatherStation.MAX_STATIONS)
            return false
        stations += WeatherStation(c)
        return true
    }

    override suspend fun removeWeatherStation(code: String) {
        stations.removeAll { it.code == code.uppercase() }
    }

    override suspend fun updateWeather() {
        stations.replaceAll {
            it.copy(metar = "${it.code} 031830Z 22008KT CAVOK 18/12 Q1021",
                    metarTime = "2026-10-03T18:30:00Z", qnh = 1021.0, windBearing = 220.0,
                    windSpeed = 4.1, temperature = 291.15, dewPoint = 285.15, cavok = true,
                    text = "${it.code} 031830Z 22008KT CAVOK 18/12 Q1021\nWind: 220° 15 km/h\n")
        }
    }

    private var tracking = TrackingSettings()

    override suspend fun trackingSettings(): TrackingSettings = tracking

    override suspend fun setTrackingSettings(settings: TrackingSettings) {
        tracking = settings
    }

    private var crew = 90.0

    override suspend fun crewMass(): Double = crew

    override suspend fun setCrewMass(kg: Double) {
        crew = kg
    }

    override suspend fun airspaceClasses(): List<AirspaceClassInfo> = classes

    override suspend fun setAirspaceClass(code: Int, display: Boolean, warning: Boolean) {
        classes = classes.map {
            if (it.code == code) it.copy(display = display, warning = warning) else it
        }
    }

    override suspend fun airspaceOption(option: AirspaceOption): Int? = airspaceOptions[option]

    override suspend fun setAirspaceOption(option: AirspaceOption, value: Int) {
        airspaceOptions[option] = value
    }

    private var files = mapOf<DataFile, String>()

    /* remembers the files; there is nothing to load */
    override suspend fun setDataFile(kind: DataFile, path: String?) {
        files = if (path.isNullOrEmpty()) files - kind else files + (kind to path)
    }

    override suspend fun dataStatus() = DataStatus(
        map = MapStatus(listOfNotNull(files[DataFile.MAP]), terrain = false),
        airspace = FileStatus(listOfNotNull(files[DataFile.AIRSPACE]), count = 0),
        waypoints = FileStatus(listOfNotNull(files[DataFile.WAYPOINTS]), count = 0),
        rasp = FileStatus(listOfNotNull(files[DataFile.RASP]), count = 0),
    )

    override suspend fun startReplay(path: String, timeScale: Double) {
        start()
    }

    override suspend fun stopReplay() {
        stop()
        eventFlow.emit(CoreEvent.ReplayFinished)
    }

    companion object {
        private const val FAKE_MAX_BALLAST = 150.0

        /** A flight second: 5 min cruise then 3 min thermal, repeated. */
        fun syntheticState(second: Int, macCready: Double = 1.0): FlightState {
            val cycle = second % 480
            val circling = cycle >= 300
            val t = second.toDouble()

            val vario = if (circling) 2.0 + 0.8 * sin(t / 5) else -0.9 + 0.3 * sin(t / 11)
            val altitude = 1500.0 + 150 * sin(t / 90) + if (circling) (cycle - 300) * 2.0 else 0.0
            val track = if (circling) (cycle * 18.0) % 360 else 245.0

            return FlightState(
                sequence = second.toLong(),
                timeUtc = 1_790_000_000.0 + t,
                flightTime = 1800.0 + t,
                position = GeoPosition(
                    45.0 + 0.001 * cos(t * PI / 10),
                    6.0 + 0.001 * sin(t * PI / 10)),
                track = track,
                groundSpeed = if (circling) 22.0 else 36.0,
                trueAirspeed = if (circling) 24.0 else 38.0,
                indicatedAirspeed = if (circling) 22.0 else 35.0,
                gpsAltitude = altitude + 12,
                baroAltitude = altitude,
                navAltitude = altitude,
                terrainAltitude = 600.0,
                altitudeAgl = altitude - 600,
                vario = vario,
                averageVario = if (circling) 1.9 else -0.8,
                nettoVario = if (circling) 2.6 else 0.2,
                wind = Wind(4.5, 270.0),
                macCready = macCready,
                next = NextWaypoint("Grenoble Le Versoud", 23_400.0 - t * 5 % 20_000, 62.0, -140.0),
                finalGlide = FinalGlide(58_000.0, -420.0),
                gpsReal = false,
                flying = true,
                circling = circling,
                aboveFinalGlide = false,
                replay = true,
                speedToFly = 33.0 + macCready * 2,
                ld = if (circling) null else 34.0,
                ldRequired = 27.0,
                nextTimeRemaining = 660.0,
                currentThermal = if (circling)
                    Thermal(2.1, (cycle - 300) * 2.0, (cycle - 300).toDouble()) else null,
                lastThermal = Thermal(2.1, 420.0, 204.0),
                maxBallast = FAKE_MAX_BALLAST,
                wingLoading = 32.5,
                staticPressure = 870.0,
            )
        }
    }
}
