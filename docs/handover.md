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
| **Full** | ✓ Xposed + `l2c_fcr_hook` | ✓ Rooted or system app | Rooted Pixel |
| **AACP-only** | ✓ Xposed + `l2c_fcr_hook` | ✗ Not rooted | Pixel w/ LSPosed, no root |
| **Limited** | ✗ | ✗ | Non-rooted Xiaomi |

**AACP** (the L2CAP socket to the AirPods) requires Xposed and an unlocked
bootloader, but not root. It gives ownership callbacks and the ability to send
`OWNS_CONNECTION` control commands to the AirPods.

**`BLUETOOTH_PRIVILEGED`** requires root or system-app installation. It unlocks
`BluetoothA2dp.disconnect()` and the privileged form of `BluetoothA2dp.connect()`.
Without it, calling `connect()` still works on most OEM stacks (see below).

**In practice, the Pixel in this project is "AACP-only"** — confirmed by this
log line from `disconnectForCD()` during a real handover:
```
W/AirPodsService: disconnectForCD: no BLUETOOTH_PRIVILEGED, skipping A2DP disconnect
```
The Pixel cannot actively force its A2DP link off. It relies on the destination's
`connect()` displacing it, which the AirPods handle automatically.

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

---

### Packet Reference

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
3. Source receives it → `disconnectForCD()`:
   - no L2CAP to close
   - `setConnectionPolicy(device, 0)` only (hint, not a hard cut)
4. Destination: `connectAudio()` — unprivileged `connect()`. On OEM stacks this
   displaces the source even without the privilege. Source sees `ACL_DISCONNECTED`
   passively when AirPods drop it.
5. Source: `sendPause()` runs if music was active; destination RouteLandPoller
   handles playback resume.

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
- **Peer-drop cooldown**: after yielding the AirPods, `peerDropCooldownUntilMs` is set
  to `now + 10 s`. The BLE reconnect path is blocked during this window so the new
  owner can finish establishing AACP without the source fighting back.

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

5. **No explicit handover acknowledgement** — the destination never confirms to the
   source "I have the AirPods now." The source infers it from `ACL_DISCONNECTED` +
   `AUDIO_BECOMING_NOISY` rather than a protocol-level confirmation.
