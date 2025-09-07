package com.checkmate.app.debug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.checkmate.app.data.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive debugging system to track pipeline execution and identify bottlenecks.
 */
class PipelineDebugger private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: PipelineDebugger? = null
        
        fun getInstance(): PipelineDebugger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PipelineDebugger().also { INSTANCE = it }
            }
        }
        
        // Debug levels
        const val DEBUG_LEVEL_VERBOSE = 0
        const val DEBUG_LEVEL_INFO = 1
        const val DEBUG_LEVEL_WARNING = 2
        const val DEBUG_LEVEL_ERROR = 3
        
        // Pipeline stages
        const val STAGE_INIT = "INIT"
        const val STAGE_PERMISSIONS = "PERMISSIONS"
        const val STAGE_SESSION_START = "SESSION_START"
        const val STAGE_MEDIA_PROJECTION = "MEDIA_PROJECTION"
        const val STAGE_AUDIO_CAPTURE = "AUDIO_CAPTURE"
        const val STAGE_SCREEN_CAPTURE = "SCREEN_CAPTURE"
        const val STAGE_OCR_PROCESSING = "OCR_PROCESSING"
        const val STAGE_IMAGE_CLASSIFICATION = "IMAGE_CLASSIFICATION"
        const val STAGE_ACCESSIBILITY_TREE = "ACCESSIBILITY_TREE"
        const val STAGE_PIPELINE_ASSEMBLY = "PIPELINE_ASSEMBLY"
        const val STAGE_BACKEND_SUBMISSION = "BACKEND_SUBMISSION"
        const val STAGE_RESPONSE_PROCESSING = "RESPONSE_PROCESSING"
        const val STAGE_CLEANUP = "CLEANUP"
    }
    
    private val debugEvents = MutableSharedFlow<DebugEvent>(replay = 100)
    private val stageTimings = ConcurrentHashMap<String, Long>()
    private val errorCounts = ConcurrentHashMap<String, AtomicLong>()
    private val performanceMetrics = ConcurrentHashMap<String, PerformanceMetric>()
    
    val events: SharedFlow<DebugEvent> = debugEvents.asSharedFlow()
    
    data class DebugEvent(
        val timestamp: Long,
        val level: Int,
        val stage: String,
        val message: String,
        val details: Map<String, Any> = emptyMap(),
        val error: Throwable? = null
    )
    
    data class PerformanceMetric(
        val stageName: String,
        val totalExecutions: Long,
        val totalTimeMs: Long,
        val averageTimeMs: Double,
        val minTimeMs: Long,
        val maxTimeMs: Long,
        val errorCount: Long
    )
    
    fun logStageStart(stage: String, details: Map<String, Any> = emptyMap()) {
        val timestamp = SystemClock.elapsedRealtime()
        stageTimings[stage] = timestamp
        
        logEvent(
            level = DEBUG_LEVEL_INFO,
            stage = stage,
            message = "STAGE_START: $stage",
            details = details
        )
        
        // Extra logging for critical stages
        when (stage) {
            STAGE_MEDIA_PROJECTION -> {
                Log.i("CheckmatePipeline", "🎬 MEDIA_PROJECTION: Starting screen capture setup")
                Timber.tag("PipelineDebug").i("Media projection initialization started")
            }
            STAGE_AUDIO_CAPTURE -> {
                Log.i("CheckmatePipeline", "🎤 AUDIO_CAPTURE: Starting audio capture setup")
                Timber.tag("PipelineDebug").i("Audio capture initialization started")
            }
            STAGE_SCREEN_CAPTURE -> {
                Log.i("CheckmatePipeline", "📱 SCREEN_CAPTURE: Capturing screen content")
                Timber.tag("PipelineDebug").i("Screen capture execution started")
            }
            STAGE_BACKEND_SUBMISSION -> {
                Log.i("CheckmatePipeline", "🌐 BACKEND_SUBMISSION: Sending data to backend")
                Timber.tag("PipelineDebug").i("Backend submission started")
            }
        }
    }
    
    fun logStageEnd(stage: String, success: Boolean = true, details: Map<String, Any> = emptyMap()) {
        val endTime = SystemClock.elapsedRealtime()
        val startTime = stageTimings[stage]
        val duration = if (startTime != null) endTime - startTime else -1L
        
        // Update performance metrics
        updatePerformanceMetrics(stage, duration, success)
        
        val allDetails = mutableMapOf<String, Any>().apply {
            putAll(details)
            put("duration_ms", duration)
            put("success", success)
        }
        
        logEvent(
            level = if (success) DEBUG_LEVEL_INFO else DEBUG_LEVEL_ERROR,
            stage = stage,
            message = "STAGE_END: $stage (${duration}ms) - ${if (success) "SUCCESS" else "FAILED"}",
            details = allDetails
        )
        
        // Extra logging for critical stages
        when (stage) {
            STAGE_MEDIA_PROJECTION -> {
                val status = if (success) "✅ SUCCESS" else "❌ FAILED"
                Log.i("CheckmatePipeline", "🎬 MEDIA_PROJECTION: $status (${duration}ms)")
                if (!success) {
                    Log.e("CheckmatePipeline", "❌ CRITICAL: Media projection failed - app may become unresponsive")
                }
            }
            STAGE_AUDIO_CAPTURE -> {
                val status = if (success) "✅ SUCCESS" else "❌ FAILED"
                Log.i("CheckmatePipeline", "🎤 AUDIO_CAPTURE: $status (${duration}ms)")
                if (!success) {
                    Log.w("CheckmatePipeline", "⚠️ Audio capture failed - continuing without audio")
                }
            }
            STAGE_SCREEN_CAPTURE -> {
                val status = if (success) "✅ SUCCESS" else "❌ FAILED"
                Log.i("CheckmatePipeline", "📱 SCREEN_CAPTURE: $status (${duration}ms)")
            }
            STAGE_BACKEND_SUBMISSION -> {
                val status = if (success) "✅ SUCCESS" else "❌ FAILED"
                Log.i("CheckmatePipeline", "🌐 BACKEND_SUBMISSION: $status (${duration}ms)")
                if (!success) {
                    Log.e("CheckmatePipeline", "❌ CRITICAL: Backend submission failed")
                }
            }
        }
        
        stageTimings.remove(stage)
    }
    
    fun logError(stage: String, error: Throwable, message: String = "", details: Map<String, Any> = emptyMap()) {
        errorCounts.computeIfAbsent(stage) { AtomicLong(0) }.incrementAndGet()
        
        val allDetails = mutableMapOf<String, Any>().apply {
            putAll(details)
            put("error_type", error.javaClass.simpleName)
            put("error_message", error.message ?: "Unknown error")
        }
        
        logEvent(
            level = DEBUG_LEVEL_ERROR,
            stage = stage,
            message = "ERROR: $stage - $message",
            details = allDetails,
            error = error
        )
        
        // Critical error logging
        Log.e("CheckmatePipeline", "❌ ERROR in $stage: $message", error)
        Timber.tag("PipelineDebug").e(error, "Pipeline error in stage: $stage")
        
        // Special handling for critical errors
        when (stage) {
            STAGE_MEDIA_PROJECTION -> {
                Log.e("CheckmatePipeline", "🔥 CRITICAL: Media projection error - this can cause app freeze!")
                Log.e("CheckmatePipeline", "Details: ${error.message}")
            }
            STAGE_AUDIO_CAPTURE -> {
                Log.e("CheckmatePipeline", "🔊 AUDIO ERROR: ${error.message}")
                Log.e("CheckmatePipeline", "This matches the pcmWrite/pcmRead failures in logcat")
            }
            STAGE_BACKEND_SUBMISSION -> {
                Log.e("CheckmatePipeline", "🌐 BACKEND ERROR: ${error.message}")
                Log.e("CheckmatePipeline", "This matches the ApiService errors in logcat")
            }
        }
    }
    
    fun logWarning(stage: String, message: String, details: Map<String, Any> = emptyMap()) {
        logEvent(
            level = DEBUG_LEVEL_WARNING,
            stage = stage,
            message = "WARNING: $stage - $message",
            details = details
        )
        
        Log.w("CheckmatePipeline", "⚠️ WARNING in $stage: $message")
        Timber.tag("PipelineDebug").w("Pipeline warning in stage: $stage - $message")
    }
    
    fun logInfo(stage: String, message: String, details: Map<String, Any> = emptyMap()) {
        logEvent(
            level = DEBUG_LEVEL_INFO,
            stage = stage,
            message = "INFO: $stage - $message",
            details = details
        )
        
        Log.i("CheckmatePipeline", "ℹ️ $stage: $message")
        Timber.tag("PipelineDebug").i("$stage: $message")
    }
    
    fun logVerbose(stage: String, message: String, details: Map<String, Any> = emptyMap()) {
        logEvent(
            level = DEBUG_LEVEL_VERBOSE,
            stage = stage,
            message = "VERBOSE: $stage - $message",
            details = details
        )
        
        Log.v("CheckmatePipeline", "🔍 $stage: $message")
        Timber.tag("PipelineDebug").v("$stage: $message")
    }
    
    private fun logEvent(
        level: Int,
        stage: String,
        message: String,
        details: Map<String, Any> = emptyMap(),
        error: Throwable? = null
    ) {
        val event = DebugEvent(
            timestamp = System.currentTimeMillis(),
            level = level,
            stage = stage,
            message = message,
            details = details,
            error = error
        )
        
        debugEvents.tryEmit(event)
    }
    
    private fun updatePerformanceMetrics(stage: String, duration: Long, success: Boolean) {
        val metric = performanceMetrics.computeIfAbsent(stage) {
            PerformanceMetric(
                stageName = stage,
                totalExecutions = 0,
                totalTimeMs = 0,
                averageTimeMs = 0.0,
                minTimeMs = Long.MAX_VALUE,
                maxTimeMs = 0,
                errorCount = 0
            )
        }
        
        val newTotalExecutions = metric.totalExecutions + 1
        val newTotalTime = metric.totalTimeMs + duration
        val newAverage = newTotalTime.toDouble() / newTotalExecutions
        val newMin = if (duration < metric.minTimeMs) duration else metric.minTimeMs
        val newMax = if (duration > metric.maxTimeMs) duration else metric.maxTimeMs
        val newErrorCount = if (success) metric.errorCount else metric.errorCount + 1
        
        performanceMetrics[stage] = PerformanceMetric(
            stageName = stage,
            totalExecutions = newTotalExecutions,
            totalTimeMs = newTotalTime,
            averageTimeMs = newAverage,
            minTimeMs = newMin,
            maxTimeMs = newMax,
            errorCount = newErrorCount
        )
    }
    
    fun getPerformanceReport(): String {
        val report = StringBuilder()
        report.appendLine("=== PIPELINE PERFORMANCE REPORT ===")
        report.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        report.appendLine()
        
        performanceMetrics.values.sortedBy { it.stageName }.forEach { metric ->
            report.appendLine("Stage: ${metric.stageName}")
            report.appendLine("  Executions: ${metric.totalExecutions}")
            report.appendLine("  Average Time: ${String.format("%.2f", metric.averageTimeMs)}ms")
            report.appendLine("  Min/Max Time: ${metric.minTimeMs}ms / ${metric.maxTimeMs}ms")
            report.appendLine("  Error Rate: ${String.format("%.2f", metric.errorCount.toDouble() / metric.totalExecutions * 100)}%")
            report.appendLine()
        }
        
        return report.toString()
    }
    
    fun logPipelineStart(sessionId: String) {
        Log.i("CheckmatePipeline", "🚀 ===== PIPELINE EXECUTION START =====")
        Log.i("CheckmatePipeline", "Session ID: $sessionId")
        Log.i("CheckmatePipeline", "Timestamp: ${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
        Log.i("CheckmatePipeline", "===========================================")
        
        logInfo("PIPELINE", "Pipeline execution started", mapOf("session_id" to sessionId))
    }
    
    fun logPipelineEnd(sessionId: String, success: Boolean, totalDuration: Long) {
        val status = if (success) "✅ SUCCESS" else "❌ FAILED"
        Log.i("CheckmatePipeline", "🏁 ===== PIPELINE EXECUTION END =====")
        Log.i("CheckmatePipeline", "Session ID: $sessionId")
        Log.i("CheckmatePipeline", "Status: $status")
        Log.i("CheckmatePipeline", "Total Duration: ${totalDuration}ms")
        Log.i("CheckmatePipeline", "Timestamp: ${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
        Log.i("CheckmatePipeline", "=========================================")
        
        logInfo("PIPELINE", "Pipeline execution ended", mapOf(
            "session_id" to sessionId,
            "success" to success,
            "total_duration_ms" to totalDuration
        ))
        
        // Log performance report
        Log.i("CheckmatePipeline", getPerformanceReport())
    }
    
    fun getCurrentActiveStages(): List<String> {
        return stageTimings.keys.toList()
    }
    
    fun getErrorCounts(): Map<String, Long> {
        return errorCounts.mapValues { it.value.get() }
    }
}
