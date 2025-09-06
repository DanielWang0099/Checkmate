package com.checkmate.app.examples

import android.content.Context
import android.media.projection.MediaProjection
import com.checkmate.app.audio.EnhancedAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Example demonstrating how to integrate Enhanced Audio Pipeline
 * into existing Android applications with minimal code changes.
 * 
 * This example shows:
 * 1. Simple migration from legacy audio capture
 * 2. Automatic capability detection and optimization
 * 3. Performance monitoring and adaptation
 * 4. Graceful fallback handling
 */
class EnhancedAudioIntegrationExample(private val context: Context) {
    
    private val audioManager = EnhancedAudioManager.getInstance(context)
    private val exampleScope = CoroutineScope(Dispatchers.Main)
    
    /**
     * Example 1: Simple migration from legacy audio capture
     * Replace your existing audio capture code with this enhanced version
     */
    fun simpleMigrationExample() {
        Timber.i("=== Simple Migration Example ===")
        
        // Before: Basic audio capture
        // startBasicAudioCapture()
        
        // After: Enhanced audio capture with automatic capabilities
        val result = audioManager.startEnhancedCapture()
        
        if (result.isSuccess) {
            Timber.i("Enhanced audio capture started with source: ${result.audioSource}")
        } else {
            Timber.e("Failed to start enhanced capture: ${result.message}")
        }
    }
    
    /**
     * Example 2: Advanced integration with system audio (Android 10+)
     * Shows how to handle MediaProjection for system audio capture
     */
    fun systemAudioExample(mediaProjection: MediaProjection?) {
        Timber.i("=== System Audio Integration Example ===")
        
        // Initialize and check capabilities
        val capabilities = audioManager.initialize()
        
        if (capabilities.supportsSystemAudioCapture && mediaProjection != null) {
            Timber.i("System audio capture supported, using MediaProjection")
            
            val result = audioManager.startEnhancedCapture(mediaProjection)
            
            if (result.isSuccess) {
                Timber.i("System audio capture started successfully")
                
                // Monitor audio status
                monitorAudioStatus()
                
            } else {
                Timber.w("System audio failed, falling back to microphone")
                fallbackToMicrophone()
            }
            
        } else {
            Timber.i("System audio not available, using enhanced microphone capture")
            fallbackToMicrophone()
        }
    }
    
    /**
     * Example 3: Performance optimization based on device capabilities
     */
    fun performanceOptimizationExample() {
        Timber.i("=== Performance Optimization Example ===")
        
        exampleScope.launch {
            // Test VAD performance on this device
            val vadPerformance = audioManager.testVADPerformance()
            
            Timber.i("VAD Performance Results:")
            Timber.i("- Supported: ${vadPerformance.isSupported}")
            Timber.i("- Average Processing Time: ${vadPerformance.averageProcessingTime}ms")
            Timber.i("- Accuracy Score: ${vadPerformance.accuracyScore}")
            Timber.i("- Recommended Threshold: ${vadPerformance.recommendedThreshold}")
            
            // Get optimization recommendations
            val recommendations = audioManager.getOptimizationRecommendations()
            
            Timber.i("Optimization Recommendations:")
            recommendations.forEach { recommendation ->
                Timber.i("- ${recommendation.type}: ${recommendation.description}")
                Timber.i("  Impact: ${recommendation.impact}")
            }
            
            // Apply recommendations (example)
            applyOptimizations(recommendations)
        }
    }
    
    /**
     * Example 4: Real-time monitoring and adaptive adjustment
     */
    private fun monitorAudioStatus() {
        exampleScope.launch {
            // Get current status
            val status = audioManager.getAudioStatus()
            
            status?.let {
                Timber.i("Audio Status Monitor:")
                Timber.i("- Capturing: ${it.isCapturing}")
                Timber.i("- Audio Source: ${it.audioSource}")
                Timber.i("- Capture Mode: ${it.captureMode}")
                Timber.i("- VAD Enabled: ${it.vadEnabled}")
                Timber.i("- Streaming: ${it.streamingEnabled}")
                Timber.i("- Quality Level: ${it.qualityLevel}")
                Timber.i("- Buffer Health: ${it.bufferHealth.healthScore}")
                Timber.i("- Processing Latency: ${it.processingLatency}ms")
                Timber.i("- Voice Detection Rate: ${it.voiceDetectionRate}%")
                
                // Adaptive adjustments based on status
                adaptiveAdjustments(it)
            }
        }
    }
    
    /**
     * Example 5: Graceful fallback handling
     */
    private fun fallbackToMicrophone() {
        Timber.i("Implementing graceful fallback to microphone capture")
        
        val result = audioManager.startEnhancedCapture() // Will auto-detect best available
        
        if (result.isSuccess) {
            Timber.i("Fallback successful with source: ${result.audioSource}")
        } else {
            Timber.e("All audio capture methods failed: ${result.message}")
            // Handle complete failure case
            handleAudioCaptureFailure(result.message)
        }
    }
    
    /**
     * Apply optimization recommendations automatically
     */
    private fun applyOptimizations(recommendations: List<com.checkmate.app.audio.AudioOptimizationRecommendation>) {
        recommendations.forEach { recommendation ->
            when (recommendation.type) {
                com.checkmate.app.audio.OptimizationType.PROCESSING_FREQUENCY -> {
                    Timber.i("Applying high-frequency processing optimization")
                    // Implementation would adjust VAD processing frequency
                }
                
                com.checkmate.app.audio.OptimizationType.POWER_OPTIMIZATION -> {
                    Timber.i("Applying power optimization")
                    // Implementation would reduce processing intensity
                }
                
                com.checkmate.app.audio.OptimizationType.COMPRESSION -> {
                    Timber.i("Enabling audio compression for bandwidth optimization")
                    // Implementation would enable compression
                }
                
                else -> {
                    Timber.i("Optimization type ${recommendation.type} noted for manual implementation")
                }
            }
        }
    }
    
    /**
     * Adaptive adjustments based on real-time status
     */
    private fun adaptiveAdjustments(status: com.checkmate.app.audio.EnhancedAudioStatus) {
        // Buffer health management
        when (status.bufferHealth.healthScore) {
            com.checkmate.app.audio.BufferHealth.CRITICAL -> {
                Timber.w("Buffer health critical, reducing processing load")
                // Implementation would reduce processing intensity
            }
            
            com.checkmate.app.audio.BufferHealth.WARNING -> {
                Timber.w("Buffer health warning, monitoring closely")
                // Implementation would increase monitoring frequency
            }
            
            else -> {
                // Buffer health is good, continue normal operation
            }
        }
        
        // Latency management
        if (status.processingLatency > 100.0f) {
            Timber.w("High processing latency detected: ${status.processingLatency}ms")
            // Implementation would optimize processing pipeline
        }
        
        // Voice detection rate analysis
        if (status.voiceDetectionRate < 30.0f) {
            Timber.i("Low voice detection rate, VAD threshold may need adjustment")
            // Implementation would adjust VAD sensitivity
        }
    }
    
    /**
     * Handle complete audio capture failure
     */
    private fun handleAudioCaptureFailure(errorMessage: String) {
        Timber.e("Complete audio capture failure: $errorMessage")
        
        // Implementation would:
        // 1. Notify user about audio issues
        // 2. Provide manual retry options
        // 3. Switch to alternative input methods
        // 4. Log detailed error information for debugging
        
        // Example user notification
        notifyUserOfAudioIssue(errorMessage)
    }
    
    /**
     * Stop enhanced audio capture
     */
    fun stopAudioCapture() {
        Timber.i("Stopping enhanced audio capture")
        
        val success = audioManager.stopCapture()
        
        if (success) {
            Timber.i("Audio capture stopped successfully")
        } else {
            Timber.e("Failed to stop audio capture")
        }
    }
    
    /**
     * Example user notification (placeholder)
     */
    private fun notifyUserOfAudioIssue(errorMessage: String) {
        // Implementation would show user-friendly error dialog
        Timber.i("Would show user notification: Audio capture unavailable - $errorMessage")
    }
}

/**
 * Usage example in Activity or Fragment:
 * 
 * class MainActivity : AppCompatActivity() {
 *     private lateinit var audioExample: EnhancedAudioIntegrationExample
 *     
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         
 *         audioExample = EnhancedAudioIntegrationExample(this)
 *         
 *         // Simple integration
 *         audioExample.simpleMigrationExample()
 *         
 *         // Advanced integration with MediaProjection
 *         // audioExample.systemAudioExample(mediaProjection)
 *         
 *         // Performance optimization
 *         audioExample.performanceOptimizationExample()
 *     }
 *     
 *     override fun onDestroy() {
 *         super.onDestroy()
 *         audioExample.stopAudioCapture()
 *     }
 * }
 */
