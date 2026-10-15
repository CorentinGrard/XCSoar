// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.analysis

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.XcsoarCore

/** XCSoar's analysis pages, read again every few seconds while shown. */
class AnalysisViewModel(private val core: XcsoarCore) : ViewModel() {
    private val analysisFlow = MutableStateFlow<Analysis?>(null)
    /** `null` until the core has answered. */
    val analysis: StateFlow<Analysis?> = analysisFlow.asStateFlow()

    private val pageFlow = MutableStateFlow(AnalysisPage.BAROGRAPH)
    /** The page shown; kept while the app runs, like upstream's dialog. */
    val page: StateFlow<AnalysisPage> = pageFlow.asStateFlow()

    fun show(page: AnalysisPage) {
        pageFlow.value = page
    }

    /** Runs until cancelled: call it from the screen's composition. */
    suspend fun refreshWhileShown() {
        while (true) {
            try {
                core.analysis()?.let { analysisFlow.value = it }
            } catch (e: CancellationException) {
                // the page was left
                throw e
            } catch (e: Exception) {
                // keep the last pages; the next round tries again
                Log.w("XCSoar", "analysis not read", e)
            }
            delay(REFRESH_MS)
        }
    }

    companion object {
        const val REFRESH_MS = 5_000L
    }
}
