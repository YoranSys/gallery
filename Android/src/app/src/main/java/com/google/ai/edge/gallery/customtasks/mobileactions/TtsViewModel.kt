/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "TtsViewModel"

/**
 * ViewModel for managing Text-to-Speech functionality.
 * 
 * This ViewModel provides a simple interface for enabling/disabling TTS and speaking text.
 */
@HiltViewModel
class TtsViewModel @Inject constructor(@ApplicationContext private val appContext: Context) : ViewModel() {
  private var tts: TextToSpeech? = null
  var isInitialized = false
  private var _isSpeakingState = false
  private var utteranceIdCounter = 0
  
  // State for TTS speaking status
  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking = _isSpeaking.asStateFlow()

  init {
    initializeTts()
  }

  /**
   * Initialize the TTS engine.
   */
  private fun initializeTts() {
    if (isInitialized) return
    
    tts = TextToSpeech(appContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        isInitialized = true
        // Set language to system default
        val locale = Locale.getDefault()
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
          Log.w(TAG, "Language not supported: $locale")
          // Fall back to English
          tts?.setLanguage(Locale.ENGLISH)
        }
        Log.d(TAG, "TTS initialized successfully")
      } else {
        Log.e(TAG, "TTS initialization failed with status: $status")
        isInitialized = false
      }
    }
  }

  /**
   * Speak the last model response once via TTS.
   * Called when user explicitly clicks the speaker button.
   *
   * @param text The text to speak
   */
  fun speakResponse(text: String) {
    Log.d(TAG, "speakResponse() called: textLength=${text.length}, isInitialized=$isInitialized")
    if (!isInitialized || tts == null) {
      Log.w(TAG, "TTS not initialized, cannot speak")
      return
    }

    val utteranceId = "utterance_${++utteranceIdCounter}"
    _isSpeaking.value = true
    _isSpeakingState = true

    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String) {
        Log.d(TAG, "TTS onStart: utteranceId=$utteranceId")
      }

      override fun onDone(utteranceId: String) {
        Log.d(TAG, "TTS onDone: utteranceId=$utteranceId")
        _isSpeakingState = false
        _isSpeaking.value = false
      }

      override fun onError(utteranceId: String) {
        Log.d(TAG, "TTS onError: utteranceId=$utteranceId")
        _isSpeakingState = false
        _isSpeaking.value = false
      }
    })

    val params = Bundle().apply {
      putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
    }

    val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    Log.d(TAG, "speakResponse() result=$result")
    
    if (result != TextToSpeech.SUCCESS) {
      Log.e(TAG, "Failed to speak text: $result")
      _isSpeakingState = false
      _isSpeaking.value = false
    }
  }

  /**
   * Stop current speech.
   */
  fun stop() {
    tts?.stop()
    _isSpeakingState = false
    _isSpeaking.value = false
  }

  fun interrupt() {
    stop()
  }

  /**
   * Clean up TTS resources.
   */
  override fun onCleared() {
    tts?.shutdown()
    tts = null
    isInitialized = false
    _isSpeakingState = false
    super.onCleared()
  }
}
