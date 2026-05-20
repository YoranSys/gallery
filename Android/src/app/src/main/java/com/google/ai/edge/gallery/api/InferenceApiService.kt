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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.android.AndroidEntryPoint
import java.net.BindException
import javax.inject.Inject

private const val TAG = "InferenceApiService"
private const val NOTIFICATION_CHANNEL_ID = "inference_api_channel"
private const val NOTIFICATION_ID = 9001

/** Intent action to start the API server. */
const val ACTION_START_API_SERVER = "com.google.ai.edge.gallery.api.START"

/** Intent action to stop the API server. */
const val ACTION_STOP_API_SERVER = "com.google.ai.edge.gallery.api.STOP"

/**
 * Foreground service that runs [InferenceApiServer] and keeps it alive while the app may
 * be backgrounded. The service shows a persistent notification with the device's current
 * WiFi IP address and port so users know where to point their clients.
 */
@AndroidEntryPoint
class InferenceApiService : Service() {

  @Inject lateinit var bridge: ApiModelBridge
  @Inject lateinit var dataStoreRepository: DataStoreRepository

  private var server: InferenceApiServer? = null

  // ---------------------------------------------------------------------------
  // Service lifecycle
  // ---------------------------------------------------------------------------

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_API_SERVER -> {
        stopServer()
        stopSelf()
        return START_NOT_STICKY
      }
      else -> startServer()
    }
    return START_STICKY
  }

  override fun onDestroy() {
    stopServer()
    super.onDestroy()
  }

  // ---------------------------------------------------------------------------
  // Server management
  // ---------------------------------------------------------------------------

  private fun startServer() {
    if (server != null) return // Already running

    val port = dataStoreRepository.readApiServerPort()
    val apiKey = { dataStoreRepository.readSecret(API_KEY_SECRET) }

    try {
      server = InferenceApiServer(bridge = bridge, getApiKey = apiKey, port = port).also {
        it.start()
      }
      val ip = getWifiIpAddress()
      Log.i(TAG, "API server started at http://$ip:$port")
      startForeground(NOTIFICATION_ID, buildNotification(ip, port))
    } catch (e: BindException) {
      Log.e(TAG, "Port $port is already in use: ${e.message}")
      startForeground(NOTIFICATION_ID, buildErrorNotification("Port $port already in use."))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start API server: ${e.message}", e)
      startForeground(NOTIFICATION_ID, buildErrorNotification("Failed to start server."))
    }
  }

  private fun stopServer() {
    server?.stop()
    server = null
  }

  // ---------------------------------------------------------------------------
  // WiFi IP helper
  // ---------------------------------------------------------------------------

  private fun getWifiIpAddress(): String {
    @Suppress("DEPRECATION")
    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Suppress("DEPRECATION")
    val ip = wifiManager.connectionInfo?.ipAddress ?: 0
    return if (ip == 0) {
      "device-ip"
    } else {
      String.format(
        "%d.%d.%d.%d",
        ip and 0xFF,
        ip shr 8 and 0xFF,
        ip shr 16 and 0xFF,
        ip shr 24 and 0xFF,
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Notification helpers
  // ---------------------------------------------------------------------------

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      NOTIFICATION_CHANNEL_ID,
      "Inference API Server",
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "Shows when the on-device inference API is running"
    }
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(channel)
  }

  private fun buildNotification(ip: String, port: Int): android.app.Notification {
    val tapIntent = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val stopIntent = PendingIntent.getService(
      this, 1,
      Intent(this, InferenceApiService::class.java).apply { action = ACTION_STOP_API_SERVER },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentTitle("Inference API running")
      .setContentText("http://$ip:$port/v1")
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentIntent(tapIntent)
      .addAction(0, "Stop", stopIntent)
      .setOngoing(true)
      .setSilent(true)
      .build()
  }

  private fun buildErrorNotification(message: String): android.app.Notification {
    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentTitle("Inference API error")
      .setContentText(message)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setSilent(true)
      .build()
  }
}
