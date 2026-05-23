package com.google.ai.edge.gallery.voice

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

data class VoiceConversationUiState(
    val state: VoiceState = VoiceState.IDLE,
    val partialTranscript: String = "",
    val userText: String = "",
    val responseText: String = "",
    val audioLevel: Int = 0,
    val isMuted: Boolean = false,
    val errorMessage: String? = null,
    val modelLoaded: Boolean = false,
)

@HiltViewModel
class VoiceConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val TAG = "AGVoiceConvVM"

    private val _uiState = MutableStateFlow(VoiceConversationUiState())
    val uiState: StateFlow<VoiceConversationUiState> = _uiState.asStateFlow()

    private val whisperTranscriber = WhisperTranscriber()
    private var vadDetector: VadDetector? = null
    private val audioCaptureManager = AudioCaptureManager()

    private var model: Model? = null
    private var modelHelper: LlmModelHelper? = null
    private var conversationJob: Job? = null
    private val ttsEngine = TtsEngine(context)

    private var isUserInterrupting = false

    init {
        viewModelScope.launch {
            audioCaptureManager.audioLevel.collect { level ->
                _uiState.update { it.copy(audioLevel = level) }
            }
        }
    }

    fun loadModel(model: Model, modelHelper: LlmModelHelper, whisperModelPath: String) {
        this.model = model
        this.modelHelper = modelHelper
        viewModelScope.launch(Dispatchers.Default) {
            val result = whisperTranscriber.loadModel(whisperModelPath)
            if (result.isSuccess) {
                vadDetector = VadDetector()
                _uiState.update { it.copy(modelLoaded = true, errorMessage = null) }
            } else {
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to load whisper model: ${result.exceptionOrNull()?.message}",
                        state = VoiceState.ERROR,
                    )
                }
            }
        }
    }

    fun startListening() {
        if (uiState.value.state != VoiceState.IDLE) return
        _uiState.update { it.copy(state = VoiceState.LISTENING, userText = "", responseText = "", errorMessage = null) }
        vadDetector?.reset()
        audioCaptureManager.clearBuffer()
        audioCaptureManager.start()
        startVadLoop()
    }

    fun stopListening() {
        audioCaptureManager.stop()
        conversationJob?.cancel()
        conversationJob = null
        ttsEngine.stop()
        _uiState.update { it.copy(state = VoiceState.IDLE, errorMessage = null) }
    }

    fun setMuted(muted: Boolean) {
        _uiState.update { it.copy(isMuted = muted) }
        if (muted) {
            ttsEngine.stop()
        }
    }

    private fun startVadLoop() {
        conversationJob?.cancel()
        conversationJob = viewModelScope.launch(Dispatchers.Default) {
            val vad = vadDetector ?: return@launch
            val silenceFrameDurationMs = (512 * 1000L) / SAMPLE_RATE
            var silenceDurationMs = 0L

            while (isActive && uiState.value.state == VoiceState.LISTENING) {
                delay(silenceFrameDurationMs)

                val audioFrame = synchronized(audioCaptureManager) {
                    val bytes = audioCaptureManager.getAccumulatedAudioBytes()
                    if (bytes.size < 1024) null
                    else bytes
                } ?: continue

                val lastFrame = audioFrame.copyOfRange(
                    maxOf(0, audioFrame.size - 1024),
                    audioFrame.size
                )

                val isFrameSpeech = vad.processFrame(lastFrame)

                if (vad.hasSpeechStarted()) {
                    Log.d(TAG, "VAD: speech detected")
                    silenceDurationMs = 0L
                }

                if (isFrameSpeech) {
                    silenceDurationMs = 0L
                    if (uiState.value.state == VoiceState.SPEAKING) {
                        Log.d(TAG, "User interruption detected during TTS")
                        isUserInterrupting = true
                        ttsEngine.stop()
                        _uiState.update { it.copy(state = VoiceState.LISTENING, responseText = "") }
                        audioCaptureManager.clearBuffer()
                        vad.reset()
                    }
                }

                if (vad.hasSpeechEnded()) {
                    Log.d(TAG, "VAD: speech ended, processing")
                    processUserSpeech()
                    break
                }
            }
        }
    }

    private suspend fun processUserSpeech() {
        val currentState = uiState.value.state
        if (currentState != VoiceState.LISTENING && currentState != VoiceState.IDLE) return

        _uiState.update { it.copy(state = VoiceState.THINKING) }
        audioCaptureManager.stop()

        val audioData = audioCaptureManager.getAccumulatedAudio()
        if (audioData.size < SAMPLE_RATE * 0.5f) {
            Log.d(TAG, "Audio too short, ignoring")
            _uiState.update { it.copy(state = VoiceState.LISTENING) }
            audioCaptureManager.clearBuffer()
            audioCaptureManager.start()
            startVadLoop()
            return
        }

        try {
            val userText = whisperTranscriber.transcribe(audioData, "auto")
            Log.d(TAG, "Transcription: $userText")
            _uiState.update { it.copy(userText = userText) }

            if (userText.isBlank()) {
                _uiState.update { it.copy(state = VoiceState.LISTENING, errorMessage = null) }
                audioCaptureManager.clearBuffer()
                audioCaptureManager.start()
                startVadLoop()
                return
            }

            sendToLlmAndSpeak(userText)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            _uiState.update {
                it.copy(state = VoiceState.ERROR, errorMessage = "Speech recognition failed: ${e.message}")
            }
        }
    }

    private suspend fun sendToLlmAndSpeak(userText: String) {
        val currentModel = model ?: return
        val helper = modelHelper ?: return
        _uiState.update { it.copy(state = VoiceState.SPEAKING, responseText = "") }

        var fullResponse = StringBuilder()

        try {
            helper.runInference(
                model = currentModel,
                input = userText,
                resultListener = { partialResult, done, _ ->
                    if (partialResult.isNotEmpty()) {
                        fullResponse.append(partialResult)
                        _uiState.update { it.copy(responseText = fullResponse.toString()) }
                    }
                    if (done && !isUserInterrupting) {
                        Log.d(TAG, "LLM inference done, response: ${fullResponse.take(100)}")
                        speakResponse(fullResponse.toString())
                    }
                },
                cleanUpListener = {
                    Log.d(TAG, "LLM cleanup triggered")
                },
                onError = { error ->
                    Log.e(TAG, "LLM inference error: $error")
                    _uiState.update {
                        it.copy(state = VoiceState.ERROR, errorMessage = "LLM error: $error")
                    }
                },
                coroutineScope = viewModelScope,
            )
        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed", e)
            _uiState.update {
                it.copy(state = VoiceState.ERROR, errorMessage = "Failed to get response: ${e.message}")
            }
        }
    }

    private fun speakResponse(responseText: String) {
        if (!uiState.value.isMuted && responseText.isNotBlank()) {
            ttsEngine.speak(responseText) {
                viewModelScope.launch {
                    delay(300)
                    if (!isUserInterrupting) {
                        _uiState.update { it.copy(state = VoiceState.LISTENING) }
                        isUserInterrupting = false
                        audioCaptureManager.clearBuffer()
                        audioCaptureManager.start()
                        startVadLoop()
                    }
                }
            }
        } else {
            launchVoiceResume("")
        }
    }

    private fun launchVoiceResume(responseText: String) {
        viewModelScope.launch {
            delay(800)
            if (!isUserInterrupting) {
                _uiState.update { it.copy(state = VoiceState.LISTENING) }
                isUserInterrupting = false
                audioCaptureManager.clearBuffer()
                audioCaptureManager.start()
                startVadLoop()
            }
        }
    }

    fun cleanup() {
        stopListening()
        whisperTranscriber.release()
        vadDetector?.close()
        vadDetector = null
        audioCaptureManager.release()
        ttsEngine.shutdown()
        model = null
        modelHelper = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
