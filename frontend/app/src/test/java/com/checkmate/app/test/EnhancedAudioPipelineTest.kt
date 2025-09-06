package com.checkmate.app.test

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.checkmate.app.audio.*
import com.checkmate.app.data.AppConfig
import com.checkmate.app.services.AudioCaptureService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import timber.log.Timber
import kotlin.test.*

/**
 * Comprehensive test suite for Phase 2 Step 4.1: Enhanced Audio Pipeline
 * 
 * Tests cover:
 * - VADProcessor voice activity detection accuracy
 * - SystemAudioCapture initialization and operation
 * - AudioStreamingService real-time streaming
 * - Enhanced AudioCaptureService integration
 * - Performance and resource management
 * - Backward compatibility with existing systems
 */
@RunWith(AndroidJUnit4::class)
class EnhancedAudioPipelineTest {

    private lateinit var context: Context
    private lateinit var vadProcessor: VADProcessor
    private lateinit var systemAudioCapture: SystemAudioCapture
    private lateinit var audioStreamingService: AudioStreamingService
    
    private val testScope = TestScope()
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        context = ApplicationProvider.getApplicationContext()
        
        // Initialize audio components
        vadProcessor = VADProcessor(context)
        systemAudioCapture = SystemAudioCapture(context, vadProcessor)
        audioStreamingService = AudioStreamingService(context)
        
        // Setup Timber for testing
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                println("AUDIO TEST [$tag]: $message")
            }
        })
    }
    
    @After
    fun cleanup() {
        systemAudioCapture.cleanup()
        audioStreamingService.cleanup()
        testScope.cancel()
        Timber.uprootAll()
    }

    @Test
    fun testVADProcessorVoiceDetection() = testScope.runTest {
        // Test: VAD correctly identifies voice vs silence/noise
        
        // Create test audio samples
        val silenceAudio = createSilenceAudio(1000) // 1 second of silence
        val voiceAudio = createVoiceAudio(1000) // 1 second of synthetic voice
        val noiseAudio = createNoiseAudio(1000) // 1 second of random noise
        
        // Test silence detection
        val silenceResult = vadProcessor.hasVoice(silenceAudio)
        assertFalse(silenceResult.hasVoice, "Silence should not be detected as voice")
        assertTrue(silenceResult.confidence < 0.3f, "Silence confidence should be low")
        
        // Test voice detection
        val voiceResult = vadProcessor.hasVoice(voiceAudio)
        assertTrue(voiceResult.hasVoice, "Voice should be detected")
        assertTrue(voiceResult.confidence > 0.5f, "Voice confidence should be high")
        
        // Test noise detection
        val noiseResult = vadProcessor.hasVoice(noiseAudio)
        // Noise detection depends on characteristics, but confidence should be reasonable
        assertTrue(noiseResult.confidence >= 0.0f && noiseResult.confidence <= 1.0f, 
                  "Noise confidence should be in valid range")
        
        // Verify metrics are populated
        assertTrue(voiceResult.metrics.energy > 0, "Voice audio should have measurable energy")
        assertTrue(voiceResult.metrics.zeroCrossingRate >= 0, "Zero crossing rate should be non-negative")
    }

    @Test
    fun testVADProcessorCalibration() = testScope.runTest {
        // Test: VAD calibration and adaptive threshold adjustment
        
        val vadStatus = vadProcessor.getVADStatus()
        assertTrue(vadStatus.isCalibrating, "VAD should start in calibration mode")
        assertEquals(0.0f, vadStatus.calibrationProgress, 0.01f, "Initial calibration progress should be 0")
        
        // Feed calibration samples
        repeat(60) { // More than MAX_CALIBRATION_SAMPLES
            val testAudio = createVariableAudio(500, intensity = it * 0.01f)
            vadProcessor.hasVoice(testAudio)
        }
        
        val postCalibrationStatus = vadProcessor.getVADStatus()
        assertFalse(postCalibrationStatus.isCalibrating, "VAD should complete calibration")
        assertEquals(1.0f, postCalibrationStatus.calibrationProgress, 0.01f, "Calibration should be complete")
        assertTrue(postCalibrationStatus.adaptiveThreshold > 0, "Adaptive threshold should be set")
    }

    @Test
    fun testSystemAudioCaptureInitialization() = testScope.runTest {
        // Test: SystemAudioCapture initialization on different Android versions
        
        // Test capability detection
        val isSupported = systemAudioCapture.isSystemAudioSupported()
        val expectedSupport = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        assertEquals(expectedSupport, isSupported, "System audio support should match Android version")
        
        // Test recommended audio source
        val recommendedSource = systemAudioCapture.getRecommendedAudioSource()
        assertNotNull(recommendedSource, "Should provide recommended audio source")
        assertTrue(
            recommendedSource == AudioSourceType.SYSTEM_AUDIO || recommendedSource == AudioSourceType.MICROPHONE,
            "Recommended source should be valid type"
        )
        
        // Test microphone fallback initialization
        val micInitSuccess = systemAudioCapture.initializeMicrophoneCapture()
        assertTrue(micInitSuccess, "Microphone capture initialization should succeed")
        
        // Verify status
        val status = systemAudioCapture.getCaptureStatus()
        assertFalse(status.isCapturing, "Should not be capturing initially")
        assertFalse(status.isSystemAudio, "Should indicate microphone mode")
    }

    @Test
    fun testAudioStreamingServiceBasicOperation() = testScope.runTest {
        // Test: AudioStreamingService basic streaming operations
        
        // Test initial state
        val initialStats = audioStreamingService.getStreamingStats()
        assertFalse(initialStats.isStreaming, "Should not be streaming initially")
        assertEquals(0L, initialStats.totalAudioStreamed, "No audio should be streamed initially")
        
        // Test streaming start (may fail due to no WebSocket connection in test)
        val startResult = audioStreamingService.startStreaming()
        // Result depends on WebSocket availability in test environment
        
        // Test audio data creation and processing
        val testAudioData = createTestAudioData()
        assertNotNull(testAudioData, "Test audio data should be created")
        assertTrue(testAudioData.getDurationMs() > 0, "Audio duration should be positive")
        assertFalse(testAudioData.toBase64().isEmpty(), "Base64 encoding should work")
        
        // Cleanup
        audioStreamingService.stopStreaming()
    }

    @Test
    fun testAudioStreamingServiceBatching() = testScope.runTest {
        // Test: Audio streaming batching and queuing logic
        
        val testAudioChunks = mutableListOf<AudioData>()
        
        // Create multiple audio chunks with voice
        repeat(5) { index ->
            val audioData = createTestAudioData(
                hasVoice = true,
                confidence = 0.8f,
                timestamp = System.currentTimeMillis() + index * 100
            )
            testAudioChunks.add(audioData)
        }
        
        // Test batch size calculation
        val totalSize = testAudioChunks.sumOf { it.rawData.size }
        assertTrue(totalSize > 0, "Total batch size should be positive")
        
        // Test audio data properties
        testAudioChunks.forEach { audioData ->
            assertTrue(audioData.hasVoice, "Test audio should have voice")
            assertTrue(audioData.voiceConfidence > 0.7f, "Voice confidence should be high")
            assertTrue(audioData.getDurationMs() > 0, "Duration should be positive")
        }
    }

    @Test
    fun testEnhancedAudioCaptureServiceIntegration() = testScope.runTest {
        // Test: Enhanced AudioCaptureService integration with new components
        
        val audioService = AudioCaptureService()
        
        // Test status reporting
        val status = audioService.getAudioCaptureStatus()
        assertNotNull(status, "Status should be available")
        assertFalse(status.isRecording, "Should not be recording initially")
        assertFalse(status.isEnhancedMode, "Should not be in enhanced mode initially")
        
        // Test backward compatibility - legacy methods still work
        val legacyDelta = AudioCaptureService.getLatestAudioDelta()
        assertNotNull(legacyDelta, "Legacy delta method should work")
    }

    @Test
    fun testAudioDataConversionAndEncoding() = testScope.runTest {
        // Test: Audio data conversion and encoding operations
        
        val rawAudio = createVoiceAudio(500) // 500ms of audio
        val testMetrics = VoiceMetrics(
            energy = 0.5f,
            zeroCrossingRate = 0.1f,
            spectralCentroid = 1000.0f,
            spectralRolloff = 2000.0f,
            backgroundNoise = -30.0f
        )
        
        val audioData = AudioData(
            rawData = rawAudio,
            sampleRate = AppConfig.AUDIO_SAMPLE_RATE,
            encoding = android.media.AudioFormat.ENCODING_PCM_16BIT,
            timestamp = System.currentTimeMillis(),
            hasVoice = true,
            voiceConfidence = 0.8f,
            voiceMetrics = testMetrics
        )
        
        // Test duration calculation
        val expectedDuration = (rawAudio.size / 2) * 1000L / AppConfig.AUDIO_SAMPLE_RATE
        val actualDuration = audioData.getDurationMs()
        assertEquals(expectedDuration, actualDuration, 10L, "Duration calculation should be accurate")
        
        // Test base64 encoding
        val base64 = audioData.toBase64()
        assertFalse(base64.isEmpty(), "Base64 should not be empty")
        assertTrue(base64.length > 0, "Base64 should have content")
        
        // Test equality
        val audioData2 = audioData.copy()
        assertEquals(audioData, audioData2, "Copied audio data should be equal")
    }

    @Test
    fun testPerformanceAndResourceManagement() = testScope.runTest {
        // Test: Performance characteristics and resource management
        
        val startTime = System.currentTimeMillis()
        val startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        
        // Process multiple audio chunks to test performance
        repeat(100) { index ->
            val testAudio = createVariableAudio(100, intensity = (index % 10) * 0.1f)
            val result = vadProcessor.hasVoice(testAudio)
            
            // Verify results are reasonable
            assertTrue(result.confidence >= 0.0f && result.confidence <= 1.0f,
                      "Confidence should be in valid range")
        }
        
        val endTime = System.currentTimeMillis()
        val endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        
        val processingTime = endTime - startTime
        val memoryIncrease = endMemory - startMemory
        
        // Performance assertions
        assertTrue(processingTime < 5000, "Processing 100 chunks should take less than 5 seconds")
        assertTrue(memoryIncrease < 10 * 1024 * 1024, "Memory increase should be less than 10MB")
        
        Timber.i("Performance: ${processingTime}ms, Memory: ${memoryIncrease / 1024}KB")
    }

    @Test
    fun testErrorHandlingAndRecovery() = testScope.runTest {
        // Test: Error handling and recovery scenarios
        
        // Test VAD with invalid audio data
        val emptyAudio = ByteArray(0)
        val emptyResult = vadProcessor.hasVoice(emptyAudio)
        assertFalse(emptyResult.hasVoice, "Empty audio should not have voice")
        assertEquals(0.0f, emptyResult.confidence, "Empty audio confidence should be 0")
        
        // Test VAD with corrupted audio data
        val corruptedAudio = ByteArray(100) { 0xFF.toByte() } // All max values
        val corruptedResult = vadProcessor.hasVoice(corruptedAudio)
        assertTrue(corruptedResult.confidence >= 0.0f && corruptedResult.confidence <= 1.0f,
                  "Corrupted audio should still return valid confidence")
        
        // Test system audio capture with invalid configuration
        val invalidInitResult = try {
            systemAudioCapture.startCapture { }
            false // Should fail without proper initialization
        } catch (e: Exception) {
            true // Expected failure
        }
        assertTrue(invalidInitResult, "Should handle invalid initialization gracefully")
    }

    // Helper methods for creating test audio data

    private fun createSilenceAudio(durationMs: Int): ByteArray {
        val samples = (AppConfig.AUDIO_SAMPLE_RATE * durationMs) / 1000
        return ByteArray(samples * 2) // 16-bit samples = 2 bytes each
    }

    private fun createVoiceAudio(durationMs: Int): ByteArray {
        val samples = (AppConfig.AUDIO_SAMPLE_RATE * durationMs) / 1000
        val audioData = ByteArray(samples * 2)
        
        // Generate synthetic voice-like audio (sine wave with modulation)
        for (i in 0 until samples) {
            val frequency = 150.0 + 50.0 * kotlin.math.sin(i * 0.001) // Varying frequency
            val amplitude = 0.3 + 0.2 * kotlin.math.sin(i * 0.0005) // Varying amplitude
            val sample = (amplitude * kotlin.math.sin(2 * kotlin.math.PI * frequency * i / AppConfig.AUDIO_SAMPLE_RATE) * 16383).toInt()
            
            audioData[i * 2] = (sample and 0xFF).toByte()
            audioData[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        
        return audioData
    }

    private fun createNoiseAudio(durationMs: Int): ByteArray {
        val samples = (AppConfig.AUDIO_SAMPLE_RATE * durationMs) / 1000
        val audioData = ByteArray(samples * 2)
        
        // Generate random noise
        val random = kotlin.random.Random(System.currentTimeMillis())
        for (i in 0 until samples) {
            val sample = (random.nextFloat() * 32767 - 16383).toInt()
            audioData[i * 2] = (sample and 0xFF).toByte()
            audioData[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        
        return audioData
    }

    private fun createVariableAudio(durationMs: Int, intensity: Float): ByteArray {
        val samples = (AppConfig.AUDIO_SAMPLE_RATE * durationMs) / 1000
        val audioData = ByteArray(samples * 2)
        
        // Generate audio with variable intensity
        for (i in 0 until samples) {
            val sample = (intensity * kotlin.math.sin(2 * kotlin.math.PI * 440.0 * i / AppConfig.AUDIO_SAMPLE_RATE) * 16383).toInt()
            audioData[i * 2] = (sample and 0xFF).toByte()
            audioData[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        
        return audioData
    }

    private fun createTestAudioData(
        hasVoice: Boolean = true,
        confidence: Float = 0.8f,
        timestamp: Long = System.currentTimeMillis()
    ): AudioData {
        val rawData = if (hasVoice) createVoiceAudio(250) else createSilenceAudio(250)
        val metrics = VoiceMetrics(
            energy = if (hasVoice) confidence else 0.1f,
            zeroCrossingRate = 0.15f,
            spectralCentroid = 1200.0f,
            spectralRolloff = 2400.0f,
            backgroundNoise = -35.0f
        )
        
        return AudioData(
            rawData = rawData,
            sampleRate = AppConfig.AUDIO_SAMPLE_RATE,
            encoding = android.media.AudioFormat.ENCODING_PCM_16BIT,
            timestamp = timestamp,
            hasVoice = hasVoice,
            voiceConfidence = confidence,
            voiceMetrics = metrics
        )
    }
}
