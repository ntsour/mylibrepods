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

## Implementation Phases

### Phase 1 — Data layer

Breaking points: #1 (peer storage), #4 partial (holders set replacing isAvailable).

**Changes:**
- Replace `cross_device_peer_mac: String` with `cross_device_peers: Set<String>` (JSON
  array in SharedPreferences). On first load, migrate the existing single-MAC value into
  the set and delete the old key.
- Replace `CrossDevice.isAvailable: Boolean` with `holders: MutableSet<String>`.
  `isAvailable` becomes a computed property (`holders.isNotEmpty()`).

**Unit tests (`CrossDeviceStorageTest`):**
- Migration: given SharedPreferences with `cross_device_peer_mac = "AA:BB:CC:DD:EE:FF"`,
  after `CrossDevice.init(...)`, assert `cross_device_peers` contains that MAC and
  `cross_device_peer_mac` key is absent.
- Migration idempotent: running init a second time does not duplicate the MAC.
- Migration no-op: if `cross_device_peer_mac` is absent, `cross_device_peers` is
  unchanged.
- Existing `cross_device_peers` read without touching legacy key.
- `isAvailable` / `holders`: `isAvailable = true` with one configured peer → `holders`
  contains that MAC, `isAvailable == true`; then `isAvailable = false` → holders empty.
- `isAvailable = true` with no configured peers → holders stays empty.

**On-device smoke test (logcat only):**
- Fresh install: check log for migration message and correct MAC set.
- Restart: confirm the set persists.

---

### Phase 2 — Transport layer

Breaking points: #2 (role election), #3 (multi-socket client), #5
(source-aware processPacket), #6 (send paths).

**Changes (implemented):**
- Per-pair role election extracted to pure `CrossDevice.electClient(ownName, peerName)`;
  `init()` builds the elected client-peer set and calls `CrossDeviceClient.start(adapter, peers)` once.
- `CrossDeviceClient` is now a `ConcurrentHashMap<peerMac, PeerLink>`; each `PeerLink`
  owns its own socket, retry/backoff coroutine (cancellable via `retryNow(mac)`), and
  keep-alive job. `start()` **reconciles** the set (per-peer add/remove, no global
  teardown); `send(mac)`, `sendAll()`, `isConnected(mac)`, `connectedPeers`, `stop(mac)`.
- The two `processPacket`s are **unified**: `CrossDevice.processPacket(raw, sourceMac)`
  is the single protocol handler; the client read loop delegates to it with `peerMac`,
  the server with the inbound socket's `addr`.
- Ownership is source-attributed: `holders.add/remove(sourceMac)` instead of a single
  `isAvailable` flag.
- **Replies are source-targeted** via `CrossDevice.sendTo(mac)` (prefer the inbound
  server socket from that MAC, else the outbound client link) — so a courtesy verdict
  for one requester can't be mis-latched by an unrelated peer (the real 3-device fix).
  Broadcast helpers (`notifyConnected/Disconnected`, `requestTakeoverVerdict`) fan out
  to all server clients **and** all client links via a private `broadcast()`.

**Unit tests (implemented — 11 new, all green):**

*Role election (`RoleElectionTest`, 5 — pure, no Android):* lower name → CLIENT /
higher → SERVER-only; exactly one client per pair; identical names → both server-only
(case-insensitive); null/blank → server-only; three-peer mixed election.

*Source-aware state (`CrossDeviceStateTest`, 6 — Robolectric):* `AIRPODS_CONNECTED`
adds source to `holders`; a second peer doesn't clobber the first; `AIRPODS_DISCONNECTED`
removes only that source; last holder leaving clears `isAvailable`; relayed
`AIRPODS_DATA` marks the source; unrecognized packet is a no-op.

**Send paths / coroutine lifecycle — covered on-device, not unit-tested.** Real socket
fan-out, per-link retry/backoff, keep-alive, and cancellation can't be meaningfully
unit-tested without heavy `BluetoothSocket` mocking that mostly tests the mocks. These
are validated via the logcat smoke test below.

**On-device smoke test (logcat only):**
- Two devices: confirm exactly one `Role election […]: CLIENT` log per pair, no
  duplicate channels, no "read ret: -1" storm.
- Kill the service on one device: confirm the other logs only that peer's link dropped
  (`[mac] disconnected, will retry`), not a full teardown of the other link.
- Three devices: trigger a takeover from a non-holder; grep each device for the
  `[mac] received … COURTESY_GRANT/DENY` lines and confirm only the requester latches.

---

## Verification (when implemented)

3 Android devices, each with the other two in its peer set. Confirm: each device
maintains a live link to both peers; only one holds A2DP at a time and the other
two show it as "has pods"; pressing play on a non-holder runs the courtesy verdict
against the real holder (DENY blocks, GRANT/timeout pulls); no tug-of-war; the
Paired Devices screen lists all peers with correct per-device status; add/remove a
peer without dropping the others.

---

## On-device test log (Pixel 10 / Samsung A52s / Xiaomi 24091RPADG)

Real 3-device run (multi-peer build, applicationId `io.nikos.propods.multipeer`,
installed alongside stable). Because the Phase 4 UI isn't built, each device's
`cross_device_peers` (and the AirPods `mac_address`) were seeded manually via
`adb run-as … tee shared_prefs/settings.xml` (SELinux blocks `sh`/`cp` redirects
into app data; `tee` works).

**Results so far:**
- **D1 — mesh + role election: PASS.** All 3 pairs elect exactly one client, both
  sides agree, 3 live links (one device holds 2 outbound, another 2 inbound). A stray
  Windows ProPods client is correctly rejected by the inbound filter.
- **D2 — handover (Pixel→Samsung/Xiaomi): PASS.** Audio moves + resumes.
- **D3 — 3-device courtesy filter: PASS.** Holder DENY blocks; toggle-on GRANT
  proceeds (`replies=2/2`). Non-holder does not mis-latch the holder's DENY.

**Bugs found & fixed during this run (each only surfaced with 3 real devices):**

1. **Parallel-install resource crash.** Hardcoded `android.resource://io.nikos.propods/…`
   URIs (island/popup VideoView, `resToUri`) pointed at the *stable* app under the new
   applicationId → `AppsFilter` blocked it → `VideoView.onError` → `BadTokenException`
   crash loop on any connection event. Fixed to use the runtime `packageName`. (Other
   hardcoded `io.nikos.propods` refs remain — FileProvider/companion metadata, log
   collection, Xposed prefs — non-crashing, mostly Xposed/root paths; cleanup deferred.)
2. **Limited-mode holder under-reported ownership.** The keep-alive status reply used
   `isConnected()` (AACP/L2CAP only → always false on a limited device), so a limited
   holder kept telling peers `AIRPODS_DISCONNECTED` while actually holding the A2DP
   link — wiping it from every peer's `holders` set. Added `holdsAirPods()` =
   `isConnected() || isA2dpConnected()` and used it at the 3 ownership-reporting sites.
3. **Courtesy veto race (fail-open).** The fixed 250 ms verdict window was too tight:
   a cold-RFCOMM DENY round-trip measured 283 ms (and later 500 ms), arriving after the
   window → takeover fail-opened and grabbed despite the holder's DENY. Replaced with
   **wait-for-all-replies**: `requestTakeoverVerdict()` returns the expected reply count
   (`connectedPeerCount`), `COURTESY_GRANT`/`DENY` both increment a counter, and the
   requester exits as soon as all peers answer, any DENY arrives, or a 600 ms ceiling
   (fail-open for silent/old-build peers). Fast replies keep the common case quick.

- **D4 — holder attribution: PARTIAL PASS + architectural gap found.** A peer sees a
  holder only if it is the *client* side of that pair. When the **holder is the client
  side** of a pair, it defers all outbound connects while holding (the pre-existing
  "don't page peers while holding the headset" radio-protection heuristic,
  `HEADSET_HELD_RECHECK_MS`), so the *server-only* peer never gets a link and is blind
  to the holder. Observed: Pixel (holding, playing) is CLIENT to Xiaomi → Pixel defers
  → Xiaomi has zero packets from Pixel → Xiaomi's `holders` never includes Pixel.
  Samsung (CLIENT to Pixel) saw it correctly.
- **Courtesy veto gap (confirmed, follow-on from D4) → RESOLVED by design change.**
  Xiaomi->Pixel takeover with the Pixel's "Playing media" toggle OFF: Xiaomi grabbed
  anyway. Pixel was client-side, deferred, invisible to Xiaomi; `REQUEST_TAKEOVER` only
  reached Samsung; `expectedReplies=1`, waited 600ms, pulled anyway. Root: the veto is
  structurally impossible when the holder is invisible to the requester. **Decision:**
  remove the veto entirely. Every takeover trigger is an explicit user action (media
  start / call) so the requester's intent is unambiguous — the holder should always yield.
  `takeoverWhenIdle` / `takeoverWhenMusic` / `takeoverWhenCall` UI toggles removed;
  `courtesyDeniesTakeover()` is a stub returning `false`; `REQUEST_TAKEOVER` round-trip
  removed from `takeOver()`; legacy packets answered with GRANT for old-build compat.
  See "Requester Always Wins" section in `handover.md`.

**Phase 3 design item — holder reachability** (holder attribution only, veto no longer
relevant). Root: the deferral heuristic + role election can leave a holder unreachable
by a server-only peer, so that peer's `holders` set misses the holder. The courtesy veto
gap is now gone (requester always wins), but accurate `holders` attribution still matters
for `isAvailable` ("are pods elsewhere?"). In steady state (all links established while
idle, kept alive without re-paging) the gap does not appear. Viable fixes if needed:
gossip `AIRPODS_CONNECTED`/`AIRPODS_DISCONNECTED` through an intermediary peer, or relax
the deferral for a single connect when in `holders`.

- **D5a — link-drop isolation: PASS.** Force-stopping one device's app dropped only
  that device's links on each peer; the remaining pair's link stayed fully alive
  (continuous keep-alive traffic). Confirms the per-link `Map<peerMac, PeerLink>`
  isolation — one peer dying never tears down the others.
- **D5b — no-steal logic: PASS (with an important side-finding).** No `takeOver`/
  `connectAudio` fired on any device during the link drop — the no-steal policy held on
  both sides (each logs `A2DP isn't ours … skip reconnect`, i.e. refuses to grab). But
  the force-stop+restart **test method itself** disrupted the holder twice in a row:
  see the "app restart pages bonded AirPods" finding below. So the no-steal *assertion*
  is validated, but it was not a pristine RFCOMM-only drop.

**Phase 3 finding — app restart pages the bonded AirPods.** Restarting a peer's app
(force-stop+relaunch) twice knocked the AirPods off the holder. Trace: on startup the
restarted peer binds the `BluetoothA2dp` proxy and does an SDP/UUID query on the AirPods
(`ACTION_UUID` → `ACL_CONNECTED` for `…97:86`), because that peer is **bonded** to the
AirPods (policy ALLOWED). That pages the single-source AirPods over to the restarting
peer and displaces the current holder. ProPods then correctly declines to grab
(`skip reconnect` on both sides) — so it is **not** a steal — but the OS-level page has
already disrupted the holder. This is latent in *any* deployment: every multi-peer peer
is bonded to the AirPods, so a service restart (OS kill / app reopen / reboot) on a
non-holding peer can interrupt whoever holds them. **Fix direction:** gate the startup
AirPods SDP/connect behind `mayProactivelyConnect()` — a non-holding peer in shared mode
must not poke the AirPods while a peer holds them. (Found via "two disconnects in a row
is not random" — the Apple auto-switch explanation for the first one was wrong.)

**Still to run:** reverse handover directions; per-peer add/remove (Phase 4 UI).

**Net: Phase 2 transport validated on real 3-device hardware** — D1, D2, D3, D5 pass;
4 bugs found & fixed (parallel-install resource crash, limited-mode ownership reporting,
courtesy veto race, courtesy veto structurally broken in 3-device).

**Phase 3 — DONE.** App-restart pages bonded AirPods fix: `bondedDevices.forEach` in
`AirPodsService.onCreate` now skips `fetchUuidsWithSdp()` entirely when `inSharedMode`
(`CrossDevice.isEnabled && configuredPeers.isNotEmpty()`). BLE background scan + explicit
user intent (play / call) are sufficient for handover in shared mode. Non-shared mode
is unaffected (normal UUID discovery runs).

**Known edge case (Phase 3 startup fix):** if a device has peers configured but
`macAddress` is still empty (peers were added before AirPods were ever paired to this
device), the UUID scan AND the BLE path both gate on a non-empty MAC → auto-discovery
is dead until the user taps Reconnect once (which seeds the MAC). Unlikely in practice
(AirPods are normally paired first) but worth noting. Aligns with handover principle
"follow user attention on manual action."

**Design change post-Phase 2:** courtesy veto mechanism removed. Requester always wins —
all takeover triggers are explicit user intent (media start / call answered). Holder-state
toggles (`takeoverWhenIdle/Music/Call`) removed from UI. See "Requester Always Wins" in
`handover.md` and the D4/veto gap finding above for rationale.

**Test-harness notes / setup gotchas:**
- A taker must have the **AirPods bonded** to it (not just the peer phones) — a fresh
  device with no AirPods bond fails takeover with `device not found in bondedDevices!`.
- AirPods Apple auto-switch-back (handover.md Limitation #6) was observed live (~pods
  left a holder unprompted ~90 s after a handover) — expected OS behaviour, handled
  cleanly (correct DISCONNECTED reporting, no false cooldown).
