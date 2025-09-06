package com.checkmate.app.utils

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.checkmate.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import kotlin.math.*

/**
 * Adaptive Capture Manager for intelligent interval adjustment based on device status.
 * Implements Step 12: Performance & Battery Optimization - Adaptive capture intervals.
 */
class AdaptiveCaptureManager private constructor(private val context: Context) {
    
    private val deviceStatusTracker = DeviceStatusTracker(context)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Adaptive configuration
    private val baseCaptureInterval = 2500L // 2.5 seconds default
    private val powerSaveCaptureInterval = 4000L // 4 seconds on power save
    private val lowBatteryCaptureInterval = 6000L // 6 seconds on low battery
    private val rapidChangeCaptureInterval = 1500L // 1.5 seconds for rapid changes
    private val thermalThrottleInterval = 8000L // 8 seconds on thermal throttling
    
    // UI activity detection
    private var lastUIChangeTime = System.currentTimeMillis()
    private var uiChangeCount = 0
    private var contentStabilityScore = 1.0f
    private var thermalState = ThermalState.NORMAL
    
    // Machine learning for pattern recognition
    private val activityPatterns = mutableMapOf<String, ActivityPattern>()
    private val captureHistory = mutableListOf<CaptureMetric>()
    
    enum class ThermalState {
        NORMAL, MODERATE, SEVERE, CRITICAL
    }
    
    data class ActivityPattern(
        val appPackage: String,
        val avgContentChangeRate: Float,
        val optimalCaptureInterval: Long,
        val batteryImpact: Float,
        val confidenceScore: Float
    )
    
    data class CaptureMetric(
        val timestamp: Long,
        val interval: Long,
        val batteryLevel: Float,
        val thermalState: ThermalState,
        val contentChanged: Boolean,
        val appPackage: String?,
        val processingTime: Long
    )
    
    data class CaptureRecommendation(
        val interval: Long,
        val reason: String,
        val confidenceScore: Float,
        val optimizations: List<String>
    )
    
    companion object {
        @Volatile
        private var INSTANCE: AdaptiveCaptureManager? = null
        
        fun getInstance(context: Context): AdaptiveCaptureManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdaptiveCaptureManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Calculate optimal capture interval using adaptive algorithms.
     */
    fun calculateOptimalInterval(
        currentApp: String? = null,
        contentChangeDetected: Boolean = false,
        processingTime: Long = 0
    ): CaptureRecommendation {
        
        val deviceStatus = deviceStatusTracker.getBatteryStatus()
        val deviceHints = deviceStatusTracker.getCurrentDeviceHints()
        
        // Update UI activity tracking
        updateUIActivityTracking(contentChangeDetected)
        
        // Update thermal state
        updateThermalState()
        
        // Base interval calculation
        var interval = baseCaptureInterval
        val optimizations = mutableListOf<String>()
        var reason = "Standard interval"
        
        // 1. Battery level optimization
        when {
            deviceStatus.level <= 10 -> {
                interval = (lowBatteryCaptureInterval * 1.5f).toLong()
                reason = "Critical battery conservation"
                optimizations.add("Extended interval for battery conservation")
            }
            deviceStatus.level <= 20 -> {
                interval = lowBatteryCaptureInterval
                reason = "Low battery optimization"
                optimizations.add("Reduced capture frequency")
            }
            deviceStatus.level <= 40 && !deviceStatus.isCharging -> {
                interval = (baseCaptureInterval * 1.3f).toLong()
                reason = "Battery preservation"
                optimizations.add("Moderate interval increase")
            }
        }
        
        // 2. Power save mode
        if (deviceStatus.isPowerSaving) {
            interval = max(interval, powerSaveCaptureInterval)
            reason = "Power save mode active"
            optimizations.add("Power save mode optimization")
        }
        
        // 3. Thermal throttling
        when (thermalState) {
            ThermalState.MODERATE -> {
                interval = (interval * 1.2f).toLong()
                optimizations.add("Thermal moderation")
            }
            ThermalState.SEVERE -> {
                interval = (interval * 1.5f).toLong()
                optimizations.add("Thermal throttling")
            }
            ThermalState.CRITICAL -> {
                interval = thermalThrottleInterval
                reason = "Critical thermal protection"
                optimizations.add("Emergency thermal throttling")
            }
            else -> {} // Normal thermal state
        }
        
        // 4. Content activity detection
        if (hasRapidUIChanges()) {
            // Only reduce interval if battery/thermal conditions allow
            if (deviceStatus.level > 30 && thermalState == ThermalState.NORMAL) {
                interval = min(interval, rapidChangeCaptureInterval)
                reason = "Rapid content changes detected"
                optimizations.add("Increased capture frequency for dynamic content")
            } else {
                optimizations.add("Rapid changes detected but throttled due to device constraints")
            }
        }
        
        // 5. Content stability optimization
        if (contentStabilityScore > 0.8f) {
            interval = (interval * 1.2f).toLong()
            optimizations.add("Content stability optimization")
        }
        
        // 6. App-specific optimization
        currentApp?.let { app ->
            activityPatterns[app]?.let { pattern ->
                if (pattern.confidenceScore > 0.7f) {
                    interval = (interval + pattern.optimalCaptureInterval) / 2
                    optimizations.add("App-specific optimization for $app")
                }
            }
        }
        
        // 7. Processing time adaptation
        if (processingTime > 0) {
            val processingOverhead = processingTime.toFloat() / interval
            if (processingOverhead > 0.3f) { // If processing takes > 30% of interval
                interval = (interval * 1.4f).toLong()
                optimizations.add("Processing time optimization")
            }
        }
        
        // 8. Time-based optimization (night mode, charging patterns)
        val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hourOfDay in 22..6) { // Night time
            interval = (interval * 1.1f).toLong()
            optimizations.add("Night time optimization")
        }
        
        // Ensure minimum and maximum bounds
        interval = interval.coerceIn(1000L, 30000L) // 1-30 seconds
        
        // Calculate confidence score
        val confidenceScore = calculateConfidenceScore(
            deviceStatus,
            thermalState,
            contentStabilityScore,
            currentApp
        )
        
        // Record this capture decision for learning
        recordCaptureMetric(interval, deviceStatus, contentChangeDetected, currentApp, processingTime)
        
        return CaptureRecommendation(
            interval = interval,
            reason = reason,
            confidenceScore = confidenceScore,
            optimizations = optimizations
        )
    }
    
    /**
     * Learn from capture patterns to improve future decisions.
     */
    fun updateActivityPattern(
        appPackage: String,
        contentChangeRate: Float,
        batteryImpact: Float,
        optimalInterval: Long
    ) {
        val existingPattern = activityPatterns[appPackage]
        
        if (existingPattern != null) {
            // Update existing pattern with weighted average
            val weight = 0.3f // Learning rate
            val newPattern = existingPattern.copy(
                avgContentChangeRate = existingPattern.avgContentChangeRate * (1 - weight) + contentChangeRate * weight,
                optimalCaptureInterval = ((existingPattern.optimalCaptureInterval * (1 - weight) + optimalInterval * weight).toLong()),
                batteryImpact = existingPattern.batteryImpact * (1 - weight) + batteryImpact * weight,
                confidenceScore = min(existingPattern.confidenceScore + 0.1f, 1.0f)
            )
            activityPatterns[appPackage] = newPattern
        } else {
            // Create new pattern
            activityPatterns[appPackage] = ActivityPattern(
                appPackage = appPackage,
                avgContentChangeRate = contentChangeRate,
                optimalCaptureInterval = optimalInterval,
                batteryImpact = batteryImpact,
                confidenceScore = 0.5f
            )
        }
        
        // Cleanup old patterns (keep only recent 50 apps)
        if (activityPatterns.size > 50) {
            val sortedPatterns = activityPatterns.toList().sortedByDescending { it.second.confidenceScore }
            activityPatterns.clear()
            sortedPatterns.take(40).forEach { (key, value) ->
                activityPatterns[key] = value
            }
        }
    }
    
    /**
     * Get battery optimization suggestions.
     */
    fun getBatteryOptimizationSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        val deviceStatus = deviceStatusTracker.getBatteryStatus()
        
        when {
            deviceStatus.level <= 15 -> {
                suggestions.add("Battery critically low - consider ending session")
                suggestions.add("Enable power save mode")
                suggestions.add("Reduce capture frequency significantly")
            }
            deviceStatus.level <= 25 -> {
                suggestions.add("Low battery - capture frequency reduced")
                suggestions.add("Non-essential features disabled")
            }
            deviceStatus.level <= 40 && !deviceStatus.isCharging -> {
                suggestions.add("Moderate battery optimization active")
            }
        }
        
        if (thermalState != ThermalState.NORMAL) {
            suggestions.add("Device temperature elevated - throttling active")
        }
        
        if (hasRapidUIChanges() && deviceStatus.level < 30) {
            suggestions.add("High activity detected but throttled due to battery")
        }
        
        return suggestions
    }
    
    /**
     * Get performance metrics for monitoring.
     */
    fun getPerformanceMetrics(): PerformanceMetrics {
        val recentMetrics = captureHistory.takeLast(100)
        
        val avgInterval = if (recentMetrics.isNotEmpty()) {
            recentMetrics.map { it.interval }.average()
        } else baseCaptureInterval.toDouble()
        
        val avgProcessingTime = if (recentMetrics.isNotEmpty()) {
            recentMetrics.map { it.processingTime }.average()
        } else 0.0
        
        val batteryEfficiency = calculateBatteryEfficiency()
        
        return PerformanceMetrics(
            avgCaptureInterval = avgInterval.toLong(),
            avgProcessingTime = avgProcessingTime.toLong(),
            batteryEfficiency = batteryEfficiency,
            thermalState = thermalState,
            contentStabilityScore = contentStabilityScore,
            learnedPatterns = activityPatterns.size
        )
    }
    
    data class PerformanceMetrics(
        val avgCaptureInterval: Long,
        val avgProcessingTime: Long,
        val batteryEfficiency: Float,
        val thermalState: ThermalState,
        val contentStabilityScore: Float,
        val learnedPatterns: Int
    )
    
    private fun updateUIActivityTracking(contentChanged: Boolean) {
        val currentTime = System.currentTimeMillis()
        
        if (contentChanged) {
            val timeSinceLastChange = currentTime - lastUIChangeTime
            
            if (timeSinceLastChange < 5000) { // 5 seconds
                uiChangeCount++
            } else {
                uiChangeCount = max(0, uiChangeCount - 1)
            }
            
            lastUIChangeTime = currentTime
            
            // Update content stability score
            val changeFrequency = uiChangeCount.toFloat() / 10f // Normalize to 0-1
            contentStabilityScore = max(0f, 1f - changeFrequency)
        } else {
            // Decay UI change count over time
            val timeSinceLastChange = currentTime - lastUIChangeTime
            if (timeSinceLastChange > 10000) { // 10 seconds
                uiChangeCount = max(0, uiChangeCount - 1)
                contentStabilityScore = min(1f, contentStabilityScore + 0.1f)
            }
        }
    }
    
    private fun hasRapidUIChanges(): Boolean {
        return uiChangeCount > 3
    }
    
    private fun updateThermalState() {
        // Simulate thermal state detection (Android 11+ has ThermalStatusManager)
        // For now, use CPU usage and battery level as proxies since direct temperature is not available
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val estimatedTemp = 25.0f // Default temperature - would need BatteryReceiver for actual temperature
        
        thermalState = when {
            estimatedTemp > 45 -> ThermalState.CRITICAL
            estimatedTemp > 40 -> ThermalState.SEVERE
            estimatedTemp > 35 -> ThermalState.MODERATE
            else -> ThermalState.NORMAL
        }
    }
    
    private fun calculateConfidenceScore(
        deviceStatus: BatteryStatus,
        thermalState: ThermalState,
        contentStabilityScore: Float,
        currentApp: String?
    ): Float {
        var confidence = 0.7f // Base confidence
        
        // Battery level confidence
        when (deviceStatus.level) {
            in 80..100 -> confidence += 0.2f
            in 50..79 -> confidence += 0.1f
            in 20..49 -> confidence -= 0.1f
            else -> confidence -= 0.3f
        }
        
        // Thermal confidence
        when (thermalState) {
            ThermalState.NORMAL -> confidence += 0.1f
            ThermalState.MODERATE -> confidence -= 0.1f
            ThermalState.SEVERE -> confidence -= 0.2f
            ThermalState.CRITICAL -> confidence -= 0.4f
        }
        
        // Content stability confidence
        confidence += contentStabilityScore * 0.2f
        
        // App pattern confidence
        currentApp?.let { app ->
            activityPatterns[app]?.let { pattern ->
                confidence += pattern.confidenceScore * 0.1f
            }
        }
        
        return confidence.coerceIn(0f, 1f)
    }
    
    private fun recordCaptureMetric(
        interval: Long,
        deviceStatus: BatteryStatus,
        contentChanged: Boolean,
        appPackage: String?,
        processingTime: Long
    ) {
        val metric = CaptureMetric(
            timestamp = System.currentTimeMillis(),
            interval = interval,
            batteryLevel = deviceStatus.level / 100f,
            thermalState = thermalState,
            contentChanged = contentChanged,
            appPackage = appPackage,
            processingTime = processingTime
        )
        
        captureHistory.add(metric)
        
        // Keep only recent 1000 metrics
        if (captureHistory.size > 1000) {
            captureHistory.removeFirst()
        }
    }
    
    private fun calculateBatteryEfficiency(): Float {
        val recentMetrics = captureHistory.takeLast(50)
        if (recentMetrics.size < 10) return 1.0f
        
        val batterLevels = recentMetrics.map { it.batteryLevel }
        val batteryDrop = batterLevels.first() - batterLevels.last()
        val timeSpan = recentMetrics.last().timestamp - recentMetrics.first().timestamp
        
        // Calculate efficiency as inverse of battery drain rate
        val drainRate = if (timeSpan > 0) batteryDrop / (timeSpan / 3600000f) else 0f // % per hour
        
        return (1f - drainRate.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
    
    fun cleanup() {
        captureScope.cancel()
    }
}
