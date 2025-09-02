package com.checkmate.app.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.checkmate.app.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "checkmate_prefs")

/**
 * Manages user session state and preferences using DataStore.
 */
class SessionManager(private val context: Context) {
    
    private val dataStore = context.dataStore
    
    // DataStore keys
    private object Keys {
        val SESSION_ID = stringPreferencesKey("session_id")
        val SESSION_ACTIVE = booleanPreferencesKey("session_active")
        val SESSION_TYPE = stringPreferencesKey("session_type")
        val SESSION_DURATION = intPreferencesKey("session_duration")
        val STRICTNESS = floatPreferencesKey("strictness")
        val SHOW_DETAILS = booleanPreferencesKey("show_details")
        val SHOW_LINKS = booleanPreferencesKey("show_links")
        val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        
        // Accessibility config
        val MONITOR_SOCIAL_MEDIA = booleanPreferencesKey("monitor_social_media")
        val MONITOR_NEWS = booleanPreferencesKey("monitor_news")
        val MONITOR_MESSAGING = booleanPreferencesKey("monitor_messaging")
        val MONITOR_WEB_BROWSER = booleanPreferencesKey("monitor_web_browser")
        val MONITOR_VIDEO = booleanPreferencesKey("monitor_video")
        val MONITOR_OTHER = booleanPreferencesKey("monitor_other")
    }
    
    // Session state management
    suspend fun createNewSession(): String {
        val sessionId = UUID.randomUUID().toString()
        
        dataStore.edit { preferences ->
            preferences[Keys.SESSION_ID] = sessionId
            preferences[Keys.SESSION_ACTIVE] = true
        }
        
        Timber.d("Created new session: $sessionId")
        return sessionId
    }
    
    suspend fun endSession() {
        dataStore.edit { preferences ->
            preferences[Keys.SESSION_ACTIVE] = false
            preferences.remove(Keys.SESSION_ID)
        }
        
        Timber.d("Session ended")
    }
    
    suspend fun getCurrentSessionId(): String? {
        return dataStore.data.map { preferences ->
            if (preferences[Keys.SESSION_ACTIVE] == true) {
                preferences[Keys.SESSION_ID]
            } else {
                null
            }
        }.first()
    }
    
    suspend fun isSessionActive(): Boolean {
        return dataStore.data.map { preferences ->
            preferences[Keys.SESSION_ACTIVE] ?: false
        }.first()
    }
    
    // Session settings
    suspend fun getSessionSettings(): SessionSettings {
        return dataStore.data.map { preferences ->
            val sessionType = when (preferences[Keys.SESSION_TYPE]) {
                "TIME" -> SessionType.TIME
                "ACTIVITY" -> SessionType.ACTIVITY
                else -> SessionType.MANUAL
            }
            
            val sessionTypeConfig = SessionTypeConfig(
                type = sessionType,
                minutes = if (sessionType == SessionType.TIME) {
                    preferences[Keys.SESSION_DURATION] ?: 60
                } else null
            )
            
            val notificationSettings = NotificationSettings(
                details = preferences[Keys.SHOW_DETAILS] ?: true,
                links = preferences[Keys.SHOW_LINKS] ?: true
            )
            
            SessionSettings(
                sessionType = sessionTypeConfig,
                strictness = preferences[Keys.STRICTNESS] ?: 0.5f,
                notify = notificationSettings
            )
        }.first()
    }
    
    suspend fun updateSessionSettings(settings: SessionSettings) {
        dataStore.edit { preferences ->
            preferences[Keys.SESSION_TYPE] = settings.sessionType.type.name
            if (settings.sessionType.minutes != null) {
                preferences[Keys.SESSION_DURATION] = settings.sessionType.minutes
            }
            preferences[Keys.STRICTNESS] = settings.strictness
            preferences[Keys.SHOW_DETAILS] = settings.notify.details
            preferences[Keys.SHOW_LINKS] = settings.notify.links
        }
        
        Timber.d("Updated session settings")
    }
    
    // Accessibility configuration
    suspend fun getAccessibilityConfig(): AccessibilityConfig {
        return dataStore.data.map { preferences ->
            AccessibilityConfig(
                monitorSocialMedia = preferences[Keys.MONITOR_SOCIAL_MEDIA] ?: true,
                monitorNews = preferences[Keys.MONITOR_NEWS] ?: true,
                monitorMessaging = preferences[Keys.MONITOR_MESSAGING] ?: true,
                monitorWebBrowser = preferences[Keys.MONITOR_WEB_BROWSER] ?: true,
                monitorVideo = preferences[Keys.MONITOR_VIDEO] ?: false,
                monitorOther = preferences[Keys.MONITOR_OTHER] ?: false
            )
        }.first()
    }
    
    suspend fun updateAccessibilityConfig(config: AccessibilityConfig) {
        dataStore.edit { preferences ->
            preferences[Keys.MONITOR_SOCIAL_MEDIA] = config.monitorSocialMedia
            preferences[Keys.MONITOR_NEWS] = config.monitorNews
            preferences[Keys.MONITOR_MESSAGING] = config.monitorMessaging
            preferences[Keys.MONITOR_WEB_BROWSER] = config.monitorWebBrowser
            preferences[Keys.MONITOR_VIDEO] = config.monitorVideo
            preferences[Keys.MONITOR_OTHER] = config.monitorOther
        }
        
        Timber.d("Updated accessibility config")
    }
    
    // Preferences state
    fun getPreferencesFlow(): Flow<PreferencesState> {
        return dataStore.data.map { preferences ->
            PreferencesState(
                sessionType = when (preferences[Keys.SESSION_TYPE]) {
                    "TIME" -> SessionType.TIME
                    "ACTIVITY" -> SessionType.ACTIVITY
                    else -> SessionType.MANUAL
                },
                sessionDurationMinutes = preferences[Keys.SESSION_DURATION] ?: 60,
                strictness = preferences[Keys.STRICTNESS] ?: 0.5f,
                showDetails = preferences[Keys.SHOW_DETAILS] ?: true,
                showLinks = preferences[Keys.SHOW_LINKS] ?: true,
                isFirstLaunch = preferences[Keys.IS_FIRST_LAUNCH] ?: true
            )
        }
    }
    
    suspend fun markFirstLaunchComplete() {
        dataStore.edit { preferences ->
            preferences[Keys.IS_FIRST_LAUNCH] = false
        }
    }
    
    // Session state flow
    fun getSessionStateFlow(): Flow<SessionState> {
        return dataStore.data.map { preferences ->
            val isActive = preferences[Keys.SESSION_ACTIVE] ?: false
            val sessionId = if (isActive) preferences[Keys.SESSION_ID] else null
            
            SessionState(
                isActive = isActive,
                sessionId = sessionId,
                settings = if (isActive) getSessionSettings() else null
            )
        }
    }
    
    // Cleanup
    fun cleanup() {
        // DataStore automatically handles cleanup
        Timber.d("SessionManager cleanup completed")
    }
}
