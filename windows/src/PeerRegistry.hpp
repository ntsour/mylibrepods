#pragma once

#include "BluetoothRfcommClient.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <vector>

namespace librepods {

// Manages a set of BluetoothRfcommClient connections — one per paired Android peer.
// Presents the same logical interface as a single BluetoothRfcommClient:
//   - sendPacket()     → broadcast to every currently-connected peer
//   - isAnyConnected() → true if at least one peer is connected
//   - callbacks        → fired for each packet/state-change from any peer
//
// Thread-safe: addPeer / start / stop / sendPacket may be called from any thread.
class PeerRegistry {
public:
    using PacketCallback = std::function<void(std::span<const std::uint8_t>)>;
    using StateCallback  = std::function<void(bool connected)>;

    // Snapshot of a single peer's status, used for UI display.
    struct PeerInfo {
        std::uint64_t address{};
        std::wstring  name;       // device display name, or formatted address if not yet known
        bool          connected{false};
    };

    void setOnPacket(PacketCallback cb);
    void setOnState(StateCallback  cb);

    // Register a peer address. If start() has already been called the connection
    // loop is launched immediately; otherwise it starts on the next start() call.
    // Duplicate addresses are silently ignored.
    void addPeer(std::uint64_t address);

    // Launch connection loops for all registered peers.
    void start();

    // Stop all connection loops and clear the peer list.
    void stop();

    // True if at least one peer is currently connected.
    bool isAnyConnected() const;

    // Send a packet to every currently-connected peer.
    void sendPacket(std::span<const std::uint8_t> bytes);

    // Returns a snapshot of registered peer addresses.
    std::vector<std::uint64_t> addresses() const;

    // Returns a snapshot of all peers with their current connection status and name.
    std::vector<PeerInfo> peerInfos() const;

private:
    struct Entry {
        std::uint64_t address{};
        std::string   name;   // last known BT display name (ASCII-safe for device names)
        std::unique_ptr<BluetoothRfcommClient> client;
    };

    void wireCallbacks(Entry& e);

    mutable std::mutex m_mutex;
    std::vector<Entry> m_entries;
    PacketCallback m_onPacket;
    StateCallback  m_onState;
    bool m_started{false};
};

} // namespace librepods
