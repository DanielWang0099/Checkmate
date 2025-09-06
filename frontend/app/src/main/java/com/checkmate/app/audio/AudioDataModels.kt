package com.checkmate.app.audio

/**
 * Audio pipeline data models and enums for enhanced audio management
 */

/**
 * Audio source types supported by the enhanced pipeline
 */
enum class AudioSourceType {
    MICROPHONE,
    SYSTEM_AUDIO,
    MIXED_AUDIO
}

/**
 * Current status of audio capture operations
 */
data class EnhancedAudioStatus(
    val isCapturing: Boolean,
    val audioSource: AudioSourceType,
    val captureMode: AudioCaptureMode,
    val vadEnabled: Boolean,
    val streamingEnabled: Boolean,
    val qualityLevel: AudioQualityLevel,
    val bufferHealth: BufferHealthStatus,
    val processingLatency: Float, // milliseconds
    val captureStartTime: Long,
    val totalBytesProcessed: Long,
    val voiceDetectionRate: Float // percentage of voice detected
)

/**
 * Audio capture modes
 */
enum class AudioCaptureMode {
    LEGACY,
    ENHANCED_MICROPHONE,
    ENHANCED_SYSTEM_AUDIO,
    ADAPTIVE
}

/**
 * Audio quality levels for processing optimization
 */
enum class AudioQualityLevel {
    LOW,      // 8kHz, 16-bit, optimized for battery
    MEDIUM,   // 16kHz, 16-bit, balanced quality/performance
    HIGH,     // 44.1kHz, 16-bit, maximum quality
    ADAPTIVE  // Automatically adjusts based on conditions
}

/**
 * Buffer health status for real-time monitoring
 */
data class BufferHealthStatus(
    val bufferLevel: Float,    // 0.0 to 1.0
    val bufferSize: Int,       // bytes
    val droppedFrames: Int,
    val healthScore: BufferHealth
)

enum class BufferHealth {
    EXCELLENT,  // < 20% buffer usage
    GOOD,       // 20-50% buffer usage
    WARNING,    // 50-80% buffer usage
    CRITICAL    // > 80% buffer usage
}

/**
 * VAD detection result with confidence metrics
 */
data class VADResult(
    val hasVoice: Boolean,
    val confidence: Float,     // 0.0 to 1.0
    val energy: Float,         // Audio energy level
    val threshold: Float,      // Current adaptive threshold
    val spectralFeatures: SpectralFeatures? = null
)

/**
 * Spectral features for advanced voice analysis
 */
data class SpectralFeatures(
    val spectralCentroid: Float,
    val spectralRolloff: Float,
    val zeroCrossingRate: Float,
    val mfccCoefficients: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpectralFeatures

        if (spectralCentroid != other.spectralCentroid) return false
        if (spectralRolloff != other.spectralRolloff) return false
        if (zeroCrossingRate != other.zeroCrossingRate) return false
        if (mfccCoefficients != null) {
            if (other.mfccCoefficients == null) return false
            if (!mfccCoefficients.contentEquals(other.mfccCoefficients)) return false
        } else if (other.mfccCoefficients != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = spectralCentroid.hashCode()
        result = 31 * result + spectralRolloff.hashCode()
        result = 31 * result + zeroCrossingRate.hashCode()
        result = 31 * result + (mfccCoefficients?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Audio streaming status and metrics
 */
data class AudioStreamingStatus(
    val isStreaming: Boolean,
    val connectionStatus: StreamConnectionStatus,
    val bytesPerSecond: Long,
    val packetsPerSecond: Int,
    val bufferDepth: Int,
    val latency: Float,        // milliseconds
    val compressionRatio: Float,
    val errorRate: Float       // percentage
)

enum class StreamConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * Performance metrics for audio processing
 */
data class AudioPerformanceMetrics(
    val cpuUsage: Float,           // percentage
    val memoryUsage: Long,         // bytes
    val batteryImpact: BatteryImpact,
    val thermalState: ThermalState,
    val processingEfficiency: Float, // operations per second
    val qualityScore: Float        // 0.0 to 1.0
)

enum class BatteryImpact {
    MINIMAL,   // < 1% per hour
    LOW,       // 1-3% per hour
    MODERATE,  // 3-7% per hour
    HIGH       // > 7% per hour
}

enum class ThermalState {
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

/**
 * Configuration settings for enhanced audio processing
 */
data class EnhancedAudioConfig(
    val audioSource: AudioSourceType = AudioSourceType.MICROPHONE,
    val qualityLevel: AudioQualityLevel = AudioQualityLevel.MEDIUM,
    val vadThreshold: Float = 0.6f,
    val vadEnabled: Boolean = true,
    val streamingEnabled: Boolean = true,
    val adaptiveProcessing: Boolean = true,
    val bufferSizeMs: Int = 100,
    val compressionEnabled: Boolean = false,
    val noiseReduction: Boolean = true,
    val autoGainControl: Boolean = true,
    val echoCancellation: Boolean = false
)

/**
 * Audio capture status for system audio capture
 */
data class AudioCaptureStatus(
    val isCapturing: Boolean,
    val captureSource: AudioSourceType,
    val isSystemAudioAvailable: Boolean,
    val currentSampleRate: Int,
    val bufferSize: Int,
    val averageLatency: Float,
    val droppedFrames: Int,
    val vadStatus: VADStatus
)

/**
 * Audio streaming statistics
 */
data class AudioStreamingStats(
    val isStreaming: Boolean,
    val connectionStatus: StreamConnectionStatus,
    val bytesPerSecond: Long,
    val packetsPerSecond: Int,
    val bufferDepth: Int,
    val latency: Float,        // milliseconds
    val compressionRatio: Float,
    val errorRate: Float       // percentage
)

/**
 * VAD (Voice Activity Detection) status
 */
data class VADStatus(
    val isActive: Boolean,
    val isCalibrating: Boolean,
    val calibrationProgress: Float,
    val currentThreshold: Float,
    val averageVoiceConfidence: Float,
    val samplesProcessed: Long,
    val voiceDetectionRate: Float
)
