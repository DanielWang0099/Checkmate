package com.checkmate.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Enhanced error recovery utility with user-triggered retry mechanisms
 * and automatic recovery strategies.
 */
class ErrorRecoveryManager(private val context: Context) {
    
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val BASE_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 30000L
        private const val CIRCUIT_BREAKER_FAILURE_THRESHOLD = 5
        private const val CIRCUIT_BREAKER_RECOVERY_TIMEOUT_MS = 60000L
    }
    
    data class RetryConfig(
        val maxAttempts: Int = MAX_RETRY_ATTEMPTS,
        val baseDelayMs: Long = BASE_DELAY_MS,
        val maxDelayMs: Long = MAX_DELAY_MS,
        val exponentialBase: Double = 2.0,
        val jitter: Boolean = true,
        val strategy: RecoveryStrategy = RecoveryStrategy.EXPONENTIAL_BACKOFF
    )
    
    enum class RecoveryStrategy {
        EXPONENTIAL_BACKOFF,
        LINEAR_BACKOFF,
        FIXED_DELAY,
        IMMEDIATE_RETRY
    }
    
    enum class ErrorSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    data class ErrorContext(
        val operation: String,
        val sessionId: String? = null,
        val attemptCount: Int = 0,
        val lastSuccessTime: Long? = null,
        val errorType: String? = null,
        val networkState: NetworkState? = null
    )
    
    data class RecoveryAction(
        val actionType: String,
        val description: String,
        val autoExecutable: Boolean = false,
        val priority: Int = 1,
        val estimatedDurationMs: Long? = null,
        val userMessage: String? = null
    )
    
    data class EnhancedError(
        val originalError: Throwable,
        val severity: ErrorSeverity,
        val context: ErrorContext,
        val recoveryActions: List<RecoveryAction>,
        val userMessage: String,
        val canRetry: Boolean = true,
        val requiresUserAction: Boolean = false
    )
    
    enum class NetworkState {
        CONNECTED_WIFI,
        CONNECTED_CELLULAR,
        DISCONNECTED,
        LIMITED_CONNECTIVITY,
        UNKNOWN
    }
    
    data class CircuitBreakerState(
        var failureCount: Int = 0,
        var lastFailureTime: Long = 0L,
        var state: CircuitState = CircuitState.CLOSED,
        var nextAttemptTime: Long = 0L
    )
    
    enum class CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }
    
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()
    private val operationContexts = ConcurrentHashMap<String, ErrorContext>()
    
    private val _systemHealth = MutableLiveData<SystemHealth>()
    val systemHealth: LiveData<SystemHealth> = _systemHealth
    
    private val _recoveryActions = MutableSharedFlow<RecoveryAction>()
    val recoveryActions: SharedFlow<RecoveryAction> = _recoveryActions.asSharedFlow()
    
    private val recoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class SystemHealth(
        val isOnline: Boolean = true,
        val networkState: NetworkState = NetworkState.UNKNOWN,
        val serviceMode: ServiceMode = ServiceMode.FULL_SERVICE,
        val activeErrors: Int = 0,
        val lastHealthCheck: Long = System.currentTimeMillis(),
        val recoveryInProgress: Boolean = false
    )
    
    enum class ServiceMode {
        FULL_SERVICE,
        REDUCED_FEATURES,
        OFFLINE_MODE,
        EMERGENCY_MODE
    }
    
    init {
        // Start health monitoring
        startHealthMonitoring()
        
        // Initialize system health
        _systemHealth.value = SystemHealth()
    }
    
    /**
     * Execute operation with automatic error recovery
     */
    suspend fun <T> executeWithRecovery(
        operation: suspend () -> T,
        operationName: String,
        sessionId: String? = null,
        retryConfig: RetryConfig = RetryConfig(),
        onError: ((EnhancedError) -> Unit)? = null,
        onRetry: ((Int, Long) -> Unit)? = null
    ): Result<T> = withContext(Dispatchers.IO) {
        
        val operationKey = "$operationName${sessionId?.let { "_$it" } ?: ""}"
        var currentContext = operationContexts[operationKey] ?: ErrorContext(
            operation = operationName,
            sessionId = sessionId,
            networkState = getCurrentNetworkState()
        )
        
        repeat(retryConfig.maxAttempts) { attemptIndex ->
            try {
                // Check circuit breaker
                if (!isCircuitBreakerAllowingRequests(operationName)) {
                    throw Exception("Circuit breaker is open for $operationName")
                }
                
                val result = operation()
                
                // Success - reset circuit breaker and clean up
                resetCircuitBreaker(operationName)
                operationContexts.remove(operationKey)
                
                return@withContext Result.success(result)
                
            } catch (e: Exception) {
                currentContext = currentContext.copy(
                    attemptCount = attemptIndex + 1,
                    errorType = e::class.simpleName,
                    networkState = getCurrentNetworkState()
                )
                operationContexts[operationKey] = currentContext
                
                updateCircuitBreaker(operationName)
                
                val enhancedError = createEnhancedError(e, currentContext)
                onError?.invoke(enhancedError)
                
                // If this is the last attempt, return the error
                if (attemptIndex == retryConfig.maxAttempts - 1) {
                    operationContexts.remove(operationKey)
                    return@withContext Result.failure(enhancedError.originalError)
                }
                
                // Calculate delay and attempt recovery
                val delay = calculateRetryDelay(attemptIndex, retryConfig)
                onRetry?.invoke(attemptIndex + 1, delay)
                
                // Attempt automatic recovery
                val autoRecovered = attemptAutomaticRecovery(enhancedError)
                if (!autoRecovered) {
                    delay(delay)
                }
            }
        }
        
        // This should not be reached due to the return statements above
        Result.failure(Exception("Max retry attempts exceeded"))
    }
    
    /**
     * Manually retry a failed operation
     */
    suspend fun retryOperation(
        operation: suspend () -> Any,
        operationName: String,
        sessionId: String? = null
    ): Result<Any> {
        return executeWithRecovery(
            operation = operation,
            operationName = operationName,
            sessionId = sessionId,
            retryConfig = RetryConfig(maxAttempts = 1)
        )
    }
    
    /**
     * Execute a specific recovery action
     */
    suspend fun executeRecoveryAction(
        action: RecoveryAction,
        context: ErrorContext
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (action.actionType) {
                "check_connectivity" -> checkConnectivity()
                "restart_websocket" -> restartWebSocketConnection(context.sessionId)
                "clear_cache" -> clearApplicationCache()
                "refresh_session" -> refreshSession(context.sessionId)
                "switch_to_offline_mode" -> switchToOfflineMode()
                "retry_with_backoff" -> {
                    delay(action.estimatedDurationMs ?: 5000L)
                    true
                }
                "manual_intervention" -> {
                    // Emit action for UI to handle
                    _recoveryActions.emit(action)
                    false // User action required
                }
                else -> {
                    // Unknown action type
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get available recovery actions for an error
     */
    fun getRecoveryActions(error: Throwable, context: ErrorContext): List<RecoveryAction> {
        val actions = mutableListOf<RecoveryAction>()
        
        when (error) {
            is java.net.UnknownHostException,
            is java.net.ConnectException -> {
                actions.addAll(listOf(
                    RecoveryAction(
                        actionType = "check_connectivity",
                        description = "Check network connectivity",
                        autoExecutable = true,
                        priority = 5,
                        estimatedDurationMs = 2000L,
                        userMessage = "Checking your internet connection..."
                    ),
                    RecoveryAction(
                        actionType = "retry_with_backoff",
                        description = "Retry with exponential backoff",
                        autoExecutable = true,
                        priority = 4,
                        estimatedDurationMs = 10000L
                    ),
                    RecoveryAction(
                        actionType = "switch_to_offline_mode",
                        description = "Switch to offline mode",
                        autoExecutable = false,
                        priority = 2,
                        userMessage = "Would you like to continue in offline mode?"
                    )
                ))
            }
            
            is java.net.SocketTimeoutException -> {
                actions.addAll(listOf(
                    RecoveryAction(
                        actionType = "retry_with_backoff",
                        description = "Retry with longer timeout",
                        autoExecutable = true,
                        priority = 5,
                        estimatedDurationMs = 15000L
                    ),
                    RecoveryAction(
                        actionType = "check_connectivity",
                        description = "Check connection quality",
                        autoExecutable = true,
                        priority = 3
                    )
                ))
            }
            
            else -> {
                // Generic recovery actions
                actions.addAll(listOf(
                    RecoveryAction(
                        actionType = "retry_with_backoff",
                        description = "Retry the operation",
                        autoExecutable = true,
                        priority = 3,
                        estimatedDurationMs = 5000L
                    ),
                    RecoveryAction(
                        actionType = "manual_intervention",
                        description = "Manual retry required",
                        autoExecutable = false,
                        priority = 1,
                        userMessage = "Tap to retry manually"
                    )
                ))
            }
        }
        
        return actions.sortedByDescending { it.priority }
    }
    
    /**
     * Get current system health
     */
    fun getCurrentSystemHealth(): SystemHealth {
        return _systemHealth.value ?: SystemHealth()
    }
    
    /**
     * Reset circuit breaker for operation
     */
    fun resetCircuitBreaker(operationName: String) {
        circuitBreakers[operationName]?.let { breaker ->
            breaker.failureCount = 0
            breaker.state = CircuitState.CLOSED
            breaker.nextAttemptTime = 0L
        }
    }
    
    private fun createEnhancedError(error: Throwable, context: ErrorContext): EnhancedError {
        val severity = determineSeverity(error, context)
        val recoveryActions = getRecoveryActions(error, context)
        val userMessage = generateUserMessage(error, context)
        
        return EnhancedError(
            originalError = error,
            severity = severity,
            context = context,
            recoveryActions = recoveryActions,
            userMessage = userMessage,
            canRetry = canRetryError(error),
            requiresUserAction = requiresUserAction(error)
        )
    }
    
    private fun determineSeverity(error: Throwable, context: ErrorContext): ErrorSeverity {
        return when {
            context.attemptCount >= 3 -> ErrorSeverity.CRITICAL
            error is SecurityException -> ErrorSeverity.HIGH
            error is java.net.UnknownHostException -> {
                if (context.attemptCount == 1) ErrorSeverity.MEDIUM else ErrorSeverity.HIGH
            }
            else -> ErrorSeverity.LOW
        }
    }
    
    private fun generateUserMessage(error: Throwable, context: ErrorContext): String {
        return when (error) {
            is java.net.UnknownHostException -> "Unable to connect. Please check your internet connection."
            is java.net.SocketTimeoutException -> "Connection timed out. Please try again."
            is SecurityException -> "Permission denied. Please check your app permissions."
            else -> "Something went wrong. We're working to fix it."
        }
    }
    
    private fun canRetryError(error: Throwable): Boolean {
        return when (error) {
            is SecurityException -> false
            is IllegalArgumentException -> false
            else -> true
        }
    }
    
    private fun requiresUserAction(error: Throwable): Boolean {
        return when (error) {
            is SecurityException -> true
            else -> false
        }
    }
    
    private suspend fun attemptAutomaticRecovery(error: EnhancedError): Boolean {
        val autoActions = error.recoveryActions.filter { it.autoExecutable }
        
        for (action in autoActions) {
            try {
                val success = executeRecoveryAction(action, error.context)
                if (success) {
                    return true
                }
            } catch (e: Exception) {
                // Continue to next action
            }
        }
        
        return false
    }
    
    private fun calculateRetryDelay(attemptIndex: Int, config: RetryConfig): Long {
        val delay = when (config.strategy) {
            RecoveryStrategy.EXPONENTIAL_BACKOFF -> {
                min(
                    config.baseDelayMs * config.exponentialBase.pow(attemptIndex).toLong(),
                    config.maxDelayMs
                )
            }
            RecoveryStrategy.LINEAR_BACKOFF -> {
                min(config.baseDelayMs * (attemptIndex + 1), config.maxDelayMs)
            }
            RecoveryStrategy.FIXED_DELAY -> config.baseDelayMs
            RecoveryStrategy.IMMEDIATE_RETRY -> 0L
        }
        
        return if (config.jitter) {
            (delay * (0.5 + Random.nextDouble() * 0.5)).toLong()
        } else {
            delay
        }
    }
    
    private fun getCurrentNetworkState(): NetworkState {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return NetworkState.DISCONNECTED
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkState.DISCONNECTED
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.CONNECTED_WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.CONNECTED_CELLULAR
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.LIMITED_CONNECTIVITY
            else -> NetworkState.DISCONNECTED
        }
    }
    
    private fun isCircuitBreakerAllowingRequests(operationName: String): Boolean {
        val breaker = circuitBreakers[operationName] ?: return true
        val currentTime = System.currentTimeMillis()
        
        return when (breaker.state) {
            CircuitState.CLOSED -> true
            CircuitState.OPEN -> {
                if (currentTime >= breaker.nextAttemptTime) {
                    breaker.state = CircuitState.HALF_OPEN
                    true
                } else {
                    false
                }
            }
            CircuitState.HALF_OPEN -> true
        }
    }
    
    private fun updateCircuitBreaker(operationName: String) {
        val breaker = circuitBreakers.getOrPut(operationName) { CircuitBreakerState() }
        breaker.failureCount++
        breaker.lastFailureTime = System.currentTimeMillis()
        
        if (breaker.failureCount >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
            breaker.state = CircuitState.OPEN
            breaker.nextAttemptTime = System.currentTimeMillis() + CIRCUIT_BREAKER_RECOVERY_TIMEOUT_MS
        }
    }
    
    private fun startHealthMonitoring() {
        recoveryScope.launch {
            while (true) {
                try {
                    val networkState = getCurrentNetworkState()
                    val isOnline = networkState != NetworkState.DISCONNECTED
                    
                    val serviceMode = when {
                        !isOnline -> ServiceMode.OFFLINE_MODE
                        networkState == NetworkState.LIMITED_CONNECTIVITY -> ServiceMode.REDUCED_FEATURES
                        else -> ServiceMode.FULL_SERVICE
                    }
                    
                    _systemHealth.postValue(
                        SystemHealth(
                            isOnline = isOnline,
                            networkState = networkState,
                            serviceMode = serviceMode,
                            activeErrors = operationContexts.size,
                            lastHealthCheck = System.currentTimeMillis()
                        )
                    )
                    
                    delay(10000L) // Check every 10 seconds
                } catch (e: Exception) {
                    // Continue monitoring even if health check fails
                    delay(30000L) // Wait longer on error
                }
            }
        }
    }
    
    // Recovery action implementations
    private suspend fun checkConnectivity(): Boolean = withContext(Dispatchers.IO) {
        getCurrentNetworkState() != NetworkState.DISCONNECTED
    }
    
    private suspend fun restartWebSocketConnection(sessionId: String?): Boolean {
        // This would integrate with your WebSocketClient
        return try {
            // Implementation depends on your WebSocket architecture
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun clearApplicationCache(): Boolean {
        return try {
            // Clear relevant application caches
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun refreshSession(sessionId: String?): Boolean {
        return try {
            // Refresh session data
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun switchToOfflineMode(): Boolean {
        _systemHealth.postValue(
            _systemHealth.value?.copy(
                serviceMode = ServiceMode.OFFLINE_MODE
            ) ?: SystemHealth(serviceMode = ServiceMode.OFFLINE_MODE)
        )
        return true
    }
    
    fun cleanup() {
        recoveryScope.cancel()
    }
}
