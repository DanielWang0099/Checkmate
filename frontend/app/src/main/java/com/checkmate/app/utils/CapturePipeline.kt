package com.checkmate.app.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.BatteryManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.getSystemService
import com.checkmate.app.audio.*
import com.checkmate.app.data.*
import com.checkmate.app.ml.ImageClassifier
import com.checkmate.app.ml.TextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import com.checkmate.app.debug.PipelineDebugger

/**
 * Manages the capture pipeline for screen content, text extraction, and image analysis.
 */
class CapturePipeline(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val textExtractor = TextExtractor(context)
    private val imageClassifier = ImageClassifier(context)
    private val accessibilityHelper = AccessibilityHelper.getInstance(context)
    private val pipelineDebugger = PipelineDebugger.getInstance()
    
    private var lastScreenshot: Bitmap? = null
    private var lastUIChangeTime = System.currentTimeMillis()
    private var uiChangeCount = 0
    
    suspend fun captureFrame(): FrameBundle? = withContext(Dispatchers.IO) {
        val pipelineStartTime = System.currentTimeMillis()
        
        try {
            pipelineDebugger.logPipelineStart("frame_capture_${System.currentTimeMillis()}")
            
            val sessionState = SessionManager.getInstance(context).getCurrentSessionState()
            val sessionId = sessionState?.sessionId ?: run {
                pipelineDebugger.logError(PipelineDebugger.STAGE_PIPELINE_ASSEMBLY, 
                    Exception("No active session"), "No active session found")
                return@withContext null
            }
            
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_PIPELINE_ASSEMBLY, "Starting frame capture", mapOf(
                "session_id" to sessionId
            ))
            
            // Capture accessibility tree
            pipelineDebugger.logStageStart(PipelineDebugger.STAGE_ACCESSIBILITY_TREE)
            val treeSummary = captureAccessibilityTree()
            pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_ACCESSIBILITY_TREE, true, mapOf(
                "app_package" to treeSummary.appPackage,
                "top_nodes_count" to treeSummary.topNodes.size,
                "confidence" to treeSummary.confidence
            ))
            
            // Capture screenshot
            pipelineDebugger.logStageStart(PipelineDebugger.STAGE_SCREEN_CAPTURE)
            val screenshot = captureScreenshot()
            pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_SCREEN_CAPTURE, screenshot != null, mapOf(
                "has_screenshot" to (screenshot != null),
                "screenshot_size" to if (screenshot != null) "${screenshot.width}x${screenshot.height}" else "null"
            ))
            
            // Extract OCR text
            pipelineDebugger.logStageStart(PipelineDebugger.STAGE_OCR_PROCESSING)
            val ocrText = if (screenshot != null) {
                val extractedText = textExtractor.extractText(screenshot).take(AppConfig.MAX_OCR_TEXT_LENGTH)
                pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_OCR_PROCESSING, true, mapOf(
                    "text_length" to extractedText.length,
                    "has_content" to extractedText.isNotBlank()
                ))
                extractedText
            } else {
                pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_OCR_PROCESSING, false, mapOf("reason" to "no_screenshot"))
                ""
            }
            
            // Classify image content
            pipelineDebugger.logStageStart(PipelineDebugger.STAGE_IMAGE_CLASSIFICATION)
            val hasImage = if (screenshot != null) {
                val result = imageClassifier.hasSignificantImage(screenshot)
                pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_IMAGE_CLASSIFICATION, true, mapOf(
                    "has_significant_image" to result
                ))
                result
            } else {
                pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_IMAGE_CLASSIFICATION, false, mapOf("reason" to "no_screenshot"))
                false
            }
            
            // Get image reference if needed
            val imageRef = if (hasImage && screenshot != null) {
                saveTemporaryImage(screenshot, sessionId)
            } else null
            
            // Capture audio transcript delta (placeholder for now)
            pipelineDebugger.logStageStart(PipelineDebugger.STAGE_AUDIO_CAPTURE)
            val audioTranscriptDelta = try {
                captureAudioDelta()
            } catch (e: Exception) {
                pipelineDebugger.logError(PipelineDebugger.STAGE_AUDIO_CAPTURE, e, "Audio capture failed")
                pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_AUDIO_CAPTURE, false, mapOf("error" to (e.message ?: "Unknown error")))
                ""  // Continue without audio
            }
            if (audioTranscriptDelta.isNotEmpty()) {
                pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_AUDIO_CAPTURE, true, mapOf(
                    "transcript_length" to audioTranscriptDelta.length
                ))
            } else {
                pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_AUDIO_CAPTURE, true, mapOf(
                    "transcript_length" to 0,
                    "note" to "no_new_audio"
                ))
            }
            
            // Device hints
            val deviceHints = DeviceHints(
                battery = getBatteryLevel(),
                powerSaver = isPowerSaveMode()
            )
            
            val frameBundle = FrameBundle(
                sessionId = sessionId,
                timestamp = Date(),
                treeSummary = treeSummary,
                ocrText = ocrText,
                hasImage = hasImage,
                imageRef = imageRef,
                audioTranscriptDelta = audioTranscriptDelta,
                deviceHints = deviceHints
            )
            
            val totalDuration = System.currentTimeMillis() - pipelineStartTime
            pipelineDebugger.logPipelineEnd("frame_capture", true, totalDuration)
            
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_PIPELINE_ASSEMBLY, "Frame capture completed successfully", mapOf(
                "total_duration_ms" to totalDuration,
                "ocr_text_length" to ocrText.length,
                "has_image" to hasImage,
                "audio_length" to audioTranscriptDelta.length
            ))
            
            frameBundle
            
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - pipelineStartTime
            pipelineDebugger.logError(PipelineDebugger.STAGE_PIPELINE_ASSEMBLY, e, "Critical error during frame capture")
            pipelineDebugger.logPipelineEnd("frame_capture", false, totalDuration)
            Timber.e(e, "Error capturing frame")
            null
        }
    }
    
    private suspend fun captureAccessibilityTree(): TreeSummary = withContext(Dispatchers.Main) {
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
    
    private suspend fun captureScreenshot(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (mediaProjection == null) {
                Timber.w("MediaProjection not available")
                return@withContext lastScreenshot // Return cached screenshot if available
            }
            
            val displayMetrics = context.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi
            
            if (imageReader == null) {
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                
                // Set up image listener for real-time capture
                imageReader?.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            lastScreenshot = convertImageToBitmap(image)
                            image.close()
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing captured image")
                    }
                }, null)
            }
            
            if (virtualDisplay == null) {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null, null
                )
            }
            
            // Wait a bit for the image to be captured
            delay(100)
            
            return@withContext lastScreenshot
            
        } catch (e: Exception) {
            Timber.e(e, "Error capturing screenshot")
            lastScreenshot // Return cached version if available
        }
    }
    
    private fun convertImageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            
            bitmap.copyPixelsFromBuffer(buffer)
            
            // Crop to actual screen size if there's padding
            if (rowPadding == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error converting image to bitmap")
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
            
            // Compress image to bytes
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, AppConfig.IMAGE_QUALITY, stream)
            val imageBytes = stream.toByteArray()
            
            // Upload to backend for S3 storage
            val networkManager = NetworkManager(context)
            val uploadResult = networkManager.uploadImage(imageBytes, sessionId, filename)
            
            if (uploadResult != null) {
                uploadResult
            } else {
                // Fallback: save locally and return local reference
                saveImageLocally(imageBytes, filename)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error saving temporary image")
            null
        }
    }
    
    private suspend fun saveImageLocally(imageBytes: ByteArray, filename: String): String? {
        return try {
            val tempDir = File(context.cacheDir, "temp_images")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            
            val imageFile = File(tempDir, filename)
            imageFile.writeBytes(imageBytes)
            
            // Return local file reference
            "file://${imageFile.absolutePath}"
            
        } catch (e: Exception) {
            Timber.e(e, "Error saving image locally")
            null
        }
    }
    
    /**
     * Capture audio delta using enhanced audio pipeline when available
     */
    private fun captureAudioDelta(): String {
        return try {
            val audioService = com.checkmate.app.services.AudioCaptureService.instance
            
            if (audioService != null) {
                val audioStatus = audioService.getAudioCaptureStatus()
                
                // If enhanced audio is active, get richer audio information
                if (audioStatus.vadEnabled && audioStatus.streamingEnabled) {
                    val legacyDelta = com.checkmate.app.services.AudioCaptureService.getLatestAudioDelta()
                    
                    // Enhanced audio provides additional context
                    if (legacyDelta.isNotBlank()) {
                        val enhancedInfo = buildString {
                            append(legacyDelta)
                            
                            // Add enhanced audio context
                            if (audioStatus.voiceDetectionRate > 50.0f) {
                                append(" [Enhanced: High voice activity]")
                            }
                            
                            when (audioStatus.captureMode) {
                                AudioCaptureMode.ENHANCED_SYSTEM_AUDIO -> append(" [System Audio]")
                                AudioCaptureMode.ENHANCED_MICROPHONE -> append(" [Enhanced Mic]")
                                else -> {} // Legacy mode
                            }
                        }
                        enhancedInfo
                    } else {
                        ""
                    }
                } else {
                    // Fallback to legacy audio delta
                    com.checkmate.app.services.AudioCaptureService.getLatestAudioDelta()
                }
            } else {
                ""
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting enhanced audio delta")
            // Fallback to legacy method
            try {
                com.checkmate.app.services.AudioCaptureService.getLatestAudioDelta()
            } catch (fallbackError: Exception) {
                Timber.e(fallbackError, "Error in fallback audio delta")
                ""
            }
        }
    }
    
    fun getBatteryLevel(): Float {
        val batteryManager = context.getSystemService<BatteryManager>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.div(100f) ?: 0.5f
        } else {
            0.5f // Fallback
        }
    }
    
    fun isPowerSaveMode(): Boolean {
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
    
    suspend fun captureScreenshot(
        bitmap: Bitmap,
        contentType: ContentType,
        captureType: CaptureType
    ) {
        try {
            val sessionState = SessionManager.getInstance(context).getCurrentSessionState()
            val sessionId = sessionState?.sessionId ?: return
            
            // Extract OCR text
            val ocrText = textExtractor.extractText(bitmap).take(AppConfig.MAX_OCR_TEXT_LENGTH)
            
            // Classify image content
            val hasImage = imageClassifier.hasSignificantImage(bitmap)
            
            // Get image reference if needed
            val imageRef = if (hasImage) {
                saveTemporaryImage(bitmap, sessionId)
            } else null
            
            // Create simplified frame bundle for screenshot
            val frameBundle = FrameBundle(
                sessionId = sessionId,
                timestamp = Date(),
                treeSummary = TreeSummary(
                    appPackage = "screenshot",
                    appReadableName = "Screenshot Capture",
                    mediaHints = MediaHints(hasText = ocrText.isNotBlank(), hasImage = hasImage),
                    topNodes = emptyList(),
                    confidence = 0.5f
                ),
                ocrText = ocrText,
                hasImage = hasImage,
                imageRef = imageRef,
                audioTranscriptDelta = "",
                deviceHints = DeviceHints(
                    battery = getBatteryLevel(),
                    powerSaver = isPowerSaveMode()
                )
            )
            
            // Send to network manager
            NetworkManager(context).sendFrameBundle(frameBundle)
            
        } catch (e: Exception) {
            Timber.e(e, "Error processing screenshot")
        }
    }
    
    suspend fun captureAccessibilityTree(
        rootNode: AccessibilityNodeInfo,
        sourceApp: AppSourceInfo?,
        contentType: ContentType,
        captureType: CaptureType
    ) {
        try {
            val sessionState = SessionManager.getInstance(context).getCurrentSessionState()
            val sessionId = sessionState?.sessionId ?: return
            
            val treeSummary = extractTreeSummaryFromNode(rootNode, sourceApp, contentType)
            
            // Capture screenshot if available
            val screenshot = captureScreenshot()
            val ocrText = if (screenshot != null) {
                textExtractor.extractText(screenshot).take(AppConfig.MAX_OCR_TEXT_LENGTH)
            } else ""
            
            val hasImage = if (screenshot != null) {
                imageClassifier.hasSignificantImage(screenshot)
            } else false
            
            val imageRef = if (hasImage && screenshot != null) {
                saveTemporaryImage(screenshot, sessionId)
            } else null
            
            val frameBundle = FrameBundle(
                sessionId = sessionId,
                timestamp = Date(),
                treeSummary = treeSummary,
                ocrText = ocrText,
                hasImage = hasImage,
                imageRef = imageRef,
                audioTranscriptDelta = captureAudioDelta(),
                deviceHints = DeviceHints(
                    battery = getBatteryLevel(),
                    powerSaver = isPowerSaveMode()
                )
            )
            
            // Send to network manager
            NetworkManager(context).sendFrameBundle(frameBundle)
            
        } catch (e: Exception) {
            Timber.e(e, "Error processing accessibility tree")
        }
    }
    
    private suspend fun extractTreeSummaryFromNode(
        rootNode: AccessibilityNodeInfo,
        sourceApp: AppSourceInfo?,
        contentType: ContentType
    ): TreeSummary {
        val topNodes = extractTopNodes(rootNode)
        val mediaHints = analyzeMediaHints(rootNode)
        
        return TreeSummary(
            appPackage = sourceApp?.packageName ?: "unknown",
            appReadableName = sourceApp?.readableName ?: "Unknown App",
            mediaHints = mediaHints,
            topNodes = topNodes,
            urlOrChannelGuess = extractUrlGuess(rootNode),
            publisherGuess = extractPublisherGuess(rootNode),
            topicGuesses = extractTopicGuesses(rootNode),
            confidence = calculateConfidence(topNodes)
        )
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
    
    companion object {
        @Volatile
        private var INSTANCE: CapturePipeline? = null
        
        fun getInstance(context: Context): CapturePipeline {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CapturePipeline(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        fun clearInstance() {
            synchronized(this) {
                INSTANCE?.cleanup()
                INSTANCE = null
            }
        }
    }
}
