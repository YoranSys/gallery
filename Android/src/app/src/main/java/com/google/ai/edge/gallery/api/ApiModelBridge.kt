/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.api

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val TAG = "ApiModelBridge"

/**
 * Thread-safe bridge that connects the model manager (which initializes models) to the HTTP
 * inference API server. A single active model is tracked; requests are serialized via a Mutex
 * so that only one inference runs at a time.
 */
@Singleton
class ApiModelBridge @Inject constructor(
  @ApplicationContext private val context: Context,
) {

  private data class ActiveEntry(val model: Model, val helper: LlmModelHelper)

  private val activeRef = AtomicReference<ActiveEntry?>(null)

  /** Held during inference so concurrent requests receive a 503. */
  private val inferenceMutex = Mutex()

  /** Held during model initialization to prevent concurrent init attempts. */
  private val initMutex = Mutex()

  // ---------------------------------------------------------------------------
  // Model registration (called by ModelManagerViewModel on successful init)
  // ---------------------------------------------------------------------------

  fun setActiveModel(model: Model, helper: LlmModelHelper) {
    activeRef.set(ActiveEntry(model, helper))
  }

  fun clearActiveModel() {
    activeRef.set(null)
  }

  // ---------------------------------------------------------------------------
  // Model discovery (GET /v1/models)
  // ---------------------------------------------------------------------------

  fun getAvailableModels(): List<Model> {
    return activeRef.get()?.model?.let { listOf(it) } ?: emptyList()
  }

  fun getActiveModelId(): String? = activeRef.get()?.model?.name

  fun getActiveModelMaxTokens(): Int {
    return activeRef.get()?.model?.llmMaxToken ?: 4000
  }

  // ---------------------------------------------------------------------------
  // Auto-load
  // ---------------------------------------------------------------------------

  /**
   * Initializes the active model if its [Model.instance] is null (not yet loaded).
   * Safe to call concurrently — only one initialization runs at a time; subsequent callers wait
   * and reuse the result.
   *
   * @return null on success, or an error message string on failure.
   */
  suspend fun initializeActiveModelIfNeeded(): String? {
    val entry = activeRef.get() ?: return "No model is registered."
    if (entry.model.instance != null) return null // already ready

    Log.i(TAG, "Auto-loading model '${entry.model.name}'\u2026")
    return initMutex.withLock {
      // Re-check under lock: another coroutine may have initialized while we waited.
      if (entry.model.instance != null) return@withLock null

      suspendCancellableCoroutine { cont ->
        entry.helper.initialize(
          context = context,
          model = entry.model,
          taskId = "llm_chat",
          supportImage = true,
          supportAudio = false,
          onDone = { errorMessage ->
            if (errorMessage.isEmpty()) {
              Log.i(TAG, "Model '${entry.model.name}' loaded successfully.")
              cont.resume(null)
            } else {
              Log.e(TAG, "Failed to auto-load model '${entry.model.name}': $errorMessage")
              cont.resume(errorMessage)
            }
          },
        )
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Inference
  // ---------------------------------------------------------------------------

  /**
   * Runs inference on [input] using the currently active model.
   *
   * @param input       The user message text.
   * @param onToken     Called for each partial result token (content, isDone).
   * @param onError     Called if inference fails or no model is available.
   * @param timeoutMs   Maximum time to wait for a response (default 5 minutes).
   * @param images      Optional list of images to include as input context.
   */
  suspend fun runInference(
    input: String,
    onToken: suspend (token: String, done: Boolean) -> Unit,
    onError: suspend (message: String) -> Unit,
    timeoutMs: Long = 5 * 60 * 1000L,
    images: List<android.graphics.Bitmap> = emptyList(),
  ) {
    val entry = activeRef.get()
    if (entry == null) {
      onError("No model is currently loaded. Please initialize a model first.")
      return
    }

    if (entry.model.instance == null) {
      val loadError = initializeActiveModelIfNeeded()
      if (loadError != null) {
        onError("Failed to auto-load model: $loadError")
        return
      }
    }

    // Only one request at a time.
    if (!inferenceMutex.tryLock()) {
      onError("Another inference request is already in progress. Please try again later.")
      return
    }

    try {
      // The API server already embeds the full conversation history in the prompt string on every
      // request. The underlying Conversation object is stateful and would accumulate duplicate
      // history across calls, so we reset it to a clean slate before each inference.
      entry.helper.resetConversation(model = entry.model)

      val deferred = CompletableDeferred<Unit>()
      withTimeout(timeoutMs) {
        entry.helper.runInference(
          model = entry.model,
          input = input,
          resultListener = { partial, done, _ ->
            // resultListener is called on a background thread; we bridge to the suspend world
            // by delegating token delivery to a callback that the SSE/full-response path handles.
            // Wrap in try-catch so that exceptions from onToken (e.g. IOException from a client
            // disconnect / broken pipe) do not propagate into the JNI callback layer and crash.
            try {
              kotlinx.coroutines.runBlocking { onToken(partial, done) }
              if (done) deferred.complete(Unit)
            } catch (e: Exception) {
              if (!deferred.isCompleted) deferred.completeExceptionally(e)
            }
          },
          cleanUpListener = {
            if (!deferred.isCompleted) deferred.complete(Unit)
          },
          onError = { msg ->
            if (!deferred.isCompleted) deferred.completeExceptionally(Exception(msg))
          },
          images = images,
        )
        deferred.await()
      }
    } catch (e: Exception) {
      onError(e.message ?: "Inference failed.")
    } finally {
      inferenceMutex.unlock()
    }
  }
}
