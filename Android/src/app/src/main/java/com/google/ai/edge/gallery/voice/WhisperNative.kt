package com.google.ai.edge.gallery.voice

import android.util.Log

object WhisperNative {
    private const val TAG = "AGWhisperNative"
    private var loaded = false

    fun loadLibrary() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val cpuInfo = cpuInfo()
                if (isArm64V8a() && cpuInfo?.contains("fphp") == true) {
                    System.loadLibrary("whisper_v8fp16_va")
                    Log.d(TAG, "Loaded whisper_v8fp16_va")
                } else if (isArm32V7a() && cpuInfo?.contains("vfpv4") == true) {
                    System.loadLibrary("whisper_vfpv4")
                    Log.d(TAG, "Loaded whisper_vfpv4")
                } else {
                    System.loadLibrary("whisper")
                    Log.d(TAG, "Loaded whisper (generic)")
                }
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load whisper native library", e)
                throw e
            }
        }
    }

    fun isLoaded(): Boolean = loaded

    private fun isArm64V8a(): Boolean {
        return android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    }

    private fun isArm32V7a(): Boolean {
        return android.os.Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" }
    }

    private fun cpuInfo(): String? {
        return try {
            java.io.BufferedReader(java.io.FileReader("/proc/cpuinfo")).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    external fun initContextFromFile(modelPath: String): Long

    external fun freeContext(contextPtr: Long)

    external fun fullTranscribe(contextPtr: Long, numThreads: Int, language: String, audioData: FloatArray)

    external fun getTextSegmentCount(contextPtr: Long): Int

    external fun getTextSegment(contextPtr: Long, index: Int): String

    external fun getTextSegmentT0(contextPtr: Long, index: Int): Long

    external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

    external fun getSystemInfo(): String
}
