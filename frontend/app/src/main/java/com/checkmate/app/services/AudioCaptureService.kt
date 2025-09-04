package com.checkmate.app.services

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.checkmate.app.data.AppConfig
import com.checkmate.app.utils.SessionManager
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for continuous audio capture and speech-to-text processing.
 * Captures audio in chunks and provides delta transcriptions.
 */
class AudioCaptureService : Service() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var audioRecord: AudioRecord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var sessionManager: SessionManager? = null
    
    private val isRecording = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    
    private var lastTranscription = ""
    private var currentTranscription = ""
    
    private var captureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        
        sessionManager = SessionManager.getInstance(this)
        initializeSpeechRecognizer()
        
        Timber.i("AudioCaptureService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                startAudioCapture()
            }
            
            ACTION_STOP_CAPTURE -> {
                stopAudioCapture()
                stopSelf()
            }
            
            else -> {
                Timber.w("Unknown action: ${intent?.action}")
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }

    private fun initializeSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Timber.e("Speech recognition not available")
                return
            }
            
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    Timber.d("Speech recognizer ready")
                }
                
                override fun onBeginningOfSpeech() {
                    Timber.d("Speech detection started")
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // Audio level monitoring (optional)
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {
                    // Raw audio buffer (optional processing)
                }
                
                override fun onEndOfSpeech() {
                    Timber.d("Speech detection ended")
                }
                
                override fun onError(error: Int) {
                    Timber.w("Speech recognition error: $error")
                    
                    // Restart recognition on certain errors
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || 
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        restartSpeechRecognition()
                    }
                }
                
                override fun onResults(results: android.os.Bundle?) {
                    handleSpeechResults(results)
                    restartSpeechRecognition()
                }
                
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    handlePartialResults(partialResults)
                }
                
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                    // Additional events
                }
            })
            
        } catch (e: Exception) {
            Timber.e(e, "Error initializing speech recognizer")
        }
    }

    private fun startAudioCapture() {
        if (isRecording.get()) {
            Timber.d("Audio capture already running")
            return
        }
        
        try {
            // Check permission
            if (!hasAudioPermission()) {
                Timber.e("Audio recording permission not granted")
                return
            }
            
            isRecording.set(true)
            
            // Start continuous speech recognition
            startSpeechRecognition()
            
            Timber.i("Audio capture started")
            
        } catch (e: Exception) {
            Timber.e(e, "Error starting audio capture")
            isRecording.set(false)
        }
    }

    private fun stopAudioCapture() {
        try {
            isRecording.set(false)
            
            captureJob?.cancel()
            captureJob = null
            
            speechRecognizer?.stopListening()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            Timber.i("Audio capture stopped")
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping audio capture")
        }
    }

    private fun startSpeechRecognition() {
        if (!isRecording.get() || isProcessing.get()) return
        
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            }
            
            speechRecognizer?.startListening(intent)
            isProcessing.set(true)
            
        } catch (e: Exception) {
            Timber.e(e, "Error starting speech recognition")
            isProcessing.set(false)
        }
    }

    private fun restartSpeechRecognition() {
        serviceScope.launch {
            delay(500) // Brief pause before restarting
            
            if (isRecording.get()) {
                isProcessing.set(false)
                startSpeechRecognition()
            }
        }
    }

    private fun handleSpeechResults(results: android.os.Bundle?) {
        try {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val newText = matches[0]
                currentTranscription = newText
                
                // Calculate delta (new text since last capture)
                val deltaText = calculateDeltaTranscription(newText)
                
                if (deltaText.isNotBlank()) {
                    Timber.d("Audio delta: $deltaText")
                    
                    // Store for next comparison
                    lastTranscription = newText
                    
                    // Send delta to capture pipeline if there's an active session
                    serviceScope.launch {
                        notifyAudioDelta(deltaText)
                    }
                }
            }
            
            isProcessing.set(false)
            
        } catch (e: Exception) {
            Timber.e(e, "Error handling speech results")
            isProcessing.set(false)
        }
    }

    private fun handlePartialResults(partialResults: android.os.Bundle?) {
        try {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                currentTranscription = matches[0]
                Timber.d("Partial audio: ${currentTranscription}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling partial results")
        }
    }

    private fun calculateDeltaTranscription(newText: String): String {
        return if (lastTranscription.isBlank()) {
            newText.trim()
        } else {
            // Simple delta calculation - find new words
            val lastWords = lastTranscription.split(" ").toSet()
            val newWords = newText.split(" ")
            
            val deltaWords = newWords.filter { word ->
                word.trim().isNotBlank() && !lastWords.contains(word)
            }
            
            deltaWords.joinToString(" ").trim()
        }
    }

    private suspend fun notifyAudioDelta(deltaText: String) {
        // This would be called by CapturePipeline to get the latest audio delta
        // For now, we'll store it in a simple way
        // In a real implementation, this would integrate with the capture pipeline
        
        try {
            val sessionState = sessionManager?.getCurrentSessionState()
            if (sessionState?.isActive == true) {
                // Audio delta is available for the next frame capture
                setLatestAudioDelta(deltaText)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error notifying audio delta")
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        serviceScope.cancel()
        
        stopAudioCapture()
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        super.onDestroy()
        Timber.i("AudioCaptureService destroyed")
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.checkmate.app.START_AUDIO_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.checkmate.app.STOP_AUDIO_CAPTURE"
        
        @Volatile
        private var latestAudioDelta = ""
        
        fun getLatestAudioDelta(): String {
            val delta = latestAudioDelta
            latestAudioDelta = "" // Clear after reading
            return delta
        }
        
        private fun setLatestAudioDelta(delta: String) {
            latestAudioDelta = delta
        }
        
        var instance: AudioCaptureService? = null
            private set
    }

    init {
        instance = this
    }
}
