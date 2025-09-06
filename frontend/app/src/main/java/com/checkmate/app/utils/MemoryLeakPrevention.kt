    package com.checkmate.app.utils

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Memory Leak Prevention and Management System.
 * Implements Step 12: Performance & Battery Optimization - Memory leak prevention.
 */
class MemoryLeakPrevention private constructor(private val context: Context) : DefaultLifecycleObserver {
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Memory tracking
    private val allocatedObjects = ConcurrentHashMap<String, WeakReference<Any>>()
    private val bitmapReferences = ConcurrentHashMap<String, WeakReference<Bitmap>>()
    private val memorySnapshots = mutableListOf<MemorySnapshot>()
    
    // Configuration
    private val memoryCheckIntervalMs = 30000L // 30 seconds
    private val maxMemoryUsageMB = 200 // 200MB threshold
    private val garbageCollectionThreshold = 0.8f // 80% memory usage
    
    private var isMonitoring = false
    private var lastGCTime = System.currentTimeMillis()
    
    data class MemorySnapshot(
        val timestamp: Long,
        val totalMemoryMB: Long,
        val usedMemoryMB: Long,
        val freeMemoryMB: Long,
        val heapSize: Long,
        val allocatedObjects: Int,
        val bitmapCount: Int,
        val gcCount: Long
    )
    
    data class MemoryReport(
        val currentSnapshot: MemorySnapshot,
        val memoryTrend: MemoryTrend,
        val leakSuspects: List<LeakSuspect>,
        val recommendations: List<String>
    )
    
    enum class MemoryTrend {
        DECREASING, STABLE, INCREASING, CRITICAL
    }
    
    data class LeakSuspect(
        val objectId: String,
        val type: String,
        val ageMs: Long,
        val suspicionLevel: Float
    )
    
    companion object {
        @Volatile
        private var INSTANCE: MemoryLeakPrevention? = null
        
        fun getInstance(context: Context): MemoryLeakPrevention {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryLeakPrevention(context.applicationContext).also { 
                    INSTANCE = it 
                    ProcessLifecycleOwner.get().lifecycle.addObserver(it)
                }
            }
        }
    }
    
    init {
        startMemoryMonitoring()
    }
    
    /**
     * Register an object for memory leak tracking.
     */
    fun trackObject(objectId: String, obj: Any) {
        allocatedObjects[objectId] = WeakReference(obj)
        Timber.d("Tracking object: $objectId (${obj::class.simpleName})")
    }
    
    /**
     * Unregister an object from memory tracking.
     */
    fun untrackObject(objectId: String) {
        allocatedObjects.remove(objectId)
        Timber.d("Untracked object: $objectId")
    }
    
    /**
     * Register bitmap for memory management.
     */
    fun trackBitmap(bitmapId: String, bitmap: Bitmap) {
        bitmapReferences[bitmapId] = WeakReference(bitmap)
        Timber.d("Tracking bitmap: $bitmapId (${bitmap.byteCount} bytes)")
    }
    
    /**
     * Properly recycle and untrack bitmap.
     */
    fun recycleBitmap(bitmapId: String) {
        bitmapReferences[bitmapId]?.get()?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
                Timber.d("Recycled bitmap: $bitmapId")
            }
        }
        bitmapReferences.remove(bitmapId)
    }
    
    /**
     * Force garbage collection if memory usage is high.
     */
    fun requestGarbageCollection(force: Boolean = false) {
        val currentMemory = getCurrentMemoryUsage()
        val memoryUsageRatio = currentMemory.usedMemoryMB.toFloat() / currentMemory.totalMemoryMB
        
        val shouldGC = force || 
                      memoryUsageRatio > garbageCollectionThreshold ||
                      (System.currentTimeMillis() - lastGCTime > 120000) // 2 minutes
        
        if (shouldGC) {
            Timber.d("Requesting garbage collection (memory usage: ${(memoryUsageRatio * 100).toInt()}%)")
            
            // Clean up weak references first
            cleanupWeakReferences()
            
            // Request GC
            System.gc()
            System.runFinalization()
            
            lastGCTime = System.currentTimeMillis()
            
            // Log memory after GC
            val afterGC = getCurrentMemoryUsage()
            Timber.d("Memory after GC: ${afterGC.usedMemoryMB}MB / ${afterGC.totalMemoryMB}MB")
        }
    }
    
    /**
     * Get current memory usage snapshot.
     */
    fun getCurrentMemoryUsage(): MemorySnapshot {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalMemoryMB = memInfo.totalMem / (1024 * 1024)
        val freeMemoryMB = memInfo.availMem / (1024 * 1024)
        val usedMemoryMB = totalMemoryMB - freeMemoryMB
        
        val runtime = Runtime.getRuntime()
        val heapSize = runtime.totalMemory()
        
        return MemorySnapshot(
            timestamp = System.currentTimeMillis(),
            totalMemoryMB = totalMemoryMB,
            usedMemoryMB = usedMemoryMB,
            freeMemoryMB = freeMemoryMB,
            heapSize = heapSize,
            allocatedObjects = allocatedObjects.size,
            bitmapCount = bitmapReferences.size,
            gcCount = Debug.getNativeHeapAllocatedSize() // Proxy for GC count
        )
    }
    
    /**
     * Analyze potential memory leaks.
     */
    fun analyzeMemoryLeaks(): MemoryReport {
        val currentSnapshot = getCurrentMemoryUsage()
        val memoryTrend = calculateMemoryTrend()
        val leakSuspects = identifyLeakSuspects()
        val recommendations = generateRecommendations(currentSnapshot, memoryTrend, leakSuspects)
        
        return MemoryReport(
            currentSnapshot = currentSnapshot,
            memoryTrend = memoryTrend,
            leakSuspects = leakSuspects,
            recommendations = recommendations
        )
    }
    
    /**
     * Emergency memory cleanup procedure.
     */
    fun emergencyMemoryCleanup() {
        Timber.w("Performing emergency memory cleanup")
        
        // 1. Clean up all tracked bitmaps
        val bitmapIds = bitmapReferences.keys.toList()
        bitmapIds.forEach { recycleBitmap(it) }
        
        // 2. Clear image caches (implementation specific)
        clearImageCaches()
        
        // 3. Clean up weak references
        cleanupWeakReferences()
        
        // 4. Force garbage collection
        System.gc()
        System.runFinalization()
        
        // 5. Clear non-essential data
        clearNonEssentialData()
        
        val afterCleanup = getCurrentMemoryUsage()
        Timber.w("Emergency cleanup complete. Memory: ${afterCleanup.usedMemoryMB}MB / ${afterCleanup.totalMemoryMB}MB")
    }
    
    /**
     * Get memory optimization suggestions.
     */
    fun getMemoryOptimizationSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        val currentMemory = getCurrentMemoryUsage()
        val memoryUsageRatio = currentMemory.usedMemoryMB.toFloat() / currentMemory.totalMemoryMB
        
        when {
            memoryUsageRatio > 0.9f -> {
                suggestions.add("Critical memory usage - emergency cleanup recommended")
                suggestions.add("Stop non-essential services")
                suggestions.add("Clear all image caches")
            }
            memoryUsageRatio > 0.8f -> {
                suggestions.add("High memory usage detected")
                suggestions.add("Reduce capture quality")
                suggestions.add("Clear old cached images")
            }
            memoryUsageRatio > 0.7f -> {
                suggestions.add("Moderate memory usage")
                suggestions.add("Consider reducing background processing")
            }
        }
        
        if (currentMemory.bitmapCount > 10) {
            suggestions.add("High number of bitmaps in memory (${currentMemory.bitmapCount})")
        }
        
        if (currentMemory.allocatedObjects > 1000) {
            suggestions.add("High number of tracked objects (${currentMemory.allocatedObjects})")
        }
        
        return suggestions
    }
    
    override fun onStart(owner: LifecycleOwner) {
        isMonitoring = true
        Timber.d("Memory monitoring started")
    }
    
    override fun onStop(owner: LifecycleOwner) {
        isMonitoring = false
        Timber.d("Memory monitoring paused")
    }
    
    private fun startMemoryMonitoring() {
        monitoringScope.launch {
            while (isActive) {
                try {
                    if (isMonitoring) {
                        val snapshot = getCurrentMemoryUsage()
                        memorySnapshots.add(snapshot)
                        
                        // Keep only recent snapshots (last 24 hours)
                        val cutoffTime = System.currentTimeMillis() - 86400000L
                        memorySnapshots.removeAll { it.timestamp < cutoffTime }
                        
                        // Check for memory pressure
                        val memoryUsageRatio = snapshot.usedMemoryMB.toFloat() / snapshot.totalMemoryMB
                        
                        when {
                            memoryUsageRatio > 0.95f -> {
                                Timber.w("Critical memory pressure detected")
                                emergencyMemoryCleanup()
                            }
                            memoryUsageRatio > garbageCollectionThreshold -> {
                                requestGarbageCollection()
                            }
                        }
                        
                        // Clean up weak references periodically
                        cleanupWeakReferences()
                    }
                    
                    delay(memoryCheckIntervalMs)
                    
                } catch (e: Exception) {
                    Timber.e(e, "Error in memory monitoring")
                }
            }
        }
    }
    
    private fun cleanupWeakReferences() {
        // Clean up dead object references
        val deadObjects = allocatedObjects.filter { it.value.get() == null }.keys
        deadObjects.forEach { allocatedObjects.remove(it) }
        
        // Clean up dead bitmap references
        val deadBitmaps = bitmapReferences.filter { it.value.get() == null }.keys
        deadBitmaps.forEach { bitmapReferences.remove(it) }
        
        if (deadObjects.isNotEmpty() || deadBitmaps.isNotEmpty()) {
            Timber.d("Cleaned up ${deadObjects.size} dead objects and ${deadBitmaps.size} dead bitmaps")
        }
    }
    
    private fun calculateMemoryTrend(): MemoryTrend {
        if (memorySnapshots.size < 3) return MemoryTrend.STABLE
        
        val recent = memorySnapshots.takeLast(5)
        val memoryChanges = recent.zipWithNext { a, b ->
            (b.usedMemoryMB - a.usedMemoryMB).toFloat() / a.usedMemoryMB
        }
        
        val avgChange = memoryChanges.average()
        
        return when {
            avgChange > 0.1f -> MemoryTrend.CRITICAL
            avgChange > 0.05f -> MemoryTrend.INCREASING
            avgChange < -0.05f -> MemoryTrend.DECREASING
            else -> MemoryTrend.STABLE
        }
    }
    
    private fun identifyLeakSuspects(): List<LeakSuspect> {
        val suspects = mutableListOf<LeakSuspect>()
        val currentTime = System.currentTimeMillis()
        
        // Check for long-lived objects
        allocatedObjects.forEach { (id, ref) ->
            ref.get()?.let { obj ->
                val ageMs = currentTime - (id.substringAfterLast("_").toLongOrNull() ?: currentTime)
                
                if (ageMs > 300000) { // 5 minutes
                    val suspicionLevel = when {
                        ageMs > 1800000 -> 1.0f // 30 minutes - very suspicious
                        ageMs > 900000 -> 0.8f  // 15 minutes - suspicious
                        ageMs > 600000 -> 0.6f  // 10 minutes - moderately suspicious
                        else -> 0.4f
                    }
                    
                    suspects.add(LeakSuspect(
                        objectId = id,
                        type = obj::class.simpleName ?: "Unknown",
                        ageMs = ageMs,
                        suspicionLevel = suspicionLevel
                    ))
                }
            }
        }
        
        // Check for old bitmaps
        bitmapReferences.forEach { (id, ref) ->
            ref.get()?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    val ageMs = currentTime - (id.substringAfterLast("_").toLongOrNull() ?: currentTime)
                    
                    if (ageMs > 120000) { // 2 minutes for bitmaps
                        suspects.add(LeakSuspect(
                            objectId = id,
                            type = "Bitmap",
                            ageMs = ageMs,
                            suspicionLevel = 0.9f // Bitmaps should be short-lived
                        ))
                    }
                }
            }
        }
        
        return suspects.sortedByDescending { it.suspicionLevel }
    }
    
    private fun generateRecommendations(
        snapshot: MemorySnapshot,
        trend: MemoryTrend,
        suspects: List<LeakSuspect>
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        // Memory usage recommendations
        val memoryUsageRatio = snapshot.usedMemoryMB.toFloat() / snapshot.totalMemoryMB
        if (memoryUsageRatio > 0.8f) {
            recommendations.add("High memory usage detected - consider reducing image quality")
            recommendations.add("Clear image caches more frequently")
        }
        
        // Trend recommendations
        when (trend) {
            MemoryTrend.INCREASING -> {
                recommendations.add("Memory usage is increasing - monitor for leaks")
                recommendations.add("Consider more aggressive garbage collection")
            }
            MemoryTrend.CRITICAL -> {
                recommendations.add("Critical memory trend - investigate potential leaks immediately")
                recommendations.add("Emergency cleanup may be required")
            }
            else -> {}
        }
        
        // Suspect recommendations
        if (suspects.isNotEmpty()) {
            recommendations.add("${suspects.size} potential memory leak(s) detected")
            suspects.take(3).forEach { suspect ->
                recommendations.add("Investigate ${suspect.type} object: ${suspect.objectId}")
            }
        }
        
        // Bitmap recommendations
        if (snapshot.bitmapCount > 5) {
            recommendations.add("High number of bitmaps in memory - ensure proper recycling")
        }
        
        return recommendations
    }
    
    private fun clearImageCaches() {
        // Implementation would clear various image caches
        // This is a placeholder for actual cache clearing logic
        Timber.d("Clearing image caches")
    }
    
    private fun clearNonEssentialData() {
        // Clear non-essential data structures
        if (memorySnapshots.size > 10) {
            memorySnapshots.removeFirst()
        }
        
        Timber.d("Cleared non-essential data")
    }
    
    fun shutdown() {
        monitoringScope.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        
        // Final cleanup
        bitmapReferences.keys.toList().forEach { recycleBitmap(it) }
        allocatedObjects.clear()
        memorySnapshots.clear()
        
        Timber.d("Memory leak prevention shutdown complete")
    }
}
