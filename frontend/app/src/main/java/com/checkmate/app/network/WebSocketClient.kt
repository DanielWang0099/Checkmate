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
import kotlin.concurrent.scheduleAtFixedRate

/**
 * Enhanced WebSocket client for real-time communication with the backend.
 * Supports session control, structured error handling, and heartbeat monitoring.
 */
class CheckmateWebSocketClient(
    private val sessionId: String,
    private val onNotification: (NotificationPayload) -> Unit,
    private val onSessionEnd: (String) -> Unit,
    private val onSessionStatus: (Map<String, Any>) -> Unit,
    private val onConnectionStateChange: (ConnectionState) -> Unit,
    private val onError: (ErrorResponse?) -> Unit
) {
    
    private var webSocket: WebSocketClient? = null
    private var connectionState = ConnectionState.DISCONNECTED
    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()
    
    private val uri = URI.create("${AppConfig.WEBSOCKET_URL}/$sessionId")
    private var heartbeatTimer: Timer? = null
    private var reconnectTimer: Timer? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    
    fun connect() {
        try {
            updateConnectionState(ConnectionState.CONNECTING)
            Timber.d("Connecting WebSocket to: $uri")
            
            webSocket = object : org.java_websocket.client.WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Timber.d("WebSocket connected successfully")
                    updateConnectionState(ConnectionState.CONNECTED)
                    reconnectAttempts = 0
                    startHeartbeat()
                }
                
                override fun onMessage(message: String?) {
                    handleMessage(message)
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Timber.d("WebSocket closed: code=$code, reason=$reason, remote=$remote")
                    stopHeartbeat()
                    
                    when {
                        code == 1000 -> {
                            // Normal close
                            updateConnectionState(ConnectionState.DISCONNECTED)
                        }
                        remote && reconnectAttempts < maxReconnectAttempts -> {
                            // Unexpected close, attempt reconnection
                            updateConnectionState(ConnectionState.RECONNECTING)
                            scheduleReconnect()
                        }
                        else -> {
                            updateConnectionState(ConnectionState.ERROR)
                            onError(null)
                        }
                    }
                }
                
                override fun onError(ex: Exception?) {
                    Timber.e(ex, "WebSocket error")
                    updateConnectionState(ConnectionState.ERROR)
                    
                    val errorResponse = ErrorResponse(
                        errorType = ErrorType.NETWORK_ERROR,
                        severity = ErrorSeverity.HIGH,
                        message = "WebSocket connection error: ${ex?.message}",
                        details = ex?.localizedMessage,
                        timestamp = Date()
                    )
                    onError(errorResponse)
                }
            }
            
            webSocket?.connect()
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect WebSocket")
            updateConnectionState(ConnectionState.ERROR)
            
            val errorResponse = ErrorResponse(
                errorType = ErrorType.NETWORK_ERROR,
                severity = ErrorSeverity.HIGH,
                message = "Connection failed: ${e.message}",
                details = e.localizedMessage,
                timestamp = Date()
            )
            onError(errorResponse)
        }
    }
    
    fun disconnect() {
        try {
            stopHeartbeat()
            stopReconnect()
            updateConnectionState(ConnectionState.DISCONNECTED)
            
            webSocket?.closeBlocking()
            webSocket = null
            
            Timber.d("WebSocket disconnected cleanly")
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting WebSocket")
        }
    }
    
    fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED
    
    fun getConnectionState(): ConnectionState = connectionState
    
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
            
            sendMessage(message)
        } catch (e: Exception) {
            Timber.e(e, "Error sending frame bundle")
            false
        }
    }
    
    suspend fun sendSessionStart(settings: SessionSettings): Boolean {
        return try {
            val message = WSMessage(
                type = WSMessageType.SESSION_START,
                data = gson.fromJson(gson.toJson(settings), Map::class.java) as Map<String, Any>,
                timestamp = Date()
            )
            
            sendMessage(message)
        } catch (e: Exception) {
            Timber.e(e, "Error sending session start")
            false
        }
    }
    
    suspend fun sendSessionStop(): Boolean {
        return try {
            val message = WSMessage(
                type = WSMessageType.SESSION_STOP,
                data = emptyMap(),
                timestamp = Date()
            )
            
            sendMessage(message)
        } catch (e: Exception) {
            Timber.e(e, "Error sending session stop")
            false
        }
    }
    
    internal fun sendMessage(message: WSMessage): Boolean {
        return try {
            val json = gson.toJson(message)
            webSocket?.send(json)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error sending WebSocket message")
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
                    handleNotificationMessage(wsMessage.data)
                }
                
                WSMessageType.SESSION_END -> {
                    val reason = wsMessage.data["reason"] as? String ?: "Unknown reason"
                    onSessionEnd(reason)
                }
                
                WSMessageType.SESSION_STATUS -> {
                    onSessionStatus(wsMessage.data)
                }
                
                WSMessageType.ERROR -> {
                    handleErrorMessage(wsMessage.data)
                }
                
                WSMessageType.PONG -> {
                    Timber.d("Received pong - connection alive")
                }
                
                WSMessageType.HEARTBEAT -> {
                    handleHeartbeatMessage(wsMessage.data)
                }
                
                else -> {
                    Timber.d("Unhandled message type: ${wsMessage.type}")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling WebSocket message")
        }
    }
    
    private fun handleNotificationMessage(data: Map<String, Any>) {
        try {
            val notification = gson.fromJson(
                gson.toJson(data),
                NotificationPayload::class.java
            )
            onNotification(notification)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing notification message")
        }
    }
    
    private fun handleErrorMessage(data: Map<String, Any>) {
        try {
            val errorResponse = gson.fromJson(
                gson.toJson(data),
                ErrorResponse::class.java
            )
            onError(errorResponse)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing error message")
            onError(null)
        }
    }
    
    private fun handleHeartbeatMessage(data: Map<String, Any>) {
        // Respond to heartbeat to maintain connection
        try {
            val heartbeatResponse = WSMessage(
                type = WSMessageType.HEARTBEAT,
                data = mapOf("status" to "alive"),
                timestamp = Date()
            )
            sendMessage(heartbeatResponse)
        } catch (e: Exception) {
            Timber.e(e, "Error responding to heartbeat")
        }
    }
    
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatTimer = Timer("WebSocketHeartbeat", true)
        
        heartbeatTimer?.scheduleAtFixedRate(
            AppConfig.WEBSOCKET_PING_INTERVAL_MS,
            AppConfig.WEBSOCKET_PING_INTERVAL_MS
        ) {
            if (isConnected()) {
                try {
                    val pingMessage = WSMessage(
                        type = WSMessageType.PING,
                        data = mapOf("timestamp" to Date()),
                        timestamp = Date()
                    )
                    
                    sendMessage(pingMessage)
                    
                } catch (e: Exception) {
                    Timber.e(e, "Error sending ping")
                }
            } else {
                stopHeartbeat()
            }
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }
    
    private fun scheduleReconnect() {
        stopReconnect()
        reconnectAttempts++
        
        val delay = minOf(
            AppConfig.WEBSOCKET_RECONNECT_DELAY_MS * reconnectAttempts,
            30000L // Max 30 seconds
        )
        
        Timber.d("Scheduling reconnect attempt $reconnectAttempts in ${delay}ms")
        
        reconnectTimer = Timer("WebSocketReconnect", true)
        reconnectTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (connectionState == ConnectionState.RECONNECTING) {
                    Timber.d("Attempting WebSocket reconnection (attempt $reconnectAttempts)")
                    connect()
                }
            }
        }, delay)
    }
    
    private fun stopReconnect() {
        reconnectTimer?.cancel()
        reconnectTimer = null
    }
    
    private fun updateConnectionState(newState: ConnectionState) {
        if (connectionState != newState) {
            connectionState = newState
            onConnectionStateChange(newState)
        }
    }
}
