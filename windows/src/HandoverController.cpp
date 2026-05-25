#include "HandoverController.hpp"

#include "Logger.hpp"
#include "crossdevice_protocol.hpp"

#include <array>
#include <thread>
#include <windows.h>

namespace librepods {

HandoverController::HandoverController(
    PeerRegistry& peers,
    AirPodsConnector& airpods,
    MediaPlaybackWatcher& media)
    : m_peers(peers), m_airpods(airpods), m_media(media)
{
    startAudioWatcher();
}

HandoverController::~HandoverController() {
    m_watcherRunning.store(false);
    if (m_watcherThread.joinable()) m_watcherThread.join();
}

void HandoverController::startAudioWatcher() {
    m_watcherRunning.store(true);
    m_watcherThread = std::thread([this]() {
        try {
            winrt::init_apartment(winrt::apartment_type::multi_threaded);
        } catch (const std::exception& e) {
            log::warn("Audio watcher: failed to init WinRT apartment: {}", e.what());
            return;  // Exit thread, but don't crash the app
        }

        // Hysteresis: gain "active" instantly (we want peer-protection ASAP),
        // but require ≥2 consecutive idle polls (≥1 s at 500 ms cadence) before
        // dropping to "idle". A single-poll WASAPI/BT flicker during Teams route
        // renegotiation would otherwise tell Android "go ahead, grab the AirPods"
        // for a few hundred ms — long enough for Android's takeover to fire.
        bool lastActive = false;     // last state broadcast to peers
        int  idleStreak = 0;         // # consecutive polls observed idle while broadcast=active
        constexpr int kIdleStreakThreshold = 2;
        // Initialised to actual state so the first poll doesn't produce a synthetic edge.
        bool lastBtConnected = m_airpods.isClassicallyConnected();
        while (m_watcherRunning.load()) {
            try {
                // Resolve Unknown state if peers are now connected.
                // Guard the packet send on setState's return value: if onPeerConnectionChanged
                // raced us here and already resolved Unknown, setState returns false and we
                // skip the duplicate send.
                if (m_state.load() == OwnershipState::Unknown && m_peers.isAnyConnected()) {
                    bool airpodsHere = m_airpods.isClassicallyConnected();
                    bool changed = setState(airpodsHere ? OwnershipState::LocalPc
                                                        : OwnershipState::RemoteAndroid);
                    if (changed) {
                        log::handover("OUT     {} → peer (periodic sync on Unknown state)",
                            airpodsHere ? "kAirPodsConnected" : "kAirPodsDisconnected");
                        m_peers.sendPacket(airpodsHere
                            ? crossdevice::kAirPodsConnected
                            : crossdevice::kAirPodsDisconnected);
                    }
                }

                // BT-edge observer: catches connect/disconnect events that bypass the
                // CrossDevice protocol (Apple auto-switch, manual connect, phone grabs via BT
                // before RFCOMM is up). Runs every 500 ms poll — fast enough for the handover
                // feel while cheap enough not to stress the BT stack.
                bool btConnected = m_airpods.isClassicallyConnected();
                if (btConnected && !lastBtConnected) {
                    // Rising edge: AirPods just appeared on Windows (X1, X3, C2 scenarios).
                    // Reset audio state: the classical BT link is up but A2DP hasn't negotiated
                    // yet, so there are no audio sessions. Carrying stale lastActive=true into
                    // this window makes the watcher misread the normal A2DP startup gap as
                    // "audio was active, now gone → phone stole them" and fire a false RECLAIM.
                    // Starting fresh means the watcher waits to observe what actually happens.
                    lastActive = false;
                    idleStreak = 0;
                    // Don't call setAsDefaultAudioDevice here — avoids stealing audio routing
                    // when the user manually connected for another purpose (e.g., firmware update).
                    if (setState(OwnershipState::LocalPc)) {
                        log::handover("BT      AirPods connected (BT rising edge) — claiming ownership");
                        if (m_peers.isAnyConnected()) {
                            log::handover("OUT     kAirPodsConnected → all peers (BT edge)");
                            m_peers.sendPacket(crossdevice::kAirPodsConnected);
                        }
                    }
                } else if (!btConnected && lastBtConnected) {
                    // Falling edge: AirPods left Windows. Only act when we thought we owned them.
                    if (m_state.load() == OwnershipState::LocalPc) {
                        // Unified pause-and-release. We previously had two branches:
                        //   - lastActive=true  → RECLAIM (try to grab AirPods back)
                        //   - lastActive=false → pause local media, release ownership
                        //
                        // Experience showed the RECLAIM was *always* wrong in practice:
                        //   - When the user intentionally moved AirPods to the phone, RECLAIM
                        //     fought the user's choice and snatched them back.
                        //   - When AirPods went into the case / died / were stolen by Apple's
                        //     auto-switch, RECLAIM couldn't find them anyway and spammed
                        //     pointless retries.
                        //   - For the original target scenario (mid-Teams-call phone grab),
                        //     pausing Windows media is actually correct: Teams' own multi-
                        //     device handover takes the call to the phone, and Windows
                        //     shouldn't fight that.
                        //
                        // Brief-bounce protection (AirPods drop off Windows for a second and
                        // come back) is now handled at the audio-endpoint layer by the
                        // IMMNotificationClient's persistent-arm mode: while we own the
                        // AirPods, any UNPLUGGED→ACTIVE re-applies routing automatically.
                        //
                        // So: always pause local media and release ownership on BT falling
                        // edge while we held LocalPc.
                        const char* reason = lastActive
                            ? "BT      AirPods disconnected mid-active-audio — pausing local media and releasing (no fight-back)"
                            : "BT      AirPods disconnected (BT falling edge, idle) — releasing";

                        // Set the anti-pingpong timestamp FIRST, before any potentially-blocking
                        // operation. Mirrors the fix in onIncomingPacket's release handlers. The
                        // pause calls below can take 10-50 ms each (GSMTC, SendInput), and the
                        // PlaybackInfoChanged callback runs on a separate GSMTC thread — without
                        // this, Chrome auto-resuming when audio falls back to PC speakers can
                        // fire onMediaPlayingChanged(true) before m_lastLostOwnership is set,
                        // and anti-pingpong fails to suppress the counter-takeover.
                        m_lastLostOwnership.store(tpToNs(std::chrono::steady_clock::now()));

                        m_media.tryPauseActive();
                        m_media.tryPauseAllSessions();
                        m_media.tryPauseViaMediaKey();
                        m_media.tryPauseAllBrowserWindows();
                        if (setState(OwnershipState::RemoteAndroid)) {
                            log::handover("{}", reason);
                            if (m_peers.isAnyConnected()) {
                                log::handover("OUT     kAirPodsDisconnected → all peers (BT edge)");
                                m_peers.sendPacket(crossdevice::kAirPodsDisconnected);
                            }
                        }
                    }
                    // state != LocalPc: RequestDisconnect / RequestHandover already updated state
                    // before the BT stack confirmed the drop — expected, no-op.
                }
                lastBtConnected = btConnected;

                // Only meaningful when AirPods are connected to this PC. We use the
                // *call-specific* check (Teams/Zoom/Discord/Slack/WebEx) rather than
                // hasActiveAudioSessions() so that kWindowsAudioActive is a meaningful
                // "do not interrupt" signal: peers should hold off from a takeover
                // when we're in a call, but should NOT hold off just because we have
                // music playing — the user is free to move the AirPods to their phone
                // in that case. Symmetric with the R1 narrowing of the local REJECT
                // gate (HandoverController.cpp:378). Without this, a peer that gates
                // its own takeover on the received kWindowsAudioActive signal would
                // refuse to migrate the AirPods during ordinary YouTube/Spotify
                // playback.
                bool active = btConnected && m_airpods.hasActiveCallSessions();

                if (active && !lastActive) {
                    // Rising edge: broadcast ACTIVE immediately.
                    lastActive = true;
                    idleStreak = 0;
                    log::handover("AUDIO   ACTIVE — AirPods have an active call session");
                    if (m_peers.isAnyConnected()) {
                        m_peers.sendPacket(crossdevice::kWindowsAudioActive);
                    }
                } else if (!active && lastActive) {
                    // Idle observed while we still consider ourselves active — count it.
                    ++idleStreak;
                    if (idleStreak >= kIdleStreakThreshold) {
                        // Sustained idle: broadcast IDLE as an informational signal to peers.
                        // We do NOT release ownership here — Windows holds the AirPods until
                        // another device actively claims them (RequestDisconnect / RequestHandover
                        // over RFCOMM, or a BT falling edge from a direct phone grab).
                        lastActive = false;
                        idleStreak = 0;
                        log::handover("AUDIO   IDLE   — AirPods call session ended");
                        if (m_peers.isAnyConnected()) {
                            m_peers.sendPacket(crossdevice::kWindowsAudioIdle);
                        }
                    } else {
                        log::handover("AUDIO   transient blip — holding active state (streak {}/{})",
                                      idleStreak, kIdleStreakThreshold);
                    }
                } else if (active && lastActive) {
                    // Recovered before the threshold — reset the streak silently.
                    idleStreak = 0;
                }
                // !active && !lastActive: steady idle. Nothing to do — we hold ownership
                // until an explicit external event takes it.
            } catch (const std::exception& e) {
                log::warn("Audio watcher: exception during poll: {}", e.what());
                // Continue polling, don't crash
            }

            // Sliced sleep so shutdown is responsive (50 ms × 10 = 500 ms poll interval).
            for (int i = 0; i < 10 && m_watcherRunning.load(); ++i) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
        }
    });
}

bool HandoverController::setState(OwnershipState s) {
    auto previous = m_state.exchange(s);
    if (previous != s) {
        const char* label = (s == OwnershipState::LocalPc)     ? "LocalPc"
                          : (s == OwnershipState::RemoteAndroid) ? "RemoteAndroid"
                          :                                        "Unknown";
        log::handover("STATE → {}", label);
        if (m_onStateChanged) m_onStateChanged(s);

        // Persistent-arm the endpoint notifier whenever we hold ownership. While
        // we're LocalPc, AirPods bouncing off Windows briefly (Apple auto-switch,
        // brief BT blip) and coming back will re-route automatically without a
        // fresh setAsDefaultAudioDevice() call. When ownership leaves us, drop
        // the persistent flag so we don't fight a peer that is intentionally
        // taking the AirPods.
        m_airpods.setPersistentArm(s == OwnershipState::LocalPc);

        return true;
    }
    return false;
}

bool HandoverController::withinDebounceWindow() {
    std::scoped_lock lk{m_debounceMutex};
    auto now = std::chrono::steady_clock::now();
    if (now - m_lastAction < kDebounce) return true;
    m_lastAction = now;
    return false;
}

void HandoverController::onMediaPlayingChanged(bool playing) {
    if (!playing) {
        log::debug("Media stopped — keeping current ownership");
        return;
    }
    if (withinDebounceWindow()) {
        log::debug("Debounced media-start event");
        return;
    }

    const bool actuallyConnected = m_airpods.isClassicallyConnected();
    const bool stateLocal = (m_state.load() == OwnershipState::LocalPc);
    log::debug("Media start: state={} airpodsConnected={}",
        stateLocal ? "LocalPc" : "remote/unknown",
        actuallyConnected);

    if (stateLocal && actuallyConnected) {
        log::handover("SKIP    AirPods already on this PC — no action needed");
        return;
    }

    // Anti-pingpong: if we just lost ownership to Android, don't immediately take back.
    auto sinceLost = std::chrono::steady_clock::now() - tpFromNs(m_lastLostOwnership.load());
    if (sinceLost < std::chrono::milliseconds(3000)) {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(sinceLost).count();
        log::handover("SKIP    Anti-pingpong: lost ownership {}ms ago — not reclaiming", ms);
        return;
    }

    const std::string appId = m_media.currentAppId();
    log::handover("TRIGGER Media/call active (app={}) — requesting handover from Android",
                  appId.empty() ? "unknown" : appId);
    // Pause local media so audio doesn't leak through PC speakers during the
    // ~1-2s while AirPods are migrating. We'll resume it once we've claimed them.
    const bool paused = m_media.tryPauseActive();

    // Capture the kickoff time BEFORE sending kRequestDisconnect so the takeover
    // thread can distinguish a "kAirPodsDisconnected from peer" arriving in response
    // to *this* request from earlier stale ones.
    const auto kickoffTime = std::chrono::steady_clock::now();
    const bool peerConnected = m_peers.isAnyConnected();
    if (peerConnected) {
        log::handover("OUT     kRequestDisconnect → all peers");
        m_peers.sendPacket(crossdevice::kRequestDisconnect);
    } else {
        log::handover("WARN    No peers connected — attempting BT connect without coordination");
    }

    // Dispatch all blocking work (sleep + BT connect + audio endpoint poll) to a
    // detached thread. The WinRT media-callback thread must not be blocked: the
    // runtime will terminate a callback thread that doesn't return promptly, causing
    // a crash.
    //
    // Kickoff: instead of a fixed 1500 ms wait, listen on m_peerAckCv for the peer
    // to confirm kAirPodsDisconnected. The Pixel app typically acks within ~100 ms.
    // We connect ~300 ms after ack to give the AirPods time to actually release at
    // the BT layer (acking the RFCOMM packet ≠ A2DP fully released). If no peer ack
    // arrives within 1500 ms, fall through to the timer-based path.
    //
    // Subsequent retries use a backoff (1500ms, 3000ms) — kept short because Xiaomi
    // MIUI and similar slow vendors can take 2-5 s to actually release A2DP after
    // the RFCOMM ack. The first attempt is the fast path; retries cover stragglers.
    std::thread([this, paused, kickoffTime, peerConnected]() {
        winrt::init_apartment(winrt::apartment_type::multi_threaded);

        // Phase 1: event-driven kickoff for the first attempt.
        constexpr auto kMaxAckWait     = std::chrono::milliseconds(1500);
        constexpr auto kPostAckGrace   = std::chrono::milliseconds(300);
        const auto ackDeadline = kickoffTime + kMaxAckWait;

        bool gotPeerAck = false;
        if (peerConnected) {
            std::unique_lock<std::mutex> lk(m_peerAckMtx);
            gotPeerAck = m_peerAckCv.wait_until(lk, ackDeadline, [this, &kickoffTime]() {
                return tpFromNs(m_lastPeerAckAt.load()) >= kickoffTime;
            });
        }

        if (gotPeerAck) {
            auto ackedAfter = std::chrono::duration_cast<std::chrono::milliseconds>(
                tpFromNs(m_lastPeerAckAt.load()) - kickoffTime).count();
            log::handover("ACTION  Peer acked kAirPodsDisconnected after {}ms — grace {}ms then BT connect",
                ackedAfter, kPostAckGrace.count());
            std::this_thread::sleep_for(kPostAckGrace);
        } else {
            auto waited = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - kickoffTime).count();
            log::handover("ACTION  No peer ack within {}ms — proceeding with BT connect anyway",
                waited);
        }

        // First attempt
        log::handover("ACTION  BT connect attempt 1/3");
        bool connected = m_airpods.connect();
        if (!connected) {
            log::handover("RETRY   BT connect attempt 1 failed — backing off");
            constexpr std::array<int, 2> kBackoffsMs{1500, 3000};
            for (size_t i = 0; i < kBackoffsMs.size(); ++i) {
                std::this_thread::sleep_for(std::chrono::milliseconds(kBackoffsMs[i]));
                log::handover("ACTION  BT connect attempt {}/3 (after {} ms wait)",
                              i + 2, kBackoffsMs[i]);
                if (m_airpods.connect()) {
                    connected = true;
                    break;
                }
                log::handover("RETRY   BT connect attempt {} failed — backing off", i + 2);
            }
        }
        if (connected) {
            setState(OwnershipState::LocalPc);
            m_lastLocalTakeover.store(tpToNs(std::chrono::steady_clock::now()));
            if (m_peers.isAnyConnected()) {
                log::handover("OUT     kAirPodsConnected → all peers");
                m_peers.sendPacket(crossdevice::kAirPodsConnected);
            }
            // Make AirPods the default audio render + capture device.
            m_airpods.setAsDefaultAudioDevice();

            // Resume whatever we paused above, now that AirPods are the active route.
            // Small delay lets the audio stack settle on the new endpoint first.
            if (paused) {
                std::this_thread::sleep_for(std::chrono::milliseconds(250));
                m_media.tryPlayActive();
            }

            // Deferred retry: device-management software (Jabra Direct, Poly Lens,
            // enterprise audio policies) often reasserts a managed headset as the
            // audio default a few seconds after a change. Re-assert AirPods after
            // 3 s to win that race. Also re-writes Teams' cmd_settings.json so any
            // call that the user answers in the interim picks up AirPods.
            std::this_thread::sleep_for(std::chrono::milliseconds(3000));
            if (m_airpods.isClassicallyConnected()) {
                log::handover("ACTION  Re-asserting AirPods as default audio (3s retry)");
                m_airpods.setAsDefaultAudioDevice();
            }
        } else {
            log::handover("FAIL    BT connect failed after 3 attempts — AirPods not acquired");
            if (paused) {
                // Takeover failed — resume on whatever route we have so we don't leave
                // the user with paused media for no reason.
                m_media.tryPlayActive();
            }
        }
    }).detach();
}

void HandoverController::onIncomingPacket(std::span<const std::uint8_t> data) {
    using namespace crossdevice;
    auto kind = classify(data);
    switch (kind) {
        case Incoming::RequestDisconnect: {
            // Reject takeover attempts that happen within ~3s of our own takeover.
            // Android's MediaController fires takeover whenever media is "active",
            // which is often true right after we ourselves grabbed the AirPods.
            auto sinceTakeover = std::chrono::steady_clock::now() - tpFromNs(m_lastLocalTakeover.load());
            if (sinceTakeover < std::chrono::milliseconds(3000)) {
                auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(sinceTakeover).count();
                log::handover("IN      kRequestDisconnect — REJECTED (anti-pingpong, {}ms since takeover)", ms);
                if (m_peers.isAnyConnected()) {
                    m_peers.sendPacket(kAirPodsConnected);
                }
                break;
            }
            // Protect active calls (Teams meeting, Zoom call, etc.) from being snatched
            // away by a peer. The check is intentionally narrow: only reject when a real
            // *communications* app has an active session on the AirPods. Passive media
            // (YouTube, Spotify, browser audio) is NOT protected — the user is happy to
            // let go of the AirPods if they're intentionally moving to their phone.
            //
            // The audio watcher already sent kWindowsAudioActive so Android should have
            // gated on takeoverWhenCall, but guard here too for races and for peers
            // (e.g. Xiaomi) that don't yet honor the audio-active signal.
            if (m_airpods.isClassicallyConnected() && m_airpods.hasActiveCallSessions()) {
                log::handover("IN      kRequestDisconnect — REJECTED (call/meeting active on Windows)");
                if (m_peers.isAnyConnected()) {
                    m_peers.sendPacket(kAirPodsConnected);  // re-assert
                }
                break;
            }
            log::handover("IN      kRequestDisconnect — ACCEPTED, releasing AirPods to Android");

            // Record "lost ownership" NOW, before any potentially-blocking operation.
            // Chrome and other apps auto-resume playback within ~30-50ms when the
            // default audio device changes (which our disconnect triggers). If we
            // delay this store until after disconnect()/setState() (~50-150ms later),
            // the media-start callback that fires during those ~50ms sees a stale
            // m_lastLostOwnership, skips the anti-pingpong check, and triggers a
            // counter-takeover that fights the peer that just legitimately claimed
            // the AirPods. Setting it first makes the anti-pingpong window cover
            // the entire release sequence.
            m_lastLostOwnership.store(tpToNs(std::chrono::steady_clock::now()));

            // Pause all Windows media before the AirPods leave so audio doesn't route
            // to PC speakers, and so the media-start callback doesn't fire and
            // immediately try to reclaim the AirPods.
            // Fire all three methods regardless of earlier successes: a GSMTC pause
            // of Spotify does not stop VLC (non-GSMTC), and the media key catches
            // anything else (browsers, etc.).
            m_media.tryPauseActive();        // GSMTC: pause the focused session
            m_media.tryPauseAllSessions();   // GSMTC: pause any background sessions
            m_media.tryPauseViaMediaKey();   // Media key: VLC, browsers, non-GSMTC apps
            m_media.tryPauseAllBrowserWindows();  // multi-window Chrome/Edge/Firefox
            m_airpods.disconnect();
            setState(OwnershipState::RemoteAndroid);
            if (m_peers.isAnyConnected()) {
                log::handover("OUT     kAirPodsDisconnected → all peers");
                m_peers.sendPacket(kAirPodsDisconnected);
            }
            break;
        }

        case Incoming::RequestHandover: {
            // Call-priority handover request from peer. Unlike RequestDisconnect,
            // this is never rejected — the peer is taking AirPods for a call, which
            // is peak user attention. Even mid-Teams-meeting on Windows, we yield.
            // The user's principle: calls always win, on whichever device they're on.
            log::handover("IN      kRequestHandover — ACCEPTED unconditionally (call priority)");
            // Set the anti-pingpong timestamp first (see comment in ACCEPTED above).
            m_lastLostOwnership.store(tpToNs(std::chrono::steady_clock::now()));
            m_media.tryPauseActive();
            m_media.tryPauseAllSessions();
            m_media.tryPauseViaMediaKey();
            m_media.tryPauseAllBrowserWindows();
            m_airpods.disconnect();
            setState(OwnershipState::RemoteAndroid);
            if (m_peers.isAnyConnected()) {
                log::handover("OUT     kAirPodsDisconnected → all peers");
                m_peers.sendPacket(kAirPodsDisconnected);
            }
            break;
        }

        case Incoming::AirPodsConnected: {
            // Peer announces it now has the AirPods. If we previously held them, pause
            // local media so anything still routing through PC speakers (Spotify after
            // AirPods left, browser audio, etc.) doesn't leak. Check state BEFORE the
            // transition so we don't pause on a peer's idempotent re-assertion when we
            // were already Remote.
            const bool wasLocalPc = (m_state.load() == OwnershipState::LocalPc);
            log::handover("IN      kAirPodsConnected from peer → STATE RemoteAndroid");

            // Record both timestamps NOW, before any potentially-blocking work:
            //   - m_lastPeerOwnershipClaim: tells the BT falling-edge logic the peer
            //     just intentionally claimed, so we skip any reclaim attempt.
            //   - m_lastLostOwnership: anti-pingpong window for the media-start
            //     callback that fires when Chrome auto-resumes after the device
            //     change. Without this, a Pixel/Xiaomi handover triggers a counter-
            //     takeover ~50ms later when Chrome resumes on PC speakers.
            const auto nowNs = tpToNs(std::chrono::steady_clock::now());
            m_lastPeerOwnershipClaim.store(nowNs);
            m_lastLostOwnership.store(nowNs);

            if (wasLocalPc) {
                m_media.tryPauseActive();
                m_media.tryPauseAllSessions();
                m_media.tryPauseViaMediaKey();
                m_media.tryPauseAllBrowserWindows();
            }
            setState(OwnershipState::RemoteAndroid);
            break;
        }

        case Incoming::AirPodsDisconnected:
            log::handover("IN      kAirPodsDisconnected from peer (waiting for local trigger)");
            // Record the timestamp and wake any takeover thread that may be waiting
            // for the peer to confirm release of the AirPods. This lets the BT
            // connect attempt fire immediately on peer ack rather than after a
            // fixed 1.5 s timer, cutting best-case handover latency by ~1.4 s.
            m_lastPeerAckAt.store(tpToNs(std::chrono::steady_clock::now()));
            m_peerAckCv.notify_all();
            // Don't claim ownership just because remote dropped — wait for our own media event.
            break;

        case Incoming::RequestConnectionStatus: {
            log::debug("Peer requested connection status");
            const auto& reply = (m_state.load() == OwnershipState::LocalPc)
                                ? kAirPodsConnected
                                : kAirPodsDisconnected;
            m_peers.sendPacket(reply);
            break;
        }

        case Incoming::RequestBatteryBytes:
        case Incoming::RequestAncBytes:
            log::debug("Peer requested battery/ANC bytes — not supported in Windows v1");
            break;

        case Incoming::RelayHeader:
            log::debug("Peer relayed AACP packet — Windows v1 ignores AACP relay");
            break;

        case Incoming::WindowsAudioActive:
        case Incoming::WindowsAudioIdle:
            // These packets flow Windows → Android only; ignore if received from Android.
            log::debug("Ignoring audio-state packet from Android (not expected)");
            break;

        case Incoming::Unknown:
            log::debug("Unknown packet ({} bytes)", data.size());
            break;
    }
}

void HandoverController::onPeerConnectionChanged(bool connected) {
    if (connected) {
        log::handover("PEER    Android connected via CrossDevice RFCOMM");
        // Re-sync cached state with reality before announcing.
        const bool airpodsHere = m_airpods.isClassicallyConnected();
        const bool wasLocalPc  = (m_state.load() == OwnershipState::LocalPc);

        setState(airpodsHere ? OwnershipState::LocalPc : OwnershipState::RemoteAndroid);
        log::handover("OUT     {} → peer (sync on connect)",
            airpodsHere ? "kAirPodsConnected" : "kAirPodsDisconnected");
        m_peers.sendPacket(airpodsHere
            ? crossdevice::kAirPodsConnected
            : crossdevice::kAirPodsDisconnected);

        // If we held ownership (LocalPc) but the AirPods are gone, the peer grabbed them
        // via Bluetooth before the RFCOMM coordination channel was established (bypassing
        // the protocol). Now that RFCOMM is up, reclaim them.
        if (wasLocalPc && !airpodsHere) {
            log::handover("RECLAIM Peer grabbed AirPods before RFCOMM was up — reclaiming now");
            onMediaPlayingChanged(true);
        }
    } else {
        log::handover("PEER    Android disconnected");
    }
}

}
