package com.google.ai.edge.gallery.customtasks.voiceconversation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.voice.VoiceConversationViewModel
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class VoiceConversationTask @Inject constructor() : CustomTask {
    override val task: Task = Task(
        id = BuiltInTaskId.LLM_VOICE_CONVERSATION,
        label = "Voice Chat",
        category = Category.LLM,
        iconVectorResourceId = R.drawable.chat_spark,
        newFeature = true,
        models = mutableListOf(),
        description = "Have a natural voice conversation with on-device AI using speech recognition and text-to-speech",
        shortDescription = "Voice conversation with AI",
        docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
        sourceCodeUrl = "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/voiceconversation/",
        textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
        defaultSystemPrompt = "You are a helpful, friendly AI assistant in a voice conversation. Keep your responses concise and natural, as they will be spoken aloud. Use a conversational tone. Answer questions clearly and directly.",
        useThemeColor = true,
    )

    override fun initializeModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        systemInstruction: Contents?,
        onDone: (String) -> Unit,
    ) {
        model.runtimeHelper.initialize(
            context = context,
            model = model,
            taskId = task.id,
            supportImage = false,
            supportAudio = false,
            onDone = onDone,
            coroutineScope = coroutineScope,
            systemInstruction = systemInstruction ?: Contents.of(task.defaultSystemPrompt),
        )
    }

    override fun cleanUpModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: () -> Unit,
    ) {
        model.runtimeHelper.cleanUp(model = model, onDone = onDone)
    }

    @Composable
    override fun MainScreen(data: Any) {
        val myData = data as CustomTaskDataForBuiltinTask
        val viewModel: VoiceConversationViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsState()
        val modelManagerViewModel = myData.modelManagerViewModel
        val selectedModel = modelManagerViewModel.uiState.collectAsState().value.selectedModel
        val context = androidx.compose.ui.platform.LocalContext.current

        LaunchedEffect(selectedModel) {
            val model = selectedModel
            val whisperModelFile = model.getExtraDataFile("whisper_model")
            if (whisperModelFile != null) {
                val whisperPath = java.io.File(
                    model.getPath(context),
                    whisperModelFile.downloadFileName
                ).absolutePath
                viewModel.loadModel(model, model.runtimeHelper, whisperPath)
            }
        }

        VoiceConversationScreen(
            task = task,
            uiState = uiState,
            onStartListening = { viewModel.startListening() },
            onStopListening = { viewModel.stopListening() },
            onToggleMute = { viewModel.setMuted(!uiState.isMuted) },
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object VoiceConversationTaskModule {
    @Provides
    @IntoSet
    fun provideTask(): CustomTask {
        return VoiceConversationTask()
    }
}
