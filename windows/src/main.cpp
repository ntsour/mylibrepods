#include <windows.h>

#include <winrt/base.h>
#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Devices.Bluetooth.Rfcomm.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Storage.Streams.h>

#include "AirPodsConnector.hpp"
#include "HandoverController.hpp"
#include "Logger.hpp"
#include "MediaPlaybackWatcher.hpp"
#include "PeerRegistry.hpp"
#include "SettingsStore.hpp"
#include "TeamsCallWatcher.hpp"
#include "TrayIcon.hpp"
#include "crossdevice_protocol.hpp"

#include <algorithm>
#include <atomic>
#include <memory>
#include <optional>

using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::Rfcomm;
using namespace winrt::Windows::Devices::Enumeration;

namespace librepods {

namespace {

std::wstring stateLabel(OwnershipState s) {
    switch (s) {
        case OwnershipState::LocalPc:       return L"AirPods: on this PC";
        case OwnershipState::RemoteAndroid: return L"AirPods: on Android";
        default:                            return L"AirPods: unknown";
    }
}

// Returns ALL paired devices that advertise the CrossDevice RFCOMM UUID.
std::vector<std::uint64_t> discoverAndroidPeers() {
    std::vector<std::uint64_t> found;
    try {
        winrt::guid rfcommGuid {
            0x1abbb9a4u, 0x10e4u, 0x4000u,
            { 0xa7, 0x5c, 0x89, 0x53, 0xc5, 0x47, 0x13, 0x42 }
        };
        auto rfcommId = RfcommServiceId::FromUuid(rfcommGuid);

        // Fast path: collect everything the SDP cache already knows about.
        auto selector = RfcommDeviceService::GetDeviceSelector(rfcommId);
        auto cachedHits = DeviceInformation::FindAllAsync(selector).get();
        for (auto&& hit : cachedHits) {
            try {
                auto service = RfcommDeviceService::FromIdAsync(hit.Id()).get();
                if (!service || !service.Device()) continue;
                auto bdev = service.Device();
                log::info("Discovered Android peer (cached SDP): {} ({})",
                    to_string(bdev.Name()),
                    SettingsStore::formatBluetoothAddress(bdev.BluetoothAddress()));
                found.push_back(bdev.BluetoothAddress());
            } catch (...) {}
        }

        // Slow path: probe paired devices not yet in the SDP cache.
        auto pairedSelector = BluetoothDevice::GetDeviceSelectorFromPairingState(true);
        auto paired = DeviceInformation::FindAllAsync(pairedSelector).get();
        log::debug("Paired classic-Bluetooth devices: {}", paired.Size());

        for (auto&& info : paired) {
            try {
                auto bdev = BluetoothDevice::FromIdAsync(info.Id()).get();
                if (!bdev) continue;
                // Skip addresses already collected from the cache.
                if (std::find(found.begin(), found.end(), bdev.BluetoothAddress()) != found.end())
                    continue;
                log::debug("  Probing {} ({})...",
                    to_string(bdev.Name()),
                    SettingsStore::formatBluetoothAddress(bdev.BluetoothAddress()));
                auto services = bdev.GetRfcommServicesForIdAsync(rfcommId, BluetoothCacheMode::Uncached).get();
                if (services.Services().Size() > 0) {
                    log::info("Discovered Android peer (uncached SDP): {} ({})",
                        to_string(bdev.Name()),
                        SettingsStore::formatBluetoothAddress(bdev.BluetoothAddress()));
                    found.push_back(bdev.BluetoothAddress());
                }
            } catch (const hresult_error& e) {
                log::debug("    skipped (probe failed: {})", to_string(e.message()));
            }
        }

        if (found.empty()) {
            log::warn("No paired device advertises CrossDevice UUID. "
                      "Make sure: (1) the phone is paired with this PC, "
                      "(2) Handover is ON in the LibrePods Android app.");
        }
    } catch (const hresult_error& e) {
        log::error("Peer discovery failed: 0x{:08X} {}",
            (std::uint32_t)e.code().value, to_string(e.message()));
    }
    return found;
}

std::optional<std::uint64_t> discoverAirPods() {
    try {
        // Match paired audio devices whose name contains "AirPods".
        auto selector = BluetoothDevice::GetDeviceSelectorFromPairingState(true);
        auto devices = DeviceInformation::FindAllAsync(selector).get();
        for (auto&& info : devices) {
            auto name = std::wstring(info.Name());
            if (name.find(L"AirPods") != std::wstring::npos) {
                auto bdev = BluetoothDevice::FromIdAsync(info.Id()).get();
                if (bdev) {
                    log::info("Found paired AirPods: {} ({})",
                        to_string(bdev.Name()),
                        SettingsStore::formatBluetoothAddress(bdev.BluetoothAddress()));
                    return bdev.BluetoothAddress();
                }
            }
        }
        log::warn("No paired AirPods found. Pair them in Windows Settings first.");
        return std::nullopt;
    } catch (const hresult_error& e) {
        log::error("AirPods discovery failed: {}", to_string(e.message()));
        return std::nullopt;
    }
}

}

}

int APIENTRY wWinMain(HINSTANCE hInstance, HINSTANCE, PWSTR, int) {
    using namespace librepods;

#ifndef NDEBUG
    // Attach a console so stderr/stdout are visible when debugging.
    AllocConsole();
    FILE* dummy;
    freopen_s(&dummy, "CONOUT$", "w", stderr);
    freopen_s(&dummy, "CONOUT$", "w", stdout);
    SetConsoleTitleW(L"LibrePods Debug");
    log::info("=== LibrePods debug build ===");
#endif

    winrt::init_apartment(winrt::apartment_type::multi_threaded);

    SettingsStore store;
    Settings settings = store.load();

    if (settings.androidAddresses.empty()) {
        auto discovered = discoverAndroidPeers();
        if (!discovered.empty()) {
            settings.androidAddresses = std::move(discovered);
            store.save(settings);
        }
    }
    if (!settings.airpodsAddress) {
        if (auto a = discoverAirPods()) {
            settings.airpodsAddress = a;
            store.save(settings);
        }
    }

    AirPodsConnector airpods{settings.airpodsAddress.value_or(0)};
    PeerRegistry peers;
    MediaPlaybackWatcher media;
    HandoverController controller{peers, airpods, media};
    TeamsCallWatcher teamsWatcher;
    TrayIcon tray;

    if (!tray.create(hInstance)) return 1;
    tray.setStatus(stateLabel(controller.state()));

    controller.setOnStateChanged([&tray](OwnershipState s) {
        tray.setStatus(stateLabel(s));
    });

    // Provide live status to the tray menu (queried each time the menu opens).
    tray.setStatusProvider([&]() -> TrayIcon::StatusInfo {
        TrayIcon::StatusInfo info;
        info.airpodsConnected = airpods.isClassicallyConnected();
        for (auto& p : peers.peerInfos())
            info.peers.push_back({p.name, p.connected});
        return info;
    });

    peers.setOnPacket([&controller](std::span<const std::uint8_t> data) {
        controller.onIncomingPacket(data);
    });
    peers.setOnState([&controller](bool connected) {
        controller.onPeerConnectionChanged(connected);
    });

    media.setCallback([&controller](bool playing) {
        controller.onMediaPlayingChanged(playing);
    });

    // WASAPI-based trigger (Teams calls, VLC, …) feeds the same handover path
    // as the GSMTC media callback. The HandoverController's debounce and
    // anti-pingpong guards apply equally to both paths.
    teamsWatcher.setOnTriggered([&controller]() {
        controller.onMediaPlayingChanged(true);
    });

    tray.setMenuHandler([&](int cmd) {
        switch (cmd) {
            case TrayIcon::kCmdQuit:
                tray.postQuit();
                break;
            case TrayIcon::kCmdPairAndroid: {
                // Discover ALL devices advertising the CrossDevice UUID and add any new ones.
                auto discovered = discoverAndroidPeers();
                auto& addrs = settings.androidAddresses;
                bool anyAdded = false;
                for (auto a : discovered) {
                    if (std::find(addrs.begin(), addrs.end(), a) == addrs.end()) {
                        addrs.push_back(a);
                        peers.addPeer(a);  // starts immediately (registry is running)
                        log::info("Added new Android peer: {}",
                            SettingsStore::formatBluetoothAddress(a));
                        anyAdded = true;
                    } else {
                        log::info("Android peer {} already registered",
                            SettingsStore::formatBluetoothAddress(a));
                    }
                }
                if (anyAdded) store.save(settings);
                break;
            }
            case TrayIcon::kCmdPairAirPods:
                if (auto a = discoverAirPods()) {
                    settings.airpodsAddress = a;
                    store.save(settings);
                    airpods.setAddress(*a);
                }
                break;
        }
    });

    for (auto addr : settings.androidAddresses)
        peers.addPeer(addr);

    if (!settings.androidAddresses.empty()) {
        peers.start();
    } else {
        log::warn("No Android peers configured. Use tray menu \"Add Android peer...\" once a device is paired in Windows.");
    }

    media.start();
    teamsWatcher.start();

    tray.runMessageLoop();

    teamsWatcher.stop();
    media.stop();
    peers.stop();
    return 0;
}
