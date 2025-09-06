package com.checkmate.app.utils

import android.content.Context
import com.checkmate.app.data.*
import com.checkmate.app.network.CheckmateWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket Connection Pool Manager for optimized connection reuse and management.
 * Implements Step 12: Performance & Battery Optimization - WebSocket connection pooling.
 */
class ConnectionPoolManager private constructor(private val context: Context) {
    
    private val activeConnections = ConcurrentHashMap<String, PooledConnection>()
    private val connectionQueue = MutableSharedFlow<ConnectionRequest>()
    private val poolScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionCounter = AtomicInteger(0)
    
    // Configuration
    private val maxPoolSize = 5
    private val maxIdleTime = 60_000L // 60 seconds
    private val connectionTimeout = 10_000L // 10 seconds
    private val healthCheckInterval = 30_000L // 30 seconds
    
    data class PooledConnection(
        val id: String,
        val client: CheckmateWebSocketClient,
        val sessionId: String?,
        val lastUsed: Long,
        val isIdle: Boolean,
        val createTime: Long,
        val useCount: AtomicInteger = AtomicInteger(0)
    ) {
        fun markUsed() {
            useCount.incrementAndGet()
        }
        
        fun isStale(): Boolean {
            return System.currentTimeMillis() - lastUsed > 60_000L
        }
        
        fun isHealthy(): Boolean {
            return client.isConnected() && !isStale()
        }
    }
    
    data class ConnectionRequest(
        val sessionId: String,
        val priority: ConnectionPriority = ConnectionPriority.NORMAL,
        val callback: (Result<CheckmateWebSocketClient>) -> Unit
    )
    
    enum class ConnectionPriority {
        LOW, NORMAL, HIGH, CRITICAL
    }
    
    companion object {
        @Volatile
        private var INSTANCE: ConnectionPoolManager? = null
        
        fun getInstance(context: Context): ConnectionPoolManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectionPoolManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    init {
        startConnectionManager()
        startHealthMonitor()
    }
    
    /**
     * Get or create a WebSocket connection for the session.
     */
    suspend fun getConnection(
        sessionId: String,
        priority: ConnectionPriority = ConnectionPriority.NORMAL
    ): Result<CheckmateWebSocketClient> = withContext(Dispatchers.IO) {
        try {
            // Check for existing healthy connection
            activeConnections[sessionId]?.let { pooled ->
                if (pooled.isHealthy()) {
                    pooled.markUsed()
                    Timber.d("Reusing existing connection for session: $sessionId")
                    return@withContext Result.success(pooled.client)
                } else {
                    // Remove stale connection
                    removeConnection(sessionId)
                }
            }
            
            // Check pool capacity
            if (activeConnections.size >= maxPoolSize) {
                cleanupIdleConnections()
                
                if (activeConnections.size >= maxPoolSize) {
                    if (priority == ConnectionPriority.CRITICAL) {
                        // Force remove oldest connection
                        removeOldestConnection()
                    } else {
                        return@withContext Result.failure(
                            Exception("Connection pool exhausted (${activeConnections.size}/$maxPoolSize)")
                        )
                    }
                }
            }
            
            // Create new connection
            val connectionId = "conn_${connectionCounter.incrementAndGet()}"
            val client = createWebSocketClient(sessionId)
            
            // Attempt connection with timeout
            val connectResult = withTimeoutOrNull(connectionTimeout) {
                client.connect()
                
                // Wait for connection to be established
                var attempts = 0
                while (!client.isConnected() && attempts < 50) {
                    delay(100)
                    attempts++
                }
                
                client.isConnected()
            }
            
            if (connectResult == true) {
                val pooledConnection = PooledConnection(
                    id = connectionId,
                    client = client,
                    sessionId = sessionId,
                    lastUsed = System.currentTimeMillis(),
                    isIdle = false,
                    createTime = System.currentTimeMillis()
                )
                
                activeConnections[sessionId] = pooledConnection
                pooledConnection.markUsed()
                
                Timber.d("Created new pooled connection for session: $sessionId")
                Result.success(client)
            } else {
                client.disconnect()
                Result.failure(Exception("Failed to establish WebSocket connection within timeout"))
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error getting pooled connection for session: $sessionId")
            Result.failure(e)
        }
    }
    
    /**
     * Release a connection back to the pool.
     */
    fun releaseConnection(sessionId: String, forceClose: Boolean = false) {
        activeConnections[sessionId]?.let { pooled ->
            if (forceClose || !pooled.isHealthy()) {
                removeConnection(sessionId)
            } else {
                // Mark as idle for potential reuse
                val updatedConnection = pooled.copy(
                    lastUsed = System.currentTimeMillis(),
                    isIdle = true
                )
                activeConnections[sessionId] = updatedConnection
                Timber.d("Released connection to pool: $sessionId")
            }
        }
    }
    
    /**
     * Get pool statistics for monitoring.
     */
    fun getPoolStats(): PoolStats {
        val totalConnections = activeConnections.size
        val idleConnections = activeConnections.values.count { it.isIdle }
        val activeConnections = totalConnections - idleConnections
        val avgUseCount = if (totalConnections > 0) {
            this.activeConnections.values.map { it.useCount.get() }.average()
        } else 0.0
        
        return PoolStats(
            totalConnections = totalConnections,
            activeConnections = activeConnections,
            idleConnections = idleConnections,
            maxPoolSize = maxPoolSize,
            avgUseCount = avgUseCount
        )
    }
    
    data class PoolStats(
        val totalConnections: Int,
        val activeConnections: Int,
        val idleConnections: Int,
        val maxPoolSize: Int,
        val avgUseCount: Double
    )
    
    /**
     * Close all connections and cleanup.
     */
    fun shutdown() {
        poolScope.cancel()
        
        activeConnections.values.forEach { pooled ->
            try {
                pooled.client.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Error disconnecting pooled client")
            }
        }
        
        activeConnections.clear()
        Timber.d("Connection pool shutdown complete")
    }
    
    private fun createWebSocketClient(sessionId: String): CheckmateWebSocketClient {
        return CheckmateWebSocketClient(
            sessionId = sessionId,
            onNotification = { notification ->
                // Forward to session manager
                SessionManager.getInstance(context).let { sessionManager ->
                    poolScope.launch {
                        sessionManager.handleNotification(notification)
                    }
                }
            },
            onSessionEnd = { reason ->
                SessionManager.getInstance(context).let { sessionManager ->
                    poolScope.launch {
                        sessionManager.handleSessionEnd(reason)
                    }
                }
            },
            onSessionStatus = { statusData ->
                SessionManager.getInstance(context).let { sessionManager ->
                    poolScope.launch {
                        sessionManager.handleSessionStatus(statusData)
                    }
                }
            },
            onConnectionStateChange = { connectionState ->
                SessionManager.getInstance(context).let { sessionManager ->
                    poolScope.launch {
                        sessionManager.updateConnectionState(connectionState)
                    }
                }
                
                // Handle connection state for pool management
                if (connectionState == ConnectionState.DISCONNECTED) {
                    removeConnection(sessionId)
                }
            },
            onError = { errorResponse ->
                SessionManager.getInstance(context).let { sessionManager ->
                    poolScope.launch {
                        sessionManager.handleStructuredError(errorResponse)
                    }
                }
            }
        )
    }
    
    private fun removeConnection(sessionId: String) {
        activeConnections.remove(sessionId)?.let { pooled ->
            try {
                pooled.client.disconnect()
                Timber.d("Removed connection from pool: $sessionId")
            } catch (e: Exception) {
                Timber.e(e, "Error removing connection from pool")
            }
        }
    }
    
    private fun removeOldestConnection() {
        val oldest = activeConnections.values.minByOrNull { it.createTime }
        oldest?.let { pooled ->
            activeConnections.entries.find { it.value == pooled }?.let { entry ->
                removeConnection(entry.key)
                Timber.d("Removed oldest connection from pool: ${entry.key}")
            }
        }
    }
    
    private fun cleanupIdleConnections() {
        val staleConnections = activeConnections.filter { (_, pooled) ->
            pooled.isIdle && pooled.isStale()
        }.keys
        
        staleConnections.forEach { sessionId ->
            removeConnection(sessionId)
        }
        
        if (staleConnections.isNotEmpty()) {
            Timber.d("Cleaned up ${staleConnections.size} stale connections")
        }
    }
    
    private fun startConnectionManager() {
        poolScope.launch {
            connectionQueue.collect { request ->
                try {
                    val result = getConnection(request.sessionId, request.priority)
                    request.callback(result)
                } catch (e: Exception) {
                    request.callback(Result.failure(e))
                }
            }
        }
    }
    
    private fun startHealthMonitor() {
        poolScope.launch {
            while (isActive) {
                try {
                    delay(healthCheckInterval)
                    
                    val unhealthyConnections = activeConnections.filter { (_, pooled) ->
                        !pooled.isHealthy()
                    }.keys
                    
                    unhealthyConnections.forEach { sessionId ->
                        removeConnection(sessionId)
                    }
                    
                    if (unhealthyConnections.isNotEmpty()) {
                        Timber.d("Health monitor removed ${unhealthyConnections.size} unhealthy connections")
                    }
                    
                } catch (e: Exception) {
                    Timber.e(e, "Error in connection health monitor")
                }
            }
        }
    }
}
