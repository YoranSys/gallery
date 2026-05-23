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
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AGWebViewHelper"

/**
 * Helper class for extracting and simplifying web page content using WebView.
 * Performs readability extraction to remove boilerplate and return clean text.
 */
class WebViewHelper(private val context: Context) {

  /**
   * JavaScript code to extract readable content from a webpage.
   * Returns a JSON object with title, main content, and metadata.
   */
  private val readabilityScript = """
    (function() {
      var doc = document;
      var body = doc.body;
      if (!body) return { title: doc.title || '', content: '', length: 0 };
      
      var selectors = ['article', 'main', '.main-content', '.article', '.post', '.content', '[role="main"]', '[role="article"]'];
      var contentElement = null;
      for (var i = 0; i < selectors.length; i++) {
        contentElement = doc.querySelector(selectors[i]);
        if (contentElement) break;
      }
      var element = contentElement || body;
      var clone = element.cloneNode(true);
      
      var unwantedTags = ['script', 'style', 'nav', 'footer', 'header', 'aside', 'iframe', 'noscript'];
      unwantedTags.forEach(function(tag) {
        var elements = clone.getElementsByTagName(tag);
        while (elements.length > 0) {
          elements[0].parentNode.removeChild(elements[0]);
        }
      });
      
      var unwantedClasses = ['ad', 'banner', 'sidebar', 'navigation', 'menu', 'toolbar', 'cookie'];
      unwantedClasses.forEach(function(cls) {
        var elements = clone.querySelectorAll('.' + cls);
        elements.forEach(function(el) {
          el.parentNode.removeChild(el);
        });
      });
      
      var content = clone.textContent || '';
      content = content.replace(/\s+/g, ' ').trim();
      content = content.replace(/Copyright[\s\S]*?(?=\n|\r|$)/gi, '');
      content = content.replace(/All rights reserved[\s\S]*?(?=\n|\r|$)/gi, '');
      content = content.replace(/Privacy Policy[\s\S]*?(?=\n|\r|$)/gi, '');
      content = content.replace(/Terms of Service[\s\S]*?(?=\n|\r|$)/gi, '');
      content = content.replace(/Cookie Policy[\s\S]*?(?=\n|\r|$)/gi, '');
      
      var maxLength = 10000;
      if (content.length > maxLength) {
        content = content.substring(0, maxLength) + '...';
      }
      
      return {
        title: doc.title || '',
        content: content,
        url: window.location.href,
        length: content.length
      };
    })()
  """

  /**
   * Simplifies HTML by removing scripts, styles, comments, and excessive whitespace.
   */
  private val simplifyScript = """
    (function() {
      var doc = document;
      var body = doc.body;
      if (!body) return { title: doc.title || '', content: '', length: 0 };
      var clone = body.cloneNode(true);
      
      ['script', 'style', 'noscript', 'link'].forEach(function(tag) {
        var elements = clone.getElementsByTagName(tag);
        while (elements.length > 0) {
          elements[0].parentNode.removeChild(elements[0]);
        }
      });
      
      var content = clone.textContent || '';
      content = content.replace(/\s+/g, ' ').trim();
      
      return {
        title: doc.title || '',
        content: content,
        url: window.location.href,
        length: content.length
      };
    })()
  """

  /**
   * Extracts web page content and returns simplified text.
   * 
   * @param url The URL to load
   * @param maxChars Maximum characters to return (default: 2000)
   * @param useReadability If true, uses advanced readability extraction (default: true)
   * @return Map containing extracted content and metadata
   */
  suspend fun extractPageContent(
    url: String,
    maxChars: Int = 2000,
    useReadability: Boolean = true
  ): Map<String, Any> {
    return withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation ->
        var resumed = false
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null

        continuation.invokeOnCancellation {
          handler.removeCallbacksAndMessages(null)
          webView?.destroy()
        }

        try {
          webView = WebView(context).apply {
            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              allowFileAccess = false
              loadWithOverviewMode = true
              useWideViewPort = true
            }
          }

          val script = if (useReadability) readabilityScript else simplifyScript

          webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
              if (resumed) return

              handler.removeCallbacksAndMessages(null)

              try {
                view?.evaluateJavascript(script) { jsonResult ->
                  if (resumed) return@evaluateJavascript
                  try {
                    val result = parseResultJson(jsonResult)
                    val title = result.optString("title", "")
                    val content = result.optString("content", "")
                    val pageUrl = result.optString("url", "")

                    val truncatedContent = if (content.length > maxChars) {
                      content.take(maxChars) + "..."
                    } else {
                      content
                    }

                    resumed = true
                    continuation.resume(mapOf(
                      "url" to pageUrl,
                      "title" to title,
                      "content" to truncatedContent,
                      "content_type" to "text",
                      "truncated" to (content.length > maxChars),
                      "original_length" to content.length,
                      "returned_length" to truncatedContent.length
                    ))
                  } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebView result", e)
                    resumed = true
                    continuation.resumeWithException(e)
                  }
                }
              } catch (e: Exception) {
                Log.e(TAG, "Error evaluating JavaScript", e)
                resumed = true
                continuation.resumeWithException(e)
              }
            }

            override fun onReceivedHttpError(
              view: WebView?,
              request: WebResourceRequest?,
              errorResponse: WebResourceResponse?
            ) {
              if (resumed) return
              handler.removeCallbacksAndMessages(null)
              resumed = true
              continuation.resumeWithException(
                Exception("HTTP ${errorResponse?.statusCode} loading page: $url")
              )
            }

            override fun onReceivedError(
              view: WebView?,
              request: WebResourceRequest?,
              error: WebResourceError?
            ) {
              super.onReceivedError(view, request, error)
              if (resumed) return
              handler.removeCallbacksAndMessages(null)
              resumed = true
              continuation.resumeWithException(
                Exception("Failed to load page: ${error?.description}")
              )
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
              view: WebView?,
              errorCode: Int,
              description: String?,
              failingUrl: String?
            ) {
              if (resumed) return
              handler.removeCallbacksAndMessages(null)
              resumed = true
              continuation.resumeWithException(
                Exception("Failed to load page: $description")
              )
            }
          }

          webView.loadUrl(url)

          handler.postDelayed({
            if (resumed) return@postDelayed
            resumed = true
            continuation.resumeWithException(
              Exception("Timeout loading page: $url")
            )
          }, 30000)

        } catch (e: Exception) {
          Log.e(TAG, "Error in WebView extraction", e)
          handler.removeCallbacksAndMessages(null)
          if (!resumed) {
            resumed = true
            continuation.resumeWithException(e)
          }
          webView?.destroy()
        }
      }
    }
  }

  /**
   * Parses the JSON string returned from JavaScript evaluation.
   * evaluateJavascript JSON-encodes the result, so a JS object like
   * `{title:"x"}` comes back as `"{\"title\":\"x\"}"`. This handles
   * both the quoted and unquoted cases.
   */
  private fun parseResultJson(jsonString: String?): JSONObject {
    if (jsonString.isNullOrEmpty() || jsonString == "null") {
      return JSONObject().apply {
        put("title", "")
        put("content", "")
        put("url", "")
      }
    }

    return try {
      JSONObject(jsonString)
    } catch (_: Exception) {
      try {
        val unquoted = jsonString.removeSurrounding("\"")
          .replace("\\\"", "\"")
          .replace("\\\\", "\\")
        JSONObject(unquoted)
      } catch (_: Exception) {
        Log.w(TAG, "Failed to parse JSON result: $jsonString")
        JSONObject().apply {
          put("title", "")
          put("content", "")
          put("url", "")
        }
      }
    }
  }
}
