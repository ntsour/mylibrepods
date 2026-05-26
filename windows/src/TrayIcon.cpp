#include "TrayIcon.hpp"

#include "Logger.hpp"

#include <strsafe.h>

namespace librepods {

namespace {
TrayIcon* g_instance = nullptr;
constexpr wchar_t kClassName[] = L"LibrePodsTrayWindow";
constexpr UINT kIconId = 1;
}

TrayIcon::TrayIcon() {
    g_instance = this;
}

TrayIcon::~TrayIcon() {
    if (m_hwnd) {
        Shell_NotifyIconW(NIM_DELETE, &m_nid);
        DestroyWindow(m_hwnd);
        m_hwnd = nullptr;
    }
    g_instance = nullptr;
}

bool TrayIcon::create(HINSTANCE hInstance) {
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = &TrayIcon::wndProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = kClassName;
    RegisterClassExW(&wc);

    m_hwnd = CreateWindowExW(
        0, kClassName, L"LibrePods",
        WS_OVERLAPPED,
        0, 0, 0, 0,
        HWND_MESSAGE, nullptr, hInstance, nullptr);
    if (!m_hwnd) {
        log::error("CreateWindowExW failed: {}", GetLastError());
        return false;
    }

    m_nid.cbSize = sizeof(m_nid);
    m_nid.hWnd = m_hwnd;
    m_nid.uID = kIconId;
    m_nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    m_nid.uCallbackMessage = WM_TRAY;
    m_nid.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    StringCchCopyW(m_nid.szTip, ARRAYSIZE(m_nid.szTip), m_statusText.c_str());
    if (!Shell_NotifyIconW(NIM_ADD, &m_nid)) {
        log::error("Shell_NotifyIconW(NIM_ADD) failed");
        return false;
    }
    return true;
}

void TrayIcon::setStatus(const std::wstring& text) {
    m_statusText = text;
    if (!m_hwnd) return;
    m_nid.uFlags = NIF_TIP;
    StringCchCopyW(m_nid.szTip, ARRAYSIZE(m_nid.szTip), m_statusText.c_str());
    Shell_NotifyIconW(NIM_MODIFY, &m_nid);
}

LRESULT CALLBACK TrayIcon::wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (g_instance) return g_instance->handleMessage(hwnd, msg, wp, lp);
    return DefWindowProcW(hwnd, msg, wp, lp);
}

LRESULT TrayIcon::handleMessage(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_TRAY:
            if (LOWORD(lp) == WM_RBUTTONUP || LOWORD(lp) == WM_CONTEXTMENU) {
                showContextMenu(hwnd);
            }
            return 0;
        case WM_COMMAND:
            if (m_menuHandler) m_menuHandler((int)LOWORD(wp));
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
        default:
            return DefWindowProcW(hwnd, msg, wp, lp);
    }
}

void TrayIcon::showContextMenu(HWND hwnd) {
    POINT pt;
    GetCursorPos(&pt);

    // Fetch live status just before the menu opens.
    StatusInfo status;
    if (m_statusProvider) status = m_statusProvider();

    HMENU menu = CreatePopupMenu();

    // ── AirPods ──────────────────────────────────────────────────────────────
    // Ownership line (e.g. "AirPods: on this PC")
    InsertMenuW(menu, static_cast<UINT>(-1),
        MF_BYPOSITION | MF_STRING | MF_DISABLED | MF_GRAYED,
        0u, m_statusText.c_str());

    // Physical BT connection state with checkmark when connected.
    const wchar_t* airpodsDetail = status.airpodsConnected
        ? L"AirPods connected (Bluetooth)"
        : L"AirPods not connected";
    UINT airpodsFlags = MF_BYPOSITION | MF_STRING | MF_DISABLED | MF_GRAYED;
    if (status.airpodsConnected) airpodsFlags |= MF_CHECKED;
    InsertMenuW(menu, static_cast<UINT>(-1), airpodsFlags, 0u, airpodsDetail);

    InsertMenuW(menu, static_cast<UINT>(-1), MF_BYPOSITION | MF_SEPARATOR, 0u, nullptr);

    // ── Android peers ─────────────────────────────────────────────────────────
    if (!status.peers.empty()) {
        InsertMenuW(menu, static_cast<UINT>(-1),
            MF_BYPOSITION | MF_STRING | MF_DISABLED | MF_GRAYED,
            0u, L"Android peers:");
        for (auto& peer : status.peers) {
            UINT peerFlags = MF_BYPOSITION | MF_STRING | MF_DISABLED | MF_GRAYED;
            if (peer.connected) peerFlags |= MF_CHECKED;
            InsertMenuW(menu, static_cast<UINT>(-1), peerFlags, 0u, peer.name.c_str());
        }
        InsertMenuW(menu, static_cast<UINT>(-1), MF_BYPOSITION | MF_SEPARATOR, 0u, nullptr);
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    InsertMenuW(menu, static_cast<UINT>(-1), MF_BYPOSITION | MF_STRING,
        static_cast<UINT_PTR>(kCmdPairAndroid), L"Add Android peer...");
    InsertMenuW(menu, static_cast<UINT>(-1), MF_BYPOSITION | MF_STRING,
        static_cast<UINT_PTR>(kCmdPairAirPods), L"Select AirPods...");
    InsertMenuW(menu, static_cast<UINT>(-1), MF_BYPOSITION | MF_SEPARATOR, 0u, nullptr);
    InsertMenuW(menu, static_cast<UINT>(-1), MF_BYPOSITION | MF_STRING,
        static_cast<UINT_PTR>(kCmdQuit), L"Quit");

    SetForegroundWindow(hwnd);
    TrackPopupMenu(menu, TPM_BOTTOMALIGN | TPM_LEFTALIGN, pt.x, pt.y, 0, hwnd, nullptr);
    DestroyMenu(menu);
}

void TrayIcon::runMessageLoop() {
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
}

void TrayIcon::postQuit() {
    PostQuitMessage(0);
}

}
