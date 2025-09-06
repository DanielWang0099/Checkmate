package com.checkmate.app.utils

import android.content.Context
import com.checkmate.app.data.*
import com.checkmate.app.network.ApiService
import com.checkmate.app.managers.WebSocketManager
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Enhanced network manager with comprehensive WebSocket lifecycle management.
 * Now delegates WebSocket operations to the centralized WebSocketManager.
 */
class NetworkManager(private val context: Context) {

    private val apiService = ApiService.getInstance()
    private val webSocketManager = WebSocketManager.getInstance(context)
    private val sessionManager = SessionManager.getInstance(context)
    private var networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient()
    private val baseUrl = AppConfig.BACKEND_BASE_URL

    /**
     * Connect WebSocket through centralized WebSocketManager
     */
    suspend fun connectWebSocket(): Boolean {
        return try {
            val sessionState = sessionManager.getCurrentSessionState()
            val sessionId = sessionState?.sessionId
            
            if (sessionId != null) {
                Timber.d("Connecting enhanced WebSocket for session: $sessionId")
                return webSocketManager.initializeConnection()
            } else {
                Timber.e("No active session ID for WebSocket connection")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error connecting to backend")
            false
        }
    }

    /**
     * Disconnect WebSocket through centralized WebSocketManager
     */
    suspend fun disconnectWebSocket() {
        try {
            Timber.d("Disconnecting from backend")
            webSocketManager.disconnectConnection()
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting from backend")
        }
    }

    /**
     * Send frame bundle through WebSocketManager
     */
    suspend fun sendFrameBundle(frameBundle: FrameBundle): Boolean {
        return try {
            val message: Map<String, Any> = mapOf(
                "type" to "frame_bundle",
                "session_id" to frameBundle.sessionId,
                "timestamp" to frameBundle.timestamp,
                "tree_summary" to frameBundle.treeSummary,
                "ocr_text" to frameBundle.ocrText,
                "has_image" to frameBundle.hasImage,
                "image_ref" to (frameBundle.imageRef ?: ""),
                "audio_transcript_delta" to frameBundle.audioTranscriptDelta,
                "device_hints" to frameBundle.deviceHints
            )
            webSocketManager.sendMessage(message)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error sending frame bundle")
            false
        }
    }
    
    /**
     * Send session start through WebSocketManager
     */
    suspend fun sendSessionStart(settings: SessionSettings): Boolean {
        return try {
            val message: Map<String, Any> = mapOf(
                "type" to "session_start",
                "settings" to mapOf<String, Any>(
                    "session_type" to settings.sessionType,
                    "strictness" to settings.strictness,
                    "notify" to settings.notify
                ),
                "timestamp" to System.currentTimeMillis()
            )
            webSocketManager.sendMessage(message)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error sending session start")
            false
        }
    }
    
    /**
     * Send session stop through WebSocketManager
     */
    suspend fun sendSessionStop(): Boolean {
        return try {
            val sessionState = sessionManager.getCurrentSessionState()
            val message: Map<String, Any> = mapOf(
                "type" to "session_stop",
                "session_token" to (sessionState?.sessionId ?: ""),
                "timestamp" to System.currentTimeMillis()
            )
            webSocketManager.sendMessage(message)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error sending session stop")
            false
        }
    }
    
    suspend fun uploadImage(imageBytes: ByteArray, sessionId: String, filename: String): String? {
        return try {
            if (baseUrl.isEmpty()) {
                Timber.w("Base URL not set, saving image locally")
                return saveImageLocally(imageBytes, filename)
            }

            val uploadUrl = "$baseUrl/sessions/$sessionId/upload-image"
            
            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .addHeader("Content-Type", "application/octet-stream")
                .build()

            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "{}")
                val imageUrl = jsonResponse.optString("image_url")
                
                if (imageUrl.isNotEmpty()) {
                    Timber.d("Image uploaded successfully: $imageUrl")
                    imageUrl
                } else {
                    Timber.e("Invalid response from upload endpoint")
                    saveImageLocally(imageBytes, filename)
                }
            } else {
                Timber.e("Upload failed with status: ${response.code}")
                saveImageLocally(imageBytes, filename)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error uploading image")
            saveImageLocally(imageBytes, filename)
        }
    }

    private fun saveImageLocally(imageBytes: ByteArray, filename: String): String? {
        return try {
            val tempDir = java.io.File(context.cacheDir, "temp_images")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }

            val imageFile = java.io.File(tempDir, filename)
            imageFile.writeBytes(imageBytes)
            
            // Return a local file reference
            "file://${imageFile.absolutePath}"
            
        } catch (e: Exception) {
            Timber.e(e, "Error saving image locally")
            null
        }
    }

    /**
     * Check if WebSocket is connected through WebSocketManager
     */
    fun isConnected(): Boolean {
        return webSocketManager.isConnected()
    }
    
    /**
     * Get WebSocket connection state through WebSocketManager
     */
    fun getConnectionState(): ConnectionState {
        return webSocketManager.getConnectionState()
    }

    fun cleanup() {
        networkScope.cancel()
        runBlocking {
            disconnectWebSocket()
        }
    }
    
    // Legacy support - will be deprecated
    @Deprecated("Use connectWebSocket() instead", ReplaceWith("connectWebSocket()"))
    suspend fun connect(): Boolean = connectWebSocket()
    
    @Deprecated("Use disconnectWebSocket() instead", ReplaceWith("disconnectWebSocket()"))
    suspend fun disconnect() = disconnectWebSocket()
}
