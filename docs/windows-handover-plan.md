# Windows Handover — Comprehensive Design

Status: design draft. Implementation in progress on branch `worktree-update-windows-version`.

This document is the Windows-side counterpart to [`handover.md`](handover.md). `handover.md` describes the cross-device protocol and the Android implementation (which currently works as intended). This document enumerates the scenarios, gaps, and phased work required so the Windows client meets the same user-experience principle:

> **AirPods follow attention.** The device where the user initiates an activity (presses play, makes a call, answers a call) should get the AirPods. New user-initiated activity on another device always wins over existing background activity. Calls — incoming or outgoing — represent peak attention and should always win.

---

## 1. Scope & Premise

**Fixed:** Android side. Per `handover.md`, Android currently sends `REQUEST_DISCONNECT` (`00 02 00 00`) before A2DP grabs, sends `AIRPODS_CONNECTED`/`AIRPODS_DISCONNECTED` on ownership changes, and reacts symmetrically to both `REQUEST_DISCONNECT` and `REQUEST_HANDOVER` by calling `disconnectForCD()`. Android does **not** currently send `REQUEST_HANDOVER` — that's a future enhancement we can plan around but not depend on.

**Mutable:** Windows side, this worktree only.

**Principle to preserve:** AirPods follow attention. Calls win. New user-initiated activity wins over background activity. Music respects ongoing peer calls.

## 2. Topology

```
   ┌──────────────┐                ┌──────────────┐
   │  Phone A     │ ◄── RFCOMM ──► │  Windows PC  │
   │  (Pixel,     │                │              │
   │   AACP-only) │                │              │
   └──────────────┘                └──────────────┘
          ▲                               ▲
          │  RFCOMM                       │  RFCOMM
          ▼                               │
   ┌──────────────┐                       │
   │  Phone B     │ ──── RFCOMM ──────────┘
   │  (Xiaomi,    │
   │   limited)   │
   └──────────────┘
                ▲
                │
                ▼
           ┌────────┐
           │ AirPods│  (exactly one A2DP host at a time)
           └────────┘
```

Three independent links per device pair: RFCOMM (coordination), A2DP (audio), AACP (only on Pixel ↔ AirPods).

## 3. Scenario Matrix

Variables:
- **Initiator** = which device's user pressed play / answered a call → {Win, PhoneA, PhoneB}
- **Activity** = {music, call/meeting}
- **Current owner** = {Win, PhoneA, PhoneB, none}
- **Channel state** = {RFCOMM up to that peer, RFCOMM down}

### 3.1 Windows initiates

| # | Trigger | AirPods are on | RFCOMM up? | Expected Windows behaviour | Status |
|---|---|---|---|---|---|
| **W1** | GSMTC media-start | Phone | yes | Send `kRequestDisconnect` → wait → connect with backoff → claim | ✓ WIP |
| **W2** | TeamsCallWatcher fires | Phone | yes | Same as W1; today we send `kRequestDisconnect`, not `kRequestHandover` | ⚠ semantic gap |
| **W3** | Media-start | Phone | down | Best-effort: skip request, attempt connect (out-of-range path) | ✓ WIP (logged WARN) |
| **W4** | Media-start | Win already | n/a | SKIP (no-op) | ✓ WIP |
| **W5** | Media-start | nobody (Win recently released **via protocol or BT-grab**) | yes | Anti-pingpong (3 s) skip; after window, take | ✓ |
| **W6** | TeamsCallWatcher fires immediately after release | nobody | yes | Resolved: proactive release removed, so the only `m_lastLostOwnership` writes come from real external takeovers — call reclaim is always desired | ✓ |

### 3.2 Phone initiates (Windows is responder)

| # | Trigger | AirPods on Win | Win audio | RFCOMM up? | Expected | Status |
|---|---|---|---|---|---|---|
| **P1** | Incoming `kRequestDisconnect` | yes | idle | yes | ACCEPT: pause, disconnect, setState Remote, send kDisconnected | ✓ WIP |
| **P2** | Incoming `kRequestDisconnect` | yes | active (Teams call) | yes | REJECT, re-assert `kAirPodsConnected` | ✓ WIP |
| **P3** | Incoming `kRequestHandover` | yes | active or idle | yes | ACCEPT unconditionally (call wins) | ✓ WIP — but Android doesn't send this yet |
| **P4** | Incoming `kRequestDisconnect` within 3 s of Win takeover | yes | any | yes | REJECT (anti-pingpong) | ✓ WIP |
| **P5** | Phone grabs AirPods via A2DP without sending packet | yes | idle | yes (peer connected) | Phase 2 BT falling edge: pause local media, set Remote, broadcast `kAirPodsDisconnected` | ✓ |
| **P6** | Phone grabs A2DP while Win in active call | yes | active | yes | RECLAIM (current behaviour — call wins) | ✓ WIP |
| **P7** | Phone grabs via A2DP, RFCOMM down | yes | idle | down | Phase 2 BT falling edge handles locally; no peer broadcast possible (none connected) | ✓ |
| **P8** | `kAirPodsConnected` arrives, Win has them | yes | idle | yes | Pause local media (triple-tap), setState Remote | ✓ |
| **P9** | `kAirPodsDisconnected` arrives | no | n/a | yes | Currently logs only; correct ("wait for local trigger") | ✓ WIP |

### 3.3 Auto-switch / external events

| # | Event | Win state | Expected | Status |
|---|---|---|---|---|
| **X1** | AirPods Apple-auto-switch back to Win after Phone had them | Remote, RFCOMM up | Set LocalPc, send `kAirPodsConnected`, claim default endpoint | ⚠ no path today — only `onPeerConnectionChanged` and audio-watcher react |
| **X2** | AirPods auto-switch **away** from Win | LocalPc, audio idle | Set Remote, send `kAirPodsDisconnected` | ⚠ no path — watcher only reclaims if audio active |
| **X3** | User puts AirPods in case, takes them out again on Win | varies | Detect via `isClassicallyConnected` rising edge | ⚠ |
| **X4** | RFCOMM to one peer drops mid-handover | LocalPc | Other peers still receive packets; don't change ownership | ✓ WIP (each peer independent) |
| **X5** | RFCOMM reconnects after Phone grabbed AirPods while we were "out of touch" | LocalPc cached but `!isClassicallyConnected` | RECLAIM via `onPeerConnectionChanged` | ✓ WIP |
| **X6** | Both phones reconnect RFCOMM simultaneously, both claim ownership | varies | Last-writer-wins on `AIRPODS_CONNECTED` | ⚠ minor — race |

### 3.4 Multi-peer races

| # | Setup | Event | Expected | Status |
|---|---|---|---|---|
| **M1** | PhoneA and PhoneB both connected | Win sends `kRequestDisconnect` | Both peers receive; whichever has AirPods releases | ✓ WIP |
| **M2** | Both peers send `kRequestDisconnect` simultaneously | Win has AirPods | First wins; second sees `state != LocalPc` post-transition and is a no-op-or-reject | ⚠ unverified |
| **M3** | PhoneA sends `kAirPodsConnected`, then PhoneB sends `kAirPodsConnected` 200 ms later | Remote | Both legal; state stays Remote. No regression. | ✓ |
| **M4** | Win wants to know **which** phone holds AirPods (for UI label, smarter routing) | Remote | Not currently tracked per-peer | ⚠ feature gap |

### 3.5 Cold-start scenarios

| # | At boot | Expected | Status |
|---|---|---|---|
| **C1** | AirPods on Phone, Win starts | state Unknown → on first peer connect: `isClassicallyConnected=false` → Remote, broadcast `kAirPodsDisconnected` | ✓ WIP |
| **C2** | AirPods on Win, Win starts | First peer connect: LocalPc + broadcast `kAirPodsConnected` | ✓ WIP |
| **C3** | RFCOMM to peer up before AirPods connect | Initial broadcast says "disconnected" but AirPods then auto-connect to Win | ⚠ no follow-up event — needs X1 fix |
| **C4** | No phones paired yet | Tray menu "Add Android peer" discovery works | ✓ WIP |

## 4. Gap Analysis (mapped to WIP code)

### G1. Proactive release was the wrong design — REMOVED
Earlier WIP released AirPods from Windows automatically 15 s after audio went idle, on the theory that "phone should be able to take over without fighting." This was wrong: it disconnected the AirPods from Windows during ordinary pauses (reading, taking notes, between songs), creating reconnect lag the next time the user wanted them.

**New principle:** Windows holds ownership until another device actively claims them. Ownership leaves Windows only on:
1. Incoming `kRequestDisconnect` or `kRequestHandover` over RFCOMM (phone-initiated, protocol).
2. BT falling edge while `state == LocalPc` (phone-initiated BT grab, or Apple auto-switch away).

Pausing media is not a release signal. The audio IDLE broadcast (`kWindowsAudioIdle`) stays — it tells Android "safe to take" so its `takeoverWhenCall` gating works, but it is purely informational from Windows' side.

### G2. No BT-state rising/falling edge handler
The watcher ([`HandoverController.cpp:48-114`](../windows/src/HandoverController.cpp)) only acts on `isClassicallyConnected AND hasActiveAudioSessions`. The BT classical-connection edge itself is observed only inside the conjunction. We need an independent state observer that fires on every transition of `isClassicallyConnected`, so:
- **Rising edge while state ≠ LocalPc** → Win re-acquired AirPods (X1, X3) → setState LocalPc, broadcast `kAirPodsConnected`.
- **Falling edge while state == LocalPc, audio idle** → Win quietly lost them (X2, P5) → setState Remote, broadcast `kAirPodsDisconnected`. **Do not reclaim.**
- **Falling edge while state == LocalPc, audio active** → existing RECLAIM (P6). Keep.

### G3. Anti-pingpong is single-window, doesn't distinguish cause
`m_lastLostOwnership` (3 s) blocks `onMediaPlayingChanged` regardless of trigger type. A real incoming call ≠ Spotify auto-resume. Split into:
- **Music-trigger anti-pingpong**: 3 s — keep as is.
- **Call-trigger** (TeamsCallWatcher path): 500 ms — calls should win quickly.

This requires plumbing the trigger reason from `TeamsCallWatcher` into `HandoverController` (today it just calls `onMediaPlayingChanged(true)`).

### G4. `onMediaPlayingChanged` doesn't tag "music vs call"
Both paths funnel through the same entry point and send the same packet (`kRequestDisconnect`). Now that the protocol has `kRequestHandover`, we should send it for the call path **even though Android currently ignores the priority distinction**. Future-proof; Android may later add differential handling per the addendum plan.

### G5. `kAirPodsConnected` incoming doesn't pause local media — RESOLVED IN PHASE 1
Triple-tap pause is now applied at every release path except the active-fall reclaim (BT falling-idle, incoming `kAirPodsConnected` when previously LocalPc). The existing `RequestDisconnect` / `RequestHandover` handlers already had it.

### G6. State machine has no `Unknown` resolution path mid-session
After cold-start `Unknown`, the first peer-connect resolves it (`onPeerConnectionChanged`). But there's no way to recover from a wedged `Unknown` if peer-connect didn't fire (e.g., Windows starts with no phones nearby). Add a periodic re-sync in the audio watcher: if `m_state == Unknown` and `isClassicallyConnected` is reliably known, set the appropriate state.

### G7. Per-peer ownership tracking missing (M4)
`PeerRegistry` knows connection state per peer, but `HandoverController` only tracks a single `OwnershipState`. We don't know **which** phone holds the AirPods when state is `RemoteAndroid`. Add a `m_holderAddress` field updated on `kAirPodsConnected` (use the source-peer address — would require packet callback to carry sender identity, a small `PeerRegistry` extension).

### G8. Proactive release one-shot loses second cycle — RESOLVED BY REMOVAL
Moot. With G1 removed, there is no `idleSince` timer and nothing to reset. `m_resetIdle` was deleted along with the rest of the release plumbing.

### G9. Reject-with-`kAirPodsConnected` re-assertion may mislead
On P2/P4 (REJECT), we send `kAirPodsConnected`. Per Android's `CrossDevice.kt` handler, that sets `isAvailable=true` only, **doesn't stop A2DP fight**. So Android's A2DP retry loop (1s/2.5s/4.5s) may still win and yank AirPods mid-Teams-call. Mitigation:
- Document as known limitation; or
- Add a stronger re-assertion: actively reconnect AirPods on each P2 within a short window (a `m_airpods.connect()` race-back). Heavy; might cause oscillation. Probably defer.

### G10. Logger timestamps are UTC (cosmetic but real)
Per WIP commit message + handover plan side-issue. One-liner.

### G11. `setAsDefaultAudioDevice` polling delays the second-try refresh
[`AirPodsConnector.cpp:370-376`](../windows/src/AirPodsConnector.cpp) polls up to 4 s for endpoint enumeration. On Pixel handovers this is fine; on cold takeovers it adds visible latency before audio routes. Defer optimization.

## 5. Phased Plan

### Phase 1 — Stabilize WIP (low risk, high value)
*Make the current implementation reliably meet the "win" scenarios in handover.md, and remove the proactive-release misfeature.*

1. **G1 — Remove proactive release entirely.** In `HandoverController.cpp::startAudioWatcher()`:
   - Delete `idleSince`, `kReleaseAfterIdle`, the `m_resetIdle.exchange` check, and the `!active && !lastActive` branch that fired `RELEASE` + `kAirPodsDisconnected`.
   - The watcher now only broadcasts audio ACTIVE/IDLE signals; it never gives up ownership.
   - In `setState()`, remove the `m_resetIdle.store(true)` hook.
   - In `HandoverController.hpp`, delete the `m_resetIdle` field.
2. **G5 — Pause local media on every loss path** (added with G1 removal). Adds the existing `tryPauseActive` + `tryPauseAllSessions` + `tryPauseViaMediaKey` triple-tap at:
   - BT-edge falling-idle (Phase 2 handler) — before broadcasting `kAirPodsDisconnected`.
   - `Incoming::AirPodsConnected` handler — only when `m_state` was `LocalPc` at packet receipt, to avoid pausing on peer re-assertions.
   The active-fall reclaim path is intentionally exempt (call must continue while we reconnect).
3. **G10 — Local-time logger.** `Logger.hpp::timestamp()` → uses `std::chrono::current_zone()` with UTC fallback.
4. **G6 — Periodic state resolver.** Inside watcher loop, if `m_state == Unknown` and `m_peers.isAnyConnected()`, infer from `isClassicallyConnected` and broadcast.

**Verification:** Play music → pause → wait 60 s: confirm log shows `AUDIO IDLE` only, no `RELEASE`, no `kAirPodsDisconnected`. From that paused state, start playback on phone: Windows accepts `kRequestDisconnect` without any anti-pingpong stall (`m_lastLostOwnership` was never stamped). Spotify pauses on Win as part of the loss; no leak to PC speakers.

### Phase 2 — BT-state event bus (medium risk)
*Close the silent-grab and auto-switch gaps (P5, P7, X1, X2, X3).*

1. **G2 — Independent BT-edge observer.**
   In the audio-watcher loop, track `lastClassicallyConnected` separately from `lastActive`. On each tick:
   - Rising edge (`!last && now`) → if `m_state != LocalPc`: `setState(LocalPc)`, broadcast `kAirPodsConnected`. *Does not call `setAsDefaultAudioDevice` unless user-initiated — avoids stealing audio routing during Apple auto-switch.*
   - Falling edge (`last && !now`):
     - If `m_state == LocalPc` AND `lastActive` (audio was active) → existing RECLAIM (kept).
     - If `m_state == LocalPc` AND `!lastActive` (idle) → `setState(RemoteAndroid)`, broadcast `kAirPodsDisconnected`. **No reclaim.**
     - If `m_state != LocalPc` → no-op.

   This subsumes the current BT-loss-during-call reclaim block (lines 75–85). Keep it as one of the falling-edge branches.

2. **Optional: switch to WinRT `BluetoothDevice.ConnectionStatusChanged` event** instead of polling. Cleaner, but watcher already runs — defer.

**Verification:** New scenarios — start Spotify on Xiaomi while Win is idle with AirPods (P5/P7); confirm Win quickly transitions to Remote without fighting. Auto-switch the AirPods back to Win (Apple Bluetooth menu); confirm Win sends `kAirPodsConnected` so phone yields.

### Phase 3 — Trigger-typed handovers (medium risk)
*Make call vs music distinction observable to peers (G3, G4).*

1. **`HandoverController::onMediaPlayingChanged`** → split into `onMediaStarted(TriggerKind kind)` where `kind ∈ {Music, Call}`.
2. **`TeamsCallWatcher`** call site in `main.cpp:205-207` → pass `TriggerKind::Call`. Existing `GSMTC` path passes `TriggerKind::Music`.
3. **Outbound packet**: send `kRequestHandover` for `Call`, `kRequestDisconnect` for `Music`.
4. **Anti-pingpong window**:
   - Music: 3 s (unchanged).
   - Call: 500 ms (tighter — calls should beat the proactive-release window if user takes a call right after a meeting).

**Verification:** Start Spotify on Win → confirm `OUT kRequestDisconnect` in log. Then end music, hang up after Teams call (proactive RELEASE), then **immediately** answer an incoming Teams call → confirm `kRequestHandover` outbound and reclaim within 500 ms.

### Phase 4 — Local-media coherence on remote takeover — DONE IN PHASE 1
G5 is closed as part of Phase 1 (see Phase 1 step 2). Optional belt-and-suspenders `m_airpods.disconnect()` on stale A2DP after `kAirPodsConnected` remains a possible future tweak; defer.

### Phase 5 — Per-peer ownership tracking (low risk, UI polish)
*Show "AirPods: on Pixel" vs "AirPods: on Xiaomi" in tray (G7, M4).*

1. **`PeerRegistry`**: change `setOnPacket` callback signature to `(uint64_t sourceAddr, span<uint8_t> data)`. Plumb the source address through `BluetoothRfcommClient` (it knows which peer it's reading from).
2. **`HandoverController::onIncomingPacket(uint64_t srcAddr, span<...> data)`**: on `kAirPodsConnected`, update `m_holderAddress = srcAddr`.
3. **Tray** `StatusProvider`: when `state == Remote`, append the holder name.

**Verification:** UI inspection only — no protocol change.

### Phase 6 — Race & robustness (low priority)
*M2, M3, X6 — corner cases.*

1. **Idempotent transitions**: ensure `setState(Remote)` while already Remote is a no-op (already true).
2. **Last-writer-wins for `kAirPodsConnected`**: implicit, since we always set Remote and last `m_holderAddress` write wins. Acceptable.
3. **Stale `m_lastLocalTakeover`**: after Win loses AirPods, clear it so a phone re-grab the next morning isn't anti-pingponged. Reset on `setState(Remote)`.

### Phase 7 — Cosmetic & ergonomics
1. Logger UTC → local (already in Phase 1).
2. Tray menu polish.
3. Verify Teams cmd_settings.json regex against latest New Teams version.
4. Audit `log::handover` vs `log::debug` levels — handover.log should read like a story.

## 6. Cross-Cutting Concerns

### 6.1 Threading & deadlock
- Audio watcher runs on its own thread, calls `m_peers.sendPacket` and `m_airpods.disconnect` under no lock. Good.
- `PeerRegistry::sendPacket` holds `m_mutex` while iterating peers — sending blocks per-peer. Acceptable (sends are quick).
- `BluetoothRfcommClient` callbacks must not deadlock against `PeerRegistry::m_mutex` — already handled per [`PeerRegistry.cpp:97-103`](../windows/src/PeerRegistry.cpp) comment.

### 6.2 Protocol forward-compat
We're adding `kRequestHandover` outbound. Android treats it identically to `kRequestDisconnect` today (no harm). When Android learns to distinguish them, calls-vs-music semantics improve automatically.

### 6.3 Known limitations to document
- **P4 (phone-call vs Win-music)**: until Android sends `kRequestHandover`, Win will REJECT a phone-call takeover during Win-music. Workaround: user pauses Spotify manually.
- **G9 (REJECT re-assertion soft)**: Android's A2DP fight may still win. Document.
- **RFCOMM down + Win is source**: audio cuts abruptly (no pause). Inherent — no fix without RFCOMM up.

### 6.4 What's explicitly out of scope
- AACP relay through Windows (not a Windows responsibility).
- Battery/ANC bytes to peers from Windows (handover.md doesn't require it).
- Role election between two Windows PCs (single-PC topology assumed).
- AirPods H2/H1 chipset-specific quirks beyond `setAsDefaultAudioDevice` polling.

## 7. Suggested Execution Order

Land **Phase 1 first, in isolation**, and verify against the existing handover.md S1–S5 tests before touching anything else. The fixes are surgical and high-confidence.

Then Phase 2 (the BT-state observer), because **G2** is the largest behavioural gap and touches multiple scenario rows. Phase 3 follows naturally on top — they share the watcher.

Phases 4–7 can be picked up in any order or trimmed.

---

## Appendix: Files relevant to this work

Windows (in this worktree):
- [`windows/src/HandoverController.cpp`](../windows/src/HandoverController.cpp) / [`.hpp`](../windows/src/HandoverController.hpp) — state machine, audio watcher, packet handling
- [`windows/src/PeerRegistry.cpp`](../windows/src/PeerRegistry.cpp) / [`.hpp`](../windows/src/PeerRegistry.hpp) — multi-peer broadcast
- [`windows/src/TeamsCallWatcher.cpp`](../windows/src/TeamsCallWatcher.cpp) / [`.hpp`](../windows/src/TeamsCallWatcher.hpp) — WASAPI Teams/VLC detection
- [`windows/src/AirPodsConnector.cpp`](../windows/src/AirPodsConnector.cpp) / [`.hpp`](../windows/src/AirPodsConnector.hpp) — BT connect/disconnect, audio routing, hasActiveAudioSessions
- [`windows/src/MediaPlaybackWatcher.cpp`](../windows/src/MediaPlaybackWatcher.cpp) — GSMTC pause/play helpers
- [`windows/src/Logger.hpp`](../windows/src/Logger.hpp) — handover.log writer
- [`windows/src/main.cpp`](../windows/src/main.cpp) — wiring
- [`windows/common/crossdevice_protocol.hpp`](../windows/common/crossdevice_protocol.hpp) — packet definitions

Reference docs:
- [`docs/handover.md`](handover.md) — protocol + Android implementation (source of truth for what works today)
