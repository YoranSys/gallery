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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import java.io.IOException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val TAG = "InferenceApiServer"

/** Default port — matches the Ollama convention for maximum client compatibility. */
const val DEFAULT_API_PORT = 11434

/** Secret map key used to store the API key in UserData.secrets. */
const val API_KEY_SECRET = "inference_api_key"

/**
 * Estimates the number of tokens in a string using a simple whitespace-based approximation.
 * This is a rough estimate: actual token count depends on the tokenizer.
 * We reserve some margin to account for tokenization differences.
 */
private fun estimateTokenCount(text: String): Int {
  // Simple approximation: count whitespace-separated tokens
  // This is not accurate for all tokenizers but gives a reasonable estimate
  if (text.isEmpty()) return 0
  // Count whitespace-delimited words as rough token estimate
  return text.split("\\s+".toRegex()).size
}

/**
 * Helper function to extract text representation from MessageContent.
 * For images, returns a placeholder description.
 */
private fun MessageContent?.toText(): String {
  return when (this) {
    null -> ""
    is MessageContent.TextContent -> text
    is MessageContent.MultimodalContent -> {
      parts.joinToString(" ") { part ->
        when (part) {
          is TextPart -> part.text
          is ImageUrlPart -> "[Image]"
          else -> ""
        }
      }
    }
  }
}

/**
 * Helper function to extract image Bitmaps from a list of messages.
 * Returns a list of Bitmaps for all image URLs found in the messages.
 */
private fun extractImagesFromMessages(messages: List<ChatMessage>): List<Bitmap> {
  val bitmaps = mutableListOf<Bitmap>()
  
  for (msg in messages) {
    val content = msg.content ?: continue
    if (content is MessageContent.MultimodalContent) {
      for (part in content.parts) {
        if (part is ImageUrlPart) {
          val bitmap = decodeBase64Image(part.imageUrl.url)
          if (bitmap != null) {
            bitmaps.add(bitmap)
          }
        }
      }
    }
  }
  
  return bitmaps
}

/**
 * Decodes a base64-encoded image URL to a Bitmap.
 * Supports data URLs like "data:image/jpeg;base64,..."
 */
private fun decodeBase64Image(imageUrl: String): Bitmap? {
  return try {
    // Handle data URLs: "data:image/jpeg;base64,/9j/4AAQSk..."
    val base64Data = if (imageUrl.startsWith("data:")) {
      val commaIndex = imageUrl.indexOf(',')
      if (commaIndex >= 0) {
        imageUrl.substring(commaIndex + 1)
      } else {
        return null
      }
    } else {
      imageUrl
    }
    
    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  } catch (e: Exception) {
    Log.w(TAG, "Failed to decode image: ${e.message}")
    null
  }
}

/**
 * Estimates the total token count for a list of messages.
 */
private fun estimateMessageListTokenCount(messages: List<ChatMessage>, tools: List<Tool>?): Int {
  // Build a minimal prompt representation to estimate tokens
  val sb = StringBuilder()
  for (msg in messages) {
    when (msg.role) {
      "system" -> {
        sb.append("[System]: ${msg.content.toText()}")
        if (!tools.isNullOrEmpty()) {
          sb.append("\n\n## Tool Use")
          for (tool in tools) {
            sb.append(" ${tool.function.name}")
          }
        }
      }
      "assistant" -> {
        if (!msg.toolCalls.isNullOrEmpty()) {
          sb.append("[Assistant]: {}")
        } else {
          sb.append("[Assistant]: ${msg.content.toText()}")
        }
      }
      "tool" -> sb.append("[Tool]: ${msg.content.toText()}")
      else -> sb.append("[User]: ${msg.content.toText()}")
    }
    sb.append("\n")
  }
  return estimateTokenCount(sb.toString())
}

/**
 * Truncates the message list to fit within the model's context window.
 * Keeps the most recent messages (last N) to stay under the token limit.
 * Always keeps at least the system message (if present) and the last user message.
 */
private fun truncateMessagesToFit(
  messages: List<ChatMessage>,
  tools: List<Tool>?,
  maxTokens: Int,
  // Reserve some tokens for the response
  reservedTokens: Int = 100
): List<ChatMessage> {
  if (messages.isEmpty()) return messages

  // First, try the full message list
  val fullTokenCount = estimateMessageListTokenCount(messages, tools)
  if (fullTokenCount <= maxTokens - reservedTokens) {
    return messages
  }

  // Find system message index
  val systemMessageIndex = messages.indexOfFirst { it.role == "system" }
  val systemMessage = if (systemMessageIndex >= 0) messages[systemMessageIndex] else null

  // Separate system message from conversation
  val conversationMessages = messages.filter { it.role != "system" }

  // Binary search to find the maximum number of recent messages that fit
  // We always want to keep at least 1 message (the last user message)
  var low = 1
  var high = conversationMessages.size
  var bestCount = 1

  while (low <= high) {
    val mid = (low + high) / 2
    val candidateMessages = conversationMessages.takeLast(mid)
    val candidateTokenCount = estimateMessageListTokenCount(
      if (systemMessage != null) listOf(systemMessage) + candidateMessages else candidateMessages,
      tools
    )

    if (candidateTokenCount <= maxTokens - reservedTokens) {
      bestCount = mid
      low = mid + 1
    } else {
      high = mid - 1
    }
  }

  val resultMessages = conversationMessages.takeLast(bestCount)
  return if (systemMessage != null) {
    listOf(systemMessage) + resultMessages
  } else {
    resultMessages
  }
}

/** Internal helper used to parse a tool call emitted by the model. */
@Serializable
private data class ParsedToolCall(
  val name: String,
  val arguments: JsonElement = JsonObject(emptyMap()),
)

/**
 * Lightweight Ktor (CIO) HTTP server that exposes an OpenAI-compatible chat completions API,
 * routing requests to [ApiModelBridge].
 */
class InferenceApiServer(
  private val bridge: ApiModelBridge,
  private val getApiKey: () -> String?,
  port: Int = DEFAULT_API_PORT,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

  private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
      exception<Throwable> { call, cause ->
        Log.e(TAG, "Unhandled error: ${cause.message}", cause)
        call.respond(
          HttpStatusCode.InternalServerError,
          ApiErrorResponse(ApiError(cause.message ?: "Internal server error", "server_error")),
        )
      }
    }

    routing {
      // ------------------------------------------------------------------
      // GET /v1/models
      // ------------------------------------------------------------------
      get("/v1/models") {
        if (!checkAuth(call)) return@get
        val now = System.currentTimeMillis() / 1000L
        val models =
          bridge.getAvailableModels().map { model ->
            ModelObject(id = model.name, created = now)
          }
        call.respond(ModelListResponse(data = models))
      }

      // ------------------------------------------------------------------
      // POST /v1/chat/completions
      // ------------------------------------------------------------------
      post("/v1/chat/completions") {
        if (!checkAuth(call)) return@post

        val request = runCatching { call.receive<ChatCompletionRequest>() }.getOrElse {
          call.respond(
            HttpStatusCode.BadRequest,
            ApiErrorResponse(ApiError("Invalid request body: ${it.message}", "invalid_request_error")),
          )
          return@post
        }

        // Get the model's max tokens and truncate messages if needed
        // Use the smaller of the model's max or the request's max_tokens (if specified)
        val modelMaxTokens = bridge.getActiveModelMaxTokens()
        val effectiveMaxTokens = request.maxTokens?.let {
          Math.min(it, modelMaxTokens)
        } ?: modelMaxTokens
        val truncatedMessages = truncateMessagesToFit(
          request.messages,
          request.tools,
          effectiveMaxTokens
        )
        // Log if truncation occurred
        if (truncatedMessages.size < request.messages.size) {
          Log.w(TAG, "Truncated ${request.messages.size - truncatedMessages.size} messages " +
            "to fit within ${effectiveMaxTokens} token limit")
        }

        // Build the prompt from the (possibly truncated) message history, injecting tool schemas when present.
        val prompt = buildPrompt(truncatedMessages, request.tools)
        val modelId = bridge.getActiveModelId() ?: request.model
        val completionId = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000L

        if (request.stream) {
          // ----------------------------------------------------------------
          // Streaming response — Server-Sent Events
          // ----------------------------------------------------------------
          // Extract images from messages for multimodal input
          val images = extractImagesFromMessages(truncatedMessages)
          
          call.respondTextWriter(ContentType.Text.EventStream) {
            // First chunk: role announcement
            val roleChunk = ChatCompletionChunk(
              id = completionId,
              created = created,
              model = modelId,
              choices = listOf(
                ChatCompletionChunkChoice(
                  index = 0,
                  delta = DeltaMessage(role = "assistant"),
                )
              ),
            )
            emit("data: ${json.encodeToString(roleChunk)}\n\n")

            // When tools are available the model may respond with a JSON tool call instead
            // of plain text.  Buffer tokens whose first non-whitespace character is '{' and
            // attempt to parse the full buffer as a tool call once the model finishes.
            val toolsEnabled = !request.tools.isNullOrEmpty()
            var toolBuffer: StringBuilder? = null
            var firstTokenReceived = false
            var errorOccurred = false

            bridge.runInference(
              input = prompt,
              images = images,
              onToken = { token, done ->
                if (!done) {
                  if (!firstTokenReceived) {
                    firstTokenReceived = true
                    if (toolsEnabled && token.trimStart().startsWith("{")) {
                      toolBuffer = StringBuilder()
                    }
                  }
                  if (toolBuffer != null) {
                    toolBuffer!!.append(token)
                  } else {
                    val chunk = ChatCompletionChunk(
                      id = completionId,
                      created = created,
                      model = modelId,
                      choices = listOf(
                        ChatCompletionChunkChoice(
                          index = 0,
                          delta = DeltaMessage(content = token),
                        )
                      ),
                    )
                    emit("data: ${json.encodeToString(chunk)}\n\n")
                  }
                } else {
                  val finishReason: String
                  if (toolBuffer != null) {
                    // Attempt to parse buffered text as a tool call.
                    val parsed = tryParseToolCall(toolBuffer.toString())
                    if (parsed != null) {
                      finishReason = "tool_calls"
                      val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
                      val toolCallChunk = ChatCompletionChunk(
                        id = completionId,
                        created = created,
                        model = modelId,
                        choices = listOf(
                          ChatCompletionChunkChoice(
                            index = 0,
                            delta = DeltaMessage(
                              toolCalls = listOf(
                                ToolCallDelta(
                                  index = 0,
                                  id = callId,
                                  type = "function",
                                  function = ToolCallFunction(
                                    name = parsed.name,
                                    arguments = json.encodeToString(parsed.arguments),
                                  ),
                                )
                              )
                            ),
                          )
                        ),
                      )
                      emit("data: ${json.encodeToString(toolCallChunk)}\n\n")
                    } else {
                      // Not a valid tool call — emit buffered text as regular content.
                      finishReason = "stop"
                      val textChunk = ChatCompletionChunk(
                        id = completionId,
                        created = created,
                        model = modelId,
                        choices = listOf(
                          ChatCompletionChunkChoice(
                            index = 0,
                            delta = DeltaMessage(content = toolBuffer.toString()),
                          )
                        ),
                      )
                      emit("data: ${json.encodeToString(textChunk)}\n\n")
                    }
                  } else {
                    finishReason = "stop"
                  }
                  val finalChunk = ChatCompletionChunk(
                    id = completionId,
                    created = created,
                    model = modelId,
                    choices = listOf(
                      ChatCompletionChunkChoice(
                        index = 0,
                        delta = DeltaMessage(),
                        finishReason = finishReason,
                      )
                    ),
                  )
                  emit("data: ${json.encodeToString(finalChunk)}\n\n")
                  emit("data: [DONE]\n\n")
                }
              },
              onError = { msg ->
                errorOccurred = true
                val errPayload = ApiErrorResponse(ApiError(msg, "server_error"))
                emit("data: ${json.encodeToString(errPayload)}\n\n")
                emit("data: [DONE]\n\n")
                Log.e(TAG, "Streaming inference error: $msg")
              },
            )
            if (errorOccurred) {
              Log.w(TAG, "Streaming ended with error.")
            }
          }
        } else {
          // ----------------------------------------------------------------
          // Non-streaming response
          // ----------------------------------------------------------------
          val fullResponse = StringBuilder()
          var inferenceError: String? = null
          
          // Extract images from messages for multimodal input
          val images = extractImagesFromMessages(truncatedMessages)

          bridge.runInference(
            input = prompt,
            images = images,
            onToken = { token, _ -> fullResponse.append(token) },
            onError = { msg -> inferenceError = msg },
          )

          if (inferenceError != null) {
            call.respond(
              HttpStatusCode.InternalServerError,
              ApiErrorResponse(ApiError(inferenceError!!, "server_error")),
            )
            return@post
          }

          val responseText = fullResponse.toString()
          val parsed = if (!request.tools.isNullOrEmpty()) tryParseToolCall(responseText) else null

          if (parsed != null) {
            val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            call.respond(
              ChatCompletionResponse(
                id = completionId,
                created = created,
                model = modelId,
                choices = listOf(
                  ChatCompletionChoice(
                    index = 0,
                    message = ChatMessage(
                      role = "assistant",
                      toolCalls = listOf(
                        ToolCall(
                          id = callId,
                          type = "function",
                          function = ToolCallFunction(
                            name = parsed.name,
                            arguments = json.encodeToString(parsed.arguments),
                          ),
                        )
                      ),
                    ),
                    finishReason = "tool_calls",
                  )
                ),
                usage = CompletionUsage(
                  promptTokens = prompt.split(" ").size,
                  completionTokens = responseText.split(" ").size,
                  totalTokens = prompt.split(" ").size + responseText.split(" ").size,
                ),
              ),
            )
          } else {
            val tokenCount = responseText.split(" ").size
            call.respond(
              ChatCompletionResponse(
                id = completionId,
                created = created,
                model = modelId,
                choices = listOf(
                  ChatCompletionChoice(
                    index = 0,
                    message = ChatMessage(
                      role = "assistant",
                      content = MessageContent.TextContent(responseText)
                    ),
                    finishReason = "stop",
                  )
                ),
                usage = CompletionUsage(
                  promptTokens = prompt.split(" ").size,
                  completionTokens = tokenCount,
                  totalTokens = prompt.split(" ").size + tokenCount,
                ),
              ),
            )
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private suspend fun checkAuth(call: io.ktor.server.application.ApplicationCall): Boolean {
    val requiredKey = getApiKey()
    if (requiredKey.isNullOrEmpty()) return true // Auth disabled
    val authHeader = call.request.headers["Authorization"] ?: ""
    val token = authHeader.removePrefix("Bearer ").trim()
    if (token != requiredKey) {
      call.respond(
        HttpStatusCode.Unauthorized,
        ApiErrorResponse(ApiError("Invalid API key.", "authentication_error", "invalid_api_key")),
      )
      return false
    }
    return true
  }

  /**
   * Formats the message history into a plain-text prompt.  When [tools] are provided, a
   * structured tool-use instruction block is appended to the system turn so the model knows how
   * to emit a tool call and what tools are available.
   */
  private fun buildPrompt(messages: List<ChatMessage>, tools: List<Tool>? = null): String {
    val sb = StringBuilder()
    for (msg in messages) {
      when (msg.role) {
        "system" -> {
          sb.append("[System]: ${msg.content.toText()}")
          if (!tools.isNullOrEmpty()) {
            sb.append(
              "\n\n## Tool Use\n" +
                "When you need to invoke a tool, respond with ONLY the following JSON object " +
                "(no markdown fences, no explanation, nothing else):\n" +
                "{\"name\": \"<tool_name>\", \"arguments\": {<arguments>}}\n\n" +
                "Available tools:\n"
            )
            for (tool in tools) {
              sb.append("- ${tool.function.name}")
              if (tool.function.description != null) sb.append(": ${tool.function.description}")
              if (tool.function.parameters != null) {
                sb.append("  parameters: ${json.encodeToString(tool.function.parameters)}")
              }
              sb.append("\n")
            }
          }
        }
        "assistant" -> {
          if (!msg.toolCalls.isNullOrEmpty()) {
            // Re-inject tool call so the model sees the full conversation history.
            val call = msg.toolCalls[0]
            sb.append("[Assistant]: {\"name\": \"${call.function.name}\", \"arguments\": ${call.function.arguments ?: "{}"}}")
          } else {
            sb.append("[Assistant]: ${msg.content.toText()}")
          }
        }
        "tool" -> sb.append("[Tool result (${msg.toolCallId ?: "unknown"})]: ${msg.content.toText()}")
        else -> sb.append("[User]: ${msg.content.toText()}")
      }
      sb.append("\n")
    }
    return sb.toString()
  }

  /**
   * Tries to parse [text] as a model-emitted tool call in the format
   * `{"name": "...", "arguments": {...}}`.  Returns null if [text] is not a valid tool call.
   */
  private fun tryParseToolCall(text: String): ParsedToolCall? {
    val trimmed = text.trim()
    return try {
      json.decodeFromString<ParsedToolCall>(trimmed)
    } catch (_: Exception) {
      null
    }
  }

  private suspend fun java.io.Writer.emit(text: String) {
    try {
      write(text)
      flush()
    } catch (_: IOException) {
      // Client disconnected mid-stream — discard the write silently.
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  fun start() {
    Log.i(TAG, "Starting inference API server…")
    server.start(wait = false)
    Log.i(TAG, "Inference API server started.")
  }

  fun stop() {
    Log.i(TAG, "Stopping inference API server…")
    server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
    Log.i(TAG, "Inference API server stopped.")
  }
}
