package com.checkmate.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.checkmate.app.R
import com.checkmate.app.data.ConnectionState
import com.checkmate.app.databinding.ViewWebsocketStatusBinding
import com.checkmate.app.managers.WebSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import timber.log.Timber

/**
 * UI component that displays real-time WebSocket connection status
 * and handles user interactions for connection management.
 * 
 * Features:
 * - Real-time connection status indicator
 * - Automatic reconnection controls
 * - Connection quality metrics
 * - Error state notifications
 */
class WebSocketStatusIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewWebsocketStatusBinding
    private val webSocketManager = WebSocketManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    init {
        binding = ViewWebsocketStatusBinding.inflate(
            LayoutInflater.from(context), this, true
        )
        setupViews()
    }

    private fun setupViews() {
        binding.apply {
            // Setup reconnect button
            btnReconnect.setOnClickListener {
                triggerReconnection()
            }
            
            // Setup connection info toggle
            tvConnectionStatus.setOnClickListener {
                toggleConnectionDetails()
            }
        }
    }

    /**
     * Start observing WebSocket connection state changes
     */
    fun startObserving(lifecycleScope: LifecycleCoroutineScope) {
        lifecycleScope.launch {
            webSocketManager.connectionState.collect { state ->
                updateConnectionStatus(state)
            }
        }
        
        scope.launch {
            webSocketManager.connectionState.collect { state ->
                updateConnectionStatus(state)
            }
        }
        
        scope.launch {
            webSocketManager.connectionHealth.collect { health ->
                updateConnectionHealth(health)
            }
        }
        
        scope.launch {
            webSocketManager.sessionEvents.collect { event ->
                handleSessionEvent(event)
            }
        }
    }

    /**
     * Update UI based on connection state
     */
    private fun updateConnectionStatus(state: ConnectionState) {
        binding.apply {
            when (state) {
                ConnectionState.CONNECTED -> {
                    tvConnectionStatus.text = "Connected"
                    tvConnectionStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.success_green)
                    )
                    indicatorDot.setBackgroundResource(R.drawable.indicator_connected)
                    btnReconnect.visibility = GONE
                    progressConnection.visibility = GONE
                }
                
                ConnectionState.CONNECTING -> {
                    tvConnectionStatus.text = "Connecting..."
                    tvConnectionStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.warning_orange)
                    )
                    indicatorDot.setBackgroundResource(R.drawable.indicator_connecting)
                    btnReconnect.visibility = GONE
                    progressConnection.visibility = VISIBLE
                }
                
                ConnectionState.DISCONNECTED -> {
                    tvConnectionStatus.text = "Disconnected"
                    tvConnectionStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.error_red)
                    )
                    indicatorDot.setBackgroundResource(R.drawable.indicator_disconnected)
                    btnReconnect.visibility = VISIBLE
                    progressConnection.visibility = GONE
                }
                
                ConnectionState.RECONNECTING -> {
                    tvConnectionStatus.text = "Reconnecting..."
                    tvConnectionStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.warning_orange)
                    )
                    indicatorDot.setBackgroundResource(R.drawable.indicator_reconnecting)
                    btnReconnect.visibility = GONE
                    progressConnection.visibility = VISIBLE
                }
                
                ConnectionState.ERROR -> {
                    tvConnectionStatus.text = "Connection Error"
                    tvConnectionStatus.setTextColor(
                        ContextCompat.getColor(context, R.color.error_red)
                    )
                    indicatorDot.setBackgroundResource(R.drawable.indicator_error)
                    btnReconnect.visibility = VISIBLE
                    progressConnection.visibility = GONE
                }
            }
        }
        
        Timber.d("WebSocket status updated: $state")
    }

    /**
     * Update connection quality metrics
     */
    private fun updateConnectionMetrics(metrics: WebSocketManager.ConnectionStats) {
        binding.apply {
            if (layoutConnectionDetails.visibility == VISIBLE) {
                // Use available properties from ConnectionStats
                tvLatency.text = "${metrics.uptime}ms" // Use uptime as latency indicator
                tvMessagesSent.text = "0" // Not available in current ConnectionStats
                tvMessagesReceived.text = "0" // Not available in current ConnectionStats
                tvReconnectCount.text = metrics.retryCount.toString()
                
                // Update latency color based on uptime
                val latencyColor = when {
                    metrics.uptime < 100 -> R.color.success_green
                    metrics.uptime < 300 -> R.color.warning_orange
                    else -> R.color.error_red
                }
                tvLatency.setTextColor(ContextCompat.getColor(context, latencyColor))
            }
        }
    }

    /**
     * Update connection health metrics
     */
    private fun updateConnectionHealth(health: WebSocketManager.ConnectionHealth) {
        // Update metrics based on health data
        updateConnectionMetrics(WebSocketManager.ConnectionStats(
            isConnected = health.isConnected,
            connectionState = health.connectionState,
            queuedMessages = health.queuedMessages,
            totalErrors = health.totalErrors,
            lastError = health.lastError,
            retryCount = health.retryCount,
            uptime = health.uptime
        ))
    }

    /**
     * Handle session events
     */
    private fun handleSessionEvent(event: WebSocketManager.SessionEvent) {
        when (event) {
            is WebSocketManager.SessionEvent.Error -> {
                showConnectionError("Session error: ${event.error.message}")
            }
            is WebSocketManager.SessionEvent.SessionEnded -> {
                showConnectionError("Session ended: ${event.sessionId}")
            }
            is WebSocketManager.SessionEvent.StatusUpdate -> {
                // Handle status updates if needed
            }
            is WebSocketManager.SessionEvent.SessionStarted -> {
                // Handle session start if needed
            }
        }
    }

    /**
     * Show connection error notification
     */
    private fun showConnectionError(error: String) {
        binding.apply {
            tvErrorMessage.text = error
            layoutErrorMessage.visibility = VISIBLE
            
            // Auto-hide error after 5 seconds
            postDelayed({
                layoutErrorMessage.visibility = GONE
            }, 5000)
        }
        
        Timber.w("WebSocket connection error: $error")
    }

    /**
     * Trigger manual reconnection
     */
    private fun triggerReconnection() {
        Timber.d("Manual WebSocket reconnection triggered")
        scope.launch {
            webSocketManager.reconnect()
        }
        
        binding.btnReconnect.text = "Reconnecting..."
        binding.btnReconnect.isEnabled = false
        
        // Re-enable button after 3 seconds
        postDelayed({
            binding.btnReconnect.text = "Reconnect"
            binding.btnReconnect.isEnabled = true
        }, 3000)
    }

    /**
     * Toggle connection details visibility
     */
    private fun toggleConnectionDetails() {
        binding.apply {
            if (layoutConnectionDetails.visibility == VISIBLE) {
                layoutConnectionDetails.visibility = GONE
                iconExpand.rotation = 0f
            } else {
                layoutConnectionDetails.visibility = VISIBLE
                iconExpand.rotation = 180f
                // Refresh metrics when showing details
                updateConnectionMetrics(webSocketManager.getCurrentMetrics())
            }
        }
    }

    /**
     * Show connection status briefly (for notifications)
     */
    fun showStatusBriefly(message: String, duration: Long = 3000) {
        binding.apply {
            tvStatusMessage.text = message
            layoutStatusMessage.visibility = VISIBLE
            
            postDelayed({
                layoutStatusMessage.visibility = GONE
            }, duration)
        }
    }

    /**
     * Force update of all status indicators
     */
    fun refreshStatus() {
        updateConnectionStatus(webSocketManager.getConnectionState())
        updateConnectionMetrics(webSocketManager.getCurrentMetrics())
    }
}