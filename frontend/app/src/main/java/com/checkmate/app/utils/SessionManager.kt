package com.checkmate.app.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.checkmate.app.data.*
import com.checkmate.app.network.ApiService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID

/**
 * Manages fact-checking sessions and application state.
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
    
    private var currentSessionState = MutableStateFlow(SessionState())
    private var preferencesState = MutableStateFlow(PreferencesState())
    
    // Public state flows
    val sessionState: StateFlow<SessionState> = currentSessionState.asStateFlow()
    val preferences: StateFlow<PreferencesState> = preferencesState.asStateFlow()

    init {
        // Load initial state
        loadPreferences()
        loadSessionState()
    }

    suspend fun startNewSession(): Boolean = sessionMutex.withLock {
        try {
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
            
            if (response != null) {
                // Update local state
                val newState = SessionState(
                    isActive = true,
                    sessionId = response.sessionId,
                    settings = response.settings,
                    lastNotification = null,
                    error = null
                )
                
                currentSessionState.value = newState
                saveSessionState(newState)
                
                Timber.d("Session created: ${response.sessionId}")
                return true
            } else {
                Timber.e("Failed to create session")
                currentSessionState.value = currentSessionState.value.copy(
                    error = "Failed to create session"
                )
                return false
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error starting session")
            currentSessionState.value = currentSessionState.value.copy(
                error = "Error starting session: ${e.message}"
            )
            return false
        }
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
}
