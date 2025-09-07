package com.checkmate.app.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.checkmate.app.CheckmateApplication
import com.checkmate.app.R
import com.checkmate.app.data.AppConfig
import com.checkmate.app.utils.SessionManager
import com.checkmate.app.utils.CapturePipeline
import com.checkmate.app.utils.NetworkManager
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Main foreground service for Checkmate.
 * Manages the background fact-checking session lifecycle.
 */
class CheckmateService : LifecycleService() {

    companion object {
        const val ACTION_START_SERVICE = "com.checkmate.app.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.checkmate.app.STOP_SERVICE"
        const val ACTION_START_SESSION = "com.checkmate.app.START_SESSION"
        const val ACTION_STOP_SESSION = "com.checkmate.app.STOP_SESSION"
        const val ACTION_TOGGLE_MONITORING = "com.checkmate.app.TOGGLE_MONITORING"
        const val ACTION_MANUAL_CAPTURE = "com.checkmate.app.MANUAL_CAPTURE"
        
        private var isServiceRunning = false
        
        fun isRunning(): Boolean = isServiceRunning
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var capturePipeline: CapturePipeline
    private lateinit var networkManager: NetworkManager
    private val binder = LocalBinder()
    
    private var captureJob: Job? = null
    private var serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): CheckmateService = this@CheckmateService
    }

    override fun onCreate() {
        super.onCreate()
        
        sessionManager = SessionManager.getInstance(this)
        capturePipeline = CapturePipeline.getInstance(this)
        networkManager = NetworkManager(this)
        
        isServiceRunning = true
        Timber.d("CheckmateService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START_SESSION -> {
                lifecycleScope.launch {
                    startForegroundService()
                    startFactCheckingSession()
                }
            }
            ACTION_STOP_SESSION -> {
                lifecycleScope.launch {
                    stopCurrentSession()
                }
            }
            ACTION_TOGGLE_MONITORING -> {
                lifecycleScope.launch {
                    if (sessionManager.isSessionActive()) {
                        stopCurrentSession()
                    } else {
                        startFactCheckingSession()
                    }
                }
            }
            ACTION_MANUAL_CAPTURE -> {
                lifecycleScope.launch {
                    if (sessionManager.isSessionActive()) {
                        // Trigger a manual capture
                        val frameBundle = capturePipeline.captureFrame()
                        if (frameBundle != null) {
                            networkManager.sendFrameBundle(frameBundle)
                        }
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }
            else -> {
                // Default behavior: start foreground service
                startForegroundService()
            }
        }

        return START_STICKY // Restart if killed
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        
        lifecycleScope.launch {
            stopCurrentSession()
            // Don't cleanup singleton CapturePipeline here - other services might be using it
            networkManager.cleanup()
        }
        
        serviceScope.cancel()
        isServiceRunning = false
        Timber.d("CheckmateService destroyed")
    }

    private fun startForegroundService() {
        val notification = createServiceNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For now, just use microphone type to avoid media projection permission issues
            // We'll only use media projection type when actually capturing screen
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            
            try {
                startForeground(
                    AppConfig.NOTIFICATION_ID_SERVICE,
                    notification,
                    serviceType
                )
            } catch (e: SecurityException) {
                // Fallback to basic foreground service if permission issues
                Timber.w(e, "Failed to start with specific service type, falling back to basic foreground service")
                startForeground(AppConfig.NOTIFICATION_ID_SERVICE, notification)
            }
        } else {
            startForeground(AppConfig.NOTIFICATION_ID_SERVICE, notification)
        }
        
        Timber.d("Foreground service started")
    }

    private fun createServiceNotification(): Notification {
        val intent = Intent(this, com.checkmate.app.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CheckmateService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CheckmateApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Checkmate Active")
            .setContentText("Fact-checking in background")
            .setSmallIcon(R.drawable.ic_checkmate)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopPendingIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private suspend fun startFactCheckingSession() {
        try {
            Timber.d("Starting fact-checking session")
            
            // Start session with default settings
            val sessionStarted = sessionManager?.startNewSession() ?: false
            
            if (sessionStarted) {
                // Initialize WebSocket connection
                val connected = networkManager.connectWebSocket()
                
                if (connected) {
                    // Send session start message over WebSocket
                    val sessionState = sessionManager.getCurrentSessionState()
                    sessionState?.settings?.let { settings ->
                        networkManager.sendSessionStart(settings)
                    }
                    
                    startCaptureLoop()
                    startAudioCapture()
                    updateServiceNotification("Session active")
                } else {
                    Timber.e("Failed to connect WebSocket")
                    showErrorNotification("Failed to connect to server")
                }
            } else {
                Timber.e("Failed to start session")
                showErrorNotification("Failed to start session")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error starting fact-checking session")
            showErrorNotification("Error: ${e.message}")
        }
    }

    private suspend fun stopCurrentSession() {
        try {
            Timber.d("Stopping current session")
            
            stopCaptureLoop()
            stopAudioCapture()
            
            // Send session stop message over WebSocket
            networkManager.sendSessionStop()
            
            // Stop session and disconnect
            sessionManager?.stopCurrentSession()
            networkManager.disconnectWebSocket()
            
            updateServiceNotification("Session stopped")
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping session")
        }
    }

    private fun startAudioCapture() {
        try {
            val audioIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START_CAPTURE
            }
            startService(audioIntent)
            Timber.d("Audio capture started")
        } catch (e: Exception) {
            Timber.e(e, "Error starting audio capture")
        }
    }

    private fun stopAudioCapture() {
        try {
            val audioIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP_CAPTURE
            }
            startService(audioIntent)
            Timber.d("Audio capture stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio capture")
        }
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            try {
                while (isActive && sessionManager.isSessionActive()) {
                    // Capture screen content
                    val frameBundle = capturePipeline.captureFrame()
                    
                    if (frameBundle != null) {
                        // Send to backend
                        networkManager.sendFrameBundle(frameBundle)
                    }
                    
                    // Wait for next capture interval
                    val interval = calculateCaptureInterval()
                    delay(interval)
                }
            } catch (e: CancellationException) {
                Timber.d("Capture loop cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Error in capture loop")
                showErrorNotification("Capture error: ${e.message}")
            }
        }
    }

    private fun stopCaptureLoop() {
        captureJob?.cancel()
        captureJob = null
    }

    private fun calculateCaptureInterval(): Long {
        // Adaptive capture interval based on battery and power saving mode
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryLevel = capturePipeline.getBatteryLevel()
        
        return when {
            powerManager.isPowerSaveMode || batteryLevel < 0.2f -> {
                AppConfig.CAPTURE_INTERVAL_POWER_SAVE_MS
            }
            capturePipeline.hasRapidUIChanges() -> {
                AppConfig.CAPTURE_INTERVAL_RAPID_MS
            }
            else -> {
                AppConfig.CAPTURE_INTERVAL_MS
            }
        }
    }

    private fun updateServiceNotification(status: String) {
        val notification = createServiceNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Update notification text
        val updatedNotification = NotificationCompat.Builder(this, CheckmateApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Checkmate Active")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_checkmate)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        notificationManager.notify(AppConfig.NOTIFICATION_ID_SERVICE, updatedNotification)
    }

    private fun showErrorNotification(error: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val errorNotification = NotificationCompat.Builder(this, CheckmateApplication.NOTIFICATION_CHANNEL_ERRORS)
            .setContentTitle("Checkmate Error")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(AppConfig.NOTIFICATION_ID_ERROR, errorNotification)
    }
}
