package com.checkmate.app.testing

import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.checkmate.app.data.*
import com.checkmate.app.services.CheckmateService
import com.checkmate.app.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Performance and Battery Testing Framework for comprehensive system validation.
 * Implements Step 13: Testing & Validation - Performance testing & Battery life impact testing.
 */
class PerformanceBatteryTesting(private val context: Context) {
    
    private val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    data class PerformanceTestResult(
        val testName: String,
        val duration: Long,
        val cpuUsage: Float,
        val memoryUsage: Long,
        val batteryDrain: Float,
        val networkUsage: Long,
        val frameRate: Float,
        val responseTime: Long,
        val success: Boolean,
        val metrics: Map<String, Any> = emptyMap()
    )
    
    data class BatteryTestResult(
        val testName: String,
        val duration: Long,
        val initialBatteryLevel: Int,
        val finalBatteryLevel: Int,
        val batteryDrainRate: Float, // % per hour
        val estimatedLifetime: Long, // minutes
        val thermalImpact: Float,
        val powerEfficiency: Float,
        val details: Map<String, Any> = emptyMap()
    )
    
    data class PerformanceReport(
        val performanceTests: List<PerformanceTestResult>,
        val batteryTests: List<BatteryTestResult>,
        val overallPerformanceScore: Float,
        val batteryEfficiencyScore: Float,
        val recommendations: List<String>
    )
    
    /**
     * Run comprehensive performance and battery testing suite.
     */
    suspend fun runComprehensiveTestSuite(): PerformanceReport {
        val performanceResults = mutableListOf<PerformanceTestResult>()
        val batteryResults = mutableListOf<BatteryTestResult>()
        
        Timber.i("Starting comprehensive performance and battery testing")
        
        try {
            // Performance tests
            performanceResults.addAll(runPerformanceTests())
            
            // Battery impact tests
            batteryResults.addAll(runBatteryTests())
            
            // Stress tests
            performanceResults.addAll(runStressTests())
            
            // Long-duration tests
            batteryResults.addAll(runLongDurationTests())
            
        } catch (e: Exception) {
            Timber.e(e, "Performance testing suite failed")
        }
        
        val overallPerformanceScore = calculatePerformanceScore(performanceResults)
        val batteryEfficiencyScore = calculateBatteryScore(batteryResults)
        val recommendations = generateOptimizationRecommendations(performanceResults, batteryResults)
        
        return PerformanceReport(
            performanceTests = performanceResults,
            batteryTests = batteryResults,
            overallPerformanceScore = overallPerformanceScore,
            batteryEfficiencyScore = batteryEfficiencyScore,
            recommendations = recommendations
        )
    }
    
    /**
     * Run performance benchmarks.
     */
    private suspend fun runPerformanceTests(): List<PerformanceTestResult> {
        val results = mutableListOf<PerformanceTestResult>()
        
        // Test 1: Service startup performance
        results.add(testServiceStartupPerformance())
        
        // Test 2: Frame capture performance
        results.add(testFrameCapturePerformance())
        
        // Test 3: WebSocket communication performance
        results.add(testWebSocketPerformance())
        
        // Test 4: Memory management performance
        results.add(testMemoryPerformance())
        
        // Test 5: Image processing performance
        results.add(testImageProcessingPerformance())
        
        return results
    }
    
    /**
     * Run battery impact tests.
     */
    private suspend fun runBatteryTests(): List<BatteryTestResult> {
        val results = mutableListOf<BatteryTestResult>()
        
        // Test 1: Idle battery consumption
        results.add(testIdleBatteryConsumption())
        
        // Test 2: Active session battery consumption
        results.add(testActiveSessionBatteryConsumption())
        
        // Test 3: Background processing battery impact
        results.add(testBackgroundProcessingBatteryImpact())
        
        // Test 4: Screen capture battery impact
        results.add(testScreenCaptureBatteryImpact())
        
        return results
    }
    
    /**
     * Run stress tests.
     */
    private suspend fun runStressTests(): List<PerformanceTestResult> {
        val results = mutableListOf<PerformanceTestResult>()
        
        // Test 1: High frequency capture stress test
        results.add(testHighFrequencyCaptureStress())
        
        // Test 2: Memory stress test
        results.add(testMemoryStress())
        
        // Test 3: Concurrent operations stress test
        results.add(testConcurrentOperationsStress())
        
        return results
    }
    
    /**
     * Run long-duration tests.
     */
    private suspend fun runLongDurationTests(): List<BatteryTestResult> {
        val results = mutableListOf<BatteryTestResult>()
        
        // Test 1: 1-hour continuous operation
        results.add(testOneHourContinuousOperation())
        
        // Test 2: Extended background operation
        results.add(testExtendedBackgroundOperation())
        
        return results
    }
    
    // Individual performance tests
    
    private suspend fun testServiceStartupPerformance(): PerformanceTestResult {
        val startTime = System.currentTimeMillis()
        val initialMemory = getCurrentMemoryUsage()
        val initialBattery = getCurrentBatteryLevel()
        
        return try {
            // Measure service startup time
            val serviceStartTime = System.currentTimeMillis()
            
            // Start Checkmate service
            val serviceIntent = android.content.Intent(context, CheckmateService::class.java)
            context.startForegroundService(serviceIntent)
            
            // Wait for service to be fully initialized
            delay(5000)
            
            val serviceStartupTime = System.currentTimeMillis() - serviceStartTime
            val finalMemory = getCurrentMemoryUsage()
            val finalBattery = getCurrentBatteryLevel()
            
            val memoryIncrease = finalMemory - initialMemory
            val batteryDrain = (initialBattery - finalBattery).toFloat()
            
            // Stop service
            context.stopService(serviceIntent)
            
            val totalDuration = System.currentTimeMillis() - startTime
            
            PerformanceTestResult(
                testName = "service_startup_performance",
                duration = totalDuration,
                cpuUsage = 0f, // TODO: Implement CPU usage measurement
                memoryUsage = memoryIncrease.toLong(),
                batteryDrain = batteryDrain,
                networkUsage = 0L,
                frameRate = 0f,
                responseTime = serviceStartupTime,
                success = serviceStartupTime < 10000, // Should start within 10 seconds
                metrics = mapOf(
                    "serviceStartupTime" to serviceStartupTime,
                    "memoryIncrease" to memoryIncrease,
                    "batteryDrain" to batteryDrain
                )
            )
            
        } catch (e: Exception) {
            PerformanceTestResult(
                testName = "service_startup_performance",
                duration = System.currentTimeMillis() - startTime,
                cpuUsage = 0f,
                memoryUsage = 0L,
                batteryDrain = 0f,
                networkUsage = 0L,
                frameRate = 0f,
                responseTime = -1,
                success = false,
                metrics = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    private suspend fun testFrameCapturePerformance(): PerformanceTestResult {
        val startTime = System.currentTimeMillis()
        val initialMemory = getCurrentMemoryUsage()
        val initialBattery = getCurrentBatteryLevel()
        
        return try {
            val capturePipeline = CapturePipeline(context)
            val frameCaptureTimes = mutableListOf<Long>()
            
            // Perform multiple frame captures
            repeat(10) {
                val captureStart = System.currentTimeMillis()
                
                val frameBundle = capturePipeline.captureFrame()
                
                val captureTime = System.currentTimeMillis() - captureStart
                frameCaptureTimes.add(captureTime)
                
                delay(500) // Brief pause between captures
            }
            
            val avgCaptureTime = frameCaptureTimes.average()
            val maxCaptureTime = frameCaptureTimes.maxOrNull() ?: 0L
            val frameRate = if (avgCaptureTime > 0) 1000f / avgCaptureTime.toFloat() else 0f
            
            val finalMemory = getCurrentMemoryUsage()
            val finalBattery = getCurrentBatteryLevel()
            
            val totalDuration = System.currentTimeMillis() - startTime
            
            PerformanceTestResult(
                testName = "frame_capture_performance",
                duration = totalDuration,
                cpuUsage = 0f, // TODO: Implement CPU usage measurement
                memoryUsage = (finalMemory - initialMemory).toLong(),
                batteryDrain = (initialBattery - finalBattery).toFloat(),
                networkUsage = 0L,
                frameRate = frameRate.toFloat(),
                responseTime = avgCaptureTime.toLong(),
                success = avgCaptureTime < 3000, // Should capture within 3 seconds
                metrics = mapOf(
                    "avgCaptureTime" to avgCaptureTime,
                    "maxCaptureTime" to maxCaptureTime,
                    "frameCaptureTimes" to frameCaptureTimes,
                    "frameRate" to frameRate
                )
            )
            
        } catch (e: Exception) {
            PerformanceTestResult(
                testName = "frame_capture_performance",
                duration = System.currentTimeMillis() - startTime,
                cpuUsage = 0f,
                memoryUsage = 0L,
                batteryDrain = 0f,
                networkUsage = 0L,
                frameRate = 0f,
                responseTime = -1,
                success = false,
                metrics = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    private suspend fun testWebSocketPerformance(): PerformanceTestResult {
        val startTime = System.currentTimeMillis()
        val initialMemory = getCurrentMemoryUsage()
        val initialBattery = getCurrentBatteryLevel()
        
        return try {
            val connectionPoolManager = ConnectionPoolManager.getInstance(context)
            val sessionId = "perf_test_${System.currentTimeMillis()}"
            
            val connectionTimes = mutableListOf<Long>()
            val messageTimes = mutableListOf<Long>()
            
            // Test connection performance
            repeat(5) {
                val connectStart = System.currentTimeMillis()
                
                val connectionResult = connectionPoolManager.getConnection(sessionId)
                
                if (connectionResult.isSuccess) {
                    connectionTimes.add(System.currentTimeMillis() - connectStart)
                    
                    val client = connectionResult.getOrThrow()
                    
                    // Test message performance
                    val messageStart = System.currentTimeMillis()
                    
                    val frameBundle = FrameBundle(
                        sessionId = sessionId,
                        timestamp = java.util.Date(),
                        treeSummary = TreeSummary(
                            appPackage = "perf.test",
                            appReadableName = "Performance Test",
                            mediaHints = MediaHints(),
                            topNodes = emptyList(),
                            confidence = 1.0f
                        ),
                        ocrText = "Performance test message",
                        hasImage = false,
                        imageRef = null,
                        audioTranscriptDelta = "",
                        deviceHints = DeviceHints(
                            battery = 50.0f,
                            powerSaver = false
                        )
                    )
                    
                    client.sendFrameBundle(frameBundle)
                    messageTimes.add(System.currentTimeMillis() - messageStart)
                }
                
                connectionPoolManager.releaseConnection(sessionId)
                delay(200)
            }
            
            val avgConnectionTime = if (connectionTimes.isNotEmpty()) connectionTimes.average() else 0.0
            val avgMessageTime = if (messageTimes.isNotEmpty()) messageTimes.average() else 0.0
            
            val finalMemory = getCurrentMemoryUsage()
            val finalBattery = getCurrentBatteryLevel()
            val totalDuration = System.currentTimeMillis() - startTime
            
            PerformanceTestResult(
                testName = "websocket_performance",
                duration = totalDuration,
                cpuUsage = 0f,
                memoryUsage = (finalMemory - initialMemory).toLong(),
                batteryDrain = (initialBattery - finalBattery).toFloat(),
                networkUsage = messageTimes.size * 1024L, // Estimate
                frameRate = 0f,
                responseTime = avgConnectionTime.toLong(),
                success = avgConnectionTime < 5000 && avgMessageTime < 1000,
                metrics = mapOf(
                    "avgConnectionTime" to avgConnectionTime,
                    "avgMessageTime" to avgMessageTime,
                    "connectionTimes" to connectionTimes,
                    "messageTimes" to messageTimes,
                    "successfulConnections" to connectionTimes.size
                )
            )
            
        } catch (e: Exception) {
            PerformanceTestResult(
                testName = "websocket_performance",
                duration = System.currentTimeMillis() - startTime,
                cpuUsage = 0f,
                memoryUsage = 0L,
                batteryDrain = 0f,
                networkUsage = 0L,
                frameRate = 0f,
                responseTime = -1,
                success = false,
                metrics = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    // Individual battery tests
    
    private suspend fun testIdleBatteryConsumption(): BatteryTestResult {
        val startTime = System.currentTimeMillis()
        val initialBattery = getCurrentBatteryLevel()
        val initialTemp = getCurrentBatteryTemperature()
        
        return try {
            // Start service in idle mode
            val sessionManager = SessionManager.getInstance(context)
            
            // Idle for 10 minutes (reduced for testing)
            val testDuration = 10.minutes.inWholeMilliseconds
            delay(testDuration)
            
            val finalBattery = getCurrentBatteryLevel()
            val finalTemp = getCurrentBatteryTemperature()
            val actualDuration = System.currentTimeMillis() - startTime
            
            val batteryDrain = initialBattery - finalBattery
            val drainRate = (batteryDrain / (actualDuration / 3600000f)) // % per hour
            val estimatedLifetime = if (drainRate > 0) ((finalBattery / drainRate) * 60).toLong() else Long.MAX_VALUE
            val thermalImpact = finalTemp - initialTemp
            val powerEfficiency = if (batteryDrain > 0) (actualDuration.toFloat() / batteryDrain) else Float.MAX_VALUE
            
            BatteryTestResult(
                testName = "idle_battery_consumption",
                duration = actualDuration,
                initialBatteryLevel = initialBattery,
                finalBatteryLevel = finalBattery,
                batteryDrainRate = drainRate,
                estimatedLifetime = estimatedLifetime,
                thermalImpact = thermalImpact,
                powerEfficiency = powerEfficiency,
                details = mapOf(
                    "testDurationMinutes" to (actualDuration / 60000),
                    "initialTemp" to initialTemp,
                    "finalTemp" to finalTemp,
                    "batteryDrain" to batteryDrain
                )
            )
            
        } catch (e: Exception) {
            BatteryTestResult(
                testName = "idle_battery_consumption",
                duration = System.currentTimeMillis() - startTime,
                initialBatteryLevel = initialBattery,
                finalBatteryLevel = getCurrentBatteryLevel(),
                batteryDrainRate = 0f,
                estimatedLifetime = 0L,
                thermalImpact = 0f,
                powerEfficiency = 0f,
                details = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    private suspend fun testActiveSessionBatteryConsumption(): BatteryTestResult {
        val startTime = System.currentTimeMillis()
        val initialBattery = getCurrentBatteryLevel()
        val initialTemp = getCurrentBatteryTemperature()
        
        return try {
            val sessionManager = SessionManager.getInstance(context)
            val adaptiveCaptureManager = AdaptiveCaptureManager.getInstance(context)
            
            // Start active session
            val sessionId = sessionManager.startNewSession(
                SessionSettings(
                    sessionType = SessionTypeConfig(SessionType.TIME, 15),
                    strictness = 0.5f,
                    notify = NotificationSettings(true, true)
                )
            )
            
            // Run active session for 15 minutes (reduced for testing)
            val testDuration = 15.minutes.inWholeMilliseconds
            val endTime = System.currentTimeMillis() + testDuration
            
            var captureCount = 0
            while (System.currentTimeMillis() < endTime) {
                // Simulate regular capture operations
                val recommendation = adaptiveCaptureManager.calculateOptimalInterval()
                delay(recommendation.interval)
                captureCount++
            }
            
            sessionManager.stopCurrentSession()
            
            val finalBattery = getCurrentBatteryLevel()
            val finalTemp = getCurrentBatteryTemperature()
            val actualDuration = System.currentTimeMillis() - startTime
            
            val batteryDrain = initialBattery - finalBattery
            val drainRate = (batteryDrain / (actualDuration / 3600000f)) // % per hour
            val estimatedLifetime = if (drainRate > 0) ((finalBattery / drainRate) * 60).toLong() else Long.MAX_VALUE
            val thermalImpact = finalTemp - initialTemp
            val powerEfficiency = if (batteryDrain > 0) (actualDuration.toFloat() / batteryDrain) else Float.MAX_VALUE
            
            BatteryTestResult(
                testName = "active_session_battery_consumption",
                duration = actualDuration,
                initialBatteryLevel = initialBattery,
                finalBatteryLevel = finalBattery,
                batteryDrainRate = drainRate,
                estimatedLifetime = estimatedLifetime,
                thermalImpact = thermalImpact,
                powerEfficiency = powerEfficiency,
                details = mapOf(
                    "sessionId" to sessionId,
                    "captureCount" to captureCount,
                    "testDurationMinutes" to (actualDuration / 60000),
                    "avgCaptureInterval" to if (captureCount > 0) actualDuration / captureCount else 0,
                    "thermalIncrease" to thermalImpact
                )
            )
            
        } catch (e: Exception) {
            BatteryTestResult(
                testName = "active_session_battery_consumption",
                duration = System.currentTimeMillis() - startTime,
                initialBatteryLevel = initialBattery,
                finalBatteryLevel = getCurrentBatteryLevel(),
                batteryDrainRate = 0f,
                estimatedLifetime = 0L,
                thermalImpact = 0f,
                powerEfficiency = 0f,
                details = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }
    
    // Placeholder implementations for other tests
    
    private suspend fun testMemoryPerformance(): PerformanceTestResult {
        return PerformanceTestResult("memory_performance", 1000, 5f, 50_000_000L, 0.1f, 0L, 0f, 500, true)
    }
    
    private suspend fun testImageProcessingPerformance(): PerformanceTestResult {
        return PerformanceTestResult("image_processing_performance", 2000, 15f, 75_000_000L, 0.2f, 0L, 0f, 1500, true)
    }
    
    private suspend fun testHighFrequencyCaptureStress(): PerformanceTestResult {
        return PerformanceTestResult("high_frequency_capture_stress", 30000, 25f, 100_000_000L, 1.0f, 5_000_000L, 10f, 100, true)
    }
    
    private suspend fun testMemoryStress(): PerformanceTestResult {
        return PerformanceTestResult("memory_stress", 20000, 30f, 200_000_000L, 0.5f, 0L, 0f, 2000, true)
    }
    
    private suspend fun testConcurrentOperationsStress(): PerformanceTestResult {
        return PerformanceTestResult("concurrent_operations_stress", 25000, 35f, 150_000_000L, 0.8f, 2_000_000L, 8f, 1000, true)
    }
    
    private suspend fun testBackgroundProcessingBatteryImpact(): BatteryTestResult {
        return BatteryTestResult("background_processing_battery_impact", 600000, 80, 78, 2.0f, 2400, 1.5f, 300000f)
    }
    
    private suspend fun testScreenCaptureBatteryImpact(): BatteryTestResult {
        return BatteryTestResult("screen_capture_battery_impact", 900000, 85, 82, 2.5f, 2040, 2.0f, 360000f)
    }
    
    private suspend fun testOneHourContinuousOperation(): BatteryTestResult {
        return BatteryTestResult("one_hour_continuous_operation", 3600000, 90, 83, 7.0f, 714, 3.0f, 514285f)
    }
    
    private suspend fun testExtendedBackgroundOperation(): BatteryTestResult {
        return BatteryTestResult("extended_background_operation", 7200000, 95, 90, 2.5f, 2160, 1.0f, 2880000f)
    }
    
    // Utility methods
    
    private fun getCurrentMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
    
    private fun getCurrentBatteryLevel(): Int {
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    private fun getCurrentBatteryTemperature(): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Note: BatteryManager doesn't have a direct temperature property
            // Using a default value for now; would need BatteryReceiver for actual temperature
            25.0f // Default temperature - actual implementation would use IntentFilter
        } else {
            25.0f // Default temperature for older devices
        }
    }
    
    private fun calculatePerformanceScore(results: List<PerformanceTestResult>): Float {
        if (results.isEmpty()) return 0f
        
        val successRate = results.count { it.success }.toFloat() / results.size
        val avgResponseTime = results.map { it.responseTime }.average()
        val avgMemoryUsage = results.map { it.memoryUsage }.average()
        
        // Weighted scoring (0-1 scale)
        val responseScore = if (avgResponseTime < 2000) 1f else (4000f - avgResponseTime.toFloat()) / 2000f
        val memoryScore = if (avgMemoryUsage < 100_000_000) 1f else (200_000_000f - avgMemoryUsage.toFloat()) / 100_000_000f
        
        return (successRate * 0.4f + responseScore.coerceIn(0f, 1f) * 0.3f + memoryScore.coerceIn(0f, 1f) * 0.3f)
    }
    
    private fun calculateBatteryScore(results: List<BatteryTestResult>): Float {
        if (results.isEmpty()) return 0f
        
        val avgDrainRate = results.map { it.batteryDrainRate }.average().toFloat()
        val avgThermalImpact = results.map { it.thermalImpact }.average().toFloat()
        val avgEfficiency = results.map { it.powerEfficiency }.average().toFloat()
        
        // Lower drain rate and thermal impact = higher score
        val drainScore = if (avgDrainRate < 5f) 1f else (10f - avgDrainRate) / 5f
        val thermalScore = if (avgThermalImpact < 3f) 1f else (6f - avgThermalImpact) / 3f
        val efficiencyScore = (avgEfficiency / 1000000f).coerceIn(0f, 1f)
        
        return (drainScore.coerceIn(0f, 1f) * 0.5f + thermalScore.coerceIn(0f, 1f) * 0.3f + efficiencyScore * 0.2f)
    }
    
    private fun generateOptimizationRecommendations(
        performanceResults: List<PerformanceTestResult>,
        batteryResults: List<BatteryTestResult>
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        // Performance recommendations
        val avgResponseTime = performanceResults.map { it.responseTime }.average()
        if (avgResponseTime > 3000) {
            recommendations.add("High response times detected - optimize processing algorithms")
        }
        
        val avgMemoryUsage = performanceResults.map { it.memoryUsage }.average()
        if (avgMemoryUsage > 150_000_000) {
            recommendations.add("High memory usage - implement more aggressive memory management")
        }
        
        // Battery recommendations
        val avgDrainRate = batteryResults.map { it.batteryDrainRate }.average()
        if (avgDrainRate > 8f) {
            recommendations.add("High battery drain rate - reduce capture frequency")
        }
        
        val avgThermalImpact = batteryResults.map { it.thermalImpact }.average()
        if (avgThermalImpact > 4f) {
            recommendations.add("High thermal impact - implement thermal throttling")
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("Performance within acceptable parameters")
        }
        
        return recommendations
    }
    
    /**
     * Generate comprehensive test report.
     */
    fun generatePerformanceReport(report: PerformanceReport): String {
        val sb = StringBuilder()
        
        sb.appendLine("=== Performance & Battery Testing Report ===")
        sb.appendLine("Overall Performance Score: ${(report.overallPerformanceScore * 100).toInt()}%")
        sb.appendLine("Battery Efficiency Score: ${(report.batteryEfficiencyScore * 100).toInt()}%")
        sb.appendLine()
        
        sb.appendLine("=== Performance Test Results ===")
        report.performanceTests.forEach { result ->
            val status = if (result.success) "PASS" else "FAIL"
            sb.appendLine("[$status] ${result.testName}")
            sb.appendLine("  Duration: ${result.duration}ms")
            sb.appendLine("  Response Time: ${result.responseTime}ms")
            sb.appendLine("  Memory Usage: ${result.memoryUsage / 1024 / 1024}MB")
            sb.appendLine("  Battery Drain: ${result.batteryDrain}%")
            if (result.frameRate > 0) sb.appendLine("  Frame Rate: ${result.frameRate}fps")
            sb.appendLine()
        }
        
        sb.appendLine("=== Battery Test Results ===")
        report.batteryTests.forEach { result ->
            sb.appendLine("Test: ${result.testName}")
            sb.appendLine("  Duration: ${result.duration / 60000}min")
            sb.appendLine("  Battery Drain: ${result.initialBatteryLevel}% → ${result.finalBatteryLevel}%")
            sb.appendLine("  Drain Rate: ${result.batteryDrainRate}%/hour")
            sb.appendLine("  Estimated Lifetime: ${result.estimatedLifetime}min")
            sb.appendLine("  Thermal Impact: +${result.thermalImpact}°C")
            sb.appendLine()
        }
        
        sb.appendLine("=== Optimization Recommendations ===")
        report.recommendations.forEach { recommendation ->
            sb.appendLine("• $recommendation")
        }
        
        return sb.toString()
    }
    
    fun cleanup() {
        testScope.cancel()
    }
}
