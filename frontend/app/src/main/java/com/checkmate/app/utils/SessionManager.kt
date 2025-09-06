package com.checkmate.app.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.asLiveData
import com.checkmate.app.data.*
import com.checkmate.app.network.ApiService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID
import java.util.Date

/**
 * Manages fact-checking sessions and application state with enhanced error recovery.
 */
class SessionManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SessionManager? = null
        
        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // DataStore keys
        private val CURRENT_SESSION_ID = stringPreferencesKey("current_session_id")
        private val SESSION_SETTINGS = stringPreferencesKey("session_settings")
        private val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        private val LAST_NOTIFICATION = stringPreferencesKey("last_notification")
    }

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "checkmate_prefs")
    private val apiService = ApiService.getInstance()
    private val sessionMutex = Mutex()
    private val deviceStatusTracker = DeviceStatusTracker(context)
    private val errorRecoveryManager = ErrorRecoveryManager(context)
    
    private var currentSessionState = MutableStateFlow(SessionState())
    private var preferencesState = MutableStateFlow(PreferencesState())
    
    // Public state flows
    val sessionState: StateFlow<SessionState> = currentSessionState.asStateFlow()
    val preferences: StateFlow<PreferencesState> = preferencesState.asStateFlow()
    val deviceHints: StateFlow<DeviceHints> = deviceStatusTracker.deviceHints
    val systemHealth = errorRecoveryManager.systemHealth

    init {
        // Load initial state
        loadPreferences()
        loadSessionState()
    }

    suspend fun startNewSession(): Boolean = sessionMutex.withLock {
        return errorRecoveryManager.executeWithRecovery(
            operation = {
                Timber.d("Starting new fact-checking session")
                
                // Get current preferences
                val prefs = preferencesState.value
                
                // Create session settings
                val sessionSettings = SessionSettings(
                    sessionType = SessionTypeConfig(
                        type = prefs.sessionType,
                        minutes = if (prefs.sessionType == SessionType.TIME) prefs.sessionDurationMinutes else null
                    ),
                    strictness = prefs.strictness,
                    notify = NotificationSettings(
                        details = prefs.showDetails,
                        links = prefs.showLinks
                    )
                )
                
                // Call backend to create session
                val response = apiService.createSession(sessionSettings)
                    ?: throw Exception("Failed to create session on backend")
                
                // Update local state with session start time and device hints
                val newState = SessionState(
                    isActive = true,
                    sessionId = response.sessionId,
                    settings = response.settings,
                    sessionStartTime = Date(),
                    deviceHints = deviceStatusTracker.getCurrentDeviceHints(),
                    lastNotification = null,
                    error = null
                )
                
                currentSessionState.value = newState
                saveSessionState(newState)
                
                Timber.d("Session created: ${response.sessionId}")
                true
            },
            operationName = "start_session",
            onError = { error ->
                Timber.e(error.originalError, "Session start failed: ${error.userMessage}")
                currentSessionState.value = currentSessionState.value.copy(
                    error = error.userMessage
                )
            },
            onRetry = { attempt, delayMs ->
                Timber.d("Retrying session start (attempt $attempt) in ${delayMs}ms")
                currentSessionState.value = currentSessionState.value.copy(
                    error = "Retrying session start (attempt $attempt)..."
                )
            }
        ).fold(
            onSuccess = { it },
            onFailure = { 
                currentSessionState.value = currentSessionState.value.copy(
                    error = "Failed to start session after multiple attempts"
                )
                false
            }
        )
    }

    /**
     * Overloaded method for testing - allows passing custom SessionSettings
     */
    suspend fun startNewSession(customSettings: SessionSettings): Boolean = sessionMutex.withLock {
        return errorRecoveryManager.executeWithRecovery(
            operation = {
                Timber.d("Starting new fact-checking session with custom settings")
                
                // Use the provided custom settings instead of preferences
                val response = apiService.createSession(customSettings)
                    ?: throw Exception("Failed to create session on backend")
                
                // Update local state with session start time and device hints
                val newState = SessionState(
                    isActive = true,
                    sessionId = response.sessionId,
                    settings = response.settings,
                    sessionStartTime = Date(),
                    deviceHints = deviceStatusTracker.getCurrentDeviceHints(),
                    lastNotification = null,
                    error = null
                )
                
                currentSessionState.value = newState
                saveSessionState(newState)
                
                Timber.d("Session created with custom settings: ${response.sessionId}")
                true
            },
            operationName = "start_session_custom",
            onError = { error ->
                Timber.e(error.originalError, "Custom session start failed: ${error.userMessage}")
                currentSessionState.value = currentSessionState.value.copy(
                    error = error.userMessage
                )
            },
            onRetry = { attempt, delayMs ->
                Timber.d("Retrying custom session start (attempt $attempt) in ${delayMs}ms")
                currentSessionState.value = currentSessionState.value.copy(
                    error = "Retrying session start (attempt $attempt)..."
                )
            }
        ).fold(
            onSuccess = { it },
            onFailure = { 
                currentSessionState.value = currentSessionState.value.copy(
                    error = "Failed to start session after multiple attempts"
                )
                false
            }
        )
    }

    suspend fun stopCurrentSession(): Boolean = sessionMutex.withLock {
        try {
            val currentState = currentSessionState.value
            
            if (currentState.isActive && currentState.sessionId != null) {
                Timber.d("Stopping session: ${currentState.sessionId}")
                
                // Call backend to end session
                val success = apiService.deleteSession(currentState.sessionId)
                
                if (success) {
                    // Update local state
                    val newState = SessionState(
                        isActive = false,
                        sessionId = null,
                        settings = null,
                        lastNotification = currentState.lastNotification,
                        error = null
                    )
                    
                    currentSessionState.value = newState
                    saveSessionState(newState)
                    
                    Timber.d("Session stopped successfully")
                    return true
                } else {
                    Timber.e("Failed to stop session on backend")
                    // Still update local state to stopped
                    currentSessionState.value = currentState.copy(isActive = false)
                    return false
                }
            } else {
                Timber.w("No active session to stop")
                return true
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping session")
            currentSessionState.value = currentSessionState.value.copy(
                error = "Error stopping session: ${e.message}"
            )
            return false
        }
    }

    fun isSessionActive(): Boolean {
        return currentSessionState.value.isActive
    }

    fun hasActiveSessions(): Boolean {
        return isSessionActive()
    }

    fun getCurrentSessionState(): SessionState? {
        return if (currentSessionState.value.isActive) currentSessionState.value else null
    }

    suspend fun updatePreferences(
        sessionType: SessionType? = null,
        sessionDurationMinutes: Int? = null,
        strictness: Float? = null,
        showDetails: Boolean? = null,
        showLinks: Boolean? = null
    ) {
        val currentPrefs = preferencesState.value
        val newPrefs = currentPrefs.copy(
            sessionType = sessionType ?: currentPrefs.sessionType,
            sessionDurationMinutes = sessionDurationMinutes ?: currentPrefs.sessionDurationMinutes,
            strictness = strictness ?: currentPrefs.strictness,
            showDetails = showDetails ?: currentPrefs.showDetails,
            showLinks = showLinks ?: currentPrefs.showLinks,
            isFirstLaunch = false
        )
        
        preferencesState.value = newPrefs
        savePreferences(newPrefs)
        
        Timber.d("Preferences updated")
    }

    suspend fun handleNotification(notification: NotificationPayload) {
        try {
            Timber.d("Received notification: ${notification.shortText}")
            
            val currentState = currentSessionState.value
            val newState = currentState.copy(lastNotification = notification)
            
            currentSessionState.value = newState
            saveSessionState(newState)
            
            // Show system notification
            NotificationHelper.showFactCheckNotification(context, notification)
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling notification")
        }
    }

    suspend fun handleSessionEnd(reason: String) {
        try {
            Timber.d("Session ended: $reason")
            
            val newState = SessionState(
                isActive = false,
                sessionId = null,
                settings = null,
                lastNotification = currentSessionState.value.lastNotification,
                error = null
            )
            
            currentSessionState.value = newState
            saveSessionState(newState)
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling session end")
        }
    }

    suspend fun handleError(error: String) {
        try {
            Timber.e("Session error: $error")
            
            val currentState = currentSessionState.value
            val newState = currentState.copy(error = error)
            
            currentSessionState.value = newState
            saveSessionState(newState)
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling error")
        }
    }
    
    // Enhanced error handling for structured error responses
    suspend fun handleStructuredError(errorResponse: ErrorResponse?) {
        try {
            if (errorResponse != null) {
                Timber.e("Structured error: ${errorResponse.errorType} - ${errorResponse.message}")
                
                val currentState = currentSessionState.value
                val newState = currentState.copy(
                    error = errorResponse.message,
                    errorResponse = errorResponse
                )
                
                currentSessionState.value = newState
                saveSessionState(newState)
                
                // Handle critical errors
                if (errorResponse.severity == ErrorSeverity.CRITICAL) {
                    // Force stop session for critical errors
                    stopCurrentSession()
                }
                
                // Show error notification if needed
                NotificationHelper.showErrorNotification(context, errorResponse)
                
            } else {
                handleError("Unknown network error occurred")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling structured error")
        }
    }
    
    // Handle session status updates from WebSocket
    suspend fun handleSessionStatus(statusData: Map<String, Any>) {
        try {
            Timber.d("Session status update: $statusData")
            
            val currentState = currentSessionState.value
            // Update session state based on status data
            // This could include memory updates, session health, etc.
            
            // For now, just clear any errors if status is healthy
            val isHealthy = statusData["status"] == "active"
            if (isHealthy && currentState.error != null) {
                val newState = currentState.copy(error = null, errorResponse = null)
                currentSessionState.value = newState
                saveSessionState(newState)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling session status")
        }
    }
    
    // Update connection state from WebSocket
    suspend fun updateConnectionState(connectionState: ConnectionState) {
        try {
            val currentState = currentSessionState.value
            val newState = currentState.copy(
                connectionState = connectionState,
                lastHeartbeat = if (connectionState == ConnectionState.CONNECTED) Date() else currentState.lastHeartbeat
            )
            
            currentSessionState.value = newState
            saveSessionState(newState)
            
            Timber.d("Connection state updated: $connectionState")
            
        } catch (e: Exception) {
            Timber.e(e, "Error updating connection state")
        }
    }
    
    // Clear last notification
    suspend fun clearLastNotification() {
        try {
            val currentState = currentSessionState.value
            val newState = currentState.copy(lastNotification = null)
            
            currentSessionState.value = newState
            saveSessionState(newState)
            
            Timber.d("Last notification cleared")
            
        } catch (e: Exception) {
            Timber.e(e, "Error clearing last notification")
        }
    }
    
    // Update session memory from WebSocket status messages
    suspend fun updateSessionMemory(sessionMemory: SessionMemory) {
        try {
            val currentState = currentSessionState.value
            val newState = currentState.copy(
                sessionMemory = sessionMemory,
                lastActivityTime = Date()
            )
            
            currentSessionState.value = newState
            saveSessionState(newState)
            
            Timber.d("Session memory updated with ${sessionMemory.timeline.size} timeline events")
            
        } catch (e: Exception) {
            Timber.e(e, "Error updating session memory")
        }
    }
    
    // Update device hints periodically
    suspend fun updateDeviceHints() {
        try {
            val currentState = currentSessionState.value
            val newHints = deviceStatusTracker.getCurrentDeviceHints()
            val newState = currentState.copy(deviceHints = newHints)
            
            currentSessionState.value = newState
            // Note: Don't save to DataStore as this updates frequently
            
        } catch (e: Exception) {
            Timber.e(e, "Error updating device hints")
        }
    }
    
    // Get current device status for UI
    fun getBatteryStatus(): BatteryStatus {
        return deviceStatusTracker.getBatteryStatus()
    }
    
    // Get performance hints for optimization
    fun getPerformanceHints(): PerformanceHints {
        return deviceStatusTracker.getPerformanceHints()
    }

    suspend fun getAccessibilityConfig(): AccessibilityConfig {
        return try {
            val prefs = preferencesState.value
            AccessibilityConfig(
                monitorSocialMedia = true,
                monitorNews = true,
                monitorMessaging = true,
                monitorWebBrowser = true,
                monitorVideo = false,
                monitorOther = false
            )
        } catch (e: Exception) {
            Timber.e(e, "Error getting accessibility config")
            AccessibilityConfig()
        }
    }

    private fun loadPreferences() {
        try {
            context.dataStore.data
                .catch { e -> 
                    Timber.e(e, "Error loading preferences")
                    emit(emptyPreferences())
                }
                .onEach { preferences ->
                    val sessionType = preferences[stringPreferencesKey("session_type")]?.let {
                        SessionType.valueOf(it)
                    } ?: SessionType.MANUAL
                    
                    val prefs = PreferencesState(
                        sessionType = sessionType,
                        sessionDurationMinutes = preferences[intPreferencesKey("session_duration")] ?: 60,
                        strictness = preferences[floatPreferencesKey("strictness")] ?: 0.5f,
                        showDetails = preferences[booleanPreferencesKey("show_details")] ?: true,
                        showLinks = preferences[booleanPreferencesKey("show_links")] ?: true,
                        isFirstLaunch = preferences[IS_FIRST_LAUNCH] ?: true
                    )
                    
                    preferencesState.value = prefs
                }
        } catch (e: Exception) {
            Timber.e(e, "Error in loadPreferences")
        }
    }

    private fun loadSessionState() {
        try {
            context.dataStore.data
                .catch { e ->
                    Timber.e(e, "Error loading session state")
                    emit(emptyPreferences())
                }
                .onEach { preferences ->
                    val sessionId = preferences[CURRENT_SESSION_ID]
                    val isActive = sessionId != null
                    
                    // Note: In a real implementation, you might want to validate
                    // the session with the backend on app restart
                    
                    val state = SessionState(
                        isActive = isActive,
                        sessionId = sessionId,
                        settings = null, // Would need to be persisted separately
                        lastNotification = null,
                        error = null
                    )
                    
                    currentSessionState.value = state
                }
        } catch (e: Exception) {
            Timber.e(e, "Error in loadSessionState")
        }
    }

    private suspend fun savePreferences(prefs: PreferencesState) {
        try {
            context.dataStore.edit { preferences ->
                preferences[stringPreferencesKey("session_type")] = prefs.sessionType.name
                preferences[intPreferencesKey("session_duration")] = prefs.sessionDurationMinutes
                preferences[floatPreferencesKey("strictness")] = prefs.strictness
                preferences[booleanPreferencesKey("show_details")] = prefs.showDetails
                preferences[booleanPreferencesKey("show_links")] = prefs.showLinks
                preferences[IS_FIRST_LAUNCH] = prefs.isFirstLaunch
            }
        } catch (e: Exception) {
            Timber.e(e, "Error saving preferences")
        }
    }

    private suspend fun saveSessionState(state: SessionState) {
        try {
            context.dataStore.edit { preferences ->
                if (state.sessionId != null) {
                    preferences[CURRENT_SESSION_ID] = state.sessionId
                } else {
                    preferences.remove(CURRENT_SESSION_ID)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error saving session state")
        }
    }
    
    // Enhanced Error Recovery Methods
    
    /**
     * Manually retry a failed session operation
     */
    suspend fun retrySessionOperation(operation: String): Boolean {
        return when (operation) {
            "start_session" -> retryStartSession()
            "stop_session" -> retryStopSession()
            "reconnect_websocket" -> retryWebSocketConnection()
            else -> false
        }
    }
    
    /**
     * Retry starting a session
     */
    suspend fun retryStartSession(): Boolean {
        return errorRecoveryManager.retryOperation(
            operation = { startNewSession() },
            operationName = "retry_start_session",
            sessionId = currentSessionState.value.sessionId
        ).fold(
            onSuccess = { it as Boolean },
            onFailure = { false }
        )
    }
    
    /**
     * Retry stopping current session
     */
    suspend fun retryStopSession(): Boolean {
        return errorRecoveryManager.retryOperation(
            operation = { stopCurrentSession() },
            operationName = "retry_stop_session",
            sessionId = currentSessionState.value.sessionId
        ).fold(
            onSuccess = { it as Boolean },
            onFailure = { false }
        )
    }
    
    /**
     * Retry WebSocket connection
     */
    suspend fun retryWebSocketConnection(): Boolean {
        val sessionId = currentSessionState.value.sessionId ?: return false
        
        return errorRecoveryManager.retryOperation(
            operation = { 
                // This would integrate with your WebSocketClient
                // WebSocketClient.getInstance().reconnect(sessionId)
                true
            },
            operationName = "retry_websocket_connection",
            sessionId = sessionId
        ).fold(
            onSuccess = { it as Boolean },
            onFailure = { false }
        )
    }
    
    /**
     * Get available recovery actions for current session state
     */
    fun getSessionRecoveryActions(): List<ErrorRecoveryManager.RecoveryAction> {
        val currentState = currentSessionState.value
        val actions = mutableListOf<ErrorRecoveryManager.RecoveryAction>()
        
        // If session failed to start
        if (!currentState.isActive && currentState.error?.contains("start") == true) {
            actions.add(
                ErrorRecoveryManager.RecoveryAction(
                    actionType = "retry_start_session",
                    description = "Retry starting session",
                    autoExecutable = false,
                    priority = 5,
                    userMessage = "Tap to retry starting your fact-checking session"
                )
            )
        }
        
        // If session is active but has connection issues
        if (currentState.isActive && currentState.error?.contains("connection") == true) {
            actions.add(
                ErrorRecoveryManager.RecoveryAction(
                    actionType = "retry_websocket_connection",
                    description = "Reconnect to service",
                    autoExecutable = true,
                    priority = 4,
                    estimatedDurationMs = 5000L,
                    userMessage = "Reconnecting to fact-checking service..."
                )
            )
        }
        
        // General refresh action
        actions.add(
            ErrorRecoveryManager.RecoveryAction(
                actionType = "refresh_session_state",
                description = "Refresh session state",
                autoExecutable = true,
                priority = 2,
                estimatedDurationMs = 2000L,
                userMessage = "Refreshing session information..."
            )
        )
        
        return actions
    }
    
    /**
     * Execute a recovery action for session management
     */
    suspend fun executeSessionRecoveryAction(action: ErrorRecoveryManager.RecoveryAction): Boolean {
        return try {
            when (action.actionType) {
                "retry_start_session" -> retryStartSession()
                "retry_stop_session" -> retryStopSession()
                "retry_websocket_connection" -> retryWebSocketConnection()
                "refresh_session_state" -> {
                    loadSessionState()
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute recovery action: ${action.actionType}")
            false
        }
    }
    
    /**
     * Clear all error states
     */
    fun clearSessionErrors() {
        currentSessionState.value = currentSessionState.value.copy(error = null)
    }
    
    /**
     * Get current system health status
     */
    fun getSystemHealth(): ErrorRecoveryManager.SystemHealth {
        return errorRecoveryManager.getCurrentSystemHealth()
    }
    
    /**
     * Reset circuit breakers for session operations
     */
    fun resetSessionCircuitBreakers() {
        errorRecoveryManager.resetCircuitBreaker("start_session")
        errorRecoveryManager.resetCircuitBreaker("stop_session")
        errorRecoveryManager.resetCircuitBreaker("websocket_connection")
    }
    
    /**
     * Cleanup resources and shutdown background tasks
     */
    fun cleanup() {
        errorRecoveryManager.cleanup()
    }
}
