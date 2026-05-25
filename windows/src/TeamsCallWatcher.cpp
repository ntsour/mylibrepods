#include "TeamsCallWatcher.hpp"

#include "Logger.hpp"

#include <shlwapi.h>

#include <future>
#include <string>
#include <vector>

namespace librepods {

using Microsoft::WRL::ComPtr;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

namespace {

const char* flowLabel(EDataFlow flow) { return flow == eRender ? "render" : "capture"; }
const char* roleLabel(ERole role) {
    switch (role) {
        case eCommunications: return "comms";
        case eMultimedia:     return "multimedia";
        default:              return "console";
    }
}

std::wstring processBasename(DWORD pid) {
    if (!pid) return {};
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h) return {};
    wchar_t buf[MAX_PATH]; DWORD len = MAX_PATH;
    std::wstring name;
    if (QueryFullProcessImageNameW(h, 0, buf, &len))
        name = PathFindFileNameW(buf);
    CloseHandle(h);
    return name;
}

// Comms apps: trigger on audio-session activation (call ringing / meeting join).
bool isCommsApp(const std::wstring& exe) {
    return _wcsicmp(exe.c_str(), L"ms-teams.exe") == 0
        || _wcsicmp(exe.c_str(), L"Teams.exe") == 0;
}

// Media apps: trigger on audio-session activation (playback started).
// VLC creates new WASAPI sessions on seek/track-change, so the PID dedupe
// (5 s window) prevents repeated popups during normal use.
bool isMediaApp(const std::wstring& exe) {
    return _wcsicmp(exe.c_str(), L"vlc.exe") == 0;
}

bool isWatchedApp(const std::wstring& exe) {
    return isCommsApp(exe) || isMediaApp(exe);
}

std::string narrow(const std::wstring& w) {
    std::string s; s.reserve(w.size());
    for (wchar_t c : w) s.push_back(static_cast<char>(c));
    return s;
}


} // namespace

// ---------------------------------------------------------------------------
// SessionEvents — IAudioSessionEvents for one audio session
// Fires onTeamsSessionActive when the session transitions to Active state.
// ---------------------------------------------------------------------------

class TeamsCallWatcher::SessionEvents : public IAudioSessionEvents {
public:
    SessionEvents(TeamsCallWatcher* owner, std::wstring exe, DWORD pid,
                  EDataFlow flow, ERole role)
        : m_owner(owner), m_exe(std::move(exe)), m_pid(pid),
          m_flow(flow), m_role(role) {}

    void clearOwner() { m_owner = nullptr; }

    ULONG STDMETHODCALLTYPE AddRef() override { return ++m_refs; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG r = --m_refs; if (!r) delete this; return r;
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
        if (!out) return E_POINTER;
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IAudioSessionEvents)) {
            *out = static_cast<IAudioSessionEvents*>(this); AddRef(); return S_OK;
        }
        *out = nullptr; return E_NOINTERFACE;
    }

    HRESULT STDMETHODCALLTYPE OnStateChanged(AudioSessionState state) override {
        if (state == AudioSessionStateActive && m_owner)
            m_owner->onTeamsSessionActive(m_pid, m_exe, m_flow, m_role);
        return S_OK;
    }

    // Unused IAudioSessionEvents stubs
    HRESULT STDMETHODCALLTYPE OnDisplayNameChanged(LPCWSTR, LPCGUID) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnIconPathChanged(LPCWSTR, LPCGUID) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnSimpleVolumeChanged(float, BOOL, LPCGUID) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnChannelVolumeChanged(DWORD, float*, DWORD, LPCGUID) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnGroupingParamChanged(LPCGUID, LPCGUID) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnSessionDisconnected(AudioSessionDisconnectReason) override { return S_OK; }

private:
    std::atomic<ULONG> m_refs{1};
    TeamsCallWatcher* m_owner;
    std::wstring m_exe;
    DWORD m_pid;
    EDataFlow m_flow;
    ERole m_role;
};

// ---------------------------------------------------------------------------
// Notification — IAudioSessionNotification for the endpoint manager
// Fires when a brand-new session is created (e.g. a fresh ringtone stream).
// ---------------------------------------------------------------------------

class TeamsCallWatcher::Notification : public IAudioSessionNotification {
public:
    Notification(TeamsCallWatcher* owner, EDataFlow flow, ERole role)
        : m_owner(owner), m_flow(flow), m_role(role) {}

    void clearOwner() { m_owner = nullptr; }

    ULONG STDMETHODCALLTYPE AddRef() override { return ++m_refs; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG r = --m_refs; if (!r) delete this; return r;
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
        if (!out) return E_POINTER;
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IAudioSessionNotification)) {
            *out = static_cast<IAudioSessionNotification*>(this); AddRef(); return S_OK;
        }
        *out = nullptr; return E_NOINTERFACE;
    }

    HRESULT STDMETHODCALLTYPE OnSessionCreated(IAudioSessionControl* ctrl) override {
        if (ctrl && m_owner)
            m_owner->onNewSession(ctrl, m_flow, m_role);
        return S_OK;
    }

private:
    std::atomic<ULONG> m_refs{1};
    TeamsCallWatcher* m_owner;
    EDataFlow m_flow;
    ERole m_role;
};

// ---------------------------------------------------------------------------
// TeamsCallWatcher implementation
// ---------------------------------------------------------------------------

void TeamsCallWatcher::onTeamsSessionActive(DWORD pid, const std::wstring& exe,
                                             EDataFlow flow, ERole role) {
    if (!shouldReport(pid)) {
        log::debug("Watched app active (dedupe suppressed): {} pid {} on {}/{}",
                   narrow(exe), (unsigned)pid, flowLabel(flow), roleLabel(role));
        return;
    }
    const bool comms = isCommsApp(exe);
    log::info("Watched app {} active: {} (pid {}) on {}/{}",
              comms ? "call/meeting" : "media playback",
              narrow(exe), (unsigned)pid, flowLabel(flow), roleLabel(role));
    log::handover("TRIGGER {} active: {} (pid {}) on {}/{}",
                  comms ? "call/meeting" : "media",
                  narrow(exe), (unsigned)pid, flowLabel(flow), roleLabel(role));
    // Feed into the handover state machine — same effect as GSMTC media-start.
    if (m_onTriggered) m_onTriggered();
}

void TeamsCallWatcher::onNewSession(IAudioSessionControl* ctrl,
                                    EDataFlow flow, ERole role) {
    // Find the right EndpointSub and register events on this new session.
    for (auto& ep : m_endpointSubs) {
        if (ep.flow == flow && ep.role == role) {
            watchSession(ctrl, flow, role);
            // Also store in ep.sessions for cleanup — find by flow/role match.
            // (watchSession stores into the first matching ep for cleanup.)
            break;
        }
    }
}

void TeamsCallWatcher::watchSession(IAudioSessionControl* ctrl,
                                    EDataFlow flow, ERole role) {
    ComPtr<IAudioSessionControl2> ctrl2;
    if (FAILED(ctrl->QueryInterface(IID_PPV_ARGS(&ctrl2)))) return;

    DWORD pid = 0;
    if (FAILED(ctrl2->GetProcessId(&pid)) || !pid) return;

    // System-sounds session — skip.
    if (ctrl2->IsSystemSoundsSession() == S_OK) return;

    std::wstring exe = processBasename(pid);
    if (exe.empty()) return;

    AudioSessionState state{};
    ctrl->GetState(&state);

    log::debug("TeamsCallWatcher: existing session {} (pid {}) on {}/{} state={}",
               narrow(exe), (unsigned)pid, flowLabel(flow), roleLabel(role), (int)state);

    if (!isWatchedApp(exe)) return;

    // If already active right now (Teams was in a call before we started), report.
    if (state == AudioSessionStateActive)
        onTeamsSessionActive(pid, exe, flow, role);

    // Register for future state changes.
    ComPtr<SessionEvents> ev;
    ev.Attach(new SessionEvents(this, exe, pid, flow, role));
    if (SUCCEEDED(ctrl->RegisterAudioSessionNotification(ev.Get()))) {
        for (auto& ep : m_endpointSubs) {
            if (ep.flow == flow && ep.role == role) {
                ep.sessions.push_back({ctrl, std::move(ev)});
                break;
            }
        }
    }
}

bool TeamsCallWatcher::shouldReport(DWORD pid) {
    using namespace std::chrono;
    std::scoped_lock lk{m_dedupeMutex};
    auto now = steady_clock::now();
    if (pid == m_lastReportedPid && now - m_lastReportedAt < seconds(20))
        return false;
    m_lastReportedPid = pid;
    m_lastReportedAt = now;
    return true;
}

bool TeamsCallWatcher::subscribeToEndpoint(EDataFlow flow, ERole role) {
    ComPtr<IMMDevice> endpoint;
    HRESULT hr = m_enumerator->GetDefaultAudioEndpoint(flow, role, &endpoint);
    if (FAILED(hr)) {
        log::debug("TeamsCallWatcher: GetDefaultAudioEndpoint({}/{}) failed: 0x{:08X}",
                   flowLabel(flow), roleLabel(role), (unsigned)hr);
        return false;
    }

    LPWSTR idRaw = nullptr;
    endpoint->GetId(&idRaw);
    std::wstring deviceId = idRaw ? idRaw : L"";
    if (idRaw) CoTaskMemFree(idRaw);

    // Don't double-register on the same physical device for the same flow direction.
    for (const auto& ep : m_endpointSubs) {
        if (ep.flow == flow && ep.deviceId == deviceId) {
            log::debug("TeamsCallWatcher: skipping duplicate device for {}/{} (same as {})",
                       flowLabel(flow), roleLabel(role), roleLabel(ep.role));
            return true;
        }
    }

    ComPtr<IAudioSessionManager2> mgr;
    hr = endpoint->Activate(__uuidof(IAudioSessionManager2), CLSCTX_ALL, nullptr, &mgr);
    if (FAILED(hr)) {
        log::warn("TeamsCallWatcher: Activate(IAudioSessionManager2) on {}/{} failed: 0x{:08X}",
                  flowLabel(flow), roleLabel(role), (unsigned)hr);
        return false;
    }

    // Register for new sessions.
    ComPtr<Notification> notif;
    notif.Attach(new Notification(this, flow, role));
    hr = mgr->RegisterSessionNotification(notif.Get());
    if (FAILED(hr)) {
        log::warn("TeamsCallWatcher: RegisterSessionNotification on {}/{} failed: 0x{:08X}",
                  flowLabel(flow), roleLabel(role), (unsigned)hr);
        notif->clearOwner();
        return false;
    }

    log::info("TeamsCallWatcher: watching {}/{} (id={})",
              flowLabel(flow), roleLabel(role), narrow(deviceId));
    m_endpointSubs.push_back({std::move(deviceId), std::move(endpoint),
                               std::move(mgr), std::move(notif), {}, flow, role});

    // Enumerate and watch sessions that already exist on this endpoint.
    ComPtr<IAudioSessionEnumerator> sessEnum;
    if (SUCCEEDED(m_endpointSubs.back().mgr->GetSessionEnumerator(&sessEnum))) {
        int count = 0;
        sessEnum->GetCount(&count);
        log::debug("TeamsCallWatcher: {} existing session(s) on {}/{}", count,
                   flowLabel(flow), roleLabel(role));
        for (int i = 0; i < count; ++i) {
            ComPtr<IAudioSessionControl> ctrl;
            if (SUCCEEDED(sessEnum->GetSession(i, &ctrl)))
                watchSession(ctrl.Get(), flow, role);
        }
    }

    return true;
}

void TeamsCallWatcher::unsubscribeAll() {
    for (auto& ep : m_endpointSubs) {
        for (auto& ss : ep.sessions) {
            if (ss.ctrl && ss.events) {
                ss.ctrl->UnregisterAudioSessionNotification(ss.events.Get());
                ss.events->clearOwner();
            }
        }
        if (ep.mgr && ep.notification) {
            ep.mgr->UnregisterSessionNotification(ep.notification.Get());
            ep.notification->clearOwner();
        }
    }
    m_endpointSubs.clear();
    m_enumerator.Reset();
}

void TeamsCallWatcher::staThreadProc(std::promise<bool>& readySignal) {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(hr)) {
        log::error("TeamsCallWatcher STA: CoInitializeEx failed: 0x{:08X}", (unsigned)hr);
        readySignal.set_value(false);
        return;
    }

    hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr,
                          CLSCTX_ALL, IID_PPV_ARGS(&m_enumerator));
    if (FAILED(hr)) {
        log::error("TeamsCallWatcher: CoCreateInstance(MMDeviceEnumerator) failed: 0x{:08X}",
                   (unsigned)hr);
        CoUninitialize();
        readySignal.set_value(false);
        return;
    }

    subscribeToEndpoint(eRender,  eCommunications);
    subscribeToEndpoint(eCapture, eCommunications);
    subscribeToEndpoint(eRender,  eMultimedia);
    subscribeToEndpoint(eCapture, eMultimedia);

    if (m_endpointSubs.empty()) {
        log::error("TeamsCallWatcher: no endpoints registered");
        CoUninitialize();
        readySignal.set_value(false);
        return;
    }

    readySignal.set_value(true);

    // STA message loop — WASAPI delivers callbacks here.
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    unsubscribeAll();
    CoUninitialize();
}

TeamsCallWatcher::TeamsCallWatcher() = default;
TeamsCallWatcher::~TeamsCallWatcher() { stop(); }

bool TeamsCallWatcher::start() {
    if (m_started.exchange(true)) return true;

    std::promise<bool> readySignal;
    auto ready = readySignal.get_future();

    m_staThread = std::thread([this, &readySignal]() {
        m_staThreadId = GetCurrentThreadId();
        staThreadProc(readySignal);
    });

    const bool ok = ready.get();
    if (!ok) { m_staThread.join(); m_started.store(false); }
    return ok;
}

void TeamsCallWatcher::stop() {
    if (!m_started.exchange(false)) return;
    if (m_staThreadId)
        PostThreadMessageW(m_staThreadId, WM_QUIT, 0, 0);
    if (m_staThread.joinable())
        m_staThread.join();
    m_staThreadId = 0;
    log::info("TeamsCallWatcher: stopped");
}

}
