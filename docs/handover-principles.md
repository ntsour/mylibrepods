# Handover Design Principles (UX intent)

These principles drive the cross-device handover behavior in ProPods. They apply
to all platforms in the project (Android: Pixel/Xiaomi, Windows PC) and to all
interaction surfaces (music, podcast, video, phone calls, Teams/Zoom/Discord/VoIP).

## Principle 1 — Follow user attention on manual action

When the user **manually initiates** something on any device, ProPods should
pull the AirPods to that device and keep them there:
- Starts music/podcast/video playback
- Answers an incoming phone call
- Joins a Teams/Zoom/VoIP call
- Manually triggers any audio session on that device

The trigger is **user intent**, not device priority. The device where the user
acted owns the AirPods.

## Principle 2 — High-priority events interrupt for attention

While AirPods are on a "focused device" and a **high-priority event** arises on a
peer device, focus should switch to the peer so the user can attend:
- Incoming phone call (ringing) on peer
- Incoming Teams/Zoom/VoIP call on peer
- Other interruption-class events

This is automatic — the user shouldn't have to grab their other device first to
hear the ring.

## Principle 3 — After interruption resolves

When the high-priority event ends (call hung up, meeting left), the system **may**:
- Fall back to the previous "focused device", OR
- Stay on the device that received the call until the user manually switches

Either behavior is acceptable. The key is: **no orphaned media** (the previous
device's podcast/music should not silently resume without the user's input).

## Why: this matters

- AirPods are a single-output device — only one source can hold them at a time.
- Users carry multiple devices simultaneously (phone + tablet + PC) and expect
  audio to follow their attention without manual Bluetooth switching.
- Apple's own ecosystem provides this via iCloud "fast device switching" between
  Apple devices; ProPods provides equivalent behavior for Android + Windows.

## How to apply

When evaluating any new handover code path or fix:
1. Does it correctly identify the user's manual trigger? (play press, call answer)
2. Does it correctly identify high-priority interruptions on peers?
3. Does it avoid "ghost takeovers" where one device silently steals AirPods
   from another without the user acting on it?
4. After a call ends, does it leave the user in a sane state (no surprise
   auto-resume of unrelated media)?

## Related documents

- [`handover.md`](handover.md) — Technical implementation reference (device tiers, scenarios, RFCOMM protocol).
