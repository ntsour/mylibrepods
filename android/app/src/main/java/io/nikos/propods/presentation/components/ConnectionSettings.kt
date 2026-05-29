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

@file:OptIn(ExperimentalEncodingApi::class)

package io.nikos.propods.presentation.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nikos.propods.R
import kotlin.io.encoding.ExperimentalEncodingApi

@SuppressLint("MissingPermission")
@Composable
fun PeerConnectionPanel(
    crossDevicePeerMac: String?,
    onPeerMacChanged: (String) -> Unit,
    crossDevicePeerConnected: Boolean = false,
    onReconnectCrossDevice: () -> Unit = {},
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val context = LocalContext.current
    var showPeerPicker by remember { mutableStateOf(false) }

    val bondedDevices: List<BluetoothDevice> = remember {
        val bt = context.getSystemService(BluetoothManager::class.java)
        bt?.adapter?.bondedDevices?.toList() ?: emptyList()
    }
    val peerName: String? = remember(crossDevicePeerMac, bondedDevices) {
        bondedDevices.find { it.address == crossDevicePeerMac }?.name
    }

    if (showPeerPicker) {
        AlertDialog(
            onDismissRequest = { showPeerPicker = false },
            title = { Text("Select peer Android device") },
            text = {
                LazyColumn {
                    items(bondedDevices) { device ->
                        TextButton(
                            onClick = {
                                onPeerMacChanged(device.address)
                                showPeerPicker = false
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
                TextButton(onClick = { showPeerPicker = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clickable { showPeerPicker = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Peer Android device",
                style = TextStyle(
                    fontSize = 16.sp,
                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                    color = if (isDarkTheme) Color.White else Color.Black
                )
            )
            Spacer(modifier = Modifier.weight(1f))
            if (crossDevicePeerMac != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .background(
                            color = if (crossDevicePeerConnected) Color(0xFF34C759) else Color(0xFF8E8E93),
                            shape = CircleShape
                        )
                )
            }
            Text(
                text = peerName ?: (crossDevicePeerMac ?: "Not set"),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                    color = if (isDarkTheme) Color.White.copy(alpha = 0.5f)
                            else Color.Black.copy(alpha = 0.5f)
                )
            )
            Text(
                text = "›",
                style = TextStyle(
                    fontSize = 18.sp,
                    color = if (isDarkTheme) Color.White.copy(alpha = 0.4f)
                            else Color.Black.copy(alpha = 0.4f)
                ),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        if (crossDevicePeerMac != null && !crossDevicePeerConnected) {
            HorizontalDivider(
                thickness = 1.dp,
                color = Color(0x40888888),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clickable {
                        onReconnectCrossDevice()
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reconnect to peer",
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontFamily = FontFamily(Font(R.font.sf_pro)),
                        color = Color(0xFF007AFF)
                    )
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectionSettings(
    crossDeviceEnabled: Boolean,
    onCrossDeviceChanged: (Boolean) -> Unit,
    crossDevicePeerMac: String?,
    onPeerMacChanged: (String) -> Unit,
    crossDevicePeerConnected: Boolean = false,
    onReconnectCrossDevice: () -> Unit = {},
    automaticEarDetectionEnabled: Boolean,
    onAutomaticEarDetectionChanged: (Boolean) -> Unit,
    automaticConnectionEnabled: Boolean,
    onAutomaticConnectionChanged: (Boolean) -> Unit,
    earDetectionAvailable: Boolean = true,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val context = LocalContext.current
    var showPeerPicker by remember { mutableStateOf(false) }

    val bondedDevices: List<BluetoothDevice> = remember {
        val bt = context.getSystemService(BluetoothManager::class.java)
        bt?.adapter?.bondedDevices?.toList() ?: emptyList()
    }
    val peerName: String? = remember(crossDevicePeerMac, bondedDevices) {
        bondedDevices.find { it.address == crossDevicePeerMac }?.name
    }

    if (showPeerPicker) {
        AlertDialog(
            onDismissRequest = { showPeerPicker = false },
            title = { Text("Select peer Android device") },
            text = {
                LazyColumn {
                    items(bondedDevices) { device ->
                        TextButton(
                            onClick = {
                                onPeerMacChanged(device.address)
                                showPeerPicker = false
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
                TextButton(onClick = { showPeerPicker = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(top = 2.dp)
    ) {
        StyledToggle(
            label = stringResource(R.string.cross_device_handover),
            description = stringResource(R.string.cross_device_handover_description),
            independent = false,
            checked = crossDeviceEnabled,
            onCheckedChange = onCrossDeviceChanged
        )
        AnimatedVisibility(
            visible = crossDeviceEnabled,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clickable { showPeerPicker = true }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Peer Android device",
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = if (isDarkTheme) Color.White else Color.Black
                        )
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (crossDevicePeerMac != null) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(8.dp)
                                .background(
                                    color = if (crossDevicePeerConnected) Color(0xFF34C759) else Color(0xFF8E8E93),
                                    shape = CircleShape
                                )
                        )
                    }
                    Text(
                        text = peerName ?: (crossDevicePeerMac ?: "Not set"),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = if (isDarkTheme) Color.White.copy(alpha = 0.5f)
                                    else Color.Black.copy(alpha = 0.5f)
                        )
                    )
                    Text(
                        text = "›",
                        style = TextStyle(
                            fontSize = 18.sp,
                            color = if (isDarkTheme) Color.White.copy(alpha = 0.4f)
                                    else Color.Black.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                if (crossDevicePeerMac != null && !crossDevicePeerConnected) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clickable {
                                android.util.Log.d("ConnectionSettings", "Reconnect to peer clicked (UI layer)")
                                onReconnectCrossDevice()
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reconnect to peer",
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                color = Color(0xFF007AFF)
                            )
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Color(0x40888888),
            modifier = Modifier
                .padding(horizontal = 12.dp)
        )

        if (earDetectionAvailable) {
            StyledToggle(
                label = stringResource(R.string.ear_detection),
                independent = false,
                checked = automaticEarDetectionEnabled,
                onCheckedChange = onAutomaticEarDetectionChanged
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f).alpha(0.4f)) {
                    StyledToggle(
                        label = stringResource(R.string.ear_detection),
                        independent = false,
                        checked = automaticEarDetectionEnabled,
                        onCheckedChange = { /* no-op: AACP not available */ },
                        enabled = false
                    )
                }
                io.nikos.propods.presentation.components.RequiresAacpIcon()
                Spacer(Modifier.width(12.dp))
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = Color(0x40888888),
            modifier = Modifier
                .padding(horizontal = 12.dp)
        )

        if (earDetectionAvailable) {
            StyledToggle(
                label = stringResource(R.string.automatically_connect),
                description = stringResource(R.string.automatically_connect_description),
                independent = false,
                checked = automaticConnectionEnabled,
                onCheckedChange = onAutomaticConnectionChanged
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f).alpha(0.4f)) {
                    StyledToggle(
                        label = stringResource(R.string.automatically_connect),
                        description = stringResource(R.string.automatically_connect_description),
                        independent = false,
                        checked = automaticConnectionEnabled,
                        onCheckedChange = { /* no-op: AACP not available */ },
                        enabled = false
                    )
                }
                io.nikos.propods.presentation.components.RequiresAacpIcon()
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}
