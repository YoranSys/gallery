package com.google.ai.edge.gallery.voice

import android.util.Log
import kotlin.math.sqrt
import com.google.ai.edge.gallery.data.SAMPLE_RATE

class VadDetector {
    private val TAG = "AGVadDetector"

    companion object {
        private const val FRAME_SIZE = 512
        private const val SILENCE_DURATION_MS = 300
        private const val SPEECH_DURATION_MS = 50
        private const val SPEECH_RMS_THRESHOLD = 400.0
    }

    private var consecutiveSilenceFrames = 0
    private var consecutiveSpeechFrames = 0
    private var isCurrentlySpeech = false
    private var speechStarted = false
    private var speechEnded = false

    private val framesPerSilenceThreshold: Int
        get() {
            val frameDurationMs = (FRAME_SIZE * 1000) / SAMPLE_RATE
            return maxOf(1, SILENCE_DURATION_MS / frameDurationMs)
        }

    private val framesPerSpeechThreshold: Int
        get() {
            val frameDurationMs = (FRAME_SIZE * 1000) / SAMPLE_RATE
            return maxOf(1, SPEECH_DURATION_MS / frameDurationMs)
        }

    val isSpeech: Boolean get() = isCurrentlySpeech

    fun processFrame(frame: ByteArray): Boolean {
        val samples = ShortArray(frame.size / 2)
        java.nio.ByteBuffer.wrap(frame)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)

        val rms = computeRms(samples)
        val isFrameSpeech = rms > SPEECH_RMS_THRESHOLD

        speechStarted = false
        speechEnded = false

        if (isFrameSpeech) {
            consecutiveSpeechFrames++
            consecutiveSilenceFrames = 0
            if (!isCurrentlySpeech && consecutiveSpeechFrames >= framesPerSpeechThreshold) {
                isCurrentlySpeech = true
                speechStarted = true
                Log.d(TAG, "Speech started (RMS=$rms)")
            }
        } else {
            consecutiveSilenceFrames++
            consecutiveSpeechFrames = 0
            if (isCurrentlySpeech && consecutiveSilenceFrames >= framesPerSilenceThreshold) {
                isCurrentlySpeech = false
                speechEnded = true
                Log.d(TAG, "Speech ended (silence detected)")
            }
        }

        return isFrameSpeech
    }

    fun hasSpeechStarted(): Boolean = speechStarted
    fun hasSpeechEnded(): Boolean = speechEnded
    fun isCurrentlySpeaking(): Boolean = isCurrentlySpeech

    fun reset() {
        consecutiveSilenceFrames = 0
        consecutiveSpeechFrames = 0
        isCurrentlySpeech = false
        speechStarted = false
        speechEnded = false
    }

    fun close() {
        // No native resources to release
    }

    private fun computeRms(samples: ShortArray): Double {
        var sum = 0L
        for (sample in samples) {
            sum += (sample.toLong() * sample.toLong())
        }
        return sqrt(sum.toDouble() / samples.size)
    }
}
