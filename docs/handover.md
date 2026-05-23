# Cross-Device AirPods Handover

This document describes how ProPods coordinates AirPods ownership transfers between
two paired Android devices. The four device-combination scenarios and the in-range /
out-of-range sub-cases are all covered.

---

## Glossary

| Term | Meaning |
|---|---|
| **AACP device** | A rooted device (or system app) that has an open L2CAP socket to the AirPods via `AACPManager`. Has `BLUETOOTH_PRIVILEGED` and can force `BluetoothA2dp.connect()` / `disconnect()` through hidden-API reflection. Receives ownership callbacks (`onOwnershipChangeReceived`, `onOwnershipToFalseRequest`). |
| **Limited-mode device** | Non-rooted. No L2CAP / AACP socket. No `BLUETOOTH_PRIVILEGED`. Can call the un-privileged reflection path for `A2DP.connect()`, which the stack may honour or silently ignore depending on whether another device holds the link. |
| **RFCOMM channel** | A side-channel Bluetooth serial socket (UUID `1abbb9a4-10e4-4000-a75c-8953c5471342`) used by the two Android peers to coordinate handovers independently of the AirPods connection itself. Managed by `CrossDevice.kt` (server) and `CrossDeviceClient.kt` (client). |
| **RouteLandPoller** | A 15 s arithmetic-backoff watchdog in `MediaController.kt` that re-issues `sendPlay(force=true)` any time `audioManager.isMusicActive` drops after a takeover — handles app auto-pause on route change (e.g. Pocket Casts on HyperOS). |

---

## Role Election

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

---

## Packet Reference

Defined in `CrossDevice.kt` as `CrossDevicePackets`:

| Packet | Hex | Meaning |
|---|---|---|
| `AIRPODS_CONNECTED` | `00 01 00 01` | "I now have the AirPods" |
| `AIRPODS_DISCONNECTED` | `00 01 00 00` | "I lost the AirPods" |
| `REQUEST_DISCONNECT` | `00 02 00 00` | "Release the AirPods — I am taking over" |
| `REQUEST_CONNECTION_STATUS` | `00 02 00 03` | Keep-alive ping (every 20 s) |

Packets are broadcast to all connected sockets (`CrossDevice.sendRemotePacket`) **and**
forwarded on the client socket (`CrossDeviceClient.send`) so both roles receive them.

---

## Handover Scenarios

### 1. AACP → AACP (both rooted)

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

- `REQUEST_DISCONNECT` send fails silently (socket write throws; caught in
  `CrossDevice.kt:~382`).
- Source A2DP link is already stale (ACL gone), so `connectAudio()` succeeds on
  the first 1 s retry with no contention.

---

### 2. AACP → Limited (e.g. Pixel → Xiaomi)

Source is rooted (AACP). Destination is non-rooted, limited-mode.

**Peer in BT range**

1. Destination: `takeOver("music")`.
2. Destination: sends `REQUEST_DISCONNECT` via both `CrossDevice.sendRemotePacket` and
   `CrossDeviceClient.send` (added in commit `4944f43` — necessary because limited devices
   previously never sent this, leaving the source holding the link).
3. Source receives it → `disconnectForCD()` + `sendPause()` as in scenario 1.
4. Destination: `connectAudio()` — un-privileged `BluetoothA2dp.connect()` reflection
   (no `BLUETOOTH_PRIVILEGED`). **This often races against the source still holding
   the link**, hence the 1 s / 2.5 s / 4.5 s retry loop in `takeOver()`.
5. Playback recovery:
   - `armPendingMusicTakeover` + `onAudioDevicesAdded` → `sendPlay(force=true)` + RouteLandPoller.
   - On Xiaomi/HyperOS the A2DP audio stream goes live several seconds *after* the
     device appears in `AudioDeviceCallback`, so `AirPodsService` also calls
     `restartRouteLandPoller()` on `A2DP PLAYING_STATE_CHANGED → STATE_PLAYING (10)`.
   - RouteLandPoller watches the full 15 s (no early-exit on stable playback) to
     catch Pocket Casts' delayed auto-pause.

**Peer out of BT range**

- REQUEST_DISCONNECT undelivered.
- Destination's un-privileged `connect()` wins because the source ACL is already dead.
- Playback recovery proceeds normally.

---

### 3. Limited → AACP (e.g. Xiaomi → Pixel)

Source is limited-mode. Destination is rooted (AACP).

**Peer in BT range**

1. Destination: `takeOver("music")`.
2. Destination: sends `REQUEST_DISCONNECT`.
3. Source receives it → `disconnectForCD()`:
   - No L2CAP to close (limited-mode has none).
   - `disconnectAudio()` → `setConnectionPolicy(device, 0)` only — no `BLUETOOTH_PRIVILEGED`,
     so no forced disconnect; the hint may not be acted on immediately.
4. Destination: `connectAudio()` with `BLUETOOTH_PRIVILEGED` → forced `connect()`.
   Even if the source is still holding the link the forced `connect()` wins — AirPods
   drop the source mid-stream.
5. Source: `sendPause()` runs if music was active; destination RouteLandPoller handles
   playback resume as in scenario 1.

**Peer out of BT range**

- Source link already gone; destination `connectAudio()` succeeds on first attempt,
  no race.

---

### 4. Limited → Limited (both non-rooted)

Neither device has `BLUETOOTH_PRIVILEGED`.

**Peer in BT range**

1. Destination: `takeOver("music")`.
2. Destination: sends `REQUEST_DISCONNECT` (if RFCOMM is up).
3. Source: `setConnectionPolicy(device, 0)` only — stack hint, not a hard disconnect.
4. Destination: un-privileged `connect()` reflection. **This fails** while the source
   still holds the A2DP link. `takeOver()` logs
   `A2DP.connect (no BLUETOOTH_PRIVILEGED) returned true` — the call appears to
   succeed but the AirPods do not switch.
5. **Outcome: handover fails.** User must manually disconnect the source in system
   Bluetooth settings.

**Peer out of BT range**

- Source ACL is gone; un-privileged `connect()` succeeds unopposed.
- Playback recovery via RouteLandPoller as normal.
- **This is the only Limited → Limited path that works reliably.**

---

## Summary Matrix

| Scenario | In-range outcome | Out-of-range outcome |
|---|---|---|
| AACP → AACP | Full forced handover; source actively releases | Immediate success; no race |
| AACP → Limited | Races source; 1 s / 2.5 s / 4.5 s retries usually win | Immediate success |
| Limited → AACP | Forced `connect()` overrides source regardless | Immediate success |
| Limited → Limited | **Fails** — unprivileged `connect()` can't override source | Succeeds (source gone) |

---

## RouteLandPoller Lifecycle

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

The poller intentionally has **no early-exit on stable playback**. On Xiaomi/HyperOS,
Pocket Casts can auto-pause 3–4 s after the stream starts — well past any short
"looks stable" window.

---

## Keep-Alive & Post-Handover Cooldown

- **Keep-alive**: both server (`CrossDevice.kt`) and client (`CrossDeviceClient.kt`)
  send `REQUEST_CONNECTION_STATUS` every 20 s to prevent Android's BT power manager
  from dropping the ACL between handovers.
- **Peer-drop cooldown**: after yielding the AirPods, `peerDropCooldownUntilMs`
  is set to `now + 10 s`. During this window the BLE reconnect path is blocked,
  preventing the yielding device from fighting back while the new owner is still
  establishing AACP.

---

## Known Limitations

1. **Limited → Limited in-range** — handover is impossible without `BLUETOOTH_PRIVILEGED`.
   Both sides must be rooted for reliable forced takeover.

2. **Identical device names** — role election assigns both devices as SERVER-ONLY.
   The RFCOMM channel never comes up. All in-range handovers degrade silently:
   no `REQUEST_DISCONNECT` is delivered, and the unprivileged `connect()` call
   decides the outcome alone.

3. **Unprivileged `connect()` race (AACP → Limited)** — the retry loop (1 s, 2.5 s,
   4.5 s) covers most timing windows but is not guaranteed. If the source is slow
   to release (e.g. HFP teardown races), a 4th retry would help.

4. **No explicit handover acknowledgement** — the destination never tells the source
   "I have the AirPods now." The source infers it from `ACL_DISCONNECTED` +
   `AUDIO_BECOMING_NOISY` (standard Android) rather than a protocol-level confirmation.
