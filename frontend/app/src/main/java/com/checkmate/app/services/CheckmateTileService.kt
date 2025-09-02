package com.checkmate.app.services

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.checkmate.app.R
import com.checkmate.app.data.SessionState
import com.checkmate.app.utils.SessionManager
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Quick Settings Tile for Checkmate.
 * Provides easy access to start/stop fact-checking sessions.
 */
class CheckmateTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var sessionManager: SessionManager

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager.getInstance(this)
        Timber.d("CheckmateTileService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Timber.d("CheckmateTileService destroyed")
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        Timber.d("Tile listening started")
    }

    override fun onStopListening() {
        super.onStopListening()
        Timber.d("Tile listening stopped")
    }

    override fun onClick() {
        super.onClick()
        
        serviceScope.launch {
            try {
                val isActive = sessionManager.isSessionActive()
                
                if (isActive) {
                    // Stop the current session
                    stopSession()
                } else {
                    // Start a new session
                    startSession()
                }
                
                updateTileState()
            } catch (e: Exception) {
                Timber.e(e, "Error in tile click")
                showError()
            }
        }
    }

    private suspend fun startSession() {
        Timber.d("Starting session from tile")
        
        // Check if the main service is running
        if (!CheckmateService.isRunning()) {
            // Start the foreground service
            val serviceIntent = Intent(this, CheckmateService::class.java).apply {
                action = CheckmateService.ACTION_START_SESSION
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            // Service is already running, just start a new session
            sessionManager.startNewSession()
        }
    }

    private suspend fun stopSession() {
        Timber.d("Stopping session from tile")
        
        sessionManager.stopCurrentSession()
        
        // If no other sessions are active, stop the service
        if (!sessionManager.hasActiveSessions()) {
            val serviceIntent = Intent(this, CheckmateService::class.java).apply {
                action = CheckmateService.ACTION_STOP_SERVICE
            }
            stopService(serviceIntent)
        }
    }

    private fun updateTileState() {
        serviceScope.launch {
            try {
                val isActive = sessionManager.isSessionActive()
                val sessionState = sessionManager.getCurrentSessionState()
                
                qsTile?.let { tile ->
                    tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    
                    tile.label = if (isActive) {
                        "Stop Fact Check"
                    } else {
                        "Start Fact Check"
                    }
                    
                    tile.subtitle = when {
                        isActive && sessionState != null -> {
                            "Checking ${sessionState.settings?.sessionType?.type?.name?.lowercase() ?: "content"}"
                        }
                        isActive -> "Active"
                        else -> "Tap to start"
                    }
                    
                    tile.icon = Icon.createWithResource(
                        this@CheckmateTileService,
                        if (isActive) R.drawable.ic_checkmate_active else R.drawable.ic_checkmate
                    )
                    
                    tile.updateTile()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error updating tile state")
                showError()
            }
        }
    }

    private fun showError() {
        qsTile?.let { tile ->
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "Checkmate Error"
            tile.subtitle = "Tap to retry"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_error)
            tile.updateTile()
        }
    }
}
