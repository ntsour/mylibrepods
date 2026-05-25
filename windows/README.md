# LibrePods — Windows

Minimal Windows companion for LibrePods. Implements cross-device handover only:
when you start media on Windows, the AirPods migrate from your paired Android
device; when Android needs them back (incoming call, media, etc.), they migrate
back. No ANC / battery / ear-detection on Windows — those live on Android.

## Requirements
- Windows 10 1803 or newer (Windows 11 recommended)
- Bluetooth + classic-RFCOMM-capable adapter
- Visual Studio 2022 Build Tools (or full IDE) with the "Desktop development with C++" workload and Windows 10/11 SDK
- CMake 3.20+

## Build
```powershell
cd windows
cmake -S . -B build -G "Visual Studio 17 2022" -A x64
cmake --build build --config Release
```
The resulting binary is `build/Release/LibrePods.exe`.

## First-time setup
1. Pair AirPods with Windows (Settings → Bluetooth & devices → Add).
2. Pair your Android phone with Windows (same place).
3. Enable Cross-Device in the LibrePods Android app.
4. Run `LibrePods.exe`. The tray icon appears. On first launch it auto-discovers
   both peers and writes `%APPDATA%\LibrePods\config.txt`.
5. If discovery fails: right-click tray → *Pair with Android...* and *Select AirPods...*

## How it works
LibrePods talks to Android's existing `1abbb9a4-10e4-…-5471342` RFCOMM service
using the 4-byte `CrossDevicePackets` protocol (see
[CrossDevice.kt](../android/app/src/main/java/io/nikos/propods/utils/CrossDevice.kt)).
Windows does not speak AACP/L2CAP to the AirPods themselves — it relies on the
OS A2DP/HFP profiles, so the AirPods appear as a normal Bluetooth headset.

| Direction | Packet | Behavior |
| --- | --- | --- |
| Windows → Android | `kRequestDisconnect` | Windows started media, please release AirPods |
| Android → Windows | `kRequestDisconnect` | Android needs them back, please release AirPods |
| Either way | `kAirPodsConnected` / `kAirPodsDisconnected` | Ownership status broadcast |
| Either way | `kWindowsAudioActive` / `kWindowsAudioIdle` | Windows is/is-not in a *call* — peers should hold off on takeovers during a call |

Multiple peers can be connected simultaneously (Pixel + Xiaomi, for example);
`kRequestDisconnect` is broadcast to *all* peers, and any peer can claim the
AirPods via `kAirPodsConnected`. The first peer to acknowledge release wins.

### Audio routing
When AirPods come up on Windows, the OS sometimes auto-promotes them to the
default render device, and sometimes doesn't (depends on the per-role
`level` / `rank` state — see
[Microsoft Learn: Default Audio Endpoint Selection](https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/default-audio-endpoint-selection)).
LibrePods runs an event-driven `IMMNotificationClient`: when the AirPods
endpoint transitions to `DEVICE_STATE_ACTIVE`, it calls
`IPolicyConfigVista::SetDefaultEndpoint` (the undocumented API every audio-
switcher utility uses) across all three roles — *but only when the endpoint
is not already the default* for that role. This avoids a brief audio glitch
when the OS has already auto-promoted us.

While Windows holds ownership (`OwnershipState::LocalPc`), the notifier stays
armed in "persistent" mode: any UNPLUGGED → ACTIVE bounce (Apple auto-switch
flutter; brief BT blip) re-applies routing automatically.

### When does Windows reject a peer takeover?
A peer's `kRequestDisconnect` is rejected only when a **real-time
communications app** has an active audio session on the AirPods. The
allowlist is in `AirPodsConnector.cpp::isCommsAppExe()` and currently covers:

| App | Process |
| --- | --- |
| Microsoft Teams (new) | `ms-teams.exe` |
| Microsoft Teams (classic) | `Teams.exe` |
| Zoom desktop | `zoom.exe` |
| Discord | `Discord.exe` |
| Slack | `slack.exe` |
| Cisco WebEx | `webex.exe`, `webexd.exe` |

Passive media (YouTube, Spotify, browser audio, system sounds) does **not**
trigger the reject. If you're listening to music on the PC and pick up your
phone to take a call, the AirPods migrate to the phone instantly.

### Multi-window pause
On a Windows → phone handover, all three GSMTC pause methods (current
session, all sessions, media-key broadcast) plus an `EnumWindows`-based
`WM_APPCOMMAND` pass to every top-level Chrome/Edge/Firefox window catch
YouTube/Spotify even when they're playing in an unfocused tab or on a
second monitor.

### Diagnostic logging
When `BluetoothSetServiceState` fails, a `[BT-diag]` block dumps
`BluetoothGetDeviceInfo` flags, the full `BluetoothEnumerateInstalledServices`
GUID list, and the WinRT `ConnectionStatus`. This makes it possible to tell
at a glance whether the device is paired, currently connected, and whether
A2DP/HFP are even advertised.

## Limitations (deferred)
- Battery / ANC / ear-detection UI (would need AACP/L2CAP)
- Auto-start on login (registry entry)
- MSI installer + code signing
- Browser-hosted call detection (Teams web, Google Meet, WebEx web). The
  current comms-app allowlist matches process names, which can't
  discriminate browser tabs running calls from browser tabs playing music.
