// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.status

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xcsoar.mobile.core.StatusPages
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale

class StatusViewModel(private val core: XcsoarCore) : ViewModel() {
    private val pagesFlow = MutableStateFlow<StatusPages?>(null)
    /** null until read. */
    val pages: StateFlow<StatusPages?> = pagesFlow.asStateFlow()

    private val selectedFlow = MutableStateFlow(FLIGHT)
    /** The page shown, by name. */
    val selected: StateFlow<String> = selectedFlow.asStateFlow()

    /** Open on [page] (a tile asks for one), else where the pilot left it. */
    fun show(page: String?) {
        if (page != null) selectedFlow.value = page
    }

    fun select(page: String) {
        selectedFlow.value = page
    }

    /** Reads the pages once a second while they show, as upstream refreshes them. */
    suspend fun refreshWhileShown() {
        while (true) {
            try {
                core.statusPages()?.let { pagesFlow.value = it }
            } catch (e: CancellationException) {
                // the page was left
                throw e
            } catch (e: Exception) {
                Log.w("XCSoar", "status not read", e)
            }
            delay(REFRESH_MS)
        }
    }

    companion object {
        const val REFRESH_MS = 1_000L
        const val FLIGHT = "flight"
    }
}

@Composable
fun StatusScreen(viewModel: StatusViewModel, onBack: () -> Unit) {
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.refreshWhileShown() }
    BackHandler(onBack = onBack)
    StatusContent(pages, selected, viewModel::select, onShare = { lat, lon ->
        val text = shareText(lat, lon)
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), null))
    }, onBack = onBack)
}

/**
 * XCSoar's status dialog: flight, system, task, rules and times, one
 * page at a time, as the core formats them.  The flight page can share
 * the position (for the retrieve crew after an outlanding).
 */
@Composable
fun StatusContent(
    pages: StatusPages?,
    selected: String,
    onSelect: (String) -> Unit,
    onShare: (latitude: Double, longitude: Double) -> Unit,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader("Status", onBack)

        val list = pages?.pages.orEmpty()
        if (list.isNotEmpty()) {
            val index = list.indexOfFirst { it.name == selected }.coerceAtLeast(0)
            Segmented(list.map { it.title }, selected = index,
                      onSelect = { onSelect(list[it].name) },
                      modifier = Modifier.fillMaxWidth())

            val page = list[index]
            Column(Modifier
                       .fillMaxWidth()
                       .background(XcsTheme.colors.panel, RoundedCornerShape(14.dp))
                       .padding(vertical = 4.dp)) {
                page.rows.forEachIndexed { i, row ->
                    if (i > 0)
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp),
                                          color = XcsTheme.colors.panelBorder)
                    StatusLine(row.label, row.value)
                }
            }

            val lat = pages?.latitude
            val lon = pages?.longitude
            if (page.name == StatusViewModel.FLIGHT && lat != null && lon != null)
                ActionButton("Share position", outlined = true,
                             modifier = Modifier.fillMaxWidth()) { onShare(lat, lon) }
        } else {
            Text("Reading…", color = XcsTheme.colors.textSecondary, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    val colors = XcsTheme.colors
    val shown = value.ifEmpty { Format.INVALID }
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = "$label: ${value.ifEmpty { "unknown" }}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = colors.textSecondary, fontSize = 16.sp,
             modifier = Modifier.weight(1f))
        Text(shown, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
             style = XcsTheme.numberStyle, textAlign = TextAlign.End,
             modifier = Modifier.weight(1.4f))
    }
}

/** Decimal degrees and an OpenStreetMap link, readable in any message. */
internal fun shareText(latitude: Double, longitude: Double): String {
    val lat = String.format(Locale.ROOT, "%.5f", latitude)
    val lon = String.format(Locale.ROOT, "%.5f", longitude)
    return "$lat, $lon\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon"
}
