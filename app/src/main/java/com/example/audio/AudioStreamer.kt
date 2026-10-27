package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Dedicated AudioStreamer module responsible for:
 * - AudioRecord initialization withVOICE_COMMUNICATION hardware AEC/NS
 * - Capturing mono PCM16 audio
 * - Resampling to strict 16 kHz mono little-endian PCM16 when hardware captures at 44.1/48 kHz
 * - Real-time RMS energy calculation and client-side VAD speech onset detection for natural interruptions
 * - Clean lifecycle teardown without memory leaks
 */
class AudioStreamer {

    companion object {
        private const val TAG = "AudioStreamer"
        const val TARGET_SAMPLE_RATE = 16_000
        private const val CHUNK_DURATION_MS = 40 // ~40ms chunks = 640 samples = 1280 bytes at 16kHz
        private const val INTERRUPTION_RMS_THRESHOLD = 0.085f
        private const val INTERRUPTION_CONSECUTIVE_FRAMES = 3
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private var consecutiveSpeechFrames = 0

    @SuppressLint("MissingPermission")
    fun startStreaming(
        onAudioChunk: suspend (ByteArray) -> Unit,
        onSpeechDetected: () -> Unit,
        onError: (String) -> Unit
    ) {
        stopStreaming()

        val candidateRates = intArrayOf(TARGET_SAMPLE_RATE, 48_000, 44_100)
        var initializedRecord: AudioRecord? = null
        var captureRate = TARGET_SAMPLE_RATE
        var bufferSizeBytes = 0

        for (rate in candidateRates) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue

            val frameChunkBytes = (rate * CHUNK_DURATION_MS / 1000) * 2
            val chosenBuf = maxOf(minBuf * 2, frameChunkBytes * 4)

            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(chosenBuf)
                    .build()

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    initializedRecord = record
                    captureRate = rate
                    bufferSizeBytes = (rate * CHUNK_DURATION_MS / 1000) * 2
                    break
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for rate $rate Hz", e)
            }
        }

        val record = initializedRecord
        if (record == null) {
            onError("Unable to initialize microphone audio input.")
            return
        }

        audioRecord = record
        attachHardwareEffects(record.audioSessionId)

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            stopStreaming()
            onError("Could not start microphone recording. Check audio permissions.")
            return
        }

        _isCapturing.value = true
        consecutiveSpeechFrames = 0

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        captureScope = scope
        captureJob = scope.launch {
            val rawShortCount = (bufferSizeBytes / 2).coerceAtLeast(320)
            val shortBuffer = ShortArray(rawShortCount)

            try {
                while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readShorts = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readShorts > 0) {
                        val resampledShorts = if (captureRate == TARGET_SAMPLE_RATE) {
                            if (readShorts == shortBuffer.size) shortBuffer else shortBuffer.copyOf(readShorts)
                        } else {
                            resampleLinear(shortBuffer, readShorts, captureRate, TARGET_SAMPLE_RATE)
                        }

                        val rms = computeNormalizedRms(resampledShorts)
                        _inputLevel.value = rms

                        if (rms >= INTERRUPTION_RMS_THRESHOLD) {
                            consecutiveSpeechFrames++
                            if (consecutiveSpeechFrames >= INTERRUPTION_CONSECUTIVE_FRAMES) {
                                onSpeechDetected()
                            }
                        } else {
                            consecutiveSpeechFrames = 0
                        }

                        val pcmBytes = shortsToLittleEndianBytes(resampledShorts)
                        onAudioChunk(pcmBytes)
                    } else if (readShorts < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $readShorts")
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in microphone capture loop", e)
                onError("Microphone stream interrupted.")
            } finally {
                _isCapturing.value = false
                _inputLevel.value = 0f
            }
        }
    }

    fun stopStreaming() {
        captureJob?.cancel()
        captureJob = null
        captureScope?.cancel()
        captureScope = null

        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
        }

        _isCapturing.value = false
        _inputLevel.value = 0f
        consecutiveSpeechFrames = 0
    }

    private fun attachHardwareEffects(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach hardware audio effects", e)
        }
    }

    internal fun resampleLinear(
        input: ShortArray,
        inputLength: Int,
        fromRate: Int,
        toRate: Int
    ): ShortArray {
        if (inputLength <= 0) return ShortArray(0)
        if (fromRate == toRate) return input.copyOf(inputLength)

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = (inputLength / ratio).toInt().coerceAtLeast(1)
        val output = ShortArray(outputLength)

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, inputLength - 1)
            val idx1 = (idx0 + 1).coerceIn(0, inputLength - 1)
            val frac = srcPos - idx0
            val interpolated = input[idx0] * (1.0 - frac) + input[idx1] * frac
            output[i] = interpolated.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    internal fun shortsToLittleEndianBytes(samples: ShortArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            byteBuffer.putShort(sample)
        }
        return byteBuffer.array()
    }

    private fun computeNormalizedRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (sample in samples) {
            val norm = sample / 32768.0
            sumSquares += norm * norm
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()
        return (rms * 3.4f).coerceIn(0f, 1f)
    }
}
