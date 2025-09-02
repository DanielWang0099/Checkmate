package com.checkmate.app.data

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Data models for Checkmate app.
 * These correspond to the backend schema definitions.
 */

// Enums
enum class MediaType {
    @SerializedName("text")
    TEXT,
    @SerializedName("text+image")
    TEXT_IMAGE,
    @SerializedName("short-video")
    SHORT_VIDEO,
    @SerializedName("long-video")
    LONG_VIDEO,
    @SerializedName("audio")
    AUDIO
}

enum class SessionType {
    @SerializedName("TIME")
    TIME,
    @SerializedName("MANUAL")
    MANUAL,
    @SerializedName("ACTIVITY")
    ACTIVITY
}

enum class NotificationColor {
    @SerializedName("green")
    GREEN,
    @SerializedName("yellow")
    YELLOW,
    @SerializedName("red")
    RED
}

enum class SourceTier {
    @SerializedName("A")
    A,
    @SerializedName("B")
    B,
    @SerializedName("C")
    C
}

enum class ContentType {
    SOCIAL_MEDIA,
    NEWS,
    MESSAGING,
    WEB_BROWSER,
    VIDEO,
    OTHER
}

enum class CaptureType {
    ACCESSIBILITY_EVENT,
    MANUAL_TRIGGER,
    AUTOMATIC_SCREEN,
    SCHEDULED
}

// Core data models
data class MediaHints(
    @SerializedName("hasText")
    val hasText: Boolean = true,
    @SerializedName("hasImage")
    val hasImage: Boolean = false,
    @SerializedName("hasVideo")
    val hasVideo: Boolean = false
)

data class TreeNode(
    val role: String,
    val text: String
)

data class TreeSummary(
    @SerializedName("appPackage")
    val appPackage: String,
    @SerializedName("appReadableName")
    val appReadableName: String,
    @SerializedName("mediaHints")
    val mediaHints: MediaHints,
    @SerializedName("topNodes")
    val topNodes: List<TreeNode>,
    @SerializedName("urlOrChannelGuess")
    val urlOrChannelGuess: String? = null,
    @SerializedName("publisherGuess")
    val publisherGuess: String? = null,
    @SerializedName("topicGuesses")
    val topicGuesses: List<String> = emptyList(),
    val confidence: Float
)

data class DeviceHints(
    val battery: Float,
    @SerializedName("powerSaver")
    val powerSaver: Boolean
)

data class FrameBundle(
    @SerializedName("sessionId")
    val sessionId: String,
    val timestamp: Date,
    @SerializedName("treeSummary")
    val treeSummary: TreeSummary,
    @SerializedName("ocrText")
    val ocrText: String,
    @SerializedName("hasImage")
    val hasImage: Boolean,
    @SerializedName("imageRef")
    val imageRef: String? = null,
    @SerializedName("audioTranscriptDelta")
    val audioTranscriptDelta: String,
    @SerializedName("deviceHints")
    val deviceHints: DeviceHints
)

data class Source(
    val url: String,
    val tier: SourceTier,
    val title: String? = null,
    @SerializedName("directQuoteMatch")
    val directQuoteMatch: Boolean = false
)

data class NotificationPayload(
    val color: NotificationColor,
    @SerializedName("shortText")
    val shortText: String,
    val details: String? = null,
    val sources: List<Source> = emptyList(),
    val confidence: Float,
    val severity: Float,
    @SerializedName("shouldNotify")
    val shouldNotify: Boolean
)

// Session configuration
data class SessionTypeConfig(
    val type: SessionType,
    val minutes: Int? = null
)

data class NotificationSettings(
    val details: Boolean = true,
    val links: Boolean = true
)

data class SessionSettings(
    @SerializedName("sessionType")
    val sessionType: SessionTypeConfig,
    val strictness: Float,
    val notify: NotificationSettings
)

// WebSocket messages
enum class WSMessageType {
    @SerializedName("frame_bundle")
    FRAME_BUNDLE,
    @SerializedName("notification")
    NOTIFICATION,
    @SerializedName("session_end")
    SESSION_END,
    @SerializedName("error")
    ERROR,
    @SerializedName("ping")
    PING,
    @SerializedName("pong")
    PONG
}

data class WSMessage(
    val type: WSMessageType,
    val data: Map<String, Any>,
    val timestamp: Date
)

// Session response models
data class CreateSessionResponse(
    @SerializedName("session_id")
    val sessionId: String,
    val settings: SessionSettings,
    @SerializedName("created_at")
    val createdAt: String
)

data class SessionInfo(
    @SerializedName("session_id")
    val sessionId: String,
    val active: Boolean
)

// UI State models
data class SessionState(
    val isActive: Boolean = false,
    val sessionId: String? = null,
    val settings: SessionSettings? = null,
    val lastNotification: NotificationPayload? = null,
    val error: String? = null
)

data class PreferencesState(
    val sessionType: SessionType = SessionType.MANUAL,
    val sessionDurationMinutes: Int = 60,
    val strictness: Float = 0.5f,
    val showDetails: Boolean = true,
    val showLinks: Boolean = true,
    val isFirstLaunch: Boolean = true
)

// Permission state
data class PermissionState(
    val hasAccessibilityPermission: Boolean = false,
    val hasMediaProjectionPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val allPermissionsGranted: Boolean = false
)

// App configuration
object AppConfig {
    const val BACKEND_BASE_URL = "http://10.0.2.2:8000" // Android emulator localhost
    const val WEBSOCKET_URL = "ws://10.0.2.2:8000/ws"
    
    // Capture settings
    const val CAPTURE_INTERVAL_MS = 2500L // 2.5 seconds
    const val CAPTURE_INTERVAL_POWER_SAVE_MS = 3000L // 3 seconds on power save
    const val CAPTURE_INTERVAL_RAPID_MS = 1000L // 1 second for rapid changes
    
    // OCR settings
    const val MAX_OCR_TEXT_LENGTH = 1200
    
    // Image settings
    const val MAX_IMAGE_SIZE_MB = 5
    const val IMAGE_QUALITY = 80
    
    // Audio settings
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_CHUNK_DURATION_MS = 5000L
    
    // Session timeouts
    const val SESSION_TIMEOUT_HOURS = 24
    const val WEBSOCKET_PING_INTERVAL_MS = 30000L
    const val WEBSOCKET_RECONNECT_DELAY_MS = 5000L
    
    // Notification settings
    const val NOTIFICATION_ID_SERVICE = 1001
    const val NOTIFICATION_ID_ALERT = 1002
    const val NOTIFICATION_ID_ERROR = 1003
    
    // Capture timing
    const val CAPTURE_DEBOUNCE_MS = 1500L
}

// Accessibility configuration
data class AccessibilityConfig(
    val monitorSocialMedia: Boolean = true,
    val monitorNews: Boolean = true,
    val monitorMessaging: Boolean = true,
    val monitorWebBrowser: Boolean = true,
    val monitorVideo: Boolean = false,
    val monitorOther: Boolean = false
)

// App source info
data class AppSourceInfo(
    val packageName: String,
    val readableName: String,
    val category: String? = null,
    val isSystemApp: Boolean = false
)
