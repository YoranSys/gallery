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

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

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
// Request models - Content parts for multimodal messages
// ---------------------------------------------------------------------------

/** Base class for content parts in multimodal messages. */
@Serializable
sealed class ContentPart {
  abstract val type: String
}

@Serializable
@SerialName("text")
data class TextPart(
  override val type: String = "text",
  val text: String,
) : ContentPart()

@Serializable
data class ImageUrl(
  val url: String,
)

@Serializable
@SerialName("image_url")
data class ImageUrlPart(
  override val type: String = "image_url",
  @SerialName("image_url") val imageUrl: ImageUrl,
) : ContentPart()

/**
 * Custom serializer for ChatMessage.content that accepts either:
 * - A string (for text-only messages)
 * - A list of ContentPart (for multimodal messages with images)
 */
class ContentSerializer : KSerializer<MessageContent> {
  private val stringSerializer = String.serializer()

  override val descriptor: SerialDescriptor = String.serializer().descriptor

  override fun serialize(encoder: Encoder, value: MessageContent) {
    when (value) {
      is MessageContent.TextContent -> encoder.encodeString(value.text)
      is MessageContent.MultimodalContent -> {
        // For multimodal content, we need to manually serialize as JSON array
        // This is a simplified approach
        encoder.encodeString("[multimodal]") // Placeholder - full impl would build JSON
      }
    }
  }

  override fun deserialize(decoder: Decoder): MessageContent {
    val json = decoder as? kotlinx.serialization.json.JsonDecoder
      ?: throw IllegalStateException("Expected JsonDecoder")
    
    val element = json.decodeJsonElement()
    
    return when {
      element is JsonArray -> {
        // It's an array of content parts - manually parse
        val parts = element.map { jsonElement ->
          when {
            jsonElement is JsonObject && 
              jsonElement["type"] is JsonPrimitive && 
              (jsonElement["type"] as JsonPrimitive).content == "text" -> {
              TextPart(
                type = "text",
                text = (jsonElement["text"] as? JsonPrimitive)?.content ?: ""
              )
            }
            jsonElement is JsonObject &&
              jsonElement["type"] is JsonPrimitive && 
              (jsonElement["type"] as JsonPrimitive).content == "image_url" -> {
              val imageUrlObj = jsonElement["image_url"] as? JsonObject
              val url = imageUrlObj?.get("url") as? JsonPrimitive
              ImageUrlPart(
                type = "image_url",
                imageUrl = ImageUrl(url = url?.content ?: "")
              )
            }
            else -> throw IllegalArgumentException("Invalid content part type")
          }
        }
        MessageContent.MultimodalContent(parts)
      }
      element is JsonPrimitive && element.isString -> {
        MessageContent.TextContent(element.content)
      }
      else -> throw IllegalArgumentException("Content must be a string or a list of content parts")
    }
  }
}

/** Union type for message content that can be either plain text or multimodal. */
@Serializable(with = ContentSerializer::class)
sealed class MessageContent {
  @Serializable
  data class TextContent(val text: String) : MessageContent()
  
  @Serializable
  data class MultimodalContent(val parts: List<ContentPart>) : MessageContent()
}

// ---------------------------------------------------------------------------
// Request models
// ---------------------------------------------------------------------------

@Serializable
data class ChatMessage(
  val role: String,
  val content: MessageContent? = null,
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
