/*
    ProPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 ProPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.nikos.propods.services

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Watches ongoing-call notifications from VoIP apps (Teams, Viber, ...) and
 * caches their action PendingIntents. AirPodsService can then call [setMuted] /
 * [hangUp] to fire the right one — the app reacts as if the user tapped the
 * button in the notification, keeping its in-app UI in sync.
 *
 * Requires the user to grant Notification access (Settings → Apps → Special
 * access → Notification access). Use [isAccessGranted] / [openAccessSettings]
 * from UI to drive the grant flow.
 */
class CallNotifListener : NotificationListenerService() {

    companion object {
        private const val TAG = "CallNotifListener"

        private val WATCHED_PACKAGES = setOf(
            "com.microsoft.teams",
            "com.microsoft.teams.ipphone",
            "com.microsoft.teams2",
            "com.viber.voip",
        )

        @Volatile private var muteAction: Notification.Action? = null
        @Volatile private var unmuteAction: Notification.Action? = null
        @Volatile private var hangUpAction: Notification.Action? = null
        @Volatile private var lastSeenKey: String? = null

        /** Canonical mute state derived from the app's notification buttons.
         *  true = app shows "Unmute" (i.e. currently muted)
         *  false = app shows "Mute" (i.e. currently unmuted)
         *  null = no active call notification seen yet */
        @Volatile var teamsMuted: Boolean? = null
            private set

        /** Called on the main thread whenever the app's in-notification mute state
         *  flips (not on first detection — only on subsequent changes). */
        @Volatile var onMuteStateChanged: ((muted: Boolean) -> Unit)? = null

        /** Returns the app's canonical mute state, or null if no call is active. */
        fun isTeamsMuted(): Boolean? = teamsMuted

        fun isAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val cn = "${context.packageName}/${CallNotifListener::class.java.name}"
            return flat.split(":").any { it.trim() == cn }
        }

        fun openAccessSettings(context: Context) {
            val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun setMuted(muted: Boolean): Boolean {
            val action = if (muted) muteAction else unmuteAction
            if (action == null) {
                Log.d(TAG, "setMuted($muted): no cached action")
                return false
            }
            return try {
                action.actionIntent.send()
                Log.d(TAG, "setMuted($muted): fired ${action.title}")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "setMuted($muted) failed: ${t.message}")
                false
            }
        }

        fun hangUp(): Boolean {
            val action = hangUpAction ?: run {
                Log.d(TAG, "hangUp(): no cached action")
                return false
            }
            return try {
                action.actionIntent.send()
                Log.d(TAG, "hangUp(): fired ${action.title}")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "hangUp() failed: ${t.message}")
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
        // Re-scan currently posted notifications so we pick up an in-progress call.
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "scan active notifications failed: ${t.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED_PACKAGES) return
        if (sbn.key == lastSeenKey) {
            Log.d(TAG, "Call notification removed; clearing cached actions")
            muteAction = null
            unmuteAction = null
            hangUpAction = null
            lastSeenKey = null
            teamsMuted = null
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED_PACKAGES) return
        val n = sbn.notification ?: return
        val actions = n.actions ?: return

        var foundMute: Notification.Action? = null
        var foundUnmute: Notification.Action? = null
        var foundHangUp: Notification.Action? = null
        for (a in actions) {
            val title = a.title?.toString().orEmpty()
            val lower = title.lowercase()
            // Order matters: "unmute" contains "mute".
            if (lower.contains("unmute") || lower.contains("réactiver") || lower.contains("activar")) {
                foundUnmute = a
            } else if (lower.contains("mute") || lower.contains("muet") || lower.contains("silenc") || lower.contains("stumm")) {
                foundMute = a
            } else if (lower.contains("hang") || lower.contains("end call") || lower.contains("colgar") ||
                lower.contains("raccrocher") || lower.contains("auflegen") || lower.contains("finalizar")) {
                foundHangUp = a
            }
        }

        if (foundMute != null || foundUnmute != null || foundHangUp != null) {
            muteAction = foundMute ?: muteAction
            unmuteAction = foundUnmute ?: unmuteAction
            hangUpAction = foundHangUp ?: hangUpAction
            lastSeenKey = sbn.key
            Log.d(
                TAG,
                "Cached actions from ${sbn.packageName}: mute=${foundMute?.title}, unmute=${foundUnmute?.title}, hangUp=${foundHangUp?.title}"
            )

            // Derive canonical mute state: "Unmute" button visible → currently muted,
            // "Mute" button visible → currently unmuted. Ambiguous if both or neither present.
            val newState: Boolean? = when {
                foundUnmute != null && foundMute == null -> true
                foundMute != null && foundUnmute == null -> false
                else -> null
            }
            if (newState != null && newState != teamsMuted) {
                val wasKnown = teamsMuted != null
                teamsMuted = newState
                if (wasKnown) {
                    Log.d(TAG, "Teams mute state changed → $newState (in-app button)")
                    onMuteStateChanged?.invoke(newState)
                } else {
                    Log.d(TAG, "Teams initial mute state detected: $newState")
                }
            }
        }
    }
}
