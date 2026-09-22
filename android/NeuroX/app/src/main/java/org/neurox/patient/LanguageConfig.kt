package org.neurox.patient

// ──────────────────────────────────────────────
// Language capability configuration
// ──────────────────────────────────────────────

/**
 * Describes the speech and TTS capabilities of a single language in NeuroX.
 *
 * This is the single source of truth for language-aware UI decisions:
 * - If [speechSupported] is false, the voice listening screen shows a
 *   clear fallback message instead of a microphone button.
 * - If [ttsSupported] is false, spoken activity instructions use the
 *   [ttsFallbackNote] language rather than the patient's preferred language.
 *
 * @param languageCode  BCP-47 language tag (e.g. "as-IN", "en-IN").
 * @param languageName  Human-readable name shown in the UI.
 * @param speechSupported  True if the active SpeechProvider can recognise
 *                         speech in this language.
 * @param ttsSupported     True if Android TTS can speak in this language
 *                         without requiring an additional voice pack download.
 * @param ttsFallbackNote  Shown when [ttsSupported] is false; explains the
 *                         fallback behaviour in plain language.
 */
data class LanguageConfig(
    val languageCode: String,
    val languageName: String,
    val speechSupported: Boolean,
    val ttsSupported: Boolean,
    val ttsFallbackNote: String? = null,
    /**
     * True if the server-side BHASHINI integration supports this language.
     */
    val bhashinSupported: Boolean = false,
    /**
     * Explains that enhanced recognition requires connectivity.
     */
    val bhashinNote: String? = null
)

// ──────────────────────────────────────────────
// Static language registry
// ──────────────────────────────────────────────

/**
 * The languages currently recognised by NeuroX and their capability
 * declarations. This list mirrors the `/language-config` backend endpoint
 * and is kept here as a local fallback so the app works offline.
 *
 * Add a new entry here when a new language is introduced. Never remove
 * an entry without a corresponding backend change.
 */
val SUPPORTED_LANGUAGES: List<LanguageConfig> = listOf(
    LanguageConfig(
        languageCode = "en-IN",
        languageName = "English",
        speechSupported = true,
        ttsSupported = true,
        bhashinSupported = false   // BHASHINI not needed; Android on-device handles English well
    ),
    LanguageConfig(
        languageCode = "as-IN",
        languageName = "Assamese",
        speechSupported = true,          // Supported via server-side BHASHINI or Android speech
        ttsSupported = false,            // Android TTS does not ship an Assamese voice by default
        ttsFallbackNote = "Voice guides will use English until an Assamese voice pack is installed.",
        bhashinSupported = true,         // BHASHINI Ulca natively supports Assamese ASR
        bhashinNote = "Enhanced Assamese speech recognition is available via BHASHINI when connected."
    ),
    LanguageConfig(
        languageCode = "hi-IN",
        languageName = "Hindi",
        speechSupported = true,
        ttsSupported = true,
        bhashinSupported = true,         // BHASHINI also supports Hindi
        bhashinNote = "Enhanced Hindi speech recognition is available via BHASHINI."
    )
)

/**
 * Returns the [LanguageConfig] for [languageCode], or the English config
 * as a safe fallback when the code is not in the registry.
 */
fun languageConfigFor(languageCode: String): LanguageConfig =
    SUPPORTED_LANGUAGES.find { it.languageCode == languageCode }
        ?: SUPPORTED_LANGUAGES.first { it.languageCode == "en-IN" }

/**
 * Maps a patient's `preferredLanguage` string from the API (e.g. "Assamese")
 * to a BCP-47 language code used by SpeechRecognizer and TTS.
 */
fun languageNameToCode(preferredLanguage: String): String = when (preferredLanguage.lowercase().trim()) {
    "assamese", "অসমীয়া" -> "as-IN"
    "hindi", "हिंदी"      -> "hi-IN"
    else                   -> "en-IN"
}
