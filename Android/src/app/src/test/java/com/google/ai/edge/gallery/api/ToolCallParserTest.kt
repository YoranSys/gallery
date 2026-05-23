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

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for tool call parsing with Tier 1 (validation) and Tier 2 (auto-fix).
 */
class ToolCallParserTest {

  // ========================================================================
  // Validation Helper (mirrors logic from InferenceApiServer)
  // ========================================================================

  private data class ValidationResult(
    val isValid: Boolean,
    val repairedText: String? = null,
    val error: String? = null
  )

  private fun validateJsonStructure(text: String): ValidationResult {
    val trimmed = text.trim()
    
    if (!trimmed.startsWith("{")) {
      return ValidationResult(isValid = false, error = "Does not start with opening brace")
    }
    
    var braceCount = 0
    var bracketCount = 0
    var quoteCount = 0
    var escapeNext = false
    var inString = false
    
    for (char in trimmed) {
      if (escapeNext) {
        escapeNext = false
        continue
      }
      
      when (char) {
        '\\' -> escapeNext = true
        '"' -> {
          quoteCount++
          inString = !inString
        }
        '{' -> if (!inString) braceCount++
        '}' -> if (!inString) braceCount--
        '[' -> if (!inString) bracketCount++
        ']' -> if (!inString) bracketCount--
      }
    }
    
    val braceBalanced = braceCount == 0
    val bracketBalanced = bracketCount == 0
    val quoteBalanced = quoteCount % 2 == 0
    
    if (braceBalanced && bracketBalanced && quoteBalanced) {
      return ValidationResult(isValid = true)
    }
    
    val errors = mutableListOf<String>()
    if (!braceBalanced) errors.add("unbalanced braces ($braceCount)")
    if (!bracketBalanced) errors.add("unbalanced brackets ($bracketCount)")
    if (!quoteBalanced) errors.add("unbalanced quotes ($quoteCount)")
    
    return ValidationResult(isValid = false, error = "Malformed JSON: " + errors.joinToString(", "))
  }

  private fun countBraces(text: String): Int {
    var inString = false
    var escapeNext = false
    var count = 0
    for (char in text) {
      if (escapeNext) { escapeNext = false; continue }
      when (char) {
        '\\' -> escapeNext = true
        '"' -> inString = !inString
        '{' -> if (!inString) count++
        '}' -> if (!inString) count--
      }
    }
    return count
  }

  private fun countBrackets(text: String): Int {
    var inString = false
    var escapeNext = false
    var count = 0
    for (char in text) {
      if (escapeNext) { escapeNext = false; continue }
      when (char) {
        '\\' -> escapeNext = true
        '"' -> inString = !inString
        '[' -> if (!inString) count++
        ']' -> if (!inString) count--
      }
    }
    return count
  }

  private fun countQuotes(text: String): Int {
    var escapeNext = false
    var count = 0
    for (char in text) {
      if (escapeNext) { escapeNext = false; continue }
      when (char) {
        '\\' -> escapeNext = true
        '"' -> count++
      }
    }
    return count
  }

  // ========================================================================
  // Tier 1: Validation Tests
  // ========================================================================

  @Test
  fun `validateJsonStructure returns valid for well-formed JSON`() {
    val validJson = "{\"name\": \"test_tool\", \"arguments\": {}}"
    val result = validateJsonStructure(validJson)
    assertTrue(result.isValid)
  }

  @Test
  fun `validateJsonStructure detects missing closing brace`() {
    val invalidJson = "{\"name\": \"test_tool\", \"arguments\": {"
    val result = validateJsonStructure(invalidJson)
    assertFalse(result.isValid)
    assertTrue(result.error?.contains("unbalanced braces") ?: false)
  }

  @Test
  fun `validateJsonStructure detects extra closing brace`() {
    val invalidJson = "{\"name\": \"test_tool\"}}"
    val result = validateJsonStructure(invalidJson)
    assertFalse(result.isValid)
    assertTrue(result.error?.contains("unbalanced braces") ?: false)
  }

  @Test
  fun `validateJsonStructure detects unbalanced brackets`() {
    val invalidJson = "{\"name\": \"test_tool\", \"arguments\": [}"
    val result = validateJsonStructure(invalidJson)
    assertFalse(result.isValid)
    assertTrue(result.error?.contains("unbalanced brackets") ?: false)
  }

  @Test
  fun `validateJsonStructure detects unbalanced quotes`() {
    val invalidJson = "{\"name\": \"test_tool, \"arguments\": {}}"
    val result = validateJsonStructure(invalidJson)
    assertFalse(result.isValid)
    assertTrue(result.error?.contains("unbalanced quotes") ?: false)
  }

  @Test
  fun `validateJsonStructure rejects non-JSON starting text`() {
    val notJson = "I am not a JSON tool call"
    val result = validateJsonStructure(notJson)
    assertFalse(result.isValid)
    assertEquals("Does not start with opening brace", result.error)
  }

  @Test
  fun `validateJsonStructure handles nested objects`() {
    val validJson = "{\"name\": \"test\", \"arguments\": {\"nested\": {\"key\": \"value\"}}}"
    val result = validateJsonStructure(validJson)
    assertTrue(result.isValid)
  }

  @Test
  fun `validateJsonStructure handles arrays`() {
    val validJson = "{\"name\": \"test\", \"arguments\": [1, 2, 3]}"
    val result = validateJsonStructure(validJson)
    assertTrue(result.isValid)
  }

  @Test
  fun `validateJsonStructure handles escaped quotes`() {
    val validJson = "{\"name\": \"test \\\"quoted\\\" value\"}"
    val result = validateJsonStructure(validJson)
    assertTrue(result.isValid)
  }

  // ========================================================================
  // Integration Tests
  // ========================================================================

  @Test
  fun `end-to-end validation and repair for missing brace`() {
    val brokenToolCall = "{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Paris\"}"
    val validation = validateJsonStructure(brokenToolCall)
    
    assertFalse(validation.isValid)
    assertTrue(validation.error?.contains("unbalanced braces") ?: false)
    
    val braceCount = countBraces(brokenToolCall)
    val repaired = attemptJsonRepair(brokenToolCall, braceCount, 0, 0)
    
    val repairValidation = validateJsonStructure(repaired)
    assertTrue(repairValidation.isValid)
  }

  @Test
  fun `end-to-end validation and repair for extra brace`() {
    val brokenToolCall = "{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Paris\"}}}"
    val validation = validateJsonStructure(brokenToolCall)
    
    assertFalse(validation.isValid)
    
    val braceCount = countBraces(brokenToolCall)
    val repaired = attemptJsonRepair(brokenToolCall, braceCount, 0, 0)
    
    val repairValidation = validateJsonStructure(repaired)
    assertTrue(repairValidation.isValid)
  }

  // ========================================================================
  // Edge Cases
  // ========================================================================

  @Test
  fun `handles empty JSON object`() {
    val validJson = "{}"
    val result = validateJsonStructure(validJson)
    assertTrue(result.isValid)
  }

  @Test
  fun `handles JSON with newlines`() {
    val validJson = """
      |
      {
        "name": "test",
        "arguments": {}
      }
      |""".trimMargin()
    val result = validateJsonStructure(validJson)
    assertTrue(result.isValid)
  }

  @Test
  fun `handles JSON with extra whitespace`() {
    val validJson = "  {  \"name\"  :  \"test\"  ,  \"arguments\"  :  {}  }  "
    val result = validateJsonStructure(validJson)
    assertTrue(result.isValid)
  }

  @Test
  fun `handles complex nested structure`() {
    val validJson = """
      {
        "name": "complex_tool",
        "arguments": {
          "nested": {
            "array": [1, 2, {"key": "value"}],
            "string": "test \"quoted\" string"
          }
        }
      }
    """.trimIndent()
    val result = validateJsonStructure(validJson)
    assertTrue(result.isValid)
  }

  // ========================================================================
  // Repair Helper (mirrors logic from InferenceApiServer)
  // ========================================================================

  private fun attemptJsonRepair(
    text: String,
    braceCount: Int,
    bracketCount: Int,
    quoteCount: Int
  ): String {
    var repaired = text
    
    if (braceCount < 0) {
      var closeBracesToRemove = -braceCount
      var lastIndex = repaired.length - 1
      while (closeBracesToRemove > 0 && lastIndex >= 0) {
        if (repaired[lastIndex] == '}') {
          repaired = repaired.removeRange(lastIndex, lastIndex + 1)
          closeBracesToRemove--
        }
        lastIndex--
      }
    }
    
    if (braceCount > 0) {
      repeat(braceCount) { repaired += "}" }
    }
    
    if (bracketCount < 0) {
      var closeBracketsToRemove = -bracketCount
      var lastIndex = repaired.length - 1
      while (closeBracketsToRemove > 0 && lastIndex >= 0) {
        if (repaired[lastIndex] == ']') {
          repaired = repaired.removeRange(lastIndex, lastIndex + 1)
          closeBracketsToRemove--
        }
        lastIndex--
      }
    }
    
    if (bracketCount > 0) {
      repeat(bracketCount) { repaired += "]" }
    }
    
    if (quoteCount % 2 != 0) {
      repaired += '"'
    }
    
    return repaired
  }
}
