#pragma once

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace librepods {

struct Settings {
    std::vector<std::uint64_t>   androidAddresses;  // one entry per paired Android peer
    std::optional<std::uint64_t> airpodsAddress;
};

class SettingsStore {
public:
    SettingsStore();
    Settings load() const;
    void save(const Settings& s) const;

    static std::optional<std::uint64_t> parseBluetoothAddress(std::string_view s);
    static std::string formatBluetoothAddress(std::uint64_t addr);

private:
    std::filesystem::path m_path;
};

}
