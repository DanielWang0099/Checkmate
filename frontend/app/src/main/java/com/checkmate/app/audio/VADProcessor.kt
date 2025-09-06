package com.checkmate.app.audio

import android.content.Context
import com.checkmate.app.data.AppConfig
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.math.*

/**
 * Voice Activity Detection (VAD) processor for intelligent audio capture.
 * A    /**
     * Get current VAD processor status
     */
    fun getVADStatus(): VADStatus {
        return VADStatus(
            isActive = !isCalibrating, // VAD is active when not calibrating
            isCalibrating = isCalibrating,
            calibrationProgress = if (maxCalibrationSamples > 0) {
                (calibrationSamples.toFloat() / maxCalibrationSamples).coerceIn(0.0f, 1.0f)
            } else 0.0f,
            currentThreshold = adaptiveThreshold,
            averageVoiceConfidence = 0.75f, // Example value - would be calculated from recent detections
            samplesProcessed = calibrationSamples.toLong(),
            voiceDetectionRate = 65.0f // Example value - would be calculated from recent detections
        )
    }data to detect speech vs silence/noise, reducing unnecessary processing.
 * 
 * Features:
 * - Real-time voice detection using energy and spectral analysis
 * - Adaptive threshold adjustment based on ambient noise
 * - Efficient processing with minimal CPU impact
 * - Integration with existing AudioCaptureService architecture
 */
class VADProcessor(private val context: Context) {

    companion object {
        private const val SILENCE_THRESHOLD_DB = -40.0f
        private const val VOICE_ENERGY_THRESHOLD = 0.01f
        private const val MIN_VOICE_DURATION_MS = 500L
        private const val ADAPTIVE_WINDOW_SIZE = 10
        private const val SPECTRAL_CENTROID_VOICE_MIN = 300.0f
        private const val SPECTRAL_CENTROID_VOICE_MAX = 3400.0f
    }

    private var backgroundNoiseLevel = SILENCE_THRESHOLD_DB
    private var adaptiveThreshold = VOICE_ENERGY_THRESHOLD
    private val energyHistory = mutableListOf<Float>()
    private var isVoiceActive = false
    private var isCalibrating = true
    private var calibrationSamples = 0
    private val maxCalibrationSamples = 50

    /**
     * Analyze audio chunk to determine if it contains voice activity.
     * 
     * @param audioData Raw audio samples (16-bit PCM)
     * @param sampleRate Audio sample rate (typically 16000 Hz)
     * @return Triple of (hasVoice, confidence, voiceMetrics)
     */
    fun hasVoice(audioData: ByteArray, sampleRate: Int = AppConfig.AUDIO_SAMPLE_RATE): VoiceDetectionResult {
        return try {
            if (audioData.isEmpty()) {
                return VoiceDetectionResult(false, 0.0f, VoiceMetrics.empty())
            }

            // Convert byte array to float samples
            val samples = convertToFloatSamples(audioData)
            
            // Calculate multiple voice detection features
            val energy = calculateRMSEnergy(samples)
            val zeroCrossingRate = calculateZeroCrossingRate(samples)
            val spectralCentroid = calculateSpectralCentroid(samples, sampleRate)
            val spectralRolloff = calculateSpectralRolloff(samples, sampleRate)
            
            // Create voice metrics
            val metrics = VoiceMetrics(
                energy = energy,
                zeroCrossingRate = zeroCrossingRate,
                spectralCentroid = spectralCentroid,
                spectralRolloff = spectralRolloff,
                backgroundNoise = backgroundNoiseLevel
            )
            
            // Update adaptive threshold if still calibrating
            if (isCalibrating) {
                updateCalibration(energy)
            }
            
            // Multi-feature voice detection
            val voiceConfidence = calculateVoiceConfidence(metrics)
            val hasVoiceActivity = voiceConfidence > 0.5f
            
            // Update background noise estimation
            if (!hasVoiceActivity) {
                updateBackgroundNoise(energy)
            }
            
            Timber.d("VAD: voice=$hasVoiceActivity, confidence=$voiceConfidence, energy=$energy")
            
            VoiceDetectionResult(hasVoiceActivity, voiceConfidence, metrics)
            
        } catch (e: Exception) {
            Timber.e(e, "Error in voice activity detection")
            VoiceDetectionResult(false, 0.0f, VoiceMetrics.empty())
        }
    }

    /**
     * Convert 16-bit PCM byte array to float samples normalized to [-1, 1]
     */
    private fun convertToFloatSamples(audioData: ByteArray): FloatArray {
        val samples = FloatArray(audioData.size / 2)
        
        for (i in samples.indices) {
            val byteIndex = i * 2
            if (byteIndex + 1 < audioData.size) {
                // Convert little-endian 16-bit samples to float
                val sample = (audioData[byteIndex].toInt() and 0xFF) or 
                           ((audioData[byteIndex + 1].toInt() and 0xFF) shl 8)
                
                // Convert to signed 16-bit and normalize
                val signedSample = if (sample > 32767) sample - 65536 else sample
                samples[i] = signedSample / 32768.0f
            }
        }
        
        return samples
    }

    /**
     * Calculate RMS (Root Mean Square) energy of audio samples
     */
    private fun calculateRMSEnergy(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0.0f
        
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        
        return sqrt(sum / samples.size).toFloat()
    }

    /**
     * Calculate zero crossing rate - indicator of speech vs noise
     */
    private fun calculateZeroCrossingRate(samples: FloatArray): Float {
        if (samples.size < 2) return 0.0f
        
        var zeroCrossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) || 
                (samples[i] < 0 && samples[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        
        return zeroCrossings.toFloat() / (samples.size - 1)
    }

    /**
     * Calculate spectral centroid - frequency center of mass
     */
    private fun calculateSpectralCentroid(samples: FloatArray, sampleRate: Int): Float {
        if (samples.isEmpty()) return 0.0f
        
        // Simple approximation using high-frequency content
        var highFreqEnergy = 0.0f
        var totalEnergy = 0.0f
        
        // Approximate high-frequency detection using sample differences
        for (i in 1 until samples.size) {
            val diff = abs(samples[i] - samples[i - 1])
            highFreqEnergy += diff
            totalEnergy += abs(samples[i])
        }
        
        return if (totalEnergy > 0) {
            (highFreqEnergy / totalEnergy) * (sampleRate / 4.0f) // Rough approximation
        } else {
            0.0f
        }
    }

    /**
     * Calculate spectral rolloff - frequency below which 85% of energy is contained
     */
    private fun calculateSpectralRolloff(samples: FloatArray, sampleRate: Int): Float {
        // Simplified approximation - in practice would use FFT
        val centroid = calculateSpectralCentroid(samples, sampleRate)
        return centroid * 1.5f // Rough approximation
    }

    /**
     * Calculate overall voice confidence based on multiple features
     */
    private fun calculateVoiceConfidence(metrics: VoiceMetrics): Float {
        var confidence = 0.0f
        
        // Energy-based confidence (40% weight)
        val energyConfidence = when {
            metrics.energy > adaptiveThreshold * 2 -> 1.0f
            metrics.energy > adaptiveThreshold -> (metrics.energy / adaptiveThreshold - 1.0f).coerceIn(0.0f, 1.0f)
            else -> 0.0f
        }
        confidence += energyConfidence * 0.4f
        
        // Zero crossing rate confidence (20% weight)
        val zcrConfidence = when {
            metrics.zeroCrossingRate in 0.05f..0.3f -> 1.0f // Typical speech range
            metrics.zeroCrossingRate < 0.05f -> metrics.zeroCrossingRate / 0.05f
            else -> max(0.0f, 1.0f - (metrics.zeroCrossingRate - 0.3f) / 0.2f)
        }
        confidence += zcrConfidence * 0.2f
        
        // Spectral centroid confidence (25% weight)
        val spectralConfidence = when {
            metrics.spectralCentroid in SPECTRAL_CENTROID_VOICE_MIN..SPECTRAL_CENTROID_VOICE_MAX -> 1.0f
            metrics.spectralCentroid < SPECTRAL_CENTROID_VOICE_MIN -> 
                (metrics.spectralCentroid / SPECTRAL_CENTROID_VOICE_MIN).coerceIn(0.0f, 1.0f)
            else -> max(0.0f, 1.0f - (metrics.spectralCentroid - SPECTRAL_CENTROID_VOICE_MAX) / 1000.0f)
        }
        confidence += spectralConfidence * 0.25f
        
        // Background noise adaptation (15% weight)
        val noiseConfidence = if (metrics.energy > backgroundNoiseLevel + 10) 1.0f else 0.0f
        confidence += noiseConfidence * 0.15f
        
        return confidence.coerceIn(0.0f, 1.0f)
    }

    /**
     * Update calibration during initial samples
     */
    private fun updateCalibration(energy: Float) {
        energyHistory.add(energy)
        calibrationSamples++
        
        // Keep only recent samples for calibration
        if (energyHistory.size > ADAPTIVE_WINDOW_SIZE) {
            energyHistory.removeAt(0)
        }
        
        // Update adaptive threshold based on recent energy levels
        if (energyHistory.isNotEmpty()) {
            val avgEnergy = energyHistory.average().toFloat()
            val stdDev = calculateStandardDeviation(energyHistory)
            adaptiveThreshold = (avgEnergy + stdDev * 2).coerceAtLeast(VOICE_ENERGY_THRESHOLD)
        }
        
        // Complete calibration after sufficient samples
        if (calibrationSamples >= maxCalibrationSamples) {
            isCalibrating = false
            Timber.i("VAD calibration complete. Adaptive threshold: $adaptiveThreshold")
        }
    }

    /**
     * Update background noise level estimation
     */
    private fun updateBackgroundNoise(energy: Float) {
        val energyDb = 20 * log10(energy.coerceAtLeast(0.0001f))
        backgroundNoiseLevel = backgroundNoiseLevel * 0.95f + energyDb * 0.05f // Slow adaptation
    }

    /**
     * Calculate standard deviation of energy samples
     */
    private fun calculateStandardDeviation(values: List<Float>): Float {
        if (values.size < 2) return 0.0f
        
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance).toFloat()
    }

    /**
     * Reset calibration (useful when switching audio sources)
     */
    fun resetCalibration() {
        isCalibrating = true
        calibrationSamples = 0
        energyHistory.clear()
        adaptiveThreshold = VOICE_ENERGY_THRESHOLD
        backgroundNoiseLevel = SILENCE_THRESHOLD_DB
        Timber.i("VAD calibration reset")
    }

    /**
     * Get current VAD status and statistics
     */
    fun getVADStatus(): VADStatus {
        return VADStatus(
            isActive = isVoiceActive,
            isCalibrating = isCalibrating,
            calibrationProgress = if (maxCalibrationSamples > 0) {
                (calibrationSamples.toFloat() / maxCalibrationSamples).coerceIn(0.0f, 1.0f)
            } else 0.0f,
            currentThreshold = adaptiveThreshold,
            averageVoiceConfidence = if (energyHistory.isNotEmpty()) {
                energyHistory.average().toFloat()
            } else 0.0f,
            samplesProcessed = calibrationSamples.toLong(),
            voiceDetectionRate = if (energyHistory.isNotEmpty()) {
                energyHistory.count { it > adaptiveThreshold }.toFloat() / energyHistory.size * 100.0f
            } else 0.0f
        )
    }
}

/**
 * Result of voice activity detection analysis
 */
data class VoiceDetectionResult(
    val hasVoice: Boolean,
    val confidence: Float,
    val metrics: VoiceMetrics
)

/**
 * Voice-related audio metrics
 */
data class VoiceMetrics(
    val energy: Float,
    val zeroCrossingRate: Float,
    val spectralCentroid: Float,
    val spectralRolloff: Float,
    val backgroundNoise: Float
) {
    companion object {
        fun empty() = VoiceMetrics(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
    }
}
