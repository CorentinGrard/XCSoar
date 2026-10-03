// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * A 56 dp button: [primary] filled, [outlined] on a panel, else plain
 * text.  Disabled buttons stay visible, dimmed.
 */
@Composable
fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    outlined: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(modifier
            .height(56.dp)
            .widthIn(min = 96.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(when {
                primary -> Modifier.background(colors.selected, shape)
                outlined -> Modifier
                    .background(colors.control, shape)
                    .border(1.dp, colors.panelBorder, shape)
                else -> Modifier.background(Color.Transparent, shape)
            })
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) colors.onSelected else colors.text,
             fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** "Back" and the screen's title. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null)
            ActionButton("Back", onClick = onBack)
        else
            Box(Modifier.padding(start = 4.dp))
        Text(title, color = XcsTheme.colors.text, fontSize = 24.sp,
             fontWeight = FontWeight.Bold)
    }
}

/** A one-line text field in the app's colours. */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    val colors = XcsTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType,
                                          capitalization = capitalization),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.selected, focusedLabelColor = colors.text,
            focusedTextColor = colors.text, unfocusedTextColor = colors.text,
            cursorColor = colors.text),
    )
}
