package com.example.ai

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.audio.AudioPlayerQueue
import com.example.audio.AudioStreamer
import com.example.domain.AssistantSessionState
import com.example.domain.NipaVoiceOption
import com.example.tools.ToolManager
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerMessage
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated LiveSessionManager orchestrating:
 * - Gemini Live connection (`gemini-3.1-flash-live-preview` via Firebase AI Logic)
 * - Real-time PCM16 16kHz microphone audio input streaming
 * - Real-time PCM16 24kHz audio output decoding & queue playback
 * - Natural user barge-in / interruption handling (both server VAD `interrupted` & client speech onset)
 * - Function calling (`openWebsite`, `searchWeb`) with immediate `FunctionResponsePart` dispatch
 * - Non-cancellable connection lifecycle teardown, `LiveServerGoAway` rotation, and bounded safe reconnection
 */
@OptIn(PublicPreviewAPI::class)
class LiveSessionManager(
    private val appContext: Context,
    val audioStreamer: AudioStreamer,
    val audioPlayerQueue: AudioPlayerQueue,
    private val toolManager: ToolManager
) {

    companion object {
        private const val TAG = "LiveSessionManager"
        const val LIVE_MODEL_NAME = "gemini-3.1-flash-live-preview"
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val MAX_AUTO_RECONNECT_ATTEMPTS = 2
        private val RECONNECT_BACKOFF_MS = longArrayOf(750L, 1_750L)
    }

    private val sessionMutex = Mutex()
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionGeneration = AtomicLong(0L)
    private val intentionalDisconnect = AtomicBoolean(false)

    private var sessionScope: CoroutineScope? = null
    private var receiveJob: Job? = null
    private var playbackObserverJob: Job? = null
    private var thinkingIndicatorJob: Job? = null
    private var reconnectJob: Job? = null
    private var liveSession: LiveSession? = null

    @Volatile
    private var activeVoiceOption: NipaVoiceOption = NipaVoiceOption.AOEDE

    @Volatile
    private var resumptionHandle: String? = null

    @Volatile
    private var consecutiveAutoReconnects: Int = 0

    @Volatile
    private var hasUserSpokenInTurn = false

    private val _sessionState = MutableStateFlow<AssistantSessionState>(AssistantSessionState.Disconnected)
    val sessionState: StateFlow<AssistantSessionState> = _sessionState.asStateFlow()

    suspend fun connect(voiceOption: NipaVoiceOption) {
        reconnectJob?.cancel()
        reconnectJob = null
        intentionalDisconnect.set(false)
        if (activeVoiceOption != voiceOption) {
            resumptionHandle = null
        }
        activeVoiceOption = voiceOption
        consecutiveAutoReconnects = 0
        establishSessionInternal(voiceOption = voiceOption, isAutoReconnect = false)
    }

    suspend fun disconnect() {
        intentionalDisconnect.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        resumptionHandle = null
        consecutiveAutoReconnects = 0
        sessionMutex.withLock {
            val nextGen = sessionGeneration.incrementAndGet()
            Log.d(TAG, "Disconnecting Gemini Live session (gen=$nextGen)")
            teardownResourcesNonCancellable()
            _sessionState.value = AssistantSessionState.Disconnected
        }
    }

    /**
     * Immediate lifecycle shutdown called from ViewModel.onCleared() so no WebSocket,
     * AudioRecord, AudioTrack, or communication audio mode outlives the ViewModel.
     */
    fun shutdown() {
        intentionalDisconnect.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        resumptionHandle = null
        sessionGeneration.incrementAndGet()

        thinkingIndicatorJob?.cancel()
        thinkingIndicatorJob = null
        receiveJob?.cancel()
        receiveJob = null
        playbackObserverJob?.cancel()
        playbackObserverJob = null

        audioStreamer.stopStreaming()
        audioPlayerQueue.release()

        val active = liveSession
        liveSession = null
        sessionScope?.cancel()
        sessionScope = null

        if (active != null) {
            supervisorScope.launch {
                try {
                    active.stopReceiving()
                } catch (_: Exception) {
                }
                try {
                    withTimeoutOrNull(1_500L) {
                        active.close()
                    }
                } catch (_: Exception) {
                } finally {
                    supervisorScope.cancel()
                }
            }
        } else {
            supervisorScope.cancel()
        }
        configureSpeakerphoneRouting(enable = false)
        _sessionState.value = AssistantSessionState.Disconnected
    }

    private suspend fun establishSessionInternal(
        voiceOption: NipaVoiceOption,
        isAutoReconnect: Boolean
    ) {
        sessionMutex.withLock {
            if (intentionalDisconnect.get()) {
                _sessionState.value = AssistantSessionState.Disconnected
                return
            }

            val currentGen = sessionGeneration.incrementAndGet()
            teardownResourcesNonCancellable()
            _sessionState.value = AssistantSessionState.Connecting

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            sessionScope = scope

            try {
                configureSpeakerphoneRouting(enable = true)
                audioPlayerQueue.initialize()

                val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                    modelName = LIVE_MODEL_NAME,
                    generationConfig = liveGenerationConfig {
                        responseModality = ResponseModality.AUDIO
                        speechConfig = SpeechConfig(voice = Voice(voiceOption.voiceId))
                    },
                    tools = toolManager.getTools(),
                    systemInstruction = NipaPersona.buildSystemInstructionContent()
                )

                val savedHandle = resumptionHandle
                Log.d(
                    TAG,
                    "Connecting Gemini Live session gen=$currentGen model=$LIVE_MODEL_NAME " +
                        "voice=${voiceOption.voiceId} autoReconnect=$isAutoReconnect hasResumeHandle=${savedHandle != null}"
                )

                val connectedSession = withTimeout(CONNECT_TIMEOUT_MS) {
                    if (!savedHandle.isNullOrBlank()) {
                        try {
                            liveModel.connect(
                                sessionResumption = SessionResumptionConfig(handle = savedHandle)
                            )
                        } catch (resumeErr: Exception) {
                            if (resumeErr is CancellationException) throw resumeErr
                            val resumeReport = LiveErrorDiagnostics.inspect(resumeErr)
                            Log.w(
                                TAG,
                                resumeReport.toDebugLogLine("resume_fallback", LIVE_MODEL_NAME)
                            )
                            resumptionHandle = null
                            liveModel.connect()
                        }
                    } else {
                        liveModel.connect()
                    }
                }

                if (intentionalDisconnect.get() || sessionGeneration.get() != currentGen) {
                    withContext(NonCancellable) {
                        runCatching { connectedSession.close() }
                    }
                    return
                }

                liveSession = connectedSession
                startObservingPlaybackState(scope, currentGen)
                startReceivingServerEvents(scope, connectedSession, currentGen)

                audioStreamer.startStreaming(
                    onAudioChunk = { pcm16Bytes ->
                        sendAudioChunkSafely(connectedSession, pcm16Bytes, currentGen)
                    },
                    onSpeechDetected = {
                        if (sessionGeneration.get() == currentGen) {
                            handleLocalUserSpeechDetected()
                        }
                    },
                    onError = { micError ->
                        Log.e(TAG, "AudioStreamer error (gen=$currentGen): $micError")
                        supervisorScope.launch {
                            handleFatalSessionFailure(micError, currentGen)
                        }
                    }
                )

                Log.i(TAG, "Gemini Live session established (gen=$currentGen, model=$LIVE_MODEL_NAME)")
                _sessionState.value = AssistantSessionState.Listening
            } catch (e: TimeoutCancellationException) {
                Log.e(
                    TAG,
                    "GeminiLive[connect_timeout] code=TIMEOUT_45S model=$LIVE_MODEL_NAME " +
                        "autoReconnect=$isAutoReconnect"
                )
                teardownResourcesNonCancellable()
                _sessionState.value = AssistantSessionState.Error(
                    message = "Connection timed out while reaching Nipa. Tap the microphone to try again."
                )
            } catch (e: CancellationException) {
                teardownResourcesNonCancellable()
                throw e
            } catch (e: Exception) {
                val report = LiveErrorDiagnostics.inspect(e)
                Log.e(TAG, report.toDebugLogLine("connect_handshake", LIVE_MODEL_NAME))
                teardownResourcesNonCancellable()
                _sessionState.value = AssistantSessionState.Error(
                    message = report.userFacingMessage
                )
            }
        }
    }

    private fun startObservingPlaybackState(scope: CoroutineScope, generation: Long) {
        playbackObserverJob = scope.launch {
            audioPlayerQueue.isPlaying.collect { playing ->
                if (sessionGeneration.get() != generation) return@collect
                val current = _sessionState.value
                if (playing) {
                    thinkingIndicatorJob?.cancel()
                    if (current == AssistantSessionState.Listening || current == AssistantSessionState.Thinking) {
                        _sessionState.value = AssistantSessionState.Speaking
                    }
                } else {
                    if (current == AssistantSessionState.Speaking) {
                        _sessionState.value = AssistantSessionState.Listening
                    }
                }
            }
        }
    }

    private fun startReceivingServerEvents(
        scope: CoroutineScope,
        session: LiveSession,
        generation: Long
    ) {
        receiveJob = scope.launch {
            try {
                session.receive().collect { message ->
                    if (sessionGeneration.get() != generation) return@collect
                    handleServerMessage(session, message, generation)
                }

                // Stream completed without throwing: check if unexpected mid-session drop
                if (sessionGeneration.get() == generation && !intentionalDisconnect.get()) {
                    val wsClose = LiveErrorDiagnostics.extractWebSocketCloseReason(session)
                    Log.w(
                        TAG,
                        "GeminiLive[stream_closed] model=$LIVE_MODEL_NAME gen=$generation " +
                            "wsClose=${wsClose ?: "none_reported"}"
                    )
                    val retryableClose = wsClose == null ||
                        (!wsClose.contains("1007") && !wsClose.contains("1008"))
                    if (retryableClose && scheduleSafeReconnectIfEligible(generation, "stream_eof")) {
                        return@launch
                    }

                    sessionMutex.withLock {
                        if (sessionGeneration.get() == generation) {
                            teardownResourcesNonCancellable()
                            val detail = if (!wsClose.isNullOrBlank()) {
                                "Connection closed by server ($wsClose). Tap the microphone to reconnect."
                            } else {
                                "Connection lost. Tap the microphone to reconnect."
                            }
                            _sessionState.value = AssistantSessionState.Error(message = detail)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionGeneration.get() != generation || intentionalDisconnect.get()) {
                    return@launch
                }
                val report = LiveErrorDiagnostics.inspect(e, session)
                Log.e(TAG, report.toDebugLogLine("receive_stream", LIVE_MODEL_NAME))

                if (report.isRetryable && scheduleSafeReconnectIfEligible(generation, report.errorCode)) {
                    return@launch
                }

                sessionMutex.withLock {
                    if (sessionGeneration.get() == generation) {
                        teardownResourcesNonCancellable()
                        _sessionState.value = AssistantSessionState.Error(
                            message = report.userFacingMessage
                        )
                    }
                }
            }
        }
    }

    private suspend fun handleServerMessage(
        session: LiveSession,
        message: LiveServerMessage,
        generation: Long
    ) {
        // Any valid server frame proves the session is healthy; reset reconnect counter
        consecutiveAutoReconnects = 0

        when (message) {
            is LiveServerContent -> {
                if (message.interrupted) {
                    Log.d(TAG, "Server signaled interruption — flushing playback queue")
                    thinkingIndicatorJob?.cancel()
                    audioPlayerQueue.interruptAndClear()
                    _sessionState.value = AssistantSessionState.Listening
                    return
                }

                val parts = message.content?.parts.orEmpty()
                var enqueuedAnyAudio = false
                for (part in parts) {
                    if (part is InlineDataPart && part.mimeType.startsWith("audio/pcm")) {
                        val bytes = part.inlineData
                        if (bytes.isNotEmpty()) {
                            enqueuedAnyAudio = true
                            audioPlayerQueue.enqueuePcmChunk(bytes)
                        }
                    }
                }

                if (enqueuedAnyAudio) {
                    thinkingIndicatorJob?.cancel()
                    hasUserSpokenInTurn = false
                    _sessionState.value = AssistantSessionState.Speaking
                } else if (message.turnComplete) {
                    thinkingIndicatorJob?.cancel()
                    if (!audioPlayerQueue.isPlaying.value &&
                        _sessionState.value != AssistantSessionState.Disconnected
                    ) {
                        _sessionState.value = AssistantSessionState.Listening
                    }
                }
            }

            is LiveServerToolCall -> {
                val calls = message.functionCalls
                if (calls.isNotEmpty()) {
                    _sessionState.value = AssistantSessionState.Thinking
                    val responses = calls.map { call ->
                        toolManager.executeToolCall(call)
                    }
                    try {
                        session.sendFunctionResponse(responses)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val report = LiveErrorDiagnostics.inspect(e, session)
                        Log.e(TAG, report.toDebugLogLine("send_tool_response", LIVE_MODEL_NAME))
                    }
                }
            }

            is LiveSessionResumptionUpdate -> {
                if (message.resumable == true && !message.newHandle.isNullOrBlank()) {
                    resumptionHandle = message.newHandle
                    Log.d(TAG, "Updated Gemini Live session resumption handle (resumable=true)")
                }
            }

            is LiveServerGoAway -> {
                Log.i(
                    TAG,
                    "Received LiveServerGoAway notice (timeLeft=${message.timeLeft}); scheduling seamless session rotation"
                )
                scheduleSafeReconnectIfEligible(generation, "server_go_away", immediate = true)
            }

            else -> Unit
        }
    }

    private fun scheduleSafeReconnectIfEligible(
        failedGeneration: Long,
        reason: String,
        immediate: Boolean = false
    ): Boolean {
        if (intentionalDisconnect.get() || sessionGeneration.get() != failedGeneration) {
            return false
        }
        val attemptIndex = consecutiveAutoReconnects
        if (attemptIndex >= MAX_AUTO_RECONNECT_ATTEMPTS) {
            Log.w(
                TAG,
                "Auto-reconnect budget exhausted ($MAX_AUTO_RECONNECT_ATTEMPTS attempts) for reason=$reason"
            )
            return false
        }
        consecutiveAutoReconnects = attemptIndex + 1
        val delayMs = if (immediate) 250L else RECONNECT_BACKOFF_MS[attemptIndex.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)]
        val targetVoice = activeVoiceOption

        Log.i(
            TAG,
            "Scheduling safe Gemini Live reconnect attempt ${consecutiveAutoReconnects}/$MAX_AUTO_RECONNECT_ATTEMPTS " +
                "in ${delayMs}ms (reason=$reason)"
        )

        reconnectJob?.cancel()
        reconnectJob = supervisorScope.launch {
            _sessionState.value = AssistantSessionState.Connecting
            delay(delayMs)
            if (!intentionalDisconnect.get()) {
                establishSessionInternal(voiceOption = targetVoice, isAutoReconnect = true)
            }
        }
        return true
    }

    private suspend fun sendAudioChunkSafely(
        session: LiveSession,
        pcm16Bytes: ByteArray,
        generation: Long
    ) {
        if (sessionGeneration.get() != generation || intentionalDisconnect.get() || session.isClosed()) {
            return
        }
        try {
            session.sendAudioRealtime(
                InlineData(pcm16Bytes, "audio/pcm;rate=16000")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val sanitized = LiveErrorDiagnostics.redactSecrets(e.message)
            Log.w(TAG, "Audio chunk send warning (gen=$generation): $sanitized")
        }
    }

    private fun handleLocalUserSpeechDetected() {
        hasUserSpokenInTurn = true
        val current = _sessionState.value

        // Natural interruption: user speaks while Nipa is speaking -> flush assistant playback immediately
        if (current == AssistantSessionState.Speaking || audioPlayerQueue.isPlaying.value) {
            Log.d(TAG, "Natural user barge-in detected — clearing assistant audio queue")
            audioPlayerQueue.interruptAndClear()
            _sessionState.value = AssistantSessionState.Listening
        }

        // Schedule subtle "Thinking..." state after brief speech pause before Nipa's first audio arrives
        thinkingIndicatorJob?.cancel()
        thinkingIndicatorJob = sessionScope?.launch {
            delay(850L)
            if (hasUserSpokenInTurn &&
                !audioPlayerQueue.isPlaying.value &&
                _sessionState.value == AssistantSessionState.Listening
            ) {
                _sessionState.value = AssistantSessionState.Thinking
            }
        }
    }

    private suspend fun handleFatalSessionFailure(message: String, generation: Long) {
        sessionMutex.withLock {
            if (sessionGeneration.get() != generation) return@withLock
            teardownResourcesNonCancellable()
            _sessionState.value = AssistantSessionState.Error(
                message = LiveErrorDiagnostics.redactSecrets(message)
            )
        }
    }

    /**
     * Tears down audio capture, playback queue, WebSocket session, and speakerphone routing
     * inside NonCancellable so callers inside `sessionScope` never self-cancel mid-cleanup.
     */
    private suspend fun teardownResourcesNonCancellable() = withContext(NonCancellable) {
        hasUserSpokenInTurn = false
        thinkingIndicatorJob?.cancel()
        thinkingIndicatorJob = null

        audioStreamer.stopStreaming()
        audioPlayerQueue.release()

        val oldReceiveJob = receiveJob
        receiveJob = null
        val oldPlaybackJob = playbackObserverJob
        playbackObserverJob = null
        val oldScope = sessionScope
        sessionScope = null

        val activeSession = liveSession
        liveSession = null
        if (activeSession != null) {
            try {
                activeSession.stopReceiving()
            } catch (_: Exception) {
            }
            try {
                withTimeoutOrNull(2_000L) {
                    activeSession.close()
                }
            } catch (_: Exception) {
            }
        }

        configureSpeakerphoneRouting(enable = false)

        oldReceiveJob?.cancel()
        oldPlaybackJob?.cancel()
        oldScope?.cancel()
    }

    @Suppress("DEPRECATION")
    private fun configureSpeakerphoneRouting(enable: Boolean) {
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (enable) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            } else {
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio routing update warning", e)
        }
    }
}
