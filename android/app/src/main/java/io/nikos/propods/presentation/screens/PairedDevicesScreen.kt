/*
    ProPods - AirPods liberated from Apple's ecosystem
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

package io.nikos.propods.presentation.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.propods.R
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel
import io.nikos.propods.presentation.viewmodel.PeerUiInfo

@SuppressLint("MissingPermission")
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PairedDevicesScreen(viewModel: AirPodsViewModel, navController: NavController) {
    val state by viewModel.uiState.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val context = LocalContext.current

    var showPicker by remember { mutableStateOf(false) }
    var confirmRemoveMac by remember { mutableStateOf<String?>(null) }

    val bondedDevices = remember {
        val bt = context.getSystemService(BluetoothManager::class.java)
        bt?.adapter?.bondedDevices?.toList() ?: emptyList()
    }

    // Device-picker dialog
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Add peer Android device") },
            text = {
                LazyColumn {
                    items(bondedDevices) { device ->
                        TextButton(
                            onClick = {
                                viewModel.addCrossDevicePeer(device.address)
                                showPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(device.name ?: device.address)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        )
    }

    // Remove-confirmation dialog
    confirmRemoveMac?.let { mac ->
        val peer = state.crossDevicePeers.find { it.mac == mac }
        AlertDialog(
            onDismissRequest = { confirmRemoveMac = null },
            title = { Text("Remove device") },
            text = { Text("Remove ${peer?.name ?: mac} from connected devices?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeCrossDevicePeer(mac)
                    confirmRemoveMac = null
                }) { Text("Remove", color = Color(0xFFFF3B30)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveMac = null }) { Text("Cancel") }
            }
        )
    }

    StyledScaffold(title = "Connected Devices") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            if (state.crossDevicePeers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor, RoundedCornerShape(18.dp))
                ) {
                    state.crossDevicePeers.forEachIndexed { index, peer ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = Color(0x40888888),
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        PeerRow(
                            peer = peer,
                            isDarkTheme = isDarkTheme,
                            textColor = textColor,
                            onReconnect = { viewModel.reconnectCrossDevicePeer(peer.mac) },
                            onRemove = { confirmRemoveMac = peer.mac }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // "Add device" button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor, RoundedCornerShape(18.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showPicker = true }) {
                        Text(
                            text = "+ Add device",
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = Color(0xFF007AFF)
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun PeerRow(
    peer: PeerUiInfo,
    isDarkTheme: Boolean,
    textColor: Color,
    onReconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Connection status dot
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(8.dp)
                    .background(
                        color = if (peer.connected) Color(0xFF34C759) else Color(0xFF8E8E93),
                        shape = CircleShape
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = textColor
                    )
                )
                if (peer.hasPods) {
                    Text(
                        text = "Has AirPods",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = Color(0xFF34C759)
                        )
                    )
                }
            }
        }
        // Action row: Reconnect (when disconnected) + Remove
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            if (!peer.connected) {
                TextButton(onClick = onReconnect) {
                    Text(
                        "Reconnect",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = Color(0xFF007AFF)
                        )
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRemove) {
                Text(
                    "Remove",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = Color(0xFFFF3B30)
                    )
                )
            }
        }
    }
}
