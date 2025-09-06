package com.checkmate.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.checkmate.app.data.AppConfig
import kotlinx.coroutines.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System audio capture component for capturing device audio output.
 * Captures audio from apps, media playback, and system sounds using MediaProjection API.
 * 
 * Features:
 * - System audio capture via AudioPlaybackCapture (Android 10+)
 * - Fallback to microphone capture for older devices
 * - Integration with existing AudioCaptureService architecture
 * - VAD-aware processing for efficient capture
 * - Real-time audio streaming capabilities
 */
class SystemAudioCapture(
    private val context: Context,
    private val vadProcessor: VADProcessor
) {

    companion object {
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val BUFFER_SIZE_MULTIPLIER = 4
        
        // Supported audio sources for system capture
        private val SYSTEM_AUDIO_USAGES = intArrayOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN
        )
    }

    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private val isCapturing = AtomicBoolean(false)
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var audioCaptureCallback: ((AudioData) -> Unit)? = null
    private var captureJob: Job? = null
    
    // Audio buffer management
    private val audioBufferSize = AudioRecord.getMinBufferSize(
        AppConfig.AUDIO_SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_ENCODING
    ) * BUFFER_SIZE_MULTIPLIER

    /**
     * Initialize system audio capture with MediaProjection (Android 10+)
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun initializeSystemCapture(mediaProjection: MediaProjection): Boolean {
        return try {
            this.mediaProjection = mediaProjection
            setupSystemAudioRecord()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize system audio capture")
            false
        }
    }

    /**
     * Initialize fallback microphone capture for older devices
     */
    fun initializeMicrophoneCapture(): Boolean {
        return try {
            setupMicrophoneAudioRecord()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize microphone capture")
            false
        }
    }

    /**
     * Setup AudioRecord for system audio capture (Android 10+)
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun setupSystemAudioRecord() {
        if (mediaProjection == null) {
            throw IllegalStateException("MediaProjection not available for system audio capture")
        }

        // Create audio playback capture configuration
        val audioPlaybackCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        // Create audio format
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_ENCODING)
            .setSampleRate(AppConfig.AUDIO_SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        // Create AudioRecord with playback capture
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(audioBufferSize)
            .setAudioPlaybackCaptureConfig(audioPlaybackCaptureConfig)
            .build()

        Timber.i("System audio capture initialized successfully")
    }

    /**
     * Setup AudioRecord for microphone capture (fallback)
     */
    @SuppressLint("MissingPermission")
    private fun setupMicrophoneAudioRecord() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AppConfig.AUDIO_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_ENCODING,
            audioBufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }

        Timber.i("Microphone audio capture initialized successfully")
    }

    /**
     * Start audio capture with callback for processed audio data
     */
    fun startCapture(callback: (AudioData) -> Unit): Boolean {
        return try {
            if (isCapturing.get()) {
                Timber.w("Audio capture already running")
                return true
            }

            val audioRecord = this.audioRecord 
                ?: throw IllegalStateException("AudioRecord not initialized")

            audioCaptureCallback = callback
            isCapturing.set(true)

            // Start recording
            audioRecord.startRecording()
            
            // Start capture processing loop
            captureJob = processingScope.launch {
                runCaptureLoop(audioRecord)
            }

            Timber.i("Audio capture started successfully")
            true

        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio capture")
            isCapturing.set(false)
            false
        }
    }

    /**
     * Stop audio capture
     */
    fun stopCapture() {
        try {
            isCapturing.set(false)
            
            captureJob?.cancel()
            captureJob = null
            
            audioRecord?.stop()
            audioCaptureCallback = null
            
            Timber.i("Audio capture stopped")
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio capture")
        }
    }

    /**
     * Main capture processing loop
     */
    private suspend fun runCaptureLoop(audioRecord: AudioRecord) = withContext(Dispatchers.IO) {
        val audioBuffer = ByteArray(audioBufferSize)
        var totalSamplesProcessed = 0L
        
        Timber.d("Starting audio capture loop")
        
        while (isCapturing.get() && !currentCoroutineContext().isActive.not()) {
            try {
                // Read audio data
                val bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                
                if (bytesRead > 0) {
                    // Create audio chunk for processing
                    val audioChunk = audioBuffer.copyOf(bytesRead)
                    val timestamp = System.currentTimeMillis()
                    
                    // Process with VAD
                    val vadResult = vadProcessor.hasVoice(audioChunk, AppConfig.AUDIO_SAMPLE_RATE)
                    
                    // Create audio data object
                    val audioData = AudioData(
                        rawData = audioChunk,
                        sampleRate = AppConfig.AUDIO_SAMPLE_RATE,
                        encoding = AUDIO_ENCODING,
                        timestamp = timestamp,
                        hasVoice = vadResult.hasVoice,
                        voiceConfidence = vadResult.confidence,
                        voiceMetrics = vadResult.metrics
                    )
                    
                    // Invoke callback with processed data
                    audioCaptureCallback?.invoke(audioData)
                    
                    totalSamplesProcessed += bytesRead / 2 // 16-bit samples
                    
                    // Log periodic statistics
                    if (totalSamplesProcessed % (AppConfig.AUDIO_SAMPLE_RATE * 10) == 0L) {
                        val secondsProcessed = totalSamplesProcessed / AppConfig.AUDIO_SAMPLE_RATE
                        Timber.d("Audio capture: ${secondsProcessed}s processed, VAD: ${vadResult.hasVoice}")
                    }
                    
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Timber.e("AudioRecord invalid operation error")
                    break
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Timber.e("AudioRecord bad value error")
                    break
                }
                
                // Small delay to prevent excessive CPU usage
                delay(10)
                
            } catch (e: Exception) {
                Timber.e(e, "Error in audio capture loop")
                if (e is CancellationException) break
                delay(100) // Brief pause before retry
            }
        }
        
        Timber.d("Audio capture loop ended")
    }

    /**
     * Get current capture status and statistics
     */
    fun getCaptureStatus(): AudioCaptureStatus {
        val audioRecord = this.audioRecord
        return AudioCaptureStatus(
            isCapturing = isCapturing.get(),
            captureSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) 
                AudioSourceType.SYSTEM_AUDIO else AudioSourceType.MICROPHONE,
            isSystemAudioAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            currentSampleRate = AppConfig.AUDIO_SAMPLE_RATE,
            bufferSize = audioBufferSize,
            averageLatency = 50.0f, // Example value - would be calculated in real implementation
            droppedFrames = 0, // Example value - would be tracked in real implementation
            vadStatus = vadProcessor.getVADStatus()
        )
    }

    /**
     * Check if system audio capture is supported on this device
     */
    fun isSystemAudioSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /**
     * Get recommended audio source based on device capabilities
     */
    fun getRecommendedAudioSource(): AudioSourceType {
        return if (isSystemAudioSupported() && mediaProjection != null) {
            AudioSourceType.SYSTEM_AUDIO
        } else {
            AudioSourceType.MICROPHONE
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopCapture()
        
        audioRecord?.release()
        audioRecord = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        processingScope.cancel()
        
        Timber.i("SystemAudioCapture cleaned up")
    }
}

/**
 * Audio data container with VAD analysis results
 */
data class AudioData(
    val rawData: ByteArray,
    val sampleRate: Int,
    val encoding: Int,
    val timestamp: Long,
    val hasVoice: Boolean,
    val voiceConfidence: Float,
    val voiceMetrics: VoiceMetrics
) {
    /**
     * Get audio duration in milliseconds
     */
    fun getDurationMs(): Long {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val samples = rawData.size / bytesPerSample
        return (samples * 1000L) / sampleRate
    }
    
    /**
     * Convert to base64 for transmission
     */
    fun toBase64(): String {
        return android.util.Base64.encodeToString(rawData, android.util.Base64.NO_WRAP)
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as AudioData
        
        if (!rawData.contentEquals(other.rawData)) return false
        if (sampleRate != other.sampleRate) return false
        if (encoding != other.encoding) return false
        if (timestamp != other.timestamp) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = rawData.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + encoding
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
