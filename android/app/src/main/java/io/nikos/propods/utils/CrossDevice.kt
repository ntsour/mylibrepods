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

package io.nikos.propods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.util.Log
import io.nikos.propods.services.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class CrossDevicePackets(val packet: ByteArray) {
    AIRPODS_CONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x01)),
    AIRPODS_DISCONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x00)),
    REQUEST_DISCONNECT(byteArrayOf(0x00, 0x02, 0x00, 0x00)),
    REQUEST_BATTERY_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x01)),
    REQUEST_ANC_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x02)),
    REQUEST_CONNECTION_STATUS(byteArrayOf(0x00, 0x02, 0x00, 0x03)),
    REQUEST_HANDOVER(byteArrayOf(0x00, 0x02, 0x00, 0x04)),
    AIRPODS_DATA_HEADER(byteArrayOf(0x00, 0x04, 0x00, 0x01)),
    // Windows → Android: whether a call/meeting is actively using AirPods on Windows.
    WINDOWS_AUDIO_ACTIVE(byteArrayOf(0x00, 0x03, 0x00, 0x01)),
    WINDOWS_AUDIO_IDLE(byteArrayOf(0x00, 0x03, 0x00, 0x00)),
}

object CrossDevice {
    private const val TAG = "CrossDevice"
    private val UUID_CROSS_DEVICE = UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")

    // Keep-alive ping interval for server-side sockets, mirroring CrossDeviceClient.
    // Server-only role (when the peer's MAC sorts lower than ours) has no app traffic
    // between handovers, so without this Android's BT power-management can drop the ACL.
    private const val SERVER_KEEPALIVE_INTERVAL_MS = 20_000L

    var isEnabled: Boolean = false
    var isAvailable: Boolean = false  // true = AirPods are on the remote device, not us
    /** True when Windows has reported an active audio session (call/meeting) on the AirPods endpoint. */
    var peerAudioActive: Boolean = false
    var batteryBytes: ByteArray = byteArrayOf()
    var ancBytes: ByteArray = byteArrayOf()

    /** True when at least one RFCOMM client is connected to our server, or our client is connected. */
    val isServerClientConnected: Boolean get() = clientSockets.isNotEmpty()
    val isPeerConnected: Boolean get() = isServerClientConnected || CrossDeviceClient.isConnected

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<BluetoothSocket>()
    @Volatile private var isServerRunning: Boolean = false

    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val peerMac = prefs.getString("cross_device_peer_mac", null)
        // Auto-enable when a peer MAC is saved but the flag was never explicitly set
        // (covers devices without AACP that configured a peer before this flag existed).
        isEnabled = prefs.getBoolean("cross_device_enabled", !peerMac.isNullOrEmpty())
        if (isEnabled && !prefs.contains("cross_device_enabled")) {
            prefs.edit().putBoolean("cross_device_enabled", true).apply()
        }
        if (!isEnabled) {
            Log.d(TAG, "Cross-device disabled by preference")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled, skipping server start")
            return
        }
        startServer(adapter)

        if (!peerMac.isNullOrEmpty()) {
            maybeStartClient(adapter, peerMac)
        }
    }

    /**
     * Role-elect whether this device should run the RFCOMM CLIENT to [peerMac].
     *
     * Both Android devices in a pair run the server (Windows always connects as a
     * client). Without this election they would also each start a client to each
     * other on the same UUID, producing two RFCOMM channels between the same MAC
     * pair — Bluedroid frequently kills the older session, which is the root
     * cause of the "Connect failed (read ret: -1)" loop.
     *
     * Election by **Bluetooth device name**: the local MAC is unreadable on modern
     * Android (`adapter.address` is the 02:00:00:00:00:00 sentinel and the Secure
     * "bluetooth_address" setting is locked down), which made the old MAC-based
     * election fall through to "client-on" on BOTH devices — the very collision it
     * was meant to prevent. Device names, however, are always readable: each device
     * knows its own (`adapter.name`) and the peer's (`getRemoteDevice(peerMac).name`,
     * cached for a bonded device). Both devices compare the SAME ordered pair, so
     * `ownName < peerName` is true on exactly one of them.
     *
     * Fallback when names are unusable (null/blank, or equal — identical models):
     * SERVER-only. Two server-only devices means no handover coordination, but no
     * `read ret: -1` collision storm either — strictly safer than starting a client.
     */
    @SuppressLint("MissingPermission")
    private fun maybeStartClient(adapter: android.bluetooth.BluetoothAdapter, peerMac: String) {
        val ownName = adapter.name
        val peerName = runCatching { adapter.getRemoteDevice(peerMac).name }.getOrNull()
        if (ownName.isNullOrBlank() || peerName.isNullOrBlank() || ownName.equals(peerName, ignoreCase = true)) {
            Log.w(TAG, "Role election: names unusable (own='$ownName' peer='$peerName') — staying SERVER-only")
            CrossDeviceClient.stop()
            return
        }
        val shouldBeClient = ownName.compareTo(peerName, ignoreCase = true) < 0
        if (shouldBeClient) {
            Log.d(TAG, "Role election: I am CLIENT to peer $peerMac (own='$ownName' < peer='$peerName')")
            CrossDeviceClient.start(adapter, peerMac)
        } else {
            Log.d(TAG, "Role election: I am SERVER-only for peer $peerMac (own='$ownName' >= peer='$peerName')")
            // Make sure no stale client coroutine is running from a previous config.
            CrossDeviceClient.stop()
        }
    }

    /**
     * Tear down and re-open the RFCOMM server. Called by the "Reconnect to peer"
     * button so the user has a way to recover when the server side is wedged
     * (e.g. accept() returned but the socket is stale).
     */
    @SuppressLint("MissingPermission")
    fun restartServer(context: Context) {
        Log.d(TAG, "restartServer: closing server socket and ${clientSockets.size} client socket(s)")
        serverSocket?.runCatching { close() }
        serverSocket = null
        clientSockets.forEach { it.runCatching { close() } }
        clientSockets.clear()
        isServerRunning = false
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "restartServer: adapter unavailable or disabled")
            return
        }
        startServer(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun startServer(adapter: android.bluetooth.BluetoothAdapter) {
        if (isServerRunning) {
            Log.d(TAG, "Server already running, skipping start")
            return
        }
        isServerRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Insecure: skips per-connect SDP-level auth so a transient pairing
                // re-negotiation (caused by other BT activity on the same ACL) can't
                // make the next connect fail with "read ret: -1". The underlying ACL
                // is still encrypted because both devices are bonded for A2DP.
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("ProPodsCrossDevice", UUID_CROSS_DEVICE)
                Log.d(TAG, "RFCOMM server listening")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to open RFCOMM server: ${e.message}")
                isServerRunning = false
                return@launch
            }
            // Accept loop — each client gets its own coroutine so multiple can be active at once
            while (true) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: IOException) {
                    Log.d(TAG, "Server socket closed: ${e.message}")
                    break
                }
                Log.d(TAG, "Client connected: ${socket.remoteDevice.address} (${clientSockets.size + 1} total)")
                clientSockets.add(socket)
                CoroutineScope(Dispatchers.IO).launch { handleClientConnection(socket) }
            }
            // Server socket died (not via close()). Reset the flag and restart
            // automatically if cross-device is still enabled, so incoming connections
            // can be accepted again without requiring a service restart.
            isServerRunning = false
            if (isEnabled) {
                Log.d(TAG, "RFCOMM server socket died unexpectedly; restarting in 2s")
                kotlinx.coroutines.delay(2_000L)
                startServer(adapter)
            }
        }
    }

    private fun handleClientConnection(socket: BluetoothSocket) {
        val addr = socket.remoteDevice.address
        val ctx = ServiceManager.getService()?.applicationContext
        ctx?.sendBroadcast(
            Intent("io.nikos.propods.AIRPODS_CONNECTED_REMOTELY").setPackage(ctx.packageName)
        )
        // Tell only this new client our current AirPods state
        sendToSocket(
            socket,
            if (ServiceManager.getService()?.isConnected() == true)
                CrossDevicePackets.AIRPODS_CONNECTED.packet
            else
                CrossDevicePackets.AIRPODS_DISCONNECTED.packet
        )

        // Server-side keep-alive: when this device is "server only" (role election
        // suppressed our client), nothing else generates app-layer traffic between
        // handovers and Android Doze will eventually drop the idle ACL. Pinging the
        // peer every 20 s keeps it alive. The peer's CrossDeviceClient already
        // replies to REQUEST_CONNECTION_STATUS, so no protocol change is needed.
        val keepAliveScope = CoroutineScope(Dispatchers.IO)
        val keepAliveJob: Job = keepAliveScope.launch {
            while (true) {
                delay(SERVER_KEEPALIVE_INTERVAL_MS)
                if (!clientSockets.contains(socket)) break
                val ok = runCatching {
                    socket.outputStream.write(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)
                    socket.outputStream.flush()
                }.isSuccess
                if (!ok) break
                Log.d(TAG, "Server keep-alive ping → $addr")
            }
        }

        val buffer = ByteArray(1024)
        while (true) {
            val bytes = try {
                socket.inputStream.read(buffer)
            } catch (e: IOException) {
                Log.d(TAG, "Client $addr read error (disconnected): ${e.message}")
                break
            }
            if (bytes == -1) break
            processPacket(buffer.copyOf(bytes))
        }

        keepAliveJob.cancel()
        socket.runCatching { close() }
        clientSockets.remove(socket)
        Log.d(TAG, "Client $addr removed (${clientSockets.size} remaining)")

        if (clientSockets.isEmpty()) {
            isAvailable = false
            val appCtx = ServiceManager.getService()?.applicationContext
            appCtx?.sendBroadcast(
                Intent("io.nikos.propods.AIRPODS_DISCONNECTED_REMOTELY").setPackage(appCtx.packageName)
            )
        }
    }

    private fun processPacket(raw: ByteArray) {
        Log.d(TAG, "Received: ${raw.joinToString("") { "%02x".format(it) }}")
        when {
            raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) -> {
                // Peer wants the AirPods — release them if we hold the connection.
                // Eagerly flip isAvailable=true: the peer just claimed ownership, so for
                // *our* takeOver gate ("crossDeviceAvailable=false → bail") to work on a
                // quick reversal play press, the peer must register as "having them"
                // immediately — without waiting for the peer's AACP handshake to finish
                // and its eventual AIRPODS_CONNECTED reply, which can be 5–60 s later.
                Log.d(TAG, "Received REQUEST_HANDOVER from peer, releasing AirPods (eagerly setting isAvailable=true)")
                isAvailable = true
                ServiceManager.getService()?.markPeerTakeoverAttempt()
                ServiceManager.getService()?.disconnectForCD()
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) -> {
                // Mark that a peer is taking over so we apply cooldown appropriately
                ServiceManager.getService()?.markPeerTakeoverAttempt()
                ServiceManager.getService()?.disconnectForCD()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet) -> {
                isAvailable = true
                // Peer explicitly announced ownership — if we were expecting a takeover,
                // arm the peer-drop cooldown proactively (don't wait for ACL_DISCONNECTED).
                ServiceManager.getService()?.confirmPeerOwnership()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet) -> {
                isAvailable = false
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_BATTERY_BYTES.packet) -> {
                sendRemotePacket(batteryBytes)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_ANC_BYTES.packet) -> {
                sendRemotePacket(ancBytes)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet) -> {
                sendRemotePacket(
                    if (ServiceManager.getService()?.isConnected() == true)
                        CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else
                        CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                )
            }
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_ACTIVE.packet) -> {
                peerAudioActive = true
                Log.d(TAG, "Windows reports active audio session (call/meeting in progress)")
            }
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_IDLE.packet) -> {
                peerAudioActive = false
                Log.d(TAG, "Windows reports audio session idle")
            }
            raw.size >= 4 && raw.sliceArray(0..3)
                .contentEquals(CrossDevicePackets.AIRPODS_DATA_HEADER.packet) -> {
                isAvailable = true
                val deduplicated = deduplicateIfNeeded(raw)
                val payload = deduplicated.drop(CrossDevicePackets.AIRPODS_DATA_HEADER.packet.size).toByteArray()
                processRelayedPacket(payload)
            }
        }
    }

    private fun deduplicateIfNeeded(packet: ByteArray): ByteArray {
        if (packet.size % 2 == 0) {
            val half = packet.size / 2
            if (packet.sliceArray(0 until half).contentEquals(packet.sliceArray(half until packet.size))) {
                Log.d(TAG, "Deduplicated doubled packet")
                return packet.sliceArray(0 until half)
            }
        }
        return packet
    }

    private fun processRelayedPacket(payload: ByteArray) {
        val svc = ServiceManager.getService() ?: return
        when {
            svc.batteryNotification.isBatteryData(payload) -> {
                batteryBytes = payload
                svc.batteryNotification.setBattery(payload)
                svc.updateBattery()
                svc.sendBatteryBroadcast()
                svc.sendBatteryNotification()
            }
            svc.ancNotification.isANCData(payload) -> {
                ancBytes = payload
                svc.ancNotification.setStatus(payload)
                svc.sendANCBroadcast()
                svc.updateNoiseControlWidget()
            }
            svc.earDetectionNotification.isEarDetectionData(payload) -> {
                svc.earDetectionNotification.setStatus(payload)
                val inEar = svc.earDetectionNotification.status.contains(0x00.toByte())
                if (inEar) {
                    svc.applicationContext.sendBroadcast(
                        Intent("io.nikos.propods.cross_device_island").setPackage(svc.packageName)
                    )
                }
            }
        }
    }

    fun sendRemotePacket(data: ByteArray) {
        if (data.isEmpty()) return
        val dead = mutableListOf<BluetoothSocket>()
        for (socket in clientSockets) {
            sendToSocket(socket, data) { dead.add(socket) }
        }
        if (dead.isNotEmpty()) clientSockets.removeAll(dead)
    }

    private fun sendToSocket(socket: BluetoothSocket, data: ByteArray, onFail: (() -> Unit)? = null) {
        val hex = data.joinToString("") { "%02x".format(it) }
        try {
            socket.outputStream.write(data)
            socket.outputStream.flush()
            Log.d(TAG, "Sent to ${socket.remoteDevice.address}: $hex")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send to ${socket.remoteDevice.address}: ${e.message}")
            onFail?.invoke()
        }
    }

    fun notifyConnected() {
        sendRemotePacket(CrossDevicePackets.AIRPODS_CONNECTED.packet)
        CrossDeviceClient.send(CrossDevicePackets.AIRPODS_CONNECTED.packet)
    }
    fun notifyDisconnected() {
        sendRemotePacket(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
        CrossDeviceClient.send(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
    }

    fun close() {
        CrossDeviceClient.stop()
        serverSocket?.runCatching { close() }
        clientSockets.forEach { it.runCatching { close() } }
        clientSockets.clear()
        serverSocket = null
        isAvailable = false
        isEnabled = false
        isServerRunning = false
    }

    @SuppressLint("MissingPermission")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("cross_device_enabled", enabled)
            .apply()
        if (enabled) {
            init(context)
        } else {
            close()
        }
    }
}
