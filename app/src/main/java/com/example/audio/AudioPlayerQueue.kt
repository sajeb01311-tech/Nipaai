package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Low-latency streaming PCM16 24kHz audio output queue for Nipa's voice responses.
 * Supports gapless chunk playback, real-time output amplitude tracking for waveform reactivity,
 * and instant interruption flushing when the user begins speaking.
 */
class AudioPlayerQueue {

    companion object {
        private const val TAG = "AudioPlayerQueue"
        const val OUTPUT_SAMPLE_RATE = 24_000
        private const val SUB_CHUNK_BYTES = 1920 // 40ms @ 24kHz 16-bit mono (960 samples * 2 bytes)
    }

    private val chunkQueue = ConcurrentLinkedQueue<ByteArray>()
    private val generationToken = AtomicInteger(0)

    private var audioTrack: AudioTrack? = null
    private var playbackScope: CoroutineScope? = null
    private var playbackJob: Job? = null
    private val trackLock = Any()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)
    val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

    fun initialize() {
        synchronized(trackLock) {
            if (audioTrack != null) return

            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SUB_CHUNK_BYTES * 2)

            try {
                val trackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(OUTPUT_SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }

                val track = trackBuilder.build()
                track.play()
                audioTrack = track
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioTrack", e)
                return
            }
        }

        if (playbackScope == null) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            playbackScope = scope
            playbackJob = scope.launch {
                runDrainLoop()
            }
        }
    }

    fun enqueuePcmChunk(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        if (audioTrack == null) {
            initialize()
        }
        // Slice large chunks into 40ms frames so interruptions and waveform levels stay ultra-responsive
        var offset = 0
        while (offset < pcmBytes.size) {
            val end = minOf(offset + SUB_CHUNK_BYTES, pcmBytes.size)
            // Keep 2-byte PCM16 alignment
            val alignedEnd = if ((end - offset) % 2 != 0 && end < pcmBytes.size) end - 1 else end
            if (alignedEnd > offset) {
                chunkQueue.offer(pcmBytes.copyOfRange(offset, alignedEnd))
            }
            offset = alignedEnd.coerceAtLeast(offset + 1)
        }
    }

    /**
     * Immediately halts current assistant audio playback and clears queued chunks
     * so the user can interrupt Nipa naturally with zero lingering audio.
     */
    fun interruptAndClear() {
        generationToken.incrementAndGet()
        chunkQueue.clear()
        _isPlaying.value = false
        _outputLevel.value = 0f

        synchronized(trackLock) {
            val track = audioTrack ?: return
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing AudioTrack on interruption", e)
            }
        }
    }

    fun release() {
        generationToken.incrementAndGet()
        chunkQueue.clear()
        playbackJob?.cancel()
        playbackJob = null
        playbackScope?.cancel()
        playbackScope = null

        synchronized(trackLock) {
            val track = audioTrack
            audioTrack = null
            if (track != null) {
                try {
                    track.pause()
                    track.flush()
                    track.stop()
                } catch (_: Exception) {
                }
                try {
                    track.release()
                } catch (_: Exception) {
                }
            }
        }

        _isPlaying.value = false
        _outputLevel.value = 0f
    }

    private suspend fun runDrainLoop() {
        var idleTicks = 0
        try {
            while (playbackScope?.isActive == true) {
                val tokenBeforeWrite = generationToken.get()
                val nextChunk = chunkQueue.poll()

                if (nextChunk != null) {
                    idleTicks = 0
                    _isPlaying.value = true
                    _outputLevel.value = computePcmByteRms(nextChunk)

                    synchronized(trackLock) {
                        val track = audioTrack
                        if (track != null && tokenBeforeWrite == generationToken.get()) {
                            try {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }
                                track.write(nextChunk, 0, nextChunk.size, AudioTrack.WRITE_BLOCKING)
                            } catch (e: Exception) {
                                Log.w(TAG, "AudioTrack write warning", e)
                            }
                        }
                    }
                } else {
                    idleTicks++
                    if (idleTicks >= 4) {
                        _isPlaying.value = false
                        _outputLevel.value = 0f
                    }
                    delay(12L)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Playback drain loop error", e)
        } finally {
            _isPlaying.value = false
            _outputLevel.value = 0f
        }
    }

    private fun computePcmByteRms(bytes: ByteArray): Float {
        if (bytes.size < 2) return 0f
        val shortCount = bytes.size / 2
        val buf = ByteBuffer.wrap(bytes, 0, shortCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        for (i in 0 until shortCount) {
            val s = buf.short / 32768.0
            sumSq += s * s
        }
        val rms = sqrt(sumSq / shortCount).toFloat()
        return (rms * 3.0f).coerceIn(0f, 1f)
    }
}
