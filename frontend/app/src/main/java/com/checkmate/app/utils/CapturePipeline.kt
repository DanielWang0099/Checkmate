package com.checkmate.app.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.BatteryManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.getSystemService
import com.checkmate.app.data.*
import com.checkmate.app.ml.ImageClassifier
import com.checkmate.app.ml.TextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.*

/**
 * Manages the capture pipeline for screen content, text extraction, and image analysis.
 */
class CapturePipeline(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val textExtractor = TextExtractor(context)
    private val imageClassifier = ImageClassifier(context)
    private val accessibilityHelper = AccessibilityHelper
    
    private var lastScreenshot: Bitmap? = null
    private var lastUIChangeTime = System.currentTimeMillis()
    private var uiChangeCount = 0
    
    suspend fun captureFrame(): FrameBundle? = withContext(Dispatchers.IO) {
        try {
            val sessionState = SessionManager.getInstance(context).getCurrentSessionState()
            val sessionId = sessionState?.sessionId ?: return@withContext null
            
            // Capture accessibility tree
            val treeSummary = captureAccessibilityTree()
            
            // Capture screenshot
            val screenshot = captureScreenshot()
            
            // Extract OCR text
            val ocrText = if (screenshot != null) {
                textExtractor.extractText(screenshot).take(AppConfig.MAX_OCR_TEXT_LENGTH)
            } else ""
            
            // Classify image content
            val hasImage = if (screenshot != null) {
                imageClassifier.hasSignificantImage(screenshot)
            } else false
            
            // Get image reference if needed
            val imageRef = if (hasImage && screenshot != null) {
                saveTemporaryImage(screenshot, sessionId)
            } else null
            
            // Capture audio transcript delta (placeholder for now)
            val audioTranscriptDelta = captureAudioDelta()
            
            // Device hints
            val deviceHints = DeviceHints(
                battery = getBatteryLevel(),
                powerSaver = isPowerSaveMode()
            )
            
            FrameBundle(
                sessionId = sessionId,
                timestamp = Date(),
                treeSummary = treeSummary,
                ocrText = ocrText,
                hasImage = hasImage,
                imageRef = imageRef,
                audioTranscriptDelta = audioTranscriptDelta,
                deviceHints = deviceHints
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Error capturing frame")
            null
        }
    }
    
    internal suspend fun captureAccessibilityTree(): TreeSummary = withContext(Dispatchers.Main) {
        try {
            val rootNode = accessibilityHelper.getRootNode()
            val currentApp = accessibilityHelper.getCurrentApp()
            
            if (rootNode != null && currentApp != null) {
                val topNodes = extractTopNodes(rootNode)
                val mediaHints = analyzeMediaHints(rootNode)
                
                TreeSummary(
                    appPackage = currentApp.packageName,
                    appReadableName = currentApp.readableName,
                    mediaHints = mediaHints,
                    topNodes = topNodes,
                    urlOrChannelGuess = extractUrlGuess(rootNode),
                    publisherGuess = extractPublisherGuess(rootNode),
                    topicGuesses = extractTopicGuesses(rootNode),
                    confidence = calculateConfidence(topNodes)
                )
            } else {
                // Fallback tree summary
                TreeSummary(
                    appPackage = "unknown",
                    appReadableName = "Unknown App",
                    mediaHints = MediaHints(),
                    topNodes = emptyList(),
                    confidence = 0.1f
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error capturing accessibility tree")
            TreeSummary(
                appPackage = "error",
                appReadableName = "Error",
                mediaHints = MediaHints(),
                topNodes = emptyList(),
                confidence = 0.0f
            )
        }
    }
    
    internal suspend fun captureScreenshot(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (mediaProjection == null) {
                Timber.w("MediaProjection not available")
                return@withContext null
            }
            
            val displayMetrics = context.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi
            
            if (imageReader == null) {
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
            }
            
            if (virtualDisplay == null) {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    0, // flags
                    imageReader?.surface,
                    null, null
                )
            }
            
            // Capture image (simplified - real implementation would need proper synchronization)
            // This is a placeholder - actual implementation would use ImageReader.OnImageAvailableListener
            
            return@withContext lastScreenshot
            
        } catch (e: Exception) {
            Timber.e(e, "Error capturing screenshot")
            null
        }
    }
    
    private fun extractTopNodes(rootNode: AccessibilityNodeInfo): List<TreeNode> {
        val topNodes = mutableListOf<TreeNode>()
        
        try {
            // Extract important UI elements
            extractNodesRecursively(rootNode, topNodes, 0, maxDepth = 3)
        } catch (e: Exception) {
            Timber.e(e, "Error extracting top nodes")
        }
        
        return topNodes.take(10) // Limit to top 10 most important nodes
    }
    
    private fun extractNodesRecursively(
        node: AccessibilityNodeInfo,
        nodes: MutableList<TreeNode>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        
        val text = node.text?.toString()
        val contentDescription = node.contentDescription?.toString()
        val className = node.className?.toString()
        
        val importantText = text ?: contentDescription
        
        if (!importantText.isNullOrBlank() && importantText.length > 3) {
            val role = when {
                className?.contains("TextView") == true -> "text"
                className?.contains("Button") == true -> "button"
                className?.contains("EditText") == true -> "input"
                className?.contains("Image") == true -> "image"
                node.isHeading -> "heading"
                else -> "content"
            }
            
            nodes.add(TreeNode(role = role, text = importantText.trim()))
        }
        
        // Recursively process child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractNodesRecursively(child, nodes, depth + 1, maxDepth)
                child.recycle()
            }
        }
    }
    
    private fun analyzeMediaHints(rootNode: AccessibilityNodeInfo): MediaHints {
        var hasText = false
        var hasImage = false
        var hasVideo = false
        
        // Analyze the UI tree for media content indicators
        analyzeNodeForMedia(rootNode) { className, contentDesc ->
            when {
                className?.contains("TextView") == true -> hasText = true
                className?.contains("ImageView") == true -> hasImage = true
                className?.contains("VideoView") == true -> hasVideo = true
                contentDesc?.contains("video", ignoreCase = true) == true -> hasVideo = true
                contentDesc?.contains("image", ignoreCase = true) == true -> hasImage = true
            }
        }
        
        return MediaHints(
            hasText = hasText,
            hasImage = hasImage,
            hasVideo = hasVideo
        )
    }
    
    private fun analyzeNodeForMedia(
        node: AccessibilityNodeInfo,
        analyzer: (className: String?, contentDesc: String?) -> Unit
    ) {
        analyzer(node.className?.toString(), node.contentDescription?.toString())
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                analyzeNodeForMedia(child, analyzer)
                child.recycle()
            }
        }
    }
    
    private fun extractUrlGuess(rootNode: AccessibilityNodeInfo): String? {
        // Look for URL patterns in the accessibility tree
        val urlPattern = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
        
        return findTextInTree(rootNode) { text ->
            urlPattern.find(text)?.value
        }
    }
    
    private fun extractPublisherGuess(rootNode: AccessibilityNodeInfo): String? {
        // Look for publisher indicators
        val publisherKeywords = listOf("by ", "from ", "source:", "published by")
        
        return findTextInTree(rootNode) { text ->
            publisherKeywords.forEach { keyword ->
                val index = text.indexOf(keyword, ignoreCase = true)
                if (index != -1) {
                    val afterKeyword = text.substring(index + keyword.length).trim()
                    val publisher = afterKeyword.split(" ", ",", "|").firstOrNull()
                    if (!publisher.isNullOrBlank() && publisher.length > 2) {
                        return@findTextInTree publisher
                    }
                }
            }
            null
        }
    }
    
    private fun extractTopicGuesses(rootNode: AccessibilityNodeInfo): List<String> {
        val topics = mutableSetOf<String>()
        
        // Extract potential topics from headings and important text
        extractTopicsFromTree(rootNode, topics)
        
        return topics.take(3).toList()
    }
    
    private fun extractTopicsFromTree(node: AccessibilityNodeInfo, topics: MutableSet<String>) {
        val text = node.text?.toString()
        
        if (!text.isNullOrBlank() && (node.isHeading || text.length > 10)) {
            // Simple topic extraction - in practice, you'd use NLP
            val words = text.split(" ").filter { it.length > 3 }
            topics.addAll(words.take(2))
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTopicsFromTree(child, topics)
                child.recycle()
            }
        }
    }
    
    private fun findTextInTree(node: AccessibilityNodeInfo, predicate: (String) -> String?): String? {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            predicate(text)?.let { return it }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findTextInTree(child, predicate)
                child.recycle()
                if (result != null) return result
            }
        }
        
        return null
    }
    
    private fun calculateConfidence(topNodes: List<TreeNode>): Float {
        return when {
            topNodes.isEmpty() -> 0.1f
            topNodes.size < 3 -> 0.3f
            else -> 0.7f + (topNodes.size * 0.05f).coerceAtMost(0.3f)
        }
    }
    
    private suspend fun saveTemporaryImage(bitmap: Bitmap, sessionId: String): String? {
        return try {
            val timestamp = System.currentTimeMillis()
            val filename = "frame_${sessionId}_$timestamp.jpg"
            
            // Compress and save to temporary storage
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, AppConfig.IMAGE_QUALITY, stream)
            
            // In a real implementation, you'd upload this to S3 or save locally
            // For now, return a placeholder reference
            "temp:///$filename"
            
        } catch (e: Exception) {
            Timber.e(e, "Error saving temporary image")
            null
        }
    }
    
    private fun captureAudioDelta(): String {
        // Placeholder for audio transcription delta
        // Real implementation would integrate with speech-to-text service
        return ""
    }
    
    fun getBatteryLevel(): Float {
        val batteryManager = context.getSystemService<BatteryManager>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.div(100f) ?: 0.5f
        } else {
            0.5f // Fallback
        }
    }
    
    private fun isPowerSaveMode(): Boolean {
        val powerManager = context.getSystemService<android.os.PowerManager>()
        return powerManager?.isPowerSaveMode ?: false
    }
    
    fun hasRapidUIChanges(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastUIChangeTime
        
        return if (timeSinceLastChange < 5000) { // 5 seconds
            uiChangeCount++
            uiChangeCount > 3
        } else {
            uiChangeCount = 0
            false
        }
    }
    
    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
    }
    
    fun cleanup() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        lastScreenshot = null
    }
}
