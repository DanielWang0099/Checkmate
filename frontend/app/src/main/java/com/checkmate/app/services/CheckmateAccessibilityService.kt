package com.checkmate.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.checkmate.app.data.AccessibilityConfig
import com.checkmate.app.data.AppConfig
import com.checkmate.app.data.CaptureType
import com.checkmate.app.data.ContentType
import com.checkmate.app.utils.SessionManager
import com.checkmate.app.utils.AccessibilityHelper
import com.checkmate.app.utils.CapturePipeline
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Accessibility service that monitors screen content changes for fact-checking.
 */
class CheckmateAccessibilityService : AccessibilityService() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var sessionManager: SessionManager? = null
    private var capturePipeline: CapturePipeline? = null
    
    private var lastCaptureTime = 0L
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        
        sessionManager = SessionManager.getInstance(this)
        capturePipeline = CapturePipeline(this)
        
        configureAccessibilityService()
        
        Timber.i("CheckmateAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        Timber.i("CheckmateAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldProcessEvent(event)) return
        
        // Debounce rapid events
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < AppConfig.CAPTURE_DEBOUNCE_MS) {
            return
        }
        
        if (isProcessing) {
            Timber.d("Skipping event - already processing")
            return
        }
        
        lastCaptureTime = currentTime
        
        serviceScope.launch {
            processAccessibilityEvent(event)
        }
    }

    private suspend fun processAccessibilityEvent(event: AccessibilityEvent) {
        isProcessing = true
        
        try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Timber.w("No root node available for accessibility event")
                return
            }
            
            val packageName = event.packageName?.toString()
            val sourceApp = AccessibilityHelper.getInstance(this).getCurrentApp(this, packageName)
            
            // Skip if this is our own app or system UI
            if (packageName == this.packageName || 
                packageName == "com.android.systemui" ||
                packageName == "android") {
                return
            }
            
            // Determine content type based on event and app
            val contentType = determineContentType(event, sourceApp?.category)
            
            // Check if we should process this content type
            if (!shouldProcessContentType(contentType)) {
                return
            }
            
            Timber.d("Processing accessibility event: type=${event.eventType}, package=${packageName}, contentType=${contentType}")
            
            // Capture and process the screen content
            capturePipeline?.captureAccessibilityTree(
                rootNode = rootNode,
                sourceApp = sourceApp,
                contentType = contentType,
                captureType = CaptureType.ACCESSIBILITY_EVENT
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Error processing accessibility event")
        } finally {
            isProcessing = false
        }
    }

    private fun shouldProcessEvent(event: AccessibilityEvent): Boolean {
        // Only process events that indicate content changes
        return when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> true
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> true
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            else -> false
        }
    }

    private fun determineContentType(event: AccessibilityEvent, appCategory: String?): ContentType {
        val packageName = event.packageName?.toString() ?: ""
        
        return when {
            // Social media apps
            packageName.contains("twitter") || 
            packageName.contains("facebook") || 
            packageName.contains("instagram") || 
            packageName.contains("tiktok") ||
            packageName.contains("linkedin") -> ContentType.SOCIAL_MEDIA
            
            // News apps
            packageName.contains("news") ||
            packageName.contains("cnn") ||
            packageName.contains("bbc") ||
            packageName.contains("reuters") ||
            appCategory == "NEWS_AND_MAGAZINES" -> ContentType.NEWS
            
            // Messaging apps
            packageName.contains("whatsapp") ||
            packageName.contains("telegram") ||
            packageName.contains("messenger") ||
            packageName.contains("signal") -> ContentType.MESSAGING
            
            // Browsers
            packageName.contains("chrome") ||
            packageName.contains("firefox") ||
            packageName.contains("browser") ||
            packageName.contains("opera") -> ContentType.WEB_BROWSER
            
            // Video apps
            packageName.contains("youtube") ||
            packageName.contains("netflix") ||
            packageName.contains("video") -> ContentType.VIDEO
            
            else -> ContentType.OTHER
        }
    }

    private suspend fun shouldProcessContentType(contentType: ContentType): Boolean {
        val config = sessionManager?.getAccessibilityConfig() ?: return false
        
        return when (contentType) {
            ContentType.SOCIAL_MEDIA -> config.monitorSocialMedia
            ContentType.NEWS -> config.monitorNews
            ContentType.MESSAGING -> config.monitorMessaging
            ContentType.WEB_BROWSER -> config.monitorWebBrowser
            ContentType.VIDEO -> config.monitorVideo
            ContentType.OTHER -> config.monitorOther
        }
    }

    private fun configureAccessibilityService() {
        val info = AccessibilityServiceInfo().apply {
            // Configure which events to receive
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            
            // Configure feedback type
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            
            // Configure flags
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            
            // Don't interrupt other accessibility services
            notificationTimeout = 100
        }
        
        serviceInfo = info
    }

    override fun onInterrupt() {
        Timber.i("CheckmateAccessibilityService interrupted")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        
        capturePipeline?.cleanup()
        sessionManager?.cleanup()
        
        super.onDestroy()
        Timber.i("CheckmateAccessibilityService destroyed")
    }

    /**
     * Public API for manual capture requests
     */
    fun captureCurrentScreen() {
        serviceScope.launch {
            try {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val packageName = rootNode.packageName?.toString()
                    val sourceApp = AccessibilityHelper.getInstance(this@CheckmateAccessibilityService).getCurrentApp(this@CheckmateAccessibilityService, packageName)
                    
                    capturePipeline?.captureAccessibilityTree(
                        rootNode = rootNode,
                        sourceApp = sourceApp,
                        contentType = ContentType.OTHER,
                        captureType = CaptureType.MANUAL_TRIGGER
                    )
                } else {
                    Timber.w("No root node available for manual capture")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during manual screen capture")
            }
        }
    }

    companion object {
        var instance: CheckmateAccessibilityService? = null
            private set
    }

    init {
        instance = this
    }
}
