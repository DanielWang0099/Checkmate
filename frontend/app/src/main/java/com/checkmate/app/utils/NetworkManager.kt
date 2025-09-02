package com.checkmate.app.utils

import android.content.Context
import com.checkmate.app.data.*
import com.checkmate.app.network.ApiService
import com.checkmate.app.network.WebSocketClient
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Manages network communication with the backend service.
 */
class NetworkManager(private val context: Context) {

    private val apiService = ApiService.getInstance()
    private var webSocketClient: WebSocketClient? = null
    private val sessionManager = SessionManager.getInstance(context)
    private var networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect(): Boolean {
        return try {
            val sessionState = sessionManager.getCurrentSessionState()
            val sessionId = sessionState?.sessionId
            
            if (sessionId != null) {
                Timber.d("Connecting WebSocket for session: $sessionId")
                
                webSocketClient = WebSocketClient(
                    sessionId = sessionId,
                    onNotification = { notification ->
                        networkScope.launch {
                            sessionManager.handleNotification(notification)
                        }
                    },
                    onSessionEnd = { reason ->
                        networkScope.launch {
                            sessionManager.handleSessionEnd(reason)
                        }
                    },
                    onError = { error ->
                        networkScope.launch {
                            sessionManager.handleError(error)
                        }
                    }
                )
                
                webSocketClient?.connect()
                true
            } else {
                Timber.e("No active session ID for WebSocket connection")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error connecting to backend")
            false
        }
    }

    suspend fun disconnect() {
        try {
            Timber.d("Disconnecting from backend")
            webSocketClient?.disconnect()
            webSocketClient = null
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting from backend")
        }
    }

    suspend fun sendFrameBundle(frameBundle: FrameBundle): Boolean {
        return try {
            webSocketClient?.sendFrameBundle(frameBundle) ?: false
        } catch (e: Exception) {
            Timber.e(e, "Error sending frame bundle")
            false
        }
    }

    fun isConnected(): Boolean {
        return webSocketClient?.isConnected() ?: false
    }

    fun cleanup() {
        networkScope.cancel()
        runBlocking {
            disconnect()
        }
    }
}
