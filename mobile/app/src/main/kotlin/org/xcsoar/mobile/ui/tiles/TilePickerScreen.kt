// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.tiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.core.TileType
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.theme.XcsTheme

/** The tile being changed and the types it can show. */
data class TilePickerState(
    val layout: TileLayout = TileLayout.CRUISE,
    val tile: Int = 0,
    /** The type shown now. */
    val current: Int? = null,
    val types: List<TileType> = emptyList(),
)

/** Choose the InfoBox a tile shows, like XCSoar's InfoBox setup. */
class TilePickerViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(TilePickerState())
    val state: StateFlow<TilePickerState> = stateFlow.asStateFlow()

    fun open(layout: TileLayout, tile: Int) {
        stateFlow.update { it.copy(layout = layout, tile = tile, current = null) }
        viewModelScope.launch {
            val types = stateFlow.value.types.ifEmpty { core.tileTypes() }
            val current = core.tileLayouts()?.of(layout)?.getOrNull(tile)
            stateFlow.update { it.copy(types = types, current = current) }
        }
    }

    fun pick(type: TileType, onDone: () -> Unit) {
        val s = stateFlow.value
        viewModelScope.launch {
            try {
                core.setTile(s.layout, s.tile, type.id)
            } finally {
                onDone()
            }
        }
    }
}

@Composable
fun TilePickerScreen(viewModel: TilePickerViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    TilePickerContent(state, onPick = { viewModel.pick(it, onDone) }, onBack = onDone)
}

@Composable
fun TilePickerContent(
    state: TilePickerState,
    onPick: (TileType) -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val shown = state.types.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) ||
            it.caption.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val where = if (state.layout == TileLayout.CIRCLING) "circling" else "cruise"
        ScreenHeader("Tile ${state.tile + 1} · $where", onBack)
        InputField(query, { query = it }, "Search")
        LazyColumn(Modifier
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(14.dp))) {
            items(shown, key = { it.id }) { type ->
                TypeRow(type, selected = type.id == state.current) { onPick(type) }
                HorizontalDivider(color = colors.panelBorder,
                                  modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

@Composable
private fun TypeRow(type: TileType, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Column(Modifier
               .fillMaxWidth()
               .heightIn(min = 64.dp)
               .background(if (selected) colors.selected else colors.panel)
               .clickable(role = Role.Button, onClickLabel = "Show ${type.name}",
                          onClick = onClick)
               .padding(horizontal = 14.dp, vertical = 10.dp),
           verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (selected) "${type.name} · shown" else type.name,
             color = if (selected) colors.onSelected else colors.text,
             fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
             overflow = TextOverflow.Ellipsis)
        Text(listOf(type.caption, type.description).filter { it.isNotEmpty() }
                 .joinToString(" · "),
             color = if (selected) colors.onSelected else colors.textSecondary,
             fontSize = 14.sp, maxLines = 2,
             overflow = TextOverflow.Ellipsis)
    }
}
