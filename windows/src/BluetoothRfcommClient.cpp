#include "BluetoothRfcommClient.hpp"

#include "Logger.hpp"
#include "crossdevice_protocol.hpp"

#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>

#include <chrono>
#include <string>

using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::Rfcomm;
using namespace winrt::Windows::Networking::Sockets;
using namespace winrt::Windows::Storage::Streams;
using namespace winrt::Windows::Foundation;

namespace librepods {

namespace {

constexpr std::chrono::milliseconds kInitialBackoff{1500};
constexpr std::chrono::milliseconds kMaxBackoff{15000};

winrt::guid uuidFromString(std::string_view s) {
    winrt::guid g{};
    // Format: 1abbb9a4-10e4-4000-a75c-8953c5471342
    auto hex = [](char c) -> unsigned {
        if (c >= '0' && c <= '9') return (unsigned)(c - '0');
        if (c >= 'a' && c <= 'f') return (unsigned)(c - 'a' + 10);
        if (c >= 'A' && c <= 'F') return (unsigned)(c - 'A' + 10);
        return 0;
    };
    auto byte = [&](size_t i) -> std::uint8_t {
        return (std::uint8_t)((hex(s[i]) << 4) | hex(s[i + 1]));
    };
    g.Data1 = (std::uint32_t)byte(0) << 24 | (std::uint32_t)byte(2) << 16
            | (std::uint32_t)byte(4) <<  8 | (std::uint32_t)byte(6);
    g.Data2 = (std::uint16_t)((byte(9) << 8) | byte(11));
    g.Data3 = (std::uint16_t)((byte(14) << 8) | byte(16));
    g.Data4[0] = byte(19);
    g.Data4[1] = byte(21);
    g.Data4[2] = byte(24);
    g.Data4[3] = byte(26);
    g.Data4[4] = byte(28);
    g.Data4[5] = byte(30);
    g.Data4[6] = byte(32);
    g.Data4[7] = byte(34);
    return g;
}

}

BluetoothRfcommClient::BluetoothRfcommClient() = default;

BluetoothRfcommClient::~BluetoothRfcommClient() {
    stop();
}

void BluetoothRfcommClient::start(std::uint64_t peerAddress) {
    if (m_running.exchange(true)) return;
    m_worker = std::thread([this, peerAddress]() {
        winrt::init_apartment(winrt::apartment_type::multi_threaded);
        runLoop(peerAddress);
    });
}

void BluetoothRfcommClient::stop() {
    if (!m_running.exchange(false)) return;
    {
        std::scoped_lock lk{m_socketMutex};
        if (m_socket) {
            try { m_socket.Close(); } catch (...) {}
            m_socket = nullptr;
            m_writer = nullptr;
        }
    }
    if (m_worker.joinable()) m_worker.join();
}

void BluetoothRfcommClient::sendPacket(std::span<const std::uint8_t> bytes) {
    std::scoped_lock lk{m_socketMutex};
    if (!m_writer) {
        log::warn("RFCOMM not connected; dropping send of {} bytes", bytes.size());
        return;
    }
    try {
        std::string hex;
        for (auto b : bytes) { char tmp[4]; std::snprintf(tmp, sizeof(tmp), "%02X ", b); hex += tmp; }
        log::debug("RFCOMM send {} bytes: {}", bytes.size(), hex);
        m_writer.WriteBytes(winrt::array_view<const std::uint8_t>(bytes.data(), bytes.data() + bytes.size()));
        m_writer.StoreAsync().get();
    } catch (const winrt::hresult_error& e) {
        log::warn("RFCOMM send failed: {}", winrt::to_string(e.message()));
    }
}

void BluetoothRfcommClient::runLoop(std::uint64_t peerAddress) {
    auto backoff = kInitialBackoff;
    const auto serviceUuid = uuidFromString(crossdevice::kServiceUuid);

    while (m_running.load()) {
        try {
            log::info("Connecting to peer {:012X}...", peerAddress);

            auto device = BluetoothDevice::FromBluetoothAddressAsync(peerAddress).get();
            if (!device) {
                throw std::runtime_error("BluetoothDevice not found (is it paired?)");
            }
            std::string deviceName = to_string(device.Name());
            log::debug("Got BluetoothDevice: name='{}' connectionStatus={}",
                deviceName, (int)device.ConnectionStatus());
            if (m_onName) m_onName(deviceName);

            auto rfcommId = RfcommServiceId::FromUuid(serviceUuid);
            log::debug("Enumerating RFCOMM services for UUID...");
            auto services = device.GetRfcommServicesForIdAsync(rfcommId).get();
            log::debug("RFCOMM services found: {}", services.Services().Size());
            if (services.Services().Size() == 0) {
                throw std::runtime_error("CrossDevice RFCOMM service not advertised — is CrossDevice enabled in the Android app?");
            }
            auto service = services.Services().GetAt(0);
            log::debug("Connecting to host='{}' service='{}'",
                to_string(service.ConnectionHostName().DisplayName()),
                to_string(service.ConnectionServiceName()));

            StreamSocket socket;
            socket.ConnectAsync(service.ConnectionHostName(), service.ConnectionServiceName()).get();

            DataWriter writer{socket.OutputStream()};
            DataReader reader{socket.InputStream()};
            reader.InputStreamOptions(InputStreamOptions::Partial);

            {
                std::scoped_lock lk{m_socketMutex};
                m_socket = socket;
                m_writer = writer;
            }
            m_connected.store(true);
            if (m_onState) m_onState(true);
            log::info("RFCOMM connected to {:012X}", peerAddress);
            backoff = kInitialBackoff;

            std::vector<std::uint8_t> buf;
            buf.resize(1024);

            while (m_running.load()) {
                std::uint32_t read = reader.LoadAsync(1024).get();
                if (read == 0) {
                    throw std::runtime_error("Peer closed connection");
                }
                if (buf.size() < read) buf.resize(read);
                reader.ReadBytes(winrt::array_view<std::uint8_t>(buf.data(), buf.data() + read));
                // Hex-dump incoming packets for debugging.
                if (read <= 64) {
                    std::string hex;
                    hex.reserve(read * 3);
                    for (std::uint32_t i = 0; i < read; ++i) {
                        char tmp[4];
                        std::snprintf(tmp, sizeof(tmp), "%02X ", buf[i]);
                        hex += tmp;
                    }
                    log::debug("RFCOMM recv {} bytes: {}", read, hex);
                } else {
                    log::debug("RFCOMM recv {} bytes", read);
                }
                if (m_onPacket) m_onPacket(std::span<const std::uint8_t>(buf.data(), read));
            }
        } catch (const winrt::hresult_error& e) {
            log::warn("RFCOMM connection error: 0x{:08X} {}",
                (std::uint32_t)e.code().value, winrt::to_string(e.message()));
        } catch (const std::exception& e) {
            log::warn("RFCOMM connection error: {}", e.what());
        }

        {
            std::scoped_lock lk{m_socketMutex};
            if (m_socket) {
                try { m_socket.Close(); } catch (...) {}
                m_socket = nullptr;
                m_writer = nullptr;
            }
        }
        const bool wasConnected = m_connected.exchange(false);
        if (wasConnected && m_onState) m_onState(false);

        if (!m_running.load()) break;
        log::info("Reconnecting in {} ms...", backoff.count());
        for (int i = 0; i < 10 && m_running.load(); ++i) {
            std::this_thread::sleep_for(backoff / 10);
        }
        backoff = std::min(backoff * 2, kMaxBackoff);
    }
}

}
