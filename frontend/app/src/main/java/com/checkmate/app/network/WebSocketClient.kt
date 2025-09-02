package com.checkmate.app.network

import com.checkmate.app.data.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import timber.log.Timber
import java.net.URI
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client for real-time communication with the backend.
 */
class WebSocketClient(
    private val sessionId: String,
    private val onNotification: (NotificationPayload) -> Unit,
    private val onSessionEnd: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    
    private var webSocket: WebSocketClient? = null
    private val isConnected = AtomicBoolean(false)
    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()
    
    private val uri = URI.create("${AppConfig.WEBSOCKET_URL}/$sessionId")
    
    fun connect() {
        try {
            Timber.d("Connecting WebSocket to: $uri")
            
            webSocket = object : org.java_websocket.client.WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Timber.d("WebSocket connected")
                    isConnected.set(true)
                    startPingLoop()
                }
                
                override fun onMessage(message: String?) {
                    handleMessage(message)
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Timber.d("WebSocket closed: $code - $reason")
                    isConnected.set(false)
                    
                    if (remote && code != 1000) {
                        // Unexpected close, attempt reconnection
                        scheduleReconnect()
                    }
                }
                
                override fun onError(ex: Exception?) {
                    Timber.e(ex, "WebSocket error")
                    isConnected.set(false)
                    onError("WebSocket error: ${ex?.message}")
                }
            }
            
            webSocket?.connect()
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect WebSocket")
            onError("Connection failed: ${e.message}")
        }
    }
    
    fun disconnect() {
        try {
            isConnected.set(false)
            webSocket?.close()
            webSocket = null
            Timber.d("WebSocket disconnected")
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting WebSocket")
        }
    }
    
    fun isConnected(): Boolean = isConnected.get()
    
    suspend fun sendFrameBundle(frameBundle: FrameBundle): Boolean {
        return try {
            if (!isConnected()) {
                Timber.w("WebSocket not connected, cannot send frame bundle")
                return false
            }
            
            val message = WSMessage(
                type = WSMessageType.FRAME_BUNDLE,
                data = gson.fromJson(gson.toJson(frameBundle), Map::class.java) as Map<String, Any>,
                timestamp = Date()
            )
            
            val json = gson.toJson(message)
            webSocket?.send(json)
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Error sending frame bundle")
            false
        }
    }
    
    private fun handleMessage(message: String?) {
        try {
            if (message == null) return
            
            Timber.d("Received WebSocket message: $message")
            
            val wsMessage = gson.fromJson(message, WSMessage::class.java)
            
            when (wsMessage.type) {
                WSMessageType.NOTIFICATION -> {
                    val notification = gson.fromJson(
                        gson.toJson(wsMessage.data),
                        NotificationPayload::class.java
                    )
                    onNotification(notification)
                }
                
                WSMessageType.SESSION_END -> {
                    val reason = wsMessage.data["reason"] as? String ?: "Unknown reason"
                    onSessionEnd(reason)
                }
                
                WSMessageType.ERROR -> {
                    val error = wsMessage.data["error"] as? String ?: "Unknown error"
                    onError(error)
                }
                
                WSMessageType.PONG -> {
                    Timber.d("Received pong")
                }
                
                else -> {
                    Timber.d("Unhandled message type: ${wsMessage.type}")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling WebSocket message")
        }
    }
    
    private fun startPingLoop() {
        // Send periodic ping messages to keep connection alive
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (isConnected()) {
                    try {
                        val pingMessage = WSMessage(
                            type = WSMessageType.PING,
                            data = mapOf("timestamp" to Date()),
                            timestamp = Date()
                        )
                        
                        val json = gson.toJson(pingMessage)
                        webSocket?.send(json)
                        
                    } catch (e: Exception) {
                        Timber.e(e, "Error sending ping")
                    }
                } else {
                    cancel()
                }
            }
        }, AppConfig.WEBSOCKET_PING_INTERVAL_MS, AppConfig.WEBSOCKET_PING_INTERVAL_MS)
    }
    
    private fun scheduleReconnect() {
        Timer().schedule(object : TimerTask() {
            override fun run() {
                if (!isConnected()) {
                    Timber.d("Attempting WebSocket reconnection")
                    connect()
                }
            }
        }, AppConfig.WEBSOCKET_RECONNECT_DELAY_MS)
    }
}
