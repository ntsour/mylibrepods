#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <span>
#include <string>
#include <thread>
#include <vector>

#include <winrt/Windows.Devices.Bluetooth.Rfcomm.h>
#include <winrt/Windows.Networking.Sockets.h>
#include <winrt/Windows.Storage.Streams.h>

namespace librepods {

class BluetoothRfcommClient {
public:
    using PacketCallback = std::function<void(std::span<const std::uint8_t>)>;
    using StateCallback  = std::function<void(bool connected)>;
    // Fired once per successful connection with the BT device's display name.
    using NameCallback   = std::function<void(const std::string& name)>;

    BluetoothRfcommClient();
    ~BluetoothRfcommClient();

    BluetoothRfcommClient(const BluetoothRfcommClient&) = delete;
    BluetoothRfcommClient& operator=(const BluetoothRfcommClient&) = delete;

    void setOnPacket(PacketCallback cb) { m_onPacket = std::move(cb); }
    void setOnState(StateCallback  cb) { m_onState  = std::move(cb); }
    void setOnName (NameCallback   cb) { m_onName   = std::move(cb); }

    void start(std::uint64_t peerAddress);
    void stop();

    bool isConnected() const { return m_connected.load(); }

    void sendPacket(std::span<const std::uint8_t> bytes);

private:
    void runLoop(std::uint64_t peerAddress);

    std::atomic<bool> m_running{false};
    std::atomic<bool> m_connected{false};
    std::thread m_worker;

    std::mutex m_socketMutex;
    winrt::Windows::Networking::Sockets::StreamSocket m_socket{nullptr};
    winrt::Windows::Storage::Streams::DataWriter m_writer{nullptr};

    PacketCallback m_onPacket;
    StateCallback  m_onState;
    NameCallback   m_onName;
};

}
