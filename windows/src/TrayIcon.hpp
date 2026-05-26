#pragma once

#include <functional>
#include <string>
#include <vector>

#include <windows.h>
#include <shellapi.h>

namespace librepods {

class TrayIcon {
public:
    using MenuHandler = std::function<void(int commandId)>;

    static constexpr int kCmdQuit        = 1001;
    static constexpr int kCmdPairAndroid = 1002;
    static constexpr int kCmdPairAirPods = 1003;

    // Per-peer info shown in the menu.
    struct PeerStatus {
        std::wstring name;
        bool         connected{false};
    };

    // Snapshot of app status, fetched just before the menu opens.
    struct StatusInfo {
        bool                    airpodsConnected{false};
        std::vector<PeerStatus> peers;
    };

    using StatusProvider = std::function<StatusInfo()>;

    TrayIcon();
    ~TrayIcon();

    TrayIcon(const TrayIcon&) = delete;
    TrayIcon& operator=(const TrayIcon&) = delete;

    bool create(HINSTANCE hInstance);
    void setStatus(const std::wstring& text);
    void setMenuHandler(MenuHandler h)     { m_menuHandler     = std::move(h); }
    void setStatusProvider(StatusProvider p) { m_statusProvider = std::move(p); }

    void runMessageLoop();
    void postQuit();

private:
    static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);
    LRESULT handleMessage(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);
    void showContextMenu(HWND hwnd);

    HWND m_hwnd{};
    NOTIFYICONDATAW m_nid{};
    std::wstring m_statusText{L"LibrePods"};
    MenuHandler     m_menuHandler;
    StatusProvider  m_statusProvider;
    static constexpr UINT WM_TRAY = WM_APP + 1;
};

}
