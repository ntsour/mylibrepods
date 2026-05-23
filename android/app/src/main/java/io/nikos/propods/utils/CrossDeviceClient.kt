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
    along with this program, if not, see <https://www.gnu.org/licenses/>.
*/

package io.nikos.propods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import io.nikos.propods.services.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// RFCOMM client used when this device acts as the secondary (tablet/client) in an
// Android-to-Android handover. Connects to the peer's RFCOMM server and exchanges
// the same 4-byte packet protocol used by the Windows tray client.
object CrossDeviceClient {
    private const val TAG = "CrossDeviceClient"
    private val RFCOMM_UUID = java.util.UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")

    // Interval for keep-alive pings while the RFCOMM channel is idle.
    // Sends REQUEST_CONNECTION_STATUS so Android's BT power-management doesn't
    // drop the ACL link between handover events.
    private const val KEEPALIVE_INTERVAL_MS = 20_000L

    // While the AirPods are connected to THIS device we must not page the peer:
    // a failed RFCOMM connect pages the BT radio for several seconds, and doing
    // that repeatedly on the same single radio that carries the AirPods
    // A2DP+AACP link causes RF contention that drops the headset. We don't need
    // to reach the peer while we hold the AirPods anyway. Re-check on this
    // interval; resume normal retries once the AirPods are no longer ours.
    private const val HEADSET_HELD_RECHECK_MS = 10_000L

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    @Volatile var isConnected: Boolean = false
    private var job: Job? = null

    // Cancelled whenever a "retry now" signal arrives (e.g. ACL_CONNECTED for the peer).
    @Volatile private var backoffJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start(adapter: BluetoothAdapter, peerMac: String) {
        if (running) return
        running = true
        job = CoroutineScope(Dispatchers.IO).launch {
            var backoff = 1_500L
            while (running) {
                // Don't page the peer while we hold the AirPods — the failed
                // RFCOMM connect attempts jam the radio and drop the headset.
                if (ServiceManager.getService()?.isConnected() == true) {
                    Log.d(TAG, "AirPods connected here — deferring CrossDevice connect to protect the headset link")
                    delay(HEADSET_HELD_RECHECK_MS)
                    continue
                }
                try {
                    val device = adapter.getRemoteDevice(peerMac)
                    // Insecure RFCOMM: same rationale as the server side. Avoids
                    // a per-connect auth step that races with other BT activity
                    // on the same ACL and intermittently fails with "read ret: -1".
                    val s = device.createInsecureRfcommSocketToServiceRecord(RFCOMM_UUID)
                    s.connect()
                    socket = s
                    isConnected = true
                    backoff = 1_500L
                    Log.d(TAG, "Connected to peer $peerMac")

                    val svc = ServiceManager.getService()
                    val announcement = if (svc?.isConnected() == true)
                        CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else
                        CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                    s.outputStream.write(announcement)
                    s.outputStream.flush()

                    // Keep-alive: send a harmless REQUEST_CONNECTION_STATUS ping
                    // periodically so Android's BT power-management doesn't drop
                    // the ACL link between handover events.
                    val keepAliveJob = launch {
                        while (true) {
                            delay(KEEPALIVE_INTERVAL_MS)
                            if (!isConnected) break
                            try {
                                s.outputStream.write(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)
                                s.outputStream.flush()
                                Log.d(TAG, "Keep-alive ping sent")
                            } catch (_: Exception) { break }
                        }
                    }

                    val buf = ByteArray(1024)
                    while (running) {
                        val n = try {
                            s.inputStream.read(buf)
                        } catch (e: Exception) {
                            Log.d(TAG, "Read error (peer disconnected): ${e.message}")
                            break
                        }
                        if (n == -1) break
                        processPacket(buf.copyOf(n))
                    }

                    keepAliveJob.cancel()
                    s.runCatching { close() }
                    socket = null
                    isConnected = false
                    Log.d(TAG, "Disconnected from peer, will retry")
                } catch (e: Exception) {
                    val bondState = try {
                        when (adapter.getRemoteDevice(peerMac).bondState) {
                            android.bluetooth.BluetoothDevice.BOND_BONDED -> "BONDED"
                            android.bluetooth.BluetoothDevice.BOND_BONDING -> "BONDING"
                            android.bluetooth.BluetoothDevice.BOND_NONE -> "NONE"
                            else -> "?"
                        }
                    } catch (_: Exception) { "unknown" }
                    Log.d(
                        TAG,
                        "Connect failed [${e.javaClass.simpleName}: ${e.message}] " +
                            "bond=$bondState adapterEnabled=${adapter.isEnabled} " +
                            "discovering=${adapter.isDiscovering}, retrying in ${backoff}ms"
                    )
                    socket?.runCatching { close() }
                    socket = null
                    isConnected = false
                }
                if (running) {
                    // Sleep with a cancellable job so retryNow() can short-circuit
                    // the wait when the peer's ACL comes back up.
                    val bj = launch { delay(backoff) }
                    backoffJob = bj
                    bj.join()
                    backoffJob = null
                    backoff = (backoff * 1.5).toLong().coerceAtMost(60_000L)
                }
            }
        }
    }

    /**
     * Cancel the current backoff delay and retry the RFCOMM connection immediately.
     * Call this when [ACL_CONNECTED] fires for the configured peer MAC, so we
     * don't wait up to 15 s before attempting the RFCOMM layer on top.
     */
    fun retryNow() {
        if (!running || isConnected) return
        Log.d(TAG, "retryNow: short-circuiting backoff (ACL came up)")
        backoffJob?.cancel()
    }

    private fun processPacket(raw: ByteArray) {
        Log.d(TAG, "Received: ${raw.joinToString("") { "%02x".format(it) }}")
        when {
            raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) ->
                ServiceManager.getService()?.disconnectForCD()
            raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) -> {
                // Eagerly claim peer-has-them so a quick reversal press doesn't bail
                // on crossDeviceAvailable=false while waiting for the peer's AACP
                // handshake to complete (which can be 5–60 s after the handover starts).
                Log.d(TAG, "Received REQUEST_HANDOVER (eagerly setting isAvailable=true)")
                CrossDevice.isAvailable = true
                ServiceManager.getService()?.markPeerTakeoverAttempt()
                ServiceManager.getService()?.disconnectForCD()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet) -> {
                CrossDevice.isAvailable = true
                // Peer explicitly announced ownership — if we were expecting a takeover,
                // arm the peer-drop cooldown proactively (don't wait for ACL_DISCONNECTED).
                ServiceManager.getService()?.confirmPeerOwnership()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet) ->
                CrossDevice.isAvailable = false
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_ACTIVE.packet) ->
                CrossDevice.peerAudioActive = true
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_IDLE.packet) ->
                CrossDevice.peerAudioActive = false
            // REQUEST_CONNECTION_STATUS is also used as a keep-alive ping from the peer;
            // reply with our current state.
            raw.contentEquals(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet) -> {
                val svc = ServiceManager.getService()
                send(
                    if (svc?.isConnected() == true) CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                )
            }
        }
    }

    fun send(data: ByteArray) {
        if (data.isEmpty()) return
        val s = socket ?: return
        try {
            s.outputStream.write(data)
            s.outputStream.flush()
            Log.d(TAG, "Sent: ${data.joinToString("") { "%02x".format(it) }}")
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: ${e.message}")
        }
    }

    fun stop() {
        running = false
        isConnected = false
        backoffJob?.cancel()
        job?.cancel()
        job = null
        socket?.runCatching { close() }
        socket = null
    }
}
