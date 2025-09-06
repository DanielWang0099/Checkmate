package com.checkmate.app.managers

import android.content.Context
import androidx.lifecycle.asLiveData
import com.checkmate.app.data.*
import com.checkmate.app.network.CheckmateWebSocketClient
import com.checkmate.app.utils.ErrorRecoveryManager
import com.checkmate.app.utils.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized WebSocket coordinator that bridges CheckmateWebSocketClient with UI components.
 * Provides real-time event broadcasting, state synchronization, and connection lifecycle management.
 * 
 * Key Responsibilities:
 * - Coordinate WebSocket connection lifecycle
 * - Broadcast events to UI components
 * - Manage connection state synchronization
 * - Handle offline message queuing
 * - Provide connection health monitoring
 */
class WebSocketManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: WebSocketManager? = null
        
        private const val MAX_QUEUE_SIZE = 100
        
        fun getInstance(context: Context): WebSocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSocketManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Dependencies
    private val sessionManager = SessionManager.getInstance(context)
    private val errorRecoveryManager = ErrorRecoveryManager(context)
    private val connectionMutex = Mutex()
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WebSocket client instance
    private var webSocketClient: CheckmateWebSocketClient? = null

    // Connection state management
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Event broadcasting
    private val _notifications = MutableSharedFlow<NotificationPayload>(
        replay = 0,
        extraBufferCapacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val notifications: SharedFlow<NotificationPayload> = _notifications.asSharedFlow()

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(
        replay = 0,
        extraBufferCapacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    // Connection health monitoring
    private val _connectionHealth = MutableStateFlow(ConnectionHealth())
    val connectionHealth: StateFlow<ConnectionHealth> = _connectionHealth.asStateFlow()

    // Message queuing for offline scenarios
    private val messageQueue = ConcurrentLinkedQueue<QueuedMessage>()
    private val isProcessingQueue = AtomicBoolean(false)

    // Live data for UI binding
    val connectionStateLiveData = connectionState.asLiveData()
    val notificationsLiveData = notifications.asLiveData()

    /**
     * Initialize WebSocket connection with current session.
     */
    suspend fun initializeConnection(): Boolean = connectionMutex.withLock {
        try {
            val sessionState = sessionManager.sessionState.value
            
            if (!sessionState.isActive || sessionState.sessionId.isNullOrEmpty()) {
                Timber.w("Cannot initialize WebSocket - no active session")
                false
            } else {
                Timber.d("Initializing WebSocket connection for session: ${sessionState.sessionId}")
                
                // Create new WebSocket client with event handlers
                webSocketClient = CheckmateWebSocketClient(
                    sessionId = sessionState.sessionId,
                    onNotification = ::handleNotificationEvent,
                    onSessionEnd = ::handleSessionEndEvent,
                    onSessionStatus = ::handleSessionStatusEvent,
                    onConnectionStateChange = ::handleConnectionStateChange,
                    onError = ::handleErrorEvent
                )
                
                // Start connection
                webSocketClient?.connect()
                
                // Update health monitoring
                updateConnectionHealth(
                    isConnected = false,
                    lastConnectAttempt = System.currentTimeMillis(),
                    retryCount = 0
                )
                
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize WebSocket connection")
            handleConnectionError(e)
            false
        }
    }

    /**
     * Gracefully disconnect WebSocket connection.
     */
    suspend fun disconnectConnection() = connectionMutex.withLock {
        try {
            Timber.d("Disconnecting WebSocket connection")
            
            webSocketClient?.disconnect()
            webSocketClient = null
            
            _connectionState.value = ConnectionState.DISCONNECTED
            updateConnectionHealth(
                isConnected = false,
                lastDisconnect = System.currentTimeMillis()
            )

            // Clear message queue
            messageQueue.clear()
            
            Timber.d("WebSocket disconnected successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error during WebSocket disconnection")
        }
    }

    /**
     * Send notification message through WebSocket.
     */
    suspend fun sendNotification(notification: NotificationPayload): Boolean {
        return try {
            if (isConnected()) {
                val wsMessage = WSMessage(
                    type = WSMessageType.NOTIFICATION,
                    data = mapOf<String, Any>(
                        "type" to "notification",
                        "color" to notification.color.name,
                        "shortText" to notification.shortText,
                        "details" to (notification.details ?: ""),
                        "sources" to notification.sources.mapIndexed { index, source ->
                            mapOf<String, Any>(
                                "id" to index.toString(),
                                "url" to source.url,
                                "title" to (source.title ?: ""),
                                "tier" to source.tier.name
                            )
                        },
                        "confidence" to notification.confidence,
                        "severity" to notification.severity,
                        "shouldNotify" to notification.shouldNotify
                    ),
                    timestamp = Date()
                )
                webSocketClient?.sendMessage(wsMessage) ?: false
            } else {
                queueMessage(QueuedMessage.NotificationMessage(notification))
                Timber.d("Notification queued - connection not available")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error sending notification")
            false
        }
    }

    /**
     * Send session control message.
     */
    suspend fun sendSessionControl(action: String, data: Map<String, Any>): Boolean {
        return try {
            if (isConnected()) {
                val messageType = when (action) {
                    "start" -> WSMessageType.SESSION_START
                    "stop" -> WSMessageType.SESSION_STOP
                    "status" -> WSMessageType.SESSION_STATUS
                    else -> WSMessageType.SESSION_STATUS
                }
                
                val wsMessage = WSMessage(
                    type = messageType,
                    data = mapOf(
                        "type" to action,
                        "action" to action
                    ) + data,
                    timestamp = Date()
                )
                webSocketClient?.sendMessage(wsMessage) ?: false
            } else {
                queueMessage(QueuedMessage.SessionMessage(action, data))
                Timber.d("Session control queued - connection not available")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error sending session control")
            false
        }
    }

    /**
     * Send frame bundle message.
     */
    suspend fun sendFrameBundle(frameBundle: FrameBundle): Boolean {
        return try {
            if (isConnected()) {
                webSocketClient?.sendFrameBundle(frameBundle) ?: false
            } else {
                queueMessage(QueuedMessage.FrameMessage(frameBundle))
                Timber.d("Frame bundle queued - connection not available")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error sending frame bundle")
            false
        }
    }

    /**
     * Send generic message (for audio streaming and other custom messages)
     */
    suspend fun sendMessage(message: Map<String, Any>): Boolean {
        return try {
            if (isConnected()) {
                // Extract type from message and convert to WSMessage
                val messageType = when (message["type"] as? String) {
                    "frame_bundle" -> WSMessageType.FRAME_BUNDLE
                    "notification" -> WSMessageType.NOTIFICATION
                    "session_end" -> WSMessageType.SESSION_END
                    "session_start" -> WSMessageType.SESSION_START
                    "session_stop" -> WSMessageType.SESSION_STOP
                    "session_status" -> WSMessageType.SESSION_STATUS
                    "error" -> WSMessageType.ERROR
                    "ping" -> WSMessageType.PING
                    "pong" -> WSMessageType.PONG
                    "heartbeat" -> WSMessageType.HEARTBEAT
                    else -> {
                        Timber.w("Unknown message type: ${message["type"]}")
                        WSMessageType.NOTIFICATION // Default fallback
                    }
                }
                
                val wsMessage = WSMessage(
                    type = messageType,
                    data = message,
                    timestamp = Date()
                )
                
                webSocketClient?.sendMessage(wsMessage) ?: false
            } else {
                // Could queue generic messages if needed
                Timber.d("Message queued - connection not available")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error sending message through WebSocket")
            false
        }
    }

    /**
     * Check if WebSocket is currently connected.
     */
    fun isConnected(): Boolean {
        return webSocketClient?.isConnected() ?: false
    }

    /**
     * Get current connection state.
     */
    fun getConnectionState(): ConnectionState {
        return _connectionState.value
    }

    /**
     * Reconnect WebSocket if disconnected.
     */
    suspend fun reconnect(): Boolean {
        return try {
            disconnectConnection()
            delay(1000) // Brief delay before reconnecting
            initializeConnection()
        } catch (e: Exception) {
            Timber.e(e, "Error during WebSocket reconnection")
            false
        }
    }

    // Event handlers
    private fun handleNotificationEvent(notification: NotificationPayload) {
        managerScope.launch {
            try {
                _notifications.emit(notification)
                Timber.d("Notification broadcasted")
                
                // Update session state if needed
                sessionManager.handleNotification(notification)
                
            } catch (e: Exception) {
                Timber.e(e, "Error handling notification event")
            }
        }
    }

    private fun handleSessionEndEvent(sessionId: String) {
        managerScope.launch {
            try {
                _sessionEvents.emit(SessionEvent.SessionEnded(sessionId))
                Timber.d("Session end event broadcasted: $sessionId")
                
                // Update session manager
                sessionManager.handleSessionEnd(sessionId)
                
                // Disconnect WebSocket
                disconnectConnection()
            } catch (e: Exception) {
                Timber.e(e, "Error handling session end event")
            }
        }
    }

    private fun handleSessionStatusEvent(status: Map<String, Any>) {
        managerScope.launch {
            try {
                _sessionEvents.emit(SessionEvent.StatusUpdate(status))
                Timber.d("Session status event broadcasted")
                
                // Update session manager
                sessionManager.handleSessionStatus(status)
            } catch (e: Exception) {
                Timber.e(e, "Error handling session status event")
            }
        }
    }

    private fun handleConnectionStateChange(newState: ConnectionState) {
        managerScope.launch {
            try {
                val previousState = _connectionState.value
                _connectionState.value = newState
                
                Timber.d("Connection state changed: $previousState -> $newState")
                
                // Update health monitoring
                updateConnectionHealth(
                    isConnected = newState == ConnectionState.CONNECTED,
                    connectionState = newState
                )
                
                // Process queued messages if connected
                if (newState == ConnectionState.CONNECTED) {
                    processQueuedMessages()
                }
                
                // Update session manager
                sessionManager.updateConnectionState(newState)
            } catch (e: Exception) {
                Timber.e(e, "Error handling connection state change")
            }
        }
    }

    private fun handleConnectionError(error: Throwable) {
        managerScope.launch {
            try {
                val errorMessage = error.message ?: "Unknown WebSocket error"
                
                updateConnectionHealth(
                    isConnected = false,
                    lastError = errorMessage,
                    errorCount = _connectionHealth.value.totalErrors + 1
                )
                
                // Emit error event
                handleErrorEvent(ErrorResponse(
                    errorType = ErrorType.NETWORK_ERROR,
                    severity = ErrorSeverity.HIGH,
                    message = errorMessage,
                    timestamp = Date()
                ))
            } catch (e: Exception) {
                Timber.e(e, "Error handling connection error")
            }
        }
    }

    private fun handleErrorEvent(error: ErrorResponse?) {
        // Error recovery is handled by ErrorRecoveryManager
        error?.let { errorResponse ->
            // Convert ErrorResponse to appropriate error handling
            val throwable = Exception(errorResponse.message)
            val context = ErrorRecoveryManager.ErrorContext(
                operation = "websocket_connection",
                sessionId = sessionManager?.getCurrentSessionState()?.sessionId,
                errorType = errorResponse.errorType.name
            )
            
            // Use executeWithRecovery for proper error handling
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    errorRecoveryManager.executeWithRecovery(
                        operation = { /* No operation to retry for error event */ },
                        operationName = "websocket_error_handling",
                        sessionId = context.sessionId
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Error in error recovery handling")
                }
            }
        }
    }

    private fun queueMessage(message: QueuedMessage) {
        if (messageQueue.size >= MAX_QUEUE_SIZE) {
            messageQueue.poll() // Remove oldest message
        }
        messageQueue.offer(message)
    }

    private suspend fun processQueuedMessages() {
        if (!isProcessingQueue.compareAndSet(false, true)) {
            return // Already processing
        }
        
        try {
            while (messageQueue.isNotEmpty() && isConnected()) {
                val message = messageQueue.poll() ?: break
                
                when (message) {
                    is QueuedMessage.NotificationMessage -> {
                        sendNotification(message.payload)
                    }
                    is QueuedMessage.SessionMessage -> {
                        sendSessionControl(message.action, message.data)
                    }
                    is QueuedMessage.FrameMessage -> {
                        webSocketClient?.sendFrameBundle(message.frameBundle)
                    }
                }
                
                delay(10) // Small delay between messages
            }
            
            // Clear any remaining messages if still disconnected
            if (!isConnected() && messageQueue.isNotEmpty()) {
                messageQueue.clear()
            }
        } finally {
            isProcessingQueue.set(false)
        }
    }

    private fun updateConnectionHealth(
        isConnected: Boolean? = null,
        lastConnectAttempt: Long? = null,
        lastDisconnect: Long? = null,
        retryCount: Int? = null,
        errorCount: Int? = null,
        lastError: String? = null,
        connectionState: ConnectionState? = null
    ) {
        val current = _connectionHealth.value
        _connectionHealth.value = current.copy(
            isConnected = isConnected ?: current.isConnected,
            connectionState = connectionState ?: current.connectionState,
            queuedMessages = messageQueue.size,
            totalErrors = errorCount ?: current.totalErrors,
            lastError = lastError ?: current.lastError,
            retryCount = retryCount ?: current.retryCount,
            uptime = if (isConnected == true) System.currentTimeMillis() - (lastConnectAttempt ?: current.uptime) else current.uptime
        )
    }

    /**
     * Get current connection statistics.
     */
    fun getCurrentMetrics(): ConnectionStats {
        return ConnectionStats(
            isConnected = isConnected(),
            connectionState = _connectionState.value,
            queuedMessages = messageQueue.size,
            totalErrors = _connectionHealth.value.totalErrors,
            lastError = _connectionHealth.value.lastError,
            retryCount = _connectionHealth.value.retryCount,
            uptime = _connectionHealth.value.uptime
        )
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        managerScope.launch {
            disconnectConnection()
        }
        messageQueue.clear()
    }

    // Data classes for queued messages
    sealed class QueuedMessage {
        data class NotificationMessage(val payload: NotificationPayload) : QueuedMessage()
        data class SessionMessage(val action: String, val data: Map<String, Any>) : QueuedMessage()
        data class FrameMessage(val frameBundle: FrameBundle) : QueuedMessage()
    }

    // Data classes for session events
    sealed class SessionEvent {
        data class SessionEnded(val sessionId: String) : SessionEvent()
        data class StatusUpdate(val status: Map<String, Any>) : SessionEvent()
        data class SessionStarted(val sessionId: String) : SessionEvent()
        data class Error(val error: ErrorResponse) : SessionEvent()
    }

    data class ConnectionHealth(
        val isConnected: Boolean = false,
        val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
        val queuedMessages: Int = 0,
        val totalErrors: Int = 0,
        val lastError: String? = null,
        val retryCount: Int = 0,
        val uptime: Long = 0
    )

    data class ConnectionStats(
        val isConnected: Boolean,
        val connectionState: ConnectionState,
        val queuedMessages: Int,
        val totalErrors: Int,
        val lastError: String?,
        val retryCount: Int,
        val uptime: Long
    )
}
