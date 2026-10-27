package com.example.domain

/**
 * Real-time voice session states for Nipa AI Assistant.
 * Single reliable source of truth driving UI visuals, waveform, and controls.
 */
sealed interface AssistantSessionState {
    data object Disconnected : AssistantSessionState
    data object Connecting : AssistantSessionState
    data object Listening : AssistantSessionState
    data object Thinking : AssistantSessionState
    data object Speaking : AssistantSessionState
    data class Error(val message: String, val isRecoverable: Boolean = true) : AssistantSessionState
}

enum class NipaVoiceOption(val voiceId: String, val displayName: String, val tagline: String) {
    AOEDE("Aoede", "Aoede", "Breezy & expressive"),
    ZEPHYR("Zephyr", "Zephyr", "Bright & energetic"),
    LEDA("Leda", "Leda", "Youthful & warm"),
    KORE("Kore", "Kore", "Confident & crisp")
}

data class BrowserActionEvent(
    val actionTitle: String,
    val validatedUrl: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class AudioDiagnostics(
    val micPermissionGranted: Boolean = false,
    val isInputCapturing: Boolean = false,
    val isOutputPlaying: Boolean = false,
    val inputSampleRateHz: Int = 16_000,
    val outputSampleRateHz: Int = 24_000,
    val inputRmsLevel: Float = 0f,
    val outputRmsLevel: Float = 0f
)

data class NipaUiState(
    val sessionState: AssistantSessionState = AssistantSessionState.Disconnected,
    val selectedVoice: NipaVoiceOption = NipaVoiceOption.AOEDE,
    val audioDiagnostics: AudioDiagnostics = AudioDiagnostics(),
    val waveformBands: List<Float> = List(28) { 0.08f },
    val isSettingsOpen: Boolean = false,
    val showPermissionRationale: Boolean = false,
    val lastBrowserAction: BrowserActionEvent? = null
)
