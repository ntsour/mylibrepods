# Cross-Device AirPods Handover

---

## What Actually Happens (Plain English)

Imagine you're listening to a podcast on your phone (Pixel) through your AirPods and
you want to switch to your other phone (Xiaomi) — maybe because you're walking over to
your desk. Here's what happens step by step, with no technical jargon:

**1. You press play on Xiaomi.**
Xiaomi notices you want audio. It looks up "do I have the AirPods right now?" — it
doesn't. But it knows the Pixel does.

**2. Xiaomi tells Pixel: "hand them over."**
Both phones keep a quiet background connection between them over Bluetooth (not the
AirPods connection — a separate one). Xiaomi sends a short message through that channel:
"I need the AirPods, please let go."

**3. Pixel pauses its music and releases.**
Pixel hears the message, pauses whatever was playing, and tells the Bluetooth stack to
let go of the AirPods. On a Pixel with Xposed (but without root), this is more of a
polite request to the stack than a hard cut — but it's usually enough.

**4. Xiaomi reaches out to the AirPods.**
Xiaomi sends a Bluetooth connection request directly to the AirPods. Because AirPods
can only be actively connected to one audio source at a time, they drop Pixel and
accept Xiaomi. This is the same thing that happens when you go to your phone's
Bluetooth settings and tap "Connect" — the AirPods always accept.

**5. Music resumes automatically.**
Once the AirPods switch over and audio is routing through Xiaomi, ProPods presses
play on your behalf. On Xiaomi specifically, some apps (like Pocket Casts) briefly
pause themselves when the audio device changes — ProPods watches for this and
presses play again automatically, for up to 15 seconds after the switch, until it's
confident the audio has settled.

---

**What if the phones are too far apart to reach each other?**

If Pixel is out of Bluetooth range when you press play on Xiaomi, step 2 (the
"hand them over" message) never arrives. But it doesn't matter — because the
AirPods already dropped their connection to Pixel when the Bluetooth range was lost.
Xiaomi just reaches out to the AirPods directly and they connect immediately with
nothing to fight over.

---

**What if neither phone is rooted?**

The handover still works in the common case. The "Connect" request Xiaomi sends to
the AirPods is the same request the system Bluetooth settings app sends — and
AirPods always accept it from a paired device. The only scenario where it reliably
fails is if both phones are non-rooted, in range, and the source phone is still
holding the connection and hasn't dropped it yet. In that case the user would need
to manually disconnect on the source phone.

---

**What if the phones lose their background connection but the AirPods are still playing?**

The background connection between the two phones (the RFCOMM channel) and the AirPods
audio connection are completely separate Bluetooth links. It is perfectly normal for
the phones to lose their connection to each other — because they drifted apart, one
was restarted, or Android killed the background service — while the AirPods keep
playing fine on whichever phone had them.

When you then press play on the other phone, step 2 ("hand them over") is skipped
silently because the background channel is down. The destination phone just reaches
out to the AirPods directly (step 4) without warning the source first. The AirPods
switch over normally — but the source phone's audio cuts abruptly instead of being
paused gracefully beforehand.

---

## Technical Reference

### Device Capability Tiers

The handover code has three meaningful device tiers — not two. AACP access and
the ability to force-disconnect A2DP are **independent capabilities**:

| Tier | AACP (L2CAP socket) | `BLUETOOTH_PRIVILEGED` | Example |
|---|---|---|---|
| **Full** | ✓ | ✓ Rooted or system app | Rooted Pixel |
| **AACP-only** | ✓ | ✗ Not rooted | Pixel 10 (Android 16 QPR3+) |
| **Limited** | ✗ | ✗ | Non-rooted Xiaomi |

**AACP** (the L2CAP socket to the AirPods) gives ownership callbacks and the
ability to send `OWNS_CONNECTION` control commands to the AirPods. On
**Android 16 QPR3+** (Pixel devices with the latest Google Play system update)
and **ColorOS/OxygenOS 16**, the underlying Bluetooth stack bug that required
an Xposed hook (`l2c_fcr_hook`) to open this socket was fixed — AACP works
natively without root or Xposed. On older Android versions it still requires
Xposed + an unlocked bootloader. The fix is expected to land for all devices
in Android 17.

**`BLUETOOTH_PRIVILEGED`** requires root or system-app installation. It unlocks
`BluetoothA2dp.disconnect()` and the privileged form of `BluetoothA2dp.connect()`.
Without it, calling `connect()` still works on most OEM stacks (see below).

**In practice, the Pixel 10 in this project is "AACP-only"** — confirmed by this
log line from `disconnectForCD()` during a real handover:
```
W/AirPodsService: disconnectForCD: no BLUETOOTH_PRIVILEGED, skipping A2DP disconnect
```
The Pixel cannot actively force its A2DP link off. It relies on the destination's
`connect()` displacing it, which the AirPods handle automatically. AACP itself
works natively on this device (Android 16 QPR3) without Xposed.

---

### Why Unprivileged `connect()` Works on Real Devices

The Android framework marks `BluetoothA2dp.connect()` as requiring
`BLUETOOTH_PRIVILEGED` (enforced in AOSP Android 12+). However:

- OEM Bluetooth stacks (HyperOS, Pixel's own build) **relax this check** for
  paired devices initiating connections to themselves. The call returns `true` and
  the connection proceeds.
- This is the same API the system Settings app uses — Settings just has the
  permission by virtue of being a priv-app. On OEM devices, unprivileged apps
  get the same result anyway.
- **`disconnect()` is not relaxed in the same way.** Actively kicking another
  device off an established link still requires privilege on all tested stacks.
  This is why the Pixel skips the force-disconnect and instead relies on the
  AirPods dropping it when the destination connects.

The practical implication: on the devices used in this project (Pixel 10 + Xiaomi
24091RPADG), unprivileged `connect()` succeeds reliably. On a strict AOSP build
without OEM relaxation it may fail.

---

### Glossary

| Term | Meaning |
|---|---|
| **AACP** | Application-Agnostic Communication Protocol — the L2CAP socket ProPods opens to the AirPods for full feature access (ownership, ANC, battery, stem config). Requires Xposed + `l2c_fcr_hook`. |
| **Limited-mode** | Device has no AACP socket. Only standard Bluetooth (A2DP / HFP). No ownership callbacks. Non-rooted Xiaomi is limited-mode in this project. |
| **`BLUETOOTH_PRIVILEGED`** | Android system permission required for forced `BluetoothA2dp.disconnect()` and the strict form of `connect()`. Granted only to rooted or priv-app installs. |
| **RFCOMM channel** | A side-channel Bluetooth serial socket between the two Android peers (UUID `1abbb9a4-10e4-4000-a75c-8953c5471342`), used to send handover coordination packets. Independent of the AirPods connection. |
| **RouteLandPoller** | A 15 s watchdog in `MediaController.kt` that re-issues `sendPlay(force=true)` whenever `audioManager.isMusicActive` drops after a takeover. Handles apps (e.g. Pocket Casts on HyperOS) that auto-pause when the audio device changes. |

---

### Role Election

Each device runs a persistent RFCOMM server. To avoid a double-client collision on
Bluedroid, exactly one device also starts a client connection to the peer:

```
// CrossDevice.kt – maybeStartClient()
val shouldBeClient = adapter.name.compareTo(peerName) < 0   // alphabetical
if (shouldBeClient) CrossDeviceClient.start() else CrossDeviceClient.stop()
```

**Edge case — identical names (or blank):** both devices stay SERVER-ONLY. Neither
starts a client, so the RFCOMM channel never comes up and all handovers silently
degrade to "out of range" behaviour (no coordination packet is sent; only the
unprivileged `A2DP.connect()` attempt fires).

**Inbound restriction (2-device hardening).** The server accepts an inbound socket
only when its remote MAC equals the single configured peer (`configuredPeerMac`,
set in `CrossDevice.init`); any other bonded ProPods device dialing in is rejected
and closed. Because the cross-device state is currently single-valued
(`isAvailable`, relayed battery/ANC), a stray third device connecting would clobber
it — restricting the server keeps the topology deterministic. (Full N-peer support
is designed in `docs/multi-peer-cross-device-plan.md` but not yet implemented.)

---

### Packet Reference

Defined in `CrossDevice.kt` as `CrossDevicePackets`:

| Packet | Hex | Meaning |
|---|---|---|
| `AIRPODS_CONNECTED` | `00 01 00 01` | "I now have the AirPods" |
| `AIRPODS_DISCONNECTED` | `00 01 00 00` | "I lost the AirPods" |
| `REQUEST_DISCONNECT` | `00 02 00 00` | "Release the AirPods — I am taking over" |
| `REQUEST_CONNECTION_STATUS` | `00 02 00 03` | Keep-alive ping (every 20 s) |
| `REQUEST_TAKEOVER` | `00 02 00 05` | Legacy: courtesy verdict request (removed — always replied with GRANT) |
| `COURTESY_GRANT` | `00 06 00 00` | Legacy: "no objection" reply (veto mechanism removed) |
| `COURTESY_DENY` | `00 06 00 01` | Legacy: "denied" reply (ignored — veto mechanism removed) |

Packets are broadcast to all connected sockets (`CrossDevice.sendRemotePacket`) **and**
forwarded on the client socket (`CrossDeviceClient.send`) so both roles receive them.

---

### Takeover Triggers & the No-Steal Policy

A device only takes the AirPods on **local intent** — never as a side effect of a
disconnection. The triggers that call `takeOver(...)`:

| Trigger | Source | Gated by |
|---|---|---|
| Media playback starts here | `MediaController` play / `onPlaybackConfigChanged` | `takeoverWhenMediaStart` |
| Cellular call ringing / offhook | `TelephonyCallback` | `takeoverWhenRingingCall` + a pod in-ear |
| **VoIP call answered/started here** | `AudioManager` `MODE_IN_COMMUNICATION` | `takeoverWhenRingingCall` + a pod in-ear |
| Manual "Reconnect to last device" | UI button (`reconnectFromSavedMac`) | — (explicit user action) |

**VoIP calls** (Teams, Zoom, Meet, WhatsApp…) are not Telecom-integrated, so they
never reach `CALL_STATE_*`. They surface only via `MODE_IN_COMMUNICATION`, which
fires on the device that **answered or started** the call — never on a device that
is merely ringing. This is deliberate: an incoming VoIP call rings on every
logged-in device at once, so triggering on *ring* would make all of them fight for
the pods. Answering is the unambiguous "this device needs them" signal.

**Disconnections never grab.** The passive, event-driven connect paths (BLE
availability tick, battery case-open, ear-in) are gated by `mayProactivelyConnect()`:
in a shared arrangement (cross-device enabled + a peer configured) they only fire
when this device **already holds the A2DP link** (i.e. it's local audio management,
not stealing). A coordination-socket drop is **not** treated as "the peer released
the pods" — `CrossDevice` no longer flips `isAvailable=false` when its RFCOMM client
socket drops (Doze/OEM-kill routinely drop that idle link). Ownership only changes
on an explicit `AIRPODS_DISCONNECTED` packet or our own intentful takeover.

### Requester Always Wins — No Veto

Every takeover trigger is an explicit user action (media playback started here,
call answered/started here, incoming call ringing here). Because the user has
already "voted with their feet" by initiating audio on the requesting device, the
holder's consent is not needed — blocking the handover would mean ignoring what
the user just did.

**The veto mechanism has been removed.** The `REQUEST_TAKEOVER` / `COURTESY_GRANT`
/ `COURTESY_DENY` round-trip no longer runs; `takeOver()` proceeds directly to
`REQUEST_DISCONNECT` + `connectAudio()`. The packet opcodes are retained in the
protocol for backwards compatibility with older builds: any `REQUEST_TAKEOVER`
received is still answered with `COURTESY_GRANT`, and `COURTESY_DENY` is silently
ignored.

The holder-state toggles (`takeoverWhenIdle`, `takeoverWhenMusic`,
`takeoverWhenCall`) have been removed from the UI and are no longer read at
runtime. `courtesyDeniesTakeover()` is kept as an API stub returning `false`.

**The requester's own triggers** (`takeoverWhenMediaStart`, `takeoverWhenRingingCall`)
remain as a user-facing option: they control whether *this device* will initiate a
handover at all when media starts or a call rings. Those are "do I want handover
on this device?" toggles, not holder veto toggles.

### Handover Scenarios

#### 1. AACP → AACP (both Xposed + rooted)

Both devices have AACP open and `BLUETOOTH_PRIVILEGED`.

**Peer in BT range**

1. Destination: user presses play → `takeOver("music")`.
2. Destination: sends `REQUEST_DISCONNECT` on RFCOMM.
3. Source receives it → `disconnectForCD()`:
   - closes L2CAP socket (drops AACP)
   - `disconnectAudio()` → `setConnectionPolicy(device, 0)` + `BluetoothA2dp.disconnect()` reflection
   - `sendPause()` if music was active and the user hadn't paused themselves
4. Destination: `connectAudio()`:
   - `setConnectionPolicy(device, 100)` + `BluetoothA2dp.connect()` reflection
   - Forces AirPods onto destination even if source hasn't fully dropped yet
5. Destination: `armPendingMusicTakeover(mac)` → when route lands, `onAudioDevicesAdded` fires:
   - `sendPlay(force=true)`, start `RouteLandPoller` (15 s watch)
   - `restartRouteLandPoller()` also fires on `A2DP PLAYING_STATE_CHANGED → STATE_PLAYING`
6. RouteLandPoller re-issues play on any `isMusicActive=false` check until the
   window expires or `iPausedTheMedia` is set.

**Peer out of BT range**

- `REQUEST_DISCONNECT` send fails silently (socket write throws; caught in `CrossDevice.kt`).
- Source A2DP link is already stale (ACL gone), so `connectAudio()` succeeds on
  the first 1 s retry with no contention.

---

#### 2. AACP-only → Limited (e.g. Pixel w/ Xposed → Xiaomi)

Source has AACP but no `BLUETOOTH_PRIVILEGED`. Destination is limited-mode.

**Peer in BT range**

1. Destination: `takeOver("music")`.
2. Destination: sends `REQUEST_DISCONNECT` via both `CrossDevice.sendRemotePacket` and
   `CrossDeviceClient.send` (added in commit `4944f43`).
3. Source receives it → `disconnectForCD()`:
   - closes L2CAP socket
   - attempts `disconnectAudio()` but **skips the force-disconnect** (`no BLUETOOTH_PRIVILEGED`)
   - `sendPause()` if music was active
4. Destination: `connectAudio()` — unprivileged `BluetoothA2dp.connect()` reflection.
   On OEM stacks (HyperOS, Pixel OEM build) this succeeds and the AirPods drop the
   source. Races the source releasing — hence the 1 s / 2.5 s / 4.5 s retry loop.
5. Playback recovery:
   - `armPendingMusicTakeover` + `onAudioDevicesAdded` → `sendPlay(force=true)` + RouteLandPoller.
   - On Xiaomi/HyperOS the A2DP stream goes live seconds after the device appears,
     so `restartRouteLandPoller()` fires again on `A2DP PLAYING_STATE_CHANGED → STATE_PLAYING`.
   - RouteLandPoller watches the full 15 s to catch Pocket Casts' delayed auto-pause.

**Peer out of BT range**

- REQUEST_DISCONNECT undelivered; source ACL already dead.
- Destination's unprivileged `connect()` wins immediately.

---

#### 3. Limited → AACP-only (e.g. Xiaomi → Pixel w/ Xposed)

Source is limited-mode. Destination has AACP but no `BLUETOOTH_PRIVILEGED`.

**Peer in BT range**

1. Destination: `takeOver("music")`.
2. Destination: sends `REQUEST_DISCONNECT`.
3. Source receives it → `disconnectForCD()` (now runs fully on limited-mode
   peers, see "Limited-mode `disconnectForCD()`" below):
   - no L2CAP to close
   - `MediaController.sendPause()` dispatched **synchronously** (before the
     A2DP proxy callback) — local podcast pauses within milliseconds
   - shows the "moved to remote" island if battery info is available
   - `setConnectionPolicy(device, 0)` (hint only, no privilege to force cut)
   - `notifyDisconnected()` sent over RFCOMM
4. Destination: `connectAudio()` — unprivileged `connect()`. On OEM stacks this
   displaces the source even without the privilege. Source sees `ACL_DISCONNECTED`
   passively when AirPods drop it.
5. Destination announces ownership via `AIRPODS_CONNECTED` once A2DP profile
   state reaches `STATE_CONNECTED` (the AACP path also sends it on AACP_ACK).
   The source's `confirmPeerOwnership()` arms the peer-drop cooldown
   proactively without waiting for ACL_DISCONNECTED.
6. Destination RouteLandPoller handles playback resume.

**Peer out of BT range**

- Source link already gone; `connectAudio()` succeeds on first attempt, no race.

---

#### 4. Limited → Limited (both non-rooted, no Xposed)

Neither device has `BLUETOOTH_PRIVILEGED`.

**Peer in BT range**

1. Destination: `takeOver("music")`.
2. Destination: sends `REQUEST_DISCONNECT` (if RFCOMM is up).
3. Source: `setConnectionPolicy(device, 0)` only — a stack hint, not a hard disconnect.
4. Destination: unprivileged `connect()` reflection. On strictly-enforced stacks this
   **fails** while the source holds the link. On OEM-relaxed stacks it may succeed.
   `takeOver()` logs `A2DP.connect (no BLUETOOTH_PRIVILEGED) returned true` in either
   case — `true` means "queued", not "connected".
5. **Outcome: unreliable.** On tested OEM devices (HyperOS, Pixel OEM build) it
   usually works. On strict AOSP it fails; user must manually disconnect the source
   in system Bluetooth settings.

**Peer out of BT range**

- Source ACL is gone; unprivileged `connect()` succeeds unopposed.
- **This is the only path guaranteed to work on all Android builds.**

---

### Summary Matrix

| Scenario | In-range outcome | Out-of-range outcome |
|---|---|---|
| AACP+privileged → AACP+privileged | Full forced handover; source actively force-disconnects | Immediate success |
| AACP-only → Limited | Unprivileged `connect()` wins on OEM stacks; retries help | Immediate success |
| Limited → AACP-only | Unprivileged `connect()` wins on OEM stacks; source can't fight back | Immediate success |
| Limited → Limited | Unreliable on strict AOSP; works on OEM-relaxed stacks | Always succeeds |

---

### Limited-mode `disconnectForCD()`

When a source receives `REQUEST_DISCONNECT` from the peer, `disconnectForCD()`
in `AirPodsService.kt` runs the following actions in order. It runs to completion
regardless of whether the AACP L2CAP socket is open (limited-mode peers do not
have one — the previous `socket.isInitialized` early-return made the whole
function a silent no-op on those devices, so podcasts kept playing into the
void for 3+ seconds until the AirPods physically left):

1. If an AACP L2CAP socket is open → close it.
2. Show "MOVED_TO_REMOTE" island (only if battery levels are known —
   limited-mode devices skip this when no battery packet has been parsed).
3. **`MediaController.sendPause()` dispatched synchronously.** This is now done
   outside the A2DP proxy callback so it fires immediately, not after the
   proxy attaches. Local audio pauses within milliseconds of the packet arriving.
4. Request the A2DP profile proxy. Inside the callback: if `BLUETOOTH_PRIVILEGED`
   is held, call `BluetoothA2dp.disconnect()` via reflection; otherwise log and
   skip (the AirPods will drop us when the destination's `connect()` wins).
5. Set `CrossDevice.isAvailable = true` and call `notifyDisconnected()`
   broadcasting `AIRPODS_DISCONNECTED` to all peers.

The `markPeerTakeoverAttempt()` call (made by the packet handler *before*
`disconnectForCD()`) sets `expectingPeerTakeover = true` and starts the 15 s
ceiling timer.

---

### Destination ownership announcement

A destination that just took the AirPods announces ownership over RFCOMM via
`CrossDevice.notifyConnected()`, which sends `AIRPODS_CONNECTED` to all peers.
Two trigger points:

1. **AACP destinations** — fire after a successful AACP handshake (`AACP_ACK`
   received). This is the historical path.
2. **Limited-mode destinations** — fire when the A2DP profile state for the
   AirPods MAC transitions to `STATE_CONNECTED`, gated on
   `CrossDevice.isAvailable == true` (to debounce when the AACP path already
   announced). Without this, a limited-mode taker would never send
   `AIRPODS_CONNECTED` to the peer and the peer's `confirmPeerOwnership()`
   proactive cooldown path would be inert in that direction.

---

### MediaController stale-state resets

Two state variables in `MediaController.kt` would otherwise persist incorrectly
across a peer takeover and break the "user comes back later and presses play"
flow.

**`iPausedTheMedia`.** Set to `true` by `sendPause()` regardless of who paused.
When the source yielded the AirPods for a call, this flag was set — and stayed
set forever after, because nothing in the normal play flow cleared it. When the
user later took the AirPods back, `onAudioDevicesAdded` hit the
"route landed but iPausedTheMedia=true; user paused intentionally, NOT replaying"
branch and suppressed the auto-replay. Now `armPendingMusicTakeover(mac)`
clears this flag — by definition arming a music takeover means the user has
requested playback, so any prior "we paused" state is stale.

**`lastKnownIsMusicActive`.** An edge-trigger used to decide whether to fire
`takeOver("music")` from `onPlaybackConfigChanged`. When the AirPods autonomously
left us (Apple auto-switch, OS reconnect) no playback callback fired with
`isActive=false`, so the flag stayed `true`. The next play press hit the
HyperOS quirk path (`Media config seen but isMusicActive=false`) but the
delayed 500 ms re-check was gated by `lastKnownIsMusicActive != true` — the
gate suppressed it and no takeover fired. Two fixes:
1. New `MediaController.resetMusicActiveState()` is called from the
   ACL_DISCONNECTED-for-AirPods handler in `AirPodsService.kt`, clearing the
   stale flag whenever the AirPods leave us for any reason.
2. The `lastKnownIsMusicActive != true` clause was removed from the delayed
   re-check's outer gate. The inner `audioManager.isMusicActive` check at
   +500 ms is the authoritative source of truth.

---

### RouteLandPoller Lifecycle

```
takeOver("music")
  └─ armPendingMusicTakeover(mac)          ← MediaController.kt
       └─ connectAudio()
            └─ [A2DP route lands]
                 └─ onAudioDevicesAdded()  ← AudioDeviceCallback
                      ├─ sendPlay(force=true)
                      └─ routeLandPoller starts (postDelayed 200 ms)
                           │  poll isMusicActive every 200→400→600…ms
                           ├─ active  → continue watching
                           ├─ inactive → sendPlay(force=true), continue
                           └─ iPausedTheMedia=true | 15 s elapsed → exit

[also] A2DP PLAYING_STATE_CHANGED → STATE_PLAYING
  └─ restartRouteLandPoller()              ← AirPodsService.kt
       └─ resets + re-arms the same poller (covers Xiaomi late-stream-start)
```

The poller has **no early-exit on stable playback**. On Xiaomi/HyperOS, Pocket Casts
can auto-pause 3–4 s after the stream starts — well past any short "looks stable" window.

---

### Keep-Alive & Post-Handover Cooldown

- **Keep-alive**: server (`CrossDevice.kt`) and client (`CrossDeviceClient.kt`) each
  send `REQUEST_CONNECTION_STATUS` every 20 s to prevent Android's BT power manager
  from dropping the ACL between handovers.
- **Peer-drop cooldown**: after yielding the AirPods, `peerDropCooldownUntilMs` is
  set to `now + 10 s`. The BLE reconnect path is blocked during this window so the
  new owner can finish establishing AACP without the source fighting back.

  **Event-based arming.** The cooldown is no longer keyed off a time window
  (`now < peerRequestedDisconnectMs + 6_000L`). Instead, an `expectingPeerTakeover`
  flag is set on receiving `REQUEST_DISCONNECT`/`REQUEST_HANDOVER` and cleared by:
  1. ACL_DISCONNECTED for the AirPods (we observed the AirPods leave us),
  2. inbound `AIRPODS_CONNECTED` packet from the peer (peer confirmed takeover —
     handled by `confirmPeerOwnership()`, arms the cooldown proactively without
     waiting for ACL_DISCONNECTED),
  3. a 15 s ceiling safety net (handover silently failed),
  4. user-initiated manual reconnect.

  This eliminates two prior failure modes: slow takeovers (cold-connect or
  unprivileged-`connect()` retries landing past the old 6 s window) and false
  positives (case close / range loss within the window misclassified as peer
  takeover).

**RFCOMM down ≠ AirPods disconnected.** The RFCOMM channel and the AirPods A2DP
link are independent. It is common for the phones to lose their RFCOMM connection
(range, service restart, BT power management) while the AirPods continue playing on
the source. When this happens and the destination calls `takeOver()`, the
`REQUEST_DISCONNECT` packet is silently dropped and the handover falls through to the
same unprivileged `connect()` path as the out-of-range case. On OEM stacks this
still succeeds — the difference is that the source never receives `sendPause()`, so
its audio cuts abruptly rather than being paused first.

---

### OEM Compatibility — Unprivileged `connect()`

Android 12 tightened `BluetoothA2dp.connect()` to require `BLUETOOTH_PRIVILEGED` at
the service level. Most OEMs who shipped before that point had apps (including their
own) relying on unprivileged connect for paired devices, so they quietly preserved the
old behaviour to avoid breakage. The `disconnect()` side was not relaxed because
forcibly removing another device from an active link is a different threat model.

| Brand / Stack | Unprivileged `connect()` | Notes |
|---|---|---|
| **Google Pixel** (Pixel OEM) | ✅ Works | Confirmed in logcat during this project |
| **Xiaomi / HyperOS** | ✅ Works | Confirmed in logcat during this project |
| **Samsung / One UI** | ✅ Likely works | Large third-party app ecosystem depends on it; OEM preserves compat |
| **Oppo, Vivo, Realme / ColorOS, FuntouchOS** | ⚠️ Uncertain | Same BBK group as OnePlus; moderate AOSP deviation |
| **OnePlus / OxygenOS** | ⚠️ Uncertain | Close to AOSP, minimal BT changes; may enforce strictly |
| **Motorola** | ❌ Likely enforces | Near-stock AOSP, minimal BT modifications |
| **Nokia / Android One** | ❌ Enforces | Pure AOSP |
| **Sony Xperia** | ❌ Likely enforces | Near-stock with some audio extensions |
| **Huawei / HarmonyOS** | ❓ Unknown | Entirely custom stack, behaviour unpredictable |

**Market impact:** Samsung and Xiaomi together cover the majority of non-stock Android
users and are both likely permissive. Motorola and Nokia are popular in the
budget/near-stock space and are likely strict, but represent a smaller share of the
target audience for this app.

**Mitigation options for strict stacks:**

1. **Xposed hook on the permission check** — `KotlinModule` already runs inside
   `BluetoothManagerService` on Xposed devices. Hooking
   `enforceBluetoothPrivilegedPermission()` in `BluetoothA2dpService` would bypass the
   check on any device running LSPosed, covering the same population that gets AACP.

2. **Source-drops-first protocol** — if the source reliably drops its A2DP link via
   `setConnectionPolicy(FORBIDDEN)` before the destination attempts `connect()`, there
   is nothing to fight over and the call succeeds on all stacks (equivalent to the
   out-of-range path). The weakness today is that the source drop is not guaranteed
   without `BLUETOOTH_PRIVILEGED`.

3. **User fallback** — on strict-AOSP devices, tapping Connect in system Bluetooth
   settings makes the identical API call with `BLUETOOTH_PRIVILEGED` and always works.

---

### Known Limitations

1. **Unprivileged `connect()` on strict AOSP** — blocked by `BluetoothA2dpService` on
   Motorola, Nokia, and likely OnePlus. Works on OEM-relaxed stacks (tested: HyperOS,
   Pixel OEM). See OEM Compatibility table above.

2. **AACP-only source cannot force-disconnect** — without `BLUETOOTH_PRIVILEGED` the
   source can only hint the stack via `setConnectionPolicy`. The destination's
   `connect()` displacing it is the actual release mechanism.

3. **Identical device names** — role election assigns both devices as SERVER-ONLY.
   RFCOMM channel never comes up; all in-range handovers degrade to unprivileged
   `connect()` only with no coordination packet.

4. **Unprivileged `connect()` race (any → Limited)** — the retry loop (1 s, 2.5 s,
   4.5 s) covers most timing windows but is not guaranteed if the source is very slow
   to release.

5. **No return-to-source after a call.** When a call on the source device pulls
   the AirPods from a peer and the call ends, there is no mechanism to hand the
   AirPods back. The peer's podcast stays paused; the user must press play on
   the peer to trigger a fresh `takeOver`. The post-call `CALL_STATE_IDLE`
   handler stops gesture detection but doesn't initiate a reverse handover.

6. **AirPods auto-switching back to source.** Apple AirPods have iCloud-driven
   "fast device switching" that autonomously moves the connection to a recently
   active paired device based on accelerometer / screen wake / audio session
   signals. Observed in testing: ~13 s after a Pixel→Xiaomi handover, the
   AirPods autonomously left Xiaomi and reconnected to Pixel without any
   takeover request from Pixel. This is OS+AirPods behaviour, not something
   the app can suppress directly. The event-based `expectingPeerTakeover` flag
   correctly identifies these as non-peer-takeover drops (no false-positive
   cooldown), but the audio still leaves the destination.

7. **Two-device only.** The protocol coordinates exactly one pair: peer identity is
   a single MAC, the client is a single socket, and `isAvailable` / relayed
   battery / ANC are single-valued. The server is restricted to the one configured
   peer to keep this safe. 3+ devices are not supported — see
   `docs/multi-peer-cross-device-plan.md` for the deferred N-peer design.

8. ~~**Courtesy veto needs peer support.**~~ Veto mechanism removed. Requester always
   wins; `REQUEST_TAKEOVER` / `COURTESY_DENY` round-trip no longer runs. See
   "Requester Always Wins" section above.
