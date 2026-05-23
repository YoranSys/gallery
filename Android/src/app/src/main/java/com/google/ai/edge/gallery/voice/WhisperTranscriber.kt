package com.google.ai.edge.gallery.voice

import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WhisperTranscriber {
    private val TAG = "AGWhisperTranscriber"
    private var contextPtr: Long = 0
    private var numThreads: Int = Runtime.getRuntime().availableProcessors()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = executor.asCoroutineDispatcher()

    val isLoaded: Boolean get() = contextPtr != 0L

    fun loadModel(modelPath: String): Result<Unit> {
        return try {
            WhisperNative.loadLibrary()
            if (contextPtr != 0L) {
                WhisperNative.freeContext(contextPtr)
            }
            val file = File(modelPath)
            if (!file.exists()) {
                return Result.failure(IllegalArgumentException("Model file not found: $modelPath"))
            }
            contextPtr = WhisperNative.initContextFromFile(modelPath)
            if (contextPtr == 0L) {
                return Result.failure(RuntimeException("Failed to initialize whisper context from $modelPath"))
            }
            Log.d(TAG, "Whisper model loaded from $modelPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            Result.failure(e)
        }
    }

    suspend fun transcribe(audioData: FloatArray, language: String = "auto"): String =
        withContext(scope) {
            if (contextPtr == 0L) {
                throw IllegalStateException("Whisper model not loaded")
            }
            WhisperNative.fullTranscribe(contextPtr, numThreads, language, audioData)
            val nSegments = WhisperNative.getTextSegmentCount(contextPtr)
            val result = StringBuilder()
            for (i in 0 until nSegments) {
                val text = WhisperNative.getTextSegment(contextPtr, i)
                result.append(text)
            }
            result.toString().trim()
        }

    fun release() {
        if (contextPtr != 0L) {
            WhisperNative.freeContext(contextPtr)
            contextPtr = 0
        }
        executor.shutdown()
    }
}
