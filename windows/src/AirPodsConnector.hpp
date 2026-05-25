#pragma once

#include <cstdint>
#include <mutex>

namespace librepods {

class AirPodsConnector {
public:
    explicit AirPodsConnector(std::uint64_t address);
    AirPodsConnector(const AirPodsConnector&) = delete;
    AirPodsConnector& operator=(const AirPodsConnector&) = delete;

    void setAddress(std::uint64_t address) { m_address = address; }
    std::uint64_t address() const { return m_address; }

    bool connect();
    bool disconnect();

    // Query Win32 Bluetooth API for the device's actual classic-Bluetooth
    // connection state (true = ACL link up to this PC right now).
    bool isClassicallyConnected();

    // True when at least one non-system audio session is in the Active state on
    // the AirPods render endpoint (i.e. a call, meeting, or other app is actively
    // using the AirPods for audio output right now).
    bool hasActiveAudioSessions();

    // True when at least one active audio session on the AirPods render endpoint
    // is owned by a known real-time communications application (Teams, Zoom,
    // Discord, Slack, WebEx). Used as the gate for rejecting peer-initiated
    // kRequestDisconnect packets: we only protect *calls* from being snatched
    // away mid-conversation, not passive media (YouTube, Spotify) that the user
    // is happy to lose if they're moving the AirPods to another device.
    bool hasActiveCallSessions();

    // Make the AirPods the default audio output (render) and input (capture) device
    // at all roles (Console/Multimedia/Communications).
    //
    // Event-driven: registers a process-wide IMMNotificationClient on first call
    // and arms it for 120s. When the audio system promotes the AirPods endpoint
    // to DEVICE_STATE_ACTIVE (typically a few seconds after the BT classical
    // link is up and A2DP has negotiated), the callback applies the routing
    // and broadcasts WM_WININICHANGE so apps switch over.
    //
    // Returns immediately (no blocking poll). Returns true if either routing
    // applied synchronously (endpoint already ACTIVE) or the notifier is armed
    // for a future apply.
    bool setAsDefaultAudioDevice();

    // Toggle the endpoint notifier's "persistent" mode. When true, the notifier
    // stays armed indefinitely and re-arms after each successful routing — so
    // if AirPods bounce off Windows mid-call (Apple auto-switch, brief blip)
    // and come back, routing reapplies automatically without another
    // setAsDefaultAudioDevice() call. Set this to true while we own the AirPods
    // (OwnershipState::LocalPc) and false when ownership leaves us.
    void setPersistentArm(bool persistent);

private:
    std::uint64_t m_address;
    std::mutex m_mutex;  // serialize connect/disconnect to avoid parallel races
};

}
