#include "SettingsStore.hpp"

#include "Logger.hpp"

#include <windows.h>
#include <shlobj.h>

#include <charconv>
#include <fstream>
#include <sstream>
#include <string>

namespace librepods {

namespace {

std::filesystem::path resolveConfigPath() {
    PWSTR roaming = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, nullptr, &roaming))) {
        std::filesystem::path p = roaming;
        CoTaskMemFree(roaming);
        p /= L"LibrePods";
        std::error_code ec;
        std::filesystem::create_directories(p, ec);
        return p / L"config.txt";
    }
    return std::filesystem::current_path() / L"config.txt";
}

}

SettingsStore::SettingsStore() : m_path(resolveConfigPath()) {}

std::optional<std::uint64_t> SettingsStore::parseBluetoothAddress(std::string_view s) {
    std::string compact;
    compact.reserve(12);
    for (char c : s) {
        if (c == ':' || c == '-' || c == ' ') continue;
        compact.push_back(c);
    }
    if (compact.size() != 12) return std::nullopt;
    std::uint64_t value = 0;
    auto [ptr, ec] = std::from_chars(
        compact.data(), compact.data() + compact.size(), value, 16);
    if (ec != std::errc{} || ptr != compact.data() + compact.size()) return std::nullopt;
    return value;
}

std::string SettingsStore::formatBluetoothAddress(std::uint64_t addr) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X",
        (unsigned)((addr >> 40) & 0xFF),
        (unsigned)((addr >> 32) & 0xFF),
        (unsigned)((addr >> 24) & 0xFF),
        (unsigned)((addr >> 16) & 0xFF),
        (unsigned)((addr >>  8) & 0xFF),
        (unsigned)((addr >>  0) & 0xFF));
    return buf;
}

Settings SettingsStore::load() const {
    log::debug("Loading settings from {}", m_path.string());
    Settings s;
    std::ifstream f(m_path);
    if (!f) {
        log::debug("Settings file not found, using defaults");
        return s;
    }
    std::string line;
    while (std::getline(f, line)) {
        auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string key = line.substr(0, eq);
        std::string val = line.substr(eq + 1);
        if (key == "android") {
            if (auto addr = parseBluetoothAddress(val)) {
                s.androidAddresses.push_back(*addr);
                log::debug("  android = {} -> {}", val, formatBluetoothAddress(*addr));
            } else {
                log::warn("  android = {} -> (parse failed)", val);
            }
        } else if (key == "airpods") {
            s.airpodsAddress = parseBluetoothAddress(val);
            log::debug("  airpods = {} -> {}",
                val, s.airpodsAddress ? formatBluetoothAddress(*s.airpodsAddress) : "(parse failed)");
        }
    }
    return s;
}

void SettingsStore::save(const Settings& s) const {
    std::ofstream f(m_path, std::ios::trunc);
    if (!f) {
        log::warn("Failed to open settings file for write: {}", m_path.string());
        return;
    }
    for (auto addr : s.androidAddresses)
        f << "android=" << formatBluetoothAddress(addr) << '\n';
    if (s.airpodsAddress) f << "airpods=" << formatBluetoothAddress(*s.airpodsAddress) << '\n';
}

}
