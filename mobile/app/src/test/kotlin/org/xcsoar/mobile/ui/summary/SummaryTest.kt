// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.summary

import androidx.lifecycle.viewModelScope
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.flight.FlightViewModel
import org.xcsoar.mobile.ui.flights.FlightLog
import org.xcsoar.mobile.ui.flights.Upload
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.cos
import kotlin.math.sin

/** The flight summary after a landing (mobile/docs/ARCHITECTURE.md §6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class SummaryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /* a triangle from Grenoble, as the core's analysis has it */
    private val analysis = Analysis.parse("""
        {"flight": {"takeoff": 41520, "takeoff_local": "12:32", "landing": 56880,
                    "landing_local": "16:48", "duration": 15360},
         "barograph": {"altitude": [[0.0, 220], [1.5, 2840], [4.2, 230]]},
         "climb": {"thermals": [[0.3, 1.2, 0.1], [1.4, 2.6, 0.05], [2.8, 1.9, 0.08]],
                   "average": 1.8},
         "contest": {"name": "WeGlide",
                     "results": [{"label": "Free", "distance": 302000, "score": 287.4,
                                  "time": 14400, "speed": 21.0,
                                  "points": [[45.36, 5.85], [45.9, 6.4], [45.2, 6.9],
                                             [45.36, 5.85]]},
                                 {"label": "Distance", "distance": 250000, "score": 0}],
                     "trace": ${trace()}}}
    """.trimIndent())

    private fun trace() = (0..60).joinToString(",", "[", "]") {
        val a = it / 60.0 * 2 * Math.PI
        "[${45.5 + 0.3 * sin(a) + 0.05 * sin(5 * a)}, ${6.3 + 0.5 * cos(a)}]"
    }

    @Test
    fun theFlightAtAGlance() {
        val s = FlightSummary.of(analysis, replay = false)
        assertEquals(15360.0, s.duration, 0.0)
        assertEquals("16:48", s.times?.landingLocal)
        // the result with the most points
        assertEquals("Free", s.result?.label)
        assertEquals(2.6, s.bestClimb!!.lift, 0.0)
        assertEquals(468.0, s.bestClimb!!.gain, 1e-6)
        assertEquals(3, s.climbs)
        assertEquals(2840.0, s.highest!!, 0.0)
    }

    @Test
    fun aClosedLogIsSigned() {
        val open = folder.newFile("open.igc").apply {
            writeText("AXCSAAA\r\nB1101355206343N00006198WA0058700558\r\n")
        }
        val closed = folder.newFile("closed.igc").apply {
            writeText(open.readText() + "GREJNGJERJKNJKRE31895478537H43982FJN9248F942389\r\n")
        }
        assertFalse(isSigned(open))
        assertTrue(isSigned(closed))
        assertFalse(isSigned(java.io.File(folder.root, "missing.igc")))
    }

    /** A core whose state and events the test sets. */
    private class Scripted(fake: FakeXcsoarCore) : XcsoarCore by fake {
        val states = MutableStateFlow<FlightState?>(null)
        val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 8)
        override val flightState: StateFlow<FlightState?> = states
        override val events: SharedFlow<CoreEvent> = eventFlow
    }

    @Test
    fun aLandingOrAReplayEndsTheFlight() = runTest {
        val core = Scripted(FakeXcsoarCore(backgroundScope))
        val viewModel = FlightViewModel(core) { "" }
        try {
            val ended = mutableListOf<FlightEnd>()
            backgroundScope.launch { viewModel.flightEnded.collect { ended += it } }
            runCurrent()
            val s = FakeXcsoarCore.syntheticState(0)

            core.states.value = s.copy(replay = false)
            core.eventFlow.emit(CoreEvent.GlideComputer(GlideComputerEvent.LANDING))
            // a replay's landing: its end brings the summary
            core.states.value = s.copy(replay = true)
            core.eventFlow.emit(CoreEvent.GlideComputer(GlideComputerEvent.LANDING))
            core.eventFlow.emit(CoreEvent.ReplayFinished("{}"))
            // stopped before it took off: nothing to sum up
            core.eventFlow.emit(CoreEvent.ReplayFinished(null))
            advanceTimeBy(100)

            assertEquals(listOf(FlightEnd.Landed, FlightEnd.Replayed("{}")), ended)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private val log = FlightLog("/logs/2026-11-19-XCS-AAA-01.igc", "2026-11-19-XCS-AAA-01.igc",
                                0, 412_000)

    @Test
    fun landed() = captureRoboImage("src/test/screenshots/summary_landed.png") {
        XcsTheme(dark = false) {
            SummaryContent(SummaryState(FlightSummary.of(analysis, replay = false), log),
                           weGlideReady = true, upload = null)
        }
    }

    @Test
    fun uploaded() = captureRoboImage("src/test/screenshots/summary_uploaded.png") {
        XcsTheme(dark = true) {
            SummaryContent(SummaryState(FlightSummary.of(analysis, replay = false), log),
                           weGlideReady = true,
                           upload = Upload.Done(WeGlideFlight(123456, "https://weglide.org")))
        }
    }

    @Test
    fun replay() = captureRoboImage("src/test/screenshots/summary_replay.png") {
        XcsTheme(dark = false) {
            SummaryContent(SummaryState(FlightSummary.of(analysis, replay = true)),
                           weGlideReady = false, upload = null)
        }
    }
}
