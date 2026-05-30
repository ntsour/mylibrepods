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
import android.content.SharedPreferences
import android.util.Log
import io.nikos.propods.services.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

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
    // Courtesy-filter handshake (holder-authoritative). The requester broadcasts
    // REQUEST_TAKEOVER; each peer replies COURTESY_DENY only if IT currently holds
    // the AirPods and its own toggle forbids interruption in its current state,
    // otherwise COURTESY_GRANT. The requester latches "did anyone deny?" — N-device
    // safe (only the real holder denies; any deny blocks; no deny → pull).
    REQUEST_TAKEOVER(byteArrayOf(0x00, 0x02, 0x00, 0x05)),
    COURTESY_GRANT(byteArrayOf(0x00, 0x06, 0x00, 0x00)),
    COURTESY_DENY(byteArrayOf(0x00, 0x06, 0x00, 0x01)),
}

object CrossDevice {
    private const val TAG = "CrossDevice"
    private val UUID_CROSS_DEVICE = UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")

    // Keep-alive ping interval for server-side sockets, mirroring CrossDeviceClient.
    // Server-only role (when the peer's MAC sorts lower than ours) has no app traffic
    // between handovers, so without this Android's BT power-management can drop the ACL.
    private const val SERVER_KEEPALIVE_INTERVAL_MS = 20_000L

    var isEnabled: Boolean = false

    /** Set of configured peer MACs. Loaded from `cross_device_peers` on [init];
     *  migrates from the legacy `cross_device_peer_mac` single-string key on first
     *  load. The RFCOMM server only accepts inbound connections from this set. */
    var configuredPeers: Set<String> = emptySet()
        internal set

    /** Which peer MACs currently hold the AirPods. `isAvailable` is a computed
     *  view of this set. Phase 2 will attribute each entry to the exact source MAC;
     *  Phase 1 uses the full configured peer set as a proxy. */
    val holders: MutableSet<String> = CopyOnWriteArraySet()

    /** True when any peer holds the AirPods (i.e. [holders] is non-empty).
     *  Setting to true adds all [configuredPeers] to [holders]; setting to false clears it.
     *  All existing call-sites in AirPodsService continue to work unchanged. */
    var isAvailable: Boolean
        get() = holders.isNotEmpty()
        set(value) { if (value) holders.addAll(configuredPeers) else holders.clear() }

    /** True when Windows has reported an active audio session (call/meeting) on the AirPods endpoint. */
    var peerAudioActive: Boolean = false

    var batteryBytes: ByteArray = byteArrayOf()
    var ancBytes: ByteArray = byteArrayOf()

    /** True when at least one RFCOMM client is connected to our server, or our client is connected. */
    val isServerClientConnected: Boolean get() = clientSockets.isNotEmpty()
    val isPeerConnected: Boolean get() = isServerClientConnected || CrossDeviceClient.isConnected

    /** True when the specific peer [mac] is reachable — either via an inbound server socket
     *  or via our outbound client link. Used by the UI to show per-peer connection status. */
    fun isConnectedTo(mac: String): Boolean =
        clientSockets.any { it.remoteDevice.address.equals(mac, ignoreCase = true) } ||
        CrossDeviceClient.isConnected(mac)

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<BluetoothSocket>()
    @Volatile private var isServerRunning: Boolean = false

    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        configuredPeers = loadAndMigratePeers(prefs)
        // Auto-enable when a peer is saved but the flag was never explicitly set
        // (covers devices that configured a peer before this flag existed).
        isEnabled = prefs.getBoolean("cross_device_enabled", configuredPeers.isNotEmpty())
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

        // Per-pair role election: client to peers whose name sorts higher than ours,
        // server-only for the rest. Reconcile the client links against that set in
        // one call so existing links aren't torn down (and re-added) needlessly.
        val clientPeers = configuredPeers.filter { shouldBeClientTo(adapter, it) }.toSet()
        CrossDeviceClient.start(adapter, clientPeers)
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
     *
     * Pure predicate (no side effects): the caller reconciles the elected client set
     * against the live links via [CrossDeviceClient.start].
     */
    @SuppressLint("MissingPermission")
    private fun shouldBeClientTo(adapter: android.bluetooth.BluetoothAdapter, peerMac: String): Boolean {
        val ownName = adapter.name
        val peerName = runCatching { adapter.getRemoteDevice(peerMac).name }.getOrNull()
        val client = electClient(ownName, peerName)
        Log.d(TAG, "Role election [$peerMac]: ${if (client) "CLIENT" else "SERVER-only"} (own='$ownName' peer='$peerName')")
        return client
    }

    /** Pure role-election predicate (extracted for testing). Returns true iff this
     *  device should be the CLIENT for the pair. SERVER-only when names are unusable
     *  (null/blank or equal): no client started, so no duplicate-channel collision. */
    internal fun electClient(ownName: String?, peerName: String?): Boolean {
        if (ownName.isNullOrBlank() || peerName.isNullOrBlank() || ownName.equals(peerName, ignoreCase = true)) {
            return false
        }
        return ownName.compareTo(peerName, ignoreCase = true) < 0
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
                // Accept only configured peers. Reject any other bonded ProPods device
                // so stale/stray peers can't connect and pollute cross-device state.
                val addr = socket.remoteDevice.address
                if (configuredPeers.none { it.equals(addr, ignoreCase = true) }) {
                    Log.w(TAG, "Rejecting inbound CrossDevice connection from $addr — not in configured peers ($configuredPeers)")
                    socket.runCatching { close() }
                    continue
                }
                Log.d(TAG, "Client connected: $addr (${clientSockets.size + 1} total)")
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
            if (ServiceManager.getService()?.holdsAirPods() == true)
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
            processPacket(buffer.copyOf(bytes), addr)
        }

        keepAliveJob.cancel()
        socket.runCatching { close() }
        clientSockets.remove(socket)
        Log.d(TAG, "Client $addr removed (${clientSockets.size} remaining)")

        // NOTE: deliberately do NOT touch `isAvailable` here. A dropped RFCOMM
        // coordination socket means we lost VISIBILITY of the peer (Android Doze
        // routinely kills this idle link — that's why the 20 s keep-alive exists,
        // and OEM process-killers do the same), NOT that the peer released the
        // AirPods. Flipping isAvailable=false used to make this device believe the
        // pods were free and race to grab them, causing an A2DP tug-of-war.
        // Ownership only changes on an explicit AIRPODS_DISCONNECTED packet from
        // the peer (handled in processPacket) or our own intentful takeover.
    }

    /**
     * Handle one inbound packet from [sourceMac]. Shared by both transport sides —
     * the server read loop ([handleClientConnection]) and the per-peer client links
     * ([CrossDeviceClient]) both route here, so the protocol lives in one place.
     *
     * Ownership is attributed to [sourceMac]: a peer announcing/relaying ownership is
     * added to [holders]; a peer announcing release is removed. Replies (status,
     * relayed bytes, courtesy verdict) are sent **back to [sourceMac]** via [sendTo],
     * not broadcast — so a courtesy verdict for one requester can't be mis-latched by
     * an unrelated peer (the 3-device correctness fix).
     */
    internal fun processPacket(raw: ByteArray, sourceMac: String) {
        Log.d(TAG, "[$sourceMac] received: ${raw.joinToString("") { "%02x".format(it) }}")
        when {
            raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) -> {
                // Peer wants the AirPods — release them if we hold the connection.
                // Eagerly mark the peer as holder: for *our* takeOver gate
                // ("crossDeviceAvailable=false → bail") to work on a quick reversal
                // play press, the peer must register as "having them" immediately —
                // without waiting for its AACP handshake and eventual AIRPODS_CONNECTED
                // reply, which can be 5–60 s later.
                Log.d(TAG, "[$sourceMac] REQUEST_HANDOVER — releasing AirPods (eagerly marking peer as holder)")
                holders.add(sourceMac)
                ServiceManager.getService()?.markPeerTakeoverAttempt()
                ServiceManager.getService()?.disconnectForCD()
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) -> {
                // Mark that a peer is taking over so we apply cooldown appropriately
                ServiceManager.getService()?.markPeerTakeoverAttempt()
                ServiceManager.getService()?.disconnectForCD()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet) -> {
                holders.add(sourceMac)
                // Peer explicitly announced ownership — if we were expecting a takeover,
                // arm the peer-drop cooldown proactively (don't wait for ACL_DISCONNECTED).
                ServiceManager.getService()?.confirmPeerOwnership()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet) -> {
                holders.remove(sourceMac)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_BATTERY_BYTES.packet) -> {
                sendTo(sourceMac, batteryBytes)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_ANC_BYTES.packet) -> {
                sendTo(sourceMac, ancBytes)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet) -> {
                sendTo(
                    sourceMac,
                    if (ServiceManager.getService()?.holdsAirPods() == true)
                        CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else
                        CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                )
            }
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_ACTIVE.packet) -> {
                peerAudioActive = true
                Log.d(TAG, "[$sourceMac] Windows reports active audio session (call/meeting in progress)")
            }
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_IDLE.packet) -> {
                peerAudioActive = false
                Log.d(TAG, "[$sourceMac] Windows reports audio session idle")
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_TAKEOVER.packet) -> {
                // Older builds send REQUEST_TAKEOVER before taking over; always grant so
                // they can proceed. We no longer use this round-trip ourselves — requester
                // always wins (see design rationale in handover.md).
                sendTo(sourceMac, CrossDevicePackets.COURTESY_GRANT.packet)
            }
            raw.contentEquals(CrossDevicePackets.COURTESY_DENY.packet) -> {
                // No-op: veto mechanism removed. Log for diagnostics only.
                Log.d(TAG, "[$sourceMac] COURTESY_DENY received (veto removed — ignored)")
            }
            raw.contentEquals(CrossDevicePackets.COURTESY_GRANT.packet) -> {
                // No-op: veto mechanism removed.
            }
            raw.size >= 4 && raw.sliceArray(0..3)
                .contentEquals(CrossDevicePackets.AIRPODS_DATA_HEADER.packet) -> {
                holders.add(sourceMac)
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

    /** Send a packet to one specific peer, regardless of which transport side reaches
     *  it: prefer an inbound server socket from that MAC, else our outbound client link. */
    fun sendTo(mac: String, data: ByteArray) {
        if (data.isEmpty()) return
        val socket = clientSockets.find { it.remoteDevice.address.equals(mac, ignoreCase = true) }
        if (socket != null) {
            sendToSocket(socket, data) { clientSockets.remove(socket) }
            return
        }
        CrossDeviceClient.send(mac, data)
    }

    /** Always grant: requester wins. Kept for backwards compat — old builds that
     *  send REQUEST_TAKEOVER receive GRANT so they can proceed. */
    @Suppress("unused")
    fun courtesyReplyPacket(): ByteArray = CrossDevicePackets.COURTESY_GRANT.packet

    /** Broadcast a packet to every peer: all inbound server clients **and** all
     *  outbound client links. The two transport sides are disjoint sets of peers
     *  (role election gives each pair exactly one direction), so this reaches all. */
    private fun broadcast(data: ByteArray) {
        sendRemotePacket(data)
        CrossDeviceClient.sendAll(data)
    }

    fun notifyConnected() {
        broadcast(CrossDevicePackets.AIRPODS_CONNECTED.packet)
    }
    fun notifyDisconnected() {
        broadcast(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
    }

    fun close() {
        CrossDeviceClient.stop()
        serverSocket?.runCatching { close() }
        clientSockets.forEach { it.runCatching { close() } }
        clientSockets.clear()
        serverSocket = null
        holders.clear()
        configuredPeers = emptySet()
        isEnabled = false
        isServerRunning = false
    }

    private fun loadAndMigratePeers(prefs: SharedPreferences): Set<String> {
        val peersJson = prefs.getString("cross_device_peers", null)
        if (peersJson != null) return parsePeerSet(peersJson)

        val legacyMac = prefs.getString("cross_device_peer_mac", null)
            ?: return emptySet()

        val set = setOf(legacyMac)
        prefs.edit()
            .putString("cross_device_peers", peerSetToJson(set))
            .remove("cross_device_peer_mac")
            .apply()
        Log.d(TAG, "Migrated cross_device_peer_mac → cross_device_peers: $legacyMac")
        return set
    }

    private fun parsePeerSet(json: String): Set<String> = buildSet {
        val arr = JSONArray(json)
        repeat(arr.length()) { add(arr.getString(it)) }
    }

    private fun peerSetToJson(peers: Set<String>): String =
        JSONArray(peers.toList()).toString()

    @Suppress("unused")
    internal fun resetForTesting() {
        holders.clear()
        configuredPeers = emptySet()
        isEnabled = false
        isServerRunning = false
        peerAudioActive = false
        batteryBytes = byteArrayOf()
        ancBytes = byteArrayOf()
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
