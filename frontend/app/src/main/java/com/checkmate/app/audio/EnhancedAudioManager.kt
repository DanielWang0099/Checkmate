package com.checkmate.app.audio

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import com.checkmate.app.services.AudioCaptureService
import timber.log.Timber

/**
 * Enhanced Audio Pipeline Manager - Integration utility for seamless adoption
 * of advanced audio features while maintaining backward compatibility.
 * 
 * This manager provides a unified interface for:
 * - Automatic capability detection
 * - Graceful fallback to legacy audio capture
 * - Simplified enhanced audio activation
 * - Performance monitoring and optimization
 */
class EnhancedAudioManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: EnhancedAudioManager? = null
        
        @Volatile
        private var pendingMediaProjection: MediaProjection? = null
        
        fun getInstance(context: Context): EnhancedAudioManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EnhancedAudioManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        fun setMediaProjectionForService(mediaProjection: MediaProjection) {
            pendingMediaProjection = mediaProjection
        }
        
        fun getAndClearPendingMediaProjection(): MediaProjection? {
            val projection = pendingMediaProjection
            pendingMediaProjection = null
            return projection
        }
    }

    private var vadProcessor: VADProcessor? = null
    private var enhancedCapabilities: EnhancedAudioCapabilities? = null

    /**
     * Initialize enhanced audio capabilities
     */
    fun initialize(): EnhancedAudioCapabilities {
        if (enhancedCapabilities != null) {
            return enhancedCapabilities!!
        }

        val capabilities = detectAudioCapabilities()
        enhancedCapabilities = capabilities
        
        // Initialize VAD processor for capability testing
        if (capabilities.supportsVAD) {
            vadProcessor = VADProcessor(context)
        }
        
        Timber.i("Enhanced audio capabilities: $capabilities")
        return capabilities
    }

    /**
     * Detect available audio capabilities on this device
     */
    private fun detectAudioCapabilities(): EnhancedAudioCapabilities {
        return EnhancedAudioCapabilities(
            supportsSystemAudioCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            supportsVAD = true, // VAD is always available
            supportsAudioStreaming = true, // Streaming is always available
            supportsAdaptiveProcessing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
            recommendedAudioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AudioSourceType.SYSTEM_AUDIO
            } else {
                AudioSourceType.MICROPHONE
            },
            devicePerformanceClass = detectPerformanceClass()
        )
    }

    /**
     * Detect device performance class for audio processing optimization
     */
    private fun detectPerformanceClass(): DevicePerformanceClass {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB
        val processorCount = runtime.availableProcessors()
        
        return when {
            maxMemory >= 512 && processorCount >= 8 -> DevicePerformanceClass.HIGH
            maxMemory >= 256 && processorCount >= 4 -> DevicePerformanceClass.MEDIUM
            else -> DevicePerformanceClass.LOW
        }
    }

    /**
     * Start enhanced audio capture with automatic capability detection
     */
    fun startEnhancedCapture(mediaProjection: MediaProjection? = null): AudioCaptureResult {
        return try {
            val capabilities = enhancedCapabilities ?: initialize()
            
            val intent = Intent(context, AudioCaptureService::class.java)
            
            if (capabilities.supportsSystemAudioCapture && mediaProjection != null) {
                // Use system audio capture - MediaProjection needs to be stored statically
                // since it cannot be passed via Intent extras
                intent.action = AudioCaptureService.ACTION_START_ENHANCED_CAPTURE
                
                // Store MediaProjection in a static way for the service to access
                setMediaProjectionForService(mediaProjection)
                
                context.startService(intent)
                AudioCaptureResult.success(AudioSourceType.SYSTEM_AUDIO, "Enhanced system audio capture started")
                
            } else if (capabilities.supportsVAD) {
                // Use VAD-enhanced microphone capture
                intent.action = AudioCaptureService.ACTION_START_CAPTURE
                intent.putExtra(AudioCaptureService.EXTRA_ENHANCED_MODE, true)
                
                context.startService(intent)
                AudioCaptureResult.success(AudioSourceType.MICROPHONE, "Enhanced microphone capture started")
                
            } else {
                // Fallback to legacy capture
                intent.action = AudioCaptureService.ACTION_START_CAPTURE
                
                context.startService(intent)
                AudioCaptureResult.success(AudioSourceType.MICROPHONE, "Legacy audio capture started")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to start enhanced audio capture")
            AudioCaptureResult.failure("Failed to start audio capture: ${e.message}")
        }
    }

    /**
     * Stop audio capture
     */
    fun stopCapture(): Boolean {
        return try {
            val intent = Intent(context, AudioCaptureService::class.java)
            intent.action = AudioCaptureService.ACTION_STOP_CAPTURE
            context.startService(intent)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop audio capture")
            false
        }
    }

    /**
     * Get current audio capture status
     */
    fun getAudioStatus(): EnhancedAudioStatus? {
        return AudioCaptureService.instance?.getAudioCaptureStatus()
    }

    /**
     * Test VAD performance on device
     */
    suspend fun testVADPerformance(): VADPerformanceResult {
        val vadProcessor = this.vadProcessor ?: return VADPerformanceResult(
            isSupported = false,
            averageProcessingTime = 0f,
            accuracyScore = 0f,
            recommendedThreshold = 0.5f
        )
        
        val testSamples = 10
        val processingTimes = mutableListOf<Long>()
        
        repeat(testSamples) {
            // Create test audio
            val testAudio = createTestAudio(1000) // 1 second
            
            val startTime = System.nanoTime()
            val result = vadProcessor.hasVoice(testAudio)
            val endTime = System.nanoTime()
            
            processingTimes.add((endTime - startTime) / 1_000_000) // Convert to milliseconds
        }
        
        val averageTime = processingTimes.average().toFloat()
        val accuracyScore = estimateVADAccuracy(vadProcessor)
        
        return VADPerformanceResult(
            isSupported = true,
            averageProcessingTime = averageTime,
            accuracyScore = accuracyScore,
            recommendedThreshold = 0.6f // Based on performance testing
        )
    }

    /**
     * Estimate VAD accuracy using synthetic test cases
     */
    private fun estimateVADAccuracy(vadProcessor: VADProcessor): Float {
        var correctDetections = 0
        val totalTests = 20
        
        repeat(totalTests / 2) {
            // Test silence detection
            val silence = ByteArray(8000) // 0.5 seconds of silence
            val silenceResult = vadProcessor.hasVoice(silence)
            if (!silenceResult.hasVoice) correctDetections++
            
            // Test voice detection
            val voice = createTestAudio(500) // 0.5 seconds of synthetic voice
            val voiceResult = vadProcessor.hasVoice(voice)
            if (voiceResult.hasVoice) correctDetections++
        }
        
        return correctDetections.toFloat() / totalTests
    }

    /**
     * Create synthetic test audio for performance testing
     */
    private fun createTestAudio(durationMs: Int): ByteArray {
        val sampleRate = 16000
        val samples = (sampleRate * durationMs) / 1000
        val audioData = ByteArray(samples * 2)
        
        // Generate simple sine wave
        for (i in 0 until samples) {
            val sample = (kotlin.math.sin(2 * kotlin.math.PI * 440.0 * i / sampleRate) * 16383).toInt()
            audioData[i * 2] = (sample and 0xFF).toByte()
            audioData[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        
        return audioData
    }

    /**
     * Get optimization recommendations based on device capabilities
     */
    fun getOptimizationRecommendations(): List<AudioOptimizationRecommendation> {
        val capabilities = enhancedCapabilities ?: initialize()
        val recommendations = mutableListOf<AudioOptimizationRecommendation>()
        
        when (capabilities.devicePerformanceClass) {
            DevicePerformanceClass.HIGH -> {
                recommendations.add(
                    AudioOptimizationRecommendation(
                        type = OptimizationType.PROCESSING_FREQUENCY,
                        description = "Enable high-frequency VAD processing for better accuracy",
                        impact = OptimizationImpact.ACCURACY_IMPROVEMENT
                    )
                )
                recommendations.add(
                    AudioOptimizationRecommendation(
                        type = OptimizationType.STREAMING_QUALITY,
                        description = "Use high-quality audio streaming with minimal compression",
                        impact = OptimizationImpact.QUALITY_IMPROVEMENT
                    )
                )
            }
            
            DevicePerformanceClass.MEDIUM -> {
                recommendations.add(
                    AudioOptimizationRecommendation(
                        type = OptimizationType.BALANCED_PROCESSING,
                        description = "Use balanced VAD processing with moderate accuracy",
                        impact = OptimizationImpact.BALANCED
                    )
                )
            }
            
            DevicePerformanceClass.LOW -> {
                recommendations.add(
                    AudioOptimizationRecommendation(
                        type = OptimizationType.POWER_OPTIMIZATION,
                        description = "Reduce VAD processing frequency to save battery",
                        impact = OptimizationImpact.BATTERY_OPTIMIZATION
                    )
                )
                recommendations.add(
                    AudioOptimizationRecommendation(
                        type = OptimizationType.COMPRESSION,
                        description = "Use audio compression to reduce bandwidth usage",
                        impact = OptimizationImpact.BANDWIDTH_OPTIMIZATION
                    )
                )
            }
        }
        
        if (!capabilities.supportsSystemAudioCapture) {
            recommendations.add(
                AudioOptimizationRecommendation(
                    type = OptimizationType.FALLBACK_STRATEGY,
                    description = "System audio capture not available, using microphone fallback",
                    impact = OptimizationImpact.COMPATIBILITY
                )
            )
        }
        
        return recommendations
    }
}

// Data classes for enhanced audio management

data class EnhancedAudioCapabilities(
    val supportsSystemAudioCapture: Boolean,
    val supportsVAD: Boolean,
    val supportsAudioStreaming: Boolean,
    val supportsAdaptiveProcessing: Boolean,
    val recommendedAudioSource: AudioSourceType,
    val devicePerformanceClass: DevicePerformanceClass
)

enum class DevicePerformanceClass {
    LOW, MEDIUM, HIGH
}

data class AudioCaptureResult private constructor(
    val isSuccess: Boolean,
    val audioSource: AudioSourceType?,
    val message: String
) {
    companion object {
        fun success(audioSource: AudioSourceType, message: String) = 
            AudioCaptureResult(true, audioSource, message)
        
        fun failure(message: String) = 
            AudioCaptureResult(false, null, message)
    }
}

data class VADPerformanceResult(
    val isSupported: Boolean,
    val averageProcessingTime: Float, // milliseconds
    val accuracyScore: Float, // 0.0 to 1.0
    val recommendedThreshold: Float
)

data class AudioOptimizationRecommendation(
    val type: OptimizationType,
    val description: String,
    val impact: OptimizationImpact
)

enum class OptimizationType {
    PROCESSING_FREQUENCY,
    STREAMING_QUALITY,
    BALANCED_PROCESSING,
    POWER_OPTIMIZATION,
    COMPRESSION,
    FALLBACK_STRATEGY
}

enum class OptimizationImpact {
    ACCURACY_IMPROVEMENT,
    QUALITY_IMPROVEMENT,
    BALANCED,
    BATTERY_OPTIMIZATION,
    BANDWIDTH_OPTIMIZATION,
    COMPATIBILITY
}
