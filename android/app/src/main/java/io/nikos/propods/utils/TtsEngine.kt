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

package io.nikos.propods.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lazily-initialised TTS singleton with built-in audio focus management.
 *
 * Use [speak] to enqueue text. The engine requests transient ducking focus
 * before utterance start, and abandons it after the utterance completes.
 *
 * The engine is released after [IDLE_RELEASE_MS] of no activity to avoid
 * holding resources indefinitely. Reinit happens automatically on next use.
 */
object TtsEngine {

    private const val TAG = "TtsEngine"
    private const val IDLE_RELEASE_MS = 5 * 60 * 1000L  // 5 minutes
    private const val DEDUPE_WINDOW_MS = 3_000L   // suppress identical text within 3s (double-delivery guard only)
    private const val MAX_QUEUE = 5              // drop new announcements above this
    private const val DEDUPE_HISTORY_CAP = 20    // ring buffer size

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var configuredLanguage: String? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val initialised = AtomicBoolean(false)
    private val initialising = AtomicBoolean(false)
    private val pendingUtterances = mutableListOf<String>()
    private val lastUseAt = AtomicLong(0L)
    private val activeUtteranceCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val recentTexts = ArrayDeque<Pair<String, Long>>()  // (text, timestamp)
    // Timestamp of the last utterance-done event (-1 = none/cleared). Keeps
    // isSpeaking() true during A2DP buffer drain after onDone fires.
    private val lastDoneAt = AtomicLong(-1L)
    private const val A2DP_GRACE_MS = 1_500L

    /**
     * Speak [text] through the AirPods. Lazy-inits the engine on first call.
     * Thread-safe.
     *
     * Skips silently if no Bluetooth A2DP device is among the active audio
     * outputs — i.e. AirPods aren't connected (in case, out of range, or the
     * user has switched to phone speaker). This avoids announcing through
     * the phone speaker, which would defeat the purpose.
     */
    fun speak(context: Context, text: String, languageTag: String? = null) {
        val ctx = context.applicationContext
        ensureAudioManager(ctx)
        if (!AnnouncementAudioRoute.canAnnounceToAirPods(ctx)) {
            Log.d(TAG, "Skipping announcement — AirPods are not the selected media route")
            return
        }
        // Hold a CPU wake lock so the engine actually finishes speaking when the
        // screen is off. Released in onUtteranceDone(). 10s timeout as safety
        // net in case onDone never fires.
        acquireWakeLock(ctx)

        val now = System.currentTimeMillis()
        synchronized(recentTexts) {
            // Drop entries older than the dedupe window so the deque stays small.
            while (recentTexts.isNotEmpty() && now - recentTexts.first().second > DEDUPE_WINDOW_MS) {
                recentTexts.removeFirst()
            }
            if (recentTexts.any { it.first == text }) {
                Log.w(TAG, "DEDUPE: \"$text\" within ${DEDUPE_WINDOW_MS}ms — skipping")
                return
            }
            // Queue cap: drop overflowing announcements rather than backing up.
            // The TTS engine itself serialises utterances via QUEUE_ADD, so
            // back-to-back messages naturally play in order — no throttle needed.
            val pending = activeUtteranceCount.get() + pendingUtterances.size
            if (pending >= MAX_QUEUE) {
                Log.d(TAG, "Queue cap reached ($pending/$MAX_QUEUE), skipping")
                return
            }
            recentTexts.addLast(text to now)
            while (recentTexts.size > DEDUPE_HISTORY_CAP) recentTexts.removeFirst()
        }
        lastUseAt.set(now)

        // Reconfigure language if the user changed the preference since
        // the engine was initialised.
        val desiredLang = languageTag ?: AnnouncementPrefs.resolvedLanguage(ctx)
        if (initialised.get()) {
            if (desiredLang != configuredLanguage) {
                applyLanguage(desiredLang)
            }
            enqueue(text)
            return
        }
        synchronized(this) {
            if (initialised.get()) {
                enqueue(text)
                return
            }
            pendingUtterances.add(text)
            if (initialising.compareAndSet(false, true)) {
                Log.d(TAG, "Initialising TextToSpeech engine")
                tts = TextToSpeech(ctx) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        configureEngine(ctx)
                        initialised.set(true)
                        initialising.set(false)
                        synchronized(this) {
                            pendingUtterances.forEach { enqueue(it) }
                            pendingUtterances.clear()
                        }
                    } else {
                        Log.w(TAG, "TTS init failed: $status")
                        initialising.set(false)
                        synchronized(this) { pendingUtterances.clear() }
                    }
                }
            }
        }
        scheduleIdleRelease(ctx)
    }

    /** Returns true if an utterance is currently speaking, queued, or within the
     *  A2DP drain grace window after completion (Android TTS onDone fires before
     *  audio reaches AirPods, so we stay "speaking" for a short window). */
    fun isSpeaking(): Boolean {
        if (activeUtteranceCount.get() > 0) return true
        val t = lastDoneAt.get()
        return t >= 0L && System.currentTimeMillis() - t < A2DP_GRACE_MS
    }

    /**
     * Cancel any in-progress and queued utterances, abandon focus.
     * Used when a stem-press should silence the announcement immediately.
     */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop() failed: ${e.message}")
        }
        abandonFocus()
        activeUtteranceCount.set(0)
        lastDoneAt.set(-1L)  // clear A2DP grace so next press is play/pause
    }

    private fun configureEngine(ctx: Context) {
        val engine = tts ?: return
        applyLanguage(AnnouncementPrefs.resolvedLanguage(ctx))
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                requestFocus()
            }
            override fun onDone(utteranceId: String?) {
                onUtteranceDone()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onUtteranceDone()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS utterance error $utteranceId: $errorCode")
                onUtteranceDone()
            }
        })
    }

    private fun onUtteranceDone() {
        if (activeUtteranceCount.decrementAndGet() <= 0) {
            activeUtteranceCount.set(0)
            lastDoneAt.set(System.currentTimeMillis())
            abandonFocus()
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock(ctx: Context) {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProPods:TtsEngine")
            wl.setReferenceCounted(false)
            wl.acquire(15_000L)  // safety upper bound; usually released in onDone
            wakeLock = wl
            Log.d(TAG, "Wake lock acquired for TTS")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock: ${e.message}")
        }
        wakeLock = null
    }

    private fun enqueue(text: String) {
        val engine = tts ?: return
        val id = UUID.randomUUID().toString()
        activeUtteranceCount.incrementAndGet()
        val params = Bundle()
        engine.speak(text, TextToSpeech.QUEUE_ADD, params, id)
    }

    private fun applyLanguage(languageTag: String) {
        val engine = tts ?: return
        val preferred = Locale.forLanguageTag(languageTag).takeIf { it.language.isNotEmpty() }
            ?: Locale(languageTag)
        val result = engine.setLanguage(preferred)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Locale $preferred unsupported; falling back to English")
            engine.setLanguage(Locale.ENGLISH)
            configuredLanguage = "en"
        } else {
            Log.d(TAG, "TTS language set to $preferred (tag=$languageTag)")
            configuredLanguage = languageTag
        }
    }

    private fun ensureAudioManager(ctx: Context) {
        if (audioManager == null) {
            audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        if (focusRequest != null) return  // already holding
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { /* no-op; TTS handles its own lifecycle */ }
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let {
            am.abandonAudioFocusRequest(it)
            focusRequest = null
        }
    }

    private val idleReleaseHandler =
        android.os.Handler(android.os.Looper.getMainLooper())

    private fun scheduleIdleRelease(ctx: Context) {
        idleReleaseHandler.removeCallbacksAndMessages(null)
        idleReleaseHandler.postDelayed({
            val now = System.currentTimeMillis()
            if (now - lastUseAt.get() >= IDLE_RELEASE_MS && activeUtteranceCount.get() == 0) {
                Log.d(TAG, "Releasing TTS engine after $IDLE_RELEASE_MS ms idle")
                try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
                tts = null
                initialised.set(false)
                abandonFocus()
            }
        }, IDLE_RELEASE_MS)
    }
}
