/*
    ProPods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 ProPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.nikos.propods.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Tiny info icon placed next to controls that are greyed out because the device
// cannot run AACP. Tapping opens a short dialog explaining why the control is
// disabled and what would unlock it.
@Composable
fun RequiresAacpIcon(
    message: String =
        "This setting requires AirPods control commands (AACP). " +
        "Your device cannot send them — needs a supported Pixel build or the LSPosed Xposed module."
) {
    var showInfo by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val iconTint = if (isDark) Color(0xFF8E8E93) else Color(0xFF6E6E73)

    Icon(
        imageVector = Icons.Outlined.Info,
        contentDescription = "Feature unavailable on this device",
        tint = iconTint,
        modifier = Modifier
            .size(18.dp)
            .clickable { showInfo = true }
    )

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Feature unavailable") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("OK") }
            }
        )
    }
}
