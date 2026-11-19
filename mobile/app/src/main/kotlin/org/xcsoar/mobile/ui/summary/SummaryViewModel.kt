// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.flights.FlightLog
import java.io.File
import java.io.RandomAccessFile

data class SummaryState(
    val summary: FlightSummary? = null,
    /** The flight's IGC file once XCSoar's logger closed and signed it. */
    val log: FlightLog? = null,
    /** Still waiting for the logger to close the file. */
    val waitingForLog: Boolean = false,
)

/**
 * The summary after a landing (or a replay's end): the flight from
 * XCSoar's analysis, and its IGC file for Share and WeGlide once the
 * logger has closed it (some time after the landing).
 *
 * @param logs the IGC files XCSoar's logger wrote
 */
class SummaryViewModel(
    private val core: XcsoarCore,
    private val logs: suspend () -> List<FlightLog>,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = stateFlow.asStateFlow()

    private var logJob: Job? = null

    /**
     * Read the flight that just ended; false when there is nothing
     * worth a summary (no analysis, a flight under [FlightSummary.MIN_SECONDS]).
     */
    suspend fun open(end: FlightEnd): Boolean {
        val analysis = when (end) {
            FlightEnd.Landed -> try { core.analysis() } catch (_: Exception) { null }
            is FlightEnd.Replayed -> withContext(Dispatchers.Default) {
                try { Analysis.parse(end.analysis) } catch (_: Exception) { null }
            }
        } ?: return false
        val summary = FlightSummary.of(analysis, replay = end is FlightEnd.Replayed)
        if (summary.duration < FlightSummary.MIN_SECONDS) return false

        logJob?.cancel()
        stateFlow.value = SummaryState(summary, waitingForLog = !summary.replay)
        if (!summary.replay)
            logJob = viewModelScope.launch { findLog(summary.duration) }
        return true
    }

    /**
     * The newest IGC file written during the flight, once signed (its
     * G record ends it): the logger stops a while after the landing.
     */
    private suspend fun findLog(duration: Double) {
        val window = (duration * 1000).toLong() + LOG_WINDOW_MS
        repeat(LOG_ATTEMPTS) {
            val now = System.currentTimeMillis()
            val log = try {
                logs().filter { now - it.modified < window }.maxByOrNull { it.modified }
            } catch (_: Exception) {
                null
            }
            if (log != null && withContext(Dispatchers.IO) { isSigned(File(log.path)) }) {
                stateFlow.value = stateFlow.value.copy(log = log, waitingForLog = false)
                return
            }
            delay(LOG_POLL_MS)
        }
        stateFlow.value = stateFlow.value.copy(waitingForLog = false)
    }

    private companion object {
        const val LOG_POLL_MS = 5_000L
        /* ten minutes, then Flights has it */
        const val LOG_ATTEMPTS = 120
        /* the file was written during the flight, plus a margin */
        const val LOG_WINDOW_MS = 15 * 60 * 1000L
    }
}

/** An IGC file ends with its G (security) record once the logger closed it. */
internal fun isSigned(file: File): Boolean = try {
    RandomAccessFile(file, "r").use { f ->
        val tail = ByteArray(minOf(f.length(), 4096L).toInt())
        f.seek(f.length() - tail.size)
        f.readFully(tail)
        String(tail, Charsets.US_ASCII).lineSequence().any { it.startsWith("G") }
    }
} catch (_: Exception) {
    false
}
