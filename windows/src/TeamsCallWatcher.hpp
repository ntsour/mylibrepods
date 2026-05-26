#pragma once

#include <atomic>
#include <chrono>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <windows.h>
#include <mmdeviceapi.h>
#include <audiopolicy.h>
#include <wrl/client.h>

namespace librepods {

// Viability spike: detect Teams calls/meetings via WASAPI session state changes.
//
// Strategy: watch for AudioSessionStateActive on any Teams audio session.
// OnSessionCreated catches new sessions; existing sessions (created before we
// started) are enumerated at registration time and also watched for state changes.
// This covers both incoming calls (ringtone makes session Active immediately)
// and meeting joins (existing session transitions to Active when audio flows).
//
// Runs on a dedicated STA thread — required by RegisterSessionNotification.
class TeamsCallWatcher {
public:
    TeamsCallWatcher();
    ~TeamsCallWatcher();

    TeamsCallWatcher(const TeamsCallWatcher&) = delete;
    TeamsCallWatcher& operator=(const TeamsCallWatcher&) = delete;

    // Called when a watched app (Teams, VLC, …) audio session becomes active.
    // Wire this to HandoverController::onMediaPlayingChanged(true) so the
    // WASAPI-based trigger feeds into the same handover state machine as GSMTC.
    using TriggerCallback = std::function<void()>;
    void setOnTriggered(TriggerCallback cb) { m_onTriggered = std::move(cb); }

    bool start();
    void stop();

    // Called by nested COM objects — not part of the user API.
    void onTeamsSessionActive(DWORD pid, const std::wstring& exe, EDataFlow flow, ERole role);
    void onNewSession(IAudioSessionControl* ctrl, EDataFlow flow, ERole role);

private:
    class Notification;  // IAudioSessionNotification — watches for new sessions
    class SessionEvents; // IAudioSessionEvents — watches state on a specific session

    struct SessionSub {
        Microsoft::WRL::ComPtr<IAudioSessionControl> ctrl;
        Microsoft::WRL::ComPtr<SessionEvents> events;
    };

    struct EndpointSub {
        std::wstring deviceId;
        Microsoft::WRL::ComPtr<IMMDevice> endpoint;
        Microsoft::WRL::ComPtr<IAudioSessionManager2> mgr;
        Microsoft::WRL::ComPtr<Notification> notification;
        std::vector<SessionSub> sessions;
        EDataFlow flow;
        ERole role;
    };

    void staThreadProc(std::promise<bool>& readySignal);
    bool subscribeToEndpoint(EDataFlow flow, ERole role);
    void watchSession(IAudioSessionControl* ctrl, EDataFlow flow, ERole role);
    void unsubscribeAll();
    bool shouldReport(DWORD pid);

    Microsoft::WRL::ComPtr<IMMDeviceEnumerator> m_enumerator;
    std::vector<EndpointSub> m_endpointSubs;

    std::thread m_staThread;
    DWORD m_staThreadId{0};
    std::atomic<bool> m_started{false};

    TriggerCallback m_onTriggered;

    std::mutex m_dedupeMutex;
    DWORD m_lastReportedPid{0};
    std::chrono::steady_clock::time_point m_lastReportedAt{};
};

}
