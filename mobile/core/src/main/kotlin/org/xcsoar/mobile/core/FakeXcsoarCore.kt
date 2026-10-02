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

    override suspend fun start() {
        if (job != null) return
        job = scope.launch {
            var second = 0
            var wasCircling = false
            while (true) {
                val s = syntheticState(second, macCready)
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

    private var files = mapOf<DataFile, String>()

    /* remembers the files; there is nothing to load */
    override suspend fun setDataFile(kind: DataFile, path: String?) {
        files = if (path.isNullOrEmpty()) files - kind else files + (kind to path)
    }

    override suspend fun dataStatus() = DataStatus(
        map = MapStatus(listOfNotNull(files[DataFile.MAP]), terrain = false),
        airspace = FileStatus(listOfNotNull(files[DataFile.AIRSPACE]), count = 0),
        waypoints = FileStatus(listOfNotNull(files[DataFile.WAYPOINTS]), count = 0),
    )

    override suspend fun startReplay(path: String, timeScale: Double) {
        start()
    }

    override suspend fun stopReplay() {
        stop()
        eventFlow.emit(CoreEvent.ReplayFinished)
    }

    companion object {
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
            )
        }
    }
}
