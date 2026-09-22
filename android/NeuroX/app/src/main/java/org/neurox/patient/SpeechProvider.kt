package org.neurox.patient

import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.io.ByteArrayOutputStream
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ──────────────────────────────────────────────
// Speech provider abstraction
// ──────────────────────────────────────────────

/**
 * A minimal, technology-agnostic speech-to-text interface.
 * Every provider must expose whether it can handle the current language
 * before starting a listening session, so the UI can show a clear
 * fallback message rather than silently failing.
 */
data class RecognitionResult(val transcript: String, val confidence: Float? = null)

interface SpeechProvider {
    /** True if this provider can recognise speech for [languageCode]. */
    fun isSupported(languageCode: String): Boolean

    /**
     * Begin listening. Calls [onResult] with the top hypothesis once
     * recognition ends, or [onError] with a human-readable message.
     * Must be called on the main thread.
     */
    fun startListening(languageCode: String, onResult: (RecognitionResult) -> Unit, onError: (String) -> Unit)

    /** Cancel an in-progress listening session without reporting a result. */
    fun stopListening()
}

/** Spoken guidance is intentionally independent from recognition and UI. */
interface TtsProvider {
    fun isSupported(languageCode: String): Boolean
    fun speak(text: String, languageCode: String)
    fun stop()
    fun close()
}

class AndroidTtsProvider(context: Context) : TtsProvider {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { ready = it == TextToSpeech.SUCCESS }
    override fun isSupported(languageCode: String): Boolean = ready &&
        tts.isLanguageAvailable(Locale.forLanguageTag(languageCode)) >= TextToSpeech.LANG_AVAILABLE
    override fun speak(text: String, languageCode: String) {
        if (isSupported(languageCode)) {
            tts.language = Locale.forLanguageTag(languageCode)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neurox-guide")
        }
    }
    override fun stop() { tts.stop() }
    override fun close() { tts.stop(); tts.shutdown() }
}

// ──────────────────────────────────────────────
// Mock provider (demo / tests — no hardware needed)
// ──────────────────────────────────────────────

/**
 * Returns a deterministic sequence of transcripts so the hackathon
 * demo always works offline and without real microphone input.
 *
 * Cycling through the phrases lets a demonstrator trigger each intent
 * by tapping the mic button in sequence.
 */
class MockSpeechProvider : SpeechProvider {
    private val phrases = listOf(
        "start memory match",
        "show my reminders",
        "I need help",
        "start object recall",
        "start pattern activity"
    )
    private var index = 0

    override fun isSupported(languageCode: String): Boolean = true

    override fun startListening(languageCode: String, onResult: (RecognitionResult) -> Unit, onError: (String) -> Unit) {
        // Simulate a short recognition delay, then return the next mock phrase.
        val result = phrases[index % phrases.size]
        index++
        // The caller supplies a coroutine scope; we call back synchronously here
        // because MockSpeechProvider is used only in controlled demo/test contexts.
        onResult(RecognitionResult(result, confidence = 0.99f))
    }

    override fun stopListening() { /* no-op */ }
}

// ──────────────────────────────────────────────
// Android on-device provider (SpeechRecognizer)
// ──────────────────────────────────────────────

/**
 * Uses Android's built-in [SpeechRecognizer] (Google on-device ASR).
 * Requires RECORD_AUDIO permission and Google Play Services.
 * Falls back gracefully when the device does not have a recogniser
 * available — [isSupported] will return false in that case.
 */
class AndroidSpeechProvider(private val context: Context) : SpeechProvider {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    override fun isSupported(languageCode: String): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)
        else SpeechRecognizer.isRecognitionAvailable(context)

    override fun startListening(languageCode: String, onResult: (RecognitionResult) -> Unit, onError: (String) -> Unit) {
        stopListening() // ensure no stale session

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this device.")
            return
        }

        val sr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                if (recognizer !== sr) return
                clearTimeout()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val top = matches?.firstOrNull()
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    ?.firstOrNull()?.takeIf { it >= 0f }
                if (top != null) onResult(RecognitionResult(top, confidence))
                else onError("Could not understand. Please try again.")
            }

            override fun onError(error: Int) {
                if (recognizer !== sr) return
                clearTimeout()
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand. Please try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Please tap the microphone and speak."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone error. Please check your device microphone."
                    SpeechRecognizer.ERROR_NETWORK -> "Offline speech recognition is unavailable. Install the language pack or reconnect to use BHASHINI."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech recognition is not available for this language."
                    else -> "Speech recognition failed. Please try again."
                }
                onError(message)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        }
        sr.startListening(intent)
        timeout = Runnable {
            stopListening()
            onError("Listening timed out. Please tap the microphone and try again.")
        }.also { handler.postDelayed(it, 15_000L) }
    }

    override fun stopListening() {
        clearTimeout()
        val previous = recognizer
        recognizer = null
        previous?.cancel()
        previous?.destroy()
    }

    private fun clearTimeout() { timeout?.let(handler::removeCallbacks); timeout = null }
}

// ──────────────────────────────────────────────
// Whisper provider stub (future remote ASR)
// ──────────────────────────────────────────────

/**
 * Stub for a future Whisper-compatible remote endpoint.
 * Always reports itself as unsupported until [baseUrl] is configured,
 * so the UI will show the correct fallback message rather than crashing.
 */
class WhisperSpeechProvider(private val baseUrl: String? = null) : SpeechProvider {
    override fun isSupported(languageCode: String): Boolean = false

    override fun startListening(languageCode: String, onResult: (RecognitionResult) -> Unit, onError: (String) -> Unit) {
        onError("Whisper speech provider is not configured. Set a base URL to enable it.")
    }

    override fun stopListening() { /* no-op */ }
}

// ──────────────────────────────────────────────
// Secure remote BHASHINI provider
// ──────────────────────────────────────────────

/**
 * Captures a short PCM clip, wraps it as WAV, and sends it to the authenticated
 * NeuroX API. BHASHINI credentials never leave the server. The server checks
 * patient consent and discards both audio and transcript after the response.
 */
class RemoteSpeechProvider(
    private val transcribe: suspend (String, String) -> SpeechTranscriptionResponse,
) : SpeechProvider {
    private val supportedLanguageCodes = setOf(
        "as-IN", "brx-IN", "mni-IN", "hi-IN", "bn-IN", "gu-IN", "kn-IN",
        "ml-IN", "mr-IN", "or-IN", "pa-IN", "ta-IN", "te-IN", "ur-IN",
    )
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    override fun isSupported(languageCode: String): Boolean = languageCode in supportedLanguageCodes

    @SuppressLint("MissingPermission")
    override fun startListening(
        languageCode: String,
        onResult: (RecognitionResult) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isSupported(languageCode)) {
            onError("Online speech recognition is not available for this language.")
            return
        }
        stopListening()
        val sampleRate = 16_000
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            onError("Microphone recording is unavailable on this device.")
            return
        }
        val activeRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimum * 2,
        )
        if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
            activeRecorder.release()
            onError("Microphone recording could not be started.")
            return
        }
        recorder = activeRecorder
        captureJob = scope.launch {
            try {
                activeRecorder.startRecording()
                val pcm = ByteArrayOutputStream()
                val buffer = ShortArray(minimum / 2)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                var speechStarted = false
                var lastSpeechAt = startedAt
                while (isActive && android.os.SystemClock.elapsedRealtime() - startedAt < 12_000L) {
                    val count = activeRecorder.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    var peak = 0
                    repeat(count) { peak = maxOf(peak, kotlin.math.abs(buffer[it].toInt())) }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (peak >= 700) {
                        speechStarted = true
                        lastSpeechAt = now
                    }
                    repeat(count) {
                        val sample = buffer[it].toInt()
                        pcm.write(sample and 0xff)
                        pcm.write((sample shr 8) and 0xff)
                    }
                    if (speechStarted && now - lastSpeechAt >= 1_200L) break
                    if (!speechStarted && now - startedAt >= 6_000L) break
                }
                activeRecorder.stop()
                recorder = null
                activeRecorder.release()
                if (!speechStarted) {
                    withContext(Dispatchers.Main) { onError("No speech detected. Please tap the microphone and speak.") }
                    return@launch
                }
                val wav = wav(pcm.toByteArray(), sampleRate)
                val response = transcribe(Base64.encodeToString(wav, Base64.NO_WRAP), languageCode)
                withContext(Dispatchers.Main) {
                    onResult(RecognitionResult(response.transcript, confidence = null))
                }
            } catch (e: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        val detail = e.message.orEmpty()
                        onError(
                            when {
                                detail.contains("consent", ignoreCase = true) -> "Enable voice recognition in Privacy & sharing, then try again."
                                else -> "Online speech recognition is unavailable. Check your connection and try again."
                            }
                        )
                    }
                }
            } finally {
                if (recorder === activeRecorder) recorder = null
                try { activeRecorder.release() } catch (_: Exception) {}
            }
        }
    }

    override fun stopListening() {
        captureJob?.cancel()
        captureJob = null
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        recorder = null
    }

    private fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val output = ByteArrayOutputStream(44 + pcm.size)
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun little(value: Int, bytes: Int) = repeat(bytes) { output.write((value shr (8 * it)) and 0xff) }
        ascii("RIFF"); little(36 + pcm.size, 4); ascii("WAVEfmt ")
        little(16, 4); little(1, 2); little(1, 2); little(sampleRate, 4)
        little(sampleRate * 2, 4); little(2, 2); little(16, 2)
        ascii("data"); little(pcm.size, 4); output.write(pcm)
        return output.toByteArray()
    }
}

// ──────────────────────────────────────────────
// Provider factory
// ──────────────────────────────────────────────

/**
 * Returns the best available [SpeechProvider] for the current context.
 *
 * Priority:
 * 1. [MockSpeechProvider] when [demoMode] is true — deterministic, no hardware.
 * 2. [RemoteSpeechProvider] for supported Indian regional languages. It sends
 *    audio to the authenticated NeuroX API; provider credentials stay server-side.
 * 3. [AndroidSpeechProvider] when the device has a recognition service — good
 *    for English and Hindi with Google services.
 * 4. [WhisperSpeechProvider] stub — always unavailable until a base URL is set.
 *
 * BHASHINI is placed above Android on-device recognition because it provides
 * far better accuracy for Assamese and other low-resource Indian languages.
 *
 * Production always supplies [remoteTranscriber] and keeps [demoMode] false.
 */
fun buildSpeechProvider(
    context: Context,
    demoMode: Boolean = true,
    remoteTranscriber: (suspend (String, String) -> SpeechTranscriptionResponse)? = null,
): SpeechProvider = when {
    demoMode -> MockSpeechProvider()
    remoteTranscriber != null -> RoutedSpeechProvider(
        remote = RemoteSpeechProvider(remoteTranscriber),
        android = AndroidSpeechProvider(context),
    )
    SpeechRecognizer.isRecognitionAvailable(context) -> AndroidSpeechProvider(context)
    else -> WhisperSpeechProvider()
}

/** Prefer server-side BHASHINI for regional languages and Android for English. */
class RoutedSpeechProvider(
    private val remote: RemoteSpeechProvider,
    private val android: AndroidSpeechProvider,
) : SpeechProvider {
    private var active: SpeechProvider? = null
    override fun isSupported(languageCode: String): Boolean =
        remote.isSupported(languageCode) || android.isSupported(languageCode)
    override fun startListening(
        languageCode: String,
        onResult: (RecognitionResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        active = if (remote.isSupported(languageCode)) remote else android
        active?.startListening(languageCode, onResult, onError)
    }
    override fun stopListening() {
        active?.stopListening()
        active = null
    }
}
