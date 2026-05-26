#include <windows.h>  // for keybd_event, VK_MEDIA_PLAY_PAUSE
#include "MediaPlaybackWatcher.hpp"

#include "Logger.hpp"

using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Media::Control;

namespace librepods {

namespace {
constexpr auto Playing = GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing;
}

MediaPlaybackWatcher::MediaPlaybackWatcher() = default;

MediaPlaybackWatcher::~MediaPlaybackWatcher() {
    stop();
}

void MediaPlaybackWatcher::start() {
    if (m_started.exchange(true)) return;
    try {
        m_manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
    } catch (const hresult_error& e) {
        log::error("Failed to get media session manager: {}", to_string(e.message()));
        m_started.store(false);
        return;
    }

    m_sessionsChangedToken = m_manager.SessionsChanged(
        [this](auto&&, auto&&) {
            rebuildSubscriptions();
            emitIfChanged();
        });

    rebuildSubscriptions();
    emitIfChanged();
}

void MediaPlaybackWatcher::stop() {
    if (!m_started.exchange(false)) return;
    if (m_manager) {
        try { m_manager.SessionsChanged(m_sessionsChangedToken); } catch (...) {}
    }
    std::scoped_lock lk{m_mutex};
    for (auto& [_, entry] : m_sessions) {
        try { entry.session.PlaybackInfoChanged(entry.playbackToken); } catch (...) {}
    }
    m_sessions.clear();
    m_manager = nullptr;
}

void MediaPlaybackWatcher::rebuildSubscriptions() {
    if (!m_manager) return;
    auto sessions = m_manager.GetSessions();

    std::scoped_lock lk{m_mutex};

    std::unordered_map<hstring, SessionEntry> next;

    for (auto&& session : sessions) {
        hstring id = session.SourceAppUserModelId();
        auto it = m_sessions.find(id);
        if (it != m_sessions.end()) {
            next.emplace(id, std::move(it->second));
            m_sessions.erase(it);
            continue;
        }
        SessionEntry entry;
        entry.session = session;
        bool isPlaying = false;
        try {
            isPlaying = session.GetPlaybackInfo().PlaybackStatus() == Playing;
        } catch (...) {}
        entry.playing = isPlaying;
        entry.playbackToken = session.PlaybackInfoChanged(
            [this, id](GlobalSystemMediaTransportControlsSession const& s, auto&&) {
                bool playing = false;
                GlobalSystemMediaTransportControlsSessionPlaybackStatus status{};
                try {
                    auto info = s.GetPlaybackInfo();
                    status = info.PlaybackStatus();
                    playing = status == Playing;
                } catch (...) {}
                log::debug("Session '{}' playback status -> {} (playing={})",
                    to_string(id), (int)status, playing);
                {
                    std::scoped_lock g{m_mutex};
                    auto it2 = m_sessions.find(id);
                    if (it2 != m_sessions.end()) it2->second.playing = playing;
                }
                emitIfChanged();
            });
        log::debug("Tracking media session: {} (initial playing={})", to_string(id), entry.playing);
        next.emplace(id, std::move(entry));
    }

    for (auto& [_, entry] : m_sessions) {
        try { entry.session.PlaybackInfoChanged(entry.playbackToken); } catch (...) {}
    }
    m_sessions = std::move(next);
}

bool MediaPlaybackWatcher::anyPlaying() const {
    for (const auto& [_, entry] : m_sessions) {
        if (entry.playing) return true;
    }
    return false;
}

std::string MediaPlaybackWatcher::currentAppId() const {
    std::scoped_lock lk{const_cast<std::mutex&>(m_mutex)};
    for (const auto& [id, entry] : m_sessions) {
        if (entry.playing) {
            std::string s;
            for (wchar_t c : std::wstring_view(id.c_str()))
                s.push_back(static_cast<char>(c));
            return s;
        }
    }
    return {};
}

bool MediaPlaybackWatcher::tryPauseActive() {
    if (!m_manager) return false;
    try {
        auto session = m_manager.GetCurrentSession();
        if (!session) {
            log::debug("tryPauseActive: no current session");
            return false;
        }
        auto status = session.GetPlaybackInfo().PlaybackStatus();
        if (status != Playing) {
            log::debug("tryPauseActive: current session not playing (status={})", (int)status);
            return false;
        }
        log::info("Pausing local media session: {}", to_string(session.SourceAppUserModelId()));
        bool ok = session.TryPauseAsync().get();
        if (!ok) log::warn("TryPauseAsync returned false (app may not support pause)");
        return ok;
    } catch (const hresult_error& e) {
        log::warn("tryPauseActive failed: 0x{:08X} {}",
            (std::uint32_t)e.code().value, to_string(e.message()));
        return false;
    }
}

bool MediaPlaybackWatcher::tryPlayActive() {
    if (!m_manager) return false;
    try {
        auto session = m_manager.GetCurrentSession();
        if (!session) {
            log::debug("tryPlayActive: no current session");
            return false;
        }
        auto status = session.GetPlaybackInfo().PlaybackStatus();
        if (status == Playing) {
            log::debug("tryPlayActive: current session already playing");
            return true;
        }
        log::info("Resuming local media session: {}", to_string(session.SourceAppUserModelId()));
        bool ok = session.TryPlayAsync().get();
        if (!ok) log::warn("TryPlayAsync returned false (app may not support play)");
        return ok;
    } catch (const hresult_error& e) {
        log::warn("tryPlayActive failed: 0x{:08X} {}",
            (std::uint32_t)e.code().value, to_string(e.message()));
        return false;
    }
}

bool MediaPlaybackWatcher::tryPauseAllSessions() {
    bool any_paused = false;
    {
        std::scoped_lock lk{m_mutex};
        for (auto& [id, entry] : m_sessions) {
            try {
                bool ok = entry.session.TryPauseAsync().get();
                if (ok) {
                    log::info("Paused session: {}", to_string(id));
                    any_paused = true;
                }
            } catch (const hresult_error& e) {
                log::debug("Failed to pause session {}: 0x{:08X}",
                    to_string(id), (std::uint32_t)e.code().value);
            } catch (...) {}
        }
    }
    return any_paused;
}

namespace {

// EnumWindows callback: post APPCOMMAND_MEDIA_PLAY_PAUSE to any visible top-level
// browser window. Class names checked:
//   - Chrome_WidgetWin_1 / Chrome_WidgetWin_0 : Google Chrome, Edge (Chromium), Brave, Vivaldi
//   - MozillaWindowClass                        : Firefox
// The lParam carries a counter we increment for each window we touch.
BOOL CALLBACK pauseBrowserWindowsProc(HWND hwnd, LPARAM lParam) {
    if (!IsWindowVisible(hwnd)) return TRUE;

    wchar_t cls[64] = {};
    if (GetClassNameW(hwnd, cls, 64) == 0) return TRUE;

    const std::wstring_view cls_v{cls};
    const bool isBrowser =
        cls_v == L"Chrome_WidgetWin_1" ||
        cls_v == L"Chrome_WidgetWin_0" ||
        cls_v == L"MozillaWindowClass";
    if (!isBrowser) return TRUE;

    // PostMessage so we don't block on a hung window. APPCOMMAND_MEDIA_PLAY_PAUSE
    // is what the OS sends when the user hits a hardware play/pause key while
    // this window has focus — every Chromium-based browser handles it for the
    // foreground tab, and Firefox honours it too.
    PostMessageW(hwnd, WM_APPCOMMAND, (WPARAM)hwnd,
                 (LPARAM)(APPCOMMAND_MEDIA_PLAY_PAUSE << 16));

    auto* count = reinterpret_cast<int*>(lParam);
    if (count) ++(*count);
    return TRUE;
}

}  // anonymous

int MediaPlaybackWatcher::tryPauseAllBrowserWindows() {
    int count = 0;
    EnumWindows(pauseBrowserWindowsProc, reinterpret_cast<LPARAM>(&count));
    if (count > 0) {
        log::info("Posted APPCOMMAND_MEDIA_PLAY_PAUSE to {} browser window(s)", count);
    } else {
        log::debug("tryPauseAllBrowserWindows: no top-level browser windows found");
    }
    return count;
}

bool MediaPlaybackWatcher::tryPauseViaMediaKey() {
    // Send a hardware VK_MEDIA_PLAY_PAUSE keystroke via SendInput. This reaches any
    // foreground-capable app that honours media keys — including VLC, which does not
    // register GSMTC sessions and is therefore invisible to tryPauseActive /
    // tryPauseAllSessions. Web browsers (Chrome, Edge) also respond to this key for
    // YouTube and similar players.
    log::info("Sending VK_MEDIA_PLAY_PAUSE to pause non-GSMTC media (VLC, browsers, ...)");
    INPUT inp[2] = {};
    inp[0].type = INPUT_KEYBOARD;
    inp[0].ki.wVk  = VK_MEDIA_PLAY_PAUSE;
    inp[0].ki.dwFlags = 0;                   // key down
    inp[1].type = INPUT_KEYBOARD;
    inp[1].ki.wVk  = VK_MEDIA_PLAY_PAUSE;
    inp[1].ki.dwFlags = KEYEVENTF_KEYUP;     // key up
    UINT sent = SendInput(static_cast<UINT>(std::size(inp)), inp, sizeof(INPUT));
    if (sent != std::size(inp)) {
        log::warn("SendInput(VK_MEDIA_PLAY_PAUSE) only sent {}/{} events", sent, std::size(inp));
        return false;
    }
    return true;
}

void MediaPlaybackWatcher::emitIfChanged() {
    bool playing;
    {
        std::scoped_lock lk{m_mutex};
        playing = anyPlaying();
    }
    bool previous = m_lastPlayingEmitted.exchange(playing);
    if (previous != playing && m_callback) {
        log::info("Media playing -> {}", playing);
        m_callback(playing);
    }
}

}
