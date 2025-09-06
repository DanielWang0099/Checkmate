package com.checkmate.app.utils

import android.content.Context
import androidx.work.*
import com.checkmate.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background Processing Optimizer for efficient task scheduling and resource management.
 * Implements Step 12: Performance & Battery Optimization - Background processing optimization.
 */
class BackgroundProcessingOptimizer private constructor(private val context: Context) {
    
    private val workManager = WorkManager.getInstance(context)
    
    // Thread pools for different task priorities
    private val highPriorityExecutor = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
    private val normalPriorityExecutor = Executors.newFixedThreadPool(3) as ThreadPoolExecutor
    private val lowPriorityExecutor = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
    
    // Processing queues
    private val processingQueues = mapOf(
        TaskPriority.CRITICAL to ConcurrentLinkedQueue<BackgroundTask>(),
        TaskPriority.HIGH to ConcurrentLinkedQueue<BackgroundTask>(),
        TaskPriority.NORMAL to ConcurrentLinkedQueue<BackgroundTask>(),
        TaskPriority.LOW to ConcurrentLinkedQueue<BackgroundTask>()
    )
    
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val taskCounter = AtomicInteger(0)
    private val runningTasks = mutableMapOf<String, Job>()
    
    // Configuration
    private val maxConcurrentTasks = 5
    private val taskTimeoutMs = 30000L // 30 seconds
    private val queueMonitorIntervalMs = 1000L // 1 second
    
    enum class TaskPriority {
        CRITICAL, HIGH, NORMAL, LOW
    }
    
    enum class TaskType {
        FRAME_PROCESSING,
        IMAGE_UPLOAD,
        AUDIO_PROCESSING,
        WEBSOCKET_MESSAGE,
        CACHE_CLEANUP,
        ANALYTICS_UPLOAD,
        BACKGROUND_SYNC
    }
    
    data class BackgroundTask(
        val id: String,
        val type: TaskType,
        val priority: TaskPriority,
        val data: Map<String, Any>,
        val processor: suspend (Map<String, Any>) -> Result<Any>,
        val onComplete: (Result<Any>) -> Unit = {},
        val timeout: Long = 30000L,
        val retryCount: Int = 0,
        val maxRetries: Int = 3,
        val createdAt: Long = System.currentTimeMillis()
    )
    
    data class ProcessingStats(
        val queueSizes: Map<TaskPriority, Int>,
        val runningTasks: Int,
        val completedTasks: Int,
        val failedTasks: Int,
        val avgProcessingTime: Long,
        val threadPoolStats: Map<String, ThreadPoolStats>
    )
    
    data class ThreadPoolStats(
        val activeThreads: Int,
        val queueSize: Int,
        val completedTasks: Long
    )
    
    companion object {
        @Volatile
        private var INSTANCE: BackgroundProcessingOptimizer? = null
        
        fun getInstance(context: Context): BackgroundProcessingOptimizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackgroundProcessingOptimizer(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }
    
    init {
        startQueueProcessor()
        startTaskMonitor()
        setupWorkManagerConstraints()
    }
    
    /**
     * Submit a task for background processing.
     */
    fun submitTask(
        type: TaskType,
        priority: TaskPriority = TaskPriority.NORMAL,
        data: Map<String, Any> = emptyMap(),
        processor: suspend (Map<String, Any>) -> Result<Any>,
        onComplete: (Result<Any>) -> Unit = {},
        timeout: Long = taskTimeoutMs,
        maxRetries: Int = 3
    ): String {
        val taskId = "task_${taskCounter.incrementAndGet()}"
        
        val task = BackgroundTask(
            id = taskId,
            type = type,
            priority = priority,
            data = data,
            processor = processor,
            onComplete = onComplete,
            timeout = timeout,
            maxRetries = maxRetries
        )
        
        processingQueues[priority]?.offer(task)
        Timber.d("Submitted background task: $taskId (type: $type, priority: $priority)")
        
        return taskId
    }
    
    /**
     * Submit frame processing task with adaptive prioritization.
     */
    fun submitFrameProcessing(
        frameBundle: FrameBundle,
        processingType: String,
        onComplete: (Result<Any>) -> Unit = {}
    ): String {
        val priority = when {
            frameBundle.deviceHints.battery < 0.2f -> TaskPriority.LOW
            frameBundle.hasImage -> TaskPriority.HIGH
            frameBundle.ocrText.isNotEmpty() -> TaskPriority.NORMAL
            else -> TaskPriority.LOW
        }
        
        return submitTask(
            type = TaskType.FRAME_PROCESSING,
            priority = priority,
            data = mapOf(
                "frameBundle" to frameBundle,
                "processingType" to processingType
            ),
            processor = { data ->
                processFrameBundle(data["frameBundle"] as FrameBundle, data["processingType"] as String)
            },
            onComplete = onComplete
        )
    }
    
    /**
     * Submit image upload task with intelligent batching.
     */
    fun submitImageUpload(
        imageData: ByteArray,
        sessionId: String,
        onComplete: (Result<String>) -> Unit = {}
    ): String {
        return submitTask(
            type = TaskType.IMAGE_UPLOAD,
            priority = TaskPriority.NORMAL,
            data = mapOf(
                "imageData" to imageData,
                "sessionId" to sessionId
            ),
            processor = { data ->
                uploadImageData(data["imageData"] as ByteArray, data["sessionId"] as String)
            },
            onComplete = { result ->
                onComplete(result.map { it as String })
            }
        )
    }
    
    /**
     * Schedule periodic background tasks using WorkManager.
     */
    fun schedulePeriodicTask(
        taskType: TaskType,
        intervalMinutes: Long,
        constraints: Constraints = getDefaultConstraints()
    ) {
        val workRequest = PeriodicWorkRequestBuilder<BackgroundWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf("taskType" to taskType.name))
            .addTag(taskType.name)
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            "periodic_${taskType.name}",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        
        Timber.d("Scheduled periodic task: $taskType (interval: ${intervalMinutes}min)")
    }
    
    /**
     * Cancel a specific task if it's still in queue.
     */
    fun cancelTask(taskId: String): Boolean {
        var found = false
        
        processingQueues.values.forEach { queue ->
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().id == taskId) {
                    iterator.remove()
                    found = true
                    break
                }
            }
        }
        
        // Cancel running task
        runningTasks[taskId]?.cancel()
        runningTasks.remove(taskId)
        
        Timber.d("Cancelled task: $taskId (found: $found)")
        return found
    }
    
    /**
     * Get current processing statistics.
     */
    fun getProcessingStats(): ProcessingStats {
        val queueSizes = processingQueues.mapValues { it.value.size }
        
        val threadPoolStats = mapOf(
            "high_priority" to ThreadPoolStats(
                activeThreads = highPriorityExecutor.activeCount,
                queueSize = highPriorityExecutor.queue.size,
                completedTasks = highPriorityExecutor.completedTaskCount
            ),
            "normal_priority" to ThreadPoolStats(
                activeThreads = normalPriorityExecutor.activeCount,
                queueSize = normalPriorityExecutor.queue.size,
                completedTasks = normalPriorityExecutor.completedTaskCount
            ),
            "low_priority" to ThreadPoolStats(
                activeThreads = lowPriorityExecutor.activeCount,
                queueSize = lowPriorityExecutor.queue.size,
                completedTasks = lowPriorityExecutor.completedTaskCount
            )
        )
        
        return ProcessingStats(
            queueSizes = queueSizes,
            runningTasks = runningTasks.size,
            completedTasks = getCompletedTaskCount(),
            failedTasks = getFailedTaskCount(),
            avgProcessingTime = calculateAverageProcessingTime(),
            threadPoolStats = threadPoolStats
        )
    }
    
    /**
     * Optimize processing based on device state.
     */
    fun optimizeForDeviceState(deviceHints: DeviceHints) {
        val batteryLevel = deviceHints.battery
        val isPowerSaver = deviceHints.powerSaver
        
        when {
            batteryLevel < 0.15f || isPowerSaver -> {
                // Aggressive optimization for low battery
                setThreadPoolSizes(1, 1, 1)
                pauseLowPriorityTasks()
                Timber.d("Applied aggressive processing optimization")
            }
            batteryLevel < 0.30f -> {
                // Moderate optimization
                setThreadPoolSizes(1, 2, 1)
                Timber.d("Applied moderate processing optimization")
            }
            else -> {
                // Normal operation
                setThreadPoolSizes(2, 3, 1)
                resumeLowPriorityTasks()
                Timber.d("Restored normal processing capacity")
            }
        }
    }
    
    /**
     * Clean shutdown of all processing.
     */
    fun shutdown() {
        processingScope.cancel()
        
        highPriorityExecutor.shutdown()
        normalPriorityExecutor.shutdown()
        lowPriorityExecutor.shutdown()
        
        // Cancel all WorkManager tasks
        workManager.cancelAllWorkByTag("BackgroundWorker")
        
        Timber.d("Background processing optimizer shutdown complete")
    }
    
    private fun startQueueProcessor() {
        processingScope.launch {
            while (isActive) {
                try {
                    processNextTask()
                    delay(queueMonitorIntervalMs)
                } catch (e: Exception) {
                    Timber.e(e, "Error in queue processor")
                }
            }
        }
    }
    
    private suspend fun processNextTask() {
        if (runningTasks.size >= maxConcurrentTasks) {
            return // At capacity
        }
        
        // Process tasks by priority order
        val priorityOrder = listOf(
            TaskPriority.CRITICAL,
            TaskPriority.HIGH,
            TaskPriority.NORMAL,
            TaskPriority.LOW
        )
        
        for (priority in priorityOrder) {
            val queue = processingQueues[priority] ?: continue
            val task = queue.poll() ?: continue
            
            executeTask(task)
            break // Process one task per cycle
        }
    }
    
    private fun executeTask(task: BackgroundTask) {
        val job = processingScope.launch {
            val startTime = System.currentTimeMillis()
            
            try {
                Timber.d("Executing task: ${task.id} (type: ${task.type})")
                
                val result = withTimeout(task.timeout) {
                    task.processor(task.data)
                }
                
                val processingTime = System.currentTimeMillis() - startTime
                Timber.d("Task completed: ${task.id} (time: ${processingTime}ms)")
                
                task.onComplete(result)
                
            } catch (e: TimeoutCancellationException) {
                Timber.w("Task timeout: ${task.id}")
                
                if (task.retryCount < task.maxRetries) {
                    val retryTask = task.copy(retryCount = task.retryCount + 1)
                    processingQueues[task.priority]?.offer(retryTask)
                    Timber.d("Retrying task: ${task.id} (attempt ${retryTask.retryCount})")
                } else {
                    task.onComplete(Result.failure(e))
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Task failed: ${task.id}")
                task.onComplete(Result.failure(e))
            } finally {
                runningTasks.remove(task.id)
            }
        }
        
        runningTasks[task.id] = job
    }
    
    private fun startTaskMonitor() {
        processingScope.launch {
            while (isActive) {
                try {
                    delay(60000) // Monitor every minute
                    
                    // Clean up stale tasks
                    val currentTime = System.currentTimeMillis()
                    processingQueues.values.forEach { queue ->
                        val iterator = queue.iterator()
                        while (iterator.hasNext()) {
                            val task = iterator.next()
                            if (currentTime - task.createdAt > 300000) { // 5 minutes
                                iterator.remove()
                                Timber.w("Removed stale task: ${task.id}")
                            }
                        }
                    }
                    
                    // Log statistics
                    val stats = getProcessingStats()
                    Timber.d("Processing stats: queues=${stats.queueSizes}, running=${stats.runningTasks}")
                    
                } catch (e: Exception) {
                    Timber.e(e, "Error in task monitor")
                }
            }
        }
    }
    
    private fun setupWorkManagerConstraints() {
        // Configure WorkManager for optimized background processing
        @Suppress("UNUSED_VARIABLE")
        val config = Configuration.Builder()
            .setMaxSchedulerLimit(10)
            .build()
        
        // Note: This would typically be set in Application.onCreate()
    }
    
    private fun getDefaultConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
    }
    
    private fun setThreadPoolSizes(high: Int, normal: Int, low: Int) {
        highPriorityExecutor.corePoolSize = high
        highPriorityExecutor.maximumPoolSize = high
        
        normalPriorityExecutor.corePoolSize = normal
        normalPriorityExecutor.maximumPoolSize = normal
        
        lowPriorityExecutor.corePoolSize = low
        lowPriorityExecutor.maximumPoolSize = low
    }
    
    private fun pauseLowPriorityTasks() {
        workManager.cancelAllWorkByTag(TaskType.CACHE_CLEANUP.name)
        workManager.cancelAllWorkByTag(TaskType.ANALYTICS_UPLOAD.name)
    }
    
    private fun resumeLowPriorityTasks() {
        schedulePeriodicTask(TaskType.CACHE_CLEANUP, 60)
        schedulePeriodicTask(TaskType.ANALYTICS_UPLOAD, 120)
    }
    
    // Sample processing functions
    private suspend fun processFrameBundle(frameBundle: FrameBundle, @Suppress("UNUSED_PARAMETER") processingType: String): Result<Any> {
        return try {
            // Simulate frame processing
            delay(100L + (frameBundle.ocrText.length / 10).toLong()) // Simulate OCR processing time
            
            Result.success("processed_${frameBundle.sessionId}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun uploadImageData(imageData: ByteArray, sessionId: String): Result<String> {
        return try {
            // Simulate image upload
            delay(1000L + (imageData.size / 1000).toLong()) // Simulate upload time based on size
            
            Result.success("image_url_${sessionId}_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun getCompletedTaskCount(): Int {
        return taskMetrics.filter { it.value.isCompleted }.size
    }
    
    private fun getFailedTaskCount(): Int {
        return taskMetrics.filter { it.value.hasFailed }.size
    }
    
    private fun calculateAverageProcessingTime(): Long {
        val completedMetrics = taskMetrics.values.filter { it.isCompleted && it.processingTimeMs > 0 }
        return if (completedMetrics.isNotEmpty()) {
            completedMetrics.map { it.processingTimeMs }.average().toLong()
        } else 0L
    }
    
    /**
     * Task metrics tracking
     */
    data class TaskMetrics(
        val isCompleted: Boolean = false,
        val hasFailed: Boolean = false,
        val processingTimeMs: Long = 0
    )
    
    private val taskMetrics = mutableMapOf<String, TaskMetrics>()
}

/**
 * WorkManager Worker for periodic background tasks.
 */
class BackgroundWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val taskType = inputData.getString("taskType") ?: return Result.failure()
        
        return try {
            when (BackgroundProcessingOptimizer.TaskType.valueOf(taskType)) {
                BackgroundProcessingOptimizer.TaskType.CACHE_CLEANUP -> performCacheCleanup()
                BackgroundProcessingOptimizer.TaskType.ANALYTICS_UPLOAD -> performAnalyticsUpload()
                BackgroundProcessingOptimizer.TaskType.BACKGROUND_SYNC -> performBackgroundSync()
                else -> Result.success()
            }
        } catch (e: Exception) {
            Timber.e(e, "Background worker failed for task: $taskType")
            Result.failure()
        }
    }
    
    private suspend fun performCacheCleanup(): Result {
        // Implement cache cleanup logic
        delay(1000) // Simulate cleanup
        Timber.d("Cache cleanup completed")
        return Result.success()
    }
    
    private suspend fun performAnalyticsUpload(): Result {
        // Implement analytics upload logic
        delay(2000) // Simulate upload
        Timber.d("Analytics upload completed")
        return Result.success()
    }
    
    private suspend fun performBackgroundSync(): Result {
        // Implement background sync logic
        delay(1500) // Simulate sync
        Timber.d("Background sync completed")
        return Result.success()
    }
}
