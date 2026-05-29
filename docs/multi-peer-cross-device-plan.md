# Multi-peer cross-device handover — design plan (deferred)

Status: **not implemented.** This captures the design and the complete inventory
of single-peer assumptions so the work can be resumed in a future session. The
current code supports a robust **2-device** setup only (the server is restricted
to the single configured peer; see `CrossDevice.configuredPeerMac`).

## Why / value

Today each device stores exactly one peer (`cross_device_peer_mac`, a single
string) and the client (`CrossDeviceClient`) is a single-socket singleton that
dials only that one peer. The server accepts many inbound clients, but the client
reaches one. Consequences with >2 devices:

- **3 Android:** can't configure a full mesh (one peer slot each → ring at best);
  `isAvailable` and battery relay are single-valued and get clobbered by
  whichever peer spoke last; the courtesy filter silently fails when the actual
  holder isn't the requester's connected peer → tug-of-war returns.
- **2 Android + 1 Windows:** better, because Windows actively clients into both
  Android servers, but Android↔Android is still one-slot-limited and state still
  clobbers. (Windows also needs its own protocol update to answer the
  holder-authoritative `REQUEST_TAKEOVER` verdict.)

What multi-peer wins: N configurable peers, a maintained link to every peer,
correct per-peer "pods are elsewhere"/battery, courtesy filter that works in 3+
devices, deterministic restricted topology, and a UI to see/manage all peers.

## Single-peer breaking points (inventory)

Files: `app/src/main/java/io/nikos/propods/utils/CrossDevice.kt`,
`…/utils/CrossDeviceClient.kt`, `…/services/AirPodsService.kt`,
`…/presentation/viewmodel/AirPodsViewModel.kt`,
`…/presentation/components/ConnectionSettings.kt`.

1. **Peer storage** — `cross_device_peer_mac` single string (CrossDevice.init;
   AirPodsViewModel state + `setCrossDevicePeerMac`). → needs a peer **set**.
2. **Role election** — `maybeStartClient(peerMac)` compares own vs one peer name.
   → run per peer (client to peers whose name sorts higher; server-only for the
   rest), giving exactly one channel per pair.
3. **Client is single-socket** — `CrossDeviceClient` object holds one `socket`,
   one `running`, one retry/backoff loop, one keep-alive job. **Main bottleneck.**
   → `Map<peerMac, link>` with per-peer socket + retry/backoff + keep-alive.
4. **Single-valued shared state** in `CrossDevice`:
   - `isAvailable: Boolean` ("pods on a remote peer") → clobbered by last speaker.
     → `holders: MutableSet<String>` (which peer MACs hold them); `isAvailable`
     becomes `holders.isNotEmpty()`.
   - `peerAudioActive` (Windows audio) → per-peer map (low priority).
   - `batteryBytes` / `ancBytes` relay → per-peer map (only one holder at a time,
     so single value mostly works but can go stale).
   - `peerDeniedTakeover` latch → already aggregate ("any deny"); N-safe as-is.
5. **`processPacket` is source-unaware** — both `CrossDevice.processPacket` and
   `CrossDeviceClient.processPacket` take only the bytes. To attribute
   `holders`/state per peer, thread the **source peer MAC** into processPacket.
6. **Send paths** — `sendRemotePacket` (server→all clients) + `CrossDeviceClient.send`
   (→ the one client socket). → add `CrossDeviceClient.sendAll()`; broadcast
   helpers (`notifyConnected/Disconnected`, `requestTakeoverVerdict`) must reach
   all server clients **and** all client links.
7. **UI / ViewModel** — `crossDevicePeerMac: String?` + `crossDevicePeerConnected:
   Boolean`; `pollCrossDeviceStatus` ORs server/client into one boolean. → peer
   **list** with per-peer connected + has-pods status.
8. **Init / config change** — `init` loads one MAC; `setCrossDevicePeerMac` /
   `reconnectCrossDevice` tear down and re-init the whole system. → per-peer
   add/remove without global teardown.

Server side is otherwise N-ready: `clientSockets` is a `CopyOnWriteArrayList`,
each accepted socket gets its own coroutine + keep-alive.

## Target design

- **Persistence:** `cross_device_peers` (JSON array / comma-set of MACs). Migrate
  existing `cross_device_peer_mac` into it on first load. Keep global
  `cross_device_enabled`.
- **Server:** keep multi-client accept; restrict inbound to the **configured peer
  set** (extend the single-peer check already in `startServer`). Thread source MAC
  into `handleClientConnection` → `processPacket(raw, sourceMac)`.
- **Client (`CrossDeviceClient`):** `Map<peerMac, PeerLink>`; `start(adapter, peers)`
  spins a per-peer connect/retry(backoff)/keep-alive coroutine for each peer where
  role election elects this device client. `send(mac)`, `sendAll()`,
  `isConnected(mac)`, `connectedPeers`.
- **Per-peer role election:** for each peer, `ownName < peerName` ⇒ client, else
  server-only. One channel per pair; avoids the "read ret: -1" duplicate-channel
  storm.
- **State:** `holders: Set<String>`; per-peer maps for battery/anc/audioActive as
  needed. Courtesy `peerDeniedTakeover` latch stays (aggregate any-deny across all
  peers; requester already reaches all).
- **Send:** broadcast helpers fan out to all server clients + all client links.
- **UI:** new `PairedDevicesScreen` (mirror `ConnectionSettingsScreen` +
  `StyledScaffold`/`StyledSelectList`): list configured peers with name + status
  dot + remove; "Add device" → bonded-device picker (reuse the existing
  `showPeerPicker` dialog pattern in `ConnectionSettings.kt`). Reach it from a
  `NavigationButton`/row in Connection Settings, replacing the single
  `PeerConnectionPanel`. New nav route in `MainActivity.kt`.
- **ViewModel:** `crossDevicePeers: List<PeerUiInfo(mac, name, connected, hasPods)>`;
  `addCrossDevicePeer` / `removeCrossDevicePeer` / `reconnectCrossDevicePeer`;
  poll per-peer.
- **Windows:** the Windows app must also speak multi-peer + the `REQUEST_TAKEOVER`
  / `COURTESY_GRANT`/`COURTESY_DENY` verdict to fully participate.

## Verification (when implemented)

3 Android devices, each with the other two in its peer set. Confirm: each device
maintains a live link to both peers; only one holds A2DP at a time and the other
two show it as "has pods"; pressing play on a non-holder runs the courtesy verdict
against the real holder (DENY blocks, GRANT/timeout pulls); no tug-of-war; the
Paired Devices screen lists all peers with correct per-device status; add/remove a
peer without dropping the others.
