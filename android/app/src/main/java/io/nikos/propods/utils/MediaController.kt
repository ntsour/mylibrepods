/*
    ProPods - AirPods liberated from Apple’s ecosystem
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

@file:OptIn(ExperimentalEncodingApi::class)

package io.nikos.propods.utils

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import io.nikos.propods.services.NotificationAnnouncementService
import io.nikos.propods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

object MediaController {
    private var initialVolume: Int? = null
    private lateinit var audioManager: AudioManager
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    private var lastSelfActionAt: Long = 0L
    private const val SELF_ACTION_IGNORE_MS = 800L
    private const val PLAYBACK_DEBOUNCE_MS = 300L
    private var lastPlaybackCallbackAt: Long = 0L
    private var lastKnownIsMusicActive: Boolean? = null

    private var relativeVolume: Boolean = false
    private var conversationalAwarenessVolume: Int = 2
    private var conversationalAwarenessPauseMusic: Boolean = false

    private var lastPlayWithReplay: Boolean = false
    private var lastPlayTime: Long = 0L

    // Set to the AirPods MAC every time we call takeOver("music"). The
    // AudioDeviceCallback below fires only when an A2DP device with THIS MAC
    // becomes the audio output — no time-window guessing. Cleared on:
    //   - the expected MAC is added (fire path)
    //   - user explicitly pauses via sendPause (don't fight user intent)
    // Volatile because it's read on the audio-system thread and written on
    // the MediaSession callback thread.
    @Volatile
    private var pendingMusicTakeoverForMac: String? = null

    // Wall-clock time when pendingMusicTakeoverForMac was set. Used to age out
    // stale pending state so a route blip much later can't fire showTakeoverIsland
    // or sendPlay(force=true) against the user.
    @Volatile
    private var pendingMusicTakeoverSetAt: Long = 0L

    // When an A2DP route change lands we watch for the app auto-pausing
    // (Pocket Casts, etc.) and re-issue play immediately. Unlike a single
    // timing guess, this polls the actual playback state with arithmetic
    // backoff (200, 400, 600, ...) and reacts as soon as isMusicActive drops
    // — regardless of exactly when the app decides to pause.
    @Volatile
    private var routeLandWatchPending: Boolean = false
    // Arithmetic-backoff delays: 200, 400, 600 ... 2400 ms → ~15.6 s total.
    private val ROUTE_LAND_DELAYS_MS = longArrayOf(
        200, 400, 600, 800, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400
    )

    private class RouteLandPoller : Runnable {
        private var checkCount = 0
        private var elapsedMs = 0L

        fun reset() {
            checkCount = 0
            elapsedMs = 0L
        }

        override fun run() {
            if (!routeLandWatchPending) return
            val delay = ROUTE_LAND_DELAYS_MS.getOrElse(checkCount) { 2400 }
            elapsedMs += delay
            checkCount++
            if (elapsedMs > 15_000L) {
                Log.d("MediaController", "routeLandPoller: timed out after ${elapsedMs}ms ($checkCount checks), giving up")
                routeLandWatchPending = false
                reset()
                return
            }
            if (iPausedTheMedia) {
                Log.d("MediaController", "routeLandPoller: user paused, stopping watch")
                routeLandWatchPending = false
                reset()
                return
            }
            if (audioManager.isMusicActive) {
                // Music is currently playing, but on Xiaomi/HyperOS Pocket Casts
                // (and similar apps) auto-pause several seconds AFTER the A2DP
                // stream starts — well past any short "stable" window. Don't
                // declare victory just because music looks fine right now;
                // keep watching until the user pauses or the 15 s budget
                // elapses, so a late auto-pause can still be caught and replayed.
                Log.d("MediaController", "routeLandPoller: music active at ${elapsedMs}ms, continuing watch")
                handler.postDelayed(this, delay)
                return
            }
            // Music dropped — re-issue play (auto-pause from the late route change).
            Log.d("MediaController", "routeLandPoller: music not active at ${elapsedMs}ms (check $checkCount), re-issuing play")
            sendPlay(force = true)
            // Schedule next check with arithmetic backoff.
            handler.postDelayed(this, delay)
        }
    }
    private val routeLandPoller = RouteLandPoller()

    // A cold-connect straight from the case has to page asleep AirPods — the A2DP
    // route can legitimately take 6-12 s to land. 3 s was far too short: the route
    // landed AFTER the window, so onAudioDevicesAdded discarded it as "stale" and
    // never showed the popup or re-issued play, leaving audio stuck on the speaker.
    // 15 s covers slow cold connects; pendingMac is still cleared on pause and on
    // ownership loss, so a genuinely stale entry can't misfire much later.
    private const val PENDING_TAKEOVER_MAX_AGE_MS = 15_000L

    /** Called by AirPodsService.takeOver() right before it actually invokes connectAudio(). */
    fun armPendingMusicTakeover(mac: String) {
        if (mac.isEmpty()) return
        pendingMusicTakeoverForMac = mac
        pendingMusicTakeoverSetAt = System.currentTimeMillis()
        // Clear the "we paused this device's media" flag. By definition, if we're
        // arming a music takeover the user has just initiated playback — any prior
        // iPausedTheMedia=true (e.g. set when we yielded the AirPods to a peer for
        // a call) is stale. Without this, when the AirPods come back the
        // AudioDeviceCallback route-land branch hits the
        // "iPausedTheMedia=true; user paused intentionally, NOT replaying"
        // path and suppresses the replay, leaving the AirPods connected but silent.
        if (iPausedTheMedia) {
            Log.d("MediaController", "armPendingMusicTakeover: clearing stale iPausedTheMedia=true")
            iPausedTheMedia = false
        }
        Log.d("MediaController", "armPendingMusicTakeover($mac)")
    }

    /**
     * Reset the music-active edge-trigger state. Call this whenever the AirPods
     * leave us (peer takeover, autonomous switch, range loss) so the next
     * playback callback after a future play press is treated as a fresh
     * false→true transition instead of being suppressed by a stale `true`.
     */
    fun resetMusicActiveState() {
        if (lastKnownIsMusicActive != null) {
            Log.d("MediaController", "resetMusicActiveState (was: $lastKnownIsMusicActive)")
        }
        lastKnownIsMusicActive = null
    }

    /** Called when we lose ownership or otherwise want to drop the pending state. */
    fun cancelPendingMusicTakeover() {
        if (pendingMusicTakeoverForMac != null) {
            Log.d("MediaController", "cancelPendingMusicTakeover (was: $pendingMusicTakeoverForMac)")
        }
        pendingMusicTakeoverForMac = null
        pendingMusicTakeoverSetAt = 0L
    }

    /**
     * Restart the routeLandPoller explicitly. Used when A2DP PLAYING_STATE_CHANGED
     * → PLAYING fires, because on some devices (Xiaomi) the actual A2DP audio stream
     * starts many seconds after the device is added to the system. Pocket Casts
     * auto-pauses on the late route change, but by then the original poller started
     * from onAudioDevicesAdded has already finished.
     */
    fun restartRouteLandPoller() {
        Log.d("MediaController", "restartRouteLandPoller: resetting and starting fresh watch")
        routeLandWatchPending = false
        handler.removeCallbacks(routeLandPoller)
        routeLandPoller.reset()
        routeLandWatchPending = true
        handler.postDelayed(routeLandPoller, ROUTE_LAND_DELAYS_MS[0])
    }

    // MediaSession-based detection (covers apps hidden from AudioPlaybackCallback by audio
    // hardening, e.g. Pocket Casts with FLAG_NO_MEDIA_PROJECTION). Sessions are addressable
    // because the app already has BIND_NOTIFICATION_LISTENER_SERVICE permission.
    private var mediaSessionManager: MediaSessionManager? = null
    private val sessionCallbacks = mutableMapOf<android.media.session.MediaController, android.media.session.MediaController.Callback>()
    private val sessionLastState = mutableMapOf<android.media.session.MediaController, Int>()

    fun initialize(audioManager: AudioManager, sharedPreferences: SharedPreferences, context: Context? = null) {
        if (this::audioManager.isInitialized) {
            return
        }
        this.audioManager = audioManager
        this.sharedPreferences = sharedPreferences
        Log.d("MediaController", "Initializing MediaController")
        relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
        conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 0.4).toInt())
        conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", true)

        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "relative_conversational_awareness_volume" -> {
                    relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
                }
                "conversational_awareness_volume" -> {
                    conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4).toInt())
                }
                "conversational_awareness_pause_music" -> {
                    conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", true)
                }
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        audioManager.registerAudioPlaybackCallback(cb, null)

        // Listen for audio routing changes. When the AirPods become the actual
        // media output (not just A2DP-profile-connected, but Android has fully
        // routed media to them), check if we should re-issue a play key. This
        // covers Pocket Casts (and any well-behaved app) auto-pausing on
        // audio device change during a handover.
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                addedDevices?.forEach { dev ->
                    if (dev.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return@forEach
                    val pendingMac = pendingMusicTakeoverForMac
                    Log.d("MediaController", "AudioDevice added: type=A2DP name='${dev.productName}' addr='${dev.address}' pendingFor='$pendingMac'")
                    if (pendingMac == null) return@forEach
                    // Age-out: if the pending state is older than the max age, drop it.
                    // Protects against a route blip happening minutes after a no-op takeOver.
                    val ageMs = System.currentTimeMillis() - pendingMusicTakeoverSetAt
                    if (ageMs > PENDING_TAKEOVER_MAX_AGE_MS) {
                        Log.d("MediaController", "  → pendingMac stale (${ageMs}ms old); clearing without action")
                        cancelPendingMusicTakeover()
                        return@forEach
                    }
                    // Exact MAC match — case-insensitive because some vendors uppercase
                    // and some lowercase the address string.
                    if (!dev.address.equals(pendingMac, ignoreCase = true)) {
                        Log.d("MediaController", "  → MAC mismatch (added=${dev.address} vs pending=$pendingMac); ignoring")
                        return@forEach
                    }
                    // Consume the pending state so we don't re-fire on later route changes.
                    cancelPendingMusicTakeover()
                    if (iPausedTheMedia) {
                        Log.d("MediaController", "  → route landed but iPausedTheMedia=true; user paused intentionally, NOT replaying")
                        ServiceManager.getService()?.showTakeoverIsland()
                        return@forEach
                    }
                    // Start the route-land poller. It watches the actual playback state
                    // every 200 ms for up to 2 s. If the app auto-pauses on the route
                    // change (Pocket Casts, Spotify, etc.), the poller detects
                    // isMusicActive dropping and re-issues play immediately — no
                    // fragile single-shot timing guess.
                    Log.d("MediaController", "  → expected AirPods route landed; starting routeLandPoller")
                    routeLandWatchPending = true
                    sendPlay(force = true)
                    handler.postDelayed(routeLandPoller, ROUTE_LAND_DELAYS_MS[0])
                    // Audio route is actually live now — show the takeover island.
                    // (Previously fired immediately on takeOver() request, ~3 s
                    // before reality. This matches what the user sees/hears.)
                    ServiceManager.getService()?.showTakeoverIsland()
                }
            }
        }, handler)

        // Stamp the A2DP flux timestamp on AUDIO_BECOMING_NOISY too — not just
        // on profile state changes. NOISY fires ~300-500 ms EARLIER than the
        // A2DP profile transitions when a peer device takes the AirPods, so
        // it's the earliest local signal that "the AirPods are leaving us."
        // Without this, the takeOver-suppression gate misses the window:
        // onPlaybackConfigChanged fires (because music is still active) right
        // between NOISY and the profile state change, sees no recent A2DP
        // flux, and triggers a fight-back takeOver.
        if (context != null) {
            try {
                val noisyReceiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: android.content.Intent?) {
                        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                            io.nikos.propods.services.AirPodsService.lastA2dpStateChangeMs = System.currentTimeMillis()
                            Log.d("MediaController", "AUDIO_BECOMING_NOISY received; stamping A2DP flux for suppression gate")
                        }
                    }
                }
                context.registerReceiver(
                    noisyReceiver,
                    android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
                Log.d("MediaController", "Registered AUDIO_BECOMING_NOISY receiver")
            } catch (e: Exception) {
                Log.w("MediaController", "Failed to register NOISY receiver: ${e.message}")
            }
        }

        // Also subscribe to MediaSessionManager to catch apps that AudioPlaybackCallback misses.
        if (context != null) initMediaSessions(context)
    }

    private fun initMediaSessions(context: Context) {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (msm == null) {
                Log.w("MediaController", "MediaSessionManager not available")
                return
            }
            mediaSessionManager = msm
            val listenerComponent = ComponentName(context, NotificationAnnouncementService::class.java)

            val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                if (controllers == null) return@OnActiveSessionsChangedListener
                Log.d("MediaController", "Active media sessions changed: ${controllers.size}")
                // Stop tracking sessions that are gone.
                val current = controllers.toSet()
                val gone = sessionCallbacks.keys - current
                for (g in gone) {
                    runCatching { g.unregisterCallback(sessionCallbacks[g]!!) }
                    sessionCallbacks.remove(g)
                    sessionLastState.remove(g)
                }
                // Track new sessions.
                for (ctrl in controllers) {
                    if (sessionCallbacks.containsKey(ctrl)) continue
                    trackSession(ctrl)
                }
            }

            // Prime with currently active sessions, then subscribe to changes.
            val initial = runCatching { msm.getActiveSessions(listenerComponent) }.getOrNull().orEmpty()
            Log.d("MediaController", "Initial active media sessions: ${initial.size}")
            for (ctrl in initial) trackSession(ctrl)
            msm.addOnActiveSessionsChangedListener(onSessionsChanged, listenerComponent, handler)
        } catch (t: Throwable) {
            Log.w("MediaController", "MediaSession setup failed: ${t.message}")
        }
    }

    private fun trackSession(ctrl: android.media.session.MediaController) {
        val pkg = ctrl.packageName
        val startState = ctrl.playbackState?.state ?: PlaybackState.STATE_NONE
        sessionLastState[ctrl] = startState
        Log.d("MediaController", "Tracking media session: $pkg initialState=$startState")

        val cb = object : android.media.session.MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val newState = state?.state ?: PlaybackState.STATE_NONE
                val prev = sessionLastState[ctrl] ?: PlaybackState.STATE_NONE
                sessionLastState[ctrl] = newState
                Log.d("MediaController", "[session $pkg] state $prev → $newState")
                if (newState == PlaybackState.STATE_PLAYING && prev != PlaybackState.STATE_PLAYING) {
                    onSessionStartedPlaying(ctrl)
                }
            }

            override fun onSessionDestroyed() {
                Log.d("MediaController", "[session $pkg] destroyed")
                sessionCallbacks.remove(ctrl)
                sessionLastState.remove(ctrl)
            }
        }
        ctrl.registerCallback(cb, handler)
        sessionCallbacks[ctrl] = cb

        // If session is already PLAYING when we attach (e.g. app launched before us),
        // honor it once. Otherwise we'd miss apps that started before MediaController init.
        if (startState == PlaybackState.STATE_PLAYING) {
            handler.post { onSessionStartedPlaying(ctrl) }
        }
    }

    private fun onSessionStartedPlaying(ctrl: android.media.session.MediaController) {
        val pkg = ctrl.packageName ?: "(?)"
        val usage = ctrl.playbackInfo?.audioAttributes?.usage
        Log.d("MediaController", "[session $pkg] STATE_PLAYING usage=$usage")

        // Same gates the AudioPlaybackCallback path uses.
        if (usage != null && usage != AudioAttributes.USAGE_MEDIA) {
            Log.d("MediaController", "  ignoring: usage is not USAGE_MEDIA")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
            Log.d("MediaController", "  ignoring: within self-action window (${now - lastSelfActionAt}ms)")
            return
        }
        if (iPausedTheMedia) {
            Log.d("MediaController", "  ignoring: iPausedTheMedia=true (we paused this ourselves)")
            return
        }
        Log.d("MediaController", "  → PROCEEDING: requesting takeOver(\"music\") from MediaSession event")
        ServiceManager.getService()?.takeOver("music")
    }

    val cb = object : AudioManager.AudioPlaybackCallback() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            val now = SystemClock.uptimeMillis()
            val isActive = audioManager.isMusicActive
            Log.d("MediaController", "Playback config changed, iPausedTheMedia: $iPausedTheMedia, isActive: $isActive, lastKnownIsMusicActive: $lastKnownIsMusicActive")

            if (!isActive && lastPlayWithReplay && now - lastPlayTime < 2500L) {
                Log.d("MediaController", "Music paused shortly after play with replay; retrying play")
                lastPlayWithReplay = false
                sendPlay()
                lastKnownIsMusicActive = true
                return
            }

            if (now - lastPlaybackCallbackAt < PLAYBACK_DEBOUNCE_MS) {
                Log.d("MediaController", "Ignoring playback callback due to debounce (${now - lastPlaybackCallbackAt}ms)")
                // Don't reset the timer on debounce — otherwise rapid cascading callbacks
                // (e.g. HyperOS firing 3+ events within 50ms when YouTube starts) keep
                // resetting the window and the meaningful event never gets processed.
                return
            }
            lastPlaybackCallbackAt = now

            if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
                Log.d("MediaController", "Ignoring playback callback because it's likely caused by our own action (${now - lastSelfActionAt}ms since last self-action)")
                lastKnownIsMusicActive = isActive
                return
            }

            Log.d("MediaController", "Configs received: ${configs?.size ?: 0} configurations")
            // Inspect both usage and contentType. Many apps (Pocket Casts, etc.) leave
            // contentType=UNKNOWN but always set usage=USAGE_MEDIA for media playback.
            data class AttrPair(val usage: Int, val contentType: Int)
            val activeAttrs = configs?.mapNotNull { config ->
                Log.d("MediaController", "Processing config: ${config}, audioAttributes: ${config.audioAttributes}")
                config.audioAttributes?.let { attrs ->
                    Log.d("MediaController", "Config usage=${attrs.usage} contentType=${attrs.contentType}")
                    AttrPair(attrs.usage, attrs.contentType)
                }
            }?.toSet() ?: emptySet()

            Log.d("MediaController", "Active audio attrs: $activeAttrs")

            val hasNewMusicOrMovie = activeAttrs.any { a ->
                // Primary signal: usage=USAGE_MEDIA covers music, podcasts, movies, audiobooks.
                a.usage == android.media.AudioAttributes.USAGE_MEDIA ||
                // Fallback: explicit content type for older/odd apps.
                a.contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC ||
                a.contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE ||
                a.contentType == android.media.AudioAttributes.CONTENT_TYPE_SPEECH
            }

            Log.d("MediaController", "Has new music or movie: $hasNewMusicOrMovie")

            if (configs != null && !iPausedTheMedia) {
                val localMac = ServiceManager.getService()?.localMac
                if (!localMac.isNullOrEmpty()) {
                    ServiceManager.getService()?.aacpManager?.sendMediaInformataion(
                        localMac,
                        isActive
                    )
                }
                Log.d("MediaController", "User changed media state themselves; will wait for ear detection pause before auto-play")
                handler.postDelayed({
                    userPlayedTheMedia = audioManager.isMusicActive
                }, 7)
            }

            // A2DP flux gate: when a peer device performs an A2DP handover, our
            // own A2DP profile state briefly bounces, which causes the audio system
            // to fire spurious playback-config callbacks here. The `lastKnownIsMusicActive`
            // edge-trigger then misfires (resets to false during the disruption, then
            // sees music active again) and we'd call takeOver(), fighting the peer
            // for the connection. Skip takeOver while A2DP state is unsettled.
            val a2dpFluxAgeMs = System.currentTimeMillis() - io.nikos.propods.services.AirPodsService.lastA2dpStateChangeMs
            val a2dpInFlux = io.nikos.propods.services.AirPodsService.lastA2dpStateChangeMs > 0 &&
                a2dpFluxAgeMs < io.nikos.propods.services.AirPodsService.A2DP_STATE_FLUX_WINDOW_MS

            if (isActive && hasNewMusicOrMovie) {
                if (lastKnownIsMusicActive != true) {
                    if (a2dpInFlux) {
                        Log.d("MediaController", "Music/movie is active BUT A2DP in flux (${a2dpFluxAgeMs}ms since change) — suppressing takeOver, peer is likely taking over")
                    } else {
                        Log.d("MediaController", "Music/movie is active; requesting takeOver")
                        ServiceManager.getService()?.takeOver("music")
                    }
                }
            } else if (!isActive && hasNewMusicOrMovie) {
                // HyperOS quirk: AudioPlaybackCallback fires with media configs before
                // audioManager.isMusicActive flips to true. Re-check after 500ms.
                //
                // The `lastKnownIsMusicActive != true` guard was removed from the
                // outer condition: when the AirPods autonomously leave Xiaomi
                // (Apple auto-switch / OS reconnect) and the user later presses
                // play, `lastKnownIsMusicActive` is stale-true from the last
                // session and would suppress the re-check. We let the inner
                // `audioManager.isMusicActive` check at +500 ms be the
                // source of truth.
                Log.d("MediaController", "Media config seen but isMusicActive=false; scheduling delayed re-check")
                handler.postDelayed({
                    if (audioManager.isMusicActive) {
                        val fluxAgeMsNow = System.currentTimeMillis() - io.nikos.propods.services.AirPodsService.lastA2dpStateChangeMs
                        val inFluxNow = io.nikos.propods.services.AirPodsService.lastA2dpStateChangeMs > 0 &&
                            fluxAgeMsNow < io.nikos.propods.services.AirPodsService.A2DP_STATE_FLUX_WINDOW_MS
                        if (inFluxNow) {
                            Log.d("MediaController", "Delayed re-check: music active BUT A2DP in flux (${fluxAgeMsNow}ms) — suppressing takeOver")
                        } else {
                            Log.d("MediaController", "Delayed re-check: music now active, requesting takeOver")
                            ServiceManager.getService()?.takeOver("music")
                            lastKnownIsMusicActive = true
                        }
                    } else {
                        Log.d("MediaController", "Delayed re-check: still not music-active; isMusicActive=${audioManager.isMusicActive}")
                    }
                }, 500)
            }

            lastKnownIsMusicActive = hasNewMusicOrMovie && isActive
        }
    }

    @Synchronized
    fun getMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    @Synchronized
    fun sendPlayPause() {
        val wasActive = audioManager.isMusicActive
        Log.d("MediaController", "Sending play/pause toggle (wasActive=$wasActive)")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
        // If music was active, this press paused it → remember so auto-resume on ear-in works.
        // If music was not active, this press is a play → clear the pause flag.
        iPausedTheMedia = wasActive
        if (wasActive) userPlayedTheMedia = false
    }

    @Synchronized
    fun sendPreviousTrack() {
        Log.d("MediaController", "Sending previous track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendNextTrack() {
        Log.d("MediaController", "Sending next track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendPause(force: Boolean = false) {
        Log.d("MediaController", "sendPause called: iPausedTheMedia=$iPausedTheMedia, userPlayedTheMedia=$userPlayedTheMedia, isMusicActive=${audioManager.isMusicActive}, force=$force")
        // Cancel any pending music-takeover replay — if we're pausing, the user
        // doesn't want auto-resume when a future A2DP route lands.
        cancelPendingMusicTakeover()
        routeLandWatchPending = false
        if ((audioManager.isMusicActive) && (!userPlayedTheMedia || force)) {
            Log.d("MediaController", "  → DISPATCHING PAUSE KEY EVENT")
            iPausedTheMedia = if (force) audioManager.isMusicActive else true
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        } else {
            Log.d("MediaController", "  → skipped (not music active or user is controlling playback)")
        }
    }

    @Synchronized
    fun sendPlay(replayWhenPaused: Boolean = false, force: Boolean = false) {
        Log.d("MediaController", "Sending play with iPausedTheMedia: $iPausedTheMedia, replayWhenPaused: $replayWhenPaused, force: $force")
        if (replayWhenPaused) {
            lastPlayWithReplay = true
            lastPlayTime = SystemClock.uptimeMillis()
        }
        if (iPausedTheMedia || force) { // very creative, ik. thanks.
            Log.d("MediaController", "Sending play and setting userPlayedTheMedia to false")
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
        if (!audioManager.isMusicActive) {
            Log.d("MediaController", "Setting iPausedTheMedia to false")
            iPausedTheMedia = false
        }
    }

    @Synchronized
    fun startSpeaking() {
        Log.d("MediaController", "Starting speaking max vol: ${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, current vol: ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}, conversationalAwarenessVolume: $conversationalAwarenessVolume, relativeVolume: $relativeVolume")

        if (initialVolume == null) {
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("MediaController", "Initial Volume: $initialVolume")
            val targetVolume = if (relativeVolume) {
                (initialVolume!! * conversationalAwarenessVolume / 100)
            } else if (initialVolume!! > (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)) {
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)
            } else {
                initialVolume!!
            }
            smoothVolumeTransition(initialVolume!!, targetVolume)
            if (conversationalAwarenessPauseMusic) {
                sendPause(force = true)
            }
        }
        Log.d("MediaController", "Initial Volume: $initialVolume")
    }

    @Synchronized
    fun stopSpeaking() {
        Log.d("MediaController", "Stopping speaking, initialVolume: $initialVolume")
        if (initialVolume != null) {
            smoothVolumeTransition(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), initialVolume!!)
            if (conversationalAwarenessPauseMusic) {
                sendPlay()
            }
            initialVolume = null
        }
    }

    private fun smoothVolumeTransition(fromVolume: Int, toVolume: Int) {
        Log.d("MediaController", "Smooth volume transition from $fromVolume to $toVolume")
        val step = if (fromVolume < toVolume) 1 else -1
        val delay = 50L
        var currentVolume = fromVolume

        handler.post(object : Runnable {
            override fun run() {
                if (currentVolume != toVolume) {
                    currentVolume += step
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}
