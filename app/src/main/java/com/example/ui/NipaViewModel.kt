package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.LiveSessionManager
import com.example.audio.AudioPlayerQueue
import com.example.audio.AudioStreamer
import com.example.domain.AssistantSessionState
import com.example.domain.AudioDiagnostics
import com.example.domain.NipaUiState
import com.example.domain.NipaVoiceOption
import com.example.tools.ToolManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class NipaViewModel(application: Application) : AndroidViewModel(application) {

    private val audioStreamer = AudioStreamer()
    private val audioPlayerQueue = AudioPlayerQueue()
    private val toolManager = ToolManager(
        appContext = application.applicationContext,
        onBrowserActionExecuted = { actionEvent ->
            _uiState.update { it.copy(lastBrowserAction = actionEvent) }
        }
    )

    private val liveSessionManager = LiveSessionManager(
        appContext = application.applicationContext,
        audioStreamer = audioStreamer,
        audioPlayerQueue = audioPlayerQueue,
        toolManager = toolManager
    )

    private val _uiState = MutableStateFlow(
        NipaUiState(
            audioDiagnostics = AudioDiagnostics(
                micPermissionGranted = checkMicPermission()
            )
        )
    )
    val uiState: StateFlow<NipaUiState> = _uiState.asStateFlow()

    init {
        observeTelemetry()
        startWaveformAnimator()
    }

    private fun observeTelemetry() {
        viewModelScope.launch {
            combine(
                liveSessionManager.sessionState,
                audioStreamer.isCapturing,
                audioStreamer.inputLevel,
                audioPlayerQueue.isPlaying,
                audioPlayerQueue.outputLevel
            ) { sessionState, isCapturing, inputRms, isPlaying, outputRms ->
                Triple(
                    sessionState,
                    AudioDiagnostics(
                        micPermissionGranted = checkMicPermission(),
                        isInputCapturing = isCapturing,
                        isOutputPlaying = isPlaying,
                        inputRmsLevel = inputRms,
                        outputRmsLevel = outputRms
                    ),
                    Unit
                )
            }.collect { (sessionState, diagnostics, _) ->
                _uiState.update { prev ->
                    prev.copy(
                        sessionState = sessionState,
                        audioDiagnostics = diagnostics
                    )
                }
            }
        }
    }

    private fun startWaveformAnimator() {
        viewModelScope.launch {
            var phase = 0f
            while (isActive) {
                phase += 0.24f
                val current = _uiState.value
                val state = current.sessionState
                val inLevel = current.audioDiagnostics.inputRmsLevel
                val outLevel = current.audioDiagnostics.outputRmsLevel
                val count = current.waveformBands.size

                val nextBands = List(count) { idx ->
                    val centerRatio = 1f - (abs(idx - (count - 1) / 2f) / (count / 2f)).coerceIn(0f, 1f)
                    val envelope = 0.25f + 0.75f * centerRatio

                    val target = when (state) {
                        AssistantSessionState.Disconnected -> {
                            0.07f + 0.03f * sin(phase * 0.4f + idx * 0.35f) * envelope
                        }
                        AssistantSessionState.Connecting -> {
                            0.14f + 0.12f * ((sin(phase * 1.3f - idx * 0.45f) + 1f) * 0.5f) * envelope
                        }
                        AssistantSessionState.Listening -> {
                            val liveEnergy = (inLevel * 1.35f).coerceIn(0.06f, 1f)
                            val harmonic = (sin(phase * 1.9f + idx * 0.6f) * 0.5f + 0.5f)
                            (0.08f + liveEnergy * (0.35f + 0.65f * harmonic) * envelope).coerceIn(0.06f, 1f)
                        }
                        AssistantSessionState.Thinking -> {
                            val ripple = (sin(phase * 1.6f - idx * 0.38f) + 1f) * 0.5f
                            (0.12f + 0.28f * ripple * envelope).coerceIn(0.08f, 0.55f)
                        }
                        AssistantSessionState.Speaking -> {
                            val voiceEnergy = outLevel.coerceAtLeast(0.28f)
                            val dualWave = (sin(phase * 2.4f + idx * 0.52f) * 0.6f +
                                sin(phase * 1.1f - idx * 0.8f + PI.toFloat() / 3f) * 0.4f) * 0.5f + 0.5f
                            (0.12f + voiceEnergy * (0.3f + 0.75f * dualWave) * envelope).coerceIn(0.10f, 1f)
                        }
                        is AssistantSessionState.Error -> {
                            0.08f
                        }
                    }

                    val previous = current.waveformBands[idx]
                    (previous * 0.52f + target * 0.48f).coerceIn(0.05f, 1f)
                }

                _uiState.update { it.copy(waveformBands = nextBands) }
                delay(33L) // ~30fps smooth GPU-light updates
            }
        }
    }

    fun refreshPermissionState() {
        val granted = checkMicPermission()
        _uiState.update { state ->
            state.copy(
                audioDiagnostics = state.audioDiagnostics.copy(micPermissionGranted = granted),
                showPermissionRationale = if (granted) false else state.showPermissionRationale
            )
        }
    }

    fun onMicButtonTapped(onRequestMicPermission: () -> Unit) {
        val hasPerm = checkMicPermission()
        refreshPermissionState()

        if (!hasPerm) {
            onRequestMicPermission()
            return
        }

        when (_uiState.value.sessionState) {
            AssistantSessionState.Disconnected,
            is AssistantSessionState.Error -> {
                connectSession()
            }
            AssistantSessionState.Connecting,
            AssistantSessionState.Listening,
            AssistantSessionState.Thinking,
            AssistantSessionState.Speaking -> {
                disconnectSession()
            }
        }
    }

    fun onMicPermissionResult(isGranted: Boolean) {
        refreshPermissionState()
        if (isGranted) {
            _uiState.update { it.copy(showPermissionRationale = false) }
            connectSession()
        } else {
            _uiState.update {
                it.copy(
                    showPermissionRationale = true,
                    sessionState = AssistantSessionState.Error(
                        message = "Microphone permission is needed so Nipa can hear you."
                    )
                )
            }
        }
    }

    fun connectSession() {
        if (!checkMicPermission()) {
            _uiState.update { it.copy(showPermissionRationale = true) }
            return
        }
        val selectedVoice = _uiState.value.selectedVoice
        viewModelScope.launch {
            liveSessionManager.connect(selectedVoice)
        }
    }

    fun disconnectSession() {
        viewModelScope.launch {
            liveSessionManager.disconnect()
        }
    }

    fun selectVoice(voice: NipaVoiceOption) {
        if (_uiState.value.selectedVoice == voice) return
        _uiState.update { it.copy(selectedVoice = voice) }

        // Reconnect seamlessly with the new voice if currently active
        val active = _uiState.value.sessionState
        if (active == AssistantSessionState.Listening ||
            active == AssistantSessionState.Speaking ||
            active == AssistantSessionState.Thinking
        ) {
            viewModelScope.launch {
                liveSessionManager.connect(voice)
            }
        }
    }

    fun setSettingsOpen(open: Boolean) {
        _uiState.update { it.copy(isSettingsOpen = open) }
        if (open) {
            refreshPermissionState()
        }
    }

    fun clearBrowserActionBanner() {
        _uiState.update { it.copy(lastBrowserAction = null) }
    }

    private fun checkMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCleared() {
        super.onCleared()
        liveSessionManager.shutdown()
    }
}
