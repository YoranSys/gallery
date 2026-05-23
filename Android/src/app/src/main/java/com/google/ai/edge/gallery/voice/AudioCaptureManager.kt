package com.google.ai.edge.gallery.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.common.calculatePeakAmplitude
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureManager {
    private val TAG = "AGAudioCapture"
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)

    private val _audioLevel = MutableStateFlow(0)
    val audioLevel: StateFlow<Int> = _audioLevel.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturingFlow: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val audioBuffer = ByteArrayOutputStream()
    private val bufferLock = Any()

    val sampleRate: Int = SAMPLE_RATE
    val frameSize: Int = 512
    val bytesPerFrame: Int = frameSize * 2

    fun start() {
        if (isCapturing.get()) return
        isCapturing.set(true)
        _isCapturing.value = true
        audioBuffer.reset()

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, bytesPerFrame * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            isCapturing.set(false)
            _isCapturing.value = false
            return
        }

        audioRecord?.startRecording()
        Log.d(TAG, "Audio capture started")

        captureThread = Thread {
            val frameBuffer = ByteArray(bytesPerFrame)
            while (isCapturing.get()) {
                val read = try {
                    audioRecord?.read(frameBuffer, 0, bytesPerFrame) ?: 0
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading audio", e)
                    break
                }

                if (read > 0) {
                    val frame = frameBuffer.copyOf(read)
                    synchronized(bufferLock) {
                        audioBuffer.write(frame)
                    }
                    val amplitude = calculatePeakAmplitude(frame, read)
                    _audioLevel.value = amplitude
                }
            }
        }.apply {
            name = "AudioCapture"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isCapturing.set(false)
        _isCapturing.value = false
        captureThread?.join(1000)
        captureThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        audioRecord = null
        Log.d(TAG, "Audio capture stopped")
    }

    fun getAccumulatedAudio(): FloatArray {
        return synchronized(bufferLock) {
            if (audioBuffer.size() == 0) return FloatArray(0)
            val bytes = audioBuffer.toByteArray()
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            FloatArray(shorts.size) { i -> shorts[i].toFloat() / 32768f }
        }
    }

    fun getAccumulatedAudioBytes(): ByteArray {
        return synchronized(bufferLock) {
            audioBuffer.toByteArray()
        }
    }

    fun clearBuffer() {
        synchronized(bufferLock) {
            audioBuffer.reset()
        }
    }

    fun release() {
        stop()
    }
}
