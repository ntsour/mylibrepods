# Windows multi-peer alignment

Status: **plan — not yet implemented.** This captures what the Windows tray client
(`windows/`) needs in order to align with the multi-peer + "requester always wins"
design that Android adopted in Phases 1–4 (see `multi-peer-cross-device-plan.md` and
the "Requester Always Wins" section of `handover.md`).

The good news: **the Windows app is already multi-peer.** Most of this doc is
"verify, no code needed." There is exactly **one** real code change.

---

## Already done on Windows (verify only — no code needed)

- **Multi-peer transport** — `windows/src/PeerRegistry.cpp` holds a `std::vector<Entry>`,
  one `BluetoothRfcommClient` per peer, each with its own socket + reconnect/backoff
  loop. `sendPacket()` broadcasts to all connected peers; `isAnyConnected()` ORs them;
  `peerInfos()` exposes per-peer name + connected status.
- **Multi-peer storage** — `windows/src/SettingsStore.cpp` reads/writes multiple
  `android=<MAC>` lines into `Settings.androidAddresses` (a `vector<uint64_t>`).
  Config file: `%APPDATA%\LibrePods\config.txt`.
- **Multi-peer discovery + tray UI** — `main.cpp::discoverAndroidPeers()` returns **all**
  paired devices advertising the CrossDevice RFCOMM UUID. The tray "Add Android peer…"
  command (`kCmdPairAndroid`) adds any newly-discovered peers via `peers.addPeer()`
  (starts immediately). The tray status provider lists every peer with a connected dot.
- **No courtesy-veto packets** — `windows/common/crossdevice_protocol.hpp` never defined
  `REQUEST_TAKEOVER` / `COURTESY_GRANT` / `COURTESY_DENY`. Android removed that handshake
  in Phase 3 ("requester always wins"), so Windows is **already** forward-compatible:
  Android no longer sends those packets, and Windows never did. Nothing to add or remove.

There is therefore **no transport, storage, discovery, or UI work** to do on Windows.

---

## The one required change — drop the holder-side call veto

**File:** `windows/src/HandoverController.cpp` → `onIncomingPacket()` →
`case Incoming::RequestDisconnect`.

Today Windows **rejects** a peer's music-takeover (`kRequestDisconnect`) when a call/
meeting is active locally:

```cpp
if (m_airpods.isClassicallyConnected() && m_airpods.hasActiveCallSessions()) {
    log::handover("IN      kRequestDisconnect — REJECTED (call/meeting active on Windows)");
    if (m_peers.isAnyConnected()) {
        m_peers.sendPacket(kAirPodsConnected);  // re-assert
    }
    break;
}
```

This is a **holder-side veto** — exactly the pattern Android eliminated. Under the
agreed design (**requester always wins**: every takeover is an explicit user action on
the requesting device, so the holder must yield), this branch must be **removed**.

**Do:**
- Delete the `hasActiveCallSessions()` reject branch above. Windows then falls through
  to the normal ACCEPTED path: pause local media, `m_airpods.disconnect()`, set
  `RemoteAndroid`, broadcast `kAirPodsDisconnected`.
- **Keep** the anti-pingpong reject immediately above it (the
  `sinceTakeover < 3000ms` branch). That is a race guard against our own just-completed
  takeover bouncing back, **not** a holder veto — it stays.
- **No change** to `case Incoming::RequestHandover` — it already yields unconditionally
  (call priority). Under requester-always-wins, both `RequestDisconnect` and
  `RequestHandover` now simply mean "yield"; keeping the separate case is harmless.

**Net effect:** Windows honors every peer takeover request, symmetric with Android.

---

## Consequence to document — `WINDOWS_AUDIO_ACTIVE/IDLE` is now a no-op on Android

The audio watcher in `HandoverController.cpp` still broadcasts `kWindowsAudioActive` /
`kWindowsAudioIdle` to tell Android "I'm in a call, don't grab." After the Phase 3 veto
removal, Android's `peerAudioActive` flag is **set but never read** (`CrossDevice.kt`
only assigns it). So this signal **no longer affects Android behavior** — Android will
take the AirPods from a Windows call on an explicit user trigger.

**Recommendation:** leave the broadcast in place for now. It is harmless, keeps the
protocol symmetric, and is useful in logs. Removing it (and the corresponding Android
`peerAudioActive` plumbing) is optional future cleanup — do **not** spend time on it as
part of this alignment.

---

## Protocol reference (unchanged, for convenience)

`windows/common/crossdevice_protocol.hpp` — 4-byte packets, RFCOMM UUID
`1abbb9a4-10e4-4000-a75c-8953c5471342`:

| Packet | Hex | Direction / meaning |
|---|---|---|
| `kAirPodsConnected` | `00 01 00 01` | "I have the AirPods" |
| `kAirPodsDisconnected` | `00 01 00 00` | "I released the AirPods" |
| `kRequestDisconnect` | `00 02 00 00` | peer wants them for music → **now always honored** |
| `kRequestHandover` | `00 02 00 04` | peer wants them for a call → yield unconditionally |
| `kRequestConnectionStat` | `00 02 00 03` | keep-alive / status query |
| `kWindowsAudioActive/Idle` | `00 03 00 01` / `00` | Windows→Android call signal → **now a no-op on Android** |
| `kAirPodsDataHeader` | `00 04 00 01` | AACP relay (Windows ignores) |

Note: Android may still *reply* `COURTESY_GRANT` (`00 06 00 00`) if it ever receives a
legacy `REQUEST_TAKEOVER` — Windows neither sends nor needs to handle these.

---

## Verification (future Windows session)

1. **Build** per `windows/README.md` / `windows/CMakeLists.txt` (CMake + WinRT).
2. **Mesh visibility** — with 2 Android phones + this PC all paired and Handover ON,
   confirm the PC appears in each phone's "Connected devices" list (green dot), and the
   tray menu lists both phones.
3. **Music takeover (the fix)** — Windows holds the AirPods in a Teams call; start music
   on a phone. Windows should now **release** (logcat/handover log shows ACCEPTED, not
   the old "REJECTED (call/meeting active)"). Audio moves to the phone.
4. **Call handover** — start a call on a phone while Windows holds them → Windows yields.
5. **No ping-pong** — after Windows yields, it must not immediately reclaim (anti-pingpong
   guard intact).
