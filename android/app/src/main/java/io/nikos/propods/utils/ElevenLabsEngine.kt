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
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin ElevenLabs TTS client that speaks text through a MediaPlayer.
 *
 * Flow:
 *   1. POST text → ElevenLabs Flash v2.5 model (lowest latency, highest quality).
 *   2. Write returned mp3 bytes to a temp file in cacheDir.
 *   3. Request AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, play via MediaPlayer.
 *   4. On completion: abandon focus, delete temp file, call [onDone].
 *   5. On any error: call [onFallback] so the caller can re-try with system TTS.
 *
 * All network I/O runs on a background thread. Playback callbacks arrive on the
 * MediaPlayer's internal thread — callers must not block them.
 */
object ElevenLabsEngine {

    private const val TAG = "ElevenLabsEngine"
    private const val BASE_URL = "https://api.elevenlabs.io/v1"
    private const val MODEL_ID = "eleven_flash_v2_5"

    /** ElevenLabs "Rachel" — clear, neutral American English. Good default. */
    const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"

    // Single-thread executor: all speak() calls run sequentially, never overlapping.
    // A fresh thread per call was the root cause of simultaneous playback.
    private val executor = Executors.newSingleThreadExecutor()

    // Generation counter: incremented on every stop(). Each queued task checks it
    // after the network fetch and aborts if a newer task has superseded it.
    private val generation = AtomicInteger(0)

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var focusRequest: AudioFocusRequest? = null
    private val speaking = AtomicBoolean(false)
    @Volatile private var playbackLatch: java.util.concurrent.CountDownLatch? = null
    // Timestamp of the last onCompletion event (-1 = none/cleared). Used to keep
    // isSpeaking() true during A2DP buffer drain after MediaPlayer reports done.
    private val lastDoneAt = java.util.concurrent.atomic.AtomicLong(-1L)
    private const val A2DP_GRACE_MS = 1_500L

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enqueue [text] for sequential playback via ElevenLabs.
     *
     * Tasks run one at a time on a dedicated thread — no simultaneous audio.
     * Each task checks [generation] after the network fetch; if [stop] was
     * called in the meantime, the task silently drops the result.
     *
     * @param onDone     called when playback finishes normally.
     * @param onFallback called on API error — caller should speak via system TTS.
     */
    fun speak(
        context: Context,
        text: String,
        apiKey: String,
        voiceId: String = DEFAULT_VOICE_ID,
        languageCode: String? = null,
        onDone: () -> Unit = {},
        onFallback: (reason: String) -> Unit = { Log.w(TAG, "Fallback: $it") },
    ) {
        val ctx = context.applicationContext
        val myGen = generation.get()          // snapshot at enqueue time
        speaking.set(true)                    // set immediately so isSpeaking() is accurate
        executor.submit {
            // Abort early if stop() was called after this task was enqueued.
            if (generation.get() != myGen) {
                Log.d(TAG, "Task superseded, skipping \"${text.take(40)}\"")
                speaking.set(false)
                return@submit
            }
            if (!AnnouncementAudioRoute.canAnnounceToAirPods(ctx)) {
                Log.d(TAG, "Skipping ElevenLabs announcement — AirPods are not the selected media route")
                speaking.set(false)
                return@submit
            }
            if (!hasValidatedInternet(ctx)) {
                Log.d(TAG, "No validated internet connection; falling back to system TTS")
                speaking.set(false)
                onFallback("No internet connection")
                return@submit
            }
            try {
                Log.d(TAG, "Fetching audio (gen=$myGen): \"${text.take(60)}\"")
                val bytes = fetchAudio(text, apiKey, voiceId, languageCode)
                // Re-check: stop() may have been called during the network fetch.
                if (generation.get() != myGen) {
                    Log.d(TAG, "Superseded after fetch, discarding audio")
                    speaking.set(false)
                    return@submit
                }
                if (!AnnouncementAudioRoute.canAnnounceToAirPods(ctx)) {
                    Log.d(TAG, "Route changed after fetch, discarding ElevenLabs audio")
                    speaking.set(false)
                    return@submit
                }
                if (bytes == null || bytes.isEmpty()) {
                    speaking.set(false)
                    onFallback("Empty response from ElevenLabs")
                    return@submit
                }
                val tmp = File.createTempFile("el_tts_", ".mp3", ctx.cacheDir)
                tmp.writeBytes(bytes)
                // Stop any lingering player from a previous utterance before starting.
                stopPlayer()
                // Block this executor thread until playback finishes so the next
                // queued utterance doesn't start while this one is still playing.
                val latch = java.util.concurrent.CountDownLatch(1)
                playbackLatch = latch
                playFile(ctx, tmp,
                    onDone = { lastDoneAt.set(System.currentTimeMillis()); latch.countDown(); speaking.set(false); onDone() },
                    onError = { reason ->
                        latch.countDown()
                        speaking.set(false)
                        try { tmp.delete() } catch (_: Exception) {}
                        onFallback(reason)
                    }
                )
                latch.await()   // wait for MediaPlayer onCompletion/onError
                playbackLatch = null
            } catch (e: Exception) {
                Log.e(TAG, "speak() failed: ${e.message}")
                speaking.set(false)
                onFallback(e.message ?: "Unknown error")
            }
        }
    }

    /** Stop current playback and cancel any pending queued utterances. */
    fun stop() {
        generation.incrementAndGet()   // invalidates all queued tasks
        speaking.set(false)
        lastDoneAt.set(-1L)            // clear A2DP grace so next press is play/pause
        playbackLatch?.countDown()     // unblock executor thread if stuck on latch.await()
        stopPlayer()
    }

    /** Returns true if an utterance is currently speaking, fetching, or within the
     *  A2DP drain grace window after completion (MediaPlayer.onCompletion fires
     *  before audio reaches AirPods, so we stay "speaking" for a short window). */
    fun isSpeaking(): Boolean {
        if (speaking.get()) return true
        val t = lastDoneAt.get()
        return t >= 0L && System.currentTimeMillis() - t < A2DP_GRACE_MS
    }

    private fun stopPlayer() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun hasValidatedInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Fetch the list of available voices for the given API key.
     * Returns list of (voiceId, name) pairs sorted by name.
     * Runs on the calling thread — call from a background thread.
     */
    fun fetchVoices(apiKey: String): List<Pair<String, String>> {
        return try {
            val conn = (URL("$BASE_URL/voices").openConnection() as HttpURLConnection).apply {
                setRequestProperty("xi-api-key", apiKey)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "fetchVoices: HTTP ${conn.responseCode}")
                return emptyList()
            }
            val json = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parseVoices(json)
        } catch (e: Exception) {
            Log.w(TAG, "fetchVoices failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Quick connectivity / key check. Returns null on success, error message on failure.
     * Runs on the calling thread — call from a background thread.
     */
    fun testKey(apiKey: String): String? {
        return try {
            val conn = (URL("$BASE_URL/user").openConnection() as HttpURLConnection).apply {
                setRequestProperty("xi-api-key", apiKey)
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            when (conn.responseCode) {
                200 -> null
                401 -> "Invalid API key"
                else -> "HTTP ${conn.responseCode}"
            }
        } catch (e: Exception) {
            e.message ?: "Network error"
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun fetchAudio(
        text: String,
        apiKey: String,
        voiceId: String,
        languageCode: String?
    ): ByteArray? {
        val safeText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")
        val languageJson = languageCode
            ?.takeIf { it.isNotBlank() }
            ?.let { ""","language_code":"${it.replace("\"", "")}"""" }
            ?: ""
        val body = """{"text":"$safeText","model_id":"$MODEL_ID"$languageJson,"voice_settings":{"stability":0.5,"similarity_boost":0.75}}"""

        val conn = (URL("$BASE_URL/text-to-speech/$voiceId").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Accept", "audio/mpeg")
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            Log.w(TAG, "fetchAudio HTTP $code: $err")
            return null
        }
        return conn.inputStream.use { it.readBytes() }
    }

    private fun playFile(
        context: Context,
        file: File,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestFocus(am)

        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener {
                abandonFocus(am)
                file.delete()
                player = null
                Log.d(TAG, "Playback complete")
                onDone()
            }
            setOnErrorListener { _, what, extra ->
                abandonFocus(am)
                try { file.delete() } catch (_: Exception) {}
                player = null
                onError("MediaPlayer error what=$what extra=$extra")
                true
            }
        }
        player = mp
        mp.start()
        Log.d(TAG, "Playing ${file.length()} bytes from ${file.name}")
    }

    private fun requestFocus(am: AudioManager) {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener {}
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus(am: AudioManager) {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * Minimal JSON parser for the /voices response.
     * Avoids adding a JSON library dependency.
     * Extracts (voice_id, name) pairs in declaration order.
     */
    private fun parseVoices(json: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        // Iterate over voice objects: find voice_id then nearest name.
        val idRegex = Regex(""""voice_id"\s*:\s*"([^"]+)"""")
        val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
        val ids = idRegex.findAll(json).map { it.groupValues[1] to it.range.first }.toList()
        val names = nameRegex.findAll(json).map { it.groupValues[1] to it.range.first }.toList()
        for ((id, idPos) in ids) {
            // Pick the name field that appears closest after this voice_id.
            val name = names
                .filter { (_, namePos) -> namePos > idPos }
                .minByOrNull { (_, namePos) -> namePos - idPos }
                ?.first ?: continue
            result += id to name
        }
        return result.sortedBy { it.second.lowercase() }
    }
}
