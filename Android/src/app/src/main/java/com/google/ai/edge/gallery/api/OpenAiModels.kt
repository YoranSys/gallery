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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// Tool / function-calling models
// ---------------------------------------------------------------------------

@Serializable
data class ToolFunctionDef(
  val name: String,
  val description: String? = null,
  val parameters: JsonObject? = null,
)

@Serializable
data class Tool(
  val type: String = "function",
  val function: ToolFunctionDef,
)

/** Accumulated tool-call entry (non-streaming response & assistant history). */
@Serializable
data class ToolCallFunction(
  val name: String? = null,
  val arguments: String? = null,
)

@Serializable
data class ToolCall(
  val id: String? = null,
  val type: String? = "function",
  val function: ToolCallFunction,
)

/** Per-chunk tool-call delta (streaming response). */
@Serializable
data class ToolCallDelta(
  val index: Int,
  val id: String? = null,
  val type: String? = null,
  val function: ToolCallFunction? = null,
)

// ---------------------------------------------------------------------------
// Request models
// ---------------------------------------------------------------------------

@Serializable
data class ChatMessage(
  val role: String,
  val content: String? = null,
  @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
  @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class ChatCompletionRequest(
  val model: String,
  val messages: List<ChatMessage>,
  val stream: Boolean = false,
  val temperature: Float? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  @SerialName("top_p") val topP: Float? = null,
  val tools: List<Tool>? = null,
)

// ---------------------------------------------------------------------------
// Response models (non-streaming)
// ---------------------------------------------------------------------------

@Serializable
data class ChatCompletionChoice(
  val index: Int,
  val message: ChatMessage,
  @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class CompletionUsage(
  @SerialName("prompt_tokens") val promptTokens: Int,
  @SerialName("completion_tokens") val completionTokens: Int,
  @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class ChatCompletionResponse(
  val id: String,
  @SerialName("object") val objectType: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<ChatCompletionChoice>,
  val usage: CompletionUsage,
)

// ---------------------------------------------------------------------------
// Streaming chunk models (SSE)
// ---------------------------------------------------------------------------

@Serializable
data class DeltaMessage(
  val role: String? = null,
  val content: String? = null,
  @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ChatCompletionChunkChoice(
  val index: Int,
  val delta: DeltaMessage,
  @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionChunk(
  val id: String,
  @SerialName("object") val objectType: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<ChatCompletionChunkChoice>,
)

// ---------------------------------------------------------------------------
// Model list
// ---------------------------------------------------------------------------

@Serializable
data class ModelObject(
  val id: String,
  @SerialName("object") val objectType: String = "model",
  val created: Long,
  @SerialName("owned_by") val ownedBy: String = "local",
)

@Serializable
data class ModelListResponse(
  @SerialName("object") val objectType: String = "list",
  val data: List<ModelObject>,
)

// ---------------------------------------------------------------------------
// Error response
// ---------------------------------------------------------------------------

@Serializable
data class ApiError(val message: String, val type: String, val code: String? = null)

@Serializable
data class ApiErrorResponse(val error: ApiError)
