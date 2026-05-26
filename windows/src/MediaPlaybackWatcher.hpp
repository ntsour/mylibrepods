#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <thread>
#include <unordered_map>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>

namespace librepods {

class MediaPlaybackWatcher {
public:
    using PlaybackCallback = std::function<void(bool playing)>;

    MediaPlaybackWatcher();
    ~MediaPlaybackWatcher();

    MediaPlaybackWatcher(const MediaPlaybackWatcher&) = delete;
    MediaPlaybackWatcher& operator=(const MediaPlaybackWatcher&) = delete;

    void setCallback(PlaybackCallback cb) { m_callback = std::move(cb); }

    void start();
    void stop();

    // Returns the SourceAppUserModelId of the first session currently in the
    // Playing state, or empty string if nothing is playing. Used for diagnostic
    // popups during the viability spike.
    std::string currentAppId() const;

    // Send transport commands to the OS's currently-focused media session. Used
    // by HandoverController to pause/resume local media around a handover so
    // audio doesn't briefly leak through PC speakers during the ACL switch.
    bool tryPauseActive();
    bool tryPlayActive();

    // Fallback pause: try all registered sessions (some web players don't expose
    // via GetCurrentSession). Returns true if any session was paused.
    bool tryPauseAllSessions();

    // Last-resort pause: send global media pause key. Works for any app that
    // respects media keys (most do). Returns true.
    bool tryPauseViaMediaKey();

    // Multi-window pause: enumerate top-level browser windows (Chrome, Edge,
    // Firefox) and post APPCOMMAND_MEDIA_PLAY_PAUSE to each. Catches YouTube
    // on a non-focused window/monitor that the focused SendInput in
    // tryPauseViaMediaKey misses (browser only registers one GSMTC session and
    // VK_MEDIA_PLAY_PAUSE only reaches the foreground window). Returns the
    // number of windows the message was posted to.
    int tryPauseAllBrowserWindows();

private:
    void rebuildSubscriptions();
    bool anyPlaying() const;
    void emitIfChanged();

    winrt::Windows::Media::Control::GlobalSystemMediaTransportControlsSessionManager m_manager{nullptr};
    winrt::event_token m_sessionsChangedToken{};

    struct SessionEntry {
        winrt::Windows::Media::Control::GlobalSystemMediaTransportControlsSession session{nullptr};
        winrt::event_token playbackToken{};
        bool playing{false};
    };

    std::mutex m_mutex;
    std::unordered_map<winrt::hstring, SessionEntry> m_sessions;
    std::atomic<bool> m_lastPlayingEmitted{false};
    std::atomic<bool> m_started{false};

    PlaybackCallback m_callback;
};

}
