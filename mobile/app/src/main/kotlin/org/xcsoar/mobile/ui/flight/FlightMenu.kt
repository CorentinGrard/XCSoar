// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.ui.theme.XcsTheme

/** How long the menu takes to slide in or out. */
private const val MENU_MILLIS = 250

/**
 * An entry of the flight menu; disabled entries stay visible, dimmed.
 * Most open a page over the menu, so Back comes back to it; those with
 * [closesMenu] act on the flight screen itself.
 */
data class MenuAction(
    val label: String,
    val detail: String,
    val enabled: Boolean = true,
    val closesMenu: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * What the flight menu offers.
 *
 * @param inFlight large buttons for what the pilot does in the air
 * @param ground what is set before or after the flight
 * @param tileLayout the pilot's choice of tiles: null follows the
 * flight mode, else circling (true) or cruise (false) until the next
 * mode change
 */
class FlightMenu(
    val inFlight: List<MenuAction> = emptyList(),
    val ground: List<MenuAction> = emptyList(),
    val tileLayout: Boolean? = null,
    val onTileLayout: (circling: Boolean?) -> Unit = {},
)

/**
 * The menu as a sheet over the flight screen: in-flight actions first,
 * within reach of the thumb, then the tiles, then the ground list.
 * It slides up over the dimmed map when [visible]; a tap on the map
 * closes it.
 */
@Composable
fun FlightMenuSheet(visible: Boolean, menu: FlightMenu, onClose: () -> Unit,
                    modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(MENU_MILLIS)),
                           exit = fadeOut(tween(MENU_MILLIS))) {
            Box(Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() },
                           indication = null, onClickLabel = "Close menu", onClick = onClose))
        }

        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        val sheetMax = maxHeight - 48.dp
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(MENU_MILLIS, easing = LinearOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(MENU_MILLIS, easing = FastOutLinearInEasing)) { it },
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = sheetMax)
                    .background(colors.sheet, shape)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(72.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Menu", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                         modifier = Modifier.weight(1f).padding(start = 4.dp))
                    CloseButton(onClose)
                }

                Caption("In flight", Modifier.padding(start = 4.dp, bottom = 8.dp))
                menu.inFlight.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { action ->
                            BigAction(action, onClose, Modifier.weight(1f).fillMaxHeight())
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }

                Caption("Tiles", Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
                Segmented(listOf("Auto", "Cruise", "Circling"),
                          selected = when (menu.tileLayout) {
                              null -> 0
                              false -> 1
                              true -> 2
                          },
                          onSelect = { menu.onTileLayout(if (it == 0) null else it == 2) },
                          modifier = Modifier.fillMaxWidth())

                Caption("Before and after the flight",
                        Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp))
                menu.ground.forEachIndexed { index, action ->
                    if (index > 0) HorizontalDivider(color = colors.panelBorder)
                    GroundAction(action, onClose)
                }
            }
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val colors = XcsTheme.colors
    Box(
        Modifier
            .size(56.dp)
            .background(colors.panel, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Close menu", onClick = onClose)
            .semantics { contentDescription = "Close menu" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.5.dp.toPx()
            drawLine(colors.text, Offset(0f, 0f), Offset(size.width, size.height), stroke,
                     StrokeCap.Round)
            drawLine(colors.text, Offset(size.width, 0f), Offset(0f, size.height), stroke,
                     StrokeCap.Round)
        }
    }
}

/** A large in-flight button: the name, and what it holds underneath. */
@Composable
private fun BigAction(action: MenuAction, onClose: () -> Unit, modifier: Modifier) {
    val colors = XcsTheme.colors
    Column(
        modifier
            .heightIn(min = 88.dp)
            .alpha(if (action.enabled) 1f else 0.4f)
            .background(colors.panel, RoundedCornerShape(16.dp))
            .clickable(enabled = action.enabled, role = Role.Button) {
                if (action.closesMenu) onClose()
                action.onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        Text(action.label, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
             maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(action.detail, color = colors.textSecondary, fontSize = 13.sp, maxLines = 2,
             overflow = TextOverflow.Ellipsis)
    }
}

/** A row of the ground list: the name, its detail, and a chevron. */
@Composable
private fun GroundAction(action: MenuAction, onClose: () -> Unit) {
    val colors = XcsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .alpha(if (action.enabled) 1f else 0.4f)
            .clickable(enabled = action.enabled, role = Role.Button) {
                if (action.closesMenu) onClose()
                action.onClick()
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(action.label, color = colors.text, fontSize = 17.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            Text(action.detail, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
        }
        Canvas(Modifier.size(width = 8.dp, height = 14.dp)) {
            val stroke = 2.25.dp.toPx()
            drawLine(colors.textSecondary, Offset(0f, 0f), Offset(size.width, size.height / 2),
                     stroke, StrokeCap.Round)
            drawLine(colors.textSecondary, Offset(size.width, size.height / 2),
                     Offset(0f, size.height), stroke, StrokeCap.Round)
        }
    }
}
