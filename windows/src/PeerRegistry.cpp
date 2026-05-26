#include "PeerRegistry.hpp"

#include "Logger.hpp"
#include "SettingsStore.hpp"

namespace librepods {

void PeerRegistry::setOnPacket(PacketCallback cb) {
    std::scoped_lock lk{m_mutex};
    m_onPacket = std::move(cb);
}

void PeerRegistry::setOnState(StateCallback cb) {
    std::scoped_lock lk{m_mutex};
    m_onState = std::move(cb);
}

void PeerRegistry::wireCallbacks(Entry& e) {
    std::uint64_t addr = e.address;  // capture address by value; Entry may be moved later

    e.client->setOnPacket([this](std::span<const std::uint8_t> data) {
        PacketCallback cb;
        { std::scoped_lock lk{m_mutex}; cb = m_onPacket; }
        if (cb) cb(data);
    });
    e.client->setOnState([this](bool connected) {
        StateCallback cb;
        { std::scoped_lock lk{m_mutex}; cb = m_onState; }
        if (cb) cb(connected);
    });
    // Store the device display name the first time we successfully connect.
    e.client->setOnName([this, addr](const std::string& name) {
        std::scoped_lock lk{m_mutex};
        for (auto& entry : m_entries)
            if (entry.address == addr) { entry.name = name; break; }
    });
}

void PeerRegistry::addPeer(std::uint64_t address) {
    bool shouldStart = false;
    {
        std::scoped_lock lk{m_mutex};
        for (auto& e : m_entries) {
            if (e.address == address) {
                log::warn("PeerRegistry: peer {} already registered — skipping",
                    SettingsStore::formatBluetoothAddress(address));
                return;
            }
        }
        auto& e = m_entries.emplace_back(
            Entry{address, /*name=*/{}, std::make_unique<BluetoothRfcommClient>()});
        wireCallbacks(e);
        shouldStart = m_started;
    }
    if (shouldStart) {
        // Find the entry again outside the lock to call start() without holding it.
        // (BluetoothRfcommClient::start launches a thread; holding m_mutex during
        // thread launch risks lock-order inversion with the client's internal mutex.)
        BluetoothRfcommClient* clientPtr = nullptr;
        {
            std::scoped_lock lk{m_mutex};
            for (auto& e : m_entries) {
                if (e.address == address) { clientPtr = e.client.get(); break; }
            }
        }
        if (clientPtr) {
            clientPtr->start(address);
            log::info("PeerRegistry: added and started peer {}",
                SettingsStore::formatBluetoothAddress(address));
        }
    } else {
        log::info("PeerRegistry: registered peer {} (not yet started)",
            SettingsStore::formatBluetoothAddress(address));
    }
}

void PeerRegistry::start() {
    // Collect (address, client*) pairs without holding the lock during start().
    std::vector<std::pair<std::uint64_t, BluetoothRfcommClient*>> toStart;
    {
        std::scoped_lock lk{m_mutex};
        if (m_started) return;
        m_started = true;
        for (auto& e : m_entries)
            toStart.emplace_back(e.address, e.client.get());
    }
    for (auto& [addr, client] : toStart)
        client->start(addr);
    log::info("PeerRegistry: started {} peer(s)", toStart.size());
}

void PeerRegistry::stop() {
    // Move entries out of the registry under the lock so isAnyConnected /
    // sendPacket see an empty list immediately. Then stop the clients outside
    // the lock to avoid deadlock: BluetoothRfcommClient::stop() joins the worker
    // thread, whose state-callback tries to acquire m_mutex.
    std::vector<Entry> toStop;
    {
        std::scoped_lock lk{m_mutex};
        m_started = false;
        toStop = std::move(m_entries);
        m_entries.clear();
    }
    for (auto& e : toStop)
        e.client->stop();
    log::info("PeerRegistry: stopped {} peer(s)", toStop.size());
}

bool PeerRegistry::isAnyConnected() const {
    std::scoped_lock lk{m_mutex};
    for (auto& e : m_entries)
        if (e.client->isConnected()) return true;
    return false;
}

void PeerRegistry::sendPacket(std::span<const std::uint8_t> bytes) {
    std::scoped_lock lk{m_mutex};
    for (auto& e : m_entries)
        if (e.client->isConnected())
            e.client->sendPacket(bytes);
}

std::vector<std::uint64_t> PeerRegistry::addresses() const {
    std::scoped_lock lk{m_mutex};
    std::vector<std::uint64_t> result;
    result.reserve(m_entries.size());
    for (auto& e : m_entries) result.push_back(e.address);
    return result;
}

std::vector<PeerRegistry::PeerInfo> PeerRegistry::peerInfos() const {
    std::scoped_lock lk{m_mutex};
    std::vector<PeerInfo> result;
    result.reserve(m_entries.size());
    for (auto& e : m_entries) {
        PeerInfo info;
        info.address   = e.address;
        info.connected = e.client->isConnected();
        if (!e.name.empty()) {
            // Device names are ASCII-safe; widen char-by-char.
            info.name.assign(e.name.begin(), e.name.end());
        } else {
            // Fallback: formatted BT address until first successful connection.
            auto s = SettingsStore::formatBluetoothAddress(e.address);
            info.name.assign(s.begin(), s.end());
        }
        result.push_back(std::move(info));
    }
    return result;
}

} // namespace librepods
